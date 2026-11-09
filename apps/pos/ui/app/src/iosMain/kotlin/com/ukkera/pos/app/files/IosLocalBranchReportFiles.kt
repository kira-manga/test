@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ukkera.pos.app.files

import com.ukkera.pos.app.LocalBranchReportExportLifetime
import com.ukkera.pos.app.LocalBranchReportFileDelivery
import com.ukkera.pos.app.LocalBranchReportFileDestinationKind
import com.ukkera.pos.app.LocalBranchReportFileProblem
import com.ukkera.pos.app.LocalBranchReportFileReceipt
import com.ukkera.pos.app.LocalReportCommitGate
import com.ukkera.pos.app.LocalReportCommitStart
import com.ukkera.pos.app.LocalReportFileFailure
import com.ukkera.pos.app.LocalReportLinkOutcome
import com.ukkera.pos.app.LocalReportNativeChoice
import com.ukkera.pos.app.LocalReportReceiptDisk
import com.ukkera.pos.app.LocalReportRecoveredPublication
import com.ukkera.pos.app.MAX_LOCAL_REPORT_CSV_BYTES
import com.ukkera.pos.app.fileFailure
import com.ukkera.pos.app.useCleared
import com.ukkera.pos.app.validLocalReportFileName
import com.ukkera.pos.security.sha256
import kotlin.coroutines.resume
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSError
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UITextAutocorrectionType
import platform.UIKit.UITextField
import platform.UIKit.UITextFieldDelegateProtocol
import platform.UIKit.UITextSpellCheckingType
import platform.UIKit.UIViewController
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.accessibilityLabel
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.flock
import platform.posix.free
import platform.posix.fsync
import platform.posix.fstat
import platform.posix.getuid
import platform.posix.link
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.realpath
import platform.posix.stat
import platform.posix.write

/**
 * Private local report roots. LIVE is the installed production root; TRAINING is a disjoint
 * root the live chooser/store never reads. Both are app-sandbox Application Support directories
 * with the no-backup resource flag, not Documents, iCloud, a file provider or a share extension.
 */
enum class IosLocalBranchReportStorageV1(internal val directoryName: String) {
    LIVE("branch-report-export-v1"),
    TRAINING("branch-report-export-training-v1"),
}

/**
 * Actual foreground iPhone/iPad name/destination review into the app's private no-backup root.
 * The alert only selects a bounded name; it never grants a path, provider URI, permission or
 * filesystem capability. Ending the bound host or backgrounding the app prevents a new commit.
 */
class IosLocalBranchReportFiles(
    private val storage: IosLocalBranchReportStorageV1 = IosLocalBranchReportStorageV1.LIVE,
) : LocalBranchReportFileDelivery(), AutoCloseable {
    private val gateLock = NSRecursiveLock()
    internal override val commitGate: LocalReportCommitGate = object : LocalReportCommitGate {
        override fun <T> locked(block: () -> T): T = gateLock.withLock(block)
    }

    private class Choice(private val continuation: CancellableContinuation<LocalReportNativeChoice?>) {
        private val done = AtomicReference<Any?>(null)
        private val completed = Any()
        fun resume(value: LocalReportNativeChoice?) {
            if (done.compareAndSet(null, completed) && continuation.isActive) continuation.resume(value)
        }
        fun fail(failure: Throwable) {
            if (done.compareAndSet(null, completed) && continuation.isActive) continuation.resumeWith(Result.failure(failure))
        }
    }

    /** Retains the text delegate because UIKit's delegate property is weak. */
    private class Dialog(val alert: UIAlertController, val delegate: NameDelegate, val choice: Choice)
    private class NameDelegate(private val changed: (String) -> Unit) : NSObject(), UITextFieldDelegateProtocol {
        override fun textFieldDidChangeSelection(textField: UITextField) {
            changed(textField.text.orEmpty())
        }
    }

    private class Host(val controller: UIViewController) {
        val alive = AtomicReference(true)
        val dialog = AtomicReference<Dialog?>(null)
    }

    private val host = AtomicReference<Host?>(null)
    private val closed = AtomicReference(false)
    private val observers: List<Any> = listOf(
        platform.Foundation.NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillResignActiveNotification, null, null,
        ) { invalidateForBackground() },
        platform.Foundation.NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidEnterBackgroundNotification, null, null,
        ) { invalidateForBackground() },
    )

    override val bound: Boolean get() = !closed.value && host.value?.let { it.alive.value } == true

    /**
     * A new binding for each visible foreground UIViewController lifetime. Ending it is
     * irreversible. Swift must call this on the main thread after the controller's view appears
     * and close it on view disappearance/scene inactivity; the app notification observer also
     * retires it before the scene becomes inactive.
     */
    fun bind(controller: UIViewController): AutoCloseable {
        check(NSThread.currentThread.isMainThread)
        val next = Host(controller)
        var retired: Dialog? = null
        commitGate.locked {
            check(!closed.value) { "iOS report file delivery is closed" }
            val previous = host.getAndSet(next)
            if (previous != null) {
                previous.alive.value = false
                retired = previous.dialog.getAndSet(null)
            }
        }
        retired?.let(::dismissDialog)
        return AutoCloseable { retireHost(next) }
    }

    override fun hostCurrent(host: Any): Boolean =
        !closed.value && host is Host && this.host.value === host && host.alive.value

    override suspend fun nativeChoice(
        suggestedName: String,
        lifetime: LocalBranchReportExportLifetime,
    ): LocalReportNativeChoice? {
        disk { Unit } // actual private root/lock admission, never an acceptance flag
        val captured = host.value ?: fileFailure(LocalBranchReportFileProblem.NOT_BOUND)
        return suspendCancellableCoroutine { continuation ->
            val choice = Choice(continuation)
            continuation.invokeOnCancellation {
                val dialog = captured.dialog.getAndSet(null)
                dialog?.let { dismissDialog(it) }
            }
            onMain {
                if (!continuation.isActive || !hostCurrent(captured) || !lifetime.isCurrent()) {
                    choice.resume(null)
                    return@onMain
                }
                presentReview(captured, choice, suggestedName, lifetime)
            }
        }
    }

    private fun presentReview(
        boundHost: Host,
        choice: Choice,
        suggestedName: String,
        lifetime: LocalBranchReportExportLifetime,
    ) {
        check(NSThread.currentThread.isMainThread)
        if (!hostCurrent(boundHost) || !lifetime.isCurrent()) {
            choice.resume(null)
            return
        }
        if (boundHost.dialog.value != null) {
            choice.fail(LocalReportFileFailure(LocalBranchReportFileProblem.NOT_BOUND))
            return
        }
        val presenter = visiblePresenter(boundHost.controller)
        if (presenter == null) {
            choice.resume(null)
            return
        }
        val training = storage == IosLocalBranchReportStorageV1.TRAINING
        val chooseRef = AtomicReference<UIAlertAction?>(null)
        val delegate = NameDelegate { text -> chooseRef.value?.enabled = validLocalReportFileName(text) }
        val alert = UIAlertController.alertControllerWithTitle(
            if (training) "Save training CSV / حفظ CSV تدريبي" else "Save local CSV / حفظ CSV محلي",
            if (training)
                "Destination: Ukkera POS SYNTHETIC TRAINING report files in this app's private on-device storage. NOT LIVE. Choose an Arabic or English name ending .csv. Existing files are never replaced. No cloud provider, upload, open or share.\n" +
                    "الوجهة: ملفات تقارير تدريب اصطناعي خاصة بـ Ukkera POS في مساحة تخزين خاصة على هذا الجهاز فقط. ليست مباشرة. اختر اسمًا بالعربية أو الإنجليزية ينتهي بـ .csv. لا تُستبدل الملفات الموجودة ولا يتم رفعها أو فتحها أو مشاركتها."
            else
                "Destination: Ukkera POS local report files in this app's private on-device storage. Choose an Arabic or English name ending .csv. Existing files are never replaced. No cloud provider, upload, open or share.\n" +
                    "الوجهة: ملفات تقارير Ukkera POS المحلية في مساحة تخزين خاصة على هذا الجهاز فقط. اختر اسمًا بالعربية أو الإنجليزية ينتهي بـ .csv. لا تُستبدل الملفات الموجودة ولا يتم رفعها أو فتحها أو مشاركتها.",
            UIAlertControllerStyleAlert,
        )
        var field: UITextField? = null
        alert.addTextFieldWithConfigurationHandler { edit ->
            edit?.let { name ->
                field = name
                name.text = suggestedName
                name.placeholder = "report-section-1234.csv"
                name.autocorrectionType = UITextAutocorrectionType.UITextAutocorrectionTypeNo
                name.spellCheckingType = UITextSpellCheckingType.UITextSpellCheckingTypeNo
                name.accessibilityLabel = "CSV file name / اسم ملف CSV"
                name.delegate = delegate
            }
        }
        val holder = AtomicReference<Dialog?>(null)
        val choose = UIAlertAction.actionWithTitle("Choose / اختيار", UIAlertActionStyleDefault) {
            val dialog = holder.value
            if (dialog != null) detachDialog(boundHost, dialog)
            val name = field?.text.orEmpty()
            if (hostCurrent(boundHost) && lifetime.isCurrent() && validLocalReportFileName(name)) {
                choice.resume(LocalReportNativeChoice(boundHost, name, directoryLabel,
                    LocalBranchReportFileDestinationKind.IOS_LOCAL_DOCUMENT))
            } else if (hostCurrent(boundHost) && lifetime.isCurrent()) {
                choice.fail(LocalReportFileFailure(LocalBranchReportFileProblem.DESTINATION_REJECTED))
            } else {
                choice.resume(null)
            }
        }
        choose.enabled = validLocalReportFileName(suggestedName)
        chooseRef.value = choose
        alert.addAction(choose)
        alert.addAction(UIAlertAction.actionWithTitle("Cancel / إلغاء", UIAlertActionStyleCancel) {
            holder.value?.let { detachDialog(boundHost, it) }
            choice.resume(null)
        })
        alert.preferredAction = choose
        val dialog = Dialog(alert, delegate, choice)
        holder.value = dialog
        if (!boundHost.dialog.compareAndSet(null, dialog)) {
            choice.fail(LocalReportFileFailure(LocalBranchReportFileProblem.NOT_BOUND))
            return
        }
        try { presenter.presentViewController(alert, true, null) }
        catch (_: Exception) {
            boundHost.dialog.compareAndSet(dialog, null)
            choice.fail(LocalReportFileFailure(LocalBranchReportFileProblem.NOT_BOUND))
        }
    }

    private fun visiblePresenter(controller: UIViewController): UIViewController? {
        var current = controller
        var depth = 0
        while (true) {
            val presented = current.presentedViewController ?: break
            if (++depth > 8) return null
            current = presented
        }
        if (current.isBeingPresented() || current.isBeingDismissed()) return null
        if (current.viewIfLoaded?.window == null) return null
        return current
    }

    private fun detachDialog(boundHost: Host, dialog: Dialog) {
        boundHost.dialog.compareAndSet(dialog, null)
    }

    private fun dismissDialog(dialog: Dialog) {
        onMain {
            dialog.alert.presentingViewController?.dismissViewControllerAnimated(false, null)
            dialog.choice.resume(null)
        }
    }

    private fun retireHost(expected: Host) {
        val retired = commitGate.locked {
            expected.alive.value = false
            host.compareAndSet(expected, null)
            expected.dialog.getAndSet(null)
        }
        retired?.let(::dismissDialog)
    }

    private fun invalidateForBackground() {
        val retired = commitGate.locked {
            val captured = host.getAndSet(null)
            captured?.alive?.value = false
            captured?.dialog?.getAndSet(null)
        }
        retired?.let(::dismissDialog)
    }

    private val directoryLabel: String
        get() = if (storage == IosLocalBranchReportStorageV1.TRAINING)
            "Ukkera POS · SYNTHETIC TRAINING report files · NOT LIVE / ملفات تقارير تدريب اصطناعي · ليست مباشرة"
        else "Ukkera POS · local report files / ملفات التقارير المحلية"

    override suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T = withContext(Dispatchers.Default) {
        if (closed.value) fileFailure(LocalBranchReportFileProblem.NOT_BOUND)
        IosReportFileStorage.access(storage, block)
    }

    override fun close() {
        val retired = commitGate.locked {
            closed.value = true
            val current = host.getAndSet(null)
            current?.alive?.value = false
            current?.dialog?.getAndSet(null)
        }
        retired?.let(::dismissDialog)
        observers.forEach { platform.Foundation.NSNotificationCenter.defaultCenter.removeObserver(it) }
        IosReportFileStorage.close(storage)
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.currentThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }
}

/** Shared by the actual UI bridge and any later read-only published-file surface in this process. */
private object IosReportFileStorage {
    private val mutex = NSRecursiveLock()
    private val stores = mutableMapOf<IosLocalBranchReportStorageV1, IosReportReceiptDisk>()
    private val initializationFailed = mutableSetOf<IosLocalBranchReportStorageV1>()

    fun <T> access(storage: IosLocalBranchReportStorageV1, block: (IosReportReceiptDisk) -> T): T = mutex.withLock {
        if (storage in initializationFailed) fileFailure(LocalBranchReportFileProblem.STORAGE_UNCERTAIN)
        val actual = stores[storage] ?: try {
            IosReportReceiptDisk.open(storage).also { stores[storage] = it }
        } catch (failure: Throwable) {
            // Never reopen after an uncertain initialization/close failure in this process.
            initializationFailed += storage
            throw failure
        }
        actual.current()
        block(actual).also { actual.current() }
    }

    fun close(storage: IosLocalBranchReportStorageV1) {
        mutex.withLock {
            val store = stores.remove(storage) ?: return@withLock
            if (!store.close()) initializationFailed += storage
        }
    }
}

private class IosReportReceiptDisk private constructor(
    private val rawHome: String,
    private val rawSupport: String,
    private val home: String,
    private val support: String,
    private val root: String,
    private val uid: UInt,
    private val keys: Map<String, String>,
    lockDescriptor: Int,
) : LocalReportReceiptDisk() {
    private var lockFd = lockDescriptor
    private var lockHeld = true
    private var closed = false
    private var closureUncertain = false

    private fun resolve(relative: String): String {
        check(relative.isEmpty() || relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it })
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    fun current() {
        check(!closed && !closureUncertain) { "iOS report storage closure remains unresolved" }
        check(lockHeld && lockFd >= 0)
        check(NSHomeDirectory() == rawHome) { "iOS app container changed" }
        check(applicationSupportPath() == rawSupport) { "iOS Application Support changed" }
        iosFrameworkDirectory(home, uid)
        iosFrameworkDirectory(support, uid)
        privateDirectory(root, uid)
        keys.forEach { (name, key) ->
            val at = if (name.isEmpty()) root else "$root/$name"
            if (name == "custody.lock") privateFile(at, uid) else privateDirectory(at, uid)
            check(iosIdentity(iosStat(at)) == key)
        }
        val held = fstatInfo(lockFd)
        check(isRegularFile(held) && held.uid == uid && held.mode and 0x1FF == 0x180)
        check(iosIdentity(held) == keys["custody.lock"])
        check(entries("", 3).toSet() == setOf("custody.lock", "receipts", "published"))
        checkExcludedFromBackup(root)
    }

    override fun entries(directory: String, maximum: Int): List<String> {
        val at = resolve(directory)
        privateDirectory(at, uid)
        check(maximum >= 0)
        val stream = opendir(at) ?: iosFailure("opendir")
        val names = mutableListOf<String>()
        var primary: Throwable? = null
        try {
            while (true) {
                val entry = readdir(stream) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                check(names.size < maximum) { "iOS report directory exceeds its bounded entry count" }
                names += name
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            if (closedir(stream) != 0) {
                val failure = IllegalStateException("iOS report directory close failed")
                if (primary != null) primary.addSuppressed(failure) else throw failure
            }
        }
        return names
    }

    override fun makeDirectory(path: String) {
        val at = resolve(path)
        check(at != root) { "The private report root is not a receipt directory" }
        privateDirectory(at.substringBeforeLast('/'), uid)
        if (mkdir(at, 0x1C0u) != 0) iosFailure("mkdir")
        privateDirectory(at, uid)
    }

    override fun exists(path: String): Boolean = iosExists(resolve(path))

    override fun identity(path: String): String = iosIdentity(iosStat(resolve(path)))

    override fun readBounded(path: String, maximum: Int): ByteArray {
        val at = resolve(path)
        privateFile(at, uid)
        val before = iosStat(at)
        val descriptor = open(at, O_RDONLY or O_NOFOLLOW or O_CLOEXEC)
        if (descriptor < 0) iosFailure("open")
        var retained: ByteArray? = null
        var primary: Throwable? = null
        try {
            val opened = fstatInfo(descriptor)
            check(isRegularFile(opened) && opened.uid == uid && opened.mode and 0x1FF == 0x180)
            check(iosIdentity(opened) == iosIdentity(before))
            val bytes = readDescriptor(descriptor, maximum)
            retained = bytes
            if (fsync(descriptor) != 0) iosFailure("fsync")
            privateFile(at, uid)
            check(iosIdentity(iosStat(at)) == iosIdentity(before))
            return bytes
        } catch (failure: Throwable) {
            primary = failure
            retained?.fill(0)
            throw failure
        } finally {
            try { closeChecked(descriptor) }
            catch (closeFailure: Throwable) {
                retained?.fill(0)
                if (primary != null) primary.addSuppressed(closeFailure) else throw closeFailure
            }
        }
    }

    override fun writeExclusive(path: String, bytes: ByteArray) {
        check(writeExclusivePrepared(path, bytes, null))
    }

    override fun writeExclusiveAtCommitStart(
        path: String,
        bytes: ByteArray,
        commitStart: LocalReportCommitStart,
    ): Boolean = writeExclusivePrepared(path, bytes, commitStart)

    private fun writeExclusivePrepared(
        path: String,
        bytes: ByteArray,
        commitStart: LocalReportCommitStart?,
    ): Boolean {
        val at = resolve(path)
        if (commitStart != null) current()
        privateDirectory(at.substringBeforeLast('/'), uid)
        val flags = O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC
        // Final preparation boundary: STARTED wins against a later end. A started open/write
        // failure throws and remains an uncertain native tail; it is never a retryable false.
        if (commitStart != null && !commitStart.admit()) return false
        val descriptor = open(at, flags, 0x180u)
        if (descriptor < 0) iosFailure("open")
        var primary: Throwable? = null
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count = bytes.usePinned {
                    write(descriptor, it.addressOf(offset), (bytes.size - offset).toULong())
                }
                if (count < 0L) {
                    if (errno == EINTR) continue
                    iosFailure("write")
                }
                check(count > 0L) { "iOS report write made no progress" }
                offset += count.toInt()
            }
            if (fsync(descriptor) != 0) iosFailure("fsync")
            val written = fstatInfo(descriptor)
            check(isRegularFile(written) && written.uid == uid && written.mode and 0x1FF == 0x180)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try { closeChecked(descriptor) }
            catch (closeFailure: Throwable) {
                if (primary != null) primary.addSuppressed(closeFailure) else throw closeFailure
            }
        }
        privateFile(at, uid)
        return true
    }

    override fun forceDirectory(path: String) {
        val at = resolve(path)
        forceIosPrivateDirectory(at, uid) { closeChecked(it) }
    }

    override fun linkComplete(
        receipt: LocalBranchReportFileReceipt,
        commitStart: LocalReportCommitStart,
    ): LocalReportLinkOutcome = try {
        verifySpool(receipt)
        current()
        privateDirectory("$root/published", uid)
        val source = resolve(spool(receipt.exportId))
        val destination = resolve(published(receipt.fileName))
        if (!commitStart.admit()) LocalReportLinkOutcome.NOT_ATTEMPTED else {
            // link(2) creates a complete name atomically and refuses ANY existing target.
            // It cannot replace a destination between a precheck and publication.
            if (link(source, destination) != 0) {
                if (errno == EEXIST) LocalReportLinkOutcome.NOT_ATTEMPTED else LocalReportLinkOutcome.UNCERTAIN
            } else if (verifyPublished(receipt)) {
                LocalReportLinkOutcome.DURABLE
            } else {
                LocalReportLinkOutcome.UNCERTAIN
            }
        }
    } catch (_: Exception) { LocalReportLinkOutcome.UNCERTAIN }

    override fun verifyPublished(receipt: LocalBranchReportFileReceipt): Boolean = try {
        val at = resolve(published(receipt.fileName))
        val original = resolve(spool(receipt.exportId))
        privateFile(at, uid)
        privateFile(original, uid)
        val destination = iosStat(at)
        check(iosIdentity(destination) == receipt.spoolIdentity)
        check(iosIdentity(destination) == iosIdentity(iosStat(original)))
        verifySpool(receipt)
        val source = checkNotNull(receipt.source)
        readBounded(published(receipt.fileName), MAX_LOCAL_REPORT_CSV_BYTES).useCleared {
            check(it.size == source.byteCount && sha256(it).value == source.contentSha256)
        }
        forceDirectory("published")
        true
    } catch (_: Exception) { false }

    override fun recoverPublication(receipt: LocalBranchReportFileReceipt): LocalReportRecoveredPublication =
        // This private app-sandbox hard link is the published object itself. There is no
        // second provider-admission boundary like Android DocumentsProvider.
        if (verifyPublished(receipt)) LocalReportRecoveredPublication.ALREADY_PUBLISHED_VERIFIED
        else LocalReportRecoveredPublication.NOT_VERIFIED

    fun close(): Boolean {
        if (closed) return !closureUncertain
        closed = true
        val descriptor = lockFd
        lockFd = -1
        if (descriptor >= 0) {
            if (lockHeld) {
                flock(descriptor, LOCK_UN)
                lockHeld = false
            }
            if (close(descriptor) != 0) closureUncertain = true
        }
        return !closureUncertain
    }

    private fun closeChecked(descriptor: Int) {
        if (close(descriptor) != 0) {
            closureUncertain = true
            throw IllegalStateException("iOS report descriptor close failed")
        }
    }

    private fun readDescriptor(descriptor: Int, maximum: Int): ByteArray {
        val buffer = ByteArray(maximum + 1)
        try {
            var size = 0
            while (size < buffer.size) {
                val count = buffer.usePinned {
                    read(descriptor, it.addressOf(size), (buffer.size - size).toULong())
                }
                if (count == 0L) break
                if (count < 0L) {
                    if (errno == EINTR) continue
                    iosFailure("read")
                }
                size += count.toInt()
            }
            check(size <= maximum) { "iOS report file exceeds its bounded size" }
            return buffer.copyOf(size)
        } finally { buffer.fill(0) }
    }

    companion object {
        fun open(storage: IosLocalBranchReportStorageV1): IosReportReceiptDisk {
            val uid = getuid()
            val rawHome = NSHomeDirectory()
            val rawSupport = applicationSupportPath() ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
            val home = canonicalIosPath(rawHome) ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
            if (!iosExists(rawSupport)) {
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    val url = NSURL(fileURLWithPath = rawSupport, isDirectory = true)
                    NSFileManager.defaultManager.createDirectoryAtURL(url, true, null, error.ptr)
                }
            }
            val support = canonicalIosPath(rawSupport) ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
            check(support.startsWith(home + "/")) { "iOS Application Support escaped the app sandbox" }
            iosFrameworkDirectory(support, uid)
            val parent = "$support/ukkera-pos"
            ensurePrivateDirectory(parent, uid, support)
            val requestedRoot = "$parent/${storage.directoryName}"
            ensurePrivateDirectory(requestedRoot, uid, null)
            val root = canonicalIosPath(requestedRoot) ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
            check(root.startsWith(parent + "/")) { "iOS report root escaped its private parent" }
            setExcludedFromBackup(parent)
            setExcludedFromBackup(root)
            checkExcludedFromBackup(root)
            forceIosPrivateDirectory(root, uid) { if (close(it) != 0) iosFailure("close") }
            for (name in listOf("receipts", "published")) {
                ensurePrivateDirectory("$root/$name", uid, null)
            }
            val lockPath = "$root/custody.lock"
            if (iosExists(lockPath)) privateFile(lockPath, uid)
            val descriptor = open(lockPath, O_RDWR or O_CREAT or O_NOFOLLOW or O_CLOEXEC, 0x180u)
            if (descriptor < 0) fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
            var locked = false
            try {
                val lockInfo = fstatInfo(descriptor)
                check(isRegularFile(lockInfo) && lockInfo.uid == uid && lockInfo.mode and 0x1FF == 0x180)
                if (flock(descriptor, LOCK_EX or LOCK_NB) != 0) fileFailure(LocalBranchReportFileProblem.STORAGE_UNCERTAIN)
                locked = true
                if (fsync(descriptor) != 0) iosFailure("fsync")
                forceIosPrivateDirectory(root, uid) { if (close(it) != 0) iosFailure("close") }
                val keys = listOf("", "receipts", "published", "custody.lock").associateWith { name ->
                    val at = if (name.isEmpty()) root else "$root/$name"
                    iosIdentity(iosStat(at))
                }
                return IosReportReceiptDisk(rawHome, rawSupport, home, support, root, uid, keys, descriptor).also {
                    it.current()
                    it.receipts()
                }
            } catch (failure: Throwable) {
                if (locked) flock(descriptor, LOCK_UN)
                if (close(descriptor) != 0) failure.addSuppressed(IllegalStateException("iOS report lock close failed"))
                throw failure
            }
        }
    }
}

private data class IosFileStat(
    val device: Int,
    val inode: ULong,
    val mode: Int,
    val uid: UInt,
    val gid: UInt,
    val links: Int,
    val size: Long,
)

private fun iosStat(path: String): IosFileStat = memScoped {
    val information = alloc<stat>()
    if (lstat(path, information.ptr) != 0) iosFailure("lstat")
    IosFileStat(information.st_dev, information.st_ino, information.st_mode.toInt(), information.st_uid,
        information.st_gid, information.st_nlink.toInt(), information.st_size)
}

private fun fstatInfo(descriptor: Int): IosFileStat = memScoped {
    val information = alloc<stat>()
    if (fstat(descriptor, information.ptr) != 0) iosFailure("fstat")
    IosFileStat(information.st_dev, information.st_ino, information.st_mode.toInt(), information.st_uid,
        information.st_gid, information.st_nlink.toInt(), information.st_size)
}

private fun iosIdentity(information: IosFileStat): String =
    "ios:${information.device}:${information.inode}".also { check(it.length <= 256) }
private fun isDirectory(information: IosFileStat): Boolean = information.mode and S_IFMT == S_IFDIR
private fun isRegularFile(information: IosFileStat): Boolean = information.mode and S_IFMT == S_IFREG

private fun iosExists(path: String): Boolean {
    val information = memScoped { alloc<stat>() }
    return if (lstat(path, information.ptr) == 0) true else {
        val code = errno
        if (code == ENOENT) false else iosFailure("lstat")
    }
}

private fun iosFailure(operation: String): Nothing =
    throw IllegalStateException("iOS report native operation failed: $operation errno=$errno")

private fun canonicalIosPath(path: String): String? {
    val resolved = realpath(path, null) ?: return null
    return try { resolved.toKString() } finally { free(resolved) }
}

private fun iosFrameworkDirectory(path: String, uid: UInt) {
    val information = iosStat(path)
    check(isDirectory(information) && information.uid == uid && information.mode and 0xE12 == 0 &&
        canonicalIosPath(path) == path) { "iOS framework directory custody changed" }
}

private fun privateDirectory(path: String, uid: UInt) {
    val information = iosStat(path)
    check(isDirectory(information) && information.uid == uid && information.mode and 0x1FF == 0x1C0 &&
        canonicalIosPath(path) == path) { "iOS private report directory custody changed" }
}

private fun privateFile(path: String, uid: UInt) {
    val information = iosStat(path)
    check(isRegularFile(information) && information.uid == uid && information.mode and 0x1FF == 0x180 &&
        information.links in 1..2 && canonicalIosPath(path) == path) { "iOS private report file custody changed" }
}

private fun ensurePrivateDirectory(path: String, uid: UInt, frameworkParent: String?) {
    if (!iosExists(path)) {
        val parent = path.substringBeforeLast('/')
        if (parent == frameworkParent) iosFrameworkDirectory(parent, uid) else privateDirectory(parent, uid)
        if (mkdir(path, 0x1C0u) != 0) iosFailure("mkdir")
        forceIosPrivateDirectory(path, uid) { if (close(it) != 0) iosFailure("close") }
    }
    privateDirectory(path, uid)
}

private inline fun forceIosPrivateDirectory(path: String, uid: UInt, closeDescriptor: (Int) -> Unit) {
    privateDirectory(path, uid)
    val before = iosStat(path)
    val descriptor = open(path, O_RDONLY or O_NOFOLLOW or O_CLOEXEC)
    if (descriptor < 0) iosFailure("open")
    var primary: Throwable? = null
    try {
        val opened = fstatInfo(descriptor)
        check(isDirectory(opened) && opened.uid == uid && opened.mode and 0x1FF == 0x1C0 &&
            iosIdentity(opened) == iosIdentity(before))
        if (fsync(descriptor) != 0) iosFailure("fsync")
        val after = iosStat(path)
        check(iosIdentity(after) == iosIdentity(before) && after.mode == before.mode && after.uid == before.uid &&
            after.gid == before.gid && canonicalIosPath(path) == path)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try { closeDescriptor(descriptor) }
        catch (closeFailure: Throwable) {
            if (primary != null) primary.addSuppressed(closeFailure) else throw closeFailure
        }
    }
}

private fun applicationSupportPath(): String? =
    NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String

private fun setExcludedFromBackup(path: String) {
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val url = NSURL(fileURLWithPath = path, isDirectory = true)
        check(url.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, error.ptr)) {
            "iOS report root no-backup exclusion unavailable"
        }
    }
}

private fun checkExcludedFromBackup(path: String) {
    memScoped {
        val value = alloc<ObjCObjectVar<Any?>>()
        val url = NSURL(fileURLWithPath = path, isDirectory = true)
        check(url.getResourceValue(value.ptr, NSURLIsExcludedFromBackupKey, null) &&
            (value.value as? NSNumber)?.boolValue == true) {
            "iOS report root no-backup exclusion is not retained"
        }
    }
}

private inline fun <T> NSRecursiveLock.withLock(block: () -> T): T {
    lock()
    return try { block() } finally { unlock() }
}

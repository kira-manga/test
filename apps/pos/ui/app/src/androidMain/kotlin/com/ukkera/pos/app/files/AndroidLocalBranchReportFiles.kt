package com.ukkera.pos.app.files

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import android.system.OsConstants
import android.system.StructStat
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ukkera.pos.app.LocalBranchReportExportLifetime
import com.ukkera.pos.app.LocalBranchReportFileDelivery
import com.ukkera.pos.app.LocalBranchReportFileDestinationKind
import com.ukkera.pos.app.LocalBranchReportFilePhase
import com.ukkera.pos.app.LocalBranchReportFileProblem
import com.ukkera.pos.app.LocalBranchReportFileReceipt
import com.ukkera.pos.app.LocalReportCommitGate
import com.ukkera.pos.app.LocalReportCommitStart
import com.ukkera.pos.app.LocalReportLinkOutcome
import com.ukkera.pos.app.LocalReportFileFailure
import com.ukkera.pos.app.LocalReportNativeChoice
import com.ukkera.pos.app.LocalReportReceiptDisk
import com.ukkera.pos.app.LocalReportRecoveredPublication
import com.ukkera.pos.app.MAX_LOCAL_REPORT_CSV_BYTES
import com.ukkera.pos.app.fileFailure
import com.ukkera.pos.app.useCleared
import com.ukkera.pos.app.validLocalReportFileName
import com.ukkera.pos.security.sha256
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Private local report roots. LIVE is the existing production root and provider. TRAINING is a
 * disjoint root that the live report provider never reads, so a synthetic training export cannot
 * appear as a live branch document (and conversely).
 */
enum class AndroidLocalBranchReportStorageV1(internal val directoryName: String) {
    LIVE("branch-report-export-v1"),
    TRAINING("branch-report-export-training-v1"),
}

/**
 * Actual foreground native chooser into Context.noBackupFilesDir, never external/cloud SAF.
 * onStop still locks the app; this bridge does not exempt a picker from that security boundary.
 * API24+ protected file access uses the native adapter for atomic close-on-exec/no-follow
 * and bounded enumeration, without hidden APIs or guessed ABI constants.
 */
class AndroidLocalBranchReportFiles(
    context: Context,
    private val storage: AndroidLocalBranchReportStorageV1 = AndroidLocalBranchReportStorageV1.LIVE,
) : LocalBranchReportFileDelivery() {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    internal override val commitGate: LocalReportCommitGate = object : LocalReportCommitGate {
        private val lock = ReentrantLock()
        override fun <T> locked(block: () -> T): T = lock.withLock(block)
    }
    private class Host(val activity: Activity) {
        val alive = AtomicBoolean(true)
        val dialog = AtomicReference<AlertDialog?>(null)
    }
    private val host = AtomicReference<Host?>(null)
    override val bound: Boolean get() = Build.VERSION.SDK_INT >= AndroidNativeKeyPolicy.MINIMUM_ANDROID_API &&
        host.get()?.let { it.alive.get() && !it.activity.isFinishing && !it.activity.isDestroyed } == true

    /** A NEW binding for each foreground Activity lifetime; ending it is irreversible. */
    fun bind(activity: Activity): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper() && activity.applicationContext === application)
        val captured = Host(activity)
        val retired = commitGate.locked { host.getAndSet(captured)?.also { it.alive.set(false) } }
        retired?.let { previous ->
            main.post { previous.dialog.getAndSet(null)?.dismiss() }
        }
        return AutoCloseable {
            // Never take the storage mutex or wait for file IO on the foreground end path.
            commitGate.locked {
                captured.alive.set(false)
                host.compareAndSet(captured, null)
            }
            main.post { captured.dialog.getAndSet(null)?.dismiss() }
        }
    }

    override fun hostCurrent(host: Any): Boolean = host is Host && this.host.get() === host && host.alive.get() &&
        !host.activity.isFinishing && !host.activity.isDestroyed

    override suspend fun nativeChoice(suggestedName: String, lifetime: LocalBranchReportExportLifetime): LocalReportNativeChoice? {
        disk { Unit } // actual protected directory/file-lock admission, never an acceptance flag
        val captured = host.get() ?: fileFailure(LocalBranchReportFileProblem.NOT_BOUND)
        val ownedDialog = AtomicReference<AlertDialog?>(null)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { main.post {
                ownedDialog.getAndSet(null)?.let { dialog ->
                    captured.dialog.compareAndSet(dialog, null)
                    dialog.dismiss()
                }
            } }
            main.post {
                if (!continuation.isActive || !hostCurrent(captured) || !lifetime.isCurrent()) {
                    if (continuation.isActive) continuation.resume(null)
                    return@post
                }
                var chooser: AlertDialog? = null
                try {
                    val activity = captured.activity
                    val padding = (20 * activity.resources.displayMetrics.density).toInt()
                    val name = EditText(activity).apply {
                        setSingleLine(true)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        filters = arrayOf(InputFilter.LengthFilter(84))
                        setText(suggestedName)
                        contentDescription = "CSV file name / اسم ملف CSV"
                    }
                    val training = storage == AndroidLocalBranchReportStorageV1.TRAINING
                    val content = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(padding, padding, padding, padding)
                        addView(TextView(activity).apply { text = if (training)
                            "Destination: Ukkera POS SYNTHETIC TRAINING report files on this device only. NOT LIVE. Choose an Arabic or English name ending .csv. Existing files are never replaced. No cloud provider, upload, open or share.\n" +
                            "الوجهة: ملفات تقارير تدريب اصطناعي خاصة بـ Ukkera POS على هذا الجهاز فقط. ليست مباشرة. اختر اسمًا بالعربية أو الإنجليزية ينتهي بـ .csv. لا تُستبدل الملفات الموجودة ولا يتم رفعها أو فتحها أو مشاركتها."
                        else
                            "Destination: Ukkera POS local report files on this device only. Choose an Arabic or English name ending .csv. Existing files are never replaced. No cloud provider, upload, open or share.\n" +
                            "الوجهة: ملفات تقارير Ukkera POS المحلية على هذا الجهاز فقط. اختر اسمًا بالعربية أو الإنجليزية ينتهي بـ .csv. لا تُستبدل الملفات الموجودة ولا يتم رفعها أو فتحها أو مشاركتها." })
                        addView(name)
                    }
                    chooser = AlertDialog.Builder(activity).setTitle(if (training)
                        "Save training CSV / حفظ CSV تدريبي" else "Save local CSV / حفظ CSV محلي")
                        .setView(content).setPositiveButton("Choose / اختيار", null)
                        .setNegativeButton("Cancel / إلغاء") { _, _ -> if (continuation.isActive) continuation.resume(null) }
                        .create()
                    val dialog = checkNotNull(chooser)
                    if (!captured.dialog.compareAndSet(null, dialog)) {
                        dialog.dismiss()
                        if (continuation.isActive) continuation.resume(null)
                        return@post
                    }
                    ownedDialog.set(dialog)
                    dialog.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
                    dialog.setOnDismissListener {
                        ownedDialog.compareAndSet(dialog, null)
                        captured.dialog.compareAndSet(dialog, null)
                        if (continuation.isActive) continuation.resume(null)
                    }
                    dialog.setOnShowListener {
                        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val chosen = name.text.toString()
                            if (!validLocalReportFileName(chosen)) {
                                name.error = "Use letters, digits, spaces, -, _ or . and end .csv (up to 84 characters; no path or controls) / استخدم أحرفًا أو أرقامًا أو مسافات وامتداد .csv (بحد أقصى 84 حرفًا، دون مسار أو محارف تحكم)"
                            } else {
                                val result = if (hostCurrent(captured) && lifetime.isCurrent())
                                    LocalReportNativeChoice(captured, chosen, if (training)
                                        "Ukkera POS · SYNTHETIC TRAINING report files · NOT LIVE / ملفات تقارير تدريب اصطناعي · ليست مباشرة"
                                    else "Ukkera POS · local report files / ملفات التقارير المحلية",
                                        LocalBranchReportFileDestinationKind.ANDROID_LOCAL_DOCUMENT) else null
                                if (continuation.isActive) continuation.resume(result)
                                dialog.dismiss()
                            }
                        }
                    }
                    dialog.show()
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(LocalReportFileFailure(LocalBranchReportFileProblem.NOT_BOUND)))
                    chooser?.let { dialog ->
                        ownedDialog.compareAndSet(dialog, null)
                        captured.dialog.compareAndSet(dialog, null)
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    override suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T = withContext(Dispatchers.IO) {
        AndroidReportFileStorage.access(application, storage, block)
    }
}

/** Shared by the real Activity bridge and the OS DocumentsProvider in this app process. */
internal object AndroidReportFileStorage {
    private val mutex = ReentrantLock()
    private val storages = mutableMapOf<AndroidLocalBranchReportStorageV1, AndroidReportReceiptDisk>()
    private val initializationAttempted = mutableSetOf<AndroidLocalBranchReportStorageV1>()
    private var application: Application? = null
    fun <T> access(context: Context, storageKind: AndroidLocalBranchReportStorageV1,
        block: (AndroidReportReceiptDisk) -> T): T = mutex.withLock {
        if (Build.VERSION.SDK_INT < AndroidNativeKeyPolicy.MINIMUM_ANDROID_API) fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
        val app = context.applicationContext as? Application ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
        check(application == null || application === app)
        val actual = storages[storageKind] ?: run {
            // A failed initialization/descriptor cleanup is held per root for this process. No
            // automatic reopening, descriptor-number guessing, directory repair or second lock.
            if (storageKind in initializationAttempted) fileFailure(LocalBranchReportFileProblem.STORAGE_UNCERTAIN)
            initializationAttempted += storageKind
            application = app
            AndroidReportReceiptDisk.open(app, storageKind).also { storages[storageKind] = it }
        }
        actual.current()
        block(actual).also { actual.current() }
    }
}

internal class AndroidReportReceiptDisk private constructor(
    private val frameworkParent: AndroidReportFrameworkParent,
    private val root: File,
    private val uid: Int,
    private val keys: Map<String, String>,
    private val lockDescriptor: FileDescriptor,
    private val lockStream: FileOutputStream,
    private val fileLock: FileLock,
) : LocalReportReceiptDisk() {
    private var closureUncertain = false
    private inline fun <T> protectedIo(block: () -> T): T = try { block() }
        catch (failure: Throwable) {
            if (failure is AndroidNativeFiles.ClosureUncertainException ||
                failure.suppressed.any { it is AndroidNativeFiles.ClosureUncertainException }) closureUncertain = true
            throw failure
        }
    private fun openDescriptor(path: String, flags: Int, mode: Int): FileDescriptor =
        protectedIo { AndroidNativeFiles.open(path, flags, mode) }
    private fun closeDescriptor(descriptor: FileDescriptor) = protectedIo { AndroidNativeFiles.close(descriptor) }

    private fun resolve(relative: String): File {
        check(relative.isEmpty() || relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it })
        return if (relative.isEmpty()) root else File(root, relative)
    }
    fun current() {
        check(!closureUncertain) { "Native report storage closure remains unresolved" }
        frameworkParent.current()
        check(lockDescriptor.valid() && fileLock.isValid && lockStream.channel.isOpen)
        keys.forEach { (name, key) ->
            val at = resolve(name)
            if (name == "custody.lock") androidPrivateFile(at, uid) else androidPrivateDirectory(at, uid)
            check(androidIdentity(Os.lstat(at.path)) == key)
        }
        check(androidIdentity(Os.fstat(lockDescriptor)) == keys["custody.lock"])
        check(entries("", 3).toSet() == setOf("custody.lock", "receipts", "published"))
    }
    override fun entries(directory: String, maximum: Int): List<String> {
        val at = resolve(directory)
        androidPrivateDirectory(at, uid)
        // This directory is app-UID-private and only this bounded engine creates its entries.
        // An unexpected/missing/over-capacity set is retained and refused, never cleaned away.
        check(maximum >= 0)
        val names = mutableListOf<String>()
        protectedIo {
            AndroidNativeFiles.openDirectory(at.path).use { cursor ->
                while (true) {
                    val name = cursor.nextName() ?: break
                    check(names.size < maximum)
                    names.add(name)
                }
            }
        }
        return names
    }
    override fun makeDirectory(path: String) {
        val at = resolve(path)
        androidPrivateDirectory(checkNotNull(at.parentFile), uid)
        Os.mkdir(at.path, 448)
        androidPrivateDirectory(at, uid)
    }
    override fun exists(path: String): Boolean = try { Os.lstat(resolve(path).path); true }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) false else throw failure }
    override fun identity(path: String): String = androidIdentity(Os.lstat(resolve(path).path))

    override fun readBounded(path: String, maximum: Int): ByteArray {
        val at = resolve(path)
        androidPrivateFile(at, uid)
        val before = androidIdentity(Os.lstat(at.path))
        val descriptor = openDescriptor(at.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var retainedBytes: ByteArray? = null
        return try {
            check(androidIdentity(Os.fstat(descriptor)) == before)
            val bytes = readAndroidFile(descriptor, maximum).also { retainedBytes = it }
            try {
                Os.fsync(descriptor)
                androidPrivateFile(at, uid)
                check(androidIdentity(Os.lstat(at.path)) == before)
                bytes
            } catch (failure: Exception) { bytes.fill(0); throw failure }
        } finally {
            try { closeDescriptor(descriptor) }
            catch (failure: Throwable) { retainedBytes?.fill(0); throw failure }
        }
    }
    override fun writeExclusive(path: String, bytes: ByteArray) {
        check(writeExclusivePrepared(path, bytes, null))
    }
    override fun writeExclusiveAtCommitStart(path: String, bytes: ByteArray, commitStart: LocalReportCommitStart): Boolean =
        writeExclusivePrepared(path, bytes, commitStart)

    private fun writeExclusivePrepared(path: String, bytes: ByteArray, commitStart: LocalReportCommitStart?): Boolean {
        val at = resolve(path)
        if (commitStart != null) current()
        androidPrivateDirectory(checkNotNull(at.parentFile), uid)
        val nativePath = at.path
        val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW
        // This is the final preparation boundary, not a check before receipt reread/fsync.
        // Once STARTED wins the short gate, later end cannot cancel or relabel this write.
        if (commitStart != null && !commitStart.admit()) return false
        val descriptor = openDescriptor(nativePath, flags, 384)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(descriptor, bytes, offset, bytes.size - offset)
                check(count > 0); offset += count
            }
            Os.fsync(descriptor)
            androidPrivateFile(at, uid)
        } finally { closeDescriptor(descriptor) }
        return true
    }
    override fun forceDirectory(path: String) { val at = resolve(path); androidPrivateDirectory(at, uid); protectedIo { forceAndroidDirectory(at) } }

    override fun linkComplete(receipt: LocalBranchReportFileReceipt, commitStart: LocalReportCommitStart): LocalReportLinkOutcome = try {
        verifySpool(receipt)
        current()
        androidPrivateDirectory(resolve("published"), uid)
        val sourcePath = resolve(spool(receipt.exportId)).path
        val destinationPath = resolve(published(receipt.fileName)).path
        if (!commitStart.admit()) LocalReportLinkOutcome.NOT_ATTEMPTED else {
            // Exclusive link, not a replace-capable rename. Full content is already fsynced.
            Os.link(sourcePath, destinationPath)
            if (verifyPublished(receipt)) LocalReportLinkOutcome.DURABLE else LocalReportLinkOutcome.UNCERTAIN
        }
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.EEXIST) LocalReportLinkOutcome.NOT_ATTEMPTED else LocalReportLinkOutcome.UNCERTAIN
    } catch (_: Exception) { LocalReportLinkOutcome.UNCERTAIN }

    override fun verifyPublished(receipt: LocalBranchReportFileReceipt): Boolean = try {
        val at = resolve(published(receipt.fileName))
        androidPrivateFile(at, uid)
        check(androidIdentity(Os.lstat(at.path)) == receipt.spoolIdentity)
        verifySpool(receipt)
        val source = checkNotNull(receipt.source)
        readBounded(published(receipt.fileName), MAX_LOCAL_REPORT_CSV_BYTES).useCleared {
            check(it.size == source.byteCount && sha256(it).value == source.contentSha256)
        }
        forceDirectory("published")
        true
    } catch (_: Exception) { false }

    override fun recoverPublication(receipt: LocalBranchReportFileReceipt): LocalReportRecoveredPublication = when {
        !verifyPublished(receipt) -> LocalReportRecoveredPublication.NOT_VERIFIED
        receipt.phase == LocalBranchReportFilePhase.PUBLISHED -> LocalReportRecoveredPublication.ALREADY_PUBLISHED_VERIFIED
        // Metadata lookup is NOT fresh EXPORT authorization. Merely finding a hidden local link
        // must never admit that file to the provider for the first time during recovery.
        else -> LocalReportRecoveredPublication.PRIVATE_FILE_ONLY
    }

    /** Read-only original descriptor, verified again after opening; no automatic open/share call. */
    fun openPublished(receipt: LocalBranchReportFileReceipt): ParcelFileDescriptor {
        if (receipt.phase != LocalBranchReportFilePhase.PUBLISHED || !verifyPublished(receipt)) throw FileNotFoundException()
        val descriptor = openDescriptor(resolve(published(receipt.fileName)).path,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var owned: ParcelFileDescriptor? = null
        try {
            val result = try {
                check(androidIdentity(Os.fstat(descriptor)) == receipt.spoolIdentity)
                val source = checkNotNull(receipt.source)
                readAndroidFile(descriptor, MAX_LOCAL_REPORT_CSV_BYTES).useCleared {
                    check(it.size == source.byteCount && sha256(it).value == source.contentSha256)
                }
                Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
                ParcelFileDescriptor.dup(descriptor).also { owned = it }
            } finally { closeDescriptor(descriptor) }
            owned = null
            return result
        } finally { owned?.close() }
    }

    companion object {
        fun open(application: Application,
            storage: AndroidLocalBranchReportStorageV1 = AndroidLocalBranchReportStorageV1.LIVE): AndroidReportReceiptDisk {
            if (Build.VERSION.SDK_INT < AndroidNativeKeyPolicy.MINIMUM_ANDROID_API) fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
            val frameworkParent = AndroidReportFrameworkParent.capture(application)
            val uid = frameworkParent.uid
            val parent = frameworkParent.noBackup
            val root = File(parent, storage.directoryName)
            if (!root.exists()) { Os.mkdir(root.path, 448); forceAndroidDirectory(parent) }
            androidPrivateDirectory(root, uid)
            for (name in listOf("receipts", "published")) {
                val at = File(root, name)
                if (!at.exists()) { Os.mkdir(at.path, 448); forceAndroidDirectory(root) }
                androidPrivateDirectory(at, uid)
            }
            val lockFile = File(root, "custody.lock")
            if (lockFile.exists()) androidPrivateFile(lockFile, uid)
            val descriptor = AndroidNativeFiles.open(lockFile.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 384)
            var borrower: FileOutputStream? = null
            var heldLock: FileLock? = null
            try {
                // Android's public FileOutputStream(fd) BORROWS fd, including through its
                // channel. The disk retains the only native close obligation explicitly.
                val stream = FileOutputStream(descriptor).also { borrower = it }
                val lock = stream.channel.tryLock() ?: fileFailure(LocalBranchReportFileProblem.STORAGE_UNCERTAIN)
                heldLock = lock
                androidPrivateFile(lockFile, uid)
                Os.fsync(descriptor); forceAndroidDirectory(root)
                val keys = listOf("", "receipts", "published", "custody.lock").associateWith { name ->
                    androidIdentity(Os.lstat(if (name.isEmpty()) root.path else File(root, name).path))
                }
                return AndroidReportReceiptDisk(frameworkParent, root, uid, keys, descriptor, stream, lock).also { it.current(); it.receipts() }
            } catch (failure: Throwable) {
                // Release/close the borrowed Java wrappers, then attempt the owned native close
                // exactly ONCE. Never retry an uncertain close against a possibly reused fd.
                try { heldLock?.let { if (it.isValid) it.release() } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { borrower?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { AndroidNativeFiles.close(descriptor) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}

/**
 * OS-managed parents are NOT app-created 0700 allocation directories. The actual Application,
 * current UID/GID and canonical data root bind this pre-existing framework no_backup parent.
 * Do not call its lazy getter here: it can mkdir/chmod. Missing/changed custody is a setup gate,
 * never enrollment, repair or permission broadening by the export engine.
 */
private class AndroidReportFrameworkParent private constructor(
    private val application: Application,
    private val dataRoot: File,
    val noBackup: File,
    val uid: Int,
    private val gid: Int,
    private val dataIdentity: String,
    private val noBackupIdentity: String,
) {
    fun current() {
        check(application.applicationContext === application && application.applicationInfo.uid == uid)
        check(Process.myUid() == uid && Os.getuid() == uid && Os.geteuid() == uid && Os.getgid() == gid && Os.getegid() == gid)
        check(application.dataDir.canonicalFile == dataRoot && noBackup == File(dataRoot, "no_backup"))
        val dataStat = androidFrameworkDirectory(dataRoot, uid, gid)
        // The framework app data root may have app-group access; never foreign-group/other
        // write or special permission bits. This is not the private report-subtree contract.
        check((dataStat.st_mode and 448) == 448 && (dataStat.st_mode and 2) == 0 && (dataStat.st_mode and 3584) == 0)
        val parentStat = androidFrameworkDirectory(noBackup, uid, gid)
        check((parentStat.st_mode and 4095) in setOf(448, 505)) // 0700 or framework 0771 only
        check(androidIdentity(dataStat) == dataIdentity && androidIdentity(parentStat) == noBackupIdentity)
    }

    companion object {
        fun capture(application: Application): AndroidReportFrameworkParent {
            val uid = Process.myUid()
            val gid = Os.getgid()
            check(uid >= 0 && gid >= 0)
            val dataRoot = application.dataDir.canonicalFile
            val noBackup = File(dataRoot, "no_backup")
            val captured = AndroidReportFrameworkParent(application, dataRoot, noBackup, uid, gid,
                androidIdentity(Os.lstat(dataRoot.path)), androidIdentity(Os.lstat(noBackup.path)))
            captured.current()
            return captured
        }
    }
}

private fun androidIdentity(stat: StructStat): String = "android:${stat.st_dev}:${stat.st_ino}"
private fun androidFrameworkDirectory(path: File, uid: Int, gid: Int): StructStat = Os.lstat(path.path).also { stat ->
    check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid && stat.st_gid == gid && path.canonicalFile == path)
}
private fun androidPrivateDirectory(path: File, uid: Int) {
    val stat = Os.lstat(path.path)
    check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid && (stat.st_mode and 4095) == 448 && path.canonicalFile == path)
}
private fun androidPrivateFile(path: File, uid: Int) {
    val stat = Os.lstat(path.path)
    check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == uid && (stat.st_mode and 4095) == 384 && stat.st_nlink in 1L..2L)
    check(path.canonicalFile == path)
}
private fun forceAndroidDirectory(path: File) {
    val before = Os.lstat(path.path)
    check(OsConstants.S_ISDIR(before.st_mode) && path.canonicalFile == path)
    // O_DIRECTORY is not a public Android OsConstants field. Do not guess its ABI value:
    // public no-follow/nonblocking open, then validate the ACTUAL descriptor before fsync.
    val descriptor = AndroidNativeFiles.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
    try {
        val opened = Os.fstat(descriptor)
        check(OsConstants.S_ISDIR(opened.st_mode) && androidIdentity(opened) == androidIdentity(before) &&
            opened.st_mode == before.st_mode && opened.st_uid == before.st_uid && opened.st_gid == before.st_gid)
        Os.fsync(descriptor)
        val after = Os.lstat(path.path)
        check(androidIdentity(after) == androidIdentity(before) && after.st_mode == before.st_mode &&
            after.st_uid == before.st_uid && after.st_gid == before.st_gid && path.canonicalFile == path)
    } finally { AndroidNativeFiles.close(descriptor) }
}
private fun readAndroidFile(descriptor: FileDescriptor, maximum: Int): ByteArray {
    val buffer = ByteArray(maximum + 1)
    return try {
        var size = 0
        while (size < buffer.size) {
            val count = Os.read(descriptor, buffer, size, buffer.size - size)
            if (count == 0) break
            check(count > 0); size += count
        }
        check(size <= maximum)
        buffer.copyOf(size)
    } finally { buffer.fill(0) }
}

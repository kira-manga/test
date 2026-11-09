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
import java.awt.EventQueue
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Fixed report stores; training exports never enter the LIVE receipt or publication root. */
enum class DesktopLocalBranchReportStorageV1(internal val directoryName: String) {
    LIVE("report-export-v1"),
    TRAINING("report-export-training-v1"),
}

private fun desktopReportDirectory(storage: DesktopLocalBranchReportStorageV1): Path =
    Paths.get(System.getProperty("user.home").orEmpty(), ".local", "share", "ukkera-pos", storage.directoryName)

/**
 * Native Save dialog and private local report/receipt custody for Linux, macOS and Windows.
 *
 * The chooser is restricted to one app-owned directory, never arbitrary Downloads, cloud
 * folders or SAF. Linux retains the original `/proc/self` mount/UID checks.
 * macOS requires a `/dev`-backed APFS/HFS store, actual `geteuid()` ownership, native
 * `acl_get_file`/`acl_get_entry` inspection rejecting every extended ACL entry, `unix:dev:ino`
 * identity, no symlink path components, a real `FileLock` and file/directory fsync.
 * Windows requires a drive-letter `GetDriveTypeW` fixed local volume, matching native NTFS and
 * volume-serial observations, no reparse/UNC/device path, an owner-only DACL, `FileLock`, and
 * kernel identity from `GetFileInformationByHandle` (volume serial + file index, shared by hard
 * links; no alternate-stream or random identity fallback).
 *
 * Windows has no supported non-administrative namespace durability: `FlushFileBuffers` requires
 * a writable file/volume handle and is not documented for directory handles, `CreateHardLinkW`
 * has no write-through variant, and volume flushing requires administrative privilege. The exact
 * current contract needs the published directory entry to be durable, so Windows report export
 * fails closed before the link syscall instead of creating an unproven name or manufacturing a
 * DURABLE result. Reading/recovering existing receipts and the fixed local storage checks remain.
 * Structural local-storage checks are NOT independent installed-storage or device qualification.
 */
class DesktopLocalBranchReportFiles private constructor(
    private val directory: Path,
    private val storage: DesktopLocalBranchReportStorageV1,
) : LocalBranchReportFileDelivery(), AutoCloseable {
    /** Existing explicit-directory callers retain the LIVE storage and presentation contract. */
    constructor(directory: Path = desktopReportDirectory(DesktopLocalBranchReportStorageV1.LIVE)) :
        this(directory, DesktopLocalBranchReportStorageV1.LIVE)

    constructor(storage: DesktopLocalBranchReportStorageV1) : this(desktopReportDirectory(storage), storage)

    internal override val commitGate: LocalReportCommitGate = object : LocalReportCommitGate {
        private val lock = ReentrantLock()
        override fun <T> locked(block: () -> T): T = lock.withLock(block)
    }
    private class Host(val window: Window) {
        val active = AtomicBoolean(window.isShowing)
        val ended = AtomicBoolean(false)
        val fence = AtomicReference(Any())
        val dialog = AtomicReference<FileDialog?>(null)
    }
    private class ChoiceOwner(val host: Host, val fence: Any)
    private val host = AtomicReference<Host?>(null)
    private val closed = AtomicBoolean(false)
    private val io = ReentrantLock()
    private var store: DesktopReportReceiptDisk? = null
    private val supported: Boolean get() = DesktopReportOs.current() != null
    override val bound: Boolean get() = supported && !closed.get() && host.get()?.let { it.active.get() && !it.ended.get() } == true

    /** Actual Window instance, bound on its event thread; no guessed native parent/session ID. */
    fun bind(window: Window): AutoCloseable {
        check(EventQueue.isDispatchThread())
        val owner = Host(window)
        commitGate.locked { check(!closed.get() && host.compareAndSet(null, owner)) }
        val listener = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) { activateHost(owner) }
            override fun windowIconified(event: WindowEvent) { invalidateHost(owner) }
            override fun windowDeiconified(event: WindowEvent) { activateHost(owner) }
            override fun windowClosed(event: WindowEvent) { invalidateHost(owner, end = true) }
        }
        val component = object : ComponentAdapter() {
            override fun componentHidden(event: ComponentEvent) { invalidateHost(owner) }
            override fun componentShown(event: ComponentEvent) { activateHost(owner) }
        }
        window.addWindowListener(listener)
        window.addComponentListener(component)
        return AutoCloseable {
            invalidateHost(owner, end = true, detach = true)
            EventQueue.invokeLater {
                window.removeWindowListener(listener)
                window.removeComponentListener(component)
            }
        }
    }

    private fun activateHost(owner: Host) {
        commitGate.locked {
            if (!closed.get() && host.get() === owner && !owner.ended.get()) owner.active.set(true)
        }
    }

    private fun invalidateHost(owner: Host, end: Boolean = false, detach: Boolean = false) {
        val dialog = commitGate.locked {
            if (end) owner.ended.set(true)
            if (detach) host.compareAndSet(owner, null)
            owner.active.set(false)
            owner.fence.set(Any())
            owner.dialog.getAndSet(null)
        }
        // Window disposal is outside the short lifecycle/commit-start gate and the storage lock.
        if (dialog != null) EventQueue.invokeLater { dialog.dispose() }
    }

    override fun hostCurrent(host: Any): Boolean {
        val captured = host as? ChoiceOwner ?: return false
        return !closed.get() && this.host.get() === captured.host && !captured.host.ended.get() &&
            captured.host.active.get() && captured.host.fence.get() === captured.fence
    }

    override suspend fun nativeChoice(suggestedName: String, lifetime: LocalBranchReportExportLifetime): LocalReportNativeChoice? {
        val os = DesktopReportOs.current() ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
        // Windows cannot prove the required published-directory-entry durability with a
        // supported non-administrative API. Refuse before a dialog, spool or link exists.
        if (!os.durablePublicationSupported) fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
        val directoryLabel = disk { (it as DesktopReportReceiptDisk).publishedDirectory.toString() }
        val current = host.get() ?: fileFailure(LocalBranchReportFileProblem.NOT_BOUND)
        val captured = ChoiceOwner(current, current.fence.get())
        val ownedDialog = AtomicReference<FileDialog?>(null)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                EventQueue.invokeLater {
                    ownedDialog.getAndSet(null)?.let { chooser ->
                        current.dialog.compareAndSet(chooser, null)
                        chooser.dispose()
                    }
                }
            }
            EventQueue.invokeLater {
                if (!continuation.isActive || !hostCurrent(captured) || !lifetime.isCurrent()) {
                    if (continuation.isActive) continuation.resume(null)
                    return@invokeLater
                }
                var created: FileDialog? = null
                try {
                    val title = if (storage == DesktopLocalBranchReportStorageV1.TRAINING)
                        "Save SYNTHETIC TRAINING CSV · NOT LIVE / حفظ CSV تدريبي اصطناعي · ليس مباشرًا"
                    else "Save local CSV / حفظ CSV محلي"
                    val chooser = when (val window = current.window) {
                        is Frame -> FileDialog(window, title, FileDialog.SAVE)
                        is Dialog -> FileDialog(window, title, FileDialog.SAVE)
                        else -> fileFailure(LocalBranchReportFileProblem.NOT_BOUND)
                    }
                    created = chooser
                    chooser.directory = directoryLabel
                    chooser.file = suggestedName
                    chooser.isMultipleMode = false
                    if (!current.dialog.compareAndSet(null, chooser)) {
                        if (continuation.isActive) continuation.resume(null)
                        return@invokeLater
                    }
                    ownedDialog.set(chooser)
                    // The actual OS dialog pumps its event loop, so cancellation can dismiss it.
                    chooser.isVisible = true
                    val name = chooser.file
                    val selectedDirectory = chooser.directory
                    val os = DesktopReportOs.current()
                    val choice = if (name != null && selectedDirectory != null &&
                        validNativeReportFileName(name, os) &&
                        sameNativeDirectory(Paths.get(selectedDirectory).toAbsolutePath().normalize(),
                            Paths.get(directoryLabel)) &&
                        hostCurrent(captured) && lifetime.isCurrent())
                        LocalReportNativeChoice(captured, name,
                            if (storage == DesktopLocalBranchReportStorageV1.TRAINING)
                                "SYNTHETIC TRAINING · NOT LIVE / تدريب اصطناعي · ليس مباشرًا · $directoryLabel"
                            else directoryLabel,
                            LocalBranchReportFileDestinationKind.DESKTOP_LOCAL_FILE)
                    else null
                    // A non-cancelled selection outside the fixed directory is a refusal, not a
                    // silently redirected save. UI explains the fixed destination before opening.
                    if (continuation.isActive && name != null && choice == null && hostCurrent(captured) && lifetime.isCurrent())
                        continuation.resumeWith(Result.failure(LocalReportFileFailure(LocalBranchReportFileProblem.DESTINATION_REJECTED)))
                    else if (continuation.isActive) continuation.resume(choice)
                } catch (failure: LocalReportFileFailure) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(LocalReportFileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)))
                } finally {
                    created?.let { chooser ->
                        ownedDialog.compareAndSet(chooser, null)
                        current.dialog.compareAndSet(chooser, null)
                        chooser.dispose()
                    }
                }
            }
        }
    }

    override suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T = withContext(Dispatchers.IO) {
        io.withLock {
            if (!supported || closed.get()) fileFailure(LocalBranchReportFileProblem.NOT_BOUND)
            val actual = store ?: DesktopReportReceiptDisk.open(directory).also { store = it }
            actual.current()
            block(actual).also { actual.current() }
        }
    }

    override fun close() {
        val dialog = commitGate.locked {
            closed.set(true)
            host.getAndSet(null)?.let { captured ->
                captured.ended.set(true)
                captured.active.set(false)
                captured.fence.set(Any())
                captured.dialog.getAndSet(null)
            }
        }
        if (dialog != null) EventQueue.invokeLater { dialog.dispose() }
        // Final store disposal may wait for in-flight IO, but end already linearized above.
        io.withLock { store?.close(); store = null }
    }
}

private class DesktopReportReceiptDisk private constructor(
    private val profile: DesktopReportProfile,
    private val root: Path,
    private val keys: Map<String, String>,
    private val lockChannel: FileChannel,
    private val fileLock: FileLock,
) : LocalReportReceiptDisk(), AutoCloseable {
    val publishedDirectory: Path get() = root.resolve("published")
    private fun resolve(relative: String): Path {
        check(relative.isEmpty() || relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it })
        return root.resolve(relative).normalize().also { check(it.startsWith(root)) }
    }

    fun current() {
        check(fileLock.isValid && lockChannel.isOpen)
        profile.qualifyStorage(root)
        keys.forEach { (name, key) ->
            val item = resolve(name)
            if (name == "custody.lock") profile.privateFile(item) else profile.privateDirectory(item)
            check(profile.identity(item) == key)
        }
        check(entries("", 3).toSet() == setOf("custody.lock", "receipts", "published"))
    }

    override fun entries(directory: String, maximum: Int): List<String> {
        val at = resolve(directory)
        profile.privateDirectory(at)
        return Files.newDirectoryStream(at).use { stream ->
            val result = ArrayList<String>()
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                check(result.size < maximum)
                result += iterator.next().fileName.toString()
            }
            result
        }
    }

    override fun makeDirectory(path: String) {
        val at = resolve(path)
        profile.privateDirectory(checkNotNull(at.parent))
        profile.createDirectory(at)
        profile.privateDirectory(at)
        profile.ensureIdentity(at)
    }

    override fun exists(path: String): Boolean = Files.exists(resolve(path), NOFOLLOW_LINKS)
    override fun identity(path: String): String = profile.identity(resolve(path))

    override fun readBounded(path: String, maximum: Int): ByteArray {
        val at = resolve(path)
        profile.privateFile(at)
        val before = profile.identity(at)
        val result = profile.readFile(at, maximum, force = true)
        try { check(profile.identity(at) == before); profile.privateFile(at); return result }
        catch (failure: Exception) { result.fill(0); throw failure }
    }

    override fun writeExclusive(path: String, bytes: ByteArray) {
        check(writeExclusivePrepared(path, bytes, null))
    }
    override fun writeExclusiveAtCommitStart(path: String, bytes: ByteArray, commitStart: LocalReportCommitStart): Boolean =
        writeExclusivePrepared(path, bytes, commitStart)

    private fun writeExclusivePrepared(path: String, bytes: ByteArray, commitStart: LocalReportCommitStart?): Boolean {
        val at = resolve(path)
        if (commitStart != null) current()
        profile.privateDirectory(checkNotNull(at.parent))
        val attribute = profile.newFileAttribute()
        val buffer = ByteBuffer.wrap(bytes)
        if (commitStart != null && !commitStart.admit()) return false
        profile.openExclusive(at, attribute).use { file ->
            while (buffer.hasRemaining()) check(file.write(buffer) > 0)
            file.force(true)
        }
        profile.finishExclusive(at)
        profile.privateFile(at)
        return true
    }

    override fun forceDirectory(path: String) { val at = resolve(path); profile.forceDirectory(at) }

    override fun linkComplete(receipt: LocalBranchReportFileReceipt, commitStart: LocalReportCommitStart): LocalReportLinkOutcome {
        // A platform without a supported durable-name primitive must not create a visible
        // published entry that no crash/durability contract covers.
        if (!profile.publicationDurabilityProven) fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
        val source = resolve(spool(receipt.exportId))
        val destination = resolve(published(receipt.fileName))
        return try {
            verifySpool(receipt)
            profile.privateDirectory(checkNotNull(destination.parent))
            current()
            if (!commitStart.admit()) return LocalReportLinkOutcome.NOT_ATTEMPTED
            // link(2) creates a complete name atomically and fails if ANY target already exists.
            // Unlike ATOMIC_MOVE, it cannot replace a target between a precheck and publication.
            Files.createLink(destination, source)
            if (verifyPublished(receipt)) {
                if (profile.publicationDurabilityProven) LocalReportLinkOutcome.DURABLE
                else LocalReportLinkOutcome.UNCERTAIN
            } else LocalReportLinkOutcome.UNCERTAIN
        } catch (_: FileAlreadyExistsException) { LocalReportLinkOutcome.NOT_ATTEMPTED }
        catch (_: Exception) { LocalReportLinkOutcome.UNCERTAIN }
    }

    override fun verifyPublished(receipt: LocalBranchReportFileReceipt): Boolean = try {
        val at = resolve(published(receipt.fileName))
        val original = resolve(spool(receipt.exportId))
        profile.privateFile(at); profile.privateFile(original)
        check(profile.identity(at) == receipt.spoolIdentity && Files.isSameFile(at, original))
        verifySpool(receipt)
        val source = checkNotNull(receipt.source)
        readBounded(published(receipt.fileName), MAX_LOCAL_REPORT_CSV_BYTES).useCleared {
            check(it.size == source.byteCount && sha256(it).value == source.contentSha256)
        }
        forceDirectory("published")
        true
    } catch (_: Exception) { false }

    override fun recoverPublication(receipt: LocalBranchReportFileReceipt): LocalReportRecoveredPublication =
        if (verifyPublished(receipt)) LocalReportRecoveredPublication.ALREADY_PUBLISHED_VERIFIED else LocalReportRecoveredPublication.NOT_VERIFIED

    override fun close() { try { if (fileLock.isValid) fileLock.release() } finally { lockChannel.close() } }

    companion object {
        fun open(requested: Path): DesktopReportReceiptDisk {
            try {
                val profile = DesktopReportProfile.open(requested)
                check(requested.isAbsolute && requested == requested.normalize() && requested.nameCount in 1..64)
                var ancestor = requested
                while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = checkNotNull(ancestor.parent)
                profile.qualifyStorage(ancestor)
                var cursor = requested.root
                requested.forEach { part ->
                    val next = cursor.resolve(part)
                    if (!Files.exists(next, NOFOLLOW_LINKS)) {
                        profile.createDirectory(next)
                        profile.forceCreatedParent(cursor)
                    }
                    profile.checkComponent(next)
                    cursor = next
                }
                val real = requested.toRealPath(NOFOLLOW_LINKS)
                when (profile.os) {
                    DesktopReportOs.LINUX -> check(real == requested)
                    DesktopReportOs.MACOS, DesktopReportOs.WINDOWS -> check(Files.isSameFile(real, requested))
                }
                val root = requested
                profile.privateDirectory(root)
                profile.ensureIdentity(root)
                for (name in listOf("receipts", "published")) {
                    val at = root.resolve(name)
                    if (!Files.exists(at, NOFOLLOW_LINKS)) {
                        profile.createDirectory(at)
                        profile.forceCreatedParent(root)
                    }
                    profile.privateDirectory(at)
                    profile.ensureIdentity(at)
                }
                val lockPath = root.resolve("custody.lock")
                if (Files.exists(lockPath, NOFOLLOW_LINKS)) profile.privateFile(lockPath)
                val channel = profile.openLock(lockPath)
                try {
                    val lock = channel.tryLock() ?: fileFailure(LocalBranchReportFileProblem.STORAGE_UNCERTAIN)
                    profile.privateFile(lockPath)
                    channel.force(true); profile.forceCreatedParent(root)
                    val keys = listOf("", "receipts", "published", "custody.lock").associateWith { profile.identity(root.resolve(it)) }
                    return DesktopReportReceiptDisk(profile, root, keys, channel, lock).also { it.current(); it.receipts() }
                } catch (failure: Exception) { channel.close(); throw failure }
            } catch (failure: LocalReportFileFailure) { throw failure }
            catch (_: Exception) { fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE) }
        }
    }
}

private enum class DesktopReportOs {
    LINUX, MACOS, WINDOWS;
    /** True only where a durable published name can be established with supported APIs. */
    val durablePublicationSupported: Boolean get() = this != WINDOWS
    companion object {
        fun current(): DesktopReportOs? {
            val name = System.getProperty("os.name").orEmpty()
            return when {
                name.equals("Linux", ignoreCase = true) -> LINUX
                name.equals("Mac OS X", ignoreCase = true) || name.equals("macOS", ignoreCase = true) -> MACOS
                name.startsWith("Windows", ignoreCase = true) -> WINDOWS
                else -> null
            }
        }
    }
}

private fun sameNativeDirectory(selected: Path, expected: Path): Boolean {
    return when (DesktopReportOs.current()) {
        DesktopReportOs.MACOS, DesktopReportOs.WINDOWS -> try { Files.isSameFile(selected, expected) }
            catch (_: Exception) { false }
        else -> selected == expected
    }
}

private fun validNativeReportFileName(name: String, os: DesktopReportOs?): Boolean {
    if (!validLocalReportFileName(name)) return false
    if (os != DesktopReportOs.WINDOWS) return true
    // Windows resolves these device names even with an extension. Refuse the name rather
    // than letting the filesystem open a console/device or a surprising alias.
    val device = name.substringBefore('.').trimEnd(' ', '.').uppercase()
    return device !in setOf("CON", "PRN", "AUX", "NUL") &&
        !Regex("^(COM|LPT)[1-9]$").matches(device)
}

private class DesktopReportProfile private constructor(
    val os: DesktopReportOs,
    private val uid: Long,
    private val principal: UserPrincipal?,
) {
    /**
     * Windows has no supported non-administrative way to persist a directory entry:
     * `FlushFileBuffers` is documented for writable file/volume handles and not for directory
     * handles, `CreateHardLinkW` has no write-through flag, and volume flush requires
     * administrative privilege. No DURABLE result is manufactured; the caller refuses the link.
     */
    val publicationDurabilityProven: Boolean get() = os != DesktopReportOs.WINDOWS

    fun qualifyStorage(path: Path) {
        when (os) {
            DesktopReportOs.LINUX -> qualifyLinuxStorage(path)
            DesktopReportOs.MACOS -> qualifyMacStorage(path)
            DesktopReportOs.WINDOWS -> qualifyWindowsStorage(path)
        }
    }

    fun checkComponent(path: Path) {
        when (os) {
            DesktopReportOs.LINUX -> {
                val mode = linuxLong(path, "mode")
                check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                check(linuxLong(path, "uid") in setOf(0L, uid))
                check((mode and 18L) == 0L) // no group/other writers in the path
            }
            DesktopReportOs.MACOS -> {
                val mode = macLong(path, "mode")
                check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                check(macLong(path, "uid") in setOf(0L, uid))
                check((mode and 18L) == 0L)
            }
            DesktopReportOs.WINDOWS -> {
                // Win32 junctions are reparse points; WindowsFileAttributes.isOther catches
                // them even where isSymbolicLink does not.
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther)
            }
        }
    }

    fun newFileAttribute(): FileAttribute<*> = when (os) {
        DesktopReportOs.LINUX, DesktopReportOs.MACOS ->
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        DesktopReportOs.WINDOWS -> windowsAclAttribute(checkNotNull(principal), directory = false)
    }

    private fun newDirectoryAttribute(): FileAttribute<*> = when (os) {
        DesktopReportOs.LINUX, DesktopReportOs.MACOS ->
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        DesktopReportOs.WINDOWS -> windowsAclAttribute(checkNotNull(principal), directory = true)
    }

    fun createDirectory(path: Path) {
        Files.createDirectory(path, newDirectoryAttribute())
        if (os == DesktopReportOs.WINDOWS) windowsSetAcl(path, checkNotNull(principal), directory = true)
    }

    fun forceCreatedParent(path: Path) {
        when (os) {
            DesktopReportOs.LINUX -> forceLinuxDirectory(path)
            DesktopReportOs.MACOS -> forceMacDirectory(path)
            DesktopReportOs.WINDOWS -> {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther)
            }
        }
    }

    fun privateDirectory(path: Path) {
        when (os) {
            DesktopReportOs.LINUX -> privateDirectory(path, uid)
            DesktopReportOs.MACOS -> macPrivateDirectory(path, uid)
            DesktopReportOs.WINDOWS -> windowsPrivateDirectory(path, checkNotNull(principal))
        }
    }

    fun privateFile(path: Path) {
        when (os) {
            DesktopReportOs.LINUX -> privateFile(path, uid)
            DesktopReportOs.MACOS -> macPrivateFile(path, uid)
            DesktopReportOs.WINDOWS -> windowsPrivateFile(path, checkNotNull(principal))
        }
    }

    fun identity(path: Path): String = when (os) {
        DesktopReportOs.LINUX -> linuxIdentity(path)
        DesktopReportOs.MACOS -> macIdentity(path)
        DesktopReportOs.WINDOWS -> windowsIdentity(path)
    }

    fun ensureIdentity(path: Path) {
        if (os == DesktopReportOs.WINDOWS) windowsEnsureIdentity(path)
    }

    fun forceDirectory(path: Path) {
        when (os) {
            DesktopReportOs.LINUX -> { privateDirectory(path, uid); forceLinuxDirectory(path) }
            DesktopReportOs.MACOS -> { macPrivateDirectory(path, uid); forceMacDirectory(path); macPrivateDirectory(path, uid) }
            DesktopReportOs.WINDOWS -> {
                // java.nio cannot open a Windows directory as a FileChannel, and FlushFileBuffers
                // is not documented for directory handles. Verify name/ACL/identity continuity,
                // but do not pretend this is a directory fsync. Publication is refused earlier.
                val actual = checkNotNull(principal)
                windowsPrivateDirectory(path, actual)
                val before = windowsIdentity(path)
                windowsPrivateDirectory(path, actual)
                check(windowsIdentity(path) == before)
            }
        }
    }

    fun readFile(path: Path, maximum: Int, force: Boolean): ByteArray = when (os) {
        DesktopReportOs.LINUX, DesktopReportOs.MACOS -> readPosixFile(path, maximum, force)
        // FlushFileBuffers requires a writable handle on Windows. Reads must not fail merely
        // because a private report is write-protected; the writer already forced its bytes and
        // the exact digest is checked after this bounded read.
        DesktopReportOs.WINDOWS -> readWindowsFile(path, maximum)
    }

    fun openExclusive(path: Path, attribute: FileAttribute<*>): FileChannel = FileChannel.open(
        path, setOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW_LINKS), attribute,
    )

    fun finishExclusive(path: Path) {
        if (os == DesktopReportOs.WINDOWS) {
            val actual = checkNotNull(principal)
            windowsSetAcl(path, actual, directory = false)
            windowsEnsureIdentity(path)
        }
    }

    fun openLock(path: Path): FileChannel = when (os) {
        DesktopReportOs.LINUX, DesktopReportOs.MACOS -> FileChannel.open(
            path, setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, NOFOLLOW_LINKS), newFileAttribute(),
        )
        DesktopReportOs.WINDOWS -> windowsOpenLock(path, checkNotNull(principal))
    }

    companion object {
        fun open(requested: Path): DesktopReportProfile {
            val os = DesktopReportOs.current() ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
            return when (os) {
                DesktopReportOs.LINUX -> DesktopReportProfile(os, effectiveLinuxUid(), null)
                DesktopReportOs.MACOS -> DesktopReportProfile(os, DesktopReportNative.macEffectiveUid(), null)
                DesktopReportOs.WINDOWS -> DesktopReportProfile(os, 0L, windowsCurrentPrincipal())
            }
        }
    }
}

private fun qualifyMacStorage(path: Path) {
    check(DesktopReportOs.current() == DesktopReportOs.MACOS)
    val store = Files.getFileStore(path)
    check(store.type().lowercase() in setOf("apfs", "hfs", "hfsx")) {
        "macOS report custody requires APFS/HFS, not ${store.type()}"
    }
    check(!store.isReadOnly)
    // Unix FileStore.name is the mounted device (e.g. /dev/disk3s1s1). This rejects SMB,
    // NFS, AFP, WebDAV and automount providers even if their type string is unfamiliar.
    check(store.name().startsWith("/dev/")) { "macOS report custody requires a device-backed local volume" }
    check(store.supportsFileAttributeView("posix") && store.supportsFileAttributeView("unix"))
    // Extended ACLs are read natively by macPrivateDirectory/macPrivateFile. A provider view
    // that exposes or omits ACLs is irrelevant; every actual entry is rejected there.
}

private fun qualifyWindowsStorage(path: Path) {
    check(DesktopReportOs.current() == DesktopReportOs.WINDOWS)
    val absolute = path.toAbsolutePath()
    val root = absolute.root ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
    check(Regex("^[A-Za-z]:\\\\$").matches(root.toString())) {
        "Windows report custody requires a non-UNC drive root"
    }
    val text = absolute.toString()
    check(!text.startsWith("\\\\") && !text.startsWith("//")) {
        "Windows report custody rejects UNC/device paths"
    }
    // Native GetDriveTypeW/GetVolumePathNameW/GetVolumeNameForVolumeMountPointW and
    // GetVolumeInformationByHandleW reject mapped/network/removable/nonlocal volumes even when
    // the JDK FileStore reports an NTFS type string.
    val observation = DesktopReportNative.windowsObserveVolume(absolute)
    check(observation.driveType == WINDOWS_DRIVE_FIXED) {
        "Windows report custody requires a fixed local drive, not type ${observation.driveType}"
    }
    check(observation.fileSystem.equals("NTFS", ignoreCase = true)) {
        "Windows report custody requires NTFS, not ${observation.fileSystem}"
    }
    check(!observation.readOnly) { "Windows report custody requires a writable volume" }
    val store: FileStore = Files.getFileStore(path)
    check(store.type().equals("NTFS", ignoreCase = true)) {
        "Windows report custody requires NTFS, not ${store.type()}"
    }
    check(!store.isReadOnly)
    check(store.supportsFileAttributeView("basic"))
    check(store.supportsFileAttributeView("acl") && store.supportsFileAttributeView("owner"))
}

private const val WINDOWS_DRIVE_FIXED = 3

private fun macLong(path: Path, name: String): Long = (Files.getAttribute(path, "unix:$name", NOFOLLOW_LINKS) as Number).toLong()
private fun macIdentity(path: Path): String = "macos:${macLong(path, "dev")}:${macLong(path, "ino")}".also { check(it.length <= 256) }
private fun macPrivateDirectory(path: Path, uid: Long) {
    requireNoMacAcl(path)
    check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && macLong(path, "uid") == uid)
    check((macLong(path, "mode") and 511L) == 448L)
}
private fun macPrivateFile(path: Path, uid: Long) {
    requireNoMacAcl(path)
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attributes.isRegularFile && !attributes.isSymbolicLink && macLong(path, "uid") == uid &&
        (macLong(path, "mode") and 511L) == 384L)
    check(macLong(path, "nlink") in 1L..2L)
}
private fun requireNoMacAcl(path: Path) {
    check(!DesktopReportNative.macHasExtendedAcl(path)) {
        "macOS report custody rejects every extended ACL entry as unmodeled by POSIX mode"
    }
}
private fun forceMacDirectory(path: Path) {
    val before = macIdentity(path)
    FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { it.force(true) }
    check(macIdentity(path) == before)
}

private fun windowsCurrentPrincipal(): UserPrincipal {
    val name = System.getProperty("user.name").orEmpty().trim()
    check(name.isNotEmpty())
    return FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(name)
}

private fun windowsAcl(principal: UserPrincipal, directory: Boolean): List<AclEntry> {
    val permissions = if (directory) windowsDirectoryPermissions else windowsFilePermissions
    val builder = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(principal)
        .setPermissions(permissions)
    if (directory) builder.setFlags(setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT))
    return listOf(builder.build())
}

private fun windowsAclAttribute(principal: UserPrincipal, directory: Boolean): FileAttribute<List<AclEntry>> {
    val entries = windowsAcl(principal, directory)
    return object : FileAttribute<List<AclEntry>> {
        override fun name(): String = "acl:acl"
        override fun value(): List<AclEntry> = entries
    }
}

private val windowsDirectoryPermissions: Set<AclEntryPermission> = AclEntryPermission.values().toSet()
private val windowsFilePermissions: Set<AclEntryPermission> =
    windowsDirectoryPermissions - AclEntryPermission.DELETE_CHILD

private fun windowsSetAcl(path: Path, principal: UserPrincipal, directory: Boolean) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
        ?: error("Windows ACL view unavailable")
    view.setAcl(windowsAcl(principal, directory))
}

private fun windowsReadAcl(path: Path): List<AclEntry> {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
        ?: error("Windows ACL view unavailable")
    return view.acl
}

private fun windowsCreateDirectory(path: Path, principal: UserPrincipal) {
    Files.createDirectory(path, windowsAclAttribute(principal, directory = true))
    windowsSetAcl(path, principal, directory = true)
}

private fun windowsPrivateDirectory(path: Path, principal: UserPrincipal) {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther)
    check(Files.getOwner(path, NOFOLLOW_LINKS) == principal)
    val acl = windowsReadAcl(path)
    check(acl.isNotEmpty())
    check(acl.none { it.type() == AclEntryType.ALLOW && it.principal() != principal })
    val applicable = acl.firstOrNull { it.type() == AclEntryType.ALLOW && it.principal() == principal &&
        AclEntryFlag.INHERIT_ONLY !in it.flags() }
    check(applicable != null && applicable.permissions().containsAll(windowsDirectoryPermissions))
    check(applicable.flags().containsAll(setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)))
}

private fun windowsPrivateFile(path: Path, principal: UserPrincipal) {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther)
    check(Files.getOwner(path, NOFOLLOW_LINKS) == principal)
    val acl = windowsReadAcl(path)
    check(acl.isNotEmpty())
    check(acl.none { it.type() == AclEntryType.ALLOW && it.principal() != principal })
    val applicable = acl.firstOrNull { it.type() == AclEntryType.ALLOW && it.principal() == principal &&
        AclEntryFlag.INHERIT_ONLY !in it.flags() }
    check(applicable != null && applicable.permissions().containsAll(windowsFilePermissions))
}

private fun windowsIdentity(path: Path): String =
    DesktopReportNative.windowsFileIdentity(path, Files.isDirectory(path, NOFOLLOW_LINKS))

private fun windowsEnsureIdentity(path: Path) {
    check(DesktopReportNative.windowsFileIdentity(path, Files.isDirectory(path, NOFOLLOW_LINKS)).isNotEmpty())
}

private fun windowsOpenLock(path: Path, principal: UserPrincipal): FileChannel {
    val existed = Files.exists(path, NOFOLLOW_LINKS)
    var created = false
    val channel = if (existed) {
        FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW_LINKS))
    } else {
        try {
            FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW, NOFOLLOW_LINKS), windowsAclAttribute(principal, directory = false))
                .also { created = true }
        } catch (_: FileAlreadyExistsException) {
            FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW_LINKS))
        }
    }
    try {
        if (created) windowsSetAcl(path, principal, directory = false)
        windowsPrivateFile(path, principal)
        windowsEnsureIdentity(path)
        return channel
    } catch (failure: Exception) {
        channel.close()
        throw failure
    }
}

private fun readWindowsFile(path: Path, maximum: Int): ByteArray =
    FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.allocate(maximum + 1)
        try {
            while (buffer.hasRemaining()) { val read = channel.read(buffer); if (read < 0) break; check(read > 0) }
            check(buffer.position() <= maximum)
            buffer.array().copyOf(buffer.position())
        } finally { buffer.array().fill(0) }
    }

private fun linuxLong(path: Path, name: String): Long = (Files.getAttribute(path, "unix:$name", NOFOLLOW_LINKS) as Number).toLong()
private fun linuxIdentity(path: Path): String = "linux:${linuxLong(path, "dev")}:${linuxLong(path, "ino")}".also { check(it.length <= 256) }
private fun privateDirectory(path: Path, uid: Long) {
    check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && linuxLong(path, "uid") == uid)
    check((linuxLong(path, "mode") and 511L) == 448L)
}
private fun privateFile(path: Path, uid: Long) {
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attrs.isRegularFile && !attrs.isSymbolicLink && linuxLong(path, "uid") == uid && (linuxLong(path, "mode") and 511L) == 384L)
    check(linuxLong(path, "nlink") in 1L..2L)
}
private fun forceLinuxDirectory(path: Path) {
    FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { it.force(true) }
}
private fun readPosixFile(path: Path, maximum: Int, force: Boolean = false): ByteArray =
    FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.allocate(maximum + 1)
        try {
            while (buffer.hasRemaining()) { val read = channel.read(buffer); if (read < 0) break; check(read > 0) }
            check(buffer.position() <= maximum)
            if (force) channel.force(true)
            buffer.array().copyOf(buffer.position())
        } finally { buffer.array().fill(0) }
    }
private fun effectiveLinuxUid(): Long = readPosixFile(Paths.get("/proc/self/status"), 65_536).useCleared { bytes ->
    val uid = bytes.decodeToString().lineSequence().single { it.startsWith("Uid:") }.removePrefix("Uid:").trim().split(Regex("\\s+"))
    check(uid.size == 4 && uid.distinct().size == 1)
    uid[1].toLong().also { check(it >= 0) }
}

/** Actual mount record + kernel block-device observation, not a caller's "local=true" flag. */
private fun qualifyLinuxStorage(path: Path) {
    check(System.getProperty("os.name").equals("Linux", ignoreCase = true))
    val mounts = readPosixFile(Paths.get("/proc/self/mountinfo"), 1_048_576).useCleared { it.decodeToString() }
    val candidates = mounts.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
        val split = line.split(" - ")
        check(split.size == 2)
        val before = split[0].split(' '); val after = split[1].split(' ')
        check(before.size >= 6 && after.size >= 3)
        val mount = Paths.get(before[4].replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\"))
        if (path.startsWith(mount)) Triple(mount, before, after) else null
    }.toList()
    val mounted = candidates.maxByOrNull { it.first.toString().length } ?: fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE)
    check(mounted.third[0] in setOf("ext4", "xfs", "btrfs"))
    check("rw" in mounted.second[5].split(','))
    val device = mounted.second[2]
    check(Regex("[1-9][0-9]*:[0-9]+").matches(device) && Files.exists(Paths.get("/sys/dev/block/$device")))
    check(Files.getFileStore(path).supportsFileAttributeView("posix"))
}

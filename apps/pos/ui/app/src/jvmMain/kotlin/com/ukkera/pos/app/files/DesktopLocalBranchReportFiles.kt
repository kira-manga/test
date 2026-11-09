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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Concrete Linux/POSIX native Save dialog and private local file/receipt custody. The native
 * chooser is intentionally restricted to one app-owned directory, never arbitrary Downloads,
 * cloud folders, SAF or a mapped drive. Unsupported OS/provider/fsync/hard-link paths fail closed.
 * Structural local-storage checks are NOT independent installed-storage or device qualification;
 * Windows/macOS remain required and UNVERIFIED, not silently accepted via a FileStore string.
 */
class DesktopLocalBranchReportFiles(
    private val directory: Path = Paths.get(System.getProperty("user.home").orEmpty(), ".local", "share", "ukkera-pos", "report-export-v1"),
) : LocalBranchReportFileDelivery(), AutoCloseable {
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
    private val supported: Boolean get() = System.getProperty("os.name").equals("Linux", ignoreCase = true)
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
                    val chooser = when (val window = current.window) {
                        is Frame -> FileDialog(window, "Save local CSV / حفظ CSV محلي", FileDialog.SAVE)
                        is Dialog -> FileDialog(window, "Save local CSV / حفظ CSV محلي", FileDialog.SAVE)
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
                    val choice = if (name != null && selectedDirectory != null && validLocalReportFileName(name) &&
                        Paths.get(selectedDirectory).toAbsolutePath().normalize() == Paths.get(directoryLabel) &&
                        hostCurrent(captured) && lifetime.isCurrent())
                        LocalReportNativeChoice(captured, name, directoryLabel, LocalBranchReportFileDestinationKind.DESKTOP_LOCAL_FILE)
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
    private val root: Path,
    private val uid: Long,
    private val keys: Map<String, String>,
    private val lockChannel: FileChannel,
    private val fileLock: java.nio.channels.FileLock,
) : LocalReportReceiptDisk(), AutoCloseable {
    val publishedDirectory: Path get() = root.resolve("published")
    private fun resolve(relative: String): Path {
        check(relative.isEmpty() || relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it })
        return root.resolve(relative).normalize().also { check(it.startsWith(root)) }
    }

    fun current() {
        check(fileLock.isValid && lockChannel.isOpen)
        qualifyLinuxStorage(root)
        keys.forEach { (name, key) ->
            val item = resolve(name)
            if (name == "custody.lock") privateFile(item, uid) else privateDirectory(item, uid)
            check(linuxIdentity(item) == key)
        }
        check(entries("", 3).toSet() == setOf("custody.lock", "receipts", "published"))
    }

    override fun entries(directory: String, maximum: Int): List<String> {
        val at = resolve(directory)
        privateDirectory(at, uid)
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
        privateDirectory(at.parent, uid)
        Files.createDirectory(at, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        privateDirectory(at, uid)
    }

    override fun exists(path: String): Boolean = Files.exists(resolve(path), NOFOLLOW_LINKS)
    override fun identity(path: String): String = linuxIdentity(resolve(path))

    override fun readBounded(path: String, maximum: Int): ByteArray {
        val at = resolve(path)
        privateFile(at, uid)
        val before = linuxIdentity(at)
        val result = readLinuxFile(at, maximum, force = true)
        try { check(linuxIdentity(at) == before); privateFile(at, uid); return result }
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
        privateDirectory(at.parent, uid)
        val options = setOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW_LINKS)
        val attributes = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val buffer = ByteBuffer.wrap(bytes)
        if (commitStart != null && !commitStart.admit()) return false
        FileChannel.open(at, options, attributes).use { file ->
            while (buffer.hasRemaining()) check(file.write(buffer) > 0)
            file.force(true)
        }
        privateFile(at, uid)
        return true
    }

    override fun forceDirectory(path: String) { val at = resolve(path); privateDirectory(at, uid); forceLinuxDirectory(at) }

    override fun linkComplete(receipt: LocalBranchReportFileReceipt, commitStart: LocalReportCommitStart): LocalReportLinkOutcome {
        val source = resolve(spool(receipt.exportId))
        val destination = resolve(published(receipt.fileName))
        return try {
            verifySpool(receipt)
            privateDirectory(destination.parent, uid)
            current()
            if (!commitStart.admit()) return LocalReportLinkOutcome.NOT_ATTEMPTED
            // link(2) creates a complete name atomically and fails if ANY target already exists.
            // Unlike ATOMIC_MOVE, it cannot replace a target between a precheck and publication.
            Files.createLink(destination, source)
            if (verifyPublished(receipt)) LocalReportLinkOutcome.DURABLE else LocalReportLinkOutcome.UNCERTAIN
        } catch (_: FileAlreadyExistsException) { LocalReportLinkOutcome.NOT_ATTEMPTED }
        catch (_: Exception) { LocalReportLinkOutcome.UNCERTAIN }
    }

    override fun verifyPublished(receipt: LocalBranchReportFileReceipt): Boolean = try {
        val at = resolve(published(receipt.fileName))
        val original = resolve(spool(receipt.exportId))
        privateFile(at, uid); privateFile(original, uid)
        check(linuxIdentity(at) == receipt.spoolIdentity && Files.isSameFile(at, original))
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
                val uid = effectiveLinuxUid()
                check(requested.isAbsolute && requested == requested.normalize() && requested.nameCount in 1..64)
                var ancestor = requested
                while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = checkNotNull(ancestor.parent)
                qualifyLinuxStorage(ancestor)
                var cursor = requested.root
                requested.forEach { part ->
                    val next = cursor.resolve(part)
                    if (!Files.exists(next, NOFOLLOW_LINKS)) {
                        Files.createDirectory(next, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                        forceLinuxDirectory(cursor)
                    }
                    val mode = linuxLong(next, "mode")
                    check(Files.isDirectory(next, NOFOLLOW_LINKS) && !Files.isSymbolicLink(next))
                    check(linuxLong(next, "uid") in setOf(0L, uid))
                    check((mode and 18L) == 0L) // no group/other writers in the path
                    cursor = next
                }
                val root = requested.toRealPath(NOFOLLOW_LINKS)
                check(root == requested)
                privateDirectory(root, uid)
                for (name in listOf("receipts", "published")) {
                    val at = root.resolve(name)
                    if (!Files.exists(at, NOFOLLOW_LINKS)) {
                        Files.createDirectory(at, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                        forceLinuxDirectory(root)
                    }
                    privateDirectory(at, uid)
                }
                val lockPath = root.resolve("custody.lock")
                if (Files.exists(lockPath, NOFOLLOW_LINKS)) privateFile(lockPath, uid)
                val channel = FileChannel.open(lockPath, setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                try {
                    val lock = channel.tryLock() ?: fileFailure(LocalBranchReportFileProblem.STORAGE_UNCERTAIN)
                    privateFile(lockPath, uid)
                    channel.force(true); forceLinuxDirectory(root)
                    val keys = listOf("", "receipts", "published", "custody.lock").associateWith { linuxIdentity(root.resolve(it)) }
                    return DesktopReportReceiptDisk(root, uid, keys, channel, lock).also { it.current(); it.receipts() }
                } catch (failure: Exception) { channel.close(); throw failure }
            } catch (failure: LocalReportFileFailure) { throw failure }
            catch (_: Exception) { fileFailure(LocalBranchReportFileProblem.UNSUPPORTED_STORAGE) }
        }
    }
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
private fun readLinuxFile(path: Path, maximum: Int, force: Boolean = false): ByteArray =
    FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.allocate(maximum + 1)
        try {
            while (buffer.hasRemaining()) { val read = channel.read(buffer); if (read < 0) break; check(read > 0) }
            check(buffer.position() <= maximum)
            if (force) channel.force(true)
            buffer.array().copyOf(buffer.position())
        } finally { buffer.array().fill(0) }
    }
private fun effectiveLinuxUid(): Long = readLinuxFile(Paths.get("/proc/self/status"), 65_536).useCleared { bytes ->
    val uid = bytes.decodeToString().lineSequence().single { it.startsWith("Uid:") }.removePrefix("Uid:").trim().split(Regex("\\s+"))
    check(uid.size == 4 && uid.distinct().size == 1)
    uid[1].toLong().also { check(it >= 0) }
}

/** Actual mount record + kernel block-device observation, not a caller's "local=true" flag. */
private fun qualifyLinuxStorage(path: Path) {
    check(System.getProperty("os.name").equals("Linux", ignoreCase = true))
    val mounts = readLinuxFile(Paths.get("/proc/self/mountinfo"), 1_048_576).useCleared { it.decodeToString() }
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

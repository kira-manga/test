package com.ukkera.pos.sales.store

import com.ukkera.pos.security.WindowsDesktopFiles
import com.ukkera.pos.security.WindowsEntryObservation
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * True only on a Windows host whose default file system has no POSIX attributes. The desktop
 * Master service then keeps its physical journals in its service-private NTFS directory (exact
 * protected DACL of the service account, inherited by every child), never a POSIX profile.
 */
internal fun windowsPhysicalProfile(root: Path): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows") &&
        "unix" !in root.fileSystem.supportedFileAttributeViews()

/**
 * Windows full-write manager for the same physical journal format. Every entry is observed by
 * handle (volume serial, 128-bit file ID, owner SID, link count, no reparse point); the open file
 * holds a mandatory exclusive whole-file lock; writes are complete and FlushFileBuffers-forced.
 * Each access re-observes the directory and file by path and compares them with the opened
 * identities, so a renamed, replaced or foreign entry is held. NTFS directory metadata is
 * journaled; Java cannot flush a Windows directory handle, so only file data is forced.
 */
internal fun windowsAcquirePhysicalNative(directory: Path, spec: PhysicalNativeFileSpecV1, keyId: DomainId,
    cipherFactory: () -> PhysicalSpoolCipher, expected: LocalPhysicalSpoolNativeIdentity?, create: Boolean): PhysicalNativeOpenV1 {
    var channel: FileChannel? = null
    var fileLock: FileLock? = null
    var processOwner: JvmPhysicalSpoolProcessOwner? = null
    var nativeFile: WindowsPhysicalSpoolFile? = null
    var transferred = false
    var result: PhysicalNativeOpenV1 = PhysicalNativeOpenV1.Held
    try {
        check(!Thread.currentThread().isInterrupted && keyId == spec.keyId)
        if (expected != null) check(expected.profile == LocalPhysicalSpoolStorageProfileV1.JVM_WINDOWS_NTFS_FULL_WRITE_FLUSH_V1)
        val cipher = cipherFactory()
        check(directory.isAbsolute && directory.normalize() == directory && directory.fileSystem === FileSystems.getDefault())
        val namespace = WindowsPhysicalJournalNamespace.capture(directory)
        val directoryStamp = windowsSpoolDirectory(directory)
        val owner = JvmPhysicalSpoolProcessOwner.acquire(windowsSpoolIdentity(directoryStamp) + ":" + spec.filename)
        processOwner = owner
        windowsSpoolEntries(directory, spec.layout, spec.filename, create)
        val path = directory.resolve(spec.filename)
        val beforeFile = if (create) null else windowsSpoolFile(path, directoryStamp.ownerSid)
        val options = mutableSetOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        if (create) options += StandardOpenOption.CREATE_NEW
        val opened = FileChannel.open(path, options)
        channel = opened
        val lock = try { opened.tryLock(0L, Long.MAX_VALUE, false) }
        catch (_: OverlappingFileLockException) { throw JvmPhysicalSpoolBusy() }
            ?: throw JvmPhysicalSpoolBusy()
        fileLock = lock
        val capturedFile = windowsSpoolFile(path, directoryStamp.ownerSid)
        if (beforeFile != null) check(windowsSpoolIdentity(beforeFile) == windowsSpoolIdentity(capturedFile))
        check(windowsSpoolIdentity(windowsSpoolDirectory(directory)) == windowsSpoolIdentity(directoryStamp))
        val native = LocalPhysicalSpoolNativeIdentity(LocalPhysicalSpoolStorageProfileV1.JVM_WINDOWS_NTFS_FULL_WRITE_FLUSH_V1,
            namespace.identity, windowsSpoolIdentity(capturedFile), "sid=" + directoryStamp.ownerSid, cipher.identity)
        val owned = WindowsPhysicalSpoolFile(directory, path, opened, lock, owner, native, namespace,
            windowsSpoolIdentity(directoryStamp), directoryStamp.ownerSid, initializing = create, layout = spec.layout,
            filename = spec.filename)
        nativeFile = owned
        if (expected != null) check(expected == native)
        result = PhysicalNativeOpenV1.Opened(PhysicalNativeOwnedFileV1(native, owned, cipher))
        transferred = true
    } catch (_: JvmPhysicalSpoolBusy) {
        result = PhysicalNativeOpenV1.Busy
    } catch (_: Exception) {
        result = PhysicalNativeOpenV1.Held
    } finally {
        if (!transferred) {
            val owned = nativeFile
            val complete = if (owned != null) owned.close() == LocalPhysicalSpoolClose.CLOSED
            else {
                var cleaned = jvmSpoolCleanup { fileLock?.let { if (it.isValid) it.release() } }
                cleaned = jvmSpoolCleanup { channel?.close() } && cleaned
                if (cleaned) cleaned = processOwner?.release() ?: true
                cleaned
            }
            if (!complete) result = PhysicalNativeOpenV1.Held
        }
    }
    return result
}

/** Bounded listing of the installed leaf, bound to the opened namespace identity; untrusted metadata. */
internal fun windowsListPhysicalNative(directory: Path, expectedDirectoryIdentity: String): List<String>? = try {
    check(!Thread.currentThread().isInterrupted && directory.isAbsolute && directory.normalize() == directory &&
        directory.fileSystem === FileSystems.getDefault())
    val namespace = WindowsPhysicalJournalNamespace.capture(directory)
    check(namespace.identity == expectedDirectoryIdentity)
    val names = windowsSpoolNames(directory, LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS)
    namespace.requireCurrent()
    names
} catch (_: Exception) { null }

private class WindowsPhysicalSpoolFile(
    private val directory: Path,
    private val path: Path,
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val processOwner: JvmPhysicalSpoolProcessOwner,
    private val native: LocalPhysicalSpoolNativeIdentity,
    private val namespace: WindowsPhysicalJournalNamespace,
    private val directoryIdentity: String,
    private val ownerSid: String,
    private var initializing: Boolean,
    private val layout: PhysicalNativeLayoutV1,
    private val filename: String,
) : PhysicalSpoolFile {
    private val mutex = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    private val processId = ProcessHandle.current().pid()
    private var cleanupResult: LocalPhysicalSpoolClose? = null
    private val leaseDenials = ConcurrentHashMap<PhysicalSpoolLease, () -> Unit>()

    override fun <T> access(block: () -> T): PhysicalSpoolAccess<T> {
        if (closed.get() || retired.get()) return PhysicalSpoolAccess.Held
        if (!mutex.tryLock()) return PhysicalSpoolAccess.Busy
        return try {
            current()
            val value = block()
            current()
            PhysicalSpoolAccess.Value(value)
        } catch (_: Exception) {
            retired.set(true)
            denyLeases()
            PhysicalSpoolAccess.Held
        } finally { mutex.unlock() }
    }

    override fun initialize(bytes: ByteArray) {
        current()
        check(initializing && channel.size() == 0L && bytes.size == layout.bytes)
        // Every byte is written (randomized encrypted vacancy included); no sparse sizing or truncate.
        val buffer = ByteBuffer.wrap(bytes)
        var offset = 0L
        while (buffer.hasRemaining()) {
            current()
            val count = channel.write(buffer, offset)
            check(count > 0)
            offset += count
        }
        check(offset == layout.bytes.toLong())
        initializing = false
        force()
    }

    override fun read(offset: Int, size: Int): ByteArray {
        layout.range(offset, size)
        current()
        check(!initializing)
        val bytes = ByteArray(size)
        var transferred = false
        try {
            val buffer = ByteBuffer.wrap(bytes)
            var position = offset.toLong()
            while (buffer.hasRemaining()) {
                current()
                val count = channel.read(buffer, position)
                check(count > 0)
                position += count
            }
            current()
            transferred = true
            return bytes
        } finally { if (!transferred) bytes.fill(0) }
    }

    override fun overwrite(offset: Int, bytes: ByteArray) {
        layout.range(offset, bytes.size, writable = true)
        current()
        check(!initializing)
        val buffer = ByteBuffer.wrap(bytes)
        var position = offset.toLong()
        while (buffer.hasRemaining()) {
            current()
            val count = channel.write(buffer, position)
            check(count > 0)
            position += count
        }
        current()
    }

    override fun force() {
        current()
        check(!initializing)
        channel.force(true) // FlushFileBuffers
        current()
    }

    private fun current() {
        processOwner.requireCurrent()
        check(!closed.get() && !retired.get() && !Thread.currentThread().isInterrupted && ProcessHandle.current().pid() == processId)
        check(channel.isOpen && fileLock.isValid && !fileLock.isShared && fileLock.position() == 0L && fileLock.size() == Long.MAX_VALUE)
        namespace.requireCurrent()
        val directoryStamp = windowsSpoolDirectory(directory)
        check(windowsSpoolIdentity(directoryStamp) == directoryIdentity && directoryStamp.ownerSid == ownerSid)
        val file = windowsSpoolFile(path, ownerSid)
        check(windowsSpoolIdentity(file) == native.fileIdentity && native.ownerIdentity == "sid=$ownerSid")
        val size = channel.size()
        check(file.size == size)
        if (initializing) check(size in 0L..layout.bytes.toLong()) else check(size == layout.bytes.toLong())
        windowsSpoolEntries(directory, layout, filename, create = false)
    }

    override fun capture(onRetirement: () -> Unit): PhysicalSpoolLease? {
        var candidate: PhysicalSpoolLease? = null
        var transferred = false
        var failure: Throwable? = null
        try {
            val result = access {
                val lease = object : PhysicalSpoolLease {
                    private val released = AtomicBoolean(false)
                    override fun release() { if (released.compareAndSet(false, true)) leaseDenials.remove(this) }
                }
                candidate = lease
                leaseDenials[lease] = onRetirement
                lease
            }
            if (result is PhysicalSpoolAccess.Value) {
                check(result.value === candidate)
                transferred = true
                return result.value
            }
            return null
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (!transferred) cleanupUntransferredPhysicalSpoolCapture(candidate, onRetirement, failure)
        }
    }

    private fun denyLeases() {
        leaseDenials.values.forEach { deny -> try { deny() } catch (_: Exception) { retired.set(true) } }
    }

    override fun close(): LocalPhysicalSpoolClose {
        closed.set(true)
        denyLeases()
        mutex.lock()
        try {
            cleanupResult?.let { return it }
            if (leaseDenials.isNotEmpty()) return LocalPhysicalSpoolClose.HELD
            var complete = jvmSpoolCleanup { if (fileLock.isValid) fileLock.release() }
            complete = jvmSpoolCleanup { channel.close() } && complete
            if (complete) complete = processOwner.release()
            return (if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD).also {
                cleanupResult = it
                if (!complete) retired.set(true)
            }
        } finally { mutex.unlock() }
    }
}

/** Exact ancestor identities from the leaf up to the volume root; any change is held. */
private class WindowsPhysicalJournalNamespace private constructor(private val records: List<Pair<Path, String>>) {
    val identity: String = "windows-journal-namespace/v1:" + records.flatMap { listOf(it.first.toString(), it.second) }
        .joinToString("") { "${it.length}:$it" }.encodeToByteArray(throwOnInvalidSequence = true)
        .withSpoolBytes { com.ukkera.pos.security.sha256(it).value }

    fun requireCurrent() {
        for ((path, expected) in records) {
            val observed = WindowsDesktopFiles.observe(path)
            check(observed.directory && windowsSpoolIdentity(observed) + "|" + observed.ownerSid == expected)
        }
    }

    companion object {
        fun capture(directory: Path): WindowsPhysicalJournalNamespace {
            val records = mutableListOf<Pair<Path, String>>()
            var path: Path? = directory
            // Only the service-private chain is bound; the volume root and shared system folders are not ours.
            while (path != null && path.nameCount > 1) {
                check(records.size < 32)
                val observed = WindowsDesktopFiles.observe(path)
                check(observed.directory)
                records += path to (windowsSpoolIdentity(observed) + "|" + observed.ownerSid)
                path = path.parent
            }
            check(records.isNotEmpty())
            return WindowsPhysicalJournalNamespace(records.toList()).also { it.requireCurrent() }
        }
    }
}

private fun windowsSpoolIdentity(observed: WindowsEntryObservation): String =
    "ntfs:%016x:%s".format(observed.volumeSerial, observed.fileId)

private fun windowsSpoolDirectory(directory: Path): WindowsEntryObservation {
    val observed = WindowsDesktopFiles.observe(directory)
    check(observed.directory)
    return observed
}

private fun windowsSpoolFile(path: Path, ownerSid: String): WindowsEntryObservation {
    val observed = WindowsDesktopFiles.observe(path)
    check(!observed.directory && observed.links == 1 && observed.ownerSid == ownerSid)
    return observed
}

private fun windowsSpoolNames(directory: Path, maximum: Int): List<String> {
    val names = mutableListOf<String>()
    Files.newDirectoryStream(directory).use { entries ->
        for (entry in entries) {
            check(names.size < maximum)
            names += entry.fileName.toString()
        }
    }
    return names
}

/** Same closed-layout rules as the POSIX manager: canonical names only, append-only generations. */
private fun windowsSpoolEntries(directory: Path, layout: PhysicalNativeLayoutV1, filename: String, create: Boolean) {
    val target = layout.generationOf(filename) ?: error("physical.spool.filename")
    var targetSeen = false
    for (name in windowsSpoolNames(directory, LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS)) {
        val generation = layout.generationOf(name) ?: error("physical.spool.entry")
        if (name == filename) targetSeen = true
        if (create) check(generation < target)
    }
    check(if (create) !targetSeen else targetSeen)
}

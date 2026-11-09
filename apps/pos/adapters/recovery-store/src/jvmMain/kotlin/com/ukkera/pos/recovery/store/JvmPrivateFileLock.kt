package com.ukkera.pos.recovery.store

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Opaque cooperative JVM lock, NOT native custody/writer/child-reap proof. One shared registry
 * reserves path and nonnull native file identity BEFORE opening a channel, including hard-link
 * aliases across host and backup callers. POSIX process locks may be released by closing ANY
 * descriptor for their inode; a failed competing acquisition must never open/close a second one.
 *
 * Use only in trusted composition under qualified ancestor/process custody. Unknown open/identity/
 * lock cleanup retains any acquired descriptor and disables further acquisitions in this JVM.
 * The owner must arrange OS retirement on uncertainty; no accepting supervisor is supplied here.
 */
public class JvmPrivateFileLock private constructor(private val entry: PrivateFileLockEntry) : AutoCloseable {
    /** Local object state only; neither a fresh syscall nor evidence of qualified OS exclusivity. */
    public val isLocallyHeld: Boolean
        get() = !entry.closed && entry.channel?.isOpen == true && entry.lock?.isValid == true

    /** Invoke only once protected work/handles are actually settled. No speculative close on error. */
    public override fun close(): Unit {
        try {
            synchronized(monitor) {
                if (entry.closed) return
                if (entry !in entries) fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE)
                try {
                    checkNotNull(entry.lock).release()
                    checkNotNull(entry.channel).close()
                    entry.closed = true
                    entries.remove(entry)
                } catch (failure: Throwable) {
                    uncertain = true
                    throw failure
                }
            }
        } catch (failure: LocalBackupException) { throw failure }
        catch (_: Exception) { fail(LocalBackupFailure.STORAGE_UNAVAILABLE) }
    }

    override fun toString(): String = "JvmPrivateFileLock([REDACTED])"

    public companion object {
        private val monitor = Any()
        private val entries = mutableListOf<PrivateFileLockEntry>()
        private var uncertain = false

        public fun acquire(path: Path): JvmPrivateFileLock {
            try {
                if (!path.isAbsolute || path != path.normalize() || path.parent == null) {
                    fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
                }
                privateDirectory(path.parent)
                return synchronized(monitor) {
                    if (uncertain || entries.any { it.path == path }) fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE)
                    val before = privateLockAttributesOrAbsent(path)?.fileKey()
                    if (before != null && entries.any { it.fileKey == before }) fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE)
                    val entry = PrivateFileLockEntry(path, before)
                    entries.add(entry)
                    try {
                        val channel = FileChannel.open(path, setOf(CREATE, WRITE, NOFOLLOW_LINKS),
                            PosixFilePermissions.asFileAttribute(PRIVATE_FILE))
                        entry.channel = channel
                        val after = privateLockAttributesOrAbsent(path)?.fileKey()
                            ?: fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
                        if ((before != null && before != after) || entries.any { it !== entry && it.fileKey == after }) {
                            fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE)
                        }
                        entry.fileKey = after
                        val locked = channel.tryLock()
                        if (locked == null) {
                            // A normal refusal by another process is known not to own this lock.
                            // The registry excluded any cooperative same-JVM descriptor first.
                            channel.close()
                            entry.closed = true
                            entries.remove(entry)
                            fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE)
                        }
                        entry.lock = locked
                        JvmPrivateFileLock(entry)
                    } catch (failure: Throwable) {
                        // In particular, OverlappingFileLockException may name an untracked
                        // same-process owner. Retain the second descriptor, do NOT close it.
                        if (!entry.closed) uncertain = true
                        throw failure
                    }
                }
            } catch (failure: LocalBackupException) { throw failure }
            catch (_: Exception) { fail(LocalBackupFailure.STORAGE_UNAVAILABLE) }
        }
    }
}

private class PrivateFileLockEntry(val path: Path, var fileKey: Any?) {
    var channel: FileChannel? = null
    var lock: FileLock? = null
    @Volatile var closed: Boolean = false
}

/** Only a known missing path permits creation; unreadable identity is never treated as absence. */
private fun privateLockAttributesOrAbsent(path: Path): BasicFileAttributes? {
    val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
    catch (_: NoSuchFileException) { return null }
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.fileKey() == null || view == null ||
        view.readAttributes().permissions().any { it !in PRIVATE_FILE }) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    return attributes
}

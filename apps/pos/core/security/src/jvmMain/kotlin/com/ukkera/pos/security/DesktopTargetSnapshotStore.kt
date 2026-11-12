package com.ukkera.pos.security

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Desktop Client target directory: one exclusive lock for the whole lifetime and write-once
 * snapshots. A write goes to `<name>.pending`, is flushed, then renamed without replacement;
 * an interrupted write leaves its pending file, which is reported and never adopted or retried.
 * This store is custody plumbing only; platform owner/file-ID facts are observed separately.
 */
internal class DesktopTargetSnapshotStore private constructor(
    val directory: Path,
    private var channel: FileChannel?,
    private var lock: FileLock?,
) : AutoCloseable {

    @Synchronized
    fun names(): Set<String> = entries().filterNot { it.endsWith(PENDING) }.toSet()

    @Synchronized
    fun pendingNames(): Set<String> = entries().filter { it.endsWith(PENDING) }.map { it.removeSuffix(PENDING) }.toSet()

    @Synchronized
    fun read(name: String, maximum: Int): ByteArray? {
        requireOpen(); requireName(name)
        require(maximum in 1..MAXIMUM_SNAPSHOT_BYTES)
        val path = directory.resolve(name)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (Files.size(path) > maximum) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = Files.readAllBytes(path)
        if (bytes.size > maximum) { bytes.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        return bytes
    }

    /** Exact bounded copy of an unfinished pending write, for review only; never adopted. */
    @Synchronized
    fun readPending(name: String, maximum: Int): ByteArray? {
        requireOpen(); requireName(name)
        val path = directory.resolve(name + PENDING)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximum) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return Files.readAllBytes(path)
    }

    @Synchronized
    fun forceNew(name: String, bytes: ByteArray) {
        requireOpen(); requireName(name)
        if (bytes.isEmpty() || bytes.size > MAXIMUM_SNAPSHOT_BYTES) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val target = directory.resolve(name)
        val pending = directory.resolve(name + PENDING)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) out.write(buffer)
            out.force(true)
        }
        // From here an incomplete outcome leaves the pending file for review; never retried.
        try { Files.move(pending, target) } catch (_: FileAlreadyExistsException) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        syncDirectory(directory)
        val readback = Files.readAllBytes(target)
        try { if (!readback.contentEquals(bytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        finally { readback.fill(0) }
    }

    @Synchronized
    override fun close() {
        val held = lock; val open = channel
        lock = null; channel = null
        var failure: Throwable? = null
        try { held?.release() } catch (error: Throwable) { failure = error }
        try { open?.close() } catch (error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
        failure?.let { throw it }
    }

    private fun requireOpen() {
        if (channel == null || lock?.isValid != true) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    }

    /** Only regular snapshot/pending files and the lock may exist; anything else is unexplained state. */
    private fun entries(): List<String> {
        requireOpen()
        return Files.list(directory).use { stream ->
            stream.map { path ->
                val name = path.fileName.toString()
                if (name == LOCK) return@map null
                val base = name.removeSuffix(PENDING)
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !validName(base)) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                name
            }.toList().filterNotNull()
        }
    }

    companion object {
        const val LOCK = "owner.lock"
        const val PENDING = ".pending"
        const val MAXIMUM_SNAPSHOT_BYTES = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
        private val NAME = Regex("[a-z0-9][a-z0-9.-]{0,95}")

        private fun validName(name: String) = NAME.matches(name) && name != LOCK && !name.endsWith(PENDING)
        private fun requireName(name: String) { if (!validName(name)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }

        /** First creation only: the directory must not exist yet. */
        fun create(directory: Path): DesktopTargetSnapshotStore {
            val parent = directory.parent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            try {
                if (parent.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                } else {
                    Files.createDirectory(directory)
                }
            } catch (_: FileAlreadyExistsException) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val lockPath = directory.resolve(LOCK)
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            syncDirectory(parent); syncDirectory(directory)
            return lock(directory, channel)
        }

        /** Existing only: never creates a missing directory or lock. */
        fun openExisting(directory: Path): DesktopTargetSnapshotStore {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val lockPath = directory.resolve(LOCK)
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return lock(directory, FileChannel.open(lockPath, StandardOpenOption.WRITE))
        }

        private fun lock(directory: Path, channel: FileChannel): DesktopTargetSnapshotStore {
            val held = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (held == null) { channel.close(); selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY) }
            return DesktopTargetSnapshotStore(directory, channel, held).also { it.entries() }
        }

        /** Directory flush where the platform supports it (POSIX); Windows has no directory handle flush here. */
        private fun syncDirectory(directory: Path) {
            if (!directory.fileSystem.supportedFileAttributeViews().contains("posix")) return
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

/** Append-only boot-session ledger assigning desktop boot ordinals; see [TargetBoot.Desktop]. */
internal object DesktopTargetBootLedger {
    private const val DOMAIN = "target-desktop-boot-ledger-entry/v2"
    private const val PURPOSE = "DESKTOP_BOOT_SESSION_ORDINAL_DATA_ONLY_NO_AUTHORITY"
    private const val PREFIX = "boot-ledger-"
    private const val MAXIMUM_ENTRIES = 9_999

    fun entryName(ordinal: Int): String {
        require(ordinal in 0 until MAXIMUM_ENTRIES)
        return PREFIX + ordinal.toString().padStart(4, '0') + ".v2"
    }

    /** The latest recorded boot, or null when none was recorded. Any gap or invalid entry is refused. */
    fun current(store: DesktopTargetSnapshotStore): TargetBoot.Desktop? = entries(store).lastOrNull()

    /** Returns the current boot, appending exactly one entry for a never-seen session. */
    fun observe(store: DesktopTargetSnapshotStore, sessionId: String): TargetBoot.Desktop {
        if (!SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(sessionId)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val recorded = entries(store)
        recorded.lastOrNull()?.let { if (it.sessionId == sessionId) return it }
        if (recorded.any { it.sessionId == sessionId }) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val next = TargetBoot.Desktop(recorded.size, sessionId)
        if (next.ordinal >= MAXIMUM_ENTRIES) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = encode(next)
        try { store.forceNew(entryName(next.ordinal), bytes) } finally { bytes.fill(0) }
        return next
    }

    private fun entries(store: DesktopTargetSnapshotStore): List<TargetBoot.Desktop> {
        if (store.pendingNames().any { it.startsWith(PREFIX) }) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val names = store.names().filter { it.startsWith(PREFIX) }.sorted()
        return names.mapIndexed { index, name ->
            if (name != entryName(index)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val bytes = store.read(name, 256) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val boot = try { decode(bytes) } catch (_: IllegalArgumentException) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            } finally { bytes.fill(0) }
            if (boot.ordinal != index) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            boot
        }.also { list ->
            if (list.map { it.sessionId }.distinct().size != list.size) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun encode(value: TargetBoot.Desktop): ByteArray = selfOwnedEncode(DOMAIN, 256) {
        string(PURPOSE); int(value.ordinal); string(value.sessionId)
    }

    private fun decode(bytes: ByteArray): TargetBoot.Desktop = selfOwnedDecode(bytes, DOMAIN) {
        require(string(128) == PURPOSE)
        TargetBoot.Desktop(int(), string(36))
    }.also { decoded ->
        val again = encode(decoded)
        try { require(again.contentEquals(bytes)) } finally { again.fill(0) }
    }
}

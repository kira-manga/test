package com.ukkera.pos.security

/** Kind of an observed (never followed) file-system entry. */
internal enum class DesktopEntryKind { DIRECTORY, FILE, OTHER }

/**
 * Actual OS observation of one entry: volume + file ID (POSIX device/inode or Windows volume
 * serial/128-bit file ID), owning principal, kind, owner-only access (POSIX 0700/0600 without
 * extended ACLs, or a Windows protected owner-only DACL), link count, size and times.
 */
internal data class DesktopStamp(
    val volume: String,
    val fileId: String,
    val owner: String,
    val kind: DesktopEntryKind,
    val ownerOnly: Boolean,
    val links: Long,
    val size: Long,
    val modified: Long,
    val changed: Long,
) {
    fun sameIdentity(other: DesktopStamp): Boolean = volume == other.volume && fileId == other.fileId &&
        owner == other.owner && kind == other.kind && ownerOnly == other.ownerOnly && links == other.links
    /** Directory link counts track entries (POSIX subdirectories, APFS children), so they are not identity. */
    fun sameDirectory(other: DesktopStamp): Boolean = kind == DesktopEntryKind.DIRECTORY && other.kind == kind &&
        volume == other.volume && fileId == other.fileId && owner == other.owner && ownerOnly == other.ownerOnly
    fun sameFile(other: DesktopStamp): Boolean = sameIdentity(other) && size == other.size &&
        modified == other.modified && changed == other.changed
    override fun toString(): String = "DesktopStamp([REDACTED])"
}

/** One open OS handle/descriptor; closing is single-shot. */
internal interface DesktopHandle : AutoCloseable {
    fun stamp(): DesktopStamp
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun preadAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int
    fun pwriteAt(bytes: ByteArray, offset: Int, length: Int, position: Long): Int
    fun pread(buffer: ByteArray, position: Long): Int {
        var done = 0
        while (done < buffer.size) {
            val count = preadAt(buffer, done, buffer.size - done, position + done)
            if (count <= 0) break
            done += count
        }
        return done
    }
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun rewind()
    fun sync()
    /** Exclusive non-blocking lock held until close; false when another holder owns it. */
    fun tryLockExclusive(): Boolean
}

/**
 * Windows/macOS file custody primitives for the original namespaces. Paths are produced only by
 * [child] from the fixed [baseDirectory]; no caller-selected path reaches an implementation.
 * Every create is exclusive and owner-only for the current principal; [observe] never follows a
 * link and returns null only for a genuinely absent entry.
 */
internal interface DesktopFiles {
    val platform: SelfOwnedNativePlatformV2
    /** Actual owner string of entries the current principal creates (SID or decimal uid). */
    val ownerName: String
    /** Fixed, already existing, OS-protected application data root of this installation. */
    val baseDirectory: String
    fun child(directory: String, name: String): String
    fun observe(path: String): DesktopStamp?
    fun openDirectory(path: String): DesktopHandle
    fun openFile(path: String, write: Boolean): DesktopHandle
    /** Exclusive owner-only create; never opens an existing entry. */
    fun createFile(path: String): DesktopHandle
    /** Exclusive owner-only create; refuses an existing entry. */
    fun createDirectory(path: String)
    fun names(directory: String): List<String>
    /** Removes an owned file or empty directory this owner created; absent is a failure. */
    fun remove(path: String)
    /** Atomic same-directory replace of [to] by [from]. */
    fun rename(from: String, to: String)
    /** Canonical lowercase UUID of the local volume holding [handle]. */
    fun volumeUuid(handle: DesktopHandle): String

    fun targetNamespace(path: String, volume: String, directory: DesktopStamp, lock: DesktopStamp,
        database: DesktopStamp): SelfOwnedNativeNamespaceV2 = when (platform) {
        SelfOwnedNativePlatformV2.WINDOWS -> SelfOwnedNativeNamespaceV2.Windows(path, ownerName, volume,
            directory.fileId, lock.fileId, database.fileId)
        SelfOwnedNativePlatformV2.MACOS -> SelfOwnedNativeNamespaceV2.Mac(path, ownerName.toInt(), volume,
            directory.fileId.toLong(), lock.fileId.toLong(), database.fileId.toLong())
    }

    fun registryNamespace(path: String, volume: String, directory: DesktopStamp, lock: DesktopStamp): SelfOwnedRegistryNamespaceV2 =
        when (platform) {
            SelfOwnedNativePlatformV2.WINDOWS -> SelfOwnedRegistryNamespaceV2.Windows(path, ownerName, volume, directory.fileId, lock.fileId)
            SelfOwnedNativePlatformV2.MACOS -> SelfOwnedRegistryNamespaceV2.Mac(path, ownerName.toInt(), volume,
                directory.fileId.toLong(), lock.fileId.toLong())
        }

    /** Record-member Room database identity (POSIX V2 identity, or the Windows binding). */
    fun databaseIdentity(stamp: DesktopStamp, namespaceDigest: PayloadDigest, roomIdentity: String): NativeHistoryDatabaseIdentity =
        when (platform) {
            SelfOwnedNativePlatformV2.WINDOWS -> NativeHistoryDatabaseBindingWindowsV1(
                NativeHistoryDatabaseIdentityWindowsV1(stamp.volume, stamp.fileId, stamp.owner), namespaceDigest, roomIdentity)
            SelfOwnedNativePlatformV2.MACOS -> NativeHistoryDatabaseIdentityV2(stamp.volume.toLong(), stamp.fileId.toLong(),
                stamp.owner.toInt(), namespaceDigest, roomIdentity)
        }

    fun matchesDatabase(stamp: DesktopStamp, identity: NativeHistoryDatabaseIdentity): Boolean = when (identity) {
        is NativeHistoryDatabaseIdentityV2 -> platform == SelfOwnedNativePlatformV2.MACOS &&
            stamp.volume == identity.device.toString() && stamp.fileId == identity.inode.toString() && stamp.owner == identity.uid.toString()
        is NativeHistoryDatabaseBindingWindowsV1 -> platform == SelfOwnedNativePlatformV2.WINDOWS &&
            stamp.volume == identity.file.volumeSerial && stamp.fileId == identity.file.fileId && stamp.owner == identity.file.ownerSid
        is NativeHistoryDatabaseIdentityWindowsV1 -> false
    }
}

internal fun desktopKnown(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }

/** Reads exactly [size] bytes and requires EOF afterwards. */
internal fun DesktopHandle.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size); val tail = ByteArray(1)
    try {
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            desktopKnown(count > 0 && count <= size - offset); offset += count
        }
        desktopKnown(read(tail, 0, 1) <= 0)
        return bytes
    } catch (failure: Throwable) { bytes.fill(0); throw failure } finally { tail.fill(0) }
}

internal fun DesktopHandle.writeFully(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val written = write(bytes, offset, bytes.size - offset)
        desktopKnown(written > 0 && written <= bytes.size - offset); offset += written
    }
}

/**
 * One owner-only directory under the fixed base, held under its exclusive lock file for its whole
 * lifetime, with verified atomic replacement of small named records. Shared by the original target
 * namespace and the provisioner registry.
 */
internal class DesktopLockedDirectory private constructor(
    val files: DesktopFiles,
    val path: String,
    val createdThisLifetime: Boolean,
    private val allowed: Set<String>,
) : AutoCloseable {
    private val base = files.baseDirectory
    private lateinit var baseStamp: DesktopStamp
    private lateinit var baseHandle: DesktopHandle
    private lateinit var directoryStamp: DesktopStamp
    private lateinit var directoryHandle: DesktopHandle
    private lateinit var lockStamp: DesktopStamp
    private lateinit var lockHandle: DesktopHandle
    lateinit var volume: String
        private set
    private var closed = false

    val directory: DesktopStamp get() = directoryStamp
    val lock: DesktopStamp get() = lockStamp

    private fun open() {
        baseStamp = files.observe(base) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        desktopKnown(baseStamp.kind == DesktopEntryKind.DIRECTORY && baseStamp.owner == files.ownerName && baseStamp.ownerOnly)
        baseHandle = files.openDirectory(base)
        desktopKnown(baseStamp.sameDirectory(baseHandle.stamp()))
        if (createdThisLifetime) files.createDirectory(path)
        directoryStamp = files.observe(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireOwnedDirectory(directoryStamp)
        directoryHandle = files.openDirectory(path)
        desktopKnown(directoryStamp.sameDirectory(directoryHandle.stamp()))
        val prior = files.names(path)
        desktopKnown(prior.all { it in allowed || it == LOCK })
        desktopKnown(if (createdThisLifetime) prior.isEmpty() else LOCK in prior)
        val lockPath = files.child(path, LOCK)
        lockHandle = if (createdThisLifetime) files.createFile(lockPath) else files.openFile(lockPath, write = true)
        if (!lockHandle.tryLockExclusive()) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        lockStamp = lockHandle.stamp()
        desktopKnown(lockStamp.kind == DesktopEntryKind.FILE && lockStamp.owner == files.ownerName && lockStamp.ownerOnly &&
            lockStamp.links == 1L && lockStamp.sameFile(files.observe(lockPath) ?: lockStamp.copy(fileId = "")))
        volume = files.volumeUuid(directoryHandle)
        desktopKnown(files.volumeUuid(lockHandle) == volume && directoryStamp.volume == lockStamp.volume)
        if (createdThisLifetime) { lockHandle.sync(); directoryHandle.sync(); baseHandle.sync() }
    }

    fun requireOwnedDirectory(stamp: DesktopStamp) = desktopKnown(stamp.kind == DesktopEntryKind.DIRECTORY &&
        stamp.owner == files.ownerName && stamp.ownerOnly)

    fun requireOwnedFile(stamp: DesktopStamp, maximum: Long, allowEmpty: Boolean) = desktopKnown(
        stamp.kind == DesktopEntryKind.FILE && stamp.owner == files.ownerName && stamp.ownerOnly && stamp.links == 1L &&
            stamp.size in (if (allowEmpty) 0L else 1L)..maximum && stamp.volume == directoryStamp.volume)

    fun names(): Set<String> = files.names(path).toSet() - LOCK

    fun assertContinuity() {
        desktopKnown(!closed)
        desktopKnown(baseStamp.sameDirectory(files.observe(base) ?: baseStamp.copy(fileId = "")) &&
            baseStamp.sameDirectory(baseHandle.stamp()))
        desktopKnown(directoryStamp.sameDirectory(files.observe(path) ?: directoryStamp.copy(fileId = "")) &&
            directoryStamp.sameDirectory(directoryHandle.stamp()))
        val lockPath = files.child(path, LOCK)
        desktopKnown(lockStamp.sameFile(files.observe(lockPath) ?: lockStamp.copy(fileId = "")) && lockStamp.sameFile(lockHandle.stamp()))
        desktopKnown(files.names(path).all { it in allowed || it == LOCK || it.endsWith(PENDING) && it.removeSuffix(PENDING) in allowed })
    }

    /** (stamp, bytes) of [name], or null when absent. */
    fun read(name: String, maximum: Int): Pair<DesktopStamp, ByteArray>? {
        desktopKnown(name in allowed)
        val file = files.child(path, name)
        val expected = files.observe(file) ?: return null
        requireOwnedFile(expected, maximum.toLong(), allowEmpty = false)
        val handle = files.openFile(file, write = false)
        try {
            desktopKnown(expected.sameFile(handle.stamp()))
            val bytes = handle.readExactly(expected.size.toInt())
            try {
                desktopKnown(expected.sameFile(handle.stamp()) && expected.sameFile(files.observe(file) ?: expected.copy(fileId = "")))
            } catch (failure: Throwable) { bytes.fill(0); throw failure }
            return expected to bytes
        } finally { handle.close() }
    }

    /** Write-temporary, flush, read back, atomic rename, directory flush, re-read. */
    fun replace(name: String, bytes: ByteArray, maximum: Int): DesktopStamp {
        assertContinuity()
        desktopKnown(name in allowed && bytes.size in 1..maximum)
        val temporary = files.child(path, name + PENDING)
        desktopKnown(files.observe(temporary) == null)
        val handle = files.createFile(temporary)
        val written: DesktopStamp
        try {
            val created = handle.stamp()
            requireOwnedFile(created, maximum.toLong(), allowEmpty = true)
            handle.writeFully(bytes); handle.sync()
            written = handle.stamp()
            requireOwnedFile(written, maximum.toLong(), allowEmpty = false)
            desktopKnown(created.sameIdentity(written) && written.size == bytes.size.toLong())
            handle.rewind()
            val readback = handle.readExactly(bytes.size)
            try { desktopKnown(readback.contentEquals(bytes)) } finally { readback.fill(0) }
        } finally { handle.close() }
        files.rename(temporary, files.child(path, name))
        directoryHandle.sync()
        val (actual, read) = read(name, maximum) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try {
            desktopKnown(written.volume == actual.volume && written.fileId == actual.fileId && read.contentEquals(bytes))
        } finally { read.fill(0) }
        assertContinuity()
        return actual
    }

    fun sync() = directoryHandle.sync()

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (handle in listOf(lockHandle, directoryHandle, baseHandle)) {
            try { handle.close() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        failure?.let { throw it }
    }

    override fun toString(): String = "DesktopLockedDirectory([REDACTED])"

    companion object {
        const val LOCK: String = "lock.v2"
        const val PENDING: String = ".pending"

        /** Opens [name] under the base; [create] only when absent (ORIGINAL_ACTION_REQUIRED otherwise). */
        fun open(files: DesktopFiles, name: String, create: Boolean, allowed: Set<String>): DesktopLockedDirectory {
            val path = files.child(files.baseDirectory, name)
            val absent = files.observe(path) == null
            if (absent && !create) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            val opened = DesktopLockedDirectory(files, path, absent, allowed)
            try { opened.open() } catch (failure: Throwable) {
                runCatching { opened.closeOpened() }
                throw failure
            }
            return opened
        }
    }

    private fun closeOpened() {
        closed = true
        if (::lockHandle.isInitialized) runCatching { lockHandle.close() }
        if (::directoryHandle.isInitialized) runCatching { directoryHandle.close() }
        if (::baseHandle.isInitialized) runCatching { baseHandle.close() }
    }
}

/** [DesktopOwnedDirectoryFactory] over [DesktopLockedDirectory] for the provisioner registry. */
internal class DesktopFilesOwnedDirectories(private val files: DesktopFiles) : DesktopOwnedDirectoryFactory {
    override fun open(profile: SelfOwnedNativeProfileV1, role: String, create: Boolean): DesktopOwnedDirectory {
        desktopKnown(role == DesktopProvisionerRegistryFilesV2.ROLE)
        val directory = DesktopLockedDirectory.open(files, profile.provisionerDirectoryName, create,
            DesktopProvisionerRegistryFilesV2.NAMES)
        return object : DesktopOwnedDirectory {
            override val createdThisLifetime: Boolean get() = directory.createdThisLifetime
            override fun read(name: String, maximum: Int): ByteArray? = directory.read(name, maximum)?.second
            override fun replace(name: String, bytes: ByteArray) { directory.replace(name, bytes, maximumOf(name)) }
            override fun names(): Set<String> = directory.names()
            override fun namespace(): SelfOwnedRegistryNamespaceV2 =
                files.registryNamespace(directory.path, directory.volume, directory.directory, directory.lock)
            override fun assertContinuity() = directory.assertContinuity()
            override fun close() = directory.close()
        }
    }

    private fun maximumOf(name: String): Int = when (name) {
        DesktopProvisionerRegistryFilesV2.BOOTSTRAP -> DesktopProvisionerRegistryFilesV2.MAX_BOOTSTRAP
        DesktopProvisionerRegistryFilesV2.COMPANY -> DesktopProvisionerRegistryFilesV2.MAX_COMPANY
        DesktopProvisionerRegistryFilesV2.ENVELOPE -> DesktopProvisionerRegistryFilesV2.MAX_ENVELOPE
        else -> SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_ENVELOPE_BYTES
    }
}

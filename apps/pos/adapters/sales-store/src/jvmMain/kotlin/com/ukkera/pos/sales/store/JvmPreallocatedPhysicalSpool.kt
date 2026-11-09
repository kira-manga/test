package com.ukkera.pos.sales.store

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.security.sha256
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.Provider
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Existing installer-owned opaque-form key and explicit registered JCA provider. No raw key
 * bytes are accepted/exported/generated. Null key.format is required, but generic JCA does not
 * establish key size, hardware non-exportability, current alias enrollment or service custody.
 * Those remain deployment gates; replacing a native key/service requires retiring its handles.
 */
public class JvmPhysicalSpoolKey(
    public val keyId: DomainId,
    internal val key: SecretKey,
    internal val provider: Provider,
) {
    override fun toString(): String = "JvmPhysicalSpoolKeyV1([REDACTED])"
}

/**
 * Real POSIX fixed-file native prerequisite, NOT an accepting v2 financial-capacity adapter.
 * Requires the default local filesystem, secure directory streams, private no-follow paths,
 * exclusive file locking, full encrypted writes and file/directory force. Unsupported platforms
 * fail HELD. Full writes are deliberately NOT described as posix_fallocate/CoW/Room/WAL proof.
 */
public object JvmPreallocatedPhysicalSpool {
    public fun createNew(directory: Path, binding: LocalPhysicalSpoolBinding,
        key: JvmPhysicalSpoolKey): LocalPhysicalSpoolOpen = acquire(directory, binding, key, null)

    public fun openExisting(directory: Path, expected: LocalPhysicalSpoolReceipt,
        key: JvmPhysicalSpoolKey): LocalPhysicalSpoolOpen = acquire(directory, expected.binding, key, expected)

    private fun acquire(directory: Path, binding: LocalPhysicalSpoolBinding, key: JvmPhysicalSpoolKey,
        expected: LocalPhysicalSpoolReceipt?): LocalPhysicalSpoolOpen {
        val spec = PhysicalNativeFileSpecV1(binding.installationId, binding.keyId,
            listOf("physical-spool-v1", binding.installationId.value, binding.spoolId.value, binding.allocationId.value),
            PhysicalNativeLayoutV1.LEGACY)
        return legacySpoolFromNative(jvmAcquirePhysicalNative(directory, spec, key, expected?.nativeIdentity,
            expected == null), binding, expected)
    }
}

/**
 * Concrete POSIX/JCA full-write manager, not a fallocate/CoW/Windows/Apple qualification. The
 * installer must provision the private fixed anchor/catalogue/128-slot directory tree below
 * storageRoot and retain the actual opaque key/provider. No directory, key, repair or fallback
 * creation is performed here. Same-device roles use distinct native namespaces.
 */
public object JvmPreallocatedPhysicalJournal {
    public fun createCatalogue(storageRoot: Path, owner: LocalPhysicalCatalogueBinding,
        key: JvmPhysicalSpoolKey): LocalPhysicalCatalogueOpen = createPhysicalCatalogue(owner, files(storageRoot, key))

    public fun openCatalogue(storageRoot: Path, owner: LocalPhysicalCatalogueBinding,
        key: JvmPhysicalSpoolKey): LocalPhysicalCatalogueOpen = openPhysicalCatalogue(owner, files(storageRoot, key))

    private fun files(root: Path, key: JvmPhysicalSpoolKey): PhysicalJournalNativeFilesV1 = object : PhysicalJournalNativeFilesV1 {
        override fun open(spec: PhysicalNativeFileSpecV1, native: LocalPhysicalSpoolNativeIdentity?,
            create: Boolean): PhysicalNativeOpenV1 {
            var directory = root
            for (segment in spec.segments) directory = directory.resolve(segment)
            return jvmAcquirePhysicalNative(directory, spec, key, native, create)
        }

        override fun list(spec: PhysicalNativeFileSpecV1, expectedDirectoryIdentity: String): List<String>? {
            var directory = root
            for (segment in spec.segments) directory = directory.resolve(segment)
            return jvmListPhysicalNative(directory, expectedDirectoryIdentity)
        }
    }
}

private fun jvmAcquirePhysicalNative(directory: Path, spec: PhysicalNativeFileSpecV1,
    key: JvmPhysicalSpoolKey, expected: LocalPhysicalSpoolNativeIdentity?, create: Boolean): PhysicalNativeOpenV1 {
    var stream: DirectoryStream<Path>? = null
    var directoryChannel: FileChannel? = null
    var rawChannel: SeekableByteChannel? = null
    var fileLock: FileLock? = null
    var processOwner: JvmPhysicalSpoolProcessOwner? = null
    var nativeFile: JvmPhysicalSpoolFile? = null
    var transferred = false
    var result: PhysicalNativeOpenV1 = PhysicalNativeOpenV1.Held
    try {
        check(!Thread.currentThread().isInterrupted && key.keyId == spec.keyId)
        if (expected != null) check(expected.profile == LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1)
        val cipher = JvmPhysicalSpoolCipher(key)
        check(directory.isAbsolute && directory.normalize() == directory && directory.fileSystem === FileSystems.getDefault())
        val beforeDirectory = jvmSpoolDirectory(directory)
        val directoryUid = jvmSpoolUnixNumber(directory, "uid")
        jvmSpoolAncestors(directory, directoryUid)
        val namespace = if (spec.layout == PhysicalNativeLayoutV1.LEGACY) null else JvmPhysicalJournalNamespace.capture(directory)
        val openedStream = Files.newDirectoryStream(directory)
        stream = openedStream
        val secure = openedStream as? SecureDirectoryStream<Path> ?: error("physical.spool.secure_directory")
        val capturedDirectory = jvmSpoolDirectoryAttributes(secure)
        check(jvmSpoolFileIdentity(beforeDirectory) == jvmSpoolFileIdentity(capturedDirectory))
        // POSIX may release this process's locks when ANY descriptor for that inode closes.
        // Refuse a second local owner BEFORE it can open/close the already locked file. Generation
        // files intentionally share one installed leaf, so include the canonical filename.
        val owner = JvmPhysicalSpoolProcessOwner.acquire(jvmSpoolFileIdentity(capturedDirectory) + ":" + spec.filename)
        processOwner = owner
        jvmSpoolEntries(secure, spec, create)
        val openedDirectoryChannel = FileChannel.open(directory,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        directoryChannel = openedDirectoryChannel
        val relative = directory.fileSystem.getPath(spec.filename)
        val beforeFile = if (create) null else jvmSpoolNamedFile(secure, relative)
        val options = mutableSetOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        if (create) options += StandardOpenOption.CREATE_NEW
        val opened = secure.newByteChannel(relative, options,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        rawChannel = opened
        val channel = opened as? FileChannel ?: error("physical.spool.file_channel")
        val lock = try { channel.tryLock(0L, Long.MAX_VALUE, false) }
        catch (_: OverlappingFileLockException) { throw JvmPhysicalSpoolBusy() }
            ?: throw JvmPhysicalSpoolBusy()
        fileLock = lock
        val capturedFile = jvmSpoolNamedFile(secure, relative)
        if (beforeFile != null) check(jvmSpoolFileIdentity(beforeFile) == jvmSpoolFileIdentity(capturedFile))
        check(capturedFile.owner() == capturedDirectory.owner())
        val native = LocalPhysicalSpoolNativeIdentity(LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1,
            namespace?.identity ?: jvmSpoolFileIdentity(capturedDirectory), jvmSpoolFileIdentity(capturedFile),
            jvmSpoolOwnerIdentity(directory), cipher.identity)
        val owned = JvmPhysicalSpoolFile(directory, secure, openedDirectoryChannel, channel, lock,
            owner, native, initializing = create, layout = spec.layout, namespace = namespace, filename = spec.filename)
        nativeFile = owned
        if (expected != null) check(expected == native)
        result = PhysicalNativeOpenV1.Opened(PhysicalNativeOwnedFileV1(native, owned, cipher))
        // There is no coroutine/dispatch boundary inside this blocking ownership transfer.
        transferred = result is PhysicalNativeOpenV1.Opened
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
                cleaned = jvmSpoolCleanup { rawChannel?.close() } && cleaned
                cleaned = jvmSpoolCleanup { directoryChannel?.close() } && cleaned
                cleaned = jvmSpoolCleanup { stream?.close() } && cleaned
                if (cleaned) cleaned = processOwner?.release() ?: true
                cleaned
            }
            if (!complete) result = PhysicalNativeOpenV1.Held
            // Failed preflight bytes and unknown directory entries are intentionally retained.
        }
    }
    return result
}

private const val JVM_PHYSICAL_SPOOL_FILE: String = "allocation.native-v1.enc"
private class JvmPhysicalSpoolBusy : Exception()

/** A non-evicting in-process ownership guard, NEVER a physical-capacity or lifetime authority. */
private class JvmPhysicalSpoolProcessOwner private constructor(private val directoryIdentity: String) {
    fun requireCurrent() { check(owners[directoryIdentity] === this) }
    fun release(): Boolean = owners.remove(directoryIdentity, this)

    companion object {
        private val owners = ConcurrentHashMap<String, JvmPhysicalSpoolProcessOwner>()
        fun acquire(directoryIdentity: String): JvmPhysicalSpoolProcessOwner {
            val owner = JvmPhysicalSpoolProcessOwner(directoryIdentity)
            if (owners.putIfAbsent(directoryIdentity, owner) != null) throw JvmPhysicalSpoolBusy()
            return owner
        }
    }
}

private class JvmPhysicalSpoolFile(
    private val directory: Path,
    private val directoryStream: SecureDirectoryStream<Path>,
    private val directoryChannel: FileChannel,
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val processOwner: JvmPhysicalSpoolProcessOwner,
    private val native: LocalPhysicalSpoolNativeIdentity,
    private var initializing: Boolean,
    private val layout: PhysicalNativeLayoutV1,
    private val namespace: JvmPhysicalJournalNamespace?,
    private val filename: String,
) : PhysicalSpoolFile {
    private val mutex = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    private val processId = ProcessHandle.current().pid()
    private val relative = directory.fileSystem.getPath(filename)
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
        // Write EVERY byte (including randomized encrypted vacancy/padding), never seek-to-end,
        // sparse sizing, truncate, free-space estimation or a native-allocation assertion.
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
        channel.force(true)
        directoryChannel.force(true)
        current()
    }

    private fun current() {
        processOwner.requireCurrent()
        check(!closed.get() && !retired.get() && !Thread.currentThread().isInterrupted && ProcessHandle.current().pid() == processId)
        check(channel.isOpen && directoryChannel.isOpen && fileLock.isValid && !fileLock.isShared &&
            fileLock.position() == 0L && fileLock.size() == Long.MAX_VALUE)
        val namedDirectory = jvmSpoolDirectory(directory)
        val heldDirectory = jvmSpoolDirectoryAttributes(directoryStream)
        namespace?.requireCurrent()
        val expectedDirectory = namespace?.directoryIdentity ?: native.directoryIdentity
        check(jvmSpoolFileIdentity(namedDirectory) == expectedDirectory &&
            jvmSpoolFileIdentity(heldDirectory) == expectedDirectory && heldDirectory.owner() == namedDirectory.owner())
        check(jvmSpoolOwnerIdentity(directory) == native.ownerIdentity)
        jvmSpoolAncestors(directory, jvmSpoolUnixNumber(directory, "uid"))
        val namedFile = jvmSpoolNamedFile(directoryStream, relative)
        val path = directory.resolve(relative)
        val absoluteFile = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(jvmSpoolFileIdentity(namedFile) == native.fileIdentity && jvmSpoolFileIdentity(absoluteFile) == native.fileIdentity &&
            namedFile.owner() == heldDirectory.owner() && absoluteFile.owner() == namedFile.owner())
        check(jvmSpoolOwnerIdentity(path) == native.ownerIdentity && jvmSpoolUnixNumber(path, "nlink") == 1L &&
            (jvmSpoolUnixNumber(path, "mode") and 0xfffL) == 0x180L)
        val size = channel.size()
        check(namedFile.size() == size && absoluteFile.size() == size)
        if (initializing) check(size in 0L..layout.bytes.toLong())
        else check(size == layout.bytes.toLong())
        jvmSpoolEntries(directoryStream, layout, filename, create = false)
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
                // Retain ownership before registration and before access constructs Value.
                // Failure at either later step must release this exact memory registration.
                candidate = lease
                leaseDenials[lease] = onRetirement
                lease
            }
            if (result is PhysicalSpoolAccess.Value) {
                val lease = result.value
                check(lease === candidate)
                transferred = true
                return lease
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
        closed.set(true) // publication/admission stops before waiting for native IO, not after it
        denyLeases()
        mutex.lock()
        try {
            cleanupResult?.let { return it }
            if (leaseDenials.isNotEmpty()) return LocalPhysicalSpoolClose.HELD
            var complete = jvmSpoolCleanup { if (fileLock.isValid) fileLock.release() }
            complete = jvmSpoolCleanup { channel.close() } && complete
            complete = jvmSpoolCleanup { directoryChannel.close() } && complete
            complete = jvmSpoolCleanup { directoryStream.close() } && complete
            // Uncertain close retains the process claim permanently; no eviction/automatic repair.
            if (complete) complete = processOwner.release()
            return (if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD).also {
                cleanupResult = it
                if (!complete) retired.set(true)
            }
        } finally { mutex.unlock() }
    }
}

private class JvmPhysicalSpoolCipher(private val material: JvmPhysicalSpoolKey) : PhysicalSpoolCipher {
    override val identity: String

    init {
        currentKey()
        identity = listOf("jca-aes-gcm-opaque-key-size-unqualified/v1", material.provider.name,
            material.provider.versionStr, material.provider.javaClass.name, material.key.javaClass.name)
            .joinToString("") { "${it.length}:$it" }
        physicalSpoolTextBound(identity, 512)
    }

    private fun currentKey(): SecretKey {
        check(!Thread.currentThread().isInterrupted && Security.getProvider(material.provider.name) === material.provider)
        val key = material.key
        check(key.algorithm == "AES" && key.format == null && !key.isDestroyed)
        // Do NOT call encoded: even inspection must not export provider-owned key material.
        return key
    }

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", material.provider)
        cipher.init(Cipher.ENCRYPT_MODE, currentKey())
        val nonce = cipher.iv ?: error("physical.spool.crypto_profile")
        var encrypted: ByteArray? = null
        try {
            val parameters = cipher.parameters?.getParameterSpec(GCMParameterSpec::class.java)
                ?: error("physical.spool.crypto_profile")
            check(nonce.size == PHYSICAL_SPOOL_NONCE_BYTES && parameters.tLen == PHYSICAL_SPOOL_TAG_BYTES * 8 &&
                parameters.iv.contentEquals(nonce) && cipher.blockSize == 16 && cipher.provider === material.provider)
            cipher.updateAAD(aad)
            val value = cipher.doFinal(plaintext)
            encrypted = value
            check(value.size == plaintext.size + PHYSICAL_SPOOL_TAG_BYTES)
            currentKey()
            return physicalSpoolEnvelope(nonce, value)
        } finally {
            nonce.fill(0)
            encrypted?.fill(0)
        }
    }

    override fun open(envelope: ByteArray, aad: ByteArray): ByteArray = physicalSpoolNonce(envelope).withSpoolBytes { nonce ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", material.provider)
        cipher.init(Cipher.DECRYPT_MODE, currentKey(), GCMParameterSpec(PHYSICAL_SPOOL_TAG_BYTES * 8, nonce))
        check(cipher.blockSize == 16 && cipher.provider === material.provider)
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(envelope, 16, envelope.size - 16)
        var transferred = false
        try {
            check(plain.size + PHYSICAL_SPOOL_ENVELOPE_BYTES == envelope.size)
            currentKey()
            transferred = true
            plain
        } finally { if (!transferred) plain.fill(0) }
    }
}

private fun jvmSpoolDirectory(directory: Path): PosixFileAttributes {
    check(directory.toRealPath() == directory)
    val attributes = Files.readAttributes(directory, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    check(attributes.isDirectory && !attributes.isSymbolicLink && attributes.permissions() == PosixFilePermissions.fromString("rwx------"))
    check((jvmSpoolUnixNumber(directory, "mode") and 0xfffL) == 0x1c0L)
    return attributes
}

private fun jvmSpoolDirectoryAttributes(directory: SecureDirectoryStream<Path>): PosixFileAttributes {
    val attributes = directory.getFileAttributeView(PosixFileAttributeView::class.java)?.readAttributes()
        ?: error("physical.spool.directory_identity")
    check(attributes.isDirectory && !attributes.isSymbolicLink && attributes.permissions() == PosixFilePermissions.fromString("rwx------"))
    return attributes
}

private fun jvmSpoolNamedFile(directory: SecureDirectoryStream<Path>, relative: Path): PosixFileAttributes {
    val attributes = directory.getFileAttributeView(relative, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.readAttributes()
        ?: error("physical.spool.file_identity")
    check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.permissions() == PosixFilePermissions.fromString("rw-------"))
    return attributes
}

private fun jvmSpoolFileIdentity(attributes: PosixFileAttributes): String {
    val key = attributes.fileKey() ?: error("physical.spool.file_identity")
    return "${key.javaClass.name}:$key"
}

private fun jvmSpoolUnixNumber(path: Path, name: String): Long =
    (Files.getAttribute(path, "unix:$name", LinkOption.NOFOLLOW_LINKS) as? Number)?.toLong()
        ?.also { check(it >= 0L) } ?: error("physical.spool.posix_identity")

private fun jvmSpoolOwnerIdentity(path: Path): String =
    "uid=${jvmSpoolUnixNumber(path, "uid")};gid=${jvmSpoolUnixNumber(path, "gid")}" // actual OS metadata, not user.name

private fun jvmSpoolAncestors(directory: Path, ownerUid: Long) {
    var parent = directory.parent
    var count = 0
    while (parent != null) {
        check(++count <= 32)
        val attributes = Files.readAttributes(parent, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isDirectory && !attributes.isSymbolicLink)
        check(PosixFilePermission.GROUP_WRITE !in attributes.permissions() && PosixFilePermission.OTHERS_WRITE !in attributes.permissions())
        val uid = jvmSpoolUnixNumber(parent, "uid")
        check(uid == 0L || uid == ownerUid)
        parent = parent.parent
    }
}

private fun jvmSpoolEntries(directory: SecureDirectoryStream<Path>, spec: PhysicalNativeFileSpecV1, create: Boolean) {
    jvmSpoolEntries(directory, spec.layout, spec.filename, create)
}

/**
 * The leaf contains only canonical files for this closed layout. Generation files are append-only:
 * create may see older generations but never the target or a newer generation; open must see the
 * exact target. Unknown names, duplicates and non-canonical suffixes are held, never ignored.
 */
private fun jvmSpoolEntries(directory: SecureDirectoryStream<Path>, layout: PhysicalNativeLayoutV1,
    filename: String, create: Boolean) {
    val target = layout.generationOf(filename) ?: error("physical.spool.filename")
    // A DirectoryStream has only one iterator; open a new anchored stream for each bounded scan.
    directory.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS).use { entries ->
        var count = 0
        var targetSeen = false
        for (entry in entries) {
            val name = entry.fileName.toString()
            check(++count <= LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS)
            val generation = layout.generationOf(name) ?: error("physical.spool.entry")
            if (name == filename) targetSeen = true
            if (create) check(generation < target)
        }
        check(if (create) !targetSeen else targetSeen)
    }
}

/** Bounded no-follow listing of the already installed leaf; every selected file is reauthenticated. */
private fun jvmListPhysicalNative(directory: Path, expectedDirectoryIdentity: String): List<String>? = try {
    check(!Thread.currentThread().isInterrupted && directory.isAbsolute && directory.normalize() == directory &&
        directory.fileSystem === FileSystems.getDefault())
    val before = jvmSpoolDirectory(directory)
    jvmSpoolAncestors(directory, jvmSpoolUnixNumber(directory, "uid"))
    val namespace = JvmPhysicalJournalNamespace.capture(directory)
    check(namespace.identity == expectedDirectoryIdentity)
    val names = mutableListOf<String>()
    Files.newDirectoryStream(directory).use { opened ->
        val entries = opened as? SecureDirectoryStream<Path> ?: error("physical.spool.secure_directory")
        check(jvmSpoolFileIdentity(jvmSpoolDirectoryAttributes(entries)) == jvmSpoolFileIdentity(before))
        for (entry in entries) {
            check(names.size < LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS)
            names += entry.fileName.toString()
        }
        namespace.requireCurrent()
        check(jvmSpoolFileIdentity(jvmSpoolDirectory(directory)) == jvmSpoolFileIdentity(before))
    }
    names
} catch (_: Exception) { null }

private inline fun jvmSpoolCleanup(block: () -> Unit): Boolean = try { block(); true } catch (_: Exception) { false }

/** New profile captures exact ancestor identities; the legacy native receipt stays byte-compatible. */
private class JvmPhysicalJournalNamespace private constructor(private val records: List<Record>) {
    val directoryIdentity: String = records.first().identity
    val identity: String = "jvm-journal-namespace/v1:" + records.flatMap { record ->
        listOf(record.path.toString(), record.identity, record.owner, record.mode.toString())
    }.joinToString("") { "${it.length}:$it" }.encodeToByteArray(throwOnInvalidSequence = true).withSpoolBytes { sha256(it).value }

    fun requireCurrent() {
        for (record in records) {
            check(record.path.toRealPath() == record.path)
            val current = Files.readAttributes(record.path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(current.isDirectory && !current.isSymbolicLink && jvmSpoolFileIdentity(current) == record.identity &&
                jvmSpoolOwnerIdentity(record.path) == record.owner && jvmSpoolUnixNumber(record.path, "mode") == record.mode)
        }
    }

    private data class Record(val path: Path, val identity: String, val owner: String, val mode: Long)
    companion object {
        fun capture(directory: Path): JvmPhysicalJournalNamespace {
            val records = mutableListOf<Record>()
            var path: Path? = directory
            while (path != null) {
                check(records.size < 32 && path.toRealPath() == path)
                val attributes = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(attributes.isDirectory && !attributes.isSymbolicLink)
                records += Record(path, jvmSpoolFileIdentity(attributes), jvmSpoolOwnerIdentity(path), jvmSpoolUnixNumber(path, "mode"))
                path = path.parent
            }
            return JvmPhysicalJournalNamespace(records.toList()).also { it.requireCurrent() }
        }
    }
}

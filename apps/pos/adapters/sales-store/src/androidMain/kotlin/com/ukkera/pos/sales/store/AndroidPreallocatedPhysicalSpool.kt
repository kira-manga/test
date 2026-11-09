package com.ukkera.pos.sales.store

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.system.Os
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import android.system.OsConstants
import android.system.StructStat
import com.ukkera.pos.security.AndroidNativeKeyInfo
import com.ukkera.pos.security.AndroidNativeKeySecurity
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.KeyStore
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Exact expected native observation from an independently authenticated installer record, not
 * a caller's hardware/custody assertion. Public KeyInfo on API 24..30 cannot distinguish hardware
 * kinds here; the native file adapter supplies protected descriptor inspection on API24+.
 * Unexpected OS/profile changes require explicit requalification. Only authenticated original
 * receipts can retain their old provenance through the bounded Keystore-to-Keystore2 join below.
 */
public enum class AndroidPhysicalSpoolKeySecurity {
    SOFTWARE, HARDWARE_KIND_UNSPECIFIED, TRUSTED_ENVIRONMENT, STRONGBOX,
}

/**
 * Actual Android fixed-file/AES-256-GCM prerequisite only; no master appointment, paired-device
 * receipt or accepting v2 capacity is exported. The existing installer MUST have provisioned
 * 0700 private children physical-spool-v1/<installation>/<spool>/<allocation> under the
 * preexisting framework no_backup parent in credential-encrypted app data. That parent must
 * have app UID/GID and exact framework 0771 or already stricter 0700; it is never repaired here.
 * API24+ uses public KeyStore and native protected file operations. No directory/key generation,
 * raw alias selection, repair or allocation fallback is supplied.
 */
public object AndroidPreallocatedPhysicalSpool {
    public const val MIN_API_LEVEL: Int = AndroidNativeKeyPolicy.MINIMUM_ANDROID_API

    public fun createNew(context: Context, binding: LocalPhysicalSpoolBinding,
        expectedKeySecurity: AndroidPhysicalSpoolKeySecurity): LocalPhysicalSpoolOpen =
        acquire(context, binding, expectedKeySecurity, null)

    public fun openExisting(context: Context, expected: LocalPhysicalSpoolReceipt,
        expectedKeySecurity: AndroidPhysicalSpoolKeySecurity): LocalPhysicalSpoolOpen =
        acquire(context, expected.binding, expectedKeySecurity, expected)

    private fun acquire(context: Context, binding: LocalPhysicalSpoolBinding,
        expectedKeySecurity: AndroidPhysicalSpoolKeySecurity, expected: LocalPhysicalSpoolReceipt?): LocalPhysicalSpoolOpen {
        val spec = PhysicalNativeFileSpecV1(binding.installationId, binding.keyId,
            listOf("physical-spool-v1", binding.installationId.value, binding.spoolId.value, binding.allocationId.value),
            PhysicalNativeLayoutV1.LEGACY)
        return legacySpoolFromNative(androidAcquirePhysicalNative(context, spec, expectedKeySecurity,
            expected?.nativeIdentity, expected == null, legacyOriginal = expected), binding, expected)
    }
}

/**
 * Real API24+ native catalogue/journal manager. All private owner/anchor/catalogue/slot directories
 * and the existing KeyStore alias MUST be installed first. This never creates/replaces keys or
 * repairs parents. Distinct master/client namespaces may intentionally share this device.
 * Native retention alone does not appoint a master or grant cashier/financial authority.
 */
public object AndroidPreallocatedPhysicalJournal {
    public fun createCatalogue(context: Context, owner: LocalPhysicalCatalogueBinding,
        expectedKeySecurity: AndroidPhysicalSpoolKeySecurity,
        composition: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1.LIVE): LocalPhysicalCatalogueOpen =
        createPhysicalCatalogue(owner, files(context, expectedKeySecurity, owner, composition))

    public fun openCatalogue(context: Context, owner: LocalPhysicalCatalogueBinding,
        expectedKeySecurity: AndroidPhysicalSpoolKeySecurity,
        composition: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1.LIVE): LocalPhysicalCatalogueOpen =
        openPhysicalCatalogue(owner, files(context, expectedKeySecurity, owner, composition))

    private fun files(context: Context, expected: AndroidPhysicalSpoolKeySecurity,
        owner: LocalPhysicalCatalogueBinding,
        composition: AndroidPhysicalCompositionNamespaceV1): PhysicalJournalNativeFilesV1 {
        val original = AndroidPhysicalCatalogueProvenance(owner, composition)
        return object : PhysicalJournalNativeFilesV1 {
            override fun open(spec: PhysicalNativeFileSpecV1, native: LocalPhysicalSpoolNativeIdentity?,
                create: Boolean): PhysicalNativeOpenV1 =
                androidAcquirePhysicalNative(context, spec.withJournalDirectory(composition.journalDirectoryName),
                    expected, native, create, catalogueOriginal = original, composition = composition)

            override fun list(spec: PhysicalNativeFileSpecV1, expectedDirectoryIdentity: String): List<String>? =
                androidListPhysicalNative(context, spec.withJournalDirectory(composition.journalDirectoryName),
                    original, composition, expectedDirectoryIdentity)
        }
    }
}

/** Common catalogue code keeps the stable logical `physical-journal-v1` prefix; this adapter
 * only redirects the native container to the already-bound live/training composition root. */
private fun PhysicalNativeFileSpecV1.withJournalDirectory(directoryName: String): PhysicalNativeFileSpecV1 {
    check(layout != PhysicalNativeLayoutV1.LEGACY && segments.firstOrNull() == "physical-journal-v1")
    return PhysicalNativeFileSpecV1(installationId, keyId, listOf(directoryName) + segments.drop(1), layout, generation)
}

private fun androidAcquirePhysicalNative(context: Context, spec: PhysicalNativeFileSpecV1,
    expectedKeySecurity: AndroidPhysicalSpoolKeySecurity,
    expected: LocalPhysicalSpoolNativeIdentity?, create: Boolean,
    catalogueOriginal: AndroidPhysicalCatalogueProvenance? = null,
    legacyOriginal: LocalPhysicalSpoolReceipt? = null,
    composition: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1.LIVE): PhysicalNativeOpenV1 {
    if (Build.VERSION.SDK_INT < AndroidPreallocatedPhysicalSpool.MIN_API_LEVEL) return PhysicalNativeOpenV1.Held
    var directoryDescriptor: FileDescriptor? = null
    var fileDescriptor: FileDescriptor? = null
    var stream: FileOutputStream? = null
    var fileLock: FileLock? = null
    var processOwner: AndroidPhysicalSpoolProcessOwner? = null
    var nativeFile: AndroidPhysicalSpoolFile? = null
    var transferred = false
    var closureUncertain = false
    var result: PhysicalNativeOpenV1 = PhysicalNativeOpenV1.Held
    try {
        check(!Thread.currentThread().isInterrupted)
        catalogueOriginal?.requireSpec(spec)
        if (expected != null) check(expected.profile == LocalPhysicalSpoolStorageProfileV1.ANDROID_API30_APP_PRIVATE_FALLOCATE_V1)
        val application = context.applicationContext as? Application ?: error("physical.spool.application")
        val processUid = Process.myUid()
        check(application.applicationContext === application && !application.isDeviceProtectedStorage &&
            application.applicationInfo.uid == processUid)
        val userManager = application.getSystemService(UserManager::class.java) ?: error("physical.spool.user_state")
        check(userManager.isUserUnlocked)
        val namespace = androidSpoolNamespace(application, spec.segments, processUid,
            if (spec.layout == PhysicalNativeLayoutV1.LEGACY) ANDROID_PHYSICAL_SPOOL_NAMESPACE else "android-journal-namespace/v1")
        if (expected != null) check(expected.directoryIdentity == namespace.identity)
        val directory = namespace.allocationDirectory.file
        androidSpoolEntries(directory, spec, create)
        val cipher = AndroidPhysicalSpoolCipher(spec.installationId, spec.keyId, expectedKeySecurity,
            composition.keyAliasPrefix)
        val directoryFd = AndroidNativeFiles.open(directory.path,
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        directoryDescriptor = directoryFd
        val capturedDirectory = Os.fstat(directoryFd)
        namespace.allocationDirectory.requireSame(capturedDirectory, processUid)
        namespace.requireCurrent(application, processUid)
        // Prevent a second local open/close from dropping this process's existing POSIX lock.
        // Generation files intentionally share one installed leaf, so the claim is per directory
        // AND canonical filename rather than per directory alone.
        val processClaim = AndroidPhysicalSpoolProcessOwner.acquire(androidSpoolIdentity(capturedDirectory) + ":" + spec.filename)
        processOwner = processClaim
        val file = File(directory, spec.filename)
        val beforeFile = if (create) null else Os.lstat(file.path)
        var flags = OsConstants.O_RDWR or OsConstants.O_NOFOLLOW
        if (create) flags = flags or OsConstants.O_CREAT or OsConstants.O_EXCL
        val openedFd = AndroidNativeFiles.open(file.path, flags, 0x180) // 0600; no create bit on openExisting
        fileDescriptor = openedFd
        // Android's public FileOutputStream(fd) BORROWS the descriptor (unlike a path-opened
        // stream). It neither opens/truncates a path nor owns native close. This factory/native
        // file remains the sole native close owner; close the channel borrower before the raw fd.
        val owner = FileOutputStream(openedFd)
        stream = owner
        val lock = try { owner.channel.tryLock(0L, Long.MAX_VALUE, false) }
        catch (_: OverlappingFileLockException) { throw AndroidPhysicalSpoolBusy() }
            ?: throw AndroidPhysicalSpoolBusy()
        fileLock = lock
        val capturedFile = Os.fstat(openedFd)
        androidSpoolPrivateFile(capturedFile, processUid)
        check(androidSpoolIdentity(capturedFile) == androidSpoolIdentity(Os.lstat(file.path)))
        if (beforeFile != null) check(androidSpoolIdentity(beforeFile) == androidSpoolIdentity(capturedFile))
        check(androidSpoolOwner(capturedFile) == androidSpoolOwner(capturedDirectory))
        val observed = LocalPhysicalSpoolNativeIdentity(LocalPhysicalSpoolStorageProfileV1.ANDROID_API30_APP_PRIVATE_FALLOCATE_V1,
            namespace.identity, androidSpoolIdentity(capturedFile), androidSpoolOwner(capturedDirectory), cipher.identity)
        val owned = AndroidPhysicalSpoolFile(application, userManager, namespace, directoryFd, openedFd,
            owner, lock, processClaim, observed, initializing = create, layout = spec.layout, filename = spec.filename)
        nativeFile = owned
        // The actual namespace/file owner remains captured before any proof read. Only the
        // public original cryptography identity may retain its authenticated OS provenance.
        val native = androidOriginalPhysicalIdentity(spec, observed, expected, create, owned, cipher,
            catalogueOriginal, legacyOriginal)
        result = PhysicalNativeOpenV1.Opened(PhysicalNativeOwnedFileV1(native, owned, cipher))
        transferred = result is PhysicalNativeOpenV1.Opened
    } catch (_: AndroidPhysicalSpoolBusy) {
        result = PhysicalNativeOpenV1.Busy
    } catch (failure: Exception) {
        closureUncertain = failure is AndroidNativeFiles.ClosureUncertainException ||
            failure.suppressed.any { it is AndroidNativeFiles.ClosureUncertainException }
        result = PhysicalNativeOpenV1.Held
    } finally {
        if (!transferred) {
            val owned = nativeFile
            val complete = if (owned != null) owned.close() == LocalPhysicalSpoolClose.CLOSED
            else {
                var cleaned = !closureUncertain
                cleaned = androidSpoolCleanup { fileLock?.let { if (it.isValid) it.release() } } && cleaned
                val owner = stream
                // The Android descriptor constructor is explicitly non-owning. Close the
                // borrower, then perform exactly one native close; never retry an uncertain fd.
                if (owner != null) cleaned = androidSpoolCleanup { owner.close() } && cleaned
                cleaned = androidSpoolCleanup { fileDescriptor?.let(AndroidNativeFiles::close) } && cleaned
                cleaned = androidSpoolCleanup { directoryDescriptor?.let(AndroidNativeFiles::close) } && cleaned
                if (cleaned) cleaned = processOwner?.release() ?: true
                cleaned
            }
            if (!complete) result = PhysicalNativeOpenV1.Held
            // Interrupted preflight and foreign/torn bytes are retained, never unlinked.
        }
    }
    return result
}

/** An anchor authenticated under this exact installed owner, never a caller's upgrade flag. */
private class AndroidPhysicalAnchorProof(val native: LocalPhysicalSpoolNativeIdentity,
    val catalogue: LocalPhysicalSpoolNativeIdentity)

private class AndroidPhysicalCatalogueProvenance(
    val owner: LocalPhysicalCatalogueBinding,
    private val composition: AndroidPhysicalCompositionNamespaceV1,
) {
    private val anchors = mutableMapOf<Int, AndroidPhysicalAnchorProof>()

    fun requireSpec(spec: PhysicalNativeFileSpecV1) {
        check(spec.installationId == owner.installationId && spec.keyId == owner.keyId)
        val slot = if (spec.layout == PhysicalNativeLayoutV1.JOURNAL) {
            val leaf = spec.segments.last()
            check(leaf.startsWith("slot-") && leaf.length == 8)
            leaf.substring(5).toInt()
        } else null
        val expected = physicalJournalNativeSpec(owner, spec.layout, slot, spec.generation).segments
        check(spec.segments.firstOrNull() == composition.journalDirectoryName &&
            spec.segments.drop(1) == expected.drop(1))
    }

    fun retain(generation: Int, proof: AndroidPhysicalAnchorProof) {
        check(anchors.put(generation, proof) == null)
    }

    fun requireDerived(spec: PhysicalNativeFileSpecV1, native: LocalPhysicalSpoolNativeIdentity) {
        requireSpec(spec)
        val original = checkNotNull(anchors[spec.generation])
        check(native.profile == original.native.profile &&
            native.cryptographyIdentity == original.native.cryptographyIdentity)
        if (spec.layout == PhysicalNativeLayoutV1.CATALOGUE) check(native == original.catalogue)
        else check(spec.layout == PhysicalNativeLayoutV1.JOURNAL)
        // Journal expectations come only from this owner's AEAD catalogue. The unchanged
        // common reopen still checks the exact receipt/header/body before publishing a journal.
    }
}

private fun androidOriginalPhysicalIdentity(spec: PhysicalNativeFileSpecV1,
    observed: LocalPhysicalSpoolNativeIdentity, expected: LocalPhysicalSpoolNativeIdentity?, create: Boolean,
    file: AndroidPhysicalSpoolFile, cipher: AndroidPhysicalSpoolCipher,
    catalogueOriginal: AndroidPhysicalCatalogueProvenance?, legacyOriginal: LocalPhysicalSpoolReceipt?): LocalPhysicalSpoolNativeIdentity {
    if (create || expected == observed) {
        check(expected == null || expected == observed)
        return observed
    }
    val older = cipher.originalKeystoreIdentity()
    if (older != null && spec.layout == PhysicalNativeLayoutV1.ANCHOR && expected == null) {
        val original = checkNotNull(catalogueOriginal)
        val proof = file.access {
            file.read(0, LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { encrypted ->
                // A bounded read-only identity qualification, not a key-generation retry.
                // Only an authentication-tag mismatch permits the one known older provenance.
                try { androidProvePhysicalAnchor(original.owner, observed, cipher, encrypted) }
                catch (_: AEADBadTagException) {
                    androidProvePhysicalAnchor(original.owner, observed.copy(cryptographyIdentity = older), cipher, encrypted)
                }
            }
        }
        val authenticated = (proof as? PhysicalSpoolAccess.Value)?.value ?: error("physical.spool.original_anchor")
        if (authenticated.native != observed) cipher.retainOriginalIdentity(authenticated.native.cryptographyIdentity)
        original.retain(spec.generation, authenticated)
        return authenticated.native
    }
    if (older != null && expected != null && expected.cryptographyIdentity == older) {
        // Device/inode, namespace, UID/GID and storage profile remain EXACT. An OS provenance
        // transition does not adopt a moved/replaced file, another owner, alias or security kind.
        check(expected.copy(cryptographyIdentity = observed.cryptographyIdentity) == observed)
        if (spec.layout == PhysicalNativeLayoutV1.LEGACY) {
            val receipt = checkNotNull(legacyOriginal)
            check(receipt.nativeIdentity == expected && receipt.binding.installationId == spec.installationId &&
                receipt.binding.keyId == spec.keyId)
            check(file.access {
                file.read(PhysicalSpoolCellV1.HEADER.offset, PhysicalSpoolCellV1.HEADER.length).withSpoolBytes { encrypted ->
                    check(sha256(encrypted) == receipt.headerDigest)
                    physicalSpoolAad(receipt.plan, PhysicalSpoolCellV1.HEADER, null).withSpoolBytes { aad ->
                        cipher.open(encrypted, aad).withSpoolBytes { plain ->
                            checkNotNull(physicalSpoolDecodePlaintext(PhysicalSpoolCellV1.HEADER,
                                receipt.plan.requirementDigest, plain)).withSpoolBytes { body ->
                                check(body.contentEquals(receipt.plan.bytes))
                            }
                        }
                    }
                }
            } is PhysicalSpoolAccess.Value)
        } else checkNotNull(catalogueOriginal).requireDerived(spec, expected)
        cipher.retainOriginalIdentity(expected.cryptographyIdentity)
        return expected
    }
    check(expected == null || expected == observed)
    return observed
}

/** Reuse the original common AEAD/canonical root, including the full owner and native lineage. */
private fun androidProvePhysicalAnchor(owner: LocalPhysicalCatalogueBinding, native: LocalPhysicalSpoolNativeIdentity,
    cipher: AndroidPhysicalSpoolCipher, encrypted: ByteArray): AndroidPhysicalAnchorProof =
    checkNotNull(journalOpen(cipher, "catalogue-anchor", catalogueAnchorBinding(owner, native), 0,
        LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, null, encrypted)).withSpoolBytes { body ->
        val root = readCatalogueRoot(owner, native, body)
        try {
            check(root.catalogue.profile == native.profile &&
                root.catalogue.cryptographyIdentity == native.cryptographyIdentity)
            AndroidPhysicalAnchorProof(native, root.catalogue)
        } finally { root.bytes.fill(0); root.header.fill(0) }
    }

private const val ANDROID_PHYSICAL_SPOOL_FILE: String = "allocation.native-v1.enc"
private const val ANDROID_PHYSICAL_SPOOL_CIPHER_PROVIDER: String = "AndroidKeyStoreBCWorkaround"
private const val ANDROID_PHYSICAL_SPOOL_NAMESPACE: String = "android-spool-namespace/v2"
private class AndroidPhysicalSpoolBusy : Exception()

/** Stops cooperating duplicate local opens; not durable capacity, supervision or rogue-UID fencing. */
private class AndroidPhysicalSpoolProcessOwner private constructor(private val directoryIdentity: String) {
    fun requireCurrent() { check(owners[directoryIdentity] === this) }
    fun release(): Boolean = owners.remove(directoryIdentity, this)

    companion object {
        private val owners = ConcurrentHashMap<String, AndroidPhysicalSpoolProcessOwner>()
        fun acquire(directoryIdentity: String): AndroidPhysicalSpoolProcessOwner {
            val owner = AndroidPhysicalSpoolProcessOwner(directoryIdentity)
            if (owners.putIfAbsent(directoryIdentity, owner) != null) throw AndroidPhysicalSpoolBusy()
            return owner
        }
    }
}

private class AndroidPhysicalSpoolFile(
    private val application: Application,
    private val userManager: UserManager,
    private val namespace: AndroidPhysicalSpoolNamespace,
    private val directoryDescriptor: FileDescriptor,
    private val descriptor: FileDescriptor,
    private val stream: FileOutputStream,
    private val fileLock: FileLock,
    private val processOwner: AndroidPhysicalSpoolProcessOwner,
    private val native: LocalPhysicalSpoolNativeIdentity,
    private var initializing: Boolean,
    private val layout: PhysicalNativeLayoutV1,
    private val filename: String,
) : PhysicalSpoolFile {
    private val mutex = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    private val processId = Process.myPid()
    private val processUid = Process.myUid()
    private val directory = namespace.allocationDirectory.file
    private val file = File(directory, filename)
    private var cleanupResult: LocalPhysicalSpoolClose? = null
    private var closureUncertain = false
    private val leaseDenials = ConcurrentHashMap<PhysicalSpoolLease, () -> Unit>()

    override fun <T> access(block: () -> T): PhysicalSpoolAccess<T> {
        if (closed.get() || retired.get()) return PhysicalSpoolAccess.Held
        if (!mutex.tryLock()) return PhysicalSpoolAccess.Busy
        return try {
            current()
            val value = block()
            current()
            PhysicalSpoolAccess.Value(value)
        } catch (failure: Exception) {
            if (failure is AndroidNativeFiles.ClosureUncertainException ||
                failure.suppressed.any { it is AndroidNativeFiles.ClosureUncertainException }) closureUncertain = true
            retired.set(true)
            denyLeases()
            PhysicalSpoolAccess.Held
        } finally { mutex.unlock() }
    }

    override fun initialize(bytes: ByteArray) {
        current()
        check(initializing && Os.fstat(descriptor).st_size == 0L && bytes.size == layout.bytes)
        // Actual POSIX allocation, not sparse sizing or free-space sampling. Any unsupported or
        // failed syscall retires the handle. There is deliberately no weaker fallback path.
        Os.posix_fallocate(descriptor, 0L, layout.bytes.toLong())
        val allocated = Os.fstat(descriptor)
        check(allocated.st_size == layout.bytes.toLong() &&
            allocated.st_blocks >= layout.bytes / 512L)
        var offset = 0
        while (offset < bytes.size) {
            current()
            val count = Os.pwrite(descriptor, bytes, offset, bytes.size - offset, offset.toLong())
            check(count in 1..(bytes.size - offset))
            offset += count
        }
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
            var count = 0
            while (count < size) {
                current()
                val read = Os.pread(descriptor, bytes, count, size - count, offset.toLong() + count)
                check(read in 1..(size - count))
                count += read
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
        var count = 0
        while (count < bytes.size) {
            current()
            val written = Os.pwrite(descriptor, bytes, count, bytes.size - count, offset.toLong() + count)
            check(written in 1..(bytes.size - count))
            count += written
        }
        current()
    }

    override fun force() {
        current()
        check(!initializing)
        Os.fsync(descriptor)
        Os.fsync(directoryDescriptor)
        current()
    }

    private fun current() {
        processOwner.requireCurrent()
        check(!closed.get() && !retired.get() && !Thread.currentThread().isInterrupted &&
            Process.myPid() == processId && Process.myUid() == processUid)
        check(application.applicationContext === application && !application.isDeviceProtectedStorage &&
            application.applicationInfo.uid == processUid && userManager.isUserUnlocked)
        namespace.requireCurrent(application, processUid)
        check(native.directoryIdentity == namespace.identity)
        check(descriptor.valid() && directoryDescriptor.valid() && stream.fd === descriptor && stream.channel.isOpen && fileLock.isValid &&
            !fileLock.isShared && fileLock.position() == 0L && fileLock.size() == Long.MAX_VALUE)
        val flags = AndroidNativeFiles.statusFlags(descriptor)
        check((flags and OsConstants.O_ACCMODE) == OsConstants.O_RDWR && (flags and OsConstants.O_APPEND) == 0)
        check(AndroidNativeFiles.closeOnExec(descriptor))
        val directoryFlags = AndroidNativeFiles.statusFlags(directoryDescriptor)
        check((directoryFlags and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY && (directoryFlags and OsConstants.O_APPEND) == 0)
        check(AndroidNativeFiles.closeOnExec(directoryDescriptor))
        val heldDirectory = Os.fstat(directoryDescriptor)
        val namedDirectory = Os.lstat(directory.path)
        namespace.allocationDirectory.requireSame(heldDirectory, processUid)
        namespace.allocationDirectory.requireSame(namedDirectory, processUid)
        check(androidSpoolOwner(heldDirectory) == native.ownerIdentity && androidSpoolOwner(namedDirectory) == native.ownerIdentity)
        val heldFile = Os.fstat(descriptor)
        val namedFile = Os.lstat(file.path)
        androidSpoolPrivateFile(heldFile, processUid)
        androidSpoolPrivateFile(namedFile, processUid)
        check(androidSpoolIdentity(heldFile) == native.fileIdentity && androidSpoolIdentity(namedFile) == native.fileIdentity &&
            androidSpoolOwner(heldFile) == native.ownerIdentity && androidSpoolOwner(namedFile) == native.ownerIdentity &&
            heldFile.st_size == namedFile.st_size && heldFile.st_blocks == namedFile.st_blocks)
        if (initializing) check(heldFile.st_size in 0L..layout.bytes.toLong())
        else check(heldFile.st_size == layout.bytes.toLong() &&
            heldFile.st_blocks >= layout.bytes / 512L)
        androidSpoolEntries(directory, layout, filename, create = false)
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
        closed.set(true)
        denyLeases()
        mutex.lock() // ownership cleanup must wait for native calls; dispatch cancellation is not fencing
        try {
            cleanupResult?.let { return it }
            if (leaseDenials.isNotEmpty()) return LocalPhysicalSpoolClose.HELD
            var complete = !closureUncertain
            complete = androidSpoolCleanup { if (fileLock.isValid) fileLock.release() } && complete
            complete = androidSpoolCleanup { stream.close() } && complete
            complete = androidSpoolCleanup { AndroidNativeFiles.close(descriptor) } && complete
            complete = androidSpoolCleanup { AndroidNativeFiles.close(directoryDescriptor) } && complete
            if (complete) complete = processOwner.release()
            return (if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD).also {
                cleanupResult = it
                if (!complete) retired.set(true)
            }
        } finally { mutex.unlock() }
    }
}

/** Standalone native operation only. Does not extend the core protected-table/AAD authority set. */
@Suppress("DEPRECATION")
private class AndroidPhysicalSpoolCipher(
    installationId: DomainId,
    keyId: DomainId,
    private val expectedSecurity: AndroidPhysicalSpoolKeySecurity,
    aliasPrefix: String,
) : PhysicalSpoolCipher {
    private val alias = "$aliasPrefix/${installationId.value}/storage/${keyId.value}"
    private val provider = Security.getProvider(ANDROID_PHYSICAL_SPOOL_CIPHER_PROVIDER)
        ?: error("physical.spool.crypto_provider")
    private val keyClass = loadExistingKey().javaClass.name
    private val observedIdentity: String = listOf("android-keystore-aes256-gcm-service-no-user-auth/v1", alias,
        expectedSecurity.name, provider.name, provider.version.toString(), provider.javaClass.name, keyClass)
        .joinToString("") { "${it.length}:$it" }.also { physicalSpoolTextBound(it, 512) }
    override var identity: String = observedIdentity
        private set

    /** Only the official Keystore1 -> Keystore2 OS provenance change; never arbitrary providers. */
    fun originalKeystoreIdentity(): String? = if (Build.VERSION.SDK_INT >= 31 &&
        provider.name == ANDROID_PHYSICAL_SPOOL_CIPHER_PROVIDER && provider.version.toString() == "1.0" &&
        provider.javaClass.name == "android.security.keystore2.AndroidKeyStoreBCWorkaroundProvider" &&
        keyClass == "android.security.keystore2.AndroidKeyStoreSecretKey") {
        listOf("android-keystore-aes256-gcm-service-no-user-auth/v1", alias, expectedSecurity.name,
            ANDROID_PHYSICAL_SPOOL_CIPHER_PROVIDER, "1.0",
            "android.security.keystore.AndroidKeyStoreBCWorkaroundProvider", "android.security.keystore.AndroidKeyStoreSecretKey")
            .joinToString("") { "${it.length}:$it" }.also { physicalSpoolTextBound(it, 512) }
    } else null

    fun retainOriginalIdentity(original: String) {
        check(identity == observedIdentity && original == originalKeystoreIdentity())
        identity = original // Preserve the authenticated original bytes; actual provider/key checks stay below.
    }

    private fun currentKey(): SecretKey {
        check(Security.getProvider(ANDROID_PHYSICAL_SPOOL_CIPHER_PROVIDER) === provider)
        return loadExistingKey().also { check(it.javaClass.name == keyClass) }
    }

    /** Exact existing installed alias and actual KeyInfo on EACH operation; never generate/export. */
    private fun loadExistingKey(): SecretKey {
        check(Build.VERSION.SDK_INT >= AndroidPreallocatedPhysicalSpool.MIN_API_LEVEL && !Thread.currentThread().isInterrupted &&
            (expectedSecurity != AndroidPhysicalSpoolKeySecurity.STRONGBOX ||
                Build.VERSION.SDK_INT >= AndroidNativeKeyPolicy.MINIMUM_EXACT_STRONGBOX_API))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }
        check(store.containsAlias(alias) && store.isKeyEntry(alias))
        val key = store.getKey(alias, null) as? SecretKey ?: error("physical.spool.key_missing")
        check(key.algorithm == KeyProperties.KEY_ALGORITHM_AES && key.format == null)
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.keystoreAlias == alias && info.keySize == 256 && info.origin == KeyProperties.ORIGIN_GENERATED &&
            info.purposes == (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) &&
            info.blockModes.toSet() == setOf(KeyProperties.BLOCK_MODE_GCM) &&
            info.encryptionPaddings.toSet() == setOf(KeyProperties.ENCRYPTION_PADDING_NONE) &&
            info.digests.isEmpty() && info.signaturePaddings.isEmpty() && !info.isUserAuthenticationRequired &&
            !info.isUserAuthenticationValidWhileOnBody && info.keyValidityStart == null &&
            info.keyValidityForOriginationEnd == null && info.keyValidityForConsumptionEnd == null)
        check(!AndroidNativeKeyInfo.hasServiceRestrictions(info))
        val observed = AndroidNativeKeyInfo.observe(info)
        val retained = when (expectedSecurity) {
            AndroidPhysicalSpoolKeySecurity.SOFTWARE -> AndroidNativeKeySecurity.SOFTWARE
            AndroidPhysicalSpoolKeySecurity.TRUSTED_ENVIRONMENT -> AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT
            AndroidPhysicalSpoolKeySecurity.STRONGBOX -> AndroidNativeKeySecurity.STRONGBOX
            AndroidPhysicalSpoolKeySecurity.HARDWARE_KIND_UNSPECIFIED -> AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
        }
        // Retain a generic original hardware witness across an OS upgrade, never claim
        // historical TEE/StrongBox or accept software in place of original hardware.
        check(AndroidNativeKeyPolicy.originalWitness(observed, retained) == retained)
        return key
    }

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", provider)
        // Supplying an encryption IV would weaken the existing randomized-encryption policy.
        cipher.init(Cipher.ENCRYPT_MODE, currentKey())
        val nonce = cipher.iv ?: error("physical.spool.crypto_profile")
        var encrypted: ByteArray? = null
        try {
            val parameters = cipher.parameters?.getParameterSpec(GCMParameterSpec::class.java)
                ?: error("physical.spool.crypto_profile")
            check(nonce.size == PHYSICAL_SPOOL_NONCE_BYTES && parameters.tLen == PHYSICAL_SPOOL_TAG_BYTES * 8 &&
                parameters.iv.contentEquals(nonce) && cipher.blockSize == 16 && cipher.provider === provider)
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", provider)
        cipher.init(Cipher.DECRYPT_MODE, currentKey(), GCMParameterSpec(PHYSICAL_SPOOL_TAG_BYTES * 8, nonce))
        check(cipher.blockSize == 16 && cipher.provider === provider)
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

/** Distinct policies; framework parent permissions are not private allocation permissions. */
private enum class AndroidPhysicalSpoolDirectoryRole {
    APP_DATA, FRAMEWORK_NO_BACKUP, PRIVATE_CHILD;

    fun require(stat: StructStat, uid: Int) {
        when (this) {
            APP_DATA -> androidSpoolDataDirectory(stat, uid)
            FRAMEWORK_NO_BACKUP -> androidSpoolFrameworkDirectory(stat, uid)
            PRIVATE_CHILD -> androidSpoolPrivateDirectory(stat, uid)
        }
    }
}

/** Capture only stable namespace facts, not directory contents, mtime, ctime or link-count churn. */
private class AndroidPhysicalSpoolDirectory private constructor(
    val file: File,
    private val role: AndroidPhysicalSpoolDirectoryRole,
    private val identity: String,
    private val owner: String,
    private val mode: Int,
) {
    fun requireSame(stat: StructStat, uid: Int) {
        role.require(stat, uid)
        check(androidSpoolIdentity(stat) == identity && androidSpoolOwner(stat) == owner && (stat.st_mode and 0xfff) == mode)
    }

    fun requireCurrent(uid: Int) {
        check(file.isAbsolute && file.absoluteFile == file.canonicalFile)
        requireSame(Os.lstat(file.path), uid)
    }

    fun identityFields(): List<String> = listOf(role.name, file.path, identity, owner, mode.toString())

    companion object {
        fun capture(file: File, role: AndroidPhysicalSpoolDirectoryRole, uid: Int): AndroidPhysicalSpoolDirectory {
            check(file.isAbsolute && file.absoluteFile == file.canonicalFile)
            val stat = Os.lstat(file.path)
            role.require(stat, uid)
            return AndroidPhysicalSpoolDirectory(file, role, androidSpoolIdentity(stat), androidSpoolOwner(stat), stat.st_mode and 0xfff)
        }
    }
}

/** The receipt binds this captured chain; reopen cannot silently adopt another accepted parent. */
private class AndroidPhysicalSpoolNamespace(
    private val dataDirectory: AndroidPhysicalSpoolDirectory,
    frameworkParent: AndroidPhysicalSpoolDirectory,
    privateDirectories: List<AndroidPhysicalSpoolDirectory>,
    private val identityTag: String,
) {
    private val observations = listOf(dataDirectory, frameworkParent) + privateDirectories
    val allocationDirectory: AndroidPhysicalSpoolDirectory = privateDirectories.last()
    // Fixed-order, length-framed strings include role, canonical path, device/inode, UID/GID and
    // exact permission mode. Only the tagged SHA-256 goes in the existing <=256-byte field.
    val identity: String = identityTag + ":" +
        (listOf(identityTag) + observations.flatMap { it.identityFields() })
            .joinToString("") { "${it.length}:$it" }.encodeToByteArray(throwOnInvalidSequence = true)
            .withSpoolBytes { sha256(it).value }

    fun requireCurrent(application: Application, uid: Int) {
        check(application.dataDir.canonicalFile == dataDirectory.file)
        observations.forEach { it.requireCurrent(uid) }
    }
}

private fun androidSpoolNamespace(application: Application, segments: List<String>,
    uid: Int, identityTag: String): AndroidPhysicalSpoolNamespace {
    // The supported Android profile uses the installed credential-encrypted dataDir/no_backup
    // layout. Do NOT call noBackupFilesDir here/on reopen: its lazy getter may mkdir a missing
    // namespace. The framework parent and installer-owned children must already exist durably.
    // Resolve the OS-supplied data root once: Android may expose /data/user/0 through an
    // OS-owned alias. No symlink below that private canonical app root is accepted.
    val dataDirectory = AndroidPhysicalSpoolDirectory.capture(application.dataDir.canonicalFile,
        AndroidPhysicalSpoolDirectoryRole.APP_DATA, uid)
    val frameworkParent = AndroidPhysicalSpoolDirectory.capture(File(dataDirectory.file, "no_backup"),
        AndroidPhysicalSpoolDirectoryRole.FRAMEWORK_NO_BACKUP, uid)
    val children = mutableListOf<AndroidPhysicalSpoolDirectory>()
    var parent = frameworkParent.file
    for (segment in segments) {
        val child = AndroidPhysicalSpoolDirectory.capture(File(parent, segment), AndroidPhysicalSpoolDirectoryRole.PRIVATE_CHILD, uid)
        children += child
        parent = child.file
    }
    return AndroidPhysicalSpoolNamespace(dataDirectory, frameworkParent, children.toList(), identityTag).also { it.requireCurrent(application, uid) }
}

private fun androidSpoolFrameworkDirectory(stat: StructStat, uid: Int) {
    // ContextImpl's shared framework no_backup is normally 0771. Accept an already stricter
    // 0700 too, but never repair it. Group-write is allowed only for the actual app UID's GID.
    val mode = stat.st_mode and 0xfff
    check(OsConstants.S_ISDIR(stat.st_mode) && (mode == 0x1f9 || mode == 0x1c0) && stat.st_uid == uid && stat.st_gid == uid)
}

private fun androidSpoolPrivateDirectory(stat: StructStat, uid: Int) {
    check(OsConstants.S_ISDIR(stat.st_mode) && (stat.st_mode and 0xfff) == 0x1c0 && stat.st_uid == uid)
}

private fun androidSpoolDataDirectory(stat: StructStat, uid: Int) {
    // OS data roots may allow traversal. Only the installer-owned descendants require 0700.
    check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid && (stat.st_mode and 0x12) == 0)
}

private fun androidSpoolPrivateFile(stat: StructStat, uid: Int) {
    check(OsConstants.S_ISREG(stat.st_mode) && (stat.st_mode and 0xfff) == 0x180 && stat.st_uid == uid && stat.st_nlink == 1L)
}

private fun androidSpoolIdentity(stat: StructStat): String = "dev=${stat.st_dev};ino=${stat.st_ino}"
private fun androidSpoolOwner(stat: StructStat): String = "uid=${stat.st_uid};gid=${stat.st_gid}"

private fun androidSpoolEntries(directory: File, spec: PhysicalNativeFileSpecV1, create: Boolean) {
    androidSpoolEntries(directory, spec.layout, spec.filename, create)
}

/**
 * The leaf contains only canonical files for this closed layout. Generation files are append-only:
 * create may see older generations but never the target or a newer generation; open must see the
 * exact target. Unknown names, duplicates and non-canonical suffixes are held, never ignored.
 */
private fun androidSpoolEntries(directory: File, layout: PhysicalNativeLayoutV1, filename: String, create: Boolean) {
    val target = layout.generationOf(filename) ?: error("physical.spool.filename")
    AndroidNativeFiles.openDirectory(directory.path).use { cursor ->
        var count = 0
        var targetSeen = false
        while (true) {
            val name = cursor.nextName() ?: break
            check(++count <= LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS)
            val generation = layout.generationOf(name) ?: error("physical.spool.entry")
            if (name == filename) targetSeen = true
            if (create) check(generation < target)
        }
        check(if (create) !targetSeen else targetSeen)
    }
}

/** Bounded no-follow listing of the already installed leaf; every selected file is reauthenticated. */
private fun androidListPhysicalNative(context: Context, spec: PhysicalNativeFileSpecV1,
    catalogueOriginal: AndroidPhysicalCatalogueProvenance,
    composition: AndroidPhysicalCompositionNamespaceV1, expectedDirectoryIdentity: String): List<String>? {
    if (Build.VERSION.SDK_INT < AndroidPreallocatedPhysicalSpool.MIN_API_LEVEL) return null
    return try {
        check(!Thread.currentThread().isInterrupted)
        catalogueOriginal.requireSpec(spec)
        val application = context.applicationContext as? Application ?: error("physical.spool.application")
        val processUid = Process.myUid()
        check(application.applicationContext === application && !application.isDeviceProtectedStorage &&
            application.applicationInfo.uid == processUid)
        check(application.getSystemService(UserManager::class.java)?.isUserUnlocked == true)
        val namespace = androidSpoolNamespace(application, spec.segments, processUid, "android-journal-namespace/v1")
        check(namespace.identity == expectedDirectoryIdentity)
        val directory = namespace.allocationDirectory.file
        val names = mutableListOf<String>()
        AndroidNativeFiles.openDirectory(directory.path).use { cursor ->
            while (true) {
                val name = cursor.nextName() ?: break
                check(names.size < LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS)
                names += name
            }
        }
        namespace.requireCurrent(application, processUid)
        names
    } catch (_: Exception) { null }
}

private inline fun androidSpoolCleanup(block: () -> Unit): Boolean = try { block(); true } catch (_: Exception) { false }

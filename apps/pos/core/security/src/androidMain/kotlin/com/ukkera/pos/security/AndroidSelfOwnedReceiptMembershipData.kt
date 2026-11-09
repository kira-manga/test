package com.ukkera.pos.security

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.ukkera.pos.android.files.AndroidNativeFiles
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Independent immutable encrypted DATA, registered with the original installation lifetime
 * before any key/file acquisition. Only live keys, owned identities and counters survive a
 * park; no original reader, trusted root or offset collection is retained or recovered.
 */
internal class AndroidSelfOwnedReceiptMembershipData(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val work: AndroidOwnedInstallationWork,
    private val environment: AndroidSelfOwnedEnvironment,
    readIdentity: Any?,
) : SelfOwnedNativeReceiptMembershipDataController {
    internal enum class State { NEW, OPENING, BUILDING, PARKING, PARKED, REOPENING, PREPARED, ACTIVE, CLOSING, CLOSED, UNKNOWN }
    @Volatile private var state = State.NEW
    private val busy = AtomicBoolean(false)
    private val denied = AtomicBoolean(false)
    private var readIdentity: Any? = readIdentity
    private var directory: File? = null
    private var directoryCreated = false
    private var directoryRemoved = false
    private var fileCreated = false
    private var fileRemoved = false
    private var parentStamp: Stamp? = null
    private var directoryStamp: Stamp? = null
    private var fileStamp: Stamp? = null
    private var parentDescriptor: OwnedDescriptor? = null
    private var directoryDescriptor: OwnedDescriptor? = null
    private var fileDescriptor: OwnedDescriptor? = null
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private var encryptionKey: SecretKey? = null
    private var locatorKey: SecretKey? = null
    private var resourceId: ByteArray? = null
    private var noncePrefix: ByteArray? = null
    private var constructionCompleted = false
    private var nextSequence = 1L
    private var endOffset = 0L

    internal val stateNative: State get() = state
    internal val readIdentityNative: Any? get() = readIdentity

    internal fun belongsToReadNative(identity: Any): Boolean = readIdentity === identity

    internal fun requireActuallyParkedNative() {
        known(state == State.PARKED && !busy.get() && descriptors.isEmpty() &&
            parentDescriptor == null && directoryDescriptor == null && fileDescriptor == null)
    }

    internal fun requireCompletedBuildParkedNative() {
        requireActuallyParkedNative()
        known(constructionCompleted && !denied.get() && readIdentity != null &&
            directoryCreated && !directoryRemoved && fileCreated && !fileRemoved &&
            parentStamp != null && directoryStamp != null && fileStamp != null &&
            encryptionKey != null && locatorKey != null && resourceId != null && noncePrefix != null)
    }

    internal fun requirePreparedForDeliveryNoIo() {
        known(state == State.PREPARED && constructionCompleted && !busy.get() && !denied.get() && readIdentity != null &&
            parentDescriptor != null && directoryDescriptor != null && fileDescriptor != null)
    }

    internal fun activatePreparedNoIo() {
        requirePreparedForDeliveryNoIo()
        readIdentity = null
        state = State.ACTIVE
    }

    internal fun abandonDeliveryNoIo() { denied.set(true); readIdentity = null }
    internal fun denyUseNoIo() { denied.set(true) }

    internal fun openNative() {
        known(state == State.NEW)
        state = State.OPENING
        owner.requireReceiptMembershipDataNative(this)
        val random = SecureRandom()
        resourceId = ByteArray(16)
        random.nextBytes(requireNotNull(resourceId))
        noncePrefix = ByteArray(4)
        random.nextBytes(requireNotNull(noncePrefix))
        encryptionKey = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        val keyBytes = ByteArray(32)
        try { random.nextBytes(keyBytes); locatorKey = SecretKeySpec(keyBytes, "HmacSHA256") }
        finally { keyBytes.fill(0) }

        val parent = environment.noBackupDirectory
        val observedParent = stamp(Os.lstat(parent.path))
        requireDirectory(observedParent, owned = false)
        known(observedParent.uid == environment.uid)
        parentStamp = observedParent
        parentDescriptor = openDirectory(parent, observedParent)
        val name = DIRECTORY_PREFIX + requireNotNull(resourceId).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        val ownedDirectory = File(parent, name)
        directory = ownedDirectory
        known(lstatOrNull(ownedDirectory) == null)
        Os.mkdir(ownedDirectory.path, DIRECTORY_MODE)
        directoryCreated = true
        val observedDirectory = stamp(Os.lstat(ownedDirectory.path))
        requireDirectory(observedDirectory, owned = true)
        known(observedDirectory.device == observedParent.device)
        directoryStamp = observedDirectory
        directoryDescriptor = openDirectory(ownedDirectory, observedDirectory)
        known(androidOwnedDirectoryNames(ownedDirectory, 0, work).isEmpty())

        val file = File(ownedDirectory, FILE_NAME)
        fileDescriptor = openDescriptor(file,
            OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, FILE_MODE)
        fileCreated = true
        Os.fchmod(requireNotNull(fileDescriptor).fd, FILE_MODE)
        val observedFile = stamp(Os.fstat(requireNotNull(fileDescriptor).fd))
        requireFile(observedFile)
        known(observedFile.sameIdentity(stamp(Os.lstat(file.path))) && observedFile.bytes == 0L)
        fileStamp = observedFile
        state = if (readIdentity == null) State.ACTIVE else State.BUILDING
        owner.requireReceiptMembershipDataNative(this)
        checkFootprint(open = true, active = true)
        constructionCompleted = true
    }

    internal fun reopenAfterClosedReadNative() {
        requireCompletedBuildParkedNative()
        state = State.REOPENING
        owner.requireReceiptMembershipDataNative(this)
        checkFootprint(open = false, active = true)
        parentDescriptor = openDirectory(environment.noBackupDirectory, requireNotNull(parentStamp))
        directoryDescriptor = openDirectory(requireNotNull(directory), requireNotNull(directoryStamp))
        // The file must be the exact existing DATA file; reopen has no CREATE or replacement edge.
        fileDescriptor = openDescriptor(File(requireNotNull(directory), FILE_NAME),
            OsConstants.O_RDWR or OsConstants.O_NOFOLLOW, 0)
        state = State.PREPARED
        owner.requireReceiptMembershipDataNative(this)
        checkFootprint(open = true, active = true)
    }

    override suspend fun append(bytes: ByteArray): ByteArray {
        input(bytes.size in 1..MAX_BLOCK)
        return dataWork { token ->
            val copied = bytes.copyOf()
            task(token, clear = { copied.fill(0) }, dispose = { it.fill(0) }) { appendNative(copied, token) }
        }
    }

    override suspend fun read(blockId: ByteArray): ByteArray {
        input(blockId.size == ID_BYTES)
        return dataWork { token ->
            val copied = blockId.copyOf()
            task(token, clear = { copied.fill(0) }, dispose = { it.fill(0) }) { readNative(copied, token) }
        }
    }

    override suspend fun preflight(): Unit = dataWork { token -> task(token) { Unit } }

    override suspend fun park(): Unit = withContext(NonCancellable) {
        if (state == State.PARKED || state == State.CLOSED) return@withContext
        val token = work.roomToken(currentCoroutineContext())
            ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        owner.requireReceiptMembershipDataCleanupNative(this@AndroidSelfOwnedReceiptMembershipData, token)
        if (!busy.compareAndSet(false, true)) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        try {
            parkNative()
            owner.requireReceiptMembershipDataCleanupNative(this@AndroidSelfOwnedReceiptMembershipData, token)
        } catch (failure: Throwable) {
            denied.set(true)
            owner.denyDataUse()
            owner.requestHistoryLifetimeClose()
            throw failure
        } finally { busy.set(false) }
    }

    private fun parkNative() {
        if (state == State.PARKED || state == State.CLOSED) return
        if (state == State.PARKING || state == State.CLOSING || state == State.UNKNOWN) closureUnknown()
        state = State.PARKING
        try {
            for (descriptor in descriptors.toList().asReversed()) descriptor.closeActual()
            parentDescriptor = null; directoryDescriptor = null; fileDescriptor = null
            state = State.PARKED
        } catch (failure: Throwable) { state = State.UNKNOWN; work.nativeClosureUnknown(); throw failure }
    }

    /** Only the lifetime's actual watched terminal cleanup calls this. No active-data guard. */
    internal fun closeNative() {
        if (state == State.CLOSED) return
        if (!busy.compareAndSet(false, true)) closureUnknown()
        denied.set(true)
        try {
            parkNative()
            state = State.CLOSING
            if (directoryCreated && !directoryRemoved) {
                val ownedDirectory = requireNotNull(directory)
                known(requireNotNull(parentStamp).sameDirectory(stamp(Os.lstat(environment.noBackupDirectory.path))))
                known(requireNotNull(directoryStamp).sameDirectory(stamp(Os.lstat(ownedDirectory.path))))
                val names = names(active = false)
                known(names.all { it == FILE_NAME })
                if (fileCreated && !fileRemoved) {
                    known(names == listOf(FILE_NAME))
                    val file = File(ownedDirectory, FILE_NAME)
                    val observed = stamp(Os.lstat(file.path)); requireFile(observed)
                    known(requireNotNull(fileStamp).sameIdentity(observed))
                    Os.remove(file.path)
                    fileRemoved = true
                    known(lstatOrNull(file) == null)
                } else known(names.isEmpty())
                known(names(active = false).isEmpty())
                known(requireNotNull(directoryStamp).sameDirectory(stamp(Os.lstat(ownedDirectory.path))))
                Os.remove(ownedDirectory.path)
                directoryRemoved = true
                known(lstatOrNull(ownedDirectory) == null)
            }
            // Keys are not persisted. Drop provider references; JVM/provider erasure is not claimed.
            encryptionKey = null; locatorKey = null
            resourceId?.fill(0); resourceId = null
            noncePrefix?.fill(0); noncePrefix = null
            readIdentity = null
            constructionCompleted = false
            state = State.CLOSED
        } catch (failure: Throwable) { state = State.UNKNOWN; work.nativeClosureUnknown(); throw failure }
        finally { busy.set(false) }
    }

    private fun appendNative(bytes: ByteArray, token: Any): ByteArray {
        input(bytes.size in 1..MAX_BLOCK)
        if (nextSequence == Long.MAX_VALUE || endOffset > Long.MAX_VALUE - HEADER_BYTES - TAG_BYTES - bytes.size)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val sequence = nextSequence++ // Consumed before cipher/provider/IO; never reused on failure.
        val offset = endOffset
        val header = buffer(HEADER_BYTES).putInt(FORMAT).putInt(bytes.size).putLong(sequence).array()
        val aad = aad(offset, sequence, bytes.size)
        val nonce = nonce(sequence)
        var envelope: ByteArray? = null
        var id: ByteArray? = null
        var delivered = false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(encryptionKey), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(bytes).also { envelope = it }
            known(encrypted.size == bytes.size + TAG_BYTES)
            val locator = blockId(offset, sequence).also { id = it }
            writeExact(header, offset, token)
            writeExact(encrypted, offset + HEADER_BYTES, token)
            endOffset = offset + HEADER_BYTES + encrypted.size
            delivered = true
            return locator
        } finally {
            header.fill(0); aad.fill(0); nonce.fill(0); envelope?.fill(0)
            if (!delivered) id?.fill(0)
        }
    }

    private fun readNative(id: ByteArray, token: Any): ByteArray {
        val coordinate = decodeId(id)
        known(coordinate.offset <= endOffset - HEADER_BYTES - TAG_BYTES)
        val header = ByteArray(HEADER_BYTES)
        var envelope: ByteArray? = null
        var associated: ByteArray? = null
        var initialization: ByteArray? = null
        var plaintext: ByteArray? = null
        var delivered = false
        try {
            readExact(header, coordinate.offset, token)
            val decoded = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            known(decoded.int == FORMAT)
            val size = decoded.int
            known(size in 1..MAX_BLOCK && decoded.long == coordinate.sequence &&
                coordinate.offset <= endOffset - HEADER_BYTES - TAG_BYTES - size)
            val encrypted = ByteArray(size + TAG_BYTES).also { envelope = it }
            readExact(encrypted, coordinate.offset + HEADER_BYTES, token)
            val aad = aad(coordinate.offset, coordinate.sequence, size).also { associated = it }
            val nonce = nonce(coordinate.sequence).also { initialization = it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, requireNotNull(encryptionKey), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val value = cipher.doFinal(encrypted).also { plaintext = it }
            known(value.size == size)
            delivered = true
            return value
        } finally {
            header.fill(0); envelope?.fill(0); associated?.fill(0); initialization?.fill(0)
            if (!delivered) plaintext?.fill(0)
        }
    }

    private data class Coordinate(val offset: Long, val sequence: Long)

    private fun decodeId(id: ByteArray): Coordinate {
        input(id.size == ID_BYTES)
        val decoded = ByteBuffer.wrap(id).order(ByteOrder.BIG_ENDIAN)
        val offset = decoded.long
        val sequence = decoded.long
        known(offset >= 0 && sequence in 1L until nextSequence)
        val expected = locatorTag(offset, sequence)
        val supplied = id.copyOfRange(16, ID_BYTES)
        try { known(MessageDigest.isEqual(expected, supplied)) }
        finally { expected.fill(0); supplied.fill(0) }
        return Coordinate(offset, sequence)
    }

    private fun blockId(offset: Long, sequence: Long): ByteArray {
        val tag = locatorTag(offset, sequence)
        return try { buffer(ID_BYTES).putLong(offset).putLong(sequence).put(tag).array() }
        finally { tag.fill(0) }
    }

    private fun locatorTag(offset: Long, sequence: Long): ByteArray {
        val coordinate = buffer(20).putInt(ID_DOMAIN).putLong(offset).putLong(sequence).array()
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(requireNotNull(locatorKey))
            mac.update(requireNotNull(resourceId))
            val digest = mac.doFinal(coordinate)
            try { digest.copyOf(TAG_BYTES) } finally { digest.fill(0) }
        } finally { coordinate.fill(0) }
    }

    private fun aad(offset: Long, sequence: Long, size: Int): ByteArray = buffer(44)
        .putInt(PAYLOAD_DOMAIN).putInt(FORMAT).putInt(size).putLong(offset).putLong(sequence)
        .put(requireNotNull(resourceId)).array()

    private fun nonce(sequence: Long): ByteArray = buffer(12).put(requireNotNull(noncePrefix)).putLong(sequence).array()

    private fun readExact(bytes: ByteArray, offset: Long, token: Any) {
        var done = 0
        while (done < bytes.size) {
            progressGuard(token)
            val count = try { Os.pread(requireNotNull(fileDescriptor).fd, bytes, done, bytes.size - done, offset + done) }
            catch (failure: ErrnoException) { if (failure.errno == OsConstants.EINTR) continue else throw failure }
            known(count in 1..(bytes.size - done))
            done += count
            progressGuard(token)
        }
    }

    private fun writeExact(bytes: ByteArray, offset: Long, token: Any) {
        var done = 0
        while (done < bytes.size) {
            progressGuard(token)
            val count = try { Os.pwrite(requireNotNull(fileDescriptor).fd, bytes, done, bytes.size - done, offset + done) }
            catch (failure: ErrnoException) { if (failure.errno == OsConstants.EINTR) continue else throw failure }
            known(count in 1..(bytes.size - done))
            done += count
            progressGuard(token)
        }
    }

    private fun progressGuard(token: Any) {
        work.requireWatchedRoom(token); work.requireActive()
        if (denied.get() || owner.dataUseDeniedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    }

    private suspend fun <T> dataWork(prepare: (Any) -> AndroidOwnedTask<T>): T {
        val token = work.roomToken(currentCoroutineContext())
            ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        try {
            return when (val result = work.run { prepare(token) }) {
                is AndroidOwnedResult.Value -> result.value
                is AndroidOwnedResult.Unavailable -> throw NativeHistoryUnavailable(result.reason)
            }
        } catch (failure: Throwable) {
            // A busy non-admission has not performed native DATA work. Every other failed
            // admitted action, including a final guard/cancellation, retires this resource.
            if ((failure as? NativeHistoryUnavailable)?.reason != SelfOwnedInstallationFailureV1.BUSY) {
                denied.set(true); owner.denyDataUse(); owner.requestHistoryLifetimeClose()
            }
            throw failure
        }
    }

    private fun <T> task(token: Any, clear: () -> Unit = {}, dispose: (T) -> Unit = {},
        body: () -> T): AndroidOwnedTask<T> = object : AndroidOwnedTask<T> {
            override fun execute(): T {
                if (!busy.compareAndSet(false, true)) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
                try {
                    guard(token)
                    val value = body()
                    try { guard(token); return value }
                    catch (failure: Throwable) { dispose(value); throw failure }
                } catch (failure: Throwable) {
                    denied.set(true); owner.denyDataUse(); owner.requestHistoryLifetimeClose()
                    throw failure
                } finally { busy.set(false) }
            }
            override fun discard(value: T) { dispose(value) }
            override fun clearInputs() { clear() }
    }

    private fun guard(token: Any) {
        known(state == State.BUILDING || state == State.PREPARED || state == State.ACTIVE)
        progressGuard(token)
        owner.requireReceiptMembershipDataNative(this)
        checkFootprint(open = true, active = true)
        progressGuard(token)
    }

    private fun checkFootprint(open: Boolean, active: Boolean) {
        val parent = requireNotNull(parentStamp)
        known(parent.sameDirectory(stamp(Os.lstat(environment.noBackupDirectory.path))))
        val ownedDirectory = requireNotNull(directory)
        known(directoryCreated && !directoryRemoved && fileCreated && !fileRemoved)
        val expectedDirectory = requireNotNull(directoryStamp)
        known(expectedDirectory.sameDirectory(stamp(Os.lstat(ownedDirectory.path))))
        known(names(active) == listOf(FILE_NAME))
        val observedFile = stamp(Os.lstat(File(ownedDirectory, FILE_NAME).path)); requireFile(observedFile)
        known(requireNotNull(fileStamp).sameIdentity(observedFile) && observedFile.bytes == endOffset)
        if (open) {
            known(parent.sameDirectory(stamp(Os.fstat(requireNotNull(parentDescriptor).fd))))
            known(expectedDirectory.sameDirectory(stamp(Os.fstat(requireNotNull(directoryDescriptor).fd))))
            val openedFile = stamp(Os.fstat(requireNotNull(fileDescriptor).fd)); requireFile(openedFile)
            known(requireNotNull(fileStamp).sameIdentity(openedFile) && openedFile.bytes == endOffset)
            known(AndroidNativeFiles.closeOnExec(requireNotNull(parentDescriptor).fd) &&
                AndroidNativeFiles.closeOnExec(requireNotNull(directoryDescriptor).fd) &&
                AndroidNativeFiles.closeOnExec(requireNotNull(fileDescriptor).fd))
        } else known(descriptors.isEmpty())
    }

    private fun names(active: Boolean): List<String> {
        val ownedDirectory = requireNotNull(directory)
        if (active) return androidOwnedDirectoryNames(ownedDirectory, 1, work)
        val cursor = try { AndroidNativeFiles.openDirectory(ownedDirectory.path) }
        catch (failure: AndroidNativeFiles.ClosureUncertainException) { work.nativeClosureUnknown(); throw failure }
        return try {
            val first = cursor.nextName() ?: return emptyList()
            known(cursor.nextName() == null)
            listOf(first)
        } finally {
            try { cursor.close() }
            catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
    }

    private inner class OwnedDescriptor {
        var native: FileDescriptor? = null
        val fd: FileDescriptor get() = requireNotNull(native)
        private var closeStarted = false
        fun closeActual() {
            if (closeStarted) closureUnknown()
            closeStarted = true
            native?.let {
                try { AndroidNativeFiles.close(it); native = null }
                catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
            }
            descriptors.remove(this)
        }
    }

    private fun openDescriptor(path: File, flags: Int, mode: Int): OwnedDescriptor {
        val descriptor = OwnedDescriptor().also { descriptors += it }
        descriptor.native = androidOwnedOpenDescriptor(path, flags, mode, work)
        return descriptor
    }

    private fun openDirectory(path: File, expected: Stamp): OwnedDescriptor {
        // O_DIRECTORY is not a public Android field; authenticate the actual directory fd.
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        known(AndroidNativeFiles.closeOnExec(descriptor.fd) && expected.sameDirectory(stamp(Os.fstat(descriptor.fd))) &&
            expected.sameDirectory(stamp(Os.lstat(path.path))))
        return descriptor
    }

    private data class Stamp(val device: Long, val inode: Long, val uid: Int, val gid: Int,
        val mode: Int, val links: Long, val bytes: Long) {
        fun sameIdentity(other: Stamp): Boolean = device == other.device && inode == other.inode &&
            uid == other.uid && gid == other.gid && mode == other.mode && links == other.links
        fun sameDirectory(other: Stamp): Boolean = device == other.device && inode == other.inode &&
            uid == other.uid && gid == other.gid && mode == other.mode && OsConstants.S_ISDIR(other.mode)
    }
    private fun stamp(value: StructStat): Stamp = Stamp(value.st_dev, value.st_ino, value.st_uid,
        value.st_gid, value.st_mode, value.st_nlink, value.st_size)
    private fun requireDirectory(value: Stamp, owned: Boolean) {
        known(OsConstants.S_ISDIR(value.mode) && value.mode and 2 == 0)
        if (owned) known(value.uid == environment.uid && value.mode and 4095 == DIRECTORY_MODE)
    }
    private fun requireFile(value: Stamp) {
        known(OsConstants.S_ISREG(value.mode) && value.uid == environment.uid && value.mode and 4095 == FILE_MODE &&
            value.links == 1L && value.device == requireNotNull(directoryStamp).device)
    }
    private fun lstatOrNull(path: File): StructStat? = try { Os.lstat(path.path) }
    catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) null else throw failure }
    private fun buffer(bytes: Int): ByteBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN)
    private fun input(value: Boolean) { if (!value) throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
    private fun closureUnknown(): Nothing {
        work.nativeClosureUnknown()
        selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    }

    private companion object {
        const val DIRECTORY_PREFIX = "ukkera-native-receipt-data-v1-"
        const val FILE_NAME = "blocks.data"
        const val DIRECTORY_MODE = 448 // 0700
        const val FILE_MODE = 384 // 0600
        const val FORMAT = 1
        const val HEADER_BYTES = 16
        const val TAG_BYTES = 16
        const val ID_BYTES = SelfOwnedNativeReceiptMembershipData.BLOCK_ID_BYTES
        const val MAX_BLOCK = SelfOwnedNativeReceiptMembershipData.MAX_BLOCK_BYTES
        const val ID_DOMAIN = 1
        const val PAYLOAD_DOMAIN = 2
    }
}

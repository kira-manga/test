package com.ukkera.pos.security

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
internal class DesktopSelfOwnedReceiptMembershipData(
    private val files: DesktopFiles,
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val work: AndroidOwnedInstallationWork,
    private val environment: SelfOwnedOriginalEnvironment,
    readIdentity: Any?,
) : SelfOwnedNativeReceiptMembershipDataController , SelfOwnedReceiptMembershipStore {
    @Volatile private var state = SelfOwnedReceiptMembershipState.NEW
    private val busy = AtomicBoolean(false)
    private val denied = AtomicBoolean(false)
    private var readIdentity: Any? = readIdentity
    private var directory: String? = null
    private var directoryCreated = false
    private var directoryRemoved = false
    private var fileCreated = false
    private var fileRemoved = false
    private var parentStamp: DesktopStamp? = null
    private var directoryStamp: DesktopStamp? = null
    private var fileStamp: DesktopStamp? = null
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

    override val stateNative: SelfOwnedReceiptMembershipState get() = state
    override val readIdentityNative: Any? get() = readIdentity

    override fun belongsToReadNative(identity: Any): Boolean = readIdentity === identity

    override fun requireActuallyParkedNative() {
        known(state == SelfOwnedReceiptMembershipState.PARKED && !busy.get() && descriptors.isEmpty() &&
            parentDescriptor == null && directoryDescriptor == null && fileDescriptor == null)
    }

    override fun requireCompletedBuildParkedNative() {
        requireActuallyParkedNative()
        known(constructionCompleted && !denied.get() && readIdentity != null &&
            directoryCreated && !directoryRemoved && fileCreated && !fileRemoved &&
            parentStamp != null && directoryStamp != null && fileStamp != null &&
            encryptionKey != null && locatorKey != null && resourceId != null && noncePrefix != null)
    }

    override fun requirePreparedForDeliveryNoIo() {
        known(state == SelfOwnedReceiptMembershipState.PREPARED && constructionCompleted && !busy.get() && !denied.get() && readIdentity != null &&
            parentDescriptor != null && directoryDescriptor != null && fileDescriptor != null)
    }

    override fun activatePreparedNoIo() {
        requirePreparedForDeliveryNoIo()
        readIdentity = null
        state = SelfOwnedReceiptMembershipState.ACTIVE
    }

    override fun abandonDeliveryNoIo() { denied.set(true); readIdentity = null }
    override fun denyUseNoIo() { denied.set(true) }

    override fun openNative() {
        known(state == SelfOwnedReceiptMembershipState.NEW)
        state = SelfOwnedReceiptMembershipState.OPENING
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

        val parent = files.baseDirectory
        val observedParent = files.observe(parent) ?: uncertainState()
        known(observedParent.kind == DesktopEntryKind.DIRECTORY && observedParent.owner == files.ownerName && observedParent.ownerOnly)
        parentStamp = observedParent
        parentDescriptor = openDirectory(parent, observedParent)
        val name = DIRECTORY_PREFIX + requireNotNull(resourceId).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        val ownedDirectory = files.child(parent, name)
        directory = ownedDirectory
        known(files.observe(ownedDirectory) == null)
        files.createDirectory(ownedDirectory)
        directoryCreated = true
        val observedDirectory = files.observe(ownedDirectory) ?: uncertainState()
        requireDirectory(observedDirectory)
        known(observedDirectory.volume == observedParent.volume)
        directoryStamp = observedDirectory
        directoryDescriptor = openDirectory(ownedDirectory, observedDirectory)
        known(files.names(ownedDirectory).isEmpty())

        val file = files.child(ownedDirectory, FILE_NAME)
        fileDescriptor = OwnedDescriptor().also { descriptors += it; it.native = files.createFile(file) }
        fileCreated = true
        val observedFile = requireNotNull(fileDescriptor).fd.stamp()
        requireFile(observedFile)
        known(observedFile.sameIdentity(files.observe(file) ?: uncertainState()) && observedFile.size == 0L)
        fileStamp = observedFile
        state = if (readIdentity == null) SelfOwnedReceiptMembershipState.ACTIVE else SelfOwnedReceiptMembershipState.BUILDING
        owner.requireReceiptMembershipDataNative(this)
        checkFootprint(open = true, active = true)
        constructionCompleted = true
    }

    override fun reopenAfterClosedReadNative() {
        requireCompletedBuildParkedNative()
        state = SelfOwnedReceiptMembershipState.REOPENING
        owner.requireReceiptMembershipDataNative(this)
        checkFootprint(open = false, active = true)
        parentDescriptor = openDirectory(files.baseDirectory, requireNotNull(parentStamp))
        directoryDescriptor = openDirectory(requireNotNull(directory), requireNotNull(directoryStamp))
        // The file must be the exact existing DATA file; reopen has no CREATE or replacement edge.
        fileDescriptor = OwnedDescriptor().also {
            descriptors += it; it.native = files.openFile(files.child(requireNotNull(directory), FILE_NAME), write = true)
        }
        state = SelfOwnedReceiptMembershipState.PREPARED
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
        if (state == SelfOwnedReceiptMembershipState.PARKED || state == SelfOwnedReceiptMembershipState.CLOSED) return@withContext
        val token = work.roomToken(currentCoroutineContext())
            ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        owner.requireReceiptMembershipDataCleanupNative(this@DesktopSelfOwnedReceiptMembershipData, token)
        if (!busy.compareAndSet(false, true)) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        try {
            parkNative()
            owner.requireReceiptMembershipDataCleanupNative(this@DesktopSelfOwnedReceiptMembershipData, token)
        } catch (failure: Throwable) {
            denied.set(true)
            owner.denyDataUse()
            owner.requestHistoryLifetimeClose()
            throw failure
        } finally { busy.set(false) }
    }

    private fun parkNative() {
        if (state == SelfOwnedReceiptMembershipState.PARKED || state == SelfOwnedReceiptMembershipState.CLOSED) return
        if (state == SelfOwnedReceiptMembershipState.PARKING || state == SelfOwnedReceiptMembershipState.CLOSING || state == SelfOwnedReceiptMembershipState.UNKNOWN) closureUnknown()
        state = SelfOwnedReceiptMembershipState.PARKING
        try {
            for (descriptor in descriptors.toList().asReversed()) descriptor.closeActual()
            parentDescriptor = null; directoryDescriptor = null; fileDescriptor = null
            state = SelfOwnedReceiptMembershipState.PARKED
        } catch (failure: Throwable) { state = SelfOwnedReceiptMembershipState.UNKNOWN; work.nativeClosureUnknown(); throw failure }
    }

    /** Only the lifetime's actual watched terminal cleanup calls this. No active-data guard. */
    override fun closeNative() {
        if (state == SelfOwnedReceiptMembershipState.CLOSED) return
        if (!busy.compareAndSet(false, true)) closureUnknown()
        denied.set(true)
        try {
            parkNative()
            state = SelfOwnedReceiptMembershipState.CLOSING
            if (directoryCreated && !directoryRemoved) {
                val ownedDirectory = requireNotNull(directory)
                known(requireNotNull(parentStamp).sameDirectory(files.observe(files.baseDirectory) ?: uncertainState()))
                known(requireNotNull(directoryStamp).sameDirectory(files.observe(ownedDirectory) ?: uncertainState()))
                val names = names(active = false)
                known(names.all { it == FILE_NAME })
                if (fileCreated && !fileRemoved) {
                    known(names == listOf(FILE_NAME))
                    val file = files.child(ownedDirectory, FILE_NAME)
                    val observed = files.observe(file) ?: uncertainState(); requireFile(observed)
                    known(requireNotNull(fileStamp).sameIdentity(observed))
                    files.remove(file)
                    fileRemoved = true
                    known(files.observe(file) == null)
                } else known(names.isEmpty())
                known(names(active = false).isEmpty())
                known(requireNotNull(directoryStamp).sameDirectory(files.observe(ownedDirectory) ?: uncertainState()))
                files.remove(ownedDirectory)
                directoryRemoved = true
                known(files.observe(ownedDirectory) == null)
            }
            // Keys are not persisted. Drop provider references; JVM/provider erasure is not claimed.
            encryptionKey = null; locatorKey = null
            resourceId?.fill(0); resourceId = null
            noncePrefix?.fill(0); noncePrefix = null
            readIdentity = null
            constructionCompleted = false
            state = SelfOwnedReceiptMembershipState.CLOSED
        } catch (failure: Throwable) { state = SelfOwnedReceiptMembershipState.UNKNOWN; work.nativeClosureUnknown(); throw failure }
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
            val count = requireNotNull(fileDescriptor).fd.preadAt(bytes, done, bytes.size - done, offset + done)
            known(count in 1..(bytes.size - done))
            done += count
            progressGuard(token)
        }
    }

    private fun writeExact(bytes: ByteArray, offset: Long, token: Any) {
        var done = 0
        while (done < bytes.size) {
            progressGuard(token)
            val count = requireNotNull(fileDescriptor).fd.pwriteAt(bytes, done, bytes.size - done, offset + done)
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
        known(state == SelfOwnedReceiptMembershipState.BUILDING || state == SelfOwnedReceiptMembershipState.PREPARED || state == SelfOwnedReceiptMembershipState.ACTIVE)
        progressGuard(token)
        owner.requireReceiptMembershipDataNative(this)
        checkFootprint(open = true, active = true)
        progressGuard(token)
    }

    private fun checkFootprint(open: Boolean, active: Boolean) {
        val parent = requireNotNull(parentStamp)
        known(parent.sameDirectory(files.observe(files.baseDirectory) ?: uncertainState()))
        val ownedDirectory = requireNotNull(directory)
        known(directoryCreated && !directoryRemoved && fileCreated && !fileRemoved)
        val expectedDirectory = requireNotNull(directoryStamp)
        known(expectedDirectory.sameDirectory(files.observe(ownedDirectory) ?: uncertainState()))
        known(names(active) == listOf(FILE_NAME))
        val observedFile = files.observe(files.child(ownedDirectory, FILE_NAME)) ?: uncertainState(); requireFile(observedFile)
        known(requireNotNull(fileStamp).sameIdentity(observedFile) && observedFile.size == endOffset)
        if (open) {
            known(parent.sameDirectory(requireNotNull(parentDescriptor).fd.stamp()))
            known(expectedDirectory.sameDirectory(requireNotNull(directoryDescriptor).fd.stamp()))
            val openedFile = requireNotNull(fileDescriptor).fd.stamp(); requireFile(openedFile)
            known(requireNotNull(fileStamp).sameIdentity(openedFile) && openedFile.size == endOffset)
        } else known(descriptors.isEmpty())
    }

    private fun names(@Suppress("UNUSED_PARAMETER") active: Boolean): List<String> {
        val result = files.names(requireNotNull(directory))
        known(result.size <= 1)
        return result
    }

    private inner class OwnedDescriptor {
        var native: DesktopHandle? = null
        val fd: DesktopHandle get() = requireNotNull(native)
        private var closeStarted = false
        fun closeActual() {
            if (closeStarted) closureUnknown()
            closeStarted = true
            native?.let {
                try { it.close(); native = null }
                catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
            }
            descriptors.remove(this)
        }
    }

    private fun openDirectory(path: String, expected: DesktopStamp): OwnedDescriptor {
        val descriptor = OwnedDescriptor().also { descriptors += it; it.native = files.openDirectory(path) }
        known(expected.sameDirectory(descriptor.fd.stamp()) && expected.sameDirectory(files.observe(path) ?: uncertainState()))
        return descriptor
    }

    private fun requireDirectory(value: DesktopStamp) {
        known(value.kind == DesktopEntryKind.DIRECTORY && value.owner == files.ownerName && value.ownerOnly)
    }
    private fun requireFile(value: DesktopStamp) {
        known(value.kind == DesktopEntryKind.FILE && value.owner == files.ownerName && value.ownerOnly &&
            value.links == 1L && value.volume == requireNotNull(directoryStamp).volume)
    }
    private fun uncertainState(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
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
        const val FORMAT = 1
        const val HEADER_BYTES = 16
        const val TAG_BYTES = 16
        const val ID_BYTES = SelfOwnedNativeReceiptMembershipData.BLOCK_ID_BYTES
        const val MAX_BLOCK = SelfOwnedNativeReceiptMembershipData.MAX_BLOCK_BYTES
        const val ID_DOMAIN = 1
        const val PAYLOAD_DOMAIN = 2
    }
}

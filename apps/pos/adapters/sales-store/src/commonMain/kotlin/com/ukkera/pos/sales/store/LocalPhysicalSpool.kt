package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** A contributor label, not appointment as the branch master or proof of the paired client. */
public enum class LocalPhysicalSpoolRole { MASTER, CLIENT }

/** Exactly what native preflight did; neither profile establishes complete financial capacity. */
public enum class LocalPhysicalSpoolStorageProfileV1 {
    JVM_POSIX_FULL_WRITE_FORCE_V1,
    ANDROID_API30_APP_PRIVATE_FALLOCATE_V1,
}

/** Constructor-trusted local configuration. Never decode this from a client's storage assertion. */
public data class LocalPhysicalSpoolBinding(
    public val requirement: SalesPhysicalRecordingRequirementV2,
    public val role: LocalPhysicalSpoolRole,
    public val installationId: DomainId,
    public val deviceId: DomainId,
    public val spoolId: DomainId,
    public val allocationId: DomainId,
    public val keyId: DomainId,
) {
    init {
        require(role != LocalPhysicalSpoolRole.CLIENT || allocationId == requirement.clientAllocationId)
        physicalSpoolTextBound(requirement.tender.kind, 128)
    }
    override fun toString(): String = "LocalPhysicalSpoolBindingV1([REDACTED])"
}

/** Native observations only. Matching these fields does not authenticate installation custody. */
public data class LocalPhysicalSpoolNativeIdentity(
    public val profile: LocalPhysicalSpoolStorageProfileV1,
    public val directoryIdentity: String,
    public val fileIdentity: String,
    public val ownerIdentity: String,
    public val cryptographyIdentity: String,
) {
    init {
        physicalSpoolTextBound(directoryIdentity, 256)
        physicalSpoolTextBound(fileIdentity, 256)
        physicalSpoolTextBound(ownerIdentity, 256)
        physicalSpoolTextBound(cryptographyIdentity, 512)
    }
    override fun toString(): String = "LocalPhysicalSpoolNativeIdentityV1([REDACTED])"
}

/**
 * Immutable native-preflight expectation. Public construction allows exact restoration from a
 * separately authenticated installer/source record; this object is NOT itself that authority.
 * openExisting rechecks its native identities, full authenticated binding and exact header hash.
 * No conversion to SalesPhysicalRecordingBackingV2 or protected current source is provided.
 */
public class LocalPhysicalSpoolReceipt(
    public val binding: LocalPhysicalSpoolBinding,
    public val nativeIdentity: LocalPhysicalSpoolNativeIdentity,
    public val headerDigest: PayloadDigest,
) {
    internal val plan: PhysicalSpoolPlanV1 = PhysicalSpoolPlanV1(binding, nativeIdentity)
    public val requirementDigest: PayloadDigest get() = plan.requirementDigest
    public val allocationDigest: PayloadDigest get() = plan.digest
    public val fileBytes: Int get() = LocalPhysicalSpoolFormatV1.FILE_BYTES
    override fun toString(): String = "LocalPhysicalSpoolReceiptV1([REDACTED])"
}

public sealed interface LocalPhysicalSpoolOpen {
    /** Native preflight/readback only. MUST NOT enable asking for physical money. */
    public class Opened(public val spool: LocalPhysicalSpool) : LocalPhysicalSpoolOpen
    public data object Busy : LocalPhysicalSpoolOpen
    public data object Held : LocalPhysicalSpoolOpen
}

public sealed interface LocalPhysicalSpoolRead {
    public class Read(public val snapshot: LocalPhysicalSpoolSnapshot) : LocalPhysicalSpoolRead
    public data object Busy : LocalPhysicalSpoolRead
    public data object Held : LocalPhysicalSpoolRead
}

public enum class LocalPhysicalSpoolWrite {
    RETAINED, ALREADY_RETAINED, IDENTITY_CONFLICT, INVALID_INPUT, OBSERVATION_REQUIRED, BUSY, HELD,
}

/** HELD means cleanup encountered uncertainty; never assume a blocked native owner was fenced. */
public enum class LocalPhysicalSpoolClose { CLOSED, HELD }

/**
 * Retained exact bodies, NOT a committed/master-authenticated reply. A null observation means
 * only no retained observation in this file; it never proves no physical money moved. Applying
 * an UNKNOWN observation does not make it known. No pending/observation body is rewritten.
 */
public class LocalPhysicalSpoolSnapshot internal constructor(
    public val receipt: LocalPhysicalSpoolReceipt,
    public val lifetimeId: DomainId,
    public val observation: StoredBytes?,
    public val pendingReply: StoredBytes?,
    public val applicationReply: StoredBytes?,
) {
    public val observationDigest: PayloadDigest? get() = observation?.copyBytes()?.withSpoolBytes(::sha256)
    public val observationSemanticDigest: PayloadDigest? get() = observation?.let { body ->
        receipt.binding.requirement.let { semanticDigest(it.scope, it.actorId, it.command, body) }
    }
    public val pendingReplyDigest: PayloadDigest? get() = pendingReply?.copyBytes()?.withSpoolBytes(::sha256)
    public val applicationReplyDigest: PayloadDigest? get() = applicationReply?.copyBytes()?.withSpoolBytes(::sha256)
    override fun toString(): String = "LocalPhysicalSpoolSnapshotV1([REDACTED])"
}

/**
 * One actual native allocation. Blocking trusted-worker API: never call inside a borrowed
 * database transaction, on the UI thread, or as a client/RPC authority assertion. There is no
 * accepting physical-capacity implementation, free-space check, liveness Boolean or permit here.
 *
 * The handle owns its native lock/descriptors until close. Concurrent access is non-queuing;
 * close marks the handle closed before waiting for in-flight native work to finish. A caller
 * dispatching open across its own cancellation boundary must close any undelivered handle.
 * Native/protection uncertainty permanently holds this instance and preserves all disk bytes.
 */
public class LocalPhysicalSpool internal constructor(
    public val receipt: LocalPhysicalSpoolReceipt,
    private val file: PhysicalSpoolFile,
    private val cipher: PhysicalSpoolCipher,
) {
    /** New per owned open; neither this identifier nor the immutable receipt is a live permit. */
    public val lifetimeId: DomainId = DomainId.random()
    private var expectedFrames: List<PayloadDigest>? = null
    private val usedNonces: MutableSet<String> = mutableSetOf()

    public fun read(): LocalPhysicalSpoolRead = when (val result = file.access {
        val snapshot = readAll()
        verifyCurrentHeader()
        snapshot
    }) {
        is PhysicalSpoolAccess.Value -> LocalPhysicalSpoolRead.Read(result.value)
        PhysicalSpoolAccess.Busy -> LocalPhysicalSpoolRead.Busy
        PhysicalSpoolAccess.Held -> LocalPhysicalSpoolRead.Held
    }

    public fun retainObservation(recordingOperationId: OperationId, body: StoredBytes): LocalPhysicalSpoolWrite {
        if (recordingOperationId != receipt.binding.requirement.recordingOperationId) return LocalPhysicalSpoolWrite.IDENTITY_CONFLICT
        if (!validInput { validateObservation(body) }) return LocalPhysicalSpoolWrite.INVALID_INPUT
        return retain(PhysicalSpoolCellV1.OBSERVATION, body)
    }

    /** Caches the first Pending BODY. Caller still owes authentication of the actual master reply. */
    public fun retainPendingReply(body: StoredBytes): LocalPhysicalSpoolWrite {
        if (!validInput { validatePending(body) }) return LocalPhysicalSpoolWrite.INVALID_INPUT
        return retain(PhysicalSpoolCellV1.PENDING, body)
    }

    /** Caches the first Applied/AlreadyApplied BODY; it neither posts nor proves financial effects. */
    public fun retainApplicationReply(body: StoredBytes): LocalPhysicalSpoolWrite {
        if (!validInput { validateApplication(body) }) return LocalPhysicalSpoolWrite.INVALID_INPUT
        return retain(PhysicalSpoolCellV1.APPLICATION, body)
    }

    /** Ownership cleanup only: no observation/allocation deletion, acknowledgement or reclamation. */
    public fun close(): LocalPhysicalSpoolClose = file.close()

    private fun retain(cell: PhysicalSpoolCellV1, body: StoredBytes): LocalPhysicalSpoolWrite = when (val result = file.access {
        val before = readAll()
        val prior = before.body(cell)
        val decision = when {
            prior != null -> if (prior == body) LocalPhysicalSpoolWrite.ALREADY_RETAINED else LocalPhysicalSpoolWrite.IDENTITY_CONFLICT
            cell != PhysicalSpoolCellV1.OBSERVATION && before.observation == null -> LocalPhysicalSpoolWrite.OBSERVATION_REQUIRED
            else -> {
                val encoded = body.copyBytes().withSpoolBytes { plain ->
                    sealPhysicalSpoolCell(receipt.plan, cipher, cell, receipt.headerDigest, plain)
                }
                encoded.withSpoolBytes { encrypted ->
                    val nonce = physicalSpoolNonceIdentity(encrypted)
                    check(usedNonces.add(nonce)) { "physical.spool.nonce_reuse" }
                    val nextDigest = sha256(encrypted)
                    file.overwrite(cell.offset, encrypted)
                    file.force()
                    val previous = expectedFrames ?: error("physical.spool.unopened")
                    expectedFrames = previous.mapIndexed { index, digest -> if (index == cell.index) nextDigest else digest }
                }
                check(readAll().body(cell) == body)
                LocalPhysicalSpoolWrite.RETAINED
            }
        }
        verifyCurrentHeader()
        decision
    }) {
        is PhysicalSpoolAccess.Value -> result.value
        PhysicalSpoolAccess.Busy -> LocalPhysicalSpoolWrite.BUSY
        PhysicalSpoolAccess.Held -> LocalPhysicalSpoolWrite.HELD
    }

    /** All regions must authenticate, including vacant and unused cells; no damaged-tail repair. */
    private fun readAll(): LocalPhysicalSpoolSnapshot {
        val digests = mutableListOf<PayloadDigest>()
        val nonces = mutableSetOf<String>()
        val bodies = mutableMapOf<PhysicalSpoolCellV1, StoredBytes>()
        for (cell in PhysicalSpoolCellV1.entries) {
            file.read(cell.offset, cell.length).withSpoolBytes { encrypted ->
                val digest = sha256(encrypted)
                digests += digest
                expectedFrames?.let { check(it[cell.index] == digest) { "physical.spool.changed" } }
                if (cell == PhysicalSpoolCellV1.HEADER) check(digest == receipt.headerDigest)
                check(nonces.add(physicalSpoolNonceIdentity(encrypted)))
                val body = openPhysicalSpoolCell(receipt.plan, cipher, cell,
                    if (cell == PhysicalSpoolCellV1.HEADER) null else receipt.headerDigest, encrypted)
                try {
                    when (cell) {
                        PhysicalSpoolCellV1.HEADER -> check(body != null && body.contentEquals(receipt.plan.bytes))
                        PhysicalSpoolCellV1.PADDING -> check(body != null && body.isEmpty())
                        else -> if (body != null) bodies[cell] = StoredBytes(body)
                    }
                } finally { body?.fill(0) }
            }
        }
        val observation = bodies[PhysicalSpoolCellV1.OBSERVATION]
        val pending = bodies[PhysicalSpoolCellV1.PENDING]
        val application = bodies[PhysicalSpoolCellV1.APPLICATION]
        observation?.let(::validateObservation)
        pending?.let(::validatePending)
        application?.let(::validateApplication)
        check(observation != null || pending == null && application == null)
        if (expectedFrames == null) expectedFrames = digests.toList()
        usedNonces.addAll(nonces)
        return LocalPhysicalSpoolSnapshot(receipt, lifetimeId, observation, pending, application)
    }

    /** Reopens the immutable challenge through the selected native key/provider before publication. */
    private fun verifyCurrentHeader() {
        val cell = PhysicalSpoolCellV1.HEADER
        file.read(cell.offset, cell.length).withSpoolBytes { encrypted ->
            check(sha256(encrypted) == receipt.headerDigest)
            val opened = openPhysicalSpoolCell(receipt.plan, cipher, cell, null, encrypted)
                ?: error("physical.spool.header")
            opened.withSpoolBytes { check(it.contentEquals(receipt.plan.bytes)) }
        }
    }

    private fun validateObservation(body: StoredBytes) {
        require(body.size in 1..LocalPhysicalSpoolFormatV1.MAX_OBSERVATION_BYTES)
        val requirement = receipt.binding.requirement
        when (requirement.kind) {
            SalesRecordingKind.PAYMENT_ATTESTATION -> {
                require(LocalCollectionCommandCodecV2.normalize(body) == body)
                val request = LocalCollectionCommandCodecV2.decode(body) as? LocalCollectionRequestV2.RecordOutcome
                    ?: throw IllegalArgumentException("physical.spool.observation")
                require(request.collectionId == requirement.documentId && request.sessionId == requirement.cashSessionId &&
                    request.attemptId == requirement.attemptId && request.intentOperationId == requirement.intentOperationId)
            }
            SalesRecordingKind.REFUND_ATTESTATION -> {
                require(LocalReturnCommandCodecV2.normalize(body) == body)
                val request = LocalReturnCommandCodecV2.decode(body) as? LocalReturnRequestV2.RecordRefundOutcome
                    ?: throw IllegalArgumentException("physical.spool.observation")
                require(request.returnId == requirement.documentId && request.sessionId == requirement.cashSessionId &&
                    request.attemptId == requirement.attemptId && request.intentOperationId == requirement.intentOperationId &&
                    request.saleId == requirement.originalSaleId && request.claimId == requirement.originalClaimId)
            }
        }
    }

    private fun validatePending(body: StoredBytes) {
        require(body.size in 1..LocalPhysicalSpoolFormatV1.MAX_PENDING_REPLY_BYTES)
        val reply = LocalObservationResultCodecV2.decode(body) as? LocalObservationResultV2.Pending
            ?: throw IllegalArgumentException("physical.spool.pending")
        require(LocalObservationResultCodecV2.encode(reply) == body)
        val requirement = receipt.binding.requirement
        require(reply.recordingOperationId == requirement.recordingOperationId && reply.documentId == requirement.documentId &&
            reply.attemptId == requirement.attemptId)
    }

    private fun validateApplication(body: StoredBytes) {
        require(body.size in 1..LocalPhysicalSpoolFormatV1.MAX_APPLICATION_REPLY_BYTES)
        val reply = LocalObservationResultCodecV2.decode(body)
        require(LocalObservationResultCodecV2.encode(reply) == body)
        val requirement = receipt.binding.requirement
        val applicationId = when (reply) {
            is LocalObservationResultV2.Applied -> {
                require(reply.recordingOperationId == requirement.recordingOperationId && reply.documentId == requirement.documentId &&
                    reply.attemptId == requirement.attemptId)
                reply.applicationOperationId
            }
            is LocalObservationResultV2.AlreadyApplied -> {
                require(reply.recordingOperationId == requirement.recordingOperationId)
                reply.applicationOperationId
            }
            else -> throw IllegalArgumentException("physical.spool.application")
        }
        require(applicationId != requirement.recordingOperationId && applicationId != requirement.intentOperationId &&
            applicationId != requirement.reservationOperationId)
    }

    internal fun establishNew(encryptedFile: ByteArray) {
        check(expectedFrames == null && encryptedFile.size == LocalPhysicalSpoolFormatV1.FILE_BYTES)
        expectedFrames = PhysicalSpoolCellV1.entries.map { cell ->
            encryptedFile.copyOfRange(cell.offset, cell.offset + cell.length).withSpoolBytes(::sha256)
        }
        val initial = establishExisting()
        check(initial.observation == null && initial.pendingReply == null && initial.applicationReply == null)
    }

    internal fun establishExisting(): LocalPhysicalSpoolSnapshot {
        readAll()
        // A valid body surviving an uncertain previous force is forced again, not called durable
        // merely because a process can read page cache. This does not repair invalid ciphertext.
        file.force()
        val snapshot = readAll()
        verifyCurrentHeader()
        return snapshot
    }

    override fun toString(): String = "LocalPhysicalSpoolV1([REDACTED])"
}

private fun LocalPhysicalSpoolSnapshot.body(cell: PhysicalSpoolCellV1): StoredBytes? = when (cell) {
    PhysicalSpoolCellV1.OBSERVATION -> observation
    PhysicalSpoolCellV1.PENDING -> pendingReply
    PhysicalSpoolCellV1.APPLICATION -> applicationReply
    else -> error("physical.spool.immutable")
}

private inline fun validInput(block: () -> Unit): Boolean = try { block(); true } catch (_: IllegalArgumentException) { false }

/** Private native composition only. These ports are not exported or accepted as capacity proof. */
internal interface PhysicalSpoolFile {
    fun <T> access(block: () -> T): PhysicalSpoolAccess<T>
    fun initialize(bytes: ByteArray)
    fun read(offset: Int, size: Int): ByteArray
    fun overwrite(offset: Int, bytes: ByteArray)
    fun force()
    fun close(): LocalPhysicalSpoolClose
    /** Native-only owned lease. Denial is one-way; never called from a borrowed Room loan. */
    fun capture(onRetirement: () -> Unit): PhysicalSpoolLease? = null
}

internal interface PhysicalSpoolLease {
    /** Releases memory ownership only. Native close is a separate outside-transaction operation. */
    fun release()
}

/** Nullable cleanup ownership is assigned immediately after a single native capture. */
internal fun releasePhysicalSpoolLease(lease: PhysicalSpoolLease?): Boolean {
    if (lease == null) return true
    return try { lease.release(); true } catch (_: Exception) { false }
}

/**
 * A capture has not delivered its lease. Attempt both one-way denial and memory release,
 * including when an access-result constructor unwinds. Neither operation is native close.
 * Preserve an already propagating failure; otherwise propagate the first cleanup failure.
 * No allocation/fatal-error/process-survival guarantee or successful cleanup is inferred.
 */
internal fun cleanupUntransferredPhysicalSpoolCapture(lease: PhysicalSpoolLease?,
    onRetirement: () -> Unit, originalFailure: Throwable?) {
    var failure = originalFailure
    try { onRetirement() } catch (caught: Throwable) { if (failure == null) failure = caught }
    try { lease?.release() } catch (caught: Throwable) { if (failure == null) failure = caught }
    val cleanupFailure = failure
    if (originalFailure == null && cleanupFailure != null) throw cleanupFailure
}

/** Attempt every memory release; an unexpected failure is uncertainty, not successful cleanup. */
internal fun releasePhysicalSpoolLeases(leases: List<PhysicalSpoolLease>): Boolean {
    var complete = true
    // This cleanup also runs after return-wrapper/list construction has failed. Traversing
    // the already-owned, fixed list must not allocate an Iterator before the first release.
    var index = 0
    while (index < leases.size) {
        if (!releasePhysicalSpoolLease(leases[index++])) complete = false
    }
    return complete
}

internal sealed interface PhysicalSpoolAccess<out T> {
    class Value<T>(val value: T) : PhysicalSpoolAccess<T>
    data object Busy : PhysicalSpoolAccess<Nothing>
    data object Held : PhysicalSpoolAccess<Nothing>
}

internal interface PhysicalSpoolCipher {
    val identity: String
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(envelope: ByteArray, aad: ByteArray): ByteArray
}

internal fun createPhysicalSpool(binding: LocalPhysicalSpoolBinding, native: LocalPhysicalSpoolNativeIdentity,
    file: PhysicalSpoolFile, cipher: PhysicalSpoolCipher): LocalPhysicalSpoolOpen = when (val result = file.access {
    check(native.cryptographyIdentity == cipher.identity)
    val plan = PhysicalSpoolPlanV1(binding, native)
    val header = sealPhysicalSpoolCell(plan, cipher, PhysicalSpoolCellV1.HEADER, null, plan.bytes)
    val receipt = LocalPhysicalSpoolReceipt(binding, native, sha256(header))
    val entire = ByteArray(LocalPhysicalSpoolFormatV1.FILE_BYTES)
    try {
        header.copyInto(entire, PhysicalSpoolCellV1.HEADER.offset)
        val nonces = mutableSetOf(physicalSpoolNonceIdentity(header))
        for (cell in PhysicalSpoolCellV1.entries.filter { it != PhysicalSpoolCellV1.HEADER }) {
            sealPhysicalSpoolCell(plan, cipher, cell, receipt.headerDigest,
                if (cell == PhysicalSpoolCellV1.PADDING) ByteArray(0) else null).withSpoolBytes { encrypted ->
                check(nonces.add(physicalSpoolNonceIdentity(encrypted))) { "physical.spool.nonce_reuse" }
                encrypted.copyInto(entire, cell.offset)
            }
        }
        file.initialize(entire)
        LocalPhysicalSpool(receipt, file, cipher).also { it.establishNew(entire) }
    } finally {
        header.fill(0)
        entire.fill(0)
    }
}) {
    is PhysicalSpoolAccess.Value -> LocalPhysicalSpoolOpen.Opened(result.value)
    PhysicalSpoolAccess.Busy -> LocalPhysicalSpoolOpen.Busy
    PhysicalSpoolAccess.Held -> LocalPhysicalSpoolOpen.Held
}

internal fun openPhysicalSpool(receipt: LocalPhysicalSpoolReceipt, file: PhysicalSpoolFile,
    cipher: PhysicalSpoolCipher): LocalPhysicalSpoolOpen = when (val result = file.access {
    check(receipt.nativeIdentity.cryptographyIdentity == cipher.identity)
    LocalPhysicalSpool(receipt, file, cipher).also { it.establishExisting() }
}) {
    is PhysicalSpoolAccess.Value -> LocalPhysicalSpoolOpen.Opened(result.value)
    PhysicalSpoolAccess.Busy -> LocalPhysicalSpoolOpen.Busy
    PhysicalSpoolAccess.Held -> LocalPhysicalSpoolOpen.Held
}

private fun sealPhysicalSpoolCell(plan: PhysicalSpoolPlanV1, cipher: PhysicalSpoolCipher, cell: PhysicalSpoolCellV1,
    header: PayloadDigest?, body: ByteArray?): ByteArray = physicalSpoolPlaintext(cell, plan.requirementDigest, body).withSpoolBytes { plain ->
    physicalSpoolAad(plan, cell, header).withSpoolBytes { aad ->
        cipher.seal(plain, aad).also { check(it.size == cell.length) }
    }
}

private fun openPhysicalSpoolCell(plan: PhysicalSpoolPlanV1, cipher: PhysicalSpoolCipher, cell: PhysicalSpoolCellV1,
    header: PayloadDigest?, encrypted: ByteArray): ByteArray? {
    check(encrypted.size == cell.length)
    return physicalSpoolAad(plan, cell, header).withSpoolBytes { aad ->
        cipher.open(encrypted, aad).withSpoolBytes { plain -> physicalSpoolDecodePlaintext(cell, plan.requirementDigest, plain) }
    }
}

/** Shared legacy facade adapter; both platforms use the same actual native ownership transfer. */
internal fun legacySpoolFromNative(opening: PhysicalNativeOpenV1, binding: LocalPhysicalSpoolBinding,
    expected: LocalPhysicalSpoolReceipt?): LocalPhysicalSpoolOpen {
    if (opening is PhysicalNativeOpenV1.Busy) return LocalPhysicalSpoolOpen.Busy
    if (opening !is PhysicalNativeOpenV1.Opened) return LocalPhysicalSpoolOpen.Held
    val owned = opening.owned
    var transferred = false
    var answer: LocalPhysicalSpoolOpen = LocalPhysicalSpoolOpen.Held
    try {
        answer = if (expected == null) createPhysicalSpool(binding, owned.native, owned.file, owned.cipher)
            else openPhysicalSpool(expected, owned.file, owned.cipher)
        transferred = answer is LocalPhysicalSpoolOpen.Opened
    } catch (_: Exception) {
        answer = LocalPhysicalSpoolOpen.Held
    } finally {
        if (!transferred && owned.file.close() == LocalPhysicalSpoolClose.HELD) answer = LocalPhysicalSpoolOpen.Held
    }
    return answer
}

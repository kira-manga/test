@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.database

import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import dev.p2pkit.rpc.RpcCallObservation
import dev.p2pkit.rpc.RpcClient
import dev.p2pkit.rpc.RpcExecutionEvidence
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcReply
import dev.p2pkit.rpc.RpcRetry
import dev.p2pkit.rpc.RpcRetrySafety
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Additive staged protocol DATA. These descriptors are not installed by their declaration. */
public object LocalMasterPhysicalRpcProtocolV1 {
    public const val MAXIMUM_WIRE_BYTES: Int = 262_144
    public const val MAXIMUM_STAGE_BYTES: Int = 16_384
    public const val PUBLICATION_HANDOFF_MILLIS: Long = 1_000

    public val ORIGINAL: RpcProcedure<LocalMasterPhysicalOriginalRequestV1, LocalMasterPhysicalOriginalResponseV1,
        LocalMasterClientRpcErrorV1> = RpcProcedure(
            name = "pos.physical.original", version = 1,
            request = LocalMasterPhysicalOriginalRequestV1.serializer(), response = LocalMasterPhysicalOriginalResponseV1.serializer(),
            applicationError = LocalMasterClientRpcErrorV1.serializer(), retrySafety = RpcRetrySafety.NeverReinvoke,
            requestLimitBytes = MAXIMUM_WIRE_BYTES, responseLimitBytes = 4_096,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.ERROR_LIMIT_BYTES)

    public val INTERACTIVE:
        RpcProcedure<LocalMasterPhysicalRpcRequestV1, LocalMasterPhysicalRpcResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.physical.interactive", version = 1,
            request = LocalMasterPhysicalRpcRequestV1.serializer(),
            response = LocalMasterPhysicalRpcResponseV1.serializer(),
            applicationError = LocalMasterClientRpcErrorV1.serializer(),
            retrySafety = RpcRetrySafety.NeverReinvoke,
            requestLimitBytes = MAXIMUM_WIRE_BYTES, responseLimitBytes = MAXIMUM_WIRE_BYTES,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.ERROR_LIMIT_BYTES,
        )
}

/** Exact original command DATA. It never supplies an actor, admission, native owner or grant. */
@Serializable
public data class LocalMasterPhysicalStartRequestV1(
    public val operationId: OperationId,
    public val commandName: String,
    public val commandVersion: Int,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val payload: StoredBytes,
    public val actionApprovalId: DomainId? = null,
) {
    init { CommandKey(commandName, commandVersion) }
    public fun request(): MutationRequest = MutationRequest(operationId, CommandKey(commandName, commandVersion),
        payload, actionApprovalId)
    override fun toString(): String = "LocalMasterPhysicalStartRequestV1([REDACTED])"

    public companion object {
        public fun capture(request: MutationRequest): LocalMasterPhysicalStartRequestV1 =
            LocalMasterPhysicalStartRequestV1(request.operationId, request.command.name, request.command.version,
                StoredBytes(request.payload.copyBytes()), request.actionApprovalId)
    }
}

@Serializable
public sealed interface LocalMasterPhysicalRpcBodyV1 {
    @Serializable @SerialName("BEGIN")
    public data class Begin(public val original: LocalMasterPhysicalStartRequestV1,
        public val native: LocalMasterPhysicalClientNativeV1) : LocalMasterPhysicalRpcBodyV1

    @Serializable @SerialName("NEXT")
    public data class Next(public val invocationId: DomainId) : LocalMasterPhysicalRpcBodyV1

    @Serializable @SerialName("NATIVE_RESULT")
    public data class NativeResult(
        public val invocationId: DomainId,
        public val ordinal: Long,
        public val stage: LocalMasterPhysicalNativeStageV1,
        @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val result: StoredBytes,
    ) : LocalMasterPhysicalRpcBodyV1 {
        init { require(ordinal in 1..MAXIMUM_STAGE_ORDINAL && result.size in 1..LocalMasterPhysicalRpcProtocolV1.MAXIMUM_STAGE_BYTES) }
    }

    @Serializable @SerialName("PUBLISH")
    public data class Publish(public val invocationId: DomainId) : LocalMasterPhysicalRpcBodyV1

    @Serializable @SerialName("DENY")
    public data class Deny(public val invocationId: DomainId) : LocalMasterPhysicalRpcBodyV1
}

/** Wire stage classification; a received enum/value is never proof of native work. */
@Serializable
public enum class LocalMasterPhysicalNativeStageV1 {
    RESERVE_CLIENT, RECHECK_CLIENT, RETAIN_START, CONSUME_START, PUBLICATION,
    PENDING, HELD, UNCERTAIN, RECOVERY_ONLY, RETAIN_RESOLUTION,
}

@Serializable(with = LocalMasterPhysicalRpcRequestSerializerV1::class)
public class LocalMasterPhysicalRpcRequestV1 private constructor(
    access: ByteArray,
    public val body: LocalMasterPhysicalRpcBodyV1,
) : LocalMasterRpcOwnedRequestV1(access) {
    /** Full nonsecret binding across the SDK authorization and handler's separately decoded copy. */
    public val bindingDigest: PayloadDigest = physicalBodyDigest(body)

    public companion object {
        public fun capture(credentials: LocalGatewayCredentials, body: LocalMasterPhysicalRpcBodyV1): LocalMasterPhysicalRpcRequestV1 =
            credentials.bearer.useBytes { LocalMasterPhysicalRpcRequestV1(it, body) }

        internal fun decoded(access: ByteArray, body: LocalMasterPhysicalRpcBodyV1): LocalMasterPhysicalRpcRequestV1 =
            LocalMasterPhysicalRpcRequestV1(access, body)
    }
}

public object LocalMasterPhysicalRpcRequestSerializerV1 : KSerializer<LocalMasterPhysicalRpcRequestV1> by ownedRpcRequestSerializer(
    "com.ukkera.pos.database.LocalMasterPhysicalRpcRequestV1", "request", LocalMasterPhysicalRpcBodyV1.serializer(),
    body = { value: LocalMasterPhysicalRpcRequestV1 -> value.body },
    construct = { access, body -> LocalMasterPhysicalRpcRequestV1.decoded(access, body) },
)

/** Bounded projection DATA only. There is intentionally no Start/offer/lifetime decoder. */
@Serializable
public data class LocalMasterPhysicalRpcResponseV1(
    public val originalActorId: DomainId,
    public val operationId: OperationId,
    public val invocationId: DomainId,
    public val ordinal: Long,
    public val stage: LocalMasterPhysicalNativeStageV1,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val payload: StoredBytes,
    public val originalValidityMillis: Long? = null,
    public val recorded: LocalMasterRpcRecordedV1? = null,
    public val offerReady: Boolean = false,
) {
    init { require(originalValidityMillis == null || (stage == LocalMasterPhysicalNativeStageV1.PUBLICATION && originalValidityMillis > 0))
        require(ordinal in 0..MAXIMUM_STAGE_ORDINAL && payload.size <= LocalMasterPhysicalRpcProtocolV1.MAXIMUM_STAGE_BYTES) }
    override fun toString(): String = "LocalMasterPhysicalRpcResponseV1([REDACTED])"
}

/** Exact actual Master response custody, kept in process and never serialized.
 * A DTO or caller callback cannot construct its private native entry/publication link.
 * This only withholds stale output; it grants neither employee nor physical authority. */
public class LocalMasterPhysicalRpcDelivery internal constructor(
    public val response: LocalMasterPhysicalRpcResponseV1,
    internal val registry: LocalMasterRpcPhysicalRegistry,
    internal val origin: LocalMasterRpcPhysicalOrigin,
    internal val retainedEntry: Any,
    internal val publication: PhysicalPublishedInstruction?,
) {
    public suspend fun current(): Boolean = registry.deliveryCurrent(this)
    override fun toString(): String = "LocalMasterPhysicalRpcDelivery([REDACTED])"
}

/** Original Client native catalogue DATA. The concrete enrolled owner alone emits this value. */
@Serializable
public data class LocalMasterPhysicalClientNativeV1(
    public val installationId: DomainId,
    public val catalogueId: DomainId,
    public val keyId: DomainId,
    public val actorIntentJournalId: DomainId,
    public val storageProfile: String,
) {
    init {
        require(setOf(installationId, catalogueId, keyId, actorIntentJournalId).size == 4)
        require(storageProfile.length in 1..96 && storageProfile.all { it.isLetterOrDigit() || it == '_' })
    }
    override fun toString(): String = "LocalMasterPhysicalClientNativeV1([REDACTED])"
}

/**
 * One local attempt mark before Begin, spanning every network/native stage. Preparation
 * precedes the Master protected writer/publication window; it cannot consume that final
 * window in advance. The SAME earlier mark conservatively bounds the eventual original
 * event ceiling without synchronized clocks or renewal. It creates no native/core capability.
 */
public class LocalMasterPhysicalInvocationAttempt private constructor(
    private val client: LocalMasterPhysicalRpcClientV1,
    private val reservation: Any,
    private val began: kotlin.time.TimeMark,
) {
    public fun belongsTo(client: LocalMasterPhysicalRpcClientV1, reservation: Any): Boolean =
        this.client === client && this.reservation === reservation
    /** Eligibility only. Actual native/certificate/phase checks remain with their owners;
     * the Master alone enforces its unchanged protected writer-to-adoption deadline. */
    public fun preparationCurrent(): Boolean = client.isCurrent() && !began.elapsedNow().isNegative()
    /** Original ceiling only: this never renews the before-Begin mark after adoption. */
    public fun withinOriginalWindow(exclusiveMillis: Long): Boolean {
        val elapsed = began.elapsedNow()
        return exclusiveMillis > 0 && client.isCurrent() && !elapsed.isNegative() && elapsed < exclusiveMillis.milliseconds
    }
    internal companion object {
        internal fun capture(client: LocalMasterPhysicalRpcClientV1, reservation: Any): LocalMasterPhysicalInvocationAttempt =
            LocalMasterPhysicalInvocationAttempt(client, reservation, TimeSource.Monotonic.markNow())
    }
    override fun toString(): String = "LocalMasterPhysicalInvocationAttempt([REDACTED])"
}

/**
 * Actual original-Master response delivery, not decoded authority or remote OS-force proof.
 * Its private constructor is used only in this retained actual SDK Client's correlated response
 * callback. The original native reservation is the SAME local object supplied before the call.
 * A copied response DTO, certificate parser or fresh connection cannot recreate this delivery.
 */
public class LocalMasterPhysicalStageDelivery private constructor(
    private val client: LocalMasterPhysicalRpcClientV1,
    private val reservation: Any,
    public val response: LocalMasterPhysicalRpcResponseV1,
    private val attempt: LocalMasterPhysicalInvocationAttempt,
) {
    public fun belongsTo(client: LocalMasterPhysicalRpcClientV1, reservation: Any): Boolean =
        this.client === client && this.reservation === reservation && attempt.belongsTo(client, reservation)
    public fun belongsTo(attempt: LocalMasterPhysicalInvocationAttempt): Boolean = this.attempt === attempt

    /** The SAME before-Begin mark; a stage, retry or receive time cannot reset it.
     * Only a correlated actual SDK delivery after actual Master adoption supplies this DATA
     * ceiling. Decoded bytes never recreate the Master publication or native owner. */
    public fun currentForPublication(): Boolean =
        response.stage == LocalMasterPhysicalNativeStageV1.PUBLICATION &&
            response.originalValidityMillis?.let(attempt::withinOriginalWindow) == true

    /** Trusted scalar result only from this actual correlated SDK delivery; no physical cap. */
    public fun recordedResult(): MutationReply.Recorded? = response.recorded?.takeIf {
        client.isCurrent() && it.operationId == response.operationId && it.originalActorId == response.originalActorId
    }?.let { value -> MutationReply.Recorded(value.operationId, value.originalActorId, value.committed, value.code,
        StoredBytes(value.result.copyBytes()), value.authorityEpoch, value.sequence, value.recordedAt, value.replayed) }

    override fun toString(): String = "LocalMasterPhysicalStageDelivery([REDACTED])"

    internal companion object {
        internal fun received(client: LocalMasterPhysicalRpcClientV1, reservation: Any,
            response: LocalMasterPhysicalRpcResponseV1, attempt: LocalMasterPhysicalInvocationAttempt): LocalMasterPhysicalStageDelivery =
            LocalMasterPhysicalStageDelivery(client, reservation, response, attempt)
    }
}

/**
 * Derived only inside the existing retained LAN business bridge from its actual original SDK
 * Client/pin/lifetime. This supplies authenticated staged delivery, not native custody or an
 * employee grant. The concrete native owner must retain/consume its own original reservation.
 */
public class LocalMasterPhysicalRpcClientV1 private constructor(
    private val bridge: LocalMasterClientRpcBridgeV1,
    private val client: RpcClient,
    private val original: LocalDevicePairingSdkOriginal,
    private val approvedMaster: LocalDevicePairingApprovedMaster,
    private val lifetime: Job,
    public val connectionId: DomainId,
) {
    public val originalDeviceId: DomainId get() = approvedMaster.targetDeviceId
    public val scope: com.ukkera.pos.security.BranchScope get() = approvedMaster.scope
    public fun isCurrent(): Boolean = lifetime.isActive && bridge.isCurrent()

    /** Actual native composition checks the same original paired enrollment, not copied IDs. */
    public fun hasOriginal(original: LocalDevicePairingSdkOriginal,
        approvedMaster: LocalDevicePairingApprovedMaster): Boolean =
        samePhysicalOriginal(this.original, this.approvedMaster, original, approvedMaster)

    public fun sameNativeOriginalAs(other: LocalMasterPhysicalRpcClientV1): Boolean =
        samePhysicalOriginal(original, approvedMaster, other.original, other.approvedMaster)

    /** Immutable original delivery from the concrete retained native owner; no bearer or result. */
    public suspend fun retainOriginal(request: LocalMasterPhysicalOriginalRequestV1): LocalMasterPhysicalOriginalRetentionV1? {
        currentCoroutineContext().ensureActive()
        check(isCurrent())
        val observation = RpcCallObservation()
        val withheld = ReceiptWithheld()
        var received: LocalMasterPhysicalOriginalRetentionV1? = null
        try {
            client.callWithAcceptance(LocalMasterPhysicalRpcProtocolV1.ORIGINAL, request,
                observation = observation, retry = RpcRetry.RecoverOnly(maxAttempts = 1)) { offered ->
                currentCoroutineContext().ensureActive()
                val expected = approvedMaster.masterSdkOriginal.fingerprint
                val progress = observation.progress.value
                if (!isCurrent() || offered.hostFingerprint != expected || progress.peerFingerprint != expected.value ||
                    progress.requestId != offered.details.requestId.value ||
                    offered.procedureName != LocalMasterPhysicalRpcProtocolV1.ORIGINAL.name ||
                    offered.procedureVersion != LocalMasterPhysicalRpcProtocolV1.ORIGINAL.version ||
                    offered.hostIncarnation.isBlank() || progress.executionEvidence != RpcExecutionEvidence.HandlerFinished)
                    throw SerializationException("Original response binding differs")
                val response = (offered.details.reply as? RpcReply.Success)?.value ?: throw withheld
                if (response.clientAllocationId != request.clientAllocationId || response.originalDigest != sha256(request.originalBody.copyBytes()))
                    throw SerializationException("Original response identity differs")
                received = response.retention
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) throw withheld
            }
        } catch (failure: ReceiptWithheld) { if (failure !== withheld) throw failure }
        return received.takeIf { isCurrent() }
    }

    /** Correlation/time only, captured once by the concrete native reservation before Begin. */
    public fun newInvocationAttempt(nativeReservation: Any): LocalMasterPhysicalInvocationAttempt {
        check(isCurrent()) { "Original physical Client unavailable" }
        return LocalMasterPhysicalInvocationAttempt.capture(this, nativeReservation)
    }

    /**
     * The callback is a Receipt consumer, never authorization or proof of a remote force.
     * Production supplies its actual retained native owner here. Returning false withholds
     * Receipt without changing the original request identity or rerunning a handler.
     */
    public suspend fun exchange(credentials: LocalGatewayCredentials, body: LocalMasterPhysicalRpcBodyV1,
        originalActorId: DomainId, originalOperationId: OperationId, nativeReservation: Any,
        attempt: LocalMasterPhysicalInvocationAttempt,
        consume: suspend (LocalMasterPhysicalStageDelivery) -> Boolean): LocalMasterPhysicalStageDelivery? {
        currentCoroutineContext().ensureActive()
        check(credentials.connectionId == connectionId && isCurrent() && attempt.belongsTo(this, nativeReservation)) { "Original physical Client unavailable" }
        val request = LocalMasterPhysicalRpcRequestV1.capture(credentials, body)
        val observation = RpcCallObservation()
        val withheld = ReceiptWithheld()
        var retained: LocalMasterPhysicalStageDelivery? = null
        try {
            try {
                client.callWithAcceptance(LocalMasterPhysicalRpcProtocolV1.INTERACTIVE, request,
                    observation = observation, retry = RpcRetry.RecoverOnly(maxAttempts = 1)) { offered ->
                    currentCoroutineContext().ensureActive()
                    check(isCurrent()) { "Original physical Client unavailable" }
                    val expected = approvedMaster.masterSdkOriginal.fingerprint
                    val observed = observation.progress.value
                    if (observed.requestId != offered.details.requestId.value || observed.peerFingerprint != expected.value ||
                        offered.hostFingerprint != expected || offered.procedureName != LocalMasterPhysicalRpcProtocolV1.INTERACTIVE.name ||
                        offered.procedureVersion != LocalMasterPhysicalRpcProtocolV1.INTERACTIVE.version ||
                        offered.hostIncarnation.isBlank() || observed.executionEvidence != RpcExecutionEvidence.HandlerFinished)
                        throw SerializationException("Physical response binding differs")
                    val reply = offered.details.reply
                    if (reply !is RpcReply.Success) throw withheld
                    val response = reply.value
                    if (response.originalActorId != originalActorId || response.operationId != originalOperationId ||
                        body.invocationIdOrNull()?.let { it != response.invocationId } == true)
                        throw SerializationException("Physical response identity differs")
                    val delivery = LocalMasterPhysicalStageDelivery.received(this, nativeReservation, response, attempt)
                    retained = delivery
                    if (!consume(delivery)) throw withheld
                    currentCoroutineContext().ensureActive()
                    check(isCurrent()) { "Original physical Client unavailable" }
                }
            } catch (failure: ReceiptWithheld) { if (failure !== withheld) throw failure }
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "Original physical Client unavailable" }
            return retained
        } finally { request.destroy() }
    }

    override fun toString(): String = "LocalMasterPhysicalRpcClientV1([REDACTED])"
    private class ReceiptWithheld : Exception()

    internal companion object {
        internal fun bind(bridge: LocalMasterClientRpcBridgeV1, client: RpcClient,
            original: LocalDevicePairingSdkOriginal, approvedMaster: LocalDevicePairingApprovedMaster,
            lifetime: Job, connectionId: DomainId): LocalMasterPhysicalRpcClientV1 {
            check(bridge.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS && bridge.connectionId == connectionId &&
                bridge.isCurrent()) { "Original physical Client unavailable" }
            return LocalMasterPhysicalRpcClientV1(bridge, client, original, approvedMaster, lifetime, connectionId)
        }
    }
}

private fun physicalBodyDigest(body: LocalMasterPhysicalRpcBodyV1): PayloadDigest {
    val fields: List<ByteArray> = when (body) {
        is LocalMasterPhysicalRpcBodyV1.Begin -> listOf("BEGIN", body.original.operationId.value,
            body.original.commandName, body.original.commandVersion.toString(),
            body.original.actionApprovalId?.value ?: "", body.native.installationId.value, body.native.catalogueId.value,
            body.native.keyId.value, body.native.actorIntentJournalId.value, body.native.storageProfile)
            .map(String::encodeToByteArray) + listOf(body.original.payload.copyBytes())
        is LocalMasterPhysicalRpcBodyV1.Next -> listOf("NEXT", body.invocationId.value).map(String::encodeToByteArray)
        is LocalMasterPhysicalRpcBodyV1.NativeResult -> listOf("NATIVE_RESULT", body.invocationId.value,
            body.ordinal.toString(), body.stage.name).map(String::encodeToByteArray) + listOf(body.result.copyBytes())
        is LocalMasterPhysicalRpcBodyV1.Publish -> listOf("PUBLISH", body.invocationId.value).map(String::encodeToByteArray)
        is LocalMasterPhysicalRpcBodyV1.Deny -> listOf("DENY", body.invocationId.value).map(String::encodeToByteArray)
    }
    return sha256(frame(listOf("ukkera.lan-physical-rpc-request/v1".encodeToByteArray()) + fields))
}

private const val MAXIMUM_STAGE_ORDINAL: Long = 32

private fun LocalMasterPhysicalRpcBodyV1.invocationIdOrNull(): DomainId? = when (this) {
    is LocalMasterPhysicalRpcBodyV1.Begin -> null
    is LocalMasterPhysicalRpcBodyV1.Next -> invocationId
    is LocalMasterPhysicalRpcBodyV1.NativeResult -> invocationId
    is LocalMasterPhysicalRpcBodyV1.Publish -> invocationId
    is LocalMasterPhysicalRpcBodyV1.Deny -> invocationId
}

/** Native readbacks may be separate objects; compare the complete qualified original DATA. */
private fun samePhysicalOriginal(left: LocalDevicePairingSdkOriginal, leftMaster: LocalDevicePairingApprovedMaster,
    right: LocalDevicePairingSdkOriginal, rightMaster: LocalDevicePairingApprovedMaster): Boolean {
    if (left.exactDeviceName != right.exactDeviceName || leftMaster.scope != rightMaster.scope ||
        leftMaster.targetDeviceId != rightMaster.targetDeviceId || leftMaster.targetKeyId != rightMaster.targetKeyId ||
        leftMaster.originalPairingActionId != rightMaster.originalPairingActionId ||
        leftMaster.originalRequestId != rightMaster.originalRequestId || leftMaster.rootId != rightMaster.rootId ||
        leftMaster.rootKeyId != rightMaster.rootKeyId || leftMaster.foundingActionId != rightMaster.foundingActionId ||
        leftMaster.rootFingerprint != rightMaster.rootFingerprint || leftMaster.rootDocumentDigest != rightMaster.rootDocumentDigest ||
        leftMaster.approvalCertificateDigest != rightMaster.approvalCertificateDigest ||
        leftMaster.challengeDigest != rightMaster.challengeDigest || leftMaster.possessionDigest != rightMaster.possessionDigest) return false
    fun same(copyLeft: ByteArray, copyRight: ByteArray): Boolean = try { copyLeft.contentEquals(copyRight) }
        finally { copyLeft.fill(0); copyRight.fill(0) }
    return same(left.identity.copyWireBytes(), right.identity.copyWireBytes()) &&
        same(leftMaster.targetSdkIdentity.copyWireBytes(), rightMaster.targetSdkIdentity.copyWireBytes()) &&
        same(leftMaster.masterSdkOriginal.copyWireBytes(), rightMaster.masterSdkOriginal.copyWireBytes())
}

/** Inert immutable original DATA; only the original enrolled device route can deliver it. */
@Serializable
public data class LocalMasterPhysicalOriginalRequestV1(
    public val clientAllocationId: DomainId,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val canonicalRequirement: StoredBytes,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val certificate: StoredBytes,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val clientSource: StoredBytes,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val originalBody: StoredBytes,
) {
    init { require(canonicalRequirement.size in 1..8_192 && certificate.size in 1..16_384 &&
        clientSource.size in 1..16_384 && originalBody.size in 1..8_192) }
    public val bindingDigest: PayloadDigest get() = sha256(frame(listOf(
        "ukkera.physical.remote-original-request/v1".encodeToByteArray(), clientAllocationId.value.encodeToByteArray(),
        canonicalRequirement.copyBytes(), certificate.copyBytes(), clientSource.copyBytes(), originalBody.copyBytes())))
    override fun toString(): String = "LocalMasterPhysicalOriginalRequestV1([REDACTED])"
}
@Serializable
public enum class LocalMasterPhysicalOriginalRetentionV1 { PENDING, HELD, UNCERTAIN, MASTER_ORIGINAL_RETAINED }
@Serializable
public data class LocalMasterPhysicalOriginalResponseV1(public val clientAllocationId: DomainId,
    public val originalDigest: PayloadDigest, public val retention: LocalMasterPhysicalOriginalRetentionV1) {
    override fun toString(): String = "LocalMasterPhysicalOriginalResponseV1([REDACTED])"
}
internal fun physicalNativeDigest(value: LocalMasterPhysicalClientNativeV1): PayloadDigest = sha256(frame(listOf(
    "ukkera.physical.remote-native-data/v1", value.installationId.value, value.catalogueId.value,
    value.keyId.value, value.actorIntentJournalId.value, value.storageProfile).map(String::encodeToByteArray)))

package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Exact old issued bytes plus the current cancelling requester at the actual native before cut. */
@Serializable
internal data class RuntimeActionApprovalRetirementEvidenceV1(
    val schema: String = PROFILE,
    val commandName: String,
    val commandVersion: Int,
    val approvalId: DomainId,
    val approval: String,
    val requestor: RuntimeCommandAuthorizationSnapshotV1,
    val nativeRecordDigest: PayloadDigest,
    @Serializable(with = MinorUnitsSerializer::class) val beforeHistorySequence: Long,
    val beforeHistoryDigest: PayloadDigest,
    val beforeCommandEpoch: Revision,
    val beforeCommandSequence: JournalSequence,
    val beforeCommandDigest: PayloadDigest,
    val images: List<RuntimeApprovalImageV1>,
) {
    fun value(): ProtectedLocalActionApproval {
        val bytes = approval.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            val value = LocalActionApprovalCodec.decode(ProtectedLocalBinding(
                ProtectedLocalRecordKey.ActionApproval(requestor.scope, approvalId), Revision(0)), bytes)
            val canonical = LocalActionApprovalCodec.encode(value)
            try { require(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
            value
        }
        finally { bytes.fill(0) }
    }
    fun event(): LocalAuthEvent = actionApprovalRetirementEvent(value(), requestor)
    fun retirementId(): DomainId = actionApprovalRetirementId(requestor.scope, approvalId)
    fun validate() {
        require(schema == PROFILE && beforeHistorySequence > 1)
        CommandKey(commandName, commandVersion); requestor.validate()
        val value = value()
        require(value.hasApproverIncarnation && value.record.requestorId == requestor.actorId &&
            value.record.requirement.scope == requestor.scope && value.record.revision == Revision(0) &&
            value.record.consumedByOperationId == null && value.record.requirement.binding?.operationId != null)
        val event = event()
        require(images == listOf(RuntimeApprovalImageV1.capture(ProtectedLocalRecordKey.AuthEvent(requestor.scope,
            event.eventId), Revision(0), LocalAuthenticationCodec.encodeEvent(event))))
    }
    /** Cancelling uses the CURRENT requester's native access window, never the old AP's expiry. */
    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validate()
        return NativeHistoryProtectedFinalDeclaration(retirementId(), runtimeApprovalDigest(value()), requestor.actorId,
            requestor.sessionId, requestor.access.issuerContextId, requestor.access.issuedAt,
            requestor.access.observedAt, requestor.access.expiresAtElapsedMillis)
    }
    override fun toString(): String = "RuntimeActionApprovalRetirementEvidenceV1([REDACTED])"
    companion object { const val PROFILE: String = "ukkera.runtime-action-approval-retirement/v1" }
}

/** Produced only after the actual kernel has proved the exact original operation absent. */
internal class RuntimeActionApprovalRetirementCapture(
    val command: CommandKey,
    val approval: ProtectedLocalActionApproval,
    val requestor: RuntimeCommandAuthorizationSnapshotV1,
) {
    val images = mutableListOf<RuntimeApprovalImageV1>()
    fun event(): LocalAuthEvent = actionApprovalRetirementEvent(approval, requestor)
    fun evidence(nativeRecordDigest: PayloadDigest, before: NativeHistoryRecordedHeadV2): RuntimeActionApprovalRetirementEvidenceV1 =
        RuntimeActionApprovalRetirementEvidenceV1(commandName = command.name, commandVersion = command.version,
            approvalId = approval.record.approvalId, approval = runtimeApprovalText(approval), requestor = requestor,
            nativeRecordDigest = nativeRecordDigest, beforeHistorySequence = before.historySequence,
            beforeHistoryDigest = before.historyDigest, beforeCommandEpoch = Revision(before.commandEpoch),
            beforeCommandSequence = JournalSequence(before.commandSequence), beforeCommandDigest = before.commandDigest,
            images = images.toList()).also { it.validate() }
}

@Serializable
private data class RuntimeActionApprovalRetirementDependenciesV1(
    val requestor: RuntimeCommandAuthorizationDependenciesV1,
    val originalApprovalDigest: PayloadDigest,
    @Serializable(with = MinorUnitsSerializer::class) val beforeHistorySequence: Long,
    val beforeHistoryDigest: PayloadDigest,
)

internal object RuntimeActionApprovalRetirementEvidenceCodec {
    private const val MAX_BYTES = 64 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeActionApprovalRetirementEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeActionApprovalRetirementEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeActionApprovalRetirementEvidenceV1.serializer(), value).encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }
    fun decode(bytes: ByteArray): RuntimeActionApprovalRetirementEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeActionApprovalRetirementEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }
    fun dependencies(value: RuntimeActionApprovalRetirementEvidenceV1): ByteArray = guarded {
        value.validate()
        json.encodeToString(RuntimeActionApprovalRetirementDependenciesV1.serializer(), RuntimeActionApprovalRetirementDependenciesV1(
            value.requestor.dependencies, runtimeApprovalDigest(value.value()), value.beforeHistorySequence, value.beforeHistoryDigest))
            .encodeToByteArray(throwOnInvalidSequence = true)
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}

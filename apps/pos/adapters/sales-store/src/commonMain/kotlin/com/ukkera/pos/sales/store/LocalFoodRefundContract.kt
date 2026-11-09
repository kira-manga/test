@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Distinct actual-tender FOOD payout, never claim qualification, stock return or a retail alias. */
public object LocalFoodRefundProfile {
    public val COMMAND: CommandKey = CommandKey("sales.food.refund", 1)
    public const val DOMAIN: String = "sales.food-refund/v1"
    public const val SLOT: String = "sales.food.refund-slot"
    public const val ORIGINAL: String = "sales.food.refund-original"
    public const val RESOLUTION: String = "sales.food.refund-resolution"
    public const val APPLICATION: String = "sales.food.refund-application"
    public const val EVENT: String = "sales.food.refund-event"
    public const val OPERATION_CLAIM: String = "sales.food.refund-operation.v1"
    public const val ALLOCATION_CLAIM: String = "sales.food.refund-allocation.v1"
    public const val ATTEMPT_CLAIM: String = "sales.food.refund-attempt.v1"
    public const val MAX_REQUEST_BYTES: Int = 8_192
    public const val MAX_RESULT_BYTES: Int = 16_384
    public const val MAX_DOMAIN_BYTES: Int = 6_144
    public const val MAX_SLOT_BYTES: Int = 16_384
    public const val MAX_RAW_BYTES: Int = 24_576
    public const val MAX_APPLICATION_BYTES: Int = 8_192
    public const val MAX_FINANCIAL_RESOURCES: Int = 64
    public val gatedKinds: Set<String> = setOf(PhysicalRecordingSourceProfileV1.KIND, SLOT, ORIGINAL, RESOLUTION)
    public val gatedClaims: Set<String> = setOf(OPERATION_CLAIM, ALLOCATION_CLAIM, ATTEMPT_CLAIM)
    public fun schema(kind: String): String = "$kind/v1"
}

/** Complete immutable original lineage. A absent sale is a real part-paid cancellation, not a guessed sale. */
@Serializable
public data class LocalFoodRefundTarget(
    public val orderId: DomainId,
    public val collectionId: DomainId,
    public val refundId: DomainId,
    public val saleId: DomainId?,
    public val originalPaymentId: DomainId,
    public val sessionId: DomainId,
    public val attemptId: DomainId,
    public val intentOperationId: OperationId,
    public val recordingOperationId: OperationId,
    public val resolutionOperationId: OperationId,
) {
    init { require(setOf(intentOperationId, recordingOperationId, resolutionOperationId).size == 3) }
}

/** Raw actual observation. A wrong amount/tender is retained as reconciliation, never changed to NOT_PAID. */
@Serializable
public sealed interface LocalFoodRefundOutcome {
    @Serializable @SerialName("UNKNOWN")
    public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalFoodRefundOutcome
    @Serializable @SerialName("NOT_PAID")
    public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalFoodRefundOutcome
    @Serializable @SerialName("CASH_PAID")
    public data class CashPaid(public val amount: Money, public val evidence: LocalCashEvidenceV1) : LocalFoodRefundOutcome
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1,
        public val terminal: String?, public val reference: String?) : LocalFoodRefundOutcome
}

/** Fixed original CASH payout/UNKNOWN resolution and SQL accounting; no new physical money on retry. */
@Serializable
public sealed interface LocalFoodRefundRequest {
    @Serializable @SerialName("START_CASH")
    public data class StartCash(
        public val refundId: DomainId,
        public val expectedRefundRevision: Revision,
        public val sessionId: DomainId,
        public val expectedSessionRevision: Revision,
        public val attemptId: DomainId,
        public val originalPaymentId: DomainId,
        public val amount: Money,
        public val recordingOperationId: OperationId,
        public val resolutionOperationId: OperationId,
        public val masterAllocationId: DomainId,
        public val clientAllocationId: DomainId,
    ) : LocalFoodRefundRequest
    @Serializable @SerialName("RECORD_ORIGINAL")
    public data class RecordOriginal(public val target: LocalFoodRefundTarget,
        public val outcome: LocalFoodRefundOutcome) : LocalFoodRefundRequest
    @Serializable @SerialName("RECORD_RESOLUTION")
    public data class RecordResolution(public val target: LocalFoodRefundTarget,
        public val originalBodyDigest: PayloadDigest, public val resolverActorId: DomainId,
        public val outcome: LocalFoodRefundOutcome) : LocalFoodRefundRequest
    @Serializable @SerialName("APPLY_ORIGINAL")
    public data class ApplyOriginal(public val recordingOperationId: OperationId) : LocalFoodRefundRequest
    @Serializable @SerialName("APPLY_RESOLUTION")
    public data class ApplyResolution(public val resolutionOperationId: OperationId) : LocalFoodRefundRequest
}

@Serializable public enum class LocalFoodRefundFactPhase { ORIGINAL, RESOLUTION }
@Serializable public enum class LocalFoodRefundAction { CASH_PAID, NOT_PAID, UNKNOWN }
@Serializable public enum class LocalFoodRefundPendingReason {
    AWAITING_FINANCIAL_APPLICATION, SOURCE_UNAVAILABLE, ORIGINAL_ATTEMPT_MISMATCH,
    CURRENT_CUSTODY_REQUIRES_RECONCILIATION, VALUE_OR_TENDER_MISMATCH, STORAGE_CAPACITY_REQUIRED,
    ORIGINAL_APPLICATION_REQUIRED,
}
@Serializable
public sealed interface LocalFoodRefundResult {
    @Serializable @SerialName("ATTEMPT_STARTED")
    public data class AttemptStarted(public val target: LocalFoodRefundTarget, public val amount: Money,
        public val requirementDigest: PayloadDigest,
        public val revisions: List<LocalFoodStorageRevisionV1>) : LocalFoodRefundResult
    @Serializable @SerialName("RAW_RETAINED")
    public data class RawRetained(public val target: LocalFoodRefundTarget,
        public val reason: LocalFoodRefundPendingReason = LocalFoodRefundPendingReason.AWAITING_FINANCIAL_APPLICATION,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodRefundFactPhase = LocalFoodRefundFactPhase.ORIGINAL) : LocalFoodRefundResult
    @Serializable @SerialName("APPLIED")
    public data class Applied(public val target: LocalFoodRefundTarget, public val action: LocalFoodRefundAction,
        public val applicationOperationId: OperationId, public val revisions: List<LocalFoodStorageRevisionV1>,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodRefundFactPhase = LocalFoodRefundFactPhase.ORIGINAL) : LocalFoodRefundResult
    @Serializable @SerialName("PENDING_RECONCILIATION")
    public data class Pending(public val target: LocalFoodRefundTarget, public val reason: LocalFoodRefundPendingReason,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodRefundFactPhase = LocalFoodRefundFactPhase.ORIGINAL) : LocalFoodRefundResult
    @Serializable @SerialName("ALREADY_APPLIED")
    public data class AlreadyApplied(public val recordingOperationId: OperationId, public val applicationOperationId: OperationId,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodRefundFactPhase = LocalFoodRefundFactPhase.ORIGINAL) : LocalFoodRefundResult
    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(public val code: String) : LocalFoodRefundResult
}

public object LocalFoodRefundCodec {
    public fun command(operationId: OperationId, request: LocalFoodRefundRequest): MutationRequest =
        MutationRequest(operationId, LocalFoodRefundProfile.COMMAND, encode(request))
    public fun encode(request: LocalFoodRefundRequest): StoredBytes {
        when (request) {
            is LocalFoodRefundRequest.StartCash -> require(request.amount.currency == Currency.EGP && request.amount.minor > 0L &&
                request.recordingOperationId != request.resolutionOperationId && request.masterAllocationId != request.clientAllocationId)
            is LocalFoodRefundRequest.RecordOriginal -> foodRefundValidateOutcome(request.outcome)
            is LocalFoodRefundRequest.RecordResolution -> {
                require(request.outcome !is LocalFoodRefundOutcome.Unknown)
                foodRefundValidateOutcome(request.outcome)
            }
            is LocalFoodRefundRequest.ApplyOriginal, is LocalFoodRefundRequest.ApplyResolution -> Unit
        }
        return localSalesEncode(request, LocalFoodRefundRequest.serializer(), LocalFoodRefundProfile.MAX_REQUEST_BYTES)
    }
    public fun decode(bytes: StoredBytes): LocalFoodRefundRequest = localSalesDecode(bytes,
        LocalFoodRefundRequest.serializer(), LocalFoodRefundProfile.MAX_REQUEST_BYTES)
    public fun normalize(bytes: StoredBytes): StoredBytes = encode(decode(bytes))
    public fun result(bytes: StoredBytes): LocalFoodRefundResult = localSalesDecode(bytes,
        LocalFoodRefundResult.serializer(), LocalFoodRefundProfile.MAX_RESULT_BYTES)
    public fun result(value: LocalFoodRefundResult): StoredBytes = localSalesEncode(value,
        LocalFoodRefundResult.serializer(), LocalFoodRefundProfile.MAX_RESULT_BYTES)
}

internal fun LocalFoodRefundRequest.StartCash.target(refund: com.ukkera.pos.sales.FoodRefundObligation,
    operationId: OperationId): LocalFoodRefundTarget = LocalFoodRefundTarget(refund.orderId, refund.originalCollection.id,
        refund.id, refund.saleId, originalPaymentId, sessionId, attemptId, operationId, recordingOperationId, resolutionOperationId)
internal fun foodRefundSlotKey(operationId: OperationId): AggregateKey = AggregateKey(LocalFoodRefundProfile.SLOT, DomainId(operationId.value))
internal fun foodRefundRawKey(operationId: OperationId): AggregateKey = AggregateKey(LocalFoodRefundProfile.ORIGINAL, DomainId(operationId.value))
internal fun foodRefundResolutionKey(operationId: OperationId): AggregateKey = AggregateKey(LocalFoodRefundProfile.RESOLUTION, DomainId(operationId.value))
internal fun foodRefundApplicationKey(operationId: OperationId): AggregateKey = AggregateKey(LocalFoodRefundProfile.APPLICATION, DomainId(operationId.value))
internal fun foodRefundOperationClaim(operationId: OperationId): ClaimKey = ClaimKey(LocalFoodRefundProfile.OPERATION_CLAIM, operationId.value)
internal fun foodRefundAllocationClaim(id: DomainId): ClaimKey = ClaimKey(LocalFoodRefundProfile.ALLOCATION_CLAIM, id.value)
internal fun foodRefundAttemptClaim(id: DomainId): ClaimKey = ClaimKey(LocalFoodRefundProfile.ATTEMPT_CLAIM, id.value)
internal fun foodRefundTargetBinding(target: LocalFoodRefundTarget): PayloadDigest = sha256(journalFrame(listOf(
    "sales.food-refund-target/v1", target.orderId.value, target.collectionId.value, target.refundId.value,
    target.saleId?.value ?: "", target.originalPaymentId.value, target.sessionId.value, target.attemptId.value,
    target.intentOperationId.value, target.recordingOperationId.value, target.resolutionOperationId.value).map(::journalText)))
internal fun foodRefundFenceBinding(target: LocalFoodRefundTarget, resolution: Boolean = false): PayloadDigest = sha256(journalFrame(listOf(
    "sales.food-refund-future/v1", foodRefundTargetBinding(target).value, if (resolution) "RESOLUTION" else "ORIGINAL",
    (if (resolution) target.resolutionOperationId else target.recordingOperationId).value).map(::journalText)))
internal fun LocalFoodRefundTarget.factOperation(phase: LocalFoodRefundFactPhase): OperationId =
    if (phase == LocalFoodRefundFactPhase.ORIGINAL) recordingOperationId else resolutionOperationId
internal fun foodRefundValidateOutcome(outcome: LocalFoodRefundOutcome): Unit = when (outcome) {
    is LocalFoodRefundOutcome.CashPaid -> require(outcome.amount.minor >= 0L)
    is LocalFoodRefundOutcome.NotPaid -> localReason(outcome.reason)
    is LocalFoodRefundOutcome.ExternalAttested -> require(listOf(outcome.terminal, outcome.reference).all { it == null || it.length <= 128 })
    is LocalFoodRefundOutcome.Unknown -> Unit
}

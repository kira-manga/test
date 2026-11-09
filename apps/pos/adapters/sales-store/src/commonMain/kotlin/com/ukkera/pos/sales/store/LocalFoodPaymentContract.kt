@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Distinct genuine FOOD CASH command. Neither a retail profile nor a physical streamed route. */
public object LocalFoodPaymentProfile {
    public val COMMAND: CommandKey = CommandKey("sales.food.payment", 1)
    public const val DOMAIN: String = "sales.food-payment/v1"
    public const val SLOT: String = "sales.food.payment-slot"
    public const val ORIGINAL: String = "sales.food.payment-original"
    public const val RESOLUTION: String = "sales.food.payment-resolution"
    public const val APPLICATION: String = "sales.food.payment-application"
    public const val EVENT: String = "sales.food.payment-event"
    public const val OPERATION_CLAIM: String = "sales.food.payment-operation.v1"
    public const val ALLOCATION_CLAIM: String = "sales.food.payment-allocation.v1"
    public const val ATTEMPT_CLAIM: String = "sales.food.payment-attempt.v1"
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

/** Complete immutable link; IDs are selectors, never custody, capacity or authorization. */
@Serializable
public data class LocalFoodPaymentTarget(
    public val orderId: DomainId,
    public val collectionId: DomainId,
    public val sessionId: DomainId,
    public val attemptId: DomainId,
    public val intentOperationId: OperationId,
    public val recordingOperationId: OperationId,
    /** Reserved once with Start; immutable UNKNOWN may retain one linked known resolution. */
    public val resolutionOperationId: OperationId,
) {
    init { require(setOf(intentOperationId, recordingOperationId, resolutionOperationId).size == 3) }
}

/** Exact bounded raw evidence. A value/tender mismatch is retained, not changed to NOT_PAID. */
@Serializable
public sealed interface LocalFoodCashOutcome {
    @Serializable @SerialName("UNKNOWN")
    public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalFoodCashOutcome
    @Serializable @SerialName("NOT_PAID")
    public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalFoodCashOutcome
    @Serializable @SerialName("CASH_RECEIVED")
    public data class CashReceived(public val received: Money, public val change: Money,
        public val evidence: LocalCashEvidenceV1) : LocalFoodCashOutcome
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1,
        public val terminal: String?, public val reference: String?) : LocalFoodCashOutcome
}

/**
 * FOOD-only Start, exact raw original/linked UNKNOWN retention and SQL accounting.
 * No new charge/physical retry, stock issue/release, tender substitution or retail alias.
 * Initial physical custody still requires wholly NOT_SENT and genuine UNKNOWN reservations.
 */
@Serializable
public sealed interface LocalFoodPaymentRequest {
    @Serializable @SerialName("START_CASH")
    public data class StartCash(
        public val collectionId: DomainId,
        /** Preparation ORDER storage generation, not a collection domain revision. */
        public val expectedOrderStorageRevision: Revision,
        public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId,
        public val expectedSessionRevision: Revision,
        public val attemptId: DomainId,
        public val amount: Money,
        public val payerReference: String? = null,
        public val recordingOperationId: OperationId,
        public val resolutionOperationId: OperationId,
        public val masterAllocationId: DomainId,
        public val clientAllocationId: DomainId,
    ) : LocalFoodPaymentRequest

    @Serializable @SerialName("RECORD_ORIGINAL")
    public data class RecordOriginal(public val target: LocalFoodPaymentTarget,
        public val outcome: LocalFoodCashOutcome) : LocalFoodPaymentRequest

    @Serializable @SerialName("RECORD_RESOLUTION")
    public data class RecordResolution(public val target: LocalFoodPaymentTarget,
        public val originalBodyDigest: PayloadDigest, public val resolverActorId: DomainId,
        public val outcome: LocalFoodCashOutcome) : LocalFoodPaymentRequest

    @Serializable @SerialName("APPLY_ORIGINAL")
    public data class ApplyOriginal(public val recordingOperationId: OperationId) : LocalFoodPaymentRequest

    @Serializable @SerialName("APPLY_RESOLUTION")
    public data class ApplyResolution(public val resolutionOperationId: OperationId) : LocalFoodPaymentRequest
}

@Serializable
public enum class LocalFoodPaymentFactPhase { ORIGINAL, RESOLUTION }

@Serializable
public enum class LocalFoodPaymentAction { CASH_PAID, NOT_PAID, UNKNOWN }

@Serializable
public enum class LocalFoodPaymentPendingReason {
    AWAITING_FINANCIAL_APPLICATION, SOURCE_UNAVAILABLE, ORIGINAL_ATTEMPT_MISMATCH,
    CURRENT_CUSTODY_REQUIRES_RECONCILIATION, VALUE_OR_TENDER_MISMATCH, STORAGE_CAPACITY_REQUIRED,
    ORIGINAL_APPLICATION_REQUIRED,
}

@Serializable
public sealed interface LocalFoodPaymentResult {
    @Serializable @SerialName("ATTEMPT_STARTED")
    public data class AttemptStarted(public val target: LocalFoodPaymentTarget, public val amount: Money,
        public val requirementDigest: PayloadDigest, public val mustCommitBefore: UtcInstant?,
        public val revisions: List<LocalFoodStorageRevisionV1>) : LocalFoodPaymentResult
    @Serializable @SerialName("RAW_RETAINED")
    public data class RawRetained(public val target: LocalFoodPaymentTarget,
        public val reason: LocalFoodPaymentPendingReason = LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodPaymentFactPhase = LocalFoodPaymentFactPhase.ORIGINAL) : LocalFoodPaymentResult
    @Serializable @SerialName("APPLIED")
    public data class Applied(public val target: LocalFoodPaymentTarget, public val action: LocalFoodPaymentAction,
        public val applicationOperationId: OperationId,
        public val revisions: List<LocalFoodStorageRevisionV1>,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodPaymentFactPhase = LocalFoodPaymentFactPhase.ORIGINAL) : LocalFoodPaymentResult
    @Serializable @SerialName("PENDING_RECONCILIATION")
    public data class Pending(public val target: LocalFoodPaymentTarget,
        public val reason: LocalFoodPaymentPendingReason,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodPaymentFactPhase = LocalFoodPaymentFactPhase.ORIGINAL) : LocalFoodPaymentResult
    @Serializable @SerialName("ALREADY_APPLIED")
    public data class AlreadyApplied(public val recordingOperationId: OperationId,
        public val applicationOperationId: OperationId,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val phase: LocalFoodPaymentFactPhase = LocalFoodPaymentFactPhase.ORIGINAL) : LocalFoodPaymentResult
    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(public val code: String) : LocalFoodPaymentResult
}

public object LocalFoodPaymentCodec {
    public fun command(operationId: OperationId, request: LocalFoodPaymentRequest): MutationRequest =
        MutationRequest(operationId, LocalFoodPaymentProfile.COMMAND, encode(request))
    public fun encode(request: LocalFoodPaymentRequest): StoredBytes {
        when (request) {
            is LocalFoodPaymentRequest.StartCash -> {
                require(request.amount.currency == Currency.EGP && request.amount.minor > 0L)
                require(request.recordingOperationId != request.resolutionOperationId &&
                    request.masterAllocationId != request.clientAllocationId)
                request.payerReference?.let { require(it.isNotBlank() && it.length <= 128) }
            }
            is LocalFoodPaymentRequest.RecordOriginal -> foodPaymentValidateOutcome(request.outcome)
            is LocalFoodPaymentRequest.RecordResolution -> {
                require(request.outcome !is LocalFoodCashOutcome.Unknown)
                foodPaymentValidateOutcome(request.outcome)
            }
            is LocalFoodPaymentRequest.ApplyOriginal, is LocalFoodPaymentRequest.ApplyResolution -> Unit
        }
        return localSalesEncode(request, LocalFoodPaymentRequest.serializer(), LocalFoodPaymentProfile.MAX_REQUEST_BYTES)
    }
    public fun decode(bytes: StoredBytes): LocalFoodPaymentRequest = localSalesDecode(bytes,
        LocalFoodPaymentRequest.serializer(), LocalFoodPaymentProfile.MAX_REQUEST_BYTES)
    public fun normalize(bytes: StoredBytes): StoredBytes = encode(decode(bytes))
    public fun result(bytes: StoredBytes): LocalFoodPaymentResult = localSalesDecode(bytes,
        LocalFoodPaymentResult.serializer(), LocalFoodPaymentProfile.MAX_RESULT_BYTES)
    public fun result(value: LocalFoodPaymentResult): StoredBytes = localSalesEncode(value,
        LocalFoodPaymentResult.serializer(), LocalFoodPaymentProfile.MAX_RESULT_BYTES)
}

internal fun LocalFoodPaymentRequest.StartCash.target(orderId: DomainId, operationId: OperationId): LocalFoodPaymentTarget =
    LocalFoodPaymentTarget(orderId, collectionId, sessionId, attemptId, operationId, recordingOperationId, resolutionOperationId)
internal fun foodPaymentSlotKey(operationId: OperationId): AggregateKey =
    AggregateKey(LocalFoodPaymentProfile.SLOT, DomainId(operationId.value))
internal fun foodPaymentRawKey(operationId: OperationId): AggregateKey =
    AggregateKey(LocalFoodPaymentProfile.ORIGINAL, DomainId(operationId.value))
internal fun foodPaymentApplicationKey(operationId: OperationId): AggregateKey =
    AggregateKey(LocalFoodPaymentProfile.APPLICATION, DomainId(operationId.value))
internal fun foodPaymentOperationClaim(operationId: OperationId): ClaimKey =
    ClaimKey(LocalFoodPaymentProfile.OPERATION_CLAIM, operationId.value)
internal fun foodPaymentAllocationClaim(id: DomainId): ClaimKey = ClaimKey(LocalFoodPaymentProfile.ALLOCATION_CLAIM, id.value)
internal fun foodPaymentAttemptClaim(id: DomainId): ClaimKey = ClaimKey(LocalFoodPaymentProfile.ATTEMPT_CLAIM, id.value)
internal fun foodPaymentTargetBinding(target: LocalFoodPaymentTarget): PayloadDigest = sha256(journalFrame(listOf(
    "sales.food-payment-target/v1", target.orderId.value, target.collectionId.value, target.sessionId.value,
    target.attemptId.value, target.intentOperationId.value, target.recordingOperationId.value,
    target.resolutionOperationId.value).map(::journalText)))
internal fun foodPaymentFenceBinding(target: LocalFoodPaymentTarget, resolution: Boolean = false): PayloadDigest =
    sha256(journalFrame(listOf("sales.food-payment-future/v1", foodPaymentTargetBinding(target).value,
        if (resolution) "RESOLUTION" else "ORIGINAL",
        (if (resolution) target.resolutionOperationId else target.recordingOperationId).value).map(::journalText)))

internal fun foodPaymentValidateOutcome(outcome: LocalFoodCashOutcome): Unit = when (outcome) {
    is LocalFoodCashOutcome.CashReceived -> require(outcome.received.minor >= 0L && outcome.change.minor >= 0L)
    is LocalFoodCashOutcome.NotPaid -> localReason(outcome.reason)
    is LocalFoodCashOutcome.ExternalAttested -> require(listOf(outcome.terminal, outcome.reference).all {
        it == null || it.length <= 128
    })
    is LocalFoodCashOutcome.Unknown -> Unit
}
internal fun LocalFoodPaymentTarget.factOperation(phase: LocalFoodPaymentFactPhase): OperationId =
    if (phase == LocalFoodPaymentFactPhase.ORIGINAL) recordingOperationId else resolutionOperationId
internal fun foodPaymentResolutionKey(operationId: OperationId): AggregateKey =
    AggregateKey(LocalFoodPaymentProfile.RESOLUTION, DomainId(operationId.value))

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** New food-specific command/source profile, never an alias of a retail collection or return. */
public object LocalFoodFinancialProfileV1 {
    public val COMMAND: CommandKey = CommandKey("sales.food.financial", 1)
    public val QUERY: ReadKey = ReadKey("sales.food.financial.read", 1)
    public const val COLLECTION: String = "sales.food.collection"
    public const val SALE: String = "sales.food.paid-sale"
    public const val ENTITLEMENT: String = "sales.food.entitlement"
    public const val REFUND: String = "sales.food.refund"
    public const val REFUND_INDEX: String = "sales.food.refund-index"
    public const val CLAIM: String = "sales.food.quality-claim"
    public const val CLAIM_INDEX: String = "sales.food.claim-index"
    public const val EVENT: String = "sales.food.financial-event"
    public const val BEFORE: String = "sales.food.financial-before"
    /** V2 raw immutable proof/document chunks; never a document or independent authority. */
    public const val FRAME: String = "sales.food.financial-frame"
    public const val PHYSICAL_SLOT: String = "sales.food.physical-slot"
    public const val PHYSICAL_FACT: String = "sales.food.physical-fact"
    public const val PHYSICAL_APPLICATION: String = "sales.food.physical-application"
    public const val SOURCE_NAMESPACE: String = "sales.food.financial-source"
    public const val MAX_DOCUMENT_BYTES: Int = 65_536
    public const val MAX_ATTEMPTS: Int = 64
    public val kinds: Set<String> = setOf(COLLECTION, SALE, ENTITLEMENT, REFUND, REFUND_INDEX, CLAIM, CLAIM_INDEX,
        EVENT, BEFORE, FRAME, PHYSICAL_SLOT, PHYSICAL_FACT, PHYSICAL_APPLICATION)
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun schema(kind: String): String = "$kind/v1"
    public fun source(role: String, id: DomainId): ClaimKey = ClaimKey(SOURCE_NAMESPACE, "$role/${id.value}")
}

/**
 * Revision spaces are deliberate, not interchangeable:
 * - expectedOrderRevision: preparation order aggregate STORAGE generation;
 * - expectedPortionRevision: FoodPreparationState DOMAIN revision;
 * - expectedCollectionRevision / expectedRefundRevision / expectedSessionRevision: DOMAIN revisions.
 * Result revision entries below are always STORAGE generations; the read DTOs label both spaces.
 * Physical variants are wire drafts only and are explicitly rejected by the nonphysical handler.
 */
@Serializable
public sealed interface LocalFoodFinancialRequestV1 {
    /** Locks the commercial snapshot and reserves ALL unsent ingredients. Not permission to take money yet. */
    @Serializable @SerialName("BEGIN_COLLECTION")
    public data class BeginCollection(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val collectionId: DomainId, public val sessionId: DomainId,
        public val expectedSessionRevision: Revision) : LocalFoodFinancialRequestV1

    /** Start must commit actual protected two-participant recording capacity before a physical collection. */
    @Serializable @SerialName("START_PAYMENT")
    public data class StartPayment(public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision, public val attemptId: DomainId,
        public val tender: LocalTenderSelectionV1, public val amount: Money, public val payerReference: String? = null,
        public val recordingOperationId: OperationId, public val clientAllocationId: DomainId) : LocalFoodFinancialRequestV1

    /** Another observation of the SAME unknown attempt, never a second physical collection. */
    @Serializable @SerialName("RESERVE_PAYMENT_RECORDING")
    public data class ReservePaymentRecording(public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision, public val attemptId: DomainId,
        public val recordingOperationId: OperationId, public val clientAllocationId: DomainId) : LocalFoodFinancialRequestV1

    @Serializable @SerialName("OBSERVE_PAYMENT")
    public data class ObservePayment(public val collectionId: DomainId, public val sessionId: DomainId,
        public val attemptId: DomainId, public val intentOperationId: OperationId,
        public val outcome: LocalPaymentOutcomeV2) : LocalFoodFinancialRequestV1

    @Serializable @SerialName("APPLY_PAYMENT_FACT")
    public data class ApplyPaymentFact(public val recordingOperationId: OperationId) : LocalFoodFinancialRequestV1

    /** Creates a financial document/entitlement, never another ingredient issue or a fulfilled flag. */
    @Serializable @SerialName("FINALIZE_PAID")
    public data class FinalizePaid(public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val saleId: DomainId) : LocalFoodFinancialRequestV1

    @Serializable @SerialName("SEND_RESERVED")
    public data class SendReserved(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val portionIds: List<DomainId>) : LocalFoodFinancialRequestV1

    /** Ready is not pickup. This explicit transition checks actual finalized paid food coverage. */
    @Serializable @SerialName("PAID_PICKUP")
    public data class PaidPickup(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val collectionId: DomainId, public val portionId: DomainId,
        public val expectedPortionRevision: Revision) : LocalFoodFinancialRequestV1

    /** Whole, never-sent check only. No unresolved attempt, service charge, supplied quantity or policy waiver. */
    @Serializable @SerialName("CANCEL_WHOLE_UNSENT")
    public data class CancelWholeUnsent(public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val expectedOrderRevision: Revision, public val refundId: DomainId,
        public val reasonId: DomainId) : LocalFoodFinancialRequestV1

    @Serializable @SerialName("START_REFUND")
    public data class StartRefund(public val refundId: DomainId, public val expectedRefundRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision, public val attemptId: DomainId,
        public val originalPaymentId: DomainId, public val amount: Money,
        public val recordingOperationId: OperationId, public val clientAllocationId: DomainId) : LocalFoodFinancialRequestV1

    @Serializable @SerialName("RESERVE_REFUND_RECORDING")
    public data class ReserveRefundRecording(public val refundId: DomainId, public val expectedRefundRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision, public val attemptId: DomainId,
        public val recordingOperationId: OperationId, public val clientAllocationId: DomainId) : LocalFoodFinancialRequestV1

    @Serializable @SerialName("OBSERVE_REFUND")
    public data class ObserveRefund(public val refundId: DomainId, public val sessionId: DomainId,
        public val attemptId: DomainId, public val intentOperationId: OperationId,
        public val outcome: LocalRefundOutcomeV2) : LocalFoodFinancialRequestV1

    @Serializable @SerialName("APPLY_REFUND_FACT")
    public data class ApplyRefundFact(public val recordingOperationId: OperationId) : LocalFoodFinancialRequestV1

    /** Retire responsibility only after both financial and preparation obligations really terminate. */
    @Serializable @SerialName("CLOSE_RESOLVED_WORK")
    public data class CloseResolvedWork(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision) : LocalFoodFinancialRequestV1

    /** Intake is possible without a usable receipt/policy. It grants no return entitlement or actual refund. */
    @Serializable @SerialName("RECORD_QUALITY_CLAIM")
    public data class RecordQualityClaim(public val claimId: DomainId, public val facts: LocalFoodClaimFactsV1) : LocalFoodFinancialRequestV1
}

@Serializable public enum class LocalFoodClaimKindV1 { QUALITY, MERCHANT_FAULT, OTHER }
@Serializable public data class LocalFoodClaimFactsV1(public val kind: LocalFoodClaimKindV1,
    public val assertedOrderId: DomainId? = null, public val assertedSaleId: DomainId? = null,
    public val assertedPortionId: DomainId? = null, public val assertedQuantity: Quantity? = null,
    public val assertedReceivedAt: UtcInstant? = null, public val claimedAt: UtcInstant,
    public val reason: String, public val evidence: LocalCashEvidenceV1)

/** All revisions here explicitly refer to aggregate STORAGE generations, including mixed source modules. */
@Serializable public data class LocalFoodStorageRevisionV1(public val kind: String, public val id: DomainId,
    public val storageRevision: Revision)
@Serializable public data class LocalFoodFinancialResultV1(public val code: String, public val documentId: DomainId? = null,
    public val revisions: List<LocalFoodStorageRevisionV1> = emptyList(), public val amount: Money? = null,
    public val recordingOperationId: OperationId? = null, public val applicationOperationId: OperationId? = null,
    public val pendingReason: String? = null)

public object LocalFoodFinancialCodecV1 {
    public fun command(operationId: OperationId, request: LocalFoodFinancialRequestV1): MutationRequest =
        MutationRequest(operationId, LocalFoodFinancialProfileV1.COMMAND, encode(request))
    public fun encode(request: LocalFoodFinancialRequestV1): StoredBytes {
        when (request) {
            is LocalFoodFinancialRequestV1.StartPayment -> {
                require(request.amount.minor > 0L)
                request.payerReference?.let { require(it.isNotBlank() && it.length <= 128) }
            }
            is LocalFoodFinancialRequestV1.StartRefund -> require(request.amount.minor > 0L)
            is LocalFoodFinancialRequestV1.SendReserved -> require(request.portionIds.size in 1..8 && request.portionIds.distinct().size == request.portionIds.size)
            is LocalFoodFinancialRequestV1.ObservePayment -> when (val outcome = request.outcome) {
                is LocalPaymentOutcomeV2.CashReceived -> require(outcome.received.minor >= 0L && outcome.change.minor >= 0L)
                is LocalPaymentOutcomeV2.ExternalAttested -> foodFactText(outcome.terminal, outcome.reference)
                is LocalPaymentOutcomeV2.NotPaid -> localReason(outcome.reason)
                is LocalPaymentOutcomeV2.Unknown -> Unit
            }
            is LocalFoodFinancialRequestV1.ObserveRefund -> when (val outcome = request.outcome) {
                is LocalRefundOutcomeV2.ExternalAttested -> foodFactText(outcome.terminal, outcome.reference)
                is LocalRefundOutcomeV2.NotPaid -> localReason(outcome.reason)
                is LocalRefundOutcomeV2.Unknown, is LocalRefundOutcomeV2.CashPaid -> Unit
            }
            is LocalFoodFinancialRequestV1.RecordQualityClaim -> {
                localReason(request.facts.reason)
                request.facts.assertedQuantity?.let { require(it.scaled > 0L) }
            }
            else -> Unit
        }
        return localSalesEncode(request, LocalFoodFinancialRequestV1.serializer(), 8_192)
    }
    public fun decode(bytes: StoredBytes): LocalFoodFinancialRequestV1 =
        localSalesDecode(bytes, LocalFoodFinancialRequestV1.serializer(), 8_192)
    public fun normalize(bytes: StoredBytes): StoredBytes = encode(decode(bytes))
    public fun result(bytes: StoredBytes): LocalFoodFinancialResultV1 = localSalesDecode(bytes, LocalFoodFinancialResultV1.serializer(), 16_384)
}
private fun foodFactText(terminal: String?, reference: String?) {
    // Retain a bounded mismatch for reconciliation. Never rewrite an invalid match as "not paid".
    require(listOf(terminal, reference).all { it == null || it.length <= 128 })
}

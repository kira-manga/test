package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raw cashier facts. Retention is NOT financial application, coverage or bank confirmation. */
@Serializable
public sealed interface LocalPaymentOutcomeV2 {
    @Serializable @SerialName("UNKNOWN")
    public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalPaymentOutcomeV2
    @Serializable @SerialName("NOT_PAID")
    public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalPaymentOutcomeV2
    @Serializable @SerialName("CASH_RECEIVED")
    public data class CashReceived(public val received: Money, public val change: Money,
        public val evidence: LocalCashEvidenceV1) : LocalPaymentOutcomeV2
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1,
        public val terminal: String?, public val reference: String?) : LocalPaymentOutcomeV2
}

@Serializable
public sealed interface LocalRefundOutcomeV2 {
    @Serializable @SerialName("UNKNOWN")
    public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalRefundOutcomeV2
    @Serializable @SerialName("NOT_PAID")
    public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalRefundOutcomeV2
    @Serializable @SerialName("CASH_PAID")
    public data class CashPaid(public val evidence: LocalCashEvidenceV1) : LocalRefundOutcomeV2
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1,
        public val terminal: String?, public val reference: String?) : LocalRefundOutcomeV2
}

/**
 * Only v2 intent/reservation creates a v2 observation fence. A stored v1 request or
 * reservation is never upgraded. Mutable preconditions belong to intent, NOT to
 * the immutable physical fact or its financial-application continuation.
 */
@Serializable
public sealed interface LocalCollectionRequestV2 {
    @Serializable @SerialName("START_ATTEMPT")
    public data class StartAttempt(
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val expectedReservationRevision: Revision, public val items: List<LocalSalesStockRevisionV1>,
        public val attemptId: DomainId, public val tender: LocalTenderSelectionV1, public val amount: Money,
        public val payerReference: String? = null, public val recordingOperationId: OperationId,
        /** Opaque selector into already qualified protected CLIENT storage; not a capacity proof. */
        public val clientAllocationId: DomainId,
    ) : LocalCollectionRequestV2

    @Serializable @SerialName("RESERVE_RECORDING")
    public data class ReserveRecording(
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val attemptId: DomainId, public val recordingOperationId: OperationId,
        public val clientAllocationId: DomainId,
    ) : LocalCollectionRequestV2

    @Serializable @SerialName("RECORD_OUTCOME")
    public data class RecordOutcome(
        public val collectionId: DomainId, public val sessionId: DomainId, public val attemptId: DomainId,
        public val intentOperationId: OperationId, public val outcome: LocalPaymentOutcomeV2,
    ) : LocalCollectionRequestV2

    /** A separate accounting continuation; it never requests another physical collection. */
    @Serializable @SerialName("APPLY_OBSERVATION")
    public data class ApplyObservation(public val recordingOperationId: OperationId) : LocalCollectionRequestV2
}

@Serializable
public sealed interface LocalReturnRequestV2 {
    @Serializable @SerialName("START_REFUND")
    public data class StartRefund(
        public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId,
        public val expectedReturnRevision: Revision, public val sessionId: DomainId,
        public val expectedSessionRevision: Revision, public val attemptId: DomainId,
        public val originalPaymentId: DomainId, public val amount: Money,
        public val recordingOperationId: OperationId, public val clientAllocationId: DomainId,
    ) : LocalReturnRequestV2

    @Serializable @SerialName("RESERVE_REFUND_RECORDING")
    public data class ReserveRefundRecording(
        public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId,
        public val expectedReturnRevision: Revision, public val sessionId: DomainId,
        public val expectedSessionRevision: Revision, public val attemptId: DomainId,
        public val recordingOperationId: OperationId, public val clientAllocationId: DomainId,
    ) : LocalReturnRequestV2

    @Serializable @SerialName("RECORD_REFUND_OUTCOME")
    public data class RecordRefundOutcome(
        public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId,
        public val sessionId: DomainId, public val attemptId: DomainId, public val intentOperationId: OperationId,
        public val outcome: LocalRefundOutcomeV2,
    ) : LocalReturnRequestV2

    /** References the immutable original payout fact; never starts another payout. */
    @Serializable @SerialName("APPLY_OBSERVATION")
    public data class ApplyObservation(public val recordingOperationId: OperationId) : LocalReturnRequestV2
}

public object LocalCollectionCommandCodecV2 {
    public fun encode(request: LocalCollectionRequestV2): StoredBytes {
        when (request) {
            is LocalCollectionRequestV2.StartAttempt -> LocalCollectionCommandCodec.encode(request.intentV1())
            is LocalCollectionRequestV2.ReserveRecording, is LocalCollectionRequestV2.ApplyObservation -> Unit
            is LocalCollectionRequestV2.RecordOutcome -> validateFact(request.outcome)
        }
        return localSalesEncode(request, LocalCollectionRequestV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_REQUEST_BYTES)
    }
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalCollectionRequestV2 =
        localSalesDecode(body, LocalCollectionRequestV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_REQUEST_BYTES)
}

public object LocalReturnCommandCodecV2 {
    public fun encode(request: LocalReturnRequestV2): StoredBytes {
        when (request) {
            is LocalReturnRequestV2.StartRefund -> require(request.amount.minor > 0L) { "sales.amount.invalid" }
            is LocalReturnRequestV2.ReserveRefundRecording, is LocalReturnRequestV2.ApplyObservation -> Unit
            is LocalReturnRequestV2.RecordRefundOutcome -> validateFact(request.outcome)
        }
        return localSalesEncode(request, LocalReturnRequestV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_REQUEST_BYTES)
    }
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalReturnRequestV2 =
        localSalesDecode(body, LocalReturnRequestV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_REQUEST_BYTES)
}

/** These are pending FACTS, not a negative payment result or permission to replace an attempt. */
@Serializable
public enum class LocalObservationPendingReasonV2 {
    AWAITING_FINANCIAL_APPLICATION, FINANCIAL_SOURCE_MISSING, FINANCIAL_SOURCE_INVALID,
    ORIGINAL_ATTEMPT_MISMATCH, FINANCIAL_STATE_REQUIRES_RECONCILIATION,
    NUMERIC_CAPACITY_REQUIRED, STORAGE_CAPACITY_REQUIRED,
}

@Serializable
public sealed interface LocalObservationResultV2 {
    @Serializable @SerialName("FACT_RETAINED_PENDING")
    public data class Pending(
        public val recordingOperationId: OperationId, public val documentId: DomainId, public val attemptId: DomainId,
        public val reason: LocalObservationPendingReasonV2,
    ) : LocalObservationResultV2

    /** Only this separate committed result means that the immutable fact affected financial state. */
    @Serializable @SerialName("FACT_FINANCIALLY_APPLIED")
    public data class Applied(
        public val recordingOperationId: OperationId, public val applicationOperationId: OperationId,
        public val documentId: DomainId, public val attemptId: DomainId, public val action: LocalSalesAction,
        public val revisions: List<LocalSalesRevision>,
    ) : LocalObservationResultV2

    /** Read the referenced original application; this refusal creates no second financial effect. */
    @Serializable @SerialName("FACT_ALREADY_APPLIED")
    public data class AlreadyApplied(public val recordingOperationId: OperationId,
        public val applicationOperationId: OperationId) : LocalObservationResultV2

    /** Does not disclose whether a foreign fact exists. No original observation is replaced. */
    @Serializable @SerialName("FACT_NOT_AVAILABLE")
    public data object NotAvailable : LocalObservationResultV2
}

public object LocalObservationResultCodecV2 {
    public fun decode(body: StoredBytes): LocalObservationResultV2 =
        localSalesDecode(body, LocalObservationResultV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_RESULT_BYTES)
    internal fun encode(value: LocalObservationResultV2): StoredBytes =
        localSalesEncode(value, LocalObservationResultV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_RESULT_BYTES)
}

internal fun LocalCollectionRequestV2.StartAttempt.intentV1(): LocalCollectionRequestV1.StartAttempt =
    LocalCollectionRequestV1.StartAttempt(collectionId, expectedCollectionRevision, expectedSessionRevision,
        expectedReservationRevision, items, attemptId, tender, amount, payerReference, recordingOperationId)

internal fun LocalReturnRequestV2.StartRefund.intentV1(): LocalReturnRequestV1.StartRefund =
    LocalReturnRequestV1.StartRefund(saleId, claimId, returnId, expectedReturnRevision, sessionId,
        expectedSessionRevision, attemptId, originalPaymentId, amount, recordingOperationId)

private fun validateFact(outcome: LocalPaymentOutcomeV2) {
    when (outcome) {
        is LocalPaymentOutcomeV2.Unknown -> Unit
        is LocalPaymentOutcomeV2.NotPaid -> localReason(outcome.reason)
        // A mismatch with the intended tender/value is retained for reconciliation,
        // NOT rewritten as NotPaid. No arithmetic involving financial totals here.
        is LocalPaymentOutcomeV2.CashReceived -> require(outcome.received.minor >= 0 && outcome.change.minor >= 0) { "sales.fact.amount.invalid" }
        is LocalPaymentOutcomeV2.ExternalAttested -> factExternalText(outcome.terminal, outcome.reference)
    }
}

private fun validateFact(outcome: LocalRefundOutcomeV2) {
    when (outcome) {
        is LocalRefundOutcomeV2.Unknown, is LocalRefundOutcomeV2.CashPaid -> Unit
        is LocalRefundOutcomeV2.NotPaid -> localReason(outcome.reason)
        is LocalRefundOutcomeV2.ExternalAttested -> factExternalText(outcome.terminal, outcome.reference)
    }
}

private fun factExternalText(terminal: String?, reference: String?) {
    require(listOf(terminal, reference).all { it == null || it.length <= 128 }) { "sales.fact.evidence.invalid" }
}

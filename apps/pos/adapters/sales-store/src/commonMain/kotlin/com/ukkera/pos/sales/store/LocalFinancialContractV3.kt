package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class LocalJournalFactPhaseV3 { ORIGINAL, RESOLUTION }

/** Explicit complete target. No actor/device/session authority is accepted from this value. */
@Serializable
public data class LocalJournalTargetV3(
    public val kind: SalesRecordingKind,
    public val documentId: DomainId,
    public val sessionId: DomainId,
    public val attemptId: DomainId,
    public val intentOperationId: OperationId,
    public val recordingOperationId: OperationId,
    public val resolutionOperationId: OperationId,
    public val originalSaleId: DomainId? = null,
    public val originalClaimId: DomainId? = null,
    public val originalPaymentId: DomainId? = null,
) {
    init {
        require(setOf(intentOperationId, recordingOperationId, resolutionOperationId).size == 3)
        require(if (kind == SalesRecordingKind.PAYMENT_ATTESTATION)
            originalSaleId == null && originalClaimId == null && originalPaymentId == null
        else originalSaleId != null && originalClaimId != null && originalPaymentId != null)
    }
    internal fun factOperation(phase: LocalJournalFactPhaseV3): OperationId =
        if (phase == LocalJournalFactPhaseV3.ORIGINAL) recordingOperationId else resolutionOperationId
}

/** Raw observations are retained even when tender/value mismatch needs reconciliation. */
@Serializable
public sealed interface LocalPaymentOutcomeV3 {
    @Serializable @SerialName("UNKNOWN")
    public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalPaymentOutcomeV3
    @Serializable @SerialName("NOT_PAID")
    public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalPaymentOutcomeV3
    @Serializable @SerialName("CASH_RECEIVED")
    public data class CashReceived(public val received: Money, public val change: Money,
        public val evidence: LocalCashEvidenceV1) : LocalPaymentOutcomeV3
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1,
        public val terminal: String?, public val reference: String?) : LocalPaymentOutcomeV3
}

@Serializable
public sealed interface LocalRefundOutcomeV3 {
    @Serializable @SerialName("UNKNOWN")
    public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalRefundOutcomeV3
    @Serializable @SerialName("NOT_PAID")
    public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalRefundOutcomeV3
    @Serializable @SerialName("CASH_PAID")
    public data class CashPaid(public val evidence: LocalCashEvidenceV1) : LocalRefundOutcomeV3
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1,
        public val terminal: String?, public val reference: String?) : LocalRefundOutcomeV3
}

/** One closed successor command family. No v2 body/receipt reinterpretation or reserve-again. */
@Serializable
public sealed interface LocalCollectionRequestV3 {
    @Serializable @SerialName("START")
    public data class Start(
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val expectedReservationRevision: Revision, public val items: List<LocalSalesStockRevisionV1>,
        public val attemptId: DomainId, public val tender: LocalTenderSelectionV1, public val amount: Money,
        public val payerReference: String? = null, public val recordingOperationId: OperationId,
        public val resolutionOperationId: OperationId,
        /** Unique allocation selectors, NOT storage/space/custody assertions. */
        public val masterAllocationId: DomainId, public val clientAllocationId: DomainId,
    ) : LocalCollectionRequestV3

    @Serializable @SerialName("RECORD_ORIGINAL")
    public data class RecordOriginal(public val target: LocalJournalTargetV3,
        public val outcome: LocalPaymentOutcomeV3) : LocalCollectionRequestV3

    @Serializable @SerialName("RECORD_RESOLUTION")
    public data class RecordResolution(public val target: LocalJournalTargetV3,
        public val originalBodyDigest: String, public val resolverActorId: DomainId,
        public val outcome: LocalPaymentOutcomeV3) : LocalCollectionRequestV3

    /**
     * SQL-only continuation. recordingOperationId is the selected phase's actual fact operation
     * (target.recordingOperationId for ORIGINAL, target.resolutionOperationId for RESOLUTION).
     * Does not acquire or release a physical-action permission.
     */
    @Serializable @SerialName("APPLY_OBSERVATION")
    public data class ApplyObservation(public val recordingOperationId: OperationId,
        public val phase: LocalJournalFactPhaseV3) : LocalCollectionRequestV3
}

@Serializable
public sealed interface LocalReturnRequestV3 {
    @Serializable @SerialName("START")
    public data class Start(
        public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId,
        public val expectedReturnRevision: Revision, public val sessionId: DomainId,
        public val expectedSessionRevision: Revision, public val attemptId: DomainId,
        public val originalPaymentId: DomainId, public val amount: Money,
        public val recordingOperationId: OperationId, public val resolutionOperationId: OperationId,
        public val masterAllocationId: DomainId, public val clientAllocationId: DomainId,
    ) : LocalReturnRequestV3

    @Serializable @SerialName("RECORD_ORIGINAL")
    public data class RecordOriginal(public val target: LocalJournalTargetV3,
        public val outcome: LocalRefundOutcomeV3) : LocalReturnRequestV3

    @Serializable @SerialName("RECORD_RESOLUTION")
    public data class RecordResolution(public val target: LocalJournalTargetV3,
        public val originalBodyDigest: String, public val resolverActorId: DomainId,
        public val outcome: LocalRefundOutcomeV3) : LocalReturnRequestV3

    /** recordingOperationId is the selected phase's actual fact operation, not always the original. */
    @Serializable @SerialName("APPLY_OBSERVATION")
    public data class ApplyObservation(public val recordingOperationId: OperationId,
        public val phase: LocalJournalFactPhaseV3) : LocalReturnRequestV3
}

public object LocalCollectionCommandCodecV3 {
    public fun encode(request: LocalCollectionRequestV3): StoredBytes {
        when (request) {
            is LocalCollectionRequestV3.Start -> {
                LocalCollectionCommandCodec.encode(request.intentV1()) // Shared scalar rules, NOT v2 dispatch.
                require(request.masterAllocationId != request.clientAllocationId &&
                    request.recordingOperationId != request.resolutionOperationId)
            }
            is LocalCollectionRequestV3.RecordOriginal -> {
                require(request.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION); validateRetailFactV3(request.outcome)
            }
            is LocalCollectionRequestV3.RecordResolution -> {
                require(request.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION && request.outcome !is LocalPaymentOutcomeV3.Unknown)
                PayloadDigest(request.originalBodyDigest); validateRetailFactV3(request.outcome)
            }
            is LocalCollectionRequestV3.ApplyObservation -> Unit
        }
        return localSalesEncode(request, LocalCollectionRequestV3.serializer(), LocalFinancialJournalProfileV3.MAX_REQUEST_BYTES)
    }
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalCollectionRequestV3 =
        localSalesDecode(body, LocalCollectionRequestV3.serializer(), LocalFinancialJournalProfileV3.MAX_REQUEST_BYTES)
}

public object LocalReturnCommandCodecV3 {
    public fun encode(request: LocalReturnRequestV3): StoredBytes {
        when (request) {
            is LocalReturnRequestV3.Start -> require(request.amount.minor > 0L &&
                request.masterAllocationId != request.clientAllocationId && request.recordingOperationId != request.resolutionOperationId)
            is LocalReturnRequestV3.RecordOriginal -> {
                require(request.target.kind == SalesRecordingKind.REFUND_ATTESTATION); validateRetailFactV3(request.outcome)
            }
            is LocalReturnRequestV3.RecordResolution -> {
                require(request.target.kind == SalesRecordingKind.REFUND_ATTESTATION && request.outcome !is LocalRefundOutcomeV3.Unknown)
                PayloadDigest(request.originalBodyDigest); validateRetailFactV3(request.outcome)
            }
            is LocalReturnRequestV3.ApplyObservation -> Unit
        }
        return localSalesEncode(request, LocalReturnRequestV3.serializer(), LocalFinancialJournalProfileV3.MAX_REQUEST_BYTES)
    }
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalReturnRequestV3 =
        localSalesDecode(body, LocalReturnRequestV3.serializer(), LocalFinancialJournalProfileV3.MAX_REQUEST_BYTES)
}

@Serializable
public enum class LocalJournalPendingReasonV3 {
    AWAITING_FINANCIAL_APPLICATION, ORIGINAL_APPLICATION_REQUIRED, FINANCIAL_SOURCE_MISSING,
    FINANCIAL_SOURCE_INVALID, ORIGINAL_ATTEMPT_MISMATCH, FINANCIAL_STATE_REQUIRES_RECONCILIATION,
    NUMERIC_CAPACITY_REQUIRED, STORAGE_CAPACITY_REQUIRED,
}

@Serializable
public enum class LocalJournalUnavailableV3 { SOURCE_REQUIRED, CAPACITY_REQUIRED, REVISION_EXHAUSTED, JOURNAL_REQUIRED }

/** Only a NONREPLAYED kernel certificate can turn AttemptStarted into a one-shot native permit. */
@Serializable
public sealed interface LocalFinancialResultV3 {
    @Serializable @SerialName("ATTEMPT_STARTED")
    public data class AttemptStarted(public val target: LocalJournalTargetV3, public val amount: Money,
        public val requirementDigest: String, public val effectsDigest: String, public val mustCommitBefore: UtcInstant?,
        public val revisions: List<LocalSalesRevision>,
        public val stockItems: List<LocalSalesStockRevisionV1>) : LocalFinancialResultV3
    @Serializable @SerialName("STOCK_HELD_NON_ARMING")
    public data class StockHeld(public val collectionId: DomainId, public val reservationId: DomainId,
        public val reservationRevision: Revision, public val effectsDigest: String, public val mustCommitBefore: UtcInstant?,
        public val stockItems: List<LocalSalesStockRevisionV1>) : LocalFinancialResultV3
    @Serializable @SerialName("FACT_PENDING_APPLICATION")
    public data class Pending(public val target: LocalJournalTargetV3, public val phase: LocalJournalFactPhaseV3,
        public val reason: LocalJournalPendingReasonV3) : LocalFinancialResultV3
    @Serializable @SerialName("FACT_FINANCIALLY_APPLIED")
    public data class Applied(public val target: LocalJournalTargetV3, public val phase: LocalJournalFactPhaseV3,
        public val applicationOperationId: OperationId, public val action: LocalSalesAction,
        public val revisions: List<LocalSalesRevision>) : LocalFinancialResultV3
    @Serializable @SerialName("FACT_ALREADY_APPLIED")
    public data class AlreadyApplied(public val recordingOperationId: OperationId, public val phase: LocalJournalFactPhaseV3,
        public val applicationOperationId: OperationId) : LocalFinancialResultV3
    @Serializable @SerialName("REJECTED")
    public data class Rejected(@Serializable(with = StoredSalesRuleSerializer::class) public val rule: SalesRule) : LocalFinancialResultV3
    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(public val reason: LocalJournalUnavailableV3) : LocalFinancialResultV3
    @Serializable @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalFinancialResultV3
}

public object LocalFinancialResultCodecV3 {
    public fun decode(body: StoredBytes): LocalFinancialResultV3 =
        localSalesDecode(body, LocalFinancialResultV3.serializer(), LocalFinancialJournalProfileV3.MAX_RESULT_BYTES)
    internal fun encode(result: LocalFinancialResultV3): StoredBytes =
        localSalesEncode(result, LocalFinancialResultV3.serializer(), LocalFinancialJournalProfileV3.MAX_RESULT_BYTES)
}

internal fun LocalCollectionRequestV3.Start.intentV1(): LocalCollectionRequestV1.StartAttempt =
    LocalCollectionRequestV1.StartAttempt(collectionId, expectedCollectionRevision, expectedSessionRevision,
        expectedReservationRevision, items, attemptId, tender, amount, payerReference, recordingOperationId)
internal fun LocalReturnRequestV3.Start.intentV1(): LocalReturnRequestV1.StartRefund =
    LocalReturnRequestV1.StartRefund(saleId, claimId, returnId, expectedReturnRevision, sessionId,
        expectedSessionRevision, attemptId, originalPaymentId, amount, recordingOperationId)
internal fun LocalCollectionRequestV3.Start.target(operationId: OperationId): LocalJournalTargetV3 =
    LocalJournalTargetV3(SalesRecordingKind.PAYMENT_ATTESTATION, collectionId, sessionId, attemptId,
        operationId, recordingOperationId, resolutionOperationId)
internal fun LocalReturnRequestV3.Start.target(operationId: OperationId): LocalJournalTargetV3 =
    LocalJournalTargetV3(SalesRecordingKind.REFUND_ATTESTATION, returnId, sessionId, attemptId, operationId,
        recordingOperationId, resolutionOperationId, saleId, claimId, originalPaymentId)

private fun validateRetailFactV3(outcome: LocalPaymentOutcomeV3) {
    when (outcome) {
        is LocalPaymentOutcomeV3.Unknown -> Unit
        is LocalPaymentOutcomeV3.NotPaid -> localReason(outcome.reason)
        is LocalPaymentOutcomeV3.CashReceived -> require(outcome.received.minor >= 0 && outcome.change.minor >= 0)
        is LocalPaymentOutcomeV3.ExternalAttested -> retailExternalTextV3(outcome.terminal, outcome.reference)
    }
}
private fun validateRetailFactV3(outcome: LocalRefundOutcomeV3) {
    when (outcome) {
        is LocalRefundOutcomeV3.Unknown, is LocalRefundOutcomeV3.CashPaid -> Unit
        is LocalRefundOutcomeV3.NotPaid -> localReason(outcome.reason)
        is LocalRefundOutcomeV3.ExternalAttested -> retailExternalTextV3(outcome.terminal, outcome.reference)
    }
}
private fun retailExternalTextV3(terminal: String?, reference: String?) {
    require(listOf(terminal, reference).all { it == null || it.length <= 128 })
}

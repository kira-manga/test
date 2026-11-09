package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Actual persisted SKU generation, never the inventory planner's transaction-local revision zero. */
@Serializable
public data class LocalSalesStockRevisionV1(public val skuId: DomainId, public val storageRevision: Revision)

@Serializable
public sealed interface LocalTenderSelectionV1 {
    @Serializable @SerialName("CASH") public data object Cash : LocalTenderSelectionV1
    @Serializable @SerialName("EXTERNAL")
    public data class External(public val tenderId: DomainId, public val expectedPolicyRevision: Revision) : LocalTenderSelectionV1
}

@Serializable
public sealed interface LocalPaymentOutcomeV1 {
    @Serializable @SerialName("UNKNOWN") public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalPaymentOutcomeV1
    @Serializable @SerialName("NOT_PAID") public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalPaymentOutcomeV1
    @Serializable @SerialName("CASH_RECEIVED")
    public data class CashReceived(public val received: Money, public val change: Money, public val evidence: LocalCashEvidenceV1) : LocalPaymentOutcomeV1
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1, public val terminal: String?, public val reference: String?) : LocalPaymentOutcomeV1
}

@Serializable
public sealed interface LocalCollectionRequestV1 {
    @Serializable @SerialName("BEGIN")
    public data class Begin(
        public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val collectionId: DomainId, public val reservationId: DomainId,
        public val items: List<LocalSalesStockRevisionV1>,
        /** Optional tighter reservation deadline; stock expiry is independently authoritative. */
        public val reservationExpiresAt: UtcInstant? = null,
    ) : LocalCollectionRequestV1

    @Serializable @SerialName("START_ATTEMPT")
    public data class StartAttempt(
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val expectedSessionRevision: Revision, public val expectedReservationRevision: Revision,
        public val items: List<LocalSalesStockRevisionV1>, public val attemptId: DomainId,
        public val tender: LocalTenderSelectionV1, public val amount: Money, public val payerReference: String? = null,
        public val recordingOperationId: OperationId,
    ) : LocalCollectionRequestV1

    /** Further evidence about the SAME unresolved attempt; never another collection instruction. */
    @Serializable @SerialName("RESERVE_RECORDING")
    public data class ReserveRecording(
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val attemptId: DomainId, public val recordingOperationId: OperationId,
    ) : LocalCollectionRequestV1

    @Serializable @SerialName("RECORD_OUTCOME")
    public data class RecordOutcome(
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val attemptId: DomainId, public val intentOperationId: OperationId, public val outcome: LocalPaymentOutcomeV1,
    ) : LocalCollectionRequestV1

    @Serializable @SerialName("FINALIZE")
    public data class Finalize(
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val expectedOrderRevision: Revision, public val expectedSessionRevision: Revision,
        public val expectedReservationRevision: Revision, public val items: List<LocalSalesStockRevisionV1>,
        public val saleId: DomainId, public val stockIssueId: DomainId,
    ) : LocalCollectionRequestV1
}

public object LocalCollectionCommandCodec {
    public fun encode(request: LocalCollectionRequestV1): StoredBytes {
        when (request) {
            is LocalCollectionRequestV1.Begin -> validateStockVersions(request.items)
            is LocalCollectionRequestV1.Finalize -> validateStockVersions(request.items)
            is LocalCollectionRequestV1.StartAttempt -> {
                validateStockVersions(request.items)
                require(request.amount.minor > 0) { "sales.amount.invalid" }
                require(request.payerReference == null || request.payerReference.isNotBlank() && request.payerReference.length <= 128) {
                    "sales.payment.payer_reference"
                }
            }
            is LocalCollectionRequestV1.ReserveRecording -> Unit
            is LocalCollectionRequestV1.RecordOutcome -> when (val outcome = request.outcome) {
                is LocalPaymentOutcomeV1.NotPaid -> localReason(outcome.reason)
                is LocalPaymentOutcomeV1.CashReceived -> require(outcome.received.currency == outcome.change.currency &&
                    outcome.received.minor > 0 && outcome.change.minor >= 0) { "sales.amount.invalid" }
                is LocalPaymentOutcomeV1.ExternalAttested -> validateExternalText(outcome.terminal, outcome.reference)
                is LocalPaymentOutcomeV1.Unknown -> Unit
            }
        }
        return localSalesEncode(request, LocalCollectionRequestV1.serializer(), SalesRecordingBudget.MAX_REQUEST_BYTES)
    }
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalCollectionRequestV1 =
        localSalesDecode(body, LocalCollectionRequestV1.serializer(), SalesRecordingBudget.MAX_REQUEST_BYTES)
}

private fun validateStockVersions(items: List<LocalSalesStockRevisionV1>) {
    require(items.size in 1..16 && items.map { it.skuId }.distinct().size == items.size && items.all { it.storageRevision.value > 0 }) {
        "sales.stock.revisions"
    }
}

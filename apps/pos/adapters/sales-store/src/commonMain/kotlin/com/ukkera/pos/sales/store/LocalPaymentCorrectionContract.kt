package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Documentary correction of one original payment, never an instruction to take or pay money. */
@Serializable
public data class LocalPaymentCorrectionRequestV1(
    public val saleId: DomainId,
    public val paymentAttemptId: DomainId,
    public val expectedSessionRevision: Revision,
    public val expectedLatestCorrectionId: DomainId?,
    /** The manager confirms the immutable source amount; the Master independently compares it. */
    public val expectedAmount: Money,
    public val correctedTender: LocalTenderSelectionV1,
    public val evidence: LocalCashEvidenceV1,
    public val reason: String,
    public val terminal: String? = null,
    public val reference: String? = null,
) {
    init {
        require(expectedAmount.minor > 0) { "sales.amount.invalid" }
        localReason(reason)
        validateExternalText(terminal, reference)
        require(correctedTender != LocalTenderSelectionV1.Cash || terminal == null && reference == null) {
            "sales.payment.correction.cash_metadata"
        }
    }
}

@Serializable
public sealed interface LocalPaymentCorrectionQueryV1 {
    @Serializable @SerialName("SOURCE")
    public data class Source(public val saleId: DomainId) : LocalPaymentCorrectionQueryV1
    @Serializable @SerialName("HISTORY")
    public data class History(
        public val saleId: DomainId,
        public val paymentAttemptId: DomainId,
        /** Exclusive backward cursor bound to this sale and payment. */
        public val before: DomainId? = null,
        public val limit: Int = 5,
    ) : LocalPaymentCorrectionQueryV1 { init { require(limit in 1..5) } }
}

/** Original submitted figures. Later classifications never replace this count, cut or variance. */
@Serializable
public data class LocalPaymentCorrectionCloseViewV1(
    public val sessionId: DomainId,
    public val originalCloseOperationId: OperationId,
    public val ledgerRevision: Revision,
    public val expectedCash: Money,
    public val externalCollections: Money,
    public val externalRefunds: Money,
    public val counted: Money,
    public val variance: Money,
    public val retainedFloat: Money,
    public val actualSafeDelivery: Money,
    public val safeTransferId: DomainId?,
    public val submittedBy: DomainId,
    public val countEvidence: LocalCashActorEvidenceV1,
    public val varianceReason: String?,
)

@Serializable
public data class LocalPaymentCorrectionPaymentViewV1(
    public val paymentAttemptId: DomainId,
    public val amount: Money,
    public val originalTender: LocalTenderViewV1,
    public val originalIntentOperationId: OperationId,
    public val originalRecordingOperationId: OperationId,
    public val originalAttestation: LocalCashActorEvidenceV1,
    public val payerReference: String?,
    public val originalTerminal: String?,
    public val originalReference: String?,
    public val latestCorrectionId: DomainId?,
    public val latestClassification: LocalTenderViewV1,
    public val totalCorrections: Long,
)

@Serializable
public data class LocalPaymentCorrectionSourceViewV1(
    public val saleId: DomainId,
    public val orderId: DomainId,
    public val collectorId: DomainId,
    public val sessionId: DomainId,
    public val sessionRevision: Revision,
    public val originalClose: LocalPaymentCorrectionCloseViewV1,
    public val payments: List<LocalPaymentCorrectionPaymentViewV1>,
)

/** An immutable, linked classification adjustment, separate from the original money record. */
@Serializable
public data class LocalPaymentCorrectionViewV1(
    public val saleId: DomainId,
    public val paymentAttemptId: DomainId,
    public val sessionId: DomainId,
    public val collectorId: DomainId,
    public val originalIntentOperationId: OperationId,
    public val originalRecordingOperationId: OperationId,
    public val originalCloseOperationId: OperationId,
    public val amount: Money,
    public val originalTender: LocalTenderViewV1,
    public val previousClassification: LocalTenderViewV1,
    public val correctedTender: LocalTenderViewV1,
    public val previousClassificationDelta: Money,
    public val correctedClassificationDelta: Money,
    public val physicalCashDelta: Money,
    public val position: Revision,
    public val previousCorrectionId: DomainId?,
    public val evidence: LocalCashActorEvidenceV1,
    public val reason: String,
    public val terminal: String?,
    public val reference: String?,
    public val operationId: OperationId,
    public val recordedAt: UtcInstant,
) {
    public val id: DomainId get() = evidence.evidenceId
    init {
        localReason(reason)
        validateExternalText(terminal, reference)
        require(amount.minor > 0 && previousClassificationDelta == Money(amount.currency, -amount.minor) &&
            correctedClassificationDelta == amount && physicalCashDelta == Money(amount.currency, 0))
        require((position.value == 0L) == (previousCorrectionId == null) && previousCorrectionId != id)
        require(previousClassification.kind != correctedTender.kind || previousClassification.tenderId != correctedTender.tenderId)
            { "sales.payment_correction.no_classification_change" }
    }
}

@Serializable
public enum class LocalPaymentCorrectionRefusalV1 {
    SOURCE_NOT_AVAILABLE, PAYMENT_NOT_AVAILABLE, SESSION_NOT_CLOSED, STALE_SOURCE,
    DUPLICATE_CORRECTION, NO_CLASSIFICATION_CHANGE, TENDER_NOT_AVAILABLE, CAPACITY_REQUIRED,
    INVALID_TENDER_EVIDENCE,
}

@Serializable
public sealed interface LocalPaymentCorrectionResultV1 {
    @Serializable @SerialName("RECORDED")
    public data class Recorded(
        public val correction: LocalPaymentCorrectionViewV1,
        public val sessionRevision: Revision,
    ) : LocalPaymentCorrectionResultV1
    @Serializable @SerialName("REFUSED")
    public data class Refused(public val reason: LocalPaymentCorrectionRefusalV1) : LocalPaymentCorrectionResultV1
}

@Serializable
public data class LocalPaymentCorrectionsPageV1(
    public val saleId: DomainId,
    public val sessionId: DomainId,
    public val sessionRevision: Revision,
    public val originalClose: LocalPaymentCorrectionCloseViewV1,
    public val payment: LocalPaymentCorrectionPaymentViewV1,
    public val before: DomainId?,
    public val corrections: List<LocalPaymentCorrectionViewV1>,
    public val nextBefore: DomainId?,
)

@Serializable
public sealed interface LocalPaymentCorrectionReadResultV1 {
    /** Missing or not currently visible is not a financial or history-absence proof. */
    @Serializable @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalPaymentCorrectionReadResultV1
    @Serializable @SerialName("SOURCE")
    public data class Source(public val source: LocalPaymentCorrectionSourceViewV1) : LocalPaymentCorrectionReadResultV1
    @Serializable @SerialName("HISTORY")
    public data class History(public val page: LocalPaymentCorrectionsPageV1) : LocalPaymentCorrectionReadResultV1
}

public object LocalPaymentCorrectionCodec {
    public val COMMAND: CommandKey = CommandKey("sales.payment-correction.write", 1)
    public val QUERY: ReadKey = ReadKey("sales.payment-correction.read", 1)
    public fun encode(value: LocalPaymentCorrectionRequestV1): StoredBytes =
        localSalesEncode(value, LocalPaymentCorrectionRequestV1.serializer(), 8_192)
    public fun decode(value: StoredBytes): LocalPaymentCorrectionRequestV1 =
        localSalesDecode(value, LocalPaymentCorrectionRequestV1.serializer(), 8_192)
    public fun normalize(value: StoredBytes): StoredBytes = encode(decode(value))
    public fun request(value: LocalPaymentCorrectionQueryV1): ReadRequest =
        ReadRequest(QUERY, localSalesEncode(value, LocalPaymentCorrectionQueryV1.serializer(), 2_048))
    public fun decodeQuery(value: StoredBytes): LocalPaymentCorrectionQueryV1 =
        localSalesDecode(value, LocalPaymentCorrectionQueryV1.serializer(), 2_048)
    public fun result(value: StoredBytes): LocalPaymentCorrectionResultV1 =
        localSalesDecode(value, LocalPaymentCorrectionResultV1.serializer(), 8_192)
    public fun read(value: StoredBytes): LocalPaymentCorrectionReadResultV1 =
        localSalesDecode(value, LocalPaymentCorrectionReadResultV1.serializer())
    internal fun result(value: LocalPaymentCorrectionResultV1): StoredBytes =
        localSalesEncode(value, LocalPaymentCorrectionResultV1.serializer(), 8_192)
}

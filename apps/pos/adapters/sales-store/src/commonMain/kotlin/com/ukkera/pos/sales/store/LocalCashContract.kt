package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Human evidence is attributed to the authenticated actor by the handler, never this DTO. */
@Serializable
public data class LocalCashEvidenceV1(public val evidenceId: DomainId, public val occurredAt: UtcInstant)

@Serializable
public sealed interface LocalCashRequestV1 {
    @Serializable @SerialName("CONFIGURE_DRAWER")
    public data class ConfigureDrawer(public val drawerId: DomainId, public val label: String, public val currency: Currency) : LocalCashRequestV1

    /** Creates operational indexes only; it never creates an employee, login or permission. */
    @Serializable @SerialName("CONFIGURE_CUSTODIAN")
    public data class ConfigureCustodian(public val custodianId: DomainId) : LocalCashRequestV1

    /** A new EMPTY safe receipt ledger. Existing money needs a separate counted custody source. */
    @Serializable @SerialName("CONFIGURE_SAFE")
    public data class ConfigureSafe(
        public val custodyId: DomainId, public val custodianId: DomainId,
        public val label: String, public val currency: Currency,
    ) : LocalCashRequestV1

    @Serializable @SerialName("OPEN")
    public data class Open(
        public val sessionId: DomainId, public val drawerId: DomainId,
        public val expectedDrawerRevision: Revision, public val expectedCustodianRevision: Revision,
        public val businessDate: BusinessDate, public val openingSourceId: DomainId,
        public val opening: Money, public val evidence: LocalCashEvidenceV1,
    ) : LocalCashRequestV1

    @Serializable @SerialName("NON_SALE")
    public data class NonSale(
        public val sessionId: DomainId, public val expectedRevision: Revision, public val sourceId: DomainId,
        public val kind: NonSaleCashKind, public val amount: Money,
        public val evidence: LocalCashEvidenceV1, public val reason: String,
    ) : LocalCashRequestV1

    @Serializable @SerialName("BEGIN_CLOSING")
    public data class BeginClosing(
        public val sessionId: DomainId, public val expectedRevision: Revision, public val expectedWorkRevision: Revision,
    ) : LocalCashRequestV1

    @Serializable @SerialName("CANCEL_CLOSING")
    public data class CancelClosing(public val sessionId: DomainId, public val expectedRevision: Revision) : LocalCashRequestV1

    /** Each submitted observation is immutable; a recount names the preceding active count. */
    @Serializable @SerialName("RECORD_CLOSING_COUNT")
    public data class RecordClosingCount(
        public val sessionId: DomainId, public val expectedRevision: Revision,
        public val previousCountId: DomainId?, public val counted: Money,
        public val evidence: LocalCashEvidenceV1, public val reason: String? = null,
    ) : LocalCashRequestV1

    /** Actual closing custody delivery, before report submission and separate from receipt. */
    @Serializable @SerialName("DELIVER_COUNTED_CLOSE")
    public data class DeliverCountedClose(
        public val sessionId: DomainId, public val expectedRevision: Revision,
        public val countId: DomainId, public val counted: Money, public val retainedFloat: Money,
        public val transferId: DomainId, public val receivingCustodyId: DomainId,
        public val expectedSafeRevision: Revision, public val evidence: LocalCashEvidenceV1,
    ) : LocalCashRequestV1

    /** Uses the exact retained count, and requires actual receipt for a prior delivery. */
    @Serializable @SerialName("SUBMIT_COUNTED_CLOSE")
    public data class SubmitCountedClose(
        public val sessionId: DomainId, public val expectedRevision: Revision,
        public val countId: DomainId, public val counted: Money, public val retainedFloat: Money,
        public val expectedTransferRevision: Revision? = null, public val varianceReason: String? = null,
    ) : LocalCashRequestV1

    @Serializable @SerialName("SUBMIT_CLOSE")
    public data class SubmitClose(
        public val sessionId: DomainId, public val expectedRevision: Revision,
        public val counted: Money, public val countEvidence: LocalCashEvidenceV1, public val retainedFloat: Money,
        public val transferId: DomainId? = null, public val receivingCustodyId: DomainId? = null,
        public val expectedSafeRevision: Revision? = null, public val deliveryEvidence: LocalCashEvidenceV1? = null,
        public val varianceReason: String? = null,
    ) : LocalCashRequestV1

    @Serializable @SerialName("REVIEW")
    public data class Review(
        public val sessionId: DomainId, public val expectedRevision: Revision,
        public val evidence: LocalCashEvidenceV1, public val reason: String,
    ) : LocalCashRequestV1

    @Serializable @SerialName("DROP_TO_SAFE")
    public data class DropToSafe(
        public val sessionId: DomainId, public val expectedRevision: Revision,
        public val transferId: DomainId, public val receivingCustodyId: DomainId, public val expectedSafeRevision: Revision,
        public val amount: Money, public val evidence: LocalCashEvidenceV1,
    ) : LocalCashRequestV1

    @Serializable @SerialName("RECEIVE_SAFE")
    public data class ReceiveSafe(
        public val transferId: DomainId, public val expectedTransferRevision: Revision,
        public val expectedSessionRevision: Revision, public val expectedSafeRevision: Revision,
        public val evidence: LocalCashEvidenceV1,
    ) : LocalCashRequestV1
}

public object LocalCashCommandCodec {
    public fun encode(request: LocalCashRequestV1): StoredBytes {
        when (request) {
            is LocalCashRequestV1.ConfigureDrawer -> localLabel(request.label)
            is LocalCashRequestV1.ConfigureCustodian -> Unit
            is LocalCashRequestV1.ConfigureSafe -> localLabel(request.label)
            is LocalCashRequestV1.Open -> require(request.opening.minor >= 0) { "sales.amount.invalid" }
            is LocalCashRequestV1.NonSale -> {
                require(request.amount.minor > 0) { "sales.amount.invalid" }
                localReason(request.reason)
            }
            is LocalCashRequestV1.BeginClosing, is LocalCashRequestV1.CancelClosing, is LocalCashRequestV1.ReceiveSafe -> Unit
            is LocalCashRequestV1.RecordClosingCount -> {
                require(request.counted.minor >= 0) { "sales.amount.invalid" }
                request.reason?.let(::localReason)
                if (request.previousCountId != null) localReason(request.reason ?: "")
            }
            is LocalCashRequestV1.DeliverCountedClose -> {
                require(request.counted.currency == request.retainedFloat.currency && request.retainedFloat.minor >= 0 &&
                    request.counted.minor > request.retainedFloat.minor) { "sales.amount.invalid" }
            }
            is LocalCashRequestV1.SubmitCountedClose -> {
                require(request.counted.currency == request.retainedFloat.currency && request.retainedFloat.minor >= 0 &&
                    request.counted.minor >= request.retainedFloat.minor) { "sales.amount.invalid" }
                request.varianceReason?.let(::localReason)
            }
            is LocalCashRequestV1.SubmitClose -> {
                require(request.counted.minor >= 0 && request.retainedFloat.minor >= 0 &&
                    request.counted.currency == request.retainedFloat.currency) { "sales.amount.invalid" }
                request.varianceReason?.let(::localReason)
                val supplied = listOf(request.transferId, request.receivingCustodyId, request.expectedSafeRevision, request.deliveryEvidence)
                require(supplied.all { it == null } || supplied.all { it != null }) { "sales.custody.invalid" }
            }
            is LocalCashRequestV1.Review -> localReason(request.reason)
            is LocalCashRequestV1.DropToSafe -> require(request.amount.minor > 0) { "sales.amount.invalid" }
        }
        return localSalesEncode(request, LocalCashRequestV1.serializer(), 8_192)
    }

    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalCashRequestV1 = localSalesDecode(body, LocalCashRequestV1.serializer(), 8_192)
}

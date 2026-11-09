package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.ReturnClaimEvidence
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Asserted facts, not an original delivery record, eligibility determination or extended deadline. */
@Serializable
public data class LocalReturnClaimFactsV1(
    public val saleId: DomainId?, public val portionId: DomainId?, public val quantity: Quantity?,
    public val assertedReceivedAt: UtcInstant?, public val claimedAt: UtcInstant,
    public val reason: String, public val evidence: LocalCashEvidenceV1,
)

@Serializable
public enum class LocalReturnDispositionV1 { INSPECTED_SELLABLE, QUARANTINE, WASTE, SUPPLIER_RETURN }

@Serializable
public sealed interface LocalRefundOutcomeV1 {
    @Serializable @SerialName("UNKNOWN") public data class Unknown(public val evidence: LocalCashEvidenceV1) : LocalRefundOutcomeV1
    @Serializable @SerialName("NOT_PAID") public data class NotPaid(public val evidence: LocalCashEvidenceV1, public val reason: String) : LocalRefundOutcomeV1
    @Serializable @SerialName("CASH_PAID") public data class CashPaid(public val evidence: LocalCashEvidenceV1) : LocalRefundOutcomeV1
    @Serializable @SerialName("EXTERNAL_ATTESTED")
    public data class ExternalAttested(public val evidence: LocalCashEvidenceV1, public val terminal: String?, public val reference: String?) : LocalRefundOutcomeV1
}

@Serializable
public sealed interface LocalReturnRequestV1 {
    /** Always a pending claim intake. Missing policy/receipt does not make the legal claim disappear. */
    @Serializable @SerialName("RECORD_CLAIM")
    public data class RecordClaim(public val claimId: DomainId, public val facts: LocalReturnClaimFactsV1) : LocalReturnRequestV1

    /** Uses original intake facts; this is not an amendment of the customer's received/claim dates. */
    @Serializable @SerialName("BIND_CLAIM")
    public data class BindClaim(public val claimId: DomainId, public val expectedIntakeRevision: Revision,
        public val expectedEntitlementRevision: Revision) : LocalReturnRequestV1

    @Serializable @SerialName("ACCEPT")
    public data class Accept(
        public val saleId: DomainId, public val claimId: DomainId, public val expectedEntitlementRevision: Revision,
        public val expectedClaimRevision: Revision, public val returnId: DomainId,
        public val disposition: LocalReturnDispositionV1, public val inspectionEvidence: LocalCashEvidenceV1,
    ) : LocalReturnRequestV1

    @Serializable @SerialName("START_REFUND")
    public data class StartRefund(
        public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId,
        public val expectedReturnRevision: Revision, public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val attemptId: DomainId, public val originalPaymentId: DomainId, public val amount: Money,
        public val recordingOperationId: OperationId,
    ) : LocalReturnRequestV1

    /** Reserves another observation of the SAME unresolved attempt; never starts another payout. */
    @Serializable @SerialName("RESERVE_REFUND_RECORDING")
    public data class ReserveRefundRecording(
        public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId,
        public val expectedReturnRevision: Revision, public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val attemptId: DomainId, public val recordingOperationId: OperationId,
    ) : LocalReturnRequestV1

    @Serializable @SerialName("RECORD_REFUND_OUTCOME")
    public data class RecordRefundOutcome(
        public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId,
        public val expectedReturnRevision: Revision, public val sessionId: DomainId, public val expectedSessionRevision: Revision,
        public val attemptId: DomainId, public val intentOperationId: OperationId, public val outcome: LocalRefundOutcomeV1,
    ) : LocalReturnRequestV1
}

public object LocalReturnCommandCodec {
    public fun encode(request: LocalReturnRequestV1): StoredBytes {
        when (request) {
            is LocalReturnRequestV1.RecordClaim -> {
                localReason(request.facts.reason)
                request.facts.quantity?.let { require(it.scaled > 0) { "sales.quantity.invalid" } }
            }
            is LocalReturnRequestV1.BindClaim, is LocalReturnRequestV1.Accept, is LocalReturnRequestV1.ReserveRefundRecording -> Unit
            is LocalReturnRequestV1.StartRefund -> require(request.amount.minor > 0) { "sales.amount.invalid" }
            is LocalReturnRequestV1.RecordRefundOutcome -> when (val outcome = request.outcome) {
                is LocalRefundOutcomeV1.NotPaid -> localReason(outcome.reason)
                is LocalRefundOutcomeV1.ExternalAttested -> validateExternalText(outcome.terminal, outcome.reference)
                is LocalRefundOutcomeV1.Unknown, is LocalRefundOutcomeV1.CashPaid -> Unit
            }
        }
        return localSalesEncode(request, LocalReturnRequestV1.serializer(), 8_192)
    }
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalReturnRequestV1 = localSalesDecode(body, LocalReturnRequestV1.serializer(), 8_192)
}

internal fun validateExternalText(terminal: String?, reference: String?) {
    require(listOf(terminal, reference).all { it == null || it.isNotBlank() && it.length <= 128 }) { "sales.external_evidence.invalid" }
}

public fun interface TransactionSalesReturnPolicySource {
    /** Must include retained original sale/fulfillment facts, not a current-policy-only deadline. */
    public suspend fun current(view: CommandReadView, saleId: DomainId): PreparedSalesReturnPolicy?
}

public interface PreparedSalesReturnPolicy {
    public val scope: BranchScope
    public val recoveryResources: Set<RecoveryResourceKey>
    /**
     * Derive/verify the original received date from persisted evidence when known.
     * The asserted alternate remains in intake; it cannot silently extend original
     * legal dates. Null leaves a visible pending claim/escalation, not "no remedy".
     */
    public fun evidence(facts: LocalReturnClaimFactsV1, sale: PaidSale, actorEvidence: CashierEvidence,
        recordedAt: UtcInstant): ReturnClaimEvidence?
}

public object UnavailableSalesReturnPolicy : TransactionSalesReturnPolicySource {
    override public suspend fun current(view: CommandReadView, saleId: DomainId): PreparedSalesReturnPolicy? = null
}

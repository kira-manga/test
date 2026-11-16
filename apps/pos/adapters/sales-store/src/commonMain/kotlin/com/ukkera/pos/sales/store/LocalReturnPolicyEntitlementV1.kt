package com.ukkera.pos.sales.store

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.ElapsedReturnWindow
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.ReturnClaimEvidence
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UtcInstant

/** Exact original sold portion and the signed promise captured with its original quote. */
public class LocalReturnPolicyPortionBasisV1(
    public val portionId: DomainId,
    public val soldQuantity: Quantity,
    public val promiseState: LocalSalesReturnPromiseStateV2,
    public val promise: LocalSalesReturnPromiseDescriptorV2?,
) {
    init { require(soldQuantity.scaled > 0 && (promiseState == LocalSalesReturnPromiseStateV2.SIGNED_PRESENT) == (promise != null)) }
}

/** One verified CHANGE_OF_MIND receipt-window rule of the ACTIVE signed return-rights publication. */
public data class LocalReturnPolicyApplicableRuleV1(public val categoryCode: String, public val receiptWindowDays: Int) {
    init { require(categoryCode.isNotBlank() && receiptWindowDays >= 14) }
}

/** The active, authenticated rights publication. Absence is never an accepting default. */
public class LocalReturnPolicyRightsBasisV1(
    public val policyId: DomainId,
    public val activatedAt: UtcInstant,
    rules: List<LocalReturnPolicyApplicableRuleV1>,
) {
    public val rules: List<LocalReturnPolicyApplicableRuleV1> = rules.toList()
}

/** One immutable recorded customer-receipt (delivery) event; customer assertions never substitute. */
public class LocalReturnPolicyReceiptBasisV1(
    public val eventId: DomainId,
    public val receivedAt: UtcInstant,
    public val recordedAt: UtcInstant,
    portions: Map<DomainId, Quantity>,
) {
    public val portions: Map<DomainId, Quantity> = portions.toMap()
}

/**
 * Detached evidence prepared by a genuine source reader inside the Master transaction:
 * the exact original paid sale, its captured signed promises, the currently ACTIVE
 * authenticated return-rights publication and the COMPLETE customer-receipt census.
 * It holds no borrowed view and grants no authority by itself.
 */
public class LocalReturnPolicyBasisV1(
    public val scope: BranchScope,
    public val saleId: DomainId,
    public val originalSaleSnapshot: String,
    portions: List<LocalReturnPolicyPortionBasisV1>,
    public val rights: LocalReturnPolicyRightsBasisV1?,
    receipts: List<LocalReturnPolicyReceiptBasisV1>,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    public val portions: List<LocalReturnPolicyPortionBasisV1> = portions.toList()
    public val receipts: List<LocalReturnPolicyReceiptBasisV1> = receipts.toList()
    private val resources = recoveryResources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()

    init {
        require(this.portions.map { it.portionId }.distinct().size == this.portions.size &&
            this.receipts.map { it.eventId }.distinct().size == this.receipts.size)
    }
}

/** Stable refusal codes. Every refusal leaves the legal claim visible as a pending intake. */
public enum class LocalReturnPolicyRefusalV1 {
    BINDING_REQUIRED, SALE_MISMATCH, PORTION_NOT_SOLD, QUANTITY_INVALID, ORIGINAL_PROMISE_UNSPECIFIED,
    APPLICABLE_RIGHTS_REQUIRED, APPLICABLE_RULE_REQUIRED, CUSTOMER_RECEIPT_REQUIRED, RECEIPT_ALLOCATION_REVIEW_REQUIRED,
    AUTHORITATIVE_CHRONOLOGY_UNAVAILABLE, TIMING_UNAVAILABLE, RECEIPT_WINDOW_OUTSIDE, ASSERTED_CLAIM_TIME_INCONSISTENT,
}

public sealed interface LocalReturnPolicyDecisionV1 {
    public class Entitled internal constructor(public val evidence: ReturnClaimEvidence,
        public val receiptEventId: DomainId, public val effectiveDays: Int, public val expiresAt: UtcInstant) : LocalReturnPolicyDecisionV1
    public data class Refused(public val reason: LocalReturnPolicyRefusalV1) : LocalReturnPolicyDecisionV1
}

/**
 * Legacy BIND_CLAIM entitlement from verified original sources only.
 *
 * The V1 intake carries an untyped narrative reason, so only the ordinary CHANGE_OF_MIND
 * receipt window is evaluated: both the captured original promise and a matching active
 * rights rule must exist, the more favorable period applies, it runs (elapsed 24-hour
 * days, inclusive expiry) from the single recorded receipt event covering the whole sold
 * portion, and it is measured against the authoritative Master intake time. Defect,
 * partial delivery, multi-delivery and every other case stays PENDING (use the typed case
 * workflow); nothing here closes continuing legal rights or makes a late claim timely.
 */
public object LocalReturnPolicyEntitlementV1 {
    public fun decide(basis: LocalReturnPolicyBasisV1, facts: LocalReturnClaimFactsV1, actorEvidence: CashierEvidence,
        decidedAt: UtcInstant, submittedAt: UtcInstant): LocalReturnPolicyDecisionV1 {
        val portionId = facts.portionId
        val quantity = facts.quantity
        if (facts.saleId == null || portionId == null || quantity == null) return refused(LocalReturnPolicyRefusalV1.BINDING_REQUIRED)
        if (facts.saleId != basis.saleId) return refused(LocalReturnPolicyRefusalV1.SALE_MISMATCH)
        val portion = basis.portions.singleOrNull { it.portionId == portionId }
            ?: return refused(LocalReturnPolicyRefusalV1.PORTION_NOT_SOLD)
        val sold = portion.soldQuantity
        if (quantity.unitId != sold.unitId || quantity.scale != sold.scale || quantity.scaled !in 1..sold.scaled)
            return refused(LocalReturnPolicyRefusalV1.QUANTITY_INVALID)
        val promise = portion.promise
        val originalDays = promise?.changeOfMindReceiptWindowDays
        if (portion.promiseState != LocalSalesReturnPromiseStateV2.SIGNED_PRESENT || promise == null || originalDays == null)
            return refused(LocalReturnPolicyRefusalV1.ORIGINAL_PROMISE_UNSPECIFIED)
        val rights = basis.rights ?: return refused(LocalReturnPolicyRefusalV1.APPLICABLE_RIGHTS_REQUIRED)
        val rule = rights.rules.singleOrNull { it.categoryCode == promise.categoryCode }
            ?: return refused(LocalReturnPolicyRefusalV1.APPLICABLE_RULE_REQUIRED)
        val relevant = basis.receipts.filter { portionId in it.portions }
        if (relevant.isEmpty()) return refused(LocalReturnPolicyRefusalV1.CUSTOMER_RECEIPT_REQUIRED)
        val receipt = relevant.singleOrNull()?.takeIf { it.portions[portionId] == sold }
            ?: return refused(LocalReturnPolicyRefusalV1.RECEIPT_ALLOCATION_REVIEW_REQUIRED)
        // The decision may not precede the evidence it relies on, nor the submission it measures.
        if (decidedAt < maxOf(submittedAt, receipt.recordedAt, rights.activatedAt))
            return refused(LocalReturnPolicyRefusalV1.AUTHORITATIVE_CHRONOLOGY_UNAVAILABLE)
        val days = maxOf(originalDays, rule.receiptWindowDays)
        val window = ElapsedReturnWindow(days)
        val expiresAt = window.expiresAt(receipt.receivedAt) ?: return refused(LocalReturnPolicyRefusalV1.TIMING_UNAVAILABLE)
        when (window.containsSubmission(receipt.receivedAt, submittedAt)) {
            null -> return refused(LocalReturnPolicyRefusalV1.TIMING_UNAVAILABLE)
            false -> return refused(LocalReturnPolicyRefusalV1.RECEIPT_WINDOW_OUTSIDE)
            true -> Unit
        }
        // The customer's asserted claim instant is retained, never trusted beyond the Master record.
        if (facts.claimedAt < receipt.receivedAt || facts.claimedAt > submittedAt)
            return refused(LocalReturnPolicyRefusalV1.ASSERTED_CLAIM_TIME_INCONSISTENT)
        val evidence = ReturnClaimEvidence(promise.policyId, promise.policyRevision, receipt.receivedAt.value,
            facts.claimedAt.value, null, facts.reason, actorEvidence)
        return LocalReturnPolicyDecisionV1.Entitled(evidence, receipt.eventId, days, expiresAt)
    }

    private fun refused(reason: LocalReturnPolicyRefusalV1) = LocalReturnPolicyDecisionV1.Refused(reason)
}

/** Production prepared policy over a detached verified basis. */
public class LocalPreparedSalesReturnPolicyV1(private val basis: LocalReturnPolicyBasisV1) : PreparedSalesReturnPolicy {
    override public val scope: BranchScope get() = basis.scope
    override public val recoveryResources: Set<RecoveryResourceKey> get() = basis.recoveryResources

    /** Without the intake instant, the later binding instant is the (stricter) submission time. */
    override public fun evidence(facts: LocalReturnClaimFactsV1, sale: PaidSale, actorEvidence: CashierEvidence,
        recordedAt: UtcInstant): ReturnClaimEvidence? = evidence(facts, sale, actorEvidence, recordedAt, recordedAt)

    override public fun evidence(facts: LocalReturnClaimFactsV1, sale: PaidSale, actorEvidence: CashierEvidence,
        recordedAt: UtcInstant, submittedAt: UtcInstant): ReturnClaimEvidence? {
        if (sale.id != basis.saleId || sale.scope != basis.scope ||
            SalesSnapshotCodec.encodePaidSale(sale) != basis.originalSaleSnapshot) return null
        return (LocalReturnPolicyEntitlementV1.decide(basis, facts, actorEvidence, recordedAt, submittedAt)
            as? LocalReturnPolicyDecisionV1.Entitled)?.evidence
    }
}

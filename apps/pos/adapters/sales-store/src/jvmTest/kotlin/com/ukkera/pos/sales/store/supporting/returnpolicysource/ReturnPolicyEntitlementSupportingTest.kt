package com.ukkera.pos.sales.store.supporting.returnpolicysource

import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalReturnClaimFactsV1
import com.ukkera.pos.sales.store.LocalReturnPolicyApplicableRuleV1
import com.ukkera.pos.sales.store.LocalReturnPolicyBasisV1
import com.ukkera.pos.sales.store.LocalReturnPolicyDecisionV1
import com.ukkera.pos.sales.store.LocalReturnPolicyEntitlementV1
import com.ukkera.pos.sales.store.LocalReturnPolicyPortionBasisV1
import com.ukkera.pos.sales.store.LocalReturnPolicyReceiptBasisV1
import com.ukkera.pos.sales.store.LocalReturnPolicyRefusalV1
import com.ukkera.pos.sales.store.LocalReturnPolicyRightsBasisV1
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseDescriptorV2
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseStateV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * SELF-AUTHORED SUPPORTING CHECKS — NOT independent requirements-only expectations.
 * Written by the implementer of LocalReturnPolicyEntitlementV1 with synthetic data only.
 * They exercise the production decision over a detached basis; they do not prove the
 * native source readers, Master authorization, persistence, RPC or restart behaviour.
 */
class ReturnPolicyEntitlementSupportingTest {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")
    private fun at(literal: String) = UtcInstant(Instant.parse(literal))
    private val scope = BranchScope(id(1), id(2), id(3))
    private val sale = id(10)
    private val portion = id(11)
    private val otherPortion = id(12)
    private val unit = id(13)
    private val actor = id(14)
    private val policy = id(15)
    private val receiptEvent = id(16)
    private val sold = Quantity(unit, 3, 0)
    private val receivedAt = at("2026-10-01T10:00:00Z")
    private val receiptRecordedAt = at("2026-10-01T10:05:00Z")
    private val rightsActivatedAt = at("2026-09-01T00:00:00Z")
    private val actorEvidence = CashierEvidence(id(17), actor, Instant.parse("2026-10-05T09:00:00Z"))

    private fun promise(days: Int? = 14) = LocalSalesReturnPromiseDescriptorV2(policy, Revision(2), "apparel",
        sha256("synthetic-promise".encodeToByteArray()), changeOfMindReceiptWindowDays = days)

    private fun basis(
        state: LocalSalesReturnPromiseStateV2 = LocalSalesReturnPromiseStateV2.SIGNED_PRESENT,
        promise: LocalSalesReturnPromiseDescriptorV2? = promise(),
        rights: LocalReturnPolicyRightsBasisV1? = LocalReturnPolicyRightsBasisV1(id(18), rightsActivatedAt,
            listOf(LocalReturnPolicyApplicableRuleV1("apparel", 14))),
        receipts: List<LocalReturnPolicyReceiptBasisV1> = listOf(
            LocalReturnPolicyReceiptBasisV1(receiptEvent, receivedAt, receiptRecordedAt, mapOf(portion to sold))),
    ) = LocalReturnPolicyBasisV1(scope, sale, "synthetic-sale-snapshot", listOf(
        LocalReturnPolicyPortionBasisV1(portion, sold, state, promise),
        LocalReturnPolicyPortionBasisV1(otherPortion, sold, LocalSalesReturnPromiseStateV2.SIGNED_ABSENT, null)),
        rights, receipts, emptySet())

    private fun facts(quantity: Quantity? = Quantity(unit, 1, 0), portionId: DomainId? = portion,
        claimedAt: UtcInstant = at("2026-10-05T08:00:00Z"), saleId: DomainId? = sale) =
        LocalReturnClaimFactsV1(saleId, portionId, quantity, at("2026-09-20T00:00:00Z"), claimedAt,
            "Customer changed their mind", LocalCashEvidenceV1(id(19), claimedAt))

    private fun decide(basis: LocalReturnPolicyBasisV1 = basis(), facts: LocalReturnClaimFactsV1 = facts(),
        submittedAt: UtcInstant = at("2026-10-05T09:00:00Z"), decidedAt: UtcInstant = at("2026-10-05T09:01:00Z")) =
        LocalReturnPolicyEntitlementV1.decide(basis, facts, actorEvidence, decidedAt, submittedAt)

    private fun refusal(decision: LocalReturnPolicyDecisionV1) = assertIs<LocalReturnPolicyDecisionV1.Refused>(decision).reason

    @Test
    fun entitledWithinWindowRecordsOriginalPolicyAndRecordedDeliveryNotTheAssertedDate() {
        val entitled = assertIs<LocalReturnPolicyDecisionV1.Entitled>(decide())
        assertEquals(policy, entitled.evidence.policyId)
        assertEquals(Revision(2), entitled.evidence.policyRevision)
        assertEquals(receivedAt.value, entitled.evidence.customerReceivedAt)
        assertEquals(at("2026-10-05T08:00:00Z").value, entitled.evidence.claimedAt)
        assertEquals("Customer changed their mind", entitled.evidence.reason)
        assertEquals(actorEvidence, entitled.evidence.evidence)
        assertNull(entitled.evidence.remedyDueAt)
        assertEquals(receiptEvent, entitled.receiptEventId)
        assertEquals(14, entitled.effectiveDays)
        assertEquals(at("2026-10-15T10:00:00Z"), entitled.expiresAt)
    }

    @Test
    fun inclusiveElapsedExpiryAndAuthoritativeSubmissionTime() {
        val expiry = at("2026-10-15T10:00:00Z")
        assertIs<LocalReturnPolicyDecisionV1.Entitled>(decide(submittedAt = expiry, decidedAt = expiry))
        assertEquals(LocalReturnPolicyRefusalV1.RECEIPT_WINDOW_OUTSIDE, refusal(decide(
            submittedAt = at("2026-10-15T10:00:00.000000001Z"), decidedAt = at("2026-10-16T00:00:00Z"))))
        // A timely Master intake bound days later is still timely.
        assertIs<LocalReturnPolicyDecisionV1.Entitled>(decide(submittedAt = at("2026-10-14T00:00:00Z"),
            decidedAt = at("2026-11-30T00:00:00Z")))
    }

    @Test
    fun moreFavorableOriginalPromiseOrApplicableRuleIsPreserved() {
        val longerPromise = basis(promise = promise(30))
        val late = at("2026-10-20T00:00:00Z")
        assertEquals(30, assertIs<LocalReturnPolicyDecisionV1.Entitled>(decide(longerPromise, submittedAt = late,
            decidedAt = late)).effectiveDays)
        val longerRule = basis(rights = LocalReturnPolicyRightsBasisV1(id(18), rightsActivatedAt,
            listOf(LocalReturnPolicyApplicableRuleV1("apparel", 21))))
        assertEquals(21, assertIs<LocalReturnPolicyDecisionV1.Entitled>(decide(longerRule, submittedAt = late,
            decidedAt = late)).effectiveDays)
        assertEquals(LocalReturnPolicyRefusalV1.RECEIPT_WINDOW_OUTSIDE, refusal(decide(submittedAt = late, decidedAt = late)))
    }

    @Test
    fun missingPolicyOrDeliveryEvidenceFailsClosed() {
        assertEquals(LocalReturnPolicyRefusalV1.APPLICABLE_RIGHTS_REQUIRED, refusal(decide(basis(rights = null))))
        assertEquals(LocalReturnPolicyRefusalV1.APPLICABLE_RULE_REQUIRED, refusal(decide(basis(
            rights = LocalReturnPolicyRightsBasisV1(id(18), rightsActivatedAt, listOf(LocalReturnPolicyApplicableRuleV1("grocery", 14)))))))
        assertEquals(LocalReturnPolicyRefusalV1.ORIGINAL_PROMISE_UNSPECIFIED, refusal(decide(basis(
            state = LocalSalesReturnPromiseStateV2.SIGNED_ABSENT, promise = null))))
        assertEquals(LocalReturnPolicyRefusalV1.ORIGINAL_PROMISE_UNSPECIFIED, refusal(decide(basis(promise = promise(null)))))
        assertEquals(LocalReturnPolicyRefusalV1.ORIGINAL_PROMISE_UNSPECIFIED, refusal(decide(facts = facts(portionId = otherPortion))))
        assertEquals(LocalReturnPolicyRefusalV1.CUSTOMER_RECEIPT_REQUIRED, refusal(decide(basis(receipts = emptyList()))))
    }

    @Test
    fun ambiguousOrPartialDeliveryStaysPendingForReview() {
        val partial = LocalReturnPolicyReceiptBasisV1(receiptEvent, receivedAt, receiptRecordedAt, mapOf(portion to Quantity(unit, 2, 0)))
        assertEquals(LocalReturnPolicyRefusalV1.RECEIPT_ALLOCATION_REVIEW_REQUIRED, refusal(decide(basis(receipts = listOf(partial)))))
        val second = LocalReturnPolicyReceiptBasisV1(id(20), receivedAt, receiptRecordedAt, mapOf(portion to Quantity(unit, 1, 0)))
        assertEquals(LocalReturnPolicyRefusalV1.RECEIPT_ALLOCATION_REVIEW_REQUIRED, refusal(decide(basis(receipts = listOf(partial, second)))))
    }

    @Test
    fun bindingAndQuantityComeFromTheOriginalSaleOnly() {
        assertEquals(LocalReturnPolicyRefusalV1.BINDING_REQUIRED, refusal(decide(facts = facts(quantity = null))))
        assertEquals(LocalReturnPolicyRefusalV1.BINDING_REQUIRED, refusal(decide(facts = facts(portionId = null))))
        assertEquals(LocalReturnPolicyRefusalV1.SALE_MISMATCH, refusal(decide(facts = facts(saleId = id(99)))))
        assertEquals(LocalReturnPolicyRefusalV1.PORTION_NOT_SOLD, refusal(decide(facts = facts(portionId = id(98)))))
        assertEquals(LocalReturnPolicyRefusalV1.QUANTITY_INVALID, refusal(decide(facts = facts(quantity = Quantity(unit, 4, 0)))))
        assertEquals(LocalReturnPolicyRefusalV1.QUANTITY_INVALID, refusal(decide(facts = facts(quantity = Quantity(id(97), 1, 0)))))
        assertIs<LocalReturnPolicyDecisionV1.Entitled>(decide(facts = facts(quantity = sold)))
    }

    @Test
    fun chronologyAndAssertedClaimTimeCannotManufactureEntitlement() {
        assertEquals(LocalReturnPolicyRefusalV1.AUTHORITATIVE_CHRONOLOGY_UNAVAILABLE, refusal(decide(
            decidedAt = at("2026-10-05T08:59:59Z"))))
        // Delivery recorded after the decision instant cannot support it.
        val laterRecorded = LocalReturnPolicyReceiptBasisV1(receiptEvent, receivedAt, at("2026-10-06T00:00:00Z"), mapOf(portion to sold))
        assertEquals(LocalReturnPolicyRefusalV1.AUTHORITATIVE_CHRONOLOGY_UNAVAILABLE, refusal(decide(basis(receipts = listOf(laterRecorded)))))
        assertEquals(LocalReturnPolicyRefusalV1.ASSERTED_CLAIM_TIME_INCONSISTENT, refusal(decide(
            facts = facts(claimedAt = at("2026-10-05T09:30:00Z")))))
        assertEquals(LocalReturnPolicyRefusalV1.ASSERTED_CLAIM_TIME_INCONSISTENT, refusal(decide(
            facts = facts(claimedAt = at("2026-09-30T00:00:00Z")))))
        assertEquals(LocalReturnPolicyRefusalV1.TIMING_UNAVAILABLE, refusal(decide(submittedAt = at("2026-09-30T00:00:00Z"))))
    }
}

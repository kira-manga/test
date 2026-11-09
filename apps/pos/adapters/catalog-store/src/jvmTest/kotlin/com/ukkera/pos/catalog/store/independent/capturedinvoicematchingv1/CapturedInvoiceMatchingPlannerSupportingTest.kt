package com.ukkera.pos.catalog.store.independent.capturedinvoicematchingv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlin.test.*
import com.ukkera.pos.catalog.store.independent.capturedinvoicematchingv1.CapturedInvoiceMatchingPlannerSeams as Inputs

/**
 * CCM v1.0.1 + combined-action binding v1.0.0 numeric oracles at the actual
 * slice/effects functions. Synthetic pre-hydrated planner-unit seams only:
 * no current authority, real source qualification, Master commit or persistence.
 */
public class CapturedInvoiceMatchingPlannerSupportingTest {
    @Test
    public fun ccm01FullHistoricalCartonSliceRecognizes24000WithBillingOnlyProposal() {
        val cut = Inputs.full()
        val request = Inputs.request(cut, 24)
        val quoted = purchasingCapturedMatchSliceV2(request.sourceQuery(), cut)
        val proposal = Inputs.propose(cut, request)
        assertEquals(quoted, proposal.slice)
        assertIncrement(proposal, quantity = 24, increment = 24000, rawClaim = 24000)
        assertEquals(2L, cut.claim.lines.single().goods.quantity.scaled)
        assertEquals("12/1", cut.claim.lines.single().goods.units.rate.canonicalString())
        val after = Inputs.postimage(cut, proposal)
        assertProgress(after, matched = 24, recognized = 24000, remaining = 0)
        assertEquals(Inputs.each(0), checkNotNull(cut.receipt).acceptedQuantity - receiptBilled(after).quantity)
        assertBillingOnly(cut, proposal, setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PROGRESS,
            LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE))
    }

    @Test
    public fun ccm02NoReceiptCannotProducePositiveMatchOrPayableProposal() {
        val cut = Inputs.arrival().copy(receipt = null)
        assertRefusesBeforeEffects(cut, Inputs.request(cut, 24), "purchasing.receipt_required")
        assertEquals(Inputs.money(24000), cut.claim.total())
        assertEquals(Inputs.each(24), cut.claim.lines.single().goods.baseQuantity())
        assertNull(cut.recognized)
        assertEquals(Inputs.each(0), orderBilled(cut).quantity)
    }

    @Test
    public fun ccm02TwoTwelveUnitSlicesKeepEach12000ResultAndOne24000Payable() {
        val firstCut = Inputs.arrival()
        assertRefusesBeforeEffects(firstCut, Inputs.request(firstCut, 24), "purchasing.remaining_quantity_exceeded")
        val first = Inputs.propose(firstCut, Inputs.request(firstCut, 12))
        val firstResultBytes = first.outcome.result.copyBytes()
        assertIncrement(first, 12, 12000, 24000)
        val afterFirst = Inputs.postimage(firstCut, first)
        assertProgress(afterFirst, 12, 12000, 12)
        assertEquals(Inputs.money(12000), firstCut.claim.total() - payable(afterFirst).net)
        assertEquals(Inputs.each(0), checkNotNull(firstCut.receipt).acceptedQuantity - receiptBilled(afterFirst).quantity)
        val secondCut = Inputs.nextArrivalReceipt(afterFirst)
        assertNotEquals(checkNotNull(firstCut.receipt).receiptId, checkNotNull(secondCut.receipt).receiptId)
        assertNotEquals(checkNotNull(firstCut.receipt).sourceId, checkNotNull(secondCut.receipt).sourceId)
        val second = Inputs.propose(secondCut, Inputs.request(secondCut, 12, ordinal = 1))
        assertIncrement(second, 12, 12000, 24000)
        val afterSecond = Inputs.postimage(secondCut, second)
        assertProgress(afterSecond, 24, 24000, 0)
        assertEquals(first.result().invoiceId, second.result().invoiceId)
        assertNotEquals(first.result().id, second.result().id)
        assertContentEquals(firstResultBytes, first.outcome.result.copyBytes())
        assertEquals(Inputs.money(12000), first.result().net)
        assertEquals(Inputs.each(12), receiptBilled(afterFirst).quantity)
        assertEquals(Inputs.each(12), receiptBilled(afterSecond).quantity)
        assertEquals(Inputs.each(24), orderBilled(afterSecond).quantity)
        assertBillingOnly(firstCut, first, setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PROGRESS,
            LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE))
        assertBillingOnly(secondCut, second, setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.RECEIPT_BILLED))
    }

    @Test
    public fun ccm03Accepted22Recognizes22000WithoutRewritingRaw24Claim() {
        val cut = Inputs.quality()
        assertRefusesBeforeEffects(cut, Inputs.request(cut, 24), "purchasing.remaining_quantity_exceeded")
        val proposal = Inputs.propose(cut, Inputs.request(cut, 22))
        assertIncrement(proposal, 22, 22000, 24000)
        val after = Inputs.postimage(cut, proposal)
        assertProgress(after, 22, 22000, 2)
        assertEquals(Inputs.money(2000), cut.claim.total() - payable(after).net)
        assertSame(cut.invoice, after.invoice)
        assertEquals(Inputs.money(24000), after.claim.total())
        assertEquals(Inputs.each(24), after.claim.lines.single().goods.baseQuantity())
        assertBillingOnly(cut, proposal, setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PROGRESS,
            LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE))
    }

    @Test
    public fun ccm06Prior18LeavesExactly6For6000AndCannotBillRemaining4Again() {
        val cut = Inputs.capacity()
        val priorOrder = orderBilled(cut)
        val priorReceipt = receiptBilled(cut)
        assertEquals(Inputs.each(6), checkNotNull(cut.receipt).acceptedQuantity - priorReceipt.quantity)
        assertRefusesBeforeEffects(cut, Inputs.request(cut, 10), "purchasing.remaining_quantity_exceeded")
        val proposal = Inputs.propose(cut, Inputs.request(cut, 6))
        assertIncrement(proposal, 6, 6000, 10000)
        val after = Inputs.postimage(cut, proposal)
        assertProgress(after, 6, 6000, 4)
        assertEquals(Inputs.money(4000), cut.claim.total() - payable(after).net)
        assertEquals(Inputs.each(24), orderBilled(after).quantity)
        assertEquals(Inputs.money(24000), receiptBilled(after).net)
        assertEquals(Inputs.each(24), receiptBilled(after).quantity)
        assertEquals(Inputs.each(0), checkNotNull(cut.receipt).acceptedQuantity - receiptBilled(after).quantity)
        assertEquals(Inputs.each(18), priorOrder.quantity)
        assertEquals(Inputs.money(18000), priorOrder.net)
        assertEquals(Inputs.each(18), priorReceipt.quantity)
        assertEquals(Inputs.money(18000), priorReceipt.net)
        assertRefusesBeforeEffects(after, Inputs.request(after, 4, ordinal = 1), "purchasing.remaining_quantity_exceeded")
        assertBillingOnly(cut, proposal, setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PROGRESS, LocalPurchasingProfile.PAYABLE))
    }

    @Test
    public fun ccm04CompliantARecognizes10000ButB11000ClaimIsNotApprovedAt10000() {
        val cut = Inputs.price()
        val proposal = Inputs.propose(cut, Inputs.request(cut, 10))
        assertIncrement(proposal, 10, 10000, 21000)
        val afterA = Inputs.postimage(cut, proposal)
        val progress = checkNotNull(afterA.recognized).progress.value.body as PurchasingBody.Progress
        assertEquals(Inputs.each(10), progress.lines[0].quantity)
        assertEquals(Inputs.money(10000), progress.lines[0].net)
        assertEquals(Inputs.each(0), progress.lines[1].quantity)
        assertEquals(Inputs.money(0), progress.lines[1].net)
        val selectedB = Inputs.price(selected = 1).copy(recognized = afterA.recognized, orderMatches = afterA.orderMatches)
        assertRefusesBeforeEffects(selectedB, Inputs.request(selectedB, 5, ordinal = 1, selected = 1),
            "purchasing.variance_requires_approval")
        val orderedB = (checkNotNull(selectedB.order).value.body as PurchasingBody.Order).value.lines[1].goods
        assertEquals(Inputs.money(11000), selectedB.claim.lines[1].goods.total())
        assertEquals(Inputs.money(10000), orderedB.total())
        assertEquals(Inputs.money(11000), selectedB.claim.total() - payable(afterA).net)
        assertEquals(Inputs.money(10000), payable(afterA).net)
        assertEquals(Inputs.money(21000), selectedB.claim.total())
        assertBillingOnly(cut, proposal, setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PROGRESS,
            LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE))
    }

    @Test
    public fun ccm08MonetaryRequirementCarriesFullRawClaimNotSelectedIncrement() {
        // Offered requirement only: no synthetic grants or authorization evaluator.
        val cases = listOf(Triple(Inputs.full(), 24L, 24000L), Triple(Inputs.arrival(), 12L, 24000L),
            Triple(Inputs.quality(), 22L, 24000L), Triple(Inputs.capacity(), 6L, 10000L), Triple(Inputs.price(), 10L, 21000L))
        for ((cut, selected, rawClaim) in cases) {
            val requirement = purchasingCapturedMatchRequirementV2(Inputs.scope, cut.claim.id, cut.invoice.row.revision,
                Inputs.operation(4), Inputs.digest, "ccm.planner_unit", purchasingInvoiceClaim(cut.invoice).total())
            assertEquals(Permission(SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE, PermissionVerb.PERFORM), requirement.permission)
            assertEquals(Inputs.scope, requirement.scope)
            assertEquals(Inputs.money(rawClaim), assertIs<LimitUse.Monetary>(requirement.limitUse).amount)
            val binding = checkNotNull(requirement.binding)
            assertEquals(cut.claim.id, binding.documentId)
            assertEquals(cut.invoice.row.revision, binding.revision)
            assertEquals(Inputs.digest, binding.payloadDigest)
            assertEquals(Inputs.operation(4), binding.operationId)
            assertEquals("ccm.planner_unit", binding.reasonCode)
            assertEquals(Inputs.money(rawClaim), Inputs.propose(cut, Inputs.request(cut, selected)).result().originalInvoiceClaim)
        }
    }

    private fun assertIncrement(proposal: PlannerProposal, quantity: Long, increment: Long, rawClaim: Long) {
        val result = proposal.result()
        assertEquals("purchasing.captured_invoice_matched_recognized", result.code)
        assertEquals(Inputs.each(quantity), result.quantity)
        assertEquals(Inputs.money(increment), result.net)
        assertEquals(Inputs.money(0), result.tax)
        assertEquals(Inputs.money(rawClaim), result.originalInvoiceClaim)
        assertEquals(Revision(1), result.revision)
        assertEquals(Inputs.money(increment), proposal.slice.net)
        assertEquals(Inputs.money(0), proposal.slice.tax)
        val event = assertIs<PurchasingBody.CapturedMatch>(proposal.row(LocalPurchasingProfile.MATCH).value.body)
        assertEquals(Inputs.money(increment), event.net)
        assertEquals(Inputs.money(0), event.tax)
    }

    private fun assertProgress(cut: PurchasingCapturedMatchCutV2, matched: Long, recognized: Long, remaining: Long) {
        val state = checkNotNull(cut.recognized).progress.value.body as PurchasingBody.Progress
        assertEquals(Inputs.each(matched), state.lines.single().quantity)
        assertEquals(Inputs.money(recognized), payable(cut).net)
        assertEquals(Inputs.money(0), payable(cut).tax)
        assertEquals(Inputs.each(remaining), cut.claim.lines.single().goods.baseQuantity() - state.lines.single().quantity)
    }

    private fun assertRefusesBeforeEffects(cut: PurchasingCapturedMatchCutV2, request: LocalMatchCapturedSupplierInvoiceV2,
        code: String) {
        var effectCallbacks = 0
        val refusal = assertFailsWith<PurchaseRejected> {
            val slice = purchasingCapturedMatchSliceV2(request.sourceQuery(), cut, request)
            purchasingCapturedMatchEffectsV2(request, cut, slice) { _, _, _, _ ->
                effectCallbacks++
                error("A refused slice must not reach this effects callback")
            }
        }
        assertEquals(code, refusal.code)
        assertEquals(0, effectCallbacks)
    }

    private fun assertBillingOnly(before: PurchasingCapturedMatchCutV2, proposal: PlannerProposal, claimKinds: Set<String>) {
        val event = assertIs<PurchasingBody.CapturedMatch>(proposal.row(LocalPurchasingProfile.MATCH).value.body)
        val request = event.request
        val receipt = checkNotNull(before.receipt)
        val expected = setOf(AggregateKey(LocalPurchasingProfile.MATCH, request.matchId),
            AggregateKey(LocalPurchasingProfile.ORDER_MATCHES, request.orderId), AggregateKey(LocalPurchasingProfile.PROGRESS, request.invoiceId),
            AggregateKey(LocalPurchasingProfile.ORDER_BILLED, request.allocationId), AggregateKey(LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId),
            AggregateKey(LocalPurchasingProfile.PAYABLE, request.invoiceId))
        assertEquals(expected, proposal.outcome.changes.map { it.key }.toSet())
        assertEquals(6, proposal.outcome.changes.size)
        val expectedClaims = expected.filter { it.kind in claimKinds }.map { key ->
            ClaimChange.Acquire(ClaimKey(LocalPurchasingProfile.DOCUMENT_CLAIM, "${key.kind}/${key.id.value}"), key.id)
        }.toSet()
        assertEquals(expectedClaims, proposal.outcome.claims.toSet())
        assertEquals(expectedClaims.size, proposal.outcome.claims.size)
        assertEquals(checkNotNull(before.document.source), event.originalSource)
        assertEquals(PurchasingSource.from(before.invoice.row), event.invoiceSource)
        assertEquals(PurchasingSource.from(checkNotNull(before.order).row), event.orderSource)
        assertEquals(receipt.originalOwnedValue, event.receipt.originalOwnedValue)
        assertEquals(receipt.acceptedQuantity, event.receipt.acceptedQuantity)
        assertEquals(receipt.baseQuantity, event.receipt.originalQuantity)
        assertTrue(proposal.outcome.jobs.isEmpty())
        assertNull(proposal.outcome.mustCommitBefore)
        assertSame(before.original, Inputs.postimage(before, proposal).original)
        // Exact six purchasing keys exclude source rewrites and every stock/value/cash/credit/settlement key.
    }

    private fun payable(cut: PurchasingCapturedMatchCutV2): PurchasingBody.Payable =
        checkNotNull(checkNotNull(cut.recognized).payable).value.body as PurchasingBody.Payable
    private fun orderBilled(cut: PurchasingCapturedMatchCutV2): PurchasingBody.OrderBilled =
        checkNotNull(cut.orderBilling).value.body as PurchasingBody.OrderBilled
    private fun receiptBilled(cut: PurchasingCapturedMatchCutV2): PurchasingBody.ReceiptBilled =
        checkNotNull(cut.receiptBilling).value.body as PurchasingBody.ReceiptBilled
}

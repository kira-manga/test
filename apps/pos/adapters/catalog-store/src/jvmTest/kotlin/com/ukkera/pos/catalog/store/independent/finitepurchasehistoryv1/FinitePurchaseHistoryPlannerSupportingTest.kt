package com.ukkera.pos.catalog.store.independent.finitepurchasehistoryv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UnitConversionRate
import kotlin.test.*

/** Frozen FPH supporting slices only. No test below qualifies native approval history. */
class FinitePurchaseHistoryPlannerSupportingTest {
    private val inputs = FinitePurchaseHistoryPlannerInputs

    @Test
    fun fph008FiniteC24Recognizes22AndRetainsTheFrozen5000Remainder() {
        val before = inputs.c24()
        val order = (checkNotNull(before.order).value.body as PurchasingBody.Order).value
        val goods = order.lines.single().goods
        assertEquals(Quantity(inputs.cartonUnit, 2, 0), goods.quantity)
        assertEquals(UnitConversionRate.ratio(12, 1), goods.units.rate)
        assertEquals(inputs.each(24), goods.baseQuantity())
        assertEquals(inputs.money(60000), order.total())
        assertEquals(1894701600000L, order.validFrom.value.toEpochMilliseconds())
        assertEquals(1894701660000L, checkNotNull(order.validUntil).value.toEpochMilliseconds())
        assertEquals(inputs.documentDigest, sha256(inputs.documentText.encodeToByteArray()))

        val proposed = inputs.propose(before)
        val result = proposed.result()
        val recognized = proposed.recognition(before)
        val progress = recognized.progress.value.body as PurchasingBody.Progress
        val orderBilled = proposed.row(LocalPurchasingProfile.ORDER_BILLED).value.body as PurchasingBody.OrderBilled
        val receiptBilled = proposed.row(LocalPurchasingProfile.RECEIPT_BILLED).value.body as PurchasingBody.ReceiptBilled
        val payable = proposed.row(LocalPurchasingProfile.PAYABLE).value.body as PurchasingBody.Payable
        assertEquals("purchasing.captured_invoice_matched_recognized", result.code)
        assertEquals(inputs.each(22), result.quantity)
        assertEquals(inputs.money(60000), result.originalInvoiceClaim)
        assertEquals(inputs.money(55000), result.net)
        assertEquals(inputs.money(0), result.tax)
        assertEquals(inputs.each(22), progress.lines.single().quantity)
        assertEquals(inputs.each(22), orderBilled.quantity)
        assertEquals(inputs.each(22), receiptBilled.quantity)
        assertEquals(inputs.money(55000), payable.net)
        assertEquals(inputs.money(0), payable.tax)
        val due = checkNotNull(purchasingDue(recognized))
        assertEquals(inputs.money(55000), due.recognized)
        assertEquals(inputs.money(55000), due.remainingDue)
        assertEquals(inputs.money(0), due.appliedCredit)
        assertEquals(inputs.money(0), due.allocatedPayment)
        assertEquals(inputs.money(0), recognized.settlements.reservedTotal)
        assertEquals(inputs.money(5000), before.claim.total() - due.recognized)
        assertEquals(inputs.each(2), before.claim.lines.single().goods.baseQuantity() - progress.lines.single().quantity)
        assertEquals(inputs.each(0), checkNotNull(before.receipt).acceptedQuantity - receiptBilled.quantity)
        assertFalse(purchasingCaptureSettlementEligible(recognized))
        assertSame(before.invoice, recognized.invoice)
        assertSame(before.order, recognized.order)
        assertEquals(inputs.validUntil, order.validUntil)
    }

    @Test
    fun fph008M1ProposalHasOnlyCombinedFinancialChangesAndNoPhysicalOrApprovalRewrite() {
        val before = inputs.c24()
        val invoiceBytes = before.invoice.row.payload
        val orderBytes = checkNotNull(before.order).row.payload
        val receipt = before.receipt
        val proposed = inputs.propose(before)
        assertEquals(listOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.ORDER_MATCHES,
            LocalPurchasingProfile.PROGRESS, LocalPurchasingProfile.ORDER_BILLED, LocalPurchasingProfile.RECEIPT_BILLED,
            LocalPurchasingProfile.PAYABLE), proposed.outcome.changes.map { it.key.kind })
        assertEquals(1, proposed.rows.count { it.row.key.kind == LocalPurchasingProfile.PAYABLE })
        assertEquals(4, proposed.outcome.claims.size)
        assertTrue(proposed.outcome.jobs.isEmpty())
        assertNull(proposed.outcome.mustCommitBefore) // Matching does not renew the old approval window.
        assertEquals(invoiceBytes, before.invoice.row.payload)
        assertEquals(orderBytes, checkNotNull(before.order).row.payload)
        assertSame(receipt, before.receipt)
        val event = proposed.row(LocalPurchasingProfile.MATCH).value.body as PurchasingBody.CapturedMatch
        assertEquals(checkNotNull(before.document.source), event.originalSource)
        assertEquals(before.invoice.value.producer.operationId, event.invoiceOperationId)
        assertEquals(checkNotNull(before.order).value.producer.operationId, event.orderOperationId)
    }

    @Test
    fun fph009MvCapacity23Against22AcceptedUnitsProducesNoEffects() {
        val before = inputs.c24()
        val proposedRows = mutableListOf<PurchasingRow>()
        assertFailsWith<PurchaseRejected> {
            inputs.propose(before, inputs.request(before, quantity = 23)) { proposedRows += it }
        }
        assertTrue(proposedRows.isEmpty())
        assertNull(before.recognized)
        assertNull(before.receiptBilling)
        assertEquals(inputs.each(22), checkNotNull(before.receipt).acceptedQuantity)
        assertEquals(inputs.money(60000), before.claim.total())
    }

    @Test
    fun fph006C24RequirementBindsTheWhole60000ClaimNotThe55000Increment() {
        val before = inputs.c24()
        val request = inputs.request(before)
        val requirement = purchasingCapturedMatchRequirementV2(inputs.scope, before.claim.id, before.invoice.row.revision,
            inputs.operation(4), inputs.inputDigest, request.reasonCode, purchasingInvoiceClaim(before.invoice).total())
        assertEquals(SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE, requirement.permission.action)
        assertEquals(PermissionVerb.PERFORM, requirement.permission.verb)
        assertEquals(LimitUse.Monetary(inputs.money(60000)), requirement.limitUse)
        assertEquals(inputs.scope, requirement.scope)
        assertEquals(before.claim.id, checkNotNull(requirement.binding).documentId)
        assertEquals(before.invoice.row.revision, checkNotNull(requirement.binding).revision)
        assertEquals(inputs.operation(4), checkNotNull(requirement.binding).operationId)
        assertEquals(inputs.inputDigest, checkNotNull(requirement.binding).payloadDigest)
        assertEquals(request.reasonCode, checkNotNull(requirement.binding).reasonCode)
        assertEquals(inputs.money(55000), inputs.propose(before, request).slice.net)
        // Actual exact-limit/insufficient-limit/current-authority decisions remain unbound.
    }

    @Test
    fun fph007NonExpiringC24KeepsTheSamePlannerRecognitionWithoutADeadline() {
        val before = inputs.c24(until = null)
        val order = (checkNotNull(before.order).value.body as PurchasingBody.Order).value
        assertNull(order.validUntil)
        val proposed = inputs.propose(before)
        assertEquals(inputs.money(55000), proposed.result().net)
        assertEquals(inputs.money(0), proposed.result().tax)
        assertEquals(inputs.money(60000), proposed.result().originalInvoiceClaim)
        assertNull(proposed.outcome.mustCommitBefore)
        assertEquals(inputs.money(5000), before.claim.total() - checkNotNull(purchasingDue(proposed.recognition(before))).recognized)
        // This planner comparison is not old-format or native non-expiring history verification.
    }

    @Test
    fun fph008M1AlreadyUsedMatchIdentityCannotCreateAnotherPlannerProposal() {
        val before = inputs.c24()
        val request = inputs.request(before)
        val original = inputs.propose(before, request)
        val recognized = original.recognition(before)
        val duplicateCut = before.copy(recognized = recognized, orderMatches = original.row(LocalPurchasingProfile.ORDER_MATCHES),
            orderBilling = original.row(LocalPurchasingProfile.ORDER_BILLED), receiptBilling = original.row(LocalPurchasingProfile.RECEIPT_BILLED),
            previousMatch = original.row(LocalPurchasingProfile.MATCH))
        val proposedRows = mutableListOf<PurchasingRow>()
        assertFailsWith<PurchaseRejected> { inputs.propose(duplicateCut, request) { proposedRows += it } }
        assertTrue(proposedRows.isEmpty())
        assertEquals(inputs.money(55000), checkNotNull(purchasingDue(recognized)).recognized)
        assertEquals(inputs.each(22), (recognized.progress.value.body as PurchasingBody.Progress).lines.single().quantity)
        // A kernel retry must recover its original result; this is only the no-second-proposal guard.
    }
}

package com.ukkera.pos.catalog.store.independent.finitepurchasehistoryv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlin.test.*

/** Frozen FPH v1.0.0 planner support only; no native originals, authority or durable commit. */
class PurchaseDirectBaseMatchSupportingTest {
    private val inputs = FinitePurchaseHistoryPlannerInputs

    @Test
    fun fph008011C22Full22Recognizes55000AndCannotBeProposedTwice() {
        val before = c22()
        val request = inputs.request(before)
        val proposed = inputs.propose(before, request)
        val after = advance(before, proposed)
        assertIncrement(proposed, 22, 55000)
        assertRecognition(after, 22, 55000, 0, 0, 1, settlementEligible = true)
        assertOnlyFinancialProposal(before, after, proposed)

        // Used identity and a distinct full candidate cannot create another proposal.
        // These are serial planner guards, not kernel retry recovery or a concurrent run.
        assertNoProposal(after.copy(previousMatch = proposed.row(LocalPurchasingProfile.MATCH)), request)
        val competing = inputs.request(before).copy(matchId = id(1523))
        assertNoProposal(after, competing)
        assertNoRemainingSlice(after, competing)
        assertRecognition(after, 22, 55000, 0, 0, 1, settlementEligible = true)
    }

    @Test
    fun fph008011C22TwelveThenTenAdds30000Then25000ToOnePayable() {
        val before = c22()
        val firstRequest = inputs.request(before, 12).copy(matchId = id(1512))
        val first = inputs.propose(before, firstRequest)
        val r12 = advance(before, first)
        assertIncrement(first, 12, 30000)
        assertRecognition(r12, 12, 30000, 25000, 10, 1, settlementEligible = false)
        assertOnlyFinancialProposal(before, r12, first)

        // RR-TWO-12 candidates retain the same initial heads; no serial order can add both.
        val competing = inputs.request(before, 12).copy(matchId = id(1513))
        assertNoProposal(r12, competing)
        assertNoRemainingSlice(r12, competing)
        val secondRequest = inputs.request(r12, 10).copy(matchId = id(1510))
        assertNotEquals(firstRequest.matchId, secondRequest.matchId)
        assertEquals(checkNotNull(r12.recognized).progress.row.revision, secondRequest.expectedProgressRevision)
        assertEquals(checkNotNull(r12.orderMatches).row.revision, secondRequest.expectedOrderMatchRevision)
        assertEquals(checkNotNull(r12.orderBilling).row.revision, secondRequest.expectedOrderBillingRevision)
        assertEquals(checkNotNull(r12.receiptBilling).row.revision, secondRequest.expectedReceiptBillingRevision)
        assertEquals(checkNotNull(checkNotNull(r12.recognized).payable).row.revision, secondRequest.expectedPayableRevision)

        val second = inputs.propose(r12, secondRequest)
        val r22 = advance(r12, second)
        assertIncrement(second, 10, 25000)
        assertRecognition(r22, 22, 55000, 0, 0, 2, settlementEligible = true)
        assertOnlyFinancialProposal(r12, r22, second)
        assertEquals(first.row(LocalPurchasingProfile.PAYABLE).row.key, second.row(LocalPurchasingProfile.PAYABLE).row.key)
        assertEquals(listOf(firstRequest.matchId, secondRequest.matchId), r22.progress.matchIds)
        assertSame(before.invoice, r22.invoice)
        assertSame(before.order, r22.order)
        assertSame(before.receipt, r22.receipt)
    }

    @Test
    fun fph009MvPriceAndMvTaxPreserveTheirActualClaimsWithoutProposedChanges() {
        for (fixture in listOf(FrozenClaim.PRICE, FrozenClaim.TAX)) {
            val before = c22(fixture)
            val invoiceBytes = before.invoice.row.payload
            val orderBytes = checkNotNull(before.order).row.payload
            val receipt = before.receipt
            assertNoProposal(before, inputs.request(before))
            assertEquals(inputs.money(55022), before.claim.total())
            assertEquals(invoiceBytes, before.invoice.row.payload)
            assertEquals(orderBytes, checkNotNull(before.order).row.payload)
            assertSame(receipt, before.receipt)
            assertNull(before.recognized)
            assertNull(before.receiptBilling)
            assertEquals(inputs.each(22), checkNotNull(before.receipt).acceptedQuantity)
        }
    }

    private fun assertIncrement(proposed: FinitePurchaseHistoryPlannerProposal, quantity: Long, net: Long) {
        val result = proposed.result()
        assertEquals("purchasing.captured_invoice_matched_recognized", result.code)
        assertEquals(inputs.money(55000), result.originalInvoiceClaim)
        assertEquals(inputs.each(quantity), result.quantity)
        assertEquals(inputs.money(net), result.net)
        assertEquals(inputs.money(0), result.tax)
        assertEquals(inputs.money(net), proposed.slice.net)
        assertEquals(inputs.money(0), proposed.slice.tax)
    }

    private fun assertRecognition(cut: PurchasingCapturedMatchCutV2, quantity: Long, net: Long,
        unmatched: Long, remainingUnits: Long, matchCount: Int, settlementEligible: Boolean) {
        val recognized = checkNotNull(cut.recognized)
        val progress = cut.progress
        val orderBilled = checkNotNull(cut.orderBilling).value.body as PurchasingBody.OrderBilled
        val receiptBilled = checkNotNull(cut.receiptBilling).value.body as PurchasingBody.ReceiptBilled
        val payable = checkNotNull(recognized.payable).value.body as PurchasingBody.Payable
        val orderMatches = checkNotNull(cut.orderMatches).value.body as PurchasingBody.OrderMatches
        val due = checkNotNull(purchasingDue(recognized))
        assertEquals(inputs.each(quantity), progress.lines.single().quantity)
        assertEquals(inputs.each(quantity), orderBilled.quantity)
        assertEquals(inputs.each(quantity), receiptBilled.quantity)
        assertEquals(inputs.money(net), progress.lines.single().net)
        assertEquals(inputs.money(net), orderBilled.net)
        assertEquals(inputs.money(net), receiptBilled.net)
        assertEquals(inputs.money(net), payable.net)
        assertEquals(inputs.money(0), progress.lines.single().tax)
        assertEquals(inputs.money(0), orderBilled.tax)
        assertEquals(inputs.money(0), receiptBilled.tax)
        assertEquals(inputs.money(0), payable.tax)
        assertEquals(inputs.money(net), due.recognized)
        assertEquals(inputs.money(net), due.remainingDue)
        assertEquals(inputs.money(0), due.appliedCredit)
        assertEquals(inputs.money(0), due.allocatedPayment)
        assertEquals(inputs.money(0), recognized.settlements.reservedTotal)
        assertEquals(inputs.money(unmatched), cut.claim.total() - due.recognized)
        assertEquals(inputs.each(remainingUnits), cut.claim.lines.single().goods.baseQuantity() - progress.lines.single().quantity)
        assertEquals(inputs.each(remainingUnits), checkNotNull(cut.receipt).acceptedQuantity - receiptBilled.quantity)
        assertEquals(matchCount, recognized.matches.size)
        assertEquals(matchCount, progress.matchIds.distinct().size)
        assertEquals(progress.matchIds, orderMatches.matchIds)
        assertEquals(progress.matchIds, orderBilled.matchIds)
        assertEquals(progress.matchIds, receiptBilled.matchIds)
        assertEquals(progress.matchIds, payable.matchIds)
        assertEquals(cut.claim.id, payable.invoiceId)
        assertEquals(settlementEligible, purchasingCaptureSettlementEligible(recognized))
        assertSame(cut.invoice, recognized.invoice)
        assertSame(cut.order, recognized.order)
    }

    private fun assertOnlyFinancialProposal(before: PurchasingCapturedMatchCutV2,
        after: PurchasingCapturedMatchCutV2, proposed: FinitePurchaseHistoryPlannerProposal) {
        assertEquals(listOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.ORDER_MATCHES,
            LocalPurchasingProfile.PROGRESS, LocalPurchasingProfile.ORDER_BILLED,
            LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE), proposed.outcome.changes.map { it.key.kind })
        assertEquals(1, proposed.rows.count { it.row.key.kind == LocalPurchasingProfile.PAYABLE })
        assertTrue(proposed.outcome.jobs.isEmpty())
        assertNull(proposed.outcome.mustCommitBefore)
        assertSame(before.original, after.original)
        assertEquals(before.invoice.row.payload, after.invoice.row.payload)
        assertEquals(checkNotNull(before.order).row.payload, checkNotNull(after.order).row.payload)
        assertSame(before.document, after.document)
        assertSame(before.receipt, after.receipt)
        val receipt = checkNotNull(after.receipt)
        assertEquals(inputs.each(22), receipt.acceptedQuantity)
        assertEquals(inputs.money(55000), receipt.originalOwnedValue)
        assertEquals(inputs.each(24), checkNotNull(receipt.allowance).committedQuantity)
        assertEquals(inputs.each(22), checkNotNull(receipt.allowance).acceptedQuantity)
        assertEquals(inputs.money(60000), checkNotNull(receipt.allowance).committedOwnedValue)
        assertEquals(inputs.validUntil, (checkNotNull(after.order).value.body as PurchasingBody.Order).value.validUntil)
    }

    private fun assertNoProposal(cut: PurchasingCapturedMatchCutV2, request: LocalMatchCapturedSupplierInvoiceV2) {
        val rows = mutableListOf<PurchasingRow>()
        assertFailsWith<PurchaseRejected> { inputs.propose(cut, request) { rows += it } }
        assertTrue(rows.isEmpty())
    }

    private fun assertNoRemainingSlice(cut: PurchasingCapturedMatchCutV2, request: LocalMatchCapturedSupplierInvoiceV2) {
        // The same candidate's source selection also sees exhausted/insufficient capacity.
        assertFailsWith<PurchaseRejected> { purchasingCapturedMatchSliceV2(request.sourceQuery(), cut) }
    }

    private fun advance(before: PurchasingCapturedMatchCutV2,
        proposed: FinitePurchaseHistoryPlannerProposal): PurchasingCapturedMatchCutV2 {
        val increment = proposed.recognition(before)
        return before.copy(recognized = increment.copy(matches = before.recognized?.matches.orEmpty() + increment.matches),
            orderMatches = proposed.row(LocalPurchasingProfile.ORDER_MATCHES),
            orderBilling = proposed.row(LocalPurchasingProfile.ORDER_BILLED),
            receiptBilling = proposed.row(LocalPurchasingProfile.RECEIPT_BILLED), previousMatch = null)
    }

    /** Fresh synthetic C22 carrier; the template PO/receipt are never converted or rewritten. */
    private fun c22(fixture: FrozenClaim = FrozenClaim.C22): PurchasingCapturedMatchCutV2 {
        val template = inputs.c24()
        val ordered = (checkNotNull(template.order).value.body as PurchasingBody.Order).value.lines.single().goods
        assertEquals(Quantity(inputs.cartonUnit, 2, 0), ordered.quantity)
        assertEquals(UnitConversionRate.ratio(12, 1), ordered.units.rate)
        assertEquals(UnitPrice.parse(Currency.EGP, "300"), ordered.unitPrice)
        assertEquals(inputs.each(24), ordered.baseQuantity())
        assertEquals(inputs.money(60000), ordered.net)
        val direct = ordered.copy(units = PurchaseUnitsV1(ordered.units.productId, inputs.eachUnit, inputs.eachUnit,
            0, null, null, UnitConversionRate.ratio(1, 1)), quantity = inputs.each(22),
            unitPrice = UnitPrice.parse(Currency.EGP, fixture.price), net = inputs.money(fixture.net),
            taxAmount = inputs.money(fixture.tax))
        val claim = template.claim.copy(id = id(1300 + fixture.ordinal), reference = fixture.reference,
            sourceEvidenceId = id(1400 + fixture.ordinal),
            lines = listOf(template.claim.lines.single().copy(lineId = id(1450 + fixture.ordinal), goods = direct)))
        val body = (template.invoice.value.body as PurchasingBody.InvoiceCapture).copy(value = claim,
            normalizedReference = normalizedPurchaseReference(claim.reference))
        val record = template.invoice.value.copy(id = claim.id, body = body)
        val bytes = purchasingEncode(record, PurchasingRecord.serializer())
        val invoice = PurchasingRow(StoredAggregate(AggregateKey(LocalPurchasingProfile.INVOICE, claim.id),
            template.invoice.row.revision, template.invoice.row.schema, bytes, sha256(bytes.copyBytes())), record)
        val digest = PayloadDigest(fixture.digest)
        assertEquals(digest, sha256(fixture.documentText().encodeToByteArray()))
        assertEquals(inputs.each(22), direct.quantity)
        assertEquals(inputs.each(22), direct.baseQuantity())
        assertEquals(inputs.money(fixture.net + fixture.tax), claim.total())
        return template.copy(original = PurchasingInvoiceCaptureCut(invoice, null),
            document = template.document.copy(source = checkNotNull(template.document.source).copy(id = claim.sourceEvidenceId, digest = digest)))
    }

    private enum class FrozenClaim(val suffix: String, val price: String, val unitNet: Long,
        val net: Long, val unitTax: Long, val tax: Long, val digest: String) {
        C22("A", "25", 2500, 55000, 0, 0, "7e1ea5369c3c24b66fe6d1a03e927e1072ae1833623d9ca4eba2e468be7897ce"),
        PRICE("P", "25.01", 2501, 55022, 0, 0, "3569cb36919f8122d3d91f864ddf0677d3dd2a088150ea8c2f649ac9dbd5c38d"),
        TAX("T", "25", 2500, 55000, 1, 22, "b2b730d7af3e90495f5bbf35cb8705b0692740357a19edce7df68d55f235df84");

        val reference: String get() = "  SYNTH-INV-$suffix/01  "
        fun documentText(): String = "SYNTHETIC ONLY\nSupplier: SUPPLIER-A\nReference: $reference\n" +
            "Date: 2030-01-15\nGoods: SKU-A / VARIANT-A\nBase quantity: 22\n" +
            "Unit net EGP minor: $unitNet\nUnit tax EGP minor: $unitTax\nNet EGP minor: $net\n" +
            "Tax EGP minor: $tax\nClaim EGP minor: ${net + tax}\n"
    }

    private fun id(value: Int): DomainId = DomainId("00000000-0000-4000-8000-" + value.toString().padStart(12, '0'))
}

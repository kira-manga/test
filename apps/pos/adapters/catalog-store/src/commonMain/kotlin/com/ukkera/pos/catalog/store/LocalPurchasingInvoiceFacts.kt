package com.ukkera.pos.catalog.store

import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.*

/** Format-aware immutable claim facts, not a converted legacy invoice or an inferred PO association. */
internal class PurchasingInvoiceClaim(
    val id: DomainId, val supplierId: DomainId, val reference: String,
    val invoiceDate: BusinessDate, val dueDate: BusinessDate, val sourceEvidenceId: DomainId,
    val lines: List<PurchasingInvoiceClaimLine>, val charges: List<PurchaseChargeV1>, val captured: Boolean,
    val originalOrderId: DomainId?,
) {
    fun total(): Money = (lines.map { it.goods.total() } + charges.map { it.total() }).fold(purchaseZero(), Money::plus)
}

internal class PurchasingInvoiceClaimLine(val lineId: DomainId, val allocationId: DomainId?, val goods: PurchaseGoodsV1)

internal fun purchasingInvoiceClaim(row: PurchasingRow): PurchasingInvoiceClaim = when (val body = row.value.body) {
    is PurchasingBody.Invoice -> purchasingInvoiceClaim(body.value)
    is PurchasingBody.InvoiceCapture -> body.value.let { value -> PurchasingInvoiceClaim(value.id, value.supplierId,
        value.reference, value.invoiceDate, value.dueDate, value.sourceEvidenceId,
        value.lines.map { PurchasingInvoiceClaimLine(it.lineId, it.internalAllocationId, it.goods) }, value.charges, true,
        value.internalOrderLink?.orderId) }
    else -> error("Purchasing invoice claim format required")
}

internal fun purchasingInvoiceClaim(value: PurchaseInvoiceV1): PurchasingInvoiceClaim = PurchasingInvoiceClaim(value.id,
    value.supplierId, value.reference, value.invoiceDate, value.dueDate, value.sourceEvidenceId,
    value.lines.map { PurchasingInvoiceClaimLine(it.lineId, it.allocationId, it.goods) }, value.charges, false, value.orderId)

/** Common event facts retain the actual versioned body; no synthetic v1 request is constructed. */
internal class PurchasingMatchFact(val body: PurchasingBody) {
    val legacy: PurchasingBody.Match? = body as? PurchasingBody.Match
    val captured: PurchasingBody.CapturedMatch? = body as? PurchasingBody.CapturedMatch
    init { check((legacy == null) != (captured == null)) }
    val request: PurchasingMatchRequestFact = legacy?.request?.let { request -> PurchasingMatchRequestFact(request.matchId,
        request.invoiceId, request.invoiceLineId, request.expectedInvoiceRevision, request.expectedProgressRevision,
        request.expectedOrderMatchRevision, request.expectedOrderBillingRevision, request.expectedPayableRevision,
        request.receiptId, request.receiptLineId, request.expectedReceiptRevision, request.expectedReceiptBillingRevision,
        request.quantity, request.reasonCode) } ?: checkNotNull(captured).request.let { request -> PurchasingMatchRequestFact(request.matchId,
        request.invoiceId, request.invoiceLineId, request.expectedInvoiceRevision, request.expectedProgressRevision,
        request.expectedOrderMatchRevision, request.expectedOrderBillingRevision, request.expectedPayableRevision,
        request.receiptId, request.receiptLineId, request.expectedReceiptRevision, request.expectedReceiptBillingRevision,
        request.quantity, request.reasonCode) }
    val orderId: DomainId get() = legacy?.orderId ?: checkNotNull(captured).request.orderId
    val allocationId: DomainId get() = legacy?.allocationId ?: checkNotNull(captured).request.allocationId
    val receipt: PurchasingReceiptSnapshot get() = legacy?.receipt ?: checkNotNull(captured).receipt
    val invoiceBefore: Quantity get() = legacy?.invoiceBefore ?: checkNotNull(captured).invoiceBefore
    val orderBefore: Quantity get() = legacy?.orderBefore ?: checkNotNull(captured).orderBefore
    val receiptBefore: Quantity get() = legacy?.receiptBefore ?: checkNotNull(captured).receiptBefore
    val net: Money get() = legacy?.net ?: checkNotNull(captured).net
    val tax: Money get() = legacy?.tax ?: checkNotNull(captured).tax
    val uncontested: PurchaseUncontestedAmountsV1? get() = legacy?.uncontested
}

internal class PurchasingMatchRequestFact(
    val matchId: DomainId, val invoiceId: DomainId, val invoiceLineId: DomainId, val expectedInvoiceRevision: Revision,
    val expectedProgressRevision: Revision?, val expectedOrderMatchRevision: Revision, val expectedOrderBillingRevision: Revision,
    val expectedPayableRevision: Revision?, val receiptId: DomainId, val receiptLineId: DomainId, val expectedReceiptRevision: Revision,
    val expectedReceiptBillingRevision: Revision?, val quantity: Quantity, val reasonCode: String,
)

internal fun PurchasingInvoiceCut.allocationFor(lineId: DomainId): DomainId? {
    val claim = purchasingInvoiceClaim(invoice)
    val line = claim.lines.single { it.lineId == lineId }
    val selected = matches.filter { it.request.invoiceLineId == lineId }.map { it.allocationId }.distinct()
    check(selected.size <= 1)
    if (selected.isNotEmpty() && line.allocationId != null) check(selected.single() == line.allocationId)
    return selected.singleOrNull() ?: line.allocationId
}

internal fun purchasingProgressOffset(invoice: PurchasingRow): Long = if (invoice.value.body is PurchasingBody.InvoiceCapture) 0L else 1L

/** No policy permits settlement of the unresolved captured remainder in this exact-match slice. */
internal fun purchasingCaptureSettlementEligible(cut: PurchasingInvoiceCut): Boolean {
    return purchasingCaptureSettlementPrefixEligible(cut.invoice, cut.matches) &&
        (!purchasingInvoiceClaim(cut.invoice).captured ||
            (cut.payable?.value?.body as? PurchasingBody.Payable)?.let { it.net + it.tax == purchasingInvoiceClaim(cut.invoice).total() } == true)
}

/** A historical cash/advance basis must already have resolved every captured goods
 * quantity, including zero-price lines. Zero-value unrecognized charges also block it. */
internal fun purchasingCaptureSettlementPrefixEligible(invoice: PurchasingRow, matches: List<PurchasingMatchFact>): Boolean {
    val claim = purchasingInvoiceClaim(invoice)
    if (!claim.captured) return true
    return claim.charges.isEmpty() && claim.lines.all { line ->
        matches.filter { it.request.invoiceLineId == line.lineId }.fold(line.goods.baseQuantity().copy(scaled = 0)) {
            total, event -> total + event.request.quantity
        } == line.goods.baseQuantity()
    } && matches.fold(purchaseZero()) { total, event -> total + event.net + event.tax } == claim.total()
}

package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.purchaseZero
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*

/** Closed whole AFTER shape. Actual immutable invoice/document/PO and receipt sources,
 * amount-bound authority and every BEFORE head are separately joined by native history. */
internal fun requirePurchasingCapturedMatchProducerV2(evidence: RetainedCommandEvidence,
    record: (String, DomainId, Revision) -> PurchasingRecord, acquire: (String, DomainId) -> Unit,
): StoredBytes {
    check(evidence.command == LocalPurchasingProfile.MATCH_CAPTURED_INVOICE && evidence.nativeOriginalProvenance == null)
    val request = LocalPurchasingCodec.decodeCapturedMatch(evidence.normalized)
    val event = record(LocalPurchasingProfile.MATCH, request.matchId, Revision(1)).body as? PurchasingBody.CapturedMatch
        ?: error("Captured match producer lost its actual event format")
    check(event.request == request && event.net.currency == Currency.EGP && event.tax.currency == Currency.EGP &&
        event.net.minor >= 0 && event.tax.minor >= 0 && event.receipt.receiptId == request.receiptId &&
        event.receipt.lineId == request.receiptLineId && event.receipt.source.revision == request.expectedReceiptRevision &&
        event.receipt.allocationId == request.allocationId && event.receipt.basisId == request.orderId)
    check(event.originalSource.kind == LocalPurchasingDocumentProfileV1.ORIGINAL && event.originalSource.id == request.originalDocumentId &&
        event.originalSource.revision == request.expectedOriginalRevision && event.originalSource.operationId != evidence.operationId)
    check(event.invoiceSource.kind == LocalPurchasingProfile.INVOICE && event.invoiceSource.id == request.invoiceId &&
        event.invoiceSource.revision == request.expectedInvoiceRevision && event.invoiceOperationId != evidence.operationId &&
        event.orderSource.kind == LocalPurchasingProfile.ORDER && event.orderSource.id == request.orderId &&
        event.orderSource.revision == request.expectedOrderRevision && event.orderOperationId != evidence.operationId)
    PayloadDigest(event.invoiceSource.digest); PayloadDigest(event.orderSource.digest)
    val progressRevision = (request.expectedProgressRevision ?: Revision(0)).next()
    val payableRevision = (request.expectedPayableRevision ?: Revision(0)).next()
    // The explicit CAPTURE_MATCH_V2 format has no empty pre-recognition progress row.
    check(progressRevision == payableRevision)
    val progress = record(LocalPurchasingProfile.PROGRESS, request.invoiceId, progressRevision).body as PurchasingBody.Progress
    val orderMatches = record(LocalPurchasingProfile.ORDER_MATCHES, request.orderId, request.expectedOrderMatchRevision.next()).body as PurchasingBody.OrderMatches
    val ordered = record(LocalPurchasingProfile.ORDER_BILLED, request.allocationId, request.expectedOrderBillingRevision.next()).body as PurchasingBody.OrderBilled
    val receipt = record(LocalPurchasingProfile.RECEIPT_BILLED, event.receipt.sourceId,
        (request.expectedReceiptBillingRevision ?: Revision(0)).next()).body as PurchasingBody.ReceiptBilled
    val payable = record(LocalPurchasingProfile.PAYABLE, request.invoiceId, payableRevision).body as PurchasingBody.Payable
    check(progress.invoiceId == request.invoiceId && ordered.orderId == request.orderId && ordered.allocationId == request.allocationId &&
        receipt.orderId == request.orderId && receipt.receiptId == request.receiptId && receipt.lineId == request.receiptLineId &&
        receipt.sourceId == event.receipt.sourceId && receipt.allocationId == request.allocationId && payable.invoiceId == request.invoiceId &&
        payable.orderId == request.orderId && payable.supplierId == event.receipt.supplierId && payable.status == PurchasingPayableStatus.UNSETTLED &&
        orderMatches.orderId == request.orderId)
    for (ids in listOf(progress.matchIds, ordered.matchIds, receipt.matchIds, payable.matchIds, orderMatches.matchIds))
        check(ids.size in 1..LocalPurchasingProfile.MAX_MATCHES && ids.last() == request.matchId && ids.distinct().size == ids.size)
    check(progress.matchIds.size.toLong() == progressRevision.value && progress.matchIds == payable.matchIds &&
        progress.lines.single { it.lineId == request.invoiceLineId }.quantity == event.invoiceBefore + request.quantity &&
        ordered.quantity == event.orderBefore + request.quantity && receipt.quantity == event.receiptBefore + request.quantity &&
        payable.net == progress.lines.fold(purchaseZero()) { sum, line -> sum + line.net } &&
        payable.tax == progress.lines.fold(purchaseZero()) { sum, line -> sum + line.tax })
    if (request.expectedProgressRevision == null) check(progress.matchIds == listOf(request.matchId) &&
        progress.lines.all { line -> if (line.lineId == request.invoiceLineId) line.net == event.net && line.tax == event.tax
            else line.quantity.scaled == 0L && line.net == purchaseZero() && line.tax == purchaseZero() })
    acquire(LocalPurchasingProfile.MATCH, request.matchId)
    if (request.expectedProgressRevision == null) acquire(LocalPurchasingProfile.PROGRESS, request.invoiceId)
    if (request.expectedReceiptBillingRevision == null) acquire(LocalPurchasingProfile.RECEIPT_BILLED, event.receipt.sourceId)
    if (request.expectedPayableRevision == null) acquire(LocalPurchasingProfile.PAYABLE, request.invoiceId)
    val result = LocalPurchasingCodec.capturedMatchResult(evidence.result)
    val expected = LocalCapturedSupplierInvoiceMatchResultV2(LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS,
        request.matchId, request.invoiceId, request.expectedInvoiceRevision, result.originalInvoiceClaim,
        Revision(1), progressRevision, payableRevision, request.quantity, event.net, event.tax)
    check(result == expected && (event.net + event.tax).minor <= result.originalInvoiceClaim.minor)
    return LocalPurchasingCodec.capturedMatchResult(expected)
}

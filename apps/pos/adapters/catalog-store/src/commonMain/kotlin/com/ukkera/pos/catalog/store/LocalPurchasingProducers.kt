package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*

internal fun purchasingNormalize(key: CommandKey, bytes: StoredBytes): StoredBytes {
    require(bytes.size <= 32_768)
    val normalized = when (key) {
        LocalPurchasingProfile.APPROVE_ORDER -> LocalPurchasingCodec.approve(purchasingDecode(bytes, LocalApprovePurchaseOrderV1.serializer()))
        LocalPurchasingProfile.RECORD_INVOICE -> LocalPurchasingCodec.invoice(purchasingDecode(bytes, LocalRecordSupplierInvoiceV1.serializer()))
        LocalPurchasingProfile.CAPTURE_INVOICE -> LocalPurchasingCodec.captureInvoice(LocalPurchasingCodec.decodeCaptureInvoice(bytes))
        LocalPurchasingProfile.MATCH_INVOICE -> LocalPurchasingCodec.match(purchasingDecode(bytes, LocalMatchSupplierInvoiceV1.serializer()))
        LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> LocalPurchasingCodec.matchUncontested(LocalPurchasingCodec.decodeUncontestedMatch(bytes))
        LocalPurchasingProfile.MATCH_CAPTURED_INVOICE -> LocalPurchasingCodec.matchCapturedInvoice(LocalPurchasingCodec.decodeCapturedMatch(bytes))
        LocalPurchasingProfile.RECORD_CREDIT -> LocalPurchasingCodec.credit(LocalPurchasingCodec.decodeCredit(bytes))
        LocalSupplierCashProfile.COMMAND -> LocalSupplierCashCodec.normalize(bytes)
        LocalSupplierSettlementProfile.ALLOCATE_ADVANCE -> purchasingEncode(LocalSupplierSettlementCodec.allocation(bytes), LocalAllocateSupplierAdvanceV1.serializer())
        else -> error("Unsupported purchasing command")
    }
    require(normalized.size <= 32_768)
    return normalized
}

/** Closed original producer shape, complete claims/writes/result/jobs; no namespace-only trust. */
internal fun requirePurchasingProducer(evidence: RetainedCommandEvidence) {
    if (evidence.command in setOf(LocalSupplierCashProfile.COMMAND, LocalSupplierSettlementProfile.ALLOCATE_ADVANCE)) {
        requireSupplierSettlementProducer(evidence)
        return
    }
    check(evidence.committed && purchasingNormalize(evidence.command, evidence.normalized) == evidence.normalized &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest && evidence.jobs.isEmpty())
    val records = evidence.aggregateWrites.filter { it.key.kind in purchasingKinds }.associate { write ->
        val value = decodePurchasingRecord(write.payload)
        check(write.schema == LocalPurchasingProfile.schema(write.key.kind) && write.key == purchasingKey(value.kind, value.id) &&
            write.storageGeneration == value.revision && value.scope == evidence.scope &&
            value.producer == PurchasingProducer(evidence.operationId, evidence.originalActorId, evidence.command.name, evidence.semanticDigest.value) &&
            value.recordedAt == evidence.recordedAt && value.policyRevision == evidence.policyRevision)
        write.key to value
    }
    val expectedKeys = mutableSetOf<AggregateKey>()
    val expectedClaims = mutableMapOf<ClaimKey, DomainId>()
    fun acquire(kind: String, id: DomainId) { expectedClaims[purchasingClaim(kind, id)] = id }
    fun record(kind: String, id: DomainId, revision: Revision): PurchasingRecord {
        val key = purchasingKey(kind, id)
        expectedKeys += key
        return records[key]?.also { check(it.revision == revision) } ?: error("Purchasing producer lost a row")
    }
    val result = when (evidence.command) {
        LocalPurchasingProfile.APPROVE_ORDER -> {
            val request = purchasingDecode(evidence.normalized, LocalApprovePurchaseOrderV1.serializer())
            val order = request.order
            val body = record(LocalPurchasingProfile.ORDER, order.id, Revision(1)).body as PurchasingBody.Order
            check(body.value == order && order.lines.all { it.goods.amountsAgree() } && body.normalizedReference == normalizedPurchaseReference(order.reference))
            val sources = (order.lines.map { purchasingKey(LocalConfigurationProfile.SKU, it.goods.skuId) } +
                order.lines.map { purchasingKey(LocalConfigurationProfile.WAREHOUSE, it.warehouseId) } +
                purchasingKey(LocalConfigurationProfile.SUPPLIER, order.supplierId)).toSet()
            check(body.sources.map { purchasingKey(it.kind, it.id) }.toSet() == sources && body.sources.size == sources.size)
            body.sources.forEach { check(it.revision.value >= 1); PayloadDigest(it.digest) }
            check(body.sources.single { it.kind == LocalConfigurationProfile.SUPPLIER }.revision == request.expectedSupplierRevision)
            acquire(LocalPurchasingProfile.ORDER, order.id)
            check(record(LocalPurchasingProfile.ORDER_MATCHES, order.id, Revision(1)).body == PurchasingBody.OrderMatches(order.id, emptyList()))
            acquire(LocalPurchasingProfile.ORDER_MATCHES, order.id)
            expectedClaims[purchasingReferenceClaim(evidence.scope, "order", order.supplierId, order.reference)] = order.id
            val basisKey = purchasingKey(LocalConfigurationProfile.BASIS, order.id)
            expectedKeys += basisKey
            val basis = evidence.aggregateWrites.single { it.key == basisKey }
            check(basis.storageGeneration == Revision(1) && basis.schema == LocalConfigurationProfile.schema(LocalConfigurationProfile.BASIS) &&
                basis.payload == encodeValue(ConfigurationRecord(evidence.scope, LocalConfigurationProfile.BASIS, order.id, Revision(0),
                    ConfigurationBody.Basis(purchasingBasis(order)), evidence.originalActorId, evidence.policyRevision, evidence.recordedAt), ConfigurationRecord.serializer()))
            for (line in order.lines) {
                val billed = record(LocalPurchasingProfile.ORDER_BILLED, line.allocationId, Revision(1)).body
                check(billed == PurchasingBody.OrderBilled(order.id, line.allocationId, line.goods.baseQuantity().copy(scaled = 0), purchaseZero(), purchaseZero(), emptyList()))
                acquire(LocalPurchasingProfile.ORDER_BILLED, line.allocationId)
                expectedClaims[ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}")] = order.id
            }
            purchasingResult("purchasing.order_approved", order.id, Revision(1))
        }
        LocalPurchasingProfile.RECORD_INVOICE -> {
            val request = purchasingDecode(evidence.normalized, LocalRecordSupplierInvoiceV1.serializer())
            val invoice = request.invoice
            val body = record(LocalPurchasingProfile.INVOICE, invoice.id, Revision(1)).body as PurchasingBody.Invoice
            check(body.value == invoice && body.normalizedReference == normalizedPurchaseReference(invoice.reference) &&
                body.orderSource.kind == LocalPurchasingProfile.ORDER && body.orderSource.id == invoice.orderId && body.orderSource.revision == request.expectedOrderRevision &&
                body.supplierSource.kind == LocalConfigurationProfile.SUPPLIER && body.supplierSource.id == invoice.supplierId && body.supplierSource.revision == request.expectedSupplierRevision)
            PayloadDigest(body.orderSource.digest); PayloadDigest(body.supplierSource.digest)
            check(record(LocalPurchasingProfile.PROGRESS, invoice.id, Revision(1)).body == purchasingInitialProgress(invoice))
            acquire(LocalPurchasingProfile.INVOICE, invoice.id); acquire(LocalPurchasingProfile.PROGRESS, invoice.id)
            expectedClaims[purchasingReferenceClaim(evidence.scope, "invoice", invoice.supplierId, invoice.reference)] = invoice.id
            purchasingResult("purchasing.invoice_recorded", invoice.id, Revision(1), Revision(1))
        }
        LocalPurchasingProfile.CAPTURE_INVOICE -> {
            val request = LocalPurchasingCodec.decodeCaptureInvoice(evidence.normalized)
            val invoice = request.invoice
            val body = record(LocalPurchasingProfile.INVOICE, invoice.id, Revision(1)).body as? PurchasingBody.InvoiceCapture
                ?: error("Purchasing capture producer lost its original format")
            requirePurchasingCaptureBodyV2(body, request)
            acquire(LocalPurchasingProfile.INVOICE, invoice.id)
            expectedClaims[purchasingReferenceClaim(evidence.scope, "invoice", invoice.supplierId, invoice.reference)] = invoice.id
            invoiceCaptureResult("purchasing.invoice_captured", invoice.id, Revision(1))
        }
        LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> {
            val reviewed = if (evidence.command == LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE)
                LocalPurchasingCodec.decodeUncontestedMatch(evidence.normalized) else null
            val request = reviewed?.match ?: purchasingDecode(evidence.normalized, LocalMatchSupplierInvoiceV1.serializer())
            val event = record(LocalPurchasingProfile.MATCH, request.matchId, Revision(1)).body as PurchasingBody.Match
            check(event.uncontested == reviewed?.amounts && (reviewed == null || event.net == reviewed.amounts.net && event.tax == reviewed.amounts.tax))
            check(event.request == request && event.net.minor >= 0 && event.tax.minor >= 0 && event.receipt.receiptId == request.receiptId &&
                event.receipt.lineId == request.receiptLineId && event.receipt.source.revision == request.expectedReceiptRevision)
            val progress = record(LocalPurchasingProfile.PROGRESS, request.invoiceId, request.expectedProgressRevision.next()).body as PurchasingBody.Progress
            val orderMatches = record(LocalPurchasingProfile.ORDER_MATCHES, event.orderId, request.expectedOrderMatchRevision.next()).body as PurchasingBody.OrderMatches
            val ordered = record(LocalPurchasingProfile.ORDER_BILLED, event.allocationId, request.expectedOrderBillingRevision.next()).body as PurchasingBody.OrderBilled
            val receipt = record(LocalPurchasingProfile.RECEIPT_BILLED, event.receipt.sourceId, (request.expectedReceiptBillingRevision ?: Revision(0)).next()).body as PurchasingBody.ReceiptBilled
            val payable = record(LocalPurchasingProfile.PAYABLE, request.invoiceId, (request.expectedPayableRevision ?: Revision(0)).next()).body as PurchasingBody.Payable
            check(progress.invoiceId == request.invoiceId && ordered.orderId == event.orderId && receipt.orderId == event.orderId &&
                receipt.receiptId == request.receiptId && receipt.lineId == request.receiptLineId && receipt.allocationId == event.allocationId &&
                payable.orderId == event.orderId && payable.supplierId == event.receipt.supplierId && payable.status == PurchasingPayableStatus.UNSETTLED)
            check(orderMatches.orderId == event.orderId)
            for (ids in listOf(progress.matchIds, ordered.matchIds, receipt.matchIds, payable.matchIds, orderMatches.matchIds)) {
                check(ids.size in 1..LocalPurchasingProfile.MAX_MATCHES && ids.last() == request.matchId && ids.distinct().size == ids.size)
            }
            check(progress.lines.single { it.lineId == request.invoiceLineId }.quantity == event.invoiceBefore + request.quantity &&
                ordered.quantity == event.orderBefore + request.quantity && receipt.quantity == event.receiptBefore + request.quantity &&
                progress.matchIds == payable.matchIds && payable.net == progress.lines.fold(purchaseZero()) { sum, it -> sum + it.net } &&
                payable.tax == progress.lines.fold(purchaseZero()) { sum, it -> sum + it.tax })
            acquire(LocalPurchasingProfile.MATCH, request.matchId)
            if (request.expectedReceiptBillingRevision == null) acquire(LocalPurchasingProfile.RECEIPT_BILLED, event.receipt.sourceId)
            if (request.expectedPayableRevision == null) acquire(LocalPurchasingProfile.PAYABLE, request.invoiceId)
            purchasingResult(if (reviewed == null) "purchasing.invoice_matched" else "purchasing.invoice_uncontested_matched",
                request.matchId, Revision(1), request.expectedProgressRevision.next())
        }
        LocalPurchasingProfile.MATCH_CAPTURED_INVOICE -> requirePurchasingCapturedMatchProducerV2(evidence, ::record, ::acquire)
        LocalPurchasingProfile.RECORD_CREDIT -> requirePurchasingCreditProducer(evidence, ::record, ::acquire, expectedClaims)
        else -> error("Unsupported purchasing producer")
    }
    check(evidence.aggregateWrites.map { it.key }.toSet() == expectedKeys && evidence.aggregateWrites.size == expectedKeys.size && evidence.result == result)
    check(evidence.claimChanges.size == expectedClaims.size && evidence.claimChanges.all { it.action == RetainedClaimAction.ACQUIRE && expectedClaims[it.key] == it.ownerId })
}

/** Shape only. Genuine native BEFORE history separately proves the retained source contents. */
internal fun requirePurchasingCaptureBodyV2(body: PurchasingBody.InvoiceCapture, request: LocalCaptureSupplierInvoiceV2) {
    val invoice = request.invoice
    check(body.value == invoice && body.normalizedReference == normalizedPurchaseReference(invoice.reference) &&
        body.supplierSource.kind == LocalConfigurationProfile.SUPPLIER && body.supplierSource.id == invoice.supplierId &&
        body.supplierSource.revision == request.expectedSupplierRevision)
    PayloadDigest(body.supplierSource.digest)
    val link = invoice.internalOrderLink
    check((link == null) == (body.orderSource == null) && (link == null) == (body.orderOperationId == null))
    body.orderSource?.let { source ->
        val original = checkNotNull(link)
        check(source.kind == LocalPurchasingProfile.ORDER && source.id == original.orderId && source.revision == original.revision)
        PayloadDigest(source.digest)
    }
    val skuIds = invoice.lines.map { it.goods.skuId }.distinct().sortedBy { it.value }
    check(body.catalogSources.size in 1..16 && body.catalogSources.map { it.skuSource.id } == skuIds)
    for (source in body.catalogSources) {
        check(source.skuSource.kind == LocalConfigurationProfile.SKU && source.skuSource.revision.value >= 1 &&
            source.publicationSource.kind == LocalConfigurationProfile.PUBLICATION && source.publicationSource.revision == Revision(1))
        PayloadDigest(source.skuSource.digest); PayloadDigest(source.publicationSource.digest)
        val unitIds = invoice.lines.filter { it.goods.skuId == source.skuSource.id }
            .flatMap { listOf(it.goods.units.purchaseUnitId, it.goods.units.baseUnitId) }.distinct().sortedBy { it.value }
        check(source.unitSources.size in 1..17 && source.unitSources.map { it.unitId } == unitIds)
        source.unitSources.forEach { unit ->
            check(unit.publicationSource.kind == LocalConfigurationProfile.PUBLICATION && unit.publicationSource.revision == Revision(1))
            PayloadDigest(unit.publicationSource.digest)
        }
    }
    check(body.discrepancies == body.discrepancies.distinct().sortedBy { it.name })
    if (link == null) check(body.discrepancies == purchaseInvoiceCaptureDiscrepanciesV2(null, invoice).sortedBy { it.name })
}

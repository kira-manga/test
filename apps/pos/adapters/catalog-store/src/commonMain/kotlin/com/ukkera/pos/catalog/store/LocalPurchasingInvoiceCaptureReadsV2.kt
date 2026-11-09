package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.types.*

/** Versioned protected invoice read: every selected legacy or captured claim remains present. */
internal class PurchasingInvoiceReadV2(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingProfile.READ_INVOICES
    private val legacy = PurchasingReadHandler(stock)

    override fun normalize(input: StoredBytes): StoredBytes {
        val query = purchasingDecode(input, LocalPurchasingQueryV1.serializer())
        require(query.section == LocalPurchasingSectionV1.INVOICE)
        return LocalPurchasingCodec.query(query)
    }

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile !in setOf(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, LocalMasterRpcWorkflowProfile.INVOICE_MATCHING)) return false
        val query = LocalPurchasingCodec.decodeQuery(normalized)
        return query.section == LocalPurchasingSectionV1.INVOICE && !query.export && normalize(normalized) == normalized
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = purchasingDecode(normalized, LocalPurchasingQueryV1.serializer())
        check(query.section == LocalPurchasingSectionV1.INVOICE)
        val reader = PurchasingReader(view)
        val page = if (query.id == null) view.aggregateKeys(LocalPurchasingProfile.INVOICE, query.after, query.limit) else null
        val keys = page?.keys ?: listOf(purchasingKey(LocalPurchasingProfile.INVOICE, checkNotNull(query.id)))
        check(keys.size <= query.limit && keys.distinct().size == keys.size && keys.all { it.kind == LocalPurchasingProfile.INVOICE } &&
            keys == keys.sortedBy { it.id.value } && query.after?.let { cursor -> keys.all { it.id.value > cursor.value } } != false &&
            (page?.nextAfter == null || page.nextAfter == keys.lastOrNull()?.id))
        val rows = keys.mapNotNull { selected ->
            reader.row(LocalPurchasingProfile.INVOICE, selected.id).also { check(it != null || page == null) }
        }
        val bindings = rows.map { it.row.key.id to it.row.revision }.ifEmpty { listOf((query.id ?: view.scope.branchId) to Revision(0)) }
        return boundedPurchasingRead(context, bindings, query.includeCosts, query.export, { unavailable(query) }) {
            query.id?.let { reader.invoiceDocument(it) }
            val documents = rows.map { row -> when (row.value.body) {
                is PurchasingBody.Invoice -> LocalPurchasingInvoiceDocumentV2(legacy = legacy.invoiceView(reader, view, row.row.key.id, query))
                is PurchasingBody.InvoiceCapture -> {
                    val original = checkNotNull(reader.captureInvoice(row.row.key.id))
                    val recognized = if (reader.row(LocalPurchasingProfile.PROGRESS, row.row.key.id) == null) null
                        else checkNotNull(reader.invoice(row.row.key.id))
                    if (recognized != null) requirePurchasingCrossAccounts(reader, recognized, stock, view)
                    LocalPurchasingInvoiceDocumentV2(capture = captureView(original, query),
                        recognition = recognized?.let { captureRecognitionView(it, query) })
                }
                else -> error("Unsupported purchasing invoice format")
            } }
            purchasingEncode(LocalPurchasingInvoicePageV2(documents, page?.nextAfter, query.includeCosts), LocalPurchasingInvoicePageV2.serializer())
        }
    }

    private fun unavailable(query: LocalPurchasingQueryV1): StoredBytes = purchasingEncode(LocalPurchasingInvoicePageV2(
        null, null, query.includeCosts, LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE,
        if (query.id == null && query.limit > 1) maxOf(1, query.limit / 2) else null), LocalPurchasingInvoicePageV2.serializer())
}

/** Recognition joins the real shared financial ledgers; raw documentary status and context stay unchanged. */
private fun captureRecognitionView(cut: PurchasingInvoiceCut, query: LocalPurchasingQueryV1): LocalCapturedSupplierInvoiceRecognitionV2 {
    val claim = (cut.invoice.value.body as PurchasingBody.InvoiceCapture).value
    val state = cut.progress.value.body as PurchasingBody.Progress
    val order = (cut.order.value.body as PurchasingBody.Order).value
    val originals = cut.matches.map { checkNotNull(it.captured).originalSource }.distinct()
    check(originals.size == 1 && originals.single().id == claim.sourceEvidenceId)
    val original = originals.single()
    val fullyMatched = claim.lines.all { line -> state.lines.single { it.lineId == line.lineId }.quantity == line.goods.baseQuantity() }
    val due = checkNotNull(purchasingDue(cut))
    val details = if (query.id == null) emptyList() else claim.lines.map { line ->
        val progress = state.lines.single { it.lineId == line.lineId }
        val allocations = cut.matches.filter { it.request.invoiceLineId == line.lineId }.map { it.allocationId }.distinct()
        check(allocations.size <= 1)
        LocalCapturedSupplierInvoiceRecognitionLineV2(line.lineId, allocations.singleOrNull(), progress.quantity,
            progress.net.takeIf { query.includeCosts }, progress.tax.takeIf { query.includeCosts })
    }
    // Goods quantity completion is nonfinancial; unresolved charges/value still
    // independently block settlement and never change when cost disclosure changes.
    val status = if (fullyMatched) "MATCHED_RECOGNIZED" else "PARTIALLY_MATCHED_RECOGNIZED"
    return LocalCapturedSupplierInvoiceRecognitionV2(claim.id, cut.invoice.row.revision, status,
        original.id, original.revision, order.id, cut.order.row.revision, cut.progress.row.revision,
        cut.payable?.row?.revision, details, purchasingCaptureSettlementEligible(cut),
        due.recognized.takeIf { query.includeCosts }, (claim.total() - due.recognized).takeIf { query.includeCosts },
        due.remainingDue.takeIf { query.includeCosts }, due.appliedCredit.takeIf { query.includeCosts },
        due.allocatedPayment.takeIf { query.includeCosts }, cut.settlements.reservedTotal.takeIf { query.includeCosts },
        purchasingAvailableToAllocate(cut).takeIf { query.includeCosts })
}

/** Never obtains historical terms from today's mutable catalog or PO pointer. */
private fun captureView(cut: PurchasingInvoiceCaptureCut, query: LocalPurchasingQueryV1): LocalPurchasingInvoiceCaptureViewV2 {
    val row = cut.invoice
    val body = row.value.body as PurchasingBody.InvoiceCapture
    val invoice = body.value
    val lines = if (query.id == null) emptyList() else invoice.lines.map { line ->
        val goods = line.goods
        val original = line.internalAllocationId?.let { id -> cut.originalOrder?.lines?.singleOrNull { it.allocationId == id } }
        val originalView = original?.let { ordered ->
            val terms = ordered.goods
            LocalPurchasingInvoiceOriginalLineV2(ordered.allocationId, terms.skuId, ordered.warehouseId, terms.units,
                terms.quantity, terms.baseQuantity(), terms.unitPrice.takeIf { query.includeCosts }, terms.discount.takeIf { query.includeCosts },
                terms.tax.takeIf { query.includeCosts }, terms.net.takeIf { query.includeCosts }, terms.taxAmount.takeIf { query.includeCosts })
        }
        LocalPurchasingInvoiceCaptureLineViewV2(line.lineId, line.provenance, line.internalAllocationId, line.supplierAllocationReference,
            goods.skuId, goods.supplierItemReference, goods.units, goods.quantity, goods.baseQuantity(), originalView,
            goods.unitPrice.takeIf { query.includeCosts }, goods.discount.takeIf { query.includeCosts }, goods.tax.takeIf { query.includeCosts },
            goods.net.takeIf { query.includeCosts }, goods.taxAmount.takeIf { query.includeCosts })
    }
    val discrepancies = if (query.includeCosts) body.discrepancies else body.discrepancies.filter { it in setOf(
        PurchaseDiscrepancyV1.ORDER_LINE_REQUIRED, PurchaseDiscrepancyV1.ITEM_OR_UNIT_VARIANCE, PurchaseDiscrepancyV1.QUANTITY_VARIANCE) }
    return LocalPurchasingInvoiceCaptureViewV2(invoice.id, row.row.revision, invoice.supplierId, "UNMATCHED_UNAPPROVED",
        invoice.invoiceDate, invoice.dueDate, invoice.internalOrderLink, invoice.reference, invoice.supplierOrderReference, invoice.sourceEvidenceId,
        row.value.producer.operationId, row.value.producer.actorId, row.value.recordedAt, row.value.policyRevision,
        lines, discrepancies, invoice.charges.takeIf { query.includeCosts }, invoice.total().takeIf { query.includeCosts })
}

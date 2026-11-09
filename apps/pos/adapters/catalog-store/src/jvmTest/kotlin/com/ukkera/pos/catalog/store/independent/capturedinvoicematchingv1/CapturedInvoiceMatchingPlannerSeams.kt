package com.ukkera.pos.catalog.store.independent.capturedinvoicematchingv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/**
 * Synthetic immutable planner-unit inputs ONLY. These carriers are not actual
 * protected rows, accepted receiving evidence, retained producers or authority.
 * Nothing here creates a database, CommandReadView, authorization or native loan.
 * They must not be used as a Master/history/persistence/RPC verification fixture.
 */
internal object CapturedInvoiceMatchingPlannerSeams {
    val scope: BranchScope = BranchScope(id(1), id(2), id(3))
    private val actor: DomainId = id(4)
    private val supplier: DomainId = id(5)
    private val eachUnit: DomainId = id(200)
    private val cartonUnit: DomainId = id(201)
    private val date: BusinessDate = BusinessDate.parseCanonical("2026-11-05")
    private val at: UtcInstant = UtcInstant.parseCanonical("2026-11-05T12:00:00Z")
    val digest = sha256("ccm.synthetic.planner-unit-input".encodeToByteArray())
    private val tax: PurchaseTaxV1 = PurchaseTaxV1(id(6), Revision(1), "ZERO", PercentageRate.parse("0"))

    fun each(quantity: Long): Quantity = Quantity(eachUnit, quantity, 0)
    fun money(minor: Long): Money = Money(Currency.EGP, minor)
    fun operation(ordinal: Int): OperationId = OperationId(id(1200 + ordinal).value)

    fun full(): PurchasingCapturedMatchCutV2 = cut(
        claimed = listOf(cartons()), ordered = listOf(cartons()),
        receiptQuantity = 24, receiptNet = 24000, allowanceAccepted = 24)

    fun arrival(): PurchasingCapturedMatchCutV2 = cut(
        claimed = listOf(goods(24, "10", 24000)), ordered = listOf(goods(24, "10", 24000)),
        receiptQuantity = 12, receiptNet = 12000, allowanceAccepted = 12)

    /** Selected released22 only; sources for the two nonbillable units are not fabricated. */
    fun quality(): PurchasingCapturedMatchCutV2 = cut(
        claimed = listOf(cartons()), ordered = listOf(cartons()),
        receiptQuantity = 22, receiptNet = 22000, allowanceAccepted = 22)

    /** Prior18 is an inert observed billing prefix, not a verified earlier invoice producer. */
    fun capacity(): PurchasingCapturedMatchCutV2 = cut(
        claimed = listOf(goods(10, "10", 10000)), ordered = listOf(goods(24, "10", 24000)),
        receiptQuantity = 24, receiptNet = 24000, allowanceAccepted = 24,
        priorBilled = 18, priorNet = 18000)

    fun price(selected: Int = 0): PurchasingCapturedMatchCutV2 = cut(
        claimed = listOf(goods(10, "10", 10000), goods(5, "22", 11000, secondSku = true)),
        ordered = listOf(goods(10, "10", 10000), goods(5, "20", 10000, secondSku = true)),
        selected = selected, receiptQuantity = if (selected == 0) 10 else 5,
        receiptNet = 10000, allowanceAccepted = if (selected == 0) 10 else 5)

    fun nextArrivalReceipt(before: PurchasingCapturedMatchCutV2): PurchasingCapturedMatchCutV2 = before.copy(
        receipt = receipt(checkNotNull(before.order), selected = 0, ordinal = 1,
            quantity = 12, net = 12000, allowanceAccepted = 24, receivedBefore = 12),
        receiptBilling = null)

    fun request(cut: PurchasingCapturedMatchCutV2, quantity: Long, ordinal: Int = 0,
        selected: Int = 0): LocalMatchCapturedSupplierInvoiceV2 {
        val orderRow = checkNotNull(cut.order)
        val ordered = (orderRow.value.body as PurchasingBody.Order).value
        return LocalMatchCapturedSupplierInvoiceV2(
            matchId = id(1100 + ordinal), invoiceId = cut.claim.id,
            invoiceLineId = cut.claim.lines[selected].lineId, expectedInvoiceRevision = cut.invoice.row.revision,
            originalDocumentId = cut.claim.sourceEvidenceId,
            expectedOriginalRevision = checkNotNull(cut.document.source).revision,
            orderId = ordered.id, expectedOrderRevision = orderRow.row.revision,
            allocationId = ordered.lines[selected].allocationId,
            receiptId = cut.receipt?.receiptId ?: id(800), receiptLineId = cut.receipt?.lineId ?: id(900),
            expectedReceiptRevision = cut.receipt?.receiptRevision ?: Revision(1),
            expectedProgressRevision = cut.recognized?.progress?.row?.revision,
            expectedOrderMatchRevision = checkNotNull(cut.orderMatches).row.revision,
            expectedOrderBillingRevision = checkNotNull(cut.orderBilling).row.revision,
            expectedReceiptBillingRevision = cut.receiptBilling?.row?.revision,
            expectedPayableRevision = cut.recognized?.payable?.row?.revision,
            quantity = each(quantity), reasonCode = "ccm.planner_unit")
    }

    fun propose(cut: PurchasingCapturedMatchCutV2, request: LocalMatchCapturedSupplierInvoiceV2): PlannerProposal {
        val slice = purchasingCapturedMatchSliceV2(request.sourceQuery(), cut, request)
        val postimages = mutableListOf<PurchasingRow>()
        val outcome = purchasingCapturedMatchEffectsV2(request, cut, slice) { kind, id, before, body ->
            // Synthetic callback metadata: typed proposed bodies only, never a durable producer.
            val after = row(kind, id, body, (before?.row?.revision ?: Revision(0)).next())
            postimages += after
            AggregateChange(after.row.key, before?.row?.revision, after.row.schema, after.row.payload)
        }
        return PlannerProposal(slice, outcome, postimages.toList())
    }

    fun postimage(cut: PurchasingCapturedMatchCutV2, proposal: PlannerProposal): PurchasingCapturedMatchCutV2 {
        val recognized = PurchasingInvoiceCut(cut.invoice, checkNotNull(cut.order),
            proposal.row(LocalPurchasingProfile.PROGRESS), proposal.row(LocalPurchasingProfile.PAYABLE),
            cut.recognized?.matches.orEmpty() + PurchasingMatchFact(proposal.row(LocalPurchasingProfile.MATCH).value.body),
            cut.recognized?.credits ?: PurchasingCreditLedger(null, emptyList(), money(0)),
            cut.recognized?.settlements ?: PurchasingSettlementLedger(null, emptyList(), emptyList(), money(0), money(0), emptyList()))
        return cut.copy(recognized = recognized, orderMatches = proposal.row(LocalPurchasingProfile.ORDER_MATCHES),
            orderBilling = proposal.row(LocalPurchasingProfile.ORDER_BILLED),
            receiptBilling = proposal.row(LocalPurchasingProfile.RECEIPT_BILLED), previousMatch = null)
    }

    private fun goods(quantity: Long, price: String, net: Long, secondSku: Boolean = false): PurchaseGoodsV1 {
        val product = id(if (secondSku) 111 else 110)
        val units = PurchaseUnitsV1(product, eachUnit, eachUnit, 0, null, null, UnitConversionRate.ratio(1, 1))
        return PurchaseGoodsV1(id(if (secondSku) 101 else 100), if (secondSku) "G-B" else "G-A", units,
            each(quantity), UnitPrice.parse(Currency.EGP, price), PercentageRate.parse("0"), tax, money(net), money(0))
    }

    private fun cartons(): PurchaseGoodsV1 = PurchaseGoodsV1(id(100), "G-A",
        PurchaseUnitsV1(id(110), cartonUnit, eachUnit, 0, id(202), Revision(1), UnitConversionRate.ratio(12, 1)),
        Quantity(cartonUnit, 2, 0), UnitPrice.parse(Currency.EGP, "120"), PercentageRate.parse("0"), tax, money(24000), money(0))

    private fun cut(claimed: List<PurchaseGoodsV1>, ordered: List<PurchaseGoodsV1>, selected: Int = 0,
        receiptQuantity: Long, receiptNet: Long, allowanceAccepted: Long,
        priorBilled: Long = 0, priorNet: Long = 0): PurchasingCapturedMatchCutV2 {
        val order = PurchaseOrderV1(id(400), supplier, "CCM-PO", date, null, "Synthetic planner terms", at, null,
            ordered.mapIndexed { i, goods -> PurchaseOrderLineV1(id(600 + i), id(700),
                PurchaseReceiptCostBasisV1.NET_GOODS_ONLY, false, null, goods) }, emptyList())
        val orderRow = row(LocalPurchasingProfile.ORDER, order.id,
            PurchasingBody.Order(order, normalizedPurchaseReference(order.reference), emptyList()),
            command = LocalPurchasingProfile.APPROVE_ORDER.name, operationOrdinal = 1)
        val invoice = PurchaseInvoiceCaptureV2(id(300), supplier, "00042-CCM", date, date, id(301),
            claimed.mapIndexed { i, goods -> PurchaseInvoiceCaptureLineV2(id(500 + i), goods,
                PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY) }, emptyList())
        val invoiceRow = row(LocalPurchasingProfile.INVOICE, invoice.id,
            PurchasingBody.InvoiceCapture(invoice, normalizedPurchaseReference(invoice.reference), null, null,
                PurchasingSource(LocalConfigurationProfile.SUPPLIER, supplier, Revision(1), digest.value), emptyList(), emptyList()),
            command = LocalPurchasingProfile.CAPTURE_INVOICE.name, operationOrdinal = 2)
        val priorIds = if (priorBilled == 0L) emptyList() else listOf(id(1110))
        val billing = row(LocalPurchasingProfile.ORDER_BILLED, order.lines[selected].allocationId,
            PurchasingBody.OrderBilled(order.id, order.lines[selected].allocationId, each(priorBilled),
                money(priorNet), money(0), priorIds), Revision(1L + priorIds.size))
        val receipt = receipt(orderRow, selected, selected, receiptQuantity, receiptNet, allowanceAccepted)
        val receiptBilling = if (priorIds.isEmpty()) null else row(LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId,
            PurchasingBody.ReceiptBilled(receipt.receiptId, receipt.lineId, receipt.sourceId, order.id,
                order.lines[selected].allocationId, each(priorBilled), money(priorNet), money(0), priorIds))
        return PurchasingCapturedMatchCutV2(PurchasingInvoiceCaptureCut(invoiceRow, null), null, orderRow,
            row(LocalPurchasingProfile.ORDER_MATCHES, order.id, PurchasingBody.OrderMatches(order.id, priorIds), Revision(1L + priorIds.size)),
            billing, PurchasingCapturedOriginalResolutionV1(
                PurchasingDocumentSource(LocalPurchasingDocumentProfileV1.ORIGINAL, invoice.sourceEvidenceId, Revision(1), digest, operation(3)),
                null, emptySet()), receipt, receiptBilling, null, emptySet())
    }

    private fun receipt(orderRow: PurchasingRow, selected: Int, ordinal: Int, quantity: Long, net: Long,
        allowanceAccepted: Long, receivedBefore: Long = 0): LocalPurchaseReceiptFacts {
        val order = (orderRow.value.body as PurchasingBody.Order).value
        val line = order.lines[selected]
        val receiptId = id(800 + ordinal); val lineId = id(900 + ordinal); val sourceId = id(1000 + ordinal)
        val allowance = LocalPurchaseAllowanceFacts(line.allocationId, order.id, order.supplierId,
            line.goods.skuId, line.warehouseId, line.goods.baseQuantity(), each(allowanceAccepted), each(0), line.goods.net,
            LocalStockSourceStamp(AggregateKey(LocalStockStoreProfile.ALLOWANCE, line.allocationId), Revision(1), digest))
        val valuation = LocalPoReceiptLineValuationV1(LocalPoReceiptProfileV1.CALCULATION,
            LocalPoReceiptOrderSourceV1(order.id, orderRow.row.revision, orderRow.row.digest.value, orderRow.value.producer.operationId),
            receiptId, lineId, sourceId, line.allocationId, line.goods.baseQuantity(), line.goods.net,
            each(receivedBefore), each(quantity), money(net))
        return LocalPurchaseReceiptFacts(scope, receiptId, lineId, sourceId, Revision(1), order.id, Revision(0),
            LocalStockBasisKind.LOCAL_PURCHASE, order.supplierId, line.allocationId, line.goods.skuId, line.warehouseId,
            each(quantity), each(quantity), null, money(net), LocalReceiptDisposition.RELEASED, each(quantity), null, null,
            at, allowance, LocalStockSourceStamp(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, receiptId), Revision(1), digest),
            valuation, emptySet())
    }

    private fun row(kind: String, id: DomainId, body: PurchasingBody, revision: Revision = Revision(1),
        command: String = LocalPurchasingProfile.MATCH_CAPTURED_INVOICE.name, operationOrdinal: Int = 0): PurchasingRow {
        val record = PurchasingRecord(scope, kind, id, revision, PurchasingProducer(operation(operationOrdinal), actor, command, digest.value),
            at, Revision(1), body)
        val bytes = purchasingEncode(record, PurchasingRecord.serializer())
        return PurchasingRow(StoredAggregate(AggregateKey(kind, id), revision, LocalPurchasingProfile.schema(kind), bytes,
            sha256(bytes.copyBytes())), record)
    }

    private fun id(number: Int): DomainId = DomainId("00000000-0000-4000-8000-" + number.toString().padStart(12, '0'))
}

internal class PlannerProposal(val slice: PurchasingCapturedMatchSliceV2, val outcome: ProposedOutcome.Apply,
    val postimages: List<PurchasingRow>) {
    fun row(kind: String): PurchasingRow = postimages.single { it.row.key.kind == kind }
    fun result(): LocalCapturedSupplierInvoiceMatchResultV2 = LocalPurchasingCodec.capturedMatchResult(outcome.result)
}

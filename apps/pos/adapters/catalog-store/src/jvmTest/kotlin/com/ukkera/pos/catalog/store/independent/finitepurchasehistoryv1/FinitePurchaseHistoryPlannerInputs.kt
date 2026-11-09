package com.ukkera.pos.catalog.store.independent.finitepurchasehistoryv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/**
 * Immutable supporting inputs for finite-purchase-approval-history-v1, C24/X22.
 * The original two-CARTON, twelve-EACH conversion is not normalized into an EACH PO.
 * Positive revisions, producers, document references and receipt facts below are inert
 * planner carriers, NOT authenticated originals, protected rows or native qualification.
 * The selected released 22-unit line does not invent the separate damaged-held line.
 * No runtime, historical command, read view, employee authority or persistence is created.
 */
internal object FinitePurchaseHistoryPlannerInputs {
    val scope: BranchScope = BranchScope(id(1), id(2), id(3))
    private val approver = id(4)
    private val matcher = id(5)
    private val supplier = id(6)
    private val sku = id(100)
    private val product = id(110)
    private val warehouse = id(700)
    val eachUnit: DomainId = id(200)
    val cartonUnit: DomainId = id(201)
    val validFrom: UtcInstant = UtcInstant.parseCanonical("2030-01-15T10:00:00Z")
    val validUntil: UtcInstant = UtcInstant.parseCanonical("2030-01-15T10:01:00Z")
    val readAfter: UtcInstant = UtcInstant.parseCanonical("2030-01-15T11:00:00Z")
    private val originalAt = UtcInstant.parseCanonical("2030-01-15T10:00:10Z")
    private val date = BusinessDate.parseCanonical("2030-01-15")
    val inputDigest: PayloadDigest = sha256("finite.purchase.history.synthetic.planner-input".encodeToByteArray())
    val documentDigest = PayloadDigest("df834e52aa0ef9874d8954289fc292dc26d2621dde70a6b6ac9f0e22bd6dbd3a")
    val documentText: String = "SYNTHETIC ONLY\nSupplier: SUPPLIER-A\nReference:   SYNTH-INV-X/01  \n" +
        "Date: 2030-01-15\nGoods: SKU-A / VARIANT-A\nBase quantity: 24\n" +
        "Unit net EGP minor: 2500\nUnit tax EGP minor: 0\nNet EGP minor: 60000\n" +
        "Tax EGP minor: 0\nClaim EGP minor: 60000\n"

    fun each(value: Long): Quantity = Quantity(eachUnit, value, 0)
    fun money(minor: Long): Money = Money(Currency.EGP, minor)
    fun operation(ordinal: Int): OperationId = OperationId(id(1200 + ordinal).value)

    fun originalOrder(until: UtcInstant? = validUntil): PurchaseOrderV1 {
        val goods = PurchaseGoodsV1(sku, "SKU-A / VARIANT-A",
            PurchaseUnitsV1(product, cartonUnit, eachUnit, 0, id(202), Revision(1), UnitConversionRate.ratio(12, 1)),
            Quantity(cartonUnit, 2, 0), UnitPrice.parse(Currency.EGP, "300"), PercentageRate.parse("0"),
            PurchaseTaxV1(id(203), Revision(1), "ZERO", PercentageRate.parse("0")), money(60000), money(0))
        return PurchaseOrderV1(id(400), supplier, "PO-A", date, null, "Synthetic original payment terms",
            validFrom, until, listOf(PurchaseOrderLineV1(id(600), warehouse,
                PurchaseReceiptCostBasisV1.NET_GOODS_ONLY, false, null, goods)), emptyList())
    }

    fun c24(until: UtcInstant? = validUntil): PurchasingCapturedMatchCutV2 {
        val order = originalOrder(until)
        val ordered = order.lines.single()
        val orderRow = row(LocalPurchasingProfile.ORDER, order.id,
            PurchasingBody.Order(order, normalizedPurchaseReference(order.reference), emptyList()),
            command = LocalPurchasingProfile.APPROVE_ORDER, operationId = operation(1), actor = approver, at = originalAt)
        // C24's exact raw 24 EACH is representable as the original 2 CARTON. C22's
        // 22/12 CARTON is not representable here and is deliberately not fabricated.
        val claim = PurchaseInvoiceCaptureV2(id(300), supplier, "  SYNTH-INV-X/01  ", date, date, id(301),
            listOf(PurchaseInvoiceCaptureLineV2(id(500), ordered.goods, PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY)),
            emptyList())
        val invoice = row(LocalPurchasingProfile.INVOICE, claim.id,
            PurchasingBody.InvoiceCapture(claim, normalizedPurchaseReference(claim.reference), null, null,
                PurchasingSource(LocalConfigurationProfile.SUPPLIER, supplier, Revision(1), inputDigest.value), emptyList(), emptyList()),
            command = LocalPurchasingProfile.CAPTURE_INVOICE, operationId = operation(2), at = originalAt)
        val allowance = LocalPurchaseAllowanceFacts(ordered.allocationId, order.id, supplier, sku, warehouse,
            each(24), each(22), each(0), money(60000),
            LocalStockSourceStamp(AggregateKey(LocalStockStoreProfile.ALLOWANCE, ordered.allocationId), Revision(1), inputDigest))
        val receiptId = id(800)
        val lineId = id(900)
        val sourceId = id(1000)
        val valuation = LocalPoReceiptLineValuationV1(LocalPoReceiptProfileV1.CALCULATION,
            LocalPoReceiptOrderSourceV1(order.id, orderRow.row.revision, orderRow.row.digest.value, orderRow.value.producer.operationId),
            receiptId, lineId, sourceId, ordered.allocationId, each(24), money(60000), each(0), each(22), money(55000))
        val receipt = LocalPurchaseReceiptFacts(scope, receiptId, lineId, sourceId, Revision(1), order.id, Revision(0),
            LocalStockBasisKind.LOCAL_PURCHASE, supplier, ordered.allocationId, sku, warehouse,
            each(22), each(22), null, money(55000), LocalReceiptDisposition.RELEASED, each(22), null, null,
            originalAt, allowance,
            LocalStockSourceStamp(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, receiptId), Revision(1), inputDigest),
            valuation, emptySet())
        return PurchasingCapturedMatchCutV2(PurchasingInvoiceCaptureCut(invoice, null), null, orderRow,
            row(LocalPurchasingProfile.ORDER_MATCHES, order.id, PurchasingBody.OrderMatches(order.id, emptyList())),
            row(LocalPurchasingProfile.ORDER_BILLED, ordered.allocationId,
                PurchasingBody.OrderBilled(order.id, ordered.allocationId, each(0), money(0), money(0), emptyList())),
            PurchasingCapturedOriginalResolutionV1(PurchasingDocumentSource(LocalPurchasingDocumentProfileV1.ORIGINAL,
                claim.sourceEvidenceId, Revision(1), documentDigest, operation(3)), null, emptySet()),
            receipt, null, null, emptySet())
    }

    fun request(cut: PurchasingCapturedMatchCutV2, quantity: Long = 22): LocalMatchCapturedSupplierInvoiceV2 {
        val order = checkNotNull(cut.order)
        val receipt = checkNotNull(cut.receipt)
        return LocalMatchCapturedSupplierInvoiceV2(id(1100), cut.claim.id, cut.claim.lines.single().lineId,
            cut.invoice.row.revision, cut.claim.sourceEvidenceId, checkNotNull(cut.document.source).revision,
            order.value.id, order.row.revision, (order.value.body as PurchasingBody.Order).value.lines.single().allocationId,
            receipt.receiptId, receipt.lineId, receipt.receiptRevision, cut.recognized?.progress?.row?.revision,
            checkNotNull(cut.orderMatches).row.revision, checkNotNull(cut.orderBilling).row.revision,
            cut.receiptBilling?.row?.revision, cut.recognized?.payable?.row?.revision, each(quantity), "MATCH-EXACT-GOODS")
    }

    fun propose(cut: PurchasingCapturedMatchCutV2,
        request: LocalMatchCapturedSupplierInvoiceV2 = FinitePurchaseHistoryPlannerInputs.request(cut),
        onProposedRow: (PurchasingRow) -> Unit = {}): FinitePurchaseHistoryPlannerProposal {
        val slice = purchasingCapturedMatchSliceV2(request.sourceQuery(), cut, request)
        val rows = mutableListOf<PurchasingRow>()
        val outcome = purchasingCapturedMatchEffectsV2(request, cut, slice) { kind, id, before, body ->
            // Synthetic proposed metadata only; no durable result or producer is established.
            val after = row(kind, id, body, (before?.row?.revision ?: Revision(0)).next())
            rows += after
            onProposedRow(after)
            AggregateChange(after.row.key, before?.row?.revision, after.row.schema, after.row.payload)
        }
        return FinitePurchaseHistoryPlannerProposal(slice, outcome, rows.toList())
    }

    private fun row(kind: String, id: DomainId, body: PurchasingBody, revision: Revision = Revision(1),
        command: CommandKey = LocalPurchasingProfile.MATCH_CAPTURED_INVOICE, operationId: OperationId = operation(4),
        actor: DomainId = matcher, at: UtcInstant = readAfter): PurchasingRow {
        val value = PurchasingRecord(scope, kind, id, revision,
            PurchasingProducer(operationId, actor, command.name, inputDigest.value), at, Revision(1), body)
        val bytes = purchasingEncode(value, PurchasingRecord.serializer())
        return PurchasingRow(StoredAggregate(AggregateKey(kind, id), revision, LocalPurchasingProfile.schema(kind),
            bytes, sha256(bytes.copyBytes())), value)
    }

    private fun id(value: Int): DomainId = DomainId("00000000-0000-4000-8000-" + value.toString().padStart(12, '0'))
}

internal class FinitePurchaseHistoryPlannerProposal(val slice: PurchasingCapturedMatchSliceV2,
    val outcome: ProposedOutcome.Apply, val rows: List<PurchasingRow>) {
    fun row(kind: String): PurchasingRow = rows.single { it.row.key.kind == kind }
    fun result(): LocalCapturedSupplierInvoiceMatchResultV2 = LocalPurchasingCodec.capturedMatchResult(outcome.result)
    fun recognition(before: PurchasingCapturedMatchCutV2): PurchasingInvoiceCut = PurchasingInvoiceCut(before.invoice,
        checkNotNull(before.order), row(LocalPurchasingProfile.PROGRESS), row(LocalPurchasingProfile.PAYABLE),
        listOf(PurchasingMatchFact(row(LocalPurchasingProfile.MATCH).value.body)),
        PurchasingCreditLedger(null, emptyList(), FinitePurchaseHistoryPlannerInputs.money(0)),
        PurchasingSettlementLedger(null, emptyList(), emptyList(), FinitePurchaseHistoryPlannerInputs.money(0),
            FinitePurchaseHistoryPlannerInputs.money(0), emptyList()))
}

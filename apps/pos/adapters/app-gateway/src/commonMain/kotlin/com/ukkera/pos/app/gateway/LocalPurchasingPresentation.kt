package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.Revision

/** Review the actual canonical request, including derived unit snapshots and every revision. */
internal fun purchasingIntentSummary(request: MutationRequest): List<LocalRecordValue> = buildList {
    when (request.command) {
        LocalPurchasingProfile.APPROVE_ORDER -> {
            val input = LocalPurchasingCodec.decodeApprove(request.payload)
            val order = input.order
            add(summaryGroup(text("Purchase agreement", "اتفاق الشراء"), listOf(
                summaryField(LocalField.PURCHASE_ID, order.id.value), summaryField(LocalField.SUPPLIER_ID, order.supplierId.value),
                summaryField(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, input.expectedSupplierRevision.value.toString()),
                summaryField(LocalField.EXTERNAL_REFERENCE, order.reference), summaryField(LocalField.BUSINESS_DATE, order.orderDate.canonicalString()),
                summaryField(LocalField.EXPECTED_DELIVERY, order.expectedDelivery?.canonicalString()),
                summaryField(LocalField.PAYMENT_TERMS, order.paymentTerms), summaryField(LocalField.VALID_FROM, order.validFrom.canonicalString()),
                summaryField(LocalField.VALID_UNTIL, order.validUntil?.canonicalString()), summaryField(LocalField.REASON, input.reasonCode),
                summaryField(LocalField.AMOUNT, moneyText(order.total()).english))))
            order.lines.forEachIndexed { index, line -> add(summaryGroup(text("Goods ${index + 1}", "بضاعة ${index + 1}"),
                listOf(summaryField(LocalField.ALLOCATION_ID, line.allocationId.value), summaryField(LocalField.WAREHOUSE_ID, line.warehouseId.value),
                    summaryField(LocalField.RECEIPT_COST_BASIS, line.receiptCostBasis.name), summaryField(LocalField.INSPECTION_REQUIRED, line.inspectionRequired.toString()),
                    summaryField(LocalField.REQUESTED_DELIVERY, line.requestedDelivery?.canonicalString())) + goodsSummary(line.goods))) }
            order.charges.forEach { add(chargeSummary(it)) }
        }
        LocalPurchasingProfile.RECORD_INVOICE -> {
            val input = LocalPurchasingCodec.decodeInvoice(request.payload)
            val invoice = input.invoice
            add(summaryGroup(text("Supplier's original claim — not receipt/payment", "مطالبة المورد الأصلية — ليست استلامًا أو دفعًا"), listOf(
                summaryField(LocalField.INVOICE_ID, invoice.id.value), summaryField(LocalField.PURCHASE_ID, invoice.orderId.value),
                summaryField(LocalField.SUPPLIER_ID, invoice.supplierId.value),
                summaryField(LocalField.EXPECTED_ORDER_REVISION, input.expectedOrderRevision.value.toString()),
                summaryField(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, input.expectedSupplierRevision.value.toString()),
                summaryField(LocalField.EXTERNAL_REFERENCE, invoice.reference), summaryField(LocalField.BUSINESS_DATE, invoice.invoiceDate.canonicalString()),
                summaryField(LocalField.DUE_DATE, invoice.dueDate.canonicalString()), summaryField(LocalField.EVIDENCE_ID, invoice.sourceEvidenceId.value),
                summaryField(LocalField.REASON, input.reasonCode), summaryField(LocalField.AMOUNT, moneyText(invoice.total()).english))))
            invoice.lines.forEachIndexed { index, line -> add(summaryGroup(text("Billed goods ${index + 1}", "البضاعة المفوترة ${index + 1}"),
                listOf(summaryField(LocalField.INVOICE_LINE_ID, line.lineId.value), summaryField(LocalField.ALLOCATION_ID, line.allocationId.value)) +
                    goodsSummary(line.goods))) }
            invoice.charges.forEach { add(chargeSummary(it)) }
        }
        LocalPurchasingProfile.CAPTURE_INVOICE -> {
            val input = LocalPurchasingCodec.decodeCaptureInvoice(request.payload)
            val invoice = input.invoice
            add(summaryGroup(text("Unmatched supplier claim — no approval, receipt or payment", "مطالبة مورد غير مطابقة — دون اعتماد أو استلام أو دفع"), listOf(
                summaryField(LocalField.INVOICE_ID, invoice.id.value), summaryField(LocalField.SUPPLIER_ID, invoice.supplierId.value),
                summaryField(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, input.expectedSupplierRevision.value.toString()),
                summaryField(LocalField.PURCHASE_ID, invoice.internalOrderLink?.orderId?.value),
                summaryField(LocalField.EXPECTED_ORDER_REVISION, invoice.internalOrderLink?.revision?.value?.toString()),
                summaryField(LocalField.SUPPLIER_ORDER_REFERENCE, invoice.supplierOrderReference),
                summaryField(LocalField.EXTERNAL_REFERENCE, invoice.reference), summaryField(LocalField.BUSINESS_DATE, invoice.invoiceDate.canonicalString()),
                summaryField(LocalField.DUE_DATE, invoice.dueDate.canonicalString()), summaryField(LocalField.EVIDENCE_ID, invoice.sourceEvidenceId.value),
                summaryField(LocalField.REASON, input.reasonCode), summaryField(LocalField.AMOUNT, moneyText(invoice.total()).english))))
            invoice.lines.forEachIndexed { index, line -> add(summaryGroup(text("Actual billed goods ${index + 1}", "البضاعة المفوترة الفعلية ${index + 1}"),
                listOf(summaryField(LocalField.INVOICE_LINE_ID, line.lineId.value), summaryField(LocalField.MODE, line.provenance.name),
                    summaryField(LocalField.ALLOCATION_ID, line.internalAllocationId?.value),
                    summaryField(LocalField.SUPPLIER_ALLOCATION_REFERENCE, line.supplierAllocationReference)) + goodsSummary(line.goods))) }
            invoice.charges.forEach { add(chargeSummary(it)) }
        }
        LocalPurchasingProfile.MATCH_CAPTURED_INVOICE -> addAll(capturedInvoiceMatchIdentitySummary(LocalPurchasingCodec.decodeCapturedMatch(request.payload)))
        LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> {
            val reviewed = if (request.command == LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE)
                LocalPurchasingCodec.decodeUncontestedMatch(request.payload) else null
            val input = reviewed?.match ?: LocalPurchasingCodec.decodeMatch(request.payload)
            add(summaryGroup(if (reviewed == null) text("Accepted-goods match — not supplier payment", "مطابقة بضاعة مقبولة — ليست دفعًا للمورد")
                else text("Original PO recognition only — higher claim stays disputed", "الاعتراف بمبلغ الأمر الأصلي فقط — تبقى المطالبة الأعلى محل نزاع"), listOf(
                summaryField(LocalField.MATCH_ID, input.matchId.value), summaryField(LocalField.INVOICE_ID, input.invoiceId.value),
                summaryField(LocalField.INVOICE_LINE_ID, input.invoiceLineId.value), summaryField(LocalField.RECEIPT_ID, input.receiptId.value),
                summaryField(LocalField.RECEIPT_LINE_ID, input.receiptLineId.value), summaryField(LocalField.QUANTITY, quantityText(input.quantity).english),
                summaryField(LocalField.QUANTITY_SCALE, input.quantity.scale.toString()), summaryField(LocalField.REASON, input.reasonCode))))
            add(summaryGroup(text("Exact selected storage revisions (absent is not zero)", "مراجعات التخزين المختارة الدقيقة (الغائب ليس صفرًا)"), listOf(
                summaryField(LocalField.EXPECTED_STORAGE_REVISION, input.expectedInvoiceRevision.value.toString()),
                summaryField(LocalField.EXPECTED_PROGRESS_REVISION, input.expectedProgressRevision.value.toString()),
                text("Order match index", "فهرس مطابقة الأمر") to literal(input.expectedOrderMatchRevision.value.toString()),
                text("Order billing", "فوترة الأمر") to literal(input.expectedOrderBillingRevision.value.toString()),
                text("Payable", "المستحق") to optionalSummary(input.expectedPayableRevision?.value?.toString()),
                summaryField(LocalField.EXPECTED_RECEIPT_REVISION, input.expectedReceiptRevision.value.toString()),
                text("Receipt billing", "فوترة الاستلام") to optionalSummary(input.expectedReceiptBillingRevision?.value?.toString()))))
            reviewed?.amounts?.let { amounts -> add(summaryGroup(text("Exact money for the selected quantity — no uplift approval or payment",
                "مبالغ الكمية المختارة الدقيقة — دون اعتماد الزيادة أو دفع"), listOf(
                text("Supplier claimed net", "صافي مطالبة المورد") to moneyText(amounts.claimedNet),
                text("Supplier claimed tax", "ضريبة مطالبة المورد") to moneyText(amounts.claimedTax),
                text("Recognized original PO net", "صافي الأمر الأصلي المعترف به") to moneyText(amounts.net),
                text("Recognized original PO tax", "ضريبة الأمر الأصلي المعترف بها") to moneyText(amounts.tax),
                text("Recognized payable — not paid", "مستحق معترف به — غير مدفوع") to moneyText(amounts.recognized()),
                text("Disputed price claim — not approved/payable", "مطالبة سعرية محل نزاع — غير معتمدة أو مستحقة") to moneyText(amounts.disputed()),
            ))) }
        }
        LocalPurchasingProfile.RECORD_CREDIT -> addAll(purchasingCreditSummary(LocalPurchasingCodec.decodeCredit(request.payload)))
        LocalSupplierSettlementProfile.ALLOCATE_ADVANCE -> addAll(supplierAdvanceAllocationSummary(LocalSupplierSettlementCodec.allocation(request.payload)))
        else -> unavailable()
    }
    require(size in 1..32) // Bounded, complete review; never truncate a financial document.
}

internal fun purchasingRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val request = entry.request()
    if (!actionMatches(entry.localAction, request)) unavailable()
    if (request.command == LocalSupplierSettlementProfile.ALLOCATE_ADVANCE) return supplierAdvanceRecorded(entry, reply)
    if (request.command == LocalPurchasingProfile.MATCH_CAPTURED_INVOICE) return capturedInvoiceMatchRecordedUi(entry, reply)
    if (request.command == LocalPurchasingProfile.CAPTURE_INVOICE) {
        val value = supplierInvoiceCaptureRecorded(entry, reply)
        if (!reply.committed) return text("Invoice capture durably rejected: ${value.code}. No claim, stock, payable or payment was created.",
            "رُفض حفظ مطالبة الفاتورة بشكل دائم: ${value.code}. لم تُنشأ مطالبة أو مخزون أو مستحق أو دفعة.") to null
        return text("Supplier claim captured, unmatched and unapproved. No goods receipt, stock, recognized payable or payment was created.",
            "حُفظت مطالبة المورد غير مطابقة وغير معتمدة. لم يُنشأ استلام بضاعة أو مخزون أو مستحق معترف به أو دفعة.") to LocalRecord(
            LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, value.id), entry.label.ui(), literal("UNMATCHED_UNAPPROVED"),
            listOf(recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(requireNotNull(value.revision).value.toString()))),
            invoiceEntryKind = LocalInvoiceEntryKind.CAPTURE_ONLY_V2)
    }
    val value = LocalPurchasingCodec.result(reply.result)
    val expectedId = purchasingTarget(request)
    val matching = request.command in setOf(LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE)
    val credit = request.command == LocalPurchasingProfile.RECORD_CREDIT
    val committedId = when (request.command) {
        LocalPurchasingProfile.MATCH_INVOICE -> LocalPurchasingCodec.decodeMatch(request.payload).matchId
        LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> LocalPurchasingCodec.decodeUncontestedMatch(request.payload).match.matchId
        LocalPurchasingProfile.RECORD_CREDIT -> LocalPurchasingCodec.decodeCredit(request.payload).creditId
        else -> expectedId
    }
    val expectedCode = when (request.command) {
        LocalPurchasingProfile.APPROVE_ORDER -> "purchasing.order_approved"
        LocalPurchasingProfile.RECORD_INVOICE -> "purchasing.invoice_recorded"
        LocalPurchasingProfile.MATCH_INVOICE -> "purchasing.invoice_matched"
        LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> "purchasing.invoice_uncontested_matched"
        LocalPurchasingProfile.RECORD_CREDIT -> "purchasing.credit_applied"
        else -> unavailable()
    }
    if (value.id != (if (reply.committed) committedId else expectedId) ||
        reply.committed && (value.code != expectedCode || value.revision == null)) unavailable()
    if (reply.committed && credit && (value.revision != Revision(1) || value.progressRevision != null || value.creditRevision !=
        (LocalPurchasingCodec.decodeCredit(request.payload).expectedCreditRevision ?: Revision(0)).next())) unavailable()
    if (!reply.committed) return text("Purchasing operation durably rejected: ${value.code}. No purchase, stock or payment was applied by this operation.",
        "رُفضت عملية الشراء بشكل دائم: ${value.code}. لم تطبق هذه العملية شراءً أو مخزونًا أو دفعًا.") to null
    val kind = when (request.command) {
        LocalPurchasingProfile.APPROVE_ORDER -> LocalRecordKind.PURCHASE
        LocalPurchasingProfile.RECORD_INVOICE -> LocalRecordKind.PURCHASE_INVOICE
        LocalPurchasingProfile.RECORD_CREDIT -> LocalRecordKind.SUPPLIER_CREDIT
        else -> LocalRecordKind.PURCHASE_MATCH
    }
    val message = when (request.command) {
        LocalPurchasingProfile.APPROVE_ORDER -> text("Purchase agreement and receiving allocations approved. No goods received or money paid.",
            "اعتُمد اتفاق الشراء ومخصصات الاستلام. لم تُستلم بضاعة ولم يُدفع مال.")
        LocalPurchasingProfile.RECORD_INVOICE -> text("Supplier claim recorded with its actual amounts and discrepancies. No stock or payment was created.",
            "سُجلت مطالبة المورد بمبالغها وفروقها الفعلية. لم يُنشأ مخزون أو دفع.")
        LocalPurchasingProfile.RECORD_CREDIT -> text("Agreed supplier credit allocated once to this invoice. Remaining recognized payable is reduced; no physical payment or stock movement was recorded.",
            "خُصص خصم المورد المتفق عليه مرة واحدة لهذه الفاتورة. انخفض المستحق المعترف به المتبقي؛ ولم يُسجل دفع فعلي أو حركة مخزون.")
        LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> text(
            "Accepted goods allocated once at the original PO amount, which is payable but not paid. The higher supplier claim stays disputed and unapproved; no uplift or charge was accepted.",
            "خُصصت البضاعة المقبولة مرة واحدة بمبلغ الأمر الأصلي، وهو مستحق وغير مدفوع. تبقى مطالبة المورد الأعلى محل نزاع وغير معتمدة؛ ولم تُعتمد زيادة أو مصروف.")
        else -> text("Accepted goods matched once. The matched amount is payable, not paid; charges are not automatically allocated.",
            "طُوبقت البضاعة المقبولة مرة واحدة. المبلغ المطابق مستحق وليس مدفوعًا؛ ولا توزع المصروفات تلقائيًا.")
    }
    return message to LocalRecord(LocalRecordReference(kind, committedId), entry.label.ui(), literal(value.code), buildList {
        add(recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(requireNotNull(value.revision).value.toString())))
        value.progressRevision?.let { add(recordValue(fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION), literal(it.value.toString()))) }
        value.creditRevision?.let { add(recordValue(fieldLabel(LocalField.EXPECTED_CREDIT_REVISION), literal(it.value.toString()))) }
        if (matching || credit) add(recordValue(fieldLabel(LocalField.INVOICE_ID), literal(expectedId.value)))
    }, selectable = !matching && !credit)
}

/** A capture receipt is accepted only for the exact original canonical command and terminal shape. */
internal fun supplierInvoiceCaptureRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalInvoiceCaptureResultV2 {
    val input = savedSupplierInvoiceCaptureRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId ||
        reply.physicalStart != null || reply.sequence.value <= 0L) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return supplierInvoiceCaptureOutcome(input, reply.committed, reply.code, reply.result)
}

/** Retained requests/results are checked before pending presentation, lookup, retry or ACK. */
internal fun savedSupplierInvoiceCaptureRequest(entry: StoredClientIntentV1): LocalCaptureSupplierInvoiceV2 {
    try {
        val request = entry.request()
        if (entry.localAction != LocalAction.SUPPLIER_INVOICE_CAPTURE || request.command != LocalPurchasingProfile.CAPTURE_INVOICE ||
            entry.formVersion != 1 || entry.actionApproval != null || request.actionApprovalId != null)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        entry.validate(entry.owner)
        val input = LocalPurchasingCodec.decodeCaptureInvoice(request.payload)
        entry.recorded?.let { retained ->
            if (retained.sequence.value <= 0L) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            supplierInvoiceCaptureOutcome(input, retained.committed, retained.code, payload)
        }
        return input
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

private fun supplierInvoiceCaptureOutcome(input: LocalCaptureSupplierInvoiceV2, committed: Boolean,
    code: String, payload: StoredBytes): LocalInvoiceCaptureResultV2 {
    try {
        val value = LocalPurchasingCodec.captureResult(payload)
        if (LocalPurchasingCodec.captureResult(value) != payload ||
            value.id != input.invoice.id) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        if (committed) {
            if (code != "committed" || value.code != "purchasing.invoice_captured" || value.revision != Revision(1))
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        } else if (value.revision != null || code != value.code || value.code !in supplierInvoiceCaptureRefusals)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        return value
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

private val supplierInvoiceCaptureRefusals: Set<String> = setOf(
    "purchasing.identity_already_used", "purchasing.duplicate_reference", "purchasing.order_required", "purchasing.supplier_required",
    "purchasing.current_item_required", "purchasing.unit_snapshot_required", "purchasing.selected_order_line_required",
    "purchasing.capacity_required", "purchasing.capacity_unavailable",
)

private fun goodsSummary(goods: PurchaseGoodsV1): List<Pair<LocalText, LocalText>> = listOf(
    summaryField(LocalField.SKU_ID, goods.skuId.value), summaryField(LocalField.SUPPLIER_ITEM_REFERENCE, goods.supplierItemReference),
    summaryField(LocalField.PRODUCT_ID, goods.units.productId.value), summaryField(LocalField.UNIT, goods.units.purchaseUnitId.value),
    text("Base unit / precision", "الوحدة الأساسية / الدقة") to literal("${goods.units.baseUnitId.value} / ${goods.units.baseScale}"),
    summaryField(LocalField.CONVERSION_ID, goods.units.conversionId?.value),
    text("Conversion revision / ratio", "مراجعة التحويل / النسبة") to literal(
        "${goods.units.conversionRevision?.value ?: "ABSENT"} / ${goods.units.rate.numerator}:${goods.units.rate.denominator}"),
    text("Dimensional evidence", "إثبات البعد") to optionalSummary(goods.units.dimensionalEvidenceId?.value),
    summaryField(LocalField.QUANTITY, quantityText(goods.quantity).english), summaryField(LocalField.QUANTITY_SCALE, goods.quantity.scale.toString()),
    text("Converted base quantity", "الكمية الأساسية المحولة") to quantityText(goods.baseQuantity()),
    summaryField(LocalField.UNIT_PRICE, "${goods.unitPrice.amount.canonicalString()} ${goods.unitPrice.currency.name}"),
    summaryField(LocalField.DISCOUNT_PERCENT, goods.discount.percent.canonicalString()), summaryField(LocalField.TAX_SOURCE_ID, goods.tax.sourceId.value),
    summaryField(LocalField.TAX_REVISION, goods.tax.revision.value.toString()), summaryField(LocalField.TAX_CATEGORY, goods.tax.categoryCode),
    summaryField(LocalField.TAX_RATE, goods.tax.rate.percent.canonicalString()), summaryField(LocalField.NET_AMOUNT, moneyText(goods.net).english),
    summaryField(LocalField.TAX_AMOUNT, moneyText(goods.taxAmount).english),
)

private fun chargeSummary(charge: PurchaseChargeV1): LocalRecordValue = summaryGroup(text("Separate original charge", "مصروف أصلي منفصل"), listOf(
    summaryField(LocalField.LINE_ID, charge.id.value), summaryField(LocalField.CHARGE_CODE, charge.code),
    summaryField(LocalField.EVIDENCE_ID, charge.sourceEvidenceId.value), summaryField(LocalField.COST_TREATMENT, charge.treatment.name),
    summaryField(LocalField.NET_AMOUNT, moneyText(charge.net).english), summaryField(LocalField.TAX_AMOUNT, moneyText(charge.taxAmount).english)))

internal fun summaryField(field: LocalField, value: String?): Pair<LocalText, LocalText> = fieldLabel(field) to optionalSummary(value)
private fun optionalSummary(value: String?): LocalText = value?.let(::literal) ?: text("ABSENT", "غير موجود")
internal fun summaryGroup(label: LocalText, values: List<Pair<LocalText, LocalText>>): LocalRecordValue = recordValue(label,
    text(values.joinToString("\n") { "${it.first.english}: ${it.second.english}" },
        values.joinToString("\n") { "${it.first.arabic}: ${it.second.arabic}" }))

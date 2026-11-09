package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

/** One invoice directory, retaining each original format rather than inventing legacy links. */
internal suspend fun purchasingInvoiceWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val controls = query.purchasing ?: invalid()
    if (controls.section != LocalPurchasingSection.INVOICES) invalid()
    val reference = query.reference
    if (reference != null && (reference.kind != LocalRecordKind.PURCHASE_INVOICE || reference.revision != null ||
            reference.parentId != null || query.cursor != null)) invalid()
    val requested = LocalPurchasingQueryV1(LocalPurchasingSectionV1.INVOICE, reference?.id,
        parseCursor(query.cursor, "purchasing-INVOICE"), includeCosts = controls.includeCosts)
    val cut = purchasingInvoiceRead(access, requested)
    val documents = checkNotNull(cut.page.documents)
    val selected = if (reference == null) null else documents.singleOrNull()
    val records = documents.filter { document ->
        val id = invoiceId(document)
        val supplier = document.legacy?.supplierId ?: requireNotNull(document.capture).supplierId
        val external = document.legacy?.reference ?: document.capture?.reference
        query.search.isBlank() || id.value == query.search || supplier.value == query.search ||
            external?.contains(query.search, ignoreCase = true) == true
    }.map { document -> document.legacy?.let { purchasingDocument(it, LocalRecordKind.PURCHASE_INVOICE) }
        ?: capturedInvoiceDocument(requireNotNull(document.capture), document.recognition) }.toMutableList()
    selected?.legacy?.let { invoice ->
        records += invoice.lines.map(::purchasingLine)
        records += invoice.charges.orEmpty().map(::purchasingCharge)
        records += invoice.credits.orEmpty().map(::purchasingCredit)
        records += supplierAllocationRecords(invoice.settlementAllocations.orEmpty())
    }
    selected?.capture?.let { invoice ->
        records += invoice.lines.map { line -> capturedInvoiceLine(line,
            selected.recognition?.lines?.singleOrNull { it.lineId == line.lineId }) }
        records += invoice.charges.orEmpty().map(::purchasingCharge)
        selected.recognition?.let { records += capturedInvoiceRecognitionRecord(it) }
    }
    val forms = mutableListOf<LocalActionForm>()
    var editorProblem: LocalServiceProblem? = null
    if (reference == null && access.installed.has(LocalPurchasingProfile.CAPTURE_INVOICE) &&
        access.installed.has(LocalConfigurationProfile.QUERY) && access.allow(LocalAction.SUPPLIER_INVOICE_CAPTURE))
        forms += supplierInvoiceCaptureForm()
    // Matching a captured claim is explicit and separate from its immutable capture. Legacy
    // approval grants/forms never substitute for the dedicated matching-and-recognition action.
    selected?.capture?.let { invoice ->
        if (controls.includeCosts && access.installed.has(LocalPurchasingProfile.MATCH_CAPTURED_INVOICE) &&
            access.installed.has(LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE) &&
            access.installed.has(LocalPurchasingDocumentProfileV1.READ) &&
            access.allow(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, invoice.id) &&
            listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.INVENTORY_COST_READ)
                .all { access.hasHint(Permission(it, PermissionVerb.PERFORM), invoice.id) } &&
            access.hasHint(Permission(SecurityAction.PURCHASING_DOCUMENT_READ, PermissionVerb.PERFORM), invoice.sourceEvidenceId)) {
            try { capturedInvoiceMatchForm(access, invoice, selected.recognition)?.let(forms::add) }
            catch (failure: GatewayFailure) { editorProblem = failure.problem }
        }
    }
    selected?.legacy?.let { invoice ->
        if (controls.includeCosts && access.installed.has(LocalPurchasingProfile.MATCH_INVOICE) &&
            access.installed.has(LocalPurchasingProfile.READ_MATCH_SOURCE) && access.allow(LocalAction.SUPPLIER_INVOICE_MATCH, invoice.id))
            supplierInvoiceMatchForm(invoice, access.installed.has(LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE) &&
                access.installed.has(LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE))?.let(forms::add)
        if (controls.includeCosts && access.installed.has(LocalPurchasingProfile.RECORD_CREDIT) &&
            access.installed.has(LocalPurchasingProfile.READ_CREDIT_SOURCE) && access.allow(LocalAction.SUPPLIER_CREDIT_RECORD, invoice.id)) {
            try { supplierCreditForm(access, invoice, LocalPurchasingSectionV1.INVOICE)?.let(forms::add) }
            catch (failure: GatewayFailure) { editorProblem = failure.problem }
        }
    }
    return LocalWorkflowPage(LocalWorkflow.PURCHASING, records, forms, text(
        "Current authorized invoice page. Captured claims keep actual supplier goods, amounts and literal references separate from any selected original PO context. " +
            "The original UNMATCHED_UNAPPROVED capture creates no stock, approval, payable or payment. Current exact matching and incremental recognition, when present, are shown separately without rewriting that original. " +
            "Dedicated matching authority and qualified original/PO/accepted receipt are required. Discrepancy approval and captured-claim settlement are not offered here; legacy financial forms remain separate. " +
            "An evidence reference alone does not qualify an attachment. ${readCut(cut.reply).english}" +
            (editorProblem?.let { " The read is retained, but its optional editor is unavailable: ${it.name}." } ?: ""),
        "صفحة الفواتير الحالية المصرح بها. تفصل المطالبات المحفوظة بضاعة المورد ومبالغه ومراجعه النصية الفعلية عن سياق أمر الشراء الأصلي المختار. " +
            "لا ينشئ الحفظ الأصلي غير المطابق وغير المعتمد مخزونًا أو اعتمادًا أو مستحقًا أو دفعًا. تُعرض المطابقة الدقيقة الحالية وإثبات المستحق التدريجي منفصلين عند وجودهما دون تغيير الأصل. " +
            "تلزم صلاحية مطابقة مستقلة وأصل مؤهل وأمر شراء واستلام مقبول. لا تتاح هنا موافقة الفروق أو تسوية المطالبات المحفوظة؛ وتبقى النماذج المالية القديمة منفصلة. " +
            "مرجع الإثبات وحده لا يؤهل المرفق. ${readCut(cut.reply).arabic}" +
            (editorProblem?.let { " حُفظ العرض، لكن محرره الاختياري غير متاح: ${it.name}." } ?: "")),
        cursor("purchasing-INVOICE", cut.page.nextAfter))
}

internal data class PurchasingInvoiceCut(val page: LocalPurchasingInvoicePageV2, val reply: ReadReply.Data)

internal suspend fun purchasingInvoiceRead(access: GatewayAccess, query: LocalPurchasingQueryV1): PurchasingInvoiceCut {
    if (query.section != LocalPurchasingSectionV1.INVOICE) invalid()
    val reply = access.read(ReadRequest(LocalPurchasingProfile.READ_INVOICES, LocalPurchasingCodec.query(query)))
    val page = LocalPurchasingCodec.invoicePage(reply.payload)
    if (page.includesCosts != query.includeCosts) unavailable()
    requirePurchasingAvailable(page.availability)
    val documents = page.documents ?: unavailable()
    val ids = documents.map(::invoiceId)
    val after = query.after
    if (documents.size > query.limit || ids.distinct().size != ids.size ||
        query.id != null && (ids.any { it != query.id } || page.nextAfter != null) ||
        ids.zipWithNext().any { (a, b) -> a.value >= b.value } ||
        after != null && ids.any { it.value <= after.value } ||
        page.nextAfter != null && page.nextAfter != ids.lastOrNull()) unavailable()
    for (document in documents) {
        document.legacy?.let { if (it.capturedInvoice || !query.includeCosts && purchasingDocumentHasCosts(it)) unavailable() }
        document.capture?.let { capture ->
            validateCapturedInvoice(capture, query.includeCosts, detailed = query.id != null)
            document.recognition?.let { validateCapturedInvoiceRecognition(capture, it, query.includeCosts, detailed = query.id != null) }
        }
    }
    return PurchasingInvoiceCut(page, reply)
}

private fun invoiceId(value: LocalPurchasingInvoiceDocumentV2): DomainId =
    value.legacy?.id ?: requireNotNull(value.capture).id

/** Reply validation is not source authority. The Master owns that qualification and cost release. */
private fun validateCapturedInvoice(value: LocalPurchasingInvoiceCaptureViewV2, costs: Boolean, detailed: Boolean) {
    if (value.revision != Revision(1) || value.status != "UNMATCHED_UNAPPROVED" ||
        (if (detailed) value.lines.size !in 1..16 else value.lines.isNotEmpty()) ||
        value.lines.map { it.lineId }.distinct().size != value.lines.size ||
        value.discrepancies.distinct().size != value.discrepancies.size) unavailable()
    var total = Money(Currency.EGP, 0)
    for (line in value.lines) {
        if (line.units.base(line.originalQuantity) != line.baseQuantity ||
            (line.internalAllocationId == null) != (line.originalOrderLine == null) ||
            line.internalAllocationId != null && value.internalOrderLink == null ||
            line.provenance == PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE && line.internalAllocationId == null) unavailable()
        line.originalOrderLine?.let { original ->
            if (original.allocationId != line.internalAllocationId || original.units.base(original.quantity) != original.baseQuantity ||
                line.provenance == PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE &&
                (line.skuId != original.skuId || line.units != original.units)) unavailable()
            if (!costs && (original.unitPrice != null || original.discount != null || original.purchaseTax != null ||
                    original.net != null || original.taxAmount != null)) unavailable()
            if (costs && (original.unitPrice == null || original.discount == null || original.purchaseTax == null ||
                    original.net == null || original.taxAmount == null)) unavailable()
        }
        if (!costs) {
            if (line.unitPrice != null || line.discount != null || line.purchaseTax != null ||
                line.claimedNet != null || line.claimedTax != null) unavailable()
        } else {
            val goods = PurchaseGoodsV1(line.skuId, line.supplierItemReference, line.units, line.originalQuantity,
                line.unitPrice ?: unavailable(), line.discount ?: unavailable(), line.purchaseTax ?: unavailable(),
                line.claimedNet ?: unavailable(), line.claimedTax ?: unavailable())
            total += goods.total()
        }
    }
    if (!costs) {
        if (value.charges != null || value.supplierClaim != null || value.discrepancies.any { it !in setOf(
                PurchaseDiscrepancyV1.ORDER_LINE_REQUIRED, PurchaseDiscrepancyV1.ITEM_OR_UNIT_VARIANCE,
                PurchaseDiscrepancyV1.QUANTITY_VARIANCE) }) unavailable()
    } else {
        val charges = value.charges ?: unavailable()
        if (charges.size > 8 || charges.map { it.id }.distinct().size != charges.size) unavailable()
        for (charge in charges) total += charge.total()
        val claim = value.supplierClaim ?: unavailable()
        // A bounded directory omits line details, not the source-backed summary. Exact
        // reconciliation is required on the selected detail; no absent goods are invented.
        if (claim.currency != Currency.EGP || claim.minor < total.minor || detailed && claim != total) unavailable()
    }
}

private fun capturedInvoiceDocument(value: LocalPurchasingInvoiceCaptureViewV2,
    recognition: LocalCapturedSupplierInvoiceRecognitionV2?): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, value.id), literal(value.reference), literal(value.status), buildList {
        add(recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(value.revision.value.toString())))
        add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(value.supplierId.value)))
        value.internalOrderLink?.let {
            add(recordValue(text("Selected original PO — context, not approval", "أمر الشراء الأصلي المختار — سياق وليس اعتمادًا"), literal(it.orderId.value)))
            add(recordValue(fieldLabel(LocalField.EXPECTED_ORDER_REVISION), literal(it.revision.value.toString())))
        }
        value.supplierOrderReference?.let { add(recordValue(fieldLabel(LocalField.SUPPLIER_ORDER_REFERENCE), literal(it))) }
        add(recordValue(fieldLabel(LocalField.BUSINESS_DATE), literal(value.date.canonicalString())))
        add(recordValue(fieldLabel(LocalField.DUE_DATE), literal(value.dueDate.canonicalString())))
        add(recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.sourceEvidenceId.value)))
        add(recordValue(text("Original capture operation", "عملية الحفظ الأصلية"), literal(value.operationId.value)))
        add(recordValue(text("Original employee", "الموظف الأصلي"), literal(value.originalActorId.value)))
        add(recordValue(text("Recorded at", "وقت التسجيل"), literal(value.recordedAt.canonicalString())))
        value.supplierClaim?.let { add(recordValue(text("Original supplier claim — not an approval", "مطالبة المورد الأصلية — ليست اعتمادًا"), moneyText(it))) }
        recognition?.let { add(recordValue(text("Current matching / recognition — separate from original capture",
            "المطابقة / إثبات المستحق الحاليان — منفصلان عن الحفظ الأصلي"), literal(it.status))) }
        if (value.discrepancies.isNotEmpty()) add(recordValue(text("Recorded discrepancies", "الفروق المسجلة"),
            literal(value.discrepancies.joinToString(", ") { it.name })))
    }, supplierId = value.supplierId, invoiceEntryKind = LocalInvoiceEntryKind.CAPTURE_ONLY_V2)

private fun capturedInvoiceLine(line: LocalPurchasingInvoiceCaptureLineViewV2,
    recognition: LocalCapturedSupplierInvoiceRecognitionLineV2?): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PURCHASE_LINE, line.lineId), literal(line.skuId.value), selectable = false,
    values = buildList {
        add(recordValue(fieldLabel(LocalField.MODE), literal(line.provenance.name)))
        add(recordValue(fieldLabel(LocalField.SUPPLIER_ITEM_REFERENCE), literal(line.supplierItemReference)))
        line.internalAllocationId?.let { add(recordValue(text("Selected original allocation — context only", "التخصيص الأصلي المختار — للسياق فقط"), literal(it.value))) }
        line.supplierAllocationReference?.let { add(recordValue(fieldLabel(LocalField.SUPPLIER_ALLOCATION_REFERENCE), literal(it))) }
        add(recordValue(text("Actual documented quantity", "الكمية الفعلية بالمستند"), quantityText(line.originalQuantity)))
        add(recordValue(text("Actual documented base quantity", "الكمية الأساسية الفعلية بالمستند"), quantityText(line.baseQuantity)))
        add(recordValue(text("Actual documented conversion", "التحويل الفعلي بالمستند"), literal("${line.units.rate.numerator}/${line.units.rate.denominator}")))
        line.unitPrice?.let { add(recordValue(fieldLabel(LocalField.UNIT_PRICE), literal("${it.amount.canonicalString()} ${it.currency.name}"))) }
        line.discount?.let { add(recordValue(fieldLabel(LocalField.DISCOUNT_PERCENT), literal(it.percent.canonicalString()))) }
        line.purchaseTax?.let { add(recordValue(fieldLabel(LocalField.TAX_RATE), literal(it.rate.percent.canonicalString()))) }
        line.claimedNet?.let { add(recordValue(fieldLabel(LocalField.NET_AMOUNT), moneyText(it))) }
        line.claimedTax?.let { add(recordValue(fieldLabel(LocalField.TAX_AMOUNT), moneyText(it))) }
        recognition?.let { matched ->
            add(recordValue(text("Current matched base quantity", "الكمية الأساسية المطابقة حاليًا"), quantityText(matched.matchedQuantity)))
            add(recordValue(text("Base quantity still unmatched", "الكمية الأساسية غير المطابقة بعد"),
                quantityText(line.baseQuantity - matched.matchedQuantity)))
            matched.allocationId?.let { add(recordValue(text("Actual matching allocation", "تخصيص المطابقة الفعلي"), literal(it.value))) }
            matched.matchedNet?.let { add(recordValue(text("Recognized net — not paid", "الصافي المثبت — غير مدفوع"), moneyText(it))) }
            matched.matchedTax?.let { add(recordValue(text("Recognized tax — not paid", "الضريبة المثبتة — غير مدفوعة"), moneyText(it))) }
        }
        line.originalOrderLine?.let { original ->
            add(recordValue(text("Original PO item — comparison only", "صنف الأمر الأصلي — للمقارنة فقط"), literal(original.skuId.value)))
            add(recordValue(text("Original PO quantity", "كمية الأمر الأصلية"), quantityText(original.quantity)))
            add(recordValue(text("Original PO base quantity", "الكمية الأساسية للأمر الأصلي"), quantityText(original.baseQuantity)))
            add(recordValue(text("Original PO conversion", "تحويل الأمر الأصلي"), literal("${original.units.rate.numerator}/${original.units.rate.denominator}")))
            original.unitPrice?.let { add(recordValue(text("Original PO unit price", "سعر وحدة الأمر الأصلي"), literal("${it.amount.canonicalString()} ${it.currency.name}"))) }
            original.net?.let { add(recordValue(text("Original PO net", "صافي الأمر الأصلي"), moneyText(it))) }
            original.taxAmount?.let { add(recordValue(text("Original PO tax", "ضريبة الأمر الأصلي"), moneyText(it))) }
        }
    })

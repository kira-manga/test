package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.PurchaseChargeV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Authorized production projections. An invoice is a supplier claim, not goods or payment. */
internal suspend fun purchasingWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val controls = query.purchasing ?: invalid()
    if (controls.section in setOf(LocalPurchasingSection.SUPPLIERS, LocalPurchasingSection.RECEIVING_ALLOWANCES))
        return configurationWorkflow(access, query)
    if (controls.section == LocalPurchasingSection.ADVANCES) return supplierAdvanceWorkflow(access, query)
    if (controls.section == LocalPurchasingSection.OPENING_OBLIGATIONS) return openingSupplierObligationWorkflow(access, query)
    if (controls.section == LocalPurchasingSection.DOCUMENTS) return purchasingDocumentaryWorkflow(access, query)
    if (controls.section == LocalPurchasingSection.INVOICES && access.installed.has(LocalPurchasingProfile.READ_INVOICES))
        return purchasingInvoiceWorkflow(access, query)
    val section = when (controls.section) {
        LocalPurchasingSection.ORDERS -> LocalPurchasingSectionV1.ORDER
        LocalPurchasingSection.INVOICES -> LocalPurchasingSectionV1.INVOICE
        LocalPurchasingSection.PAYABLES -> LocalPurchasingSectionV1.PAYABLE
        else -> invalid()
    }
    // Choosing the payable section does not silently escalate a quantity-only read.
    if (section == LocalPurchasingSectionV1.PAYABLE && !controls.includeCosts)
        return LocalWorkflowPage(LocalWorkflow.PURCHASING, emptyList(), emptyList(),
            text("Payables require an explicit cost read. Enable costs with current permission; no balance has been read or inferred as zero.",
                "تتطلب المستحقات قراءة تكلفة صريحة. فعّل التكلفة بصلاحية سارية؛ لم يُقرأ رصيد ولم يُفترض أنه صفر."))
    val kind = purchasingRecordKind(section)
    val reference = query.reference
    if (reference != null && (reference.kind != kind || reference.parentId != null || query.cursor != null)) invalid()
    val after = parseCursor(query.cursor, "purchasing-${section.name}")
    val requested = LocalPurchasingQueryV1(section, reference?.id, after, includeCosts = controls.includeCosts)
    val cut = purchasingRead(access, requested)
    val documents = checkNotNull(cut.page.documents)
    val selected = if (reference == null) null else documents.singleOrNull()
    val records = documents.filter { document -> query.search.isBlank() || document.id.value == query.search ||
        document.supplierId.value == query.search || document.reference?.contains(query.search, ignoreCase = true) == true
    }.map { purchasingDocument(it, kind) }.toMutableList()
    if (selected != null) {
        records += selected.lines.map { purchasingLine(it) }
        records += selected.charges.orEmpty().map(::purchasingCharge)
        records += selected.credits.orEmpty().map(::purchasingCredit)
        records += supplierAllocationRecords(selected.settlementAllocations.orEmpty())
    }
    val forms = mutableListOf<LocalActionForm>()
    var editorProblem: LocalServiceProblem? = null
    if (section == LocalPurchasingSectionV1.ORDER && reference == null && access.allow(LocalAction.PURCHASE_APPROVE) &&
        access.installed.has(LocalPurchasingProfile.APPROVE_ORDER)) {
        try { purchaseApprovalForm(access)?.let(forms::add) }
        catch (failure: GatewayFailure) { editorProblem = failure.problem }
    }
    if (section == LocalPurchasingSectionV1.ORDER && selected != null &&
        access.installed.has(LocalPurchasingProfile.RECORD_INVOICE) && access.allow(LocalAction.SUPPLIER_INVOICE_RECORD)) {
        supplierInvoiceForm(selected)?.let(forms::add)
    }
    if (section == LocalPurchasingSectionV1.ORDER && selected != null &&
        access.installed.has(LocalPurchasingProfile.CAPTURE_INVOICE) && access.installed.has(LocalPurchasingProfile.READ_INVOICES) &&
        access.installed.has(LocalConfigurationProfile.QUERY) && access.allow(LocalAction.SUPPLIER_INVOICE_CAPTURE))
        forms += supplierInvoiceCaptureForm(selected)
    if (section == LocalPurchasingSectionV1.INVOICE && selected != null && controls.includeCosts &&
        access.installed.has(LocalPurchasingProfile.MATCH_INVOICE) && access.installed.has(LocalPurchasingProfile.READ_MATCH_SOURCE) &&
        access.allow(LocalAction.SUPPLIER_INVOICE_MATCH, selected.id)) {
        supplierInvoiceMatchForm(selected, access.installed.has(LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE) &&
            access.installed.has(LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE))?.let(forms::add)
    }
    if (section in setOf(LocalPurchasingSectionV1.INVOICE, LocalPurchasingSectionV1.PAYABLE) && selected != null && !selected.capturedInvoice && controls.includeCosts &&
        access.installed.has(LocalPurchasingProfile.RECORD_CREDIT) && access.installed.has(LocalPurchasingProfile.READ_CREDIT_SOURCE) &&
        access.allow(LocalAction.SUPPLIER_CREDIT_RECORD, selected.id)) {
        try { supplierCreditForm(access, selected, section)?.let(forms::add) }
        catch (failure: GatewayFailure) { editorProblem = failure.problem }
    }
    val notice = text(
        "Current authorized page only. Orders approve receiving allocations; invoices retain claims and discrepancies. Matching recognizes only accepted goods, never pays a supplier or silently allocates charges. " +
            "Original-PO uncontested recognition never accepts a higher supplier price; the uplift stays disputed and unapproved. " +
            "Agreed invoice credits reduce remaining recognized payable once and retain their original source; they do not record a cash refund or change stock/cost. " +
            "Supplier cash payout and unapplied advance are explicit separate native workflows; allocating a paid advance is nonphysical. Variance approval, document amendments and unmatched charge resolution remain separate required workflows. ${readCut(cut.reply).english}" +
            (editorProblem?.let { " The document read is retained, but its optional editor is unavailable: ${it.name}." } ?: ""),
        "الصفحة الحالية المصرح بها فقط. تعتمد الأوامر مخصصات الاستلام؛ وتحفظ الفواتير المطالبات والفروق. تعترف المطابقة بالبضاعة المقبولة فقط، ولا تدفع للمورد أو توزع المصروفات ضمنيًا. " +
            "لا يعتمد الاعتراف بالمبلغ الأصلي غير المتنازع عليه سعر المورد الأعلى؛ وتبقى الزيادة محل نزاع وغير معتمدة. " +
            "تخفض خصومات الفاتورة المتفق عليها المستحق المعترف به المتبقي مرة واحدة وتحفظ مصدرها الأصلي؛ دون تسجيل رد نقدي أو تغيير المخزون أو التكلفة. " +
            "دفع نقدية المورد والدفعة المقدمة غير المخصصة مساران أصليان منفصلان وصريحان؛ وتخصيص دفعة مقدمة مدفوعة لا يحرك النقدية. تظل اعتماد الفروق وتعديل المستندات ومعالجة المصروفات غير المطابقة مسارات مطلوبة منفصلة. ${readCut(cut.reply).arabic}" +
            (editorProblem?.let { " حُفظ عرض المستند، لكن محرره الاختياري غير متاح: ${it.name}." } ?: ""))
    return LocalWorkflowPage(LocalWorkflow.PURCHASING, records, forms, notice,
        cursor("purchasing-${section.name}", cut.page.nextAfter))
}

internal data class PurchasingCut(val page: LocalPurchasingPageV1, val reply: ReadReply.Data)

internal suspend fun purchasingRead(access: GatewayAccess, query: LocalPurchasingQueryV1): PurchasingCut {
    val reply = access.read(ReadRequest(LocalPurchasingProfile.READ, LocalPurchasingCodec.query(query)))
    val page = LocalPurchasingCodec.page(reply.payload)
    if (page.section != query.section || page.includesCosts != query.includeCosts) unavailable()
    requirePurchasingAvailable(page.availability)
    val documents = page.documents ?: unavailable()
    val after = query.after
    if (documents.size > query.limit || documents.map { it.id }.distinct().size != documents.size ||
        query.id != null && (documents.any { it.id != query.id } || page.nextAfter != null) ||
        documents.zipWithNext().any { (a, b) -> a.id.value >= b.id.value } ||
        after != null && documents.any { it.id.value <= after.value } ||
        page.nextAfter != null && page.nextAfter != documents.lastOrNull()?.id) unavailable()
    if (!query.includeCosts && documents.any(::purchasingDocumentHasCosts)) unavailable()
    if (query.section != LocalPurchasingSectionV1.PAYABLE && documents.any { it.capturedInvoice }) unavailable()
    return PurchasingCut(page, reply)
}

internal fun requirePurchasingAvailable(availability: LocalPurchasingReadAvailabilityV1) {
    when (availability) {
        LocalPurchasingReadAvailabilityV1.AVAILABLE -> Unit
        LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE ->
            throw GatewayFailure(LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED)
        LocalPurchasingReadAvailabilityV1.UNSUPPORTED_INVOICE_CAPTURE ->
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        LocalPurchasingReadAvailabilityV1.SOURCE_UNAVAILABLE ->
            throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
    }
}

/** Reject over-disclosure before projection; a quantity-only selector never authorizes costs. */
internal fun purchasingDocumentHasCosts(document: LocalPurchasingDocumentViewV1): Boolean =
        document.reference != null || document.sourceEvidenceId != null || document.paymentTerms != null ||
            document.charges != null || document.supplierClaim != null || document.matchedPayable != null ||
            document.unmatchedClaim != null || document.remainingDue != null || document.disputedPriceClaim != null || document.dueDate != null ||
            document.creditRevision != null || document.appliedCredit != null || document.credits != null ||
            document.settlementRevision != null || document.allocatedPayment != null || document.reservedSupplierCash != null ||
            document.availableToAllocate != null || document.settlementStatus != null || document.settlementAllocations != null ||
            document.payableRevision != null || document.lines.any { line -> line.unitPrice != null || line.discount != null ||
                line.purchaseTax != null || line.claimedNet != null || line.claimedTax != null || line.matchedNet != null || line.matchedTax != null ||
                line.disputedPriceClaim != null }

internal suspend fun purchasingDocumentRead(access: GatewayAccess, section: LocalPurchasingSectionV1,
    id: DomainId, costs: Boolean = false): LocalPurchasingDocumentViewV1 =
    purchasingRead(access, LocalPurchasingQueryV1(section, id = id, includeCosts = costs)).page.documents?.singleOrNull()
        ?: invalid(if (section == LocalPurchasingSectionV1.ORDER) LocalField.PURCHASE_ID else LocalField.INVOICE_ID)

private fun purchasingRecordKind(section: LocalPurchasingSectionV1): LocalRecordKind = when (section) {
    LocalPurchasingSectionV1.ORDER -> LocalRecordKind.PURCHASE
    LocalPurchasingSectionV1.INVOICE -> LocalRecordKind.PURCHASE_INVOICE
    LocalPurchasingSectionV1.PAYABLE -> LocalRecordKind.SUPPLIER_PAYABLE
}

internal fun purchasingDocument(value: LocalPurchasingDocumentViewV1, kind: LocalRecordKind): LocalRecord = LocalRecord(
    LocalRecordReference(kind, value.id), literal(value.reference ?: value.id.value), literal(value.status), buildList {
        add(recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(value.revision.value.toString())))
        add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(value.supplierId.value)))
        add(recordValue(fieldLabel(LocalField.PURCHASE_ID), literal(value.orderId.value)))
        add(recordValue(fieldLabel(LocalField.BUSINESS_DATE), literal(value.date.canonicalString())))
        value.dueDate?.let { add(recordValue(fieldLabel(LocalField.DUE_DATE), literal(it.canonicalString()))) }
        value.progressRevision?.let { add(recordValue(fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION), literal(it.value.toString()))) }
        value.paymentTerms?.let { add(recordValue(fieldLabel(LocalField.PAYMENT_TERMS), literal(it))) }
        value.sourceEvidenceId?.let { add(recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(it.value))) }
        value.supplierClaim?.let { add(recordValue(text("Supplier's original claim", "مطالبة المورد الأصلية"), moneyText(it))) }
        value.matchedPayable?.let { add(recordValue(text("Gross recognized invoice amount", "مبلغ الفاتورة الإجمالي المعترف به"), moneyText(it))) }
        value.unmatchedClaim?.let { add(recordValue(text("Unmatched supplier claim", "مطالبة المورد غير المطابقة"), moneyText(it))) }
        value.remainingDue?.let { add(recordValue(text("Remaining recognized payable", "المستحق المعترف به المتبقي"), moneyText(it))) }
        value.appliedCredit?.let { add(recordValue(text("Agreed supplier credits allocated", "خصومات المورد المتفق عليها والمخصصة"), moneyText(it))) }
        value.creditRevision?.let { add(recordValue(fieldLabel(LocalField.EXPECTED_CREDIT_REVISION), literal(it.value.toString()))) }
        value.allocatedPayment?.let { add(recordValue(text("Allocated supplier payments", "مدفوعات المورد المخصصة"), moneyText(it))) }
        value.reservedSupplierCash?.let { add(recordValue(text("Reserved supplier cash — not paid", "نقدية المورد المحجوزة — غير مدفوعة"), moneyText(it))) }
        value.availableToAllocate?.let { add(recordValue(if (value.capturedInvoice)
            text("Unreserved recognized balance — not settlement authorization", "الرصيد المثبت غير المحجوز — ليس إذنًا بالتسوية")
            else text("Available for a new credit/payment allocation", "المتاح لتخصيص خصم أو دفع جديد"), moneyText(it))) }
        value.settlementRevision?.let { add(recordValue(fieldLabel(LocalField.EXPECTED_SETTLEMENT_REVISION), literal(it.value.toString()))) }
        value.settlementStatus?.let { add(recordValue(text("Supplier settlement status", "حالة تسوية المورد"), literal(it))) }
        value.disputedPriceClaim?.let { add(recordValue(text("Price claim for allocated goods — disputed, not approved/payable",
            "مطالبة سعرية لبضاعة مخصصة — محل نزاع وغير معتمدة أو مستحقة"), moneyText(it))) }
        if (value.discrepancies.isNotEmpty()) add(recordValue(text("Recorded discrepancies", "الفروق المسجلة"),
            literal(value.discrepancies.joinToString(", ") { it.name })))
    }, supplierId = value.supplierId, invoiceEntryKind = when {
        value.capturedInvoice && kind == LocalRecordKind.SUPPLIER_PAYABLE -> LocalInvoiceEntryKind.CAPTURE_ONLY_V2
        kind in setOf(LocalRecordKind.PURCHASE_INVOICE, LocalRecordKind.SUPPLIER_PAYABLE) -> LocalInvoiceEntryKind.ORDER_LINKED_V1
        else -> null
    })

internal fun purchasingLine(line: LocalPurchasingLineViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PURCHASE_LINE, line.lineId), literal(line.skuId.value), selectable = false,
    values = buildList {
        add(recordValue(fieldLabel(LocalField.ALLOCATION_ID), literal(line.allocationId.value)))
        line.warehouseId?.let { add(recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(it.value))) }
        add(recordValue(text("Original document quantity", "كمية المستند الأصلية"), quantityText(line.originalQuantity)))
        add(recordValue(text("Base quantity", "الكمية الأساسية"), quantityText(line.baseQuantity)))
        add(recordValue(text("Matched quantity", "الكمية المطابقة"), quantityText(line.matchedQuantity)))
        line.receivedCommitted?.let { add(recordValue(text("Receiving committed quantity", "كمية الاستلام المثبتة"), quantityText(it))) }
        line.receivedAccepted?.let { add(recordValue(text("Accepted quantity", "الكمية المقبولة"), quantityText(it))) }
        line.inspectionPending?.let { add(recordValue(text("Inspection pending", "قيد الفحص"), quantityText(it))) }
        add(recordValue(text("Original conversion ratio", "نسبة التحويل الأصلية"), literal("${line.units.rate.numerator}/${line.units.rate.denominator}")))
        line.units.conversionId?.let { add(recordValue(fieldLabel(LocalField.CONVERSION_ID), literal("${it.value} · ${line.units.conversionRevision?.value}"))) }
        line.unitPrice?.let { add(recordValue(fieldLabel(LocalField.UNIT_PRICE), literal("${it.amount.canonicalString()} ${it.currency.name}"))) }
        line.discount?.let { add(recordValue(fieldLabel(LocalField.DISCOUNT_PERCENT), literal(it.percent.canonicalString()))) }
        line.purchaseTax?.let {
            add(recordValue(fieldLabel(LocalField.TAX_SOURCE_ID), literal("${it.sourceId.value} · ${it.revision.value}")))
            add(recordValue(fieldLabel(LocalField.TAX_CATEGORY), literal(it.categoryCode)))
            add(recordValue(fieldLabel(LocalField.TAX_RATE), literal(it.rate.percent.canonicalString())))
        }
        line.claimedNet?.let { add(recordValue(fieldLabel(LocalField.NET_AMOUNT), moneyText(it))) }
        line.claimedTax?.let { add(recordValue(fieldLabel(LocalField.TAX_AMOUNT), moneyText(it))) }
        line.matchedNet?.let { add(recordValue(text("Matched net", "الصافي المطابق"), moneyText(it))) }
        line.matchedTax?.let { add(recordValue(text("Matched tax", "الضريبة المطابقة"), moneyText(it))) }
        line.disputedPriceClaim?.let { add(recordValue(text("Allocated-goods price dispute — not payable",
            "فرق سعر البضاعة المخصصة — غير مستحق"), moneyText(it))) }
    })

internal fun purchasingCharge(charge: PurchaseChargeV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PURCHASE_CHARGE, charge.id), literal(charge.code), literal(charge.treatment.name),
    listOf(recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(charge.sourceEvidenceId.value)),
        recordValue(fieldLabel(LocalField.NET_AMOUNT), moneyText(charge.net)), recordValue(fieldLabel(LocalField.TAX_AMOUNT), moneyText(charge.taxAmount))),
    selectable = false)

internal suspend fun purchasingSupplier(access: GatewayAccess, id: DomainId,
    expectedDomainRevision: Revision? = null): LocalConfigurationListItemV1 {
    val cut = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER, id = id))
    val detail = cut.page.detail as? LocalConfigurationDetailV1.Supplier ?: invalid(LocalField.SUPPLIER_ID)
    val row = cut.page.items.singleOrNull() ?: invalid(LocalField.SUPPLIER_ID)
    if (row.id != id || detail.value.id != id) unavailable()
    if (!detail.value.active || row.active != true || expectedDomainRevision != null && row.revision != expectedDomainRevision)
        invalid(LocalField.SUPPLIER_ID)
    return row
}

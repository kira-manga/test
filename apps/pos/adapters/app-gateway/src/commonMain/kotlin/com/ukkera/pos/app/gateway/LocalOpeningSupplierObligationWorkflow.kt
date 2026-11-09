package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.types.Revision

/** Migrated debt is its own immutable document, never a purchase, receipt or cash payout. */
internal suspend fun openingSupplierObligationWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val controls = query.purchasing ?: invalid()
    val reference = query.reference
    if (controls.section != LocalPurchasingSection.OPENING_OBLIGATIONS || reference != null &&
        (reference.kind != LocalRecordKind.OPENING_SUPPLIER_OBLIGATION || reference.parentId != null ||
            reference.revision != null || query.cursor != null)) invalid()
    val forms = if (reference == null && access.installed.has(LocalOpeningSupplierObligationProfile.COMMAND) &&
        access.installed.has(LocalConfigurationProfile.QUERY) && access.allow(LocalAction.SUPPLIER_OPENING_RECORD))
        listOf(openingSupplierObligationForm()) else emptyList()
    if (!controls.includeCosts) return LocalWorkflowPage(LocalWorkflow.PURCHASING, emptyList(), forms, text(
        "Existing opening supplier debt requires an explicit current financial read. No balance was read or inferred as zero. New migrated debt, when authorized, is recorded separately from stock, purchases and cash.",
        "تتطلب ديون الموردين الافتتاحية الحالية قراءة مالية صريحة وسارية. لم يُقرأ رصيد أو يُفترض أنه صفر. يُسجل الدين المرحّل الجديد عند السماح به بشكل منفصل عن المخزون والمشتريات والنقدية."))
    val after = parseCursor(query.cursor, "opening-supplier-obligations")
    // Documentary originals include exact source bytes; one complete original keeps a
    // valid bounded record readable without silently dropping evidence from larger pages.
    val requested = LocalOpeningSupplierObligationQueryV1(id = reference?.id, after = after, limit = 1)
    val reply = access.read(ReadRequest(LocalOpeningSupplierObligationProfile.READ,
        LocalOpeningSupplierObligationCodec.query(requested)))
    val page = LocalOpeningSupplierObligationCodec.decodePage(reply.payload)
    if (page.query != requested) unavailable()
    if (page.availability != LocalPurchasingReadAvailabilityV1.AVAILABLE)
        throw GatewayFailure(LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED)
    val entries = page.entries
    val nextAfter = page.nextAfter
    if (entries.size > requested.limit || entries.map { it.id }.distinct().size != entries.size ||
        entries.any { it.sourceDocumentMetadata.scope != access.view.scope } ||
        entries.zipWithNext().any { (a, b) -> a.id.value >= b.id.value } ||
        after != null && entries.any { it.id.value <= after.value } ||
        reference != null && (entries.any { it.id != reference.id } || page.nextAfter != null) ||
        nextAfter != null && ((after != null && nextAfter.value <= after.value) ||
            entries.any { it.id.value > nextAfter.value })) unavailable()
    val records = entries.filter { query.search.isBlank() || it.id.value == query.search ||
        it.supplierId.value == query.search || it.sourceReference.contains(query.search, ignoreCase = true) }
        .map(::openingSupplierObligationRecord)
    return LocalWorkflowPage(LocalWorkflow.PURCHASING, records, forms, text(
        "Original migrated supplier obligations only. Recording opening debt does not purchase goods, receive stock, generate sales/revenue, pay a supplier or move drawer cash. Settlement against these documents is not yet connected; invoice and advance payments must not be used as a substitute. ${readCut(reply).english}",
        "التزامات الموردين المرحّلة الأصلية فقط. لا يشتري تسجيل الدين الافتتاحي بضاعة ولا يستلم مخزونًا ولا يولد مبيعات أو إيرادات ولا يدفع للمورد أو يحرك نقدية الدرج. لم يُربط سداد هذه المستندات بعد؛ ولا تُستخدم دفعات الفواتير أو الدفعات المقدمة كبديل. ${readCut(reply).arabic}"),
        cursor("opening-supplier-obligations", nextAfter)) // Preserve the actual cursor even after an empty display filter.
}

private fun openingSupplierObligationForm(): LocalActionForm = form(LocalAction.SUPPLIER_OPENING_RECORD, listOf(
    newIdField(LocalField.OPENING_OBLIGATION_ID), pagedReferenceField(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER),
    idField(LocalField.EVIDENCE_ID).copy(help = text(
        "Identity for the actual documentary text captured below. An identifier alone is not evidence; do not invent a purchase or reuse another obligation's evidence.",
        "هوية النص المستندي الفعلي المحفوظ أدناه. المعرف وحده ليس إثباتًا؛ ولا تنشئ شراءً وهميًا أو تعيد استخدام إثبات التزام آخر.")),
    scalar(LocalField.SOURCE_DOCUMENT_IDENTITY, LocalInputKind.TEXT, maximum = 128),
    scalar(LocalField.SOURCE_DOCUMENT_TEXT, LocalInputKind.MULTILINE, maximum = 4096, help = text(
        "Preserve the original plain-text statement verbatim (up to 4,096 UTF-8 bytes). It is retained locally with this immutable debt. PDF/image intake is not connected; do not substitute a file path or an invented summary.",
        "احفظ نص البيان الأصلي كما هو (حتى 4096 بايت بصيغة UTF-8). يُحفظ محليًا مع هذا الدين الثابت. لم يُربط إدخال PDF أو الصور؛ ولا تستبدل النص بمسار ملف أو ملخص مختلق.")),
    scalar(LocalField.EXTERNAL_REFERENCE, LocalInputKind.TEXT, maximum = 96),
    scalar(LocalField.BUSINESS_DATE, LocalInputKind.DATE), moneyField(LocalField.AMOUNT),
    scalar(LocalField.PAYMENT_TERMS, LocalInputKind.TEXT, maximum = 256, required = false).copy(help = text(
        "Preserve the original debt terms. Leave blank only when the documentary source explicitly has no terms.",
        "احتفظ بشروط الدين الأصلية. اتركها فارغة فقط عندما ينص المصدر المستندي صراحةً على عدم وجود شروط.")),
    scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128)), text(
    "Confirm the original opening date, documentary source, supplier, amount and terms. This is immutable migrated debt, not a supplier invoice, stock receipt, sale, credit or payment. Save and recover this same operation; never re-enter an uncertain submission as new debt.",
    "أكد التاريخ الافتتاحي الأصلي والمصدر المستندي والمورد والمبلغ والشروط. هذا دين مرحّل ثابت، وليس فاتورة مورد أو استلام مخزون أو بيعًا أو ائتمانًا أو دفعًا. احفظ نفس العملية واستعدها؛ ولا تعِد إدخال طلب غير مؤكد كدين جديد."))

internal suspend fun prepareOpeningSupplierObligation(access: GatewayAccess, intent: LocalCommandIntent,
    input: InputValues): MutationRequest {
    if (intent.action != LocalAction.SUPPLIER_OPENING_RECORD || intent.reference != null || intent.lines.isNotEmpty()) invalid()
    val id = input.id(LocalField.OPENING_OBLIGATION_ID)
    access.requireAction(intent.action, id)
    val selected = input.reference(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER)
    if (selected.revision == null || selected.parentId != null) invalid(LocalField.SUPPLIER_ID)
    val supplier = purchasingSupplier(access, selected.id, selected.revision)
    val request = LocalRecordOpeningSupplierObligationV1(
        obligationId = id, supplierId = supplier.id, expectedSupplierRevision = supplier.storageRevision,
        sourceEvidenceId = input.id(LocalField.EVIDENCE_ID), sourceReference = input.text(LocalField.EXTERNAL_REFERENCE),
        openingDate = input.date(LocalField.BUSINESS_DATE), amount = input.money(LocalField.AMOUNT),
        paymentTerms = input.optionalText(LocalField.PAYMENT_TERMS) ?: "", reasonCode = input.text(LocalField.REASON),
        sourceDocument = LocalOpeningSupplierDocumentaryEvidenceV1(input.text(LocalField.SOURCE_DOCUMENT_IDENTITY),
            input.text(LocalField.SOURCE_DOCUMENT_TEXT)))
    return LocalOpeningSupplierObligationCommands.record(intent.operationId, request)
}

internal fun openingSupplierObligationSummary(request: MutationRequest): List<LocalRecordValue> {
    if (request.command != LocalOpeningSupplierObligationProfile.COMMAND || request.actionApprovalId != null) unavailable()
    val value = LocalOpeningSupplierObligationCodec.decodeRequest(request.payload)
    return listOf(
        recordValue(fieldLabel(LocalField.OPENING_OBLIGATION_ID), literal(value.obligationId.value)),
        recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(value.supplierId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(value.expectedSupplierRevision.value.toString())),
        recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.sourceEvidenceId.value)),
        recordValue(fieldLabel(LocalField.SOURCE_DOCUMENT_IDENTITY), literal(value.sourceDocument.sourceIdentity)),
        recordValue(fieldLabel(LocalField.EXTERNAL_REFERENCE), literal(value.sourceReference)),
        recordValue(fieldLabel(LocalField.BUSINESS_DATE), literal(value.openingDate.canonicalString())),
        recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)),
        recordValue(fieldLabel(LocalField.PAYMENT_TERMS), literal(value.paymentTerms)),
        recordValue(fieldLabel(LocalField.REASON), literal(value.reasonCode))) + openingDocumentaryText(value.sourceDocument.text)
}

private fun openingSupplierObligationRecord(value: LocalOpeningSupplierObligationViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.OPENING_SUPPLIER_OBLIGATION, value.id), names(value.supplierNames, value.supplierId),
    text("Original opening supplier obligation", "التزام المورد الافتتاحي الأصلي"), listOf(
        recordValue(fieldLabel(LocalField.OPENING_OBLIGATION_ID), literal(value.id.value)),
        recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(value.supplierId.value)),
        recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)),
        recordValue(fieldLabel(LocalField.BUSINESS_DATE), literal(value.openingDate.canonicalString())),
        recordValue(fieldLabel(LocalField.PAYMENT_TERMS), literal(value.paymentTerms)),
        recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.sourceEvidenceId.value)),
        recordValue(fieldLabel(LocalField.SOURCE_DOCUMENT_IDENTITY), literal(value.sourceDocument.sourceIdentity)),
        recordValue(fieldLabel(LocalField.EXTERNAL_REFERENCE), literal(value.sourceReference)),
        recordValue(fieldLabel(LocalField.REASON), literal(value.reasonCode)),
        recordValue(text("Original operation", "العملية الأصلية"), literal(value.operationId.value)),
        recordValue(text("Original recording employee", "الموظف الذي سجل الالتزام الأصلي"), literal(value.actorId.value)),
        recordValue(text("Recorded at", "وقت التسجيل"), literal(value.recordedAt.canonicalString())),
        recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(value.supplierRevision.value.toString()))) +
        openingDocumentaryText(value.sourceDocument.text))

/** Display-only contiguous parts keep LocalText bounded without truncating the original
 * documentary request, evidence bytes or readback. Never split a UTF-16 surrogate pair. */
private fun openingDocumentaryText(source: String): List<LocalRecordValue> = buildList {
    var offset = 0
    var part = 1
    while (offset < source.length) {
        var end = minOf(offset + 2048, source.length)
        if (end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end--
        add(recordValue(text("Original documentary text — part $part", "النص المستندي الأصلي — الجزء $part"),
            literal(source.substring(offset, end))))
        offset = end
        part++
    }
}

internal fun openingSupplierObligationRecorded(entry: StoredClientIntentV1,
    reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val request = entry.request()
    if (!actionMatches(entry.localAction, request)) unavailable()
    val input = LocalOpeningSupplierObligationCodec.decodeRequest(request.payload)
    val value = LocalOpeningSupplierObligationCodec.decodeResult(reply.result)
    if (value.id != input.obligationId || reply.committed &&
        (value.code != "purchasing.opening_obligation_recorded" || value.revision != Revision(1)) ||
        !reply.committed && value.revision != null) unavailable()
    if (!reply.committed) return text(
        "Opening supplier obligation durably rejected: ${value.code}. This operation applied no debt, purchase, stock, sale or cash movement.",
        "رُفض التزام المورد الافتتاحي بشكل دائم: ${value.code}. لم تطبق هذه العملية دينًا أو شراءً أو مخزونًا أو بيعًا أو حركة نقدية.") to null
    return text(
        "Original migrated supplier debt recorded once. No purchase, receipt, sale or cash movement was created. Read the separate opening document; its settlement remains a distinct workflow.",
        "سُجل دين المورد المرحّل الأصلي مرة واحدة. لم يُنشأ شراء أو استلام أو بيع أو حركة نقدية. اقرأ المستند الافتتاحي المنفصل؛ ويبقى سداده مسارًا مستقلًا.") to LocalRecord(
        LocalRecordReference(LocalRecordKind.OPENING_SUPPLIER_OBLIGATION, value.id), literal(value.id.value),
        text("Opening obligation recorded", "سُجل الالتزام الافتتاحي"), emptyList())
}

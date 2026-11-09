package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.types.Revision

/** Actual protected documentary metadata, not receiving approval, stock or a payable. */
internal suspend fun purchasingDocumentaryWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val reference = query.reference
    if (reference != null && (reference.kind != LocalRecordKind.PURCHASING_DOCUMENT ||
            reference.parentId != null || query.cursor != null)) invalid()
    if (!access.installed.has(LocalPurchasingDocumentProfileV1.READ))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val after = parseCursor(query.cursor, "purchasing-documents")
    val request = LocalPurchasingDocumentQueryV1(documentId = reference?.id, after = after)
    val response = access.read(LocalPurchasingDocumentCodecV1.query(request))
    val result = LocalPurchasingDocumentCodecV1.readResult(response.payload)
    if (result.pending != null || result.items.size > LocalPurchasingDocumentProfileV1.PAGE_SIZE ||
        result.items.map { it.documentId }.distinct().size != result.items.size ||
        result.items.zipWithNext().any { (a, b) -> a.documentId.value >= b.documentId.value } ||
        after != null && result.items.any { it.documentId.value <= after.value } ||
        result.nextAfter != null && result.nextAfter != result.items.lastOrNull()?.documentId) unavailable()
    val detail = result.detail
    if (reference == null) {
        if (detail != null || result.items.any { it.parentOriginalId != null }) unavailable()
    } else if (result.items.isNotEmpty() || result.nextAfter != null ||
        detail != null && (detail.original.documentId != reference.id || detail.original.parentOriginalId != null)) unavailable()
    val files = result.items + listOfNotNull(detail?.original) + detail?.attachments.orEmpty()
    if (files.any { it.scope != access.view.scope || it.state != LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH ||
            it.qualification != LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS ||
            it.hashFormat != LocalPurchasingDocumentProfileV1.HASH_FORMAT ||
            it.byteLength !in 1..if (it.parentOriginalId == null) LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES
                else LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_BYTES }) unavailable()
    if (detail != null && ((detail.attachmentsRevision?.value ?: 0) < 1 ||
            detail.attachments.size > LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_COUNT ||
            detail.attachments.map { it.documentId }.distinct().size != detail.attachments.size ||
            detail.attachments.any { it.parentOriginalId != detail.original.documentId || it.supplierId != detail.original.supplierId } ||
            detail.attachments.sumOf { it.byteLength.toLong() } > LocalPurchasingDocumentProfileV1.MAX_TOTAL_ATTACHMENT_BYTES ||
            detail.duplicateCandidateIds.size > LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES ||
            detail.original.documentId in detail.duplicateCandidateIds ||
            detail.duplicateCandidateIds.zipWithNext().any { (a, b) -> a.value >= b.value })) unavailable()
    val records = buildList {
        result.items.filter { query.search.isBlank() || it.documentId.value == query.search ||
            it.supplierId.value == query.search || it.supplierReferenceOriginal.contains(query.search, ignoreCase = true) }
            .forEach { add(documentaryRecord(it, LocalRecordKind.PURCHASING_DOCUMENT)) }
        detail?.let {
            add(documentaryRecord(it.original, LocalRecordKind.PURCHASING_DOCUMENT, it.attachmentsRevision))
            it.attachments.forEach { child -> add(documentaryRecord(child, LocalRecordKind.PURCHASING_DOCUMENT_ATTACHMENT)) }
            it.duplicateCandidateIds.forEach { id -> add(LocalRecord(
                LocalRecordReference(LocalRecordKind.PURCHASING_DOCUMENT_DUPLICATE, id), literal(id.value),
                text("Possible duplicate — both originals retained", "تكرار محتمل — الأصلان محفوظان"), selectable = false)) }
            if (it.moreDuplicateCandidates) add(LocalRecord(
                LocalRecordReference(LocalRecordKind.PURCHASING_DOCUMENT_DUPLICATE, it.original.documentId),
                text("Additional local duplicate candidates exist", "توجد احتمالات تكرار محلية إضافية"), selectable = false))
        }
    }
    return LocalWorkflowPage(LocalWorkflow.PURCHASING, records, emptyList(),
        text("Captured original bytes and metadata only. This is not accepted evidence, goods receipt, invoice recognition or payment. Private source saving, Master transfer and protected native download are separate actions.",
            "حفظ للبايتات الأصلية والبيانات فقط؛ ليس قبول دليل أو استلام بضاعة أو اعتماد فاتورة أو دفعًا. حفظ المصدر الخاص ونقله للجهاز الرئيسي وتنزيله محليًا إجراءات منفصلة."),
        cursor("purchasing-documents", result.nextAfter))
}

private fun documentaryRecord(file: LocalPurchasingDocumentFileV1, kind: LocalRecordKind,
    attachmentsRevision: Revision? = null): LocalRecord = LocalRecord(
    LocalRecordReference(kind, file.documentId, parentId = file.parentOriginalId), literal(file.supplierReferenceOriginal),
    text("Captured at branch — unqualified documentary original", "محفوظ بالفرع — أصل مستندي غير مؤهل"),
    buildList {
        add(recordValue(text("Supplier", "المورد"), LocalText(file.supplierNames.english, file.supplierNames.arabic)))
        add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(file.supplierId.value)))
        add(recordValue(text("Original filename", "اسم الملف الأصلي"), literal(file.originalFileName)))
        add(recordValue(text("Declared media / purpose", "نوع الملف والغرض المعلن"), literal("${file.media.name} / ${file.purpose.name}")))
        add(recordValue(text("Exact original bytes / SHA-256", "حجم البايتات الأصلية وبصمتها"), literal("${file.byteLength} / ${file.exactBytesDigest.value}")))
        add(recordValue(text("Original actor / Begin operation", "الموظف الأصلي وعملية البدء"), literal("${file.originalActorId.value} / ${file.beginOperationId.value}")))
        add(recordValue(text("Captured at / original journal sequence", "وقت الحفظ وتسلسل السجل الأصلي"), literal("${file.capturedAt.value} / ${file.historyOrigin.sequence.value}")))
        attachmentsRevision?.let { add(recordValue(text("Attachments revision", "مراجعة المرفقات"), literal(it.value.toString()))) }
        file.documentDate?.let { add(recordValue(fieldLabel(LocalField.BUSINESS_DATE), literal(it.canonicalString()))) }
        file.correctionOf?.let { add(recordValue(text("Linked correction of", "تصحيح مرتبط بالأصل"), literal(it.value))) }
        file.correctionReason?.let { add(recordValue(fieldLabel(LocalField.REASON), literal(it))) }
    }, selectable = kind == LocalRecordKind.PURCHASING_DOCUMENT, supplierId = file.supplierId)

package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.PurchasingEvidencePurposeV1

/** Uses the existing entry/controller owner; lazy-list disposal never retains a second form. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalPurchasingDocumentCard(controller: LocalWorkflowController, state: LocalWorkspaceState, token: NavigationToken) {
    if (state.workflow != LocalWorkflow.PURCHASING || state.purchasingQuery.section != LocalPurchasingSection.DOCUMENTS) return
    if (!controller.purchasingDocumentDisplayCurrent(token)) {
        Notice(documentaryText("Document input needs a monitored current session. Reload current access; private unsaved form and file data are cleared when the session changes.",
            "يتطلب إدخال المستند جلسة سارية تتم مراقبتها. أعد قراءة الصلاحيات الحالية؛ تُمسح بيانات النموذج والملف الخاصة غير المحفوظة عند تغير الجلسة."))
        return
    }
    val documents = state.purchasingDocuments
    val ready = controller.purchasingDocumentReady(token)
    val fileBound = controller.purchasingDocumentFilesBound()
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(documentaryText("Original supplier document intake", "تسجيل مستندات المورد الأصلية"))
            Notice(documentaryText("Save the original file privately, then explicitly Transfer / resume that saved source. Branch capture is documentary only; it does not qualify evidence, receive stock, recognize an invoice or make a payment.",
                "احفظ الملف الأصلي بشكل خاص، ثم انقل أو استأنف ذلك المصدر المحفوظ صراحةً. الحفظ بالفرع مستندي فقط؛ لا يؤهل الدليل أو يستلم المخزون أو يعتمد فاتورة أو ينفذ دفعًا."))
            if (!fileBound) Notice(documentaryFileProblem(LocalPurchasingDocumentFileProblem.NOT_BOUND), caution = true)
            if (documents.draft == null && documents.completionRetry == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalPurchasingDocumentKind.entries.forEach { kind ->
                        OutlinedButton(onClick = { controller.openPurchasingDocument(kind, token) }, enabled = ready && fileBound,
                            modifier = Modifier.touchTarget()) { Text(documentaryKind(kind)) }
                    }
                }
            }
            documents.draft?.let { draft -> DocumentaryForm(draft, documents, ready, fileBound, controller, token) }
            documents.completionRetry?.let { retry ->
                SectionHeading(documentaryText("Review an attachment completion retry", "مراجعة إعادة إكمال المرفق"))
                IdentifierText(documentaryText("Saved child document ID", "معرف المستند المرفق المحفوظ"), retry.documentId.value)
                IdentifierText(documentaryText("Immutable refused completion operation", "عملية الإكمال المرفوضة الثابتة"), retry.refusedCompleteOperationId.value)
                Notice(documentaryText("Enter the parent's current attachments revision after reviewing its metadata. Saving this retry retains the original file and refusal. Transfer remains a separate action.",
                    "أدخل مراجعة مرفقات الأصل الأب الحالية بعد مراجعة بياناته. يحفظ إعداد المحاولة الملف الأصلي والرفض السابق. يظل النقل إجراءً منفصلًا."))
                DocumentaryInput(retry.parentAttachmentsRevision, documentaryText("Reviewed parent attachments revision", "مراجعة مرفقات الأصل الأب التي تمت مراجعتها"),
                    ready && !retry.saveAttempted, integer = true,
                    error = documents.inputIssue?.field == LocalPurchasingDocumentField.PARENT_ATTACHMENTS_REVISION) {
                    controller.editPurchasingDocumentCompletionRevision(it, token)
                }
                documents.inputIssue?.let { Notice(LocalAppLanguage.current.text(it.message), caution = true) }
                if (retry.saveAttempted) Notice(documentaryText("The reviewed revision and original refusal identity are fixed for this save retry.",
                    "ثبتت المراجعة التي تمت مراجعتها ومعرف الرفض الأصلي لإعادة محاولة الحفظ هذه."))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.savePurchasingDocumentCompletionRetry(token) }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(documentaryText("Save reviewed completion retry", "حفظ إعادة الإكمال التي تمت مراجعتها")) }
                    OutlinedButton(onClick = { controller.discardPurchasingDocument(token) }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(documentaryText("Clear private retry form", "مسح نموذج إعادة المحاولة الخاص")) }
                }
            }
            documents.saveResult?.let { result -> DocumentaryPrivateSaveStatus(result, completion = false) }
            documents.completionSaveResult?.let { result -> DocumentaryPrivateSaveStatus(result, completion = true) }

            SectionHeading(documentaryText("Saved private sources — transfer / resume", "المصادر الخاصة المحفوظة — نقل أو استئناف"))
            Notice(documentaryText("At most $LOCAL_PURCHASING_DOCUMENT_SOURCE_LIMIT private sources can be retained for this employee on this device. Captured, refused and unresolved sources still occupy capacity. This list shows sources available under current access.",
                "يمكن حفظ $LOCAL_PURCHASING_DOCUMENT_SOURCE_LIMIT مصادر خاصة كحد أقصى لهذا الموظف على هذا الجهاز. تستمر المصادر المحفوظة بالفرع والمرفوضة وغير المحسومة في شغل السعة. تعرض هذه القائمة المصادر المتاحة بالصلاحيات الحالية."))
            OutlinedButton(onClick = { controller.refreshPurchasingDocumentSources(token) }, enabled = ready,
                modifier = Modifier.touchTarget()) { Text(documentaryText("Read saved private sources", "قراءة المصادر الخاصة المحفوظة")) }
            val sources = documents.sources
            when (sources) {
                null -> Text(documentaryText("Saved private sources have not been read yet.", "لم تُقرأ المصادر الخاصة المحفوظة بعد."), style = MaterialTheme.typography.bodySmall)
                is LocalPurchasingDocumentSourcesResult.Unavailable -> Notice(localProblem(sources.problem), caution = true)
                is LocalPurchasingDocumentSourcesResult.Available -> {
                    if (sources.sources.isEmpty()) Text(documentaryText("No private sources are available under current access.",
                        "لا توجد مصادر خاصة متاحة بالصلاحيات الحالية."), style = MaterialTheme.typography.bodySmall)
                    sources.sources.forEach { source -> key(source.documentId.value) {
                        Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Outline)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                IdentifierText(documentaryText("Saved document ID", "معرف المستند المحفوظ"), source.documentId.value)
                                Text(source.originalFileName)
                                Text(documentaryText("Exact original size: ${source.originalByteLength} bytes", "حجم الأصل الدقيق: ${source.originalByteLength} بايت"),
                                    style = MaterialTheme.typography.bodySmall)
                                source.parentOriginalId?.let { IdentifierText(documentaryText("Parent original ID", "معرف الأصل الأب"), it.value) }
                                val transfer = documents.transfers[source.documentId]
                                if (transfer == null) Text(documentaryText("Saved privately; branch transfer status has not been read here.",
                                    "محفوظ بشكل خاص؛ لم تُقرأ حالة نقله إلى الفرع هنا."), style = MaterialTheme.typography.bodySmall)
                                else DocumentaryTransferStatus(transfer)
                                documents.completionOriginalRefusals[source.documentId]?.let { operationId ->
                                    IdentifierText(documentaryText("Retained original refused completion", "عملية الإكمال الأصلية المرفوضة المحفوظة"), operationId.value)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { controller.transferPurchasingDocument(source.documentId, token) }, enabled = ready && !documents.hasDraft,
                                        modifier = Modifier.touchTarget()) { Text(documentaryText("Transfer / resume same original", "نقل أو استئناف الأصل نفسه")) }
                                    val completionSaved = documents.completionSaveResult.let { it is LocalPurchasingDocumentSourceResult.Saved && it.documentId == source.documentId }
                                    if (source.parentOriginalId != null && transfer is LocalPurchasingDocumentTransferResult.Rejected &&
                                        transfer.code == "purchasing.document.stale_revision" && !completionSaved) {
                                        OutlinedButton(onClick = { controller.reviewPurchasingDocumentCompletion(source.documentId, token) },
                                            enabled = ready && !documents.hasDraft, modifier = Modifier.touchTarget()) {
                                            Text(documentaryText("Review current parent revision", "مراجعة مراجعة الأصل الأب الحالية"))
                                        }
                                    }
                                }
                            }
                        }
                    } }
                }
            }
            val shownIds = (sources as? LocalPurchasingDocumentSourcesResult.Available)?.sources?.map { it.documentId }.orEmpty().toSet()
            documents.transfers.filterKeys { it !in shownIds }.values.forEach { DocumentaryTransferStatus(it) }

            SectionHeading(documentaryText("Download a captured original", "تنزيل أصل محفوظ بالفرع"))
            val targets = controller.purchasingDocumentDownloadTargets(token)
            if (targets.isEmpty()) Text(documentaryText("Select a captured original in the document browser to download its exact file or a listed attachment.",
                "اختر أصلًا محفوظًا في متصفح المستندات لتنزيل ملفه الدقيق أو مرفق مدرج له."), style = MaterialTheme.typography.bodySmall)
            targets.forEach { target -> key("download-${target.reference.id.value}") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IdentifierText(documentaryText(if (target.reference.kind == LocalRecordKind.PURCHASING_DOCUMENT_ATTACHMENT) "Attachment ID" else "Original ID",
                        if (target.reference.kind == LocalRecordKind.PURCHASING_DOCUMENT_ATTACHMENT) "معرف المرفق" else "معرف الأصل"), target.reference.id.value)
                    Text(LocalAppLanguage.current.text(target.title))
                    OutlinedButton(onClick = { controller.downloadPurchasingDocument(target.reference.id, token) }, enabled = ready && fileBound && !documents.hasDraft,
                        modifier = Modifier.touchTarget()) { Text(documentaryText("Choose destination and download original bytes", "اختيار وجهة وتنزيل البايتات الأصلية")) }
                }
            } }
            documents.download?.let { result ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    when (result) {
                        is LocalPurchasingDocumentDownloadResult.Saved -> {
                            IdentifierText(documentaryText("Downloaded document ID", "معرف المستند الذي تم تنزيله"), result.documentId.value)
                            Notice(documentaryText("The original file was saved to the chosen destination.", "حُفظ الملف الأصلي في الوجهة التي اخترتها."))
                        }
                        is LocalPurchasingDocumentDownloadResult.Cancelled -> Text(documentaryText("Destination choice cancelled.", "أُلغي اختيار الوجهة."))
                        is LocalPurchasingDocumentDownloadResult.Unavailable -> {
                            IdentifierText(documentaryText("Document ID", "معرف المستند"), result.documentId.value)
                            Notice(documentaryText("Original download was not confirmed. Check the selected destination before retrying.",
                                "لم يتأكد تنزيل الأصل. افحص الوجهة التي اخترتها قبل إعادة المحاولة."), caution = true)
                            Text(localProblem(result.problem), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentaryForm(draft: LocalPurchasingDocumentDraft, documents: LocalPurchasingDocumentPresentation, ready: Boolean,
    fileBound: Boolean, controller: LocalWorkflowController, token: NavigationToken) {
    val editable = ready && !draft.saveAttempted
    SectionHeading(documentaryKind(draft.kind))
    val issueField = documents.inputIssue?.field
    val edit: (LocalPurchasingDocumentField, String) -> Unit = { field, value -> controller.editPurchasingDocument(field, value, token) }
    DocumentaryDraftField(draft.documentId, LocalPurchasingDocumentField.DOCUMENT_ID, "Document ID (lowercase UUID)", "معرف المستند (UUID بأحرف صغيرة)", editable, issueField, edit)
    if (draft.documentId.isEmpty()) OutlinedButton(onClick = { controller.newPurchasingDocumentId(token) }, enabled = editable,
        modifier = Modifier.touchTarget()) { Text(documentaryText("Create a new document ID", "إنشاء معرف مستند جديد")) }
    DocumentaryDraftField(draft.supplierId, LocalPurchasingDocumentField.SUPPLIER_ID, "Exact supplier ID", "معرف المورد الدقيق", editable, issueField, edit)
    DocumentaryDraftField(draft.supplierRevision, LocalPurchasingDocumentField.SUPPLIER_REVISION, "Current supplier revision", "مراجعة المورد الحالية", editable, issueField, edit, integer = true)
    DocumentaryDraftField(draft.supplierReferenceOriginal, LocalPurchasingDocumentField.SUPPLIER_REFERENCE, "Original supplier reference — retained exactly", "مرجع المورد الأصلي — يُحفظ حرفيًا", editable, issueField, edit, ltr = false)
    DocumentaryDraftField(draft.documentDate, LocalPurchasingDocumentField.DOCUMENT_DATE, "Document date YYYY-MM-DD (optional)", "تاريخ المستند YYYY-MM-DD (اختياري)", editable, issueField, edit)
    Text(documentaryText("Declared purpose", "الغرض المعلن"), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PurchasingEvidencePurposeV1.entries.forEach { purpose ->
            FilterChip(selected = draft.purpose == purpose, enabled = editable,
                onClick = { controller.choosePurchasingDocumentPurpose(purpose, token) },
                label = { Text(LocalAppLanguage.current.text(documentaryPurpose(purpose))) }, modifier = Modifier.touchTarget())
        }
    }
    when (draft.kind) {
        LocalPurchasingDocumentKind.PRIMARY -> Unit
        LocalPurchasingDocumentKind.ATTACHMENT -> {
            DocumentaryDraftField(draft.parentOriginalId, LocalPurchasingDocumentField.PARENT_ORIGINAL_ID, "Captured parent original ID", "معرف الأصل الأب المحفوظ", editable, issueField, edit)
            DocumentaryDraftField(draft.parentAttachmentsRevision, LocalPurchasingDocumentField.PARENT_ATTACHMENTS_REVISION, "Current parent attachments revision", "مراجعة مرفقات الأصل الأب الحالية", editable, issueField, edit, integer = true)
        }
        LocalPurchasingDocumentKind.CORRECTION -> {
            DocumentaryDraftField(draft.correctionOf, LocalPurchasingDocumentField.CORRECTION_OF, "Original document ID being corrected", "معرف المستند الأصلي الذي يجري تصحيحه", editable, issueField, edit)
            DocumentaryDraftField(draft.correctionReason, LocalPurchasingDocumentField.CORRECTION_REASON, "Correction reason", "سبب التصحيح", editable, issueField, edit, ltr = false)
            Text(documentaryText("A correction is a distinct linked original. The earlier original remains retained.", "التصحيح أصل مستقل مرتبط. يبقى الأصل السابق محفوظًا."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick = { controller.importPurchasingDocument(token) }, enabled = editable && fileBound,
        modifier = Modifier.touchTarget()) { Text(documentaryText("Choose original JPEG, PNG or PDF", "اختيار الأصل بصيغة JPEG أو PNG أو PDF")) }
    documents.selectedFile?.let { file ->
        OutlinedTextField(value = file.originalFileName, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(documentaryText("Original filename from chosen file", "اسم الملف الأصلي من الملف المختار")) }, modifier = Modifier.fillMaxWidth())
        Text(documentaryText("Chosen original: ${file.media.name}, ${file.byteCount} exact bytes", "الأصل المختار: ${file.media.name}، ${file.byteCount} بايت دقيق"),
            style = MaterialTheme.typography.bodySmall)
    }
    Text(documentaryText(if (draft.kind == LocalPurchasingDocumentKind.ATTACHMENT) "Attachment limit: 256 KiB. The original bytes are retained without conversion."
        else "Original file limit: 512 KiB. The original bytes are retained without conversion.",
        if (draft.kind == LocalPurchasingDocumentKind.ATTACHMENT) "حد المرفق: 256 KiB. تُحفظ البايتات الأصلية دون تحويل."
        else "حد الملف الأصلي: 512 KiB. تُحفظ البايتات الأصلية دون تحويل."), style = MaterialTheme.typography.bodySmall)
    documents.fileProblem?.let { Notice(documentaryFileProblem(it), caution = true) }
    if (documents.importCancelled) Text(documentaryText("File choice cancelled; no transfer was started.", "أُلغي اختيار الملف؛ لم يبدأ أي نقل."), style = MaterialTheme.typography.bodySmall)
    documents.inputIssue?.let { Notice(LocalAppLanguage.current.text(it.message), caution = true) }
    if (draft.saveAttempted) Notice(documentaryText("This save attempt fixes the metadata, document ID and chosen original bytes. Retry this same input or clear only the private form; saved sources are retained.",
        "تثبت محاولة الحفظ هذه البيانات ومعرف المستند والبايتات الأصلية المختارة. أعد محاولة المدخل نفسه أو امسح النموذج الخاص فقط؛ تبقى المصادر المحفوظة محفوظة."))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { controller.savePurchasingDocument(token) }, enabled = ready && documents.selectedFile != null,
            modifier = Modifier.touchTarget()) { Text(documentaryText(if (draft.saveAttempted) "Retry private save of same ID and bytes" else "Save original privately — no transfer",
                if (draft.saveAttempted) "إعادة الحفظ الخاص لنفس المعرف والبايتات" else "حفظ الأصل بشكل خاص — دون نقل")) }
        OutlinedButton(onClick = { controller.discardPurchasingDocument(token) }, enabled = ready,
            modifier = Modifier.touchTarget()) { Text(documentaryText("Clear private form and file", "مسح النموذج والملف الخاصين")) }
    }
}

@Composable
private fun DocumentaryDraftField(value: String, field: LocalPurchasingDocumentField, english: String, arabic: String,
    enabled: Boolean, issueField: LocalPurchasingDocumentField?, edit: (LocalPurchasingDocumentField, String) -> Unit,
    integer: Boolean = false, ltr: Boolean = true) {
    DocumentaryInput(value, documentaryText(english, arabic), enabled, integer = integer, ltr = ltr,
        error = issueField == field) { edit(field, it) }
}

@Composable
private fun DocumentaryInput(value: String, label: String, enabled: Boolean, integer: Boolean = false, ltr: Boolean = true,
    error: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, enabled = enabled, singleLine = true, isError = error,
        label = { Text(label) }, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (integer) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DocumentaryPrivateSaveStatus(result: LocalPurchasingDocumentSourceResult, completion: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (result) {
            is LocalPurchasingDocumentSourceResult.Saved -> {
                IdentifierText(documentaryText("Privately saved document ID", "معرف المستند المحفوظ بشكل خاص"), result.documentId.value)
                Notice(if (completion) documentaryText("Reviewed completion retry saved privately. The original source and recorded refusal remain retained. Use Transfer / resume separately.",
                    "حُفظت إعادة الإكمال التي تمت مراجعتها بشكل خاص. يبقى المصدر الأصلي والرفض المسجل محفوظين. استخدم النقل أو الاستئناف بشكل منفصل.")
                else documentaryText("The original source is saved privately on this device. Private save does not submit branch capture; read its transfer result separately.",
                    "حُفظ المصدر الأصلي بشكل خاص على هذا الجهاز. لا يرسل الحفظ الخاص عملية حفظ إلى الفرع؛ اقرأ نتيجة النقل بشكل منفصل."))
            }
            is LocalPurchasingDocumentSourceResult.Unavailable -> {
                IdentifierText(documentaryText("Original document ID", "معرف المستند الأصلي"), result.documentId.value)
                Notice(documentaryText("Private saving was not confirmed. Retry the same fixed input or read saved sources; an unavailable result does not remove an original.",
                    "لم يتأكد الحفظ الخاص. أعد محاولة المدخل الثابت نفسه أو اقرأ المصادر المحفوظة؛ لا تُزيل النتيجة غير المتاحة أصلًا."), caution = true)
                Text(localProblem(result.problem), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DocumentaryTransferStatus(result: LocalPurchasingDocumentTransferResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (result) {
            is LocalPurchasingDocumentTransferResult.Captured -> {
                StatusPill(documentaryText("Captured at branch — documentary only", "محفوظ بالفرع — مستندي فقط"))
                IdentifierText(documentaryText("Complete operation ID", "معرف عملية الإكمال"), result.completeOperationId.value)
            }
            is LocalPurchasingDocumentTransferResult.Rejected -> {
                StatusPill(documentaryText("Recorded refusal — original retained", "رفض مسجل — الأصل محفوظ"), caution = true)
                IdentifierText(documentaryText("Immutable refused operation ID", "معرف العملية المرفوضة الثابت"), result.operationId.value)
                Text(result.code, style = MaterialTheme.typography.bodySmall)
            }
            is LocalPurchasingDocumentTransferResult.Unresolved -> {
                StatusPill(documentaryText("Transfer unresolved — resume same source", "النقل غير محسوم — استأنف المصدر نفسه"), caution = true)
                IdentifierText(documentaryText("Original document ID", "معرف المستند الأصلي"), result.documentId.value)
                result.operationId?.let { IdentifierText(documentaryText("Original unresolved operation ID", "معرف العملية الأصلية غير المحسومة"), it.value) }
                Text(localProblem(result.problem), style = MaterialTheme.typography.bodySmall)
                Text(documentaryText("No missing reply is treated as absence or rollback. Keep the saved source and resume its same original identities.",
                    "لا يُعامل الرد المفقود كغياب أو تراجع. احتفظ بالمصدر المحفوظ واستأنف المعرفات الأصلية نفسها."), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun documentaryKind(kind: LocalPurchasingDocumentKind): String = documentaryText(when (kind) {
    LocalPurchasingDocumentKind.PRIMARY -> "New original"
    LocalPurchasingDocumentKind.CORRECTION -> "New linked correction"
    LocalPurchasingDocumentKind.ATTACHMENT -> "New attachment"
}, when (kind) {
    LocalPurchasingDocumentKind.PRIMARY -> "أصل جديد"
    LocalPurchasingDocumentKind.CORRECTION -> "تصحيح جديد مرتبط"
    LocalPurchasingDocumentKind.ATTACHMENT -> "مرفق جديد"
})

private fun documentaryPurpose(purpose: PurchasingEvidencePurposeV1): LocalText = when (purpose) {
    PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY -> LocalText("Supplier delivery", "تسليم المورد")
    PurchasingEvidencePurposeV1.OWNERSHIP_ATTESTATION -> LocalText("Ownership attestation", "إفادة الملكية")
    PurchasingEvidencePurposeV1.INSPECTION -> LocalText("Inspection", "الفحص")
    PurchasingEvidencePurposeV1.PURCHASE_AGREEMENT -> LocalText("Purchase agreement", "اتفاق الشراء")
    PurchasingEvidencePurposeV1.SUPPLIER_INVOICE -> LocalText("Supplier invoice", "فاتورة المورد")
    PurchasingEvidencePurposeV1.SUPPLIER_DISPATCH -> LocalText("Supplier dispatch", "إرسال المورد")
    PurchasingEvidencePurposeV1.CLAIM_SUPPORT -> LocalText("Claim support", "دعم المطالبة")
}

@Composable
private fun documentaryFileProblem(problem: LocalPurchasingDocumentFileProblem): String = when (problem) {
    LocalPurchasingDocumentFileProblem.NOT_BOUND -> documentaryText("Native file selection and download are unavailable on this platform.", "اختيار الملفات وتنزيلها محليًا غير متاحين على هذه المنصة.")
    LocalPurchasingDocumentFileProblem.ACCESS_CHANGED -> documentaryText("Current file access ended before completion. Reload the session before choosing another file.", "انتهت صلاحية الوصول الحالية إلى الملف قبل الإكمال. أعد قراءة الجلسة قبل اختيار ملف آخر.")
    LocalPurchasingDocumentFileProblem.BUSY -> documentaryText("A native file choice is already open. Finish or cancel it first.", "يوجد اختيار ملف محلي مفتوح بالفعل. أكمله أو ألغِه أولًا.")
    LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME -> documentaryText("The original filename is unavailable or invalid.", "اسم الملف الأصلي غير متاح أو غير صالح.")
    LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA -> documentaryText("Choose a complete original JPEG, PNG or PDF file.", "اختر ملفًا أصليًا كاملًا بصيغة JPEG أو PNG أو PDF.")
    LocalPurchasingDocumentFileProblem.TOO_LARGE -> documentaryText("The chosen original exceeds the 512 KiB input limit. Attachments have a 256 KiB limit.", "يتجاوز الأصل المختار حد الإدخال 512 KiB. حد المرفقات 256 KiB.")
    LocalPurchasingDocumentFileProblem.EMPTY_FILE -> documentaryText("The chosen original file is empty.", "الملف الأصلي المختار فارغ.")
    LocalPurchasingDocumentFileProblem.PERMISSION_DENIED -> documentaryText("The native file provider denied access to the chosen file.", "رفض مزود الملفات المحلي الوصول إلى الملف المختار.")
    LocalPurchasingDocumentFileProblem.IO_FAILED, LocalPurchasingDocumentFileProblem.CLOSE_FAILED,
    LocalPurchasingDocumentFileProblem.OUTPUT_MISMATCH, LocalPurchasingDocumentFileProblem.CLEANUP_FAILED,
    LocalPurchasingDocumentFileProblem.ALREADY_USED -> documentaryText("Native file handling did not complete: ${problem.name}.", "لم تكتمل معالجة الملف المحلية: ${problem.name}.")
}

@Composable
private fun documentaryText(english: String, arabic: String): String = LocalAppLanguage.current.text(LocalText(english, arabic))

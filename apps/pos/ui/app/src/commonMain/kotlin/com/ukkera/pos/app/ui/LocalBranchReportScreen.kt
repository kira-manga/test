package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalBranchReportAmount
import com.ukkera.pos.app.LocalBranchReportAmountGroup
import com.ukkera.pos.app.LocalBranchReportDocument
import com.ukkera.pos.app.LocalBranchReportAuditStage
import com.ukkera.pos.app.LocalBranchReportExportCoverage
import com.ukkera.pos.app.LocalBranchReportExportHistory
import com.ukkera.pos.app.LocalBranchReportExportProgress
import com.ukkera.pos.app.LocalBranchReportExportRecord
import com.ukkera.pos.app.LocalBranchReportExportResult
import com.ukkera.pos.app.LocalBranchReportFilePhase
import com.ukkera.pos.app.LocalBranchReportFilePresence
import com.ukkera.pos.app.LocalBranchReportFileProblem
import com.ukkera.pos.app.LocalBranchReportPage
import com.ukkera.pos.app.LocalBranchReportPaymentCorrection
import com.ukkera.pos.app.LocalBranchReportRetailTenderPayment
import com.ukkera.pos.app.LocalBranchReportSection
import com.ukkera.pos.app.LocalBranchReportTenderBucket
import com.ukkera.pos.app.LocalBranchReportTenderClassification
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkspaceState
import com.ukkera.pos.app.LocalWorkBusy
import com.ukkera.pos.types.DomainId

/** Actual branch-workspace report route; never used to dress training/central data as local facts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalBranchReportScreen(
    state: LocalWorkspaceState,
    capabilityCurrent: Boolean,
    selectSection: (LocalBranchReportSection) -> Unit,
    selectReportSale: (DomainId) -> Unit,
    restart: () -> Unit,
    next: () -> Unit,
    retry: () -> Unit,
    filesBound: Boolean,
    export: (LocalBranchReportExportCoverage?) -> Unit,
    exportHistory: (Boolean) -> Unit,
    recoverExport: (DomainId) -> Unit,
    cancelExport: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val selectedReport = state.branchReportQuery.section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS
    var saleIdInput by remember(state.branchReportQuery.section, state.branchReportQuery.selectedSaleId) {
        mutableStateOf(state.branchReportQuery.selectedSaleId?.value.orEmpty())
    }
    val enteredSaleId = runCatching { DomainId(saleIdInput) }.getOrNull()
    val exporting = state.busy in setOf(LocalWorkBusy.REPORT_EXPORT, LocalWorkBusy.REPORT_EXPORT_LOOKUP)
    // A known-invalid session/connection cannot retain even a previously authorized page.
    val page = state.page?.branchReport?.takeIf { capabilityCurrent && (!state.working || exporting) && state.readProblem == null &&
        it.section == state.branchReportQuery.section && it.after == state.branchReportQuery.after &&
        it.selectedSaleId == state.branchReportQuery.selectedSaleId }
    val canRead = capabilityCurrent && !state.working
    val problem = state.readProblem ?: if (!capabilityCurrent) LocalServiceProblem.SESSION_ENDED else null
    LazyColumn(Modifier.widthIn(max = 1100.dp).fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionHeading(reportText("Current local document pages", "صفحات المستندات المحلية الحالية"),
                reportText("Operational branch data only · not fiscal statements, company totals or a complete period report.",
                    "بيانات تشغيلية للفرع فقط · ليست إقرارات ضريبية أو إجماليات للشركة أو تقريرًا كاملًا لفترة."))
        }
        item {
            Notice(reportText(
                "Period/calendar reports, inventory valuation/cost/margin and a complete incident register are not available in this profile. It reads durable local sources only; missing or inconsistent provenance makes the page unavailable, not zero.",
                "تقارير الفترات والتقويم وتقييم المخزون والتكلفة والهامش وسجل الحوادث الكامل غير متاحة في هذا الملف. يقرأ المصادر المحلية المحفوظة فقط؛ غياب دليل المصدر أو عدم اتساقه يجعل الصفحة غير متاحة، وليس صفرًا."))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(reportText("Document section", "قسم المستندات"), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalBranchReportSection.entries.forEach { section ->
                        FilterChip(selected = state.branchReportQuery.section == section, enabled = canRead,
                            onClick = { selectSection(section) }, label = { Text(reportSectionTitle(section)) },
                            modifier = Modifier.touchTarget())
                    }
                }
                Text(if (selectedReport) reportText(
                    "One explicitly selected paid sale per read. This does not scan or filter a section of eligible sales. Every request checks current access at the local master.",
                    "بيع مدفوع واحد محدد صراحة في كل قراءة. لا يتم مسح قسم المبيعات المؤهلة أو تصفيته. يتحقق الخادم المحلي من الصلاحية الحالية مع كل طلب.")
                    else reportText(
                    "At most five documents per page, ordered by document ID. No posting-date filter is available in this profile. Each new request checks current access at the local master.",
                    "خمسة مستندات كحد أقصى في الصفحة، مرتبة حسب معرف المستند. لا يتوفر مرشح لتاريخ الترحيل في هذا الملف. يتحقق الخادم المحلي من الصلاحية الحالية مع كل طلب جديد."),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Notice(reportSectionNotice(state.branchReportQuery.section))
                if (selectedReport) {
                    OutlinedTextField(value = saleIdInput, onValueChange = { if (it.length <= 36) saleIdInput = it },
                        enabled = canRead, singleLine = true, isError = saleIdInput.isNotEmpty() && enteredSaleId == null,
                        label = { Text(reportText("Selected paid-sale ID", "معرف البيع المدفوع المحدد")) },
                        supportingText = { Text(reportText(
                            "Enter the exact canonical lowercase ID. Typing does not change the displayed source; Read makes a fresh protected request.",
                            "أدخل المعرف الأصلي الدقيق بأحرف لاتينية صغيرة. الكتابة لا تغير المصدر المعروض؛ ينشئ زر القراءة طلبًا محميًا جديدًا.")) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { enteredSaleId?.let(selectReportSale) }, enabled = canRead && enteredSaleId != null,
                        modifier = Modifier.touchTarget()) {
                        Text(reportText("Read selected paid sale", "قراءة البيع المدفوع المحدد"))
                    }
                    if (state.branchReportQuery.selectedSaleId == null) Text(reportText(
                        "No paid sale is selected or read yet. No empty result, zero balance or eligible-sale census is inferred.",
                        "لم يُحدد أو يُقرأ بيع مدفوع بعد. لا تُستنتج نتيجة فارغة أو رصيد صفري أو قائمة بالمبيعات المؤهلة."),
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        if (state.working && capabilityCurrent) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (exporting) reportExportProgress(state.branchReportExport.progress)
                    else reportText("Reading the authorized local page…", "جارٍ قراءة الصفحة المحلية المصرح بها…"))
                if (exporting) OutlinedButton(onClick = cancelExport, modifier = Modifier.touchTarget()) {
                    Text(reportText("Stop waiting · keep recovery evidence", "إيقاف الانتظار · حفظ أدلة الاسترداد"))
                }
            }
        }
        problem?.let { value -> item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Notice(localProblem(value), caution = true,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                if (value != LocalServiceProblem.REPORT_RESTART_REQUIRED) {
                    Text(reportText("No retained report amounts are shown while this read is unavailable.",
                        "لا تُعرض مبالغ تقرير محفوظة أثناء تعذر هذه القراءة."), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = retry, enabled = canRead, modifier = Modifier.touchTarget()) {
                        Text(reportText("Retry this page", "إعادة محاولة هذه الصفحة"))
                    }
                }
            }
        } }
        if (page != null) {
            item { ReportReadMetadata(page) }
            if (page.documents.isEmpty()) item {
                Notice(reportText("No documents in this authorized page. This does not mean a zero balance or a complete branch report.",
                    "لا توجد مستندات في هذه الصفحة المصرح بها. هذا لا يعني رصيدًا صفريًا أو تقريرًا كاملًا للفرع."),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            // Retail and FOOD can genuinely share a UUID without sharing a document source.
            items(page.documents, key = { "${page.section.name}/${it.id.value}" }) { document ->
                ReportDocumentCard(page.section, document)
            }
            item {
                Text(reportText("Documents on this page: ${language.digits(page.documents.size.toString())}. Only this page is displayed; no totals are accumulated.",
                    "المستندات في هذه الصفحة: ${language.digits(page.documents.size.toString())}. تُعرض هذه الصفحة فقط؛ لا تُجمع إجماليات."),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = restart, enabled = canRead && (!selectedReport || state.branchReportQuery.selectedSaleId != null),
                    modifier = Modifier.touchTarget()) {
                    Text(if (selectedReport) reportText("Refresh this selected sale", "تحديث هذا البيع المحدد")
                        else reportText("Restart at first page", "البدء من الصفحة الأولى"))
                }
                if (!selectedReport) OutlinedButton(onClick = next, enabled = canRead && page?.nextAfter != null,
                    modifier = Modifier.touchTarget()) { Text(reportText("Next page · same read revision", "الصفحة التالية · نفس مراجعة القراءة")) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider()
                Text(reportText("Local CSV file delivery", "حفظ ملف CSV محلي"), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Notice(if (filesBound) reportText(
                    "Choose a file name in this app's fixed, private local report directory. Arbitrary folders and cloud providers are unavailable. Viewing does not grant export permission. No upload, automatic open or sharing is performed.",
                    "اختر اسم ملف في مجلد التقارير المحلي الخاص والثابت للتطبيق. المجلدات الأخرى وموفرو السحابة غير متاحين. صلاحية العرض لا تمنح صلاحية التصدير. لا يتم رفع الملف أو فتحه أو مشاركته تلقائيًا.")
                    else reportText("Native local file delivery is not bound or supported here. New exports are unavailable; existing files and recovery evidence are not erased.",
                        "حفظ الملفات المحلي الأصلي غير مربوط أو غير مدعوم هنا. التصدير الجديد غير متاح؛ ولا تُحذف الملفات أو أدلة الاسترداد السابقة."), caution = !filesBound)
                Text(if (selectedReport) reportText(
                    "Export freshly reads exactly this selected paid sale after its audit request and retains the exact sale ID/profile for recovery. The displayed VIEW cut stays unchanged. Complete-section export is unavailable. The whole CSV must fit 64 KiB; it is not a cash-session or period total.",
                    "يقرأ التصدير هذا البيع المدفوع المحدد فقط قراءة جديدة بعد طلب التدقيق ويحفظ المعرف والملف التعريفي الدقيقين للاسترداد. تظل مراجعة العرض دون تغيير. تصدير القسم الكامل غير متاح. يجب ألا يتجاوز ملف CSV كاملًا 64 كيلوبايت؛ وليس إجمالي جلسة نقدية أو فترة.")
                    else reportText(
                    "Export reads fresh data after recording its audit request; the displayed page stays unchanged. Export this page (at most five documents), or the complete current section from its first page (at most 128 data pages). The whole CSV must fit 64 KiB; a changed revision or exceeded bound stops publication, never saves a truncated section. Neither option is a period report or an accumulated total.",
                    "يقرأ التصدير بيانات جديدة بعد تسجيل طلب التدقيق؛ وتبقى الصفحة المعروضة دون تغيير. صدّر هذه الصفحة (خمسة مستندات كحد أقصى)، أو القسم الحالي كاملًا من الصفحة الأولى (128 صفحة بيانات كحد أقصى). يجب ألا يتجاوز ملف CSV كاملًا 64 كيلوبايت؛ تغير المراجعة أو تجاوز الحد يمنع النشر ولا يحفظ قسمًا مبتورًا. لا يمثل أي خيار تقرير فترة أو إجماليًا مجمعًا."),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { export(null) }, enabled = canRead && filesBound && page != null, modifier = Modifier.touchTarget()) {
                    Text(if (selectedReport) reportText("Choose local destination · export selected sale", "اختيار وجهة محلية · تصدير البيع المحدد")
                        else reportText("Choose local destination · export a fresh page", "اختيار وجهة محلية · تصدير قراءة جديدة للصفحة"))
                }
                if (!selectedReport) OutlinedButton(onClick = { export(LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2) },
                    enabled = canRead && filesBound && page != null, modifier = Modifier.touchTarget()) {
                    Text(reportText("Choose local destination · export complete current section", "اختيار وجهة محلية · تصدير القسم الحالي كاملًا"))
                }
                OutlinedButton(onClick = { exportHistory(false) }, enabled = canRead && filesBound, modifier = Modifier.touchTarget()) {
                    Text(reportText("Review original local export receipts", "مراجعة إيصالات التصدير المحلية الأصلية"))
                }
            }
        }
        if (capabilityCurrent) {
            state.branchReportExport.result?.let { result -> item {
                ReportExportResult(result, canRead && filesBound, recoverExport)
            } }
            when (val history = state.branchReportExport.history) {
                is LocalBranchReportExportHistory.Unavailable -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.serviceProblem?.let { Notice(localProblem(it), caution = true) }
                        history.fileProblem?.let { Notice(reportFileProblem(it), caution = true) }
                    }
                }
                is LocalBranchReportExportHistory.Available -> {
                    item {
                        Text(reportText("Original export receipt page · not a new export", "صفحة إيصالات التصدير الأصلية · ليست تصديرًا جديدًا"),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        if (history.records.isEmpty()) Notice(reportText(
                            "No receipts on this authorized local page. This does not prove that no file has ever been delivered.",
                            "لا توجد إيصالات في هذه الصفحة المحلية المصرح بها. هذا لا يثبت عدم تسليم أي ملف سابقًا."))
                    }
                    items(history.records, key = { "export-${it.receipt.exportId.value}" }) { record ->
                        ReportExportRecord(record, canRead && filesBound, recoverExport, detailed = false)
                    }
                    if (history.nextAfter != null) item {
                        OutlinedButton(onClick = { exportHistory(true) }, enabled = canRead && filesBound, modifier = Modifier.touchTarget()) {
                            Text(reportText("Next receipt page", "صفحة الإيصالات التالية"))
                        }
                    }
                }
                null -> Unit
            }
        }
    }
}

@Composable
private fun ReportExportResult(result: LocalBranchReportExportResult, enabled: Boolean, recover: (DomainId) -> Unit) {
    when (result) {
        LocalBranchReportExportResult.ChooserCancelled -> Notice(reportText(
            "Destination selection cancelled. No report export was requested.", "أُلغي اختيار الوجهة. لم يُطلب تصدير تقرير."))
        is LocalBranchReportExportResult.Outcome -> ReportExportRecord(result.record, enabled, recover, detailed = true)
        is LocalBranchReportExportResult.AuditRecovered -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Notice(reportText("Original audit acknowledgement recovered. Current local file inspection is unavailable; no file was created or republished by this recovery.",
                "استُعيد الإقرار الأصلي للتدقيق. فحص الملف المحلي غير متاح حاليًا؛ لم ينشئ هذا الاسترداد ملفًا ولم يُعد نشره."))
            ReportScalar(reportText("Original export ID", "معرف التصدير الأصلي"), result.exportId.value)
            result.originals.forEach { original ->
                ReportScalar(reportText("Recovered original operation ID", "معرف العملية الأصلية المستعادة"), original.operationId.value)
                Text(reportAuditStage(original.acknowledgement.stage), style = MaterialTheme.typography.bodySmall)
            }
            Notice(localProblem(result.serviceProblem), caution = true)
        }
        is LocalBranchReportExportResult.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            result.serviceProblem?.let { Notice(localProblem(it), caution = true) }
            result.fileProblem?.let { Notice(reportFileProblem(it), caution = true) }
            Notice(reportText("A stopped or unavailable operation is not proof that no file exists. Review the original local receipt; never repeat file delivery merely to resolve an audit reply.",
                "توقف العملية أو تعذرها لا يثبت عدم وجود ملف. راجع الإيصال المحلي الأصلي؛ لا تكرر حفظ الملف لمجرد حسم رد التدقيق."), caution = true)
            result.exportId?.let { id ->
                ReportScalar(reportText("Original export ID", "معرف التصدير الأصلي"), id.value)
                OutlinedButton(onClick = { recover(id) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                    Text(reportText("Inspect/reconcile this original export", "فحص وتسوية هذا التصدير الأصلي"))
                }
            }
        }
    }
}

@Composable
private fun ReportExportRecord(record: LocalBranchReportExportRecord, enabled: Boolean, recover: (DomainId) -> Unit, detailed: Boolean) {
    val language = LocalAppLanguage.current
    val receipt = record.receipt
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(reportText("Local file and audit outcome", "نتيجة الملف المحلي والتدقيق"), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Notice(reportExportOutcome(record), caution = !record.verifiedLocally || !record.auditAcknowledged,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            ReportScalar(reportText("Chosen local CSV name", "اسم ملف CSV المحلي المختار"), receipt.fileName)
            ReportScalar(reportText("Original export ID", "معرف التصدير الأصلي"), receipt.exportId.value)
            Text(if (receipt.query.selectedSaleId != null) reportText("Requested coverage: exactly one selected paid sale", "النطاق المطلوب: بيع مدفوع محدد واحد فقط")
                else if (receipt.query.coverage == null) reportText("Requested coverage: one freshly read page", "النطاق المطلوب: صفحة واحدة بقراءة جديدة")
                else reportText("Requested coverage: complete bounded current section · not a period total", "النطاق المطلوب: القسم الحالي كاملًا ضمن الحدود · ليس إجمالي فترة"),
                style = MaterialTheme.typography.bodySmall)
            receipt.query.selectedSaleId?.let { ReportScalar(reportText("Retained selected paid-sale ID", "معرف البيع المدفوع المحدد المحفوظ"), it.value) }
            Text(reportAuditStage(record.masterStage), style = MaterialTheme.typography.bodySmall)
            if (detailed) {
                ReportScalar(reportText("Native receipt ID", "معرف الإيصال الأصلي"), receipt.receiptId.value)
                Text(reportSectionTitle(LocalBranchReportSection.valueOf(receipt.query.section)), style = MaterialTheme.typography.bodyMedium)
                receipt.query.after?.let { ReportScalar(reportText("Export query after document ID", "استعلام التصدير بعد معرف المستند"), it.value) }
                receipt.source?.let { source ->
                    HorizontalDivider()
                    Text(reportText("Actual fresh export source · not the displayed VIEW cut", "مصدر التصدير الجديد الفعلي · ليس مراجعة الصفحة المعروضة"),
                        style = MaterialTheme.typography.titleSmall)
                    ReportScalar(reportText("Export source branch revision", "مراجعة فرع مصدر التصدير"), language.digits(source.branchRevision.value.toString()))
                    ReportScalar(reportText("Export source observed at · UTC", "وقت قراءة مصدر التصدير · UTC"), source.observedAt.canonicalString())
                    ReportScalar(reportText("CSV byte count", "عدد بايتات CSV"), language.digits(source.byteCount.toString()))
                    ReportScalar(reportText("Original CSV SHA-256", "بصمة SHA-256 لملف CSV الأصلي"), source.contentSha256)
                }
                val audit = receipt.recovery?.acknowledgement ?: receipt.finish?.acknowledgement ?: receipt.begin?.acknowledgement
                audit?.auditRevision?.let { ReportScalar(reportText("Logical audit revision · separate from source cut", "مراجعة التدقيق المنطقية · منفصلة عن مراجعة المصدر"),
                    language.digits(it.value.toString())) }
                record.serviceProblem?.let { Notice(localProblem(it), caution = true) }
            }
            OutlinedButton(onClick = { recover(receipt.exportId) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(reportText("Inspect original file / reconcile audit · no new save", "فحص الملف الأصلي / تسوية التدقيق · دون حفظ جديد"))
            }
        }
    }
}

@Composable
private fun reportExportOutcome(record: LocalBranchReportExportRecord): String = when (record.receipt.phase) {
    LocalBranchReportFilePhase.PUBLISHED -> when {
        record.verifiedLocally && record.auditAcknowledged -> reportText(
            "CSV saved locally. The completion audit acknowledges a client attestation, not master certification of the filesystem.",
            "حُفظ ملف CSV محليًا. يقر تدقيق الإكمال بإفادة العميل، وليس بتوثيق الخادم لنظام الملفات.")
        record.verifiedLocally -> reportText("CSV saved locally; audit acknowledgement pending. Keep the original receipt and do not save a second file to resolve the audit.",
            "حُفظ ملف CSV محليًا؛ إقرار التدقيق قيد الانتظار. احتفظ بالإيصال الأصلي ولا تحفظ ملفًا ثانيًا لحسم التدقيق.")
        record.presence == LocalBranchReportFilePresence.NOT_VERIFIED -> reportText(
            "A prior local publication receipt is retained, but the original file is missing, changed or cannot be verified now. This does not mean it was never delivered.",
            "إيصال النشر المحلي السابق محفوظ، لكن الملف الأصلي مفقود أو تغير أو يتعذر التحقق منه الآن. هذا لا يعني أنه لم يُسلّم سابقًا.")
        else -> reportText("A prior local publication receipt is retained. Current file presence/durability has not been inspected on this receipt page.",
            "إيصال النشر المحلي السابق محفوظ. لم يُفحص وجود الملف أو متانة حفظه الحالية في صفحة الإيصالات هذه.")
    }
    LocalBranchReportFilePhase.CANCELLED -> reportText("Cancelled before publication. Any private spool and lifecycle evidence remain retained; the master audit status is shown separately.",
        "أُلغي قبل النشر. تبقى المسودة الخاصة وأدلة دورة العملية محفوظة إن وجدت؛ وتظهر حالة تدقيق الخادم بشكل منفصل.")
    LocalBranchReportFilePhase.SELECTED, LocalBranchReportFilePhase.SPOOLED -> reportText(
        "An unfinished original destination/spool record remains. Inspect it explicitly; it is not a saved-file success or permission to publish after restart.",
        "يبقى سجل الوجهة أو المسودة الأصلي غير مكتمل. افحصه صراحةً؛ فهو ليس نجاحًا لحفظ ملف ولا إذنًا بالنشر بعد إعادة التشغيل.")
    LocalBranchReportFilePhase.PUBLICATION_ATTEMPTED, LocalBranchReportFilePhase.OUTCOME_UNKNOWN -> reportText(
        "Publication outcome unknown. A local file may already exist. Inspect the original receipt; a missing destination does not prove no earlier disclosure. No second file is created automatically.",
        "نتيجة النشر غير معروفة. قد يوجد ملف محلي بالفعل. افحص الإيصال الأصلي؛ غياب الوجهة لا يثبت عدم الكشف سابقًا. لا يُنشأ ملف ثانٍ تلقائيًا.")
}

@Composable
private fun reportAuditStage(stage: LocalBranchReportAuditStage?): String = when (stage) {
    null -> reportText("Master audit: no acknowledged result retained.", "تدقيق الخادم: لا توجد نتيجة مقر بها محفوظة.")
    LocalBranchReportAuditStage.REQUESTED -> reportText("Master audit: REQUESTED means outcome not reported, not proof that no file exists.",
        "تدقيق الخادم: مطلوب يعني أن النتيجة لم تُبلّغ، وليس دليلًا على عدم وجود ملف.")
    LocalBranchReportAuditStage.CLIENT_ATTESTED_PUBLISHED -> reportText("Master audit: client-attested publication; not an independent filesystem certificate.",
        "تدقيق الخادم: نشر بإفادة العميل؛ ليس توثيقًا مستقلًا لنظام الملفات.")
    LocalBranchReportAuditStage.CANCELLED_BEFORE_PUBLICATION -> reportText("Master audit: cancellation before publication reported by the client.",
        "تدقيق الخادم: أبلغ العميل بالإلغاء قبل النشر.")
    LocalBranchReportAuditStage.PUBLICATION_OUTCOME_UNKNOWN -> reportText("Master audit: publication outcome reported as unknown.", "تدقيق الخادم: أُبلغ بأن نتيجة النشر غير معروفة.")
    LocalBranchReportAuditStage.REJECTED -> reportText("Audit command rejected; this alone says nothing about whether a file exists.",
        "رُفض أمر التدقيق؛ هذا وحده لا يحدد ما إذا كان هناك ملف.")
}

@Composable
private fun reportFileProblem(problem: LocalBranchReportFileProblem): String = when (problem) {
    LocalBranchReportFileProblem.NOT_BOUND -> reportText("A current native file host is not bound.", "مضيف الملفات الأصلي الحالي غير مربوط.")
    LocalBranchReportFileProblem.UNSUPPORTED_STORAGE -> reportText("Required local storage ownership, non-overwrite or durability primitives are unavailable. This platform/storage path remains unverified.",
        "ضوابط ملكية التخزين المحلي أو عدم الاستبدال أو متانة الحفظ المطلوبة غير متاحة. تظل هذه المنصة أو الوجهة غير متحقق منها.")
    LocalBranchReportFileProblem.DESTINATION_REJECTED -> reportText("Choose a permitted ASCII .csv name inside the fixed app-owned local report directory; other folders are unavailable.",
        "اختر اسم .csv مسموحًا بأحرف لاتينية داخل مجلد التقارير المحلي الخاص والثابت للتطبيق؛ المجلدات الأخرى غير متاحة.")
    LocalBranchReportFileProblem.FILE_EXISTS -> reportText("That destination name already exists and was not replaced.", "اسم الوجهة موجود بالفعل ولم يُستبدل.")
    LocalBranchReportFileProblem.CAPACITY_REACHED -> reportText("The bounded native receipt store is full. Existing evidence is retained; no new export starts.",
        "مخزن الإيصالات الأصلي المحدود ممتلئ. الأدلة السابقة محفوظة؛ ولا يبدأ تصدير جديد.")
    LocalBranchReportFileProblem.OWNER_ENDED -> reportText("The original interactive or native host lifetime ended. Review its retained receipt after authorized sign-in.",
        "انتهت صلاحية التفاعل أو المضيف الأصلي. راجع الإيصال المحفوظ بعد تسجيل دخول مصرح به.")
    LocalBranchReportFileProblem.RECOVERY_REQUIRED -> reportText("Review the original local export receipt before another save. Stopping the wait is not cancellation of an already delivered file.",
        "راجع إيصال التصدير المحلي الأصلي قبل حفظ آخر. إيقاف الانتظار لا يلغي ملفًا سُلّم بالفعل.")
    LocalBranchReportFileProblem.STORAGE_UNCERTAIN -> reportText("Native storage outcome is uncertain. Retained files and journal tails are not erased or automatically retried.",
        "نتيجة التخزين الأصلي غير مؤكدة. لا تُحذف الملفات أو نهايات السجل المحفوظة ولا تُعاد المحاولة تلقائيًا.")
}

@Composable
private fun reportExportProgress(progress: LocalBranchReportExportProgress?): String = when (progress) {
    LocalBranchReportExportProgress.CHOOSING_DESTINATION -> reportText("Choosing a native local destination…", "جارٍ اختيار وجهة محلية أصلية…")
    LocalBranchReportExportProgress.BEGINNING_AUDIT -> reportText("Recording the original export audit request…", "جارٍ تسجيل طلب تدقيق التصدير الأصلي…")
    LocalBranchReportExportProgress.READING_CURRENT_PAGE -> reportText("Reading freshly authorized EXPORT data for the selected coverage…", "جارٍ قراءة بيانات تصدير جديدة مصرح بها للنطاق المحدد…")
    LocalBranchReportExportProgress.WRITING_LOCAL_FILE -> reportText("Writing the bounded local file with the original owner…", "جارٍ حفظ الملف المحلي المحدود بصلاحية المالك الأصلي…")
    LocalBranchReportExportProgress.REPORTING_OUTCOME -> reportText("Reporting the client-observed native outcome to the master audit…", "جارٍ إبلاغ تدقيق الخادم بالنتيجة الأصلية التي رصدها العميل…")
    LocalBranchReportExportProgress.RECOVERING -> reportText("Inspecting original export metadata/files; not saving a new file…", "جارٍ فحص بيانات وملفات التصدير الأصلية؛ دون حفظ ملف جديد…")
    null -> reportText("Retaining the original export recovery state…", "جارٍ حفظ حالة استرداد التصدير الأصلية…")
}

@Composable
private fun ReportReadMetadata(page: LocalBranchReportPage) {
    val language = LocalAppLanguage.current
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(reportText("This page's source", "مصدر هذه الصفحة"), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(reportSectionTitle(page.section), style = MaterialTheme.typography.bodyMedium)
            ReportScalar(reportText("Branch read revision", "مراجعة قراءة الفرع"), language.digits(page.branchRevision.value.toString()))
            ReportScalar(reportText("Observed at the local master · UTC", "وقت القراءة لدى الخادم المحلي · UTC"), page.observedAt.canonicalString())
            ReportScalar(reportText("Branch ID", "معرف الفرع"), page.branchId.value)
            ReportScalar(reportText("Authorized local actor ID", "معرف المستخدم المحلي المصرح له"), page.actorId.value)
            if (page.selectedSaleId != null) ReportScalar(reportText("Explicitly selected paid-sale ID", "معرف البيع المدفوع المحدد صراحة"), page.selectedSaleId.value)
            else if (page.after == null) Text(reportText("First document-ID page", "الصفحة الأولى حسب معرف المستند"),
                style = MaterialTheme.typography.bodySmall)
            else ReportScalar(reportText("Page after document ID", "الصفحة بعد معرف المستند"), page.after.value)
            Text(reportText("These amounts describe the recorded read time, not live balances. A different revision requires a new first-page read.",
                "تصف هذه المبالغ وقت القراءة المسجل، وليست أرصدة لحظية. تغير المراجعة يتطلب قراءة جديدة من الصفحة الأولى."),
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (page.section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS) Text(reportText(
                "Original and reconciled classifications use complete payment membership and correction heads at this local read cut only. No whole-session, period or remote-sync completeness is claimed.",
                "تستخدم التصنيفات الأصلية وبعد التسوية جميع الدفعات ورؤوس تصحيحاتها عند مراجعة القراءة المحلية هذه فقط. لا يُدعى اكتمال جلسة كاملة أو فترة أو مزامنة بعيدة."),
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (page.section == LocalBranchReportSection.PAYMENT_CORRECTIONS) Text(reportText(
                "Only linked corrections present at this local master read revision are included. This is not proof of period completeness or remote synchronization.",
                "لا تُدرج إلا التصحيحات المرتبطة الموجودة عند مراجعة قراءة الخادم المحلي هذه. هذا ليس إثباتًا لاكتمال فترة أو للمزامنة البعيدة."),
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun ReportDocumentCard(section: LocalBranchReportSection, document: LocalBranchReportDocument) {
    val language = LocalAppLanguage.current
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ReportScalar(when (section) {
                LocalBranchReportSection.PAYMENT_CORRECTIONS -> reportText("Linked correction ID", "معرف التصحيح المرتبط")
                LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS -> reportText("Original paid-sale ID", "معرف البيع المدفوع الأصلي")
                else -> reportText("Document ID", "معرف المستند")
            }, document.id.value)
            StatusPill(language.text(document.status), caution = document.pendingCount > 0 ||
                section == LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS)
            ReportScalar(if (section == LocalBranchReportSection.PAYMENT_CORRECTIONS)
                reportText("Linked correction position · not the close revision", "موضع التصحيح المرتبط · ليس مراجعة الإغلاق")
                else reportText("Document revision", "مراجعة المستند"), language.digits(document.revision.value.toString()))
            document.storageRevision?.let {
                ReportScalar(reportText("FOOD storage generation · distinct from document revision", "جيل تخزين الطعام · منفصل عن مراجعة المستند"),
                    language.digits(it.value.toString()))
            }
            document.relatedDocumentId?.let { ReportScalar(reportRelatedLabel(section), it.value) }
            document.cashSessionId?.let { ReportScalar(if (section == LocalBranchReportSection.PAYMENT_CORRECTIONS ||
                section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS)
                reportText("Original collecting cash session ID", "معرف جلسة التحصيل النقدية الأصلية")
                else reportText("Cash session ID", "معرف الجلسة النقدية"), it.value) }
            document.originalPaidDocumentId?.let {
                ReportScalar(reportText("Original paid-document ID before FOOD cancellation", "معرف مستند البيع المدفوع الأصلي قبل إلغاء الطعام"), it.value)
            }
            document.finalizedAt?.let {
                ReportScalar(reportText("Original FOOD paid-document finalization · UTC", "اعتماد مستند بيع الطعام المدفوع الأصلي · UTC"),
                    it.canonicalString())
            }
            document.cashSessionBusinessDate?.let {
                ReportScalar(reportText("Original cash-session business date · not a sale posting date", "تاريخ عمل الجلسة النقدية الأصلي · ليس تاريخ ترحيل البيع"),
                    language.digits(it.canonicalString()))
            }
            document.paymentCorrection?.let { ReportPaymentCorrectionMetadata(it) }
            document.retailTenderBreakdown?.let { detail ->
                ReportScalar(reportText("Original submitted close operation ID", "معرف عملية الإغلاق المقدم الأصلية"), detail.originalCloseOperationId.value)
                ReportScalar(reportText("Original accountable collector ID", "معرف المحصل الأصلي المسؤول"), detail.originalCollectorId.value)
            }
            if (section != LocalBranchReportSection.PAYMENT_CORRECTIONS && section != LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS)
                ReportScalar(if (section == LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS)
                reportText("Unresolved refund attempts", "محاولات رد المبلغ غير المحسومة")
                else reportText("Pending items in this document", "البنود قيد المتابعة في هذا المستند"),
                language.digits(document.pendingCount.toString()))
            if (section == LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS) Text(
                if (document.originalPaidDocumentId == null) reportText(
                    "No finalized paid document is linked. This cancellation liability uses actual applied collections, not a quoted or invented sale.",
                    "لا يرتبط مستند بيع مدفوع معتمد. يستند التزام الإلغاء إلى التحصيلات الفعلية المطبقة، وليس إلى سعر معروض أو بيع مختلق.")
                else reportText("The original finalized paid document is unchanged; cancellation refunds are separate monetary facts.",
                    "مستند البيع المدفوع الأصلي المعتمد لم يتغير؛ مبالغ رد الإلغاء وقائع مالية منفصلة."),
                style = MaterialTheme.typography.bodySmall, color = Muted)
            HorizontalDivider()
            if (section == LocalBranchReportSection.PAYMENT_CORRECTIONS) {
                Text(reportText("Original counted close · unchanged", "الإغلاق المعدود الأصلي · لم يتغير"),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(reportText(
                    "These original figures are repeated unchanged for this linked correction. Do not add them across correction rows.",
                    "تُكرر هذه القيم الأصلية دون تغيير لهذا التصحيح المرتبط. لا تجمعها عبر صفوف التصحيحات."),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                ReportAmountFields(document.amounts.filter { it.group == LocalBranchReportAmountGroup.ORIGINAL_CLOSE })
                HorizontalDivider()
                Text(reportText("Linked classification deltas · not money movement", "فروق التصنيف المرتبطة · ليست حركة أموال"),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                ReportAmountFields(document.amounts.filter { it.group == LocalBranchReportAmountGroup.LINKED_CORRECTION })
                Text(reportText(
                    "This row explains its linked classification only, not an entire closing variance or a period total. No new collection, refund, transfer, sale or stock effect is recorded by the correction. Original custody stays unchanged; its actor receives no cash.",
                    "يوضح هذا الصف تصنيفه المرتبط فقط، وليس فرق إغلاق كاملًا أو إجمالي فترة. لا يسجل التصحيح تحصيلًا أو رد مبلغ أو تحويلًا أو بيعًا أو أثر مخزون جديدًا. تظل العهدة الأصلية دون تغيير؛ ولا يستلم صاحب التصحيح نقدية."),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            } else if (section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS) ReportRetailTenderBreakdown(document)
            else ReportAmountFields(document.amounts)
        }
    }
}

@Composable
private fun ReportRetailTenderBreakdown(document: LocalBranchReportDocument) {
    val detail = document.retailTenderBreakdown
    if (detail == null) {
        Notice(reportText("Required complete payment membership is unavailable. No classification amounts are shown or inferred.",
            "جميع الدفعات المطلوبة غير متاحة. لا تُعرض أو تُستنتج مبالغ تصنيف."), caution = true)
        return
    }
    Notice(reportText(
        "Complete tender classification for this paid sale; not a whole cash session or period; no money movement.",
        "التصنيف الكامل لوسائل دفع هذا البيع المدفوع؛ ليس جلسة نقدية كاملة أو فترة؛ ودون حركة أموال."))
    ReportAmountFields(document.amounts.filter { it.group == LocalBranchReportAmountGroup.PAID_SALE })
    Text(reportText("Original counted close · unchanged", "الإغلاق المعدود الأصلي · لم يتغير"),
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    Text(reportText(
        "These original collecting-session close figures are repeated only as linked evidence. This sale's classification does not recompute them or cover the rest of that session.",
        "تُكرر قيم إغلاق جلسة التحصيل الأصلية كدليل مرتبط فقط. لا يعيد تصنيف هذا البيع حسابها ولا يغطي بقية تلك الجلسة."),
        style = MaterialTheme.typography.bodySmall, color = Muted)
    ReportAmountFields(document.amounts.filter { it.group == LocalBranchReportAmountGroup.ORIGINAL_CLOSE })
    HorizontalDivider()
    Text(reportText("Original recorded tender buckets · this sale only", "مجموعات وسائل الدفع الأصلية المسجلة · لهذا البيع فقط"),
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    ReportTenderBuckets(detail.originalBuckets)
    Text(reportText("Reconciled tender buckets · qualified linked corrections included", "مجموعات وسائل الدفع بعد التسوية · تشمل التصحيحات المرتبطة المتحقق منها"),
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    ReportTenderBuckets(detail.reconciledBuckets)
    Text(reportText(
        "Buckets group every original payment by method kind and configured tender ID. They classify the same immutable money, not a new charge, cash receipt, refund, transfer, sale or stock change. Policy snapshots remain on the individual payments below.",
        "تجمع المجموعات كل دفعة أصلية حسب نوع وسيلة الدفع ومعرف إعدادها. تصنف الأموال الثابتة نفسها، وليس تحصيلًا جديدًا أو استلام نقدية أو رد مبلغ أو تحويلًا أو بيعًا أو تغيير مخزون. تظل مراجعات السياسات محفوظة لكل دفعة أدناه."),
        style = MaterialTheme.typography.bodySmall, color = Muted)
    ReportAmountFields(document.amounts.filter { it.group == LocalBranchReportAmountGroup.PHYSICAL_MOVEMENT })
    HorizontalDivider()
    Text(reportText("Exact original payment links and reconciled heads", "روابط الدفعات الأصلية الدقيقة ورؤوس التصحيحات بعد التسوية"),
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    detail.payments.forEach { payment -> ReportRetailTenderPayment(payment) }
    if (detail.payments.isEmpty()) Text(reportText(
        "This qualified zero-amount paid sale has no original payment entries. No tender buckets are invented.",
        "هذا البيع المدفوع المتحقق منه ذو مبلغ صفري وليس له دفعات أصلية. لا تُختلق مجموعات وسائل دفع."),
        style = MaterialTheme.typography.bodySmall, color = Muted)
}

@Composable
private fun ReportTenderBuckets(buckets: List<LocalBranchReportTenderBucket>) {
    val language = LocalAppLanguage.current
    buckets.forEach { bucket ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(language.text(bucket.label), style = MaterialTheme.typography.bodyMedium)
            bucket.tenderId?.let { ReportScalar(reportText("Configured tender ID", "معرف وسيلة الدفع المُعدّة"), it.value) }
            Text(reportText("Sale payment classification amount · not new money", "مبلغ تصنيف دفعات البيع · ليس أموالًا جديدة"),
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(language.money(bucket.amount), style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (buckets.isEmpty()) Text(reportText("No payment classification buckets exist for this qualified sale.",
        "لا توجد مجموعات تصنيف دفعات لهذا البيع المتحقق منه."), style = MaterialTheme.typography.bodySmall, color = Muted)
}

@Composable
private fun ReportRetailTenderPayment(payment: LocalBranchReportRetailTenderPayment) {
    val language = LocalAppLanguage.current
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReportScalar(reportText("Original payment attempt ID", "معرف محاولة الدفع الأصلية"), payment.paymentAttemptId.value)
            Text(reportText("Original payment amount · unchanged", "مبلغ الدفع الأصلي · لم يتغير"),
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(language.money(payment.amount), style = MaterialTheme.typography.bodyLarge)
            ReportClassification(reportText("Original recorded classification", "التصنيف الأصلي المسجل"), payment.originalClassification)
            ReportClassification(reportText("Reconciled historical classification at this read cut", "التصنيف التاريخي بعد التسوية عند مراجعة القراءة هذه"),
                payment.reconciledClassification)
            ReportScalar(reportText("Linked corrections for this payment", "التصحيحات المرتبطة لهذه الدفعة"), language.digits(payment.correctionCount.toString()))
            payment.latestCorrectionId?.let { ReportScalar(reportText("Latest qualified correction ID", "معرف أحدث تصحيح متحقق منه"), it.value) }
            payment.latestCorrectionRecordedAt?.let { ReportScalar(reportText("Latest correction recorded at local master · UTC", "وقت تسجيل أحدث تصحيح لدى الخادم المحلي · UTC"),
                it.canonicalString()) }
            if (payment.correctionCount == 0L) Text(reportText("No linked correction at this local read cut; original classification is unchanged.",
                "لا يوجد تصحيح مرتبط عند مراجعة القراءة المحلية هذه؛ التصنيف الأصلي لم يتغير."),
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun ReportPaymentCorrectionMetadata(detail: LocalBranchReportPaymentCorrection) {
    ReportScalar(reportText("Original payment attempt ID", "معرف محاولة الدفع الأصلية"), detail.paymentAttemptId.value)
    ReportScalar(reportText("Original submitted close operation ID", "معرف عملية الإغلاق المقدم الأصلية"), detail.originalCloseOperationId.value)
    ReportScalar(reportText("Original accountable collector ID", "معرف المحصل الأصلي المسؤول"), detail.originalCollectorId.value)
    ReportScalar(reportText("Correction actor ID · not a cash recipient", "معرف صاحب التصحيح · ليس مستلم نقدية"), detail.correctionActorId.value)
    ReportScalar(reportText("Correction recorded at the local master · UTC", "وقت تسجيل التصحيح لدى الخادم المحلي · UTC"),
        detail.recordedAt.canonicalString())
    ReportClassification(reportText("Previous historical classification", "التصنيف التاريخي السابق"), detail.previousClassification)
    ReportClassification(reportText("Corrected historical classification", "التصنيف التاريخي المصحح"), detail.correctedClassification)
}

@Composable
private fun ReportClassification(label: String, classification: LocalBranchReportTenderClassification) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(language.text(classification.label), style = MaterialTheme.typography.bodyMedium)
        classification.tenderId?.let { ReportScalar(reportText("Tender configuration ID", "معرف إعداد وسيلة الدفع"), it.value) }
        classification.policyRevision?.let { ReportScalar(reportText("Tender policy revision", "مراجعة سياسة وسيلة الدفع"),
            language.digits(it.value.toString())) }
    }
}

@Composable
private fun ReportAmountFields(amounts: List<LocalBranchReportAmount>) {
    val language = LocalAppLanguage.current
    amounts.forEach { field ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(language.text(field.label), style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(language.money(field.amount), style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (amounts.isEmpty()) Text(reportText("No amount fields were returned for this document.", "لم تُرجع حقول مبالغ لهذا المستند."),
        style = MaterialTheme.typography.bodySmall)
}

/** Canonical identifiers and UTC values remain LTR without changing surrounding Arabic layout. */
@Composable
private fun ReportScalar(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
    }
}

@Composable
private fun reportText(english: String, arabic: String): String = LocalAppLanguage.current.text(LocalText(english, arabic))

@Composable
private fun reportSectionTitle(section: LocalBranchReportSection): String = when (section) {
    LocalBranchReportSection.CASH_SESSIONS -> reportText("Cash sessions", "الجلسات النقدية")
    LocalBranchReportSection.COLLECTIONS -> reportText("Retail collections", "تحصيلات التجزئة")
    LocalBranchReportSection.PAID_SALES -> reportText("Retail paid documents", "مستندات بيع التجزئة المدفوعة")
    LocalBranchReportSection.ACCEPTED_RETURNS -> reportText("Retail accepted returns", "مرتجعات التجزئة المقبولة")
    LocalBranchReportSection.SAFE_TRANSFERS -> reportText("Safe custody transfers", "تحويلات عهدة الخزنة")
    LocalBranchReportSection.FOOD_COLLECTIONS -> reportText("FOOD collections", "تحصيلات الطعام")
    LocalBranchReportSection.FOOD_PAID_SALES -> reportText("FOOD paid documents", "مستندات بيع الطعام المدفوعة")
    LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS -> reportText("FOOD cancellation obligations", "التزامات رد مبالغ إلغاء الطعام")
    LocalBranchReportSection.PAYMENT_CORRECTIONS -> reportText("Linked payment corrections", "تصحيحات الدفع المرتبطة")
    LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS -> reportText("Retail tender breakdown · selected sale", "تفصيل وسائل دفع التجزئة · بيع محدد")
}

@Composable
private fun reportRelatedLabel(section: LocalBranchReportSection): String = when (section) {
    LocalBranchReportSection.CASH_SESSIONS -> reportText("Drawer ID", "معرف الدرج")
    LocalBranchReportSection.COLLECTIONS, LocalBranchReportSection.PAID_SALES,
    LocalBranchReportSection.FOOD_COLLECTIONS, LocalBranchReportSection.FOOD_PAID_SALES -> reportText("Related order ID", "معرف الطلب المرتبط")
    LocalBranchReportSection.ACCEPTED_RETURNS -> reportText("Original paid-document ID", "معرف مستند البيع المدفوع الأصلي")
    LocalBranchReportSection.SAFE_TRANSFERS -> reportText("Receiving custody ID", "معرف عهدة الاستلام")
    LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS -> reportText("Original FOOD collection ID", "معرف تحصيل الطعام الأصلي")
    LocalBranchReportSection.PAYMENT_CORRECTIONS -> reportText("Original sale ID", "معرف البيع الأصلي")
    LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS -> reportText("Original order ID", "معرف الطلب الأصلي")
}

@Composable
private fun reportSectionNotice(section: LocalBranchReportSection): String = when (section) {
    LocalBranchReportSection.CASH_SESSIONS -> reportText(
        "Cash custody, actual collections/refunds and recorded closing counts are separate fields. Opening float and safe transfers are not revenue or expenses. Missing closing fields are not zero counts.",
        "العهدة النقدية والتحصيلات والمبالغ المردودة الفعلية وعد الإغلاق المسجل حقول منفصلة. العهدة الافتتاحية وتحويلات الخزنة ليست إيرادات أو مصروفات. غياب حقول الإغلاق لا يعني عدًا صفريًا.")
    LocalBranchReportSection.COLLECTIONS -> reportText(
        "Retail-kind documents, including normalized retail; FOOD has separate source sections. Applied collections are not paid-sales totals. Coverage is not finalization; unresolved physical-payment facts remain pending.",
        "مستندات نوع التجزئة، بما فيها التجزئة ذات التخزين المنظّم؛ للطعام أقسام مصادر منفصلة. التحصيلات المطبقة ليست إجمالي المبيعات المدفوعة. اكتمال التحصيل ليس اعتمادًا؛ تظل وقائع الدفع الفعلي غير المحسومة قيد المتابعة.")
    LocalBranchReportSection.PAID_SALES -> reportText(
        "Historical and normalized retail share this source. These are immutable paid-document amounts and original after-discount component allocations. The advertised discount is explanatory, not subtracted again. No retail posting date, tax return or fiscal filing is inferred.",
        "التجزئة التاريخية وذات التخزين المنظّم تشتركان في هذا المصدر. هذه قيم مستندات بيع مدفوعة غير قابلة للتغيير وتخصيصات مكونات أصلية بعد الخصم. الخصم المعلن للتوضيح ولا يُطرح مرة أخرى. لا يُستنتج تاريخ ترحيل للتجزئة أو إقرار أو ملف ضريبي.")
    LocalBranchReportSection.ACCEPTED_RETURNS -> reportText(
        "Accepted return amounts and original component allocations describe the obligation. Only actual applied refunds reduce it. Acceptance or approval is not a physical refund.",
        "تصف قيمة المرتجع المقبول وتخصيصات المكونات الأصلية الالتزام. لا تخفضه إلا المبالغ المردودة الفعلية المطبقة. القبول أو الموافقة ليس ردًا فعليًا للمبلغ.")
    LocalBranchReportSection.SAFE_TRANSFERS -> reportText(
        "Safe transfers move custody, not sales, revenue or expenses. An unacknowledged receipt stays visible as a pending custody item; this screen cannot acknowledge it.",
        "تنقل تحويلات الخزنة العهدة، وليست مبيعات أو إيرادات أو مصروفات. يظل الاستلام غير المؤكد بند عهدة قيد المتابعة؛ لا يمكن تأكيده من هذه الشاشة.")
    LocalBranchReportSection.FOOD_COLLECTIONS -> reportText(
        "Original locked FOOD batches and actual applied collections, not paid-sales totals. Whole-unsent cancellation ends collection but does not pay a refund: its remaining-collection field is not the separate cancellation refund liability. Pending physical-payment facts are not applied collections.",
        "دفعات الطعام الأصلية المثبتة والتحصيلات الفعلية المطبقة، وليست إجمالي المبيعات المدفوعة. إلغاء الطلب غير المرسل بالكامل ينهي التحصيل لكنه لا يرد مبلغًا: حقل التحصيل المتبقي ليس التزام رد مبلغ الإلغاء المنفصل. وقائع الدفع الفعلي قيد المتابعة ليست تحصيلات مطبقة.")
    LocalBranchReportSection.FOOD_PAID_SALES -> reportText(
        "Immutable original FOOD batch values and posted component allocations; no current recipe or price backfill. The advertised discount is explanatory, not subtracted again. Genuine finalization UTC is not a period/calendar index, preparation/fulfillment, receipt delivery or fiscal acceptance.",
        "قيم دفعات الطعام الأصلية غير القابلة للتغيير وتخصيصات مكوناتها المرحلة؛ دون استكمال من وصفة أو سعر حالي. الخصم المعلن للتوضيح ولا يُطرح مرة أخرى. وقت الاعتماد الفعلي بتوقيت UTC ليس فهرسًا لفترة أو تقويمًا أو إثبات تحضير أو تسليم أو قبولًا ضريبيًا.")
    LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS -> reportText(
        "FOOD refund payout v2 · durable whole-unsent cancellation obligations only. Original quote, actual collected tender liability, applied refunds and amount still due are distinct. UNKNOWN/unapplied facts do not reduce due or cash. Actual cash refunds are already included once in the paying cash session, not posted again as sales, stock or ingredients. Zero due alone does not prove a payout. Prepared/served-food remedies and alternate/external payouts remain unavailable.",
        "رد مبالغ الطعام v2 · التزامات إلغاء الطلب غير المرسل بالكامل والمحفوظة فقط. السعر الأصلي والتزام وسيلة الدفع المحصلة فعليًا والمبالغ المردودة المطبقة والمتبقي المستحق حقول منفصلة. الوقائع غير المحسومة أو غير المطبقة لا تخفض المستحق أو النقدية. يتضمن حساب الجلسة النقدية الدافعة المبالغ النقدية المردودة فعليًا مرة واحدة؛ ولا تُرحل مرة أخرى كمبيعات أو مخزون أو مكونات. صفر مستحق وحده لا يثبت الدفع. معالجة الطعام المُحضّر أو المُقدّم والرد بوسيلة بديلة أو خارجية تظل غير متاحة.")
    LocalBranchReportSection.PAYMENT_CORRECTIONS -> reportText(
        "Payment-correction v2 · original counted closes and separate linked historical classification deltas only. Counts, expected cash, submitted variance, collector and custody remain unchanged. Classification conserves the original amount; physical cash movement is zero. This is not a whole-close reconciliation, a period allocation or money received by the correction actor/current session.",
        "تصحيح الدفع v2 · الإغلاقات المعدودة الأصلية وفروق التصنيف التاريخي المرتبطة المنفصلة فقط. يظل العد والنقدية المتوقعة والفرق المقدم والمحصل والعهدة دون تغيير. يحافظ التصنيف على المبلغ الأصلي؛ وحركة النقدية الفعلية صفر. هذا ليس تسوية إغلاق كامل أو تخصيصًا لفترة أو أموالًا استلمها صاحب التصحيح أو الجلسة الحالية.")
    LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS -> reportText(
        "Selected normalized retail paid sale v3 · complete original and reconciled tender classification for this paid sale only, not a whole cash session or period; no money movement. The original counted close is unchanged. Missing or damaged sale, payment, close or correction-head provenance makes the selected read unavailable, never an empty result or convenient zero.",
        "بيع تجزئة مدفوع محدد ذو تخزين منظّم v3 · التصنيف الكامل الأصلي وبعد التسوية لوسائل دفع هذا البيع فقط، وليس جلسة نقدية كاملة أو فترة؛ ودون حركة أموال. الإغلاق الأصلي بعد العد لم يتغير. غياب دليل مصدر البيع أو الدفع أو الإغلاق أو رأس التصحيح أو تلفه يجعل القراءة المحددة غير متاحة، وليس نتيجة فارغة أو صفرًا ملائمًا.")
}

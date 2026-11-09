package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalBranchReportAmount
import com.ukkera.pos.app.LocalBranchReportAmountGroup
import com.ukkera.pos.app.LocalBranchReportDocument
import com.ukkera.pos.app.LocalBranchReportPage
import com.ukkera.pos.app.LocalBranchReportPaymentCorrection
import com.ukkera.pos.app.LocalBranchReportRetailTenderBreakdown
import com.ukkera.pos.app.LocalBranchReportRetailTenderPayment
import com.ukkera.pos.app.LocalBranchReportSection
import com.ukkera.pos.app.LocalBranchReportTenderBucket
import com.ukkera.pos.app.LocalBranchReportTenderClassification
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportProjectionV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRetailTenderPaymentV3
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.UtcInstant

/**
 * Real authenticated VIEW reads only. No report mutation, direct DAO, cached/export-shaped
 * projection, page/session/period totals, date inference or CSV delivery is implemented here.
 * GatewayAccess reauthorizes through the installed master client and its exact current lease.
 */
internal suspend fun branchReportWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val selection = query.branchReport ?: invalid()
    if (query.workflow != LocalWorkflow.REPORTS || query.search.isNotEmpty() || query.reference != null ||
        query.cursor != null || query.pricePreview != null) invalid()
    val selectedReport = selection.section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS
    // An unselected form is not a source request; never turn it into a scan or empty report.
    if (selectedReport && selection.selectedSaleId == null) invalid()
    val section = when (selection.section) {
        LocalBranchReportSection.CASH_SESSIONS -> LocalBranchReportSectionV1.CASH_SESSIONS
        LocalBranchReportSection.COLLECTIONS -> LocalBranchReportSectionV1.COLLECTIONS
        LocalBranchReportSection.PAID_SALES -> LocalBranchReportSectionV1.PAID_SALES
        LocalBranchReportSection.ACCEPTED_RETURNS -> LocalBranchReportSectionV1.ACCEPTED_RETURNS
        LocalBranchReportSection.SAFE_TRANSFERS -> LocalBranchReportSectionV1.SAFE_TRANSFERS
        LocalBranchReportSection.FOOD_COLLECTIONS -> LocalBranchReportSectionV1.FOOD_COLLECTIONS
        LocalBranchReportSection.FOOD_PAID_SALES -> LocalBranchReportSectionV1.FOOD_PAID_SALES
        LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS -> LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS
        LocalBranchReportSection.PAYMENT_CORRECTIONS -> LocalBranchReportSectionV1.PAYMENT_CORRECTIONS
        LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS -> LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS
    }
    val profile = currentReportProfile(section)
    val limit = if (selectedReport) 1 else 5
    val reply = access.read(LocalBranchReportReadCodec.request(LocalBranchReportQueryV1(
        section, after = selection.after, limit = limit, delivery = LocalBranchReportDeliveryV1.VIEW, profile = profile,
        selectedSaleId = selection.selectedSaleId)))
    // Discard the new cut, rather than append it, reinterpret it as the previous cut or
    // silently restart from the middle. A new first-page read requires an explicit reset.
    if (selection.expectedBranchRevision != null && selection.expectedBranchRevision != reply.branchRevision)
        throw GatewayFailure(LocalServiceProblem.REPORT_RESTART_REQUIRED)
    val page = LocalBranchReportReadCodec.decode(reply.payload) as? LocalBranchReportResultV1.Page ?: unavailable()
    val ids = page.rows.map { it.id.value }
    if (page.section != section || page.profile != profile || page.delivery != LocalBranchReportDeliveryV1.VIEW || page.after != selection.after ||
        page.selectedSaleId != selection.selectedSaleId || page.rows.size > limit || ids != ids.distinct().sorted() ||
        selection.after?.let { after -> ids.any { it <= after.value } } == true ||
        page.nextAfter != null && page.nextAfter != page.rows.lastOrNull()?.id ||
        selectedReport && (page.after != null || page.nextAfter != null || page.rows.size != 1 ||
            page.rows.single().id != selection.selectedSaleId)) unavailable()
    validateBranchReportRows(section, page.rows, reply.observedAt, profile)
    val documents = page.rows.map { row ->
        LocalBranchReportDocument(
            id = row.id,
            revision = row.revision,
            status = reportStatus(section, row.status, profile),
            relatedDocumentId = row.relatedDocumentId,
            cashSessionId = row.cashSessionId,
            cashSessionBusinessDate = row.businessDate,
            pendingCount = row.pendingCount,
            amounts = row.amounts.map { LocalBranchReportAmount(reportMetricLabel(section, it.metric), it.amount,
                reportMetricGroup(section, it.metric)) },
            storageRevision = row.storageRevision,
            finalizedAt = row.finalizedAt,
            originalPaidDocumentId = row.originalPaidDocumentId,
            paymentCorrection = row.paymentCorrection?.let { detail ->
                LocalBranchReportPaymentCorrection(detail.paymentAttemptId, detail.originalCloseOperationId,
                    detail.originalCollectorId, detail.correctionActorId,
                    reportClassification(detail.previousClassification), reportClassification(detail.correctedClassification),
                    detail.recordedAt)
            },
            retailTenderBreakdown = row.retailTenderBreakdown?.let { detail ->
                LocalBranchReportRetailTenderBreakdown(detail.originalCloseOperationId, detail.originalCollectorId,
                    detail.payments.map { payment ->
                        LocalBranchReportRetailTenderPayment(payment.paymentAttemptId, payment.amount,
                            reportClassification(payment.originalClassification), reportClassification(payment.reconciledClassification),
                            payment.latestCorrectionId, payment.correctionCount, payment.latestCorrectionRecordedAt)
                    }, reportTenderBuckets(detail.payments, reconciled = false), reportTenderBuckets(detail.payments, reconciled = true))
            },
        )
    }
    access.checkCurrent()
    return LocalWorkflowPage(LocalWorkflow.REPORTS, records = emptyList(), forms = emptyList(),
        branchReport = LocalBranchReportPage(selection.section, page.after, page.nextAfter,
            reply.branchRevision, reply.observedAt, access.view.actorId, access.view.scope.companyId,
            access.view.scope.branchId, documents, selectedSaleId = page.selectedSaleId))
}

/** Both fresh VIEW and fresh EXPORT cuts use this closed projection profile. It does not
 * prove source provenance or authorization; those remain the actual master's responsibility. */
internal fun currentReportProfile(section: LocalBranchReportSectionV1): LocalBranchReportProjectionV1? =
    when (section) {
        LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS -> LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2
        LocalBranchReportSectionV1.PAYMENT_CORRECTIONS -> LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2
        LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS -> LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3
        else -> null
    }

internal fun validateBranchReportRows(section: LocalBranchReportSectionV1, rows: List<LocalBranchReportRowV1>,
    observedAt: UtcInstant, profile: LocalBranchReportProjectionV1? = null) {
    val validProfile = when (profile) {
        null -> section != LocalBranchReportSectionV1.PAYMENT_CORRECTIONS && section != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS
        LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2 -> section == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS
        LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2 -> section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS
        LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3 -> section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS
    }
    if (!validProfile) unavailable()
    val allowed = reportMetrics(section, profile)
    for (row in rows) {
        if (section != LocalBranchReportSectionV1.PAYMENT_CORRECTIONS && row.paymentCorrection != null) unavailable()
        if (section != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS && row.retailTenderBreakdown != null) unavailable()
        if (row.amounts.any { it.metric !in allowed || it.amount.currency != Currency.EGP }) unavailable()
        reportStatus(section, row.status, profile) // Export cannot bypass the closed status family.
        val fields = row.amounts.map { it.metric }.toSet()
        val metadataValid = when (section) {
            LocalBranchReportSectionV1.CASH_SESSIONS ->
                row.storageRevision == null && row.finalizedAt == null && row.originalPaidDocumentId == null
            LocalBranchReportSectionV1.COLLECTIONS, LocalBranchReportSectionV1.PAID_SALES,
            LocalBranchReportSectionV1.ACCEPTED_RETURNS, LocalBranchReportSectionV1.SAFE_TRANSFERS ->
                row.storageRevision == null && row.finalizedAt == null && row.originalPaidDocumentId == null && row.businessDate == null
            LocalBranchReportSectionV1.FOOD_COLLECTIONS ->
                foodReportStorageRevision(row) && row.finalizedAt == null && row.originalPaidDocumentId == null &&
                    row.businessDate == null && row.relatedDocumentId != null && row.cashSessionId != null &&
                    row.pendingCount <= 64 && fields == allowed
            LocalBranchReportSectionV1.FOOD_PAID_SALES ->
                foodReportStorageRevision(row) && row.revision.value == 0L && row.finalizedAt != null &&
                    row.originalPaidDocumentId == null && row.businessDate == null && row.relatedDocumentId != null &&
                    row.cashSessionId != null && row.pendingCount == 0 && fields.containsAll(setOf(
                        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL_BEFORE_DISCOUNTS,
                        LocalBranchReportMetricV1.ADVERTISED_DISCOUNT, LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL))
            LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS ->
                foodReportStorageRevision(row) && row.finalizedAt == null && row.businessDate == null &&
                    row.relatedDocumentId != null && row.cashSessionId == null && fields == allowed &&
                    (if (profile == null) row.pendingCount == 0 else validFoodRefundPayout(row))
            LocalBranchReportSectionV1.PAYMENT_CORRECTIONS ->
                fields == allowed && LocalBranchReportReadCodec.validPaymentCorrectionRow(row, observedAt)
            LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS ->
                fields == allowed && LocalBranchReportReadCodec.validRetailTenderBreakdownRow(row, observedAt)
        }
        if (!metadataValid) unavailable()
    }
}

private fun foodReportStorageRevision(row: LocalBranchReportRowV1): Boolean =
    row.storageRevision?.let { it.value > 0 && it.value - 1 == row.revision.value } == true

/** Closed field families keep custody, collections, sales and return obligations distinct. */
private fun reportMetrics(section: LocalBranchReportSectionV1, profile: LocalBranchReportProjectionV1?): Set<LocalBranchReportMetricV1> = when (section) {
    LocalBranchReportSectionV1.CASH_SESSIONS -> setOf(
        LocalBranchReportMetricV1.OPENING_FLOAT, LocalBranchReportMetricV1.CASH_RECEIVED,
        LocalBranchReportMetricV1.CHANGE_GIVEN, LocalBranchReportMetricV1.CASH_REFUNDS,
        LocalBranchReportMetricV1.PAY_INS, LocalBranchReportMetricV1.PAY_OUTS, LocalBranchReportMetricV1.SAFE_DROPS,
        LocalBranchReportMetricV1.EXPECTED_CASH, LocalBranchReportMetricV1.RESERVED_CASH_OUT,
        LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS, LocalBranchReportMetricV1.EXTERNAL_REFUNDS,
        LocalBranchReportMetricV1.CLOSING_EXPECTED, LocalBranchReportMetricV1.ACTUAL_COUNT,
        LocalBranchReportMetricV1.VARIANCE, LocalBranchReportMetricV1.RETAINED_FLOAT,
        LocalBranchReportMetricV1.ACTUAL_SAFE_DELIVERY)
    LocalBranchReportSectionV1.COLLECTIONS, LocalBranchReportSectionV1.FOOD_COLLECTIONS -> setOf(
        LocalBranchReportMetricV1.LOCKED_PAYABLE, LocalBranchReportMetricV1.ACTUAL_COLLECTIONS,
        LocalBranchReportMetricV1.REMAINING_COLLECTION, LocalBranchReportMetricV1.CASH_COLLECTIONS,
        LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS)
    LocalBranchReportSectionV1.PAID_SALES -> setOf(
        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL_BEFORE_DISCOUNTS, LocalBranchReportMetricV1.ADVERTISED_DISCOUNT,
        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL, LocalBranchReportMetricV1.ITEM_NET,
        LocalBranchReportMetricV1.ITEM_TAX, LocalBranchReportMetricV1.CHARGE_NET, LocalBranchReportMetricV1.CHARGE_TAX)
    // The genuine FOOD profile currently posts original item net/tax only, not charges.
    LocalBranchReportSectionV1.FOOD_PAID_SALES -> setOf(
        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL_BEFORE_DISCOUNTS, LocalBranchReportMetricV1.ADVERTISED_DISCOUNT,
        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL, LocalBranchReportMetricV1.ITEM_NET, LocalBranchReportMetricV1.ITEM_TAX)
    LocalBranchReportSectionV1.ACCEPTED_RETURNS -> setOf(
        LocalBranchReportMetricV1.ACCEPTED_RETURN_AMOUNT, LocalBranchReportMetricV1.ACTUAL_REFUNDS,
        LocalBranchReportMetricV1.REFUND_DUE, LocalBranchReportMetricV1.CASH_REFUNDS,
        LocalBranchReportMetricV1.EXTERNAL_REFUNDS, LocalBranchReportMetricV1.ITEM_NET,
        LocalBranchReportMetricV1.ITEM_TAX, LocalBranchReportMetricV1.CHARGE_NET, LocalBranchReportMetricV1.CHARGE_TAX)
    LocalBranchReportSectionV1.SAFE_TRANSFERS -> setOf(LocalBranchReportMetricV1.TRANSFER_AMOUNT)
    LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS -> setOf(
        LocalBranchReportMetricV1.LOCKED_PAYABLE, LocalBranchReportMetricV1.CANCELLED_COLLECTION_REFUND_LIABILITY,
        LocalBranchReportMetricV1.REFUND_DUE) + if (profile == null) emptySet() else setOf(
            LocalBranchReportMetricV1.ACTUAL_REFUNDS, LocalBranchReportMetricV1.CASH_REFUNDS,
            LocalBranchReportMetricV1.EXTERNAL_REFUNDS)
    LocalBranchReportSectionV1.PAYMENT_CORRECTIONS -> setOf(
        LocalBranchReportMetricV1.CLOSING_EXPECTED, LocalBranchReportMetricV1.ACTUAL_COUNT,
        LocalBranchReportMetricV1.VARIANCE, LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA, LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA)
    LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS -> setOf(
        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL, LocalBranchReportMetricV1.CLOSING_EXPECTED,
        LocalBranchReportMetricV1.ACTUAL_COUNT, LocalBranchReportMetricV1.VARIANCE,
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA)
}

/** Literal scalar consistency only; genuine source/history checks remain at the master. */
private fun validFoodRefundPayout(row: LocalBranchReportRowV1): Boolean {
    val values = row.amounts.associate { it.metric to it.amount.minor }
    if (values.values.any { it < 0L } || row.pendingCount !in 0..64) return false
    val quoted = values.getValue(LocalBranchReportMetricV1.LOCKED_PAYABLE)
    val liability = values.getValue(LocalBranchReportMetricV1.CANCELLED_COLLECTION_REFUND_LIABILITY)
    val paid = values.getValue(LocalBranchReportMetricV1.ACTUAL_REFUNDS)
    val due = values.getValue(LocalBranchReportMetricV1.REFUND_DUE)
    // Only the installed original-CASH payout producer is supported. Unpaid external
    // original tender remains part of due; a report profile grants no external payout.
    if (liability > quoted || paid > liability || due != liability - paid ||
        values.getValue(LocalBranchReportMetricV1.CASH_REFUNDS) != paid ||
        values.getValue(LocalBranchReportMetricV1.EXTERNAL_REFUNDS) != 0L) return false
    return when (row.status) {
        "DUE" -> row.pendingCount == 0 && paid == 0L && due > 0L
        "PART_REFUNDED" -> row.pendingCount == 0 && paid > 0L && due > 0L
        "OUTCOME_UNKNOWN" -> row.pendingCount > 0 && due > 0L
        "SETTLED" -> row.pendingCount == 0 && due == 0L
        else -> false
    }
}

private fun reportStatus(section: LocalBranchReportSectionV1, status: String, profile: LocalBranchReportProjectionV1?): LocalText = when (section) {
    LocalBranchReportSectionV1.CASH_SESSIONS -> when (status) {
        "OPEN" -> text("Open cash session", "جلسة نقدية مفتوحة")
        "CLOSING" -> text("Cash session closing", "جلسة نقدية قيد الإغلاق")
        "SUBMITTED_CLOSED" -> text("Closed; closing report submitted", "مغلقة؛ قُدم تقرير الإغلاق")
        "REVIEWED" -> text("Closed; closing report reviewed", "مغلقة؛ روجع تقرير الإغلاق")
        else -> unavailable()
    }
    LocalBranchReportSectionV1.COLLECTIONS, LocalBranchReportSectionV1.FOOD_COLLECTIONS -> when (status) {
        "LOCKED_UNPAID" -> text("Locked; no applied collection", "مثبتة؛ لا يوجد تحصيل مطبق")
        "PART_PAID" -> text("Partially collected", "محصلة جزئيًا")
        "OUTCOME_UNKNOWN" -> text("Collection outcome needs checking", "نتيجة التحصيل تحتاج إلى مراجعة")
        "COVERED" -> text("Collection covered; paid document not finalized", "التحصيل مكتمل؛ مستند البيع المدفوع لم يُعتمد بعد")
        "FINALIZED_PAID" -> text("Collection finalized as paid", "التحصيل معتمد كمدفوع")
        "CANCELLED_REFUND_OBLIGATION" -> if (section == LocalBranchReportSectionV1.FOOD_COLLECTIONS)
            text("Whole-unsent check cancelled; separate refund obligation", "أُلغي الطلب غير المرسل بالكامل؛ التزام رد المبلغ منفصل") else unavailable()
        else -> unavailable()
    }
    LocalBranchReportSectionV1.PAID_SALES, LocalBranchReportSectionV1.FOOD_PAID_SALES -> if (status == "PAID_DOCUMENT")
        text("Recorded paid document", "مستند بيع مدفوع مسجل") else unavailable()
    LocalBranchReportSectionV1.ACCEPTED_RETURNS -> when (status) {
        "DUE" -> text("Refund obligation due", "التزام رد مبلغ مستحق")
        "PART_REFUNDED" -> text("Partially refunded", "رُد المبلغ جزئيًا")
        "OUTCOME_UNKNOWN" -> text("Refund outcome needs checking", "نتيجة رد المبلغ تحتاج إلى مراجعة")
        "SETTLED" -> text("Recorded refund obligation settled", "التزام رد المبلغ المسجل تمت تسويته")
        else -> unavailable()
    }
    LocalBranchReportSectionV1.SAFE_TRANSFERS -> when (status) {
        "AWAITING_RECEIPT" -> text("Awaiting recipient acknowledgement", "بانتظار تأكيد المستلم")
        "RECEIVED" -> text("Receipt acknowledged", "تم تأكيد الاستلام")
        else -> unavailable()
    }
    LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS -> when (status) {
        "DUE" -> text("Original-tender cancellation refund due", "رد مبلغ الإلغاء إلى وسيلة الدفع الأصلية مستحق")
        "PART_REFUNDED" -> if (profile != null) text("Cancellation refund partially applied", "رُد مبلغ الإلغاء جزئيًا وطُبق") else unavailable()
        "OUTCOME_UNKNOWN" -> if (profile != null) text("Cancellation refund pending or outcome needs checking", "رد مبلغ الإلغاء قيد المتابعة أو نتيجته تحتاج إلى مراجعة") else unavailable()
        "SETTLED" -> if (profile != null) text("No cancellation refund remains due · see actual applied refunds", "لا يتبقى رد مبلغ إلغاء مستحق · راجع المبالغ المردودة الفعلية المطبقة")
            else text("No cancellation refund due; not proof of a physical refund", "لا يوجد رد مبلغ إلغاء مستحق؛ ليس إثباتًا لرد مبلغ فعلي")
        else -> unavailable()
    }
    LocalBranchReportSectionV1.PAYMENT_CORRECTIONS ->
        if (profile == LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2 && status == "CLASSIFICATION_CORRECTED")
            text("Linked classification corrected · no money movement", "صُحح التصنيف المرتبط · دون حركة أموال")
        else unavailable()
    LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS ->
        if (profile == LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3 && status == "PAID_SALE_TENDER_BREAKDOWN")
            text("Paid-sale original and reconciled classification · no money movement", "تصنيف البيع المدفوع الأصلي وبعد التسوية · دون حركة أموال")
        else unavailable()
}

/** Groups literal fields without computing a reconciled count, variance or period total. */
private fun reportMetricGroup(section: LocalBranchReportSectionV1,
    metric: LocalBranchReportMetricV1): LocalBranchReportAmountGroup? = when (section) {
    LocalBranchReportSectionV1.PAYMENT_CORRECTIONS -> when (metric) {
        LocalBranchReportMetricV1.CLOSING_EXPECTED, LocalBranchReportMetricV1.ACTUAL_COUNT,
        LocalBranchReportMetricV1.VARIANCE -> LocalBranchReportAmountGroup.ORIGINAL_CLOSE
        LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA ->
            LocalBranchReportAmountGroup.LINKED_CORRECTION
        else -> unavailable()
    }
    LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS -> when (metric) {
        LocalBranchReportMetricV1.CLOSING_EXPECTED, LocalBranchReportMetricV1.ACTUAL_COUNT,
        LocalBranchReportMetricV1.VARIANCE -> LocalBranchReportAmountGroup.ORIGINAL_CLOSE
        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL -> LocalBranchReportAmountGroup.PAID_SALE
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA -> LocalBranchReportAmountGroup.PHYSICAL_MOVEMENT
        else -> unavailable()
    }
    else -> null
}

private fun reportClassification(value: LocalTenderViewV1): LocalBranchReportTenderClassification =
    LocalBranchReportTenderClassification(reportClassificationLabel(value.kind), value.tenderId, value.policyRevision)

private fun reportClassificationLabel(kind: String): LocalText = when (kind) {
    "CASH" -> text("Cash", "نقدي")
    "CARD" -> text("Card", "بطاقة")
    "EXTERNAL_WALLET" -> text("External wallet", "محفظة خارجية")
    "OTHER" -> text("Other configured external tender", "وسيلة دفع خارجية أخرى مُعدّة")
    else -> unavailable()
}

private data class ReportTenderBucketKey(val kind: String, val tenderId: DomainId?)

/** Exact classification buckets of every qualified original payment in this sale only.
 * Positive-payment conservation/overflow checks ran before mapping. Never infer absent zeros,
 * combine pages, revise the original close or merge different configured tender identities.
 */
private fun reportTenderBuckets(payments: List<LocalBranchReportRetailTenderPaymentV3>,
    reconciled: Boolean): List<LocalBranchReportTenderBucket> {
    val grouped = payments.groupBy { payment ->
        val tender = if (reconciled) payment.reconciledClassification else payment.originalClassification
        ReportTenderBucketKey(tender.kind, tender.tenderId)
    }
    val keys = grouped.keys.sortedWith(compareBy<ReportTenderBucketKey> { it.kind }.thenBy { it.tenderId?.value ?: "" })
    return keys.map { key ->
        val amount = grouped.getValue(key).fold(Money(Currency.EGP, 0)) { total, payment -> total + payment.amount }
        LocalBranchReportTenderBucket(reportClassificationLabel(key.kind), key.tenderId, amount)
    }
}

private fun reportMetricLabel(section: LocalBranchReportSectionV1, metric: LocalBranchReportMetricV1): LocalText = when (metric) {
    LocalBranchReportMetricV1.OPENING_FLOAT -> text("Opening float · custody, not revenue", "عهدة افتتاحية · ليست إيرادًا")
    LocalBranchReportMetricV1.CASH_RECEIVED -> text("Actual cash received", "النقدية المستلمة فعليًا")
    LocalBranchReportMetricV1.CHANGE_GIVEN -> text("Change given", "الباقي المصروف")
    LocalBranchReportMetricV1.CASH_REFUNDS -> text("Applied cash refunds", "المبالغ النقدية المردودة المطبقة")
    LocalBranchReportMetricV1.PAY_INS -> text("Non-sale pay-ins", "إيداعات غير بيعية")
    LocalBranchReportMetricV1.PAY_OUTS -> text("Non-sale pay-outs", "مدفوعات غير بيعية")
    LocalBranchReportMetricV1.SAFE_DROPS -> text("Safe drops · custody transfers", "تسليمات الخزنة · تحويلات عهدة")
    LocalBranchReportMetricV1.EXPECTED_CASH -> text("Expected cash in custody", "النقدية المتوقعة في العهدة")
    LocalBranchReportMetricV1.RESERVED_CASH_OUT -> text("Cash reserved for unresolved outflow", "نقدية محجوزة لصرف غير محسوم")
    LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS -> text("Applied external collections", "التحصيلات الخارجية المطبقة")
    LocalBranchReportMetricV1.EXTERNAL_REFUNDS -> text("Applied external refunds", "المبالغ الخارجية المردودة المطبقة")
    LocalBranchReportMetricV1.CLOSING_EXPECTED -> if (section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS || section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
        text("Original expected cash at counted close · unchanged", "النقدية المتوقعة عند الإغلاق المعدود الأصلي · لم تتغير")
        else text("Recorded closing expected cash", "النقدية المتوقعة المسجلة عند الإغلاق")
    LocalBranchReportMetricV1.ACTUAL_COUNT -> if (section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS || section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
        text("Original actual closing count · unchanged", "العد الفعلي عند الإغلاق الأصلي · لم يتغير")
        else text("Recorded actual closing count", "العد الفعلي المسجل عند الإغلاق")
    LocalBranchReportMetricV1.VARIANCE -> if (section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS || section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
        text("Original submitted closing variance · unchanged", "فرق الإغلاق المقدم الأصلي · لم يتغير")
        else text("Recorded closing variance", "فرق الإغلاق المسجل")
    LocalBranchReportMetricV1.RETAINED_FLOAT -> text("Recorded retained float", "العهدة المتبقية المسجلة")
    LocalBranchReportMetricV1.ACTUAL_SAFE_DELIVERY -> text("Recorded actual safe delivery", "التسليم الفعلي المسجل للخزنة")
    LocalBranchReportMetricV1.LOCKED_PAYABLE -> if (section == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)
        text("Original locked quoted total · not the refund amount", "إجمالي السعر الأصلي المثبت · ليس مبلغ الرد")
        else text("Locked amount payable", "المبلغ المستحق المثبت")
    LocalBranchReportMetricV1.ACTUAL_COLLECTIONS -> text("Actual applied collections", "التحصيلات الفعلية المطبقة")
    LocalBranchReportMetricV1.REMAINING_COLLECTION -> text("Collection still due", "التحصيل المتبقي المستحق")
    LocalBranchReportMetricV1.CASH_COLLECTIONS -> text("Applied cash collections", "التحصيلات النقدية المطبقة")
    LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL_BEFORE_DISCOUNTS -> text("Document payable before discounts", "المستحق بالمستند قبل الخصومات")
    LocalBranchReportMetricV1.ADVERTISED_DISCOUNT -> text("Advertised discount · explanatory only", "الخصم المعلن · للتوضيح فقط")
    LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL -> if (section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS)
        text("Immutable paid-sale document amount · unchanged", "قيمة مستند البيع المدفوع الثابتة · لم تتغير")
        else text("Posted paid-document amount", "قيمة مستند البيع المدفوع المرحلة")
    LocalBranchReportMetricV1.ITEM_NET -> text("Recorded item net allocation", "تخصيص صافي الأصناف المسجل")
    LocalBranchReportMetricV1.ITEM_TAX -> text("Recorded item tax allocation", "تخصيص ضريبة الأصناف المسجل")
    LocalBranchReportMetricV1.CHARGE_NET -> text("Recorded charge net allocation", "تخصيص صافي الرسوم المسجل")
    LocalBranchReportMetricV1.CHARGE_TAX -> text("Recorded charge tax allocation", "تخصيص ضريبة الرسوم المسجل")
    LocalBranchReportMetricV1.ACCEPTED_RETURN_AMOUNT -> text("Original accepted return amount", "قيمة المرتجع الأصلية المقبولة")
    LocalBranchReportMetricV1.ACTUAL_REFUNDS -> text("Actual applied refunds", "المبالغ المردودة الفعلية المطبقة")
    LocalBranchReportMetricV1.REFUND_DUE -> text("Refund liability still due", "التزام رد المبلغ المتبقي")
    LocalBranchReportMetricV1.TRANSFER_AMOUNT -> text("Actual custody transfer · not sales or expense", "تحويل العهدة الفعلي · ليس مبيعات أو مصروفًا")
    LocalBranchReportMetricV1.CANCELLED_COLLECTION_REFUND_LIABILITY ->
        text("Original actually collected tender liability", "التزام وسيلة الدفع المحصلة فعليًا في الأصل")
    LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA ->
        text("Previous classification delta · linked deduction", "فرق التصنيف السابق · خصم مرتبط")
    LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA ->
        text("Corrected classification delta · linked addition", "فرق التصنيف المصحح · إضافة مرتبطة")
    LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA ->
        text("Cash classification delta · this linked row only", "فرق التصنيف النقدي · لهذا الصف المرتبط فقط")
    LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA ->
        text("Physical cash delta · no movement", "فرق النقدية الفعلي · دون حركة")
}

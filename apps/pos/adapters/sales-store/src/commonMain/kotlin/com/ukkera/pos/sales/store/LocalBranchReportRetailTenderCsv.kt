package com.ukkera.pos.sales.store

import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Explicit selected-sale CSV. Payment membership remains traceable; classification rows
 * are documentary alternative views, never additive money movements or whole-period totals.
 * No provider reference, terminal, payer, contact, reason or raw evidence is serialized. */
internal fun encodeRetailTenderBreakdownCsv(page: LocalBranchReportResultV1.Page,
    branchRevision: Revision, observedAt: UtcInstant): ByteArray {
    require(page.section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS &&
        page.profile == LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3 &&
        page.delivery == LocalBranchReportDeliveryV1.EXPORT && page.after == null && page.nextAfter == null &&
        page.rows.size == 1 && page.rows.single().id == page.selectedSaleId)
    val row = page.rows.single()
    require(validRetailTenderBreakdownReportRow(row, observedAt))
    val detail = checkNotNull(row.retailTenderBreakdown)
    val header = listOf("profile", "section", "branch_revision", "observed_at_utc", "selected_sale_id",
        "document_id", "document_revision", "related_order_id", "cash_session_id", "original_close_operation_id",
        "original_collector_id", "row_kind", "metric", "payment_attempt_id", "tender_kind", "tender_id",
        "tender_policy_revision", "currency", "minor_units", "latest_correction_id", "correction_count",
        "latest_correction_recorded_at_utc")
    val out = StringBuilder()
    fun record(fields: List<String>) {
        require(fields.size == header.size)
        out.append(fields.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append("\r\n")
    }
    record(header)
    val source = listOf("selected-retail-paid-sale-tenders/v3", page.section.name,
        branchRevision.value.toString(), observedAt.canonicalString(), checkNotNull(page.selectedSaleId).value,
        row.id.value, row.revision.value.toString(), checkNotNull(row.relatedDocumentId).value,
        checkNotNull(row.cashSessionId).value, detail.originalCloseOperationId.value, detail.originalCollectorId.value)
    for (amount in row.amounts) {
        record(source + listOf("UNCHANGED_ORIGINAL_DOCUMENT", amount.metric.name, "", "", "", "",
            amount.amount.currency.name, amount.amount.minor.toString(), "", "", ""))
    }
    // A qualified zero-payable source has two empty bases, not absent report data.
    // These membership markers carry neither a fabricated tender nor money amount.
    if (detail.payments.isEmpty()) {
        for (kind in listOf("ORIGINAL_RECORDED", "RECONCILED_HISTORICAL")) {
            record(source + listOf(kind, "EMPTY_PAYMENT_MEMBERSHIP", "", "", "", "",
                "", "", "", "", ""))
        }
    }
    for (payment in detail.payments) {
        for ((kind, tender) in listOf("ORIGINAL_RECORDED" to payment.originalClassification,
            "RECONCILED_HISTORICAL" to payment.reconciledClassification)) {
            record(source + listOf(kind, "PAYMENT_CLASSIFICATION", payment.paymentAttemptId.value,
                tender.kind, tender.tenderId?.value.orEmpty(), tender.policyRevision?.value?.toString().orEmpty(),
                payment.amount.currency.name, payment.amount.minor.toString(), payment.latestCorrectionId?.value.orEmpty(),
                payment.correctionCount.toString(), payment.latestCorrectionRecordedAt?.canonicalString().orEmpty()))
        }
    }
    val bytes = out.toString().encodeToByteArray()
    require(bytes.size <= LocalBranchReportSectionCsvV2.MAX_BYTES) { "report.csv.capacity" }
    return bytes
}

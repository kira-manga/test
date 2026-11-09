package com.ukkera.pos.sales.store

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Pure formatting, not export authorization or a file writer. The caller must obtain a fresh
 * EXPORT response using the current interactive capability immediately before publishing this
 * bounded page to an owner-selected file. Never format a cached VIEW page as an authorized
 * export, append pages from different source/authority/revision cuts, or label a page a total.
 * This format contains only typed identifiers/enums/scalars, not raw spreadsheet formulas,
 * private reasons/customer contacts/tokens or unfiltered domain/entity JSON.
 * Existing sections retain their exact v1 profile/header, including normalized retail.
 * FOOD has a distinct profile and explicit storage/finalization/original-paid-link columns.
 * Corrections have an explicit profile with linked, safe classification metadata only.
 */
public object LocalBranchReportCsvV1 {
    public fun encodePage(page: LocalBranchReportResultV1.Page, branchRevision: Revision, observedAt: UtcInstant): ByteArray {
        require(page.profile == null && page.section !in setOf(LocalBranchReportSectionV1.PAYMENT_CORRECTIONS, LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS))
        val food = isFoodReportPage(page)
        val profile = if (food) "current-local-food-documents-page/v1" else "current-local-documents-page/v1"
        return encodeBranchReportPage(page, branchRevision, observedAt, profile)
    }
}

/** Explicit profile dispatch; all original headers/scalars/CSV bytes retain their v1 meaning. */
public object LocalBranchReportCsv {
    public fun encodePage(page: LocalBranchReportResultV1.Page, branchRevision: Revision, observedAt: UtcInstant): ByteArray =
        when (page.profile) {
            null -> LocalBranchReportCsvV1.encodePage(page, branchRevision, observedAt)
            LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2 -> {
                require(page.section == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)
                encodeBranchReportPage(page, branchRevision, observedAt, "current-local-food-refund-payout-page/v2")
            }
            LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2 -> {
                require(page.section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS)
                encodeBranchReportPage(page, branchRevision, observedAt, "current-local-payment-classifications-page/v2")
            }
            LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3 ->
                encodeRetailTenderBreakdownCsv(page, branchRevision, observedAt)
        }
}

private fun isFoodReportPage(page: LocalBranchReportResultV1.Page): Boolean = page.section in setOf(
    LocalBranchReportSectionV1.FOOD_COLLECTIONS, LocalBranchReportSectionV1.FOOD_PAID_SALES,
    LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)

private fun encodeBranchReportPage(page: LocalBranchReportResultV1.Page, branchRevision: Revision,
    observedAt: UtcInstant, profile: String): ByteArray {
    require(page.delivery == LocalBranchReportDeliveryV1.EXPORT && page.rows.size <= 5)
    val food = isFoodReportPage(page)
    val correction = page.section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS
    val out = StringBuilder()
    fun record(fields: List<String>) {
        out.append(fields.joinToString(",") { value -> "\"" + value.replace("\"", "\"\"") + "\"" }).append("\r\n")
    }
    val header = listOf("profile", "section", "branch_revision", "observed_at_utc", "page_after", "next_after",
        "document_id", "document_revision", "related_document_id", "cash_session_id", "status", "cash_session_business_date",
        "pending_count", "metric", "currency", "minor_units") + when {
        food -> listOf("food_storage_generation", "food_paid_finalized_at_utc", "original_paid_document_id")
        correction -> listOf("payment_attempt_id", "original_close_operation_id", "original_collector_id",
            "correction_actor_id", "previous_tender_kind", "previous_tender_id", "previous_tender_policy_revision",
            "corrected_tender_kind", "corrected_tender_id", "corrected_tender_policy_revision", "correction_recorded_at_utc")
        else -> emptyList()
    }
    record(header)
    for (row in page.rows) {
        require(row.retailTenderBreakdown == null)
        require(correction || row.paymentCorrection == null && row.amounts.none { it.metric in setOf(
            LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA,
            LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA) })
        val metadata = if (correction) {
            require(LocalBranchReportReadCodec.validPaymentCorrectionRow(row, observedAt))
            val detail = checkNotNull(row.paymentCorrection)
            listOf(detail.paymentAttemptId.value, detail.originalCloseOperationId.value, detail.originalCollectorId.value,
                detail.correctionActorId.value, detail.previousClassification.kind,
                detail.previousClassification.tenderId?.value.orEmpty(), detail.previousClassification.policyRevision?.value?.toString().orEmpty(),
                detail.correctedClassification.kind, detail.correctedClassification.tenderId?.value.orEmpty(),
                detail.correctedClassification.policyRevision?.value?.toString().orEmpty(), detail.recordedAt.canonicalString())
        } else if (food) {
            val storage = checkNotNull(row.storageRevision)
            require(storage.value > 0 && storage.value - 1 == row.revision.value && row.businessDate == null)
            require(if (page.section == LocalBranchReportSectionV1.FOOD_PAID_SALES)
                row.revision.value == 0L && row.finalizedAt != null else row.finalizedAt == null)
            require(page.section == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS || row.originalPaidDocumentId == null)
            listOf(storage.value.toString(), row.finalizedAt?.canonicalString().orEmpty(), row.originalPaidDocumentId?.value.orEmpty())
        } else {
            // A legacy header must not silently discard newly supplied FOOD metadata.
            require(row.storageRevision == null && row.finalizedAt == null && row.originalPaidDocumentId == null)
            emptyList()
        }
        for (field in row.amounts) {
            record(listOf(profile, page.section.name, branchRevision.value.toString(), observedAt.canonicalString(),
                page.after?.value.orEmpty(), page.nextAfter?.value.orEmpty(), row.id.value, row.revision.value.toString(),
                row.relatedDocumentId?.value.orEmpty(), row.cashSessionId?.value.orEmpty(), row.status,
                row.businessDate?.canonicalString().orEmpty(), row.pendingCount.toString(), field.metric.name,
                field.amount.currency.name, field.amount.minor.toString()) + metadata)
        }
    }
    // Empty pages still carry their exact source/page metadata, never a fabricated zero row.
    if (page.rows.isEmpty()) record(listOf(profile, page.section.name, branchRevision.value.toString(),
        observedAt.canonicalString(), page.after?.value.orEmpty(), page.nextAfter?.value.orEmpty()) + List(header.size - 6) { "" })
    val bytes = out.toString().encodeToByteArray()
    require(bytes.size <= 131_072)
    return bytes
}

/**
 * Bounded complete-current-section container. Every data record retains its original page
 * profile, cursor and actual observation time. The final complete-current-section/v2 metadata
 * record is emitted only after a root-to-terminal cursor chain at one branch revision and a
 * separately supplied confirming EXPORT observation. It is not a period total or an atomic
 * snapshot. Current authority and genuine source identity remain the master's responsibility.
 * No prefix is returned on a source mismatch, page bound or output overflow.
 */
public class LocalBranchReportSectionCsvV2(private val query: LocalBranchReportQueryV1) {
    public class CapacityExceeded : IllegalArgumentException()

    public companion object {
        public const val MAX_BYTES: Int = 65_536
        public const val MAX_PAGES: Int = 128
    }

    private val content = ByteArray(MAX_BYTES)
    private var size = 0
    private var headerBytes = 0
    private var headerFields = 0
    private var after: DomainId? = null
    private var revision: Revision? = null
    private var terminal = false
    private var closed = false
    public var pageCount: Int = 0
        private set

    init { require(query.after == null && query.delivery == LocalBranchReportDeliveryV1.EXPORT &&
        query.section != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS) }

    public fun append(page: LocalBranchReportResultV1.Page, branchRevision: Revision, observedAt: UtcInstant) {
        check(!closed && !terminal)
        if (pageCount >= MAX_PAGES) throw CapacityExceeded()
        require(page.section == query.section && page.profile == query.profile &&
            page.delivery == LocalBranchReportDeliveryV1.EXPORT && page.after == after && page.rows.size <= query.limit)
        val ids = page.rows.map { it.id.value }
        require(ids == ids.distinct().sorted() && after?.let { cursor -> ids.any { it <= cursor.value } } != true &&
            (page.nextAfter == null || page.nextAfter == page.rows.lastOrNull()?.id))
        // A document without any exported metric must not silently disappear from a section.
        require(page.rows.all { it.amounts.isNotEmpty() })
        require(revision == null || revision == branchRevision)
        val bytes = LocalBranchReportCsv.encodePage(page, branchRevision, observedAt)
        try {
            val headerEnd = (0 until bytes.size - 1).firstOrNull { bytes[it] == 13.toByte() && bytes[it + 1] == 10.toByte() }
                ?.plus(2) ?: error("report.csv.header")
            if (pageCount == 0) {
                headerBytes = headerEnd
                headerFields = 1 + (0 until headerEnd - 2).count { bytes[it] == ','.code.toByte() }
            } else require(headerEnd == headerBytes && (0 until headerBytes).all { bytes[it] == content[it] })
            appendBytes(bytes, if (pageCount == 0) 0 else headerBytes)
        } finally { bytes.fill(0) }
        revision = branchRevision
        after = page.nextAfter
        terminal = page.nextAfter == null
        pageCount++
    }

    /** The supplied time is the actual confirming source read, not an invented common page time. */
    public fun finish(branchRevision: Revision, observedAt: UtcInstant): ByteArray {
        check(!closed && terminal && pageCount > 0 && revision == branchRevision)
        val fields = listOf("complete-current-section/v2", query.section.name, branchRevision.value.toString(),
            observedAt.canonicalString(), "", "") + List(headerFields - 6) { "" }
        val marker = (fields.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\r\n").encodeToByteArray()
        return try {
            appendBytes(marker, 0)
            content.copyOf(size)
        } finally {
            marker.fill(0)
            clear()
        }
    }

    /** Called on every cancellation/failure as well as normal completion. */
    public fun clear() { content.fill(0); closed = true }

    private fun appendBytes(bytes: ByteArray, offset: Int) {
        val count = bytes.size - offset
        if (count > MAX_BYTES - size) throw CapacityExceeded()
        bytes.copyInto(content, size, offset, bytes.size)
        size += count
    }
}

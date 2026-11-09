package com.ukkera.pos.app

import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Current local document families only; none is a period, company or fiscal aggregate. */
enum class LocalBranchReportSection {
    CASH_SESSIONS, COLLECTIONS, PAID_SALES, ACCEPTED_RETURNS, SAFE_TRANSFERS,
    FOOD_COLLECTIONS, FOOD_PAID_SALES, FOOD_REFUND_OBLIGATIONS,
    PAYMENT_CORRECTIONS,
    RETAIL_TENDER_BREAKDOWNS,
}

/**
 * A bounded VIEW read. A continuation must retain the actual previous envelope revision.
 * Only an explicit restart drops that revision. These fields never supply authorization,
 * a posting-date index, a snapshot lease or permission to export a displayed page.
 */
data class LocalBranchReportQuery(
    val section: LocalBranchReportSection,
    val after: DomainId? = null,
    val expectedBranchRevision: Revision? = null,
    /** Explicit document selection, never a document cursor or an inferred source census. */
    val selectedSaleId: DomainId? = null,
) {
    init {
        require(after == null || expectedBranchRevision != null)
        require(if (section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS) after == null else selectedSaleId == null)
    }
}

/** Exact posted money from the report service, with a closed adapter-provided bilingual label. */
data class LocalBranchReportAmount(val label: LocalText, val amount: Money,
    val group: LocalBranchReportAmountGroup? = null)

/** Presentation grouping only; original counted-close figures are never adjusted or totaled. */
enum class LocalBranchReportAmountGroup { ORIGINAL_CLOSE, LINKED_CORRECTION, PAID_SALE, PHYSICAL_MOVEMENT }

/** Safe historical classification summary; no terminal, provider reference or private evidence. */
data class LocalBranchReportTenderClassification(
    val label: LocalText,
    val tenderId: DomainId? = null,
    val policyRevision: Revision? = null,
)

/** Documentary links only, not authority to fetch or act on the original payment or close. */
data class LocalBranchReportPaymentCorrection(
    val paymentAttemptId: DomainId,
    val originalCloseOperationId: OperationId,
    val originalCollectorId: DomainId,
    val correctionActorId: DomainId,
    val previousClassification: LocalBranchReportTenderClassification,
    val correctedClassification: LocalBranchReportTenderClassification,
    val recordedAt: UtcInstant,
)

/** Classification of this sale's payments only, grouped by kind and configured tender ID.
 * A bucket is not a new collection, custody balance, cash-session total or period aggregate.
 */
data class LocalBranchReportTenderBucket(
    val label: LocalText,
    val tenderId: DomainId?,
    val amount: Money,
)

/** Exact safe membership of one immutable payment and its qualified correction head. */
data class LocalBranchReportRetailTenderPayment(
    val paymentAttemptId: DomainId,
    val amount: Money,
    val originalClassification: LocalBranchReportTenderClassification,
    val reconciledClassification: LocalBranchReportTenderClassification,
    val latestCorrectionId: DomainId?,
    val correctionCount: Long,
    val latestCorrectionRecordedAt: UtcInstant?,
)

/** Complete classification for one selected paid sale, not every payment in its cash session. */
data class LocalBranchReportRetailTenderBreakdown(
    val originalCloseOperationId: OperationId,
    val originalCollectorId: DomainId,
    val payments: List<LocalBranchReportRetailTenderPayment>,
    val originalBuckets: List<LocalBranchReportTenderBucket>,
    val reconciledBuckets: List<LocalBranchReportTenderBucket>,
)

/** A read-only document, not an actionable record or a fabricated payment/closing event. */
data class LocalBranchReportDocument(
    val id: DomainId,
    val revision: Revision,
    val status: LocalText,
    val relatedDocumentId: DomainId?,
    val cashSessionId: DomainId?,
    /** Original cash-session date when supplied; never a sale/return posting date. */
    val cashSessionBusinessDate: BusinessDate?,
    val pendingCount: Int,
    val amounts: List<LocalBranchReportAmount>,
    /** Actual FOOD storage generation, not the immutable/domain revision above. */
    val storageRevision: Revision? = null,
    /** Genuine FOOD paid-document finalization, never a retail posting-date substitute. */
    val finalizedAt: UtcInstant? = null,
    /** Original paid document only when the FOOD cancellation has one. */
    val originalPaidDocumentId: DomainId? = null,
    /** Linked correction summary only under the explicit payment-correction projection. */
    val paymentCorrection: LocalBranchReportPaymentCorrection? = null,
    /** Complete immutable payment membership only for the explicitly selected retail sale. */
    val retailTenderBreakdown: LocalBranchReportRetailTenderBreakdown? = null,
) {
    init {
        require(pendingCount >= 0 && amounts.size <= 24)
        require(amounts.map { it.label }.distinct().size == amounts.size)
    }
}

/**
 * One authorized page and its OUTER read envelope, not accumulated branch totals.
 * The controller additionally owns the exact interactive session/connection lifetime; IDs
 * here are metadata, not a serializable capability or authority for another display/export.
 */
data class LocalBranchReportPage(
    val section: LocalBranchReportSection,
    val after: DomainId?,
    val nextAfter: DomainId?,
    val branchRevision: Revision,
    val observedAt: UtcInstant,
    val actorId: DomainId,
    val companyId: DomainId,
    val branchId: DomainId,
    val documents: List<LocalBranchReportDocument>,
    val selectedSaleId: DomainId? = null,
) {
    init {
        require(documents.size <= 5)
        val ids = documents.map { it.id.value }
        require(ids == ids.distinct().sorted())
        require(after == null || ids.all { it > after.value })
        require(nextAfter == null || nextAfter == documents.lastOrNull()?.id)
        require(if (section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS)
            selectedSaleId != null && after == null && nextAfter == null && documents.size == 1 && documents.single().id == selectedSaleId
            else selectedSaleId == null)
    }
}

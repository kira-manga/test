package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.Serializable

/** Null keeps the original page action; complete coverage always starts at the section's root. */
@Serializable
enum class LocalBranchReportExportCoverage { COMPLETE_CURRENT_SECTION_V2 }

/** Freshly reads this query for export. The currently displayed VIEW cut is not reused/replaced. */
data class LocalBranchReportExportSelection(val section: LocalBranchReportSection, val after: DomainId?,
    val coverage: LocalBranchReportExportCoverage? = null,
    val selectedSaleId: DomainId? = null) {
    init {
        require(coverage == null || after == null)
        require(if (section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS)
            selectedSaleId != null && after == null && coverage == null else selectedSaleId == null)
    }
}

enum class LocalBranchReportExportProgress { CHOOSING_DESTINATION, BEGINNING_AUDIT, READING_CURRENT_PAGE, WRITING_LOCAL_FILE, REPORTING_OUTCOME, RECOVERING }

/** Native file fact and master audit acknowledgement are intentionally separate. */
data class LocalBranchReportExportRecord(
    val receipt: LocalBranchReportFileReceipt,
    val presence: LocalBranchReportFilePresence = LocalBranchReportFilePresence.NOT_INSPECTED,
    val masterStage: LocalBranchReportAuditStage? = null,
    val serviceProblem: LocalServiceProblem? = null,
) {
    val verifiedLocally: Boolean get() = receipt.phase == LocalBranchReportFilePhase.PUBLISHED && presence == LocalBranchReportFilePresence.VERIFIED_PRESENT
    val auditAcknowledged: Boolean get() = masterStage == LocalBranchReportAuditStage.CLIENT_ATTESTED_PUBLISHED &&
        (receipt.recovery?.acknowledgement ?: receipt.finish?.acknowledgement)?.stage == LocalBranchReportAuditStage.CLIENT_ATTESTED_PUBLISHED
}

sealed interface LocalBranchReportExportResult {
    data object ChooserCancelled : LocalBranchReportExportResult
    data class Outcome(val record: LocalBranchReportExportRecord) : LocalBranchReportExportResult
    /** Opaque original acknowledgements only; no file receipt, path, bytes or publication grant. */
    data class AuditRecovered(val exportId: DomainId, val originals: List<LocalBranchReportRecoveredAudit>,
        val serviceProblem: LocalServiceProblem) : LocalBranchReportExportResult {
        init { require(originals.size in 1..3 && originals.map { it.slot }.distinct().size == originals.size) }
    }
    data class Unavailable(val serviceProblem: LocalServiceProblem? = null, val fileProblem: LocalBranchReportFileProblem? = null,
        val exportId: DomainId? = null) : LocalBranchReportExportResult
}

data class LocalBranchReportRecoveredAudit(val slot: LocalBranchReportAuditSlot, val operationId: OperationId,
    val acknowledgement: LocalBranchReportAuditAcknowledgement)

sealed interface LocalBranchReportExportHistory {
    data class Available(val records: List<LocalBranchReportExportRecord>, val nextAfter: DomainId?) : LocalBranchReportExportHistory
    data class Unavailable(val serviceProblem: LocalServiceProblem? = null, val fileProblem: LocalBranchReportFileProblem? = null) : LocalBranchReportExportHistory
}

internal data class LocalBranchReportExportPresentation(
    val progress: LocalBranchReportExportProgress? = null,
    val result: LocalBranchReportExportResult? = null,
    val history: LocalBranchReportExportHistory? = null,
)

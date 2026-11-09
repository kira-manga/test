package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Pure original audit producer shared by the live writer and native history comparison.
 * Its inputs are data, not employee authority, a publication permit or a file certificate.
 * Authorization and native BEFORE-source custody remain at their respective boundaries. */
internal class LocalBranchReportExportProduction(
    private val scope: BranchScope,
    private val actor: DomainId,
    private val operation: OperationId,
    digest: PayloadDigest,
    private val request: LocalBranchReportExportRequestV1,
    private val current: LocalBranchReportExportCutV1?,
) {
    val requirement: AuthorizationRequirement = exportAuditRequirement(scope, digest, operation,
        current?.value?.view?.revision ?: Revision(0))
    val affectedResources: Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor))

    fun propose(recordedAt: UtcInstant): ProposedOutcome {
        val next: LocalBranchReportExportStateV1
        val claims: List<ClaimChange>
        when (request) {
            is LocalBranchReportExportRequestV1.Begin -> {
                if (current != null) return rejected(LocalBranchReportExportRejectionV1.EXPORT_ID_EXISTS)
                next = beginReportExport(scope, actor, operation, recordedAt, request)
                claims = listOf(ClaimChange.Acquire(LocalBranchReportExportProfileV1.owner(request.exportId), actor))
            }
            is LocalBranchReportExportRequestV1.Finish -> {
                if (current == null) return rejected(LocalBranchReportExportRejectionV1.NOT_FOUND)
                reportExportFinishRejection(current.value, actor, request)?.let { return rejected(it) }
                next = finishReportExport(current.value, actor, operation, recordedAt, request)
                claims = emptyList()
            }
        }
        return ProposedOutcome.Apply(LocalBranchReportExportCodecV1.result(LocalBranchReportExportResultV1.Recorded(next.view)),
            listOf(AggregateChange(LocalBranchReportExportProfileV1.key(request.exportId), current?.stored?.revision,
                LocalBranchReportExportProfileV1.SCHEMA, reportExportStateBytes(next))), claims)
    }
}

internal fun exportAuditRequirement(scope: BranchScope, digest: PayloadDigest,
    operation: OperationId?, revision: Revision): AuthorizationRequirement =
    AuthorizationRequirement(Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT), scope,
        ActionBinding(scope.branchId, revision, digest, operation, "report.export.audit"))

private fun rejected(reason: LocalBranchReportExportRejectionV1): ProposedOutcome = ProposedOutcome.Reject(
    "report.export.${reason.name.lowercase()}",
    LocalBranchReportExportCodecV1.result(LocalBranchReportExportResultV1.Rejected(reason)))

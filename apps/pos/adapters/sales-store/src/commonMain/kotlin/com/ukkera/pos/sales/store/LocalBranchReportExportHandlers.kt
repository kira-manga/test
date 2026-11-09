package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Real durable audit mutation; no data export, file IO, publication permission or money effects. */
public class LocalBranchReportExportHandler : MasterCommandHandler {
    override val key: CommandKey = LocalBranchReportExportCodecV1.COMMAND
    override val writableKinds: Set<String> = setOf(LocalBranchReportExportProfileV1.KIND)
    override val claimNamespaces: Set<String> = setOf(LocalBranchReportExportProfileV1.OWNER_NAMESPACE)
    override fun normalize(input: StoredBytes): StoredBytes =
        LocalBranchReportExportCodecV1.encode(LocalBranchReportExportCodecV1.decodeRequest(input))

    // This opts in the exact audit key, not file publication or another business command.
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.REPORTS

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalBranchReportExportCodecV1.decodeRequest(normalized)
        val current = readReportExport(view, request.exportId)
        val production = LocalBranchReportExportProduction(context.scope, context.originalActorId,
            context.operationId, context.semanticDigest, request, current)
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = production.requirement
            override val affectedResources: Set<RecoveryResourceKey> = production.affectedResources
            override val allowsActionApproval: Boolean = false

            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
                    authorization.isBoundTo(requirement, context.originalActorId))
                return production.propose(recordedAt)
            }
        }
    }
}

/** Own-actor, currently EXPORT-authorized metadata only. NotFound never certifies file absence. */
public class LocalBranchReportExportReadHandler : MasterReadHandler {
    override val key: ReadKey = LocalBranchReportExportCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = localSalesEncode(
        localSalesDecode(input, LocalBranchReportExportQueryV1.serializer(), 1_024),
        LocalBranchReportExportQueryV1.serializer(), 1_024)

    // Metadata still requires the current EXPORT grant and is projected only for its owner.
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.REPORTS

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalBranchReportExportQueryV1.serializer(), 1_024)
        val record = readReportExport(view, query.exportId)?.value?.view
        val requirement = exportAuditRequirement(context.scope, context.semanticDigest, null, Revision(0))
        val visible = record?.takeIf { it.ownerId == context.actorId }
        // Query-bound current recovery authority: a visible record selects its authentic
        // last original operation. NotFound names only this branch, never an invented
        // operation or another owner's export. The read remains bound to its query digest.
        val recovery = AuthorizationRequirement(
            Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM), context.scope,
            ActionBinding(visible?.let { DomainId(it.lastOperationId.value) } ?: context.scope.branchId,
                Revision(0), context.semanticDigest, null, "report.export.metadata"),
            owner = OwnerConstraint.OwnerPrivate(context.actorId))
        val required = listOf(requirement, recovery)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = required
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == required.size && authorizations.zip(required).all { (proof, expected) ->
                    proof.principalKind == PrincipalKind.LOCAL_EMPLOYEE && proof.isBoundTo(expected, context.actorId)
                })
                return LocalBranchReportExportCodecV1.result(visible?.let { LocalBranchReportExportResultV1.Recorded(it) }
                    ?: LocalBranchReportExportResultV1.NotFound)
            }
        }
    }
}

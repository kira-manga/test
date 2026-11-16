package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Audited disclosure and separately reauthorized SQL accounting, never an ordinary
 * financial Execute, Start instruction, copied original actor or replacement cash owner. */
public class LocalSupplierCashSupervisorHandler(private val stock: LocalStockRepository,
    private val originalSources: LocalFinancialOriginalSourceServices? = null) : MasterCommandHandler,
    LocalMasterRpcRetainedOriginalAccessProvider {
    private val applications = LocalSupplierCashHandler(stock)
    override val key: CommandKey get() = LocalSupplierCashSupervisorCodec.COMMAND
    override val writableKinds: Set<String> get() = applications.writableKinds - LocalSupplierCashProfile.gatedKinds - ReservedOperations.KIND
    override val claimNamespaces: Set<String> get() = applications.claimNamespaces - LocalSupplierCashProfile.gatedClaims
    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierCashSupervisorCodec.normalize(input)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS && normalize(normalized) == normalized
    override suspend fun rpcRetainedOriginalRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        original: RetainedOriginalV2, view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        if (!supportsRpcWorkflow(profile, normalized) || context.key != key) return null
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        val sources = originalSources ?: LocalFinancialOriginalSourceServices(stock, null,
            UnavailableHistoricalSalesTenderPolicySourceV1, LocalCatalogFinancialOriginalProof(stock))
        return sources.read(view, retained) { loan ->
            supplierCashRpcOriginalRequirements(normalized, original, loan, context, stock)
        }
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == view.scope &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = LocalSupplierCashSupervisorCodec.decode(normalized)) {
            is LocalSupplierCashSupervisorRequest.Apply -> applications.prepareSupervisedApplication(request, normalized, view, context)
            is LocalSupplierCashSupervisorRequest.Review -> {
                val reader = PurchasingReader(view)
                val original = supplierCashFact(view, request.originalRecordingOperationId, LocalSupplierCashPhase.ORIGINAL, reader)
                    ?: return supplierUnavailable(context, DomainId(request.originalRecordingOperationId.value))
                val resolution = supplierCashFact(view, original.value.target.resolutionOperationId, LocalSupplierCashPhase.RESOLUTION, reader)
                val result = supplierCashSupervisorReview(original, resolution)
                val requirement = supplierCashSupervisorReviewRequirement(context.scope, context.operationId, context.semanticDigest, original.slot.value)
                object : PreparedMutation() {
                    override val requirement: AuthorizationRequirement = requirement
                    override val allowsActionApproval: Boolean = false
                    override val affectedResources: Set<RecoveryResourceKey> = original.slot.value.domain.financialResources.toSet() +
                        LocalSupplierCashJournal.authorityResources(original.slot.value.requirement()) +
                        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)
                    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = supplierCashBarriers
                    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
                            authorization.isBoundTo(requirement, context.originalActorId))
                        return ProposedOutcome.Reject(SUPPLIER_SUPERVISOR_REVIEWED, LocalSupplierCashSupervisorCodec.review(result))
                    }
                }
            }
        }
    }
}

internal const val SUPPLIER_SUPERVISOR_REVIEWED: String = "purchasing.supplier_cash_supervisor_reviewed"
internal fun supplierCashSupervisorReviewRequirement(scope: BranchScope, operationId: OperationId,
    digest: PayloadDigest, slot: LocalSupplierCashSlot): AuthorizationRequirement = salesAuthorizationRequirement(
    SecurityAction.SUPPLIER_CASH_RESOLVE, scope, DomainId(slot.target.recordingOperationId.value), Revision(0),
    operationId, digest, "purchasing.supplier_cash_supervisor_review", slot.originalActorId, slot.domain.amount)

internal fun supplierCashSupervisorReview(original: LocalSupplierCashStoredFact,
    resolution: LocalSupplierCashStoredFact?): LocalSupplierCashSupervisorReview {
    check(original.value.phase == LocalSupplierCashPhase.ORIGINAL && original.value.recordingActorId == original.value.originalActorId)
    resolution?.let { check(it.value.phase == LocalSupplierCashPhase.RESOLUTION && it.value.target == original.value.target &&
        it.value.originalActorId == original.value.originalActorId && original.outcome() is LocalSupplierCashOutcome.Unknown) }
    fun summary(fact: LocalSupplierCashStoredFact): LocalSupplierCashSupervisorFact = LocalSupplierCashSupervisorFact(
        fact.value.target, fact.value.originalActorId, fact.value.recordingActorId, fact.value.bodyDigest,
        if (fact.value.phase == LocalSupplierCashPhase.RESOLUTION) original.value.bodyDigest else null,
        fact.slot.value.domain.amount, fact.application.applicationOperationId,
        (fact.application.result as? LocalSupplierCashResult.Applied)?.action)
    return LocalSupplierCashSupervisorReview(summary(original), original.outcome(), resolution?.let(::summary), resolution?.outcome())
}

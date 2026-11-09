package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant

/** Actual kernel-backed case workflow. No money, stock, delivery, policy publication or fake capacity writer. */
public class LocalReturnCaseHandler(
    private val source: TransactionSalesReturnCaseSourceV1 = UnavailableSalesReturnCaseSourceV1,
) : MasterCommandHandler {
    override public val key: CommandKey get() = LocalReturnCaseProfileV1.COMMAND
    override public val writableKinds: Set<String> = setOf(LocalReturnCaseProfileV1.CASE, LocalReturnCaseProfileV1.EVENT,
        LocalReturnCaseProfileV1.WORK, LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, LocalReturnCaseProfileV1.CLAIM_BEFORE,
        LocalReturnCaseProfileV1.WORK_BEFORE, LocalSalesStoreProfile.RETURN_CLAIM_KIND, LocalSalesStoreProfile.ENTITLEMENT_KIND,
        LocalSalesStoreProfile.RETURN_KIND, LocalSalesStoreProfile.WORK_KIND,
        LocalReturnEntitlementProvenanceProfile.LOCATOR, LocalReturnEntitlementProvenanceProfile.CONTRIBUTION)
    override public val claimNamespaces: Set<String> = setOf(LocalReturnCaseProfileV1.NAMESPACE, LocalSalesStoreProfile.UNIQUE_NAMESPACE,
        LocalReturnEntitlementProvenanceProfile.NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalReturnCaseCommandCodecV1.normalize(input)

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalReturnCaseCommandCodecV1.decode(normalized)) {
            is LocalReturnCaseRequestV1.Record -> record(request, view, context)
            is LocalReturnCaseRequestV1.AppendReceiptEvidence -> append(request, view, context)
            is LocalReturnCaseRequestV1.Qualify -> qualify(request, view, context)
            is LocalReturnCaseRequestV1.Accept -> accept(request, view, context)
        }
    }

    private suspend fun record(request: LocalReturnCaseRequestV1.Record, view: CommandReadView, context: CommandContext): PreparedMutation {
        val dependencies = LocalReturnCaseDependencies()
        val work = findReturnCaseWork(view, context.originalActorId, dependencies)
        val duplicate = view.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, request.caseId)) != null ||
            view.claim(LocalReturnCaseProfileV1.source("case", request.caseId)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, request.caseId)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, request.caseId)) != null ||
            view.claim(LocalSalesStoreProfile.unique("return_intake", request.caseId)) != null ||
            view.claim(LocalSalesStoreProfile.unique("return_claim", request.caseId)) != null ||
            view.claim(LocalReturnCaseProfileV1.source("claim", request.caseId)) != null
        requireNewCaseEvent(view, context)
        return ReturnCasePrepared(context, returnCaseRecordProduction(returnCaseIdentity(context), request, work,
            dependencies.snapshot(), duplicate))
    }

    private suspend fun append(request: LocalReturnCaseRequestV1.AppendReceiptEvidence, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readReturnCase(view, request.caseId)
        requireNewCaseEvent(view, context)
        return ReturnCasePrepared(context, returnCaseAppendProduction(returnCaseIdentity(context), request, cut))
    }

    private suspend fun qualify(request: LocalReturnCaseRequestV1.Qualify, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readReturnCase(view, request.caseId)
        val binding = cut.binding?.value?.binding
        val original = binding?.let { findEntitlement(view, it.saleId) }
        val prepared = if (binding == null || original == null || request.expectedEntitlementRevision == null) null else source.prepare(view,
            SalesReturnCaseQualificationRequestV1(view.scope, request.caseId, returnCaseFactsDigest(cut.row.value.facts),
                checkNotNull(cut.binding).stored.digest, cut.row.value.facts, binding, original.sale.value,
                cut.row.value.recordedAt, cut.row.value.ownerId, cut.row.value.originalOperationId,
                request.recordOriginalCommercialTiming, request.ordinaryInspection))
        check(prepared == null || prepared.scope == view.scope) { "sales.return.case_source_scope" }
        val references = prepared?.sourceReferences?.toList().orEmpty()
        requireReturnCaseSources(view, references)
        // Capture and its original promise are validated while the transaction can still read.
        val captures = references.filter { it.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND }
        val selectedPromises = linkedMapOf<LocalReturnCaseSourceReferenceV1, LocalSalesReturnPortionPromiseV1?>()
        if (original != null && binding != null) for (reference in captures) {
            val placeholder = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.PENDING, "PREPARED", "PENDING",
                salePolicyCapture = reference)
            val promise = requireReturnCasePolicyForSale(view, original, binding, placeholder)
            selectedPromises[reference] = promise
        }
        val claimExists = view.claim(LocalSalesStoreProfile.unique("return_claim", request.caseId)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, request.caseId)) != null ||
            view.claim(LocalReturnCaseProfileV1.source("claim", request.caseId)) != null
        requireNewCaseEvent(view, context)
        if (prepared == null) return ReturnCasePrepared(context,
            returnCaseUnavailableQualificationProduction(returnCaseIdentity(context), request, cut, original))
        val receiptEvents = if (request.recordOriginalCommercialTiming || request.ordinaryInspection != null ||
                cut.row.value.facts.claimedReason != null)
            readReturnCommercialReceiptEvents(view.scope, references) { view.aggregate(it) } else emptyMap()
        val production = returnCaseSourceQualificationProduction(returnCaseIdentity(context), request, cut, original,
            prepared, references, selectedPromises, receiptEvents, claimExists)
        return ReturnCasePrepared(context, production)
    }

    private suspend fun accept(request: LocalReturnCaseRequestV1.Accept, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readReturnCase(view, request.caseId)
        val binding = cut.binding?.value?.binding
        if (cut.row.value.stage != LocalReturnCaseStageV1.BOUND_UNACCEPTED || binding == null) {
            return ReturnCasePrepared(context, returnCaseUnavailableAcceptanceProduction(returnCaseIdentity(context), request, cut))
        }
        val original = readEntitlement(view, binding.saleId)
        val qualified = readReturnCaseClaim(view, original, request.caseId)
        val custodian = LocalSalesStorage.custodian(view, context.originalActorId)
        val cash = custodian.value.activeSessionId?.let { LocalSalesStorage.cash(view, it) }
        val work = cash?.work ?: LocalSalesStorage.work(view, context.originalActorId)
        val duplicate = view.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId)) != null ||
            view.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.caseId)) != null ||
            view.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, request.returnId)) != null
        requireNewCaseEvent(view, context)
        val entitlementAppend = prepareReturnEntitlementAppend(view, original, returnCaseIdentity(context), request)
        return ReturnCasePrepared(context, returnCaseAcceptanceProduction(returnCaseIdentity(context), request, cut,
            original, qualified, custodian, cash, work, duplicate, entitlementAppend))
    }
}

private fun returnCaseIdentity(context: CommandContext): LocalReturnCaseProductionIdentity =
    LocalReturnCaseProductionIdentity(context.scope, context.originalActorId, context.operationId, context.semanticDigest)

private class ReturnCasePrepared private constructor(private val context: CommandContext,
    override val requirement: AuthorizationRequirement,
    override val affectedResources: Set<RecoveryResourceKey>,
    private val proposal: (UtcInstant) -> ProposedOutcome) : PreparedMutation() {
    constructor(context: CommandContext, production: LocalReturnCaseIntakeProduction) : this(context,
        production.requirement, production.affectedResources, production::propose)
    constructor(context: CommandContext, production: LocalReturnCaseSourceQualificationProduction) : this(context,
        production.requirement, production.affectedResources, { now -> production.evaluate(now).outcome })
    // This authority producer records the direct actor, not a separate approver's full provenance.
    override val allowsActionApproval: Boolean = false
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.REFUND_ENTITLEMENT, RecoveryResourceKind.ORDER_CUSTODY)
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId)) { "sales.authorization.invalid" }
        return try { proposal(recordedAt) }
        catch (failure: SalesRuleViolation) { caseRefused(failure.rule.name) }
        catch (_: LocalSalesCapacityExceeded) { caseRefused("CAPACITY_REQUIRED") }
        catch (_: LocalSalesWireCapacityExceeded) { caseRefused("CAPACITY_REQUIRED") }
        // Source, transaction, IO and arbitrary arithmetic errors remain uncertainty, never a legal denial.
    }
}

private suspend fun requireNewCaseEvent(view: CommandReadView, context: CommandContext) {
    val id = DomainId(context.operationId.value)
    requireLocalSalesSource(view.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, id)) == null &&
        view.claim(LocalReturnCaseProfileV1.source("event", id)) == null &&
        listOf(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, LocalReturnCaseProfileV1.CLAIM_BEFORE, LocalReturnCaseProfileV1.WORK_BEFORE)
            .all { view.aggregate(LocalReturnCaseProfileV1.key(it, id)) == null })
}

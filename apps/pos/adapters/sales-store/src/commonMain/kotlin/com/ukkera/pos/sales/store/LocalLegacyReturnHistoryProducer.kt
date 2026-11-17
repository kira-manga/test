package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.ReturnAccepted
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Exact positive ordinary legacy legal intake. Qualification, acceptance and physical
 * refund observations require their own complete sources; no receipt, policy, entitlement,
 * historical employee command or recording capability is fabricated here. */
public class LocalLegacyReturnHistoryProducer(
    private val policySource: FactualSalesReturnPolicySourceV1? = null,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalSalesStoreProfile.RETURN_COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            !original.committed || original.code != "committed" || original.nativeOriginalProvenance != null) return false
        val request = LocalReturnCommandCodec.decode(command.normalized)
        if (request !is LocalReturnRequestV1.RecordClaim && request !is LocalReturnRequestV1.BindClaim &&
            request !is LocalReturnRequestV1.Accept) return false
        requireOriginal(NativeBusinessOriginalProgram(command))
        return true
    }

    public suspend fun requireOriginal(command: BusinessOriginalProgram) {
        val original = command.evidence
        check(command.command in commands && original.committed && original.code == "committed" &&
            original.jobs.isEmpty() && original.nativeOriginalProvenance == null &&
            LocalReturnCommandCodec.normalize(command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        when (val request = LocalReturnCommandCodec.decode(command.normalized)) {
            is LocalReturnRequestV1.RecordClaim -> requireIntake(command, request)
            is LocalReturnRequestV1.BindClaim -> requireBinding(command, request)
            is LocalReturnRequestV1.Accept -> requireAcceptance(command, request)
            else -> error("Original legacy physical sources required")
        }
    }

    private suspend fun requireIntake(command: BusinessOriginalProgram, request: LocalReturnRequestV1.RecordClaim) {
        val work = LocalSalesStorage.work(command, command.actorId)
        check(!legacyReturnIntakeExists(command, request.claimId))
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.RETURN_CLAIM, command.scope,
            request.claimId, Revision(0), command.operationId, command.semanticDigest, "return.claim.intake", command.actorId))
        command.requireLegacyEffects(legacyReturnIntakeOutcome(command.scope, command.actorId, command.operationId,
            request, work, command.recordedAt) as? ProposedOutcome.Apply
            ?: error("Original legacy return intake capacity result required"), setOf(entitlementResource(request.claimId), orderResource(work.value.id),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)))
    }

    private suspend fun requireBinding(command: BusinessOriginalProgram, request: LocalReturnRequestV1.BindClaim) {
        val intake = readIntake(command, request.claimId)
        val work = LocalSalesStorage.work(command, intake.value.originalActorId)
        val facts = intake.value.facts
        val original = facts.saleId?.let { findReturnCaseHistoryEntitlement(command, it, LocalReturnCaseHistorySources(command)) }
        val source = original?.let {
            checkNotNull(policySource) { "Original return policy composition required" }.before(command, it.sale.value.id)
        }
        check(source == null || source.scope == command.scope)
        val duplicate = command.claim(LocalSalesStoreProfile.unique("return_claim", request.claimId)) != null ||
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, request.claimId)) != null
        val resources = setOf(entitlementResource(request.claimId), orderResource(work.value.id)) +
            listOfNotNull(facts.saleId?.let(::entitlementResource)) + priceSourcesResources(original?.sale?.value?.priceSourceReference) +
            entitlementReturnPolicyResources(original) + entitlementSourceResources(original) + source?.recoveryResources.orEmpty()
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.RETURN_CLAIM, command.scope,
            request.claimId, Revision(0), command.operationId, command.semanticDigest, "return.claim.bind", command.actorId))
        check(command.actorId == intake.value.originalActorId && intake.value.revision == request.expectedIntakeRevision &&
            intake.value.boundSaleId == null && !duplicate)
        check(original != null && facts.portionId != null && facts.quantity != null && source != null)
        val actorEvidence = facts.evidence.domain(intake.value.originalActorId)
        val evidence = checkNotNull(source.evidence(facts, original.sale.value, actorEvidence, command.recordedAt, intake.value.recordedAt))
        check(evidence.claimedAt == facts.claimedAt.value && evidence.reason == facts.reason && evidence.evidence == actorEvidence)
        val claim = Returns.originalClaimValue(original.entitlement.value, request.expectedEntitlementRevision,
            request.claimId, facts.portionId, facts.quantity, evidence, command.actorId)
        command.requireLegacyEffects(legacyReturnBoundOutcome(command.scope, request, intake, work, original, claim) as? ProposedOutcome.Apply
            ?: error("Original legacy return binding capacity result required"),
            resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
    }

    private suspend fun requireAcceptance(command: BusinessOriginalProgram, request: LocalReturnRequestV1.Accept) {
        // A CASE claim cannot be reinterpreted as the old intake/acceptance contract.
        check(command.claim(LocalReturnCaseProfileV1.source("claim", request.claimId)) == null &&
            command.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, request.claimId)) == null &&
            command.claim(LocalReturnCaseProfileV1.source("case", request.claimId)) == null)
        val original = checkNotNull(findReturnCaseHistoryEntitlement(command, request.saleId, LocalReturnCaseHistorySources(command)))
        val cut = readLegacyClaimSource(command, original, request.saleId, request.claimId)
        val work = LocalSalesStorage.work(command, command.actorId)
        val acceptsWork = acceptsNewWork(command, command.actorId)
        val entitlement = original.entitlement.value
        val claim = cut.claim.value
        val cursor = entitlement.cursors.singleOrNull { it.portionId == claim.portionId }
        val validQuantity = cursor != null && claim.quantity.unitId == cursor.originalQuantity.unitId &&
            claim.quantity.scale == cursor.originalQuantity.scale && claim.quantity.scaled > 0 && claim.quantity <= cursor.remainingQuantity
        val amount = if (validQuantity) checkNotNull(cursor).consume(claim.quantity).payable else entitlement.sale.quote.payable
        val duplicate = command.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId)) != null ||
            command.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.claimId)) != null ||
            command.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId)) != null ||
            command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, request.returnId)) != null
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.RETURN_ACCEPT, command.scope, request.saleId,
            request.expectedEntitlementRevision, command.operationId, command.semanticDigest, "return.accept", command.actorId, amount))
        check(cut.intake != null && original.normalizedSource?.locator == null && !duplicate && acceptsWork && validQuantity)
        val after = Returns.originalAcceptanceValue(entitlement, request.expectedEntitlementRevision, claim, request.expectedClaimRevision,
            request.returnId, ReturnDisposition.valueOf(request.disposition.name), request.inspectionEvidence.domain(command.actorId), command.actorId)
        SalesSnapshotLinks.requireAcceptedReturn(after.acceptedReturn, after.entitlement, after.claim)
        val resources = setOf(entitlementResource(request.saleId), entitlementResource(request.claimId),
            entitlementResource(request.returnId), orderResource(work.value.id)) + priceSourcesResources(entitlement.sale.priceSourceReference) +
            cut.caseRecoveryResources + entitlementReturnPolicyResources(original)
        command.requireLegacyEffects(legacyReturnAcceptedOutcome(request, cut, work, after) as? ProposedOutcome.Apply
            ?: error("Original legacy acceptance capacity result required"),
            resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
    }
}

/** Same ordered duplicate census used by live preparation and original BEFORE replay. */
internal suspend fun legacyReturnIntakeExists(view: BusinessFactRead, claimId: DomainId): Boolean =
    view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, claimId)) != null ||
        view.claim(LocalSalesStoreProfile.unique("return_intake", claimId)) != null ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, claimId)) != null ||
        view.claim(LocalSalesStoreProfile.unique("return_claim", claimId)) != null ||
        view.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, claimId)) != null ||
        view.claim(LocalReturnCaseProfileV1.source("case", claimId)) != null ||
        view.claim(LocalReturnCaseProfileV1.source("claim", claimId)) != null

/** Immutable asserted intake and its pending-work index, never refund qualification. */
internal fun legacyReturnIntakeOutcome(scope: BranchScope, actorId: DomainId, operationId: OperationId,
    request: LocalReturnRequestV1.RecordClaim, work: LocalSalesRow<LocalWorkState>, now: UtcInstant): ProposedOutcome {
    val value = LocalReturnIntake(LocalSalesScope.from(scope), request.claimId, Revision(0), actorId, operationId, now, request.facts)
    val (afterWork, workWrite) = changedWork(work, pendingClaimIds = work.value.pendingClaimIds + value.claimId)
    return salesApplied(LocalSalesAction.RETURN_CLAIM_PENDING, value.claimId,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_INTAKE_KIND, value.claimId, value.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, afterWork.id, afterWork.revision)),
        listOf(rootWrite(LocalSalesStoreProfile.RETURN_INTAKE_KIND, value.claimId, value, LocalReturnIntake.serializer(), null), workWrite),
        listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_intake", value.claimId), value.originalActorId)))
}

/** Shared literal successor; live code still validates its actual authorized domain transition. */
internal fun legacyReturnBoundOutcome(scope: BranchScope, request: LocalReturnRequestV1.BindClaim,
    intake: LocalSalesRow<LocalReturnIntake>, work: LocalSalesRow<LocalWorkState>, original: LocalEntitlementCut,
    claim: com.ukkera.pos.sales.ReturnClaim): ProposedOutcome {
    check(scope == original.sale.value.scope && claim.id == request.claimId)
    val afterIntake = intake.value.copy(revision = intake.value.revision.next(), boundSaleId = original.sale.value.id)
    val (afterWork, workWrite) = changedWork(work, pendingClaimIds = work.value.pendingClaimIds - request.claimId)
    return salesApplied(LocalSalesAction.RETURN_CLAIMED, claim.id,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_INTAKE_KIND, claim.id, afterIntake.revision),
            LocalSalesRevision(LocalSalesStoreProfile.RETURN_CLAIM_KIND, claim.id, claim.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, afterWork.id, afterWork.revision)),
        listOf(rootWrite(LocalSalesStoreProfile.RETURN_INTAKE_KIND, claim.id, afterIntake, LocalReturnIntake.serializer(), intake.stored),
            claimWrite(claim, null), workWrite),
        listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_claim", request.claimId), claim.saleId)))
}

/** Same complete literal allocation successor as live acceptance; no stock or payout. */
internal fun legacyReturnAcceptedOutcome(request: LocalReturnRequestV1.Accept, cut: LocalClaimCut,
    work: LocalSalesRow<LocalWorkState>, after: ReturnAccepted): ProposedOutcome {
    val workChange = if (after.acceptedReturn.due.minor == 0L) null else changedWork(work, returnIds = work.value.returnIds + request.returnId)
    return salesApplied(LocalSalesAction.RETURN_ACCEPTED, request.returnId,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.ENTITLEMENT_KIND, request.saleId, after.entitlement.revision),
            LocalSalesRevision(LocalSalesStoreProfile.RETURN_CLAIM_KIND, after.claim.id, after.claim.revision),
            LocalSalesRevision(LocalSalesStoreProfile.RETURN_KIND, request.returnId, after.acceptedReturn.revision)) +
            listOfNotNull(workChange?.first?.let { LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, it.id, it.revision) }),
        listOf(entitlementWrite(after.entitlement, cut.original.entitlement.stored), claimWrite(after.claim, cut.claim.stored),
            acceptedReturnWrite(after.acceptedReturn, after.entitlement, after.claim, null)) + listOfNotNull(workChange?.second),
        listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("accepted_return", request.returnId), request.saleId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("claim_acceptance", request.claimId), request.returnId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_claim_source", request.returnId), request.claimId)),
        after.acceptedReturn.originalPayable)
}

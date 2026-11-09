package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.security.SecurityAction

/** The refusal-only route has no invented monetary basis or refund acceptance authority. */
internal fun returnCaseUnavailableAcceptanceProduction(identity: LocalReturnCaseProductionIdentity,
    request: LocalReturnCaseRequestV1.Accept, cut: LocalReturnCaseCut): LocalReturnCaseIntakeProduction {
    val reason = "return.case.accept.unavailable"
    return LocalReturnCaseIntakeProduction(returnCaseAuthorizationRequirement(identity, SecurityAction.RETURN_CLAIM,
        request.caseId, request.expectedCaseRevision, reason), reason,
        returnCaseAffectedResources(returnCaseResources(cut), identity)) {
        if (cut.row.value.ownerId != identity.actorId) caseRefused("OWNER_REQUIRED")
        else caseRefused("QUALIFIED_DECISION_REQUIRED")
    }
}

/** Shared original obligation production. Every input is a verified borrowed live/native
 * BEFORE cut. It computes exact quantity/tender allocation, never a grant, stock or payout. */
internal fun returnCaseAcceptanceProduction(identity: LocalReturnCaseProductionIdentity,
    request: LocalReturnCaseRequestV1.Accept, cut: LocalReturnCaseCut, original: LocalEntitlementCut,
    qualified: LocalClaimCut, custodian: LocalSalesRow<LocalCustodianState>, cash: LocalCashCut?,
    work: LocalSalesRow<LocalWorkState>, duplicate: Boolean,
    entitlementAppend: LocalReturnEntitlementAppend?): LocalReturnCaseIntakeProduction {
    val binding = returnCaseRequired(cut.binding?.value?.binding)
    val claim = qualified.claim.value
    val cursor = original.entitlement.value.cursors.singleOrNull { it.portionId == claim.portionId }
    val validQuantity = cursor != null && claim.quantity.unitId == cursor.originalQuantity.unitId &&
        claim.quantity.scale == cursor.originalQuantity.scale && claim.quantity.scaled > 0 && claim.quantity <= cursor.remainingQuantity
    val amount = if (validQuantity) checkNotNull(cursor).consume(claim.quantity).payable else original.sale.value.quote.payable
    val acceptsWork = cash == null || cash.session.value.status == CashSessionStatus.OPEN
    val workResources = setOf(orderResource(work.value.id), cashResource(custodian.value.id)) + cash?.let(::cashResources).orEmpty()
    val resources = qualified.caseRecoveryResources + entitlementResource(binding.saleId) + entitlementResource(request.returnId) +
        workResources + priceSourcesResources(original.sale.value.priceSourceReference) +
        entitlementReturnPolicyResources(original) + entitlementAppend?.recoveryResources.orEmpty()
    val reason = "return.case.accept"
    return LocalReturnCaseIntakeProduction(returnCaseAuthorizationRequirement(identity, SecurityAction.RETURN_ACCEPT,
        binding.saleId, request.expectedEntitlementRevision, reason, amount), reason,
        returnCaseAffectedResources(resources, identity)) { now ->
        val previous = cut.row.value
        caseGuard(previous, request.expectedCaseRevision, identity.actorId, LocalReturnCaseStageV1.BOUND_UNACCEPTED)?.let {
            return@LocalReturnCaseIntakeProduction it
        }
        if (previous.decisionOperationId != request.decisionOperationId) return@LocalReturnCaseIntakeProduction caseRefused("DECISION_CHANGED")
        if (!returnCaseOrdinaryBoundDecision(cut)) return@LocalReturnCaseIntakeProduction caseRefused("QUALIFIED_DECISION_REQUIRED")
        if (duplicate) return@LocalReturnCaseIntakeProduction caseRefused("DUPLICATE_SOURCE")
        if (!acceptsWork) return@LocalReturnCaseIntakeProduction caseRefused("CLOSING_BLOCKED")
        if (!validQuantity) return@LocalReturnCaseIntakeProduction caseRefused("RETURN_ENTITLEMENT_EXHAUSTED")
        val accepted = Returns.originalAcceptanceValue(original.entitlement.value, request.expectedEntitlementRevision, claim,
            request.expectedClaimRevision, request.returnId, ReturnDisposition.valueOf(request.disposition.name),
            request.inspectionEvidence.domain(identity.actorId), identity.actorId)
        SalesSnapshotLinks.requireAcceptedReturn(accepted.acceptedReturn, accepted.entitlement, accepted.claim)
        val workChange = if (accepted.acceptedReturn.due.minor == 0L) null else changedWork(work, returnIds = work.value.returnIds + request.returnId)
        val after = advanceCase(previous, identity.operationId).copy(stage = LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING,
            acceptedReturnId = request.returnId)
        val entitlementChange = entitlementWrite(accepted.entitlement, original.entitlement.stored)
        val proposal = returnCaseApplied(identity.operationId, cut.row, cut.work, after, LocalReturnCaseActionV1.ACCEPTED, now,
            expectedEntitlementRevision = request.expectedEntitlementRevision,
            entitlementRevision = accepted.entitlement.revision, claimRevision = accepted.claim.revision,
            amount = accepted.acceptedReturn.originalPayable, beforeEntitlementDigest = original.entitlement.stored.digest,
            beforeClaimDigest = qualified.claim.stored.digest, beforeCashWorkDigest = workChange?.let { work.stored.digest },
            extraChanges = listOf(entitlementChange,
                claimWrite(accepted.claim, qualified.claim.stored), acceptedReturnWrite(accepted.acceptedReturn, accepted.entitlement, accepted.claim, null),
                returnCaseWitness(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, identity.operationId, original.entitlement.stored),
                returnCaseWitness(LocalReturnCaseProfileV1.CLAIM_BEFORE, identity.operationId, qualified.claim.stored)) +
                listOfNotNull(workChange?.second, workChange?.let {
                    returnCaseWitness(LocalReturnCaseProfileV1.WORK_BEFORE, identity.operationId, work.stored)
                }) + entitlementAppend?.changes(entitlementChange).orEmpty(),
            extraClaims = listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("accepted_return", request.returnId), binding.saleId),
                ClaimChange.Acquire(LocalSalesStoreProfile.unique("claim_acceptance", request.caseId), request.returnId),
                ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_claim_source", request.returnId), request.caseId)) +
                entitlementAppend?.claims().orEmpty())
        entitlementAppend?.bounded(proposal) ?: proposal
    }
}

/** A later Accept retains the exact timely original decision; its processing clock does not expire it. */
internal fun returnCaseOrdinaryBoundDecision(cut: LocalReturnCaseCut): Boolean {
    val decision = cut.decision?.value ?: return false
    return cut.row.value.facts.claimedReason == LocalReturnClaimReasonV1.CHANGE_OF_MIND &&
        cut.row.value.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND &&
        decision.action == LocalReturnCaseActionV1.BOUND &&
        decision.assessment?.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE &&
        decision.assessment?.ordinaryTiming?.status == LocalReturnCommercialWindowStatusV1.WITHIN &&
        decision.ordinaryInspection?.condition == LocalReturnInspectionConditionV1.UNCHANGED &&
        decision.assessedBindingDigest == cut.binding?.stored?.digest
}

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.types.Revision

/** Closed stale-CASE DATA selector. Native CASE/initial-entitlement originals,
 * prepared signed sources and original authorization must still join separately. */
internal fun returnCaseHistorySourceStaleQualification(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify, before: LocalReturnCaseStateV1,
    entitlementRevision: Revision): Boolean =
    returnCaseHistoryInitialEntitlementRejection(original, request, before, entitlementRevision) &&
        request.expectedCaseRevision.value < before.revision.value &&
        request.expectedEntitlementRevision == entitlementRevision

/** The exact live null-expectation guard, not an absent-source or qualification fallback.
 * Genuine CASE/initial-entitlement BEFORE and original authorization remain mandatory;
 * the producer must independently derive and close the complete original resources. */
internal fun returnCaseHistorySourceOmittedEntitlementQualification(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify, before: LocalReturnCaseStateV1,
    entitlementRevision: Revision): Boolean =
    returnCaseHistoryInitialEntitlementRejection(original, request, before, entitlementRevision) &&
        request.expectedCaseRevision == before.revision && request.expectedEntitlementRevision == null

/** Inert DATA selection cannot establish native custody, source presence or absent effects. */
private fun returnCaseHistoryInitialEntitlementRejection(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify, before: LocalReturnCaseStateV1,
    entitlementRevision: Revision): Boolean =
    !original.committed && original.nativeOriginalProvenance == null &&
        original.command == LocalReturnCaseProfileV1.COMMAND &&
        original.normalized == LocalReturnCaseCommandCodecV1.encode(request) &&
        original.code == "sales.return_case.refused" &&
        original.result == LocalReturnCaseCommandCodecV1.result(LocalReturnCaseResultV1.Refused("REVISION_CONFLICT")) &&
        original.aggregateWrites.isEmpty() && original.claimChanges.isEmpty() && original.jobs.isEmpty() &&
        before.scope.value() == original.scope && before.ownerId == original.originalActorId &&
        before.caseId == request.caseId && before.stage == LocalReturnCaseStageV1.PENDING &&
        before.acceptedReturnId == null && before.bindingOperationId != null && entitlementRevision == Revision(0)

/** Additive typed-input refusal selection. Genuine native CASE/current-entitlement/source reads
 * and the complete shared refusal/core comparison follow; these bytes never prove authority,
 * source absence, guard priority or an accepting result. Legacy refusal selectors stay intact. */
internal fun returnCaseHistoryOrdinaryQualificationRejection(original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Qualify, before: LocalReturnCaseStateV1): Boolean =
    !original.committed && original.nativeOriginalProvenance == null &&
        original.command == LocalReturnCaseProfileV1.COMMAND &&
        original.normalized == LocalReturnCaseCommandCodecV1.encode(request) &&
        original.code == "sales.return_case.refused" && original.aggregateWrites.isEmpty() &&
        original.claimChanges.isEmpty() && original.jobs.isEmpty() &&
        before.scope.value() == original.scope && before.caseId == request.caseId &&
        (before.facts.claimedReason != null || request.ordinaryInspection != null)

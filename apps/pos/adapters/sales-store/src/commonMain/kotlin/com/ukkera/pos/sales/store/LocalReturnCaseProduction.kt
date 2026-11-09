package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.ReturnClaimEvidence
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Inert original coordinates. These describe a request; they confer no employee authority. */
internal data class LocalReturnCaseProductionIdentity(
    val scope: BranchScope, val actorId: DomainId, val operationId: OperationId, val semanticDigest: PayloadDigest,
)

/** The live intake/evidence and unavailable-source pending producer, reused by native history
 * to compare the exact no-source-equivalent PENDING profile, not historical prepare provenance.
 * Reads and authorization stay at their respective real boundaries. No SalesCommand,
 * historical session, CommandReadView or legal/delivery source is manufactured here. */
internal class LocalReturnCaseIntakeProduction(
    val requirement: AuthorizationRequirement,
    val reason: String,
    val affectedResources: Set<RecoveryResourceKey>,
    private val proposal: (UtcInstant) -> ProposedOutcome,
) {
    fun propose(recordedAt: UtcInstant): ProposedOutcome = returnCaseRuleOutcome { proposal(recordedAt) }
}

/** Inert effect production, shared by the protected live and original historical boundaries. */
internal sealed interface LocalReturnCaseSourceQualificationEvaluation {
    class Completed(val outcome: ProposedOutcome) : LocalReturnCaseSourceQualificationEvaluation
}

internal class LocalReturnCaseSourceQualificationProduction(
    val requirement: AuthorizationRequirement,
    val reason: String,
    val affectedResources: Set<RecoveryResourceKey>,
    val sourceReferences: List<LocalReturnCaseSourceReferenceV1>,
    val sourceRecoveryResources: List<RecoveryResourceKey>,
    private val evaluation: (UtcInstant) -> LocalReturnCaseSourceQualificationEvaluation.Completed,
) {
    fun evaluate(recordedAt: UtcInstant): LocalReturnCaseSourceQualificationEvaluation.Completed = try { evaluation(recordedAt) }
        catch (failure: SalesRuleViolation) { LocalReturnCaseSourceQualificationEvaluation.Completed(caseRefused(failure.rule.name)) }
        catch (_: LocalSalesCapacityExceeded) { LocalReturnCaseSourceQualificationEvaluation.Completed(caseRefused("CAPACITY_REQUIRED")) }
        catch (_: LocalSalesWireCapacityExceeded) { LocalReturnCaseSourceQualificationEvaluation.Completed(caseRefused("CAPACITY_REQUIRED")) }
}

private inline fun returnCaseRuleOutcome(produce: () -> ProposedOutcome): ProposedOutcome = try { produce() }
    catch (failure: SalesRuleViolation) { caseRefused(failure.rule.name) }
    catch (_: LocalSalesCapacityExceeded) { caseRefused("CAPACITY_REQUIRED") }
    catch (_: LocalSalesWireCapacityExceeded) { caseRefused("CAPACITY_REQUIRED") }

internal fun returnCaseRecordProduction(identity: LocalReturnCaseProductionIdentity,
    request: LocalReturnCaseRequestV1.Record, work: LocalSalesRow<LocalReturnCaseWorkV1>?,
    dependencies: Set<RecoveryResourceKey>, duplicate: Boolean): LocalReturnCaseIntakeProduction =
    returnCaseIntakeProduction(identity, request.caseId, Revision(0), "return.case.record",
        returnCaseResources(identity.actorId, request.caseId) + dependencies) { now ->
        if (duplicate) return@returnCaseIntakeProduction caseRefused("DUPLICATE_SOURCE")
        val binding = initialReturnCaseBinding(request.facts)
        val after = LocalReturnCaseStateV1(LocalSalesScope.from(identity.scope), request.caseId, identity.actorId,
            Revision(0), identity.operationId, identity.operationId, now, request.facts, LocalReturnCaseStageV1.PENDING,
            listOf(identity.operationId), binding?.let { identity.operationId })
        returnCaseApplied(identity.operationId, null, work, after, LocalReturnCaseActionV1.RECORDED, now, binding = binding,
            extraClaims = listOf(ClaimChange.Acquire(LocalReturnCaseProfileV1.source("case", request.caseId), identity.actorId)))
    }

internal fun returnCaseAppendProduction(identity: LocalReturnCaseProductionIdentity,
    request: LocalReturnCaseRequestV1.AppendReceiptEvidence, cut: LocalReturnCaseCut): LocalReturnCaseIntakeProduction =
    returnCaseIntakeProduction(identity, request.caseId, request.expectedCaseRevision, "return.case.receipt_evidence",
        returnCaseResources(cut) + entitlementResource(request.binding.saleId)) { now ->
        val previous = cut.row.value
        caseGuard(previous, request.expectedCaseRevision, identity.actorId, LocalReturnCaseStageV1.PENDING)?.let {
            return@returnCaseIntakeProduction it
        }
        if (!bindingMatches(previous.facts, request.binding)) return@returnCaseIntakeProduction caseRefused("ASSERTED_FACT_CONFLICT")
        val old = cut.binding?.value?.binding
        if (old != null && (old.saleId != request.binding.saleId || old.portionId != request.binding.portionId || old.quantity != request.binding.quantity)) {
            return@returnCaseIntakeProduction caseRefused("IMMUTABLE_RECEIPT_BINDING")
        }
        val after = advanceCase(previous, identity.operationId).copy(bindingOperationId = identity.operationId, decisionOperationId = null)
        returnCaseApplied(identity.operationId, cut.row, cut.work, after, LocalReturnCaseActionV1.EVIDENCE_APPENDED, now, binding = request.binding)
    }

/** The existing live NO-SOURCE branch; native history compares its exact no-source-equivalent
 * PENDING output, not historical prepare provenance. Readers supply the original/current cut
 * or proved absence; this neither supplies legal/delivery evidence nor creates refund rights. */
internal fun returnCaseUnavailableQualificationProduction(identity: LocalReturnCaseProductionIdentity,
    request: LocalReturnCaseRequestV1.Qualify, cut: LocalReturnCaseCut,
    original: LocalEntitlementCut?): LocalReturnCaseIntakeProduction {
    val binding = cut.binding?.value?.binding
    val captureResources = entitlementReturnPolicyResources(original)
    val sourceResources = captureResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value }))
    val resources = returnCaseResources(cut) + listOfNotNull(binding?.saleId?.let(::entitlementResource)) +
        priceSourcesResources(original?.sale?.value?.priceSourceReference) + captureResources + entitlementSourceResources(original)
    // Preserve the absent profile's original pre-authorization source admission.
    if (captureResources.isEmpty()) requireLocalSalesSource(sourceResources.size <= 128)
    return returnCaseIntakeProduction(identity, request.caseId, Revision(0), "return.case.qualify", resources) { now ->
        val previous = cut.row.value
        caseGuard(previous, request.expectedCaseRevision, identity.actorId, LocalReturnCaseStageV1.PENDING)?.let {
            return@returnCaseIntakeProduction it
        }
        if (binding == null) return@returnCaseIntakeProduction caseRefused("RECEIPT_BINDING_REQUIRED")
        returnCaseEntitlementRevisionGuard(request.expectedEntitlementRevision, original?.entitlement?.value?.revision)?.let {
            return@returnCaseIntakeProduction it
        }
        if (sourceResources.size > 128) return@returnCaseIntakeProduction caseRefused("CAPACITY_REQUIRED")
        val assessment = returnCaseUnavailableAssessment(original != null)
        val after = advanceCase(previous, identity.operationId).copy(decisionOperationId = identity.operationId,
            stage = LocalReturnCaseStageV1.PENDING)
        returnCaseApplied(identity.operationId, cut.row, cut.work, after, LocalReturnCaseActionV1.ASSESSMENT_PENDING,
            now, assessment = assessment, assessedBindingDigest = checkNotNull(cut.binding).stored.digest,
            expectedEntitlementRevision = request.expectedEntitlementRevision,
            entitlementRevision = original?.entitlement?.value?.revision,
            beforeEntitlementDigest = original?.entitlement?.stored?.digest, sourceRecoveryResources = sourceResources,
            extraChanges = listOfNotNull(original?.let {
                returnCaseWitness(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, identity.operationId, it.entitlement.stored)
            }), ordinaryInspection = request.ordinaryInspection)
    }
}

/** Exact shared source-backed production from already verified borrowed-cut data.
 * It preserves the original case/entitlement guards, source snapshots, immutable BEFORE
 * witness and event bytes. It neither reads a source nor supplies historical authority. */
internal fun returnCaseSourceQualificationProduction(identity: LocalReturnCaseProductionIdentity,
    request: LocalReturnCaseRequestV1.Qualify, cut: LocalReturnCaseCut, original: LocalEntitlementCut?,
    prepared: PreparedSalesReturnCaseSourceV1, references: List<LocalReturnCaseSourceReferenceV1>,
    selectedPromises: Map<LocalReturnCaseSourceReferenceV1, LocalSalesReturnPortionPromiseV1?>,
    receiptEvents: Map<DomainId, LocalRetailCustomerReceiptEventV1>,
    claimExists: Boolean? = null,
): LocalReturnCaseSourceQualificationProduction {
    check(prepared.scope == identity.scope) { "sales.return.case_source_scope" }
    val binding = cut.binding?.value?.binding
    val sourceReferences = references.toList()
    val promises = selectedPromises.toMap()
    val captures = sourceReferences.filter { it.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND }
    val promiseResources = returnCaseSourceResources(promises.values.mapNotNull { it?.promiseSource })
    val preparedResources = prepared.recoveryResources.toSet()
    val captureResources = entitlementReturnPolicyResources(original)
    val resources = returnCaseResources(cut) + listOfNotNull(binding?.saleId?.let(::entitlementResource)) +
        priceSourcesResources(original?.sale?.value?.priceSourceReference) + returnCaseSourceResources(sourceReferences) +
        promiseResources + preparedResources + captureResources + entitlementSourceResources(original)
    val sourceResources = (returnCaseSourceResources(sourceReferences) + promiseResources + preparedResources + captureResources)
        .sortedWith(compareBy({ it.kind.name }, { it.resourceId.value }))
    // Preserve the absent profile's original pre-authorization source admission.
    if (captureResources.isEmpty()) requireLocalSalesSource(sourceResources.size <= 128)
    val reason = "return.case.qualify"
    return LocalReturnCaseSourceQualificationProduction(returnCaseAuthorizationRequirement(identity, SecurityAction.RETURN_CLAIM,
        request.caseId, Revision(0), reason), reason, returnCaseAffectedResources(resources, identity),
        sourceReferences, sourceResources) { now ->
        val previous = cut.row.value
        caseGuard(previous, request.expectedCaseRevision, identity.actorId, LocalReturnCaseStageV1.PENDING)?.let {
            return@LocalReturnCaseSourceQualificationProduction LocalReturnCaseSourceQualificationEvaluation.Completed(it)
        }
        if (binding == null) {
            return@LocalReturnCaseSourceQualificationProduction LocalReturnCaseSourceQualificationEvaluation.Completed(caseRefused("RECEIPT_BINDING_REQUIRED"))
        }
        returnCaseEntitlementRevisionGuard(request.expectedEntitlementRevision, original?.entitlement?.value?.revision)?.let {
            return@LocalReturnCaseSourceQualificationProduction LocalReturnCaseSourceQualificationEvaluation.Completed(it)
        }
        // Never truncate a complete dependency cut or widen the persisted 128-key bound.
        if (sourceResources.size > 128) {
            return@LocalReturnCaseSourceQualificationProduction LocalReturnCaseSourceQualificationEvaluation.Completed(caseRefused("CAPACITY_REQUIRED"))
        }
        val offered = prepared.assess(now)
        val assessment = localSalesDecode(localSalesEncode(offered, LocalReturnCaseAssessmentV1.serializer(), 16_384),
            LocalReturnCaseAssessmentV1.serializer(), 16_384)
        check(assessment.sources().all { it in sourceReferences } &&
            (assessment.salePolicyCapture == null || assessment.salePolicyCapture in captures)) {
            "sales.return.unobserved_qualification_source"
        }
        assessment.salePolicyCapture?.takeIf { it.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA }?.let { capture ->
            check(promises.containsKey(capture)) { "sales.return.unobserved_policy_capture" }
            val promise = promises[capture]
            check(if (promise == null) assessment.outcome == LocalReturnOrdinaryAssessmentV1.PENDING && assessment.policyId == null
                else assessment.policyId == null || assessment.policyId == promise.policyId && assessment.policyRevision == promise.policyRevision) {
                "sales.return.signed_promise_assessment_mismatch"
            }
        }
        requireReturnCommercialTiming(assessment, request, previous, binding, original?.returnPolicyCapture?.value,
            original?.entitlement?.value?.cursors?.singleOrNull { it.portionId == binding.portionId }?.originalQuantity,
            receiptEvents)
        requireReturnOrdinaryTiming(assessment, request, previous, binding, original?.returnPolicyCapture?.value,
            original?.entitlement?.value?.cursors?.singleOrNull { it.portionId == binding.portionId }?.originalQuantity,
            receiptEvents)
        if (assessment.outcome != LocalReturnOrdinaryAssessmentV1.PENDING) {
            check(original != null && assessment.customerReceivedAt != null &&
                assessment.customerReceivedAt <= previous.recordedAt && previous.recordedAt <= now) {
                "sales.return.qualification_dates"
            }
        }
        val bind = assessment.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE &&
            previous.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND
        val changes = mutableListOf<AggregateChange>()
        val claims = mutableListOf<ClaimChange>()
        if (original != null) changes += returnCaseWitness(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE,
            identity.operationId, original.entitlement.stored)
        if (bind) {
            requireLocalSalesSource(claimExists != null)
            if (claimExists == true) {
                return@LocalReturnCaseSourceQualificationProduction LocalReturnCaseSourceQualificationEvaluation.Completed(caseRefused("DUPLICATE_SOURCE"))
            }
            val sale = returnCaseRequired(original)
            val evidence = returnCaseQualifiedClaimEvidence(previous, assessment, request.ordinaryInspection)
            val claim = Returns.originalClaimValue(sale.entitlement.value, checkNotNull(request.expectedEntitlementRevision),
                previous.caseId, binding.portionId, binding.quantity, evidence, identity.actorId)
            changes += claimWrite(claim, null)
            claims += ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_claim", previous.caseId), binding.saleId)
            claims += ClaimChange.Acquire(LocalReturnCaseProfileV1.source("claim", previous.caseId), previous.caseId)
        }
        val after = advanceCase(previous, identity.operationId).copy(decisionOperationId = identity.operationId,
            stage = if (bind) LocalReturnCaseStageV1.BOUND_UNACCEPTED else LocalReturnCaseStageV1.PENDING)
        LocalReturnCaseSourceQualificationEvaluation.Completed(returnCaseApplied(identity.operationId, cut.row, cut.work, after,
            if (bind) LocalReturnCaseActionV1.BOUND else LocalReturnCaseActionV1.ASSESSMENT_PENDING, now, assessment = assessment,
            assessedBindingDigest = checkNotNull(cut.binding).stored.digest,
            expectedEntitlementRevision = request.expectedEntitlementRevision,
            entitlementRevision = original?.entitlement?.value?.revision,
            claimRevision = if (bind) Revision(0) else null,
            beforeEntitlementDigest = original?.entitlement?.stored?.digest,
            qualificationSources = sourceReferences, sourceRecoveryResources = sourceResources,
            extraChanges = changes, extraClaims = claims, ordinaryInspection = request.ordinaryInspection))
    }
}

/** Legacy claim evidence remains explicit; new ordinary decisions retain Master submission
 * and employee inspection without overwriting the customer's original assertions. */
internal fun returnCaseQualifiedClaimEvidence(state: LocalReturnCaseStateV1, assessment: LocalReturnCaseAssessmentV1,
    inspection: LocalReturnOrdinaryInspectionV1?): ReturnClaimEvidence {
    val ordinary = assessment.ordinaryTiming != null
    val submittedAt = if (ordinary) state.recordedAt else state.facts.asserted.claimedAt
    val evidence = if (ordinary) returnCaseRequired(inspection).evidence else state.facts.asserted.evidence
    return ReturnClaimEvidence(returnCaseRequired(assessment.policyId), returnCaseRequired(assessment.policyRevision),
        returnCaseRequired(assessment.customerReceivedAt).value, submittedAt.value, assessment.remedyDueAt?.value,
        state.facts.asserted.reason, evidence.domain(state.ownerId))
}

internal fun returnCaseSubmissionTime(state: LocalReturnCaseStateV1, assessment: LocalReturnCaseAssessmentV1): UtcInstant =
    if (assessment.ordinaryTiming != null) state.recordedAt else state.facts.asserted.claimedAt

/** Literal unavailable-source assessment, including its original bounded codec round trip. */
internal fun returnCaseUnavailableAssessment(originalReceiptPresent: Boolean): LocalReturnCaseAssessmentV1 {
    val offered = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.PENDING,
        if (originalReceiptPresent) "QUALIFICATION_SOURCE_REQUIRED" else "ORIGINAL_RECEIPT_REQUIRED", "EVIDENCE_OR_POLICY_REVIEW")
    return localSalesDecode(localSalesEncode(offered, LocalReturnCaseAssessmentV1.serializer(), 16_384),
        LocalReturnCaseAssessmentV1.serializer(), 16_384)
}

private fun returnCaseIntakeProduction(identity: LocalReturnCaseProductionIdentity, targetId: DomainId,
    revision: Revision, reason: String, resources: Set<RecoveryResourceKey>,
    proposal: (UtcInstant) -> ProposedOutcome): LocalReturnCaseIntakeProduction =
    LocalReturnCaseIntakeProduction(returnCaseAuthorizationRequirement(identity, SecurityAction.RETURN_CLAIM,
        targetId, revision, reason), reason, returnCaseAffectedResources(resources, identity), proposal)

internal fun returnCaseAuthorizationRequirement(identity: LocalReturnCaseProductionIdentity, action: SecurityAction,
    targetId: DomainId, revision: Revision, reason: String, amount: Money? = null): AuthorizationRequirement =
    salesAuthorizationRequirement(action, identity.scope, targetId, revision, identity.operationId,
        identity.semanticDigest, reason, identity.actorId, amount)

internal fun returnCaseAffectedResources(resources: Set<RecoveryResourceKey>,
    identity: LocalReturnCaseProductionIdentity): Set<RecoveryResourceKey> = resources.toSet() +
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, identity.actorId) + entitlementResource(DomainId(identity.operationId.value))

/** Original V1 event/result/index/effects construction, shared without changing its bytes,
 * action limits, claim order, storage generations, capacity refusal or optional witnesses. */
internal fun returnCaseApplied(operationId: OperationId, before: LocalSalesRow<LocalReturnCaseStateV1>?, work: LocalSalesRow<LocalReturnCaseWorkV1>?,
    after: LocalReturnCaseStateV1, action: LocalReturnCaseActionV1, now: UtcInstant,
    binding: LocalReturnCaseReceiptBindingV1? = null, assessment: LocalReturnCaseAssessmentV1? = null,
    assessedBindingDigest: PayloadDigest? = null, expectedEntitlementRevision: Revision? = null,
    entitlementRevision: Revision? = null, claimRevision: Revision? = null,
    amount: Money? = null, beforeEntitlementDigest: PayloadDigest? = null, beforeClaimDigest: PayloadDigest? = null,
    beforeCashWorkDigest: PayloadDigest? = null, qualificationSources: List<LocalReturnCaseSourceReferenceV1> = emptyList(),
    sourceRecoveryResources: List<RecoveryResourceKey> = emptyList(),
    extraChanges: List<AggregateChange> = emptyList(), extraClaims: List<ClaimChange> = emptyList(),
    ordinaryInspection: LocalReturnOrdinaryInspectionV1? = null): ProposedOutcome {
    val limit = when (action) { LocalReturnCaseActionV1.ACCEPTED -> LocalReturnCaseProfileV1.MAX_EVENTS
        LocalReturnCaseActionV1.BOUND -> LocalReturnCaseProfileV1.MAX_EVENTS - 1
        else -> LocalReturnCaseProfileV1.MAX_EVENTS - 2 }
    if (after.events.size > limit) return caseRefused("CAPACITY_REQUIRED")
    val result = LocalReturnCaseResultV1.Applied(action, after.caseId, after.revision, after.stage, operationId,
        claimRevision, after.acceptedReturnId, entitlementRevision, amount)
    val event = LocalReturnCaseEventV1(after = after, action = action, operationId = operationId,
        recordedAt = now, beforeCaseOperationId = before?.value?.lastOperationId, beforeCaseDigest = before?.stored?.digest,
        beforeIndexOperationId = work?.value?.lastOperationId, beforeIndexDigest = work?.stored?.digest,
        binding = binding, assessment = assessment, assessedBindingDigest = assessedBindingDigest,
        expectedEntitlementRevision = expectedEntitlementRevision, entitlementRevision = entitlementRevision,
        beforeEntitlementDigest = beforeEntitlementDigest, beforeClaimDigest = beforeClaimDigest,
        beforeCashWorkDigest = beforeCashWorkDigest, qualificationSources = qualificationSources,
        sourceRecoveryResources = sourceRecoveryResources, result = result, ordinaryInspection = ordinaryInspection)
    val afterWork = returnCaseWorkAfter(work, after)
    val changes = listOf(returnCaseWrite(after, before?.stored), returnCaseEventWrite(event), returnCaseWorkWrite(afterWork, work?.stored)) + extraChanges
    val claims = listOf(ClaimChange.Acquire(LocalReturnCaseProfileV1.source("event", DomainId(operationId.value)), after.caseId)) +
        (if (work == null) listOf(ClaimChange.Acquire(LocalReturnCaseProfileV1.source("work", after.ownerId), after.ownerId)) else emptyList()) + extraClaims
    val encoded = LocalReturnCaseCommandCodecV1.result(result)
    if (changes.size > 128 || claims.size > 128 || changes.sumOf { it.payload.size.toLong() } + encoded.size > 1_048_576L) {
        return caseRefused("CAPACITY_REQUIRED")
    }
    return ProposedOutcome.Apply(encoded, changes, claims)
}

internal fun caseRefused(reason: String): ProposedOutcome.Reject =
    ProposedOutcome.Reject("sales.return_case.refused", LocalReturnCaseCommandCodecV1.result(LocalReturnCaseResultV1.Refused(reason)))

/** A scalar revision comparison only, called after the real original reader establishes
 * a complete receipt cut or proves absence. Null neither authenticates that absence
 * nor grants authority. Legacy unused expectations remain unobserved when absent. */
internal fun returnCaseEntitlementRevisionGuard(expected: Revision?, observed: Revision?): ProposedOutcome.Reject? =
    if (observed != null && observed != expected) caseRefused("REVISION_CONFLICT") else null

internal fun caseGuard(value: LocalReturnCaseStateV1, revision: Revision, actorId: DomainId, stage: LocalReturnCaseStageV1): ProposedOutcome.Reject? = when {
    value.ownerId != actorId -> caseRefused("OWNER_REQUIRED")
    value.revision != revision -> caseRefused("REVISION_CONFLICT")
    value.stage != stage -> caseRefused("IMMUTABLE_CASE_STAGE")
    else -> null
}

internal fun advanceCase(value: LocalReturnCaseStateV1, operationId: OperationId): LocalReturnCaseStateV1 =
    value.copy(revision = value.revision.next(), lastOperationId = operationId, events = value.events + operationId)

private fun bindingMatches(facts: LocalReturnCaseFactsV1, binding: LocalReturnCaseReceiptBindingV1): Boolean =
    (facts.asserted.saleId == null || facts.asserted.saleId == binding.saleId) &&
        (facts.asserted.portionId == null || facts.asserted.portionId == binding.portionId) &&
        (facts.asserted.quantity == null || facts.asserted.quantity == binding.quantity)

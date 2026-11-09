package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.types.UtcInstant

/** Exact native BEFORE reconstruction for supported CASE intake, ordinary qualification and
 * acceptance. Shared production compares the complete original requirement/result/effects/resources;
 * core closes native authorization, operation/result/journal/audit/upload and branch mutations.
 * No historical grant, CommandReadView or SalesCommand is manufactured. No-source-equivalent
 * PENDING originals retain their dispatch before source preparation; a source mismatch never
 * retries the unavailable route. Every advanced entitlement must join its full original lineage. */
public class LocalReturnCaseHistoryProducer(
    private val source: HistoricalSalesReturnCaseSourceV1 = UnavailableHistoricalSalesReturnCaseSourceV1,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalReturnCaseProfileV1.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            command.supervisorReview != null) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (original.nativeOriginalProvenance != null) return false
        check(original.scope == command.scope && original.operationId == command.operationId && original.command == command.command &&
            original.originalActorId == command.actorId && original.authenticationId == command.authenticationId &&
            original.policyRevision == command.policyRevision && original.semanticDigest == command.semanticDigest &&
            original.recordedAt == command.recordedAt && original.normalized == command.normalized && original.jobs.isEmpty() &&
            LocalReturnCaseCommandCodecV1.normalize(command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val request = LocalReturnCaseCommandCodecV1.decode(command.normalized)
        if (!original.committed && request !is LocalReturnCaseRequestV1.Qualify && request !is LocalReturnCaseRequestV1.Accept) return false
        if (original.committed && request is LocalReturnCaseRequestV1.Qualify &&
            !returnCaseHistorySupportedQualification(original, request)) return false
        val identity = LocalReturnCaseProductionIdentity(command.scope, command.actorId, command.operationId, command.semanticDigest)
        val sources = LocalReturnCaseHistorySources(command)
        var requiresNewEvent = true
        val production = when (request) {
            is LocalReturnCaseRequestV1.Record -> {
                val dependencies = LocalReturnCaseDependencies()
                val work = sources.findWork(command.actorId, dependencies)
                CaseProduction(returnCaseRecordProduction(identity, request, work, dependencies.snapshot(), sources.recordDuplicate(request.caseId)))
            }
            is LocalReturnCaseRequestV1.AppendReceiptEvidence -> CaseProduction(returnCaseAppendProduction(identity, request, sources.readCase(request.caseId)))
            is LocalReturnCaseRequestV1.Qualify -> {
                val cut = sources.readCase(request.caseId)
                val binding = cut.binding?.value?.binding
                val originalSale = binding?.let { findReturnCaseHistoryEntitlement(command, it.saleId, sources) }
                val omittedEntitlementProfile = !original.committed && binding != null &&
                    originalSale?.returnPolicyCapture != null &&
                    returnCaseHistorySourceOmittedEntitlementQualification(original, request, cut.row.value,
                        originalSale.entitlement.value.revision)
                val ordinaryRejectionProfile = !original.committed &&
                    returnCaseHistoryOrdinaryQualificationRejection(original, request, cut.row.value)
                if (!original.committed && !ordinaryRejectionProfile && (binding == null || originalSale?.returnPolicyCapture == null ||
                        !omittedEntitlementProfile && !returnCaseHistorySourceStaleQualification(original, request, cut.row.value,
                            originalSale.entitlement.value.revision))) return false
                // Exact original bytes and genuine BEFORE capture resources select this
                // dispatch. Complete BEFORE/effect/resource comparison still follows.
                val unavailableProfile = original.committed &&
                    (returnCaseHistoryUnavailableQualification(original, request, entitlementReturnPolicyResources(originalSale)) ||
                        returnCaseHistoryUnavailableOrdinaryQualification(original, request, entitlementReturnPolicyResources(originalSale)))
                // These are the same live pre-source branches, not fallback after preparation.
                val ordinaryPreSource = ordinaryRejectionProfile &&
                    (binding == null || originalSale == null || request.expectedEntitlementRevision == null)
                val prepared = if (unavailableProfile || omittedEntitlementProfile || ordinaryPreSource || binding == null || originalSale == null || request.expectedEntitlementRevision == null) null
                    else source.prepare(command, SalesReturnCaseQualificationRequestV1(command.scope, request.caseId,
                        returnCaseFactsDigest(cut.row.value.facts), checkNotNull(cut.binding).stored.digest,
                        cut.row.value.facts, binding, originalSale.sale.value, cut.row.value.recordedAt, cut.row.value.ownerId,
                        cut.row.value.originalOperationId, request.recordOriginalCommercialTiming, request.ordinaryInspection))
                check(prepared == null || prepared.scope == command.scope) { "sales.return.case_source_scope" }
                if (unavailableProfile || omittedEntitlementProfile || ordinaryPreSource) {
                    // An omitted expectation with a genuine initial entitlement takes the
                    // same pre-assessment rejection as live dispatch. This is not a retry
                    // after unavailable preparation or proof that an original is absent.
                    CaseProduction(returnCaseUnavailableQualificationProduction(identity, request, cut, originalSale))
                } else {
                    // Missing or mismatched source preparation never retries another profile.
                    if (prepared == null) return false
                    val references = prepared.sourceReferences.toList()
                    requireReturnCaseHistorySources(command, references)
                    val selectedPromises = linkedMapOf<LocalReturnCaseSourceReferenceV1, LocalSalesReturnPortionPromiseV1?>()
                    if (originalSale != null && binding != null) for (reference in references.filter {
                        it.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND
                    }) {
                        val placeholder = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.PENDING, "PREPARED", "PENDING",
                            salePolicyCapture = reference)
                        selectedPromises[reference] = requireReturnCaseHistoryPolicyForSale(command, originalSale, binding, placeholder)
                    }
                    val receiptEvents = if (request.recordOriginalCommercialTiming || request.ordinaryInspection != null ||
                            cut.row.value.facts.claimedReason != null)
                        readReturnCommercialReceiptEvents(command.scope, references) { command.aggregate(it) } else emptyMap()
                    val claimExists = sources.claimExists(request.caseId)
                    CaseProduction(returnCaseSourceQualificationProduction(identity, request, cut, originalSale,
                        prepared, references, selectedPromises, receiptEvents, claimExists))
                }
            }
            is LocalReturnCaseRequestV1.Accept -> {
                val cut = sources.readCase(request.caseId)
                val binding = cut.binding?.value?.binding
                if (cut.row.value.stage != LocalReturnCaseStageV1.BOUND_UNACCEPTED || binding == null) {
                    // The live unavailable route does not inspect or reserve a new event.
                    requiresNewEvent = false
                    CaseProduction(returnCaseUnavailableAcceptanceProduction(identity, request, cut))
                } else {
                    val originalSale = findReturnCaseHistoryEntitlement(command, binding.saleId, sources) ?: return false
                    val qualified = sources.readClaim(originalSale, request.caseId)
                    val work = readReturnCaseHistoryAcceptanceWork(command)
                    val duplicate = sources.acceptanceDuplicate(request)
                    sources.requireNewEvent()
                    requiresNewEvent = false
                    val append = prepareReturnCaseHistoryEntitlementAppend(command, originalSale, identity, request, sources)
                    CaseProduction(returnCaseAcceptanceProduction(identity, request, cut, originalSale, qualified,
                        work.custodian, work.cash, work.work, duplicate, append))
                }
            }
        }
        if (requiresNewEvent) sources.requireNewEvent()
        val expected = production.propose(command.recordedAt)
        command.requireRequirement(production.requirement)
        if (original.committed) {
            if (expected !is ProposedOutcome.Apply) return false
            command.requireLegacyEffects(expected, production.resources)
        } else {
            if (expected !is ProposedOutcome.Reject) return false
            command.requireReturnCaseQualificationRejection(expected, production.resources)
        }
        return true
    }

    /** Inert dispatch adapter only. Production values confer no historical authority. */
    private class CaseProduction(val requirement: AuthorizationRequirement, val resources: Set<RecoveryResourceKey>,
        val propose: (UtcInstant) -> ProposedOutcome) {
        constructor(production: LocalReturnCaseIntakeProduction) : this(production.requirement, production.affectedResources, production::propose)
        constructor(production: LocalReturnCaseSourceQualificationProduction) : this(production.requirement, production.affectedResources,
            { now -> production.evaluate(now).outcome })
    }
}

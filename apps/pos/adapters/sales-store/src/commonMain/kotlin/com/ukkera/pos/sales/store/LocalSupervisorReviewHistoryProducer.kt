package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/** CLOSED historical successful audited RETAIL/FOOD supervisor Review rejection producer.
 * It executes no handler, writes nothing, authenticates nobody and manufactures no native
 * authority. The actual original authorized rejection, sensitive result and entire native
 * operation/result/journal/audit/upload/branch closure are required, not a generic Reject. */
public class LocalSupervisorReviewHistoryProducer(
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
) : RuntimeHistoryBusinessProducer {
    private val retailSources = LocalRetailSupervisorReviewHistorySources(tenderPolicies)
    override val commands: Set<CommandKey> = setOf(LocalRetailSupervisorCodecV1.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND)

    /** Trusted registry subtype dispatch only. Once selected, a failed Review join must
     * retain its hold; it must not fall back to an Apply producer or a rejection importer. */
    public fun isReviewRequest(key: CommandKey, normalized: StoredBytes): Boolean = when (key) {
        LocalRetailSupervisorCodecV1.COMMAND -> LocalRetailSupervisorCodecV1.decode(normalized) is LocalRetailSupervisorRequestV1.Review
        LocalFoodPaymentSupervisorCodec.COMMAND -> LocalFoodPaymentSupervisorCodec.decode(normalized) is LocalFoodPaymentSupervisorRequest.Review
        else -> false
    }

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val profile = command.supervisorReview ?: return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        check(profile.command == command.command && !original.committed && original.code == profile.rejectionCode &&
            original.nativeOriginalProvenance == null && original.scope == command.scope && original.operationId == command.operationId &&
            original.originalActorId == command.actorId && original.command == command.command && original.semanticDigest == command.semanticDigest &&
            original.authenticationId == command.authenticationId && original.policyRevision == command.policyRevision &&
            original.normalized == command.normalized && original.recordedAt == command.recordedAt &&
            original.aggregateWrites.isEmpty() && original.claimChanges.isEmpty() && original.jobs.isEmpty())
        return when (profile) {
            RuntimeHistoricalSupervisorReviewProfile.RETAIL -> retail(command)
            RuntimeHistoricalSupervisorReviewProfile.FOOD -> food(command)
        }
    }

    private suspend fun retail(command: RuntimeHistoricalCommand): Boolean {
        check(command.command == LocalRetailSupervisorCodecV1.COMMAND && LocalRetailSupervisorCodecV1.normalize(command.normalized) == command.normalized)
        val request = LocalRetailSupervisorCodecV1.decode(command.normalized) as? LocalRetailSupervisorRequestV1.Review ?: return false
        val original = retailSources.original(command, request.originalRecordingOperationId)
        val resolution = retailSources.resolution(command, original)
        val slot = original.slot.value
        val target = slot.target
        val expected = LocalRetailSupervisorReviewV1(retailSummary(original), resolution?.let(::retailSummary),
            if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) retailPaymentObservation(original.fact.value) else null,
            if (target.kind == SalesRecordingKind.REFUND_ATTESTATION) retailRefundObservation(original.fact.value) else null,
            resolution?.takeIf { target.kind == SalesRecordingKind.PAYMENT_ATTESTATION }?.let { retailPaymentObservation(it.fact.value) },
            resolution?.takeIf { target.kind == SalesRecordingKind.REFUND_ATTESTATION }?.let { retailRefundObservation(it.fact.value) })
        command.requireRequirement(salesAuthorizationRequirement(retailFactAction(target.kind, LocalJournalFactPhaseV3.RESOLUTION),
            command.scope, slot.id, Revision(0), command.operationId, command.semanticDigest, "financial.supervisor.review",
            slot.actorId, slot.domain.amount))
        command.requireSupervisorReviewRejection(ProposedOutcome.Reject("sales.supervisor.reviewed", LocalRetailSupervisorCodecV1.review(expected)),
            retailJournalAuthorityResources(slot.requirement()) + recordingAuthority(command.actorId))
        return true
    }

    private suspend fun food(command: RuntimeHistoricalCommand): Boolean {
        check(command.command == LocalFoodPaymentSupervisorCodec.COMMAND && LocalFoodPaymentSupervisorCodec.normalize(command.normalized) == command.normalized)
        val request = LocalFoodPaymentSupervisorCodec.decode(command.normalized) as? LocalFoodPaymentSupervisorRequest.Review ?: return false
        val original = LocalFoodSupervisorReviewHistorySources.original(command, request.originalRecordingOperationId)
        val resolution = LocalFoodSupervisorReviewHistorySources.resolution(command, original)
        val slot = original.slot.value
        val expected = LocalFoodPaymentSupervisorReview(foodPaymentOriginalSummary(original),
            (LocalFoodPaymentCodec.decode(original.raw.value.body()) as LocalFoodPaymentRequest.RecordOriginal).outcome,
            resolution?.let(::foodPaymentResolutionSummary), resolution?.let {
                (LocalFoodPaymentCodec.decode(it.raw.value.body()) as LocalFoodPaymentRequest.RecordResolution).outcome
            })
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.COLLECTION_RESOLVE, command.scope,
            original.slot.stored.key.id, Revision(0), command.operationId, command.semanticDigest, "food.payment.supervisor.review",
            slot.originalActorId, slot.domain.amount))
        command.requireSupervisorReviewRejection(ProposedOutcome.Reject("food.payment.supervisor.reviewed",
            LocalFoodPaymentSupervisorCodec.review(expected)), slot.domain.financialResources.toSet() +
            foodPaymentAuthorityResources(slot.requirement()) + recordingAuthority(command.actorId))
        return true
    }

    /** Same immutable raw/current-at-cut application projection as the actual RETAIL Review. */
    private fun retailSummary(cut: LocalRetailJournalFactCutV3): LocalJournalFactSummaryV3 = with(cut.fact.value) {
        LocalJournalFactSummaryV3(target, phase, target.factOperation(phase), originalActorId, recordingActorId, bodyDigest,
            originalBodyDigest, recordedAt, cut.slot.value.domain.amount, cut.slot.value.domain.tender,
            cut.application.value.state, cut.application.value.reason, cut.application.value.lastApplicationOperationId,
            cut.application.value.appliedAction)
    }
}

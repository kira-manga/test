package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*

/** Complete original pricing decisions, including deterministic refusals. The fixed
 * historical reader uses native BEFORE sources and their already joined originals;
 * it never re-prepares a live handler or constructs an authorization/read-view source.
 * Core separately closes original employee authority, every native mutation, the
 * protected result/audit/upload sidecars and the final commit deadline witness. */
internal class LocalPricingHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalPricingProfile.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (retained.nativeOriginalProvenance != null) return false
        check(LocalPricingCodec.encode(LocalPricingCodec.decode(command.normalized)) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val request = LocalPricingCodec.decode(command.normalized)
        val prepared = prepareLocalPricing(request, LocalPricingSourceReader.original(command), LocalPricingIdentity.original(command))
        if (prepared.sourceUnavailable) return false
        command.requireRequirement(prepared.requirement)
        // Native-selected employee original proofs require null action approval. The
        // actual retained actor/policy metadata supplies no current authorization.
        val outcome = prepared.outcome(LocalPricingActorAudit(command.actorId, command.policyRevision, null), command.recordedAt)
        when (outcome) {
            is ProposedOutcome.Apply -> {
                if (!retained.committed) return false
                command.requireLegacyReadEvidence()
                command.requireLegacyEffects(outcome, prepared.resources)
            }
            is ProposedOutcome.Reject -> {
                if (retained.committed) return false
                command.requirePricingRejection(outcome, prepared.resources)
            }
        }
        return true
    }
}

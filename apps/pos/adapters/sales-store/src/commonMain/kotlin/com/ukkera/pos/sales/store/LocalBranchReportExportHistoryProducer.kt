package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.semanticDigest

/** Native-history closure of committed Begin/Finish audit observations, not file publication.
 * Every present BEFORE row is joined to its exact earlier native original. The live pure
 * producer supplies the unchanged requirement, result, ordered effects and resource set.
 * Core separately closes employee authorization, deadline and complete journal/audit/outbox.
 * Rejected commands, unsupported history and native file outcomes retain their own holds. */
public class LocalBranchReportExportHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalBranchReportExportCodecV1.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            command.supervisorReview != null) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (!original.committed || original.nativeOriginalProvenance != null) return false
        check(original.scope == command.scope && original.operationId == command.operationId &&
            original.command == command.command && original.originalActorId == command.actorId &&
            original.authenticationId == command.authenticationId && original.policyRevision == command.policyRevision &&
            original.semanticDigest == command.semanticDigest && original.recordedAt == command.recordedAt &&
            original.normalized == command.normalized && original.jobs.isEmpty() &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val request = LocalBranchReportExportCodecV1.decodeRequest(command.normalized)
        check(LocalBranchReportExportCodecV1.encode(request) == command.normalized)
        val before = command.aggregateSource(LocalBranchReportExportProfileV1.key(request.exportId))
        val owner = command.claim(LocalBranchReportExportProfileV1.owner(request.exportId))
        val current = requireReportExportSource(command.scope, request.exportId, before?.row, owner) { operation ->
            (command.earlierOriginal(operation) as? RetainedOriginalV2.Legacy)?.evidence
        }
        if (current != null) {
            val selected = (before?.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
            val last = (command.earlierOriginal(current.value.view.lastOperationId) as? RetainedOriginalV2.Legacy)?.evidence
                ?: return false
            check(selected === last)
        }
        val production = LocalBranchReportExportProduction(command.scope, command.actorId, command.operationId,
            command.semanticDigest, request, current)
        val expected = production.propose(command.recordedAt) as? ProposedOutcome.Apply ?: return false
        command.requireRequirement(production.requirement)
        command.requireLegacyEffects(expected, production.affectedResources)
        return true
    }
}

package com.ukkera.pos.database.setup

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/**
 * Restart/reopen producer for the two additive setup-result commands. It proves the retained
 * employee requirement and exact immutable effect/result pair. A refusal is never recorded by
 * these handlers; duplicate/unsettled actions are admitted as no-effect holds, so no invented
 * rejection profile is required.
 */
public class LocalSetupResultHistoryProducer(
    private val prerequisites: LocalSetupGoLivePrerequisiteSource = UnavailableLocalSetupGoLivePrerequisites,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(
        LocalSetupResultProfileV1.ACKNOWLEDGE,
        LocalSetupResultProfileV1.GO_LIVE,
    )

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.authority != com.ukkera.pos.database.RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val evidence = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (!evidence.committed || evidence.nativeOriginalProvenance != null ||
            evidence.command != command.command) return false
        val request = LocalSetupResultCodecV1.decodeRequest(evidence.normalized)
        val outcome = LocalSetupResultCodecV1.decodeOutcome(evidence.result)
        return when (evidence.command) {
            LocalSetupResultProfileV1.ACKNOWLEDGE -> joinAcknowledgement(
                command, request, outcome,
                LocalSetupResultProfileV1.ACKNOWLEDGE_REASON,
                SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE,
                LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND,
                LocalSetupResultProfileV1.ACKNOWLEDGEMENT_SCHEMA,
            )
            LocalSetupResultProfileV1.GO_LIVE -> joinGoLive(command, request, outcome)
            else -> false
        }
    }

    private suspend fun joinAcknowledgement(
        command: RuntimeHistoricalCommand,
        request: LocalSetupResultRequestV1,
        outcome: LocalSetupResultOutcomeV1,
        reason: String,
        action: SecurityAction,
        kind: String,
        schema: String,
    ): Boolean {
        if (outcome.status != LocalSetupResultStatusV1.ACKNOWLEDGED || outcome.goLive != null) return false
        val record = outcome.acknowledgement ?: return false
        if (!command.matchesOriginalSetup(record.installationId, record.originalRequestId,
            record.issuedAttemptId, record.attemptSequence, record.definitionDigest,
            record.genesisDigest, record.receiptDigest)) return false
        require(record.installationId == request.installationId &&
            record.organizationId == command.scope.organizationId &&
            record.companyId == command.scope.companyId &&
            record.branchId == command.scope.branchId &&
            record.actorId == command.actorId &&
            record.operationId == command.operationId &&
            record.acknowledgedAt == command.recordedAt &&
            record.policyRevision == command.policyRevision &&
            record.masterAuthorityEpoch == evidenceEpoch(command)) {
            "Setup acknowledgement history identity mismatch"
        }
        command.requireRequirement(requirement(command, request.installationId, action, reason))
        val expected = ProposedOutcome.Apply(
            result = LocalSetupResultCodecV1.encodeOutcome(outcome),
            changes = listOf(AggregateChange(
                com.ukkera.pos.database.AggregateKey(kind, record.installationId), null, schema,
                LocalSetupResultCodecV1.encodeAcknowledgement(record),
            )),
        )
        command.requireLegacyEffects(expected, resources(command, record.installationId))
        return true
    }

    private suspend fun joinGoLive(
        command: RuntimeHistoricalCommand,
        request: LocalSetupResultRequestV1,
        outcome: LocalSetupResultOutcomeV1,
    ): Boolean {
        if (outcome.status != LocalSetupResultStatusV1.LIVE || outcome.acknowledgement != null) return false
        val record = outcome.goLive ?: return false
        if (!command.matchesOriginalSetup(record.installationId, record.originalRequestId,
            record.issuedAttemptId, record.attemptSequence, record.definitionDigest,
            record.genesisDigest, record.receiptDigest)) return false
        require(record.installationId == request.installationId &&
            record.organizationId == command.scope.organizationId &&
            record.companyId == command.scope.companyId &&
            record.branchId == command.scope.branchId &&
            record.actorId == command.actorId &&
            record.operationId == command.operationId &&
            record.occurredAt == command.recordedAt &&
            record.policyRevision == command.policyRevision &&
            record.masterAuthorityEpoch == evidenceEpoch(command) &&
            record.prerequisites == outcome.prerequisites &&
            record.prerequisites.all { it.settled }) {
            "Setup go-live history identity mismatch"
        }
        command.requireRequirement(requirement(command, request.installationId,
            SecurityAction.SETUP_BUSINESS_GO_LIVE, LocalSetupResultProfileV1.GO_LIVE_REASON))
        val expected = ProposedOutcome.Apply(
            result = LocalSetupResultCodecV1.encodeOutcome(outcome),
            changes = listOf(AggregateChange(
                com.ukkera.pos.database.AggregateKey(LocalSetupResultProfileV1.GO_LIVE_KIND, record.installationId),
                null,
                LocalSetupResultProfileV1.GO_LIVE_SCHEMA,
                LocalSetupResultCodecV1.encodeGoLive(record),
            )),
        )
        val before = com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command)
        val observed = prerequisites.historicalObservations(before, record) ?: return false
        if (record.prerequisites.filter { it.code != LocalSetupPrerequisiteCodeV1.NATIVE_INSTALLATION_SETTLEMENT }
                .sortedBy { it.code.ordinal } != observed.sortedBy { it.code.ordinal }) return false
        val native = record.prerequisites.single { it.code == LocalSetupPrerequisiteCodeV1.NATIVE_INSTALLATION_SETTLEMENT }
        if (!native.settled || native.binding != "native.installation.settlement:${record.receiptDigest.value}") return false
        val prerequisiteResources = prerequisites.recoveryResources(before, record.installationId) ?: return false
        command.requireLegacyEffects(expected, resources(command, record.installationId) + prerequisiteResources)
        return true
    }

    private fun requirement(command: RuntimeHistoricalCommand, installationId: com.ukkera.pos.types.DomainId,
        action: SecurityAction, reason: String): AuthorizationRequirement =
        AuthorizationRequirement(
            permission = Permission(action, PermissionVerb.PERFORM),
            scope = command.scope,
            binding = ActionBinding(installationId, Revision(0), command.semanticDigest,
                command.operationId, reason),
        )

    private fun resources(command: RuntimeHistoricalCommand,
        installationId: com.ukkera.pos.types.DomainId): Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, installationId),
    )

    private fun evidenceEpoch(command: RuntimeHistoricalCommand): Revision =
        ((command.original as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("Setup history original unavailable")).authorityEpoch
}

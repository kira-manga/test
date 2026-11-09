package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*

/** Binding-only native history, derived from complete genuine BEFORE sources and the live pure producer.
 * Core independently authenticates original employee/session/device/policy, every mutation, dependency,
 * result and resource. No handler, current grant, historical CommandReadView or native importer is made. */
public class LocalFoodTableAssignmentHistoryProducer(private val directory: HistoricalFoodTableAssignmentDirectorySourceV1) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalFoodTableAssignmentProfileV1.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            command.supervisorReview != null || command.originalApproval != null) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (original.nativeOriginalProvenance != null || original.jobs.isNotEmpty() || original.claimChanges.isNotEmpty()) return false
        val result = LocalFoodTableAssignmentCodecV1.decodeResult(original.result)
        if (original.committed) {
            if (result.code != LocalFoodTableAssignmentProfileV1.SAVED) return false
        } else if (original.code !in LocalFoodTableAssignmentProfileV1.REJECTION_CODES || original.code != result.code ||
            original.aggregateWrites.isNotEmpty()) return false
        check(original.scope == command.scope && original.operationId == command.operationId && original.command == command.command &&
            original.originalActorId == command.actorId && original.authenticationId == command.authenticationId &&
            original.policyRevision == command.policyRevision && original.semanticDigest == command.semanticDigest &&
            original.normalized == command.normalized && original.recordedAt == command.recordedAt)
        val request = LocalFoodTableAssignmentCodecV1.decode(command.normalized)
        check(LocalFoodTableAssignmentCodecV1.encode(request) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)

        val order = foodTableAssignmentHistoricalOrder(command, request.orderId)
        val binding = foodTableAssignmentHistoricalBinding(command, request.orderId, directory)
        check(order != null || binding == null) { "food.table.assignment.history_binding_without_order" }
        val definitions = directory.before(command)
        check(command.aggregateSource(foodTableAssignmentVersionKey(command.operationId)) == null) {
            "food.table.assignment.history_original_version_already_present"
        }
        val identity = FoodTableAssignmentIdentityV1(command.scope, command.actorId, command.operationId, command.semanticDigest)
        val production = foodTableAssignmentProduction(identity, request, order, binding, definitions)
        val expected = production.propose(command.recordedAt, FoodTableAssignmentAuthorizationV1(command.actorId,
            command.authenticationId, command.authorizationGeneration, command.policyRevision))
        command.requireRequirement(production.requirement)
        if (original.committed) {
            if (expected !is ProposedOutcome.Apply) return false
            command.requireLegacyEffects(expected, production.affectedResources)
        } else {
            if (expected !is ProposedOutcome.Reject) return false
            command.requireFoodTableAssignmentRejection(expected, production.affectedResources)
        }
        return true
    }
}

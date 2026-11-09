package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Explicit binding-only CAS command on the real current Master writer. No FOOD writer is borrowed. */
public class LocalFoodTableAssignmentHandler(private val stock: LocalStockRepository,
    private val directory: TransactionFoodTableAssignmentDirectorySourceV1) : MasterCommandHandler,
    LocalMasterRpcRetainedResultSourceAccessProvider {
    override val key: CommandKey = LocalFoodTableAssignmentProfileV1.COMMAND
    override val writableKinds: Set<String> = setOf(LocalFoodTableAssignmentProfileV1.BINDING, LocalFoodTableAssignmentProfileV1.VERSION)
    override val claimNamespaces: Set<String> = emptySet()
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodTableAssignmentCodecV1.encode(LocalFoodTableAssignmentCodecV1.decode(input))

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS &&
            LocalFoodTableAssignmentCodecV1.encode(LocalFoodTableAssignmentCodecV1.decode(normalized)) == normalized

    /** Disclosure closes the exact earlier writers, never a fresh assignment or today's owner. */
    override suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile,
        normalized: StoredBytes, view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        if (!supportsRpcWorkflow(profile, normalized) || context.key != key || context.scope != view.scope ||
            semanticDigest(context.scope, context.originalActorId, key, normalized) != context.semanticDigest) return null
        val bounded = FoodReadView(commerceLoan(view, stock))
        val original = bounded.retainedCommand(context.operationId) ?: return null
        if (original.nativeOriginalProvenance != null || original.scope != context.scope ||
            original.operationId != context.operationId || original.originalActorId != context.originalActorId ||
            original.command != context.key || original.normalized != normalized || original.semanticDigest != context.semanticDigest)
            return null
        val identity = FoodTableAssignmentIdentityV1(context.scope, context.originalActorId,
            context.operationId, context.semanticDigest)
        val request = LocalFoodTableAssignmentCodecV1.decode(normalized)
        if (original.committed) {
            val order = foodTableAssignmentRetainedOrder(bounded, original, directory)
            return listOf(foodTableAssignmentRequirement(identity, request, order.value.ownerId))
        }
        if (original.code !in LocalFoodTableAssignmentProfileV1.REJECTION_CODES) return null
        val expected = foodTableAssignmentReject(original.code)
        check(original.result == expected.result && original.resultHash == foodTableAssignmentDigest(original.result) &&
            original.aggregateWrites.isEmpty() && original.claimChanges.isEmpty() && original.jobs.isEmpty())
        // The genuine native BEFORE projection is source DATA only. Independently join
        // its FOOD writers/claims before interpreting an owner; no current ORDER fallback.
        val sources = view.foodTableAssignmentRejectedOriginalSources(context.operationId) ?: return null
        requireSameFoodTableAssignmentRetainedOriginal(original, sources.evidence)
        val order = foodTableAssignmentRejectedOrder(bounded, sources, request.orderId) ?: return null
        val requirement = foodTableAssignmentRequirement(identity, request, order.value.ownerId)
        if (sources.requirement != requirement) return null
        return listOf(requirement)
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == view.scope &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalFoodTableAssignmentCodecV1.decode(normalized)
        val identity = FoodTableAssignmentIdentityV1(context.scope, context.originalActorId, context.operationId, context.semanticDigest)
        val bounded = FoodReadView(commerceLoan(view, stock))
        // This genuine bounded header selects only a refusal/hold's actual owner requirement.
        // A successful or recorded deterministic proposal still closes both full FOOD writers below.
        val header = foodObserveRow(bounded, LocalFoodProfileV1.ORDER, request.orderId)
        val owner = (header?.envelope?.body as? FoodBodyV1.Order)?.ownerId ?: context.originalActorId
        return try {
            val order = if (header == null) null else foodTableAssignmentOrderCut(foodOrder(bounded, request.orderId, includeClosed = true))
            val binding = readFoodTableAssignmentBinding(bounded, request.orderId, directory)
            check(order != null || binding == null) { "food.table.assignment.binding_without_order" }
            val definitions = directory.current(bounded)
            check(bounded.aggregate(foodTableAssignmentVersionKey(context.operationId)) == null) {
                "food.table.assignment.original_version_already_present"
            }
            FoodTableAssignmentPrepared(identity, foodTableAssignmentProduction(identity, request, order, binding, definitions))
        } catch (_: FoodCapacityExceeded) {
            // Partial source/history work cannot supply a canonical recorded refusal or absence.
            FoodTableAssignmentUnavailablePrepared(identity, request, owner)
        }
    }
}

private class FoodTableAssignmentPrepared(private val identity: FoodTableAssignmentIdentityV1,
    private val production: FoodTableAssignmentProductionV1) : PreparedMutation() {
    override val requirement: AuthorizationRequirement get() = production.requirement
    override val affectedResources: Set<RecoveryResourceKey> get() = production.affectedResources
    override val allowsActionApproval: Boolean = false
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        requireFoodTableAssignmentAuthorization(authorization, requirement, identity.actorId)
        return production.propose(recordedAt, FoodTableAssignmentAuthorizationV1(authorization.actorId,
            authorization.authenticationId, authorization.generation, authorization.policyRevision))
    }
}

/** Refusal-only admission restriction. No owner/source/grant is fabricated for an Apply. */
private class FoodTableAssignmentUnavailablePrepared(identity: FoodTableAssignmentIdentityV1,
    request: LocalFoodTableAssignmentRequestV1, ownerId: DomainId) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = foodTableAssignmentRequirement(identity, request, ownerId)
    override val affectedResources: Set<RecoveryResourceKey> = setOf(orderResource(request.orderId), orderResource(ownerId),
        orderResource(DomainId(identity.operationId.value)), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, ownerId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, identity.actorId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, identity.scope.branchId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.floorId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.tableId))
    override val admissionRestriction: PreparedMutationAdmissionRestriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
    override val allowsActionApproval: Boolean = false
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome =
        error("food.table.assignment.source_unavailable")
}

internal fun requireFoodTableAssignmentAuthorization(authorization: AuthorizedAction,
    requirement: AuthorizationRequirement, actorId: DomainId) {
    check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
        authorization.isBoundTo(requirement, actorId)) { "food.table.assignment.authorization" }
}

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Literal source coordinates. The actual retained FOOD writer supplies the body separately. */
@Serializable
internal data class FoodTableAssignmentFoodSourceV1(val kind: String, val id: DomainId, val revision: Revision,
    val schema: String, val digest: PayloadDigest, val operationId: OperationId, val actorId: DomainId) {
    init {
        require(kind == LocalFoodProfileV1.ORDER || kind == LocalFoodProfileV1.OWNER_INDEX)
        require(revision.value > 0 && (schema == LocalFoodProfileV1.schema(kind) || foodStreamedSchema(kind, schema)))
    }
}

@Serializable
internal data class FoodTableAssignmentBeforeV1(val revision: Revision, val operationId: OperationId,
    val actorId: DomainId, val digest: PayloadDigest) {
    init { require(revision.value > 0) }
}

@Serializable
internal data class FoodTableAssignmentAuthorizationV1(val actorId: DomainId, val authenticationId: DomainId,
    val generation: SessionGeneration, val policyRevision: Revision)

/** Compact original links preserve existing FOOD and configuration bytes without embedding them. */
@Serializable
internal data class FoodTableAssignmentRecordV1(val scope: BranchScope, val operationId: OperationId,
    val semanticDigest: PayloadDigest, val authorization: FoodTableAssignmentAuthorizationV1, val recordedAt: UtcInstant,
    val request: LocalFoodTableAssignmentRequestV1, val binding: LocalFoodTableBindingV1,
    val orderSource: FoodTableAssignmentFoodSourceV1, val ownerIndexSource: FoodTableAssignmentFoodSourceV1,
    val before: FoodTableAssignmentBeforeV1?, val directory: LocalFoodTableAssignmentDirectoryV1) {
    init {
        require(orderSource.kind == LocalFoodProfileV1.ORDER && orderSource.id == request.orderId &&
            ownerIndexSource.kind == LocalFoodProfileV1.OWNER_INDEX)
        require(binding.observedOrderRevision == orderSource.revision && binding.observedOrderRevision == request.expectedOrderRevision &&
            binding.floorId == request.floorId && binding.floorRevision == request.expectedFloorRevision &&
            binding.tableId == request.tableId && binding.tableRevision == request.expectedTableRevision)
        require(request.expectedAssignmentRevision == before?.revision && before?.revision?.value != Long.MAX_VALUE &&
            binding.revision == (before?.revision?.next() ?: Revision(1)))
        require(before?.operationId != operationId)
    }
}

/** Inert actual envelopes only, never a retained view, stock loan or authority. */
internal data class FoodTableAssignmentOrderCutV1(val order: FoodEnvelopeV1, val ownerIndex: FoodEnvelopeV1,
    val orderSource: FoodTableAssignmentFoodSourceV1, val ownerIndexSource: FoodTableAssignmentFoodSourceV1) {
    val value: FoodBodyV1.Order get() = order.body as FoodBodyV1.Order
}

internal data class FoodTableAssignmentBindingCutV1(val row: StoredAggregate, val value: FoodTableAssignmentRecordV1,
    val recoveryResources: Set<RecoveryResourceKey>) {
    fun reference(): FoodTableAssignmentBeforeV1 = FoodTableAssignmentBeforeV1(value.binding.revision,
        value.operationId, value.authorization.actorId, row.digest)
}

internal data class FoodTableAssignmentIdentityV1(val scope: BranchScope, val actorId: DomainId,
    val operationId: OperationId, val semanticDigest: PayloadDigest)

internal class FoodTableAssignmentProductionV1(val requirement: AuthorizationRequirement,
    resources: Set<RecoveryResourceKey>, private val proposal: (UtcInstant, FoodTableAssignmentAuthorizationV1) -> ProposedOutcome) {
    val affectedResources: Set<RecoveryResourceKey> = resources.toSet()
    fun propose(at: UtcInstant, authorization: FoodTableAssignmentAuthorizationV1): ProposedOutcome = proposal(at, authorization)
}

internal fun foodTableAssignmentOrderCut(order: FoodOrderCut): FoodTableAssignmentOrderCutV1 =
    FoodTableAssignmentOrderCutV1(order.order.envelope, order.ownerIndex.envelope,
        foodTableAssignmentFoodSource(order.order), foodTableAssignmentFoodSource(order.ownerIndex)).also {
        requireFoodTableAssignmentOrderCut(it, order.order.envelope.scope)
    }

private fun foodTableAssignmentFoodSource(row: FoodRow): FoodTableAssignmentFoodSourceV1 =
    FoodTableAssignmentFoodSourceV1(row.row.key.kind, row.row.key.id, row.row.revision, row.row.schema,
        row.row.digest, row.envelope.operationId,
        row.normalizedProducer?.identity?.producer?.originalActorId ?: row.producer.originalActorId)

internal fun requireFoodTableAssignmentOrderCut(cut: FoodTableAssignmentOrderCutV1, scope: BranchScope) {
    for ((envelope, source) in listOf(cut.order to cut.orderSource, cut.ownerIndex to cut.ownerIndexSource)) {
        requireFoodStoredEnvelope(envelope, source.schema)
        check(envelope.scope == scope && envelope.kind == source.kind && envelope.id == source.id &&
            envelope.revision == source.revision && envelope.operationId == source.operationId &&
            foodTableAssignmentDigest(foodStoredEnvelopeBytes(envelope, source.schema)) == source.digest)
    }
    val order = cut.value
    val index = cut.ownerIndex.body as? FoodBodyV1.OwnerIndex ?: error("food.table.assignment.owner_index_body")
    check(cut.ownerIndex.id == order.ownerId &&
        (cut.order.id in index.orderIds) == (order.closedByOperationId == null)) {
        "food.table.assignment.owner_index_mismatch"
    }
}

internal fun foodTableAssignmentProduction(identity: FoodTableAssignmentIdentityV1,
    request: LocalFoodTableAssignmentRequestV1, order: FoodTableAssignmentOrderCutV1?,
    before: FoodTableAssignmentBindingCutV1?, suppliedDirectory: LocalFoodTableAssignmentDirectoryV1): FoodTableAssignmentProductionV1 {
    check(semanticDigest(identity.scope, identity.actorId, LocalFoodTableAssignmentProfileV1.COMMAND,
        LocalFoodTableAssignmentCodecV1.encode(request)) == identity.semanticDigest)
    order?.let { requireFoodTableAssignmentOrderCut(it, identity.scope); check(it.order.id == request.orderId) }
    check(before == null || order != null && before.value.scope == identity.scope &&
        before.value.request.orderId == request.orderId && before.value.operationId != identity.operationId)
    val directory = LocalFoodTableAssignmentDirectoryV1(suppliedDirectory.definitions.toList())
    val requirement = foodTableAssignmentRequirement(identity, request, order?.value?.ownerId ?: identity.actorId)
    val resources = foodTableAssignmentResources(identity, request, order, directory) + before?.recoveryResources.orEmpty()
    val refusal = foodTableAssignmentRejection(identity.actorId, request, order, before?.value?.binding, directory)
    val previous = before?.reference()
    return FoodTableAssignmentProductionV1(requirement, resources) { at, authorization ->
        check(authorization.actorId == identity.actorId)
        if (refusal != null) return@FoodTableAssignmentProductionV1 foodTableAssignmentReject(refusal)
        val actual = checkNotNull(order)
        val binding = LocalFoodTableBindingV1(previous?.revision?.next() ?: Revision(1), actual.order.revision,
            request.floorId, request.expectedFloorRevision, request.tableId, request.expectedTableRevision)
        val record = FoodTableAssignmentRecordV1(identity.scope, identity.operationId, identity.semanticDigest, authorization,
            at, request, binding, actual.orderSource, actual.ownerIndexSource, previous, directory)
        try {
            foodTableAssignmentApply(record)
        } catch (_: LocalSalesWireCapacityExceeded) {
            foodTableAssignmentReject("food.table.assignment.capacity_required")
        }
    }
}

/** Shared first-refusal derivation. No source/status is inferred from free-text labels. */
internal fun foodTableAssignmentRejection(actorId: DomainId, request: LocalFoodTableAssignmentRequestV1,
    order: FoodTableAssignmentOrderCutV1?, before: LocalFoodTableBindingV1?,
    directory: LocalFoodTableAssignmentDirectoryV1): String? {
    if (order == null || order.value.ownerId != actorId) return "food.table.assignment.order_unavailable"
    if (order.order.revision != request.expectedOrderRevision) return "food.table.assignment.order_stale"
    if (order.value.closedByOperationId != null) return "food.table.assignment.order_closed"
    if (order.value.service.channel != LocalSalesChannel.DINE_IN) return "food.table.assignment.channel_required"
    if (order.value.handoff != null) return "food.table.assignment.handoff_pending"
    if (request.expectedAssignmentRevision != before?.revision) return "food.table.assignment.stale"
    val floor = directory.definition(LocalFoodTableDefinitionKindV1.FLOOR, request.floorId)
        ?: return "food.table.assignment.floor_required"
    val table = directory.definition(LocalFoodTableDefinitionKindV1.TABLE, request.tableId)
        ?: return "food.table.assignment.table_required"
    if (table.floorId != floor.id) return "food.table.assignment.table_floor_mismatch"
    if (floor.revision != request.expectedFloorRevision) return "food.table.assignment.floor_stale"
    if (table.revision != request.expectedTableRevision) return "food.table.assignment.table_stale"
    if (!floor.active || !table.active) return "food.table.assignment.inactive"
    if (before?.revision?.value == Long.MAX_VALUE) return "food.table.assignment.capacity_required"
    return null
}

internal fun foodTableAssignmentRequirement(identity: FoodTableAssignmentIdentityV1,
    request: LocalFoodTableAssignmentRequestV1, ownerId: DomainId): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM), identity.scope,
    ActionBinding(request.orderId, request.expectedOrderRevision, identity.semanticDigest, identity.operationId, "food.table.assignment"),
    owner = OwnerConstraint.OwnerPrivate(ownerId))

internal fun foodTableAssignmentResources(identity: FoodTableAssignmentIdentityV1,
    request: LocalFoodTableAssignmentRequestV1, order: FoodTableAssignmentOrderCutV1?,
    directory: LocalFoodTableAssignmentDirectoryV1): Set<RecoveryResourceKey> = buildSet {
    fun authorization(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
    add(authorization(identity.scope.branchId)); add(authorization(identity.actorId))
    add(orderResource(request.orderId)); add(orderResource(DomainId(identity.operationId.value)))
    add(authorization(request.floorId)); add(authorization(request.tableId))
    if (order != null) {
        addAll(foodEnvelopeResources(order.order, order.ownerIndex, emptyList()))
        for (source in listOf(order.orderSource, order.ownerIndexSource)) {
            add(orderResource(DomainId(source.operationId.value))); add(authorization(source.actorId))
        }
    }
    for (source in directory.definitions) {
        add(authorization(source.id)); add(authorization(source.actorId)); add(authorization(DomainId(source.operationId.value)))
        source.floorId?.let { add(authorization(it)) }
    }
}

internal fun foodTableAssignmentApply(record: FoodTableAssignmentRecordV1): ProposedOutcome.Apply {
    val payload = foodTableAssignmentRecordBytes(record)
    val result = LocalFoodTableAssignmentResultV1(LocalFoodTableAssignmentProfileV1.SAVED, record.request.orderId,
        record.binding.observedOrderRevision, record.binding)
    return ProposedOutcome.Apply(localSalesEncode(result, LocalFoodTableAssignmentResultV1.serializer(), 4_096),
        listOf(AggregateChange(foodTableAssignmentBindingKey(record.request.orderId), record.before?.revision,
            LocalFoodTableAssignmentProfileV1.schema(LocalFoodTableAssignmentProfileV1.BINDING), payload),
            AggregateChange(foodTableAssignmentVersionKey(record.operationId), null,
                LocalFoodTableAssignmentProfileV1.schema(LocalFoodTableAssignmentProfileV1.VERSION), payload))
            .sortedWith(compareBy({ it.key.kind }, { it.key.id.value })))
}

internal fun foodTableAssignmentReject(code: String): ProposedOutcome.Reject {
    check(code in LocalFoodTableAssignmentProfileV1.REJECTION_CODES)
    return ProposedOutcome.Reject(code, localSalesEncode(LocalFoodTableAssignmentResultV1(code, null, null, null),
        LocalFoodTableAssignmentResultV1.serializer(), 4_096))
}

internal fun foodTableAssignmentRecordBytes(record: FoodTableAssignmentRecordV1): StoredBytes =
    localSalesEncode(record, FoodTableAssignmentRecordV1.serializer(), LocalFoodTableAssignmentProfileV1.MAX_RECORD_BYTES)
internal fun foodTableAssignmentBindingKey(orderId: DomainId): AggregateKey =
    LocalFoodTableAssignmentProfileV1.key(LocalFoodTableAssignmentProfileV1.BINDING, orderId)
internal fun foodTableAssignmentVersionKey(operationId: OperationId): AggregateKey =
    LocalFoodTableAssignmentProfileV1.key(LocalFoodTableAssignmentProfileV1.VERSION, DomainId(operationId.value))
internal fun foodTableAssignmentDigest(bytes: StoredBytes): PayloadDigest {
    val raw = bytes.copyBytes()
    return try { sha256(raw) } finally { raw.fill(0) }
}

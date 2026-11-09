package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.StockCanonicalProjectionV2
import com.ukkera.pos.database.StockIndexIdentityV2
import com.ukkera.pos.database.StockIndexRecordV2
import com.ukkera.pos.database.StockRelationalKindsV2
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.StreamedProducerIdentityV2
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryAllocationRecord
import com.ukkera.pos.inventory.InventoryHoldRecord
import com.ukkera.pos.inventory.InventoryIssueSlice
import com.ukkera.pos.inventory.InventoryPoolProjection
import com.ukkera.pos.inventory.InventoryReservationHeader
import com.ukkera.pos.inventory.KitchenDisposition
import com.ukkera.pos.inventory.ReservationHold
import com.ukkera.pos.inventory.ReservationHoldReason
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact canonical per-ID codec; the projection is computed from actual decoded payload bytes. */
public object LocalStockRelationalCodecV2 : StockCanonicalProjectionV2 {
    override public fun project(scope: BranchScope, aggregate: StoredAggregate): StockIndexRecordV2? {
        if (aggregate.key.kind !in StockRelationalKindsV2.all) return null
        val record = decode(scope, aggregate.key, aggregate.revision, aggregate.schema, aggregate.payload, aggregate.digest)
        return projectRecord(scope, record)
    }

    internal fun projectRecord(scope: BranchScope, record: StockRelationalRecordV2): StockIndexRecordV2? {
        requireBody(scope, record.key, record.body)
        if (record.key.kind !in StockRelationalKindsV2.indexed) return null
        val identity = StockIndexIdentityV2(record.key, record.generation, record.producer.operationId, record.digest)
        return when (val body = record.body) {
            is StockRelationalBody.Position -> {
                val p = body.value; val owner = p.key.pool.ownership
                StockIndexRecordV2.Position(identity, p.key.pool.skuId, p.key.pool.warehouseId,
                    if (owner == StockOwnership.CompanyOwned) 1 else 2, (owner as? StockOwnership.SupplierOwned)?.supplierId,
                    stockV2CustodyTag(p.key.custody), p.key.lotId, p.key.serialId, p.expiresAtEpochMillis)
            }
            is StockRelationalBody.Reservation -> with(body.value) { StockIndexRecordV2.Reservation(identity, binding.orderId, binding.collectionId,
                binding.originalActorId, stockV2StatusTag(status), stockV2ExposureTag(binding.exposure), expiresAtEpochMillis) }
            is StockRelationalBody.Allocation -> with(body.value) { StockIndexRecordV2.Allocation(identity, reservationId,
                allocation.allocationId, allocation.lineId, LocalStockRelationalProfileV2.positionKey(allocation.position).id, 0) }
            is StockRelationalBody.Hold -> with(body.value) { StockIndexRecordV2.Allocation(identity, reservationId, null, null,
                hold.position?.let { LocalStockRelationalProfileV2.positionKey(it).id }, stockV2HoldTag(hold.reason)) }
            is StockRelationalBody.ValueEvent -> StockIndexRecordV2.Value(identity, LocalStockRelationalProfileV2.poolKey(body.pool).id,
                body.sourceId, null, body.issueId, null, null, body.ordinal)
            is StockRelationalBody.ValueSlice -> StockIndexRecordV2.Value(identity, LocalStockRelationalProfileV2.poolKey(body.pool).id,
                body.sourceId, body.eventId, body.issueId, body.allocationId, null, body.eventOrdinal)
            is StockRelationalBody.ValueTarget -> StockIndexRecordV2.Value(identity, LocalStockRelationalProfileV2.poolKey(body.pool).id,
                body.sourceId, body.originalEventId, null, null, null, body.eventOrdinal)
            is StockRelationalBody.TransferEdge -> StockIndexRecordV2.Value(identity, LocalStockRelationalProfileV2.poolKey(body.pool).id,
                body.sourceId, body.eventId, null, null, LocalStockRelationalProfileV2.poolKey(body.linkedPool).id, body.eventOrdinal)
            is StockRelationalBody.IssueSlice -> with(body.value) { StockIndexRecordV2.Value(identity,
                LocalStockRelationalProfileV2.poolKey(allocation.position.pool).id, issueId, null, issueId, allocation.allocationId, null, 0) }
            else -> throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        }
    }

    internal fun decode(scope: BranchScope, row: StoredAggregate): StockRelationalRecordV2 = decode(scope, row.key, row.revision, row.schema, row.payload, row.digest)

    internal fun decode(scope: BranchScope, key: AggregateKey, generation: Revision, schema: String, payload: StoredBytes, digest: PayloadDigest): StockRelationalRecordV2 = localStockWire {
        localStockCheck(key.kind in StockRelationalKindsV2.all && schema == StockRelationalKindsV2.schema(key.kind) && sha256(payload.copyBytes()) == digest,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val wire = stockDecode(payload, StockRecordV2Wire.serializer())
        localStockCheck(wire.format == 2 && wire.scope.restore() == scope && wire.kind == key.kind && wire.id == key.id && wire.generation == generation && generation.value >= 1,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val producer = StreamedProducerIdentityV2(wire.producer.operationId, wire.producer.commandKey(), wire.producer.actorId, PayloadDigest(wire.producer.digest))
        localStockCheck(if (generation == Revision(1)) wire.previous == null else wire.previous != null &&
            wire.previous.revision.next() == generation && wire.previous.operationId != producer.operationId, LocalStockFailure.STORAGE_INCONSISTENT)
        wire.previous?.let { PayloadDigest(it.digest) }
        val body = body(scope, wire.body)
        requireBody(scope, key, body)
        StockRelationalRecordV2(key, generation, schema, payload, digest, producer, wire.previous, body)
    }

    internal fun change(context: CommandContext, key: AggregateKey, previous: StockRelationalRecordV2?, body: StockRelationalBody): AggregateChange {
        localStockCheck(previous == null || previous.key == key, LocalStockFailure.STORAGE_INCONSISTENT)
        requireBody(context.scope, key, body)
        val wire = StockRecordV2Wire(2, StockScopeWire.from(context.scope), key.kind, key.id, previous?.generation?.next() ?: Revision(1),
            StockProducerWire.from(context), previous?.let { StockPreviousWire(it.producer.operationId, it.generation, it.digest.value) }, body(body))
        return AggregateChange(key, previous?.generation, StockRelationalKindsV2.schema(key.kind), stockEncode(wire, StockRecordV2Wire.serializer()))
    }

    internal fun body(value: StockRelationalBody): String = stockBody(toWire(value), StockBodyV2Wire.serializer())
    internal fun body(scope: BranchScope, bytes: String): StockRelationalBody = fromWire(scope, stockBody(bytes, StockBodyV2Wire.serializer()))

    private fun requireBody(scope: BranchScope, key: AggregateKey, body: StockRelationalBody) {
        localStockCheck(key.kind in StockRelationalKindsV2.all, LocalStockFailure.STORAGE_INCONSISTENT)
        when (body) {
            is StockRelationalBody.Item -> localStockCheck(body.scale in 0..6)
            is StockRelationalBody.Position -> {
                requirePoolScope(scope, body.value.key.pool)
                localStockCheck(body.value.key.lotId != null || body.value.expiresAtEpochMillis == null)
            }
            is StockRelationalBody.Pool -> requirePoolScope(scope, body.value.key)
            is StockRelationalBody.Reservation -> localStockCheck(body.allocationSetRevision.value >= 1)
            is StockRelationalBody.Allocation -> requirePoolScope(scope, body.value.allocation.position.pool)
            is StockRelationalBody.Hold -> body.value.hold.position?.let { requirePoolScope(scope, it.pool) }
            is StockRelationalBody.Issue -> Unit
            is StockRelationalBody.IssueSlice -> requirePoolScope(scope, body.value.allocation.position.pool)
            is StockRelationalBody.ValueEvent -> {
                requirePoolScope(scope, body.pool)
                localStockCheck(body.before.key == body.pool && body.after.key == body.pool && body.ordinal == body.after.lastEventOrdinal &&
                    body.before.lastEventOrdinal < Long.MAX_VALUE && body.ordinal == body.before.lastEventOrdinal + 1 &&
                    body.after.revision == body.before.revision.next() && body.after.onHand == body.before.onHand + body.quantityDelta &&
                    body.after.carryingValue == body.before.carryingValue + body.carryingValueDelta && body.sliceCount >= 0)
                if (body.event == StockValueEventKind.RESTORE) localStockCheck(body.issueId != null && body.issueId != body.sourceId &&
                    body.quantityDelta.scaled > 0 && body.originalValueDelta.minor >= 0 &&
                    body.carryingValueDelta == body.originalValueDelta && body.sliceCount > 0)
            }
            is StockRelationalBody.ValueSlice -> {
                requirePoolScope(scope, body.pool); localStockCheck(body.eventOrdinal >= 1 && body.quantity.scaled > 0 && body.originalValue.minor >= 0)
            }
            is StockRelationalBody.ValueTarget -> {
                requirePoolScope(scope, body.pool); localStockCheck(body.eventOrdinal >= 1 && body.originalValue.minor >= 0 && body.targetValue.minor >= 0)
            }
            is StockRelationalBody.TransferEdge -> {
                requirePoolScope(scope, body.pool); requirePoolScope(scope, body.linkedPool)
                localStockCheck(body.pool != body.linkedPool && body.pool.skuId == body.linkedPool.skuId && body.eventOrdinal >= 1 &&
                    body.quantity.scaled > 0 && body.originalValue.minor >= 0)
            }
            is StockRelationalBody.Posting -> localStockCheck(key.kind == StockRelationalKindsV2.POSTING && body.sourceCount >= 0 && body.effectCount >= 0 &&
                body.sourceBytes >= 0 && body.effectBytes >= 0)
            is StockRelationalBody.Activation -> localStockCheck(key == LocalStockRelationalProfileV2.activationKey(scope.branchId) &&
                stockActivationShape(body))
        }
        if (body !is StockRelationalBody.Posting && body !is StockRelationalBody.Activation)
            localStockCheck(stockKey(body) == key, LocalStockFailure.STORAGE_INCONSISTENT)
    }

    private fun requirePoolScope(scope: BranchScope, pool: StockPoolKey) {
        localStockCheck(pool.scope.companyId == scope.companyId && pool.scope.branchId == scope.branchId, LocalStockFailure.WRONG_SCOPE)
    }

    private fun toWire(body: StockRelationalBody): StockBodyV2Wire = when (body) {
        is StockRelationalBody.Item -> StockBodyV2Wire.Item(body.skuId, body.unitId, body.scale, body.serialized)
        is StockRelationalBody.Position -> StockBodyV2Wire.Position(PositionV2Wire.from(body.value))
        is StockRelationalBody.Pool -> StockBodyV2Wire.Pool(PoolV2Wire.from(body.value))
        is StockRelationalBody.Reservation -> StockBodyV2Wire.Reservation(ReservationV2Wire.from(body.value), body.allocationSetId, body.allocationSetRevision)
        is StockRelationalBody.Allocation -> StockBodyV2Wire.Allocation(body.value.reservationId, AllocationV2Wire.from(body.value.allocation))
        is StockRelationalBody.Hold -> StockBodyV2Wire.Hold(body.value.reservationId, stockV2HoldTag(body.value.hold.reason), body.value.hold.position?.let(PositionKeyV2Wire::from))
        is StockRelationalBody.Issue -> StockBodyV2Wire.Issue(IssueV2Wire.from(body.value))
        is StockRelationalBody.IssueSlice -> StockBodyV2Wire.IssueSlice(body.value.issueId, AllocationV2Wire.from(body.value.allocation), body.value.originalValue, body.value.restoredQuantity)
        is StockRelationalBody.ValueEvent -> StockBodyV2Wire.ValueEvent(StockPoolRefWire.from(body.pool), body.sourceId, body.ordinal, body.event.tag,
            PoolV2Wire.from(body.before), PoolV2Wire.from(body.after), body.quantityDelta, body.originalValueDelta, body.carryingValueDelta, body.sliceCount, body.issueId)
        is StockRelationalBody.ValueSlice -> StockBodyV2Wire.ValueSlice(StockPoolRefWire.from(body.pool), body.sourceId, body.eventId, body.eventOrdinal,
            body.issueId, body.allocationId, body.quantity, body.originalValue)
        is StockRelationalBody.ValueTarget -> StockBodyV2Wire.ValueTarget(StockPoolRefWire.from(body.pool), body.sourceId, body.originalEventId, body.eventOrdinal, body.originalValue, body.targetValue)
        is StockRelationalBody.TransferEdge -> StockBodyV2Wire.TransferEdge(StockPoolRefWire.from(body.pool), body.sourceId, body.eventId, body.eventOrdinal,
            StockPoolRefWire.from(body.linkedPool), body.linkedEventId, body.quantity, body.originalValue)
        is StockRelationalBody.Posting -> StockBodyV2Wire.Posting(body.sourceCount, body.sourceBytes, body.sourceCommitment.value,
            body.effectCount, body.effectBytes, body.effectCommitment.value, body.resultHash.value)
        is StockRelationalBody.Activation -> StockBodyV2Wire.Activation(body.migrationId, body.legacyAggregateCount, body.normalizedAggregateCount,
            body.legacyCommitment.value, body.sourceProfile, body.targetProfile)
    }

    private fun fromWire(scope: BranchScope, wire: StockBodyV2Wire): StockRelationalBody = when (wire) {
        is StockBodyV2Wire.Item -> StockRelationalBody.Item(wire.skuId, wire.unitId, wire.scale, wire.serialized)
        is StockBodyV2Wire.Position -> StockRelationalBody.Position(wire.value.restore(scope))
        is StockBodyV2Wire.Pool -> StockRelationalBody.Pool(wire.value.restore(scope))
        is StockBodyV2Wire.Reservation -> StockRelationalBody.Reservation(wire.value.restore(), wire.allocationSetId, wire.allocationSetRevision)
        is StockBodyV2Wire.Allocation -> StockRelationalBody.Allocation(InventoryAllocationRecord(wire.reservationId, wire.value.restore(scope)))
        is StockBodyV2Wire.Hold -> StockRelationalBody.Hold(InventoryHoldRecord(wire.reservationId, ReservationHold(stockV2Hold(wire.reason), wire.position?.restore(scope))))
        is StockBodyV2Wire.Issue -> StockRelationalBody.Issue(wire.value.restore())
        is StockBodyV2Wire.IssueSlice -> StockRelationalBody.IssueSlice(InventoryIssueSlice(wire.issueId, wire.allocation.restore(scope), wire.originalValue, wire.restoredQuantity))
        is StockBodyV2Wire.ValueEvent -> StockRelationalBody.ValueEvent(wire.pool.key(scope), wire.sourceId, wire.ordinal, StockValueEventKind.fromTag(wire.event),
            wire.before.restore(scope), wire.after.restore(scope), wire.quantityDelta, wire.originalValueDelta, wire.carryingValueDelta, wire.sliceCount, wire.issueId)
        is StockBodyV2Wire.ValueSlice -> StockRelationalBody.ValueSlice(wire.pool.key(scope), wire.sourceId, wire.eventId, wire.eventOrdinal,
            wire.issueId, wire.allocationId, wire.quantity, wire.originalValue)
        is StockBodyV2Wire.ValueTarget -> StockRelationalBody.ValueTarget(wire.pool.key(scope), wire.sourceId, wire.originalEventId, wire.eventOrdinal, wire.originalValue, wire.targetValue)
        is StockBodyV2Wire.TransferEdge -> StockRelationalBody.TransferEdge(wire.pool.key(scope), wire.sourceId, wire.eventId, wire.eventOrdinal,
            wire.linkedPool.key(scope), wire.linkedEventId, wire.quantity, wire.originalValue)
        is StockBodyV2Wire.Posting -> StockRelationalBody.Posting(wire.sourceCount, wire.sourceBytes, PayloadDigest(wire.sourceCommitment),
            wire.effectCount, wire.effectBytes, PayloadDigest(wire.effectCommitment), PayloadDigest(wire.resultHash))
        is StockBodyV2Wire.Activation -> StockRelationalBody.Activation(wire.migrationId, wire.legacyAggregateCount, wire.normalizedAggregateCount,
            PayloadDigest(wire.legacyCommitment), wire.sourceProfile, wire.targetProfile)
    }
}

internal class StockRelationalRecordV2(val key: AggregateKey, val generation: Revision, val schema: String, val payload: StoredBytes,
    val digest: PayloadDigest, val producer: StreamedProducerIdentityV2, val previous: StockPreviousWire?, val body: StockRelationalBody)

@Serializable internal data class StockRecordV2Wire(val format: Int, val scope: StockScopeWire, val kind: String, val id: DomainId,
    val generation: Revision, val producer: StockProducerWire, val previous: StockPreviousWire?, val body: String)

@Serializable internal data class PositionKeyV2Wire(val skuId: DomainId, val warehouseId: DomainId, val ownership: Int, val ownerId: DomainId?,
    val custody: Int, val lotId: DomainId?, val serialId: DomainId?) {
    fun restore(scope: BranchScope): StockPositionKey {
        localStockCheck(ownership in 1..2 && (ownership == 1) == (ownerId == null))
        return LocalStockPositionRef(skuId, warehouseId, lotId, stockV2Custody(custody), ownerId, serialId).inScope(scope)
    }
    companion object { fun from(key: StockPositionKey): PositionKeyV2Wire = PositionKeyV2Wire(key.pool.skuId, key.pool.warehouseId,
        if (key.pool.ownership == StockOwnership.CompanyOwned) 1 else 2, (key.pool.ownership as? StockOwnership.SupplierOwned)?.supplierId,
        stockV2CustodyTag(key.custody), key.lotId, key.serialId) }
}
@Serializable internal data class PositionV2Wire(val key: PositionKeyV2Wire, val onHand: Quantity, val safety: Int,
    @Serializable(with = MinorUnitsSerializer::class) val expiryMillis: Long?, val serialRegistrationId: DomainId?) {
    fun restore(scope: BranchScope): StockPosition = StockPosition(key.restore(scope), onHand, stockV2Safety(safety), expiryMillis, serialRegistrationId)
    companion object { fun from(value: StockPosition): PositionV2Wire = PositionV2Wire(PositionKeyV2Wire.from(value.key), value.onHand,
        stockV2SafetyTag(value.safety), value.expiresAtEpochMillis, value.serialRegistrationId) }
}
@Serializable internal data class PoolV2Wire(val key: StockPoolRefWire, val revision: Revision, val onHand: Quantity, val value: Money,
    @Serializable(with = MinorUnitsSerializer::class) val ordinal: Long) {
    fun restore(scope: BranchScope): InventoryPoolProjection = InventoryPoolProjection(key.key(scope), revision, onHand, value, ordinal)
    companion object { fun from(value: InventoryPoolProjection): PoolV2Wire = PoolV2Wire(StockPoolRefWire.from(value.key), value.revision, value.onHand, value.carryingValue, value.lastEventOrdinal) }
}
@Serializable internal data class BindingV2Wire(val orderId: DomainId, val quoteRevision: Revision, val collectionId: DomainId, val actorId: DomainId, val exposure: Int) {
    fun restore(): CollectionBinding = CollectionBinding(orderId, quoteRevision, collectionId, actorId, stockV2Exposure(exposure))
    companion object { fun from(value: CollectionBinding): BindingV2Wire = BindingV2Wire(value.orderId, value.quoteRevision, value.collectionId, value.originalActorId, stockV2ExposureTag(value.exposure)) }
}
@Serializable internal data class ReservationV2Wire(val id: DomainId, val binding: BindingV2Wire, val revision: Revision, val status: Int,
    @Serializable(with = MinorUnitsSerializer::class) val expiryMillis: Long?,
    @Serializable(with = MinorUnitsSerializer::class) val allocationCount: Long,
    @Serializable(with = MinorUnitsSerializer::class) val holdCount: Long, val issueId: DomainId?) {
    fun restore(): InventoryReservationHeader = InventoryReservationHeader(id, binding.restore(), revision, stockV2Status(status), expiryMillis, allocationCount, holdCount, issueId)
    companion object { fun from(value: InventoryReservationHeader): ReservationV2Wire = ReservationV2Wire(value.id, BindingV2Wire.from(value.binding), value.revision,
        stockV2StatusTag(value.status), value.expiresAtEpochMillis, value.allocationCount, value.holdCount, value.issueId) }
}
@Serializable internal data class AllocationV2Wire(val id: DomainId, val lineId: DomainId, val position: PositionKeyV2Wire, val quantity: Quantity) {
    fun restore(scope: BranchScope): StockAllocation = StockAllocation(id, lineId, position.restore(scope), quantity)
    companion object { fun from(value: StockAllocation): AllocationV2Wire = AllocationV2Wire(value.allocationId, value.lineId, PositionKeyV2Wire.from(value.position), value.quantity) }
}
@Serializable internal data class IssueV2Wire(val id: DomainId, val reservationId: DomainId, val binding: BindingV2Wire, val purpose: Int, val revision: Revision,
    @Serializable(with = MinorUnitsSerializer::class) val allocationCount: Long, val originalValue: Money, val recipeSnapshotId: DomainId?,
    val requiredStationIds: List<DomainId>, val startedStationIds: List<DomainId>, val disposition: Int?) {
    fun restore(): LocalStockIssueHeader = LocalStockIssueHeader(id, reservationId, binding.restore(), stockV2Purpose(purpose), revision, allocationCount,
        originalValue, recipeSnapshotId, requiredStationIds, startedStationIds, disposition?.let(::stockV2Kitchen))
    companion object { fun from(value: LocalStockIssueHeader): IssueV2Wire = IssueV2Wire(value.id, value.reservationId, BindingV2Wire.from(value.binding), stockV2PurposeTag(value.purpose),
        value.revision, value.allocationCount, value.originalValue, value.recipeSnapshotId, value.requiredStationIds, value.startedStationIds, value.disposition?.let(::stockV2KitchenTag)) }
}

@Serializable internal sealed interface StockBodyV2Wire {
    @Serializable @SerialName("item") data class Item(val skuId: DomainId, val unitId: DomainId, val scale: Int, val serialized: Boolean) : StockBodyV2Wire
    @Serializable @SerialName("position") data class Position(val value: PositionV2Wire) : StockBodyV2Wire
    @Serializable @SerialName("pool") data class Pool(val value: PoolV2Wire) : StockBodyV2Wire
    @Serializable @SerialName("reservation") data class Reservation(val value: ReservationV2Wire, val allocationSetId: DomainId, val allocationSetRevision: Revision) : StockBodyV2Wire
    @Serializable @SerialName("allocation") data class Allocation(val reservationId: DomainId, val value: AllocationV2Wire) : StockBodyV2Wire
    @Serializable @SerialName("hold") data class Hold(val reservationId: DomainId, val reason: Int, val position: PositionKeyV2Wire?) : StockBodyV2Wire
    @Serializable @SerialName("issue") data class Issue(val value: IssueV2Wire) : StockBodyV2Wire
    @Serializable @SerialName("issue-slice") data class IssueSlice(val issueId: DomainId, val allocation: AllocationV2Wire, val originalValue: Money, val restoredQuantity: Quantity) : StockBodyV2Wire
    @Serializable @SerialName("value-event") data class ValueEvent(val pool: StockPoolRefWire, val sourceId: DomainId,
        @Serializable(with = MinorUnitsSerializer::class) val ordinal: Long, val event: Int, val before: PoolV2Wire, val after: PoolV2Wire,
        val quantityDelta: Quantity, val originalValueDelta: Money, val carryingValueDelta: Money,
        @Serializable(with = MinorUnitsSerializer::class) val sliceCount: Long, val issueId: DomainId?) : StockBodyV2Wire
    @Serializable @SerialName("value-slice") data class ValueSlice(val pool: StockPoolRefWire, val sourceId: DomainId, val eventId: DomainId,
        @Serializable(with = MinorUnitsSerializer::class) val eventOrdinal: Long, val issueId: DomainId?, val allocationId: DomainId, val quantity: Quantity, val originalValue: Money) : StockBodyV2Wire
    @Serializable @SerialName("value-target") data class ValueTarget(val pool: StockPoolRefWire, val sourceId: DomainId, val originalEventId: DomainId,
        @Serializable(with = MinorUnitsSerializer::class) val eventOrdinal: Long, val originalValue: Money, val targetValue: Money) : StockBodyV2Wire
    @Serializable @SerialName("transfer-edge") data class TransferEdge(val pool: StockPoolRefWire, val sourceId: DomainId, val eventId: DomainId,
        @Serializable(with = MinorUnitsSerializer::class) val eventOrdinal: Long, val linkedPool: StockPoolRefWire, val linkedEventId: DomainId, val quantity: Quantity, val originalValue: Money) : StockBodyV2Wire
    @Serializable @SerialName("posting") data class Posting(
        @Serializable(with = MinorUnitsSerializer::class) val sourceCount: Long,
        @Serializable(with = MinorUnitsSerializer::class) val sourceBytes: Long, val sourceCommitment: String,
        @Serializable(with = MinorUnitsSerializer::class) val effectCount: Long,
        @Serializable(with = MinorUnitsSerializer::class) val effectBytes: Long, val effectCommitment: String, val resultHash: String) : StockBodyV2Wire
    @Serializable @SerialName("activation") data class Activation(val migrationId: DomainId,
        @Serializable(with = MinorUnitsSerializer::class) val legacyAggregateCount: Long,
        @Serializable(with = MinorUnitsSerializer::class) val normalizedAggregateCount: Long, val legacyCommitment: String, val sourceProfile: String, val targetProfile: String) : StockBodyV2Wire
}

internal fun stockV2StatusTag(value: ReservationStatus): Int = when (value) { ReservationStatus.RESERVED -> 1; ReservationStatus.HELD -> 2; ReservationStatus.ISSUED -> 3; ReservationStatus.RELEASED -> 4 }
internal fun stockV2Status(tag: Int): ReservationStatus = when (tag) { 1 -> ReservationStatus.RESERVED; 2 -> ReservationStatus.HELD; 3 -> ReservationStatus.ISSUED; 4 -> ReservationStatus.RELEASED; else -> badStockV2Tag() }
internal fun stockV2ExposureTag(value: CollectionExposure): Int = when (value) { CollectionExposure.UNCOLLECTED -> 1; CollectionExposure.PART_PAID -> 2; CollectionExposure.PAID -> 3; CollectionExposure.UNKNOWN -> 4 }
internal fun stockV2Exposure(tag: Int): CollectionExposure = when (tag) { 1 -> CollectionExposure.UNCOLLECTED; 2 -> CollectionExposure.PART_PAID; 3 -> CollectionExposure.PAID; 4 -> CollectionExposure.UNKNOWN; else -> badStockV2Tag() }
internal fun stockV2HoldTag(value: ReservationHoldReason): Int = when (value) { ReservationHoldReason.RECALL -> 1; ReservationHoldReason.EXPIRY -> 2; ReservationHoldReason.COUNT_SHORTAGE -> 3; ReservationHoldReason.RESERVATION_EXPIRED -> 4; ReservationHoldReason.UNAVAILABLE -> 5 }
internal fun stockV2Hold(tag: Int): ReservationHoldReason = when (tag) { 1 -> ReservationHoldReason.RECALL; 2 -> ReservationHoldReason.EXPIRY; 3 -> ReservationHoldReason.COUNT_SHORTAGE; 4 -> ReservationHoldReason.RESERVATION_EXPIRED; 5 -> ReservationHoldReason.UNAVAILABLE; else -> badStockV2Tag() }
internal fun stockV2CustodyTag(value: StockCustody): Int = when (value) { StockCustody.RELEASED -> 1; StockCustody.QUARANTINE -> 2; StockCustody.REJECTED_HELD -> 3 }
internal fun stockV2Custody(tag: Int): StockCustody = when (tag) { 1 -> StockCustody.RELEASED; 2 -> StockCustody.QUARANTINE; 3 -> StockCustody.REJECTED_HELD; else -> badStockV2Tag() }
internal fun stockV2SafetyTag(value: StockSafety): Int = when (value) { StockSafety.CLEAR -> 1; StockSafety.RECALLED -> 2; StockSafety.EXPIRED -> 3; StockSafety.COUNT_REVIEW -> 4 }
internal fun stockV2Safety(tag: Int): StockSafety = when (tag) { 1 -> StockSafety.CLEAR; 2 -> StockSafety.RECALLED; 3 -> StockSafety.EXPIRED; 4 -> StockSafety.COUNT_REVIEW; else -> badStockV2Tag() }
internal fun stockV2PurposeTag(value: ValueIssuePurpose): Int = when (value) { ValueIssuePurpose.RETAIL -> 1; ValueIssuePurpose.DIRECT_RECIPE -> 2; else -> badStockV2Tag() }
internal fun stockV2Purpose(tag: Int): ValueIssuePurpose = when (tag) { 1 -> ValueIssuePurpose.RETAIL; 2 -> ValueIssuePurpose.DIRECT_RECIPE; else -> badStockV2Tag() }
internal fun stockV2KitchenTag(value: KitchenDisposition): Int = when (value) { KitchenDisposition.ACTIVE -> 1; KitchenDisposition.CANCELLED_BEFORE_START -> 2; KitchenDisposition.WASTE_AFTER_START -> 3 }
internal fun stockV2Kitchen(tag: Int): KitchenDisposition = when (tag) { 1 -> KitchenDisposition.ACTIVE; 2 -> KitchenDisposition.CANCELLED_BEFORE_START; 3 -> KitchenDisposition.WASTE_AFTER_START; else -> badStockV2Tag() }
private fun badStockV2Tag(): Nothing = throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)

package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.inventory.InventoryPostedEffectKind
import com.ukkera.pos.inventory.InventoryPostedRecords
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.LinkedMovingAveragePlanner
import com.ukkera.pos.inventory.OwnedTransferValueLink
import com.ukkera.pos.inventory.ReservationHold
import com.ukkera.pos.inventory.ReservationHoldReason
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** Permanent single-child value edge. Pool scope is the exact transfer record envelope scope. */
@Serializable internal data class StockOwnedTransferValueWire(
    val calculationVersion: String,
    val transferId: DomainId,
    val allocationId: DomainId,
    val sourcePool: StockPoolRefWire,
    val destinationPool: StockPoolRefWire,
    val sourceEventId: DomainId,
    val destinationEventId: DomainId,
    val sourceRevision: Revision,
    val destinationRevision: Revision,
    val quantity: Quantity,
    val originalValue: Money,
) {
    companion object {
        fun from(link: OwnedTransferValueLink): StockOwnedTransferValueWire = StockOwnedTransferValueWire(
            LinkedMovingAveragePlanner.CALCULATION_VERSION, link.transferId, link.allocationId,
            StockPoolRefWire.from(link.sourcePool), StockPoolRefWire.from(link.destinationPool),
            link.sourceEventId, link.destinationEventId, link.sourceRevision, link.destinationRevision,
            link.quantity, link.originalValue)
    }
}

/** THEN-active branch warehouse authority, not a transferable approval or today's permission. */
@Serializable internal data class StockTransferWarehouseWire(
    val scope: StockScopeWire, val warehouseId: DomainId, val active: Boolean, val sources: List<StockSourceStampWire>,
) {
    companion object {
        fun from(value: LocalStockWarehouseAuthority): StockTransferWarehouseWire = StockTransferWarehouseWire(
            StockScopeWire.from(value.scope), value.warehouseId, value.active,
            value.sources.sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value }).map(StockSourceStampWire::from))
    }
}

@Serializable internal data class StockOwnedTransferWire(
    val value: StockOwnedTransferValueWire,
    val sourceWarehouse: StockTransferWarehouseWire,
    val destinationWarehouse: StockTransferWarehouseWire,
)

/** Typed closed producer shape; full retained before/after correspondence is checked separately. */
internal fun requireOwnedTransferProducer(
    evidence: RetainedCommandEvidence, request: LocalWarehouseTransferRequest,
    transfer: StockTransferWire, records: InventoryPostedRecords,
) {
    val owned = transfer.owned ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(evidence.command == LocalStockStoreProfile.TRANSFER_OWNED && request.source.supplierId == null &&
        request.source.custody == StockCustody.RELEASED && transfer.request == StockTransferRequestWire.from(request) &&
        transfer.transferredBy == evidence.originalActorId && transfer.recordedAt == evidence.recordedAt,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val source = request.source.inScope(evidence.scope)
    val destination = source.copy(pool = source.pool.copy(warehouseId = request.destinationWarehouseId))
    localStockCheck(records.valuations.size == 2 && records.movements.size == 2 && records.counts.isEmpty() &&
        records.issues.isEmpty() && records.serialRegistrations.isEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
    val outgoing = records.valuations[0]
    val incoming = records.valuations[1]
    val out = outgoing.fact as? ValuationFact.TransferOut ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    val into = incoming.fact as? ValuationFact.TransferIn ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    val link = out.link
    localStockCheck(into.link == link && owned.value == StockOwnedTransferValueWire.from(link) &&
        link.transferId == request.transferId && link.allocationId == request.transferId &&
        link.sourcePool == source.pool && link.destinationPool == destination.pool &&
        link.sourceEventId == request.sourceEventId && link.destinationEventId == request.destinationEventId &&
        link.quantity == request.quantity && transfer.originalValue == link.originalValue &&
        link.originalValue.currency == Currency.EGP && link.originalValue.minor >= 0 &&
        outgoing.pool == source.pool && incoming.pool == destination.pool &&
        outgoing.requiredRevision == link.sourceRevision && incoming.requiredRevision == link.destinationRevision &&
        outgoing.quantityDelta == -request.quantity && incoming.quantityDelta == request.quantity &&
        outgoing.originalValueDelta == -link.originalValue && incoming.originalValueDelta == link.originalValue &&
        outgoing.linkedAdjustment == null && incoming.linkedAdjustment == null,
        LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(records.movements[0].position == source && records.movements[0].sourceId == request.sourceEventId &&
        records.movements[0].quantityDelta == -request.quantity && records.movements[1].position == destination &&
        records.movements[1].sourceId == request.destinationEventId && records.movements[1].quantityDelta == request.quantity &&
        records.movements.all { it.reason == StockMovementReason.WAREHOUSE_TRANSFER && it.sourceAllocationId == request.transferId },
        LocalStockFailure.STORAGE_INCONSISTENT)
    val pairedKinds = listOf(InventoryPostedEffectKind.VALUATION, InventoryPostedEffectKind.MOVEMENT,
        InventoryPostedEffectKind.VALUATION, InventoryPostedEffectKind.MOVEMENT)
    localStockCheck(records.effectKinds.takeLast(4) == pairedKinds &&
        records.effectKinds.dropLast(4).all { it == InventoryPostedEffectKind.SAFETY || it == InventoryPostedEffectKind.RESERVATION } &&
        records.safety.map { it.first.key }.distinct().size == records.safety.size &&
        records.safety.all { (before, after) -> before.safety == StockSafety.CLEAR &&
            before.expiresAtEpochMillis?.let { it <= evidence.recordedAt.value.toEpochMilliseconds() } == true &&
            after == before.copy(safety = StockSafety.EXPIRED) } &&
        records.reservations.all { it.status == ReservationStatus.HELD }, LocalStockFailure.STORAGE_INCONSISTENT)
    requireTransferWarehouse(owned.sourceWarehouse, evidence.scope, request.source.warehouseId)
    requireTransferWarehouse(owned.destinationWarehouse, evidence.scope, request.destinationWarehouseId)
    localStockCheck((owned.sourceWarehouse.sources + owned.destinationWarehouse.sources).groupBy { it.restore().key }
        .values.all { it.distinct().size == 1 }, LocalStockFailure.STORAGE_INCONSISTENT)
    if (source.serialId != null) localStockCheck(request.quantity == stockSerialUnit(request.quantity), LocalStockFailure.STORAGE_INCONSISTENT)
}

private fun requireTransferWarehouse(value: StockTransferWarehouseWire, scope: BranchScope, id: DomainId) {
    localStockCheck(value.scope.restore() == scope && value.warehouseId == id && value.active && value.sources.size in 1..16 &&
        value.sources.map { it.restore().key }.distinct().size == value.sources.size &&
        value.sources == value.sources.sortedWith(compareBy<StockSourceStampWire> { it.kind }.thenBy { it.id.value }),
        LocalStockFailure.STORAGE_INCONSISTENT)
    value.sources.forEach { it.restore() }
}

/** Exact physical/claim preservation and pool frontier, without replaying a business command. */
internal fun requireOwnedTransferSuccessors(
    evidence: RetainedCommandEvidence,
    observed: Map<AggregateKey, StockHistoricalRecord>,
    after: Map<AggregateKey, StockHistoricalRecord>,
    records: InventoryPostedRecords,
) {
    val request = LocalStockCommandCodec.transfer(evidence.normalized)
    val transfer = ownedTransferBody(after, AggregateKey(LocalStockStoreProfile.TRANSFER_DOCUMENT, request.transferId), StockTransferWire.serializer())
    requireOwnedTransferProducer(evidence, request, transfer, records)
    val positions = observed.filterKeys { it.kind == LocalStockStoreProfile.POSITION }.values.map { row ->
        val value = historicalBody(row, StockPositionWire.serializer())
        InventoryRecordCodec.decodePosition(value.encoded, value.position.key(evidence.scope))
    }
    val scope = InventoryScope(evidence.scope.companyId, evidence.scope.branchId)
    val reservations = observed.filterKeys { it.kind == LocalStockStoreProfile.RESERVATION }.map { (key, row) ->
        val value = historicalBody(row, StockReservationWire.serializer())
        InventoryRecordCodec.decodeReservation(value.encoded, scope, key.id, value.revision)
    }
    val expired = positions.filter { it.safety == StockSafety.CLEAR &&
        it.expiresAtEpochMillis?.let { expiry -> expiry <= evidence.recordedAt.value.toEpochMilliseconds() } == true }
    localStockCheck(records.safety.map { it.first }.toSet() == expired.toSet(), LocalStockFailure.STORAGE_INCONSISTENT)
    val currentClaims = reservations.associateBy { it.reservationId }.toMutableMap()
    var reservationOffset = 0
    val prefixKinds = mutableListOf<InventoryPostedEffectKind>()
    for ((before, _) in records.safety) {
        prefixKinds += InventoryPostedEffectKind.SAFETY
        val active = currentClaims.values.filter { it.retainsStockClaim && it.allocations.any { row -> row.position == before.key } }
        val steps = records.reservations.drop(reservationOffset).take(active.size)
        localStockCheck(steps.size == active.size && steps.map { it.reservationId }.toSet() == active.map { it.reservationId }.toSet(),
            LocalStockFailure.STORAGE_INCONSISTENT)
        for (step in steps) {
            requireTransferredHold(currentClaims.getValue(step.reservationId), step, before)
            currentClaims[step.reservationId] = step
            prefixKinds += InventoryPostedEffectKind.RESERVATION
        }
        reservationOffset += active.size
    }
    localStockCheck(reservationOffset == records.reservations.size && records.effectKinds.dropLast(4) == prefixKinds,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val key = request.source.inScope(evidence.scope)
    val original = positions.singleOrNull { it.key == key } ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    val source = records.safety.singleOrNull { it.first.key == key }?.second ?: original
    val zero = Quantity(source.onHand.unitId, 0, source.onHand.scale)
    val claimed = reservations.filter { it.retainsStockClaim }.flatMap { it.allocations }.filter { it.position == key }
        .fold(zero) { sum, row -> sum + row.quantity }
    localStockCheck(claimed <= source.onHand && request.quantity <= source.onHand - claimed, LocalStockFailure.STORAGE_INCONSISTENT)
    val sourceAfter = ownedTransferPosition(after, LocalStockStoreProfile.positionKey(key), evidence.scope)
    localStockCheck(sourceAfter == source.copy(onHand = source.onHand - request.quantity), LocalStockFailure.STORAGE_INCONSISTENT)
    val destinationKey = key.copy(pool = key.pool.copy(warehouseId = request.destinationWarehouseId))
    val priorDestination = positions.singleOrNull { it.key == destinationKey }?.let { position ->
        records.safety.singleOrNull { it.first.key == destinationKey }?.second ?: position
    }
    localStockCheck(priorDestination == null || priorDestination.safety == source.safety &&
        priorDestination.expiresAtEpochMillis == source.expiresAtEpochMillis && priorDestination.serialRegistrationId == source.serialRegistrationId,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val destination = ownedTransferPosition(after, LocalStockStoreProfile.positionKey(destinationKey), evidence.scope)
    localStockCheck(destination == StockPosition(destinationKey, (priorDestination?.onHand ?: zero) + request.quantity,
        source.safety, source.expiresAtEpochMillis, source.serialRegistrationId), LocalStockFailure.STORAGE_INCONSISTENT)
    val link = requireNotNull(transfer.owned).value
    val sourcePool = ownedTransferBody(observed, LocalStockStoreProfile.poolKey(key.pool), StockPoolWire.serializer())
    val destinationPool = observed[LocalStockStoreProfile.poolKey(destinationKey.pool)]?.let { historicalBody(it, StockPoolWire.serializer()) }
    localStockCheck(link.sourceRevision == sourcePool.revision && link.destinationRevision == (destinationPool?.revision ?: Revision(0)) &&
        (destinationPool != null || positions.none { it.key.pool == destinationKey.pool }), LocalStockFailure.STORAGE_INCONSISTENT)
    // The generic predecessor fold separately checks exact original carrying-value removal,
    // matching incoming value, complete graph histories, and immutable serial-origin rows.
}

private fun requireTransferredHold(before: StockReservation, after: StockReservation, position: StockPosition) {
    val hold = ReservationHold(ReservationHoldReason.EXPIRY, position.key)
    localStockCheck(after.reservationId == before.reservationId && after.status == ReservationStatus.HELD &&
        after.revision == before.revision.next() && after.binding == before.binding && after.allocations == before.allocations &&
        after.expiresAtEpochMillis == before.expiresAtEpochMillis && after.holds == (before.holds + hold).distinct(),
        LocalStockFailure.STORAGE_INCONSISTENT)
}

private fun ownedTransferPosition(rows: Map<AggregateKey, StockHistoricalRecord>, key: AggregateKey, scope: BranchScope): StockPosition {
    val wire = ownedTransferBody(rows, key, StockPositionWire.serializer())
    localStockCheck(LocalStockStoreProfile.positionKey(wire.position.key(scope)) == key, LocalStockFailure.STORAGE_INCONSISTENT)
    return InventoryRecordCodec.decodePosition(wire.encoded, wire.position.key(scope))
}

private fun <T> ownedTransferBody(rows: Map<AggregateKey, StockHistoricalRecord>, key: AggregateKey, serializer: kotlinx.serialization.KSerializer<T>): T =
    historicalBody(rows[key] ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT), serializer)

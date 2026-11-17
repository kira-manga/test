package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

/** Original factual definition from signed native BEFORE SKU/warehouse rows; not a grant. */
public data class LocalStockHistoricalAllocationDefinition(
    val skuId: DomainId, val warehouseId: DomainId, val unitId: DomainId,
    val scale: Int, val lotTracked: Boolean, val requiresExpiry: Boolean,
)

/** Exact first-insufficiency replay over the complete native BEFORE ranges. No current
 * view, historical authorization or writable spool is manufactured. Every source is
 * paired to its real fully joined original and canonical index. Unknown evidence throws,
 * never becoming a stock refusal. Only the existing bounded256 logical demands are held. */
public suspend fun requireNativeRetailInsufficiency(command: RuntimeHistoricalCommand,
    demand: LocalStockReservationDemand, definitions: List<LocalStockHistoricalAllocationDefinition>): Boolean {
    check(command.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND &&
        demand.lines.size in 1..256 && definitions.size == demand.lines.size &&
        demand.lines.map { it.lineId }.distinct().size == demand.lines.size)
    val before = NativeReservationBefore(command)
    check(before.record(LocalStockRelationalProfileV2.reservationKey(demand.reservationId)) == null &&
        command.claim(LocalStockStoreProfile.reservationClaim(demand.reservationId)) == null)
    // All prior demands on the same SKU/warehouse consume the SAME original FEFO
    // sequence. Recompute their prefix per line instead of storing physical selections.
    val priorDemand = mutableMapOf<Pair<DomainId, DomainId>, Quantity>()
    for ((ordinal, line) in demand.lines.withIndex()) {
        val definition = definitions[ordinal]
        check(definition.skuId == line.skuId && definition.warehouseId == line.warehouseId &&
            definition.unitId == line.baseQuantity.unitId && definition.scale == line.baseQuantity.scale &&
            line.baseQuantity.scaled > 0)
        val item = before.record(LocalStockRelationalProfileV2.itemKey(line.skuId))?.body as? StockRelationalBody.Item
            ?: error("stock.native_refusal_item_missing")
        check(item.skuId == line.skuId && item.unitId == definition.unitId && item.scale == definition.scale && !item.serialized)
        val group = line.skuId to line.warehouseId
        val zero = Quantity(line.baseQuantity.unitId, 0, line.baseQuantity.scale)
        val earlier = priorDemand[group] ?: zero
        var prefix = earlier
        var remaining = line.baseQuantity
        command.forEachRetailStockBefore(StockQueryV2.Positions(line.skuId, line.warehouseId)) { _, source ->
            val position = (before.decode(source).body as? StockRelationalBody.Position)?.value
                ?: error("stock.native_refusal_position_kind")
            check(position.key.pool.skuId == line.skuId && position.key.pool.warehouseId == line.warehouseId &&
                position.onHand.unitId == definition.unitId && position.onHand.scale == definition.scale &&
                position.key.serialId == null && (!definition.lotTracked || position.key.lotId != null) &&
                (!definition.requiresExpiry || position.expiresAtEpochMillis != null))
            if (remaining.scaled > 0 && position.isSellableAt(command.recordedAt.value.toEpochMilliseconds())) {
                var held = zero
                var allHeld = true
                command.forEachRetailStockBefore(StockQueryV2.PositionMembers(
                    LocalStockRelationalProfileV2.positionKey(position.key).id)) { _, member ->
                    when (val body = before.decode(member).body) {
                        is StockRelationalBody.Allocation -> {
                            val allocation = body.value
                            check(allocation.allocation.position == position.key)
                            val reservation = before.reservation(allocation.reservationId)
                            before.requireMembership(reservation)
                            check(allocation.allocation.allocationId == LocalStockRelationalProfileV2.allocationId(
                                reservation.allocationSetId, reservation.allocationSetRevision,
                                allocation.allocation.lineId, position.key) &&
                                command.claim(LocalStockStoreProfile.allocationClaim(allocation.allocation.allocationId)) == reservation.value.id)
                            if (reservation.value.retainsStockClaim) {
                                held += allocation.allocation.quantity
                                allHeld = allHeld && reservation.value.status == ReservationStatus.HELD
                            }
                        }
                        is StockRelationalBody.Hold -> check(body.value.hold.position == position.key)
                        else -> error("stock.native_refusal_member_kind")
                    }
                }
                check(held <= position.onHand || allHeld)
                val available = InventoryPositionAvailability(position, held)
                val free = InventoryRelationalPlanner.available(available, command.recordedAt.value.toEpochMilliseconds())
                val previous = minOf(prefix, free)
                prefix -= previous
                val quantity = InventoryRelationalPlanner.select(available, previous, remaining,
                    command.recordedAt.value.toEpochMilliseconds())
                if (quantity.scaled > 0) {
                    val id = LocalStockRelationalProfileV2.allocationId(demand.allocationSetId,
                        demand.allocationSetRevision, line.lineId, position.key)
                    check(command.claim(LocalStockStoreProfile.allocationClaim(id)) == null &&
                        before.record(LocalStockRelationalProfileV2.allocationKey(id)) == null)
                    remaining -= quantity
                }
            }
        }
        check(prefix.scaled == 0L)
        if (remaining.scaled != 0L) return true
        priorDemand[group] = earlier + line.baseQuantity
    }
    return false // Later domain/encoding outcomes are not inferred from the outer label.
}

private class NativeReservationBefore(private val command: RuntimeHistoricalCommand) {
    suspend fun record(key: AggregateKey): StockRelationalRecordV2? = command.aggregateSource(key)?.let(::decode)

    fun decode(selected: RuntimeHistoricalAggregateSource): StockRelationalRecordV2 {
        val decoded = LocalStockRelationalCodecV2.decode(command.scope, selected.row)
        val producer = (selected.original as? RetainedOriginalV2.Streamed)?.evidence?.identity?.producer
            ?: error("stock.native_refusal_streamed_source_required")
        check(decoded.producer == producer)
        return decoded
    }

    suspend fun reservation(id: DomainId): StockRelationalBody.Reservation {
        val body = record(LocalStockRelationalProfileV2.reservationKey(id))?.body as? StockRelationalBody.Reservation
            ?: error("stock.native_refusal_reservation_missing")
        check(body.value.id == id && command.claim(LocalStockStoreProfile.reservationClaim(id)) == id)
        return body
    }

    suspend fun requireMembership(reservation: StockRelationalBody.Reservation) {
        var allocations = 0L
        var holds = 0L
        command.forEachRetailStockBefore(StockQueryV2.ReservationMembers(reservation.value.id)) { _, source ->
            when (val body = decode(source).body) {
                is StockRelationalBody.Allocation -> {
                    check(body.value.reservationId == reservation.value.id &&
                        command.claim(LocalStockStoreProfile.allocationClaim(body.value.allocation.allocationId)) == reservation.value.id)
                    allocations++
                }
                is StockRelationalBody.Hold -> { check(body.value.reservationId == reservation.value.id); holds++ }
                else -> error("stock.native_refusal_reservation_member_kind")
            }
        }
        check(allocations == reservation.value.allocationCount && holds == reservation.value.holdCount)
    }
}

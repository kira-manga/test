package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentReservationSources
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentWriter
import com.ukkera.pos.inventory.InventoryAllocationRecord
import com.ukkera.pos.inventory.InventoryHoldRecord
import com.ukkera.pos.inventory.InventoryReservationHeader
import com.ukkera.pos.inventory.ReservationStatus

/** Inert current reservation facts. Retained holds and released allocations stay explicit;
 * this object grants no availability, issuance, expiry reversal or financial authority. */
public class LocalStockCurrentFoodReservation internal constructor(
    public val reservation: InventoryReservationHeader,
    allocations: List<InventoryAllocationRecord>,
    holds: List<InventoryHoldRecord>,
) {
    public val allocations: List<InventoryAllocationRecord> = localStockFrozen(allocations)
    public val holds: List<InventoryHoldRecord> = localStockFrozen(holds)
    override fun toString(): String = "LocalStockCurrentFoodReservation([REDACTED])"
}

/** The same strict normalized codec as the actual stock owner, on the core-created source
 * handle. Every native-current allocation/hold is decoded and its permanent claims matched.
 * Unknown rows, missing members and an issued header cannot become an unsent reservation. */
public suspend fun requireCurrentFoodReservationSources(
    sources: RuntimeHistoricalFoodCurrentReservationSources,
): LocalStockCurrentFoodReservation {
    val rows = sources.sources().map { source ->
        val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
        LocalStockRelationalCodecV2.decode(sources.scope, source.row).also { value ->
            localStockCheck(original.identity.scope == sources.scope && value.producer == original.identity.producer)
        }
    }
    val key = LocalStockRelationalProfileV2.reservationKey(sources.reservationId)
    val body = rows.singleOrNull { it.key == key }?.body as? StockRelationalBody.Reservation ?: inconsistentStock()
    val reservation = body.value
    localStockCheck(reservation.id == sources.reservationId && reservation.issueId == null &&
        reservation.status in setOf(ReservationStatus.RESERVED, ReservationStatus.HELD, ReservationStatus.RELEASED) &&
        sources.claim(LocalStockStoreProfile.reservationClaim(reservation.id)) == reservation.id)
    val allocations = mutableListOf<InventoryAllocationRecord>()
    val holds = mutableListOf<InventoryHoldRecord>()
    for (row in rows) when (val member = row.body) {
        is StockRelationalBody.Reservation -> localStockCheck(row.key == key)
        is StockRelationalBody.Allocation -> {
            val allocation = member.value.allocation
            localStockCheck(member.value.reservationId == reservation.id &&
                allocation.allocationId == LocalStockRelationalProfileV2.allocationId(body.allocationSetId,
                    body.allocationSetRevision, allocation.lineId, allocation.position) &&
                sources.claim(LocalStockStoreProfile.allocationClaim(allocation.allocationId)) == reservation.id)
            allocations += member.value
        }
        is StockRelationalBody.Hold -> {
            localStockCheck(member.value.reservationId == reservation.id)
            holds += member.value
        }
        else -> inconsistentStock()
    }
    localStockCheck(allocations.size.toLong() == reservation.allocationCount &&
        allocations.map { it.allocation.allocationId }.distinct().size == allocations.size &&
        holds.size.toLong() == reservation.holdCount && holds.map { it.hold }.distinct().size == holds.size)
    sources.requireExactWriters(rows.map { RuntimeHistoricalFoodCurrentWriter(it.key, it.generation, it.producer.operationId) })
    return LocalStockCurrentFoodReservation(reservation, allocations, holds)
}

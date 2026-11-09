package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/**
 * A transaction-local, dependency-closed set of normalized records. This is NOT a whole-branch
 * historical snapshot, an authoritative absence assertion, a serialized proof or a stock ledger.
 * Its inventory revision is a local planning-step counter (initially zero), never a branch CAS.
 * The durable adapter owns independent row revisions, complete item/claim indexes and operation,
 * source and allocation uniqueness. It must load every position in each included valuation pool,
 * every paired same-SKU transfer pool, every active reservation touching an included position,
 * and the other pools of those reservations.
 * A bounded closure which cannot be loaded completely must be refused, not truncated.
 *
 * Existing whole-branch snapshot and retail-posting codecs deliberately DO NOT accept this cut.
 * Individual record codecs and the exact source-object effect crosswalk are the storage boundary.
 */
public object InventoryLocalCut {
    public fun assemble(
        scope: InventoryScope,
        positions: List<StockPosition>,
        pools: List<OwnedValuationPool>,
        reservations: List<StockReservation>,
        issues: List<StockIssue> = emptyList(),
    ): InventorySnapshot {
        guard(positions.size <= InventoryPlanner.MAX_POSITIONS && pools.size <= InventoryPlanner.MAX_POOLS &&
            reservations.size <= InventoryPlanner.MAX_RESERVATIONS && issues.size <= InventoryPlanner.MAX_ISSUES,
            InventoryFailure.BOUND_EXCEEDED, "Normalized dependency cut exceeds command capacity")
        guard(positions.map { it.key }.distinct().size == positions.size && pools.map { it.key }.distinct().size == pools.size &&
            reservations.map { it.reservationId }.distinct().size == reservations.size &&
            issues.map { it.issueId }.distinct().size == issues.size,
            InventoryFailure.INVALID_STATE, "Duplicate normalized record")
        val byPosition = positions.associateBy { it.key }
        val byPool = pools.associateBy { it.key }
        val byReservation = reservations.associateBy { it.reservationId }
        requireSerialCut(positions, reservations)
        for (position in positions) {
            guard(position.key.pool.scope == scope, InventoryFailure.WRONG_SCOPE, "Mixed normalized scope")
            InventoryRecordCodec.requirePosition(position)
            if (position.key.pool.ownership == StockOwnership.CompanyOwned) {
                guard(position.key.pool in byPool, InventoryFailure.INVALID_STATE, "Owned valuation dependency missing")
            } else guard(position.key.pool !in byPool, InventoryFailure.WRONG_OWNERSHIP, "Supplier custody has no owned value")
        }
        positions.groupBy { it.key.pool.skuId }.values.forEach { sameSku ->
            sameSku.forEach { requireSameUnit(sameSku.first().onHand, it.onHand) }
        }
        positions.groupBy { it.key.pool.skuId to it.key.lotId }.values.forEach { sameLot ->
            guard(sameLot.map { it.expiresAtEpochMillis }.distinct().size == 1,
                InventoryFailure.INVALID_STATE, "Lot metadata disagrees")
            guard(sameLot.none { it.safety == StockSafety.RECALLED } || sameLot.all { it.safety == StockSafety.RECALLED },
                InventoryFailure.UNSAFE_STOCK, "Recall coverage is incomplete")
            guard(sameLot.none { it.safety == StockSafety.EXPIRED } || sameLot.none { it.safety == StockSafety.CLEAR },
                InventoryFailure.UNSAFE_STOCK, "Recorded expiry coverage is incomplete")
        }
        for (pool in pools) {
            guard(pool.key.scope == scope, InventoryFailure.WRONG_SCOPE, "Mixed normalized scope")
            InventoryRecordCodec.requirePool(pool)
            val physical = positions.filter { it.key.pool == pool.key }
            guard(physical.isNotEmpty(), InventoryFailure.INVALID_STATE, "Valuation has no retained positions")
            val total = physical.fold(Quantity(pool.value.unitId, 0, pool.value.scale)) { sum, position ->
                requireSameUnit(sum, position.onHand)
                sum + position.onHand
            }
            guard(total == pool.value.onHand, InventoryFailure.INVALID_STATE, "Valuation cut is incomplete")
        }
        // Individual pool validation cannot establish the paired or historical linked frontier.
        requireOwnedTransferCut(pools)
        val allocationIds = mutableSetOf<DomainId>()
        for (reservation in reservations) {
            InventoryRecordCodec.requireReservation(reservation, scope)
            for (allocation in reservation.allocations) {
                guard(allocationIds.add(allocation.allocationId), InventoryFailure.DUPLICATE_SOURCE, "Allocation ID reused")
                val position = byPosition[allocation.position]
                    ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Reservation dependency missing")
                requireSameUnit(position.onHand, allocation.quantity)
            }
        }
        for (position in positions) {
            val active = reservations.filter { it.retainsStockClaim && it.allocations.any { row -> row.position == position.key } }
            val total = active.flatMap { it.allocations }.filter { it.position == position.key }
                .fold(zeroLike(position.onHand)) { sum, allocation -> sum + allocation.quantity }
            guard(total <= position.onHand || active.all { it.status == ReservationStatus.HELD },
                InventoryFailure.INVALID_STATE, "A shortage must retain held claims")
            val reason = when (position.safety) {
                StockSafety.CLEAR -> null
                StockSafety.RECALLED -> ReservationHoldReason.RECALL
                StockSafety.EXPIRED -> ReservationHoldReason.EXPIRY
                StockSafety.COUNT_REVIEW -> ReservationHoldReason.COUNT_SHORTAGE
            }
            if (reason != null) guard(active.all { it.status == ReservationStatus.HELD &&
                ReservationHold(reason, position.key) in it.holds }, InventoryFailure.INVALID_STATE, "Safety claims were omitted")
        }
        for (issue in issues) {
            InventoryRecordCodec.requireIssue(issue, scope)
            val reservation = byReservation[issue.reservationId]
                ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original reservation missing")
            guard(reservation.status == ReservationStatus.ISSUED && reservation.binding == issue.binding &&
                reservation.allocations.associateBy { it.allocationId } == issue.allocations.associate { it.allocation.allocationId to it.allocation },
                InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original issue correspondence failed")
            for ((key, rows) in issue.allocations.groupBy { it.allocation.position.pool }) {
                val pool = byPool[key]?.value
                    ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original valued pool missing")
                val fact = pool.originalIssue(issue.issueId)
                    ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original issue valuation missing")
                guard(fact.purpose == issue.purpose && fact.slices.size == rows.size && fact.slices.all { slice ->
                    rows.any { row -> row.allocation.allocationId == slice.allocationId && row.allocation.quantity == slice.quantity &&
                        row.originalValue == slice.originalValue && row.restoredQuantity == pool.restoredQuantity(issue.issueId, slice.allocationId) }
                }, InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original/restored stock and value slices disagree")
            }
        }
        // These IDs are duplicate-detection hints inside this one plan, not a substitute for the
        // store's permanent source/operation/allocation claims. No historical counter is reset.
        val sourceIds = (pools.flatMap { it.value.eventIds } + issues.map { it.issueId }).distinct()
        return InventorySnapshot(scope, Revision(0), positions, pools, reservations, issues, emptyList(), sourceIds)
    }
}

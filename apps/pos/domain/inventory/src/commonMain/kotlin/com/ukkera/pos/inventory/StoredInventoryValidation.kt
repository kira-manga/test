package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

/** Checks links INSIDE the supplied whole cut; a consistent cut does not prove that it is complete. */
internal fun validateStoredInventory(snapshot: InventorySnapshot) {
    inventorySnapshotCheck(snapshot.revision.value == snapshot.operationIds.size.toLong() &&
        snapshot.operationIds.distinct().size == snapshot.operationIds.size && snapshot.sourceIds.distinct().size == snapshot.sourceIds.size)
    inventorySnapshotCheck(snapshot.sourceIds.size.toLong() <= snapshot.operationIds.size.toLong() * InventoryPlanner.MAX_ALLOCATIONS)
    inventorySnapshotCheck(snapshot.positions.map { it.key }.distinct().size == snapshot.positions.size &&
        snapshot.pools.map { it.key }.distinct().size == snapshot.pools.size &&
        snapshot.reservations.map { it.reservationId }.distinct().size == snapshot.reservations.size &&
        snapshot.issues.map { it.issueId }.distinct().size == snapshot.issues.size &&
        snapshot.issues.map { it.reservationId }.distinct().size == snapshot.issues.size)
    if (snapshot.revision.value == 0L) inventorySnapshotCheck(snapshot.positions.isEmpty() && snapshot.pools.isEmpty() &&
        snapshot.reservations.isEmpty() && snapshot.issues.isEmpty() && snapshot.sourceIds.isEmpty())
    inventorySnapshotCheck(snapshot.positions.isEmpty() || snapshot.sourceIds.isNotEmpty())

    val positions = snapshot.positions.associateBy { it.key }
    val pools = snapshot.pools.associateBy { it.key }
    val reservations = snapshot.reservations.associateBy { it.reservationId }
    val issues = snapshot.issues.associateBy { it.issueId }
    val issuedReservationIds = snapshot.issues.map { it.reservationId }.toSet()
    val sources = snapshot.sourceIds.toSet()
    for (position in snapshot.positions) {
        inventorySnapshotCheck(position.key.pool.scope == snapshot.scope && position.onHand.scaled >= 0 &&
            (position.expiresAtEpochMillis == null || (position.expiresAtEpochMillis >= 0 && position.key.lotId != null)))
        inventorySnapshotCheck(position.safety != StockSafety.EXPIRED || position.expiresAtEpochMillis != null)
        if (position.key.pool.ownership == StockOwnership.CompanyOwned) {
            inventorySnapshotCheck(position.key.custody != StockCustody.REJECTED_HELD && position.key.pool in pools)
        } else inventorySnapshotCheck(position.key.custody != StockCustody.RELEASED && position.key.pool !in pools)
    }
    for (sameSku in snapshot.positions.groupBy { it.key.pool.skuId }.values) {
        val original = sameSku.first().onHand
        sameSku.forEach { requireSameUnit(original, it.onHand) }
    }
    for (sameLot in snapshot.positions.groupBy { it.key.pool.skuId to it.key.lotId }.values) {
        inventorySnapshotCheck(sameLot.map { it.expiresAtEpochMillis }.distinct().size == 1)
        // The current profile has no recall/recorded-expiry clearing command.
        if (sameLot.any { it.safety == StockSafety.RECALLED }) inventorySnapshotCheck(sameLot.all { it.safety == StockSafety.RECALLED })
        if (sameLot.any { it.safety == StockSafety.EXPIRED }) inventorySnapshotCheck(sameLot.none { it.safety == StockSafety.CLEAR })
    }

    val chargeBindings = mutableMapOf<DomainId, Pair<StockPoolKey, DomainId>>()
    for (pool in snapshot.pools) {
        inventorySnapshotCheck(pool.key.scope == snapshot.scope && pool.key.ownership == StockOwnership.CompanyOwned)
        validateStoredValuation(pool.value)
        inventorySnapshotCheck(pool.value.facts.any { it is ValuationFact.Receipt })
        val physical = snapshot.positions.filter { it.key.pool == pool.key }
        inventorySnapshotCheck(physical.isNotEmpty()) // Retained zero positions are not pruned in this profile.
        val quantity = physical.fold(Quantity(pool.value.unitId, 0, pool.value.scale)) { sum, position -> sum + position.onHand }
        inventorySnapshotCheck(quantity == pool.value.onHand)
        val events = pool.value.eventIds.toSet()
        inventorySnapshotCheck(pool.value.eventIds == snapshot.sourceIds.filter { it in events })
        for (target in pool.value.costTargets) {
            val previous = chargeBindings.put(target.sourceId, pool.key to target.receiptId)
            inventorySnapshotCheck(previous == null || previous == (pool.key to target.receiptId))
        }
    }

    val allocationIds = mutableSetOf<DomainId>()
    val claimsByPosition = mutableMapOf<StockPositionKey, MutableList<StoredPositionClaim>>()
    for (reservation in snapshot.reservations) {
        inventorySnapshotCheck(reservation.allocations.isNotEmpty() && reservation.revision.value >= 1 &&
            reservation.revision.value <= snapshot.revision.value * (InventoryPlanner.MAX_ALLOCATIONS + 2))
        inventorySnapshotCheck(reservation.expiresAtEpochMillis == null || reservation.expiresAtEpochMillis > 0)
        inventorySnapshotCheck(reservation.holds.distinct().size == reservation.holds.size)
        when (reservation.status) {
            ReservationStatus.RESERVED -> inventorySnapshotCheck(reservation.holds.isEmpty())
            ReservationStatus.HELD -> inventorySnapshotCheck(reservation.holds.isNotEmpty() && reservation.revision.value >= 2)
            ReservationStatus.ISSUED -> inventorySnapshotCheck(reservation.holds.isEmpty() && reservation.revision.value >= 2 &&
                reservation.reservationId in issuedReservationIds)
            ReservationStatus.RELEASED -> inventorySnapshotCheck(reservation.revision.value >= 2)
        }
        for (allocation in reservation.allocations) {
            inventorySnapshotCheck(allocationIds.add(allocation.allocationId))
            val position = positions[allocation.position]
            inventorySnapshotCheck(position != null && allocation.position.pool.scope == snapshot.scope &&
                allocation.position.pool.ownership == StockOwnership.CompanyOwned && allocation.position.custody == StockCustody.RELEASED)
            val stock = requireNotNull(position)
            storedQuantity(allocation.quantity, stock.onHand.unitId, stock.onHand.scale)
        }
        val allocatedPositions = reservation.allocations.groupBy { it.position }
        for (hold in reservation.holds) validateStoredHold(hold, reservation, allocatedPositions.keys, positions)
        if (reservation.retainsStockClaim) {
            val holds = reservation.holds.toSet()
            for ((key, rows) in allocatedPositions) {
                val claimed = rows.fold(zeroLike(rows.first().quantity)) { sum, row -> sum + row.quantity }
                claimsByPosition.getOrPut(key) { mutableListOf() } += StoredPositionClaim(reservation.status, claimed, holds)
            }
        }
    }

    for (position in snapshot.positions) {
        val claims = claimsByPosition[position.key].orEmpty()
        val claimed = claims.fold(zeroLike(position.onHand)) { sum, row -> sum + row.quantity }
        if (claimed > position.onHand) inventorySnapshotCheck(claims.all { claim ->
            claim.status == ReservationStatus.HELD &&
                (ReservationHold(ReservationHoldReason.COUNT_SHORTAGE, position.key) in claim.holds ||
                    ReservationHold(ReservationHoldReason.UNAVAILABLE, position.key) in claim.holds)
        })
        val reason = when (position.safety) {
            StockSafety.CLEAR -> null
            StockSafety.RECALLED -> ReservationHoldReason.RECALL
            StockSafety.EXPIRED -> ReservationHoldReason.EXPIRY
            StockSafety.COUNT_REVIEW -> ReservationHoldReason.COUNT_SHORTAGE
        }
        if (reason != null) inventorySnapshotCheck(claims.all { it.status == ReservationStatus.HELD &&
            ReservationHold(reason, position.key) in it.holds })
    }

    val factRows = snapshot.pools.flatMap { pool -> pool.value.facts.map { pool.key to it } }
    val factsByEvent = factRows.groupBy { it.second.factId }
    val eventPools = snapshot.pools.flatMap { pool -> pool.value.eventIds.map { it to pool.key } }.groupBy { it.first }
    for ((event, locations) in eventPools) {
        inventorySnapshotCheck(event in sources)
        if (locations.size > 1) {
            val rows = factsByEvent[event].orEmpty().map { it.second }
            inventorySnapshotCheck(rows.size == locations.size && (
                rows.all { it is ValuationFact.Issue && (it.purpose == ValueIssuePurpose.RETAIL || it.purpose == ValueIssuePurpose.DIRECT_RECIPE) } ||
                    rows.all { it is ValuationFact.Restoration }))
        }
    }
    val restorationTotals = mutableMapOf<Triple<StockPoolKey, DomainId, DomainId>, StoredRestorationTotal>()
    for ((key, fact) in factRows) {
        if (fact is ValuationFact.Issue && (fact.purpose == ValueIssuePurpose.RETAIL || fact.purpose == ValueIssuePurpose.DIRECT_RECIPE)) {
            inventorySnapshotCheck(fact.factId in issues)
        }
        if (fact is ValuationFact.Restoration) {
            inventorySnapshotCheck(fact.restorations.map { it.issueId }.distinct().size == 1 && fact.restorations.all { it.issueId in issues })
            for (row in fact.restorations) {
                val total = restorationTotals.getOrPut(Triple(key, row.issueId, row.allocationId)) {
                    StoredRestorationTotal(zeroLike(row.quantity))
                }
                total.quantity += row.quantity
                total.eventIds += fact.factId
            }
        }
    }
    for ((_, rows) in factsByEvent) {
        if (rows.first().second is ValuationFact.Restoration) inventorySnapshotCheck(rows.all { it.second is ValuationFact.Restoration } &&
            rows.flatMap { (it.second as ValuationFact.Restoration).restorations }.map { it.issueId }.distinct().size == 1)
    }

    for (issue in snapshot.issues) {
        inventorySnapshotCheck(issue.purpose == ValueIssuePurpose.RETAIL || issue.purpose == ValueIssuePurpose.DIRECT_RECIPE)
        inventorySnapshotCheck(issue.revision.value in 1..snapshot.revision.value && issue.issueId in sources && issue.allocations.isNotEmpty())
        val reservation = reservations[issue.reservationId]
        inventorySnapshotCheck(reservation != null && reservation.status == ReservationStatus.ISSUED && issue.binding == reservation.binding)
        inventorySnapshotCheck(issue.allocations.map { it.allocation.allocationId }.distinct().size == issue.allocations.size)
        inventorySnapshotCheck(issue.allocations.map { it.allocation }.associateBy { it.allocationId } ==
            requireNotNull(reservation).allocations.associateBy { it.allocationId })
        val groups = issue.allocations.groupBy { it.allocation.position.pool }
        val rows = factsByEvent[issue.issueId].orEmpty()
        inventorySnapshotCheck(rows.map { it.first }.toSet() == groups.keys && rows.size == groups.size)
        for ((key, raw) in rows) {
            inventorySnapshotCheck(raw is ValuationFact.Issue && raw.purpose == issue.purpose)
            val fact = raw as ValuationFact.Issue
            val expected = groups.getValue(key).associate { it.allocation.allocationId to
                CostedValueSlice(it.allocation.allocationId, it.allocation.quantity, it.originalValue) }
            inventorySnapshotCheck(fact.slices.associateBy { it.allocationId } == expected)
        }
        val restorationEvents = mutableSetOf<DomainId>()
        for (allocation in issue.allocations) {
            val original = allocation.allocation
            storedValue(allocation.originalValue, Currency.EGP)
            storedQuantity(allocation.restoredQuantity, original.quantity.unitId, original.quantity.scale, positive = false)
            inventorySnapshotCheck(allocation.restoredQuantity <= original.quantity)
            val restored = restorationTotals[Triple(original.position.pool, issue.issueId, original.allocationId)]
            inventorySnapshotCheck((restored?.quantity ?: zeroLike(original.quantity)) == allocation.restoredQuantity)
            if (restored != null) restorationEvents += restored.eventIds
        }
        storedValue(issue.originalValue, Currency.EGP) // Aggregate cross-pool original cost must not overflow.
        validateStoredKitchen(issue, restorationEvents)
    }
}

private data class StoredPositionClaim(val status: ReservationStatus, val quantity: Quantity, val holds: Set<ReservationHold>)

private class StoredRestorationTotal(var quantity: Quantity) {
    val eventIds: MutableSet<DomainId> = mutableSetOf()
}

private fun validateStoredHold(
    hold: ReservationHold,
    reservation: StockReservation,
    allocatedPositions: Set<StockPositionKey>,
    positions: Map<StockPositionKey, StockPosition>,
) {
    if (hold.reason == ReservationHoldReason.RESERVATION_EXPIRED) {
        inventorySnapshotCheck(hold.position == null && reservation.expiresAtEpochMillis != null)
    } else {
        inventorySnapshotCheck(hold.position != null && hold.position in allocatedPositions)
        val position = positions.getValue(requireNotNull(hold.position))
        when (hold.reason) {
            ReservationHoldReason.RECALL -> inventorySnapshotCheck(position.safety == StockSafety.RECALLED)
            ReservationHoldReason.EXPIRY -> inventorySnapshotCheck(position.expiresAtEpochMillis != null && position.safety != StockSafety.CLEAR)
            ReservationHoldReason.COUNT_SHORTAGE -> inventorySnapshotCheck(position.safety != StockSafety.CLEAR)
            ReservationHoldReason.UNAVAILABLE -> Unit // Historical hold survives subsequent receipt/availability changes.
            ReservationHoldReason.RESERVATION_EXPIRED -> error("Handled above")
        }
    }
}

private fun validateStoredKitchen(issue: StockIssue, restorationEvents: Set<DomainId>) {
    if (issue.purpose == ValueIssuePurpose.RETAIL) {
        inventorySnapshotCheck(issue.kitchen == null && issue.revision.value == 1L + restorationEvents.size)
        return
    }
    val kitchen = issue.kitchen
    inventorySnapshotCheck(kitchen != null)
    val state = requireNotNull(kitchen)
    inventorySnapshotCheck(state.requiredStationIds.isNotEmpty() && state.requiredStationIds.distinct().size == state.requiredStationIds.size &&
        state.startedStationIds.distinct().size == state.startedStationIds.size && state.startedStationIds.all { it in state.requiredStationIds })
    when (state.disposition) {
        KitchenDisposition.ACTIVE -> inventorySnapshotCheck(restorationEvents.isEmpty() && issue.allocations.all { it.restoredQuantity.scaled == 0L })
        KitchenDisposition.CANCELLED_BEFORE_START -> inventorySnapshotCheck(state.startedStationIds.isEmpty() && restorationEvents.size == 1 &&
            issue.allocations.all { it.restoredQuantity == it.allocation.quantity })
        KitchenDisposition.WASTE_AFTER_START -> inventorySnapshotCheck(state.startedStationIds.isNotEmpty() && restorationEvents.isEmpty() &&
            issue.allocations.all { it.restoredQuantity.scaled == 0L })
    }
    inventorySnapshotCheck(issue.revision.value == 1L + state.startedStationIds.size +
        (if (state.disposition == KitchenDisposition.ACTIVE) 0 else 1))
}

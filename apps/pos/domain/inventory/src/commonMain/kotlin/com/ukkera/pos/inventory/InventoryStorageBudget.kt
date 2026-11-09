package com.ukkera.pos.inventory

/**
 * Runs before verification or DTO/JSON allocation. Estimates deliberately exceed
 * the encoded ASCII fields (all free-form identifiers/scalars have owned bounds).
 * Exhaustion rejects storage; it never drops historical facts or source rights.
 */
internal fun checkInventoryStorageBudget(snapshot: InventorySnapshot) {
    inventoryStoredCount(snapshot.positions.size, InventoryPlanner.MAX_POSITIONS)
    inventoryStoredCount(snapshot.pools.size, InventoryPlanner.MAX_POOLS)
    inventoryStoredCount(snapshot.reservations.size, InventoryPlanner.MAX_RESERVATIONS)
    inventoryStoredCount(snapshot.issues.size, InventoryPlanner.MAX_ISSUES)
    inventoryStoredCount(snapshot.operationIds.size, InventoryPlanner.MAX_OPERATIONS)
    inventoryStoredCount(snapshot.sourceIds.size, InventorySnapshotCodec.MAX_ARRAY_ENTRIES)
    val budget = InventoryStorageBudget()
    budget.add(snapshot.positions.size, 1_024)
    budget.add(snapshot.operationIds.size, 40)
    budget.add(snapshot.sourceIds.size, 40)
    for (reservation in snapshot.reservations) {
        inventoryStoredCount(reservation.allocations.size, InventoryPlanner.MAX_ALLOCATIONS)
        inventoryStoredCount(reservation.holds.size, InventoryPlanner.MAX_ALLOCATIONS * ReservationHoldReason.entries.size + 1)
        budget.add(1, 1_024)
        budget.add(reservation.allocations.size, 1_536)
        budget.add(reservation.holds.size, 1_024)
    }
    for (issue in snapshot.issues) {
        inventoryStoredCount(issue.allocations.size, InventoryPlanner.MAX_ALLOCATIONS)
        budget.add(1, 1_024)
        budget.add(issue.allocations.size, 1_792)
        issue.kitchen?.let { kitchen ->
            inventoryStoredCount(kitchen.requiredStationIds.size, 32)
            inventoryStoredCount(kitchen.startedStationIds.size, 32)
            budget.add(1, 512)
            budget.add(kitchen.requiredStationIds.size + kitchen.startedStationIds.size, 40)
        }
    }
    var replayUnits = 0L
    for (pool in snapshot.pools) {
        val value = pool.value
        inventoryStoredCount(value.facts.size, MovingAveragePlanner.MAX_EVENTS)
        inventoryStoredCount(value.eventIds.size, MovingAveragePlanner.MAX_EVENTS)
        inventoryStoredCount(value.adjustments.size, MovingAveragePlanner.MAX_EVENTS)
        inventoryStoredCount(value.costTargets.size, MovingAveragePlanner.MAX_COST_SOURCES)
        inventoryStoredCount(value.transferTargets.size, MovingAveragePlanner.MAX_EVENTS)
        inventoryStoredCount(value.linkedCorrectionBalances.size, InventorySnapshotCodec.MAX_ARRAY_ENTRIES)
        budget.add(1, 2_048)
        budget.add(value.eventIds.size, 40)
        budget.add(value.costTargets.size, 256)
        budget.add(value.transferTargets.size, 192)
        budget.add(value.linkedCorrectionBalances.size, 384)
        var slices = 0L
        for (fact in value.facts) {
            when (fact) {
                is ValuationFact.Receipt -> budget.add(1, 640)
                is ValuationFact.TransferIn, is ValuationFact.TransferOut -> budget.add(1, 2_048)
                is ValuationFact.Issue -> {
                    inventoryStoredCount(fact.slices.size, MovingAveragePlanner.MAX_SLICES)
                    budget.add(1, 256)
                    budget.add(fact.slices.size, 512)
                    slices += fact.slices.size
                }
                is ValuationFact.Restoration -> {
                    inventoryStoredCount(fact.restorations.size, MovingAveragePlanner.MAX_SLICES)
                    budget.add(1, 256)
                    budget.add(fact.restorations.size, 640)
                    slices += fact.restorations.size
                }
            }
        }
        for (adjustment in value.adjustments) {
            inventoryStoredCount(adjustment.targetBasis.size, MovingAveragePlanner.MAX_COST_SOURCES)
            inventoryStoredCount(adjustment.deltas.size, InventorySnapshotCodec.MAX_ARRAY_ENTRIES)
            inventoryStoredCount(adjustment.transferTargetBasis.size, MovingAveragePlanner.MAX_EVENTS)
            budget.add(1, 768)
            if (adjustment.changedSource != null) budget.add(1, 256)
            budget.add(adjustment.targetBasis.size, 256)
            budget.add(adjustment.transferTargetBasis.size, 192)
            budget.add(adjustment.deltas.size, 384)
            adjustment.transferCorrection?.let { basis ->
                inventoryStoredCount(basis.pools.size, LinkedMovingAveragePlanner.MAX_POOLS)
                budget.add(1, 1_024)
                budget.add(basis.pools.size, 640)
            }
        }
        // Structural input-expansion units, not CPU/comparison counts. Scalar arithmetic, bounded
        // slice/destination sorting and indexed lookups each still have their own operation cost.
        replayUnits += value.eventIds.size.toLong() * (1 + value.facts.size + 2 * slices + value.costTargets.size + value.transferTargets.size)
        inventorySnapshotCheck(replayUnits <= InventorySnapshotCodec.MAX_VALUATION_VERIFICATION_STEPS,
            InventorySnapshotFailure.TOO_LARGE)
    }
    for (group in snapshot.pools.groupBy { it.key.scope to it.key.skuId }.values) {
        if (group.none { it.value.hasTransferLineage }) continue
        inventoryStoredCount(group.size, LinkedMovingAveragePlanner.MAX_POOLS)
        val events = group.sumOf { it.value.eventIds.size.toLong() }
        val corrections = group.flatMap { it.value.adjustments }.filter { it.transferCorrection != null }.map { it.adjustmentId }.toSet().size
        val factsAndSlices = group.sumOf { it.value.facts.sumOf { fact -> when (fact) {
            is ValuationFact.Issue -> fact.slices.size.toLong()
            is ValuationFact.Restoration -> fact.restorations.size.toLong()
            else -> 1L
        } } }
        inventorySnapshotCheck(events <= LinkedMovingAveragePlanner.MAX_GRAPH_EVENTS &&
            (events + factsAndSlices) * (2 * corrections.toLong() + 1) <= LinkedMovingAveragePlanner.MAX_HISTORY_WORK,
            InventorySnapshotFailure.TOO_LARGE)
    }
}

private class InventoryStorageBudget {
    private var records = 0L
    private var bytes = 2_048L

    fun add(count: Int, maximumBytesEach: Int) {
        records += count
        bytes += count.toLong() * maximumBytesEach
        inventorySnapshotCheck(records <= InventorySnapshotCodec.MAX_STORED_RECORDS && bytes <= InventorySnapshotCodec.MAX_ENCODED_BYTES,
            InventorySnapshotFailure.TOO_LARGE)
    }
}

internal fun inventoryStoredCount(count: Int, maximum: Int) {
    inventorySnapshotCheck(count <= maximum, InventorySnapshotFailure.TOO_LARGE)
}

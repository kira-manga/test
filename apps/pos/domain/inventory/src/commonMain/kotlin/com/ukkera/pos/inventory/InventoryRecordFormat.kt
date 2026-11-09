package com.ukkera.pos.inventory

/** Old whole-cut/retail profiles do not silently acquire new production value semantics. */
internal fun requireLegacyInventoryFormat(snapshot: InventorySnapshot) {
    inventorySnapshotCheck(!hasSerialInventory(snapshot) && snapshot.pools.none { pool ->
        pool.value.hasTransferLineage || pool.value.facts.any(::extendedInventoryFact) },
        InventorySnapshotFailure.UNSUPPORTED_VERSION)
}

internal fun extendedInventoryFact(fact: ValuationFact): Boolean =
    fact is ValuationFact.Receipt && fact.origin == ReceiptValueOrigin.COUNT_SURPLUS

internal fun inventoryPoolRecordVersion(pool: OwnedValuationPool): Int =
    if (pool.value.hasTransferLineage) InventoryRecordCodec.OWNED_TRANSFER_VERSION
    else if (pool.value.facts.any(::extendedInventoryFact)) InventoryRecordCodec.EXTENDED_VERSION else InventoryRecordCodec.VERSION

internal fun ownedTransferInventoryFact(fact: ValuationFact?): Boolean =
    fact is ValuationFact.TransferOut || fact is ValuationFact.TransferIn

internal fun ownedTransferInventoryEffect(effect: InventoryEffect): Boolean = effect is InventoryEffect.Valuation &&
    (ownedTransferInventoryFact(effect.plan.fact) || effect.plan.linkedAdjustment?.calculationVersion == LinkedMovingAveragePlanner.CALCULATION_VERSION)

internal fun inventoryPositionRecordVersion(key: StockPositionKey): Int = if (key.serialId == null) 1 else 2
internal fun inventoryReservationRecordVersion(reservation: StockReservation): Int =
    if (reservation.allocations.any { it.position.serialId != null }) 2 else 1
internal fun inventoryIssueRecordVersion(issue: StockIssue): Int =
    if (issue.allocations.any { it.allocation.position.serialId != null }) 2 else 1

internal fun extendedInventoryEffect(effect: InventoryEffect): Boolean = when (effect) {
    is InventoryEffect.Movement -> effect.position.serialId != null
    is InventoryEffect.Valuation -> effect.plan.fact?.let(::extendedInventoryFact) == true
    is InventoryEffect.ReservationRecorded -> inventoryReservationRecordVersion(effect.reservation) == 2
    is InventoryEffect.ReservationReleased -> inventoryReservationRecordVersion(effect.reservation) == 2
    is InventoryEffect.IssueRecorded -> inventoryIssueRecordVersion(effect.issue) == 2
    is InventoryEffect.CountReconciled -> effect.position.serialId != null
    is InventoryEffect.SafetyChanged -> effect.before.key.serialId != null
    is InventoryEffect.SerialRegistrationConfirmed -> true
    is InventoryEffect.KitchenConfirmed, is InventoryEffect.WasteClassified -> false
}

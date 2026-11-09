package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

/** Literal qualified identity supplied by the trusted adapter, not a permission or wire proof. */
public data class StockSerialRegistrationConfirmation(
    public val scope: InventoryScope,
    public val skuId: DomainId,
    public val serialId: DomainId,
    public val registrationId: DomainId,
)

internal fun serialUnit(scale: Int): Long = when (scale) {
    0 -> 1L
    1 -> 10L
    2 -> 100L
    3 -> 1_000L
    4 -> 10_000L
    5 -> 100_000L
    6 -> 1_000_000L
    else -> throw InventoryRejection(InventoryFailure.UNIT_OR_SCALE_MISMATCH, "Unsupported serial precision")
}

internal fun requireSerialQuantity(key: StockPositionKey, quantity: Quantity, allowZero: Boolean = false) {
    if (key.serialId != null) guard(quantity.scaled == serialUnit(quantity.scale) || allowZero && quantity.scaled == 0L,
        InventoryFailure.INVALID_QUANTITY, "One serial represents exactly one whole base unit")
}

/** Retained zero positions are part of identity/history, not permission to receive the unit again. */
internal fun requireSerialCut(positions: List<StockPosition>, reservations: List<StockReservation>) {
    val serials = positions.filter { it.key.serialId != null }.groupBy { it.key.serialId }
    for ((id, rows) in serials) {
        val first = rows.first()
        guard(rows.all { it.key.pool.scope == first.key.pool.scope && it.key.pool.skuId == first.key.pool.skuId &&
            it.key.pool.ownership == first.key.pool.ownership && it.key.lotId == first.key.lotId &&
            it.onHand.unitId == first.onHand.unitId && it.onHand.scale == first.onHand.scale &&
            it.expiresAtEpochMillis == first.expiresAtEpochMillis && it.serialRegistrationId == first.serialRegistrationId } &&
            rows.count { it.onHand.scaled != 0L } <= 1, InventoryFailure.INVALID_STATE,
            "Serial identity, registration and current custody must be singular")
        rows.forEach { requireSerialQuantity(it.key, it.onHand, allowZero = true) }
        val claims = reservations.filter { it.retainsStockClaim }.flatMap { it.allocations }.filter { it.position.serialId == id }
        guard(claims.size <= 1, InventoryFailure.INVALID_STATE, "A serial cannot have competing retained claims")
    }
}

internal fun hasSerialInventory(snapshot: InventorySnapshot): Boolean = snapshot.positions.any { it.key.serialId != null } ||
    snapshot.reservations.any { reservation -> reservation.allocations.any { it.position.serialId != null } } ||
    snapshot.issues.any { issue -> issue.allocations.any { it.allocation.position.serialId != null } }

internal fun requireSerialRegistrationTransition(before: StockPosition, after: StockPosition) {
    inventorySnapshotCheck(before.key.serialId != null && before.serialRegistrationId == null && after.serialRegistrationId != null &&
        after == before.copy(serialRegistrationId = after.serialRegistrationId))
}

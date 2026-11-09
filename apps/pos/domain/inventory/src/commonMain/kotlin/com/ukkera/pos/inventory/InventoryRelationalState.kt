package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** Planning values, never a database coverage/authorization certificate. No whole-stock lists. */
public data class InventoryPositionAvailability(public val position: StockPosition, public val held: Quantity) {
    init { requireSameUnit(position.onHand, held); guard(held.scaled >= 0, InventoryFailure.INVALID_STATE, "stock.v2.held") }
}

public data class InventoryReservationHeader(
    public val id: DomainId,
    public val binding: CollectionBinding,
    public val revision: Revision,
    public val status: ReservationStatus,
    public val expiresAtEpochMillis: Long?,
    public val allocationCount: Long,
    public val holdCount: Long,
    public val issueId: DomainId? = null,
) {
    init {
        guard(revision.value >= 1 && allocationCount > 0 && holdCount >= 0 &&
            (expiresAtEpochMillis == null || expiresAtEpochMillis >= 0), InventoryFailure.INVALID_STATE, "stock.v2.reservation")
        guard((status == ReservationStatus.ISSUED) == (issueId != null), InventoryFailure.INVALID_STATE, "stock.v2.issue_link")
        guard(when (status) {
            ReservationStatus.RESERVED, ReservationStatus.ISSUED -> holdCount == 0L
            ReservationStatus.HELD -> holdCount > 0L
            ReservationStatus.RELEASED -> true
        }, InventoryFailure.INVALID_STATE, "stock.v2.hold_link")
    }
    public val retainsStockClaim: Boolean get() = status == ReservationStatus.RESERVED || status == ReservationStatus.HELD
}

public data class InventoryAllocationRecord(public val reservationId: DomainId, public val allocation: StockAllocation) {
    init { positive(allocation.quantity) }
}

public data class InventoryHoldRecord(public val reservationId: DomainId, public val hold: ReservationHold)

/** Current exact pool totals; immutable source/slice/transfer facts are separately retained. */
public data class InventoryPoolProjection(
    public val key: StockPoolKey,
    public val revision: Revision,
    public val onHand: Quantity,
    public val carryingValue: Money,
    public val lastEventOrdinal: Long,
) {
    init {
        guard(key.ownership == StockOwnership.CompanyOwned && revision.value >= 1 && onHand.scaled >= 0 &&
            carryingValue.minor >= 0 && lastEventOrdinal >= 0 && (onHand.scaled != 0L || carryingValue.minor == 0L),
            InventoryFailure.INVALID_STATE, "stock.v2.pool")
    }
}

public data class InventoryIssueSlice(
    public val issueId: DomainId,
    public val allocation: StockAllocation,
    public val originalValue: Money,
    public val restoredQuantity: Quantity,
) {
    init {
        positive(allocation.quantity); requireSameUnit(allocation.quantity, restoredQuantity)
        guard(originalValue.minor >= 0 && restoredQuantity.scaled >= 0 && restoredQuantity <= allocation.quantity,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "stock.v2.original_slice")
    }
}

/** A scalar delta; its producer and exact original source remain the adapter's obligations. */
public data class InventoryPoolDelta(
    public val before: InventoryPoolProjection,
    public val after: InventoryPoolProjection,
    public val quantity: Quantity,
    public val value: Money,
)

public fun interface InventoryMembershipStream {
    /** Must finish the complete selected-position stream, including held and expired headers. */
    public suspend fun read(consume: suspend (InventoryReservationHeader, InventoryAllocationRecord) -> Unit)
}

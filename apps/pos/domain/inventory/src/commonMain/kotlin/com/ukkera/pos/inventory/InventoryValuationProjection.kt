package com.ukkera.pos.inventory

import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity

/** Exact bounded scalars; the same-loan SQL scratch owns any large ordered slice set. */
public object InventoryValuationProjection {
    public fun issue(pool: InventoryPoolProjection, quantity: Quantity): InventoryPoolDelta {
        positive(quantity); requireSameUnit(pool.onHand, quantity)
        val value = MovingAveragePlanner.issueValue(pool.carryingValue, quantity, pool.onHand)
        return delta(pool, -quantity, -value)
    }

    public fun receive(pool: InventoryPoolProjection, quantity: Quantity, approvedValue: Money): InventoryPoolDelta {
        positive(quantity); requireSameUnit(pool.onHand, quantity)
        guard(approvedValue.currency == pool.carryingValue.currency && approvedValue.minor >= 0,
            InventoryFailure.NEGATIVE_VALUE, "stock.v2.receipt_value")
        return delta(pool, quantity, approvedValue)
    }

    /**
     * Immutable original issue-cost ownership only. This is NOT a current pool restoration:
     * receipt-cost corrections/owned transfers require their actual linked reference frontier
     * before a writer may derive a carrying-value delta. No blind original-value pool mutation.
     */
    public fun originalRestorationValue(original: InventoryIssueSlice, quantity: Quantity): Money {
        positive(quantity); requireSameUnit(quantity, original.allocation.quantity)
        val cumulative = original.restoredQuantity + quantity
        guard(cumulative <= original.allocation.quantity, InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "stock.v2.restore_exhausted")
        return cumulativeValue(original.originalValue, cumulative, original.allocation.quantity) -
            cumulativeValue(original.originalValue, original.restoredQuantity, original.allocation.quantity)
    }

    /** Floor and exact integer remainder for the existing largest-remainder/ID tie-break rule. */
    public fun slice(value: Money, quantity: Quantity, total: Quantity): InventorySliceShare {
        positive(quantity); positive(total); requireSameUnit(quantity, total)
        guard(quantity <= total && value.minor >= 0, InventoryFailure.INVALID_QUANTITY, "stock.v2.slice")
        val exact = ExactRational.ratio(value.minor, 1) * ExactRational.ratio(quantity.scaled, total.scaled)
        val floor = exact.floorScaled(0)
        val remainder = ((exact - ExactRational.ratio(floor, 1)) * ExactRational.ratio(total.scaled, 1)).toScaledLongExact(0)
        return InventorySliceShare(Money(value.currency, floor), remainder)
    }

    private fun cumulativeValue(value: Money, quantity: Quantity, total: Quantity): Money =
        if (quantity == total) value else Money(value.currency,
            (ExactRational.ratio(value.minor, 1) * ExactRational.ratio(quantity.scaled, total.scaled)).roundHalfUp(0))

    private fun delta(pool: InventoryPoolProjection, quantity: Quantity, value: Money): InventoryPoolDelta {
        guard(pool.lastEventOrdinal < Long.MAX_VALUE, InventoryFailure.BOUND_EXCEEDED, "stock.v2.ordinal")
        val after = pool.copy(revision = pool.revision.next(), onHand = pool.onHand + quantity,
            carryingValue = pool.carryingValue + value, lastEventOrdinal = pool.lastEventOrdinal + 1)
        return InventoryPoolDelta(pool, after, quantity, value)
    }
}

public data class InventorySliceShare(public val floor: Money, public val remainder: Long)

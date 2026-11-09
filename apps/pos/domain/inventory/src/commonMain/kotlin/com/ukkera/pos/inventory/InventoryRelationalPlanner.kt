package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** Per-record exact rules for the repository's single streamed operation. No capacity authority. */
public object InventoryRelationalPlanner {
    public suspend fun availability(position: StockPosition, membership: InventoryMembershipStream): InventoryPositionAvailability {
        var held = zeroLike(position.onHand)
        membership.read { header, record ->
            guard(record.reservationId == header.id && record.allocation.position == position.key,
                InventoryFailure.INVALID_STATE, "stock.v2.membership")
            requireSameUnit(position.onHand, record.allocation.quantity)
            if (header.retainsStockClaim) held += record.allocation.quantity
        }
        return InventoryPositionAvailability(position, held)
    }

    public fun available(source: InventoryPositionAvailability, nowEpochMillis: Long): Quantity {
        guard(nowEpochMillis >= 0, InventoryFailure.INVALID_STATE, "stock.v2.time")
        if (!source.position.isSellableAt(nowEpochMillis) || source.held >= source.position.onHand) return zeroLike(source.position.onHand)
        return source.position.onHand - source.held
    }

    public fun select(source: InventoryPositionAvailability, alreadySelected: Quantity, remaining: Quantity,
        nowEpochMillis: Long): Quantity {
        requireSameUnit(source.position.onHand, alreadySelected); requireSameUnit(source.position.onHand, remaining)
        guard(alreadySelected.scaled >= 0 && remaining.scaled > 0, InventoryFailure.INVALID_QUANTITY, "stock.v2.selection")
        val available = available(source, nowEpochMillis)
        guard(alreadySelected <= available, InventoryFailure.INSUFFICIENT_STOCK, "stock.v2.selected_twice")
        val free = available - alreadySelected
        return if (free <= remaining) free else remaining
    }

    public fun reserve(id: DomainId, binding: CollectionBinding, allocationCount: Long, nowEpochMillis: Long,
        expiresAtEpochMillis: Long?): InventoryReservationHeader {
        guard(nowEpochMillis >= 0 && (expiresAtEpochMillis == null || expiresAtEpochMillis > nowEpochMillis),
            InventoryFailure.INVALID_STATE, "stock.v2.expiry")
        // Preserve legacy reserve semantics: protected timed reservations expire into HELD,
        // never into availability. The financial precollection consumer uses no expiry.
        return InventoryReservationHeader(id, binding, Revision(1), ReservationStatus.RESERVED,
            expiresAtEpochMillis, allocationCount, 0)
    }

    public fun release(source: InventoryReservationHeader, expected: Revision,
        resolution: CollectionResolutionReference?): InventoryReservationHeader {
        requireRevision(source.revision, expected)
        guard(source.retainsStockClaim, InventoryFailure.RESERVATION_NOT_ACTIVE, "stock.v2.release_state")
        if (source.binding.exposure != CollectionExposure.UNCOLLECTED || resolution != null) {
            guard(resolution != null && resolution.orderId == source.binding.orderId &&
                resolution.collectionId == source.binding.collectionId,
                InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "stock.v2.release_resolution")
        }
        return source.copy(revision = source.revision.next(), status = ReservationStatus.RELEASED)
    }

    public fun issue(source: InventoryReservationHeader, expected: Revision, issueId: DomainId,
        nowEpochMillis: Long): InventoryReservationHeader {
        requireRevision(source.revision, expected)
        guard(source.status == ReservationStatus.RESERVED && source.holdCount == 0L &&
            (source.expiresAtEpochMillis == null || nowEpochMillis < source.expiresAtEpochMillis),
            InventoryFailure.UNSAFE_STOCK, "stock.v2.issue_hold")
        return source.copy(revision = source.revision.next(), status = ReservationStatus.ISSUED, issueId = issueId)
    }

    public fun hold(source: InventoryReservationHeader, newDistinctHoldCount: Long): InventoryReservationHeader {
        guard(source.retainsStockClaim && newDistinctHoldCount > 0 && newDistinctHoldCount <= Long.MAX_VALUE - source.holdCount &&
            newDistinctHoldCount <= Long.MAX_VALUE - source.revision.value, InventoryFailure.INVALID_STATE, "stock.v2.hold_count")
        return source.copy(revision = Revision(source.revision.value + newDistinctHoldCount), status = ReservationStatus.HELD,
            holdCount = source.holdCount + newDistinctHoldCount)
    }

    public fun movement(position: StockPosition, delta: Quantity): StockPosition {
        requireSameUnit(position.onHand, delta)
        val after = position.onHand + delta
        guard(after.scaled >= 0, InventoryFailure.INSUFFICIENT_STOCK, "stock.v2.negative_stock")
        return position.copy(onHand = after)
    }
}

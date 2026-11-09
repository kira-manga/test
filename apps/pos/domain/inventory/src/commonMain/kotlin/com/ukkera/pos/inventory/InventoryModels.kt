package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

public enum class CollectionExposure { UNCOLLECTED, PART_PAID, PAID, UNKNOWN }

/** Monetary evidence belongs to sales. Inventory retains these links and never edits money. */
public data class CollectionBinding(
    public val orderId: DomainId,
    public val quoteRevision: Revision,
    public val collectionId: DomainId,
    public val originalActorId: DomainId,
    public val exposure: CollectionExposure,
)

public enum class ReservationStatus { RESERVED, HELD, ISSUED, RELEASED }
public enum class ReservationHoldReason { RECALL, EXPIRY, COUNT_SHORTAGE, RESERVATION_EXPIRED, UNAVAILABLE }
public data class ReservationHold(public val reason: ReservationHoldReason, public val position: StockPositionKey? = null)

public class StockReservation internal constructor(
    public val reservationId: DomainId,
    public val binding: CollectionBinding,
    allocations: List<StockAllocation>,
    public val revision: Revision,
    public val status: ReservationStatus,
    holds: List<ReservationHold> = emptyList(),
    public val expiresAtEpochMillis: Long? = null,
) {
    public val allocations: List<StockAllocation> = frozenList(allocations)
    public val holds: List<ReservationHold> = frozenList(holds)
    public val retainsStockClaim: Boolean get() = status == ReservationStatus.RESERVED || status == ReservationStatus.HELD
}

/** Required durable resolution record; merely constructing this is NOT authorization. */
public data class CollectionResolutionReference(
    public val resolutionId: DomainId,
    public val orderId: DomainId,
    public val collectionId: DomainId,
    /** An unresolved refund/fulfillment obligation stays recorded, even after stock is released. */
    public val preservedObligationId: DomainId,
)

public data class IssuedStockAllocation(
    public val allocation: StockAllocation,
    public val originalValue: Money,
    public val restoredQuantity: Quantity,
)

public class RecipeIssueRequest(public val recipeSnapshotId: DomainId, requiredStationIds: List<DomainId>) {
    public val requiredStationIds: List<DomainId> = frozenList(requiredStationIds)
    init {
        guard(requiredStationIds.isNotEmpty() && requiredStationIds.size <= 32 &&
            requiredStationIds.toSet().size == requiredStationIds.size,
            InventoryFailure.INVALID_STATE, "A bounded, unique required-station set is mandatory")
    }
}

public enum class KitchenDisposition { ACTIVE, CANCELLED_BEFORE_START, WASTE_AFTER_START }

public class KitchenIssueState internal constructor(
    public val recipeSnapshotId: DomainId,
    requiredStationIds: List<DomainId>,
    startedStationIds: List<DomainId>,
    public val disposition: KitchenDisposition,
) {
    public val requiredStationIds: List<DomainId> = frozenList(requiredStationIds)
    public val startedStationIds: List<DomainId> = frozenList(startedStationIds)
}

public class KitchenCancellationConfirmation(
    public val confirmationId: DomainId,
    public val issueId: DomainId,
    public val issueRevision: Revision,
    public val confirmedBy: DomainId,
    confirmedStationIds: List<DomainId>,
) {
    public val confirmedStationIds: List<DomainId> = frozenList(confirmedStationIds)
}

public class StockIssue internal constructor(
    public val issueId: DomainId,
    public val reservationId: DomainId,
    public val binding: CollectionBinding,
    allocations: List<IssuedStockAllocation>,
    public val purpose: ValueIssuePurpose,
    public val revision: Revision,
    public val kitchen: KitchenIssueState?,
) {
    public val allocations: List<IssuedStockAllocation> = frozenList(allocations)
    public val originalValue: Money get() = Money(Currency.EGP,
        allocations.fold(minor(0)) { sum, item -> sum + minor(item.originalValue.minor) }.toScaledLongExact(0))
}

public data class OwnedValuationPool(public val key: StockPoolKey, public val value: MovingAverageState) {
    init {
        guard(key.ownership == StockOwnership.CompanyOwned, InventoryFailure.WRONG_OWNERSHIP,
            "Supplier-owned rejected custody is outside owned valuation")
    }
}

/** A bounded authoritative read cut; not a durable storage implementation. */
public class InventorySnapshot internal constructor(
    public val scope: InventoryScope,
    public val revision: Revision,
    positions: List<StockPosition>,
    pools: List<OwnedValuationPool>,
    reservations: List<StockReservation>,
    issues: List<StockIssue>,
    operationIds: List<OperationId>,
    sourceIds: List<DomainId>,
) {
    public val positions: List<StockPosition> = frozenList(positions)
    public val pools: List<OwnedValuationPool> = frozenList(pools)
    public val reservations: List<StockReservation> = frozenList(reservations)
    public val issues: List<StockIssue> = frozenList(issues)
    public val operationIds: List<OperationId> = frozenList(operationIds)
    public val sourceIds: List<DomainId> = frozenList(sourceIds)

    public fun position(key: StockPositionKey): StockPosition? = positions.firstOrNull { it.key == key }
    public fun pool(key: StockPoolKey): OwnedValuationPool? = pools.firstOrNull { it.key == key }
    public fun reservation(id: DomainId): StockReservation? = reservations.firstOrNull { it.reservationId == id }
    public fun issue(id: DomainId): StockIssue? = issues.firstOrNull { it.issueId == id }

    /** Held claims remain claims. A shortage returns zero availability, never a negative bypass. */
    public fun available(key: StockPositionKey, nowEpochMillis: Long): Quantity {
        guard(nowEpochMillis >= 0, InventoryFailure.INVALID_STATE, "Use an authoritative nonnegative time")
        val position = position(key) ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Stock position is missing")
        if (!position.isSellableAt(nowEpochMillis)) return zeroLike(position.onHand)
        val claims = reservations.filter { it.retainsStockClaim }.flatMap { it.allocations }
            .filter { it.position == key }.fold(zeroLike(position.onHand)) { sum, item -> sum + item.quantity }
        return if (claims >= position.onHand) zeroLike(position.onHand) else position.onHand - claims
    }

    public companion object {
        public fun empty(scope: InventoryScope): InventorySnapshot =
            InventorySnapshot(scope, Revision(0), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

public enum class StockMovementReason { RECEIPT, ISSUE, ORIGINAL_RETURN, COUNT_SHORTAGE, OPENING, QUARANTINE_RELEASE, WAREHOUSE_TRANSFER, COUNT_SURPLUS }

public sealed class InventoryEffect {
    public data class Movement(
        public val sourceId: DomainId,
        public val position: StockPositionKey,
        public val quantityDelta: Quantity,
        public val reason: StockMovementReason,
        /** Distinguishes multiple portion slices using the same lot in one issue/return. */
        public val sourceAllocationId: DomainId? = null,
    ) : InventoryEffect()

    public data class Valuation(public val pool: StockPoolKey, public val plan: ValuationPlan) : InventoryEffect()
    public data class ReservationRecorded(public val reservation: StockReservation) : InventoryEffect()
    public data class ReservationReleased(
        public val reservation: StockReservation,
        public val resolution: CollectionResolutionReference?,
    ) : InventoryEffect()
    public data class SafetyChanged(public val before: StockPosition, public val after: StockPosition) : InventoryEffect()
    public data class SerialRegistrationConfirmed(public val before: StockPosition, public val after: StockPosition) : InventoryEffect()
    public data class IssueRecorded(public val issue: StockIssue) : InventoryEffect()
    public data class CountReconciled(
        public val countId: DomainId,
        public val position: StockPositionKey,
        public val capturedRevision: Revision,
        public val counted: Quantity,
    ) : InventoryEffect()
    public data class KitchenConfirmed(public val confirmation: KitchenCancellationConfirmation) : InventoryEffect()
    public data class WasteClassified(public val issueId: DomainId, public val originalValue: Money, public val reasonId: DomainId) : InventoryEffect()
}

/**
 * CAS precondition plus immutable effects. Persist all effects, result, audit/outbox and related
 * commerce obligations in ONE master transaction before success. This class does not commit.
 */
public class InventoryPlan internal constructor(
    public val operationId: OperationId,
    public val sourceSnapshot: InventorySnapshot,
    public val nextSnapshot: InventorySnapshot,
    effects: List<InventoryEffect>,
    /** Time can invalidate safety without any revision change. Exclusive authoritative deadline. */
    public val mustCommitBeforeEpochMillis: Long?,
) {
    public val requiredInventoryRevision: Revision = sourceSnapshot.revision
    public val effects: List<InventoryEffect> = frozenList(effects)
}

/** In-process planning evidence only, neither a wire capability nor a durable receipt. */
public class ReservationProof internal constructor(
    public val scope: InventoryScope,
    public val reservationId: DomainId,
    binding: CollectionBinding,
    allocations: List<StockAllocation>,
    public val requiredInventoryRevision: Revision,
    public val reservationRevision: Revision,
    public val validatedAtEpochMillis: Long,
    public val operationId: OperationId,
    public val mustCommitBeforeEpochMillis: Long?,
) {
    public val orderId: DomainId = binding.orderId
    public val quoteRevision: Revision = binding.quoteRevision
    public val collectionId: DomainId = binding.collectionId
    public val originalActorId: DomainId = binding.originalActorId
    public val exposure: CollectionExposure = binding.exposure
    public val allocations: List<StockAllocation> = frozenList(allocations)
}

public class IssueProof internal constructor(
    public val scope: InventoryScope,
    public val issueId: DomainId,
    public val reservationId: DomainId,
    public val purpose: ValueIssuePurpose,
    binding: CollectionBinding,
    allocations: List<IssuedStockAllocation>,
    public val requiredInventoryRevision: Revision,
    public val reservationRevision: Revision,
    public val validatedAtEpochMillis: Long,
    public val operationId: OperationId,
    public val mustCommitBeforeEpochMillis: Long?,
) {
    public val orderId: DomainId = binding.orderId
    public val quoteRevision: Revision = binding.quoteRevision
    public val collectionId: DomainId = binding.collectionId
    public val originalActorId: DomainId = binding.originalActorId
    public val costedAllocations: List<IssuedStockAllocation> = frozenList(allocations)
}

public class ReservationPlan internal constructor(public val plan: InventoryPlan, public val proof: ReservationProof)

public sealed class ReservationIssueResult {
    public class Issued internal constructor(public val plan: InventoryPlan, public val proof: IssueProof) : ReservationIssueResult()
    public class Held internal constructor(public val plan: InventoryPlan, public val reservationId: DomainId) : ReservationIssueResult()
}

public sealed class ReservationSafetyResult {
    public class Safe internal constructor(public val plan: InventoryPlan, public val proof: ReservationProof) : ReservationSafetyResult()
    public class Held internal constructor(public val plan: InventoryPlan, public val reservationId: DomainId) : ReservationSafetyResult()
}

public data class StockReceipt(
    public val receiptId: DomainId,
    public val position: StockPositionKey,
    public val quantity: Quantity,
    /** Null only for supplier-owned custody. This engine creates no invoice or payable. */
    public val originalValue: Money?,
    public val expiresAtEpochMillis: Long? = null,
    /** Opening balances are not purchases. Transfer/production require their linked planners. */
    public val valueOrigin: ReceiptValueOrigin = ReceiptValueOrigin.PURCHASE,
    public val serialRegistrationId: DomainId? = null,
)

public data class StockRestoration(
    public val allocationId: DomainId,
    public val quantity: Quantity,
    public val destination: StockPositionKey,
)

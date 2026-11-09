@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** Private storage shapes deliberately do not make public planning/evidence types serializable. */
@Serializable
internal data class StoredInventoryScope(val companyId: DomainId, val branchId: DomainId) {
    fun restore(): InventoryScope = InventoryScope(companyId, branchId)
    companion object {
        fun from(value: InventoryScope): StoredInventoryScope = StoredInventoryScope(value.companyId, value.branchId)
    }
}

@Serializable
internal data class StoredPoolKey(
    val scope: StoredInventoryScope,
    val skuId: DomainId,
    val warehouseId: DomainId,
    val ownership: String,
    val supplierId: DomainId?,
) {
    fun restore(): StockPoolKey {
        val owner = when (ownership) {
            "COMPANY_OWNED" -> {
                inventorySnapshotCheck(supplierId == null)
                StockOwnership.CompanyOwned
            }
            "SUPPLIER_OWNED" -> StockOwnership.SupplierOwned(requireNotNull(supplierId))
            else -> throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
        }
        return StockPoolKey(scope.restore(), skuId, warehouseId, owner)
    }
    companion object {
        fun from(value: StockPoolKey): StoredPoolKey = StoredPoolKey(StoredInventoryScope.from(value.scope),
            value.skuId, value.warehouseId, if (value.ownership == StockOwnership.CompanyOwned) "COMPANY_OWNED" else "SUPPLIER_OWNED",
            (value.ownership as? StockOwnership.SupplierOwned)?.supplierId)
    }
}

@Serializable
internal data class StoredPositionKey(val pool: StoredPoolKey, val lotId: DomainId?, val custody: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialId: DomainId? = null) {
    fun restore(): StockPositionKey = StockPositionKey(pool.restore(), lotId, enumValueOf<StockCustody>(custody), serialId)
    companion object {
        fun from(value: StockPositionKey): StoredPositionKey = StoredPositionKey(StoredPoolKey.from(value.pool), value.lotId, value.custody.name, value.serialId)
    }
}

@Serializable
internal data class StoredPosition(
    val key: StoredPositionKey,
    val onHand: Quantity,
    val safety: String,
    val expiresAtEpochMillis: String?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialRegistrationId: DomainId? = null,
) {
    fun restore(): StockPosition = StockPosition(key.restore(), onHand, enumValueOf<StockSafety>(safety), storedEpoch(expiresAtEpochMillis), serialRegistrationId)
    companion object {
        fun from(value: StockPosition): StoredPosition = StoredPosition(StoredPositionKey.from(value.key), value.onHand,
            value.safety.name, value.expiresAtEpochMillis?.toString(), value.serialRegistrationId)
    }
}

@Serializable
internal data class StoredStockAllocation(
    val allocationId: DomainId,
    val lineId: DomainId,
    val position: StoredPositionKey,
    val quantity: Quantity,
) {
    fun restore(): StockAllocation = StockAllocation(allocationId, lineId, position.restore(), quantity)
    companion object {
        fun from(value: StockAllocation): StoredStockAllocation = StoredStockAllocation(value.allocationId, value.lineId,
            StoredPositionKey.from(value.position), value.quantity)
    }
}

@Serializable
internal data class StoredCollectionBinding(
    val orderId: DomainId,
    val quoteRevision: Revision,
    val collectionId: DomainId,
    val originalActorId: DomainId,
    val exposure: String,
) {
    fun restore(): CollectionBinding = CollectionBinding(orderId, quoteRevision, collectionId, originalActorId,
        enumValueOf<CollectionExposure>(exposure))
    companion object {
        fun from(value: CollectionBinding): StoredCollectionBinding = StoredCollectionBinding(value.orderId, value.quoteRevision,
            value.collectionId, value.originalActorId, value.exposure.name)
    }
}

@Serializable
internal data class StoredReservationHold(val reason: String, val position: StoredPositionKey?) {
    fun restore(): ReservationHold = ReservationHold(enumValueOf<ReservationHoldReason>(reason), position?.restore())
    companion object {
        fun from(value: ReservationHold): StoredReservationHold = StoredReservationHold(value.reason.name, value.position?.let(StoredPositionKey::from))
    }
}

@Serializable
internal data class StoredReservation(
    val reservationId: DomainId,
    val binding: StoredCollectionBinding,
    val allocations: List<StoredStockAllocation>,
    val revision: Revision,
    val status: String,
    val holds: List<StoredReservationHold>,
    val expiresAtEpochMillis: String?,
) {
    fun restore(): StockReservation {
        inventorySnapshotCheck(allocations.size <= InventoryPlanner.MAX_ALLOCATIONS &&
            holds.size <= InventoryPlanner.MAX_ALLOCATIONS * ReservationHoldReason.entries.size + 1, InventorySnapshotFailure.TOO_LARGE)
        return StockReservation(reservationId, binding.restore(), allocations.map { it.restore() }, revision,
            enumValueOf<ReservationStatus>(status), holds.map { it.restore() }, storedEpoch(expiresAtEpochMillis))
    }
    companion object {
        fun from(value: StockReservation): StoredReservation = StoredReservation(value.reservationId,
            StoredCollectionBinding.from(value.binding), value.allocations.map(StoredStockAllocation::from), value.revision,
            value.status.name, value.holds.map(StoredReservationHold::from), value.expiresAtEpochMillis?.toString())
    }
}

@Serializable
internal data class StoredIssuedAllocation(
    val allocation: StoredStockAllocation,
    val originalValue: Money,
    val restoredQuantity: Quantity,
) {
    fun restore(): IssuedStockAllocation = IssuedStockAllocation(allocation.restore(), originalValue, restoredQuantity)
    companion object {
        fun from(value: IssuedStockAllocation): StoredIssuedAllocation = StoredIssuedAllocation(
            StoredStockAllocation.from(value.allocation), value.originalValue, value.restoredQuantity)
    }
}

@Serializable
internal data class StoredKitchenState(
    val recipeSnapshotId: DomainId,
    val requiredStationIds: List<DomainId>,
    val startedStationIds: List<DomainId>,
    val disposition: String,
) {
    fun restore(): KitchenIssueState {
        inventorySnapshotCheck(requiredStationIds.size <= 32 && startedStationIds.size <= 32, InventorySnapshotFailure.TOO_LARGE)
        return KitchenIssueState(recipeSnapshotId, requiredStationIds, startedStationIds, enumValueOf<KitchenDisposition>(disposition))
    }
    companion object {
        fun from(value: KitchenIssueState): StoredKitchenState = StoredKitchenState(value.recipeSnapshotId,
            value.requiredStationIds, value.startedStationIds, value.disposition.name)
    }
}

@Serializable
internal data class StoredIssue(
    val issueId: DomainId,
    val reservationId: DomainId,
    val binding: StoredCollectionBinding,
    val allocations: List<StoredIssuedAllocation>,
    val purpose: String,
    val revision: Revision,
    val kitchen: StoredKitchenState?,
) {
    fun restore(): StockIssue {
        inventorySnapshotCheck(allocations.size <= InventoryPlanner.MAX_ALLOCATIONS, InventorySnapshotFailure.TOO_LARGE)
        return StockIssue(issueId, reservationId, binding.restore(), allocations.map { it.restore() },
            enumValueOf<ValueIssuePurpose>(purpose), revision, kitchen?.restore())
    }
    companion object {
        fun from(value: StockIssue): StoredIssue = StoredIssue(value.issueId, value.reservationId,
            StoredCollectionBinding.from(value.binding), value.allocations.map(StoredIssuedAllocation::from),
            value.purpose.name, value.revision, value.kitchen?.let(StoredKitchenState::from))
    }
}

@Serializable
internal data class StoredOwnedPool(val key: StoredPoolKey, val value: StoredMovingAverageState) {
    fun restore(): OwnedValuationPool = OwnedValuationPool(key.restore(), value.restore())
    companion object {
        fun from(value: OwnedValuationPool): StoredOwnedPool = StoredOwnedPool(StoredPoolKey.from(value.key), StoredMovingAverageState.from(value.value))
    }
}

@Serializable
internal data class StoredInventoryState(
    val positions: List<StoredPosition>,
    val pools: List<StoredOwnedPool>,
    val reservations: List<StoredReservation>,
    val issues: List<StoredIssue>,
    val operationIds: List<OperationId>,
    val sourceIds: List<DomainId>,
) {
    fun restore(binding: InventorySnapshotBinding): InventorySnapshot {
        inventorySnapshotCheck(positions.size <= InventoryPlanner.MAX_POSITIONS && pools.size <= InventoryPlanner.MAX_POOLS &&
            reservations.size <= InventoryPlanner.MAX_RESERVATIONS && issues.size <= InventoryPlanner.MAX_ISSUES &&
            operationIds.size <= InventoryPlanner.MAX_OPERATIONS, InventorySnapshotFailure.TOO_LARGE)
        return InventorySnapshot(binding.scope, binding.revision, positions.map { it.restore() }, pools.map { it.restore() },
            reservations.map { it.restore() }, issues.map { it.restore() }, operationIds, sourceIds)
    }
    companion object {
        fun from(value: InventorySnapshot): StoredInventoryState = StoredInventoryState(value.positions.map(StoredPosition::from),
            value.pools.map(StoredOwnedPool::from), value.reservations.map(StoredReservation::from), value.issues.map(StoredIssue::from),
            value.operationIds, value.sourceIds)
    }
}

private fun storedEpoch(value: String?): Long? {
    if (value == null) return null
    inventorySnapshotCheck(value.length <= 19 && Regex("(?:0|[1-9][0-9]{0,18})").matches(value), InventorySnapshotFailure.NON_CANONICAL)
    return value.toLongOrNull() ?: throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
}

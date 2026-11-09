package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** Pure bounded transition planning. No mutex, in-memory result map or boolean promises durability. */
public object InventoryPlanner {
    public const val MAX_POSITIONS: Int = 2_048
    public const val MAX_POOLS: Int = 256
    public const val MAX_RESERVATIONS: Int = 1_024
    public const val MAX_ISSUES: Int = 1_024
    public const val MAX_OPERATIONS: Int = 4_096
    public const val MAX_ALLOCATIONS: Int = 256

    /** A durable adapter must perform this revision check inside its real atomic transaction. */
    public fun requireCurrent(plan: InventoryPlan, authoritative: InventorySnapshot, nowEpochMillis: Long? = null) {
        guard(plan.sourceSnapshot.scope == authoritative.scope, InventoryFailure.WRONG_SCOPE, "Wrong branch cut")
        requireRevision(authoritative.revision, plan.requiredInventoryRevision)
        guard(plan.operationId !in authoritative.operationIds, InventoryFailure.DUPLICATE_SOURCE,
            "Recover the persisted operation result instead of executing it again")
        if (nowEpochMillis != null) time(nowEpochMillis)
        val deadline = plan.mustCommitBeforeEpochMillis
        guard(deadline == null || (nowEpochMillis != null && nowEpochMillis < deadline), InventoryFailure.UNSAFE_STOCK,
            "Recheck authoritative commit time: a safety deadline cannot be omitted or elapsed")
    }

    public fun receive(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        receipts: List<StockReceipt>,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        guard(receipts.isNotEmpty() && receipts.size <= MAX_ALLOCATIONS, InventoryFailure.BOUND_EXCEEDED, "Receipt batch bound exceeded")
        receipts.forEach { receipt ->
            work.claimSource(receipt.receiptId)
            guard(receipt.valueOrigin == ReceiptValueOrigin.PURCHASE || receipt.valueOrigin == ReceiptValueOrigin.OPENING,
                InventoryFailure.UNSUPPORTED_LINEAGE, "Use a linked transfer or production planner")
            scope(snapshot, receipt.position.pool)
            positive(receipt.quantity)
            requireSerialQuantity(receipt.position, receipt.quantity)
            if (receipt.position.serialId != null) {
                guard(work.positions.none { it.key.serialId == receipt.position.serialId }, InventoryFailure.DUPLICATE_SOURCE,
                    "A retained serial cannot be reacquired, even after issue or loss")
                guard(receipt.position.custody != StockCustody.RELEASED || receipt.serialRegistrationId != null,
                    InventoryFailure.UNSAFE_STOCK, "Unqualified serial custody must remain pending and nonreleased")
            }
            guard(receipt.position.lotId != null || receipt.expiresAtEpochMillis == null,
                InventoryFailure.INVALID_STATE, "Expiry needs an explicit lot identity")
            val sameLot = work.positions.filter { sameLot(it.key, receipt.position) }
            guard(sameLot.all { it.expiresAtEpochMillis == receipt.expiresAtEpochMillis },
                InventoryFailure.INVALID_STATE, "Lot expiry cannot be changed by another receipt")
            val existing = work.positions.firstOrNull { it.key == receipt.position }
            existing?.let { requireSameUnit(it.onHand, receipt.quantity) }
            val initialSafety = when {
                sameLot.any { it.safety == StockSafety.RECALLED } -> StockSafety.RECALLED
                sameLot.any { it.safety == StockSafety.EXPIRED } -> StockSafety.EXPIRED
                else -> existing?.safety ?: StockSafety.CLEAR
            }
            val updated = StockPosition(receipt.position, (existing?.onHand ?: zeroLike(receipt.quantity)) + receipt.quantity,
                initialSafety, receipt.expiresAtEpochMillis, receipt.serialRegistrationId)
            if (receipt.position.pool.ownership == StockOwnership.CompanyOwned) {
                guard(receipt.position.custody != StockCustody.REJECTED_HELD,
                    InventoryFailure.WRONG_OWNERSHIP, "Rejected supplier custody is not purchased inventory")
                val value = receipt.originalValue ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Owned receipt needs its book value")
                val pool = work.pools.firstOrNull { it.key == receipt.position.pool }
                val previous = pool?.value ?: MovingAverageState.empty(receipt.quantity.unitId, receipt.quantity.scale, value.currency)
                val valuation = MovingAveragePlanner.receive(previous, previous.revision, receipt.receiptId, receipt.quantity, value,
                    receipt.valueOrigin)
                work.setPool(OwnedValuationPool(receipt.position.pool, valuation.next))
                work.effects += InventoryEffect.Valuation(receipt.position.pool, valuation)
            } else {
                guard(receipt.originalValue == null, InventoryFailure.WRONG_OWNERSHIP, "Supplier custody must not create owned carrying value")
                guard(receipt.position.custody != StockCustody.RELEASED,
                    InventoryFailure.WRONG_OWNERSHIP, "Supplier-owned receipt is not sellable")
            }
            work.setPosition(updated)
            work.effects += InventoryEffect.Movement(receipt.receiptId, receipt.position, receipt.quantity,
                if (receipt.valueOrigin == ReceiptValueOrigin.OPENING) StockMovementReason.OPENING else StockMovementReason.RECEIPT)
        }
        return work.finish()
    }

    /**
     * An inspection disposition, not another receipt or acquisition. The adapter must additionally
     * consume the remaining quantity of the ORIGINAL quarantined receipt under its current approval.
     * Rejected supplier-owned custody cannot be converted into purchased stock by this operation.
     */
    public fun releaseQuarantine(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        releaseId: DomainId,
        source: StockPositionKey,
        quantity: Quantity,
        nowEpochMillis: Long,
        serialRegistration: StockSerialRegistrationConfirmation? = null,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        time(nowEpochMillis)
        scope(snapshot, source.pool)
        positive(quantity)
        guard(source.pool.ownership == StockOwnership.CompanyOwned && source.custody == StockCustody.QUARANTINE,
            InventoryFailure.WRONG_OWNERSHIP, "Only owned inspection quarantine can release")
        val previous = snapshot.position(source)
            ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Original quarantine position is absent")
        requireSameUnit(previous.onHand, quantity)
        requireSerialQuantity(source, quantity)
        guard(quantity <= previous.onHand, InventoryFailure.INSUFFICIENT_STOCK, "Quarantine has insufficient custody")
        guard(previous.safety == StockSafety.CLEAR &&
            (previous.expiresAtEpochMillis == null || nowEpochMillis < previous.expiresAtEpochMillis),
            InventoryFailure.UNSAFE_STOCK, "Recall, expiry and count review cannot be cleared by inspection")
        if (source.serialId == null) guard(serialRegistration == null, InventoryFailure.INVALID_STATE,
            "Nonserialized inspection has no serial qualification")
        else {
            serialRegistration?.let { registration ->
                guard(registration.scope == snapshot.scope && registration.skuId == source.pool.skuId &&
                    registration.serialId == source.serialId && (previous.serialRegistrationId == null ||
                    previous.serialRegistrationId == registration.registrationId), InventoryFailure.INVALID_STATE,
                    "Registration must bind the exact retained serial")
            }
            val registrationId = previous.serialRegistrationId ?: serialRegistration?.registrationId
                ?: throw InventoryRejection(InventoryFailure.UNSAFE_STOCK, "Serial registration is still pending")
            if (previous.serialRegistrationId == null) work.positions.filter { it.key.serialId == source.serialId }.forEach { before ->
                guard(before.serialRegistrationId == null, InventoryFailure.INVALID_STATE, "Mixed serial registration state")
                val after = before.copy(serialRegistrationId = registrationId)
                work.setPosition(after)
                work.effects += InventoryEffect.SerialRegistrationConfirmed(before, after)
            }
        }
        val qualified = work.positions.first { it.key == source }
        val destination = source.copy(custody = StockCustody.RELEASED)
        val existing = work.positions.firstOrNull { it.key == destination }
        guard(existing == null || (existing.safety == StockSafety.CLEAR && existing.expiresAtEpochMillis == previous.expiresAtEpochMillis),
            InventoryFailure.UNSAFE_STOCK, "Released destination is not safe")
        work.claimSource(releaseId)
        work.requireBefore(previous.expiresAtEpochMillis)
        work.setPosition(qualified.copy(onHand = qualified.onHand - quantity))
        work.effects += InventoryEffect.Movement(releaseId, source, -quantity, StockMovementReason.QUARANTINE_RELEASE)
        work.setPosition(StockPosition(destination, (existing?.onHand ?: zeroLike(quantity)) + quantity,
            StockSafety.CLEAR, previous.expiresAtEpochMillis, qualified.serialRegistrationId))
        work.effects += InventoryEffect.Movement(releaseId, destination, quantity, StockMovementReason.QUARANTINE_RELEASE)
        // No valuation effect: ownership, pool quantity and carrying value are unchanged.
        return work.finish()
    }

    /**
     * Same-branch supplier-owned physical custody relocation. No owned pool or acquisition is
     * created. Owned released custody uses the distinct paired value-graph planner below,
     * never a loss plus a new purchase or a broadening of this legacy profile.
     */
    public fun transferSupplierCustody(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        sourceEventId: DomainId,
        destinationEventId: DomainId,
        source: StockPositionKey,
        destinationWarehouseId: DomainId,
        quantity: Quantity,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        scope(snapshot, source.pool)
        positive(quantity)
        guard(source.pool.ownership is StockOwnership.SupplierOwned && source.custody != StockCustody.RELEASED,
            InventoryFailure.UNSUPPORTED_LINEAGE, "Owned transfer needs linked valuation lineage")
        guard(source.pool.warehouseId != destinationWarehouseId && sourceEventId != destinationEventId,
            InventoryFailure.INVALID_STATE, "Distinct transfer endpoints and sources are required")
        val previous = snapshot.position(source)
            ?: throw InventoryRejection(InventoryFailure.INSUFFICIENT_STOCK, "Supplier custody is absent")
        requireSameUnit(previous.onHand, quantity)
        requireSerialQuantity(source, quantity)
        guard(quantity <= previous.onHand, InventoryFailure.INSUFFICIENT_STOCK, "Insufficient supplier custody")
        val destination = source.copy(pool = source.pool.copy(warehouseId = destinationWarehouseId))
        val existing = snapshot.position(destination)
        if (existing != null) {
            requireSameUnit(existing.onHand, quantity)
            guard(existing.safety == previous.safety && existing.expiresAtEpochMillis == previous.expiresAtEpochMillis,
                InventoryFailure.UNSAFE_STOCK, "Custody transfer cannot erase a safety disposition")
        }
        work.claimSource(sourceEventId)
        work.claimSource(destinationEventId)
        work.setPosition(previous.copy(onHand = previous.onHand - quantity))
        work.effects += InventoryEffect.Movement(sourceEventId, source, -quantity, StockMovementReason.WAREHOUSE_TRANSFER)
        work.setPosition(StockPosition(destination, (existing?.onHand ?: zeroLike(quantity)) + quantity,
            previous.safety, previous.expiresAtEpochMillis, previous.serialRegistrationId))
        work.effects += InventoryEffect.Movement(destinationEventId, destination, quantity, StockMovementReason.WAREHOUSE_TRANSFER)
        return work.finish()
    }

    /**
     * Same-branch owned RELEASED custody, without consuming or moving any reservation. Unsafe
     * stock stays unsafe at both coordinates; relocation is not inspection/release authority.
     * Quarantine relocation requires separate physical receipt-entitlement lineage and is not
     * implemented here. The adapter binds the permanent transfer document and both source IDs.
     */
    public fun transferReleasedOwned(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        transferId: DomainId,
        sourceEventId: DomainId,
        destinationEventId: DomainId,
        source: StockPositionKey,
        destinationWarehouseId: DomainId,
        quantity: Quantity,
        nowEpochMillis: Long,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        time(nowEpochMillis)
        scope(snapshot, source.pool)
        positive(quantity)
        requireSerialQuantity(source, quantity)
        guard(source.pool.ownership == StockOwnership.CompanyOwned && source.custody == StockCustody.RELEASED,
            InventoryFailure.UNSUPPORTED_LINEAGE, "Only owned released custody has this paired transfer lineage")
        guard(source.pool.warehouseId != destinationWarehouseId && sourceEventId != destinationEventId,
            InventoryFailure.INVALID_STATE, "Distinct transfer endpoints and sources are required")
        requireOwnedTransferCut(snapshot.pools)
        // Expiry facts and their collateral holds precede the paired movements, never disappear.
        work.markExpiredStock(nowEpochMillis)
        val previous = work.positions.firstOrNull { it.key == source }
            ?: throw InventoryRejection(InventoryFailure.INSUFFICIENT_STOCK, "Owned source custody is absent")
        requireSameUnit(previous.onHand, quantity)
        val claimed = work.reservations.filter { it.retainsStockClaim }.flatMap { it.allocations }
            .filter { it.position == source }.fold(zeroLike(quantity)) { sum, row -> sum + row.quantity }
        guard(claimed <= previous.onHand && quantity <= previous.onHand - claimed,
            InventoryFailure.INSUFFICIENT_STOCK, "Reserved and held custody cannot be transferred")
        val destination = source.copy(pool = source.pool.copy(warehouseId = destinationWarehouseId))
        val existing = work.positions.firstOrNull { it.key == destination }
        if (existing != null) {
            requireSameUnit(existing.onHand, quantity)
            guard(existing.safety == previous.safety && existing.expiresAtEpochMillis == previous.expiresAtEpochMillis &&
                existing.serialRegistrationId == previous.serialRegistrationId,
                InventoryFailure.UNSAFE_STOCK, "Transfer cannot replace destination safety or serial qualification")
        }
        val sourcePool = work.pools.firstOrNull { it.key == source.pool }
            ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Source valuation pool is absent")
        val destinationPool = work.pools.firstOrNull { it.key == destination.pool } ?: run {
            guard(work.positions.none { it.key.pool == destination.pool }, InventoryFailure.INVALID_STATE,
                "A present owned destination cannot have a missing value history")
            OwnedValuationPool(destination.pool, MovingAverageState.empty(quantity.unitId, quantity.scale, sourcePool.value.currency))
        }
        work.claimSource(sourceEventId)
        work.claimSource(destinationEventId)
        val (outgoing, incoming) = LinkedMovingAveragePlanner.transfer(sourcePool, destinationPool, transferId,
            sourceEventId, destinationEventId, quantity)
        work.setPool(OwnedValuationPool(source.pool, outgoing.next))
        work.effects += InventoryEffect.Valuation(source.pool, outgoing)
        work.setPosition(previous.copy(onHand = previous.onHand - quantity))
        work.effects += InventoryEffect.Movement(sourceEventId, source, -quantity, StockMovementReason.WAREHOUSE_TRANSFER, transferId)
        work.setPool(OwnedValuationPool(destination.pool, incoming.next))
        work.effects += InventoryEffect.Valuation(destination.pool, incoming)
        work.setPosition(StockPosition(destination, (existing?.onHand ?: zeroLike(quantity)) + quantity,
            previous.safety, previous.expiresAtEpochMillis, previous.serialRegistrationId))
        work.effects += InventoryEffect.Movement(destinationEventId, destination, quantity, StockMovementReason.WAREHOUSE_TRANSFER, transferId)
        return work.finish()
    }

    public fun reserve(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        reservationId: DomainId,
        binding: CollectionBinding,
        allocations: List<StockAllocation>,
        nowEpochMillis: Long,
        expiresAtEpochMillis: Long? = null,
    ): ReservationPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        time(nowEpochMillis)
        guard(expiresAtEpochMillis == null || expiresAtEpochMillis > nowEpochMillis,
            InventoryFailure.INVALID_STATE, "A new reservation must not already be expired")
        guard(snapshot.reservation(reservationId) == null, InventoryFailure.DUPLICATE_SOURCE, "Reservation already exists")
        validateAllocations(snapshot, allocations)
        val historicalIds = snapshot.reservations.flatMap { it.allocations }.map { it.allocationId }.toSet()
        guard(allocations.none { it.allocationId in historicalIds }, InventoryFailure.DUPLICATE_SOURCE, "Stock allocation identity was already used")
        allocations.groupBy { it.position }.forEach { (key, items) ->
            val position = snapshot.position(key) ?: throw InventoryRejection(InventoryFailure.INSUFFICIENT_STOCK, "Position is absent")
            guard(position.isSellableAt(nowEpochMillis), InventoryFailure.UNSAFE_STOCK, "Only released, unexpired owned stock can reserve")
            val quantity = items.fold(zeroLike(position.onHand)) { sum, item -> requireSameUnit(position.onHand, item.quantity); sum + item.quantity }
            guard(quantity <= snapshot.available(key, nowEpochMillis), InventoryFailure.INSUFFICIENT_STOCK, "Insufficient unreserved stock")
            work.requireBefore(position.expiresAtEpochMillis)
        }
        work.requireBefore(expiresAtEpochMillis)
        val reservation = StockReservation(reservationId, binding, allocations, Revision(1), ReservationStatus.RESERVED,
            expiresAtEpochMillis = expiresAtEpochMillis)
        work.setReservation(reservation)
        work.effects += InventoryEffect.ReservationRecorded(reservation)
        val plan = work.finish()
        return ReservationPlan(plan, reservationProof(plan, reservation, nowEpochMillis))
    }

    /** Call only with the related sales evidence in the same transaction. Never downgrades money to unpaid. */
    public fun recordCollectionExposure(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        reservationId: DomainId,
        expectedReservationRevision: Revision,
        exposure: CollectionExposure,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        val prior = activeReservation(snapshot, reservationId, expectedReservationRevision)
        guard(exposure != CollectionExposure.UNCOLLECTED || prior.binding.exposure == CollectionExposure.UNCOLLECTED,
            InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "Recorded/unknown money cannot be reset to unpaid")
        val updated = StockReservation(prior.reservationId, prior.binding.copy(exposure = exposure), prior.allocations,
            prior.revision.next(), prior.status, prior.holds, prior.expiresAtEpochMillis)
        work.setReservation(updated)
        work.effects += InventoryEffect.ReservationRecorded(updated)
        return work.finish()
    }

    public fun releaseReservation(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        reservationId: DomainId,
        expectedReservationRevision: Revision,
        resolution: CollectionResolutionReference? = null,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        val prior = activeReservation(snapshot, reservationId, expectedReservationRevision)
        if (prior.binding.exposure != CollectionExposure.UNCOLLECTED) {
            guard(resolution != null && resolution.orderId == prior.binding.orderId &&
                resolution.collectionId == prior.binding.collectionId,
                InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "Preserve and resolve the protected collection/obligation first")
        } else if (resolution != null) {
            guard(resolution.orderId == prior.binding.orderId && resolution.collectionId == prior.binding.collectionId,
                InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "Resolution belongs to a different collection")
        }
        val updated = StockReservation(prior.reservationId, prior.binding, prior.allocations, prior.revision.next(),
            ReservationStatus.RELEASED, prior.holds, prior.expiresAtEpochMillis)
        work.setReservation(updated)
        work.effects += InventoryEffect.ReservationReleased(updated, resolution)
        return work.finish()
    }

    /** Only known-uncollected leases auto-release; all paid/partial/unknown leases become holds. */
    public fun expireReservations(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        nowEpochMillis: Long,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        time(nowEpochMillis)
        work.markExpiredStock(nowEpochMillis)
        work.reservations.toList().filter { it.retainsStockClaim && it.expiresAtEpochMillis != null && it.expiresAtEpochMillis <= nowEpochMillis }
            .forEach { prior ->
                if (prior.binding.exposure == CollectionExposure.UNCOLLECTED) {
                    val released = StockReservation(prior.reservationId, prior.binding, prior.allocations, prior.revision.next(),
                        ReservationStatus.RELEASED, prior.holds, prior.expiresAtEpochMillis)
                    work.setReservation(released)
                    work.effects += InventoryEffect.ReservationReleased(released, null)
                } else work.hold(prior, listOf(ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED)))
            }
        return work.finish()
    }

    /** Recall all positions for the scoped SKU/lot, including zero stock and other warehouses. */
    public fun recallLot(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        skuId: DomainId,
        lotId: DomainId,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        val positions = work.positions.filter { it.key.pool.skuId == skuId && it.key.lotId == lotId }
        guard(positions.isNotEmpty(), InventoryFailure.INVALID_STATE, "Recall lot is absent from this complete local cut")
        positions.forEach { previous ->
            val updated = previous.copy(safety = StockSafety.RECALLED)
            work.setPosition(updated)
            work.effects += InventoryEffect.SafetyChanged(previous, updated)
            work.holdPosition(updated.key, ReservationHoldReason.RECALL)
        }
        return work.finish()
    }

    /** Revalidation without issue supports a paid-unsent reservation; commercial finalization stays separate. */
    public fun revalidateReservation(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        reservationId: DomainId,
        expectedReservationRevision: Revision,
        nowEpochMillis: Long,
    ): ReservationSafetyResult {
        val work = begin(snapshot, expectedRevision, operationId)
        time(nowEpochMillis)
        activeReservation(snapshot, reservationId, expectedReservationRevision)
        work.markExpiredStock(nowEpochMillis)
        val reservation = work.reservations.first { it.reservationId == reservationId }
        val holds = safetyHolds(work, reservation, nowEpochMillis)
        if (reservation.status == ReservationStatus.HELD || holds.isNotEmpty()) {
            work.hold(reservation, holds)
            return ReservationSafetyResult.Held(work.finish(), reservationId)
        }
        val checked = StockReservation(reservation.reservationId, reservation.binding, reservation.allocations,
            reservation.revision.next(), reservation.status, reservation.holds, reservation.expiresAtEpochMillis)
        work.setReservation(checked)
        work.requireReservationBefore(checked)
        work.effects += InventoryEffect.ReservationRecorded(checked)
        val plan = work.finish()
        return ReservationSafetyResult.Safe(plan, reservationProof(plan, checked, nowEpochMillis))
    }

    public fun issueReservation(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        reservationId: DomainId,
        expectedReservationRevision: Revision,
        issueId: DomainId,
        nowEpochMillis: Long,
        purpose: ValueIssuePurpose = ValueIssuePurpose.RETAIL,
        recipe: RecipeIssueRequest? = null,
    ): ReservationIssueResult {
        val work = begin(snapshot, expectedRevision, operationId)
        time(nowEpochMillis)
        guard(purpose == ValueIssuePurpose.RETAIL || purpose == ValueIssuePurpose.DIRECT_RECIPE,
            InventoryFailure.UNSUPPORTED_LINEAGE, "Reserved fulfillment supports retail or direct recipe consumption only")
        guard((purpose == ValueIssuePurpose.DIRECT_RECIPE) == (recipe != null),
            InventoryFailure.INVALID_STATE, "Recipe issue must pin its recipe and required stations")
        activeReservation(snapshot, reservationId, expectedReservationRevision)
        work.markExpiredStock(nowEpochMillis)
        val reservation = work.reservations.first { it.reservationId == reservationId }
        guard(purpose == ValueIssuePurpose.RETAIL || reservation.allocations.none { it.position.serialId != null },
            InventoryFailure.UNSUPPORTED_LINEAGE, "Serialized stock supports retail issue, not recipe consumption")
        val holds = safetyHolds(work, reservation, nowEpochMillis)
        if (reservation.status == ReservationStatus.HELD || holds.isNotEmpty()) {
            work.hold(reservation, holds)
            return ReservationIssueResult.Held(work.finish(), reservationId)
        }
        work.claimSource(issueId)
        work.requireReservationBefore(reservation)
        val costed = mutableListOf<IssuedStockAllocation>()
        reservation.allocations.groupBy { it.position.pool }.forEach { (key, allocations) ->
            val pool = work.pools.first { it.key == key }
            val valuePlan = MovingAveragePlanner.issue(pool.value, pool.value.revision, issueId,
                allocations.map { ValueSliceRequest(it.allocationId, it.quantity) }, purpose)
            work.setPool(OwnedValuationPool(key, valuePlan.next))
            work.effects += InventoryEffect.Valuation(key, valuePlan)
            val values = (valuePlan.fact as ValuationFact.Issue).slices.associateBy { it.allocationId }
            allocations.forEach { allocation ->
                val position = work.positions.first { it.key == allocation.position }
                work.setPosition(position.copy(onHand = position.onHand - allocation.quantity))
                work.effects += InventoryEffect.Movement(issueId, allocation.position, -allocation.quantity,
                    StockMovementReason.ISSUE, allocation.allocationId)
                costed += IssuedStockAllocation(allocation, values.getValue(allocation.allocationId).originalValue, zeroLike(allocation.quantity))
            }
        }
        val issuedReservation = StockReservation(reservation.reservationId, reservation.binding, reservation.allocations,
            reservation.revision.next(), ReservationStatus.ISSUED, reservation.holds, reservation.expiresAtEpochMillis)
        val kitchen = recipe?.let { KitchenIssueState(it.recipeSnapshotId, it.requiredStationIds, emptyList(), KitchenDisposition.ACTIVE) }
        val issue = StockIssue(issueId, reservationId, reservation.binding, costed, purpose, Revision(1), kitchen)
        check(issue.originalValue.minor >= 0) // Validate the aggregate posted cost boundary before exposing a plan.
        work.setReservation(issuedReservation)
        work.setIssue(issue)
        work.effects += InventoryEffect.ReservationRecorded(issuedReservation)
        work.effects += InventoryEffect.IssueRecorded(issue)
        val plan = work.finish()
        return ReservationIssueResult.Issued(plan, IssueProof(snapshot.scope, issueId, reservationId, purpose, reservation.binding,
            costed, snapshot.revision, issuedReservation.revision, nowEpochMillis, operationId, plan.mustCommitBeforeEpochMillis))
    }

    /** Captured-cut shortage only. Subsequent movements require a new reconciled cut/recount, not an overwrite. */
    public fun recordCountShortage(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        countId: DomainId,
        key: StockPositionKey,
        capturedRevision: Revision,
        counted: Quantity,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        requireRevision(snapshot.revision, capturedRevision)
        work.claimSource(countId)
        scope(snapshot, key.pool)
        val position = snapshot.position(key) ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Count position is missing")
        requireSameUnit(position.onHand, counted)
        requireSerialQuantity(key, counted, allowZero = true)
        guard(counted.scaled >= 0 && counted <= position.onHand, InventoryFailure.INVALID_QUANTITY,
            "This bounded count workflow accepts nonnegative shortage quantities only")
        val shortage = position.onHand - counted
        if (shortage.scaled != 0L) {
            if (key.pool.ownership == StockOwnership.CompanyOwned) {
                val pool = work.pools.first { it.key == key.pool }
                val valuePlan = MovingAveragePlanner.issue(pool.value, pool.value.revision, countId,
                    listOf(ValueSliceRequest(countId, shortage)), ValueIssuePurpose.COUNT_LOSS)
                work.setPool(OwnedValuationPool(key.pool, valuePlan.next))
                work.effects += InventoryEffect.Valuation(key.pool, valuePlan)
            }
            val hasClaims = work.reservations.any { it.retainsStockClaim && it.allocations.any { allocation -> allocation.position == key } }
            val safety = if (hasClaims && position.safety == StockSafety.CLEAR) StockSafety.COUNT_REVIEW else position.safety
            val updated = position.copy(onHand = counted, safety = safety)
            work.setPosition(updated)
            work.effects += InventoryEffect.Movement(countId, key, -shortage, StockMovementReason.COUNT_SHORTAGE)
            if (position.safety != safety) work.effects += InventoryEffect.SafetyChanged(position, updated)
            work.holdPosition(key, ReservationHoldReason.COUNT_SHORTAGE)
        }
        work.effects += InventoryEffect.CountReconciled(countId, key, capturedRevision, counted)
        return work.finish()
    }

    /**
     * Captured-cut count gain using a separately approved value for the additional units only.
     * The adapter must bind and consume the exact live count/value approval in the same proposal.
     * Existing recall/quarantine/count holds are retained; a count is not an inspection release.
     */
    public fun recordCountSurplus(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        countId: DomainId,
        key: StockPositionKey,
        capturedRevision: Revision,
        counted: Quantity,
        approvedSurplusValue: Money,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        requireRevision(snapshot.revision, capturedRevision)
        scope(snapshot, key.pool)
        guard(key.pool.ownership == StockOwnership.CompanyOwned && key.custody != StockCustody.REJECTED_HELD,
            InventoryFailure.WRONG_OWNERSHIP, "An owned count gain cannot manufacture supplier custody")
        val position = snapshot.position(key)
            ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "A gain needs the captured identified position")
        guard(key.serialId == null, InventoryFailure.UNSUPPORTED_LINEAGE,
            "Serial rediscovery needs a distinct reconciliation, not a count gain or reused acquisition")
        requireSameUnit(position.onHand, counted)
        guard(counted > position.onHand, InventoryFailure.INVALID_QUANTITY, "Count gain must add identified units")
        work.claimSource(countId)
        val gain = counted - position.onHand
        val pool = snapshot.pool(key.pool)
            ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Captured value pool is missing")
        val valuation = MovingAveragePlanner.countSurplus(pool.value, pool.value.revision, countId, gain, approvedSurplusValue)
        work.setPool(OwnedValuationPool(key.pool, valuation.next))
        work.effects += InventoryEffect.Valuation(key.pool, valuation)
        work.setPosition(position.copy(onHand = counted))
        work.effects += InventoryEffect.Movement(countId, key, gain, StockMovementReason.COUNT_SURPLUS)
        work.effects += InventoryEffect.CountReconciled(countId, key, capturedRevision, counted)
        return work.finish()
    }

    public fun adjustReceiptCost(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        poolKey: StockPoolKey,
        expectedPoolRevision: Revision,
        adjustmentId: DomainId,
        sourceId: DomainId,
        receiptId: DomainId,
        targetDelta: Money,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        scope(snapshot, poolKey)
        work.claimSource(adjustmentId)
        guard(poolKey.ownership == StockOwnership.CompanyOwned, InventoryFailure.WRONG_OWNERSHIP, "Custody has no owned landed cost")
        guard(snapshot.pools.all { candidate ->
            candidate.value.costTargets.none { target -> target.sourceId == sourceId &&
                (candidate.key != poolKey || target.receiptId != receiptId) }
        }, InventoryFailure.INVALID_ORIGINAL_ALLOCATION,
            "One retained acquisition-charge source cannot be rebound to another pool or receipt")
        val pool = snapshot.pool(poolKey) ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Pool is missing")
        requireRevision(pool.value.revision, expectedPoolRevision)
        val values = if (pool.value.hasTransferLineage) LinkedMovingAveragePlanner.correct(snapshot.pools, poolKey,
            adjustmentId, sourceId, receiptId, targetDelta)
        else listOf(poolKey to MovingAveragePlanner.adjustReceiptCost(pool.value, expectedPoolRevision,
            adjustmentId, sourceId, receiptId, targetDelta))
        for ((key, valuePlan) in values) {
            work.setPool(OwnedValuationPool(key, valuePlan.next))
            work.effects += InventoryEffect.Valuation(key, valuePlan)
        }
        return work.finish()
    }

    /** Inventory inspection disposition only: customer return/refund entitlement is a separate atomic dependency. */
    public fun restoreOriginalRetail(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        restorationId: DomainId,
        issueId: DomainId,
        expectedIssueRevision: Revision,
        restorations: List<StockRestoration>,
        nowEpochMillis: Long,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        val issue = findIssue(snapshot, issueId, expectedIssueRevision)
        guard(issue.purpose == ValueIssuePurpose.RETAIL, InventoryFailure.INVALID_ORIGINAL_ALLOCATION,
            "Recipe ingredients require verified before-start cancellation, not a food refund")
        restore(work, issue, restorationId, restorations, nowEpochMillis)
        return work.finish()
    }

    public fun acknowledgeKitchenStart(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        issueId: DomainId,
        expectedIssueRevision: Revision,
        stationId: DomainId,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        val issue = findIssue(snapshot, issueId, expectedIssueRevision)
        val kitchen = issue.kitchen ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Issue is not a recipe")
        guard(kitchen.disposition == KitchenDisposition.ACTIVE && stationId in kitchen.requiredStationIds &&
            stationId !in kitchen.startedStationIds, InventoryFailure.INVALID_STATE, "Kitchen station cannot start this issue")
        val updated = copyIssue(issue, revision = issue.revision.next(), kitchen = KitchenIssueState(kitchen.recipeSnapshotId,
            kitchen.requiredStationIds, kitchen.startedStationIds + stationId, kitchen.disposition))
        work.setIssue(updated)
        work.effects += InventoryEffect.IssueRecorded(updated)
        return work.finish()
    }

    /** Every required station must confirm; a print job or absence of a start message is not confirmation. */
    public fun cancelRecipeBeforeStart(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        restorationId: DomainId,
        confirmation: KitchenCancellationConfirmation,
        destinations: List<StockRestoration>,
        nowEpochMillis: Long,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        val issue = findIssue(snapshot, confirmation.issueId, confirmation.issueRevision)
        val kitchen = issue.kitchen ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Issue is not a recipe")
        guard(kitchen.disposition == KitchenDisposition.ACTIVE, InventoryFailure.INVALID_STATE, "Recipe already has a disposition")
        guard(kitchen.startedStationIds.isEmpty(), InventoryFailure.KITCHEN_ALREADY_STARTED, "Started work cannot restore ingredients")
        guard(confirmation.confirmedStationIds.size == kitchen.requiredStationIds.size &&
            confirmation.confirmedStationIds.toSet() == kitchen.requiredStationIds.toSet(),
            InventoryFailure.KITCHEN_CONFIRMATION_REQUIRED, "All required stations must confirm unstarted cancellation")
        guard(destinations.size == issue.allocations.size && destinations.map { it.allocationId }.toSet() ==
            issue.allocations.map { it.allocation.allocationId }.toSet() && destinations.all { returned ->
                val original = issue.allocations.first { it.allocation.allocationId == returned.allocationId }
                original.restoredQuantity.scaled == 0L && returned.quantity == original.allocation.quantity
            }, InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Bounded recipe cancellation restores the entire original batch exactly once")
        restore(work, issue, restorationId, destinations, nowEpochMillis,
            KitchenIssueState(kitchen.recipeSnapshotId, kitchen.requiredStationIds,
                emptyList(), KitchenDisposition.CANCELLED_BEFORE_START))
        work.effects += InventoryEffect.KitchenConfirmed(confirmation)
        return work.finish()
    }

    public fun cancelRecipeAfterStart(
        snapshot: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        issueId: DomainId,
        expectedIssueRevision: Revision,
        reasonId: DomainId,
    ): InventoryPlan {
        val work = begin(snapshot, expectedRevision, operationId)
        val issue = findIssue(snapshot, issueId, expectedIssueRevision)
        val kitchen = issue.kitchen ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Issue is not a recipe")
        guard(kitchen.disposition == KitchenDisposition.ACTIVE && kitchen.startedStationIds.isNotEmpty(),
            InventoryFailure.INVALID_STATE, "After-start waste requires actual acknowledged start and no prior disposition")
        val updated = copyIssue(issue, revision = issue.revision.next(), kitchen = KitchenIssueState(kitchen.recipeSnapshotId,
            kitchen.requiredStationIds, kitchen.startedStationIds, KitchenDisposition.WASTE_AFTER_START))
        work.setIssue(updated)
        // Deliberately no stock/valuation movement: classify the already-issued cost once.
        work.effects += InventoryEffect.IssueRecorded(updated)
        work.effects += InventoryEffect.WasteClassified(issueId, issue.originalValue, reasonId)
        return work.finish()
    }

    private fun restore(work: WorkingInventory, issue: StockIssue, restorationId: DomainId,
        restorations: List<StockRestoration>, nowEpochMillis: Long, kitchen: KitchenIssueState? = issue.kitchen) {
        time(nowEpochMillis)
        // Observing expiry on a returned slice is an observation about existing stock too.
        // Persist it on the released source/other positions before a backward clock can resell them.
        work.markExpiredStock(nowEpochMillis)
        work.claimSource(restorationId)
        guard(restorations.isNotEmpty() && restorations.size <= MAX_ALLOCATIONS &&
            restorations.map { it.allocationId }.toSet().size == restorations.size,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Unique bounded original restoration allocations required")
        val byId = issue.allocations.associateBy { it.allocation.allocationId }
        restorations.forEach { restoration ->
            positive(restoration.quantity)
            val original = byId[restoration.allocationId]
                ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Allocation does not belong to the original issue")
            requireSameUnit(original.allocation.quantity, restoration.quantity)
            guard(original.restoredQuantity + restoration.quantity <= original.allocation.quantity,
                InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original allocation was already restored")
            guard(restoration.destination.pool == original.allocation.position.pool &&
                restoration.destination.lotId == original.allocation.position.lotId &&
                restoration.destination.serialId == original.allocation.position.serialId,
                InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original return must retain SKU/warehouse/ownership/lot/serial identity")
            requireSerialQuantity(restoration.destination, restoration.quantity)
            if (restoration.destination.serialId != null) guard(work.positions.none {
                it.key.serialId == restoration.destination.serialId && it.onHand.scaled != 0L
            }, InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "The original sold serial is already in local custody")
            val source = work.positions.first { it.key == original.allocation.position }
            guard(restoration.destination.custody == StockCustody.QUARANTINE ||
                (restoration.destination.custody == StockCustody.RELEASED && source.isSellableAt(nowEpochMillis)),
                InventoryFailure.UNSAFE_STOCK, "Unsafe originals must go to quarantine, not sellable stock")
            if (restoration.destination.custody == StockCustody.RELEASED) work.requireBefore(source.expiresAtEpochMillis)
        }
        restorations.groupBy { it.destination.pool }.forEach { (key, items) ->
            val pool = work.pools.first { it.key == key }
            val valuePlan = MovingAveragePlanner.restoreOriginal(pool.value, pool.value.revision, restorationId,
                items.map { ValueRestorationRequest(issue.issueId, it.allocationId, it.quantity) })
            work.setPool(OwnedValuationPool(key, valuePlan.next))
            work.effects += InventoryEffect.Valuation(key, valuePlan)
            items.forEach { item ->
                val original = byId.getValue(item.allocationId)
                val source = work.positions.first { it.key == original.allocation.position }
                val previous = work.positions.firstOrNull { it.key == item.destination }
                val safety = if (source.expiresAtEpochMillis != null && nowEpochMillis >= source.expiresAtEpochMillis &&
                    source.safety == StockSafety.CLEAR) StockSafety.EXPIRED else source.safety
                val position = StockPosition(item.destination, (previous?.onHand ?: zeroLike(item.quantity)) + item.quantity,
                    previous?.safety?.takeUnless { it == StockSafety.CLEAR } ?: safety, source.expiresAtEpochMillis, source.serialRegistrationId)
                work.setPosition(position)
                work.effects += InventoryEffect.Movement(restorationId, item.destination, item.quantity,
                    StockMovementReason.ORIGINAL_RETURN, item.allocationId)
            }
        }
        val returned = restorations.associateBy { it.allocationId }
        val allocations = issue.allocations.map { original ->
            val restoration = returned[original.allocation.allocationId]
            if (restoration == null) original else original.copy(restoredQuantity = original.restoredQuantity + restoration.quantity)
        }
        val updated = copyIssue(issue, allocations = allocations, revision = issue.revision.next(), kitchen = kitchen)
        work.setIssue(updated)
        work.effects += InventoryEffect.IssueRecorded(updated)
    }

    private fun begin(snapshot: InventorySnapshot, expectedRevision: Revision, operationId: OperationId): WorkingInventory {
        requireRevision(snapshot.revision, expectedRevision)
        guard(operationId !in snapshot.operationIds, InventoryFailure.DUPLICATE_SOURCE,
            "Recover the persisted operation result rather than replanning a duplicate")
        guard(snapshot.operationIds.size < MAX_OPERATIONS, InventoryFailure.BOUND_EXCEEDED, "Bounded inventory cut exhausted")
        return WorkingInventory(snapshot, operationId)
    }

    private fun activeReservation(snapshot: InventorySnapshot, id: DomainId, revision: Revision): StockReservation {
        val reservation = snapshot.reservation(id)
            ?: throw InventoryRejection(InventoryFailure.RESERVATION_NOT_ACTIVE, "Reservation is missing")
        requireRevision(reservation.revision, revision)
        guard(reservation.retainsStockClaim, InventoryFailure.RESERVATION_NOT_ACTIVE, "Reservation has already issued or released")
        return reservation
    }

    private fun findIssue(snapshot: InventorySnapshot, id: DomainId, revision: Revision): StockIssue {
        val issue = snapshot.issue(id) ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original issue is missing")
        requireRevision(issue.revision, revision)
        return issue
    }

    private fun validateAllocations(snapshot: InventorySnapshot, allocations: List<StockAllocation>) {
        guard(allocations.isNotEmpty() && allocations.size <= MAX_ALLOCATIONS &&
            allocations.map { it.allocationId }.toSet().size == allocations.size,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Unique nonempty bounded allocations required")
        allocations.forEach {
            scope(snapshot, it.position.pool)
            guard(it.position.pool.ownership == StockOwnership.CompanyOwned,
                InventoryFailure.WRONG_OWNERSHIP, "Supplier custody cannot fulfill a sale")
        }
    }

    private fun safetyHolds(work: WorkingInventory, reservation: StockReservation, now: Long): List<ReservationHold> {
        val holds = mutableListOf<ReservationHold>()
        if (reservation.expiresAtEpochMillis != null && reservation.expiresAtEpochMillis <= now) {
            holds += ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED)
        }
        reservation.allocations.groupBy { it.position }.forEach { (key, allocations) ->
            val position = work.positions.first { it.key == key }
            val own = allocations.fold(zeroLike(position.onHand)) { total, item -> total + item.quantity }
            val others = work.reservations.filter { it.retainsStockClaim && it.reservationId != reservation.reservationId }
                .flatMap { it.allocations }.filter { it.position == key }
                .fold(zeroLike(position.onHand)) { total, item -> total + item.quantity }
            val reason = when {
                position.safety == StockSafety.RECALLED -> ReservationHoldReason.RECALL
                position.safety == StockSafety.EXPIRED || (position.expiresAtEpochMillis != null && now >= position.expiresAtEpochMillis) -> ReservationHoldReason.EXPIRY
                position.safety == StockSafety.COUNT_REVIEW -> ReservationHoldReason.COUNT_SHORTAGE
                !position.isSellableAt(now) || own + others > position.onHand -> ReservationHoldReason.UNAVAILABLE
                else -> null
            }
            if (reason != null) holds += ReservationHold(reason, key)
        }
        return holds
    }

    private fun reservationProof(plan: InventoryPlan, reservation: StockReservation, now: Long): ReservationProof =
        ReservationProof(plan.sourceSnapshot.scope, reservation.reservationId, reservation.binding, reservation.allocations,
            plan.requiredInventoryRevision, reservation.revision, now, plan.operationId, plan.mustCommitBeforeEpochMillis)

    private fun scope(snapshot: InventorySnapshot, pool: StockPoolKey) {
        guard(snapshot.scope == pool.scope, InventoryFailure.WRONG_SCOPE, "Stock belongs to another company/branch")
    }

    private fun time(now: Long) { guard(now >= 0, InventoryFailure.INVALID_STATE, "Use an authoritative nonnegative time") }
}

private fun sameLot(left: StockPositionKey, right: StockPositionKey): Boolean =
    left.pool.scope == right.pool.scope && left.pool.skuId == right.pool.skuId && left.lotId == right.lotId

private fun copyIssue(issue: StockIssue, allocations: List<IssuedStockAllocation> = issue.allocations,
    revision: Revision = issue.revision, kitchen: KitchenIssueState? = issue.kitchen): StockIssue =
    StockIssue(issue.issueId, issue.reservationId, issue.binding, allocations, issue.purpose, revision, kitchen)

private class WorkingInventory(val source: InventorySnapshot, val operationId: OperationId) {
    val positions = source.positions.toMutableList()
    val pools = source.pools.toMutableList()
    val reservations = source.reservations.toMutableList()
    val issues = source.issues.toMutableList()
    val sourceIds = source.sourceIds.toMutableList()
    val effects = mutableListOf<InventoryEffect>()
    private var deadline: Long? = null

    fun requireBefore(exclusive: Long?) {
        if (exclusive != null) deadline = deadline?.let { minOf(it, exclusive) } ?: exclusive
    }

    fun requireReservationBefore(reservation: StockReservation) {
        requireBefore(reservation.expiresAtEpochMillis)
        reservation.allocations.forEach { allocation ->
            requireBefore(positions.first { it.key == allocation.position }.expiresAtEpochMillis)
        }
    }

    fun claimSource(id: DomainId) {
        guard(id !in sourceIds, InventoryFailure.DUPLICATE_SOURCE, "Movement/correction source was already consumed")
        sourceIds += id
    }

    fun setPosition(value: StockPosition) { positions.removeAll { it.key == value.key }; positions += value }
    fun setPool(value: OwnedValuationPool) { pools.removeAll { it.key == value.key }; pools += value }
    fun setReservation(value: StockReservation) { reservations.removeAll { it.reservationId == value.reservationId }; reservations += value }
    fun setIssue(value: StockIssue) { issues.removeAll { it.issueId == value.issueId }; issues += value }

    fun hold(prior: StockReservation, additional: List<ReservationHold>) {
        val holds = (prior.holds + additional).distinct()
        guard(holds.isNotEmpty(), InventoryFailure.INVALID_STATE, "A fulfillment hold needs a reason")
        val updated = StockReservation(prior.reservationId, prior.binding, prior.allocations, prior.revision.next(),
            ReservationStatus.HELD, holds, prior.expiresAtEpochMillis)
        setReservation(updated)
        effects += InventoryEffect.ReservationRecorded(updated)
    }

    fun holdPosition(key: StockPositionKey, reason: ReservationHoldReason) {
        reservations.toList().filter { it.retainsStockClaim && it.allocations.any { allocation -> allocation.position == key } }
            .forEach { hold(it, listOf(ReservationHold(reason, key))) }
    }

    fun markExpiredStock(now: Long) {
        positions.toList().filter { it.expiresAtEpochMillis != null && now >= it.expiresAtEpochMillis && it.safety == StockSafety.CLEAR }
            .forEach { previous ->
                val updated = previous.copy(safety = StockSafety.EXPIRED)
                setPosition(updated)
                effects += InventoryEffect.SafetyChanged(previous, updated)
                holdPosition(updated.key, ReservationHoldReason.EXPIRY)
            }
    }

    fun finish(): InventoryPlan {
        guard(positions.size <= InventoryPlanner.MAX_POSITIONS && pools.size <= InventoryPlanner.MAX_POOLS &&
            reservations.size <= InventoryPlanner.MAX_RESERVATIONS && issues.size <= InventoryPlanner.MAX_ISSUES &&
            sourceIds.size <= InventoryPlanner.MAX_OPERATIONS * InventoryPlanner.MAX_ALLOCATIONS,
            InventoryFailure.BOUND_EXCEEDED, "Bounded inventory state exceeded")
        guard(positions.all { it.key.pool.scope == source.scope } && pools.all { it.key.scope == source.scope },
            InventoryFailure.WRONG_SCOPE, "Mixed inventory scope")
        positions.groupBy { it.key.pool.skuId }.values.forEach { sameSku ->
            val first = sameSku.first().onHand
            sameSku.forEach { requireSameUnit(first, it.onHand) }
        }
        requireSerialCut(positions, reservations)
        requireOwnedTransferCut(pools)
        pools.forEach { pool ->
            val quantity = positions.filter { it.key.pool == pool.key }.fold(Quantity(pool.value.unitId, 0, pool.value.scale)) { sum, item ->
                requireSameUnit(sum, item.onHand); sum + item.onHand
            }
            check(quantity == pool.value.onHand) { "Physical owned custody and valuation quantity differ" }
        }
        positions.forEach { position ->
            if (position.key.pool.ownership == StockOwnership.CompanyOwned) check(pools.any { it.key == position.key.pool })
            val claims = reservations.filter { it.retainsStockClaim && it.allocations.any { allocation -> allocation.position == position.key } }
            val claimed = claims.flatMap { it.allocations }.filter { it.position == position.key }
                .fold(zeroLike(position.onHand)) { sum, allocation -> sum + allocation.quantity }
            guard(claimed <= position.onHand || claims.all { it.status == ReservationStatus.HELD },
                InventoryFailure.INVALID_STATE, "Short claims must remain explicit safety holds")
        }
        val next = InventorySnapshot(source.scope, source.revision.next(), positions, pools, reservations, issues,
            source.operationIds + operationId, sourceIds)
        return InventoryPlan(operationId, source, next, effects, deadline)
    }
}

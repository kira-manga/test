package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

internal fun validateInventoryPostingBinding(binding: InventoryPostingBinding) {
    inventoryPostingCheck(binding.resultingInventoryRevision == binding.requiredInventoryRevision.next(),
        InventoryPostingFailure.BINDING_MISMATCH)
    inventoryPostingCheck(binding.resultingInventoryRevision.value <= InventoryPlanner.MAX_OPERATIONS,
        InventoryPostingFailure.TOO_LARGE)
}

/** State-shape restriction, NOT proof that its receipts, safety events or reservations have provenance. */
internal fun validateInventoryPostingProfile(snapshot: InventorySnapshot) {
    inventoryPostingCheck(!hasSerialInventory(snapshot), InventoryPostingFailure.UNSUPPORTED_STATE)
    snapshot.positions.forEach { postingPosition(it, snapshot.scope) }
    snapshot.reservations.forEach { postingReservation(it, snapshot.scope, snapshot.revision) }
    snapshot.issues.forEach { postingIssue(it, snapshot.scope) }
    val factIds = mutableSetOf<DomainId>()
    for (pool in snapshot.pools) {
        postingPool(pool.key, snapshot.scope)
        postingValueProfile(pool.value)
        pool.value.facts.forEach { factIds += it.factId }
    }
    // Source-less reserve/hold operations are legitimate. Unknown source IDs are not silently ignored.
    inventoryPostingCheck(snapshot.sourceIds.toSet() == factIds, InventoryPostingFailure.UNSUPPORTED_STATE)
}

private fun postingValueProfile(value: MovingAverageState) {
    // Also guards intermediate same-pool states, which are not themselves the final whole cut.
    inventoryPostingCheck(value.facts.size <= MovingAveragePlanner.MAX_EVENTS &&
        value.eventIds.size <= MovingAveragePlanner.MAX_EVENTS &&
        value.linkedCorrectionBalances.size <= InventorySnapshotCodec.MAX_ARRAY_ENTRIES, InventoryPostingFailure.TOO_LARGE)
    inventoryPostingCheck(!value.hasTransferLineage && value.transferTargets.isEmpty() && value.costTargets.isEmpty() && value.adjustments.isEmpty() &&
        value.linkedCorrectionBalances.all { it.value.minor == 0L } && value.facts.all {
            (it is ValuationFact.Receipt && it.origin == ReceiptValueOrigin.PURCHASE) ||
                (it is ValuationFact.Issue && it.purpose == ValueIssuePurpose.RETAIL)
        }, InventoryPostingFailure.UNSUPPORTED_STATE)
}

internal fun projectInventoryPostingEffect(effect: InventoryEffect): InventoryPostingEffect = when (effect) {
    is InventoryEffect.Movement -> InventoryPostingEffect.Movement(effect.sourceId, effect.position,
        effect.quantityDelta, effect.reason, effect.sourceAllocationId)
    is InventoryEffect.Valuation -> {
        inventoryPostingCheck(effect.plan.linkedAdjustment == null, InventoryPostingFailure.UNSUPPORTED_STATE)
        val fact = effect.plan.fact ?: throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
        InventoryPostingEffect.Valuation(effect.pool, effect.plan.requiredRevision, effect.plan.next.revision, fact,
            effect.plan.quantityDelta, effect.plan.originalValueDelta, effect.plan.carryingValueDelta)
    }
    is InventoryEffect.ReservationRecorded -> InventoryPostingEffect.ReservationRecorded(effect.reservation)
    is InventoryEffect.SafetyChanged -> InventoryPostingEffect.SafetyChanged(effect.before, effect.after)
    is InventoryEffect.IssueRecorded -> InventoryPostingEffect.IssueRecorded(effect.issue)
    is InventoryEffect.SerialRegistrationConfirmed, is InventoryEffect.ReservationReleased, is InventoryEffect.CountReconciled,
    is InventoryEffect.KitchenConfirmed, is InventoryEffect.WasteClassified ->
        throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
}

/** Only intrinsic batch consistency. A decoded batch alone cannot validate a prior or current cut. */
internal fun validateInventoryPostingBatch(batch: InventoryPostingBatch) {
    validateInventoryPostingBinding(batch.binding)
    val scope = batch.binding.scope
    val poolRevisions = mutableMapOf<StockPoolKey, Revision>()
    val poolFacts = mutableSetOf<Pair<StockPoolKey, DomainId>>()
    val reservations = mutableMapOf<DomainId, StockReservation>()
    val expiredPositions = mutableSetOf<StockPositionKey>()
    for (effect in batch.effects) when (effect) {
        is InventoryPostingEffect.Valuation -> {
            postingValuation(effect, scope)
            poolRevisions[effect.pool]?.let { inventoryPostingCheck(effect.requiredPoolRevision == it) }
            poolRevisions[effect.pool] = effect.resultingPoolRevision
            inventoryPostingCheck(poolFacts.add(effect.pool to effect.fact.factId))
        }
        is InventoryPostingEffect.Movement -> {
            postingPositionKey(effect.position, scope)
            inventoryPostingCheck(when (effect.reason) {
                StockMovementReason.RECEIPT -> effect.quantityDelta.scaled > 0 && effect.sourceAllocationId == null
                StockMovementReason.ISSUE -> effect.quantityDelta.scaled < 0 && effect.sourceAllocationId != null
                else -> false
            }, InventoryPostingFailure.UNSUPPORTED_STATE)
        }
        is InventoryPostingEffect.ReservationRecorded -> {
            postingReservation(effect.reservation, scope, batch.binding.resultingInventoryRevision)
            reservations[effect.reservation.reservationId]?.let { prior -> postingReservationAdvance(prior, effect.reservation) }
            reservations[effect.reservation.reservationId] = effect.reservation
        }
        is InventoryPostingEffect.SafetyChanged -> {
            postingSafety(effect, scope)
            inventoryPostingCheck(expiredPositions.add(effect.before.key))
        }
        is InventoryPostingEffect.IssueRecorded -> postingIssue(effect.issue, scope)
    }
    validatePostingEffectOrder(batch.effects)
}

private fun postingPool(key: StockPoolKey, scope: InventoryScope) {
    inventoryPostingCheck(key.scope == scope, InventoryPostingFailure.BINDING_MISMATCH)
    inventoryPostingCheck(key.ownership == StockOwnership.CompanyOwned, InventoryPostingFailure.UNSUPPORTED_STATE)
}

private fun postingPositionKey(key: StockPositionKey, scope: InventoryScope) {
    postingPool(key.pool, scope)
    inventoryPostingCheck(key.custody == StockCustody.RELEASED && key.serialId == null, InventoryPostingFailure.UNSUPPORTED_STATE)
}

private fun postingPosition(position: StockPosition, scope: InventoryScope) {
    postingPositionKey(position.key, scope)
    inventoryPostingCheck(position.safety == StockSafety.CLEAR || position.safety == StockSafety.EXPIRED,
        InventoryPostingFailure.UNSUPPORTED_STATE)
    inventoryPostingCheck(position.onHand.scaled >= 0 &&
        (position.expiresAtEpochMillis == null || (position.expiresAtEpochMillis >= 0 && position.key.lotId != null)) &&
        (position.safety != StockSafety.EXPIRED || position.expiresAtEpochMillis != null))
}

private fun postingSafety(effect: InventoryPostingEffect.SafetyChanged, scope: InventoryScope) {
    postingPosition(effect.before, scope); postingPosition(effect.after, scope)
    inventoryPostingCheck(effect.before.safety == StockSafety.CLEAR && effect.after.safety == StockSafety.EXPIRED,
        InventoryPostingFailure.UNSUPPORTED_STATE)
    inventoryPostingCheck(effect.before.key == effect.after.key && effect.before.onHand == effect.after.onHand &&
        effect.before.expiresAtEpochMillis != null && effect.before.expiresAtEpochMillis == effect.after.expiresAtEpochMillis)
}

private fun postingReservation(reservation: StockReservation, scope: InventoryScope, inventoryRevision: Revision) {
    inventoryPostingCheck(reservation.revision.value in 1..inventoryRevision.value * (InventoryPlanner.MAX_ALLOCATIONS + 2) &&
        reservation.allocations.isNotEmpty() && reservation.allocations.size <= InventoryPlanner.MAX_ALLOCATIONS &&
        reservation.allocations.map { it.allocationId }.distinct().size == reservation.allocations.size &&
        (reservation.expiresAtEpochMillis == null || reservation.expiresAtEpochMillis > 0) &&
        reservation.holds.distinct().size == reservation.holds.size)
    inventoryPostingCheck(reservation.status != ReservationStatus.RELEASED, InventoryPostingFailure.UNSUPPORTED_STATE)
    inventoryPostingCheck(when (reservation.status) {
        ReservationStatus.RESERVED -> reservation.holds.isEmpty()
        ReservationStatus.HELD -> reservation.holds.isNotEmpty() && reservation.revision.value >= 2
        ReservationStatus.ISSUED -> reservation.holds.isEmpty() && reservation.revision.value >= 2
        ReservationStatus.RELEASED -> false
    })
    reservation.allocations.forEach { postingPositionKey(it.position, scope); inventoryPostingCheck(it.quantity.scaled > 0) }
    for (hold in reservation.holds) when (hold.reason) {
        ReservationHoldReason.EXPIRY -> inventoryPostingCheck(hold.position != null && reservation.allocations.any { it.position == hold.position })
        ReservationHoldReason.RESERVATION_EXPIRED -> inventoryPostingCheck(hold.position == null && reservation.expiresAtEpochMillis != null)
        else -> throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
    }
}

private fun postingReservationAdvance(before: StockReservation, after: StockReservation) {
    inventoryPostingCheck(before.retainsStockClaim && after.reservationId == before.reservationId &&
        after.revision == before.revision.next() && after.allocations == before.allocations &&
        after.binding == before.binding.copy(exposure = after.binding.exposure) &&
        (after.binding.exposure != CollectionExposure.UNCOLLECTED || before.binding.exposure == CollectionExposure.UNCOLLECTED) &&
        after.expiresAtEpochMillis == before.expiresAtEpochMillis &&
        after.holds.take(before.holds.size) == before.holds &&
        (before.status != ReservationStatus.HELD || after.status == ReservationStatus.HELD))
}

private fun postingIssue(issue: StockIssue, scope: InventoryScope) {
    inventoryPostingCheck(issue.purpose == ValueIssuePurpose.RETAIL && issue.kitchen == null && issue.revision == Revision(1) &&
        issue.allocations.all { it.restoredQuantity.scaled == 0L }, InventoryPostingFailure.UNSUPPORTED_STATE)
    inventoryPostingCheck(issue.allocations.isNotEmpty() && issue.allocations.size <= InventoryPlanner.MAX_ALLOCATIONS &&
        issue.allocations.map { it.allocation.allocationId }.distinct().size == issue.allocations.size)
    issue.allocations.forEach {
        postingPositionKey(it.allocation.position, scope)
        inventoryPostingCheck(it.allocation.quantity.scaled > 0 && it.originalValue.currency == Currency.EGP && it.originalValue.minor >= 0 &&
            it.restoredQuantity == zeroLike(it.allocation.quantity))
    }
    inventoryPostingCheck(issue.originalValue.minor >= 0) // Aggregate cross-pool overflow is an error too.
}

private fun postingValuation(effect: InventoryPostingEffect.Valuation, scope: InventoryScope) {
    postingPool(effect.pool, scope)
    inventoryPostingCheck(effect.resultingPoolRevision == effect.requiredPoolRevision.next() &&
        effect.resultingPoolRevision.value <= MovingAveragePlanner.MAX_EVENTS &&
        effect.originalValueDelta.currency == Currency.EGP && effect.carryingValueDelta == effect.originalValueDelta)
    when (val fact = effect.fact) {
        is ValuationFact.Receipt -> {
            inventoryPostingCheck(fact.origin == ReceiptValueOrigin.PURCHASE, InventoryPostingFailure.UNSUPPORTED_STATE)
            inventoryPostingCheck(fact.quantity.scaled > 0 && fact.originalValue.currency == Currency.EGP && fact.originalValue.minor >= 0 &&
                effect.quantityDelta == fact.quantity && effect.originalValueDelta == fact.originalValue)
        }
        is ValuationFact.Issue -> {
            inventoryPostingCheck(fact.purpose == ValueIssuePurpose.RETAIL, InventoryPostingFailure.UNSUPPORTED_STATE)
            inventoryPostingCheck(fact.slices.isNotEmpty() && fact.slices.size <= MovingAveragePlanner.MAX_SLICES &&
                fact.slices.map { it.allocationId }.distinct().size == fact.slices.size)
            var quantity = zeroLike(effect.quantityDelta)
            var value = Money(Currency.EGP, 0)
            for (slice in fact.slices) {
                inventoryPostingCheck(slice.quantity.scaled > 0 && slice.originalValue.currency == Currency.EGP && slice.originalValue.minor >= 0)
                quantity += slice.quantity; value += slice.originalValue
            }
            inventoryPostingCheck(effect.quantityDelta == -quantity && effect.originalValueDelta == -value)
        }
        is ValuationFact.Restoration, is ValuationFact.TransferOut, is ValuationFact.TransferIn ->
            throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
    }
}

/** Checks recorded grouping/order, not stock selection, availability, valuation or safety decisions. */
private fun validatePostingEffectOrder(effects: List<InventoryPostingEffect>) {
    if (effects.any { it is InventoryPostingEffect.Valuation && it.fact is ValuationFact.Receipt }) {
        inventoryPostingCheck(effects.size % 2 == 0)
        val receiptIds = mutableSetOf<DomainId>()
        for (index in effects.indices step 2) {
            val value = effects[index] as? InventoryPostingEffect.Valuation
                ?: throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
            val fact = value.fact as? ValuationFact.Receipt
                ?: throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
            val movement = effects[index + 1] as? InventoryPostingEffect.Movement
                ?: throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
            inventoryPostingCheck(receiptIds.add(fact.factId) && movement.reason == StockMovementReason.RECEIPT &&
                movement.sourceId == fact.factId && movement.position.pool == value.pool && movement.quantityDelta == fact.quantity &&
                movement.sourceAllocationId == null)
        }
        return
    }
    val finalIssue = effects.lastOrNull() as? InventoryPostingEffect.IssueRecorded
    if (finalIssue == null) {
        inventoryPostingCheck(effects.all { it is InventoryPostingEffect.SafetyChanged ||
            (it is InventoryPostingEffect.ReservationRecorded && it.reservation.status != ReservationStatus.ISSUED) })
        return
    }
    val issue = finalIssue.issue
    val reservationEffect = effects.getOrNull(effects.size - 2) as? InventoryPostingEffect.ReservationRecorded
        ?: throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
    val reservation = reservationEffect.reservation
    inventoryPostingCheck(reservation.reservationId == issue.reservationId && reservation.binding == issue.binding &&
        reservation.status == ReservationStatus.ISSUED && reservation.allocations.associateBy { it.allocationId } ==
        issue.allocations.map { it.allocation }.associateBy { it.allocationId })
    val groups = issue.allocations.groupBy { it.allocation.position.pool }
    val groupSize = groups.size + issue.allocations.size
    val prefixSize = effects.size - 2 - groupSize
    inventoryPostingCheck(prefixSize >= 0 && effects.take(prefixSize).all { it is InventoryPostingEffect.SafetyChanged ||
        (it is InventoryPostingEffect.ReservationRecorded && it.reservation.status == ReservationStatus.HELD) })
    var index = prefixSize
    for ((pool, allocations) in groups) {
        val value = effects.getOrNull(index++) as? InventoryPostingEffect.Valuation
            ?: throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
        val fact = value.fact as? ValuationFact.Issue
            ?: throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
        inventoryPostingCheck(value.pool == pool && fact.factId == issue.issueId &&
            fact.slices == allocations.map { CostedValueSlice(it.allocation.allocationId, it.allocation.quantity, it.originalValue) })
        for (allocation in allocations) {
            val movement = effects.getOrNull(index++) as? InventoryPostingEffect.Movement
                ?: throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
            inventoryPostingCheck(movement.reason == StockMovementReason.ISSUE && movement.sourceId == issue.issueId &&
                movement.position == allocation.allocation.position && movement.sourceAllocationId == allocation.allocation.allocationId &&
                movement.quantityDelta == -allocation.allocation.quantity)
        }
    }
    inventoryPostingCheck(index == effects.size - 2)
}

/**
 * An ordered storage diff, NOT another inventory/valuation planner. It consumes exact captured
 * objects and recorded deltas, then compares every next-cut field/index. No intermediate whole
 * cut is treated as a safe snapshot (a safety change precedes its several reservation holds).
 */
internal fun crosswalkInventoryPosting(
    source: InventorySnapshot,
    plan: InventoryPlan,
    batch: InventoryPostingBatch,
    now: Long,
) {
    val positions = source.positions.toMutableList()
    val pools = source.pools.toMutableList()
    val reservations = source.reservations.toMutableList()
    val issues = source.issues.toMutableList()
    val sourceIds = source.sourceIds.toMutableList()
    val addedSources = mutableSetOf<DomainId>()
    for ((index, posted) in batch.effects.withIndex()) when (posted) {
        is InventoryPostingEffect.Valuation -> {
            val live = (plan.effects[index] as InventoryEffect.Valuation).plan
            val prior = pools.firstOrNull { it.key == posted.pool }?.value
            if (prior == null) {
                inventoryPostingCheck(posted.fact is ValuationFact.Receipt && emptyPostingBasis(live.sourceBasis, posted),
                    InventoryPostingFailure.BINDING_MISMATCH)
            } else inventoryPostingCheck(live.sourceBasis === prior, InventoryPostingFailure.BINDING_MISMATCH)
            postingValueAdvance(live, posted)
            pools.removeAll { it.key == posted.pool }; pools += OwnedValuationPool(posted.pool, live.next)
            if (addedSources.add(posted.fact.factId)) {
                inventoryPostingCheck(posted.fact.factId !in source.sourceIds)
                sourceIds += posted.fact.factId
            }
        }
        is InventoryPostingEffect.Movement -> {
            val prior = positions.firstOrNull { it.key == posted.position }
            val base = if (prior != null) prior else {
                inventoryPostingCheck(posted.reason == StockMovementReason.RECEIPT)
                // InventoryEffect.Movement does not contain a new receipt's expiry/safety metadata.
                // Do not invent it: bind the complete result state here; A2 must independently bind
                // that cut to the full receipt/lot/expiry/upstream request and its source claims.
                val result = plan.nextSnapshot.position(posted.position)
                    ?: throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
                result.copy(onHand = zeroLike(posted.quantityDelta))
            }
            val changed = base.copy(onHand = base.onHand + posted.quantityDelta)
            positions.removeAll { it.key == posted.position }; positions += changed
        }
        is InventoryPostingEffect.SafetyChanged -> {
            inventoryPostingCheck(positions.firstOrNull { it.key == posted.before.key } == posted.before &&
                now >= requireNotNull(posted.before.expiresAtEpochMillis))
            positions.removeAll { it.key == posted.before.key }; positions += posted.after
        }
        is InventoryPostingEffect.ReservationRecorded -> {
            val next = posted.reservation
            val prior = reservations.firstOrNull { it.reservationId == next.reservationId }
            if (prior == null) inventoryPostingCheck(next.revision == Revision(1) && next.status == ReservationStatus.RESERVED)
            else postingReservationAdvance(prior, next)
            for (hold in next.holds) when (hold.reason) {
                ReservationHoldReason.EXPIRY -> inventoryPostingCheck(positions.firstOrNull { it.key == hold.position }?.safety == StockSafety.EXPIRED)
                ReservationHoldReason.RESERVATION_EXPIRED -> if (prior == null || hold !in prior.holds) {
                    inventoryPostingCheck(now >= requireNotNull(next.expiresAtEpochMillis))
                }
                else -> throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
            }
            reservations.removeAll { it.reservationId == next.reservationId }; reservations += next
        }
        is InventoryPostingEffect.IssueRecorded -> {
            inventoryPostingCheck(issues.none { it.issueId == posted.issue.issueId })
            issues += posted.issue
        }
    }
    // Keep list ordering as well as values. In particular, do not flatten reservation records to a map.
    val reconstructed = InventorySnapshot(source.scope, batch.binding.resultingInventoryRevision, positions, pools, reservations,
        issues, source.operationIds + plan.operationId, sourceIds)
    inventoryPostingCheck(StoredInventoryState.from(reconstructed) == StoredInventoryState.from(plan.nextSnapshot))
    for (pool in pools) inventoryPostingCheck(plan.nextSnapshot.pool(pool.key)?.value === pool.value,
        InventoryPostingFailure.BINDING_MISMATCH)
}

private fun emptyPostingBasis(basis: MovingAverageState, effect: InventoryPostingEffect.Valuation): Boolean =
    basis.revision == Revision(0) && basis.onHand == zeroLike(effect.quantityDelta) && basis.carryingValue == Money(Currency.EGP, 0) &&
        basis.unitId == effect.quantityDelta.unitId && basis.scale == effect.quantityDelta.scale && basis.currency == Currency.EGP &&
        basis.facts.isEmpty() && basis.eventIds.isEmpty() && basis.costTargets.isEmpty() &&
        basis.adjustments.isEmpty() && basis.linkedCorrectionBalances.isEmpty()

private fun postingValueAdvance(live: ValuationPlan, posted: InventoryPostingEffect.Valuation) {
    val before = live.sourceBasis
    val after = live.next
    inventoryPostingCheck(after.facts.size == before.facts.size + 1 && after.facts.size <= MovingAveragePlanner.MAX_EVENTS &&
        after.eventIds == before.eventIds + posted.fact.factId && after.facts.last() === posted.fact &&
        before.facts.indices.all { before.facts[it] === after.facts[it] } &&
        live.requiredRevision == before.revision && after.revision == before.revision.next() &&
        after.unitId == before.unitId && after.scale == before.scale && after.currency == before.currency &&
        after.onHand == before.onHand + posted.quantityDelta && after.carryingValue == before.carryingValue + posted.carryingValueDelta)
    postingValueProfile(before); postingValueProfile(after)
    // Zero-valued destination records still carry immutable consumed-allocation identity. This is
    // a key/zero preservation check, not moving-average arithmetic or cost-source replay.
    val destinations = before.linkedCorrectionBalances.map { it.destination }.toMutableList()
    if (destinations.isEmpty()) destinations += ValueDestination.Held
    if (posted.fact is ValuationFact.Issue) destinations += posted.fact.slices.map { ValueDestination.Consumed(posted.fact.factId, it.allocationId) }
    val expected = destinations.sortedBy {
        when (it) {
            ValueDestination.Held -> "0"
            is ValueDestination.Consumed -> "1:${it.issueId.value}:${it.allocationId.value}"
            is ValueDestination.Transferred -> throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
        }
    }.map { DestinationValue(it, Money(Currency.EGP, 0)) }
    inventoryPostingCheck(after.linkedCorrectionBalances == expected)
}

package com.ukkera.pos.inventory.store

import com.ukkera.pos.inventory.CostedValueSlice
import com.ukkera.pos.inventory.InventoryPosting
import com.ukkera.pos.inventory.InventoryPostingCodec
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockIssue
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/**
 * Cumulative cross-row correspondence, not another value engine or journal replay. A1 owns the
 * intrinsic whole-cut profile and valuation validation. This layer adds the missing receipt lot,
 * expiry, origin and source-owner spine. A coherent historical spine is still not an authority or
 * frontier proof; historical source-free reservation births are deliberately not indexed here.
 */
internal fun validateRetailCut(snapshot: InventorySnapshot, companion: InventoryRetailCompanion) {
    requireRetailSnapshotBounds(snapshot)
    validateRetailCompanionShape(companion)
    InventoryPosting.requireSupportedSnapshot(snapshot)
    retailCheck(snapshot.scope == InventoryScope(companion.scope.companyId, companion.scope.branchId) &&
        snapshot.revision == companion.inventoryRevision, InventoryRetailStoreFailure.BINDING_MISMATCH)
    val receipts = companion.receipts.associateBy { it.receiptId }
    val issueOwners = companion.issueSources.associateBy { it.issueId }
    val issues = snapshot.issues.associateBy { it.issueId }
    retailCheck(snapshot.sourceIds.toSet() == receipts.keys + issueOwners.keys && issues.keys == issueOwners.keys)
    retailCheck(snapshot.sourceIds.filter { it in receipts } == companion.receipts.map { it.receiptId } &&
        snapshot.sourceIds.filter { it in issueOwners } == companion.issueSources.map { it.issueId } &&
        snapshot.issues.map { it.issueId } == companion.issueSources.map { it.issueId })

    // One operation can receive many sources (including interleaved pools), or issue once across
    // several pools, or have no physical source. It cannot mix those operation families.
    val owners = companion.receipts.associate { it.receiptId to it.producingOperationId } +
        companion.issueSources.associate { it.issueId to it.producingOperationId }
    val operationOrdinals = snapshot.operationIds.withIndex().associate { it.value to it.index }
    val sourceOrdinals = snapshot.sourceIds.map { source ->
        operationOrdinals[owners.getValue(source)] ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
    }
    retailCheck(sourceOrdinals.zipWithNext().all { (before, after) -> before <= after })
    retailCheck(companion.issueSources.map { it.producingOperationId }.distinct().size == companion.issueSources.size &&
        companion.issueSources.none { issue -> companion.receipts.any { it.producingOperationId == issue.producingOperationId } })
    if (snapshot.revision == Revision(0)) {
        retailCheck(snapshot.operationIds.isEmpty())
    } else {
        retailCheck(snapshot.operationIds.last() == companion.producer.operationId)
        val previous = companion.previous ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
        if (snapshot.operationIds.size > 1) retailCheck(previous.producerOperationId == snapshot.operationIds[snapshot.operationIds.lastIndex - 1])
        else retailCheck(previous.producerOperationId !in snapshot.operationIds)
    }

    // Every purchase fact has exactly one complete retained receipt binding. No unrecognized
    // fact variant is silently filtered away, and multi-pool issues keep literal slice order.
    val seenReceipts = mutableSetOf<DomainId>()
    retailCheck(snapshot.pools.map { it.key }.toSet() == companion.receipts.map { it.position.pool }.toSet())
    for (pool in snapshot.pools) {
        val factIds = pool.value.facts.map { it.factId }
        retailCheck(factIds == snapshot.sourceIds.filter { it in factIds })
        for (fact in pool.value.facts) when (fact) {
            is ValuationFact.Receipt -> {
                val receipt = receipts[fact.factId] ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
                retailCheck(seenReceipts.add(fact.factId) && receipt.position.pool == pool.key &&
                    fact.quantity == receipt.quantity && fact.originalValue == receipt.originalValue && fact.origin == ReceiptValueOrigin.PURCHASE)
            }
            is ValuationFact.Issue -> {
                val issue = issues[fact.factId] ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
                retailCheck(fact.purpose == issue.purpose && fact.slices == issue.allocations.filter { it.allocation.position.pool == pool.key }
                    .map { CostedValueSlice(it.allocation.allocationId, it.allocation.quantity, it.originalValue) })
            }
            is ValuationFact.Restoration, is ValuationFact.TransferOut, is ValuationFact.TransferIn ->
                throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.UNSUPPORTED_STATE)
        }
    }
    retailCheck(seenReceipts == receipts.keys)
    for (issue in snapshot.issues) {
        val reservation = snapshot.reservation(issue.reservationId)
            ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
        retailCheck(issue.allocations.map { it.allocation } == reservation.allocations.groupBy { it.position.pool }.values.flatten())
    }

    val positions = snapshot.positions.associateBy { it.key }
    retailCheck(positions.keys == companion.receipts.map { it.position }.toSet())
    for (receipt in companion.receipts) {
        val position = positions.getValue(receipt.position)
        retailCheck(position.expiresAtEpochMillis == receipt.expiresAtEpochMillis &&
            position.onHand.unitId == receipt.quantity.unitId && position.onHand.scale == receipt.quantity.scale)
    }

    // Never total lifetime receipts and then subtract lifetime issues in Long: receive MAX,
    // issue MAX, receive MAX is valid. Fold physical sources in recorded chronological order;
    // require representable nonnegative intermediate LOT balances, and retain zero positions.
    val balances = mutableMapOf<StockPositionKey, Quantity>()
    for (sourceId in snapshot.sourceIds) {
        val receipt = receipts[sourceId]
        if (receipt != null) {
            val before = balances[receipt.position] ?: Quantity(receipt.quantity.unitId, 0, receipt.quantity.scale)
            val after = before + receipt.quantity
            retailCheck(after.scaled >= 0)
            balances[receipt.position] = after
        } else {
            val issue = issues.getValue(sourceId)
            for (issued in issue.allocations) {
                val allocation = issued.allocation
                val before = balances[allocation.position]
                    ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
                val after = before - allocation.quantity
                retailCheck(after.scaled >= 0)
                balances[allocation.position] = after
            }
        }
    }
    // Safety-only operations may reorder positions. Their current ordering is checked by the
    // live A1 transition, not reconstructed from this source-only physical correspondence fold.
    retailCheck(balances == snapshot.positions.associate { it.key to it.onHand })
    validateRetailLatestBatch(snapshot, companion)
}

internal fun requireRetailSnapshotBounds(snapshot: InventorySnapshot) {
    retailCheck(snapshot.issues.size <= InventoryRetailStoreProfile.MAX_ISSUES &&
        snapshot.reservations.size <= InventoryRetailStoreProfile.MAX_RESERVATIONS &&
        snapshot.sourceIds.size <= InventoryRetailStoreProfile.MAX_PHYSICAL_SOURCES &&
        snapshot.operationIds.size <= InventoryRetailStoreProfile.MAX_INVENTORY_OPERATIONS,
        InventoryRetailStoreFailure.TOO_LARGE)
}

private fun validateRetailLatestBatch(snapshot: InventorySnapshot, companion: InventoryRetailCompanion) {
    val batch = companion.latestBatch ?: return // Shape validation permits this only for genesis.
    InventoryPostingCodec.encode(batch) // Intrinsic batch validity, not a reconstructed executable plan.
    val currentReceiptIds = companion.receipts.filter { it.producingOperationId == companion.producer.operationId }.map { it.receiptId }
    val currentIssueIds = companion.issueSources.filter { it.producingOperationId == companion.producer.operationId }.map { it.issueId }
    val currentSources = (currentReceiptIds + currentIssueIds).toSet()
    val valuations = batch.effects.filterIsInstance<InventoryPostingEffect.Valuation>()
    retailCheck(valuations.map { it.fact.factId }.distinct() == snapshot.sourceIds.filter { it in currentSources })
    retailCheck(valuations.filter { it.fact is ValuationFact.Receipt }.map { it.fact.factId } == currentReceiptIds &&
        batch.effects.filterIsInstance<InventoryPostingEffect.IssueRecorded>().map { it.issue.issueId } == currentIssueIds)
    for ((key, posted) in valuations.groupBy { it.pool }) {
        val value = snapshot.pool(key)?.value ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
        retailCheck(value.revision.value >= posted.size && value.facts.size >= posted.size &&
            posted.first().requiredPoolRevision == Revision(value.revision.value - posted.size) &&
            posted.last().resultingPoolRevision == value.revision)
        retailCheck(value.facts.takeLast(posted.size).zip(posted).all { (fact, effect) -> sameRetailFact(fact, effect.fact) })
    }
    val lastReservations = batch.effects.filterIsInstance<InventoryPostingEffect.ReservationRecorded>()
        .associate { it.reservation.reservationId to it.reservation }
    for ((id, reservation) in lastReservations) {
        val stored = snapshot.reservation(id) ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
        retailCheck(sameRetailReservation(reservation, stored))
    }
    for (effect in batch.effects) when (effect) {
        is InventoryPostingEffect.Movement -> if (effect.reason == StockMovementReason.RECEIPT) {
            val receipt = companion.receipts.firstOrNull { it.receiptId == effect.sourceId }
                ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
            retailCheck(receipt.producingOperationId == companion.producer.operationId && effect.position == receipt.position &&
                effect.quantityDelta == receipt.quantity && effect.sourceAllocationId == null)
        }
        is InventoryPostingEffect.SafetyChanged -> retailCheck(effect.after == snapshot.position(effect.after.key))
        is InventoryPostingEffect.IssueRecorded -> {
            val issue = snapshot.issue(effect.issue.issueId) ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
            retailCheck(sameRetailIssue(effect.issue, issue))
        }
        is InventoryPostingEffect.Valuation, is InventoryPostingEffect.ReservationRecorded -> Unit
    }
}

internal fun sameRetailReservation(left: StockReservation, right: StockReservation): Boolean =
    left.reservationId == right.reservationId && left.binding == right.binding && left.allocations == right.allocations &&
        left.revision == right.revision && left.status == right.status && left.holds == right.holds && left.expiresAtEpochMillis == right.expiresAtEpochMillis

internal fun sameRetailIssue(left: StockIssue, right: StockIssue): Boolean =
    left.issueId == right.issueId && left.reservationId == right.reservationId && left.binding == right.binding &&
        left.allocations == right.allocations && left.purpose == right.purpose && left.revision == right.revision &&
        left.kitchen == null && right.kitchen == null

private fun sameRetailFact(left: ValuationFact, right: ValuationFact): Boolean = left.factId == right.factId && when {
    left is ValuationFact.Receipt && right is ValuationFact.Receipt ->
        left.quantity == right.quantity && left.originalValue == right.originalValue && left.origin == right.origin
    left is ValuationFact.Issue && right is ValuationFact.Issue -> left.slices == right.slices && left.purpose == right.purpose
    else -> false
}

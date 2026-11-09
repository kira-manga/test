package com.ukkera.pos.inventory

import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.allocateByLargestRemainder

/** Same-branch, same-SKU value edges only. No WIP, interbranch receipt or supplier obligation. */
public object LinkedMovingAveragePlanner {
    public const val CALCULATION_VERSION: String = "local-owned-transfer-reference-half-up-v1"
    public const val MAX_POOLS: Int = 32
    public const val MAX_GRAPH_EVENTS: Int = 8_192
    public const val MAX_HISTORY_WORK: Long = 2_000_000

    internal fun transfer(source: OwnedValuationPool, destination: OwnedValuationPool, transferId: DomainId,
        sourceEventId: DomainId, destinationEventId: DomainId, quantity: Quantity): Pair<ValuationPlan, ValuationPlan> {
        // The single-child identity is the permanently claimed transfer document ID. It is not
        // borrowed from the reservation-allocation namespace, nor regenerated on a retry.
        val link = OwnedTransferValueLink(transferId, transferId, source.key, destination.key,
            sourceEventId, destinationEventId, source.value.revision, destination.value.revision, quantity,
            MovingAveragePlanner.issueValue(source.value.carryingValue, quantity, source.value.onHand))
        requireTransferLink(link)
        return MovingAveragePlanner.transferOut(source.value, link) to MovingAveragePlanner.transferIn(destination.value, link)
    }

    internal fun correct(pools: List<OwnedValuationPool>, sourcePool: StockPoolKey, adjustmentId: DomainId,
        sourceId: DomainId, receiptId: DomainId, targetDelta: Money): List<Pair<StockPoolKey, ValuationPlan>> {
        val selected = pools.filter { it.key.scope == sourcePool.scope && it.key.skuId == sourcePool.skuId }
            .sortedBy { transferPoolOrder(it.key) }
        requireOwnedTransferCut(selected)
        val source = selected.single { it.key == sourcePool }.value
        val receipt = source.facts.filterIsInstance<ValuationFact.Receipt>().singleOrNull { it.factId == receiptId }
        guard(receipt?.origin == ReceiptValueOrigin.PURCHASE && targetDelta.currency == source.currency,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Correction requires the original local purchased receipt")
        guard(source.costTargets.none { it.sourceId == sourceId && it.receiptId != receiptId },
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "An acquisition source cannot change receipt")
        val changed = ReceiptCostTarget(sourceId, receiptId, targetDelta)
        val basis = TransferCorrectionBasis(adjustmentId, sourcePool, changed, selected.map { ValuationPoolRevision(it.key, it.value.revision) })
        val reference = referenceLinkedValues(selected, sourcePool to changed)
        val plans = selected.mapNotNull { pool ->
            val result = reference.getValue(pool.key)
            val desired = linkedDesired(pool.value, result)
            if (pool.key != sourcePool && desired == pool.value.linkedCorrectionBalances && result.imports == pool.value.transferTargets) null
            else pool.key to MovingAveragePlanner.linkedCorrection(pool.value, pool.key, basis, result.imports)
        }
        // Routing destinations are deliberately excluded: incoming/outgoing edges cancel once.
        val terminal = plans.flatMap { it.second.linkedAdjustment?.deltas.orEmpty() }
            .filter { it.destination !is ValueDestination.Transferred }.fold(minor(0)) { sum, row -> sum + minor(row.value.minor) }
        val previous = source.costTargets.singleOrNull { it.sourceId == sourceId }?.targetDelta ?: Money(source.currency, 0)
        check(terminal == minor((targetDelta - previous).minor)) { "Cross-pool correction value is not conserved" }
        return plans
    }
}

internal data class LinkedReference(val quantity: Quantity, val held: Money, val values: Map<ValueDestination, Money>,
    val imports: List<IncomingTransferCostTarget>)
private data class LinkedReferenceSlice(val quantity: Quantity, val value: Money, val restored: Quantity, val restoredValue: Money)

/** Pure reference calculation over immutable facts, never replay of inventory/business commands. */
internal fun referenceLinkedValues(pools: List<OwnedValuationPool>, changed: Pair<StockPoolKey, ReceiptCostTarget>? = null): Map<StockPoolKey, LinkedReference> {
    requireTransferPairs(pools)
    val ordered = pools.sortedBy { transferPoolOrder(it.key) }
    val cursors = ordered.associate { pool -> pool.key to LinkedReferenceCursor(pool, changed?.takeIf { it.first == pool.key }?.second) }
    val outputs = mutableMapOf<Pair<DomainId, DomainId>, Money>()
    var remaining = ordered.sumOf { it.value.facts.size }
    while (remaining > 0) {
        var advanced = false
        for (pool in ordered) {
            val cursor = cursors.getValue(pool.key)
            val fact = pool.value.facts.getOrNull(cursor.index) ?: continue
            if (fact is ValuationFact.TransferIn && (fact.link.transferId to fact.link.allocationId) !in outputs) continue
            cursor.append(fact, outputs)
            remaining--
            advanced = true
        }
        guard(advanced, InventoryFailure.INVALID_STATE, "Transfer facts have a missing predecessor or event cycle")
    }
    return cursors.mapValues { it.value.result() }
}

private class LinkedReferenceCursor(pool: OwnedValuationPool, changed: ReceiptCostTarget?) {
    private val state = pool.value
    var index = 0
        private set
    private var quantity = Quantity(state.unitId, 0, state.scale)
    private var held = Money(state.currency, 0)
    private val slices = linkedMapOf<Pair<DomainId, DomainId>, LinkedReferenceSlice>()
    private val transfers = linkedMapOf<ValueDestination, Money>()
    private val imports = mutableListOf<IncomingTransferCostTarget>()
    private val targets = if (changed == null) state.costTargets else
        (state.costTargets.filterNot { it.sourceId == changed.sourceId } + changed).sortedBy { it.sourceId.value }
    private val charges = targets.groupBy { it.receiptId }.mapValues { (_, rows) -> rows.fold(minor(0)) { sum, row -> sum + minor(row.targetDelta.minor) } }

    fun append(fact: ValuationFact, outputs: MutableMap<Pair<DomainId, DomainId>, Money>) {
        when (fact) {
            is ValuationFact.Receipt -> {
                guard(fact.origin in setOf(ReceiptValueOrigin.PURCHASE, ReceiptValueOrigin.OPENING, ReceiptValueOrigin.COUNT_SURPLUS),
                    InventoryFailure.UNSUPPORTED_LINEAGE, "Unsupported external value origin")
                val value = Money(state.currency, (minor(fact.originalValue.minor) + (charges[fact.factId] ?: minor(0))).toScaledLongExact(0))
                guard(value.minor >= 0, InventoryFailure.NEGATIVE_VALUE, "Correction makes an acquisition negative")
                quantity += fact.quantity
                held += value
            }
            is ValuationFact.TransferIn -> {
                val value = outputs.getValue(fact.link.transferId to fact.link.allocationId)
                quantity += fact.link.quantity
                held += value
                imports += IncomingTransferCostTarget(fact.factId, value - fact.link.originalValue)
            }
            is ValuationFact.TransferOut -> {
                val value = proportional(held, fact.link.quantity, quantity)
                quantity -= fact.link.quantity
                held -= value
                transfers[transferDestination(fact.link)] = value
                outputs[fact.link.transferId to fact.link.allocationId] = value
            }
            is ValuationFact.Issue -> {
                guard(fact.purpose != ValueIssuePurpose.TRANSFER && fact.purpose != ValueIssuePurpose.WORK_IN_PROGRESS,
                    InventoryFailure.UNSUPPORTED_LINEAGE, "Unsupported unpaired issue lineage")
                val issued = fact.slices.fold(Quantity(state.unitId, 0, state.scale)) { sum, slice -> sum + slice.quantity }
                val value = proportional(held, issued, quantity)
                val allocated = allocateByLargestRemainder(value, fact.slices.map { AllocationWeight(it.allocationId.value, it.quantity.scaled) })
                    .associate { it.key to it.amount }
                fact.slices.forEach { slice -> slices[fact.factId to slice.allocationId] = LinkedReferenceSlice(slice.quantity,
                    allocated.getValue(slice.allocationId.value), Quantity(state.unitId, 0, state.scale), Money(state.currency, 0)) }
                quantity -= issued
                held -= value
            }
            is ValuationFact.Restoration -> for (restoration in fact.restorations) {
                val key = restoration.issueId to restoration.allocationId
                val slice = slices[key] ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Missing original reference allocation")
                val cumulative = slice.restored + restoration.quantity
                val value = proportional(slice.value, cumulative, slice.quantity)
                held += value - slice.restoredValue
                quantity += restoration.quantity
                slices[key] = slice.copy(restored = cumulative, restoredValue = value)
            }
        }
        guard(quantity.scaled >= 0 && held.minor >= 0 && (quantity.scaled != 0L || held.minor == 0L),
            InventoryFailure.NEGATIVE_VALUE, "Reference value is negative or orphaned")
        index++
    }

    fun result(): LinkedReference {
        val values = linkedMapOf<ValueDestination, Money>(ValueDestination.Held to held)
        slices.forEach { (key, slice) -> values[ValueDestination.Consumed(key.first, key.second)] = slice.value - slice.restoredValue }
        values.putAll(transfers)
        return LinkedReference(quantity, held, values, imports.sortedBy { it.receiptEventId.value })
    }

    private fun proportional(value: Money, part: Quantity, whole: Quantity): Money {
        requireSameUnit(part, whole)
        guard(part.scaled > 0 && part <= whole, InventoryFailure.INVALID_QUANTITY, "Incomplete reference quantity")
        return if (part == whole) value else Money(value.currency,
            (minor(value.minor) * ExactRational.ratio(part.scaled, whole.scaled)).roundHalfUp(0))
    }
}

internal fun linkedDesired(state: MovingAverageState, reference: LinkedReference): List<DestinationValue> {
    val original = MovingAveragePlanner.originalDestinations(state, state.facts)
    return reference.values.map { (key, value) -> DestinationValue(key,
        Money(state.currency, (minor(value.minor) - original.getValue(key)).toScaledLongExact(0))) }
        .sortedBy { valueDestinationOrder(it.destination) }
}

/** Paired original facts, exact event frontiers and zero-valued edges remain permanent. */
private fun requireTransferPairs(pools: List<OwnedValuationPool>) {
    guard(pools.size <= LinkedMovingAveragePlanner.MAX_POOLS && pools.map { it.key }.distinct().size == pools.size &&
        pools.sumOf { it.value.eventIds.size } <= LinkedMovingAveragePlanner.MAX_GRAPH_EVENTS,
        InventoryFailure.BOUND_EXCEEDED, "Linked valuation closure exceeds capacity")
    val outgoing = linkedMapOf<Pair<DomainId, DomainId>, OwnedTransferValueLink>()
    val incoming = linkedMapOf<Pair<DomainId, DomainId>, OwnedTransferValueLink>()
    for (pool in pools) for (fact in pool.value.facts) {
        val link = when (fact) {
            is ValuationFact.TransferOut -> fact.link
            is ValuationFact.TransferIn -> fact.link
            else -> continue
        }
        requireTransferLink(link)
        requireSameUnit(pool.value.onHand, link.quantity)
        val key = link.transferId to link.allocationId
        if (fact is ValuationFact.TransferOut) {
            guard(pool.key == link.sourcePool && pool.value.eventIds.indexOf(fact.factId).toLong() == link.sourceRevision.value && outgoing.put(key, link) == null,
                InventoryFailure.INVALID_STATE, "Transferred source identity/frontier reused")
        } else guard(pool.key == link.destinationPool && pool.value.eventIds.indexOf(fact.factId).toLong() == link.destinationRevision.value && incoming.put(key, link) == null,
            InventoryFailure.INVALID_STATE, "Transferred destination identity/frontier reused")
    }
    guard(outgoing == incoming, InventoryFailure.INVALID_STATE, "Transferred value requires both matching local original facts")
}

/** Complete current and historical source closure; a per-pool decoder cannot establish this. */
internal fun requireOwnedTransferCut(pools: List<OwnedValuationPool>) {
    for (group in pools.groupBy { it.key.scope to it.key.skuId }.values) {
        if (group.none { it.value.hasTransferLineage }) continue
        guard(group.size <= LinkedMovingAveragePlanner.MAX_POOLS &&
            group.sumOf { it.value.eventIds.size } <= LinkedMovingAveragePlanner.MAX_GRAPH_EVENTS,
            InventoryFailure.BOUND_EXCEEDED, "Linked valuation closure exceeds capacity")
        val acquisitionBindings = group.flatMap { pool -> pool.value.costTargets.map { target ->
            target.sourceId to (pool.key to target.receiptId)
        } }.groupBy({ it.first }, { it.second })
        guard(acquisitionBindings.values.all { it.distinct().size == 1 }, InventoryFailure.INVALID_ORIGINAL_ALLOCATION,
            "A retained acquisition source cannot change pool or receipt in the linked closure")
        val corrections = group.flatMap { pool -> pool.value.adjustments.filter { it.transferCorrection != null }.map { pool.key to it } }
            .groupBy { it.second.adjustmentId }
        val work = group.sumOf { it.value.eventIds.size.toLong() + it.value.facts.sumOf { fact -> when (fact) {
            is ValuationFact.Issue -> fact.slices.size.toLong()
            is ValuationFact.Restoration -> fact.restorations.size.toLong()
            else -> 1L
        } } } * (2 * corrections.size.toLong() + 1)
        guard(work <= LinkedMovingAveragePlanner.MAX_HISTORY_WORK, InventoryFailure.BOUND_EXCEEDED, "Linked historical frontier verification exceeds capacity")
        requireLinkedCurrent(group)
        val byPool = group.associateBy { it.key }
        for ((eventId, recorded) in corrections) {
            val basis = requireNotNull(recorded.first().second.transferCorrection)
            guard(basis.adjustmentId == eventId && basis.pools.size in 1..LinkedMovingAveragePlanner.MAX_POOLS &&
                basis.pools.map { it.pool }.distinct().size == basis.pools.size &&
                basis.pools == basis.pools.sortedBy { transferPoolOrder(it.pool) } && basis.pools.any { it.pool == basis.sourcePool } &&
                basis.pools.all { it.pool.scope == basis.sourcePool.scope && it.pool.skuId == basis.sourcePool.skuId },
                InventoryFailure.INVALID_STATE, "Invalid linked correction frontier")
            val before = basis.pools.map { ref ->
                val pool = byPool[ref.pool] ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Missing historical linked pool")
                OwnedValuationPool(ref.pool, valuationPrefix(pool.value, ref.revision))
            }
            // A correction's fixed frontier cannot cut through another atomic routed correction.
            // Equal numeric deltas (including zero) do not erase its permanent causal identity.
            val prefixEvents = before.associate { it.key to it.value.eventIds.toSet() }
            for ((priorId, priorRows) in corrections) {
                val included = priorRows.count { (key, _) -> priorId in prefixEvents[key].orEmpty() }
                guard(included == 0 || included == priorRows.size, InventoryFailure.INVALID_STATE,
                    "Historical frontier splits an atomic linked correction")
            }
            requireLinkedCurrent(before)
            val source = before.single { it.key == basis.sourcePool }.value
            guard(source.facts.any { it is ValuationFact.Receipt && it.factId == basis.changedSource.receiptId && it.origin == ReceiptValueOrigin.PURCHASE } &&
                source.costTargets.none { it.sourceId == basis.changedSource.sourceId && it.receiptId != basis.changedSource.receiptId },
                InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Invalid linked acquisition-source binding")
            val reference = referenceLinkedValues(before, basis.sourcePool to basis.changedSource)
            val expectedKeys = mutableSetOf<StockPoolKey>()
            for (pool in before) {
                val result = reference.getValue(pool.key)
                val desired = linkedDesired(pool.value, result)
                if (pool.key != basis.sourcePool && desired == pool.value.linkedCorrectionBalances && result.imports == pool.value.transferTargets) continue
                expectedKeys += pool.key
                val adjustment = recorded.singleOrNull { it.first == pool.key }?.second
                    ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Linked correction omitted an affected pool")
                val otherBasis = requireNotNull(adjustment.transferCorrection)
                val changed = basis.changedSource.takeIf { pool.key == basis.sourcePool }
                val targets = if (changed == null) pool.value.costTargets else
                    (pool.value.costTargets.filterNot { it.sourceId == changed.sourceId } + changed).sortedBy { it.sourceId.value }
                val prior = pool.value.linkedCorrectionBalances.associate { it.destination to it.value }
                val deltas = desired.map { DestinationValue(it.destination, it.value - (prior[it.destination] ?: Money(it.value.currency, 0))) }
                    .filter { it.value.minor != 0L }
                val currentPool = byPool.getValue(pool.key).value
                guard(sameTransferBasis(basis, otherBasis) && adjustment.basisRevision == pool.value.revision &&
                    currentPool.eventIds.getOrNull(pool.value.revision.value.toInt()) == eventId && currentPool.facts.none { it.factId == eventId } &&
                    adjustment.changedSource == changed && adjustment.targetBasis == targets && adjustment.transferTargetBasis == result.imports &&
                    adjustment.deltas == deltas, InventoryFailure.INVALID_STATE, "Linked correction does not match its fixed original frontier")
            }
            guard(recorded.map { it.first }.toSet() == expectedKeys && recorded.size == expectedKeys.size,
                InventoryFailure.INVALID_STATE, "Extraneous linked correction destination")
        }
    }
}

private fun requireLinkedCurrent(pools: List<OwnedValuationPool>) {
    val current = referenceLinkedValues(pools)
    for (pool in pools) {
        val expected = current.getValue(pool.key)
        guard(expected.quantity == pool.value.onHand && expected.held == pool.value.carryingValue &&
            expected.imports == pool.value.transferTargets && linkedDesired(pool.value, expected) == pool.value.linkedCorrectionBalances,
            InventoryFailure.INVALID_STATE, "Transferred reference closure disagrees")
    }
}

private fun sameTransferBasis(a: TransferCorrectionBasis, b: TransferCorrectionBasis): Boolean =
    a.adjustmentId == b.adjustmentId && a.sourcePool == b.sourcePool && a.changedSource == b.changedSource && a.pools == b.pools

/** Literal prefix fold, not a command planner or a reusable authorization/proof. */
private fun valuationPrefix(state: MovingAverageState, revision: Revision): MovingAverageState {
    guard(revision.value in 0..state.revision.value, InventoryFailure.INVALID_STATE, "Historical valuation frontier missing")
    val events = state.eventIds.take(revision.value.toInt())
    val ids = events.toSet()
    val facts = state.facts.filter { it.factId in ids }
    val adjustments = state.adjustments.filter { it.adjustmentId in ids }
    val byFact = facts.associateBy { it.factId }
    val byAdjustment = adjustments.associateBy { it.adjustmentId }
    var quantity = Quantity(state.unitId, 0, state.scale)
    var book = Money(state.currency, 0)
    var targets = emptyList<ReceiptCostTarget>()
    var imports = emptyList<IncomingTransferCostTarget>()
    val balances = linkedMapOf<ValueDestination, Money>()
    if (events.isNotEmpty()) balances[ValueDestination.Held] = Money(state.currency, 0)
    for (id in events) {
        when (val fact = byFact[id]) {
            is ValuationFact.Receipt -> { quantity += fact.quantity; book += fact.originalValue }
            is ValuationFact.TransferIn -> {
                quantity += fact.link.quantity; book += fact.link.originalValue
                imports = (imports + IncomingTransferCostTarget(id, Money(state.currency, 0))).sortedBy { it.receiptEventId.value }
            }
            is ValuationFact.TransferOut -> {
                quantity -= fact.link.quantity; book -= fact.link.originalValue
                balances[transferDestination(fact.link)] = Money(state.currency, 0)
            }
            is ValuationFact.Issue -> for (slice in fact.slices) {
                quantity -= slice.quantity; book -= slice.originalValue
                balances[ValueDestination.Consumed(id, slice.allocationId)] = Money(state.currency, 0)
            }
            is ValuationFact.Restoration -> for (slice in fact.restorations) { quantity += slice.quantity; book += slice.originalValue }
            null -> Unit
        }
        byAdjustment[id]?.let { adjustment ->
            targets = adjustment.targetBasis
            imports = adjustment.transferTargetBasis
            for (delta in adjustment.deltas) balances[delta.destination] = (balances[delta.destination] ?: Money(state.currency, 0)) + delta.value
            book += adjustment.heldDelta
        }
    }
    return MovingAverageState(state.unitId, state.scale, state.currency, revision, quantity, book, facts, targets,
        balances.map { DestinationValue(it.key, it.value) }.sortedBy { valueDestinationOrder(it.destination) }, adjustments, events, imports)
}

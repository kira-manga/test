package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity

/**
 * Version-pinned historical verification, not business-command replay or repricing.
 * Original issues are checked against the THEN-known actual carrying value. A
 * separate reference replay verifies corrections; it never replaces stored facts.
 */
internal fun validateStoredValuation(state: MovingAverageState) {
    inventorySnapshotCheck(state.currency == Currency.EGP && state.scale in 0..6)
    storedQuantity(state.onHand, state.unitId, state.scale, positive = false)
    storedValue(state.carryingValue, state.currency)
    inventorySnapshotCheck(state.onHand.scaled != 0L || state.carryingValue.minor == 0L)
    inventorySnapshotCheck(state.revision.value == state.eventIds.size.toLong())
    inventorySnapshotCheck(state.eventIds.distinct().size == state.eventIds.size &&
        state.facts.map { it.factId }.distinct().size == state.facts.size &&
        state.adjustments.map { it.adjustmentId }.distinct().size == state.adjustments.size)
    val facts = state.facts.associateBy { it.factId }
    val adjustments = state.adjustments.associateBy { it.adjustmentId }
    inventorySnapshotCheck(state.facts.map { it.factId } == state.eventIds.filter { it in facts } &&
        state.adjustments.map { it.adjustmentId } == state.eventIds.filter { it in adjustments })
    for (fact in state.facts) validateStoredFact(fact, state)

    var targets = emptyList<ReceiptCostTarget>()
    var imports = emptyList<IncomingTransferCostTarget>()
    var balances = emptyList<DestinationValue>()
    var book = Money(state.currency, 0)
    val seen = mutableListOf<ValuationFact>()
    val receipts = mutableMapOf<DomainId, ValuationFact.Receipt>()
    val original = StoredOriginalLedger(state)
    var linkedHistory = false
    for ((index, eventId) in state.eventIds.withIndex()) {
        val fact = facts[eventId]
        val adjustment = adjustments[eventId]
        val changed = adjustment?.changedSource
        val graph = adjustment?.transferCorrection
        // A fact may also cause a return reclassification, but not an acquisition-source change.
        inventorySnapshotCheck((fact != null) != (changed != null || graph != null))
        inventorySnapshotCheck(graph == null || graph.adjustmentId == eventId &&
            (changed == null || changed == graph.changedSource))
        inventorySnapshotCheck(changed == null || graph != null || !linkedHistory)
        linkedHistory = linkedHistory || graph != null || fact is ValuationFact.TransferIn || fact is ValuationFact.TransferOut
        val previousTargets = targets
        val previousImports = imports
        if (changed != null) {
            val receipt = receipts[changed.receiptId]
            inventorySnapshotCheck(receipt != null && receipt.origin == ReceiptValueOrigin.PURCHASE)
            storedValue(changed.targetDelta, state.currency, nonnegative = false)
            inventorySnapshotCheck(targets.none { it.sourceId == changed.sourceId && it.receiptId != changed.receiptId })
            targets = (targets.filterNot { it.sourceId == changed.sourceId } + changed).sortedBy { it.sourceId.value }
            inventorySnapshotCheck(targets.size <= MovingAveragePlanner.MAX_COST_SOURCES)
        }
        if (fact is ValuationFact.TransferIn) imports = (imports + IncomingTransferCostTarget(fact.factId, Money(state.currency, 0)))
            .sortedBy { it.receiptEventId.value }
        if (graph != null) imports = requireNotNull(adjustment).transferTargetBasis
        imports.forEach { storedValue(it.targetDelta, state.currency, nonnegative = false) }
        inventorySnapshotCheck(imports.size <= MovingAveragePlanner.MAX_EVENTS && imports.map { it.receiptEventId }.distinct().size == imports.size &&
            imports == imports.sortedBy { it.receiptEventId.value })
        val originalDelta = if (fact != null) {
            val delta = original.append(fact, book)
            seen += fact
            if (fact is ValuationFact.Receipt) receipts[fact.factId] = fact
            delta
        } else Money(state.currency, 0)
        val reference = replayStoredReference(state, seen, targets, imports)
        inventorySnapshotCheck(reference.quantity == original.quantity)
        val desired = reference.values.map { (destination, amount) ->
            DestinationValue(destination, Money(state.currency,
                (minor(amount.minor) - original.at(destination)).toScaledLongExact(0)))
        }.sortedBy { storedDestinationKey(it.destination) }
        val previous = balances.associate { it.destination to it.value }
        // desired retains exactly these map keys; do not scan all destinations for each old key.
        inventorySnapshotCheck(previous.keys.all { key -> key in reference.values })
        val deltas = desired.map { row ->
            DestinationValue(row.destination, row.value - (previous[row.destination] ?: Money(state.currency, 0)))
        }.filter { it.value.minor != 0L }
        inventorySnapshotCheck(storedMoneySum(deltas.map { it.value }) ==
            storedMoneySum(targets.map { it.targetDelta }) - storedMoneySum(previousTargets.map { it.targetDelta }) +
                storedMoneySum(imports.map { it.targetDelta }) - storedMoneySum(previousImports.map { it.targetDelta }))
        if (changed != null || graph != null || deltas.isNotEmpty()) {
            inventorySnapshotCheck(adjustment != null)
            val recorded = requireNotNull(adjustment)
            val version = if (linkedHistory)
                LinkedMovingAveragePlanner.CALCULATION_VERSION else MovingAveragePlanner.CALCULATION_VERSION
            inventorySnapshotCheck(recorded.calculationVersion == version && recorded.transferTargetBasis == imports &&
                recorded.basisRevision.value == index.toLong() && recorded.targetBasis == targets && recorded.deltas == deltas)
        } else inventorySnapshotCheck(adjustment == null)
        val heldDelta = deltas.firstOrNull { it.destination == ValueDestination.Held }?.value ?: Money(state.currency, 0)
        inventorySnapshotCheck(minor(book.minor) + minor(originalDelta.minor) + minor(heldDelta.minor) == minor(reference.held.minor))
        book = reference.held
        balances = desired
    }
    inventorySnapshotCheck(original.quantity == state.onHand && book == state.carryingValue &&
        targets == state.costTargets && imports == state.transferTargets && balances == state.linkedCorrectionBalances)
}

private fun validateStoredFact(fact: ValuationFact, state: MovingAverageState) {
    when (fact) {
        is ValuationFact.Receipt -> {
            inventorySnapshotCheck(fact.origin == ReceiptValueOrigin.PURCHASE || fact.origin == ReceiptValueOrigin.OPENING ||
                fact.origin == ReceiptValueOrigin.COUNT_SURPLUS)
            storedQuantity(fact.quantity, state.unitId, state.scale)
            storedValue(fact.originalValue, state.currency)
        }
        is ValuationFact.TransferOut -> {
            requireTransferLink(fact.link)
            storedQuantity(fact.link.quantity, state.unitId, state.scale)
            storedValue(fact.link.originalValue, state.currency)
        }
        is ValuationFact.TransferIn -> {
            requireTransferLink(fact.link)
            storedQuantity(fact.link.quantity, state.unitId, state.scale)
            storedValue(fact.link.originalValue, state.currency)
        }
        is ValuationFact.Issue -> {
            inventorySnapshotCheck(fact.purpose != ValueIssuePurpose.TRANSFER && fact.purpose != ValueIssuePurpose.WORK_IN_PROGRESS &&
                fact.slices.isNotEmpty() && fact.slices.map { it.allocationId }.distinct().size == fact.slices.size)
            fact.slices.forEach { storedQuantity(it.quantity, state.unitId, state.scale); storedValue(it.originalValue, state.currency) }
        }
        is ValuationFact.Restoration -> {
            inventorySnapshotCheck(fact.restorations.isNotEmpty() &&
                fact.restorations.map { it.issueId to it.allocationId }.distinct().size == fact.restorations.size)
            fact.restorations.forEach { storedQuantity(it.quantity, state.unitId, state.scale); storedValue(it.originalValue, state.currency) }
        }
    }
}

private data class StoredOriginalSlice(
    val quantity: Quantity,
    val value: Money,
    val purpose: ValueIssuePurpose,
    val restored: Quantity,
    val restoredValue: Money,
)

/** Original held can be negative after later actual-cost issues; it is exact, not a posted balance. */
private class StoredOriginalLedger(private val state: MovingAverageState) {
    var quantity: Quantity = Quantity(state.unitId, 0, state.scale)
        private set
    private var held = minor(0)
    private val slices = mutableMapOf<Pair<DomainId, DomainId>, StoredOriginalSlice>()
    private val transfers = mutableMapOf<ValueDestination.Transferred, Money>()

    fun at(destination: ValueDestination): ExactRational = when (destination) {
        ValueDestination.Held -> held
        is ValueDestination.Transferred -> minor(transfers.getValue(destination).minor)
        is ValueDestination.Consumed -> slices.getValue(destination.issueId to destination.allocationId).let {
            minor(it.value.minor) - minor(it.restoredValue.minor)
        }
    }

    fun append(fact: ValuationFact, currentBook: Money): Money = when (fact) {
        is ValuationFact.Receipt -> {
            quantity += fact.quantity
            held += minor(fact.originalValue.minor)
            fact.originalValue
        }
        is ValuationFact.TransferIn -> {
            quantity += fact.link.quantity
            held += minor(fact.link.originalValue.minor)
            fact.link.originalValue
        }
        is ValuationFact.TransferOut -> {
            val amount = storedProportionalValue(currentBook, fact.link.quantity, quantity)
            inventorySnapshotCheck(amount == fact.link.originalValue && transfers.put(transferDestination(fact.link), amount) == null)
            quantity -= fact.link.quantity
            held -= minor(amount.minor)
            -amount
        }
        is ValuationFact.Issue -> {
            val issued = fact.slices.fold(Quantity(state.unitId, 0, state.scale)) { total, slice -> total + slice.quantity }
            inventorySnapshotCheck(issued <= quantity)
            val amount = storedProportionalValue(currentBook, issued, quantity)
            val allocated = storedSliceAllocation(amount, fact.slices)
            fact.slices.forEach { slice ->
                inventorySnapshotCheck(slice.originalValue == allocated.getValue(slice.allocationId))
                slices[fact.factId to slice.allocationId] = StoredOriginalSlice(slice.quantity, slice.originalValue, fact.purpose,
                    Quantity(state.unitId, 0, state.scale), Money(state.currency, 0))
            }
            quantity -= issued
            held -= minor(amount.minor)
            -amount
        }
        is ValuationFact.Restoration -> {
            for (restoration in fact.restorations) {
                val key = restoration.issueId to restoration.allocationId
                val slice = slices[key]
                inventorySnapshotCheck(slice != null && slice.purpose != ValueIssuePurpose.COUNT_LOSS && slice.purpose != ValueIssuePurpose.SUPPLIER_RETURN)
                val original = requireNotNull(slice)
                val cumulative = original.restored + restoration.quantity
                inventorySnapshotCheck(cumulative <= original.quantity)
                val cumulativeValue = storedProportionalValue(original.value, cumulative, original.quantity)
                inventorySnapshotCheck(restoration.originalValue == cumulativeValue - original.restoredValue)
                quantity += restoration.quantity
                held += minor(restoration.originalValue.minor)
                slices[key] = original.copy(restored = cumulative, restoredValue = cumulativeValue)
            }
            Money(state.currency, storedMoneySum(fact.restorations.map { it.originalValue }).toScaledLongExact(0))
        }
    }
}

private data class StoredReferenceSlice(val quantity: Quantity, val value: Money, val restored: Quantity, val restoredValue: Money)
private data class StoredReferenceResult(val quantity: Quantity, val held: Money, val values: Map<ValueDestination, Money>)

private fun replayStoredReference(
    state: MovingAverageState,
    facts: List<ValuationFact>,
    targets: List<ReceiptCostTarget>,
    transferTargets: List<IncomingTransferCostTarget>,
): StoredReferenceResult {
    var quantity = Quantity(state.unitId, 0, state.scale)
    var held = Money(state.currency, 0)
    val slices = mutableMapOf<Pair<DomainId, DomainId>, StoredReferenceSlice>()
    val transfers = mutableMapOf<ValueDestination.Transferred, Money>()
    val imports = transferTargets.associate { it.receiptEventId to it.targetDelta }
    inventorySnapshotCheck(imports.size == transferTargets.size &&
        imports.keys == facts.filterIsInstance<ValuationFact.TransferIn>().map { it.factId }.toSet())
    val charges = targets.groupBy { it.receiptId }.mapValues { (_, group) -> storedMoneySum(group.map { it.targetDelta }) }
    for (fact in facts) {
        when (fact) {
            is ValuationFact.Receipt -> {
                val amount = Money(state.currency, (minor(fact.originalValue.minor) + (charges[fact.factId] ?: minor(0))).toScaledLongExact(0))
                storedValue(amount, state.currency)
                quantity += fact.quantity
                held += amount
            }
            is ValuationFact.TransferIn -> {
                val amount = fact.link.originalValue + imports.getValue(fact.factId)
                storedValue(amount, state.currency)
                quantity += fact.link.quantity
                held += amount
            }
            is ValuationFact.TransferOut -> {
                val amount = storedProportionalValue(held, fact.link.quantity, quantity)
                transfers[transferDestination(fact.link)] = amount
                quantity -= fact.link.quantity
                held -= amount
            }
            is ValuationFact.Issue -> {
                val issued = fact.slices.fold(Quantity(state.unitId, 0, state.scale)) { total, slice -> total + slice.quantity }
                inventorySnapshotCheck(issued <= quantity)
                val amount = storedProportionalValue(held, issued, quantity)
                val allocated = storedSliceAllocation(amount, fact.slices)
                fact.slices.forEach { slice -> slices[fact.factId to slice.allocationId] = StoredReferenceSlice(slice.quantity,
                    allocated.getValue(slice.allocationId), Quantity(state.unitId, 0, state.scale), Money(state.currency, 0)) }
                quantity -= issued
                held -= amount
            }
            is ValuationFact.Restoration -> for (restoration in fact.restorations) {
                val key = restoration.issueId to restoration.allocationId
                val slice = slices.getValue(key)
                val cumulative = slice.restored + restoration.quantity
                inventorySnapshotCheck(cumulative <= slice.quantity)
                val value = storedProportionalValue(slice.value, cumulative, slice.quantity)
                quantity += restoration.quantity
                held += value - slice.restoredValue
                slices[key] = slice.copy(restored = cumulative, restoredValue = value)
            }
        }
        inventorySnapshotCheck(held.minor >= 0 && (quantity.scaled != 0L || held.minor == 0L))
    }
    val values = mutableMapOf<ValueDestination, Money>(ValueDestination.Held to held)
    for ((key, slice) in slices) values[ValueDestination.Consumed(key.first, key.second)] = slice.value - slice.restoredValue
    values.putAll(transfers)
    return StoredReferenceResult(quantity, held, values)
}

/** Final quantity owns the residual; no signed Long multiplication is used. */
private fun storedProportionalValue(value: Money, part: Quantity, whole: Quantity): Money {
    requireSameUnit(part, whole)
    inventorySnapshotCheck(part.scaled > 0 && part <= whole)
    return if (part == whole) value else Money(value.currency,
        (minor(value.minor) * ExactRational.ratio(part.scaled, whole.scaled)).roundHalfUp(0))
}

/** Version-pinned absolute weighted floors/remainders; allocation IDs settle exact ties. */
private fun storedSliceAllocation(value: Money, slices: List<CostedValueSlice>): Map<DomainId, Money> {
    val total = slices.fold(minor(0)) { sum, slice -> sum + minor(slice.quantity.scaled) }
    val exact = slices.associate { it.allocationId to minor(value.minor) * minor(it.quantity.scaled) / total }
    val floors = exact.mapValues { (_, amount) -> Money(value.currency, amount.floorScaled(0)) }
    val remainder = (minor(value.minor) - storedMoneySum(floors.values)).toScaledLongExact(0)
    inventorySnapshotCheck(remainder in 0..slices.size.toLong())
    val winners = exact.keys.sortedWith(compareByDescending<DomainId> {
        exact.getValue(it) - minor(floors.getValue(it).minor)
    }.thenBy { it.value }).take(remainder.toInt()).toSet()
    return floors.mapValues { (id, floor) -> floor + Money(value.currency, if (id in winners) 1 else 0) }
}

private fun storedDestinationKey(value: ValueDestination): String = when (value) {
    ValueDestination.Held -> "0"
    is ValueDestination.Consumed -> "1:${value.issueId.value}:${value.allocationId.value}"
    is ValueDestination.Transferred -> "2:${value.transferId.value}:${value.allocationId.value}"
}

internal fun storedMoneySum(values: Iterable<Money>): ExactRational = values.fold(minor(0)) { sum, value -> sum + minor(value.minor) }

internal fun storedValue(value: Money, currency: Currency, nonnegative: Boolean = true) {
    inventorySnapshotCheck(value.currency == currency && (!nonnegative || value.minor >= 0))
}

internal fun storedQuantity(value: Quantity, unit: DomainId, scale: Int, positive: Boolean = true) {
    inventorySnapshotCheck(value.unitId == unit && value.scale == scale && (if (positive) value.scaled > 0 else value.scaled >= 0))
}

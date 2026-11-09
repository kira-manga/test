package com.ukkera.pos.inventory

import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.allocateByLargestRemainder

public enum class ReceiptValueOrigin { PURCHASE, OPENING, TRANSFER_IN, PRODUCTION_OUTPUT, COUNT_SURPLUS }
public enum class ValueIssuePurpose { RETAIL, DIRECT_RECIPE, COUNT_LOSS, SUPPLIER_RETURN, TRANSFER, WORK_IN_PROGRESS }

public data class ValueSliceRequest(public val allocationId: DomainId, public val quantity: Quantity)
public data class CostedValueSlice(
    public val allocationId: DomainId,
    public val quantity: Quantity,
    public val originalValue: Money,
)

public data class ValueRestorationRequest(
    public val issueId: DomainId,
    public val allocationId: DomainId,
    public val quantity: Quantity,
)

public data class OriginalValueRestoration(
    public val issueId: DomainId,
    public val allocationId: DomainId,
    public val quantity: Quantity,
    public val originalValue: Money,
)

/** Immutable actual postings. Reference replay NEVER replaces an issue or return here. */
public sealed class ValuationFact(public val factId: DomainId) {
    public class Receipt internal constructor(
        factId: DomainId,
        public val quantity: Quantity,
        public val originalValue: Money,
        public val origin: ReceiptValueOrigin,
    ) : ValuationFact(factId)

    public class Issue internal constructor(
        factId: DomainId,
        slices: List<CostedValueSlice>,
        public val purpose: ValueIssuePurpose,
    ) : ValuationFact(factId) {
        public val slices: List<CostedValueSlice> = frozenList(slices)
    }

    public class TransferOut internal constructor(public val link: OwnedTransferValueLink) : ValuationFact(link.sourceEventId)
    public class TransferIn internal constructor(public val link: OwnedTransferValueLink) : ValuationFact(link.destinationEventId)

    public class Restoration internal constructor(
        factId: DomainId,
        restorations: List<OriginalValueRestoration>,
    ) : ValuationFact(factId) {
        public val restorations: List<OriginalValueRestoration> = frozenList(restorations)
    }
}

public data class ReceiptCostTarget(
    public val sourceId: DomainId,
    public val receiptId: DomainId,
    /** Cumulative desired delta for this one acquisition-charge source, not another payable. */
    public val targetDelta: Money,
)

public sealed class ValueDestination {
    public data object Held : ValueDestination()
    public data class Consumed(public val issueId: DomainId, public val allocationId: DomainId) : ValueDestination()
    /** A routing balance, never another terminal consumed-cost variance. */
    public data class Transferred(public val transferId: DomainId, public val allocationId: DomainId) : ValueDestination()
}

public data class DestinationValue(public val destination: ValueDestination, public val value: Money)

/** Current linked deltas, separate from historical cost/margin and from all quantities/payables. */
public class LinkedCostAdjustment internal constructor(
    public val adjustmentId: DomainId,
    public val basisRevision: Revision,
    public val changedSource: ReceiptCostTarget?,
    targets: List<ReceiptCostTarget>,
    deltas: List<DestinationValue>,
    transferTargets: List<IncomingTransferCostTarget> = emptyList(),
    public val transferCorrection: TransferCorrectionBasis? = null,
    public val calculationVersion: String = MovingAveragePlanner.CALCULATION_VERSION,
) {
    public val transferTargetBasis: List<IncomingTransferCostTarget> = frozenList(transferTargets)
    public val targetBasis: List<ReceiptCostTarget> = frozenList(targets)
    public val deltas: List<DestinationValue> = frozenList(deltas)
    public val heldDelta: Money get() =
        deltas.firstOrNull { it.destination == ValueDestination.Held }?.value ?: Money(Currency.EGP, 0)
    public val consumedDelta: Money get() = deltas.filter { it.destination is ValueDestination.Consumed }
        .fold(minor(0)) { sum, item -> sum + minor(item.value.minor) }
        .let { Money(Currency.EGP, it.toScaledLongExact(0)) }
}

/**
 * Bounded calculation state, NOT a database, journal reader, idempotency store or commit receipt.
 * The owner must persist actual facts and linked deltas with its command/result transaction.
 */
public class MovingAverageState internal constructor(
    public val unitId: DomainId,
    public val scale: Int,
    public val currency: Currency,
    public val revision: Revision,
    public val onHand: Quantity,
    public val carryingValue: Money,
    facts: List<ValuationFact>,
    targets: List<ReceiptCostTarget>,
    balances: List<DestinationValue>,
    adjustments: List<LinkedCostAdjustment>,
    eventIds: List<DomainId>,
    transferTargets: List<IncomingTransferCostTarget> = emptyList(),
) {
    public val facts: List<ValuationFact> = frozenList(facts)
    public val costTargets: List<ReceiptCostTarget> = frozenList(targets)
    public val linkedCorrectionBalances: List<DestinationValue> = frozenList(balances)
    public val adjustments: List<LinkedCostAdjustment> = frozenList(adjustments)
    public val eventIds: List<DomainId> = frozenList(eventIds)
    public val transferTargets: List<IncomingTransferCostTarget> = frozenList(transferTargets)
    public val hasTransferLineage: Boolean get() = facts.any { it is ValuationFact.TransferIn || it is ValuationFact.TransferOut } ||
        adjustments.any { it.transferCorrection != null }
    public val calculationVersion: String get() = if (hasTransferLineage) LinkedMovingAveragePlanner.CALCULATION_VERSION
        else MovingAveragePlanner.CALCULATION_VERSION

    public fun originalIssue(issueId: DomainId): ValuationFact.Issue? =
        facts.filterIsInstance<ValuationFact.Issue>().firstOrNull { it.factId == issueId }

    public fun restoredQuantity(issueId: DomainId, allocationId: DomainId): Quantity =
        facts.filterIsInstance<ValuationFact.Restoration>().flatMap { it.restorations }
            .filter { it.issueId == issueId && it.allocationId == allocationId }
            .fold(Quantity(unitId, 0, scale)) { total, item -> total + item.quantity }

    public companion object {
        public fun empty(unitId: DomainId, scale: Int, currency: Currency = Currency.EGP): MovingAverageState {
            val zero = Quantity(unitId, 0, scale)
            return MovingAverageState(unitId, scale, currency, Revision(0), zero, Money(currency, 0),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }
}

public class ValuationPlan internal constructor(
    public val requiredRevision: Revision,
    /** Pin this exact source history/targets in the durable source hash, not only a clock. */
    public val sourceBasis: MovingAverageState,
    public val next: MovingAverageState,
    public val fact: ValuationFact?,
    public val quantityDelta: Quantity,
    public val originalValueDelta: Money,
    public val linkedAdjustment: LinkedCostAdjustment?,
) {
    public val carryingValueDelta: Money get() = next.carryingValue - sourceBasis.carryingValue
}

/**
 * Single local pool reference valuation, with HALF_UP minor-unit issues, final-pool residual,
 * and stable largest-remainder slice allocation. Physical FEFO/receipt residues are not inputs.
 * Public transfer/WIP/output enum shortcuts reject. Paired same-branch transfers have a separate
 * coordinator; ordinary appends preserve its imported reference targets. Not the full F08 engine.
 */
public object MovingAveragePlanner {
    public const val CALCULATION_VERSION: String = "single-pool-reference-half-up-v1"
    public const val MAX_EVENTS: Int = 1_024
    public const val MAX_SLICES: Int = 256
    public const val MAX_COST_SOURCES: Int = 128

    public fun receive(
        state: MovingAverageState,
        expectedRevision: Revision,
        receiptId: DomainId,
        quantity: Quantity,
        originalValue: Money,
        origin: ReceiptValueOrigin = ReceiptValueOrigin.PURCHASE,
    ): ValuationPlan {
        preflight(state, expectedRevision, receiptId)
        quantityFor(state, quantity)
        guard(origin == ReceiptValueOrigin.PURCHASE || origin == ReceiptValueOrigin.OPENING,
            InventoryFailure.UNSUPPORTED_LINEAGE, "Transfers and production output need their own value lineage")
        requireValue(state, originalValue)
        val fact = ValuationFact.Receipt(receiptId, quantity, originalValue, origin)
        return transition(state, receiptId, fact, state.costTargets, null, quantity, originalValue)
    }

    public fun issue(
        state: MovingAverageState,
        expectedRevision: Revision,
        issueId: DomainId,
        slices: List<ValueSliceRequest>,
        purpose: ValueIssuePurpose = ValueIssuePurpose.RETAIL,
    ): ValuationPlan {
        preflight(state, expectedRevision, issueId)
        guard(purpose != ValueIssuePurpose.TRANSFER && purpose != ValueIssuePurpose.WORK_IN_PROGRESS,
            InventoryFailure.UNSUPPORTED_LINEAGE, "Transfer/WIP ownership is outside this terminal-issue engine")
        guard(slices.isNotEmpty() && slices.size <= MAX_SLICES,
            InventoryFailure.BOUND_EXCEEDED, "Issue slice bound exceeded")
        guard(slices.map { it.allocationId }.toSet().size == slices.size,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Duplicate issue allocation")
        slices.forEach { quantityFor(state, it.quantity) }
        val quantity = sumQuantities(state, slices.map { it.quantity })
        guard(quantity <= state.onHand, InventoryFailure.INSUFFICIENT_STOCK, "Issue exceeds owned pool quantity")
        val value = issueValue(state.carryingValue, quantity, state.onHand)
        val allocations = allocateSlices(value, slices)
        val fact = ValuationFact.Issue(issueId, allocations, purpose)
        return transition(state, issueId, fact, state.costTargets, null, -quantity, -value)
    }

    /** A separately approved count gain, not a purchase or an inferred new payable. */
    public fun countSurplus(
        state: MovingAverageState,
        expectedRevision: Revision,
        countId: DomainId,
        quantity: Quantity,
        approvedValue: Money,
    ): ValuationPlan {
        preflight(state, expectedRevision, countId)
        quantityFor(state, quantity)
        requireValue(state, approvedValue)
        // Both inputs and existing balances are nonnegative. Check remaining signed-64-bit
        // headroom before creating a fact/replaying, rather than leaking a scalar overflow.
        guard(quantity.scaled <= Long.MAX_VALUE - state.onHand.scaled,
            InventoryFailure.BOUND_EXCEEDED, "Count gain exceeds representable owned pool quantity")
        guard(approvedValue.minor <= Long.MAX_VALUE - state.carryingValue.minor,
            InventoryFailure.BOUND_EXCEEDED, "Count gain exceeds representable owned pool value")
        val fact = ValuationFact.Receipt(countId, quantity, approvedValue, ReceiptValueOrigin.COUNT_SURPLUS)
        return transition(state, countId, fact, state.costTargets, null, quantity, approvedValue)
    }

    public fun restoreOriginal(
        state: MovingAverageState,
        expectedRevision: Revision,
        restorationId: DomainId,
        requests: List<ValueRestorationRequest>,
    ): ValuationPlan {
        preflight(state, expectedRevision, restorationId)
        guard(requests.isNotEmpty() && requests.size <= MAX_SLICES,
            InventoryFailure.BOUND_EXCEEDED, "Restoration slice bound exceeded")
        guard(requests.map { it.issueId to it.allocationId }.toSet().size == requests.size,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Duplicate restoration allocation")
        val restorations = requests.map { request ->
            quantityFor(state, request.quantity)
            val issue = state.originalIssue(request.issueId)
                ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original issue is missing")
            guard(issue.purpose != ValueIssuePurpose.COUNT_LOSS && issue.purpose != ValueIssuePurpose.SUPPLIER_RETURN,
                InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "A loss/supplier dispatch is not a customer return")
            val slice = issue.slices.firstOrNull { it.allocationId == request.allocationId }
                ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original slice is missing")
            val previous = state.restoredQuantity(request.issueId, request.allocationId)
            val cumulative = previous + request.quantity
            guard(cumulative <= slice.quantity, InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Original allocation exhausted")
            val originalValue = cumulativeValue(slice.originalValue, cumulative, slice.quantity) -
                cumulativeValue(slice.originalValue, previous, slice.quantity)
            OriginalValueRestoration(request.issueId, request.allocationId, request.quantity, originalValue)
        }
        val fact = ValuationFact.Restoration(restorationId, restorations)
        val quantity = sumQuantities(state, restorations.map { it.quantity })
        val value = sumMoney(state.currency, restorations.map { it.originalValue })
        return transition(state, restorationId, fact, state.costTargets, null, quantity, value)
    }

    /** Amend/cancel a source by its cumulative target; changing a target never creates a payable. */
    public fun adjustReceiptCost(
        state: MovingAverageState,
        expectedRevision: Revision,
        adjustmentId: DomainId,
        sourceId: DomainId,
        receiptId: DomainId,
        targetDelta: Money,
    ): ValuationPlan {
        preflight(state, expectedRevision, adjustmentId)
        guard(!state.hasTransferLineage, InventoryFailure.UNSUPPORTED_LINEAGE,
            "A transferred pool requires a complete linked correction frontier")
        guard(targetDelta.currency == state.currency, InventoryFailure.INVALID_STATE, "Cost currency differs")
        val receipt = state.facts.filterIsInstance<ValuationFact.Receipt>().firstOrNull { it.factId == receiptId }
            ?: throw InventoryRejection(InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "Eligible receipt is missing")
        guard(receipt.origin == ReceiptValueOrigin.PURCHASE, InventoryFailure.UNSUPPORTED_LINEAGE,
            "Late acquisition charges must name a local purchase receipt")
        val prior = state.costTargets.firstOrNull { it.sourceId == sourceId }
        guard(prior == null || prior.receiptId == receiptId,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "A charge source cannot move to a different receipt")
        guard(prior != null || state.costTargets.size < MAX_COST_SOURCES,
            InventoryFailure.BOUND_EXCEEDED, "Cost source bound exceeded")
        val target = ReceiptCostTarget(sourceId, receiptId, targetDelta)
        val targets = (state.costTargets.filterNot { it.sourceId == sourceId } + target).sortedBy { it.sourceId.value }
        return transition(state, adjustmentId, null, targets, target, Quantity(state.unitId, 0, state.scale),
            Money(state.currency, 0))
    }

    internal fun transferOut(state: MovingAverageState, link: OwnedTransferValueLink): ValuationPlan {
        requireTransferLink(link)
        preflight(state, link.sourceRevision, link.sourceEventId)
        quantityFor(state, link.quantity)
        guard(link.originalValue == issueValue(state.carryingValue, link.quantity, state.onHand),
            InventoryFailure.INVALID_STATE, "Transfer must carry the current source-pool value")
        return transition(state, link.sourceEventId, ValuationFact.TransferOut(link), state.costTargets, null,
            -link.quantity, -link.originalValue)
    }

    internal fun transferIn(state: MovingAverageState, link: OwnedTransferValueLink): ValuationPlan {
        requireTransferLink(link)
        preflight(state, link.destinationRevision, link.destinationEventId)
        quantityFor(state, link.quantity)
        requireValue(state, link.originalValue)
        val imports = (state.transferTargets + IncomingTransferCostTarget(link.destinationEventId, Money(state.currency, 0)))
            .sortedBy { it.receiptEventId.value }
        return transition(state, link.destinationEventId, ValuationFact.TransferIn(link), state.costTargets, null,
            link.quantity, link.originalValue, imports)
    }

    internal fun linkedCorrection(state: MovingAverageState, pool: StockPoolKey, basis: TransferCorrectionBasis,
        imports: List<IncomingTransferCostTarget>): ValuationPlan {
        preflight(state, basis.pools.single { it.pool == pool }.revision, basis.adjustmentId)
        val changed = basis.changedSource.takeIf { pool == basis.sourcePool }
        val targets = if (changed == null) state.costTargets else
            (state.costTargets.filterNot { it.sourceId == changed.sourceId } + changed).sortedBy { it.sourceId.value }
        guard(targets.size <= MAX_COST_SOURCES, InventoryFailure.BOUND_EXCEEDED, "Cost source bound exceeded")
        return transition(state, basis.adjustmentId, null, targets, changed, Quantity(state.unitId, 0, state.scale),
            Money(state.currency, 0), imports, basis)
    }

    private fun preflight(state: MovingAverageState, expectedRevision: Revision, eventId: DomainId) {
        requireRevision(state.revision, expectedRevision)
        guard(eventId !in state.eventIds, InventoryFailure.DUPLICATE_SOURCE, "Valuation source already posted")
        guard(state.eventIds.size < MAX_EVENTS, InventoryFailure.BOUND_EXCEEDED, "Bounded valuation history exhausted")
        guard(state.onHand.scaled >= 0 && state.carryingValue.minor >= 0 &&
            (state.onHand.scaled != 0L || state.carryingValue.minor == 0L),
            InventoryFailure.INVALID_STATE, "Inconsistent pre-existing quantity/value needs reconciliation")
    }

    private fun transition(
        state: MovingAverageState,
        eventId: DomainId,
        fact: ValuationFact?,
        targets: List<ReceiptCostTarget>,
        changedSource: ReceiptCostTarget?,
        quantityDelta: Quantity,
        originalValueDelta: Money,
        transferTargets: List<IncomingTransferCostTarget> = state.transferTargets,
        transferCorrection: TransferCorrectionBasis? = null,
    ): ValuationPlan {
        val facts = if (fact == null) state.facts else state.facts + fact
        val reference = replay(state, facts, targets, transferTargets)
        val original = originalDestinations(state, facts)
        val desired = reference.values.map { (destination, value) ->
            DestinationValue(destination, Money(state.currency,
                (minor(value.minor) - (original[destination] ?: minor(0))).toScaledLongExact(0)))
        }.sortedBy { valueDestinationOrder(it.destination) }
        val previous = state.linkedCorrectionBalances.associate { it.destination to it.value }
        val deltas = desired.map { balance ->
            DestinationValue(balance.destination, balance.value - (previous[balance.destination] ?: Money(state.currency, 0)))
        }.filter { it.value.minor != 0L }
        // All historic consumed destinations remain in replay even after a full original return.
        check(previous.keys.all { old -> desired.any { it.destination == old } })
        val targetChange = sumExact(targets.map { it.targetDelta }) - sumExact(state.costTargets.map { it.targetDelta }) +
            sumExact(transferTargets.map { it.targetDelta }) - sumExact(state.transferTargets.map { it.targetDelta })
        check(sumExact(deltas.map { it.value }) == targetChange) { "Correction value is not conserved" }
        val heldDelta = deltas.firstOrNull { it.destination == ValueDestination.Held }?.value ?: Money(state.currency, 0)
        check(state.onHand + quantityDelta == reference.quantity)
        check(minor(state.carryingValue.minor) + minor(originalValueDelta.minor) + minor(heldDelta.minor) ==
            minor(reference.held.minor)) { "Original posting plus linked correction does not reconcile" }
        val adjustment = if (changedSource != null || transferCorrection != null || deltas.isNotEmpty()) {
            val version = if (state.hasTransferLineage || transferCorrection != null || facts.any { it is ValuationFact.TransferIn || it is ValuationFact.TransferOut })
                LinkedMovingAveragePlanner.CALCULATION_VERSION else CALCULATION_VERSION
            LinkedCostAdjustment(eventId, state.revision, changedSource, targets, deltas, transferTargets, transferCorrection, version)
        } else null
        val next = MovingAverageState(state.unitId, state.scale, state.currency, state.revision.next(),
            reference.quantity, reference.held, facts, targets, desired,
            if (adjustment == null) state.adjustments else state.adjustments + adjustment, state.eventIds + eventId, transferTargets)
        return ValuationPlan(state.revision, state, next, fact, quantityDelta, originalValueDelta, adjustment)
    }

    private fun replay(
        state: MovingAverageState,
        facts: List<ValuationFact>,
        targets: List<ReceiptCostTarget>,
        transferTargets: List<IncomingTransferCostTarget>,
    ): ReferenceResult {
        var quantity = Quantity(state.unitId, 0, state.scale)
        var held = Money(state.currency, 0)
        val issues = mutableMapOf<Pair<DomainId, DomainId>, ReferenceSlice>()
        val transferred = linkedMapOf<ValueDestination.Transferred, Money>()
        val imports = transferTargets.associate { it.receiptEventId to it.targetDelta }
        guard(imports.size == transferTargets.size && imports.keys == facts.filterIsInstance<ValuationFact.TransferIn>().map { it.factId }.toSet(),
            InventoryFailure.INVALID_STATE, "Incomplete incoming transfer reference targets")
        val deltasByReceipt = targets.groupBy { it.receiptId }.mapValues { (_, values) ->
            sumExact(values.map { it.targetDelta })
        }
        for (fact in facts) {
            when (fact) {
                is ValuationFact.Receipt -> {
                    guard(fact.origin == ReceiptValueOrigin.PURCHASE || fact.origin == ReceiptValueOrigin.OPENING ||
                        fact.origin == ReceiptValueOrigin.COUNT_SURPLUS,
                        InventoryFailure.UNSUPPORTED_LINEAGE, "Unsupported incoming value lineage")
                    val value = (minor(fact.originalValue.minor) + (deltasByReceipt[fact.factId] ?: minor(0))).toScaledLongExact(0)
                    guard(value >= 0, InventoryFailure.NEGATIVE_VALUE, "Cost reduction makes a receipt negative")
                    quantity += fact.quantity
                    held += Money(state.currency, value)
                }
                is ValuationFact.TransferIn -> {
                    requireTransferLink(fact.link)
                    val value = fact.link.originalValue + imports.getValue(fact.factId)
                    requireValue(state, value)
                    quantity += fact.link.quantity
                    held += value
                }
                is ValuationFact.TransferOut -> {
                    requireTransferLink(fact.link)
                    val value = issueValue(held, fact.link.quantity, quantity)
                    transferred[transferDestination(fact.link)] = value
                    quantity -= fact.link.quantity
                    held -= value
                }
                is ValuationFact.Issue -> {
                    guard(fact.purpose != ValueIssuePurpose.TRANSFER && fact.purpose != ValueIssuePurpose.WORK_IN_PROGRESS,
                        InventoryFailure.UNSUPPORTED_LINEAGE, "Unsupported outgoing value lineage")
                    val issuedQuantity = sumQuantities(state, fact.slices.map { it.quantity })
                    guard(issuedQuantity <= quantity, InventoryFailure.INVALID_STATE, "Incomplete reference history")
                    val value = issueValue(held, issuedQuantity, quantity)
                    val slices = allocateSlices(value, fact.slices.map { ValueSliceRequest(it.allocationId, it.quantity) })
                    slices.forEach { slice ->
                        issues[fact.factId to slice.allocationId] = ReferenceSlice(slice.quantity, slice.originalValue,
                            Quantity(state.unitId, 0, state.scale), Money(state.currency, 0))
                    }
                    quantity -= issuedQuantity
                    held -= value
                }
                is ValuationFact.Restoration -> fact.restorations.forEach { restoration ->
                    val key = restoration.issueId to restoration.allocationId
                    val slice = issues[key] ?: error("Missing original reference allocation")
                    val cumulative = slice.restored + restoration.quantity
                    check(cumulative <= slice.quantity)
                    val returnedValue = cumulativeValue(slice.value, cumulative, slice.quantity)
                    held += returnedValue - slice.restoredValue
                    quantity += restoration.quantity
                    issues[key] = slice.copy(restored = cumulative, restoredValue = returnedValue)
                }
            }
            guard(held.minor >= 0 && (quantity.scaled != 0L || held.minor == 0L),
                InventoryFailure.NEGATIVE_VALUE, "Reference value cannot be negative or orphaned at zero quantity")
        }
        val values = mutableMapOf<ValueDestination, Money>(ValueDestination.Held to held)
        issues.forEach { (key, slice) -> values[ValueDestination.Consumed(key.first, key.second)] = slice.value - slice.restoredValue }
        values.putAll(transferred)
        return ReferenceResult(quantity, held, values)
    }

    internal fun originalDestinations(state: MovingAverageState, facts: List<ValuationFact>): Map<ValueDestination, ExactRational> {
        val values = mutableMapOf<ValueDestination, ExactRational>(ValueDestination.Held to minor(0))
        var held = minor(0)
        for (fact in facts) {
            when (fact) {
                is ValuationFact.Receipt -> held += minor(fact.originalValue.minor)
                is ValuationFact.TransferIn -> held += minor(fact.link.originalValue.minor)
                is ValuationFact.TransferOut -> {
                    held -= minor(fact.link.originalValue.minor)
                    values[transferDestination(fact.link)] = minor(fact.link.originalValue.minor)
                }
                is ValuationFact.Issue -> fact.slices.forEach { slice ->
                    held -= minor(slice.originalValue.minor)
                    values[ValueDestination.Consumed(fact.factId, slice.allocationId)] = minor(slice.originalValue.minor)
                }
                is ValuationFact.Restoration -> fact.restorations.forEach { restoration ->
                    held += minor(restoration.originalValue.minor)
                    val key = ValueDestination.Consumed(restoration.issueId, restoration.allocationId)
                    values[key] = values.getValue(key) - minor(restoration.originalValue.minor)
                }
            }
        }
        values[ValueDestination.Held] = held
        check(values.values.fold(minor(0)) { total, value -> total + value } ==
            sumExact(facts.filterIsInstance<ValuationFact.Receipt>().map { it.originalValue }) +
                sumExact(facts.filterIsInstance<ValuationFact.TransferIn>().map { it.link.originalValue }))
        check(state.currency == Currency.EGP)
        return values
    }

    internal fun issueValue(value: Money, issued: Quantity, onHand: Quantity): Money {
        positive(issued)
        requireSameUnit(issued, onHand)
        guard(issued <= onHand, InventoryFailure.INSUFFICIENT_STOCK, "No negative pool quantity")
        // Explicit residual ownership: zero units must leave exactly zero minor units.
        if (issued == onHand) return value
        return Money(value.currency, (minor(value.minor) * ExactRational.ratio(issued.scaled, onHand.scaled)).roundHalfUp(0))
    }

    private fun cumulativeValue(value: Money, cumulative: Quantity, total: Quantity): Money {
        requireSameUnit(cumulative, total)
        if (cumulative == total) return value
        return Money(value.currency, (minor(value.minor) * ExactRational.ratio(cumulative.scaled, total.scaled)).roundHalfUp(0))
    }

    private fun allocateSlices(value: Money, slices: List<ValueSliceRequest>): List<CostedValueSlice> {
        val amounts = allocateByLargestRemainder(value, slices.map { AllocationWeight(it.allocationId.value, it.quantity.scaled) })
            .associate { it.key to it.amount }
        return slices.map { CostedValueSlice(it.allocationId, it.quantity, amounts.getValue(it.allocationId.value)) }
    }

    private fun quantityFor(state: MovingAverageState, quantity: Quantity) {
        positive(quantity)
        requireSameUnit(Quantity(state.unitId, 0, state.scale), quantity)
    }

    private fun requireValue(state: MovingAverageState, value: Money) {
        guard(value.currency == state.currency && value.minor >= 0,
            InventoryFailure.NEGATIVE_VALUE, "Owned acquisition value must be nonnegative in the pool currency")
    }

    private fun sumQuantities(state: MovingAverageState, quantities: List<Quantity>): Quantity =
        quantities.fold(Quantity(state.unitId, 0, state.scale)) { sum, item -> sum + item }

    private fun sumMoney(currency: Currency, values: List<Money>): Money = Money(currency, sumExact(values).toScaledLongExact(0))
    private fun sumExact(values: List<Money>): ExactRational = values.fold(minor(0)) { sum, item -> sum + minor(item.minor) }

}

private data class ReferenceSlice(val quantity: Quantity, val value: Money, val restored: Quantity, val restoredValue: Money)
private data class ReferenceResult(val quantity: Quantity, val held: Money, val values: Map<ValueDestination, Money>)

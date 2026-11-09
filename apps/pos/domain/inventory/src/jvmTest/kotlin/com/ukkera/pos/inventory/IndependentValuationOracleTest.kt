package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent JDK integers; never uses the production rational, rounding or allocation helper. */
class IndependentValuationOracleTest {
    @Test fun randomizedReceiptsIssuesPartialReturnsAndAmendedCostsMatchIndependentReplay() {
        repeat(24) { seed ->
            val random = Random(97_103 + seed)
            val ids = IdSequence(10_000 + seed * 2_000)
            var state = MovingAverageState.empty(unit, 6)
            val receipts = mutableListOf<ValuationFact.Receipt>()
            val issued = mutableListOf<Pair<DomainId, CostedValueSlice>>()
            val returned = mutableMapOf<Pair<DomainId, DomainId>, Long>()
            val sources = mutableMapOf<DomainId, DomainId>()
            repeat(70) { step ->
                val previousFacts = state.facts.toList()
                val candidates = issued.filter { (issue, slice) ->
                    returned.getOrElse(issue to slice.allocationId) { 0 } < slice.quantity.scaled
                }
                val choice = random.nextInt(10)
                val plan = when {
                    receipts.isEmpty() || choice < 3 -> {
                        val result = MovingAveragePlanner.receive(state, state.revision, ids.next(),
                            quantity(random.nextLong(1, 16), 6), money(random.nextLong(0, 10_001)))
                        receipts += result.fact as ValuationFact.Receipt
                        result
                    }
                    choice < 6 && state.onHand.scaled > 0 -> {
                        val q = random.nextLong(1, state.onHand.scaled + 1)
                        val issueId = ids.next()
                        val slices = if (q >= 2 && random.nextBoolean()) {
                            val first = random.nextLong(1, q)
                            listOf(ValueSliceRequest(ids.next(), quantity(first, 6)), ValueSliceRequest(ids.next(), quantity(q - first, 6)))
                                .let { if (random.nextBoolean()) it.reversed() else it }
                        } else listOf(ValueSliceRequest(ids.next(), quantity(q, 6)))
                        val result = MovingAveragePlanner.issue(state, state.revision, issueId, slices)
                        (result.fact as ValuationFact.Issue).slices.forEach { issued += issueId to it }
                        result
                    }
                    choice < 8 && candidates.isNotEmpty() -> {
                        val (issueId, slice) = candidates.random(random)
                        val key = issueId to slice.allocationId
                        val previous = returned.getOrElse(key) { 0 }
                        val q = random.nextLong(1, slice.quantity.scaled - previous + 1)
                        val result = MovingAveragePlanner.restoreOriginal(state, state.revision, ids.next(),
                            listOf(ValueRestorationRequest(issueId, slice.allocationId, quantity(q, 6))))
                        returned[key] = previous + q
                        result
                    }
                    else -> {
                        val receipt = receipts.random(random)
                        val base = receipt.originalValue.minor
                        val target = if (random.nextInt(4) == 0) 0 else random.nextLong(-base, base * 2 + 101)
                        MovingAveragePlanner.adjustReceiptCost(state, state.revision, ids.next(),
                            sources.getOrPut(receipt.factId) { ids.next() }, receipt.factId, money(target))
                    }
                }
                state = plan.next
                assertTrue(previousFacts.indices.all { previousFacts[it] === state.facts[it] }, "Historical fact was replaced seed=$seed step=$step")
                assertEquals(state.onHand.scaled, (bi(plan.sourceBasis.onHand.scaled) + bi(plan.quantityDelta.scaled)).longValueExact())
                JdkReferenceOracle.verify(state, "seed=$seed step=$step")
            }
        }
    }

    @Test fun movingAverageWideIntegerBoundaryMatchesJdkOracleWithoutIntermediateOverflow() {
        val random = Random(813)
        repeat(180) { index ->
            val ids = IdSequence(100_000 + index * 10)
            val q = if (index == 0) Long.MAX_VALUE else random.nextLong(1, Long.MAX_VALUE)
            val value = if (index == 0) Long.MAX_VALUE else random.nextLong(0, Long.MAX_VALUE)
            val issued = if (index % 3 == 0) q else random.nextLong(1, q + if (q < Long.MAX_VALUE) 1 else 0)
            var state = MovingAverageState.empty(unit, 6)
            state = MovingAveragePlanner.receive(state, state.revision, ids.next(), quantity(q, 6), money(value)).next
            val plan = MovingAveragePlanner.issue(state, state.revision, ids.next(), listOf(ValueSliceRequest(ids.next(), quantity(issued, 6))))
            val expected = if (issued == q) bi(value) else roundNonnegative(bi(value) * bi(issued), bi(q))
            assertEquals(expected.longValueExact(), -(plan.originalValueDelta.minor), "wide case $index")
            assertEquals((bi(value) - expected).longValueExact(), plan.next.carryingValue.minor)
            JdkReferenceOracle.verify(plan.next, "wide=$index")
        }
    }

    @Test fun oppositeSourceApplicationOrderHasSameCurrentReferenceDestinations() {
        fun scenario(reverse: Boolean): MovingAverageState {
            val ids = IdSequence()
            var state = MovingAverageState.empty(unit, 0)
            state = MovingAveragePlanner.receive(state, state.revision, id(500), quantity(7), money(101)).next
            state = MovingAveragePlanner.receive(state, state.revision, id(501), quantity(4), money(203)).next
            state = MovingAveragePlanner.issue(state, state.revision, id(502),
                listOf(ValueSliceRequest(id(503), quantity(2)), ValueSliceRequest(id(504), quantity(3)))).next
            val targets = listOf(Triple(id(505), id(500), 37L), Triple(id(506), id(501), -29L))
                .let { if (reverse) it.reversed() else it }
            for ((source, receipt, delta) in targets) {
                state = MovingAveragePlanner.adjustReceiptCost(state, state.revision, ids.next(), source, receipt, money(delta)).next
                JdkReferenceOracle.verify(state, "order=$reverse")
            }
            state = MovingAveragePlanner.restoreOriginal(state, state.revision, ids.next(),
                listOf(ValueRestorationRequest(id(502), id(504), quantity(1)))).next
            JdkReferenceOracle.verify(state, "returned order=$reverse")
            return state
        }
        val a = scenario(false)
        val b = scenario(true)
        assertEquals(a.carryingValue, b.carryingValue)
        assertEquals(a.linkedCorrectionBalances, b.linkedCorrectionBalances)
        assertEquals(a.originalIssue(id(502))!!.slices, b.originalIssue(id(502))!!.slices)
    }
}

private object JdkReferenceOracle {
    fun verify(state: MovingAverageState, label: String) {
        var quantity = BigInteger.ZERO
        var held = BigInteger.ZERO
        var originalHeld = BigInteger.ZERO
        var originalReceipts = BigInteger.ZERO
        val slices = mutableMapOf<Pair<DomainId, DomainId>, OracleSlice>()
        val targetDeltas = state.costTargets.groupBy { it.receiptId }.mapValues { (_, targets) ->
            targets.fold(BigInteger.ZERO) { sum, target -> sum + bi(target.targetDelta.minor) }
        }
        for (fact in state.facts) {
            when (fact) {
                is ValuationFact.Receipt -> {
                    val original = bi(fact.originalValue.minor)
                    val corrected = original + targetDeltas.getOrDefault(fact.factId, BigInteger.ZERO)
                    assertTrue(corrected.signum() >= 0, label)
                    quantity += bi(fact.quantity.scaled)
                    held += corrected
                    originalHeld += original
                    originalReceipts += original
                }
                is ValuationFact.Issue -> {
                    val issueQuantity = fact.slices.fold(BigInteger.ZERO) { sum, slice -> sum + bi(slice.quantity.scaled) }
                    val issueValue = if (issueQuantity == quantity) held else roundNonnegative(held * issueQuantity, quantity)
                    val allocated = allocateIndependent(issueValue, fact.slices.map { it.allocationId to bi(it.quantity.scaled) })
                    fact.slices.forEach { slice ->
                        slices[fact.factId to slice.allocationId] = OracleSlice(bi(slice.quantity.scaled), bi(slice.originalValue.minor),
                            allocated.getValue(slice.allocationId))
                        originalHeld -= bi(slice.originalValue.minor)
                    }
                    quantity -= issueQuantity
                    held -= issueValue
                }
                is ValuationFact.Restoration -> fact.restorations.forEach { restoration ->
                    val slice = slices.getValue(restoration.issueId to restoration.allocationId)
                    val cumulative = slice.returnedQuantity + bi(restoration.quantity.scaled)
                    assertTrue(cumulative <= slice.quantity, label)
                    val originalReturned = roundNonnegative(slice.original * cumulative, slice.quantity)
                    val referenceReturned = roundNonnegative(slice.reference * cumulative, slice.quantity)
                    assertEquals((originalReturned - slice.originalReturned).longValueExact(), restoration.originalValue.minor,
                        "Original return was repriced $label")
                    originalHeld += originalReturned - slice.originalReturned
                    held += referenceReturned - slice.referenceReturned
                    quantity += bi(restoration.quantity.scaled)
                    slice.returnedQuantity = cumulative
                    slice.originalReturned = originalReturned
                    slice.referenceReturned = referenceReturned
                }
                // These unchanged fixtures generate receipts, issues and restorations only.
                // Do not invent a transfer oracle or silently ignore a new fact kind.
                is ValuationFact.TransferIn, is ValuationFact.TransferOut ->
                    error("Transfer facts require separately designed valuation cases")
            }
            assertTrue(quantity.signum() >= 0 && held.signum() >= 0, "Negative reference state $label")
            if (quantity == BigInteger.ZERO) assertEquals(BigInteger.ZERO, held, "Orphan value $label")
        }
        assertEquals(quantity.longValueExact(), state.onHand.scaled, label)
        assertEquals(held.longValueExact(), state.carryingValue.minor, label)
        val expected = mutableMapOf<ValueDestination, BigInteger>(ValueDestination.Held to (held - originalHeld))
        slices.forEach { (key, slice) ->
            expected[ValueDestination.Consumed(key.first, key.second)] =
                (slice.reference - slice.referenceReturned) - (slice.original - slice.originalReturned)
        }
        val actual = state.linkedCorrectionBalances.associate { it.destination to bi(it.value.minor) }
        assertEquals(expected, actual, "Destination lineage mismatch $label")
        val originalConsumed = slices.values.fold(BigInteger.ZERO) { sum, slice -> sum + slice.original - slice.originalReturned }
        val referenceConsumed = slices.values.fold(BigInteger.ZERO) { sum, slice -> sum + slice.reference - slice.referenceReturned }
        val correctionConsumed = actual.filterKeys { it is ValueDestination.Consumed }.values.fold(BigInteger.ZERO, BigInteger::add)
        val charge = state.costTargets.fold(BigInteger.ZERO) { sum, target -> sum + bi(target.targetDelta.minor) }
        assertEquals(originalReceipts, originalHeld + originalConsumed, "Original posting conservation $label")
        assertEquals(originalReceipts + charge, held + referenceConsumed, "Reference conservation $label")
        assertEquals(originalReceipts + charge, bi(state.carryingValue.minor) + originalConsumed + correctionConsumed,
            "Actual book plus separately linked correction conservation $label")
        assertEquals(charge, expected.values.fold(BigInteger.ZERO, BigInteger::add), "Charge was created twice $label")
        val applied = mutableMapOf<ValueDestination, BigInteger>()
        val previousTargets = mutableMapOf<DomainId, BigInteger>()
        for (adjustment in state.adjustments) {
            val change = adjustment.changedSource?.let { source ->
                val previous = previousTargets.put(source.sourceId, bi(source.targetDelta.minor)) ?: BigInteger.ZERO
                bi(source.targetDelta.minor) - previous
            } ?: BigInteger.ZERO
            assertEquals(change, adjustment.deltas.fold(BigInteger.ZERO) { sum, delta -> sum + bi(delta.value.minor) },
                "One correction/reclassification does not conserve $label")
            adjustment.deltas.forEach { delta ->
                applied[delta.destination] = applied.getOrDefault(delta.destination, BigInteger.ZERO) + bi(delta.value.minor)
            }
        }
        expected.forEach { (destination, value) -> assertEquals(value, applied.getOrDefault(destination, BigInteger.ZERO), label) }
    }
}

private class OracleSlice(val quantity: BigInteger, val original: BigInteger, val reference: BigInteger) {
    var returnedQuantity: BigInteger = BigInteger.ZERO
    var originalReturned: BigInteger = BigInteger.ZERO
    var referenceReturned: BigInteger = BigInteger.ZERO
}

private fun allocateIndependent(total: BigInteger, weights: List<Pair<DomainId, BigInteger>>): Map<DomainId, BigInteger> {
    val denominator = weights.fold(BigInteger.ZERO) { sum, weight -> sum + weight.second }
    val quotients = weights.map { (id, weight) ->
        val qr = (total * weight).divideAndRemainder(denominator)
        Triple(id, qr[0], qr[1])
    }
    val left = (total - quotients.fold(BigInteger.ZERO) { sum, item -> sum + item.second }).intValueExact()
    val winners = quotients.sortedWith { a, b ->
        val remainder = b.third.compareTo(a.third)
        if (remainder == 0) a.first.value.compareTo(b.first.value) else remainder
    }.take(left).map { it.first }.toSet()
    return quotients.associate { it.first to (it.second + if (it.first in winners) BigInteger.ONE else BigInteger.ZERO) }
}

private fun roundNonnegative(numerator: BigInteger, denominator: BigInteger): BigInteger {
    require(numerator.signum() >= 0 && denominator.signum() > 0)
    val qr = numerator.divideAndRemainder(denominator)
    return qr[0] + if (qr[1].shiftLeft(1) >= denominator) BigInteger.ONE else BigInteger.ZERO
}

private fun bi(value: Long): BigInteger = BigInteger.valueOf(value)

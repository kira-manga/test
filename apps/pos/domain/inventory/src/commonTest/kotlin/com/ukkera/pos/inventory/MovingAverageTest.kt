package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MovingAverageTest {
    @Test fun movingAverageAndSupplierReturnKeepHistoricalSaleAndClaimVarianceSeparate() {
        val f = ValueFixture()
        f.receive(10, 20_000)
        f.receive(10, 30_000)
        val sale = f.issue(10)
        assertEquals(money(25_000), sale.slices.single().originalValue)
        assertEquals(money(25_000), f.state.carryingValue)
        val dispatched = f.issue(10, ValueIssuePurpose.SUPPLIER_RETURN)
        assertEquals(money(25_000), dispatched.slices.single().originalValue)
        val supplierClaim = money(30_000) // A separate procurement fact, never an inventory receipt reversal.
        assertEquals(money(5_000), supplierClaim - dispatched.slices.single().originalValue)
        assertEquals(quantity(0), f.state.onHand)
        assertEquals(money(0), f.state.carryingValue)
        assertEquals(money(25_000), sale.slices.single().originalValue)
        fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) { f.restore(dispatched, 1) }
    }

    @Test fun finalPoolIssueAbsorbsResidualAndFractionalQuantitiesStayExact() {
        val ids = IdSequence()
        var state = MovingAverageState.empty(unit, 6)
        state = MovingAveragePlanner.receive(state, state.revision, ids.next(), quantity(3, 6), money(1)).next
        val values = mutableListOf<Long>()
        repeat(3) {
            val p = MovingAveragePlanner.issue(state, state.revision, ids.next(), listOf(ValueSliceRequest(ids.next(), quantity(1, 6))))
            values += (p.fact as ValuationFact.Issue).slices.single().originalValue.minor
            state = p.next
        }
        assertEquals(listOf(0L, 1L, 0L), values)
        assertEquals(quantity(0, 6), state.onHand)
        assertEquals(money(0), state.carryingValue)
        assertEquals(1L, values.sum())
    }

    @Test fun originalReturnRestoresOriginalCostIntoChangedAverageNotTodaysCost() {
        val f = ValueFixture()
        f.receive(10, 20_000)
        val original = f.issue(5)
        f.receive(5, 20_000)
        assertEquals(money(30_000), f.state.carryingValue)
        val restored = f.restore(original, 2)
        assertEquals(money(4_000), restored.originalValueDelta)
        assertEquals(money(34_000), f.state.carryingValue)
        assertEquals(quantity(12), f.state.onHand)
        assertEquals(money(10_000), original.slices.single().originalValue)
    }

    @Test fun repeatedPartialOriginalReturnsUseCumulativeCursorAndFinalResidual() {
        val f = ValueFixture()
        f.receive(3, 2)
        val original = f.issue(3)
        val restored = List(3) { f.restore(original, 1).originalValueDelta.minor }
        assertEquals(listOf(1L, 0L, 1L), restored)
        assertEquals(money(2), f.state.carryingValue)
        fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) { f.restore(original, 1) }
        assertEquals(quantity(3), f.state.onHand)
    }

    @Test fun lateCostUsesPooledValueLineageNinetyThirtyNotReceiptLotSixtySixty() {
        val f = ValueFixture()
        val first = f.receive(10, 20_000)
        f.receive(10, 30_000)
        val originalSale = f.issue(5)
        val correction = f.correct(first, 12_000)
        val adjustment = correction.linkedAdjustment!!
        assertEquals(money(9_000), adjustment.heldDelta)
        assertEquals(money(3_000), adjustment.consumedDelta)
        assertEquals(money(46_500), f.state.carryingValue)
        assertEquals(quantity(15), f.state.onHand)
        assertEquals(quantity(0), correction.quantityDelta)
        assertEquals(money(0), correction.originalValueDelta)
        assertEquals(money(12_500), originalSale.slices.single().originalValue)
        assertSame(originalSale, f.state.originalIssue(originalSale.factId))
    }

    @Test fun returnAfterLateCostRestoresOriginalFiftyPlusSeparateTwelveReclassification() {
        val f = ValueFixture()
        val first = f.receive(10, 20_000)
        f.receive(10, 30_000)
        val sale = f.issue(5)
        f.correct(first, 12_000)
        val restored = f.restore(sale, 2)
        assertEquals(money(5_000), restored.originalValueDelta)
        assertEquals(money(1_200), restored.linkedAdjustment!!.heldDelta)
        assertEquals(money(-1_200), restored.linkedAdjustment!!.consumedDelta)
        assertEquals(money(52_700), f.state.carryingValue)
        assertEquals(quantity(17), f.state.onHand)
        assertEquals(money(12_500), sale.slices.single().originalValue)
        val originalRestoration = assertIs<ValuationFact.Restoration>(restored.fact)
        assertEquals(money(5_000), originalRestoration.restorations.single().originalValue)
    }

    @Test fun lateCostAfterReturnUsesStillConsumedValueRatherThanAllHistoricalIssues() {
        val f = ValueFixture()
        val first = f.receive(10, 20_000)
        f.receive(10, 30_000)
        val sale = f.issue(5)
        f.restore(sale, 2)
        val adjustment = f.correct(first, 12_000).linkedAdjustment!!
        assertEquals(money(10_200), adjustment.heldDelta)
        assertEquals(money(1_800), adjustment.consumedDelta)
        assertEquals(money(52_700), f.state.carryingValue)
    }

    @Test fun cancellingCostAfterReturnReversesCurrentOwnershipNotFirstNinetyThirtySplit() {
        val f = ValueFixture()
        val first = f.receive(10, 20_000)
        f.receive(10, 30_000)
        val sale = f.issue(5)
        f.correct(first, 12_000)
        f.restore(sale, 2)
        val cancelled = f.correct(first, 0).linkedAdjustment!!
        assertEquals(money(-10_200), cancelled.heldDelta)
        assertEquals(money(-1_800), cancelled.consumedDelta)
        assertEquals(money(42_500), f.state.carryingValue)
        assertTrue(f.state.linkedCorrectionBalances.all { it.value.minor == 0L })
        val repeated = f.correct(first, 0)
        assertEquals(emptyList(), repeated.linkedAdjustment!!.deltas)
        assertEquals(money(42_500), repeated.next.carryingValue)
    }

    @Test fun cancellationAfterNewIssuePreservesBothActualHistoricalIssueSnapshots() {
        val f = ValueFixture()
        val first = f.receive(10, 20_000)
        f.receive(10, 30_000)
        val beforeCost = f.issue(5)
        f.correct(first, 12_000)
        val afterCost = f.issue(5)
        assertEquals(money(15_500), afterCost.slices.single().originalValue)
        val cancelled = f.correct(first, 0).linkedAdjustment!!
        assertEquals(money(-6_000), cancelled.heldDelta)
        assertEquals(money(-6_000), cancelled.consumedDelta)
        assertEquals(money(25_000), f.state.carryingValue)
        assertEquals(money(12_500), beforeCost.slices.single().originalValue)
        assertEquals(money(15_500), afterCost.slices.single().originalValue)
        // A zero cumulative charge may still need nonzero destination corrections relative to
        // actual later issues whose original cost already included the then-known acquisition cost.
        assertEquals(0L, f.state.linkedCorrectionBalances.sumOf { it.value.minor })
        assertTrue(f.state.linkedCorrectionBalances.any { it.value.minor != 0L })
    }

    @Test fun fullyConsumedCostAdjustmentHasNoOrphanHeldValueAndNegativeCorrectionCanReachZero() {
        val f = ValueFixture()
        val receipt = f.receive(3, 2)
        val issue = f.issue(3)
        val increased = f.correct(receipt, 7).linkedAdjustment!!
        assertEquals(money(0), increased.heldDelta)
        assertEquals(money(7), increased.consumedDelta)
        assertEquals(money(0), f.state.carryingValue)
        val decreased = f.correct(receipt, -2).linkedAdjustment!!
        assertEquals(money(-9), decreased.consumedDelta)
        val returned = f.restore(issue, 1)
        assertEquals(money(1), returned.originalValueDelta)
        assertEquals(money(-1), returned.linkedAdjustment!!.heldDelta)
        assertEquals(money(1), returned.linkedAdjustment!!.consumedDelta)
        assertEquals(quantity(1), f.state.onHand)
        assertEquals(money(0), f.state.carryingValue)
    }

    @Test fun negativeReceiptValueRejectedAtomicallyRatherThanFabricatingNegativeInventory() {
        val f = ValueFixture()
        val receipt = f.receive(1, 2)
        val before = f.state
        fails(InventoryFailure.NEGATIVE_VALUE) { f.correct(receipt, -3) }
        assertSame(before, f.state)
        assertEquals(money(2), f.state.carryingValue)
        assertTrue(f.state.costTargets.isEmpty())
    }

    @Test fun sliceRoundingTieIsStableAndReturnKeepsOriginalAllocationWithLinkedResidual() {
        val ids = IdSequence()
        var state = MovingAverageState.empty(unit, 0)
        val receipt = ids.next()
        state = MovingAveragePlanner.receive(state, state.revision, receipt, quantity(3), money(2)).next
        val issueId = ids.next()
        val early = id(100)
        val late = id(101)
        val issue = MovingAveragePlanner.issue(state, state.revision, issueId,
            listOf(ValueSliceRequest(late, quantity(1)), ValueSliceRequest(early, quantity(1))))
        state = issue.next
        val fact = issue.fact as ValuationFact.Issue
        assertEquals(0L, fact.slices.first { it.allocationId == late }.originalValue.minor)
        assertEquals(1L, fact.slices.first { it.allocationId == early }.originalValue.minor)
        state = MovingAveragePlanner.adjustReceiptCost(state, state.revision, ids.next(), ids.next(), receipt, money(1)).next
        val returned = MovingAveragePlanner.restoreOriginal(state, state.revision, ids.next(),
            listOf(ValueRestorationRequest(issueId, late, quantity(1))))
        assertEquals(money(0), returned.originalValueDelta)
        assertEquals(money(1), returned.linkedAdjustment!!.heldDelta)
        assertEquals(money(-1), returned.linkedAdjustment!!.consumedDelta)
    }

    @Test fun repeatedTargetAndDifferentCorrectionSourcesConserveTheirOwnChargeExactlyOnce() {
        val f = ValueFixture()
        val first = f.receive(10, 20_000)
        val second = f.receive(10, 30_000)
        f.issue(5)
        f.correct(first, 12_000)
        val same = f.correct(first, 12_000)
        assertTrue(same.linkedAdjustment!!.deltas.isEmpty())
        val new = f.correct(second, 4_000)
        assertEquals(money(3_000), new.linkedAdjustment!!.heldDelta)
        assertEquals(money(1_000), new.linkedAdjustment!!.consumedDelta)
        assertEquals(16_000L, f.state.linkedCorrectionBalances.sumOf { it.value.minor })
        assertEquals(2, f.state.costTargets.size)
        assertEquals(quantity(15), f.state.onHand)
    }

    @Test fun staleAndDuplicateCostCommandsRejectInsteadOfDoubleQuantityOrValue() {
        val f = ValueFixture()
        val receipt = f.receive(1, 100)
        val previous = f.state.revision
        val adjusted = f.correct(receipt, 10)
        fails(InventoryFailure.STALE_REVISION) {
            MovingAveragePlanner.adjustReceiptCost(f.state, previous, f.ids.next(), id(900), receipt, money(10))
        }
        fails(InventoryFailure.DUPLICATE_SOURCE) {
            MovingAveragePlanner.adjustReceiptCost(f.state, f.state.revision, adjusted.linkedAdjustment!!.adjustmentId,
                id(900), receipt, money(10))
        }
        assertEquals(quantity(1), f.state.onHand)
        assertEquals(money(110), f.state.carryingValue)
    }

    @Test fun transferWipAndOutputFailClosedRatherThanPretendingSinglePoolReplayCoversThem() {
        val f = ValueFixture()
        f.receive(2, 100)
        for (purpose in listOf(ValueIssuePurpose.TRANSFER, ValueIssuePurpose.WORK_IN_PROGRESS)) {
            fails(InventoryFailure.UNSUPPORTED_LINEAGE) { f.issue(1, purpose) }
        }
        for (origin in listOf(ReceiptValueOrigin.TRANSFER_IN, ReceiptValueOrigin.PRODUCTION_OUTPUT)) {
            fails(InventoryFailure.UNSUPPORTED_LINEAGE) {
                MovingAveragePlanner.receive(f.state, f.state.revision, f.ids.next(), quantity(1), money(10), origin)
            }
        }
        assertEquals(quantity(2), f.state.onHand)
        assertEquals(money(100), f.state.carryingValue)
    }

    @Test fun exactWideIntermediateAndFinalBoundaryOverflow() {
        val f = ValueFixture()
        f.receive(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(money(1), f.issue(1).slices.single().originalValue)
        assertEquals(money(Long.MAX_VALUE - 1), f.issue(Long.MAX_VALUE - 1).slices.single().originalValue)
        assertEquals(money(0), f.state.carryingValue)
        val g = ValueFixture()
        g.receive(1, Long.MAX_VALUE)
        val before = g.state
        assertFailsWith<ArithmeticException> { g.receive(1, 1) }
        assertSame(before, g.state)
    }

    @Test fun inconsistentZeroQuantityValueIsAReviewExceptionNotSilentlyDiscarded() {
        val inconsistent = MovingAverageState(unit, 0, Currency.EGP, Revision(0), quantity(0), money(1),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        fails(InventoryFailure.INVALID_STATE) {
            MovingAveragePlanner.receive(inconsistent, inconsistent.revision, id(200), quantity(1), money(10))
        }
    }
}

internal class ValueFixture {
    val ids = IdSequence()
    var state = MovingAverageState.empty(unit, 0)
    private val chargeByReceipt = mutableMapOf<DomainId, DomainId>()

    fun receive(q: Long, value: Long): DomainId {
        val receiptId = ids.next()
        state = MovingAveragePlanner.receive(state, state.revision, receiptId, quantity(q), money(value)).next
        return receiptId
    }

    fun issue(q: Long, purpose: ValueIssuePurpose = ValueIssuePurpose.RETAIL): ValuationFact.Issue {
        val result = MovingAveragePlanner.issue(state, state.revision, ids.next(), listOf(ValueSliceRequest(ids.next(), quantity(q))), purpose)
        state = result.next
        return result.fact as ValuationFact.Issue
    }

    fun restore(issue: ValuationFact.Issue, q: Long): ValuationPlan {
        val result = MovingAveragePlanner.restoreOriginal(state, state.revision, ids.next(),
            listOf(ValueRestorationRequest(issue.factId, issue.slices.single().allocationId, quantity(q))))
        state = result.next
        return result
    }

    fun correct(receiptId: DomainId, target: Long): ValuationPlan {
        val source = chargeByReceipt.getOrPut(receiptId) { ids.next() }
        val result = MovingAveragePlanner.adjustReceiptCost(state, state.revision, ids.next(), source, receiptId, money(target))
        state = result.next
        return result
    }
}

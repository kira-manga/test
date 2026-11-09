package com.ukkera.pos.inventory

import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Lead-owned counterexamples, independent of the posting author's test fixtures. */
class InventoryPostingIndependentTest {
    @Test fun equalIntermediateValueStateCannotSubstituteForTheActualSequentialPoolBasis() {
        val before = InventorySnapshot.empty(scope)
        val key = StockPositionKey(pool, id(70_100))
        val plan = InventoryPlanner.receive(before, before.revision, op(70_101), listOf(
            StockReceipt(id(70_102), key, quantity(2), money(0)),
            StockReceipt(id(70_103), key, quantity(3), money(5)),
            StockReceipt(id(70_104), key, quantity(4), money(20)),
        ))
        val valuations = plan.effects.filterIsInstance<InventoryEffect.Valuation>()
        assertEquals(3, valuations.size)
        assertSame(valuations[0].plan.next, valuations[1].plan.sourceBasis)
        assertSame(valuations[1].plan.next, valuations[2].plan.sourceBasis)
        roundTrip(before, plan) // A real same-pool batch must reach the positive factory first.

        val actual = valuations[1].plan
        val clone = copyValue(actual.sourceBasis)
        assertNotSame(actual.sourceBasis, clone)
        assertEquals(StoredMovingAverageState.from(actual.sourceBasis), StoredMovingAverageState.from(clone))
        val substituted = ValuationPlan(actual.requiredRevision, clone, actual.next, actual.fact,
            actual.quantityDelta, actual.originalValueDelta, actual.linkedAdjustment)
        val effects = plan.effects.map { effect ->
            if (effect === valuations[1]) InventoryEffect.Valuation(valuations[1].pool, substituted) else effect
        }
        val forged = InventoryPlan(plan.operationId, before, plan.nextSnapshot, effects, plan.mustCommitBeforeEpochMillis)
        assertSame(before, forged.sourceSnapshot)
        assertEquals(InventorySnapshotCodec.encode(plan.nextSnapshot), InventorySnapshotCodec.encode(forged.nextSnapshot))
        rejected { capture(before, forged) }
        roundTrip(before, plan) // Refusal must not mutate the actual sequential source or proposal.
    }

    @Test fun firstNewPoolBasisMustBeCanonicalEmptyNotOnlyZeroRevisionQuantityAndValue() {
        val before = InventorySnapshot.empty(scope)
        val plan = InventoryPlanner.receive(before, before.revision, op(70_201), listOf(
            StockReceipt(id(70_202), StockPositionKey(pool, null), quantity(1), money(0)),
        ))
        roundTrip(before, plan)
        val effect = plan.effects.filterIsInstance<InventoryEffect.Valuation>().single()
        val actual = effect.plan.sourceBasis
        assertEquals(Revision(0), actual.revision)
        assertTrue(actual.linkedCorrectionBalances.isEmpty())
        // A hidden zero entry changes the source basis even though all scalar totals agree.
        val noncanonical = MovingAverageState(actual.unitId, actual.scale, actual.currency, actual.revision,
            actual.onHand, actual.carryingValue, actual.facts, actual.costTargets,
            listOf(DestinationValue(ValueDestination.Held, money(0))), actual.adjustments, actual.eventIds)
        val replaced = ValuationPlan(effect.plan.requiredRevision, noncanonical, effect.plan.next, effect.plan.fact,
            effect.plan.quantityDelta, effect.plan.originalValueDelta, effect.plan.linkedAdjustment)
        val forged = InventoryPlan(plan.operationId, before, plan.nextSnapshot,
            plan.effects.map { if (it === effect) InventoryEffect.Valuation(effect.pool, replaced) else it },
            plan.mustCommitBeforeEpochMillis)
        assertEquals(actual.onHand, noncanonical.onHand)
        assertEquals(actual.carryingValue, noncanonical.carryingValue)
        rejected { capture(before, forged) }
    }

    @Test fun nullCurrentLinkedAdjustmentCannotHideCancelledCostHistoryInAnUntouchedPool() {
        fun scenario(withCancelledCost: Boolean): InventoryPlan {
            val f = InventoryFixture()
            val receipt = f.receive(3, 100)
            if (withCancelledCost) {
                val source = storedInventoryCorrection(f, receipt, 7)
                storedInventoryCorrection(f, receipt, 0, source)
                assertEquals(0L, f.snapshot.pool(pool)!!.value.costTargets.single().targetDelta.minor)
                assertEquals(2, f.snapshot.pool(pool)!!.value.adjustments.size)
            }
            val other = StockPositionKey(StockPoolKey(scope, id(70_301), id(70_302)), null)
            return InventoryPlanner.receive(f.snapshot, f.snapshot.revision, f.ids.operation(), listOf(
                StockReceipt(f.ids.next(), other, quantity(1), money(10)),
            ))
        }
        val healthy = scenario(false)
        roundTrip(healthy.sourceSnapshot, healthy)
        val unsupported = scenario(true)
        val untouchedValuation = unsupported.effects.filterIsInstance<InventoryEffect.Valuation>().single()
        assertTrue(untouchedValuation.pool != pool)
        assertNull(untouchedValuation.plan.linkedAdjustment)
        assertSame(unsupported.sourceSnapshot.pool(pool), unsupported.nextSnapshot.pool(pool))
        // Both complete snapshots are valid in the broader domain, but not this cost-free profile.
        InventorySnapshotCodec.encode(unsupported.sourceSnapshot)
        InventorySnapshotCodec.encode(unsupported.nextSnapshot)
        rejected { capture(unsupported.sourceSnapshot, unsupported) }
    }

    @Test fun genericCodecValidUntouchedLotExpiryRewriteStillRequiresAnActualEffect() {
        val empty = InventorySnapshot.empty(scope)
        val untouched = StockPositionKey(pool, id(70_401))
        val changed = StockPositionKey(StockPoolKey(scope, id(70_402), id(70_403)), null)
        val setup = InventoryPlanner.receive(empty, empty.revision, op(70_404), listOf(
            StockReceipt(id(70_405), untouched, quantity(1), money(11), 1_000),
            StockReceipt(id(70_406), changed, quantity(1), money(13)),
        ))
        val before = setup.nextSnapshot
        val plan = InventoryPlanner.receive(before, before.revision, op(70_407), listOf(
            StockReceipt(id(70_408), changed, quantity(1), money(17)),
        ))
        roundTrip(before, plan)
        val after = plan.nextSnapshot
        val rewritten = InventorySnapshot(after.scope, after.revision,
            after.positions.map { if (it.key == untouched) it.copy(expiresAtEpochMillis = 2_000) else it },
            after.pools, after.reservations, after.issues, after.operationIds, after.sourceIds)
        val encoded = InventorySnapshotCodec.encode(rewritten)
        val decoded = InventorySnapshotCodec.decode(encoded, InventorySnapshotBinding(
            InventorySnapshotKind.BRANCH_INVENTORY, rewritten.scope, rewritten.scope.branchId, rewritten.revision))
        assertEquals(2_000L, decoded.position(untouched)!!.expiresAtEpochMillis)
        assertEquals(1_000L, before.position(untouched)!!.expiresAtEpochMillis)
        assertTrue(plan.effects.none { it is InventoryEffect.SafetyChanged })
        val forged = InventoryPlan(plan.operationId, before, rewritten, plan.effects, plan.mustCommitBeforeEpochMillis)
        // Failure cannot be credited to a malformed whole snapshot or a detached source.
        assertSame(before, forged.sourceSnapshot)
        rejected { capture(before, forged) }
        roundTrip(before, plan)
    }

    @Test fun historicalFactDecodingDoesNotRenewTheLiveIssueDeadline() {
        val f = InventoryFixture()
        f.receive(1, 19, expires = 100)
        val reservation = f.reserve(1, CollectionExposure.PAID)
        val before = f.snapshot
        val issued = InventoryPlanner.issueReservation(before, before.revision, f.ids.operation(),
            reservation.proof.reservationId, before.reservation(reservation.proof.reservationId)!!.revision,
            f.ids.next(), 99) as ReservationIssueResult.Issued
        assertEquals(100L, issued.plan.mustCommitBeforeEpochMillis)
        val binding = expected(issued.plan)
        val posted = InventoryPosting.fromPlan(before, issued.plan, binding, 99)
        val encoded = InventoryPostingCodec.encode(posted)
        assertEquals(issued.plan.effects.size, posted.effects.size)
        rejected {
            InventoryPosting.fromPlan(before, issued.plan, binding, 100)
        }
        // Decoding has no current clock or executable plan. These historical bytes remain data.
        val historical = InventoryPostingCodec.decode(encoded, binding)
        assertEquals(encoded, InventoryPostingCodec.encode(historical))
        assertEquals(100L, issued.plan.mustCommitBeforeEpochMillis)
        assertEquals(InventorySnapshotCodec.encode(before), InventorySnapshotCodec.encode(issued.plan.sourceSnapshot))
    }

    private fun rejected(block: () -> Unit): InventoryPostingRejectedException {
        val failure = assertFailsWith<InventoryPostingRejectedException>(block = block)
        assertEquals(failure.failure.name, failure.message)
        assertNull(failure.cause)
        return failure
    }

    private fun capture(before: InventorySnapshot, plan: InventoryPlan): InventoryPostingBatch =
        InventoryPosting.fromPlan(before, plan, expected(plan), 1)

    private fun roundTrip(before: InventorySnapshot, plan: InventoryPlan) {
        val posted = capture(before, plan)
        assertEquals(plan.effects.size, posted.effects.size)
        val encoded = InventoryPostingCodec.encode(posted)
        assertEquals(encoded, InventoryPostingCodec.encode(InventoryPostingCodec.decode(encoded, expected(plan))))
    }

    private fun expected(plan: InventoryPlan): InventoryPostingBinding = InventoryPostingBinding(
        plan.sourceSnapshot.scope, plan.operationId, plan.requiredInventoryRevision, plan.nextSnapshot.revision)

    private fun copyValue(value: MovingAverageState): MovingAverageState = MovingAverageState(
        value.unitId, value.scale, value.currency, value.revision, value.onHand, value.carryingValue,
        value.facts, value.costTargets, value.linkedCorrectionBalances, value.adjustments, value.eventIds)

    private fun op(number: Int): OperationId = OperationId(id(number).value)
}

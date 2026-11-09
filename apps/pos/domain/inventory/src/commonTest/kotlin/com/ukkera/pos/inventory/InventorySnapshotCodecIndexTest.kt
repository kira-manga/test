package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InventorySnapshotCodecIndexTest {
    @Test fun manyConsumedDestinationsAndAmendmentsFitOnlyWithinTheStructuralReplayBudget() {
        val (snapshot, tooMuchWork) = manyDestinationSnapshots()
        val original = snapshot.pools.single().value
        assertEquals(127, original.eventIds.size)
        assertEquals(5, original.facts.size)
        assertEquals(1_024, original.facts.filterIsInstance<ValuationFact.Issue>().sumOf { it.slices.size })
        assertEquals(260_985, 127 * (1 + 5 + 2 * 1_024 + 1))
        assertTrue(260_985 <= InventorySnapshotCodec.MAX_VALUATION_VERIFICATION_STEPS)
        val encoded = InventorySnapshotCodec.encode(snapshot)
        val decoded = InventorySnapshotCodec.decode(encoded, storedInventoryBinding(snapshot))
        val value = decoded.pools.single().value
        assertEquals(quantity(0), value.onHand)
        assertEquals(money(0), value.carryingValue)
        assertEquals(1_025, value.linkedCorrectionBalances.size)
        assertEquals(122, value.adjustments.size)
        assertTrue(value.adjustments.all { it.deltas.size == 1 && it.deltas.single().value == money(1) })
        val expectedIssueCosts = listOf(287L, 286L, 287L, 286L)
        decoded.issues.forEachIndexed { index, issue ->
            val linked = value.linkedCorrectionBalances.filter {
                val destination = it.destination
                destination is ValueDestination.Consumed && destination.issueId == issue.issueId
            }.sumOf { it.value.minor }
            assertEquals(money(256), issue.originalValue)
            assertEquals(expectedIssueCosts[index], issue.originalValue.minor + linked)
        }
        assertEquals(128, tooMuchWork.pools.single().value.eventIds.size)
        assertEquals(InventorySnapshotFailure.TOO_LARGE,
            assertFailsWith<InventorySnapshotRejectedException> { InventorySnapshotCodec.encode(tooMuchWork) }.failure)
        // The former per-destination list scan made 64,150,650 comparisons in amendments alone.
        // This exercises the admitted shape and arithmetic, not a wall-clock performance claim.
    }

    @Test fun indexedClaimsSumEveryPortionAndKeepEachRetainedReservationsOwnPositionHolds() {
        val f = InventoryFixture()
        val second = f.key.copy(lotId = id(760))
        f.receive(8, 800)
        f.receive(4, 400, second)
        val firstRows = listOf(StockAllocation(f.ids.next(), f.ids.next(), f.key, quantity(1)),
            StockAllocation(f.ids.next(), f.ids.next(), f.key, quantity(2)),
            StockAllocation(f.ids.next(), f.ids.next(), second, quantity(1)))
        val first = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            binding(CollectionExposure.PAID), firstRows, 1)
        f.snapshot = first.plan.nextSnapshot
        val secondRows = listOf(StockAllocation(f.ids.next(), f.ids.next(), f.key, quantity(2)),
            StockAllocation(f.ids.next(), f.ids.next(), second, quantity(1)))
        val other = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            binding(CollectionExposure.UNKNOWN), secondRows, 1)
        f.snapshot = other.plan.nextSnapshot
        val released = f.reserve(1)
        f.snapshot = InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(),
            released.proof.reservationId, released.proof.reservationRevision).nextSnapshot
        val toIssue = f.reserve(1)
        assertIs<ReservationIssueResult.Issued>(f.issue(toIssue.proof.reservationId))
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(quantity(2), f.snapshot.available(f.key, 1))
        assertEquals(quantity(2), f.snapshot.available(second, 1))
        val firstIndex = f.snapshot.reservations.indexOfFirst { it.reservationId == first.proof.reservationId }.toString()
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(InventorySnapshotCodec.encode(f.snapshot),
            listOf("state", "reservations", firstIndex, "allocations", "1", "quantity", "scaled"), JsonPrimitive("6")))

        f.snapshot = InventoryPlanner.recordCountShortage(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            f.key, f.snapshot.revision, quantity(4)).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(quantity(0), f.snapshot.available(f.key, 1))
        assertEquals(quantity(2), f.snapshot.available(second, 1))
        for (reservationId in listOf(first.proof.reservationId, other.proof.reservationId)) {
            val reservation = f.snapshot.reservation(reservationId)!!
            assertEquals(ReservationStatus.HELD, reservation.status)
            assertEquals(listOf(ReservationHold(ReservationHoldReason.COUNT_SHORTAGE, f.key)), reservation.holds)
        }
        val otherIndex = f.snapshot.reservations.indexOfFirst { it.reservationId == other.proof.reservationId }.toString()
        var encoded = InventorySnapshotCodec.encode(f.snapshot)
        val otherPosition = inventoryJsonAt(encoded, "state", "reservations", otherIndex, "allocations", "1", "position")
        encoded = inventoryJsonEdit(encoded, listOf("state", "reservations", otherIndex, "holds", "0", "position"), otherPosition)
        encoded = inventoryJsonEdit(encoded, listOf("state", "reservations", otherIndex, "holds", "0", "reason"), JsonPrimitive("UNAVAILABLE"))
        storedInventoryFailure(f.snapshot, encoded, InventorySnapshotFailure.INCONSISTENT_STATE)
    }

    @Test fun restorationIndexKeepsExactSlicesAndCountsOneReturnEventAcrossMultiplePools() {
        val f = InventoryFixture()
        val second = f.key.copy(pool = pool.copy(warehouseId = id(761)))
        f.receive(6, 600)
        f.receive(6, 1_200, second)
        val allocations = listOf(f.key, f.key, second, second).map {
            StockAllocation(f.ids.next(), f.ids.next(), it, quantity(2))
        }
        val reserved = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            binding(CollectionExposure.PAID), allocations, 1)
        f.snapshot = reserved.plan.nextSnapshot
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        var issue = f.snapshot.issue(issued.proof.issueId)!!
        f.snapshot = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            issue.issueId, issue.revision, allocations.map { StockRestoration(it.allocationId, quantity(1), it.position) }, 1).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        issue = f.snapshot.issue(issue.issueId)!!
        assertEquals(Revision(2), issue.revision)
        assertTrue(issue.allocations.all { it.restoredQuantity == quantity(1) })
        f.snapshot = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            issue.issueId, issue.revision, listOf(allocations.first(), allocations.last()).map {
                StockRestoration(it.allocationId, quantity(1), it.position)
            }, 1).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        issue = f.snapshot.issue(issue.issueId)!!
        assertEquals(Revision(3), issue.revision)
        assertEquals(listOf(2L, 1L, 1L, 2L), issue.allocations.map { it.restoredQuantity.scaled })
        assertEquals(quantity(5), f.snapshot.position(f.key)!!.onHand)
        assertEquals(quantity(5), f.snapshot.position(second)!!.onHand)
        var encoded = InventorySnapshotCodec.encode(f.snapshot)
        encoded = inventoryJsonEdit(encoded, listOf("state", "issues", "0", "allocations", "0", "restoredQuantity", "scaled"), JsonPrimitive("1"))
        encoded = inventoryJsonEdit(encoded, listOf("state", "issues", "0", "allocations", "1", "restoredQuantity", "scaled"), JsonPrimitive("2"))
        // Same aggregate restored quantity and current pool balance; the named original slices differ.
        storedInventoryFailure(f.snapshot, encoded, InventorySnapshotFailure.INCONSISTENT_STATE)
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(InventorySnapshotCodec.encode(f.snapshot),
            listOf("state", "issues", "0", "revision"), JsonPrimitive("5")), InventorySnapshotFailure.INCONSISTENT_STATE)
    }
}

/**
 * Independent small-integer fixture: 1024@1024, four 256-slice issues, then +1 source amendments.
 * No planner/replay call constructs this history. Integer division handles the four equal batches;
 * all equal-weight slice residuals go to ascending IDs. All values are well below Long overflow.
 */
private fun manyDestinationSnapshots(): Pair<InventorySnapshot, InventorySnapshot> {
    val ids = IdSequence(30_000)
    val position = StockPositionKey(pool, id(20))
    val receiptId = ids.next()
    val facts = mutableListOf<ValuationFact>(ValuationFact.Receipt(receiptId, quantity(1_024), money(1_024), ReceiptValueOrigin.PURCHASE))
    val events = mutableListOf(receiptId)
    val operations = mutableListOf(ids.operation())
    val reservations = mutableListOf<StockReservation>()
    val issues = mutableListOf<StockIssue>()
    repeat(4) {
        val reservationId = ids.next()
        val issueId = ids.next()
        val allocations = List(256) { StockAllocation(ids.next(), ids.next(), position, quantity(1)) }
        reservations += StockReservation(reservationId, binding(), allocations, Revision(2), ReservationStatus.ISSUED)
        issues += StockIssue(issueId, reservationId, binding(), allocations.map { IssuedStockAllocation(it, money(1), quantity(0)) },
            ValueIssuePurpose.RETAIL, Revision(1), null)
        facts += ValuationFact.Issue(issueId, allocations.map { CostedValueSlice(it.allocationId, it.quantity, money(1)) }, ValueIssuePurpose.RETAIL)
        events += issueId
        operations += ids.operation() // Reservation.
        operations += ids.operation() // Issue.
    }
    val chargeSource = ids.next()
    val adjustments = mutableListOf<LinkedCostAdjustment>()
    var targets = emptyList<ReceiptCostTarget>()
    var balances = listOf(DestinationValue(ValueDestination.Held, money(0))) + issues.flatMap { issue ->
        issue.allocations.map { DestinationValue(ValueDestination.Consumed(issue.issueId, it.allocation.allocationId), money(0)) }
    }
    fun snapshot(): InventorySnapshot = InventorySnapshot(scope, Revision(operations.size.toLong()),
        listOf(StockPosition(position, quantity(0))), listOf(OwnedValuationPool(pool,
            MovingAverageState(unit, 0, Currency.EGP, Revision(events.size.toLong()), quantity(0), money(0),
                facts, targets, balances, adjustments, events))), reservations, issues, operations, events)
    var admitted: InventorySnapshot? = null
    for (amount in 1..123) {
        var remaining = 1_024L + amount
        val desired = mutableListOf(DestinationValue(ValueDestination.Held, money(0)))
        issues.forEachIndexed { index, issue ->
            val batchesLeft = 4L - index
            val issueValue = (2 * remaining + batchesLeft) / (2 * batchesLeft) // Positive HALF_UP.
            remaining -= issueValue
            val extras = issueValue - 256
            assertTrue(extras in 0L..256L)
            issue.allocations.forEachIndexed { sliceIndex, allocation ->
                desired += DestinationValue(ValueDestination.Consumed(issue.issueId, allocation.allocation.allocationId),
                    money(if (sliceIndex.toLong() < extras) 1 else 0))
            }
        }
        assertEquals(0L, remaining)
        val deltas = desired.zip(balances).map { (next, prior) ->
            assertEquals(prior.destination, next.destination)
            DestinationValue(next.destination, next.value - prior.value)
        }.filter { it.value.minor != 0L }
        assertEquals(1, deltas.size)
        assertEquals(money(1), deltas.single().value)
        val target = ReceiptCostTarget(chargeSource, receiptId, money(amount.toLong()))
        targets = listOf(target)
        val event = ids.next()
        adjustments += LinkedCostAdjustment(event, Revision(events.size.toLong()), target, targets, deltas)
        events += event
        operations += ids.operation()
        balances = desired
        if (amount == 122) admitted = snapshot()
    }
    return requireNotNull(admitted) to snapshot()
}

package com.ukkera.pos.sales.independent.foodcurrentpayablev2

import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.FoodCurrentUnpaidPayable
import com.ukkera.pos.sales.FoodPreparation
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.FoodStationStatus
import com.ukkera.pos.sales.FoodStationTask
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded domain subassertions of FCUP/FCPV2 frozen cases. These do not exercise a protected
 * Master, production Add/cancellation, original publication custody, persistence or local RPC.
 */
class FoodCurrentPayableFrozenDomainSlicesTest {
    private val f = FoodCurrentPayableFrozenDomainInputs

    @Test
    fun fcup001_002_004_005_fcpv2006_currentSequenceKeepsOriginalBatchPostings() {
        val liveLines = f.resolvedLines(listOf(f.p1, f.p2), "110", Revision(1)).toMutableList()
        val a = f.quote(f.batchA, liveLines)
        val originalA = StoredPricingSnapshotCodec.encode(a)
        val initial = listOf(f.unsent(f.p1), f.unsent(f.p2))

        assertEquals(f.egp(22_000), a.payable, "FCUP-001: original A")
        assertEquals(f.egp(22_000), FoodCurrentUnpaidPayable.project(listOf(a), initial,
            f.egp(22_000), null, null), "FCUP-001/FCPV2-006: F0 current")

        val corrected = listOf(initial[0], f.cancelUnsent(initial[1]))
        assertEquals(f.egp(11_000), FoodCurrentUnpaidPayable.project(listOf(a), corrected,
            f.egp(22_000), null, null), "FCUP-002/FCPV2-006: F1 current")
        assertEquals(originalA, StoredPricingSnapshotCodec.encode(a), "FCUP-002: A stays unchanged")

        // Change the external resolved pricing inputs without supplying a new original batch.
        for (index in liveLines.indices) liveLines[index] = liveLines[index].copy(
            unitPrice = UnitPrice.parse(com.ukkera.pos.types.Currency.EGP, "140"), priceRevision = Revision(2))
        assertEquals(f.egp(11_000), FoodCurrentUnpaidPayable.project(listOf(a), corrected,
            f.egp(22_000), null, null), "FCUP-004/FCPV2-006: no new Add, no repricing")
        assertEquals(originalA, StoredPricingSnapshotCodec.encode(a), "FCUP-004: original price snapshot stays pinned")

        val b = f.quote(f.batchB, f.resolvedLines(listOf(f.p3, f.p4), "140", Revision(2)))
        val originalB = StoredPricingSnapshotCodec.encode(b)
        val later = corrected + listOf(f.unsent(f.p3), f.unsent(f.p4))
        assertEquals(f.egp(28_000), b.payable, "FCUP-005: original B")
        assertEquals(f.egp(39_000), FoodCurrentUnpaidPayable.project(listOf(a, b), later,
            f.egp(50_000), null, null), "FCUP-005/FCPV2-006: F2 current")
        assertEquals(f.egp(50_000), a.payable + b.payable, "FCUP-005: historical contributions only")
        assertEquals(originalA, StoredPricingSnapshotCodec.encode(a), "FCUP-005: original A bytes")
        assertEquals(originalB, StoredPricingSnapshotCodec.encode(b), "FCUP-005: original B bytes")
        assertEquals(listOf(f.p1, f.p2), a.lines.map { it.portionId })
        assertEquals(listOf(f.p3, f.p4), b.lines.map { it.portionId })
        assertTrue(initial.all { it.tasks.isEmpty() && it.issueId == null && it.reservationId == null })
        assertTrue(later.all { it.tasks.isEmpty() && it.issueId == null && it.reservationId == null })
    }

    @Test
    fun fcup003_fcpv2006_knownZeroPreservesOriginalCancelledServings() {
        val a = f.quote(f.batchA, f.resolvedLines(listOf(f.p1, f.p2), "110", Revision(1)))
        val originalA = StoredPricingSnapshotCodec.encode(a)
        val cancelled = listOf(f.p1, f.p2).map { f.cancelUnsent(f.unsent(it)) }
        assertEquals(f.egp(0), FoodCurrentUnpaidPayable.project(listOf(a), cancelled,
            f.egp(22_000), null, null), "FCUP-003/FCPV2-006: open eligible logical FZ")
        assertEquals(f.egp(22_000), a.payable)
        assertEquals(originalA, StoredPricingSnapshotCodec.encode(a))
        assertTrue(cancelled.all(FoodCurrentUnpaidPayable::isNeverSent))
        // The pure projection takes explicit open/uncollected inputs; it cannot prove that
        // cancelling the last serving leaves a real stored order open.
    }

    @Test
    fun fcpv2006_emptyEligibleProjectionHasKnownZero() {
        assertEquals(f.egp(0), FoodCurrentUnpaidPayable.project(emptyList(), emptyList(),
            f.egp(0), null, null), "C1: empty eligible check")
    }

    @Test
    fun fcup012_fcpv2008_sentCollectionAndClosedBoundariesAreUnavailable() {
        val a = f.quote(f.batchA, f.resolvedLines(listOf(f.p1, f.p2), "110", Revision(1)))
        val originalA = StoredPricingSnapshotCodec.encode(a)
        val unsent = listOf(f.unsent(f.p1), f.unsent(f.p2))
        val sent = FoodPreparation.sent(unsent[1], unsent[1].revision, f.id(20), f.id(21),
            listOf(FoodStationTask(f.id(22), f.id(23), FoodStationDeliveryMode.DISPLAY, FoodStationStatus.QUEUED)),
            f.cancellationAt)
        assertNull(FoodCurrentUnpaidPayable.project(listOf(a), listOf(unsent[0], sent),
            f.egp(22_000), null, null), "FCPV2-008(a): any sent work, including an off-page member")
        assertFalse(FoodCurrentUnpaidPayable.isNeverSent(sent))
        assertNull(FoodCurrentUnpaidPayable.project(listOf(a), unsent, f.egp(22_000),
            f.id(24), null), "FCPV2-008(b): explicit collection boundary")
        assertNull(FoodCurrentUnpaidPayable.project(listOf(a), unsent, f.egp(22_000), null,
            OperationId(f.id(25).value)), "FCPV2-008(e): closed boundary")
        assertNull(FoodCurrentUnpaidPayable.project(emptyList(), emptyList(), f.egp(0), null,
            OperationId(f.id(26).value)), "FCPV2-008(e): closed empty check differs from eligible zero")
        assertEquals(originalA, StoredPricingSnapshotCodec.encode(a))
        // Paid, unknown money and actual unresolved decisions need separately lawful financial fixtures.
    }

    @Test
    fun fcup011_fcpv2011_missingOffPageOriginalCannotProducePartialOrNullEconomics() {
        val a = f.quote(f.batchA, f.resolvedLines(listOf(f.p1, f.p2), "110", Revision(1)))
        val b = f.quote(f.batchB, f.resolvedLines(listOf(f.p3, f.p4), "140", Revision(2)))
        val incompleteB = f.quote(f.id(30), f.resolvedLines(listOf(f.p3), "140", Revision(2)))
        val portions = listOf(f.unsent(f.p1), f.cancelUnsent(f.unsent(f.p2)), f.unsent(f.p3), f.unsent(f.p4))
        val originalA = StoredPricingSnapshotCodec.encode(a)
        val originalB = StoredPricingSnapshotCodec.encode(b)
        assertFailsWith<SalesRuleViolation>("FCUP-011/FCPV2-011: P4 original missing") {
            FoodCurrentUnpaidPayable.project(listOf(a, incompleteB), portions, f.egp(50_000), null, null)
        }
        assertEquals(originalA, StoredPricingSnapshotCodec.encode(a))
        assertEquals(originalB, StoredPricingSnapshotCodec.encode(b))
        // This validates the pure complete-member refusal; native missing/corrupt history remains separate.
    }
}

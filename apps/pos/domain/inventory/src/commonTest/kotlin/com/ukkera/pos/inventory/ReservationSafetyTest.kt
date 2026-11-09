package com.ukkera.pos.inventory

import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReservationSafetyTest {
    @Test fun ownReservationConvertsWithoutBeingCountedAsSomeoneElsesClaim() {
        val f = InventoryFixture()
        f.receive(1, 99)
        val reservation = f.reserve(1, CollectionExposure.PAID)
        assertEquals(quantity(0), f.snapshot.available(f.key, 1))
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reservation.proof.reservationId))
        assertEquals(quantity(0), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(0), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(money(99), issued.proof.costedAllocations.single().originalValue)
        assertEquals(ValueIssuePurpose.RETAIL, issued.proof.purpose)
        assertEquals(binding(CollectionExposure.PAID), f.snapshot.reservation(reservation.proof.reservationId)!!.binding)
    }

    @Test fun twoClientsCanPlanLastUnitButOnlyOneCanPassTheRequiredCommitRevision() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val initial = f.snapshot
        val first = f.reserve(1)
        val competing = InventoryPlanner.reserve(initial, initial.revision, f.ids.operation(), f.ids.next(), binding(),
            listOf(StockAllocation(f.ids.next(), id(31), f.key, quantity(1))), 1)
        InventoryPlanner.requireCurrent(first.plan, initial)
        fails(InventoryFailure.STALE_REVISION) { InventoryPlanner.requireCurrent(competing.plan, first.plan.nextSnapshot) }
        fails(InventoryFailure.INSUFFICIENT_STOCK) { f.reserve(1) }
        assertEquals(quantity(1), initial.available(f.key, 1))
        assertEquals(quantity(0), first.plan.nextSnapshot.available(f.key, 1))
    }

    @Test fun exactAllComponentsOrNothingAndNoManagerNegativeOverride() {
        val f = InventoryFixture()
        f.receive(2, 200)
        val other = StockPositionKey(pool.copy(skuId = id(50)), id(51))
        f.receive(1, 400, other)
        val before = f.snapshot
        fails(InventoryFailure.INSUFFICIENT_STOCK) {
            InventoryPlanner.reserve(before, before.revision, f.ids.operation(), f.ids.next(), binding(), listOf(
                StockAllocation(f.ids.next(), id(30), f.key, quantity(2)),
                StockAllocation(f.ids.next(), id(31), other, quantity(2)),
            ), 1)
        }
        assertTrue(before.reservations.isEmpty())
        assertEquals(quantity(2), before.available(f.key, 1))
        assertEquals(quantity(1), before.available(other, 1))
    }

    @Test fun paidUnknownAndPartialReservationsKeepTheirMoneyLinksWhenTheLotExpires() {
        for (exposure in listOf(CollectionExposure.PAID, CollectionExposure.UNKNOWN, CollectionExposure.PART_PAID)) {
            val f = InventoryFixture()
            f.receive(3, 300, expires = 10)
            val reserved = f.reserve(2, exposure)
            val original = f.snapshot.reservation(reserved.proof.reservationId)!!
            val held = assertIs<ReservationIssueResult.Held>(f.issue(original.reservationId, now = 10))
            val next = f.snapshot.reservation(original.reservationId)!!
            assertEquals(ReservationStatus.HELD, next.status)
            assertEquals(original.binding, next.binding)
            assertEquals(original.allocations, next.allocations)
            assertEquals(quantity(3), f.snapshot.position(f.key)!!.onHand)
            assertEquals(money(300), f.snapshot.pool(pool)!!.value.carryingValue)
            assertTrue(next.holds.any { it.reason == ReservationHoldReason.EXPIRY })
            assertFalse(held.plan.effects.any { it is InventoryEffect.Movement })
            assertEquals(quantity(0), f.snapshot.available(f.key, 1)) // A backward clock cannot clear recorded expiry.
            fails(InventoryFailure.COLLECTION_RESOLUTION_REQUIRED) {
                InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(), next.reservationId, next.revision)
            }
        }
    }

    @Test fun recallRacesWithIssueAndTouchesAllWarehousesAndAlreadyZeroPositions() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val other = f.key.copy(pool = pool.copy(warehouseId = id(80)))
        f.receive(1, 200, other)
        val r1 = f.reserve(1, CollectionExposure.PAID)
        val r2 = f.reserve(1, CollectionExposure.UNKNOWN, other)
        val before = f.snapshot
        val staleIssue = InventoryPlanner.issueReservation(before, before.revision, f.ids.operation(), r1.proof.reservationId,
            before.reservation(r1.proof.reservationId)!!.revision, f.ids.next(), 1) as ReservationIssueResult.Issued
        val recall = InventoryPlanner.recallLot(before, before.revision, f.ids.operation(), pool.skuId, f.key.lotId!!)
        f.snapshot = recall.nextSnapshot
        fails(InventoryFailure.STALE_REVISION) { InventoryPlanner.requireCurrent(staleIssue.plan, f.snapshot) }
        assertEquals(2, f.snapshot.reservations.count { it.status == ReservationStatus.HELD })
        assertIs<ReservationIssueResult.Held>(f.issue(r2.proof.reservationId))
        // A fresh receipt of a recalled lot must not create sellable units in another warehouse.
        val third = f.key.copy(pool = pool.copy(warehouseId = id(81)))
        f.receive(1, 300, third)
        assertEquals(StockSafety.RECALLED, f.snapshot.position(third)!!.safety)
        assertEquals(quantity(0), f.snapshot.available(third, 1))
        assertTrue(f.snapshot.positions.all { it.onHand.scaled == 1L })
    }

    @Test fun shortageKeepsMultipleReservationClaimsEvenIfClaimsExceedFoundStock() {
        val f = InventoryFixture()
        f.receive(5, 500)
        val paid = f.reserve(3, CollectionExposure.PAID)
        val unknown = f.reserve(2, CollectionExposure.UNKNOWN)
        val before = f.snapshot
        val counted = InventoryPlanner.recordCountShortage(before, before.revision, f.ids.operation(), f.ids.next(),
            f.key, before.revision, quantity(2))
        f.snapshot = counted.nextSnapshot
        assertEquals(quantity(2), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(200), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(quantity(0), f.snapshot.available(f.key, 1))
        assertEquals(2, f.snapshot.reservations.count { it.status == ReservationStatus.HELD })
        assertEquals(5L, f.snapshot.reservations.sumOf { it.allocations.single().quantity.scaled })
        assertIs<ReservationIssueResult.Held>(f.issue(paid.proof.reservationId))
        assertIs<ReservationIssueResult.Held>(f.issue(unknown.proof.reservationId))
        fails(InventoryFailure.INSUFFICIENT_STOCK) { f.reserve(3, key = f.key.copy(lotId = id(99))) }
        assertEquals(binding(CollectionExposure.PAID), f.snapshot.reservation(paid.proof.reservationId)!!.binding)
    }

    @Test fun staleCountCutCannotEraseAReceiptOrReapplyShortage() {
        val f = InventoryFixture()
        f.receive(5, 500)
        val captured = f.snapshot.revision
        f.receive(2, 200)
        fails(InventoryFailure.STALE_REVISION) {
            InventoryPlanner.recordCountShortage(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
                f.key, captured, quantity(4))
        }
        assertEquals(quantity(7), f.snapshot.position(f.key)!!.onHand)
        fails(InventoryFailure.INVALID_QUANTITY) {
            InventoryPlanner.recordCountShortage(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
                f.key, f.snapshot.revision, quantity(-1))
        }
    }

    @Test fun leaseExpiryReleasesOnlyUncollectedAndNeverErasesProtectedReservations() {
        val f = InventoryFixture()
        f.receive(4, 400)
        val unpaid = f.reserve(1, expires = 3)
        val paid = f.reserve(1, CollectionExposure.PAID, expires = 3)
        val unknown = f.reserve(1, CollectionExposure.UNKNOWN, expires = 3)
        val partial = f.reserve(1, CollectionExposure.PART_PAID, expires = 3)
        f.snapshot = InventoryPlanner.expireReservations(f.snapshot, f.snapshot.revision, f.ids.operation(), 3).nextSnapshot
        assertEquals(ReservationStatus.RELEASED, f.snapshot.reservation(unpaid.proof.reservationId)!!.status)
        for (r in listOf(paid, unknown, partial)) assertEquals(ReservationStatus.HELD, f.snapshot.reservation(r.proof.reservationId)!!.status)
        assertEquals(quantity(1), f.snapshot.available(f.key, 3))
        assertEquals(quantity(4), f.snapshot.position(f.key)!!.onHand)
    }

    @Test fun protectedReleaseRequiresMatchingResolutionAndRetainsOutstandingObligationReference() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val r = f.reserve(1, CollectionExposure.UNKNOWN)
        val reservation = f.snapshot.reservation(r.proof.reservationId)!!
        fails(InventoryFailure.COLLECTION_RESOLUTION_REQUIRED) {
            InventoryPlanner.recordCollectionExposure(f.snapshot, f.snapshot.revision, f.ids.operation(), reservation.reservationId,
                reservation.revision, CollectionExposure.UNCOLLECTED)
        }
        fails(InventoryFailure.COLLECTION_RESOLUTION_REQUIRED) {
            InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(), reservation.reservationId,
                reservation.revision, CollectionResolutionReference(id(100), id(101), reservation.binding.collectionId, id(102)))
        }
        val resolution = CollectionResolutionReference(id(100), reservation.binding.orderId, reservation.binding.collectionId, id(102))
        val result = InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(), reservation.reservationId,
            reservation.revision, resolution)
        val effect = assertIs<InventoryEffect.ReservationReleased>(result.effects.single())
        assertEquals(resolution, effect.resolution)
        assertEquals(reservation.binding, effect.reservation.binding)
        assertEquals(quantity(1), result.nextSnapshot.available(f.key, 1))
    }

    @Test fun exactSubunitRequestsRejectWrongUnitsScalesAndDuplicateAllocations() {
        val f = InventoryFixture()
        val receipt = StockReceipt(f.ids.next(), f.key, quantity(1_000_001, 6), money(17))
        f.snapshot = InventoryPlanner.receive(f.snapshot, f.snapshot.revision, f.ids.operation(), listOf(receipt)).nextSnapshot
        val allocation = StockAllocation(f.ids.next(), id(30), f.key, quantity(1, 6))
        val r = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(), listOf(allocation), 1)
        assertEquals(quantity(1_000_000, 6), r.plan.nextSnapshot.available(f.key, 1))
        fails(InventoryFailure.UNIT_OR_SCALE_MISMATCH) {
            InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(),
                listOf(allocation.copy(quantity = quantity(1, 0))), 1)
        }
        fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) {
            InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(), listOf(allocation, allocation), 1)
        }
        fails(InventoryFailure.STALE_REVISION) {
            InventoryPlanner.revalidateReservation(r.plan.nextSnapshot, r.plan.nextSnapshot.revision, f.ids.operation(),
                r.proof.reservationId, Revision(0), 1)
        }
    }

    @Test fun unchangedRevisionCannotHideExpiryBetweenPlanningAndDurableCommit() {
        val f = InventoryFixture()
        f.receive(1, 100, expires = 10)
        val before = f.snapshot
        val reserved = f.reserve(1, expires = 8)
        assertEquals(8L, reserved.plan.mustCommitBeforeEpochMillis)
        assertEquals(8L, reserved.proof.mustCommitBeforeEpochMillis)
        InventoryPlanner.requireCurrent(reserved.plan, before, 7)
        fails(InventoryFailure.UNSAFE_STOCK) { InventoryPlanner.requireCurrent(reserved.plan, before) }
        fails(InventoryFailure.UNSAFE_STOCK) { InventoryPlanner.requireCurrent(reserved.plan, before, 8) }
        val issueCut = f.snapshot
        val issue = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        assertEquals(8L, issue.proof.mustCommitBeforeEpochMillis)
        InventoryPlanner.requireCurrent(issue.plan, issueCut, 7)
        fails(InventoryFailure.UNSAFE_STOCK) { InventoryPlanner.requireCurrent(issue.plan, issueCut, 9) }
    }
}

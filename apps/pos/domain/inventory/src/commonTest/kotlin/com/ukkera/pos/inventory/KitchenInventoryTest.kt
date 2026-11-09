package com.ukkera.pos.inventory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KitchenInventoryTest {
    private val stations = listOf(id(300), id(301))
    private fun recipe(): RecipeIssueRequest = RecipeIssueRequest(id(302), stations)

    @Test fun paidUnsentIngredientsStayReservedAndSafetyRevalidationDoesNotIssueThem() {
        val f = InventoryFixture()
        f.receive(3, 300)
        val reservation = f.reserve(3, CollectionExposure.UNKNOWN)
        val initial = f.snapshot.reservation(reservation.proof.reservationId)!!
        val paid = InventoryPlanner.recordCollectionExposure(f.snapshot, f.snapshot.revision, f.ids.operation(),
            initial.reservationId, initial.revision, CollectionExposure.PAID)
        f.snapshot = paid.nextSnapshot
        val current = f.snapshot.reservation(initial.reservationId)!!
        val validated = assertIs<ReservationSafetyResult.Safe>(InventoryPlanner.revalidateReservation(f.snapshot,
            f.snapshot.revision, f.ids.operation(), current.reservationId, current.revision, 1))
        f.snapshot = validated.plan.nextSnapshot
        assertEquals(quantity(3), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(300), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(quantity(0), f.snapshot.available(f.key, 1))
        assertTrue(f.snapshot.issues.isEmpty())
        assertFalse(validated.plan.effects.any { it is InventoryEffect.Movement || it is InventoryEffect.Valuation })
        val sent = assertIs<ReservationIssueResult.Issued>(f.issue(initial.reservationId, recipe = recipe()))
        assertEquals(ValueIssuePurpose.DIRECT_RECIPE, sent.proof.purpose)
        assertEquals(quantity(0), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(300), f.snapshot.issue(sent.proof.issueId)!!.originalValue)
        fails(InventoryFailure.RESERVATION_NOT_ACTIVE) { f.issue(initial.reservationId, recipe = recipe()) }
    }

    @Test fun prepaidRecipeExpiredBeforeSendBecomesFulfillmentHoldNotIssueOrRelease() {
        val f = InventoryFixture()
        f.receive(2, 200, expires = 9)
        val r = f.reserve(2, CollectionExposure.PAID)
        val held = assertIs<ReservationIssueResult.Held>(f.issue(r.proof.reservationId, now = 9, recipe = recipe()))
        assertEquals(quantity(2), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(200), f.snapshot.pool(pool)!!.value.carryingValue)
        assertTrue(f.snapshot.issues.isEmpty())
        assertEquals(CollectionExposure.PAID, held.plan.nextSnapshot.reservation(r.proof.reservationId)!!.binding.exposure)
    }

    @Test fun verifiedBeforeStartCancellationRequiresEveryStationAndRestoresOriginalCostOnce() {
        val f = InventoryFixture()
        f.receive(3, 300)
        val r = f.reserve(2, CollectionExposure.PAID)
        val sent = f.issue(r.proof.reservationId, recipe = recipe()) as ReservationIssueResult.Issued
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        val destinations = issue.allocations.map { StockRestoration(it.allocation.allocationId, it.allocation.quantity, it.allocation.position) }
        val partial = KitchenCancellationConfirmation(f.ids.next(), issue.issueId, issue.revision, id(303), listOf(stations.first()))
        fails(InventoryFailure.KITCHEN_CONFIRMATION_REQUIRED) {
            InventoryPlanner.cancelRecipeBeforeStart(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), partial, destinations, 1)
        }
        assertEquals(quantity(1), f.snapshot.position(f.key)!!.onHand)
        val confirmation = KitchenCancellationConfirmation(f.ids.next(), issue.issueId, issue.revision, id(303), stations)
        val cancelled = InventoryPlanner.cancelRecipeBeforeStart(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            confirmation, destinations, 1)
        f.snapshot = cancelled.nextSnapshot
        assertEquals(quantity(3), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(300), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(KitchenDisposition.CANCELLED_BEFORE_START, f.snapshot.issue(issue.issueId)!!.kitchen!!.disposition)
        assertEquals(issue.originalValue, f.snapshot.issue(issue.issueId)!!.originalValue)
        assertEquals(1, cancelled.effects.filterIsInstance<InventoryEffect.IssueRecorded>().size)
        // Cancelling ingredients does not erase paid collection or pretend to refund it.
        assertEquals(CollectionExposure.PAID, f.snapshot.issue(issue.issueId)!!.binding.exposure)
        fails(InventoryFailure.STALE_REVISION) {
            InventoryPlanner.cancelRecipeBeforeStart(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), confirmation, destinations, 1)
        }
    }

    @Test fun startAndBeforeStartCancellationCompeteOnTheSameIssueAndInventoryVersions() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val r = f.reserve(1)
        val sent = f.issue(r.proof.reservationId, recipe = recipe()) as ReservationIssueResult.Issued
        val cut = f.snapshot
        val issue = cut.issue(sent.proof.issueId)!!
        val confirmation = KitchenCancellationConfirmation(f.ids.next(), issue.issueId, issue.revision, id(303), stations)
        val destinations = issue.allocations.map { StockRestoration(it.allocation.allocationId, it.allocation.quantity, it.allocation.position) }
        val cancelled = InventoryPlanner.cancelRecipeBeforeStart(cut, cut.revision, f.ids.operation(), f.ids.next(), confirmation, destinations, 1)
        val started = InventoryPlanner.acknowledgeKitchenStart(cut, cut.revision, f.ids.operation(), issue.issueId, issue.revision, stations.first())
        fails(InventoryFailure.STALE_REVISION) { InventoryPlanner.requireCurrent(cancelled, started.nextSnapshot) }
        fails(InventoryFailure.STALE_REVISION) { InventoryPlanner.requireCurrent(started, cancelled.nextSnapshot) }
        val current = started.nextSnapshot.issue(issue.issueId)!!
        fails(InventoryFailure.KITCHEN_ALREADY_STARTED) {
            InventoryPlanner.cancelRecipeBeforeStart(started.nextSnapshot, started.nextSnapshot.revision, f.ids.operation(), f.ids.next(),
                KitchenCancellationConfirmation(f.ids.next(), issue.issueId, current.revision, id(303), stations), destinations, 1)
        }
        val cancelledIssue = cancelled.nextSnapshot.issue(issue.issueId)!!
        fails(InventoryFailure.INVALID_STATE) {
            InventoryPlanner.acknowledgeKitchenStart(cancelled.nextSnapshot, cancelled.nextSnapshot.revision, f.ids.operation(),
                issue.issueId, cancelledIssue.revision, stations.last())
        }
    }

    @Test fun unsafeBeforeStartReturnMustGoToQuarantineAndDoesNotClearRecall() {
        val f = InventoryFixture()
        f.receive(1, 99)
        val r = f.reserve(1)
        val sent = f.issue(r.proof.reservationId, recipe = recipe()) as ReservationIssueResult.Issued
        f.snapshot = InventoryPlanner.recallLot(f.snapshot, f.snapshot.revision, f.ids.operation(), pool.skuId, f.key.lotId!!).nextSnapshot
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        val allocation = issue.allocations.single().allocation
        val confirmation = KitchenCancellationConfirmation(f.ids.next(), issue.issueId, issue.revision, id(303), stations)
        fails(InventoryFailure.UNSAFE_STOCK) {
            InventoryPlanner.cancelRecipeBeforeStart(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), confirmation,
                listOf(StockRestoration(allocation.allocationId, allocation.quantity, f.key)), 1)
        }
        val quarantine = f.key.copy(custody = StockCustody.QUARANTINE)
        f.snapshot = InventoryPlanner.cancelRecipeBeforeStart(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), confirmation,
            listOf(StockRestoration(allocation.allocationId, allocation.quantity, quarantine)), 1).nextSnapshot
        assertEquals(quantity(0), f.snapshot.available(f.key, 1))
        assertEquals(quantity(0), f.snapshot.available(quarantine, 1))
        assertEquals(quantity(1), f.snapshot.position(quarantine)!!.onHand)
        assertEquals(StockSafety.RECALLED, f.snapshot.position(quarantine)!!.safety)
        assertEquals(money(99), f.snapshot.pool(pool)!!.value.carryingValue)
    }

    @Test fun afterStartWasteClassifiesAlreadyIssuedCostWithoutSecondIssueOrRestock() {
        val f = InventoryFixture()
        f.receive(2, 333)
        val r = f.reserve(2)
        val sent = f.issue(r.proof.reservationId, recipe = recipe()) as ReservationIssueResult.Issued
        var issue = f.snapshot.issue(sent.proof.issueId)!!
        f.snapshot = InventoryPlanner.acknowledgeKitchenStart(f.snapshot, f.snapshot.revision, f.ids.operation(),
            issue.issueId, issue.revision, stations.last()).nextSnapshot
        issue = f.snapshot.issue(issue.issueId)!!
        val waste = InventoryPlanner.cancelRecipeAfterStart(f.snapshot, f.snapshot.revision, f.ids.operation(), issue.issueId,
            issue.revision, f.ids.next())
        f.snapshot = waste.nextSnapshot
        assertFalse(waste.effects.any { it is InventoryEffect.Movement || it is InventoryEffect.Valuation })
        assertEquals(quantity(0), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(0), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(money(333), waste.effects.filterIsInstance<InventoryEffect.WasteClassified>().single().originalValue)
        val disposed = f.snapshot.issue(issue.issueId)!!
        fails(InventoryFailure.INVALID_STATE) {
            InventoryPlanner.cancelRecipeAfterStart(f.snapshot, f.snapshot.revision, f.ids.operation(), issue.issueId,
                disposed.revision, f.ids.next())
        }
        fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) {
            InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), issue.issueId,
                disposed.revision, listOf(StockRestoration(issue.allocations.single().allocation.allocationId, quantity(1), f.key)), 1)
        }
    }

    @Test fun beforeStartFullReversalAlsoReclassifiesEarlierLateCostWithoutChangingOriginalIssue() {
        val f = InventoryFixture()
        val receipt = f.receive(10, 20_000)
        f.receive(10, 30_000)
        val r = f.reserve(5)
        val sent = f.issue(r.proof.reservationId, recipe = recipe()) as ReservationIssueResult.Issued
        f.snapshot = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), pool,
            f.snapshot.pool(pool)!!.value.revision, f.ids.next(), f.ids.next(), receipt, money(12_000)).nextSnapshot
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        val confirmation = KitchenCancellationConfirmation(f.ids.next(), issue.issueId, issue.revision, id(303), stations)
        val cancellation = InventoryPlanner.cancelRecipeBeforeStart(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), confirmation,
            issue.allocations.map { StockRestoration(it.allocation.allocationId, it.allocation.quantity, it.allocation.position) }, 1)
        val valuePlan = cancellation.effects.filterIsInstance<InventoryEffect.Valuation>().single().plan
        assertEquals(money(12_500), valuePlan.originalValueDelta)
        assertEquals(money(3_000), valuePlan.linkedAdjustment!!.heldDelta)
        assertEquals(money(-3_000), valuePlan.linkedAdjustment!!.consumedDelta)
        assertEquals(money(62_000), cancellation.nextSnapshot.pool(pool)!!.value.carryingValue)
        assertEquals(money(12_500), issue.originalValue)
    }
}

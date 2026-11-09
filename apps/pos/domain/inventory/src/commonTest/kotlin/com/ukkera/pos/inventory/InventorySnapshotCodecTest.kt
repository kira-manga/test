package com.ukkera.pos.inventory

import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InventorySnapshotCodecTest {
    @Test fun emptyCutRetainsDomainZeroAndDoesNotInventDatabaseRevisionMapping() {
        val original = InventorySnapshot.empty(scope)
        val restored = storedInventoryRoundTrip(original)
        assertEquals(Revision(0), restored.revision)
        assertTrue(restored.positions.isEmpty())
        val encoded = InventorySnapshotCodec.encode(original)
        val error = kotlin.test.assertFailsWith<InventorySnapshotRejectedException> {
            InventorySnapshotCodec.decode(encoded, storedInventoryBinding(original).copy(revision = Revision(1)))
        }
        assertEquals(InventorySnapshotFailure.BINDING_MISMATCH, error.failure)
        val ids = IdSequence()
        val checkedEmpty = InventoryPlanner.expireReservations(restored, restored.revision, ids.operation(), 1).nextSnapshot
        assertEquals(Revision(1), storedInventoryRoundTrip(checkedEmpty).revision)
    }

    @Test fun ownedQuarantineAndSupplierCustodyKeepSeparatePositionsAndOwnedValue() {
        val f = InventoryFixture()
        val quarantine = f.key.copy(custody = StockCustody.QUARANTINE)
        val supplier = f.key.copy(pool = pool.copy(ownership = StockOwnership.SupplierOwned(id(550))), custody = StockCustody.REJECTED_HELD)
        f.snapshot = InventoryPlanner.receive(f.snapshot, f.snapshot.revision, f.ids.operation(), listOf(
            StockReceipt(f.ids.next(), f.key, quantity(20), money(2_000)),
            StockReceipt(f.ids.next(), quarantine, quantity(2), money(200)),
            StockReceipt(f.ids.next(), supplier, quantity(2), null),
        )).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(24L, f.snapshot.positions.sumOf { it.onHand.scaled })
        assertEquals(money(2_200), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(quantity(20), f.snapshot.available(f.key, 1))
        assertEquals(quantity(0), f.snapshot.available(quarantine, 1))
        assertEquals(quantity(0), f.snapshot.available(supplier, 1))
        assertEquals(null, f.snapshot.pool(supplier.pool))
    }

    @Test fun multiPoolIssueRetainsConcretePortionsOriginalCostsAndDuplicateSourceProtection() {
        val f = InventoryFixture()
        val second = f.key.copy(pool = pool.copy(warehouseId = id(551)))
        f.receive(3, 100)
        f.receive(4, 200, second)
        val allocations = listOf(StockAllocation(f.ids.next(), id(560), second, quantity(2)),
            StockAllocation(f.ids.next(), id(561), f.key, quantity(1)))
        val reserved = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            binding(CollectionExposure.UNKNOWN), allocations, 1)
        f.snapshot = storedInventoryRoundTrip(reserved.plan.nextSnapshot)
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        val issue = f.snapshot.issue(issued.proof.issueId)!!
        assertEquals(allocations.toSet(), issue.allocations.map { it.allocation }.toSet())
        assertEquals(money(133), issue.originalValue)
        assertEquals(CollectionExposure.UNKNOWN, issue.binding.exposure)
        fails(InventoryFailure.RESERVATION_NOT_ACTIVE) { f.issue(reserved.proof.reservationId) }
        fails(InventoryFailure.DUPLICATE_SOURCE) {
            InventoryPlanner.expireReservations(f.snapshot, f.snapshot.revision, f.snapshot.operationIds.first(), 2)
        }
    }

    @Test fun fractionalScaleAndHighestSignedMoneyAndQuantityRemainCanonicalExactScalars() {
        val fractional = InventoryFixture()
        fractional.snapshot = InventoryPlanner.receive(fractional.snapshot, fractional.snapshot.revision, fractional.ids.operation(),
            listOf(StockReceipt(fractional.ids.next(), fractional.key, quantity(1_234_567, 6), money(987)))).nextSnapshot
        val restoredFractional = storedInventoryRoundTrip(fractional.snapshot)
        assertEquals(quantity(1_234_567, 6), restoredFractional.position(fractional.key)!!.onHand)
        val huge = InventoryFixture()
        huge.receive(Long.MAX_VALUE, Long.MAX_VALUE)
        val reservation = huge.reserve(Long.MAX_VALUE)
        val issued = assertIs<ReservationIssueResult.Issued>(huge.issue(reservation.proof.reservationId))
        huge.snapshot = storedInventoryRoundTrip(huge.snapshot)
        storedInventoryReturn(huge, issued.proof.issueId, Long.MAX_VALUE - 1)
        huge.snapshot = storedInventoryRoundTrip(huge.snapshot)
        assertEquals(money(Long.MAX_VALUE), huge.snapshot.issue(issued.proof.issueId)!!.originalValue)
        assertEquals(quantity(Long.MAX_VALUE - 1), huge.snapshot.position(huge.key)!!.onHand)
        assertEquals(money(Long.MAX_VALUE - 1), huge.snapshot.pool(pool)!!.value.carryingValue)
    }

    @Test fun lateCostReturnAndCancellationRetainHistoricalMoneyAndSeparateReclassifications() {
        val fixture = storedFreightFixture()
        val f = fixture.fixture
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(money(46_500), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(money(12_500), f.snapshot.issue(fixture.issueId)!!.originalValue)
        storedInventoryReturn(f, fixture.issueId, 2)
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        val value = f.snapshot.pool(pool)!!.value
        val restored = value.facts.filterIsInstance<ValuationFact.Restoration>().single()
        assertEquals(money(5_000), restored.restorations.single().originalValue)
        assertEquals(money(1_200), value.adjustments.last().heldDelta)
        assertEquals(money(-1_200), value.adjustments.last().consumedDelta)
        assertEquals(money(52_700), value.carryingValue)
        storedInventoryCorrection(f, fixture.receiptId, 0, fixture.costSource)
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(money(42_500), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(fixture.costSource, f.snapshot.pool(pool)!!.value.costTargets.single().sourceId)
        assertEquals(money(0), f.snapshot.pool(pool)!!.value.costTargets.single().targetDelta)
        assertEquals(money(12_500), f.snapshot.issue(fixture.issueId)!!.originalValue)
        assertEquals(money(-10_200), f.snapshot.pool(pool)!!.value.adjustments.last().heldDelta)
    }

    @Test fun laterIssueCapturesThenKnownValueWithoutRewritingEitherOriginalWhenCostIsCancelled() {
        val fixture = storedFreightFixture()
        val f = fixture.fixture
        val reserved = f.reserve(5)
        val second = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(money(15_500), f.snapshot.issue(second.proof.issueId)!!.originalValue)
        storedInventoryCorrection(f, fixture.receiptId, 0, fixture.costSource)
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(money(12_500), f.snapshot.issue(fixture.issueId)!!.originalValue)
        assertEquals(money(15_500), f.snapshot.issue(second.proof.issueId)!!.originalValue)
        assertEquals(money(25_000), f.snapshot.pool(pool)!!.value.carryingValue)
        assertTrue(f.snapshot.pool(pool)!!.value.linkedCorrectionBalances.any { it.value.minor != 0L })
        assertEquals(0L, f.snapshot.pool(pool)!!.value.linkedCorrectionBalances.sumOf { it.value.minor })
    }

    @Test fun repeatedPartialReturnsRestartFromOriginalCumulativeCapturedCost() {
        val f = InventoryFixture()
        f.receive(3, 2)
        val reserved = f.reserve(3)
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        val amounts = mutableListOf<Long>()
        repeat(3) {
            f.snapshot = storedInventoryRoundTrip(f.snapshot)
            storedInventoryReturn(f, issued.proof.issueId, 1)
            amounts += f.snapshot.pool(pool)!!.value.facts.filterIsInstance<ValuationFact.Restoration>().last().restorations.single().originalValue.minor
        }
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(listOf(1L, 0L, 1L), amounts)
        assertEquals(quantity(3), f.snapshot.issue(issued.proof.issueId)!!.allocations.single().restoredQuantity)
        fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) { storedInventoryReturn(f, issued.proof.issueId, 1) }
    }

    @Test fun countShortageCanRetainClaimsAboveStockOnlyAsExplicitHolds() {
        val f = InventoryFixture()
        f.receive(5, 500)
        val reserved = f.reserve(4, CollectionExposure.PART_PAID)
        f.snapshot = InventoryPlanner.recordCountShortage(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), f.key,
            f.snapshot.revision, quantity(2)).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        val claim = f.snapshot.reservation(reserved.proof.reservationId)!!
        assertEquals(ReservationStatus.HELD, claim.status)
        assertEquals(listOf(ReservationHold(ReservationHoldReason.COUNT_SHORTAGE, f.key)), claim.holds)
        assertEquals(quantity(4), claim.allocations.single().quantity)
        assertEquals(quantity(2), f.snapshot.position(f.key)!!.onHand)
        assertEquals(quantity(0), f.snapshot.available(f.key, 1))
        assertIs<ReservationIssueResult.Held>(f.issue(claim.reservationId))
    }

    @Test fun decodeDoesNotRefreshLeaseTimeOrTurnHydratedStateIntoAReusableSafetyProof() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val reserved = f.reserve(1, CollectionExposure.PAID, expires = 9)
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(9L, f.snapshot.reservation(reserved.proof.reservationId)!!.expiresAtEpochMillis)
        assertIs<ReservationIssueResult.Held>(f.issue(reserved.proof.reservationId, now = 9))
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(CollectionExposure.PAID, f.snapshot.reservations.single().binding.exposure)
        assertTrue(ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED) in f.snapshot.reservations.single().holds)
        assertTrue(f.snapshot.issues.isEmpty())
    }

    @Test fun expiryObservedOnQuarantineReturnAlsoSurvivesOnReleasedStockAfterClockRollback() {
        val f = InventoryFixture()
        f.receive(5, 500, expires = 9)
        val first = f.reserve(2)
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(first.proof.reservationId))
        val paid = f.reserve(1, CollectionExposure.PAID)
        val quarantine = f.key.copy(custody = StockCustody.QUARANTINE)
        storedInventoryReturn(f, issued.proof.issueId, 1, now = 9, destination = quarantine)
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(StockSafety.EXPIRED, f.snapshot.position(f.key)!!.safety)
        assertEquals(StockSafety.EXPIRED, f.snapshot.position(quarantine)!!.safety)
        assertEquals(ReservationStatus.HELD, f.snapshot.reservation(paid.proof.reservationId)!!.status)
        assertEquals(quantity(0), f.snapshot.available(f.key, 1))
        assertEquals(quantity(0), f.snapshot.available(quarantine, 1))
    }

    @Test fun releasedPaidClaimStillRetainsItsCommercialBindingAndHistoricalHolds() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val reserved = f.reserve(1, CollectionExposure.UNKNOWN)
        f.snapshot = InventoryPlanner.recallLot(f.snapshot, f.snapshot.revision, f.ids.operation(), pool.skuId, f.key.lotId!!).nextSnapshot
        val claim = f.snapshot.reservation(reserved.proof.reservationId)!!
        val resolution = CollectionResolutionReference(f.ids.next(), claim.binding.orderId, claim.binding.collectionId, f.ids.next())
        f.snapshot = InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(), claim.reservationId,
            claim.revision, resolution).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        assertEquals(ReservationStatus.RELEASED, f.snapshot.reservations.single().status)
        assertEquals(claim.binding, f.snapshot.reservations.single().binding)
        assertEquals(claim.holds, f.snapshot.reservations.single().holds)
        // The matching resolution/audit/obligation row is a separate mandatory store dependency.
        assertFalse(InventorySnapshotCodec.encode(f.snapshot).contains("preservedObligationId"))
    }

    @Test fun fullBeforeStartRecipeCancellationRestoresOnceAndPreservesRequiredStationsAndLateCost() {
        val fixture = storedFreightFixture()
        val f = fixture.fixture
        val reserved = f.reserve(2, CollectionExposure.PAID)
        val stations = listOf(id(570), id(571))
        val sent = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId,
            recipe = RecipeIssueRequest(id(572), stations)))
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        val confirmation = KitchenCancellationConfirmation(f.ids.next(), issue.issueId, issue.revision, id(573), stations)
        f.snapshot = InventoryPlanner.cancelRecipeBeforeStart(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), confirmation,
            issue.allocations.map { StockRestoration(it.allocation.allocationId, it.allocation.quantity, it.allocation.position) }, 1).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        val restored = f.snapshot.issue(issue.issueId)!!
        val kitchen = restored.kitchen!!
        assertEquals(stations, kitchen.requiredStationIds)
        assertEquals(KitchenDisposition.CANCELLED_BEFORE_START, kitchen.disposition)
        assertEquals(issue.originalValue, restored.originalValue)
        assertTrue(restored.allocations.all { it.restoredQuantity == it.allocation.quantity })
        assertEquals(Revision(2), restored.revision)
    }

    @Test fun acknowledgedRecipeStartAndWasteRestartWithoutASecondIssueOrRestoration() {
        val f = InventoryFixture()
        f.receive(2, 333)
        val reserved = f.reserve(2)
        val station = id(574)
        val sent = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId,
            recipe = RecipeIssueRequest(id(575), listOf(station, id(576)))))
        var issue = f.snapshot.issue(sent.proof.issueId)!!
        f.snapshot = InventoryPlanner.acknowledgeKitchenStart(f.snapshot, f.snapshot.revision, f.ids.operation(), issue.issueId,
            issue.revision, station).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        issue = f.snapshot.issue(issue.issueId)!!
        f.snapshot = InventoryPlanner.cancelRecipeAfterStart(f.snapshot, f.snapshot.revision, f.ids.operation(), issue.issueId,
            issue.revision, f.ids.next()).nextSnapshot
        f.snapshot = storedInventoryRoundTrip(f.snapshot)
        val waste = f.snapshot.issue(issue.issueId)!!
        val kitchen = waste.kitchen!!
        assertEquals(KitchenDisposition.WASTE_AFTER_START, kitchen.disposition)
        assertEquals(listOf(station), kitchen.startedStationIds)
        assertEquals(quantity(0), f.snapshot.position(f.key)!!.onHand)
        assertEquals(money(333), waste.originalValue)
        assertEquals(1, f.snapshot.pool(pool)!!.value.facts.filterIsInstance<ValuationFact.Issue>().size)
        assertTrue(f.snapshot.pool(pool)!!.value.facts.none { it is ValuationFact.Restoration })
    }

    @Test fun decodedCollectionsAreDefensivelyFrozenAndEnvelopeContainsNoAuthorizationOrPlan() {
        val fixture = storedFreightFixture()
        val snapshot = storedInventoryRoundTrip(fixture.fixture.snapshot)
        assertFails { (snapshot.positions as MutableList<*>).clear() }
        assertFails { (snapshot.reservations.single().allocations as MutableList<*>).clear() }
        assertFails { (snapshot.issues.single().allocations as MutableList<*>).clear() }
        assertFails { (snapshot.pools.single().value.facts as MutableList<*>).clear() }
        assertFails { (snapshot.pools.single().value.adjustments.single().deltas as MutableList<*>).clear() }
        val encoded = InventorySnapshotCodec.encode(snapshot)
        for (excluded in listOf("authorizationToken", "ReservationProof", "IssueProof", "mustCommitBeforeEpochMillis", "validatedAtEpochMillis", "requiredInventoryRevision")) {
            assertFalse(encoded.contains(excluded))
        }
    }

    @Test fun wholeCutCodecCapacityDoesNotExpandTheSmallerKernelStoredBytesLimit() {
        val f = InventoryFixture()
        val receipts = List(256) { StockReceipt(f.ids.next(), f.key, quantity(1), money(1)) }
        f.snapshot = InventoryPlanner.receive(f.snapshot, f.snapshot.revision, f.ids.operation(), receipts).nextSnapshot
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        assertTrue(encoded.encodeToByteArray().size > 65_536)
        assertTrue(encoded.encodeToByteArray().size < InventorySnapshotCodec.MAX_ENCODED_BYTES)
        assertEquals(quantity(256), InventorySnapshotCodec.decode(encoded, storedInventoryBinding(f.snapshot)).position(f.key)!!.onHand)
        // A typed Room handler must reject this payload; no kernel-limit change or paging is supplied here.
    }
}

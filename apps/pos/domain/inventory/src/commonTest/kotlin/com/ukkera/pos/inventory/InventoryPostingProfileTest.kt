package com.ukkera.pos.inventory

import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InventoryPostingProfileTest {
    @Test fun emptyGenesisNeedsNoManufacturedPlanOrOperation() {
        val snapshot = InventorySnapshot.empty(scope)
        supportedWithoutMutation(snapshot)
        assertEquals(Revision(0), snapshot.revision)
        assertTrue(snapshot.operationIds.isEmpty())
        assertTrue(snapshot.sourceIds.isEmpty())
        assertTrue(snapshot.positions.isEmpty())
        assertTrue(snapshot.pools.isEmpty())
    }

    @Test fun purchaseReceiptsKeepEveryPoolLotAndSourceInTheSuppliedCut() {
        val f = PostingFixture()
        val other = f.key.copy(pool = pool.copy(warehouseId = id(900)))
        val receipts = listOf(StockReceipt(f.ids.next(), f.key, quantity(2), money(10)),
            StockReceipt(f.ids.next(), other, quantity(3), money(20)),
            StockReceipt(f.ids.next(), f.key.copy(lotId = id(901)), quantity(1), money(30)))
        f.receipts(receipts)
        supportedWithoutMutation(f.snapshot)
        assertEquals(receipts.map { it.receiptId }, f.snapshot.sourceIds)
        assertEquals(3, f.snapshot.positions.size)
        assertEquals(quantity(3), f.snapshot.pool(pool)!!.value.onHand)
        assertEquals(money(40), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(money(20), f.snapshot.pool(other.pool)!!.value.carryingValue)
    }

    @Test fun everyCollectionExposureRemainsUninterpretedHistoricalData() {
        val f = PostingFixture(); f.receive()
        CollectionExposure.entries.forEach { f.reserve(exposure = it) }
        supportedWithoutMutation(f.snapshot)
        assertEquals(CollectionExposure.entries.toList(), f.snapshot.reservations.map { it.binding.exposure })
        assertTrue(f.snapshot.reservations.all { it.status == ReservationStatus.RESERVED })
        assertTrue(f.snapshot.issues.isEmpty()) // PAID/UNKNOWN do not become an issue or payment proof.
    }

    @Test fun retailIssuesRetainExhaustedStockAndZeroConsumedDestinations() {
        val f = PostingFixture(); f.receive(3, 1)
        val first = f.reserve(listOf(f.allocation(2)))
        val firstIssue = assertIs<ReservationIssueResult.Issued>(f.issue(first.proof.reservationId))
        val second = f.reserve(listOf(f.allocation(1)))
        val secondIssue = assertIs<ReservationIssueResult.Issued>(f.issue(second.proof.reservationId))
        supportedWithoutMutation(f.snapshot)
        val value = f.snapshot.pool(pool)!!.value
        assertEquals(quantity(0), f.snapshot.position(f.key)!!.onHand)
        assertEquals(quantity(0), value.onHand)
        assertEquals(money(0), value.carryingValue)
        assertEquals(money(1), f.snapshot.issue(firstIssue.proof.issueId)!!.originalValue)
        assertEquals(money(0), f.snapshot.issue(secondIssue.proof.issueId)!!.originalValue)
        val consumed = value.linkedCorrectionBalances.filter { it.destination is ValueDestination.Consumed }
        assertEquals(2, consumed.size)
        assertEquals(setOf(firstIssue.proof.issueId, secondIssue.proof.issueId),
            consumed.map { (it.destination as ValueDestination.Consumed).issueId }.toSet())
        assertTrue(value.linkedCorrectionBalances.all { it.value == money(0) })
        assertTrue(f.snapshot.reservations.all { it.status == ReservationStatus.ISSUED })
    }

    @Test fun wholeHeldCutKeepsCollateralExpiryOverlapAndIntermediateRevisionJumps() {
        val held = postingHeldFixture()
        val before = held.result.plan.sourceSnapshot
        val after = held.result.plan.nextSnapshot
        supportedWithoutMutation(before)
        supportedWithoutMutation(after)
        assertEquals(Revision(4), after.reservation(held.target)!!.revision)
        assertEquals(Revision(3), after.reservation(held.overlap)!!.revision)
        assertEquals(2, after.reservation(held.target)!!.holds.size)
        assertEquals(2, after.reservation(held.overlap)!!.holds.size)
        assertEquals(quantity(0), after.position(held.zero)!!.onHand)
        assertEquals(StockSafety.EXPIRED, after.position(held.zero)!!.safety)
        assertEquals(before.sourceIds, after.sourceIds)
        assertEquals(before.issues, after.issues)
        assertEquals(before.operationIds + held.result.plan.operationId, after.operationIds)
    }

    @Test fun successfulIssueAlsoRetainsUnrelatedExpiredHeldClaims() {
        val issued = postingIssueWithExpiry()
        val snapshot = issued.plan.nextSnapshot
        supportedWithoutMutation(snapshot)
        assertEquals(ReservationStatus.ISSUED, snapshot.reservation(issued.proof.reservationId)!!.status)
        val collateral = snapshot.reservations.single { it.reservationId != issued.proof.reservationId }
        assertEquals(ReservationStatus.HELD, collateral.status)
        assertEquals(ReservationHoldReason.EXPIRY, collateral.holds.single().reason)
        assertEquals(StockSafety.EXPIRED, snapshot.position(collateral.holds.single().position!!)!!.safety)
        assertEquals(issued.plan.sourceSnapshot.sourceIds + issued.proof.issueId, snapshot.sourceIds)
    }

    @Test fun reservationLeaseExpiryDoesNotRequirePhysicalExpiryOrInventAnIssue() {
        val f = PostingFixture(); f.receive(expires = 100)
        val reservation = f.reserve(expires = 10)
        val held = assertIs<ReservationIssueResult.Held>(f.issue(reservation.proof.reservationId, 10))
        supportedWithoutMutation(f.snapshot)
        assertEquals(listOf(ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED)),
            f.snapshot.reservation(reservation.proof.reservationId)!!.holds)
        assertEquals(StockSafety.CLEAR, f.snapshot.position(f.key)!!.safety)
        assertEquals(held.plan.sourceSnapshot.sourceIds, f.snapshot.sourceIds)
        assertTrue(f.snapshot.issues.isEmpty())
    }

    @Test fun historicalClearStockCanBeExpiredNowWithoutRenewingTheLivePlanDeadline() {
        val f = PostingFixture(); f.receive(expires = 10)
        val plan = f.reserve().plan
        assertEquals(10L, plan.mustCommitBeforeEpochMillis)
        supportedWithoutMutation(plan.nextSnapshot)
        assertEquals(StockSafety.CLEAR, plan.nextSnapshot.position(f.key)!!.safety)
        assertFalse(plan.nextSnapshot.position(f.key)!!.isSellableAt(10))
        assertEquals(quantity(0), plan.nextSnapshot.available(f.key, 10))
        postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) { postingCapture(plan, 10) }
        assertEquals(10L, plan.mustCommitBeforeEpochMillis)
    }

    @Test fun detachedHistoricalSnapshotIsValidDataButNotThePlansExactSource() {
        val f = PostingFixture(); f.receive()
        val plan = f.reserve().plan
        val source = plan.sourceSnapshot
        val detached = InventorySnapshotCodec.decode(InventorySnapshotCodec.encode(source), storedInventoryBinding(source))
        assertFalse(detached === source)
        supportedWithoutMutation(detached)
        postingFailure(InventoryPostingFailure.BINDING_MISMATCH) {
            InventoryPosting.fromPlan(detached, plan, postingBinding(plan), 1)
        }
    }

    @Test fun oldPositiveZeroAndCancelledCostHistoryRejectsTheWholeUnrelatedCut() {
        for (mode in listOf("positive", "zero", "cancelled")) {
            val f = PostingFixture(); f.receive()
            val other = f.key.copy(pool = pool.copy(warehouseId = id(902)))
            val receiptPlan = f.receive(at = other)
            val receiptId = (receiptPlan.effects[0] as InventoryEffect.Valuation).plan.fact!!.factId
            val costSource = f.ids.next()
            fun adjust(amount: Long) {
                f.snapshot = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), other.pool,
                    f.snapshot.pool(other.pool)!!.value.revision, f.ids.next(), costSource, receiptId, money(amount)).nextSnapshot
            }
            adjust(if (mode == "zero") 0 else 10)
            if (mode == "cancelled") adjust(0)
            f.reserve() // A later ordinary reservation does not erase the unrelated pool's history.
            val value = f.snapshot.pool(other.pool)!!.value
            assertTrue(value.costTargets.isNotEmpty())
            assertTrue(value.adjustments.isNotEmpty())
            if (mode != "positive") assertEquals(money(0), value.costTargets.single().targetDelta)
            unsupportedHistoricalSnapshot(f.snapshot)
        }
    }

    @Test fun genericOpeningReceiptIsNotAPurchaseReceipt() {
        val f = PostingFixture()
        val plan = f.receive()
        val receipt = (plan.effects[0] as InventoryEffect.Valuation).plan.fact as ValuationFact.Receipt
        val opening = MovingAveragePlanner.receive(MovingAverageState.empty(unit, 0), Revision(0), receipt.factId,
            receipt.quantity, receipt.originalValue, ReceiptValueOrigin.OPENING).next
        unsupportedHistoricalSnapshot(postingSnapshotCopy(f.snapshot, pools = listOf(OwnedValuationPool(pool, opening))))
    }

    @Test fun originalReturnHistoryRejectsEvenAfterAnotherPurchase() {
        val f = PostingFixture(); f.receive()
        val reservation = f.reserve(listOf(f.allocation(2)))
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reservation.proof.reservationId))
        val issue = f.snapshot.issue(issued.proof.issueId)!!
        f.snapshot = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            issue.issueId, issue.revision, listOf(StockRestoration(issue.allocations.single().allocation.allocationId,
                quantity(1), f.key)), 1).nextSnapshot
        f.receive(1, 10)
        unsupportedHistoricalSnapshot(f.snapshot)
        assertEquals(quantity(1), f.snapshot.issue(issue.issueId)!!.allocations.single().restoredQuantity)
    }

    @Test fun recipeKitchenHistoryIsNotRetailHistory() {
        val f = PostingFixture(); f.receive()
        val reservation = f.reserve()
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reservation.proof.reservationId,
            recipe = RecipeIssueRequest(id(903), listOf(id(904)))))
        assertEquals(ValueIssuePurpose.DIRECT_RECIPE, f.snapshot.issue(issued.proof.issueId)!!.purpose)
        unsupportedHistoricalSnapshot(f.snapshot)
    }

    @Test fun recallAndCountShortageHistoriesAreNotSilentlyFiltered() {
        val recall = PostingFixture(); recall.receive(); recall.reserve()
        recall.snapshot = InventoryPlanner.recallLot(recall.snapshot, recall.snapshot.revision, recall.ids.operation(),
            pool.skuId, recall.key.lotId!!).nextSnapshot
        assertEquals(StockSafety.RECALLED, recall.snapshot.position(recall.key)!!.safety)
        unsupportedHistoricalSnapshot(recall.snapshot)

        val count = PostingFixture(); count.receive(); count.reserve(listOf(count.allocation(10)))
        count.snapshot = InventoryPlanner.recordCountShortage(count.snapshot, count.snapshot.revision, count.ids.operation(),
            count.ids.next(), count.key, count.snapshot.revision, quantity(9)).nextSnapshot
        assertEquals(StockSafety.COUNT_REVIEW, count.snapshot.position(count.key)!!.safety)
        unsupportedHistoricalSnapshot(count.snapshot)
    }

    @Test fun releasedReservationHistoryRejectsRatherThanDisappearing() {
        val f = PostingFixture(); f.receive()
        val reservation = f.reserve(exposure = CollectionExposure.UNCOLLECTED)
        f.snapshot = InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(), reservation.proof.reservationId,
            f.snapshot.reservation(reservation.proof.reservationId)!!.revision).nextSnapshot
        assertEquals(ReservationStatus.RELEASED, f.snapshot.reservations.single().status)
        unsupportedHistoricalSnapshot(f.snapshot)
    }

    @Test fun nonreleasedAndSupplierOwnedCustodyRemainUnsupported() {
        for (key in listOf(StockPositionKey(pool, id(905), StockCustody.QUARANTINE),
            StockPositionKey(pool.copy(ownership = StockOwnership.SupplierOwned(id(906))), id(907), StockCustody.REJECTED_HELD))) {
            val f = PostingFixture()
            f.receipts(listOf(StockReceipt(f.ids.next(), key, quantity(2),
                if (key.pool.ownership == StockOwnership.CompanyOwned) money(20) else null)))
            unsupportedHistoricalSnapshot(f.snapshot)
        }
    }

    @Test fun unknownMissingAndDuplicateSourceClaimsAreRejectedWithoutNormalization() {
        val snapshot = postingSimpleReceipt().nextSnapshot
        for (sources in listOf(snapshot.sourceIds + id(908), emptyList())) {
            postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) {
                InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(snapshot, sources = sources))
            }
        }
        postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) {
            InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(snapshot, sources = snapshot.sourceIds + snapshot.sourceIds))
        }
    }

    @Test fun structurallyInvalidOperationAndPhysicalPoolLinksStillReject() {
        val f = PostingFixture(); f.receive(); f.receive()
        val snapshot = f.snapshot
        val badCuts = listOf(
            postingSnapshotCopy(snapshot, revision = Revision(0)),
            postingSnapshotCopy(snapshot, operations = List(2) { snapshot.operationIds.first() }),
            postingSnapshotCopy(snapshot, positions = emptyList()),
            postingSnapshotCopy(snapshot, positions = snapshot.positions + snapshot.positions),
            postingSnapshotCopy(snapshot, pools = snapshot.pools + snapshot.pools),
            postingSnapshotCopy(snapshot, positions = listOf(snapshot.positions.single().copy(onHand = quantity(19)))),
        )
        badCuts.forEach { bad -> postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) {
            InventoryPosting.requireSupportedSnapshot(bad)
        } }
        supportedWithoutMutation(snapshot)
    }

    @Test fun reservationAndIssueCrossLinksAreNotOptionalForASupportedShape() {
        val f = PostingFixture(); f.receive(3, 30)
        val reserved = f.reserve(listOf(f.allocation(2)))
        val reservation = f.snapshot.reservations.single()
        val missingPosition = StockReservation(reservation.reservationId, reservation.binding,
            listOf(reservation.allocations.single().copy(position = f.key.copy(lotId = id(909)))),
            reservation.revision, reservation.status)
        val overclaim = StockReservation(f.ids.next(), reservation.binding, listOf(f.allocation(2)),
            Revision(1), ReservationStatus.RESERVED)
        for (reservations in listOf(listOf(missingPosition), listOf(reservation, overclaim))) {
            postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) {
                InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(f.snapshot, reservations = reservations))
            }
        }
        assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) {
            InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(f.snapshot, issues = emptyList()))
        }
        supportedWithoutMutation(f.snapshot)
    }

    @Test fun zeroCorrectionDestinationCannotBePrunedFromAnOtherwiseSupportedIssue() {
        val f = PostingFixture(); f.receive(1, 0)
        val reserved = f.reserve()
        assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        val value = f.snapshot.pool(pool)!!.value
        assertEquals(2, value.linkedCorrectionBalances.size)
        val pruned = MovingAverageState(value.unitId, value.scale, value.currency, value.revision, value.onHand, value.carryingValue,
            value.facts, value.costTargets, value.linkedCorrectionBalances.filter { it.destination == ValueDestination.Held },
            value.adjustments, value.eventIds)
        postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) {
            InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(f.snapshot, pools = listOf(OwnedValuationPool(pool, pruned))))
        }
        supportedWithoutMutation(f.snapshot)
    }

    @Test fun scopeUnitAndOverflowFailuresExposeOnlyThePostingFailureCode() {
        val f = PostingFixture(); f.receive(Long.MAX_VALUE, 0)
        val position = f.snapshot.positions.single()
        val wrongScope = position.copy(key = position.key.copy(pool = pool.copy(scope = scope.copy(branchId = id(910)))))
        postingFailure(InventoryPostingFailure.BINDING_MISMATCH) {
            InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(f.snapshot, positions = listOf(wrongScope)))
        }
        for (positions in listOf(listOf(position.copy(onHand = Quantity(id(911), Long.MAX_VALUE, 0))),
            listOf(position, position.copy(key = f.key.copy(lotId = id(912)), onHand = quantity(1))))) {
            postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) {
                InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(f.snapshot, positions = positions))
            }
        }
        assertNotEquals(id(911), position.onHand.unitId)
        supportedWithoutMutation(f.snapshot)
    }

    @Test fun outerStorageCountsRejectBeforeDuplicateOrProfileValidation() {
        val f = PostingFixture(); f.receive()
        val reservation = f.reserve()
        assertIs<ReservationIssueResult.Issued>(f.issue(reservation.proof.reservationId))
        val snapshot = f.snapshot
        val badCuts = listOf(
            postingSnapshotCopy(snapshot, positions = List(InventoryPlanner.MAX_POSITIONS + 1) { snapshot.positions.single() }),
            postingSnapshotCopy(snapshot, pools = List(InventoryPlanner.MAX_POOLS + 1) { snapshot.pools.single() }),
            postingSnapshotCopy(snapshot, reservations = List(InventoryPlanner.MAX_RESERVATIONS + 1) { snapshot.reservations.single() }),
            postingSnapshotCopy(snapshot, issues = List(InventoryPlanner.MAX_ISSUES + 1) { snapshot.issues.single() }),
            postingSnapshotCopy(snapshot, operations = List(InventoryPlanner.MAX_OPERATIONS + 1) { snapshot.operationIds.first() }),
            postingSnapshotCopy(snapshot, sources = List(InventorySnapshotCodec.MAX_ARRAY_ENTRIES + 1) { snapshot.sourceIds.first() }),
        )
        badCuts.forEach { bad -> postingFailure(InventoryPostingFailure.TOO_LARGE) { InventoryPosting.requireSupportedSnapshot(bad) } }
    }

    @Test fun nestedAllocationAndHoldCountsAreBoundedBeforeTheirContentsAreChecked() {
        val f = PostingFixture(); f.receive(); f.reserve()
        val reservation = f.snapshot.reservations.single()
        val tooManyAllocations = StockReservation(reservation.reservationId, reservation.binding,
            List(InventoryPlanner.MAX_ALLOCATIONS + 1) { reservation.allocations.single() }, reservation.revision, reservation.status)
        val tooManyHolds = StockReservation(reservation.reservationId, reservation.binding, reservation.allocations,
            Revision(2), ReservationStatus.HELD,
            List(InventoryPlanner.MAX_ALLOCATIONS * ReservationHoldReason.entries.size + 2) {
                ReservationHold(ReservationHoldReason.EXPIRY, f.key)
            })
        for (bad in listOf(tooManyAllocations, tooManyHolds)) {
            postingFailure(InventoryPostingFailure.TOO_LARGE) {
                InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(f.snapshot, reservations = listOf(bad)))
            }
        }
    }

    @Test fun aggregateRecordAndBytePreflightsDoNotFallThroughToInvalidRows() {
        val f = PostingFixture(); f.receive(); f.reserve()
        val reservation = f.snapshot.reservations.single()
        val wide = StockReservation(reservation.reservationId, reservation.binding,
            List(InventoryPlanner.MAX_ALLOCATIONS) { reservation.allocations.single() }, reservation.revision, reservation.status)
        val badCuts = listOf(
            postingSnapshotCopy(f.snapshot, sources = List(InventorySnapshotCodec.MAX_STORED_RECORDS) { id(30_000 + it) }),
            postingSnapshotCopy(f.snapshot, reservations = List(22) { wide }),
        )
        badCuts.forEach { bad -> postingFailure(InventoryPostingFailure.TOO_LARGE) { InventoryPosting.requireSupportedSnapshot(bad) } }
    }

    @Test fun valuationHistoryVerificationHasItsOwnBoundBeforeReplay() {
        val snapshot = postingSimpleReceipt().nextSnapshot
        val value = snapshot.pools.single().value
        val tooMuchWork = MovingAverageState(value.unitId, value.scale, value.currency, Revision(600), value.onHand,
            value.carryingValue, List(600) { value.facts.single() }, value.costTargets, value.linkedCorrectionBalances,
            value.adjustments, List(600) { id(50_000 + it) })
        assertTrue(600 * 601 > InventorySnapshotCodec.MAX_VALUATION_VERIFICATION_STEPS)
        postingFailure(InventoryPostingFailure.TOO_LARGE) {
            InventoryPosting.requireSupportedSnapshot(postingSnapshotCopy(snapshot, pools = listOf(OwnedValuationPool(pool, tooMuchWork))))
        }
    }
}

private fun supportedWithoutMutation(snapshot: InventorySnapshot) {
    val before = InventorySnapshotCodec.encode(snapshot)
    assertEquals(Unit, InventoryPosting.requireSupportedSnapshot(snapshot))
    assertEquals(before, InventorySnapshotCodec.encode(snapshot))
}

private fun unsupportedHistoricalSnapshot(snapshot: InventorySnapshot) {
    val before = InventorySnapshotCodec.encode(snapshot) // A valid generic snapshot can still be outside this profile.
    postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { InventoryPosting.requireSupportedSnapshot(snapshot) }
    assertEquals(before, InventorySnapshotCodec.encode(snapshot))
}

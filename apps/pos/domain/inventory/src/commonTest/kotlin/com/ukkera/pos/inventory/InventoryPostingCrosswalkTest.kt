package com.ukkera.pos.inventory

import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InventoryPostingCrosswalkTest {
    @Test fun multipleReceiptsInSamePoolUseSequentialReferentialBases() {
        val f = PostingFixture()
        val plan = f.receipts(listOf(StockReceipt(f.ids.next(), f.key, quantity(3), money(100)),
            StockReceipt(f.ids.next(), f.key.copy(lotId = id(820)), quantity(4), money(200))))
        val first = (plan.effects[0] as InventoryEffect.Valuation).plan
        val second = (plan.effects[2] as InventoryEffect.Valuation).plan
        assertSame(first.next, second.sourceBasis)
        val batch = postingRoundTrip(plan)
        assertEquals(listOf(Revision(0), Revision(1)), batch.effects.filterIsInstance<InventoryPostingEffect.Valuation>().map { it.requiredPoolRevision })
        assertEquals(quantity(7), plan.nextSnapshot.pool(pool)!!.value.onHand)
        assertEquals(money(300), plan.nextSnapshot.pool(pool)!!.value.carryingValue)
    }

    @Test fun interleavedMultiPoolReceiptBatchKeepsExactSourceAndStateOrder() {
        val f = PostingFixture()
        val other = f.key.copy(pool = pool.copy(warehouseId = id(821)))
        val receipts = listOf(StockReceipt(f.ids.next(), f.key, quantity(2), money(10)),
            StockReceipt(f.ids.next(), other, quantity(3), money(20)), StockReceipt(f.ids.next(), f.key, quantity(1), money(30)))
        val plan = f.receipts(receipts)
        val batch = postingRoundTrip(plan)
        assertEquals(receipts.map { it.receiptId }, batch.effects.filterIsInstance<InventoryPostingEffect.Movement>().map { it.sourceId })
        assertEquals(receipts.map { it.receiptId }, plan.nextSnapshot.sourceIds)
        assertEquals(listOf(other.pool, pool), plan.nextSnapshot.pools.map { it.key })
    }

    @Test fun newReceiptMetadataIsResultBoundAndRequiresTheLaterFullReceiptOriginCrosswalk() {
        val source = InventorySnapshot.empty(scope)
        val key = StockPositionKey(pool, id(860))
        fun receive(expiry: Long): InventoryPlan = InventoryPlanner.receive(source, source.revision, OperationId(id(861).value),
            listOf(StockReceipt(id(862), key, quantity(3), money(100), expiry)))
        val first = receive(10)
        val second = receive(20)
        val firstFacts = InventoryPostingCodec.encode(postingRoundTrip(first))
        val secondFacts = InventoryPostingCodec.encode(postingRoundTrip(second))
        assertEquals(firstFacts, secondFacts) // Real plans: no expiry field exists in the receipt Movement.
        assertNotEquals(InventorySnapshotCodec.encode(first.nextSnapshot), InventorySnapshotCodec.encode(second.nextSnapshot))
        assertEquals(10L, first.nextSnapshot.position(key)!!.expiresAtEpochMillis)
        assertEquals(20L, second.nextSnapshot.position(key)!!.expiresAtEpochMillis)
        // Neither batch is a receipt-origin capability. A2 must bind the complete cut AND request.
    }

    @Test fun detachedEqualWholeSourceCannotSubstituteForTheTransactionObject() {
        val f = PostingFixture(); f.receive()
        val plan = f.reserve().plan
        postingRoundTrip(plan)
        val detached = InventorySnapshotCodec.decode(InventorySnapshotCodec.encode(plan.sourceSnapshot), storedInventoryBinding(plan.sourceSnapshot))
        assertEquals(InventorySnapshotCodec.encode(plan.sourceSnapshot), InventorySnapshotCodec.encode(detached))
        assertFalse(detached === plan.sourceSnapshot)
        postingFailure(InventoryPostingFailure.BINDING_MISMATCH) {
            InventoryPosting.fromPlan(detached, plan, postingBinding(plan), 1)
        }
    }

    @Test fun matchingScopeRevisionAndOperationDoNotBindAlternateHistory() {
        val first = PostingFixture(); first.receive(value = 100)
        val second = PostingFixture(); second.receive(value = 200)
        val original = first.reserve().plan
        val alternate = second.reserve().plan
        postingRoundTrip(original); postingRoundTrip(alternate)
        assertEquals(postingBinding(original), postingBinding(alternate))
        assertNotEquals(InventorySnapshotCodec.encode(original.sourceSnapshot), InventorySnapshotCodec.encode(alternate.sourceSnapshot))
        postingFailure(InventoryPostingFailure.BINDING_MISMATCH) {
            InventoryPosting.fromPlan(original.sourceSnapshot, alternate, postingBinding(original), 1)
        }
    }

    @Test fun detachedEqualIntermediatePoolBasisIsRejectedEvenWithHealthyFinalCut() {
        val f = PostingFixture()
        val plan = f.receipts(List(2) { StockReceipt(f.ids.next(), f.key, quantity(2), money(10)) })
        postingRoundTrip(plan)
        val second = plan.effects[2] as InventoryEffect.Valuation
        val v = second.plan
        val detached = postingValueCopy(v.sourceBasis)
        assertEquals(StoredMovingAverageState.from(v.sourceBasis), StoredMovingAverageState.from(detached))
        assertFalse(detached === v.sourceBasis)
        val altered = InventoryEffect.Valuation(second.pool, ValuationPlan(v.requiredRevision, detached, v.next, v.fact,
            v.quantityDelta, v.originalValueDelta, v.linkedAdjustment))
        val forged = postingForge(plan, effects = plan.effects.toMutableList().also { it[2] = altered })
        postingFailure(InventoryPostingFailure.BINDING_MISMATCH) { postingCapture(forged) }
    }

    @Test fun aNewPoolRequiresCanonicalFullyEmptyBasisNotJustZeroQuantity() {
        val plan = postingSimpleReceipt(); postingRoundTrip(plan)
        val effect = plan.effects[0] as InventoryEffect.Valuation
        val v = effect.plan
        val base = v.sourceBasis
        val nonempty = MovingAverageState(base.unitId, base.scale, base.currency, base.revision, base.onHand, base.carryingValue,
            base.facts, base.costTargets, listOf(DestinationValue(ValueDestination.Held, money(0))), base.adjustments, base.eventIds)
        val altered = InventoryEffect.Valuation(effect.pool, ValuationPlan(v.requiredRevision, nonempty, v.next, v.fact,
            v.quantityDelta, v.originalValueDelta, null))
        postingFailure(InventoryPostingFailure.BINDING_MISMATCH) {
            postingCapture(postingForge(plan, effects = listOf(altered, plan.effects[1])))
        }
    }

    @Test fun issueSpansPoolsAndSameLotSlicesWithoutMergingAllocationIdentityOrCost() {
        val f = PostingFixture(); f.receive(3, 1)
        val other = f.key.copy(pool = pool.copy(warehouseId = id(822)))
        f.receive(2, 5, other)
        val first = f.allocation(1); val second = f.allocation(1); val third = f.allocation(2, other)
        val reserved = f.reserve(listOf(first, third, second)) // Interleaved reservation order is retained.
        val result = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        val batch = postingRoundTrip(result.plan)
        val movements = batch.effects.filterIsInstance<InventoryPostingEffect.Movement>()
        assertEquals(listOf(first.allocationId, second.allocationId, third.allocationId), movements.map { it.sourceAllocationId })
        assertEquals(2, movements.count { it.position == f.key })
        assertEquals(1, result.plan.nextSnapshot.sourceIds.count { it == result.proof.issueId })
        assertEquals(2, batch.effects.filterIsInstance<InventoryPostingEffect.Valuation>().count { it.fact.factId == result.proof.issueId })
        assertEquals(listOf(1L, 0L, 5L), result.proof.costedAllocations.map { it.originalValue.minor })
        assertEquals(money(6), result.plan.nextSnapshot.issue(result.proof.issueId)!!.originalValue)
        assertEquals(quantity(1), f.snapshot.pool(pool)!!.value.onHand)
        assertEquals(money(0), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(3, f.snapshot.pool(pool)!!.value.linkedCorrectionBalances.size)
        assertTrue(f.snapshot.pool(pool)!!.value.linkedCorrectionBalances.all { it.value == money(0) })
    }

    @Test fun zeroOriginalValueAndFinalPoolResidualDoNotPruneConsumedDestinations() {
        val f = PostingFixture(); f.receive(3, 1)
        val first = f.reserve(listOf(f.allocation(2)))
        postingRoundTrip(assertIs<ReservationIssueResult.Issued>(f.issue(first.proof.reservationId)).plan)
        val second = f.reserve(listOf(f.allocation(1)))
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(second.proof.reservationId))
        val decoded = postingRoundTrip(issued.plan)
        assertEquals(money(0), assertIs<InventoryPostingEffect.IssueRecorded>(decoded.effects.last()).issue.originalValue)
        assertEquals(quantity(0), f.snapshot.pool(pool)!!.value.onHand)
        assertEquals(money(0), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(3, f.snapshot.pool(pool)!!.value.linkedCorrectionBalances.size)
    }

    @Test fun issuedOutcomeStillPersistsUnrelatedCollateralExpiryAndHold() {
        val issued = postingIssueWithExpiry()
        val batch = postingRoundTrip(issued.plan, 10)
        assertIs<InventoryPostingEffect.SafetyChanged>(batch.effects[0])
        val collateral = assertIs<InventoryPostingEffect.ReservationRecorded>(batch.effects[1]).reservation
        assertNotEquals(issued.proof.reservationId, collateral.reservationId)
        assertEquals(ReservationStatus.HELD, collateral.status)
        assertEquals(issued.plan.sourceSnapshot.sourceIds + issued.proof.issueId, issued.plan.nextSnapshot.sourceIds)
    }

    @Test fun heldWholeCutKeepsTwoLotMultiJumpsOverlapAndUnrelatedZeroStockExpiry() {
        val fixture = postingHeldFixture()
        val plan = fixture.result.plan
        val batch = postingRoundTrip(plan, 10)
        val signature = batch.effects.map {
            when (it) {
                is InventoryPostingEffect.SafetyChanged -> "S:${it.before.key.lotId}"
                is InventoryPostingEffect.ReservationRecorded -> "R:${it.reservation.reservationId}:${it.reservation.revision.value}"
                else -> "UNEXPECTED"
            }
        }
        assertEquals(listOf("S:${fixture.first.lotId}", "R:${fixture.target}:2", "R:${fixture.overlap}:2",
            "S:${fixture.second.lotId}", "R:${fixture.target}:3", "R:${fixture.overlap}:3",
            "S:${fixture.zero.lotId}", "R:${fixture.target}:4"), signature)
        assertEquals(Revision(4), plan.nextSnapshot.reservation(fixture.target)!!.revision)
        assertEquals(quantity(0), plan.nextSnapshot.position(fixture.zero)!!.onHand)
        assertEquals(StockSafety.EXPIRED, plan.nextSnapshot.position(fixture.zero)!!.safety)
        assertEquals(plan.sourceSnapshot.sourceIds, plan.nextSnapshot.sourceIds)
        assertEquals(plan.sourceSnapshot.issues, plan.nextSnapshot.issues)
        assertEquals(plan.sourceSnapshot.operationIds + plan.operationId, plan.nextSnapshot.operationIds)
        assertEquals(plan.sourceSnapshot.revision.next(), plan.nextSnapshot.revision)
        assertEquals(null, plan.mustCommitBeforeEpochMillis)
        assertFalse(batch.effects.any { it is InventoryPostingEffect.Movement || it is InventoryPostingEffect.Valuation || it is InventoryPostingEffect.IssueRecorded })
    }

    @Test fun leaseExpiryOnlyProducesHeldRecordWithoutPhysicalExpiryOrIssueClaim() {
        val f = PostingFixture(); f.receive(expires = 100)
        val reservation = f.reserve(expires = 10)
        val held = assertIs<ReservationIssueResult.Held>(f.issue(reservation.proof.reservationId, 10))
        val batch = postingRoundTrip(held.plan, 10)
        assertEquals(1, batch.effects.size)
        assertEquals(listOf(ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED)),
            assertIs<InventoryPostingEffect.ReservationRecorded>(batch.effects.single()).reservation.holds)
        assertEquals(StockSafety.CLEAR, held.plan.nextSnapshot.position(f.key)!!.safety)
        assertEquals(held.plan.sourceSnapshot.sourceIds, held.plan.nextSnapshot.sourceIds)
    }

    @Test fun anotherHeldAttemptRetainsPriorHoldsAndAppendsOnlyItsOwnOperation() {
        val fixture = postingHeldFixture()
        val source = fixture.result.plan.nextSnapshot
        val result = assertIs<ReservationIssueResult.Held>(InventoryPlanner.issueReservation(source, source.revision,
            OperationId(id(830).value), fixture.target, source.reservation(fixture.target)!!.revision, id(831), 11))
        val batch = postingRoundTrip(result.plan, 11)
        assertEquals(1, batch.effects.size)
        assertEquals(Revision(5), assertIs<InventoryPostingEffect.ReservationRecorded>(batch.effects.single()).reservation.revision)
        assertEquals(source.sourceIds, result.plan.nextSnapshot.sourceIds)
    }

    @Test fun directSafeRevalidationRoundTripsWithoutInventingIssueOrCommitAuthority() {
        val f = PostingFixture(); f.receive(expires = 100)
        val reservation = f.reserve(expires = 50)
        val source = f.snapshot
        val result = assertIs<ReservationSafetyResult.Safe>(InventoryPlanner.revalidateReservation(source, source.revision,
            f.ids.operation(), reservation.proof.reservationId, source.reservation(reservation.proof.reservationId)!!.revision, 2))
        val batch = postingRoundTrip(result.plan, 2)
        val recorded = assertIs<InventoryPostingEffect.ReservationRecorded>(batch.effects.single()).reservation
        assertEquals(Revision(2), recorded.revision)
        assertEquals(ReservationStatus.RESERVED, recorded.status)
        assertTrue(recorded.holds.isEmpty())
        assertEquals(50L, result.plan.mustCommitBeforeEpochMillis) // Still an outer live-commit obligation.
        assertEquals(source.sourceIds, result.plan.nextSnapshot.sourceIds)
        assertEquals(source.issues, result.plan.nextSnapshot.issues)
    }

    @Test fun directHeldRevalidationPreservesExpiryAndBothIntermediateReservationRecords() {
        val f = PostingFixture(); f.receive(expires = 10)
        val reservation = f.reserve()
        val source = f.snapshot
        val result = assertIs<ReservationSafetyResult.Held>(InventoryPlanner.revalidateReservation(source, source.revision,
            f.ids.operation(), reservation.proof.reservationId, source.reservation(reservation.proof.reservationId)!!.revision, 10))
        val batch = postingRoundTrip(result.plan, 10)
        assertEquals(3, batch.effects.size)
        assertIs<InventoryPostingEffect.SafetyChanged>(batch.effects[0])
        val records = batch.effects.drop(1).map { assertIs<InventoryPostingEffect.ReservationRecorded>(it).reservation }
        assertEquals(listOf(Revision(2), Revision(3)), records.map { it.revision })
        assertTrue(records.all { it.status == ReservationStatus.HELD && it.holds == listOf(ReservationHold(ReservationHoldReason.EXPIRY, f.key)) })
        assertEquals(source.sourceIds, result.plan.nextSnapshot.sourceIds)
        assertEquals(source.issues, result.plan.nextSnapshot.issues)
    }

    @Test fun protectedLeaseExpirationHoldsUnknownPartialAndPaidWithoutSalesAdmission() {
        for (exposure in listOf(CollectionExposure.UNKNOWN, CollectionExposure.PART_PAID, CollectionExposure.PAID)) {
            val f = PostingFixture(); f.receive(expires = 100)
            f.reserve(exposure = exposure, expires = 10)
            val source = f.snapshot
            val plan = InventoryPlanner.expireReservations(source, source.revision, f.ids.operation(), 10)
            val batch = postingRoundTrip(plan, 10)
            val held = assertIs<InventoryPostingEffect.ReservationRecorded>(batch.effects.single()).reservation
            assertEquals(exposure, held.binding.exposure) // Inventory metadata, not proof of current coverage.
            assertEquals(Revision(2), held.revision)
            assertEquals(ReservationStatus.HELD, held.status)
            assertEquals(listOf(ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED)), held.holds)
            assertEquals(StockSafety.CLEAR, plan.nextSnapshot.position(f.key)!!.safety)
            assertEquals(source.sourceIds, plan.nextSnapshot.sourceIds)
            assertEquals(source.issues, plan.nextSnapshot.issues)
            assertEquals(source.operationIds + plan.operationId, plan.nextSnapshot.operationIds)
        }
    }

    @Test fun allExposureValuesRemainHistoricalAndRecordedPartialDoesNotMeanUncovered() {
        for (exposure in CollectionExposure.entries) {
            val f = PostingFixture(); f.receive()
            val reserved = f.reserve(exposure = exposure)
            val batch = postingRoundTrip(reserved.plan)
            assertEquals(exposure, assertIs<InventoryPostingEffect.ReservationRecorded>(batch.effects.single()).reservation.binding.exposure)
        }
        val f = PostingFixture(); f.receive()
        val reserved = f.reserve()
        val partial = InventoryPlanner.recordCollectionExposure(f.snapshot, f.snapshot.revision, f.ids.operation(), reserved.proof.reservationId,
            f.snapshot.reservation(reserved.proof.reservationId)!!.revision, CollectionExposure.PART_PAID)
        assertEquals(CollectionExposure.PART_PAID,
            assertIs<InventoryPostingEffect.ReservationRecorded>(postingRoundTrip(partial).effects.single()).reservation.binding.exposure)
    }

    @Test fun missingExtraAndReorderedReceiptEffectsRejectWithHealthyTwin() {
        val plan = postingSimpleReceipt(); postingRoundTrip(plan)
        for (effects in listOf(plan.effects.take(1), plan.effects + plan.effects, plan.effects.reversed())) {
            postingFailure { postingCapture(postingForge(plan, effects = effects)) }
        }
    }

    @Test fun swappingSamePositionMovementsCannotHideBehindEqualQuantityTotals() {
        val f = PostingFixture(); f.receive(3, 1)
        val reserved = f.reserve(listOf(f.allocation(), f.allocation()))
        val plan = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId)).plan
        postingRoundTrip(plan)
        val swapped = plan.effects.toMutableList().also { val first = it[1]; it[1] = it[2]; it[2] = first }
        postingFailure { postingCapture(postingForge(plan, effects = swapped)) }
    }

    @Test fun deletingIntermediateHoldOrMovingItBeforeItsSafetyChangeRejects() {
        val plan = postingHeldFixture().result.plan; postingRoundTrip(plan, 10)
        val missing = plan.effects.filterIndexed { index, _ -> index != 1 }
        val early = plan.effects.toMutableList().also { val safety = it[0]; it[0] = it[1]; it[1] = safety }
        for (effects in listOf(missing, early)) postingFailure { postingCapture(postingForge(plan, effects = effects), 10) }
    }

    @Test fun unreportedExpiryRewriteInUntouchedPoolRejectsDespiteValidGenericCodecState() {
        val f = PostingFixture(); f.receive(expires = 100)
        val other = f.key.copy(pool = pool.copy(warehouseId = id(832)), lotId = id(833))
        f.receive(at = other, expires = 10)
        val plan = f.reserve().plan; postingRoundTrip(plan)
        val altered = postingSnapshotCopy(plan.nextSnapshot, positions = plan.nextSnapshot.positions.map {
            if (it.key == other) it.copy(safety = StockSafety.EXPIRED) else it
        })
        assertTrue(InventorySnapshotCodec.encode(altered).isNotEmpty()) // Intrinsically valid, not a reported transition.
        postingFailure { postingCapture(postingForge(plan, next = altered)) }
    }

    @Test fun unreportedUntouchedReceiptHistoryRewriteRejectsEvenWhenMoneyStillReconciles() {
        val f = PostingFixture(); f.receive()
        val other = f.key.copy(pool = pool.copy(warehouseId = id(834)))
        val received = f.receive(at = other)
        val plan = f.reserve().plan; postingRoundTrip(plan)
        val receipt = (received.effects[0] as InventoryEffect.Valuation).plan.fact as ValuationFact.Receipt
        val different = MovingAveragePlanner.receive(MovingAverageState.empty(unit, 0), Revision(0), receipt.factId, receipt.quantity,
            receipt.originalValue + money(1)).next
        val altered = postingSnapshotCopy(plan.nextSnapshot, pools = plan.nextSnapshot.pools.map {
            if (it.key == other.pool) OwnedValuationPool(it.key, different) else it
        })
        assertTrue(InventorySnapshotCodec.encode(altered).isNotEmpty())
        postingFailure { postingCapture(postingForge(plan, next = altered)) }
    }

    @Test fun unchangedIndexesAndListOrderCannotBeDroppedOrReplaced() {
        val f = PostingFixture(); f.receive()
        f.receive(at = f.key.copy(lotId = id(835)))
        val plan = f.reserve().plan; postingRoundTrip(plan)
        val reordered = postingSnapshotCopy(plan.nextSnapshot, positions = plan.nextSnapshot.positions.reversed())
        assertTrue(InventorySnapshotCodec.encode(reordered).isNotEmpty())
        postingFailure { postingCapture(postingForge(plan, next = reordered)) }
        val unknownSource = postingSnapshotCopy(plan.nextSnapshot, sources = plan.nextSnapshot.sourceIds + id(836))
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(postingForge(plan, next = unknownSource)) }
        val wrongOperation = postingSnapshotCopy(plan.nextSnapshot, operations = plan.nextSnapshot.operationIds.dropLast(1) + OperationId(id(837).value))
        postingFailure { postingCapture(postingForge(plan, next = wrongOperation)) }
    }

    @Test fun independentExpectedOperationScopeAndNextRevisionAreNotCopiedFromPlan() {
        val plan = postingSimpleReceipt(); postingRoundTrip(plan)
        val binding = postingBinding(plan)
        for (bad in listOf(binding.copy(operationId = OperationId(id(838).value)), binding.copy(scope = scope.copy(branchId = id(839))),
            binding.copy(requiredInventoryRevision = Revision(1), resultingInventoryRevision = Revision(2)))) {
            postingFailure(InventoryPostingFailure.BINDING_MISMATCH) { InventoryPosting.fromPlan(plan.sourceSnapshot, plan, bad, 1) }
        }
    }

    @Test fun factoryChecksExclusiveDeadlineButHistoricalDecodeCannotAuthorizeLaterCommit() {
        val f = PostingFixture(); f.receive(expires = 10)
        val plan = f.reserve().plan
        assertEquals(10L, plan.mustCommitBeforeEpochMillis)
        val batch = postingCapture(plan, 9)
        postingFailure { postingCapture(plan, 10) }
        postingFailure { postingCapture(plan, -1) }
        val text = InventoryPostingCodec.encode(batch)
        assertEquals(text, InventoryPostingCodec.encode(InventoryPostingCodec.decode(text, postingBinding(plan))))
        assertFalse(text.contains("deadline"))
    }

    @Test fun costTargetsIncludingZeroAndCancelledHistoryRejectInSourceEvenWhenUntouched() {
        for (mode in listOf("positive", "zero", "cancelled")) {
            val f = PostingFixture(); f.receive()
            val other = f.key.copy(pool = pool.copy(warehouseId = id(840)))
            val receiptPlan = f.receive(at = other)
            val receipt = ((receiptPlan.effects[0] as InventoryEffect.Valuation).plan.fact as ValuationFact.Receipt).factId
            postingRoundTrip(f.noop())
            val costSource = f.ids.next()
            fun adjust(amount: Long) {
                f.snapshot = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), other.pool,
                    f.snapshot.pool(other.pool)!!.value.revision, f.ids.next(), costSource, receipt, money(amount)).nextSnapshot
            }
            adjust(if (mode == "zero") 0 else 10)
            if (mode == "cancelled") adjust(0)
            assertTrue(InventorySnapshotCodec.encode(f.snapshot).isNotEmpty())
            val source = f.snapshot
            val plan = f.reserve().plan // The changed receipt pool is unrelated to the reserved position.
            assertSame(source, plan.sourceSnapshot)
            postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(plan) }
        }
    }

    @Test fun costOnlyResultCannotMasqueradeAsAnEmptyPostedBatch() {
        val f = PostingFixture()
        val received = f.receive()
        val receipt = ((received.effects[0] as InventoryEffect.Valuation).plan.fact as ValuationFact.Receipt).factId
        postingRoundTrip(f.noop())
        for (amount in listOf(0L, 10L)) {
            val plan = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), pool,
                f.snapshot.pool(pool)!!.value.revision, f.ids.next(), f.ids.next(), receipt, money(amount))
            assertTrue(InventorySnapshotCodec.encode(plan.nextSnapshot).isNotEmpty())
            postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(plan) }
        }
    }

    @Test fun retailIssueWithNoNewAdjustmentStillRejectsOldCostHistory() {
        val f = PostingFixture()
        val receiptPlan = f.receive()
        val receipt = ((receiptPlan.effects[0] as InventoryEffect.Valuation).plan.fact as ValuationFact.Receipt).factId
        val healthy = f.reserve()
        postingRoundTrip(healthy.plan)
        f.snapshot = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), pool,
            f.snapshot.pool(pool)!!.value.revision, f.ids.next(), f.ids.next(), receipt, money(10)).nextSnapshot
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(healthy.proof.reservationId))
        assertTrue(issued.plan.effects.filterIsInstance<InventoryEffect.Valuation>().all { it.plan.linkedAdjustment == null })
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(issued.plan) }
    }

    @Test fun originalReturnRejectsBothResultAndSubsequentSource() {
        val f = PostingFixture(); f.receive()
        val reserved = f.reserve(listOf(f.allocation(2)))
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId))
        postingRoundTrip(issued.plan)
        val issue = f.snapshot.issue(issued.proof.issueId)!!
        val restored = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            issue.issueId, issue.revision, listOf(StockRestoration(issue.allocations.single().allocation.allocationId, quantity(1), f.key)), 1)
        assertTrue(InventorySnapshotCodec.encode(restored.nextSnapshot).isNotEmpty())
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(restored) }
        f.snapshot = restored.nextSnapshot
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(f.noop()) }
    }

    @Test fun recipeKitchenHistoryRejectsResultAndSubsequentSource() {
        val f = PostingFixture(); f.receive()
        val reserved = f.reserve(); postingRoundTrip(reserved.plan)
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId, recipe = RecipeIssueRequest(id(841), listOf(id(842)))))
        assertTrue(InventorySnapshotCodec.encode(issued.plan.nextSnapshot).isNotEmpty())
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(issued.plan) }
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(f.noop()) }
    }

    @Test fun recallRejectsResultAndSourceRatherThanLosingCollateralSafety() {
        val f = PostingFixture(); f.receive(); f.reserve()
        postingRoundTrip(f.noop())
        val recalled = InventoryPlanner.recallLot(f.snapshot, f.snapshot.revision, f.ids.operation(), pool.skuId, f.key.lotId!!)
        assertTrue(InventorySnapshotCodec.encode(recalled.nextSnapshot).isNotEmpty())
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(recalled) }
        f.snapshot = recalled.nextSnapshot
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(f.noop()) }
    }

    @Test fun countShortageRejectsResultAndSourceIncludingHeldShortClaims() {
        val f = PostingFixture(); f.receive(); f.reserve(listOf(f.allocation(10)))
        postingRoundTrip(f.noop())
        val counted = InventoryPlanner.recordCountShortage(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            f.key, f.snapshot.revision, quantity(9))
        assertEquals(StockSafety.COUNT_REVIEW, counted.nextSnapshot.position(f.key)!!.safety)
        assertTrue(InventorySnapshotCodec.encode(counted.nextSnapshot).isNotEmpty())
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(counted) }
        f.snapshot = counted.nextSnapshot
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(f.noop()) }
    }

    @Test fun nonownedAndNonreleasedReceiptLineageRejectsBothCuts() {
        postingRoundTrip(postingSimpleReceipt())
        for (key in listOf(StockPositionKey(pool, id(843), StockCustody.QUARANTINE),
            StockPositionKey(pool.copy(ownership = StockOwnership.SupplierOwned(id(844))), id(845), StockCustody.REJECTED_HELD))) {
            val f = PostingFixture()
            val plan = f.receipts(listOf(StockReceipt(f.ids.next(), key, quantity(2), if (key.pool.ownership == StockOwnership.CompanyOwned) money(20) else null)))
            assertTrue(InventorySnapshotCodec.encode(plan.nextSnapshot).isNotEmpty())
            postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(plan) }
            postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(f.noop()) }
        }
    }

    @Test fun genericCodecValidOpeningReceiptIsExplicitlyOutsidePostingProfile() {
        val f = PostingFixture()
        val received = f.receive()
        postingRoundTrip(received)
        val receipt = (received.effects[0] as InventoryEffect.Valuation).plan.fact as ValuationFact.Receipt
        val opening = MovingAveragePlanner.receive(MovingAverageState.empty(unit, 0), Revision(0), receipt.factId,
            receipt.quantity, receipt.originalValue, ReceiptValueOrigin.OPENING).next
        f.snapshot = postingSnapshotCopy(f.snapshot, pools = listOf(OwnedValuationPool(pool, opening)))
        assertTrue(InventorySnapshotCodec.encode(f.snapshot).isNotEmpty())
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(f.noop()) }
    }

    @Test fun releasedReservationsDoNotDisappearIntoReservationRecorded() {
        val f = PostingFixture(); f.receive()
        val reserved = f.reserve(exposure = CollectionExposure.UNCOLLECTED); postingRoundTrip(reserved.plan)
        val released = InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(), reserved.proof.reservationId,
            f.snapshot.reservation(reserved.proof.reservationId)!!.revision)
        assertTrue(InventorySnapshotCodec.encode(released.nextSnapshot).isNotEmpty())
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(released) }
        f.snapshot = released.nextSnapshot
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(f.noop()) }
    }

    @Test fun allFourUnsupportedEffectVariantsRejectRatherThanBeingFiltered() {
        val f = PostingFixture(); f.receive()
        val reserved = f.reserve(exposure = CollectionExposure.UNCOLLECTED)
        val noop = f.noop(); postingRoundTrip(noop)
        val actualRelease = InventoryPlanner.releaseReservation(f.snapshot, f.snapshot.revision, f.ids.operation(), reserved.proof.reservationId,
            f.snapshot.reservation(reserved.proof.reservationId)!!.revision).effects.single()
        val unsupported = listOf(actualRelease,
            InventoryEffect.CountReconciled(id(846), f.key, f.snapshot.revision, quantity(10)),
            InventoryEffect.KitchenConfirmed(KitchenCancellationConfirmation(id(847), id(848), Revision(1), id(849), listOf(id(850)))),
            InventoryEffect.WasteClassified(id(851), money(1), id(852)))
        unsupported.forEach { effect -> postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) {
            postingCapture(postingForge(noop, effects = listOf(effect)))
        } }
    }

    @Test fun deadlineNullDoesNotLetHeldInventIssueOrSourceEffects() {
        val plan = postingHeldFixture().result.plan; postingRoundTrip(plan, 10)
        val forgedSource = postingSnapshotCopy(plan.nextSnapshot, sources = plan.nextSnapshot.sourceIds + id(853))
        postingFailure(InventoryPostingFailure.UNSUPPORTED_STATE) { postingCapture(postingForge(plan, next = forgedSource), 10) }
        val receipt = postingSimpleReceipt().effects
        postingFailure { postingCapture(postingForge(plan, effects = plan.effects + receipt), 10) }
    }

    @Test fun effectLimitRejectsBeforeTryingToBuildAnOversizedPostedList() {
        val f = PostingFixture(); f.receive()
        val noop = f.noop(); postingRoundTrip(noop)
        val movement = (postingSimpleReceipt().effects[1] as InventoryEffect.Movement)
        val excessive = postingForge(noop, effects = List(InventoryPostingCodec.MAX_EFFECTS + 1) { movement })
        postingFailure(InventoryPostingFailure.TOO_LARGE) { postingCapture(excessive) }
    }
}

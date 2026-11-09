package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.StockRestoration
import com.ukkera.pos.inventory.ValueDestination
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Genuine live planner positives. These pure contributions install no sales-bypassing handlers. */
class InventoryRetailContributionTest {
    private val f = RetailFixtures

    @Test fun explicitGenesisIsExactlyTwoInsertsAndAnEmptyHistoricalResult() {
        val proposal = f.genesis()
        val contribution = proposal.contribution
        val current = f.current(proposal)
        assertEquals(Revision(0), current.snapshot.revision)
        assertEquals(Revision(1), current.storageGeneration)
        assertEquals(2, contribution.changes.size)
        assertTrue(contribution.changes.all { it.expectedRevision == null })
        assertTrue(contribution.claims.isEmpty())
        assertNull(contribution.mustCommitBeforeEpochMillis)
        assertNull(contribution.companion.previous)
        assertNull(contribution.companion.latestBatch)
        assertTrue(contribution.companion.receipts.isEmpty() && contribution.companion.issueSources.isEmpty())
        val result = InventoryRetailFixtureResultCodec.decode(proposal.result)
        assertEquals(InventoryRetailFixtureResultKind.GENESIS, result.kind)
        assertEquals(emptyList(), result.receiptIds)
        assertEquals(current.cutRow.digest, result.cutDigest)
        assertEquals(current.companionRow.digest, result.companionDigest)
        validateRetailFixtureProducer(current, f.retained(proposal, InventoryRetailCommandCodec.genesisRequest()))
    }

    @Test fun oneOperationReceivesManySourcesWithInterleavedPoolsInOriginalRequestOrder() {
        val before = f.current(f.genesis())
        val first = f.request(1)
        val second = f.request(2).copy(skuId = f.id(24), lotId = f.id(25), quantity = Quantity(f.unit, 7, 0), originalValue = Money(Currency.EGP, 49))
        val third = f.request(3).copy(lotId = f.id(23), quantity = Quantity(f.unit, 5, 0), originalValue = Money(Currency.EGP, 55))
        val requests = listOf(first, second, third)
        val proposal = f.receive(before, requests)
        val after = f.current(proposal)
        assertEquals(Revision(1), after.snapshot.revision)
        assertEquals(listOf(f.op(1_001)), after.snapshot.operationIds)
        assertEquals(requests.map { it.receiptId }, after.snapshot.sourceIds)
        assertEquals(requests.map { it.receiptId }, after.companion.receipts.map { it.receiptId })
        assertEquals(1, after.companion.receipts.map { it.producingOperationId }.distinct().size)
        assertEquals(listOf(second.skuId, first.skuId), after.snapshot.pools.map { it.key.skuId })
        assertEquals(listOf(first.receiptId, third.receiptId), after.snapshot.pools.last().value.facts.map { it.factId })
        assertEquals(6, after.companion.latestBatch!!.effects.size)
        assertEquals(6, proposal.contribution.claims.size)
        assertTrue(proposal.contribution.claims.all { it is ClaimChange.Acquire })
        assertTrue(proposal.contribution.changes.all { it.expectedRevision == Revision(1) })
        assertEquals(requests.map { it.receiptId }, InventoryRetailFixtureResultCodec.decode(proposal.result).receiptIds)
        assertEquals(before.cutRow.digest, after.companion.previous!!.cutDigest)
        assertEquals(before.companionRow.digest, after.companion.previous!!.companionDigest)
        validateRetailFixtureProducer(after, f.retained(proposal, InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(requests))))
    }

    @Test fun multiSliceMultiPoolIssueCountsOnceAndPreservesZeroPositionsAndConsumedDestinations() {
        val requests = listOf(f.request(1), f.request(2).copy(skuId = f.id(24), lotId = f.id(25),
            quantity = Quantity(f.unit, 7, 0), originalValue = Money(Currency.EGP, 49)),
            f.request(3).copy(lotId = f.id(23), quantity = Quantity(f.unit, 5, 0), originalValue = Money(Currency.EGP, 55)))
        val received = f.current(f.receive(f.current(f.genesis()), requests))
        val allocations = listOf(f.allocation(1, requests[0].toReceipt(f.scope).position, 6),
            f.allocation(2, requests[1].toReceipt(f.scope).position, 7),
            f.allocation(3, requests[2].toReceipt(f.scope).position, 5),
            f.allocation(4, requests[0].toReceipt(f.scope).position, 4))
        val reserve = f.reserve(received, allocations = allocations, expires = 800)
        val reserveContribution = f.project(received, reserve)
        assertEquals(800, reserveContribution.mustCommitBeforeEpochMillis)
        assertTrue(reserveContribution.claims.isEmpty())
        val reserved = f.current(reserveContribution)
        val reservation = reserved.snapshot.reservations.single()
        val issued = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(reserved.snapshot, reserved.snapshot.revision,
            f.op(8_001), reservation.reservationId, reservation.revision, f.id(9_001), 101))
        val contribution = f.project(reserved, issued.plan, 101)
        assertSame(issued.plan.nextSnapshot, contribution.snapshot)
        assertEquals(issued.plan.mustCommitBeforeEpochMillis, contribution.mustCommitBeforeEpochMillis)
        assertEquals(800, contribution.mustCommitBeforeEpochMillis)
        assertEquals(listOf(ClaimChange.Acquire(InventoryRetailStoreProfile.sourceClaim(f.id(9_001)), f.id(8_001))), contribution.claims)
        val after = f.current(contribution)
        assertEquals(3, after.snapshot.positions.size)
        assertTrue(after.snapshot.positions.all { it.onHand.scaled == 0L })
        assertTrue(after.snapshot.pools.all { it.value.carryingValue.minor == 0L })
        assertEquals(4, after.snapshot.sourceIds.size)
        assertEquals(listOf(InventoryRetailIssueSourceBinding(f.id(9_001), f.op(8_001))), after.companion.issueSources)
        assertEquals(received.companion.receipts, after.companion.receipts)
        val issue = after.snapshot.issues.single()
        assertEquals(listOf(allocations[0], allocations[2], allocations[3], allocations[1]), issue.allocations.map { it.allocation })
        assertEquals(Money(Currency.EGP, 205), issue.originalValue)
        val consumed = after.snapshot.pools.flatMap { it.value.linkedCorrectionBalances }.filter { it.destination is ValueDestination.Consumed }
        assertEquals(4, consumed.size)
        assertTrue(consumed.all { it.value.minor == 0L })
        assertEquals(8, after.companion.latestBatch!!.effects.size)
        assertIs<InventoryPostingEffect.IssueRecorded>(after.companion.latestBatch!!.effects.last())
        assertEquals(ReservationStatus.ISSUED, after.snapshot.reservations.single().status)
    }

    @Test fun sourceFreeReserveRevalidationExposureAndPaidExpiryRetainAllIntermediateFactsAndNoClaims() {
        val received = f.one(f.request().copy(expiresAtEpochMillis = 1_000))
        val reserve = f.reserve(received, expires = 900, exposure = CollectionExposure.UNCOLLECTED)
        var current = f.current(f.project(received, reserve))
        val revalidated = assertIs<ReservationSafetyResult.Safe>(InventoryPlanner.revalidateReservation(current.snapshot, current.snapshot.revision,
            f.op(8_002), current.snapshot.reservations.single().reservationId, current.snapshot.reservations.single().revision, 200))
        val safeContribution = f.project(current, revalidated.plan, 200)
        assertEquals(900, safeContribution.mustCommitBeforeEpochMillis)
        assertTrue(safeContribution.claims.isEmpty())
        current = f.current(safeContribution)
        val exposure = InventoryPlanner.recordCollectionExposure(current.snapshot, current.snapshot.revision, f.op(8_003),
            current.snapshot.reservations.single().reservationId, current.snapshot.reservations.single().revision, CollectionExposure.PAID)
        val exposureContribution = f.project(current, exposure, 201)
        assertNull(exposureContribution.mustCommitBeforeEpochMillis)
        assertTrue(exposureContribution.claims.isEmpty())
        current = f.current(exposureContribution)
        val expiry = InventoryPlanner.expireReservations(current.snapshot, current.snapshot.revision, f.op(8_004), 1_000)
        val expiryContribution = f.project(current, expiry, 1_000)
        current = f.current(expiryContribution)
        val effects = current.companion.latestBatch!!.effects
        assertEquals(3, effects.size)
        assertIs<InventoryPostingEffect.SafetyChanged>(effects[0])
        val first = assertIs<InventoryPostingEffect.ReservationRecorded>(effects[1]).reservation
        val second = assertIs<InventoryPostingEffect.ReservationRecorded>(effects[2]).reservation
        assertEquals(first.revision.next(), second.revision)
        assertEquals(2, second.holds.size)
        assertEquals(ReservationStatus.HELD, current.snapshot.reservations.single().status)
        assertEquals(StockSafety.EXPIRED, current.snapshot.positions.single().safety)
        assertEquals(received.snapshot.sourceIds, current.snapshot.sourceIds)
        assertEquals(received.companion.receipts, current.companion.receipts)
        assertTrue(expiryContribution.claims.isEmpty() && current.companion.issueSources.isEmpty())
        assertNull(expiryContribution.mustCommitBeforeEpochMillis)

        val held = assertIs<ReservationIssueResult.Held>(InventoryPlanner.issueReservation(current.snapshot, current.snapshot.revision,
            f.op(8_005), second.reservationId, second.revision, f.id(9_002), 1_001))
        val heldContribution = f.project(current, held.plan, 1_001)
        val afterHeld = f.current(heldContribution)
        assertTrue(heldContribution.claims.isEmpty() && afterHeld.companion.issueSources.isEmpty())
        assertEquals(received.snapshot.sourceIds, afterHeld.snapshot.sourceIds)
        assertTrue(f.id(9_002) !in afterHeld.snapshot.sourceIds)
    }

    @Test fun aLaterReceiptDoesNotClearAnExpiredPositionOrForgetPriorProvenance() {
        val before = f.one(f.request().copy(expiresAtEpochMillis = 500))
        val expired = f.current(f.project(before, InventoryPlanner.expireReservations(before.snapshot, before.snapshot.revision, f.op(8_006), 500), 500))
        val later = f.request(2, expired.snapshot.revision).copy(expiresAtEpochMillis = 500)
        val after = f.current(f.receive(expired, listOf(later), f.op(1_002)))
        assertEquals(StockSafety.EXPIRED, after.snapshot.positions.single().safety)
        assertEquals(20, after.snapshot.positions.single().onHand.scaled)
        assertEquals(before.companion.receipts.single(), after.companion.receipts.first())
        assertEquals(listOf(before.companion.receipts.single().receiptId, later.receiptId), after.snapshot.sourceIds)
    }

    @Test fun projectionKeepsTheOriginalDeadlineAndRejectsAnElapsedOrNegativeClock() {
        val before = f.one(f.request().copy(expiresAtEpochMillis = 800))
        val plan = f.reserve(before, expires = 900)
        val contribution = f.project(before, plan, 799)
        assertEquals(plan.mustCommitBeforeEpochMillis, contribution.mustCommitBeforeEpochMillis)
        assertEquals(800, contribution.mustCommitBeforeEpochMillis)
        retailFailure { f.project(before, plan, 800) }
        retailFailure { f.project(before, plan, -1) }
        assertTrue(!f.current(contribution).companionRow.payload.copyBytes().decodeToString().contains("mustCommitBefore"))
    }

    @Test fun sourceObjectContextScopeOperationAndLoadedBytesMustBeTheExactObservedPair() {
        val before = f.one()
        val plan = f.reserve(before)
        val copiedSnapshot = ReceiptFixtures.roundTrip(before.snapshot)
        val copiedPair = RetailObservedPair(before.scope, copiedSnapshot, before.companion, before.cutRow, before.companionRow)
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) { f.project(copiedPair, plan) }
        val context = f.planContext(plan)
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) {
            projectRetailContribution(before, context.copy(operationId = f.op(88_001)), plan, f.now)
        }
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) {
            projectRetailContribution(before, context.copy(scope = BranchScope(f.id(88_002), f.scope.companyId, f.scope.branchId)), plan, f.now)
        }
        val badBytes = RetailObservedPair(before.scope, before.snapshot, before.companion,
            before.cutRow.copy(digest = PayloadDigest("f".repeat(64))), before.companionRow)
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) { f.project(badBytes, plan) }
        assertNotEquals(InventorySnapshotCodec.encode(copiedSnapshot), InventorySnapshotCodec.encode(plan.nextSnapshot))
        assertSame(before.snapshot, plan.sourceSnapshot)
    }

    @Test fun everyNewReceiptNeedsFullOrderedInputsIncludingExpiryAndNoOmittedOrExtraRequest() {
        val before = f.current(f.genesis())
        val inputs = listOf(f.request(1), f.request(2).copy(lotId = f.id(23)))
        val plan = InventoryPlanner.receive(before.snapshot, before.snapshot.revision, f.op(1_001), inputs.map { it.toReceipt(f.scope) })
        for (wrong in listOf(emptyList(), inputs.take(1), inputs + f.request(3), inputs.reversed(),
            listOf(inputs[0].copy(expiresAtEpochMillis = inputs[0].expiresAtEpochMillis!! + 1), inputs[1]),
            listOf(inputs[0].copy(expectedRevision = Revision(1)), inputs[1]),
            listOf(inputs[0].copy(lotId = f.id(87_001)), inputs[1]),
            listOf(inputs[0].copy(originalValue = Money(Currency.EGP, 102)), inputs[1]))) {
            retailFailure { f.project(before, plan, receipts = wrong) }
        }
        val correct = f.project(before, plan, receipts = inputs)
        assertEquals(inputs.map { it.expiresAtEpochMillis }, correct.companion.receipts.map { it.expiresAtEpochMillis })
        assertEquals(inputs.map { it.origin }, correct.companion.receipts.map { it.origin })
    }

    @Test fun duplicateOrOldOriginsAreRejectedAndFixtureDigestBindsEveryInputField() {
        val before = f.one()
        val oldOrigin = before.companion.receipts.single().origin
        val input = f.request(2, before.snapshot.revision)
        val changed = input.copy(origin = oldOrigin)
        val plan = InventoryPlanner.receive(before.snapshot, before.snapshot.revision, f.op(1_002), listOf(input.toReceipt(f.scope)))
        retailFailure(InventoryRetailStoreFailure.SOURCE_CONFLICT) { f.project(before, plan, receipts = listOf(changed)) }
        val request = InventoryRetailReceiveBatch(listOf(input))
        val context = f.receiveContext(request, plan.operationId)
        for (bad in listOf(input.copy(origin = input.origin.copy(allocationId = f.id(87_002))),
            input.copy(expiresAtEpochMillis = input.expiresAtEpochMillis!! + 1))) {
            retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) {
                fixtureRetailReceive(before, context, InventoryRetailReceiveBatch(listOf(bad)), plan, f.now)
            }
        }
        val reserve = f.reserve(before)
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) { f.project(before, reserve, receipts = listOf(input)) }
    }

    @Test fun lifetimeMaxReceiptIssueReceiptCancellationIsValidWithoutOverflow() {
        val first = f.request().copy(quantity = Quantity(f.unit, Long.MAX_VALUE, 0), originalValue = Money(Currency.EGP, Long.MAX_VALUE),
            expiresAtEpochMillis = null)
        val received = f.one(first)
        val reserved = f.current(f.project(received, f.reserve(received,
            allocations = listOf(f.allocation(1, received.snapshot.positions.single().key, Long.MAX_VALUE)))))
        val reservation = reserved.snapshot.reservations.single()
        val issuedPlan = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(reserved.snapshot, reserved.snapshot.revision,
            f.op(8_007), reservation.reservationId, reservation.revision, f.id(9_007), f.now)).plan
        val issued = f.current(f.project(reserved, issuedPlan))
        assertEquals(0, issued.snapshot.positions.single().onHand.scaled)
        assertEquals(Money(Currency.EGP, Long.MAX_VALUE), issued.snapshot.issues.single().originalValue)
        val next = first.copy(expectedRevision = issued.snapshot.revision, receiptId = f.id(102),
            origin = f.request(2).origin)
        val after = f.current(f.receive(issued, listOf(next), f.op(1_002)))
        assertEquals(Long.MAX_VALUE, after.snapshot.positions.single().onHand.scaled)
        assertEquals(Long.MAX_VALUE, after.snapshot.pools.single().value.carryingValue.minor)
        assertEquals(2, after.companion.receipts.size)
        assertEquals(1, after.companion.issueSources.size)
        assertEquals(listOf(first.receiptId, f.id(9_007), next.receiptId), after.snapshot.sourceIds)
    }

    @Test fun sourceFreeEmptyExpiryStillAdvancesTheInventoryOperationSpine() {
        val before = f.current(f.genesis())
        val plan = InventoryPlanner.expireReservations(before.snapshot, before.snapshot.revision, f.op(8_008), f.now)
        val contribution = f.project(before, plan)
        val after = f.current(contribution)
        assertEquals(Revision(1), after.snapshot.revision)
        assertEquals(listOf(plan.operationId), after.snapshot.operationIds)
        assertTrue(after.snapshot.sourceIds.isEmpty() && contribution.claims.isEmpty())
        assertNotNull(after.companion.latestBatch)
        assertTrue(after.companion.latestBatch!!.effects.isEmpty())
        assertNull(contribution.mustCommitBeforeEpochMillis)
    }

    @Test fun unsupportedCostHistoryIncludesZeroAndCancelledTargetsEvenWhenAnUnrelatedReceiptWouldBeSmall() {
        val before = f.one()
        val pool = before.snapshot.pools.single()
        val source = before.companion.receipts.single()
        val zero = InventoryPlanner.adjustReceiptCost(before.snapshot, before.snapshot.revision, f.op(8_010), pool.key, pool.value.revision,
            f.id(9_010), f.id(9_011), source.receiptId, Money(Currency.EGP, 0))
        retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_STATE) { f.project(before, zero) }
        val charged = InventoryPlanner.adjustReceiptCost(before.snapshot, before.snapshot.revision, f.op(8_011), pool.key, pool.value.revision,
            f.id(9_012), f.id(9_013), source.receiptId, Money(Currency.EGP, 4)).nextSnapshot
        val cancelled = InventoryPlanner.adjustReceiptCost(charged, charged.revision, f.op(8_012), pool.key, charged.pool(pool.key)!!.value.revision,
            f.id(9_014), f.id(9_013), source.receiptId, Money(Currency.EGP, 0)).nextSnapshot
        for (unsupported in listOf(zero.nextSnapshot, charged, cancelled)) {
            val decoded = ReceiptFixtures.roundTrip(unsupported) // Genuine generic supported cost state, not truncated history.
            assertTrue(decoded.pools.single().value.costTargets.isNotEmpty())
            retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_STATE) { retailGuard { validateRetailCut(decoded, before.companion) } }
            val unrelated = f.request(2, decoded.revision).copy(skuId = f.id(89_001), lotId = f.id(89_002))
            val next = InventoryPlanner.receive(decoded, decoded.revision, f.op(8_013), listOf(unrelated.toReceipt(f.scope))).nextSnapshot
            retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_STATE) { retailGuard { validateRetailCut(next, before.companion) } }
        }
        assertEquals(0, cancelled.pools.single().value.costTargets.single().targetDelta.minor)
    }

    @Test fun releaseRecallAndRetailReturnRemainUnsupportedWholeCutsRatherThanEmptyContributions() {
        val received = f.one()
        val reserve = f.reserve(received, exposure = CollectionExposure.UNCOLLECTED)
        val reserved = f.current(f.project(received, reserve))
        val reservation = reserved.snapshot.reservations.single()
        val released = InventoryPlanner.releaseReservation(reserved.snapshot, reserved.snapshot.revision, f.op(8_020), reservation.reservationId, reservation.revision)
        retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_STATE) { f.project(reserved, released) }
        val recalled = InventoryPlanner.recallLot(received.snapshot, received.snapshot.revision, f.op(8_021),
            received.snapshot.positions.single().key.pool.skuId, received.snapshot.positions.single().key.lotId!!)
        retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_STATE) { f.project(received, recalled) }
        val issued = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(reserved.snapshot, reserved.snapshot.revision,
            f.op(8_022), reservation.reservationId, reservation.revision, f.id(9_022), f.now)).plan
        val afterIssue = f.current(f.project(reserved, issued))
        val allocation = afterIssue.snapshot.issues.single().allocations.single().allocation
        val returned = InventoryPlanner.restoreOriginalRetail(afterIssue.snapshot, afterIssue.snapshot.revision, f.op(8_023), f.id(9_023),
            afterIssue.snapshot.issues.single().issueId, afterIssue.snapshot.issues.single().revision,
            listOf(StockRestoration(allocation.allocationId, allocation.quantity, allocation.position)), f.now)
        retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_STATE) { f.project(afterIssue, returned) }
    }
}

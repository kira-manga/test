package com.ukkera.pos.database

import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.CollectionStatus
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.store.SalesStorageRevision
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real disposable Room commits with synthetic identity/runtime/catalog/custody; never a POS gateway. */
class CheckoutStoreIntegrationTest {
    @Test fun realCashOpenPreservesAssignmentVersionsAndAllFourClaimsAndCannotReinitializeOnReopen() = runTest {
        val j = CheckoutJourney()
        try {
            assertFailsWith<IllegalArgumentException> { j.fixture.store(initializing = true) }
            j.install()
            assertEquals(Revision(0), j.cash().revision)
            assertEquals(Revision(0), j.cash().cashLedgerRevision)
            assertEquals(Revision(1), j.cashRef().drawerAssignmentRevision)
            assertEquals(Revision(1), j.cashRef().custodianAssignmentRevision)
            val open = j.history.last()
            val retained = j.retained(open)
            requireCheckoutAfterFacts(retained)
            assertEquals(3, retained.aggregateWrites.size)
            assertEquals(4, retained.claimChanges.size)
            assertTrue(retained.claimChanges.all { it.action == RetainedClaimAction.ACQUIRE && it.ownerId == j.sessionId })
            assertEquals(setOf(
                CheckoutFixtureProfile.claim(SalesUniqueKind.CASH_SESSION, j.sessionId),
                CheckoutFixtureProfile.claim(SalesUniqueKind.OPENING_FLOAT_SOURCE,
                    assertIs<CheckoutRequest.Open>(CheckoutFixtureCodec.decode(open.command, open.payload)).openingSourceId),
                CheckoutFixtureProfile.claim(SalesUniqueKind.ACTIVE_DRAWER, CHECKOUT_DRAWER),
                CheckoutFixtureProfile.claim(SalesUniqueKind.ACTIVE_CUSTODIAN, TEST_ACTOR),
            ), retained.claimChanges.map { it.key }.toSet())
            val before = j.fixture.databaseState()
            j.reopen()
            assertEquals(before, j.fixture.databaseState())
            assertFailsWith<IllegalArgumentException> { j.fixture.store(initializing = true) }
            checkoutDeferred(j.fixture.store().execute(testPrincipal(), j.fixture.genesisRequest()), DeferredReason.UNKNOWN_COMMAND)
            assertEquals(before, j.fixture.databaseState())
        } finally { j.close() }
    }

    @Test fun genuineStagedSaleCommitsExactDomainStorageRevisionsAndStandaloneInitialEntitlement() = runTest {
        val j = CheckoutJourney()
        try {
            j.install()
            val target = j.create()
            assertEquals(Revision(2), j.inventory().snapshot.revision)
            assertEquals(Revision(3), j.inventory().storageGeneration)
            assertEquals(Revision(1), j.current(target).order.revision)
            assertEquals(Revision(0), j.current(target).collection.revision)
            assertEquals(Revision(0), j.cash().revision)
            val attempt = j.start(target, 100)
            assertEquals(Revision(3), j.inventory().snapshot.revision)
            assertEquals(Revision(1), j.current(target).collection.revision)
            assertEquals(Revision(1), j.cash().revision)
            j.outcome(target, attempt, CheckoutOutcome.CashReceived(checkoutMoney(150), checkoutMoney(50), j.evidence()))
            assertEquals(Revision(3), j.inventory().snapshot.revision)
            assertEquals(Revision(2), j.current(target).collection.revision)
            assertEquals(Revision(2), j.cash().revision)
            assertEquals(Revision(1), j.cash().cashLedgerRevision)
            val cashBefore = SalesSnapshotCodec.encodeCashSession(j.cash())
            val beforeReopen = j.fixture.databaseState()
            j.reopen()
            assertEquals(beforeReopen, j.fixture.databaseState())
            val request = j.finalRequest(target)
            val result = assertIs<CheckoutResult.Issued>(CheckoutFixtureCodec.decodeResult(request.command, j.execute(request).result))
            val retained = j.retained(request)
            assertFinalShape(retained, issued = true)
            assertEquals(Revision(4), j.inventory().snapshot.revision)
            assertEquals(Revision(2), j.current(target).order.revision)
            assertEquals(Revision(3), j.current(target).collection.revision)
            assertEquals(9L, j.inventory().snapshot.positions.single().onHand.scaled)
            assertEquals(180L, j.inventory().snapshot.pools.single().value.carryingValue.minor)
            assertEquals(cashBefore, SalesSnapshotCodec.encodeCashSession(j.cash()))
            val sale = j.fixture.sale(result.saleId)
            val entitlement = j.fixture.entitlement(result.saleId)
            assertEquals(SalesSnapshotCodec.encodePaidSale(sale), SalesSnapshotCodec.encodePaidSale(entitlement.sale))
            assertEquals(Revision(0), entitlement.revision)
            assertTrue(entitlement.cursors.all { it.consumedQuantity.scaled == 0L })
            assertEquals(sale.originalTenderAllocations.map { it.componentKey to it.shares },
                entitlement.remainingComponentTenders.map { it.componentKey to it.shares })
            assertEquals(j.current(target).collection.attempts.mapNotNull { it.payment }, sale.payments)
            val rows = j.fixture.databaseState()
            for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent"))
                assertEquals(8, rows.getValue(table).size, table)
            assertEquals(15, rows.getValue("unique_claim").size)
            j.validateBusinessHistory()
            j.reopen()
            assertEquals(rows, j.fixture.databaseState())
            assertEquals(result.saleId, j.current(target).order.paidSaleId)
            assertEquals(ReservationStatus.ISSUED, j.inventory().snapshot.reservationFor(target).status)
        } finally { j.close() }
    }

    @Test fun coveredUnknownExposureIssuesWithoutCashReposting() = runTest { exerciseExposure(CollectionExposure.UNKNOWN, false) }
    @Test fun coveredUnknownExposureHoldsWithoutChangingMoney() = runTest { exerciseExposure(CollectionExposure.UNKNOWN, true) }
    @Test fun genuinePartialExposureThenRemainingPaymentIssuesAfterReopen() = runTest { exerciseExposure(CollectionExposure.PART_PAID, false) }
    @Test fun genuinePartialExposureThenRemainingPaymentHoldsAfterReopen() = runTest { exerciseExposure(CollectionExposure.PART_PAID, true) }
    @Test fun evidenceDerivedPaidExposureIssuesAfterReopen() = runTest { exerciseExposure(CollectionExposure.PAID, false) }
    @Test fun evidenceDerivedPaidExposureHoldsAfterReopen() = runTest { exerciseExposure(CollectionExposure.PAID, true) }

    @Test fun zeroPayableIssuesRealStockWithoutInventedAttemptOrMoney() = runTest { exerciseZero(false) }
    @Test fun zeroPayableHoldsRealReservationWithoutInventedAttemptOrMoney() = runTest { exerciseZero(true) }
    @Test fun ordinaryFinalizeGrantCannotAuthorizeZeroIssuedPath() = runTest { rejectOrdinaryZeroGrant(false) }
    @Test fun ordinaryFinalizeGrantCannotAuthorizeZeroHeldPath() = runTest { rejectOrdinaryZeroGrant(true) }

    @Test fun cashFortyExternalSixtyConservesLiteralComponentMatrixIncludingZeroTaxRows() = runTest { exerciseMatrix(40, false) }
    @Test fun equalTenderTieUsesStableAttemptIdsEvenWhenExternalArrivesFirst() = runTest { exerciseMatrix(50, true) }

    @Test fun repeatedUnknownObservationsRemainInOriginalJournalsAfterActualResolution() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); val attempt = j.start(target, 100)
            val cashBefore = SalesSnapshotCodec.encodeCashSession(j.cash())
            val evidenceA = j.evidence()
            j.outcome(target, attempt, CheckoutOutcome.Unknown(evidenceA))
            val first = j.history.last()
            val evidenceB = j.evidence()
            j.outcome(target, attempt, CheckoutOutcome.Unknown(evidenceB))
            val second = j.history.last()
            assertEquals(cashBefore, SalesSnapshotCodec.encodeCashSession(j.cash()))
            for (request in listOf(first, second)) {
                val retained = j.retained(request)
                assertEquals(1, retained.aggregateWrites.size)
                assertTrue(retained.claimChanges.isEmpty())
                requireCheckoutAfterFacts(retained)
            }
            j.outcome(target, attempt, CheckoutOutcome.CashReceived(checkoutMoney(100), checkoutMoney(0), j.evidence()))
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
            assertEquals(Revision(1), j.cash().cashLedgerRevision)
            j.reopen()
            for ((request, expected) in listOf(first to evidenceA, second to evidenceB)) {
                val retained = j.retained(request)
                requireCheckoutAfterFacts(retained)
                val row = retained.aggregateWrites.single()
                val historical = SalesSnapshotCodec.decodeHistoricalCollection(row.payload.copyBytes().decodeToString(),
                    SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, TEST_SCOPE, target.collectionId,
                        SalesStorageRevision.toDomain(row.storageGeneration)))
                val old = historical.attempts.single()
                assertEquals(AttemptStatus.OUTCOME_UNKNOWN, old.status)
                assertNull(old.payment)
                assertEquals(expected.evidenceId, assertIs<CollectionOutcome.Unknown>(old.latestObservation).evidence.evidenceId)
            }
            assertEquals(CollectionStatus.COVERED, j.current(target).collection.status)
            j.execute(j.finalRequest(target)); j.validateBusinessHistory()
        } finally { j.close() }
    }

    @Test fun unknownDerivationAndNotPaidPreservePriorPartialMoneyAndConservativeExposure() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payCash(target, 40, derive = true)
            val attempt = j.start(target, 60)
            val cashBefore = SalesSnapshotCodec.encodeCashSession(j.cash())
            j.outcome(target, attempt, CheckoutOutcome.Unknown(j.evidence()), derive = true)
            assertEquals(3, j.retained(j.history.last()).aggregateWrites.size)
            assertEquals(cashBefore, SalesSnapshotCodec.encodeCashSession(j.cash()))
            assertEquals(CollectionExposure.UNKNOWN, j.inventory().snapshot.reservationFor(target).binding.exposure)
            j.outcome(target, attempt, CheckoutOutcome.NotPaid(j.evidence(), "Synthetic terminal reported no payment"), derive = true)
            assertEquals(4, j.retained(j.history.last()).aggregateWrites.size)
            assertTrue(j.retained(j.history.last()).claimChanges.isEmpty())
            assertEquals(CollectionExposure.PART_PAID, j.inventory().snapshot.reservationFor(target).binding.exposure)
            assertEquals(checkoutMoney(540), j.cash().expectedCash)
            assertEquals(Revision(1), j.cash().cashLedgerRevision)
            assertTrue(j.cash().pendingAttemptIds.isEmpty())
            j.payCash(target, 60)
            assertEquals(CollectionStatus.COVERED, j.current(target).collection.status)
            assertEquals(CollectionExposure.PART_PAID, j.inventory().snapshot.reservationFor(target).binding.exposure)
            j.validateBusinessHistory()
        } finally { j.close() }
    }

    @Test fun firstNotPaidDerivedExposureIsUnknownNeverUncollectedOrAReleasedReservation() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); val attempt = j.start(target, 100)
            j.outcome(target, attempt, CheckoutOutcome.NotPaid(j.evidence(), "Synthetic no payment"), derive = true)
            assertEquals(CollectionStatus.LOCKED_UNPAID, j.current(target).collection.status)
            val reservation = j.inventory().snapshot.reservationFor(target)
            assertEquals(CollectionExposure.UNKNOWN, reservation.binding.exposure)
            assertEquals(ReservationStatus.RESERVED, reservation.status)
            assertEquals(checkoutMoney(500), j.cash().expectedCash)
            assertEquals(Revision(0), j.cash().cashLedgerRevision)
            assertTrue(j.retained(j.history.last()).claimChanges.isEmpty())
            j.validateBusinessHistory()
        } finally { j.close() }
    }

    @Test fun actualLateCashAttestationAfterDurableAttemptIsRecordedBeforeHeldFinalization() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(lotExpiry = j.nowMillis + 60_000)
            val attempt = j.start(target, 100)
            j.at(assertNotNull(target.reservationExpiry))
            j.outcome(target, attempt, CheckoutOutcome.CashReceived(checkoutMoney(100), checkoutMoney(0), j.evidence()), derive = true)
            assertEquals(CollectionStatus.COVERED, j.current(target).collection.status)
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
            assertEquals(4, j.retained(j.history.last()).aggregateWrites.size)
            val request = j.finalRequest(target)
            assertIs<CheckoutResult.Held>(CheckoutFixtureCodec.decodeResult(request.command, j.execute(request).result))
            assertFinalShape(j.retained(request), issued = false)
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
            j.validateBusinessHistory()
        } finally { j.close() }
    }

    @Test fun externalAttestationChangesExternalTotalButNeverCashOrCashLedger() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payExternal(target)
            val cash = j.cash()
            assertEquals(checkoutMoney(500), cash.expectedCash)
            assertEquals(checkoutMoney(100), cash.totals.externalCollections)
            assertEquals(Revision(0), cash.cashLedgerRevision)
            val outcome = j.retained(j.history.last())
            assertEquals(2, outcome.aggregateWrites.size)
            assertEquals(1, outcome.claimChanges.size)
            assertTrue(outcome.claimChanges.none { it.key.namespace == "sales.cash.source" })
            val before = SalesSnapshotCodec.encodeCashSession(cash)
            j.execute(j.finalRequest(target))
            assertEquals(before, SalesSnapshotCodec.encodeCashSession(j.cash()))
            j.validateBusinessHistory()
        } finally { j.close() }
    }

    @Test fun missingRequiredExternalEvidenceDoesNotResolveIntentOrInventNotPaid() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); val attempt = j.start(target, 100, j.externalTender())
            val before = j.fixture.databaseState().checkoutBusinessRows()
            val request = j.request(CheckoutRequest.Outcome(j.salesRef(target), attempt,
                CheckoutOutcome.ExternalAttested(j.evidence(), null, null), CheckoutExposureUpdate.Preserve))
            val reply = assertIs<MutationReply.Recorded>(j.fixture.store().execute(testPrincipal(), request))
            assertFalse(reply.committed)
            assertEquals("fixture.checkout.sales.reference-required", reply.code)
            assertEquals(before, j.fixture.databaseState().checkoutBusinessRows())
            assertEquals(AttemptStatus.AWAITING_ATTESTATION, j.current(target).collection.attempts.single().status)
            assertEquals(listOf(attempt), j.cash().pendingAttemptIds)
        } finally { j.close() }
    }

    @Test fun coveredTargetIssuesWhileAnotherCollectionIsPendingAndCollateralLotExpires() = runTest { exerciseOtherPending(false) }
    @Test fun coveredTargetHoldsWhileAnotherCollectionIsPendingAndCollateralLotExpires() = runTest { exerciseOtherPending(true) }

    @Test fun laterReceiveAcceptsCompleteCheckoutProducerAndUnrelatedJournalTailIsNotInventoryHead() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payCash(target); val request = j.finalRequest(target)
            j.execute(request)
            val selected = j.inventory().retained.operationId
            assertEquals(request.operationId, selected)
            val unrelated = j.fixture.base.request(419_991, CounterInput(id = testId(419_992)))
            checkoutCommitted(j.fixture.base.store().execute(testPrincipal(), unrelated))
            assertEquals(selected, j.inventory().retained.operationId)
            j.reopen()
            assertEquals(selected, j.inventory().retained.operationId)
            j.receive(target.lines, quantity = 2)
            assertEquals(11L, j.inventory().snapshot.positions.single().onHand.scaled)
            assertEquals(1, j.inventory().snapshot.issues.size)
            j.validateBusinessHistory()
        } finally { j.close() }
    }
}

private suspend fun exerciseExposure(exposure: CollectionExposure, held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create()
        when (exposure) {
            CollectionExposure.UNKNOWN -> j.payCash(target)
            CollectionExposure.PART_PAID -> {
                j.payCash(target, 40, received = 50, change = 10, derive = true)
                assertEquals(CollectionStatus.PART_PAID, j.current(target).collection.status)
                assertEquals(CollectionExposure.PART_PAID, j.inventory().snapshot.reservationFor(target).binding.exposure)
                j.payCash(target, 60)
            }
            CollectionExposure.PAID -> j.payCash(target, derive = true)
            CollectionExposure.UNCOLLECTED -> error("Not a positive fixture mode")
        }
        val before = j.current(target)
        assertEquals(CollectionStatus.COVERED, before.collection.status)
        assertEquals(exposure, j.inventory().snapshot.reservationFor(target).binding.exposure)
        val cash = SalesSnapshotCodec.encodeCashSession(before.cash)
        val order = SalesSnapshotCodec.encodeOrder(before.order)
        val collection = SalesSnapshotCodec.encodeCollection(before.collection)
        j.reopen()
        if (held) j.at(assertNotNull(target.reservationExpiry))
        val request = j.finalRequest(target)
        val reply = j.execute(request)
        val result = CheckoutFixtureCodec.decodeResult(request.command, reply.result)
        assertFinalShape(j.retained(request), !held)
        assertEquals(cash, SalesSnapshotCodec.encodeCashSession(j.cash()))
        assertEquals(exposure, j.inventory().snapshot.reservationFor(target).binding.exposure)
        if (held) {
            assertIs<CheckoutResult.Held>(result)
            assertEquals(order, SalesSnapshotCodec.encodeOrder(j.current(target).order))
            assertEquals(collection, SalesSnapshotCodec.encodeCollection(j.current(target).collection))
            assertEquals(ReservationStatus.HELD, j.inventory().snapshot.reservationFor(target).status)
            assertTrue(j.inventory().snapshot.issues.isEmpty())
        } else {
            val sale = j.fixture.sale(assertIs<CheckoutResult.Issued>(result).saleId)
            assertEquals(before.collection.attempts.mapNotNull { it.payment }, sale.payments)
            assertEquals(SalesSnapshotCodec.encodePaidSale(sale),
                SalesSnapshotCodec.encodePaidSale(j.fixture.entitlement(sale.id).sale))
        }
        j.validateBusinessHistory()
        val all = j.fixture.databaseState()
        j.reopen()
        val replay = j.execute(request)
        assertTrue(replay.replayed)
        assertEquals(reply.result, replay.result)
        assertEquals(all, j.fixture.databaseState())
    } finally { j.close() }
}

private suspend fun exerciseZero(held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(CheckoutBasket.ZERO)
        assertEquals(CollectionStatus.COVERED, j.current(target).collection.status)
        assertTrue(j.current(target).collection.attempts.isEmpty())
        val cash = SalesSnapshotCodec.encodeCashSession(j.cash())
        j.reopen()
        if (held) j.at(assertNotNull(target.reservationExpiry))
        val request = j.finalRequest(target)
        val result = CheckoutFixtureCodec.decodeResult(request.command, j.execute(request).result)
        assertFinalShape(j.retained(request), !held)
        if (held) assertIs<CheckoutResult.Held>(result)
        else {
            val sale = j.fixture.sale(assertIs<CheckoutResult.Issued>(result).saleId)
            assertEquals(checkoutMoney(0), sale.quote.payable)
            assertTrue(sale.payments.isEmpty())
            assertTrue(sale.originalTenderAllocations.all { it.shares.isEmpty() })
            assertEquals(9L, j.inventory().snapshot.positions.single().onHand.scaled)
            assertTrue(j.fixture.entitlement(sale.id).cursors.all { it.consumedQuantity.scaled == 0L })
        }
        assertEquals(cash, SalesSnapshotCodec.encodeCashSession(j.cash()))
        assertTrue(j.current(target).collection.attempts.isEmpty())
        j.validateBusinessHistory()
    } finally { j.close() }
}

private suspend fun rejectOrdinaryZeroGrant(held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(CheckoutBasket.ZERO)
        j.fixture.setGrants(TEST_ACTOR, setOf(SecurityAction.COLLECTION_FINALIZE, SecurityAction.OPERATION_RESULT_RECOVERY), OwnerAccess.OWN_ONLY)
        if (held) j.at(assertNotNull(target.reservationExpiry))
        val request = j.finalRequest(target)
        val before = j.fixture.databaseState()
        checkoutDeferred(j.fixture.store().execute(testPrincipal(), request), DeferredReason.AUTHORIZATION_DENIED)
        assertEquals(before, j.fixture.databaseState())
        assertEquals(0, j.fixture.probes.getValue(CheckoutFixtureProfile.FINALIZE).inventoryPlans)
    } finally { j.close() }
}

private suspend fun exerciseMatrix(cashAmount: Long, externalFirst: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(CheckoutBasket.SPLIT_33_33_34)
        val cashAttempt = j.id()
        val externalAttempt = j.id()
        assertTrue(cashAttempt.value < externalAttempt.value)
        if (externalFirst) {
            j.payExternal(target, 100 - cashAmount, attemptId = externalAttempt)
            j.payCash(target, cashAmount, attemptId = cashAttempt)
        } else {
            j.payCash(target, cashAmount, received = cashAmount + 10, change = 10, attemptId = cashAttempt)
            j.payExternal(target, 100 - cashAmount, attemptId = externalAttempt)
        }
        val originalPayments = j.current(target).collection.attempts.mapNotNull { it.payment }
        val cash = SalesSnapshotCodec.encodeCashSession(j.cash())
        j.reopen()
        val request = j.finalRequest(target)
        val result = assertIs<CheckoutResult.Issued>(CheckoutFixtureCodec.decodeResult(request.command, j.execute(request).result))
        val sale = j.fixture.sale(result.saleId)
        val matrix = sale.originalTenderAllocations.sortedBy { it.componentKey }
        val keys = target.lines.sortedBy { it.portionId.value }.flatMap { listOf("p/${it.portionId.value}/item/net", "p/${it.portionId.value}/item/tax") }
        assertEquals(keys, matrix.map { it.componentKey })
        val expected = if (cashAmount == 40L) listOf(13L to 20L, 0L to 0L, 13L to 20L, 0L to 0L, 14L to 20L, 0L to 0L)
            else listOf(17L to 16L, 0L to 0L, 16L to 17L, 0L to 0L, 17L to 17L, 0L to 0L)
        assertEquals(expected, matrix.map { row ->
            assertEquals(setOf(cashAttempt, externalAttempt), row.shares.map { it.paymentId }.toSet())
            row.shares.single { it.paymentId == cashAttempt }.amount.minor to row.shares.single { it.paymentId == externalAttempt }.amount.minor
        })
        assertEquals(originalPayments, sale.payments)
        assertEquals(checkoutMoney(500 + cashAmount), j.cash().expectedCash)
        assertEquals(checkoutMoney(100 - cashAmount), j.cash().totals.externalCollections)
        assertEquals(cash, SalesSnapshotCodec.encodeCashSession(j.cash()))
        j.validateBusinessHistory()
    } finally { j.close() }
}

private suspend fun exerciseOtherPending(held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(); j.payCash(target)
        val other = j.create(reservationExpiry = j.nowMillis + 120_000, lotExpiry = j.nowMillis + 10)
        val pending = j.start(other, 100)
        assertEquals(CheckoutFixtureProfile.ATTEMPT, j.inventory().retained.command)
        val originalOther = SalesSnapshotCodec.encodeCollection(j.current(other).collection)
        val cash = SalesSnapshotCodec.encodeCashSession(j.cash())
        j.reopen()
        j.at(if (held) assertNotNull(target.reservationExpiry) else j.nowMillis + 10)
        val request = j.finalRequest(target)
        val result = CheckoutFixtureCodec.decodeResult(request.command, j.execute(request).result)
        if (held) assertIs<CheckoutResult.Held>(result) else assertIs<CheckoutResult.Issued>(result)
        assertEquals(cash, SalesSnapshotCodec.encodeCashSession(j.cash()))
        assertEquals(originalOther, SalesSnapshotCodec.encodeCollection(j.current(other).collection))
        assertEquals(listOf(pending), j.cash().pendingAttemptIds)
        val inventory = j.inventory()
        assertEquals(ReservationStatus.HELD, inventory.snapshot.reservationFor(other).status)
        assertEquals(StockSafety.EXPIRED, inventory.snapshot.positions.single { it.key.lotId == other.lines.single().lotId }.safety)
        assertTrue(assertNotNull(inventory.companion.latestBatch).effects.any { it is InventoryPostingEffect.SafetyChanged })
        val footprint = j.fixture.probes.getValue(CheckoutFixtureProfile.FINALIZE).lastFootprint
        for (id in listOf(other.reservationId, other.collectionId, other.lines.single().skuId,
            assertNotNull(other.lines.single().lotId), other.lines.single().allocationId))
            assertTrue(RecoveryResourceKey(RecoveryResourceKind.STOCK, id) in footprint, id.value)
        assertTrue(RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, j.sessionId) in footprint)
        assertFinalShape(j.retained(request), !held)
        j.validateBusinessHistory()
    } finally { j.close() }
}

internal fun assertFinalShape(retained: RetainedCommandEvidence, issued: Boolean) {
    requireCheckoutAfterFacts(retained)
    assertEquals(if (issued) 6 else 2, retained.aggregateWrites.size)
    assertEquals(if (issued) 4 else 0, retained.claimChanges.size)
    assertTrue(retained.jobs.isEmpty())
    assertEquals(2, retained.aggregateWrites.count { it.key.kind in setOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND) })
    assertTrue(retained.aggregateWrites.none { it.key.kind == CheckoutFixtureProfile.SESSION_KIND })
    assertTrue(retained.claimChanges.none { it.key.namespace == "sales.cash.source" })
}

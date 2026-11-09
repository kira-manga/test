package com.ukkera.pos.database

import androidx.room3.useWriterConnection
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.types.Revision
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual disposable SQLite commits; IO probes model error reporting, not physical power loss. */
class CheckoutAtomicityIntegrationTest {
    @Test fun beginRollsBackOrRecoversTheWholeReservationAndCollectionAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.BEGIN)
    }
    @Test fun attemptRollsBackOrRecoversTheWholeRevalidationAndMoneyIntentAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.ATTEMPT)
    }
    @Test fun preservedCashOutcomeNeverLosesOrDuplicatesMoneyAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.CASH_PRESERVE)
    }
    @Test fun derivedCashOutcomeNeverLosesMoneyOrCommitsOnlyItsInventoryAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.CASH_DERIVE)
    }
    @Test fun preservedUnknownObservationIsAtomicAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.UNKNOWN_PRESERVE)
    }
    @Test fun derivedUnknownObservationAndInventoryAreAtomicAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.UNKNOWN_DERIVE)
    }
    @Test fun heldFinalizationRollsBackOrRecoversBothRowsAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.HELD)
    }
    @Test fun issuedFinalizationRollsBackOrRecoversAllSixRowsAtEverySemanticCut() = runTest {
        checkoutSemanticFaults(CheckoutStage.ISSUED)
    }

    @Test fun heldActualCommitErrorBeforeSqliteCommitLeavesNoFinalization() = runTest {
        checkoutCommitFailure(CheckoutStage.HELD, CommitFailureCut.BEFORE_SQLITE_COMMIT)
    }
    @Test fun heldActualCommitErrorAfterSqliteCommitRecoversOriginalHeld() = runTest {
        checkoutCommitFailure(CheckoutStage.HELD, CommitFailureCut.AFTER_SQLITE_COMMIT)
    }
    @Test fun issuedActualCommitErrorBeforeSqliteCommitLeavesNoPartialSale() = runTest {
        checkoutCommitFailure(CheckoutStage.ISSUED, CommitFailureCut.BEFORE_SQLITE_COMMIT)
    }
    @Test fun issuedActualCommitErrorAfterSqliteCommitRecoversOriginalSaleWithoutCashReposting() = runTest {
        checkoutCommitFailure(CheckoutStage.ISSUED, CommitFailureCut.AFTER_SQLITE_COMMIT)
    }
    @Test fun cashActualCommitErrorBeforeSqliteCommitLeavesOriginalUnresolvedIntent() = runTest {
        checkoutCommitFailure(CheckoutStage.CASH_DERIVE, CommitFailureCut.BEFORE_SQLITE_COMMIT)
    }
    @Test fun cashActualCommitErrorAfterSqliteCommitRecoversOneReceiptAndExposure() = runTest {
        checkoutCommitFailure(CheckoutStage.CASH_DERIVE, CommitFailureCut.AFTER_SQLITE_COMMIT)
    }

    @Test fun concurrentIdenticalIssuedOperationsCommitOnceAndChangedBodyConflicts() = runTest {
        checkoutConcurrentIdentity(CheckoutStage.ISSUED)
    }
    @Test fun concurrentIdenticalHeldOperationsCommitOnceAndChangedBodyConflicts() = runTest {
        checkoutConcurrentIdentity(CheckoutStage.HELD)
    }
    @Test fun concurrentIdenticalCashOutcomesCommitOneReceiptAndChangedBodyConflicts() = runTest {
        checkoutConcurrentIdentity(CheckoutStage.CASH_DERIVE)
    }

    @Test fun twoFinalizationsAtTheSameExpectedRevisionCannotPostTwoSales() = runTest {
        val j = CheckoutJourney()
        try {
            val (first, _) = prepareCheckoutStage(j, CheckoutStage.ISSUED)
            val original = assertIs<CheckoutRequest.Finalize>(CheckoutFixtureCodec.decode(first.command, first.payload))
            val second = j.request(original.copy(issueId = j.id(), saleId = j.id()))
            val store = j.fixture.store()
            val actor = testPrincipal()
            val replies = coroutineScope { listOf(first, second).map { request ->
                async(Dispatchers.Default) { store.execute(actor, request) }
            }.awaitAll() }.map { assertIs<MutationReply.Recorded>(it) }
            assertEquals(1, replies.count { it.committed })
            assertEquals("fixture.checkout.sales.revision-conflict", replies.single { !it.committed }.code)
            assertEquals(1, j.inventory().snapshot.issues.size)
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
            val facts = j.fixture.databaseState()
            for (request in listOf(first, second)) assertTrue(assertIs<MutationReply.Recorded>(store.execute(actor, request)).replayed)
            assertEquals(facts, j.fixture.databaseState())
        } finally { j.close() }
    }

    @Test fun heldCancellationReplaysCommittedOutcomeOrReplansOnlyAfterRollback() = runTest {
        checkoutCancellation(CheckoutStage.HELD)
    }
    @Test fun issuedCancellationReplaysCommittedOutcomeOrReplansOnlyAfterRollback() = runTest {
        checkoutCancellation(CheckoutStage.ISSUED)
    }

    @Test fun finalPlanningOneMillisecondBeforeOriginalExpiryCanIssue() = runTest { checkoutPlanningDeadline(-1, false) }
    @Test fun finalPlanningAtOriginalExpiryHoldsRatherThanIssuing() = runTest { checkoutPlanningDeadline(0, true) }
    @Test fun finalPlanningAfterOriginalExpiryHoldsRatherThanRenewingLease() = runTest { checkoutPlanningDeadline(1, true) }
    @Test fun finalCommitDecisionBeforeOriginalExpiryCanCommit() = runTest { checkoutCommitDeadline(-1) }
    @Test fun finalCommitDecisionAtOriginalExpiryRollsBackAllSixRows() = runTest { checkoutCommitDeadline(0) }
    @Test fun finalCommitDecisionAfterOriginalExpiryRollsBackAllSixRows() = runTest { checkoutCommitDeadline(1) }
}

private enum class CheckoutStage { BEGIN, ATTEMPT, CASH_PRESERVE, CASH_DERIVE, UNKNOWN_PRESERVE, UNKNOWN_DERIVE, HELD, ISSUED }

private suspend fun prepareCheckoutStage(j: CheckoutJourney, stage: CheckoutStage): Pair<MutationRequest, CheckoutTarget> {
    j.install()
    val target = j.create(begin = stage != CheckoutStage.BEGIN)
    val request = when (stage) {
        CheckoutStage.BEGIN -> {
            val order = j.fixture.withView { checkoutOrder(it, target.orderId) }
            j.request(CheckoutRequest.Begin(CheckoutRef(order.id, order.revision), j.cashRef(), target.collectionId,
                j.inventory().snapshot.revision, target.reservationId, target.reservationExpiry))
        }
        CheckoutStage.ATTEMPT -> j.request(CheckoutRequest.Attempt(j.salesRef(target), j.stockRef(target), j.id(),
            CheckoutTender.Cash, checkoutMoney(100), "atomicity synthetic payer"))
        CheckoutStage.CASH_PRESERVE, CheckoutStage.CASH_DERIVE, CheckoutStage.UNKNOWN_PRESERVE, CheckoutStage.UNKNOWN_DERIVE -> {
            val attempt = j.start(target, 100)
            val outcome = if (stage == CheckoutStage.UNKNOWN_PRESERVE || stage == CheckoutStage.UNKNOWN_DERIVE)
                CheckoutOutcome.Unknown(j.evidence())
            else CheckoutOutcome.CashReceived(checkoutMoney(150), checkoutMoney(50), j.evidence())
            val exposure = if (stage == CheckoutStage.CASH_DERIVE || stage == CheckoutStage.UNKNOWN_DERIVE)
                CheckoutExposureUpdate.Derive(j.stockRef(target)) else CheckoutExposureUpdate.Preserve
            j.request(CheckoutRequest.Outcome(j.salesRef(target), attempt, outcome, exposure))
        }
        CheckoutStage.HELD, CheckoutStage.ISSUED -> {
            j.payCash(target)
            if (stage == CheckoutStage.HELD) j.at(assertNotNull(target.reservationExpiry))
            j.finalRequest(target)
        }
    }
    return request to target
}

private suspend fun checkoutSemanticFaults(stage: CheckoutStage) {
    for (point in StoreFaultPoint.entries) {
        val j = CheckoutJourney()
        try {
            val (request, _) = prepareCheckoutStage(j, stage)
            val before = j.fixture.databaseState()
            var reached = 0
            checkoutDeferred(j.fixture.store(fault = StoreFaultProbe {
                if (it == point) { reached++; throw IOException("Synthetic checkout commit report") }
            }).execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
            assertEquals(1, reached, "$stage/$point")
            val after = j.fixture.databaseState()
            val committed = point == StoreFaultPoint.AFTER_COMMIT
            if (!committed) assertEquals(before, after, "$stage/$point") else assertNotEquals(before, after)
            j.reopen()
            assertEquals(after, j.fixture.databaseState())
            val reply = j.execute(request)
            assertEquals(committed, reply.replayed)
            assertEquals(if (committed) 0 else 1, j.fixture.probes.getValue(request.command).prepares)
            assertCheckoutStage(j, stage, request)
            val once = j.fixture.databaseState()
            repeat(2) { assertTrue(j.execute(request).replayed) }
            j.reopen()
            assertTrue(j.execute(request).replayed)
            assertEquals(0, j.fixture.probes.getValue(request.command).prepares)
            assertEquals(once, j.fixture.databaseState())
        } finally { j.close() }
    }
}

private suspend fun checkoutCommitFailure(stage: CheckoutStage, cut: CommitFailureCut) {
    val j = CheckoutJourney()
    try {
        val (request, _) = prepareCheckoutStage(j, stage)
        val before = j.fixture.databaseState()
        val path = j.fixture.path
        val clock = j.fixture.base.clockValue
        j.close()
        val driver = CommitFaultDriver(cut)
        j.fixture = CheckoutFixture(path, driver)
        j.fixture.base.clockValue = clock
        j.fixture.database.useWriterConnection { requireDurableWriter(it) }
        checkoutDeferred(j.fixture.store(fault = StoreFaultProbe {
            if (it == StoreFaultPoint.BEFORE_COMMIT) driver.arm()
        }).execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
        driver.assertInjectedAtExpectedBoundary()
        j.fixture.database.useWriterConnection {
            assertFalse(it.inTransaction(), "Closing the database must not rescue a leaked transaction")
            requireDurableWriter(it)
        }
        val after = j.fixture.databaseState()
        val committed = cut == CommitFailureCut.AFTER_SQLITE_COMMIT
        if (!committed) assertEquals(before, after) else assertNotEquals(before, after)
        j.reopen()
        assertEquals(after, j.fixture.databaseState())
        val reply = j.execute(request)
        assertEquals(committed, reply.replayed)
        assertEquals(if (committed) 0 else 1, j.fixture.probes.getValue(request.command).prepares)
        assertCheckoutStage(j, stage, request)
        val once = j.fixture.databaseState()
        j.reopen()
        assertTrue(j.execute(request).replayed)
        assertEquals(0, j.fixture.probes.getValue(request.command).prepares)
        assertEquals(once, j.fixture.databaseState())
    } finally { j.close() }
}

private suspend fun assertCheckoutStage(j: CheckoutJourney, stage: CheckoutStage, request: MutationRequest) {
    val retained = j.retained(request)
    requireCheckoutAfterFacts(retained)
    assertTrue(retained.jobs.isEmpty())
    val expected = when (stage) {
        CheckoutStage.BEGIN, CheckoutStage.ATTEMPT -> 4 to 1
        CheckoutStage.CASH_PRESERVE -> 2 to 2
        CheckoutStage.CASH_DERIVE -> 4 to 2
        CheckoutStage.UNKNOWN_PRESERVE -> 1 to 0
        CheckoutStage.UNKNOWN_DERIVE -> 3 to 0
        CheckoutStage.HELD -> 2 to 0
        CheckoutStage.ISSUED -> 6 to 4
    }
    assertEquals(expected.first, retained.aggregateWrites.size)
    assertEquals(expected.second, retained.claimChanges.size)
    val paid = stage in setOf(CheckoutStage.CASH_PRESERVE, CheckoutStage.CASH_DERIVE, CheckoutStage.HELD, CheckoutStage.ISSUED)
    assertEquals(checkoutMoney(if (paid) 600 else 500), j.cash().expectedCash)
    assertEquals(Revision(if (paid) 1 else 0), j.cash().cashLedgerRevision)
    if (stage == CheckoutStage.HELD || stage == CheckoutStage.ISSUED) assertFinalShape(retained, stage == CheckoutStage.ISSUED)
}

private suspend fun checkoutConcurrentIdentity(stage: CheckoutStage) {
    val j = CheckoutJourney()
    try {
        val (request, _) = prepareCheckoutStage(j, stage)
        val store = j.fixture.store()
        val principal = testPrincipal()
        val replies = coroutineScope { List(6) { async(Dispatchers.Default) { store.execute(principal, request) } }.awaitAll() }
            .map { checkoutCommitted(it) }
        assertEquals(1, replies.count { !it.replayed })
        assertEquals(1, j.fixture.probes.getValue(request.command).prepares)
        assertEquals(1, j.fixture.probes.getValue(request.command).applies)
        assertTrue(replies.all { it.result == replies.first().result && it.sequence == replies.first().sequence })
        assertCheckoutStage(j, stage, request)
        val changed = when (val body = CheckoutFixtureCodec.decode(request.command, request.payload)) {
            is CheckoutRequest.Finalize -> body.copy(saleId = j.id())
            is CheckoutRequest.Outcome -> body.copy(outcome = CheckoutOutcome.CashReceived(checkoutMoney(100), checkoutMoney(0), j.evidence()))
            else -> error("Unsupported concurrency test stage")
        }
        val once = j.fixture.databaseState()
        assertIs<MutationReply.IdentityConflict>(store.execute(principal, j.request(changed, request.operationId)))
        assertEquals(once, j.fixture.databaseState())
    } finally { j.close() }
}

private suspend fun checkoutCancellation(stage: CheckoutStage) {
    for (point in StoreFaultPoint.entries) {
        val j = CheckoutJourney()
        try {
            val (request, _) = prepareCheckoutStage(j, stage)
            val before = j.fixture.databaseState()
            val sentinel = LocalStorageCancellationSentinel("synthetic-checkout-cancel")
            val thrown = assertFailsWith<LocalStorageCancellationSentinel> {
                j.fixture.store(fault = StoreFaultProbe { if (it == point) throw sentinel }).execute(testPrincipal(), request)
            }
            assertSame(sentinel, thrown)
            if (point != StoreFaultPoint.AFTER_COMMIT) assertEquals(before, j.fixture.databaseState())
            j.reopen()
            assertEquals(point == StoreFaultPoint.AFTER_COMMIT, j.execute(request).replayed)
            assertEquals(if (point == StoreFaultPoint.AFTER_COMMIT) 0 else 1, j.fixture.probes.getValue(request.command).prepares)
            assertCheckoutStage(j, stage, request)
        } finally { j.close() }
    }
}

private suspend fun checkoutPlanningDeadline(delta: Long, held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(); j.payCash(target)
        val cash = SalesSnapshotCodec.encodeCashSession(j.cash())
        j.reopen()
        j.at(assertNotNull(target.reservationExpiry) + delta)
        val request = j.finalRequest(target)
        val result = CheckoutFixtureCodec.decodeResult(request.command, j.execute(request).result)
        if (held) assertIs<CheckoutResult.Held>(result) else assertIs<CheckoutResult.Issued>(result)
        assertFinalShape(j.retained(request), !held)
        assertEquals(cash, SalesSnapshotCodec.encodeCashSession(j.cash()))
    } finally { j.close() }
}

private suspend fun checkoutCommitDeadline(delta: Long) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(); j.payCash(target)
        val expiry = assertNotNull(target.reservationExpiry)
        j.at(expiry - 2)
        val request = j.finalRequest(target)
        val before = j.fixture.databaseState()
        var reached = 0
        val reply = j.fixture.store(fault = StoreFaultProbe {
            if (it == StoreFaultPoint.BEFORE_COMMIT) { reached++; j.at(expiry + delta) }
        }).execute(testPrincipal(), request)
        assertEquals(1, reached)
        assertEquals(1, j.fixture.probes.getValue(request.command).inventoryPlans)
        if (delta < 0) {
            checkoutCommitted(reply)
            assertFinalShape(j.retained(request), true)
        } else {
            // Successor freeze qa/pos/freezes/deadline-result-correction-v2: proven full-ownership
            // deadline rollback is a typed rejection, not storage uncertainty.
            checkoutDeferred(reply, DeferredReason.PRECONDITION_EXPIRED)
            assertEquals(before, j.fixture.databaseState())
            j.reopen()
            assertEquals(before, j.fixture.databaseState())
            // Same absent operation is freshly planned at the original expired lease; it must hold.
            assertIs<CheckoutResult.Held>(CheckoutFixtureCodec.decodeResult(request.command, j.execute(request).result))
            assertFinalShape(j.retained(request), false)
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
        }
    } finally { j.close() }
}

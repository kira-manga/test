package com.ukkera.pos.database

import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CollectionStatus
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Genuine staged commands through synthetic fixture trust in real disposable SQLite transactions.
 * Only the clock is additionally controlled. Checks the kernel's last transaction decision, not
 * physical payment or fsync completion; this supplies no production identity or capacity proof.
 */
class CheckoutAdmissionDeadlineIntegrationTest {
    @Test fun beginCommitBeforeOriginalDeadlinePersistsExactlyOneCollection() = runTest {
        checkoutAdmissionDeadline(CheckoutAdmissionStage.BEGIN, -1)
    }

    @Test fun beginCommitAtOriginalDeadlineRollsBackBeforeFreshSameOperationRejects() = runTest {
        checkoutAdmissionDeadline(CheckoutAdmissionStage.BEGIN, 0)
    }

    @Test fun beginCommitAfterOriginalDeadlineRollsBackBeforeFreshSameOperationRejects() = runTest {
        checkoutAdmissionDeadline(CheckoutAdmissionStage.BEGIN, 1)
    }

    @Test fun attemptCommitBeforeOriginalDeadlinePersistsExactlyOneUnpaidIntent() = runTest {
        checkoutAdmissionDeadline(CheckoutAdmissionStage.ATTEMPT, -1)
    }

    @Test fun attemptCommitAtOriginalDeadlineLeavesNeitherIntentNorHeldSideCommit() = runTest {
        checkoutAdmissionDeadline(CheckoutAdmissionStage.ATTEMPT, 0)
    }

    @Test fun attemptCommitAfterOriginalDeadlineLeavesNeitherIntentNorHeldSideCommit() = runTest {
        checkoutAdmissionDeadline(CheckoutAdmissionStage.ATTEMPT, 1)
    }
}

private enum class CheckoutAdmissionStage { BEGIN, ATTEMPT }
private enum class CheckoutAdmissionLimit { RESERVATION, EARLIER_LOT }

private suspend fun checkoutAdmissionDeadline(stage: CheckoutAdmissionStage, commitOffset: Long) {
    for (limit in CheckoutAdmissionLimit.entries) {
        val j = CheckoutJourney()
        val label = "$stage/$limit/$commitOffset"
        try {
            j.install()
            val reservationExpiry = j.nowMillis + 60_000
            val lotExpiry = if (limit == CheckoutAdmissionLimit.EARLIER_LOT) j.nowMillis + 30_000 else null
            val originalDeadline = lotExpiry ?: reservationExpiry
            val target = j.create(reservationExpiry = reservationExpiry, lotExpiry = lotExpiry,
                begin = stage == CheckoutAdmissionStage.ATTEMPT)
            val inventoryBefore = j.inventory()
            val cashBefore = j.cash()
            val orderBefore = j.fixture.withView { checkoutOrder(it, target.orderId) }
            val body: CheckoutRequest = when (stage) {
                CheckoutAdmissionStage.BEGIN -> CheckoutRequest.Begin(CheckoutRef(orderBefore.id, orderBefore.revision),
                    j.cashRef(), target.collectionId, inventoryBefore.snapshot.revision, target.reservationId, reservationExpiry)
                CheckoutAdmissionStage.ATTEMPT -> CheckoutRequest.Attempt(j.salesRef(target), j.stockRef(target), j.id(),
                    CheckoutTender.Cash, checkoutMoney(100), "synthetic deadline payer")
            }
            val request = j.request(body)
            val before = j.fixture.databaseState()
            assertEquals(setOf("branch_authority", "operation_result", "aggregate_state", "unique_claim",
                "journal_envelope", "audit_event", "outbox_intent"), before.keys, label)
            assertEquals(0, j.fixture.probes.getValue(request.command).prepares, label)
            j.fixture.withView { assertNull(it.retainedCommand(request.operationId), label) }

            // The actual reserve/revalidation is safe. Change time only AFTER persistence has run,
            // at the last commit decision: equality must fail against the ORIGINAL exclusive limit.
            j.at(originalDeadline - 2)
            val evaluatedAt = j.fixture.base.clockValue
            var beforeCommit = 0
            var afterCommit = 0
            val reply = j.fixture.store(fault = StoreFaultProbe { point ->
                when (point) {
                    StoreFaultPoint.BEFORE_COMMIT -> {
                        beforeCommit++
                        assertEquals(evaluatedAt, j.fixture.base.clockValue, label)
                        j.at(originalDeadline + commitOffset)
                    }
                    StoreFaultPoint.AFTER_COMMIT -> afterCommit++
                    else -> Unit
                }
            }).execute(testPrincipal(), request)
            assertEquals(1, beforeCommit, label)
            assertEquals(if (commitOffset < 0) 1 else 0, afterCommit, label)
            assertEquals(originalDeadline + commitOffset, j.nowMillis, label)
            // These are real plan/domain/Apply PROPOSALS, including the ones rolled back below.
            assertAdmissionProbe(j.fixture.probes.getValue(request.command), domainCalls = 1, applies = 1, label = label)

            val durable = if (commitOffset < 0) {
                val committed = checkoutCommitted(reply)
                assertFalse(committed.replayed, label)
                assertEquals(evaluatedAt, committed.recordedAt, label)
                assertAdmissionCommitted(j, target, body, request, committed, inventoryBefore, cashBefore, label)
                if (stage == CheckoutAdmissionStage.ATTEMPT) assertEquals(SalesSnapshotCodec.encodeOrder(orderBefore),
                    SalesSnapshotCodec.encodeOrder(j.current(target).order), label)
                assertOneAdmissionRecord(before, j.fixture.databaseState(), label)
                committed
            } else {
                // Successor freeze qa/pos/freezes/deadline-result-correction-v2: proven full-ownership
                // deadline rollback is a typed rejection, not storage uncertainty.
                checkoutDeferred(reply, DeferredReason.PRECONDITION_EXPIRED)
                // Exact cells in ALL seven tables, including branch head, original result, audit and
                // upload, must roll back. Neither an intent nor a planner's Held effect may survive.
                assertEquals(before, j.fixture.databaseState(), label)
                j.fixture.withView { assertNull(it.retainedCommand(request.operationId), label) }
                assertAdmissionAbsent(j, target, body, request, inventoryBefore, cashBefore, label)
                j.reopen()
                assertEquals(before, j.fixture.databaseState(), label)

                val rejected = assertIs<MutationReply.Recorded>(j.fixture.store().execute(testPrincipal(), request))
                assertFalse(rejected.committed, label)
                assertFalse(rejected.replayed, label)
                assertEquals(request.operationId, rejected.operationId, label)
                assertEquals(j.fixture.base.clockValue, rejected.recordedAt, label)
                // reserve checks a now-expired requested lease before stock; with the earlier lot,
                // the requested lease is still future and the actual stock check rejects instead.
                // revalidateReservation returns Held at either limit, but ATTEMPT discards that plan.
                val expectedCode = if (stage == CheckoutAdmissionStage.BEGIN && limit == CheckoutAdmissionLimit.RESERVATION)
                    "fixture.checkout.inventory.invalid-state" else "fixture.checkout.inventory.unsafe-stock"
                assertEquals(expectedCode, rejected.code, label)
                assertEquals(CheckoutResult.Rejected(expectedCode), CheckoutFixtureCodec.decodeResult(request.command, rejected.result), label)
                assertAdmissionProbe(j.fixture.probes.getValue(request.command), domainCalls = 0, applies = 0, label = label)
                assertEquals(before.checkoutBusinessRows(), j.fixture.databaseState().checkoutBusinessRows(), label)
                assertAdmissionAbsent(j, target, body, request, inventoryBefore, cashBefore, label)
                val retained = j.retained(request)
                assertFalse(retained.committed, label)
                assertEquals(request.payload, retained.normalized, label)
                assertEquals(rejected.result, retained.result, label)
                assertTrue(retained.aggregateWrites.isEmpty(), label)
                assertTrue(retained.claimChanges.isEmpty(), label)
                assertTrue(retained.jobs.isEmpty(), label)
                assertOneAdmissionRecord(before, j.fixture.databaseState(), label)
                rejected
            }

            // Both the committed intent/collection and the fresh rejection recover their exact
            // original result after expiry, without preparing again or creating another intent.
            val once = j.fixture.databaseState()
            j.reopen()
            j.at(originalDeadline + 1)
            assertEquals(once, j.fixture.databaseState(), label)
            repeat(2) {
                val replay = assertIs<MutationReply.Recorded>(j.fixture.store().execute(testPrincipal(), request))
                assertTrue(replay.replayed, label)
                assertEquals(durable.operationId, replay.operationId, label)
                assertEquals(durable.originalActorId, replay.originalActorId, label)
                assertEquals(durable.committed, replay.committed, label)
                assertEquals(durable.code, replay.code, label)
                assertEquals(durable.result, replay.result, label)
                assertEquals(durable.authorityEpoch, replay.authorityEpoch, label)
                assertEquals(durable.sequence, replay.sequence, label)
                assertEquals(durable.recordedAt, replay.recordedAt, label)
            }
            val replayProbe = j.fixture.probes.getValue(request.command)
            assertEquals(0, replayProbe.prepares, label)
            assertEquals(0, replayProbe.evaluations, label)
            assertEquals(0, replayProbe.inventoryPlans, label)
            assertEquals(0, replayProbe.domainCalls, label)
            assertEquals(0, replayProbe.applies, label)
            assertEquals(once, j.fixture.databaseState(), label)
        } finally { j.close() }
    }
}

private fun assertAdmissionProbe(probe: CheckoutCommandProbe, domainCalls: Int, applies: Int, label: String) {
    assertEquals(1, probe.prepares, label)
    assertEquals(1, probe.evaluations, label)
    assertEquals(1, probe.preflightChecks, label)
    assertEquals(1, probe.preflightPasses, label)
    assertEquals(1, probe.inventoryPlans, label)
    assertEquals(domainCalls, probe.domainCalls, label)
    assertEquals(applies, probe.applies, label)
}

private fun assertOneAdmissionRecord(before: Map<String, List<List<String>>>, after: Map<String, List<List<String>>>, label: String) {
    // The complete-cell comparisons above/below remain the rollback and replay oracle, not counts.
    for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent"))
        assertEquals(before.getValue(table).size + 1, after.getValue(table).size, "$label/$table")
}

private suspend fun assertAdmissionCommitted(
    j: CheckoutJourney,
    target: CheckoutTarget,
    body: CheckoutRequest,
    request: MutationRequest,
    reply: MutationReply.Recorded,
    inventoryBefore: InventoryRetailObservedFacts,
    cashBefore: CashSession,
    label: String,
) {
    val inventory = j.inventory()
    val reservation = inventory.snapshot.reservations.single()
    val current = j.current(target)
    assertEquals(request.operationId, inventory.retained.operationId, label)
    assertEquals(inventoryBefore.snapshot.revision.next(), inventory.snapshot.revision, label)
    assertEquals(target.reservationId, reservation.reservationId, label)
    assertEquals(target.reservationExpiry, reservation.expiresAtEpochMillis, label)
    assertEquals(ReservationStatus.RESERVED, reservation.status, label)
    assertEquals(CollectionExposure.UNKNOWN, reservation.binding.exposure, label)
    assertTrue(reservation.holds.isEmpty(), label)
    assertTrue(inventory.snapshot.issues.isEmpty(), label)
    assertEquals(inventoryBefore.snapshot.sourceIds, inventory.snapshot.sourceIds, label)
    assertEquals(inventoryBefore.snapshot.positions, inventory.snapshot.positions, label)
    assertEquals(checkoutMoney(0), current.collection.collected, label)
    assertNull(current.collection.saleId, label)
    assertNull(current.order.paidSaleId, label)
    assertEquals(cashBefore.opening, current.cash.opening, label)
    assertEquals(cashBefore.expectedCash, current.cash.expectedCash, label)
    assertEquals(cashBefore.reservedCashOut, current.cash.reservedCashOut, label)
    assertEquals(cashBefore.totals, current.cash.totals, label)
    assertEquals(cashBefore.cashLedgerRevision, current.cash.cashLedgerRevision, label)
    val stock = CheckoutReservationRef(inventory.snapshot.revision, CheckoutRef(reservation.reservationId, reservation.revision))
    val retained = j.retained(request)
    requireCheckoutAfterFacts(retained)
    assertEquals(4, retained.aggregateWrites.size, label)
    assertTrue(retained.jobs.isEmpty(), label)
    val claim = retained.claimChanges.single()
    assertEquals(RetainedClaimAction.ACQUIRE, claim.action, label)
    val inventoryKinds = setOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND)
    when (body) {
        is CheckoutRequest.Begin -> {
            val expected = CheckoutResult.Begun(CheckoutSalesRef(body.order.copy(revision = body.order.revision.next()),
                CheckoutRef(body.collectionId, Revision(0)), body.cash), stock)
            assertEquals(expected, CheckoutFixtureCodec.decodeResult(request.command, reply.result), label)
            assertEquals(Revision(1), reservation.revision, label)
            assertEquals(CollectionStatus.LOCKED_UNPAID, current.collection.status, label)
            assertTrue(current.collection.attempts.isEmpty(), label)
            assertEquals(SalesSnapshotCodec.encodeCashSession(cashBefore), SalesSnapshotCodec.encodeCashSession(current.cash), label)
            assertEquals(inventoryKinds + setOf(CheckoutFixtureProfile.ORDER_KIND, CheckoutFixtureProfile.COLLECTION_KIND),
                retained.aggregateWrites.map { it.key.kind }.toSet(), label)
            assertEquals(CheckoutFixtureProfile.claim(SalesUniqueKind.COLLECTION, body.collectionId), claim.key, label)
            assertEquals(body.order.id, claim.ownerId, label)
        }
        is CheckoutRequest.Attempt -> {
            val sales = body.sales.copy(collection = body.sales.collection.copy(revision = body.sales.collection.revision.next()),
                cash = body.sales.cash.copy(session = body.sales.cash.session.copy(revision = body.sales.cash.session.revision.next())))
            assertEquals(CheckoutResult.Attempted(sales, stock, body.attemptId),
                CheckoutFixtureCodec.decodeResult(request.command, reply.result), label)
            assertEquals(body.stock.reservation.revision.next(), reservation.revision, label)
            assertEquals(CollectionStatus.OUTCOME_UNKNOWN, current.collection.status, label)
            val attempt = current.collection.attempts.single()
            assertEquals(body.attemptId, attempt.id, label)
            assertEquals(request.operationId, attempt.originalOperationId, label)
            assertEquals(TEST_ACTOR, attempt.originalActorId, label)
            assertEquals(j.sessionId, attempt.originalCashSessionId, label)
            assertEquals(body.amount, attempt.amount, label)
            assertEquals(body.payerReference, attempt.payerReference, label)
            assertEquals(AttemptStatus.AWAITING_ATTESTATION, attempt.status, label)
            assertNull(attempt.latestObservation, label)
            assertNull(attempt.payment, label)
            assertEquals(listOf(body.attemptId), current.cash.pendingAttemptIds, label)
            assertEquals(inventoryKinds + setOf(CheckoutFixtureProfile.COLLECTION_KIND, CheckoutFixtureProfile.SESSION_KIND),
                retained.aggregateWrites.map { it.key.kind }.toSet(), label)
            assertEquals(CheckoutFixtureProfile.claim(SalesUniqueKind.PAYMENT_ATTEMPT, body.attemptId), claim.key, label)
            assertEquals(target.collectionId, claim.ownerId, label)
            j.fixture.withView {
                assertNull(it.claim(CheckoutFixtureProfile.claim(SalesUniqueKind.PAYMENT_RECORD, body.attemptId)), label)
                assertNull(it.claim(CheckoutFixtureProfile.claim(SalesUniqueKind.CASH_SOURCE, body.attemptId)), label)
            }
        }
        else -> error("Only BEGIN and ATTEMPT belong to this deadline qualification")
    }
}

private suspend fun assertAdmissionAbsent(
    j: CheckoutJourney,
    target: CheckoutTarget,
    body: CheckoutRequest,
    request: MutationRequest,
    inventoryBefore: InventoryRetailObservedFacts,
    cashBefore: CashSession,
    label: String,
) {
    val inventory = j.inventory()
    assertEquals(inventoryBefore.cutBytes, inventory.cutBytes, label)
    assertEquals(inventoryBefore.companionBytes, inventory.companionBytes, label)
    assertFalse(request.operationId in inventory.snapshot.operationIds, label)
    assertTrue(inventory.snapshot.issues.isEmpty(), label)
    // An expired lot's purely proposed EXPIRED/HELD changes must not leak from a refused attempt.
    assertEquals(StockSafety.CLEAR, inventory.snapshot.positions.single().safety, label)
    assertEquals(SalesSnapshotCodec.encodeCashSession(cashBefore), SalesSnapshotCodec.encodeCashSession(j.cash()), label)
    assertTrue(j.cash().pendingAttemptIds.isEmpty(), label)
    when (body) {
        is CheckoutRequest.Begin -> {
            assertNull(inventory.snapshot.reservation(target.reservationId), label)
            j.fixture.withView {
                assertNull(it.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.COLLECTION, target.collectionId)), label)
                assertNull(it.claim(CheckoutFixtureProfile.claim(SalesUniqueKind.COLLECTION, target.collectionId)), label)
            }
        }
        is CheckoutRequest.Attempt -> {
            val reservation = assertNotNull(inventory.snapshot.reservation(target.reservationId), label)
            assertEquals(ReservationStatus.RESERVED, reservation.status, label)
            assertTrue(reservation.holds.isEmpty(), label)
            val collection = j.current(target).collection
            assertEquals(CollectionStatus.LOCKED_UNPAID, collection.status, label)
            assertEquals(checkoutMoney(0), collection.collected, label)
            assertTrue(collection.attempts.isEmpty(), label)
            j.fixture.withView {
                for (kind in listOf(SalesUniqueKind.PAYMENT_ATTEMPT, SalesUniqueKind.PAYMENT_RECORD, SalesUniqueKind.CASH_SOURCE))
                    assertNull(it.claim(CheckoutFixtureProfile.claim(kind, body.attemptId)), label)
            }
        }
        else -> error("Only BEGIN and ATTEMPT belong to this deadline qualification")
    }
}

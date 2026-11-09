package com.ukkera.pos.database

import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.recovery.RecoveryAdmissionPolicy
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.recovery.RecoveryResourceSelector
import com.ukkera.pos.recovery.ResourceAdmissionDecision
import com.ukkera.pos.sales.CollectionStatus
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Genuine positive history, then explicit stale requests or labeled current-row corruption controls. */
class CheckoutPreflightIntegrationTest {
    @Test fun allSevenExpectedRevisionsGateIssuedBeforeTheIssuePlanner() = runTest { checkoutRevisionGuards(false) }
    @Test fun allSevenExpectedRevisionsGateHeldBeforeTheIssuePlanner() = runTest { checkoutRevisionGuards(true) }
    @Test fun wrongActorWithAnyOwnerGrantStillCannotIssueAnotherCollectorsOrder() = runTest { checkoutWrongActor(false) }
    @Test fun wrongActorWithAnyOwnerGrantStillCannotCommitHeldForAnotherCollectorsOrder() = runTest { checkoutWrongActor(true) }
    @Test fun genuineUnresolvedAttemptCannotReachIssuedPlanner() = runTest { checkoutMoneyGuard(false, true) }
    @Test fun genuineUnresolvedAttemptCannotReachHeldPlanner() = runTest { checkoutMoneyGuard(true, true) }
    @Test fun genuinePartialCashCannotReachIssuedPlanner() = runTest { checkoutMoneyGuard(false, false) }
    @Test fun genuinePartialCashCannotReachHeldPlanner() = runTest { checkoutMoneyGuard(true, false) }
    @Test fun currentAssignmentMismatchAndClosingTransferGateIssuedBeforePlanner() = runTest { checkoutCustodyGuards(false) }
    @Test fun currentAssignmentMismatchAndClosingTransferGateHeldBeforePlanner() = runTest { checkoutCustodyGuards(true) }
    @Test fun missingOrWrongCurrentClaimsAreStorageUncertaintyNotMoneyAuthority() = runTest { checkoutClaimCorruption() }
    @Test fun malformedOrMissingCurrentSalesRowsCannotBecomeHeldSuccess() = runTest { checkoutRowCorruption() }
    @Test fun anotherGenuineCurrentReservationCannotAuthorizeIssuedForThisCoveredOrder() = runTest { checkoutOtherReservation(false) }
    @Test fun anotherGenuineCurrentReservationCannotAuthorizeHeldForThisCoveredOrder() = runTest { checkoutOtherReservation(true) }

    @Test fun unsafeAttemptRejectsEntireRevalidationWithoutCreatingAnIntentOrHeldSideCommit() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(lotExpiry = j.nowMillis + 5)
            val request = j.request(CheckoutRequest.Attempt(j.salesRef(target), j.stockRef(target), j.id(), CheckoutTender.Cash,
                checkoutMoney(100), null))
            j.at(j.nowMillis + 5)
            val before = j.fixture.databaseState().checkoutBusinessRows()
            val reply = assertIs<MutationReply.Recorded>(j.fixture.store().execute(testPrincipal(), request))
            assertFalse(reply.committed)
            assertEquals("fixture.checkout.inventory.unsafe-stock", reply.code)
            assertEquals(before, j.fixture.databaseState().checkoutBusinessRows())
            assertEquals(1, j.fixture.probes.getValue(request.command).inventoryPlans)
            assertEquals(0, j.fixture.probes.getValue(request.command).domainCalls)
            assertTrue(j.current(target).collection.attempts.isEmpty())
            assertTrue(j.cash().pendingAttemptIds.isEmpty())
        } finally { j.close() }
    }

    @Test fun finalAuthorizationBindsLoadedOwnerQuoteAmountAndExactOperationNotCallerFlags() = runTest {
        for (zero in listOf(false, true)) {
            val j = CheckoutJourney()
            try {
                j.install(); val target = j.create(if (zero) CheckoutBasket.ZERO else CheckoutBasket.SIMPLE_100)
                if (!zero) j.payCash(target)
                val request = j.finalRequest(target)
                val body = assertIs<CheckoutRequest.Finalize>(CheckoutFixtureCodec.decode(request.command, request.payload))
                j.execute(request)
                val actual = assertNotNull(j.fixture.probes.getValue(request.command).lastRequirement)
                val expected = salesAuthorizationRequirement(
                    if (zero) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE,
                    TEST_SCOPE, target.orderId, body.sales.order.revision, request.operationId,
                    semanticDigest(TEST_SCOPE, TEST_ACTOR, request.command, request.payload), request.command.name, TEST_ACTOR,
                    checkoutMoney(if (zero) 0 else 100))
                assertEquals(expected, actual)
            } finally { j.close() }
        }
    }

    @Test fun preservedActualOutcomeNeedsNeitherInventoryReadsNorStockRecoveryReadmission() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); val attempt = j.start(target, 100)
            val request = j.request(CheckoutRequest.Outcome(j.salesRef(target), attempt,
                CheckoutOutcome.CashReceived(checkoutMoney(100), checkoutMoney(0), j.evidence()), CheckoutExposureUpdate.Preserve))
            val stockHold = syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.STOCK)),
                setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, TEST_ACTOR)))
            j.fixture.base.installSyntheticBarrierRecovery(stockHold)
            // Explicit destructive negative dependency control in this disposable file. Money intent exists;
            // absence of inventory must not fabricate NotPaid or prevent this PRESERVE observation.
            for (key in listOf(InventoryRetailStoreProfile.cutKey(TEST_SCOPE.branchId), InventoryRetailStoreProfile.companionKey(TEST_SCOPE.branchId)))
                j.fixture.deleteCheckoutRowForNegativeControl(key)
            j.execute(request)
            val probe = j.fixture.probes.getValue(request.command)
            assertEquals(0, probe.inventoryPlans)
            assertTrue(probe.lastFootprint.none { it.kind == RecoveryResourceKind.STOCK })
            assertEquals(CollectionStatus.COVERED, j.current(target).collection.status)
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
            val retained = j.retained(request)
            assertEquals(2, retained.aggregateWrites.size)
            requireCheckoutAfterFacts(retained)
        } finally { j.close() }
    }

    @Test fun derivedOutcomeCannotIgnoreAStockHoldEvenForAnUnrelatedReadmittedAlias() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); val attempt = j.start(target, 100)
            val request = j.request(CheckoutRequest.Outcome(j.salesRef(target), attempt,
                CheckoutOutcome.CashReceived(checkoutMoney(100), checkoutMoney(0), j.evidence()),
                CheckoutExposureUpdate.Derive(j.stockRef(target))))
            val alias = RecoveryResourceKey(RecoveryResourceKind.STOCK, j.id())
            val state = syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Resource(alias)),
                setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, TEST_ACTOR), alias))
            j.fixture.base.installSyntheticBarrierRecovery(state)
            val before = j.fixture.databaseState()
            checkoutDeferred(j.fixture.store().execute(testPrincipal(), request), DeferredReason.RESOURCE_RECONCILIATION_REQUIRED)
            val probe = j.fixture.probes.getValue(request.command)
            assertEquals(ResourceAdmissionDecision.ADMITTED, RecoveryAdmissionPolicy.evaluate(state, TEST_SCOPE.branchId, probe.lastFootprint))
            assertEquals(0, probe.evaluations)
            assertEquals(before, j.fixture.databaseState())
        } finally { j.close() }
    }

    @Test fun finalStockCashAndOrderRecoveryHoldsDenyBeforeEitherFinalBranch() = runTest {
        for (held in listOf(false, true)) for (kind in listOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY,
            RecoveryResourceKind.ORDER_CUSTODY)) {
            val j = CheckoutJourney()
            try {
                j.install(); val target = j.create(); j.payCash(target)
                if (held) j.at(assertNotNull(target.reservationExpiry))
                val request = j.finalRequest(target)
                val key = RecoveryResourceKey(kind, when (kind) {
                    RecoveryResourceKind.STOCK -> target.lines.single().allocationId
                    RecoveryResourceKind.CASH_CUSTODY -> j.sessionId
                    else -> target.orderId
                })
                j.fixture.base.installSyntheticBarrierRecovery(syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Resource(key)),
                    setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, TEST_ACTOR))))
                val before = j.fixture.databaseState()
                checkoutDeferred(j.fixture.store().execute(testPrincipal(), request), DeferredReason.RESOURCE_RECONCILIATION_REQUIRED)
                assertEquals(0, j.fixture.probes.getValue(request.command).evaluations)
                assertTrue(key in j.fixture.probes.getValue(request.command).lastFootprint)
                assertEquals(before, j.fixture.databaseState())
            } finally { j.close() }
        }
    }

    @Test fun originalFinalResultReplaysBeforePrepareDespiteExpiryHoldsAndUnqualifiedWriterButNotRevocation() = runTest {
        for (held in listOf(false, true)) {
            val j = CheckoutJourney()
            try {
                j.install(); val target = j.create(); j.payCash(target)
                if (held) j.at(assertNotNull(target.reservationExpiry))
                val request = j.finalRequest(target)
                val original = j.execute(request)
                j.reopen()
                j.at(assertNotNull(target.reservationExpiry) + 1000)
                j.fixture.setGrants(TEST_ACTOR, setOf(SecurityAction.OPERATION_RESULT_RECOVERY), OwnerAccess.OWN_ONLY)
                j.fixture.base.restoreHold()
                j.fixture.base.writerVerification = RuntimeVerification.REFUSED
                j.fixture.base.absenceVerification = RuntimeVerification.UNAVAILABLE
                val before = j.fixture.databaseState()
                val replay = j.execute(request)
                assertTrue(replay.replayed)
                assertEquals(original.result, replay.result)
                assertEquals(original.sequence, replay.sequence)
                assertEquals(original.recordedAt, replay.recordedAt)
                assertEquals(0, j.fixture.probes.getValue(request.command).prepares)
                assertEquals(before, j.fixture.databaseState())
                j.fixture.base.setAuth("revoked")
                val revoked = j.fixture.databaseState()
                checkoutDeferred(j.fixture.store().execute(testPrincipal(), request), DeferredReason.AUTHORIZATION_DENIED)
                assertEquals(0, j.fixture.probes.getValue(request.command).prepares)
                assertEquals(revoked, j.fixture.databaseState())
            } finally { j.close() }
        }
    }

    @Test fun previousSaleAndIssueSourcesCannotBeReusedByAnotherGenuineCoveredOrder() = runTest {
        for (held in listOf(false, true)) for (reuseSale in listOf(false, true)) {
            val j = CheckoutJourney()
            try {
                j.install(); val first = j.create(); j.payCash(first)
                val original = j.finalRequest(first)
                val result = assertIs<CheckoutResult.Issued>(CheckoutFixtureCodec.decodeResult(original.command, j.execute(original).result))
                val next = j.create(); j.payCash(next)
                if (held) j.at(assertNotNull(next.reservationExpiry))
                val request = j.finalRequest(next, if (reuseSale) j.id() else result.issueId, if (reuseSale) result.saleId else j.id())
                val probe = j.fixture.probes.getValue(request.command)
                val plans = probe.inventoryPlans
                val before = j.fixture.databaseState().checkoutBusinessRows()
                val rejected = assertIs<MutationReply.Recorded>(j.fixture.store().execute(testPrincipal(), request))
                assertFalse(rejected.committed)
                assertEquals("fixture.checkout.sales.duplicate-source-allocation", rejected.code)
                assertEquals(plans, probe.inventoryPlans)
                assertEquals(before, j.fixture.databaseState().checkoutBusinessRows())
                assertEquals(checkoutMoney(700), j.cash().expectedCash)
            } finally { j.close() }
        }
    }

    @Test fun newOperationForAlreadyRecordedCashDoesNotTreatSameOwnerClaimAsIdempotency() = runTest {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); val attempt = j.payCash(target)
            val before = j.fixture.databaseState().checkoutBusinessRows()
            val request = j.request(CheckoutRequest.Outcome(j.salesRef(target), attempt,
                CheckoutOutcome.CashReceived(checkoutMoney(100), checkoutMoney(0), j.evidence()), CheckoutExposureUpdate.Preserve))
            val reply = assertIs<MutationReply.Recorded>(j.fixture.store().execute(testPrincipal(), request))
            assertFalse(reply.committed)
            assertEquals("fixture.checkout.sales.immutable-record", reply.code)
            assertEquals(before, j.fixture.databaseState().checkoutBusinessRows())
            assertEquals(checkoutMoney(600), j.cash().expectedCash)
        } finally { j.close() }
    }
}

private enum class CheckoutRevisionControl { ORDER, COLLECTION, CASH, DRAWER, CUSTODIAN, INVENTORY, RESERVATION }

private suspend fun checkoutOtherReservation(held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(); j.payCash(target)
        val other = j.create() // Genuine separate current reservation, not forged facts or a stale revision.
        if (held) j.at(assertNotNull(target.reservationExpiry))
        val request = j.request(CheckoutRequest.Finalize(j.salesRef(target), j.stockRef(other), j.id(), j.id()))
        j.inventory() // Actual observation + closed producer validation succeeds before the live mismatch.
        assertCheckoutPreflightRejection(j, request, SalesRule.STOCK_PROOF_MISMATCH)
        assertEquals(checkoutMoney(600), j.cash().expectedCash)
    } finally { j.close() }
}

private suspend fun checkoutRevisionGuards(held: Boolean) {
    for (field in CheckoutRevisionControl.entries) {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payCash(target)
            j.inventory() // independently succeeds before the requested-revision mutant
            if (held) j.at(assertNotNull(target.reservationExpiry))
            val ordinary = j.finalRequest(target)
            val body = assertIs<CheckoutRequest.Finalize>(CheckoutFixtureCodec.decode(ordinary.command, ordinary.payload))
            fun previous(ref: CheckoutRef) = ref.copy(revision = Revision(ref.revision.value - 1))
            val altered = when (field) {
                CheckoutRevisionControl.ORDER -> body.copy(sales = body.sales.copy(order = previous(body.sales.order)))
                CheckoutRevisionControl.COLLECTION -> body.copy(sales = body.sales.copy(collection = previous(body.sales.collection)))
                CheckoutRevisionControl.CASH -> body.copy(sales = body.sales.copy(cash = body.sales.cash.copy(session = previous(body.sales.cash.session))))
                CheckoutRevisionControl.DRAWER -> body.copy(sales = body.sales.copy(cash = body.sales.cash.copy(drawerAssignmentRevision = Revision(0))))
                CheckoutRevisionControl.CUSTODIAN -> body.copy(sales = body.sales.copy(cash = body.sales.cash.copy(custodianAssignmentRevision = Revision(0))))
                CheckoutRevisionControl.INVENTORY -> body.copy(stock = body.stock.copy(inventoryRevision = Revision(body.stock.inventoryRevision.value - 1)))
                CheckoutRevisionControl.RESERVATION -> body.copy(stock = body.stock.copy(reservation = previous(body.stock.reservation)))
            }
            assertCheckoutPreflightRejection(j, j.request(altered), SalesRule.REVISION_CONFLICT)
        } finally { j.close() }
    }
}

private suspend fun checkoutWrongActor(held: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create(); j.payCash(target)
        val other = j.id()
        j.fixture.setGrants(other, CHECKOUT_DEFAULT_ACTIONS, OwnerAccess.ANY_OWNER)
        if (held) j.at(assertNotNull(target.reservationExpiry))
        val request = j.finalRequest(target)
        assertCheckoutPreflightRejection(j, request, SalesRule.OWNER_REQUIRED, other)
        assertEquals(OwnerConstraint.OwnerPrivate(TEST_ACTOR), assertNotNull(j.fixture.probes.getValue(request.command).lastRequirement).owner)
    } finally { j.close() }
}

private suspend fun checkoutMoneyGuard(held: Boolean, unresolved: Boolean) {
    val j = CheckoutJourney()
    try {
        j.install(); val target = j.create()
        if (unresolved) j.start(target, 100) else j.payCash(target, 40)
        if (held) j.at(assertNotNull(target.reservationExpiry))
        assertCheckoutPreflightRejection(j, j.finalRequest(target),
            if (unresolved) SalesRule.PAYMENT_UNRESOLVED else SalesRule.INSUFFICIENT_COVERAGE)
    } finally { j.close() }
}

private suspend fun checkoutCustodyGuards(held: Boolean) {
    for (control in 0..3) {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payCash(target)
            if (held) j.at(assertNotNull(target.reservationExpiry))
            val request = j.finalRequest(target)
            if (control < 3) {
                val key = CheckoutFixtureProfile.drawerKey(CHECKOUT_DRAWER)
                val row = j.fixture.withView { CheckoutRow.from(assertNotNull(it.aggregate(key))) }
                val value = CheckoutRows.drawer(row, TEST_SCOPE, CHECKOUT_DRAWER)
                val altered = when (control) {
                    0 -> value.copy(activeSessionId = null)
                    1 -> value.copy(activeSessionId = j.id())
                    else -> value.copy(unresolvedClosingTransferId = j.id())
                }
                j.fixture.overwriteCheckoutRowForNegativeControl(key, CheckoutRows.encodeDrawer(altered))
            } else {
                val key = CheckoutFixtureProfile.custodianKey(TEST_ACTOR)
                val row = j.fixture.withView { CheckoutRow.from(assertNotNull(it.aggregate(key))) }
                val value = CheckoutRows.custodian(row, TEST_SCOPE, TEST_ACTOR)
                j.fixture.overwriteCheckoutRowForNegativeControl(key, CheckoutRows.encodeCustodian(value.copy(activeSessionId = null)))
            }
            assertCheckoutPreflightRejection(j, request, SalesRule.CUSTODY_UNRESOLVED)
        } finally { j.close() }
    }
}

private suspend fun checkoutClaimCorruption() {
    for (control in 0..8) for (wrongOwner in listOf(false, true)) {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); val attempt = j.payCash(target)
            val open = assertIs<CheckoutRequest.Open>(CheckoutFixtureCodec.decode(j.history[1].command, j.history[1].payload))
            val keys = listOf(SalesUniqueKind.CASH_SESSION to j.sessionId, SalesUniqueKind.OPENING_FLOAT_SOURCE to open.openingSourceId,
                SalesUniqueKind.ACTIVE_DRAWER to CHECKOUT_DRAWER, SalesUniqueKind.ACTIVE_CUSTODIAN to TEST_ACTOR,
                SalesUniqueKind.ORDER to target.orderId, SalesUniqueKind.COLLECTION to target.collectionId,
                SalesUniqueKind.PAYMENT_ATTEMPT to attempt, SalesUniqueKind.PAYMENT_RECORD to attempt, SalesUniqueKind.CASH_SOURCE to attempt)
            val key = CheckoutFixtureProfile.claim(keys[control].first, keys[control].second)
            val request = j.finalRequest(target)
            j.fixture.corruptProjection { connection ->
                connection.usePrepared(if (wrongOwner) "UPDATE unique_claim SET ownerId=? WHERE scope=? AND namespace=? AND `key`=?"
                    else "DELETE FROM unique_claim WHERE scope=? AND namespace=? AND `key`=?") { statement ->
                    var index = 1
                    if (wrongOwner) statement.bindText(index++, j.id().value)
                    statement.bindText(index++, TEST_SCOPE.storageKey()); statement.bindText(index++, key.namespace); statement.bindText(index, key.key)
                    statement.step()
                }
            }
            val before = j.fixture.databaseState()
            checkoutDeferred(j.fixture.store().execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
            assertEquals(0, j.fixture.probes.getValue(request.command).preflightChecks)
            assertEquals(0, j.fixture.probes.getValue(request.command).inventoryPlans)
            assertEquals(before, j.fixture.databaseState())
        } finally { j.close() }
    }
}

private suspend fun checkoutRowCorruption() {
    for (kind in listOf(SalesSnapshotKind.ORDER, SalesSnapshotKind.COLLECTION, SalesSnapshotKind.CASH_SESSION)) for (missing in listOf(false, true)) {
        val j = CheckoutJourney()
        try {
            j.install(); val target = j.create(); j.payCash(target)
            j.at(assertNotNull(target.reservationExpiry))
            val request = j.finalRequest(target)
            val key = CheckoutFixtureProfile.salesKey(kind, when (kind) {
                SalesSnapshotKind.ORDER -> target.orderId
                SalesSnapshotKind.COLLECTION -> target.collectionId
                else -> j.sessionId
            })
            if (missing) j.fixture.deleteCheckoutRowForNegativeControl(key)
            else j.fixture.overwriteCheckoutRowForNegativeControl(key, StoredBytes("{}".encodeToByteArray()))
            val before = j.fixture.databaseState()
            checkoutDeferred(j.fixture.store().execute(testPrincipal(), request), DeferredReason.STORAGE_UNCERTAIN)
            assertEquals(0, j.fixture.probes.getValue(request.command).inventoryPlans)
            assertEquals(before, j.fixture.databaseState())
        } finally { j.close() }
    }
}

internal suspend fun assertCheckoutPreflightRejection(j: CheckoutJourney, request: MutationRequest, rule: SalesRule, actor: DomainId = TEST_ACTOR) {
    val before = j.fixture.databaseState().checkoutBusinessRows()
    val reply = assertIs<MutationReply.Recorded>(j.fixture.store().execute(testPrincipal(actor), request))
    assertFalse(reply.committed)
    assertEquals(CheckoutRejectionCodes.sales(rule), reply.code)
    assertEquals(CheckoutResult.Rejected(reply.code), CheckoutFixtureCodec.decodeResult(request.command, reply.result))
    val probe = j.fixture.probes.getValue(request.command)
    assertEquals(1, probe.preflightChecks, "Must reach this factual preflight, not an earlier provenance/permission refusal")
    assertEquals(0, probe.preflightPasses)
    assertEquals(0, probe.inventoryPlans)
    assertEquals(0, probe.domainCalls)
    assertEquals(before, j.fixture.databaseState().checkoutBusinessRows())
}

/** Negative-only disposable database edits. Never used by positive staging. */
internal suspend fun CheckoutFixture.overwriteCheckoutRowForNegativeControl(key: AggregateKey, bytes: StoredBytes) = corruptProjection { connection ->
    connection.usePrepared("UPDATE aggregate_state SET payload=?,payloadHash=? WHERE scope=? AND kind=? AND id=?") {
        it.bindBlob(1, bytes.copyBytes()); it.bindText(2, sha256(bytes.copyBytes()).value)
        it.bindText(3, TEST_SCOPE.storageKey()); it.bindText(4, key.kind); it.bindText(5, key.id.value); it.step()
    }
}

internal suspend fun CheckoutFixture.deleteCheckoutRowForNegativeControl(key: AggregateKey) = corruptProjection { connection ->
    connection.usePrepared("DELETE FROM aggregate_state WHERE scope=? AND kind=? AND id=?") {
        it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, key.kind); it.bindText(3, key.id.value); it.step()
    }
}

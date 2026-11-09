package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.recovery.RecoveryResourceSelector
import com.ukkera.pos.sales.CashAssignmentReadSet
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessions
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.ClosingWorkReadSet
import com.ukkera.pos.sales.CountedCash
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.sales.store.CashNonSaleCommandCodec
import com.ukkera.pos.sales.store.CashNonSaleMovementCodec
import com.ukkera.pos.sales.store.CashNonSaleMovementHandler
import com.ukkera.pos.sales.store.CashNonSaleRequestV1
import com.ukkera.pos.sales.store.CashNonSaleResultCodec
import com.ukkera.pos.sales.store.CashNonSaleResultV1
import com.ukkera.pos.sales.store.CashNonSaleStoreProfile
import com.ukkera.pos.sales.store.CashNonSaleUnsupported
import com.ukkera.pos.sales.store.NonSaleCashKind
import com.ukkera.pos.sales.store.SalesStorageRevision
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real file-backed SQLite + the PUBLIC typed handler. Identity, all seeded domain
 * provenance/companion indexes and runtime/custody are synthetic test inputs.
 * Closing/drop setup persists their cash snapshots, not complete companion jobs.
 * This is never a production provisioner, login implementation or finance gate.
 */
private class CashDatabaseFixture(path: Path? = null) {
    val kernel = if (path == null) StoreFixture() else StoreFixture(path)
    val path: Path get() = kernel.path
    val sessionId = testId(500)
    val drawerId = testId(501)
    val handler = CashNonSaleMovementHandler()
    var ownerAccess = OwnerAccess.OWN_ONLY
    private var serial = 600
    private fun nextId(): DomainId = testId(serial++)

    private fun snapshot(principal: LocalEmployeePrincipal, state: String): AuthorizationSnapshot = AuthorizationSnapshot(
        principal.subjectId, principal.kind, principal.scope, principal.authenticationId, principal.generation,
        principal.deviceId, principal.admissionRevision, Revision(1), state != "disabled", state != "revoked",
        true, AuthenticationPurpose.INTERACTIVE,
        if (state == "no-grants") emptyList() else listOf(
            PermissionGrant(Permission(SecurityAction.CASH_MOVE, PermissionVerb.PERFORM), TEST_SCOPE,
                GrantEffect.ALLOW, PermissionLimits(Money(Currency.EGP, Long.MAX_VALUE)), ownerAccess),
            PermissionGrant(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM), TEST_SCOPE,
                GrantEffect.ALLOW),
        ),
    )

    private val authorization = TransactionAuthorizationSource { principal, view ->
        val state = view.aggregate(AUTH_KEY)?.payload?.copyBytes()?.decodeToString()
        if (principal !is LocalEmployeePrincipal || state == null) null else snapshot(principal, state)
    }

    fun store(fault: StoreFaultProbe = NoStoreFault): AtomicMasterStore = AtomicMasterStore(
        kernel.database, TEST_SCOPE, TEST_AUTHORITY, listOf(handler), authorization, kernel.runtime,
        MasterClock { TEST_TIME }, fault,
    )

    /** Pure setup transition with explicitly synthetic authorization; no public proof factory. */
    suspend fun command(action: SecurityAction, id: DomainId, revision: Revision, amount: Money? = null): SalesCommand {
        val principal = testPrincipal()
        val operation = OperationId(nextId().value)
        val digest = PayloadDigest("2".repeat(64))
        val reason = "synthetic.fixture"
        val requirement = salesAuthorizationRequirement(action, TEST_SCOPE, id, revision, operation, digest,
            reason, TEST_ACTOR, amount)
        val state = AuthorizationSnapshot(TEST_ACTOR, PrincipalKind.LOCAL_EMPLOYEE, TEST_SCOPE,
            principal.authenticationId, principal.generation, principal.deviceId, principal.admissionRevision,
            Revision(1), true, true, true, AuthenticationPurpose.INTERACTIVE,
            listOf(PermissionGrant(requirement.permission, TEST_SCOPE, GrantEffect.ALLOW,
                PermissionLimits(Money(Currency.EGP, Long.MAX_VALUE)))))
        val authorized = assertIs<AuthorizationDecision.Allowed>(AuthorizationEvaluator.evaluate(principal, state, requirement))
        return SalesCommand(operation, authorized.authorization, TEST_TIME.value, digest, reason)
    }

    suspend fun seed(opening: Long = 1_000): CashSession {
        kernel.seed()
        val assignment = CashAssignmentReadSet(TEST_SCOPE, drawerId, Revision(0), null, null,
            TEST_ACTOR, Revision(0), null)
        val cash = CashSessions.open(command(SecurityAction.CASH_OPEN, drawerId, Revision(0), money(opening)),
            assignment, Revision(0), Revision(0), sessionId, LocalDate(2026, 10, 23),
            CountedCash(money(opening), CashierEvidence(nextId(), TEST_ACTOR, TEST_TIME.value)), nextId()).value
        seedProtectedSession(cash)
        return cash
    }

    suspend fun seedProtectedSession(session: CashSession) {
        val body = SalesSnapshotCodec.encodeCashSession(session).encodeToByteArray()
        kernel.database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val old = kernel.database.facts().aggregate(TEST_SCOPE.storageKey(), CashNonSaleStoreProfile.SESSION_KIND, session.id.value)
                if (old == null) kernel.database.facts().insertAggregate(AggregateRow(TEST_SCOPE.storageKey(),
                    CashNonSaleStoreProfile.SESSION_KIND, session.id.value, SalesStorageRevision.toStorage(session.revision).value,
                    CashNonSaleStoreProfile.SESSION_SCHEMA, body, sha256(body).value))
                else check(kernel.database.facts().replaceAggregate(old.scope, old.kind, old.id, old.revision,
                    SalesStorageRevision.toStorage(session.revision).value, old.schema, body, sha256(body).value) == 1)
            }
        }
    }

    fun request(revision: Revision = Revision(0), source: Int = 520, operation: Int = 530,
        kind: NonSaleCashKind = NonSaleCashKind.NON_SALE_PAY_IN, amount: Long = 100,
        target: DomainId = sessionId): MutationRequest = MutationRequest(OperationId(testId(operation).value),
        handler.key, CashNonSaleCommandCodec.encode(CashNonSaleRequestV1(target, revision, testId(source), kind,
            money(amount), testId(540), TEST_TIME, "Synthetic counted cash; not a real payment.")))

    suspend fun session(): CashSession = kernel.database.useWriterConnection {
        val row = assertNotNull(kernel.database.facts().aggregate(TEST_SCOPE.storageKey(),
            CashNonSaleStoreProfile.SESSION_KIND, sessionId.value))
        assertEquals(sha256(row.payload).value, row.payloadHash)
        SalesSnapshotCodec.decodeCashSession(row.payload.decodeToString(), SalesSnapshotBinding(
            SalesSnapshotKind.CASH_SESSION, TEST_SCOPE, sessionId, SalesStorageRevision.toDomain(Revision(row.revision))))
    }

    suspend fun row(key: AggregateKey): AggregateRow? = kernel.database.useWriterConnection {
        kernel.database.facts().aggregate(TEST_SCOPE.storageKey(), key.kind, key.id.value)
    }

    suspend fun counts(results: Long, claims: Long) {
        for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent"))
            assertEquals(results, kernel.count(table), table)
        assertEquals(claims, kernel.count("unique_claim"))
    }
    fun close() = kernel.close()
}

private fun money(value: Long): Money = Money(Currency.EGP, value)
private fun recorded(reply: MutationReply, committed: Boolean): MutationReply.Recorded =
    assertIs<MutationReply.Recorded>(reply).also { assertEquals(committed, it.committed) }

class CashStoreIntegrationTest {
    @Test fun unrelatedStockHoldDoesNotImplicitlyDisableTheExistingPublicCashHandler() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed()
            val state = syntheticBarrierRecovery(listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.STOCK)),
                setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, TEST_ACTOR)))
            assertTrue(state.holds.isNotEmpty())
            f.kernel.installSyntheticBarrierRecovery(state)
            recorded(f.store().execute(testPrincipal(), f.request()), true)
            assertEquals(money(1_100), f.session().expectedCash)
            f.counts(1, 1)
            // This cash-only handler has no new implicit STOCK restriction, but it
            // cannot clear or turn the unresolved stock hold into readmission.
            val branch = assertNotNull(f.kernel.database.facts().branch(TEST_SCOPE.storageKey()))
            assertEquals(state, Json.decodeFromString<RecoveryAdmissionState>(branch.recoveryJson))
        } finally { f.close() }
    }

    @Test fun retainedPublicCashFactsSurviveASecondMovementAndReopenWithoutBecomingCurrentState() = runTest {
        var f = CashDatabaseFixture()
        try {
            f.seed()
            val request = f.request()
            val original = recorded(f.store().execute(testPrincipal(), request), true)
            val sessionKey = CashNonSaleStoreProfile.sessionKey(f.sessionId)
            val movementKey = CashNonSaleStoreProfile.movementKey(testId(520))
            val originalSession = assertNotNull(f.row(sessionKey))
            val originalMovement = assertNotNull(f.row(movementKey))
            recorded(f.store().execute(testPrincipal(), f.request(Revision(1), source = 521, operation = 531,
                kind = NonSaleCashKind.OPERATING_EXPENSE, amount = 25)), true)
            val path = f.path
            f.close()
            f = CashDatabaseFixture(path)
            f.kernel.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val view = TransactionView(TEST_SCOPE, f.kernel.database.facts())
                    try {
                        val retained = assertNotNull(view.retainedCommand(request.operationId))
                        assertTrue(retained.committed)
                        assertEquals(request.command, retained.command)
                        assertEquals(TEST_ACTOR, retained.originalActorId)
                        assertEquals(original.result, retained.result)
                        assertEquals(original.sequence, retained.sequence)
                        assertEquals(original.authorityEpoch, retained.authorityEpoch)
                        assertEquals(f.handler.normalize(request.payload), retained.normalized)
                        assertEquals(semanticDigest(TEST_SCOPE, TEST_ACTOR, request.command, retained.normalized), retained.semanticDigest)
                        assertEquals(setOf(sessionKey, movementKey), retained.aggregateWrites.map { it.key }.toSet())
                        assertEquals(2, retained.aggregateWrites.size)
                        val session = retained.aggregateWrites.single { it.key == sessionKey }
                        val movement = retained.aggregateWrites.single { it.key == movementKey }
                        for ((after, row) in listOf(session to originalSession, movement to originalMovement)) {
                            assertEquals(Revision(row.revision), after.storageGeneration)
                            assertEquals(row.schema, after.schema)
                            assertContentEquals(row.payload, after.payload.copyBytes())
                            assertEquals(PayloadDigest(row.payloadHash), after.payloadDigest)
                        }
                        assertEquals(Revision(2), session.storageGeneration)
                        assertEquals(Revision(1), movement.storageGeneration)
                        val historicalCash = SalesSnapshotCodec.decodeCashSession(session.payload.copyBytes().decodeToString(),
                            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, TEST_SCOPE, f.sessionId,
                                SalesStorageRevision.toDomain(session.storageGeneration)))
                        assertEquals(money(1_100), historicalCash.expectedCash)
                        val historicalMovement = CashNonSaleMovementCodec.decode(movement.payload, TEST_SCOPE,
                            testId(520), movement.storageGeneration)
                        assertEquals(request.operationId, historicalMovement.operationId)
                        assertEquals(retained.semanticDigest, historicalMovement.semanticDigest)
                        assertEquals(money(100), historicalMovement.delta)
                        val claim = retained.claimChanges.single()
                        assertEquals(RetainedClaimAction.ACQUIRE, claim.action)
                        assertEquals(CashNonSaleStoreProfile.cashSourceClaim(testId(520)), claim.key)
                        assertEquals(f.sessionId, claim.ownerId)
                        assertEquals(f.sessionId, view.claim(claim.key))
                        assertTrue(retained.jobs.isEmpty()) // Store-owned journal upload is not a requested job.
                        val current = assertNotNull(view.aggregate(sessionKey))
                        assertEquals(Revision(3), current.revision)
                        assertNotEquals(current.revision, session.storageGeneration)
                        assertNotEquals(current.payload, session.payload)
                        assertEquals(2L, assertNotNull(f.kernel.database.facts().branch(TEST_SCOPE.storageKey())).sequence)
                    } finally { view.close() }
                }
            }
            assertEquals(money(1_075), f.session().expectedCash)
            f.counts(2, 2)
        } finally { f.close() }
    }

    @Test fun typedPublicPayInCommitsExactCashProjectionResultJournalAndOutboxTogether() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed()
            val request = f.request(amount = 9_007_199_254_740_993)
            val result = recorded(f.store().execute(testPrincipal(), request), true)
            val dto = assertIs<CashNonSaleResultV1.Applied>(CashNonSaleResultCodec.decode(result.result))
            assertEquals(money(9_007_199_254_741_993), dto.expectedCash)
            assertEquals(Revision(1), dto.domainRevision)
            assertEquals(dto.expectedCash, f.session().expectedCash)
            val row = assertNotNull(f.row(CashNonSaleStoreProfile.movementKey(testId(520))))
            val fact = CashNonSaleMovementCodec.decode(StoredBytes(row.payload), TEST_SCOPE, testId(520), Revision(row.revision))
            assertEquals(request.operationId, fact.operationId)
            assertEquals(TEST_ACTOR, fact.originalActorId)
            assertEquals(f.drawerId, fact.drawerId)
            assertEquals(money(9_007_199_254_740_993), fact.delta)
            assertEquals(semanticDigest(TEST_SCOPE, TEST_ACTOR, request.command, request.payload), fact.semanticDigest)
            assertEquals(Revision(0), fact.previousDomainRevision)
            assertEquals(Revision(1), fact.domainRevision)
            f.counts(1, 1)
        } finally { f.close() }
    }

    @Test fun operatingExpenseConsumesRealAvailableCashAndNeverChangesCustomerTotals() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed()
            recorded(f.store().execute(testPrincipal(), f.request(kind = NonSaleCashKind.OPERATING_EXPENSE, amount = 150)), true)
            val cash = f.session()
            assertEquals(money(850), cash.expectedCash)
            assertEquals(money(150), cash.totals.payOuts)
            assertEquals(money(0), cash.totals.received)
            assertEquals(money(0), cash.totals.refunds)
            assertEquals(money(0), cash.totals.externalCollections)
            f.counts(1, 1)
        } finally { f.close() }
    }

    @Test fun reopenReplayKeepsOriginalBytesAndNewPayloadConflictsBeforeReevaluatingChangedCash() = runTest {
        val first = CashDatabaseFixture(); first.seed()
        val request = first.request()
        val original = recorded(first.store().execute(testPrincipal(), request), true)
        first.close()
        val f = CashDatabaseFixture(first.path)
        try {
            recorded(f.store().execute(testPrincipal(), f.request(Revision(1), source = 521, operation = 531,
                kind = NonSaleCashKind.OPERATING_EXPENSE, amount = 200)), true)
            val replay = recorded(f.store().execute(testPrincipal(), request), true)
            assertTrue(replay.replayed)
            assertContentEquals(original.result.copyBytes(), replay.result.copyBytes())
            assertEquals(original.sequence, replay.sequence)
            assertEquals(original.authorityEpoch, replay.authorityEpoch)
            assertEquals(money(900), f.session().expectedCash)
            assertIs<MutationReply.IdentityConflict>(f.store().execute(testPrincipal(), f.request(amount = 101)))
            f.counts(2, 2)
        } finally { f.close() }
    }

    @Test fun freshOperationCannotReuseCashSourceEvenWithCorrectNewDomainRevision() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed(); recorded(f.store().execute(testPrincipal(), f.request()), true)
            val reply = recorded(f.store().execute(testPrincipal(), f.request(Revision(1), operation = 531)), false)
            assertEquals(SalesRule.DUPLICATE_SOURCE_ALLOCATION,
                assertIs<CashNonSaleResultV1.DomainRejected>(CashNonSaleResultCodec.decode(reply.result)).rule)
            assertEquals(money(1_100), f.session().expectedCash)
            f.counts(2, 1)
        } finally { f.close() }
    }

    @Test fun realThreadRequestsShareTheSessionCasAndCannotDoublePost() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed(); val principal = testPrincipal()
            val ready = Channel<Unit>(2)
            val start = CompletableDeferred<Unit>()
            val jobs = listOf(530, 531).map { operation -> async(Dispatchers.Default) {
                ready.send(Unit)
                start.await()
                f.store().execute(principal, f.request(operation = operation,
                    kind = NonSaleCashKind.OPERATING_EXPENSE, amount = 800))
            } }
            repeat(2) { ready.receive() }
            start.complete(Unit)
            val replies = jobs.awaitAll().map { assertIs<MutationReply.Recorded>(it) }
            ready.close()
            // Both requests reach one real Room writer. The loser normally fails
            // session revision before source uniqueness; this is NOT an isolated
            // cross-session source-claim race or an assertion of simultaneous IO.
            assertEquals(1, replies.count { it.committed })
            assertEquals(1, replies.count { !it.committed })
            assertEquals(money(200), f.session().expectedCash)
            f.counts(2, 1)
        } finally { f.close() }
    }

    @Test fun wrongCustodianCannotMoveCashEvenWithAnAnyOwnerGrantAndRevocationBlocksReplay() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed(); val request = f.request()
            assertEquals(DeferredReason.AUTHORIZATION_DENIED,
                assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(testId(50)), request)).reason)
            f.ownerAccess = OwnerAccess.ANY_OWNER
            // Exact actual-custodian validation rejects a broad manager grant;
            // this route cannot transfer attribution or become a cash takeover.
            assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(testId(50)), request))
            f.counts(0, 0)
            recorded(f.store().execute(testPrincipal(), request), true)
            f.kernel.setAuth("revoked")
            assertEquals(DeferredReason.AUTHORIZATION_DENIED,
                assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(), request)).reason)
            assertEquals(money(1_100), f.session().expectedCash)
            f.counts(1, 1)
        } finally { f.close() }
    }

    @Test fun insufficientCashRevisionConflictAndMissingSessionAreDurableRejectionsWithoutMovements() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed()
            val insufficient = recorded(f.store().execute(testPrincipal(), f.request(kind = NonSaleCashKind.OPERATING_EXPENSE, amount = 1_001)), false)
            assertEquals(SalesRule.INSUFFICIENT_DRAWER_CASH,
                assertIs<CashNonSaleResultV1.DomainRejected>(CashNonSaleResultCodec.decode(insufficient.result)).rule)
            val stale = recorded(f.store().execute(testPrincipal(), f.request(Revision(9), operation = 531)), false)
            assertEquals(SalesRule.REVISION_CONFLICT,
                assertIs<CashNonSaleResultV1.DomainRejected>(CashNonSaleResultCodec.decode(stale.result)).rule)
            val missing = recorded(f.store().execute(testPrincipal(), f.request(operation = 532, target = testId(599))), false)
            assertIs<CashNonSaleResultV1.SessionMissing>(CashNonSaleResultCodec.decode(missing.result))
            assertEquals(money(1_000), f.session().expectedCash)
            assertNull(f.row(CashNonSaleStoreProfile.movementKey(testId(520))))
            f.counts(3, 0)
        } finally { f.close() }
    }

    @Test fun inventorySettlementCannotUseCashMovePermissionAsSupplierSettlementAuthority() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed()
            val request = f.request(kind = NonSaleCashKind.INVENTORY_SETTLEMENT)
            val reply = recorded(f.store().execute(testPrincipal(), request), false)
            assertEquals(CashNonSaleUnsupported.INVENTORY_SETTLEMENT_SOURCE_REQUIRED,
                assertIs<CashNonSaleResultV1.Unsupported>(CashNonSaleResultCodec.decode(reply.result)).reason)
            assertEquals(money(1_000), f.session().expectedCash)
            assertNull(f.row(CashNonSaleStoreProfile.movementKey(testId(520))))
            assertTrue(recorded(f.store().execute(testPrincipal(), request), false).replayed)
            f.counts(1, 0)
        } finally { f.close() }
    }

    @Test fun closingCutAndActualPendingTransferCannotBeBypassedByNonSaleMovement() = runTest {
        for (closing in listOf(false, true)) {
            val f = CashDatabaseFixture()
            try {
                val open = f.seed()
                val protected = if (closing) {
                    val work = ClosingWorkReadSet(TEST_SCOPE, TEST_ACTOR, Revision(0), emptyList(), emptyList())
                    CashSessions.beginClosing(f.command(SecurityAction.CASH_CLOSE, open.id, open.revision),
                        open, open.revision, work, work.revision).value
                } else CashSessions.dropToSafe(f.command(SecurityAction.CASH_TRANSFER, open.id, open.revision, money(100)),
                    open, open.revision, testId(560), testId(561), money(100),
                    CashierEvidence(testId(562), TEST_ACTOR, TEST_TIME.value)).value.session
                f.seedProtectedSession(protected)
                val reply = recorded(f.store().execute(testPrincipal(), f.request(protected.revision)), false)
                val result = CashNonSaleResultCodec.decode(reply.result)
                if (closing) assertIs<CashNonSaleResultV1.DomainRejected>(result)
                else assertEquals(CashNonSaleUnsupported.PENDING_FINANCIAL_OR_CUSTODY_WORK,
                    assertIs<CashNonSaleResultV1.Unsupported>(result).reason)
                assertEquals(protected.expectedCash, f.session().expectedCash)
                assertEquals(protected.revision, f.session().revision)
                f.counts(1, 0)
            } finally { f.close() }
        }
    }

    @Test fun everyKernelFaultBoundaryRollsBackOrRecoversTheSameTypedCashEffectAfterReopen() = runTest {
        for (point in StoreFaultPoint.entries) {
            val first = CashDatabaseFixture(); first.seed()
            val request = first.request()
            var fired = 0
            val reply = first.store(StoreFaultProbe { if (it == point) {
                fired++
                error("Synthetic typed cash fault")
            } })
                .execute(testPrincipal(), request)
            assertEquals(1, fired, "Requested fault boundary must be reached: $point")
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(reply).reason, point.name)
            first.close()
            val f = CashDatabaseFixture(first.path)
            try {
                val committed = point == StoreFaultPoint.AFTER_COMMIT
                f.counts(if (committed) 1 else 0, if (committed) 1 else 0)
                assertEquals(money(if (committed) 1_100 else 1_000), f.session().expectedCash, point.name)
                val movement = f.row(CashNonSaleStoreProfile.movementKey(testId(520)))
                if (committed) {
                    assertNotNull(movement)
                    assertEquals(sha256(movement.payload).value, movement.payloadHash)
                    val projection = CashNonSaleMovementCodec.decode(StoredBytes(movement.payload), TEST_SCOPE,
                        testId(520), Revision(movement.revision))
                    assertEquals(request.operationId, projection.operationId)
                    assertEquals(money(100), projection.delta)
                    f.kernel.database.useWriterConnection {
                        val claim = assertNotNull(f.kernel.database.facts().claim(TEST_SCOPE.storageKey(),
                            CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE, testId(520).value))
                        assertEquals(f.sessionId.value, claim.ownerId)
                    }
                } else assertNull(movement)
                val recovered = recorded(f.store().execute(testPrincipal(), request), true)
                assertEquals(committed, recovered.replayed, point.name)
                assertEquals(money(1_100), f.session().expectedCash)
                f.counts(1, 1)
            } finally { f.close() }
        }
    }

    @Test fun recoveryHoldAllowsAuthorizedPositiveResultButBlocksFreshCashRights() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed(); val request = f.request()
            recorded(f.store().execute(testPrincipal(), request), true)
            f.kernel.restoreHold()
            assertTrue(recorded(f.store().execute(testPrincipal(), request), true).replayed)
            val newWork = f.request(Revision(1), source = 521, operation = 531)
            assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED,
                assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(), newWork)).reason)
            f.counts(1, 1)
        } finally { f.close() }
    }

    @Test fun unqualifiedWriterAndUnavailableAuthRemainClosedWithARegisteredRealHandler() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed(); f.kernel.writerVerification = RuntimeVerification.UNAVAILABLE
            assertEquals(DeferredReason.WRITER_NOT_QUALIFIED,
                assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(), f.request())).reason)
            val denied = AtomicMasterStore(f.kernel.database, TEST_SCOPE, TEST_AUTHORITY, listOf(f.handler),
                UnavailableTransactionAuthorization, f.kernel.runtime, MasterClock { TEST_TIME })
            assertEquals(DeferredReason.AUTHORIZATION_DENIED,
                assertIs<MutationReply.Deferred>(denied.execute(testPrincipal(), f.request())).reason)
            f.counts(0, 0)
        } finally { f.close() }
    }

    @Test fun corruptProtectedCashNeverDefaultsToAnOpenOrZeroBalance() = runTest {
        val f = CashDatabaseFixture()
        try {
            f.seed()
            f.kernel.database.useWriterConnection { c ->
                requireDurableWriter(c)
                c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val row = assertNotNull(f.kernel.database.facts().aggregate(TEST_SCOPE.storageKey(),
                        CashNonSaleStoreProfile.SESSION_KIND, f.sessionId.value))
                    val corrupt = "{}".encodeToByteArray()
                    check(f.kernel.database.facts().replaceAggregate(row.scope, row.kind, row.id, row.revision,
                        row.revision + 1, row.schema, corrupt, sha256(corrupt).value) == 1)
                }
            }
            assertEquals(DeferredReason.STORAGE_UNCERTAIN,
                assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(), f.request(Revision(1)))).reason)
            f.counts(0, 0)
        } finally { f.close() }
    }
}

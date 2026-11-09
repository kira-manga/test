package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial public-kernel tests against the file-backed StoreFixture.
 * ALL accepting identity/runtime adapters are SYNTHETIC: these tests do not establish
 * physical writer exclusion, authentic recovery coverage, usable backups, or login/key
 * qualification. The counter is not a stock/cash domain handler or typed production schema.
 */
class StoreRecoveryAdversarialTest {
    @Test fun restoredRightsCannotBeSpentByChangingOperationActorOrResourceIds() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val store = f.store()
            val owner = testPrincipal()
            val original = f.request(200, CounterInput(id = testId(201), delta = 4, claim = "original-custody"))
            val committed = assertIs<MutationReply.Recorded>(store.execute(owner, original))
            assertTrue(committed.committed)
            val unchanged = f.commandCounts()
            f.restoreHold()

            for (actor in listOf(TEST_ACTOR, testId(202))) {
                val principal = testPrincipal(actor)
                for (index in 0 until 8) {
                    val offset = if (actor == TEST_ACTOR) 0 else 8
                    val target = testId(240 + offset + index)
                    val request = f.request(220 + offset + index,
                        CounterInput(id = target, claim = "new-custody-$offset-$index"))
                    val reply = assertIs<MutationReply.Deferred>(store.execute(principal, request))
                    assertEquals(request.operationId, reply.operationId)
                    assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED, reply.reason)
                    f.assertCounterAbsent(target)
                }
            }

            // Retained facts are recoverable; that does not release any copied mutable right.
            val recovered = assertIs<LookupReply.Found>(store.lookup(owner, f.identity(original))).result
            assertEquals(committed.result, recovered.result)
            assertEquals(TEST_ACTOR, recovered.originalActorId)
            assertTrue(assertIs<MutationReply.Recorded>(store.execute(owner, original)).replayed)
            f.assertCounter(testId(201), "4", Revision(1))
            assertEquals(unchanged, f.commandCounts())
        } finally { f.close() }
    }

    @Test fun missingAuthorizationFootprintFailsBeforeHandlerEvaluationWithoutCachingRejection() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val principal = testPrincipal()
            val request = f.request(270)
            for (footprint in listOf(emptySet(), setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, testId(10))))) {
                var evaluated = false
                val incomplete = object : MasterCommandHandler by f.handler {
                    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
                        val prepared = f.handler.prepare(normalized, view, context)
                        return object : PreparedMutation() {
                            override val requirement = prepared.requirement
                            override val affectedResources = footprint
                            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                                evaluated = true
                                return prepared.evaluate(authorization, recordedAt)
                            }
                        }
                    }
                }
                val reply = assertIs<MutationReply.Deferred>(f.adversarialStore(handler = incomplete).execute(principal, request))
                assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED, reply.reason)
                assertFalse(evaluated)
                f.assertNoCommandRows()
                f.assertCounterAbsent(testId(10))
            }

            // Fixing a server handler must not require inventing a different operation ID.
            assertIs<LookupReply.AbsentAtCurrentWriter>(f.store().lookup(principal, f.identity(request)))
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(principal, request)).committed)
            assertEquals(1L, f.count("operation_result"))
        } finally { f.close() }
    }

    @Test fun committedAndRejectedFactsRemainKnownWhileMissingHistoryAndWriterQualificationFailClosed() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val principal = testPrincipal()
            val store = f.store()
            val committedRequest = f.request(300, CounterInput(id = testId(301), delta = 5, claim = "retained-custody"))
            val rejectedRequest = f.request(302, CounterInput(id = testId(303), reject = true))
            val committed = assertIs<MutationReply.Recorded>(store.execute(principal, committedRequest))
            val rejected = assertIs<MutationReply.Recorded>(store.execute(principal, rejectedRequest))
            assertTrue(committed.committed)
            assertFalse(rejected.committed)
            val unchanged = f.commandCounts()
            f.restoreHold()

            val unavailableCases = listOf(
                RuntimeVerification.UNAVAILABLE to RuntimeVerification.UNAVAILABLE,
                RuntimeVerification.REFUSED to RuntimeVerification.REFUSED,
                RuntimeVerification.VERIFIED to RuntimeVerification.UNAVAILABLE,
                RuntimeVerification.VERIFIED to RuntimeVerification.REFUSED,
            )
            for ((index, qualification) in unavailableCases.withIndex()) {
                f.writerVerification = qualification.first
                f.absenceVerification = qualification.second
                for ((request, original) in listOf(committedRequest to committed, rejectedRequest to rejected)) {
                    val found = assertIs<LookupReply.Found>(store.lookup(principal, f.identity(request))).result
                    val replay = assertIs<MutationReply.Recorded>(store.execute(principal, request))
                    for (result in listOf(found, replay)) {
                        assertTrue(result.replayed)
                        assertEquals(original.committed, result.committed)
                        assertEquals(original.code, result.code)
                        assertEquals(original.result, result.result)
                        assertEquals(original.sequence, result.sequence)
                        assertEquals(original.recordedAt, result.recordedAt)
                        assertEquals(TEST_ACTOR, result.originalActorId)
                    }
                }
                val missing = f.request(320 + index, CounterInput(id = testId(330 + index)))
                val reason = if (qualification.first == RuntimeVerification.VERIFIED) DeferredReason.HISTORY_INCOMPLETE
                    else DeferredReason.WRITER_NOT_QUALIFIED
                assertEquals(reason, assertIs<LookupReply.Deferred>(store.lookup(principal, f.identity(missing))).reason)
                assertEquals(reason, assertIs<MutationReply.Deferred>(store.execute(principal, missing)).reason)
                f.assertCounterAbsent(testId(330 + index))
                assertEquals(unchanged, f.commandCounts())
            }
            assertEquals(2, f.handler.prepareCount)
            f.assertCounter(testId(301), "5", Revision(1))
        } finally { f.close() }
    }

    @Test fun publicFactoryDefaultsNeverAdmitNewWorkOrNegativeExistence() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val principal = testPrincipal()
            for (configuration in 0..2) {
                // Deliberately omit the relevant argument, testing the public defaults.
                val store = when (configuration) {
                    0 -> openLocalMasterStore(f.path, TEST_SCOPE, TEST_AUTHORITY, listOf(f.handler))
                    1 -> openLocalMasterStore(f.path, TEST_SCOPE, TEST_AUTHORITY, listOf(f.handler), runtime = f.runtime)
                    else -> openLocalMasterStore(f.path, TEST_SCOPE, TEST_AUTHORITY, listOf(f.handler), authorization = f.auth)
                }
                try {
                    val request = f.request(350 + configuration)
                    val expected = if (configuration == 2) DeferredReason.WRITER_NOT_QUALIFIED else DeferredReason.AUTHORIZATION_DENIED
                    assertEquals(expected, assertIs<MutationReply.Deferred>(store.execute(principal, request)).reason)
                    assertEquals(expected, assertIs<LookupReply.Deferred>(store.lookup(principal, f.identity(request))).reason)
                } finally { store.close() }
            }
            assertEquals(0, f.handler.prepareCount)
            f.assertNoCommandRows()
            f.assertCounterAbsent(testId(10))
        } finally { f.close() }
    }

    @Test fun storedOwnerWinsOverSpoofedSearchOwnerAndRevocationPrecedesEveryOutcomeDisclosure() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val store = f.store()
            val owner = testPrincipal()
            val stranger = testPrincipal(testId(450))
            val request = f.request(400, CounterInput(id = testId(401), delta = 9))
            val original = assertIs<MutationReply.Recorded>(store.execute(owner, request))
            val unchanged = f.commandCounts()
            val matching = f.identity(request)
            val changed = matching.copy(semanticSha256 = "f".repeat(64))
            val spoofedOwner = f.identity(request, stranger.actorId)
            val missing = f.request(402, CounterInput(id = testId(403)))

            f.setAuth("no-grants")
            assertEquals(original.result, assertIs<LookupReply.Found>(store.lookup(owner, matching)).result.result)
            assertTrue(assertIs<MutationReply.Recorded>(store.execute(owner, request)).replayed)
            assertIs<LookupReply.IdentityConflict>(store.lookup(owner, changed))
            for (query in listOf(matching, changed, spoofedOwner)) {
                assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(store.lookup(stranger, query)).reason)
            }
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(store.execute(stranger, request)).reason)
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(store.execute(owner, missing)).reason)

            for (state in listOf("revoked", "disabled")) {
                f.setAuth(state)
                for (query in listOf(matching, changed, spoofedOwner, f.identity(missing))) {
                    assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(store.lookup(owner, query)).reason, state)
                }
                for (candidate in listOf(request, missing, f.request(400, CounterInput(id = testId(401), delta = 10)))) {
                    assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(store.execute(owner, candidate)).reason, state)
                }
            }
            assertEquals(unchanged, f.commandCounts())
            // The still-enabled owner reaches prepare once for the fresh command,
            // then fails its action grant; revoked/disabled sessions never reach it.
            assertEquals(2, f.handler.prepareCount)
            f.assertCounter(testId(401), "9", Revision(1))
            f.assertCounterAbsent(testId(403))
        } finally { f.close() }
    }

    @Test fun independentlyEnabledSupervisorCanRecoverDisabledOwnersResultButCannotAdoptItsOperation() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val owner = testPrincipal()
            val supervisorId = testId(480)
            val supervisor = testPrincipal(supervisorId)
            val request = f.request(460, CounterInput(id = testId(461), delta = 7))
            val original = assertIs<MutationReply.Recorded>(f.store().execute(owner, request))

            // TEST ONLY: the original account flags remain read from the fixture's
            // durable auth row; this models a separately active supervisor account.
            // It is deliberately NOT an implementation of production account storage.
            val separateSupervisor = TransactionAuthorizationSource { principal, view ->
                val current = f.auth.current(principal, view)
                if (current == null || principal.subjectId != supervisorId) current else AuthorizationSnapshot(
                    current.subjectId, current.kind, current.scope, current.authenticationId, current.generation,
                    current.deviceId, current.admissionRevision, current.policyRevision,
                    true, true, current.deviceAdmitted, AuthenticationPurpose.INTERACTIVE,
                    current.grants.filter { it.permission == Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM) },
                )
            }
            val store = f.adversarialStore(authorization = separateSupervisor)
            f.setAuth("disabled")
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(store.lookup(owner, f.identity(request))).reason)
            val recovered = assertIs<LookupReply.Found>(store.lookup(supervisor, f.identity(request))).result
            assertEquals(TEST_ACTOR, recovered.originalActorId)
            assertEquals(original.result, recovered.result)
            assertEquals(original.recordedAt, recovered.recordedAt)
            assertIs<MutationReply.IdentityConflict>(store.execute(supervisor, request))

            val audits = f.supervisorAttributions()
            assertEquals(2, audits.size) // Successful read and denied attempt to adopt its identity.
            for (audit in audits) {
                assertEquals(supervisorId, audit.readingActor)
                assertEquals(TEST_ACTOR, audit.originalActor)
                assertEquals(request.operationId, audit.operationId)
                assertEquals(Revision(1), audit.policyRevision)
            }
            f.setAuth("no-grants")
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(store.lookup(supervisor, f.identity(request))).reason)
            assertEquals(audits, f.supervisorAttributions())
            assertEquals(1L, f.count("operation_result"))
            assertEquals(1L, f.count("journal_envelope"))
            assertEquals(2L, f.count("outbox_intent"))
            f.assertCounter(testId(461), "7", Revision(1))
        } finally { f.close() }
    }

    @Test fun unavailableSupervisorAuditCannotDiscloseResultConflictOrAbsence() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val store = f.store()
            val owner = testPrincipal()
            val supervisor = testPrincipal(testId(520))
            val request = f.request(500, CounterInput(id = testId(501)))
            val original = assertIs<MutationReply.Recorded>(store.execute(owner, request))
            val unchanged = f.commandCounts()
            val matching = f.identity(request)
            val changed = matching.copy(semanticSha256 = "e".repeat(64))
            val missing = f.identity(f.request(502, CounterInput(id = testId(503))))
            f.database.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER qualification_refuse_supervisor_audit BEFORE INSERT ON audit_event WHEN NEW.kind='result.supervisor-read' BEGIN SELECT RAISE(ABORT, 'synthetic audit unavailable'); END") { it.step() }
            }
            try {
                for (query in listOf(matching, changed, missing)) {
                    val refusal = assertIs<LookupReply.Deferred>(store.lookup(supervisor, query))
                    assertTrue(refusal.reason == DeferredReason.AUTHORIZATION_DENIED || refusal.reason == DeferredReason.STORAGE_UNCERTAIN)
                }
                val refusal = assertIs<MutationReply.Deferred>(store.execute(supervisor, request))
                assertTrue(refusal.reason == DeferredReason.AUTHORIZATION_DENIED || refusal.reason == DeferredReason.STORAGE_UNCERTAIN)
                assertEquals(original.result, assertIs<LookupReply.Found>(store.lookup(owner, matching)).result.result)
                assertEquals(unchanged, f.commandCounts())
            } finally {
                f.database.useWriterConnection { connection ->
                    connection.usePrepared("DROP TRIGGER qualification_refuse_supervisor_audit") { it.step() }
                }
            }
            assertIs<LookupReply.Found>(store.lookup(supervisor, matching))
            assertEquals(1, f.supervisorAttributions().size)
            assertEquals(1L, f.count("operation_result"))
            f.assertCounter(testId(501), "1", Revision(1))
        } finally { f.close() }
    }

    @Test fun revocationCommittedAheadOfBorrowedWriterPreventsCachedPositiveResultRecovery() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val store = f.store()
            val principal = testPrincipal()
            val request = f.request(550, CounterInput(id = testId(551), delta = 3))
            assertIs<MutationReply.Recorded>(store.execute(principal, request))
            val unchanged = f.commandCounts()
            val revocationWritten = CompletableDeferred<Unit>()
            val allowRevocationCommit = CompletableDeferred<Unit>()
            val revoke = async(Dispatchers.Default) {
                f.database.useWriterConnection { connection ->
                    requireDurableWriter(connection)
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        val row = requireNotNull(f.database.facts().aggregate(TEST_SCOPE.storageKey(), AUTH_KEY.kind, AUTH_KEY.id.value))
                        val revoked = "revoked".encodeToByteArray()
                        check(f.database.facts().replaceAggregate(row.scope, row.kind, row.id, row.revision, Revision(row.revision).next().value,
                            row.schema, revoked, sha256(revoked).value) == 1)
                        revocationWritten.complete(Unit)
                        allowRevocationCommit.await()
                    }
                }
            }
            revocationWritten.await()
            // These calls originate outside the transaction's coroutine context and
            // cannot borrow its writer or disclose a cached result while it is held.
            val lookup = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.lookup(principal, f.identity(request)) }
            val replay = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.execute(principal, request) }
            try {
                assertFalse(lookup.isCompleted)
                assertFalse(replay.isCompleted)
            } finally { allowRevocationCommit.complete(Unit) }
            revoke.await()
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(lookup.await()).reason)
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(replay.await()).reason)
            assertEquals(unchanged, f.commandCounts())
            assertEquals(1, f.handler.prepareCount)
            f.assertCounter(testId(551), "3", Revision(1))
        } finally { f.close() }
    }

    @Test fun absenceIsOnlyAnObservationAndNeverCachesAnOperationResult() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val store = f.store()
            val principal = testPrincipal()
            val request = f.request(580, CounterInput(id = testId(581), delta = 6))
            val observation = assertIs<LookupReply.AbsentAtCurrentWriter>(store.lookup(principal, f.identity(request)))
            assertEquals(request.operationId, observation.operationId)
            f.assertNoCommandRows()
            val committed = assertIs<MutationReply.Recorded>(store.execute(principal, request))
            assertTrue(committed.committed)
            assertFalse(committed.replayed)
            f.absenceVerification = RuntimeVerification.UNAVAILABLE
            val found = assertIs<LookupReply.Found>(store.lookup(principal, f.identity(request))).result
            val replay = assertIs<MutationReply.Recorded>(store.execute(principal, request))
            assertEquals(committed.result, found.result)
            assertEquals(committed.sequence, found.sequence)
            assertTrue(replay.replayed)
            assertEquals(1, f.handler.prepareCount)
            assertEquals(1L, f.count("operation_result"))
            assertEquals(1L, f.count("journal_envelope"))
            assertEquals(1L, f.count("audit_event"))
            assertEquals(2L, f.count("outbox_intent"))
            f.assertCounter(testId(581), "6", Revision(1))
        } finally { f.close() }
    }

    @Test fun deadlineReachedAtFinalSuspendingBoundaryRollsBackInsteadOfAcknowledgingExpiredWork() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val principal = testPrincipal()
            val deadline = UtcInstant.parseCanonical("2026-10-23T12:00:01Z")
            val request = f.request(600, CounterInput(id = testId(601), delta = 2, claim = "deadline-custody", deadline = deadline))
            val slowCommit = f.store(StoreFaultProbe { point ->
                if (point == StoreFaultPoint.BEFORE_COMMIT) {
                    f.clockValue = deadline
                    yield()
                }
            })
            val unknown = assertIs<MutationReply.Deferred>(slowCommit.execute(principal, request))
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, unknown.reason)
            f.assertNoCommandRows()
            f.assertCounterAbsent(testId(601))
            assertIs<LookupReply.AbsentAtCurrentWriter>(f.store().lookup(principal, f.identity(request)))

            // The same ID may now record its conclusive expired-precondition rejection;
            // the failed transaction itself did not persist either success or rejection.
            val rejected = assertIs<MutationReply.Recorded>(f.store().execute(principal, request))
            assertFalse(rejected.committed)
            assertEquals("precondition.expired", rejected.code)
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(principal, request)).replayed)
            assertEquals(1L, f.count("operation_result"))
            assertEquals(1L, f.count("journal_envelope"))
            assertEquals(1L, f.count("audit_event"))
            assertEquals(1L, f.count("outbox_intent"))
            assertEquals(0L, f.count("unique_claim"))
            f.assertCounterAbsent(testId(601))
        } finally { f.close() }
    }
}

private fun StoreFixture.adversarialStore(
    handler: MasterCommandHandler = this.handler,
    authorization: TransactionAuthorizationSource = auth,
): AtomicMasterStore = AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY, listOf(handler), authorization,
    runtime, MasterClock { clockValue })

private val commandTables = listOf("operation_result", "journal_envelope", "outbox_intent", "audit_event", "unique_claim")

private suspend fun StoreFixture.commandCounts(): Map<String, Long> {
    val values = mutableMapOf<String, Long>()
    for (table in commandTables) values[table] = count(table)
    return values
}

private suspend fun StoreFixture.assertNoCommandRows() {
    for (table in commandTables) assertEquals(0L, count(table), table)
}

private suspend fun StoreFixture.assertCounterAbsent(id: DomainId) {
    assertNull(database.facts().aggregate(TEST_SCOPE.storageKey(), "qualification.counter", id.value))
}

private suspend fun StoreFixture.assertCounter(id: DomainId, value: String, revision: Revision) {
    val row = requireNotNull(database.facts().aggregate(TEST_SCOPE.storageKey(), "qualification.counter", id.value))
    assertEquals(value, row.payload.decodeToString())
    assertEquals(revision.value, row.revision)
    assertEquals(sha256(row.payload).value, row.payloadHash)
}

private data class AuditAttribution(
    val readingActor: DomainId,
    val originalActor: DomainId,
    val operationId: OperationId,
    val policyRevision: Revision,
)

private suspend fun StoreFixture.supervisorAttributions(): List<AuditAttribution> = database.useWriterConnection { connection ->
    connection.usePrepared("SELECT actorId,originalActorId,operationId,policyRevision FROM audit_event WHERE kind='result.supervisor-read' ORDER BY id") { statement ->
        val events = mutableListOf<AuditAttribution>()
        while (statement.step()) events += AuditAttribution(DomainId(statement.getText(0)), DomainId(statement.getText(1)),
            OperationId(statement.getText(2)), Revision(statement.getLong(3)))
        events
    }
}

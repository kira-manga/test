package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.throwSQLiteException
import com.ukkera.pos.security.sha256
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * SYNTHETIC IO-error reporting at Room's actual outer COMMIT step, not power loss,
 * an fsync failure, a real disk fault, or qualification of physical device storage.
 * The counter and accepting identity/runtime adapters are also test-only.
 */
class StoreCommitFailureTest {
    @Test fun ioErrorBeforeUnderlyingCommitRollsBackWithoutAcknowledgingAnyOutcome() = runTest {
        exerciseCommitFailure(CommitFailureCut.BEFORE_SQLITE_COMMIT, reject = false)
    }

    @Test fun rejectionWhoseCommitFailsIsNotExposedAsADurableBusinessRejection() = runTest {
        exerciseCommitFailure(CommitFailureCut.BEFORE_SQLITE_COMMIT, reject = true)
    }

    @Test fun appliedCommitReportingIoErrorReopensAndReplaysExactlyOneCompleteEffect() = runTest {
        exerciseCommitFailure(CommitFailureCut.AFTER_SQLITE_COMMIT, reject = false)
    }

    @Test fun appliedRejectionCommitReportingIoErrorReopensAsOneDurableRejection() = runTest {
        exerciseCommitFailure(CommitFailureCut.AFTER_SQLITE_COMMIT, reject = true)
    }

    private suspend fun exerciseCommitFailure(cut: CommitFailureCut, reject: Boolean) {
        val seed = StoreFixture()
        val originalFacts = try {
            seed.seed()
            seed.database.commitFacts()
        } finally { seed.close() }
        val input = CounterInput(id = testId(651), delta = 17, claim = "commit-custody", reject = reject)
        // Retain the exact request, actor and ID across the uncertain reply and reopens.
        val request = seed.request(650, input)
        val principal = testPrincipal()
        val driver = CommitFaultDriver(cut)
        val database = buildControlledMasterRoomFixture(
            Room.databaseBuilder<MasterRoomDatabase>(seed.path.toString(), MasterRoomConstructor::initialize),
            driver,
        )
        val afterFailure = try {
            // Creation and connection configuration must complete without injected faults.
            database.useWriterConnection { requireDurableWriter(it) }
            val store = AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY, listOf(seed.handler),
                seed.auth, seed.runtime, MasterClock { seed.clockValue }, StoreFaultProbe { point ->
                    if (point == StoreFaultPoint.BEFORE_COMMIT) driver.arm()
                })
            val uncertain = assertIs<MutationReply.Deferred>(store.execute(principal, request))
            assertEquals(request.operationId, uncertain.operationId)
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, uncertain.reason)
            assertEquals(1, seed.handler.prepareCount)
            driver.assertInjectedAtExpectedBoundary()
            // Check cleanup BEFORE close: closing SQLite must not be what rescues an
            // uncommitted transaction that leaked back into the live Room pool.
            database.useWriterConnection { connection ->
                assertFalse(connection.inTransaction())
                requireDurableWriter(connection)
            }
            database.commitFacts().also { facts ->
                if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertEquals(originalFacts, facts)
                else assertNotEquals(originalFacts, facts)
            }
        } finally { database.close() }

        val reopened = StoreFixture(seed.path)
        val committedFacts = try {
            // A newly constructed, ordinary driver/Room/store has no fault wrapper or
            // in-memory result cache from the first attempt.
            assertEquals(afterFailure, reopened.database.commitFacts())
            val store = reopened.store()
            val lookup = store.lookup(principal, reopened.identity(request))
            if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) {
                assertEquals(request.operationId, assertIs<LookupReply.AbsentAtCurrentWriter>(lookup).operationId)
                assertEquals(originalFacts, reopened.database.commitFacts())
            } else {
                assertRecorded(assertIs<LookupReply.Found>(lookup).result, request, input, replayed = true)
                reopened.assertCompleteCommand(request, input)
                assertEquals(afterFailure, reopened.database.commitFacts())
            }

            // The before-COMMIT failure permits a first durable execution of the SAME
            // operation. The after-COMMIT failure must recover, not re-evaluate it.
            assertRecorded(assertIs<MutationReply.Recorded>(store.execute(principal, request)), request, input,
                replayed = cut == CommitFailureCut.AFTER_SQLITE_COMMIT)
            assertEquals(if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) 1 else 0, reopened.handler.prepareCount)
            reopened.assertCompleteCommand(request, input)
            val once = reopened.database.commitFacts()
            repeat(3) {
                assertRecorded(assertIs<MutationReply.Recorded>(store.execute(principal, request)), request, input, replayed = true)
            }
            assertEquals(once, reopened.database.commitFacts(), "Replay must leave every persisted fact and effect unchanged")
            once
        } finally { reopened.close() }

        val again = StoreFixture(seed.path)
        try {
            assertEquals(committedFacts, again.database.commitFacts())
            assertRecorded(assertIs<MutationReply.Recorded>(again.store().execute(principal, request)), request, input, replayed = true)
            assertEquals(0, again.handler.prepareCount)
            again.assertCompleteCommand(request, input)
            assertEquals(committedFacts, again.database.commitFacts())
        } finally { again.close() }
    }
}

internal enum class CommitFailureCut { BEFORE_SQLITE_COMMIT, AFTER_SQLITE_COMMIT }

/**
 * Room3 3.0.3 ConnectionPoolImpl ends an outer transaction with END TRANSACTION.
 * Its suspending androidx.sqlite.async.executeSQL calls prepare(...).step() on
 * SQLite 2.7.1 JVM; there is no separate async driver interface on this platform.
 * Intercept execution, not preparation (Room may cache the prepared statement).
 * The semantic hook only ARMS this one-shot fault while the command owns the
 * writer, so schema creation or asynchronous invalidation cannot consume it.
 */
internal class CommitFaultDriver(private val cut: CommitFailureCut) : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    private val armed = AtomicBoolean(false)
    private val armedOnce = AtomicBoolean(false)
    private val injections = AtomicInteger(0)
    private val completedUnderlyingCommits = AtomicInteger(0)
    private val completedRollbacksAfterFailure = AtomicInteger(0)
    private val injectedSql = AtomicReference<String?>(null)
    private val transactionActiveAtFailure = AtomicBoolean(false)

    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

    fun arm() {
        check(armedOnce.compareAndSet(false, true)) { "Commit probe must only be armed once" }
        check(armed.compareAndSet(false, true))
    }

    override fun open(fileName: String): SQLiteConnection = FaultConnection(delegate.open(fileName))

    private inner class FaultConnection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement {
            val statement = connection.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean {
                    if (sql == "END TRANSACTION" && armed.compareAndSet(true, false)) {
                        check(connection.inTransaction()) { "Probe did not reach an active outer transaction" }
                        injectedSql.set(sql)
                        if (cut == CommitFailureCut.AFTER_SQLITE_COMMIT) {
                            // SQLite has completed the real commit before the driver
                            // reports uncertainty to Room/the application.
                            check(!statement.step())
                            completedUnderlyingCommits.incrementAndGet()
                        }
                        transactionActiveAtFailure.set(connection.inTransaction())
                        injections.incrementAndGet()
                        // SQLITE_IOERR=10. This models an error report only; it does
                        // not emulate SQLite VFS/fsync or claim a damaged real disk.
                        throwSQLiteException(10, "SYNTHETIC test-only COMMIT IO uncertainty")
                    }
                    val result = statement.step()
                    if (sql == "ROLLBACK TRANSACTION" && injections.get() == 1) {
                        check(!connection.inTransaction())
                        completedRollbacksAfterFailure.incrementAndGet()
                    }
                    return result
                }
            }
        }
    }

    fun assertInjectedAtExpectedBoundary() {
        assertTrue(armedOnce.get())
        assertFalse(armed.get())
        assertEquals(1, injections.get(), "The error must come from executing the actual outer COMMIT")
        assertEquals("END TRANSACTION", injectedSql.get())
        val committed = cut == CommitFailureCut.AFTER_SQLITE_COMMIT
        assertEquals(if (committed) 1 else 0, completedUnderlyingCommits.get())
        assertEquals(!committed, transactionActiveAtFailure.get())
        assertEquals(if (committed) 0 else 1, completedRollbacksAfterFailure.get(),
            "Room must roll back a still-active transaction before returning uncertainty")
    }
}

private fun assertRecorded(reply: MutationReply.Recorded, request: MutationRequest, input: CounterInput, replayed: Boolean) {
    assertEquals(request.operationId, reply.operationId)
    assertEquals(TEST_ACTOR, reply.originalActorId)
    assertEquals(!input.reject, reply.committed)
    assertEquals(if (input.reject) "stock.insufficient" else "committed", reply.code)
    assertEquals(if (input.reject) "stock.insufficient" else input.delta.toString(), reply.result.copyBytes().decodeToString())
    assertEquals(1L, reply.sequence.value)
    assertEquals(TEST_TIME, reply.recordedAt)
    assertEquals(replayed, reply.replayed)
}

private suspend fun StoreFixture.assertCompleteCommand(request: MutationRequest, input: CounterInput) {
    assertEquals(1L, count("operation_result"))
    assertEquals(1L, count("journal_envelope"))
    assertEquals(1L, count("audit_event"))
    assertEquals(if (input.reject) 1L else 2L, count("outbox_intent"))
    assertEquals(if (input.reject) 0L else 1L, count("unique_claim"))
    val facts = database.facts()
    val operation = requireNotNull(facts.operation(TEST_SCOPE.storageKey(), request.operationId.value))
    assertEquals(if (input.reject) "stock.insufficient" else "committed", operation.outcome)
    assertEquals(TEST_ACTOR.value, operation.actorId)
    assertEquals(request.command.name, operation.command)
    assertEquals(request.command.version, operation.commandVersion)
    assertEquals(identity(request).semanticSha256, operation.digest)
    assertEquals(sha256(operation.result).value, operation.resultHash)
    assertEquals(1L, operation.sequence)
    val counter = facts.aggregate(TEST_SCOPE.storageKey(), "qualification.counter", input.id.value)
    val claim = facts.claim(TEST_SCOPE.storageKey(), "qualification.custody", requireNotNull(input.claim))
    if (input.reject) {
        assertNull(counter)
        assertNull(claim)
    } else {
        assertNotNull(counter)
        assertEquals(input.delta.toString(), counter.payload.decodeToString())
        assertEquals(1L, counter.revision)
        assertEquals("counter/v1", counter.schema)
        assertEquals(sha256(counter.payload).value, counter.payloadHash)
        assertEquals(input.id.value, assertNotNull(claim).ownerId)
    }
    val branch = requireNotNull(facts.branch(TEST_SCOPE.storageKey()))
    assertEquals(1L, branch.revision)
    assertEquals(1L, branch.sequence)
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.usePrepared("SELECT operationId,sequence,previousHash,hash,envelope FROM journal_envelope") { statement ->
            assertTrue(statement.step())
            assertEquals(request.operationId.value, statement.getText(0))
            assertEquals(1L, statement.getLong(1))
            assertEquals("0".repeat(64), statement.getText(2))
            assertEquals(sha256(statement.getBlob(4)).value, statement.getText(3))
            assertEquals(branch.journalHash, statement.getText(3))
            assertFalse(statement.step())
        }
        connection.usePrepared("SELECT operationId,kind,actorId,originalActorId,recordedAt FROM audit_event") { statement ->
            assertTrue(statement.step())
            assertEquals(request.operationId.value, statement.getText(0))
            assertEquals("command.${operation.outcome}", statement.getText(1))
            assertEquals(TEST_ACTOR.value, statement.getText(2))
            assertEquals(TEST_ACTOR.value, statement.getText(3))
            assertEquals(TEST_TIME.toString(), statement.getText(4))
            assertFalse(statement.step())
        }
        connection.usePrepared("SELECT operationId,ordinal,kind,body,bodyHash FROM outbox_intent ORDER BY ordinal") { statement ->
            assertTrue(statement.step())
            assertEquals(request.operationId.value, statement.getText(0))
            assertEquals(0L, statement.getLong(1))
            assertEquals(SYNC_JOB, statement.getText(2))
            assertEquals(sha256(statement.getBlob(3)).value, statement.getText(4))
            if (!input.reject) {
                assertTrue(statement.step())
                assertEquals(request.operationId.value, statement.getText(0))
                assertEquals(1L, statement.getLong(1))
                assertEquals("qualification.receipt", statement.getText(2))
                assertEquals(input.delta.toString(), statement.getBlob(3).decodeToString())
                assertEquals(sha256(statement.getBlob(3)).value, statement.getText(4))
            }
            assertFalse(statement.step())
        }
        connection.usePrepared("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        connection.usePrepared("PRAGMA integrity_check") {
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
            assertFalse(it.step())
        }
    }
}

/** All persistent application rows, including branch/auth state, compared by value. */
private suspend fun MasterRoomDatabase.commitFacts(): Map<String, List<List<String>>> = useWriterConnection { connection ->
    requireDurableWriter(connection)
    val rows = linkedMapOf<String, List<List<String>>>()
    for ((table, order) in listOf(
        "branch_authority" to "scope",
        "operation_result" to "scope,operationId",
        "aggregate_state" to "scope,kind,id",
        "unique_claim" to "scope,namespace,`key`",
        "journal_envelope" to "scope,sequence",
        "audit_event" to "id",
        "outbox_intent" to "scope,operationId,ordinal",
    )) {
        rows[table] = connection.usePrepared("SELECT * FROM $table ORDER BY $order") { statement ->
            buildList {
                while (statement.step()) add(List(statement.getColumnCount()) { column ->
                    when (val type = statement.getColumnType(column)) {
                        SQLITE_DATA_BLOB -> "blob:${HexFormat.of().formatHex(statement.getBlob(column))}"
                        SQLITE_DATA_NULL -> "null"
                        else -> "$type:${statement.getText(column)}"
                    }
                })
            }
        }
    }
    rows
}

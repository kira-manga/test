package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.throwSQLiteException
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal enum class ReadRefusalKind { AUTHORIZATION, INVALID_SELECTION }
internal enum class ReadRefusalPhase { PREPARE, PROJECT }
internal enum class ReadBoundaryFault { BEFORE_ROLLBACK, AFTER_ROLLBACK, OUTER_LOAN_RETURN }

internal val READ_REFUSAL_SENTINEL = AggregateKey("qualification.read-refusal", testId(9960))
internal val READ_CORRUPTION_SOURCE = AggregateKey("qualification.read-corruption", testId(9961))
internal val READ_REFUSAL_PERMISSION = Permission(SecurityAction.PURCHASING_DOCUMENT_READ, PermissionVerb.PERFORM)
internal val READ_REFUSAL_PAYLOAD = StoredBytes("permitted bounded result".encodeToByteArray())

/**
 * Controlled transaction fixture, with real disposable SQLite and explicitly synthetic
 * branch/identity/authorization/history assumptions. It is not native master composition,
 * original-history admission, current-schema owner setup or persistence acceptance.
 *
 * The generic durable builder is the existing detached-fixture path. The production
 * legacy/owned master factories are unchanged. All ordinary schema callbacks remain
 * installed; no trigger is removed, relaxed or silently repaired.
 */
internal class ReadRefusalFixture(
    internal val driver: ReadBoundaryDriver = ReadBoundaryDriver(),
    path: Path? = null,
) : AutoCloseable {
    private val ownedDirectory = if (path == null) Files.createTempDirectory("master-read-refusal-") else null
    internal val file = path ?: assertNotNull(ownedDirectory).resolve("master.sqlite")
    internal val database = buildDurableRoom(
        Room.databaseBuilder<MasterRoomDatabase>(file.toString(), MasterRoomConstructor::initialize)
            .addCallback(ProtectedLocalSchemaCallback)
            .addCallback(LocalAccessIssuanceSchemaCallback)
            .addCallback(MasterReplacementGuardsCallback)
            .addCallback(LocalAuthenticationSchemaCallback)
            .addCallback(LocalActionApprovalSchemaCallback),
        driver,
    )
    internal val counter = CounterHandler()
    internal val readHandler = ReadRefusalHandler(this)
    private val runtime = object : MasterRuntimeVerifier {
        override suspend fun writer(observation: BranchObservation) = RuntimeVerification.VERIFIED
        override suspend fun absentOperation(observation: BranchObservation, identity: OperationIdentity) = RuntimeVerification.VERIFIED
    }
    private val authorization = TransactionAuthorizationSource { principal, view ->
        val state = view.aggregate(AUTH_KEY)?.payload?.copyBytes()?.decodeToString()
        if (principal.kind != PrincipalKind.LOCAL_EMPLOYEE || state == null) null else AuthorizationSnapshot(
            principal.subjectId, principal.kind, principal.scope, principal.authenticationId,
            principal.generation, principal.deviceId, principal.admissionRevision, Revision(1),
            state != "disabled", state != "revoked", true, AuthenticationPurpose.INTERACTIVE,
            if (state == "no-grants") emptyList() else listOf(
                PermissionGrant(READ_REFUSAL_PERMISSION, TEST_SCOPE, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW),
            ),
        )
    }

    internal fun store() = AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY, listOf(counter),
        authorization, runtime, MasterClock { TEST_TIME }, readHandlers = listOf(readHandler))

    internal suspend fun seed() = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            database.facts().insertBranch(BranchRow(TEST_SCOPE.storageKey(), TEST_AUTHORITY.assignmentId.value,
                TEST_AUTHORITY.deviceId.value, 1, 0, 0, "0".repeat(64), true,
                Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(TEST_SCOPE.branchId))))
            val enabled = "enabled".encodeToByteArray()
            database.facts().insertAggregate(AggregateRow(TEST_SCOPE.storageKey(), AUTH_KEY.kind, AUTH_KEY.id.value,
                1, "auth/v1", enabled, sha256(enabled).value))
        }
    }

    internal suspend fun setAuthorization(state: String) = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val row = assertNotNull(database.facts().aggregate(TEST_SCOPE.storageKey(), AUTH_KEY.kind, AUTH_KEY.id.value))
            val bytes = state.encodeToByteArray()
            assertEquals(1, database.facts().replaceAggregate(row.scope, row.kind, row.id, row.revision,
                row.revision + 1, row.schema, bytes, sha256(bytes).value))
        }
    }

    /** Actual malformed stored bytes/hash in this disposable fixture, not a fabricated refusal. */
    internal suspend fun seedCorruptAggregate() = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            database.facts().insertAggregate(AggregateRow(TEST_SCOPE.storageKey(), READ_CORRUPTION_SOURCE.kind,
                READ_CORRUPTION_SOURCE.id.value, 1, "document/v1", "retained synthetic original".encodeToByteArray(),
                "0".repeat(64)))
        }
    }

    /** Test-only staging on the read's real transaction, never a production read-handler pattern. */
    internal suspend fun stageSentinel() {
        val bytes = "provisional read sentinel".encodeToByteArray()
        database.facts().insertAggregate(AggregateRow(TEST_SCOPE.storageKey(), READ_REFUSAL_SENTINEL.kind,
            READ_REFUSAL_SENTINEL.id.value, 1, "sentinel/v1", bytes, sha256(bytes).value))
    }

    internal fun request() = ReadRequest(readHandler.key, StoredBytes("selection".encodeToByteArray()))

    internal fun counterRequest() = MutationRequest(OperationId(testId(9962).value), counter.key,
        StoredBytes(Json.encodeToString(CounterInput(id = testId(9963))).encodeToByteArray()))

    /** Existing exact-cell snapshot covers all seven financial and five protected tables. */
    internal suspend fun snapshot() = localAuthorizationRows(database)

    override fun close() {
        try { database.close() } finally { ownedDirectory?.toFile()?.deleteRecursively() }
    }
}

internal class ReadRefusalHandler(private val fixture: ReadRefusalFixture) : MasterReadHandler {
    override val key = ReadKey("qualification.read-refusal", 1)
    internal var refusal: ReadRefusalKind? = null
    internal var phase = ReadRefusalPhase.PREPARE
    internal var stageBeforeFailure = false
    internal var corruptStoredRead = false
    internal var genericFailure: Exception? = null
    internal var fault: ReadBoundaryFault? = null
    internal var prepareCalls = 0
    internal var projectCalls = 0

    override fun normalize(input: StoredBytes): StoredBytes {
        require(input == StoredBytes("selection".encodeToByteArray()))
        return input
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        prepareCalls++
        assertEquals(TEST_SCOPE, context.scope)
        assertEquals(TEST_ACTOR, context.actorId)
        assertEquals(key, context.key)
        if (stageBeforeFailure) {
            fixture.stageSentinel()
            // Observe this owned transaction, rather than a pending Room invalidation job.
            fixture.driver.observe()
        }
        if (corruptStoredRead) {
            view.aggregate(READ_CORRUPTION_SOURCE)
            fail("The corrupt aggregate must fail through the production read view")
        }
        if (phase == ReadRefusalPhase.PREPARE) failIfSelected()
        return object : PreparedRead() {
            override val requirements = listOf(AuthorizationRequirement(READ_REFUSAL_PERMISSION, TEST_SCOPE))
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                projectCalls++
                assertEquals(1, authorizations.size)
                assertTrue(authorizations.single().isBoundTo(requirements.single(), TEST_ACTOR))
                assertEquals(TEST_TIME, observedAt)
                if (phase == ReadRefusalPhase.PROJECT) failIfSelected()
                return READ_REFUSAL_PAYLOAD
            }
        }
    }

    private fun failIfSelected() {
        if (refusal == null && genericFailure == null) return
        fault?.let(fixture.driver::arm)
        genericFailure?.let { throw it }
        when (refusal) {
            ReadRefusalKind.AUTHORIZATION -> throw MasterReadAuthorizationDenied()
            ReadRefusalKind.INVALID_SELECTION -> throw MasterReadInvalidSelection()
            null -> error("A fault needs an assigned read failure")
        }
    }

    internal fun permitNextRead() {
        refusal = null
        genericFailure = null
        corruptStoredRead = false
        stageBeforeFailure = false
        fault = null
    }
}

/**
 * Synthetic one-shot IO/error reports at real Room SQL rollback or pool loan return.
 * No VFS/power-loss/physical-storage guarantee is inferred from these controls.
 * Failure probes run in bounded disposable child processes because failed pool recycle
 * or rollback can leave an unusable Room loan; closing must not rescue an assertion.
 */
internal class ReadBoundaryDriver : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    private val observing = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    private val outerReturnArmed = AtomicBoolean(false)
    @Volatile private var fault: ReadBoundaryFault? = null
    internal val commits = AtomicInteger()
    internal val successfulRollbacks = AtomicInteger()
    internal val rollbackFailures = AtomicInteger()
    internal val outerReturnFailures = AtomicInteger()
    internal val activeAtFailure = AtomicBoolean(false)
    internal val actualOuterRecycleReached = AtomicBoolean(false)

    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

    internal fun observe() {
        assertFalse(armed.get())
        assertFalse(outerReturnArmed.get())
        commits.set(0)
        successfulRollbacks.set(0)
        rollbackFailures.set(0)
        outerReturnFailures.set(0)
        activeAtFailure.set(false)
        actualOuterRecycleReached.set(false)
        observing.set(true)
    }

    internal fun stopObserving() { observing.set(false) }

    internal fun arm(selected: ReadBoundaryFault) {
        assertTrue(observing.get())
        fault = selected
        assertTrue(armed.compareAndSet(false, true))
    }

    override fun open(fileName: String): SQLiteConnection = instrument(delegate.open(fileName))

    private fun instrument(connection: SQLiteConnection): SQLiteConnection = object : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement {
            val statement = connection.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean {
                    if (sql == "ROLLBACK TRANSACTION" && fault == ReadBoundaryFault.BEFORE_ROLLBACK &&
                        armed.compareAndSet(true, false)) {
                        activeAtFailure.set(connection.inTransaction())
                        assertTrue(activeAtFailure.get())
                        rollbackFailures.incrementAndGet()
                        throwSQLiteException(10, "synthetic private rollback failure")
                    }
                    val result = statement.step()
                    if (observing.get() && sql == "END TRANSACTION") {
                        commits.incrementAndGet()
                        observing.set(false)
                    }
                    if (observing.get() && sql == "ROLLBACK TRANSACTION") {
                        assertFalse(connection.inTransaction())
                        successfulRollbacks.incrementAndGet()
                        observing.set(false)
                        if (armed.compareAndSet(true, false)) {
                            when (fault) {
                                ReadBoundaryFault.AFTER_ROLLBACK -> {
                                    activeAtFailure.set(connection.inTransaction())
                                    rollbackFailures.incrementAndGet()
                                    throwSQLiteException(10, "synthetic private rollback result failure")
                                }
                                ReadBoundaryFault.OUTER_LOAN_RETURN -> outerReturnArmed.set(true)
                                else -> error("Unexpected rollback fault")
                            }
                        }
                    }
                    return result
                }
            }
        }

        override fun inTransaction(): Boolean {
            if (outerReturnArmed.get() && Throwable().stackTrace.any { "markRecycled" in it.methodName } &&
                outerReturnArmed.compareAndSet(true, false)) {
                actualOuterRecycleReached.set(true)
                activeAtFailure.set(connection.inTransaction())
                outerReturnFailures.incrementAndGet()
                throw IllegalStateException("synthetic private outer loan cleanup failure")
            }
            return connection.inTransaction()
        }
    }
}

/**
 * Read-only observation of the existing admission latch. It never changes authority,
 * installs native handles or bypasses a production guard. A later generic read alone
 * would not establish that physical/native admission remains open.
 */
internal fun AtomicMasterStore.readAdmissionCurrent(): Boolean {
    val field = AtomicMasterStore::class.java.getDeclaredField("physicalService")
    field.isAccessible = true
    return (field.get(this) as PhysicalRecordingServiceLatch).current()
}

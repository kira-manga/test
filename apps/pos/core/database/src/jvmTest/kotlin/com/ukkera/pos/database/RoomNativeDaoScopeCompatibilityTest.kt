package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.NativeHistoryRoomDaoScopeV1
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Frozen room-native-dao-savepoint-v1. Actual generated DAO/Room/SQLite; no native custody. */
class RoomNativeDaoScopeCompatibilityTest {
    @Test fun predecessorRefusalReproducesGeneratedDaoFailureAndOuterRollback() = runTest {
        val driver = ScopeDriver(predecessor = true)
        val f = opened(driver)
        try {
            assertFailsWith<IllegalStateException> {
                f.database.useWriterConnection { c ->
                    requireDurableWriter(c)
                    c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { f.database.facts().insertBranch(branch()) }
                }
            }
            assertTrue("SAVEPOINT '1'" in driver.attempted)
            assertEquals(0, driver.commits)
            assertEquals(0L, count(f))
        } finally { f.close() }
    }

    @Test fun daoReleaseCannotEscapeExplicitOuterRollback() = runTest {
        val driver = ScopeDriver(); val f = opened(driver)
        try {
            f.database.useWriterConnection { c ->
                requireDurableWriter(c)
                c.withTransaction<Unit>(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    f.database.facts().insertBranch(branch())
                    assertTrue("RELEASE SAVEPOINT '1'" in driver.completed)
                    assertEquals(0, driver.commits)
                    rollback(Unit)
                }
            }
            assertEquals(0, driver.commits)
            assertEquals(0L, count(f))
        } finally { f.close() }
    }

    @Test fun onlyActualOuterCommitRetainsTheGeneratedInsert() = runTest {
        val driver = ScopeDriver(); val f = opened(driver)
        try {
            f.database.useWriterConnection { c ->
                requireDurableWriter(c)
                c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    f.database.facts().insertBranch(branch())
                    assertEquals(0, driver.commits)
                }
            }
            assertEquals(1, driver.commits)
            assertEquals(1L, count(f))
            assertEquals(1, driver.completed.count { it == "SAVEPOINT '1'" })
            assertEquals(1, driver.completed.count { it == "RELEASE SAVEPOINT '1'" })
        } finally { f.close() }
    }

    @Test fun failedDaoRequiresActualOuterRollbackAndRetainsNoRows() = runTest {
        val driver = ScopeDriver(); val f = opened(driver)
        try {
            assertFails {
                f.database.useWriterConnection { c ->
                    requireDurableWriter(c)
                    c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        f.database.facts().insertBranch(branch())
                        f.database.facts().insertBranch(branch())
                    }
                }
            }
            assertTrue("ROLLBACK TRANSACTION TO SAVEPOINT '1'" in driver.completed)
            assertEquals(0, driver.commits)
            assertEquals(0L, count(f))
        } finally { f.close() }
    }

    @Test fun deeperRoomTransactionRefusesBeforeAnyDurableResult() = runTest {
        val driver = ScopeDriver(); val f = opened(driver)
        try {
            assertFailsWith<IllegalStateException> {
                f.database.useWriterConnection { c ->
                    requireDurableWriter(c)
                    c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                            f.database.facts().insertBranch(branch())
                        }
                    }
                }
            }
            assertTrue("SAVEPOINT '2'" in driver.attempted)
            assertEquals(0, driver.commits)
            assertEquals(0L, count(f))
        } finally { f.close() }
    }

    @Test fun publicWriterEntryStillRefusesAnActiveTransaction() = runTest {
        val driver = ScopeDriver(); val f = opened(driver)
        try {
            f.database.useWriterConnection { c ->
                requireDurableWriter(c)
                c.withTransaction<Unit>(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    assertFailsWith<IllegalStateException> { requireDurableWriter(c) }
                    rollback(Unit)
                }
            }
            assertEquals(0, driver.commits)
            assertEquals(0L, count(f))
        } finally { f.close() }
    }

    @Test fun closedGrammarAndScopeOrderCannotAuthorizeCommit() {
        for (sql in listOf("SAVEPOINT '2'", "SAVEPOINT x", "RELEASE x", "ROLLBACK TO SAVEPOINT '1'")) {
            assertFailsWith<IllegalStateException> { NativeHistoryRoomDaoScopeV1().before(sql) }
        }
        val scope = NativeHistoryRoomDaoScopeV1()
        assertFailsWith<IllegalStateException> { scope.before("RELEASE SAVEPOINT '1'") }
        repeat(3) {
            scope.completed(assertNotNull(scope.before("SAVEPOINT '1'")))
            assertFailsWith<IllegalStateException> { scope.before("SAVEPOINT '1'") }
            assertFailsWith<IllegalStateException> { scope.before("COMMIT") }
            scope.completed(assertNotNull(scope.before("RELEASE SAVEPOINT '1'")))
        }
        scope.completed(assertNotNull(scope.before("SAVEPOINT '1'")))
        scope.completed(assertNotNull(scope.before("ROLLBACK TRANSACTION TO SAVEPOINT '1'")))
        for (sql in listOf("COMMIT", "SELECT 1", "SAVEPOINT '1'", "RELEASE SAVEPOINT '1'")) {
            assertFailsWith<IllegalStateException> { scope.before(sql) }
        }
        assertNull(scope.before("ROLLBACK TRANSACTION"))
    }

    private suspend fun opened(driver: ScopeDriver): StoreFixture = StoreFixture(driver = driver).also { f ->
        f.database.useWriterConnection { requireDurableWriter(it) }
        driver.recording = true
    }
    private suspend fun count(f: StoreFixture): Long = f.database.useWriterConnection { c ->
        c.usePrepared("SELECT count(*) FROM branch_authority") { check(it.step()); it.getLong(0) }
    }
    private fun branch() = BranchRow(TEST_SCOPE.storageKey(), TEST_AUTHORITY.assignmentId.value,
        TEST_AUTHORITY.deviceId.value, 1, 0, 0, "0".repeat(64), true,
        Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(TEST_SCOPE.branchId)))

    /** Controlled compatibility adapter, not an Android actor/frame/custody implementation. */
    private class ScopeDriver(val predecessor: Boolean = false) : SQLiteDriver {
        private val delegate = BundledSQLiteDriver()
        var recording = false
        val attempted = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<String>()
        var commits = 0
        override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            var scope: NativeHistoryRoomDaoScopeV1? = null
            var branchWriter = false
            fun before(sql: String): NativeHistoryRoomDaoScopeV1.Action? {
                if (!recording) return null
                if (predecessor && sql.startsWith("SAVEPOINT")) error("Nested original transaction")
                return scope?.before(sql)
            }
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    if (recording) attempted += sql
                    before(sql)
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            val action = before(sql)
                            if (recording && sql.startsWith("INSERT OR ABORT INTO `branch_authority`")) branchWriter = true
                            val result = statement.step()
                            if (recording && !result) {
                                if (sql.startsWith("BEGIN")) { scope = NativeHistoryRoomDaoScopeV1(); branchWriter = false }
                                action?.let { checkNotNull(scope).completed(it) }
                                if (sql in setOf("COMMIT", "COMMIT TRANSACTION", "END TRANSACTION", "ROLLBACK", "ROLLBACK TRANSACTION")) {
                                    // Count the application writer, excluding Room TEMP invalidation housekeeping.
                                    if (!sql.startsWith("ROLLBACK") && branchWriter) commits++
                                    scope = null; branchWriter = false
                                }
                                completed += sql
                            }
                            return result
                        }
                    }
                }
            }
        }
    }
}

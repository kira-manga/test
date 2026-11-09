package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Disposable actual master files, intact production guards and synthetic records only.
 * These are raw-DML integrity regressions, not employee authentication or file-tampering defenses.
 * No enclosing transaction can hide a successful destructive UPDATE before its snapshot is taken.
 */
class MasterReplacementRegressionTest {
    private val otherScope = TEST_SCOPE.copy(branchId = testId(9850))

    @Test fun aggregateRowidUpdateOrReplaceCannotEraseAnotherAggregate() = runTest {
        withReplacementDatabase { database, connection ->
            val source = aggregateRow(TEST_SCOPE, 9851, "source")
            val victim = aggregateRow(otherScope, 9852, "victim")
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.facts().insertBranch(branchRow(TEST_SCOPE))
                database.facts().insertBranch(branchRow(otherScope))
                database.facts().insertAggregate(source)
                database.facts().insertAggregate(victim)
            }
            val table = "aggregate_state"
            val sourceRowid = rowidOf(connection, table, "scope=? AND kind=? AND id=?", source.scope, source.kind, source.id)
            val victimRowid = rowidOf(connection, table, "scope=? AND kind=? AND id=?", victim.scope, victim.kind, victim.id)
            // Logical keys stay unchanged; revision+1 satisfies the historical aggregate guard.
            // The victim is in another scope: hidden-rowid uniqueness is table-global.
            val sql = "UPDATE OR REPLACE aggregate_state SET rowid=?, revision=revision+1 WHERE rowid=?"
            assertNonconflictingControl(connection, table, sourceRowid, victimRowid, setOf("revision"), sql) {
                it.bindLong(1, sourceRowid); it.bindLong(2, sourceRowid)
            }
            assertReplacementRefused(connection, table, sourceRowid, victimRowid, sql) {
                it.bindLong(1, victimRowid); it.bindLong(2, sourceRowid)
            }
        }
    }

    @Test fun protectedPolicyRowidUpdateOrReplaceCannotEraseAnotherPolicyWithRecursionOff() = runTest {
        withReplacementDatabase { database, connection ->
            val keyReference = ProtectedStorageKeyReference(testId(9853))
            val protection = TestLocalStorageAead(keyReference)
            val source = policyRow(protection, keyReference, TEST_SCOPE, 1)
            val victim = policyRow(protection, keyReference, otherScope, 1)
            val control = policyRow(protection, keyReference, TEST_SCOPE, 2)
            val replacement = policyRow(protection, keyReference, TEST_SCOPE, 3)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.facts().insertBranch(branchRow(TEST_SCOPE))
                database.facts().insertBranch(branchRow(otherScope))
                database.localSecurity().insertPolicy(source)
                database.localSecurity().insertPolicy(victim)
            }
            val table = "protected_effective_policy"
            val sourceRowid = rowidOf(connection, table, "scope=? AND actorId=?", source.scope, source.actorId)
            val victimRowid = rowidOf(connection, table, "scope=? AND actorId=?", victim.scope, victim.actorId)
            // Both updates have valid advancing revisions AND freshly sealed matching envelopes.
            // No explicit DELETE is issued; recursive OFF exposes REPLACE's implicit victim deletion.
            val sql = "UPDATE OR REPLACE protected_effective_policy SET rowid=?, revision=revision+1, " +
                "nonce=?, ciphertextAndTag=? WHERE rowid=?"
            assertNonconflictingControl(connection, table, sourceRowid, victimRowid,
                setOf("revision", "nonce", "ciphertextAndTag"), sql) {
                it.bindLong(1, sourceRowid); it.bindBlob(2, control.nonce)
                it.bindBlob(3, control.ciphertextAndTag); it.bindLong(4, sourceRowid)
            }
            assertReplacementRefused(connection, table, sourceRowid, victimRowid, sql) {
                it.bindLong(1, victimRowid); it.bindBlob(2, replacement.nonce)
                it.bindBlob(3, replacement.ciphertextAndTag); it.bindLong(4, sourceRowid)
            }
        }
    }

    @Test fun branchScopeUpdateOrReplaceCannotEraseAnUnreferencedBranch() = runTest {
        withReplacementDatabase { database, connection ->
            val source = branchRow(TEST_SCOPE)
            val victim = branchRow(otherScope)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.facts().insertBranch(source)
                database.facts().insertBranch(victim)
            }
            // Neither branch has children. NO ACTION foreign keys must not mask the missing guard.
            for (table in listOf("operation_result", "aggregate_state", "unique_claim", "journal_envelope",
                "audit_event", "outbox_intent", "protected_local_account", "protected_effective_policy",
                "protected_device_admission", "protected_login_throttle", "protected_local_access_issuance")) {
                connection.usePrepared("SELECT count(*) FROM `$table`") {
                    check(it.step()); assertEquals(0L, it.getLong(0), "Unreferenced branch control: $table")
                }
            }
            val table = "branch_authority"
            val sourceRowid = rowidOf(connection, table, "scope=?", source.scope)
            val victimRowid = rowidOf(connection, table, "scope=?", victim.scope)
            val sql = "UPDATE OR REPLACE branch_authority SET scope=?, revision=revision+1 WHERE rowid=?"
            assertNonconflictingControl(connection, table, sourceRowid, victimRowid, setOf("revision"), sql) {
                it.bindText(1, source.scope); it.bindLong(2, sourceRowid)
            }
            assertReplacementRefused(connection, table, sourceRowid, victimRowid, sql) {
                it.bindText(1, victim.scope); it.bindLong(2, sourceRowid)
            }
        }
    }

    @Test fun claimKeyUpdateOrReplaceCannotEraseAnotherClaim() = runTest {
        withReplacementDatabase { database, connection ->
            val source = ClaimRow(TEST_SCOPE.storageKey(), "qualification.replacement", "source-key", testId(9854).value)
            val victim = ClaimRow(TEST_SCOPE.storageKey(), source.namespace, "victim-key", testId(9855).value)
            val controlOwner = testId(9856).value
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.facts().insertBranch(branchRow(TEST_SCOPE))
                database.facts().insertClaim(source)
                database.facts().insertClaim(victim)
            }
            val table = "unique_claim"
            val sourceRowid = rowidOf(connection, table, "scope=? AND namespace=? AND `key`=?",
                source.scope, source.namespace, source.key)
            val victimRowid = rowidOf(connection, table, "scope=? AND namespace=? AND `key`=?",
                victim.scope, victim.namespace, victim.key)
            // This identity-preserving SQL control is not an authorization for claim transfer.
            val sql = "UPDATE OR REPLACE unique_claim SET `key`=?, ownerId=? WHERE rowid=?"
            assertNonconflictingControl(connection, table, sourceRowid, victimRowid, setOf("ownerId"), sql) {
                it.bindText(1, source.key); it.bindText(2, controlOwner); it.bindLong(3, sourceRowid)
            }
            assertReplacementRefused(connection, table, sourceRowid, victimRowid, sql) {
                it.bindText(1, victim.key); it.bindText(2, controlOwner); it.bindLong(3, sourceRowid)
            }
        }
    }

    private suspend fun withReplacementDatabase(block: suspend (MasterRoomDatabase, Transactor) -> Unit) {
        val path = Files.createTempDirectory("ukkera-replacement-regression-").resolve("master.db")
        val database = buildMasterRoom(
            Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize),
            BundledSQLiteDriver(),
        )
        try {
            database.useWriterConnection { connection ->
                requireDurableWriter(connection) // Real file, TRUNCATE/FULL, foreign keys ON.
                connection.usePrepared("PRAGMA recursive_triggers=OFF") { it.step() }
                connection.usePrepared("PRAGMA recursive_triggers") {
                    check(it.step()); assertEquals(0L, it.getLong(0)); assertFalse(it.step())
                }
                block(database, connection)
            }
        } finally { database.close() }
    }

    private fun branchRow(scope: BranchScope) = BranchRow(scope.storageKey(), TEST_AUTHORITY.assignmentId.value,
        TEST_AUTHORITY.deviceId.value, 1, 0, 0, "0".repeat(64), true,
        Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(scope.branchId)))

    private fun aggregateRow(scope: BranchScope, id: Int, value: String): AggregateRow {
        val payload = value.encodeToByteArray()
        return AggregateRow(scope.storageKey(), "qualification.replacement", testId(id).value, 1,
            "replacement-test/v1", payload, sha256(payload).value)
    }

    private suspend fun policyRow(
        protection: TestLocalStorageAead,
        reference: ProtectedStorageKeyReference,
        scope: BranchScope,
        revision: Long,
    ): ProtectedEffectivePolicyRow {
        val value = ActivatedLocalPolicy(scope, TEST_ACTOR, Revision(revision), listOf(PermissionGrant(
            Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
        )), LocalSessionPolicy(), ThrottlePolicy())
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(scope, TEST_ACTOR), value.revision)
        val plaintext = ProtectedLocalSecurityCodec.encodePolicy(value)
        val record = try { assertIs<LocalSealResult.Sealed>(protection.seal(reference, binding, plaintext)).record }
        finally { plaintext.fill(0) }
        return ProtectedEffectivePolicyRow(scope.storageKey(), TEST_ACTOR.value, 1, 1, "AES-256-GCM", revision,
            reference.keyId.value, record.copyNonce(), record.copyCiphertextAndTag())
    }

    private suspend fun rowidOf(connection: Transactor, table: String, where: String, vararg values: String): Long =
        connection.usePrepared("SELECT rowid FROM `$table` WHERE $where") {
            values.forEachIndexed { index, value -> it.bindText(index + 1, value) }
            assertTrue(it.step(), "Missing $table fixture row")
            val rowid = it.getLong(0)
            assertFalse(it.step(), "Ambiguous $table fixture row")
            rowid
        }

    private data class TypedRow(val rowid: Long, val cells: Map<String, String>)

    /** Exact typed SQL cells plus hidden rowids, including both rows and their parent branches. */
    private suspend fun snapshot(connection: Transactor, target: String): Map<String, List<TypedRow>> {
        val result = linkedMapOf<String, List<TypedRow>>()
        for (table in listOf("branch_authority", target).distinct()) {
            val columns = connection.usePrepared("PRAGMA main.table_info(`$table`)") {
                buildList { while (it.step()) add(it.getText(1)) }
            }
            check(columns.isNotEmpty())
            val projection = columns.joinToString(",") { "typeof(`$it`) || ':' || hex(CAST(`$it` AS BLOB))" }
            result[table] = connection.usePrepared("SELECT rowid,$projection FROM `$table` ORDER BY rowid") { statement ->
                buildList {
                    while (statement.step()) add(TypedRow(statement.getLong(0), columns.indices.associate {
                        columns[it] to statement.getText(it + 1)
                    }))
                }
            }
        }
        return result
    }

    private suspend fun execute(connection: Transactor, sql: String, bind: (SQLiteStatement) -> Unit) {
        connection.usePrepared(sql) { bind(it); it.step() }
    }

    private suspend fun changes(connection: Transactor): Long = connection.usePrepared("SELECT changes()") {
        check(it.step()); it.getLong(0)
    }

    private suspend fun assertCleanReferences(connection: Transactor) {
        connection.usePrepared("PRAGMA foreign_key_check") {
            assertFalse(it.step(), "Fixture must remain reference-valid; an FK failure is not the regression oracle")
        }
    }

    private suspend fun assertNonconflictingControl(
        connection: Transactor,
        table: String,
        sourceRowid: Long,
        victimRowid: Long,
        changedColumns: Set<String>,
        sql: String,
        bind: (SQLiteStatement) -> Unit,
    ) {
        assertFalse(connection.inTransaction())
        assertNotEquals(sourceRowid, victimRowid)
        val before = snapshot(connection, table)
        val source = before.getValue(table).single { it.rowid == sourceRowid }
        val victim = before.getValue(table).single { it.rowid == victimRowid }
        execute(connection, sql, bind) // Same statement shape as the collision, without changing identity.
        val affected = changes(connection)
        val after = snapshot(connection, table)
        assertEquals(1L, affected, "Control UPDATE must really execute")
        assertEquals(before.getValue(table).map { it.rowid }, after.getValue(table).map { it.rowid })
        assertEquals(victim, after.getValue(table).single { it.rowid == victimRowid })
        val updatedSource = after.getValue(table).single { it.rowid == sourceRowid }
        assertNotEquals(source.cells, updatedSource.cells, "Control must perform a real payload/revision update")
        assertEquals(source.cells.filterKeys { it !in changedColumns }, updatedSource.cells.filterKeys { it !in changedColumns })
        assertEquals(before.filterKeys { it != table }, after.filterKeys { it != table })
        assertCleanReferences(connection)
    }

    private suspend fun assertReplacementRefused(
        connection: Transactor,
        table: String,
        sourceRowid: Long,
        victimRowid: Long,
        sql: String,
        bind: (SQLiteStatement) -> Unit,
    ) {
        assertFalse(connection.inTransaction(), "Observe the statement, not an enclosing transaction rollback")
        assertCleanReferences(connection)
        val before = snapshot(connection, table)
        val beforeRows = before.getValue(table)
        val source = beforeRows.single { it.rowid == sourceRowid }
        val victim = beforeRows.single { it.rowid == victimRowid }
        var refusal: SQLiteException? = null
        try { execute(connection, sql, bind) }
        catch (error: SQLiteException) { refusal = error }
        val affected = if (refusal == null) changes(connection) else null
        // Capture ALL observations before asserting refusal: a vulnerable run must expose its lost victim.
        val after = snapshot(connection, table)
        val afterRows = after.getValue(table)
        val sourcePreserved = source in afterRows
        val victimPreserved = victim in afterRows
        val destructiveReplacementObserved = refusal == null && affected == 1L &&
            afterRows.size == beforeRows.size - 1 && !victimPreserved
        val diagnostic = "$table: sqlCompleted=${refusal == null}, sqliteFailure=${refusal?.message}, changes=$affected, " +
            "destructiveReplacementObserved=$destructiveReplacementObserved, " +
            "sourceRowid=$sourceRowid sourcePreserved=$sourcePreserved, victimRowid=$victimRowid victimPreserved=$victimPreserved\n" +
            "SQL=$sql\nBEFORE_TYPED_ROWS_AND_ROWIDS=$before\nAFTER_TYPED_ROWS_AND_ROWIDS=$after"
        assertNotNull(refusal, diagnostic)
        assertEquals(before, after, diagnostic)
        assertCleanReferences(connection)
    }
}

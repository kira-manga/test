package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.types.Revision
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Disposable local files and synthetic v1 facts only; never a live database migration. */
class ProtectedLocalMigrationTest {
    @Test fun legacyFixtureReallyIsSevenEntityVersionOneWithOriginalRoomIdentity() = runTest {
        val legacy = LegacyV1MasterFixture()
        try {
            legacy.seed()
            legacy.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(1L, it.getLong(0)) }
                connection.usePrepared("SELECT identity_hash FROM room_master_table WHERE id=42") {
                    check(it.step()); assertEquals("eed72c010956c64963333a5fc4f60632", it.getText(0))
                }
                connection.usePrepared("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'protected_%'") {
                    check(it.step()); assertEquals(0L, it.getLong(0))
                }
            }
            val snapshot = legacyRowSnapshot(legacy.database)
            assertEquals(7, snapshot.size)
            assertEquals(listOf(1, 1, 2, 1, 1, 1, 2), snapshot.values.map { it.size })
            val dao = legacy.database.facts()
            val retained = assertNotNull(decodeRetainedCommand(TEST_SCOPE, legacy.request.operationId,
                dao.operation(TEST_SCOPE.storageKey(), legacy.request.operationId.value),
                dao.retainedJournal(TEST_SCOPE.storageKey(), legacy.request.operationId.value)))
            assertContentEquals(legacy.resultBytes, retained.result.copyBytes())
        } finally { legacy.close() }
    }

    @Test fun actualAdditiveMigrationPreservesEveryLegacyCellAndCreatesOnlyEmptySecurityTables() = runTest {
        val legacy = LegacyV1MasterFixture(); legacy.seed()
        val before = legacyRowSnapshot(legacy.database)
        val oldGuards = legacyGuards(legacy.database)
        legacy.close()
        val migrated = open(legacy)
        try {
            assertEquals(before, legacyRowSnapshot(migrated))
            assertEquals(oldGuards, legacyGuards(migrated))
            assertEmptyProtectedSchema(migrated)
            migrated.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(2L, it.getLong(0)) }
                connection.usePrepared("SELECT id,identity_hash FROM room_master_table") {
                    check(it.step()); assertEquals(42L, it.getLong(0)); assertEquals(LEGACY_V2_ROOM_IDENTITY, it.getText(1))
                    assertFalse(it.step())
                }
                connection.usePrepared("PRAGMA foreign_key_check") { assertFalse(it.step()) }
            }
        } finally { migrated.close() }
        val reopened = open(legacy)
        try {
            assertEquals(before, legacyRowSnapshot(reopened))
            assertEmptyProtectedSchema(reopened)
        } finally { reopened.close() }
    }

    @Test fun migratedSyntheticKernelReplaysOriginalResultWithoutEffectsThenCanAppendNewWork() = runTest {
        val legacy = LegacyV1MasterFixture(); legacy.seed(); legacy.close()
        val current = StoreFixture(legacy.path)
        try {
            // Synthetic fixture authorization only. Empty security tables do not authenticate a user.
            val beforeReplay = legacyRowSnapshot(current.database)
            val replay = assertIs<MutationReply.Recorded>(current.store().execute(testPrincipal(), legacy.request))
            assertTrue(replay.replayed)
            assertContentEquals(legacy.resultBytes, replay.result.copyBytes())
            assertEquals(0, current.handler.prepareCount)
            assertEquals(1L, current.count("operation_result"))
            assertEquals(beforeReplay, legacyRowSnapshot(current.database))
            val next = current.request(21, CounterInput(expectedRevision = Revision(1), delta = 1))
            val recorded = assertIs<MutationReply.Recorded>(current.store().execute(testPrincipal(), next))
            assertTrue(recorded.committed); assertFalse(recorded.replayed)
            assertContentEquals("8".encodeToByteArray(), recorded.result.copyBytes())
            assertEquals(2L, current.count("operation_result"))
            val afterAppend = legacyRowSnapshot(current.database)
            assertNotEquals(beforeReplay, afterAppend)
            val old = assertIs<MutationReply.Recorded>(current.store().execute(testPrincipal(), legacy.request))
            assertTrue(old.replayed); assertContentEquals(legacy.resultBytes, old.result.copyBytes())
            assertEquals(afterAppend, legacyRowSnapshot(current.database))
            // This replay exercises the CURRENT kernel, not the separately preserved 1-to-2 target.
            assertEmptyCurrentProtectedSchema(current.database)
        } finally { current.close() }
    }

    @Test fun failureAfterActualNewTablesAndGuardsRollsBackToReadableUnchangedVersionOne() = runTest {
        val legacy = LegacyV1MasterFixture(); legacy.seed()
        val before = legacyRowSnapshot(legacy.database)
        legacy.close()
        var reached = false
        val failure = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                LocalSecurityMigration1To2.migrate(connection)
                connection.prepare("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'protected_%'").use {
                    check(it.step()); assertEquals(4L, it.getLong(0))
                }
                reached = true
                error("Synthetic migration failure after actual protected DDL")
            }
        }
        val failed = buildDurableRoom(
            Room.databaseBuilder<LegacyV2MasterDatabase>(legacy.path.toString(), ::LegacyV2MasterDatabase_Impl)
                .addMigrations(failure).addCallback(ProtectedLocalSchemaCallback),
        )
        try { assertFails { failed.useWriterConnection { requireDurableWriter(it) } }; assertTrue(reached) }
        finally { failed.close() }
        val original = LegacyV1MasterFixture(legacy.path)
        try {
            assertEquals(before, legacyRowSnapshot(original.database))
            assertVersionAndProtectedTableCount(original.database, 1, 0)
        } finally { original.close() }
        val retried = open(legacy)
        try { assertEquals(before, legacyRowSnapshot(retried)); assertEmptyProtectedSchema(retried) }
        finally { retried.close() }
    }

    @Test fun migrationDoesNotBlessMissingOriginalGuardOrCreateAnySecurityTables() = runTest {
        val legacy = LegacyV1MasterFixture(); legacy.seed(); legacy.close()
        val name = ImmutableFactsCallback.triggerNames.first()
        BundledSQLiteDriver().open(legacy.path.toString()).use { connection ->
            connection.prepare("DROP TRIGGER $name").use { it.step() }
        }
        val before = rawLegacyRowSnapshot(legacy.path)
        val metadata = rawMigrationMetadata(legacy.path)
        val probe = MigrationCreateProbe()
        val refused = open(legacy, probe)
        try { assertFails { refused.useWriterConnection { requireDurableWriter(it) } } }
        finally { refused.close() }
        assertEquals(0, probe.preparations.get(), "Guard validation must precede even preparation of protected DDL")
        assertEquals(0, probe.executions.get())
        assertEquals(before, rawLegacyRowSnapshot(legacy.path))
        assertEquals(metadata, rawMigrationMetadata(legacy.path), "Do not repair a missing guard before refusing")
        BundledSQLiteDriver().open(legacy.path.toString()).use { connection ->
            connection.prepare("PRAGMA user_version").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
            connection.prepare("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'protected_%'").use {
                check(it.step()); assertEquals(0L, it.getLong(0))
            }
            connection.prepare("SELECT result FROM operation_result").use {
                check(it.step()); assertContentEquals(legacy.resultBytes, it.getBlob(0))
            }
        }
    }

    @Test fun healthyLegacyControlActuallyPreparesAndExecutesFourProtectedCreates() = runTest {
        val legacy = LegacyV1MasterFixture(); legacy.seed(); legacy.close()
        val probe = MigrationCreateProbe()
        val migrated = open(legacy, probe)
        try { assertEmptyProtectedSchema(migrated) } finally { migrated.close() }
        assertEquals(4, probe.preparations.get())
        assertEquals(4, probe.executions.get())
    }

    @Test fun unknownMissingOrCoercibleLegacyIdentityIsRefusedBeforeAnyProtectedDdl() = runTest {
        val corruptions = listOf(
            listOf("DROP TABLE room_master_table"),
            listOf("DELETE FROM room_master_table"),
            listOf("UPDATE room_master_table SET identity_hash='00000000000000000000000000000000'"),
            listOf("UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB)"),
            listOf("UPDATE room_master_table SET identity_hash=identity_hash || char(0) || 'tail'"),
            listOf("UPDATE room_master_table SET identity_hash=zeroblob(1048576)"),
            listOf("INSERT INTO room_master_table (id,identity_hash) VALUES (43,'eed72c010956c64963333a5fc4f60632')"),
            listOf("UPDATE room_master_table SET id=43"),
            listOf("ALTER TABLE room_master_table RENAME TO old_identity",
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT COLLATE NOCASE)",
                "INSERT INTO room_master_table VALUES (42,'EED72C010956C64963333A5FC4F60632')",
                "DROP TABLE old_identity"),
            listOf("ALTER TABLE room_master_table RENAME TO old_identity",
                "CREATE TABLE room_master_table (id TEXT,identity_hash TEXT)",
                "INSERT INTO room_master_table VALUES ('42','eed72c010956c64963333a5fc4f60632')",
                "DROP TABLE old_identity"),
        )
        for (sqls in corruptions) {
            val legacy = LegacyV1MasterFixture(); legacy.seed(); legacy.close()
            val before = rawLegacyRowSnapshot(legacy.path)
            BundledSQLiteDriver().open(legacy.path.toString()).use { connection ->
                for (sql in sqls) connection.prepare(sql).use { it.step() }
            }
            val corruptedMetadata = rawMigrationMetadata(legacy.path)
            val probe = MigrationCreateProbe()
            val refused = open(legacy, probe)
            try { assertFails { refused.useWriterConnection { requireDurableWriter(it) } } }
            finally { refused.close() }
            assertEquals(0, probe.preparations.get())
            assertEquals(0, probe.executions.get())
            assertEquals(before, rawLegacyRowSnapshot(legacy.path))
            assertEquals(corruptedMetadata, rawMigrationMetadata(legacy.path), "Unknown identity must not be restamped or repaired")
        }
    }

    @Test fun migrationEntryPointItselfRejectsWrongUserVersionBeforeProtectedDdl() = runTest {
        val legacy = LegacyV1MasterFixture(); legacy.seed(); legacy.close()
        val probe = MigrationCreateProbe()
        probe.open(legacy.path.toString()).use { connection ->
            connection.prepare("PRAGMA journal_mode=TRUNCATE").use { check(it.step()) }
            connection.prepare("PRAGMA synchronous=FULL").use { it.step() }
            connection.prepare("PRAGMA user_version=3").use { it.step() }
            connection.prepare("BEGIN IMMEDIATE").use { it.step() }
            try { assertFailsWith<IllegalStateException> { LocalSecurityMigration1To2.migrate(connection) } }
            finally { connection.prepare("ROLLBACK").use { it.step() } }
        }
        assertEquals(0, probe.preparations.get())
        assertEquals(0, probe.executions.get())
    }

    @Test fun unexpectedPreexistingSecurityTableIsPreservedAndRefusedRatherThanAdopted() = runTest {
        val legacy = LegacyV1MasterFixture(); legacy.seed(); legacy.close()
        BundledSQLiteDriver().open(legacy.path.toString()).use { connection ->
            connection.prepare("CREATE TABLE protected_local_account (unexpected TEXT NOT NULL)").use { it.step() }
            connection.prepare("INSERT INTO protected_local_account VALUES ('do-not-bless')").use { it.step() }
        }
        val refused = open(legacy)
        try { assertFails { refused.useWriterConnection { requireDurableWriter(it) } } }
        finally { refused.close() }
        val original = LegacyV1MasterFixture(legacy.path)
        try {
            assertVersionAndProtectedTableCount(original.database, 1, 1)
            original.database.useWriterConnection { connection ->
                connection.usePrepared("SELECT unexpected FROM protected_local_account") {
                    check(it.step()); assertEquals("do-not-bless", it.getText(0)); assertFalse(it.step())
                }
            }
        } finally { original.close() }
    }

    @Test fun missingOrNoOpProtectedGuardOnReopenIsNeverSilentlyRecreated() = runTest {
        for (replacement in listOf(false, true)) {
            val current = StoreFixture(); current.seed(); current.close()
            val name = ProtectedLocalSchemaCallback.triggerNames.first()
            BundledSQLiteDriver().open(current.path.toString()).use { connection ->
                connection.prepare("DROP TRIGGER $name").use { it.step() }
                if (replacement) connection.prepare("CREATE TRIGGER $name BEFORE INSERT ON protected_local_account BEGIN SELECT 1; END").use { it.step() }
            }
            val before = rawMigrationMetadata(current.path)
            val refused = StoreFixture(current.path)
            try { assertFails { refused.database.useWriterConnection { requireDurableWriter(it) } } }
            finally { refused.close() }
            assertEquals(before, rawMigrationMetadata(current.path), "Refusal must not repair a missing or no-op guard")
            BundledSQLiteDriver().open(current.path.toString()).use { connection ->
                connection.prepare("SELECT count(*) FROM aggregate_state").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
                connection.prepare("SELECT count(*) FROM protected_local_account").use { check(it.step()); assertEquals(0L, it.getLong(0)) }
            }
        }
    }

    // These original 1-to-2 assertions must not follow the current master to version3.
    private fun open(legacy: LegacyV1MasterFixture, driver: SQLiteDriver = BundledSQLiteDriver()): LegacyV2MasterDatabase =
        buildLegacyV2MasterRoom(legacy.path, driver)

    private suspend fun assertEmptyProtectedSchema(database: RoomDatabase) {
        assertVersionAndProtectedTableCount(database, 2, 4)
        database.useWriterConnection { connection ->
            for (table in ProtectedLocalSchemaCallback.tableNames) {
                connection.usePrepared("SELECT count(*) FROM $table") { check(it.step()); assertEquals(0L, it.getLong(0)) }
            }
            for (name in ProtectedLocalSchemaCallback.triggerNames) {
                connection.usePrepared("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name=?") {
                    it.bindText(1, name); check(it.step()); assertEquals(1L, it.getLong(0))
                }
            }
        }
    }

    private suspend fun assertEmptyCurrentProtectedSchema(database: MasterRoomDatabase) {
        assertVersionAndProtectedTableCount(database, 4, 5)
        database.useWriterConnection { connection ->
            for (table in ProtectedLocalSchemaCallback.tableNames + "protected_local_access_issuance") {
                connection.usePrepared("SELECT count(*) FROM $table") { check(it.step()); assertEquals(0L, it.getLong(0)) }
            }
            for (name in ProtectedLocalSchemaCallback.triggerNames + LocalAccessIssuanceSchemaCallback.triggerNames +
                MasterReplacementGuardsCallback.triggerNames) {
                connection.usePrepared("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name=?") {
                    it.bindText(1, name); check(it.step()); assertEquals(1L, it.getLong(0))
                }
            }
        }
    }

    private suspend fun assertVersionAndProtectedTableCount(database: RoomDatabase, version: Long, count: Long) {
        database.useWriterConnection { connection ->
            connection.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(version, it.getLong(0)) }
            connection.usePrepared("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'protected_%'") {
                check(it.step()); assertEquals(count, it.getLong(0))
            }
        }
    }

    private suspend fun legacyGuards(database: RoomDatabase): List<Pair<String, String>> = database.useWriterConnection { connection ->
        connection.usePrepared("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name NOT LIKE 'protected_%' ORDER BY name") {
            buildList { while (it.step()) add(it.getText(0) to it.getText(1)) }
        }
    }
}

/** Independent observations: refusal may not alter version, identity cells, or main schema SQL. */
private fun rawMigrationMetadata(path: Path): List<List<String>> =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA query_only=ON").use { it.step() }
        buildList {
            connection.prepare("PRAGMA user_version").use { check(it.step()); add(listOf(it.getLong(0).toString())) }
            connection.prepare("SELECT type,name,tbl_name,COALESCE(sql,'') FROM sqlite_master ORDER BY type,name").use {
                while (it.step()) add((0..3).map(it::getText))
            }
            val exists = connection.prepare("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='room_master_table'").use {
                check(it.step()); it.getLong(0) == 1L
            }
            if (exists) connection.prepare("SELECT typeof(id)||':'||hex(CAST(id AS BLOB)),typeof(identity_hash)||':'||hex(CAST(identity_hash AS BLOB)) FROM room_master_table ORDER BY id").use {
                while (it.step()) add(listOf(it.getText(0), it.getText(1)))
            }
        }
    }

/** Counts attempted DDL even when a subsequent rollback removes its effects. */
private class MigrationCreateProbe : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    val preparations = AtomicInteger()
    val executions = AtomicInteger()
    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val protectedCreate = Regex("^\\s*CREATE\\s+TABLE\\s+[`\\\"]?protected_", RegexOption.IGNORE_CASE).containsMatchIn(sql)
                if (protectedCreate) preparations.incrementAndGet()
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        if (protectedCreate) executions.incrementAndGet()
                        return statement.step()
                    }
                }
            }
        }
    }
}

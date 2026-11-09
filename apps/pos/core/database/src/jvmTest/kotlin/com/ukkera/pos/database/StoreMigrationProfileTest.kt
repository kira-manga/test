package com.ukkera.pos.database

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.*

// TEST-ONLY marker version2: distinct from the actual development master-v2 schema.
// The genuine LegacyV1MasterFixture supplies the original seven evidence entities,
// not a version-following current StoreFixture masquerading as migration input.
@Entity(tableName = "migration_marker")
internal data class MigrationMarker(@PrimaryKey val id: Int, val note: String)

@Dao
internal interface MigrationMarkerDao {
    @Query("SELECT note FROM migration_marker WHERE id=:id")
    suspend fun note(id: Int): String?
}

@Database(
    entities = [BranchRow::class, OperationRow::class, AggregateRow::class, ClaimRow::class,
        JournalRow::class, AuditRow::class, OutboxRow::class, MigrationMarker::class],
    version = 2, exportSchema = false,
)
internal abstract class MigrationProfileDatabase : RoomDatabase() {
    abstract fun markers(): MigrationMarkerDao
}

class StoreMigrationProfileTest {
    private class CheckedMigration(private val failAfterDdl: Boolean = false) : Migration(1, 2) {
        var observed = false
        var failureReachedAfterDdlAndInsert = false
        override suspend fun migrate(connection: SQLiteConnection) {
            // This runs INSIDE Room's real migration, before user onOpen. An onOpen-
            // only synchronous correction would therefore fail this assertion.
            connection.prepare("PRAGMA main.synchronous").use { check(it.step()); assertEquals(2L, it.getLong(0)) }
            connection.prepare("PRAGMA main.journal_mode").use { check(it.step()); assertEquals("truncate", it.getText(0)) }
            connection.prepare("PRAGMA database_list").use { s ->
                var file = false
                while (s.step()) if (s.getText(1) == "main") file = s.getText(2).isNotEmpty()
                assertTrue(file)
            }
            observed = true
            connection.prepare("CREATE TABLE IF NOT EXISTS `migration_marker` (`id` INTEGER NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`id`))").use { it.step() }
            connection.prepare("INSERT INTO migration_marker(id,note) VALUES(1,'migration-full')").use { it.step() }
            connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
            if (failAfterDdl) {
                failureReachedAfterDdlAndInsert = true
                error("Synthetic migration failure after actual transactional DDL")
            }
        }
    }

    private fun open(path: Path, migration: Migration): MigrationProfileDatabase = buildDurableRoom(
        Room.databaseBuilder<MigrationProfileDatabase>(path.toString(), ::MigrationProfileDatabase_Impl)
            .addMigrations(migration),
    )

    @Test fun migrationUsesFullBeforeDdlAndPreservesActualJournalAndResultBytes() = runTest {
        val f = LegacyV1MasterFixture(); f.seed()
        val originalBytes = f.database.useWriterConnection { c ->
            c.usePrepared("SELECT envelope FROM journal_envelope") { check(it.step()); it.getBlob(0) }
        }
        f.close()
        val migration = CheckedMigration()
        val migrated = open(f.path, migration)
        try {
            assertEquals("migration-full", migrated.markers().note(1))
            assertTrue(migration.observed)
            migrated.useWriterConnection { c ->
                requireDurableWriter(c)
                c.usePrepared("SELECT result, resultHash, epoch, sequence FROM operation_result") {
                    check(it.step()); assertContentEquals(f.resultBytes, it.getBlob(0))
                    assertEquals(f.operation.epoch, it.getLong(2)); assertEquals(f.operation.sequence, it.getLong(3))
                    assertFalse(it.step())
                }
                c.usePrepared("SELECT envelope FROM journal_envelope") { check(it.step()); assertContentEquals(originalBytes, it.getBlob(0)) }
                c.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(2L, it.getLong(0)) }
                c.usePrepared("PRAGMA foreign_key_check") { assertFalse(it.step()) }
            }
        } finally { migrated.close() }
        val reopen = open(f.path, CheckedMigration())
        try { reopen.useWriterConnection { requireDurableWriter(it) }; assertEquals("migration-full", reopen.markers().note(1)) }
        finally { reopen.close() }
    }

    @Test fun failedMigrationRollsBackDdlKeepsVersionAndCanReopenOriginalWithoutDataLoss() = runTest {
        val f = LegacyV1MasterFixture(); f.seed(); f.close()
        val migration = CheckedMigration(failAfterDdl = true)
        val failed = open(f.path, migration)
        try {
            assertFails { failed.markers().note(1) }
            assertTrue(migration.observed)
            assertTrue(migration.failureReachedAfterDdlAndInsert)
        }
        finally { failed.close() }
        BundledSQLiteDriver().open(f.path.toString()).use { c ->
            c.prepare("PRAGMA user_version").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
            c.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='migration_marker'").use { assertFalse(it.step()) }
            c.prepare("SELECT COUNT(*) FROM operation_result").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
        }
        val original = LegacyV1MasterFixture(f.path)
        try {
            val row = assertNotNull(original.database.facts().operation(TEST_SCOPE.storageKey(), f.request.operationId.value))
            assertContentEquals(f.resultBytes, row.result)
            val journal = assertNotNull(original.database.facts().retainedJournal(TEST_SCOPE.storageKey(), f.request.operationId.value))
            assertContentEquals(f.envelope, journal.envelope)
            assertNotNull(decodeRetainedCommand(TEST_SCOPE, f.request.operationId, row, journal))
        }
        finally { original.close() }
    }
}

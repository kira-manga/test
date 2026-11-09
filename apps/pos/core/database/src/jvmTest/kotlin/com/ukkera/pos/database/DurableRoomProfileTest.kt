package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DurableRoomProfileTest {
    @Test fun creationAndEveryReopenUseRealFileTruncateFullForeignKeys() = runTest {
        val first = StoreFixture(); first.seed()
        first.database.useWriterConnection { requireDurableWriter(it) }
        first.close()
        repeat(3) {
            val f = StoreFixture(first.path)
            try { f.database.useWriterConnection { requireDurableWriter(it) } } finally { f.close() }
        }
    }

    @Test fun internallyPooledDriverIsRejectedRatherThanIgnoringPoolContract() {
        val f = StoreFixture()
        try {
            val pooled = object : SQLiteDriver by BundledSQLiteDriver() { override val hasConnectionPool = true }
            assertFailsWith<IllegalArgumentException> {
                buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(f.path.resolveSibling("pooled.db").toString(), MasterRoomConstructor::initialize), pooled)
            }
        } finally { f.close() }
    }

    @Test fun inMemoryDatabaseCannotPassFinancialWriterProfile() = runTest {
        val db = buildMasterRoom(Room.inMemoryDatabaseBuilder<MasterRoomDatabase>(MasterRoomConstructor::initialize))
        try { db.useWriterConnection { assertFailsWith<IllegalStateException> { requireDurableWriter(it) } } }
        finally { db.close() }
    }

    @Test fun unexpectedSynchronousForeignKeysAndJournalModeFailClosedWithoutRepair() = runTest {
        for ((change, query, expected) in listOf(
            Triple("PRAGMA synchronous=NORMAL", "PRAGMA synchronous", "1"),
            Triple("PRAGMA foreign_keys=OFF", "PRAGMA foreign_keys", "0"),
            Triple("PRAGMA journal_mode=WAL", "PRAGMA journal_mode", "wal"),
        )) {
            val f = StoreFixture(); f.seed()
            try {
                f.database.useWriterConnection { c -> c.usePrepared(change) { it.step() } }
                assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(), f.request())).reason)
                f.database.useWriterConnection { c -> c.usePrepared(query) {
                    check(it.step()); assertEquals(expected, it.getText(0))
                } }
                assertEquals(0L, f.count("operation_result"))
            } finally { f.close() }
        }
    }

    @Test fun immutableFactsRejectDeleteUpdateAndImplicitReplaceEvenWithRecursiveTriggersOff() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            f.store().execute(testPrincipal(), f.request(input = CounterInput(claim = "drawer")))
            f.database.useWriterConnection { c ->
                c.usePrepared("PRAGMA recursive_triggers=OFF") { it.step() }
                for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
                    assertFails { c.usePrepared("DELETE FROM $table") { it.step() } }
                    assertFails { c.usePrepared("INSERT OR REPLACE INTO $table SELECT * FROM $table") { it.step() } }
                }
                assertFails { c.usePrepared("UPDATE operation_result SET outcome='rewritten'") { it.step() } }
                for (table in listOf("aggregate_state", "branch_authority", "unique_claim")) {
                    assertFails { c.usePrepared("INSERT OR REPLACE INTO $table SELECT * FROM $table") { it.step() } }
                }
                // Alternate sequence uniqueness must not replace another operation ID.
                val operationError = assertFails { c.usePrepared("INSERT OR REPLACE INTO operation_result SELECT scope, '${testId(900).value}', actorId, command, commandVersion, digest, outcome, result, resultHash, sequence, epoch, committedAt, authenticationId, policyRevision FROM operation_result") { it.step() } }
                assertTrue(operationError.message.orEmpty().contains("identity already exists"))
                // Fresh primary position, existing operation: alternate UNIQUE index,
                // not a duplicate PK or an incidental missing-parent FK failure.
                val journalError = assertFails { c.usePrepared("INSERT OR REPLACE INTO journal_envelope SELECT scope, sequence+1, operationId, assignmentId, epoch, previousHash, hash, envelope FROM journal_envelope") { it.step() } }
                assertTrue(journalError.message.orEmpty().contains("identity already exists"))
            }
            assertEquals(1L, f.count("operation_result")); assertEquals(1L, f.count("unique_claim"))
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), f.request(input = CounterInput(claim = "drawer")))).replayed)
        } finally { f.close() }
    }

    @Test fun missingSchemaIntegrityGuardBlocksReopenRatherThanSilentlyRecreatingIt() = runTest {
        val f = StoreFixture(); f.seed()
        f.database.useWriterConnection { c -> c.usePrepared("DROP TRIGGER operation_result_no_replace") { it.step() } }
        f.close()
        val reopened = StoreFixture(f.path)
        try {
            val result = reopened.store().execute(testPrincipal(), reopened.request())
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(result).reason)
        } finally { reopened.close() }
    }

    @Test fun sameNameIneffectiveSchemaGuardBlocksReopenWithoutRepairingOrDeletingEvidence() = runTest {
        val f = StoreFixture(); f.seed()
        assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), f.request())).committed)
        val noOpSql = "CREATE TRIGGER operation_result_no_replace BEFORE INSERT ON operation_result WHEN 0 BEGIN SELECT RAISE(ABORT, 'identity already exists'); END"
        f.database.useWriterConnection { c ->
            c.usePrepared("DROP TRIGGER operation_result_no_replace") { it.step() }
            c.usePrepared(noOpSql) { it.step() }
        }
        f.close()
        val reopened = StoreFixture(f.path)
        try {
            assertEquals(DeferredReason.STORAGE_UNCERTAIN,
                assertIs<MutationReply.Deferred>(reopened.store().execute(testPrincipal(), reopened.request())).reason)
        } finally { reopened.close() }
        BundledSQLiteDriver().open(f.path.toString()).use { c ->
            c.prepare("SELECT sql FROM sqlite_master WHERE name='operation_result_no_replace'").use {
                check(it.step()); assertEquals(noOpSql, it.getText(0))
            }
            c.prepare("SELECT COUNT(*) FROM operation_result").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
        }
    }

    @Test fun sqliteFullLeavesNoPartialEffectsAndNeverReportsDefiniteRejection() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            f.handler.extraJobBytes = ByteArray(60_000) { (it % 251).toByte() }
            f.database.useWriterConnection { c ->
                val pages = c.usePrepared("PRAGMA page_count") { check(it.step()); it.getLong(0) }
                c.usePrepared("PRAGMA max_page_count=$pages") { it.step() }
            }
            val reply = f.store().execute(testPrincipal(), f.request())
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(reply).reason)
            for (table in listOf("operation_result", "journal_envelope", "outbox_intent", "audit_event")) assertEquals(0L, f.count(table))
            f.database.useWriterConnection { c -> c.usePrepared("PRAGMA max_page_count=2147483646") { it.step() } }
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), f.request())).committed)
        } finally { f.close() }
    }

    @Test fun unsupportedSchemaDowngradeCannotEraseExistingEvidence() = runTest {
        val f = StoreFixture(); f.seed()
        f.store().execute(testPrincipal(), f.request())
        f.database.useWriterConnection { c -> c.usePrepared("PRAGMA user_version=999") { it.step() } }
        f.close()
        val reopened = StoreFixture(f.path)
        try {
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(reopened.store().execute(testPrincipal(), reopened.request())).reason)
        } finally { reopened.close() }
        // Direct independent SQLite read confirms unsupported Room version did not
        // trigger a destructive fallback. This is not an authorized downgrade.
        BundledSQLiteDriver().open(f.path.toString()).use { c ->
            c.prepare("SELECT COUNT(*) FROM operation_result").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
        }
    }
}

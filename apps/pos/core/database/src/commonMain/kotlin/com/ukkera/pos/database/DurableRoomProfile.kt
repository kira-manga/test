package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.SelfOwnedNativeRoomCoroutineDriver
import kotlinx.coroutines.Dispatchers

/**
 * Room3 3.0.3 public-API durability candidate, not a power-loss qualification.
 * Non-WAL Room sets FULL before create/migrate AND for subsequent connections.
 * onOpen-only WAL tuning is deliberately not used. A custom pooled driver cannot
 * silently ignore the requested single Room connection.
 */
internal fun <T : RoomDatabase> buildDurableRoom(
    builder: RoomDatabase.Builder<T>,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): T {
    require(!driver.hasConnectionPool) { "Driver-internal pools are not qualified" }
    val owned = (driver as? SelfOwnedNativeRoomCoroutineDriver)?.ownedRoomCoroutines
    val database = builder
        .setDriver(driver)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .setSingleConnectionPool()
        .setQueryCoroutineContext(owned?.contextForActualBuilder(driver) ?: Dispatchers.Default)
        .addCallback(ImmutableFactsCallback)
        .build()
    owned?.afterActualBuilder(driver)
    return database
}

internal suspend fun requireDurableWriter(connection: Transactor) {
    check(!connection.inTransaction()) { "Success must not escape an enclosing transaction" }
    check(connection.usePrepared("PRAGMA main.journal_mode") { it.step() && it.getText(0).lowercase() == "truncate" }) {
        "Unexpected journal mode: no in-memory/WAL fallback"
    }
    check(connection.usePrepared("PRAGMA main.synchronous") { it.step() && it.getLong(0) == 2L }) { "Writer is not synchronous FULL" }
    check(connection.usePrepared("PRAGMA foreign_keys") { it.step() && it.getLong(0) == 1L }) { "Foreign keys are disabled" }
    check(connection.usePrepared("PRAGMA database_list") {
        var persistentMain = false
        while (it.step()) if (it.getText(1) == "main") persistentMain = it.getText(2).isNotEmpty()
        persistentMain
    }) { "Master financial storage must be file-backed" }
}

/** Immutable financial facts cannot be rewritten even through an accidental internal DAO. */
internal object ImmutableFactsCallback : RoomDatabase.Callback() {
    private val immutableIdentity: Map<String, String> = mapOf(
        "operation_result" to "scope=NEW.scope AND (operationId=NEW.operationId OR (epoch=NEW.epoch AND sequence=NEW.sequence))",
        "journal_envelope" to "scope=NEW.scope AND ((epoch=NEW.epoch AND sequence=NEW.sequence) OR operationId=NEW.operationId)",
        "audit_event" to "id=NEW.id",
        "outbox_intent" to "scope=NEW.scope AND operationId=NEW.operationId AND ordinal=NEW.ordinal",
        "branch_authority" to "scope=NEW.scope",
        "aggregate_state" to "scope=NEW.scope AND kind=NEW.kind AND id=NEW.id",
        "unique_claim" to "scope=NEW.scope AND namespace=NEW.namespace AND `key`=NEW.`key`",
    )
    private data class GuardDefinition(val table: String, val sql: String)
    // One definition supplies both creation and verification. Room's identity hash
    // does not include trigger bodies; checking names alone misses a no-op/wrong-table
    // guard. Exact DDL comparison is intentional for this pinned SQLite/schema profile.
    // This detects schema drift, NOT a hostile process able to rewrite the live file.
    private val definitions: Map<String, GuardDefinition> = buildMap {
        for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
            for (operation in listOf("UPDATE", "DELETE")) {
                val name = "${table}_immutable_${operation.lowercase()}"
                put(name, GuardDefinition(table, "CREATE TRIGGER $name BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'immutable fact'); END"))
            }
        }
        // REPLACE performs an implicit delete which does not necessarily fire DELETE
        // triggers (recursive_triggers is normally off). Reject it BEFORE conflict
        // replacement, including conflicts on each alternate unique identity.
        for ((table, predicate) in immutableIdentity) {
            val name = "${table}_no_replace"
            put(name, GuardDefinition(table, "CREATE TRIGGER $name BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE $predicate) BEGIN SELECT RAISE(ABORT, 'identity already exists'); END"))
        }
        put("aggregate_revision_insert", GuardDefinition("aggregate_state", "CREATE TRIGGER aggregate_revision_insert BEFORE INSERT ON aggregate_state WHEN NEW.revision != 1 BEGIN SELECT RAISE(ABORT, 'initial revision'); END"))
        put("aggregate_revision_update", GuardDefinition("aggregate_state", "CREATE TRIGGER aggregate_revision_update BEFORE UPDATE ON aggregate_state WHEN NEW.revision != OLD.revision + 1 OR NEW.scope != OLD.scope OR NEW.kind != OLD.kind OR NEW.id != OLD.id BEGIN SELECT RAISE(ABORT, 'aggregate identity or revision'); END"))
    }

    val triggerNames: List<String> get() = definitions.keys.toList()

    override suspend fun onCreate(connection: SQLiteConnection) {
        for (guard in definitions.values) connection.prepare(guard.sql).use { it.step() }
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        // Verification only: journal/synchronous configuration was done earlier by
        // Room, including the migration. Never silently repair missing integrity guards.
        for ((name, expected) in definitions) {
            connection.prepare("SELECT tbl_name, sql FROM sqlite_master WHERE type='trigger' AND name=?").use {
                it.bindText(1, name)
                check(it.step() && it.getText(0) == expected.table && it.getText(1) == expected.sql) {
                    "Missing or changed immutable schema guard"
                }
            }
        }
    }
}

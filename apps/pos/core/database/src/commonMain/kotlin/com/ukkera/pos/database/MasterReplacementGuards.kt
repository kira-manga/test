package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Additive hardening of the reviewed v1/v2 tables. Historical guard bodies stay frozen.
 * This is a fixed-DDL integrity profile, not an arbitrary UNIQUE-index/file-tampering detector.
 * RAISE(ABORT) rolls back the failing statement, not prior statements in its transaction.
 */
internal object MasterReplacementGuardsCallback : RoomDatabase.Callback() {
    private val insertTables = listOf(
        "branch_authority", "operation_result", "aggregate_state", "unique_claim",
        "journal_envelope", "audit_event", "outbox_intent", "protected_local_account",
        "protected_effective_policy", "protected_device_admission", "protected_login_throttle",
    )

    // The five other mutable tables already have logical-key/revision guards. The
    // four immutable financial tables and fifth protected table reject ALL updates.
    private val updateKeys = linkedMapOf(
        "branch_authority" to listOf("scope"),
        "aggregate_state" to emptyList(),
        "unique_claim" to listOf("scope", "namespace", "key"),
        "protected_local_account" to emptyList(),
        "protected_effective_policy" to emptyList(),
        "protected_device_admission" to emptyList(),
        "protected_login_throttle" to emptyList(),
    )

    private data class Guard(val table: String, val sql: String)
    private val definitions: Map<String, Guard> = buildMap {
        for (table in insertTables) {
            val name = "${table}_rowid_insert"
            put(name, Guard(table, "CREATE TRIGGER $name BEFORE INSERT ON $table " +
                "WHEN EXISTS(SELECT 1 FROM $table WHERE rowid=NEW.rowid) " +
                "BEGIN SELECT RAISE(ABORT, 'rowid already exists'); END"))
        }
        for ((table, keys) in updateKeys) {
            val name = "${table}_identity_update"
            val changed = (listOf("NEW.rowid IS NOT OLD.rowid") +
                keys.map { "NEW.`$it` IS NOT OLD.`$it`" }).joinToString(" OR ")
            // Unconditional event, NOT UPDATE OF rowid: oid/_rowid_ aliases must not bypass it.
            put(name, Guard(table, "CREATE TRIGGER $name BEFORE UPDATE ON $table WHEN $changed " +
                "BEGIN SELECT RAISE(ABORT, 'identity relocation unavailable'); END"))
        }
    }

    val triggerNames: List<String> get() = definitions.keys.toList()

    /**
     * SQLite's implicit rowid is globally unique within its table, not within scope.
     * NEW.rowid=-1 is also SQLite's omitted-rowid BEFORE INSERT sentinel. If raw DML
     * previously stored -1, conservatively refuse automatic inserts; never exempt
     * negative IDs or renumber persisted evidence. Normal DAOs do not specify rowid.
     */
    internal fun install(connection: SQLiteConnection) {
        for (guard in definitions.values) connection.prepare(guard.sql).use { it.step() }
    }

    /** Before even inherited migration DDL, reject every reserved main-schema name. */
    internal fun requireNamesAbsent(connection: SQLiteConnection) {
        for (name in definitions.keys) {
            // SQLite identifiers are ASCII-case-insensitive; include wrong object types,
            // not just triggers. This collation is deliberately NOT used for identity hashes.
            connection.prepare("SELECT 1 FROM main.sqlite_master WHERE name=? COLLATE NOCASE LIMIT 1").use {
                it.bindText(1, name)
                check(!it.step()) { "Unexpected replacement-guard schema object" }
            }
        }
    }

    // Only install this callback's own guards. ImmutableFactsCallback.onCreate runs later.
    override suspend fun onCreate(connection: SQLiteConnection) = install(connection)

    override suspend fun onOpen(connection: SQLiteConnection) {
        for ((name, guard) in definitions) {
            connection.prepare("SELECT tbl_name, sql FROM main.sqlite_master WHERE type='trigger' AND name=?").use {
                it.bindText(1, name)
                check(it.step() && it.getText(0) == guard.table && it.getText(1) == guard.sql && !it.step()) {
                    "Missing or changed replacement schema guard"
                }
            }
        }
    }
}

internal object MasterReplacementMigration1To4 : Migration(1, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        MasterReplacementGuardsCallback.requireNamesAbsent(connection)
        LocalAccessIssuanceMigration1To3.migrate(connection)
        finishReplacementMigration(connection)
    }
}

internal object MasterReplacementMigration2To4 : Migration(2, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        MasterReplacementGuardsCallback.requireNamesAbsent(connection)
        LocalAccessIssuanceMigration2To3.migrate(connection)
        finishReplacementMigration(connection)
    }
}

/** Genuine corrected development v3 only; no intermediate restamping or profile adoption. */
internal object MasterReplacementMigration3To4 : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        MasterReplacementGuardsCallback.requireNamesAbsent(connection)
        connection.prepare("PRAGMA main.journal_mode").use {
            check(it.step() && it.getText(0).lowercase() == "truncate") { "Migration journal profile unavailable" }
        }
        connection.prepare("PRAGMA main.synchronous").use {
            check(it.step() && it.getLong(0) == 2L) { "Migration durability profile unavailable" }
        }
        connection.prepare("PRAGMA database_list").use {
            var persistent = false
            while (it.step()) if (it.getText(1) == "main") persistent = it.getText(2).isNotEmpty()
            check(persistent) { "Migration requires a persistent master file" }
        }
        connection.prepare("PRAGMA main.user_version").use {
            check(it.step() && it.getLong(0) == 3L && !it.step()) { "Unsupported legacy version" }
        }
        connection.prepare(
            "SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
                "AND typeof(identity_hash)='text' AND length(CAST(identity_hash AS BLOB))=32 " +
                "AND CAST(identity_hash AS BLOB)=CAST('239f7ef6fc1afb6637a58c9b69668d42' AS BLOB) " +
                "THEN 1 ELSE 0 END), 0) FROM room_master_table",
        ).use {
            check(it.step() && it.getLong(0) == 1L && it.getLong(1) == 1L && !it.step()) {
                "Unsupported legacy schema identity"
            }
        }
        ImmutableFactsCallback.onOpen(connection)
        ProtectedLocalSchemaCallback.onOpen(connection)
        LocalAccessIssuanceSchemaCallback.onOpen(connection)
        finishReplacementMigration(connection)
    }
}

private suspend fun finishReplacementMigration(connection: SQLiteConnection) {
    MasterReplacementGuardsCallback.install(connection)
    MasterReplacementGuardsCallback.onOpen(connection)
    connection.prepare("PRAGMA foreign_key_check").use {
        check(!it.step()) { "Migration reference integrity unavailable" }
    }
}

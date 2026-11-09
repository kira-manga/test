package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Room runs all migration steps before restamping identity/user_version. A direct 1 -> 3 route
 * preserves the original gated 1 -> 2 step without pretending its connection already says v2.
 */
internal object LocalAccessIssuanceMigration1To3 : Migration(1, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalSecurityMigration1To2.migrate(connection)
        createLocalAccessIssuanceSchema(connection)
    }
}

/** Authentic development v2 only. No existing row, guard, identity or key is repaired/adopted. */
internal object LocalAccessIssuanceMigration2To3 : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
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
            check(it.step() && it.getLong(0) == 2L && !it.step()) { "Unsupported legacy version" }
        }
        connection.prepare(
            "SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
                "AND typeof(identity_hash)='text' AND length(CAST(identity_hash AS BLOB))=32 " +
                "AND CAST(identity_hash AS BLOB)=CAST('c52a9fdfbd302c090a0d063c023432a7' AS BLOB) " +
                "THEN 1 ELSE 0 END), 0) FROM room_master_table",
        ).use {
            check(it.step() && it.getLong(0) == 1L && it.getLong(1) == 1L && !it.step()) {
                "Unsupported legacy schema identity"
            }
        }
        // Identity hashes do not cover trigger bodies. Both old guard sets are checked before DDL.
        ImmutableFactsCallback.onOpen(connection)
        ProtectedLocalSchemaCallback.onOpen(connection)
        createLocalAccessIssuanceSchema(connection)
    }
}

private fun createLocalAccessIssuanceSchema(connection: SQLiteConnection) {
    // No IF NOT EXISTS: a preexisting table must not be silently adopted or repaired.
    connection.prepare(
        "CREATE TABLE `protected_local_access_issuance` (`scope` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
            "`schemaVersion` INTEGER NOT NULL, `formatVersion` INTEGER NOT NULL, `algorithm` TEXT NOT NULL, " +
            "`revision` INTEGER NOT NULL, `keyId` TEXT NOT NULL, `nonce` BLOB NOT NULL, " +
            "`ciphertextAndTag` BLOB NOT NULL, PRIMARY KEY(`scope`, `sessionId`), " +
            "FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    ).use { it.step() }
    LocalAccessIssuanceSchemaCallback.install(connection)
    connection.prepare("PRAGMA foreign_key_check").use {
        check(!it.step()) { "Migration reference integrity unavailable" }
    }
}

/**
 * Fifth-table guards are separate so the frozen four-table v2 migration stays unchanged.
 * The implicit rowid is unique across ALL scopes; every alias and negative value is covered.
 * Before an automatic insert SQLite reports NEW.rowid=-1. If raw DML previously stored -1,
 * conservatively refuse subsequent automatic inserts rather than permit an immutable deletion.
 * The normal Room insert path does not supply an explicit rowid.
 */
internal object LocalAccessIssuanceSchemaCallback : RoomDatabase.Callback() {
    private const val TABLE = "protected_local_access_issuance"
    private val definitions: Map<String, String> = linkedMapOf(
        "${TABLE}_no_replace" to "CREATE TRIGGER ${TABLE}_no_replace BEFORE INSERT ON $TABLE " +
            "WHEN EXISTS(SELECT 1 FROM $TABLE WHERE (scope=NEW.scope AND sessionId=NEW.sessionId) OR rowid=NEW.rowid) " +
            "BEGIN SELECT RAISE(ABORT, 'protected issuance already exists'); END",
        "${TABLE}_revision_insert" to "CREATE TRIGGER ${TABLE}_revision_insert BEFORE INSERT ON $TABLE " +
            "WHEN typeof(NEW.revision) != 'integer' OR NEW.revision != 0 " +
            "BEGIN SELECT RAISE(ABORT, 'protected issuance revision'); END",
        "${TABLE}_immutable_update" to "CREATE TRIGGER ${TABLE}_immutable_update BEFORE UPDATE ON $TABLE " +
            "BEGIN SELECT RAISE(ABORT, 'protected issuance immutable'); END",
        "${TABLE}_no_delete" to "CREATE TRIGGER ${TABLE}_no_delete BEFORE DELETE ON $TABLE " +
            "BEGIN SELECT RAISE(ABORT, 'protected issuance deletion unavailable'); END",
    )

    val triggerNames: List<String> get() = definitions.keys.toList()

    internal fun install(connection: SQLiteConnection) {
        for (sql in definitions.values) connection.prepare(sql).use { it.step() }
    }

    override suspend fun onCreate(connection: SQLiteConnection) = install(connection)

    override suspend fun onOpen(connection: SQLiteConnection) {
        for ((name, sql) in definitions) {
            connection.prepare("SELECT tbl_name, sql FROM sqlite_master WHERE type='trigger' AND name=?").use {
                it.bindText(1, name)
                check(it.step() && it.getText(0) == TABLE && it.getText(1) == sql) {
                    "Missing or changed protected issuance guard"
                }
            }
        }
    }
}

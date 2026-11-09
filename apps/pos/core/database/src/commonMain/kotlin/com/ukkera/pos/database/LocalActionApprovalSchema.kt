package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Direct routes never assume that Room restamps an intermediate migration version/hash. */
internal object LocalActionApprovalMigration1To6 : Migration(1, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalActionApprovalSchemaCallback.requireNamesAbsent(connection)
        LocalAuthenticationMigration1To5.migrate(connection)
        createActionApprovalSchema(connection)
    }
}
internal object LocalActionApprovalMigration2To6 : Migration(2, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalActionApprovalSchemaCallback.requireNamesAbsent(connection)
        LocalAuthenticationMigration2To5.migrate(connection)
        createActionApprovalSchema(connection)
    }
}
internal object LocalActionApprovalMigration3To6 : Migration(3, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalActionApprovalSchemaCallback.requireNamesAbsent(connection)
        LocalAuthenticationMigration3To5.migrate(connection)
        createActionApprovalSchema(connection)
    }
}
internal object LocalActionApprovalMigration4To6 : Migration(4, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalActionApprovalSchemaCallback.requireNamesAbsent(connection)
        LocalAuthenticationMigration4To5.migrate(connection)
        createActionApprovalSchema(connection)
    }
}
internal object LocalActionApprovalMigration5To6 : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalActionApprovalSchemaCallback.requireNamesAbsent(connection)
        connection.prepare("PRAGMA main.journal_mode").use {
            check(it.step() && it.getText(0).lowercase() == "truncate" && !it.step()) { "Migration journal profile unavailable" }
        }
        connection.prepare("PRAGMA main.synchronous").use {
            check(it.step() && it.getLong(0) == 2L && !it.step()) { "Migration durability profile unavailable" }
        }
        connection.prepare("PRAGMA database_list").use {
            var persistent = false
            while (it.step()) if (it.getText(1) == "main") persistent = it.getText(2).isNotEmpty()
            check(persistent) { "Migration requires a persistent master file" }
        }
        connection.prepare("PRAGMA main.user_version").use {
            check(it.step() && it.getLong(0) == 5L && !it.step()) { "Unsupported legacy version" }
        }
        connection.prepare("SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
            "AND typeof(identity_hash)='text' AND CAST(identity_hash AS BLOB)=CAST('917ea675dbaed518193bc43cfcd19ffc' AS BLOB) " +
            "THEN 1 ELSE 0 END),0) FROM room_master_table").use {
            check(it.step() && it.getLong(0) == 1L && it.getLong(1) == 1L && !it.step()) { "Unsupported legacy schema identity" }
        }
        ImmutableFactsCallback.onOpen(connection)
        ProtectedLocalSchemaCallback.onOpen(connection)
        LocalAccessIssuanceSchemaCallback.onOpen(connection)
        MasterReplacementGuardsCallback.onOpen(connection)
        LocalAuthenticationSchemaCallback.onOpen(connection)
        createActionApprovalSchema(connection)
    }
}

private fun createActionApprovalSchema(connection: SQLiteConnection) {
    connection.prepare("CREATE TABLE `protected_local_action_approval` (`scope` TEXT NOT NULL, `approvalId` TEXT NOT NULL, " +
        "`schemaVersion` INTEGER NOT NULL, `formatVersion` INTEGER NOT NULL, `algorithm` TEXT NOT NULL, " +
        "`revision` INTEGER NOT NULL, `keyId` TEXT NOT NULL, `nonce` BLOB NOT NULL, `ciphertextAndTag` BLOB NOT NULL, " +
        "PRIMARY KEY(`scope`, `approvalId`), FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) " +
        "ON UPDATE NO ACTION ON DELETE NO ACTION )").use { it.step() }
    LocalActionApprovalSchemaCallback.install(connection)
    connection.prepare("PRAGMA foreign_key_check").use { check(!it.step()) { "Migration reference integrity unavailable" } }
}

/** Exactly one state change: unconsumed rev0 to consumed rev1; immutable identity and no deletion. */
internal object LocalActionApprovalSchemaCallback : RoomDatabase.Callback() {
    private const val TABLE = "protected_local_action_approval"
    private val guards = linkedMapOf(
        "${TABLE}_no_replace" to "CREATE TRIGGER ${TABLE}_no_replace BEFORE INSERT ON $TABLE " +
            "WHEN EXISTS(SELECT 1 FROM $TABLE WHERE (scope=NEW.scope AND approvalId=NEW.approvalId) OR rowid=NEW.rowid) " +
            "BEGIN SELECT RAISE(ABORT, 'protected approval identity exists'); END",
        "${TABLE}_revision_insert" to "CREATE TRIGGER ${TABLE}_revision_insert BEFORE INSERT ON $TABLE " +
            "WHEN typeof(NEW.revision)!='integer' OR NEW.revision!=0 " +
            "BEGIN SELECT RAISE(ABORT, 'protected approval initial revision'); END",
        "${TABLE}_guard_update" to "CREATE TRIGGER ${TABLE}_guard_update BEFORE UPDATE ON $TABLE " +
            "WHEN NEW.rowid IS NOT OLD.rowid OR NEW.scope IS NOT OLD.scope OR NEW.approvalId IS NOT OLD.approvalId " +
            "OR typeof(OLD.revision)!='integer' OR OLD.revision!=0 OR typeof(NEW.revision)!='integer' OR NEW.revision!=1 " +
            "BEGIN SELECT RAISE(ABORT, 'protected approval update unavailable'); END",
        "${TABLE}_no_delete" to "CREATE TRIGGER ${TABLE}_no_delete BEFORE DELETE ON $TABLE " +
            "BEGIN SELECT RAISE(ABORT, 'protected approval deletion unavailable'); END",
    )
    internal fun requireNamesAbsent(connection: SQLiteConnection) {
        for (name in listOf(TABLE) + guards.keys) connection.prepare(
            "SELECT 1 FROM main.sqlite_master WHERE name=? COLLATE NOCASE LIMIT 1").use {
            it.bindText(1, name); check(!it.step()) { "Unexpected action approval schema object" }
        }
    }
    internal fun install(connection: SQLiteConnection) {
        for (sql in guards.values) connection.prepare(sql).use { it.step() }
    }
    override suspend fun onCreate(connection: SQLiteConnection) = install(connection)
    override suspend fun onOpen(connection: SQLiteConnection) {
        for ((name, sql) in guards) connection.prepare(
            "SELECT tbl_name,sql FROM main.sqlite_master WHERE type='trigger' AND name=?").use {
            it.bindText(1, name)
            check(it.step() && it.getText(0) == TABLE && it.getText(1) == sql && !it.step()) { "Missing or changed action approval guard" }
        }
    }
}

package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Direct routes: Room does not restamp user_version/identity between migration callbacks. */
internal object LocalAuthenticationMigration1To5 : Migration(1, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalAuthenticationSchemaCallback.requireNamesAbsent(connection)
        MasterReplacementMigration1To4.migrate(connection)
        createAuthenticationSchema(connection)
    }
}
internal object LocalAuthenticationMigration2To5 : Migration(2, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalAuthenticationSchemaCallback.requireNamesAbsent(connection)
        MasterReplacementMigration2To4.migrate(connection)
        createAuthenticationSchema(connection)
    }
}
internal object LocalAuthenticationMigration3To5 : Migration(3, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalAuthenticationSchemaCallback.requireNamesAbsent(connection)
        MasterReplacementMigration3To4.migrate(connection)
        createAuthenticationSchema(connection)
    }
}
internal object LocalAuthenticationMigration4To5 : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        LocalAuthenticationSchemaCallback.requireNamesAbsent(connection)
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
            check(it.step() && it.getLong(0) == 4L && !it.step()) { "Unsupported legacy version" }
        }
        connection.prepare("SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
            "AND typeof(identity_hash)='text' AND length(CAST(identity_hash AS BLOB))=32 " +
            "AND CAST(identity_hash AS BLOB)=CAST('239f7ef6fc1afb6637a58c9b69668d42' AS BLOB) " +
            "THEN 1 ELSE 0 END),0) FROM room_master_table").use {
            check(it.step() && it.getLong(0) == 1L && it.getLong(1) == 1L && !it.step()) { "Unsupported legacy schema identity" }
        }
        ImmutableFactsCallback.onOpen(connection)
        ProtectedLocalSchemaCallback.onOpen(connection)
        LocalAccessIssuanceSchemaCallback.onOpen(connection)
        MasterReplacementGuardsCallback.onOpen(connection)
        createAuthenticationSchema(connection)
    }
}

private val authenticationTableKeys = linkedMapOf(
    "protected_local_auth_configuration" to "actorId",
    "protected_local_refresh_issuance" to "sessionId",
    "protected_local_auth_event" to "eventId",
    "protected_local_session_archive" to "sessionId",
)
private const val LOGIN_INDEX = "index_protected_local_auth_configuration_scope_loginKey"

private fun createAuthenticationSchema(connection: SQLiteConnection) {
    for ((table, id) in authenticationTableKeys) {
        val login = if (table == "protected_local_auth_configuration") "`loginKey` TEXT NOT NULL, " else ""
        connection.prepare("CREATE TABLE `$table` (`scope` TEXT NOT NULL, `$id` TEXT NOT NULL, $login" +
            "`schemaVersion` INTEGER NOT NULL, `formatVersion` INTEGER NOT NULL, `algorithm` TEXT NOT NULL, " +
            "`revision` INTEGER NOT NULL, `keyId` TEXT NOT NULL, `nonce` BLOB NOT NULL, `ciphertextAndTag` BLOB NOT NULL, " +
            "PRIMARY KEY(`scope`, `$id`), FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )").use { it.step() }
    }
    connection.prepare("CREATE UNIQUE INDEX `$LOGIN_INDEX` ON `protected_local_auth_configuration` (`scope`, `loginKey`)").use { it.step() }
    LocalAuthenticationSchemaCallback.install(connection)
    connection.prepare("PRAGMA foreign_key_check").use { check(!it.step()) { "Migration reference integrity unavailable" } }
}

/** Additive v5 guards. Historical1–4 guard bodies and exported schemas are never edited/repaired. */
internal object LocalAuthenticationSchemaCallback : RoomDatabase.Callback() {
    private data class Guard(val table: String, val sql: String)
    private val definitions: Map<String, Guard> = buildMap {
        for ((table, id) in authenticationTableKeys) {
            val configuration = table == "protected_local_auth_configuration"
            val alternative = if (configuration) " OR (scope=NEW.scope AND loginKey=NEW.loginKey)" else ""
            val insert = "${table}_no_replace"
            put(insert, Guard(table, "CREATE TRIGGER $insert BEFORE INSERT ON $table " +
                "WHEN EXISTS(SELECT 1 FROM $table WHERE (scope=NEW.scope AND $id=NEW.$id) OR rowid=NEW.rowid$alternative) " +
                "BEGIN SELECT RAISE(ABORT, 'protected authentication identity exists'); END"))
            val revision = "${table}_revision_insert"
            put(revision, Guard(table, "CREATE TRIGGER $revision BEFORE INSERT ON $table " +
                "WHEN typeof(NEW.revision)!='integer' OR NEW.revision!=0 " +
                "BEGIN SELECT RAISE(ABORT, 'protected authentication initial revision'); END"))
            val update = "${table}_guard_update"
            val predicate = if (configuration) "WHEN NEW.rowid IS NOT OLD.rowid OR NEW.scope IS NOT OLD.scope OR NEW.$id IS NOT OLD.$id " +
                "OR NEW.loginKey IS NOT OLD.loginKey OR typeof(OLD.revision)!='integer' OR OLD.revision<0 " +
                "OR OLD.revision=9223372036854775807 OR typeof(NEW.revision)!='integer' OR NEW.revision!=OLD.revision+1 " else ""
            put(update, Guard(table, "CREATE TRIGGER $update BEFORE UPDATE ON $table $predicate" +
                "BEGIN SELECT RAISE(ABORT, 'protected authentication update unavailable'); END"))
            val delete = "${table}_no_delete"
            put(delete, Guard(table, "CREATE TRIGGER $delete BEFORE DELETE ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'protected authentication deletion unavailable'); END"))
        }
    }
    val tableNames: List<String> get() = authenticationTableKeys.keys.toList()
    val triggerNames: List<String> get() = definitions.keys.toList()

    internal fun requireNamesAbsent(connection: SQLiteConnection) {
        for (name in tableNames + LOGIN_INDEX + definitions.keys) {
            connection.prepare("SELECT 1 FROM main.sqlite_master WHERE name=? COLLATE NOCASE LIMIT 1").use {
                it.bindText(1, name)
                check(!it.step()) { "Unexpected authentication schema object" }
            }
        }
    }
    internal fun install(connection: SQLiteConnection) {
        for (guard in definitions.values) connection.prepare(guard.sql).use { it.step() }
    }
    override suspend fun onCreate(connection: SQLiteConnection) = install(connection)
    override suspend fun onOpen(connection: SQLiteConnection) {
        for ((name, guard) in definitions) {
            connection.prepare("SELECT tbl_name,sql FROM main.sqlite_master WHERE type='trigger' AND name=?").use {
                it.bindText(1, name)
                check(it.step() && it.getText(0) == guard.table && it.getText(1) == guard.sql && !it.step()) {
                    "Missing or changed authentication schema guard"
                }
            }
        }
    }
}

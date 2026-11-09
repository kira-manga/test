package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Master-specific wiring. The unrelated legacy/marker qualification DBs stay on the generic builder. */
internal fun buildMasterRoom(
    builder: RoomDatabase.Builder<MasterRoomDatabase>,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): MasterRoomDatabase {
    requireLegacyMasterRoomProfile()
    return buildDurableRoom(
    builder.addMigrations(LocalSecurityMigration1To2, LocalAccessIssuanceMigration1To3, LocalAccessIssuanceMigration2To3,
        MasterReplacementMigration1To4, MasterReplacementMigration2To4, MasterReplacementMigration3To4,
        LocalAuthenticationMigration1To5, LocalAuthenticationMigration2To5, LocalAuthenticationMigration3To5,
        LocalAuthenticationMigration4To5, LocalActionApprovalMigration1To6, LocalActionApprovalMigration2To6,
        LocalActionApprovalMigration3To6, LocalActionApprovalMigration4To6, LocalActionApprovalMigration5To6)
        .addCallback(ProtectedLocalSchemaCallback).addCallback(LocalAccessIssuanceSchemaCallback)
        .addCallback(MasterReplacementGuardsCallback).addCallback(LocalAuthenticationSchemaCallback)
        .addCallback(LocalActionApprovalSchemaCallback), driver,
    )
}

/** Explicit no-migrate owned profile used by shared startup with the platform's exact native
 * driver. The native owner independently checks that same driver on bootstrap. */
internal fun buildOwnedMasterRoom(builder: RoomDatabase.Builder<MasterRoomDatabase>,driver: SQLiteDriver): MasterRoomDatabase =
    buildDurableRoom(builder.addCallback(ProtectedLocalSchemaCallback).addCallback(LocalAccessIssuanceSchemaCallback)
        .addCallback(MasterReplacementGuardsCallback).addCallback(LocalAuthenticationSchemaCallback)
        .addCallback(LocalActionApprovalSchemaCallback).addCallback(RuntimeHistoryOwnedSchemaCallbackV2),driver)

/** No version6 -> version7 migration or public supplier-created schema7 database exists.
 * Kept at legacy callsites BEFORE Room.databaseBuilder, not after opening the owned file. */
internal fun requireLegacyMasterRoomProfile() {
    check(RuntimeHistoryProfileV2.PROPOSED_ROOM_VERSION==6) {
        "Legacy master opener cannot construct the owned history profile; original native startup required"
    }
}

/**
 * Additive development v1 -> v2 only. No account, policy, admission, throttle, key,
 * principal or privilege is manufactured by opening/migrating a database.
 */
internal object LocalSecurityMigration1To2 : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        // Room's non-WAL profile must already be durable BEFORE the first DDL.
        // Verify, never repair onOpen or tune a private connection implementation.
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
        // Room validates/restamps identity after migrate(), before onOpen checks it.
        // Do not adopt an unknown v1 file merely because its tables are compatible.
        // Only bounded scalar metadata is fetched; this is a drift gate, not proof
        // against a malicious/coherent disk rollback or an authenticity signature.
        connection.prepare("PRAGMA main.user_version").use {
            check(it.step() && it.getLong(0) == 1L && !it.step()) { "Unsupported legacy version" }
        }
        connection.prepare(
            "SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
                "AND typeof(identity_hash)='text' AND length(CAST(identity_hash AS BLOB))=32 " +
                "AND CAST(identity_hash AS BLOB)=CAST('eed72c010956c64963333a5fc4f60632' AS BLOB) " +
                "THEN 1 ELSE 0 END), 0) FROM room_master_table",
        ).use {
            check(it.step() && it.getLong(0) == 1L && it.getLong(1) == 1L && !it.step()) {
                "Unsupported legacy schema identity"
            }
        }
        // A migration must not bless/repair a legacy database whose original
        // integrity guards are missing. This check precedes every new table.
        ImmutableFactsCallback.onOpen(connection)
        for ((table, keys) in protectedTableKeys) {
            val columns = keys.joinToString(", ") { "`$it` TEXT NOT NULL" }
            val primary = keys.joinToString(", ") { "`$it`" }
            // No IF NOT EXISTS: unexpected preexisting tables in v1 must not be blessed.
            val sql = "CREATE TABLE `$table` ($columns, " +
                "`schemaVersion` INTEGER NOT NULL, `formatVersion` INTEGER NOT NULL, " +
                "`algorithm` TEXT NOT NULL, `revision` INTEGER NOT NULL, `keyId` TEXT NOT NULL, " +
                "`nonce` BLOB NOT NULL, `ciphertextAndTag` BLOB NOT NULL, PRIMARY KEY($primary), " +
                "FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) ON UPDATE NO ACTION ON DELETE NO ACTION )"
            connection.prepare(sql).use { it.step() }
        }
        ProtectedLocalSchemaCallback.install(connection)
        connection.prepare("PRAGMA foreign_key_check").use {
            check(!it.step()) { "Migration reference integrity unavailable" }
        }
    }
}

private val protectedTableKeys: Map<String, List<String>> = linkedMapOf(
    "protected_local_account" to listOf("scope", "actorId"),
    "protected_effective_policy" to listOf("scope", "actorId"),
    "protected_device_admission" to listOf("scope", "deviceId"),
    "protected_login_throttle" to listOf("scope", "actorId", "dimensionKey"),
)

/**
 * Reject accidental replacement/deletion and identity/revision drift. Encrypted
 * state semantics (including account terminalization/history) still require the
 * authenticated adapter; SQL metadata alone cannot inspect or authorize them.
 * These guards do not defend against an attacker who can replace the whole file.
 */
internal object ProtectedLocalSchemaCallback : RoomDatabase.Callback() {
    private data class Guard(val table: String, val sql: String)
    private val definitions: Map<String, Guard> = buildMap {
        for ((table, keys) in protectedTableKeys) {
            val identity = keys.joinToString(" AND ") { "`$it`=NEW.`$it`" }
            val changed = keys.joinToString(" OR ") { "NEW.`$it` != OLD.`$it`" }
            val noReplace = "${table}_no_replace"
            put(noReplace, Guard(table, "CREATE TRIGGER $noReplace BEFORE INSERT ON $table " +
                "WHEN EXISTS(SELECT 1 FROM $table WHERE $identity) " +
                "BEGIN SELECT RAISE(ABORT, 'protected identity already exists'); END"))
            val noDelete = "${table}_no_delete"
            put(noDelete, Guard(table, "CREATE TRIGGER $noDelete BEFORE DELETE ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'protected state deletion unavailable'); END"))
            val revision = "${table}_revision_update"
            val advance = if (table == "protected_local_account") {
                // A near-exhausted generation may terminalize revision4 -> MAX.
                // The adapter must also verify both MAX, disabled, all revoked,
                // unchanged credential and complete immutable session history.
                "(NEW.revision != OLD.revision + 1 AND NEW.revision != 9223372036854775807)"
            } else "NEW.revision != OLD.revision + 1"
            put(revision, Guard(table, "CREATE TRIGGER $revision BEFORE UPDATE ON $table " +
                "WHEN $changed OR typeof(OLD.revision) != 'integer' OR OLD.revision < 0 " +
                "OR OLD.revision = 9223372036854775807 OR typeof(NEW.revision) != 'integer' " +
                "OR NEW.revision < 0 OR $advance " +
                "BEGIN SELECT RAISE(ABORT, 'protected identity or revision'); END"))
        }
    }

    val tableNames: List<String> get() = protectedTableKeys.keys.toList()
    val triggerNames: List<String> get() = definitions.keys.toList()

    internal fun install(connection: SQLiteConnection) {
        for (guard in definitions.values) connection.prepare(guard.sql).use { it.step() }
    }

    override suspend fun onCreate(connection: SQLiteConnection) = install(connection)

    override suspend fun onOpen(connection: SQLiteConnection) {
        for ((name, expected) in definitions) {
            connection.prepare("SELECT tbl_name, sql FROM sqlite_master WHERE type='trigger' AND name=?").use {
                it.bindText(1, name)
                check(it.step() && it.getText(0) == expected.table && it.getText(1) == expected.sql) {
                    "Missing or changed protected schema guard"
                }
            }
        }
    }
}

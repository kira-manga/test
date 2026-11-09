package com.ukkera.pos.database

/**
 * READ-ONLY inventory of the pinned, existing v6 schema. These strings are comparison inputs,
 * never executed as DDL. No callback, migration, Room schema JSON or old guard is rewritten.
 * Unknown application objects cannot masquerade as an empty supported installation. This is
 * not a hostile-file/rollback detector or a substitute for the real custody/origin verifier.
 */
internal object RoomBranchInstallationSchemaProfile {
    const val IDENTITY = "c35cf1dddfea41417d2bb3d4f95ce791"
    const val TRACKING = "room_table_modification_log"
    data class Object(val type: String, val table: String, val sql: String?)

    private val protectedKeys = linkedMapOf(
        "protected_local_account" to listOf("scope", "actorId"),
        "protected_effective_policy" to listOf("scope", "actorId"),
        "protected_device_admission" to listOf("scope", "deviceId"),
        "protected_login_throttle" to listOf("scope", "actorId", "dimensionKey"),
    )
    private val authenticationKeys = linkedMapOf(
        "protected_local_auth_configuration" to "actorId",
        "protected_local_refresh_issuance" to "sessionId",
        "protected_local_auth_event" to "eventId",
        "protected_local_session_archive" to "sessionId",
    )
    private val allProtectedKeys = buildMap {
        putAll(protectedKeys)
        put("protected_local_access_issuance", listOf("scope", "sessionId"))
        for ((table, id) in authenticationKeys) put(table, listOf("scope", id))
        put("protected_local_action_approval", listOf("scope", "approvalId"))
    }

    private val tableSql: Map<String, String> = buildMap {
        put("branch_authority", "CREATE TABLE `branch_authority` (`scope` TEXT NOT NULL, `assignmentId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, `journalHash` TEXT NOT NULL, `writable` INTEGER NOT NULL, `recoveryJson` TEXT NOT NULL, PRIMARY KEY(`scope`))")
        put("operation_result", "CREATE TABLE `operation_result` (`scope` TEXT NOT NULL, `operationId` TEXT NOT NULL, `actorId` TEXT NOT NULL, `command` TEXT NOT NULL, `commandVersion` INTEGER NOT NULL, `digest` TEXT NOT NULL, `outcome` TEXT NOT NULL, `result` BLOB NOT NULL, `resultHash` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `epoch` INTEGER NOT NULL, `committedAt` TEXT NOT NULL, `authenticationId` TEXT NOT NULL, `policyRevision` INTEGER NOT NULL, PRIMARY KEY(`scope`, `operationId`), FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        put("aggregate_state", "CREATE TABLE `aggregate_state` (`scope` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, `revision` INTEGER NOT NULL, `schema` TEXT NOT NULL, `payload` BLOB NOT NULL, `payloadHash` TEXT NOT NULL, PRIMARY KEY(`scope`, `kind`, `id`), FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        put("unique_claim", "CREATE TABLE `unique_claim` (`scope` TEXT NOT NULL, `namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `ownerId` TEXT NOT NULL, PRIMARY KEY(`scope`, `namespace`, `key`), FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        put("journal_envelope", "CREATE TABLE `journal_envelope` (`scope` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `operationId` TEXT NOT NULL, `assignmentId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `previousHash` TEXT NOT NULL, `hash` TEXT NOT NULL, `envelope` BLOB NOT NULL, PRIMARY KEY(`scope`, `epoch`, `sequence`), FOREIGN KEY(`scope`, `operationId`) REFERENCES `operation_result`(`scope`, `operationId`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        put("audit_event", "CREATE TABLE `audit_event` (`id` TEXT NOT NULL, `scope` TEXT NOT NULL, `operationId` TEXT NOT NULL, `kind` TEXT NOT NULL, `actorId` TEXT NOT NULL, `originalActorId` TEXT NOT NULL, `policyRevision` INTEGER NOT NULL, `recordedAt` TEXT NOT NULL, `digest` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        put("outbox_intent", "CREATE TABLE `outbox_intent` (`scope` TEXT NOT NULL, `operationId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `kind` TEXT NOT NULL, `body` BLOB NOT NULL, `bodyHash` TEXT NOT NULL, PRIMARY KEY(`scope`, `operationId`, `ordinal`), FOREIGN KEY(`scope`, `operationId`) REFERENCES `operation_result`(`scope`, `operationId`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        for ((table, keys) in allProtectedKeys) {
            val columns = keys.joinToString(", ") { "`$it` TEXT NOT NULL" }
            val primary = keys.joinToString(", ") { "`$it`" }
            val login = if (table == "protected_local_auth_configuration") "`loginKey` TEXT NOT NULL, " else ""
            put(table, "CREATE TABLE `$table` ($columns, $login" +
                "`schemaVersion` INTEGER NOT NULL, `formatVersion` INTEGER NOT NULL, `algorithm` TEXT NOT NULL, " +
                "`revision` INTEGER NOT NULL, `keyId` TEXT NOT NULL, `nonce` BLOB NOT NULL, `ciphertextAndTag` BLOB NOT NULL, " +
                "PRIMARY KEY($primary), FOREIGN KEY(`scope`) REFERENCES `branch_authority`(`scope`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION )")
        }
    }

    /** All 17 tables, including aggregate stock, operation/journal/audit/outbox and every auth table. */
    val applicationTables: Set<String> = tableSql.keys.toSet()

    val requiredMain: Map<String, Object> = buildMap {
        for ((table, sql) in tableSql) {
            put(table, Object("table", table, sql))
            put("sqlite_autoindex_${table}_1", Object("index", table, null))
        }
        put("room_master_table", Object("table", "room_master_table",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"))
        for ((table, columns, unique) in listOf(
            Triple("operation_result", listOf("scope", "epoch", "sequence"), true),
            Triple("journal_envelope", listOf("scope", "operationId"), true),
            Triple("audit_event", listOf("scope", "operationId"), false),
            Triple("outbox_intent", listOf("scope", "operationId"), false),
            Triple("protected_local_auth_configuration", listOf("scope", "loginKey"), true),
        )) {
            val name = "index_${table}_${columns.joinToString("_")}"
            put(name, Object("index", table, "CREATE ${if (unique) "UNIQUE " else ""}INDEX `$name` ON `$table` " +
                "(${columns.joinToString(", ") { "`$it`" }})"))
        }
        for ((name, guard) in guards()) put(name, guard)
    }

    /** Explicit optional SQLite statistics metadata, allowed only when empty by the reader. */
    val optionalStatistics: Map<String, String> = mapOf(
        "sqlite_stat1" to "CREATE TABLE sqlite_stat1(tbl,idx,stat)",
        "sqlite_stat4" to "CREATE TABLE sqlite_stat4(tbl,idx,neq,nlt,ndlt,sample)",
    )

    /**
     * Pinned Room3 3.0.3 tracking objects may be TEMP or main, but not shadow application tables.
     * Trigger SQL is restricted to Room's metadata update, not merely a trusted-looking name.
     * Table IDs are compiler-owned values in this 17-table profile, not application authority.
     */
    fun isTrackingObject(type: String, name: String, table: String, sql: String?): Boolean {
        if (type == "table" && name == TRACKING && table == TRACKING) return normalized(sql) ==
            "CREATE TABLE $TRACKING (table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)"
        if (type != "trigger" || table !in applicationTables || sql == null) return false
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            if (name != "room_table_modification_trigger_${table}_$operation") continue
            for (id in 0 until applicationTables.size) {
                val expected = "CREATE TRIGGER `$name` AFTER $operation ON `$table` BEGIN UPDATE $TRACKING " +
                    "SET invalidated = 1 WHERE table_id = $id AND invalidated = 0; END"
                if (normalized(sql) == expected) return true
            }
        }
        return false
    }

    private val createDecoration = Regex("^CREATE (?:TEMP | )?(TABLE|TRIGGER|(?:UNIQUE )?INDEX) (?:IF NOT EXISTS )?")
    /** Only CREATE's prefix decoration is normalized; predicates/bodies/literals stay exact. */
    fun normalized(sql: String?): String? = sql?.replaceFirst(createDecoration, "CREATE $1 ")

    private fun guards(): Map<String, Object> = buildMap {
        fun guard(table: String, name: String, sql: String) { put(name, Object("trigger", table, sql)) }
        // Exact existing ImmutableFactsCallback bodies.
        for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
            for (operation in listOf("UPDATE", "DELETE")) {
                val name = "${table}_immutable_${operation.lowercase()}"
                guard(table, name, "CREATE TRIGGER $name BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'immutable fact'); END")
            }
        }
        val immutableIdentity = linkedMapOf(
            "operation_result" to "scope=NEW.scope AND (operationId=NEW.operationId OR (epoch=NEW.epoch AND sequence=NEW.sequence))",
            "journal_envelope" to "scope=NEW.scope AND ((epoch=NEW.epoch AND sequence=NEW.sequence) OR operationId=NEW.operationId)",
            "audit_event" to "id=NEW.id", "outbox_intent" to "scope=NEW.scope AND operationId=NEW.operationId AND ordinal=NEW.ordinal",
            "branch_authority" to "scope=NEW.scope", "aggregate_state" to "scope=NEW.scope AND kind=NEW.kind AND id=NEW.id",
            "unique_claim" to "scope=NEW.scope AND namespace=NEW.namespace AND `key`=NEW.`key`",
        )
        for ((table, predicate) in immutableIdentity) {
            val name = "${table}_no_replace"
            guard(table, name, "CREATE TRIGGER $name BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE $predicate) BEGIN SELECT RAISE(ABORT, 'identity already exists'); END")
        }
        guard("aggregate_state", "aggregate_revision_insert", "CREATE TRIGGER aggregate_revision_insert BEFORE INSERT ON aggregate_state WHEN NEW.revision != 1 BEGIN SELECT RAISE(ABORT, 'initial revision'); END")
        guard("aggregate_state", "aggregate_revision_update", "CREATE TRIGGER aggregate_revision_update BEFORE UPDATE ON aggregate_state WHEN NEW.revision != OLD.revision + 1 OR NEW.scope != OLD.scope OR NEW.kind != OLD.kind OR NEW.id != OLD.id BEGIN SELECT RAISE(ABORT, 'aggregate identity or revision'); END")
        // Exact existing replacement guards (v4).
        for (table in immutableIdentity.keys + protectedKeys.keys) {
            val name = "${table}_rowid_insert"
            guard(table, name, "CREATE TRIGGER $name BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE rowid=NEW.rowid) BEGIN SELECT RAISE(ABORT, 'rowid already exists'); END")
        }
        val updateKeys = linkedMapOf("branch_authority" to listOf("scope"), "aggregate_state" to emptyList(),
            "unique_claim" to listOf("scope", "namespace", "key"), "protected_local_account" to emptyList(),
            "protected_effective_policy" to emptyList(), "protected_device_admission" to emptyList(), "protected_login_throttle" to emptyList())
        for ((table, keys) in updateKeys) {
            val name = "${table}_identity_update"
            val changed = (listOf("NEW.rowid IS NOT OLD.rowid") + keys.map { "NEW.`$it` IS NOT OLD.`$it`" }).joinToString(" OR ")
            guard(table, name, "CREATE TRIGGER $name BEFORE UPDATE ON $table WHEN $changed BEGIN SELECT RAISE(ABORT, 'identity relocation unavailable'); END")
        }
        // Exact existing four-table protected guards (v2).
        for ((table, keys) in protectedKeys) {
            val identity = keys.joinToString(" AND ") { "`$it`=NEW.`$it`" }
            val changed = keys.joinToString(" OR ") { "NEW.`$it` != OLD.`$it`" }
            var name = "${table}_no_replace"
            guard(table, name, "CREATE TRIGGER $name BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE $identity) BEGIN SELECT RAISE(ABORT, 'protected identity already exists'); END")
            name = "${table}_no_delete"
            guard(table, name, "CREATE TRIGGER $name BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, 'protected state deletion unavailable'); END")
            name = "${table}_revision_update"
            val advance = if (table == "protected_local_account") "(NEW.revision != OLD.revision + 1 AND NEW.revision != 9223372036854775807)" else "NEW.revision != OLD.revision + 1"
            guard(table, name, "CREATE TRIGGER $name BEFORE UPDATE ON $table WHEN $changed OR typeof(OLD.revision) != 'integer' OR OLD.revision < 0 " +
                "OR OLD.revision = 9223372036854775807 OR typeof(NEW.revision) != 'integer' OR NEW.revision < 0 OR $advance " +
                "BEGIN SELECT RAISE(ABORT, 'protected identity or revision'); END")
        }
        // Exact existing access issuance guards (v3).
        val issuance = "protected_local_access_issuance"
        guard(issuance, "${issuance}_no_replace", "CREATE TRIGGER ${issuance}_no_replace BEFORE INSERT ON $issuance " +
            "WHEN EXISTS(SELECT 1 FROM $issuance WHERE (scope=NEW.scope AND sessionId=NEW.sessionId) OR rowid=NEW.rowid) BEGIN SELECT RAISE(ABORT, 'protected issuance already exists'); END")
        guard(issuance, "${issuance}_revision_insert", "CREATE TRIGGER ${issuance}_revision_insert BEFORE INSERT ON $issuance " +
            "WHEN typeof(NEW.revision) != 'integer' OR NEW.revision != 0 BEGIN SELECT RAISE(ABORT, 'protected issuance revision'); END")
        guard(issuance, "${issuance}_immutable_update", "CREATE TRIGGER ${issuance}_immutable_update BEFORE UPDATE ON $issuance BEGIN SELECT RAISE(ABORT, 'protected issuance immutable'); END")
        guard(issuance, "${issuance}_no_delete", "CREATE TRIGGER ${issuance}_no_delete BEFORE DELETE ON $issuance BEGIN SELECT RAISE(ABORT, 'protected issuance deletion unavailable'); END")
        // Exact existing authentication guards (v5).
        for ((table, id) in authenticationKeys) {
            val configuration = table == "protected_local_auth_configuration"
            val alternative = if (configuration) " OR (scope=NEW.scope AND loginKey=NEW.loginKey)" else ""
            var name = "${table}_no_replace"
            guard(table, name, "CREATE TRIGGER $name BEFORE INSERT ON $table WHEN EXISTS(SELECT 1 FROM $table WHERE (scope=NEW.scope AND $id=NEW.$id) OR rowid=NEW.rowid$alternative) " +
                "BEGIN SELECT RAISE(ABORT, 'protected authentication identity exists'); END")
            name = "${table}_revision_insert"
            guard(table, name, "CREATE TRIGGER $name BEFORE INSERT ON $table WHEN typeof(NEW.revision)!='integer' OR NEW.revision!=0 BEGIN SELECT RAISE(ABORT, 'protected authentication initial revision'); END")
            name = "${table}_guard_update"
            val predicate = if (configuration) "WHEN NEW.rowid IS NOT OLD.rowid OR NEW.scope IS NOT OLD.scope OR NEW.$id IS NOT OLD.$id " +
                "OR NEW.loginKey IS NOT OLD.loginKey OR typeof(OLD.revision)!='integer' OR OLD.revision<0 OR OLD.revision=9223372036854775807 " +
                "OR typeof(NEW.revision)!='integer' OR NEW.revision!=OLD.revision+1 " else ""
            guard(table, name, "CREATE TRIGGER $name BEFORE UPDATE ON $table $predicate" +
                "BEGIN SELECT RAISE(ABORT, 'protected authentication update unavailable'); END")
            name = "${table}_no_delete"
            guard(table, name, "CREATE TRIGGER $name BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, 'protected authentication deletion unavailable'); END")
        }
        // Exact existing approval guards (v6).
        val approval = "protected_local_action_approval"
        guard(approval, "${approval}_no_replace", "CREATE TRIGGER ${approval}_no_replace BEFORE INSERT ON $approval " +
            "WHEN EXISTS(SELECT 1 FROM $approval WHERE (scope=NEW.scope AND approvalId=NEW.approvalId) OR rowid=NEW.rowid) BEGIN SELECT RAISE(ABORT, 'protected approval identity exists'); END")
        guard(approval, "${approval}_revision_insert", "CREATE TRIGGER ${approval}_revision_insert BEFORE INSERT ON $approval " +
            "WHEN typeof(NEW.revision)!='integer' OR NEW.revision!=0 BEGIN SELECT RAISE(ABORT, 'protected approval initial revision'); END")
        guard(approval, "${approval}_guard_update", "CREATE TRIGGER ${approval}_guard_update BEFORE UPDATE ON $approval " +
            "WHEN NEW.rowid IS NOT OLD.rowid OR NEW.scope IS NOT OLD.scope OR NEW.approvalId IS NOT OLD.approvalId " +
            "OR typeof(OLD.revision)!='integer' OR OLD.revision!=0 OR typeof(NEW.revision)!='integer' OR NEW.revision!=1 BEGIN SELECT RAISE(ABORT, 'protected approval update unavailable'); END")
        guard(approval, "${approval}_no_delete", "CREATE TRIGGER ${approval}_no_delete BEFORE DELETE ON $approval BEGIN SELECT RAISE(ABORT, 'protected approval deletion unavailable'); END")
    }
}

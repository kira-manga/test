package com.ukkera.pos.database

import androidx.room3.Transactor
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalBranchInstallationRecord
import kotlinx.serialization.json.Json

/** Counts saturate at three: sufficient to reject everything beyond the exact <=2-row seed shape. */
internal class RoomBranchInstallationFootprint(private val counts: Map<String, Long>) {
    val empty: Boolean get() = counts.keys == RoomBranchInstallationSchemaProfile.applicationTables && counts.values.all { it == 0L }
    val initial: Boolean get() = counts.keys == RoomBranchInstallationSchemaProfile.applicationTables && counts.all { (table, count) ->
        count == when (table) {
            "branch_authority", "protected_local_account", "protected_effective_policy", "protected_device_admission",
            "protected_local_auth_configuration", "protected_local_auth_event" -> 1L
            "protected_login_throttle" -> 2L
            else -> 0L
        }
    }
}

/**
 * Fixed SQL, only borrowed by the concrete installation frame/service through the store's
 * guarded operation boundary. No table name, predicate, callback, DDL or writable SQL is supplied
 * by a public caller. All 17 app tables are inspected globally, not just the requested scope.
 */
internal object RoomBranchInstallationStorage {
    suspend fun footprint(connection: Transactor): RoomBranchInstallationFootprint? {
        val profile = RoomBranchInstallationSchemaProfile
        if (!connection.usePrepared("PRAGMA main.user_version") { it.step() && it.getLong(0) == 6L && !it.step() }) return null
        if (!connection.usePrepared(
            "SELECT CASE WHEN name='main' AND length(CAST(file AS BLOB))>0 THEN 1 " +
                "WHEN name='temp' THEN 2 ELSE 0 END FROM pragma_database_list LIMIT 3",
        ) { statement ->
            val seen = mutableSetOf<Long>()
            while (statement.step()) {
                val kind = statement.getLong(0)
                if (kind !in 1L..2L || !seen.add(kind)) return@usePrepared false
            }
            1L in seen
        }) return null
        val main = schema(connection, "main") ?: return null
        val temp = schema(connection, "temp") ?: return null
        val remaining = profile.requiredMain.keys.toMutableSet()
        val statistics = mutableSetOf<String>()
        for (entry in main) {
            val expected = profile.requiredMain[entry.name]
            if (expected != null) {
                if (!remaining.remove(entry.name) || entry.type != expected.type || entry.table != expected.table ||
                    profile.normalized(entry.sql) != profile.normalized(expected.sql)) return null
            } else if (entry.name in profile.optionalStatistics) {
                if (entry.type != "table" || entry.table != entry.name ||
                    profile.normalized(entry.sql) != profile.optionalStatistics[entry.name] || !statistics.add(entry.name)) return null
            } else if (!profile.isTrackingObject(entry.type, entry.name, entry.table, entry.sql)) return null
        }
        if (remaining.isNotEmpty()) return null
        for (entry in temp) if (!profile.isTrackingObject(entry.type, entry.name, entry.table, entry.sql)) return null
        val mainTracking = main.any { it.type == "table" && it.name == profile.TRACKING }
        val tempTracking = temp.any { it.type == "table" && it.name == profile.TRACKING }
        if (mainTracking && tempTracking) return null
        for (entries in listOf(main, temp)) {
            if (entries.any { it.name.startsWith("room_table_modification_trigger_") } &&
                entries.none { it.type == "table" && it.name == profile.TRACKING }) return null
        }
        if (mainTracking && !validTrackingRows(connection, "main")) return null
        if (tempTracking && !validTrackingRows(connection, "temp")) return null
        for (table in statistics) if (countUpToThree(connection, table) != 0L) return null
        if (!connection.usePrepared(
            "SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
                "AND typeof(identity_hash)='text' AND length(CAST(identity_hash AS BLOB))=32 " +
                "AND CAST(identity_hash AS BLOB)=CAST(? AS BLOB) THEN 1 ELSE 0 END),0) " +
                "FROM (SELECT id,identity_hash FROM main.room_master_table LIMIT 2)",
        ) { statement ->
            statement.bindText(1, profile.IDENTITY)
            statement.step() && statement.getLong(0) == 1L && statement.getLong(1) == 1L && !statement.step()
        }) return null
        val counts = linkedMapOf<String, Long>()
        for (table in profile.applicationTables) counts[table] = countUpToThree(connection, table)
        val result = RoomBranchInstallationFootprint(counts.toMap())
        // Never scan a populated history for this bounded initializer. Non-seed shapes refuse.
        if ((result.empty || result.initial) && !connection.usePrepared("PRAGMA main.foreign_key_check") { !it.step() }) return null
        return result
    }

    suspend fun matchesBranch(connection: Transactor, expected: BranchRow): Boolean = connection.usePrepared(
        // A separate outer CASE checks byte bounds before any exact text comparison. No
        // unknown branch string is hydrated into application memory, even on malformed rows.
        "SELECT CASE WHEN typeof(scope)='text' AND length(CAST(scope AS BLOB))=110 " +
            "AND typeof(assignmentId)='text' AND length(CAST(assignmentId AS BLOB))=36 " +
            "AND typeof(deviceId)='text' AND length(CAST(deviceId AS BLOB))=36 " +
            "AND typeof(epoch)='integer' AND typeof(revision)='integer' AND typeof(sequence)='integer' " +
            "AND typeof(journalHash)='text' AND length(CAST(journalHash AS BLOB))=64 " +
            "AND typeof(writable)='integer' AND typeof(recoveryJson)='text' " +
            "AND length(CAST(recoveryJson AS BLOB)) BETWEEN 1 AND 1024 " +
            "THEN CASE WHEN CAST(scope AS BLOB)=CAST(? AS BLOB) " +
            "AND CAST(assignmentId AS BLOB)=CAST(? AS BLOB) AND CAST(deviceId AS BLOB)=CAST(? AS BLOB) " +
            "AND epoch=? AND revision=? AND sequence=? AND CAST(journalHash AS BLOB)=CAST(? AS BLOB) " +
            "AND writable=? AND CAST(recoveryJson AS BLOB)=CAST(? AS BLOB) " +
            "THEN 1 ELSE 0 END ELSE 0 END FROM main.branch_authority LIMIT 2",
    ) {
        it.bindText(1, expected.scope); it.bindText(2, expected.assignmentId); it.bindText(3, expected.deviceId)
        it.bindLong(4, expected.epoch); it.bindLong(5, expected.revision); it.bindLong(6, expected.sequence)
        it.bindText(7, expected.journalHash); it.bindLong(8, if (expected.writable) 1L else 0L); it.bindText(9, expected.recoveryJson)
        it.step() && it.getLong(0) == 1L && !it.step()
    }

    fun recoveryJson(scope: BranchScope): String = Json.encodeToString(RecoveryAdmissionState.serializer(),
        RecoveryAdmissionState.initiallyProvisioned(scope.branchId))

    fun branch(record: LocalBranchInstallationRecord): BranchRow {
        val identity = record.request.identity
        return BranchRow(identity.scope.storageKey(), identity.assignmentId.value, identity.masterDeviceId.value,
            identity.authorityEpoch.value, 0, 0, record.genesis.value, true, recoveryJson(identity.scope))
    }

    /** Separate schema7 census; not the schema6 initial-footprint reader. All29 registered
     * tables participate, with only THIS transaction's frame/captures allowed pre-C1. */
    suspend fun originalFootprint(connection: Transactor,initial: Boolean): Boolean {
        if(!connection.usePrepared("PRAGMA main.user_version") { it.step() && it.getLong(0)==7L && !it.step() }) return false
        for(table in RuntimeHistoryTableV2.entries) {
            val expected=when(table) {
                RuntimeHistoryTableV2.WRITE_FRAME -> 1L
                RuntimeHistoryTableV2.CAPTURE -> if(initial) 8L else 0L
                RuntimeHistoryTableV2.BRANCH,RuntimeHistoryTableV2.ACCOUNT,RuntimeHistoryTableV2.POLICY,RuntimeHistoryTableV2.ADMISSION,
                RuntimeHistoryTableV2.CONFIGURATION,RuntimeHistoryTableV2.AUTH_EVENT -> if(initial) 1L else 0L
                RuntimeHistoryTableV2.THROTTLE -> if(initial) 2L else 0L
                else -> 0L
            }
            if(!connection.usePrepared("SELECT count(*) FROM (SELECT 1 FROM main.`${table.sqlName}` LIMIT ${expected+1})") {
                it.step() && it.getLong(0)==expected && !it.step()
            }) return false
        }
        return connection.usePrepared("PRAGMA main.foreign_key_check") { !it.step() }
    }
    fun originalBranch(receipt: com.ukkera.pos.security.LocalBranchInstallationReceiptV2): BranchRow {
        val c=receipt.core.allocation.coordinates
        return BranchRow(c.scope.storageKey(),c.assignmentId.value,c.deviceId.value,receipt.core.authorityEpoch.value,
            0,0,receipt.genesis.value,true,recoveryJson(c.scope))
    }

    private suspend fun countUpToThree(connection: Transactor, table: String): Long {
        check(table in RoomBranchInstallationSchemaProfile.applicationTables || table in RoomBranchInstallationSchemaProfile.optionalStatistics)
        return connection.usePrepared("SELECT count(*) FROM (SELECT 1 FROM main.`$table` LIMIT 3)") {
            check(it.step()); val count = it.getLong(0); check(count in 0L..3L && !it.step()); count
        }
    }

    /** Exact optional Room bookkeeping only: at most one integer 0/1 flag for each v6 table. */
    private suspend fun validTrackingRows(connection: Transactor, schema: String): Boolean {
        check(schema == "main" || schema == "temp")
        return connection.usePrepared(
            "SELECT count(*), COALESCE(sum(CASE WHEN typeof(table_id)='integer' AND table_id BETWEEN 0 AND 16 " +
                "AND typeof(invalidated)='integer' AND invalidated IN (0,1) THEN 1 ELSE 0 END),0) " +
                "FROM (SELECT table_id,invalidated FROM $schema.room_table_modification_log LIMIT 18)",
        ) {
            if (!it.step()) return@usePrepared false
            val count = it.getLong(0)
            count in 0L..17L && it.getLong(1) == count && !it.step()
        }
    }

    private data class Entry(val type: String, val name: String, val table: String, val sql: String?)
    private suspend fun schema(connection: Transactor, schema: String): List<Entry>? {
        check(schema == "main" || schema == "temp")
        return connection.usePrepared(
            "SELECT CASE WHEN typeof(type)='text' AND length(CAST(type AS BLOB)) BETWEEN 1 AND 7 " +
                "AND typeof(name)='text' AND length(CAST(name AS BLOB)) BETWEEN 1 AND 160 AND instr(CAST(name AS BLOB),x'00')=0 " +
                "AND typeof(tbl_name)='text' AND length(CAST(tbl_name AS BLOB)) BETWEEN 1 AND 96 AND instr(CAST(tbl_name AS BLOB),x'00')=0 " +
                "AND (sql IS NULL OR (typeof(sql)='text' AND length(CAST(sql AS BLOB)) BETWEEN 1 AND 8192 AND instr(CAST(sql AS BLOB),x'00')=0)) " +
                "THEN 1 ELSE 0 END, type,name,tbl_name,sql FROM $schema.sqlite_master LIMIT 257",
        ) { statement ->
            val entries = mutableListOf<Entry>()
            val names = mutableSetOf<String>()
            while (statement.step()) {
                if (entries.size >= 256 || statement.getLong(0) != 1L) return@usePrepared null
                val type = statement.getText(1); val name = statement.getText(2); val table = statement.getText(3)
                if (!names.add(name)) return@usePrepared null
                entries += Entry(type, name, table, if (statement.isNull(4)) null else statement.getText(4))
            }
            entries.toList()
        }
    }
}

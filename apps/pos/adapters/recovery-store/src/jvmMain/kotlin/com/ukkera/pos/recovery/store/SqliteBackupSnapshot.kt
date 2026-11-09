package com.ukkera.pos.recovery.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val SQLITE_READONLY = 1
private const val SQLITE_READWRITE = 2
private const val MAX_CELL_BYTES = 4 * 1024 * 1024

/**
 * Canonical OBSERVATION utility for trusted profile qualification. The caller must independently
 * validate core table/index/guard definitions on a genuine fresh or migrated disposable database
 * before trusting its hash. Never use an archive/restored file to populate an accepted hash set.
 * This utility does not verify a schema, grant custody or manufacture a compatibility approval.
 */
public object JvmBackupSchemaFingerprint {
    public const val ALGORITHM_ID: String = "ukkera.sqlite-main-schema/v1"

    /** Requires an actually stopped lease; leaves the database read-only and retains its lease. */
    public suspend fun observeStopped(lease: MasterMaintenanceLease): String {
        try {
            return withContext(Dispatchers.IO) {
                lease.ensureStopped()
                val path = lease.databasePath
                if (!path.isAbsolute || path != path.normalize() || path.parent == null ||
                    path.fileName.toString() == ":memory:" || Files.isSymbolicLink(path)) {
                    fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE)
                }
                privateDirectory(path.parent)
                assertNoSqliteSidecars(path)
                val before = requireRegular(path)
                BundledSQLiteDriver().open(path.toString(), SQLITE_READONLY).use { connection ->
                    configureBackupReader(connection)
                    connection.exec("BEGIN")
                    currentCoroutineContext().ensureActive()
                    val fingerprint = fingerprintSchema(connection).second
                    val after = requireRegular(path)
                    if (before.fileKey() != after.fileKey() || before.size() != after.size()) {
                        fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                    }
                    assertNoSqliteSidecars(path)
                    lease.ensureStopped()
                    connection.exec("COMMIT")
                    fingerprint
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LocalBackupException) { throw failure }
        catch (_: Exception) { fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE) }
    }
}

/**
 * Real SQLite read transaction + a mandatory stopped-runtime lease, not loose live-file copying.
 * Rollback journaling's SHARED read lock prevents a compliant SQLite writer changing main pages.
 * WAL/SHM/hot rollback files are rejected, not omitted. Room's per-connection TRUNCATE setting
 * naturally appears as DELETE on a new read-only connection; both use the same rollback format.
 */
internal class SqliteBackupSnapshot(
    private val configuration: JvmLocalBackupConfiguration,
    private val clock: () -> UtcInstant,
    private val historyVerification: LocalBackupHistoryVerification?,
) {
    private val driver = BundledSQLiteDriver()
    private val scope = configuration.scope
    private val profile = configuration.schema
    private val scopeKey = "${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}"

    suspend fun capture(source: Path, target: Path, lease: MasterMaintenanceLease): CapturedSnapshot {
        lease.ensureStopped()
        assertNoSqliteSidecars(source)
        val before = requireRegular(source)
        if (before.size() > configuration.maxDatabaseBytes) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
        return driver.open(source.toString(), SQLITE_READONLY).use { connection ->
            configureBackupReader(connection)
            connection.exec("BEGIN")
            try {
                // Conservative wall-clock capture start, distinct from later archive publication.
                // It is not monotonic evidence or a bound on an unavailable historical tail.
                val capturedAt = clock()
                // Inspection establishes a real SHARED lock before copying the first page.
                val inspection = inspectInTransaction(connection, profile)
                val copied = copyPrivateFile(source, target, configuration.maxDatabaseBytes)
                val after = requireRegular(source)
                if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
                    copied.first != before.size()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                assertNoSqliteSidecars(source)
                lease.ensureStopped()
                connection.exec("COMMIT")
                CapturedSnapshot(inspection, copied.first, copied.second, capturedAt)
            } catch (failure: Throwable) {
                try { connection.exec("ROLLBACK") } catch (_: Exception) { /* The source was never writable. */ }
                throw failure
            }
        }
    }

    suspend fun inspect(path: Path, expectedProfile: SqliteSnapshotProfile = profile): SnapshotInspection {
        assertNoSqliteSidecars(path)
        requireRegular(path)
        return driver.open(path.toString(), SQLITE_READONLY).use { connection ->
            configureBackupReader(connection)
            connection.exec("BEGIN")
            val result = inspectInTransaction(connection, expectedProfile)
            connection.exec("COMMIT")
            result
        }
    }

    suspend fun fingerprints(path: Path, tables: Set<String>): Map<String, TableFingerprint> =
        driver.open(path.toString(), SQLITE_READONLY).use { connection ->
            configureBackupReader(connection)
            connection.exec("BEGIN")
            val result = linkedMapOf<String, TableFingerprint>()
            for (table in tables.sorted()) {
                currentCoroutineContext().ensureActive()
                result[table] = fingerprint(connection, table)
            }
            connection.exec("COMMIT")
            result
        }

    suspend fun requirePreservedAudit(path: Path, before: TableFingerprint) {
        driver.open(path.toString(), SQLITE_READONLY).use { connection ->
            configureBackupReader(connection)
            connection.exec("BEGIN")
            val prefix = fingerprint(connection, profile.quarantineAuditTable, before.maxRowId)
            val after = connection.scalarLong("SELECT count(*) FROM ${quoted(profile.quarantineAuditTable)}")
            if (prefix != before || after != before.rows + 1L) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
            connection.exec("COMMIT")
        }
    }

    /** Only the private stage is changed; old state remains in pinned authenticated archives. */
    suspend fun applyRestoredBranchHold(
        path: Path,
        before: SnapshotInspection,
        held: RecoveryAdmissionState,
    ): SnapshotInspection {
        driver.open(path.toString(), SQLITE_READWRITE).use { connection ->
            connection.exec("PRAGMA trusted_schema=OFF")
            if (connection.scalarText("PRAGMA journal_mode=TRUNCATE") != "truncate") fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
            connection.exec("PRAGMA synchronous=FULL")
            connection.exec("PRAGMA foreign_keys=ON")
            if (connection.scalarLong("PRAGMA synchronous") != 2L || connection.scalarLong("PRAGMA foreign_keys") != 1L) {
                fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
            }
            connection.exec("BEGIN IMMEDIATE")
            try {
                connection.prepare("UPDATE branch_authority SET writable=0, revision=?, recoveryJson=? WHERE scope=? AND revision=?").use {
                    it.bindLong(1, before.branchRevision.next().value)
                    it.bindText(2, backupJson.encodeToString(held))
                    it.bindText(3, scopeKey)
                    it.bindLong(4, before.branchRevision.value)
                    it.step()
                }
                if (connection.scalarLong("SELECT changes()") != 1L) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                connection.exec("COMMIT")
            } catch (failure: Throwable) {
                try { connection.exec("ROLLBACK") } catch (_: Exception) { /* Stage will not be installed. */ }
                throw failure
            }
        }
        forceFile(path)
        return inspect(path)
    }

    private suspend fun inspectInTransaction(connection: SQLiteConnection, profile: SqliteSnapshotProfile): SnapshotInspection {
        requireConfiguredProfile(profile)
        if (connection.scalarLong("PRAGMA user_version") != profile.schemaVersion.toLong()) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        connection.prepare("PRAGMA integrity_check").use {
            if (!it.step() || it.getText(0) != "ok" || it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        }
        connection.prepare("PRAGMA foreign_key_check").use {
            if (it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        }
        if (connection.scalarLong("SELECT count(*) FROM room_master_table") != 1L) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        connection.prepare("SELECT identity_hash FROM room_master_table WHERE id=42 AND typeof(identity_hash)='text' AND length(CAST(identity_hash AS BLOB))=32").use {
            if (!it.step() || it.getText(0) != profile.roomIdentityHash || it.step()) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        }
        val schema = fingerprintSchema(connection)
        val tables = schema.first
        if (tables != profile.requiredTables || schema.second !in profile.permittedSchemaSha256) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        // A correctly named branch row cannot legitimize foreign/malformed scoped records in
        // other tables. Compare exact stored bytes, not a possibly broadened SQL collation.
        for (table in profile.scopedTables.sorted()) {
            currentCoroutineContext().ensureActive()
            connection.prepare("SELECT 1 FROM ${quoted(table)} WHERE typeof(scope)!='text' " +
                "OR CAST(scope AS BLOB)!=CAST(? AS BLOB) LIMIT 1").use {
                it.bindText(1, scopeKey)
                if (it.step()) fail(LocalBackupFailure.WRONG_SCOPE)
            }
        }
        if (connection.scalarLong("SELECT count(*) FROM branch_authority") != 1L) fail(LocalBackupFailure.WRONG_SCOPE)
        // Guard every variable field before materializing it. SQL values never become path names.
        val valid = connection.prepare("SELECT CASE WHEN typeof(scope)='text' AND length(CAST(scope AS BLOB))=110 " +
            "AND typeof(assignmentId)='text' AND length(CAST(assignmentId AS BLOB))=36 " +
            "AND typeof(deviceId)='text' AND length(CAST(deviceId AS BLOB))=36 " +
            "AND typeof(epoch)='integer' AND epoch>=0 AND typeof(revision)='integer' AND revision>=0 " +
            "AND typeof(sequence)='integer' AND sequence>=0 AND typeof(writable)='integer' AND writable IN (0,1) " +
            "AND typeof(journalHash)='text' AND length(CAST(journalHash AS BLOB))=64 " +
            "AND typeof(recoveryJson)='text' AND length(CAST(recoveryJson AS BLOB)) BETWEEN 2 AND $MAX_CELL_BYTES " +
            "THEN 1 ELSE 0 END FROM branch_authority WHERE scope=?").use {
            it.bindText(1, scopeKey); it.step() && it.getLong(0) == 1L && !it.step()
        }
        if (!valid) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        val result = connection.prepare("SELECT assignmentId, deviceId, epoch, revision, sequence, journalHash, writable, recoveryJson FROM branch_authority WHERE scope=?").use {
            it.bindText(1, scopeKey)
            if (!it.step()) fail(LocalBackupFailure.WRONG_SCOPE)
            val recoveryText = it.getText(7)
            requireShallowJson(recoveryText)
            val recovery = backupJson.decodeFromString<RecoveryAdmissionState>(recoveryText)
            if (recovery.branchId != scope.branchId || !isSha256(it.getText(5))) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
            SnapshotInspection(AuthorityIdentity(DomainId(it.getText(0)), DomainId(it.getText(1)), Revision(it.getLong(2))),
                Revision(it.getLong(3)), JournalSequence(it.getLong(4)), it.getText(5), it.getLong(6) == 1L,
                recovery, schema.second, tables)
        }
        if (result.sequence.value > 0) connection.prepare(
            "SELECT assignmentId, hash FROM journal_envelope WHERE scope=? AND epoch=? AND sequence=? " +
                "AND typeof(assignmentId)='text' AND length(CAST(assignmentId AS BLOB))=36 " +
                "AND typeof(hash)='text' AND length(CAST(hash AS BLOB))=64",
        ).use {
            it.bindText(1, scopeKey); it.bindLong(2, result.authority.epoch.value); it.bindLong(3, result.sequence.value)
            if (!it.step() || it.getText(0) != result.authority.assignmentId.value ||
                it.getText(1) != result.journalHash || it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        }
        // A missing partner is corruption; no inner join can hide an orphan as absent history.
        for ((left, right) in listOf("operation_result" to "journal_envelope", "journal_envelope" to "operation_result")) {
            connection.prepare("SELECT 1 FROM $left a LEFT JOIN $right b ON a.scope=b.scope AND a.operationId=b.operationId " +
                "WHERE b.operationId IS NULL OR a.epoch!=b.epoch OR a.sequence!=b.sequence LIMIT 1").use {
                if (it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
            }
        }
        // Whole-file authentication cannot detect a payload already damaged before capture.
        // Validate the existing immutable/current payload checksums without replaying handlers.
        for ((table, value, hash) in listOf(
            Triple("operation_result", "result", "resultHash"),
            Triple("journal_envelope", "envelope", "hash"),
            Triple("aggregate_state", "payload", "payloadHash"),
            Triple("outbox_intent", "body", "bodyHash"),
        )) verifyStoredHashes(connection, table, value, hash)
        if (profile.schemaVersion == 7) {
            val verifier = historyVerification ?: fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
            val reader = SqliteBackupHistoryRead(connection, scope, profile, result)
            try {
                reader.requireStructuralIntegrity()
                // Whole-file AEAD and SQL counts cannot qualify original native history or
                // streamed commitments. The real adapter must perform every bounded traversal.
                verifier.verify(reader)
                reader.requireCompleteTraversal()
            } finally { reader.close() }
        }
        return result
    }

    private fun requireConfiguredProfile(profile: SqliteSnapshotProfile) {
        val configured = configuration.readProfiles.singleOrNull { it.schemaVersion == profile.schemaVersion }
            ?: fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        if (profile.roomIdentityHash != configured.roomIdentityHash || profile.requiredTables != configured.requiredTables ||
            profile.securityTables != configured.securityTables || profile.scopedTables != configured.scopedTables ||
            profile.quarantineAuditTable != configured.quarantineAuditTable ||
            profile.permittedSchemaSha256 != configured.permittedSchemaSha256) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        if (profile.schemaVersion > 7 || profile.schemaVersion != 7 && profile.scopedTables != profile.requiredTables) {
            fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        }
        if (profile.schemaVersion == 7) {
            val current = LocalMasterBackupProfiles.localAuthV7(profile.permittedSchemaSha256)
            if (profile.roomIdentityHash != current.roomIdentityHash || profile.requiredTables != current.requiredTables ||
                profile.securityTables != current.securityTables || profile.scopedTables != current.scopedTables ||
                profile.quarantineAuditTable != current.quarantineAuditTable || historyVerification == null) {
                fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
            }
        }
    }

    private suspend fun verifyStoredHashes(connection: SQLiteConnection, table: String, value: String, hash: String) {
        connection.prepare("SELECT 1 FROM ${quoted(table)} WHERE typeof(${quoted(value)})!='blob' " +
            "OR length(${quoted(value)})>$MAX_CELL_BYTES OR typeof(${quoted(hash)})!='text' " +
            "OR length(CAST(${quoted(hash)} AS BLOB))!=64 LIMIT 1").use {
            if (it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        }
        connection.prepare("SELECT ${quoted(value)},${quoted(hash)} FROM ${quoted(table)}").use {
            while (it.step()) {
                currentCoroutineContext().ensureActive()
                val bytes = it.getBlob(0)
                try {
                    val expected = it.getText(1)
                    if (!isSha256(expected) || sha256(bytes) != expected) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                } finally { bytes.fill(0) }
            }
        }
    }

    private suspend fun fingerprint(connection: SQLiteConnection, table: String, throughRowId: Long? = null): TableFingerprint {
        if (configuration.readProfiles.none { table in it.requiredTables }) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        val columns = mutableListOf<String>()
        connection.prepare("PRAGMA table_info(${quoted(table)})").use {
            while (it.step()) {
                val name = it.getText(1)
                if (columns.size >= 64 || name.length !in 1..96 || name.any { c -> !c.isLetterOrDigit() && c != '_' }) {
                    fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
                }
                columns += name
            }
        }
        if (columns.isEmpty()) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        // This extra scan bounds TEXT/BLOB hydration, including text containing embedded NUL.
        for (column in columns) {
            if (connection.scalarLong("SELECT COALESCE(max(length(CAST(${quoted(column)} AS BLOB))),0) FROM ${quoted(table)}") > MAX_CELL_BYTES) {
                fail(LocalBackupFailure.CAPACITY_EXCEEDED)
            }
        }
        val rowBytes = columns.joinToString("+") { "COALESCE(length(CAST(${quoted(it)} AS BLOB)),0)" }
        if (connection.scalarLong("SELECT COALESCE(max($rowBytes),0) FROM ${quoted(table)}") > 2L * MAX_CELL_BYTES) {
            fail(LocalBackupFailure.CAPACITY_EXCEEDED)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val selected = "rowid," + columns.joinToString(",", transform = ::quoted)
        var rows = 0L
        var maxRowId = 0L
        val where = if (throughRowId == null) "" else " WHERE rowid<=?"
        connection.prepare("SELECT $selected FROM ${quoted(table)}$where ORDER BY rowid").use {
            if (throughRowId != null) it.bindLong(1, throughRowId)
            while (it.step()) {
                currentCoroutineContext().ensureActive()
                rows++
                maxRowId = it.getLong(0)
                for (index in 0..columns.size) digest.cell(it, index)
            }
        }
        return TableFingerprint(rows, digest.digest().hex(), maxRowId)
    }
}

private fun configureBackupReader(connection: SQLiteConnection) {
    connection.exec("PRAGMA trusted_schema=OFF")
    connection.exec("PRAGMA query_only=ON")
    connection.exec("PRAGMA busy_timeout=5000")
    if (connection.scalarText("PRAGMA journal_mode") !in setOf("delete", "truncate", "persist")) {
        fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
    }
}

/** Shared with the public stopped-profile observer; SQL spelling and every schema object count. */
private fun fingerprintSchema(connection: SQLiteConnection): Pair<Set<String>, String> {
    if (connection.scalarLong("SELECT count(*) FROM main.sqlite_master") > 1024L ||
        connection.scalarLong("SELECT COALESCE(max(length(CAST(sql AS BLOB))),0) FROM main.sqlite_master") > MAX_CELL_BYTES ||
        connection.scalarLong("SELECT COALESCE(max(length(CAST(name AS BLOB))),0) FROM main.sqlite_master") > 128L ||
        connection.scalarLong("SELECT COALESCE(max(length(CAST(tbl_name AS BLOB))),0) FROM main.sqlite_master") > 128L ||
        connection.scalarLong("SELECT COALESCE(max(length(CAST(type AS BLOB))),0) FROM main.sqlite_master") > 16L) {
        fail(LocalBackupFailure.CAPACITY_EXCEEDED)
    }
    val tables = mutableSetOf<String>()
    val hash = MessageDigest.getInstance("SHA-256")
    hash.update((JvmBackupSchemaFingerprint.ALGORITHM_ID + "\u0000").toByteArray(Charsets.US_ASCII))
    connection.prepare("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type COLLATE BINARY,name COLLATE BINARY").use { statement ->
        while (statement.step()) {
            val type = statement.getText(0)
            val name = statement.getText(1)
            if (name.length > 128 || type !in setOf("table", "index", "trigger")) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
            if (type == "table" && name != "room_master_table" && name != "sqlite_sequence") {
                if (!validSqlName(name)) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
                tables += name
            }
            for (column in 0..3) hash.cell(statement, column)
        }
    }
    return tables to hash.digest().hex()
}

private fun quoted(name: String): String {
    require(name.length in 1..128 && name.all { it.isLetterOrDigit() || it == '_' })
    return "\"$name\""
}
private fun SQLiteConnection.exec(sql: String) { prepare(sql).use { it.step() } }
private fun SQLiteConnection.scalarLong(sql: String): Long = prepare(sql).use {
    if (!it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
    it.getLong(0).also { _ -> if (it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE) }
}
private fun SQLiteConnection.scalarText(sql: String): String = prepare(sql).use {
    if (!it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
    it.getText(0).also { _ -> if (it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE) }
}
private fun MessageDigest.cell(statement: SQLiteStatement, column: Int) {
    val type = statement.getColumnType(column)
    update(type.toByte())
    val bytes = when (type) {
        1 -> ByteBuffer.allocate(8).putLong(statement.getLong(column)).array()
        2 -> ByteBuffer.allocate(8).putLong(statement.getDouble(column).toRawBits()).array()
        // SQLite's byte projection preserves embedded NUL and invalid UTF-8 without a lossy
        // decode/re-encode. The storage type remains separately bound above.
        3 -> statement.getBlob(column)
        4 -> statement.getBlob(column)
        5 -> ByteArray(0)
        else -> fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
    }
    try {
        if (bytes.size > MAX_CELL_BYTES) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
        update(ByteBuffer.allocate(4).putInt(bytes.size).array()); update(bytes)
    } finally { bytes.fill(0) }
}

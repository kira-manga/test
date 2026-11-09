package com.ukkera.pos.recovery.store

import androidx.sqlite.SQLiteConnection
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeHistoryPhysicalCellV2
import com.ukkera.pos.security.NativeHistoryPhysicalReadProfileV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowWireV2
import com.ukkera.pos.security.NativeHistorySqliteTypeV2
import com.ukkera.pos.security.NativeHistoryTableV2
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Actual bounded SQLite census for Room7. Reuses security's sole physical row/key codec.
 * Structural/current-row closure is necessary but cannot replace native provenance, complete
 * event/image commitments, protected plaintext or retained producer verification.
 */
internal class SqliteBackupHistoryRead(
    private val connection: SQLiteConnection,
    override val scope: BranchScope,
    override val profile: SqliteSnapshotProfile,
    override val inspection: SnapshotInspection,
) : LocalBackupHistoryRead {
    private var active = true
    private var reading = false
    private var traversedBytes = 0L
    private val completed = mutableSetOf<NativeHistoryTableV2>()

    override suspend fun forEachRow(table: NativeHistoryTableV2, consume: suspend (NativeHistoryPhysicalRowV2) -> Unit) {
        requireActive()
        if (reading) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        reading = true
        try {
            visit(table, consume)
            completed += table // Only after exact EOF and every normally returned callback.
        } finally { reading = false }
    }

    fun requireCompleteTraversal() {
        requireActive()
        if (reading || completed != NativeHistoryTableV2.entries.toSet()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
    }

    fun close() { active = false }

    suspend fun requireStructuralIntegrity() {
        requireActive()
        // Validate all metadata before any relationship query hydrates a partner's TEXT/BLOB.
        for (table in NativeHistoryTableV2.entries) preflight(table)
        val counts = linkedMapOf<NativeHistoryTableV2, Long>()
        for (table in NativeHistoryTableV2.entries) {
            var rows = 0L
            visit(table) { row ->
                if (rows == Long.MAX_VALUE) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
                rows++
                if (table.application) requireCurrentTip(row)
            }
            counts[table] = rows
        }
        if (counts.getValue(NativeHistoryTableV2.HEAD) != 1L ||
            counts.getValue(NativeHistoryTableV2.WRITE_FRAME) != 0L ||
            counts.getValue(NativeHistoryTableV2.CAPTURE) != 0L) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        val head = singleLong("SELECT sequence FROM runtime_history_head_v2 WHERE singleton=1")
        if (counts.getValue(NativeHistoryTableV2.EVENT) != head ||
            singleLong("SELECT COALESCE(min(sequence),0) FROM runtime_history_event_v2") != 1L ||
            singleLong("SELECT COALESCE(max(sequence),0) FROM runtime_history_event_v2") != head) {
            fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        }
        requireNoRows("SELECT 1 FROM runtime_history_head_v2 h LEFT JOIN runtime_history_event_v2 e ON e.sequence=h.sequence " +
            "LEFT JOIN branch_authority b ON CAST(b.scope AS BLOB)=CAST(h.scope AS BLOB) " +
            "WHERE e.sequence IS NULL OR b.scope IS NULL OR CAST(h.eventDigest AS BLOB)!=CAST(e.eventDigest AS BLOB) " +
            "OR h.commandEpoch!=b.epoch OR h.commandSequence!=b.sequence " +
            "OR CAST(h.commandDigest AS BLOB)!=CAST(b.journalHash AS BLOB) LIMIT 1")
        requireNoRows("SELECT 1 FROM runtime_history_event_v2 e LEFT JOIN runtime_history_event_v2 p ON p.sequence=e.parentSequence " +
            "WHERE (e.sequence=1 AND (e.parentKind!=1 OR e.parentSequence!=0)) " +
            "OR (e.sequence>1 AND (e.parentKind!=2 OR e.parentSequence!=e.sequence-1 OR p.sequence IS NULL " +
            "OR CAST(e.parentDigest AS BLOB)!=CAST(p.eventDigest AS BLOB))) LIMIT 1")
        for ((table, count) in listOf("runtime_history_row_version_v2" to "changeCount", "runtime_history_chunk_v2" to "chunkCount")) {
            requireNoRows("SELECT 1 FROM $table r LEFT JOIN runtime_history_event_v2 e ON e.sequence=r.sequence " +
                "WHERE e.sequence IS NULL OR r.ordinal>=e.$count LIMIT 1")
            requireNoRows("SELECT 1 FROM runtime_history_event_v2 e WHERE e.$count!=(SELECT count(*) FROM $table r WHERE r.sequence=e.sequence) " +
                "OR (e.$count>0 AND ((SELECT min(ordinal) FROM $table r WHERE r.sequence=e.sequence)!=0 " +
                "OR (SELECT max(ordinal) FROM $table r WHERE r.sequence=e.sequence)!=e.$count-1)) LIMIT 1")
        }
        val chunk = NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES
        requireNoRows("SELECT 1 FROM runtime_history_chunk_v2 c JOIN runtime_history_event_v2 e ON e.sequence=c.sequence " +
            "WHERE length(c.bytes)!=CASE WHEN c.ordinal=e.chunkCount-1 THEN e.payloadBytes-(e.chunkCount-1)*$chunk ELSE $chunk END LIMIT 1")
        requireNoRows("SELECT 1 FROM runtime_history_row_version_v2 v LEFT JOIN runtime_history_row_version_v2 p " +
            "ON p.sequence=v.previousSequence AND p.ordinal=v.previousOrdinal WHERE " +
            "(v.version=1 AND (v.previousSequence IS NOT NULL OR v.previousOrdinal IS NOT NULL OR v.previousVersion IS NOT NULL)) " +
            "OR (v.version>1 AND (p.sequence IS NULL OR v.previousVersion IS NOT p.version OR v.version!=p.version+1 " +
            "OR v.tableTag!=p.tableTag OR v.key!=p.key OR p.sequence>v.sequence OR (p.sequence=v.sequence AND p.ordinal>=v.ordinal))) LIMIT 1")
        requireNoRows("SELECT 1 FROM runtime_history_row_version_v2 WHERE previousSequence IS NOT NULL " +
            "GROUP BY previousSequence,previousOrdinal HAVING count(*)!=1 LIMIT 1")
        requireNoRows("SELECT 1 FROM runtime_history_row_tip_v2 t LEFT JOIN runtime_history_row_version_v2 v " +
            "ON v.sequence=t.sequence AND v.ordinal=t.ordinal WHERE v.sequence IS NULL OR t.tableTag!=v.tableTag " +
            "OR t.key!=v.key OR t.version!=v.version OR t.targetRowId IS NOT v.afterRowId OR t.rowDigest IS NOT v.afterDigest " +
            "OR t.tombstone!=(CASE WHEN v.afterKind=2 THEN 1 ELSE 0 END) " +
            "OR EXISTS(SELECT 1 FROM runtime_history_row_version_v2 n WHERE n.previousSequence=v.sequence AND n.previousOrdinal=v.ordinal) LIMIT 1")
        requireNoRows("SELECT 1 FROM runtime_history_row_version_v2 v LEFT JOIN runtime_history_row_tip_v2 t " +
            "ON t.sequence=v.sequence AND t.ordinal=v.ordinal WHERE t.sequence IS NULL " +
            "AND NOT EXISTS(SELECT 1 FROM runtime_history_row_version_v2 n WHERE n.previousSequence=v.sequence AND n.previousOrdinal=v.ordinal) LIMIT 1")
        for (table in NativeHistoryPhysicalReadProfileV2.applicationTables) {
            val tips = singleLong("SELECT count(*) FROM runtime_history_row_tip_v2 WHERE tableTag=${table.tag} AND tombstone=0")
            if (tips != counts.getValue(table)) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        }
        for (table in listOf("stock_position_index_v2", "stock_allocation_index_v2", "stock_reservation_index_v2", "stock_value_index_v2")) {
            requireNoRows("SELECT 1 FROM $table s LEFT JOIN aggregate_state a ON s.scope=a.scope AND s.recordKind=a.kind AND s.recordId=a.id " +
                "WHERE a.id IS NULL OR s.aggregateGeneration!=a.revision OR CAST(s.payloadHash AS BLOB)!=CAST(a.payloadHash AS BLOB) LIMIT 1")
        }
        var operation: String? = null
        var ordinal = 0L
        visit(NativeHistoryTableV2.JOURNAL_FRAGMENT) { row ->
            val values = row.cells
            val current = (values[1] as NativeHistoryPhysicalCellV2.Text).bytes()
            val currentOperation = try { current.decodeToString(throwOnInvalidSequence = true) } finally { current.fill(0) }
            if (currentOperation != operation) { operation = currentOperation; ordinal = 0L }
            if ((values[2] as NativeHistoryPhysicalCellV2.Integer).value != ordinal++) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
            val payload = (values[4] as NativeHistoryPhysicalCellV2.Blob).bytes()
            val hash = (values[5] as NativeHistoryPhysicalCellV2.Text).bytes()
            try {
                if ((values[3] as NativeHistoryPhysicalCellV2.Integer).value != payload.size.toLong() ||
                    sha256(payload) != hash.decodeToString(throwOnInvalidSequence = true)) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
            } finally { payload.fill(0); hash.fill(0) }
        }
    }

    private suspend fun visit(table: NativeHistoryTableV2, consume: suspend (NativeHistoryPhysicalRowV2) -> Unit) {
        requireActive()
        if (table.sqlName !in profile.requiredTables) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        preflight(table)
        val columns = NativeHistoryPhysicalReadProfileV2.columns(table)
        val selected = "rowid," + columns.joinToString(",") { quote(it.name) }
        val order = table.keyColumns.joinToString(",") { quote(it) + " COLLATE BINARY" }
        connection.prepare("SELECT $selected FROM ${quote(table.sqlName)} ORDER BY $order").use { statement ->
            while (statement.step()) {
                currentCoroutineContext().ensureActive()
                requireActive()
                val cells = mutableListOf<NativeHistoryPhysicalCellV2>()
                try {
                    for (index in columns.indices) {
                        cells += when (statement.getColumnType(index + 1)) {
                            1 -> NativeHistoryPhysicalCellV2.Integer(statement.getLong(index + 1))
                            3, 4 -> {
                                val value = statement.getBlob(index + 1)
                                try {
                                    if (statement.getColumnType(index + 1) == 3) NativeHistoryPhysicalCellV2.Text(value)
                                    else NativeHistoryPhysicalCellV2.Blob(value)
                                } finally { value.fill(0) }
                            }
                            5 -> NativeHistoryPhysicalCellV2.Null
                            else -> fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                        }
                    }
                    val row = NativeHistoryPhysicalRowV2(table, statement.getLong(0), cells)
                    val encoded = try { NativeHistoryPhysicalRowWireV2.encode(row) }
                    catch (_: IllegalArgumentException) { fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE) }
                    try {
                        if (encoded.size.toLong() > NativeHistoryPhysicalReadProfileV2.MAX_DATASET_BYTES - traversedBytes) {
                            fail(LocalBackupFailure.CAPACITY_EXCEEDED)
                        }
                        traversedBytes += encoded.size
                    } finally { encoded.fill(0) }
                    consume(row)
                } finally {
                    cells.forEach { cell ->
                        when (cell) {
                            is NativeHistoryPhysicalCellV2.Text -> cell.clear()
                            is NativeHistoryPhysicalCellV2.Blob -> cell.clear()
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun preflight(table: NativeHistoryTableV2) {
        val columns = NativeHistoryPhysicalReadProfileV2.columns(table)
        for (column in columns) {
            val name = quote(column.name)
            val type = column.type.name.lowercase()
            val nullable = if (column.nullable) "$name IS NOT NULL AND " else ""
            val length = if (column.type in setOf(NativeHistorySqliteTypeV2.TEXT, NativeHistorySqliteTypeV2.BLOB))
                " OR length(CAST($name AS BLOB)) NOT BETWEEN ${column.minimum} AND ${column.maximum}" else ""
            requireNoRows("SELECT 1 FROM ${quote(table.sqlName)} WHERE $nullable(typeof($name)!='$type'$length) LIMIT 1")
        }
        val bytes = columns.joinToString("+") { "COALESCE(length(CAST(${quote(it.name)} AS BLOB)),0)" }
        if (singleLong("SELECT COALESCE(max($bytes),0) FROM ${quote(table.sqlName)}") > NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES) {
            fail(LocalBackupFailure.CAPACITY_EXCEEDED)
        }
    }

    private fun requireCurrentTip(row: NativeHistoryPhysicalRowV2) {
        val key = NativeHistoryPhysicalRowWireV2.key(row)
        val digest = NativeHistoryPhysicalRowWireV2.rowDigest(row)
        try {
            connection.prepare("SELECT tombstone,targetRowId,rowDigest FROM runtime_history_row_tip_v2 WHERE tableTag=? AND `key`=?").use {
                it.bindLong(1, row.table.tag.toLong()); it.bindBlob(2, key)
                if (!it.step() || it.getLong(0) != 0L || it.getLong(1) != row.rowId || it.getText(2) != digest.value || it.step()) {
                    fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                }
            }
        } finally { key.fill(0) }
    }

    private fun requireActive() { if (!active) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE) }
    private fun requireNoRows(sql: String) { connection.prepare(sql).use { if (it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE) } }
    private fun singleLong(sql: String): Long = connection.prepare(sql).use {
        if (!it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        it.getLong(0).also { _ -> if (it.step()) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE) }
    }
    private fun quote(name: String): String {
        require(name.length in 1..96 && name.all { it.isLetterOrDigit() || it == '_' })
        return "\"$name\""
    }
}

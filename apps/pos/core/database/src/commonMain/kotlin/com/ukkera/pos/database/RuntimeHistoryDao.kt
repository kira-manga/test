package com.ukkera.pos.database

import com.ukkera.pos.security.NativeHistoryCellMetadataV2
import com.ukkera.pos.security.NativeHistoryPhysicalCellV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowWireV2
import com.ukkera.pos.security.NativeHistoryRowKindV2
import com.ukkera.pos.security.NativeHistoryRowMetadataV2
import com.ukkera.pos.security.NativeHistorySchemaPageV2
import com.ukkera.pos.security.NativeHistoryReadPassV2
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Real D2-R data access, not a Room DAO or a supplied SQL implementation. It consumes only
 * the finite native-owned traversal. No caller chooses a table/key/predicate/offset, lends a
 * handle, supplies plaintext, registers a decoder or announces physical close.
 *
 * One bounded row/image is held at a time. This conservative implementation does NOT claim
 * F05's larger nested recovery capacity or bounded-memory normalization for that future
 * profile. Oversize is a failure; it never skips a row and never becomes a smaller valid row.
 */
internal class RuntimeHistoryDao(private val session: SelfOwnedNativeHistoryReadSession) {
    suspend fun schemaPage(): NativeHistorySchemaPageV2? = session.readSchemaPage()

    suspend fun next(): RuntimeHistoryReadItem? {
        currentCoroutineContext().ensureActive()
        val metadata = session.nextRowMetadata()?.let(RuntimeHistoryReadMetadata::fromNative) ?: return null
        return when (metadata.kind) {
            NativeHistoryRowKindV2.GROUP_END, NativeHistoryRowKindV2.PASS_END -> {
                historyShapeV2(metadata.cells.isEmpty() && metadata.rowId == null && metadata.protectedBinding == null)
                RuntimeHistoryReadItem.Boundary(metadata)
            }
            NativeHistoryRowKindV2.PHYSICAL -> {
                val table = requireNotNull(metadata.table)
                historyShapeV2(metadata.rowId != null && metadata.protectedBinding == null)
                val size = RuntimeHistoryRowCodecV2.preflight(table, metadata.cells)
                val cells = mutableListOf<NativeHistoryPhysicalCellV2>()
                try {
                    for ((index, actual) in metadata.cells.withIndex()) {
                        val bytes = readCell(index, actual)
                        try {
                            val cell = when (actual.type) {
                                RuntimeSqliteTypeV2.NULL -> NativeHistoryPhysicalCellV2.Null
                                RuntimeSqliteTypeV2.INTEGER -> NativeHistoryPhysicalCellV2.Integer(requireNotNull(actual.integerValue))
                                RuntimeSqliteTypeV2.TEXT -> {
                                    NativeHistoryPhysicalRowWireV2.requireBytes(historyColumnsV2(table)[index], bytes)
                                    NativeHistoryPhysicalCellV2.Text(bytes)
                                }
                                RuntimeSqliteTypeV2.BLOB -> {
                                    NativeHistoryPhysicalRowWireV2.requireBytes(historyColumnsV2(table)[index], bytes)
                                    NativeHistoryPhysicalCellV2.Blob(bytes)
                                }
                                else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
                            }
                            cells += cell
                        } finally { bytes.fill(0) }
                    }
                    val physical = NativeHistoryPhysicalRowV2(table, requireNotNull(metadata.rowId), cells)
                    val item = RuntimeHistoryReadItem.Row(metadata, RuntimeHistoryRowViewV2(physical), size)
                    cells.clear() // Ownership transferred, not a disposal of the returned cells.
                    item
                } finally { cells.forEach(::clearHistoryCell) }
            }
            NativeHistoryRowKindV2.IMAGE -> {
                historyShapeV2(metadata.table?.application == true && metadata.rowId != null &&
                    metadata.protectedBinding == null && metadata.cells.size == 1 &&
                    metadata.cells[0].name == "image" && metadata.cells[0].type == RuntimeSqliteTypeV2.BLOB)
                val bytes = readCell(0, metadata.cells[0])
                try {
                    val physical = RuntimeHistoryRowCodecV2.decode(bytes)
                    try {
                        historyShapeV2(physical.table == metadata.table && physical.rowId == metadata.rowId)
                        RuntimeHistoryReadItem.Row(metadata, RuntimeHistoryRowViewV2(physical), bytes.size.toLong())
                    } catch (failure: Throwable) { physical.clear(); throw failure }
                } finally { bytes.fill(0) }
            }
            NativeHistoryRowKindV2.PLAINTEXT -> {
                val binding = requireNotNull(metadata.protectedBinding)
                historyShapeV2(metadata.table?.protectedTable == binding.table && metadata.rowId != null &&
                    metadata.cells.size == 1 && metadata.cells[0].name == "plaintext" &&
                    metadata.cells[0].type == RuntimeSqliteTypeV2.BLOB)
                historyCapacityV2(metadata.cells[0].byteCount <= binding.table.maximumPlaintextBytes)
                val bytes = readCell(0, metadata.cells[0])
                try {
                    binding.requirePlaintextSize(bytes.size)
                    RuntimeHistoryReadItem.Plaintext(metadata, bytes)
                } catch (failure: Throwable) { bytes.fill(0); throw failure }
            }
        }
    }

    private suspend fun readCell(column: Int, metadata: NativeHistoryCellMetadataV2): ByteArray {
        historyShapeV2(metadata.byteCount >= 0)
        historyCapacityV2(metadata.byteCount <= RuntimeHistoryProfileV2.MAX_ROW_BYTES)
        val bytes = ByteArray(metadata.byteCount.toInt())
        var offset = 0L
        try {
            do {
                currentCoroutineContext().ensureActive()
                val chunk = session.readCellChunk()
                try {
                    val part = chunk.copyBytes()
                    try {
                        val remaining = metadata.byteCount - offset
                        val expected = minOf(remaining, RuntimeHistoryProfileV2.MAX_CHUNK_BYTES.toLong()).toInt()
                        historyShapeV2(chunk.column == column && chunk.offset == offset && chunk.totalBytes == metadata.byteCount &&
                            part.size == expected && chunk.integerValue == metadata.integerValue &&
                            chunk.last == (expected.toLong() == remaining))
                        part.copyInto(bytes, offset.toInt())
                        offset += part.size
                        if (chunk.last) break
                        historyShapeV2(part.isNotEmpty())
                    } finally { part.fill(0) }
                } finally { chunk.clear() }
            } while (true)
            historyShapeV2(offset == metadata.byteCount)
            return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }
}

/** Copied row coordinates only. Unlike native custody/source objects this DATA can be
 * captured in a private workspace and decoded without constructing native authority. */
internal class RuntimeHistoryReadMetadata(
    val pass: NativeHistoryReadPassV2,
    val kind: NativeHistoryRowKindV2,
    val table: RuntimeHistoryTableV2?,
    val rowId: Long?,
    cells: List<NativeHistoryCellMetadataV2>,
    val protectedBinding: ProtectedLocalBinding? = null,
) {
    val cells: List<NativeHistoryCellMetadataV2> = cells.toList()
    override fun toString(): String = "RuntimeHistoryReadMetadata([REDACTED])"
    companion object {
        fun fromNative(value: NativeHistoryRowMetadataV2): RuntimeHistoryReadMetadata =
            RuntimeHistoryReadMetadata(value.pass, value.kind, value.table, value.rowId, value.cells, value.protectedBinding)
    }
}

internal sealed interface RuntimeHistoryReadItem {
    val metadata: RuntimeHistoryReadMetadata
    fun clear()
    class Row(override val metadata: RuntimeHistoryReadMetadata, val row: RuntimeHistoryRowViewV2,
        val encodedBytes: Long) : RuntimeHistoryReadItem {
        override fun clear(): Unit = row.clear()
        override fun toString(): String = "RuntimeHistoryReadItemV2.Row([REDACTED])"
    }
    class Plaintext(override val metadata: RuntimeHistoryReadMetadata, val bytes: ByteArray) : RuntimeHistoryReadItem {
        override fun clear(): Unit = bytes.fill(0)
        override fun toString(): String = "RuntimeHistoryReadItemV2.Plaintext([REDACTED])"
    }
    class Boundary(override val metadata: RuntimeHistoryReadMetadata) : RuntimeHistoryReadItem {
        override fun clear() = Unit
    }
}
private fun clearHistoryCell(cell: NativeHistoryPhysicalCellV2) {
    when (cell) {
        is NativeHistoryPhysicalCellV2.Text -> cell.clear()
        is NativeHistoryPhysicalCellV2.Blob -> cell.clear()
        else -> Unit
    }
}

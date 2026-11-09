package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Complete inert recording of ONE forward-only native traversal. Replaying these bytes
 * neither reopens the original source nor creates a new native session. All original
 * semantic/cross-pass checks still run before the original read can be admitted. */
internal class RuntimeHistoryColdTranscript private constructor(val records: RuntimeHistoryColdRecords) {
    val session: SelfOwnedNativeHistoryReadSession get() = records.session
    private val scope = session.original.record.allocation.coordinates.scope
    private var captured = false
    private var disposed = false
    private var count = 0L
    val itemCount: Long get() { historyShapeV2(captured && !disposed); return count }
    private val cursors = mutableSetOf<Cursor>()

    private suspend fun capture(dao: RuntimeHistoryDao) {
        historyShapeV2(!captured && count == 0L)
        while (true) {
            currentCoroutineContext().ensureActive()
            // Native metadata preflight and its cell/row codecs allocate only within their
            // existing 5 MiB bound. Reserve before asking them for the next row, not after.
            records.memory.reserve(CAPTURE_RESERVATION)
            try {
                val item = dao.next() ?: break
                try {
                    val next = historyAddV2(count, 1, RuntimeHistoryProfileV2.MAX_DATASET_BYTES)
                    val metadata = RuntimeHistoryColdMetadataCodec.encode(item, scope)
                    try { records.append(PARTITION, METADATA_KEY, next, 0, metadata) }
                    finally { metadata.fill(0) }
                    when (item) {
                        is RuntimeHistoryReadItem.Row -> {
                            val bytes = RuntimeHistoryRowCodecV2.encode(item.row.physical)
                            try { records.append(PARTITION, PAYLOAD_KEY, next, 0, bytes) }
                            finally { bytes.fill(0) }
                        }
                        is RuntimeHistoryReadItem.Plaintext -> records.append(PARTITION, PAYLOAD_KEY, next, 0, item.bytes)
                        is RuntimeHistoryReadItem.Boundary -> Unit
                    }
                    count = next
                } finally { item.clear() }
            } finally { records.memory.release(CAPTURE_RESERVATION) }
        }
        // This is captured DATA EOF only. It is not semantic closure or native-close proof.
        captured = true
    }

    suspend fun openCursor(first: Long = 1, afterLast: Long = itemCount + 1): Cursor {
        historyShapeV2(captured && !disposed && first >= 1 && first <= afterLast && afterLast <= count + 1)
        historyCapacityV2(cursors.size < 64)
        return Cursor(first, afterLast).also { cursors += it }
    }

    inner class Cursor internal constructor(private var next: Long, private val end: Long) :
        RuntimeHistoryColdCursor<RuntimeHistoryReadItem> {
        private var current: Loan? = null
        private var closed = false
        val nextPosition: Long get() = next

        override suspend fun next(): RuntimeHistoryReadItem? {
            currentCoroutineContext().ensureActive()
            historyShapeV2(!closed && !disposed && captured)
            current?.close(); current = null
            if (next == end) return null
            val loan = read(next)
            current = loan; next++
            return loan.item
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            current?.close(); current = null; cursors.remove(this)
        }
    }

    private inner class Loan(val item: RuntimeHistoryReadItem, private val reservation: Long) {
        private var closed = false
        fun close() {
            if (closed) return
            closed = true
            try { item.clear() } finally { records.memory.release(reservation) }
        }
    }

    suspend fun <T> withItem(position: Long, use: suspend (RuntimeHistoryReadItem) -> T): T {
        val loan = read(position)
        return try { use(loan.item) } finally { loan.close() }
    }

    private suspend fun read(position: Long): Loan {
        historyShapeV2(captured && !disposed && position in 1..count)
        val metadataReservation = RuntimeHistoryColdMetadataCodec.MAX_BYTES * 4L
        records.memory.reserve(metadataReservation)
        var reservation = metadataReservation
        var transferred = false
        try {
            val metadata = checkNotNull(records.exact(PARTITION, METADATA_KEY, position, 0))
            val decoded = try {
                historyCapacityV2(metadata.payloadBytes <= RuntimeHistoryColdMetadataCodec.MAX_BYTES)
                records.withPayload(metadata) { RuntimeHistoryColdMetadataCodec.decode(it, scope) }
            } finally { metadata.clear() }
            val payload = records.exact(PARTITION, PAYLOAD_KEY, position, 0)
            val item = if (decoded.metadata.kind in BOUNDARIES) {
                try { historyShapeV2(payload == null) } finally { payload?.clear() }
                RuntimeHistoryReadItem.Boundary(decoded.metadata)
            } else {
                val actual = checkNotNull(payload)
                try {
                    // Includes codec input/cell copies and the returned row/plaintext. The
                    // raw payload and native chunk buffers have their own short reservation.
                    val decodingReservation = actual.payloadBytes * 5L + 4096L
                    records.memory.reserve(decodingReservation); reservation += decodingReservation
                    records.withPayload(actual) { decodeItem(decoded, it) }
                } finally { actual.clear() }
            }
            return Loan(item, reservation).also { transferred = true }
        } finally { if (!transferred) records.memory.release(reservation) }
    }

    private fun decodeItem(decoded: RuntimeHistoryColdMetadataCodec.Decoded, bytes: ByteArray): RuntimeHistoryReadItem {
        val m = decoded.metadata
        if (m.kind == NativeHistoryRowKindV2.PLAINTEXT) {
            historyShapeV2(m.cells.single().byteCount == bytes.size.toLong())
            checkNotNull(m.protectedBinding).requirePlaintextSize(bytes.size)
            return RuntimeHistoryReadItem.Plaintext(m, bytes.copyOf())
        }
        historyShapeV2(m.kind == NativeHistoryRowKindV2.PHYSICAL || m.kind == NativeHistoryRowKindV2.IMAGE)
        val physical = RuntimeHistoryRowCodecV2.decode(bytes)
        return try {
            historyShapeV2(physical.table == m.table && physical.rowId == m.rowId)
            if (m.kind == NativeHistoryRowKindV2.IMAGE) historyShapeV2(decoded.encodedBytes == bytes.size.toLong())
            else {
                historyShapeV2(RuntimeHistoryRowCodecV2.preflight(physical.table, m.cells) == decoded.encodedBytes &&
                    m.cells.size == physical.cells.size)
                physical.cells.forEachIndexed { index, cell ->
                    val actual = m.cells[index]
                    when (cell) {
                        NativeHistoryPhysicalCellV2.Null -> historyShapeV2(actual.type == RuntimeSqliteTypeV2.NULL)
                        is NativeHistoryPhysicalCellV2.Integer -> historyShapeV2(actual.type == RuntimeSqliteTypeV2.INTEGER &&
                            actual.integerValue == cell.value)
                        is NativeHistoryPhysicalCellV2.Text -> {
                            val value = cell.bytes()
                            try { historyShapeV2(actual.type == RuntimeSqliteTypeV2.TEXT && actual.byteCount == value.size.toLong()) }
                            finally { value.fill(0) }
                        }
                        is NativeHistoryPhysicalCellV2.Blob -> {
                            val value = cell.bytes()
                            try { historyShapeV2(actual.type == RuntimeSqliteTypeV2.BLOB && actual.byteCount == value.size.toLong()) }
                            finally { value.fill(0) }
                        }
                    }
                }
            }
            RuntimeHistoryReadItem.Row(m, RuntimeHistoryRowViewV2(physical), decoded.encodedBytes)
        } catch (failure: Throwable) { physical.clear(); throw failure }
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        if (disposed) return@withContext
        disposed = true
        var failure: Throwable? = null
        for (cursor in cursors.toList()) try { cursor.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        try { records.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        failure?.let { throw it }
    }

    companion object {
        private val PARTITION = SelfOwnedNativeHistoryDataPartition.TRANSCRIPT
        private val METADATA_KEY = "metadata/v1".encodeToByteArray()
        private val PAYLOAD_KEY = "payload/v1".encodeToByteArray()
        private val BOUNDARIES = setOf(NativeHistoryRowKindV2.GROUP_END, NativeHistoryRowKindV2.PASS_END)
        private const val CAPTURE_RESERVATION = RuntimeHistoryProfileV2.MAX_ROW_BYTES * 5L

        /** Called after the genuine native schema pass; consumes the remaining original
         * row traversal once. Failure disposes this scratch, never retries a native source. */
        suspend fun capture(session: SelfOwnedNativeHistoryReadSession,
            memory: RuntimeHistoryColdMemory = RuntimeHistoryColdMemory()): RuntimeHistoryColdTranscript {
            val records = RuntimeHistoryColdRecords.open(session, memory)
            val transcript = RuntimeHistoryColdTranscript(records)
            return try { transcript.capture(RuntimeHistoryDao(session)); transcript }
            catch (failure: Throwable) {
                try { transcript.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}

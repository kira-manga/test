package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded payload and metadata loans over the actual source-owned private workspace.
 * This class exposes DATA only; the original read session stays retained by its caller. */
internal class RuntimeHistoryColdRecords private constructor(
    val session: SelfOwnedNativeHistoryReadSession,
    private val workspace: SelfOwnedNativeHistoryWorkspace,
    val memory: RuntimeHistoryColdMemory,
) {
    private var closed = false
    private val cursors = mutableSetOf<Cursor>()

    suspend fun append(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int, bytes: ByteArray) {
        active()
        val reservation = bytes.size.toLong() * 2 + CHUNK_RESERVATION
        memory.reserve(reservation)
        try { workspace.append(partition, key, sequence, ordinal, bytes).clear() }
        finally { memory.release(reservation) }
    }

    suspend fun exact(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int): SelfOwnedNativeHistoryDataRecord? {
        active(); return workspace.exact(partition, key, sequence, ordinal)
    }

    suspend fun floor(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        beforeSequenceExclusive: Long): SelfOwnedNativeHistoryDataRecord? {
        active(); return workspace.floor(partition, key, beforeSequenceExclusive)
    }

    suspend fun discardDerived(key: ByteArray) { active(); workspace.discardDerived(key) }

    /** The callback must not retain plaintext. Decoding has a separate caller-owned loan. */
    suspend fun <T> withPayload(record: SelfOwnedNativeHistoryDataRecord, use: suspend (ByteArray) -> T): T {
        active()
        historyCapacityV2(record.payloadBytes in 0..SelfOwnedNativeHistoryWorkspace.MAX_PAYLOAD_BYTES)
        val reservation = record.payloadBytes.toLong() + CHUNK_RESERVATION
        memory.reserve(reservation)
        var bytes: ByteArray? = null
        try {
            val value = ByteArray(record.payloadBytes).also { bytes = it }
            var offset = 0
            for (ordinal in 0 until record.chunkCount) {
                active()
                val chunk = workspace.readPayloadChunk(record, ordinal)
                try {
                    historyShapeV2(chunk.size == minOf(SelfOwnedNativeHistoryWorkspace.MAX_CHUNK_BYTES, value.size - offset))
                    chunk.copyInto(value, offset); offset += chunk.size
                } finally { chunk.fill(0) }
            }
            historyShapeV2(offset == value.size)
            return use(value)
        } finally { bytes?.fill(0); memory.release(reservation) }
    }

    suspend fun openCursor(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray? = null): Cursor {
        active(); historyCapacityV2(cursors.size < 64)
        memory.reserve(PAGE_RESERVATION)
        try { return Cursor(workspace.openCursor(partition, key)).also { cursors += it } }
        catch (failure: Throwable) { memory.release(PAGE_RESERVATION); throw failure }
    }

    inner class Cursor internal constructor(private val native: SelfOwnedNativeHistoryDataCursor) {
        private var page = emptyList<SelfOwnedNativeHistoryDataRecord>()
        private var at = 0
        private var ended = false
        private var disposed = false

        /** A record remains valid only until the next call or close. */
        suspend fun next(): SelfOwnedNativeHistoryDataRecord? {
            active(); historyShapeV2(!disposed)
            if (at > 0) page[at - 1].clear()
            if (ended) return null
            if (at == page.size) {
                page.forEach { it.clear() }; page = emptyList(); at = 0
                page = native.nextPage()
                historyCapacityV2(page.size <= SelfOwnedNativeHistoryWorkspace.MAX_PAGE_RECORDS)
                if (page.isEmpty()) { ended = true; return null }
            }
            return page[at++]
        }

        suspend fun close() {
            if (disposed) return
            disposed = true
            try { native.close() }
            finally {
                page.forEach { it.clear() }; page = emptyList(); at = 0
                cursors.remove(this); memory.release(PAGE_RESERVATION)
            }
        }

        internal fun discardAfterWorkspaceClose() {
            if (disposed) return
            disposed = true
            page.forEach { it.clear() }; page = emptyList(); at = 0
            cursors.remove(this); memory.release(PAGE_RESERVATION)
        }
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed) return@withContext
        // Actual native close also invalidates abandoned cursors. Do not replace it with
        // an isOpen check or a caller assertion when the original source has been denied.
        try { workspace.close(); closed = true }
        finally { cursors.toList().forEach { it.discardAfterWorkspaceClose() } }
    }

    private suspend fun active() { currentCoroutineContext().ensureActive(); historyShapeV2(!closed) }

    companion object {
        private const val CHUNK_RESERVATION = 4L * SelfOwnedNativeHistoryWorkspace.MAX_CHUNK_BYTES
        private const val PAGE_RESERVATION = SelfOwnedNativeHistoryWorkspace.MAX_PAGE_RECORDS.toLong() *
            (SelfOwnedNativeHistoryWorkspace.MAX_KEY_BYTES * 3L + 256L)

        suspend fun open(session: SelfOwnedNativeHistoryReadSession,
            memory: RuntimeHistoryColdMemory = RuntimeHistoryColdMemory()): RuntimeHistoryColdRecords {
            val workspace = session.openDataWorkspace()
            return try {
                historyShapeV2(workspace.original === session.original)
                RuntimeHistoryColdRecords(session, workspace, memory)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { workspace.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }
}

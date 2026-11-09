package com.ukkera.pos.database

import com.ukkera.pos.security.SelfOwnedNativeHistoryDataPartition
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Ordinary external ordering of encrypted DATA locators, never of native authorities.
 * Only 64 bounded keys are buffered; merges open at most two input runs. The owner supplies
 * a unique increasing sortId for this workspace and separately checks required uniqueness. */
internal object RuntimeHistoryColdOrder {
    const val MAX_SCALARS = 16
    const val MAX_TEXT_BYTES = 1024
    const val MAX_LOCATOR_BYTES = 32
    private const val MAX_ENTRY_BYTES = 18 * 1024
    private const val ENTRY_RESERVATION = MAX_ENTRY_BYTES * 6L
    private const val CODEC_RESERVATION = MAX_ENTRY_BYTES * 3L
    private val PARTITION = SelfOwnedNativeHistoryDataPartition.DERIVED

    /** Takes ownership of a fresh small locator buffer. Immutable Strings are dropped,
     * not claimed erased; an entry is usable only until its cursor advances or closes. */
    class Entry(order: List<StockSqlScalar>, val payload: ByteArray) {
        private var values = try {
            validateOrder(order); historyCapacityV2(payload.size <= MAX_LOCATOR_BYTES); order.toList()
        } catch (failure: Throwable) { payload.fill(0); throw failure }
        private var cleared = false
        val order: List<StockSqlScalar> get() { historyShapeV2(!cleared); return values }
        fun clear() { payload.fill(0); values = emptyList(); cleared = true }
        override fun toString(): String = "RuntimeHistoryColdOrder.Entry([REDACTED])"
    }

    fun compare(left: List<StockSqlScalar>, right: List<StockSqlScalar>): Int {
        historyShapeV2(left.size == right.size)
        for (at in left.indices) {
            val a = left[at]; val b = right[at]
            val difference = when {
                a is StockSqlScalar.Text && b is StockSqlScalar.Text -> a.value.compareTo(b.value)
                a is StockSqlScalar.Number && b is StockSqlScalar.Number -> a.value.compareTo(b.value)
                else -> malformed()
            }
            if (difference != 0) return difference
        }
        return 0
    }

    /** Input entries transfer ownership to this helper. The producer must release any
     * preceding hydrated input before producing the next locator; no input estate is kept. */
    suspend fun open(records: RuntimeHistoryColdRecords, sortId: Long,
        input: suspend () -> Entry?): RuntimeHistoryColdCursor<Entry> {
        historyShapeV2(sortId >= 0)
        val sort = Sort(records, sortId)
        return try { sort.build(input); sort.result() }
        catch (failure: Throwable) {
            try { sort.discard() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private class Loan(val entry: Entry, val position: Long, private val memory: RuntimeHistoryColdMemory) {
        private var closed = false
        fun close() {
            if (closed) return
            closed = true
            try { entry.clear() } finally { memory.release(ENTRY_RESERVATION) }
        }
    }

    private class Sort(private val records: RuntimeHistoryColdRecords, private val id: Long) {
        private var level = 0
        private var runs = 0L
        private var pendingLevel: Int? = null
        private var pendingRuns = 0L
        private var count = 0L
        private var kinds: List<Boolean>? = null
        private val readers = mutableSetOf<RunReader>()

        private fun key(atLevel: Int, run: Long): ByteArray =
            "runtime-order/v1/$id/$atLevel/$run".encodeToByteArray()

        private fun shape(entry: Entry) {
            val actual = entry.order.map { it is StockSqlScalar.Text }
            val expected = kinds
            if (expected == null) kinds = actual else historyShapeV2(actual == expected)
        }

        suspend fun build(input: suspend () -> Entry?) {
            val buffer = mutableListOf<Loan>()
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    records.memory.reserve(ENTRY_RESERVATION)
                    var entry: Entry? = null
                    var transferred = false
                    try {
                        entry = input() ?: break
                        shape(entry)
                        historyShapeV2(count < Long.MAX_VALUE)
                        count++
                        buffer += Loan(entry, count, records.memory)
                        transferred = true
                    } finally {
                        if (!transferred) { entry?.clear(); records.memory.release(ENTRY_RESERVATION) }
                    }
                    if (buffer.size == 64) flush(buffer)
                }
                if (buffer.isNotEmpty()) flush(buffer)
            } finally { buffer.forEach { it.close() }; buffer.clear() }
            while (runs > 1) {
                historyShapeV2(level < 63)
                pendingLevel = level + 1; pendingRuns = 0
                var first = 1L
                while (true) {
                    val second = if (first < runs) first + 1 else null
                    pendingRuns++ // Includes a partially written run in failure cleanup.
                    merge(first, second, pendingRuns)
                    if (second == null || second == runs) break
                    first = second + 1
                }
                level = checkNotNull(pendingLevel); runs = pendingRuns
                pendingLevel = null; pendingRuns = 0
            }
        }

        private suspend fun flush(buffer: MutableList<Loan>) {
            buffer.sortWith(Comparator { a, b -> compareLoans(a, b) })
            historyShapeV2(runs < Long.MAX_VALUE)
            val runKey = key(level, ++runs)
            try {
                for ((at, value) in buffer.withIndex()) write(runKey, at + 1L, value)
                header(runKey, buffer.size.toLong())
            } finally { runKey.fill(0); buffer.forEach { it.close() }; buffer.clear() }
        }

        private suspend fun merge(first: Long, second: Long?, output: Long) {
            val leftKey = key(level, first)
            val rightKey = second?.let { key(level, it) }
            val outputKey = key(checkNotNull(pendingLevel), output)
            try {
                val left = openRun(leftKey)
                val right = rightKey?.let { openRun(it) }
                var a = left.nextLoan(); var b = right?.nextLoan()
                var written = 0L
                while (a != null || b != null) {
                    currentCoroutineContext().ensureActive()
                    val takeLeft = b == null || a != null && compareLoans(a, b) <= 0
                    val chosen = checkNotNull(if (takeLeft) a else b)
                    historyShapeV2(written < Long.MAX_VALUE)
                    write(outputKey, ++written, chosen)
                    if (takeLeft) a = left.nextLoan() else b = checkNotNull(right).nextLoan()
                }
                header(outputKey, written)
                left.close(); right?.close()
                records.discardDerived(leftKey)
                if (rightKey != null) records.discardDerived(rightKey)
            } finally { leftKey.fill(0); rightKey?.fill(0); outputKey.fill(0) }
        }

        private suspend fun write(key: ByteArray, sequence: Long, loan: Loan) {
            records.memory.reserve(CODEC_RESERVATION)
            val out = try { HistoryBinaryWriterV2(MAX_ENTRY_BYTES) }
                catch (failure: Throwable) { records.memory.release(CODEC_RESERVATION); throw failure }
            try {
                out.int(1); out.long(loan.position); out.int(loan.entry.order.size)
                for (scalar in loan.entry.order) when (scalar) {
                    is StockSqlScalar.Number -> { out.byte(0); out.long(scalar.value) }
                    is StockSqlScalar.Text -> {
                        out.byte(1)
                        val bytes = textBytes(scalar.value)
                        try { out.bytes(bytes, MAX_TEXT_BYTES) } finally { bytes.fill(0) }
                    }
                }
                out.bytes(loan.entry.payload, MAX_LOCATOR_BYTES)
                val bytes = out.finish()
                try { records.append(PARTITION, key, sequence, 0, bytes) } finally { bytes.fill(0) }
            } finally { out.clear(); records.memory.release(CODEC_RESERVATION) }
        }

        private suspend fun header(key: ByteArray, entries: Long) {
            historyShapeV2(entries in 1..count)
            records.memory.reserve(CODEC_RESERVATION)
            val out = try { HistoryBinaryWriterV2(12) }
                catch (failure: Throwable) { records.memory.release(CODEC_RESERVATION); throw failure }
            try {
                out.int(1); out.long(entries)
                val bytes = out.finish()
                try { records.append(PARTITION, key, 0, 0, bytes) } finally { bytes.fill(0) }
            } finally { out.clear(); records.memory.release(CODEC_RESERVATION) }
        }

        private suspend fun openRun(key: ByteArray): RunReader {
            historyCapacityV2(readers.size < 2)
            val cursor = records.openCursor(PARTITION, key)
            return try {
                val record = checkNotNull(cursor.next())
                historyShapeV2(record.sequence == 0L && record.ordinal == 0 && record.payloadBytes == 12)
                val expected = records.withPayload(record) { bytes ->
                    val input = HistoryBinaryReaderV2(bytes, 12)
                    try {
                        historyShapeV2(input.int() == 1)
                        input.long().also { historyShapeV2(it in 1..count); input.end() }
                    } finally { input.clear() }
                }
                RunReader(cursor, expected).also { readers += it }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { cursor.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }

        private inner class RunReader(private val cursor: RuntimeHistoryColdRecords.Cursor, val expected: Long) {
            private var seen = 0L
            private var current: Loan? = null
            private var closed = false
            private var failed = false

            suspend fun nextLoan(): Loan? {
                historyShapeV2(!closed && !failed)
                current?.close(); current = null
                return try {
                    val record = cursor.next()
                    if (record == null) { historyShapeV2(seen == expected); return null }
                    historyShapeV2(seen < expected && record.sequence == seen + 1 && record.ordinal == 0)
                    historyCapacityV2(record.payloadBytes <= MAX_ENTRY_BYTES)
                    records.memory.reserve(ENTRY_RESERVATION)
                    var entry: Entry? = null
                    var transferred = false
                    try {
                        val loan = records.withPayload(record) { bytes ->
                            val input = HistoryBinaryReaderV2(bytes, MAX_ENTRY_BYTES)
                            try {
                                historyShapeV2(input.int() == 1)
                                val position = input.long(); historyShapeV2(position in 1..count)
                                val size = input.int(); historyShapeV2(size in 0..MAX_SCALARS)
                                val order = List(size) { when (input.byte()) {
                                    0 -> StockSqlScalar.Number(input.long())
                                    1 -> {
                                        val value = input.bytes(MAX_TEXT_BYTES)
                                        try { StockSqlScalar.Text(decodeText(value)) } finally { value.fill(0) }
                                    }
                                    else -> malformed()
                                } }
                                val payload = input.bytes(MAX_LOCATOR_BYTES)
                                try {
                                    input.end()
                                    val value = Entry(order, payload).also { entry = it; shape(it) }
                                    Loan(value, position, records.memory)
                                } catch (failure: Throwable) { payload.fill(0); throw failure }
                            } finally { input.clear() }
                        }
                        current = loan; seen++; transferred = true
                        loan
                    } finally {
                        if (!transferred) { entry?.clear(); records.memory.release(ENTRY_RESERVATION) }
                    }
                } catch (failure: Throwable) { failed = true; throw failure }
            }

            suspend fun close(): Unit = withContext(NonCancellable) {
                if (closed) return@withContext
                closed = true
                try { current?.close() }
                finally { current = null; try { cursor.close() } finally { readers.remove(this@RunReader) } }
            }
        }

        suspend fun result(): RuntimeHistoryColdCursor<Entry> {
            val reader = if (runs == 0L) null else {
                val runKey = key(level, 1)
                try { openRun(runKey).also { historyShapeV2(it.expected == count) } } finally { runKey.fill(0) }
            }
            return object : RuntimeHistoryColdCursor<Entry> {
                private var closed = false
                private var disposing = false
                override suspend fun next(): Entry? {
                    currentCoroutineContext().ensureActive(); historyShapeV2(!closed && !disposing)
                    return reader?.nextLoan()?.entry
                }
                override suspend fun close() {
                    if (closed) return
                    disposing = true
                    discard()
                    closed = true
                }
            }
        }

        suspend fun discard(): Unit = withContext(NonCancellable) {
            var failure: Throwable? = null
            for (reader in readers.toList()) try { reader.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
            // If guarded cleanup is unavailable, the owning workspace's actual terminal
            // close must reclaim the scratch. Never turn a cleanup error into success.
            failure?.let { throw it }
            discardLevel(level, runs)
            pendingLevel?.let { discardLevel(it, pendingRuns) }
            runs = 0; pendingLevel = null; pendingRuns = 0; kinds = null
        }

        private suspend fun discardLevel(atLevel: Int, entries: Long) {
            var run = 1L
            while (run <= entries) {
                val runKey = key(atLevel, run)
                try { records.discardDerived(runKey) } finally { runKey.fill(0) }
                if (run == entries) break
                run++
            }
        }
    }

    private fun compareLoans(left: Loan, right: Loan): Int =
        compare(left.entry.order, right.entry.order).let { if (it == 0) left.position.compareTo(right.position) else it }

    private fun validateOrder(order: List<StockSqlScalar>) {
        historyCapacityV2(order.size <= MAX_SCALARS)
        for (scalar in order) if (scalar is StockSqlScalar.Text) textBytes(scalar.value).fill(0)
    }

    private fun textBytes(value: String): ByteArray {
        historyCapacityV2(value.length <= MAX_TEXT_BYTES)
        val bytes = try { value.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) { malformed() }
        if (bytes.size > MAX_TEXT_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        return bytes
    }

    private fun decodeText(bytes: ByteArray): String =
        try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { malformed() }

    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}

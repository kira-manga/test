package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Finite literal read evidence in the existing v1 journal; never a physical streaming route. */
public object StockReadEvidenceV1 {
    public const val KIND: String = "stock.payment.read_evidence"
    public const val SCHEMA: String = "$KIND/v1"
    public const val MAX_BYTES: Int = 524_288
    private const val CHUNK_BYTES: Int = 60_000
    private const val FORMAT: String = "ukkera.stock/payment-read/v1"
    internal const val MAX_SOURCES: Int = 4096

    internal fun requiredChangeCount(byteCount: Int): Int {
        stockSqlRequireV2(byteCount in 1..MAX_BYTES)
        return (byteCount + CHUNK_BYTES - 1) / CHUNK_BYTES
    }

    internal fun key(operation: com.ukkera.pos.types.OperationId, ordinal: Int): AggregateKey {
        stockSqlRequireV2(ordinal in 0..8)
        val hex = sha256("$FORMAT/${operation.value}/$ordinal".encodeToByteArray()).value
        return AggregateKey(KIND, DomainId("${hex.take(8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-a${hex.substring(17, 20)}-${hex.substring(20, 32)}"))
    }

    internal fun changes(context: CommandContext, stream: ByteArray): List<AggregateChange> = changes(context.scope,
        StreamedProducerIdentityV2(context.operationId, context.key, context.originalActorId, context.semanticDigest), stream)

    private fun changes(scope: BranchScope, producer: StreamedProducerIdentityV2, stream: ByteArray): List<AggregateChange> {
        stockSqlRequireV2(stream.isNotEmpty() && stream.size <= MAX_BYTES)
        val count = requiredChangeCount(stream.size)
        val hash = sha256(stream)
        return (0 until count).map { ordinal ->
            val part = stream.copyOfRange(ordinal * CHUNK_BYTES, minOf(stream.size, (ordinal + 1) * CHUNK_BYTES))
            val bytes = StockFrameWriterV2(MAX_STORED_BYTES).run {
                text(FORMAT, 48); scope(scope); producer(producer)
                int(ordinal); int(count); int(stream.size); digest(hash); bytes(part, CHUNK_BYTES); finish()
            }
            AggregateChange(key(producer.operationId, ordinal), null, SCHEMA, StoredBytes(bytes))
        }
    }

    /** Pure literal proposal encoding for a separately authenticated original BEFORE cut.
     * This does not create a capture, grant, current read view, EOF proof or storage admission.
     * The live writer still accepts ONLY its actual loan-owned sealed capture; the historical
     * owner independently compares every proposed observation/effect with its native cut. */
    public fun proposedChanges(scope: BranchScope, producer: StreamedProducerIdentityV2,
        sources: List<StreamedSourceObservationV2>): List<AggregateChange> {
        val frames = linkedMapOf<Pair<String, String>, ByteArray>()
        var size = 0
        try {
            for (source in sources) {
                stockSqlRequireV2(readSource(source))
                val frame = RetainedCommandFrameV2.Source(source)
                val key = RetainedCommandFramesV2.key(frame)
                val bytes = RetainedCommandFramesV2.encode(frame)
                val previous = frames[key]
                if (previous != null) {
                    try { stockSqlRequireV2(previous.contentEquals(bytes)) } finally { bytes.fill(0) }
                    continue
                }
                if (frames.size >= MAX_SOURCES || bytes.size + 4 > MAX_BYTES - size) {
                    bytes.fill(0)
                    throw StockReadEvidenceCapacityExceeded()
                }
                frames[key] = bytes
                size += bytes.size + 4
            }
            stockSqlRequireV2(frames.isNotEmpty())
            val ordered = frames.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            StockReadObservations(scope, ordered.map {
                (RetainedCommandFramesV2.decode(it.value) as RetainedCommandFrameV2.Source).value
            }).requireRanges()
            val stream = ByteArray(size)
            try {
                var at = 0
                for ((_, bytes) in ordered) {
                    for (shift in 24 downTo 0 step 8) stream[at++] = (bytes.size ushr shift).toByte()
                    bytes.copyInto(stream, at); at += bytes.size
                }
                stockSqlRequireV2(at == size)
                return changes(scope, producer, stream)
            } finally { stream.fill(0) }
        } finally { frames.values.forEach { it.fill(0) } }
    }

    /** Data-only member/terminal encoding. The caller must select COMPLETE genuine original
     * membership in the actual fixed-query order; this method proves neither fact. */
    public fun proposedRangeSources(query: StockQueryV2,
        records: List<StockIndexRecordV2>): List<StreamedSourceObservationV2> {
        if (records.size >= MAX_SOURCES) throw StockReadEvidenceCapacityExceeded()
        val chain = StockRangeChainV2(query)
        return buildList {
            for (row in records) {
                add(StreamedSourceObservationV2.StockMember(query, chain.count, row))
                chain.append(row)
            }
            add(chain.finish())
        }
    }

    private fun readSource(source: StreamedSourceObservationV2): Boolean =
        source is StreamedSourceObservationV2.Current || source is StreamedSourceObservationV2.CurrentAbsent ||
            source is StreamedSourceObservationV2.StockItemAbsent || source is StreamedSourceObservationV2.Claim ||
            source is StreamedSourceObservationV2.StockMember || source is StreamedSourceObservationV2.StockRange

    /** Decode only literal original observations. This does not make a current view or a producer pass. */
    public fun read(original: RetainedCommandEvidence): StockReadObservations? {
        stockSqlRequireV2(original.committed)
        return decode(original.scope, StreamedProducerIdentityV2(original.operationId, original.command,
            original.originalActorId, original.semanticDigest), original.aggregateWrites)
    }

    /** Pure interpretation of actual effective proposal bytes, not an evidence mint or a producer pass. */
    public fun decodeChanges(scope: BranchScope, producer: StreamedProducerIdentityV2,
        changes: List<AggregateChange>): StockReadObservations? = decode(scope, producer, changes.map {
        RetainedAggregateWrite(it.key, (it.expectedRevision ?: Revision(0)).next(), it.schema, it.payload, sha256(it.payload.copyBytes()))
    })

    private fun decode(scope: BranchScope, expected: StreamedProducerIdentityV2,
        all: List<RetainedAggregateWrite>): StockReadObservations? {
        val writes = all.filter { it.key.kind == KIND }
        if (writes.isEmpty()) return null
        stockSqlRequireV2(writes.size in 1..9)
        var total: Int? = null
        var hash: PayloadDigest? = null
        val parts = arrayOfNulls<ByteArray>(writes.size)
        for (write in writes) {
            stockSqlRequireV2(write.schema == SCHEMA && write.storageGeneration == Revision(1) && sha256(write.payload.copyBytes()) == write.payloadDigest)
            StockFrameReaderV2(write.payload.copyBytes(), MAX_STORED_BYTES).run {
                stockSqlRequireV2(text(48) == FORMAT && scope() == scope && producer() == expected)
                val ordinal = int(); val count = int(); val length = int(); val digest = digest()
                stockSqlRequireV2(count == writes.size && ordinal in parts.indices && parts[ordinal] == null &&
                    write.key == key(expected.operationId, ordinal) && length in 1..MAX_BYTES &&
                    count == (length + CHUNK_BYTES - 1) / CHUNK_BYTES)
                stockSqlRequireV2(total == null || total == length); stockSqlRequireV2(hash == null || hash == digest)
                total = length; hash = digest
                val part = bytes(CHUNK_BYTES)
                stockSqlRequireV2(part.size == minOf(CHUNK_BYTES, length - ordinal * CHUNK_BYTES))
                parts[ordinal] = part; end()
            }
        }
        val stream = ByteArray(checkNotNull(total))
        var at = 0
        for (part in parts) { val value = checkNotNull(part); value.copyInto(stream, at); at += value.size }
        stockSqlRequireV2(at == stream.size && sha256(stream) == hash)
        val sources = mutableListOf<StreamedSourceObservationV2>()
        var previous: Pair<String, String>? = null
        try {
            var cursor = 0
            while (cursor < stream.size) {
                stockSqlRequireV2(stream.size - cursor >= 4)
                var length = 0L
                repeat(4) { length = (length shl 8) or (stream[cursor++].toLong() and 255) }
                stockSqlRequireV2(length in 1L..STOCK_FRAME_BYTES_V2.toLong() && length <= stream.size - cursor)
                val bytes = stream.copyOfRange(cursor, cursor + length.toInt()); cursor += length.toInt()
                val frame = RetainedCommandFramesV2.decode(bytes) as? RetainedCommandFrameV2.Source ?: stockSqlUnavailableV2()
                stockSqlRequireV2(RetainedCommandFramesV2.encode(frame).contentEquals(bytes) && sources.size < MAX_SOURCES)
                val key = RetainedCommandFramesV2.key(frame)
                previous?.let { stockSqlRequireV2(it.first < key.first || it.first == key.first && it.second < key.second) }
                previous = key
                stockSqlRequireV2(readSource(frame.value))
                sources += frame.value
                bytes.fill(0)
            }
        } finally { stream.fill(0); parts.forEach { it?.fill(0) } }
        stockSqlRequireV2(sources.isNotEmpty())
        return StockReadObservations(scope, sources).also { it.requireRanges() }
    }
}

/** Read-only literal original rows/claims and complete member streams; no lifetime or authority is minted. */
public class StockReadObservations internal constructor(public val scope: BranchScope, sources: List<StreamedSourceObservationV2>) {
    private val values = sources.toList()
    public val sources: List<StreamedSourceObservationV2> get() = values.toList()
    /** Exact canonical bytes of one literal member of THIS finite capture. This is only
     * frame identity/correspondence, not a current view, authority or complete-history proof.
     * The full decoder and root reader stay core-internal; callers own and may clear this copy. */
    public fun copySourceFrameBytes(source: StreamedSourceObservationV2): ByteArray {
        val literal = values.singleOrNull { it == source } ?: stockSqlUnavailableV2()
        return RetainedCommandFramesV2.encode(RetainedCommandFrameV2.Source(literal))
    }
    public fun current(key: AggregateKey): StreamedSourceObservationV2.Current? =
        values.filterIsInstance<StreamedSourceObservationV2.Current>().singleOrNull { it.key == key }
    public fun claim(key: ClaimKey): StreamedSourceObservationV2.Claim? =
        values.filterIsInstance<StreamedSourceObservationV2.Claim>().singleOrNull { it.key == key }
    public fun absent(key: AggregateKey): Boolean = values.any { it is StreamedSourceObservationV2.CurrentAbsent && it.key == key }
    /** Narrow stock item absence only; unrelated missing stock never becomes an empty cut. */
    public fun absentStockItem(key: AggregateKey): Boolean = values.any {
        it is StreamedSourceObservationV2.StockItemAbsent && it.key == key
    }
    public fun range(query: StockQueryV2, consume: (StockIndexRecordV2) -> Unit) {
        val terminal = values.filterIsInstance<StreamedSourceObservationV2.StockRange>().singleOrNull { it.query == query }
            ?: stockSqlUnavailableV2()
        val chain = StockRangeChainV2(query)
        for (member in values.filterIsInstance<StreamedSourceObservationV2.StockMember>().filter { it.query == query }) {
            stockSqlRequireV2(member.ordinal == chain.count)
            chain.append(member.record); consume(member.record)
        }
        stockSqlRequireV2(chain.finish() == terminal)
    }
    internal fun requireRanges() {
        val queries = values.filterIsInstance<StreamedSourceObservationV2.StockRange>().map { it.query }.toSet()
        stockSqlRequireV2(values.filterIsInstance<StreamedSourceObservationV2.StockMember>().all { it.query in queries })
        for (query in queries) range(query) { row ->
            val source = current(row.identity.key) ?: stockSqlUnavailableV2()
            stockSqlRequireV2(source.generation == row.identity.generation && source.payloadHash == row.identity.payloadHash)
        }
        val current = values.filterIsInstance<StreamedSourceObservationV2.Current>().map { it.key }.toSet()
        stockSqlRequireV2(values.filterIsInstance<StreamedSourceObservationV2.CurrentAbsent>().none { it.key in current })
        stockSqlRequireV2(values.filterIsInstance<StreamedSourceObservationV2.StockItemAbsent>().none { it.key in current })
    }
}

/** Explicit finite capacity refusal, not a truncated stock cut or evidence of empty stock. */
public class StockReadEvidenceCapacityExceeded internal constructor() : IllegalStateException("Stock payment evidence capacity required")

/** Minted on the actual command's SQL loan. Caller-authored matching bytes cannot seal this capture. */
public class StockReadCapture internal constructor(private val access: StockRelationalAccess, internal val context: CommandContext) {
    private class Range(var next: StockPageCursor?, val chain: StockRangeChainV2)
    private val sources = linkedMapOf<Pair<String, String>, ByteArray>()
    private val ranges = mutableMapOf<StockQueryV2, Range>()
    private var size = 0
    private var sealed: List<AggregateChange>? = null
    private var discarded = false
    internal val collecting: Boolean get() = sealed == null && !discarded
    internal val hasEvidence: Boolean get() = sealed != null

    internal fun append(value: StreamedSourceObservationV2) {
        if (!collecting) return
        val frame = RetainedCommandFrameV2.Source(value)
        val key = RetainedCommandFramesV2.key(frame)
        val bytes = RetainedCommandFramesV2.encode(frame)
        val old = sources[key]
        if (old != null) { stockSqlRequireV2(old.contentEquals(bytes)); return }
        if (sources.size >= StockReadEvidenceV1.MAX_SOURCES || bytes.size + 4 > StockReadEvidenceV1.MAX_BYTES - size)
            throw StockReadEvidenceCapacityExceeded()
        sources[key] = bytes; size += bytes.size + 4
    }
    internal fun aggregate(key: AggregateKey, value: StoredAggregate?) {
        if (!collecting) return
        append(if (value != null) StreamedSourceObservationV2.Current(key, value.revision, value.schema, value.payload, value.digest)
            else if (key.kind == StockRelationalKindsV2.ITEM) StreamedSourceObservationV2.StockItemAbsent(key)
            else StreamedSourceObservationV2.CurrentAbsent(key))
    }
    internal fun pageStart(query: StockQueryV2, after: StockPageCursor?) {
        if (!collecting) return
        val range = ranges[query]
        stockSqlRequireV2(if (after == null) range == null else range != null && range.next === after)
        if (range == null) ranges[query] = Range(null, StockRangeChainV2(query))
    }
    internal fun pageEnd(query: StockQueryV2, page: StockIndexPage) {
        if (!collecting) return
        val range = ranges[query] ?: stockSqlUnavailableV2()
        for (row in page.records) { append(StreamedSourceObservationV2.StockMember(query, range.chain.count, row)); range.chain.append(row) }
        range.next = page.next
        if (page.next == null) { append(range.chain.finish()); ranges.remove(query) }
    }
    /** Actual same-loan size, before sealing; permits a truthful bounded accounting refusal. */
    public suspend fun requiredChangeCount(): Int {
        access.loan.requireBody()
        stockSqlRequireV2(access.loan.readCapture === this && collecting && ranges.isEmpty() && sources.isNotEmpty())
        return StockReadEvidenceV1.requiredChangeCount(size)
    }
    public suspend fun finish(): List<AggregateChange> {
        access.loan.requireBody()
        stockSqlRequireV2(access.loan.readCapture === this && collecting && ranges.isEmpty() && sources.isNotEmpty())
        val stream = ByteArray(size)
        var at = 0
        for ((_, bytes) in sources.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            for (shift in 24 downTo 0 step 8) stream[at++] = (bytes.size ushr shift).toByte()
            bytes.copyInto(stream, at); at += bytes.size
        }
        try { sealed = StockReadEvidenceV1.changes(context, stream) } finally { stream.fill(0) }
        return checkNotNull(sealed).toList()
    }
    /** Same-loan literal observations, including owner-generated range EOF; still not typed authority. */
    public suspend fun observations(): StockReadObservations {
        access.loan.requireBody()
        stockSqlRequireV2(access.loan.readCapture === this && !discarded && ranges.isEmpty())
        return StockReadObservations(context.scope, sources.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map {
            (RetainedCommandFramesV2.decode(it.value) as RetainedCommandFrameV2.Source).value
        }).also { it.requireRanges() }
    }
    internal fun clear() {
        discarded = true
        sources.values.forEach { it.fill(0) }; sources.clear(); ranges.clear(); sealed = null
    }
    /** Only for a refused proposal; this cannot make caller-provided evidence valid. */
    public suspend fun discard() {
        access.loan.requireBody(); stockSqlRequireV2(access.loan.readCapture === this && sealed == null)
        clear()
    }
    internal suspend fun validate(plan: ProposedOutcome.Apply, dao: StockRelationalDao) {
        access.loan.requireBody()
        val expected = sealed ?: stockSqlUnavailableV2()
        val actual = plan.changes.filter { it.key.kind == StockReadEvidenceV1.KIND }
        stockSqlRequireV2(!discarded && actual.size == expected.size && actual.all { change -> expected.any {
            it.key == change.key && change.expectedRevision == null && it.schema == change.schema && it.payload == change.payload
        } })
        // Reopen every literal point and every fixed SQL range on this SAME loan before effects.
        val decoded = sources.values.map { (RetainedCommandFramesV2.decode(it) as RetainedCommandFrameV2.Source).value }
        for (source in decoded) when (source) {
            is StreamedSourceObservationV2.Current -> {
                val row = dao.aggregate(source.key) ?: stockSqlUnavailableV2()
                stockSqlRequireV2(row.revision == source.generation && row.schema == source.schema && row.payload == source.payload && row.digest == source.payloadHash)
            }
            is StreamedSourceObservationV2.CurrentAbsent -> stockSqlRequireV2(dao.aggregate(source.key) == null)
            is StreamedSourceObservationV2.StockItemAbsent -> stockSqlRequireV2(dao.aggregate(source.key) == null &&
                dao.claim(ClaimKey("local.stock.item-id", source.key.id.value)) == null)
            is StreamedSourceObservationV2.Claim -> stockSqlRequireV2(dao.claim(source.key) == source.ownerId)
            is StreamedSourceObservationV2.StockRange -> {
                val chain = StockRangeChainV2(source.query)
                var cursor: StockPageCursor? = null
                do {
                    val page = dao.page(source.query, cursor)
                    for (row in page.records) {
                        val bytes = RetainedCommandFramesV2.encode(RetainedCommandFrameV2.Source(StreamedSourceObservationV2.StockMember(source.query, chain.count, row)))
                        stockSqlRequireV2(sources[RetainedCommandFramesV2.key(RetainedCommandFrameV2.Source(StreamedSourceObservationV2.StockMember(source.query, chain.count, row)))]?.contentEquals(bytes) == true)
                        chain.append(row)
                    }
                    cursor = page.next
                } while (cursor != null)
                stockSqlRequireV2(chain.finish() == source)
            }
            is StreamedSourceObservationV2.StockMember -> Unit
            else -> stockSqlUnavailableV2()
        }
    }
}

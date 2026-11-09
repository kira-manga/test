package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Indexed DATA from the one complete native transcript. The index crossmatches the
 * independently traversed physical rows; it does not replace event semantics, native
 * custody, authorization replay, business producers or the scanner's final closure.
 * No decoded value from this class is an original-command or permission capability. */
internal class RuntimeHistoryColdIndex private constructor(
    private val transcript: RuntimeHistoryColdTranscript,
) : RuntimeHistoryColdBusinessStore {
    private val records = transcript.records
    override val session: SelfOwnedNativeHistoryReadSession get() = transcript.session
    private val census = LongArray(RuntimeHistoryTableV2.entries.size)
    private val censusEnded = BooleanArray(census.size)
    private val live = LongArray(census.size)
    private val keyCounts = LongArray(census.size)
    private var tips = 0L
    private var businessTips = 0L
    private var eventCount = 0L
    private var mutationCount = 0L
    private var lineageCount = 0L
    private var commands = 0L
    private var eventsEnded = false
    private var lineagesEnded = false
    private var commandsEnded = false
    private var built = false
    private var nextSort = 0L
    private var joined = 0L
    private var joinedMutations = 0L
    private var lastJoinedSequence = 0L

    private data class Location(val version: Long, val image: Long)
    internal data class EventRange(val first: Long, val afterLast: Long)
    private class Event(val sequence: Long, val start: Long, val changes: Int) {
        var mutations = 0
    }
    private class Lineage(val row: RuntimeHistoryRowVersionRowV2, val position: Long) {
        var image = 0L
        var tip = false
        var current = false
        var keyClosed = false
        fun clear() { row.key.fill(0) }
    }
    private class Command(val sequence: Long, val id: OperationId, val journal: Long) {
        var operation = 0L
    }
    private var event: Event? = null
    private var lineage: Lineage? = null
    private var command: Command? = null

    override fun reserveOwnedBytes(bytes: Long) = records.memory.reserve(bytes)
    override fun releaseOwnedBytes(bytes: Long) = records.memory.release(bytes)
    override fun reserveOwnedCommand(versions: Int) = records.memory.reserveOwnedCommand(versions)
    override fun releaseOwnedCommand(versions: Int) = records.memory.releaseOwnedCommand(versions)
    override val tipDelta: Long get() { ready(); return businessTips }
    override fun currentCount(table: RuntimeHistoryTableV2): Long {
        ready()
        return if (table == RuntimeHistoryTableV2.BRANCH || table.protectedTable != null) 0 else live[table.ordinal]
    }

    private suspend fun build() {
        val cursor = transcript.openCursor()
        try {
            while (true) {
                val position = cursor.nextPosition
                val item = cursor.next() ?: break
                when (item) {
                    is RuntimeHistoryReadItem.Row -> row(item, position)
                    is RuntimeHistoryReadItem.Boundary -> boundary(item.metadata, position)
                    is RuntimeHistoryReadItem.Plaintext -> Unit // Authenticated DATA remains in its exact transcript position.
                }
            }
            historyShapeV2(censusEnded.all { it } && eventsEnded && lineagesEnded && commandsEnded &&
                event == null && lineage == null && command == null && eventCount == census[RuntimeHistoryTableV2.EVENT.ordinal] &&
                mutationCount == lineageCount && lineageCount == census[RuntimeHistoryTableV2.ROW_VERSION.ordinal] &&
                tips == census[RuntimeHistoryTableV2.ROW_TIP.ordinal] && commands == census[RuntimeHistoryTableV2.JOURNAL.ordinal] &&
                commands == census[RuntimeHistoryTableV2.OPERATION.ordinal])
            for (table in RuntimeHistoryTableV2.entries.filter { it.application })
                historyShapeV2(live[table.ordinal] == census[table.ordinal])
            built = true
        } finally { cursor.close(); lineage?.clear(); lineage = null }
    }

    private suspend fun row(item: RuntimeHistoryReadItem.Row, position: Long) {
        val row = item.row
        when (item.metadata.pass) {
            NativeHistoryReadPassV2.CENSUS -> {
                historyShapeV2(!censusEnded[row.table.ordinal] && item.metadata.kind == NativeHistoryRowKindV2.PHYSICAL)
                val key = row.key()
                try {
                    val bytes = data { long(row.rowId); digest(row.digest()) }
                    try { records.append(SelfOwnedNativeHistoryDataPartition.OBSERVATION, key, 0, 0, bytes) }
                    finally { bytes.fill(0) }
                } finally { key.fill(0) }
                census[row.table.ordinal] = increment(census[row.table.ordinal])
            }
            NativeHistoryReadPassV2.EVENTS -> {
                historyShapeV2(censusEnded.all { it } && !eventsEnded && event == null)
                matchCensus(row)
                val source = row.event()
                try {
                    historyShapeV2(source.sequence == increment(eventCount))
                    event = Event(source.sequence, position, source.changeCount); eventCount = source.sequence
                } finally { source.header.fill(0) }
            }
            NativeHistoryReadPassV2.EVENT_MUTATIONS -> if (row.table == RuntimeHistoryTableV2.ROW_VERSION) {
                matchCensus(row)
                val current = checkNotNull(event)
                val version = row.version()
                try {
                    historyShapeV2(version.sequence == current.sequence && version.ordinal == current.mutations &&
                        current.mutations < current.changes)
                    val wire = RuntimeHistoryCodecV2.encodeMutation(version)
                    try { records.append(SelfOwnedNativeHistoryDataPartition.MUTATION, EVENT_MUTATIONS,
                        version.sequence, version.ordinal, wire) }
                    finally { wire.fill(0) }
                    current.mutations++; mutationCount = increment(mutationCount)
                } finally { version.key.fill(0) }
            }
            NativeHistoryReadPassV2.EVENT_CHUNKS -> matchCensus(row)
            NativeHistoryReadPassV2.LINEAGE -> startLineage(row, position)
            NativeHistoryReadPassV2.LINEAGE_EVENT -> {
                matchCensus(row)
                historyShapeV2(row.number("sequence") == checkNotNull(lineage).row.sequence)
            }
            NativeHistoryReadPassV2.LINEAGE_IMAGE -> {
                val current = checkNotNull(lineage)
                historyShapeV2(current.image == 0L && current.row.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
                matchAfter(current.row, row)
                if (item.metadata.kind == NativeHistoryRowKindV2.PHYSICAL) matchCensus(row)
                current.image = position
            }
            NativeHistoryReadPassV2.LINEAGE_TIP -> {
                matchCensus(row)
                val current = checkNotNull(lineage)
                val tip = row.tip()
                try {
                    val version = current.row
                    historyShapeV2(!current.tip && tip.tableTag == version.tableTag && tip.key.contentEquals(version.key) &&
                        tip.sequence == version.sequence && tip.ordinal == version.ordinal && tip.version == version.version &&
                        tip.targetRowId == version.afterRowId && tip.rowDigest == version.afterDigest &&
                        (tip.tombstone == 1) == (version.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag))
                    current.tip = true; tips = increment(tips)
                    val table = RuntimeHistoryTableV2.fromTag(version.tableTag)
                    if (table != RuntimeHistoryTableV2.BRANCH && table.protectedTable == null && version.sequence > 1)
                        businessTips = increment(businessTips)
                } finally { tip.key.fill(0) }
            }
            NativeHistoryReadPassV2.LINEAGE_CURRENT -> {
                val current = checkNotNull(lineage)
                historyShapeV2(current.tip && !current.current)
                matchAfter(current.row, row); matchCensus(row)
                current.current = true; live[row.table.ordinal] = increment(live[row.table.ordinal])
            }
            NativeHistoryReadPassV2.COMMANDS -> {
                historyShapeV2(lineagesEnded && !commandsEnded && command == null)
                matchCensus(row)
                val key = row.key()
                val sequence = try {
                    withLastVersion(key, Long.MAX_VALUE) { version ->
                        val actual = checkNotNull(version)
                        historyShapeV2(actual.table == RuntimeHistoryTableV2.JOURNAL && actual.descriptor.version == 1L)
                        actual.descriptor.sequence
                    }
                } finally { key.fill(0) }
                command = Command(sequence, OperationId(row.text("operationId")), position)
            }
            NativeHistoryReadPassV2.COMMAND_OPERATION -> {
                val current = checkNotNull(command)
                historyShapeV2(current.operation == 0L && row.text("operationId") == current.id.value)
                matchCensus(row)
                val key = row.key()
                try { withLastVersion(key, Long.MAX_VALUE) { version ->
                    val actual = checkNotNull(version)
                    historyShapeV2(actual.table == RuntimeHistoryTableV2.OPERATION && actual.descriptor.version == 1L &&
                        actual.descriptor.sequence == current.sequence)
                    matchAfter(actual.descriptor, row)
                } } finally { key.fill(0) }
                current.operation = position
            }
            NativeHistoryReadPassV2.COMMAND_AUDIT, NativeHistoryReadPassV2.COMMAND_OUTBOX,
            NativeHistoryReadPassV2.COMMAND_FRAGMENT -> matchCensus(row)
            else -> Unit // The unmodified full scanner remains responsible for the other native passes.
        }
    }

    private suspend fun startLineage(row: RuntimeHistoryRowViewV2, position: Long) {
        historyShapeV2(eventsEnded && !lineagesEnded)
        matchCensus(row)
        val value = row.version()
        var retained = false
        try {
            val old = lineage
            if (old == null) historyShapeV2(value.version == 1L)
            else {
                val same = old.row.tableTag == value.tableTag && old.row.key.contentEquals(value.key)
                if (same) {
                    historyShapeV2(!old.keyClosed && value.version == old.row.version + 1L &&
                        value.previousVersion == old.row.version && value.previousSequence == old.row.sequence &&
                        value.previousOrdinal == old.row.ordinal)
                    if (old.row.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
                        historyShapeV2(value.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag)
                    else historyShapeV2(value.mutationKind != RuntimeHistoryMutationKindV2.INSERT.tag &&
                        value.beforeRowId == old.row.afterRowId && value.beforeDigest == old.row.afterDigest)
                    if (value.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag)
                        historyShapeV2(value.beforeRowId == value.afterRowId)
                } else historyShapeV2(old.keyClosed && value.version == 1L &&
                    (value.tableTag > old.row.tableTag || value.tableTag == old.row.tableTag && compareBytes(old.row.key, value.key) < 0))
                storeLineage(old); old.clear(); lineage = null
            }
            val observed = checkNotNull(records.exact(SelfOwnedNativeHistoryDataPartition.MUTATION,
                EVENT_MUTATIONS, value.sequence, value.ordinal))
            try {
                historyCapacityV2(observed.payloadBytes <= 2048)
                records.withPayload(observed) { expected ->
                val wire = RuntimeHistoryCodecV2.encodeMutation(value)
                try { historyShapeV2(wire.contentEquals(expected)) } finally { wire.fill(0) }
            } } finally { observed.clear() }
            lineage = Lineage(value, position); retained = true
            lineageCount = increment(lineageCount)
        } finally { if (!retained) value.key.fill(0) }
    }

    private suspend fun storeLineage(value: Lineage) {
        val row = value.row
        historyShapeV2((value.image == 0L) == (row.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) &&
            (!value.keyClosed || value.tip && value.current == (value.image != 0L)))
        val bytes = data { long(value.position); long(value.image) }
        try {
            records.append(SelfOwnedNativeHistoryDataPartition.VERSION, row.key, row.sequence, row.ordinal, bytes)
            records.append(SelfOwnedNativeHistoryDataPartition.MUTATION, MATCHED_VERSIONS, row.sequence, row.ordinal, bytes)
            val table = RuntimeHistoryTableV2.fromTag(row.tableTag)
            withTableKey(table, "versions") { records.append(SelfOwnedNativeHistoryDataPartition.MUTATION, it,
                row.sequence, row.ordinal, bytes) }
            if (row.version == 1L) {
                val next = increment(keyCounts[table.ordinal])
                withTableKey(table, "keys") { records.append(SelfOwnedNativeHistoryDataPartition.MUTATION, it, next, 0, bytes) }
                keyCounts[table.ordinal] = next
            }
            if (row.sequence == 1L && table == RuntimeHistoryTableV2.BRANCH)
                records.append(SelfOwnedNativeHistoryDataPartition.SEED, BRANCH_SEED, 1, 0, bytes)
        } finally { bytes.fill(0) }
    }

    private suspend fun boundary(metadata: RuntimeHistoryReadMetadata, position: Long) {
        if (metadata.kind == NativeHistoryRowKindV2.PASS_END) {
            when (metadata.pass) {
                NativeHistoryReadPassV2.CENSUS -> {
                    val table = checkNotNull(metadata.table)
                    historyShapeV2(!censusEnded[table.ordinal]); censusEnded[table.ordinal] = true
                }
                NativeHistoryReadPassV2.EVENTS -> { historyShapeV2(!eventsEnded && event == null); eventsEnded = true }
                NativeHistoryReadPassV2.LINEAGE -> {
                    historyShapeV2(!lineagesEnded && eventsEnded)
                    lineage?.let { historyShapeV2(it.keyClosed); storeLineage(it); it.clear() }; lineage = null
                    lineagesEnded = true
                }
                NativeHistoryReadPassV2.COMMANDS -> { historyShapeV2(!commandsEnded && command == null); commandsEnded = true }
                else -> Unit
            }
            return
        }
        when (metadata.pass) {
            NativeHistoryReadPassV2.EVENT_CHUNKS -> {
                val current = checkNotNull(event)
                historyShapeV2(current.changes == current.mutations)
                val bytes = data { long(current.start); long(position + 1) }
                try { records.append(SelfOwnedNativeHistoryDataPartition.EVENT, EVENTS, current.sequence, 0, bytes) }
                finally { bytes.fill(0) }
                event = null
            }
            NativeHistoryReadPassV2.LINEAGE -> {
                val current = checkNotNull(lineage)
                historyShapeV2(!current.keyClosed && current.tip && current.current == (current.image != 0L))
                current.keyClosed = true
            }
            NativeHistoryReadPassV2.COMMAND_FRAGMENT -> {
                val current = checkNotNull(command)
                historyShapeV2(current.operation > 0L)
                val bytes = data { long(current.sequence); long(current.journal); long(current.operation) }
                val key = current.id.value.encodeToByteArray()
                try {
                    // Exact operation identity is unique irrespective of event sequence.
                    records.append(SelfOwnedNativeHistoryDataPartition.COMMAND, key, 0, 0, bytes)
                    records.append(SelfOwnedNativeHistoryDataPartition.COMMAND, COMMANDS, current.sequence, 0, bytes)
                } finally { key.fill(0); bytes.fill(0) }
                commands = increment(commands); command = null
            }
            else -> Unit
        }
    }

    private suspend fun matchCensus(row: RuntimeHistoryRowViewV2) {
        val key = row.key()
        try {
            val record = checkNotNull(records.exact(SelfOwnedNativeHistoryDataPartition.OBSERVATION, key, 0, 0))
            try { readRecord(record, 40) {
                historyShapeV2(long() == row.rowId && digest() == row.digest())
            } } finally { record.clear() }
        } finally { key.fill(0) }
    }

    private fun matchAfter(version: RuntimeHistoryRowVersionRowV2, row: RuntimeHistoryRowViewV2) {
        historyShapeV2(row.table.tag == version.tableTag && row.rowId == version.afterRowId && row.digest().value == version.afterDigest)
        val key = row.key()
        try { historyShapeV2(key.contentEquals(version.key)) } finally { key.fill(0) }
    }

    override suspend fun <T> withSeedBranch(use: suspend (RuntimeHistoryRowViewV2) -> T): T {
        ready()
        val record = checkNotNull(records.exact(SelfOwnedNativeHistoryDataPartition.SEED, BRANCH_SEED, 1, 0))
        val location = try { location(record) } finally { record.clear() }
        return transcript.withItem(location.image) { item ->
            val row = (item as? RuntimeHistoryReadItem.Row)?.row ?: malformed()
            historyShapeV2(row.table == RuntimeHistoryTableV2.BRANCH); use(row)
        }
    }

    override suspend fun <T> withVersion(sequence: Long, ordinal: Int,
        use: suspend (RuntimeBusinessHistoryJoin.Mutation) -> T): T {
        ready()
        val record = checkNotNull(records.exact(SelfOwnedNativeHistoryDataPartition.MUTATION, MATCHED_VERSIONS, sequence, ordinal))
        val location = try { location(record) } finally { record.clear() }
        return withMutation(location, use)
    }

    override suspend fun <T> withBefore(table: RuntimeHistoryTableV2, canonicalKey: ByteArray,
        beforeSequenceExclusive: Long, use: suspend (RuntimeBusinessHistoryJoin.Mutation?) -> T): T {
        ready(); RuntimeHistoryRowCodecV2.requireKey(table, canonicalKey)
        return withLastVersion(canonicalKey, beforeSequenceExclusive) { value ->
            historyShapeV2(value == null || value.table == table); use(value)
        }
    }

    private suspend fun <T> withLastVersion(key: ByteArray, before: Long,
        use: suspend (RuntimeBusinessHistoryJoin.Mutation?) -> T): T {
        historyShapeV2(lineagesEnded && before > 0L)
        val record = records.floor(SelfOwnedNativeHistoryDataPartition.VERSION, key, before) ?: return use(null)
        val location = try { location(record) } finally { record.clear() }
        return withMutation(location, use)
    }

    private inner class MutationLoan(val value: RuntimeBusinessHistoryJoin.Mutation, val bytes: Long) {
        private var closed = false
        fun close() {
            if (closed) return
            closed = true
            try { value.clear() } finally { records.memory.release(bytes) }
        }
    }

    private suspend fun mutation(location: Location): MutationLoan {
        // Descriptor/key/wire and fixed object overhead are reserved before decoding.
        var reservation = 16_384L
        records.memory.reserve(reservation)
        var mutation: RuntimeBusinessHistoryJoin.Mutation? = null
        var transferred = false
        try {
            val value = transcript.withItem(location.version) { item ->
                val row = (item as? RuntimeHistoryReadItem.Row)?.row ?: malformed()
                historyShapeV2(item.metadata.pass == NativeHistoryReadPassV2.LINEAGE && row.table == RuntimeHistoryTableV2.ROW_VERSION)
                val descriptor = row.version()
                try { RuntimeBusinessHistoryJoin.Mutation(descriptor, RuntimeHistoryCodecV2.encodeMutation(descriptor)) }
                catch (failure: Throwable) { descriptor.key.fill(0); throw failure }
            }.also { mutation = it }
            historyShapeV2((location.image == 0L) == (value.descriptor.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag))
            if (location.image != 0L) transcript.withItem(location.image) { item ->
                val row = (item as? RuntimeHistoryReadItem.Row)?.row ?: malformed()
                historyShapeV2(item.metadata.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE)
                val bytes = item.encodedBytes * 2L
                records.memory.reserve(bytes); reservation += bytes
                matchAfter(value.descriptor, row)
                value.image = RuntimeHistoryRowCodecV2.encode(row.physical)
            }
            // This is the live result of the independent EVENTS/LINEAGE match performed
            // by this index builder, not a deserialized joined/authorization flag.
            value.seen = true
            return MutationLoan(value, reservation).also { transferred = true }
        } finally { if (!transferred) { mutation?.clear(); records.memory.release(reservation) } }
    }

    private suspend fun <T> withMutation(location: Location, use: suspend (RuntimeBusinessHistoryJoin.Mutation) -> T): T {
        val loan = mutation(location)
        return try { use(loan.value) } finally { loan.close() }
    }

    override suspend fun openEarlierVersions(table: RuntimeHistoryTableV2, canonicalKey: ByteArray?,
        beforeSequenceExclusive: Long): RuntimeHistoryColdCursor<RuntimeBusinessHistoryJoin.Mutation> {
        ready(); historyShapeV2(beforeSequenceExclusive > 0L)
        val cursor = if (canonicalKey != null) {
            RuntimeHistoryRowCodecV2.requireKey(table, canonicalKey)
            records.openCursor(SelfOwnedNativeHistoryDataPartition.VERSION, canonicalKey)
        } else withTableKey(table, "versions") { records.openCursor(SelfOwnedNativeHistoryDataPartition.MUTATION, it) }
        return mutationCursor(object : RuntimeHistoryColdCursor<Location> {
            private var ended = false
            override suspend fun next(): Location? {
                if (ended) return null
                val row = cursor.next() ?: return null
                if (row.sequence >= beforeSequenceExclusive) { ended = true; return null }
                return location(row)
            }
            override suspend fun close() = cursor.close()
        })
    }

    override suspend fun openBeforeRows(table: RuntimeHistoryTableV2, beforeSequenceExclusive: Long,
        order: RuntimeHistoryColdRowOrder): RuntimeHistoryColdCursor<RuntimeBusinessHistoryJoin.Mutation> =
        sortedBefore(table, beforeSequenceExclusive) { value -> withImage(value) { row ->
            if (order == RuntimeHistoryColdRowOrder.AFTER_ROW_ID) listOf(StockSqlScalar.Number(row.rowId))
            else table.keyColumns.map { column ->
                when (historyColumnsV2(table).single { it.name == column }.type) {
                    RuntimeSqliteTypeV2.INTEGER -> StockSqlScalar.Number(row.number(column))
                    RuntimeSqliteTypeV2.TEXT -> StockSqlScalar.Text(row.text(column))
                    else -> malformed()
                }
            }
        } }

    private suspend fun sortedBefore(table: RuntimeHistoryTableV2, before: Long,
        order: suspend (RuntimeBusinessHistoryJoin.Mutation) -> List<StockSqlScalar>?):
        RuntimeHistoryColdCursor<RuntimeBusinessHistoryJoin.Mutation> {
        ready(); historyShapeV2(before > 0L)
        val keys = withTableKey(table, "keys") { records.openCursor(SelfOwnedNativeHistoryDataPartition.MUTATION, it) }
        val sorted = try {
            RuntimeHistoryColdOrder.open(records, increment(nextSort).also { nextSort = it }) {
                var entry: RuntimeHistoryColdOrder.Entry? = null
                while (entry == null) {
                    currentCoroutineContext().ensureActive()
                    val first = keys.next() ?: break
                    val firstLocation = location(first)
                    val latest = transcript.withItem(firstLocation.version) { item ->
                        val version = ((item as? RuntimeHistoryReadItem.Row)?.row ?: malformed()).version()
                        try {
                            val record = records.floor(SelfOwnedNativeHistoryDataPartition.VERSION, version.key, before)
                            if (record == null) null else try { location(record) } finally { record.clear() }
                        } finally { version.key.fill(0) }
                    } ?: continue
                    withMutation(latest) { value ->
                        if (value.descriptor.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag) {
                            val tuple = order(value)
                            if (tuple != null) entry = RuntimeHistoryColdOrder.Entry(tuple,
                                data { long(latest.version); long(latest.image) })
                        }
                    }
                }
                entry
            }
        } catch (failure: Throwable) { closeAfterFailure(failure) { keys.close() }; throw failure }
        try { keys.close() }
        catch (failure: Throwable) { closeAfterFailure(failure) { sorted.close() }; throw failure }
        val orderReservation = 64L * 1024
        try { records.memory.reserve(orderReservation) }
        catch (failure: Throwable) { closeAfterFailure(failure) { sorted.close() }; throw failure }
        return mutationCursor(object : RuntimeHistoryColdCursor<Location> {
            private var previous: List<StockSqlScalar>? = null
            private var closed = false
            override suspend fun next(): Location? {
                val entry = sorted.next() ?: return null
                previous?.let { historyShapeV2(RuntimeHistoryColdOrder.compare(it, entry.order) < 0) }
                previous = entry.order.toList()
                return readLocation(entry.payload)
            }
            override suspend fun close() {
                if (closed) return
                closed = true
                try { sorted.close() } finally { previous = null; records.memory.release(orderReservation) }
            }
        })
    }

    override suspend fun openStockRangeBefore(query: StockQueryV2, beforeSequenceExclusive: Long): RuntimeHistoryColdCursor<StockIndexRecordV2> {
        val table = when (query) {
            StockQueryV2.AllPositions, is StockQueryV2.SkuPositions, is StockQueryV2.Positions, is StockQueryV2.LotPositions -> RuntimeHistoryTableV2.STOCK_POSITION
            StockQueryV2.AllReservations, is StockQueryV2.OrderReservations, is StockQueryV2.CollectionReservations -> RuntimeHistoryTableV2.STOCK_RESERVATION
            is StockQueryV2.PositionMembers, is StockQueryV2.ReservationMembers -> RuntimeHistoryTableV2.STOCK_ALLOCATION
            is StockQueryV2.PoolValues, is StockQueryV2.SourceValues, is StockQueryV2.IssueValues, is StockQueryV2.LinkedValues -> RuntimeHistoryTableV2.STOCK_VALUE
        }
        val sorted = sortedBefore(table, beforeSequenceExclusive) { value -> withImage(value) { row ->
            val stock = row.runtimeStockIndex()
            if (runtimeStockMatches(query, stock)) runtimeStockOrder(query, stock) else null
        } }
        return object : RuntimeHistoryColdCursor<StockIndexRecordV2> {
            override suspend fun next(): StockIndexRecordV2? = try {
                sorted.next()?.let { withImage(it) { row -> row.runtimeStockIndex() } }
            } catch (failure: Throwable) { closeAfterFailure(failure) { sorted.close() }; throw failure }
            override suspend fun close() = sorted.close()
        }
    }

    private fun mutationCursor(source: RuntimeHistoryColdCursor<Location>): RuntimeHistoryColdCursor<RuntimeBusinessHistoryJoin.Mutation> =
        object : RuntimeHistoryColdCursor<RuntimeBusinessHistoryJoin.Mutation> {
            private var current: MutationLoan? = null
            private var closed = false
            override suspend fun next(): RuntimeBusinessHistoryJoin.Mutation? {
                historyShapeV2(!closed)
                try {
                    ready(); current?.close(); current = null
                    return source.next()?.let { mutation(it).also { loan -> current = loan }.value }
                } catch (failure: Throwable) { closeAfterFailure(failure) { close() }; throw failure }
            }
            override suspend fun close() {
                if (closed) return
                closed = true
                try { source.close() } finally { current?.close(); current = null }
            }
        }

    private suspend fun <T> withImage(value: RuntimeBusinessHistoryJoin.Mutation,
        use: suspend (RuntimeHistoryRowViewV2) -> T): T {
        val bytes = checkNotNull(value.image)
        val reservation = bytes.size * 4L
        records.memory.reserve(reservation)
        try {
            val row = RuntimeHistoryRowViewV2(RuntimeHistoryRowCodecV2.decode(bytes))
            return try { use(row) } finally { row.clear() }
        } finally { records.memory.release(reservation) }
    }

    internal suspend fun eventRange(sequence: Long): EventRange? {
        ready()
        val record = records.exact(SelfOwnedNativeHistoryDataPartition.EVENT, EVENTS, sequence, 0) ?: return null
        return try { readRecord(record, 16) { EventRange(long(), long()) } }
        finally { record.clear() }
    }

    internal suspend fun sequenceForOperation(operationId: OperationId): Long? {
        ready()
        val key = operationId.value.encodeToByteArray()
        try {
            val record = records.exact(SelfOwnedNativeHistoryDataPartition.COMMAND, key, 0, 0) ?: return null
            return try { readRecord(record, 24) { long().also { long(); long() } } }
            finally { record.clear() }
        } finally { key.fill(0) }
    }

    internal suspend fun <T> withIndependentCommand(sequence: Long, use: suspend (JournalRow, OperationRow) -> T): T {
        ready()
        val record = checkNotNull(records.exact(SelfOwnedNativeHistoryDataPartition.COMMAND, COMMANDS, sequence, 0))
        val positions = try { readRecord(record, 24) {
            historyShapeV2(long() == sequence); long() to long()
        } } finally { record.clear() }
        return transcript.withItem(positions.first) { journalItem ->
            val journal = ((journalItem as? RuntimeHistoryReadItem.Row)?.row ?: malformed()).journal()
            try { transcript.withItem(positions.second) { operationItem ->
                val operation = ((operationItem as? RuntimeHistoryReadItem.Row)?.row ?: malformed()).operation()
                try { use(journal, operation) } finally { operation.result.fill(0) }
            } } finally { journal.envelope.fill(0) }
        }
    }

    override suspend fun markBusinessJoined(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        producer: StreamedProducerIdentityV2) {
        ready(); historyShapeV2(header.sequence > lastJoinedSequence)
        val range = checkNotNull(eventRange(header.sequence))
        transcript.withItem(range.first) { item ->
            val row = ((item as? RuntimeHistoryReadItem.Row)?.row ?: malformed()).event()
            try { historyShapeV2(row.eventDigest == eventDigest.value && RuntimeHistoryCodecV2.decodeHeader(row.header) == header) }
            finally { row.header.fill(0) }
        }
        withIndependentCommand(header.sequence) { journal, operation ->
            historyShapeV2(operation.operationId == producer.operationId.value && operation.actorId == producer.originalActorId.value &&
                operation.command == producer.command.name && operation.commandVersion == producer.command.version &&
                operation.digest == producer.semanticDigest.value && journal.hash == header.commandDigest.value)
        }
        val bytes = producer.operationId.value.encodeToByteArray()
        try { records.append(SelfOwnedNativeHistoryDataPartition.COMMAND, JOINED, header.sequence, 0, bytes) }
        finally { bytes.fill(0) }
        lastJoinedSequence = header.sequence; joined = increment(joined)
        joinedMutations = historyAddV2(joinedMutations, header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }

    override suspend fun openEarlierOperations(beforeSequenceExclusive: Long): RuntimeHistoryColdCursor<OperationId> {
        ready(); historyShapeV2(beforeSequenceExclusive > 0L)
        val source = records.openCursor(SelfOwnedNativeHistoryDataPartition.COMMAND, JOINED)
        val sorted = try { RuntimeHistoryColdOrder.open(records, increment(nextSort).also { nextSort = it }) {
            val row = source.next()
            if (row == null || row.sequence >= beforeSequenceExclusive) null
            else {
                historyShapeV2(row.payloadBytes == 36)
                records.withPayload(row) { bytes ->
                    val id = OperationId(historyUtf8V2(bytes))
                    RuntimeHistoryColdOrder.Entry(listOf(StockSqlScalar.Text(id.value)), data { long(row.sequence) })
                }
            }
        } } catch (failure: Throwable) { closeAfterFailure(failure) { source.close() }; throw failure }
        try { source.close() }
        catch (failure: Throwable) { closeAfterFailure(failure) { sorted.close() }; throw failure }
        return object : RuntimeHistoryColdCursor<OperationId> {
            override suspend fun next(): OperationId? {
                try {
                    val value = sorted.next() ?: return null
                    return OperationId((value.order.single() as StockSqlScalar.Text).value)
                } catch (failure: Throwable) { closeAfterFailure(failure) { sorted.close() }; throw failure }
            }
            override suspend fun close() = sorted.close()
        }
    }

    override suspend fun requireBusinessClosure(expectedCommands: Long, expectedMutations: Long) {
        ready()
        historyShapeV2(joined == expectedCommands && joined == commands && joinedMutations == expectedMutations)
    }

    private suspend fun location(record: SelfOwnedNativeHistoryDataRecord): Location {
        historyShapeV2(record.payloadBytes == 16)
        return records.withPayload(record, ::readLocation)
    }
    private suspend fun <T> readRecord(record: SelfOwnedNativeHistoryDataRecord, expectedBytes: Int,
        read: HistoryBinaryReaderV2.() -> T): T {
        historyShapeV2(record.payloadBytes == expectedBytes)
        return records.withPayload(record) { readData(it, read) }
    }
    private fun readLocation(bytes: ByteArray): Location = readData(bytes) { Location(long(), long()) }.also {
        historyShapeV2(it.version in 1..transcript.itemCount && it.image in 0..transcript.itemCount)
    }
    private suspend fun <T> withTableKey(table: RuntimeHistoryTableV2, purpose: String, use: suspend (ByteArray) -> T): T {
        val key = "$purpose/${table.tag}/v1".encodeToByteArray()
        return try { use(key) } finally { key.fill(0) }
    }
    private fun ready() { historyShapeV2(built) }
    private fun increment(value: Long): Long = historyAddV2(value, 1, RuntimeHistoryProfileV2.MAX_DATASET_BYTES)

    companion object {
        private val EVENTS = "events/v1".encodeToByteArray()
        private val EVENT_MUTATIONS = "event-mutations/v1".encodeToByteArray()
        private val MATCHED_VERSIONS = "matched-versions/v1".encodeToByteArray()
        private val BRANCH_SEED = "branch/v1".encodeToByteArray()
        private val COMMANDS = "commands/v1".encodeToByteArray()
        private val JOINED = "joined/v1".encodeToByteArray()
        private inline fun data(write: HistoryBinaryWriterV2.() -> Unit): ByteArray {
            val writer = HistoryBinaryWriterV2(128)
            return try { writer.write(); writer.finish() } finally { writer.clear() }
        }
        private inline fun <T> readData(bytes: ByteArray, read: HistoryBinaryReaderV2.() -> T): T {
            val reader = HistoryBinaryReaderV2(bytes, 128)
            return try { reader.read().also { reader.end() } } finally { reader.clear() }
        }
        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (index in 0 until minOf(a.size, b.size)) {
                val difference = (a[index].toInt() and 255) - (b[index].toInt() and 255)
                if (difference != 0) return difference
            }
            return a.size.compareTo(b.size)
        }
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)

        private suspend fun closeAfterFailure(failure: Throwable, close: suspend () -> Unit) = withContext(NonCancellable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        }

        suspend fun build(transcript: RuntimeHistoryColdTranscript): RuntimeHistoryColdIndex =
            RuntimeHistoryColdIndex(transcript).also { it.build() }
    }
}

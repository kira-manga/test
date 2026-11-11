package com.ukkera.pos.database

import androidx.sqlite.SQLiteStatement
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Literal direct-source identity, never proof of current authority or complete history. */
public data class StreamedProducerIdentityV2(
    public val operationId: OperationId,
    public val command: CommandKey,
    public val originalActorId: DomainId,
    public val semanticDigest: PayloadDigest,
)

public sealed interface StreamedSourceObservationV2 {
    public data class Aggregate(
        public val key: AggregateKey,
        public val generation: Revision,
        public val schema: String,
        public val payload: StoredBytes,
        public val payloadHash: PayloadDigest,
        public val producer: StreamedProducerIdentityV2,
    ) : StreamedSourceObservationV2 {
        init {
            requireProtocolName(schema)
            stockSqlRequireV2(generation.value >= 1 && sha256(payload.copyBytes()) == payloadHash)
        }
    }
    /** Literal current nonstock authority input. Its typed authority reader, not this DTO,
     * establishes catalog/policy publication; it does not fabricate a stock original producer. */
    public data class Current(
        public val key: AggregateKey,
        public val generation: Revision,
        public val schema: String,
        public val payload: StoredBytes,
        public val payloadHash: PayloadDigest,
    ) : StreamedSourceObservationV2 {
        init { requireProtocolName(schema); stockSqlRequireV2(generation.value >= 1 && sha256(payload.copyBytes()) == payloadHash) }
    }
    /** Literal nonstock aggregate absence rechecked on the actual loan before any writes.
     * A typed consumer must account for the absent key and its authority/fallback rule. This
     * does not stand in for the owner's complete physical census, nor mean missing stock is empty. */
    public data class CurrentAbsent(public val key: AggregateKey) : StreamedSourceObservationV2 {
        init { stockSqlRequireV2(key.kind !in StockRelationalKindsV2.all) }
    }
    /** Only a never-stocked item coordinate, not empty stock or absent physical custody.
     * The typed consumer requires the genuine activation and null permanent item claim;
     * owner verification separately rechecks the actual complete native item absence. */
    public data class StockItemAbsent(public val key: AggregateKey) : StreamedSourceObservationV2 {
        init { stockSqlRequireV2(key.kind == StockRelationalKindsV2.ITEM) }
    }
    /** Owner-observed complete fixed SQL range. Every member is literal below; a hash alone is NOT coverage. */
    public data class StockRange(public val query: StockQueryV2, public val count: Long,
        public val commitment: PayloadDigest) : StreamedSourceObservationV2 {
        init { stockSqlRequireV2(count >= 0) }
    }
    public data class StockMember(public val query: StockQueryV2, public val ordinal: Long,
        public val record: StockIndexRecordV2) : StreamedSourceObservationV2 {
        init { stockSqlRequireV2(ordinal >= 0) }
    }
    /** Whole branch estate, originated only by the actual owner's unfiltered typed census.
     * This is literal negative membership, not a caller-owned migration/absence certificate. */
    public data class EstateMember(public val ordinal: Long, public val record: StockEstateRecordV2) : StreamedSourceObservationV2 {
        init { stockSqlRequireV2(ordinal >= 0) }
    }
    public data class EstateEnd(public val count: Long, public val commitment: PayloadDigest) : StreamedSourceObservationV2 {
        init { stockSqlRequireV2(count >= 0) }
    }
    /** Null is the observed actual claim absence in this loan, not a reusable absence proof. */
    public data class Claim(public val key: ClaimKey, public val ownerId: DomainId?) : StreamedSourceObservationV2
}

public data class StreamedEffectV2(public val kind: String, public val id: DomainId, public val payload: StoredBytes) {
    init { requireProtocolName(kind) }
}

/** Explicit ordered tags; increasing global counts are Longs, not an arbitrary sale-size cap. */
public enum class StreamedSectionV2(public val tag: Int) {
    NORMALIZED(1), RESULT(2), WRITES(3), CLAIMS(4), JOBS(5), SOURCES(6), EFFECTS(7), RESOURCES(8);
    internal companion object {
        fun fromTag(tag: Int): StreamedSectionV2 = entries.singleOrNull { it.tag == tag } ?: stockSqlUnavailableV2()
    }
}

public sealed interface RetainedCommandFrameV2 {
    public val section: StreamedSectionV2
    public data class Normalized(public val body: StoredBytes) : RetainedCommandFrameV2 { override val section: StreamedSectionV2 = StreamedSectionV2.NORMALIZED }
    public data class Result(public val body: StoredBytes) : RetainedCommandFrameV2 { override val section: StreamedSectionV2 = StreamedSectionV2.RESULT }
    public data class Write(public val value: RetainedAggregateWrite) : RetainedCommandFrameV2 { override val section: StreamedSectionV2 = StreamedSectionV2.WRITES }
    public data class Claim(public val value: RetainedClaimChange) : RetainedCommandFrameV2 { override val section: StreamedSectionV2 = StreamedSectionV2.CLAIMS }
    public data class Job(public val ordinal: Long, public val value: RetainedJobIntent) : RetainedCommandFrameV2 {
        override val section: StreamedSectionV2 = StreamedSectionV2.JOBS
        init { stockSqlRequireV2(ordinal >= 1) }
    }
    public data class Source(public val value: StreamedSourceObservationV2) : RetainedCommandFrameV2 { override val section: StreamedSectionV2 = StreamedSectionV2.SOURCES }
    public data class Effect(public val value: StreamedEffectV2) : RetainedCommandFrameV2 { override val section: StreamedSectionV2 = StreamedSectionV2.EFFECTS }
    public data class Resource(public val value: RecoveryResourceKey) : RetainedCommandFrameV2 { override val section: StreamedSectionV2 = StreamedSectionV2.RESOURCES }
}

public data class StreamedSectionSummaryV2(public val count: Long, public val bytes: Long, public val commitment: PayloadDigest) {
    init { stockSqlRequireV2(count >= 0 && bytes >= 0) }
}

public data class RetainedCommandIdentityV2(
    public val scope: BranchScope,
    public val producer: StreamedProducerIdentityV2,
    public val assignmentId: DomainId,
    public val authorityEpoch: Revision,
    public val sequence: JournalSequence,
    public val recordedAt: UtcInstant,
    public val authenticationId: DomainId,
    public val policyRevision: Revision,
    public val resultHash: PayloadDigest,
    public val previousJournalHash: PayloadDigest,
) {
    init { stockSqlRequireV2(sequence.value >= 1); requireEvidenceV1Time(recordedAt) }
}

public sealed interface RetainedOriginalV2 {
    public class Legacy internal constructor(public val evidence: RetainedCommandEvidence) : RetainedOriginalV2
    public class Streamed internal constructor(public val evidence: RetainedStreamedCommand) : RetainedOriginalV2
}

/** Core-owned original readers only. The ordinary reader retains its actual StockSqlLoan;
 * startup retains complete native-selected immutable frames and its active historical cut.
 * Neither route accepts a supplied current CommandReadView or a caller-selected SQL callback. */
internal interface RetainedStreamedCommandReader {
    suspend fun requireCurrent(evidence: RetainedStreamedCommand)
    suspend fun frames(evidence: RetainedStreamedCommand, consume: suspend (RetainedCommandFrameV2) -> Unit)
    suspend fun read(owner: RetainedStreamedCommand, operationId: OperationId): RetainedOriginalV2?
    suspend fun completedNativeBusinessPublicationsBefore(evidence: RetainedStreamedCommand): List<CompanyBusinessPublicationOutput>
}

/**
 * Owner-bound, already structurally read to EOF. It is still NOT a typed business-producer pass.
 * Each revisit traverses every frame again to EOF; a favorable early matching write cannot
 * bypass later claims, originals, malformed sections, extra fragments, or failed cancellation.
 */
public class RetainedStreamedCommand internal constructor(
    private val reader: RetainedStreamedCommandReader,
    internal val root: JournalRootV2,
    public val journalHash: PayloadDigest,
    public val normalized: StoredBytes,
    public val result: StoredBytes,
) {
    public val identity: RetainedCommandIdentityV2 get() = root.identity
    public suspend fun frames(consume: suspend (RetainedCommandFrameV2) -> Unit) {
        reader.requireCurrent(this)
        reader.frames(this, consume)
    }
    /** Same actual active loan, complete v1 pair or v2 stream. Structural facts, NOT a producer pass. */
    public suspend fun original(operationId: OperationId): RetainedOriginalV2? {
        reader.requireCurrent(this)
        return reader.read(this, operationId)
    }
    /** Original serial-qualification time only, never the current clock or recordedAt.
     * The retained v2 format carries no qualified serial-time observation. An active
     * reader cannot reconstruct it from audit time, an interval or a stored result. */
    public suspend fun qualifiedSerialRegistrationTime(): QualifiedSerialRegistrationTime? {
        reader.requireCurrent(this)
        return null
    }
    /** Exact native completed-output membership observed by THIS original command.
     * Only an active native-history reader can bind its actual original event/cut.
     * Ordinary retained SQL has no such source and throws unavailable; neither today's
     * native ledger nor absent literal frames supplies an empty historical membership. */
    public suspend fun completedNativeBusinessPublicationsBefore(): List<CompanyBusinessPublicationOutput> {
        reader.requireCurrent(this)
        val publications = reader.completedNativeBusinessPublicationsBefore(this)
        reader.requireCurrent(this)
        return publications
    }
    /** One ACTUAL retained observation, projected by core after complete loan-bound traversal.
     * A supplied DTO cannot mint StoredAggregate or impersonate a historical CommandReadView.
     * Null means no literal row observation here, not authoritative current stock absence. */
    public suspend fun sourceRow(key: AggregateKey): StoredAggregate? {
        var found: StoredAggregate? = null
        var absent = false
        frames { frame -> when (val value = (frame as? RetainedCommandFrameV2.Source)?.value) {
            is StreamedSourceObservationV2.Aggregate -> if (value.key == key) {
                stockSqlRequireV2(found == null && !absent)
                found = StoredAggregate(key, value.generation, value.schema, value.payload, value.payloadHash)
            }
            is StreamedSourceObservationV2.Current -> if (value.key == key) {
                stockSqlRequireV2(found == null && !absent)
                found = StoredAggregate(key, value.generation, value.schema, value.payload, value.payloadHash)
            }
            is StreamedSourceObservationV2.StockItemAbsent -> if (value.key == key) { stockSqlRequireV2(found == null && !absent); absent = true }
            is StreamedSourceObservationV2.CurrentAbsent -> if (value.key == key) { stockSqlRequireV2(found == null && !absent); absent = true }
            else -> Unit
        } }
        return found
    }

    /** Literal after-write row from THIS original, never the current mutable aggregate. */
    public suspend fun writeRow(key: AggregateKey): StoredAggregate? {
        var found: StoredAggregate? = null
        frames { frame ->
            val value = (frame as? RetainedCommandFrameV2.Write)?.value
            if (value?.key == key) {
                stockSqlRequireV2(found == null)
                found = StoredAggregate(key, value.storageGeneration, value.schema, value.payload, value.payloadDigest)
            }
        }
        return found
    }

    /** Literal pre-write estate captured by the owner. Every original reference is joined
     * to the ACTUAL full retained v1 pair or v2 stream again, never trusted from its hash alone. */
    public suspend fun stockEstate(consume: suspend (StockEstateRecordV2) -> Unit): StreamedSourceObservationV2.EstateEnd {
        val chain = StockEstateChainV2()
        var terminal: StreamedSourceObservationV2.EstateEnd? = null
        frames { frame -> when (val value = (frame as? RetainedCommandFrameV2.Source)?.value) {
            is StreamedSourceObservationV2.EstateMember -> {
                stockSqlRequireV2(value.ordinal == chain.count)
                val row = value.record
                if (row is StockEstateRecordV2.Original) {
                    val actual = original(row.operationId) ?: stockSqlUnavailableV2()
                    stockSqlRequireV2(stockEstateOriginalV2(actual) == row && row.operationId != identity.producer.operationId)
                }
                chain.append(row); consume(row)
            }
            is StreamedSourceObservationV2.EstateEnd -> { stockSqlRequireV2(terminal == null); terminal = value }
            else -> Unit
        } }
        val expected = terminal ?: stockSqlUnavailableV2()
        stockSqlRequireV2(chain.finish() == expected)
        return expected
    }

    /** Replays the COMPLETE literal owner-observed range, not a requested favorable row. */
    public suspend fun stockRange(query: StockQueryV2, consume: suspend (StockIndexRecordV2) -> Unit) {
        var terminal: StreamedSourceObservationV2.StockRange? = null
        val chain = StockRangeChainV2(query)
        frames { frame ->
            val value = (frame as? RetainedCommandFrameV2.Source)?.value
            when {
                value is StreamedSourceObservationV2.StockRange && value.query == query -> {
                    stockSqlRequireV2(terminal == null); terminal = value
                }
                value is StreamedSourceObservationV2.StockMember && value.query == query -> {
                    stockSqlRequireV2(value.ordinal == chain.count)
                    chain.append(value.record); consume(value.record)
                }
            }
        }
        stockSqlRequireV2(terminal != null && chain.finish() == terminal)
    }
    public suspend fun sectionSummary(section: StreamedSectionV2): StreamedSectionSummaryV2 {
        val commitment = StockSectionChainV2(section)
        frames { if (it.section == section) commitment.append(RetainedCommandFramesV2.encode(it)) }
        return commitment.finish()
    }
}

/** Same owned connection, metadata before BOTH root/result BLOBs and before EACH fragment BLOB. */
public class RetainedOriginalReader internal constructor(private val loan: StockSqlLoan) {
    private val commandReader = object : RetainedStreamedCommandReader {
        override suspend fun requireCurrent(evidence: RetainedStreamedCommand) = this@RetainedOriginalReader.requireCurrent(evidence)
        override suspend fun frames(evidence: RetainedStreamedCommand, consume: suspend (RetainedCommandFrameV2) -> Unit) =
            this@RetainedOriginalReader.scan(evidence.root, consume)
        override suspend fun read(owner: RetainedStreamedCommand, operationId: OperationId): RetainedOriginalV2? =
            this@RetainedOriginalReader.read(operationId)
        override suspend fun completedNativeBusinessPublicationsBefore(evidence: RetainedStreamedCommand): List<CompanyBusinessPublicationOutput> {
            this@RetainedOriginalReader.requireCurrent(evidence)
            // This loan has retained SQL only, not the original native selected observation.
            // Keep the failure sticky if a producer catches it and attempts another write.
            loan.poison()
            stockSqlUnavailableV2()
        }
    }
    /** Complete key union from BOTH retained tables plus fragments, after an unfiltered typed
     * metadata pass over ALL three. Journal-only/result-only/fragment-only rows cannot disappear. */
    internal suspend fun estate(consume: suspend (StockEstateRecordV2) -> Unit) {
        loan.requireBody()
        for (table in listOf("operation_result", "journal_envelope", "journal_fragment_v2")) {
            var after: Long? = null
            while (true) {
                val shape = when (table) {
                    "operation_result" -> operationStorage() + " AND length(result)<=$MAX_STORED_BYTES"
                    "journal_envelope" -> journalStorage() + " AND length(envelope)<=$MAX_RETAINED_ENVELOPE_BYTES"
                    else -> fragmentStorage()
                }
                val columns = when (table) {
                    "operation_result" -> "actorId,command,commandVersion,digest,outcome,resultHash,sequence,epoch,committedAt,authenticationId,policyRevision"
                    "journal_envelope" -> "assignmentId,sequence,epoch,previousHash,hash"
                    else -> "ordinal,byteCount,payloadHash"
                }
                val ids = loan.sql("SELECT rowid,CASE WHEN $shape THEN 1 ELSE 0 END,scope,operationId,$columns FROM main.$table" +
                    (if (after == null) "" else " WHERE rowid>?") + " ORDER BY rowid LIMIT $STOCK_SQL_PAGE_V2") { s ->
                    after?.let { s.bindLong(1, it) }
                    val page = mutableListOf<Long>()
                    while (s.step()) {
                        stockSqlRequireV2(page.size < STOCK_SQL_PAGE_V2 && s.getLong(1) == 1L)
                        stockScopeStorageKeyV2(s.getText(2)); OperationId(s.getText(3))
                        when (table) {
                            "operation_result" -> {
                                DomainId(s.getText(4)); CommandKey(s.getText(5), s.getLong(6).toInt()); PayloadDigest(s.getText(7))
                                requireProtocolName(s.getText(8)); PayloadDigest(s.getText(9)); JournalSequence(s.getLong(10)); Revision(s.getLong(11))
                                val instant = UtcInstant(kotlin.time.Instant.parse(s.getText(12)))
                                requireEvidenceV1Time(instant); stockSqlRequireV2(instant.toString() == s.getText(12))
                                DomainId(s.getText(13)); Revision(s.getLong(14))
                            }
                            "journal_envelope" -> {
                                DomainId(s.getText(4)); JournalSequence(s.getLong(5)); Revision(s.getLong(6))
                                PayloadDigest(s.getText(7)); PayloadDigest(s.getText(8))
                            }
                            else -> PayloadDigest(s.getText(6))
                        }
                        page += s.getLong(0)
                    }
                    page
                }
                for (id in ids) { stockSqlRequireV2(after == null || id > checkNotNull(after)); after = id }
                if (ids.size < STOCK_SQL_PAGE_V2) break
            }
        }
        var after: OperationId? = null
        while (true) {
            val ids = loan.sql("SELECT operationId FROM (SELECT operationId FROM main.operation_result WHERE scope=? UNION " +
                "SELECT operationId FROM main.journal_envelope WHERE scope=? UNION SELECT operationId FROM main.journal_fragment_v2 WHERE scope=?)" +
                (if (after == null) "" else " WHERE operationId>?") + " ORDER BY operationId LIMIT $STOCK_SQL_PAGE_V2") { s ->
                repeat(3) { s.bindText(it + 1, loan.scope.storageKey()) }; after?.let { s.bindText(4, it.value) }
                val page = mutableListOf<OperationId>()
                while (s.step()) { stockSqlRequireV2(page.size < STOCK_SQL_PAGE_V2); page += OperationId(s.getText(0)) }
                page
            }
            for (id in ids) {
                stockSqlRequireV2(after == null || checkNotNull(after).value < id.value); after = id
                consume(stockEstateOriginalV2(read(id) ?: stockSqlUnavailableV2()))
            }
            if (ids.size < STOCK_SQL_PAGE_V2) break
        }
        loan.requireBody()
    }

    private fun fragmentStorage(): String = listOf(stockTextStorageV2("scope", 110, 110), stockTextStorageV2("operationId", 36, 36),
        "typeof(ordinal)='integer' AND ordinal>=0", "typeof(byteCount)='integer' AND byteCount BETWEEN 1 AND $STOCK_JOURNAL_FRAGMENT_BYTES_V2",
        "typeof(payload)='blob' AND length(payload)=byteCount", stockTextStorageV2("payloadHash", 64, 64)).joinToString(" AND ") { "($it)" }

    public suspend fun read(operationId: OperationId): RetainedOriginalV2? = poisonOnFailure {
        readChecked(operationId)
    }

    private suspend fun readChecked(operationId: OperationId): RetainedOriginalV2? {
        loan.requireBody()
        loan.physicalTrace?.add(PhysicalReadDependency.Retained(operationId))
        val operationBytes = metadata("operation_result", "result", operationId, operationStorage(), MAX_STORED_BYTES)
        val journalBytes = metadata("journal_envelope", "envelope", operationId, journalStorage(), MAX_RETAINED_ENVELOPE_BYTES)
        val hasFragments = loan.sql("SELECT 1 FROM main.journal_fragment_v2 WHERE scope=? AND operationId=? LIMIT 1") { statement ->
            bindOperation(statement, operationId); statement.step()
        }
        if (operationBytes == null && journalBytes == null && !hasFragments) return null
        stockSqlRequireV2(operationBytes != null && journalBytes != null)
        // Distinguish a v2 root with a fixed bounded BLOB prefix BEFORE hydrating the full
        // envelope. The historical 4MiB v1 allowance must not admit a malformed oversized root.
        val rootPrefix = loan.sql("SELECT substr(envelope,1,32) FROM main.journal_envelope WHERE scope=? AND operationId=?") { statement ->
            bindOperation(statement, operationId); stockSqlRequireV2(statement.step())
            val prefix = statement.getBlob(0)
            stockSqlRequireV2(prefix.size <= 32 && !statement.step()); prefix
        }
        val isRoot = RetainedCommandFramesV2.isRoot(rootPrefix)
        rootPrefix.fill(0)
        stockSqlRequireV2(!isRoot || checkNotNull(journalBytes) <= STOCK_JOURNAL_ROOT_BYTES_V2.toLong())
        val operation = operation(operationId, checkNotNull(operationBytes))
        val journal = journal(operationId, checkNotNull(journalBytes))
        if (!isRoot) {
            stockSqlRequireV2(!hasFragments)
            return RetainedOriginalV2.Legacy(decodeRetainedCommand(loan.scope, operationId, operation, journal) ?: stockSqlUnavailableV2())
        }
        stockSqlRequireV2(journal.envelope.size <= STOCK_JOURNAL_ROOT_BYTES_V2 && hasFragments)
        val root = RetainedCommandFramesV2.decodeRoot(journal.envelope)
        requirePair(root, operation, journal)
        var normalized: StoredBytes? = null
        var result: StoredBytes? = null
        scan(root) { frame -> when (frame) {
            is RetainedCommandFrameV2.Normalized -> normalized = frame.body
            is RetainedCommandFrameV2.Result -> result = frame.body
            else -> Unit
        } }
        stockSqlRequireV2(checkNotNull(result).copyBytes().contentEquals(operation.result) &&
            semanticDigest(loan.scope, root.identity.producer.originalActorId, root.identity.producer.command,
                checkNotNull(normalized)) == root.identity.producer.semanticDigest)
        return RetainedOriginalV2.Streamed(RetainedStreamedCommand(commandReader, root, PayloadDigest(journal.hash), checkNotNull(normalized), checkNotNull(result)))
    }

    internal suspend fun requireCurrent(evidence: RetainedStreamedCommand): Unit = poisonOnFailure {
        loan.requireBody()
        stockSqlRequireV2(evidence.identity.scope == loan.scope)
        val id = evidence.identity.producer.operationId
        val size = metadata("journal_envelope", "envelope", id, journalStorage(), STOCK_JOURNAL_ROOT_BYTES_V2) ?: stockSqlUnavailableV2()
        val row = journal(id, size)
        stockSqlRequireV2(PayloadDigest(row.hash) == evidence.journalHash &&
            row.envelope.contentEquals(RetainedCommandFramesV2.encodeRoot(evidence.root)))
    }

    /** A caller catching malformed history must not continue a different write on this loan.
     * This covers decoding/cross-row checks after SQL, not just statement/fragment failures. */
    private suspend fun <T> poisonOnFailure(block: suspend () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        loan.poison()
        throw failure
    }

    internal suspend fun scan(root: JournalRootV2, consume: suspend (RetainedCommandFrameV2) -> Unit) {
        loan.requireBody()
        val chain = StockFragmentChainV2()
        val order = StockFrameOrderV2()
        val parser = StockFrameStreamDecoderV2 { bytes ->
            val frame = RetainedCommandFramesV2.decode(bytes)
            stockSqlRequireV2(RetainedCommandFramesV2.encode(frame).contentEquals(bytes))
            order.append(frame, bytes.size)
            if (frame is RetainedCommandFrameV2.Result) stockSqlRequireV2(sha256(frame.body.copyBytes()) == root.identity.resultHash)
            consume(frame)
        }
        try {
        var expected = 0L
        var after: Long? = null
        var previousBytes: Long? = null
        while (true) {
            val page = fragmentMetadata(root.identity.producer.operationId, after)
            for (metadata in page) {
                stockSqlRequireV2(metadata.ordinal == expected && expected < root.fragmentCount &&
                    (previousBytes == null || previousBytes == STOCK_JOURNAL_FRAGMENT_BYTES_V2.toLong()))
                val payload = fragment(root.identity.producer.operationId, metadata)
                try {
                    chain.append(expected, payload)
                    parser.append(payload)
                } finally { payload.fill(0) }
                expected = stockCheckedAddV2(expected, 1)
                after = metadata.ordinal; previousBytes = metadata.bytes
            }
            if (page.size < STOCK_SQL_PAGE_V2) break
        }
        parser.finish()
        order.finish(root.counts, root.sectionBytes)
        val actual = chain.finish()
        stockSqlRequireV2(expected == root.fragmentCount && actual.count == root.fragmentCount && actual.bytes == root.streamBytes && actual.commitment == root.commitment)
        loan.requireBody()
        } catch (failure: Throwable) {
            loan.poison()
            throw failure
        } finally { parser.clear() }
    }

    private data class FragmentMetadata(val ordinal: Long, val bytes: Long)
    private suspend fun fragmentMetadata(operationId: OperationId, after: Long?): List<FragmentMetadata> =
        loan.sql("SELECT ordinal,byteCount,CASE WHEN ${stockTextStorageV2("scope", 110, 110)} AND ${stockTextStorageV2("operationId", 36, 36)} " +
            "AND typeof(ordinal)='integer' AND ordinal>=0 AND typeof(byteCount)='integer' AND byteCount BETWEEN 1 AND $STOCK_JOURNAL_FRAGMENT_BYTES_V2 " +
            "AND typeof(payload)='blob' AND length(payload)=byteCount AND ${stockTextStorageV2("payloadHash", 64, 64)} THEN 1 ELSE 0 END " +
            "FROM main.journal_fragment_v2 WHERE scope=? AND operationId=?" + (if (after != null) " AND ordinal>?" else "") +
            " ORDER BY ordinal LIMIT $STOCK_SQL_PAGE_V2") { statement ->
            bindOperation(statement, operationId); after?.let { statement.bindLong(3, it) }
            val rows = mutableListOf<FragmentMetadata>()
            while (statement.step()) {
                stockSqlRequireV2(rows.size < STOCK_SQL_PAGE_V2 && statement.getLong(2) == 1L)
                rows += FragmentMetadata(statement.getLong(0), statement.getLong(1))
            }
            rows
        }

    private suspend fun fragment(operationId: OperationId, metadata: FragmentMetadata): ByteArray =
        loan.sql("SELECT payload,payloadHash FROM main.journal_fragment_v2 WHERE scope=? AND operationId=? AND ordinal=?") { statement ->
            bindOperation(statement, operationId); statement.bindLong(3, metadata.ordinal)
            stockSqlRequireV2(statement.step())
            val bytes = statement.getBlob(0)
            stockSqlRequireV2(bytes.size.toLong() == metadata.bytes && sha256(bytes) == PayloadDigest(statement.getText(1)) && !statement.step())
            bytes
        }

    private suspend fun metadata(table: String, blob: String, operationId: OperationId, shape: String, maximum: Int): Long? =
        loan.sql("SELECT CASE WHEN $shape THEN 1 ELSE 0 END,COALESCE(length($blob),-1) FROM main.$table WHERE scope=? AND operationId=?") { statement ->
            bindOperation(statement, operationId)
            if (!statement.step()) null else {
                stockSqlRequireV2(statement.getLong(0) == 1L && statement.getLong(1) in 0L..maximum.toLong())
                val count = statement.getLong(1); stockSqlRequireV2(!statement.step()); count
            }
        }

    private suspend fun operation(id: OperationId, bytes: Long): OperationRow =
        loan.sql("SELECT scope,operationId,actorId,command,commandVersion,digest,outcome,result,resultHash,sequence,epoch,committedAt,authenticationId,policyRevision " +
            "FROM main.operation_result WHERE scope=? AND operationId=?") { s ->
            bindOperation(s, id); stockSqlRequireV2(s.step())
            val row = OperationRow(s.getText(0), s.getText(1), s.getText(2), s.getText(3), s.getLong(4).toInt(), s.getText(5),
                s.getText(6), s.getBlob(7), s.getText(8), s.getLong(9), s.getLong(10), s.getText(11), s.getText(12), s.getLong(13))
            stockSqlRequireV2(row.result.size.toLong() == bytes && !s.step()); row
        }

    private suspend fun journal(id: OperationId, bytes: Long): JournalRow =
        loan.sql("SELECT scope,sequence,operationId,assignmentId,epoch,previousHash,hash,envelope FROM main.journal_envelope WHERE scope=? AND operationId=?") { s ->
            bindOperation(s, id); stockSqlRequireV2(s.step())
            val row = JournalRow(s.getText(0), s.getLong(1), s.getText(2), s.getText(3), s.getLong(4), s.getText(5), s.getText(6), s.getBlob(7))
            stockSqlRequireV2(row.envelope.size.toLong() == bytes && !s.step()); row
        }

    private fun requirePair(root: JournalRootV2, operation: OperationRow, journal: JournalRow) {
        val i = root.identity; val p = i.producer
        stockSqlRequireV2(i.scope == loan.scope && operation.scope == loan.scope.storageKey() && journal.scope == operation.scope &&
            operation.operationId == p.operationId.value && journal.operationId == operation.operationId && operation.actorId == p.originalActorId.value &&
            operation.command == p.command.name && operation.commandVersion == p.command.version && operation.digest == p.semanticDigest.value &&
            operation.outcome == "committed" && operation.resultHash == i.resultHash.value && sha256(operation.result) == i.resultHash &&
            operation.sequence == i.sequence.value && journal.sequence == operation.sequence && operation.epoch == i.authorityEpoch.value && journal.epoch == operation.epoch &&
            operation.committedAt == i.recordedAt.toString() && operation.authenticationId == i.authenticationId.value && operation.policyRevision == i.policyRevision.value &&
            journal.assignmentId == i.assignmentId.value && journal.previousHash == i.previousJournalHash.value && sha256(journal.envelope).value == journal.hash)
    }

    private fun operationStorage(): String = listOf(stockTextStorageV2("scope", 110, 110), stockTextStorageV2("operationId", 36, 36),
        stockTextStorageV2("actorId", 36, 36), stockTextStorageV2("command", 1, 96), "typeof(commandVersion)='integer' AND commandVersion BETWEEN 1 AND 1000",
        stockTextStorageV2("digest", 64, 64), stockTextStorageV2("outcome", 1, 96), "typeof(result)='blob'", stockTextStorageV2("resultHash", 64, 64),
        "typeof(sequence)='integer' AND sequence>=1", "typeof(epoch)='integer' AND epoch>=0", stockTextStorageV2("committedAt", 20, 30),
        stockTextStorageV2("authenticationId", 36, 36), "typeof(policyRevision)='integer' AND policyRevision>=0").joinToString(" AND ") { "($it)" }
    private fun journalStorage(): String = listOf(stockTextStorageV2("scope", 110, 110), stockTextStorageV2("operationId", 36, 36),
        stockTextStorageV2("assignmentId", 36, 36), "typeof(sequence)='integer' AND sequence>=1", "typeof(epoch)='integer' AND epoch>=0",
        stockTextStorageV2("previousHash", 64, 64), stockTextStorageV2("hash", 64, 64), "typeof(envelope)='blob'").joinToString(" AND ") { "($it)" }
    private fun bindOperation(statement: SQLiteStatement, id: OperationId) { statement.bindText(1, loan.scope.storageKey()); statement.bindText(2, id.value) }
}

internal const val STOCK_JOURNAL_FRAGMENT_BYTES_V2: Int = 49_152
internal const val STOCK_JOURNAL_ROOT_BYTES_V2: Int = 4_096
// One StoredBytes plus fixed, bounded identity framing. Never an entire operation.
internal const val STOCK_FRAME_BYTES_V2: Int = 67_584

internal class JournalRootV2(
    val identity: RetainedCommandIdentityV2,
    val fragmentCount: Long,
    val streamBytes: Long,
    val commitment: PayloadDigest,
    counts: LongArray,
    sectionBytes: LongArray,
) {
    val counts: LongArray = counts.copyOf()
    val sectionBytes: LongArray = sectionBytes.copyOf()
    init {
        stockSqlRequireV2(identity.producer.command in StockStreamingCommandsV2.all && fragmentCount > 0 && streamBytes > 0 && counts.size == 8 && sectionBytes.size == 8 &&
            counts.all { it >= 0 } && sectionBytes.all { it >= 0 } && counts[0] == 1L && counts[1] == 1L &&
            stockCheckedAddV2(counts[2], counts[3]) > 0)
        val expected = streamBytes / STOCK_JOURNAL_FRAGMENT_BYTES_V2 + if (streamBytes % STOCK_JOURNAL_FRAGMENT_BYTES_V2 == 0L) 0L else 1L
        stockSqlRequireV2(fragmentCount == expected)
        stockSqlRequireV2(sectionBytes.fold(0L, ::stockCheckedAddV2) == streamBytes)
    }
}

internal object RetainedCommandFramesV2 {
    private const val ROOT: String = "ukkera.journal/root/v2"
    private const val FRAME: String = "ukkera.journal/frame/v2"

    fun isRoot(bytes: ByteArray): Boolean {
        if (bytes.size < ROOT.length + 4) return false
        return bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3].toInt() == ROOT.length &&
            bytes.copyOfRange(4, 4 + ROOT.length).contentEquals(ROOT.encodeToByteArray())
    }

    fun encodeRoot(root: JournalRootV2): ByteArray = StockFrameWriterV2(STOCK_JOURNAL_ROOT_BYTES_V2).run {
        text(ROOT, 32); scope(root.identity.scope); producer(root.identity.producer); id(root.identity.assignmentId)
        long(root.identity.authorityEpoch.value); long(root.identity.sequence.value); text(root.identity.recordedAt.toString(), 30)
        id(root.identity.authenticationId); long(root.identity.policyRevision.value); digest(root.identity.resultHash); digest(root.identity.previousJournalHash)
        long(root.fragmentCount); long(root.streamBytes); digest(root.commitment)
        repeat(8) { long(root.counts[it]); long(root.sectionBytes[it]) }
        finish()
    }

    fun decodeRoot(bytes: ByteArray): JournalRootV2 = StockFrameReaderV2(bytes, STOCK_JOURNAL_ROOT_BYTES_V2).run {
        stockSqlRequireV2(text(32) == ROOT)
        val identity = RetainedCommandIdentityV2(scope(), producer(), id(), Revision(long()), JournalSequence(long()),
            UtcInstant.parseCanonical(text(30)), id(), Revision(long()), digest(), digest())
        val count = long(); val length = long(); val commitment = digest(); val counts = LongArray(8); val sizes = LongArray(8)
        repeat(8) { counts[it] = long(); sizes[it] = long() }
        end(); JournalRootV2(identity, count, length, commitment, counts, sizes).also { stockSqlRequireV2(encodeRoot(it).contentEquals(bytes)) }
    }

    fun encode(frame: RetainedCommandFrameV2): ByteArray = StockFrameWriterV2(STOCK_FRAME_BYTES_V2).run {
        text(FRAME, 32); int(frame.section.tag)
        when (frame) {
            is RetainedCommandFrameV2.Normalized -> stored(frame.body)
            is RetainedCommandFrameV2.Result -> stored(frame.body)
            is RetainedCommandFrameV2.Write -> with(frame.value) { key(key); long(storageGeneration.value); text(schema, 96); stored(payload); digest(payloadDigest) }
            is RetainedCommandFrameV2.Claim -> with(frame.value) { int(if (action == RetainedClaimAction.ACQUIRE) 1 else 2); claim(key); id(ownerId) }
            is RetainedCommandFrameV2.Job -> { long(frame.ordinal); text(frame.value.kind, 96); stored(frame.value.body) }
            is RetainedCommandFrameV2.Source -> when (val value = frame.value) {
                is StreamedSourceObservationV2.Aggregate -> { int(1); key(value.key); long(value.generation.value); text(value.schema, 96)
                    stored(value.payload); digest(value.payloadHash); producer(value.producer) }
                is StreamedSourceObservationV2.Claim -> { int(2); claim(value.key); optionalId(value.ownerId) }
                is StreamedSourceObservationV2.Current -> { int(3); key(value.key); long(value.generation.value); text(value.schema, 96)
                    stored(value.payload); digest(value.payloadHash) }
                is StreamedSourceObservationV2.StockRange -> { int(4); query(value.query); long(value.count); digest(value.commitment) }
                is StreamedSourceObservationV2.StockMember -> { int(5); query(value.query); long(value.ordinal); index(value.record) }
                is StreamedSourceObservationV2.CurrentAbsent -> { int(6); key(value.key) }
                is StreamedSourceObservationV2.StockItemAbsent -> { int(9); key(value.key) }
                is StreamedSourceObservationV2.EstateMember -> { int(7); long(value.ordinal); estate(value.record) }
                is StreamedSourceObservationV2.EstateEnd -> { int(8); long(value.count); digest(value.commitment) }
            }
            is RetainedCommandFrameV2.Effect -> { text(frame.value.kind, 96); id(frame.value.id); stored(frame.value.payload) }
            is RetainedCommandFrameV2.Resource -> { int(stockResourceTagV2(frame.value.kind)); id(frame.value.resourceId) }
        }
        finish()
    }

    fun decode(bytes: ByteArray): RetainedCommandFrameV2 = StockFrameReaderV2(bytes, STOCK_FRAME_BYTES_V2).run {
        stockSqlRequireV2(text(32) == FRAME)
        val result: RetainedCommandFrameV2 = when (StreamedSectionV2.fromTag(int())) {
            StreamedSectionV2.NORMALIZED -> RetainedCommandFrameV2.Normalized(stored())
            StreamedSectionV2.RESULT -> RetainedCommandFrameV2.Result(stored())
            StreamedSectionV2.WRITES -> {
                val key = key(); val generation = Revision(long()); val schema = text(96).also(::requireProtocolName); val payload = stored(); val digest = digest()
                stockSqlRequireV2(generation.value >= 1 && sha256(payload.copyBytes()) == digest)
                RetainedCommandFrameV2.Write(RetainedAggregateWrite(key, generation, schema, payload, digest))
            }
            StreamedSectionV2.CLAIMS -> RetainedCommandFrameV2.Claim(RetainedClaimChange(when (int()) {
                1 -> RetainedClaimAction.ACQUIRE; 2 -> RetainedClaimAction.RELEASE; else -> stockSqlUnavailableV2()
            }, claim(), id()))
            StreamedSectionV2.JOBS -> {
                val ordinal = long(); val kind = text(96).also(::requireProtocolName); stockSqlRequireV2(kind != SYNC_JOB)
                RetainedCommandFrameV2.Job(ordinal, RetainedJobIntent(kind, stored()))
            }
            StreamedSectionV2.SOURCES -> RetainedCommandFrameV2.Source(when (int()) {
                1 -> StreamedSourceObservationV2.Aggregate(key(), Revision(long()), text(96), stored(), digest(), producer())
                2 -> StreamedSourceObservationV2.Claim(claim(), optionalId())
                3 -> StreamedSourceObservationV2.Current(key(), Revision(long()), text(96), stored(), digest())
                4 -> StreamedSourceObservationV2.StockRange(query(), long(), digest())
                5 -> StreamedSourceObservationV2.StockMember(query(), long(), index())
                6 -> StreamedSourceObservationV2.CurrentAbsent(key())
                7 -> StreamedSourceObservationV2.EstateMember(long(), estate())
                8 -> StreamedSourceObservationV2.EstateEnd(long(), digest())
                9 -> StreamedSourceObservationV2.StockItemAbsent(key())
                else -> stockSqlUnavailableV2()
            })
            StreamedSectionV2.EFFECTS -> RetainedCommandFrameV2.Effect(StreamedEffectV2(text(96), id(), stored()))
            StreamedSectionV2.RESOURCES -> RetainedCommandFrameV2.Resource(RecoveryResourceKey(stockResourceKindV2(int()), id()))
        }
        end(); result
    }

    fun key(frame: RetainedCommandFrameV2): Pair<String, String> = when (frame) {
        is RetainedCommandFrameV2.Normalized, is RetainedCommandFrameV2.Result -> "" to ""
        is RetainedCommandFrameV2.Write -> frame.value.key.kind to frame.value.key.id.value
        is RetainedCommandFrameV2.Claim -> frame.value.key.namespace to frame.value.key.key
        is RetainedCommandFrameV2.Job -> frame.ordinal.toString().padStart(19, '0') to ""
        is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
            is StreamedSourceObservationV2.Aggregate -> "aggregate.${source.key.kind}" to source.key.id.value
            is StreamedSourceObservationV2.Claim -> "claim.${source.key.namespace}" to source.key.key
            is StreamedSourceObservationV2.Current -> "current.${source.key.kind}" to source.key.id.value
            is StreamedSourceObservationV2.CurrentAbsent -> "absent.${source.key.kind}" to source.key.id.value
            is StreamedSourceObservationV2.StockItemAbsent -> "stock.item-absent" to source.key.id.value
            is StreamedSourceObservationV2.EstateMember -> "estate.member" to source.ordinal.toString().padStart(19, '0')
            is StreamedSourceObservationV2.EstateEnd -> "estate.end" to ""
            is StreamedSourceObservationV2.StockRange -> "stock.range" to stockQueryIdV2(source.query).value
            is StreamedSourceObservationV2.StockMember -> "stock.member.${stockQueryIdV2(source.query).value}" to source.ordinal.toString().padStart(19, '0')
        }
        is RetainedCommandFrameV2.Effect -> frame.value.kind to frame.value.id.value
        is RetainedCommandFrameV2.Resource -> stockResourceTagV2(frame.value.kind).toString().padStart(2, '0') to frame.value.resourceId.value
    }
}

/** Bounded chained commitments. These are NOT SHA256(concatenated unbounded operation). */
internal class StockSectionChainV2(private val section: StreamedSectionV2) {
    private var state = sha256("ukkera.journal/section-chain/v2/${section.tag}".encodeToByteArray())
    private var count = 0L
    private var bytes = 0L
    private var finished = false
    fun append(record: ByteArray) {
        stockSqlRequireV2(!finished && record.size <= STOCK_FRAME_BYTES_V2)
        val out = StockFrameWriterV2(256)
        out.text("ukkera.journal/section-link/v2", 48); out.int(section.tag); out.digest(state); out.long(count); out.int(record.size); out.digest(sha256(record))
        state = sha256(out.finish()); count = stockCheckedAddV2(count, 1); bytes = stockCheckedAddV2(bytes, record.size.toLong() + 4)
    }
    fun finish(): StreamedSectionSummaryV2 {
        stockSqlRequireV2(!finished); finished = true
        val out = StockFrameWriterV2(256)
        out.text("ukkera.journal/section-finish/v2", 48); out.int(section.tag); out.digest(state); out.long(count); out.long(bytes)
        return StreamedSectionSummaryV2(count, bytes, sha256(out.finish()))
    }
}

internal class StockFragmentChainV2 {
    private var state = sha256("ukkera.journal/fragment-chain/v2".encodeToByteArray())
    private var count = 0L
    private var bytes = 0L
    fun append(ordinal: Long, payload: ByteArray) {
        stockSqlRequireV2(ordinal == count && payload.size in 1..STOCK_JOURNAL_FRAGMENT_BYTES_V2)
        val out = StockFrameWriterV2(256)
        out.text("ukkera.journal/fragment-link/v2", 48); out.digest(state); out.long(ordinal); out.int(payload.size); out.digest(sha256(payload))
        state = sha256(out.finish()); count = stockCheckedAddV2(count, 1); bytes = stockCheckedAddV2(bytes, payload.size.toLong())
    }
    fun finish(): StreamedSectionSummaryV2 {
        val out = StockFrameWriterV2(256)
        out.text("ukkera.journal/fragment-finish/v2", 48); out.digest(state); out.long(count); out.long(bytes)
        return StreamedSectionSummaryV2(count, bytes, sha256(out.finish()))
    }
}

internal class StockFrameOrderV2 {
    private var section = 0
    private var previous: Pair<String, String>? = null
    val counts: LongArray = LongArray(8)
    val bytes: LongArray = LongArray(8)
    fun append(frame: RetainedCommandFrameV2, size: Int) {
        val tag = frame.section.tag
        stockSqlRequireV2(tag >= section)
        if (tag != section) previous = null
        val key = RetainedCommandFramesV2.key(frame)
        previous?.let { stockSqlRequireV2(it.first < key.first || it.first == key.first && it.second < key.second) }
        section = tag; previous = key
        counts[tag - 1] = stockCheckedAddV2(counts[tag - 1], 1)
        bytes[tag - 1] = stockCheckedAddV2(bytes[tag - 1], size.toLong() + 4)
        if (frame is RetainedCommandFrameV2.Job) stockSqlRequireV2(frame.ordinal == counts[4] && frame.ordinal <= MAX_JOBS)
    }
    fun finish(expectedCounts: LongArray, expectedBytes: LongArray) {
        stockSqlRequireV2(counts.contentEquals(expectedCounts) && bytes.contentEquals(expectedBytes) && counts[0] == 1L && counts[1] == 1L)
    }
}

/** Record length can cross fragments; at most one bounded frame + one fragment is retained. */
internal class StockFrameStreamDecoderV2(private val consume: suspend (ByteArray) -> Unit) {
    private val length = ByteArray(4)
    private var lengthAt = 0
    private var record: ByteArray? = null
    private var recordAt = 0
    private var cleared = false
    suspend fun append(fragment: ByteArray) {
        stockSqlRequireV2(!cleared)
        var at = 0
        while (at < fragment.size) {
            if (record == null) {
                while (at < fragment.size && lengthAt < 4) length[lengthAt++] = fragment[at++]
                if (lengthAt < 4) continue
                var size = 0L
                for (byte in length) size = (size shl 8) or (byte.toLong() and 255)
                stockSqlRequireV2(size in 1L..STOCK_FRAME_BYTES_V2.toLong())
                record = ByteArray(size.toInt()); recordAt = 0
            }
            val current = checkNotNull(record)
            val count = minOf(current.size - recordAt, fragment.size - at)
            fragment.copyInto(current, recordAt, at, at + count); recordAt += count; at += count
            if (recordAt == current.size) {
                record = null; lengthAt = 0; recordAt = 0
                // Completed frame is borrowed only during consume; decoded StoredBytes copy it.
                try { consume(current) } finally { current.fill(0) }
            }
        }
    }
    fun finish() { stockSqlRequireV2(!cleared && record == null && recordAt == 0 && lengthAt == 0) }
    /** Mandatory borrower finally, including malformed input, callback failure and cancellation. */
    fun clear() {
        record?.fill(0); record = null; recordAt = 0
        length.fill(0); lengthAt = 0; cleared = true
    }
}

internal class StockFrameWriterV2(private val maximum: Int) {
    private var bytes = ByteArray(minOf(maximum, 256))
    private var at = 0
    private fun need(count: Int) {
        stockSqlRequireV2(count >= 0 && count <= maximum - at)
        if (at + count > bytes.size) bytes = bytes.copyOf(maxOf(at + count, minOf(maximum, bytes.size * 2)))
    }
    fun int(value: Int) { need(4); for (shift in 24 downTo 0 step 8) bytes[at++] = (value ushr shift).toByte() }
    fun long(value: Long) { need(8); for (shift in 56 downTo 0 step 8) bytes[at++] = (value ushr shift).toByte() }
    fun bytes(value: ByteArray, maximum: Int) { stockSqlRequireV2(value.size <= maximum); need(4 + value.size); int(value.size); value.copyInto(bytes, at); at += value.size }
    fun text(value: String, maximum: Int) {
        stockSqlRequireV2('\u0000' !in value)
        // ASCII is already canonical UTF-8. Keep strict conversion for every
        // non-ASCII code unit, including malformed surrogate sequences.
        val encoded = if (value.all { it.code <= 0x7f }) {
            ByteArray(value.length) { value[it].code.toByte() }
        } else value.encodeToByteArray(throwOnInvalidSequence = true)
        bytes(encoded, maximum)
    }
    fun id(value: DomainId) { text(value.value, 36) }
    fun optionalId(value: DomainId?) { text(value?.value.orEmpty(), 36) }
    fun digest(value: PayloadDigest) { text(value.value, 64) }
    fun stored(value: StoredBytes) { bytes(value.copyBytes(), MAX_STORED_BYTES) }
    fun scope(value: BranchScope) { id(value.organizationId); id(value.companyId); id(value.branchId) }
    fun key(value: AggregateKey) { text(value.kind, 96); id(value.id) }
    fun claim(value: ClaimKey) { text(value.namespace, 96); text(value.key, 256) }
    fun producer(value: StreamedProducerIdentityV2) { text(value.operationId.value, 36); text(value.command.name, 96); int(value.command.version); id(value.originalActorId); digest(value.semanticDigest) }
    fun estate(value: StockEstateRecordV2) {
        when (value) {
            is StockEstateRecordV2.Aggregate -> { int(1); key(value.key); long(value.generation.value); text(value.schema, 96); stored(value.payload); digest(value.payloadHash) }
            is StockEstateRecordV2.Claim -> { int(2); claim(value.key); id(value.ownerId) }
            is StockEstateRecordV2.Original -> { int(3); text(value.operationId.value, 36); int(value.format); digest(value.journalHash) }
        }
    }
    fun query(value: StockQueryV2) {
        when (value) {
            StockQueryV2.AllPositions -> int(11)
            StockQueryV2.AllReservations -> int(12)
            is StockQueryV2.SkuPositions -> { int(13); id(value.skuId) }
            is StockQueryV2.Positions -> { int(1); id(value.skuId); id(value.warehouseId) }
            is StockQueryV2.LotPositions -> { int(2); id(value.skuId); id(value.lotId) }
            is StockQueryV2.PositionMembers -> { int(3); id(value.positionId) }
            is StockQueryV2.ReservationMembers -> { int(4); id(value.reservationId) }
            is StockQueryV2.OrderReservations -> { int(5); id(value.orderId) }
            is StockQueryV2.CollectionReservations -> { int(6); id(value.collectionId) }
            is StockQueryV2.PoolValues -> { int(7); id(value.poolId) }
            is StockQueryV2.SourceValues -> { int(8); id(value.sourceId) }
            is StockQueryV2.IssueValues -> { int(9); id(value.issueId) }
            is StockQueryV2.LinkedValues -> { int(10); id(value.linkedPoolId) }
        }
    }
    fun index(value: StockIndexRecordV2) {
        with(value.identity) { key(key); long(generation.value); text(producerOperationId.value, 36); digest(payloadHash) }
        when (value) {
            is StockIndexRecordV2.Position -> { int(1); id(value.skuId); id(value.warehouseId); int(value.ownership); optionalId(value.ownerId)
                int(value.custody); optionalId(value.lotId); optionalId(value.serialId); long(value.expiryMillis ?: -1) }
            is StockIndexRecordV2.Allocation -> { int(2); id(value.reservationId); optionalId(value.allocationId); optionalId(value.lineId)
                optionalId(value.positionId); int(value.holdReason) }
            is StockIndexRecordV2.Reservation -> { int(3); id(value.orderId); id(value.collectionId); id(value.originalActorId)
                int(value.status); int(value.exposure); long(value.expiresAtMillis ?: -1) }
            is StockIndexRecordV2.Value -> { int(4); id(value.poolId); id(value.sourceId); optionalId(value.parentEventId)
                optionalId(value.issueId); optionalId(value.allocationId); optionalId(value.linkedPoolId); long(value.eventOrdinal) }
        }
    }
    fun finish(): ByteArray = bytes.copyOf(at)
}

internal class StockFrameReaderV2(private val bytes: ByteArray, maximum: Int) {
    private var at = 0
    init { stockSqlRequireV2(bytes.size <= maximum) }
    private fun need(count: Int) { stockSqlRequireV2(count >= 0 && count <= bytes.size - at) }
    fun int(): Int { need(4); var value = 0; repeat(4) { value = (value shl 8) or (bytes[at++].toInt() and 255) }; return value }
    fun long(): Long { need(8); var value = 0L; repeat(8) { value = (value shl 8) or (bytes[at++].toLong() and 255) }; return value }
    fun bytes(maximum: Int): ByteArray { val size = int(); stockSqlRequireV2(size in 0..maximum); need(size); return bytes.copyOfRange(at, at + size).also { at += size } }
    fun text(maximum: Int): String {
        val value = bytes(maximum)
        // Every nonnegative byte is a complete canonical ASCII scalar. Any
        // high-bit byte still goes through the existing strict UTF-8 decoder.
        val text = if (value.all { it >= 0 }) {
            buildString(value.size) { for (byte in value) append(byte.toInt().toChar()) }
        } else value.decodeToString(throwOnInvalidSequence = true)
        stockSqlRequireV2('\u0000' !in text)
        return text
    }
    fun id(): DomainId = DomainId(text(36))
    fun optionalId(): DomainId? = text(36).let { if (it.isEmpty()) null else DomainId(it) }
    fun digest(): PayloadDigest = PayloadDigest(text(64))
    fun stored(): StoredBytes = StoredBytes(bytes(MAX_STORED_BYTES))
    fun scope(): BranchScope = BranchScope(id(), id(), id())
    fun key(): AggregateKey = AggregateKey(text(96), id())
    fun claim(): ClaimKey = ClaimKey(text(96), text(256))
    fun producer(): StreamedProducerIdentityV2 = StreamedProducerIdentityV2(OperationId(text(36)), CommandKey(text(96), int()), id(), digest())
    fun estate(): StockEstateRecordV2 = when (int()) {
        1 -> StockEstateRecordV2.Aggregate(key(), Revision(long()), text(96), stored(), digest())
        2 -> StockEstateRecordV2.Claim(claim(), id())
        3 -> StockEstateRecordV2.Original(OperationId(text(36)), int(), digest())
        else -> stockSqlUnavailableV2()
    }
    fun query(): StockQueryV2 = when (int()) {
        1 -> StockQueryV2.Positions(id(), id()); 2 -> StockQueryV2.LotPositions(id(), id())
        3 -> StockQueryV2.PositionMembers(id()); 4 -> StockQueryV2.ReservationMembers(id())
        5 -> StockQueryV2.OrderReservations(id()); 6 -> StockQueryV2.CollectionReservations(id())
        7 -> StockQueryV2.PoolValues(id()); 8 -> StockQueryV2.SourceValues(id())
        9 -> StockQueryV2.IssueValues(id()); 10 -> StockQueryV2.LinkedValues(id())
        11 -> StockQueryV2.AllPositions; 12 -> StockQueryV2.AllReservations; 13 -> StockQueryV2.SkuPositions(id()); else -> stockSqlUnavailableV2()
    }
    private fun optionalTime(): Long? { val value = long(); stockSqlRequireV2(value >= -1); return value.takeIf { it >= 0 } }
    fun index(): StockIndexRecordV2 {
        val identity = StockIndexIdentityV2(key(), Revision(long()), OperationId(text(36)), digest())
        return when (int()) {
            1 -> StockIndexRecordV2.Position(identity, id(), id(), int(), optionalId(), int(), optionalId(), optionalId(), optionalTime())
            2 -> StockIndexRecordV2.Allocation(identity, id(), optionalId(), optionalId(), optionalId(), int())
            3 -> StockIndexRecordV2.Reservation(identity, id(), id(), id(), int(), int(), optionalTime())
            4 -> StockIndexRecordV2.Value(identity, id(), id(), optionalId(), optionalId(), optionalId(), optionalId(), long())
            else -> stockSqlUnavailableV2()
        }
    }
    fun end() { stockSqlRequireV2(at == bytes.size) }
}

internal fun stockResourceTagV2(kind: RecoveryResourceKind): Int = when (kind) {
    RecoveryResourceKind.STOCK -> 1; RecoveryResourceKind.REFUND_ENTITLEMENT -> 2; RecoveryResourceKind.DEPOSIT_ENTITLEMENT -> 3
    RecoveryResourceKind.EXCHANGE_OFFSET -> 4; RecoveryResourceKind.CASH_CUSTODY -> 5; RecoveryResourceKind.PO_ALLOWANCE -> 6
    RecoveryResourceKind.SUPPLIER_SETTLEMENT -> 7; RecoveryResourceKind.KITCHEN_CUSTODY -> 8; RecoveryResourceKind.ORDER_CUSTODY -> 9
    RecoveryResourceKind.PURCHASE_CUSTODY -> 10; RecoveryResourceKind.AUTHORIZATION -> 11
}
internal fun stockResourceKindV2(tag: Int): RecoveryResourceKind = when (tag) {
    1 -> RecoveryResourceKind.STOCK; 2 -> RecoveryResourceKind.REFUND_ENTITLEMENT; 3 -> RecoveryResourceKind.DEPOSIT_ENTITLEMENT
    4 -> RecoveryResourceKind.EXCHANGE_OFFSET; 5 -> RecoveryResourceKind.CASH_CUSTODY; 6 -> RecoveryResourceKind.PO_ALLOWANCE
    7 -> RecoveryResourceKind.SUPPLIER_SETTLEMENT; 8 -> RecoveryResourceKind.KITCHEN_CUSTODY; 9 -> RecoveryResourceKind.ORDER_CUSTODY
    10 -> RecoveryResourceKind.PURCHASE_CUSTODY; 11 -> RecoveryResourceKind.AUTHORIZATION; else -> stockSqlUnavailableV2()
}

internal fun stockQueryIdV2(query: StockQueryV2): PayloadDigest =
    sha256(StockFrameWriterV2(256).run { text("ukkera.stock/query/v2", 32); query(query); finish() })

/** Domain-separated per-member literal range commitment; always recomputed by reading all members. */
internal class StockRangeChainV2(private val query: StockQueryV2) {
    private var state = stockQueryIdV2(query)
    var count: Long = 0
        private set
    fun append(row: StockIndexRecordV2) {
        val bytes = StockFrameWriterV2(2048).run { index(row); finish() }
        state = sha256(StockFrameWriterV2(256).run {
            text("ukkera.stock/range-link/v2", 32); digest(state); long(count); digest(sha256(bytes)); finish()
        })
        count = stockCheckedAddV2(count, 1)
    }
    fun finish(): StreamedSourceObservationV2.StockRange = StreamedSourceObservationV2.StockRange(query, count,
        sha256(StockFrameWriterV2(256).run { text("ukkera.stock/range-end/v2", 32); digest(state); long(count); finish() }))
}

internal fun stockEstateOriginalV2(original: RetainedOriginalV2): StockEstateRecordV2.Original = when (original) {
    is RetainedOriginalV2.Legacy -> StockEstateRecordV2.Original(original.evidence.operationId, 1, original.evidence.journalHash)
    is RetainedOriginalV2.Streamed -> StockEstateRecordV2.Original(original.evidence.identity.producer.operationId, 2, original.evidence.journalHash)
}

internal class StockEstateChainV2 {
    private var state = sha256("ukkera.stock/estate/v2".encodeToByteArray())
    private var rank = 0
    var count: Long = 0
        private set
    fun append(row: StockEstateRecordV2) {
        val next = when (row) { is StockEstateRecordV2.Aggregate -> 1; is StockEstateRecordV2.Claim -> 2; is StockEstateRecordV2.Original -> 3 }
        stockSqlRequireV2(next >= rank); rank = next
        val bytes = StockFrameWriterV2(STOCK_FRAME_BYTES_V2).run { estate(row); finish() }
        state = sha256(StockFrameWriterV2(256).run {
            text("ukkera.stock/estate-link/v2", 32); digest(state); long(count); digest(sha256(bytes)); finish()
        })
        count = stockCheckedAddV2(count, 1)
    }
    fun finish(): StreamedSourceObservationV2.EstateEnd = StreamedSourceObservationV2.EstateEnd(count,
        sha256(StockFrameWriterV2(256).run { text("ukkera.stock/estate-end/v2", 32); digest(state); long(count); finish() }))
}

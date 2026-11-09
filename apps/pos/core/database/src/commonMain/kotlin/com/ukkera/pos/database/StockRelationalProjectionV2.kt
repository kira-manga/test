package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Closed storage names. There is no caller-selected table, column, or SQL expression. */
public object StockRelationalKindsV2 {
    public const val ITEM: String = "local.stock.v2.item"
    public const val POSITION: String = "local.stock.v2.position"
    public const val POOL: String = "local.stock.v2.pool"
    public const val RESERVATION: String = "local.stock.v2.reservation"
    public const val ALLOCATION: String = "local.stock.v2.allocation"
    public const val HOLD: String = "local.stock.v2.hold"
    public const val ISSUE: String = "local.stock.v2.issue"
    public const val ISSUE_SLICE: String = "local.stock.v2.issue-slice"
    public const val VALUE_EVENT: String = "local.stock.v2.value-event"
    public const val VALUE_SLICE: String = "local.stock.v2.value-slice"
    public const val VALUE_TARGET: String = "local.stock.v2.value-target"
    public const val TRANSFER_EDGE: String = "local.stock.v2.transfer-edge"
    public const val POSTING: String = "local.stock.v2.posting"
    public const val ACTIVATION: String = "local.stock.v2.activation"
    public val indexed: Set<String> get() = setOf(POSITION, RESERVATION, ALLOCATION, HOLD,
        ISSUE_SLICE, VALUE_EVENT, VALUE_SLICE, VALUE_TARGET, TRANSFER_EDGE)
    public val all: Set<String> get() = indexed + setOf(ITEM, POOL, ISSUE, POSTING, ACTIVATION)
    public fun schema(kind: String): String {
        stockSqlRequireV2(kind in all)
        return "$kind/v2"
    }
}

/** Equality includes the full aggregate identity, exact generation and literal payload digest. */
public data class StockIndexIdentityV2(
    public val key: AggregateKey,
    public val generation: Revision,
    public val producerOperationId: OperationId,
    public val payloadHash: PayloadDigest,
) {
    init { stockSqlRequireV2(generation.value >= 1 && key.kind in StockRelationalKindsV2.indexed) }
}

public sealed interface StockIndexRecordV2 {
    public val identity: StockIndexIdentityV2

    public data class Position(
        override val identity: StockIndexIdentityV2,
        public val skuId: DomainId,
        public val warehouseId: DomainId,
        /** 1 company, 2 supplier. Never a Kotlin enum ordinal. */
        public val ownership: Int,
        public val ownerId: DomainId?,
        /** 1 released, 2 quarantine, 3 rejected-held. */
        public val custody: Int,
        public val lotId: DomainId?,
        public val serialId: DomainId?,
        public val expiryMillis: Long?,
    ) : StockIndexRecordV2 {
        init {
            stockSqlRequireV2(identity.key.kind == StockRelationalKindsV2.POSITION && ownership in 1..2 &&
                (ownership == 1) == (ownerId == null) && custody in 1..3 &&
                (expiryMillis == null || expiryMillis >= 0))
        }
        public val expiryOrder: Long get() = expiryMillis ?: Long.MAX_VALUE
    }

    public data class Allocation(
        override val identity: StockIndexIdentityV2,
        public val reservationId: DomainId,
        public val allocationId: DomainId?,
        public val lineId: DomainId?,
        public val positionId: DomainId?,
        /** Zero for allocation, 1..5 for the fixed hold variants. */
        public val holdReason: Int,
    ) : StockIndexRecordV2 {
        init {
            stockSqlRequireV2(when (identity.key.kind) {
                StockRelationalKindsV2.ALLOCATION -> allocationId == identity.key.id && lineId != null && positionId != null && holdReason == 0
                StockRelationalKindsV2.HOLD -> allocationId == null && lineId == null && holdReason in 1..5
                else -> false
            })
        }
    }

    public data class Reservation(
        override val identity: StockIndexIdentityV2,
        public val orderId: DomainId,
        public val collectionId: DomainId,
        public val originalActorId: DomainId,
        public val status: Int,
        public val exposure: Int,
        public val expiresAtMillis: Long?,
    ) : StockIndexRecordV2 {
        init {
            stockSqlRequireV2(identity.key.kind == StockRelationalKindsV2.RESERVATION && status in 1..4 && exposure in 1..4 &&
                (expiresAtMillis == null || expiresAtMillis >= 0))
        }
    }

    public data class Value(
        override val identity: StockIndexIdentityV2,
        public val poolId: DomainId,
        public val sourceId: DomainId,
        public val parentEventId: DomainId?,
        public val issueId: DomainId?,
        public val allocationId: DomainId?,
        public val linkedPoolId: DomainId?,
        public val eventOrdinal: Long,
    ) : StockIndexRecordV2 {
        init {
            stockSqlRequireV2(eventOrdinal >= 0 && when (identity.key.kind) {
                StockRelationalKindsV2.VALUE_EVENT -> parentEventId == null && allocationId == null && linkedPoolId == null
                StockRelationalKindsV2.VALUE_SLICE -> parentEventId != null && allocationId != null && linkedPoolId == null
                StockRelationalKindsV2.VALUE_TARGET -> parentEventId != null && allocationId == null && linkedPoolId == null
                StockRelationalKindsV2.TRANSFER_EDGE -> parentEventId != null && linkedPoolId != null && linkedPoolId != poolId
                StockRelationalKindsV2.ISSUE_SLICE -> issueId != null && allocationId != null && linkedPoolId == null
                else -> false
            })
        }
    }
}

/** Literal complete-estate members. Only the owner can append their source frames.
 * Originals remain the actual full retained pair/stream, not a new hash-only history store. */
public sealed interface StockEstateRecordV2 {
    public data class Aggregate(public val key: AggregateKey, public val generation: Revision,
        public val schema: String, public val payload: StoredBytes, public val payloadHash: PayloadDigest) : StockEstateRecordV2 {
        init { requireProtocolName(schema); stockSqlRequireV2(generation.value >= 1 && com.ukkera.pos.security.sha256(payload.copyBytes()) == payloadHash) }
    }
    public data class Claim(public val key: ClaimKey, public val ownerId: DomainId) : StockEstateRecordV2
    public data class Original(public val operationId: OperationId, public val format: Int,
        public val journalHash: PayloadDigest) : StockEstateRecordV2 {
        init { stockSqlRequireV2(format == 1 || format == 2) }
    }
}

/** Installed typed stock codec, not decoded from a request and not an authorization port. */
public fun interface StockCanonicalProjectionV2 {
    /** Decode/canonicalize the actual aggregate; null is legal ONLY for a non-indexed kind. */
    public fun project(scope: BranchScope, aggregate: StoredAggregate): StockIndexRecordV2?
}

/** Native qualification and the all-table physical schema census remain the kernel's duties. */
internal class StockRelationalProjection(private val codec: StockCanonicalProjectionV2) {
    fun project(scope: BranchScope, row: StoredAggregate): StockIndexRecordV2? {
        val projected = codec.project(scope, row)
        stockSqlRequireV2((row.key.kind in StockRelationalKindsV2.indexed) == (projected != null))
        if (projected != null) stockSqlRequireV2(projected.identity.key == row.key &&
            projected.identity.generation == row.revision && projected.identity.payloadHash == row.digest)
        return projected
    }

    /**
     * Complete BOTH directions in bounded pages. No selector filter can hide a malformed member,
     * orphan, missing row, wrong generation or changed coordinate. This is loan-local derived-row
     * validation, not a history/absence/frontier certificate. The actual owner's physical census
     * additionally covers unsupported scopes, schema objects and every other persistent table.
     */
    suspend fun census(dao: StockRelationalDao) {
        // BEFORE any scope/kind WHERE: malformed aliases in any physical stock selector fail.
        dao.requireUnfilteredIndexShapes()
        var aggregates = 0L
        for (kind in StockRelationalKindsV2.indexed.sorted()) {
            var after: DomainId? = null
            do {
                val keys = dao.aggregateKeys(kind, after)
                for (key in keys) {
                    val aggregate = dao.aggregate(key) ?: stockSqlUnavailableV2()
                    val projected = project(dao.scope, aggregate) ?: stockSqlUnavailableV2()
                    stockSqlRequireV2(dao.index(key) == projected)
                    aggregates = stockCheckedAddV2(aggregates, 1)
                }
                after = if (keys.size == STOCK_SQL_PAGE_V2) keys.last().id else null
            } while (after != null)
        }
        var indexes = 0L
        for (table in StockIndexTableV2.entries) {
            var after: Long? = null
            while (true) {
                val page = dao.censusPage(table, after)
                for ((rowId, projected) in page) {
                    val aggregate = dao.aggregate(projected.identity.key) ?: stockSqlUnavailableV2()
                    stockSqlRequireV2(project(dao.scope, aggregate) == projected)
                    indexes = stockCheckedAddV2(indexes, 1)
                    after = rowId
                }
                if (page.size < STOCK_SQL_PAGE_V2) break
            }
        }
        stockSqlRequireV2(aggregates == indexes)
    }
}

public sealed interface StockQueryV2 {
    /** Administrative safety sweep, never interpreted as a selected-checkout cut. */
    public data object AllPositions : StockQueryV2
    public data object AllReservations : StockQueryV2
    public data class SkuPositions(public val skuId: DomainId) : StockQueryV2
    public data class Positions(public val skuId: DomainId, public val warehouseId: DomainId) : StockQueryV2
    public data class LotPositions(public val skuId: DomainId, public val lotId: DomainId) : StockQueryV2
    public data class PositionMembers(public val positionId: DomainId) : StockQueryV2
    public data class ReservationMembers(public val reservationId: DomainId) : StockQueryV2
    public data class OrderReservations(public val orderId: DomainId) : StockQueryV2
    public data class CollectionReservations(public val collectionId: DomainId) : StockQueryV2
    public data class PoolValues(public val poolId: DomainId) : StockQueryV2
    public data class SourceValues(public val sourceId: DomainId) : StockQueryV2
    public data class IssueValues(public val issueId: DomainId) : StockQueryV2
    public data class LinkedValues(public val linkedPoolId: DomainId) : StockQueryV2
}

/** Opaque continuation bound to the actual connection, query, invocation and BODY phase. */
public class StockPageCursor internal constructor(
    internal val loan: StockSqlLoan,
    internal val query: StockQueryV2,
    internal val order: List<StockSqlScalar>,
)

public class StockIndexPage internal constructor(
    records: List<StockIndexRecordV2>,
    public val next: StockPageCursor?,
) {
    private val values: List<StockIndexRecordV2> = records.toList()
    public val records: List<StockIndexRecordV2> get() = values.toList()
}

/** Implemented only by the actual TransactionView (or a forwarding owner-preserving wrapper). */
public interface StockRelationalReadView : CommandReadView {
    public suspend fun stockRelationalAccess(): StockRelationalAccess
}

public suspend fun CommandReadView.requireStockRelational(): StockRelationalAccess =
    (this as? StockRelationalReadView)?.stockRelationalAccess() ?: stockSqlUnavailableV2()

/** No public constructor, alternate DAO, pool lookup, SQL string, accepting flag, or writer. */
public class StockRelationalAccess internal constructor(
    internal val loan: StockSqlLoan,
    codec: StockCanonicalProjectionV2,
) {
    private val dao: StockRelationalDao = StockRelationalDao(loan)
    internal val projection: StockRelationalProjection = StockRelationalProjection(codec)
    private var censusComplete: Boolean = false
    private var censusing: Boolean = false
    public val scope: BranchScope get() = loan.scope
    public val originals: RetainedOriginalReader = RetainedOriginalReader(loan)

    public suspend fun page(query: StockQueryV2, after: StockPageCursor? = null): StockIndexPage {
        loan.requireBody()
        if (after != null) stockSqlRequireV2(after.loan === loan && after.query == query)
        requireCensus()
        loan.physicalTrace?.stockPageStart(query, after)
        loan.readCapture?.pageStart(query, after)
        return try { dao.page(query, after).also {
            loan.physicalTrace?.stockPageEnd(query, it.next)
            loan.readCapture?.pageEnd(query, it)
        } } catch (failure: Throwable) { loan.physicalTrace?.fail(); throw failure }
    }

    public suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        loan.requireBody()
        requireCensus()
        loan.physicalTrace?.add(PhysicalReadDependency.Aggregate(key))
        return dao.aggregate(key).also { loan.readCapture?.aggregate(key, it) }
    }

    public suspend fun claim(key: ClaimKey): DomainId? {
        loan.requireBody()
        loan.physicalTrace?.add(PhysicalReadDependency.Claim(key))
        return dao.claim(key).also { loan.readCapture?.append(StreamedSourceObservationV2.Claim(key, it)) }
    }

    public suspend fun keys(kind: String, after: DomainId? = null): List<AggregateKey> {
        loan.requireBody()
        stockSqlRequireV2(kind in StockRelationalKindsV2.all)
        requireCensus()
        stockSqlRequireV2(loan.readCapture?.collecting != true)
        loan.physicalTrace?.stockKeysStart(kind, after)
        return dao.aggregateKeys(kind, after).also { loan.physicalTrace?.stockKeysEnd(kind,
            if (it.size == STOCK_SQL_PAGE_V2) it.last().id else null) }
    }

    /** Actual fixed-query traversal, including the genuine terminal page. No supplied EOF flag. */
    public suspend fun completeRange(query: StockQueryV2, consume: suspend (StockIndexRecordV2) -> Unit) {
        var cursor: StockPageCursor? = null
        do { val page = page(query, cursor); for (row in page.records) consume(row); cursor = page.next } while (cursor != null)
    }

    public suspend fun beginReadEvidence(context: CommandContext): StockReadCapture {
        loan.requireBody()
        stockSqlRequireV2(loan.commandContext === context && context.scope == scope && loan.readCapture == null && loan.physicalTrace == null)
        return StockReadCapture(this, context).also { loan.readCapture = it }
    }

    internal suspend fun validateReadEvidence(plan: ProposedOutcome.Apply) {
        loan.requireBody()
        val evidence = plan.changes.any { it.key.kind == StockReadEvidenceV1.KIND }
        if (evidence) (loan.readCapture ?: stockSqlUnavailableV2()).validate(plan, dao)
        else stockSqlRequireV2(loan.readCapture?.collecting != true && loan.readCapture?.hasEvidence != true)
    }

    /** Complete same-owner current + permanent-claim + retained estate, to real EOF.
     * No caller key list, predicate, completeness boolean, cursor or selected-SKU loader. */
    internal suspend fun estate(consume: suspend (StockEstateRecordV2) -> Unit) {
        loan.requireBody()
        try {
            requireCensus()
            dao.estateCurrent(consume)
            originals.estate(consume)
            loan.requireBody()
        } catch (failure: Throwable) { loan.poison(); throw failure }
    }

    internal suspend fun requireCensus() {
        loan.requireBody()
        if (censusComplete) return
        stockSqlRequireV2(!censusing)
        censusing = true
        try {
            // The complete derived census depends conservatively on EVERY normalized kind.
            // Selected point/claim/original reads are additionally traced below.
            for (kind in StockRelationalKindsV2.all) loan.physicalTrace?.add(PhysicalReadDependency.Range(kind))
            projection.census(dao)
            loan.requireBody()
            censusComplete = true
        } catch (failure: Throwable) {
            loan.poison()
            throw failure
        } finally { censusing = false }
    }
}

internal const val STOCK_SQL_PAGE_V2: Int = 128
internal fun stockSqlUnavailableV2(): Nothing = throw IllegalStateException("Stock v2 storage unavailable")
internal fun stockSqlRequireV2(condition: Boolean) { if (!condition) stockSqlUnavailableV2() }
internal fun stockCheckedAddV2(left: Long, right: Long): Long {
    stockSqlRequireV2(left >= 0 && right >= 0 && right <= Long.MAX_VALUE - left)
    return left + right
}

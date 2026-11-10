package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.sqlite.SQLiteStatement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Minted only by the actual writer owner with its ALREADY borrowed Transactor. It never asks
 * Room for another connection, starts a transaction, commits, publishes, or owns native custody.
 * Owner join: construct in BODY; attach to TransactionView; prepareNormalReturn BEFORE the
 * existing final no-IO observation; finishNow there; abortBeforeReturn in exceptional finally
 * while the same connection is still borrowed. A failed cleanup forbids publication/reuse.
 */
internal class StockSqlLoan(
    internal val owner: AtomicMasterStore,
    internal val scope: BranchScope,
    internal val frameId: DomainId,
    private val connection: Transactor,
    private val ownerContext: CoroutineContext,
    internal val physicalTrace: PhysicalInstructionReadTrace? = null,
) {
    init { stockSqlRequireV2(owner.scope == scope) }
    private enum class Phase { BODY, OWNER_WRITE, CLEANING, PREPARED, CLOSED }
    private var phase: Phase = Phase.BODY
    private var failed: Boolean = false
    private var busy: Boolean = false
    private var scratch: TransactionPlanSpool? = null
    internal var commandContext: CommandContext? = null
    internal var readCapture: StockReadCapture? = null

    internal fun poison() { failed = true; physicalTrace?.fail() }

    internal suspend fun requireBody() {
        requireOwner()
        stockSqlRequireV2(phase == Phase.BODY)
    }

    private suspend fun requireOwner() {
        ownerContext.ensureActive()
        currentCoroutineContext().ensureActive()
        stockSqlRequireV2(!failed && currentCoroutineContext() == ownerContext &&
            (phase == Phase.BODY || phase == Phase.OWNER_WRITE) && connection.inTransaction())
    }

    internal suspend fun <T> sql(sql: String, block: suspend (SQLiteStatement) -> T): T {
        try {
            requireOwner()
            stockSqlRequireV2(!busy)
            busy = true
            val result = connection.usePrepared(sql, block)
            requireOwner()
            return result
        } catch (failure: Throwable) {
            failed = true
            physicalTrace?.fail()
            if (failure is CancellationException) throw failure
            try { reportRuntimeHistoryLoanFailure(failure) }
            catch (_: Throwable) { /* Static observation only, before detail-free conversion. */ }
            stockSqlUnavailableV2()
        } finally { busy = false }
    }

    internal suspend fun attach(spool: TransactionPlanSpool) {
        requireBody()
        stockSqlRequireV2(scratch == null && spool.loan === this)
        // Attach before the first CREATE so partial creation also has a cleanup owner.
        scratch = spool
    }

    internal suspend fun beginOwnerWrite(spool: TransactionPlanSpool) {
        requireBody()
        stockSqlRequireV2(scratch === spool && spool.isSealed)
        phase = Phase.OWNER_WRITE
    }

    internal suspend fun discardProposal(spool: TransactionPlanSpool) {
        requireBody()
        stockSqlRequireV2(scratch === spool)
        cleanup(normal = true)
        ownerContext.ensureActive()
        stockSqlRequireV2(!failed && connection.inTransaction())
        phase = Phase.BODY
    }

    internal suspend fun prepareNormalReturn() {
        requireOwner()
        cleanup(normal = true)
        ownerContext.ensureActive()
        stockSqlRequireV2(!failed && connection.inTransaction())
        phase = Phase.PREPARED
    }

    /** No SQL/suspension. Called before the outer actual commit/publication boundary. */
    internal fun finishNow() {
        ownerContext.ensureActive()
        stockSqlRequireV2(!failed && !busy && scratch == null && phase == Phase.PREPARED)
        phase = Phase.CLOSED
    }

    internal suspend fun abortBeforeReturn() {
        if (phase == Phase.CLOSED && scratch == null) return
        try { cleanup(normal = false) } finally { failed = true; phase = Phase.CLOSED }
    }

    private suspend fun cleanup(normal: Boolean) {
        stockSqlRequireV2(!busy && phase != Phase.CLEANING)
        if (normal) stockSqlRequireV2(!failed && (phase == Phase.BODY || phase == Phase.OWNER_WRITE))
        phase = Phase.CLEANING
        try {
            // Cancellation cannot skip DROP/verification while the actual owner retains its loan.
            withContext(NonCancellable) {
                scratch?.cleanup { sql, block -> connection.usePrepared(sql, block) }
            }
            scratch = null
            readCapture?.clear(); readCapture = null; commandContext = null
        } catch (failure: Throwable) {
            failed = true
            physicalTrace?.fail()
            if (failure is CancellationException) throw failure
            try { reportRuntimeHistoryLoanFailure(failure) }
            catch (_: Throwable) { /* Static observation only, before detail-free conversion. */ }
            stockSqlUnavailableV2()
        }
    }
}

internal sealed interface StockSqlScalar {
    data class Text(val value: String) : StockSqlScalar
    data class Number(val value: Long) : StockSqlScalar
}

internal fun SQLiteStatement.bindStockScalar(index: Int, value: StockSqlScalar) {
    when (value) { is StockSqlScalar.Text -> bindText(index, value.value); is StockSqlScalar.Number -> bindLong(index, value.value) }
}

internal enum class StockIndexTableV2(val table: String, val columns: List<String>) {
    POSITION("stock_position_index_v2", listOf("skuId", "warehouseId", "ownership", "ownerId", "custody", "lotId", "serialId", "expiryMillis", "expiryOrder")),
    ALLOCATION("stock_allocation_index_v2", listOf("reservationId", "allocationId", "lineId", "positionId", "holdReason")),
    RESERVATION("stock_reservation_index_v2", listOf("orderId", "collectionId", "originalActorId", "status", "exposure", "expiresAtMillis")),
    VALUE("stock_value_index_v2", listOf("poolId", "sourceId", "parentEventId", "issueId", "allocationId", "linkedPoolId", "eventOrdinal"));

    val allColumns: List<String> get() = listOf("scope", "recordKind", "recordId", "aggregateGeneration", "producerOperationId", "payloadHash") + columns
    companion object {
        fun forKind(kind: String): StockIndexTableV2 = when (kind) {
            StockRelationalKindsV2.POSITION -> POSITION
            StockRelationalKindsV2.ALLOCATION, StockRelationalKindsV2.HOLD -> ALLOCATION
            StockRelationalKindsV2.RESERVATION -> RESERVATION
            StockRelationalKindsV2.ISSUE_SLICE, StockRelationalKindsV2.VALUE_EVENT, StockRelationalKindsV2.VALUE_SLICE,
            StockRelationalKindsV2.VALUE_TARGET, StockRelationalKindsV2.TRANSFER_EDGE -> VALUE
            else -> stockSqlUnavailableV2()
        }
    }
}

/** Not a Room @Dao: every statement uses the already-confined, exact passed connection. */
internal class StockRelationalDao(internal val loan: StockSqlLoan) {
    val scope: BranchScope get() = loan.scope

    /** Unfiltered rowid metadata passes run BEFORE selectors. Rowid is merely a bounded
     * traversal cursor, never a stock identity or a producer completeness assertion. */
    suspend fun requireUnfilteredIndexShapes() {
        for (table in StockIndexTableV2.entries) {
            var after: Long? = null
            while (true) {
                val ids = metadata(table, if (after == null) "1=1" else "rowid>?",
                    after?.let { listOf(StockSqlScalar.Number(it)) } ?: emptyList(), "rowid", STOCK_SQL_PAGE_V2)
                for (id in ids) { hydrate(table, id, requireOwnerScope = false); after = id }
                if (ids.size < STOCK_SQL_PAGE_V2) break
            }
        }
    }

    suspend fun estateCurrent(consume: suspend (StockEstateRecordV2) -> Unit) {
        // Every row is typed, even foreign/nonstock rows: a BLOB scope or kind is not hidden by WHERE.
        var after: Long? = null
        while (true) {
            val rows = loan.sql("SELECT rowid,CASE WHEN ${stockAggregateStorageV2()} AND length(payload)<=$MAX_STORED_BYTES THEN 1 ELSE 0 END," +
                "scope,kind,id,revision,schema,payloadHash FROM main.aggregate_state" +
                (if (after == null) "" else " WHERE rowid>?") + " ORDER BY rowid LIMIT $STOCK_SQL_PAGE_V2") { s ->
                after?.let { s.bindLong(1, it) }
                val page = mutableListOf<Pair<Long, Pair<BranchScope, AggregateKey>>>()
                while (s.step()) {
                    stockSqlRequireV2(page.size < STOCK_SQL_PAGE_V2 && s.getLong(1) == 1L)
                    val rowScope = stockScopeStorageKeyV2(s.getText(2))
                    val key = AggregateKey(s.getText(3), DomainId(s.getText(4)))
                    stockSqlRequireV2(Revision(s.getLong(5)).value >= 1)
                    requireProtocolName(s.getText(6)); PayloadDigest(s.getText(7))
                    page += s.getLong(0) to (rowScope to key)
                }
                page
            }
            for ((id, entry) in rows) {
                stockSqlRequireV2(after == null || id > checkNotNull(after)); after = id
                if (entry.first == scope) {
                    val row = aggregate(entry.second) ?: stockSqlUnavailableV2()
                    consume(StockEstateRecordV2.Aggregate(row.key, row.revision, row.schema, row.payload, row.digest))
                }
            }
            if (rows.size < STOCK_SQL_PAGE_V2) break
        }
        after = null
        while (true) {
            val rows = loan.sql("SELECT rowid,CASE WHEN ${stockClaimStorageV2()} THEN 1 ELSE 0 END,scope,namespace,`key`,ownerId " +
                "FROM main.unique_claim" + (if (after == null) "" else " WHERE rowid>?") + " ORDER BY rowid LIMIT $STOCK_SQL_PAGE_V2") { s ->
                after?.let { s.bindLong(1, it) }
                val page = mutableListOf<Triple<Long, BranchScope, StockEstateRecordV2.Claim>>()
                while (s.step()) {
                    stockSqlRequireV2(page.size < STOCK_SQL_PAGE_V2 && s.getLong(1) == 1L)
                    page += Triple(s.getLong(0), stockScopeStorageKeyV2(s.getText(2)),
                        StockEstateRecordV2.Claim(ClaimKey(s.getText(3), s.getText(4)), DomainId(s.getText(5))))
                }
                page
            }
            for ((id, rowScope, row) in rows) {
                stockSqlRequireV2(after == null || id > checkNotNull(after)); after = id
                if (rowScope == scope) {
                    // Exact point lookup also rejects duplicate physical aliases.
                    stockSqlRequireV2(claim(row.key) == row.ownerId); consume(row)
                }
            }
            if (rows.size < STOCK_SQL_PAGE_V2) break
        }
    }

    suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        val metadata = loan.sql("SELECT CASE WHEN ${stockAggregateStorageV2()} THEN 1 ELSE 0 END, COALESCE(length(payload),-1) " +
            "FROM main.aggregate_state WHERE scope=? AND kind=? AND id=?") { statement ->
            bindKey(statement, key)
            if (!statement.step()) null else {
                stockSqlRequireV2(statement.getLong(0) == 1L && statement.getLong(1) in 0L..MAX_STORED_BYTES.toLong())
                val count = statement.getLong(1)
                stockSqlRequireV2(!statement.step())
                count
            }
        } ?: return null
        return loan.sql("SELECT scope,kind,id,revision,schema,payload,payloadHash FROM main.aggregate_state WHERE scope=? AND kind=? AND id=?") { statement ->
            bindKey(statement, key)
            stockSqlRequireV2(statement.step())
            stockSqlRequireV2(statement.getText(0) == scope.storageKey() && statement.getText(1) == key.kind && statement.getText(2) == key.id.value)
            val revision = Revision(statement.getLong(3))
            val schema = statement.getText(4).also(::requireProtocolName)
            val bytes = statement.getBlob(5)
            val digest = PayloadDigest(statement.getText(6))
            stockSqlRequireV2(revision.value >= 1 && bytes.size.toLong() == metadata && sha256(bytes) == digest && !statement.step())
            StoredAggregate(key, revision, schema, StoredBytes(bytes), digest)
        }
    }

    suspend fun claim(key: ClaimKey): DomainId? {
        return loan.sql("SELECT CASE WHEN ${stockTextStorageV2("scope", 110, 110)} AND ${stockTextStorageV2("namespace", 1, 96)} " +
            "AND ${stockTextStorageV2("key", 1, 256)} AND ${stockTextStorageV2("ownerId", 36, 36)} THEN ownerId ELSE NULL END " +
            "FROM main.unique_claim WHERE scope=? AND namespace=? AND `key`=?") { statement ->
            statement.bindText(1, scope.storageKey()); statement.bindText(2, key.namespace); statement.bindText(3, key.key)
            if (!statement.step()) null else {
                stockSqlRequireV2(!statement.isNull(0))
                val owner = DomainId(statement.getText(0))
                stockSqlRequireV2(!statement.step())
                owner
            }
        }
    }

    suspend fun aggregateKeys(kind: String, after: DomainId?): List<AggregateKey> {
        requireProtocolName(kind)
        return loan.sql("SELECT CASE WHEN ${stockTextStorageV2("id", 36, 36)} THEN id ELSE NULL END " +
            "FROM main.aggregate_state WHERE scope=? AND kind=?" + (if (after != null) " AND id>?" else "") +
            " ORDER BY id LIMIT $STOCK_SQL_PAGE_V2") { statement ->
            statement.bindText(1, scope.storageKey()); statement.bindText(2, kind); after?.let { statement.bindText(3, it.value) }
            val result = mutableListOf<AggregateKey>()
            var previous = after?.value
            while (statement.step()) {
                stockSqlRequireV2(result.size < STOCK_SQL_PAGE_V2 && !statement.isNull(0))
                val id = DomainId(statement.getText(0))
                stockSqlRequireV2(previous == null || checkNotNull(previous) < id.value)
                result += AggregateKey(kind, id); previous = id.value
            }
            result
        }
    }

    suspend fun index(key: AggregateKey): StockIndexRecordV2? {
        val table = StockIndexTableV2.forKind(key.kind)
        val ids = metadata(table, "scope=? AND recordKind=? AND recordId=?", listOf(
            StockSqlScalar.Text(scope.storageKey()), StockSqlScalar.Text(key.kind), StockSqlScalar.Text(key.id.value)), "rowid", 2)
        stockSqlRequireV2(ids.size <= 1)
        return ids.singleOrNull()?.let { hydrate(table, it) }
    }

    suspend fun censusPage(table: StockIndexTableV2, after: Long?): List<Pair<Long, StockIndexRecordV2>> {
        val arguments = mutableListOf<StockSqlScalar>(StockSqlScalar.Text(scope.storageKey()))
        if (after != null) arguments += StockSqlScalar.Number(after)
        val ids = metadata(table, "scope=?" + (if (after != null) " AND rowid>?" else ""), arguments, "rowid", STOCK_SQL_PAGE_V2)
        var previous = after
        return ids.map { id ->
            stockSqlRequireV2(previous == null || checkNotNull(previous) < id)
            previous = id
            id to hydrate(table, id)
        }
    }

    suspend fun page(query: StockQueryV2, after: StockPageCursor?): StockIndexPage {
        val selection = selection(query)
        val arguments = mutableListOf<StockSqlScalar>(StockSqlScalar.Text(scope.storageKey()))
        arguments += selection.arguments
        var where = "scope=? AND ${selection.where}"
        if (after != null) {
            stockSqlRequireV2(after.loan === loan && after.query == query && after.order.size == selection.order.size)
            where += " AND (${selection.order.joinToString(",")}) > (${selection.order.joinToString(",") { "?" }})"
            arguments += after.order
        }
        val ids = metadata(selection.table, where, arguments, selection.order.joinToString(","), STOCK_SQL_PAGE_V2)
        val rows = ids.map { hydrate(selection.table, it) }
        var previous = after?.order
        for (row in rows) {
            val order = order(query, row)
            stockSqlRequireV2(previous == null || ordered(checkNotNull(previous), order))
            previous = order
        }
        return StockIndexPage(rows, if (rows.size == STOCK_SQL_PAGE_V2)
            StockPageCursor(loan, query, checkNotNull(previous)) else null)
    }

    /** Only owner-called after complete CAS/claims/invalidation; no commit or second writer. */
    suspend fun replaceIndex(projected: StockIndexRecordV2) {
        val table = StockIndexTableV2.forKind(projected.identity.key.kind)
        val values = fields(projected)
        stockSqlRequireV2(values.size == table.allColumns.size)
        loan.sql("INSERT INTO main.${table.table} (${table.allColumns.joinToString(",") { "`$it`" }}) VALUES (${values.joinToString(",") { "?" }}) " +
            "ON CONFLICT(scope,recordKind,recordId) DO UPDATE SET " +
            table.allColumns.drop(3).joinToString(",") { "`$it`=excluded.`$it`" }) { statement ->
            values.forEachIndexed { index, value -> if (value == null) statement.bindNull(index + 1) else statement.bindStockScalar(index + 1, value) }
            stockSqlRequireV2(!statement.step())
        }
    }

    private suspend fun metadata(table: StockIndexTableV2, where: String, arguments: List<StockSqlScalar>, order: String, limit: Int): List<Long> =
        loan.sql("SELECT rowid,CASE WHEN ${storage(table)} THEN 1 ELSE 0 END FROM main.${table.table} WHERE $where ORDER BY $order LIMIT $limit") { statement ->
            arguments.forEachIndexed { index, value -> statement.bindStockScalar(index + 1, value) }
            val result = mutableListOf<Long>()
            while (statement.step()) {
                stockSqlRequireV2(result.size < limit && statement.getLong(1) == 1L)
                result += statement.getLong(0)
            }
            result
        }

    private suspend fun hydrate(table: StockIndexTableV2, rowId: Long, requireOwnerScope: Boolean = true): StockIndexRecordV2 =
        loan.sql("SELECT ${table.allColumns.joinToString(",") { "`$it`" }} FROM main.${table.table} WHERE rowid=?") { statement ->
            statement.bindLong(1, rowId); stockSqlRequireV2(statement.step())
            val rowScope = stockScopeStorageKeyV2(statement.getText(0))
            stockSqlRequireV2(!requireOwnerScope || rowScope == scope)
            val identity = StockIndexIdentityV2(AggregateKey(statement.getText(1), DomainId(statement.getText(2))),
                Revision(statement.getLong(3)), OperationId(statement.getText(4)), PayloadDigest(statement.getText(5)))
            val result = when (table) {
                StockIndexTableV2.POSITION -> StockIndexRecordV2.Position(identity, statement.id(6), statement.id(7), statement.tag(8),
                    statement.optionalId(9), statement.tag(10), statement.optionalId(11), statement.optionalId(12), statement.optionalLong(13)).also {
                    stockSqlRequireV2(it.expiryOrder == statement.getLong(14))
                }
                StockIndexTableV2.ALLOCATION -> StockIndexRecordV2.Allocation(identity, statement.id(6), statement.optionalId(7),
                    statement.optionalId(8), statement.optionalId(9), statement.tag(10))
                StockIndexTableV2.RESERVATION -> StockIndexRecordV2.Reservation(identity, statement.id(6), statement.id(7), statement.id(8),
                    statement.tag(9), statement.tag(10), statement.optionalLong(11))
                StockIndexTableV2.VALUE -> StockIndexRecordV2.Value(identity, statement.id(6), statement.id(7), statement.optionalId(8),
                    statement.optionalId(9), statement.optionalId(10), statement.optionalId(11), statement.getLong(12))
            }
            stockSqlRequireV2(!statement.step())
            result
        }

    private fun storage(table: StockIndexTableV2): String {
        val common = listOf(stockTextStorageV2("scope", 110, 110), stockTextStorageV2("recordKind", 1, 96),
            stockTextStorageV2("recordId", 36, 36), "typeof(aggregateGeneration)='integer' AND aggregateGeneration>=1",
            stockTextStorageV2("producerOperationId", 36, 36), stockTextStorageV2("payloadHash", 64, 64))
        val specific = when (table) {
            StockIndexTableV2.POSITION -> listOf(stockTextStorageV2("skuId", 36, 36), stockTextStorageV2("warehouseId", 36, 36),
                "typeof(ownership)='integer' AND ownership IN (1,2)", stockOptionalIdStorageV2("ownerId"),
                "typeof(custody)='integer' AND custody BETWEEN 1 AND 3", stockOptionalIdStorageV2("lotId"), stockOptionalIdStorageV2("serialId"),
                stockOptionalTimeStorageV2("expiryMillis"), "typeof(expiryOrder)='integer' AND expiryOrder>=0")
            StockIndexTableV2.ALLOCATION -> listOf(stockTextStorageV2("reservationId", 36, 36), stockOptionalIdStorageV2("allocationId"),
                stockOptionalIdStorageV2("lineId"), stockOptionalIdStorageV2("positionId"), "typeof(holdReason)='integer' AND holdReason BETWEEN 0 AND 5")
            StockIndexTableV2.RESERVATION -> listOf(stockTextStorageV2("orderId", 36, 36), stockTextStorageV2("collectionId", 36, 36),
                stockTextStorageV2("originalActorId", 36, 36), "typeof(status)='integer' AND status BETWEEN 1 AND 4",
                "typeof(exposure)='integer' AND exposure BETWEEN 1 AND 4", stockOptionalTimeStorageV2("expiresAtMillis"))
            StockIndexTableV2.VALUE -> listOf(stockTextStorageV2("poolId", 36, 36), stockTextStorageV2("sourceId", 36, 36),
                stockOptionalIdStorageV2("parentEventId"), stockOptionalIdStorageV2("issueId"), stockOptionalIdStorageV2("allocationId"),
                stockOptionalIdStorageV2("linkedPoolId"), "typeof(eventOrdinal)='integer' AND eventOrdinal>=0")
        }
        return (common + specific).joinToString(" AND ") { "($it)" }
    }

    private data class Selection(val table: StockIndexTableV2, val where: String, val arguments: List<StockSqlScalar>, val order: List<String>)
    private fun selection(query: StockQueryV2): Selection = when (query) {
        StockQueryV2.AllPositions -> Selection(StockIndexTableV2.POSITION, "1=1", emptyList(), listOf("recordId"))
        StockQueryV2.AllReservations -> Selection(StockIndexTableV2.RESERVATION, "1=1", emptyList(), listOf("recordId"))
        is StockQueryV2.SkuPositions -> Selection(StockIndexTableV2.POSITION, "skuId=?", ids(query.skuId), listOf("warehouseId", "recordId"))
        is StockQueryV2.Positions -> Selection(StockIndexTableV2.POSITION, "skuId=? AND warehouseId=?", ids(query.skuId, query.warehouseId),
            listOf("expiryOrder", "lotId", "serialId", "recordId"))
        is StockQueryV2.LotPositions -> Selection(StockIndexTableV2.POSITION, "skuId=? AND lotId=?", ids(query.skuId, query.lotId), listOf("warehouseId", "recordId"))
        is StockQueryV2.PositionMembers -> Selection(StockIndexTableV2.ALLOCATION, "positionId=?", ids(query.positionId), listOf("recordKind", "recordId"))
        is StockQueryV2.ReservationMembers -> Selection(StockIndexTableV2.ALLOCATION, "reservationId=?", ids(query.reservationId), listOf("recordKind", "lineId", "recordId"))
        is StockQueryV2.OrderReservations -> Selection(StockIndexTableV2.RESERVATION, "orderId=?", ids(query.orderId), listOf("recordId"))
        is StockQueryV2.CollectionReservations -> Selection(StockIndexTableV2.RESERVATION, "collectionId=?", ids(query.collectionId), listOf("recordId"))
        is StockQueryV2.PoolValues -> Selection(StockIndexTableV2.VALUE, "poolId=?", ids(query.poolId), listOf("eventOrdinal", "recordKind", "recordId"))
        is StockQueryV2.SourceValues -> Selection(StockIndexTableV2.VALUE, "sourceId=?", ids(query.sourceId), listOf("recordKind", "recordId"))
        is StockQueryV2.IssueValues -> Selection(StockIndexTableV2.VALUE, "issueId=?", ids(query.issueId), listOf("allocationId", "recordKind", "recordId"))
        is StockQueryV2.LinkedValues -> Selection(StockIndexTableV2.VALUE, "linkedPoolId=?", ids(query.linkedPoolId), listOf("poolId", "recordId"))
    }

    private fun order(query: StockQueryV2, row: StockIndexRecordV2): List<StockSqlScalar> {
        val identity = row.identity
        return when (query) {
            StockQueryV2.AllPositions -> { stockSqlRequireV2(row is StockIndexRecordV2.Position); texts(identity.key.id.value) }
            StockQueryV2.AllReservations -> { stockSqlRequireV2(row is StockIndexRecordV2.Reservation); texts(identity.key.id.value) }
            is StockQueryV2.SkuPositions -> (row as? StockIndexRecordV2.Position ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.skuId == query.skuId); texts(it.warehouseId.value, identity.key.id.value)
            }
            is StockQueryV2.Positions -> (row as? StockIndexRecordV2.Position ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.skuId == query.skuId && it.warehouseId == query.warehouseId)
                listOf(StockSqlScalar.Number(it.expiryOrder)) + texts(it.lotId?.value.orEmpty(), it.serialId?.value.orEmpty(), identity.key.id.value)
            }
            is StockQueryV2.LotPositions -> (row as? StockIndexRecordV2.Position ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.skuId == query.skuId && it.lotId == query.lotId); texts(it.warehouseId.value, identity.key.id.value)
            }
            is StockQueryV2.PositionMembers -> (row as? StockIndexRecordV2.Allocation ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.positionId == query.positionId); texts(identity.key.kind, identity.key.id.value)
            }
            is StockQueryV2.ReservationMembers -> (row as? StockIndexRecordV2.Allocation ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.reservationId == query.reservationId); texts(identity.key.kind, it.lineId?.value.orEmpty(), identity.key.id.value)
            }
            is StockQueryV2.OrderReservations -> (row as? StockIndexRecordV2.Reservation ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.orderId == query.orderId); texts(identity.key.id.value)
            }
            is StockQueryV2.CollectionReservations -> (row as? StockIndexRecordV2.Reservation ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.collectionId == query.collectionId); texts(identity.key.id.value)
            }
            is StockQueryV2.PoolValues -> (row as? StockIndexRecordV2.Value ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.poolId == query.poolId); listOf(StockSqlScalar.Number(it.eventOrdinal)) + texts(identity.key.kind, identity.key.id.value)
            }
            is StockQueryV2.SourceValues -> (row as? StockIndexRecordV2.Value ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.sourceId == query.sourceId); texts(identity.key.kind, identity.key.id.value)
            }
            is StockQueryV2.IssueValues -> (row as? StockIndexRecordV2.Value ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.issueId == query.issueId); texts(it.allocationId?.value.orEmpty(), identity.key.kind, identity.key.id.value)
            }
            is StockQueryV2.LinkedValues -> (row as? StockIndexRecordV2.Value ?: stockSqlUnavailableV2()).let {
                stockSqlRequireV2(it.linkedPoolId == query.linkedPoolId); texts(it.poolId.value, identity.key.id.value)
            }
        }
    }

    private fun ordered(left: List<StockSqlScalar>, right: List<StockSqlScalar>): Boolean {
        stockSqlRequireV2(left.size == right.size)
        for (index in left.indices) {
            val a = left[index]; val b = right[index]
            val comparison = when {
                a is StockSqlScalar.Text && b is StockSqlScalar.Text -> a.value.compareTo(b.value)
                a is StockSqlScalar.Number && b is StockSqlScalar.Number -> a.value.compareTo(b.value)
                else -> stockSqlUnavailableV2()
            }
            if (comparison != 0) return comparison < 0
        }
        return false
    }

    private fun fields(row: StockIndexRecordV2): List<StockSqlScalar?> {
        val identity = row.identity
        val common = texts(scope.storageKey(), identity.key.kind, identity.key.id.value) + listOf(StockSqlScalar.Number(identity.generation.value)) +
            texts(identity.producerOperationId.value, identity.payloadHash.value)
        return common + when (row) {
            is StockIndexRecordV2.Position -> ids(row.skuId, row.warehouseId) + listOf(StockSqlScalar.Number(row.ownership.toLong())) +
                texts(row.ownerId?.value.orEmpty()) + listOf(StockSqlScalar.Number(row.custody.toLong())) +
                texts(row.lotId?.value.orEmpty(), row.serialId?.value.orEmpty()) + listOf(row.expiryMillis?.let { StockSqlScalar.Number(it) }, StockSqlScalar.Number(row.expiryOrder))
            is StockIndexRecordV2.Allocation -> texts(row.reservationId.value, row.allocationId?.value.orEmpty(), row.lineId?.value.orEmpty(), row.positionId?.value.orEmpty()) +
                listOf(StockSqlScalar.Number(row.holdReason.toLong()))
            is StockIndexRecordV2.Reservation -> ids(row.orderId, row.collectionId, row.originalActorId) +
                listOf(StockSqlScalar.Number(row.status.toLong()), StockSqlScalar.Number(row.exposure.toLong()), row.expiresAtMillis?.let { StockSqlScalar.Number(it) })
            is StockIndexRecordV2.Value -> texts(row.poolId.value, row.sourceId.value, row.parentEventId?.value.orEmpty(), row.issueId?.value.orEmpty(),
                row.allocationId?.value.orEmpty(), row.linkedPoolId?.value.orEmpty()) + listOf(StockSqlScalar.Number(row.eventOrdinal))
        }
    }

    private fun bindKey(statement: SQLiteStatement, key: AggregateKey) {
        statement.bindText(1, scope.storageKey()); statement.bindText(2, key.kind); statement.bindText(3, key.id.value)
    }
    private fun ids(first: DomainId, second: DomainId? = null, third: DomainId? = null): List<StockSqlScalar> =
        listOfNotNull(first, second, third).map { StockSqlScalar.Text(it.value) }
    private fun texts(vararg values: String): List<StockSqlScalar> = values.map { StockSqlScalar.Text(it) }
    private fun SQLiteStatement.id(index: Int): DomainId = DomainId(getText(index))
    private fun SQLiteStatement.optionalId(index: Int): DomainId? = getText(index).let { if (it.isEmpty()) null else DomainId(it) }
    private fun SQLiteStatement.optionalLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun SQLiteStatement.tag(index: Int): Int { val value = getLong(index); stockSqlRequireV2(value in 0..Int.MAX_VALUE.toLong()); return value.toInt() }
}

internal fun stockTextStorageV2(column: String, minimum: Int, maximum: Int): String =
    "typeof(`$column`)='text' AND length(CAST(`$column` AS BLOB)) BETWEEN $minimum AND $maximum AND instr(CAST(`$column` AS BLOB),x'00')=0"
internal fun stockOptionalIdStorageV2(column: String): String =
    "(${stockTextStorageV2(column, 0, 36)}) AND length(CAST(`$column` AS BLOB)) IN (0,36)"
internal fun stockOptionalTimeStorageV2(column: String): String =
    "(typeof(`$column`)='null' OR (typeof(`$column`)='integer' AND `$column`>=0))"
internal fun stockAggregateStorageV2(): String = listOf(stockTextStorageV2("scope", 110, 110), stockTextStorageV2("kind", 1, 96),
    stockTextStorageV2("id", 36, 36), "typeof(revision)='integer' AND revision>=1", stockTextStorageV2("schema", 1, 96),
    "typeof(payload)='blob'", stockTextStorageV2("payloadHash", 64, 64)).joinToString(" AND ") { "($it)" }

internal fun stockClaimStorageV2(): String = listOf(stockTextStorageV2("scope", 110, 110), stockTextStorageV2("namespace", 1, 96),
    stockTextStorageV2("key", 1, 256), stockTextStorageV2("ownerId", 36, 36)).joinToString(" AND ") { "($it)" }

internal fun stockScopeStorageKeyV2(value: String): BranchScope {
    stockSqlRequireV2(value.length == 110 && value[36] == '/' && value[73] == '/')
    val scope = BranchScope(DomainId(value.substring(0, 36)), DomainId(value.substring(37, 73)), DomainId(value.substring(74, 110)))
    stockSqlRequireV2(scope.storageKey() == value)
    return scope
}

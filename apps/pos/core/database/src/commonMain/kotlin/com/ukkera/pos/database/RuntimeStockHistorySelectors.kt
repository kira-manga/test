package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** ONE full literal decoder shared by the structural census and historical codec join. */
internal fun RuntimeHistoryRowViewV2.runtimeStockIndex(): StockIndexRecordV2 {
    val kind = text("recordKind")
    requireProtocolName(kind); historyShapeV2(runtimeStockProjectionTable(kind) == table)
    val identity = StockIndexIdentityV2(AggregateKey(kind, DomainId(text("recordId"))), Revision(number("aggregateGeneration")),
        OperationId(text("producerOperationId")), PayloadDigest(text("payloadHash")))
    fun id(name: String): DomainId = DomainId(text(name))
    fun optionalId(name: String): DomainId? = text(name).let { if (it.isEmpty()) null else DomainId(it) }
    return when (table) {
        RuntimeHistoryTableV2.STOCK_POSITION -> StockIndexRecordV2.Position(identity, id("skuId"), id("warehouseId"), int("ownership"),
            optionalId("ownerId"), int("custody"), optionalId("lotId"), optionalId("serialId"), optionalNumber("expiryMillis")).also {
            historyShapeV2(it.expiryOrder == number("expiryOrder"))
        }
        RuntimeHistoryTableV2.STOCK_ALLOCATION -> StockIndexRecordV2.Allocation(identity, id("reservationId"), optionalId("allocationId"),
            optionalId("lineId"), optionalId("positionId"), int("holdReason"))
        RuntimeHistoryTableV2.STOCK_RESERVATION -> StockIndexRecordV2.Reservation(identity, id("orderId"), id("collectionId"), id("originalActorId"),
            int("status"), int("exposure"), optionalNumber("expiresAtMillis"))
        RuntimeHistoryTableV2.STOCK_VALUE -> StockIndexRecordV2.Value(identity, id("poolId"), id("sourceId"), optionalId("parentEventId"),
            optionalId("issueId"), optionalId("allocationId"), optionalId("linkedPoolId"), number("eventOrdinal"))
        else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}
internal fun runtimeStockProjectionTable(kind: String): RuntimeHistoryTableV2? = when (kind) {
    StockRelationalKindsV2.POSITION -> RuntimeHistoryTableV2.STOCK_POSITION
    StockRelationalKindsV2.RESERVATION -> RuntimeHistoryTableV2.STOCK_RESERVATION
    StockRelationalKindsV2.ALLOCATION, StockRelationalKindsV2.HOLD -> RuntimeHistoryTableV2.STOCK_ALLOCATION
    StockRelationalKindsV2.ISSUE_SLICE, StockRelationalKindsV2.VALUE_EVENT, StockRelationalKindsV2.VALUE_SLICE,
    StockRelationalKindsV2.VALUE_TARGET, StockRelationalKindsV2.TRANSFER_EDGE -> RuntimeHistoryTableV2.STOCK_VALUE
    else -> null
}

/** Fixed StockRelationalDao query vocabulary and exact SQL ordering. The input is EVERY
 * native-before index row, not a retained member list, selected request census, or hash. */
internal fun runtimeStockMatches(query: StockQueryV2, row: StockIndexRecordV2): Boolean = when (query) {
    StockQueryV2.AllPositions -> row is StockIndexRecordV2.Position
    StockQueryV2.AllReservations -> row is StockIndexRecordV2.Reservation
    is StockQueryV2.SkuPositions -> row is StockIndexRecordV2.Position && row.skuId == query.skuId
    is StockQueryV2.Positions -> row is StockIndexRecordV2.Position && row.skuId == query.skuId && row.warehouseId == query.warehouseId
    is StockQueryV2.LotPositions -> row is StockIndexRecordV2.Position && row.skuId == query.skuId && row.lotId == query.lotId
    is StockQueryV2.PositionMembers -> row is StockIndexRecordV2.Allocation && row.positionId == query.positionId
    is StockQueryV2.ReservationMembers -> row is StockIndexRecordV2.Allocation && row.reservationId == query.reservationId
    is StockQueryV2.OrderReservations -> row is StockIndexRecordV2.Reservation && row.orderId == query.orderId
    is StockQueryV2.CollectionReservations -> row is StockIndexRecordV2.Reservation && row.collectionId == query.collectionId
    is StockQueryV2.PoolValues -> row is StockIndexRecordV2.Value && row.poolId == query.poolId
    is StockQueryV2.SourceValues -> row is StockIndexRecordV2.Value && row.sourceId == query.sourceId
    is StockQueryV2.IssueValues -> row is StockIndexRecordV2.Value && row.issueId == query.issueId
    is StockQueryV2.LinkedValues -> row is StockIndexRecordV2.Value && row.linkedPoolId == query.linkedPoolId
}

/** The same literal tuple used by the guarded selector, without hydrating an estate. */
internal fun runtimeStockOrder(query: StockQueryV2, row: StockIndexRecordV2): List<StockSqlScalar> {
    historyShapeV2(runtimeStockMatches(query, row))
    fun texts(vararg values: String): List<StockSqlScalar> = values.map { StockSqlScalar.Text(it) }
    val identity = row.identity
    return when (query) {
        StockQueryV2.AllPositions, StockQueryV2.AllReservations, is StockQueryV2.OrderReservations, is StockQueryV2.CollectionReservations -> texts(identity.key.id.value)
        is StockQueryV2.SkuPositions, is StockQueryV2.LotPositions -> (row as StockIndexRecordV2.Position).let {
            texts(it.warehouseId.value, identity.key.id.value)
        }
        is StockQueryV2.Positions -> (row as StockIndexRecordV2.Position).let {
            listOf(StockSqlScalar.Number(it.expiryOrder)) + texts(it.lotId?.value.orEmpty(), it.serialId?.value.orEmpty(), identity.key.id.value)
        }
        is StockQueryV2.PositionMembers, is StockQueryV2.SourceValues -> texts(identity.key.kind, identity.key.id.value)
        is StockQueryV2.ReservationMembers -> (row as StockIndexRecordV2.Allocation).let {
            texts(identity.key.kind, it.lineId?.value.orEmpty(), identity.key.id.value)
        }
        is StockQueryV2.PoolValues -> (row as StockIndexRecordV2.Value).let {
            listOf(StockSqlScalar.Number(it.eventOrdinal)) + texts(identity.key.kind, identity.key.id.value)
        }
        is StockQueryV2.IssueValues -> (row as StockIndexRecordV2.Value).let {
            texts(it.allocationId?.value.orEmpty(), identity.key.kind, identity.key.id.value)
        }
        is StockQueryV2.LinkedValues -> (row as StockIndexRecordV2.Value).let { texts(it.poolId.value, identity.key.id.value) }
    }
}

internal fun runtimeStockSelect(query: StockQueryV2, rows: List<StockIndexRecordV2>): List<StockIndexRecordV2> {
    val selected = rows.filter { row -> runtimeStockMatches(query, row) }
    fun compare(left: StockIndexRecordV2, right: StockIndexRecordV2): Int {
        val a = runtimeStockOrder(query, left); val b = runtimeStockOrder(query, right)
        historyShapeV2(a.size == b.size)
        for (index in a.indices) {
            val x = a[index]; val y = b[index]
            val difference = when {
                x is StockSqlScalar.Text && y is StockSqlScalar.Text -> x.value.compareTo(y.value)
                x is StockSqlScalar.Number && y is StockSqlScalar.Number -> x.value.compareTo(y.value)
                else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
            }
            if (difference != 0) return difference
        }
        return 0
    }
    return selected.sortedWith(Comparator { left, right -> compare(left, right) }).also { sorted ->
        for (at in 1 until sorted.size) historyShapeV2(compare(sorted[at - 1], sorted[at]) < 0)
    }
}

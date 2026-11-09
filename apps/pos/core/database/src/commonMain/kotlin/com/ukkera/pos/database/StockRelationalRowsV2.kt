package com.ukkera.pos.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * Derived lookup rows, not an independently writable stock ledger. The owner must register
 * these exact tables together with its physical history schema. Merely compiling these entities
 * neither installs a schema nor admits a v2 writer. Payload/generation/producer equality is the
 * bidirectional StockRelationalProjection census, not just the foreign key below.
 */
@Entity(
    tableName = "stock_position_index_v2",
    primaryKeys = ["scope", "recordKind", "recordId"],
    foreignKeys = [ForeignKey(entity = AggregateRow::class, parentColumns = ["scope", "kind", "id"],
        childColumns = ["scope", "recordKind", "recordId"])],
    indices = [
        Index(name = "stock_position_coordinate_v2", value = ["scope", "skuId", "warehouseId", "ownership", "ownerId", "custody", "lotId", "serialId"], unique = true),
        Index(name = "stock_position_fefo_v2", value = ["scope", "skuId", "warehouseId", "expiryOrder", "lotId", "serialId", "recordId"]),
        Index(name = "stock_position_lot_v2", value = ["scope", "skuId", "lotId", "warehouseId", "recordId"]),
    ],
)
internal data class StockPositionIndexRowV2(
    val scope: String,
    val recordKind: String,
    val recordId: String,
    val aggregateGeneration: Long,
    val producerOperationId: String,
    val payloadHash: String,
    val skuId: String,
    val warehouseId: String,
    val ownership: Int,
    val ownerId: String,
    val custody: Int,
    val lotId: String,
    val serialId: String,
    val expiryMillis: Long?,
    val expiryOrder: Long,
)

@Entity(
    tableName = "stock_allocation_index_v2",
    primaryKeys = ["scope", "recordKind", "recordId"],
    foreignKeys = [ForeignKey(entity = AggregateRow::class, parentColumns = ["scope", "kind", "id"],
        childColumns = ["scope", "recordKind", "recordId"])],
    indices = [
        Index(name = "stock_allocation_position_v2", value = ["scope", "positionId", "recordKind", "recordId"]),
        Index(name = "stock_allocation_reservation_v2", value = ["scope", "reservationId", "recordKind", "lineId", "recordId"]),
    ],
)
internal data class StockAllocationIndexRowV2(
    val scope: String,
    val recordKind: String,
    val recordId: String,
    val aggregateGeneration: Long,
    val producerOperationId: String,
    val payloadHash: String,
    val reservationId: String,
    val allocationId: String,
    val lineId: String,
    val positionId: String,
    val holdReason: Int,
)

@Entity(
    tableName = "stock_reservation_index_v2",
    primaryKeys = ["scope", "recordKind", "recordId"],
    foreignKeys = [ForeignKey(entity = AggregateRow::class, parentColumns = ["scope", "kind", "id"],
        childColumns = ["scope", "recordKind", "recordId"])],
    indices = [
        Index(name = "stock_reservation_order_v2", value = ["scope", "orderId", "recordId"]),
        Index(name = "stock_reservation_collection_v2", value = ["scope", "collectionId", "recordId"]),
        Index(name = "stock_reservation_owner_v2", value = ["scope", "originalActorId", "status", "recordId"]),
    ],
)
internal data class StockReservationIndexRowV2(
    val scope: String,
    val recordKind: String,
    val recordId: String,
    val aggregateGeneration: Long,
    val producerOperationId: String,
    val payloadHash: String,
    val orderId: String,
    val collectionId: String,
    val originalActorId: String,
    val status: Int,
    val exposure: Int,
    val expiresAtMillis: Long?,
)

@Entity(
    tableName = "stock_value_index_v2",
    primaryKeys = ["scope", "recordKind", "recordId"],
    foreignKeys = [ForeignKey(entity = AggregateRow::class, parentColumns = ["scope", "kind", "id"],
        childColumns = ["scope", "recordKind", "recordId"])],
    indices = [
        Index(name = "stock_value_pool_v2", value = ["scope", "poolId", "eventOrdinal", "recordKind", "recordId"]),
        Index(name = "stock_value_source_v2", value = ["scope", "sourceId", "recordKind", "recordId"]),
        Index(name = "stock_value_issue_v2", value = ["scope", "issueId", "allocationId", "recordKind", "recordId"]),
        Index(name = "stock_value_link_v2", value = ["scope", "linkedPoolId", "poolId", "recordId"]),
    ],
)
internal data class StockValueIndexRowV2(
    val scope: String,
    val recordKind: String,
    val recordId: String,
    val aggregateGeneration: Long,
    val producerOperationId: String,
    val payloadHash: String,
    val poolId: String,
    val sourceId: String,
    val parentEventId: String,
    val issueId: String,
    val allocationId: String,
    val linkedPoolId: String,
    val eventOrdinal: Long,
)

/** Actual ordered fragments of ONE operation's journal, not separately committed events. */
@Entity(
    tableName = "journal_fragment_v2",
    primaryKeys = ["scope", "operationId", "ordinal"],
    foreignKeys = [ForeignKey(entity = OperationRow::class, parentColumns = ["scope", "operationId"],
        childColumns = ["scope", "operationId"])],
)
internal data class JournalFragmentRowV2(
    val scope: String,
    val operationId: String,
    val ordinal: Long,
    val byteCount: Long,
    val payload: ByteArray,
    val payloadHash: String,
)

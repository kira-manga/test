package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
public data class LocalCollectionStockQueryV2(public val collectionId: DomainId,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) public val afterHold: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) public val holdLimit: Int = 32) {
    init { require(holdLimit in 1..64 && (afterHold == null || afterHold.length in 1..256)) }
}

/** Full held coordinate. A serial is independent of its lot and supplier/custody pool. */
@Serializable
public data class LocalCollectionStockHoldV2(
    public val reason: String, public val skuId: DomainId?, public val warehouseId: DomainId?,
    public val lotId: DomainId?, public val supplierOwnerId: DomainId?, public val custody: String?, public val serialId: DomainId?,
)

@Serializable
public sealed interface LocalCollectionStockReadResultV2 {
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalCollectionStockReadResultV2
    @Serializable @SerialName("CAPACITY_REQUIRED") public data object CapacityRequired : LocalCollectionStockReadResultV2
    @Serializable @SerialName("NORMALIZED_STOCK_CUT")
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    public data class NormalizedCut(public val collectionId: DomainId, public val reservationId: DomainId,
        public val reservationRevision: Revision, public val status: String, public val allocationCount: Long,
        public val holdCount: Long, public val holds: List<LocalCollectionStockHoldV2>, public val nextAfterHold: String?,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        public val items: List<LocalSalesStockRevisionV1> = emptyList()) : LocalCollectionStockReadResultV2
    @Serializable @SerialName("STOCK_CUT")
    public data class StockCut(public val collectionId: DomainId, public val reservationId: DomainId,
        public val reservationRevision: Revision, public val status: String, public val items: List<LocalSalesStockRevisionV1>,
        public val holds: List<LocalCollectionStockHoldV2>) : LocalCollectionStockReadResultV2
}

public object LocalCollectionStockReadCodecV2 {
    public val QUERY: ReadKey = ReadKey("sales.collection.stock", 2)
    public fun request(query: LocalCollectionStockQueryV2): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalCollectionStockQueryV2): StoredBytes = localSalesEncode(query, LocalCollectionStockQueryV2.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalCollectionStockReadResultV2 = localSalesDecode(body, LocalCollectionStockReadResultV2.serializer())
}

public class LocalCollectionStockReadHandler(private val stock: LocalStockRepository) : MasterReadHandler {
    override public val key: ReadKey get() = LocalCollectionStockReadCodecV2.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalCollectionStockReadCodecV2.encode(localSalesDecode(input, LocalCollectionStockQueryV2.serializer(), 2_048))
    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key) { "sales.context.invalid" }
        val loan = commerceLoan(view, stock)
        val query = localSalesDecode(normalized, LocalCollectionStockQueryV2.serializer(), 2_048)
        var result: LocalCollectionStockReadResultV2 = LocalCollectionStockReadResultV2.NotAvailable
        var revision = Revision(0)
        if (localSalesSourcePresent(loan, LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, query.collectionId),
                listOf(LocalSalesStoreProfile.unique("collection", query.collectionId)))) {
            val cut = readCollection(loan, query.collectionId)
            if (cut.collection.value.collectorId == context.actorId) {
                revision = cut.collection.value.revision
                val collection = cut.collection.value
                if (collection.stockRequirements.normalized) {
                    val header = normalizedCollectionStock(loan, stock, collection)
                    val read = stock.observeRelational(loan)
                    val page = mutableListOf<Pair<String, LocalCollectionStockHoldV2>>()
                    var remaining = 0L
                    var cursorPresent = query.afterHold == null
                    read.holds(header.id) { member ->
                        val hold = member.hold
                        val cursor = hold.reason.name + "/" + (hold.position?.let {
                            com.ukkera.pos.inventory.store.LocalStockRelationalProfileV2.positionKey(it).id.value } ?: "none")
                        if (cursor == query.afterHold) cursorPresent = true
                        if (query.afterHold == null || cursor > query.afterHold) {
                            check(remaining < Long.MAX_VALUE); remaining++
                            val item = LocalCollectionStockHoldV2(hold.reason.name, hold.position?.pool?.skuId, hold.position?.pool?.warehouseId,
                                hold.position?.lotId, (hold.position?.pool?.ownership as? StockOwnership.SupplierOwned)?.supplierId,
                                hold.position?.custody?.name, hold.position?.serialId)
                            page += cursor to item
                            page.sortBy { it.first }
                            if (page.size > query.holdLimit) page.removeAt(page.lastIndex)
                        }
                    }
                    check(cursorPresent) { "sales.stock.stale_hold_cursor" }
                    result = LocalCollectionStockReadResultV2.NormalizedCut(collection.id, header.id, header.revision, header.status.name,
                        header.allocationCount, header.holdCount, page.map { it.second }, if (remaining > page.size) page.last().first else null,
                        checkNotNull(collection.stockRequirements.normalizedLines).map { it.skuId }.distinct().sortedBy { it.value }.map {
                            val item = read.itemVersion(it)
                            LocalSalesStockRevisionV1(item.skuId, checkNotNull(item.storageRevision))
                        })
                    return localSalesRead(context, SecurityAction.ORDER_READ, query.collectionId, revision, context.actorId,
                        "collection.read.stock.v2", result, LocalCollectionStockReadResultV2.serializer())
                }
                val skuIds = collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
                val current = observeBoundedStock { stock.observe(view, skuIds.toList(), listOf(collection.reservationId)) }
                result = if (current == null) LocalCollectionStockReadResultV2.CapacityRequired else {
                    val reservation = requireCollectionReservation(cut, current)
                    LocalCollectionStockReadResultV2.StockCut(collection.id, reservation.reservationId, reservation.revision,
                        reservation.status.name, current.items.filter { it.skuId in skuIds }.sortedBy { it.skuId.value }.map {
                            LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision) { "sales.stock.source" })
                        }, reservation.holds.map { hold ->
                            LocalCollectionStockHoldV2(hold.reason.name, hold.position?.pool?.skuId, hold.position?.pool?.warehouseId,
                                hold.position?.lotId, (hold.position?.pool?.ownership as? StockOwnership.SupplierOwned)?.supplierId,
                                hold.position?.custody?.name, hold.position?.serialId)
                        })
                }
            }
        }
        return localSalesRead(context, SecurityAction.ORDER_READ, query.collectionId, revision, context.actorId,
            "collection.read.stock.v2", result, LocalCollectionStockReadResultV2.serializer())
    }
}

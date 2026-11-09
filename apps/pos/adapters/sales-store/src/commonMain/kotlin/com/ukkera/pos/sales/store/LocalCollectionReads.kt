package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
public sealed interface LocalCollectionQueryV1 {
    /** Exact pinned primary SKU generations for Begin, without guessing item-to-SKU mapping. */
    @Serializable @SerialName("ORDER_STOCK_CUT") public data class OrderStockCut(public val orderId: DomainId) : LocalCollectionQueryV1
    /** Financial recovery remains readable without successful inventory-source revalidation. */
    @Serializable @SerialName("COLLECTION") public data class Collection(public val collectionId: DomainId) : LocalCollectionQueryV1
    @Serializable @SerialName("STOCK_CUT") public data class StockCut(public val collectionId: DomainId) : LocalCollectionQueryV1
    /** An immutable financial document, not kitchen/delivery/fiscal or bank acceptance. */
    @Serializable @SerialName("PAID_SALE") public data class PaidSale(public val saleId: DomainId) : LocalCollectionQueryV1
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class LocalPaymentAttemptViewV1(
    public val attemptId: DomainId, public val intentOperationId: OperationId, public val status: String,
    public val tender: LocalTenderViewV1, public val amount: Money, public val payerReference: String?,
    public val activeRecordingOperationId: OperationId?, public val completedRecordingOperationId: OperationId?,
    public val cashReceived: Money?, public val changeGiven: Money?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val activeRecordingProtocolVersion: Int? = null,
)

@Serializable
public data class LocalCollectionViewV1(
    public val collectionId: DomainId, public val orderId: DomainId, public val sessionId: DomainId,
    public val collectionRevision: Revision, public val orderRevision: Revision, public val sessionRevision: Revision,
    public val quoteRevision: Revision, public val payable: Money, public val collected: Money, public val remainingPayable: Money,
    public val status: String, public val reservationId: DomainId, public val saleId: DomainId?, public val attempts: List<LocalPaymentAttemptViewV1>,
)

@Serializable
public data class LocalCollectionStockHoldV1(
    public val reason: String, public val skuId: DomainId?, public val warehouseId: DomainId?, public val lotId: DomainId?,
)

@Serializable
public data class LocalPaidSaleViewV1(
    public val saleId: DomainId, public val beforeDiscount: Money, public val discount: Money, public val payable: Money,
    public val lines: List<LocalOrderPriceLineV1>, public val components: List<LocalOrderComponentV1>,
    public val tenders: List<LocalRefundObligationViewV1>,
)

@Serializable
public sealed interface LocalCollectionReadResultV1 {
    /** Missing or not visible; not an empty stock/money state or a history-absence proof. */
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalCollectionReadResultV1
    /** Explicit normalized demand, NOT a legacy empty item-generation or availability proof. */
    @Serializable @SerialName("NORMALIZED_ORDER_DEMAND")
    public data class NormalizedOrderDemand(public val orderId: DomainId, public val orderRevision: Revision,
        public val quoteRevision: Revision, public val lines: List<com.ukkera.pos.sales.RetailStockDemandV2>) : LocalCollectionReadResultV1
    @Serializable @SerialName("NORMALIZED_RESERVATION")
    public data class NormalizedReservation(public val collectionId: DomainId, public val reservationId: DomainId,
        public val revision: Revision, public val status: String, public val allocationCount: Long, public val holdCount: Long) : LocalCollectionReadResultV1
    @Serializable @SerialName("ORDER_STOCK_CUT")
    public data class OrderStockCut(public val orderId: DomainId, public val orderRevision: Revision, public val quoteRevision: Revision,
        public val items: List<LocalSalesStockRevisionV1>) : LocalCollectionReadResultV1
    @Serializable @SerialName("COLLECTION") public data class Collection(public val value: LocalCollectionViewV1) : LocalCollectionReadResultV1
    @Serializable @SerialName("STOCK_CUT")
    public data class StockCut(public val collectionId: DomainId, public val reservationId: DomainId, public val reservationRevision: Revision,
        public val status: String, public val items: List<LocalSalesStockRevisionV1>, public val holds: List<LocalCollectionStockHoldV1>) : LocalCollectionReadResultV1
    @Serializable @SerialName("PAID_SALE") public data class PaidSale(public val value: LocalPaidSaleViewV1) : LocalCollectionReadResultV1
}

public object LocalCollectionReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.collection.read", 1)
    public fun request(query: LocalCollectionQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalCollectionQueryV1): StoredBytes = localSalesEncode(query, LocalCollectionQueryV1.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalCollectionReadResultV1 = localSalesDecode(body, LocalCollectionReadResultV1.serializer())
}

public class LocalCollectionReadHandler(private val stock: LocalStockRepository) : MasterReadHandler {
    override public val key: ReadKey get() = LocalCollectionReadCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalCollectionReadCodec.encode(localSalesDecode(input, LocalCollectionQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key) { "sales.context.invalid" }
        val view = commerceLoan(view, stock)
        return when (val query = localSalesDecode(normalized, LocalCollectionQueryV1.serializer(), 2_048)) {
            is LocalCollectionQueryV1.OrderStockCut -> {
                if (!orderSourcePresent(view, query.orderId)) return collectionReadUnavailable(context, query.orderId, SecurityAction.ORDER_READ)
                val cut = readOrder(view, query.orderId)
                val order = cut.order.value
                if (order.ownerId != context.actorId) return collectionReadUnavailable(context, query.orderId, SecurityAction.ORDER_READ)
                if (order.stockRequirements.normalized) {
                    stock.observeRelational(view) // Real installed-profile/census gate, not availability.
                    return localSalesRead(context, SecurityAction.ORDER_READ, order.id, order.revision, context.actorId,
                        "collection.read.normalized_demand", LocalCollectionReadResultV1.NormalizedOrderDemand(order.id, order.revision,
                            order.quote.revision, checkNotNull(order.stockRequirements.normalizedLines)), LocalCollectionReadResultV1.serializer())
                }
                val skuIds = order.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
                val current = observeBoundedStock { stock.observe(view, skuIds.toList()) }
                    ?: return collectionReadUnavailable(context, query.orderId, SecurityAction.ORDER_READ)
                val result = LocalCollectionReadResultV1.OrderStockCut(order.id, order.revision, order.quote.revision,
                    current.items.filter { it.skuId in skuIds }.sortedBy { it.skuId.value }.map {
                        LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision) { "sales.stock.source" })
                    })
                localSalesRead(context, SecurityAction.ORDER_READ, order.id, order.revision, context.actorId,
                    "collection.read.order_stock", result, LocalCollectionReadResultV1.serializer())
            }
            is LocalCollectionQueryV1.Collection -> {
                if (!collectionSourcePresent(view, query.collectionId)) return collectionReadUnavailable(context, query.collectionId, SecurityAction.ORDER_READ)
                val cut = readCollection(view, query.collectionId)
                val value = cut.collection.value
                if (value.collectorId != context.actorId) return collectionReadUnavailable(context, query.collectionId, SecurityAction.ORDER_READ)
                val attempts = value.attempts.map { attempt ->
                    val active = if (attempt.status.unresolved()) activeRecordingOperation(view, SalesRecordingKind.PAYMENT_ATTESTATION,
                        value.id, attempt.id, context.actorId, value.cashSessionId, attempt.originalOperationId) else null
                    LocalPaymentAttemptViewV1(attempt.id, attempt.originalOperationId, attempt.status.name, tenderView(attempt.tender),
                        attempt.amount, attempt.payerReference, active, attempt.payment?.recordingOperationId, attempt.payment?.cashReceived,
                        attempt.payment?.changeGiven, activeRecordingProtocolV2(view, active))
                }
                val projection = LocalCollectionViewV1(value.id, value.orderId, value.cashSessionId, value.revision,
                    cut.order.order.value.revision, cut.cash.session.value.revision, value.quote.revision, value.quote.payable,
                    value.collected, value.remainingPayable, value.status.name, value.reservationId, value.saleId, attempts)
                localSalesRead(context, SecurityAction.ORDER_READ, value.orderId, cut.order.order.value.revision, context.actorId,
                    "collection.read", LocalCollectionReadResultV1.Collection(projection), LocalCollectionReadResultV1.serializer())
            }
            is LocalCollectionQueryV1.StockCut -> {
                if (!collectionSourcePresent(view, query.collectionId)) return collectionReadUnavailable(context, query.collectionId, SecurityAction.ORDER_READ)
                val cut = readCollection(view, query.collectionId)
                val collection = cut.collection.value
                if (collection.collectorId != context.actorId) return collectionReadUnavailable(context, query.collectionId, SecurityAction.ORDER_READ)
                if (collection.stockRequirements.normalized) {
                    val header = normalizedCollectionStock(view, stock, collection)
                    return localSalesRead(context, SecurityAction.ORDER_READ, collection.orderId, cut.order.order.value.revision, context.actorId,
                        "collection.read.normalized_stock", LocalCollectionReadResultV1.NormalizedReservation(collection.id, header.id,
                            header.revision, header.status.name, header.allocationCount, header.holdCount), LocalCollectionReadResultV1.serializer())
                }
                val skuIds = collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
                val current = observeBoundedStock { stock.observe(view, skuIds.toList(), listOf(collection.reservationId)) }
                    ?: return collectionReadUnavailable(context, query.collectionId, SecurityAction.ORDER_READ)
                val reservation = requireCollectionReservation(cut, current)
                // Never erase a serial coordinate to force it into the historical v1 hold DTO.
                if (reservation.holds.any { it.position?.serialId != null }) {
                    return collectionReadUnavailable(context, query.collectionId, SecurityAction.ORDER_READ)
                }
                val result = LocalCollectionReadResultV1.StockCut(collection.id, reservation.reservationId, reservation.revision,
                    reservation.status.name, current.items.filter { it.skuId in skuIds }.sortedBy { it.skuId.value }.map {
                        LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision) { "sales.stock.source" })
                    }, reservation.holds.map { LocalCollectionStockHoldV1(it.reason.name, it.position?.pool?.skuId,
                        it.position?.pool?.warehouseId, it.position?.lotId) })
                localSalesRead(context, SecurityAction.ORDER_READ, collection.orderId, cut.order.order.value.revision, context.actorId,
                    "collection.read.stock", result, LocalCollectionReadResultV1.serializer())
            }
            is LocalCollectionQueryV1.PaidSale -> {
                if (!returnSaleSourcePresent(view, query.saleId))
                    return collectionReadUnavailable(context, query.saleId, SecurityAction.RETURN_RECEIPT_LOOKUP)
                // A normalized recorded sale closes its immutable quote/Finalize independently
                // of today's remaining return rights. Explicit older profiles retain their
                // previous identical-sale/entitlement check; no weaker legacy proof is inferred.
                val original = readReturnSaleSource(view, query.saleId)
                val revision = if (original.priceSources?.value?.normalizedStock == true)
                    original.sale.stored.domainRevision()
                else readEntitlement(view, query.saleId).entitlement.value.revision
                val sale = original.sale.value
                val result = LocalPaidSaleViewV1(sale.id, sale.quote.beforeDiscountPayable, sale.quote.advertisedDiscount, sale.quote.payable,
                    sale.quote.lines.map { LocalOrderPriceLineV1(it.portionId, it.quantity, it.payable) },
                    sale.quote.components.map { LocalOrderComponentV1(it.key, it.portionId, it.chargeId, it.kind.name, it.posted, it.tax.categoryCode) },
                    sale.payments.map { LocalRefundObligationViewV1(it.attemptId, tenderView(it.tender), it.amount) })
                localSalesRead(context, SecurityAction.RETURN_RECEIPT_LOOKUP, sale.id, revision, context.actorId,
                    "collection.read.paid_sale", LocalCollectionReadResultV1.PaidSale(result), LocalCollectionReadResultV1.serializer())
            }
        }
    }
}

private suspend fun collectionSourcePresent(view: CommandReadView, id: DomainId): Boolean =
    localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, id),
        listOf(LocalSalesStoreProfile.unique("collection", id)))

private fun collectionReadUnavailable(context: ReadContext, targetId: DomainId, action: SecurityAction): PreparedRead =
    localSalesRead(context, action, targetId, Revision(0), context.actorId, "collection.read.unavailable",
        LocalCollectionReadResultV1.NotAvailable, LocalCollectionReadResultV1.serializer())

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** Complete stored pricing evidence for one currently authorized retail target. */
@Serializable
public data class LocalManagerOriginalQuote(
    public val target: LocalProfiledOrderV2,
    public val originalCreatorId: DomainId,
    public val quoteSnapshot: String,
)

@Serializable
public data class LocalManagerOriginalQuoteResult(public val orders: List<LocalManagerOriginalQuote>)

/** A separate read identity preserves the existing manager and owner reads. */
public object LocalManagerOriginalQuoteCodec {
    public val QUERY: ReadKey = ReadKey("sales.order.manager-quote", 1)

    public fun request(query: LocalManagerOrderReviewQueryV1): ReadRequest =
        ReadRequest(QUERY, LocalManagerOrderReviewCodec.encode(query))

    public fun decode(body: StoredBytes, query: LocalManagerOrderReviewQueryV1): LocalManagerOriginalQuoteResult =
        localSalesDecode(body, LocalManagerOriginalQuoteResult.serializer()).also { result ->
            require(result.orders.size <= 1 && result.orders.all { it.target.order.orderId == query.orderId }) {
                "sales.manager_quote.invalid"
            }
            result.orders.forEach(::validateManagerOriginalQuote)
        }
}

private fun validateManagerOriginalQuote(entry: LocalManagerOriginalQuote) {
    require(entry.quoteSnapshot.isNotBlank()) { "sales.manager_quote.invalid" }
    val quote = StoredPricingSnapshotCodec.decode(entry.quoteSnapshot)
    val order = entry.target.order
    if (entry.target.stockProfile == LocalOrderStockProfileV2.NORMALIZED) validateNormalizedSelection(order.selection)
    else validateSelection(order.selection)
    // Preserve the original retail snapshot identity binding enforced by readOrder.
    require(quote.lines.isNotEmpty() && quote.snapshotId == order.orderId &&
        quote.revision == order.quoteRevision && quote.currency == order.selection.currency &&
        quote.beforeDiscountPayable == order.beforeDiscount && quote.advertisedDiscount == order.discount &&
        quote.payable == order.payable &&
        quote.lines.map { Triple(it.lineId, it.portionId, it.quantity) }.sortedBy { it.second.value } ==
        order.selection.lines.map { Triple(it.lineId, it.portionId, it.quantity) }.sortedBy { it.second.value } &&
        quote.lines.map { LocalOrderPriceLineV1(it.portionId, it.quantity, it.payable) } == order.lines &&
        quote.components.map { LocalOrderComponentV1(it.key, it.portionId, it.chargeId, it.kind.name, it.posted, it.tax.categoryCode) } ==
        order.components) { "sales.manager_quote.invalid" }
}

/** Same authoritative order/source cut and permission as manager review version 1.
 * Install through CommerceReadHandler with the original stock repository loan. */
public class LocalManagerOriginalQuoteReadHandler : MasterReadHandler {
    override public val key: ReadKey = LocalManagerOriginalQuoteCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes = LocalManagerOrderReviewCodec.encode(
        localSalesDecode(input, LocalManagerOrderReviewQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope) { "sales.context.invalid" }
        val query = localSalesDecode(normalized, LocalManagerOrderReviewQueryV1.serializer(), 2_048)
        val orderKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, query.orderId)
        val contextKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, query.orderId)
        val present = localSalesSourcePresent(view, orderKey,
            listOf(LocalSalesStoreProfile.unique("order", query.orderId),
                LocalSalesStoreProfile.unique("sale_order", query.orderId)),
            listOf(contextKey, commerceLinkKey(orderKey), commerceLinkKey(contextKey)))
        val cut = if (present) readOrder(view, query.orderId) else null
        val result = LocalManagerOriginalQuoteResult(cut?.let {
            val order = it.order.value
            listOf(LocalManagerOriginalQuote(
                LocalProfiledOrderV2(orderView(it), if (order.stockRequirements.normalized)
                    LocalOrderStockProfileV2.NORMALIZED else LocalOrderStockProfileV2.LEGACY_BOUNDED),
                order.originalCreatorId, StoredPricingSnapshotCodec.encode(order.quote)))
        }.orEmpty())
        return localSalesRead(context, SecurityAction.ORDER_REASSIGN, query.orderId,
            cut?.order?.value?.revision ?: Revision(0), context.actorId,
            "order.manager-review", result, LocalManagerOriginalQuoteResult.serializer())
    }
}

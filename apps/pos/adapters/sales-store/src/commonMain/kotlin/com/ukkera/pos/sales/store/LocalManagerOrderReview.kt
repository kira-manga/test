package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** One selected retail target, never a caller-supplied owner or authority. */
@Serializable
public data class LocalManagerOrderReviewQueryV1(public val orderId: DomainId)

/** Separate identity leaves both strict owner-private read contracts unchanged. */
public object LocalManagerOrderReviewCodec {
    public val QUERY: ReadKey = ReadKey("sales.order.manager-review", 1)
    public fun encode(query: LocalManagerOrderReviewQueryV1): StoredBytes =
        localSalesEncode(query, LocalManagerOrderReviewQueryV1.serializer(), 2_048)
    public fun request(query: LocalManagerOrderReviewQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun decode(body: StoredBytes, query: LocalManagerOrderReviewQueryV1): LocalOrderReadResultV2 =
        LocalOrderReadCodecV2.decode(body).also { result ->
            require(result.nextAfter == null && result.orders.size <= 1 &&
                result.orders.all { it.order.orderId == query.orderId }) { "sales.manager_review.invalid" }
        }
}

/** Current target-bound manager disclosure, not reassignment or monetary eligibility.
 * Reuses the same actual order/quote/stock-source joins as ordinary retail reads.
 * Install through CommerceReadHandler with the original repository loan. */
public class LocalManagerOrderReviewReadHandler : MasterReadHandler {
    override public val key: ReadKey = LocalManagerOrderReviewCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes = LocalManagerOrderReviewCodec.encode(
        localSalesDecode(input, LocalManagerOrderReviewQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope) { "sales.context.invalid" }
        val query = localSalesDecode(normalized, LocalManagerOrderReviewQueryV1.serializer(), 2_048)
        val orderKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, query.orderId)
        val contextKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, query.orderId)
        // Surviving normalized producer links are an incomplete cut, not an absent target.
        val present = localSalesSourcePresent(view, orderKey,
            listOf(LocalSalesStoreProfile.unique("order", query.orderId),
                LocalSalesStoreProfile.unique("sale_order", query.orderId)),
            listOf(contextKey, commerceLinkKey(orderKey), commerceLinkKey(contextKey)))
        val cut = if (present) readOrder(view, query.orderId) else null
        val result = LocalOrderReadResultV2(cut?.let {
            listOf(LocalProfiledOrderV2(orderView(it), if (it.order.value.stockRequirements.normalized)
                LocalOrderStockProfileV2.NORMALIZED else LocalOrderStockProfileV2.LEGACY_BOUNDED))
        }.orEmpty(), null)
        return localSalesRead(context, SecurityAction.ORDER_REASSIGN, query.orderId,
            cut?.order?.value?.revision ?: Revision(0), context.actorId,
            "order.manager-review", result, LocalOrderReadResultV2.serializer())
    }
}

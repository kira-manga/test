package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/** Separate presentation prerequisite; this target/profile response is never mutation authority. */
public object LocalManagerReassignmentCodec {
    public val QUERY: ReadKey = ReadKey("sales.order.reassignment", 1)
    public fun request(query: LocalManagerOrderReviewQueryV1): ReadRequest =
        ReadRequest(QUERY, LocalManagerOrderReviewCodec.encode(query))
    public fun decode(body: StoredBytes, query: LocalManagerOrderReviewQueryV1): LocalOrderReadResultV2 =
        LocalManagerOrderReviewCodec.decode(body, query).also { result ->
            require(result.orders.all { it.stockProfile == LocalOrderStockProfileV2.NORMALIZED &&
                it.order.selection.activity in setOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET) &&
                it.order.collectionId == null && it.order.saleId == null && it.order.handoffId == null &&
                it.order.handoffRecipientId == null }) { "sales.reassignment.profile.invalid" }
        }
}

/**
 * A candidate is a literal current source join, not a transfer eligibility Boolean or quote.
 * Both current writer and native original-history receiving checks remain independent.
 */
internal class LocalManagerReassignmentReadHandler : MasterReadHandler {
    override val key: ReadKey = LocalManagerReassignmentCodec.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalManagerOrderReviewCodec.encode(
        localSalesDecode(input, LocalManagerOrderReviewQueryV1.serializer(), 2_048))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope) { "sales.context.invalid" }
        val query = localSalesDecode(normalized, LocalManagerOrderReviewQueryV1.serializer(), 2_048)
        val orderKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, query.orderId)
        val contextKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, query.orderId)
        val present = localSalesSourcePresent(view, orderKey,
            listOf(LocalSalesStoreProfile.unique("order", query.orderId), LocalSalesStoreProfile.unique("sale_order", query.orderId)),
            listOf(contextKey, commerceLinkKey(orderKey), commerceLinkKey(contextKey)))
        val cut = if (present) readOrder(view, query.orderId) else null
        val candidate = cut?.takeIf { it.context.value.selection.activity in setOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET) &&
            it.order.value.stockRequirements.normalized && it.order.value.priceSourceReference != null &&
            it.order.value.collectionId == null && it.order.value.paidSaleId == null && it.order.value.handoff == null }
        val result = LocalOrderReadResultV2(candidate?.let {
            listOf(LocalProfiledOrderV2(orderView(it), LocalOrderStockProfileV2.NORMALIZED))
        }.orEmpty(), null)
        return localSalesRead(context, SecurityAction.ORDER_REASSIGN, query.orderId,
            cut?.order?.value?.revision ?: Revision(0), context.actorId, "order.reassignment.read", result, LocalOrderReadResultV2.serializer())
    }
}

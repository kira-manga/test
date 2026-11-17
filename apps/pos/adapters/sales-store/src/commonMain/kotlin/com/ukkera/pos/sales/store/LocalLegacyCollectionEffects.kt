package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockContribution
import com.ukkera.pos.sales.*
import com.ukkera.pos.types.*

/** Literal business DATA shared by live authorized collection and original replay.
 * Stock projection/composition and the final kernel/native comparisons remain separate. */
internal class LegacyCollectionEffects(val result: LocalSalesResultV1,
    val changes: List<AggregateChange>, val claims: List<ClaimChange>)

internal fun legacyCollectionStockVersions(contribution: LocalStockContribution): List<LocalSalesStockRevisionV1> =
    contribution.items.map { LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision)) }

internal fun legacyCollectionBeginEffects(operationId: OperationId, order: LocalOrderCut,
    cash: LocalCashCut, after: BegunCollection, stock: List<LocalSalesStockRevisionV1>): LegacyCollectionEffects =
    LegacyCollectionEffects(LocalSalesResultV1.Applied(LocalSalesAction.COLLECTION_LOCKED, after.collection.id,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.order.id, after.order.revision),
            LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, after.collection.id, after.collection.revision)),
        after.collection.quote.payable, stock),
        listOf(orderWrite(after.order, order.order.stored), collectionWrite(after.collection, null)) +
            collectionBefore(operationId, listOf("order" to order.order.stored, "cash" to cash.session.stored) + priceSourcesBefore(order)),
        listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("collection", after.collection.id), after.order.id)))

internal fun legacyCollectionFinalizeEffects(cut: LocalCollectionCut, after: FinalizedSale,
    before: List<AggregateChange>, stock: List<LocalSalesStockRevisionV1>,
    workChange: Pair<LocalWorkState, AggregateChange> = changedWork(cut.order.work,
        orderIds = cut.order.work.value.orderIds - cut.order.order.value.id)): LegacyCollectionEffects {
    val order = cut.order.order.value
    val collection = cut.collection.value
    val (work, workWrite) = workChange
    return LegacyCollectionEffects(LocalSalesResultV1.Applied(LocalSalesAction.SALE_FINALIZED, after.sale.id,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, order.id, after.order.revision),
            LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, collection.id, after.collection.revision),
            LocalSalesRevision(LocalSalesStoreProfile.SALE_KIND, after.sale.id, Revision(0)),
            LocalSalesRevision(LocalSalesStoreProfile.ENTITLEMENT_KIND, after.sale.id, after.entitlement.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)), after.sale.quote.payable, stock),
        listOf(orderWrite(after.order, cut.order.order.stored), collectionWrite(after.collection, cut.collection.stored),
            domainWrite(LocalSalesStoreProfile.SALE_KIND, after.sale.id, Revision(0), SalesSnapshotCodec.encodePaidSale(after.sale), null),
            entitlementWrite(after.entitlement, null), workWrite) + before,
        listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale", after.sale.id), order.id),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale_order", order.id), after.sale.id),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_entitlement", after.sale.id), after.sale.id)))
}

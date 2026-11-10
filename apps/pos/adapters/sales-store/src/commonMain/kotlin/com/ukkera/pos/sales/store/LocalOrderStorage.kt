package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** Business selection is retained independently of the existing immutable pricing wire schema. */
@Serializable
internal data class LocalOrderContext(
    val scope: LocalSalesScope, val orderId: DomainId, val revision: Revision,
    val quoteRevision: Revision, val selection: LocalOrderSelectionV1,
)

internal data class LocalOrderCut(
    val order: LocalSalesRow<OwnedOrder>, val context: LocalSalesRow<LocalOrderContext>, val work: LocalSalesRow<LocalWorkState>,
    val priceSources: LocalSalesRow<LocalSalesPriceSourcesV2>?,
    val returnPolicyCapture: LocalSalesRow<LocalSalesReturnPolicyCaptureV2>? = null,
)

internal suspend fun readOrder(view: CommandReadView, id: DomainId): LocalOrderCut =
    readOrderCut(view, id) { row, normalized -> if (normalized) requireCommerceCurrent(view, row) }

/** Both selected current roots must close before any collection cut can return.
 * The private reader never exposes an unchecked normalized order to its callers. */
internal suspend fun readOrderWithCollectionSource(view: CommandReadView, id: DomainId,
    collection: StoredAggregate): LocalOrderCut {
    check(collection.key.kind == LocalSalesStoreProfile.COLLECTION_KIND)
    return readOrderCut(view, id) { row, normalized ->
        if (normalized) requireCommerceCurrent(view, collection, row)
        else requireCommerceCurrent(view, collection)
    }
}

private suspend fun readOrderCut(view: CommandReadView, id: DomainId,
    requireCurrent: suspend (StoredAggregate, Boolean) -> Unit): LocalOrderCut {
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, id))
    val order = SalesSnapshotCodec.decodeOrder(row.text(), SalesSnapshotBinding(SalesSnapshotKind.ORDER, view.scope, id, row.domainRevision()))
    requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("order", id)) == id) { "sales.source.unavailable" }
    val contextRow = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, id))
    val context = decodeLocalSalesSource(contextRow.payload, LocalOrderContext.serializer())
    requireLocalSalesSource(context.scope.value() == view.scope && context.orderId == id && context.revision == contextRow.domainRevision() &&
        context.quoteRevision == order.quote.revision) { "sales.order.context.unavailable" }
    validateLocalSalesSource { requireSelectedQuote(context.selection, order.quote, id, context.quoteRevision, order.stockRequirements.normalized) }
    requireCurrent(row, order.stockRequirements.normalized)
    val priceSources = readPriceSources(view, id, order.quote, order.priceSourceReference,
        if (order.stockRequirements.normalized) null else order.stockRequirements.allocations, order.stockRequirements.normalizedLines)
    val returnPolicyCapture = readReturnPolicyCapture(view, id, order.quote, order.returnPolicySourceReference,
        priceSources?.value?.sources.orEmpty(), order.stockRequirements.normalizedLines)
    val work = LocalSalesStorage.work(view, order.ownerId)
    requireLocalSalesSource((id in work.value.orderIds) == (order.paidSaleId == null)) { "sales.work.unavailable" }
    return LocalOrderCut(LocalSalesRow(row, order), LocalSalesRow(contextRow, context), work, priceSources, returnPolicyCapture)
}

internal fun orderResources(cut: LocalOrderCut): Set<com.ukkera.pos.recovery.RecoveryResourceKey> =
    setOf(orderResource(cut.order.value.id), orderResource(cut.work.value.id)) + priceSourcesResources(cut.order.value.priceSourceReference) +
        returnPolicyCaptureResources(cut.returnPolicyCapture?.value)

internal fun requireSelectedQuote(selection: LocalOrderSelectionV1, quote: PricingSnapshot, quoteId: DomainId, revision: Revision, normalized: Boolean = false) {
    if (normalized) validateNormalizedSelection(selection) else validateSelection(selection)
    requireLocalSalesSource(quote.snapshotId == quoteId && quote.revision == revision && quote.currency == selection.currency &&
        quote.lines.map { Triple(it.lineId, it.portionId, it.quantity) }.sortedBy { it.second.value } ==
        selection.lines.map { Triple(it.lineId, it.portionId, it.quantity) }.sortedBy { it.second.value }) {
        "sales.quote.source_mismatch"
    }
}

internal fun orderWrite(after: OwnedOrder, before: StoredAggregate?): AggregateChange =
    domainWrite(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision, SalesSnapshotCodec.encodeOrder(after), before)

internal fun changedWork(before: LocalSalesRow<LocalWorkState>, orderIds: List<DomainId> = before.value.orderIds,
    returnIds: List<DomainId> = before.value.returnIds,
    pendingClaimIds: List<DomainId> = before.value.pendingClaimIds): Pair<LocalWorkState, AggregateChange> {
    if (listOf(orderIds, returnIds, pendingClaimIds).any { it.size > LocalSalesStoreProfile.MAX_OPEN_WORK }) throw LocalSalesCapacityExceeded()
    check(listOf(orderIds, returnIds, pendingClaimIds).all { it.distinct().size == it.size }) { "sales.work.invalid" }
    val after = before.value.copy(revision = before.value.revision.next(), orderIds = orderIds, returnIds = returnIds, pendingClaimIds = pendingClaimIds)
    return after to rootWrite(LocalSalesStoreProfile.WORK_KIND, after.id, after, LocalWorkState.serializer(), before.stored)
}

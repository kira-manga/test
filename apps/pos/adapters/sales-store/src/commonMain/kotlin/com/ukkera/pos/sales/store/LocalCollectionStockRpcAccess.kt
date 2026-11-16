package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision

/** Explicit nonphysical normalized workflow; manager reassignment has a separate source contract. */
internal fun LocalCollectionStockRequestV2.isLanBusinessCollectionRequest(): Boolean = when (this) {
    is LocalCollectionStockRequestV2.CreateOrder, is LocalCollectionStockRequestV2.ReplaceUnlockedOrder,
    is LocalCollectionStockRequestV2.OfferHandoff, is LocalCollectionStockRequestV2.CancelHandoff,
    is LocalCollectionStockRequestV2.AcceptHandoff, is LocalCollectionStockRequestV2.Begin,
    is LocalCollectionStockRequestV2.Finalize -> true
    is LocalCollectionStockRequestV2.ReassignOrder -> false
}

/**
 * The kernel lends this exact original and current view. A successful result first completes
 * the installed stock/commerce producer and every required predecessor, then reads only the
 * selected immutable AFTER rows through complete EOF traversals. No current order/collector,
 * proposed mutation or full history list can substitute for that source.
 */
internal suspend fun localCollectionStockRpcOriginalRequirements(stock: LocalStockRepository,
    normalized: StoredBytes, original: RetainedOriginalV2, view: CommandReadView,
    context: CommandContext): List<AuthorizationRequirement>? {
    if (context.scope != view.scope || context.key != StockStreamingCommandsV2.COLLECTION ||
        LocalCollectionStockCodecV2.normalize(normalized) != normalized ||
        semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest) return null
    val request = LocalCollectionStockCodecV2.decode(normalized)
    if (!request.isLanBusinessCollectionRequest()) return null
    if (original is RetainedOriginalV2.Legacy) {
        val rejected = original.evidence
        if (rejected.scope != context.scope || rejected.operationId != context.operationId ||
            rejected.originalActorId != context.originalActorId || rejected.command != context.key ||
            rejected.semanticDigest != context.semanticDigest || rejected.normalized != normalized ||
            rejected.nativeOriginalProvenance != null || rejected.committed ||
            rejected.aggregateWrites.isNotEmpty() || rejected.claimChanges.isNotEmpty() || rejected.jobs.isNotEmpty() ||
            sha256(rejected.result.copyBytes()) != rejected.resultHash) return null
        val scalar = when (rejected.code) {
            "precondition.expired" -> rejected.result == StoredBytes("precondition.expired".encodeToByteArray())
            "sales.stock.rule_rejected", "sales.stock.quote_unavailable", "sales.stock.unavailable",
            "sales.stock.capacity_required" -> {
                val result = LocalSalesResultCodec.decode(rejected.result)
                LocalSalesResultCodec.encode(result) == rejected.result && when (rejected.code) {
                    "sales.stock.rule_rejected" -> result is LocalSalesResultV1.Rejected
                    "sales.stock.quote_unavailable", "sales.stock.unavailable" ->
                        result == LocalSalesResultV1.Unavailable(LocalSalesUnavailable.STOCK_REQUIRED)
                    "sales.stock.capacity_required" ->
                        result == LocalSalesResultV1.Unavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
                    else -> false
                }
            }
            else -> false
        }
        if (!scalar) return null
        // These exact scalar refusals disclose no source owner, monetary total or revision.
        // The initiating actor is the private audience, never inferred as the order's owner.
        return listOf(salesAuthorizationRequirement(SecurityAction.ORDER_READ, context.scope,
            context.originalActorId, Revision(0), context.operationId, context.semanticDigest,
            "collection.refusal.read", context.originalActorId))
    }
    val retained = (original as? RetainedOriginalV2.Streamed)?.evidence ?: return null
    val producer = retained.identity.producer
    if (retained.identity.scope != context.scope || producer.operationId != context.operationId ||
        producer.originalActorId != context.originalActorId || producer.command != context.key ||
        producer.semanticDigest != context.semanticDigest || retained.normalized != normalized) return null
    stock.requireRelationalProducer(view, retained)
    val orderId = when (request) {
        is LocalCollectionStockRequestV2.CreateOrder -> request.orderId
        is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> request.orderId
        is LocalCollectionStockRequestV2.OfferHandoff -> request.orderId
        is LocalCollectionStockRequestV2.CancelHandoff -> request.orderId
        is LocalCollectionStockRequestV2.AcceptHandoff -> request.orderId
        is LocalCollectionStockRequestV2.Begin -> request.orderId
        is LocalCollectionStockRequestV2.Finalize -> request.orderId
        is LocalCollectionStockRequestV2.ReassignOrder -> return null
    }
    val row = retained.writeRow(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, orderId)) ?: return null
    check(row.schema == LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ORDER_KIND) &&
        sha256(row.payload.copyBytes()) == row.digest)
    val order = SalesSnapshotCodec.decodeOrder(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, context.scope, orderId, row.domainRevision()))
    check(order.stockRequirements.normalized)
    val action: SecurityAction
    val revision: Revision
    val amount = when (request) {
        is LocalCollectionStockRequestV2.Begin, is LocalCollectionStockRequestV2.Finalize -> {
            val collectionId = when (request) {
                is LocalCollectionStockRequestV2.Begin -> request.collectionId
                is LocalCollectionStockRequestV2.Finalize -> request.collectionId
                else -> error("collection.original.kind")
            }
            val collectionRow = retained.writeRow(LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND,
                collectionId)) ?: return null
            check(collectionRow.schema == LocalSalesStoreProfile.schema(LocalSalesStoreProfile.COLLECTION_KIND) &&
                sha256(collectionRow.payload.copyBytes()) == collectionRow.digest)
            // Original order/session links were validated by the complete producer above.
            val collection = SalesSnapshotCodec.decodeHistoricalCollection(collectionRow.text(),
                SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, context.scope, collectionId, collectionRow.domainRevision()))
            check(collection.orderId == order.id && collection.collectorId == context.originalActorId &&
                order.ownerId == collection.collectorId && collection.quote.payable == order.quote.payable)
            revision = when (request) {
                is LocalCollectionStockRequestV2.Begin -> request.expectedOrderRevision
                is LocalCollectionStockRequestV2.Finalize -> request.expectedOrderRevision
                else -> error("collection.original.kind")
            }
            action = if (request is LocalCollectionStockRequestV2.Begin) SecurityAction.COLLECTION_BEGIN
                else if (collection.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO
                else SecurityAction.COLLECTION_FINALIZE
            collection.quote.payable
        }
        is LocalCollectionStockRequestV2.OfferHandoff -> {
            action = SecurityAction.ORDER_HANDOFF; revision = request.expectedOrderRevision; null
        }
        is LocalCollectionStockRequestV2.CancelHandoff -> {
            action = SecurityAction.ORDER_HANDOFF; revision = request.expectedOrderRevision; null
        }
        is LocalCollectionStockRequestV2.AcceptHandoff -> {
            action = SecurityAction.ORDER_HANDOFF; revision = request.expectedOrderRevision; null
        }
        is LocalCollectionStockRequestV2.CreateOrder, is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> {
            action = SecurityAction.ORDER_READ; revision = order.revision; null
        }
        is LocalCollectionStockRequestV2.ReassignOrder -> return null
    }
    return listOf(salesAuthorizationRequirement(action, context.scope, order.id, revision,
        context.operationId, context.semanticDigest, "collection.original.read", order.ownerId, amount))
}

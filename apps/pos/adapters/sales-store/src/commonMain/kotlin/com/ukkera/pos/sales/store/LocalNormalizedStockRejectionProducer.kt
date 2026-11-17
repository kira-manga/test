package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.store.LocalStockAllocationRequest
import com.ukkera.pos.inventory.store.LocalStockReservationDemand
import com.ukkera.pos.inventory.store.requireNativeRetailInsufficiency
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction

/** Closed Replace/Begin refusal replay. No proposal spool survives rejection, so every
 * prior factual check is made against real native BEFORE sources. A familiar outer code
 * or an unexpected source/capacity failure cannot qualify a rejected transaction. */
internal suspend fun joinNormalizedStockRejectionHistory(command: RuntimeHistoricalCommand,
    quotes: HistoricalNormalizedCommerceSource): Boolean {
    if (command.command != StockStreamingCommandsV2.COLLECTION ||
        command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
    val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
    if (retained.committed || retained.nativeOriginalProvenance != null) return false
    val request = LocalCollectionStockCodecV2.decode(command.normalized)
    val id = when (request) {
        is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> request.orderId
        is LocalCollectionStockRequestV2.Begin -> request.orderId
        else -> return false
    }
    check(LocalCollectionStockCodecV2.encode(request) == command.normalized && retained.scope == command.scope &&
        retained.operationId == command.operationId && retained.command == command.command &&
        retained.originalActorId == command.actorId && retained.semanticDigest == command.semanticDigest &&
        retained.authenticationId == command.authenticationId && retained.policyRevision == command.policyRevision &&
        retained.normalized == command.normalized && retained.recordedAt == command.recordedAt &&
        retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
        semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
    val sources = OrderReassignmentRejectionSources(command)
    val selected = sources.source(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, id)) ?: return false
    val order = sources.orderValue(selected.row)
    val (action, revision, reason) = when (request) {
        is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> Triple(SecurityAction.ORDER_EDIT, request.expectedOrderRevision, "order.replace")
        is LocalCollectionStockRequestV2.Begin -> Triple(SecurityAction.COLLECTION_BEGIN, request.expectedOrderRevision, "collection.begin")
        else -> return false
    }
    command.requireRequirement(salesAuthorizationRequirement(action, command.scope, id, revision, command.operationId,
        command.semanticDigest, reason, order.ownerId, if (request is LocalCollectionStockRequestV2.Begin) order.quote.payable else null))
    if (!sources.requireFreshStockActivation()) return false
    val cut = sources.orderCut(selected, order)
    if (!order.stockRequirements.normalized) return false
    val expected = when (request) {
        is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> {
            if (!sources.acceptsNewWork(order.ownerId)) return false
            val quoted = quotes.quoteBefore(command, request.selection, id, order.quote.revision.next())
            check(quoted.stockRequirements.normalized && quoted.priceSourceProfile == LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2)
            requireSelectedQuote(request.selection, quoted.quote, id, order.quote.revision.next(), true)
            val price = checkNotNull(preparePriceSources(command.scope, command.operationId, quoted))
            check(command.aggregateSource(price.change.key) == null)
            prepareReturnPolicyCapture(command.scope, command.operationId, quoted)?.let {
                check(command.aggregateSource(it.change.key) == null)
            }
            // Orders.replaceUnlockedQuote first checks the revision, before owner,
            // editable/handoff or later transition rules. Earlier quote checks completed.
            if (cut.order.value.revision == request.expectedOrderRevision) return false
            ProposedOutcome.Reject("sales.stock.rule_rejected",
                LocalSalesResultCodec.encode(LocalSalesResultV1.Rejected(SalesRule.REVISION_CONFLICT)))
        }
        is LocalCollectionStockRequestV2.Begin -> {
            sources.openCash(request.sessionId) ?: return false
            if (command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId)) != null ||
                command.claim(LocalSalesStoreProfile.unique("collection", request.collectionId)) != null) return false
            val lines = checkNotNull(order.stockRequirements.normalizedLines).map {
                LocalStockAllocationRequest(it.portionId, it.skuId, it.warehouseId, it.quantity)
            }
            val definitions = quotes.reservationBefore(command, lines)
            val demand = LocalStockReservationDemand(request.reservationId, CollectionBinding(order.id, order.quote.revision,
                request.collectionId, command.actorId, CollectionExposure.UNKNOWN), order.id, order.quote.revision.next(),
                lines, request.reservationExpiresAt)
            if (!requireNativeRetailInsufficiency(command, demand, definitions)) return false
            ProposedOutcome.Reject("sales.stock.unavailable",
                LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.STOCK_REQUIRED)))
        }
        else -> return false
    }
    command.requireNormalizedStockRejection(expected, setOf(orderResource(id), orderResource(order.ownerId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)))
    return true
}

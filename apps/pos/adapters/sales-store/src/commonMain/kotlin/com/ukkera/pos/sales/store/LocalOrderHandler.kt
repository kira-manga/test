package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.LocalMasterRpcRetainedResultAccessProvider
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.HandoffAccepted
import com.ukkera.pos.sales.HandoffCancelled
import com.ukkera.pos.sales.HandoffOffered
import com.ukkera.pos.sales.OrderChanged
import com.ukkera.pos.sales.Orders
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Activated catalog/pricing is constructor-trusted; a request cannot inject a quote or approval. */
public class LocalOrderHandler(private val quoteSource: TransactionSalesQuoteSource) :
    MasterCommandHandler, LocalMasterRpcRetainedResultAccessProvider {
    override public val key: CommandKey get() = LocalSalesStoreProfile.ORDER_COMMAND
    override public val writableKinds: Set<String> = setOf(LocalSalesStoreProfile.ORDER_KIND,
        LocalSalesStoreProfile.ORDER_CONTEXT_KIND, LocalSalesStoreProfile.PRICE_SOURCES_KIND, LocalSalesStoreProfile.WORK_KIND)
    override public val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalOrderCommandCodec.normalize(input)

    override public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.RETAIL_ORDERS) return false
        val request = LocalOrderCommandCodec.decode(normalized)
        return request.retailOrderRpcSelection() != null && LocalOrderCommandCodec.encode(request) == normalized
    }

    override public fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        context: CommandContext): List<AuthorizationRequirement> {
        check(profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS && context.key == key &&
            normalize(normalized) == normalized &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val selection = checkNotNull(LocalOrderCommandCodec.decode(normalized).retailOrderRpcSelection())
        return listOf(retailOrderRpcResultRequirement(selection, context))
    }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalOrderCommandCodec.decode(normalized)) {
            is LocalOrderRequestV1.Create -> create(request, view, context)
            is LocalOrderRequestV1.ReplaceUnlocked -> replace(request, view, context)
            is LocalOrderRequestV1.OfferHandoff -> offerHandoff(request, view, context)
            is LocalOrderRequestV1.CancelHandoff -> cancelHandoff(request, view, context)
            is LocalOrderRequestV1.AcceptHandoff -> acceptHandoff(request, view, context)
        }
    }

    private suspend fun create(request: LocalOrderRequestV1.Create, view: CommandReadView, context: CommandContext): PreparedMutation {
        val work = LocalSalesStorage.work(view, context.originalActorId)
        val acceptingNewWork = acceptsNewWork(view, context.originalActorId)
        val claim = LocalSalesStoreProfile.unique("order", request.orderId)
        val exists = view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, request.orderId)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, request.orderId)) != null || view.claim(claim) != null
        val source = quoteSource.current(view, request.selection)
        check(source == null || source.scope == context.scope) { "sales.catalog.scope" }
        return LocalSalesPrepared(context, SecurityAction.ORDER_CREATE, request.orderId, Revision(0), context.originalActorId,
            "order.create", resources = setOf(orderResource(request.orderId), orderResource(work.value.id), orderResource(DomainId(context.operationId.value))) +
                (source?.recoveryResources ?: emptySet())) { command, now ->
            if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            if (!acceptingNewWork) return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
            if (work.value.orderIds.size >= LocalSalesStoreProfile.MAX_OPEN_WORK) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
            if (source == null) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
            val priced = source.quote(request.orderId, Revision(0), now)
            requireSelectedQuote(request.selection, priced.quote, request.orderId, Revision(0))
            val priceSources = preparePriceSources(context, priced)
            val transition = Orders.create(command, context.scope, request.orderId, priced.quote, priced.stockRequirements, priceSources?.reference)
            val after = transition.value
            check(transition.command == command && transition.versions.isEmpty() && transition.inventoryDependencies.isEmpty() &&
                transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.ORDER, after.id)) && transition.effects == listOf(OrderChanged(after)))
            val orderContext = LocalOrderContext(LocalSalesScope.from(context.scope), after.id, Revision(0), after.quote.revision, request.selection)
            val (afterWork, workWrite) = changedWork(work, orderIds = work.value.orderIds + after.id)
            salesApplied(LocalSalesAction.ORDER_CREATED, after.id,
                listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, afterWork.id, afterWork.revision)) + listOfNotNull(priceSources?.revision),
                listOf(orderWrite(after, null), rootWrite(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, after.id, orderContext, LocalOrderContext.serializer(), null), workWrite) +
                    listOfNotNull(priceSources?.change),
                listOf(ClaimChange.Acquire(claim, after.id)), after.quote.payable)
        }
    }

    private suspend fun replace(request: LocalOrderRequestV1.ReplaceUnlocked, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readOrder(view, request.orderId)
        val acceptingNewWork = acceptsNewWork(view, cut.order.value.ownerId)
        val source = quoteSource.current(view, request.selection)
        check(source == null || source.scope == context.scope) { "sales.catalog.scope" }
        return LocalSalesPrepared(context, SecurityAction.ORDER_EDIT, request.orderId, request.expectedRevision, cut.order.value.ownerId,
            "order.replace", resources = orderResources(cut) + orderResource(DomainId(context.operationId.value)) +
                (source?.recoveryResources ?: emptySet())) { command, now ->
            if (!acceptingNewWork) return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
            if (source == null) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
            val quoteRevision = cut.order.value.quote.revision.next()
            val priced = source.quote(request.orderId, quoteRevision, now)
            requireSelectedQuote(request.selection, priced.quote, request.orderId, quoteRevision)
            val priceSources = preparePriceSources(context, priced)
            val transition = Orders.replaceUnlockedQuote(command, cut.order.value, request.expectedRevision, priced.quote, priced.stockRequirements, priceSources?.reference)
            val after = transition.value
            check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.unique.isEmpty() &&
                transition.inventoryDependencies.isEmpty() && transition.effects == listOf(OrderChanged(after)))
            val orderContext = cut.context.value.copy(revision = cut.context.value.revision.next(), quoteRevision = quoteRevision, selection = request.selection)
            salesApplied(LocalSalesAction.ORDER_EDITED, after.id, listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision)) +
                listOfNotNull(priceSources?.revision),
                listOf(orderWrite(after, cut.order.stored), rootWrite(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, after.id, orderContext,
                    LocalOrderContext.serializer(), cut.context.stored)) + listOfNotNull(priceSources?.change), amount = after.quote.payable)
        }
    }
}

private suspend fun offerHandoff(request: LocalOrderRequestV1.OfferHandoff, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = readOrder(view, request.orderId)
    LocalSalesStorage.custodian(view, request.recipientId)
    val recipientWork = LocalSalesStorage.work(view, request.recipientId)
    val claim = LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)
    val exists = view.claim(claim) != null
    return LocalSalesPrepared(context, SecurityAction.ORDER_HANDOFF, request.orderId, request.expectedRevision, cut.order.value.ownerId,
        "order.handoff.offer", resources = orderResources(cut) + orderResource(recipientWork.value.id)) { command, _ ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val transition = Orders.offerHandoff(command, cut.order.value, request.expectedRevision, request.handoffId, request.recipientId,
            request.evidence.domain(context.originalActorId))
        val after = transition.value
        val handoff = checkNotNull(after.handoff)
        check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.inventoryDependencies.isEmpty() &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.HANDOFF_OFFER, handoff.id)) &&
            transition.effects == listOf(OrderChanged(after), HandoffOffered(handoff)))
        salesApplied(LocalSalesAction.HANDOFF_OFFERED, after.id, listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision)),
            listOf(orderWrite(after, cut.order.stored)), listOf(ClaimChange.Acquire(claim, after.id)))
    }
}

private suspend fun cancelHandoff(request: LocalOrderRequestV1.CancelHandoff, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = readOrder(view, request.orderId)
    return LocalSalesPrepared(context, SecurityAction.ORDER_HANDOFF, request.orderId, request.expectedRevision, cut.order.value.ownerId,
        "order.handoff.cancel", resources = orderResources(cut)) { command, _ ->
        val transition = Orders.cancelHandoff(command, cut.order.value, request.expectedRevision, request.handoffId)
        val after = transition.value
        check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.unique.isEmpty() &&
            transition.inventoryDependencies.isEmpty() && transition.effects == listOf(OrderChanged(after),
                HandoffCancelled(checkNotNull(cut.order.value.handoff), context.originalActorId)))
        salesApplied(LocalSalesAction.HANDOFF_CANCELLED, after.id, listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision)),
            listOf(orderWrite(after, cut.order.stored)))
    }
}

private suspend fun acceptHandoff(request: LocalOrderRequestV1.AcceptHandoff, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = readOrder(view, request.orderId)
    val recipient = LocalSalesStorage.work(view, context.originalActorId)
    val acceptingNewWork = acceptsNewWork(view, context.originalActorId)
    val claim = LocalSalesStoreProfile.unique("handoff_acceptance", request.handoffId)
    val exists = view.claim(claim) != null
    return LocalSalesPrepared(context, SecurityAction.ORDER_HANDOFF, request.orderId, request.expectedRevision, context.originalActorId,
        "order.handoff.accept", resources = orderResources(cut) + orderResource(recipient.value.id)) { command, _ ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (!acceptingNewWork) return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
        if (recipient.value.orderIds.size >= LocalSalesStoreProfile.MAX_OPEN_WORK) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
        val evidence = request.evidence.domain(context.originalActorId)
        val transition = Orders.acceptHandoff(command, cut.order.value, request.expectedRevision, request.handoffId, evidence)
        val after = transition.value
        check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.inventoryDependencies.isEmpty() &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.HANDOFF_ACCEPTANCE, request.handoffId)) &&
            transition.effects == listOf(OrderChanged(after), HandoffAccepted(checkNotNull(cut.order.value.handoff), evidence)))
        check(cut.work.value.id != recipient.value.id && after.id in cut.work.value.orderIds && after.id !in recipient.value.orderIds)
        val (oldOwner, oldWrite) = changedWork(cut.work, orderIds = cut.work.value.orderIds - after.id)
        val (newOwner, newWrite) = changedWork(recipient, orderIds = recipient.value.orderIds + after.id)
        salesApplied(LocalSalesAction.HANDOFF_ACCEPTED, after.id,
            listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision),
                LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, oldOwner.id, oldOwner.revision),
                LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, newOwner.id, newOwner.revision)),
            listOf(orderWrite(after, cut.order.stored), oldWrite, newWrite), listOf(ClaimChange.Acquire(claim, after.id)))
    }
}

/** Closing is a cut: a new owned obligation must not slip in while its owner is counting. */
internal suspend fun acceptsNewWork(view: CommandReadView, ownerId: DomainId): Boolean {
    val assignment = LocalSalesStorage.custodian(view, ownerId)
    val sessionId = assignment.value.activeSessionId ?: return true
    return LocalSalesStorage.cash(view, sessionId).session.value.status == CashSessionStatus.OPEN
}

internal fun orderVersion(cut: LocalOrderCut): SalesVersionCheck =
    SalesVersionCheck(SalesAggregate.ORDER, cut.order.value.id, cut.order.value.revision)

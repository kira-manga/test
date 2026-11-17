package com.ukkera.pos.sales.store

import com.ukkera.pos.database.BusinessOriginalProgram
import com.ukkera.pos.database.NativeBusinessOriginalProgram
import com.ukkera.pos.database.BusinessFactRead
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
import com.ukkera.pos.sales.OwnedOrder
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
        val program = createProgram(request, view, context.scope, context.operationId, context.originalActorId)
        return LocalSalesPrepared(context, SecurityAction.ORDER_CREATE, request.orderId, Revision(0), context.originalActorId,
            "order.create", resources = program.resources) { command, now ->
            program.evaluate(now) { quote, stock, reference ->
                val transition = Orders.create(command, context.scope, request.orderId, quote, stock, reference)
                val after = transition.value
                check(transition.command == command && transition.versions.isEmpty() && transition.inventoryDependencies.isEmpty() &&
                    transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.ORDER, after.id)) && transition.effects == listOf(OrderChanged(after)))
                after
            }
        }
    }

    /** Exact replay of one committed LEGACY Create over core's genuine native BEFORE cut.
     * The SAME read/quote/effect program runs; no SalesCommand or authorization is built.
     * Core compares the original captured requirement, then every literal effect/resource. */
    internal suspend fun requireHistoricalCreate(command: com.ukkera.pos.database.RuntimeHistoricalCommand,
        request: LocalOrderRequestV1.Create) {
        requireOriginalCreate(NativeBusinessOriginalProgram(command), request)
    }

    internal suspend fun requireOriginalCreate(command: BusinessOriginalProgram, request: LocalOrderRequestV1.Create) {
        val program = createProgram(request, command, command.scope, command.operationId, command.actorId)
        command.requireRequirement(com.ukkera.pos.sales.salesAuthorizationRequirement(SecurityAction.ORDER_CREATE, command.scope,
            request.orderId, Revision(0), command.operationId, command.semanticDigest, "order.create", command.actorId, null))
        val outcome = program.evaluate(command.recordedAt) { quote, stock, reference ->
            Orders.createdOrder(command.scope, request.orderId, command.actorId, quote, stock, reference)
        } as? ProposedOutcome.Apply ?: error("Original legacy order effects unavailable")
        command.requireLegacyEffects(outcome, program.resources +
            com.ukkera.pos.recovery.RecoveryResourceKey(com.ukkera.pos.recovery.RecoveryResourceKind.AUTHORIZATION, command.actorId))
    }

    /** Complete original positive program for all five LEGACY order subtypes. */
    internal suspend fun requireOriginal(command: BusinessOriginalProgram, request: LocalOrderRequestV1) {
        if (request is LocalOrderRequestV1.Create) return requireOriginalCreate(command, request)
        val id = when (request) {
            is LocalOrderRequestV1.ReplaceUnlocked -> request.orderId
            is LocalOrderRequestV1.OfferHandoff -> request.orderId
            is LocalOrderRequestV1.CancelHandoff -> request.orderId
            is LocalOrderRequestV1.AcceptHandoff -> request.orderId
            is LocalOrderRequestV1.Create -> error("Create already selected")
        }
        val cut = readLegacyOrderBefore(command, id)
        val outcome: ProposedOutcome
        val resources: Set<com.ukkera.pos.recovery.RecoveryResourceKey>
        suspend fun requirement(action: SecurityAction, revision: Revision, reason: String, owner: DomainId) =
            command.requireRequirement(com.ukkera.pos.sales.salesAuthorizationRequirement(action, command.scope,
                id, revision, command.operationId, command.semanticDigest, reason, owner, null))
        when (request) {
            is LocalOrderRequestV1.ReplaceUnlocked -> {
                val accepting = acceptsNewWork(command, cut.order.value.ownerId)
                val source = (quoteSource as? LegacySalesOriginalQuoteSource
                    ?: error("Original legacy quotation source unavailable")).quoteBefore(command, request.selection)
                check(source == null || source.scope == command.scope)
                resources = orderResources(cut) + orderResource(DomainId(command.operationId.value)) + source?.recoveryResources.orEmpty()
                requirement(SecurityAction.ORDER_EDIT, request.expectedRevision, "order.replace", cut.order.value.ownerId)
                check(accepting && source != null)
                val revision = cut.order.value.quote.revision.next()
                val priced = source.quote(id, revision, command.recordedAt)
                requireSelectedQuote(request.selection, priced.quote, id, revision)
                val prices = preparePriceSources(command.scope, command.operationId, priced)
                val after = Orders.replacedOrder(command.actorId, cut.order.value, request.expectedRevision,
                    priced.quote, priced.stockRequirements, prices?.reference)
                outcome = legacyOrderReplacementOutcome(request, cut, after, prices)
            }
            is LocalOrderRequestV1.OfferHandoff -> {
                LocalSalesStorage.custodian(command, request.recipientId)
                val recipient = LocalSalesStorage.work(command, request.recipientId)
                val claim = LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)
                val duplicate = command.claim(claim) != null
                resources = orderResources(cut) + orderResource(recipient.value.id)
                requirement(SecurityAction.ORDER_HANDOFF, request.expectedRevision, "order.handoff.offer", cut.order.value.ownerId)
                check(!duplicate)
                val after = Orders.offeredHandoffOrder(command.actorId, cut.order.value, request.expectedRevision,
                    request.handoffId, request.recipientId, request.evidence.domain(command.actorId))
                outcome = legacyOrderOfferOutcome(cut, after, claim)
            }
            is LocalOrderRequestV1.CancelHandoff -> {
                resources = orderResources(cut)
                requirement(SecurityAction.ORDER_HANDOFF, request.expectedRevision, "order.handoff.cancel", cut.order.value.ownerId)
                outcome = legacyOrderCancelOutcome(cut, Orders.cancelledHandoffOrder(command.actorId,
                    cut.order.value, request.expectedRevision, request.handoffId))
            }
            is LocalOrderRequestV1.AcceptHandoff -> {
                val recipient = LocalSalesStorage.work(command, command.actorId)
                val accepting = acceptsNewWork(command, command.actorId)
                val claim = LocalSalesStoreProfile.unique("handoff_acceptance", request.handoffId)
                val duplicate = command.claim(claim) != null
                resources = orderResources(cut) + orderResource(recipient.value.id)
                requirement(SecurityAction.ORDER_HANDOFF, request.expectedRevision, "order.handoff.accept", command.actorId)
                check(!duplicate && accepting && recipient.value.orderIds.size < LocalSalesStoreProfile.MAX_OPEN_WORK)
                val after = Orders.acceptedHandoffOrder(command.actorId, cut.order.value, request.expectedRevision,
                    request.handoffId, request.evidence.domain(command.actorId))
                outcome = legacyOrderAcceptanceOutcome(cut, after, recipient, claim)
            }
            is LocalOrderRequestV1.Create -> error("Create already selected")
        }
        command.requireLegacyEffects(outcome as? ProposedOutcome.Apply ?: error("Original order effects required"),
            resources + com.ukkera.pos.recovery.RecoveryResourceKey(com.ukkera.pos.recovery.RecoveryResourceKind.AUTHORIZATION, command.actorId))
    }

    private suspend fun createProgram(request: LocalOrderRequestV1.Create, view: BusinessFactRead,
        scope: com.ukkera.pos.security.BranchScope, operationId: com.ukkera.pos.types.OperationId, actorId: DomainId): LegacyOrderCreateProgram {
        val work = LocalSalesStorage.work(view, actorId)
        val acceptingNewWork = acceptsNewWork(view, actorId)
        val claim = LocalSalesStoreProfile.unique("order", request.orderId)
        val exists = view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, request.orderId)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, request.orderId)) != null || view.claim(claim) != null
        val source = when (view) {
            is CommandReadView -> quoteSource.current(view, request.selection)
            else -> (quoteSource as? LegacySalesOriginalQuoteSource
                ?: error("Original legacy quotation source unavailable")).quoteBefore(view, request.selection)
        }
        check(source == null || source.scope == scope) { "sales.catalog.scope" }
        val resources = setOf(orderResource(request.orderId), orderResource(work.value.id), orderResource(DomainId(operationId.value))) +
            (source?.recoveryResources ?: emptySet())
        return LegacyOrderCreateProgram(resources) { now, create ->
            if (exists) return@LegacyOrderCreateProgram salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            if (!acceptingNewWork) return@LegacyOrderCreateProgram salesRejected(SalesRule.CLOSING_BLOCKED)
            if (work.value.orderIds.size >= LocalSalesStoreProfile.MAX_OPEN_WORK) return@LegacyOrderCreateProgram salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
            if (source == null) return@LegacyOrderCreateProgram salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
            val priced = source.quote(request.orderId, Revision(0), now)
            requireSelectedQuote(request.selection, priced.quote, request.orderId, Revision(0))
            val priceSources = preparePriceSources(scope, operationId, priced)
            val after = create(priced.quote, priced.stockRequirements, priceSources?.reference)
            check(after.id == request.orderId && after.scope == scope && after.ownerId == actorId)
            val orderContext = LocalOrderContext(LocalSalesScope.from(scope), after.id, Revision(0), after.quote.revision, request.selection)
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
            legacyOrderReplacementOutcome(request, cut, after, priceSources)
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
        legacyOrderOfferOutcome(cut, after, claim)
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
        legacyOrderCancelOutcome(cut, after)
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
        legacyOrderAcceptanceOutcome(cut, after, recipient, claim)
    }
}

/** Literal writers shared by live authorized transitions and exact original reducers. */
private fun legacyOrderReplacementOutcome(request: LocalOrderRequestV1.ReplaceUnlocked, cut: LocalOrderCut,
    after: OwnedOrder, prices: LocalPreparedPriceSources?): ProposedOutcome {
    val context = cut.context.value.copy(revision = cut.context.value.revision.next(),
        quoteRevision = after.quote.revision, selection = request.selection)
    return salesApplied(LocalSalesAction.ORDER_EDITED, after.id,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision)) + listOfNotNull(prices?.revision),
        listOf(orderWrite(after, cut.order.stored), rootWrite(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, after.id,
            context, LocalOrderContext.serializer(), cut.context.stored)) + listOfNotNull(prices?.change), amount = after.quote.payable)
}

private fun legacyOrderOfferOutcome(cut: LocalOrderCut, after: OwnedOrder, claim: com.ukkera.pos.database.ClaimKey): ProposedOutcome =
    salesApplied(LocalSalesAction.HANDOFF_OFFERED, after.id,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision)),
        listOf(orderWrite(after, cut.order.stored)), listOf(ClaimChange.Acquire(claim, after.id)))

private fun legacyOrderCancelOutcome(cut: LocalOrderCut, after: OwnedOrder): ProposedOutcome =
    salesApplied(LocalSalesAction.HANDOFF_CANCELLED, after.id,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision)), listOf(orderWrite(after, cut.order.stored)))

private fun legacyOrderAcceptanceOutcome(cut: LocalOrderCut, after: OwnedOrder,
    recipient: LocalSalesRow<LocalWorkState>, claim: com.ukkera.pos.database.ClaimKey): ProposedOutcome {
    check(cut.work.value.id != recipient.value.id && after.id in cut.work.value.orderIds && after.id !in recipient.value.orderIds)
    val (oldOwner, oldWrite) = changedWork(cut.work, orderIds = cut.work.value.orderIds - after.id)
    val (newOwner, newWrite) = changedWork(recipient, orderIds = recipient.value.orderIds + after.id)
    return salesApplied(LocalSalesAction.HANDOFF_ACCEPTED, after.id,
        listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, oldOwner.id, oldOwner.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, newOwner.id, newOwner.revision)),
        listOf(orderWrite(after, cut.order.stored), oldWrite, newWrite), listOf(ClaimChange.Acquire(claim, after.id)))
}

/** One shared legacy Create program. [evaluate] receives the kernel's recorded time and the
 * caller's order construction (an actual authorized transition or the fact-only replay). */
internal class LegacyOrderCreateProgram(
    val resources: Set<com.ukkera.pos.recovery.RecoveryResourceKey>,
    private val body: (com.ukkera.pos.types.UtcInstant, (com.ukkera.pos.pricing.PricingSnapshot, com.ukkera.pos.sales.RetailStockRequirements,
        com.ukkera.pos.sales.SalesPriceSourceReference?) -> com.ukkera.pos.sales.OwnedOrder) -> ProposedOutcome,
) {
    fun evaluate(now: com.ukkera.pos.types.UtcInstant, create: (com.ukkera.pos.pricing.PricingSnapshot, com.ukkera.pos.sales.RetailStockRequirements,
        com.ukkera.pos.sales.SalesPriceSourceReference?) -> com.ukkera.pos.sales.OwnedOrder): ProposedOutcome = body(now, create)
}

/** Closing is a cut: a new owned obligation must not slip in while its owner is counting. */
internal suspend fun acceptsNewWork(view: BusinessFactRead, ownerId: DomainId): Boolean {
    val assignment = LocalSalesStorage.custodian(view, ownerId)
    val sessionId = assignment.value.activeSessionId ?: return true
    return LocalSalesStorage.cash(view, sessionId).session.value.status == CashSessionStatus.OPEN
}

internal fun orderVersion(cut: LocalOrderCut): SalesVersionCheck =
    SalesVersionCheck(SalesAggregate.ORDER, cut.order.value.id, cut.order.value.revision)

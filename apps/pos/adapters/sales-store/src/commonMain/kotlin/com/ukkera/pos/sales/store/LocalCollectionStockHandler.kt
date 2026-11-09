package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/**
 * Real normalized consumer: all quote256 demands are reserved before a collection exists;
 * subsequent issue and the normal sale/order/entitlement/work changes share ONE actual sink.
 * No physical attempt/attestation, native capacity right, gateway registration or migration is
 * supplied here. Those are separate required all-writer joins, not accepting defaults.
 */
public class LocalCollectionStockHandler(private val stock: LocalStockRepository,
    private val authority: LocalStockAuthorityReader, private val quotes: TransactionSalesStockQuoteSource,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) :
    MasterStreamingCommandHandler, LocalMasterRpcRetainedResultAccessProvider {
    override val key: CommandKey = StockStreamingCommandsV2.COLLECTION
    override val writableKinds: Set<String> = StockRelationalKindsV2.all - StockRelationalKindsV2.ACTIVATION + setOf(
        LocalSalesStoreProfile.ORDER_KIND, LocalSalesStoreProfile.ORDER_CONTEXT_KIND, LocalSalesStoreProfile.PRICE_SOURCES_KIND,
        LocalSalesStoreProfile.COLLECTION_KIND, LocalSalesStoreProfile.WORK_KIND, LocalSalesStoreProfile.SALE_KIND,
        LocalSalesStoreProfile.ENTITLEMENT_KIND, LocalSalesStoreProfile.ORDER_REASSIGNMENT_KIND,
        LocalSalesReturnPolicyCaptureCodecV2.KIND, COMMERCE_STOCK_LINK_KIND_V2)
    override val claimNamespaces: Set<String> = LocalStockStoreProfile.inventoryClaimNamespaces + LocalSalesStoreProfile.UNIQUE_NAMESPACE
    override fun normalize(input: StoredBytes): StoredBytes = LocalCollectionStockCodecV2.normalize(input)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.RETAIL_ORDERS) return false
        val request = LocalCollectionStockCodecV2.decode(normalized)
        return request.retailOrderRpcSelection() != null && LocalCollectionStockCodecV2.encode(request) == normalized
    }
    override fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        context: CommandContext): List<AuthorizationRequirement> {
        check(profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS && context.key == key &&
            normalize(normalized) == normalized &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val selection = checkNotNull(LocalCollectionStockCodecV2.decode(normalized).retailOrderRpcSelection())
        return listOf(retailOrderRpcResultRequirement(selection, context))
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedStreamingMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalCollectionStockCodecV2.decode(normalized)
        val admission = when (request) {
            is LocalCollectionStockRequestV2.CreateOrder -> CommerceAdmission(SecurityAction.ORDER_CREATE, request.orderId,
                Revision(0), context.originalActorId, "order.create", null)
            is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> collectionOrderAdmission(view, context, request.orderId,
                request.expectedOrderRevision, SecurityAction.ORDER_EDIT, "order.replace")
            is LocalCollectionStockRequestV2.OfferHandoff -> collectionOrderAdmission(view, context, request.orderId,
                request.expectedOrderRevision, SecurityAction.ORDER_HANDOFF, "order.handoff.offer")
            is LocalCollectionStockRequestV2.CancelHandoff -> collectionOrderAdmission(view, context, request.orderId,
                request.expectedOrderRevision, SecurityAction.ORDER_HANDOFF, "order.handoff.cancel")
            is LocalCollectionStockRequestV2.AcceptHandoff -> collectionOrderAdmission(view, context, request.orderId,
                request.expectedOrderRevision, SecurityAction.ORDER_HANDOFF, "order.handoff.accept", recipient = true)
            is LocalCollectionStockRequestV2.ReassignOrder -> collectionOrderAdmission(view, context, request.orderId,
                request.expectedOrderRevision, SecurityAction.ORDER_REASSIGN, request.reasonCode)
            is LocalCollectionStockRequestV2.Begin -> collectionOrderAdmission(view, context, request.orderId,
                request.expectedOrderRevision, SecurityAction.COLLECTION_BEGIN, "collection.begin")
            is LocalCollectionStockRequestV2.Finalize -> collectionOrderAdmission(view, context, request.orderId,
                request.expectedOrderRevision, SecurityAction.COLLECTION_FINALIZE, "collection.finalize")
        }
        val additionalResources = if (request is LocalCollectionStockRequestV2.ReassignOrder) setOf(
            orderResource(request.recipientId), orderResource(DomainId(context.operationId.value)),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.recipientId)) else emptySet()
        return CommercePrepared(context, admission, additionalResources,
            technicalFailuresAreUncertain = request is LocalCollectionStockRequestV2.ReassignOrder) { command, at, sink ->
            sink.requireContext(view.requireStockRelational(), context)
            val loan = CommerceLoan(view, stock, sink, tenderPolicies)
            val inventory = stock.streamRelational(loan, context, sink, authority, at)
            when (request) {
                is LocalCollectionStockRequestV2.CreateOrder -> create(request, loan, context, command, at, sink, inventory)
                is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> replace(request, loan, context, command, at, sink, inventory)
                is LocalCollectionStockRequestV2.OfferHandoff -> offer(request, loan, context, command, sink, inventory)
                is LocalCollectionStockRequestV2.CancelHandoff -> cancel(request, loan, context, command, sink, inventory)
                is LocalCollectionStockRequestV2.AcceptHandoff -> accept(request, loan, context, command, sink, inventory)
                is LocalCollectionStockRequestV2.ReassignOrder -> reassign(request, loan, context, command, sink, inventory)
                is LocalCollectionStockRequestV2.Begin -> begin(request, loan, context, command, at, sink, inventory)
                is LocalCollectionStockRequestV2.Finalize -> finalize(request, loan, context, command, at, sink, inventory)
            }
        }
    }
    private suspend fun create(request: LocalCollectionStockRequestV2.CreateOrder, view: CommandReadView,
        context: CommandContext, command: SalesCommand, at: UtcInstant, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val work = LocalSalesStorage.work(view, context.originalActorId)
        requireCommerceRule(acceptsNewWork(view, context.originalActorId), SalesRule.CLOSING_BLOCKED)
        requireCommerceRule(view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, request.orderId)) == null &&
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, request.orderId)) == null &&
            view.claim(LocalSalesStoreProfile.unique("order", request.orderId)) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val prepared = quotes.currentNormalized(view, request.selection) ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.UNSUPPORTED_SELECTOR)
        check(prepared.scope == context.scope)
        val quoted = prepared.quote(request.orderId, Revision(0), at)
        check(quoted.stockRequirements.normalized && quoted.priceSourceProfile == LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2)
        requireSelectedQuote(request.selection, quoted.quote, request.orderId, Revision(0), true)
        val source = checkNotNull(preparePriceSources(context, quoted))
        check(view.aggregate(source.change.key) == null)
        val returnPolicy = prepareReturnPolicyCapture(context, quoted)
        returnPolicy?.let { check(view.aggregate(it.change.key) == null) }
        val transition = Orders.create(command, context.scope, request.orderId, quoted.quote, quoted.stockRequirements, source.reference, returnPolicy?.reference)
        check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions.isEmpty() &&
            transition.effects == listOf(OrderChanged(transition.value)) && transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.ORDER, request.orderId)))
        val value = transition.value
        val selected = LocalOrderContext(LocalSalesScope.from(context.scope), value.id, Revision(0), Revision(0), request.selection)
        val (afterWork, workWrite) = changedWork(work, orderIds = work.value.orderIds + value.id)
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(value, null), null, true)
        writes.put(rootWrite(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, value.id, selected, LocalOrderContext.serializer(), null), null)
        writes.put(source.change, null)
        returnPolicy?.let { writes.put(it.change, null) }
        writes.put(workWrite, work.stored)
        writes.claim(ClaimChange.Acquire(LocalSalesStoreProfile.unique("order", value.id), value.id))
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.ORDER_CREATED, value.id, listOf(
            LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, value.id, value.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, afterWork.id, afterWork.revision), source.revision) + listOfNotNull(returnPolicy?.revision), value.quote.payable))
        writes.resources(prepared.recoveryResources + setOf(orderResource(value.id), orderResource(work.value.id), orderResource(source.reference.id),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    private suspend fun replace(request: LocalCollectionStockRequestV2.ReplaceUnlockedOrder, view: CommandReadView,
        context: CommandContext, command: SalesCommand, at: UtcInstant, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val cut = readOrder(view, request.orderId)
        val before = cut.order.value
        requireCommerceRule(before.stockRequirements.normalized, SalesRule.UNSUPPORTED_STOCK_TIMING)
        requireCommerceRule(acceptsNewWork(view, before.ownerId), SalesRule.CLOSING_BLOCKED)
        val prepared = quotes.currentNormalized(view, request.selection) ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.UNSUPPORTED_SELECTOR)
        check(prepared.scope == context.scope)
        val quoteRevision = before.quote.revision.next()
        val quoted = prepared.quote(request.orderId, quoteRevision, at)
        check(quoted.stockRequirements.normalized && quoted.priceSourceProfile == LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2)
        requireSelectedQuote(request.selection, quoted.quote, request.orderId, quoteRevision, true)
        val source = checkNotNull(preparePriceSources(context, quoted))
        check(view.aggregate(source.change.key) == null)
        val returnPolicy = prepareReturnPolicyCapture(context, quoted)
        returnPolicy?.let { check(view.aggregate(it.change.key) == null) }
        val transition = Orders.replaceUnlockedQuote(command, before, request.expectedOrderRevision,
            quoted.quote, quoted.stockRequirements, source.reference, returnPolicy?.reference)
        val after = transition.value
        check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.unique.isEmpty() &&
            transition.inventoryDependencies.isEmpty() && transition.effects == listOf(OrderChanged(after)))
        val selected = cut.context.value.copy(revision = cut.context.value.revision.next(), quoteRevision = quoteRevision, selection = request.selection)
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(after, cut.order.stored), cut.order.stored, true)
        writes.put(rootWrite(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, after.id, selected, LocalOrderContext.serializer(), cut.context.stored), cut.context.stored)
        writes.put(source.change, null)
        returnPolicy?.let { writes.put(it.change, null) }
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.ORDER_EDITED, after.id, listOf(
            LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision), source.revision) + listOfNotNull(returnPolicy?.revision), after.quote.payable))
        writes.resources(prepared.recoveryResources + orderResources(cut) + orderResource(source.reference.id) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    private suspend fun offer(request: LocalCollectionStockRequestV2.OfferHandoff, view: CommandReadView,
        context: CommandContext, command: SalesCommand, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val cut = readOrder(view, request.orderId)
        requireCommerceRule(cut.order.value.stockRequirements.normalized, SalesRule.UNSUPPORTED_STOCK_TIMING)
        LocalSalesStorage.custodian(view, request.recipientId)
        val recipient = LocalSalesStorage.work(view, request.recipientId)
        val claim = LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)
        requireCommerceRule(view.claim(claim) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val transition = Orders.offerHandoff(command, cut.order.value, request.expectedOrderRevision,
            request.handoffId, request.recipientId, request.evidence.domain(context.originalActorId))
        val after = transition.value
        val handoff = checkNotNull(after.handoff)
        check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.inventoryDependencies.isEmpty() &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.HANDOFF_OFFER, request.handoffId)) &&
            transition.effects == listOf(OrderChanged(after), HandoffOffered(handoff)))
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(after, cut.order.stored), cut.order.stored, true)
        writes.claim(ClaimChange.Acquire(claim, after.id))
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.HANDOFF_OFFERED, after.id,
            listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision))))
        writes.resources(orderResources(cut) + orderResource(recipient.value.id) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    private suspend fun cancel(request: LocalCollectionStockRequestV2.CancelHandoff, view: CommandReadView,
        context: CommandContext, command: SalesCommand, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val cut = readOrder(view, request.orderId)
        requireCommerceRule(cut.order.value.stockRequirements.normalized, SalesRule.UNSUPPORTED_STOCK_TIMING)
        requireCommerceRule(view.claim(LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)) == request.orderId, SalesRule.HANDOFF_CONFLICT)
        val transition = Orders.cancelHandoff(command, cut.order.value, request.expectedOrderRevision, request.handoffId)
        val after = transition.value
        check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.inventoryDependencies.isEmpty() &&
            transition.unique.isEmpty() && transition.effects == listOf(OrderChanged(after),
                HandoffCancelled(checkNotNull(cut.order.value.handoff), context.originalActorId)))
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(after, cut.order.stored), cut.order.stored, true)
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.HANDOFF_CANCELLED, after.id,
            listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision))))
        writes.resources(orderResources(cut) + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    private suspend fun accept(request: LocalCollectionStockRequestV2.AcceptHandoff, view: CommandReadView,
        context: CommandContext, command: SalesCommand, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val cut = readOrder(view, request.orderId)
        requireCommerceRule(cut.order.value.stockRequirements.normalized, SalesRule.UNSUPPORTED_STOCK_TIMING)
        val recipient = LocalSalesStorage.work(view, context.originalActorId)
        requireCommerceRule(acceptsNewWork(view, context.originalActorId), SalesRule.CLOSING_BLOCKED)
        requireCommerceRule(view.claim(LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)) == request.orderId, SalesRule.HANDOFF_CONFLICT)
        val claim = LocalSalesStoreProfile.unique("handoff_acceptance", request.handoffId)
        requireCommerceRule(view.claim(claim) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (recipient.value.orderIds.size >= LocalSalesStoreProfile.MAX_OPEN_WORK) return commerceCapacity()
        val evidence = request.evidence.domain(context.originalActorId)
        val transition = Orders.acceptHandoff(command, cut.order.value, request.expectedOrderRevision, request.handoffId, evidence)
        val after = transition.value
        check(transition.command == command && transition.versions == listOf(orderVersion(cut)) && transition.inventoryDependencies.isEmpty() &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.HANDOFF_ACCEPTANCE, request.handoffId)) &&
            transition.effects == listOf(OrderChanged(after), HandoffAccepted(checkNotNull(cut.order.value.handoff), evidence)))
        check(cut.work.value.id != recipient.value.id && after.id in cut.work.value.orderIds && after.id !in recipient.value.orderIds)
        val (oldOwner, oldWrite) = changedWork(cut.work, orderIds = cut.work.value.orderIds - after.id)
        val (newOwner, newWrite) = changedWork(recipient, orderIds = recipient.value.orderIds + after.id)
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(after, cut.order.stored), cut.order.stored, true)
        writes.put(oldWrite, cut.work.stored)
        writes.put(newWrite, recipient.stored)
        writes.claim(ClaimChange.Acquire(claim, after.id))
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.HANDOFF_ACCEPTED, after.id, listOf(
            LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, oldOwner.id, oldOwner.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, newOwner.id, newOwner.revision))))
        writes.resources(orderResources(cut) + orderResource(recipient.value.id) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    /** Current receiving authority is checked inside the same authoritative writer loan. */
    private suspend fun reassign(request: LocalCollectionStockRequestV2.ReassignOrder, view: CommerceLoan,
        context: CommandContext, command: SalesCommand, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val cut = readOrder(view, request.orderId)
        val before = cut.order.value
        if (cut.context.value.selection.activity !in setOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET))
            return commerceReject("sales.stock.reassignment.unsupported_activity",
                LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.UNSUPPORTED_ACTIVITY)))
        requireCommerceRule(before.revision == request.expectedOrderRevision, SalesRule.REVISION_CONFLICT)
        requireCommerceRule(before.ownerId == request.previousOwnerId, SalesRule.OWNER_REQUIRED)
        requireCommerceRule(before.stockRequirements.normalized && before.priceSourceReference != null, SalesRule.UNSUPPORTED_STOCK_TIMING)
        requireCommerceRule(request.recipientId != before.ownerId && before.handoff == null, SalesRule.HANDOFF_CONFLICT)
        requireCommerceRule(before.collectionId == null, SalesRule.PAYABLE_LOCKED)
        requireCommerceRule(before.paidSaleId == null, SalesRule.IMMUTABLE_RECORD)
        // Protected current account/credential/configuration and exact target grant/deny check.
        // Its revision projection is not persisted as a substitute for original-before proof.
        val receiving = TransactionLocalHandoffRecipients.current(view.handoffBorrowedView(), before.id, request.recipientId)
        requireCommerceRule(receiving != null && receiving.actorId == request.recipientId, SalesRule.PERMISSION_DENIED)
        val recipient = LocalSalesStorage.work(view, request.recipientId)
        requireCommerceRule(acceptsNewWork(view, before.ownerId) && acceptsNewWork(view, request.recipientId), SalesRule.CLOSING_BLOCKED)
        if (recipient.value.orderIds.size >= LocalSalesStoreProfile.MAX_OPEN_WORK) return commerceCapacity()
        val auditId = DomainId(context.operationId.value)
        val auditKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_REASSIGNMENT_KIND, auditId)
        requireCommerceRule(view.aggregate(auditKey) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val evidence = request.evidence.domain(context.originalActorId)
        val transition = Orders.reassign(command, before, request.expectedOrderRevision, request.previousOwnerId, request.recipientId, evidence)
        val after = transition.value
        val event = OrderReassigned(context.scope, context.operationId, before.id, before.revision, after.revision,
            context.originalActorId, request.previousOwnerId, request.recipientId, request.reasonCode, evidence, command.recordedAt)
        check(transition.command == command && command.reasonCode == request.reasonCode && transition.versions == listOf(orderVersion(cut)) &&
            transition.unique.isEmpty() && transition.inventoryDependencies.isEmpty() && transition.effects == listOf(OrderChanged(after), event))
        check(cut.work.value.id != recipient.value.id && before.id in cut.work.value.orderIds && before.id !in recipient.value.orderIds)
        val (oldOwner, oldWrite) = changedWork(cut.work, orderIds = cut.work.value.orderIds - after.id)
        val (newOwner, newWrite) = changedWork(recipient, orderIds = recipient.value.orderIds + after.id)
        val audit = orderReassignmentAudit(event, cut.work.value, recipient.value)
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(after, cut.order.stored), cut.order.stored, true)
        writes.put(oldWrite, cut.work.stored)
        writes.put(newWrite, recipient.stored)
        writes.put(rootWrite(auditKey.kind, auditId, audit, LocalOrderReassignmentAuditV1.serializer(), null), null)
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.ORDER_REASSIGNED, after.id, listOf(
            LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.id, after.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, oldOwner.id, oldOwner.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, newOwner.id, newOwner.revision),
            LocalSalesRevision(auditKey.kind, auditId, audit.revision))))
        writes.resources(orderResources(cut) + setOf(orderResource(recipient.value.id), orderResource(auditId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.recipientId)))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    private suspend fun begin(request: LocalCollectionStockRequestV2.Begin, view: CommandReadView,
        context: CommandContext, command: SalesCommand, at: UtcInstant, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val order = readOrder(view, request.orderId)
        val cash = LocalSalesStorage.cash(view, request.sessionId)
        requireCommerceRule(order.order.value.stockRequirements.normalized, SalesRule.UNSUPPORTED_STOCK_TIMING)
        requireCommerceRule(view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId)) == null &&
            view.claim(LocalSalesStoreProfile.unique("collection", request.collectionId)) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val value = order.order.value
        val demand = LocalStockReservationDemand(request.reservationId, CollectionBinding(value.id, value.quote.revision,
            request.collectionId, context.originalActorId, CollectionExposure.UNKNOWN), value.id, value.quote.revision.next(),
            checkNotNull(value.stockRequirements.normalizedLines).map { LocalStockAllocationRequest(it.portionId, it.skuId, it.warehouseId, it.quantity) },
            request.reservationExpiresAt)
        val authorityResources = quotes.observeReservationAuthority(view, demand.lines)
        inventory.reserveRetail(demand)
        val reserved = inventory.plannedReservation(request.reservationId)
        val transition = Collections.beginNormalized(command, value, request.expectedOrderRevision, cash.session.value,
            request.expectedSessionRevision, request.collectionId, reserved)
        val after = transition.value
        check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == listOf(orderVersion(order), SalesVersionCheck(SalesAggregate.CASH_SESSION, cash.session.value.id, cash.session.value.revision)) &&
            transition.effects == listOf(OrderChanged(after.order), CollectionChanged(after.collection)) &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.COLLECTION, request.collectionId)))
        SalesSnapshotLinks.requireCollection(after.collection, after.order, cash.session.value)
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(after.order, order.order.stored), order.order.stored, true)
        writes.put(collectionWrite(after.collection, null), null, true)
        writes.claim(ClaimChange.Acquire(LocalSalesStoreProfile.unique("collection", request.collectionId), request.orderId))
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.COLLECTION_LOCKED, after.collection.id, listOf(
            LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.order.id, after.order.revision),
            LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, after.collection.id, after.collection.revision)), after.collection.quote.payable))
        writes.resources(authorityResources + orderResources(order) + cashResources(cash) + setOf(orderResource(after.collection.id),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    private suspend fun finalize(request: LocalCollectionStockRequestV2.Finalize, view: CommandReadView,
        context: CommandContext, command: SalesCommand, at: UtcInstant, sink: StreamedCommandSink,
        inventory: LocalStockRelationalTransaction): StreamedMutationDecision {
        val cut = readCollection(view, request.collectionId)
        val value = cut.collection.value
        requireCommerceRule(value.orderId == request.orderId && value.stockRequirements.normalized, SalesRule.PAYABLE_LOCKED)
        // Money coverage is proved BEFORE trying stock; no physical observation is minted here.
        requireCommerceRule(!value.unresolved && value.collected == value.quote.payable, SalesRule.INSUFFICIENT_COVERAGE)
        for ((kind, id) in listOf(LocalSalesStoreProfile.SALE_KIND to request.saleId, LocalSalesStoreProfile.ENTITLEMENT_KIND to request.saleId))
            requireCommerceRule(view.aggregate(LocalSalesStoreProfile.key(kind, id)) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val claims = listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale", request.saleId), request.orderId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale_order", request.orderId), request.saleId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_entitlement", request.saleId), request.saleId))
        for (claim in claims) requireCommerceRule(view.claim(claim.key) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val read = inventory.observations() // Same sink captures the complete immutable BEFORE membership.
        val before = checkNotNull(read.reservation(value.reservationId))
        requireNormalizedCollectionReservation(value, before) { consume -> read.allocations(value.reservationId, consume) }
        inventory.issueReserved(LocalStockReservedIssue(value.reservationId, request.reservationRevision, request.issueId, ValueIssuePurpose.RETAIL))
        val issued = inventory.plannedReservation(value.reservationId)
        val issue = inventory.plannedIssue(request.issueId)
        check(issue.purpose == ValueIssuePurpose.RETAIL && issue.reservationId == value.reservationId && issue.binding == before.binding)
        val transition = Collections.finalizeNormalized(command, cut.order.order.value, request.expectedOrderRevision,
            value, request.expectedCollectionRevision, request.saleId, issued, request.issueId)
        val after = transition.value
        check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.effects == listOf(OrderChanged(after.order),
            CollectionChanged(after.collection), SalePosted(after.sale), ReceiptEntitlementChanged(after.entitlement)) &&
            transition.versions == listOf(orderVersion(cut.order), SalesVersionCheck(SalesAggregate.COLLECTION, value.id, value.revision)) &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.SALE, request.saleId), SalesUniqueCheck(SalesUniqueKind.SALE_ORDER, request.orderId),
                SalesUniqueCheck(SalesUniqueKind.RETURN_ENTITLEMENT, request.saleId)))
        val (work, workWrite) = changedWork(cut.order.work, orderIds = cut.order.work.value.orderIds - request.orderId)
        val writes = CommerceWrites(view, context, sink)
        writes.put(orderWrite(after.order, cut.order.order.stored), cut.order.order.stored, true)
        writes.put(collectionWrite(after.collection, cut.collection.stored), cut.collection.stored, true)
        writes.put(domainWrite(LocalSalesStoreProfile.SALE_KIND, after.sale.id, Revision(0), SalesSnapshotCodec.encodePaidSale(after.sale), null), null, true)
        writes.put(domainWrite(LocalSalesStoreProfile.ENTITLEMENT_KIND, after.entitlement.sale.id, after.entitlement.revision,
            SalesSnapshotCodec.encodeReceiptEntitlement(after.entitlement), null), null, true)
        writes.put(workWrite, cut.order.work.stored)
        for (claim in claims) writes.claim(claim)
        val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.SALE_FINALIZED, after.sale.id, listOf(
            LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.order.id, after.order.revision),
            LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, after.collection.id, after.collection.revision),
            LocalSalesRevision(LocalSalesStoreProfile.SALE_KIND, after.sale.id, Revision(0)),
            LocalSalesRevision(LocalSalesStoreProfile.ENTITLEMENT_KIND, after.sale.id, after.entitlement.revision),
            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)), after.sale.quote.payable))
        writes.resources(collectionResources(cut) + entitlementResource(after.sale.id) + orderResource(after.sale.id) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
        emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
}

internal data class CommerceAdmission(val action: SecurityAction, val target: DomainId, val revision: Revision,
    val owner: DomainId, val reason: String, val amount: Money?)
private suspend fun collectionOrderAdmission(view: CommandReadView, context: CommandContext, id: DomainId,
    revision: Revision, action: SecurityAction, reason: String, recipient: Boolean = false): CommerceAdmission {
    val row = view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, id))
    val value = row?.let { SalesSnapshotCodec.decodeOrder(it.text(), SalesSnapshotBinding(SalesSnapshotKind.ORDER, view.scope, id, it.domainRevision())) }
    return CommerceAdmission(if (action == SecurityAction.COLLECTION_FINALIZE && value?.quote?.payable?.minor == 0L)
        SecurityAction.COLLECTION_FINALIZE_ZERO else action, id, revision,
        if (recipient) context.originalActorId else value?.ownerId ?: context.originalActorId, reason,
        if (action == SecurityAction.COLLECTION_BEGIN || action == SecurityAction.COLLECTION_FINALIZE) value?.quote?.payable else null)
}
internal class CommercePrepared(private val context: CommandContext, admission: CommerceAdmission,
    additionalResources: Set<RecoveryResourceKey>,
    private val technicalFailuresAreUncertain: Boolean,
    private val proposal: suspend (SalesCommand, UtcInstant, StreamedCommandSink) -> StreamedMutationDecision) : PreparedStreamingMutation() {
    private val reason = admission.reason
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(admission.action, context.scope, admission.target,
        admission.revision, context.operationId, context.semanticDigest, reason, admission.owner, admission.amount)
    override val affectedResources: Set<RecoveryResourceKey> = setOf(orderResource(admission.target), orderResource(admission.owner),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)) + additionalResources.toSet()
    override val allowsActionApproval: Boolean = false
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)
    override suspend fun propose(authorization: AuthorizedAction, recordedAt: UtcInstant, sink: StreamedCommandSink): StreamedMutationDecision {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
        return try { proposal(SalesCommand(context.operationId, authorization, recordedAt.value, context.semanticDigest, reason), recordedAt, sink) }
        catch (failure: SalesRuleViolation) { commerceReject("sales.stock.rule_rejected", LocalSalesResultCodec.encode(LocalSalesResultV1.Rejected(failure.rule))) }
        // A discarded reassignment spool cannot prove a technical failure's original
        // phase on restart. Let the kernel roll back/defer it rather than record an
        // invented business refusal. Explicit recipient work-limit refusals are
        // returned by the proposal and ordinary rule refusals remain deterministic.
        catch (failure: LocalSalesCapacityExceeded) {
            if (technicalFailuresAreUncertain) throw failure
            commerceCapacity()
        }
        catch (failure: LocalSalesWireCapacityExceeded) {
            if (technicalFailuresAreUncertain) throw failure
            commerceCapacity()
        }
        catch (failure: FoodCapacityExceeded) {
            if (technicalFailuresAreUncertain) throw failure
            commerceCapacity()
        }
        catch (failure: LocalSalesQuoteUnavailable) {
            if (technicalFailuresAreUncertain) throw failure
            if (failure.reason == LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED) commerceCapacity()
            else commerceReject("sales.stock.quote_unavailable", LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.STOCK_REQUIRED)))
        }
        catch (failure: LocalStockRejected) {
            if (technicalFailuresAreUncertain) throw failure
            when (failure.failure) {
                LocalStockFailure.BOUND_EXCEEDED -> commerceCapacity()
                LocalStockFailure.INSUFFICIENT_STOCK, LocalStockFailure.UNSAFE_STOCK -> commerceReject("sales.stock.unavailable",
                    LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.STOCK_REQUIRED)))
                else -> throw failure
            }
        }
        // IO, damaged originals, arithmetic and unknown storage states retain kernel uncertainty.
    }
}
internal fun requireCommerceRule(value: Boolean, rule: SalesRule) { if (!value) throw SalesRuleViolation(rule) }
internal fun commerceReject(code: String, bytes: StoredBytes): StreamedMutationDecision.Reject = StreamedMutationDecision.Reject(code, bytes)
private fun commerceCapacity(): StreamedMutationDecision.Reject = commerceReject("sales.stock.capacity_required",
    LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)))

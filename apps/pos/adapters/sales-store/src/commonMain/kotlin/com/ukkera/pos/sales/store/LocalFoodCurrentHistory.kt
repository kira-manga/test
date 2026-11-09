package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentState
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentTaskIdentity
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentReservationIdentity
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentCancellationIdentity
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentTenderIdentity
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentWriter
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.inventory.store.requireCurrentFoodIssueSources
import com.ukkera.pos.inventory.store.requireCurrentFoodReservationSources
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodCollectionStatus
import com.ukkera.pos.sales.FoodStockOrigin
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money

/** Actual native CURRENT documents from their already joined canonical FOOD and CASH writers.
 * No fake CommandReadView, re-preparation, current grant or recipe/price backfill is used.
 * The existing whole FoodStockOriginalProof/signature/stock/resource joins remain mandatory
 * for every writer before this adapter runs. This additional traversal closes all current
 * order/owner/portion/price-batch/station relationships and exact complete identity claims.
 * Unbound quotations additionally close every immutable sidecar and ordered batch member;
 * their original writers supply custody, while the current structural join grants no stock.
 *
 * Sent work additionally joins the actual current normalized issue/reservation and complete
 * allocation/value ranges to the original native first issue. Start/ready/serve and pending
 * cancellation retain issued quantities; after-start waste retains consumption and clears
 * active station locators. No delivery/printing success is inferred from the task or outbox.
 * Collection locks, original CASH attempts/coverage, paid documents and whole-unsent refund
 * obligations retain their exact quote and reservation relationships. Current closure never
 * replays accounting or releases stock; unsupported handoff, quality claims, legacy producers
 * and unstarted-confirmation/restoration remain held. A cancelled sent portion never becomes
 * an unsent draft or acquires restored stock. */
internal suspend fun requireFoodCurrentHistory(state: RuntimeHistoricalFoodCurrentState): Boolean {
    if (state.hasIncomingHandoffClaims()) return false
    val sources = state.sources()
    val kinds = setOf(LocalFoodProfileV1.ORDER, LocalFoodProfileV1.PORTION,
        LocalFoodProfileV1.PRICE_BATCH, LocalFoodProfileV1.OWNER_INDEX, LocalFoodProfileV1.STATION_INDEX,
        FoodQuotationStorageV1.RECIPE_QUOTATION)
    if (sources.any { it.row.key.kind !in kinds + foodCurrentFinancialKinds + LocalFoodFinancialProfileV1.FRAME }) return false
    val foodSources = sources.filter { it.row.key.kind in kinds }
    if (foodSources.any { source ->
        !foodStreamedSchema(source.row.key.kind, source.row.schema) ||
            (source.original as? RetainedOriginalV2.Streamed)?.evidence?.identity?.producer?.command != StockStreamingCommandsV2.FOOD
    }) return false

    val rows = foodSources.associate { source ->
        val row = source.row
        val original = (source.original as RetainedOriginalV2.Streamed).evidence
        val envelope = foodStoredEnvelope(row.schema, row.payload)
        check(envelope.scope == state.scope && envelope.kind == row.key.kind && envelope.id == row.key.id &&
            envelope.revision == row.revision && original.identity.scope == state.scope &&
            original.identity.producer.operationId == envelope.operationId)
        row.key to envelope
    }
    check(rows.size == foodSources.size)
    val financial = foodCurrentFinancialHistory(state, sources)
    val collections = financial.values.filter { it.body is FoodFinancialBodyV1.Collection }.associateBy { it.id }
    val sales = financial.values.filter { it.body is FoodFinancialBodyV1.Sale }.associateBy { it.id }
    val rights = financial.values.filter { it.body is FoodFinancialBodyV1.Entitlement }.associateBy { it.id }
    val refunds = financial.values.filter { it.body is FoodFinancialBodyV1.Refund }.associateBy { it.id }
    val refundIndexes = financial.values.filter { it.body is FoodFinancialBodyV1.RefundIndex }.associateBy { it.id }
    val orders = rows.values.filter { it.body is FoodBodyV1.Order }
    val portions = rows.values.filter { it.body is FoodBodyV1.Portion || it.body is FoodBodyV1.QuotedPortion }
    val quotations = rows.values.filter { it.body is FoodBodyV1.RecipeQuotation }
    val batches = rows.values.filter { it.body is FoodBodyV1.PriceBatch }
    val owners = rows.values.filter { it.body is FoodBodyV1.OwnerIndex }
    val stations = rows.values.filter { it.body is FoodBodyV1.StationIndex }
    if (orders.any { (it.body as FoodBodyV1.Order).handoff != null } ||
        portions.any { !supportedFoodCurrentPortion(it.body) } ||
        collections.values.any { foodFinancialCollection(it).let { value ->
            value.responsibility.isNotEmpty() || value.attempts.any { attempt -> attempt.tender != Tender.Cash }
        } } || refunds.values.any { foodFinancialRefund(it).attempts.any { attempt -> attempt.tender != Tender.Cash } }) return false

    val portionsByOrder = portions.groupBy { foodCurrentPortionOrderId(it.body) }
    val batchesByOrder = batches.groupBy { (it.body as FoodBodyV1.PriceBatch).orderId }
    val portionsByBatch = portions.groupBy { foodCurrentPortionBatchId(it.body) }
    val quotationsByBatch = quotations.groupBy { (it.body as FoodBodyV1.RecipeQuotation).priceBatchId }
    val ordersByOwner = orders.filter { (it.body as FoodBodyV1.Order).closedByOperationId == null }
        .groupBy { (it.body as FoodBodyV1.Order).ownerId }
    val collectionsByOrder = collections.values.groupBy { foodFinancialCollection(it).orderId }
    val tasks = mutableListOf<RuntimeHistoricalFoodCurrentTaskIdentity>()
    val unissued = mutableListOf<RuntimeHistoricalFoodCurrentReservationIdentity>()
    val cancellations = mutableListOf<RuntimeHistoricalFoodCurrentCancellationIdentity>()
    val tenders = mutableListOf<RuntimeHistoricalFoodCurrentTenderIdentity>()
    val activeTasksByStation = linkedMapOf<DomainId, MutableList<FoodTaskLocatorV1>>()
    for (row in orders) {
        val order = row.body as FoodBodyV1.Order
        val index = rows[AggregateKey(LocalFoodProfileV1.OWNER_INDEX, order.ownerId)]?.body as? FoodBodyV1.OwnerIndex
            ?: unavailable()
        check((row.id in index.orderIds) == (order.closedByOperationId == null) && order.ownerId == order.creatorId &&
            portionsByOrder[row.id].orEmpty().map { it.id }.toSet() == order.portionIds.toSet() &&
            batchesByOrder[row.id].orEmpty().map { it.id }.toSet() == order.batchIds.toSet())
        var total = Money(Currency.EGP, 0)
        for (batch in batchesByOrder[row.id].orEmpty())
            total += StoredPricingSnapshotCodec.decode((batch.body as FoodBodyV1.PriceBatch).quote).payable
        check(total == order.originalQuotedTotal)
        val collectionId = order.financialCollectionId
        if (collectionId == null) {
            check(order.closedByOperationId == null && collectionsByOrder[row.id].orEmpty().isEmpty())
        } else {
            // Unbound quotations have no financial or stock ingress. The installed original
            // producer must establish a routed binding before a collection can exist.
            check(portionsByOrder[row.id].orEmpty().all { it.body is FoodBodyV1.Portion })
            val value = foodFinancialCollection(collections[collectionId] ?: unavailable())
            check(collectionsByOrder[row.id].orEmpty().map { it.id } == listOf(collectionId) &&
                value.orderId == row.id && value.currentOwnerId == order.ownerId && value.originalCreatorId == order.creatorId &&
                value.collectorId == order.ownerId && value.lockedCommercialRevision < row.revision)
            requireFoodFinancialQuote(value, order, order.portionIds.map { rows.getValue(AggregateKey(LocalFoodProfileV1.PORTION, it)) },
                order.batchIds.map { rows.getValue(AggregateKey(LocalFoodProfileV1.PRICE_BATCH, it)) })
            value.attempts.forEach { attempt -> tenders += RuntimeHistoricalFoodCurrentTenderIdentity(
                AggregateKey(LocalFoodFinancialProfileV1.COLLECTION, collectionId), attempt.id, attempt.originalCashSessionId,
                attempt.payment != null, attempt.tender == Tender.Cash) }
            val paid = value.saleId?.let { id -> foodFinancialSale(sales[id] ?: unavailable()) }
            val entitlement = value.saleId?.let { id -> foodFinancialEntitlement(rights[id] ?: unavailable()) }
            if (paid != null && entitlement != null) requireFoodPaidSource(value, paid, entitlement)
            val refund = value.cancellationRefundId?.let { id -> foodFinancialRefund(refunds[id] ?: unavailable()) }
            if (refund != null) {
                val indexBody = refundIndexes[row.id]?.body as? FoodFinancialBodyV1.RefundIndex ?: unavailable()
                requireFoodRefundBasis(refund, value, indexBody, paid, entitlement)
                check(order.portionIds.all { id -> (rows.getValue(AggregateKey(LocalFoodProfileV1.PORTION, id)).body as FoodBodyV1.Portion)
                    .preparation.let { it.status == FoodPreparationStatus.CANCELLED_BEFORE_START && it.issueId == null &&
                        it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER } })
                cancellations += RuntimeHistoricalFoodCurrentCancellationIdentity(row.id, collectionId, refund.id,
                    foodCancellationResolutionId(refund.cancellationOperationId))
                refund.attempts.forEach { attempt -> tenders += RuntimeHistoricalFoodCurrentTenderIdentity(
                    AggregateKey(LocalFoodFinancialProfileV1.REFUND, refund.id), attempt.id, attempt.originalCashSessionId,
                    attempt.refund != null, attempt.tender == Tender.Cash) }
            }
            if (order.closedByOperationId != null) {
                check(!value.unresolved && (if (refund != null) !refund.unresolved && refund.due.minor == 0L
                else value.status == FoodCollectionStatus.FINALIZED_PAID && order.portionIds.all { id ->
                    (rows.getValue(AggregateKey(LocalFoodProfileV1.PORTION, id)).body as FoodBodyV1.Portion)
                        .preparation.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER }))
            }
        }
    }
    // Complete two-way document membership. A financial root or obligation cannot be
    // omitted just because the preparation/owner rows happen to form a coherent subset.
    check(collectionsByOrder.keys == orders.filter { (it.body as FoodBodyV1.Order).financialCollectionId != null }.map { it.id }.toSet() &&
        sales.keys == collections.values.mapNotNull { foodFinancialCollection(it).saleId }.toSet() && rights.keys == sales.keys &&
        refunds.keys == collections.values.mapNotNull { foodFinancialCollection(it).cancellationRefundId }.toSet() &&
        refundIndexes.keys == refunds.values.map { foodFinancialRefund(it).orderId }.toSet())
    for (row in portions.filter { it.body is FoodBodyV1.Portion }) {
        val portion = row.body as FoodBodyV1.Portion
        val order = rows[AggregateKey(LocalFoodProfileV1.ORDER, portion.orderId)]?.body as? FoodBodyV1.Order
            ?: unavailable()
        val batch = rows[AggregateKey(LocalFoodProfileV1.PRICE_BATCH, portion.priceBatchId)]?.body as? FoodBodyV1.PriceBatch
            ?: unavailable()
        check(row.id in order.portionIds && portion.priceBatchId in order.batchIds && batch.orderId == portion.orderId &&
            portion.recipe.activity == order.activity && portion.recipe.channel == order.service.channel)
        val quote = StoredPricingSnapshotCodec.decode(batch.quote)
        val line = quote.lines.singleOrNull { it.portionId == row.id } ?: unavailable()
        val source = batch.sources.singleOrNull { it.portionId == row.id } ?: unavailable()
        check(line.quantity == portion.selection.quantity && source.skuId == portion.recipe.pricingSkuId)
        if (portion.preparation.fulfillment == FoodFulfillmentStatus.SERVED)
            check(order.service.channel == LocalSalesChannel.DINE_IN)
        val collection = order.financialCollectionId?.let { foodFinancialCollection(collections[it] ?: unavailable()) }
        if (portion.preparation.fulfillment == FoodFulfillmentStatus.PICKED_UP) {
            val value = collection ?: unavailable()
            check(value.status == FoodCollectionStatus.FINALIZED_PAID && value.cancellationRefundId == null &&
                order.service.channel in setOf(LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY) &&
                foodFinancialEntitlement(rights[checkNotNull(value.saleId)] ?: unavailable()).cursors
                    .single { it.portionId == row.id }.consumedQuantity.scaled == 0L)
        }
        portion.normalizedIssue?.let { reference ->
            val orderRow = rows.getValue(AggregateKey(LocalFoodProfileV1.ORDER, portion.orderId))
            check(reference.actorId == order.ownerId && reference.collectionActorId == order.ownerId &&
                reference.lockedQuoteRevision < orderRow.revision)
            val stock = requireCurrentFoodIssueSources(state.issueSources(row.id, reference.reservationId, reference.issueId))
            requireFoodCurrentIssueHeader(portion, stock.issue)
            requireFoodFirstIssue(portion, stock.firstIssue)
            requireFoodReservationMembers(portion, stock.reservation) { consume -> stock.allocations.forEach { consume(it) } }
            requireFoodIssueMembers(portion, stock.issue) { consume -> stock.slices.forEach { consume(it) } }
            if (collection != null) {
                val link = collection.portions.single { it.portionId == row.id }
                check(collection.cancellationRefundId == null && stock.reservation.expiresAtEpochMillis == null)
                if (link.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION) check(
                    portion.financialCollectionId == collection.id && stock.reservation.binding == foodFinancialStockBinding(collection) &&
                        reference.lockedQuoteRevision == collection.lockedCommercialRevision)
                else check(portion.financialCollectionId == null && link.priorIssueId == stock.issue.id &&
                    stock.issue.binding.exposure == CollectionExposure.UNCOLLECTED &&
                    stock.issue.binding.collectionId == foodStockContextId(collection.orderId) &&
                    reference.lockedQuoteRevision < collection.lockedCommercialRevision)
            }
        }
        if (portion.normalizedIssue == null && collection != null) {
            val link = collection.portions.single { it.portionId == row.id }
            check(link.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION && portion.financialCollectionId == null)
            val stock = requireCurrentFoodReservationSources(state.reservationSources(row.id, link.reservationId, foodIssueId(row.id)))
            requireFoodReservationMembers(portion, stock.reservation) { consume -> stock.allocations.forEach { consume(it) } }
            check(stock.reservation.binding == foodFinancialStockBinding(collection) && stock.reservation.expiresAtEpochMillis == null &&
                (if (collection.cancellationRefundId != null) stock.reservation.status == ReservationStatus.RELEASED &&
                    portion.preparation.status == FoodPreparationStatus.CANCELLED_BEFORE_START
                else stock.reservation.retainsStockClaim && portion.preparation.status == FoodPreparationStatus.NOT_SENT))
            unissued += RuntimeHistoricalFoodCurrentReservationIdentity(row.id, link.reservationId, collection.id)
        } else if (collection == null) check(portion.financialCollectionId == null)
        val active = portion.preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER &&
            portion.preparation.status !in setOf(FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START)
        for (task in portion.preparation.tasks) {
            val route = portion.recipe.routes.singleOrNull { it.stationId == task.stationId } ?: unavailable()
            check(task.taskId == foodTaskId(row.id, task.stationId) && task.deliveryMode == route.mode)
            tasks += RuntimeHistoricalFoodCurrentTaskIdentity(task.taskId, portion.orderId, row.id, task.stationId)
            if (active) activeTasksByStation.getOrPut(task.stationId) { mutableListOf() } +=
                FoodTaskLocatorV1(task.taskId, portion.orderId, row.id)
        }
    }
    val linkedSidecarIds = linkedSetOf<DomainId>()
    for (row in batches) {
        val batch = row.body as FoodBodyV1.PriceBatch
        val orderRow = rows[AggregateKey(LocalFoodProfileV1.ORDER, batch.orderId)] ?: unavailable()
        val order = orderRow.body as? FoodBodyV1.Order ?: unavailable()
        val quote = StoredPricingSnapshotCodec.decode(batch.quote)
        val members = portionsByBatch[row.id].orEmpty()
        val batchSidecars = quotationsByBatch[row.id].orEmpty()
        check(row.id in order.batchIds && quote.lines.map { it.portionId }.toSet() == members.map { it.id }.toSet())
        if (members.any { it.body is FoodBodyV1.QuotedPortion } || batchSidecars.isNotEmpty()) {
            val byId = members.associateBy { it.id }
            val ordered = quote.lines.map { byId[it.portionId] ?: unavailable() }
            val sidecars = ordered.map { portion ->
                val id = when (val body = portion.body) {
                    is FoodBodyV1.QuotedPortion -> body.quotationId
                    is FoodBodyV1.Portion -> LocalFoodQuotationProfileV1.quotationId(portion.id, body.recipe.publicationId)
                    else -> unavailable()
                }
                rows[AggregateKey(FoodQuotationStorageV1.RECIPE_QUOTATION, id)] ?: unavailable()
            }
            check(sidecars.map { it.id }.distinct().size == sidecars.size &&
                batchSidecars.map { it.id }.toSet() == sidecars.map { it.id }.toSet())
            // Actual current envelopes already passed genuine original/native writer checks.
            // The derived lookup alone is NOT custody; the join requires every literal
            // sidecar, original fact and ordered member, including linked routed work.
            foodOriginalQuotationBatch(orderRow, ordered, sidecars, row)
            for (sidecar in sidecars) check(linkedSidecarIds.add(sidecar.id))
        } else {
            // Legacy routed history has no quotation sidecars. Never use this branch
            // to excuse a partial sidecar-backed batch or an unbound quoted member.
            check(members.all { it.body is FoodBodyV1.Portion })
        }
    }
    check(quotations.map { it.id }.toSet() == linkedSidecarIds)
    for (row in owners) {
        val index = row.body as FoodBodyV1.OwnerIndex
        check(index.orderIds.toSet() == ordersByOwner[row.id].orEmpty().map { it.id }.toSet())
    }
    // Complete two-way membership, including empty retained indexes after service/waste.
    // Pending cancellation remains active until an actual terminal preparation transition.
    check(tasks.map { it.taskId }.distinct().size == tasks.size &&
        stations.map { it.id }.toSet() == tasks.map { it.stationId }.toSet())
    for (row in stations) {
        val index = row.body as FoodBodyV1.StationIndex
        check(index.tasks.toSet() == activeTasksByStation[row.id].orEmpty().toSet())
    }
    val expected = sources.map { source ->
        val key = source.row.key
        val food = rows[key]
        val money = financial[key]
        when {
            food != null -> RuntimeHistoricalFoodCurrentWriter(key, food.revision, food.operationId)
            money != null -> RuntimeHistoricalFoodCurrentWriter(key, money.revision, money.operationId)
            key.kind == LocalFoodFinancialProfileV1.FRAME -> {
                val operationId = when (val original = source.original) {
                    is RetainedOriginalV2.Streamed -> original.evidence.identity.producer.operationId
                    is RetainedOriginalV2.Legacy -> original.evidence.operationId
                }
                RuntimeHistoricalFoodCurrentWriter(key, source.row.revision, operationId)
            }
            else -> unavailable()
        }
    }
    state.requireExactWriters(expected, tasks, unissued, cancellations, tenders)
    return true
}

private fun supportedFoodCurrentPortion(body: FoodBodyV1): Boolean {
    if (body is FoodBodyV1.QuotedPortion) {
        val preparation = body.preparation
        return preparation.status in setOf(FoodPreparationStatus.NOT_SENT, FoodPreparationStatus.CANCELLED_BEFORE_START) &&
            preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && preparation.tasks.isEmpty() &&
            preparation.reservationId == null && preparation.issueId == null && preparation.issueRevision == null &&
            preparation.sentAt == null && preparation.servedAt == null && preparation.pickedUpAt == null
    }
    val portion = body as? FoodBodyV1.Portion ?: return false
    val preparation = portion.preparation
    if (portion.originalIssue != null) return false
    if (portion.normalizedIssue == null) return preparation.issueId == null && preparation.tasks.isEmpty() &&
        preparation.status in setOf(FoodPreparationStatus.NOT_SENT, FoodPreparationStatus.CANCELLED_BEFORE_START) &&
        preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER
    // Original confirmation/restoration authority remains independently held, even after
    // a partial confirmation or a completed before-start reversal. Never erase its pointers.
    return preparation.status in setOf(FoodPreparationStatus.QUEUED, FoodPreparationStatus.PREPARING,
        FoodPreparationStatus.READY, FoodPreparationStatus.CANCEL_PENDING, FoodPreparationStatus.WASTE_AFTER_START) &&
        preparation.tasks.none { it.cancellationConfirmationId != null }
}

private fun foodCurrentPortionOrderId(body: FoodBodyV1): DomainId = when (body) {
    is FoodBodyV1.Portion -> body.orderId
    is FoodBodyV1.QuotedPortion -> body.orderId
    else -> unavailable()
}

private fun foodCurrentPortionBatchId(body: FoodBodyV1): DomainId = when (body) {
    is FoodBodyV1.Portion -> body.priceBatchId
    is FoodBodyV1.QuotedPortion -> body.priceBatchId
    else -> unavailable()
}

private fun unavailable(): Nothing = error("food.current_history.source_unavailable")

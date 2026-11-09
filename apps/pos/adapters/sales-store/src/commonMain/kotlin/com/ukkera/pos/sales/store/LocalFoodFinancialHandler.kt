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
 * Genuine NONPHYSICAL financial cut. No physical attempt is accepted by this handler until
 * the shared journal-first B1/B2 provider and execution gate are actually implemented/wired.
 * This is not an accepting capacity placeholder, retail bridge or complete food workflow.
 */
public class LocalFoodFinancialHandler(private val stock: LocalStockRepository,
    private val recipes: TransactionFoodRecipeSourceV1) : MasterCommandHandler {
    override val key: CommandKey = LocalFoodFinancialProfileV1.COMMAND
    override val writableKinds: Set<String> = foodFinancialDocumentKinds + setOf(LocalFoodFinancialProfileV1.EVENT,
        LocalFoodFinancialProfileV1.BEFORE, LocalFoodFinancialProfileV1.FRAME) + LocalFoodProfileV1.kinds + LocalStockStoreProfile.inventoryKinds
    override val claimNamespaces: Set<String> = setOf(LocalFoodFinancialProfileV1.SOURCE_NAMESPACE, LocalFoodProfileV1.CLAIM) +
        LocalStockStoreProfile.inventoryClaimNamespaces
    override fun normalize(input: StoredBytes): StoredBytes {
        val request = LocalFoodFinancialCodecV1.decode(input)
        require(foodNonphysicalRequest(request)) { "food.financial.physical_profile_not_installed" }
        return LocalFoodFinancialCodecV1.encode(request)
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == view.scope &&
            semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalFoodFinancialCodecV1.decode(normalized)
        check(foodNonphysicalRequest(request))
        val bounded = FoodReadView(commerceLoan(view, stock))
        // Bounded headers bind refusals. They are NOT grants or a substitute for the full
        // producer/catalog/current-stock observations made by every successful path below.
        val admission = foodFinancialAdmission(request, bounded, context)
        fun refuse(code: String): PreparedMutation = admission.prepared(context) { _, _ -> foodFinancialReject(code) }
        if (admission.missing) return refuse("food.financial.target_unavailable")
        if (admission.action.ownerPrivate && admission.ownerId != context.originalActorId)
            return refuse("food.financial.target_unavailable")
        if (request is LocalFoodFinancialRequestV1.BeginCollection &&
            foodObserveRow(bounded, LocalFoodProfileV1.ORDER, request.orderId)?.row?.schema == LocalFoodDraftProfileV1.ORDER_SCHEMA)
            return refuse("food.financial.empty_draft")
        return try {
            when (request) {
                is LocalFoodFinancialRequestV1.BeginCollection -> begin(request, bounded, context)
                is LocalFoodFinancialRequestV1.FinalizePaid -> finalize(request, bounded, context)
                is LocalFoodFinancialRequestV1.SendReserved -> send(request, bounded, context)
                is LocalFoodFinancialRequestV1.PaidPickup -> pickup(request, bounded, context)
                is LocalFoodFinancialRequestV1.CancelWholeUnsent -> foodCancelWholeUnsent(request, bounded, context, stock, recipes)
                is LocalFoodFinancialRequestV1.CloseResolvedWork -> foodCloseResolvedWork(request, bounded, context, stock, recipes)
                is LocalFoodFinancialRequestV1.RecordQualityClaim -> foodRecordQualityClaim(request, bounded, context)
                else -> error("food.financial.physical_profile_not_installed")
            }
        } catch (_: FoodCapacityExceeded) { refuse("food.financial.capacity_required") }
        catch (_: LocalStockHydrationCapacityUnavailable) { refuse("food.financial.capacity_required") }
    }

    private suspend fun begin(request: LocalFoodFinancialRequestV1.BeginCollection, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val order = foodOrder(view, request.orderId)
        val portions = order.value.portionIds.map { foodPortion(view, order, it, recipes) }
        val batches = order.value.batchIds.map { foodRead(view, LocalFoodProfileV1.PRICE_BATCH, it) ?: error("food.financial.price_missing") }
        val quote = foodFinancialQuote(request.orderId, batches.map { it.envelope })
        check(quote.portionIds == order.value.portionIds && quote.payable == order.value.originalQuotedTotal)
        val cash = LocalSalesStorage.cash(view, request.sessionId)
        val existing = foodFinancialRead(view, LocalFoodFinancialProfileV1.COLLECTION, request.collectionId)
        val unsent = portions.filter { (it.envelope.body as FoodBodyV1.Portion).preparation.status == FoodPreparationStatus.NOT_SENT }
        val kitchenRestriction = foodKitchenExecutionRestriction(view, recipes, order, portions)
        val current = unsent.map { recipes.currentForSend(view, (it.envelope.body as FoodBodyV1.Portion).recipe) }.all { it }
        val inventory = foodFinancialLegacyStock(view, stock, portions)
        var reservedIdentityUsed = false
        for (row in unsent) {
            val id = foodReservationId(row.envelope.id)
            if (view.aggregate(LocalStockStoreProfile.reservationKey(id)) != null || view.claim(LocalStockStoreProfile.reservationClaim(id)) != null ||
                view.aggregate(LocalStockStoreProfile.issueKey(foodIssueId(row.envelope.id))) != null ||
                view.claim(LocalStockStoreProfile.sourceClaim(foodIssueId(row.envelope.id))) != null) reservedIdentityUsed = true
        }
        val observed = listOf(foodObserved(order.order), foodObserved(order.ownerIndex)) + portions.map(::foodObserved) +
            batches.map(::foodObserved) + inventory.observed
        return FoodFinancialPrepared(context, SecurityAction.COLLECTION_BEGIN, request.orderId, request.expectedOrderRevision,
            order.value.ownerId, "food.collection.begin", quote.payable, foodResources(order, portions) + cashResources(cash) +
                inventory.resources + orderResource(request.collectionId) + portions.map {
                    RecoveryResourceKey(RecoveryResourceKind.STOCK, foodReservationId(it.envelope.id))
                }, admissionRestriction = kitchenRestriction) { command, at ->
            foodFinancialOwner(context, order, request.expectedOrderRevision)
            if (existing != null || reservedIdentityUsed) return@FoodFinancialPrepared foodFinancialReject("food.financial.identity_used")
            if (order.value.financialCollectionId != null) return@FoodFinancialPrepared foodFinancialReject("food.financial.already_locked")
            if (!current) return@FoodFinancialPrepared foodFinancialReject("food.financial.recipe_changed_requires_replacement")
            if (portions.any { (it.envelope.body as FoodBodyV1.Portion).preparation.status in foodChargeDecisionStates })
                return@FoodFinancialPrepared foodFinancialReject("food.financial.charge_decision_required")
            val links = portions.map { row ->
                val body = row.envelope.body as FoodBodyV1.Portion
                FoodFinancialPortion(row.envelope.id, body.priceBatchId, body.recipe.snapshotId, body.selection.quantity,
                    foodReservationId(row.envelope.id), if (body.preparation.issueId == null) FoodStockOrigin.RESERVED_FOR_COLLECTION
                        else FoodStockOrigin.ISSUED_BEFORE_COLLECTION, body.preparation.issueId)
            }
            val collection = FoodCollections.begin(command, context.scope, request.orderId, request.expectedOrderRevision,
                order.value.creatorId, order.value.ownerId, cash.session.value, request.expectedSessionRevision,
                request.collectionId, quote, links)
            val plan = if (unsent.isEmpty()) null else {
                val requests = unsent.flatMap { row ->
                    val portion = row.envelope.body as FoodBodyV1.Portion
                    portion.recipe.ingredients.map { LocalStockAllocationRequest(foodIngredientLine(row.envelope.id, it.componentId),
                        it.skuId, portion.recipe.warehouseId, it.requiredBaseQuantity) }
                }
                val allocations = stock.allocate(inventory.planning, request.orderId, request.expectedOrderRevision, requests, at)
                val reserve = unsent.map { row ->
                    val portion = row.envelope.body as FoodBodyV1.Portion
                    val lines = portion.recipe.ingredients.map { foodIngredientLine(row.envelope.id, it.componentId) }.toSet()
                    FoodIngredientReservation(foodReservationId(row.envelope.id), foodFinancialStockBinding(collection),
                        allocations.filter { it.lineId in lines })
                }
                FoodInventoryPlanner.reserveBeforeCollection(inventory.planning.snapshot, inventory.planning.snapshot.revision,
                    context.operationId, reserve, at.value.toEpochMilliseconds())
            }
            val contribution = plan?.let { stock.project(inventory.planning, context, it, at) }
            val writes = FoodFinancialWrites(context, at, observed, foodFinancialCashInput(cash))
            writes.putFood(LocalFoodProfileV1.ORDER, request.orderId, order.value.copy(financialCollectionId = collection.id))
            writes.put(LocalFoodFinancialProfileV1.COLLECTION, collection.id,
                FoodFinancialBodyV1.Collection(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeCollection(collection) }))
            writes.apply("food.collection.locked", collection.id, collection.quote.payable,
                stock = if (contribution == null) null else stock, contribution = contribution)
        }
    }

    private suspend fun finalize(request: LocalFoodFinancialRequestV1.FinalizePaid, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        val inventory = foodFinancialLegacyStock(view, stock, cut.portions, cut.value)
        val sale = foodFinancialRead(view, LocalFoodFinancialProfileV1.SALE, request.saleId)
        val entitlement = foodFinancialRead(view, LocalFoodFinancialProfileV1.ENTITLEMENT, request.saleId)
        val action = if (cut.value.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
        return FoodFinancialPrepared(context, action, request.collectionId, request.expectedCollectionRevision, cut.value.currentOwnerId,
            "food.collection.finalize", cut.value.quote.payable, foodFinancialResources(cut) + inventory.resources + entitlementResource(request.saleId)) { command, at ->
            if (sale != null || entitlement != null) return@FoodFinancialPrepared foodFinancialReject("food.financial.identity_used")
            if (cut.portions.any { (it.envelope.body as FoodBodyV1.Portion).preparation.status in foodChargeDecisionStates })
                return@FoodFinancialPrepared foodFinancialReject("food.financial.charge_decision_required")
            val finalized = FoodCollections.finalize(command, cut.value, request.expectedCollectionRevision, request.saleId)
            val writes = FoodFinancialWrites(context, at, foodFinancialObservations(cut) + inventory.observed)
            writes.put(LocalFoodFinancialProfileV1.COLLECTION, cut.value.id,
                FoodFinancialBodyV1.Collection(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeCollection(finalized.collection) }))
            writes.put(LocalFoodFinancialProfileV1.SALE, request.saleId,
                FoodFinancialBodyV1.Sale(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeSale(finalized.sale) }))
            writes.put(LocalFoodFinancialProfileV1.ENTITLEMENT, request.saleId,
                FoodFinancialBodyV1.Entitlement(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeEntitlement(finalized.entitlement) }))
            // Deliberately no stock projection/issue, no cash movement, no fulfillment/index retirement.
            writes.apply("food.sale.finalized", request.saleId, finalized.sale.quote.payable)
        }
    }

    private suspend fun send(request: LocalFoodFinancialRequestV1.SendReserved, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        val selected = request.portionIds.mapNotNull { id -> cut.portions.singleOrNull { it.envelope.id == id } }
        val kitchenRestriction = foodKitchenExecutionRestriction(view, recipes, cut.order, selected)
        val inventory = foodFinancialLegacyStock(view, stock, cut.portions, cut.value)
        val current = selected.map { recipes.currentForSend(view, (it.envelope.body as FoodBodyV1.Portion).recipe) }.all { it }
        val stations = selected.flatMap { (it.envelope.body as FoodBodyV1.Portion).recipe.routes }.map { it.stationId }.distinct()
        val indexes = stations.associateWith { foodRead(view, LocalFoodProfileV1.STATION_INDEX, it) }
        val taskUsed = selected.flatMap { row -> (row.envelope.body as FoodBodyV1.Portion).recipe.routes.map {
            foodTaskId(row.envelope.id, it.stationId)
        } }.map { view.claim(LocalFoodProfileV1.claim("task", it)) }.any { it != null }
        return FoodFinancialPrepared(context, SecurityAction.ORDER_EDIT, request.orderId, request.expectedOrderRevision,
            cut.order.value.ownerId, "food.collection.send_reserved", resources = foodFinancialResources(cut) + inventory.resources +
                stations.map(::orderResource) + selected.flatMap { row -> listOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, foodIssueId(row.envelope.id))) +
                    (row.envelope.body as FoodBodyV1.Portion).recipe.routes.map { orderResource(foodTaskId(row.envelope.id, it.stationId)) } },
            admissionRestriction = kitchenRestriction) { _, at ->
            foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
            foodFinancialRule(request.orderId == cut.value.orderId && cut.value.revision == request.expectedCollectionRevision, SalesRule.REVISION_CONFLICT)
            if (cut.value.cancellationRefundId != null || taskUsed) return@FoodFinancialPrepared foodFinancialReject("food.financial.cancelled_or_identity_used")
            if (selected.size != request.portionIds.size || selected.any { row ->
                val portion = row.envelope.body as FoodBodyV1.Portion
                portion.preparation.status != FoodPreparationStatus.NOT_SENT ||
                    cut.value.portions.single { it.portionId == row.envelope.id }.stockOrigin != FoodStockOrigin.RESERVED_FOR_COLLECTION
            }) return@FoodFinancialPrepared foodFinancialReject("food.financial.unsent_selection_required")
            if (!current) return@FoodFinancialPrepared foodFinancialReject("food.financial.recipe_changed_requires_resolution")
            val issueRequests = selected.map { row ->
                val portion = row.envelope.body as FoodBodyV1.Portion
                val reservation = inventory.reservations.getValue(foodReservationId(row.envelope.id))
                FoodReservedIngredientIssue(reservation.reservationId, reservation.revision, foodIssueId(row.envelope.id),
                    RecipeIssueRequest(portion.recipe.snapshotId, portion.recipe.routes.map { it.stationId }))
            }
            val plan = FoodInventoryPlanner.sendReserved(inventory.planning.snapshot, inventory.planning.snapshot.revision,
                context.operationId, issueRequests, at.value.toEpochMilliseconds())
            val contribution = stock.project(inventory.planning, context, plan, at)
            val writes = FoodFinancialWrites(context, at, foodFinancialObservations(cut) + inventory.observed + indexes.values.filterNotNull().map(::foodObserved))
            val changed = selected.map { row ->
                val portion = row.envelope.body as FoodBodyV1.Portion
                val tasks = portion.recipe.routes.map { FoodStationTask(foodTaskId(row.envelope.id, it.stationId), it.stationId, it.mode, FoodStationStatus.QUEUED) }
                val next = FoodPreparation.sent(portion.preparation, portion.preparation.revision, foodReservationId(row.envelope.id), foodIssueId(row.envelope.id), tasks, at)
                val issue = checkNotNull(plan.nextSnapshot.issue(foodIssueId(row.envelope.id)))
                requireFoodIssueRecipe(issue, portion)
                writes.putFood(LocalFoodProfileV1.PORTION, row.envelope.id, portion.copy(preparation = next,
                    originalIssue = InventoryRecordCodec.encodeIssue(issue, plan.nextSnapshot.scope), financialCollectionId = cut.value.id))
            }
            foodFinancialStationWrites(writes, indexes, changed.map { it.body as FoodBodyV1.Portion }, adding = true)
            writes.putFood(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value)
            val jobs = changed.flatMap { foodDeliveryJobs(cut.order.value, it.body as FoodBodyV1.Portion, "PREPARE") }
            writes.apply("food.reserved.sent", request.orderId, jobs = jobs, stock = stock, contribution = contribution,
                extraClaims = changed.flatMap { (it.body as FoodBodyV1.Portion).preparation.tasks }.map {
                    ClaimChange.Acquire(LocalFoodProfileV1.claim("task", it.taskId), request.orderId)
                })
        }
    }

    private suspend fun pickup(request: LocalFoodFinancialRequestV1.PaidPickup, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        val inventory = foodFinancialLegacyStock(view, stock, cut.portions, cut.value)
        val selected = cut.portions.singleOrNull { it.envelope.id == request.portionId }
        val portion = selected?.envelope?.body as? FoodBodyV1.Portion
        val indexes = portion?.preparation?.tasks.orEmpty().associate { it.stationId to foodRead(view, LocalFoodProfileV1.STATION_INDEX, it.stationId) }
        val paid = cut.value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.SALE, it) ?: error("food.financial.paid_document_missing") }
        val entitlement = cut.value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.ENTITLEMENT, it) ?: error("food.financial.entitlement_missing") }
        return FoodFinancialPrepared(context, SecurityAction.ORDER_EDIT, request.orderId, request.expectedOrderRevision,
            cut.order.value.ownerId, "food.paid_pickup", resources = foodFinancialResources(cut) + inventory.resources) { _, at ->
            foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
            foodFinancialRule(request.orderId == cut.value.orderId, SalesRule.INVALID_EVIDENCE)
            if (portion == null || paid == null || entitlement == null || cut.value.cancellationRefundId != null ||
                cut.value.status != FoodCollectionStatus.FINALIZED_PAID) return@FoodFinancialPrepared foodFinancialReject("food.financial.paid_coverage_required")
            if (cut.order.value.service.channel !in setOf(LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY))
                return@FoodFinancialPrepared foodFinancialReject("food.financial.pickup_channel_required")
            requireFoodPaidSource(cut.value, foodFinancialSale(paid.envelope), foodFinancialEntitlement(entitlement.envelope))
            val cursor = foodFinancialEntitlement(entitlement.envelope).cursors.single { it.portionId == request.portionId }
            foodFinancialRule(cursor.consumedQuantity.scaled == 0L, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
            val next = FoodPreparation.pickUp(portion.preparation, request.expectedPortionRevision, at)
            val writes = FoodFinancialWrites(context, at, foodFinancialObservations(cut) + inventory.observed +
                listOf(foodObserved(paid), foodObserved(entitlement)) + indexes.values.filterNotNull().map(::foodObserved))
            val changed = writes.putFood(LocalFoodProfileV1.PORTION, request.portionId, portion.copy(preparation = next))
            foodFinancialStationWrites(writes, indexes, listOf(changed.body as FoodBodyV1.Portion), adding = false)
            writes.putFood(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value)
            writes.apply("food.paid_pickup.recorded", request.orderId)
        }
    }
}

internal val foodChargeDecisionStates: Set<FoodPreparationStatus> = setOf(FoodPreparationStatus.CANCEL_PENDING,
    FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START)
internal fun foodNonphysicalRequest(request: LocalFoodFinancialRequestV1): Boolean = when (request) {
    is LocalFoodFinancialRequestV1.BeginCollection, is LocalFoodFinancialRequestV1.FinalizePaid,
    is LocalFoodFinancialRequestV1.SendReserved, is LocalFoodFinancialRequestV1.PaidPickup,
    is LocalFoodFinancialRequestV1.CancelWholeUnsent, is LocalFoodFinancialRequestV1.CloseResolvedWork,
    is LocalFoodFinancialRequestV1.RecordQualityClaim -> true
    else -> false
}
internal fun foodFinancialStationWrites(writes: FoodFinancialWrites, indexes: Map<DomainId, FoodRow?>,
    portions: List<FoodBodyV1.Portion>, adding: Boolean) {
    for ((station, row) in indexes) {
        val prior = (row?.envelope?.body as? FoodBodyV1.StationIndex)?.tasks.orEmpty()
        val selected = portions.filter { p -> p.preparation.tasks.any { it.stationId == station } }.map {
            FoodTaskLocatorV1(foodTaskId(it.selection.portionId, station), it.orderId, it.selection.portionId)
        }
        check(selected.all { (it !in prior) == adding })
        val after = if (adding) prior + selected else prior.filterNot { old -> selected.any { it.taskId == old.taskId } }
        foodCapacity(after.size <= LocalFoodProfileV1.MAX_ACTIVE_INDEX)
        writes.putFood(LocalFoodProfileV1.STATION_INDEX, station, FoodBodyV1.StationIndex(after))
    }
}

internal data class FoodFinancialAdmission(val action: SecurityAction, val targetId: DomainId, val revision: Revision,
    val ownerId: DomainId, val reason: String, val amount: Money?, val missing: Boolean = false) {
    fun prepared(context: CommandContext, proposal: (SalesCommand, UtcInstant) -> ProposedOutcome): PreparedMutation =
        FoodFinancialPrepared(context, action, targetId, revision, ownerId, reason, amount,
            setOf(orderResource(targetId), orderResource(ownerId)),
            recoveryHoldBarrierKinds = if (action == SecurityAction.RETURN_CLAIM) setOf(RecoveryResourceKind.AUTHORIZATION)
                else setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION), proposal = proposal)
}
private suspend fun foodFinancialAdmission(request: LocalFoodFinancialRequestV1, view: CommandReadView,
    context: CommandContext): FoodFinancialAdmission = when (request) {
    is LocalFoodFinancialRequestV1.RecordQualityClaim -> FoodFinancialAdmission(SecurityAction.RETURN_CLAIM, request.claimId,
        Revision(0), context.originalActorId, "food.quality.claim", null)
    is LocalFoodFinancialRequestV1.BeginCollection -> {
        val header = foodObserveRow(view, LocalFoodProfileV1.ORDER, request.orderId)?.envelope?.body as? FoodBodyV1.Order
        FoodFinancialAdmission(SecurityAction.COLLECTION_BEGIN, request.orderId, request.expectedOrderRevision,
            header?.ownerId ?: context.originalActorId, "food.collection.begin", header?.originalQuotedTotal ?: Money(Currency.EGP, 0),
            header == null || header.closedByOperationId != null)
    }
    is LocalFoodFinancialRequestV1.FinalizePaid, is LocalFoodFinancialRequestV1.CancelWholeUnsent -> {
        val id = if (request is LocalFoodFinancialRequestV1.FinalizePaid) request.collectionId else (request as LocalFoodFinancialRequestV1.CancelWholeUnsent).collectionId
        val header = foodFinancialObserve(view, LocalFoodFinancialProfileV1.COLLECTION, id)?.second?.let(::foodFinancialCollection)
        val revision = if (request is LocalFoodFinancialRequestV1.FinalizePaid) request.expectedCollectionRevision else (request as LocalFoodFinancialRequestV1.CancelWholeUnsent).expectedCollectionRevision
        val amount = if (request is LocalFoodFinancialRequestV1.CancelWholeUnsent) header?.collected else header?.quote?.payable
        val action = if (request is LocalFoodFinancialRequestV1.CancelWholeUnsent) SecurityAction.COLLECTION_RESOLVE
            else if (amount?.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
        val closed = header != null && header.currentOwnerId == context.originalActorId && foodFinancialClosedHeader(view, header)
        FoodFinancialAdmission(action, id, revision, header?.currentOwnerId ?: context.originalActorId,
            if (request is LocalFoodFinancialRequestV1.FinalizePaid) "food.collection.finalize" else "food.cancel_whole_unsent",
            amount ?: Money(Currency.EGP, 0), header == null || closed)
    }
    else -> {
        val (id, revision, reason) = when (request) {
            is LocalFoodFinancialRequestV1.SendReserved -> Triple(request.orderId, request.expectedOrderRevision, "food.collection.send_reserved")
            is LocalFoodFinancialRequestV1.PaidPickup -> Triple(request.orderId, request.expectedOrderRevision, "food.paid_pickup")
            is LocalFoodFinancialRequestV1.CloseResolvedWork -> Triple(request.orderId, request.expectedOrderRevision, "food.close_resolved_work")
            else -> error("food.financial.physical_profile_not_installed")
        }
        val collectionId = when (request) {
            is LocalFoodFinancialRequestV1.SendReserved -> request.collectionId
            is LocalFoodFinancialRequestV1.PaidPickup -> request.collectionId
            is LocalFoodFinancialRequestV1.CloseResolvedWork -> request.collectionId
            else -> error("food.financial.physical_profile_not_installed")
        }
        val header = foodObserveRow(view, LocalFoodProfileV1.ORDER, id)?.envelope?.body as? FoodBodyV1.Order
        // Refuse a caller's cross-order collection selector from this owned header alone;
        // do not turn another private collection's provenance/capacity into an owned reply.
        FoodFinancialAdmission(SecurityAction.ORDER_EDIT, id, revision, header?.ownerId ?: context.originalActorId, reason, null,
            header == null || header.financialCollectionId != collectionId || header.closedByOperationId != null)
    }
}

/** A known closed target is a bounded refusal, not an exception from later active-order loading. */
private suspend fun foodFinancialClosedHeader(view: CommandReadView, collection: FoodCollection): Boolean {
    val order = foodObserveRow(view, LocalFoodProfileV1.ORDER, collection.orderId)?.envelope?.body as? FoodBodyV1.Order
        ?: error("food.financial.order_missing")
    check(order.financialCollectionId == collection.id && order.ownerId == collection.currentOwnerId)
    return order.closedByOperationId != null
}

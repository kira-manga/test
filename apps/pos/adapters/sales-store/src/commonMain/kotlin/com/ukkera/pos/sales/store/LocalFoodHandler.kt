package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Durable typed preparation, never a synthetic checkout or a second stock database. */
public class LocalFoodHandler(private val stock: LocalStockRepository, private val recipes: TransactionFoodRecipeSourceV1) :
    MasterCommandHandler, LocalMasterRpcRetainedResultAccessProvider {
    override val key: CommandKey = LocalFoodProfileV1.COMMAND
    override val writableKinds: Set<String> = LocalFoodProfileV1.kinds + LocalFoodHandoffProfileV2.kinds + LocalPaidFoodHandoffProfileV1.kinds + LocalStockStoreProfile.inventoryKinds
    override val claimNamespaces: Set<String> = setOf(LocalFoodProfileV1.CLAIM, LocalFoodHandoffProfileV2.INCOMING, LocalFoodFinancialProfileV1.SOURCE_NAMESPACE) + LocalStockStoreProfile.inventoryClaimNamespaces
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodCodecV1.normalize(input)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.KITCHEN) return false
        return LocalFoodCodecV1.decode(normalized).foodKitchenRpcStation() != null
    }
    override fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        context: CommandContext): List<AuthorizationRequirement> {
        check(profile == LocalMasterRpcWorkflowProfile.KITCHEN && context.key == key &&
            normalize(normalized) == normalized &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val station = checkNotNull(LocalFoodCodecV1.decode(normalized).foodKitchenRpcStation())
        return listOf(foodKitchenRpcStationResultRequirement(station, context))
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == view.scope && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalFoodCodecV1.decode(normalized)
        val bounded = FoodReadView(commerceLoan(view, stock))
        val orderId = request.foodOrderId()
        val action = foodAction(request)
        val target = foodTarget(request)
        // A fresh budget always accommodates this one bounded header/claim observation. It
        // only selects a refusal's exact owner/action binding; it is NOT producer validation.
        // All successful work below still requires the complete current-row/source crosswalk.
        val header = foodObserveRow(bounded, LocalFoodProfileV1.ORDER, orderId)
        val observed = header?.envelope?.body as? FoodBodyV1.Order
        val owner = when {
            request is LocalFoodRequestV1.Create -> context.originalActorId
            request is LocalFoodRequestV1.AcceptHandoff -> observed?.handoff?.to ?: observed?.ownerId ?: context.originalActorId
            else -> observed?.ownerId ?: context.originalActorId
        }
        // Preserve legacy originals, but fresh quoted additions require the explicit
        // normalized activity-protected route. The kernel still authorizes and admits
        // resources before returning this nonrecording hold.
        if (request is LocalFoodRequestV1.Create || request is LocalFoodRequestV1.Add) return FoodPrepared(context, action, target, request.foodOrderRevision(), owner,
            setOf(orderResource(orderId), orderResource(owner)), PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE) {
            error("food.legacy_quote_requires_original")
        }
        // Fresh handoff history and recipient funding continuation are not installed.
        // Hold before any offer/owner/responsibility write, including fully paid work.
        // Existing originals still use the kernel's retained-result recovery path.
        if (request is LocalFoodRequestV1.OfferHandoff || request is LocalFoodRequestV1.AcceptHandoff ||
            request is LocalFoodRequestV1.CancelHandoff) return FoodPrepared(context, action, target,
            request.foodOrderRevision(), owner, setOf(orderResource(orderId), orderResource(owner)),
            PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE) {
            error("food.handoff.current_history_required")
        }
        fun refusal(code: String): PreparedMutation = FoodPrepared(context, action, target, request.foodOrderRevision(), owner,
            setOf(orderResource(orderId), orderResource(owner), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, owner))) { _ -> foodReject(code) }
        if (request !is LocalFoodRequestV1.Create && observed == null) return refusal("food.order.unavailable")
        if (observed?.closedByOperationId != null) return refusal("food.order.closed")
        if (action.ownerPrivate && owner != context.originalActorId) return refusal("food.order.unavailable")
        if (header?.row?.schema == LocalFoodDraftProfileV1.ORDER_SCHEMA) return refusal("food.order.current_profile_required")
        if (request is LocalFoodRequestV1.AcceptHandoff && observed?.handoff?.to != context.originalActorId)
            return refusal("food.handoff.unavailable")
        if (observed?.financialCollectionId != null && request is LocalFoodRequestV1.Send)
            return refusal("food.financial.same_reservation_send_required")
        if (observed?.financialCollectionId != null && request is LocalFoodRequestV1.Add)
            return refusal("food.financial.commercial_lock")
        return try {
            if (request is LocalFoodRequestV1.Create) create(request, bounded, context)
            else {
                val order = foodOrder(bounded, orderId)
                when (request) {
                    is LocalFoodRequestV1.Add -> add(request, order, bounded, context)
                    is LocalFoodRequestV1.EditServiceDetails -> editServiceDetails(request, order, context)
                    is LocalFoodRequestV1.Send -> send(request, order, bounded, context)
                    is LocalFoodRequestV1.Station, is LocalFoodRequestV1.Serve, is LocalFoodRequestV1.Cancel, is LocalFoodRequestV1.DiscardStarted ->
                        portionChange(request, order, bounded, context)
                    is LocalFoodRequestV1.OfferHandoff, is LocalFoodRequestV1.AcceptHandoff, is LocalFoodRequestV1.CancelHandoff ->
                        if (order.value.financialCollectionId == null && request.foodHandoffFinancialV1() != null)
                            refusal("food.handoff.financial_expectation_mismatch")
                        else foodPreparePaidHandoff(request, order, bounded, context)
                    is LocalFoodRequestV1.Create -> error("food.create_dispatch")
                }
            }
        } catch (_: FoodCapacityExceeded) {
            refusal("food.capacity.required")
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            // In particular, an issued portion's CURRENT transitive reservation closure can
            // grow after its send. This is known capacity, not corruption or a lost commit.
            refusal("food.capacity.required")
        }
    }

    private suspend fun create(request: LocalFoodRequestV1.Create, view: CommandReadView, context: CommandContext): PreparedMutation {
        val exists = foodRead(view, LocalFoodProfileV1.ORDER, request.orderId) != null
        val owner = foodOpenOwnerIndex(view, context.originalActorId)
        val admission = foodWorkAdmission(view, context.originalActorId)
        val source = recipes.prepare(view, request.activity, request.service, request.expectedCatalogRevision, request.portions)
        val portionExists = request.portions.map { foodRead(view, LocalFoodProfileV1.PORTION, it.portionId) }.any { it != null }
        val batchId = DomainId(context.operationId.value)
        check(foodRead(view, LocalFoodProfileV1.PRICE_BATCH, batchId) == null)
        return FoodPrepared(context, SecurityAction.ORDER_CREATE, request.orderId, Revision(0), context.originalActorId,
            setOf(orderResource(request.orderId), orderResource(context.originalActorId), orderResource(batchId)) +
                request.portions.map { orderResource(it.portionId) } + admission.resources + source?.recoveryResources.orEmpty()) { at ->
            if (exists || portionExists) return@FoodPrepared foodReject("food.identity.used")
            if (!admission.accepting) return@FoodPrepared foodReject("food.owner.closing")
            if (foodOpenOrderIds(owner).size >= LocalFoodProfileV1.MAX_ACTIVE_INDEX) return@FoodPrepared foodReject("food.capacity.required")
            if (source == null) return@FoodPrepared foodReject("food.recipe.current_source_required")
            check(source.scope == context.scope)
            val quoted = source.quote(batchId, at)
            val writes = FoodWrites(context, at)
            val portions = addQuoted(writes, request.orderId, request.portions, batchId, quoted)
            val order = writes.put(LocalFoodProfileV1.ORDER, request.orderId, FoodBodyV1.Order(context.originalActorId, context.originalActorId,
                request.activity, request.service, portions.map { it.id }, listOf(batchId), quoted.quote.payable))
            writes.put(LocalFoodProfileV1.OWNER_INDEX, context.originalActorId,
                FoodBodyV1.OwnerIndex(foodOpenOrderIds(owner) + request.orderId), owner)
            writes.apply(foodResult("food.order.created", order, portions))
        }
    }

    /** Label-only owner edit remains separate from preparation and any locked collection. */
    private fun editServiceDetails(request: LocalFoodRequestV1.EditServiceDetails, order: FoodOrderCut,
        context: CommandContext): PreparedMutation = FoodPrepared(context, SecurityAction.ORDER_EDIT,
        request.orderId, request.expectedOrderRevision, order.value.ownerId, foodResources(order)) { at ->
        ownerOrder(context, order, request.expectedOrderRevision)
        val writes = FoodWrites(context, at)
        val after = writes.put(LocalFoodProfileV1.ORDER, request.orderId, order.value.copy(
            service = order.value.service.copy(reference = request.reference, seatNote = request.seatNote)), order.order)
        writes.apply(foodResult(foodResultCode(request), after))
    }

    private suspend fun add(request: LocalFoodRequestV1.Add, order: FoodOrderCut, view: CommandReadView, context: CommandContext): PreparedMutation {
        val admission = foodWorkAdmission(view, order.value.ownerId)
        val source = recipes.prepare(view, order.value.activity, order.value.service, request.expectedCatalogRevision, request.portions)
        val exists = request.portions.map { foodRead(view, LocalFoodProfileV1.PORTION, it.portionId) }.any { it != null }
        return FoodPrepared(context, SecurityAction.ORDER_EDIT, request.orderId, request.expectedOrderRevision, order.value.ownerId,
            foodResources(order) + request.portions.map { orderResource(it.portionId) } +
                orderResource(DomainId(context.operationId.value)) + admission.resources + source?.recoveryResources.orEmpty()) { at ->
            ownerOrder(context, order, request.expectedOrderRevision)
            if (!admission.accepting) return@FoodPrepared foodReject("food.owner.closing")
            if (exists) return@FoodPrepared foodReject("food.identity.used")
            if (order.value.portionIds.size + request.portions.size > LocalFoodProfileV1.MAX_ORDER_PORTIONS) return@FoodPrepared foodReject("food.capacity.required")
            if (source == null) return@FoodPrepared foodReject("food.recipe.current_source_required")
            val batchId = DomainId(context.operationId.value)
            val quoted = source.quote(batchId, at)
            if (quoted.quote.payable.minor > Long.MAX_VALUE - order.value.originalQuotedTotal.minor)
                return@FoodPrepared foodReject("food.capacity.required")
            val writes = FoodWrites(context, at)
            val portions = addQuoted(writes, request.orderId, request.portions, batchId, quoted)
            val after = writes.put(LocalFoodProfileV1.ORDER, request.orderId, order.value.copy(
                portionIds = order.value.portionIds + portions.map { it.id }, batchIds = order.value.batchIds + batchId,
                originalQuotedTotal = order.value.originalQuotedTotal + quoted.quote.payable), order.order)
            writes.apply(foodResult("food.order.added", after, portions))
        }
    }

    private suspend fun send(request: LocalFoodRequestV1.Send, order: FoodOrderCut, view: CommandReadView, context: CommandContext): PreparedMutation {
        val selected = request.portionIds.filter { it in order.value.portionIds }.map { foodPortion(view, order, it, recipes) }
        val portions = selected.map { it.envelope.body as FoodBodyV1.Portion }
        val kitchenRestriction = foodKitchenExecutionRestriction(view, recipes, order, selected)
        val current = portions.map { recipes.currentForSend(view, it.recipe) }.all { it }
        val stations = portions.flatMap { it.recipe.routes }.map { it.stationId }.distinct()
        val indexes = stations.associateWith { foodRead(view, LocalFoodProfileV1.STATION_INDEX, it) }
        val skuIds = portions.flatMap { it.recipe.ingredients }.map { it.skuId }.distinct()
        val ingredientCount = portions.sumOf { it.recipe.ingredients.size }
        var capacity = skuIds.size > LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND || ingredientCount > LocalStockStoreProfile.MAX_LINES_PER_COMMAND
        val cut = if (capacity || selected.size != request.portionIds.size) null else try { stock.observe(view, skuIds) }
        catch (_: LocalStockHydrationCapacityUnavailable) { capacity = true; null }
        val used = portions.any { portion ->
            view.claim(LocalStockStoreProfile.reservationClaim(foodReservationId(portion.selection.portionId))) != null ||
                view.claim(LocalStockStoreProfile.sourceClaim(foodIssueId(portion.selection.portionId))) != null
        }
        return FoodPrepared(context, SecurityAction.ORDER_EDIT, request.orderId, request.expectedOrderRevision, order.value.ownerId,
            foodResources(order, selected) + portions.flatMap { portion -> listOf(
                RecoveryResourceKey(RecoveryResourceKind.STOCK, foodReservationId(portion.selection.portionId)),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, foodIssueId(portion.selection.portionId))) +
                portion.recipe.routes.map { orderResource(foodTaskId(portion.selection.portionId, it.stationId)) }
            } + cut?.let(stock::resources).orEmpty(), kitchenRestriction) { at ->
            ownerOrder(context, order, request.expectedOrderRevision)
            if (selected.size != request.portionIds.size) return@FoodPrepared foodReject("food.portion.unavailable")
            if (capacity) return@FoodPrepared foodReject("food.capacity.required")
            if (!current) return@FoodPrepared foodReject("food.recipe.changed_requires_replacement")
            if (used || portions.any { it.preparation.status != FoodPreparationStatus.NOT_SENT }) return@FoodPrepared foodReject("food.portion.already_sent")
            val actual = checkNotNull(cut)
            val requests = portions.flatMap { portion -> portion.recipe.ingredients.map { ingredient -> LocalStockAllocationRequest(
                foodIngredientLine(portion.selection.portionId, ingredient.componentId), ingredient.skuId,
                portion.recipe.warehouseId, ingredient.requiredBaseQuantity)
            } }
            val allocations = stock.allocate(actual, request.orderId, order.order.envelope.revision, requests, at)
            val issues = portions.map { portion ->
                val lines = portion.recipe.ingredients.map { foodIngredientLine(portion.selection.portionId, it.componentId) }.toSet()
                FoodIngredientIssue(foodReservationId(portion.selection.portionId), foodIssueId(portion.selection.portionId),
                    CollectionBinding(request.orderId, order.order.envelope.revision, foodStockContextId(request.orderId), context.originalActorId, CollectionExposure.UNCOLLECTED),
                    allocations.filter { it.lineId in lines }, RecipeIssueRequest(portion.recipe.snapshotId, portion.recipe.routes.map { it.stationId }))
            }
            val plan = FoodInventoryPlanner.sendUncollected(actual.snapshot, actual.snapshot.revision, context.operationId, issues, at.value.toEpochMilliseconds())
            val contribution = stock.project(actual, context, plan, at)
            val writes = FoodWrites(context, at)
            val changed = selected.map { row ->
                val portion = row.envelope.body as FoodBodyV1.Portion
                val id = portion.selection.portionId
                val tasks = portion.recipe.routes.map { FoodStationTask(foodTaskId(id, it.stationId), it.stationId, it.mode, FoodStationStatus.QUEUED) }
                val next = FoodPreparation.sent(portion.preparation, portion.preparation.revision, foodReservationId(id), foodIssueId(id), tasks, at)
                val issue = checkNotNull(plan.nextSnapshot.issue(foodIssueId(id)))
                requireFoodIssueRecipe(issue, portion)
                writes.put(LocalFoodProfileV1.PORTION, id, portion.copy(preparation = next,
                    originalIssue = InventoryRecordCodec.encodeIssue(issue, actual.snapshot.scope)), row)
            }
            for (station in stations) {
                val index = indexes[station]
                val before = (index?.envelope?.body as? FoodBodyV1.StationIndex)?.tasks.orEmpty()
                val additions = changed.map { it.body as FoodBodyV1.Portion }.filter { p -> p.preparation.tasks.any { it.stationId == station } }
                    .map { FoodTaskLocatorV1(foodTaskId(it.selection.portionId, station), request.orderId, it.selection.portionId) }
                if (before.size + additions.size > LocalFoodProfileV1.MAX_ACTIVE_INDEX) return@FoodPrepared foodReject("food.station.capacity_required")
                writes.put(LocalFoodProfileV1.STATION_INDEX, station, FoodBodyV1.StationIndex(before + additions), index)
            }
            val after = writes.put(LocalFoodProfileV1.ORDER, request.orderId, order.value, order.order)
            val jobs = changed.flatMap { row -> foodDeliveryJobs(order.value, row.body as FoodBodyV1.Portion, "PREPARE") }
            writes.apply(foodResult("food.sent", after, changed), jobs, stock, contribution,
                changed.flatMap { (it.body as FoodBodyV1.Portion).preparation.tasks }.map {
                    ClaimChange.Acquire(LocalFoodProfileV1.claim("task", it.taskId), request.orderId)
                })
        }
    }

    private suspend fun portionChange(request: LocalFoodRequestV1, order: FoodOrderCut, view: CommandReadView, context: CommandContext): PreparedMutation {
        val id = request.foodPortionId()
        val selected = if (id in order.value.portionIds) foodPortion(view, order, id, recipes) else null
        val portion = selected?.envelope?.body as? FoodBodyV1.Portion
        val currentStock = if (portion == null || portion.preparation.issueId == null) null else stock.observe(view,
            portion.recipe.ingredients.map { it.skuId }.distinct(), issueIds = listOf(checkNotNull(portion.preparation.issueId)))
        val indexRows = portion?.preparation?.tasks.orEmpty().associate { it.stationId to foodRead(view, LocalFoodProfileV1.STATION_INDEX, it.stationId) }
        return FoodPrepared(context, foodAction(request), foodTarget(request), request.foodOrderRevision(), order.value.ownerId,
            foodResources(order, listOfNotNull(selected)) + currentStock?.let(stock::resources).orEmpty() +
                if (request is LocalFoodRequestV1.Station && request.confirmationId != null) setOf(orderResource(request.confirmationId),
                    RecoveryResourceKey(RecoveryResourceKind.STOCK, foodId("restoration", context.operationId.value))) else emptySet()) { at ->
            foodRule(order.order.envelope.revision == request.foodOrderRevision(), SalesRule.REVISION_CONFLICT)
            if (selected == null || portion == null) return@FoodPrepared foodReject("food.portion.unavailable")
            if (request !is LocalFoodRequestV1.Station && request !is LocalFoodRequestV1.DiscardStarted) ownerOrder(context, order, request.foodOrderRevision())
            val before = portion.preparation
            foodRule(before.revision == request.foodPortionRevision(), SalesRule.REVISION_CONFLICT)
            if (request is LocalFoodRequestV1.Cancel && order.value.financialCollectionId != null && before.status == FoodPreparationStatus.NOT_SENT)
                return@FoodPrepared foodReject("food.financial.unsent_cancellation_required")
            val stationId = when (request) { is LocalFoodRequestV1.Station -> request.stationId; is LocalFoodRequestV1.DiscardStarted -> request.stationId; else -> null }
            if (stationId != null && before.tasks.none { it.stationId == stationId }) return@FoodPrepared foodReject("food.station.not_assigned")
            val issue = currentStock?.snapshot?.issue(checkNotNull(before.issueId))
            if (issue != null) requireCurrentFoodIssue(portion, issue)
            var plan: InventoryPlan? = null
            val next = when (request) {
                is LocalFoodRequestV1.Station -> when (request.action) {
                    LocalFoodStationActionV1.START -> {
                        val after = FoodPreparation.start(before, before.revision, request.stationId, at)
                        val cut = checkNotNull(currentStock)
                        plan = InventoryPlanner.acknowledgeKitchenStart(cut.snapshot, cut.snapshot.revision, context.operationId,
                            checkNotNull(issue).issueId, issue.revision, request.stationId)
                        after
                    }
                    LocalFoodStationActionV1.READY -> FoodPreparation.ready(before, before.revision, request.stationId, at)
                    LocalFoodStationActionV1.CONFIRM_UNSTARTED -> {
                        val after = FoodPreparation.confirmUnstarted(before, before.revision, request.stationId, checkNotNull(request.confirmationId),
                            context.originalActorId, checkNotNull(request.returnDisposition), at)
                        if (after.status == FoodPreparationStatus.CANCELLED_BEFORE_START) {
                            val cut = checkNotNull(currentStock)
                            val original = checkNotNull(issue)
                            val quarantine = after.tasks.any { it.returnDisposition == FoodIngredientReturnDisposition.QUARANTINE }
                            val destinations = original.allocations.map { allocation ->
                                val sourcePosition = checkNotNull(cut.snapshot.position(allocation.allocation.position))
                                val unsafe = quarantine || !sourcePosition.isSellableAt(at.value.toEpochMilliseconds())
                                StockRestoration(allocation.allocation.allocationId, allocation.allocation.quantity,
                                    allocation.allocation.position.copy(custody = if (unsafe) StockCustody.QUARANTINE else StockCustody.RELEASED))
                            }
                            plan = InventoryPlanner.cancelRecipeBeforeStart(cut.snapshot, cut.snapshot.revision, context.operationId,
                                foodId("restoration", context.operationId.value), KitchenCancellationConfirmation(checkNotNull(request.confirmationId),
                                    original.issueId, original.revision, context.originalActorId, after.tasks.map { it.stationId }), destinations, at.value.toEpochMilliseconds())
                        }
                        after
                    }
                }
                is LocalFoodRequestV1.Cancel -> FoodPreparation.requestCancellation(before, before.revision, request.reasonId, at)
                is LocalFoodRequestV1.DiscardStarted -> {
                    val after = FoodPreparation.discardStarted(before, before.revision)
                    val cut = checkNotNull(currentStock)
                    plan = InventoryPlanner.cancelRecipeAfterStart(cut.snapshot, cut.snapshot.revision, context.operationId,
                        checkNotNull(issue).issueId, issue.revision, checkNotNull(before.cancellationReasonId))
                    after
                }
                is LocalFoodRequestV1.Serve -> {
                    if (order.value.service.channel != LocalSalesChannel.DINE_IN) return@FoodPrepared foodReject("food.collection.required_before_pickup")
                    FoodPreparation.serve(before, before.revision, at)
                }
                else -> error("food.portion_action")
            }
            val writes = FoodWrites(context, at)
            val changed = writes.put(LocalFoodProfileV1.PORTION, id, portion.copy(preparation = next), selected)
            val finished = next.status in setOf(FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START) ||
                next.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER
            if (finished && before.tasks.isNotEmpty()) for (task in before.tasks) {
                val index = checkNotNull(indexRows[task.stationId])
                val body = index.envelope.body as FoodBodyV1.StationIndex
                check(body.tasks.any { it.taskId == task.taskId && it.portionId == id && it.orderId == request.foodOrderId() })
                writes.put(LocalFoodProfileV1.STATION_INDEX, task.stationId, body.copy(tasks = body.tasks.filterNot { it.taskId == task.taskId }), index)
            }
            val afterOrder = writes.put(LocalFoodProfileV1.ORDER, request.foodOrderId(), order.value, order.order)
            val jobs = if (request is LocalFoodRequestV1.Cancel && before.tasks.isNotEmpty())
                foodDeliveryJobs(order.value, changed.body as FoodBodyV1.Portion, "CANCEL_REQUEST_CONFIRM_REQUIRED") else emptyList()
            val contribution = plan?.let { stock.project(checkNotNull(currentStock), context, it, at) }
            writes.apply(foodResult(foodResultCode(request), afterOrder, listOf(changed)), jobs,
                if (contribution == null) null else stock, contribution,
                if (request is LocalFoodRequestV1.Station && request.confirmationId != null)
                    listOf(ClaimChange.Acquire(LocalFoodProfileV1.claim("confirmation", request.confirmationId), id)) else emptyList())
        }
    }


}

private fun addQuoted(writes: FoodWrites, orderId: DomainId, selections: List<LocalFoodPortionSelectionV1>,
    batchId: DomainId, quoted: LocalFoodQuotedBatchV1): List<FoodEnvelopeV1> {
    check(quoted.quote.snapshotId == batchId && quoted.quote.revision == Revision(0) && quoted.snapshots.size == selections.size &&
        quoted.quote.lines.map { it.portionId }.toSet() == selections.map { it.portionId }.toSet())
    writes.put(LocalFoodProfileV1.PRICE_BATCH, batchId, FoodBodyV1.PriceBatch(orderId, StoredPricingSnapshotCodec.encode(quoted.quote), quoted.priceSources))
    return selections.zip(quoted.snapshots).map { (selection, recipe) ->
        writes.put(LocalFoodProfileV1.PORTION, selection.portionId, FoodBodyV1.Portion(orderId, selection, batchId, recipe,
            FoodPreparationState(selection.portionId, Revision(0), FoodPreparationStatus.NOT_SENT)))
    }
}
internal fun foodDeliveryJobs(order: FoodBodyV1.Order, portion: FoodBodyV1.Portion, instruction: String): List<JobIntent> =
    portion.preparation.tasks.map { task -> JobIntent(LocalFoodProfileV1.DELIVERY_JOB, localSalesEncode(FoodDeliveryV1(
        portion.orderId, portion.selection.portionId, task.taskId, task.stationId, portion.preparation.revision, task.deliveryMode,
        instruction, portion.recipe.description, portion.selection.quantity, portion.selection.itemNote, order.service.reference,
        portion.selection.seatNote), FoodDeliveryV1.serializer(), 4_096)) }
internal fun requireCurrentFoodIssue(portion: FoodBodyV1.Portion, issue: StockIssue) {
    check(issue.issueId == portion.preparation.issueId && issue.revision == portion.preparation.issueRevision)
    val original = InventoryRecordCodec.decodeIssue(checkNotNull(portion.originalIssue),
        issue.allocations.first().allocation.position.pool.scope, issue.issueId, Revision(1))
    val disposition = when (portion.preparation.status) {
        FoodPreparationStatus.CANCELLED_BEFORE_START -> KitchenDisposition.CANCELLED_BEFORE_START
        FoodPreparationStatus.WASTE_AFTER_START -> KitchenDisposition.WASTE_AFTER_START
        else -> KitchenDisposition.ACTIVE
    }
    check(issue.kitchen?.disposition == disposition && issue.kitchen?.recipeSnapshotId == original.kitchen?.recipeSnapshotId &&
        issue.kitchen?.requiredStationIds == original.kitchen?.requiredStationIds && issue.allocations.all {
            if (disposition == KitchenDisposition.CANCELLED_BEFORE_START) it.restoredQuantity == it.allocation.quantity else it.restoredQuantity.scaled == 0L
        })
    check(issue.reservationId == original.reservationId && issue.binding == original.binding && issue.purpose == original.purpose &&
        issue.allocations.map { it.copy(restoredQuantity = Quantity(it.allocation.quantity.unitId, 0, it.allocation.quantity.scale)) } == original.allocations &&
        issue.kitchen?.startedStationIds?.toSet() == portion.preparation.tasks.filter { it.startedAt != null }.map { it.stationId }.toSet())
}
private fun ownerOrder(context: CommandContext, order: FoodOrderCut, expected: Revision) {
    foodRule(order.order.envelope.revision == expected, SalesRule.REVISION_CONFLICT)
    foodRule(order.value.ownerId == context.originalActorId, SalesRule.OWNER_REQUIRED)
    foodRule(order.value.handoff == null, SalesRule.HANDOFF_CONFLICT)
}

internal class FoodWorkAdmission(val accepting: Boolean, val resources: Set<RecoveryResourceKey>)
internal suspend fun foodWorkAdmission(view: CommandReadView, ownerId: DomainId): FoodWorkAdmission {
    val assignment = LocalSalesStorage.custodian(view, ownerId)
    val resources = setOf(cashResource(ownerId), orderResource(ownerId), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, ownerId))
    val sessionId = assignment.value.activeSessionId ?: return FoodWorkAdmission(true, resources)
    val cash = LocalSalesStorage.cash(view, sessionId)
    return FoodWorkAdmission(cash.session.value.status == CashSessionStatus.OPEN, resources + cashResources(cash))
}

/** Closed body classification shared by legacy and normalized kitchen routes. */
internal fun LocalFoodRequestV1.foodKitchenRpcStation(): LocalFoodRequestV1.Station? {
    val station = this as? LocalFoodRequestV1.Station ?: return null
    return station.takeIf { it.action == LocalFoodStationActionV1.START || it.action == LocalFoodStationActionV1.READY }
}

/** Original disclosure uses the existing exact Station binding, without a fresh task read. */
internal fun foodKitchenRpcStationResultRequirement(station: LocalFoodRequestV1.Station,
    context: CommandContext): AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.KITCHEN_TASK, PermissionVerb.PERFORM), context.scope,
        ActionBinding(station.stationId, station.expectedOrderRevision, context.semanticDigest, context.operationId, "food.operation"))

internal class FoodPrepared(private val context: CommandContext, action: SecurityAction, targetId: DomainId, revision: Revision,
    private val ownerId: DomainId, resources: Set<RecoveryResourceKey>,
    override val admissionRestriction: PreparedMutationAdmissionRestriction? = null,
    private val proposal: (UtcInstant) -> ProposedOutcome) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
        ActionBinding(targetId, revision, context.semanticDigest, context.operationId, "food.operation"),
        owner = if (action.ownerPrivate) OwnerConstraint.OwnerPrivate(ownerId) else OwnerConstraint.Unrestricted)
    override val affectedResources: Set<RecoveryResourceKey> = resources + orderResource(targetId) +
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)
    override val allowsActionApproval: Boolean = false
    // Conservative until aliases and independent food recovery evidence are qualified.
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        // Current LocalMasterEndpoint/AtomicMasterStore ingress is employee-only. Real service
        // credentials, audit and result recovery require a separately qualified future route.
        check(admissionRestriction == null && authorization.isBoundTo(requirement, context.originalActorId) &&
            authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null)
        return try { proposal(recordedAt) }
        catch (_: FoodCapacityExceeded) { foodReject("food.capacity.required") }
        catch (failure: SalesRuleViolation) { foodReject("food.rule.${failure.rule.name.lowercase()}") }
        catch (failure: LocalStockRejected) {
            when (failure.failure) {
                LocalStockFailure.INSUFFICIENT_STOCK -> foodReject("food.stock.shortage")
                LocalStockFailure.BOUND_EXCEEDED -> foodReject("food.capacity.required")
                else -> throw failure
            }
        }
        catch (failure: InventoryRejection) {
            when (failure.failure) {
                InventoryFailure.INSUFFICIENT_STOCK, InventoryFailure.UNSAFE_STOCK -> foodReject("food.stock.unavailable")
                InventoryFailure.BOUND_EXCEEDED -> foodReject("food.capacity.required")
                else -> throw failure
            }
        }
        catch (failure: LocalSalesQuoteUnavailable) { foodReject("food.quote.${failure.reason.name.lowercase()}") }
        catch (_: LocalSalesWireCapacityExceeded) { foodReject("food.capacity.required") }
    }
}

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Request-confined complete normalized observations, never a second stock ledger. */
internal class FoodFinancialLegacyStockCut(val planning: LocalStockReadCut,
    val reservations: Map<DomainId, StockReservation>, val issues: Map<DomainId, StockIssue>,
    val observed: List<FoodFinancialObservation>, val resources: Set<RecoveryResourceKey>)

internal suspend fun foodFinancialLegacyStock(view: CommandReadView, stock: LocalStockRepository,
    portions: List<FoodRow>, collection: FoodCollection? = null): FoodFinancialLegacyStockCut {
    val bodies = portions.map { it.envelope.body as FoodBodyV1.Portion }
    val skuIds = bodies.flatMap { it.recipe.ingredients }.map { it.skuId }.distinct()
    foodCapacity(skuIds.size <= LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND)
    val reservations = collection?.portions?.map { it.reservationId }
        ?: bodies.mapNotNull { it.preparation.reservationId }
    val issues = bodies.mapNotNull { it.preparation.issueId }
    // Each loader still verifies the FULL current transitive reservation/value/source closure.
    // Partitioning explicit issue lookups does not truncate it; all loaders share FoodReadView.
    val chunks = if (issues.isEmpty()) listOf(emptyList()) else issues.chunked(16)
    val cuts = chunks.map { stock.observe(view, skuIds, reservations, it) }
    val observed = linkedMapOf<AggregateKey, FoodFinancialObservation>()
    val reserved = linkedMapOf<DomainId, StockReservation>()
    val issued = linkedMapOf<DomainId, StockIssue>()
    for (cut in cuts) {
        for (id in reservations) {
            val value = cut.snapshot.reservation(id) ?: error("food.financial.reservation_missing")
            val stamp = stock.observedReservationSource(cut, id) ?: error("food.financial.reservation_source_missing")
            val input = foodObservedStock(view, stamp, value.revision, InventoryRecordCodec.encodeReservation(value, cut.snapshot.scope))
            observed[input.input.key()]?.let { check(it.input == input.input) }
            observed[input.input.key()] = input; reserved[id] = value
        }
        for (id in issues) {
            val value = cut.snapshot.issue(id) ?: continue // Explicitly in another captured chunk, checked below.
            val stamp = stock.observedIssueSource(cut, id) ?: error("food.financial.issue_source_missing")
            val input = foodObservedStock(view, stamp, value.revision, InventoryRecordCodec.encodeIssue(value, cut.snapshot.scope))
            observed[input.input.key()]?.let { check(it.input == input.input) }
            observed[input.input.key()] = input; issued[id] = value
        }
    }
    check(reserved.keys == reservations.toSet() && issued.keys == issues.toSet())
    for (portion in bodies) {
        val issue = portion.preparation.issueId?.let { issued.getValue(it) }
        if (issue != null) {
            requireCurrentFoodIssue(portion, issue)
            val reservation = reserved.getValue(checkNotNull(portion.preparation.reservationId))
            check(reservation.status == ReservationStatus.ISSUED && reservation.binding == issue.binding &&
                reservation.allocations == issue.allocations.map { it.allocation })
        }
        if (collection != null) {
            val link = collection.portions.single { it.portionId == portion.selection.portionId }
            val reservation = reserved.getValue(link.reservationId)
            requireFoodReservationRecipe(reservation, portion)
            if (link.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION) {
                check(reservation.binding == foodFinancialStockBinding(collection) && reservation.expiresAtEpochMillis == null)
                if (collection.cancellationRefundId != null) check(reservation.status == ReservationStatus.RELEASED &&
                    portion.preparation.issueId == null && portion.preparation.status == FoodPreparationStatus.CANCELLED_BEFORE_START)
                else if (issue == null) check(reservation.retainsStockClaim && portion.preparation.status == FoodPreparationStatus.NOT_SENT)
                else check(portion.financialCollectionId == collection.id && issue.reservationId == link.reservationId)
            } else check(issue != null && link.priorIssueId == issue.issueId && issue.binding.exposure == CollectionExposure.UNCOLLECTED &&
                issue.binding.collectionId == foodStockContextId(collection.orderId))
        }
    }
    return FoodFinancialLegacyStockCut(cuts.first(), reserved, issued, observed.values.toList(), cuts.flatMap { stock.resources(it) }.toSet())
}

internal fun requireFoodReservationRecipe(reservation: StockReservation, portion: FoodBodyV1.Portion) {
    check(reservation.reservationId == foodReservationId(portion.selection.portionId) && reservation.binding.orderId == portion.orderId &&
        reservation.allocations.isNotEmpty() && reservation.allocations.all { it.position.serialId == null &&
            it.position.pool.warehouseId == portion.recipe.warehouseId && it.position.pool.ownership == StockOwnership.CompanyOwned &&
            it.position.custody == StockCustody.RELEASED })
    val grouped = reservation.allocations.groupBy { it.lineId }
    check(grouped.keys == portion.recipe.ingredients.map { foodIngredientLine(portion.selection.portionId, it.componentId) }.toSet())
    for (ingredient in portion.recipe.ingredients) {
        val lines = grouped.getValue(foodIngredientLine(portion.selection.portionId, ingredient.componentId))
        check(lines.all { it.position.pool.skuId == ingredient.skuId } && lines.fold(
            Quantity(ingredient.requiredBaseQuantity.unitId, 0, ingredient.requiredBaseQuantity.scale)) { amount, line -> amount + line.quantity
        } == ingredient.requiredBaseQuantity)
    }
}
internal fun foodFinancialStockBinding(collection: FoodCollection): CollectionBinding = CollectionBinding(collection.orderId,
    collection.lockedCommercialRevision, collection.id, collection.collectorId, CollectionExposure.UNKNOWN)

internal fun foodFinancialObservations(cut: FoodFinancialCollectionCut): List<FoodFinancialObservation> =
    listOf(foodObserved(cut.row), foodObserved(cut.order.order), foodObserved(cut.order.ownerIndex)) +
        cut.portions.map(::foodObserved) + cut.batches.map(::foodObserved)

internal class FoodFinancialPrepared(private val context: CommandContext, action: SecurityAction,
    targetId: DomainId, revision: Revision, ownerId: DomainId, private val reason: String, amount: Money? = null,
    resources: Set<RecoveryResourceKey>,
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION),
    override val admissionRestriction: PreparedMutationAdmissionRestriction? = null,
    private val proposal: (SalesCommand, UtcInstant) -> ProposedOutcome) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(action, context.scope, targetId, revision,
        context.operationId, context.semanticDigest, reason, ownerId, amount)
    override val affectedResources: Set<RecoveryResourceKey> = resources + orderResource(targetId) +
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)
    override val allowsActionApproval: Boolean = false
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(admissionRestriction == null && authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.isBoundTo(requirement, context.originalActorId))
        return try { proposal(SalesCommand(context.operationId, authorization, recordedAt.value, context.semanticDigest, reason), recordedAt) }
        catch (_: FoodCapacityExceeded) { foodFinancialReject("food.financial.capacity_required") }
        catch (_: LocalSalesWireCapacityExceeded) { foodFinancialReject("food.financial.capacity_required") }
        catch (failure: SalesRuleViolation) { foodFinancialReject("food.financial.rule.${failure.rule.name.lowercase()}") }
        catch (failure: LocalStockRejected) {
            when (failure.failure) {
                LocalStockFailure.BOUND_EXCEEDED -> foodFinancialReject("food.financial.capacity_required")
                LocalStockFailure.INSUFFICIENT_STOCK -> foodFinancialReject("food.financial.stock_unavailable")
                else -> throw failure
            }
        }
        catch (failure: InventoryRejection) {
            when (failure.failure) {
                InventoryFailure.BOUND_EXCEEDED -> foodFinancialReject("food.financial.capacity_required")
                InventoryFailure.INSUFFICIENT_STOCK, InventoryFailure.UNSAFE_STOCK -> foodFinancialReject("food.financial.stock_unavailable")
                else -> throw failure
            }
        }
        // Corruption, arithmetic and unexpected exceptions remain uncertainty. They are not
        // a payment/refund outcome, recorded physical fact, empty work list or not-paid proof.
    }
}

internal fun foodFinancialRule(valid: Boolean, rule: SalesRule) { if (!valid) throw SalesRuleViolation(rule) }
/** Only encode a NEW domain proposal here. Stored snapshot decoding must never use this translation. */
internal inline fun <T> foodFinancialNewSnapshot(encode: () -> T): T = try { encode() }
catch (failure: SalesSnapshotRejectedException) {
    if (failure.failure != SalesSnapshotFailure.TOO_LARGE) throw failure
    throw FoodCapacityExceeded()
}
internal fun foodFinancialOwner(context: CommandContext, order: FoodOrderCut, revision: Revision) {
    foodFinancialRule(order.order.envelope.revision == revision, SalesRule.REVISION_CONFLICT)
    foodFinancialRule(order.value.ownerId == context.originalActorId, SalesRule.OWNER_REQUIRED)
    foodFinancialRule(order.value.handoff == null && order.value.closedByOperationId == null, SalesRule.HANDOFF_CONFLICT)
}

/** Scalar headers only; all per-lot members are verified by the same actual loan and streamed. */
internal class FoodFinancialRelationalStockCut(val read: LocalStockRelationalRead,
    val reservations: Map<DomainId, InventoryReservationHeader>, val issues: Map<DomainId, LocalStockIssueHeader>)
/** Ordinary read: genuine current proof, deliberately no posting capture. */
internal suspend fun foodFinancialRelationalStock(view: CommandReadView, stock: LocalStockRepository,
    portions: List<FoodRow>, collection: FoodCollection? = null): FoodFinancialRelationalStockCut =
    foodFinancialRelationalStock(view, stock, stock.observeRelational(view), portions, collection)

/** Mutations pass inventory.observations(): the exact transaction reader AND sink, not a clone. */
internal suspend fun foodFinancialRelationalStock(view: CommandReadView, stock: LocalStockRepository,
    read: LocalStockRelationalRead, portions: List<FoodRow>, collection: FoodCollection? = null): FoodFinancialRelationalStockCut {
    check(read.scope == view.scope)
    val reservations = linkedMapOf<DomainId, InventoryReservationHeader>()
    val issues = linkedMapOf<DomainId, LocalStockIssueHeader>()
    check(portions.size <= LocalFoodProfileV1.MAX_ORDER_PORTIONS)
    for (row in portions) {
        val portion = row.envelope.body as FoodBodyV1.Portion
        val link = collection?.portions?.single { it.portionId == row.envelope.id }
        val reservationId = link?.reservationId ?: portion.preparation.reservationId
        val reservation = reservationId?.let { id ->
            val header = read.reservation(id) ?: error("food.stock.current_reservation_missing")
            requireFoodReservationMembers(portion, header) { consume -> read.allocations(id, consume) }
            reservations[id] = header; header
        }
        val issue = portion.preparation.issueId?.let { id ->
            foodFinancialRule(portion.normalizedIssue != null, SalesRule.UNSUPPORTED_STOCK_TIMING)
            val header = read.issue(id) ?: error("food.stock.current_issue_missing")
            requireFoodCurrentIssueHeader(portion, header)
            requireFoodIssueMembers(portion, header) { consume -> read.issueAllocations(id, consume) }
            val first = read.issueOriginal(id) ?: error("food.stock.first_issue_missing")
            requireFoodFirstIssue(portion, first)
            stock.requireRelationalProducer(view, first.original)
            check(checkNotNull(reservation).status == ReservationStatus.ISSUED && reservation.issueId == id &&
                reservation.binding == header.binding && reservation.allocationCount == header.allocationCount)
            issues[id] = header; header
        }
        if (link != null) {
            val header = checkNotNull(reservation)
            if (link.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION) {
                check(header.binding == foodFinancialStockBinding(checkNotNull(collection)) && header.expiresAtEpochMillis == null)
                if (collection.cancellationRefundId != null) check(header.status == ReservationStatus.RELEASED && issue == null &&
                    portion.preparation.status == FoodPreparationStatus.CANCELLED_BEFORE_START)
                else if (issue == null) check(header.retainsStockClaim && portion.preparation.status == FoodPreparationStatus.NOT_SENT)
                else check(portion.financialCollectionId == collection.id && issue.reservationId == link.reservationId)
            } else check(issue != null && issue.id == link.priorIssueId && issue.binding.exposure == CollectionExposure.UNCOLLECTED &&
                issue.binding.collectionId == foodStockContextId(collection.orderId))
        }
    }
    return FoodFinancialRelationalStockCut(read, reservations, issues)
}
internal suspend fun foodRequireNormalizedCurrentIssue(view: CommandReadView, stock: LocalStockRepository,
    row: FoodRow) { foodFinancialRelationalStock(view, stock, listOf(row)) }

/** Read-only financial custody for a kitchen confirmation. A station actor is not the collector
 * or the current financial owner. Neither an unknown payment nor an outstanding handoff grants
 * stock availability, changes responsibility, or authorizes a refund here. */
internal suspend fun foodKitchenFinancialCut(view: CommandReadView, order: FoodOrderCut,
    recipes: TransactionFoodRecipeSourceV1): FoodFinancialCollectionCut? {
    val id = order.value.financialCollectionId ?: return null
    val row = foodFinancialRead(view, LocalFoodFinancialProfileV1.COLLECTION, id)
        ?: error("food.stock.kitchen_collection_missing")
    val value = foodFinancialCollection(row.envelope)
    check(value.scope == view.scope && value.orderId == order.order.envelope.id &&
        value.currentOwnerId == order.value.ownerId && value.originalCreatorId == order.value.creatorId)
    val portions = order.value.portionIds.map { foodPortion(view, order, it, recipes) }
    val batches = order.value.batchIds.map { foodRead(view, LocalFoodProfileV1.PRICE_BATCH, it)
        ?: error("food.stock.kitchen_price_missing") }
    requireFoodFinancialQuote(value, order.value, portions.map { it.envelope }, batches.map { it.envelope })
    return FoodFinancialCollectionCut(row, value, order, portions, batches)
}

/** The portion itself must already have its genuine complete retained producer. These are the
 * actual permanent task/earlier-confirmation claims on the same loan, not invented authority. */
internal suspend fun requireFoodKitchenTaskSources(portion: FoodBodyV1.Portion,
    readClaim: suspend (ClaimKey) -> DomainId?) {
    val tasks = portion.preparation.tasks
    check(tasks.isNotEmpty() && tasks.size == portion.recipe.routes.size)
    val confirmations = tasks.mapNotNull { it.cancellationConfirmationId }
    check(confirmations.distinct().size == confirmations.size)
    for ((task, route) in tasks.zip(portion.recipe.routes)) {
        check(task.taskId == foodTaskId(portion.selection.portionId, route.stationId) &&
            task.stationId == route.stationId && task.deliveryMode == route.mode &&
            readClaim(LocalFoodProfileV1.claim("task", task.taskId)) == portion.orderId)
        task.cancellationConfirmationId?.let { id ->
            check(readClaim(LocalFoodProfileV1.claim("confirmation", id)) == portion.selection.portionId)
        }
    }
}

/** Only map the final genuine domain transition. Quantity, allocations and cost are deliberately
 * absent: stock restores the exact immutable original issue through its own same-sink proof. */
internal fun foodKitchenRestoration(operation: OperationId, portion: FoodBodyV1.Portion,
    after: FoodPreparationState, issue: LocalStockIssueHeader): LocalStockKitchenRestoration {
    requireFoodCurrentIssueHeader(portion, issue)
    check(portion.preparation.status == FoodPreparationStatus.CANCEL_PENDING && !portion.preparation.started &&
        after.portionId == portion.selection.portionId && after.status == FoodPreparationStatus.CANCELLED_BEFORE_START &&
        after.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && after.issueId == issue.id &&
        after.reservationId == issue.reservationId && after.issueRevision == issue.revision.next() &&
        after.tasks.map { it.taskId } == portion.preparation.tasks.map { it.taskId } &&
        after.tasks.map { it.stationId } == issue.requiredStationIds && after.tasks.all {
            it.status == FoodStationStatus.CANCEL_CONFIRMED && it.startedAt == null && it.readyAt == null
        })
    return LocalStockKitchenRestoration(foodId("restoration", operation.value), issue.id, issue.revision,
        after.tasks.map { task -> LocalStockKitchenConfirmation(task.stationId,
            checkNotNull(task.cancellationConfirmationId), checkNotNull(task.cancellationConfirmedBy),
            checkNotNull(task.cancellationConfirmedAt), when (checkNotNull(task.returnDisposition)) {
                FoodIngredientReturnDisposition.RELEASED -> StockCustody.RELEASED
                FoodIngredientReturnDisposition.QUARANTINE -> StockCustody.QUARANTINE
            }) })
}

internal fun foodKitchenConfirmationResources(state: FoodPreparationState): Set<RecoveryResourceKey> = buildSet {
    for (task in state.tasks) {
        add(RecoveryResourceKey(RecoveryResourceKind.KITCHEN_CUSTODY, task.stationId))
        add(RecoveryResourceKey(RecoveryResourceKind.KITCHEN_CUSTODY, task.taskId))
        task.cancellationConfirmationId?.let { id ->
            add(orderResource(id)); add(RecoveryResourceKey(RecoveryResourceKind.KITCHEN_CUSTODY, id))
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, checkNotNull(task.cancellationConfirmedBy)))
        }
    }
}

/** Explicit additive installation seam; core registry and native final settlement retain authority. */
public object LocalFoodStockServices {
    public fun handler(stock: LocalStockRepository, authority: LocalStockAuthorityReader,
        recipes: TransactionFoodStockSource, foodCheckRefusals: LocalFoodCheckRefusalSupport? = null): MasterCommandHandler =
        LocalFoodStockHandler(stock, authority, recipes, foodCheckRefusals)
    public fun producer(recipes: TransactionFoodStockSource): LocalStockRelationalCompositeVerifier = LocalFoodStockProducer(recipes)
}

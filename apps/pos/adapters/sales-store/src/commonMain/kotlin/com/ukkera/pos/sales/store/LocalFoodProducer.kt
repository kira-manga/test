package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/**
 * Closed retained whole-producer validator, not a command-name allowlist. Literal before/after
 * facts, result, every claim and job, and recipe/issue/cancellation/waste correspond exactly.
 * The stock repository additionally proves its complete normalized dependency/predecessor/value
 * crosswalk. The catalog verifier additionally authenticates returned signed recipe snapshots.
 * No current grants, source absence, money movement or physical print success is inferred here.
 */
public object LocalFoodProducer {
    public fun requireComplete(evidence: RetainedCommandEvidence): List<LocalFoodRecipeSnapshotV1> =
        if (isPaidFoodHandoffProducer(evidence)) LocalPaidFoodHandoffProducer.requireComplete(evidence)
        else if (isFoodHandoffProducerV2(evidence)) LocalHistoricalFoodHandoffProducer.requireComplete(evidence)
        else FoodProducerProof(evidence).validate()
    /** Additive closed normalized route; legacy bytes and the R2 handoff proof above are unchanged. */
    public suspend fun requireNormalizedComplete(original: RetainedStreamedCommand,
        stock: com.ukkera.pos.inventory.store.LocalStockRelationalInspection, recipes: TransactionFoodStockSource) {
        requireFoodNormalizedProducer(original, stock, recipes)
    }

}

private class FoodProducerProof(private val evidence: RetainedCommandEvidence) {
    private val request = LocalFoodCodecV1.decode(evidence.normalized)
    private val event = foodEvent(evidence)
    private val before = event.beforeIds.map { foodBefore(evidence, it).envelope }.associateBy { AggregateKey(it.kind, it.id) }
    private val after = evidence.aggregateWrites.filter { it.key.kind in LocalFoodProfileV1.kinds - setOf(LocalFoodProfileV1.BEFORE, LocalFoodProfileV1.EVENT) }
        .associate { it.key to foodEnvelope(it.payload) }
    private val used = mutableSetOf<AggregateKey>()
    private val extraClaims = mutableListOf<ClaimChange.Acquire>()
    private val orderedPortions = mutableListOf<FoodEnvelopeV1>()
    private var expectedJobs: List<JobIntent> = emptyList()
    private val stock: InventoryPostedRecords? = if (event.stockProduced) LocalStockRepository().inspectPosting(evidence) else null
    private val scope get() = InventoryScope(evidence.scope.companyId, evidence.scope.branchId)

    fun validate(): List<LocalFoodRecipeSnapshotV1> {
        check(request.foodHandoffFinancialV1() == null)
        check(evidence.committed && evidence.command == LocalFoodProfileV1.COMMAND && LocalFoodCodecV1.normalize(evidence.normalized) == evidence.normalized &&
            semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
        check(event.scope == evidence.scope && event.operationId == evidence.operationId && event.actorId == evidence.originalActorId && event.recordedAt == evidence.recordedAt &&
            event.beforeIds.distinct().size == event.beforeIds.size && before.size == event.beforeIds.size &&
            event.after.map { it.kind to it.id }.distinct().size == event.after.size && event.after.size == after.size)
        val expectedAllKeys = after.keys + event.beforeIds.map { AggregateKey(LocalFoodProfileV1.BEFORE, it) } +
            AggregateKey(LocalFoodProfileV1.EVENT, DomainId(evidence.operationId.value))
        check(evidence.aggregateWrites.filter { it.key.kind !in LocalStockStoreProfile.inventoryKinds }.map { it.key }.toSet() == expectedAllKeys &&
            evidence.aggregateWrites.map { it.key }.distinct().size == evidence.aggregateWrites.size &&
            evidence.aggregateWrites.all { sha256(it.payload.copyBytes()) == it.payloadDigest })
        if (!event.stockProduced) check(evidence.aggregateWrites.none { it.key.kind in LocalStockStoreProfile.inventoryKinds } &&
            evidence.claimChanges.none { it.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces })
        for ((key, value) in after) {
            val row = evidence.aggregateWrites.single { it.key == key }
            val reference = event.after.single { it.kind == key.kind && it.id == key.id }
            check(value.scope == evidence.scope && value.kind == key.kind && value.id == key.id && value.operationId == evidence.operationId &&
                value.revision == row.storageGeneration && reference.revision == row.storageGeneration && reference.digest == row.payloadDigest &&
                row.schema == LocalFoodProfileV1.schema(key.kind))
            val old = before[key]
            check(if (old == null) value.revision == Revision(1) else old.scope == evidence.scope && old.operationId != evidence.operationId && old.revision.next() == value.revision)
        }
        check(before.keys.all { it in after } && event.stockProduced == (stock != null))
        val orderKey = AggregateKey(LocalFoodProfileV1.ORDER, request.foodOrderId())
        val nextOrder = use(orderKey)
        val next = nextOrder.body as FoodBodyV1.Order
        val priorOrder = before[orderKey]
        if (request is LocalFoodRequestV1.Create) create(request, nextOrder, next)
        else {
            val prior = checkNotNull(priorOrder)
            check(prior.revision == request.foodOrderRevision())
            val old = prior.body as FoodBodyV1.Order
            check(old.closedByOperationId == null)
            if (old.financialCollectionId != null) check(request !is LocalFoodRequestV1.Add && request !is LocalFoodRequestV1.Send &&
                request !is LocalFoodRequestV1.OfferHandoff && request !is LocalFoodRequestV1.AcceptHandoff && request !is LocalFoodRequestV1.CancelHandoff)
            if (request !is LocalFoodRequestV1.Station && request !is LocalFoodRequestV1.DiscardStarted && request !is LocalFoodRequestV1.AcceptHandoff)
                check(old.ownerId == evidence.originalActorId)
            when (val selected = request) {
                is LocalFoodRequestV1.Add -> add(selected, old, next)
                is LocalFoodRequestV1.EditServiceDetails -> check(old.handoff == null && stock == null &&
                    next == old.copy(service = old.service.copy(reference = selected.reference, seatNote = selected.seatNote)))
                is LocalFoodRequestV1.Send -> { check(next == old && old.handoff == null); send(selected, old, prior.revision) }
                is LocalFoodRequestV1.Station, is LocalFoodRequestV1.Serve, is LocalFoodRequestV1.Cancel, is LocalFoodRequestV1.DiscardStarted -> {
                    check(next == old)
                    if (selected !is LocalFoodRequestV1.Station && selected !is LocalFoodRequestV1.DiscardStarted) check(old.handoff == null)
                    portionChange(selected, old)
                }
                is LocalFoodRequestV1.OfferHandoff -> {
                    check(old.handoff == null && selected.recipientId != old.ownerId && next == old.copy(handoff = FoodHandoffV1(selected.handoffId,
                        old.ownerId, selected.recipientId, evidence.recordedAt)) && stock == null)
                    extraClaims += ClaimChange.Acquire(LocalFoodProfileV1.claim("handoff", selected.handoffId), request.foodOrderId())
                }
                is LocalFoodRequestV1.AcceptHandoff -> {
                    check(old.handoff?.let { it.id == selected.handoffId && it.to == evidence.originalActorId && it.from == old.ownerId } == true &&
                        next == old.copy(ownerId = evidence.originalActorId, handoff = null) && stock == null)
                    ownerIndex(old.ownerId, request.foodOrderId(), false); ownerIndex(next.ownerId, request.foodOrderId(), true)
                    extraClaims += ClaimChange.Acquire(LocalFoodProfileV1.claim("accepted-handoff", selected.handoffId), request.foodOrderId())
                }
                is LocalFoodRequestV1.CancelHandoff -> check(old.handoff?.id == selected.handoffId && next == old.copy(handoff = null) && stock == null)
                is LocalFoodRequestV1.Create -> error("food.producer.create")
            }
        }
        check(after.keys == used)
        val expectedResult = foodResult(foodResultCode(request), nextOrder, orderedPortions)
        check(event.result == expectedResult && evidence.result == localSalesEncode(expectedResult, LocalFoodResultV1.serializer()) &&
            evidence.resultHash == sha256(evidence.result.copyBytes()))
        val claims = after.filterKeys { it !in before }.map { (key, _) -> ClaimChange.Acquire(LocalFoodProfileV1.claim(key.kind, key.id), key.id) } + extraClaims
        val actualClaims = evidence.claimChanges.filter { it.key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces }
        check(actualClaims.size == claims.size && actualClaims.all { it.action == RetainedClaimAction.ACQUIRE } &&
            claims.all { expected -> actualClaims.any { it.key == expected.key && it.ownerId == expected.ownerId } })
        check(evidence.jobs.size == expectedJobs.size && evidence.jobs.zip(expectedJobs).all { (actual, expected) -> actual.kind == expected.kind && actual.body == expected.body })
        return (before.values + after.values).mapNotNull { (it.body as? FoodBodyV1.Portion)?.recipe }.distinct()
    }

    private fun use(key: AggregateKey): FoodEnvelopeV1 = after.getValue(key).also { check(used.add(key)) }
    private fun create(request: LocalFoodRequestV1.Create, nextOrder: FoodEnvelopeV1, next: FoodBodyV1.Order) {
        check(before[AggregateKey(LocalFoodProfileV1.ORDER, request.orderId)] == null && stock == null)
        val batchId = DomainId(evidence.operationId.value)
        val total = newPriceBatch(request.orderId, request.portions, next.activity, next.service.channel)
        check(nextOrder.revision == Revision(1) && next == FoodBodyV1.Order(evidence.originalActorId, evidence.originalActorId,
            request.activity, request.service, request.portions.map { it.portionId }, listOf(batchId), total))
        ownerIndex(evidence.originalActorId, request.orderId, true)
    }
    private fun add(request: LocalFoodRequestV1.Add, prior: FoodBodyV1.Order, next: FoodBodyV1.Order) {
        val total = newPriceBatch(request.orderId, request.portions, prior.activity, prior.service.channel)
        check(prior.handoff == null && stock == null && next == prior.copy(portionIds = prior.portionIds + request.portions.map { it.portionId },
            batchIds = prior.batchIds + DomainId(evidence.operationId.value), originalQuotedTotal = prior.originalQuotedTotal + total))
    }
    private fun newPriceBatch(orderId: DomainId, selections: List<LocalFoodPortionSelectionV1>, activity: LocalSalesActivity, channel: LocalSalesChannel): Money {
        LocalFoodSelectionGroups.group(selections)
        val id = DomainId(evidence.operationId.value)
        val key = AggregateKey(LocalFoodProfileV1.PRICE_BATCH, id)
        check(key !in before)
        val value = use(key).body as FoodBodyV1.PriceBatch
        val quote = StoredPricingSnapshotCodec.decode(value.quote)
        check(value.orderId == orderId && quote.currency == Currency.EGP && quote.charges.isEmpty() && quote.discounts.isEmpty() &&
            quote.lines.map { Triple(it.lineId, it.portionId, it.quantity) } == selections.map { Triple(it.commercialLineId, it.portionId, it.quantity) })
        for (selection in selections) {
            val portionKey = AggregateKey(LocalFoodProfileV1.PORTION, selection.portionId)
            check(portionKey !in before)
            val row = use(portionKey); orderedPortions += row
            val portion = row.body as FoodBodyV1.Portion
            check(portion.orderId == orderId && portion.selection == selection && portion.priceBatchId == id && portion.originalIssue == null &&
                portion.recipe.activity == activity && portion.recipe.channel == channel &&
                portion.preparation == FoodPreparationState(selection.portionId, Revision(0), FoodPreparationStatus.NOT_SENT))
            val source = value.sources.single { it.portionId == selection.portionId }
            val line = quote.lines.single { it.portionId == selection.portionId }
            check(source.lineId == selection.commercialLineId && source.skuId == portion.recipe.pricingSkuId &&
                source.effectiveUnitPrice == line.unitPrice && source.companyPriceRevision == line.priceRevision)
        }
        return quote.payable
    }
    private fun ownerIndex(owner: DomainId, orderId: DomainId, adding: Boolean) {
        val key = AggregateKey(LocalFoodProfileV1.OWNER_INDEX, owner)
        val previous = (before[key]?.body as? FoodBodyV1.OwnerIndex)?.orderIds.orEmpty()
        check((orderId !in previous) == adding)
        check((use(key).body as FoodBodyV1.OwnerIndex).orderIds == if (adding) previous + orderId else previous - orderId)
    }
    private fun send(request: LocalFoodRequestV1.Send, order: FoodBodyV1.Order, quoteRevision: Revision) {
        val records = checkNotNull(stock)
        check(records.counts.isEmpty() && records.serialRegistrations.isEmpty() && records.kitchenConfirmations.isEmpty() && records.waste.isEmpty())
        val added = mutableListOf<FoodBodyV1.Portion>()
        for (id in request.portionIds) {
            check(id in order.portionIds)
            val key = AggregateKey(LocalFoodProfileV1.PORTION, id)
            val original = checkNotNull(before[key]).body as FoodBodyV1.Portion
            check(original.orderId == request.orderId && original.preparation.status == FoodPreparationStatus.NOT_SENT)
            val row = use(key); orderedPortions += row
            val portion = row.body as FoodBodyV1.Portion
            val tasks = original.recipe.routes.map { FoodStationTask(foodTaskId(id, it.stationId), it.stationId, it.mode, FoodStationStatus.QUEUED) }
            val expected = original.preparation.copy(revision = original.preparation.revision.next(), status = FoodPreparationStatus.QUEUED,
                tasks = tasks, reservationId = foodReservationId(id), issueId = foodIssueId(id), issueRevision = Revision(1), sentAt = evidence.recordedAt)
            check(portion.copy(preparation = original.preparation, originalIssue = original.originalIssue) == original && portion.preparation == expected)
            val issue = records.issues.single { it.issueId == foodIssueId(id) }
            check(InventoryRecordCodec.encodeIssue(issue, scope) == portion.originalIssue && issue.revision == Revision(1) &&
                issue.kitchen?.disposition == KitchenDisposition.ACTIVE && issue.kitchen?.startedStationIds?.isEmpty() == true &&
                issue.binding == CollectionBinding(request.orderId, quoteRevision, foodStockContextId(request.orderId), evidence.originalActorId, CollectionExposure.UNCOLLECTED))
            requireFoodIssueRecipe(issue, portion)
            val reservations = records.reservations.filter { it.reservationId == issue.reservationId }
            check(reservations.size == 2 && reservations[0].status == ReservationStatus.RESERVED && reservations[0].revision == Revision(1) &&
                reservations[1].status == ReservationStatus.ISSUED && reservations[1].revision == Revision(2) && reservations.all { reservation ->
                    reservation.binding == issue.binding && reservation.expiresAtEpochMillis == null && reservation.holds.isEmpty() &&
                        reservation.allocations.toSet() == issue.allocations.map { it.allocation }.toSet()
                })
            check(issue.allocations.all { it.restoredQuantity.scaled == 0L })
            tasks.forEach { extraClaims += ClaimChange.Acquire(LocalFoodProfileV1.claim("task", it.taskId), request.orderId) }
            added += portion
        }
        val issueIds = added.map { checkNotNull(it.preparation.issueId) }
        check(records.issues.map { it.issueId } == issueIds && records.newSourceIds == issueIds.toSet())
        check(records.movements == records.issues.flatMap { issue -> issue.allocations.map {
            InventoryPostedMovement(issue.issueId, it.allocation.position, -it.allocation.quantity,
                StockMovementReason.ISSUE, it.allocation.allocationId)
        } })
        val issuePools = records.issues.flatMap { issue -> issue.allocations.map { it.allocation.position.pool }.distinct().map { issue.issueId to it } }
        check(records.valuations.map { it.fact?.factId to it.pool } == issuePools)
        for (value in records.valuations) {
            val fact = value.fact as? ValuationFact.Issue ?: error("food.producer.issue_value_kind")
            val issue = records.issues.single { it.issueId == fact.factId }
            val slices = issue.allocations.filter { it.allocation.position.pool == value.pool }.map {
                CostedValueSlice(it.allocation.allocationId, it.allocation.quantity, it.originalValue)
            }
            check(fact.purpose == ValueIssuePurpose.DIRECT_RECIPE && slices.isNotEmpty() && fact.slices == slices)
            check(value.quantityDelta == -foodQuantityTotal(slices.map { it.quantity }) &&
                value.originalValueDelta == -foodValueTotal(slices.map { it.originalValue }))
        }
        val reservationIds = added.map { checkNotNull(it.preparation.reservationId) }.toSet()
        val expectedKinds = records.issues.flatMap { issue -> listOf(InventoryPostedEffectKind.RESERVATION) +
            issue.allocations.groupBy { it.allocation.position.pool }.values.flatMap { allocations ->
                listOf(InventoryPostedEffectKind.VALUATION) + List(allocations.size) { InventoryPostedEffectKind.MOVEMENT }
            } + listOf(InventoryPostedEffectKind.RESERVATION, InventoryPostedEffectKind.ISSUE)
        }
        check(withoutCollateralExpiry(records, reservationIds) == expectedKinds)
        stationIndexes(added, true)
        expectedJobs = added.flatMap { foodDeliveryJobs(order, it, "PREPARE") }
    }

    private fun portionChange(request: LocalFoodRequestV1, order: FoodBodyV1.Order) {
        val id = request.foodPortionId()
        val key = AggregateKey(LocalFoodProfileV1.PORTION, id)
        val old = checkNotNull(before[key]).body as FoodBodyV1.Portion
        check(id in order.portionIds && old.orderId == request.foodOrderId() && old.preparation.revision == request.foodPortionRevision())
        val row = use(key); orderedPortions += row
        val next = row.body as FoodBodyV1.Portion
        check(next.copy(preparation = old.preparation) == old)
        val prior = old.preparation
        var expected: FoodPreparationState
        when (request) {
            is LocalFoodRequestV1.Station -> {
                val task = prior.tasks.single { it.stationId == request.stationId }
                when (request.action) {
                    LocalFoodStationActionV1.START -> {
                        check(task.status == FoodStationStatus.QUEUED && prior.status in setOf(FoodPreparationStatus.QUEUED,
                            FoodPreparationStatus.PREPARING, FoodPreparationStatus.CANCEL_PENDING))
                        expected = prior.copy(revision = prior.revision.next(), tasks = prior.tasks.map { if (it == task)
                            it.copy(status = FoodStationStatus.STARTED, startedAt = evidence.recordedAt) else it },
                            status = if (prior.status == FoodPreparationStatus.CANCEL_PENDING) prior.status else FoodPreparationStatus.PREPARING,
                            issueRevision = checkNotNull(prior.issueRevision).next())
                        val issue = onlyIssue(next, listOf(InventoryPostedEffectKind.ISSUE))
                        check(issue.kitchen?.disposition == KitchenDisposition.ACTIVE && issue.kitchen?.startedStationIds?.toSet() ==
                            expected.tasks.filter { it.startedAt != null }.map { it.stationId }.toSet() && issue.allocations.all { it.restoredQuantity.scaled == 0L })
                    }
                    LocalFoodStationActionV1.READY -> {
                        check(stock == null && prior.status == FoodPreparationStatus.PREPARING && task.status == FoodStationStatus.STARTED)
                        val tasks = prior.tasks.map { if (it == task) it.copy(status = FoodStationStatus.READY, readyAt = evidence.recordedAt) else it }
                        expected = prior.copy(revision = prior.revision.next(), tasks = tasks,
                            status = if (tasks.all { it.status == FoodStationStatus.READY }) FoodPreparationStatus.READY else FoodPreparationStatus.PREPARING)
                    }
                    LocalFoodStationActionV1.CONFIRM_UNSTARTED -> {
                        check(prior.status == FoodPreparationStatus.CANCEL_PENDING && !prior.started && task.status == FoodStationStatus.QUEUED)
                        val tasks = prior.tasks.map { if (it == task) it.copy(status = FoodStationStatus.CANCEL_CONFIRMED,
                            cancellationConfirmationId = request.confirmationId, cancellationConfirmedBy = evidence.originalActorId,
                            cancellationConfirmedAt = evidence.recordedAt, returnDisposition = request.returnDisposition) else it }
                        val complete = tasks.all { it.status == FoodStationStatus.CANCEL_CONFIRMED }
                        expected = prior.copy(revision = prior.revision.next(), tasks = tasks,
                            status = if (complete) FoodPreparationStatus.CANCELLED_BEFORE_START else prior.status,
                            issueRevision = if (complete) checkNotNull(prior.issueRevision).next() else prior.issueRevision)
                        extraClaims += ClaimChange.Acquire(LocalFoodProfileV1.claim("confirmation", checkNotNull(request.confirmationId)), id)
                        if (complete) restored(old, next, request, expected) else check(stock == null)
                    }
                }
            }
            is LocalFoodRequestV1.Cancel -> {
                check(stock == null && prior.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && prior.status in setOf(FoodPreparationStatus.NOT_SENT,
                    FoodPreparationStatus.QUEUED, FoodPreparationStatus.PREPARING, FoodPreparationStatus.READY))
                check(order.financialCollectionId == null || prior.status != FoodPreparationStatus.NOT_SENT)
                expected = prior.copy(revision = prior.revision.next(),
                    status = if (prior.status == FoodPreparationStatus.NOT_SENT) FoodPreparationStatus.CANCELLED_BEFORE_START else FoodPreparationStatus.CANCEL_PENDING,
                    cancellationReasonId = request.reasonId, cancellationRequestedAt = evidence.recordedAt)
                if (prior.tasks.isNotEmpty()) expectedJobs = foodDeliveryJobs(order, next, "CANCEL_REQUEST_CONFIRM_REQUIRED")
            }
            is LocalFoodRequestV1.Serve -> {
                check(stock == null && order.service.channel == LocalSalesChannel.DINE_IN && prior.status == FoodPreparationStatus.READY && prior.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER)
                expected = prior.copy(revision = prior.revision.next(), fulfillment = FoodFulfillmentStatus.SERVED, servedAt = evidence.recordedAt)
            }
            is LocalFoodRequestV1.DiscardStarted -> {
                check(prior.status == FoodPreparationStatus.CANCEL_PENDING && prior.started && prior.tasks.any { it.stationId == request.stationId })
                expected = prior.copy(revision = prior.revision.next(), status = FoodPreparationStatus.WASTE_AFTER_START,
                    tasks = prior.tasks.map { it.copy(status = FoodStationStatus.CANCELLED) }, issueRevision = checkNotNull(prior.issueRevision).next())
                val issue = onlyIssue(next, listOf(InventoryPostedEffectKind.ISSUE, InventoryPostedEffectKind.WASTE))
                check(issue.kitchen?.disposition == KitchenDisposition.WASTE_AFTER_START && issue.allocations.all { it.restoredQuantity.scaled == 0L } &&
                    checkNotNull(stock).waste == listOf(InventoryPostedWaste(issue.issueId, issue.originalValue, checkNotNull(prior.cancellationReasonId))))
            }
            else -> error("food.producer.portion_action")
        }
        check(next.preparation == expected)
        if (prior.tasks.isNotEmpty() && (expected.status in setOf(FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START) ||
                expected.fulfillment == FoodFulfillmentStatus.SERVED)) stationIndexes(listOf(next), false)
    }

    private fun onlyIssue(portion: FoodBodyV1.Portion, kinds: List<InventoryPostedEffectKind>): StockIssue {
        val records = checkNotNull(stock)
        check(records.effectKinds == kinds && records.issues.size == 1 &&
            records.movements.isEmpty() && records.valuations.isEmpty() && records.reservations.isEmpty() && records.safety.isEmpty() &&
            records.counts.isEmpty() && records.kitchenConfirmations.isEmpty() && records.serialRegistrations.isEmpty())
        if (InventoryPostedEffectKind.WASTE !in kinds) check(records.waste.isEmpty())
        val issue = records.issues.single()
        requireCurrentFoodIssue(portion, issue)
        return issue
    }

    private fun restored(old: FoodBodyV1.Portion, next: FoodBodyV1.Portion, request: LocalFoodRequestV1.Station, expected: FoodPreparationState) {
        val records = checkNotNull(stock)
        check(records.counts.isEmpty() && records.serialRegistrations.isEmpty() && records.waste.isEmpty() && records.issues.size == 1)
        val issue = records.issues.single()
        requireCurrentFoodIssue(next, issue)
        check(issue.kitchen?.disposition == KitchenDisposition.CANCELLED_BEFORE_START && issue.kitchen?.startedStationIds?.isEmpty() == true &&
            issue.allocations.all { it.restoredQuantity == it.allocation.quantity })
        val confirmations = records.kitchenConfirmations
        check(confirmations.size == 1 && confirmations.single().let { it.issueId == issue.issueId && it.issueRevision == old.preparation.issueRevision &&
            it.confirmationId == request.confirmationId && it.confirmedBy == evidence.originalActorId && it.confirmedStationIds == expected.tasks.map { task -> task.stationId } })
        val restoration = foodId("restoration", evidence.operationId.value)
        check(records.newSourceIds == setOf(restoration) && records.movements.size == issue.allocations.size &&
            records.movements.zip(issue.allocations).all { (movement, allocation) ->
                movement.reason == StockMovementReason.ORIGINAL_RETURN && movement.sourceId == restoration &&
                    allocation.allocation.allocationId == movement.sourceAllocationId && allocation.allocation.quantity == movement.quantityDelta &&
                    allocation.allocation.position.pool == movement.position.pool && allocation.allocation.position.lotId == movement.position.lotId &&
                    movement.position.serialId == null && movement.position.custody in setOf(StockCustody.RELEASED, StockCustody.QUARANTINE) &&
                    (expected.tasks.none { it.returnDisposition == FoodIngredientReturnDisposition.QUARANTINE } || movement.position.custody == StockCustody.QUARANTINE)
            })
        val pools = issue.allocations.groupBy { it.allocation.position.pool }
        check(records.valuations.map { it.pool } == pools.keys.toList())
        for (value in records.valuations) {
            val fact = value.fact as? ValuationFact.Restoration ?: error("food.producer.restoration_value_kind")
            val slices = pools.getValue(value.pool).map {
                OriginalValueRestoration(issue.issueId, it.allocation.allocationId, it.allocation.quantity, it.originalValue)
            }
            check(fact.factId == restoration && slices.isNotEmpty() && fact.restorations == slices &&
                value.quantityDelta == foodQuantityTotal(slices.map { it.quantity }) &&
                value.originalValueDelta == foodValueTotal(slices.map { it.originalValue }))
        }
        val expectedKinds = pools.values.flatMap { allocations -> listOf(InventoryPostedEffectKind.VALUATION) +
            List(allocations.size) { InventoryPostedEffectKind.MOVEMENT }
        } + listOf(InventoryPostedEffectKind.ISSUE, InventoryPostedEffectKind.KITCHEN_CONFIRMATION)
        check(withoutCollateralExpiry(records, emptySet()) == expectedKinds)
    }

    private fun withoutCollateralExpiry(records: InventoryPostedRecords, ownReservationIds: Set<DomainId>): List<InventoryPostedEffectKind> {
        check(records.safety.all { (old, after) -> old.safety == StockSafety.CLEAR && after.safety == StockSafety.EXPIRED &&
            after == old.copy(safety = StockSafety.EXPIRED) &&
            after.expiresAtEpochMillis != null && checkNotNull(after.expiresAtEpochMillis) <= evidence.recordedAt.value.toEpochMilliseconds() })
        val expired = records.safety.map { it.second.key }.toSet()
        check(records.reservations.filter { it.reservationId !in ownReservationIds }.all { reservation ->
            reservation.status == ReservationStatus.HELD && reservation.holds.any {
                it.reason == ReservationHoldReason.EXPIRY && it.position in expired && reservation.allocations.any { allocation -> allocation.position == it.position }
            }
        })
        var reservation = 0
        return records.effectKinds.filter { kind -> when (kind) {
            InventoryPostedEffectKind.SAFETY -> false
            InventoryPostedEffectKind.RESERVATION -> records.reservations[reservation++].reservationId in ownReservationIds
            else -> true
        } }.also { check(reservation == records.reservations.size) }
    }
    private fun stationIndexes(portions: List<FoodBodyV1.Portion>, adding: Boolean) {
        val stations = portions.flatMap { it.preparation.tasks }.map { it.stationId }.distinct()
        for (station in stations) {
            val key = AggregateKey(LocalFoodProfileV1.STATION_INDEX, station)
            val prior = (before[key]?.body as? FoodBodyV1.StationIndex)?.tasks.orEmpty()
            val changed = portions.filter { portion -> portion.preparation.tasks.any { it.stationId == station } }
                .map { FoodTaskLocatorV1(foodTaskId(it.selection.portionId, station), it.orderId, it.selection.portionId) }
            check(changed.all { (it !in prior) == adding })
            val expected = if (adding) prior + changed else prior.filterNot { old -> changed.any { it.taskId == old.taskId } }
            check((use(key).body as FoodBodyV1.StationIndex).tasks == expected)
        }
    }
}

private fun foodQuantityTotal(values: List<Quantity>): Quantity {
    val first = values.first()
    return values.fold(Quantity(first.unitId, 0, first.scale)) { total, quantity -> total + quantity }
}
private fun foodValueTotal(values: List<Money>): Money = values.fold(Money(Currency.EGP, 0)) { total, value -> total + value }

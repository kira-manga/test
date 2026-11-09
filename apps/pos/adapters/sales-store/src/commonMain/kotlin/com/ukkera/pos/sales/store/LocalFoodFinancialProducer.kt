package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/**
 * Whole genuine food producer: exact closed request, source copies, every write/claim/job,
 * original quotes, financial documents and normalized stock effects. No retail origin/type is
 * manufactured. No historical validator recreates a grant or runs a domain transition.
 * Actual predecessor bytes and CURRENT normalized closure remain separate required reads.
 */
public object LocalFoodFinancialProducer {
    public fun requireComplete(evidence: RetainedCommandEvidence): List<LocalFoodRecipeSnapshotV1> =
        FoodFinancialProducerProof(evidence).validate()
}

private class FoodFinancialProducerProof(private val evidence: RetainedCommandEvidence) {
    private val request = LocalFoodFinancialCodecV1.decode(evidence.normalized)
    private val storedEvent = foodFinancialStoredEvent(evidence)
    private val frames = storedEvent.framing?.let { FoodFinancialBundleReaderV2(evidence, it) }
    private val event = if (frames == null) storedEvent else storedEvent.copy(cash = frames.cash())
    private val inputList = foodFinancialInputs(evidence, frames)
    private val inputs = inputList.associateBy { it.key() }
    private val food = evidence.aggregateWrites.filter { it.key.kind in LocalFoodProfileV1.kinds - setOf(LocalFoodProfileV1.BEFORE, LocalFoodProfileV1.EVENT) }
        .associate { it.key to foodEnvelope(it.payload) }
    private val financial = evidence.aggregateWrites.filter { it.key.kind in foodFinancialDocumentKinds }
        .associate { write ->
            // Only INPUTS may cross the explicit v1/v2 boundary. An event's own financial
            // AFTERs all use its one declared profile; no silent legacy fallback in v2.
            check(write.schema == if (frames == null) LocalFoodFinancialProfileV1.schema(write.key.kind)
                else foodFinancialSchemaV2(write.key.kind))
            write.key to (frames?.document(write.payload) ?: foodFinancialEnvelope(write.payload))
        }
    private val afterKeys = food.keys + financial.keys
    private val usedInputs = mutableSetOf<AggregateKey>()
    private val usedAfter = mutableSetOf<AggregateKey>()
    private val extraClaims = mutableListOf<ClaimChange.Acquire>()
    private var jobs: List<JobIntent> = emptyList()
    private val inventoryScope = InventoryScope(evidence.scope.companyId, evidence.scope.branchId)
    private val stock = if (event.stockProduced) LocalStockRepository().inspectPosting(evidence) else null
    private var documentId: DomainId? = null
    private var amount: Money? = null
    private var resultCode: String = ""

    fun validate(): List<LocalFoodRecipeSnapshotV1> {
        check(evidence.committed && evidence.command == LocalFoodFinancialProfileV1.COMMAND && foodNonphysicalRequest(request) &&
            LocalFoodFinancialCodecV1.normalize(evidence.normalized) == evidence.normalized &&
            semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
        check(event.scope == evidence.scope && event.operationId == evidence.operationId && event.actorId == evidence.originalActorId &&
            event.recordedAt == evidence.recordedAt && event.inputIds.size == inputList.size && inputList.size == inputs.size &&
            inputs.size <= (if (frames == null) 96 else FOOD_FINANCIAL_MAX_INPUTS_V2) &&
            event.inputIds.distinct().size == event.inputIds.size && event.after.map { it.kind to it.id }.distinct().size == event.after.size &&
            event.after.size == afterKeys.size && event.stockProduced == (stock != null))
        val proofKeys = frames?.chunkKeys ?: event.inputIds.map { AggregateKey(LocalFoodFinancialProfileV1.BEFORE, it) }.toSet()
        val ownKeys = afterKeys + proofKeys + AggregateKey(LocalFoodFinancialProfileV1.EVENT, DomainId(evidence.operationId.value))
        frames?.requireCompleteLeaves(event.after)
        check(evidence.aggregateWrites.map { it.key }.distinct().size == evidence.aggregateWrites.size &&
            evidence.aggregateWrites.filter { it.key.kind !in LocalStockStoreProfile.inventoryKinds }.map { it.key }.toSet() == ownKeys &&
            evidence.aggregateWrites.all { it.payloadDigest == sha256(it.payload.copyBytes()) })
        if (stock == null) check(evidence.aggregateWrites.none { it.key.kind in LocalStockStoreProfile.inventoryKinds } &&
            evidence.claimChanges.none { it.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces })
        for (key in afterKeys) {
            val write = evidence.aggregateWrites.single { it.key == key }
            val reference = event.after.single { it.kind == key.kind && it.id == key.id }
            val foodRow = food[key]; val financialRow = financial[key]
            check(reference.revision == write.storageGeneration && reference.digest == write.payloadDigest &&
                write.storageGeneration == (inputs[key]?.revision?.next() ?: Revision(1)))
            if (foodRow != null) check(foodRow.scope == evidence.scope && foodRow.id == key.id && foodRow.kind == key.kind &&
                foodRow.operationId == evidence.operationId && foodRow.revision == write.storageGeneration && write.schema == LocalFoodProfileV1.schema(key.kind))
            else check(checkNotNull(financialRow).scope == evidence.scope && financialRow.id == key.id && financialRow.kind == key.kind &&
                financialRow.operationId == evidence.operationId && financialRow.revision == write.storageGeneration &&
                write.schema == if (financialRow.framed) foodFinancialSchemaV2(key.kind) else LocalFoodFinancialProfileV1.schema(key.kind))
        }
        when (val selected = request) {
            is LocalFoodFinancialRequestV1.BeginCollection -> begin(selected)
            is LocalFoodFinancialRequestV1.FinalizePaid -> finalize(selected)
            is LocalFoodFinancialRequestV1.SendReserved -> send(selected)
            is LocalFoodFinancialRequestV1.PaidPickup -> pickup(selected)
            is LocalFoodFinancialRequestV1.CancelWholeUnsent -> cancel(selected)
            is LocalFoodFinancialRequestV1.CloseResolvedWork -> close(selected)
            is LocalFoodFinancialRequestV1.RecordQualityClaim -> claim(selected)
            else -> error("food.financial.physical_profile_not_installed")
        }
        check(usedAfter == afterKeys && usedInputs == inputs.keys)
        if (request !is LocalFoodFinancialRequestV1.BeginCollection) check(event.cash == null)
        val expected = LocalFoodFinancialResultV1(resultCode, checkNotNull(documentId), event.after.map {
            LocalFoodStorageRevisionV1(it.kind, it.id, it.revision)
        }, amount)
        check(event.result == expected && evidence.result == localSalesEncode(expected, LocalFoodFinancialResultV1.serializer(), 16_384) &&
            evidence.resultHash == sha256(evidence.result.copyBytes()))
        val claims = afterKeys.filter { it !in inputs }.map { key -> ClaimChange.Acquire(
            if (key.kind in LocalFoodProfileV1.kinds) LocalFoodProfileV1.claim(key.kind, key.id)
            else LocalFoodFinancialProfileV1.source(key.kind, key.id), key.id) } + extraClaims
        val actual = evidence.claimChanges.filter { it.key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces }
        check(actual.size == claims.size && actual.all { it.action == RetainedClaimAction.ACQUIRE } &&
            claims.all { expectedClaim -> actual.any { it.key == expectedClaim.key && it.ownerId == expectedClaim.ownerId } })
        check(evidence.jobs.size == jobs.size && evidence.jobs.zip(jobs).all { (a, b) -> a.kind == b.kind && a.body == b.body })
        return inputList.filter { it.kind == LocalFoodProfileV1.PORTION }.map { (foodEnvelope(it.bytes()).body as FoodBodyV1.Portion).recipe }.distinct()
    }

    private fun beforeFood(kind: String, id: DomainId): FoodEnvelopeV1 {
        val key = AggregateKey(kind, id); usedInputs += key
        return foodEnvelope(inputs.getValue(key).bytes())
    }
    private fun beforeFinancial(kind: String, id: DomainId): FoodFinancialEnvelopeV1 {
        val key = AggregateKey(kind, id); usedInputs += key
        val input = inputs.getValue(key)
        return input.financial ?: foodFinancialEnvelope(input.bytes())
    }
    private fun optionalFinancial(kind: String, id: DomainId): FoodFinancialEnvelopeV1? =
        if (AggregateKey(kind, id) in inputs) beforeFinancial(kind, id) else null
    private fun afterFood(kind: String, id: DomainId): FoodEnvelopeV1 = food.getValue(AggregateKey(kind, id)).also { check(usedAfter.add(AggregateKey(kind, id))) }
    private fun afterFinancial(kind: String, id: DomainId): FoodFinancialEnvelopeV1 = financial.getValue(AggregateKey(kind, id)).also { check(usedAfter.add(AggregateKey(kind, id))) }
    private fun reservation(id: DomainId): StockReservation {
        val key = AggregateKey(LocalStockStoreProfile.RESERVATION, id); usedInputs += key
        val literal = checkNotNull(inputs.getValue(key).stock)
        return InventoryRecordCodec.decodeReservation(literal.snapshot, inventoryScope, id, literal.domainRevision)
    }
    private fun issue(id: DomainId): StockIssue {
        val key = AggregateKey(LocalStockStoreProfile.ISSUE, id); usedInputs += key
        val literal = checkNotNull(inputs.getValue(key).stock)
        return InventoryRecordCodec.decodeIssue(literal.snapshot, inventoryScope, id, literal.domainRevision)
    }
    private class OrderSources(val row: FoodEnvelopeV1, val index: FoodEnvelopeV1,
        val portions: List<FoodEnvelopeV1>, val batches: List<FoodEnvelopeV1>) {
        val order: FoodBodyV1.Order get() = row.body as FoodBodyV1.Order
    }
    private fun orderSources(id: DomainId): OrderSources {
        val row = beforeFood(LocalFoodProfileV1.ORDER, id); val order = row.body as FoodBodyV1.Order
        val index = beforeFood(LocalFoodProfileV1.OWNER_INDEX, order.ownerId)
        check(order.closedByOperationId == null && order.handoff == null && id in (index.body as FoodBodyV1.OwnerIndex).orderIds)
        return OrderSources(row, index, order.portionIds.map { beforeFood(LocalFoodProfileV1.PORTION, it) },
            order.batchIds.map { beforeFood(LocalFoodProfileV1.PRICE_BATCH, it) })
    }
    private fun collectionSources(id: DomainId): Pair<FoodCollection, OrderSources> {
        val collection = foodFinancialCollection(beforeFinancial(LocalFoodFinancialProfileV1.COLLECTION, id))
        val source = orderSources(collection.orderId)
        check(source.order.financialCollectionId == id && source.order.ownerId == collection.currentOwnerId &&
            source.order.creatorId == collection.originalCreatorId && collection.currentOwnerId == evidence.originalActorId)
        requireFoodFinancialQuote(collection, source.order, source.portions, source.batches)
        currentStock(source, collection)
        return collection to source
    }
    private fun currentStock(source: OrderSources, collection: FoodCollection? = null) {
        for (row in source.portions) {
            val portion = row.body as FoodBodyV1.Portion
            val link = collection?.portions?.single { it.portionId == row.id }
            val reserved = if (link != null) reservation(link.reservationId) else portion.preparation.reservationId?.let(::reservation)
            val issued = portion.preparation.issueId?.let(::issue)
            if (issued != null) {
                requireCurrentFoodIssue(portion, issued)
                check(checkNotNull(reserved).status == ReservationStatus.ISSUED && reserved.binding == issued.binding &&
                    reserved.allocations == issued.allocations.map { it.allocation })
            }
            if (link != null) {
                requireFoodReservationRecipe(checkNotNull(reserved), portion)
                if (link.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION) {
                    check(reserved.binding == foodFinancialStockBinding(checkNotNull(collection)) && reserved.expiresAtEpochMillis == null)
                    if (collection.cancellationRefundId != null) check(reserved.status == ReservationStatus.RELEASED && issued == null &&
                        portion.preparation.status == FoodPreparationStatus.CANCELLED_BEFORE_START)
                    else if (issued == null) check(reserved.retainsStockClaim && portion.preparation.status == FoodPreparationStatus.NOT_SENT)
                    else check(portion.financialCollectionId == collection.id && issued.reservationId == link.reservationId)
                } else check(issued != null && link.priorIssueId == issued.issueId && issued.binding.exposure == CollectionExposure.UNCOLLECTED &&
                    issued.binding.collectionId == foodStockContextId(collection.orderId))
            }
        }
    }

    private fun begin(request: LocalFoodFinancialRequestV1.BeginCollection) {
        val source = orderSources(request.orderId); val old = source.order
        check(source.row.revision == request.expectedOrderRevision && old.ownerId == evidence.originalActorId && old.financialCollectionId == null)
        check(source.portions.none { (it.body as FoodBodyV1.Portion).preparation.status in foodChargeDecisionStates })
        currentStock(source)
        val after = foodFinancialCollection(afterFinancial(LocalFoodFinancialProfileV1.COLLECTION, request.collectionId))
        check(AggregateKey(LocalFoodFinancialProfileV1.COLLECTION, request.collectionId) !in inputs && after.revision == Revision(0) &&
            after.scope == evidence.scope && after.orderId == request.orderId && after.lockedCommercialRevision == request.expectedOrderRevision &&
            after.originalCreatorId == old.creatorId && after.collectorId == old.ownerId && after.cashSessionId == request.sessionId &&
            after.attempts.isEmpty() && after.responsibility.isEmpty() && after.saleId == null && after.cancellationRefundId == null)
        requireFoodFinancialQuote(after, old, source.portions, source.batches)
        check(afterFood(LocalFoodProfileV1.ORDER, request.orderId).body == old.copy(financialCollectionId = request.collectionId))
        val cashInput = checkNotNull(event.cash)
        check(cashInput.sessionId == request.sessionId && cashInput.storageRevision == request.expectedSessionRevision.next())
        val cash = SalesSnapshotCodec.decodeCashSession(cashInput.snapshot,
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, evidence.scope, request.sessionId, request.expectedSessionRevision))
        check(cash.custodianId == evidence.originalActorId && cash.status == CashSessionStatus.OPEN)
        val unsent = source.portions.filter { (it.body as FoodBodyV1.Portion).preparation.status == FoodPreparationStatus.NOT_SENT }
        for (row in source.portions) {
            val portion = row.body as FoodBodyV1.Portion
            val link = after.portions.single { it.portionId == row.id }
            check(link.stockOrigin == if (row in unsent) FoodStockOrigin.RESERVED_FOR_COLLECTION else FoodStockOrigin.ISSUED_BEFORE_COLLECTION)
            check(link.priorIssueId == portion.preparation.issueId)
        }
        if (unsent.isEmpty()) check(stock == null) else {
            val records = checkNotNull(stock); onlyReservations(records, release = false)
            check(records.reservations.map { it.reservationId } == unsent.map { foodReservationId(it.id) })
            for (row in unsent) {
                val reserved = records.reservations.single { it.reservationId == foodReservationId(row.id) }
                check(reserved.revision == Revision(1) && reserved.status == ReservationStatus.RESERVED && reserved.holds.isEmpty() &&
                    reserved.expiresAtEpochMillis == null && reserved.binding == foodFinancialStockBinding(after))
                requireFoodReservationRecipe(reserved, row.body as FoodBodyV1.Portion)
            }
        }
        resultCode = "food.collection.locked"; documentId = request.collectionId; amount = after.quote.payable
    }

    private fun finalize(request: LocalFoodFinancialRequestV1.FinalizePaid) {
        val (before, source) = collectionSources(request.collectionId)
        check(stock == null && before.revision == request.expectedCollectionRevision && before.saleId == null && before.cancellationRefundId == null &&
            !before.unresolved && before.collected == before.quote.payable &&
            source.portions.none { (it.body as FoodBodyV1.Portion).preparation.status in foodChargeDecisionStates })
        val after = foodFinancialCollection(afterFinancial(LocalFoodFinancialProfileV1.COLLECTION, before.id))
        check(after.revision == before.revision.next() && after.saleId == request.saleId && after.cancellationRefundId == null &&
            FoodFinancialSnapshots.sameCollectionSource(before, after))
        val sale = foodFinancialSale(afterFinancial(LocalFoodFinancialProfileV1.SALE, request.saleId))
        val entitlement = foodFinancialEntitlement(afterFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, request.saleId))
        check(FoodFinancialFramesV2.encodeCollection(sale.collection) == FoodFinancialFramesV2.encodeCollection(before) &&
            sale.finalizationOperationId == evidence.operationId && sale.finalizedAt == evidence.recordedAt && entitlement.revision == Revision(0))
        requireFoodPaidSource(after, sale, entitlement)
        resultCode = "food.sale.finalized"; documentId = request.saleId; amount = sale.quote.payable
    }

    private fun send(request: LocalFoodFinancialRequestV1.SendReserved) {
        val (collection, source) = collectionSources(request.collectionId)
        check(collection.orderId == request.orderId && collection.revision == request.expectedCollectionRevision && collection.cancellationRefundId == null &&
            source.row.revision == request.expectedOrderRevision && afterFood(LocalFoodProfileV1.ORDER, request.orderId).body == source.order)
        val records = checkNotNull(stock)
        check(records.counts.isEmpty() && records.serialRegistrations.isEmpty() && records.kitchenConfirmations.isEmpty() && records.waste.isEmpty() && records.releases.isEmpty())
        val changed = request.portionIds.map { id ->
            val old = source.portions.single { it.id == id }.body as FoodBodyV1.Portion
            val link = collection.portions.single { it.portionId == id }
            check(link.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION && old.preparation.status == FoodPreparationStatus.NOT_SENT)
            val next = afterFood(LocalFoodProfileV1.PORTION, id).body as FoodBodyV1.Portion
            val tasks = old.recipe.routes.map { FoodStationTask(foodTaskId(id, it.stationId), it.stationId, it.mode, FoodStationStatus.QUEUED) }
            val expected = old.preparation.copy(revision = old.preparation.revision.next(), status = FoodPreparationStatus.QUEUED,
                tasks = tasks, reservationId = link.reservationId, issueId = foodIssueId(id), issueRevision = Revision(1), sentAt = evidence.recordedAt)
            check(next.copy(preparation = old.preparation, originalIssue = old.originalIssue, financialCollectionId = old.financialCollectionId) == old &&
                next.preparation == expected && next.financialCollectionId == collection.id)
            val issued = records.issues.single { it.issueId == foodIssueId(id) }
            requireCurrentFoodIssue(next, issued); requireFoodIssueRecipe(issued, next)
            check(issued.revision == Revision(1) && issued.binding == foodFinancialStockBinding(collection) &&
                InventoryRecordCodec.encodeIssue(issued, inventoryScope) == next.originalIssue)
            val beforeReservation = reservation(link.reservationId)
            val afterReservation = records.reservations.single { it.reservationId == link.reservationId }
            check(beforeReservation.status == ReservationStatus.RESERVED && afterReservation.status == ReservationStatus.ISSUED &&
                afterReservation.revision == beforeReservation.revision.next() && afterReservation.binding == beforeReservation.binding &&
                afterReservation.allocations == beforeReservation.allocations && afterReservation.holds.isEmpty() &&
                afterReservation.expiresAtEpochMillis == null && issued.allocations.map { it.allocation } == beforeReservation.allocations)
            tasks.forEach { extraClaims += ClaimChange.Acquire(LocalFoodProfileV1.claim("task", it.taskId), request.orderId) }
            next
        }
        check(records.issues.map { it.issueId } == request.portionIds.map(::foodIssueId) && records.newSourceIds == records.issues.map { it.issueId }.toSet())
        check(records.movements == records.issues.flatMap { issued -> issued.allocations.map {
            InventoryPostedMovement(issued.issueId, it.allocation.position, -it.allocation.quantity, StockMovementReason.ISSUE, it.allocation.allocationId)
        } })
        val issuePools = records.issues.flatMap { issued -> issued.allocations.map { it.allocation.position.pool }.distinct().map { issued.issueId to it } }
        check(records.valuations.map { it.fact?.factId to it.pool } == issuePools)
        for (value in records.valuations) {
            val fact = value.fact as? ValuationFact.Issue ?: error("food.financial.issue_value_kind")
            val issued = records.issues.single { it.issueId == fact.factId }
            val slices = issued.allocations.filter { it.allocation.position.pool == value.pool }.map {
                CostedValueSlice(it.allocation.allocationId, it.allocation.quantity, it.originalValue)
            }
            check(fact.purpose == ValueIssuePurpose.DIRECT_RECIPE && fact.slices == slices && slices.isNotEmpty() &&
                value.quantityDelta == -foodFinancialQuantity(slices.map { it.quantity }) &&
                value.originalValueDelta == -slices.fold(Money(Currency.EGP, 0)) { total, slice -> total + slice.originalValue })
        }
        val expectedKinds = records.issues.flatMap { issued -> issued.allocations.groupBy { it.allocation.position.pool }.values.flatMap { allocations ->
            listOf(InventoryPostedEffectKind.VALUATION) + List(allocations.size) { InventoryPostedEffectKind.MOVEMENT }
        } + listOf(InventoryPostedEffectKind.RESERVATION, InventoryPostedEffectKind.ISSUE) }
        check(foodFinancialWithoutExpiry(records, changed.map { checkNotNull(it.preparation.reservationId) }.toSet(), evidence.recordedAt) == expectedKinds)
        stationIndexes(changed, true)
        jobs = changed.flatMap { foodDeliveryJobs(source.order, it, "PREPARE") }
        resultCode = "food.reserved.sent"; documentId = request.orderId
    }

    private fun pickup(request: LocalFoodFinancialRequestV1.PaidPickup) {
        val (collection, source) = collectionSources(request.collectionId)
        check(stock == null && collection.orderId == request.orderId && source.row.revision == request.expectedOrderRevision &&
            collection.status == FoodCollectionStatus.FINALIZED_PAID && collection.cancellationRefundId == null &&
            source.order.service.channel in setOf(LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY))
        val saleId = checkNotNull(collection.saleId)
        val sale = foodFinancialSale(beforeFinancial(LocalFoodFinancialProfileV1.SALE, saleId))
        val entitlement = foodFinancialEntitlement(beforeFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, saleId))
        requireFoodPaidSource(collection, sale, entitlement)
        check(entitlement.cursors.single { it.portionId == request.portionId }.consumedQuantity.scaled == 0L)
        val old = source.portions.single { it.id == request.portionId }.body as FoodBodyV1.Portion
        val prior = old.preparation
        check(prior.revision == request.expectedPortionRevision && prior.status == FoodPreparationStatus.READY && prior.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER)
        val next = afterFood(LocalFoodProfileV1.PORTION, request.portionId).body as FoodBodyV1.Portion
        check(next == old.copy(preparation = prior.copy(revision = prior.revision.next(), fulfillment = FoodFulfillmentStatus.PICKED_UP, pickedUpAt = evidence.recordedAt)))
        check(afterFood(LocalFoodProfileV1.ORDER, request.orderId).body == source.order)
        stationIndexes(listOf(next), false)
        resultCode = "food.paid_pickup.recorded"; documentId = request.orderId
    }

    private fun cancel(request: LocalFoodFinancialRequestV1.CancelWholeUnsent) {
        val (before, source) = collectionSources(request.collectionId)
        check(before.revision == request.expectedCollectionRevision && source.row.revision == request.expectedOrderRevision &&
            !before.unresolved && before.cancellationRefundId == null && before.portions.all { it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION } &&
            source.portions.all { (it.body as FoodBodyV1.Portion).preparation.let { p ->
                p.status == FoodPreparationStatus.NOT_SENT && p.issueId == null && p.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER
            } })
        val after = foodFinancialCollection(afterFinancial(LocalFoodFinancialProfileV1.COLLECTION, before.id))
        check(after.revision == before.revision.next() && after.saleId == before.saleId && after.cancellationRefundId == request.refundId &&
            FoodFinancialSnapshots.sameCollectionSource(before, after))
        val refund = foodFinancialRefund(afterFinancial(LocalFoodFinancialProfileV1.REFUND, request.refundId))
        check(refund.revision == Revision(0) && refund.attempts.isEmpty() && refund.cancellationOperationId == evidence.operationId &&
            refund.cancellationReasonId == request.reasonId && refund.acceptingActorId == evidence.originalActorId && refund.acceptedAt == evidence.recordedAt &&
            FoodFinancialFramesV2.encodeCollection(refund.originalCollection) == FoodFinancialFramesV2.encodeCollection(before) && refund.amount == before.collected)
        val oldIndex = optionalFinancial(LocalFoodFinancialProfileV1.REFUND_INDEX, before.orderId)?.body as? FoodFinancialBodyV1.RefundIndex
        val nextIndex = afterFinancial(LocalFoodFinancialProfileV1.REFUND_INDEX, before.orderId).body as FoodFinancialBodyV1.RefundIndex
        check(nextIndex == FoodFinancialBodyV1.RefundIndex(before.orderId, oldIndex?.refundIds.orEmpty() + refund.id))
        before.saleId?.let { id ->
            val sale = foodFinancialSale(beforeFinancial(LocalFoodFinancialProfileV1.SALE, id))
            val old = foodFinancialEntitlement(beforeFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, id))
            requireFoodPaidSource(before, sale, old)
            check(old.revision == Revision(0) && old.cursors.all { it.consumedQuantity.scaled == 0L })
            val next = foodFinancialEntitlement(afterFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, id))
            check(next.revision == old.revision.next() && FoodFinancialFramesV2.encodeSale(next.sale) == FoodFinancialFramesV2.encodeSale(sale) &&
                next.cursors.all { it.remainingQuantity.scaled == 0L } && next.remainingTenders.all { row -> row.shares.all { it.amount.minor == 0L } })
        }
        for (row in source.portions) {
            val old = row.body as FoodBodyV1.Portion
            check(afterFood(LocalFoodProfileV1.PORTION, row.id).body == old.copy(preparation = old.preparation.copy(
                revision = old.preparation.revision.next(), status = FoodPreparationStatus.CANCELLED_BEFORE_START,
                cancellationReasonId = request.reasonId, cancellationRequestedAt = evidence.recordedAt)))
        }
        check(afterFood(LocalFoodProfileV1.ORDER, before.orderId).body == source.order)
        val records = checkNotNull(stock); onlyReservations(records, release = true)
        val resolution = CollectionResolutionReference(foodCancellationResolutionId(evidence.operationId), before.orderId, before.id, refund.id)
        check(records.reservations.map { it.reservationId } == before.portions.map { it.reservationId } &&
            records.releases.map { it.reservation.reservationId } == before.portions.map { it.reservationId } &&
            records.releases.all { it.resolution == resolution })
        for (next in records.reservations) {
            val old = reservation(next.reservationId)
            check(old.retainsStockClaim && next.status == ReservationStatus.RELEASED && next.revision == old.revision.next() &&
                next.binding == old.binding && next.allocations == old.allocations && next.holds == old.holds && next.expiresAtEpochMillis == old.expiresAtEpochMillis)
        }
        extraClaims += ClaimChange.Acquire(LocalFoodFinancialProfileV1.source("unsent-resolution", resolution.resolutionId), refund.id)
        resultCode = "food.unsent.cancelled_refund_obligation"; documentId = request.refundId; amount = refund.amount
    }

    private fun close(request: LocalFoodFinancialRequestV1.CloseResolvedWork) {
        val (collection, source) = collectionSources(request.collectionId)
        check(stock == null && collection.orderId == request.orderId && collection.revision == request.expectedCollectionRevision &&
            source.row.revision == request.expectedOrderRevision && !collection.unresolved)
        val claims = optionalFinancial(LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, request.orderId))
            ?.body as? FoodFinancialBodyV1.ClaimIndex
        check(claims?.claimIds.orEmpty().isEmpty())
        val refundIds = (optionalFinancial(LocalFoodFinancialProfileV1.REFUND_INDEX, request.orderId)?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty()
        val refunds = refundIds.map { foodFinancialRefund(beforeFinancial(LocalFoodFinancialProfileV1.REFUND, it)) }
        check(refundIds == listOfNotNull(collection.cancellationRefundId))
        refunds.forEach { requireFoodRefundSource(collection, it) }
        check(refunds.all { !it.unresolved && it.due.minor == 0L })
        collection.saleId?.let { id -> requireFoodPaidSource(collection, foodFinancialSale(beforeFinancial(LocalFoodFinancialProfileV1.SALE, id)),
            foodFinancialEntitlement(beforeFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, id))) }
        if (collection.cancellationRefundId != null) {
            check(source.portions.all {
                (it.body as FoodBodyV1.Portion).preparation.let { p -> p.issueId == null && p.status == FoodPreparationStatus.CANCELLED_BEFORE_START &&
                    p.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER }
            })
        } else check(collection.status == FoodCollectionStatus.FINALIZED_PAID && source.portions.all {
            (it.body as FoodBodyV1.Portion).preparation.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER
        })
        check(afterFood(LocalFoodProfileV1.ORDER, request.orderId).body == source.order.copy(closedByOperationId = evidence.operationId) &&
            afterFood(LocalFoodProfileV1.OWNER_INDEX, source.order.ownerId).body == FoodBodyV1.OwnerIndex((source.index.body as FoodBodyV1.OwnerIndex).orderIds - request.orderId))
        resultCode = "food.work.closed"; documentId = request.orderId
    }

    private fun claim(request: LocalFoodFinancialRequestV1.RecordQualityClaim) {
        check(stock == null)
        val after = afterFinancial(LocalFoodFinancialProfileV1.CLAIM, request.claimId).body as FoodFinancialBodyV1.Claim
        check(after == FoodFinancialBodyV1.Claim(request.facts, evidence.originalActorId, evidence.recordedAt, evidence.originalActorId, null))
        fun append(target: FoodClaimIndexTargetV1, id: DomainId) {
            val key = foodClaimIndexId(target, id)
            val old = optionalFinancial(LocalFoodFinancialProfileV1.CLAIM_INDEX, key)?.body as? FoodFinancialBodyV1.ClaimIndex
            check(afterFinancial(LocalFoodFinancialProfileV1.CLAIM_INDEX, key).body ==
                FoodFinancialBodyV1.ClaimIndex(target, id, old?.claimIds.orEmpty() + request.claimId))
        }
        append(FoodClaimIndexTargetV1.OWNER, evidence.originalActorId)
        request.facts.assertedOrderId?.let { append(FoodClaimIndexTargetV1.ORDER, it) }
        resultCode = "food.quality_claim.recorded"; documentId = request.claimId
    }

    private fun stationIndexes(portions: List<FoodBodyV1.Portion>, adding: Boolean) {
        for (station in portions.flatMap { it.preparation.tasks }.map { it.stationId }.distinct()) {
            val key = AggregateKey(LocalFoodProfileV1.STATION_INDEX, station)
            val prior = if (key in inputs) (beforeFood(key.kind, key.id).body as FoodBodyV1.StationIndex).tasks else emptyList()
            val selected = portions.filter { p -> p.preparation.tasks.any { it.stationId == station } }.map {
                FoodTaskLocatorV1(foodTaskId(it.selection.portionId, station), it.orderId, it.selection.portionId)
            }
            check(selected.all { (it !in prior) == adding })
            check(afterFood(key.kind, key.id).body == FoodBodyV1.StationIndex(if (adding) prior + selected else prior.filterNot { old -> selected.any { it.taskId == old.taskId } }))
        }
    }
    private fun onlyReservations(records: InventoryPostedRecords, release: Boolean) {
        check(records.movements.isEmpty() && records.valuations.isEmpty() && records.issues.isEmpty() && records.counts.isEmpty() &&
            records.safety.isEmpty() && records.serialRegistrations.isEmpty() && records.kitchenConfirmations.isEmpty() && records.waste.isEmpty() &&
            records.newSourceIds.isEmpty() && records.effectKinds == List(records.reservations.size) {
                if (release) InventoryPostedEffectKind.RELEASE else InventoryPostedEffectKind.RESERVATION
            } && records.releases.size == if (release) records.reservations.size else 0)
    }
}

private fun foodFinancialQuantity(quantities: List<Quantity>): Quantity = quantities.fold(
    Quantity(quantities.first().unitId, 0, quantities.first().scale)) { total, quantity -> total + quantity }

/** Exact permitted collateral expiry, not permission to alter unrelated stock/reservations. */
private fun foodFinancialWithoutExpiry(records: InventoryPostedRecords, own: Set<DomainId>, at: UtcInstant): List<InventoryPostedEffectKind> {
    check(records.safety.all { (old, after) -> old.safety == StockSafety.CLEAR && after.safety == StockSafety.EXPIRED &&
        after == old.copy(safety = StockSafety.EXPIRED) && after.expiresAtEpochMillis != null &&
        checkNotNull(after.expiresAtEpochMillis) <= at.value.toEpochMilliseconds() })
    val expired = records.safety.map { it.second.key }.toSet()
    check(records.reservations.filter { it.reservationId !in own }.all { r -> r.status == ReservationStatus.HELD && r.holds.any {
        it.reason == ReservationHoldReason.EXPIRY && it.position in expired && r.allocations.any { a -> a.position == it.position }
    } })
    var reservation = 0
    return records.effectKinds.filter { kind -> when (kind) {
        InventoryPostedEffectKind.SAFETY -> false
        InventoryPostedEffectKind.RESERVATION -> records.reservations[reservation++].reservationId in own
        else -> true
    } }.also { check(reservation == records.reservations.size) }
}

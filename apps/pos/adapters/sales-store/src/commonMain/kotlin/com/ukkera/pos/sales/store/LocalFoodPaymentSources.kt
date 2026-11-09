package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Literal complete FOOD/cash source data, not a current view, employee grant or stock permit. */
internal class FoodPaymentLiteralCut(val inputs: List<FoodFinancialInputV1>,
    val collectionEnvelope: FoodFinancialEnvelopeV1, val orderEnvelope: FoodEnvelopeV1,
    val ownerEnvelope: FoodEnvelopeV1, val portions: List<FoodEnvelopeV1>, val batches: List<FoodEnvelopeV1>,
    val cash: CashSession, val drawer: LocalDrawerState, val custodian: LocalCustodianState,
    val work: LocalWorkState) {
    val collection: FoodCollection get() = foodFinancialCollection(collectionEnvelope)
    val order: FoodBodyV1.Order get() = orderEnvelope.body as FoodBodyV1.Order
    val reservationIds: List<DomainId> get() = collection.portions.map { it.reservationId }
    val reservedReservationIds: List<DomainId> get() = collection.portions
        .filter { it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION }.map { it.reservationId }
    val issuedReservationIds: List<DomainId> get() = collection.portions
        .filter { it.stockOrigin == FoodStockOrigin.ISSUED_BEFORE_COLLECTION }.map { it.reservationId }
    val financialResources: Set<RecoveryResourceKey> get() =
        foodEnvelopeResources(orderEnvelope, ownerEnvelope, portions) + orderResource(collection.id) +
            collection.portions.map { RecoveryResourceKey(RecoveryResourceKind.STOCK, it.reservationId) } +
            setOf(cashResource(cash.id), cashResource(drawer.id), cashResource(custodian.id), orderResource(work.id)) +
            work.orderIds.map(::orderResource) + work.returnIds.map(::entitlementResource)
}

/**
 * Exact census over the owner's actual finite observations. Every point/absence/claim is
 * consumed once by identity; the inventory owner independently closes the complete union
 * of every selected reservation, competing membership, literal member and range EOF.
 */
internal class FoodPaymentSources(private val observed: StockReadObservations) {
    private val rows = mutableSetOf<AggregateKey>()
    private val absences = mutableSetOf<AggregateKey>()
    private val claims = mutableSetOf<ClaimKey>()
    fun current(key: AggregateKey, schema: String): StreamedSourceObservationV2.Current {
        rows += key
        val source = observed.current(key) ?: error("food.payment.source_missing")
        check(source.schema == schema && source.generation.value >= 1 && sha256(source.payload.copyBytes()) == source.payloadHash)
        return source
    }
    fun image(key: AggregateKey, schema: String, generation: Revision, payload: StoredBytes): StreamedSourceObservationV2.Current =
        current(key, schema).also { check(it.generation == generation && it.payload == payload) }
    fun claim(key: ClaimKey, owner: DomainId?) {
        claims += key
        check((observed.claim(key) ?: error("food.payment.claim_missing")).ownerId == owner)
    }
    fun absent(key: AggregateKey) { absences += key; check(observed.absent(key)) }

    fun collection(inputs: List<FoodFinancialInputV1>, cashInput: FoodFinancialCashInputV1): FoodPaymentLiteralCut {
        check(inputs.isNotEmpty() && inputs.size <= FOOD_FINANCIAL_MAX_INPUTS_V2 && inputs.map { it.key() }.distinct().size == inputs.size)
        val financial = foodPaymentFinancialEnvelope(inputs.single { it.kind == LocalFoodFinancialProfileV1.COLLECTION }, observed.scope)
        val collection = foodFinancialCollection(financial)
        val order = foodPaymentFoodEnvelope(inputs.single { it.kind == LocalFoodProfileV1.ORDER && it.id == collection.orderId }, observed.scope)
        val body = order.body as FoodBodyV1.Order
        val owner = foodPaymentFoodEnvelope(inputs.single { it.kind == LocalFoodProfileV1.OWNER_INDEX && it.id == body.ownerId }, observed.scope)
        val portions = body.portionIds.map { id -> foodPaymentFoodEnvelope(inputs.single { it.kind == LocalFoodProfileV1.PORTION && it.id == id }, observed.scope) }
        val batches = body.batchIds.map { id -> foodPaymentFoodEnvelope(inputs.single { it.kind == LocalFoodProfileV1.PRICE_BATCH && it.id == id }, observed.scope) }
        val keys = listOf(AggregateKey(financial.kind, financial.id), AggregateKey(order.kind, order.id), AggregateKey(owner.kind, owner.id)) +
            portions.map { AggregateKey(it.kind, it.id) } + batches.map { AggregateKey(it.kind, it.id) }
        check(inputs.map { it.key() } == keys && body.financialCollectionId == collection.id && body.closedByOperationId == null &&
            body.handoff == null && body.ownerId == collection.currentOwnerId && body.creatorId == collection.originalCreatorId &&
            order.id in (owner.body as FoodBodyV1.OwnerIndex).orderIds)
        requireFoodFinancialQuote(collection, body, portions, batches)
        for (portion in portions) {
            val value = portion.body as FoodBodyV1.Portion
            check(value.orderId == order.id && value.priceBatchId in body.batchIds && value.recipe.activity == body.activity &&
                value.recipe.channel == body.service.channel)
            val batch = (batches.single { it.id == value.priceBatchId }.body as FoodBodyV1.PriceBatch)
            val quote = com.ukkera.pos.pricing.StoredPricingSnapshotCodec.decode(batch.quote)
            check(batch.orderId == order.id && quote.lines.single { it.portionId == portion.id }.quantity == value.selection.quantity &&
                batch.sources.single { it.portionId == portion.id }.skuId == value.recipe.pricingSkuId)
        }
        for (input in inputs) {
            image(input.key(), input.schema, input.revision, input.bytes())
            claim(foodSourceClaim(input.key()), input.id)
        }
        check(cashInput.sessionId == collection.cashSessionId && cashInput.storageRevision.value >= 1)
        val cashBytes = originalBytes(cashInput.snapshot)
        image(CashNonSaleStoreProfile.sessionKey(cashInput.sessionId), CashNonSaleStoreProfile.SESSION_SCHEMA,
            cashInput.storageRevision, cashBytes)
        val cash = SalesSnapshotCodec.decodeCashSession(cashInput.snapshot, SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION,
            observed.scope, cashInput.sessionId, Revision(cashInput.storageRevision.value - 1)))
        check(SalesSnapshotCodec.encodeCashSession(cash) == cashInput.snapshot && cash.custodianId == body.ownerId)
        val drawerRow = current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.DRAWER_KIND, cash.drawerId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.DRAWER_KIND))
        val drawer = localSalesDecode(drawerRow.payload, LocalDrawerState.serializer())
        check(drawer.scope.value() == observed.scope && drawer.id == cash.drawerId && drawer.revision.next() == drawerRow.generation &&
            drawer.currency == cash.expectedCash.currency && (drawer.activeSessionId == null || drawer.closingTransferId == null))
        localLabel(drawer.label)
        val custodianRow = current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, cash.custodianId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.CUSTODIAN_KIND))
        val custodian = localSalesDecode(custodianRow.payload, LocalCustodianState.serializer())
        check(custodian.scope.value() == observed.scope && custodian.id == cash.custodianId && custodian.revision.next() == custodianRow.generation)
        val workRow = current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, cash.custodianId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.WORK_KIND))
        val work = localSalesDecode(workRow.payload, LocalWorkState.serializer())
        check(work.scope.value() == observed.scope && work.id == cash.custodianId && work.revision.next() == workRow.generation &&
            listOf(work.orderIds, work.returnIds, work.pendingClaimIds).all { it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        // FOOD work is its genuine owner index above, not a fabricated retail order obligation.
        claim(configuredClaim(LocalSalesStoreProfile.DRAWER_KIND, drawer.id), drawer.id)
        claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id), custodian.id)
        claim(LocalSalesStoreProfile.activeDrawer(drawer.id), drawer.activeSessionId)
        claim(LocalSalesStoreProfile.activeCustodian(custodian.id), custodian.activeSessionId)
        claim(LocalSalesStoreProfile.unique("cash_session", cash.id), cash.id)
        claim(LocalSalesStoreProfile.unique("opening_source", cash.openingSourceId), cash.id)
        if (cash.status == CashSessionStatus.OPEN || cash.status == CashSessionStatus.CLOSING)
            check(drawer.activeSessionId == cash.id && custodian.activeSessionId == cash.id && drawer.closingTransferId == null)
        else check(drawer.activeSessionId != cash.id && custodian.activeSessionId != cash.id)
        for (attempt in collection.attempts) {
            claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id), collection.id)
            attempt.payment?.let {
                claim(LocalSalesStoreProfile.unique("payment_record", attempt.id), collection.id)
                if (attempt.tender == Tender.Cash) claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id), attempt.originalCashSessionId)
            }
        }
        return FoodPaymentLiteralCut(inputs, financial, order, owner, portions, batches, cash, drawer, custodian, work)
    }

    fun startVacancy(requirement: LocalPhysicalJournalRequirementV1) {
        val target = foodPaymentDomain(requirement).target
        for (key in foodPaymentVacantRows(requirement)) absent(key)
        for (change in foodPaymentLineageClaims(requirement)) claim(change.key, null)
        for (key in foodPaymentUnusedMoneyClaims(target.attemptId)) claim(key, null)
    }
    fun applicationVacancy(attemptId: DomainId) {
        claim(LocalSalesStoreProfile.unique("payment_record", attemptId), null)
        claim(CashNonSaleStoreProfile.cashSourceClaim(attemptId), null)
        absent(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attemptId))
    }
    fun finish(stock: Boolean) {
        check(observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>().filter { it.key.kind !in StockRelationalKindsV2.all }.map { it.key }.toSet() == rows &&
            observed.sources.filterIsInstance<StreamedSourceObservationV2.CurrentAbsent>().map { it.key }.toSet() == absences &&
            observed.sources.filterIsInstance<StreamedSourceObservationV2.Claim>().filter { it.key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces }.map { it.key }.toSet() == claims)
        if (!stock) check(observed.sources.none { it is StreamedSourceObservationV2.StockMember || it is StreamedSourceObservationV2.StockRange ||
            it is StreamedSourceObservationV2.Current && it.key.kind in StockRelationalKindsV2.all ||
            it is StreamedSourceObservationV2.Claim && it.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces })
    }
}

internal fun foodPaymentVacantRows(requirement: LocalPhysicalJournalRequirementV1): List<AggregateKey> =
    listOf(foodPaymentSlotKey(requirement.recordingOperationId), LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, requirement.attemptId)) +
        listOf(requirement.recordingOperationId, requirement.resolutionOperationId).flatMap {
            listOf(ReservedOperations.key(it), foodPaymentRawKey(it), foodPaymentApplicationKey(it))
        } + listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { AggregateKey(PhysicalRecordingSourceProfileV1.KIND, it) }
internal fun foodPaymentUnusedMoneyClaims(id: DomainId): List<ClaimKey> = listOf("payment_attempt", "refund_attempt", "payment_record", "refund_record")
    .map { LocalSalesStoreProfile.unique(it, id) } + CashNonSaleStoreProfile.cashSourceClaim(id)

/** Denial probes only; genuine complete-history future-operation absence is still core-owned. */
internal suspend fun foodPaymentVacant(view: CommandReadView, requirement: LocalPhysicalJournalRequirementV1): Boolean {
    var vacant = true
    for (key in foodPaymentVacantRows(requirement)) if (view.aggregate(key) != null) vacant = false
    for (claim in foodPaymentLineageClaims(requirement)) if (view.claim(claim.key) != null) vacant = false
    for (key in foodPaymentUnusedMoneyClaims(requirement.attemptId)) if (view.claim(key) != null) vacant = false
    for (id in listOf(requirement.recordingOperationId, requirement.resolutionOperationId)) if (view.retainedCommand(id) != null) vacant = false
    return vacant
}

/** Exact canonical STOCK subset of actual observations, including every member and range EOF. */
internal fun foodPaymentStockDigest(observed: StockReadObservations): PayloadDigest {
    val frames = observed.sources.filter { source -> when (source) {
        is StreamedSourceObservationV2.Current -> source.key.kind in StockRelationalKindsV2.all
        is StreamedSourceObservationV2.Claim -> source.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces
        is StreamedSourceObservationV2.StockMember, is StreamedSourceObservationV2.StockRange -> true
        else -> false
    } }.map { observed.copySourceFrameBytes(it) }
    check(frames.isNotEmpty())
    val header = "sales.food-payment-complete-stock-sources/v1".encodeToByteArray()
    val size = header.size.toLong() + 8L + frames.sumOf { it.size.toLong() + 4L }
    foodCapacity(size <= StockReadEvidenceV1.MAX_BYTES + 128L)
    val bytes = ByteArray(size.toInt()); journalPutInt(bytes, 0, header.size); header.copyInto(bytes, 4)
    journalPutInt(bytes, header.size + 4, frames.size)
    var offset = header.size + 8
    for (frame in frames) { journalPutInt(bytes, offset, frame.size); frame.copyInto(bytes, offset + 4); offset += frame.size + 4 }
    return try { sha256(bytes) } finally { bytes.fill(0); frames.forEach { it.fill(0) } }
}

/** Original financial custody only. Dine-in service retains the issued ingredients; it is
 * not a transfer of the collection, a new reservation or permission to pick up unpaid work. */
internal fun foodPaymentInitialPhysicalCustody(collection: FoodCollection, order: FoodBodyV1.Order,
    portions: List<FoodEnvelopeV1>, cash: CashSession): Boolean =
    collection.saleId == null && collection.cancellationRefundId == null && collection.responsibility.isEmpty() &&
        order.handoff == null && order.closedByOperationId == null && order.ownerId == collection.currentOwnerId &&
        collection.currentOwnerId == collection.collectorId && cash.custodianId == collection.collectorId &&
        collection.portions.isNotEmpty() && collection.portions.map { it.portionId } == portions.map { it.id } &&
        portions.all { row -> (row.body as FoodBodyV1.Portion).let { portion ->
            val entry = collection.portions.single { it.portionId == row.id }
            when (entry.stockOrigin) {
                FoodStockOrigin.RESERVED_FOR_COLLECTION -> entry.priorIssueId == null &&
                    portion.preparation.status == FoodPreparationStatus.NOT_SENT && portion.preparation.issueId == null &&
                    portion.normalizedIssue == null && portion.preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER
                FoodStockOrigin.ISSUED_BEFORE_COLLECTION -> portion.normalizedIssue != null &&
                    portion.preparation.issueId == entry.priorIssueId && portion.preparation.reservationId == entry.reservationId &&
                    FoodCurrentUnpaidPayable.isOrdinaryUnpaid(portion.preparation) &&
                    (portion.preparation.fulfillment != FoodFulfillmentStatus.SERVED || order.service.channel == LocalSalesChannel.DINE_IN)
            }
        } }

/** Exact recipe/collection relationship for the complete literal reserved/issued union.
 * Genuine first-issue actor, operation and timestamp are additionally closed by each owner. */
internal suspend fun requireFoodPaymentCustody(cut: FoodPaymentLiteralCut, selected: LocalStockPaymentCustody) {
    val collection = cut.collection
    check(foodPaymentInitialPhysicalCustody(collection, cut.order, cut.portions, cut.cash) &&
        selected.reserved.keys == cut.reservedReservationIds.toSet() && selected.issued.keys == cut.issuedReservationIds.toSet())
    for (row in cut.portions) {
        val portion = row.body as FoodBodyV1.Portion
        val entry = collection.portions.single { it.portionId == row.id }
        when (entry.stockOrigin) {
            FoodStockOrigin.RESERVED_FOR_COLLECTION -> {
                val stock = selected.reserved.getValue(entry.reservationId)
                check(stock.header.binding == foodFinancialStockBinding(collection) && stock.header.expiresAtEpochMillis == null)
                requireFoodReservationMembers(portion, stock.header) { consume -> stock.allocations.forEach { consume(it) } }
            }
            FoodStockOrigin.ISSUED_BEFORE_COLLECTION -> {
                val stock = selected.issued.getValue(entry.reservationId)
                val source = checkNotNull(portion.normalizedIssue)
                check(stock.issue.id == entry.priorIssueId && stock.header.binding == source.binding() &&
                    stock.header.binding.exposure == CollectionExposure.UNCOLLECTED &&
                    stock.header.binding.collectionId == foodStockContextId(collection.orderId) &&
                    stock.firstIssueProducer == StreamedProducerIdentityV2(source.operationId,
                        StockStreamingCommandsV2.FOOD, source.actorId, source.semanticDigest))
                requireFoodCurrentIssueHeader(portion, stock.issue)
                requireFoodReservationMembers(portion, stock.header) { consume -> stock.allocations.forEach { consume(it) } }
                requireFoodIssueMembers(portion, stock.issue) { consume -> stock.slices.forEach { consume(it) } }
            }
        }
    }
}

/** Same complete captured cut, not comparison of a convenient quantity/value summary. */
internal fun sameFoodPaymentReservedCut(left: LocalStockPaymentCut, right: LocalStockPaymentCut): Boolean =
    left.header == right.header && left.allocations.associateBy { it.allocation.allocationId } ==
        right.allocations.associateBy { it.allocation.allocationId } && left.items == right.items &&
        left.deadline == right.deadline && left.structurallySafe == right.structurallySafe

internal fun sameFoodPaymentIssuedCut(left: LocalStockPaymentIssuedCut, right: LocalStockPaymentIssuedCut): Boolean =
    sameFoodPaymentIssuedOrigin(left, right) && left.issue.revision == right.issue.revision &&
        left.issue.startedStationIds == right.issue.startedStationIds

/** Kitchen START may advance the current issue header without changing its original custody. */
internal fun sameFoodPaymentIssuedOrigin(left: LocalStockPaymentIssuedCut, right: LocalStockPaymentIssuedCut): Boolean =
    left.header == right.header && sameFoodPaymentIssueOrigin(left.issue, right.issue) && left.firstIssueProducer == right.firstIssueProducer &&
        left.allocations.associateBy { it.allocation.allocationId } == right.allocations.associateBy { it.allocation.allocationId } &&
        left.slices.associateBy { it.allocation.allocationId } == right.slices.associateBy { it.allocation.allocationId }

private fun sameFoodPaymentIssueOrigin(left: LocalStockIssueHeader, right: LocalStockIssueHeader): Boolean =
    left.id == right.id && left.reservationId == right.reservationId && left.binding == right.binding &&
        left.purpose == right.purpose && left.allocationCount == right.allocationCount &&
        left.originalValue == right.originalValue && left.recipeSnapshotId == right.recipeSnapshotId &&
        left.requiredStationIds == right.requiredStationIds && left.disposition == right.disposition

internal fun foodPaymentDeadline(selected: Collection<LocalStockPaymentCut>): UtcInstant? = selected.mapNotNull { it.deadline }.minOrNull()
internal fun earlierFoodPaymentDeadline(current: UtcInstant?, original: UtcInstant?): UtcInstant? = listOfNotNull(current, original).minOrNull()

/** Pure cash CAS encoding from literal revisions; it never builds a StoredAggregate. */
internal fun foodPaymentCashWrite(after: CashSession, beforeStorageRevision: Revision): AggregateChange {
    check(after.revision == beforeStorageRevision && beforeStorageRevision.value >= 1)
    return AggregateChange(CashNonSaleStoreProfile.sessionKey(after.id), beforeStorageRevision,
        CashNonSaleStoreProfile.SESSION_SCHEMA, originalBytes(SalesSnapshotCodec.encodeCashSession(after)))
}

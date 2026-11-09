package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Actual closed accepted obligation and its original money roots, not kitchen/stock authority. */
internal class FoodRefundCut(val refund: FoodFinancialRow, val collection: FoodFinancialRow,
    val index: FoodFinancialRow, val sale: FoodFinancialRow?, val entitlement: FoodFinancialRow?, val batches: List<FoodRow>) {
    val value: FoodRefundObligation get() = foodFinancialRefund(refund.envelope)
    val inputs: List<FoodFinancialInputV1> get() = listOf(foodPaymentInput(refund), foodPaymentInput(collection),
        foodPaymentInput(index)) + listOfNotNull(sale?.let(::foodPaymentInput), entitlement?.let(::foodPaymentInput)) +
        batches.map(::foodPaymentInput)
}

/** No current stock/readiness or claim qualification is a prerequisite to pay an ALREADY accepted liability. */
internal suspend fun foodRefundCut(view: CommandReadView, id: DomainId): FoodRefundCut {
    val refund = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND, id)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val value = foodFinancialRefund(refund.envelope)
    val collection = foodFinancialRead(view, LocalFoodFinancialProfileV1.COLLECTION, value.originalCollection.id)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val index = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND_INDEX, value.orderId)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val sale = value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.SALE, it)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING) }
    val rights = value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.ENTITLEMENT, it)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING) }
    requireFoodRefundBasis(value, foodFinancialCollection(collection.envelope),
        (index.envelope.body as FoodFinancialBodyV1.RefundIndex), sale?.let { foodFinancialSale(it.envelope) },
        rights?.let { foodFinancialEntitlement(it.envelope) })
    val batches = value.originalCollection.quote.batches.map { batch ->
        foodRead(view, LocalFoodProfileV1.PRICE_BATCH, batch.id)
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    }
    val cut = FoodRefundCut(refund, collection, index, sale, rights, batches)
    requireFoodRefundPrices(cut.inputs, view.scope, value)
    for (attempt in value.originalCollection.attempts) {
        check(view.claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id)) == value.originalCollection.id)
        attempt.payment?.let {
            check(view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) == value.originalCollection.id)
            if (attempt.tender == Tender.Cash)
                check(view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) == attempt.originalCashSessionId)
        }
    }
    for (attempt in value.attempts) {
        check(view.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) == value.id)
        attempt.refund?.let {
            check(view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) == value.id)
            if (attempt.tender == Tender.Cash)
                check(view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) == attempt.originalCashSessionId)
        }
    }
    return cut
}

/** Both exact closed economic roots. The unpaid quoted remainder can NEVER become refund value. */
internal fun requireFoodRefundBasis(refund: FoodRefundObligation, collection: FoodCollection,
    index: FoodFinancialBodyV1.RefundIndex, sale: FoodPaidSale?, entitlement: FoodReceiptEntitlement?) {
    requireFoodRefundSource(collection, refund)
    check(index.orderId == refund.orderId && index.refundIds == listOf(refund.id) &&
        refund.obligations.map { it.originalPaymentId } == refund.originalCollection.attempts.mapNotNull { it.payment?.attemptId } &&
        refund.amount == refund.originalCollection.collected && refund.due.minor >= 0L)
    if (refund.saleId == null) {
        check(sale == null && entitlement == null && collection.saleId == null && refund.originalCollection.saleId == null)
    } else {
        val paid = checkNotNull(sale); val rights = checkNotNull(entitlement)
        check(paid.id == refund.saleId && rights.sale.id == paid.id)
        requireFoodPaidSource(collection, paid, rights)
        // The original whole-unsent cancellation consumed all quantity/component rights at acceptance.
        check(rights.cursors.all { it.remainingQuantity.scaled == 0L && it.remainingComponents.all { c -> c.amount.minor == 0L } } &&
            rights.remainingTenders.all { row -> row.shares.all { it.amount.minor == 0L } })
    }
}

internal fun requireFoodRefundPrices(inputs: List<FoodFinancialInputV1>, scope: BranchScope, refund: FoodRefundObligation) {
    val batches = foodFinancialPriceBasesV2(inputs, scope, allowStockInputs = true)
    check(batches.map { it.id } == refund.originalCollection.quote.batches.map { it.id } &&
        FoodFinancialSnapshots.sameQuote(FoodFinancialQuote(batches), refund.originalCollection.quote))
    for (input in inputs.filter { it.kind == LocalFoodProfileV1.PRICE_BATCH }) {
        val value = foodPaymentFoodEnvelope(input, scope)
        val body = value.body as FoodBodyV1.PriceBatch
        check(body.orderId == refund.orderId && StoredPricingSnapshotCodec.decode(body.quote).snapshotId == input.id)
    }
}

/** Full literal economic source identity, not a caller-provided entitlement or capacity proof. */
internal fun foodRefundBasisDigest(inputs: List<FoodFinancialInputV1>): PayloadDigest = sha256(journalFrame(
    listOf(journalText("sales.food-refund-actual-obligation-sources/v1")) + inputs.map { input -> journalFrame(listOf(
        input.kind, input.id.value, input.revision.value.toString(), input.schema, input.digest.value,
        input.operationId.value).map(::journalText), 4_096) }, 65_536))

internal fun foodRefundBasisResources(refund: FoodRefundObligation): Set<RecoveryResourceKey> =
    setOf(entitlementResource(refund.id), entitlementResource(refund.orderId), orderResource(refund.orderId),
        orderResource(refund.originalCollection.id)) + listOfNotNull(refund.saleId?.let(::entitlementResource)) +
        refund.originalCollection.quote.batches.map { orderResource(it.id) } +
        refund.obligations.map { entitlementResource(it.originalPaymentId) } +
        refund.originalCollection.attempts.map { cashResource(it.originalCashSessionId) } +
        refund.attempts.flatMap { listOf(entitlementResource(it.id), cashResource(it.originalCashSessionId)) }

internal class FoodRefundLiteralCut(val inputs: List<FoodFinancialInputV1>, val refundEnvelope: FoodFinancialEnvelopeV1,
    val collectionEnvelope: FoodFinancialEnvelopeV1, val indexEnvelope: FoodFinancialEnvelopeV1,
    val saleEnvelope: FoodFinancialEnvelopeV1?, val entitlementEnvelope: FoodFinancialEnvelopeV1?,
    val cash: CashSession, val drawer: LocalDrawerState, val custodian: LocalCustodianState, val work: LocalWorkState) {
    val refund: FoodRefundObligation get() = foodFinancialRefund(refundEnvelope)
    val financialResources: Set<RecoveryResourceKey> get() = foodRefundBasisResources(refund) +
        setOf(cashResource(cash.id), cashResource(drawer.id), cashResource(custodian.id), orderResource(work.id)) +
        work.orderIds.map(::orderResource) + work.returnIds.map(::entitlementResource) + work.pendingClaimIds.map(::entitlementResource)
}

/** Exact same-loan financial/cash census. No stock member/range or absent-source fallback is admitted. */
internal class FoodRefundSources(private val observed: StockReadObservations) {
    private val rows = mutableSetOf<AggregateKey>()
    private val absences = mutableSetOf<AggregateKey>()
    private val claims = mutableSetOf<ClaimKey>()
    fun current(key: AggregateKey, schema: String): StreamedSourceObservationV2.Current {
        rows += key
        return (observed.current(key) ?: error("food.refund.source_missing")).also {
            check(it.schema == schema && it.generation.value >= 1 && sha256(it.payload.copyBytes()) == it.payloadHash)
        }
    }
    fun image(key: AggregateKey, schema: String, generation: Revision, payload: StoredBytes) =
        current(key, schema).also { check(it.generation == generation && it.payload == payload) }
    fun claim(key: ClaimKey, owner: DomainId?) { claims += key; check((observed.claim(key) ?: error("food.refund.claim_missing")).ownerId == owner) }
    fun absent(key: AggregateKey) { absences += key; check(observed.absent(key)) }
    fun refund(inputs: List<FoodFinancialInputV1>, cashInput: FoodFinancialCashInputV1): FoodRefundLiteralCut {
        check(inputs.isNotEmpty() && inputs.size <= FOOD_FINANCIAL_MAX_INPUTS_V2 && inputs.map { it.key() }.distinct().size == inputs.size)
        fun financial(kind: String): FoodFinancialEnvelopeV1 = foodRefundFinancialEnvelope(inputs.single { it.kind == kind }, observed.scope)
        val row = financial(LocalFoodFinancialProfileV1.REFUND); val refund = foodFinancialRefund(row)
        val collection = financial(LocalFoodFinancialProfileV1.COLLECTION); val index = financial(LocalFoodFinancialProfileV1.REFUND_INDEX)
        val sale = refund.saleId?.let { financial(LocalFoodFinancialProfileV1.SALE) }
        val entitlement = refund.saleId?.let { financial(LocalFoodFinancialProfileV1.ENTITLEMENT) }
        requireFoodRefundBasis(refund, foodFinancialCollection(collection), index.body as FoodFinancialBodyV1.RefundIndex,
            sale?.let(::foodFinancialSale), entitlement?.let(::foodFinancialEntitlement))
        val keys = listOf(AggregateKey(row.kind, row.id), AggregateKey(collection.kind, collection.id), AggregateKey(index.kind, index.id)) +
            listOfNotNull(sale?.let { AggregateKey(it.kind, it.id) }, entitlement?.let { AggregateKey(it.kind, it.id) }) +
            refund.originalCollection.quote.batches.map { AggregateKey(LocalFoodProfileV1.PRICE_BATCH, it.id) }
        check(inputs.map { it.key() } == keys)
        requireFoodRefundPrices(inputs, observed.scope, refund)
        for (input in inputs) { image(input.key(), input.schema, input.revision, input.bytes()); claim(foodSourceClaim(input.key()), input.id) }
        for (attempt in refund.originalCollection.attempts) {
            claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id), refund.originalCollection.id)
            attempt.payment?.let {
                claim(LocalSalesStoreProfile.unique("payment_record", attempt.id), refund.originalCollection.id)
                if (attempt.tender == Tender.Cash) claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id), attempt.originalCashSessionId)
            }
        }
        for (attempt in refund.attempts) {
            claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id), refund.id)
            attempt.refund?.let {
                claim(LocalSalesStoreProfile.unique("refund_record", attempt.id), refund.id)
                if (attempt.tender == Tender.Cash) claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id), attempt.originalCashSessionId)
            }
        }
        check(cashInput.storageRevision.value >= 1)
        image(CashNonSaleStoreProfile.sessionKey(cashInput.sessionId), CashNonSaleStoreProfile.SESSION_SCHEMA,
            cashInput.storageRevision, originalBytes(cashInput.snapshot))
        val cash = SalesSnapshotCodec.decodeCashSession(cashInput.snapshot, SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION,
            observed.scope, cashInput.sessionId, Revision(cashInput.storageRevision.value - 1)))
        check(SalesSnapshotCodec.encodeCashSession(cash) == cashInput.snapshot)
        val drawerRow = current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.DRAWER_KIND, cash.drawerId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.DRAWER_KIND))
        val drawer = localSalesDecode(drawerRow.payload, LocalDrawerState.serializer())
        check(drawer.scope.value() == observed.scope && drawer.id == cash.drawerId && drawer.revision.next() == drawerRow.generation &&
            drawer.currency == cash.expectedCash.currency && (drawer.activeSessionId == null || drawer.closingTransferId == null)); localLabel(drawer.label)
        val custodianRow = current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, cash.custodianId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.CUSTODIAN_KIND))
        val custodian = localSalesDecode(custodianRow.payload, LocalCustodianState.serializer())
        check(custodian.scope.value() == observed.scope && custodian.id == cash.custodianId && custodian.revision.next() == custodianRow.generation)
        val workRow = current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, cash.custodianId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.WORK_KIND))
        val work = localSalesDecode(workRow.payload, LocalWorkState.serializer())
        check(work.scope.value() == observed.scope && work.id == cash.custodianId && work.revision.next() == workRow.generation &&
            listOf(work.orderIds, work.returnIds, work.pendingClaimIds).all { it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        claim(configuredClaim(LocalSalesStoreProfile.DRAWER_KIND, drawer.id), drawer.id)
        claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id), custodian.id)
        claim(LocalSalesStoreProfile.activeDrawer(drawer.id), drawer.activeSessionId)
        claim(LocalSalesStoreProfile.activeCustodian(custodian.id), custodian.activeSessionId)
        claim(LocalSalesStoreProfile.unique("cash_session", cash.id), cash.id)
        claim(LocalSalesStoreProfile.unique("opening_source", cash.openingSourceId), cash.id)
        if (cash.status == CashSessionStatus.OPEN || cash.status == CashSessionStatus.CLOSING)
            check(drawer.activeSessionId == cash.id && custodian.activeSessionId == cash.id && drawer.closingTransferId == null)
        else check(drawer.activeSessionId != cash.id && custodian.activeSessionId != cash.id)
        return FoodRefundLiteralCut(inputs, row, collection, index, sale, entitlement, cash, drawer, custodian, work)
    }
    fun startVacancy(requirement: LocalPhysicalJournalRequirementV1) {
        for (key in foodRefundVacantRows(requirement)) absent(key)
        for (change in foodRefundLineageClaims(requirement)) claim(change.key, null)
        for (key in foodRefundUnusedMoneyClaims(requirement.attemptId)) claim(key, null)
    }
    fun applicationVacancy(attemptId: DomainId) {
        claim(LocalSalesStoreProfile.unique("refund_record", attemptId), null)
        claim(CashNonSaleStoreProfile.cashSourceClaim(attemptId), null)
        absent(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attemptId))
    }
    fun finish() {
        check(observed.sources.all { it is StreamedSourceObservationV2.Current || it is StreamedSourceObservationV2.CurrentAbsent || it is StreamedSourceObservationV2.Claim })
        check(observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>().map { it.key }.toSet() == rows &&
            observed.sources.filterIsInstance<StreamedSourceObservationV2.CurrentAbsent>().map { it.key }.toSet() == absences &&
            observed.sources.filterIsInstance<StreamedSourceObservationV2.Claim>().map { it.key }.toSet() == claims &&
            rows.none { it.kind in StockRelationalKindsV2.all || it.kind in LocalStockStoreProfile.inventoryKinds } &&
            claims.none { it.namespace in LocalStockStoreProfile.inventoryClaimNamespaces })
    }
}

internal fun foodRefundVacantRows(requirement: LocalPhysicalJournalRequirementV1): List<AggregateKey> =
    listOf(foodRefundSlotKey(requirement.recordingOperationId), foodRefundRawKey(requirement.recordingOperationId),
        foodRefundResolutionKey(requirement.resolutionOperationId), LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, requirement.attemptId)) +
        listOf(requirement.recordingOperationId, requirement.resolutionOperationId).flatMap { listOf(ReservedOperations.key(it), foodRefundApplicationKey(it)) } +
        listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { AggregateKey(PhysicalRecordingSourceProfileV1.KIND, it) }
internal fun foodRefundUnusedMoneyClaims(id: DomainId): List<ClaimKey> = foodPaymentUnusedMoneyClaims(id)
internal suspend fun foodRefundVacant(view: CommandReadView, requirement: LocalPhysicalJournalRequirementV1): Boolean {
    var vacant = true
    for (key in foodRefundVacantRows(requirement)) if (view.aggregate(key) != null) vacant = false
    for (change in foodRefundLineageClaims(requirement)) if (view.claim(change.key) != null) vacant = false
    for (key in foodRefundUnusedMoneyClaims(requirement.attemptId)) if (view.claim(key) != null) vacant = false
    for (id in listOf(requirement.recordingOperationId, requirement.resolutionOperationId)) if (view.retainedCommand(id) != null) vacant = false
    return vacant
}

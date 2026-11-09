package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStockOrigin
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import org.json.JSONArray
import org.json.JSONObject

/** Exact actual successful protected payloads, not a grant, source lease or accepting
 * history verifier. Every page must be complete and observe one unchanged branch cut. */
internal class CafeReadCut {
    private val values = linkedMapOf<String, StoredBytes>()
    private var revision: Revision? = null
    val branchRevision: Revision get() = checkNotNull(revision)
    val payloads: Map<String, StoredBytes> get() = values.toMap()
    fun add(symbol: String, reply: ReadReply.Data): StoredBytes {
        check(values.put(symbol, reply.payload) == null)
        revision?.let { check(it == reply.branchRevision) { "Current native reads did not observe one unchanged business cut" } }
        revision = reply.branchRevision
        return reply.payload
    }
    fun requireSame(after: CafeReadCut) {
        check(branchRevision == after.branchRevision && payloads == after.payloads) { "Recovery changed the exact current protected cut" }
    }
    fun data(): JSONObject = JSONObject().put("branchRevision", branchRevision.value).put("payloads", JSONObject().also { json ->
        payloads.forEach { (symbol, bytes) -> json.put(symbol, cafeBase64(bytes)) }
    })
    override fun toString(): String = "CafeReadCut([REDACTED])"
}
internal enum class CafeCutPhase { QUOTED_UNPAID, RESERVED_BEFORE_START, STARTED_UNAPPLIED, DURABLE_UNAPPLIED, PAID_UNSENT }
internal data class CafeProtectedCut(val phase: CafeCutPhase, val reads: CafeReadCut, val food: LocalFoodCheckKitchenResultV3,
    val cash: LocalCashReadResultV1.OwnCash, val collection: LocalFoodCollectionViewV1?) {
    fun requireSame(after: CafeProtectedCut) { check(phase == after.phase); reads.requireSame(after.reads) }
}
internal suspend fun cafeConfiguration(client: NativeCafeCashClient, kind: String, id: DomainId? = null): LocalConfigurationPageV1 =
    LocalConfigurationCodec.decodePage(client.read(ReadRequest(LocalConfigurationProfile.QUERY,
        LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id)))).payload)
internal suspend fun cafeFood(client: NativeCafeCashClient, plan: NativeCafeCashPlan): LocalFoodCheckKitchenResultV3 {
    val value = LocalFoodCheckKitchenCodecV3.result(client.read(LocalFoodCheckKitchenCodecV3.read(
        LocalFoodQueryV1.Order(plan.orderId, limit = 2))).payload)
    check(value.food.unavailable == null && value.food.orders.isEmpty() && value.food.tasks.isEmpty() && value.food.nextAfter == null)
    return value
}
internal suspend fun cafeOwnCash(client: NativeCafeCashClient): LocalCashReadResultV1.OwnCash =
    LocalCashReadCodec.decode(client.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)).payload)
        as? LocalCashReadResultV1.OwnCash ?: error("Actual own CASH projection is unavailable")
internal suspend fun cafeCollection(client: NativeCafeCashClient, plan: NativeCafeCashPlan): LocalFoodCollectionViewV1 =
    (LocalFoodFinancialReadCodecV1.result(client.read(LocalFoodFinancialReadCodecV1.read(
        LocalFoodFinancialQueryV1.Collection(plan.collectionId))).payload) as? LocalFoodFinancialReadResultV1.Collection)?.value
        ?: error("Actual FOOD collection projection is unavailable")

internal object NativeCafeCashProjections {
    suspend fun read(client: NativeCafeCashClient, plan: NativeCafeCashPlan, seed: CafeCashSeed, phase: CafeCutPhase,
        label: String, evidence: NativeCafeCashEvidence, requirementBytes: StoredBytes? = null,
        outcome: LocalFoodCashOutcome.CashReceived? = null, originals: List<CafeSavedOriginal> = emptyList()): CafeProtectedCut {
        val cut = CafeReadCut()
        val requested = seed.published.requested
        seed.published.requireCurrent(checkNotNull(client.publications))
        requireSources(client, plan, seed, cut)
        requireCafeOpening(client, plan, seed.openingBasisRevision, seed.openingRecordedAt, cut)
        val reserved = phase != CafeCutPhase.QUOTED_UNPAID
        val itemPage = LocalStockQueries.decodeItems(cut.add("STOCK_ITEMS", client.read(LocalStockCommands.items())))
        check(itemPage.nextAfter == null && itemPage.items.size == plan.stocks.size &&
            itemPage.items.map { it.skuId }.toSet() == plan.stocks.map { it.skuId }.toSet())
        check(LocalStockRelationalQueriesV2.normalized(cut.add("STOCK_PROFILE", client.read(LocalStockRelationalQueriesV2.profile()))))
        for (stock in plan.stocks) {
            val quantities = requireCafeStock(client, plan, stock, reserved, cut)
            val item = itemPage.items.single { it.skuId == stock.skuId }
            check(item.unitId == plan.unit(stock.role).id && item.quantityScale == 0 && item.positionCount == 1 &&
                item.storageRevision == quantities.itemStorageRevision)
            val history = LocalStockRelationalQueriesV2.decodeHistory(cut.add("STOCK_HISTORY/${stock.role}", client.read(
                LocalStockRelationalQueriesV2.history(stock.skuId, plan.warehouseId, afterOrdinal = 0, limit = 25))))
            check(history.skuId == stock.skuId && history.warehouseId == plan.warehouseId && history.nextOrdinal == null &&
                history.itemRevision == quantities.itemStorageRevision && history.rows.size == 1)
            val row = history.rows.single()
            check(row.sourceId == stock.sourceId && row.ordinal > 0 && row.movement == "OPENING" &&
                row.quantityDelta == plan.quantity(stock.role, stock.onHand) && row.originalValueDelta == stock.carryingValue &&
                row.carryingValueDelta == stock.carryingValue && row.resultingQuantity == plan.quantity(stock.role, stock.onHand) &&
                row.resultingValue == stock.carryingValue && row.recordedAt == seed.openingRecordedAt) {
                "Paid/unsent work must retain the original opening quantities/value with no ISSUE or other value event"
            }
        }
        val food = LocalFoodCheckKitchenCodecV3.result(cut.add("CURRENT_FOOD_V3_COMPLETE_TWO", client.read(
            LocalFoodCheckKitchenCodecV3.read(LocalFoodQueryV1.Order(plan.orderId, limit = plan.cafe.getInt("selected_page_size"))))))
        check(food.food.unavailable == null && food.food.orders.isEmpty() && food.food.tasks.isEmpty() && food.food.nextAfter == null && food.delivery == null)
        val detail = checkNotNull(food.food.detail)
        val summary = detail.summary
        val actor = client.original.material.coordinates.firstActorId
        check(summary.orderId == plan.orderId && summary.ownerId == actor && summary.activity == LocalSalesActivity.CAFE &&
            summary.service == plan.service && summary.portionCount == 2 && !summary.workClosed &&
            summary.handoffId == null && summary.intendedRecipientId == null && !detail.chargeResolutionRequired &&
            detail.portions.map { it.portionId } == plan.portionIds && detail.originalQuotedTotal == plan.payable)
        for (portion in detail.portions) {
            val preparation = portion.preparation
            check(portion.quantity == plan.quantity("latte", 1) && portion.commercialLineId == plan.portionIds.first() &&
                portion.modifierIds.isEmpty() && portion.itemNote == "" && portion.seatNote == "" &&
                preparation.portionId == portion.portionId && preparation.status == FoodPreparationStatus.NOT_SENT &&
                preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && preparation.tasks.isEmpty() &&
                preparation.reservationId == null && preparation.issueId == null && preparation.issueRevision == null && preparation.sentAt == null &&
                preparation.cancellationReasonId == null && preparation.cancellationRequestedAt == null &&
                preparation.servedAt == null && preparation.pickedUpAt == null)
        }
        val kitchen = checkNotNull(food.kitchen)
        check(kitchen.orderId == plan.orderId && kitchen.orderStorageRevision == summary.revision && kitchen.unsentPortionCount == 2 &&
            kitchen.status == LocalFoodCheckKitchenStatusV1.UNKNOWN) // Diagnostic, NOT positive Send authority/capability.
        val own = LocalFoodReadCodecV1.result(cut.add("OWN_FOOD_COMPLETE", client.read(LocalFoodReadCodecV1.read(LocalFoodQueryV1.Own()))))
        check(own.unavailable == null && own.nextAfter == null && own.detail == null && own.tasks.isEmpty() && own.orders == listOf(summary))
        val station = LocalFoodReadCodecV1.result(cut.add("STATION_TASKS_COMPLETE", client.read(LocalFoodReadCodecV1.read(
            LocalFoodQueryV1.Station(plan.stationId)))))
        check(station.unavailable == null && station.nextAfter == null && station.detail == null && station.orders.isEmpty() && station.tasks.isEmpty())
        check(LocalStockRelationalQueriesV2.normalized(cut.add("STATION_NATIVE_STOCK_PROFILE", client.read(
            LocalStockRelationalQueriesV2.stationProfile(plan.stationId)))))

        val cash = LocalCashReadCodec.decode(cut.add("OWN_CASH", client.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash))))
            as? LocalCashReadResultV1.OwnCash ?: error("The current own CASH ledger is unavailable")
        val session = checkNotNull(cash.session)
        val paid = phase == CafeCutPhase.PAID_UNSENT
        check(cash.custodianId == actor && cash.unresolvedReturnIds.isEmpty() && cash.unsettledOrderIds.all { it == plan.orderId } &&
            session.id == plan.cashSessionId && session.drawerId == plan.drawerId && session.custodianId == actor && session.status == "OPEN" &&
            session.businessDate == seed.cashBusinessDate && session.opening == plan.openingCash && session.reservedCashOut == plan.zero &&
            session.expectedCash == (if (paid) plan.openingCash + plan.payable else plan.openingCash))
        check(session.received == (if (paid) plan.received else plan.zero))
        check(session.change == (if (paid) plan.change else plan.zero))
        check(listOf(session.refunds, session.payIns, session.payOuts, session.safeDrops, session.externalCollections, session.externalRefunds)
            .all { it == plan.zero } && session.pendingTransferIds.isEmpty() && session.closingExpected == null && session.counted == null &&
            session.variance == null && session.retainedFloat == null && session.actualSafeDelivery == null && session.closingTransferId == null)
        val hasAttempt = phase in setOf(CafeCutPhase.STARTED_UNAPPLIED, CafeCutPhase.DURABLE_UNAPPLIED, CafeCutPhase.PAID_UNSENT)
        check(session.pendingAttemptIds == (if (hasAttempt && !paid) listOf(plan.attemptId) else emptyList<DomainId>()))
        val sessionRead = LocalCashReadCodec.decode(cut.add("ORIGINAL_CASH_SESSION", client.read(LocalCashReadCodec.request(
            LocalCashQueryV1.Session(plan.cashSessionId))))) as? LocalCashReadResultV1.Session ?: error("Actual original CASH session is unavailable")
        check(sessionRead.session == session)
        val drawers = LocalCashReadCodec.decode(cut.add("DRAWERS_COMPLETE", client.read(LocalCashReadCodec.request(LocalCashQueryV1.Drawers()))))
            as? LocalCashReadResultV1.Drawers ?: error("Actual drawer choices are unavailable")
        check(drawers.nextAfter == null && drawers.drawers.size == 1 && drawers.drawers.single().id == plan.drawerId &&
            drawers.drawers.single().currency == Currency.EGP && drawers.drawers.single().assigned && !drawers.drawers.single().awaitingClosingReceipt)

        val collection: LocalFoodCollectionViewV1?
        if (phase == CafeCutPhase.QUOTED_UNPAID) {
            collection = null
            check(summary.financialCollectionId == null && detail.collectionState == "UNPAID_NO_COLLECTION" &&
                detail.financialHandoff == null && food.currentUnpaidPayable == plan.payable)
        } else {
            val result = LocalFoodFinancialReadCodecV1.result(cut.add("ORIGINAL_COLLECTION", client.read(
                LocalFoodFinancialReadCodecV1.read(LocalFoodFinancialQueryV1.Collection(plan.collectionId)))))
                as? LocalFoodFinancialReadResultV1.Collection ?: error("The complete actual collection is unavailable")
            collection = result.value
            val status = if (paid) "FINALIZED_PAID" else if (hasAttempt) "OUTCOME_UNKNOWN" else "LOCKED_UNPAID"
            check(summary.financialCollectionId == plan.collectionId && detail.collectionState == status && food.currentUnpaidPayable == null &&
                collection.collectionId == plan.collectionId && collection.orderId == plan.orderId && collection.orderStorageRevision == summary.revision &&
                collection.status == status && collection.executionProfile == "GENUINE_FOOD_CASH_V1" && collection.originalPayable == plan.payable &&
                collection.collected == (if (paid) plan.payable else plan.zero))
            check(collection.unpaidRemaining == (if (paid) plan.zero else plan.payable))
            check(collection.saleId == (if (paid) plan.saleId else null))
            check(collection.cancellationRefundId == null && !collection.workClosed && collection.refundLiabilityDue == plan.zero &&
                collection.unresolvedClaimIds.isEmpty() && collection.portions.map { it.portionId } == plan.portionIds &&
                collection.portions.all { it.originalQuantity == plan.quantity("latte", 1) &&
                    it.preparationStatus == FoodPreparationStatus.NOT_SENT && it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER &&
                    it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION && it.reservationStatus == "RESERVED" && !it.chargeDecisionRequired })
            if (hasAttempt) {
                val attempt = collection.attempts.single()
                check(attempt.attemptId == plan.attemptId && attempt.status == (if (paid) "ATTESTED_PAID" else "AWAITING_ATTESTATION"))
                check(attempt.tender == LocalTenderViewV1("CASH") && attempt.amount == plan.payable &&
                    attempt.intentOperationId == plan.startOperationId && attempt.appliedPaymentObservationId == (if (paid) plan.recordingOperationId else null))
            } else check(collection.attempts.isEmpty())
        }
        if (hasAttempt) {
            val requirement = checkNotNull(requirementBytes)
            val recording = LocalFoodPaymentReadCodec.decode(cut.add("ORIGINAL_ATTEMPT_RECORDING", client.read(
                LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.AttemptRecording(plan.orderId, plan.collectionId,
                    plan.cashSessionId, plan.attemptId, plan.startOperationId))))) as? LocalFoodPaymentReadResult.AttemptRecording
                ?: error("The actual original attempt linkage is unavailable")
            check(recording.target == plan.target() && recording.amount == plan.payable && recording.requirementDigest == cafeDigest(requirement) &&
                recording.masterAllocationId == plan.masterAllocationId && recording.clientAllocationId == plan.clientAllocationId &&
                recording.clientDeviceId == client.original.peer.deviceId && recording.requirementBytes() == requirement)
            if (phase == CafeCutPhase.STARTED_UNAPPLIED) check(recording.original == null) // SQL read only; no native absence/capacity claim.
            else {
                val original = LocalFoodPaymentReadCodec.decode(cut.add("ORIGINAL_O_WITH_CURRENT_ACCOUNTING", client.read(
                    LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Original(plan.recordingOperationId)))))
                    as? LocalFoodPaymentReadResult.Original ?: error("The actual original O is unavailable")
                client.requireOriginal(original, checkNotNull(outcome))
                check(recording.original == original.summary)
                val pending = LocalFoodPaymentReadCodec.decode(cafeStored(evidence.binding("ORIGINAL_PENDING_NATIVE_READ_BYTES") as String))
                    as? LocalFoodPaymentReadResult.Original ?: error("Original pending DATA was not retained before application")
                requireSameCafeImmutableOriginal(pending, original)
                check(original.summary.applicationState == (if (paid) LocalFoodPaymentApplicationState.APPLIED else LocalFoodPaymentApplicationState.PENDING_RECONCILIATION))
                check(original.summary.pendingReason == (if (paid) null else LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION))
                check(original.summary.lastApplicationOperationId == (if (paid) plan.applyOperationId else null))
                check(original.summary.appliedAction == (if (paid) LocalFoodPaymentAction.CASH_PAID else null))
            }
        }
        if (paid) requireReceipt(client, plan, originals, cut)
        val result = CafeProtectedCut(phase, cut, food, cash, collection)
        evidence.event(label, JSONObject().put("phase", phase.name).put("current", cut.data())
            .put("historicalQuoteMinor", detail.originalQuotedTotal.minor).put("currentUnpaidPayable", food.currentUnpaidPayable?.minor ?: JSONObject.NULL)
            .put("commercialOpen", !summary.workClosed).put("pendingPreparation", true).put("preparationTasks", station.tasks.size)
            .put("drawerAppliedLedgerMinor", session.expectedCash.minor).put("physicallyCountedDrawer", "UNBOUND")
            .put("positiveKitchenSendExecution", "UNBOUND_DIAGNOSTIC_UNKNOWN_ONLY")
            .put("preCloseFoodProducerSourceProof", "UNBOUND").put("wholeCase", "NOT_QUALIFIED"))
        check(requested.activity.activities == setOf(LocalSalesActivity.CAFE))
        return result
    }

    private suspend fun requireSources(client: NativeCafeCashClient, plan: NativeCafeCashPlan, seed: CafeCashSeed, cut: CafeReadCut) {
        val requested = seed.published.requested
        val catalogue = LocalConfigurationCodec.decodePage(cut.add("CURRENT_CATALOG_CURSOR_AND_MEMBERS", client.read(
            ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.SKU))))))
        check(catalogue.nextAfter == null && catalogue.catalogRevision == seed.quoteCursor && catalogue.detail == null &&
            catalogue.items.size == plan.roles.size && catalogue.items.map { it.id }.toSet() == plan.roles.map(plan::skuId).toSet())
        for (role in plan.roles) {
            val expected = requested.skus.getValue(role)
            val actual = LocalFoodConfigurationReadCodecV1.result(cut.add("FULL_SKU/$role", client.read(LocalFoodConfigurationReadCodecV1.read(
                LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.SKU, plan.skuId(role))))))
            check(actual.kind == LocalFoodConfigurationSourceKindV1.SKU && actual.nextAfter == null && actual.labels.size == 1 &&
                actual.labels.single().id == expected.skuId && actual.labels.single().revision == Revision(1) &&
                actual.labels.single().storageRevision.value > 0 && actual.labels.single().active &&
                (actual.detail as? LocalFoodConfigurationDetailV1.Sku)?.definition == expected)
            if (role == "latte") check(expected.variantValues == listOf(plan.small) &&
                expected.productDefinition?.variantAxes == listOf(plan.sizeAxis) &&
                expected.productDefinition?.allowedVariantCombinations == listOf(listOf(plan.small)))
        }
        suspend fun full(symbol: String, kind: LocalFoodConfigurationSourceKindV1, id: DomainId): LocalFoodConfigurationPageV1 =
            LocalFoodConfigurationReadCodecV1.result(cut.add(symbol, client.read(LocalFoodConfigurationReadCodecV1.read(LocalFoodConfigurationQueryV1(kind, id)))))
                .also { check(it.kind == kind && it.labels.size == 1 && it.labels.single().id == id && it.nextAfter == null && it.labels.single().active) }
        check((full("FULL_RECIPE", LocalFoodConfigurationSourceKindV1.RECIPE, plan.recipeId).detail as? LocalFoodConfigurationDetailV1.Recipe)
            ?.definition == requested.recipe)
        check((full("FULL_ROUTE", LocalFoodConfigurationSourceKindV1.ROUTE, plan.routeId).detail as? LocalFoodConfigurationDetailV1.Route)
            ?.definition == plan.route)
        check((full("FULL_STATION", LocalFoodConfigurationSourceKindV1.STATION, plan.stationId).detail as? LocalFoodConfigurationDetailV1.Station)
            ?.definition == plan.station)
        for ((kind, id) in listOf(LocalConfigurationProfile.WAREHOUSE to plan.warehouseId, LocalConfigurationProfile.BASIS to plan.openingApprovalId)) {
            val page = LocalConfigurationCodec.decodePage(cut.add("CONFIGURATION/$kind", client.read(ReadRequest(LocalConfigurationProfile.QUERY,
                LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id))))))
            check(page.nextAfter == null && page.items.size == 1)
            if (kind == LocalConfigurationProfile.WAREHOUSE) check((page.detail as? LocalConfigurationDetailV1.Warehouse)?.value == plan.warehouse)
            else check(page.items.single().revision == seed.openingBasisRevision &&
                (page.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == plan.openingApproval)
        }
        val activity = LocalBranchActivityCodecV1.decodeRead(cut.add("ORIGINAL_APPLIED_CAFE_ACTIVITY", client.read(LocalBranchActivityCodecV1.readCurrent())))
        val applied = checkNotNull(activity.applied)
        check(activity.status == LocalBranchActivityReadStatusV1.APPLIED && applied.definition == requested.activity &&
            applied.activities == setOf(LocalSalesActivity.CAFE) && applied.scope == client.original.material.coordinates.scope &&
            applied.actorId == client.original.material.coordinates.firstActorId && applied.operationId == plan.operation(110))
        val release = LocalCatalogReleaseCodecV1.decodeRead(cut.add("ACTIVE_SELECTED_RELEASE", client.read(
            LocalCatalogReleaseCodecV1.query(LocalCatalogReleaseQueryV1(plan.catalogId, plan.releasePublicationId)))))
        check(release.catalogId == plan.catalogId && release.activeStorageRevision != null &&
            release.active == seed.published.release && release.publication == seed.published.release &&
            release.active?.definition == requested.release)
    }
    private suspend fun requireReceipt(client: NativeCafeCashClient, plan: NativeCafeCashPlan, originals: List<CafeSavedOriginal>, cut: CafeReadCut) {
        val receipt = (LocalFoodFinancialReadCodecV1.result(cut.add("ORIGINAL_PAID_RECEIPT_DOCUMENT", client.read(
            LocalFoodFinancialReadCodecV1.read(LocalFoodFinancialQueryV1.Receipt(plan.saleId)))))
            as? LocalFoodFinancialReadResultV1.Receipt)?.value ?: error("Actual original paid receipt document is unavailable")
        val finalize = originals.single { it.symbol == "FINALIZE_ONE_PAID_SALE" }
        check(receipt.saleId == plan.saleId && receipt.finalizedAt == finalize.result.recordedAt && receipt.originalPayable == plan.payable &&
            receipt.lines.map { it.portionId } == plan.portionIds && receipt.lines.size == plan.paidSnapshot.getInt("sold_portion_count"))
        receipt.lines.forEach { line -> check(line.batchId == plan.batchId && line.originalQuantity == plan.quantity("latte", 1) &&
            line.remainingOriginalQuantity == line.originalQuantity && line.originalValue == plan.portionGross && line.remainingOriginalValue == line.originalValue) }
        check(receipt.components.size == 4 && receipt.components.map { it.originalKey }.distinct().size == 4 &&
            receipt.components.all { it.batchId == plan.batchId && it.portionId in plan.portionIds })
        for (portion in plan.portionIds) {
            val components = receipt.components.filter { it.portionId == portion }
            check(components.size == 2 && components.single { it.kind == "ITEM_NET" }.originalPosted == plan.portionNet &&
                components.single { it.kind == "ITEM_TAX" }.originalPosted == plan.portionTax)
        }
        check(receipt.components.filter { it.kind == "ITEM_NET" }.sumOf { it.originalPosted.minor } == plan.net.minor &&
            receipt.components.filter { it.kind == "ITEM_TAX" }.sumOf { it.originalPosted.minor } == plan.tax.minor)
        val tender = receipt.originalTenders.single()
        check(tender.originalPaymentId == plan.attemptId && tender.tender == LocalTenderViewV1("CASH") && tender.amount == plan.payable)
        check(receipt.remainingComponentTenders.size == 4 && receipt.remainingComponentTenders.map { it.componentKey }.distinct().size == 4 &&
            receipt.remainingComponentTenders.all { it.shares.size == 1 && it.shares.single().originalPaymentId == plan.attemptId } &&
            receipt.remainingComponentTenders.map { it.shares.single().amount.minor }.sorted() ==
                listOf(plan.portionTax.minor, plan.portionTax.minor, plan.portionNet.minor, plan.portionNet.minor).sorted())
        // This DTO is a paid receipt DOCUMENT; it has no physical-delivery, fiscal or pending-
        // preparation flag. Exact pending fulfillment is separately asserted from current FOOD.
    }
}

internal suspend fun requireCafeOpening(client: NativeCafeCashClient, plan: NativeCafeCashPlan, basisRevision: Revision,
    openingAt: UtcInstant, cut: CafeReadCut) {
    for (costs in listOf(false, true)) {
        val request = if (costs) LocalStockCommands.receiptCosts(plan.openingReceiptId) else LocalStockCommands.receipt(plan.openingReceiptId)
        val receipt = checkNotNull(LocalStockQueries.decodeReceipt(cut.add(if (costs) "ORIGINAL_OPENING_COSTS" else "ORIGINAL_OPENING_QUANTITIES", client.read(request))))
        check(receipt.receiptId == plan.openingReceiptId && receipt.basisId == plan.openingApprovalId && receipt.basisRevision == basisRevision &&
            receipt.basisKind == LocalStockBasisKind.OPENING && receipt.supplierId == null &&
            receipt.recordedBy == client.original.material.coordinates.firstActorId && receipt.receivedAt == openingAt &&
            receipt.reasonCode == plan.reasonCode && receipt.costsIncluded == costs && receipt.lines.size == plan.stocks.size)
        for (stock in plan.stocks) {
            val line = receipt.lines.single { it.skuId == stock.skuId }
            check(line.lineId == stock.receiptLineId && line.allocationId == stock.allocationId && line.sourceId == stock.sourceId &&
                line.warehouseId == plan.warehouseId && line.recordedQuantity == plan.quantity(stock.role, stock.onHand) &&
                line.baseQuantity == line.recordedQuantity && line.conversion == null && line.lotId == null && line.expiresAt == null &&
                line.disposition == LocalReceiptDisposition.RELEASED && line.originalValue == (if (costs) stock.carryingValue else null))
            check(line.releasedFromQuarantine == plan.quantity(stock.role, 0) && line.serialId == null && line.serialCode == null &&
                line.initialSerialRegistrationStatus == null)
        }
    }
}
internal suspend fun requireCafeStock(client: NativeCafeCashClient, plan: NativeCafeCashPlan, stock: CafeStockInput,
    reserved: Boolean, cut: CafeReadCut): LocalStockQuantityPage {
    val q = LocalStockQueries.decodeQuantities(cut.add("QUANTITY/${stock.role}", client.read(LocalStockCommands.quantities(stock.skuId))))
    val c = LocalStockQueries.decodeCosts(cut.add("COST/${stock.role}", client.read(LocalStockCommands.costs(stock.skuId))))
    val zero = plan.quantity(stock.role, 0)
    check(q.skuId == stock.skuId && q.itemStorageRevision != null && q.unitId == plan.unit(stock.role).id && q.quantityScale == 0 &&
        q.nextAfter == null && q.rows.size == 1)
    val row = q.rows.single()
    check(row.position.skuId == stock.skuId && row.position.warehouseId == plan.warehouseId && row.position.supplierId == null &&
        row.position.lotId == null && row.position.serialId == null && row.position.custody == StockCustody.RELEASED &&
        row.onHand == plan.quantity(stock.role, stock.onHand) && row.reserved == plan.quantity(stock.role, if (reserved) stock.reserved else 0) &&
        row.available == plan.quantity(stock.role, if (reserved) stock.available else stock.onHand) &&
        row.safety == StockSafety.CLEAR && row.expiresAt == null && !row.expiredAtRead && row.heldQuantity == zero &&
        row.heldShortageQuantity == zero && row.serialCode == null && row.serialRegistrationStatus == null)
    check(c.skuId == stock.skuId && c.itemStorageRevision == q.itemStorageRevision && c.nextAfter == null && c.rows.size == 1 &&
        c.rows.single().warehouseId == plan.warehouseId && c.rows.single().quantity == row.onHand && c.rows.single().carryingValue == stock.carryingValue)
    return q
}

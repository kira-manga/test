package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

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
import org.json.JSONObject

/** Public DATA only, not a source/disclosure authority. Record complete protected
 * observations at one current business cut; compare decoded business DATA, not transient
 * session/observed-at metadata or an invented byte golden. */
internal class FoodAlreadyAppliedProtectedCut {
    private var revision: Revision? = null
    private val payloads = linkedMapOf<String, StoredBytes>()
    private val business = linkedMapOf<String, Any>()
    val branchRevision: Revision get() = checkNotNull(revision)
    fun add(symbol: String, reply: ReadReply.Data): StoredBytes {
        check(payloads.put(symbol, reply.payload) == null)
        revision?.let { check(it == reply.branchRevision) { "Protected reads did not observe one unchanged native business cut" } }
        revision = reply.branchRevision
        return reply.payload
    }
    fun retain(symbol: String, value: Any) { check(business.put(symbol, value) == null) }
    fun payload(symbol: String): StoredBytes = payloads.getValue(symbol)
    fun requireSameNoEffects(after: FoodAlreadyAppliedProtectedCut) {
        check(business == after.business) { "AlreadyApplied/read/recovery changed the original money, stock, allocations or native original DATA" }
    }
    fun data() = JSONObject().put("branchRevision", branchRevision.value).put("protectedPayloadData", JSONObject().also { data ->
        payloads.forEach { (symbol, bytes) -> data.put(symbol, foodAlreadyAppliedBase64(bytes)) }
    }).put("encodingQualification", "FAA-Q04_UNBOUND")
}

internal object NativeFoodAlreadyAppliedProjections {
    suspend fun read(client: NativeFoodAlreadyAppliedClient, plan: NativeFoodAlreadyAppliedPlan,
        seed: FoodAlreadyAppliedSeed, paid: Boolean, label: String, evidence: NativeFoodAlreadyAppliedEvidence,
        outcome: LocalFoodCashOutcome.CashReceived? = null): FoodAlreadyAppliedProtectedCut {
        check(paid == (outcome != null))
        val cut = FoodAlreadyAppliedProtectedCut()
        seed.published.requireCurrent(checkNotNull(client.publications))
        val itemPage = LocalStockQueries.decodeItems(cut.add("STOCK_ITEMS_COMPLETE", client.read(LocalStockCommands.items())))
        check(itemPage.nextAfter == null && itemPage.items.size == plan.stocks.size &&
            itemPage.items.map { it.skuId }.toSet() == plan.stocks.map { it.skuId }.toSet())
        cut.retain("STOCK_ITEMS_COMPLETE", listOf(itemPage.nextAfter, itemPage.items))
        check(LocalStockRelationalQueriesV2.normalized(cut.add("GENUINE_RELATIONAL_STOCK_PROFILE", client.read(LocalStockRelationalQueriesV2.profile()))))
        for (stock in plan.stocks) {
            val quantities = LocalStockQueries.decodeQuantities(cut.add("QUANTITIES/${stock.role}", client.read(LocalStockCommands.quantities(stock.skuId))))
            val costs = LocalStockQueries.decodeCosts(cut.add("COSTS/${stock.role}", client.read(LocalStockCommands.costs(stock.skuId))))
            check(quantities.skuId == stock.skuId && quantities.itemStorageRevision != null && quantities.unitId == plan.unit(stock.role).id &&
                quantities.quantityScale == 0 && quantities.nextAfter == null && quantities.rows.size == 1)
            val row = quantities.rows.single()
            check(row.position.skuId == stock.skuId && row.position.warehouseId == plan.warehouseId && row.position.supplierId == null &&
                row.position.lotId == null && row.position.serialId == null && row.position.custody == StockCustody.RELEASED &&
                row.onHand == plan.quantity(stock.role, stock.onHand) && row.reserved == plan.quantity(stock.role, stock.reserved) &&
                row.available == plan.quantity(stock.role, stock.available) && row.safety == StockSafety.CLEAR && row.expiresAt == null &&
                !row.expiredAtRead && row.heldQuantity == plan.quantity(stock.role, 0) && row.heldShortageQuantity == plan.quantity(stock.role, 0))
            check(costs.skuId == stock.skuId && costs.itemStorageRevision == quantities.itemStorageRevision && costs.nextAfter == null &&
                costs.rows.size == 1 && costs.rows.single().warehouseId == plan.warehouseId && costs.rows.single().quantity == row.onHand &&
                costs.rows.single().carryingValue == plan.zero) // Explicit synthetic opening value; preservation, not a new FAA cost oracle.
            val history = LocalStockRelationalQueriesV2.decodeHistory(cut.add("STOCK_HISTORY/${stock.role}", client.read(
                LocalStockRelationalQueriesV2.history(stock.skuId, plan.warehouseId, afterOrdinal = 0, limit = 25))))
            check(history.skuId == stock.skuId && history.warehouseId == plan.warehouseId && history.nextOrdinal == null &&
                history.itemRevision == quantities.itemStorageRevision && history.rows.size == 1)
            val event = history.rows.single()
            check(event.sourceId == stock.sourceId && event.movement == "OPENING" && event.quantityDelta == plan.quantity(stock.role, stock.onHand) &&
                event.originalValueDelta == plan.zero && event.carryingValueDelta == plan.zero && event.resultingQuantity == row.onHand &&
                event.resultingValue == plan.zero && event.recordedAt == seed.stockOpeningRecordedAt) { "An extra stock issue/reversal/value event appeared" }
            cut.retain("QUANTITIES/${stock.role}", listOf(quantities.skuId, quantities.itemStorageRevision,
                quantities.unitId, quantities.quantityScale, quantities.nextAfter, quantities.rows))
            cut.retain("COSTS/${stock.role}", listOf(costs.skuId, costs.itemStorageRevision, costs.nextAfter, costs.rows))
            cut.retain("STOCK_HISTORY/${stock.role}", listOf(history.skuId, history.warehouseId,
                history.itemRevision, history.nextOrdinal, history.rows))
        }
        for (costs in listOf(false, true)) {
            val request = if (costs) LocalStockCommands.receiptCosts(plan.openingReceiptId) else LocalStockCommands.receipt(plan.openingReceiptId)
            val receipt = checkNotNull(LocalStockQueries.decodeReceipt(cut.add("OPENING_RECEIPT/$costs", client.read(request))))
            check(receipt.receiptId == plan.openingReceiptId && receipt.basisId == plan.openingApprovalId && receipt.basisRevision == seed.openingBasisRevision &&
                receipt.basisKind == LocalStockBasisKind.OPENING && receipt.supplierId == null && receipt.recordedBy == client.original.material.coordinates.firstActorId &&
                receipt.receivedAt == seed.stockOpeningRecordedAt && receipt.costsIncluded == costs && receipt.lines.size == plan.stocks.size)
            for (stock in plan.stocks) {
                val line = receipt.lines.single { it.skuId == stock.skuId }
                check(line.lineId == stock.receiptLineId && line.allocationId == stock.allocationId && line.sourceId == stock.sourceId &&
                    line.warehouseId == plan.warehouseId && line.recordedQuantity == plan.quantity(stock.role, stock.onHand) &&
                    line.baseQuantity == line.recordedQuantity && line.originalValue == (if (costs) plan.zero else null))
            }
            cut.retain("OPENING_RECEIPT/$costs", listOf(receipt.receiptId, receipt.storageRevision, receipt.basisId,
                receipt.basisRevision, receipt.basisKind, receipt.supplierId, receipt.recordedBy, receipt.receivedAt,
                receipt.reasonCode, receipt.costsIncluded, receipt.lines))
        }
        val food = LocalFoodCheckKitchenCodecV3.result(cut.add("ORIGINAL_FOOD_COMPLETE_TWO_PORTIONS", client.read(
            LocalFoodCheckKitchenCodecV3.read(LocalFoodQueryV1.Order(plan.orderId, limit = 2)))))
        check(food.food.unavailable == null && food.food.nextAfter == null && food.food.orders.isEmpty() && food.food.tasks.isEmpty())
        val detail = checkNotNull(food.food.detail)
        val summary = detail.summary
        val actorA = client.original.material.coordinates.firstActorId
        check(summary.orderId == plan.orderId && summary.ownerId == actorA && summary.activity == plan.activity && summary.service == plan.service &&
            summary.portionCount == 2 && !summary.workClosed && summary.financialCollectionId == plan.collectionId &&
            detail.portions.map { it.portionId } == plan.portionIds && detail.originalQuotedTotal == plan.payable && !detail.chargeResolutionRequired)
        detail.portions.forEach { portion ->
            val preparation = portion.preparation
            check(portion.quantity == plan.quantity("meal", 1) && portion.commercialLineId == plan.portionIds.first() &&
                preparation.portionId == portion.portionId && preparation.status == FoodPreparationStatus.NOT_SENT &&
                preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && preparation.tasks.isEmpty() &&
                preparation.issueId == null && preparation.issueRevision == null && preparation.sentAt == null &&
                preparation.cancellationReasonId == null && preparation.servedAt == null && preparation.pickedUpAt == null)
        }
        val kitchen = checkNotNull(food.kitchen)
        check(kitchen.orderId == plan.orderId && kitchen.orderStorageRevision == summary.revision && kitchen.unsentPortionCount == 2)
        cut.retain("ORIGINAL_FOOD_COMPLETE_TWO_PORTIONS", food)
        val station = LocalFoodReadCodecV1.result(cut.add("STATION_TASKS_COMPLETE", client.read(
            LocalFoodReadCodecV1.read(LocalFoodQueryV1.Station(plan.stationId)))))
        check(station.unavailable == null && station.nextAfter == null && station.detail == null && station.orders.isEmpty() && station.tasks.isEmpty())
        cut.retain("STATION_TASKS_COMPLETE", station)

        val financial = LocalFoodFinancialReadCodecV1.result(cut.add("ORIGINAL_COLLECTION", client.read(
            LocalFoodFinancialReadCodecV1.read(LocalFoodFinancialQueryV1.Collection(plan.collectionId))))) as? LocalFoodFinancialReadResultV1.Collection
            ?: error("The original protected collection is unavailable")
        val collection = financial.value
        check(collection.collectionId == plan.collectionId && collection.orderId == plan.orderId && collection.orderStorageRevision == summary.revision &&
            collection.originalPayable == plan.payable && collection.collected == (if (paid) plan.payable else plan.zero) &&
            collection.unpaidRemaining == (if (paid) plan.zero else plan.payable) && collection.saleId == null &&
            collection.cancellationRefundId == null && collection.refundLiabilityDue == plan.zero && collection.unresolvedClaimIds.isEmpty() &&
            !collection.workClosed && collection.portions.map { it.portionId } == plan.portionIds)
        collection.portions.forEach { portion ->
            check(portion.originalQuantity == plan.quantity("meal", 1) && portion.preparationStatus == FoodPreparationStatus.NOT_SENT &&
                portion.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && portion.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION &&
                portion.reservationStatus == "RESERVED" && !portion.chargeDecisionRequired)
        }
        if (paid) {
            check(collection.status == "COVERED" && collection.attempts.size == 1)
            val attempt = collection.attempts.single()
            check(attempt.attemptId == plan.attemptId && attempt.status == "ATTESTED_PAID" && attempt.tender == LocalTenderViewV1("CASH") &&
                attempt.amount == plan.payable && attempt.intentOperationId == plan.startOperationId &&
                attempt.appliedPaymentObservationId == plan.recordingOperationId)
        } else check(collection.status == "LOCKED_UNPAID" && collection.attempts.isEmpty())
        cut.retain("ORIGINAL_COLLECTION", financial)
        for (actor in FoodAlreadyAppliedActor.entries) {
            val cash = LocalCashReadCodec.decode(cut.add("OWN_CASH/${actor.name}", client.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash), actor)))
                as? LocalCashReadResultV1.OwnCash ?: error("A genuine independent drawer observation is unavailable")
            val session = checkNotNull(cash.session)
            val a = actor == FoodAlreadyAppliedActor.A
            val actorId = plan.actorId(actor, client.original.material.coordinates)
            val expected = if (a && paid) plan.expectedA else if (a) plan.openingA else plan.expectedB
            check(cash.custodianId == actorId && cash.unresolvedReturnIds.isEmpty() &&
                session.id == (if (a) plan.cashSessionA else plan.cashSessionB) && session.drawerId == (if (a) plan.drawerA else plan.drawerB) &&
                session.custodianId == actorId && session.status == "OPEN" && session.opening == (if (a) plan.openingA else plan.openingB) &&
                session.expectedCash == expected && session.businessDate == (if (a) seed.cashBusinessDateA else seed.cashBusinessDateB) &&
                session.received == (if (a && paid) plan.received else plan.zero) && session.change == (if (a && paid) plan.change else plan.zero) &&
                session.reservedCashOut == plan.zero && session.pendingAttemptIds.isEmpty())
            check(listOf(session.refunds, session.payIns, session.payOuts, session.safeDrops, session.externalCollections, session.externalRefunds)
                .all { it == plan.zero } && session.pendingTransferIds.isEmpty())
            val selected = LocalCashReadCodec.decode(cut.add("EXACT_CASH_SESSION/${actor.name}", client.read(
                LocalCashReadCodec.request(LocalCashQueryV1.Session(session.id)), actor))) as? LocalCashReadResultV1.Session
                ?: error("The exact genuine cash session is unavailable")
            check(selected.session == session)
            // W deliberately creates an unrelated empty FOOD draft. Its unsettled-work
            // index is not a money effect; retain complete actual cash custody/session.
            cut.retain("OWN_CASH/${actor.name}", listOf(cash.custodianId, cash.assignmentRevision, session))
            cut.retain("EXACT_CASH_SESSION/${actor.name}", selected)
        }
        if (paid) {
            val original = LocalFoodPaymentReadCodec.decode(cut.add("EXACT_ORIGINAL_NATIVE_CASH", client.read(
                LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Original(plan.recordingOperationId))))) as? LocalFoodPaymentReadResult.Original
                ?: error("The genuine original payment read is unavailable")
            client.requireOriginal(original, checkNotNull(outcome))
            check(original.summary.applicationState == LocalFoodPaymentApplicationState.APPLIED && original.summary.pendingReason == null &&
                original.summary.appliedAction == LocalFoodPaymentAction.CASH_PAID && original.summary.lastApplicationOperationId == plan.earlierApplyOperationId)
            val attempt = LocalFoodPaymentReadCodec.decode(cut.add("EXACT_ORIGINAL_ATTEMPT_LINKAGE", client.read(
                LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.AttemptRecording(plan.orderId, plan.collectionId,
                    plan.cashSessionA, plan.attemptId, plan.startOperationId))))) as? LocalFoodPaymentReadResult.AttemptRecording
                ?: error("The genuine original attempt linkage is unavailable")
            check(attempt.target == plan.target() && attempt.original == original.summary && attempt.amount == plan.payable &&
                attempt.masterAllocationId == plan.masterAllocationId && attempt.clientAllocationId == plan.clientAllocationId)
            val pending = LocalFoodPaymentReadCodec.decode(cut.add("OWN_PENDING_ORIGINALS_COMPLETE", client.read(
                LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.OwnPending())))) as? LocalFoodPaymentReadResult.OwnPending
                ?: error("The genuine pending-original frontier is unavailable")
            check(pending.originals.isEmpty() && pending.nextAfter == null)
            cut.retain("EXACT_ORIGINAL_NATIVE_CASH", original)
            cut.retain("EXACT_ORIGINAL_ATTEMPT_LINKAGE", attempt)
            cut.retain("OWN_PENDING_ORIGINALS_COMPLETE", pending)
        }
        evidence.event(label, cut.data().put("paid", paid).put("drawerAExpectedMinor", if (paid) plan.expectedA.minor else plan.openingA.minor)
            .put("drawerBExpectedMinor", plan.expectedB.minor).put("coverageMinor", if (paid) plan.payable.minor else 0)
            .put("remainingMinor", if (paid) 0 else plan.payable.minor).put("saleFinalizations", 0).put("ingredientIssues", 0).put("kitchenTasks", 0))
        return cut
    }
}

internal suspend fun foodAlreadyAppliedConfiguration(client: NativeFoodAlreadyAppliedClient, kind: String,
    id: DomainId? = null): LocalConfigurationPageV1 = LocalConfigurationCodec.decodePage(client.read(ReadRequest(LocalConfigurationProfile.QUERY,
        LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id)))).payload)
internal suspend fun foodAlreadyAppliedFood(client: NativeFoodAlreadyAppliedClient, plan: NativeFoodAlreadyAppliedPlan): LocalFoodCheckKitchenResultV3 =
    LocalFoodCheckKitchenCodecV3.result(client.read(LocalFoodCheckKitchenCodecV3.read(LocalFoodQueryV1.Order(plan.orderId, limit = 2))).payload)
internal suspend fun foodAlreadyAppliedOwnCash(client: NativeFoodAlreadyAppliedClient,
    actor: FoodAlreadyAppliedActor = FoodAlreadyAppliedActor.A): LocalCashReadResultV1.OwnCash =
    LocalCashReadCodec.decode(client.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash), actor).payload) as? LocalCashReadResultV1.OwnCash
        ?: error("The genuine current own drawer is unavailable")
internal suspend fun foodAlreadyAppliedCollection(client: NativeFoodAlreadyAppliedClient,
    plan: NativeFoodAlreadyAppliedPlan): LocalFoodCollectionViewV1 =
    (LocalFoodFinancialReadCodecV1.result(client.read(LocalFoodFinancialReadCodecV1.read(LocalFoodFinancialQueryV1.Collection(plan.collectionId))).payload)
        as? LocalFoodFinancialReadResultV1.Collection)?.value ?: error("The genuine original collection is unavailable")

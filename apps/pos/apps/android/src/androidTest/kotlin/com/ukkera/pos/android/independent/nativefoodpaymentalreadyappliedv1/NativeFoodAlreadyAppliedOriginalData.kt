@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeHistoryTableV2
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Closed native DATA comparisons and joins only. Complete current-authorized public
 * stock/cost/receipt/FOOD/cash/Original reads remain primary observations. This creates no
 * producer/source capability, canonical golden, current authority or history-admission port. */
internal object NativeFoodAlreadyAppliedOriginalData {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }
    // Exact selected setup/request inventory, not independent byte or setup qualification.
    private val originalSymbols = setOf("INSTALL_SKU_meal", "INSTALL_SKU_bread", "INSTALL_SKU_meat", "ACTIVATE_SYNTHETIC_FOOD_ACTIVITY",
        "CONFIGURE_WAREHOUSE", "APPROVE_SYNTHETIC_OPENING", "INITIALIZE_FRESH_STOCK", "OPEN_FROZEN_INGREDIENT_QUANTITIES",
        "CONFIGURE_STATION", "INSTALL_ORIGINAL_RECIPE", "CONFIGURE_ORIGINAL_ROUTE", "ACTIVATE_SELECTED_CATALOG_RELEASE",
        "CONFIGURE_CUSTODIAN_A", "CONFIGURE_DRAWER_A", "OPEN_FROZEN_FLOAT_A", "CONFIGURE_CUSTODIAN_B", "CONFIGURE_DRAWER_B",
        "OPEN_FROZEN_FLOAT_B", "CREATE_ORIGINAL_FOOD_DRAFT", "QUOTE_TWO_ORIGINAL_PORTIONS", "BEGIN_RESERVE_BOTH_UNSENT_PORTIONS",
        "I_START", "O_NATIVE_IMPORT", "A_EARLIER_APPLY", NativeFoodAlreadyAppliedStaging.REJECTION, "W_LATER_EMPTY_FOOD_DRAFT")

    fun require(cut: FoodAlreadyAppliedCensusRows, plan: NativeFoodAlreadyAppliedPlan, seed: FoodAlreadyAppliedSeed,
        protected: FoodAlreadyAppliedProtectedCut, originals: List<FoodAlreadyAppliedSavedOriginal>,
        evidence: NativeFoodAlreadyAppliedEvidence): JSONObject {
        check(cut.branch.integer("revision") == protected.branchRevision.value && originals.size == originalSymbols.size &&
            originals.map { it.symbol }.toSet() == originalSymbols)
        val i = originals.single { it.symbol == "I_START" }
        val o = originals.single { it.symbol == "O_NATIVE_IMPORT" }
        val a = originals.single { it.symbol == "A_EARLIER_APPLY" }
        val r = originals.single { it.symbol == NativeFoodAlreadyAppliedStaging.REJECTION }
        val w = originals.single { it.symbol == "W_LATER_EMPTY_FOOD_DRAFT" }
        val scope = i.scope
        val actor = i.identity.originalActorId
        check(i.request.operationId == plan.startOperationId && o.request.operationId == plan.recordingOperationId &&
            a.request.operationId == plan.earlierApplyOperationId && r.request.operationId == plan.rejectionOperationId &&
            w.request.operationId == plan.laterWriterOperationId && r.nativeStaged &&
            listOf(o, a, r, w).all { it.scope == scope && it.identity.originalActorId == actor && it.authSessionId == i.authSessionId &&
                it.deviceId == i.deviceId && it.policyRevision == plan.activePolicyRevision })
        check(i.result.sequence.value < o.result.sequence.value && o.result.sequence.value < a.result.sequence.value &&
            a.result.sequence.value < r.result.sequence.value && r.result.sequence.value < w.result.sequence.value) {
            "The actual durable I/O/earlier-A/ordinary-R/later-W order was not retained"
        }
        requireOrdinaryAlreadyApplied(r.result, plan)
        val apply = LocalFoodPaymentCodec.result(a.result.payload) as? LocalFoodPaymentResult.Applied
            ?: error("Earlier A is not a genuinely applied original CASH result")
        check(a.result.committed && apply.phase == LocalFoodPaymentFactPhase.ORIGINAL && apply.action == LocalFoodPaymentAction.CASH_PAID &&
            apply.target == plan.target() && apply.applicationOperationId == a.request.operationId)
        val outcome = (LocalFoodPaymentCodec.decode(o.request.payload) as? LocalFoodPaymentRequest.RecordOriginal)?.outcome
            as? LocalFoodCashOutcome.CashReceived ?: error("Retained O DATA are not genuine successful FOOD CASH")
        check(outcome.received == plan.received && outcome.change == plan.change && outcome.evidence.evidenceId == plan.outcomeEvidenceId)
        requirePayment(cut, plan, scope, actor, o, a, evidence)
        requireHistoricalQuote(cut, plan, seed, originals)
        requireStock(cut, plan, scope, actor, originals)

        val counts = mapOf(LocalFoodProfileV1.ORDER to 2, LocalFoodProfileV1.PORTION to plan.portionIds.size,
            LocalFoodProfileV1.PRICE_BATCH to 1, LocalFoodProfileV1.OWNER_INDEX to 1,
            LocalFoodFinancialProfileV1.COLLECTION to 1, LocalFoodPaymentProfile.SLOT to 1,
            LocalFoodPaymentProfile.ORIGINAL to 1, LocalFoodPaymentProfile.APPLICATION to 1,
            LocalPhysicalJournalFormatV1.SOURCE_KIND to 2, LocalSalesStoreProfile.MOVEMENT_KIND to 1,
            CashNonSaleStoreProfile.SESSION_KIND to 2, LocalSalesStoreProfile.DRAWER_KIND to 2,
            LocalSalesStoreProfile.CUSTODIAN_KIND to 2, LocalStockStoreProfile.RECEIPT_DOCUMENT to 1)
        counts.forEach { (kind, count) -> check(cut.aggregates(kind).size == count) { "Unexpected original native membership: $kind" } }
        for (kind in listOf(LocalFoodPaymentProfile.RESOLUTION, LocalFoodFinancialProfileV1.SALE, LocalFoodFinancialProfileV1.ENTITLEMENT,
            LocalFoodFinancialProfileV1.REFUND, LocalFoodFinancialProfileV1.REFUND_INDEX, LocalFoodFinancialProfileV1.CLAIM,
            LocalFoodFinancialProfileV1.CLAIM_INDEX, LocalFoodFinancialProfileV1.PHYSICAL_SLOT, LocalFoodFinancialProfileV1.PHYSICAL_FACT,
            LocalFoodFinancialProfileV1.PHYSICAL_APPLICATION, LocalSalesStoreProfile.SALE_KIND, LocalSalesStoreProfile.RETURN_KIND,
            CashNonSaleStoreProfile.MOVEMENT_KIND)) check(cut.aggregates(kind).isEmpty())
        val collection = (LocalFoodFinancialReadCodecV1.result(protected.payload("ORIGINAL_COLLECTION"))
            as? LocalFoodFinancialReadResultV1.Collection)?.value ?: error("The actual protected original collection is unavailable")
        check(cut.aggregates(LocalFoodFinancialProfileV1.COLLECTION).single().let {
            it.text("id") == plan.collectionId.value && it.integer("revision") == collection.collectionStorageRevision.value
        } && collection.collected == plan.payable && collection.unpaidRemaining == plan.zero && collection.saleId == null)
        check(cut.aggregates(LocalFoodProfileV1.ORDER).map { it.text("id") }.toSet() == setOf(plan.orderId.value, plan.laterOrderId.value))
        val later = JSONObject(cut.aggregates(LocalFoodProfileV1.ORDER).single { it.text("id") == plan.laterOrderId.value }.textBytes("payload"))
        check(later.getString("operationId") == plan.laterWriterOperationId.value && later.getJSONObject("body").getJSONArray("portionIds").length() == 0)
        val stations = cut.aggregates(LocalFoodProfileV1.STATION_INDEX)
        check(stations.size <= 1 && stations.all { it.text("id") == plan.stationId.value &&
            JSONObject(it.textBytes("payload")).getJSONObject("body").getJSONArray("tasks").length() == 0 })
        return JSONObject().put("slotRows", 1).put("actualPhysicalSourceRows", 2).put("durableOriginalORows", 1)
            .put("originalCashAccountingRows", 1).put("netCashMovementRows", 1).put("actualMovementId", plan.attemptId.value)
            .put("movementUuidQualification", "LITERAL_FROZEN_MOVEMENT_LABEL_UNBOUND_OBSERVED_NATIVE_MAPPING_NOT_NEW_ORACLE")
            .put("receivedMinor", plan.received.minor).put("changeMinor", plan.change.minor).put("netCashMinor", plan.payable.minor)
            .put("drawerAExpectedMinor", plan.expectedA.minor).put("drawerBExpectedMinor", plan.expectedB.minor)
            .put("coverageMinor", plan.payable.minor).put("remainingMinor", 0).put("ordinaryAlreadyAppliedRows", 1)
            .put("ordinaryRSequence", r.result.sequence.value).put("laterUnrelatedWSequence", w.result.sequence.value)
            .put("reservedPortions", plan.portionIds.size).put("ingredientIssues", 0).put("kitchenTasks", 0)
            .put("saleFinalizations", 0).put("refundMinor", 0).put("nativeInstructionCount", "NOT_INFERRED_FROM_SOURCE_ROWS")
            .put("requestInventoryData", JSONArray(originals.map { it.symbol })).put("wholeCases", "NOT_QUALIFIED")
    }

    private fun requirePayment(cut: FoodAlreadyAppliedCensusRows, plan: NativeFoodAlreadyAppliedPlan, scope: BranchScope,
        actor: DomainId, original: FoodAlreadyAppliedSavedOriginal, applied: FoodAlreadyAppliedSavedOriginal,
        evidence: NativeFoodAlreadyAppliedEvidence) {
        val requirement = foodAlreadyAppliedStored(evidence.binding("ACTUAL_NATIVE_REQUIREMENT_DATA") as String)
        val projection = LocalFoodPaymentPhysicalProjection.parse(requirement)
        check(projection.target == plan.target() && projection.amount == plan.payable && projection.requirement.scope == scope &&
            projection.requirement.originalActorId == actor)
        val sources = cut.aggregates(LocalPhysicalJournalFormatV1.SOURCE_KIND).map { row ->
            check(row.integer("revision") == 1L && row.text("schema") == LocalPhysicalJournalFormatV1.SOURCE_SCHEMA)
            LocalPhysicalJournalSourceProjection.decode(AggregateChange(AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND,
                DomainId(row.text("id"))), null, LocalPhysicalJournalFormatV1.SOURCE_SCHEMA, StoredBytes(row.bytes("payload")))).also {
                check(it.requirement.canonicalBytes == requirement && it.sourceCommitment == StoredBytes(row.bytes("payload")) &&
                    it.owner.scope == scope && it.owner.installationId == original.installationId &&
                    it.owner.deviceId == (if (it.owner.role == LocalPhysicalSpoolRole.MASTER) projection.requirement.masterDeviceId
                        else projection.requirement.clientDeviceId))
            }
        }
        check(sources.size == 2 && sources.map { it.owner.role }.toSet() == LocalPhysicalSpoolRole.entries.toSet() &&
            sources.map { it.allocationId }.toSet() == setOf(plan.masterAllocationId, plan.clientAllocationId) &&
            sources.map { it.nativeIdentity.directoryIdentity to it.nativeIdentity.fileIdentity }.distinct().size == 2)
        for (kind in listOf(LocalFoodPaymentProfile.SLOT, LocalFoodPaymentProfile.ORIGINAL, LocalFoodPaymentProfile.APPLICATION)) {
            val row = cut.aggregates(kind).single()
            check(row.text("id") == plan.recordingOperationId.value && row.text("schema") == LocalFoodPaymentProfile.schema(kind))
        }
        val slotRow = cut.aggregates(LocalFoodPaymentProfile.SLOT).single()
        val slot = JSONObject(slotRow.textBytes("payload"))
        check(slotRow.integer("revision") == 1L && dto(slot.getJSONObject("scope"), BranchScope.serializer()) == scope &&
            slot.getString("originalActorId") == actor.value && slot.getString("masterAllocationId") == plan.masterAllocationId.value &&
            slot.getString("clientAllocationId") == plan.clientAllocationId.value)
        for ((name, role) in listOf("masterSource" to LocalPhysicalSpoolRole.MASTER, "clientSource" to LocalPhysicalSpoolRole.CLIENT)) {
            val source = sources.single { it.owner.role == role }
            val linked = slot.getJSONObject(name)
            check(linked.getString("allocationId") == source.allocationId.value && linked.getString("payloadDigest") == foodAlreadyAppliedDigest(source.sourceCommitment).value &&
                linked.getString("headerDigest") == source.headerDigest.value && linked.getString("allocationDigest") == source.allocationDigest.value &&
                linked.getInt("slotIndex") == source.slotIndex)
        }
        val rawRow = cut.aggregates(LocalFoodPaymentProfile.ORIGINAL).single()
        val raw = JSONObject(rawRow.textBytes("payload"))
        check(rawRow.integer("revision") == 1L && dto(raw.getJSONObject("scope"), BranchScope.serializer()) == scope &&
            dto(raw.getJSONObject("target"), LocalFoodPaymentTarget.serializer()) == plan.target() && raw.getString("originalActorId") == actor.value &&
            raw.getString("recordingActorId") == actor.value && raw.getString("bodyDigest") == foodAlreadyAppliedDigest(original.request.payload).value &&
            raw.getString("semanticDigest") == original.identity.semanticSha256 && raw.getString("slotDigest") == slotRow.text("payloadHash") &&
            raw.getString("recordedAt") == original.result.recordedAt.canonicalString())
        val normalized = raw.getString("normalizedBody").encodeToByteArray(throwOnInvalidSequence = true)
        try { check(StoredBytes(normalized) == original.request.payload) } finally { normalized.fill(0) }
        val application = JSONObject(cut.aggregates(LocalFoodPaymentProfile.APPLICATION).single().textBytes("payload"))
        check(dto(application.getJSONObject("scope"), BranchScope.serializer()) == scope &&
            dto(application.getJSONObject("target"), LocalFoodPaymentTarget.serializer()) == plan.target() &&
            application.getString("originalActorId") == actor.value && application.getString("state") == "APPLIED" &&
            application.getString("action") == "CASH_PAID" && application.isNull("reason") &&
            application.getString("lastApplicationOperationId") == plan.earlierApplyOperationId.value &&
            application.getString("updatedAt") == applied.result.recordedAt.canonicalString())
        // ORIGINAL phase may be serializer-omitted; real native import and protected Original
        // reads establish it. An absent field is not an accepting default or reconstructed fact.
        val movementRow = cut.aggregates(LocalSalesStoreProfile.MOVEMENT_KIND).single()
        val movement = JSONObject(movementRow.textBytes("payload"))
        val outcome = (LocalFoodPaymentCodec.decode(original.request.payload) as LocalFoodPaymentRequest.RecordOriginal).outcome as LocalFoodCashOutcome.CashReceived
        check(movementRow.text("id") == plan.attemptId.value && movementRow.integer("revision") == 1L &&
            movement.getString("sourceId") == plan.attemptId.value && movement.getString("operationId") == plan.earlierApplyOperationId.value &&
            movement.getString("originalActorId") == actor.value && movement.getString("sessionId") == plan.cashSessionA.value &&
            movement.getString("kind") == "CUSTOMER_RECEIPT" && dto(movement.getJSONObject("delta"), Money.serializer()) == plan.payable &&
            dto(movement.getJSONObject("cashReceived"), Money.serializer()) == plan.received && dto(movement.getJSONObject("changeGiven"), Money.serializer()) == plan.change &&
            dto(movement.getJSONObject("evidence"), LocalCashEvidenceV1.serializer()) == outcome.evidence &&
            movement.getString("recordedAt") == applied.result.recordedAt.canonicalString() && movement.isNull("reason"))
    }

    private fun requireHistoricalQuote(cut: FoodAlreadyAppliedCensusRows, plan: NativeFoodAlreadyAppliedPlan,
        seed: FoodAlreadyAppliedSeed, originals: List<FoodAlreadyAppliedSavedOriginal>) {
        val add = originals.single { it.symbol == "QUOTE_TWO_ORIGINAL_PORTIONS" }
        val row = cut.aggregates(LocalFoodProfileV1.PRICE_BATCH).single()
        val envelope = JSONObject(row.textBytes("payload"))
        check(row.text("id") == plan.quoteOperationId.value && row.integer("revision") == 1L && envelope.getString("operationId") == add.request.operationId.value)
        val quote = StoredPricingSnapshotCodec.decode(envelope.getJSONObject("body").getString("quote"))
        val price = seed.published.requested.skus.getValue("meal").prices.single()
        check(quote.snapshotId.value == plan.quoteOperationId.value && quote.currency == Currency.EGP && quote.payable == plan.payable &&
            quote.beforeDiscountPayable == plan.payable && quote.advertisedDiscount == plan.zero && quote.charges.isEmpty() && quote.discounts.isEmpty() &&
            quote.lines.map { it.portionId } == plan.portionIds && quote.lines.all { it.lineId == plan.portionIds.first() &&
                it.quantity == plan.quantity("meal", 1) && it.priceRevision == Revision(1) && it.unitPrice == price.unitPrice &&
                it.tax == price.tax && it.inclusion == price.inclusion && it.payable == plan.portionGross } &&
            quote.components.size == 4 && quote.components.all { it.portionId in plan.portionIds && it.chargeId == null &&
                it.kind in setOf(PriceComponentKind.ITEM_NET, PriceComponentKind.ITEM_TAX) && it.posted == it.postedBeforeDiscounts } &&
            quote.components.filter { it.kind == PriceComponentKind.ITEM_NET }.sumOf { it.posted.minor } == plan.payable.minor &&
            quote.components.filter { it.kind == PriceComponentKind.ITEM_TAX }.all { it.posted == plan.zero })
        plan.portionIds.forEach { portion -> val components = quote.components.filter { it.portionId == portion }
            check(components.size == 2 && components.single { it.kind == PriceComponentKind.ITEM_NET }.posted == plan.portionGross &&
                components.single { it.kind == PriceComponentKind.ITEM_TAX }.posted == plan.zero) }
    }

    private fun requireStock(cut: FoodAlreadyAppliedCensusRows, plan: NativeFoodAlreadyAppliedPlan, scope: BranchScope,
        actor: DomainId, originals: List<FoodAlreadyAppliedSavedOriginal>) {
        val rows = cut.table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") in StockRelationalKindsV2.all }
        rows.forEach { row ->
            val record = JSONObject(row.textBytes("payload"))
            check(record.getInt("format") == 2 && dto(record.getJSONObject("scope"), BranchScope.serializer()) == scope &&
                record.getString("kind") == row.text("kind") && record.getString("id") == row.text("id") && record.getLong("generation") == row.integer("revision"))
            val producer = record.getJSONObject("producer")
            val original = originals.single { it.request.operationId.value == producer.getString("operationId") }
            check(producer.getString("command") == original.request.command.name && producer.getInt("version") == original.request.command.version &&
                producer.getString("actorId") == actor.value && producer.getString("digest") == original.identity.semanticSha256)
        }
        val indexedKinds = mapOf(NativeHistoryTableV2.STOCK_POSITION to setOf(StockRelationalKindsV2.POSITION),
            NativeHistoryTableV2.STOCK_ALLOCATION to setOf(StockRelationalKindsV2.ALLOCATION, StockRelationalKindsV2.HOLD),
            NativeHistoryTableV2.STOCK_RESERVATION to setOf(StockRelationalKindsV2.RESERVATION),
            NativeHistoryTableV2.STOCK_VALUE to setOf(StockRelationalKindsV2.ISSUE_SLICE, StockRelationalKindsV2.VALUE_EVENT,
                StockRelationalKindsV2.VALUE_SLICE, StockRelationalKindsV2.VALUE_TARGET, StockRelationalKindsV2.TRANSFER_EDGE))
        check(indexedKinds.values.flatten().toSet() == StockRelationalKindsV2.indexed)
        indexedKinds.forEach { (table, kinds) ->
            val indexes = cut.table(table)
            val aggregates = rows.filter { it.text("kind") in kinds }
            val keys = indexes.map { it.text("recordKind") to it.text("recordId") }
            check(keys.size == aggregates.size && keys.toSet().size == keys.size && keys.toSet() == aggregates.map { it.text("kind") to it.text("id") }.toSet())
            indexes.forEach { index -> val row = aggregates.single { it.text("kind") == index.text("recordKind") && it.text("id") == index.text("recordId") }
                check(index.integer("aggregateGeneration") == row.integer("revision") && index.text("payloadHash") == row.text("payloadHash") &&
                    index.text("producerOperationId") == JSONObject(row.textBytes("payload")).getJSONObject("producer").getString("operationId")) }
        }
        for ((kind, count) in mapOf(StockRelationalKindsV2.ITEM to 2, StockRelationalKindsV2.POSITION to 2, StockRelationalKindsV2.POOL to 2,
            StockRelationalKindsV2.RESERVATION to 2, StockRelationalKindsV2.ALLOCATION to 4, StockRelationalKindsV2.VALUE_EVENT to 2))
            check(cut.aggregates(kind).size == count)
        check(cut.aggregates(StockRelationalKindsV2.VALUE_SLICE).size == plan.stocks.size)
        for (kind in listOf(StockRelationalKindsV2.ISSUE, StockRelationalKindsV2.ISSUE_SLICE, StockRelationalKindsV2.HOLD,
            StockRelationalKindsV2.VALUE_TARGET, StockRelationalKindsV2.TRANSFER_EDGE)) check(cut.aggregates(kind).isEmpty())
        val reservations = cut.aggregates(StockRelationalKindsV2.RESERVATION).map(::stockBody)
        val reservationIds = reservations.map { it.getJSONObject("value").getString("id") }.toSet()
        check(reservationIds.size == plan.portionIds.size)
        val allocations = cut.aggregates(StockRelationalKindsV2.ALLOCATION).map(::stockBody)
        check(allocations.all { it.getString("reservationId") in reservationIds })
        reservations.forEach { body -> val value = body.getJSONObject("value"); val binding = value.getJSONObject("binding")
            check(value.getInt("status") == 1 && value.isNull("expiryMillis") && value.isNull("issueId") && value.getLong("holdCount") == 0L &&
                binding.getString("orderId") == plan.orderId.value && binding.getString("collectionId") == plan.collectionId.value && binding.getString("actorId") == actor.value)
            val selected = allocations.filter { it.getString("reservationId") == value.getString("id") }
            check(value.getLong("allocationCount") == selected.size.toLong() && selected.size == plan.stocks.size)
            plan.stocks.forEach { stock -> val allocation = selected.single { it.getJSONObject("value").getJSONObject("position").getString("skuId") == stock.skuId.value }
                check(dto(allocation.getJSONObject("value").getJSONObject("quantity"), Quantity.serializer()) == plan.quantity(stock.role, stock.perPortion)) }
        }
        for (stock in plan.stocks) {
            val position = cut.aggregates(StockRelationalKindsV2.POSITION).map(::stockBody).single {
                it.getJSONObject("value").getJSONObject("key").getString("skuId") == stock.skuId.value }.getJSONObject("value")
            check(position.getJSONObject("key").getString("warehouseId") == plan.warehouseId.value && position.isNull("expiryMillis") &&
                dto(position.getJSONObject("onHand"), Quantity.serializer()) == plan.quantity(stock.role, stock.onHand))
            val pool = cut.aggregates(StockRelationalKindsV2.POOL).map(::stockBody).single {
                it.getJSONObject("value").getJSONObject("key").getString("skuId") == stock.skuId.value }.getJSONObject("value")
            check(dto(pool.getJSONObject("onHand"), Quantity.serializer()) == plan.quantity(stock.role, stock.onHand) &&
                dto(pool.getJSONObject("value"), Money.serializer()) == plan.zero) // Synthetic opening carrying DATA only.
            val events = cut.aggregates(StockRelationalKindsV2.VALUE_EVENT).map(::stockBody).filter { it.getJSONObject("pool").getString("skuId") == stock.skuId.value }
            val event = events.single()
            check(event.getInt("event") == 7 && event.getString("sourceId") == stock.sourceId.value && event.isNull("issueId") &&
                dto(event.getJSONObject("quantityDelta"), Quantity.serializer()) == plan.quantity(stock.role, stock.onHand) &&
                dto(event.getJSONObject("originalValueDelta"), Money.serializer()) == plan.zero &&
                dto(event.getJSONObject("carryingValueDelta"), Money.serializer()) == plan.zero)
            val slice = cut.aggregates(StockRelationalKindsV2.VALUE_SLICE).map(::stockBody).single { it.getString("sourceId") == stock.sourceId.value }
            check(slice.getJSONObject("pool").getString("skuId") == stock.skuId.value && slice.isNull("issueId") &&
                slice.getString("allocationId") == stock.receiptLineId.value &&
                dto(slice.getJSONObject("quantity"), Quantity.serializer()) == plan.quantity(stock.role, stock.onHand) &&
                dto(slice.getJSONObject("originalValue"), Money.serializer()) == plan.zero)
        }
    }
    private fun stockBody(row: FoodAlreadyAppliedCensusRow): JSONObject = JSONObject(JSONObject(row.textBytes("payload")).getString("body"))
    private fun <T> dto(value: JSONObject, serializer: KSerializer<T>): T = json.decodeFromString(serializer, value.toString())
}

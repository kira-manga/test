@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.android.independent.nativefoodcashcoldv1

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

/** Literal closed DATA joins only. Public protected reads remain the primary quantity,
 * carrying-cost, receipt and current FOOD oracle. No internal producer/source capability,
 * retained-command authority or accepting history decoder is fabricated here. */
internal object NativeCafeCashOriginalData {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }
    private val originalSymbols = setOf("INSTALL_SKU_latte", "INSTALL_SKU_beans", "INSTALL_SKU_milk", "ACTIVATE_CAFE_ONLY",
        "CONFIGURE_WAREHOUSE", "APPROVE_EXACT_OPENING", "INITIALIZE_FRESH_STOCK", "OPENING_STOCK_WITH_FROZEN_VALUE",
        "CONFIGURE_STATION", "INSTALL_FROZEN_RECIPE", "CONFIGURE_CAFE_TAKEAWAY_ROUTE", "ACTIVATE_SELECTED_CATALOG_RELEASE",
        "CONFIGURE_ORIGINAL_CUSTODIAN", "CONFIGURE_FROZEN_DRAWER", "OPEN_FROZEN_FLOAT", "CREATE_CAFE_TAKEAWAY_DRAFT",
        "QUOTE_ORIGINAL_TWO_SMALL_LATTES", "BEGIN_COLLECTION_RESERVE_ALL_UNSENT", "I_START", "O_NATIVE_IMPORT", "A_APPLY", "FINALIZE_ONE_PAID_SALE")

    fun require(cut: CafeCensusRows, plan: NativeCafeCashPlan, seed: CafeCashSeed, protected: CafeProtectedCut,
        originals: List<CafeSavedOriginal>, evidence: NativeCafeCashEvidence): JSONObject {
        check(protected.phase == CafeCutPhase.PAID_UNSENT && cut.branch.integer("revision") == protected.reads.branchRevision.value &&
            originals.size == originalSymbols.size && originals.map { it.symbol }.toSet() == originalSymbols)
        val scope = originals.first().scope
        val actor = originals.first().identity.originalActorId
        val i = originals.single { it.symbol == "I_START" }
        val o = originals.single { it.symbol == "O_NATIVE_IMPORT" }
        val a = originals.single { it.symbol == "A_APPLY" }
        val finalized = originals.single { it.symbol == "FINALIZE_ONE_PAID_SALE" }
        check(i.result.sequence.value < o.result.sequence.value && o.result.sequence.value < a.result.sequence.value &&
            a.result.sequence.value < finalized.result.sequence.value) // Actual durable order, never wall-clock sorting.
        check(i.result.recordedAt.canonicalString() == evidence.binding("I_ORIGINAL_NATIVE_RECORDED_AT") &&
            o.result.recordedAt.canonicalString() == evidence.binding("O_ORIGINAL_NATIVE_IMPORT_RECORDED_AT") &&
            a.result.recordedAt.canonicalString() == evidence.binding("A_ORIGINAL_NATIVE_COMMITTED_AT"))
        val outcome = (LocalFoodPaymentCodec.decode(o.request.payload) as? LocalFoodPaymentRequest.RecordOriginal)?.outcome
            as? LocalFoodCashOutcome.CashReceived ?: error("Retained O comparison DATA are not the original successful FOOD CASH body")
        check(outcome.received == plan.received && outcome.change == plan.change && outcome.evidence.evidenceId == plan.outcomeEvidenceId &&
            outcome.evidence.occurredAt.canonicalString() == evidence.binding("O_ORIGINAL_NATIVE_ATTESTATION_AT"))
        requirePayment(cut, plan, scope, actor, o, a, evidence)
        requireHistoricalQuote(cut, plan, seed, originals)
        requireStock(cut, plan, scope, actor, originals)

        val exactCounts = mapOf(LocalFoodProfileV1.ORDER to 1, LocalFoodProfileV1.PORTION to plan.portionIds.size,
            LocalFoodProfileV1.PRICE_BATCH to 1, LocalFoodProfileV1.OWNER_INDEX to 1,
            LocalFoodFinancialProfileV1.COLLECTION to 1, LocalFoodFinancialProfileV1.SALE to 1,
            LocalFoodFinancialProfileV1.ENTITLEMENT to 1, LocalFoodPaymentProfile.SLOT to 1,
            LocalFoodPaymentProfile.ORIGINAL to 1, LocalFoodPaymentProfile.APPLICATION to 1,
            LocalPhysicalJournalFormatV1.SOURCE_KIND to 2, LocalSalesStoreProfile.MOVEMENT_KIND to 1,
            CashNonSaleStoreProfile.SESSION_KIND to 1, LocalSalesStoreProfile.DRAWER_KIND to 1,
            LocalSalesStoreProfile.CUSTODIAN_KIND to 1, LocalStockStoreProfile.RECEIPT_DOCUMENT to 1)
        exactCounts.forEach { (kind, count) -> check(cut.aggregates(kind).size == count) { "Unexpected original native membership: $kind" } }
        for (kind in listOf(LocalFoodPaymentProfile.RESOLUTION, LocalFoodFinancialProfileV1.REFUND,
            LocalFoodFinancialProfileV1.REFUND_INDEX, LocalFoodFinancialProfileV1.CLAIM, LocalFoodFinancialProfileV1.CLAIM_INDEX,
            LocalFoodFinancialProfileV1.PHYSICAL_SLOT, LocalFoodFinancialProfileV1.PHYSICAL_FACT,
            LocalFoodFinancialProfileV1.PHYSICAL_APPLICATION, LocalSalesStoreProfile.SALE_KIND,
            LocalSalesStoreProfile.RETURN_KIND, CashNonSaleStoreProfile.MOVEMENT_KIND)) check(cut.aggregates(kind).isEmpty())
        val collection = checkNotNull(protected.collection)
        check(cut.aggregates(LocalFoodFinancialProfileV1.COLLECTION).single().let {
            it.text("id") == plan.collectionId.value && it.integer("revision") == collection.collectionStorageRevision.value })
        val receipt = (LocalFoodFinancialReadCodecV1.result(protected.reads.payloads.getValue("ORIGINAL_PAID_RECEIPT_DOCUMENT"))
            as? LocalFoodFinancialReadResultV1.Receipt)?.value ?: error("The separately protected original receipt cut is unavailable")
        check(cut.aggregates(LocalFoodFinancialProfileV1.SALE).single().let { it.text("id") == plan.saleId.value &&
            it.integer("revision") == receipt.saleStorageRevision.value } &&
            cut.aggregates(LocalFoodFinancialProfileV1.ENTITLEMENT).single().let { it.text("id") == plan.saleId.value &&
                it.integer("revision") == receipt.entitlementStorageRevision.value } && receipt.finalizedAt == finalized.result.recordedAt)
        val stations = cut.aggregates(LocalFoodProfileV1.STATION_INDEX)
        check(stations.size <= 1 && stations.all {
            it.text("id") == plan.stationId.value && JSONObject(it.textBytes("payload")).getJSONObject("body").getJSONArray("tasks").length() == 0
        }) // Native absence/empty membership, plus complete protected Station read; no task is inferred from a callback count.
        // Every actual original outbox job is compared with its retained journal, then
        // fingerprinted unchanged across recovery. Setup delivery jobs, if any, are not
        // confused with a physical instruction or an independently counted kitchen task.
        return JSONObject().put("slotRows", 1).put("nativeSourceRows", 2).put("durableOriginalORows", 1)
            .put("appliedARows", 1).put("netCashEffectRows", 1).put("netCashMinor", plan.payable.minor)
            .put("paidSales", 1).put("receiptDocuments", 1).put("soldPortions", receipt.lines.size)
            .put("postedNetMinor", plan.net.minor).put("postedTaxMinor", plan.tax.minor)
            .put("reservedPortions", plan.portionIds.size).put("ingredientIssues", 0).put("kitchenTasks", 0)
            .put("reservationExpiresAt", JSONObject.NULL).put("nativeInstructionCount", "UNBOUND")
            .put("originals", JSONArray(originals.map { it.symbol }))
    }

    private fun requirePayment(cut: CafeCensusRows, plan: NativeCafeCashPlan, scope: BranchScope, actor: DomainId,
        original: CafeSavedOriginal, applied: CafeSavedOriginal, evidence: NativeCafeCashEvidence) {
        val requirement = cafeStored(evidence.binding("ORIGINAL_NATIVE_REQUIREMENT_BYTES") as String)
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
        check(slotRow.integer("revision") == 1L)
        val slot = JSONObject(slotRow.textBytes("payload"))
        check(dto(slot.getJSONObject("scope"), BranchScope.serializer()) == scope && slot.getString("originalActorId") == actor.value &&
            slot.getString("masterAllocationId") == plan.masterAllocationId.value && slot.getString("clientAllocationId") == plan.clientAllocationId.value)
        for ((name, role) in listOf("masterSource" to LocalPhysicalSpoolRole.MASTER, "clientSource" to LocalPhysicalSpoolRole.CLIENT)) {
            val source = sources.single { it.owner.role == role }
            val linked = slot.getJSONObject(name)
            check(linked.getString("allocationId") == source.allocationId.value && linked.getString("payloadDigest") == cafeDigest(source.sourceCommitment).value &&
                linked.getString("headerDigest") == source.headerDigest.value && linked.getString("allocationDigest") == source.allocationDigest.value &&
                linked.getInt("slotIndex") == source.slotIndex)
        }
        val rawRow = cut.aggregates(LocalFoodPaymentProfile.ORIGINAL).single()
        val raw = JSONObject(rawRow.textBytes("payload"))
        check(rawRow.integer("revision") == 1L && dto(raw.getJSONObject("scope"), BranchScope.serializer()) == scope &&
            dto(raw.getJSONObject("target"), LocalFoodPaymentTarget.serializer()) == plan.target() && raw.getString("originalActorId") == actor.value &&
            raw.getString("recordingActorId") == actor.value && raw.getString("bodyDigest") == cafeDigest(original.request.payload).value &&
            raw.getString("semanticDigest") == original.identity.semanticSha256 && raw.getString("slotDigest") == slotRow.text("payloadHash") &&
            raw.getString("recordedAt") == original.result.recordedAt.canonicalString())
        val normalized = raw.getString("normalizedBody").encodeToByteArray(throwOnInvalidSequence = true)
        try { check(StoredBytes(normalized) == original.request.payload) } finally { normalized.fill(0) }
        val applicationRow = cut.aggregates(LocalFoodPaymentProfile.APPLICATION).single()
        val application = JSONObject(applicationRow.textBytes("payload"))
        check(applicationRow.integer("revision") == 2L && dto(application.getJSONObject("scope"), BranchScope.serializer()) == scope &&
            dto(application.getJSONObject("target"), LocalFoodPaymentTarget.serializer()) == plan.target() &&
            application.getString("originalActorId") == actor.value && application.getString("state") == "APPLIED" &&
            application.getString("action") == "CASH_PAID" && application.isNull("reason") &&
            application.getString("lastApplicationOperationId") == plan.applyOperationId.value &&
            application.getString("updatedAt") == applied.result.recordedAt.canonicalString())
        // ORIGINAL may be serializer-omitted. Protected actual Original reads and native
        // import provenance establish its phase; an absent field is not an accepting default.
        val movementRow = cut.aggregates(LocalSalesStoreProfile.MOVEMENT_KIND).single()
        val movement = JSONObject(movementRow.textBytes("payload"))
        val outcome = (LocalFoodPaymentCodec.decode(original.request.payload) as LocalFoodPaymentRequest.RecordOriginal).outcome
            as LocalFoodCashOutcome.CashReceived
        check(movementRow.text("id") == plan.attemptId.value && movementRow.integer("revision") == 1L &&
            movement.getString("sourceId") == plan.attemptId.value && movement.getString("operationId") == plan.applyOperationId.value &&
            movement.getString("originalActorId") == actor.value && movement.getString("sessionId") == plan.cashSessionId.value &&
            movement.getString("kind") == "CUSTOMER_RECEIPT" && dto(movement.getJSONObject("delta"), Money.serializer()) == plan.payable &&
            dto(movement.getJSONObject("cashReceived"), Money.serializer()) == plan.received &&
            dto(movement.getJSONObject("changeGiven"), Money.serializer()) == plan.change &&
            dto(movement.getJSONObject("evidence"), LocalCashEvidenceV1.serializer()) == outcome.evidence &&
            movement.getString("recordedAt") == applied.result.recordedAt.canonicalString() && movement.isNull("reason"))
    }

    private fun requireHistoricalQuote(cut: CafeCensusRows, plan: NativeCafeCashPlan, seed: CafeCashSeed, originals: List<CafeSavedOriginal>) {
        val add = originals.single { it.symbol == "QUOTE_ORIGINAL_TWO_SMALL_LATTES" }
        val row = cut.aggregates(LocalFoodProfileV1.PRICE_BATCH).single()
        val envelope = JSONObject(row.textBytes("payload"))
        check(row.text("id") == plan.batchId.value && row.integer("revision") == 1L && envelope.getString("operationId") == add.request.operationId.value)
        val quote = StoredPricingSnapshotCodec.decode(envelope.getJSONObject("body").getString("quote"))
        val price = seed.published.requested.skus.getValue("latte").prices.single()
        check(quote.snapshotId == plan.batchId && quote.currency == Currency.EGP && quote.payable == plan.payable &&
            quote.beforeDiscountPayable == plan.payable && quote.advertisedDiscount == plan.zero && quote.charges.isEmpty() && quote.discounts.isEmpty() &&
            quote.lines.map { it.portionId } == plan.portionIds && quote.lines.all { it.lineId == plan.portionIds.first() &&
                it.quantity == plan.quantity("latte", 1) && it.priceRevision == Revision(1) && it.unitPrice == price.unitPrice &&
                it.tax == price.tax && it.inclusion == price.inclusion && it.payable == plan.portionGross } &&
            quote.components.size == 4 && quote.components.all { it.portionId in plan.portionIds && it.chargeId == null &&
                it.kind in setOf(PriceComponentKind.ITEM_NET, PriceComponentKind.ITEM_TAX) && it.posted == it.postedBeforeDiscounts } &&
            quote.components.filter { it.kind == PriceComponentKind.ITEM_NET }.sumOf { it.posted.minor } == plan.net.minor &&
            quote.components.filter { it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.posted.minor } == plan.tax.minor)
        plan.portionIds.forEach { portion -> val components = quote.components.filter { it.portionId == portion }
            check(components.size == 2 && components.single { it.kind == PriceComponentKind.ITEM_NET }.posted == plan.portionNet &&
                components.single { it.kind == PriceComponentKind.ITEM_TAX }.posted == plan.portionTax) }
    }

    private fun requireStock(cut: CafeCensusRows, plan: NativeCafeCashPlan, scope: BranchScope, actor: DomainId, originals: List<CafeSavedOriginal>) {
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
        for ((kind, count) in mapOf(StockRelationalKindsV2.ITEM to 2, StockRelationalKindsV2.POSITION to 2,
            StockRelationalKindsV2.POOL to 2, StockRelationalKindsV2.RESERVATION to 2, StockRelationalKindsV2.ALLOCATION to 4,
            StockRelationalKindsV2.VALUE_EVENT to 2)) check(cut.aggregates(kind).size == count)
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
            val positions = cut.aggregates(StockRelationalKindsV2.POSITION).map(::stockBody).filter { it.getJSONObject("value").getJSONObject("key").getString("skuId") == stock.skuId.value }
            val position = positions.single().getJSONObject("value")
            check(position.getJSONObject("key").getString("warehouseId") == plan.warehouseId.value && position.isNull("expiryMillis") &&
                dto(position.getJSONObject("onHand"), Quantity.serializer()) == plan.quantity(stock.role, stock.onHand))
            val pool = cut.aggregates(StockRelationalKindsV2.POOL).map(::stockBody).single { it.getJSONObject("value").getJSONObject("key").getString("skuId") == stock.skuId.value }.getJSONObject("value")
            check(dto(pool.getJSONObject("onHand"), Quantity.serializer()) == plan.quantity(stock.role, stock.onHand) && dto(pool.getJSONObject("value"), Money.serializer()) == stock.carryingValue)
            val events = cut.aggregates(StockRelationalKindsV2.VALUE_EVENT).map(::stockBody).filter { it.getJSONObject("pool").getString("skuId") == stock.skuId.value }
            val event = events.single()
            check(event.getInt("event") == 7 && event.getString("sourceId") == stock.sourceId.value && event.isNull("issueId") &&
                dto(event.getJSONObject("quantityDelta"), Quantity.serializer()) == plan.quantity(stock.role, stock.onHand) &&
                dto(event.getJSONObject("originalValueDelta"), Money.serializer()) == stock.carryingValue &&
                dto(event.getJSONObject("carryingValueDelta"), Money.serializer()) == stock.carryingValue)
        }
    }
    private fun stockBody(row: CafeCensusRow): JSONObject = JSONObject(JSONObject(row.textBytes("payload")).getString("body"))
    private fun <T> dto(value: JSONObject, serializer: KSerializer<T>): T = json.decodeFromString(serializer, value.toString())
}

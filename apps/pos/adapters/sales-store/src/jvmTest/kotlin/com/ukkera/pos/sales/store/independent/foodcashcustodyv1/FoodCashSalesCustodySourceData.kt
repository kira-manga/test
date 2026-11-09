package com.ukkera.pos.sales.store.independent.foodcashcustodyv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/**
 * FOOD/cash decoder DATA for the already frozen FCC-R-02 instruction-held and
 * FCC-C-01 original unsent collection states. This does not execute Start.
 * The committed stock DATA and its untouched frozen resource are only inputs.
 *
 * Frozen scope, employee, order, portion, batch, SKU, recipe, drawer and session
 * UUIDs retain their DATA meanings. Food reservation/issue/ingredient/snapshot
 * IDs use the actual lookup helpers, not captured native IDs. Ingredient SKU
 * UUIDs also serve as component-ID syntax aliases, never original components.
 * TAKEAWAY is the stored channel syntax for the frozen TAKEAWAY_PREPAID Café;
 * this fixture has no Send, pickup, payment, activity or kitchen authority.
 *
 * Additional IDs use fccb5100-0000-4000-8000-<hex slot>, base 300 Restaurant or
 * 400 Café: +0 collection, +1 recipe publication, +2 routing version, +3 station,
 * +4 route key, +5 tax policy, +6 choice, +7 envelope operation, +8 opening
 * evidence, +9 opening source, +10 serving unit, +11 price publication,
 * +20+i price rule, +99 mismatched component. Revisions 0/1 and tags, descriptions,
 * label hashes, DISPLAY routing, source references/authorizedBy and the nonempty
 * signedPublication placeholder are decoder syntax only, not signed acceptance.
 * All unframed financial and FOOD envelopes are the real codecs' accepted DATA
 * profiles; no native financial bundle or protected original is fabricated.
 *
 * Restaurant preparation revision 4 / row 5 and issue business revision 2 are
 * DTO syntax for one READY station per SERVED portion, not native history.
 * Only the frozen reference send/start/ready/served and cash-opening timestamps
 * fill required DTO fields. The frozen reference business date fills cash DATA.
 * These are not capture-once native bindings under timestamp supplement v1.0.1;
 * no I/O/A timestamp, authoritative ordering, clock or owner action is tested.
 *
 * Historical price components are copied from frozen minor amounts, encoded and
 * validated by StoredPricingSnapshotCodec; no current pricing calculation runs.
 * FoodFinancialSnapshots and SalesSnapshotCodec construct their actual domain
 * values. Every fixture input is decoded before any expected-refusal assertion.
 */
internal class FoodCashSalesCustodySourceData(mode: String) {
    private val stockData = FoodCashCustodyData(mode)
    val frozen = stockData.frozen
    private val root = checkNotNull(javaClass.getResourceAsStream(
        "/independent/foodcashcustodyv1/food-cash-cold-recovery-v1-fixtures.json")).use {
        Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }
    private val base = if (frozen.issued) 300 else 400
    private val profile = frozen.state.getValue("original_price_profile").jsonObject
    private val expected = frozen.state.getValue("snapshots").jsonObject.getValue("instructed_before_physical").jsonObject
    private val case = if (frozen.issued) "FCC-R-02 instruction-held DATA" else "FCC-C-01 original unsent collection DATA"
    val historicalQuote = money(expected.number("historical_quote_minor"))
    val zero = money(expected.number("net_applied_cash_minor"))
    val drawerLedger = money(expected.number("drawer_ledger_minor"))
    private val timeline = frozen.state.getValue("timeline_utc").jsonObject
    private val collectionId = syntax(0)
    private val batchId = id("${mode}_batch")
    private val operationId = OperationId(syntax(7).value)
    private val stationId = syntax(3)
    private val activity = LocalSalesActivity.valueOf(frozen.state.text("activity"))
    private val channel = if (frozen.issued) LocalSalesChannel.DINE_IN else LocalSalesChannel.TAKEAWAY
    private val serving = Quantity(syntax(10), frozen.state.getValue("recipe").jsonObject.number("yield_portions"), 0)
    private val warehouse = stockData.portions.first().allocations.first().position.pool.warehouseId
    private val priceEntries: List<JsonObject> = if (frozen.issued) profile.getValue("portions").jsonArray.map { it.jsonObject }
        else frozen.portionIds.map { portion -> buildJsonObject {
            put("id", portion.value); put("sku_id", id("latte_small_sku").value)
            put("quantity", serving.scaled.toString()); put("tax_inclusive", profile.getValue("tax_inclusive"))
            put("configured_unit_price", profile.text("unit_price"))
            put("posted_net_minor", profile.text("per_portion_net_minor"))
            put("posted_tax_minor", profile.text("per_portion_tax_minor"))
            put("posted_gross_minor", profile.text("per_portion_gross_minor"))
        } }
    private val recipes = priceEntries.map { entry -> recipe(entry) }
    private val portionRows = priceEntries.mapIndexed { index, entry -> portion(index, entry) }
    val original: FoodCashSalesCustodySources

    init {
        assertEquals(frozen.portionIds.map { it.value }, priceEntries.map { it.text("id") }, "$case: frozen input coverage")
        assertEquals(0L, zero.minor, "$case: frozen pre-application zero, never applied coverage")
        val quote = historicalPriceData()
        val sources = priceEntries.map { entry ->
            val portion = DomainId(entry.text("id")); val price = UnitPrice.parse(frozen.currency, entry.text("configured_unit_price"))
            LocalSalesPriceSourceV2(frozen.scope, portion, portion, DomainId(entry.text("sku_id")), syntax(11),
                syntax(20 + frozen.portionIds.indexOf(portion)), Revision(profile.number("price_revision")),
                dataDigest("price/${portion.value}"), price, price, null, null, null, null, null, null)
        }
        val batch = FoodEnvelopeV1(frozen.scope, LocalFoodProfileV1.PRICE_BATCH, batchId, Revision(1), operationId,
            FoodBodyV1.PriceBatch(frozen.orderId, quote, sources))
        val financialPortions = portionRows.map { row ->
            val portion = row.body as FoodBodyV1.Portion
            FoodFinancialPortion(row.id, batchId, portion.recipe.snapshotId, portion.selection.quantity,
                foodReservationId(row.id), if (frozen.issued) FoodStockOrigin.ISSUED_BEFORE_COLLECTION else FoodStockOrigin.RESERVED_FOR_COLLECTION,
                if (frozen.issued) foodIssueId(row.id) else null)
        }
        val collectionText = buildJsonObject {
            put("profile", "GENUINE_LOCAL_FOOD_FINANCIAL_V1"); put("kind", "COLLECTION"); put("scope", scopeJson())
            put("id", collectionId.value); put("revision", "0")
            put("state", buildJsonObject {
                put("orderId", frozen.orderId.value); put("lockedCommercialRevision", "1")
                put("originalCreatorId", frozen.actorId.value); put("collectorId", frozen.actorId.value)
                put("cashSessionId", id("${mode}_cash_session").value)
                put("batches", JsonArray(listOf(buildJsonObject {
                    put("id", batchId.value); put("digest", sha256(foodEnvelopeBytes(batch).copyBytes()).value); put("quote", quote)
                })))
                put("portions", JsonArray(financialPortions.map { localSalesJson.encodeToJsonElement(FoodFinancialPortion.serializer(), it) }))
                put("attempts", JsonArray(emptyList())); put("saleId", JsonNull); put("cancellationRefundId", JsonNull)
            })
        }.toString()
        val collection = FoodFinancialSnapshots.decodeCollection(collectionText, frozen.scope, collectionId, Revision(0))
        assertEquals(collectionText, FoodFinancialSnapshots.encodeCollection(collection), "$case: actual collection decoder round-trip")
        val financial = FoodFinancialEnvelopeV1(frozen.scope, LocalFoodFinancialProfileV1.COLLECTION, collectionId,
            Revision(1), operationId, FoodFinancialBodyV1.Collection(collectionText))
        val order = FoodEnvelopeV1(frozen.scope, LocalFoodProfileV1.ORDER, frozen.orderId, Revision(1), operationId,
            FoodBodyV1.Order(frozen.actorId, frozen.actorId, activity, LocalFoodServiceV1(channel,
                frozen.state.text(if (frozen.issued) "table_note" else "pickup_reference")), frozen.portionIds,
                listOf(batchId), historicalQuote, financialCollectionId = collectionId))
        val owner = FoodEnvelopeV1(frozen.scope, LocalFoodProfileV1.OWNER_INDEX, frozen.actorId, Revision(1), operationId,
            FoodBodyV1.OwnerIndex(listOf(frozen.orderId)))
        val financialBytes = localSalesEncode(financial, FoodFinancialEnvelopeV1.serializer())
        val collectionInput = FoodFinancialInputV1(financial.kind, financial.id, financial.revision,
            LocalFoodFinancialProfileV1.schema(financial.kind), sha256(financialBytes.copyBytes()), operationId,
            financialBytes.copyBytes().decodeToString())
        foodPaymentFinancialEnvelope(collectionInput, frozen.scope)
        val inputs = listOf(collectionInput, foodInput(order), foodInput(owner)) + portionRows.map(::foodInput) + foodInput(batch)
        val cashInput = cashData(mode)
        val cash = SalesSnapshotCodec.decodeCashSession(cashInput.snapshot,
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, frozen.scope, cashInput.sessionId, Revision(0)))
        assertEquals(cashInput.snapshot, SalesSnapshotCodec.encodeCashSession(cash), "$case: actual cash decoder round-trip")
        val localScope = LocalSalesScope.from(frozen.scope)
        val drawer = LocalDrawerState(localScope, cash.drawerId, Revision(0), "FOOD custody DATA", frozen.currency, cash.id)
        val custodian = LocalCustodianState(localScope, frozen.actorId, Revision(0), cash.id)
        val work = LocalWorkState(localScope, frozen.actorId, Revision(0))
        val rows = inputs.map { current(it.key(), it.schema, it.bytes(), it.revision) } +
            current(CashNonSaleStoreProfile.sessionKey(cash.id), CashNonSaleStoreProfile.SESSION_SCHEMA, StoredBytes(cashInput.snapshot.encodeToByteArray())) +
            current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.DRAWER_KIND, drawer.id), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.DRAWER_KIND),
                localSalesEncode(drawer, LocalDrawerState.serializer())) +
            current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.CUSTODIAN_KIND),
                localSalesEncode(custodian, LocalCustodianState.serializer())) +
            current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, work.id), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.WORK_KIND),
                localSalesEncode(work, LocalWorkState.serializer()))
        val claims = inputs.map { StreamedSourceObservationV2.Claim(foodSourceClaim(it.key()), it.id) } + listOf(
            StreamedSourceObservationV2.Claim(configuredClaim(LocalSalesStoreProfile.DRAWER_KIND, drawer.id), drawer.id),
            StreamedSourceObservationV2.Claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id), custodian.id),
            StreamedSourceObservationV2.Claim(LocalSalesStoreProfile.activeDrawer(drawer.id), cash.id),
            StreamedSourceObservationV2.Claim(LocalSalesStoreProfile.activeCustodian(custodian.id), cash.id),
            StreamedSourceObservationV2.Claim(LocalSalesStoreProfile.unique("cash_session", cash.id), cash.id),
            StreamedSourceObservationV2.Claim(LocalSalesStoreProfile.unique("opening_source", cash.openingSourceId), cash.id),
        )
        val stock = foodCashSalesStockData(stockData, recipes, collectionId, stationId)
        original = FoodCashSalesCustodySources(inputs, cashInput, StockReadObservations(frozen.scope, rows + claims + stock.sources))
    }

    fun missingSecondPortion(): FoodCashSalesCustodySources {
        val key = AggregateKey(LocalFoodProfileV1.PORTION, frozen.portionIds[1])
        return FoodCashSalesCustodySources(original.inputs, original.cashInput, StockReadObservations(frozen.scope,
            original.observed.sources.filterNot { it is StreamedSourceObservationV2.Current && it.key == key }))
    }

    /** Only one recipe component relationship differs. All stock, prices, quantities and IDs
     * remain literal originals; the actual FOOD decoder validates this candidate before use. */
    fun mismatchedSecondPortionIngredient(): FoodCashSalesCustodySources {
        val row = portionRows[1]; val body = row.body as FoodBodyV1.Portion
        val changed = foodInput(row.copy(body = body.copy(recipe = body.recipe.copy(ingredients =
            body.recipe.ingredients.mapIndexed { index, ingredient -> if (index == 0) ingredient.copy(componentId = syntax(99)) else ingredient }))))
        val inputs = original.inputs.map { if (it.key() == changed.key()) changed else it }
        val observed = StockReadObservations(frozen.scope, original.observed.sources.map { source ->
            if (source is StreamedSourceObservationV2.Current && source.key == changed.key())
                current(changed.key(), changed.schema, changed.bytes(), changed.revision) else source
        })
        return FoodCashSalesCustodySources(inputs, original.cashInput, observed)
    }

    private fun recipe(entry: JsonObject): LocalFoodRecipeSnapshotV1 {
        val portion = DomainId(entry.text("id"))
        return LocalFoodRecipeSnapshotV1(LocalFoodProfileV1.recipeSnapshotId(portion, syntax(1), syntax(2)), portion, syntax(2),
            activity, channel, frozen.recipeAlias, Revision(frozen.state.getValue("recipe").jsonObject.number("version")), syntax(1),
            DomainId(entry.text("sku_id")), Revision(1), syntax(6), emptyList(), "FOOD custody DATA", serving,
            Quantity(serving.unitId, entry.number("quantity"), 0), DomainId(entry.text("sku_id")),
            frozen.ingredients.map { LocalFoodIngredientV1(it.skuId, it.skuId, Revision(1), it.perPortion, it.perPortion) },
            warehouse, listOf(LocalFoodStationRouteV1(syntax(4), stationId, Revision(1), FoodStationDeliveryMode.DISPLAY)),
            "NON_NATIVE_DATA: no authenticated recipe publication",
            listOf(LocalFoodSourceReferenceV1("comparison.food-cash-sales-custody-data", syntax(1), Revision(1), dataDigest("recipe"), frozen.actorId)))
    }

    private fun portion(index: Int, entry: JsonObject): FoodEnvelopeV1 {
        val recipe = recipes[index]; val id = recipe.portionId
        val preparation = if (frozen.issued) FoodPreparationState(id, Revision(4), FoodPreparationStatus.READY,
            FoodFulfillmentStatus.SERVED, listOf(FoodStationTask(foodTaskId(id, stationId), stationId, FoodStationDeliveryMode.DISPLAY,
                FoodStationStatus.READY, at("r${index + 1}_start"), at("r${index + 1}_ready"))), foodReservationId(id), foodIssueId(id),
            Revision(2), at("send"), servedAt = at("r${index + 1}_served"))
            else FoodPreparationState(id, Revision(0), FoodPreparationStatus.NOT_SENT)
        val first = stockData.portions[index].producer
        val reference = if (frozen.issued) FoodStockIssueReferenceV2(foodIssueId(id), foodReservationId(id), first.operationId,
            frozen.actorId, first.semanticDigest, frozen.orderId, Revision(1), foodStockContextId(frozen.orderId), frozen.actorId,
            CollectionExposure.UNCOLLECTED.name, frozen.ingredients.size.toLong(),
            frozen.ingredients.fold(zeroForConstruction()) { sum, ingredient -> sum + ingredient.portionValue }, recipe.snapshotId, listOf(stationId)) else null
        return FoodEnvelopeV1(frozen.scope, LocalFoodProfileV1.PORTION, id, preparation.revision.next(), operationId,
            FoodBodyV1.Portion(frozen.orderId, LocalFoodPortionSelectionV1(id, DomainId(entry.text("sku_id")), frozen.recipeAlias,
                recipe.recipeRevision, recipe.choiceId, emptyList(), recipe.portionQuantity), batchId, recipe, preparation, normalizedIssue = reference))
    }

    private fun historicalPriceData(): String {
        val rate = profile.getValue("tax_rate_ratio").jsonObject
        check(rate.number("denominator") == 100L)
        val tax = localSalesJson.encodeToJsonElement(TaxSnapshot.serializer(),
            TaxSnapshot(syntax(5), Revision(1), "FOOD-DATA", PercentageRate.parse(rate.text("numerator"))))
        val limits = localSalesJson.encodeToJsonElement(DiscountLimits.serializer(), DiscountLimits.none(frozen.currency))
        val lines = priceEntries.map { entry -> buildJsonObject {
            put("lineId", entry.text("id")); put("portionId", entry.text("id")); put("priceRevision", profile.text("price_revision"))
            put("quantity", localSalesJson.encodeToJsonElement(Quantity.serializer(), Quantity(serving.unitId, entry.number("quantity"), 0)))
            put("unitPrice", localSalesJson.encodeToJsonElement(UnitPrice.serializer(), UnitPrice.parse(frozen.currency, entry.text("configured_unit_price"))))
            put("inclusion", if (entry.getValue("tax_inclusive").jsonPrimitive.boolean) "INCLUSIVE" else "EXCLUSIVE")
            put("tax", tax); put("discountLimits", limits); put("exactOriginalItemNet", exact(entry.number("posted_net_minor")))
            put("exactItemNet", exact(entry.number("posted_net_minor"))); put("payable", moneyJson(entry.number("posted_gross_minor")))
        } }
        val components = priceEntries.flatMap { entry -> listOf("net", "tax").map { kind -> buildJsonObject {
            val amount = entry.number("posted_${kind}_minor")
            put("key", "p/${entry.text("id")}/item/$kind"); put("lineId", entry.text("id")); put("portionId", entry.text("id"))
            put("chargeId", JsonNull); put("kind", if (kind == "net") "ITEM_NET" else "ITEM_TAX"); put("tax", tax)
            put("refundTreatment", "PROPORTIONAL_WITH_ITEMS"); put("exactBeforeDiscounts", exact(amount)); put("exact", exact(amount))
            put("postedBeforeDiscounts", moneyJson(amount)); put("posted", moneyJson(amount))
        } } }
        val text = buildJsonObject {
            put("schemaVersion", StoredPricingSnapshotCodec.SCHEMA_VERSION); put("snapshotId", batchId.value); put("revision", "0")
            put("currency", frozen.currency.name); put("profile", PricingProfiles.LINEAR_PROTECTED_V2)
            put("taxPostingPolicy", "OPERATIONAL_COMPONENT_ALLOCATION"); put("promotionResolution", "NONE"); put("discountLimits", limits)
            put("beforeDiscountPayable", moneyJson(historicalQuote.minor)); put("payable", moneyJson(historicalQuote.minor))
            put("exactBeforeDiscountPayable", exact(historicalQuote.minor)); put("exactPayable", exact(historicalQuote.minor))
            put("lines", JsonArray(lines.sortedBy { it.text("portionId") })); put("charges", JsonArray(emptyList()))
            put("components", JsonArray(components.sortedBy { it.text("key") })); put("discounts", JsonArray(emptyList()))
        }.toString()
        assertEquals(text, StoredPricingSnapshotCodec.encode(StoredPricingSnapshotCodec.decode(text)), "$case: frozen historical price DATA decoder round-trip")
        return text
    }

    private fun cashData(mode: String): FoodFinancialCashInputV1 {
        val session = id("${mode}_cash_session")
        val text = buildJsonObject {
            put("schemaVersion", SalesSnapshotCodec.SCHEMA_VERSION); put("kind", "CASH_SESSION"); put("scope", scopeJson())
            put("id", session.value); put("revision", "0")
            put("state", buildJsonObject {
                put("drawerId", id("${mode}_drawer").value); put("custodianId", frozen.actorId.value)
                put("businessDate", root.getValue("fixture_clock").jsonObject.text("business_date"))
                put("opening", buildJsonObject {
                    put("amount", moneyJson(drawerLedger.minor)); put("evidence", buildJsonObject {
                        put("evidenceId", syntax(8).value); put("actorId", frozen.actorId.value); put("occurredAt", at("cash_open_observed").canonicalString())
                    })
                })
                put("openingSourceId", syntax(9).value); put("totals", buildJsonObject {
                    for (kind in listOf("received", "change", "refunds", "payIns", "payOuts", "safeDrops", "externalCollections", "externalRefunds"))
                        put(kind, moneyJson(zero.minor))
                })
                put("expectedCash", moneyJson(drawerLedger.minor)); put("reservedCashOut", moneyJson(zero.minor)); put("cashLedgerRevision", "0")
                put("status", "OPEN"); put("pendingAttemptIds", JsonArray(emptyList())); put("pendingTransferIds", JsonArray(emptyList()))
                put("closingCut", JsonNull); put("closeReport", JsonNull); put("review", JsonNull)
            })
        }.toString()
        return FoodFinancialCashInputV1(session, Revision(1), text)
    }

    private fun foodInput(value: FoodEnvelopeV1): FoodFinancialInputV1 {
        val bytes = foodEnvelopeBytes(value)
        val input = FoodFinancialInputV1(value.kind, value.id, value.revision, LocalFoodProfileV1.schema(value.kind),
            sha256(bytes.copyBytes()), value.operationId, bytes.copyBytes().decodeToString())
        foodPaymentFoodEnvelope(input, frozen.scope)
        return input
    }

    private fun current(key: AggregateKey, schema: String, bytes: StoredBytes, generation: Revision = Revision(1)) =
        StreamedSourceObservationV2.Current(key, generation, schema, bytes, sha256(bytes.copyBytes()))
    private fun scopeJson() = buildJsonObject {
        put("organizationId", frozen.scope.organizationId.value); put("companyId", frozen.scope.companyId.value); put("branchId", frozen.scope.branchId.value)
    }
    private fun id(name: String) = DomainId(root.getValue("synthetic_ids").jsonObject.text(name))
    private fun syntax(slot: Int) = DomainId("fccb5100-0000-4000-8000-${(base + slot).toString(16).padStart(12, '0')}")
    private fun at(role: String) = UtcInstant.parseCanonical(timeline.text(role))
    private fun dataDigest(role: String) = sha256("food-cash-sales-custody-DATA/${frozen.mode}/$role".encodeToByteArray())
    private fun zeroForConstruction() = Money(frozen.currency, 0)
    private fun money(minor: Long) = Money(frozen.currency, minor)
    private fun moneyJson(minor: Long) = localSalesJson.encodeToJsonElement(Money.serializer(), money(minor))
    private fun exact(minor: Long) = ExactRational.ratio(minor, 100).canonicalString()
}

internal class FoodCashSalesCustodySources(inputs: List<FoodFinancialInputV1>, val cashInput: FoodFinancialCashInputV1,
    val observed: StockReadObservations) {
    val inputs = inputs.toList()
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String) = text(key).toLong()

package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeHistoryTableV2
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import org.json.JSONObject

/** Literal original DATA assertions using public DTOs/codecs only. No internal FOOD
 * envelope, source/producer capability or accepting streamed-history decoder is rebuilt. */
internal object NativeFoodColdOriginalData {
    private const val RECIPE_QUOTATION = "sales.food.recipe-quotation"
    private const val QUOTED_PORTION_SCHEMA = "sales.food.portion/quotation-v1"
    private val foodKinds = setOf(LocalFoodProfileV1.ORDER, LocalFoodProfileV1.PORTION, LocalFoodProfileV1.PRICE_BATCH,
        LocalFoodProfileV1.OWNER_INDEX, LocalFoodProfileV1.STATION_INDEX, RECIPE_QUOTATION)
    private val allowedKinds = foodKinds + LocalPricingProfile.historyKinds + setOf(
        LocalConfigurationProfile.SKU, LocalConfigurationProfile.PUBLICATION, LocalConfigurationProfile.WAREHOUSE,
        LocalConfigurationProfile.BASIS, LocalConfigurationProfile.CATALOG_CURSOR,
        LocalFoodConfigurationProfileV1.RECIPE, LocalFoodConfigurationProfileV1.PUBLICATION,
        LocalFoodConfigurationProfileV1.STATION, LocalFoodConfigurationProfileV1.ROUTE, LocalFoodConfigurationProfileV1.VERSION,
        LocalFoodFloorProfileV1.FLOOR, LocalFoodFloorProfileV1.TABLE, LocalFoodFloorProfileV1.VERSION,
        LocalBranchActivityProfileV1.ACTIVE, LocalBranchActivityProfileV1.PUBLICATION,
        LocalCatalogReleaseProfileV1.ACTIVE, LocalCatalogReleaseProfileV1.PUBLICATION,
        LocalFoodTableAssignmentProfileV1.BINDING, LocalFoodTableAssignmentProfileV1.VERSION,
        StockRelationalKindsV2.ITEM, StockRelationalKindsV2.POSITION, StockRelationalKindsV2.POOL,
        StockRelationalKindsV2.RESERVATION, StockRelationalKindsV2.ALLOCATION, StockRelationalKindsV2.ISSUE,
        StockRelationalKindsV2.ISSUE_SLICE, StockRelationalKindsV2.VALUE_EVENT, StockRelationalKindsV2.VALUE_SLICE,
        StockRelationalKindsV2.POSTING, StockRelationalKindsV2.ACTIVATION,
        LocalStockStoreProfile.RECEIPT_DOCUMENT, LocalStockStoreProfile.ALLOWANCE)
    private val allowedClaims = setOf(LocalConfigurationProfile.SOURCE_CLAIM, LocalPricingProfile.VERSION_CLAIM,
        LocalFoodConfigurationProfileV1.CLAIM, LocalBranchActivityProfileV1.BRANCH_CLAIM,
        LocalBranchActivityProfileV1.PUBLICATION_CLAIM, "catalog.release.branch", "catalog.release.publication", "catalog.release.version",
        LocalFoodProfileV1.CLAIM, LocalStockStoreProfile.ITEM_CLAIM, LocalStockStoreProfile.SOURCE_CLAIM,
        LocalStockStoreProfile.RESERVATION_CLAIM, LocalStockStoreProfile.ALLOCATION_CLAIM, LocalStockStoreProfile.DOCUMENT_CLAIM,
        LocalStockStoreProfile.ALLOWANCE_CLAIM, LocalStockStoreProfile.RECEIPT_LINE_CLAIM)

    fun require(cut: FoodColdCensusRows, plan: NativeFoodColdInputPlan, seed: FoodColdSeedBindings,
        protected: FoodColdProtectedSeedCut, originals: List<FoodColdSavedOriginal>) {
        check(cut.branch.integer("revision") == protected.reads.branchRevision.value)
        check(originals.map { it.symbol }.toSet() == seed.originalSymbols && originals.size == seed.originalSymbols.size)
        // Closed allowlists over the complete physical census establish the ZERO financial,
        // cash/refund/finalization/incident/restoration oracle; unknown kinds never count as zero.
        check(cut.table(NativeHistoryTableV2.AGGREGATE).all { it.text("kind") in allowedKinds }) {
            "An unknown or financial/incident/restoration aggregate is outside the unchanged unpaid seed"
        }
        check(cut.table(NativeHistoryTableV2.CLAIM).all { it.text("namespace") in allowedClaims })
        check(cut.table(NativeHistoryTableV2.OUTBOX).all {
            it.text("kind") == "journal.upload" || it.text("kind") == LocalFoodProfileV1.DELIVERY_JOB
        })
        requireCounts(cut, plan)
        val scope = originals.first().scope
        val detail = checkNotNull(protected.current.food.detail)
        val quotedAdd = originals.single { it.symbol == "SEED_QUOTED_ADD" }
        val batchId = DomainId(quotedAdd.request.operationId.value)
        val order = envelope(cut.aggregates(LocalFoodProfileV1.ORDER).single(), scope, originals)
        val orderBody = order.getJSONObject("body")
        check(orderBody.getString("type") == "ORDER" && orderBody.getString("creatorId") == detail.summary.ownerId.value &&
            orderBody.getString("ownerId") == detail.summary.ownerId.value && orderBody.getString("activity") == plan.activity.name &&
            dto(orderBody.getJSONObject("service"), LocalFoodServiceV1.serializer()) == plan.service &&
            orderBody.getJSONArray("portionIds").strings() == plan.portions.map { it.id.value } &&
            orderBody.getJSONArray("batchIds").strings() == listOf(batchId.value) &&
            dto(orderBody.getJSONObject("originalQuotedTotal"), Money.serializer()) == plan.payable &&
            order.getString("id") == plan.orderId.value && order.getLong("revision") == detail.summary.revision.value &&
            listOf("handoff", "financialCollectionId", "closedByOperationId", "deliveryDetails").all { orderBody.isNull(it) })
        val latestServe = originals.last { it.symbol.startsWith("SEED_SERVE_") }
        check(order.getString("operationId") == latestServe.request.operationId.value)

        val batchRow = cut.aggregates(LocalFoodProfileV1.PRICE_BATCH).single()
        val batch = envelope(batchRow, scope, originals)
        val priced = batch.getJSONObject("body")
        check(batchRow.text("id") == batchId.value && batchRow.integer("revision") == 1L &&
            batch.getString("operationId") == quotedAdd.request.operationId.value && priced.getString("type") == "PRICE_BATCH" &&
            priced.getString("orderId") == plan.orderId.value)
        val quote = StoredPricingSnapshotCodec.decode(priced.getString("quote")) // Historical decode, never calculate/current-price fallback.
        check(quote.snapshotId == batchId && quote.revision == Revision(0) && quote.currency == Currency.EGP &&
            quote.payable == plan.payable && quote.beforeDiscountPayable == plan.payable && quote.advertisedDiscount == plan.zero &&
            quote.charges.isEmpty() && quote.discounts.isEmpty() && quote.lines.map { it.portionId } == plan.portions.map { it.id } &&
            quote.lines.size == plan.totalQuantity.toInt() && quote.lines.sumOf { it.quantity.scaled } == plan.totalQuantity)
        val provenance = priced.getJSONArray("sources").objects().map { dto(it, LocalSalesPriceSourceV2.serializer()) }
        check(provenance.map { it.portionId } == quote.lines.map { it.portionId })
        for (input in plan.portions) {
            val line = quote.lines.single { it.portionId == input.id }
            val definition = seed.published.requested.skus.getValue(input.product)
            val price = definition.prices.single()
            val group = plan.lines.single { it.anchor == input.group }
            val source = provenance.single { it.portionId == input.id }
            check(line.lineId == input.group && line.quantity == plan.quantity(input.product, input.quantity) &&
                line.priceRevision == price.revision && line.unitPrice == price.unitPrice && line.tax == price.tax && line.inclusion == price.inclusion &&
                line.payable == Money(Currency.EGP, group.priceMinor) && line.exactOriginalItemNet == line.exactItemNet &&
                line.exactItemNet == price.unitPrice.amount.toFraction() * line.quantity.toFraction())
            check(source.scope == scope && source.lineId == input.group && source.portionId == input.id && source.skuId == plan.skuId(input.product) &&
                source.companyPublicationId == definition.publicationId && source.companyPriceRuleId == price.ruleId &&
                source.companyPriceRevision == price.revision && source.companyPriceDigest == seed.published.requested.priceDigest(input.product) &&
                source.companyUnitPrice == price.unitPrice && source.effectiveUnitPrice == price.unitPrice &&
                source.policyEntryId == null && source.policyEntryRevision == null && source.policyPublicationId == null && source.policyEntryDigest == null &&
                source.branchOverrideEventId == null && source.branchOverrideRevision == null)
        }
        for (group in plan.lines) check(quote.lines.filter { it.lineId == group.anchor }.let { rows ->
            rows.map { it.portionId } == group.portionIds && rows.sumOf { it.quantity.scaled } == group.quantity &&
                rows.sumOf { it.payable.minor } == group.totalMinor
        })
        check(quote.lines.map { it.lineId }.distinct().size == plan.groupCount && quote.components.all {
            it.kind in setOf(PriceComponentKind.ITEM_NET, PriceComponentKind.ITEM_TAX) && it.chargeId == null &&
                it.portionId in plan.portions.map { portion -> portion.id } && it.exact == it.exactBeforeDiscounts &&
                it.posted == it.postedBeforeDiscounts && (it.kind != PriceComponentKind.ITEM_TAX || it.posted == plan.zero)
        } && quote.components.sumOf { it.posted.minor } == plan.payable.minor)

        for (input in plan.portions) {
            val row = cut.aggregates(LocalFoodProfileV1.PORTION).single { it.text("id") == input.id.value }
            val retained = envelope(row, scope, originals)
            val body = retained.getJSONObject("body")
            check(body.getString("orderId") == plan.orderId.value && body.getString("priceBatchId") == batchId.value &&
                dto(body.getJSONObject("selection"), LocalFoodPortionSelectionV1.serializer()) == plan.selection(input) &&
                dto(body.getJSONObject("preparation"), FoodPreparationState.serializer()) == detail.portions.single { it.portionId == input.id }.preparation &&
                row.integer("revision") == detail.portions.single { it.portionId == input.id }.preparation.revision.value + 1L)
            val lastSymbol = when {
                input.fulfillment == FoodFulfillmentStatus.SERVED -> foodColdTransitionSymbol("SERVE", input.id)
                input.preparation == FoodPreparationStatus.READY -> foodColdTransitionSymbol("READY", input.id)
                input.preparation == FoodPreparationStatus.PREPARING -> foodColdTransitionSymbol("START", input.id)
                input.preparation == FoodPreparationStatus.QUEUED -> "SEED_SEND"
                else -> "SEED_QUOTED_ADD"
            }
            check(retained.getString("operationId") == originals.single { it.symbol == lastSymbol }.request.operationId.value)
            val quotationId = LocalFoodQuotationProfileV1.quotationId(input.id, plan.recipePublicationId(input.product))
            val quotationRow = cut.aggregates(RECIPE_QUOTATION).single { it.text("id") == quotationId.value }
            check(quotationRow.text("schema") == "$RECIPE_QUOTATION/v1" && quotationRow.integer("revision") == 1L)
            val quotationEnvelope = envelope(quotationRow, scope, originals)
            val quotationBody = quotationEnvelope.getJSONObject("body")
            check(quotationEnvelope.getString("operationId") == quotedAdd.request.operationId.value &&
                quotationBody.getString("type") == "RECIPE_QUOTATION" && quotationBody.getString("orderId") == plan.orderId.value &&
                quotationBody.getString("priceBatchId") == batchId.value)
            val quotation = dto(quotationBody.getJSONObject("quotation"), LocalFoodRecipeQuotationV1.serializer())
            requireQuotation(quotation, input, scope, plan, seed, cut, originals)
            if (input.tasks == 0) {
                check(body.getString("type") == "QUOTED_PORTION" && row.text("schema") == QUOTED_PORTION_SCHEMA &&
                    body.getString("quotationId") == quotationId.value)
            } else {
                check(body.getString("type") == "PORTION" && row.text("schema") == "${LocalFoodProfileV1.PORTION}/stock-v2" &&
                    body.isNull("originalIssue") && body.isNull("financialCollectionId"))
                val snapshot = dto(body.getJSONObject("recipe"), LocalFoodRecipeSnapshotV1.serializer())
                check(FoodRecipeRouting.requireOriginalFacts(quotation, snapshot) == snapshot && snapshot.warehouseId == plan.warehouseId &&
                    snapshot.routeVersionId == DomainId(plan.operation(400 + plan.outputRoles.indexOf(input.product)).value) &&
                    snapshot.routes.size == 1 && snapshot.routes.single().routeKey == plan.routeKey(input.product) &&
                    snapshot.routes.single().stationId == plan.stationId && snapshot.routes.single().mode == FoodStationDeliveryMode.DISPLAY)
                check(snapshot.sources.take(quotation.sources.size) == quotation.sources)
                val routing = snapshot.sources.drop(quotation.sources.size)
                check(routing.map { it.id } == listOf(snapshot.routeVersionId, DomainId(plan.operation(205).value)))
                routing.forEach { source -> requireSource(source, cut, scope, plan, seed, originals) }
                requireIssue(body.getJSONObject("normalizedIssue"), input, snapshot, detail, plan, originals)
            }
        }
        val ownerIndex = envelope(cut.aggregates(LocalFoodProfileV1.OWNER_INDEX).single(), scope, originals)
        check(ownerIndex.getString("id") == detail.summary.ownerId.value &&
            ownerIndex.getJSONObject("body").getJSONArray("orderIds").strings() == listOf(plan.orderId.value))
        val station = envelope(cut.aggregates(LocalFoodProfileV1.STATION_INDEX).single(), scope, originals)
        val tasks = station.getJSONObject("body").getJSONArray("tasks").objects()
        val active = detail.portions.filter { it.preparation.tasks.isNotEmpty() && it.preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER }
        check(station.getString("id") == plan.stationId.value && tasks.size == active.size && tasks.map { it.getString("taskId") }.toSet() ==
            active.map { it.preparation.tasks.single().taskId.value }.toSet() && tasks.all { it.getString("orderId") == plan.orderId.value })
        requireStock(cut, scope, plan, detail, originals)
    }

    private fun requireCounts(cut: FoodColdCensusRows, plan: NativeFoodColdInputPlan) {
        val exact = mapOf(LocalConfigurationProfile.SKU to plan.roles.size, LocalConfigurationProfile.PUBLICATION to plan.roles.size,
            LocalConfigurationProfile.WAREHOUSE to 1, LocalConfigurationProfile.BASIS to 1, LocalConfigurationProfile.CATALOG_CURSOR to 1,
            LocalFoodConfigurationProfileV1.RECIPE to plan.outputRoles.size, LocalFoodConfigurationProfileV1.PUBLICATION to plan.outputRoles.size,
            LocalFoodConfigurationProfileV1.ROUTE to plan.outputRoles.size, LocalFoodConfigurationProfileV1.STATION to 1,
            LocalFoodConfigurationProfileV1.VERSION to 1 + 2 * plan.outputRoles.size,
            LocalFoodFloorProfileV1.FLOOR to 1, LocalFoodFloorProfileV1.TABLE to 1, LocalFoodFloorProfileV1.VERSION to 2,
            LocalBranchActivityProfileV1.ACTIVE to 1, LocalBranchActivityProfileV1.PUBLICATION to 1,
            LocalCatalogReleaseProfileV1.ACTIVE to 1, LocalCatalogReleaseProfileV1.PUBLICATION to 1,
            LocalFoodProfileV1.ORDER to 1, LocalFoodProfileV1.PORTION to plan.portions.size, LocalFoodProfileV1.PRICE_BATCH to 1,
            LocalFoodProfileV1.OWNER_INDEX to 1, LocalFoodProfileV1.STATION_INDEX to 1, RECIPE_QUOTATION to plan.portions.size,
            LocalFoodTableAssignmentProfileV1.BINDING to 1, LocalFoodTableAssignmentProfileV1.VERSION to 1,
            StockRelationalKindsV2.ITEM to plan.stocks.size, StockRelationalKindsV2.POSITION to plan.stocks.size,
            StockRelationalKindsV2.POOL to plan.stocks.size, StockRelationalKindsV2.ISSUE to plan.issuedPortions,
            StockRelationalKindsV2.RESERVATION to plan.issuedPortions, StockRelationalKindsV2.ACTIVATION to 1,
            LocalStockStoreProfile.RECEIPT_DOCUMENT to 1, LocalStockStoreProfile.ALLOWANCE to plan.stocks.size)
        exact.forEach { (kind, count) -> check(cut.aggregates(kind).size == count) { "Unexpected literal native seed membership: $kind" } }
    }

    private fun requireQuotation(q: LocalFoodRecipeQuotationV1, input: FoodColdPortionInput, scope: BranchScope,
        plan: NativeFoodColdInputPlan, seed: FoodColdSeedBindings, cut: FoodColdCensusRows, originals: List<FoodColdSavedOriginal>) {
        val recipe = seed.published.requested.recipes.getValue(input.product)
        val choice = recipe.choices.single()
        val ingredients = plan.components.getValue(input.product).mapIndexed { index, component -> LocalFoodIngredientV1(
            plan.componentId(input.product, index), plan.skuId(component.role), Revision(1), plan.quantity(component.role, component.quantity),
            plan.quantity(component.role, component.quantity)) }
        check(q.quotationId == LocalFoodQuotationProfileV1.quotationId(input.id, recipe.publicationId) && q.portionId == input.id &&
            q.activity == plan.activity && q.channel == LocalSalesChannel.DINE_IN && q.recipeId == recipe.recipeId && q.recipeRevision == Revision(1) &&
            q.publicationId == recipe.publicationId && q.outputSkuId == recipe.outputSkuId && q.outputSkuRevision == Revision(1) &&
            q.choiceId == choice.id && q.modifierIds.isEmpty() && q.description == "Standard" &&
            q.yieldQuantity == recipe.yieldQuantity && q.portionQuantity == plan.quantity(input.product, input.quantity) &&
            q.pricingSkuId == recipe.outputSkuId && q.ingredients == ingredients && q.routeKeys == listOf(plan.routeKey(input.product)) &&
            q.signedPublication == LocalFoodConfigurationCodecV1.signedPublication(seed.published.recipes.getValue(input.product)))
        val expected = listOf(LocalFoodConfigurationProfileV1.PUBLICATION to recipe.publicationId) +
            (listOf(input.product) + plan.components.getValue(input.product).map { it.role }).distinct().map {
                LocalConfigurationProfile.PUBLICATION to plan.skuPublicationId(it) }
        check(q.sources.map { it.kind to it.id } == expected)
        q.sources.forEach { requireSource(it, cut, scope, plan, seed, originals) }
    }

    private fun requireSource(source: LocalFoodSourceReferenceV1, cut: FoodColdCensusRows, scope: BranchScope,
        plan: NativeFoodColdInputPlan, seed: FoodColdSeedBindings, originals: List<FoodColdSavedOriginal>) {
        val row = cut.aggregates(source.kind).single { it.text("id") == source.id.value }
        check(source.revision.value == row.integer("revision") && source.digest.value == row.text("payloadHash") && source.authorizedBy == plan.administratorId)
        val record = JSONObject(row.textBytes("payload"))
        check(dto(record.getJSONObject("scope"), BranchScope.serializer()) == scope)
        when (source.kind) {
            LocalConfigurationProfile.PUBLICATION -> {
                val role = plan.roles.single { plan.skuPublicationId(it) == source.id }
                val original = originals.single { it.symbol == "INSTALL_SKU_$role" }
                check(source.operationId == null && source.signingKeyId == seed.published.skus.getValue(role).signingKeyId &&
                    record.getString("kind") == source.kind && record.getString("id") == source.id.value &&
                    record.getString("authorizedBy") == source.authorizedBy.value && record.getLong("policyRevision") == original.policyRevision.value &&
                    record.getString("recordedAt") == original.result.recordedAt.canonicalString() &&
                    dto(record.getJSONObject("body").getJSONObject("value"), LocalSignedSkuV1.serializer()) == seed.published.skus.getValue(role))
            }
            LocalFoodConfigurationProfileV1.PUBLICATION, LocalFoodConfigurationProfileV1.VERSION -> {
                val original = originals.single { it.request.operationId == source.operationId }
                check(record.getString("operationId") == original.request.operationId.value && record.getString("actorId") == source.authorizedBy.value &&
                    record.getLong("policyRevision") == original.policyRevision.value && record.getString("recordedAt") == original.result.recordedAt.canonicalString())
                val request = dto(record.getJSONObject("request"), LocalFoodConfigurationRequestV1.serializer())
                if (source.kind == LocalFoodConfigurationProfileV1.PUBLICATION) {
                    val role = plan.outputRoles.single { plan.recipePublicationId(it) == source.id }
                    check(request == LocalFoodConfigurationRequestV1.InstallRecipe(seed.published.recipes.getValue(role)) &&
                        source.signingKeyId == seed.published.recipes.getValue(role).signingKeyId && original.symbol == "INSTALL_RECIPE_$role")
                } else {
                    check(source.signingKeyId == null && source.id.value == original.request.operationId.value &&
                        request == (if (original.symbol == "CONFIGURE_STATION") LocalFoodConfigurationRequestV1.Station(null, plan.station)
                        else LocalFoodConfigurationRequestV1.Route(null, plan.route(plan.outputRoles.single { original.symbol == "CONFIGURE_ROUTE_$it" }))))
                }
            }
            else -> error("Unsupported immutable original recipe source")
        }
    }

    private fun requireIssue(reference: JSONObject, input: FoodColdPortionInput, snapshot: LocalFoodRecipeSnapshotV1,
        detail: LocalFoodOrderDetailV1, plan: NativeFoodColdInputPlan, originals: List<FoodColdSavedOriginal>) {
        val portion = detail.portions.single { it.portionId == input.id }
        val send = originals.single { it.symbol == "SEED_SEND" }
        check(reference.getString("issueId") == portion.preparation.issueId?.value && reference.getString("reservationId") == portion.preparation.reservationId?.value &&
            reference.getString("operationId") == send.request.operationId.value && reference.getString("actorId") == send.identity.originalActorId.value &&
            reference.getString("semanticDigest") == send.identity.semanticSha256 && reference.getString("orderId") == plan.orderId.value &&
            reference.getString("collectionActorId") == detail.summary.ownerId.value && reference.getString("exposure") == "UNCOLLECTED" &&
            reference.getLong("allocationCount") == snapshot.ingredients.size.toLong() && dto(reference.getJSONObject("originalValue"), Money.serializer()) == plan.zero &&
            reference.getString("recipeSnapshotId") == snapshot.snapshotId.value && reference.getJSONArray("requiredStationIds").strings() == listOf(plan.stationId.value))
    }

    private fun requireStock(cut: FoodColdCensusRows, scope: BranchScope, plan: NativeFoodColdInputPlan,
        detail: LocalFoodOrderDetailV1, originals: List<FoodColdSavedOriginal>) {
        val ids = originals.map { it.request.operationId.value }.toSet()
        for (row in cut.table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") in StockRelationalKindsV2.all }) {
            val record = JSONObject(row.textBytes("payload"))
            check(record.getInt("format") == 2 && dto(record.getJSONObject("scope"), BranchScope.serializer()) == scope &&
                record.getString("kind") == row.text("kind") && record.getString("id") == row.text("id") && record.getLong("generation") == row.integer("revision"))
            val producer = record.getJSONObject("producer")
            val original = originals.single { it.request.operationId.value == producer.getString("operationId") }
            check(producer.getString("command") == original.request.command.name && producer.getInt("version") == original.request.command.version &&
                producer.getString("actorId") == original.identity.originalActorId.value && producer.getString("digest") == original.identity.semanticSha256)
        }
        val indexedKinds = mapOf(
            NativeHistoryTableV2.STOCK_POSITION to setOf(StockRelationalKindsV2.POSITION),
            NativeHistoryTableV2.STOCK_ALLOCATION to setOf(StockRelationalKindsV2.ALLOCATION, StockRelationalKindsV2.HOLD),
            NativeHistoryTableV2.STOCK_RESERVATION to setOf(StockRelationalKindsV2.RESERVATION),
            NativeHistoryTableV2.STOCK_VALUE to setOf(StockRelationalKindsV2.ISSUE_SLICE, StockRelationalKindsV2.VALUE_EVENT,
                StockRelationalKindsV2.VALUE_SLICE, StockRelationalKindsV2.VALUE_TARGET, StockRelationalKindsV2.TRANSFER_EDGE))
        check(indexedKinds.values.flatten().toSet() == StockRelationalKindsV2.indexed)
        for ((table, kinds) in indexedKinds) {
            val indexes = cut.table(table)
            val aggregateKeys = cut.table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") in kinds }
                .map { it.text("kind") to it.text("id") }
            val indexKeys = indexes.map { it.text("recordKind") to it.text("recordId") }
            // Every indexed aggregate needs exactly one row in its actual fixed table;
            // an empty/missing selector cannot be accepted by a one-way join.
            check(indexKeys.size == aggregateKeys.size && indexKeys.toSet().size == indexKeys.size &&
                indexKeys.toSet() == aggregateKeys.toSet())
            for (index in indexes) {
                check(index.text("recordKind") in kinds)
                val row = cut.aggregates(index.text("recordKind")).single { it.text("id") == index.text("recordId") }
                check(index.integer("aggregateGeneration") == row.integer("revision") && index.text("payloadHash") == row.text("payloadHash") &&
                    index.text("producerOperationId") in ids && index.text("producerOperationId") ==
                    JSONObject(row.textBytes("payload")).getJSONObject("producer").getString("operationId"))
            }
        }
        val issues = detail.portions.filter { it.preparation.issueId != null }
        check(cut.aggregates(StockRelationalKindsV2.ISSUE).map { it.text("id") }.toSet() == issues.map { checkNotNull(it.preparation.issueId).value }.toSet() &&
            cut.aggregates(StockRelationalKindsV2.RESERVATION).map { it.text("id") }.toSet() == issues.map { checkNotNull(it.preparation.reservationId).value }.toSet())
        for (input in plan.stocks) {
            val positions = cut.aggregates(StockRelationalKindsV2.POSITION).map(::stockBody).filter {
                it.getJSONObject("value").getJSONObject("key").getString("skuId") == plan.skuId(input.role).value }
            check(positions.size == 1)
            val value = positions.single().getJSONObject("value")
            check(dto(value.getJSONObject("onHand"), Quantity.serializer()) == plan.quantity(input.role, input.available))
        }
        val movements = cut.aggregates(StockRelationalKindsV2.VALUE_EVENT).map(::stockBody)
        check(movements.all { it.getInt("event") in setOf(2, 7) &&
            dto(it.getJSONObject("originalValueDelta"), Money.serializer()) == plan.zero &&
            dto(it.getJSONObject("carryingValueDelta"), Money.serializer()) == plan.zero })
        check(movements.count { it.getInt("event") == 7 } == plan.stocks.size)
        val allocationCount = plan.portions.filter { it.tasks != 0 }.sumOf { plan.components.getValue(it.product).size }
        check(movements.count { it.getInt("event") == 2 } == allocationCount &&
            cut.aggregates(StockRelationalKindsV2.ISSUE_SLICE).size == allocationCount)
        check(cut.aggregates(StockRelationalKindsV2.ISSUE_SLICE).map(::stockBody).all {
            dto(it.getJSONObject("restoredQuantity"), Quantity.serializer()).scaled == 0L &&
                dto(it.getJSONObject("originalValue"), Money.serializer()) == plan.zero })
    }

    private fun envelope(row: FoodColdCensusRow, scope: BranchScope, originals: List<FoodColdSavedOriginal>): JSONObject {
        val value = JSONObject(row.textBytes("payload"))
        check(dto(value.getJSONObject("scope"), BranchScope.serializer()) == scope && value.getString("kind") == row.text("kind") &&
            value.getString("id") == row.text("id") && value.getLong("revision") == row.integer("revision") &&
            value.getString("operationId") in originals.map { it.request.operationId.value })
        return value
    }
    private fun stockBody(row: FoodColdCensusRow): JSONObject = JSONObject(JSONObject(row.textBytes("payload")).getString("body"))
    private fun <T> dto(value: JSONObject, serializer: KSerializer<T>): T = foodColdJson.decodeFromString(serializer, value.toString())
}

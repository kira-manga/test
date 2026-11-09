package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import org.json.JSONArray
import org.json.JSONObject

/** Only successful actual protected projections enter a cut. Read metadata remain
 * observations, not authority, a preflight grant, or native full-history admission. */
internal class FoodColdReadCut {
    private val retained = linkedMapOf<String, StoredBytes>()
    private var revision: Revision? = null
    val payloads: Map<String, StoredBytes> get() = retained.toMap()
    val branchRevision: Revision get() = checkNotNull(revision)
    fun add(symbol: String, reply: ReadReply.Data): StoredBytes {
        check(retained.put(symbol, reply.payload) == null)
        revision?.let { check(reply.branchRevision == it) { "Protected pages did not observe one unchanged business cut" } }
        revision = reply.branchRevision
        return reply.payload
    }
    fun requireSame(after: FoodColdReadCut) {
        check(branchRevision == after.branchRevision && payloads == after.payloads) { "The original protected business cut changed" }
    }
    /** A separately performed business mutation may advance the branch revision while
     * documentary configuration remains byte-for-byte identical. Cold equality above
     * deliberately keeps the stronger final branch-revision requirement. */
    fun requireSamePayloads(after: FoodColdReadCut) {
        check(payloads == after.payloads) { "Documentary activation changed a retained protected source" }
    }
}

internal class FoodColdConfigurationCut(val catalogCursor: Revision, val sources: FoodColdReadCut, val stock: FoodColdReadCut) {
    fun requireSameSources(after: FoodColdConfigurationCut) {
        check(catalogCursor == after.catalogCursor)
        sources.requireSame(after.sources)
    }
    fun requireSame(after: FoodColdConfigurationCut) { requireSameSources(after); stock.requireSame(after.stock) }
    fun requireSameDocumentarySources(after: FoodColdConfigurationCut) {
        check(catalogCursor == after.catalogCursor)
        sources.requireSamePayloads(after.sources)
    }
    fun requireNoDocumentaryRewrite(after: FoodColdConfigurationCut) {
        requireSameDocumentarySources(after)
        stock.requireSamePayloads(after.stock)
    }
}

internal suspend fun NativeFoodColdClient.foodColdConfiguration(actor: FoodColdActor, kind: String,
    id: DomainId? = null): LocalConfigurationPageV1 = LocalConfigurationCodec.decodePage(read(actor,
        ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id)))).payload)

internal suspend fun NativeFoodColdClient.foodColdOrder(plan: NativeFoodColdInputPlan): LocalFoodOrderDetailV1 {
    val found = LocalFoodReadCodecV1.result(read(FoodColdActor.OWNER,
        LocalFoodReadCodecV1.read(LocalFoodQueryV1.Order(plan.orderId))).payload)
    check(found.unavailable == null && found.nextAfter == null && found.orders.isEmpty() && found.tasks.isEmpty())
    return checkNotNull(found.detail)
}

internal suspend fun foodColdRequireSku(client: NativeFoodColdClient, actor: FoodColdActor, plan: NativeFoodColdInputPlan,
    sources: FoodColdRequestedSources, role: String): LocalFoodConfigurationPageV1 {
    val found = LocalFoodConfigurationReadCodecV1.result(client.read(actor,
        LocalFoodConfigurationReadCodecV1.read(LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.SKU, plan.skuId(role)))).payload)
    requireSkuDefinition(found, plan, sources, role)
    return found
}
private fun requireSkuDefinition(found: LocalFoodConfigurationPageV1, plan: NativeFoodColdInputPlan,
    sources: FoodColdRequestedSources, role: String) {
    val sku = sources.skus.getValue(role)
    check(found.kind == LocalFoodConfigurationSourceKindV1.SKU && found.labels.size == 1 && found.nextAfter == null &&
        (found.detail as? LocalFoodConfigurationDetailV1.Sku)?.definition == sku)
    val label = found.labels.single()
    check(label.id == sku.skuId && label.revision == Revision(1) && label.storageRevision.value > 0 && label.active && label.names == sku.names)
    check(sku.catalogRevision == Revision(0) && sku.skuRevision == Revision(1) && sku.productDefinition?.revision == Revision(0) &&
        sku.productDefinition?.id == plan.productId(role) && sku.productDefinition?.allowedVariantCombinations == listOf(emptyList<LocalVariantValueV1>()) &&
        sku.variantValues == emptyList<LocalVariantValueV1>() && sku.unitDefinitions?.size == 1)
}

/** Retains complete definitions and their actual storage/domain/cursor coordinates. The
 * sensitive stock/value/receiving parts are separately authorized to the administrator. */
internal suspend fun foodColdConfigurationCut(client: NativeFoodColdClient, plan: NativeFoodColdInputPlan,
    requested: FoodColdRequestedSources, basisRevision: Revision, openingAt: UtcInstant,
    sourceActor: FoodColdActor = FoodColdActor.ADMINISTRATOR, atSeed: Boolean = false): FoodColdConfigurationCut {
    val sources = FoodColdReadCut()
    val stock = FoodColdReadCut()
    var cursor: Revision? = null
    suspend fun configuration(kind: String, id: DomainId? = null): LocalConfigurationPageV1 {
        val input = ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id)))
        val body = sources.add("CONFIGURATION/$kind/${id?.value ?: "ALL"}", client.read(
            if (kind == LocalConfigurationProfile.SKU) sourceActor else FoodColdActor.ADMINISTRATOR, input))
        val result = LocalConfigurationCodec.decodePage(body)
        check(result.kind == kind && result.nextAfter == null)
        if (kind == LocalConfigurationProfile.SKU) {
            val actual = checkNotNull(result.catalogRevision) { "The current local quotation cursor is required" }
            cursor?.let { check(it == actual) }; cursor = actual
        }
        return result
    }
    val list = configuration(LocalConfigurationProfile.SKU)
    check(list.items.map { it.id }.toSet() == plan.roles.map(plan::skuId).toSet() && list.items.size == plan.roles.size && list.detail == null)
    for (role in plan.roles) {
        val definition = requested.skus.getValue(role)
        val short = configuration(LocalConfigurationProfile.SKU, plan.skuId(role))
        // The general configuration record has its own initial domain revision 0,
        // independent of both SKU revision 1 and the signed publication revision 0.
        check(short.items.size == 1 && short.items.single().revision == Revision(0) && short.items.single().storageRevision.value > 0)
        val detail = short.detail as? LocalConfigurationDetailV1.Sku ?: error("The selected SKU full catalog detail is required")
        check(detail.skuId == definition.skuId && detail.productId == definition.productId && detail.skuRevision == Revision(1) &&
            detail.publicationRevision == Revision(0) && detail.names == definition.names && detail.code == definition.code &&
            detail.barcodes == definition.barcodes && detail.baseUnitId == definition.baseUnitId && detail.quantityScale == 0 &&
            detail.tracking == definition.tracking && detail.fulfillment == definition.fulfillment && detail.active == definition.active &&
            detail.canPurchase == definition.canPurchase && detail.canSell == definition.canSell && detail.requiresExpiry == definition.requiresExpiry &&
            detail.conversions == definition.conversions && detail.prices == definition.prices && detail.unitDefinitions == definition.unitDefinitions &&
            detail.productDefinition == definition.productDefinition && detail.variantValues == definition.variantValues)
        val full = LocalFoodConfigurationReadCodecV1.result(sources.add("FULL_SKU/$role", client.read(sourceActor,
            LocalFoodConfigurationReadCodecV1.read(LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.SKU, definition.skuId)))))
        requireSkuDefinition(full, plan, requested, role)
        check(full.labels.single().storageRevision == short.items.single().storageRevision)
    }
    for (role in plan.outputRoles) {
        val full = LocalFoodConfigurationReadCodecV1.result(sources.add("FULL_RECIPE/$role", client.read(sourceActor,
            LocalFoodConfigurationReadCodecV1.read(LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, plan.recipeId(role))))))
        check(full.kind == LocalFoodConfigurationSourceKindV1.RECIPE && full.labels.size == 1 && full.nextAfter == null &&
            full.labels.single().id == plan.recipeId(role) && full.labels.single().revision == Revision(1) && full.labels.single().active &&
            (full.detail as? LocalFoodConfigurationDetailV1.Recipe)?.definition == requested.recipes.getValue(role))
        val route = LocalFoodConfigurationReadCodecV1.result(sources.add("FULL_ROUTE/$role", client.read(sourceActor,
            LocalFoodConfigurationReadCodecV1.read(LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.ROUTE, plan.routeId(role))))))
        check(route.kind == LocalFoodConfigurationSourceKindV1.ROUTE && route.labels.size == 1 && route.nextAfter == null &&
            route.labels.single().id == plan.routeId(role) && route.labels.single().storageRevision.value > 0 && route.labels.single().active &&
            (route.detail as? LocalFoodConfigurationDetailV1.Route)?.definition == plan.route(role))
    }
    val station = LocalFoodConfigurationReadCodecV1.result(sources.add("FULL_STATION", client.read(sourceActor,
        LocalFoodConfigurationReadCodecV1.read(LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.STATION, plan.stationId)))))
    check(station.labels.size == 1 && station.nextAfter == null && station.labels.single().id == plan.stationId &&
        station.labels.single().storageRevision.value > 0 && (station.detail as? LocalFoodConfigurationDetailV1.Station)?.definition == plan.station)
    val warehouse = configuration(LocalConfigurationProfile.WAREHOUSE, plan.warehouseId)
    check(warehouse.items.size == 1 && (warehouse.detail as? LocalConfigurationDetailV1.Warehouse)?.value == plan.warehouse)
    val basis = configuration(LocalConfigurationProfile.BASIS, plan.openingApprovalId)
    check(basis.items.size == 1 && basis.items.single().revision == basisRevision &&
        (basis.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == plan.openingApproval)
    check(configuration(LocalConfigurationProfile.WAREHOUSE).items.map { it.id } == listOf(plan.warehouseId))
    check(configuration(LocalConfigurationProfile.BASIS).items.map { it.id } == listOf(plan.openingApprovalId))
    for (kind in listOf(LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.TENDER, LocalConfigurationProfile.SALES_ROUTE))
        check(configuration(kind).items.isEmpty())
    val activity = LocalBranchActivityCodecV1.decodeRead(sources.add("ACTIVITY", client.read(sourceActor, LocalBranchActivityCodecV1.readCurrent())))
    val applied = checkNotNull(activity.applied)
    check(activity.status == LocalBranchActivityReadStatusV1.APPLIED && applied.definition == requested.activity &&
        applied.activities == setOf(plan.activity) && applied.actorId == plan.administratorId && applied.operationId == plan.operation(200))
    val floor = foodColdRequireFloor(client, sourceActor, plan)
    sources.add("FLOOR_TABLE_DIRECT", client.read(sourceActor, LocalFoodFloorReadCodecV1.read(LocalFoodFloorQueryV1.Table(plan.tableId))))
    val floors = LocalFoodFloorReadCodecV1.decodeResult(sources.add("FLOORS", client.read(sourceActor,
        LocalFoodFloorReadCodecV1.read(LocalFoodFloorQueryV1.Floors()))))
    check(floors.configured && floors.floors.size == 1 && floors.floors.single().definition == plan.floor && floors.nextAfter == null &&
        floors.floors.single() == floor.floor)
    val tables = LocalFoodFloorReadCodecV1.decodeResult(sources.add("FLOOR_TABLES", client.read(sourceActor,
        LocalFoodFloorReadCodecV1.read(LocalFoodFloorQueryV1.Tables(plan.floorId)))))
    check(tables == floor.copy(query = LocalFoodFloorQueryV1.Tables(plan.floorId)))
    val opening = foodColdRequireOpening(client, plan, basisRevision, openingAt)
    sources.add("OPENING_QUANTITY", opening.quantity)
    sources.add("OPENING_COST", opening.costs)
    val itemPage = LocalStockQueries.decodeItems(stock.add("STOCK_ITEMS", client.read(FoodColdActor.ADMINISTRATOR, LocalStockCommands.items())))
    check(itemPage.nextAfter == null && itemPage.items.size == plan.stocks.size &&
        itemPage.items.map { it.skuId }.toSet() == plan.stocks.map { plan.skuId(it.role) }.toSet())
    check(LocalStockRelationalQueriesV2.normalized(stock.add("STOCK_PROFILE", client.read(FoodColdActor.ADMINISTRATOR,
        LocalStockRelationalQueriesV2.profile()))))
    for (input in plan.stocks) {
        val reads = foodColdRequireStock(client, plan, input, atSeed)
        stock.add("QUANTITY/${input.role}", reads.quantity)
        stock.add("COST/${input.role}", reads.costs)
        val item = itemPage.items.single { it.skuId == plan.skuId(input.role) }
        check(item.unitId == plan.unit(input.unit).id && item.quantityScale == 0 && item.positionCount == 1 &&
            item.storageRevision == reads.quantities.itemStorageRevision)
        stock.add("HISTORY/${input.role}", client.read(FoodColdActor.ADMINISTRATOR,
            LocalStockRelationalQueriesV2.history(plan.skuId(input.role), plan.warehouseId)))
    }
    return FoodColdConfigurationCut(checkNotNull(cursor), sources, stock)
}

internal suspend fun foodColdRequireFloor(client: NativeFoodColdClient, actor: FoodColdActor,
    plan: NativeFoodColdInputPlan): LocalFoodFloorPageV1 {
    val page = LocalFoodFloorReadCodecV1.decodeResult(client.read(actor,
        LocalFoodFloorReadCodecV1.read(LocalFoodFloorQueryV1.Table(plan.tableId))).payload)
    check(page.query == LocalFoodFloorQueryV1.Table(plan.tableId) && page.configured && page.floors.isEmpty() && page.tables.size == 1 &&
        page.nextAfter == null && page.tables.single().definition == plan.table && page.floor?.definition == plan.floor)
    return page
}

internal suspend fun foodColdRequireRelease(client: NativeFoodColdClient, plan: NativeFoodColdInputPlan,
    published: FoodColdPublishedSources): LocalCatalogReleaseReadReplyV1 {
    val result = LocalCatalogReleaseCodecV1.decodeRead(client.read(FoodColdActor.ADMINISTRATOR, LocalCatalogReleaseCodecV1.query(
        LocalCatalogReleaseQueryV1(plan.catalogId, published.releases.last().definition.publicationId))).payload)
    check(result.catalogId == plan.catalogId && result.activeStorageRevision != null && result.active == published.releases.last() &&
        result.publication == published.releases.last() && result.active?.definition?.revision == Revision(5))
    return result
}

internal data class FoodColdOpeningReads(val quantity: ReadReply.Data, val costs: ReadReply.Data)
internal suspend fun foodColdRequireOpening(client: NativeFoodColdClient, plan: NativeFoodColdInputPlan,
    basisRevision: Revision, openingAt: UtcInstant): FoodColdOpeningReads {
    val quantityReply = client.read(FoodColdActor.ADMINISTRATOR, LocalStockCommands.receipt(plan.openingReceiptId))
    val costReply = client.read(FoodColdActor.ADMINISTRATOR, LocalStockCommands.receiptCosts(plan.openingReceiptId))
    fun requireReceipt(reply: ReadReply.Data, costs: Boolean) {
        val receipt = checkNotNull(LocalStockQueries.decodeReceipt(reply.payload))
        check(receipt.receiptId == plan.openingReceiptId && receipt.basisId == plan.openingApprovalId && receipt.basisRevision == basisRevision &&
            receipt.basisKind == LocalStockBasisKind.OPENING && receipt.supplierId == null && receipt.recordedBy == plan.administratorId &&
            receipt.receivedAt == openingAt && receipt.reasonCode == plan.reasonCode && receipt.costsIncluded == costs && receipt.lines.size == plan.stocks.size)
        for (input in plan.stocks) {
            val line = receipt.lines.single { it.skuId == plan.skuId(input.role) }
            check(line.lineId == plan.receiptLineId(input.role) && line.sourceId == plan.stockSourceId(input.role) &&
                line.allocationId == plan.allocationId(input.role) && line.warehouseId == plan.warehouseId &&
                line.recordedQuantity == plan.quantity(input.role, input.initial) && line.baseQuantity == plan.quantity(input.role, input.initial) &&
                line.conversion == null && line.lotId == null && line.expiresAt == null && line.disposition == LocalReceiptDisposition.RELEASED &&
                line.originalValue == if (costs) plan.zero else null)
            check(line.releasedFromQuarantine == plan.quantity(input.role, 0) && line.serialId == null && line.serialCode == null &&
                line.initialSerialRegistrationStatus == null)
        }
    }
    requireReceipt(quantityReply, false); requireReceipt(costReply, true)
    return FoodColdOpeningReads(quantityReply, costReply)
}

internal data class FoodColdStockReads(val quantity: ReadReply.Data, val costs: ReadReply.Data, val quantities: LocalStockQuantityPage)
internal suspend fun foodColdRequireStock(client: NativeFoodColdClient, plan: NativeFoodColdInputPlan,
    input: FoodColdStockInput, atSeed: Boolean): FoodColdStockReads {
    val skuId = plan.skuId(input.role)
    val quantity = client.read(FoodColdActor.OWNER, LocalStockCommands.quantities(skuId))
    val costs = client.read(FoodColdActor.ADMINISTRATOR, LocalStockCommands.costs(skuId))
    val q = LocalStockQueries.decodeQuantities(quantity.payload)
    val c = LocalStockQueries.decodeCosts(costs.payload)
    val available = plan.quantity(input.role, if (atSeed) input.available else input.initial)
    val zero = plan.quantity(input.role, 0)
    check(q.skuId == skuId && q.nextAfter == null && q.itemStorageRevision != null && q.unitId == available.unitId && q.quantityScale == 0 && q.rows.size == 1)
    val row = q.rows.single()
    check(row.position.skuId == skuId && row.position.warehouseId == plan.warehouseId && row.position.supplierId == null &&
        row.position.lotId == null && row.position.serialId == null && row.position.custody == StockCustody.RELEASED &&
        row.onHand == available && row.available == available && row.reserved == plan.quantity(input.role, if (atSeed) input.reserved else 0) &&
        row.safety == StockSafety.CLEAR && row.expiresAt == null && !row.expiredAtRead && row.heldQuantity == zero &&
        row.heldShortageQuantity == zero && row.serialCode == null && row.serialRegistrationStatus == null)
    check(c.skuId == skuId && c.itemStorageRevision == q.itemStorageRevision && c.nextAfter == null && c.rows.size == 1 &&
        c.rows.single().warehouseId == plan.warehouseId && c.rows.single().quantity == available && c.rows.single().carryingValue == plan.zero)
    return FoodColdStockReads(quantity, costs, q)
}

internal object NativeFoodColdProjections {
    suspend fun readLiteralSeed(client: NativeFoodColdClient, plan: NativeFoodColdInputPlan, seed: FoodColdSeedBindings,
        originals: List<FoodColdSavedOriginal>, label: String, evidence: NativeFoodColdEvidence): FoodColdProtectedSeedCut {
        check(originals.map { it.symbol }.toSet() == seed.originalSymbols && originals.size == seed.originalSymbols.size)
        val configuration = foodColdConfigurationCut(client, plan, seed.published.requested, seed.openingBasisRevision,
            seed.openingRecordedAt, sourceActor = FoodColdActor.OWNER, atSeed = true)
        // Seed preparation changes business branch revision, not these exact configuration
        // payloads/cursors. Cold comparison below additionally preserves the final revision.
        check(configuration.catalogCursor == seed.quoteCursor && configuration.sources.payloads == seed.configuration.sources.payloads)
        seed.published.requireCurrent(checkNotNull(client.publications))
        val reads = FoodColdReadCut()
        val release = LocalCatalogReleaseCodecV1.decodeRead(reads.add("RELEASE5", client.read(FoodColdActor.ADMINISTRATOR,
            LocalCatalogReleaseCodecV1.query(LocalCatalogReleaseQueryV1(plan.catalogId, seed.published.releases.last().definition.publicationId)))))
        check(release == seed.release)
        check(LocalFoodCheckKitchenCodecV3.QUERY in client.original.readKeys)
        val current = LocalFoodCheckKitchenCodecV3.result(reads.add("OWNER_DIRECT_V3", client.read(FoodColdActor.OWNER,
            LocalFoodCheckKitchenCodecV3.read(LocalFoodQueryV1.Order(plan.orderId, limit = 8)))))
        val food = current.food
        check(food.unavailable == null && food.nextAfter == null && food.orders.isEmpty() && food.tasks.isEmpty() && current.delivery == null)
        val detail = checkNotNull(food.detail)
        val summary = detail.summary
        check(summary.orderId == plan.orderId && summary.ownerId == client.original.material.coordinates.firstActorId && summary.activity == plan.activity &&
            summary.service == plan.service && summary.portionCount == plan.totalQuantity.toInt() && summary.handoffId == null &&
            summary.intendedRecipientId == null && summary.financialCollectionId == null && !summary.workClosed &&
            detail.collectionState == "UNPAID_NO_COLLECTION" && detail.financialHandoff == null && !detail.chargeResolutionRequired &&
            detail.originalQuotedTotal == plan.payable && current.currentUnpaidPayable == plan.payable && current.currentUnpaidPayable == plan.outstanding)
        check(detail.portions.map { it.portionId } == plan.portions.map { it.id } && detail.portions.sumOf { it.quantity.scaled } == plan.totalQuantity &&
            detail.portions.map { it.portionId }.distinct().size == plan.totalQuantity.toInt() &&
            detail.portions.map { it.commercialLineId }.toSet() == plan.lines.map { it.anchor }.toSet())
        for (input in plan.portions) {
            val row = detail.portions.single { it.portionId == input.id }
            val preparation = row.preparation
            check(row.lineId == input.group && row.quantity == plan.quantity(input.product, input.quantity) && row.modifierIds.isEmpty() &&
                row.itemNote.isEmpty() && row.seatNote.isEmpty() && preparation.portionId == input.id && preparation.status == input.preparation &&
                preparation.fulfillment == input.fulfillment && preparation.tasks.size == input.tasks && preparation.cancellationReasonId == null &&
                preparation.cancellationRequestedAt == null && preparation.pickedUpAt == null)
            if (input.tasks == 0) {
                check(preparation.issueId == null && preparation.reservationId == null && preparation.issueRevision == null &&
                    preparation.sentAt == null && preparation.servedAt == null && preparation.tasks.isEmpty())
            } else {
                check(preparation.issueId != null && preparation.reservationId != null && preparation.issueRevision != null &&
                    preparation.sentAt == originals.single { it.symbol == "SEED_SEND" }.result.recordedAt)
                val task = preparation.tasks.single()
                val status = when (input.preparation) {
                    FoodPreparationStatus.QUEUED -> FoodStationStatus.QUEUED
                    FoodPreparationStatus.PREPARING -> FoodStationStatus.STARTED
                    FoodPreparationStatus.READY -> FoodStationStatus.READY
                    else -> error("The unchanged issued baseline state is required")
                }
                check(task.stationId == plan.stationId && task.deliveryMode == FoodStationDeliveryMode.DISPLAY && task.status == status &&
                    task.cancellationConfirmationId == null && task.cancellationConfirmedBy == null && task.cancellationConfirmedAt == null && task.returnDisposition == null)
                val started = originals.singleOrNull { it.symbol == foodColdTransitionSymbol("START", input.id) }?.result?.recordedAt
                val ready = originals.singleOrNull { it.symbol == foodColdTransitionSymbol("READY", input.id) }?.result?.recordedAt
                val served = originals.singleOrNull { it.symbol == foodColdTransitionSymbol("SERVE", input.id) }?.result?.recordedAt
                check(task.startedAt == started && task.readyAt == ready && preparation.servedAt == served &&
                    (started != null) == (input.preparation != FoodPreparationStatus.QUEUED) &&
                    (ready != null) == (input.preparation == FoodPreparationStatus.READY) && (served != null) == (input.fulfillment == FoodFulfillmentStatus.SERVED))
            }
        }
        val issues = detail.portions.mapNotNull { it.preparation.issueId }
        val reservations = detail.portions.mapNotNull { it.preparation.reservationId }
        val tasks = detail.portions.flatMap { it.preparation.tasks }
        check(issues.size == plan.issuedPortions && issues.distinct().size == plan.issuedPortions &&
            reservations.size == plan.issuedPortions && reservations.distinct().size == plan.issuedPortions &&
            tasks.size == plan.taskCount && tasks.map { it.taskId }.distinct().size == plan.taskCount)
        val own = LocalFoodReadCodecV1.result(reads.add("OWNER_LIST", client.read(FoodColdActor.OWNER, LocalFoodReadCodecV1.read(LocalFoodQueryV1.Own()))))
        check(own.unavailable == null && own.detail == null && own.tasks.isEmpty() && own.nextAfter == null && own.orders == listOf(summary))
        val assignment = LocalFoodTableAssignmentReadCodecV1.decodeResult(reads.add("TABLE_ASSIGNMENT", client.read(FoodColdActor.OWNER,
            LocalFoodTableAssignmentReadCodecV1.read(LocalFoodTableAssignmentQueryV1(plan.orderId)))))
        check(assignment.orderId == plan.orderId && assignment.ownerId == summary.ownerId && assignment.orderRevision == summary.revision &&
            assignment.activity == plan.activity && assignment.channel == LocalSalesChannel.DINE_IN && !assignment.workClosed &&
            !assignment.handoffPending && assignment.assignment == seed.assignment)
        val station = LocalFoodReadCodecV1.result(reads.add("KDS_ACTIVE_STATION", client.read(FoodColdActor.KDS,
            LocalFoodReadCodecV1.read(LocalFoodQueryV1.Station(plan.stationId)))))
        val active = detail.portions.filter { it.preparation.tasks.isNotEmpty() && it.preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER }
        check(station.unavailable == null && station.nextAfter == null && station.orders.isEmpty() && station.detail == null &&
            station.tasks.size == active.size && active.size == if (plan.profile == "restaurant") 2 else 4)
        check(station.tasks.map { it.portionId }.toSet() == active.map { it.portionId }.toSet())
        for (row in detail.portions.filter { it.preparation.tasks.isNotEmpty() }) {
            val task = row.preparation.tasks.single()
            val direct = LocalFoodReadCodecV1.result(reads.add("KDS_TASK/${task.taskId.value}", client.read(FoodColdActor.KDS,
                LocalFoodReadCodecV1.read(LocalFoodQueryV1.Task(plan.stationId, task.taskId)))))
            check(direct.unavailable == null && direct.nextAfter == null && direct.orders.isEmpty() && direct.detail == null)
            if (row.preparation.fulfillment == FoodFulfillmentStatus.SERVED) check(direct.tasks.isEmpty())
            else {
                val expected = station.tasks.single { it.taskId == task.taskId }
                check(direct.tasks == listOf(expected) && expected.orderId == plan.orderId && expected.orderRevision == summary.revision &&
                    expected.portionId == row.portionId && expected.portionRevision == row.preparation.revision && expected.stationId == plan.stationId &&
                    expected.quantity == row.quantity && expected.itemNote == row.itemNote && expected.seatNote == row.seatNote &&
                    expected.serviceReference == plan.service.reference && expected.taskStatus == task.status &&
                    expected.preparationStatus == row.preparation.status && expected.deliveryMode == FoodStationDeliveryMode.DISPLAY &&
                    expected.deliveryEvidence == "DURABLE_INTENT_NOT_PHYSICAL_ACK")
            }
        }
        val unsent = plan.portions.count { it.preparation == FoodPreparationStatus.NOT_SENT }
        val kitchen = checkNotNull(current.kitchen)
        check(kitchen.orderId == plan.orderId && kitchen.orderStorageRevision == summary.revision && kitchen.unsentPortionCount == unsent &&
            (unsent != 0 || kitchen.status == LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK))
        for (input in plan.stocks) requireHistory(configuration.stock.payloads.getValue("HISTORY/${input.role}"), detail, plan, seed, originals, input)
        check(configuration.sources.branchRevision == reads.branchRevision && configuration.stock.branchRevision == reads.branchRevision)
        if (label == "PRE_CLOSE_LITERAL_SEED") {
            evidence.bind("${plan.profile}_r0", summary.revision.value.toString())
            evidence.bind("ACTUAL_PRIOR_PORTION_TASK_ISSUE_RESERVATION_IDENTITIES", JSONArray(detail.portions.map { row -> JSONObject()
                .put("portionId", row.portionId.value).put("explicitGroupKey", checkNotNull(row.lineId).value)
                .put("preparationRevision", row.preparation.revision.value.toString()).put("issueId", row.preparation.issueId?.value ?: JSONObject.NULL)
                .put("reservationId", row.preparation.reservationId?.value ?: JSONObject.NULL)
                .put("taskIds", JSONArray(row.preparation.tasks.map { it.taskId.value })) }))
        }
        evidence.event(label, JSONObject().put("V3CurrentPayableMinor", checkNotNull(current.currentUnpaidPayable).minor.toString())
            .put("outstandingMinor", plan.outstanding.minor.toString()).put("quantity", plan.totalQuantity.toString())
            .put("commercialGroups", plan.groupCount).put("historicalRequiredTasks", tasks.size).put("activeKDSTasks", station.tasks.size)
            .put("originalIssues", issues.size).put("ownerId", summary.ownerId.value).put("orderRevision", summary.revision.value.toString())
            .put("branchRevision", reads.branchRevision.value.toString()).put("collection", "UNPAID_NO_COLLECTION")
            .put("financialEffects", "ZERO; independently checked by complete native census"))
        return FoodColdProtectedSeedCut(configuration, reads, current)
    }

    private fun requireHistory(bytes: StoredBytes, detail: LocalFoodOrderDetailV1, plan: NativeFoodColdInputPlan,
        seed: FoodColdSeedBindings, originals: List<FoodColdSavedOriginal>, input: FoodColdStockInput) {
        val history = LocalStockRelationalQueriesV2.decodeHistory(bytes)
        val issued = plan.portions.filter { it.tasks == 1 && plan.components.getValue(it.product).any { component -> component.role == input.role } }
        check(history.skuId == plan.skuId(input.role) && history.warehouseId == plan.warehouseId && history.itemRevision != null &&
            history.nextOrdinal == null && history.rows.size == issued.size + 1)
        val opening = history.rows.first()
        check(opening.movement == "OPENING" && opening.sourceId == plan.stockSourceId(input.role) && opening.quantityDelta == plan.quantity(input.role, input.initial) &&
            opening.resultingQuantity == plan.quantity(input.role, input.initial) && opening.recordedAt == seed.openingRecordedAt)
        val issues = history.rows.drop(1)
        check(issues.map { it.sourceId }.toSet() == issued.map { portion -> checkNotNull(detail.portions.single { it.portionId == portion.id }.preparation.issueId) }.toSet())
        for (portion in issued) {
            val issueId = checkNotNull(detail.portions.single { it.portionId == portion.id }.preparation.issueId)
            val movement = issues.single { it.sourceId == issueId }
            val component = plan.components.getValue(portion.product).single { it.role == input.role }
            check(movement.movement == "ISSUE" && movement.quantityDelta == plan.quantity(input.role, -component.quantity) &&
                movement.recordedAt == originals.single { it.symbol == "SEED_SEND" }.result.recordedAt)
        }
        check(issues.sumOf { -it.quantityDelta.scaled } == input.issued && history.rows.last().resultingQuantity == plan.quantity(input.role, input.available) &&
            history.rows.all { it.originalValueDelta == plan.zero && it.carryingValueDelta == plan.zero && it.resultingValue == plan.zero } &&
            history.rows.none { it.movement == "RESTORE" })
    }
}

internal class FoodColdProtectedSeedCut(val configuration: FoodColdConfigurationCut, val reads: FoodColdReadCut,
    val current: LocalFoodCheckKitchenResultV3) {
    fun requireSame(after: FoodColdProtectedSeedCut) {
        configuration.requireSame(after.configuration)
        reads.requireSame(after.reads)
        check(current == after.current)
    }
}

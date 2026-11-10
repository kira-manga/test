package com.ukkera.pos.android.independent.coldoriginalstartv1

import com.ukkera.pos.android.independent.nativefixture.nativeFixtureSecret
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import org.json.JSONObject

/** Genuine construction pattern; no Room rows, signed SKU, receipt, revisions or cash
 * projections are seeded. Requests are retained by the client before every actual dispatch. */
internal class NativeColdOriginalPreparation(private val client: NativeColdOriginalClient,
    private val plan: NativeColdOriginalFixturePlan,
    private val evidence: NativeColdOriginalEvidence) {
    val reads = ColdOriginalProjectionReader(client, plan, evidence)

    suspend fun prepareC0(headSecret: CharArray): ColdBusinessCut {
        val company = client.original.material.coordinates.scope.company()
        val definition = plan.requestedSku(company)
        val publications = checkNotNull(client.publications) { "Genuine native company catalog publication is unavailable" }
        val canonical = LocalConfigurationCodec.canonicalPublication(definition)
        val proposal = try { CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
            plan.publicationId, plan.skuId, canonical) } finally { canonical.fill(0) }
        val action = nativeFixtureValue(publications.prepare(proposal))
        val output = nativeFixtureSecret(headSecret) { nativeFixtureValue(publications.approve(action, plan.headLogin, it)) }
        coldPublicationDiagnostic("output-returned")
        val actualSku = output.readNativeCompanySku()
        coldPublicationDiagnostic("output-decoded")
        check(actualSku.definition == definition && output.originalActionId == action.originalActionId &&
            output.proposal.contentDigest == proposal.contentDigest && output.signingKeyId == action.signingKeyId)
        coldPublicationDiagnostic("output-matched")
        val signedCanonical = LocalConfigurationCodec.canonicalPublication(definition)
        try { check(NativeCompanyPublicationSignatureVerifier(publications.trust).verify(company,
            output.signingKeyId, signedCanonical, output.signatureHex)) } finally { signedCanonical.fill(0) }
        coldPublicationDiagnostic("signature-accepted")
        evidence.bind("GENUINE_SKU_PUBLICATION", JSONObject().put("publicationId", output.proposal.publicationId.value)
            .put("originalActionId", output.originalActionId.value).put("contentDigest", output.proposal.contentDigest.value))
        configuration(ColdPreparationCommand.INSTALL_SKU, LocalConfigurationRequestV1.InstallCompanySku(actualSku), plan.skuId)
        requireAbsentConfiguration(LocalConfigurationProfile.WAREHOUSE, plan.warehouseId)
        configuration(ColdPreparationCommand.CONFIGURE_WAREHOUSE,
            LocalConfigurationRequestV1.ConfigureWarehouse(null, plan.warehouse), plan.warehouseId)
        requireAbsentConfiguration(LocalConfigurationProfile.SALES_ROUTE, plan.routeId)
        configuration(ColdPreparationCommand.CONFIGURE_ROUTE,
            LocalConfigurationRequestV1.ConfigureSalesRoute(null, plan.route), plan.routeId)
        requireAbsentConfiguration(LocalConfigurationProfile.BASIS, plan.openingApprovalId)
        configuration(ColdPreparationCommand.APPROVE_OPENING,
            LocalConfigurationRequestV1.ApproveReceiving(plan.openingApproval), plan.openingApprovalId)

        val init = LocalStockRelationalCommandsV2.initializeFresh(plan.operation(ColdPreparationCommand.INITIALIZE_STOCK),
            plan.stockInitializationId, plan.reasonCode)
        val initialized = client.dispatch(ColdPreparationCommand.INITIALIZE_STOCK.name, init)
        val initialization = LocalStockRelationalCommandsV2.decodeInitializationResult(init.payload, initialized.result)
        check(initialization.initializationId == plan.stockInitializationId && initialization.freshEmpty)
        val approval = configurationPage(LocalConfigurationProfile.BASIS, plan.openingApprovalId)
        check((approval.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == plan.openingApproval)
        val quantityBefore = LocalStockQueries.decodeQuantities(client.read(LocalStockCommands.quantities(plan.skuId)).payload)
        val costBefore = LocalStockQueries.decodeCosts(client.read(LocalStockCommands.costs(plan.skuId)).payload)
        check(quantityBefore.rows.isEmpty() && quantityBefore.itemStorageRevision == null && quantityBefore.nextAfter == null &&
            quantityBefore.unitId == null && quantityBefore.quantityScale == null && costBefore.rows.isEmpty() &&
            costBefore.itemStorageRevision == null && costBefore.nextAfter == null)
        val opening = LocalGoodsReceiptRequest(plan.openingReceiptId, plan.openingApprovalId,
            approval.items.single().revision, listOf(LocalStockItemVersion(plan.skuId, null)),
            listOf(LocalGoodsReceiptLine(plan.openingReceiptLineId, plan.openingAllocationId, plan.openingStockSourceId,
                plan.skuId, plan.warehouseId, plan.initialQuantity, conversion = null, lotId = null, expiresAt = null,
                disposition = LocalReceiptDisposition.RELEASED, originalValue = plan.inventoryValue)), plan.reasonCode)
        val stockCommand = LocalStockRelationalCommandsV2.opening(plan.operation(ColdPreparationCommand.OPENING_STOCK), opening)
        val stockReply = client.dispatch(ColdPreparationCommand.OPENING_STOCK.name, stockCommand)
        val stockResult = LocalStockRelationalCommandsV2.decodeResult(stockCommand.payload, stockReply.result)
        check(stockResult.kind == LocalStockResultKind.OPENED && stockResult.documentId == plan.openingReceiptId && stockResult.failure == null)
        val actualOpening = checkNotNull(LocalStockQueries.decodeReceipt(client.read(LocalStockCommands.receiptCosts(plan.openingReceiptId)).payload))
        check(actualOpening.receiptId == plan.openingReceiptId && actualOpening.basisKind == LocalStockBasisKind.OPENING &&
            actualOpening.basisId == plan.openingApprovalId && actualOpening.supplierId == null && actualOpening.costsIncluded &&
            actualOpening.recordedBy == client.original.material.coordinates.firstActorId && actualOpening.lines.size == 1)
        val openingLine = actualOpening.lines.single()
        check(openingLine.lineId == plan.openingReceiptLineId && openingLine.allocationId == plan.openingAllocationId &&
            openingLine.sourceId == plan.openingStockSourceId && openingLine.skuId == plan.skuId &&
            openingLine.warehouseId == plan.warehouseId && openingLine.recordedQuantity == plan.initialQuantity &&
            openingLine.baseQuantity == plan.initialQuantity && openingLine.originalValue == plan.inventoryValue &&
            openingLine.disposition == LocalReceiptDisposition.RELEASED && openingLine.conversion == null &&
            openingLine.lotId == null && openingLine.expiresAt == null && openingLine.serialId == null)

        val foundingActor = client.original.material.coordinates.firstActorId
        cash(ColdPreparationCommand.CONFIGURE_CUSTODIAN, LocalCashRequestV1.ConfigureCustodian(foundingActor),
            LocalSalesAction.CUSTODIAN_CONFIGURED, foundingActor)
        cash(ColdPreparationCommand.CONFIGURE_DRAWER,
            LocalCashRequestV1.ConfigureDrawer(plan.drawerId, "Synthetic founding drawer", Currency.EGP),
            LocalSalesAction.DRAWER_CONFIGURED, plan.drawerId)
        val own = reads.ownCash()
        check(own.custodianId == foundingActor && own.session == null && own.unsettledOrderIds.isEmpty() && own.unresolvedReturnIds.isEmpty())
        val drawers = LocalCashReadCodec.decode(client.read(LocalCashReadCodec.request(LocalCashQueryV1.Drawers())).payload)
            as? LocalCashReadResultV1.Drawers ?: error("Actual drawer choices are unavailable")
        check(drawers.nextAfter == null && drawers.drawers.size == 1)
        val drawer = drawers.drawers.single()
        check(drawer.id == plan.drawerId && drawer.currency == Currency.EGP && !drawer.assigned && !drawer.awaitingClosingReceipt)
        cash(ColdPreparationCommand.OPEN_DRAWER, LocalCashRequestV1.Open(plan.cashSessionId, plan.drawerId,
            drawer.revision, own.assignmentRevision, plan.businessDate, plan.cashOpeningSourceId, plan.openingCash,
            plan.openingCashEvidence), LocalSalesAction.CASH_OPENED, plan.cashSessionId)

        val catalog = configurationPage(LocalConfigurationProfile.SKU, plan.skuId)
        val sku = catalog.detail as? LocalConfigurationDetailV1.Sku ?: error("Actual installed SKU projection is missing")
        check(sku.skuId == plan.skuId && sku.productId == plan.productId && sku.baseUnitId == plan.baseUnitId &&
            sku.quantityScale == 0 && sku.tracking == LocalTrackingV1.QUANTITY && sku.fulfillment == LocalFulfillmentV1.RETAIL &&
            sku.canSell && sku.active && sku.prices == definition.prices)
        check((configurationPage(LocalConfigurationProfile.SALES_ROUTE, plan.routeId).detail as? LocalConfigurationDetailV1.SalesRoute)?.value == plan.route)
        val emptyOrder = LocalOrderReadCodecV2.decode(client.read(LocalOrderReadCodecV2.request(LocalOrderQueryV1.Order(plan.orderId))).payload)
        check(emptyOrder.orders.isEmpty() && emptyOrder.nextAfter == null)
        val selection = LocalOrderSelectionV1(plan.activity, plan.channel, Currency.EGP, checkNotNull(catalog.catalogRevision),
            listOf(LocalOrderLineV1(plan.orderLineId, plan.orderPortionId, plan.skuId, plan.orderQuantity)), serviceContextId = null)
        val created = client.dispatch(ColdPreparationCommand.CREATE_ORDER.name, LocalCollectionStockCodecV2.command(
            plan.operation(ColdPreparationCommand.CREATE_ORDER), LocalCollectionStockRequestV2.CreateOrder(plan.orderId, selection)))
        requireSalesResult(created, LocalSalesAction.ORDER_CREATED, plan.orderId)
        return reads.checkpoint("C0")
    }

    suspend fun beginAndPrepareStart(c0: ColdBusinessCut): MutationRequest {
        val begin = LocalCollectionStockRequestV2.Begin(plan.orderId, c0.order.order.revision, plan.cashSessionId,
            c0.cash.revision, plan.collectionId, plan.reservationId)
        val reply = client.dispatch(ColdPreparationCommand.BEGIN_COLLECTION.name, LocalCollectionStockCodecV2.command(
            plan.operation(ColdPreparationCommand.BEGIN_COLLECTION), begin))
        requireSalesResult(reply, LocalSalesAction.COLLECTION_LOCKED, plan.collectionId)
        val collection = reads.collection()
        val stock = reads.normalizedStockCut()
        check(collection.collectionId == plan.collectionId && collection.orderId == plan.orderId &&
            collection.sessionId == plan.cashSessionId && collection.attempts.isEmpty() && collection.collected.minor == 0L &&
            collection.remainingPayable == plan.payable && stock.status == "RESERVED" && stock.holdCount == 0L && stock.holds.isEmpty() &&
            stock.items.size == 1 && stock.items.single().skuId == plan.skuId)
        return MutationRequest(plan.startOperationId, LocalFinancialJournalProfileV3.COLLECTION_COMMAND,
            LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.Start(plan.collectionId, collection.collectionRevision,
                plan.cashSessionId, collection.sessionRevision, stock.reservationRevision, stock.items, plan.attemptId,
                LocalTenderSelectionV1.Cash, plan.payable, payerReference = null, plan.recordingOperationId,
                plan.resolutionOperationId, plan.masterAllocationId, plan.clientAllocationId)))
    }

    suspend fun applyAndFinalize(): ColdBusinessCut {
        val applied = client.dispatch("ORIGINAL_APPLICATION", MutationRequest(plan.applicationOperationId,
            LocalFinancialJournalProfileV3.COLLECTION_COMMAND, LocalCollectionCommandCodecV3.encode(
                LocalCollectionRequestV3.ApplyObservation(plan.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL))))
        val application = LocalFinancialResultCodecV3.decode(applied.result) as? LocalFinancialResultV3.Applied
            ?: error("Actual original observation did not financially apply")
        check(application.phase == LocalJournalFactPhaseV3.ORIGINAL && application.target.recordingOperationId == plan.recordingOperationId &&
            application.target.intentOperationId == plan.startOperationId && application.applicationOperationId == plan.applicationOperationId &&
            application.action == LocalSalesAction.PAYMENT_RECORDED)
        val collection = reads.collection()
        val stock = reads.normalizedStockCut()
        check(collection.collected == plan.payable && collection.remainingPayable == Money(Currency.EGP, 0) &&
            collection.status == "COVERED" && collection.saleId == null && stock.status == "RESERVED")
        val finalize = LocalCollectionStockRequestV2.Finalize(plan.orderId, collection.orderRevision, plan.collectionId,
            collection.collectionRevision, stock.reservationRevision, plan.saleId, plan.issueId)
        val finalized = client.dispatch("ORIGINAL_FINALIZATION", LocalCollectionStockCodecV2.command(plan.finalizeOperationId, finalize))
        requireSalesResult(finalized, LocalSalesAction.SALE_FINALIZED, plan.saleId)
        return reads.checkpoint("C3")
    }

    private suspend fun configuration(step: ColdPreparationCommand, request: LocalConfigurationRequestV1, id: DomainId) {
        val reply = client.dispatch(step.name, MutationRequest(plan.operation(step), LocalConfigurationProfile.COMMAND,
            LocalConfigurationCodec.encode(request)))
        val result = LocalConfigurationCodec.decodeResult(reply.result)
        check(result.code == "configuration.applied" && result.id == id && result.revision != null)
    }
    private suspend fun cash(step: ColdPreparationCommand, request: LocalCashRequestV1, action: LocalSalesAction, id: DomainId) {
        requireSalesResult(client.dispatch(step.name, MutationRequest(plan.operation(step), LocalSalesStoreProfile.CASH_COMMAND,
            LocalCashCommandCodec.encode(request))), action, id)
    }
    private fun requireSalesResult(reply: MutationReply.Recorded, action: LocalSalesAction, id: DomainId) {
        val result = LocalSalesResultCodec.decode(reply.result) as? LocalSalesResultV1.Applied
            ?: error("The real normalized/cash operation did not return Applied")
        check(result.action == action && result.documentId == id)
    }
    private suspend fun configurationPage(kind: String, id: DomainId): LocalConfigurationPageV1 {
        val page = LocalConfigurationCodec.decodePage(client.read(ReadRequest(LocalConfigurationProfile.QUERY,
            LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id = id)))).payload)
        check(page.kind == kind && page.nextAfter == null && page.items.size <= 1 && page.items.all { it.id == id })
        return page
    }
    private suspend fun requireAbsentConfiguration(kind: String, id: DomainId) {
        val actual = configurationPage(kind, id)
        check(actual.items.isEmpty() && actual.detail == null)
    }
}

/** Supported protected projections bind the unchanged economic checkpoints. Absent result
 * projections are not promoted to complete native-history absence or receipt/count proofs. */
internal class ColdOriginalProjectionReader(private val client: NativeColdOriginalClient,
    private val plan: NativeColdOriginalFixturePlan, private val evidence: NativeColdOriginalEvidence) {
    suspend fun ownCash(): LocalCashReadResultV1.OwnCash = LocalCashReadCodec.decode(client.read(
        LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)).payload) as? LocalCashReadResultV1.OwnCash
        ?: error("The actual own cash projection is unavailable")
    suspend fun collection(): LocalCollectionViewV1 = (LocalCollectionReadCodec.decode(client.read(
        LocalCollectionReadCodec.request(LocalCollectionQueryV1.Collection(plan.collectionId))).payload)
        as? LocalCollectionReadResultV1.Collection)?.value ?: error("The actual original collection is unavailable")
    suspend fun normalizedStockCut(): LocalCollectionStockReadResultV2.NormalizedCut = LocalCollectionStockReadCodecV2.decode(client.read(
        LocalCollectionStockReadCodecV2.request(LocalCollectionStockQueryV2(plan.collectionId))).payload)
        as? LocalCollectionStockReadResultV2.NormalizedCut ?: error("Actual normalized original reservation is unavailable")

    suspend fun checkpoint(name: String): ColdBusinessCut {
        check(name in setOf("C0", "C1", "C2-SUCCESS", "C3"))
        val paid = name == "C3"
        val expected = plan.businessCheckpoint(name)
        val revisions = mutableSetOf<Revision>()
        suspend fun read(request: ReadRequest): StoredBytes = client.read(request).let { revisions += it.branchRevision; it.payload }
        val orders = LocalOrderReadCodecV2.decode(read(LocalOrderReadCodecV2.request(LocalOrderQueryV1.Order(plan.orderId))))
        check(orders.nextAfter == null && orders.orders.size == 1)
        val order = orders.orders.single()
        val own = LocalCashReadCodec.decode(read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash))) as? LocalCashReadResultV1.OwnCash
            ?: error("Own drawer checkpoint is unavailable")
        val cash = checkNotNull(own.session)
        val quantities = LocalStockQueries.decodeQuantities(read(LocalStockCommands.quantities(plan.skuId)))
        // Actual cost-read authorization is separate from quantity/cash/paid-sale permissions.
        val costs = LocalStockQueries.decodeCosts(read(LocalStockCommands.costs(plan.skuId)))
        // The genuine registered history read requires both quantity and cost
        // permissions. Decode only its actual protected ReadReply, not raw rows.
        val costHistory = LocalStockRelationalQueriesV2.decodeHistory(read(
            LocalStockRelationalQueriesV2.history(plan.skuId, plan.warehouseId, afterOrdinal = 0)))
        check(quantities.nextAfter == null && costs.nextAfter == null && quantities.rows.size == 1 && costs.rows.size == 1 &&
            quantities.itemStorageRevision != null && quantities.itemStorageRevision == costs.itemStorageRevision)
        check(costHistory.skuId == plan.skuId && costHistory.warehouseId == plan.warehouseId &&
            costHistory.itemRevision == quantities.itemStorageRevision && costHistory.nextOrdinal == null) {
            "The complete protected original-cost history is unavailable at this stock cut"
        }
        val quantity = quantities.rows.single()
        val cost = costs.rows.single()
        val collectionProjection = LocalCollectionReadCodec.decode(read(LocalCollectionReadCodec.request(
            LocalCollectionQueryV1.Collection(plan.collectionId))))
        val collection = (collectionProjection as? LocalCollectionReadResultV1.Collection)?.value
        val stockProjection = LocalCollectionStockReadCodecV2.decode(read(LocalCollectionStockReadCodecV2.request(
            LocalCollectionStockQueryV2(plan.collectionId))))
        val reservation = stockProjection as? LocalCollectionStockReadResultV2.NormalizedCut
        val saleProjection = LocalCollectionReadCodec.decode(read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.PaidSale(plan.saleId))))
        val sale = (saleProjection as? LocalCollectionReadResultV1.PaidSale)?.value
        val zeroQuantity = Quantity(plan.baseUnitId, 0, 0)
        val zeroMoney = Money(Currency.EGP, 0)
        val actor = client.original.material.coordinates.firstActorId
        check(order.stockProfile == LocalOrderStockProfileV2.NORMALIZED && order.order.orderId == plan.orderId && order.order.ownerId == actor &&
            order.order.selection.activity == plan.activity && order.order.selection.channel == plan.channel &&
            order.order.selection.lines == listOf(LocalOrderLineV1(plan.orderLineId, plan.orderPortionId, plan.skuId, plan.orderQuantity)) &&
            order.order.selection.serviceContextId == null &&
            order.order.selection.currency == Currency.EGP && order.order.beforeDiscount == plan.payable &&
            order.order.payable == plan.payable && order.order.discount == zeroMoney && order.order.handoffId == null &&
            order.order.handoffRecipientId == null && order.order.lines.single().portionId == plan.orderPortionId &&
            order.order.lines.single().quantity == plan.orderQuantity && order.order.lines.single().payable == plan.payable)
        requireZeroExtras(order.order.components)
        check(own.custodianId == actor && cash.id == plan.cashSessionId && cash.drawerId == plan.drawerId && cash.custodianId == actor &&
            cash.businessDate == plan.businessDate && cash.status == "OPEN" && cash.opening == plan.openingCash &&
            cash.expectedCash.minor == expected.getString("drawer_expected_cash_minor").toLong() &&
            cash.pendingTransferIds.isEmpty() && own.unresolvedReturnIds.isEmpty() &&
            listOf(cash.reservedCashOut, cash.refunds, cash.payIns, cash.payOuts, cash.safeDrops,
                cash.externalCollections, cash.externalRefunds).all { it == zeroMoney } &&
            cash.received == (if (paid) plan.received else zeroMoney) && cash.change == (if (paid) plan.change else zeroMoney))
        check(quantities.skuId == plan.skuId && quantities.unitId == plan.baseUnitId && quantities.quantityScale == 0 &&
            quantity.position.skuId == plan.skuId && quantity.position.warehouseId == plan.warehouseId &&
            quantity.position.supplierId == null && quantity.position.custody == StockCustody.RELEASED &&
            quantity.position.lotId == null && quantity.position.serialId == null && quantity.safety == StockSafety.CLEAR &&
            quantity.expiresAt == null && !quantity.expiredAtRead && quantity.heldQuantity == zeroQuantity &&
            quantity.heldShortageQuantity == zeroQuantity && quantity.onHand == Quantity(plan.baseUnitId, expected.getString("stock_on_hand").toLong(), 0) &&
            quantity.reserved == Quantity(plan.baseUnitId, expected.getString("stock_reserved").toLong(), 0) &&
            quantity.available == Quantity(plan.baseUnitId, expected.getString("stock_available").toLong(), 0))
        check(costs.skuId == plan.skuId && cost.warehouseId == plan.warehouseId && cost.quantity == quantity.onHand &&
            cost.carryingValue == Money(Currency.EGP, expected.getString("inventory_carrying_value_minor").toLong()))
        val expectedOriginalSaleCost = Money(Currency.EGP, expected.getString("original_sale_cost_minor").toLong())
        val issueRows = costHistory.rows.filter { it.movement == "ISSUE" }
        val originalSourceRows = costHistory.rows.filter { it.sourceId == plan.issueId }
        val originalIssue = if (paid) {
            check(issueRows.size == 1 && issueRows == originalSourceRows) {
                "Original sale cost is not the one exact finalized stock-issue source"
            }
            issueRows.single().also {
                // ISSUE is an outgoing original value delta. Frozen sale cost is
                // positive 4,000 minor: compare -4,000 from the actual original
                // event, never opening-minus-current inventory or PaidSale totals.
                check(it.sourceId == plan.issueId && it.quantityDelta == -plan.orderQuantity &&
                    it.originalValueDelta == -expectedOriginalSaleCost &&
                    it.recordedAt == evidence.savedOriginal("ORIGINAL_FINALIZATION").result.recordedAt)
            }
        } else {
            // Complete authorized value history has no original issue yet. This
            // is not a physical-action, payment-receipt or invocation count claim.
            check(issueRows.isEmpty() && originalSourceRows.isEmpty() && expectedOriginalSaleCost == zeroMoney)
            null
        }
        val observedOriginalSaleCost = originalIssue?.originalValueDelta?.let { -it } ?: zeroMoney
        if (name == "C0") {
            check(collectionProjection == LocalCollectionReadResultV1.NotAvailable && stockProjection == LocalCollectionStockReadResultV2.NotAvailable &&
                order.order.collectionId == null && order.order.saleId == null && cash.pendingAttemptIds.isEmpty())
        } else {
            check(collection != null && reservation != null)
            check(collection.collectionId == plan.collectionId && collection.orderId == plan.orderId && collection.sessionId == plan.cashSessionId &&
                collection.reservationId == plan.reservationId && order.order.collectionId == plan.collectionId && collection.payable == plan.payable &&
                collection.collected == Money(Currency.EGP, expected.getString("paid_minor").toLong()) &&
                collection.remainingPayable == Money(Currency.EGP, expected.getString("payable_remaining_minor").toLong()) &&
                collection.attempts.size == 1 && collection.attempts.single().attemptId == plan.attemptId &&
                collection.attempts.single().intentOperationId == plan.startOperationId && collection.attempts.single().amount == plan.payable &&
                reservation.collectionId == plan.collectionId && reservation.reservationId == plan.reservationId && reservation.holdCount == 0L &&
                reservation.holds.isEmpty() && reservation.nextAfterHold == null &&
                reservation.status == (if (paid) "ISSUED" else "RESERVED"))
            val attempt = collection.attempts.single()
            check(attempt.cashReceived == (if (paid) plan.received else null) && attempt.changeGiven == (if (paid) plan.change else null) &&
                attempt.completedRecordingOperationId == (if (paid) plan.recordingOperationId else null) &&
                attempt.tender == LocalTenderViewV1("CASH"))
        }
        if (paid) {
            check(sale != null && sale.saleId == plan.saleId && order.order.saleId == plan.saleId && collection?.saleId == plan.saleId &&
                sale.beforeDiscount == plan.payable && sale.payable == plan.payable && sale.discount == zeroMoney &&
                sale.lines.single().portionId == plan.orderPortionId &&
                sale.lines.single().quantity == plan.orderQuantity && sale.lines.single().payable == plan.payable &&
                sale.tenders.single().amount == plan.payable && sale.tenders.single().originalPaymentId == plan.attemptId &&
                own.unsettledOrderIds.isEmpty() && cash.pendingAttemptIds.isEmpty())
            requireZeroExtras(sale.components)
            val fact = LocalFinancialReadCodecV3.decode(read(LocalFinancialReadCodecV3.request(
                LocalFinancialQueryV3.Fact(plan.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL))))
                as? LocalFinancialReadResultV3.Fact ?: error("C3 original fact is unavailable")
            client.requireOriginalFact(fact)
            check(fact.summary.applicationState == LocalJournalApplicationStateV3.APPLIED && fact.summary.pendingReason == null &&
                fact.summary.lastApplicationOperationId == plan.applicationOperationId && fact.summary.appliedAction == LocalSalesAction.PAYMENT_RECORDED)
        } else {
            check(saleProjection == LocalCollectionReadResultV1.NotAvailable && order.order.saleId == null && own.unsettledOrderIds == listOf(plan.orderId))
        }
        check(revisions.size == 1) { "Protected checkpoint projections belong to different authoritative business cuts" }
        evidence.event("${name}_SUPPORTED_PROTECTED_ECONOMICS_MATCH_FROZEN", JSONObject()
            .put("branchRevision", revisions.single().value).put("drawerMinor", cash.expectedCash.minor)
            .put("stockOnHand", quantity.onHand.scaled).put("stockReserved", quantity.reserved.scaled)
            .put("stockAvailable", quantity.available.scaled).put("carryingValueMinor", cost.carryingValue.minor)
            .put("paidMinor", collection?.collected?.minor ?: 0).put("remainingMinor", collection?.remainingPayable?.minor ?: plan.payable.minor)
            .put("costProjection", "SEPARATELY_AUTHORIZED_INVENTORY_COST_READ")
            .put("originalSaleCost", if (paid) "EXACT_PROTECTED_ORIGINAL_ISSUE_VALUE" else "NO_ORIGINAL_ISSUE_IN_COMPLETE_PROTECTED_HISTORY")
            .put("originalSaleCostProjection", "PROTECTED_STOCK_NORMALIZED_HISTORY_V2_ORIGINAL_VALUE_DELTA")
            .put("originalSaleCostMinor", observedOriginalSaleCost.minor)
            .put("originalSaleCostSourceId", originalIssue?.sourceId?.value ?: JSONObject.NULL)
            .put("originalSaleCostValueDeltaMinor", originalIssue?.originalValueDelta?.minor ?: 0L)
            .put("paymentReceipt", "UNBOUND")
            .put("countClaims", "NOT_INFERRED_FROM_READ_ABSENCE_OR_TEST_CALLS"))
        return ColdBusinessCut(order, cash, quantity, cost, checkNotNull(quantities.itemStorageRevision), collection, reservation, sale,
            costHistory.rows)
    }

    private fun requireZeroExtras(components: List<LocalOrderComponentV1>) {
        check(components.all { it.amount.currency == Currency.EGP })
        check(components.filter { it.kind in setOf(PriceComponentKind.ITEM_TAX.name, PriceComponentKind.CHARGE_NET.name,
            PriceComponentKind.CHARGE_TAX.name) }.all { it.amount.minor == 0L })
        check(components.filter { it.kind == PriceComponentKind.ITEM_NET.name }.sumOf { it.amount.minor } == plan.payable.minor)
    }
}

internal data class ColdBusinessCut(val order: LocalProfiledOrderV2, val cash: LocalCashSessionViewV1,
    val quantity: LocalStockQuantityRow, val cost: LocalStockCostRow, val stockItemRevision: Revision,
    val collection: LocalCollectionViewV1?, val reservation: LocalCollectionStockReadResultV2.NormalizedCut?,
    val sale: LocalPaidSaleViewV1?,
    // Whole protected history DATA, including the exact original issue/value/time,
    // participate in the existing C3 equality check across both cold reopens.
    val originalCostHistory: List<LocalStockHistoryRowV2>)

/** Fixed observations only; preserve the original assertions, fixtures and native budget. */
private fun coldPublicationDiagnostic(phase: String) {
    try { android.util.Log.i("UkkeraNativeOwner", "Cold executor publication: $phase") }
    catch (_: Throwable) { /* Diagnostics cannot change the original test outcome. */ }
}

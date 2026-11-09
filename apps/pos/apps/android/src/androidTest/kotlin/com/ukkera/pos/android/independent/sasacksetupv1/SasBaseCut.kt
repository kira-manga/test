package com.ukkera.pos.android.independent.sasacksetupv1

import com.ukkera.pos.android.independent.nativefixture.nativeFixtureSecret
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.catalog.store.LocalBranchActivityCodecV1
import com.ukkera.pos.catalog.store.LocalCompanyActivityPublicationCodecV1
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.catalog.store.LocalConfigurationResultV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalPublishedCompanyActivityProfileV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalReceivingApprovalV1
import com.ukkera.pos.catalog.store.LocalSalesRouteV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalWarehouseV1
import com.ukkera.pos.catalog.store.LocalApprovedStockLineV1
import com.ukkera.pos.catalog.store.LocalBasisKindV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProjectionV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointTargetV1
import com.ukkera.pos.catalog.store.setup.LocalSetupConfigurationRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupEnvironmentV1
import com.ukkera.pos.catalog.store.setup.LocalSetupFiscalIdentityV1
import com.ukkera.pos.catalog.store.setup.LocalSetupImportModeV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningChoiceV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningStockManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningStockRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1
import com.ukkera.pos.catalog.store.LocalActivateInitialBranchActivityV1
import com.ukkera.pos.catalog.store.readNativeCompanyActivityProfile
import com.ukkera.pos.catalog.store.readNativeCompanySku
import com.ukkera.pos.catalog.store.LocalBranchActivityProfileV1
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalGoodsReceiptLine
import com.ukkera.pos.inventory.store.LocalGoodsReceiptRequest
import com.ukkera.pos.inventory.store.LocalReceiptDisposition
import com.ukkera.pos.inventory.store.LocalStockCommands
import com.ukkera.pos.inventory.store.LocalStockItemVersion
import com.ukkera.pos.inventory.store.LocalStockQueries
import com.ukkera.pos.inventory.store.LocalStockResult
import com.ukkera.pos.inventory.store.LocalStockResultKind
import com.ukkera.pos.sales.store.CashNonSaleStoreProfile
import com.ukkera.pos.sales.store.LocalCashCommandCodec
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalCashQueryV1
import com.ukkera.pos.sales.store.LocalCashReadCodec
import com.ukkera.pos.sales.store.LocalCashReadResultV1
import com.ukkera.pos.sales.store.LocalCashRequestV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.sales.store.LocalSalesResultCodec
import com.ukkera.pos.sales.store.LocalSalesResultV1
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import org.json.JSONObject

internal const val SAS_SENTINEL_SKU_CODE = "SETUP-SYNTH-001"
internal const val SAS_SENTINEL_QUANTITY = 3L
internal const val SAS_SENTINEL_STOCK_VALUE_MINOR = 7500L
internal const val SAS_SENTINEL_FLOAT_MINOR = 50000L

internal data class SasBaseCut(
    val skuId: DomainId,
    val warehouseId: DomainId,
    val openingBasisId: DomainId,
    val openingReceiptId: DomainId,
    val openingStockSourceId: DomainId,
    val openingStockOperationId: OperationId,
    val cashDrawerId: DomainId,
    val cashSessionId: DomainId,
    val openingFloatSourceId: DomainId,
    val openingFloatOperationId: OperationId,
    val policy: com.ukkera.pos.security.ActivatedLocalPolicy,
    val projection: LocalSetupCheckpointProjectionV1,
) {
    fun facts(): JSONObject = JSONObject()
        .put("skuCode", SAS_SENTINEL_SKU_CODE).put("skuId", skuId.value)
        .put("quantity", SAS_SENTINEL_QUANTITY).put("stockValueMinor", SAS_SENTINEL_STOCK_VALUE_MINOR)
        .put("floatMinor", SAS_SENTINEL_FLOAT_MINOR)
        .put("openingStockSourceId", openingStockSourceId.value)
        .put("openingReceiptId", openingReceiptId.value)
        .put("openingStockOperationId", openingStockOperationId.value)
        .put("openingFloatSourceId", openingFloatSourceId.value)
        .put("cashSessionId", cashSessionId.value)
        .put("openingFloatOperationId", openingFloatOperationId.value)
        .put("checkpointConfiguration", projection.configuration?.operationId?.value ?: "ABSENT")
        .put("checkpointCatalogImport", projection.catalogImport?.operationId?.value ?: "ABSENT")
        .put("checkpointOpening", projection.opening?.operationId?.value ?: "ABSENT")
}

/**
 * Frozen BASE-CUT only. It creates the sentinel SKU, opening stock, opening float and the three
 * original-bound setup checkpoints through ordinary production commands, using only explicitly
 * selected setup/catalog/stock/cash/read actions. It performs no delivery ACK and no go-live.
 */
internal suspend fun SasFixture.settleBaseCut(): SasBaseCut {
    val coordinates = coordinates
    val scope = coordinates.scope
    val company = scope.company()
    val base = plan.firstAccount(coordinates).policy
    val installation = anchor.installationId

    val skuId = DomainId.random()
    val skuPublicationId = DomainId.random()
    val productId = DomainId.random()
    val baseUnitId = DomainId.random()
    val warehouseId = DomainId.random()
    val openingBasisId = DomainId.random()
    val openingAllocationId = DomainId.random()
    val openingReceiptId = DomainId.random()
    val openingStockSourceId = DomainId.random()
    val openingStockOperationId = OperationId.random()
    val cashDrawerId = DomainId.random()
    val cashSessionId = DomainId.random()
    val openingFloatSourceId = DomainId.random()
    val openingFloatOperationId = OperationId.random()
    val skuInstallOperation = OperationId.random()

    val grants = buildList {
        add(plan.baselineGrant(coordinates, SecurityAction.CATALOG_ACTIVATE, verb = com.ukkera.pos.security.PermissionVerb.ADMINISTER,
            targets = setOf(skuId)))
        add(plan.baselineGrant(coordinates, SecurityAction.WAREHOUSE_CONFIGURE,
            verb = com.ukkera.pos.security.PermissionVerb.ADMINISTER, targets = setOf(warehouseId)))
        add(plan.baselineGrant(coordinates, SecurityAction.OPENING_INVENTORY_APPROVE,
            verb = com.ukkera.pos.security.PermissionVerb.ADMINISTER,
            amount = Money(Currency.EGP, SAS_SENTINEL_STOCK_VALUE_MINOR), targets = setOf(openingBasisId)))
        add(plan.baselineGrant(coordinates, SecurityAction.INVENTORY_ADJUST, targets = setOf(openingReceiptId)))
        add(plan.baselineGrant(coordinates, SecurityAction.CASH_CONFIGURE,
            targets = setOf(coordinates.firstActorId, cashDrawerId)))
        add(plan.baselineGrant(coordinates, SecurityAction.CASH_OPEN,
            amount = Money(Currency.EGP, SAS_SENTINEL_FLOAT_MINOR), targets = setOf(cashDrawerId)))
        add(plan.baselineGrant(coordinates, SecurityAction.CASH_READ,
            targets = setOf(coordinates.firstActorId, cashSessionId)))
        add(plan.baselineGrant(coordinates, SecurityAction.INVENTORY_QUANTITY_READ))
        add(plan.baselineGrant(coordinates, SecurityAction.INVENTORY_COST_READ))
        add(plan.baselineGrant(coordinates, SecurityAction.CATALOG_READ))
        add(plan.baselineGrant(coordinates, SecurityAction.BRANCH_ACTIVITY_ACTIVATE,
            verb = com.ukkera.pos.security.PermissionVerb.ADMINISTER))
        add(plan.baselineGrant(coordinates, SecurityAction.SETUP_CONFIGURATION_SETTLE,
            verb = com.ukkera.pos.security.PermissionVerb.ADMINISTER))
        add(plan.baselineGrant(coordinates, SecurityAction.SETUP_CATALOG_IMPORT_SETTLE,
            verb = com.ukkera.pos.security.PermissionVerb.ADMINISTER))
        add(plan.baselineGrant(coordinates, SecurityAction.SETUP_OPENING_SETTLE,
            verb = com.ukkera.pos.security.PermissionVerb.ADMINISTER))
        add(plan.baselineGrant(coordinates, SecurityAction.SETUP_CHECKPOINTS_READ))
        // Saved-result read is deliberately not granted here: BASE-CUT verification uses only
        // the new checkpoint-detail read. ACK/go-live remain absent from P0.
    }
    val policy = plan.baselinePolicy(coordinates, Revision(1), grants)
    check(policy.grants.none { it.permission.action == SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE ||
        it.permission.action == SecurityAction.SETUP_BUSINESS_GO_LIVE }) { "P0 granted a new ACK/go-live action" }
    activatePolicy(SasActor.US, policy, Revision(1))
    signIn(SasActor.US)

    suspend fun command(request: MutationRequest): MutationReply.Recorded =
        client.execute(SasActor.US, request) as? MutationReply.Recorded
            ?: error("Base-cut production command was not committed: ${request.command.name}")

    val publications = client.publications ?: error("The production native publication owner is unavailable")
    val skuDefinition = LocalPublishedSkuV1(
        company = company, publicationId = skuPublicationId, skuId = skuId, productId = productId,
        catalogRevision = Revision(0), skuRevision = Revision(0),
        names = LocalNamesV1("SAS synthetic sentinel SKU", ""), code = SAS_SENTINEL_SKU_CODE,
        barcodes = emptyList(), baseUnitId = baseUnitId, quantityScale = 0,
        tracking = LocalTrackingV1.QUANTITY, fulfillment = LocalFulfillmentV1.RETAIL,
        requiresExpiry = false, canPurchase = true, canSell = true, active = true,
        conversions = emptyList(), prices = listOf(LocalPriceV1(
            ruleId = DomainId.random(), revision = Revision(0), channel = "COUNTER", priority = 0,
            minimumQuantity = Quantity(baseUnitId, 0, 0), maximumQuantityExclusive = null,
            effectiveFrom = UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), effectiveUntil = null,
            unitPrice = UnitPrice(Currency.EGP, ExactDecimal.parse("25.00")),
            inclusion = com.ukkera.pos.pricing.TaxInclusion.INCLUSIVE,
            tax = com.ukkera.pos.pricing.TaxSnapshot(DomainId.random(), Revision(0), "SYNTHETIC_ZERO",
                PercentageRate.parse("0")),
        )), returnPromise = null,
    )
    val skuProposal = CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
        skuPublicationId, skuId, LocalConfigurationCodec.canonicalPublication(skuDefinition))
    val skuAction = nativeFixtureValue(publications.prepare(skuProposal))
    val skuOutput = withSasSecret(secrets.head) { head ->
        nativeFixtureSecret(head) { nativeFixtureValue(publications.approve(skuAction, plan.headLogin, it)) }
    }
    val signedSku = skuOutput.readNativeCompanySku()

    val activityDefinition = LocalPublishedCompanyActivityProfileV1(company, DomainId.random(), DomainId.random(),
        Revision(0), setOf(LocalSalesActivity.SUPERMARKET))
    val activityProposal = CompanyBusinessPublicationProposal(company,
        CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1,
        activityDefinition.publicationId, activityDefinition.profileId,
        LocalCompanyActivityPublicationCodecV1.canonicalPublication(activityDefinition))
    val activityAction = nativeFixtureValue(publications.prepare(activityProposal))
    val activityOutput = withSasSecret(secrets.head) { head ->
        nativeFixtureSecret(head) { nativeFixtureValue(publications.approve(activityAction, plan.headLogin, it)) }
    }
    val signedActivity = activityOutput.readNativeCompanyActivityProfile()

    val skuResult = command(MutationRequest(skuInstallOperation, LocalConfigurationProfile.COMMAND,
        LocalConfigurationCodec.encode(LocalConfigurationRequestV1.InstallCompanySku(signedSku))))
    val installed = LocalConfigurationCodec.decodeResult(skuResult.result)
    check(installed.code == "configuration.applied" && installed.id == skuId && installed.revision != null)

    val activity = command(LocalBranchActivityCodecV1.activateInitial(OperationId.random(),
        LocalActivateInitialBranchActivityV1(LocalCompanyActivityPublicationCodecV1.signedPublication(signedActivity))))
    check(LocalBranchActivityCodecV1.decodeResult(activity.result).code == LocalBranchActivityProfileV1.ACTIVATED)

    val warehouseReply = command(MutationRequest(OperationId.random(), LocalConfigurationProfile.COMMAND,
        LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(warehouseId, LocalNamesV1("SAS synthetic warehouse", ""), true)))))
    check(LocalConfigurationCodec.decodeResult(warehouseReply.result).id == warehouseId)

    val approval = LocalReceivingApprovalV1(openingBasisId, LocalBasisKindV1.OPENING, supplierId = null,
        validFrom = UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), validUntil = null,
        lines = listOf(LocalApprovedStockLineV1(openingAllocationId, skuId, warehouseId,
            Quantity(baseUnitId, SAS_SENTINEL_QUANTITY, 0), Money(Currency.EGP, SAS_SENTINEL_STOCK_VALUE_MINOR),
            inspectionRequired = false)))
    val approvalReply = command(MutationRequest(OperationId.random(), LocalConfigurationProfile.COMMAND,
        LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ApproveReceiving(approval))))
    val approvalResult = LocalConfigurationCodec.decodeResult(approvalReply.result)
    check(approvalResult.id == openingBasisId && approvalResult.revision != null)

    val openingRequest = LocalGoodsReceiptRequest(openingReceiptId, openingBasisId,
        checkNotNull(approvalResult.revision), listOf(LocalStockItemVersion(skuId, null)),
        listOf(LocalGoodsReceiptLine(DomainId.random(), openingAllocationId, openingStockSourceId, skuId,
            warehouseId, Quantity(baseUnitId, SAS_SENTINEL_QUANTITY, 0), conversion = null, lotId = null,
            expiresAt = null, disposition = LocalReceiptDisposition.RELEASED,
            originalValue = Money(Currency.EGP, SAS_SENTINEL_STOCK_VALUE_MINOR))), "setup.opening")
    val openingReply = command(LocalStockCommands.opening(openingStockOperationId, openingRequest))
    val opening = LocalStockCommands.decodeResult(openingReply.result)
    check(opening.kind == LocalStockResultKind.OPENED && opening.documentId == openingReceiptId)

    val quantity = LocalStockQueries.decodeQuantities(read(LocalStockCommands.quantities(skuId)))
    check(quantity.rows.sumOf { it.onHand.scaled } == SAS_SENTINEL_QUANTITY) {
        "BASE-CUT opening stock is not the frozen 3 base units"
    }
    val costs = LocalStockQueries.decodeCosts(read(LocalStockCommands.costs(skuId)))
    check(costs.rows.sumOf { it.carryingValue.minor } == SAS_SENTINEL_STOCK_VALUE_MINOR) {
        "BASE-CUT opening stock value is not the frozen 7500 minor"
    }

    command(MutationRequest(OperationId.random(), LocalSalesStoreProfile.CASH_COMMAND,
        LocalCashCommandCodec.encode(LocalCashRequestV1.ConfigureCustodian(coordinates.firstActorId))))
    command(MutationRequest(OperationId.random(), LocalSalesStoreProfile.CASH_COMMAND,
        LocalCashCommandCodec.encode(LocalCashRequestV1.ConfigureDrawer(cashDrawerId,
            "SAS synthetic drawer", Currency.EGP))))
    val cashOpen = command(MutationRequest(openingFloatOperationId, LocalSalesStoreProfile.CASH_COMMAND,
        LocalCashCommandCodec.encode(LocalCashRequestV1.Open(cashSessionId, cashDrawerId, Revision(0), Revision(0),
            BusinessDate.parseCanonical("2026-11-09"), openingFloatSourceId,
            Money(Currency.EGP, SAS_SENTINEL_FLOAT_MINOR),
            LocalCashEvidenceV1(DomainId.random(), UtcInstant.parseCanonical("2026-11-09T00:00:00Z"))))))
    val cashResult = LocalSalesResultCodec.decode(cashOpen.result)
    check(cashResult is LocalSalesResultV1.Applied &&
        cashResult.action == com.ukkera.pos.sales.store.LocalSalesAction.CASH_OPENED &&
        cashResult.documentId == cashSessionId && cashResult.amount == Money(Currency.EGP, SAS_SENTINEL_FLOAT_MINOR))
    val cashView = LocalCashReadCodec.decode(read(LocalCashReadCodec.request(
        LocalCashQueryV1.Session(cashSessionId))))
    check(cashView is LocalCashReadResultV1.Session &&
        cashView.session.expectedCash == Money(Currency.EGP, SAS_SENTINEL_FLOAT_MINOR)) {
        "BASE-CUT drawer float is not the frozen 50000 minor"
    }

    val target = LocalSetupCheckpointTargetV1(LocalSetupOriginalBindingV1(anchor.installationId,
        anchor.originalRequestId, anchor.issuedAttemptId, anchor.attemptSequence,
        com.ukkera.pos.security.PayloadDigest(anchor.definitionDigest),
        com.ukkera.pos.security.PayloadDigest(anchor.genesisDigest),
        com.ukkera.pos.security.PayloadDigest(anchor.receiptDigest)), LocalSetupEnvironmentV1.LIVE)
    command(LocalSetupCheckpointCodecV1.configure(OperationId.random(),
        LocalSetupConfigurationRequestV1(target, Revision(0), Currency.EGP, "Africa/Cairo", 0,
            LocalSetupFiscalIdentityV1("SAS synthetic company", taxRegistrationAbsent = true))))
    val catalogEntry = LocalSetupCatalogImportManifestEntryV1(openingStockSourceId, skuId, skuInstallOperation)
    command(LocalSetupCheckpointCodecV1.catalogImportRow(OperationId.random(),
        LocalSetupCatalogImportRowRequestV1(target, openingStockSourceId, skuId, skuInstallOperation)))
    command(LocalSetupCheckpointCodecV1.completeCatalogImport(OperationId.random(),
        LocalSetupCatalogImportCompleteRequestV1(target, LocalSetupImportModeV1.DECLARED, 1,
            LocalSetupCheckpointCodecV1.catalogInputManifestDigest(listOf(catalogEntry)))))
    command(LocalSetupCheckpointCodecV1.openingStockRow(OperationId.random(),
        LocalSetupOpeningStockRowRequestV1(target, openingStockSourceId, openingReceiptId, openingStockOperationId)))
    command(LocalSetupCheckpointCodecV1.openingFloatRow(OperationId.random(),
        LocalSetupOpeningFloatRowRequestV1(target, openingFloatSourceId, cashSessionId, openingFloatOperationId)))
    val stockEntry = LocalSetupOpeningStockManifestEntryV1(openingStockSourceId, openingReceiptId,
        openingStockOperationId)
    val floatEntry = LocalSetupOpeningFloatManifestEntryV1(openingFloatSourceId, cashSessionId,
        openingFloatOperationId)
    command(LocalSetupCheckpointCodecV1.completeOpening(OperationId.random(),
        LocalSetupOpeningCompleteRequestV1(target, LocalSetupOpeningChoiceV1.DECLARED, 1,
            LocalSetupCheckpointCodecV1.stockInputManifestDigest(listOf(stockEntry)),
            LocalSetupOpeningChoiceV1.DECLARED, 1,
            LocalSetupCheckpointCodecV1.floatInputManifestDigest(listOf(floatEntry)))))

    val projection = LocalSetupCheckpointCodecV1.decodeProjection(
        read(LocalSetupCheckpointCodecV1.read(anchor.installationId)))
    check(projection.configuration != null && projection.catalogImport != null && projection.opening != null) {
        "BASE-CUT setup checkpoints are incomplete"
    }
    check(projection.opening?.stockValueTotal == Money(Currency.EGP, SAS_SENTINEL_STOCK_VALUE_MINOR) &&
        projection.opening?.floatTotal == Money(Currency.EGP, SAS_SENTINEL_FLOAT_MINOR)) {
        "BASE-CUT checkpoint totals differ from the frozen sentinel arithmetic"
    }

    client.logout(SasActor.US)
    evidence.event("SAS_BASE_CUT_SETTLED", JSONObject()
        .put("skuCode", SAS_SENTINEL_SKU_CODE).put("quantity", SAS_SENTINEL_QUANTITY)
        .put("stockValueMinor", SAS_SENTINEL_STOCK_VALUE_MINOR).put("floatMinor", SAS_SENTINEL_FLOAT_MINOR)
        .put("salesCommandsExecuted", 0).put("supplierObligationMinor", 0)
        .put("acknowledgement", "ABSENT").put("goLive", "ABSENT"))
    return SasBaseCut(skuId, warehouseId, openingBasisId, openingReceiptId, openingStockSourceId,
        openingStockOperationId, cashDrawerId, cashSessionId, openingFloatSourceId, openingFloatOperationId,
        policy, projection)
}

private suspend fun SasFixture.read(request: com.ukkera.pos.database.ReadRequest): StoredBytes {
    val reply = client.read(SasActor.US, request)
    return (reply as? ReadReply.Data)?.payload ?: error("Base-cut production read was unavailable: ${request.query.name}")
}

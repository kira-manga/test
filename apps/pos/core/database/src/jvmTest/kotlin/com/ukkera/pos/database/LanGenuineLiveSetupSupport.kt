package com.ukkera.pos.database

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.catalog.store.setup.*
import com.ukkera.pos.database.setup.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/**
 * Genuine LIVE fixture preparation only, NOT an independent oracle or a readiness provider.
 * Not invoked by the frozen bounded v1 executor. A separately frozen input mapping must name
 * these actions before running them. The supplied dispatch must retain each exact original
 * before the actual protected production command; an unknown result stops preparation.
 *
 * Quantities/values below are INPUTS copied from frozen F-RETAIL, not computed expectations.
 * No zero/legacy opening, database seed, accepting verifier or prerequisite flag is supplied.
 */
internal class LanGenuineLiveSetupSupport(
    private val original: LocalSetupSavedOriginalResultV1,
    private val publications: LocalCompanyBusinessPublications,
    private val headLogin: String,
    private val headPassword: () -> CharArray,
    private val dispatch: suspend (MutationRequest) -> MutationReply.Recorded,
) {
    private val company = CompanyScope(original.organizationId, original.companyId)
    private val scope = BranchScope(original.organizationId, original.companyId, original.branchId)
    private val target = LocalSetupCheckpointTargetV1(LocalSetupOriginalBindingV1(
        original.installationId, original.originalRequestId, original.issuedAttemptId,
        original.attemptSequence, original.definitionDigest, original.genesisDigest,
        original.receiptDigest), LocalSetupEnvironmentV1.LIVE)

    data class RetailOriginals(
        val skuA: LocalPublishedSkuV1,
        val skuB: LocalPublishedSkuV1,
        val warehouseId: DomainId,
        val stockRows: List<LocalSetupOpeningStockManifestEntryV1>,
    )

    suspend fun configureCatalogAndOpeningStock(): RetailOriginals {
        val activity = LocalPublishedCompanyActivityProfileV1(company, DomainId.random(), DomainId.random(),
            Revision(1), setOf(LocalSalesActivity.CLOTHING))
        val signedActivity = publish(activity.toNativeCompanyActivityProposal()).readNativeCompanyActivityProfile()
        dispatch(LocalBranchActivityCodecV1.activateInitial(OperationId.random(),
            LocalActivateInitialBranchActivityV1(LocalCompanyActivityPublicationCodecV1.signedPublication(signedActivity))))
        dispatch(LocalSetupCheckpointCodecV1.configure(OperationId.random(), LocalSetupConfigurationRequestV1(
            target, Revision(0), Currency.EGP, "Africa/Cairo", 0,
            LocalSetupFiscalIdentityV1("Synthetic LAN legal entity", taxRegistrationAbsent = true))))

        val unit = DomainId.random()
        val taxCategory = DomainId.random()
        val skuA = retailSku(unit, taxCategory, "A", "100", TaxInclusion.EXCLUSIVE)
        val skuB = retailSku(unit, taxCategory, "B", "114", TaxInclusion.INCLUSIVE)
        // Initialize the actual normalized stock projection. Legacy opening after this point
        // is not an alternative; its deliberately closed producer must remain closed.
        dispatch(LocalStockRelationalCommandsV2.initializeFresh(OperationId.random(), DomainId.random(),
            "synthetic.lan.opening"))
        val catalogRows = listOf(skuA, skuB).map { sku ->
            val bytes = LocalConfigurationCodec.canonicalPublication(sku)
            val signed = try {
                publish(CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
                    sku.publicationId, sku.skuId, bytes)).readNativeCompanySku()
            } finally { bytes.fill(0) }
            val command = configuration(LocalConfigurationRequestV1.InstallCompanySku(signed))
            dispatch(command)
            val row = LocalSetupCatalogImportManifestEntryV1(DomainId.random(), sku.skuId, command.operationId)
            dispatch(LocalSetupCheckpointCodecV1.catalogImportRow(OperationId.random(),
                LocalSetupCatalogImportRowRequestV1(target, row.sourceId, row.skuId, row.catalogOperationId)))
            row
        }
        dispatch(LocalSetupCheckpointCodecV1.completeCatalogImport(OperationId.random(),
            LocalSetupCatalogImportCompleteRequestV1(target, LocalSetupImportModeV1.DECLARED, catalogRows.size,
                checkNotNull(LocalSetupCheckpointCodecV1.catalogInputManifestDigest(catalogRows)))))

        val warehouse = DomainId.random()
        dispatch(configuration(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic LAN warehouse", "مخزن شبكة اختبار"), true))))
        dispatch(configuration(LocalConfigurationRequestV1.ConfigureSalesRoute(null,
            LocalSalesRouteV1(DomainId.random(), LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER,
                warehouse, true))))
        val allocationA = DomainId.random()
        val allocationB = DomainId.random()
        val basis = LocalReceivingApprovalV1(DomainId.random(), LocalBasisKindV1.OPENING, null, EFFECTIVE_FROM, null,
            listOf(LocalApprovedStockLineV1(allocationA, skuA.skuId, warehouse, Quantity(unit, 12, 0),
                Money(Currency.EGP, 48_000), false),
                LocalApprovedStockLineV1(allocationB, skuB.skuId, warehouse, Quantity(unit, 10, 0),
                    Money(Currency.EGP, 60_000), false)))
        dispatch(configuration(LocalConfigurationRequestV1.ApproveReceiving(basis)))
        val receipt = DomainId.random()
        val sourceA = DomainId.random()
        val sourceB = DomainId.random()
        val opening = LocalStockRelationalCommandsV2.opening(OperationId.random(), LocalGoodsReceiptRequest(
            receipt, basis.id, Revision(1),
            listOf(LocalStockItemVersion(skuA.skuId, null), LocalStockItemVersion(skuB.skuId, null)),
            listOf(LocalGoodsReceiptLine(DomainId.random(), allocationA, sourceA, skuA.skuId, warehouse,
                Quantity(unit, 12, 0), null, null, null, LocalReceiptDisposition.RELEASED,
                Money(Currency.EGP, 48_000)),
                LocalGoodsReceiptLine(DomainId.random(), allocationB, sourceB, skuB.skuId, warehouse,
                    Quantity(unit, 10, 0), null, null, null, LocalReceiptDisposition.RELEASED,
                    Money(Currency.EGP, 60_000))), "synthetic.lan.opening"))
        dispatch(opening)
        val stockRows = listOf(sourceA, sourceB).map {
            LocalSetupOpeningStockManifestEntryV1(it, receipt, opening.operationId)
        }
        // The production checkpoint must understand the genuine streamed opening original.
        // Missing binding is a real dependency/failure, never permission to claim EXPLICIT_ZERO.
        for (row in stockRows) dispatch(LocalSetupCheckpointCodecV1.openingStockRow(OperationId.random(),
            LocalSetupOpeningStockRowRequestV1(target, row.sourceId, row.receiptId, row.openingOperationId)))
        return RetailOriginals(skuA, skuB, warehouse, stockRows)
    }

    suspend fun settleOpening(stockRows: List<LocalSetupOpeningStockManifestEntryV1>,
        floatRows: List<LocalSetupOpeningFloatManifestEntryV1>) {
        require(stockRows.isNotEmpty() && floatRows.size == 3 && floatRows.map { it.sessionId }.distinct().size == 3)
        for (row in floatRows) dispatch(LocalSetupCheckpointCodecV1.openingFloatRow(OperationId.random(),
            LocalSetupOpeningFloatRowRequestV1(target, row.sourceId, row.sessionId, row.openingOperationId)))
        dispatch(LocalSetupCheckpointCodecV1.completeOpening(OperationId.random(), LocalSetupOpeningCompleteRequestV1(
            target, LocalSetupOpeningChoiceV1.DECLARED, stockRows.size,
            checkNotNull(LocalSetupCheckpointCodecV1.stockInputManifestDigest(stockRows)),
            LocalSetupOpeningChoiceV1.DECLARED, floatRows.size,
            checkNotNull(LocalSetupCheckpointCodecV1.floatInputManifestDigest(floatRows)))))
    }

    suspend fun explicitlyGoLive(endpoint: LocalMasterClient, credentials: LocalGatewayCredentials) {
        val request = ReadRequest(LocalSetupResultProfileV1.READ,
            LocalSetupResultCodecV1.encodeRequest(original.installationId))
        val before = endpoint.read(credentials, request)
        check(before is ReadReply.Data) { "Genuine LIVE prerequisite read unavailable" }
        val projection = LocalSetupResultCodecV1.decodeProjection(before.payload)
        check(projection.original == original && projection.acknowledgement != null && projection.goLive == null &&
            projection.prerequisites.all { it.settled }) { "Actual LIVE prerequisites are not all settled" }
        val goLive = MutationRequest(OperationId.random(), LocalSetupResultProfileV1.GO_LIVE, request.payload)
        val recorded = dispatch(goLive)
        val outcome = LocalSetupResultCodecV1.decodeOutcome(recorded.result)
        check(recorded.committed && outcome.status == LocalSetupResultStatusV1.LIVE &&
            outcome.code == LocalSetupResultProfileV1.LIVE_CODE && outcome.goLive?.operationId == goLive.operationId)
        // Resend the exact retained original only. This does not simulate or claim lost-reply
        // injection; the eventual mapped case must separately execute that fault boundary.
        val replay = endpoint.execute(credentials, goLive)
        check(replay is MutationReply.Recorded && replay.replayed && replay.operationId == recorded.operationId &&
            replay.result == recorded.result) { "Genuine original go-live result did not recover" }
        val after = endpoint.read(credentials, request)
        check(after is ReadReply.Data && LocalSetupResultCodecV1.decodeProjection(after.payload)
            .goLive?.operationId == goLive.operationId) { "Actual explicit go-live readback unavailable" }
    }

    private suspend fun publish(proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationOutput {
        val prepared = publications.prepare(proposal).fixtureValue("native publication preparation")
        val reviewed = publications.review(prepared.originalActionId).fixtureValue("native publication original review")
        check(reviewed.originalActionId == prepared.originalActionId)
        val password = headPassword()
        val output = try { publications.approve(reviewed, headLogin, password).fixtureValue("fresh named-head publication approval") }
            finally { password.fill('\u0000') }
        val source = proposal.copyCanonicalContent()
        val retained = output.proposal.copyCanonicalContent()
        try {
            check(source.contentEquals(retained) && publications.trust.verify(company, output.signingKeyId,
                source, output.signatureHex)) { "Actual native publication changed or failed installed trust verification" }
        } finally { source.fill(0); retained.fill(0) }
        check(publications.recover(output.originalActionId).fixtureValue("native publication original recovery").signatureHex == output.signatureHex)
        return output
    }

    private fun retailSku(unit: DomainId, taxCategory: DomainId, label: String, price: String,
        inclusion: TaxInclusion): LocalPublishedSkuV1 {
        val product = DomainId.random()
        val names = LocalNamesV1("Synthetic LAN item $label", "صنف شبكة اختبار $label")
        return LocalPublishedSkuV1(company, DomainId.random(), DomainId.random(), product, Revision(1), Revision(1),
            names, "SYNTHETIC-LAN-$label", emptyList(), unit, 0, LocalTrackingV1.QUANTITY,
            LocalFulfillmentV1.RETAIL, false, true, true, true, emptyList(),
            listOf(LocalPriceV1(DomainId.random(), Revision(1), null, 0, Quantity(unit, 1, 0),
                effectiveFrom = EFFECTIVE_FROM, unitPrice = UnitPrice.parse(Currency.EGP, price),
                inclusion = inclusion, tax = TaxSnapshot(taxCategory, Revision(1), "SYNTHETIC_VAT_14",
                    PercentageRate.parse("14")))),
            unitDefinitions = listOf(CatalogUnit(unit, Code.exact("EA"), UnitDimension.COUNT, 0)),
            productDefinition = LocalPublishedProductV1(product, Revision(1), names, emptyList(), emptyList(),
                emptyList(), LocalProductStatusV1.ACTIVE), variantValues = emptyList())
    }

    private fun configuration(request: LocalConfigurationRequestV1) = MutationRequest(OperationId.random(),
        LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(request))

    companion object {
        // Explicit fixture facts, independent of a device clock. They are not certification.
        val EFFECTIVE_FROM: UtcInstant = UtcInstant.parseCanonical("2020-01-01T00:00:00Z")
        val BUSINESS_DATE: BusinessDate = BusinessDate.parseCanonical("2026-11-15")
        val OBSERVED_AT: UtcInstant = UtcInstant.parseCanonical("2026-11-15T10:00:00Z")
        val ADMINISTER_PERMISSIONS: Set<Permission> = setOf(SecurityAction.BRANCH_ACTIVITY_ACTIVATE,
            SecurityAction.SETUP_CONFIGURATION_SETTLE, SecurityAction.SETUP_CATALOG_IMPORT_SETTLE,
            SecurityAction.SETUP_OPENING_SETTLE, SecurityAction.CATALOG_ACTIVATE,
            SecurityAction.WAREHOUSE_CONFIGURE, SecurityAction.OPENING_INVENTORY_APPROVE)
            .mapTo(linkedSetOf()) { Permission(it, PermissionVerb.ADMINISTER) }
    }
}

/** Real Client UI stage/submit/recovery/presentation ACK, never a fabricated Client journal. */
internal suspend fun openLanFixtureFloat(workflows: LocalWorkflowGateway, drawerId: DomainId):
    LocalSetupOpeningFloatManifestEntryV1 {
    val read = workflows.read(LocalWorkflowQuery(LocalWorkflow.CASH))
    check(read is LocalReadResult.Available) { "Actual cashier opening form read unavailable" }
    val form = read.page.forms.singleOrNull { it.action == LocalAction.CASH_OPEN }
        ?: error("Actual protected cashier opening form is unavailable")
    val drawer = (form.fields.single { it.field == LocalField.DRAWER_ID } as? LocalFormField.Scalar)?.references
        .orEmpty()
        .singleOrNull { it.reference.id == drawerId }?.reference
        ?: error("The exact fixture drawer is not an available authorized choice")
    val custodianRevision = (form.fields.single { it.field == LocalField.EXPECTED_CUSTODIAN_REVISION }
        as? LocalFormField.Scalar)?.initialValue
        ?: error("The actual custodian revision is unavailable")
    val operation = OperationId.random()
    val session = DomainId.random()
    val source = DomainId.random()
    val intent = LocalCommandIntent(operation, LocalAction.CASH_OPEN, form.version, null, listOf(
        LocalScalarInput(LocalField.SESSION_ID, LocalScalarValue.Identifier(session)),
        LocalScalarInput(LocalField.DRAWER_ID, LocalScalarValue.Reference(drawer)),
        LocalScalarInput(LocalField.EXPECTED_CUSTODIAN_REVISION, custodianRevision),
        LocalScalarInput(LocalField.BUSINESS_DATE, LocalScalarValue.Date(LanGenuineLiveSetupSupport.BUSINESS_DATE.canonicalString())),
        LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(source)),
        LocalScalarInput(LocalField.OPENING_FLOAT, LocalScalarValue.Decimal("1000.00")),
        LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(DomainId.random())),
        LocalScalarInput(LocalField.OCCURRED_AT, LocalScalarValue.Text(LanGenuineLiveSetupSupport.OBSERVED_AT.canonicalString()))))
    val staged = workflows.stage(intent)
    check(staged is LocalStageResult.Staged && staged.operation.operationId == operation) { "Genuine opening intent did not stage" }
    val recorded = workflows.submit(operation)
    check(recorded is LocalOperationResult.Recorded && recorded.operationId == operation &&
        recorded.decision == LocalRecordedDecision.APPLIED) { "Original synthetic opening float did not apply" }
    val recovered = workflows.lookup(operation)
    check(recovered is LocalOperationResult.Recorded && recovered.operationId == operation &&
        recovered.decision == recorded.decision) { "Original synthetic opening float result did not recover" }
    val acknowledged = workflows.acknowledgeRecorded(operation)
    check(acknowledged is LocalAcknowledgementResult.Acknowledged && acknowledged.operationId == operation) {
        "Original synthetic opening result presentation ACK unavailable"
    }
    return LocalSetupOpeningFloatManifestEntryV1(source, session, operation)
}

private fun <T> LocalCompanyAuthorityResult<T>.fixtureValue(stage: String): T = when (this) {
    is LocalCompanyAuthorityResult.Value -> value
    is LocalCompanyAuthorityResult.Unavailable -> error("$stage unavailable: $reason")
}

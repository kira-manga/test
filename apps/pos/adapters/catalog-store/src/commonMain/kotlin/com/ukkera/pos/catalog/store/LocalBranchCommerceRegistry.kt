package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.ActionApprovalBusinessAttributionSupport
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.MasterLocalDisplayReadHandler
import com.ukkera.pos.database.NativeOriginalDomain
import com.ukkera.pos.database.NativeOriginalImportInterpreter
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StockCanonicalProjectionV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventClassifier
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentState
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.StreamedSourceObservationV2
import com.ukkera.pos.database.setup.LocalSetupAcknowledgementServices
import com.ukkera.pos.database.setup.LocalSetupResultHistoryProducer
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointHistoryProducer
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointServices
import com.ukkera.pos.inventory.store.LocalStockHandlers
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockHistoryProducer
import com.ukkera.pos.inventory.store.LocalStockRelationalCodecV2
import com.ukkera.pos.inventory.store.LocalStockRelationalServices
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalServices
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalServicesV2
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalHistoryProducerV2
import com.ukkera.pos.sales.store.LocalFoodFinancialHandler
import com.ukkera.pos.sales.store.LocalFoodFinancialReadHandler
import com.ukkera.pos.sales.store.LocalFoodPaymentHandler
import com.ukkera.pos.sales.store.LocalFoodPaymentSupervisorHandler
import com.ukkera.pos.sales.store.LocalFoodPaymentReadHandler
import com.ukkera.pos.sales.store.LocalFoodPaymentHistoryProducer
import com.ukkera.pos.sales.store.LocalFoodNativeOriginalImport
import com.ukkera.pos.sales.store.LocalFoodRefundHandler
import com.ukkera.pos.sales.store.LocalFoodRefundReadHandler
import com.ukkera.pos.sales.store.LocalFoodRefundHistoryProducer
import com.ukkera.pos.sales.store.LocalFoodRefundNativeOriginalImport
import com.ukkera.pos.sales.store.LocalFoodHandler
import com.ukkera.pos.sales.store.LocalFoodReadHandler
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenReadHandler
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenReadHandlerV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenReadHandlerV3
import com.ukkera.pos.sales.store.LocalFoodSelectionPreviewReadHandler
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentHandler
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentReadHandler
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentHistoryProducer
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentProfileV1
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentDirectoryV1
import com.ukkera.pos.sales.store.LocalFoodTableDefinitionKindV1
import com.ukkera.pos.sales.store.LocalFoodTableDefinitionSourceV1
import com.ukkera.pos.sales.store.TransactionFoodTableAssignmentDirectorySourceV1
import com.ukkera.pos.sales.store.HistoricalFoodTableAssignmentDirectorySourceV1
import com.ukkera.pos.sales.store.LocalJournalCollectionHandler
import com.ukkera.pos.sales.store.LocalJournalFinancialReadHandler
import com.ukkera.pos.sales.store.LocalRetailNativeOriginalImport
import com.ukkera.pos.sales.store.LocalRetailSupervisorHandler
import com.ukkera.pos.sales.store.LocalJournalReturnHandler
import com.ukkera.pos.sales.store.LocalSalesHandlers
import com.ukkera.pos.sales.store.LocalNonphysicalHistoryProducer
import com.ukkera.pos.sales.store.LocalReturnStockHistoryProducer
import com.ukkera.pos.sales.store.LocalNormalizedPaymentHistoryProducer
import com.ukkera.pos.sales.store.LocalPaymentCorrectionHistoryProducer
import com.ukkera.pos.sales.store.HistoricalSalesTenderPolicySourceV1
import com.ukkera.pos.sales.store.LocalSalesTenderPolicyProfileV1
import com.ukkera.pos.sales.store.LocalSalesTenderSnapshot
import com.ukkera.pos.sales.store.LocalRetailRefundHistoryProducer
import com.ukkera.pos.sales.store.LocalReturnCaseHistoryProducer
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptHistoryProducer
import com.ukkera.pos.sales.store.LocalBranchReportExportHistoryProducer
import com.ukkera.pos.sales.store.LocalSupervisorReviewHistoryProducer
import com.ukkera.pos.sales.store.LocalFoodOperationHistoryProducer
import com.ukkera.pos.sales.store.LocalFoodCheckRefusalSupport
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileReadHandler
import com.ukkera.pos.sales.store.TransactionRetailJournalParticipantsSource
import com.ukkera.pos.sales.store.TransactionSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.TransactionSalesRecordingCapacity
import com.ukkera.pos.sales.store.TransactionSalesReturnPolicySource
import com.ukkera.pos.sales.store.TransactionSalesReturnCaseSourceV1
import com.ukkera.pos.sales.store.UnavailableSalesReturnCaseSourceV1
import com.ukkera.pos.sales.store.UnavailableRetailJournalParticipantsSource
import com.ukkera.pos.security.NativePurchasingPolicyTrust
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.ExternalTenderKind
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Production handler assembly for the implemented local subset. The same normalized stock
 * repository, complete collection/food verifiers and durable catalog feed reads, mutations and quotes.
 * In particular pricing and count-surplus authority are not forgotten in a separate test-only
 * registry. The grouped master and its client must use THESE exact command/read key sets.
 *
 * This creates no database, writer appointment, employee, signature key, return policy, capacity
 * receipt or platform qualification. All trust inputs are explicit. Missing physical capacity
 * remains refusal; installing these handlers does not qualify an unavailable implementation.
 * Food preparation, financial planning and genuine FOOD CASH handlers share these same sources.
 * Physical observations still require the actually registered native gate; this registry supplies
 * neither native custody, remedy policy, printer acknowledgment nor full food
 * workflows. The mandatory cash-close guard preserves unresolved food obligations. Unpriced
 * receiving retains its explicit missing-policy/evidence/qualified-serial gates; registration
 * does not make those required local capabilities complete.
 * Food/local-scope completion and hands-on readiness are NOT claimed. The retail quote source
 * stays base-unit retail; it does not silently accept food, service or pack collection proofs.
 */
public class LocalBranchCommerceRegistry(
    signatures: CompanyPublicationSignatureVerifier,
    returnPolicy: TransactionSalesReturnPolicySource,
    recordingCapacity: TransactionSalesRecordingCapacity,
    physicalRecordingCapacity: TransactionSalesPhysicalRecordingCapacityV2,
    retailParticipants: TransactionRetailJournalParticipantsSource = UnavailableRetailJournalParticipantsSource,
    returnCaseSource: TransactionSalesReturnCaseSourceV1 = LocalCatalogReturnCaseSource(signatures),
    purchasingPolicyTrust: NativePurchasingPolicyTrust? = null,
    nativePublications: LocalCompanyBusinessPublications? = null,
) {
    private val serialRegistrations = LocalSerialRegistrationServices(nativePublications)
    public val configuration: LocalConfigurationRepository = LocalConfigurationRepository(signatures, nativePublications)
    private val branchActivities = LocalBranchActivityServices(signatures)
    // Documentary activation never supplies the separate actual-sale qualification source.
    private val returnRightsPolicies = LocalReturnRightsActivationServices(signatures)
    private val purchasingAuthority = LocalPurchasingReceivingAuthority(configuration)
    private val stockAuthority = LocalStockRelationalAuthority(configuration, purchasingAuthority)
    private val normalizedQuotes = LocalCatalogNormalizedSalesQuoteSource(signatures)
    private val foodSource = LocalCatalogFoodStockSource(signatures)
    private val foodCheckRefusals = LocalFoodCheckRefusalSupport(LocalCatalogFoodCheckRefusalSupport(signatures))
    private val foodTableDirectory = LocalRegisteredFoodTableAssignmentDirectorySourceV1()
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = LocalRegisteredSalesTenderPolicySourceV1
    /** The kernel must install this codec on its own writer, not a separate stock connection. */
    public val stockProjection: StockCanonicalProjectionV2 = LocalStockRelationalCodecV2
    private val arrivalAuthorityV2 = LocalCatalogUnpricedArrivalAuthorityV2(configuration, purchasingPolicyTrust, nativePublications)
    public val stock: LocalStockRepository = LocalStockRepository(listOf(LocalSalesHandlers.stockCompositeVerifier(tenderPolicies),
        LocalCatalogFoodStockVerifier(signatures), LocalCatalogFoodFinancialVerifier(signatures)),
        purchaseOrderReader = purchasingAuthority,
        relationalCompositeVerifiers = LocalSalesHandlers.normalizedStockCompositeVerifiers(normalizedQuotes, foodSource, tenderPolicies),
        relationalAuthority = stockAuthority, documentaryCodecV2 = arrivalAuthorityV2)
    // Explicit setup-result commands/read and the durable configuration/import/opening
    // checkpoints that back their go-live prerequisites. Registration creates no grant,
    // readiness or native custody; the master kernel still derives current authority and
    // original binding. Every settled prerequisite additionally verifies the exact retained
    // catalog/stock/cash operation/result/effect identity, so unrelated rows are never used.
    private val setupCheckpoints = LocalSetupCheckpointServices(signatures)
    private val setupResults = LocalSetupAcknowledgementServices(setupCheckpoints.prerequisites)
    private val supplierSettlement = LocalSupplierSettlementServices(stock, retailParticipants)
    // Retains only actual native-issued policy envelopes. Activation does not qualify
    // documentary receipt/product selectors or supply the separate arrival authority.
    private val purchasingPolicies = LocalPurchasingPolicyServices(purchasingPolicyTrust)
    private val catalogReleases = LocalCatalogReleaseServices(nativePublications)
    private val documentaryIntake = LocalPurchasingDocumentServices()
    private val openingSupplierObligations = LocalOpeningSupplierObligationServices(configuration)
    private val retailCustomerReceiptHistory = LocalRetailCustomerReceiptHistoryProducer()
    private val historyProducers = listOf(LocalConfigurationHistoryProducer(signatures), LocalSupplierHistoryProducerV2(), LocalPricingHistoryProducer(), LocalFoodConfigurationHistoryProducer(signatures),
        LocalFoodFloorHistoryProducer(), LocalFoodTableAssignmentHistoryProducer(foodTableDirectory),
        LocalStockHistoryProducer(stock), LocalNonphysicalHistoryProducer(stock), LocalReturnStockHistoryProducer(stock),
        LocalNormalizedPaymentHistoryProducer(tenderPolicies), LocalPaymentCorrectionHistoryProducer(tenderPolicies), LocalRetailRefundHistoryProducer(),
        LocalReturnCaseHistoryProducer(returnCaseSource as? com.ukkera.pos.sales.store.HistoricalSalesReturnCaseSourceV1
            ?: com.ukkera.pos.sales.store.UnavailableHistoricalSalesReturnCaseSourceV1), retailCustomerReceiptHistory,
        LocalBranchReportExportHistoryProducer(),
        LocalFoodOperationHistoryProducer(stock, foodCheckRefusals), LocalFoodPaymentHistoryProducer(), LocalFoodRefundHistoryProducer(), LocalPurchasingHistoryProducer(configuration, stock),
        LocalPurchasingInvoiceCaptureHistoryProducerV2(PurchasingInvoiceCaptureCatalogAuthorityV2(signatures, nativePublications)),
        LocalPurchasingCapturedMatchHistoryProducerV2(stock),
        supplierSettlement.history, purchasingPolicies.history, catalogReleases.history, serialRegistrations.history, openingSupplierObligations.history, branchActivities.history,
        returnRightsPolicies.history, documentaryIntake.history, LocalSetupResultHistoryProducer(setupCheckpoints.prerequisites),
        LocalSetupCheckpointHistoryProducer(signatures),
        LocalUnpricedArrivalHistoryProducerV2(stock, LocalCatalogUnpricedArrivalOriginalSourcesV2(configuration, purchasingPolicyTrust, nativePublications)))
    private val supervisorReviewHistory = LocalSupervisorReviewHistoryProducer(tenderPolicies)
    /** The actual registry's signature-backed configuration and complete installed stock/
     * commerce producers, not a structural verifier or a replacement current read view. */
    public val historyProducer: RuntimeHistoryBusinessProducer = object : RuntimeHistoryBusinessProducer {
        private val byCommand = historyProducers.flatMap { source -> source.commands.map { it to source } }.toMap()
        override val commands: Set<CommandKey> = byCommand.keys.toSet() + supervisorReviewHistory.commands
        override val retailCustomerReceiptEventClassifier: RuntimeRetailCustomerReceiptEventClassifier =
            retailCustomerReceiptHistory.retailCustomerReceiptEventClassifier
        override fun actionApprovalBusinessAttribution(command: CommandKey): ActionApprovalBusinessAttributionSupport =
            byCommand[command]?.actionApprovalBusinessAttribution(command) ?: ActionApprovalBusinessAttributionSupport.UNSUPPORTED
        override suspend fun joinCurrentFood(state: RuntimeHistoricalFoodCurrentState): Boolean =
            historyProducers.filterIsInstance<LocalFoodOperationHistoryProducer>().single().joinCurrentFood(state)
        override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
            // Explicit decoded Review subtype route, not duplicate command ownership or a
            // general rejection fallback. Non-Review keeps its original Apply producer.
            if (supervisorReviewHistory.isReviewRequest(command.command, command.normalized))
                return supervisorReviewHistory.join(command)
            return byCommand[command.command]?.join(command) ?: false
        }
    }
    // LocalStockHandlers also owns the relational administration command. Its current
    // authority must be the exact bridge installed on stock for retained/current joins.
    // The bridge delegates legacy configuration reads to the same configuration owner.
    private val stockHandlers = LocalStockHandlers(stock, stockAuthority)
    private val normalizedStock = LocalStockRelationalServices(stock, stockAuthority)
    private val arrivals = LocalUnpricedArrivalServices(stock, LocalCatalogUnpricedArrivalAuthority(configuration))
    private val arrivalsV2 = LocalUnpricedArrivalServicesV2(stock, arrivalAuthorityV2)
    private val purchasing = LocalPurchasingServices(signatures, stock, nativePublications)
    private val quoteSource = LocalCatalogSalesQuoteSource(signatures, stock)
    private val commandHandlers: List<MasterCommandHandler> =
        listOf(LocalConfigurationHandler(signatures, nativePublications), LocalSupplierHandlerV2(), LocalPricingHandler(signatures), LocalCountSurplusApprovalHandler(stock),
            LocalFoodConfigurationHandler(signatures), LocalFoodFloorConfigurationHandler(), LocalFoodHandler(stock, foodSource),
            LocalFoodTableAssignmentHandler(stock, foodTableDirectory),
            LocalFoodFinancialHandler(stock, foodSource),
            LocalFoodPaymentHandler(stock, foodSource, retailParticipants),
            LocalFoodPaymentSupervisorHandler(stock, foodSource),
            LocalFoodRefundHandler(stock, retailParticipants),
            LocalJournalCollectionHandler(stock, configuration, retailParticipants, tenderPolicies),
            LocalJournalReturnHandler(retailParticipants, stock, tenderPolicies),
            LocalRetailSupervisorHandler(stock, tenderPolicies)) +
            // LocalStockHandlers already includes the normalized administration command.
            // The separate relational services instance contributes its reads only below.
            stockHandlers.commands +
            LocalSalesHandlers.normalizedStockCommands(stock, stockAuthority, normalizedQuotes, foodSource, tenderPolicies, foodCheckRefusals) +
            purchasing.commands + documentaryIntake.commands + supplierSettlement.commands + purchasingPolicies.commands + catalogReleases.commands + serialRegistrations.commands + openingSupplierObligations.commands +
            branchActivities.commands + returnRightsPolicies.commands + arrivals.commands + arrivalsV2.commands +
            setupCheckpoints.commands + setupResults.commands + LocalSalesHandlers.commands(stock, quoteSource, configuration, returnPolicy,
                recordingCapacity, physicalRecordingCapacity, returnCaseSource, tenderPolicies)
    private val queryHandlers: List<MasterReadHandler> =
        listOf(configuration.readHandler(), LocalSupplierReadHandlerV2(), LocalPricingReadHandler(signatures), LocalCountSurplusApprovalReadHandler(stock),
            LocalFoodConfigurationReadHandler(signatures), LocalFoodFloorReadHandler(),
            LocalFoodMenuReadHandler(signatures), LocalFoodStationDirectoryReadHandler(signatures),
            MasterLocalDisplayReadHandler(LocalFoodReadHandler(foodSource, stock, foodSource)),
            LocalFoodTableAssignmentReadHandler(stock, foodTableDirectory),
            LocalFoodCheckKitchenReadHandler(foodSource, stock, foodSource),
            LocalFoodCheckKitchenReadHandlerV2(foodSource, stock, foodSource),
            LocalFoodCheckKitchenReadHandlerV3(foodSource, stock, foodSource),
            LocalFoodQuotationProfileReadHandler(foodSource, stock),
            LocalFoodSelectionPreviewReadHandler.withStock(stock, foodSource),
            LocalFoodFinancialReadHandler(foodSource, stock), LocalFoodPaymentReadHandler(stock, foodSource), LocalFoodRefundReadHandler(stock),
            LocalJournalFinancialReadHandler(stock, tenderPolicies)) +
            LocalSalesHandlers.foodHandoffReads(stock, tenderPolicies) + stockHandlers.reads + normalizedStock.reads +
            purchasing.reads + documentaryIntake.reads + supplierSettlement.reads + purchasingPolicies.reads + catalogReleases.reads + serialRegistrations.reads + openingSupplierObligations.reads +
            branchActivities.reads + returnRightsPolicies.reads + arrivals.reads + arrivalsV2.reads +
            setupCheckpoints.reads + setupResults.reads + LocalSalesHandlers.reads(stock, foodSource, tenderPolicies)

    // R2 discovery/review and v2 handoff effects use the real same-loan protected recipient
    // source and genuine full preparation/financial proof. Registration is not independent
    // acceptance evidence, stock32x16 capacity or physical food-payment qualification.

    // These are historical data interpreters, not an authorization/native-custody facade.
    // The production master must install them on its SAME physical gate/history owner.
    private val originalInterpreters: List<NativeOriginalImportInterpreter> = listOf(
        LocalRetailNativeOriginalImport(NativeOriginalDomain.RETAIL_PAYMENT_V3, stock, tenderPolicies),
        LocalRetailNativeOriginalImport(NativeOriginalDomain.RETAIL_REFUND_V3, stock, tenderPolicies),
        LocalFoodNativeOriginalImport(stock),
        LocalFoodRefundNativeOriginalImport(stock),
        supplierSettlement.nativeOriginalImport,
    )

    public val commands: List<MasterCommandHandler> get() = commandHandlers.toList()
    public val reads: List<MasterReadHandler> get() = queryHandlers.toList()
    public val nativeOriginalImports: List<NativeOriginalImportInterpreter> get() = originalInterpreters.toList()
    public val commandKeys: Set<CommandKey> get() = commandHandlers.map { it.key }.toSet()
    public val readKeys: Set<ReadKey> get() = queryHandlers.map { it.key }.toSet()

    init {
        require(commandKeys.size == commandHandlers.size) {
            "Duplicate command registration: ${commandHandlers.groupBy { it.key }.filterValues { it.size > 1 }.keys}"
        }
        require(readKeys.size == queryHandlers.size) {
            "Duplicate read registration: ${queryHandlers.groupBy { it.key }.filterValues { it.size > 1 }.keys}"
        }
        val historyCommands = historyProducers.flatMap { it.commands }
        require(historyCommands.size == historyCommands.distinct().size) {
            "Duplicate business-history command registration"
        }
        require(historyProducer.commands.all { it in commandKeys }) {
            "Business-history commands lack handlers: ${historyProducer.commands - commandKeys}"
        }
        require(commandHandlers.size <= 128 && queryHandlers.size <= 128)
        require(originalInterpreters.map { it.domain }.toSet() == NativeOriginalDomain.entries.toSet() &&
            originalInterpreters.all { it.domain.command in commandKeys })
    }
}

/** Canonical catalog DATA only. Consumers obtain the exact same-loan/native BEFORE row;
 * this decoder does not look up today's policy or provide employee/native authority. */
private object LocalRegisteredSalesTenderPolicySourceV1 : HistoricalSalesTenderPolicySourceV1 {
    override fun decode(scope: BranchScope, row: StoredAggregate): LocalSalesTenderSnapshot? =
        snapshot(scope, row.key, row.revision, row.schema, row.payload, row.digest)

    override fun decode(scope: BranchScope, row: StreamedSourceObservationV2.Current): LocalSalesTenderSnapshot? =
        snapshot(scope, row.key, row.generation, row.schema, row.payload, row.payloadHash)

    private fun snapshot(scope: BranchScope, key: AggregateKey, generation: Revision,
        schema: String, payload: StoredBytes, digest: PayloadDigest): LocalSalesTenderSnapshot? {
        if (key.kind != LocalSalesTenderPolicyProfileV1.KIND || schema != LocalSalesTenderPolicyProfileV1.SCHEMA) return null
        check(key == LocalConfigurationProfile.key(LocalConfigurationProfile.TENDER, key.id) &&
            schema == LocalConfigurationProfile.schema(LocalConfigurationProfile.TENDER) && sha256(payload.copyBytes()) == digest)
        val record = decodeRecord(scope, key, generation, schema, payload)
        val value = (record.body as? ConfigurationBody.ExternalTender)?.value ?: error("Invalid tender storage")
        if (!value.active) return null
        return LocalSalesTenderSnapshot(scope, Tender.External(value.id, record.revision,
            ExternalTenderKind.valueOf(value.kind.name), value.requiresTerminal, value.requiresReference),
            setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, value.id)))
    }
}

/** The actual catalog writers bridge into the binding-only sales profile. Definition names,
 * occupancy and directory permission are not copied into an order or used as assignment authority. */
private class LocalRegisteredFoodTableAssignmentDirectorySourceV1 : TransactionFoodTableAssignmentDirectorySourceV1,
    HistoricalFoodTableAssignmentDirectorySourceV1 {
    override suspend fun current(view: CommandReadView): LocalFoodTableAssignmentDirectoryV1 =
        tableAssignmentDirectory(LocalFoodFloorSourceReader(view).directory())

    override suspend fun before(command: RuntimeHistoricalCommand): LocalFoodTableAssignmentDirectoryV1 =
        tableAssignmentDirectory(FoodFloorDirectoryV1(
            historicalDefinitions(command, LocalFoodFloorProfileV1.FLOOR, LocalFoodFloorProfileV1.MAX_FLOORS),
            historicalDefinitions(command, LocalFoodFloorProfileV1.TABLE, LocalFoodFloorProfileV1.MAX_TABLES)))

    override suspend fun requireRecorded(view: CommandReadView, directory: LocalFoodTableAssignmentDirectoryV1,
        consumer: RetainedCommandEvidence) {
        check(consumer.scope == view.scope)
        val cache = mutableMapOf<OperationId, Pair<RetainedCommandEvidence, FoodFloorRecordV1>>()
        var bytes = 0L
        requireRecordedDirectory(directory, consumer) { operation ->
            cache[operation] ?: run {
                val original = view.retainedCommand(operation) ?: error("food.table.assignment.definition_original_missing")
                check(original.scope == view.scope && original.operationId == operation)
                val record = requireFoodFloorProducer(original)
                val version = view.aggregate(LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(operation.value)))
                    ?: error("food.table.assignment.definition_version_missing")
                check(decodeFoodFloorRecord(view.scope, version) == record)
                requireFoodFloorWrite(original, version)
                bytes = chargeDefinitionOriginal(cache.size, bytes, original)
                (original to record).also { cache[operation] = it }
            }
        }
    }

    override suspend fun requireRecorded(command: RuntimeHistoricalCommand, directory: LocalFoodTableAssignmentDirectoryV1,
        consumer: RetainedCommandEvidence) {
        check(consumer.scope == command.scope)
        val cache = mutableMapOf<OperationId, Pair<RetainedCommandEvidence, FoodFloorRecordV1>>()
        var bytes = 0L
        requireRecordedDirectory(directory, consumer) { operation ->
            cache[operation] ?: run {
                val paired = command.earlierOriginal(operation) ?: error("food.table.assignment.history_definition_original_missing")
                val original = (paired as? RetainedOriginalV2.Legacy)?.evidence
                    ?: error("food.table.assignment.history_definition_original_profile")
                check(original.scope == command.scope && original.operationId == operation)
                val record = requireFoodFloorProducer(original)
                val version = command.aggregateSource(LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(operation.value)))
                    ?: error("food.table.assignment.history_definition_version_missing")
                check(version.original === paired && decodeFoodFloorRecord(command.scope, version.row) == record)
                requireFoodFloorWrite(original, version.row)
                bytes = chargeDefinitionOriginal(cache.size, bytes, original)
                (original to record).also { cache[operation] = it }
            }
        }
    }

    private suspend fun historicalDefinitions(command: RuntimeHistoricalCommand, kind: String,
        maximum: Int): List<FoodFloorRecordV1> {
        val rows = command.aggregates(kind).sortedBy { it.key.id.value }
        check(rows.size <= maximum && rows.zipWithNext().all { (a, b) -> a.key.id.value < b.key.id.value })
        return rows.map { row ->
            val paired = command.aggregateSource(row.key) ?: error("food.table.assignment.history_definition_source_missing")
            sameDefinitionRow(row, paired.row)
            val record = decodeFoodFloorRecord(command.scope, row)
            val original = (paired.original as? RetainedOriginalV2.Legacy)?.evidence
                ?: error("food.table.assignment.history_definition_original_profile")
            check(original.scope == command.scope && original.operationId == record.operationId &&
                command.earlierOriginal(record.operationId) === paired.original && requireFoodFloorProducer(original) == record)
            requireFoodFloorWrite(original, row)
            val version = command.aggregateSource(LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(record.operationId.value)))
                ?: error("food.table.assignment.history_definition_version_missing")
            check(version.original === paired.original && decodeFoodFloorRecord(command.scope, version.row) == record)
            requireFoodFloorWrite(original, version.row)
            record
        }
    }

    /** Every original dependency is proved iteratively. A favorable destination cannot bypass
     * a broken earlier definition, missing immutable version or malformed directory source. */
    private suspend fun requireRecordedDirectory(directory: LocalFoodTableAssignmentDirectoryV1,
        consumer: RetainedCommandEvidence,
        producer: suspend (OperationId) -> Pair<RetainedCommandEvidence, FoodFloorRecordV1>) {
        check(consumer.committed && consumer.nativeOriginalProvenance == null && consumer.command == LocalFoodTableAssignmentProfileV1.COMMAND)
        val pending = ArrayDeque<Pair<RetainedCommandEvidence, FoodFloorRecordV1>>()
        val queued = mutableSetOf<OperationId>()
        for (definition in directory.definitions) {
            val (original, record) = producer(definition.operationId)
            requireEarlierDefinition(original, consumer)
            check(tableAssignmentDefinition(record) == definition)
            if (queued.add(original.operationId)) pending.addLast(original to record)
        }
        while (pending.isNotEmpty()) {
            val (original, record) = pending.removeFirst()
            val prior = record.beforeSources.map { reference ->
                val (earlier, source) = producer(reference.operationId)
                requireEarlierDefinition(earlier, original)
                requireFoodFloorReference(reference, earlier, source)
                if (queued.add(earlier.operationId)) pending.addLast(earlier to source)
                source
            }
            val before = FoodFloorDirectoryV1(prior.filter { it.kind == LocalFoodFloorProfileV1.FLOOR },
                prior.filter { it.kind == LocalFoodFloorProfileV1.TABLE })
            check(before.references() == record.beforeSources && foodFloorRejection(record.request, before) == null)
        }
    }

    private fun tableAssignmentDirectory(directory: FoodFloorDirectoryV1): LocalFoodTableAssignmentDirectoryV1 =
        LocalFoodTableAssignmentDirectoryV1((directory.floors + directory.tables).map(::tableAssignmentDefinition))

    private fun tableAssignmentDefinition(record: FoodFloorRecordV1): LocalFoodTableDefinitionSourceV1 {
        val kind: LocalFoodTableDefinitionKindV1
        val active: Boolean
        val floorId: DomainId?
        when (val request = record.request) {
            is LocalFoodFloorConfigurationRequestV1.Floor -> {
                kind = LocalFoodTableDefinitionKindV1.FLOOR; active = request.floor.active; floorId = null
            }
            is LocalFoodFloorConfigurationRequestV1.Table -> {
                kind = LocalFoodTableDefinitionKindV1.TABLE; active = request.table.active; floorId = request.table.floorId
            }
        }
        return LocalFoodTableDefinitionSourceV1(kind, record.id, record.revision, record.operationId, record.actorId,
            foodFloorDigest(foodFloorEncode(record, FoodFloorRecordV1.serializer(), LocalFoodFloorProfileV1.MAX_RECORD_BYTES)), active, floorId)
    }

    private fun sameDefinitionRow(a: StoredAggregate, b: StoredAggregate) {
        check(a.key == b.key && a.revision == b.revision && a.schema == b.schema && a.payload == b.payload && a.digest == b.digest)
    }

    private fun requireEarlierDefinition(earlier: RetainedCommandEvidence, later: RetainedCommandEvidence) {
        check(earlier.committed && earlier.nativeOriginalProvenance == null && earlier.scope == later.scope &&
            earlier.sequence < later.sequence && earlier.authorityEpoch <= later.authorityEpoch && earlier.operationId != later.operationId)
    }

    private fun chargeDefinitionOriginal(count: Int, bytes: Long, original: RetainedCommandEvidence): Long {
        val charge = 8_192L + original.normalized.size + original.result.size + original.aggregateWrites.sumOf { it.payload.size.toLong() + 512L }
        check(count < 4_096 && charge <= 64L * 1_024 * 1_024 - bytes) {
            "food.table.assignment.definition_history_capacity_unavailable"
        }
        return bytes + charge
    }
}

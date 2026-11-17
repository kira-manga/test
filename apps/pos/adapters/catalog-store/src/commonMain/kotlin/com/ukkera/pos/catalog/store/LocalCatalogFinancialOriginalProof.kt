package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Installed catalog source services only. Whole ordinary configuration replay remains
 * separate from financial producers and from current employee/result disclosure policy. */
internal class LocalCatalogFinancialOriginalProof(private val stock: LocalStockRepository,
    signatures: CompanyPublicationSignatureVerifier? = null, configuration: LocalConfigurationRepository? = null,
    private val publications: LocalCompanyBusinessPublications? = null,
    caseSource: HistoricalSalesReturnCaseSourceV1? = null,
    returnPolicy: FactualSalesReturnPolicySourceV1? = null,
) : LocalFinancialOriginalCatalogProof {
    private val configurationHistory = signatures?.let(::LocalConfigurationHistoryProducer)
    private val pricingHistory = LocalPricingHistoryProducer()
    private val collectionHistory = LocalLegacyCollectionHistoryProducer(stock)
    private val returnHistory = LocalLegacyReturnHistoryProducer(returnPolicy)
    private val exchangeHistory = LocalExchangeHistoryProducer()
    private val caseHistory = LocalReturnCaseHistoryProducer(caseSource ?: UnavailableHistoricalSalesReturnCaseSourceV1)
    private val receiptEventHistory = LocalRetailCustomerReceiptHistoryProducer()
    private val rightsHistory = signatures?.let(::LocalReturnRightsActivationHistoryProducer)
    private val orderHistory = signatures?.let { LocalLegacyOrderHistoryProducer(LocalCatalogSalesQuoteSource(it, stock)) }
    private val receiptHistory = configuration?.let { com.ukkera.pos.inventory.store.LocalLegacyStockReceiptHistoryProducer(stock, it) }

    override suspend fun originalPublicationContents(proof: NativeHistoryBusinessPublicationObservationProof): List<CompanyBusinessPublicationOutput> =
        publications?.originalCompletedContents(proof) ?: error("financial.original_publication_contents_required")

    override suspend fun requireLegacyBusinessOriginal(program: BusinessOriginalProgram) {
        when (program.command) {
            LocalConfigurationProfile.COMMAND -> check(checkNotNull(configurationHistory) { "Original configuration services required" }.joinCommitted(program))
            LocalPricingProfile.COMMAND -> check(pricingHistory.joinCommitted(program))
            LocalReturnCaseProfileV1.COMMAND -> caseHistory.requireOriginal(program)
            LocalRetailCustomerReceiptCodec.COMMAND -> receiptEventHistory.requireOriginal(program)
            LocalReturnRightsActivationProfileV1.ACTIVATE -> check(checkNotNull(rightsHistory) {
                "Original return-rights source services required" }.joinCommitted(program))
            LocalSalesStoreProfile.COLLECTION_COMMAND -> collectionHistory.requireOriginal(program)
            LocalSalesStoreProfile.RETURN_COMMAND -> returnHistory.requireOriginal(program)
            LocalExchangeCodec.COMMAND -> exchangeHistory.requireOriginal(program)
            LocalSalesStoreProfile.ORDER_COMMAND -> checkNotNull(orderHistory) { "Original quotation services required" }.requireOriginal(program)
            com.ukkera.pos.inventory.store.LocalStockStoreProfile.OPENING,
            com.ukkera.pos.inventory.store.LocalStockStoreProfile.RECEIVE -> checkNotNull(receiptHistory) { "Original receipt services required" }.requireOriginal(program)
            else -> error("financial.original_source_command_unsupported")
        }
    }

    override suspend fun requireSupplierOriginal(scope: LocalFinancialOriginalProofScope, original: RetainedCommandEvidence) {
        check(original.command in setOf(LocalSupplierCashProfile.COMMAND, LocalSupplierCashSupervisorCodec.COMMAND))
        SupplierCashRpcOriginalProof(scope.view, stock).require(original)
    }

    override suspend fun requireSupplierConfiguration(scope: LocalFinancialOriginalProofScope,
        sources: RuntimeHistoricalCashConfigurationSources) {
        val original = sources.evidence
        check(sources.kind == RuntimeCashConfigurationSourceKind.CONFIGURE_SUPPLIER &&
            original.command == LocalConfigurationProfile.COMMAND && original.committed && original.code == "committed" &&
            original.nativeOriginalProvenance == null && LocalConfigurationCodec.encode(LocalConfigurationCodec.decode(original.normalized)) == original.normalized &&
            semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest)
        val request = LocalConfigurationCodec.decode(original.normalized) as? LocalConfigurationRequestV1.ConfigureSupplier
            ?: error("financial.supplier_configuration_original_required")
        val key = LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, request.supplier.id)
        val source = sources.aggregateSource(key)
        source?.let { scope.requireOriginal(it.original) }
        val before = source?.row
        val previous = before?.let { decodeRecord(original.scope, it) }
        check(before?.revision == request.expectedStorageRevision && before?.revision?.value != Long.MAX_VALUE &&
            (previous == null || previous.body is ConfigurationBody.Supplier))
        val next = previous?.revision?.next() ?: Revision(0)
        val requirement = AuthorizationRequirement(Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER),
            original.scope, ActionBinding(key.id, previous?.revision ?: Revision(0), original.semanticDigest,
                original.operationId, "local.configuration"), LimitUse.NotApplicable)
        check(sources.requirement == requirement)
        val record = ConfigurationRecord(original.scope, key.kind, key.id, next,
            ConfigurationBody.Supplier(request.supplier, (previous?.body as? ConfigurationBody.Supplier)?.reference),
            original.originalActorId, original.policyRevision, original.recordedAt)
        val expected = ProposedOutcome.Apply(encodeValue(LocalConfigurationResultV1("configuration.applied", key.id, next),
            LocalConfigurationResultV1.serializer()), listOf(AggregateChange(key, before?.revision,
                configurationV1WriteSchema(key.kind, before), encodeValue(record, ConfigurationRecord.serializer()))))
        check(original.result == expected.result && original.resultHash == sha256(expected.result.copyBytes()) &&
            original.aggregateWrites.size == 1 && original.claimChanges.isEmpty() && original.jobs.isEmpty())
        val change = expected.changes.single()
        val write = original.aggregateWrites.single()
        check(write.key == key && write.storageGeneration == (before?.revision ?: Revision(0)).next() &&
            write.schema == change.schema && write.payload == change.payload && write.payloadDigest == sha256(change.payload.copyBytes()))
        val resources = mutableSetOf(priceResource(original.originalActorId), priceResource(key.id))
        previous?.let { resources += priceResource(it.id); resources += priceResource(it.authorizedBy) }
        check(sources.resources.size == resources.size && sources.resources.all { native -> native.selectorTag == 3 &&
            native.preserveHold && native.originalEvidenceDigest == original.semanticDigest && resources.any {
                it.kind.name == native.kind?.name && it.resourceId == native.resourceId } })
    }
}

/** The exact supplier configuration writer at THIS native BEFORE, never today's supplier row. */
internal suspend fun qualifiedSupplierFinancialSource(sources: RuntimeHistoricalFinancialSources,
    view: CommandReadView, id: DomainId): StoredAggregate? {
    val source = sources.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id)) ?: return null
    LocalFinancialOriginalSourceAccess.require(view, source.original)
    return source.row
}

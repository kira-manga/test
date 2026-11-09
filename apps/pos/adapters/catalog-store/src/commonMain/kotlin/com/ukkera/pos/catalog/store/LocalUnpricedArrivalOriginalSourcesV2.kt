package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Reads genuine original BEFORE sources directly. It never implements CommandReadView,
 * invokes current authentication, reconstructs an approval or re-prepares a historical command. */
public class LocalCatalogUnpricedArrivalOriginalSourcesV2(
    private val configuration: LocalConfigurationRepository,
    private val trust: NativePurchasingPolicyTrust?,
    private val publications: LocalCompanyBusinessPublications?,
) : LocalUnpricedArrivalOriginalSourcesV2 {
    override suspend fun physicalBefore(command: RuntimeHistoricalCommand, supplierId: DomainId,
        skuIds: List<DomainId>, warehouseIds: List<DomainId>, serialIds: List<DomainId>): LocalArrivalHistoricalSourcesV2? {
        if (publications == null || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            serialIds.isNotEmpty()) return null
        val native = CatalogReleaseNativeCut.read(command.scope, publications, command.completedNativeBusinessPublicationsBefore())
        if (!completeCatalogCopiesBeforeV2(command, native)) return null
        val supplierRow = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, supplierId)) ?: return null
        val supplierRecord = decodeRecord(command.scope, supplierRow.row)
        val supplierValue = (supplierRecord.body as? ConfigurationBody.Supplier)?.value ?: error("Original arrival supplier kind")
        check(supplierValue.id == supplierId)
        val supplier = LocalArrivalSupplierAuthorityV2(command.scope, LocalArrivalSupplierViewV2(supplierId, supplierRow.row.revision,
            LocalArrivalNamesV1(supplierValue.names.english, supplierValue.names.arabic), supplierValue.active),
            supplierRow.row.arrivalStampV2(), listOf(supplierRow.row.arrivalStampV2()))
        val items = skuIds.map { id ->
            val row = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, id)) ?: return null
            val signed = decodeRecord(command.scope, row.row).signedSku()
            val sku = signed.definition
            if (native.skus[sku.publicationId] != signed || sku.skuId != id) return null
            val publication = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, sku.publicationId)) ?: return null
            if (decodeRecord(command.scope, publication.row).signedSku() != signed || publication.row.revision != Revision(0)) return null
            val product = sku.productDefinition ?: return null
            if (product.id != sku.productId || localCatalogProductShapeRejection(sku) != null) return null
            val relevant = localCatalogProductRelevantDefinitions(sku.productId, sku.skuId, native.skus.values.map { it.definition })
            val pinnedVersions = relevant.mapNotNull { it.productDefinition }.filter { it.id == product.id && it.revision <= product.revision }
            if (localCatalogProductVersionHistoryRejection(pinnedVersions) != null) return null
            if (sku.unitDefinitions != null && (localCatalogUnitShapeRejection(sku) != null ||
                localCatalogUnitHistoryRejection(native.skus.values.toList()) != null)) return null
            for (identity in listOf("sku-code/${sku.code}") + sku.barcodes.map { "barcode/$it" })
                check(command.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, identity)) == id)
            val actual = configuration.historicalStockItem(command, id) ?: return null
            val item = LocalStockItemAuthority(actual.scope, actual.skuId, actual.productId, actual.catalogRevision,
                actual.skuRevision, actual.baseUnitId, actual.quantityScale, actual.tracking, actual.requiresExpiry,
                actual.canPurchase, actual.active, actual.conversions,
                arrivalSourcesV2(actual.sources + publication.row.arrivalStampV2()), actual.unitDefinitions)
            LocalArrivalItemAuthorityV2(item, row.row.arrivalStampV2(),
                LocalArrivalNamesV1(sku.names.english, sku.names.arabic), sku.code, product.revision)
        }
        val warehouses = warehouseIds.map { id ->
            val row = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, id)) ?: return null
            val warehouse = (decodeRecord(command.scope, row.row).body as? ConfigurationBody.Warehouse)?.value
                ?: error("Original arrival warehouse kind")
            check(warehouse.id == id)
            val actual = LocalStockWarehouseAuthority(command.scope, id, warehouse.active, listOf(row.row.arrivalStampV2()))
            LocalArrivalWarehouseAuthority(actual, row.row.arrivalStampV2(), LocalArrivalNamesV1(warehouse.names.english, warehouse.names.arabic))
        }
        return LocalArrivalHistoricalSourcesV2(supplier, items, warehouses, emptyList(), emptySet())
    }

    override suspend fun evidenceBefore(command: RuntimeHistoricalCommand, action: LocalArrivalActionV1,
        selection: LocalArrivalEvidenceSelectionV2, expectedRequesterPolicyRevision: Revision,
        supplier: LocalArrivalSupplierAuthorityV2, items: List<LocalArrivalItemAuthorityV2>): LocalArrivalHistoricalEvidenceV2? {
        if (trust == null || publications == null || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return null
        check(selection.policy.scope == command.scope && selection.catalog.scope == command.scope &&
            supplier.scope == command.scope && items.isNotEmpty() && items.all { it.value.scope == command.scope })
        val policyReader = HistoricalPurchasingPolicyReader(command, trust)
        val policy = policyReader.active() ?: return null
        val native = CatalogReleaseNativeCut.read(command.scope, publications, command.completedNativeBusinessPublicationsBefore())
        val catalogReader = HistoricalCatalogReleaseReader(command, publications, native)
        val catalog = catalogReader.active() ?: return null
        native.requiresCandidate(catalog.signed)
        if (!catalogReader.membersMatch(catalog.signed)) return null
        val body = policy.policy.body
        val actualPolicy = LocalArrivalPolicySelectionV2(command.scope, body.policyId, body.publicationId, body.revision,
            LocalArrivalSourceSelectionV2.from(policy.active.arrivalStampV2()), LocalArrivalSourceSelectionV2.from(policy.publication.arrivalStampV2()),
            body.validity.validFrom, body.validity.validUntil)
        val actualCatalog = LocalArrivalCatalogSelectionV2(command.scope, catalog.signed.definition.catalogId,
            catalog.signed.definition.publicationId, catalog.signed.definition.revision,
            LocalArrivalSourceSelectionV2.from(catalog.active.arrivalStampV2()), LocalArrivalSourceSelectionV2.from(catalog.publication.arrivalStampV2()))
        val stamps = (listOf(policy.active.arrivalStampV2(), policy.publication.arrivalStampV2(),
            catalog.active.arrivalStampV2(), catalog.publication.arrivalStampV2()) + supplier.sources + items.flatMap { it.value.sources }).toMutableList()
        val readerResources = (policyReader.resources + catalogReader.resources).toMutableSet()
        fun decision(failure: LocalArrivalFailureV2?, prepared: Boolean): LocalArrivalHistoricalEvidenceV2 {
            // A preparation refusal carries only the physical-source/stock resources.
            // Successful preparation also contributes the exact documentary source union.
            val sources = if (prepared) arrivalSourcesV2(stamps) else emptyList()
            return LocalArrivalHistoricalEvidenceV2(failure, sources,
                if (prepared) readerResources + arrivalSourceResourcesV2(sources) else emptySet(), body.validity.validUntil)
        }
        fun preparationFailure(failure: LocalArrivalFailureV2) = decision(failure, false)
        if (selection.policy != actualPolicy || selection.catalog != actualCatalog)
            return preparationFailure(LocalArrivalFailureV2.POLICY_MISMATCH)
        val rule = body.rules.singleOrNull { it.ruleId == selection.ruleId }
            ?: return preparationFailure(LocalArrivalFailureV2.POLICY_MISMATCH)
        if (rule.action != action.purchasingActionV2()) return preparationFailure(LocalArrivalFailureV2.POLICY_MISMATCH)
        val approval = command.originalApproval
        // Old/direct captures retain the exact early refusal and its physical-only footprint.
        // Only a core-joined typed approval original can select the approved preparation path.
        if (approval == null && rule.approval !is PurchasingApprovalRouteV1.Direct)
            return preparationFailure(LocalArrivalFailureV2.APPROVAL_HISTORY_UNAVAILABLE)
        for (item in items) {
            val row = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, item.value.skuId))
                ?: error("Original selected arrival SKU lost")
            val signed = decodeRecord(command.scope, row.row).signedSku()
            val member = catalog.signed.definition.members.singleOrNull { it.skuId == item.value.skuId }
                ?: return preparationFailure(LocalArrivalFailureV2.POLICY_MISMATCH)
            if (native.skus[member.publicationId] != signed || member.publicationId != signed.definition.publicationId ||
                signed.definition.skuRevision != item.value.skuRevision || signed.definition.productId != item.value.productId ||
                signed.definition.productDefinition?.revision != item.productRevision)
                return preparationFailure(LocalArrivalFailureV2.POLICY_MISMATCH)
        }
        val documents = try {
            loadArrivalDocumentsV2(HistoricalPurchasingDocumentReader(command), supplier.value.id,
                selection.documents.map { it.documentId }, stamps, readerResources)
        } catch (failure: LocalArrivalEvidenceRejectedV2) { return preparationFailure(failure.failure) }
        if (documents != selection.documents) return preparationFailure(LocalArrivalFailureV2.POLICY_MISMATCH)
        // Native original employee identity and exact issuance/consume provenance are joined
        // by core, not reconstructed as a current AuthorizedAction. Policy selectors compare
        // only those immutable original coordinates and retain completion-refusal precedence.
        val mismatch = command.recordedAt < body.validity.validFrom || body.validity.validUntil?.let { command.recordedAt >= it } == true ||
            rule.limits !is PurchasingLimitsV1.NoMonetaryEffect || rule.acquisition != PurchasingAcquisitionProfileV1.NOT_APPLICABLE ||
            rule.selection.suppliers.none { it.supplierId == supplier.value.id && it.revision == supplier.value.storageRevision } ||
            items.any { item -> rule.selection.products.skus.none { arrivalProductV2(item).matchesV2(it, actualCatalog) } } ||
            !purchasingArrivalUseRouteMatchesV2(rule.approval, command.actorId,
                approval?.approvalId, approval?.approverId, approval?.approverPolicyRevision) ||
            selection.retention.classId != rule.evidence.retentionClassId || selection.retention.revision != rule.evidence.retentionClassRevision ||
            body.retentionClasses.none { it.classId == selection.retention.classId && it.revision == selection.retention.revision } ||
            documents.size !in rule.evidence.minimumCount..rule.evidence.maximumCount || documents.any {
                PurchasingEvidenceMediaV1.valueOf(it.media) !in rule.evidence.media || PurchasingEvidencePurposeV1.valueOf(it.purpose) !in rule.evidence.purposes }
        val failure = when {
            command.policyRevision != expectedRequesterPolicyRevision -> LocalArrivalFailureV2.STALE_REQUESTER_POLICY
            mismatch -> LocalArrivalFailureV2.POLICY_MISMATCH
            else -> null
        }
        if (failure != null) return decision(failure, true)
        val prepared = decision(null, true)
        val requirement = arrivalOriginalRequirementV2(command)
        if (approval != null && !arrivalOriginalApprovalMatchesV2(command, approval, requirement, expectedRequesterPolicyRevision))
            return LocalArrivalHistoricalEvidenceV2(LocalArrivalFailureV2.APPROVAL_HISTORY_UNAVAILABLE,
                prepared.sources, prepared.resources, prepared.mustCommitBefore)
        val effects = try { purchasingArrivalDocumentaryProjectionV2(command.scope, command.operationId, command.actorId,
            command.command, command.semanticDigest, command.authenticationId, command.authorizationGeneration, requirement,
            expectedRequesterPolicyRevision, command.recordedAt, selection, policy.policy, items.map(::arrivalProductV2), documents, prepared.sources,
            approval?.approvalId, approval?.approverId, approval?.approverPolicyRevision) }
        catch (rejection: LocalArrivalEvidenceRejectedV2) { return LocalArrivalHistoricalEvidenceV2(rejection.failure,
            prepared.sources, prepared.resources, prepared.mustCommitBefore) }
        return LocalArrivalHistoricalEvidenceV2(null, prepared.sources, prepared.resources, prepared.mustCommitBefore, effects)
    }

    override suspend fun before(command: RuntimeHistoricalCommand, supplierId: DomainId,
        skuIds: List<DomainId>, warehouseIds: List<DomainId>, serialIds: List<DomainId>,
        purchasing: LocalArrivalPurchasingEvidenceV2): LocalArrivalHistoricalSourcesV2? {
        if (trust == null || publications == null || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return null
        // Configuration has no genuine serial-registration producer yet. Never derive one
        // from an observed custody code, a manifest or a supplied historical DTO.
        if (serialIds.isNotEmpty()) return null
        val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        val use = retained.aggregateWrites.singleOrNull { it.key.kind == LocalPurchasingArrivalDocumentUseProfileV2.KIND } ?: return null
        val useRecord = purchasingDocumentDecode(use.payload, PurchasingArrivalDocumentUseRecordV2.serializer(),
            LocalPurchasingArrivalDocumentUseProfileV2.MAX_ROW_BYTES)
        // Exact original employee generation is native capture data, not a new session/proof.
        if (useRecord.producer.generation != command.authorizationGeneration ||
            useRecord.producer.authenticationId != command.authenticationId ||
            useRecord.requesterPolicyRevision != command.policyRevision) return null
        val selected = purchasing.selection
        val policyReader = HistoricalPurchasingPolicyReader(command, trust)
        val policy = policyReader.active() ?: return null
        val native = CatalogReleaseNativeCut.read(command.scope, publications, command.completedNativeBusinessPublicationsBefore())
        val catalogReader = HistoricalCatalogReleaseReader(command, publications, native)
        val catalog = catalogReader.active() ?: return null
        native.requiresCandidate(catalog.signed)
        if (!catalogReader.membersMatch(catalog.signed) || !completeCatalogCopiesBeforeV2(command, native)) return null
        val body = policy.policy.body
        val actualPolicy = LocalArrivalPolicySelectionV2(command.scope, body.policyId, body.publicationId, body.revision,
            LocalArrivalSourceSelectionV2.from(policy.active.arrivalStampV2()), LocalArrivalSourceSelectionV2.from(policy.publication.arrivalStampV2()),
            body.validity.validFrom, body.validity.validUntil)
        val actualCatalog = LocalArrivalCatalogSelectionV2(command.scope, catalog.signed.definition.catalogId,
            catalog.signed.definition.publicationId, catalog.signed.definition.revision,
            LocalArrivalSourceSelectionV2.from(catalog.active.arrivalStampV2()), LocalArrivalSourceSelectionV2.from(catalog.publication.arrivalStampV2()))
        if (selected.policy != actualPolicy || selected.catalog != actualCatalog || command.recordedAt < body.validity.validFrom ||
            body.validity.validUntil?.let { command.recordedAt >= it } == true) return null
        val wire = LocalCompanyPolicyWireV1.encodeSignedPolicy(policy.policy)
        try { if (wire.arrivalHexV2() != purchasing.policyWireHex) return null } finally { wire.fill(0) }
        val rule = body.rules.singleOrNull { it.ruleId == selected.ruleId } ?: return null
        val expectedAction = when (command.command) {
            LocalUnpricedArrivalProfileV2.RECORD -> PurchasingRuleActionV1.ARRIVAL_RECORD
            LocalUnpricedArrivalProfileV2.QUALIFY -> PurchasingRuleActionV1.ARRIVAL_QUALIFY
            LocalUnpricedArrivalProfileV2.REJECT -> PurchasingRuleActionV1.ARRIVAL_REJECT
            LocalUnpricedArrivalProfileV2.DISPATCH -> PurchasingRuleActionV1.ARRIVAL_DISPATCH
            else -> return null
        }
        val approval = command.originalApproval
        if (!purchasingArrivalUseRouteMatchesV2(rule.approval, command.actorId,
                approval?.approvalId, approval?.approverId, approval?.approverPolicyRevision) ||
            useRecord.producer.approvalId != approval?.approvalId ||
            useRecord.producer.approverId != approval?.approverId ||
            useRecord.producer.approverPolicyRevision != approval?.approverPolicyRevision) return null
        if (approval != null && !arrivalOriginalApprovalMatchesV2(command, approval,
                arrivalOriginalRequirementV2(command), useRecord.requesterPolicyRevision)) return null
        if (rule.action != expectedAction || rule.limits !is PurchasingLimitsV1.NoMonetaryEffect ||
            rule.acquisition != PurchasingAcquisitionProfileV1.NOT_APPLICABLE) return null
        val physical = physicalBefore(command, supplierId, skuIds, warehouseIds, serialIds) ?: return null
        val supplier = physical.supplier
        val items = physical.items
        val warehouses = physical.warehouses
        if (rule.selection.suppliers.none { it.supplierId == supplierId && it.revision == supplier.value.storageRevision } ||
            items.any { item -> rule.selection.products.skus.none { arrivalProductV2(item).matchesV2(it, actualCatalog) } }) return null
        val sources = (listOf(policy.active.arrivalStampV2(), policy.publication.arrivalStampV2(), catalog.active.arrivalStampV2(),
            catalog.publication.arrivalStampV2()) + supplier.sources + items.flatMap { it.value.sources }).toMutableList()
        val resources: MutableSet<RecoveryResourceKey> = (policyReader.resources + catalogReader.resources).toMutableSet()
        val originals = loadArrivalDocumentsV2(HistoricalPurchasingDocumentReader(command), supplierId,
            selected.documents.map { it.documentId }, sources, resources)
        if (originals != selected.documents || originals != purchasing.originalDocuments || items.map(::arrivalProductV2) != purchasing.products) return null
        val evidence = rule.evidence
        if (selected.retention.classId != evidence.retentionClassId || selected.retention.revision != evidence.retentionClassRevision ||
            body.retentionClasses.none { it.classId == selected.retention.classId && it.revision == selected.retention.revision } ||
            originals.size !in evidence.minimumCount..evidence.maximumCount || originals.any {
                PurchasingEvidenceMediaV1.valueOf(it.media) !in evidence.media || PurchasingEvidencePurposeV1.valueOf(it.purpose) !in evidence.purposes }) return null
        if (arrivalSourcesV2(sources).map(LocalArrivalSourceSelectionV2::from) != purchasing.sources) return null
        for (stamp in purchasing.sources) {
            val source = command.aggregateSource(stamp.stamp().key) ?: return null
            if (source.row.revision != stamp.storageRevision || source.row.digest != stamp.digest) return null
        }
        return LocalArrivalHistoricalSourcesV2(supplier, items, warehouses, emptyList(), resources + arrivalSourceResourcesV2(sources))
    }
}


/** Same complete activated/publication membership gate used by current product/unit reads. */
private suspend fun completeCatalogCopiesBeforeV2(command: RuntimeHistoricalCommand, native: CatalogReleaseNativeCut): Boolean {
    val originals = mutableMapOf<DomainId, LocalSignedSkuV1>()
    for (kind in listOf(LocalConfigurationProfile.PUBLICATION, LocalConfigurationProfile.SKU)) {
        val rows = command.aggregates(kind)
        if (rows.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return false
        for (row in rows) {
            val source = command.aggregateSource(row.key) ?: return false
            check(source.row.key == row.key && source.row.revision == row.revision && source.row.schema == row.schema &&
                source.row.payload == row.payload && source.row.digest == row.digest)
            val record = decodeRecord(command.scope, source.row)
            val signed = record.signedSku()
            if (kind == LocalConfigurationProfile.PUBLICATION) {
                if (record.revision != Revision(0) || native.skus[signed.definition.publicationId] != signed ||
                    originals.put(signed.definition.publicationId, signed) != null) return false
            } else if (originals[signed.definition.publicationId] != signed) return false
        }
    }
    return true
}

private fun ByteArray.arrivalHexV2(): String = joinToString("") { byte ->
    val n = byte.toInt() and 255
    "0123456789abcdef"[n ushr 4].toString() + "0123456789abcdef"[n and 15]
}

/** Exact core-joined original DATA, not a new approval or recovered employee authority. */
private fun arrivalOriginalApprovalMatchesV2(command: RuntimeHistoricalCommand,
    approval: RuntimeHistoricalActionApproval, requirement: AuthorizationRequirement,
    expectedRequesterPolicyRevision: Revision): Boolean =
    approval.requestorId == command.actorId && approval.requestorPolicyRevision == expectedRequesterPolicyRevision &&
        approval.requirement == requirement && approval.operationId == command.operationId &&
        approval.command == command.command && approval.semanticDigest == command.semanticDigest

private fun arrivalOriginalRequirementV2(command: RuntimeHistoricalCommand): AuthorizationRequirement {
    val binding = when (command.command) {
        LocalUnpricedArrivalProfileV2.RECORD -> LocalUnpricedArrivalCommandsV2.decodeRecordRequest(command.normalized).let {
            ActionBinding(it.arrivalId, Revision(0), command.semanticDigest, command.operationId, it.reasonCode) }
        LocalUnpricedArrivalProfileV2.QUALIFY -> LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(command.normalized).let {
            ActionBinding(it.arrivalId, it.expectedArrivalRevision, command.semanticDigest, command.operationId, it.reasonCode) }
        LocalUnpricedArrivalProfileV2.REJECT -> LocalUnpricedArrivalCommandsV2.decodeRejectRequest(command.normalized).let {
            ActionBinding(it.arrivalId, it.expectedArrivalRevision, command.semanticDigest, command.operationId, it.reasonCode) }
        LocalUnpricedArrivalProfileV2.DISPATCH -> LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(command.normalized).let {
            ActionBinding(it.arrivalId, it.expectedArrivalRevision, command.semanticDigest, command.operationId, it.reasonCode) }
        else -> error("Unsupported original arrival approval")
    }
    return AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), command.scope, binding)
}

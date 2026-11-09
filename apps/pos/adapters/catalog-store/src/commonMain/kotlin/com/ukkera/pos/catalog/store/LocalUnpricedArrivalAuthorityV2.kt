package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Actual same-branch sources for the additive direct custody workflow. A metadata projection
 * is not an execution permit: prepare reloads the policy, complete native release and originals
 * in the original writer, and complete requires the Master's exact current AuthorizedAction. */
public class LocalCatalogUnpricedArrivalAuthorityV2(
    private val configuration: LocalConfigurationRepository,
    private val policyTrust: NativePurchasingPolicyTrust?,
    private val publications: LocalCompanyBusinessPublications?,
) : LocalUnpricedArrivalAuthorityV2 {
    override val documentaryKinds: Set<String> get() = setOf(LocalPurchasingArrivalDocumentUseProfileV2.KIND)
    override val documentaryClaimNamespaces: Set<String> get() = setOf(LocalPurchasingArrivalDocumentUseProfileV2.OPERATION_USE_CLAIM)

    override suspend fun supplier(view: CommandReadView, id: DomainId): LocalArrivalSupplierAuthorityV2? {
        val (row, record) = arrivalConfigurationV2(view, LocalConfigurationProfile.SUPPLIER, id) ?: return null
        val value = (record.body as? ConfigurationBody.Supplier)?.value ?: error("Arrival supplier source kind")
        check(value.id == id)
        return LocalArrivalSupplierAuthorityV2(view.scope,
            LocalArrivalSupplierViewV2(id, row.revision, value.names.arrivalNamesV2(), value.active), row.arrivalStampV2(), listOf(row.arrivalStampV2()))
    }

    override suspend fun item(view: CommandReadView, id: DomainId): LocalArrivalItemAuthorityV2? {
        val actual = configuration.item(view, id) ?: return null
        val (row, record) = arrivalConfigurationV2(view, LocalConfigurationProfile.SKU, id) ?: error("Arrival SKU source lost")
        val signed = record.signedSku(); val sku = signed.definition
        val snapshot = view.originalNativeBusinessPublications() ?: publications?.currentCompletedSnapshot()
        val product = LocalCatalogProductAuthority(publications).current(view, signed, snapshot)
        if (product.sourceUnavailable || product.rejection != null || product.definition == null) return null
        val definition = product.definition
        check(definition.id == actual.productId && definition == sku.productDefinition && sku.skuId == id && row.arrivalStampV2() in actual.sources)
        val (published, publicationRecord) = arrivalConfigurationV2(view, LocalConfigurationProfile.PUBLICATION, sku.publicationId)
            ?: error("Arrival signed SKU original lost")
        check(publicationRecord.signedSku() == signed && publicationRecord.revision == Revision(0))
        for (identity in listOf("sku-code/${sku.code}") + sku.barcodes.map { "barcode/$it" })
            check(view.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, identity)) == id)
        val qualified = LocalStockItemAuthority(actual.scope, actual.skuId, actual.productId, actual.catalogRevision,
            actual.skuRevision, actual.baseUnitId, actual.quantityScale, actual.tracking, actual.requiresExpiry,
            actual.canPurchase, actual.active, actual.conversions, arrivalSourcesV2(actual.sources + published.arrivalStampV2()), actual.unitDefinitions)
        return LocalArrivalItemAuthorityV2(qualified, row.arrivalStampV2(), sku.names.arrivalNamesV2(), sku.code, definition.revision)
    }

    override suspend fun warehouse(view: CommandReadView, id: DomainId): LocalArrivalWarehouseAuthority? {
        val actual = configuration.warehouse(view, id) ?: return null
        val (row, record) = arrivalConfigurationV2(view, LocalConfigurationProfile.WAREHOUSE, id) ?: error("Arrival warehouse source lost")
        val value = (record.body as? ConfigurationBody.Warehouse)?.value ?: error("Arrival warehouse source kind")
        check(actual.scope == view.scope && actual.warehouseId == id && actual.active == value.active && row.arrivalStampV2() in actual.sources)
        return LocalArrivalWarehouseAuthority(actual, row.arrivalStampV2(), value.names.arrivalNamesV2())
    }

    override suspend fun serialRegistration(view: CommandReadView, id: DomainId): LocalStockSerialRegistration? =
        configuration.serialRegistration(view, id)

    override suspend fun keys(view: CommandReadView, kind: LocalArrivalSourceKindV1, after: DomainId?, limit: Int): LocalArrivalSourceKeys {
        require(limit in 1..5)
        val table = when (kind) {
            LocalArrivalSourceKindV1.SUPPLIER -> LocalConfigurationProfile.SUPPLIER
            LocalArrivalSourceKindV1.ITEM -> LocalConfigurationProfile.SKU
            LocalArrivalSourceKindV1.WAREHOUSE -> LocalConfigurationProfile.WAREHOUSE
            LocalArrivalSourceKindV1.SERIAL_REGISTRATION -> error("Serial registration requires exact identity")
        }
        val page = view.aggregateKeys(table, after, limit)
        check(page.keys.size <= limit && page.keys.all { it.kind == table } && page.keys.distinct().size == page.keys.size &&
            page.keys == page.keys.sortedBy { it.id.value } && (after == null || page.keys.all { it.id.value > after.value }) &&
            (page.nextAfter == null || page.nextAfter == page.keys.lastOrNull()?.id))
        return LocalArrivalSourceKeys(page.keys.map { it.id }, page.nextAfter)
    }

    override suspend fun purchasing(view: CommandReadView, supplierId: DomainId, action: LocalArrivalActionV1,
        skuIds: List<DomainId>, documentIds: List<DomainId>): LocalArrivalPurchasingSourceV2? {
        // Bounded metadata preparation only. The installed read handler must authorize
        // every projected original and supplemental parent before returning any fields.
        // Raw document chunks are not reconstructed in this discovery path.
        val loaded = loadPurchasingV2(view, originalWriter = false) ?: return null
        val supplier = supplier(view, supplierId) ?: return null
        val items = skuIds.map { item(view, it) ?: return null }
        val sources = loaded.sources.toMutableList()
        sources += supplier.sources; items.forEach { sources += it.value.sources }
        val resources = loaded.resources.toMutableSet()
        val documents = loadArrivalDocumentsV2(CurrentPurchasingDocumentReader(view), supplierId, documentIds, sources, resources, metadataOnly = true)
        val products = items.map(::arrivalProductV2)
        val rules = loaded.policy.policy.body.rules.filter { rule ->
            rule.action == action.purchasingActionV2() && rule.limits is PurchasingLimitsV1.NoMonetaryEffect &&
                rule.acquisition == PurchasingAcquisitionProfileV1.NOT_APPLICABLE &&
                rule.selection.suppliers.any { it.supplierId == supplierId && it.revision == supplier.value.storageRevision } &&
                products.all { actual -> rule.selection.products.skus.any { selected -> actual.matchesV2(selected, loaded.catalogSelection) } }
        }.map { rule ->
            val evidence = rule.evidence
            LocalArrivalRuleViewV2(rule.ruleId, action, rule.approval is PurchasingApprovalRouteV1.Direct,
                evidence.minimumCount, evidence.maximumCount, evidence.media.map { it.name }, evidence.purposes.map { it.name },
                LocalArrivalRetentionSelectionV2(evidence.retentionClassId, evidence.retentionClassRevision),
                rule.approval.arrivalApprovalRouteV2(), rule.arrivalRuleDetailsV2())
        }
        return LocalArrivalPurchasingSourceV2(LocalArrivalPurchasingViewV2(loaded.policySelection, loaded.catalogSelection, rules, documents),
            arrivalSourcesV2(sources), resources + arrivalSourceResourcesV2(sources))
    }

    override suspend fun prepareEvidence(view: CommandReadView, context: CommandContext, action: LocalArrivalActionV1,
        selection: LocalArrivalEvidenceSelectionV2, expectedRequesterPolicyRevision: Revision,
        supplier: LocalArrivalSupplierAuthorityV2, items: List<LocalArrivalItemAuthorityV2>,
        requirement: AuthorizationRequirement): LocalArrivalPreparedEvidenceV2? {
        check(view.scope == context.scope && supplier.scope == view.scope && selection.policy.scope == view.scope &&
            items.isNotEmpty() && items.all { it.value.scope == view.scope })
        val loaded = loadPurchasingV2(view, originalWriter = true) ?: return null
        arrivalCheckV2(loaded.policySelection == selection.policy && loaded.catalogSelection == selection.catalog,
            LocalArrivalFailureV2.POLICY_MISMATCH)
        val policy = loaded.policy.policy
        val rule = policy.body.rules.singleOrNull { it.ruleId == selection.ruleId }
            ?: throw LocalArrivalEvidenceRejectedV2(LocalArrivalFailureV2.POLICY_MISMATCH)
        arrivalCheckV2(rule.action == action.purchasingActionV2(), LocalArrivalFailureV2.POLICY_MISMATCH)
        if (!context.actionApprovalRequested)
            arrivalCheckV2(rule.approval is PurchasingApprovalRouteV1.Direct, LocalArrivalFailureV2.APPROVAL_HISTORY_UNAVAILABLE)
        val native = loaded.native
        for (item in items) {
            val current = arrivalConfigurationV2(view, LocalConfigurationProfile.SKU, item.value.skuId)
                ?: error("Arrival selected SKU lost")
            val signed = current.second.signedSku()
            val member = loaded.catalog.signed.definition.members.singleOrNull { it.skuId == item.value.skuId }
                ?: throw LocalArrivalEvidenceRejectedV2(LocalArrivalFailureV2.POLICY_MISMATCH)
            arrivalCheckV2(native.skus[member.publicationId] == signed && member.publicationId == signed.definition.publicationId &&
                signed.definition.skuRevision == item.value.skuRevision && signed.definition.productId == item.value.productId &&
                signed.definition.productDefinition?.revision == item.productRevision,
                LocalArrivalFailureV2.POLICY_MISMATCH)
        }
        val sources = (loaded.sources + supplier.sources + items.flatMap { it.value.sources }).toMutableList()
        val resources = loaded.resources.toMutableSet()
        val originals = loadArrivalDocumentsV2(CurrentPurchasingDocumentReader(view), supplier.value.id,
            selection.documents.map { it.documentId }, sources, resources)
        arrivalCheckV2(originals == selection.documents, LocalArrivalFailureV2.POLICY_MISMATCH)
        val products = items.map(::arrivalProductV2)
        val exactSources = arrivalSourcesV2(sources)
        val exactResources = resources + arrivalSourceResourcesV2(exactSources)
        return object : LocalArrivalPreparedEvidenceV2() {
            override val sources: List<LocalStockSourceStamp> = exactSources
            override val affectedResources: Set<RecoveryResourceKey> = exactResources
            override val mustCommitBefore: UtcInstant? = policy.body.validity.validUntil
            override fun complete(authorization: AuthorizedAction, recordedAt: UtcInstant): LocalArrivalDocumentaryEffectsV2 {
                arrivalCheckV2(authorization.policyRevision == expectedRequesterPolicyRevision, LocalArrivalFailureV2.STALE_REQUESTER_POLICY)
                // The kernel provides the exact current proof after genuine approval
                // preparation. Apply still requires same-transaction consume CAS and final
                // revalidation before commit. Never infer an approver from selected policy
                // or client-supplied coordinates.
                arrivalCheckV2(recordedAt >= policy.body.validity.validFrom &&
                    (policy.body.validity.validUntil == null || recordedAt < policy.body.validity.validUntil!!), LocalArrivalFailureV2.POLICY_MISMATCH)
                val result = PurchasingArrivalRuleConstraints.compare(policy.body,
                    PurchasingArrivalRuleSelection(context.scope, selection.policy.policyId, selection.policy.publicationId,
                        selection.policy.publicationRevision, selection.ruleId, PurchasingValidityV1(selection.policy.validFrom, selection.policy.validUntil)),
                    rule, action.purchasingActionV2(), PurchasingSupplierReferenceV1(supplier.value.id, supplier.value.storageRevision),
                    products.map { it.reference(selection.catalog) }, PurchasingCatalogReferenceV1(selection.catalog.publicationId, selection.catalog.revision),
                    selection.documents.map { it.reference() }, originals.map { it.reference() },
                    PurchasingArrivalRetentionSelection(selection.retention.classId, selection.retention.revision), authorization,
                    context.originalActorId, expectedRequesterPolicyRevision, requirement)
                arrivalCheckV2(result == PurchasingArrivalRuleConstraintResult.NO_MISMATCH, LocalArrivalFailureV2.POLICY_MISMATCH)
                return purchasingArrivalDocumentaryEffectsV2(context, requirement, expectedRequesterPolicyRevision, authorization,
                    recordedAt, selection, policy, products, originals, exactSources)
            }
        }
    }

    override fun retainedEffects(original: RetainedCommandEvidence, evidence: LocalArrivalPurchasingEvidenceV2,
        requirement: AuthorizationRequirement, expectedRequesterPolicyRevision: Revision): LocalArrivalDocumentaryEffectsV2 =
        retainedPurchasingArrivalDocumentaryEffectsV2(original, evidence, requirement, expectedRequesterPolicyRevision)

    private suspend fun loadPurchasingV2(view: CommandReadView, originalWriter: Boolean): ArrivalPurchasingLoadedV2? {
        if (policyTrust == null || publications == null) return null
        val snapshot = if (originalWriter) view.originalNativeBusinessPublications() else publications.currentCompletedSnapshot()
        if (snapshot == null) return null
        val policyReader = CurrentPurchasingPolicyReader(view, policyTrust)
        val policy = policyReader.active() ?: return null
        val native = CatalogReleaseNativeCut.current(view.scope, publications, snapshot)
        val catalogReader = CurrentCatalogReleaseReader(view, publications, native)
        val catalog = catalogReader.active() ?: return null
        native.requiresCandidate(catalog.signed)
        if (!catalogReader.membersMatch(catalog.signed)) return null
        val policyBody = policy.policy.body
        val policySelection = LocalArrivalPolicySelectionV2(view.scope, policyBody.policyId, policyBody.publicationId, policyBody.revision,
            LocalArrivalSourceSelectionV2.from(policy.active.arrivalStampV2()), LocalArrivalSourceSelectionV2.from(policy.publication.arrivalStampV2()),
            policyBody.validity.validFrom, policyBody.validity.validUntil)
        val catalogSelection = LocalArrivalCatalogSelectionV2(view.scope, catalog.signed.definition.catalogId,
            catalog.signed.definition.publicationId, catalog.signed.definition.revision,
            LocalArrivalSourceSelectionV2.from(catalog.active.arrivalStampV2()), LocalArrivalSourceSelectionV2.from(catalog.publication.arrivalStampV2()))
        return ArrivalPurchasingLoadedV2(policy, catalog, native, policySelection, catalogSelection,
            listOf(policy.active.arrivalStampV2(), policy.publication.arrivalStampV2(), catalog.active.arrivalStampV2(), catalog.publication.arrivalStampV2()),
            policyReader.resources + catalogReader.resources)
    }
}

private class ArrivalPurchasingLoadedV2(val policy: LoadedPurchasingPolicy, val catalog: LoadedCatalogRelease,
    val native: CatalogReleaseNativeCut, val policySelection: LocalArrivalPolicySelectionV2,
    val catalogSelection: LocalArrivalCatalogSelectionV2, val sources: List<LocalStockSourceStamp>, val resources: Set<RecoveryResourceKey>)

/** Validates completion, all captured chunks and supplemental membership without projecting bytes. */
internal suspend fun loadArrivalDocumentsV2(reader: PurchasingDocumentReader, supplierId: DomainId,
    documentIds: List<DomainId>, sources: MutableList<LocalStockSourceStamp>, resources: MutableSet<RecoveryResourceKey>,
    metadataOnly: Boolean = false): List<LocalArrivalDocumentSelectionV2> {
    val result = documentIds.map { id ->
        val file = reader.file(id) ?: throw LocalArrivalEvidenceRejectedV2(LocalArrivalFailureV2.EVIDENCE_REQUIRED)
        val body = file.value.body as PurchasingDocumentBody.Captured
        val intake = if (metadataOnly) reader.requireSource(body.intakeSource) else reader.requireFile(file)
        val request = body.intake.request
        arrivalCheckV2(request.supplierId == supplierId, LocalArrivalFailureV2.POLICY_MISMATCH)
        sources += file.row.arrivalStampV2(); sources += intake.row.arrivalStampV2()
        if (!metadataOnly) for (source in body.slotSources) sources += reader.requireSource(source).row.arrivalStampV2()
        body.intake.correctionSource?.let { sources += reader.requireSource(it).row.arrivalStampV2() }
        var parentHead: LocalStockSourceStamp? = null
        body.intake.parentSource?.let { source ->
            val parent = reader.requireSource(source)
            if (!metadataOnly) reader.requireFile(parent)
            val head = reader.head(parent)
            val attachments = head.value.body as PurchasingDocumentBody.Attachments
            check(attachments.files.count { it == purchasingDocumentSource(file) } == 1)
            sources += parent.row.arrivalStampV2(); sources += head.row.arrivalStampV2()
            parentHead = head.row.arrivalStampV2()
        }
        resources += reader.resources
        LocalArrivalDocumentSelectionV2(reader.scope, request.supplierId, file.value.id, file.row.revision, request.exactBytesDigest,
            request.byteLength, request.media.name, request.purpose.name, request.parentOriginalId,
            parentHead?.let(LocalArrivalSourceSelectionV2::from))
    }
    resources += reader.resources
    return result
}

internal fun LocalArrivalActionV1.purchasingActionV2(): PurchasingRuleActionV1 = when (this) {
    LocalArrivalActionV1.RECORD -> PurchasingRuleActionV1.ARRIVAL_RECORD
    LocalArrivalActionV1.QUALIFY -> PurchasingRuleActionV1.ARRIVAL_QUALIFY
    LocalArrivalActionV1.REJECT -> PurchasingRuleActionV1.ARRIVAL_REJECT
    LocalArrivalActionV1.DISPATCH -> PurchasingRuleActionV1.ARRIVAL_DISPATCH
}
private fun PurchasingApprovalRouteV1.arrivalApprovalRouteV2(): LocalArrivalApprovalRouteV2 = when (this) {
    is PurchasingApprovalRouteV1.Direct -> LocalArrivalApprovalRouteV2(LocalArrivalApprovalModeV2.DIRECT,
        performers = performers.arrivalActorSelectionV2())
    is PurchasingApprovalRouteV1.Separate -> LocalArrivalApprovalRouteV2(LocalArrivalApprovalModeV2.SEPARATE,
        requesters = requesters.arrivalActorSelectionV2(), approvers = approvers.arrivalActorSelectionV2())
    is PurchasingApprovalRouteV1.NamedSelf -> LocalArrivalApprovalRouteV2(LocalArrivalApprovalModeV2.NAMED_SELF,
        requesters = requesters.arrivalActorSelectionV2(), approvers = approvers.arrivalActorSelectionV2(),
        namedEmployeeIds = namedEmployeeIds)
}
private fun PurchasingActorSelectionV1.arrivalActorSelectionV2(): LocalArrivalActorSelectionV2 = when (this) {
    PurchasingActorSelectionV1.CurrentBranchGrantees -> LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES)
    is PurchasingActorSelectionV1.NamedEmployees -> LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.NAMED_EMPLOYEES, employeeIds)
}
/** Only the cost-free arrival profile selected above is described. Never infer missing limits. */
private fun PurchasingPolicyRuleV1.arrivalRuleDetailsV2(): String {
    check(limits is PurchasingLimitsV1.NoMonetaryEffect && acquisition == PurchasingAcquisitionProfileV1.NOT_APPLICABLE)
    return buildString {
        append("Rule ${ruleId.value}; delegation grant ${delegationGrantId.value}; action ${action.name}")
        append("\nLimits NO_MONETARY_EFFECT; acquisition ${acquisition.name}")
        append("\nSupplier selectors (exact IDs/revisions):")
        selection.suppliers.forEach { append("\n${it.supplierId.value} / ${it.revision.value}") }
        append("\nProduct selection is the explicit SKU/category union, not all products.")
        append("\nSKU selectors (SKU/revision; product/revision; catalog publication/revision):")
        selection.products.skus.forEach {
            append("\n${it.skuId.value} / ${it.skuRevision.value}; ${it.productId.value} / ${it.productRevision.value}; ")
            append("${it.catalog.publicationId.value} / ${it.catalog.revision.value}")
        }
        append("\nCategory selectors (category/revision; catalog publication/revision):")
        selection.products.categories.forEach {
            append("\n${it.categoryId.value} / ${it.revision.value}; ${it.catalog.publicationId.value} / ${it.catalog.revision.value}")
        }
        append("\nEvidence ${evidence.decision.name}; count ${evidence.minimumCount}–${evidence.maximumCount}")
        append("\nMedia ${evidence.media.joinToString { it.name }}; purposes ${evidence.purposes.joinToString { it.name }}")
        append("\nRetention ${evidence.retentionClassId.value} / ${evidence.retentionClassRevision.value}")
    }.also { check(it.encodeToByteArray(throwOnInvalidSequence = true).size <= 32_768) }
}
internal fun arrivalProductV2(item: LocalArrivalItemAuthorityV2): LocalArrivalProductReferenceV2 = LocalArrivalProductReferenceV2(
    item.value.skuId, item.value.skuRevision, item.value.productId, item.productRevision)
internal fun LocalArrivalProductReferenceV2.matchesV2(selected: PurchasingSkuReferenceV1, catalog: LocalArrivalCatalogSelectionV2): Boolean =
    skuId == selected.skuId && skuRevision == selected.skuRevision && productId == selected.productId && productRevision == selected.productRevision &&
        catalog.publicationId == selected.catalog.publicationId && catalog.revision == selected.catalog.revision
internal fun StoredAggregate.arrivalStampV2(): LocalStockSourceStamp = LocalStockSourceStamp(key, revision, digest)
private fun LocalNamesV1.arrivalNamesV2(): LocalArrivalNamesV1 = LocalArrivalNamesV1(english, arabic)
internal fun arrivalSourcesV2(values: List<LocalStockSourceStamp>): List<LocalStockSourceStamp> {
    check(values.groupBy { it.key }.values.all { it.distinct().size == 1 })
    val unique = values.distinct().sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
    arrivalCheckV2(unique.size in 1..LocalUnpricedArrivalProfileV2.MAX_PURCHASING_SOURCES, LocalArrivalFailureV2.CAPACITY_EXCEEDED)
    return unique
}
internal fun arrivalSourceResourcesV2(values: List<LocalStockSourceStamp>): Set<RecoveryResourceKey> = values.flatMap { stamp ->
    listOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, stamp.key.id), RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, stamp.key.id))
}.toSet()
internal fun arrivalCheckV2(condition: Boolean, failure: LocalArrivalFailureV2) {
    if (!condition) throw LocalArrivalEvidenceRejectedV2(failure)
}
private suspend fun arrivalConfigurationV2(view: CommandReadView, kind: String, id: DomainId): Pair<StoredAggregate, ConfigurationRecord>? {
    val key = LocalConfigurationProfile.key(kind, id)
    val row = view.aggregate(key) ?: return null
    check(row.key == key && sha256(row.payload.copyBytes()) == row.digest)
    return row to decodeRecord(view.scope, row)
}

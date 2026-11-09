package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Private actual-source data, never a command-provided catalog or a receiving capability. */
internal data class PurchasingCaptureItemV2(
    val definition: LocalPublishedSkuV1,
    val units: List<CatalogUnit>,
    val sources: PurchasingCaptureCatalogSourceV2,
    val resources: Set<RecoveryResourceKey>,
)

internal data class PurchasingCaptureItemResolutionV2(
    val item: PurchasingCaptureItemV2?, val rejection: String? = null, val sourceUnavailable: Boolean = false,
    val resources: Set<RecoveryResourceKey> = item?.resources.orEmpty(),
)

/**
 * The capture path requires both native unit and product/variant provenance. Legacy unqualified
 * SKU IDs cannot acquire typed authority by entering an invoice. Claimed conversion IDs/rates
 * and money remain documentary snapshots; none must equal today's catalog or approved PO terms.
 */
internal class PurchasingInvoiceCaptureCatalogAuthorityV2(
    private val signatures: CompanyPublicationSignatureVerifier,
    nativePublications: LocalCompanyBusinessPublications?,
) {
    private val units = LocalCatalogUnitAuthority(nativePublications)
    private val products = LocalCatalogProductAuthority(nativePublications)

    suspend fun nativeBefore(command: RuntimeHistoricalCommand): PurchasingCaptureNativeCatalogBeforeV2 =
        PurchasingCaptureNativeCatalogBeforeV2.read(command, signatures)

    suspend fun current(view: CommandReadView, skuId: DomainId, unitIds: Set<DomainId>): PurchasingCaptureItemResolutionV2 {
        val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, skuId))
            ?: return PurchasingCaptureItemResolutionV2(null, "purchasing.current_item_required")
        val record = decodeRecord(view.scope, row)
        val signed = record.signedSku()
        verify(view.scope, signed)
        val definition = signed.definition
        check(definition.skuId == skuId)
        val configuredResources = captureConfiguredResourcesV2(record, signed)
        sourceShapeRejection(definition)?.let { return PurchasingCaptureItemResolutionV2(null, it, resources = configuredResources) }
        val observed = view.originalNativeBusinessPublications()
            ?: return PurchasingCaptureItemResolutionV2(null, sourceUnavailable = true, resources = configuredResources)
        val unit = units.documentaryCurrent(view, signed, unitIds, observed)
        val product = products.current(view, signed, observed)
        val observedResources = configuredResources + unit.resources + product.resources
        if (unit.sourceUnavailable || product.sourceUnavailable)
            return PurchasingCaptureItemResolutionV2(null, sourceUnavailable = true, resources = observedResources)
        if (unit.rejection != null || unit.definitions == null)
            return PurchasingCaptureItemResolutionV2(null, "purchasing.unit_snapshot_required", resources = observedResources)
        if (product.rejection != null || product.definition == null || product.variantValues == null)
            return PurchasingCaptureItemResolutionV2(null, "purchasing.current_item_required", resources = observedResources)
        check(unit.definitions.map { it.id }.toSet() == unitIds && product.definition == definition.productDefinition &&
            product.variantValues == definition.variantValues)
        val published = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, definition.publicationId))
            ?: return PurchasingCaptureItemResolutionV2(null, sourceUnavailable = true, resources = observedResources)
        requirePublicationPairV2(view.scope, row, published)
        val unitSources = unit.publications.map { (unitId, publication) -> PurchasingCaptureUnitSourceV2(unitId, PurchasingSource.from(publication)) }
            .sortedBy { it.unitId.value }
        val unitResources = unit.publications.values.flatMap { publication ->
            listOf(priceResource(publication.key.id), priceResource(decodeRecord(view.scope, publication).authorizedBy))
        } + unitIds.map(::priceResource)
        return PurchasingCaptureItemResolutionV2(PurchasingCaptureItemV2(definition, unit.definitions,
            PurchasingCaptureCatalogSourceV2(PurchasingSource.from(row), PurchasingSource.from(published), unitSources),
            observedResources + priceResource(published.key.id) + priceResource(decodeRecord(view.scope, published).authorizedBy) + unitResources))
    }

    /** Same source rules on the core-created original BEFORE cut, with no live catalog view. */
    suspend fun historical(command: RuntimeHistoricalCommand, skuId: DomainId, unitIds: Set<DomainId>,
        native: suspend () -> PurchasingCaptureNativeCatalogBeforeV2): PurchasingCaptureItemResolutionV2 {
        val source = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, skuId))
            ?: return PurchasingCaptureItemResolutionV2(null, "purchasing.current_item_required")
        val record = decodeRecord(command.scope, source.row)
        val signed = record.signedSku()
        verify(command.scope, signed)
        val definition = signed.definition
        check(definition.skuId == skuId)
        val configuredResources = captureConfiguredResourcesV2(record, signed)
        sourceShapeRejection(definition)?.let { return PurchasingCaptureItemResolutionV2(null, it, resources = configuredResources) }
        val before = native()
        check(before.native[definition.publicationId] == signed && before.activated[definition.publicationId] == signed)
        val relevant = localCatalogProductRelevantDefinitions(definition.productId, definition.skuId,
            before.native.values.map { it.definition })
        val relevantIds = relevant.map { it.publicationId }.toSet()
        val productResources = localCatalogProductComparisonResources(relevant) + before.records
            .filter { it.signedSku().definition.publicationId in relevantIds }.flatMap { listOf(priceResource(it.id), priceResource(it.authorizedBy)) }
        // Live derives both unit and product source footprints before choosing the first refusal.
        val observedResources = configuredResources + productResources +
            localCatalogDocumentaryUnitHistoryResourcesV2(before.native.values.toList(), before.records)
        if (localCatalogUnitHistoryRejection(before.native.values.toList()) != null)
            return PurchasingCaptureItemResolutionV2(null, "purchasing.unit_snapshot_required", resources = observedResources)
        val carriers = localCatalogDocumentaryUnitCarriersV2(definition, unitIds, before.activated)
            ?: return PurchasingCaptureItemResolutionV2(null, "purchasing.unit_snapshot_required", resources = observedResources)
        val product = checkNotNull(definition.productDefinition)
        val pinned = relevant.mapNotNull { it.productDefinition }.filter { it.id == product.id && it.revision <= product.revision }
        if (localCatalogProductShapeRejection(definition) != null || localCatalogProductVersionHistoryRejection(pinned) != null)
            return PurchasingCaptureItemResolutionV2(null, "purchasing.current_item_required", resources = observedResources)
        val actualUnits = carriers.map { (unitId, publicationId) ->
            before.activated.getValue(publicationId).definition.unitDefinitions.orEmpty().single { it.id == unitId }
        }
        val unitSources = carriers.map { (unitId, publicationId) ->
            PurchasingCaptureUnitSourceV2(unitId, PurchasingSource.from(before.publications.getValue(publicationId)))
        }
        val published = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, definition.publicationId))
            ?: error("Original capture publication unavailable")
        requirePublicationPairV2(command.scope, source.row, published.row)
        val unitResources = carriers.values.flatMap { publicationId ->
            val publication = before.publications.getValue(publicationId)
            listOf(priceResource(publicationId), priceResource(decodeRecord(command.scope, publication).authorizedBy))
        } + unitIds.map(::priceResource)
        return PurchasingCaptureItemResolutionV2(PurchasingCaptureItemV2(definition, actualUnits,
            PurchasingCaptureCatalogSourceV2(PurchasingSource.from(source.row), PurchasingSource.from(published.row), unitSources),
            observedResources + priceResource(published.row.key.id) + priceResource(decodeRecord(command.scope, published.row).authorizedBy) + unitResources))
    }

    private fun verify(scope: BranchScope, signed: LocalSignedSkuV1) {
        val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
        try { check(signed.definition.company == scope.company() &&
            signatures.verify(scope.company(), signed.signingKeyId, bytes, signed.signatureHex)) }
        finally { bytes.fill(0) }
    }
}

/** Only the already decoded, signature-checked configured source supplies this footprint. */
private fun captureConfiguredResourcesV2(record: ConfigurationRecord, signed: LocalSignedSkuV1): Set<RecoveryResourceKey> =
    localCatalogProductComparisonResources(listOf(signed.definition)) + localCatalogUnitResources(signed.definition) +
        setOf(priceResource(record.id), priceResource(record.authorizedBy), priceResource(signed.signingKeyId))

private fun sourceShapeRejection(definition: LocalPublishedSkuV1): String? {
    if (definition.unitDefinitions == null || localCatalogUnitShapeRejection(definition) != null)
        return "purchasing.unit_snapshot_required"
    if (definition.productDefinition == null || definition.variantValues == null || localCatalogProductShapeRejection(definition) != null)
        return "purchasing.current_item_required"
    return null
}

/** Complete original native and activated membership; every present row has an earlier native join. */
internal class PurchasingCaptureNativeCatalogBeforeV2 private constructor(
    val native: Map<DomainId, LocalSignedSkuV1>, val activated: Map<DomainId, LocalSignedSkuV1>,
    val records: List<ConfigurationRecord>, val publications: Map<DomainId, StoredAggregate>,
) {
    companion object {
        suspend fun read(command: RuntimeHistoricalCommand, signatures: CompanyPublicationSignatureVerifier): PurchasingCaptureNativeCatalogBeforeV2 {
            val completed = command.completedNativeBusinessPublicationsBefore()
            check(completed.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
                completed.all { it.proposal.company == command.scope.company() } &&
                completed.map { it.originalActionId }.distinct().size == completed.size &&
                completed.map { it.proposal.publicationId }.distinct().size == completed.size)
            val native = mutableMapOf<DomainId, LocalSignedSkuV1>()
            for (output in completed) {
                if (output.proposal.kind != CompanyBusinessPublicationKind.CATALOG_SKU_V1) continue
                val signed = output.readNativeCompanySku()
                val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
                try { check(signed.definition.company == command.scope.company() &&
                    signatures.verify(command.scope.company(), signed.signingKeyId, bytes, signed.signatureHex)) }
                finally { bytes.fill(0) }
                check(native.put(signed.definition.publicationId, signed) == null)
            }
            val records = mutableListOf<ConfigurationRecord>()
            val publications = mutableMapOf<DomainId, StoredAggregate>()
            suspend fun members(kind: String): List<ConfigurationRecord> {
                val rows = command.aggregates(kind)
                check(rows.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS && rows.map { it.key }.distinct().size == rows.size)
                return rows.map { row ->
                    val before = command.aggregateSource(row.key)?.row ?: error("Original capture catalog membership unavailable")
                    check(before.revision == row.revision && before.schema == row.schema && before.digest == row.digest && before.payload == row.payload)
                    decodeRecord(command.scope, before).also {
                        records += it
                        if (kind == LocalConfigurationProfile.PUBLICATION) check(publications.put(row.key.id, before) == null)
                    }
                }
            }
            val activated = mutableMapOf<DomainId, LocalSignedSkuV1>()
            for (record in members(LocalConfigurationProfile.PUBLICATION)) {
                check(record.revision == Revision(0))
                val signed = record.signedSku()
                check(native[signed.definition.publicationId] == signed && activated.put(signed.definition.publicationId, signed) == null)
            }
            for (record in members(LocalConfigurationProfile.SKU)) {
                val signed = record.signedSku()
                check(activated[signed.definition.publicationId] == signed)
            }
            return PurchasingCaptureNativeCatalogBeforeV2(native, activated, records, publications)
        }
    }
}

/** Exact original configured SKU and immutable publication, including its author/time/policy. */
internal fun requirePublicationPairV2(scope: BranchScope, sku: StoredAggregate, published: StoredAggregate) {
    val record = decodeRecord(scope, sku)
    val signed = record.signedSku()
    check(sku.key.kind == LocalConfigurationProfile.SKU && sku.key.id == signed.definition.skuId &&
        published.key == LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, signed.definition.publicationId) &&
        published.revision == Revision(1) && decodeRecord(scope, published) ==
        record.copy(kind = LocalConfigurationProfile.PUBLICATION, id = published.key.id, revision = Revision(0)))
}

/**
 * An immutable publication retains all original unit/product facts after SKU/catalog changes.
 * Reconstruct only the exact old configured-row bytes from that original and its storage stamp;
 * this validates historical data and supplies no new authority for a submitted invoice.
 */
internal data class PurchasingCaptureCatalogSnapshotV2(val definition: LocalPublishedSkuV1, val units: List<CatalogUnit>)

internal suspend fun PurchasingReader.captureCatalogSnapshot(source: PurchasingCaptureCatalogSourceV2): PurchasingCaptureCatalogSnapshotV2 {
    check(source.skuSource.kind == LocalConfigurationProfile.SKU && source.publicationSource.kind == LocalConfigurationProfile.PUBLICATION &&
        source.skuSource.revision.value >= 1 && source.publicationSource.revision == Revision(1))
    val row = capturePublication(source.publicationSource.id)
    check(PurchasingSource.from(row) == source.publicationSource)
    val record = decodeRecord(scope, row)
    check(record.revision == Revision(0))
    val definition = record.signedSku().definition
    check(definition.skuId == source.skuSource.id && definition.publicationId == source.publicationSource.id && definition.company == scope.company())
    val configured = record.copy(kind = LocalConfigurationProfile.SKU, id = definition.skuId,
        revision = Revision(source.skuSource.revision.value - 1))
    check(sha256(encodeValue(configured, ConfigurationRecord.serializer()).copyBytes()).value == source.skuSource.digest &&
        sourceShapeRejection(definition) == null)
    check(source.unitSources.size in 1..17 && source.unitSources.map { it.unitId } ==
        source.unitSources.map { it.unitId }.distinct().sortedBy { it.value })
    val actualUnits = source.unitSources.map { unitSource ->
        val stamp = unitSource.publicationSource
        check(stamp.kind == LocalConfigurationProfile.PUBLICATION && stamp.revision == Revision(1))
        val publication = capturePublication(stamp.id)
        check(PurchasingSource.from(publication) == stamp)
        val original = decodeRecord(scope, publication)
        check(original.revision == Revision(0))
        val signed = original.signedSku()
        check(signed.definition.company == scope.company())
        signed.definition.unitDefinitions.orEmpty().single { it.id == unitSource.unitId }
    }
    check(actualUnits.all { unit -> definition.unitDefinitions.orEmpty().singleOrNull { it.id == unit.id }?.let { it == unit } != false })
    return PurchasingCaptureCatalogSnapshotV2(definition, actualUnits)
}

/** Identity, dimensions and declared precision are qualified; documentary rates/prices are not. */
internal fun purchasingCaptureGoodsRejection(goods: PurchaseGoodsV1, definition: LocalPublishedSkuV1,
    actualUnits: List<CatalogUnit>): String? {
    if (!definition.active || !definition.canPurchase || definition.productDefinition?.status != LocalProductStatusV1.ACTIVE ||
        goods.skuId != definition.skuId || goods.units.productId != definition.productId ||
        goods.units.baseUnitId != definition.baseUnitId || goods.units.baseScale != definition.quantityScale)
        return "purchasing.current_item_required"
    val purchase = actualUnits.singleOrNull { it.id == goods.units.purchaseUnitId } ?: return "purchasing.unit_snapshot_required"
    val base = actualUnits.singleOrNull { it.id == goods.units.baseUnitId } ?: return "purchasing.unit_snapshot_required"
    // Native catalog currently supports only same-dimension conversions. A raw evidence ID
    // cannot turn an unsupported cross-dimension claim into a qualified converted goods line.
    if (purchase.dimension != base.dimension || goods.units.dimensionalEvidenceId != null ||
        goods.quantity.scale > purchase.maximumScale || goods.units.baseScale > base.maximumScale)
        return "purchasing.unit_snapshot_required"
    if (definition.tracking == LocalTrackingV1.SERIAL) {
        val quantity = goods.baseQuantity()
        var oneUnit = 1L
        repeat(quantity.scale) { oneUnit *= 10L }
        if (quantity.scaled % oneUnit != 0L) return "purchasing.unit_snapshot_required"
    }
    return null
}

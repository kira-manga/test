package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.CatalogVersionRef
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProfileV1
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerializationException

internal data class LocalCatalogUnitResolution(
    val definitions: List<CatalogUnit>?,
    val rejection: String?,
    val sourceUnavailable: Boolean = false,
)

/** Exact native-backed activated carriers for documentary unit identities, not conversions. */
internal data class LocalCatalogDocumentaryUnitResolutionV2(
    val definitions: List<CatalogUnit>?, val publications: Map<DomainId, StoredAggregate> = emptyMap(),
    val rejection: String? = null, val sourceUnavailable: Boolean = false,
    val resources: Set<RecoveryResourceKey> = emptySet(),
)

/**
 * Uses the same original native owner's already refreshed data in the borrowed transaction.
 * No controller call, request-provided registry, permission or cached completeness is accepted.
 * A full authenticated observed native-local ledger is not company-global history or an
 * independent cold-start rollback anchor for an approved, never-activated ledger suffix.
 */
internal class LocalCatalogUnitAuthority(private val publications: LocalCompanyBusinessPublications?) {
    suspend fun activation(view: CommandReadView, signed: LocalSignedSkuV1,
        previous: LocalSignedSkuV1?): LocalCatalogUnitResolution {
        val sku = signed.definition
        localCatalogUnitShapeRejection(sku)?.let { return LocalCatalogUnitResolution(null, it) }
        if (sku.unitDefinitions == null) {
            return if (previous?.definition?.unitDefinitions != null)
                LocalCatalogUnitResolution(null, "catalog.unit_definitions_required")
            else LocalCatalogUnitResolution(null, null)
        }
        val history = readCurrentHistory(view, signed, view.originalNativeBusinessPublications()) ?: return unavailable()
        if (previous != null && history.activated[previous.definition.publicationId] != previous) return unavailable()
        localCatalogUnitHistoryRejection(history.native.values.toList())?.let {
            return LocalCatalogUnitResolution(null, it)
        }
        localCatalogConversionRevisionRejection(sku, history.activated.values.map { it.definition })?.let {
            return LocalCatalogUnitResolution(null, it)
        }
        return LocalCatalogUnitResolution(sku.unitDefinitions.toList(), null)
    }

    suspend fun current(view: CommandReadView, signed: LocalSignedSkuV1): LocalCatalogUnitResolution {
        if (signed.definition.unitDefinitions == null) return LocalCatalogUnitResolution(null, null)
        localCatalogUnitShapeRejection(signed.definition)?.let { return LocalCatalogUnitResolution(null, it) }
        return current(view, signed, publications?.currentCompletedSnapshot())
    }

    /** Capture uses its actual writer-frame native observation, not a later refreshed ledger. */
    suspend fun current(view: CommandReadView, signed: LocalSignedSkuV1,
        observed: NativeCompanyBusinessPublicationSnapshot?): LocalCatalogUnitResolution {
        val sku = signed.definition
        // Old literal conversions remain historical evidence, never new dimensional authority.
        if (sku.unitDefinitions == null) return LocalCatalogUnitResolution(null, null)
        localCatalogUnitShapeRejection(sku)?.let { return LocalCatalogUnitResolution(null, it) }
        val history = readCurrentHistory(view, signed, observed) ?: return unavailable()
        if (history.activated[sku.publicationId] != signed) return unavailable()
        localCatalogUnitHistoryRejection(history.native.values.toList())?.let {
            return LocalCatalogUnitResolution(null, it)
        }
        return LocalCatalogUnitResolution(sku.unitDefinitions.toList(), null)
    }

    /** A goods claim may use a genuine company unit absent from this SKU's configured rates. */
    suspend fun documentaryCurrent(view: CommandReadView, signed: LocalSignedSkuV1,
        unitIds: Set<DomainId>, observed: NativeCompanyBusinessPublicationSnapshot?): LocalCatalogDocumentaryUnitResolutionV2 {
        val sku = signed.definition
        if (sku.unitDefinitions == null) return LocalCatalogDocumentaryUnitResolutionV2(null, rejection = "catalog.unit_definitions_required")
        localCatalogUnitShapeRejection(sku)?.let { return LocalCatalogDocumentaryUnitResolutionV2(null, rejection = it) }
        val history = readCurrentHistory(view, signed, observed)
            ?: return LocalCatalogDocumentaryUnitResolutionV2(null, sourceUnavailable = true)
        val resources = localCatalogDocumentaryUnitHistoryResourcesV2(history.native.values.toList(), history.records)
        if (history.activated[sku.publicationId] != signed)
            return LocalCatalogDocumentaryUnitResolutionV2(null, sourceUnavailable = true, resources = resources)
        localCatalogUnitHistoryRejection(history.native.values.toList())?.let {
            return LocalCatalogDocumentaryUnitResolutionV2(null, rejection = it, resources = resources)
        }
        val carriers = localCatalogDocumentaryUnitCarriersV2(sku, unitIds, history.activated)
            ?: return LocalCatalogDocumentaryUnitResolutionV2(null, rejection = "catalog.unit_definitions_required", resources = resources)
        val definitions = carriers.map { (unitId, publicationId) ->
            history.activated.getValue(publicationId).definition.unitDefinitions.orEmpty().single { it.id == unitId }
        }
        return LocalCatalogDocumentaryUnitResolutionV2(definitions,
            carriers.mapValues { (_, publicationId) -> history.publications.getValue(publicationId) }, resources = resources)
    }

    private suspend fun readCurrentHistory(view: CommandReadView, target: LocalSignedSkuV1,
        observed: NativeCompanyBusinessPublicationSnapshot?): CurrentHistory? {
        val owner = publications ?: return null
        // Activation uses its recorded same-frame observation. Ordinary current reads
        // keep the existing refreshed trust path without recording per-sale manifests.
        val snapshot = observed ?: return null
        if (snapshot.company != view.scope.company() || snapshot.company != owner.trust.company ||
            snapshot.rootFingerprint != owner.trust.rootFingerprint) return null
        val native = readNative(snapshot, owner) ?: return null
        if (native[target.definition.publicationId] != target) return null
        val retained = readCompleteKind(view, LocalConfigurationProfile.PUBLICATION) ?: return null
        val activated = mutableMapOf<DomainId, LocalSignedSkuV1>()
        val records = mutableListOf<ConfigurationRecord>()
        for (row in retained) {
            val record = decodeRecord(view.scope, row)
            if (record.revision != Revision(0)) return null
            val signed = record.signedSku()
            if (native[signed.definition.publicationId] != signed) return null
            if (activated.put(signed.definition.publicationId, signed) != null) return null
            records += record
        }
        // Current pointers must retain their exact original publication too. A missing old
        // publication is uncertainty, not permission to invent the old raw ID's dimension.
        val current = readCompleteKind(view, LocalConfigurationProfile.SKU) ?: return null
        for (row in current) {
            val record = decodeRecord(view.scope, row)
            val signed = record.signedSku()
            if (activated[signed.definition.publicationId] != signed) return null
            records += record
        }
        return CurrentHistory(native, activated, retained.associateBy { it.key.id }, records)
    }

    private fun readNative(snapshot: NativeCompanyBusinessPublicationSnapshot,
        owner: LocalCompanyBusinessPublications): Map<DomainId, LocalSignedSkuV1>? {
        val values = mutableMapOf<DomainId, LocalSignedSkuV1>()
        for (output in snapshot.completedPublications) {
            if (output.proposal.kind != CompanyBusinessPublicationKind.CATALOG_SKU_V1) continue
            val signed = try { output.readNativeCompanySku() }
            catch (_: SerializationException) { return null }
            catch (_: IllegalArgumentException) { return null }
            val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
            val verified = try { owner.trust.verify(snapshot.company, signed.signingKeyId, bytes, signed.signatureHex) }
            finally { bytes.fill(0) }
            if (!verified || values.put(signed.definition.publicationId, signed) != null) return null
        }
        return values
    }

    private fun unavailable(): LocalCatalogUnitResolution =
        LocalCatalogUnitResolution(null, "catalog.unit_history_required", sourceUnavailable = true)

    private class CurrentHistory(val native: Map<DomainId, LocalSignedSkuV1>,
        val activated: Map<DomainId, LocalSignedSkuV1>, val publications: Map<DomainId, StoredAggregate>,
        val records: List<ConfigurationRecord>)
}

/** Actual complete unit-comparison sources remain dependencies even when no carrier qualifies. */
internal fun localCatalogDocumentaryUnitHistoryResourcesV2(native: List<LocalSignedSkuV1>,
    records: List<ConfigurationRecord>): Set<RecoveryResourceKey> = buildSet {
    for (signed in native) {
        val sku = signed.definition
        listOf(signed.signingKeyId, sku.publicationId, sku.skuId, sku.productId, sku.baseUnitId).forEach { add(priceResource(it)) }
        addAll(localCatalogUnitResources(sku))
        for (conversion in sku.conversions) {
            add(priceResource(conversion.id))
            add(priceResource(conversion.sourceUnitId)); add(priceResource(conversion.targetUnitId))
            conversion.dimensionalEvidenceId?.let { add(priceResource(it)) }
        }
    }
    for (record in records) {
        add(priceResource(record.id)); add(priceResource(record.authorizedBy))
    }
}

/** Pure deterministic choice after complete authenticated membership has been established. */
internal fun localCatalogDocumentaryUnitCarriersV2(target: LocalPublishedSkuV1, unitIds: Set<DomainId>,
    activated: Map<DomainId, LocalSignedSkuV1>): Map<DomainId, DomainId>? {
    check(unitIds.size in 1..17)
    val candidates = listOfNotNull(activated[target.publicationId]) + activated.values
        .filter { it.definition.publicationId != target.publicationId }.sortedBy { it.definition.publicationId.value }
    val carriers = mutableMapOf<DomainId, DomainId>()
    for (unitId in unitIds.sortedBy { it.value }) {
        val carrier = candidates.firstOrNull { member -> member.definition.unitDefinitions.orEmpty().any { it.id == unitId } } ?: return null
        carriers[unitId] = carrier.definition.publicationId
    }
    return carriers
}

/** Complete real keyset membership, never a limited first page or an empty fallback. */
private suspend fun readCompleteKind(view: CommandReadView, kind: String): List<StoredAggregate>? {
    val rows = mutableListOf<StoredAggregate>()
    var after: DomainId? = null
    while (true) {
        val page = try { view.aggregateKeys(kind, after, 50) }
        catch (_: UnsupportedOperationException) { return null }
        if (page.keys.size > 50 || page.keys.any { it.kind != kind } ||
            page.keys.map { it.id }.distinct().size != page.keys.size ||
            page.keys != page.keys.sortedBy { it.id.value } ||
            after?.let { cursor -> page.keys.any { it.id.value <= cursor.value } } == true ||
            (page.nextAfter != null && page.nextAfter != page.keys.lastOrNull()?.id)) return null
        if (rows.size + page.keys.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return null
        for (key in page.keys) {
            val row = view.aggregate(key) ?: return null
            if (row.key != key) return null
            rows += row
        }
        after = page.nextAfter ?: return rows
    }
}

internal fun localCatalogUnitShapeRejection(sku: LocalPublishedSkuV1): String? {
    val definitions = sku.unitDefinitions
        ?: return if (sku.conversions.isEmpty()) null else "catalog.unit_definitions_required"
    val units = definitions.associateBy { it.id }
    val base = units[sku.baseUnitId] ?: return "catalog.unit_definitions_required"
    if (sku.quantityScale > base.maximumScale) return "catalog.unit_precision_required"
    if (sku.tracking == LocalTrackingV1.SERIAL && (base.dimension != UnitDimension.COUNT || sku.quantityScale != 0))
        return "catalog.serial_unit_required"
    for (conversion in sku.conversions) {
        val source = units[conversion.sourceUnitId] ?: return "catalog.unit_definitions_required"
        val target = units[conversion.targetUnitId] ?: return "catalog.unit_definitions_required"
        if (source.dimension != target.dimension) return "catalog.cross_dimension_unavailable"
        if (conversion.dimensionalEvidenceId != null) return "catalog.conversion_evidence_invalid"
    }
    return null
}

/** Pure identity checks over actual authenticated contents supplied by the native/BEFORE joins. */
internal fun localCatalogUnitHistoryRejection(publications: List<LocalSignedSkuV1>): String? {
    val units = mutableMapOf<DomainId, CatalogUnit>()
    val codes = mutableMapOf<String, CatalogUnit>()
    val legacyIds = mutableSetOf<DomainId>()
    val conversions = mutableMapOf<CatalogVersionRef, ProductUnitConversion>()
    val conversionIdentities = mutableMapOf<DomainId, ProductUnitConversion>()
    for (signed in publications) {
        val sku = signed.definition
        val declaredIds = sku.unitDefinitions.orEmpty().map { it.id }.toSet()
        if (sku.baseUnitId !in declaredIds) legacyIds += sku.baseUnitId
        sku.conversions.forEach {
            if (it.sourceUnitId !in declaredIds) legacyIds += it.sourceUnitId
            if (it.targetUnitId !in declaredIds) legacyIds += it.targetUnitId
        }
        for (unit in sku.unitDefinitions.orEmpty()) {
            val old = units.put(unit.id, unit)
            if (old != null && old != unit) return "catalog.unit_definition_conflict"
            val oldCode = codes.put(unit.code.normalizedKey, unit)
            if (oldCode != null && oldCode != unit) return "catalog.unit_definition_conflict"
        }
        for (conversion in sku.conversions) {
            val value = conversion.domain(sku.productId)
            val identity = conversionIdentities.put(value.id, value)
            if (identity != null && (identity.productId != value.productId ||
                    identity.sourceUnitId != value.sourceUnitId || identity.targetUnitId != value.targetUnitId))
                return "catalog.conversion_identity_changed"
            val version = conversions.put(value.reference, value)
            if (version != null && version != value) return "catalog.conversion_version_changed"
        }
    }
    if (units.keys.any { it in legacyIds }) return "catalog.legacy_unit_identity"
    return null
}

internal fun localCatalogConversionRevisionRejection(sku: LocalPublishedSkuV1,
    previous: List<LocalPublishedSkuV1>): String? {
    val highWater = previous.flatMap { it.conversions }.groupBy { it.id }.mapValues { (_, values) -> values.maxOf { it.revision } }
    if (sku.conversions.any { conversion -> highWater[conversion.id]?.let { conversion.revision < it } == true })
        return "catalog.conversion_version_changed"
    return null
}

/** Signed unit/version identities are exact resources, not a wildcard authorization grant. */
internal fun localCatalogUnitResources(sku: LocalPublishedSkuV1): Set<RecoveryResourceKey> =
    if (sku.unitDefinitions == null) emptySet() else buildSet {
        sku.unitDefinitions.forEach { add(priceResource(it.id)) }
        sku.conversions.forEach { add(priceResource(it.id)) }
    }

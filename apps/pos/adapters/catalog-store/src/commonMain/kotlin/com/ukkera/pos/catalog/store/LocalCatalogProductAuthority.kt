package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProfileV1
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Exact adapter-owned wire body of the immutable product value. */
public object LocalPublishedProductSerializer : KSerializer<LocalPublishedProductV1> {
    override val descriptor: SerialDescriptor = LocalPublishedProductWire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: LocalPublishedProductV1) {
        encoder.encodeSerializableValue(LocalPublishedProductWire.serializer(), LocalPublishedProductWire(
            value.id, value.revision, value.names, value.categoryIds, value.variantAxes, value.attributes, value.status,
            value.allowedVariantCombinations))
    }
    override fun deserialize(decoder: Decoder): LocalPublishedProductV1 {
        val value = decoder.decodeSerializableValue(LocalPublishedProductWire.serializer())
        return LocalPublishedProductV1(value.id, value.revision, value.names, value.categoryIds,
            value.variantAxes, value.attributes, value.status, value.allowedVariantCombinations)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class LocalPublishedProductWire(
    val id: DomainId, val revision: Revision, val names: LocalNamesV1,
    val categoryIds: List<DomainId>, val variantAxes: List<LocalVariantAxisV1>,
    val attributes: List<LocalProductAttributeV1>, val status: LocalProductStatusV1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val allowedVariantCombinations: List<List<LocalVariantValueV1>>? = null,
)

public object LocalVariantAxisSerializer : KSerializer<LocalVariantAxisV1> {
    override val descriptor: SerialDescriptor = LocalVariantAxisWire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: LocalVariantAxisV1) {
        encoder.encodeSerializableValue(LocalVariantAxisWire.serializer(), LocalVariantAxisWire(value.id, value.code, value.values))
    }
    override fun deserialize(decoder: Decoder): LocalVariantAxisV1 {
        val value = decoder.decodeSerializableValue(LocalVariantAxisWire.serializer())
        return LocalVariantAxisV1(value.id, value.code, value.values)
    }
}

@Serializable
private data class LocalVariantAxisWire(val id: DomainId, val code: Code, val values: List<Code>)

/** Pure content selection, not proof that a supplied list is complete, native or authorized. */
public sealed interface LocalCatalogProductSelectionV1 {
    public data class Selected(public val definition: LocalPublishedProductV1) : LocalCatalogProductSelectionV1
    public data class NoVersion(public val legacyIdentityPresent: Boolean) : LocalCatalogProductSelectionV1
    public data class Conflict(public val code: String) : LocalCatalogProductSelectionV1
}

/**
 * The caller must first authenticate the actual completed native cut. Pending reviews do not
 * enter this list. SKU/catalog/storage revisions never select an independent product version.
 * Unrelated products' structural refusals do not poison selection for this product.
 */
public fun selectLocalCatalogProductVersion(productId: DomainId,
    completedDefinitions: List<LocalPublishedSkuV1>): LocalCatalogProductSelectionV1 {
    if (completedDefinitions.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        return LocalCatalogProductSelectionV1.Conflict("catalog.product_history_required")
    val relevant = localCatalogProductRelevantDefinitions(productId, null, completedDefinitions)
    localCatalogProductHistoryRejection(relevant)?.let { return LocalCatalogProductSelectionV1.Conflict(it) }
    val versions = relevant.mapNotNull { it.productDefinition }.filter { it.id == productId }
    val latest = versions.maxByOrNull { it.revision }
    return if (latest != null) LocalCatalogProductSelectionV1.Selected(latest)
    else LocalCatalogProductSelectionV1.NoVersion(relevant.any { it.productId == productId })
}

/** A signed extension is explicit, including the empty selection for a simple product. */
public fun localCatalogProductShapeRejection(sku: LocalPublishedSkuV1): String? {
    val product = sku.productDefinition
        ?: return if (sku.variantValues == null) null else "catalog.product_definition_required"
    if (product.id != sku.productId) return "catalog.product_identity_changed"
    if (product.categoryIds.distinct().size != product.categoryIds.size ||
        product.variantAxes.map { it.id }.distinct().size != product.variantAxes.size ||
        product.variantAxes.map { it.code }.distinct().size != product.variantAxes.size ||
        product.attributes.map { it.key }.distinct().size != product.attributes.size ||
        product.variantAxes.any { it.values.isEmpty() || it.values.distinct().size != it.values.size })
        return "catalog.product_definition_invalid"
    product.allowedVariantCombinations?.let { rows ->
        if (rows.isEmpty()) return "catalog.product_definition_invalid"
        val combinations = mutableSetOf<List<LocalVariantValueV1>>()
        for (row in rows) {
            if (row.map { it.axisId }.distinct().size != row.size) return "catalog.product_definition_invalid"
            val combination = row.associate { it.axisId to it.value }
            if (combination.keys != product.variantAxes.map { it.id }.toSet() ||
                product.variantAxes.any { combination[it.id] !in it.values } ||
                !combinations.add(row.sortedBy { it.axisId.value })) return "catalog.product_definition_invalid"
        }
    }
    val choices = sku.variantValues ?: return "catalog.variant_values_required"
    if (choices.map { it.axisId }.distinct().size != choices.size) return "catalog.variant_selection_invalid"
    val selected = choices.associate { it.axisId to it.value }
    if (selected.keys != product.variantAxes.map { it.id }.toSet() ||
        product.variantAxes.any { selected[it.id] !in it.values }) return "catalog.variant_selection_invalid"
    if (product.allowedVariantCombinations?.none { row -> row.associate { it.axisId to it.value } == selected } == true)
        return "catalog.variant_selection_invalid"
    if (sku.active && product.status == LocalProductStatusV1.ARCHIVED) return "catalog.archived_product"
    return null
}

/**
 * Exact identity/version checks over already authenticated completed contents. Callers select
 * the relevant product and same-SKU members before using this helper for a particular decision.
 * List order is part of each product body; variant identity is its axis/value mapping.
 */
public fun localCatalogProductHistoryRejection(completedDefinitions: List<LocalPublishedSkuV1>): String? {
    if (completedDefinitions.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        return "catalog.product_history_required"
    for (sku in completedDefinitions) localCatalogProductShapeRejection(sku)?.let { return it }
    val qualified = completedDefinitions.filter { it.productDefinition != null }
    val legacyProductIds = completedDefinitions.filter { it.productDefinition == null }.map { it.productId }.toSet()
    if (qualified.any { it.productId in legacyProductIds }) return "catalog.legacy_product_identity"
    val products = qualified.map { checkNotNull(it.productDefinition) }
    localCatalogProductVersionHistoryRejection(products)?.let { return it }
    val byProduct = products.distinct().groupBy { it.id }
    for ((_, members) in completedDefinitions.groupBy { it.skuId }) {
        if (members.none { it.productDefinition != null }) continue
        if (members.any { it.productDefinition == null }) return "catalog.legacy_product_identity"
        val first = members.first()
        if (members.any { it.productId != first.productId }) return "catalog.product_identity_changed"
        val identity = checkNotNull(first.variantValues).associate { it.axisId to it.value }
        if (members.any { checkNotNull(it.variantValues).associate { value -> value.axisId to value.value } != identity })
            return "catalog.variant_identity_changed"
        val ordered = members.sortedBy { it.skuRevision }
        for (index in 1 until ordered.size) {
            val before = ordered[index - 1]
            val after = ordered[index]
            val beforeProduct = checkNotNull(before.productDefinition)
            val afterProduct = checkNotNull(after.productDefinition)
            if (afterProduct.revision < beforeProduct.revision ||
                after.skuRevision == before.skuRevision && afterProduct != beforeProduct)
                return "catalog.product_version_changed"
        }
    }
    // Later product revisions retain completed SKU selections in their declared axes, values
    // and any explicit allowed matrix, including the identities of archived originals.
    for (sku in qualified) {
        val product = checkNotNull(sku.productDefinition)
        for (later in byProduct[product.id].orEmpty().filter { it.revision >= product.revision }) {
            for (choice in checkNotNull(sku.variantValues)) {
                val beforeAxis = product.variantAxes.single { it.id == choice.axisId }
                val afterAxis = later.variantAxes.singleOrNull { it.id == choice.axisId }
                if (afterAxis == null || beforeAxis.code != afterAxis.code || choice.value !in afterAxis.values)
                    return "catalog.variant_identity_changed"
            }
            val selected = checkNotNull(sku.variantValues).associate { it.axisId to it.value }
            if (later.allowedVariantCombinations?.none { row -> row.associate { it.axisId to it.value } == selected } == true)
                return "catalog.variant_identity_changed"
        }
    }
    val combinations = mutableMapOf<Pair<DomainId, List<LocalVariantValueV1>>, DomainId>()
    for (sku in qualified) {
        val combination = sku.productId to checkNotNull(sku.variantValues).sortedBy { it.axisId.value }
        val owner = combinations.put(combination, sku.skuId)
        if (owner != null && owner != sku.skuId) return "catalog.duplicate_variant_combination"
    }
    return null
}

/** Independent complete ordered bodies, beginning at the explicitly proved native genesis. */
internal fun localCatalogProductVersionHistoryRejection(products: List<LocalPublishedProductV1>): String? {
    val versions = mutableMapOf<Pair<DomainId, Revision>, LocalPublishedProductV1>()
    for (product in products) {
        val old = versions.put(product.id to product.revision, product)
        if (old != null && old != product) return "catalog.product_version_changed"
    }
    for (members in versions.values.groupBy { it.id }.values) {
        val ordered = members.sortedBy { it.revision }
        if (ordered.first().revision != Revision(0)) return "catalog.product_version_changed"
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1].revision
            if (previous.value == Long.MAX_VALUE || ordered[index].revision != previous.next())
                return "catalog.product_version_changed"
        }
    }
    return null
}

internal fun localCatalogProductRelevantDefinitions(productId: DomainId, skuId: DomainId?,
    definitions: List<LocalPublishedSkuV1>): List<LocalPublishedSkuV1> {
    val productMembers = definitions.filter { it.productId == productId || it.productDefinition?.id == productId }
    val skuIds = productMembers.map { it.skuId }.toSet() + listOfNotNull(skuId)
    return definitions.filter { it.productId == productId || it.productDefinition?.id == productId || it.skuId in skuIds }
}

internal fun localCatalogProductActivationRejection(sku: LocalPublishedSkuV1, previous: LocalPublishedSkuV1?,
    completedDefinitions: List<LocalPublishedSkuV1>): String? {
    localCatalogProductShapeRejection(sku)?.let { return it }
    if (sku.productDefinition == null && previous?.productDefinition != null) return "catalog.product_definition_required"
    localCatalogProductHistoryRejection(completedDefinitions)?.let { return it }
    val product = sku.productDefinition ?: return null
    val highWater = completedDefinitions.mapNotNull { it.productDefinition }.filter { it.id == product.id }.maxOfOrNull { it.revision }
    if (highWater != null && product.revision < highWater) return "catalog.product_version_changed"
    return null
}

/** Exact signed product/axis/category dependencies, never a category or stock grant. */
internal fun localCatalogProductResources(sku: LocalPublishedSkuV1): Set<RecoveryResourceKey> =
    if (sku.productDefinition == null && sku.variantValues == null) emptySet() else buildSet {
        add(priceResource(sku.productId))
        sku.productDefinition?.let { product ->
            add(priceResource(product.id))
            product.categoryIds.forEach { add(priceResource(it)) }
            product.variantAxes.forEach { add(priceResource(it.id)) }
        }
        sku.variantValues.orEmpty().forEach { add(priceResource(it.axisId)) }
    }

internal fun localCatalogProductComparisonResources(definitions: List<LocalPublishedSkuV1>): Set<RecoveryResourceKey> = buildSet {
    for (sku in definitions) {
        add(priceResource(sku.productId)); add(priceResource(sku.skuId)); add(priceResource(sku.publicationId))
        add(RecoveryResourceKey(RecoveryResourceKind.STOCK, sku.skuId))
        addAll(localCatalogProductResources(sku))
    }
}

internal data class LocalCatalogProductResolution(
    val definition: LocalPublishedProductV1?, val variantValues: List<LocalVariantValueV1>?, val rejection: String?,
    val sourceUnavailable: Boolean = false, val resources: Set<RecoveryResourceKey> = emptySet(),
)

/** Product authority belongs only to the actual same-frame native original membership. */
internal class LocalCatalogProductAuthority(private val publications: LocalCompanyBusinessPublications?) {
    suspend fun activation(view: CommandReadView, signed: LocalSignedSkuV1,
        previous: LocalSignedSkuV1?): LocalCatalogProductResolution {
        val sku = signed.definition
        val explicit = sku.productDefinition != null || sku.variantValues != null ||
            previous?.definition?.productDefinition != null || previous?.definition?.variantValues != null
        val snapshot = view.originalNativeBusinessPublications()
        val native = authenticatedNative(view, snapshot)
        if (native == null) return if (explicit) unavailable() else LocalCatalogProductResolution(null, null, null)
        val relevant = localCatalogProductRelevantDefinitions(sku.productId, sku.skuId, native.values.map { it.definition })
        if (!explicit && relevant.none { it.productDefinition != null || it.variantValues != null })
            return LocalCatalogProductResolution(null, null, null)
        val history = readCurrentHistory(view, signed, native, relevant) ?: return unavailable()
        if (previous != null && history.activated[previous.definition.publicationId] != previous) return unavailable()
        val rejection = localCatalogProductActivationRejection(sku, previous?.definition, relevant)
        return resolution(sku, rejection, history.resources)
    }

    suspend fun current(view: CommandReadView, signed: LocalSignedSkuV1): LocalCatalogProductResolution {
        val sku = signed.definition
        if (sku.productDefinition == null && sku.variantValues == null) return LocalCatalogProductResolution(null, null, null)
        return current(view, signed, publications?.currentCompletedSnapshot())
    }

    /** An operation that captures native dependencies must use its own original writer cut. */
    suspend fun current(view: CommandReadView, signed: LocalSignedSkuV1,
        observed: NativeCompanyBusinessPublicationSnapshot?): LocalCatalogProductResolution {
        val sku = signed.definition
        if (sku.productDefinition == null && sku.variantValues == null) return LocalCatalogProductResolution(null, null, null)
        val native = authenticatedNative(view, observed) ?: return unavailable()
        val relevant = localCatalogProductRelevantDefinitions(sku.productId, sku.skuId, native.values.map { it.definition })
        val history = readCurrentHistory(view, signed, native, relevant) ?: return unavailable()
        if (history.activated[sku.publicationId] != signed) return unavailable()
        // Metadata remains pinned to this exact activated original, not a later approved body.
        // Later candidates' combination/regression refusals cannot reinterpret that old pin.
        // This projection supplies no new activation, stock, purchasing or policy authority.
        val product = sku.productDefinition
        val pinnedVersions = relevant.mapNotNull { it.productDefinition }.filter {
            product != null && it.id == product.id && it.revision <= product.revision
        }
        val rejection = localCatalogProductShapeRejection(sku) ?: localCatalogProductVersionHistoryRejection(pinnedVersions)
        return resolution(sku, rejection, history.resources)
    }

    private fun authenticatedNative(view: CommandReadView,
        snapshot: NativeCompanyBusinessPublicationSnapshot?): Map<DomainId, LocalSignedSkuV1>? {
        val owner = publications ?: return null
        val observed = snapshot ?: return null
        if (observed.company != view.scope.company() || observed.company != owner.trust.company ||
            observed.rootFingerprint != owner.trust.rootFingerprint) return null
        val native = mutableMapOf<DomainId, LocalSignedSkuV1>()
        for (output in observed.completedPublications) {
            if (output.proposal.kind != CompanyBusinessPublicationKind.CATALOG_SKU_V1) continue
            val signed = try { output.readNativeCompanySku() }
            catch (_: SerializationException) { return null }
            catch (_: IllegalArgumentException) { return null }
            val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
            val verified = try { owner.trust.verify(observed.company, signed.signingKeyId, bytes, signed.signatureHex) }
            finally { bytes.fill(0) }
            if (!verified || native.put(signed.definition.publicationId, signed) != null) return null
        }
        return native
    }

    private suspend fun readCurrentHistory(view: CommandReadView, target: LocalSignedSkuV1,
        native: Map<DomainId, LocalSignedSkuV1>, relevant: List<LocalPublishedSkuV1>): ProductCurrentHistory? {
        if (native[target.definition.publicationId] != target) return null
        val relevantPublications = relevant.map { it.publicationId }.toSet()
        val resources = localCatalogProductComparisonResources(relevant).toMutableSet()
        fun track(record: ConfigurationRecord) {
            if (record.signedSku().definition.publicationId in relevantPublications) {
                resources += priceResource(record.id); resources += priceResource(record.authorizedBy)
            }
        }
        val retained = readCompleteProductKind(view, LocalConfigurationProfile.PUBLICATION) ?: return null
        val activated = mutableMapOf<DomainId, LocalSignedSkuV1>()
        for (row in retained) {
            val record = decodeRecord(view.scope, row)
            if (record.revision != Revision(0)) return null
            val signed = record.signedSku()
            if (native[signed.definition.publicationId] != signed || activated.put(signed.definition.publicationId, signed) != null) return null
            track(record)
        }
        val current = readCompleteProductKind(view, LocalConfigurationProfile.SKU) ?: return null
        for (row in current) {
            val record = decodeRecord(view.scope, row)
            val signed = record.signedSku()
            if (activated[signed.definition.publicationId] != signed) return null
            track(record)
        }
        return ProductCurrentHistory(activated, resources)
    }

    private fun resolution(sku: LocalPublishedSkuV1, rejection: String?, resources: Set<RecoveryResourceKey>): LocalCatalogProductResolution =
        LocalCatalogProductResolution(if (rejection == null) sku.productDefinition else null,
            if (rejection == null) sku.variantValues?.toList() else null, rejection, resources = resources.toSet())
    private fun unavailable(): LocalCatalogProductResolution =
        LocalCatalogProductResolution(null, null, "catalog.product_history_required", sourceUnavailable = true)
    private class ProductCurrentHistory(val activated: Map<DomainId, LocalSignedSkuV1>, val resources: Set<RecoveryResourceKey>)
}

/** Complete bounded real keyset membership, not a first-page or known-empty substitute. */
private suspend fun readCompleteProductKind(view: CommandReadView, kind: String): List<StoredAggregate>? {
    val rows = mutableListOf<StoredAggregate>()
    var after: DomainId? = null
    while (true) {
        val page = try { view.aggregateKeys(kind, after, 50) }
        catch (_: UnsupportedOperationException) { return null }
        if (page.keys.size > 50 || page.keys.any { it.kind != kind } ||
            page.keys.map { it.id }.distinct().size != page.keys.size || page.keys != page.keys.sortedBy { it.id.value } ||
            after?.let { cursor -> page.keys.any { it.id.value <= cursor.value } } == true ||
            page.nextAfter != null && page.nextAfter != page.keys.lastOrNull()?.id) return null
        if (rows.size + page.keys.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return null
        for (key in page.keys) {
            val row = view.aggregate(key) ?: return null
            if (row.key != key) return null
            rows += row
        }
        after = page.nextAfter ?: return rows
    }
}

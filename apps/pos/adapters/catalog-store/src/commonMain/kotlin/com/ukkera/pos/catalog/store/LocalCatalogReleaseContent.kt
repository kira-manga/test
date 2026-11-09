package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyBusinessPublicationProfileV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Canonical documentary coordinate only; computing it does not authenticate or activate a release. */
public fun localCatalogReleaseReference(definition: LocalPublishedCatalogReleaseV1): LocalCatalogReleaseReferenceV1 {
    val canonical = LocalCatalogReleaseCodecV1.canonicalPublication(definition)
    return try { LocalCatalogReleaseReferenceV1(definition.catalogId, definition.publicationId, definition.revision, sha256(canonical)) }
    finally { canonical.fill(0) }
}

/**
 * Pure complete-sequence content check. Supplied lists are not evidence of native ownership,
 * completeness or access. The caller first authenticates the actual completed original cut.
 * Each catalog has its own full revision-0 chain; another catalog cannot fill a missing version.
 * Empty supplied content has no contradiction but proves neither native absence nor membership.
 */
public fun localCatalogReleaseHistoryRejection(releases: List<LocalPublishedCatalogReleaseV1>): String? {
    if (releases.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return "catalog.release_history_required"
    if (releases.isEmpty()) return null
    if (releases.any { it.company != releases.first().company }) return "catalog.release_identity_changed"
    if (releases.map { it.publicationId }.distinct().size != releases.size) return "catalog.release_publication_changed"
    val publicationIds = releases.map { it.publicationId }.toSet()
    if (releases.any { it.catalogId in publicationIds }) return "catalog.release_identity_changed"
    for (catalog in releases.groupBy { it.catalogId }.values) {
        val ordered = catalog.sortedBy { it.revision }
        if (ordered.first().revision != Revision(0) || ordered.first().previous != null)
            return "catalog.release_version_changed"
        for (index in 1 until ordered.size) {
            val before = ordered[index - 1]
            val after = ordered[index]
            if (before.revision.value == Long.MAX_VALUE || after.revision != before.revision.next())
                return "catalog.release_version_changed"
            if (after.previous != localCatalogReleaseReference(before)) return "catalog.release_predecessor_changed"
        }
    }
    return null
}

/**
 * Pure content join over signed-envelope values previously authenticated by the caller. Signature
 * syntax or a supplied list never substitutes for the real native completion observation.
 */
public fun localCatalogReleaseMemberRejection(release: LocalPublishedCatalogReleaseV1,
    completedSkus: List<LocalSignedSkuV1>): String? {
    if (completedSkus.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        return "catalog.release_member_history_required"
    return localCatalogReleaseMemberContentRejection(release, completedSkus.map { it.definition })
}

/**
 * Definition-only form for content selection. All relevant completed product/SKU bodies enter
 * the real complete product-history gate, not just this release's selected singleton versions.
 * Missing, legacy, conflicting or gapped product provenance cannot qualify a manifest member.
 */
public fun localCatalogReleaseMemberContentRejection(release: LocalPublishedCatalogReleaseV1,
    completedDefinitions: List<LocalPublishedSkuV1>): String? {
    if (completedDefinitions.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        return "catalog.release_member_history_required"
    if (completedDefinitions.any { it.company != release.company }) return "catalog.release_member_changed"
    val selectedProducts = mutableMapOf<DomainId, LocalPublishedProductV1>()
    for (member in release.members) {
        val sku = completedDefinitions.singleOrNull { it.publicationId == member.publicationId }
            ?: return "catalog.release_member_required"
        val product = sku.productDefinition ?: return "catalog.product_definition_required"
        if (sku.skuId != member.skuId || sku.skuRevision != member.skuRevision || sku.productId != member.productId ||
            product.id != member.productId || product.revision != member.productRevision)
            return "catalog.release_member_changed"
        localCatalogProductShapeRejection(sku)?.let { return it }
        val canonical = LocalConfigurationCodec.canonicalPublication(sku)
        try {
            if (canonical.size !in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES || sha256(canonical) != member.contentDigest)
                return "catalog.release_member_changed"
        } finally { canonical.fill(0) }
        val prior = selectedProducts.put(product.id, product)
        if (prior != null && prior != product) return "catalog.product_version_changed"
    }
    val productIds = release.members.map { it.productId }.toSet()
    val relatedSkuIds = release.members.map { it.skuId }.toSet() + completedDefinitions.filter {
        it.productId in productIds || it.productDefinition?.id in productIds
    }.map { it.skuId }
    val relevant = completedDefinitions.filter {
        it.productId in productIds || it.productDefinition?.id in productIds || it.skuId in relatedSkuIds
    }
    return localCatalogProductHistoryRejection(relevant)
}

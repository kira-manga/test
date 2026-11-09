package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.sha256

/** Request for the existing original company owner's opt-in ceremony, never a new trust source. */
public fun makeNativeCatalogReleaseProposal(definition: LocalPublishedCatalogReleaseV1): CompanyBusinessPublicationProposal {
    val canonical = LocalCatalogReleaseCodecV1.canonicalPublication(definition)
    return try {
        CompanyBusinessPublicationProposal(definition.company, CompanyBusinessPublicationKind.CATALOG_RELEASE_V1,
            definition.publicationId, definition.catalogId, canonical)
    } finally { canonical.fill(0) }
}

/** Decode the exact retained head review without reconstructing or replacing a manifest member. */
public fun CompanyBusinessPublicationAction.readNativeCatalogReleaseDefinition(): LocalPublishedCatalogReleaseV1 =
    proposal.readCatalogReleaseDefinition()

/** Syntax/content join only; callers still require the actual native original cut and trust. */
public fun CompanyBusinessPublicationOutput.readNativeCatalogRelease(): LocalSignedCatalogReleaseV1 =
    LocalSignedCatalogReleaseV1(proposal.readCatalogReleaseDefinition(), signingKeyId, signatureHex)

private fun CompanyBusinessPublicationProposal.readCatalogReleaseDefinition(): LocalPublishedCatalogReleaseV1 {
    require(kind == CompanyBusinessPublicationKind.CATALOG_RELEASE_V1)
    val content = copyCanonicalContent()
    try {
        require(sha256(content) == contentDigest)
        val definition = LocalCatalogReleaseCodecV1.decodeCanonicalPublication(content)
        require(definition.company == company && definition.publicationId == publicationId && definition.catalogId == entityId)
        return definition
    } finally { content.fill(0) }
}

package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal

/** Descriptive request for the existing original native owner's separate publication ceremony. */
public fun LocalPublishedCompanyActivityProfileV1.toNativeCompanyActivityProposal(): CompanyBusinessPublicationProposal {
    val canonical = LocalCompanyActivityPublicationCodecV1.canonicalPublication(this)
    return try {
        CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1,
            publicationId, profileId, canonical)
    } finally { canonical.fill(0) }
}

/** Decodes the exact retained review supplied by the original native publication owner. */
public fun CompanyBusinessPublicationAction.readNativeCompanyActivityProfileDefinition(): LocalPublishedCompanyActivityProfileV1 =
    proposal.readCompanyActivityProfile()

/** Binds the retained native output's explicit key and signature to the exact reviewed content. */
public fun CompanyBusinessPublicationOutput.readNativeCompanyActivityProfile(): LocalSignedCompanyActivityProfileV1 =
    LocalSignedCompanyActivityProfileV1(proposal.readCompanyActivityProfile(), signingKeyId, signatureHex)

private fun CompanyBusinessPublicationProposal.readCompanyActivityProfile(): LocalPublishedCompanyActivityProfileV1 {
    require(kind == CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1)
    val content = copyCanonicalContent()
    try {
        val definition = LocalCompanyActivityPublicationCodecV1.decodeCanonicalPublication(content)
        require(definition.company == company && definition.publicationId == publicationId && definition.profileId == entityId)
        return definition
    } finally { content.fill(0) }
}

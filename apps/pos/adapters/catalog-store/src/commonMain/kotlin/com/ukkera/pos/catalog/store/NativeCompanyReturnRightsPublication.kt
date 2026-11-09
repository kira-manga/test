package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal

/** Documentary request for the original native owner's separate publication ceremony, not return eligibility. */
public fun LocalPublishedReturnRightsPolicy.toNativeCompanyReturnRightsProposal(): CompanyBusinessPublicationProposal {
    val canonical = LocalReturnRightsPublicationCodec.canonicalPublication(this)
    return try {
        CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.RETURN_RIGHTS_V1,
            publicationId, policyId, canonical)
    } finally { canonical.fill(0) }
}

/** Reads the exact retained native review; another publication purpose cannot become return-rights content. */
public fun CompanyBusinessPublicationAction.readNativeCompanyReturnRightsPolicyDefinition(): LocalPublishedReturnRightsPolicy =
    proposal.readReturnRightsPolicy()

/** Joins the completed native output's explicit key and signature to its exact reviewed definition. */
public fun CompanyBusinessPublicationOutput.readNativeCompanyReturnRightsPolicy(): LocalSignedReturnRightsPolicy =
    LocalSignedReturnRightsPolicy(proposal.readReturnRightsPolicy(), signingKeyId, signatureHex)

private fun CompanyBusinessPublicationProposal.readReturnRightsPolicy(): LocalPublishedReturnRightsPolicy {
    require(kind == CompanyBusinessPublicationKind.RETURN_RIGHTS_V1)
    val content = copyCanonicalContent()
    try {
        val definition = LocalReturnRightsPublicationCodec.decodeCanonicalPublication(content)
        require(definition.company == company && definition.publicationId == publicationId && definition.policyId == entityId)
        return definition
    } finally { content.fill(0) }
}

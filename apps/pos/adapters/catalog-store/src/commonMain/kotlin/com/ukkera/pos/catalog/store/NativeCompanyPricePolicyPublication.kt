package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal

/** Exact canonical content retained by the native head ceremony; not employee-supplied trust. */
public fun LocalPricePolicyEntryV1.toNativeCompanyPricePolicyProposal(): CompanyBusinessPublicationProposal {
    val bytes = LocalPricingCodec.canonicalPolicy(this)
    return try { CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.PRICE_POLICY_V1,
        publicationId, entryId, bytes) } finally { bytes.fill(0) }
}
public fun CompanyBusinessPublicationAction.readNativeCompanyPricePolicyDefinition(): LocalPricePolicyEntryV1 = proposal.readPricePolicy()
public fun CompanyBusinessPublicationOutput.readNativeCompanyPricePolicy(): LocalSignedPricePolicyEntryV1 =
    LocalSignedPricePolicyEntryV1(proposal.readPricePolicy(), signingKeyId, signatureHex)

private fun CompanyBusinessPublicationProposal.readPricePolicy(): LocalPricePolicyEntryV1 {
    require(kind == CompanyBusinessPublicationKind.PRICE_POLICY_V1)
    val bytes = copyCanonicalContent()
    val prefix = kind.domain.encodeToByteArray()
    try {
        require(bytes.size > prefix.size && prefix.indices.all { bytes[it] == prefix[it] })
        val value = localConfigurationJson.decodeFromString(LocalPricePolicyEntryV1.serializer(),
            bytes.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
        require(value.company == company && value.publicationId == publicationId && value.entryId == entityId)
        val canonical = LocalPricingCodec.canonicalPolicy(value)
        try { require(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        return value
    } finally { bytes.fill(0); prefix.fill(0) }
}

package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.NativeCompanyPublicationTrust
import com.ukkera.pos.types.DomainId

/** Only the actual native-local company's completed, exact-content root endorsements. */
public class NativeCompanyPublicationSignatureVerifier(
    private val trust: NativeCompanyPublicationTrust,
) : CompanyPublicationSignatureVerifier {
    override fun verify(company: CompanyScope, keyId: DomainId, canonicalDefinition: ByteArray, signatureHex: String): Boolean =
        trust.verify(company, keyId, canonicalDefinition, signatureHex)
}

/** Decode the exact retained review, not a new definition reconstructed from a UI draft. */
public fun CompanyBusinessPublicationAction.readNativeCompanySkuDefinition(): LocalPublishedSkuV1 = proposal.readSku()

/** No unsigned employee input, alternate root or caller-selected trust enters this join. */
public fun CompanyBusinessPublicationOutput.readNativeCompanySku(): LocalSignedSkuV1 =
    LocalSignedSkuV1(proposal.readSku(), signingKeyId, signatureHex)

private fun CompanyBusinessPublicationProposal.readSku(): LocalPublishedSkuV1 {
    require(kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1)
    val content = copyCanonicalContent()
    val prefix = kind.domain.encodeToByteArray()
    try {
        require(content.size > prefix.size && prefix.indices.all { content[it] == prefix[it] })
        val definition = localConfigurationJson.decodeFromString(LocalPublishedSkuV1.serializer(),
            content.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
        require(definition.company == company && definition.publicationId == publicationId && definition.skuId == entityId)
        val canonical = LocalConfigurationCodec.canonicalPublication(definition)
        try { require(canonical.contentEquals(content)) } finally { canonical.fill(0) }
        return definition
    } finally { content.fill(0); prefix.fill(0) }
}

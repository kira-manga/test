package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.DomainId
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Public trust anchor provisioned by the installer, never carried in an item import request. */
public class PinnedCompanyPublicationKey(
    public val company: CompanyScope,
    public val keyId: DomainId,
    encodedPublicKey: ByteArray,
) {
    internal val key: PublicKey
    init {
        require(encodedPublicKey.size in 32..1024)
        key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encodedPublicKey.copyOf()))
    }
}

/**
 * Actual local JCA signature verification; no network, embedded signing secret or accept-all
 * fallback. Pin enrollment and signer revocation are deployment/administration responsibilities.
 * Synthetic qualification anchors must be provisioned in an isolated synthetic namespace and
 * must never be described as the real company's central registration.
 */
public class JvmCompanyPublicationKeys(anchors: List<PinnedCompanyPublicationKey>) : CompanyPublicationSignatureVerifier {
    private val keys: Map<Pair<CompanyScope, DomainId>, PublicKey>
    init {
        require(anchors.size in 1..128)
        require(anchors.map { it.company to it.keyId }.distinct().size == anchors.size)
        keys = anchors.associate { (it.company to it.keyId) to it.key }
    }

    override fun verify(company: CompanyScope, keyId: DomainId, canonicalDefinition: ByteArray, signatureHex: String): Boolean {
        if (canonicalDefinition.size !in 1..65_536 || signatureHex.length != 128 ||
            !signatureHex.all { it in '0'..'9' || it in 'a'..'f' }) return false
        val key = keys[company to keyId] ?: return false
        val signature = ByteArray(64) { index -> signatureHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return try {
            Signature.getInstance("Ed25519").run { initVerify(key); update(canonicalDefinition); verify(signature) }
        } catch (_: Exception) { false }
    }
}

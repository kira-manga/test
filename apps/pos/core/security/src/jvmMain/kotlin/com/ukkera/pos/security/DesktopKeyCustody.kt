package com.ukkera.pos.security

/**
 * Windows/macOS non-exportable key custody addressed by an alias that the shared core derives from
 * a retained key intent of THIS namespace. Private/secret key material NEVER crosses this seam:
 * only public points, signatures, ciphertexts, tags and key-check values do. Windows supplies CNG
 * (P-256, AES-GCM) plus a DPAPI-protected HMAC secret; macOS supplies Keychain/Secure Enclave keys.
 *
 * Contract: `create*` refuses an alias that already exists (no replacement, no import); lookups
 * return null only for a genuinely absent alias and fail closed on any other native error; every
 * key is owned by the current OS principal and bound to its profile namespace.
 */
internal interface DesktopKeyCustody {
    /** Uncompressed P-256 point (65 bytes) of [alias], or null when absent. */
    fun p256Point(alias: String): ByteArray?
    /** Creates a fresh non-exportable P-256 signing key and returns its uncompressed point. */
    fun createP256(alias: String): ByteArray
    /** ECDSA P-256 / SHA-256 over [message]; raw 64-byte r||s. */
    fun signP256(alias: String, message: ByteArray): ByteArray

    /** Creates a fresh non-exportable AES-256-GCM key. */
    fun createAes(alias: String)
    /** Randomized 12-byte IV AES-256-GCM seal with a 128-bit tag; returns (nonce, ciphertext||tag). */
    fun sealAes(alias: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun openAes(alias: String, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray

    /** Creates a fresh non-exportable HMAC-SHA256 secret. */
    fun createHmac(alias: String)
    fun hmac(alias: String, message: ByteArray): ByteArray

    /**
     * Deterministic 32-byte key-check value of an existing symmetric key (AES or HMAC) or null when
     * absent; it never reveals the key. Platform rule: HMAC over the fixed check label for HMAC
     * secrets; for AES the GCM tag over the fixed check label as AAD with an all-zero IV and empty
     * plaintext (the only all-zero-IV use; seals always use random IVs), hashed to 32 bytes.
     */
    fun symmetricCheck(alias: String): ByteArray?

    companion object {
        const val CHECK_LABEL: String = "ukkera.desktop-key-check/v2"
    }
}

/** Alias grammar: profile namespace + retained key ID; never caller-chosen. */
internal object DesktopKeyAliases {
    fun provisioner(profile: SelfOwnedNativeProfileV1, keyId: com.ukkera.pos.types.DomainId): String =
        "ukkera.pos.${profile.targetDirectoryName}.provisioner.${keyId.value}"
    fun head(profile: SelfOwnedNativeProfileV1, keyId: com.ukkera.pos.types.DomainId): String =
        "ukkera.pos.${profile.targetDirectoryName}.head.${keyId.value}"
    fun target(profile: SelfOwnedNativeProfileV1, keyId: com.ukkera.pos.types.DomainId): String =
        "ukkera.pos.${profile.targetDirectoryName}.target.${keyId.value}"
}

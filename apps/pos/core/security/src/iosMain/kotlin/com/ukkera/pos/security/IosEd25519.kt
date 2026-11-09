package com.ukkera.pos.security

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit
import kotlin.coroutines.cancellation.CancellationException

/** Fixed Apple CryptoKit Ed25519 provider; no provider registry or caller-selected verifier. */
internal fun iosVerifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != 32 || signature.size != 64 || message.isEmpty()) return false
    return try {
        CryptographyProvider.CryptoKit.get(EdDSA)
            .publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, publicKey)
            .signatureVerifier()
            .tryVerifySignatureBlocking(message, signature)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

package com.ukkera.pos.security

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.coroutines.cancellation.CancellationException

/** Actual JCA Ed25519 over RFC 8410 SPKI; no caller-selected algorithm/provider or fallback. */
internal actual fun offlineInstallationVerifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != 32 || signature.size != 64 || message.size !in 1..(64 * 1024)) return false
    val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + publicKey
    return try {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(message)
        verifier.verify(signature)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    finally { spki.fill(0) }
}

internal actual fun offlineInstallationRandomChallenge(): ByteArray? {
    val bytes = ByteArray(OfflineInstallationFormat.CHALLENGE_BYTES)
    return try { SecureRandom().nextBytes(bytes); bytes }
    catch (cancelled: CancellationException) { bytes.fill(0); throw cancelled }
    catch (_: Exception) { bytes.fill(0); null }
}

internal actual fun offlineInstallationPasswordHasher(pepper: DeviceProtectedPepper,
    parameters: PasswordHashParameters): LocalPasswordHasher? = JvmLocalPasswordHasher(pepper, parameters)

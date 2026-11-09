package com.ukkera.pos.security

import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc8032.Ed25519
import kotlin.coroutines.cancellation.CancellationException

/** Fixed RFC8032 verification on API24+; never depends on platform Ed25519 registration. */
internal actual fun offlineInstallationVerifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != 32 || signature.size != 64 || message.size !in 1..(64 * 1024)) return false
    return try {
        Ed25519.validatePublicKeyFull(publicKey, 0) &&
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

internal actual fun offlineInstallationRandomChallenge(): ByteArray? {
    val bytes = ByteArray(OfflineInstallationFormat.CHALLENGE_BYTES)
    return try { SecureRandom().nextBytes(bytes); bytes }
    catch (cancelled: CancellationException) { bytes.fill(0); throw cancelled }
    catch (_: Exception) { bytes.fill(0); null }
}

internal actual fun offlineInstallationPasswordHasher(pepper: DeviceProtectedPepper,
    parameters: PasswordHashParameters): LocalPasswordHasher? = AndroidLocalPasswordHasher(pepper, parameters)

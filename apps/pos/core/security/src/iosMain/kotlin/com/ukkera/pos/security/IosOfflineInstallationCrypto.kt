package com.ukkera.pos.security

import kotlin.coroutines.cancellation.CancellationException

internal actual fun offlineInstallationVerifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (message.size !in 1..(64 * 1024)) return false
    return iosVerifyEd25519(publicKey, message, signature)
}

internal actual fun offlineInstallationRandomChallenge(): ByteArray? {
    val challenge = ByteArray(OfflineInstallationFormat.CHALLENGE_BYTES)
    return try {
        IosLocalAuthenticationEntropy.fill(challenge)
        challenge
    } catch (cancelled: CancellationException) {
        challenge.fill(0)
        throw cancelled
    } catch (_: Exception) {
        challenge.fill(0)
        null
    }
}

internal actual fun offlineInstallationPasswordHasher(
    pepper: DeviceProtectedPepper,
    parameters: PasswordHashParameters,
): LocalPasswordHasher? = IosLocalPasswordHasher(pepper, parameters)

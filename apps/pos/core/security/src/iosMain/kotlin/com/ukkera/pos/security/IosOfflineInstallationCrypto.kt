package com.ukkera.pos.security

/** No native Ed25519/CSPRNG/pepper-hasher adapter is supplied or qualified by this delivery. */
internal actual fun offlineInstallationVerifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = false
internal actual fun offlineInstallationRandomChallenge(): ByteArray? = null
internal actual fun offlineInstallationPasswordHasher(pepper: DeviceProtectedPepper,
    parameters: PasswordHashParameters): LocalPasswordHasher? = null

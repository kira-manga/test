package com.ukkera.pos.security

/** Fixed platform primitive, not an injected accepting verifier. Unsupported providers fail closed. */
internal expect fun offlineInstallationVerifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
internal expect fun offlineInstallationRandomChallenge(): ByteArray?
internal expect fun offlineInstallationPasswordHasher(pepper: DeviceProtectedPepper, parameters: PasswordHashParameters): LocalPasswordHasher?

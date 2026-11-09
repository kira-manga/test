package com.ukkera.pos.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256

/** iOS regression fixture glue; fabricated key material never qualifies Keychain/service custody. */
internal actual fun localPasswordRegressionHasher(
    pepper: DeviceProtectedPepper,
    deterministicSalt: Boolean,
): LocalPasswordHasher = if (deterministicSalt) {
    IosLocalPasswordHasher(pepper, entropy = LocalAuthenticationEntropy { bytes ->
        bytes.indices.forEach { bytes[it] = (it + 1).toByte() }
    })
} else {
    IosLocalPasswordHasher(pepper)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun localPasswordRegressionHmacSha256(message: ByteArray): ByteArray {
    val key = ByteArray(32) { (it + 1).toByte() }
    val tag = ByteArray(32)
    try {
        key.usePinned { keyBytes ->
            message.usePinned { messageBytes ->
                tag.usePinned { tagBytes ->
                    CCHmac(kCCHmacAlgSHA256, keyBytes.addressOf(0), key.size.toULong(),
                        messageBytes.addressOf(0), message.size.toULong(), tagBytes.addressOf(0))
                }
            }
        }
        return tag
    } finally {
        key.fill(0)
    }
}

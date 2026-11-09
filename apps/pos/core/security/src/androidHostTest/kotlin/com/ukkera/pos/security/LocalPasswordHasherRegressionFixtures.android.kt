package com.ukkera.pos.security

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Android-host regression fixture glue; synthetic keys never qualify Android protected storage. */
internal actual fun localPasswordRegressionHasher(
    pepper: DeviceProtectedPepper,
    deterministicSalt: Boolean,
): LocalPasswordHasher = if (deterministicSalt) {
    AndroidLocalPasswordHasher(pepper, random = SyntheticSaltRandom())
} else {
    AndroidLocalPasswordHasher(pepper)
}

internal actual fun localPasswordRegressionHmacSha256(message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256"))
    return mac.doFinal(message)
}

private class SyntheticSaltRandom : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) {
        bytes.indices.forEach { bytes[it] = (it + 1).toByte() }
    }
}

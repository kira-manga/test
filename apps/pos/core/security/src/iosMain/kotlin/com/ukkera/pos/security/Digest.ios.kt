package com.ukkera.pos.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256

/** Public CommonCrypto SHA-256 API; no copied cryptographic implementation or managed key file. */
@OptIn(ExperimentalForeignApi::class)
public actual fun sha256(bytes: ByteArray): PayloadDigest {
    val result = ByteArray(32)
    result.usePinned { output ->
        bytes.usePinned { input ->
            check(
                CC_SHA256(
                    if (bytes.isEmpty()) null else input.addressOf(0),
                    bytes.size.toUInt(),
                    output.addressOf(0).reinterpret(),
                ) != null,
            ) { "SHA-256 provider unavailable" }
        }
    }
    return result.hexDigest()
}

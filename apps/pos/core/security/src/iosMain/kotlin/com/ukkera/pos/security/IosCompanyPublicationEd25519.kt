package com.ukkera.pos.security

internal actual fun nativeBusinessPublicationVerifyEd25519(publicKey: ByteArray, content: ByteArray, signature: ByteArray): Boolean {
    if (content.size !in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES) return false
    return iosVerifyEd25519(publicKey, content, signature)
}

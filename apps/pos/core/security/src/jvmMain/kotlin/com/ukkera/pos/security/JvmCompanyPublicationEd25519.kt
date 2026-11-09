package com.ukkera.pos.security

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.coroutines.cancellation.CancellationException

internal actual fun nativeBusinessPublicationVerifyEd25519(publicKey: ByteArray, content: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != 32 || signature.size != 64 || content.size !in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES) return false
    val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + publicKey
    return try {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("Ed25519").run { initVerify(key); update(content); verify(signature) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    finally { spki.fill(0) }
}

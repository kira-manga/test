package com.ukkera.pos.security

import org.bouncycastle.math.ec.rfc8032.Ed25519
import kotlin.coroutines.cancellation.CancellationException

/** Fixed RFC8032 implementation on supported Android versions; no global provider registration. */
internal actual fun nativeBusinessPublicationVerifyEd25519(publicKey: ByteArray, content: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != 32 || signature.size != 64 || content.size !in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES) return false
    return try { Ed25519.validatePublicKeyFull(publicKey, 0) && Ed25519.verify(signature, 0, publicKey, 0, content, 0, content.size) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

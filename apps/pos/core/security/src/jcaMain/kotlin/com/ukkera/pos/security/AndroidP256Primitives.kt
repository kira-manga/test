package com.ukkera.pos.security

import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

/** Android compatibility facade; signed messages, curve checks and canonical signatures are unchanged. */
internal object AndroidP256Primitives {
    fun randomBytes(size: Int) = JvmP256Primitives.randomBytes(size)
    fun newId() = JvmP256Primitives.newId()
    fun hash(bytes: ByteArray) = JvmP256Primitives.hash(bytes)
    fun hashHex(bytes: ByteArray) = JvmP256Primitives.hashHex(bytes)
    fun same(left: ByteArray, right: ByteArray) = JvmP256Primitives.same(left, right)
    fun point(key: ECPublicKey) = JvmP256Primitives.point(key)
    fun publicKey(point: ByteArray) = JvmP256Primitives.publicKey(point)
    fun sign(key: PrivateKey, message: ByteArray) = JvmP256Primitives.sign(key, message)
    fun verify(publicPoint: ByteArray, message: ByteArray, raw: ByteArray) =
        JvmP256Primitives.verify(publicPoint, message, raw)
    fun signDocument(role: SelfOwnedDocumentRoleV1, body: ByteArray, key: PrivateKey) =
        JvmP256Primitives.signDocument(role, body, key)
    fun verifyDocument(document: SelfOwnedSignedDocumentV1, point: ByteArray) =
        JvmP256Primitives.verifyDocument(document, point)
    fun documentDigest(document: SelfOwnedSignedDocumentV1) = JvmP256Primitives.documentDigest(document)
    fun rootFingerprint(body: SelfOwnedRootBodyV1) = JvmP256Primitives.rootFingerprint(body)
}

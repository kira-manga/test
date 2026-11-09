package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.util.UUID

/** Fixed P-256/JCA primitives shared by Android and desktop native owners; no custody or authority. */
internal object JvmP256Primitives {
    private val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val a = p.subtract(BigInteger.valueOf(3))
    private val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val n = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val gx = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val gy = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
    // API 24 providers need not expose AlgorithmParameters("EC"). Use the exact
    // already-validated P-256 domain, not a provider-selected or weaker curve.
    private val parameters = ECParameterSpec(EllipticCurve(ECFieldFp(p), a, b), ECPoint(gx, gy), n, 1)
    private val halfN = n.shiftRight(1)
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray {
        require(size in 1..64)
        return ByteArray(size).also { random.nextBytes(it) }
    }

    fun newId(): DomainId {
        val bytes = randomBytes(16)
        return try {
            bytes[6] = ((bytes[6].toInt() and 15) or 64).toByte()
            bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            DomainId(UUID(buffer.long, buffer.long).toString())
        } finally { bytes.fill(0) }
    }

    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hashHex(bytes: ByteArray): String { val digest = hash(bytes); return try { selfOwnedHex(digest) } finally { digest.fill(0) } }
    fun same(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)

    fun point(key: ECPublicKey): ByteArray {
        requireCurve(key.params); requirePoint(key.w)
        val x = scalar32(key.w.affineX); val y = scalar32(key.w.affineY)
        return try { byteArrayOf(4) + x + y } finally { x.fill(0); y.fill(0) }
    }

    fun publicKey(point: ByteArray): ECPublicKey {
        require(point.size == 65 && point[0].toInt() == 4)
        val x = point.copyOfRange(1, 33); val y = point.copyOfRange(33, 65)
        val coordinate = try { ECPoint(BigInteger(1, x), BigInteger(1, y)) } finally { x.fill(0); y.fill(0) }
        requirePoint(coordinate)
        requireCurve(parameters)
        return (KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(coordinate, parameters)) as ECPublicKey)
            .also { requireCurve(it.params); requirePoint(it.w) }
    }

    private fun requireCurve(value: ECParameterSpec) {
        require((value.curve.field as? ECFieldFp)?.p == p && value.curve.a == a && value.curve.b == b &&
            value.order == n && value.cofactor == 1 && value.generator.affineX == gx && value.generator.affineY == gy)
    }

    private fun requirePoint(value: ECPoint) {
        require(value != ECPoint.POINT_INFINITY)
        val x = value.affineX; val y = value.affineY
        require(x.signum() >= 0 && x < p && y.signum() >= 0 && y < p)
        require(y.multiply(y).mod(p) == x.multiply(x).multiply(x).add(a.multiply(x)).add(b).mod(p))
    }

    fun sign(key: PrivateKey, message: ByteArray): ByteArray {
        require(key.algorithm == "EC" && message.size <= SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 65_536)
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); sign() }
        return try { fromDer(der) } finally { der.fill(0) }
    }

    fun verify(publicPoint: ByteArray, message: ByteArray, raw: ByteArray): Boolean = try {
        require(message.size <= SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 65_536)
        val key = publicKey(publicPoint)
        val der = toDer(raw)
        try { Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(der) } }
        finally { der.fill(0) }
    } catch (_: Exception) { false }

    fun signDocument(role: SelfOwnedDocumentRoleV1, body: ByteArray, key: PrivateKey): SelfOwnedSignedDocumentV1 {
        val message = SelfOwnedInstallationWireV1.signingMessage(role, body)
        val signature = try { sign(key, message) } finally { message.fill(0) }
        return try { SelfOwnedSignedDocumentV1(role, body, signature) } finally { signature.fill(0) }
    }

    fun verifyDocument(document: SelfOwnedSignedDocumentV1, point: ByteArray): Boolean {
        val body = document.body(); val signature = document.signature()
        val message = try { SelfOwnedInstallationWireV1.signingMessage(document.role, body) } finally { body.fill(0) }
        return try { verify(point, message, signature) } finally { message.fill(0); signature.fill(0) }
    }

    fun documentDigest(document: SelfOwnedSignedDocumentV1): String {
        val bytes = document.copyWireBytes()
        return try { hashHex(bytes) } finally { bytes.fill(0) }
    }

    /** Fingerprint the role-bound root BODY, not randomized ECDSA signature bytes. */
    fun rootFingerprint(body: SelfOwnedRootBodyV1): String {
        val bytes = SelfOwnedInstallationWireV1.encodeRoot(body)
        val message = try { SelfOwnedInstallationWireV1.signingMessage(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT, bytes) }
        finally { bytes.fill(0) }
        return try { hashHex(message) } finally { message.fill(0) }
    }

    /** Strict minimal DER, positive integers, exact sequence; then canonicalize S to low-S. */
    fun fromDer(bytes: ByteArray): ByteArray {
        require(bytes.size in 8..72 && bytes[0].toInt() == 0x30 && (bytes[1].toInt() and 255) == bytes.size - 2)
        var at = 2
        fun integer(): BigInteger {
            require(at + 2 <= bytes.size && bytes[at++].toInt() == 2)
            val length = bytes[at++].toInt() and 255
            require(length in 1..33 && length <= bytes.size - at)
            val first = bytes[at].toInt() and 255
            require(first < 128)
            if (length > 1 && first == 0) require((bytes[at + 1].toInt() and 128) != 0)
            val value = bytes.copyOfRange(at, at + length); at += length
            return try { BigInteger(1, value).also { require(it.signum() > 0 && it < n) } } finally { value.fill(0) }
        }
        val r = integer(); val originalS = integer(); require(at == bytes.size)
        val s = if (originalS > halfN) n.subtract(originalS) else originalS
        val rb = scalar32(r); val sb = scalar32(s)
        return try { rb + sb } finally { rb.fill(0); sb.fill(0) }
    }

    /** Normalize a native P1363 signature; parsed wire signatures must use [toDer] instead. */
    fun canonicalize(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        val rb = raw.copyOfRange(0, 32); val sb = raw.copyOfRange(32, 64)
        val r = try { BigInteger(1, rb) } finally { rb.fill(0) }
        val s = try { BigInteger(1, sb) } finally { sb.fill(0) }
        require(r.signum() > 0 && r < n && s.signum() > 0 && s < n)
        val canonicalR = scalar32(r); val canonicalS = scalar32(if (s > halfN) n.subtract(s) else s)
        return try { canonicalR + canonicalS } finally { canonicalR.fill(0); canonicalS.fill(0) }
    }

    /** Reject high-S wire signatures; native DER is reconstructed minimally, never guessed. */
    fun toDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        val rb = raw.copyOfRange(0, 32); val sb = raw.copyOfRange(32, 64)
        val r = try { BigInteger(1, rb) } finally { rb.fill(0) }
        val s = try { BigInteger(1, sb) } finally { sb.fill(0) }
        require(r.signum() > 0 && r < n && s.signum() > 0 && s <= halfN)
        val rd = r.toByteArray(); val sd = s.toByteArray()
        return try {
            byteArrayOf(0x30, (4 + rd.size + sd.size).toByte(), 2, rd.size.toByte()) + rd +
                byteArrayOf(2, sd.size.toByte()) + sd
        } finally { rd.fill(0); sd.fill(0) }
    }

    private fun scalar32(value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value.bitLength() <= 256)
        val encoded = value.toByteArray()
        return try {
            val first = if (encoded.size == 33 && encoded[0].toInt() == 0) 1 else 0
            require(encoded.size - first <= 32)
            ByteArray(32).also { encoded.copyInto(it, 32 - (encoded.size - first), first) }
        } finally { encoded.fill(0) }
    }
}

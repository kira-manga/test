package com.ukkera.pos.security.frozen.androidv1baseline

import com.ukkera.pos.security.ProtectedLocalStorageFormat
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture.hex
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture.unhex
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reference of the CURRENT Android V1 data-key operations in AndroidSelfOwnedLocalSecurity
 * (sealNative/openNative/pepperNative), replayed through javax.crypto with a SOFTWARE key so the
 * later operation-style refactor (sealStorage/openStorage/pepperTag) can be checked against exactly
 * the same Cipher/Mac results: "AES/GCM/NoPadding", 12-byte nonce, 128-bit tag, AAD supplied with
 * updateAAD before one doFinal, ciphertext = plaintext + 16; "HmacSHA256" one doFinal, 32 bytes.
 */
internal object AndroidV1DataKeyReference {
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(nonce.size == ProtectedLocalStorageFormat.NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(ProtectedLocalStorageFormat.TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext).also { check(it.size == plaintext.size + ProtectedLocalStorageFormat.TAG_BYTES) }
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(ProtectedLocalStorageFormat.TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun pepper(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message).also { check(it.size == 32) }
    }
}

/**
 * Frozen Android V1 crypto/boot-id baseline (WP-A). AES-GCM expectations are the published GCM
 * specification Test Case 16 (AES-256, 96-bit IV, with AAD); HMAC expectations are RFC 4231 Test
 * Case 2; boot IDs are computed from the documented `ukkera.android-boot-count/v1` UUIDv8
 * derivation and cross-checked against the private production function by reflection (harness
 * only). NOT independent (same author, owner instruction).
 */
class AndroidV1CryptoBaselineTest {
    private val gcmKey = unhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val gcmNonce = unhex("cafebabefacedbaddecaf888")
    private val gcmAad = unhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    private val gcmPlaintext = unhex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
        "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
    private val gcmCiphertextAndTag = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
        "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" + "76fc6ece0f4e1768cddf8853bb2d551b"

    @Test
    fun storageSealAndOpenMatchTheGcmSpecificationVector() {
        assertEquals(12, ProtectedLocalStorageFormat.NONCE_BYTES)
        assertEquals(16, ProtectedLocalStorageFormat.TAG_BYTES)
        val sealed = AndroidV1DataKeyReference.seal(gcmKey, gcmNonce, gcmAad, gcmPlaintext)
        assertEquals(gcmCiphertextAndTag, hex(sealed))
        assertContentEquals(gcmPlaintext, AndroidV1DataKeyReference.open(gcmKey, gcmNonce, gcmAad, sealed))
    }

    @Test
    fun storageOpenFailsClosedOnAnyAuthenticatedChange() {
        val sealed = AndroidV1DataKeyReference.seal(gcmKey, gcmNonce, gcmAad, gcmPlaintext)
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<AEADBadTagException> { AndroidV1DataKeyReference.open(gcmKey, gcmNonce, gcmAad, tampered) }
        val otherAad = gcmAad.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<AEADBadTagException> { AndroidV1DataKeyReference.open(gcmKey, gcmNonce, otherAad, sealed) }
        val otherNonce = gcmNonce.copyOf().also { it[11] = (it[11].toInt() xor 1).toByte() }
        assertFailsWith<AEADBadTagException> { AndroidV1DataKeyReference.open(gcmKey, otherNonce, gcmAad, sealed) }
    }

    @Test
    fun pepperMatchesRfc4231AndIsThirtyTwoBytes() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex(AndroidV1DataKeyReference.pepper("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())))
    }

    private fun documentedBootId(installationId: String, bootCount: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("ukkera.android-boot-count/v1\u0000$installationId\u0000$bootCount".encodeToByteArray())
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun productionBootId(installationId: String, bootCount: Int): String {
        val outer = Class.forName("com.ukkera.pos.security.AndroidSelfOwnedLocalSecurity")
        val companionClass = Class.forName("com.ukkera.pos.security.AndroidSelfOwnedLocalSecurity\$Companion")
        val method = (companionClass.declaredMethods + outer.declaredMethods)
            .single { it.name.startsWith("originalBootId") && it.parameterCount == 2 }
        method.isAccessible = true
        val receiver = if (Modifier.isStatic(method.modifiers)) null
            else outer.getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        return method.invoke(receiver, installationId, bootCount).toString()
    }

    @Test
    fun androidBootIdDerivationIsPinned() {
        val installation = "00000000-0000-4000-8000-000000000301"
        val expected = mapOf(0 to "191cc8ac-51a8-8031-8020-e10942e067ff", 42 to "885b3449-918b-8093-b24a-c1112da87dd6")
        for ((count, uuid) in expected) {
            assertEquals(uuid, documentedBootId(installation, count))
            assertEquals(uuid, productionBootId(installation, count))
        }
    }
}

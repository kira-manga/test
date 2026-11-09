package com.ukkera.pos.security.independent.androidp256publicpointv1

import com.ukkera.pos.security.AndroidP256Primitives
import java.math.BigInteger
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECPoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Six frozen adapter cases, 25 primary variants, using the real androidMain decoder.
 * Literal public inputs/oracles: android-p256-public-point-compatibility frozen fixtures-v1.json.
 * This Android host result is not native API24/API31 execution, AndroidKeyStore custody,
 * original private-key/identity/ciphertext preservation, enrollment or a workflow result.
 */
class AndroidP256PublicPointCompatibilityV1HostTest {
    @Test
    fun p256U01_exactFinitePointsAndFullDomain() {
        val existing = mutableListOf<Pair<ECPublicKey, ValidPoint>>()
        for (fixture in VALID_POINTS) {
            val id = "P256-U01/${fixture.id}"
            val key = decodeExact(fixture, id)
            for ((previous, previousFixture) in existing) assertExact(previous, previousFixture, "$id existing key")
            existing += key to fixture
        }
    }

    @Test
    fun p256U02_knownAnswerSha256WithEcdsa() {
        val existing = mutableListOf<Pair<ECPublicKey, ValidPoint>>()
        for (fixture in SIGNATURE_VARIANTS) {
            val id = "P256-U02/${fixture.id}"
            val point = validPoint(fixture.keyId)
            val key = decodeExact(point, id)
            val message = octets(fixture.messageHex)
            val signature = octets(fixture.signatureHex)
            val originalMessage = message.copyOf()
            val originalSignature = signature.copyOf()
            try {
                val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
                verifier.initVerify(key)
                verifier.update(message) // Exact message, not its already hashed digest.
                assertEquals(fixture.expectedVerify, verifier.verify(signature), "$id exact Boolean outcome")
            } finally {
                assertContentEquals(originalMessage, message, "$id message input unchanged")
                assertContentEquals(originalSignature, signature, "$id signature input unchanged")
                assertExact(key, point, "$id returned key unchanged")
                for ((previous, previousFixture) in existing) assertExact(previous, previousFixture, "$id existing key")
            }
            existing += key to point
        }
    }

    @Test
    fun p256U03_exactLengthRejectsWithIndependentGControls() =
        rejectAll("P256-U03", LENGTH_REJECTIONS, freshGAfterEach = true)

    @Test
    fun p256U04_onlyUncompressedEncoding() =
        rejectAll("P256-U04", ENCODING_REJECTIONS)

    @Test
    fun p256U05_outOfFieldCoordinatesRejectWithoutReduction() =
        rejectAll("P256-U05", RANGE_REJECTIONS)

    @Test
    fun p256U06_infinityAndInRangeOffCurvePointsReject() =
        rejectAll("P256-U06", CURVE_REJECTIONS)

    private fun decodeExact(fixture: ValidPoint, id: String): ECPublicKey {
        val input = octets(fixture.sec1Hex)
        val original = input.copyOf()
        return try {
            AndroidP256Primitives.publicKey(input).also { assertExact(it, fixture, id) }
        } finally {
            assertContentEquals(original, input, "$id SEC1 input unchanged")
        }
    }

    private fun rejectAll(caseId: String, fixtures: List<RejectedPoint>, freshGAfterEach: Boolean = false) {
        val g = validPoint("F-G")
        // An existing public-key control observes key immutability, not native custody.
        val existing = decodeExact(g, "$caseId existing-key control")
        for (fixture in fixtures) {
            val id = "$caseId/${fixture.id}"
            val input = octets(fixture.sec1Hex)
            val original = input.copyOf()
            assertEquals(fixture.byteCount, input.size, "$id literal byte count")
            try {
                // No exception class/text is an oracle: successful key return must fail.
                assertFails("$id explicit REJECT") { AndroidP256Primitives.publicKey(input) }
            } finally {
                assertContentEquals(original, input, "$id rejected SEC1 input unchanged")
                assertExact(existing, g, "$id existing key unchanged")
            }
            if (freshGAfterEach) decodeExact(g, "$id independent F-G control")
        }
    }

    private fun assertExact(key: ECPublicKey, fixture: ValidPoint, id: String) {
        assertPublicObservation(key, fixture, id)
        val point = AndroidP256Primitives.point(key)
        assertEquals(SEC1_BYTES, point.size, "$id exact SEC1 width")
        assertEquals(SEC1_PREFIX, point[0].toInt() and 0xff, "$id uncompressed prefix")
        assertContentEquals(octets(fixture.xHex), point.copyOfRange(1, 1 + COORDINATE_BYTES), "$id fixed-width x")
        assertContentEquals(octets(fixture.yHex), point.copyOfRange(1 + COORDINATE_BYTES, SEC1_BYTES), "$id fixed-width y")
        assertContentEquals(octets(fixture.sec1Hex), point, "$id whole exact SEC1 point")
        assertPublicObservation(key, fixture, "$id after point observation")
    }

    private fun assertPublicObservation(key: ECPublicKey, fixture: ValidPoint, id: String) {
        assertNotEquals(ECPoint.POINT_INFINITY, key.w, "$id finite affine point")
        assertEquals(unsigned(fixture.xHex), key.w.affineX, "$id exact unsigned x")
        assertEquals(unsigned(fixture.yHex), key.w.affineY, "$id exact unsigned y")
        val parameters = key.params
        val field = parameters.curve.field
        assertTrue(field is ECFieldFp, "$id prime field")
        assertEquals(FIELD_BITS, field.fieldSize, "$id field size")
        assertEquals(unsigned(P_HEX), (field as ECFieldFp).p, "$id p")
        assertEquals(unsigned(A_HEX), parameters.curve.a, "$id a")
        assertEquals(unsigned(B_HEX), parameters.curve.b, "$id b")
        assertEquals(unsigned(GENERATOR_X_HEX), parameters.generator.affineX, "$id generator x")
        assertEquals(unsigned(GENERATOR_Y_HEX), parameters.generator.affineY, "$id generator y")
        assertEquals(unsigned(ORDER_HEX), parameters.order, "$id subgroup order")
        assertEquals(COFACTOR, parameters.cofactor, "$id cofactor")
    }

    private fun validPoint(id: String): ValidPoint = VALID_POINTS.single { it.id == id }
    private fun unsigned(hex: String): BigInteger = BigInteger(1, octets(hex))
    private fun octets(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private data class ValidPoint(val id: String, val sec1Hex: String, val xHex: String, val yHex: String)
    private data class RejectedPoint(val id: String, val byteCount: Int, val sec1Hex: String)
    private data class SignatureVariant(val id: String, val keyId: String, val messageHex: String,
        val signatureHex: String, val expectedVerify: Boolean)

    private companion object {
        const val SEC1_BYTES = 65
        const val SEC1_PREFIX = 0x04
        const val COORDINATE_BYTES = 32
        const val FIELD_BITS = 256
        const val COFACTOR = 1
        const val P_HEX = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"
        const val A_HEX = "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc"
        const val B_HEX = "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b"
        const val GENERATOR_X_HEX = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        const val GENERATOR_Y_HEX = "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        const val ORDER_HEX = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        val VALID_POINTS = listOf(
            ValidPoint("F-G",
                "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
                "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
            ValidPoint("F-NEG-G",
                "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296b01cbd1c01e58065711814b583f061e9d431cca994cea1313449bf97c840ae0a",
                "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
                "b01cbd1c01e58065711814b583f061e9d431cca994cea1313449bf97c840ae0a"),
            ValidPoint("F-4G",
                "04e2534a3532d08fbba02dde659ee62bd0031fe2db785596ef509302446b030852e0f1575a4c633cc719dfee5fda862d764efc96c3f30ee0055c42c23f184ed8c6",
                "e2534a3532d08fbba02dde659ee62bd0031fe2db785596ef509302446b030852",
                "e0f1575a4c633cc719dfee5fda862d764efc96c3f30ee0055c42c23f184ed8c6"),
            ValidPoint("F-ZERO-X-FINITE",
                "04000000000000000000000000000000000000000000000000000000000000000066485c780e2f83d72433bd5d84a06bb6541c2af31dae871728bf856a174f93f4",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "66485c780e2f83d72433bd5d84a06bb6541c2af31dae871728bf856a174f93f4"),
            ValidPoint("F-43G",
                "04986ae2506f1ff104d04230861d8f4b498f4bc4c6d009b30f7544dc129b82d28d003cccc0a6460e0ae328a4d97d3c7b61d86fc6289c189f2525110c441bb07e97",
                "986ae2506f1ff104d04230861d8f4b498f4bc4c6d009b30f7544dc129b82d28d",
                "003cccc0a6460e0ae328a4d97d3c7b61d86fc6289c189f2525110c441bb07e97"),
        )

        val LENGTH_REJECTIONS = listOf(
            RejectedPoint("F-LEN-EMPTY", 0, ""),
            RejectedPoint("F-LEN-SHORT", 64, "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51"),
            RejectedPoint("F-LEN-LONG", 66, "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f500"),
        )

        val ENCODING_REJECTIONS = listOf(
            RejectedPoint("F-ENC-RAW", 64, "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
            RejectedPoint("F-ENC-COMPRESSED", 33, "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"),
            RejectedPoint("F-ENC-HYBRID", 65, "076b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
            RejectedPoint("F-ENC-UNDEFINED", 65, "056b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
        )

        val RANGE_REJECTIONS = listOf(
            RejectedPoint("F-RANGE-X-P", 65, "04ffffffff00000001000000000000000000000000ffffffffffffffffffffffff66485c780e2f83d72433bd5d84a06bb6541c2af31dae871728bf856a174f93f4"),
            RejectedPoint("F-RANGE-X-MAX", 65, "04ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
            RejectedPoint("F-RANGE-Y-P", 65, "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"),
            RejectedPoint("F-RANGE-Y-MAX", 65, "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
        )

        val CURVE_REJECTIONS = listOf(
            RejectedPoint("F-INFINITY-SEC1", 1, "00"),
            RejectedPoint("F-INFINITY-PADDED", 65, "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"),
            RejectedPoint("F-CURVE-ZERO", 65, "0400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"),
            RejectedPoint("F-CURVE-ALTERED", 65, "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f6"),
        )

        val SIGNATURE_VARIANTS = listOf(
            SignatureVariant("V-LOW-S", "F-G",
                "73796e7468657469632d616e64726f69642d703235362d7075626c69632d706f696e742d7631",
                "304402207cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997802200a471c043d0f3df9e663deff03b3743b4c61e0c3c169e5acd10d6b610e06c629", true),
            SignatureVariant("V-HIGH-S", "F-G",
                "73796e7468657469632d616e64726f69642d703235362d7075626c69632d706f696e742d7631",
                "304502207cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978022100f5b8e3fac2f0c207199c2100fc4c8bc4708519e9e5adb8d822ac5f61ee5c5f28", true),
            SignatureVariant("V-CHANGED-MESSAGE", "F-G",
                "73796e7468657469632d616e64726f69642d703235362d7075626c69632d706f696e742d763121",
                "304402207cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997802200a471c043d0f3df9e663deff03b3743b4c61e0c3c169e5acd10d6b610e06c629", false),
            SignatureVariant("V-CHANGED-SIGNATURE", "F-G",
                "73796e7468657469632d616e64726f69642d703235362d7075626c69632d706f696e742d7631",
                "304402207cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997802200a471c043d0f3df9e663deff03b3743b4c61e0c3c169e5acd10d6b610e06c628", false),
            SignatureVariant("V-WRONG-PUBLIC-KEY", "F-4G",
                "73796e7468657469632d616e64726f69642d703235362d7075626c69632d706f696e742d7631",
                "304402207cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997802200a471c043d0f3df9e663deff03b3743b4c61e0c3c169e5acd10d6b610e06c629", false),
        )
    }
}

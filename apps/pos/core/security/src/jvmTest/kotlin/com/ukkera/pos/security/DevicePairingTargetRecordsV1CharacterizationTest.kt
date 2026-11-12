package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization of the existing Android V1 target-only pairing record bytes, captured from
 * the unchanged encoder on 12 November 2026 BEFORE the desktop V2 variant was added. Any change
 * to these digests means persisted Android records changed and must be refused.
 * Authored by the implementer (not an independent oracle).
 */
class DevicePairingTargetRecordsV1CharacterizationTest {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    private fun digest(c: Char) = PayloadDigest(c.toString().repeat(64))
    private fun octets(size: Int, fill: Int) = SelfOwnedOctetsV1(ByteArray(size) { fill.toByte() }, size)
    private val scope = BranchScope(id(91), id(92), id(93))
    private fun hash(bytes: ByteArray) = sha256(bytes).value

    @Test
    fun identityBytesUnchanged() {
        val target = SelfOwnedTargetDescriptorV1("com.ukkera.pos", 10123, "a".repeat(64), "b".repeat(64))
        val namespace = TargetNamespace("/data/user/0/com.ukkera.pos/no_backup/" +
            SelfOwnedCompositionNames.devicePairingTargetDirectory(null), 10123,
            "0123abcd-0000-4000-8000-00000000abcd", 77, 78)
        val value = TargetIdentity(id(1), id(2), id(3), id(4), scope, listOf("cash", "print"), target, namespace,
            5, 123_456, octets(32, 7))
        assertEquals(EXPECTED_IDENTITY, hash(TargetWire.identity(value)))
        assertEquals(EXPECTED_IDENTITY, hash(TargetWire.identity(TargetWire.decodeIdentity(TargetWire.identity(value)))))
    }

    @Test
    fun keyIntentBytesUnchanged() {
        val point = ByteArray(65) { 4 }.also { it[1] = 9 }
        val value = TargetKeyIntent(digest('c'), 5, 223_456, SelfOwnedNativeSecurityV1.TRUSTED_ENVIRONMENT, SelfOwnedOctetsV1(point, 65))
        assertEquals(EXPECTED_KEY_INTENT, hash(TargetWire.keyIntent(value)))
    }

    @Test
    fun allocationAndResponseIntentBytesUnchanged() {
        val allocation = TargetResponseAllocation(1, id(5), octets(32, 3), digest('d'), digest('e'), digest('f'), null,
            octets(40, 1), 6, 323_456)
        assertEquals(EXPECTED_ALLOCATION, hash(TargetWire.allocation(allocation)))
        val intent = TargetResponseIntent(id(6), 0, digest('a'), digest('b'), 6, 423_456, octets(32, 2), octets(50, 5))
        assertEquals(EXPECTED_RESPONSE_INTENT, hash(TargetWire.responseIntent(intent)))
    }

    @Test
    fun sdkRecordBytesUnchanged() {
        val acquisition = TargetSdkAcquisition(id(7), digest('1'), digest('2'), AppId("ukkera.pos"), "Front counter",
            8, 523_456, octets(32, 4))
        assertEquals(EXPECTED_SDK_ACQUISITION, hash(TargetSdkWireV2.acquisition(acquisition)))
        val signing = TargetSdkSigningIntent(digest('3'), digest('4'), digest('5'), 8, 623_456, octets(60, 6))
        assertEquals(EXPECTED_SDK_SIGNING, hash(TargetSdkWireV2.signingIntent(signing)))
    }

    private companion object {
        const val EXPECTED_IDENTITY = "3d59e7dc90bc140f0f73f47c77c6b2bd88292e96363d3cd01891249ad4c2b35a"
        const val EXPECTED_KEY_INTENT = "1b91d3059a6d7bc27531b36b8a4623c4b9ce67c2680d017524dfe7d80f37427c"
        const val EXPECTED_ALLOCATION = "d85be8ad9db4b825260ca2cfb26ffb2eaec65c0d3ab633991a05bc6e1a051029"
        const val EXPECTED_RESPONSE_INTENT = "f8047423f6cdf35112d33631e0b02f549b85dbbb23dd18e69eb5040d7552ec61"
        const val EXPECTED_SDK_ACQUISITION = "f81a17e6e356801a32d62a77ffe0962a813ab3ec7d88aad158b65edc0e3183a7"
        const val EXPECTED_SDK_SIGNING = "eb4aba894aa265b14541096f57c5736d6e24904bce8ba292c792de828918e992"
    }
}

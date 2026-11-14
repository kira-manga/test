package com.ukkera.pos.security.frozen.mastersdkoriginalv2

import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import com.ukkera.pos.security.LocalMasterSdkOriginalV2
import com.ukkera.pos.security.SelfOwnedAndroidInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityFactoryV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedInstalledTargetPlatformV1
import com.ukkera.pos.security.SelfOwnedMacServiceIdentityV2
import com.ukkera.pos.security.SelfOwnedNativeInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import com.ukkera.pos.security.independent.masterboundpairing.MasterBoundPairingDataFixture as F
import com.ukkera.pos.security.independent.masterboundpairing.SupportingPairingId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Frozen expectations for the additive Windows/macOS Master SDK original (V2) and the shared
 * [LocalMasterSdkOriginal] decode dispatch, authored on 13 November 2026 BEFORE the shared
 * interface existed and before any desktop Master producer. Expected framing prefixes are written
 * from the documented layout (4-byte big-endian length + UTF-8 per string), not rendered by the
 * code under test. NOT independent (same author, owner instruction). Android V1 bytes must not
 * change and must still decode as exactly V1.
 */
class MasterSdkOriginalV2FrozenTest {
    private val windows = SelfOwnedWindowsPackagedAppIdentityV2("Ukkera.POS.Test_n9kkbga0qmnm6",
        "Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS")
    private val mac = SelfOwnedMacServiceIdentityV2("com.ukkera.pos.local.desktop.master", "ABCDE12345", "e".repeat(64))

    private fun binding(target: Any): SelfOwnedNativeInstalledTargetBindingV1 {
        val result = when (target) {
            is SelfOwnedWindowsPackagedAppIdentityV2 ->
                SelfOwnedInstalledTargetBindingV1.accept(SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI, target)
            is SelfOwnedMacServiceIdentityV2 ->
                SelfOwnedInstalledTargetBindingV1.accept(SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE, target)
            else -> error("fixture")
        }
        return assertIs<SelfOwnedNativeInstalledTargetBindingV1>(assertIs<SelfOwnedInstalledIdentityResultV2.Data<*>>(result).value)
    }

    private fun v2(target: SelfOwnedNativeInstalledTargetBindingV1 = binding(windows)): LocalMasterSdkOriginalV2 =
        LocalMasterSdkOriginalV2.capture(F.id(SupportingPairingId.MASTER_ACQUISITION), F.allocation(), target,
            F.master().appId, F.masterPeer, F.masterFingerprint, F.masterQr, "Master desktop")

    private fun frame(vararg strings: String): ByteArray = strings.fold(ByteArray(0)) { acc, s ->
        val utf8 = s.encodeToByteArray()
        acc + byteArrayOf((utf8.size ushr 24).toByte(), (utf8.size ushr 16).toByte(), (utf8.size ushr 8).toByte(), utf8.size.toByte()) + utf8
    }

    @Test
    fun androidV1BytesStillDecodeAsExactlyV1WithAnAndroidBinding() {
        val v1 = F.master()
        val decoded = LocalMasterSdkOriginal.decode(v1.copyWireBytes())
        assertIs<LocalMasterSdkOriginalV1>(decoded)
        assertContentEquals(v1.copyWireBytes(), decoded.copyWireBytes())
        assertEquals(v1.descriptorDigest, decoded.descriptorDigest)
        assertEquals(1, decoded.formatVersion)
        val android = assertIs<SelfOwnedAndroidInstalledTargetBindingV1>(decoded.installedTarget)
        assertEquals(SelfOwnedInstalledTargetPlatformV1.ANDROID, android.platform)
        assertEquals(F.application.applicationId, android.target.applicationId)
        assertEquals(F.application.apkSignerSha256, android.target.apkSignerSha256)
        assertTrue(decoded.installedTarget.matches(SelfOwnedInstalledTargetBindingV1.android(F.application)))
    }

    @Test
    fun windowsAndMacV2RoundTripThroughTheSharedDecoder() {
        for ((target, platform) in listOf(binding(windows) to SelfOwnedInstalledTargetPlatformV1.WINDOWS,
                binding(mac) to SelfOwnedInstalledTargetPlatformV1.MACOS)) {
            val original = v2(target)
            val decoded = LocalMasterSdkOriginal.decode(original.copyWireBytes())
            assertIs<LocalMasterSdkOriginalV2>(decoded)
            assertEquals(2, decoded.formatVersion)
            assertContentEquals(original.copyWireBytes(), decoded.copyWireBytes())
            assertEquals(original.descriptorDigest, decoded.descriptorDigest)
            assertEquals(platform, decoded.installedTarget.platform)
            assertTrue(decoded.installedTarget.matches(target))
            assertEquals(F.masterPeer, decoded.peerId)
            assertEquals(F.masterQr, decoded.pairingQr)
            assertEquals("Master desktop", decoded.exactDeviceName)
            assertEquals(F.allocation().coordinates.deviceId, decoded.allocation.coordinates.deviceId)
            assertTrue(decoded.matchesSdkTransportIdentity(F.master().appId, F.masterPeer, F.masterFingerprint, F.masterQr))
        }
    }

    @Test
    fun v2FramingHasItsOwnDisjointDomainAndPurpose() {
        val bytes = v2().copyWireBytes()
        val prefix = frame("ukkera.installation/self-owned-local-p256/v1", "original-native-master-sdk-identity-data/v2",
            "ORIGINAL_NATIVE_MASTER_SDK_X25519_IDENTITY_V2")
        assertContentEquals(prefix, bytes.copyOf(prefix.size))
        val v1Prefix = frame("ukkera.installation/self-owned-local-p256/v1", "original-native-master-sdk-identity-data/v1")
        assertContentEquals(v1Prefix, F.master().copyWireBytes().copyOf(v1Prefix.size))
    }

    @Test
    fun versionSpecificDecodersRefuseTheOtherVersion() {
        assertFails { LocalMasterSdkOriginalV1.decode(v2().copyWireBytes()) }
        assertFails { LocalMasterSdkOriginalV2.decode(F.master().copyWireBytes()) }
    }

    @Test
    fun sharedDecoderRefusesTrailingUnknownAndEmptyInput() {
        for (bytes in listOf(F.master().copyWireBytes(), v2().copyWireBytes())) {
            assertFails { LocalMasterSdkOriginal.decode(bytes + 0) }
            assertFails { LocalMasterSdkOriginal.decode(bytes.copyOf(bytes.size - 1)) }
        }
        val unknown = v2().copyWireBytes().also { bytes ->
            val domain = "original-native-master-sdk-identity-data/v2".encodeToByteArray()
            val at = bytes.indexOfSlice(domain)
            bytes[at + domain.size - 1] = '3'.code.toByte()
        }
        assertFails { LocalMasterSdkOriginal.decode(unknown) }
        assertFails { LocalMasterSdkOriginal.decode(ByteArray(0)) }
        assertFails { LocalMasterSdkOriginal.decode(ByteArray(LocalMasterSdkOriginalV2.MAXIMUM_ORIGINAL_BYTES + 1)) }
    }

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int =
        (0..size - slice.size).first { start -> slice.indices.all { this[start + it] == slice[it] } }
}

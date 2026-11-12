package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Frozen expectations for the desktop (Windows/macOS Client) target-only pairing records,
 * authored BEFORE the implementation on 12 November 2026. NOT independent: same author as the
 * implementation, by explicit owner instruction. Android V1 bytes are separately frozen by
 * [DevicePairingTargetRecordsV1CharacterizationTest].
 */
class DevicePairingTargetRecordsDesktopV2FrozenTest {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    private fun digest(c: Char) = PayloadDigest(c.toString().repeat(64))
    private fun octets(size: Int, fill: Int) = SelfOwnedOctetsV1(ByteArray(size) { fill.toByte() }, size)
    private val scope = BranchScope(id(91), id(92), id(93))
    private val boot = TargetBoot.Desktop(3, "6f1c2a3b-0000-4000-8000-0000000000aa")
    private val windowsApp = SelfOwnedWindowsPackagedAppIdentityV2("Ukkera.POS.Test_n9kkbga0qmnm6",
        "Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS")
    private val windowsNamespace = TargetDesktopNamespaceV2(SelfOwnedNativePlatformV2.WINDOWS,
        "C:\\Users\\cashier\\AppData\\Local\\Packages\\Ukkera.POS.Test_n9kkbga0qmnm6\\LocalState\\" +
            SelfOwnedCompositionNames.devicePairingTargetDirectory(null),
        "S-1-5-21-1000-2000-3000-1001", "0123abcd-0000-4000-8000-00000000abcd",
        "00000000000000000000000000000011", "00000000000000000000000000000012")

    private fun desktopIdentity(origin: TargetOrigin = TargetOrigin.Desktop(windowsApp, windowsNamespace),
        osBoot: TargetBoot = boot) = TargetIdentity(id(1), id(2), id(3), id(4), scope, listOf("cash"),
        origin, osBoot, 10_000, octets(32, 7))

    @Test
    fun desktopBootRequiresCanonicalSessionAndOrdinal() {
        assertFailsWith<IllegalArgumentException> { TargetBoot.Desktop(-1, "6f1c2a3b-0000-4000-8000-0000000000aa") }
        assertFailsWith<IllegalArgumentException> { TargetBoot.Desktop(0, "6F1C2A3B-0000-4000-8000-0000000000AA") }
        assertFailsWith<IllegalArgumentException> { TargetBoot.Desktop(0, "not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { TargetBoot.Android(-1) }
    }

    @Test
    fun desktopNamespaceGrammarIsPlatformSpecific() {
        // macOS owner is a numeric uid and a POSIX path; Windows owner is an account SID.
        TargetDesktopNamespaceV2(SelfOwnedNativePlatformV2.MACOS,
            "/Users/cashier/Library/Application Support/com.ukkera.pos/" +
                SelfOwnedCompositionNames.devicePairingTargetDirectory(null),
            "501", "0123abcd-0000-4000-8000-00000000abcd", "44", "45")
        assertFailsWith<IllegalArgumentException> { windowsNamespace.copy(owner = "501") }
        assertFailsWith<IllegalArgumentException> { windowsNamespace.copy(lockId = windowsNamespace.directoryId) }
        assertFailsWith<IllegalArgumentException> { windowsNamespace.copy(canonicalPath = "C:\\elsewhere\\target") }
    }

    @Test
    fun desktopIdentityRoundTripsCanonically() {
        val bytes = TargetWire.identity(desktopIdentity())
        assertContentEquals(bytes, TargetWire.identity(TargetWire.decodeIdentity(bytes, TargetWireVariant.DESKTOP_V2)))
    }

    @Test
    fun variantsNeverDecodeEachOther() {
        val desktop = TargetWire.identity(desktopIdentity())
        assertFails { TargetWire.decodeIdentity(desktop) }
        val android = TargetWire.identity(TargetIdentity(id(1), id(2), id(3), id(4), scope, listOf("cash"),
            TargetOrigin.Android(SelfOwnedTargetDescriptorV1("com.ukkera.pos", 10123, "a".repeat(64), "b".repeat(64)),
                TargetNamespace("/data/user/0/com.ukkera.pos/no_backup/" +
                    SelfOwnedCompositionNames.devicePairingTargetDirectory(null), 10123,
                    "0123abcd-0000-4000-8000-00000000abcd", 77, 78)), TargetBoot.Android(5), 1, octets(32, 7)))
        assertFails { TargetWire.decodeIdentity(android, TargetWireVariant.DESKTOP_V2) }
    }

    @Test
    fun mixedOriginAndBootVariantsAreRefused() {
        assertFailsWith<IllegalArgumentException> { desktopIdentity(osBoot = TargetBoot.Android(3)) }
        assertFailsWith<IllegalArgumentException> {
            TargetKeyIntent(digest('c'), TargetBoot.Android(1), 1, SelfOwnedNativeSecurityV1.SOFTWARE, octets(65, 4))
                .let { TargetWire.keyIntent(it, TargetWireVariant.DESKTOP_V2) }
        }
    }

    @Test
    fun desktopKeyIntentAndResponseIntentRoundTrip() {
        val point = ByteArray(65) { 4 }.also { it[1] = 9 }
        val intent = TargetKeyIntent(digest('c'), boot, 20_000, SelfOwnedNativeSecurityV1.SOFTWARE, SelfOwnedOctetsV1(point, 65))
        val bytes = TargetWire.keyIntent(intent, TargetWireVariant.DESKTOP_V2)
        assertContentEquals(bytes, TargetWire.keyIntent(
            TargetWire.decodeKeyIntent(bytes, TargetWireVariant.DESKTOP_V2), TargetWireVariant.DESKTOP_V2))
        assertFails { TargetWire.decodeKeyIntent(bytes) }
        val response = TargetResponseIntent(id(6), 0, digest('a'), digest('b'), boot, 30_000, octets(32, 2), octets(50, 5))
        val encoded = TargetWire.responseIntent(response, TargetWireVariant.DESKTOP_V2)
        assertContentEquals(encoded, TargetWire.responseIntent(
            TargetWire.decodeResponseIntent(encoded, TargetWireVariant.DESKTOP_V2), TargetWireVariant.DESKTOP_V2))
        assertTrue(encoded.size > 0)
    }

    @Test
    fun bootOrderingUsesOrdinalAndSameBootNeedsSameSession() {
        val later = TargetBoot.Desktop(4, "7f1c2a3b-0000-4000-8000-0000000000bb")
        assertTrue(boot < later && boot.sameBoot(boot))
        assertTrue(!boot.sameBoot(TargetBoot.Desktop(3, "7f1c2a3b-0000-4000-8000-0000000000bb")))
        assertFails { boot.compareTo(TargetBoot.Android(3)) }
    }
}

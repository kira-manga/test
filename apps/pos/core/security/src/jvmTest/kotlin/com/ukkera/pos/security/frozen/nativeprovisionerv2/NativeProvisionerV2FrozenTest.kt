package com.ukkera.pos.security.frozen.nativeprovisionerv2

import com.ukkera.pos.security.LocalCompanyAuthorityWireV1
import com.ukkera.pos.security.LocalProvisionerAllocationPhaseV1
import com.ukkera.pos.security.LocalProvisionerAllocationV2
import com.ukkera.pos.security.SelfOwnedBootstrapV2
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityRefusalV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedIosActiveAppIdentityV2
import com.ukkera.pos.security.SelfOwnedKeyIntentPhaseV1
import com.ukkera.pos.security.SelfOwnedNativeBootSessionV2
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedNativeOriginalProfileV2
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.SelfOwnedRegistryNamespaceV2
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.security.frozen.androidv1baseline.DocumentedLayout
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as F
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Frozen expectations for the additive WP-B Windows/macOS provisioner formats, authored on
 * 13 November 2026 BEFORE the formats existed. Expected framing is written from the documented
 * layout (4-byte big-endian length + UTF-8 strings, 4-byte ints, 8-byte longs, 0/1 int flags,
 * format version int 2 after the domain), never rendered by the code under test. Synthetic DATA.
 * NOT independent (same author, owner instruction). Android V1 decoders must refuse these bytes
 * and the V2 decoders must refuse Android V1 bytes.
 */
class NativeProvisionerV2FrozenTest {
    private val profile = "ukkera.installation/self-owned-native-original/v2"

    private fun header(domain: String) = DocumentedLayout().string(profile).string(domain).int(2)

    private fun <T> data(result: SelfOwnedInstalledIdentityResultV2<T>): T =
        assertIs<SelfOwnedInstalledIdentityResultV2.Data<T>>(result).value

    private fun refused(result: SelfOwnedInstalledIdentityResultV2<*>, code: SelfOwnedInstalledIdentityRefusalV2? = null) {
        val refusal = assertIs<SelfOwnedInstalledIdentityResultV2.Refused>(result)
        if (code != null) assertEquals(code, refusal.code)
    }

    private fun windowsNamespaceLayout(): ByteArray = header("native-provisioner-namespace/v2").int(1)
        .string(F.WINDOWS_PATH).string(F.OWNER_SID).string(F.VOLUME).string(F.DIRECTORY_FILE_ID).string(F.LOCK_FILE_ID).bytes()

    @Test
    fun profileDomainsAndPurposeAreTheFrozenDisjointNames() {
        assertEquals(profile, SelfOwnedNativeOriginalProfileV2.ID)
        assertEquals("native-provisioner-namespace/v2", SelfOwnedNativeOriginalProfileV2.NAMESPACE_DOMAIN)
        assertEquals("native-provisioner-bootstrap/v2", SelfOwnedNativeOriginalProfileV2.BOOTSTRAP_DOMAIN)
        assertEquals("native-provisioner-registry-state/v2", SelfOwnedNativeOriginalProfileV2.REGISTRY_STATE_DOMAIN)
        assertEquals("native-company-authority-bootstrap/v2", SelfOwnedNativeOriginalProfileV2.COMPANY_BOOTSTRAP_DOMAIN)
        assertEquals("native-boot-ordinal-ledger/v2", SelfOwnedNativeOriginalProfileV2.BOOT_ORDINAL_LEDGER_DOMAIN)
        assertEquals("ORIGINAL_NATIVE_PROVISIONER_REGISTRY_V2", SelfOwnedNativeOriginalProfileV2.REGISTRY_PURPOSE)
        assertEquals(2, SelfOwnedNativeOriginalProfileV2.FORMAT_VERSION)
    }

    @Test
    fun registryNamespaceUsesExactDocumentedFramingForBothPlatforms() {
        val windows = SelfOwnedNativeProvisionerWireV2.encodeNamespace(F.windowsNamespace)
        assertContentEquals(windowsNamespaceLayout(), windows)
        assertEquals(F.windowsNamespace, data(SelfOwnedNativeProvisionerWireV2.decodeNamespace(windows)))
        val mac = SelfOwnedNativeProvisionerWireV2.encodeNamespace(F.macNamespace)
        assertContentEquals(header("native-provisioner-namespace/v2").int(2).string(F.MAC_PATH).int(501).string(F.VOLUME)
            .long(31).long(32).bytes(), mac)
        assertEquals(F.macNamespace, data(SelfOwnedNativeProvisionerWireV2.decodeNamespace(mac)))
        assertEquals("SelfOwnedRegistryNamespaceV2.Windows([REDACTED])", F.windowsNamespace.toString())
        assertEquals("SelfOwnedRegistryNamespaceV2.Mac([REDACTED])", F.macNamespace.toString())
    }

    @Test
    fun registryNamespaceRefusesTrailingTruncatedOversizedUnknownAndInvalidValues() {
        val bytes = windowsNamespaceLayout()
        refused(SelfOwnedNativeProvisionerWireV2.decodeNamespace(bytes + byteArrayOf(0)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING)
        refused(SelfOwnedNativeProvisionerWireV2.decodeNamespace(bytes.copyOf(bytes.size - 1)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRUNCATED)
        refused(SelfOwnedNativeProvisionerWireV2.decodeNamespace(ByteArray(SelfOwnedNativeOriginalProfileV2.MAXIMUM_NAMESPACE_BYTES + 1)),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        refused(SelfOwnedNativeProvisionerWireV2.decodeNamespace(header("native-provisioner-namespace/v2").int(3)
            .string(F.WINDOWS_PATH).bytes()), SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
        refused(SelfOwnedNativeProvisionerWireV2.decodeNamespace(DocumentedLayout().string(profile)
            .string("native-provisioner-namespace/v2").int(1).int(1).bytes()), SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
        refused(SelfOwnedNativeProvisionerWireV2.decodeNamespace(DocumentedLayout().string(profile)
            .string("native-provisioner-bootstrap/v2").int(2).int(1).bytes()), SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        // Target directory, duplicate/uppercase file IDs, non-canonical GUID and relative paths are refused.
        assertFails { SelfOwnedRegistryNamespaceV2.Windows("C:\\ProgramData\\Ukkera\\ukkera-self-owned-target-v1", F.OWNER_SID, F.VOLUME, F.DIRECTORY_FILE_ID, F.LOCK_FILE_ID) }
        assertFails { SelfOwnedRegistryNamespaceV2.Windows(F.WINDOWS_PATH, F.OWNER_SID, F.VOLUME, F.DIRECTORY_FILE_ID, F.DIRECTORY_FILE_ID) }
        assertFails { SelfOwnedRegistryNamespaceV2.Windows(F.WINDOWS_PATH, F.OWNER_SID, F.VOLUME, "000102030405060708090A0B0C0D0E0F", F.LOCK_FILE_ID) }
        assertFails { SelfOwnedRegistryNamespaceV2.Windows(F.WINDOWS_PATH, F.OWNER_SID, F.VOLUME.uppercase(), F.DIRECTORY_FILE_ID, F.LOCK_FILE_ID) }
        assertFails { SelfOwnedRegistryNamespaceV2.Windows(F.WINDOWS_PATH, "not-a-sid", F.VOLUME, F.DIRECTORY_FILE_ID, F.LOCK_FILE_ID) }
        assertFails { SelfOwnedRegistryNamespaceV2.Mac("relative/ukkera-self-owned-provisioner-v1", 501, F.VOLUME, 1, 2) }
        assertFails { SelfOwnedRegistryNamespaceV2.Mac(F.MAC_PATH, -1, F.VOLUME, 1, 2) }
        assertFails { SelfOwnedRegistryNamespaceV2.Mac(F.MAC_PATH, 501, F.VOLUME, 0, 2) }
        assertFails { SelfOwnedRegistryNamespaceV2.Mac(F.MAC_PATH, 501, F.VOLUME, 2, 2) }
    }

    private fun bootstrapLayout(target: ByteArray = F.binding(F.windowsIdentity).copyWireBytes()): ByteArray {
        val layout = header("native-provisioner-bootstrap/v2").long(2).id(A.bootstrapAction).id(A.root).id(A.organization)
            .id(A.company).id(A.issuer).id(A.installer).string("Synthetic Company").string("Synthetic Person")
            .string("installer.one").int(0).field(target).field(windowsNamespaceLayout()).int(0)
            .field(A.bytes(32, 0xc1)).int(4)
        for ((index, keyId) in listOf(A.rootSigningKey, A.issuerSigningKey).withIndex()) {
            layout.int(index + 1).id(keyId).int(2).int(1).int(1).string(F.WINDOWS_BOOT).int(1).int(3)
                .field(A.bytes(91, 0x31 + index))
        }
        layout.int(3).id(A.registryKey).int(1).int(1).int(1).string(F.WINDOWS_BOOT).int(0).field(ByteArray(0))
        layout.int(4).id(A.installerPepperKey).int(0).int(0).int(0).field(ByteArray(0))
        return layout.bytes()
    }

    @Test
    fun bootstrapV2UsesExactDocumentedFramingAndRoundTrips() {
        val bytes = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(F.windowsBootstrap())
        assertContentEquals(bootstrapLayout(), bytes)
        val decoded = data(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(bytes))
        assertContentEquals(bytes, SelfOwnedNativeProvisionerWireV2.encodeBootstrap(decoded))
        assertEquals(F.windowsNamespace, decoded.namespace)
        assertFalse(decoded.complete())
        val mac = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(F.macCompleteBootstrap())
        assertContentEquals(mac, SelfOwnedNativeProvisionerWireV2.encodeBootstrap(data(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(mac))))
        assertTrue(F.macCompleteBootstrap().complete())
        assertEquals("SelfOwnedBootstrapV2([REDACTED])", F.windowsBootstrap().toString())
    }

    @Test
    fun bootstrapV2RefusesAndroidTargetsMixedPlatformsAndMalformedBytes() {
        // An Android installed-target binding is never a V2 desktop provisioner target.
        val android = SelfOwnedInstalledTargetBindingV1.android(A.target).copyWireBytes()
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(bootstrapLayout(android)))
        val bytes = bootstrapLayout()
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(bytes + byteArrayOf(0)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING)
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(bytes.copyOf(bytes.size - 3)))
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(ByteArray(SelfOwnedNativeOriginalProfileV2.MAXIMUM_BOOTSTRAP_BYTES + 1)),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        // Windows target + macOS namespace, or a macOS boot session on a Windows bootstrap, is refused.
        assertFails { F.windowsBootstrap().copy(namespace = F.macNamespace) }
        assertFails { F.windowsBootstrap().copy(keys = F.windowsBootstrap().keys.map { if (it.startedBoot != null) it.copy(startedBoot = F.macBoot) else it }) }
        // Phase progression mirrors V1: a STARTED key cannot precede a COMPLETE one; enrollment needs all COMPLETE.
        assertFails { F.windowsBootstrap().copy(enrollmentStarted = true) }
        assertFails { F.windowsBootstrap().copy(keys = F.windowsBootstrap().keys.reversed()) }
        assertFails { F.key(SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedKeyIntentPhaseV1.COMPLETE, F.windowsBoot).copy(proof = SelfOwnedOctetsV1(ByteArray(513))) }
    }

    @Test
    fun androidV1AndDesktopV2BootstrapDecodersRefuseEachOther() {
        val v2 = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(F.windowsBootstrap())
        assertFails { SelfOwnedInstallationWireV1.decodeBootstrap(v2) }
        assertFails { LocalCompanyAuthorityWireV1.decodeBootstrap(v2) }
        val v1 = SelfOwnedInstallationWireV1.encodeBootstrap(A.partialBootstrap())
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(v1), SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        refused(SelfOwnedNativeProvisionerWireV2.decodeNamespace(v1), SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        val companyV1 = LocalCompanyAuthorityWireV1.encodeBootstrap(A.companyBootstrap())
        refused(SelfOwnedNativeProvisionerWireV2.decodeCompanyBootstrap(companyV1), SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        val companyV2 = SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(F.macCompanyBootstrap())
        assertFails { LocalCompanyAuthorityWireV1.decodeBootstrap(companyV2) }
        assertFalse(LocalCompanyAuthorityWireV1.isCompanyProfile(companyV2))
        val registryV1 = SelfOwnedInstallationWireV1.encodeRegistry(A.registryState())
        refused(SelfOwnedNativeProvisionerWireV2.decodeRegistry(registryV1), SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        val registryV2 = SelfOwnedNativeProvisionerWireV2.encodeRegistry(F.registry())
        assertFails { SelfOwnedInstallationWireV1.decodeRegistry(registryV2) }
        // Desktop V2 domains are disjoint from each other.
        refused(SelfOwnedNativeProvisionerWireV2.decodeRegistry(v2), SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        refused(SelfOwnedNativeProvisionerWireV2.decodeCompanyBootstrap(v2), SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
    }

    @Test
    fun registryStateV2UsesV2PreparationAndGrantWithPurposeAndRoundTrips() {
        val bytes = SelfOwnedNativeProvisionerWireV2.encodeRegistry(F.registry())
        val expected = header("native-provisioner-registry-state/v2").long(4).field(A.rootDocument().copyWireBytes())
            .field(A.delegationDocument().copyWireBytes()).int(1).long(2).int(1)
            .id(A.installer).string("Synthetic Installer").string("installer.one").long(0).long(0).int(1)
            .int(600_000).field(A.bytes(32, 0xe1)).field(A.bytes(32, 0xe2)).int(0).int(0).int(3).long(0).long(0)
            .int(1).field(F.preparation().copyWireBytes()).int(2).int(1).field(F.grant().copyWireBytes())
            .string("ORIGINAL_NATIVE_PROVISIONER_REGISTRY_V2").bytes()
        assertContentEquals(expected, bytes)
        val decoded = data(SelfOwnedNativeProvisionerWireV2.decodeRegistry(bytes))
        assertContentEquals(bytes, SelfOwnedNativeProvisionerWireV2.encodeRegistry(decoded))
        refused(SelfOwnedNativeProvisionerWireV2.decodeRegistry(bytes + byteArrayOf(0)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING)
        refused(SelfOwnedNativeProvisionerWireV2.decodeRegistry(bytes.copyOf(bytes.size - 1)))
        // A different purpose string is refused.
        val wrongPurpose = bytes.copyOf().also { it[it.size - 1] = 'X'.code.toByte() }
        refused(SelfOwnedNativeProvisionerWireV2.decodeRegistry(wrongPurpose))
        assertEquals("LocalProvisionerRegistryStateV2([REDACTED])", F.registry().toString())
        assertEquals(5L, F.registry().next().revision)
    }

    @Test
    fun registryAllocationV2FailsClosed() {
        // Retained grant must be the exact same V2 preparation; phase and grant presence must agree.
        assertFails { LocalProvisionerAllocationV2(F.preparation(), LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, F.grant(F.preparation(serial = 7L))) }
        assertFails { LocalProvisionerAllocationV2(F.preparation(), LocalProvisionerAllocationPhaseV1.ALLOCATED, F.grant()) }
        assertFails { LocalProvisionerAllocationV2(F.preparation(), LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, null) }
        // iOS active-app identity is DATA-only and never a desktop provisioner allocation.
        val ios = SelfOwnedIosActiveAppIdentityV2("com.ukkera.pos.ios", "ABCDE12345.com.ukkera.pos.ios")
        assertFails { LocalProvisionerAllocationV2(F.preparation(ios), LocalProvisionerAllocationPhaseV1.ALLOCATED, null) }
        // Serial must stay below nextSerial.
        assertFails { F.registry().copy(nextSerial = 1L) }
        assertEquals(LocalProvisionerAllocationPhaseV1.ALLOCATED,
            LocalProvisionerAllocationV2(F.preparation(F.macIdentity), LocalProvisionerAllocationPhaseV1.ALLOCATED, null).phase)
    }

    @Test
    fun companyAuthorityBootstrapV2MirrorsV1WithNativeBindingAndNamespace() {
        val base = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(F.macCompleteBootstrap())
        val bytes = SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(F.macCompanyBootstrap())
        val expected = header("native-company-authority-bootstrap/v2").long(11).field(base).id(A.headAuthority)
            .id(A.headEnrollment).string("Synthetic Head").string("head.one").string("UNTIL_LOCAL_DISABLE")
            .string("POLICY_PUBLISH").string("FULL_D7_INSTALLATION_APPROVE").int(3)
            .string("HEAD_POLICY_SIGNING").id(A.headPolicyKey).long(0).string("COMPLETE").int(1).int(2).string(F.MAC_BOOT)
            .int(1).string("HARDWARE_KIND_UNSPECIFIED").field(A.bytes(65, 0xd0))
            .string("HEAD_INSTALLATION_SIGNING").id(A.headInstallationKey).long(0).string("STARTED").int(1).int(2)
            .string(F.MAC_BOOT).int(0).field(ByteArray(0))
            .string("HEAD_PEPPER").id(A.headPepperKey).long(0).string("NOT_STARTED").int(0).int(0).field(ByteArray(0)).bytes()
        assertContentEquals(expected, bytes)
        val decoded = data(SelfOwnedNativeProvisionerWireV2.decodeCompanyBootstrap(bytes))
        assertContentEquals(bytes, SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(decoded))
        refused(SelfOwnedNativeProvisionerWireV2.decodeCompanyBootstrap(bytes + byteArrayOf(0)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING)
        refused(SelfOwnedNativeProvisionerWireV2.decodeCompanyBootstrap(bytes.copyOf(bytes.size - 1)))
        assertEquals("LocalCompanyAuthorityBootstrapV2([REDACTED])", F.macCompanyBootstrap().toString())
        // Head key boot sessions must be on the original's platform; revision cannot precede the original.
        assertFails { F.macCompanyBootstrap().copy(headKeys = F.macCompanyBootstrap().headKeys.map {
            if (it.startedBoot != null) it.copy(startedBoot = F.windowsBoot) else it }) }
        assertFails { F.macCompanyBootstrap().copy(revision = 8L) }
        assertFails { F.macCompanyBootstrap().copy(original = F.windowsBootstrap()) }
    }
}

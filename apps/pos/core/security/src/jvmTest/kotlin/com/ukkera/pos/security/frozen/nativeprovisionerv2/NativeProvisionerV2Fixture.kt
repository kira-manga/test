package com.ukkera.pos.security.frozen.nativeprovisionerv2

import com.ukkera.pos.security.LocalCompanyAuthorityBootstrapV2
import com.ukkera.pos.security.LocalCompanyHeadKeyIntentV2
import com.ukkera.pos.security.LocalCompanyHeadKeyRoleV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalProvisionerAllocationPhaseV1
import com.ukkera.pos.security.LocalProvisionerAllocationV2
import com.ukkera.pos.security.LocalProvisionerInstallerV1
import com.ukkera.pos.security.LocalProvisionerRegistryStateV2
import com.ukkera.pos.security.SelfOwnedBootstrapV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityFactoryV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityPreparationV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedBodyV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedDocumentV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityTargetV2
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedKeyIntentPhaseV1
import com.ukkera.pos.security.SelfOwnedMacServiceIdentityV2
import com.ukkera.pos.security.SelfOwnedNativeBootSessionV2
import com.ukkera.pos.security.SelfOwnedNativeInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedNativeSecurityV1
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.SelfOwnedProvisionerKeyIntentV2
import com.ukkera.pos.security.SelfOwnedRegistryNamespaceV2
import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.types.DomainId
import kotlin.test.assertIs

/** Synthetic shape-only DATA for the additive WP-B desktop provisioner formats. Grants nothing. */
internal object NativeProvisionerV2Fixture {
    const val WINDOWS_PATH: String = "C:\\ProgramData\\Ukkera\\ukkera-self-owned-provisioner-v1"
    const val MAC_PATH: String = "/Library/Application Support/Ukkera/ukkera-self-owned-provisioner-v1"
    const val OWNER_SID: String = "S-1-5-21-1000-2000-3000-1001"
    const val VOLUME: String = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    const val DIRECTORY_FILE_ID: String = "000102030405060708090a0b0c0d0e0f"
    const val LOCK_FILE_ID: String = "101112131415161718191a1b1c1d1e1f"
    const val WINDOWS_BOOT: String = "11111111-2222-4333-8444-555555555555"
    const val WINDOWS_NEXT_BOOT: String = "11111111-2222-4333-8444-666666666666"
    const val MAC_BOOT: String = "99999999-8888-4777-8666-555555555555"

    val windowsIdentity = SelfOwnedWindowsPackagedAppIdentityV2("Ukkera.POS.Test_n9kkbga0qmnm6",
        "Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS")
    val macIdentity = SelfOwnedMacServiceIdentityV2("com.ukkera.pos.local.desktop.master", "ABCDE12345", "e".repeat(64))

    fun binding(target: SelfOwnedInstalledIdentityTargetV2): SelfOwnedNativeInstalledTargetBindingV1 {
        val factory = if (target is SelfOwnedMacServiceIdentityV2) SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE
            else SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI
        val result = SelfOwnedInstalledTargetBindingV1.accept(factory, target)
        return assertIs<SelfOwnedNativeInstalledTargetBindingV1>(assertIs<SelfOwnedInstalledIdentityResultV2.Data<*>>(result).value)
    }

    val windowsNamespace = SelfOwnedRegistryNamespaceV2.Windows(WINDOWS_PATH, OWNER_SID, VOLUME, DIRECTORY_FILE_ID, LOCK_FILE_ID)
    val macNamespace = SelfOwnedRegistryNamespaceV2.Mac(MAC_PATH, 501, VOLUME, 31L, 32L)
    val windowsBoot = SelfOwnedNativeBootSessionV2.Windows(WINDOWS_BOOT)
    val macBoot = SelfOwnedNativeBootSessionV2.Mac(MAC_BOOT)

    private fun keyId(role: SelfOwnedNativeKeyRoleV1): DomainId = when (role) {
        SelfOwnedNativeKeyRoleV1.ROOT -> A.rootSigningKey
        SelfOwnedNativeKeyRoleV1.ISSUER -> A.issuerSigningKey
        SelfOwnedNativeKeyRoleV1.REGISTRY -> A.registryKey
        SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> A.installerPepperKey
    }

    fun key(role: SelfOwnedNativeKeyRoleV1, phase: SelfOwnedKeyIntentPhaseV1, boot: SelfOwnedNativeBootSessionV2?):
        SelfOwnedProvisionerKeyIntentV2 {
        val complete = phase == SelfOwnedKeyIntentPhaseV1.COMPLETE
        return SelfOwnedProvisionerKeyIntentV2(role, keyId(role), phase, boot,
            if (complete) SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED else null,
            if (complete) SelfOwnedOctetsV1(A.bytes(91, 0x30 + role.wireId)) else SelfOwnedOctetsV1(ByteArray(0)))
    }

    fun windowsBootstrap(): SelfOwnedBootstrapV2 = SelfOwnedBootstrapV2(2L, A.bootstrapAction, A.root, A.organization,
        A.company, A.issuer, A.installer, A.facts, binding(windowsIdentity), windowsNamespace,
        SelfOwnedOctetsV1(A.bytes(32, 0xc1)), listOf(
            key(SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedKeyIntentPhaseV1.COMPLETE, windowsBoot),
            key(SelfOwnedNativeKeyRoleV1.ISSUER, SelfOwnedKeyIntentPhaseV1.COMPLETE, windowsBoot),
            key(SelfOwnedNativeKeyRoleV1.REGISTRY, SelfOwnedKeyIntentPhaseV1.STARTED, windowsBoot),
            key(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER, SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null)), false)

    fun macCompleteBootstrap(): SelfOwnedBootstrapV2 = SelfOwnedBootstrapV2(9L, A.bootstrapAction, A.root,
        A.organization, A.company, A.issuer, A.installer, A.facts, binding(macIdentity), macNamespace,
        SelfOwnedOctetsV1(A.bytes(32, 0xc1)),
        SelfOwnedNativeKeyRoleV1.entries.map { key(it, SelfOwnedKeyIntentPhaseV1.COMPLETE, macBoot) }, false)

    fun headKey(role: LocalCompanyHeadKeyRoleV1, phase: SelfOwnedKeyIntentPhaseV1, boot: SelfOwnedNativeBootSessionV2?):
        LocalCompanyHeadKeyIntentV2 {
        val keyId = when (role) {
            LocalCompanyHeadKeyRoleV1.POLICY -> A.headPolicyKey
            LocalCompanyHeadKeyRoleV1.INSTALLATION -> A.headInstallationKey
            LocalCompanyHeadKeyRoleV1.PEPPER -> A.headPepperKey
        }
        val complete = phase == SelfOwnedKeyIntentPhaseV1.COMPLETE
        return LocalCompanyHeadKeyIntentV2(role, keyId, 0L, phase, boot,
            if (complete) SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED else null,
            if (complete) SelfOwnedOctetsV1(A.bytes(65, 0xd0 + role.ordinal)) else SelfOwnedOctetsV1(ByteArray(0)))
    }

    fun macCompanyBootstrap(): LocalCompanyAuthorityBootstrapV2 = LocalCompanyAuthorityBootstrapV2(11L,
        macCompleteBootstrap(), A.headAuthority, A.headEnrollment, "Synthetic Head", "head.one",
        LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, listOf(
            headKey(LocalCompanyHeadKeyRoleV1.POLICY, SelfOwnedKeyIntentPhaseV1.COMPLETE, macBoot),
            headKey(LocalCompanyHeadKeyRoleV1.INSTALLATION, SelfOwnedKeyIntentPhaseV1.STARTED, macBoot),
            headKey(LocalCompanyHeadKeyRoleV1.PEPPER, SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null)))

    fun preparation(target: SelfOwnedInstalledIdentityTargetV2 = windowsIdentity, serial: Long = 1L):
        SelfOwnedInstalledIdentityPreparationV2 = SelfOwnedInstalledIdentityPreparationV2(A.root,
        A.sha256Hex(A.rootDocument().copyWireBytes()), A.issuer, A.sha256Hex(A.delegationDocument().copyWireBytes()),
        A.installer, 0L, 0L, A.coordinates, serial, A.bytes(32, 0x51), target, "Main Branch")

    fun grant(preparation: SelfOwnedInstalledIdentityPreparationV2 = preparation()): SelfOwnedInstalledIdentitySignedDocumentV2 =
        SelfOwnedInstalledIdentitySignedDocumentV2(SelfOwnedInstalledIdentitySignedBodyV2(preparation), A.bytes(64, 0x61))

    val installer = LocalProvisionerInstallerV1(A.installer, "Synthetic Installer", "installer.one", 0L, 0L, true,
        SelfOwnedOctetsV1(A.bytes(32, 0xe1)), SelfOwnedOctetsV1(A.bytes(32, 0xe2)), 0, null, 3, 0L, 0L)

    fun registry(): LocalProvisionerRegistryStateV2 = LocalProvisionerRegistryStateV2(4L, A.rootDocument(),
        A.delegationDocument(), true, 2L, listOf(installer),
        listOf(LocalProvisionerAllocationV2(preparation(), LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, grant())))
}

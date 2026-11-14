package com.ukkera.pos.security.frozen.androidv1baseline

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalCompanyAuthorityBootstrapV1
import com.ukkera.pos.security.LocalCompanyHeadKeyIntentV1
import com.ukkera.pos.security.LocalCompanyHeadKeyRoleV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalProvisionerAllocationPhaseV1
import com.ukkera.pos.security.LocalProvisionerAllocationV1
import com.ukkera.pos.security.LocalProvisionerInstallerV1
import com.ukkera.pos.security.LocalProvisionerRegistryStateV1
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedBootstrapV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedDelegationBodyV1
import com.ukkera.pos.security.SelfOwnedDocumentRoleV1
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedKeyIntentPhaseV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeBindingBodyV1
import com.ukkera.pos.security.SelfOwnedNativeBindingV1
import com.ukkera.pos.security.SelfOwnedNativeInstallationWireV1
import com.ukkera.pos.security.SelfOwnedNativeIntentV1
import com.ukkera.pos.security.SelfOwnedNativeJournalStateV1
import com.ukkera.pos.security.SelfOwnedNativeKeyIntentV1
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedNativeNamespaceV1
import com.ukkera.pos.security.SelfOwnedNativeSecurityV1
import com.ukkera.pos.security.SelfOwnedNativeSourceV1
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.SelfOwnedPreparationBodyV1
import com.ukkera.pos.security.SelfOwnedRegistryNamespaceV1
import com.ukkera.pos.security.SelfOwnedRootBodyV1
import com.ukkera.pos.security.SelfOwnedSignedDocumentV1
import com.ukkera.pos.security.SelfOwnedTargetDescriptorV1
import com.ukkera.pos.security.SelfOwnedTargetKeyIntentV1
import com.ukkera.pos.security.SelfOwnedTargetKeyRoleV1
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest

/**
 * Deterministic SYNTHETIC inputs for the Android V1 regression baseline. Every identifier, point,
 * signature, nonce and proof below is fabricated shape-only test DATA: no signature verifies, no
 * key exists and nothing here is an authority, custody or installation record.
 */
internal object AndroidV1BaselineFixture {
    const val PROFILE: String = "ukkera.installation/self-owned-local-p256/v1"
    const val COMPANY_PROFILE: String = "ukkera.local-company-authority/p256/v1"
    const val VOLUME: String = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    const val TARGET_PATH: String = "/data/user/0/synthetic.app/no_backup/ukkera-self-owned-target-v1"
    const val PROVISIONER_PATH: String = "/data/user/0/synthetic.app/no_backup/ukkera-self-owned-provisioner-v1"

    fun id(number: Int): DomainId = DomainId("00000000-0000-4000-8000-" + number.toString(16).padStart(12, '0'))

    val organization = id(0x201); val company = id(0x202); val branch = id(0x203)
    val root = id(0x204); val rootKey = id(0x205); val installer = id(0x206); val issuer = id(0x207)
    val issuerKey = id(0x208); val bootstrapAction = id(0x209)
    val rootSigningKey = id(0x20a); val issuerSigningKey = id(0x20b); val registryKey = id(0x20c)
    val installerPepperKey = id(0x20d); val headAuthority = id(0x20e); val headEnrollment = id(0x20f)
    val headPolicyKey = id(0x210); val headInstallationKey = id(0x211); val headPepperKey = id(0x212)

    fun bytes(size: Int, seed: Int): ByteArray = ByteArray(size) { ((seed + it * 7) and 0xff).toByte() }
    fun point(seed: Int): ByteArray = bytes(65, seed).also { it[0] = 4 }
    fun octets(size: Int, seed: Int): SelfOwnedOctetsV1 = SelfOwnedOctetsV1(bytes(size, seed))

    val target = SelfOwnedTargetDescriptorV1("synthetic.app", 10123, "a".repeat(64), "b".repeat(64))

    val coordinates = SelfOwnedAllocationCoordinatesV1(id(0x301), BranchScope(organization, company, branch),
        id(0x302), id(0x303), id(0x304), id(0x305), id(0x306), id(0x307), id(0x308), id(0x309))

    val rootBody = SelfOwnedRootBodyV1(root, rootKey, organization, company, installer, "Synthetic Company",
        "Synthetic Person", "installer.one", SelfOwnedOctetsV1(point(0x11)),
        SelfOwnedKeyProtectionV1.OS_ISOLATED, bootstrapAction)

    fun rootDocument(): SelfOwnedSignedDocumentV1 = SelfOwnedSignedDocumentV1(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT,
        SelfOwnedInstallationWireV1.encodeRoot(rootBody), bytes(64, 0x21))

    fun delegationBody(): SelfOwnedDelegationBodyV1 = SelfOwnedDelegationBodyV1(root, sha256Hex(rootDocument().copyWireBytes()),
        organization, company, issuer, issuerKey, SelfOwnedOctetsV1(point(0x31)), installer, 0L, 0L,
        SelfOwnedKeyProtectionV1.OS_ISOLATED)

    fun delegationDocument(): SelfOwnedSignedDocumentV1 = SelfOwnedSignedDocumentV1(SelfOwnedDocumentRoleV1.ISSUER_DELEGATION,
        SelfOwnedInstallationWireV1.encodeDelegation(delegationBody()), bytes(64, 0x41))

    fun preparationBody(): SelfOwnedPreparationBodyV1 = SelfOwnedPreparationBodyV1(root,
        sha256Hex(rootDocument().copyWireBytes()), issuer, sha256Hex(delegationDocument().copyWireBytes()), installer,
        0L, 0L, coordinates, 1L, octets(32, 0x51), target, "Main Branch", SelfOwnedKeyProtectionV1.OS_ISOLATED)

    fun preparationDocument(): SelfOwnedSignedDocumentV1 = SelfOwnedSignedDocumentV1(SelfOwnedDocumentRoleV1.NATIVE_PREPARATION,
        SelfOwnedInstallationWireV1.encodePreparation(preparationBody()), bytes(64, 0x61))

    fun source(): SelfOwnedNativeSourceV1 = SelfOwnedNativeSourceV1(rootDocument(), delegationDocument(), preparationDocument())

    val targetNamespace = SelfOwnedNativeNamespaceV1(TARGET_PATH, VOLUME, target.applicationUid, 11L, 12L, 13L)

    private fun proofSize(role: SelfOwnedTargetKeyRoleV1): Int = when (role) {
        SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> 65
        SelfOwnedTargetKeyRoleV1.STORAGE -> 61
        SelfOwnedTargetKeyRoleV1.PEPPER -> 32
    }

    fun targetKey(role: SelfOwnedTargetKeyRoleV1, phase: SelfOwnedKeyIntentPhaseV1, boot: Int?): SelfOwnedTargetKeyIntentV1 {
        val keyId = when (role) {
            SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> coordinates.targetSigningKeyId
            SelfOwnedTargetKeyRoleV1.STORAGE -> coordinates.storageKeyId
            SelfOwnedTargetKeyRoleV1.PEPPER -> coordinates.pepperKeyId
        }
        val complete = phase == SelfOwnedKeyIntentPhaseV1.COMPLETE
        return SelfOwnedTargetKeyIntentV1(role, keyId, phase, boot,
            if (complete) SelfOwnedNativeSecurityV1.entries[role.wireId] else null,
            if (complete) octets(proofSize(role), 0x70 + role.wireId) else SelfOwnedOctetsV1(ByteArray(0)))
    }

    /** Partially progressed intent: signing COMPLETE, storage STARTED, pepper NOT_STARTED. */
    fun partialIntent(): SelfOwnedNativeIntentV1 = SelfOwnedNativeIntentV1(3L, source(), targetNamespace, octets(32, 0x81),
        listOf(targetKey(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING, SelfOwnedKeyIntentPhaseV1.COMPLETE, 7),
            targetKey(SelfOwnedTargetKeyRoleV1.STORAGE, SelfOwnedKeyIntentPhaseV1.STARTED, 8),
            targetKey(SelfOwnedTargetKeyRoleV1.PEPPER, SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null)), false)

    fun completeIntent(): SelfOwnedNativeIntentV1 = SelfOwnedNativeIntentV1(6L, source(), targetNamespace, octets(32, 0x81),
        SelfOwnedTargetKeyRoleV1.entries.map { targetKey(it, SelfOwnedKeyIntentPhaseV1.COMPLETE, 7) }, true)

    fun bindingBody(): SelfOwnedNativeBindingBodyV1 = SelfOwnedNativeBindingBodyV1(coordinates, target,
        sha256Hex(rootDocument().copyWireBytes()), sha256Hex(delegationDocument().copyWireBytes()),
        sha256Hex(preparationDocument().copyWireBytes()),
        sha256Hex(SelfOwnedNativeInstallationWireV1.encodeIntent(completeIntent())), SelfOwnedOctetsV1(point(0x91)),
        SelfOwnedKeyProtectionV1.OS_ISOLATED, SelfOwnedNativeSecurityV1.TRUSTED_ENVIRONMENT,
        SelfOwnedNativeSecurityV1.STRONGBOX, SelfOwnedNativeSecurityV1.SOFTWARE)

    fun binding(): SelfOwnedNativeBindingV1 =
        SelfOwnedNativeBindingV1(SelfOwnedNativeInstallationWireV1.encodeBindingBody(bindingBody()), bytes(64, 0xa1))

    fun journalState(): SelfOwnedNativeJournalStateV1 = SelfOwnedNativeJournalStateV1(5L, binding(), true)

    val registryNamespace = SelfOwnedRegistryNamespaceV1(PROVISIONER_PATH, target.applicationUid, VOLUME, 21L, 22L)

    private fun provisionerKeyId(role: SelfOwnedNativeKeyRoleV1): DomainId = when (role) {
        SelfOwnedNativeKeyRoleV1.ROOT -> rootSigningKey
        SelfOwnedNativeKeyRoleV1.ISSUER -> issuerSigningKey
        SelfOwnedNativeKeyRoleV1.REGISTRY -> registryKey
        SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> installerPepperKey
    }

    fun provisionerKey(role: SelfOwnedNativeKeyRoleV1, phase: SelfOwnedKeyIntentPhaseV1, boot: Int?): SelfOwnedNativeKeyIntentV1 {
        val complete = phase == SelfOwnedKeyIntentPhaseV1.COMPLETE
        return SelfOwnedNativeKeyIntentV1(role, provisionerKeyId(role), phase, boot,
            if (complete) SelfOwnedNativeSecurityV1.entries[role.wireId - 1] else null,
            if (complete) octets(65, 0xb0 + role.wireId) else SelfOwnedOctetsV1(ByteArray(0)))
    }

    val facts = SelfOwnedCompanyFactsV1("Synthetic Company", "Synthetic Person", "installer.one",
        SelfOwnedKeyProtectionV1.OS_ISOLATED)

    /** ROOT/ISSUER COMPLETE, REGISTRY STARTED, INSTALLER_PEPPER NOT_STARTED. */
    fun partialBootstrap(): SelfOwnedBootstrapV1 = SelfOwnedBootstrapV1(2L, bootstrapAction, root, organization, company,
        issuer, installer, facts, target, registryNamespace, octets(32, 0xc1), listOf(
            provisionerKey(SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedKeyIntentPhaseV1.COMPLETE, 4),
            provisionerKey(SelfOwnedNativeKeyRoleV1.ISSUER, SelfOwnedKeyIntentPhaseV1.COMPLETE, 4),
            provisionerKey(SelfOwnedNativeKeyRoleV1.REGISTRY, SelfOwnedKeyIntentPhaseV1.STARTED, 5),
            provisionerKey(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER, SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null)), false)

    fun completeBootstrap(): SelfOwnedBootstrapV1 = SelfOwnedBootstrapV1(9L, bootstrapAction, root, organization, company,
        issuer, installer, facts, target, registryNamespace, octets(32, 0xc1),
        SelfOwnedNativeKeyRoleV1.entries.map { provisionerKey(it, SelfOwnedKeyIntentPhaseV1.COMPLETE, 4) }, false)

    fun headKey(role: LocalCompanyHeadKeyRoleV1, phase: SelfOwnedKeyIntentPhaseV1, boot: Int?): LocalCompanyHeadKeyIntentV1 {
        val keyId = when (role) {
            LocalCompanyHeadKeyRoleV1.POLICY -> headPolicyKey
            LocalCompanyHeadKeyRoleV1.INSTALLATION -> headInstallationKey
            LocalCompanyHeadKeyRoleV1.PEPPER -> headPepperKey
        }
        val complete = phase == SelfOwnedKeyIntentPhaseV1.COMPLETE
        return LocalCompanyHeadKeyIntentV1(role, keyId, 0L, phase, boot,
            if (complete) SelfOwnedNativeSecurityV1.STRONGBOX else null,
            if (complete) octets(if (role == LocalCompanyHeadKeyRoleV1.PEPPER) 32 else 65, 0xd0 + role.ordinal)
            else SelfOwnedOctetsV1(ByteArray(0)))
    }

    fun companyBootstrap(): LocalCompanyAuthorityBootstrapV1 = LocalCompanyAuthorityBootstrapV1(11L, completeBootstrap(),
        headAuthority, headEnrollment, "Synthetic Head", "head.one", LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        listOf(headKey(LocalCompanyHeadKeyRoleV1.POLICY, SelfOwnedKeyIntentPhaseV1.COMPLETE, 6),
            headKey(LocalCompanyHeadKeyRoleV1.INSTALLATION, SelfOwnedKeyIntentPhaseV1.STARTED, 6),
            headKey(LocalCompanyHeadKeyRoleV1.PEPPER, SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null)))

    fun registryState(): LocalProvisionerRegistryStateV1 = LocalProvisionerRegistryStateV1(4L, rootDocument(),
        delegationDocument(), true, 2L,
        listOf(LocalProvisionerInstallerV1(installer, "Synthetic Installer", "installer.one", 0L, 0L, true,
            octets(32, 0xe1), octets(32, 0xe2), 0, null, 3, 0L, 0L)),
        listOf(LocalProvisionerAllocationV1(preparationBody(), LocalProvisionerAllocationPhaseV1.GRANT_RETAINED,
            preparationDocument())))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Independent test-side writer of the DOCUMENTED V1 layout: 4-byte big-endian non-negative int,
 * 8-byte big-endian non-negative long, single 0/1 boolean byte, 4-byte length + raw bytes field,
 * strings as UTF-8 fields. It deliberately shares no code with the production writer.
 */
internal class DocumentedLayout {
    private val out = java.io.ByteArrayOutputStream()
    fun int(value: Int): DocumentedLayout = apply {
        require(value >= 0); for (shift in intArrayOf(24, 16, 8, 0)) out.write((value ushr shift) and 0xff)
    }
    fun long(value: Long): DocumentedLayout = apply {
        require(value >= 0); for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xff).toInt())
    }
    fun bool(value: Boolean): DocumentedLayout = apply { out.write(if (value) 1 else 0) }
    fun field(value: ByteArray): DocumentedLayout = apply { int(value.size); out.write(value) }
    fun string(value: String): DocumentedLayout = field(value.encodeToByteArray())
    fun id(value: DomainId): DocumentedLayout = string(value.value)
    fun bytes(): ByteArray = out.toByteArray()
}

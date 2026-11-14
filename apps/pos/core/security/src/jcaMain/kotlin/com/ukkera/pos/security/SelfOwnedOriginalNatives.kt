package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId

/**
 * Platform-native custody behind the shared original installation owner. Android supplies its
 * Application/KeyStore/Os implementations; desktop supplies Windows/macOS ones. Every value is an
 * observation of the exact platform object that created it; nothing here grants authority, and a
 * platform without a qualified implementation fails closed (UNSUPPORTED_PLATFORM), never a fallback.
 */
internal interface SelfOwnedOriginalPlatform {
    val process: SelfOwnedProcessPlatform
    fun captureEnvironment(work: AndroidOwnedInstallationWork, profile: SelfOwnedNativeProfileV1,
        preparation: Boolean): SelfOwnedOriginalEnvironment
    fun databaseNamespace(environment: SelfOwnedOriginalEnvironment, work: AndroidOwnedInstallationWork,
        ownerToken: Any): SelfOwnedOriginalDatabaseNamespace
    fun signing(environment: SelfOwnedOriginalEnvironment, journal: AndroidSelfOwnedInstallationJournal,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalSigning
    fun dataKeys(environment: SelfOwnedOriginalEnvironment, journal: AndroidSelfOwnedInstallationJournal,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalDataKeys
    fun masterSdkOwner(work: AndroidOwnedInstallationWork): SelfOwnedMasterSdkOwner
    fun provisionerRegistry(environment: SelfOwnedOriginalEnvironment, work: AndroidOwnedInstallationWork): SelfOwnedOriginalRegistryStore
    fun provisionerKeys(environment: SelfOwnedOriginalEnvironment, registry: SelfOwnedOriginalRegistryStore,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalProvisionerKeys
    fun historyWorkspace(source: AndroidSelfOwnedHistoryReadStorage, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, original: OriginalNativeHistorySnapshot): SelfOwnedHistoryWorkspaceStore
    fun receiptMembership(owner: AndroidSelfOwnedInstallationLifetime, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, readIdentity: Any?): SelfOwnedReceiptMembershipStore
}

/**
 * OS-observed installed target, boot and principal facts captured once per native lifetime. The
 * platform selects the format through these facts alone ([originalFormat]): Android supplies its
 * legacy target, boot count and application UID; Windows/macOS supply a native target binding,
 * the boot-ordinal ledger of the actual boot session and the actual SID/uid. No field is optional.
 */
internal interface SelfOwnedOriginalEnvironment {
    val profile: SelfOwnedNativeProfileV1
    /** The installed target as a format-neutral binding (Android legacy, Windows or macOS). */
    val installedTarget: SelfOwnedInstalledTargetBindingV1
    /** Actual OS boot observation (Android boot count or desktop boot-session ledger). */
    val bootMark: SelfOwnedBootMark
    /** OS boot ordinal used for throttles and same-boot ordering; never a session substitute. */
    val bootCount: Int get() = bootMark.ordinal
    /** Actual OS principal that owns the original namespace. */
    val principal: SelfOwnedProcessPrincipal
    /** Reject a creating observation profile BEFORE any re-observation for an original reader. */
    fun requireExistingOriginalObservation()
    /** Actual fixed provisioner volume; no caller-selected path or volume identity. */
    fun currentProvisionerVolumeUuid(): String
    fun requireCurrent()
    /** Refuses a key protection this platform cannot actually provide (UNSUPPORTED_PLATFORM). */
    fun requireSupportedProtection(protection: SelfOwnedKeyProtectionV1)
    /**
     * Desktop (V2) only: the retained boot-ordinal ledger chain from genesis (ordinal 0) through the
     * CURRENT boot ledger of [bootMark]. The core verifies the chain ([SelfOwnedBootChainV2]) and
     * orders key-intent boots only from it. Platform contract: append exactly one
     * [SelfOwnedBootOrdinalLedgerV2.advance] step per new OS boot session (verifySuccessor-checked)
     * in an OS-protected store owned by [principal]; never truncate, rewrite or restore it together
     * with older original state; refuse to open if it cannot. Android never supplies one (V1 uses
     * the OS boot count). The empty default fails every V2 ordering check closed.
     */
    fun bootLedgerChain(): List<SelfOwnedBootOrdinalLedgerV2> = emptyList()
}

internal enum class OriginalDatabaseUse { STABLE_READ, ROLLBACK_PAGER }

internal class OriginalDatabasePin internal constructor(
    internal val path: String, internal val binding: NativeHistoryDatabaseIdentity,
    internal val use: OriginalDatabaseUse,
) { override fun toString(): String = "OriginalDatabasePinV2([REDACTED])" }

/** Original private namespace: intent/state files, history anchor and the pinned Room database. */
internal interface SelfOwnedOriginalDatabaseNamespace {
    val createdThisLifetime: Boolean
    fun openNative(approval: SelfOwnedOriginalApproval?)
    fun identity(): SelfOwnedOriginalNamespaceIdentity
    fun readIntent(): ByteArray?
    fun readState(): ByteArray?
    fun retainIntent(bytes: ByteArray)
    fun retainState(bytes: ByteArray)
    fun readHistoryAnchorBytes(): ByteArray?
    fun retainHistoryAnchorBytes(bytes: ByteArray)
    fun retainHistoryOriginIntent(allocation: PayloadDigest)
    fun prepareRoomDatabaseFile(original: NativeHistoryAnchorRecordV2): String
    fun observeRoomDatabaseIdentity(path: String, roomIdentity: String): NativeHistoryDatabaseIdentity
    fun historyNamespaceDigest(): PayloadDigest
    fun pinOriginalDatabase(binding: NativeHistoryDatabaseIdentity, use: OriginalDatabaseUse): OriginalDatabasePin
    fun assertOriginalDatabasePin(pin: OriginalDatabasePin)
    fun closeOriginalDatabasePin(pin: OriginalDatabasePin)
    fun reobserveOriginalDatabaseAfterActualClose(pin: OriginalDatabasePin)
    fun assertContinuity()
    fun closeNative()
}

/** The original target signing key (non-exportable) and the signatures it produces. */
internal interface SelfOwnedOriginalSigning {
    fun assertAliasNamespace()
    fun prepareOriginalSigningKey()
    fun assertOriginalSigningKey()
    fun originalPoint(): ByteArray
    fun verifyOriginalSource()
    /** Android returns [SelfOwnedNativeBindingV1]; Windows/macOS return [SelfOwnedNativeBindingV2]. */
    fun createOriginalBinding(): SelfOwnedNativeOriginalBinding
    fun validateOriginalBinding(binding: SelfOwnedNativeOriginalBinding)
    fun signJournalEnvelope(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray
    fun openOriginalJournalState(aad: ByteArray, envelope: SelfOwnedOriginalJournalEnvelope,
        material: SelfOwnedOriginalDataKeys): SelfOwnedOriginalJournalState
    fun originalHistoryAllocation(binding: SelfOwnedNativeOriginalBinding,
        initial: AndroidOriginalCompanionInitialization? = null): OriginalAllocationCommitmentV2
    fun signHistoryEnvelope(allocation: OriginalAllocationCommitmentV2, aad: ByteArray, nonce: ByteArray,
        ciphertext: ByteArray, initial: AndroidOriginalCompanionInitialization? = null): ByteArray
    fun verifyHistoryEnvelope(allocation: OriginalAllocationCommitmentV2, aad: ByteArray,
        envelope: NativeHistoryEnvelopeV2, initial: AndroidOriginalCompanionInitialization? = null)
    fun verifyOriginalHumanApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        proof: LocalInstallationSignedApprovalV2)
    fun verifyOriginalTargetApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        human: LocalInstallationSignedApprovalV2, proof: LocalInstallationSignedApprovalV2)
    fun signOriginalTargetApproval(anchor: AndroidSelfOwnedHistoryAnchor, attempt: OriginalInstallationAttempt,
        human: LocalInstallationSignedApprovalV2): LocalInstallationSignedApprovalV2
    fun verifyOriginalAcknowledgement(value: LocalInstallationSignedAcknowledgementV2)
}

/** The configured storage/pepper key identities, independent of the native key store. */
internal data class SelfOwnedDataKeyConfiguration(
    val installationId: DomainId,
    val scope: BranchScope,
    val storageReference: ProtectedStorageKeyReference,
    val pepperReference: ProtectedPepperReference,
)

/**
 * Original non-exportable storage (AES-256-GCM, 12-byte IV, 128-bit tag) and pepper (HMAC-SHA256)
 * keys. Key objects never cross this seam; only the operations do.
 */
internal interface SelfOwnedOriginalDataKeys {
    fun prepareOriginalDataKey(role: SelfOwnedTargetKeyRoleV1)
    fun assertOriginalDataKeys()
    fun assertOriginalPepperKey()
    /** Re-checks the exact original storage key (no extra guard), as after each storage use. */
    fun requireStorageKey()
    /** Re-checks the exact original pepper key (no extra guard), as after each pepper use. */
    fun requirePepperKey()
    /** Native-randomized-IV seal; an IV/parameter/size contract miss fails with [contractFailure]. */
    fun sealStorage(aad: ByteArray, plaintext: ByteArray, contractFailure: SelfOwnedInstallationFailureV1): AndroidSelfOwnedCiphertext
    /** One-shot authenticated open; the caller checks sizes and then [requireStorageKey]. */
    fun openStorage(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray
    /** Raw HMAC-SHA256 tag; the caller checks the size and then [requirePepperKey]. */
    fun pepperTag(message: ByteArray): ByteArray
    fun keyConfiguration(): SelfOwnedDataKeyConfiguration
    fun sealJournal(aad: ByteArray, plaintext: ByteArray): AndroidSelfOwnedCiphertext
    fun openJournal(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray
}

/** The official P2pKit SDK identity owned by this Master installation. */
internal interface SelfOwnedMasterSdkOwner {
    fun createNative(exactAppId: AppId, exactDeviceName: String)
    /** Android captures [LocalMasterSdkOriginalV1]; Windows/macOS capture [LocalMasterSdkOriginalV2]. */
    fun captureMasterNative(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
        environment: SelfOwnedOriginalEnvironment): LocalMasterSdkOriginal
    fun requireMatchesNative(original: LocalMasterSdkOriginal)
    fun closeNative()
}

/**
 * Version-neutral original provisioner registry: only what the shared owner needs to read the
 * single P1-retained original preparation. Company/installer workflows use the Android V1
 * [LocalProvisionerRegistryStore]; a registry that is not one refuses them (UNSUPPORTED_PLATFORM).
 */
internal interface SelfOwnedOriginalRegistryStore {
    fun openNative()
    fun openExistingNative()
    fun assertContinuity()
    fun closeNative()
    fun originalBootstrap(): SelfOwnedOriginalBootstrap?
    /** Authenticated registry state, or null when absent. */
    fun readOriginalRegistryState(keys: SelfOwnedOriginalProvisionerKeys): SelfOwnedOriginalRegistryState?
}

/** Encrypted, anti-rollback Android V1 provisioner registry files. */
internal interface LocalProvisionerRegistryStore : SelfOwnedOriginalRegistryStore {
    override fun originalBootstrap(): SelfOwnedOriginalBootstrap? = bootstrapRecord()?.let { SelfOwnedOriginalBootstrap.V1(it) }
    /** The V1 registry is read only with the V1 keys of the same Android provisioner; no cross-version fallback. */
    override fun readOriginalRegistryState(keys: SelfOwnedOriginalProvisionerKeys): SelfOwnedOriginalRegistryState? =
        readState(keys.legacyProvisionerKeys())?.let { SelfOwnedOriginalRegistryState.V1(it) }
    fun bootstrapRecord(): SelfOwnedBootstrapV1?
    fun companyBootstrapRecord(): LocalCompanyAuthorityBootstrapV1?
    fun namespaceIdentity(): SelfOwnedRegistryNamespaceV1
    fun retainBootstrap(next: SelfOwnedBootstrapV1)
    fun retainCompanyBootstrap(next: LocalCompanyAuthorityBootstrapV1)
    fun retainEnrollmentStart()
    fun readState(keys: LocalProvisionerNativeKeys): LocalProvisionerRegistryStateV1?
    fun retainState(next: LocalProvisionerRegistryStateV1, keys: LocalProvisionerNativeKeys): LocalProvisionerRegistryStateV1
    fun readCompanyState(keys: LocalProvisionerNativeKeys, diagnoseRetentionReadback: Boolean = false): LocalCompanyAuthorityStateV1?
    fun retainCompanyState(next: LocalCompanyAuthorityStateV1, keys: LocalProvisionerNativeKeys): LocalCompanyAuthorityStateV1
    fun readBusinessPublications(keys: LocalProvisionerNativeKeys): NativeCompanyBusinessPublicationStateV1?
    fun retainBusinessPublications(next: NativeCompanyBusinessPublicationStateV1,
        keys: LocalProvisionerNativeKeys): NativeCompanyBusinessPublicationStateV1
    fun readOriginalSerialCustody(keys: LocalProvisionerNativeKeys): OriginalLocalSerialCustodyReferenceV1
    fun recoverOriginalSerialCustody(originalActionId: DomainId, keys: LocalProvisionerNativeKeys): OriginalLocalSerialCustodyReferenceV1
    fun readEmployeeEnrollments(keys: LocalProvisionerNativeKeys): NativeEmployeeEnrollmentStateV1?
    fun retainEmployeeEnrollments(next: NativeEmployeeEnrollmentStateV1, keys: LocalProvisionerNativeKeys): NativeEmployeeEnrollmentStateV1
    fun readEmployeePolicyActivations(keys: LocalProvisionerNativeKeys): NativeEmployeePolicyActivationStateV1?
    fun retainEmployeePolicyActivations(next: NativeEmployeePolicyActivationStateV1,
        keys: LocalProvisionerNativeKeys): NativeEmployeePolicyActivationStateV1
    fun readDevicePairings(keys: LocalProvisionerNativeKeys): NativeDevicePairingStateV1?
    fun retainDevicePairings(next: NativeDevicePairingStateV1, keys: LocalProvisionerNativeKeys): NativeDevicePairingStateV1
    fun devicePairingReservedIdentityIds(keys: LocalProvisionerNativeKeys): Set<DomainId>
    fun readDeviceAdmissions(keys: LocalProvisionerNativeKeys): NativeDeviceAdmissionStateV1?
    fun retainDeviceAdmissions(next: NativeDeviceAdmissionStateV1, keys: LocalProvisionerNativeKeys): NativeDeviceAdmissionStateV1
    fun deviceAdmissionReservedIdentityIds(keys: LocalProvisionerNativeKeys): Set<DomainId>
    fun requireCompanyCapacity(next: LocalCompanyAuthorityStateV1)
}

/** Same-fact comparison of retained company facts (no native state). */
internal fun sameProvisionerCompanyFacts(left: SelfOwnedCompanyFactsV1, right: SelfOwnedCompanyFactsV1): Boolean =
    left.companyName == right.companyName && left.responsiblePersonName == right.responsiblePersonName &&
        left.installerLogin == right.installerLogin && left.protection == right.protection

/**
 * Format-neutral provisioner key custody: the four original roles (root, issuer, registry,
 * installer pepper) as non-exportable native keys. Only the original preparation read needs this
 * neutral view; every write or company workflow uses exactly one versioned sub-interface.
 */
internal interface SelfOwnedOriginalProvisionerKeys {
    val originalKeysFormat: SelfOwnedOriginalFormat
    fun assertOriginalNamespace()
    fun assertCompletedOriginalKeys()
    fun prepareOriginalKeys()
    fun assertAllOriginalKeys()
    fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray
}

/** Android V1 view; any other key custody is UNSUPPORTED_PLATFORM, never adapted. */
internal fun SelfOwnedOriginalProvisionerKeys.legacyProvisionerKeys(): LocalProvisionerNativeKeys =
    this as? LocalProvisionerNativeKeys ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)

/**
 * Windows/macOS (V2) provisioner keys. Root/delegation documents keep the V1 document shape (the
 * V2 registry reuses them), while preparation grants are V2 installed-identity documents and the
 * registry is sealed in the disjoint V2 envelope. Private keys NEVER export.
 */
internal interface SelfOwnedProvisionerNativeKeysV2 : SelfOwnedOriginalProvisionerKeys {
    override val originalKeysFormat: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
    fun rootBody(): SelfOwnedRootBodyV1
    fun rootAnnouncement(): SelfOwnedSignedDocumentV1
    fun issuerDelegation(root: SelfOwnedSignedDocumentV1): SelfOwnedSignedDocumentV1
    fun signOriginalPreparationV2(body: SelfOwnedInstalledIdentityPreparationV2): SelfOwnedInstalledIdentitySignedDocumentV2
    fun installerTag(message: ByteArray): ByteArray
    fun sealRegistryV2(aad: ByteArray, plaintext: ByteArray): SelfOwnedRegistryEnvelopeV2
    fun openRegistryV2(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV2): ByteArray
}

/** Four legacy roles, or the explicit seven-role company profile; private keys NEVER export. */
internal interface LocalProvisionerNativeKeys : SelfOwnedOriginalProvisionerKeys {
    override val originalKeysFormat: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
    fun rootBody(): SelfOwnedRootBodyV1
    fun rootAnnouncement(): SelfOwnedSignedDocumentV1
    fun issuerDelegation(root: SelfOwnedSignedDocumentV1): SelfOwnedSignedDocumentV1
    fun signOriginalPreparation(body: SelfOwnedPreparationBodyV1): SelfOwnedSignedDocumentV1
    fun installerTag(message: ByteArray): ByteArray
    fun sealRegistry(aad: ByteArray, plaintext: ByteArray): SelfOwnedRegistryEnvelopeV1
    fun openRegistry(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV1): ByteArray
    fun prepareCompanyKeys()
    fun assertAllCompanyKeys()
    fun headPoint(role: LocalCompanyHeadKeyRoleV1): ByteArray
    fun headEnrollmentBody(): LocalCompanyHeadEnrollmentBodyV1
    fun signFreshHeadEnrollment(): LocalCompanyHeadEnrollmentV1
    fun headTag(message: ByteArray): ByteArray
    fun signBusinessPublicationEndorsement(record: NativeCompanyBusinessPublicationRecordV1,
        approval: AndroidLocalInstallerAuthentication.BusinessPublicationHeadApproval): ByteArray
    fun verifyBusinessPublication(record: NativeCompanyBusinessPublicationRecordV1)
    fun signEmployeeEnrollmentEndorsement(record: NativeEmployeeEnrollmentRecordV1,
        approval: AndroidLocalInstallerAuthentication.EmployeeEnrollmentHeadApproval): ByteArray
    fun verifyEmployeeEnrollment(record: NativeEmployeeEnrollmentRecordV1)
    fun signEmployeePolicyActivationEndorsement(record: NativeEmployeePolicyActivationRecordV1,
        approval: AndroidLocalInstallerAuthentication.EmployeePolicyActivationHeadApproval): ByteArray
    fun verifyEmployeePolicyActivation(record: NativeEmployeePolicyActivationRecordV1)
    fun signDevicePairingEndorsement(record: NativeDevicePairingRecordV1,
        approval: AndroidLocalInstallerAuthentication.DevicePairingHeadApproval,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray
    fun verifyDevicePairing(record: NativeDevicePairingRecordV1)
    fun signUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray
    fun verifyUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1)
    fun signCompanyPolicy(record: LocalCompanyPolicyRecordV1,
        approval: AndroidLocalInstallerAuthentication.HeadApproval): PurchasingSignedPolicyV1
    fun signOriginalInstallationApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        approval: AndroidLocalInstallerAuthentication.HeadApproval): LocalInstallationSignedApprovalV2
    fun signOriginalInstallationAcknowledgement(frame: LocalInstallationCommitAcknowledgementV2): LocalInstallationSignedAcknowledgementV2
}

/** Original history scratch workspace (derived data only; never authority). */
internal interface SelfOwnedHistoryWorkspaceStore : SelfOwnedNativeHistoryWorkspaceController {
    val actuallyClosedNative: Boolean
    fun openNative()
    fun closeNative()
}

internal enum class SelfOwnedReceiptMembershipState {
    NEW, OPENING, BUILDING, PARKING, PARKED, REOPENING, PREPARED, ACTIVE, CLOSING, CLOSED, UNKNOWN
}

/** Receipt-membership data file owned by one installation lifetime. */
internal interface SelfOwnedReceiptMembershipStore : SelfOwnedNativeReceiptMembershipDataController {
    val stateNative: SelfOwnedReceiptMembershipState
    val readIdentityNative: Any?
    fun belongsToReadNative(identity: Any): Boolean
    fun requireActuallyParkedNative()
    fun requireCompletedBuildParkedNative()
    fun requirePreparedForDeliveryNoIo()
    fun activatePreparedNoIo()
    fun abandonDeliveryNoIo()
    fun denyUseNoIo()
    fun openNative()
    fun reopenAfterClosedReadNative()
    fun closeNative()
}

/** The original Master SDK DATA names exactly this environment's and the opened material's target. */
internal fun LocalMasterSdkOriginal.namesInstalledTarget(environment: SelfOwnedOriginalEnvironment,
    material: SelfOwnedNativeMaterialView): Boolean =
    installedTarget.matches(environment.installedTarget) && installedTarget.matches(material.installedTarget)

/**
 * Shared JCA AES-256-GCM / HMAC-SHA256 operations for a native storage/pepper key that the JCA
 * provider holds (Android KeyStore today). The key never leaves the calling native implementation;
 * the provider chooses the randomized IV and any IV/parameter/size contract miss fails closed.
 */
internal object SelfOwnedJcaStorageCipher {
    fun seal(key: javax.crypto.SecretKey, aad: ByteArray, plaintext: ByteArray,
        contractFailure: SelfOwnedInstallationFailureV1): AndroidSelfOwnedCiphertext {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key) // Native provider chooses the randomized IV.
        val nonce = cipher.iv ?: selfOwnedFail(contractFailure)
        var ciphertext: ByteArray? = null
        try {
            val parameters = cipher.parameters?.getParameterSpec(javax.crypto.spec.GCMParameterSpec::class.java)
                ?: selfOwnedFail(contractFailure)
            if (nonce.size != ProtectedLocalStorageFormat.NONCE_BYTES || parameters.tLen != ProtectedLocalStorageFormat.TAG_BYTES * 8 ||
                !parameters.iv.contentEquals(nonce)) selfOwnedFail(contractFailure)
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext); ciphertext = encrypted
            if (encrypted.size != plaintext.size + ProtectedLocalStorageFormat.TAG_BYTES) selfOwnedFail(contractFailure)
            return AndroidSelfOwnedCiphertext(nonce.copyOf(), encrypted.copyOf())
        } finally { nonce.fill(0); ciphertext?.fill(0) }
    }

    /** One-shot authenticated decrypt; no unauthenticated update() plaintext escapes. */
    fun open(key: javax.crypto.SecretKey, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
            javax.crypto.spec.GCMParameterSpec(ProtectedLocalStorageFormat.TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun tag(key: javax.crypto.SecretKey, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(message)
    }
}

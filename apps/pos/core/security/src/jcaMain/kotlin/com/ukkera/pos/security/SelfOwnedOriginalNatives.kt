package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import javax.crypto.SecretKey
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
    fun provisionerRegistry(environment: SelfOwnedOriginalEnvironment, work: AndroidOwnedInstallationWork): LocalProvisionerRegistryStore
    fun provisionerKeys(environment: SelfOwnedOriginalEnvironment, registry: LocalProvisionerRegistryStore,
        work: AndroidOwnedInstallationWork): LocalProvisionerNativeKeys
    fun historyWorkspace(source: AndroidSelfOwnedHistoryReadStorage, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, original: OriginalNativeHistorySnapshot): SelfOwnedHistoryWorkspaceStore
    fun receiptMembership(owner: AndroidSelfOwnedInstallationLifetime, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, readIdentity: Any?): SelfOwnedReceiptMembershipStore
}

/** OS-observed installed target, boot and directory facts captured once per native lifetime. */
internal interface SelfOwnedOriginalEnvironment {
    val profile: SelfOwnedNativeProfileV1
    val target: SelfOwnedTargetDescriptorV1
    /** OS boot ordinal used for same-boot exclusion of a possibly still running native generation. */
    val bootCount: Int
    val uid: Int
    /** Reject a creating observation profile BEFORE any re-observation for an original reader. */
    fun requireExistingOriginalObservation()
    /** Actual fixed provisioner volume; no caller-selected path or volume identity. */
    fun currentProvisionerVolumeUuid(): String
    fun requireCurrent()
    /** Refuses a key protection this platform cannot actually provide (UNSUPPORTED_PLATFORM). */
    fun requireSupportedProtection(protection: SelfOwnedKeyProtectionV1)
}

internal enum class OriginalDatabaseUse { STABLE_READ, ROLLBACK_PAGER }

internal class OriginalDatabasePin internal constructor(
    internal val path: String, internal val binding: NativeHistoryDatabaseIdentityV2,
    internal val use: OriginalDatabaseUse,
) { override fun toString(): String = "OriginalDatabasePinV2([REDACTED])" }

/** Original private namespace: intent/state files, history anchor and the pinned Room database. */
internal interface SelfOwnedOriginalDatabaseNamespace {
    val createdThisLifetime: Boolean
    fun openNative(approval: SelfOwnedNativeApproval?)
    fun identity(): SelfOwnedNativeNamespaceV1
    fun readIntent(): ByteArray?
    fun readState(): ByteArray?
    fun retainIntent(bytes: ByteArray)
    fun retainState(bytes: ByteArray)
    fun readHistoryAnchorBytes(): ByteArray?
    fun retainHistoryAnchorBytes(bytes: ByteArray)
    fun retainHistoryOriginIntent(allocation: PayloadDigest)
    fun prepareRoomDatabaseFile(original: NativeHistoryAnchorRecordV2): String
    fun observeRoomDatabaseIdentity(path: String, roomIdentity: String): NativeHistoryDatabaseIdentityV2
    fun historyNamespaceDigest(): PayloadDigest
    fun pinOriginalDatabase(binding: NativeHistoryDatabaseIdentityV2, use: OriginalDatabaseUse): OriginalDatabasePin
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
    fun createOriginalBinding(): SelfOwnedNativeBindingV1
    fun validateOriginalBinding(binding: SelfOwnedNativeBindingV1)
    fun signJournalEnvelope(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray
    fun openOriginalJournalState(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV1,
        material: SelfOwnedOriginalDataKeys): SelfOwnedNativeJournalStateV1
    fun originalHistoryAllocation(binding: SelfOwnedNativeBindingV1,
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

/** Original non-exportable storage (AES-GCM) and pepper (HMAC) keys. */
internal interface SelfOwnedOriginalDataKeys {
    fun prepareOriginalDataKey(role: SelfOwnedTargetKeyRoleV1)
    fun assertOriginalDataKeys()
    fun assertOriginalPepperKey()
    fun storageKey(): SecretKey
    fun pepperKey(): SecretKey
    fun keyConfiguration(): SelfOwnedDataKeyConfiguration
    fun sealJournal(aad: ByteArray, plaintext: ByteArray): AndroidSelfOwnedCiphertext
    fun openJournal(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray
}

/** The official P2pKit SDK identity owned by this Master installation. */
internal interface SelfOwnedMasterSdkOwner {
    fun createNative(exactAppId: AppId, exactDeviceName: String)
    fun captureMasterNative(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
        nativeApplication: SelfOwnedTargetDescriptorV1): LocalMasterSdkOriginalV1
    fun requireMatchesNative(original: LocalMasterSdkOriginalV1)
    fun closeNative()
}

/** Encrypted, anti-rollback provisioner registry files. */
internal interface LocalProvisionerRegistryStore {
    fun openNative()
    fun openExistingNative()
    fun bootstrapRecord(): SelfOwnedBootstrapV1?
    fun companyBootstrapRecord(): LocalCompanyAuthorityBootstrapV1?
    fun namespaceIdentity(): SelfOwnedRegistryNamespaceV1
    fun assertContinuity()
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
    fun closeNative()
}

/** Same-fact comparison of retained company facts (no native state). */
internal fun sameProvisionerCompanyFacts(left: SelfOwnedCompanyFactsV1, right: SelfOwnedCompanyFactsV1): Boolean =
    left.companyName == right.companyName && left.responsiblePersonName == right.responsiblePersonName &&
        left.installerLogin == right.installerLogin && left.protection == right.protection

/** Four legacy roles, or the explicit seven-role company profile; private keys NEVER export. */
internal interface LocalProvisionerNativeKeys {
    fun assertOriginalNamespace()
    fun assertCompletedOriginalKeys()
    fun prepareOriginalKeys()
    fun assertAllOriginalKeys()
    fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray
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

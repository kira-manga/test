package com.ukkera.pos.app.setup

import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.StateFlow

/**
 * UI commands only. An implementation is NOT an installation verifier, native context,
 * authenticated employee, runtime gateway or approval producer. Every mutation must recheck
 * the real retained native action; a review ID is only a stale-UI discriminator.
 *
 * Password arrays transfer on entry, including denial/invalid input. Implementations admit
 * before private copying, clear both original arrays and never queue mutable caller inputs.
 * UI disposal/backgrounding locks presentation; it does not cancel owned native work or close
 * the branch master. The platform service/root, not this interface, owns physical close.
 */
public interface LocalSetupController {
    public val state: StateFlow<LocalSetupView>
    public fun chooseNewLocalCompany()
    /** Existing-only P1 authority/pre-target recovery, never a creating factory on missing files. */
    public fun inspectOriginalCompany()
    /** Explicit continuation of the native owner's original key intents; accepts no new facts. */
    public fun resumeOriginalCompany()
    /** Existing-only P2/P3 inspection, separate from original pre-target company recovery. */
    public fun inspectOriginalInstallation()
    public fun prepareNewLocalCompany(input: LocalSetupNewCompanyInput)
    public fun completeNewLocalCompany(reviewId: DomainId, installerSecret: CharArray, headSecret: CharArray)
    public fun prepareOriginalBranch(branchName: String)
    public fun issueOriginalBranch(reviewId: DomainId, installerSecret: CharArray)
    public fun prepareOriginalNativeTarget()
    /** Existing-only original owner; distinct from the read-only historical receipt route. */
    public fun openOriginalFirstAccount()
    public fun prepareFirstAccount(input: LocalSetupFirstAccountInput)
    public fun installFirstAccount(reviewId: DomainId, headSecret: CharArray, employeePassword: CharArray)
    /** Same retained installation only; no fresh approval, credential or SQL initializer. */
    public fun resumeOriginalInstallationAcknowledgement()
    /** Explicit once-only original cohost journals; never a missing-state recovery shortcut. */
    public fun enrollOriginalLocalClient() { }
    /** Only the positive completed-first/untouched-second enrollment boundary; no reset. */
    public fun continueOriginalLocalClientEnrollment() { }
    /** Existing-only reopening, independent from enrollment and interactive employee sign-in. */
    public fun reconnectOriginalLocalClient() { }
    /** Existing native-fenced ciphertext only; never new enrollment or an invented result. */
    public fun recoverOriginalLocalClientWrites() { }
    /** Dedicated genuine head/full-policy ceremony; never the minimal fresh-ID enrollment form. */
    public fun openEmployeeEnrollment() { }
    public fun beginEmployeeEnrollment() { }
    public fun inspectEmployeeEnrollment(originalRequestId: DomainId) { }
    public fun prepareEmployeeEnrollment(account: LocalAccountProvisioning) { }
    /** Head buffer transfers on all outcomes. Approval is not employee sign-in or device pairing. */
    public fun approveEmployeeEnrollment(reviewId: DomainId, headSecret: CharArray) { headSecret.fill('\u0000') }
    /** Once submitted, this original becomes lookup-only until a native terminal result is observed. */
    public fun enrollApprovedEmployee(reviewId: DomainId, employeePassword: CharArray) { employeePassword.fill('\u0000') }
    public fun enrollEmployeeIntentJournal(reviewId: DomainId) { }
    public fun reopenEmployeeIntentJournal(reviewId: DomainId) { }
    public fun recoverEmployeeIntentJournalWrites(reviewId: DomainId) { }
    /** Explicit completion after actual journal acquisition; keeps all original records. */
    public fun finishEmployeeEnrollment(reviewId: DomainId) { }
    /** Complete existing-employee policy ceremony on the original native setup connection. */
    public fun openEmployeePolicies() { }
    public fun closeEmployeePolicies() { }
    public fun beginEmployeePolicy() { }
    public fun inspectEmployeePolicy(originalRequestId: DomainId) { }
    public fun prepareEmployeePolicy(reviewId: DomainId, nextPolicy: ActivatedLocalPolicy) { }
    /** The named-head secret transfers on every outcome. This approves without activation. */
    public fun approveEmployeePolicy(reviewId: DomainId, headSecret: CharArray) { headSecret.fill('\u0000') }
    /** Separate explicit confirmation of the unchanged original approved complete policy. */
    public fun activateApprovedEmployeePolicy(reviewId: DomainId) { }
    /** Read/recovery of the selected original only; never prepares, signs or dispatches. */
    public fun lookupEmployeePolicy(reviewId: DomainId) { }
    /** Leave an unsubmitted editor or a native-confirmed terminal original, retaining its history. */
    public fun finishEmployeePolicy(reviewId: DomainId) { }
    /** Retained native originals only. No new target/Kit, imported approval or employee login. */
    public fun openDeviceAdmissions() { }
    public fun inspectDeviceAdmission(originalActionId: DomainId) { }
    /** The named-head buffer transfers on every outcome; approval does not perform admission. */
    public fun approveDeviceAdmission(reviewId: DomainId, headSecret: CharArray) { headSecret.fill('\u0000') }
    /** Separate explicit once-only original dispatch, with durable intent retained first. */
    public fun admitApprovedDevice(reviewId: DomainId) { }
    /** Read-only original native/protected outcome; no reapproval, renewal or redispatch. */
    public fun lookupDeviceAdmission(reviewId: DomainId) { }
    public fun closeDeviceAdmissions() { }
    /** Distinct physical target role. Reviewing exact inputs prepares no native key or Kit. */
    public fun chooseFreshDeviceTarget() { }
    public fun reviewFreshDeviceTarget(input: LocalSetupFreshDeviceTargetInput) { }
    public fun prepareFreshDeviceTarget(reviewId: DomainId) { }
    public fun acquireFreshDeviceSdkIdentity(reviewId: DomainId) { }
    /** Existing-only target owner; never falls back to preparation or SDK acquisition. */
    public fun inspectOriginalFreshDeviceTarget() { }
    /** Fresh pairing through the already-operational original branch Master/client. */
    public fun openFreshDevicePairing() { }
    public fun beginFreshDevicePairing() { }
    public fun inspectFreshDevicePairing(originalRequestId: DomainId) { }
    public fun importFreshDeviceIdentity(reviewId: DomainId) { }
    public fun prepareFreshDeviceChallenge(reviewId: DomainId) { }
    public fun importFreshDeviceChallenge(reviewId: DomainId) { }
    public fun respondFreshDeviceChallenge(reviewId: DomainId) { }
    public fun importFreshDevicePossession(reviewId: DomainId) { }
    /** Import/review public original approval/root DATA without selecting a trusted Master. */
    public fun importApprovedPairingMaster(reviewId: DomainId) { }
    /** Explicit native validation and immutable original retention; no admission or login. */
    public fun verifyApprovedPairingMaster(reviewId: DomainId) { }
    /** Existing-only native readback, including after an uncertain import. */
    public fun recoverApprovedPairingMaster(reviewId: DomainId) { }
    public fun proveFreshDeviceTarget(reviewId: DomainId) { }
    /** Read-only original review; explicit retirement remains separate. */
    public fun reviewUnsignedDevicePairingRetirement(reviewId: DomainId) { }
    public fun retireUnsignedDevicePairing(reviewId: DomainId) { }
    public fun importUnsignedDevicePairingRetirement(reviewId: DomainId) { }
    public fun continueUnsignedDevicePairing(reviewId: DomainId) { }
    /** Only exact retained native DATA can be exported; I/O success grants no authority. */
    public fun exportFreshDeviceDocument(reviewId: DomainId, kind: LocalSetupPairingDocumentKind) { }
    public fun recoverFreshDevicePairing(reviewId: DomainId) { }
    /** Separate review/head/admission route after actual original Master proof confirmation. */
    public fun handoffFreshDeviceAdmission(reviewId: DomainId) { }
    public fun closeFreshDevicePairing() { }
    /** Existing operational Master only. Does not reopen, install or authenticate an employee. */
    public fun startLanHost(input: LocalSetupLanInput) { }
    /** Transfers the retained approved fresh target; no supplied trust store or pin. */
    public fun connectLanClient(input: LocalSetupLanInput, endpoint: LocalSetupLanEndpoint) { }
    /** Explicitly reobserves this same attachment; never reconnects or retargets automatically. */
    public fun refreshLanAttachment() { }
    /** Terminal for this attachment lifetime, not an instruction to stop the Master. */
    public fun closeLanAttachment() { }
    public fun refreshOriginalStatus()
    public fun lockPresentation()
    public fun resumePresentation()
}

/** Complete requested account data, never an implicit administrator or an enrollment permit. */
public class LocalSetupFirstAccountInput(
    public val account: LocalAccountProvisioning,
    public val authorityEpoch: Revision,
) {
    override fun toString(): String = "LocalSetupFirstAccountInput([REDACTED])"
}

/** Non-secret owner-entered facts. No default passwords, implicit head or imported company root. */
public class LocalSetupNewCompanyInput(
    public val companyName: String,
    public val installerName: String,
    public val installerLogin: String,
    public val headName: String,
    public val headLogin: String,
    public val protection: SelfOwnedKeyProtectionV1,
) {
    init {
        require(companyName.length in 1..160 && installerName.length in 1..160 && headName.length in 1..128)
        require(installerLogin.length in 3..64 && headLogin.length in 3..64)
    }
    override fun toString(): String = "LocalSetupNewCompanyInputV1([REDACTED])"
}

public enum class LocalSetupPhase {
    CHOOSE_ROUTE,
    NEW_COMPANY_DETAILS,
    COMPANY_REVIEW,
    ORIGINAL_COMPANY_PENDING,
    COMPANY_RETAINED,
    BRANCH_REVIEW,
    ORIGINAL_PREPARATION_RETAINED,
    ORIGINAL_TARGET_RETAINED,
    ORIGINAL_RECEIPT_FOUND,
    FIRST_ACCOUNT_DETAILS,
    FIRST_ACCOUNT_REVIEW,
    INSTALLATION_ACK_PENDING,
    INSTALLATION_RETAINED,
    EMPLOYEE_ENROLLMENT,
    ORIGINAL_UNRESOLVED,
    CLOSING,
    CLOSED,
    EMPLOYEE_POLICY,
    DEVICE_ADMISSION,
    FRESH_DEVICE_PAIRING,
}

public enum class LocalSetupWork {
    OPENING_LOCAL_AUTHORITY, PREPARING_COMPANY, RETAINING_COMPANY,
    READING_ORIGINAL_COMPANY, RESUMING_ORIGINAL_COMPANY,
    INSPECTING_LOCAL_AUTHORITY, PREPARING_BRANCH, RETAINING_PREPARATION,
    PREPARING_ORIGINAL_TARGET, READING_ORIGINAL_RECEIPT, CLOSING_NATIVE_OWNER,
    OPENING_ORIGINAL_INSTALLATION, REVIEWING_FIRST_ACCOUNT, INSTALLING_FIRST_ACCOUNT,
    ACKNOWLEDGING_ORIGINAL_INSTALLATION,
    CONNECTING_ORIGINAL_LOCAL_CLIENT,
    READING_EMPLOYEE_ENROLLMENT, RETAINING_EMPLOYEE_ENROLLMENT,
    REVIEWING_EMPLOYEE_ENROLLMENT, APPROVING_EMPLOYEE_ENROLLMENT,
    ENROLLING_EMPLOYEE_ACCOUNT, CONNECTING_EMPLOYEE_INTENT_JOURNAL,
    READING_EMPLOYEE_POLICY, PREPARING_EMPLOYEE_POLICY, APPROVING_EMPLOYEE_POLICY,
    ACTIVATING_EMPLOYEE_POLICY, LOOKING_UP_EMPLOYEE_POLICY,
    READING_DEVICE_ADMISSION, APPROVING_DEVICE_ADMISSION, ADMITTING_DEVICE, LOOKING_UP_DEVICE_ADMISSION,
    READING_FRESH_DEVICE_PAIRING, PREPARING_FRESH_DEVICE_TARGET, ACQUIRING_FRESH_DEVICE_SDK,
    PREPARING_FRESH_DEVICE_CHALLENGE, RESPONDING_FRESH_DEVICE_CHALLENGE, PROVING_FRESH_DEVICE_TARGET,
    IMPORTING_FRESH_DEVICE_DOCUMENT, EXPORTING_FRESH_DEVICE_DOCUMENT,
    RETIRING_UNSIGNED_DEVICE_PAIRING, CONTINUING_UNSIGNED_DEVICE_PAIRING,
    VERIFYING_APPROVED_PAIRING_MASTER,
    STARTING_LAN_HOST, CONNECTING_LAN_CLIENT, READING_LAN_ATTACHMENT, CLOSING_LAN_ATTACHMENT,
}

public enum class LocalSetupProblem {
    BUSY, INVALID_INPUT, AUTHENTICATION_REFUSED, THROTTLED, UNSUPPORTED_PLATFORM,
    INVALID_APPLICATION, USER_LOCKED, CAPACITY_EXHAUSTED, ORIGINAL_ACTION_REQUIRED,
    ISSUANCE_WITHDRAWN, STATE_UNCERTAIN, NATIVE_UNAVAILABLE, CLOSE_UNCONFIRMED,
    HEAD_ENROLLMENT_REQUIRED, ORIGINAL_TARGET_OWNER_REQUIRED, FULL_INSTALLATION_HELD,
    ORIGINAL_KEY_PREPARATION_REQUIRED,
    RUNTIME_PUBLICATION_HELD,
    CLIENT_JOURNAL_UNAVAILABLE,
    AUTOMATIC_REOPEN_STOPPED,
    AUTOMATIC_REOPEN_UNAVAILABLE,
}

/** Bounded display projection. Contains no original native action, keys, tokens or secret. */
public data class LocalSetupCompanyReview(
    public val reviewId: DomainId,
    public val companyName: String,
    public val installerName: String,
    public val installerLogin: String,
    public val headName: String,
    public val headLogin: String,
    public val rootFingerprint: String,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val headAuthorityId: DomainId,
    public val headEnrollmentId: DomainId,
    public val protection: SelfOwnedKeyProtectionV1,
    public val purposes: Set<LocalCompanyHeadPurposeV1>,
) {
    init {
        require(companyName.length in 1..160 && installerName.length in 1..160 && headName.length in 1..128)
        require(installerLogin.length in 3..64 && headLogin.length in 3..64)
        require(rootFingerprint.length == 64 && purposes == LocalCompanyHeadPurposeV1.entries.toSet())
    }
    override fun toString(): String = "LocalSetupCompanyReviewV1([REDACTED])"
}

public data class LocalSetupCompanyView(
    public val companyName: String,
    public val installerName: String,
    public val installerLogin: String,
    public val headName: String,
    public val headLogin: String,
    public val rootFingerprint: String,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val protection: SelfOwnedKeyProtectionV1,
    public val headAuthorityId: DomainId,
    public val headEnrollmentId: DomainId,
    /** Display of retained head state only, not an authenticated approval or runtime permit. */
    public val headEnabled: Boolean,
    public val enabledHeadPurposes: Set<LocalCompanyHeadPurposeV1>,
) {
    override fun toString(): String = "LocalSetupCompanyViewV1([REDACTED])"
}

public data class LocalSetupBranchReview(
    public val reviewId: DomainId,
    public val branchName: String,
    public val rootFingerprint: String,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val branchId: DomainId,
    public val allocationId: DomainId,
    public val installationId: DomainId,
    public val deviceId: DomainId,
    public val firstActorId: DomainId,
    public val protection: SelfOwnedKeyProtectionV1,
) {
    override fun toString(): String = "LocalSetupBranchReviewV1([REDACTED])"
}

public data class LocalSetupNativeView(
    public val branchName: String,
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val branchId: DomainId,
    public val allocationId: DomainId,
    public val installationId: DomainId,
    public val deviceId: DomainId,
    public val firstActorId: DomainId,
    public val targetFingerprint: String,
    public val nativeBindingDigest: String,
    public val protection: SelfOwnedKeyProtectionV1,
) {
    override fun toString(): String = "LocalSetupNativeViewV1([REDACTED])"
}

public data class LocalSetupReceiptView(
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val installationId: DomainId,
    public val branchId: DomainId,
    public val definitionDigest: String,
    public val receiptDigest: String,
    public val genesisDigest: String,
) {
    override fun toString(): String = "LocalSetupReceiptViewV1([REDACTED])"
}

/**
 * Complete existing security DATA for review, not a parallel policy schema or opaque native
 * proposal. The complete snapshot is produced by the actual definition codec/owner. No
 * caller-provided digest, summary-only review, editable approval flag or readiness Boolean.
 * A review ID does not approve this data. Submission must use the genuine original owner.
 */
public class LocalSetupDefinitionReview(
    public val reviewId: DomainId,
    public val snapshot: LocalBranchInstallationSnapshotV2,
    public val headLogin: String,
) {
    init { require(headLogin.length in 3..64) }
    override fun toString(): String = "LocalSetupDefinitionReviewV1([REDACTED])"
}

/**
 * Presentation progress only. Positive receipt/native material never changes into READY.
 * A locked projection omits all private company/branch/native/receipt/definition display data;
 * locking does not destroy the original native actions retained by the service.
 */
public data class LocalSetupView(
    public val phase: LocalSetupPhase = LocalSetupPhase.CHOOSE_ROUTE,
    public val work: LocalSetupWork? = null,
    public val locked: Boolean = false,
    public val companyReview: LocalSetupCompanyReview? = null,
    public val company: LocalSetupCompanyView? = null,
    public val branchReview: LocalSetupBranchReview? = null,
    public val native: LocalSetupNativeView? = null,
    public val receipt: LocalSetupReceiptView? = null,
    public val firstAccountHeadLogin: String? = null,
    public val definitionReview: LocalSetupDefinitionReview? = null,
    public val problem: LocalSetupProblem? = null,
    /** Bounded original factory diagnostic when actual cleanup is unobserved; never a permit. */
    public val nativeProblem: LocalSetupProblem? = null,
    /** Connection progress only, not platform qualification or full POS readiness. */
    public val localClientConnected: Boolean = false,
    public val localClientConnectionAvailable: Boolean = false,
    public val firstClientEnrollmentAvailable: Boolean = false,
    public val firstClientEnrollmentContinuationAvailable: Boolean = false,
    public val employeeEnrollments: LocalSetupEmployeeEnrollments? = null,
    public val employeeEnrollment: LocalSetupEmployeeEnrollmentView? = null,
    public val employeePolicies: LocalSetupEmployeePolicies? = null,
    public val employeePolicy: LocalSetupEmployeePolicyView? = null,
    public val deviceAdmissions: LocalSetupDeviceAdmissions? = null,
    public val deviceAdmission: LocalSetupDeviceAdmissionView? = null,
    public val freshDevicePairing: LocalSetupFreshDevicePairingView? = null,
    public val lan: LocalSetupLanView = LocalSetupLanView(),
) {
    override fun toString(): String = "LocalSetupViewV1([REDACTED])"
}

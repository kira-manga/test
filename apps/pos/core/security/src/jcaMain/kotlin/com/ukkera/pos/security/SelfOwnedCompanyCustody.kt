package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/*
 * Format-neutral company workflow custody. The four company workflows (employee enrollment,
 * employee policy activation, device pairing, device admission) keep ONE implementation of their
 * native/history logic and reach the company registry, the original root key and the named-head
 * ceremony only through [SelfOwnedCompanyCustody]:
 *
 * - [AndroidCompanyCustodyV1] delegates every call unchanged to the Android V1 registry, keys and
 *   installer authentication (same formats, files, AADs, ceremonies and signatures);
 * - [SelfOwnedDesktopCompanyCustodyV2] is the Windows/macOS custody over the V2 company container,
 *   the V2 ledgers ([SelfOwnedCompanyLedgersV2]) and [SelfOwnedDesktopCompanyHeadV2].
 *
 * The views below are DATA projections of the authenticated bootstrap/container; holding one
 * grants nothing. No V1 value is adapted into V2 custody or vice versa.
 */

internal class SelfOwnedCompanyKeyRef(val keyId: DomainId)

/** Founding facts of the retained company bootstrap (V1 or V2). */
internal class SelfOwnedCompanyFoundingView(
    val rootId: DomainId,
    val actionId: DomainId,
    val organizationId: DomainId,
    val companyId: DomainId,
    val facts: SelfOwnedCompanyFactsV1,
    private val keyIds: Map<SelfOwnedNativeKeyRoleV1, DomainId>,
) {
    fun key(role: SelfOwnedNativeKeyRoleV1): SelfOwnedCompanyKeyRef =
        SelfOwnedCompanyKeyRef(keyIds[role] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN))
    override fun toString(): String = "SelfOwnedCompanyFoundingView([REDACTED])"
}

internal class SelfOwnedCompanyBootstrapView(
    val original: SelfOwnedCompanyFoundingView,
    val headAuthorityId: DomainId,
    val headEnrollmentId: DomainId,
    val headName: String,
    val headLogin: String,
    private val ids: List<DomainId>,
) {
    fun allIds(): List<DomainId> = ids
    override fun toString(): String = "SelfOwnedCompanyBootstrapView([REDACTED])"

    companion object {
        fun of(value: LocalCompanyAuthorityBootstrapV1): SelfOwnedCompanyBootstrapView = SelfOwnedCompanyBootstrapView(
            SelfOwnedCompanyFoundingView(value.original.rootId, value.original.actionId, value.original.organizationId,
                value.original.companyId, value.original.facts, value.original.keys.associate { it.role to it.keyId }),
            value.headAuthorityId, value.headEnrollmentId, value.headName, value.headLogin, value.allIds())
        fun of(value: LocalCompanyAuthorityBootstrapV2): SelfOwnedCompanyBootstrapView = SelfOwnedCompanyBootstrapView(
            SelfOwnedCompanyFoundingView(value.original.rootId, value.original.actionId, value.original.organizationId,
                value.original.companyId, value.original.facts, value.original.keys.associate { it.role to it.keyId }),
            value.headAuthorityId, value.headEnrollmentId, value.headName, value.headLogin, value.allIds())
    }
}

internal class SelfOwnedCompanyRegistryView(
    val revision: Long,
    val root: SelfOwnedSignedDocumentV1,
    val installers: List<LocalProvisionerInstallerV1>,
)

/** Authenticated container projection; [source] is the exact V1 or V2 state it was read as. */
internal class SelfOwnedCompanyStateView private constructor(
    val head: LocalCompanyHeadAccountV1,
    val original: SelfOwnedCompanyRegistryView,
    internal val source: Any,
) {
    override fun toString(): String = "SelfOwnedCompanyStateView([REDACTED])"

    companion object {
        fun of(value: LocalCompanyAuthorityStateV1): SelfOwnedCompanyStateView = SelfOwnedCompanyStateView(value.head,
            SelfOwnedCompanyRegistryView(value.original.revision, value.original.root, value.original.installers), value)
        fun of(value: LocalCompanyAuthorityStateV2): SelfOwnedCompanyStateView = SelfOwnedCompanyStateView(value.head,
            SelfOwnedCompanyRegistryView(value.original.revision, value.original.root, value.original.installers), value)
    }
}

/** Invocation-local head approval of exactly one workflow action; never a session. */
internal sealed class SelfOwnedCompanyHeadApproval {
    abstract val accountRevision: Long
    abstract val credentialRevision: Long
    abstract val retainedRevision: Long
    abstract val boot: Int
    abstract val expires: Long
    override fun toString(): String = "SelfOwnedCompanyHeadApproval([REDACTED])"

    class EnrollmentV1(internal val value: AndroidLocalInstallerAuthentication.EmployeeEnrollmentHeadApproval) : SelfOwnedCompanyHeadApproval() {
        override val accountRevision get() = value.accountRevision
        override val credentialRevision get() = value.credentialRevision
        override val retainedRevision get() = value.retainedRevision
        override val boot get() = value.boot
        override val expires get() = value.expires
    }
    class PolicyV1(internal val value: AndroidLocalInstallerAuthentication.EmployeePolicyActivationHeadApproval) : SelfOwnedCompanyHeadApproval() {
        override val accountRevision get() = value.accountRevision
        override val credentialRevision get() = value.credentialRevision
        override val retainedRevision get() = value.retainedRevision
        override val boot get() = value.boot
        override val expires get() = value.expires
    }
    class PairingV1(internal val value: AndroidLocalInstallerAuthentication.DevicePairingHeadApproval) : SelfOwnedCompanyHeadApproval() {
        override val accountRevision get() = value.accountRevision
        override val credentialRevision get() = value.credentialRevision
        override val retainedRevision get() = value.retainedRevision
        override val boot get() = value.boot
        override val expires get() = value.expires
    }
    class DesktopV2(internal val value: SelfOwnedDesktopCompanyHeadV2.Approval) : SelfOwnedCompanyHeadApproval() {
        override val accountRevision get() = value.accountRevision
        override val credentialRevision get() = value.credentialRevision
        override val retainedRevision get() = value.retainedRevision
        override val boot get() = value.boot.ordinal
        override val expires get() = value.expires
    }
    class DesktopPairingV2(internal val value: SelfOwnedDesktopCompanyHeadV2.PairingApproval,
        internal val body: NativeDevicePairingBodyV1, internal val possession: SelfOwnedOctetsV1,
        internal val consent: NativeDevicePairingConsentV1) : SelfOwnedCompanyHeadApproval() {
        override val accountRevision get() = value.accountRevision
        override val credentialRevision get() = value.credentialRevision
        override val retainedRevision get() = value.retainedRevision
        override val boot get() = value.boot.ordinal
        override val expires get() = value.expires
    }
}

internal interface SelfOwnedCompanyCustody {
    val format: SelfOwnedOriginalFormat
    fun assertContinuity()
    /** Retained company bootstrap, or null (the caller refuses HEAD_ENROLLMENT_REQUIRED). */
    fun companyOriginal(): SelfOwnedCompanyBootstrapView?
    /** Authenticated current container, or null when absent. */
    fun companyState(): SelfOwnedCompanyStateView?
    /** Fingerprint of the actual original root key body. */
    fun rootFingerprint(): String
    /** Android business publications; null on desktop (no desktop business ledger exists). */
    fun readBusinessPublications(): NativeCompanyBusinessPublicationStateV1?

    fun readEmployeeEnrollments(): NativeEmployeeEnrollmentStateV1?
    fun retainEmployeeEnrollments(next: NativeEmployeeEnrollmentStateV1): NativeEmployeeEnrollmentStateV1
    fun readEmployeePolicyActivations(): NativeEmployeePolicyActivationStateV1?
    fun retainEmployeePolicyActivations(next: NativeEmployeePolicyActivationStateV1): NativeEmployeePolicyActivationStateV1
    fun readDevicePairings(): NativeDevicePairingStateV1?
    fun retainDevicePairings(next: NativeDevicePairingStateV1): NativeDevicePairingStateV1
    fun devicePairingReservedIdentityIds(): Set<DomainId>
    fun readDeviceAdmissions(): NativeDeviceAdmissionStateV1?
    fun retainDeviceAdmissions(next: NativeDeviceAdmissionStateV1): NativeDeviceAdmissionStateV1
    fun deviceAdmissionReservedIdentityIds(): Set<DomainId>

    fun authenticateEmployeeEnrollment(originalActionId: DomainId, actionDigest: PayloadDigest, login: String,
        secret: CharArray, actionDeadline: Long): SelfOwnedCompanyHeadApproval
    fun requireEmployeeEnrollmentStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        originalActionId: DomainId, actionDigest: PayloadDigest)
    fun signEmployeeEnrollmentEndorsement(record: NativeEmployeeEnrollmentRecordV1, approval: SelfOwnedCompanyHeadApproval): ByteArray
    fun verifyEmployeeEnrollment(record: NativeEmployeeEnrollmentRecordV1)

    fun authenticateEmployeePolicyActivation(originalActionId: DomainId, actionDigest: PayloadDigest, login: String,
        secret: CharArray, actionDeadline: Long): SelfOwnedCompanyHeadApproval
    fun requireEmployeePolicyActivationStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        originalActionId: DomainId, actionDigest: PayloadDigest)
    fun signEmployeePolicyActivationEndorsement(record: NativeEmployeePolicyActivationRecordV1,
        approval: SelfOwnedCompanyHeadApproval): ByteArray
    fun verifyEmployeePolicyActivation(record: NativeEmployeePolicyActivationRecordV1)

    fun authenticateDevicePairing(record: NativeDevicePairingRecordV1, login: String, secret: CharArray): SelfOwnedCompanyHeadApproval
    fun requireDevicePairingStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        record: NativeDevicePairingRecordV1)
    fun requireDevicePairingKnownUnsigned(record: NativeDevicePairingRecordV1): SelfOwnedCompanyStateView
    fun signDevicePairingEndorsement(record: NativeDevicePairingRecordV1, approval: SelfOwnedCompanyHeadApproval,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray
    fun verifyDevicePairing(record: NativeDevicePairingRecordV1)
    fun signUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray
    fun verifyUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1)
}

/** Exact Android V1 delegation: every call is the pre-existing registry/keys/authentication call. */
internal class AndroidCompanyCustodyV1(
    internal val registry: LocalProvisionerRegistryStore,
    internal val keys: LocalProvisionerNativeKeys,
    internal val authentication: AndroidLocalInstallerAuthentication,
) : SelfOwnedCompanyCustody {
    override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
    override fun assertContinuity() = registry.assertContinuity()
    override fun companyOriginal(): SelfOwnedCompanyBootstrapView? = registry.companyBootstrapRecord()?.let { SelfOwnedCompanyBootstrapView.of(it) }
    override fun companyState(): SelfOwnedCompanyStateView? = registry.readCompanyState(keys)?.let { SelfOwnedCompanyStateView.of(it) }
    override fun rootFingerprint(): String = AndroidP256Primitives.rootFingerprint(keys.rootBody())
    override fun readBusinessPublications(): NativeCompanyBusinessPublicationStateV1? = registry.readBusinessPublications(keys)

    override fun readEmployeeEnrollments() = registry.readEmployeeEnrollments(keys)
    override fun retainEmployeeEnrollments(next: NativeEmployeeEnrollmentStateV1) = registry.retainEmployeeEnrollments(next, keys)
    override fun readEmployeePolicyActivations() = registry.readEmployeePolicyActivations(keys)
    override fun retainEmployeePolicyActivations(next: NativeEmployeePolicyActivationStateV1) = registry.retainEmployeePolicyActivations(next, keys)
    override fun readDevicePairings() = registry.readDevicePairings(keys)
    override fun retainDevicePairings(next: NativeDevicePairingStateV1) = registry.retainDevicePairings(next, keys)
    override fun devicePairingReservedIdentityIds() = registry.devicePairingReservedIdentityIds(keys)
    override fun readDeviceAdmissions() = registry.readDeviceAdmissions(keys)
    override fun retainDeviceAdmissions(next: NativeDeviceAdmissionStateV1) = registry.retainDeviceAdmissions(next, keys)
    override fun deviceAdmissionReservedIdentityIds() = registry.deviceAdmissionReservedIdentityIds(keys)

    override fun authenticateEmployeeEnrollment(originalActionId: DomainId, actionDigest: PayloadDigest, login: String,
        secret: CharArray, actionDeadline: Long): SelfOwnedCompanyHeadApproval = SelfOwnedCompanyHeadApproval.EnrollmentV1(
        authentication.authenticateEmployeeEnrollmentAction(originalActionId, actionDigest, login, secret, actionDeadline))
    override fun requireEmployeeEnrollmentStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        originalActionId: DomainId, actionDigest: PayloadDigest) =
        authentication.requireEmployeeEnrollmentStillCurrent(enrollment(approval), v1(state), originalActionId, actionDigest)
    override fun signEmployeeEnrollmentEndorsement(record: NativeEmployeeEnrollmentRecordV1, approval: SelfOwnedCompanyHeadApproval) =
        keys.signEmployeeEnrollmentEndorsement(record, enrollment(approval))
    override fun verifyEmployeeEnrollment(record: NativeEmployeeEnrollmentRecordV1) = keys.verifyEmployeeEnrollment(record)

    override fun authenticateEmployeePolicyActivation(originalActionId: DomainId, actionDigest: PayloadDigest, login: String,
        secret: CharArray, actionDeadline: Long): SelfOwnedCompanyHeadApproval = SelfOwnedCompanyHeadApproval.PolicyV1(
        authentication.authenticateEmployeePolicyActivationAction(originalActionId, actionDigest, login, secret, actionDeadline))
    override fun requireEmployeePolicyActivationStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        originalActionId: DomainId, actionDigest: PayloadDigest) =
        authentication.requireEmployeePolicyActivationStillCurrent(policy(approval), v1(state), originalActionId, actionDigest)
    override fun signEmployeePolicyActivationEndorsement(record: NativeEmployeePolicyActivationRecordV1,
        approval: SelfOwnedCompanyHeadApproval) = keys.signEmployeePolicyActivationEndorsement(record, policy(approval))
    override fun verifyEmployeePolicyActivation(record: NativeEmployeePolicyActivationRecordV1) = keys.verifyEmployeePolicyActivation(record)

    override fun authenticateDevicePairing(record: NativeDevicePairingRecordV1, login: String, secret: CharArray): SelfOwnedCompanyHeadApproval =
        SelfOwnedCompanyHeadApproval.PairingV1(authentication.authenticateDevicePairingAction(record, login, secret))
    override fun requireDevicePairingStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        record: NativeDevicePairingRecordV1) = authentication.requireDevicePairingStillCurrent(pairing(approval), v1(state), record)
    override fun requireDevicePairingKnownUnsigned(record: NativeDevicePairingRecordV1): SelfOwnedCompanyStateView =
        SelfOwnedCompanyStateView.of(authentication.requireDevicePairingKnownUnsigned(record))
    override fun signDevicePairingEndorsement(record: NativeDevicePairingRecordV1, approval: SelfOwnedCompanyHeadApproval,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings) = keys.signDevicePairingEndorsement(record, pairing(approval), actualPairingsOwner)
    override fun verifyDevicePairing(record: NativeDevicePairingRecordV1) = keys.verifyDevicePairing(record)
    override fun signUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings) = keys.signUnsignedDevicePairingDisposition(record, actualPairingsOwner)
    override fun verifyUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1) = keys.verifyUnsignedDevicePairingDisposition(record)

    private fun v1(state: SelfOwnedCompanyStateView): LocalCompanyAuthorityStateV1 =
        state.source as? LocalCompanyAuthorityStateV1 ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    private fun enrollment(approval: SelfOwnedCompanyHeadApproval) =
        (approval as? SelfOwnedCompanyHeadApproval.EnrollmentV1)?.value ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    private fun policy(approval: SelfOwnedCompanyHeadApproval) =
        (approval as? SelfOwnedCompanyHeadApproval.PolicyV1)?.value ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    private fun pairing(approval: SelfOwnedCompanyHeadApproval) =
        (approval as? SelfOwnedCompanyHeadApproval.PairingV1)?.value ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
}

package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** An exact native-retained full-definition review; no reusable head approval or employee session. */
public class LocalEmployeeEnrollmentAction internal constructor(
    internal val owner: Any,
    public val originalActionId: DomainId,
    public val requested: LocalEmployeeEnrollmentDefinition,
    public val companyName: String,
    public val headName: String,
    public val headLogin: String,
    public val rootFingerprint: String,
) {
    override fun toString(): String = "LocalEmployeeEnrollmentAction([REDACTED])"
}

public enum class LocalEmployeeEnrollmentPhase {
    PREPARED, SIGNING_INTENT, APPROVED, DISPATCH_INTENT, CONSUMPTION_INTENT, CONSUMED, REJECTED,
}

public data class LocalEmployeeEnrollmentReference(
    public val originalActionId: DomainId,
    public val originalRequestId: DomainId,
    public val actorId: DomainId,
    public val phase: LocalEmployeeEnrollmentPhase,
)

/** Verified actual original-root signature, not a public data constructor or a general grant. */
public class LocalEmployeeEnrollmentAuthorityReceipt internal constructor(
    internal val record: NativeEmployeeEnrollmentRecordV1,
) {
    public val originalActionId: DomainId get() = record.originalActionId
    public val requested: LocalEmployeeEnrollmentDefinition get() = record.requested()
    public val rootFingerprint: String get() = record.target.rootFingerprint
    public val headAuthorityId: DomainId get() = record.target.headAuthorityId
    public val headEnrollmentId: DomainId get() = record.target.headEnrollmentId
    public val authorityDigest: PayloadDigest get() {
        val bytes = NativeEmployeeEnrollmentWireV1.authorityCertificate(record)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    public fun copyAuthorityCertificate(): ByteArray = NativeEmployeeEnrollmentWireV1.authorityCertificate(record)
    override fun toString(): String = "LocalEmployeeEnrollmentAuthorityReceipt([REDACTED])"
}

/** One actual service-owned original dispatch. Neither a DTO nor a scalar request ID can claim it. */
public class NativeLocalEmployeeEnrollmentDispatch internal constructor(
    internal val owner: Any,
    internal val identity: Any,
    internal val controller: NativeLocalEmployeeEnrollmentController,
    public val originalActionId: DomainId,
    public val request: LocalProvisioningRequest,
    internal val record: NativeEmployeeEnrollmentRecordV1,
) {
    /** Read-only and exact-object dispatch only. The actual Room administration has a private
     * employee path; its ordinary endpoint method never accepts a native effect permit. */
    public suspend fun authorizeOriginal(request: LocalProvisioningRequest): LocalProvisioningPermit? =
        controller.authorizeEffect(this, request)?.let(::LocalProvisioningPermit)
    override fun toString(): String = "NativeLocalEmployeeEnrollmentDispatch([REDACTED])"
}

/**
 * A final native-owned effect permit, constructed only by the original owner after explicit
 * durable dispatch admission. Its postcommit method is negative/read-only receipt eligibility;
 * it NEVER makes isCurrent true again or reauthorizes a consumed action.
 */
public class NativeLocalEmployeeProvisioningPermit internal constructor(
    internal val owner: Any,
    internal val dispatch: NativeLocalEmployeeEnrollmentDispatch,
    internal val controller: NativeLocalEmployeeEnrollmentController,
    public val approval: LocalEmployeeEnrollmentAuthorityReceipt,
) {
    public val request: LocalProvisioningRequest get() = dispatch.request
    public fun isCurrent(): Boolean = controller.effectPermitCurrent(this)
    public fun observedOwnCommitCurrent(): Boolean = controller.ownCommitCurrent(this)
    override fun toString(): String = "NativeLocalEmployeeProvisioningPermit([REDACTED])"
}

/** Consumed/rejected ORIGINAL-ID receipt access only. There is no conversion to an effect permit. */
public class LocalProvisioningReceiptPermit internal constructor(
    internal val owner: Any,
    internal val controller: NativeLocalEmployeeEnrollmentController,
    internal val originalActionId: DomainId,
    public val request: LocalProvisioningRequest,
    public val expectedEventDigest: PayloadDigest?,
    public val permitsAbsence: Boolean,
) {
    init { require(permitsAbsence == (expectedEventDigest == null)) }
    public fun isCurrent(): Boolean = controller.receiptPermitCurrent(this)
    override fun toString(): String = "LocalProvisioningReceiptPermit([REDACTED])"
}

public fun interface LocalProvisioningReceiptAuthority {
    /** Missing native final/terminal joins are unavailable, never authenticated absence. */
    public suspend fun lookupOriginal(request: LocalProvisioningRequest): LocalProvisioningReceiptPermit?
}

/** No public constructor, accepting callbacks, imported signature provider or caller key/path. */
public class NativeLocalEmployeeProvisioningAuthority internal constructor(
    private val controller: NativeLocalEmployeeEnrollmentController,
) : LocalProvisioningAuthority, LocalProvisioningReceiptAuthority {
    // Generic endpoint calls cannot borrow a dispatch by knowing its request ID/digest.
    // Only the final actual dispatch delivered to the private Room employee path can qualify.
    override public suspend fun authorize(request: LocalProvisioningRequest): LocalProvisioningPermit? = null
    override public suspend fun lookupOriginal(request: LocalProvisioningRequest): LocalProvisioningReceiptPermit? =
        controller.lookupReceipt(request)
    override fun toString(): String = "NativeLocalEmployeeProvisioningAuthority([REDACTED])"
}

public enum class NativeEmployeeClientJournalAction { ENROLL_EMPLOYEE, REOPEN_EXISTING, RECOVER_PENDING_WRITES }

/**
 * Real consumed enrollment + protected child/final witness binding. This owns no employee
 * credential/session and cannot appoint a master or pair another device. Each journal ID/key
 * was allocated and committed before head authentication, not guessed after missing files.
 */
public class NativeEmployeeClientCustody internal constructor(
    internal val owner: Any,
    internal val controller: NativeLocalEmployeeEnrollmentController,
    internal val record: NativeEmployeeEnrollmentRecordV1,
) {
    public val originalActionId: DomainId get() = record.originalActionId
    public val request: LocalProvisioningRequest get() = record.requested().request
    public val actorId: DomainId get() = request.targetId
    public val deviceId: DomainId get() = record.target.coordinates.deviceId
    public val scope: BranchScope get() = request.scope
    public val installationId: DomainId get() = record.target.coordinates.installationId
    public val clientAllocationId: DomainId get() = record.clientAllocationId
    public val intentJournalId: DomainId get() = record.intentJournalId
    public val intentJournalKeyId: DomainId get() = record.intentJournalKeyId
    public val enrollmentDefinitionDigest: PayloadDigest get() = record.requested().definitionDigest
    public val enrollmentChild: NativeHistoryRecordedHeadV2 get() = checkNotNull(record.effect).child
    /** Persistent immutable enrollment/authority/settled-effect DATA for the new journal AAD.
     * STARTED and this service incarnation are intentionally not a replaceable binding. */
    public fun copyOriginalJournalBinding(): ByteArray = NativeEmployeeEnrollmentWireV1.journalCustodyMessage(record)
    public fun isCurrent(): Boolean = controller.custodyCurrent(this)
    public suspend fun acquireJournalAction(action: NativeEmployeeClientJournalAction): NativeEmployeeJournalAllocation =
        controller.acquireJournalAction(this, action)
    override fun toString(): String = "NativeEmployeeClientCustody([REDACTED])"
}

/** STARTED is durably forced before ENROLL creates ANY client-native key or file. */
public class NativeEmployeeJournalAllocation internal constructor(
    internal val owner: Any,
    internal val custody: NativeEmployeeClientCustody,
    public val action: NativeEmployeeClientJournalAction,
    internal val controller: NativeLocalEmployeeEnrollmentController,
) {
    public val journalId: DomainId get() = custody.intentJournalId
    public val keyId: DomainId get() = custody.intentJournalKeyId
    public fun isCurrent(): Boolean = controller.journalAllocationCurrent(this)
    public fun belongsTo(custody: NativeEmployeeClientCustody): Boolean = this.custody === custody && isCurrent()
    /** One exact native-owned open attempt. Once attempted/unknown, never create again;
     * only a newly acquired REOPEN/RECOVER existing-only action may follow. */
    public suspend fun claimOriginalJournalOpen(): Unit = controller.claimJournalOpen(this)
    public fun originalOpenClaimed(): Boolean = controller.journalOpenClaimed(this)
    override fun toString(): String = "NativeEmployeeJournalAllocation([REDACTED])"
}

/** Actual original native owner, same worker/exclusion/root. Generic contexts remain unavailable. */
public class LocalCompanyEmployeeEnrollments internal constructor(
    private val controller: NativeLocalEmployeeEnrollmentController,
    public val provisioning: NativeLocalEmployeeProvisioningAuthority,
) {
    public suspend fun prepare(requested: LocalEmployeeEnrollmentDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> = controller.prepareEmployee(requested, original)
    /** Head buffer transfers on every outcome; this is NOT employee password verification. */
    public suspend fun approve(action: LocalEmployeeEnrollmentAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt> = controller.approveEmployee(action, headLogin, headSecret)
    public suspend fun review(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
        controller.reviewEmployee(originalActionId)
    /** Read-only exact original APPROVED output; uncertain signing never permits another signature. */
    public suspend fun recoverApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt> =
        controller.recoverEmployeeApproval(originalActionId)
    /** Read-only exact late-phase signed connection DATA. This never renews APPROVED
     * authority or converts a consumed/rejected/uncertain original into an effect permit. */
    public suspend fun recoverConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt> =
        controller.recoverEmployeeConnection(originalActionId)
    public suspend fun retained(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> = controller.retainedEmployees()
    public suspend fun originalDefinition(requestId: DomainId, login: LocalLoginIdentifier): LocalEmployeeEnrollmentDefinition? =
        controller.originalDefinition(requestId, login)
    public suspend fun beginAccountProvisioning(request: LocalProvisioningRequest): NativeLocalEmployeeEnrollmentDispatch =
        controller.beginDispatch(request)
    /** Negative-only terminalization of this exact UNUSED dispatch. The native owner
     * independently requires no active/bound frame, no child and the unchanged original cut.
     * It withdraws any later effect permit and releases no endpoint/OS resource. Production
     * invokes this only after its exact endpoint worker's actual cleanup has returned. */
    public suspend fun finishKnownNoEffect(dispatch: NativeLocalEmployeeEnrollmentDispatch): Boolean = controller.finishKnownNoEffect(dispatch)
    public suspend fun acquireConsumedEmployee(requestId: DomainId): NativeEmployeeClientCustody = controller.acquireConsumedEmployee(requestId)
    override fun toString(): String = "LocalCompanyEmployeeEnrollments([REDACTED])"
}

internal interface SelfOwnedEmployeeEnrollmentsOwner {
    suspend fun readOriginalEmployeeEnrollments(): LocalCompanyAuthorityResult<LocalCompanyEmployeeEnrollments>
}

public suspend fun SelfOwnedNativeHistoryCustody.readOriginalEmployeeEnrollments(): LocalCompanyAuthorityResult<LocalCompanyEmployeeEnrollments> =
    (controller as? SelfOwnedEmployeeEnrollmentsOwner)?.readOriginalEmployeeEnrollments()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

internal interface NativeLocalEmployeeEnrollmentController {
    suspend fun prepareEmployee(requested: LocalEmployeeEnrollmentDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction>
    suspend fun approveEmployee(action: LocalEmployeeEnrollmentAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt>
    suspend fun reviewEmployee(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction>
    suspend fun recoverEmployeeApproval(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt>
    suspend fun recoverEmployeeConnection(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt>
    suspend fun retainedEmployees(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>>
    suspend fun originalDefinition(requestId: DomainId, login: LocalLoginIdentifier): LocalEmployeeEnrollmentDefinition?
    suspend fun beginDispatch(request: LocalProvisioningRequest): NativeLocalEmployeeEnrollmentDispatch
    suspend fun authorizeEffect(dispatch: NativeLocalEmployeeEnrollmentDispatch, request: LocalProvisioningRequest): NativeLocalEmployeeProvisioningPermit?
    suspend fun lookupReceipt(request: LocalProvisioningRequest): LocalProvisioningReceiptPermit?
    fun effectPermitCurrent(permit: NativeLocalEmployeeProvisioningPermit): Boolean
    fun ownCommitCurrent(permit: NativeLocalEmployeeProvisioningPermit): Boolean
    fun receiptPermitCurrent(permit: LocalProvisioningReceiptPermit): Boolean
    suspend fun finishKnownNoEffect(dispatch: NativeLocalEmployeeEnrollmentDispatch): Boolean
    suspend fun acquireConsumedEmployee(requestId: DomainId): NativeEmployeeClientCustody
    fun custodyCurrent(custody: NativeEmployeeClientCustody): Boolean
    suspend fun acquireJournalAction(custody: NativeEmployeeClientCustody, action: NativeEmployeeClientJournalAction): NativeEmployeeJournalAllocation
    fun journalAllocationCurrent(allocation: NativeEmployeeJournalAllocation): Boolean
    suspend fun claimJournalOpen(allocation: NativeEmployeeJournalAllocation)
    fun journalOpenClaimed(allocation: NativeEmployeeJournalAllocation): Boolean
}

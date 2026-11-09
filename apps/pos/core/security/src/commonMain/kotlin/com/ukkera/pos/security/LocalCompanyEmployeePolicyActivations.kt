package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Exact retained complete policy review. It grants no employee session or reusable head approval. */
public class LocalEmployeePolicyActivationAction internal constructor(
    internal val owner: Any,
    public val originalActionId: DomainId,
    public val requested: LocalEmployeePolicyActivationDefinition,
    public val companyName: String,
    public val headName: String,
    public val headLogin: String,
    public val rootFingerprint: String,
) {
    override fun toString(): String = "LocalEmployeePolicyActivationAction([REDACTED])"
}

public enum class LocalEmployeePolicyActivationPhase {
    PREPARED, SIGNING_INTENT, APPROVED, DISPATCH_INTENT, CONSUMPTION_INTENT, CONSUMED, REJECTED,
}

public data class LocalEmployeePolicyActivationReference(
    public val originalActionId: DomainId,
    public val originalRequestId: DomainId,
    public val actorId: DomainId,
    public val phase: LocalEmployeePolicyActivationPhase,
)

/** Verified original-root signature for this complete employee-policy action only. */
public class LocalEmployeePolicyActivationAuthorityReceipt internal constructor(
    internal val record: NativeEmployeePolicyActivationRecordV1,
) {
    public val originalActionId: DomainId get() = record.originalActionId
    public val requested: LocalEmployeePolicyActivationDefinition get() = record.requested()
    public val rootFingerprint: String get() = record.target.rootFingerprint
    public val headAuthorityId: DomainId get() = record.target.headAuthorityId
    public val headEnrollmentId: DomainId get() = record.target.headEnrollmentId
    public val authorityDigest: PayloadDigest get() {
        val bytes = NativeEmployeePolicyActivationWireV1.authorityCertificate(record)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    public fun copyAuthorityCertificate(): ByteArray = NativeEmployeePolicyActivationWireV1.authorityCertificate(record)
    override fun toString(): String = "LocalEmployeePolicyActivationAuthorityReceipt([REDACTED])"
}

/** One native-owned original dispatch; a request ID/digest cannot borrow this object. */
public class NativeLocalEmployeePolicyActivationDispatch internal constructor(
    internal val owner: Any,
    internal val identity: Any,
    internal val controller: NativeLocalEmployeePolicyActivationController,
    public val originalActionId: DomainId,
    public val request: LocalProvisioningRequest,
    internal val record: NativeEmployeePolicyActivationRecordV1,
) {
    public suspend fun authorizeOriginal(request: LocalProvisioningRequest): LocalProvisioningPermit? =
        controller.authorizePolicyEffect(this, request)?.let(::LocalProvisioningPermit)
    override fun toString(): String = "NativeLocalEmployeePolicyActivationDispatch([REDACTED])"
}

/** Constructed by the genuine native owner after forced original dispatch, never from DATA. */
public class NativeLocalEmployeePolicyActivationPermit internal constructor(
    internal val owner: Any,
    internal val dispatch: NativeLocalEmployeePolicyActivationDispatch,
    internal val controller: NativeLocalEmployeePolicyActivationController,
    public val approval: LocalEmployeePolicyActivationAuthorityReceipt,
) {
    public val request: LocalProvisioningRequest get() = dispatch.request
    public fun isCurrent(): Boolean = controller.policyEffectPermitCurrent(this)
    /** Negative/read-only own-commit disclosure only; consumption never renews an effect grant. */
    public fun observedOwnCommitCurrent(): Boolean = controller.policyOwnCommitCurrent(this)
    override fun toString(): String = "NativeLocalEmployeePolicyActivationPermit([REDACTED])"
}

/** Terminal original-ID receipt access only. Missing final joins never authorize absence. */
public class LocalEmployeePolicyActivationReceiptPermit internal constructor(
    internal val owner: Any,
    internal val controller: NativeLocalEmployeePolicyActivationController,
    internal val originalActionId: DomainId,
    public val request: LocalProvisioningRequest,
    public val expectedEventDigest: PayloadDigest?,
    public val permitsAbsence: Boolean,
) {
    init { require(permitsAbsence == (expectedEventDigest == null)) }
    public fun isCurrent(): Boolean = controller.policyReceiptPermitCurrent(this)
    override fun toString(): String = "LocalEmployeePolicyActivationReceiptPermit([REDACTED])"
}

/** This native route has no generic accepting callback or request-ID-to-dispatch conversion. */
public class NativeLocalEmployeePolicyActivationAuthority internal constructor(
    private val controller: NativeLocalEmployeePolicyActivationController,
) : LocalProvisioningAuthority {
    override public suspend fun authorize(request: LocalProvisioningRequest): LocalProvisioningPermit? = null
    public suspend fun lookupOriginal(request: LocalProvisioningRequest): LocalEmployeePolicyActivationReceiptPermit? =
        controller.lookupPolicyReceipt(request)
    override fun toString(): String = "NativeLocalEmployeePolicyActivationAuthority([REDACTED])"
}

/** Existing original company root/head/native worker. No central or branch-employee substitute. */
public class LocalCompanyEmployeePolicyActivations internal constructor(
    private val controller: NativeLocalEmployeePolicyActivationController,
    public val provisioning: NativeLocalEmployeePolicyActivationAuthority,
) {
    public suspend fun prepare(requested: LocalEmployeePolicyActivationDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = controller.preparePolicy(requested, original)
    /** Head buffer transfers on every outcome; this does not authenticate a branch employee. */
    public suspend fun approve(action: LocalEmployeePolicyActivationAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = controller.approvePolicy(action, headLogin, headSecret)
    public suspend fun review(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
        controller.reviewPolicy(originalActionId)
    public suspend fun recoverApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        controller.recoverPolicyApproval(originalActionId)
    /** Exact signed late-phase DATA only; no re-signing, dispatch, renewed deadline or effect permit. */
    public suspend fun recoverConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        controller.recoverPolicyConnection(originalActionId)
    public suspend fun retained(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> = controller.retainedPolicies()
    public suspend fun beginPolicyActivation(request: LocalProvisioningRequest): NativeLocalEmployeePolicyActivationDispatch =
        controller.beginPolicyDispatch(request)
    /** Native independently proves no active/bound frame, no child and unchanged original cut.
     * Only actual endpoint-worker cleanup can precede this negative terminalization. */
    public suspend fun finishKnownNoEffect(dispatch: NativeLocalEmployeePolicyActivationDispatch): Boolean =
        controller.finishPolicyKnownNoEffect(dispatch)
    override fun toString(): String = "LocalCompanyEmployeePolicyActivations([REDACTED])"
}

internal interface SelfOwnedEmployeePolicyActivationsOwner {
    suspend fun readOriginalEmployeePolicyActivations(): LocalCompanyAuthorityResult<LocalCompanyEmployeePolicyActivations>
}

public suspend fun SelfOwnedNativeHistoryCustody.readOriginalEmployeePolicyActivations():
    LocalCompanyAuthorityResult<LocalCompanyEmployeePolicyActivations> =
    (controller as? SelfOwnedEmployeePolicyActivationsOwner)?.readOriginalEmployeePolicyActivations()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

internal interface NativeLocalEmployeePolicyActivationController {
    suspend fun preparePolicy(requested: LocalEmployeePolicyActivationDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction>
    suspend fun approvePolicy(action: LocalEmployeePolicyActivationAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt>
    suspend fun reviewPolicy(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction>
    suspend fun recoverPolicyApproval(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt>
    suspend fun recoverPolicyConnection(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt>
    suspend fun retainedPolicies(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>>
    suspend fun beginPolicyDispatch(request: LocalProvisioningRequest): NativeLocalEmployeePolicyActivationDispatch
    suspend fun authorizePolicyEffect(dispatch: NativeLocalEmployeePolicyActivationDispatch, request: LocalProvisioningRequest):
        NativeLocalEmployeePolicyActivationPermit?
    suspend fun lookupPolicyReceipt(request: LocalProvisioningRequest): LocalEmployeePolicyActivationReceiptPermit?
    fun policyEffectPermitCurrent(permit: NativeLocalEmployeePolicyActivationPermit): Boolean
    fun policyOwnCommitCurrent(permit: NativeLocalEmployeePolicyActivationPermit): Boolean
    fun policyReceiptPermitCurrent(permit: LocalEmployeePolicyActivationReceiptPermit): Boolean
    suspend fun finishPolicyKnownNoEffect(dispatch: NativeLocalEmployeePolicyActivationDispatch): Boolean
}

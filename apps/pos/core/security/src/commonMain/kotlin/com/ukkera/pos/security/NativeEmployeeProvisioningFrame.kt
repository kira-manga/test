package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Exact original native frame/effect permit binding. Parsed records, scalar frame IDs and
 * callbacks cannot construct this object or claim actual settlement/final observation. */
public class NativeEmployeeProvisioningFrame internal constructor(
    internal val owner: Any,
    internal val controller: SelfOwnedEmployeeProvisioningOwner,
    internal val native: SelfOwnedNativeRoomFrame,
    internal val permit: NativeLocalEmployeeProvisioningPermit,
    public val bindingObservation: LocalAuthenticationObservation,
) {
    public val frameId: DomainId get() = native.frameId
    public val requested: LocalEmployeeEnrollmentDefinition = permit.approval.requested
    internal val tokenWorkLifetimeMillis: Long = requested.definition.tokenPolicy.workLifetimeMillis
    public val authorityDigest: PayloadDigest get() = permit.approval.authorityDigest
    public val nativeBindingDigest: PayloadDigest = NativeEmployeeEnrollmentWireV1.bindingMessage(this).let { bytes ->
        try { sha256(bytes) } finally { bytes.fill(0) }
    }
    /** Called by the actual closed Room producer only AFTER generic native child preparation.
     * Native independently joins the registered frame, its actual prepared child and intent. */
    public suspend fun retainOriginalPreparedEffect(intent: NativeHistoryOriginalIntentV2,
        evidence: LocalEmployeeEnrollmentEffectEvidence): Unit = controller.retainEmployeePreparedEffect(this, intent, evidence)
    override fun toString(): String = "NativeEmployeeProvisioningFrame([REDACTED])"
}

internal interface SelfOwnedEmployeeProvisioningOwner {
    suspend fun bindEmployeeProvisioningFrame(native: SelfOwnedNativeRoomFrame,
        permit: NativeLocalEmployeeProvisioningPermit): NativeEmployeeProvisioningFrame
    suspend fun retainEmployeePreparedEffect(frame: NativeEmployeeProvisioningFrame,
        intent: NativeHistoryOriginalIntentV2, evidence: LocalEmployeeEnrollmentEffectEvidence)
}

/** Additive actual-owner seam. Every generic/platform controller remains unavailable. */
public suspend fun SelfOwnedNativeHistoryCustody.bindOriginalEmployeeProvisioningFrame(
    native: SelfOwnedNativeRoomFrame, permit: NativeLocalEmployeeProvisioningPermit,
): NativeEmployeeProvisioningFrame = (controller as? SelfOwnedEmployeeProvisioningOwner)
    ?.bindEmployeeProvisioningFrame(native, permit)
    ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)

/** Canonical DATA commitment to the actual Room producer's complete absence/account census.
 * No Boolean absence claims, imported current callbacks or scalar parent grants are accepted. */
public fun localEmployeeEnrollmentAbsenceDigestV1(request: LocalProvisioningRequest, login: LocalLoginIdentifier,
    accountIds: Set<DomainId>, retainedDeviceIds: Set<DomainId>): PayloadDigest {
    require(request.action == LocalProvisioningAction.PROVISION_ACCOUNT && accountIds.size < 4096 &&
        request.targetId !in accountIds && retainedDeviceIds.size in 1..256)
    val bytes = selfOwnedEncode("actual-room-local-employee-absence-dependencies/v1", 256 * 1024) {
        id(request.scope.organizationId); id(request.scope.companyId); id(request.scope.branchId)
        id(request.requestId); id(request.targetId); string(request.contentDigest.value); string(login.value)
        string("ABSENT_ACCOUNT_POLICY_CONFIGURATION_LOGIN_EVENT_ALL_NEW_ACTOR_THROTTLES")
        int(accountIds.size); accountIds.sortedBy { it.value }.forEach(::id)
        int(retainedDeviceIds.size); retainedDeviceIds.sortedBy { it.value }.forEach(::id)
    }
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

/** The original pure dependency bytes used by BOTH the actual producer and native intent join. */
public fun localEmployeeEnrollmentDependenciesV1(evidence: LocalEmployeeEnrollmentEffectEvidence): ByteArray =
    selfOwnedEncode("actual-room-local-employee-provisioning-dependencies/v1", 32 * 1024) {
        id(evidence.request.scope.organizationId); id(evidence.request.scope.companyId); id(evidence.request.scope.branchId)
        id(evidence.request.requestId); id(evidence.request.targetId); string(evidence.request.contentDigest.value)
        string(evidence.definitionDigest.value); string(evidence.nativeBindingDigest.value); string(evidence.absenceDependenciesDigest.value)
        int(evidence.admissionDigests.size)
        evidence.admissionDigests.forEach { (deviceId, commitment) -> id(deviceId); string(commitment.value) }
    }

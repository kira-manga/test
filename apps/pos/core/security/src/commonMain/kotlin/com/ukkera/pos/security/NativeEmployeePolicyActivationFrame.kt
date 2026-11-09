package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Actual original native writer frame and exact typed policy permit. No public constructor. */
public class NativeEmployeePolicyActivationFrame internal constructor(
    internal val owner: Any,
    internal val controller: SelfOwnedEmployeePolicyActivationFrameOwner,
    internal val native: SelfOwnedNativeRoomFrame,
    internal val permit: NativeLocalEmployeePolicyActivationPermit,
    public val bindingObservation: LocalAuthenticationObservation,
) {
    public val frameId: DomainId get() = native.frameId
    public val requested: LocalEmployeePolicyActivationDefinition = permit.approval.requested
    public val authorityDigest: PayloadDigest get() = permit.approval.authorityDigest
    public val nativeBindingDigest: PayloadDigest = NativeEmployeePolicyActivationWireV1.bindingMessage(this).let { bytes ->
        try { sha256(bytes) } finally { bytes.fill(0) }
    }
    /** The actual closed Room producer calls this after the genuine native child preparation. */
    public suspend fun retainOriginalPreparedEffect(intent: NativeHistoryOriginalIntentV2,
        evidence: LocalEmployeePolicyActivationEffectEvidence): Unit = controller.retainPolicyPreparedEffect(this, intent, evidence)
    override fun toString(): String = "NativeEmployeePolicyActivationFrame([REDACTED])"
}

internal interface SelfOwnedEmployeePolicyActivationFrameOwner {
    suspend fun bindEmployeePolicyActivationFrame(native: SelfOwnedNativeRoomFrame,
        permit: NativeLocalEmployeePolicyActivationPermit): NativeEmployeePolicyActivationFrame
    suspend fun retainPolicyPreparedEffect(frame: NativeEmployeePolicyActivationFrame,
        intent: NativeHistoryOriginalIntentV2, evidence: LocalEmployeePolicyActivationEffectEvidence)
}

public suspend fun SelfOwnedNativeHistoryCustody.bindOriginalEmployeePolicyActivationFrame(
    native: SelfOwnedNativeRoomFrame, permit: NativeLocalEmployeePolicyActivationPermit,
): NativeEmployeePolicyActivationFrame = (controller as? SelfOwnedEmployeePolicyActivationFrameOwner)
    ?.bindEmployeePolicyActivationFrame(native, permit)
    ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)

/** Pure complete original dependencies; producer and native intent join use the same bytes. */
public fun localEmployeePolicyActivationDependenciesV1(evidence: LocalEmployeePolicyActivationEffectEvidence): ByteArray =
    selfOwnedEncode("actual-room-local-employee-policy-dependencies/v1", 32 * 1024) {
        id(evidence.request.scope.organizationId); id(evidence.request.scope.companyId); id(evidence.request.scope.branchId)
        id(evidence.request.requestId); id(evidence.request.targetId); string(evidence.request.contentDigest.value)
        string(evidence.definitionDigest.value); string(evidence.authorityDigest.value); string(evidence.nativeBindingDigest.value)
        string(evidence.beforePolicyDigest.value); string(evidence.beforeAccountDigest.value)
    }

package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** A single original native dispatch. Approval DATA and request IDs cannot construct it. */
public class NativeLocalDeviceAdmissionDispatch internal constructor(
    internal val owner: Any,
    internal val identity: Any,
    internal val controller: NativeLocalDeviceAdmissionController,
    internal val record: NativeDeviceAdmissionRecordV1,
) {
    public val originalActionId: DomainId get() = record.pairing.body.originalActionId
    public val requested: LocalDevicePairingDefinition get() = record.pairing.body.requested()
    public suspend fun authorizeOriginal(request: LocalProvisioningRequest): LocalProvisioningPermit? =
        controller.authorizeDeviceAdmission(this, request)?.let(::LocalProvisioningPermit)
    override fun toString(): String = "NativeLocalDeviceAdmissionDispatch([REDACTED])"
}

/** Actual-owner eligibility only; own-commit disclosure never restores effect eligibility. */
public class NativeLocalDeviceAdmissionPermit internal constructor(
    internal val owner: Any,
    internal val controller: NativeLocalDeviceAdmissionController,
    internal val dispatch: NativeLocalDeviceAdmissionDispatch,
) {
    public val requested: LocalDevicePairingDefinition get() = dispatch.requested
    public val request: LocalProvisioningRequest get() = requested.request
    public val authorityDigest: PayloadDigest get() = LocalDevicePairingApproval(dispatch.record.pairing).authorityDigest
    public fun isCurrent(): Boolean = controller.deviceAdmissionPermitCurrent(this)
    public fun observedOwnCommitCurrent(): Boolean = controller.deviceAdmissionOwnCommitCurrent(this)
    override fun toString(): String = "NativeLocalDeviceAdmissionPermit([REDACTED])"
}

/** Terminal original receipt eligibility, never a device, session or effect permit. */
public class LocalDeviceAdmissionReceiptPermit internal constructor(
    internal val owner: Any,
    internal val controller: NativeLocalDeviceAdmissionController,
    internal val record: NativeDeviceAdmissionRecordV1,
) {
    public val requested: LocalDevicePairingDefinition get() = record.pairing.body.requested()
    public val request: LocalProvisioningRequest get() = requested.request
    public val expectedEventDigest: PayloadDigest? get() = record.evidence()?.images?.last()?.plaintextDigest
    public val permitsAbsence: Boolean get() = record.phase == NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT
    init {
        require(record.phase in setOf(NativeDeviceAdmissionPhaseV1.CONSUMED, NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT) &&
            permitsAbsence == (expectedEventDigest == null))
    }
    public fun isCurrent(): Boolean = controller.deviceAdmissionReceiptCurrent(this)
    override fun toString(): String = "LocalDeviceAdmissionReceiptPermit([REDACTED])"
}

public class NativeDeviceAdmissionFrame internal constructor(
    internal val owner: Any,
    internal val controller: SelfOwnedDeviceAdmissionFrameOwner,
    internal val native: SelfOwnedNativeRoomFrame,
    internal val permit: NativeLocalDeviceAdmissionPermit,
    public val bindingObservation: LocalAuthenticationObservation,
) {
    public val frameId: DomainId get() = native.frameId
    public val requested: LocalDevicePairingDefinition get() = permit.requested
    public val authorityDigest: PayloadDigest get() = permit.authorityDigest
    public val nativeBindingDigest: PayloadDigest = NativeDeviceAdmissionWireV1.binding(this).let {
        try { sha256(it) } finally { it.fill(0) }
    }
    public suspend fun retainOriginalPreparedEffect(intent: NativeHistoryOriginalIntentV2,
        evidence: LocalDeviceAdmissionEffectEvidence): Unit = controller.retainDeviceAdmissionEffect(this, intent, evidence)
    override fun toString(): String = "NativeDeviceAdmissionFrame([REDACTED])"
}

internal interface NativeLocalDeviceAdmissionController {
    suspend fun beginDeviceAdmission(id: DomainId): NativeLocalDeviceAdmissionDispatch
    suspend fun authorizeDeviceAdmission(dispatch: NativeLocalDeviceAdmissionDispatch,
        request: LocalProvisioningRequest): NativeLocalDeviceAdmissionPermit?
    suspend fun finishUnusedDeviceAdmission(dispatch: NativeLocalDeviceAdmissionDispatch): Boolean
    suspend fun lookupOriginalDeviceAdmission(id: DomainId): LocalDeviceAdmissionReceiptPermit?
    fun deviceAdmissionPermitCurrent(permit: NativeLocalDeviceAdmissionPermit): Boolean
    fun deviceAdmissionOwnCommitCurrent(permit: NativeLocalDeviceAdmissionPermit): Boolean
    fun deviceAdmissionReceiptCurrent(permit: LocalDeviceAdmissionReceiptPermit): Boolean
}

internal interface SelfOwnedDeviceAdmissionFrameOwner {
    suspend fun bindDeviceAdmissionFrame(native: SelfOwnedNativeRoomFrame,
        permit: NativeLocalDeviceAdmissionPermit): NativeDeviceAdmissionFrame
    suspend fun retainDeviceAdmissionEffect(frame: NativeDeviceAdmissionFrame,
        intent: NativeHistoryOriginalIntentV2, evidence: LocalDeviceAdmissionEffectEvidence)
}

public suspend fun SelfOwnedNativeHistoryCustody.bindOriginalDeviceAdmissionFrame(native: SelfOwnedNativeRoomFrame,
    permit: NativeLocalDeviceAdmissionPermit): NativeDeviceAdmissionFrame =
    (controller as? SelfOwnedDeviceAdmissionFrameOwner)?.bindDeviceAdmissionFrame(native, permit)
        ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)

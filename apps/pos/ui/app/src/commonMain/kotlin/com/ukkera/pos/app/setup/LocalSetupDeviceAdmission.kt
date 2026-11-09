package com.ukkera.pos.app.setup

import com.ukkera.pos.security.LocalDevicePairingDefinition
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.types.DomainId

/** Presentation progress only. An approved original remains DATA, not a native effect permit. */
public enum class LocalSetupDeviceAdmissionStage {
    ORIGINAL_UNAVAILABLE, TARGET_NOT_READY, HEAD_REVIEW, APPROVAL_UNRESOLVED, APPROVED,
    ADMISSION_UNRESOLVED, APPLIED, KNOWN_NO_EFFECT,
}
public enum class LocalSetupDeviceAdmissionOutcome { APPLIED, KNOWN_NO_EFFECT, DENIED, UNAVAILABLE, STORAGE_UNCERTAIN, BUSY }

/** Full non-secret review DATA; native action/approval objects and passwords are never published. */
public data class LocalSetupDeviceAdmissionView(
    public val reviewId: DomainId,
    public val originalActionId: DomainId,
    public val originalRequestId: DomainId,
    public val deviceId: DomainId,
    public val keyId: DomainId,
    public val stage: LocalSetupDeviceAdmissionStage,
    public val nativePhase: LocalDevicePairingPhase? = null,
    public val requested: LocalDevicePairingDefinition? = null,
    public val companyName: String? = null,
    public val headName: String? = null,
    public val headLogin: String? = null,
    public val rootFingerprint: String? = null,
    public val challengeDigest: String? = null,
    public val authorityDigest: String? = null,
    public val headAttempted: Boolean = false,
    public val admissionAttempted: Boolean = false,
    public val outcome: LocalSetupDeviceAdmissionOutcome? = null,
    public val eventId: DomainId? = null,
) {
    init {
        requested?.let { require(it.request.requestId == originalRequestId && it.admission.deviceId == deviceId && it.target.keyId == keyId) }
        require((outcome == LocalSetupDeviceAdmissionOutcome.APPLIED) == (eventId != null))
        eventId?.let { require(it == originalRequestId) }
    }
    override fun toString(): String = "LocalSetupDeviceAdmissionView([REDACTED])"
}

/** Actual native original references, not an admitted-device directory or a transport grant. */
public data class LocalSetupDeviceAdmissions(
    public val originals: List<LocalDevicePairingReference> = emptyList(),
    public val unresolvedActionId: DomainId? = null,
) {
    override fun toString(): String = "LocalSetupDeviceAdmissions([REDACTED])"
}

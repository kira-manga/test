package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionOutcome
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionStage
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionView
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingDefinition
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.types.DomainId

/** Same original-owner workflow memory only. Durable attempt state is in the separate journal. */
internal class LocalSetupDeviceAdmissionOriginal private constructor(
    val actionId: DomainId, val requestId: DomainId, val deviceId: DomainId, val keyId: DomainId,
) {
    constructor(reference: LocalDevicePairingReference) : this(reference.originalActionId,
        reference.originalRequestId, reference.deviceId, reference.keyId) { retainReference(reference) }
    /** Journal identity only when native review is missing; it cannot manufacture an action. */
    constructor(record: LocalDeviceAdmissionIntents.Record) : this(record.identity.actionId,
        record.identity.requestId, record.identity.deviceId, record.identity.keyId) { retainRecord(record) }

    var record: LocalDeviceAdmissionIntents.Record? = null
        private set
    var action: LocalDevicePairingAction? = null
        private set
    var approval: LocalDevicePairingApproval? = null
        private set
    var requested: LocalDevicePairingDefinition? = null
        private set
    var phase: LocalDevicePairingPhase? = null
    // Conservative same-lifetime denial if a journal write itself has an unknown result.
    // No native call is allowed until the corresponding forced marker has returned.
    var headAttemptEntered: Boolean = false
    var admissionAttemptEntered: Boolean = false
    // Persisted labels are not authoritative after reopening. Only this owner's
    // exact native outcome lookup can clear the original's recovery barrier.
    var confirmedTerminal: LocalDeviceAdmissionIntents.Terminal? = null
    val unresolvedAttempt: Boolean get() =
        record?.terminal?.let { it != confirmedTerminal } == true ||
            record?.terminal == null && (admissionAttemptEntered || headAttemptEntered && record?.authorityDigest == null)

    fun retainReference(reference: LocalDevicePairingReference) {
        require(reference.originalActionId == actionId && reference.originalRequestId == requestId &&
            reference.deviceId == deviceId && reference.keyId == keyId)
        phase = reference.phase
    }
    fun retainRecord(record: LocalDeviceAdmissionIntents.Record) {
        val identity = record.identity
        require(identity.actionId == actionId && identity.requestId == requestId && identity.deviceId == deviceId && identity.keyId == keyId)
        this.record?.let { require(it.identity == identity) }
        action?.let { require(identity.definitionDigest == it.requested.definitionDigest.value &&
            identity.requestDigest == it.requested.request.contentDigest.value && identity.challengeDigest == it.challenge.challengeDigest.value &&
            identity.sdkDigest == it.requested.sdkIdentity?.descriptorDigest?.value && identity.rootFingerprint == it.challenge.rootFingerprint) }
        this.record = record
        headAttemptEntered = headAttemptEntered || record.headAttempted
        admissionAttemptEntered = admissionAttemptEntered || record.admissionAttempted
    }

    fun retainAction(action: LocalDevicePairingAction) {
        require(action.originalActionId == actionId && action.requested.request.requestId == requestId &&
            action.requested.admission.deviceId == deviceId && action.requested.target.keyId == keyId)
        requested?.let { require(sameDeviceAdmissionDefinition(it, action.requested)) }
        this.action?.let { require(it.challenge.challengeDigest == action.challenge.challengeDigest) }
        record?.identity?.let { require(it.definitionDigest == action.requested.definitionDigest.value &&
            it.requestDigest == action.requested.request.contentDigest.value && it.challengeDigest == action.challenge.challengeDigest.value &&
            it.sdkDigest == action.requested.sdkIdentity?.descriptorDigest?.value && it.rootFingerprint == action.challenge.rootFingerprint) }
        this.action = action; requested = action.requested; phase = action.phase
    }
    fun retainApproval(approval: LocalDevicePairingApproval) {
        require(approval.originalActionId == actionId && sameDeviceAdmissionDefinition(checkNotNull(requested), approval.requested) &&
            approval.challengeDigest == checkNotNull(action).challenge.challengeDigest)
        this.approval?.let { require(it.authorityDigest == approval.authorityDigest && it.challengeDigest == approval.challengeDigest) }
        record?.authorityDigest?.let { require(it == approval.authorityDigest.value) }
        this.approval = approval
    }
    fun projection(stage: LocalSetupDeviceAdmissionStage, outcome: LocalSetupDeviceAdmissionOutcome? = null,
        eventId: DomainId? = null): LocalSetupDeviceAdmissionView {
        val challenge = action?.challenge
        return LocalSetupDeviceAdmissionView(DomainId.random(), actionId, requestId,
            deviceId, keyId, stage, phase, requested, challenge?.companyName,
            challenge?.headName, challenge?.headLogin, challenge?.rootFingerprint ?: record?.identity?.rootFingerprint,
            challenge?.challengeDigest?.value ?: record?.identity?.challengeDigest,
            approval?.authorityDigest?.value ?: record?.authorityDigest, headAttemptEntered,
            admissionAttemptEntered, outcome, eventId)
    }
    override fun toString(): String = "LocalSetupDeviceAdmissionOriginal([REDACTED])"
}

internal fun sameDeviceAdmissionDefinition(a: LocalDevicePairingDefinition, b: LocalDevicePairingDefinition): Boolean {
    if (a.request != b.request || a.definitionDigest != b.definitionDigest) return false
    val left = a.copyWireBytes()
    return try {
        val right = b.copyWireBytes()
        try { left.contentEquals(right) } finally { right.fill(0) }
    } finally { left.fill(0) }
}

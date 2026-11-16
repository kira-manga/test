package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession
import com.ukkera.pos.security.NativeDeviceAdmissionHistorySource

/** Memory-only registered proof of this native-enrolled employee's original interactive
 * account, policy, admission and issuance at the exact configuration/pricing refusal cut.
 * It binds only this CLOSED candidate to the same genuine native read; the installed
 * producer and full native closure must still prove the actual refusal and result. */
internal class RuntimeEmployeeConfigurationRejectionHistoryAuthorization internal constructor(
    private val owner: RuntimeEmployeeAuthenticationReplay,
    private val source: RuntimeEmployeeHistoryOrigin,
    private val header: RuntimeHistoryEventHeaderV2,
    private val eventDigest: PayloadDigest,
    val profile: RuntimeHistoricalConfigurationRejectionProfile,
    val evidence: RuntimeCommandAuthorizationEvidenceV1,
    private val capturedPolicyDigest: PayloadDigest,
    private val additionalDeviceOrigin: NativeDeviceAdmissionHistorySource?,
    private val capturedAdmissionDigest: PayloadDigest,
) {
    val sequence: Long get() = header.sequence
    private var capturedSession: SelfOwnedNativeHistoryReadSession? = null
    private var discarded = false
    var joined: Boolean = false
        private set

    fun bind(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest) {
        owner.requireIssuedConfigurationRejection(this)
        historyShapeV2(!discarded && capturedSession == null && !joined && this.header == header && this.eventDigest == eventDigest)
        requireOriginalSource(session)
        capturedSession = session
    }

    fun requireCaptured(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        owner.requireIssuedConfigurationRejection(this)
        historyShapeV2(!discarded && capturedSession === session && this.header == header && this.eventDigest == eventDigest &&
            this.evidence === evidence)
        requireOriginalSource(session)
    }

    fun markJoined(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        requireCaptured(session, header, eventDigest, evidence)
        historyShapeV2(!joined)
        joined = true
    }

    private fun requireOriginalSource(session: SelfOwnedNativeHistoryReadSession) {
        owner.requireCommandSession(session)
        val coordinates = session.original.record.allocation.coordinates
        val authorization = evidence.authorization
        historyShapeV2(source.matchesNativeSelection(session) &&
            source.scope == coordinates.scope && authorization.scope == coordinates.scope &&
            source.actorId == authorization.actorId && authorization.actorId != coordinates.firstActorId &&
            source.originalCohostDeviceId == coordinates.deviceId &&
            source.child.historySequence < header.sequence && source.child.commandSequence < header.commandSequence &&
            source.child.commandEpoch == header.commandEpoch && source.child.installationGenesisDigest == header.installationGenesisDigest &&
            header.scope == coordinates.scope.storageKey() && header.assignmentId == coordinates.assignmentId &&
            profile.matches(header, evidence) &&
            authorization.dependencies.policy == capturedPolicyDigest &&
            authorization.dependencies.admission == capturedAdmissionDigest)
        if (authorization.deviceId == source.originalCohostDeviceId) {
            historyShapeV2(additionalDeviceOrigin == null && capturedAdmissionDigest == source.admissionDigests.single {
                it.first == source.originalCohostDeviceId }.second)
        } else {
            val origin = checkNotNull(additionalDeviceOrigin)
            historyShapeV2(profile.matchesAdditionalDeviceNonSale(header, evidence) &&
                session.hasDeviceAdmissionHistoryTraversal && session.deviceAdmissions.any { it === origin } &&
                origin.originalCohostDeviceId == coordinates.deviceId &&
                origin.requested.request.scope == coordinates.scope && origin.requested.request.targetId == authorization.deviceId &&
                origin.requested.admission.admitted &&
                origin.child.historySequence < header.sequence && origin.child.commandEpoch == header.commandEpoch &&
                origin.child.installationGenesisDigest == header.installationGenesisDigest &&
                origin.evidence.images.first().plaintextDigest == capturedAdmissionDigest)
        }
    }

    fun clear() { discarded = true; capturedSession = null }
    override fun toString(): String = "RuntimeEmployeeConfigurationRejectionHistoryAuthorization([REDACTED])"
}

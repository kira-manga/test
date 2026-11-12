package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession

/** Distinct registered original enrolled-employee proof for closed QUALIFY/Accept
 * refusal. It binds this exact native read/event/evidence, never Apply, native retention,
 * another refusal or current access. Genuine CASE/entitlement/qualification sources,
 * the exact first refusal and the complete native footprint still join in the business pass. */
internal class RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization internal constructor(
    private val owner: RuntimeEmployeeAuthenticationReplay,
    private val source: RuntimeEmployeeHistoryOrigin,
    private val header: RuntimeHistoryEventHeaderV2,
    private val eventDigest: PayloadDigest,
    val profile: RuntimeHistoricalReturnCaseQualificationRejectionProfile,
    val evidence: RuntimeCommandAuthorizationEvidenceV1,
    private val capturedPolicyDigest: PayloadDigest,
) {
    val sequence: Long get() = header.sequence
    private var capturedSession: SelfOwnedNativeHistoryReadSession? = null
    private var discarded = false
    var joined: Boolean = false
        private set

    fun bind(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest) {
        owner.requireIssuedReturnCaseQualificationRejection(this)
        historyShapeV2(!discarded && capturedSession == null && !joined && this.header == header && this.eventDigest == eventDigest)
        requireOriginalSource(session)
        capturedSession = session
    }

    fun requireCaptured(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        owner.requireIssuedReturnCaseQualificationRejection(this)
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
        val coordinates = session.original.record.allocation.coordinates
        val authorization = evidence.authorization
        historyShapeV2(source.matchesNativeSelection(session) &&
            source.scope == coordinates.scope && authorization.scope == coordinates.scope &&
            source.actorId == authorization.actorId && authorization.actorId != coordinates.firstActorId &&
            source.originalCohostDeviceId == coordinates.deviceId && authorization.deviceId == source.originalCohostDeviceId &&
            source.child.historySequence < header.sequence && source.child.commandSequence < header.commandSequence &&
            source.child.commandEpoch == header.commandEpoch && source.child.installationGenesisDigest == header.installationGenesisDigest &&
            header.scope == coordinates.scope.storageKey() && header.assignmentId == coordinates.assignmentId &&
            profile.matches(header, evidence) &&
            authorization.dependencies.policy == capturedPolicyDigest &&
            authorization.dependencies.admission == source.admissionDigests.single {
                it.first == source.originalCohostDeviceId }.second)
    }

    fun clear() { discarded = true; capturedSession = null }
    override fun toString(): String = "RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization([REDACTED])"
}

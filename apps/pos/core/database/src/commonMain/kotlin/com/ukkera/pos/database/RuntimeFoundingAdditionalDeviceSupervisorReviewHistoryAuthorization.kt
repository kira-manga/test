package com.ukkera.pos.database

import com.ukkera.pos.security.NativeDeviceAdmissionHistorySource
import com.ukkera.pos.security.OriginalNativeHistorySnapshot
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession

/** Memory-only, separately registered founding/additional-device audited-Review proof.
 * Only the actual C1-seeded reducer may issue it at the original native event cut.
 * No cohost predicate, enrolled actor, positive Apply/Start, raw intake or grant is reused.
 * The exact whole business rejection program and five mutations must still join. */
internal class RuntimeFoundingAdditionalDeviceSupervisorReviewHistoryAuthorization internal constructor(
    private val owner: RuntimeOriginalAuthenticationReplay,
    private val original: OriginalNativeHistorySnapshot,
    private val selected: SelfOwnedNativeHistoryReadSession,
    private val admission: NativeDeviceAdmissionHistorySource,
    private val header: RuntimeHistoryEventHeaderV2,
    private val eventDigest: PayloadDigest,
    val profile: RuntimeHistoricalSupervisorReviewProfile,
    val evidence: RuntimeCommandAuthorizationEvidenceV1,
    private val capturedPolicyDigest: PayloadDigest,
    private val capturedAdmissionDigest: PayloadDigest,
) {
    val sequence: Long get() = header.sequence
    private var capturedSession: SelfOwnedNativeHistoryReadSession? = null
    private var discarded = false
    var joined: Boolean = false
        private set

    fun bind(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest) {
        owner.requireIssuedAdditionalDeviceSupervisorReview(this, session)
        historyShapeV2(!discarded && capturedSession == null && !joined && this.header == header && this.eventDigest == eventDigest)
        requireOriginalSource(session)
        capturedSession = session
    }

    fun requireCaptured(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        owner.requireIssuedAdditionalDeviceSupervisorReview(this, session)
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
        val native = original.record
        val coordinates = native.allocation.coordinates
        val first = checkNotNull(native.firstChild)
        val authorization = evidence.authorization
        historyShapeV2(selected === session && session.original === original &&
            session.hasChronologicalAccountImageTraversal && session.hasDeviceAdmissionHistoryTraversal &&
            session.deviceAdmissions.any { it === admission } &&
            authorization.actorId == coordinates.firstActorId && authorization.scope == coordinates.scope &&
            authorization.deviceId != coordinates.deviceId &&
            admission.originalCohostDeviceId == coordinates.deviceId &&
            admission.requested.request.scope == coordinates.scope && admission.requested.request.targetId == authorization.deviceId &&
            admission.requested.admission.admitted &&
            admission.child.historySequence > 1L && admission.child.historySequence < header.sequence &&
            admission.child.commandSequence < header.commandSequence &&
            admission.child.commandEpoch == header.commandEpoch && header.commandEpoch == checkNotNull(native.definition).definition.authorityEpoch.value &&
            admission.child.installationGenesisDigest == first.installationGenesisDigest && header.installationGenesisDigest == first.installationGenesisDigest &&
            header.scope == coordinates.scope.storageKey() && header.assignmentId == coordinates.assignmentId &&
            (profile.matches(header, evidence) || profile.matchesApplicationRefusal(header, evidence)) &&
            authorization.dependencies.policy == capturedPolicyDigest && authorization.dependencies.admission == capturedAdmissionDigest &&
            admission.evidence.images.first().plaintextDigest == capturedAdmissionDigest)
    }

    fun clear() { discarded = true; capturedSession = null }
    override fun toString(): String = "RuntimeFoundingAdditionalDeviceSupervisorReviewHistoryAuthorization([REDACTED])"
}

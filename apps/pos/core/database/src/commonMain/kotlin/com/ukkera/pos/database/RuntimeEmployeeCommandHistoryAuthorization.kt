package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession

/** Memory-only proof of one original employee command at its actual historical event cut.
 * The separate native-seeded reducer registers this exact object ONLY after joining its
 * original account, unchanged complete policy/admission and immutable interactive issuance.
 * An internal constructor, copied DATA or a successful policy Boolean cannot register it.
 * No principal, AuthorizedAction, bearer, writer or native-retention authority is created.
 * Every seed/authentication/native image and current closure must still pass the full scan.
 */
internal class RuntimeEmployeeCommandHistoryAuthorization internal constructor(
    private val owner: RuntimeEmployeeAuthenticationReplay,
    private val source: RuntimeEmployeeHistoryOrigin,
    private val header: RuntimeHistoryEventHeaderV2,
    private val eventDigest: PayloadDigest,
    val evidence: RuntimeCommandAuthorizationEvidenceV1,
    private val capturedPolicyDigest: PayloadDigest,
) {
    val sequence: Long get() = header.sequence
    private var capturedSession: SelfOwnedNativeHistoryReadSession? = null
    private var discarded = false
    var joined: Boolean = false
        private set

    /** Single capture into this same actual native read, never a transferable approval. */
    fun bind(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest) {
        owner.requireIssuedCommand(this)
        historyShapeV2(!discarded && capturedSession == null && !joined && this.header == header && this.eventDigest == eventDigest)
        requireOriginalSource(session)
        capturedSession = session
    }

    /** Later command/source traversal reuses only this exact registered historical proof,
     * not the reducer's now-advanced account or today's account/policy/session rows. */
    fun requireCaptured(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        owner.requireIssuedCommand(this)
        historyShapeV2(!discarded && capturedSession === session && this.header == header && this.eventDigest == eventDigest &&
            this.evidence === evidence)
        requireOriginalSource(session)
    }

    fun markJoined(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        requireCaptured(session, header, eventDigest, evidence)
        historyShapeV2(!joined)
        owner.markCommandJoined(this)
        joined = true
    }

    /** End this exact proof's scope after all native original/core/producer joins. A later
     * earlier-original loan needs a genuine replay and join, never this discarded object. */
    fun retireJoinedCommand(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        owner.retireJoinedCommand(this, session, header, eventDigest, evidence)
    }

    private fun requireOriginalSource(session: SelfOwnedNativeHistoryReadSession) {
        owner.requireCommandSession(session)
        val coordinates = session.original.record.allocation.coordinates
        val authorization = evidence.authorization
        historyShapeV2(source.matchesNativeSelection(session) &&
            source.scope == coordinates.scope && authorization.scope == coordinates.scope &&
            source.actorId == authorization.actorId && authorization.actorId != coordinates.firstActorId &&
            source.originalCohostDeviceId == coordinates.deviceId && authorization.deviceId == source.originalCohostDeviceId &&
            source.child.historySequence < header.sequence && source.child.commandSequence < header.commandSequence &&
            source.child.commandEpoch == header.commandEpoch && source.child.installationGenesisDigest == header.installationGenesisDigest &&
            header.scope == coordinates.scope.storageKey() && header.assignmentId == coordinates.assignmentId &&
            header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && evidence.purposeTag == header.purpose.tag &&
            evidence.capturedChanges == header.changeCount && evidence.actionApprovalId == null &&
            authorization.dependencies.policy == capturedPolicyDigest &&
            authorization.dependencies.admission == source.admissionDigests.single {
                it.first == source.originalCohostDeviceId }.second)
    }

    fun clear() { discarded = true; capturedSession = null; joined = false }
    override fun toString(): String = "RuntimeEmployeeCommandHistoryAuthorization([REDACTED])"
}

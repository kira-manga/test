package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Receiving facts at one original command cut, not a current directory entry, principal,
 * session, permission grant or transferable authorization. Protected bytes never escape. */
public class RuntimeHistoricalLocalHandoffRecipient internal constructor(
    public val actorId: DomainId,
    public val label: String,
    public val accountRevision: Revision,
    public val policyRevision: Revision,
) {
    override fun toString(): String = "RuntimeHistoricalLocalHandoffRecipient([REDACTED])"
}

/** Extracted only inside the native-seeded reducers. No credential or issuance is retained. */
internal class RuntimeHistoricalLocalHandoffRecipientFacts private constructor(
    val scope: BranchScope,
    val actorId: DomainId,
    val enabled: Boolean,
    val accountRevision: Revision,
    val generation: SessionGeneration,
    val credentialPresent: Boolean,
    val policy: ActivatedLocalPolicy,
    val configuration: LocalAuthConfiguration,
) {
    companion object {
        fun capture(account: ProtectedLocalAccount, policy: ActivatedLocalPolicy,
            configuration: LocalAuthConfiguration): RuntimeHistoricalLocalHandoffRecipientFacts {
            val state = account.state
            historyShapeV2(policy.scope == state.scope && policy.actorId == state.actorId &&
                configuration.scope == state.scope && configuration.actorId == state.actorId)
            return RuntimeHistoricalLocalHandoffRecipientFacts(state.scope, state.actorId, state.enabled,
                state.revision, state.generation, account.credential != null, policy, configuration)
        }
    }
}

/** Same-read, event-order receiving projection. Origins are actual C1/enrollment joins,
 * never the seed prepass or current census. Account points are published only after the
 * supported reducer has compared its complete event; native LINEAGE and final closure are
 * independently mandatory. A separate exact native policy-event point changes receiving
 * grants only at its actual cut; configuration remains an immutable native-origin fact.
 * The reserve callback only charges the owner's existing shared byte budget, not authority. */
internal class RuntimeHistoricalLocalHandoffRecipientTimeline(
    private val session: SelfOwnedNativeHistoryReadSession,
    private val reserveBytes: (Long) -> Unit,
) {
    private val native = session.original.record
    private val coordinates = native.allocation.coordinates
    private val scope = coordinates.scope
    private val expectedActors = (listOf(coordinates.firstActorId) +
        session.employeeEnrollments.map { it.requested.request.targetId }).toSet()
    private class Point(val sequence: Long, facts: RuntimeHistoricalLocalHandoffRecipientFacts) {
        val revision = facts.accountRevision
        val enabled = facts.enabled
        val generation = facts.generation
        val credentialPresent = facts.credentialPresent
        val policy = facts.policy
        fun matches(facts: RuntimeHistoricalLocalHandoffRecipientFacts): Boolean =
            revision == facts.accountRevision && enabled == facts.enabled && generation == facts.generation &&
                credentialPresent == facts.credentialPresent
    }
    private class Origin(facts: RuntimeHistoricalLocalHandoffRecipientFacts, first: Point) {
        val configuration = facts.configuration
        val points = mutableListOf(first)
    }
    private val origins = linkedMapOf<DomainId, Origin>()
    private var pointCount = 0L
    private var eventsClosed = false
    private var enabled = true

    fun founding(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        replay: RuntimeOriginalAuthenticationReplay) {
        if (!enabled) return
        requireEvent(header)
        historyShapeV2(header.sequence == 1L && header.purpose == RuntimeWritePurposeV2.ORIGINAL_INSTALLATION &&
            native.firstChild?.historyDigest == eventDigest)
        // The older fromCensus reducer deliberately provides no native origin.
        val facts = replay.nativeHandoffRecipientFacts(session) ?: return
        historyShapeV2(facts.actorId == coordinates.firstActorId)
        addOrigin(header.sequence, facts)
    }

    fun enrollment(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        replay: RuntimeEmployeeAuthenticationReplay) {
        if (!enabled) return
        requireEvent(header)
        addOrigin(header.sequence, replay.nativeHandoffRecipientOrigin(session, header, eventDigest))
    }

    fun accountAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeOriginalAuthenticationReplay) {
        if (!enabled) return
        requireAccountEvent(header)
        // Absence of the new native origin is UNKNOWN, not an old census-derived account.
        if (coordinates.firstActorId !in origins) return
        append(header.sequence, replay.nativeHandoffRecipientFacts(session) ?: unavailable())
    }

    fun accountAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeEmployeeAuthenticationReplay) {
        if (!enabled) return
        requireAccountEvent(header)
        append(header.sequence, replay.handoffRecipientAccountAfter(session, header))
    }
    fun policyAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeOriginalAuthenticationReplay) {
        if (!enabled) return
        requireEvent(header)
        if (coordinates.firstActorId !in origins) return
        val facts = replay.nativeHandoffRecipientFacts(session) ?: unavailable()
        appendPolicy(header.sequence,facts,requirePolicyEvent(header,facts.actorId))
    }
    fun policyAfter(header: RuntimeHistoryEventHeaderV2, replay: RuntimeEmployeeAuthenticationReplay) {
        if (!enabled) return
        requireEvent(header)
        val facts = replay.handoffRecipientAccountAfter(session,header)
        appendPolicy(header.sequence,facts,requirePolicyEvent(header,facts.actorId))
    }

    private fun addOrigin(sequence: Long, facts: RuntimeHistoricalLocalHandoffRecipientFacts) {
        historyShapeV2(facts.scope == scope && facts.actorId in expectedActors && facts.actorId !in origins &&
            facts.enabled && facts.accountRevision == Revision(0) && facts.generation == SessionGeneration(0))
        historyCapacityV2(pointCount < MAX_POINTS)
        val policy = LocalEffectivePolicyCodec.encodePolicy(facts.policy)
        try {
            val configuration = LocalAuthenticationCodec.encodeConfiguration(facts.configuration)
            try {
                // Immutable dependencies are held once per actor. The fixed overhead also
                // accounts conservatively for map/list references and the account point.
                reserveBytes(ORIGIN_BYTES + POINT_BYTES + policy.size.toLong() + configuration.size.toLong())
            } finally { configuration.fill(0) }
        } finally { policy.fill(0) }
        origins[facts.actorId] = Origin(facts, Point(sequence, facts))
        pointCount++
    }

    private fun append(sequence: Long, facts: RuntimeHistoricalLocalHandoffRecipientFacts) {
        val origin = origins[facts.actorId] ?: unavailable()
        val previous = origin.points.last()
        historyShapeV2(facts.scope == scope && facts.policy === previous.policy && facts.configuration === origin.configuration)
        historyShapeV2(sequence > previous.sequence)
        if (previous.revision == facts.accountRevision) {
            historyShapeV2(previous.matches(facts))
            return
        }
        historyShapeV2(facts.accountRevision > previous.revision)
        historyCapacityV2(pointCount < MAX_POINTS)
        reserveBytes(POINT_BYTES)
        origin.points += Point(sequence, facts)
        pointCount++
    }
    private fun appendPolicy(sequence: Long, facts: RuntimeHistoricalLocalHandoffRecipientFacts,
        source: NativeEmployeePolicyActivationHistorySource) {
        val origin = origins[facts.actorId] ?: unavailable()
        val previous = origin.points.last()
        historyShapeV2(facts.scope == scope && facts.configuration === origin.configuration && sequence > previous.sequence &&
            facts.accountRevision >= previous.revision && previous.policy.revision.value < Long.MAX_VALUE &&
            facts.policy.revision == previous.policy.revision.next() && facts.policy.actorId == source.requested.request.targetId)
        if (facts.accountRevision == previous.revision) historyShapeV2(previous.matches(facts))
        val policy = LocalEffectivePolicyCodec.encodePolicy(facts.policy)
        val approved = source.requested.copyCanonicalPolicy()
        try {
            historyShapeV2(policy.contentEquals(approved) && facts.accountRevision == source.evidence.afterAccountRevision)
            historyCapacityV2(pointCount < MAX_POINTS)
            reserveBytes(POINT_BYTES + policy.size.toLong())
        } finally { policy.fill(0); approved.fill(0) }
        origin.points += Point(sequence,facts)
        pointCount++
    }

    fun closeEvents() {
        if (!enabled) return
        historyShapeV2(!eventsClosed)
        eventsClosed = true
        // Missing origins do not change older supported business histories. Only the new
        // receiving query refuses them; it cannot turn UNKNOWN into proven absence.
    }

    fun before(capture: RuntimeBusinessHistoryJoin.CommandCapture, orderId: DomainId,
        recipientId: DomainId): RuntimeHistoricalLocalHandoffRecipient? {
        if (!enabled || !eventsClosed || origins.keys != expectedActors) unavailable()
        requireCut(capture.header)
        historyShapeV2(capture.scope == scope)
        val origin = origins[recipientId] ?: return null
        val point = origin.points.lastOrNull { it.sequence < capture.header.sequence } ?: return null
        if (!point.enabled || point.generation.value == Long.MAX_VALUE || !point.credentialPresent) return null
        historyShapeV2(point.policy.scope == scope && point.policy.actorId == recipientId &&
            origin.configuration.scope == scope && origin.configuration.actorId == recipientId)
        if (!AuthorizationEvaluator.permitsLocalHandoffRecipient(point.policy, scope, orderId)) return null
        return RuntimeHistoricalLocalHandoffRecipient(recipientId, origin.configuration.login.value,
            point.revision, point.policy.revision)
    }

    private fun requireEvent(header: RuntimeHistoryEventHeaderV2) {
        historyShapeV2(enabled && !eventsClosed)
        requireCut(header)
    }
    private fun requireAccountEvent(header: RuntimeHistoryEventHeaderV2) {
        requireEvent(header)
        historyShapeV2(header.sequence > 1L && header.purpose in setOf(RuntimeWritePurposeV2.SESSION_ISSUE,
            RuntimeWritePurposeV2.SESSION_REFRESH, RuntimeWritePurposeV2.SESSION_LOGOUT,
            RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION, RuntimeWritePurposeV2.DEVICE_ADMINISTRATION))
    }
    private fun requirePolicyEvent(header: RuntimeHistoryEventHeaderV2, actor: DomainId): NativeEmployeePolicyActivationHistorySource {
        val source = session.employeePolicyActivations.singleOrNull {
            it.child.historySequence == header.sequence && it.requested.request.targetId == actor
        } ?: unavailable()
        historyShapeV2(header.sequence > 1L && header.purpose == RuntimeWritePurposeV2.PROVISIONING &&
            header.transactionId == source.frameId && header.parentSequence == source.preparationParent.historySequence &&
            header.parentDigest == source.preparationParent.historyDigest &&
            NativeHistoryRecordedHeadV2(header.sequence,source.child.historyDigest,header.installationGenesisDigest,
                header.commandEpoch,header.commandSequence,header.commandDigest,header.roleCommitment,header.recoveryCommitment) == source.child)
        return source
    }
    private fun requireCut(header: RuntimeHistoryEventHeaderV2) {
        historyShapeV2(header.scope == scope.storageKey() && header.assignmentId == coordinates.assignmentId &&
            header.originalAllocationDigest == native.allocation.digest &&
            header.commandEpoch == checkNotNull(native.definition).definition.authorityEpoch.value &&
            header.installationGenesisDigest == native.firstChild?.installationGenesisDigest)
    }
    fun abandon() {
        enabled = false; eventsClosed = false
        origins.values.forEach { it.points.clear() }; origins.clear(); pointCount = 0L
    }
    private fun unavailable(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.UNRESOLVED_PROTOCOL)

    companion object {
        private const val POINT_BYTES: Long = 128L
        private const val ORIGIN_BYTES: Long = 256L
        // Derived from the existing shared byte workspace, not a new business limit.
        private const val MAX_POINTS: Long = RuntimeBusinessHistoryJoin.MAX_RETAINED_BYTES / POINT_BYTES
    }
}

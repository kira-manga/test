package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision

internal enum class RuntimeEmployeePolicyActivationTargetOwner { ORIGINAL, ENROLLED_EMPLOYEE }

/** Pure prediction from an already authentic original action at its actual ordered event.
 * It grants no authority. Current census is not an input; original native LINEAGE must still
 * independently join every physical BEFORE/AFTER and actual protected plaintext below. */
internal class RuntimeEmployeePolicyActivationTransition(
    source: NativeEmployeePolicyActivationHistorySource,
    before: ProtectedLocalAccount,
    previous: ActivatedLocalPolicy,
) {
    val policy: ActivatedLocalPolicy = source.requested.nextPolicy
    val policyDigest: PayloadDigest
    val account: ProtectedLocalAccount
    val images: List<RuntimeAuthenticationImageV1>

    init {
        val requested = source.requested
        val request = requested.request
        val evidence = source.evidence
        val scope = request.scope
        val actor = request.targetId
        val canonical = requested.copyCanonicalPolicy()
        val semantic = LocalEffectivePolicyCodec.encodePolicy(policy)
        try {
            historyShapeV2(canonical.contentEquals(semantic) && request.action == LocalProvisioningAction.ACTIVATE_POLICY &&
                previous.scope == scope && previous.actorId == actor && before.state.scope == scope && before.state.actorId == actor &&
                previous.revision == requested.previousPolicyRevision && policy.revision == previous.revision.next() &&
                digest(LocalEffectivePolicyCodec.encodePolicy(previous)) == evidence.beforePolicyDigest &&
                digest(ProtectedLocalSecurityCodec.encodeAccount(before)) == evidence.beforeAccountDigest)
            policyDigest = sha256(semantic)
            val state = before.state
            val revoke = state.sessions.count { it.status == SessionStatus.ACTIVE } > policy.sessionPolicy.maximumConcurrentSessions
            val predicted = if (revoke) (LocalSessions.revokeAll(state) as? SessionTransition.Applied)?.state
                ?: malformed() else state
            account = ProtectedLocalAccount(predicted, before.credentialRevision, before.credential)
            val afterDigest = digest(ProtectedLocalSecurityCodec.encodeAccount(account))
            historyShapeV2(afterDigest == evidence.afterAccountDigest && predicted.revision == evidence.afterAccountRevision)
            val event = LocalAuthEvent(scope, request.requestId, LocalAuthEventKind.POLICY_ACTIVATED, actor,
                null, null, predicted.revision, evidence.eventAt, request.contentDigest)
            val expected = listOf(LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.EFFECTIVE_POLICY,
                actor, policy.revision, evidence.beforePolicyDigest, policyDigest)) +
                (if (revoke) listOf(LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.ACCOUNT,
                    actor, predicted.revision, evidence.beforeAccountDigest, afterDigest)) else emptyList()) +
                LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.AUTH_EVENT, request.requestId, Revision(0),
                    null, digest(LocalAuthenticationCodec.encodeEvent(event)))
            historyShapeV2(expected == evidence.images)
            images = expected.map { value ->
                val key = when (value.table) {
                    ProtectedLocalTable.EFFECTIVE_POLICY -> ProtectedLocalRecordKey.EffectivePolicy(scope, actor)
                    ProtectedLocalTable.ACCOUNT -> ProtectedLocalRecordKey.Account(scope, actor)
                    ProtectedLocalTable.AUTH_EVENT -> ProtectedLocalRecordKey.AuthEvent(scope, request.requestId)
                    else -> malformed()
                }
                RuntimeAuthenticationImageV1.capture(ProtectedLocalBinding(key, value.revision), value.plaintextDigest)
            }
        } finally { canonical.fill(0); semantic.fill(0) }
    }
    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}

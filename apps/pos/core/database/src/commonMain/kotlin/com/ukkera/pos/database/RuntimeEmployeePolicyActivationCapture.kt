package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision

/** Closed same-writer producer. The unchanged codecs qualify the complete approved policy
 * and actual BEFORE dependencies; only actual participating protected sealing supplies AFTER
 * hashes. Native-trigger mutation order and original native preparation independently join it. */
internal class RuntimeEmployeePolicyActivationCapture(
    val native: NativeEmployeePolicyActivationFrame,
    before: ProtectedLocalAccount,
    after: ProtectedLocalAccount,
    previous: ActivatedLocalPolicy,
    next: ActivatedLocalPolicy,
    private val event: LocalAuthEvent,
) {
    val request: LocalProvisioningRequest = native.requested.request
    val scope: BranchScope get() = request.scope
    private val beforePolicyDigest = policyDigest(previous)
    private val beforeAccountDigest = accountDigest(before)
    private val afterAccountDigest = accountDigest(after)
    private val afterAccountRevision = after.state.revision
    private val expectedImages: List<LocalEmployeePolicyActivationProtectedImage>
    val images = mutableListOf<LocalEmployeePolicyActivationProtectedImage>()
    private var preflight: LocalAuthenticationObservation? = null

    init {
        val requested = native.requested
        val policy = LocalEffectivePolicyCodec.encodePolicy(next)
        val retained = requested.copyCanonicalPolicy()
        try {
            val definition = LocalEmployeePolicyActivationDefinition.capture(request.requestId, next, policy)
            val actualDefinition = definition.copyWireBytes(); val retainedDefinition = requested.copyWireBytes()
            try { check(actualDefinition.contentEquals(retainedDefinition)) }
            finally { actualDefinition.fill(0); retainedDefinition.fill(0) }
            check(policy.contentEquals(retained) &&
                localPolicyActivationRequestV1(scope, request.requestId, next.actorId, policy.copyOf()) == request &&
                before.state.scope == scope && before.state.actorId == request.targetId &&
                previous.scope == scope && previous.actorId == request.targetId &&
                previous.revision == requested.previousPolicyRevision && next.revision == previous.revision.next())
            val state = before.state
            val revoke = state.sessions.count { it.status == SessionStatus.ACTIVE } > next.sessionPolicy.maximumConcurrentSessions
            val predicted = if (revoke) (LocalSessions.revokeAll(state) as? SessionTransition.Applied)?.state
                ?: protectedLocalUnavailable() else state
            check(afterAccountDigest == accountDigest(ProtectedLocalAccount(predicted, before.credentialRevision, before.credential)) &&
                event.scope == scope && event.eventId == request.requestId && event.kind == LocalAuthEventKind.POLICY_ACTIVATED &&
                event.targetId == request.targetId && event.operatorId == null && event.sessionId == null &&
                event.accountRevision == afterAccountRevision && event.contentDigest == request.contentDigest)
            expectedImages = listOf(LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.EFFECTIVE_POLICY,
                request.targetId, next.revision, beforePolicyDigest, sha256(policy))) +
                (if (revoke) listOf(LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.ACCOUNT,
                    request.targetId, afterAccountRevision, beforeAccountDigest, afterAccountDigest)) else emptyList()) +
                LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.AUTH_EVENT, request.requestId, Revision(0),
                    null, digest(LocalAuthenticationCodec.encodeEvent(event)))
        } finally { policy.fill(0); retained.fill(0) }
    }

    /** Actual protected BEFORE rereads/health and original native binding precede the writes. */
    fun bindPreflight(observed: RoomLocalAuthorizationObservation) {
        check(preflight == null && images.isEmpty())
        val binding = native.bindingObservation
        val current = observed.publicValue()
        check(current.issuerContextId == binding.issuerContextId && current.moment.bootId == binding.moment.bootId &&
            event.at.bootId == binding.moment.bootId && event.at.elapsedMillis >= binding.moment.elapsedMillis &&
            current.moment.elapsedMillis >= event.at.elapsedMillis)
        preflight = current
    }

    fun captureImage(binding: ProtectedLocalBinding, commitment: PayloadDigest) {
        check(preflight != null && binding.key.scope == scope && images.size < expectedImages.size)
        val expected = expectedImages[images.size]
        check(binding.key.table == expected.table && binding.key.recordId == expected.recordId &&
            binding.revision == expected.revision && commitment == expected.plaintextDigest)
        images += expected
    }
    fun evidence(): LocalEmployeePolicyActivationEffectEvidence {
        check(images == expectedImages)
        return LocalEmployeePolicyActivationEffectEvidence(request, native.requested.definitionDigest, native.authorityDigest,
            native.nativeBindingDigest, event.at, checkNotNull(preflight), beforePolicyDigest, beforeAccountDigest,
            afterAccountDigest, afterAccountRevision, images)
    }
    private fun policyDigest(value: ActivatedLocalPolicy): PayloadDigest = digest(LocalEffectivePolicyCodec.encodePolicy(value))
    private fun accountDigest(value: ProtectedLocalAccount): PayloadDigest = digest(ProtectedLocalSecurityCodec.encodeAccount(value))
    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    override fun toString(): String = "RuntimeEmployeePolicyActivationCapture([REDACTED])"
}

internal fun LocalEmployeePolicyActivationProtectedImage.runtimePolicyKey(scope: BranchScope): ByteArray {
    val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == this.table }
    return runtimeSecurityKey(table, listOf(scope.storageKey(), recordId.value))
}

package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact original service/native bridge over the SAME sole writer and protected source.
 * A complete review takes no employee credential and grants no branch employee session. */
internal class RoomSelfOwnedEmployeePolicyActivations(
    private val owner: RoomRuntimeHistoryOwner,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    val administration: RoomLocalCredentialAdministration,
    val native: LocalCompanyEmployeePolicyActivations,
    private val endpoint: LocalMasterEndpoint,
) {
    init { check(store.database === owner.database && source.belongsTo(store)) }

    fun matchesRetained(requested: LocalEmployeePolicyActivationDefinition, requestId: DomainId,
        nextPolicy: ActivatedLocalPolicy): Boolean {
        if (requested.request.requestId != requestId || nextPolicy.scope != owner.originalScope ||
            nextPolicy.revision.value == 0L) return false
        val policy = LocalEffectivePolicyCodec.encodePolicy(nextPolicy)
        return try {
            val captured = LocalEmployeePolicyActivationDefinition.capture(requestId, nextPolicy, policy)
            val actual = captured.copyWireBytes(); val retained = requested.copyWireBytes()
            try { actual.contentEquals(retained) } finally { actual.fill(0); retained.fill(0) }
        } finally { policy.fill(0) }
    }

    suspend fun prepare(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> {
        currentCoroutineContext().ensureActive()
        check(owner.operational && nextPolicy.scope == owner.originalScope && nextPolicy.revision.value > 0L)
        val requested = store.transaction { transaction ->
            val scope = owner.originalScope
            if (transaction.readAuthEvent(scope, requestId) != null) transaction.refuse()
            val account = transaction.readProtectedAccount(scope, nextPolicy.actorId) ?: transaction.refuse()
            val previous = transaction.readPolicy(scope, nextPolicy.actorId) ?: transaction.refuse()
            if (previous.revision.value == Long.MAX_VALUE || previous.revision.next() != nextPolicy.revision) transaction.refuse()
            if (account.state.sessions.count { it.status == SessionStatus.ACTIVE } > nextPolicy.sessionPolicy.maximumConcurrentSessions &&
                LocalSessions.revokeAll(account.state) !is SessionTransition.Applied) transaction.refuse()
            transaction.ensureAuthorizationHealthy()
            val policy = LocalEffectivePolicyCodec.encodePolicy(nextPolicy)
            try { LocalEmployeePolicyActivationDefinition.capture(requestId, nextPolicy, policy) } finally { policy.fill(0) }
        }
        val original = owner.originalRecord
        check(owner.operational && original.record.pending == null)
        return native.prepare(requested, original)
    }

    /** The caller's native lifetime remains a platform observation, never policy authority. */
    suspend fun approve(action: LocalEmployeePolicyActivationAction, headLogin: String,
        headSecret: CharArray, requireCurrentOwner: () -> Unit):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        val result = native.approve(action, headLogin, headSecret)
        requireCurrentOwner()
        if (result is LocalCompanyAuthorityResult.Value) {
            check(result.value.originalActionId == action.originalActionId &&
                result.value.requested.definitionDigest == action.requested.definitionDigest)
        }
        return result
    }

    /** Recovering late receipt DATA cannot recreate a live effect permit. */
    suspend fun recoverApproval(originalActionId: DomainId, late: Boolean, requireCurrentOwner: () -> Unit):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        val result = if (late) native.recoverConnection(originalActionId) else native.recoverApproval(originalActionId)
        requireCurrentOwner()
        if (result is LocalCompanyAuthorityResult.Value) check(result.value.originalActionId == originalActionId)
        return result
    }

    /** Shared original approval/dispatch/commit protocol over the composition's exact endpoint. */
    suspend fun activate(approval: LocalEmployeePolicyActivationAuthorityReceipt, nextPolicy: ActivatedLocalPolicy,
        requireCurrentOwner: () -> Unit): LocalCredentialAdministrationResult {
        val requestId = approval.requested.request.requestId
        if (!matchesRetained(approval.requested, requestId, nextPolicy)) return LocalCredentialAdministrationResult.Denied
        // Only native APPROVED can dispatch. A late recovered receipt cannot renew it.
        val retained = native.recoverApproval(approval.originalActionId)
        if (retained !is LocalCompanyAuthorityResult.Value) return LocalCredentialAdministrationResult.Unavailable
        if (retained.value.authorityDigest != approval.authorityDigest ||
            !matchesRetained(retained.value.requested, requestId, nextPolicy)) return LocalCredentialAdministrationResult.Denied
        requireCurrentOwner()
        val dispatch = native.beginPolicyActivation(retained.value.requested.request)
        check(dispatch.originalActionId == approval.originalActionId)
        val result = endpoint.activateOriginalEmployeePolicy(this, dispatch, nextPolicy)
        // Actual endpoint-worker cleanup precedes native proof of a known no-effect outcome.
        if (result !is LocalCredentialAdministrationResult.Applied) native.finishKnownNoEffect(dispatch)
        return result
    }

    suspend fun lookup(approval: LocalEmployeePolicyActivationAuthorityReceipt, nextPolicy: ActivatedLocalPolicy):
        LocalProvisioningStatus {
        val requestId = approval.requested.request.requestId
        if (!matchesRetained(approval.requested, requestId, nextPolicy)) return LocalProvisioningStatus.Denied
        return endpoint.lookupOriginalEmployeePolicy(this, requestId, nextPolicy)
    }
}

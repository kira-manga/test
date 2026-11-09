package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Actual production Room/native bridge: ONE original protected store/source/sole writer.
 * This full review takes no employee password, makes no INSERT and grants no local session.
 * Native independently joins the genuine original cut, head verification and retained definition. */
internal class RoomSelfOwnedEmployeeEnrollments(
    private val owner: RoomRuntimeHistoryOwner,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    val administration: RoomLocalCredentialAdministration,
    val native: LocalCompanyEmployeeEnrollments,
) {
    init { check(store.database === owner.database && source.belongsTo(store)) }

    /** Pure qualification with the ORIGINAL unchanged production codecs before dispatch.
     * Complete semantic bytes, not caller login/actor labels or a partial definition hash. */
    fun matchesRetained(requested: LocalEmployeeEnrollmentDefinition, requestId: DomainId,
        definition: LocalAccountProvisioning, credentialWasProvided: Boolean): Boolean {
        if (requested.request.requestId != requestId || requested.credentialWasProvided != credentialWasProvided ||
            definition.policy.scope != owner.originalScope) return false
        val policy = LocalEffectivePolicyCodec.encodePolicy(definition.policy)
        val configuration = LocalAuthenticationCodec.encodeConfiguration(LocalAuthConfiguration(owner.originalScope,
            definition.actorId, Revision(0), definition.login, definition.tokenPolicy))
        return try {
            val captured = LocalEmployeeEnrollmentDefinition.capture(requestId, definition, credentialWasProvided,
                policy, configuration, requested.admissions)
            val actual = captured.copyWireBytes(); val retained = requested.copyWireBytes()
            try { actual.contentEquals(retained) } finally { actual.fill(0); retained.fill(0) }
        } finally { policy.fill(0); configuration.fill(0) }
    }

    suspend fun prepare(requestId: DomainId, definition: LocalAccountProvisioning, credentialWasProvided: Boolean):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> {
        currentCoroutineContext().ensureActive()
        check(owner.operational && definition.policy.scope == owner.originalScope &&
            definition.actorId != owner.originalRecord.record.allocation.coordinates.firstActorId)
        val requested = store.transaction { transaction ->
            val scope = owner.originalScope; val actor = definition.actorId
            if (transaction.readAuthEvent(scope, requestId) != null || transaction.readProtectedAccount(scope, actor) != null ||
                transaction.readPolicy(scope, actor) != null || transaction.readAuthConfiguration(scope, actor) != null ||
                transaction.resolveLogin(scope, definition.login) != null) transaction.refuse()
            val admissions = transaction.readAdmissions(scope).sortedBy { it.deviceId.value }
            if (admissions.filter { it.admitted }.map { it.deviceId }.toSet() != definition.admittedDeviceIds) transaction.refuse()
            val dimensions = listOf<ThrottleDimension>(ThrottleDimension.Account) + admissions.map { ThrottleDimension.Device(it.deviceId) }
            for (dimension in dimensions) if (transaction.readThrottle(ThrottleKey(scope, actor, dimension)) != null) transaction.refuse()
            val accounts = mutableSetOf<DomainId>()
            transaction.forEachAccount(scope) { account -> check(accounts.add(account.state.actorId)) }
            if (accounts.size >= 4096 || actor in accounts) transaction.refuse()
            transaction.ensureAuthorizationHealthy()
            val policy = LocalEffectivePolicyCodec.encodePolicy(definition.policy)
            val configuration = LocalAuthenticationCodec.encodeConfiguration(LocalAuthConfiguration(scope, actor,
                Revision(0), definition.login, definition.tokenPolicy))
            try { LocalEmployeeEnrollmentDefinition.capture(requestId, definition, credentialWasProvided, policy, configuration, admissions) }
            finally { policy.fill(0); configuration.fill(0) }
        }
        // This is the owner's ACTUAL settled native snapshot after the no-child outer read.
        // If another writer moves the cut before native prepare, native refuses that stale cut.
        val original = owner.originalRecord
        check(owner.operational && original.record.pending == null)
        return native.prepare(requested, original)
    }
}

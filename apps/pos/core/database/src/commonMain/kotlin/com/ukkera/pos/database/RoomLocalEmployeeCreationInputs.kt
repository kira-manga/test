package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.MutableStateFlow

/** Loan-local input comparison for delegated creation. Only inspect's actual protected
 * reads can construct it. The public review response contains no such object. This is
 * neither employee authority nor an effect permit: the future writer still needs current
 * authorization, exact sealing/INSERTs, native final settlement and original recovery.
 * The callback borrows this comparison only until it returns; close drops its transaction
 * and census even when the callback is cancelled or throws. No reference may cross a loan. */
internal class RoomLocalEmployeeCreationInputs private constructor(
    transaction: RoomLocalSecurityTransaction,
    original: LocalDelegatedEmployeeCreationV1,
    principal: LocalEmployeePrincipal,
    snapshot: AuthorizationSnapshot,
    facts: RoomLocalAuthorizationFacts,
    accountIds: Set<DomainId>,
) {
    private val scope = original.scope
    private val requestId = original.requestId
    private val operatorId = original.operatorId
    private val actorId = original.actorId
    private val contentDigest = original.contentDigest
    private val sessionId = principal.sessionId
    private val deviceId = principal.deviceId
    private val generation = principal.generation
    private val kind = snapshot.kind
    private val purpose = snapshot.authenticationPurpose
    private val admissionRevision = snapshot.admissionRevision
    private val policyRevision = snapshot.policyRevision
    private val dependencies = facts.runtimeCommandDependencies()
    private val accountRevision = facts.account.revision
    private val credentialRevision = facts.credentialRevision
    private val issuerContextId = facts.issuance.issuerContextId
    private val issuedAt = facts.issuance.issuedAt
    private val expiresAtElapsedMillis = facts.issuance.expiresAtElapsedMillis
    private class Loan(val transaction: RoomLocalSecurityTransaction, val accountIds: List<DomainId>)
    private val loan = MutableStateFlow<Loan?>(Loan(transaction, accountIds.sortedBy { it.value }))

    fun reviewData(): LocalEmployeeCreationReviewV1.InputsChecked {
        check(loan.value != null)
        return LocalEmployeeCreationReviewV1.InputsChecked(requestId, operatorId, actorId, contentDigest)
    }

    /** Consume before the first INSERT, on this same still-live zero-write transaction.
     * IDs or a read-time InputsChecked value cannot manufacture this comparison. On any
     * mismatch/cancellation it is already retired; a later attempt must enumerate again. */
    suspend fun consumeAccountIds(transaction: RoomLocalSecurityTransaction,
        expectedRequestId: DomainId, expectedActorId: DomainId, expectedDigest: PayloadDigest,
        authorization: RuntimeCommandAuthorizationSnapshotV1): List<DomainId> {
        val retained = checkNotNull(loan.value)
        check(loan.compareAndSet(retained, null))
        require(retained.transaction === transaction && requestId == expectedRequestId &&
            actorId == expectedActorId && contentDigest == expectedDigest && scope == authorization.scope &&
            operatorId == authorization.actorId && sessionId == authorization.sessionId &&
            deviceId == authorization.deviceId && generation == authorization.generation &&
            kind == authorization.principalKind && purpose == authorization.authenticationPurpose &&
            admissionRevision == authorization.admissionRevision && policyRevision == authorization.policyRevision &&
            dependencies == authorization.dependencies && accountRevision == authorization.accountRevision &&
            credentialRevision == authorization.credentialRevision && issuerContextId == authorization.access.issuerContextId &&
            issuedAt == authorization.access.issuedAt && expiresAtElapsedMillis == authorization.access.expiresAtElapsedMillis)
        transaction.requireAdministrationWriteCount(0)
        return retained.accountIds.toList()
    }

    private fun close() { loan.value = null }
    override fun toString(): String = "RoomLocalEmployeeCreationInputs([REDACTED])"

    companion object {
        /** Same complete protected input check for read review and the future writer's
         * preflight. No credentials/current account objects survive the enumeration. */
        suspend fun <T> inspect(transaction: RoomLocalSecurityTransaction,
            principal: LocalEmployeePrincipal, original: LocalDelegatedEmployeeCreationV1,
            onRefused: (LocalEmployeeCreationReviewV1.Refused) -> T,
            onChecked: suspend (RoomLocalEmployeeCreationInputs) -> T): T {
            transaction.requireAdministrationWriteCount(0)
            fun refused(reason: LocalEmployeeCreationInputFailureV1,
                delegation: LocalEmployeeDelegationFailureV1? = null): T =
                onRefused(LocalEmployeeCreationReviewV1.Refused(reason, delegation))
            // Read all four dependencies on this exact transaction. A supplied snapshot
            // cannot substitute for the protected account/policy/admission/issuance images.
            val facts = transaction.readAuthorizationFacts(principal) ?: currentLocalAuthorizationDenied()
            val snapshot = facts.snapshot(principal)
            LocalEmployeeDelegationReviewV1.failure(principal, snapshot, original)?.let {
                return refused(LocalEmployeeCreationInputFailureV1.GRANT_DELEGATION, it)
            }
            val requested = original.copyRequestedDefinition()
            val definition = requested.definition
            val scope = original.scope
            val actor = original.actorId
            val creatorPolicy = facts.policy
            if (creatorPolicy.scope != scope || creatorPolicy.actorId != principal.actorId ||
                creatorPolicy.revision != snapshot.policyRevision) protectedLocalUnavailable()
            // Only the current protected, head-activated policy supplies permitted COMPLETE choices.
            // The public request and an InputsChecked response cannot supply this authority.
            if (creatorPolicy.employeeCreationConfiguration?.permits(definition) != true)
                return refused(LocalEmployeeCreationInputFailureV1.CONFIGURATION_DELEGATION)
            val admissions = transaction.readAdmissions(scope).sortedBy { it.deviceId.value }
            if (admissions.filter { it.admitted }.map { it.deviceId }.toSet() != definition.admittedDeviceIds)
                return refused(LocalEmployeeCreationInputFailureV1.ADMISSIONS_CHANGED)

            // Independently regenerate both real codecs and the complete retained admission images.
            // A benign semantic grant list beside different supplied policy bytes must never pass.
            val policy = LocalEffectivePolicyCodec.encodePolicy(definition.policy)
            val configuration = LocalAuthenticationCodec.encodeConfiguration(LocalAuthConfiguration(scope, actor,
                Revision(0), definition.login, definition.tokenPolicy))
            val consistent = try {
                val actual = LocalEmployeeEnrollmentDefinition.capture(original.requestId, definition, requested.credentialWasProvided,
                    policy, configuration, admissions).copyWireBytes()
                val retained = requested.copyWireBytes()
                try { actual.contentEquals(retained) } finally { actual.fill(0); retained.fill(0) }
            } finally { policy.fill(0); configuration.fill(0) }
            if (!consistent) return refused(LocalEmployeeCreationInputFailureV1.INCONSISTENT_DEFINITION)
            if (transaction.readAuthEvent(scope, original.requestId) != null || transaction.readProtectedAccount(scope, actor) != null ||
                transaction.readPolicy(scope, actor) != null || transaction.readAuthConfiguration(scope, actor) != null ||
                transaction.resolveLogin(scope, definition.login) != null)
                return refused(LocalEmployeeCreationInputFailureV1.TARGET_UNAVAILABLE)
            val dimensions = listOf<ThrottleDimension>(ThrottleDimension.Account) + admissions.map { ThrottleDimension.Device(it.deviceId) }
            for (dimension in dimensions) if (transaction.readThrottle(ThrottleKey(scope, actor, dimension)) != null)
                return refused(LocalEmployeeCreationInputFailureV1.TARGET_UNAVAILABLE)
            val accounts = mutableSetOf<DomainId>()
            transaction.forEachAccount(scope) { account -> check(accounts.add(account.state.actorId)) }
            if (accounts.size >= 4096 || actor in accounts)
                return refused(LocalEmployeeCreationInputFailureV1.ACCOUNT_CAPACITY)
            // The authenticated creator must occur in this actual complete census. A missing
            // creator is inconsistent storage, never a reusable empty/partial absence claim.
            if (principal.actorId !in accounts) protectedLocalUnavailable()
            transaction.ensureAuthorizationHealthy()
            transaction.requireAdministrationWriteCount(0)
            val checked = RoomLocalEmployeeCreationInputs(transaction, original, principal, snapshot, facts, accounts)
            return try { onChecked(checked) } finally { checked.close() }
        }
    }
}

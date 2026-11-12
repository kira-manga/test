package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One writer's actual LOCAL_ACCOUNT_ADMINISTRATION proof. Reset, disable and session revoke
 * are a CLOSED effect family. In particular, the operator may deliberately revoke its own
 * access: only the exact reducer-predicted account is accepted, not fresh authentication
 * through the intentionally invalidated session or an exemption for arbitrary changed facts.
 * Constructor credential records are reduced to canonical hashes and are never retained.
 */
internal class RoomLocalAccountAdministrationFrame(
    private val principal: LocalEmployeePrincipal,
    private val original: RoomLocalAuthorizationFacts,
    authorization: AuthorizedAction,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peer: RoomLocalAccessPeerBinding,
    private val context: CoroutineContext,
    targetBefore: ProtectedLocalAccount,
    targetAfter: ProtectedLocalAccount,
    private val event: LocalAuthEvent,
    archiveBefore: LocalSessionArchive? = null,
    originalCommand: LocalAdministrationCommand? = null,
    originalObservation: RoomLocalAuthorizationObservation? = null,
    retainedSessionAccess: LocalAccessIssuance? = null,
    retainedSessionRefresh: LocalRefreshIssuance? = null,
    resolutionPolicy: ActivatedLocalPolicy? = null,
    resolutionConfiguration: LocalAuthConfiguration? = null,
) {
    private enum class Phase { NEW, INITIALIZING, BODY, PREPARING, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    private var failure: Exception? = null
    private var owner: RoomLocalSecurityTransaction? = null
    private var finished = false
    private var expectedOperator: RoomLocalAuthorizationFacts? = null
    private val targetId = targetBefore.state.actorId
    private val beforeDigest = accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeAccount(targetBefore))
    private val afterDigest = accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeAccount(targetAfter))
    private val eventDigest = accountAdministrationDigest(LocalAuthenticationCodec.encodeEvent(event))
    private val archiveId = archiveBefore?.session?.sessionId
    private val archiveDigest = archiveBefore?.let { accountAdministrationDigest(LocalAuthenticationCodec.encodeArchive(it)) }
    private val targetPolicyDigest = resolutionPolicy?.let { accountAdministrationDigest(LocalEffectivePolicyCodec.encodePolicy(it)) }
    private val targetConfigurationDigest = resolutionConfiguration?.let { accountAdministrationDigest(LocalAuthenticationCodec.encodeConfiguration(it)) }
    private var historyRevocation: RuntimeSessionRevocationHistoryCapture? = null

    init {
        val requirement = authorization.requirement
        val binding = checkNotNull(requirement.binding)
        val before = targetBefore.state
        check(principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            principal.scope == peer.scope && principal.deviceId == peer.deviceId &&
            peer.matchesAdmission(original.admission) &&
            authorization.actorId == principal.actorId && authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.authenticationId == principal.sessionId && authorization.generation == principal.sessionGeneration &&
            authorization.policyRevision == original.policy.revision && authorization.approvalId == null &&
            requirement.scope == principal.scope &&
            requirement.permission == Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER) &&
            requirement.limitUse == LimitUse.NotApplicable && requirement.owner == OwnerConstraint.Unrestricted &&
            binding.reasonCode == "local-credential-administration" && binding.documentId == targetId &&
            binding.revision == before.revision &&
            (binding.operationId == null || binding.operationId?.value == event.eventId.value))
        check(before.scope == principal.scope && targetAfter.state.scope == before.scope &&
            targetAfter.state.actorId == targetId && event.scope == principal.scope && event.targetId == targetId &&
            event.operatorId == principal.actorId && event.contentDigest == binding.payloadDigest &&
            event.accountRevision == targetAfter.state.revision)
        val expected = when (event.kind) {
            LocalAuthEventKind.CREDENTIAL_RESET -> {
                check(event.sessionId == null && archiveBefore == null && before.enabled &&
                    targetBefore.credentialRevision.value < Long.MAX_VALUE && targetAfter.credential != null)
                val next = (LocalSessions.revokeAll(before) as? SessionTransition.Applied)?.state
                    ?: protectedLocalUnavailable()
                check(next.enabled)
                ProtectedLocalAccount(next, targetBefore.credentialRevision.next(), targetAfter.credential)
            }
            LocalAuthEventKind.SESSION_REVOKED -> {
                val sessionId = checkNotNull(event.sessionId)
                if (before.sessions.none { it.sessionId == sessionId }) {
                    check(archiveBefore?.session?.let {
                        it.scope == before.scope && it.actorId == targetId && it.sessionId == sessionId &&
                            it.status == SessionStatus.REVOKED
                    } == true)
                } else check(archiveBefore == null)
                val next = (LocalSessions.revoke(before, sessionId) as? SessionTransition.Applied)?.state
                    ?: protectedLocalUnavailable()
                ProtectedLocalAccount(next, targetBefore.credentialRevision, targetBefore.credential)
            }
            LocalAuthEventKind.ACCOUNT_ACTIVATED -> {
                check(event.sessionId == null && archiveBefore == null && targetId != principal.actorId &&
                    !before.enabled && targetBefore.credential == null && targetAfter.credential != null &&
                    targetBefore.credentialRevision.value < Long.MAX_VALUE && resolutionPolicy != null && resolutionConfiguration != null)
                val command = checkNotNull(originalCommand)
                val expected = checkNotNull(command.activationRevisions)
                check(command.action == LocalAdministrationAction.ACTIVATE_ACCOUNT &&
                    command.expectedCredentialRevision == targetBefore.credentialRevision && expected.policy == resolutionPolicy.revision &&
                    expected.configuration == resolutionConfiguration.revision && resolutionPolicy.scope == before.scope &&
                    resolutionPolicy.actorId == targetId && resolutionConfiguration.scope == before.scope &&
                    resolutionConfiguration.actorId == targetId)
                val next = (LocalSessions.reactivate(before) as? SessionTransition.Applied)?.state ?: protectedLocalUnavailable()
                ProtectedLocalAccount(next, targetBefore.credentialRevision.next(), targetAfter.credential)
            }
            LocalAuthEventKind.ACCOUNT_REACTIVATED -> {
                check(event.sessionId == null && archiveBefore == null && targetId != principal.actorId &&
                    targetBefore.credential != null && resolutionPolicy != null && resolutionConfiguration != null)
                val expected = checkNotNull(originalCommand?.reactivationRevisions)
                check(expected.credential == targetBefore.credentialRevision && expected.policy == resolutionPolicy.revision &&
                    expected.configuration == resolutionConfiguration.revision && resolutionPolicy.scope == before.scope &&
                    resolutionPolicy.actorId == targetId && resolutionConfiguration.scope == before.scope &&
                    resolutionConfiguration.actorId == targetId)
                val next = (LocalSessions.reactivate(before) as? SessionTransition.Applied)?.state ?: protectedLocalUnavailable()
                ProtectedLocalAccount(next, targetBefore.credentialRevision, targetBefore.credential)
            }
            LocalAuthEventKind.ACCOUNT_DISABLED -> {
                check(event.sessionId == null && archiveBefore == null)
                val next = (LocalSessions.disable(before) as? SessionTransition.Applied)?.state
                    ?: protectedLocalUnavailable()
                ProtectedLocalAccount(next, targetBefore.credentialRevision, targetBefore.credential)
            }
            else -> protectedLocalUnavailable()
        }
        check(afterDigest == accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeAccount(expected)))
        expectedOperator = if (targetId == principal.actorId) {
            check(original.sameDependencies(projectedFacts(targetBefore)))
            projectedFacts(targetAfter)
        } else original
        // The existing self-session format keeps its exact capture and validation. Only
        // the actual typed invocation supplies this protected authorization observation.
        if (originalCommand != null && originalObservation != null && event.kind == LocalAuthEventKind.SESSION_REVOKED &&
            targetId == principal.actorId && event.sessionId == principal.sessionId && archiveBefore == null &&
            before.revision.value < Long.MAX_VALUE - 1L && before.generation.value < Long.MAX_VALUE - 1L &&
            targetAfter.state.enabled) {
            historyRevocation = RuntimeSessionRevocationHistoryCapture.self(RuntimeSessionRevocationCapture.capture(
                principal, original, authorization, originalCommand, originalObservation, event))
        } else if (originalCommand != null && originalObservation != null && event.kind == LocalAuthEventKind.SESSION_REVOKED &&
            archiveBefore == null && targetAfter.state.enabled) {
            historyRevocation = RuntimeRetainedSessionRevocationCapture.capture(principal, original, authorization,
                originalCommand, originalObservation, event, targetBefore, retainedSessionAccess, retainedSessionRefresh)
                ?.let { RuntimeSessionRevocationHistoryCapture.retained(it) }
        } else if (originalCommand != null && originalObservation != null && event.kind == LocalAuthEventKind.ACCOUNT_ACTIVATED &&
            archiveBefore == null) {
            historyRevocation = RuntimeAccountActivationCapture.capture(principal, original, authorization,
                originalCommand, originalObservation, event, targetBefore, checkNotNull(resolutionPolicy), checkNotNull(resolutionConfiguration))
                ?.let { RuntimeSessionRevocationHistoryCapture.activation(it) }
        } else if (originalCommand != null && originalObservation != null && event.kind == LocalAuthEventKind.ACCOUNT_REACTIVATED &&
            archiveBefore == null) {
            historyRevocation = RuntimeAccountReactivationCapture.capture(principal, original, authorization,
                originalCommand, originalObservation, event, targetBefore, checkNotNull(resolutionPolicy), checkNotNull(resolutionConfiguration))
                ?.let { RuntimeSessionRevocationHistoryCapture.reactivation(it) }
        } else if (originalCommand != null && originalObservation != null && event.kind == LocalAuthEventKind.ACCOUNT_DISABLED &&
            archiveBefore == null) {
            historyRevocation = if (targetBefore.credential == null) {
                RuntimeCredentialFreeAccountDisableCapture.capture(principal, original, authorization,
                    originalCommand, originalObservation, event, targetBefore)
                    ?.let { RuntimeSessionRevocationHistoryCapture.credentialFreeDisable(it) }
            } else RuntimeAccountDisableCapture.capture(principal, original, authorization,
                originalCommand, originalObservation, event, targetBefore)
                ?.let { RuntimeSessionRevocationHistoryCapture.disable(it) }
        } else if (originalCommand != null && originalObservation != null && event.kind == LocalAuthEventKind.CREDENTIAL_RESET &&
            archiveBefore == null && targetAfter.state.enabled) {
            historyRevocation = RuntimeCredentialResetCapture.capture(principal, original, authorization,
                originalCommand, originalObservation, event, targetBefore)
                ?.let { RuntimeSessionRevocationHistoryCapture.reset(it) }
        }
    }

    /** Store attaches the frame before this first suspension and before any effect. */
    suspend fun initialize(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.NEW)
        phase = Phase.INITIALIZING
        owner = transaction
        // The signed runtime history must identify this actual administrative action, not
        // inherit the generic PROVISIONING purpose or a newly allocated frame request ID.
        transaction.historyPurpose(RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION, principal.actorId, event.eventId, event.contentDigest)
        val actual = transaction.readAuthorizationFacts(principal) ?: transaction.refuse()
        if (!original.sameDependencies(actual)) transaction.refuse()
        val target = transaction.readProtectedAccount(principal.scope, targetId) ?: transaction.refuse()
        if (accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeAccount(target)) != beforeDigest) transaction.refuse()
        checkResolutionDependencies(transaction)
        checkArchive(transaction)
        checkRetainedIssuances(transaction)
        historyRevocation?.let(transaction::beginHistorySessionRevocation)
        phase = Phase.BODY
    }

    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.BODY)
        if (transaction !== owner) transaction.refuse()
        phase = Phase.PREPARING
        val actual = transaction.readAuthorizationFacts(principal) ?: transaction.refuse()
        if (expectedOperator?.sameDependencies(actual) != true) transaction.refuse()
        val target = transaction.readProtectedAccount(principal.scope, targetId) ?: transaction.refuse()
        if (accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeAccount(target)) != afterDigest) transaction.refuse()
        checkResolutionDependencies(transaction)
        checkArchive(transaction)
        checkRetainedIssuances(transaction)
        val recorded = transaction.readAuthEvent(principal.scope, event.eventId) ?: transaction.refuse()
        if (accountAdministrationDigest(LocalAuthenticationCodec.encodeEvent(recorded)) != eventDigest) transaction.refuse()
        transaction.ensureAuthorizationHealthy()
        historyRevocation?.let(transaction::completeHistorySessionRevocation)
        phase = Phase.PREPARED
    }

    /** No more application-owned suspension/provider/dependency work follows this sample. */
    fun finishNow(): Unit = guarded {
        requirePhase(Phase.PREPARED)
        if (!peer.isCurrent()) currentLocalAuthorizationDenied()
        val observed = source.observeCurrent(context)
        if (!eligible(observed)) currentLocalAuthorizationDenied()
        phase = Phase.FINISHED
        finished = true
    }

    /** Only after normal COMMIT, still inside the same sole outer serialized writer loan. */
    fun finishAfterCommit(callerContext: CoroutineContext): Boolean {
        callerContext.ensureActive()
        failure?.let { throw it }
        if (!finished || phase != Phase.CLOSED || !peer.isCurrent()) return false
        val observed = try { source.observeCurrent(callerContext) }
        catch (_: RoomCurrentLocalAuthorizationDenied) { return false }
        return eligible(observed)
    }

    fun close() {
        phase = Phase.CLOSED; owner = null; expectedOperator = null
        historyRevocation?.images?.clear(); historyRevocation = null
    }

    private suspend fun checkResolutionDependencies(transaction: RoomLocalSecurityTransaction) {
        targetPolicyDigest?.let { expected ->
            val actual = transaction.readPolicy(principal.scope, targetId) ?: transaction.refuse()
            if (accountAdministrationDigest(LocalEffectivePolicyCodec.encodePolicy(actual)) != expected) transaction.refuse()
        }
        targetConfigurationDigest?.let { expected ->
            val actual = transaction.readAuthConfiguration(principal.scope, targetId) ?: transaction.refuse()
            if (accountAdministrationDigest(LocalAuthenticationCodec.encodeConfiguration(actual)) != expected) transaction.refuse()
        }
    }

    private suspend fun checkArchive(transaction: RoomLocalSecurityTransaction) {
        archiveId?.let { id ->
            val retained = transaction.readSessionArchive(principal.scope, id) ?: transaction.refuse()
            if (accountAdministrationDigest(LocalAuthenticationCodec.encodeArchive(retained)) != archiveDigest) transaction.refuse()
        }
    }

    private suspend fun checkRetainedIssuances(transaction: RoomLocalSecurityTransaction) {
        val target = historyRevocation?.retainedTarget ?: return
        val access = transaction.readIssuance(principal.scope, target.session.sessionId)
        val refresh = transaction.readRefresh(principal.scope, target.session.sessionId)
        if (!target.matchesIssuances(access, refresh)) transaction.refuse()
    }

    private fun eligible(observed: RoomLocalAuthorizationObservation): Boolean = LocalAccessEligibility.matchesCurrentFacts(
        principal, original.issuance, original.account, original.credentialRevision, original.credentialPresent,
        original.admission, observed.issuerContextId, observed.moment)

    private fun projectedFacts(account: ProtectedLocalAccount): RoomLocalAuthorizationFacts = RoomLocalAuthorizationFacts(
        account.state, account.credentialRevision, account.credential != null, original.policy, original.admission, original.issuance,
        listOf(accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeAccount(account)),
            accountAdministrationDigest(LocalEffectivePolicyCodec.encodePolicy(original.policy)),
            accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeAdmission(original.admission)),
            accountAdministrationDigest(ProtectedLocalSecurityCodec.encodeIssuance(original.issuance))))

    private fun requirePhase(expected: Phase) { context.ensureActive(); failure?.let { throw it }; check(phase == expected) }
    private inline fun <T> guarded(block: () -> T): T = try { failure?.let { throw it }; block() }
    catch (caught: Exception) { if (failure == null) failure = caught; throw caught }
}

private fun accountAdministrationDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }

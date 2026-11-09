package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/**
 * Provisioned LOCAL authentication. Every success follows real protected Room COMMIT + outer
 * cleanup. Password work holds no writer. F35 unborrowed/non-reentrant entry is a composition
 * precondition; the grouped endpoint establishes its own request lineage, not this primitive.
 * There is no cloud, default identity, in-memory registry or expired-refresh fallback.
 */
internal class RoomLocalAuthenticationService(
    private val scope: BranchScope,
    private val store: RoomLocalSecurityStore,
    private val authorization: RoomCurrentLocalAuthorizationSource,
    private val peers: RoomLocalAccessPeerSource,
    private val passwords: LocalPasswordHasher,
    private val entropy: LocalAuthenticationEntropy,
    maximumConcurrentAuthentication: Int,
) : LocalAuthenticationService {
    private val slots = Semaphore(maximumConcurrentAuthentication)
    init { check(authorization.belongsTo(store)) }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = bounded(LocalAuthenticationResult.Unavailable, LocalAuthenticationResult.StorageUncertain, busy()) {
        if (password.size !in 4..1024) return@bounded refused()
        val ownedPassword = password.copyOf()
        var material: LocalAuthenticationTokenMaterial? = null
        try {
            val peer = capturePeer(connectionId) ?: return@bounded refused()
            val attemptId = freshAuthenticationId(entropy)
            val captured = writer {
                val configuration = store.transaction { transaction ->
                    val device = transaction.readAdmission(scope, peer.deviceId) ?: return@transaction null
                    if (!device.admitted || !peer.matchesAdmission(device) || !peer.isCurrent()) return@transaction null
                    transaction.resolveLogin(scope, login)
                } ?: return@writer null
                val throttle = RoomPersistentThrottleStore.capture(store, scope, configuration.actorId, peer.deviceId,
                    attemptId, configuration) ?: return@writer null
                val reservation = throttle.reserve(observe().moment)
                PasswordAttempt(configuration, throttle, reservation)
            } ?: return@bounded refused()
            val allowed = captured.reservation as? ThrottleReservation.Allowed
                ?: return@bounded if ((captured.reservation as ThrottleReservation.Denied).reason in setOf(
                    ThrottleDenial.DELAYED, ThrottleDenial.ATTEMPT_IN_PROGRESS)) {
                    LocalAuthenticationResult.Denied(LocalAuthenticationDenial.THROTTLED)
                } else LocalAuthenticationResult.Unavailable
            val verified = captured.throttle.verifyPassword(passwords, ownedPassword)
            if (verified !is PasswordVerificationResult.Match) {
                val outcome = if (verified is PasswordVerificationResult.Mismatch) CredentialAttemptOutcome.MISMATCH
                    else CredentialAttemptOutcome.VERIFIER_UNAVAILABLE
                val completed = writer { captured.throttle.complete(allowed.permit, outcome, observe().moment) }
                return@bounded if (completed is ThrottleCompletion.Persisted && outcome == CredentialAttemptOutcome.MISMATCH) refused()
                    else if (completed is ThrottleCompletion.Persisted && verified is PasswordVerificationResult.Unavailable &&
                        verified.reason == PasswordFailure.HASHER_BUSY) busy()
                    else if (completed is ThrottleCompletion.Persisted && verified is PasswordVerificationResult.Unavailable &&
                        verified.reason == PasswordFailure.INVALID_INPUT) refused()
                    else LocalAuthenticationResult.Unavailable
            }
            val generated = LocalAuthenticationTokenMaterial(entropy)
            material = generated
            val eventId = freshAuthenticationId(entropy)
            val committed = writer {
                val completion = captured.throttle.completeAndApply(allowed.permit, observe().moment) { transaction ->
                    val admission = transaction.readAdmission(scope, peer.deviceId) ?: transaction.refuse()
                    if (!admission.admitted || !peer.matchesAdmission(admission) || !peer.isCurrent()) transaction.refuse()
                    val policy = transaction.readPolicy(scope, captured.configuration.actorId) ?: transaction.refuse()
                    val result = issue(transaction, captured.configuration, policy, admission, generated,
                        explicitDeviceMove = explicitDeviceMove, previous = null, eventId = eventId)
                    if (result is AuthenticationCommit.Issued) {
                        transaction.historyPurpose(RuntimeWritePurposeV2.SESSION_ISSUE,result.value.access.actorId,eventId)
                        transaction.attachAuthenticationFrame(RoomLocalAuthenticationFrame.session(authorization,peer,currentCoroutineContext(),result.value))
                        transaction.ensureAuthorizationHealthy()
                        if (!peer.isCurrent()) transaction.refuse()
                        val final = observe()
                        if (!captured.throttle.attemptValidAt(final.moment) || !result.value.eligible(final)) transaction.refuse()
                    }
                    result
                }
                when (completion) {
                    is RoomThrottleApplication.Applied -> {
                        val value = completion.value
                        if (value is AuthenticationCommit.Issued && !finish(peer, value.value)) null else value
                    }
                    RoomThrottleApplication.Denied -> null
                }
            }
            when (committed) {
                is AuthenticationCommit.Issued -> publish(generated, committed.value)
                is AuthenticationCommit.Denied -> LocalAuthenticationResult.Denied(committed.reason)
                null -> LocalAuthenticationResult.Unavailable
            }
        } finally {
            ownedPassword.fill('\u0000')
            material?.close()
        }
    }

    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = bounded(LocalAuthenticationResult.Unavailable, LocalAuthenticationResult.StorageUncertain, busy()) {
        val bearer = credentials.bearer.useBytes(LocalAuthenticationBearer::parse) ?: return@bounded refused()
        var material: LocalAuthenticationTokenMaterial? = null
        try {
            if (!bearer.refresh) return@bounded refused()
            val peer = capturePeer(credentials.connectionId) ?: return@bounded refused()
            val generated = LocalAuthenticationTokenMaterial(entropy)
            material = generated
            val eventId = freshAuthenticationId(entropy)
            val committed = writer {
                val result = store.transaction { transaction ->
                    val old = readSession(transaction, bearer, peer) ?: return@transaction null
                    val previous = old.refresh ?: return@transaction null
                    if (!LocalRefreshEligibility.matchesCurrentFacts(previous, old.access, old.account, old.admission,
                            old.configuration, observe().publicValue())) return@transaction null
                    val next = issue(transaction, old.configuration, old.policy, old.admission, generated,
                        explicitDeviceMove = false, previous = previous, eventId = eventId)
                    if (next is AuthenticationCommit.Issued) {
                        transaction.ensureAuthorizationHealthy()
                        if (!peer.isCurrent() || !next.value.eligible(observe())) transaction.refuse()
                        transaction.historyPurpose(RuntimeWritePurposeV2.SESSION_REFRESH,next.value.access.actorId,eventId)
                        transaction.attachAuthenticationFrame(RoomLocalAuthenticationFrame.session(authorization,peer,currentCoroutineContext(),next.value))
                    }
                    next
                }
                if (result is AuthenticationCommit.Issued && !finish(peer, result.value)) null else result
            }
            when (committed) {
                is AuthenticationCommit.Issued -> publish(generated, committed.value)
                is AuthenticationCommit.Denied -> LocalAuthenticationResult.Denied(committed.reason)
                null -> refused()
            }
        } finally { bearer.close(); material?.close() }
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = bounded(LocalSessionViewResult.Unavailable, LocalSessionViewResult.StorageUncertain, LocalSessionViewResult.Busy) {
        val bearer = credentials.bearer.useBytes(LocalAuthenticationBearer::parse) ?: return@bounded LocalSessionViewResult.Denied
        try {
            if (bearer.refresh) return@bounded LocalSessionViewResult.Denied
            val peer = capturePeer(credentials.connectionId) ?: return@bounded LocalSessionViewResult.Denied
            val result = writer {
                val value = store.transaction { transaction ->
                    val current = readSession(transaction, bearer, peer) ?: return@transaction null
                    val issued = current.projection() ?: return@transaction null
                    transaction.ensureAuthorizationHealthy()
                    if (!peer.isCurrent() || !issued.eligible(observe())) return@transaction null
                    transaction.attachAuthenticationFrame(RoomLocalAuthenticationFrame.session(authorization,peer,currentCoroutineContext(),issued))
                    issued
                } ?: return@writer null
                if (!finish(peer, value)) return@writer null
                value.view()
            }
            if (result == null) LocalSessionViewResult.Denied else LocalSessionViewResult.Current(result)
        } finally { bearer.close() }
    }

    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = bounded(LocalSignOutResult.Unavailable, LocalSignOutResult.StorageUncertain, LocalSignOutResult.Busy) {
        val bearer = credentials.bearer.useBytes(LocalAuthenticationBearer::parse) ?: return@bounded LocalSignOutResult.Denied
        try {
            val peer = capturePeer(credentials.connectionId) ?: return@bounded LocalSignOutResult.Denied
            val eventId = freshAuthenticationId(entropy)
            val result = writer {
                val revoked = store.transaction { transaction ->
                    // Expired access/work/service contexts may ONLY revoke that original identity.
                    val access = transaction.readIssuance(scope, bearer.sessionId) ?: return@transaction false
                    val refresh = if (bearer.refresh) transaction.readRefresh(scope, bearer.sessionId) else null
                    if (!bearer.matches(access, refresh) || access.deviceId != peer.deviceId) return@transaction false
                    val device = transaction.readAdmission(scope, peer.deviceId) ?: return@transaction false
                    if (!device.admitted || !peer.matchesAdmission(device) || !peer.isCurrent()) return@transaction false
                    val account = transaction.readProtectedAccount(scope, access.actorId) ?: return@transaction false
                    val session = account.state.sessions.singleOrNull { it.sessionId == access.sessionId }
                        ?: transaction.readSessionArchive(scope, access.sessionId)?.session ?: return@transaction false
                    if (session.actorId != access.actorId || session.deviceId != peer.deviceId || session.generation != access.generation ||
                        session.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return@transaction false
                    if (session.status == SessionStatus.ACTIVE) {
                        val now = observe()
                        val history = transaction.beginHistoryAuthentication(scope, access.actorId, peer.deviceId, eventId,
                            RuntimeAuthenticationActionV1.LOGOUT, now, null, session.sessionId, false)
                        val transition = LocalSessions.revoke(account.state, session.sessionId) as? SessionTransition.Applied ?: transaction.refuse()
                        if (!transaction.replaceAccount(account.state.revision, transition.state)) transaction.refuse()
                        transaction.insertAuthEvent(LocalAuthEvent(scope, eventId, LocalAuthEventKind.LOGGED_OUT, access.actorId,
                            access.actorId, access.sessionId, transition.state.revision, now.moment, null))
                        transaction.completeHistoryAuthentication(history)
                    }
                    transaction.historyPurpose(RuntimeWritePurposeV2.SESSION_LOGOUT,access.actorId,eventId)
                    transaction.attachAuthenticationFrame(RoomLocalAuthenticationFrame.logout(authorization,peer,currentCoroutineContext(),access))
                    transaction.ensureAuthorizationHealthy()
                    if (!peer.isCurrent()) transaction.refuse()
                    observe()
                    true
                }
                if (revoked) {
                    if (!peer.isCurrent()) return@writer LocalSignOutResult.Unavailable
                    observe()
                }
                if (revoked) LocalSignOutResult.Revoked else LocalSignOutResult.Denied
            }
            result
        } finally { bearer.close() }
    }

    private suspend fun issue(transaction: RoomLocalSecurityTransaction, configuration: LocalAuthConfiguration,
        policy: ActivatedLocalPolicy, admission: LocalDeviceAdmission, material: LocalAuthenticationTokenMaterial,
        explicitDeviceMove: Boolean, previous: LocalRefreshIssuance?, eventId: DomainId): AuthenticationCommit {
        val now = observe()
        val history = transaction.beginHistoryAuthentication(scope, configuration.actorId, admission.deviceId, eventId,
            if (previous == null) RuntimeAuthenticationActionV1.SIGN_IN else RuntimeAuthenticationActionV1.REFRESH,
            now, material.sessionId, previous?.sessionId, explicitDeviceMove)
        if (previous == null) transaction.retireUnusableInteractiveSessions(scope, configuration.actorId, now)
        val state = transaction.archiveRevokedSessions(scope, configuration.actorId, now.moment)
        val transition = if (previous == null) LocalSessions.signIn(state, material.sessionId, admission.deviceId,
            policy.sessionPolicy, explicitDeviceMove) else LocalSessions.rotate(state, previous.sessionId, material.sessionId)
        if (transition is SessionTransition.Denied) {
            transaction.completeHistoryAuthentication(history, denial = transition.reason)
            return AuthenticationCommit.Denied(when (transition.reason) {
                SessionTransitionDenial.EXPLICIT_DEVICE_MOVE_REQUIRED -> LocalAuthenticationDenial.EXPLICIT_DEVICE_MOVE_REQUIRED
                SessionTransitionDenial.SESSION_LIMIT -> LocalAuthenticationDenial.SESSION_LIMIT
                else -> LocalAuthenticationDenial.AUTHENTICATION_REFUSED
            })
        }
        transition as SessionTransition.Applied
        val account = transaction.readProtectedAccount(scope, configuration.actorId) ?: transaction.refuse()
        if (account.credential == null) transaction.refuse()
        val session = checkNotNull(transition.newSession)
        val start = previous?.workStartedAt ?: now.moment
        val workMillis = previous?.workLifetimeMillis ?: configuration.tokens.workLifetimeMillis
        if (workMillis > Long.MAX_VALUE - start.elapsedMillis) transaction.refuse()
        val workEnd = start.elapsedMillis + workMillis
        if (start.bootId != now.moment.bootId || now.moment.elapsedMillis >= workEnd) transaction.refuse()
        val accessMillis = minOf(configuration.tokens.accessLifetimeMillis, workEnd - now.moment.elapsedMillis)
        val access = LocalAccessIssuance(scope, session.actorId, session.sessionId, session.deviceId, session.generation,
            account.credentialRevision, admission.revision, now.issuerContextId, now.moment, accessMillis, material.accessHash)
        val refresh = LocalRefreshIssuance(scope, session.actorId, session.sessionId, session.deviceId, session.generation,
            account.credentialRevision, admission.revision, configuration.revision, now.issuerContextId, now.moment,
            previous?.originalSessionId ?: session.sessionId, start, workMillis, material.refreshHash)
        if (!transaction.replaceAccount(state.revision, transition.state)) transaction.refuse()
        transaction.insertIssuance(access)
        transaction.insertRefresh(refresh)
        transaction.insertAuthEvent(LocalAuthEvent(scope, eventId,
            if (previous == null) LocalAuthEventKind.SIGNED_IN else LocalAuthEventKind.REFRESHED,
            session.actorId, session.actorId, session.sessionId, transition.state.revision, now.moment, null))
        transaction.completeHistoryAuthentication(history, access = access, refresh = refresh)
        return AuthenticationCommit.Issued(IssuedAuthentication(access, refresh, transition.state, account.credentialRevision,
            admission, configuration, policy))
    }

    private suspend fun readSession(transaction: RoomLocalSecurityTransaction, bearer: LocalAuthenticationBearer,
        peer: RoomLocalAccessPeerBinding): AuthenticationSession? {
        val access = transaction.readIssuance(scope, bearer.sessionId) ?: return null
        val refresh = transaction.readRefresh(scope, bearer.sessionId)
        if (access.deviceId != peer.deviceId || !bearer.matches(access, refresh)) return null
        val admission = transaction.readAdmission(scope, peer.deviceId) ?: return null
        if (!admission.admitted || !peer.matchesAdmission(admission) || !peer.isCurrent()) return null
        return AuthenticationSession(access, refresh,
            transaction.readProtectedAccount(scope, access.actorId) ?: return null, admission,
            transaction.readAuthConfiguration(scope, access.actorId) ?: return null,
            transaction.readPolicy(scope, access.actorId) ?: return null)
    }
    private suspend fun capturePeer(id: DomainId): RoomLocalAccessPeerBinding? = peers.capture(id)?.takeIf {
        it.scope == scope && it.connectionId == id && it.isCurrent()
    }
    private suspend fun observe(): RoomLocalAuthorizationObservation = authorization.observeCurrent(currentCoroutineContext())
    private suspend fun finish(peer: RoomLocalAccessPeerBinding, issued: IssuedAuthentication): Boolean {
        currentCoroutineContext().ensureActive()
        // Ordinary disconnection/expiry withholds the result, but does not poison healthy Room.
        return peer.isCurrent() && issued.eligible(observe())
    }
    private suspend fun <T> writer(block: suspend () -> T): T = localSecurityLane(store.database) { block() }
    private suspend fun publish(material: LocalAuthenticationTokenMaterial, issued: IssuedAuthentication): LocalAuthenticationResult {
        currentCoroutineContext().ensureActive()
        val tokens = material.publish(issued.view())
        try { currentCoroutineContext().ensureActive() }
        catch (failure: Throwable) { tokens.destroy(); throw failure }
        return LocalAuthenticationResult.Authenticated(tokens)
    }
    private suspend fun <T> bounded(unavailable: T, uncertain: T, busy: T, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return busy
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); uncertain }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); unavailable }
        finally { slots.release() }
    }
}

private fun refused(): LocalAuthenticationResult = LocalAuthenticationResult.Denied(LocalAuthenticationDenial.AUTHENTICATION_REFUSED)
private fun busy(): LocalAuthenticationResult = LocalAuthenticationResult.Denied(LocalAuthenticationDenial.SERVICE_BUSY)
private class PasswordAttempt(val configuration: LocalAuthConfiguration, val throttle: RoomPersistentThrottleStore, val reservation: ThrottleReservation)
private sealed interface AuthenticationCommit {
    class Issued(val value: IssuedAuthentication) : AuthenticationCommit
    class Denied(val reason: LocalAuthenticationDenial) : AuthenticationCommit
}
private class AuthenticationSession(val access: LocalAccessIssuance, val refresh: LocalRefreshIssuance?, val account: ProtectedLocalAccount,
    val admission: LocalDeviceAdmission, val configuration: LocalAuthConfiguration, val policy: ActivatedLocalPolicy) {
    fun projection(): IssuedAuthentication? {
        if (account.credential == null) return null
        val refresh = refresh ?: return null
        if (refresh.sessionId != access.sessionId || refresh.actorId != access.actorId || refresh.scope != access.scope ||
            refresh.deviceId != access.deviceId || refresh.generation != access.generation || refresh.issuedAt != access.issuedAt ||
            refresh.issuerContextId != access.issuerContextId || refresh.credentialRevisionAtIssue != access.credentialRevisionAtIssue ||
            refresh.admissionRevisionAtIssue != access.admissionRevisionAtIssue || refresh.configurationRevisionAtIssue != configuration.revision) return null
        return IssuedAuthentication(access, refresh, account.state, account.credentialRevision, admission, configuration, policy)
    }
}
/** No password/verifier escapes the transaction in a successful projection. */
internal class IssuedAuthentication(val access: LocalAccessIssuance, val refresh: LocalRefreshIssuance,
    val account: LocalAccountSessionState, val credentialRevision: com.ukkera.pos.types.Revision,
    val admission: LocalDeviceAdmission, val configuration: LocalAuthConfiguration, val policy: ActivatedLocalPolicy) {
    fun eligible(observed: RoomLocalAuthorizationObservation): Boolean = LocalAccessEligibility.matchesCurrentFacts(
        VerifiedLocalIdentity(access.actorId, access.scope, access.deviceId, access.sessionId, access.generation,
            access.admissionRevisionAtIssue, AuthenticationPurpose.INTERACTIVE), access, account, credentialRevision, true,
        admission, observed.issuerContextId, observed.moment) && observed.moment.elapsedMillis < refresh.expiresAtElapsedMillis
    fun view(): LocalSessionView = LocalSessionView(access.actorId, access.scope, access.deviceId, access.sessionId, access.generation,
        configuration.login, policy, MonotonicMoment(access.issuedAt.bootId, access.expiresAtElapsedMillis),
        MonotonicMoment(refresh.workStartedAt.bootId, refresh.expiresAtElapsedMillis))
}

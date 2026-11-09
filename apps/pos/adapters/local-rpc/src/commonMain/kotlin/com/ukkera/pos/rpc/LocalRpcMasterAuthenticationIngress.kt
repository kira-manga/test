package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterRpcPeerRegistration
import com.ukkera.pos.database.LocalMasterRpcRegisteredPeer
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcAuthenticationContext
import dev.p2pkit.rpc.RpcAuthorizationContext
import dev.p2pkit.rpc.RpcAuthorizationScope
import dev.p2pkit.rpc.RpcCallContext
import dev.p2pkit.rpc.RpcHostConfiguration
import dev.p2pkit.rpc.RpcPhysicalSession
import dev.p2pkit.rpc.RpcPhysicalSessionClosure
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Eligibility only: the caller must still prove exact revocation and guard decoded retention. */
internal fun isAuthenticationCurrentReadReclaimable(
    expired: Boolean,
    scopeInactive: Boolean,
    privateRetentionCleared: Boolean,
    started: Boolean,
    handlerFinished: Boolean,
    nativeClean: Boolean,
    authorityChecks: Int,
    authorityUncertain: Boolean,
): Boolean {
    if (authorityChecks < 0) return false
    return expired && scopeInactive && privateRetentionCleared && authorityChecks == 0 && !authorityUncertain &&
        (!started || (handlerFinished && nativeClean))
}

/**
 * Actual original-Host authentication exchanges. An admitted device scope is not an
 * employee login and is never accepted by a business procedure or identity binding.
 * Issuance starts only in an SDK-owned handler, after its dedup admission. A result
 * adds its real employee identity to this exact exchange's revocation census without
 * changing the SDK scope's device-only metadata.
 */
internal class LocalRpcMasterAuthenticationIngress(
    private val registration: LocalMasterRpcPeerRegistration,
    cleanupScope: CoroutineScope,
    private val parent: Job,
) {
    private class ResultAuthority(val original: LocalEmployeePrincipal, val access: OpaqueCredential)

    private class DecodedRequest(val request: LocalRpcAuthenticationRequest,
        val procedureVersion: Int, val created: TimeMark)
    // Referential CAS state: SDK deserialization is synchronous and cannot borrow a suspend gate.
    private class DecodedRequests(val closed: Boolean = false, val owned: List<DecodedRequest> = emptyList(),
        val reclaiming: Entry? = null)

    private class Entry(
        val physical: RpcPhysicalSession,
        val procedureVersion: Int,
        val exchangeId: String,
        val action: LocalRpcAuthenticationAction,
        val digest: PayloadDigest,
        val peer: LocalMasterRpcRegisteredPeer,
        val scope: RpcAuthorizationScope,
    ) {
        val gate = Mutex()
        var started = false
        var expired = false
        var handlerFinished = false
        var nativeClean = false
        var authorityChecks = 0
        var authorityUncertain = false
        var authority: ResultAuthority? = null
        var response: LocalRpcAuthenticationResponse? = null
    }

    private val gate = Mutex()
    private val admissionGate = Mutex()
    private val retired = MutableStateFlow(false)
    private val entries = mutableListOf<Entry>()
    private val failedSessions = mutableListOf<RpcApplicationSession>()
    private val callbackSlots = Semaphore(MAXIMUM_CALLBACKS)
    private val decodedRequests = MutableStateFlow(DecodedRequests())
    private var callbacks = 0
    private val drained = CompletableDeferred<Unit>()
    // A transient private salt prevents retained request digests from being ordinary password
    // digests. It is neither a credential verifier nor part of the application wire protocol.
    private val digestSalt = DomainId.random().value.encodeToByteArray()
    private var cleanupFailure: Throwable? = null
    private val cleanupJob = cleanupScope.launch(start = CoroutineStart.LAZY) {
        try {
            while (isActive && !isRetired()) {
                delay(1000)
                admissionGate.withLock { discardExpiredEntries(); reapDecodedRequests() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            cleanupFailure = failure
            deny()
        }
    }

    init { cleanupJob.start() }

    fun install(configuration: RpcHostConfiguration) {
        install(configuration, authenticationExchangeProcedure)
        install(configuration, authenticationCurrentProcedure)
    }

    private fun install(configuration: RpcHostConfiguration,
        shared: RpcProcedure<LocalRpcAuthenticationRequest, LocalRpcAuthenticationResponse, LocalRpcAuthenticationError>) {
        // Preserve the shared key/schema. This Host-only wrapper owns the second decoded Q
        // even when SDK Execute authorization fails before our handler can receive it.
        val ownedProcedure = RpcProcedure(shared.name, shared.version,
            OwnedRequestSerializer(shared.request, shared.version), shared.response,
            shared.applicationError, shared.retrySafety,
            shared.requestLimitBytes, shared.responseLimitBytes, shared.errorLimitBytes)
        // Authentication and call contexts carry no version. The actual registered descriptor
        // supplies it, rather than caller data or an inferred SDK context field.
        val version = shared.version
        configuration.registerScopedWithPhysicalSession(ownedProcedure,
            authenticate = { context, request -> authenticate(version, context, request) },
            authorize = { context -> authorize(version, context) },
            handler = { context, request -> exchange(version, context, request) })
    }

    /** The composite original Host owns this denial; cleanup survives its parent cancellation. */
    fun deny() {
        retired.value = true
        closeDecodedRetention()
        registration.retireAll()
    }

    private fun isRetired(): Boolean = retired.value || !parent.isActive

    suspend fun closeOwned() = withContext(NonCancellable) {
        deny()
        registration.awaitSessionRevocation()
        cleanupJob.cancelAndJoin()
        gate.withLock { if (callbacks == 0) drained.complete(Unit) }
        drained.await()
        admissionGate.withLock {
            val owned = gate.withLock { entries.toList() }
            val failed = gate.withLock { failedSessions.toList() }
            var firstFailure: Throwable? = null
            for (entry in owned) {
                try { retireEntry(entry) }
                catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
            }
            for (session in failed) {
                try { registration.revokeApplicationSession(session) }
                catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
            }
            (firstFailure ?: cleanupFailure)?.let { throw it }
            gate.withLock { entries.clear(); failedSessions.clear() }
            destroyDecodedRequests()
            digestSalt.fill(0)
        }
    }

    private suspend fun <T> withCallback(refuse: () -> T, cleanup: suspend () -> Unit = {}, work: suspend () -> T): T {
        var acquired = false
        var accepted = false
        try {
            acquired = callbackSlots.tryAcquire()
            if (!acquired) return refuse()
            accepted = gate.withLock {
                if (isRetired()) false else { callbacks++; true }
            }
            return if (accepted) work() else refuse()
        } finally {
            withContext(NonCancellable) {
                try { cleanup() }
                catch (failure: Throwable) {
                    cleanupFailure = failure
                    deny()
                    throw failure
                } finally {
                    if (accepted) gate.withLock {
                        callbacks--
                        if (retired.value && callbacks == 0) drained.complete(Unit)
                    }
                    if (acquired) callbackSlots.release()
                }
            }
        }
    }

    private suspend fun authenticate(procedureVersion: Int, context: RpcAuthenticationContext,
        request: LocalRpcAuthenticationRequest): RpcAuthorizationScope? {
        if (!request.claimForCallback()) return null
        return withCallback({ null }, cleanup = {
            request.destroyAndAwaitCleanup()
            reapDecodedRequests()
        }) {
            admissionGate.withLock { authenticateOwned(procedureVersion, context, request) }
        }
    }

    private suspend fun authenticateOwned(procedureVersion: Int, context: RpcAuthenticationContext,
        request: LocalRpcAuthenticationRequest): RpcAuthorizationScope? {
        var unretained: RpcApplicationSession? = null
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || !request.validateShape() || context.peer != context.physicalSession.peer) return null
            if (procedureVersion == authenticationCurrentProcedure.version &&
                request.action != LocalRpcAuthenticationAction.CURRENT) return null
            discardExpiredEntries()
            val digest = requestDigest(request)
            val epoch = registration.captureSessionEpoch() ?: return null
            val peer = registration.register(context.physicalSession) ?: return null
            currentCoroutineContext().ensureActive()
            if (isRetired() || !peer.isCurrent()) return null
            val original = gate.withLock {
                entries.firstOrNull { it.physical === context.physicalSession &&
                    it.procedureVersion == procedureVersion && it.exchangeId == request.exchangeId }
            }
            if (original != null) {
                if (original.action != request.action || original.digest != digest || original.peer !== peer ||
                    original.gate.withLock { original.expired } || !original.scope.session.isActive) return null
                if (!registration.retainAuthenticationExchangeSession(epoch, original.scope.session, peer)) return null
                return original.scope.takeIf { !isRetired() && peer.isCurrent() && it.session.isActive }
            }
            // Versions have separate bounded original namespaces. V1 polling cannot consume
            // its non-read quota or v2 renewal capacity; no live original is evicted.
            if (gate.withLock {
                failedSessions.isNotEmpty() || if (request.action == LocalRpcAuthenticationAction.CURRENT) {
                    entries.count { it.procedureVersion == procedureVersion &&
                        it.action == LocalRpcAuthenticationAction.CURRENT } >= MAXIMUM_CURRENT_EXCHANGES
                } else entries.count { it.procedureVersion == procedureVersion &&
                    it.action != LocalRpcAuthenticationAction.CURRENT } >= MAXIMUM_ISSUING_EXCHANGES
            }) return null
            val fingerprint = context.peer.fingerprint ?: return null
            val session = RpcApplicationSession(fingerprint, DEVICE_EXCHANGE_PRINCIPAL,
                request.exchangeId, EXCHANGE_LIFETIME_SECONDS.seconds)
            unretained = session
            val scope = RpcAuthorizationScope(session, RESOURCE)
            if (!registration.retainAuthenticationExchangeSession(epoch, session, peer)) return null
            val entry = Entry(context.physicalSession, procedureVersion, request.exchangeId, request.action, digest, peer, scope)
            val published = gate.withLock {
                if (isRetired() || !peer.isCurrent() || !session.isActive) false
                else { entries.add(entry); true }
            }
            if (!published) return null
            unretained = null
            return scope
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return null
        } finally {
            withContext(NonCancellable) {
                unretained?.let { session ->
                    try { registration.revokeApplicationSession(session) }
                    catch (failure: Throwable) {
                        gate.withLock { failedSessions.add(session) }
                        deny()
                        throw failure
                    }
                }
            }
        }
    }

    private suspend fun authorize(procedureVersion: Int, context: RpcAuthorizationContext): Boolean =
        withCallback({ false }) {
            if (isRetired() || context.procedureName != authenticationExchangeProcedure.name ||
                context.procedureVersion != procedureVersion || context.scope.resource != RESOURCE) {
                return@withCallback false
            }
            val entry = gate.withLock { entries.firstOrNull {
                it.procedureVersion == procedureVersion && it.scope.session === context.scope.session
            } }
                ?: return@withCallback false
            if (procedureVersion == authenticationCurrentProcedure.version && context.scope !== entry.scope)
                return@withCallback false
            if (context.originalPhysicalSession !== entry.physical || context.physicalSession !== entry.physical ||
                context.peer != entry.physical.peer || !entry.peer.isCurrent()) return@withCallback false
            var disposable: OpaqueCredential? = null
            var trackedAuthority = false
            var authoritySettled = false
            try {
                val authority = entry.gate.withLock {
                    if (entry.expired || !entry.scope.session.isActive) return@withCallback false
                    entry.authority?.also {
                        disposable = it.access.useBytes(::OpaqueCredential)
                        if (procedureVersion == authenticationCurrentProcedure.version) {
                            entry.authorityChecks++
                            trackedAuthority = true
                        }
                    }
                }
                if (authority != null) {
                    val bearer = disposable ?: return@withCallback false
                    val current = authenticateDisposable(entry.peer, bearer)
                    authoritySettled = true
                    if (current == null) return@withCallback false
                    if (!sameOriginal(authority.original, current)) return@withCallback false
                }
                currentCoroutineContext().ensureActive()
                !isRetired() && entry.peer.isCurrent() && entry.gate.withLock {
                    !entry.expired && entry.scope.session.isActive && entry.authority === authority
                }
            } finally {
                disposable?.destroy()
                if (trackedAuthority) withContext(NonCancellable) {
                    entry.gate.withLock {
                        entry.authorityChecks--
                        if (!authoritySettled) entry.authorityUncertain = true
                    }
                }
            }
        }

    private suspend fun exchange(procedureVersion: Int, context: RpcCallContext,
        request: LocalRpcAuthenticationRequest): RpcReply<LocalRpcAuthenticationResponse, LocalRpcAuthenticationError> {
        if (!request.claimForCallback()) return unavailable()
        var owned: Entry? = null
        return withCallback(::unavailable, cleanup = {
            request.destroyAndAwaitCleanup()
            reapDecodedRequests()
            // Only the callback that claimed started may finish that original. This marker
            // follows completed bearer/request cleanup and precedes callback drain.
            // Completion on cancellation is not nativeClean.
            owned?.takeIf { it.procedureVersion == authenticationCurrentProcedure.version }?.let { entry ->
                entry.gate.withLock { entry.handlerFinished = true }
            }
        }) {
            exchangeOwned(procedureVersion, context, request) { owned = it }
        }
    }

    private suspend fun exchangeOwned(procedureVersion: Int, context: RpcCallContext,
        request: LocalRpcAuthenticationRequest, onStarted: (Entry) -> Unit):
        RpcReply<LocalRpcAuthenticationResponse, LocalRpcAuthenticationError> {
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || !request.validateShape()) return unavailable()
            if (procedureVersion == authenticationCurrentProcedure.version &&
                request.action != LocalRpcAuthenticationAction.CURRENT) return unavailable()
            val entry = gate.withLock { entries.firstOrNull {
                it.procedureVersion == procedureVersion && it.scope.session === context.authorizationScope?.session
            } }
                ?: return unavailable()
            if (procedureVersion == authenticationCurrentProcedure.version && context.authorizationScope !== entry.scope)
                return unavailable()
            if (context.physicalSession !== entry.physical || context.peer != entry.physical.peer ||
                request.exchangeId != entry.exchangeId || request.action != entry.action ||
                requestDigest(request) != entry.digest || !entry.peer.isCurrent()) return unavailable()
            // The marker never resets, including cancellation, failed serialization and expiry.
            // V1 tombstones survive until actual physical Closed. Only fully reclaimed v2
            // reads may mint a later fresh scope; no callback can restart this exact entry.
            val started = entry.gate.withLock {
                if (entry.started || entry.expired || !entry.scope.session.isActive || isRetired()) false
                else { entry.started = true; onStarted(entry); true }
            }
            if (!started) return unavailable()
            currentCoroutineContext().ensureActive()
            return when (request.action) {
                LocalRpcAuthenticationAction.LOGIN -> {
                    val result = registration.client.signIn(entry.peer.nativePeer.connectionId,
                        LocalLoginIdentifier(requireNotNull(request.login)), requireNotNull(request.password), request.explicitDeviceMove)
                    authenticationResult(entry, request, result)
                }
                LocalRpcAuthenticationAction.REFRESH -> withRequestBearer(entry, request) { credentials ->
                    authenticationResult(entry, request, registration.client.refresh(credentials))
                }
                LocalRpcAuthenticationAction.CURRENT -> {
                    var cleanResult = false
                    val reply = withRequestBearer(entry, request) { credentials ->
                        val result = if (procedureVersion == authenticationCurrentProcedure.version)
                            registration.currentAfterExecutionSettles(credentials)
                        else registration.client.current(credentials)
                        cleanResult = result is LocalSessionViewResult.Current || result is LocalSessionViewResult.Denied ||
                            result is LocalSessionViewResult.Busy
                        if (result is LocalSessionViewResult.Current) {
                            var transferred = false
                            try {
                                if (!attachResult(entry, credentials.bearer, result.view)) return@withRequestBearer unavailable()
                                val response = publish(entry, LocalRpcAuthenticationResponse.capture(request.action, result))
                                transferred = response is RpcReply.Success
                                response
                            } finally { if (!transferred) retireEntry(entry) }
                        } else publish(entry, LocalRpcAuthenticationResponse.capture(request.action, result))
                    }
                    // CurrentWork cleanup alone does not settle the later principal read.
                    // A clean witness follows NORMAL attachment/publication and bearer cleanup;
                    // cancelled, failed or early-unavailable paths can never reach this marker.
                    if (procedureVersion == authenticationCurrentProcedure.version && cleanResult && reply is RpcReply.Success) {
                        currentCoroutineContext().ensureActive()
                        entry.gate.withLock { entry.nativeClean = true }
                    }
                    reply
                }
                LocalRpcAuthenticationAction.LOGOUT -> withRequestBearer(entry, request) { credentials ->
                    publish(entry, LocalRpcAuthenticationResponse.capture(request.action, registration.client.logout(credentials)))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return unavailable()
        }
    }

    private suspend fun authenticationResult(entry: Entry, request: LocalRpcAuthenticationRequest,
        result: LocalAuthenticationResult): RpcReply<LocalRpcAuthenticationResponse, LocalRpcAuthenticationError> {
        if (result !is LocalAuthenticationResult.Authenticated) {
            return publish(entry, LocalRpcAuthenticationResponse.capture(request.action, result))
        }
        var transferred = false
        try {
            val view = result.tokens.view
            if (request.action == LocalRpcAuthenticationAction.LOGIN && view.login.value != request.login) return unavailable()
            if (!attachResult(entry, result.tokens.access, view)) return unavailable()
            val reply = publish(entry, LocalRpcAuthenticationResponse.capture(request.action, result))
            transferred = reply is RpcReply.Success
            return reply
        } finally {
            result.tokens.destroy()
            if (!transferred) retireEntry(entry)
        }
    }

    /** Copy and freshly verify only after the durable service result has settled. */
    private suspend fun attachResult(entry: Entry, access: OpaqueCredential, view: LocalSessionView): Boolean {
        var retained: OpaqueCredential? = access.useBytes(::OpaqueCredential)
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || !entry.peer.isCurrent() || !entry.scope.session.isActive) return false
            val epoch = registration.captureSessionEpoch() ?: return false
            val bearer = retained ?: return false
            val principal = authenticateDisposable(entry.peer, bearer) ?: return false
            currentCoroutineContext().ensureActive()
            if (principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE || principal.scope != view.scope ||
                principal.scope != entry.peer.nativePeer.scope || principal.deviceId != view.deviceId ||
                principal.deviceId != entry.peer.nativePeer.deviceId || principal.actorId != view.actorId ||
                principal.sessionId != view.sessionId || principal.sessionGeneration != view.generation) return false
            if (!registration.attachAuthenticationExchangeResult(epoch, entry.scope.session, entry.peer, principal)) return false
            val published = entry.gate.withLock {
                if (isRetired() || entry.expired || !entry.scope.session.isActive || entry.authority != null || !entry.peer.isCurrent()) false
                else {
                    entry.authority = ResultAuthority(principal, bearer)
                    retained = null
                    true
                }
            }
            currentCoroutineContext().ensureActive()
            return published
        } finally { retained?.destroy() }
    }

    private suspend fun authenticateDisposable(peer: LocalMasterRpcRegisteredPeer,
        bearer: OpaqueCredential): LocalEmployeePrincipal? {
        val copy = bearer.useBytes(::OpaqueCredential)
        return try { registration.principals.authenticateLocal(LocalGatewayCredentials(peer.nativePeer.connectionId, copy)) }
        finally { copy.destroy() }
    }

    private suspend fun <T> withRequestBearer(entry: Entry, request: LocalRpcAuthenticationRequest,
        work: suspend (LocalGatewayCredentials) -> T): T {
        val bearer = OpaqueCredential(requireNotNull(request.bearer))
        return try { work(LocalGatewayCredentials(entry.peer.nativePeer.connectionId, bearer)) }
        finally { bearer.destroy() }
    }

    private suspend fun publish(entry: Entry, response: LocalRpcAuthenticationResponse):
        RpcReply<LocalRpcAuthenticationResponse, LocalRpcAuthenticationError> {
        var retained = false
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || !entry.peer.isCurrent()) return unavailable()
            retained = entry.gate.withLock {
                if (entry.expired || !entry.scope.session.isActive || entry.response != null || isRetired()) false
                else { entry.response = response; true }
            }
            return if (retained) RpcReply.Success(response) else unavailable()
        } finally { if (!retained) response.destroy() }
    }

    private suspend fun retireEntry(entry: Entry) = withContext(NonCancellable) {
        entry.gate.withLock {
            entry.expired = true
            entry.authority?.access?.destroy()
            entry.response?.destroy()
            if (entry.procedureVersion == authenticationCurrentProcedure.version) {
                entry.authority = null
                entry.response = null
            }
        }
        try { registration.revokeApplicationSession(entry.scope.session) }
        catch (failure: Throwable) {
            if (entry.procedureVersion == authenticationCurrentProcedure.version) {
                cleanupFailure = failure
                deny()
            }
            throw failure
        }
    }

    private suspend fun discardExpiredEntries() {
        val owned = gate.withLock { entries.toList() }
        for (entry in owned) {
            if (!entry.scope.session.isActive || !entry.physical.isConnected || !entry.peer.isCurrent()) {
                retireEntry(entry)
                // The successful revoke above precedes v2 release. Timeout/Closed hints never
                // settle native work; unavailable/uncertain completed handlers stay retained.
                val renewable = entry.procedureVersion == authenticationCurrentProcedure.version && entry.gate.withLock {
                    isAuthenticationCurrentReadReclaimable(
                        expired = entry.expired, scopeInactive = !entry.scope.session.isActive,
                        privateRetentionCleared = entry.authority == null && entry.response == null,
                        started = entry.started, handlerFinished = entry.handlerFinished,
                        nativeClean = entry.nativeClean, authorityChecks = entry.authorityChecks,
                        authorityUncertain = entry.authorityUncertain,
                    )
                }
                // V1 every-original-once semantics remain physical-Closed-only, including CURRENT.
                val closedOriginal = entry.procedureVersion == authenticationExchangeProcedure.version &&
                    entry.physical.closure.value == RpcPhysicalSessionClosure.Closed
                if (closedOriginal) gate.withLock { entries.remove(entry) }
                else if (renewable && acquireDecodedReclamation(entry)) {
                    try { gate.withLock { entries.remove(entry) } }
                    finally {
                        if (!releaseDecodedReclamation(entry)) {
                            val failure = IllegalStateException("Authentication read retention release is unresolved")
                            cleanupFailure = failure
                            deny()
                            throw failure
                        }
                    }
                }
            }
        }
    }

    /** One short CAS reservation makes absence of matching Q and entry removal a single cut. */
    private fun acquireDecodedReclamation(entry: Entry): Boolean {
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val before = decodedRequests.value
            if (before.closed || isRetired() || before.reclaiming != null || before.owned.any {
                it.procedureVersion == entry.procedureVersion && it.request.exchangeId == entry.exchangeId &&
                    !it.request.isDestroyed
            }) return false
            if (decodedRequests.compareAndSet(before, DecodedRequests(before.closed, before.owned, entry))) return true
        }
        // No reservation means no removal, even under simultaneous decoder/reaper activity.
        return false
    }

    private fun releaseDecodedReclamation(entry: Entry): Boolean {
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val before = decodedRequests.value
            if (before.reclaiming !== entry) return false
            if (decodedRequests.compareAndSet(before, DecodedRequests(before.closed, before.owned))) return true
        }
        return false
    }

    private inner class OwnedRequestSerializer(
        private val delegate: KSerializer<LocalRpcAuthenticationRequest>,
        private val procedureVersion: Int,
    ) : KSerializer<LocalRpcAuthenticationRequest> {
        override val descriptor: SerialDescriptor get() = delegate.descriptor
        override fun serialize(encoder: Encoder, value: LocalRpcAuthenticationRequest) {
            delegate.serialize(encoder, value)
        }
        override fun deserialize(decoder: Decoder): LocalRpcAuthenticationRequest {
            val request = delegate.deserialize(decoder)
            if (!retainDecodedRequest(request, procedureVersion)) {
                request.destroy()
                throw SerializationException("Authentication request ownership is unavailable")
            }
            return request
        }
    }

    private fun retainDecodedRequest(request: LocalRpcAuthenticationRequest, procedureVersion: Int): Boolean {
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val before = decodedRequests.value
            if (before.closed || isRetired()) return false
            val reclaiming = before.reclaiming
            if (reclaiming != null && reclaiming.procedureVersion == procedureVersion &&
                reclaiming.exchangeId == request.exchangeId) return false
            val owned = before.owned.filterNot { it.request.isDestroyed }
            if (owned.size >= MAXIMUM_DECODED_REQUESTS) return false
            val after = DecodedRequests(false,
                owned + DecodedRequest(request, procedureVersion, TimeSource.Monotonic.markNow()), before.reclaiming)
            if (decodedRequests.compareAndSet(before, after)) {
                // A simultaneous close retains this exact object for cleanup, but it may not
                // publish a live decoded secret after the synchronous denial cut.
                if (isRetired() || decodedRequests.value.closed) { request.destroy(); return false }
                return true
            }
        }
        return false
    }

    private fun closeDecodedRetention(): DecodedRequests? {
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val before = decodedRequests.value
            if (before.closed) return before
            val after = DecodedRequests(true, before.owned, before.reclaiming)
            if (decodedRequests.compareAndSet(before, after)) return after
        }
        // retired was set first; all bounded retention attempts also consult that irreversible gate.
        return null
    }

    private fun reapDecodedRequests() {
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val before = decodedRequests.value
            for (owned in before.owned) {
                if (!owned.request.isDestroyed && owned.created.elapsedNow() >= EXCHANGE_LIFETIME_SECONDS.seconds) {
                    // Resource cleanup only: a full request lifetime measured from decoding.
                    // An entered callback claims before suspension, including before the
                    // endpoint can copy inputs. Expiry may clear only never-entered Q.
                    owned.request.destroyIfUnclaimed()
                }
            }
            val retained = before.owned.filterNot { it.request.isDestroyed }
            if (retained.size == before.owned.size ||
                decodedRequests.compareAndSet(before, DecodedRequests(before.closed, retained, before.reclaiming))) return
        }
    }

    private suspend fun destroyDecodedRequests() {
        try {
            val before = closeDecodedRetention()
                ?: throw IllegalStateException("Authentication request cleanup cut is unresolved")
            for (owned in before.owned) {
                // Accepted work is drained. Refused/pre-admission claim owners still perform
                // their own cleanup; never force-clear their claimed or already-wiping Q.
                owned.request.destroyIfUnclaimed()
                owned.request.awaitCleanup()
            }
            // The closed CAS cut forbids new retained Q; every exact snapshot input is clean.
            decodedRequests.value = DecodedRequests(closed = true)
        } catch (failure: Throwable) {
            cleanupFailure = failure
            deny()
            // Keep the census until all cleanup is positively complete.
            throw failure
        }
    }

    /** Complete semantic request binding; no password String is created or retained here. */
    private fun requestDigest(request: LocalRpcAuthenticationRequest): PayloadDigest {
        val parts = mutableListOf<ByteArray>()
        var joined: ByteArray? = null
        try {
            parts += digestSalt.copyOf()
            parts += request.exchangeId.encodeToByteArray()
            parts += request.action.name.encodeToByteArray()
            parts += byteArrayOf(if (request.explicitDeviceMove) 1 else 0)
            parts += request.login?.encodeToByteArray(throwOnInvalidSequence = true) ?: byteArrayOf()
            parts += request.password?.let { chars ->
                ByteArray(chars.size * 2).also { bytes ->
                    chars.forEachIndexed { index, char ->
                        bytes[index * 2] = (char.code ushr 8).toByte()
                        bytes[index * 2 + 1] = char.code.toByte()
                    }
                }
            } ?: byteArrayOf()
            parts += request.bearer?.copyOf() ?: byteArrayOf()
            val bytes = ByteArray(parts.sumOf { it.size + 4 })
            joined = bytes
            var at = 0
            for (part in parts) {
                for (shift in 24 downTo 0 step 8) bytes[at++] = (part.size ushr shift).toByte()
                part.copyInto(bytes, at)
                at += part.size
            }
            return sha256(bytes)
        } finally { joined?.fill(0); parts.forEach { it.fill(0) } }
    }

    private fun unavailable(): RpcReply<LocalRpcAuthenticationResponse, LocalRpcAuthenticationError> =
        RpcReply.ApplicationError(LocalRpcAuthenticationError.UNAVAILABLE)

    private fun sameOriginal(original: LocalEmployeePrincipal, current: LocalEmployeePrincipal): Boolean =
        current.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            current.authenticationPurpose == original.authenticationPurpose && current.scope == original.scope &&
            current.actorId == original.actorId && current.deviceId == original.deviceId &&
            current.sessionId == original.sessionId && current.sessionGeneration == original.sessionGeneration &&
            current.admissionRevision == original.admissionRevision

    private companion object {
        const val RESOURCE = "pos.auth.exchange"
        const val DEVICE_EXCHANGE_PRINCIPAL = "pos.auth.exchange.device"
        const val MAXIMUM_CALLBACKS = 128
        const val MAXIMUM_ISSUING_EXCHANGES = 128
        const val MAXIMUM_CURRENT_EXCHANGES = 128
        const val MAXIMUM_DECODED_REQUESTS = 256
        const val MAXIMUM_CAS_ATTEMPTS = 128
        const val EXCHANGE_LIFETIME_SECONDS = 120
    }
}

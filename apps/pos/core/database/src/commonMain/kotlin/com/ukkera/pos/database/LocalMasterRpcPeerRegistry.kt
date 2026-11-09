package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalAuthenticationEntropy
import com.ukkera.pos.security.LocalAuthenticationEnvironment
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcPhysicalSession
import dev.p2pkit.rpc.RpcPhysicalSessionClosure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Service-pushed registration for one retained original Host. Only its private, actual Host
 * callback retains this object; neither original-client nor transport APIs return it.
 * Retention is not physical Host-origin proof. The callback must supply its exact SDK-owned
 * physical session. Protected employee/device/policy checks still use this same Master's sources.
 */
public class LocalMasterRpcPeerRegistration internal constructor(
    private val registry: LocalMasterRpcPeerRegistry,
    internal val lifetime: LocalMasterRpcPeerRegistrationLifetime,
    internal val trust: LocalMasterRpcTrust,
    internal val appId: AppId,
    public val client: LocalMasterClient,
    public val principals: TrustedPrincipalFactory,
) {
    /** No supplied admission, connection ID, currentness predicate or latest-key lookup. */
    public suspend fun register(physicalSession: RpcPhysicalSession): LocalMasterRpcRegisteredPeer? =
        registry.register(this, physicalSession)

    /** SDK expiry hint derived from this Master's current session and original native clock. */
    public suspend fun remainingSessionLifetimeMillis(credentials: LocalGatewayCredentials): Long? =
        registry.remainingSessionLifetimeMillis(this, credentials)

    /** Renewable CURRENT may release retention only after the genuine endpoint work settles. */
    public suspend fun currentAfterExecutionSettles(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        registry.currentAfterExecutionSettles(this, credentials)

    /** Capture before registration/principal verification; a later protected write invalidates it. */
    public suspend fun captureSessionEpoch(): LocalMasterRpcSessionEpoch? = registry.captureSessionEpoch(this)

    /** Retain only the exact freshly verified SDK session under this registration's captured epoch. */
    public suspend fun retainApplicationSession(epoch: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession, original: LocalEmployeePrincipal): Boolean =
        registry.retainApplicationSession(this, epoch, session, original)

    /** Business output has a separate policy-fenced SDK session, never an identity-only scope. */
    public suspend fun retainResourceApplicationSession(epoch: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession, original: LocalEmployeePrincipal): Boolean =
        registry.retainResourceApplicationSession(this, epoch, session, original)

    /** Private Host exchange only: exact admitted device, not a supplied employee identity. */
    public suspend fun retainAuthenticationExchangeSession(epoch: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer): Boolean =
        registry.retainAuthenticationExchangeSession(this, epoch, session, peer)

    /** Post-issuance capture precedes fresh result verification; attachment is irreversible. */
    public suspend fun attachAuthenticationExchangeResult(epoch: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer,
        original: LocalEmployeePrincipal): Boolean =
        registry.attachAuthenticationExchangeResult(this, epoch, session, peer, original)

    /** Await exact SDK revocation before releasing an expired login's private binding. */
    public suspend fun revokeApplicationSession(session: RpcApplicationSession): Unit =
        registry.revokeApplicationSession(this, session)

    /** Rechecked at every business/control/output boundary by the actual protected endpoint. */
    public suspend fun authorizeBusiness(credentials: LocalGatewayCredentials,
        access: LocalMasterRpcBusinessAccess, requireRecovery: Boolean,
        peer: LocalMasterRpcRegisteredPeer? = null, resourceSession: RpcApplicationSession? = null): Boolean =
        registry.authorizeBusiness(this, credentials, access, requireRecovery, peer, resourceSession)

    /** Genuine original audience is carried into the supervised protected operation itself. */
    public suspend fun readBusiness(credentials: LocalGatewayCredentials, request: ReadRequest,
        profile: LocalMasterRpcWorkflowProfile, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): ReadReply =
        registry.readBusiness(this, credentials, request, profile, peer, resourceSession)

    public suspend fun executeBusiness(credentials: LocalGatewayCredentials, request: MutationRequest,
        profile: LocalMasterRpcWorkflowProfile, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): MutationReply =
        registry.executeBusiness(this, credentials, request, profile, peer, resourceSession)

    public suspend fun lookupBusiness(credentials: LocalGatewayCredentials, identity: OperationIdentity,
        profile: LocalMasterRpcWorkflowProfile, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): LookupReply =
        registry.lookupBusiness(this, credentials, identity, profile, peer, resourceSession)

    /** Actual Host cleanup calls this after denial and before draining owned callbacks. */
    public suspend fun awaitSessionRevocation(): Unit = registry.awaitSessionRevocation(this)

    /** Immediate, irreversible identity retirement; exact SDK cleanup remains owned. */
    public fun retireAll(): Unit = registry.retireAll()

    override fun toString(): String = "LocalMasterRpcPeerRegistration([REDACTED])"
}

/** One exact registry entry. Native admission descriptors are never replaced by SDK keys. */
public class LocalMasterRpcRegisteredPeer internal constructor(
    internal val registry: LocalMasterRpcPeerRegistry,
    public val association: LocalMasterRpcPeerAssociation,
    public val physicalSession: RpcPhysicalSession,
    connectionId: DomainId,
) {
    private val active = MutableStateFlow(true)
    internal lateinit var cleanup: Job
    public val nativePeer: LocalAuthenticatedPeer = association.admission.let { admission ->
        LocalAuthenticatedPeer(connectionId, admission.scope, admission.deviceId,
            admission.publicKeyIdentifier, admission.publicFingerprint, ::isCurrent)
    }

    public fun isCurrent(): Boolean = active.value && registry.isCurrent(this)
    public fun invalidate(): Unit { active.value = false }

    internal val permitsIdentity: Boolean get() = active.value

    override fun toString(): String = "LocalMasterRpcRegisteredPeer([REDACTED])"
}

/** Private native service composition only; no public supplied-currentness implementation. */
internal interface LocalMasterRpcPeerRegistrationLifetime {
    val cleanupScope: CoroutineScope
    fun isCurrent(): Boolean
}

/**
 * This original Master's sole cohost/remote identity source. The private StateFlow has no
 * collectors and acts only as a referential CAS atom. Native checks, SDK calls and coroutine
 * starts occur outside its atomic phase. Capture retains an exact existing entry by ID.
 *
 * At most 128 physical entries, including uncertain cleanup, are retained. An invalidated
 * entry cannot be replaced while its physical session is still open. Removal requires the
 * exact SDK capability's irreversible Closed state; incomplete close remains retained.
 */
internal class LocalMasterRpcPeerRegistry(
    private val owner: RoomRuntimeHistoryOwner,
    private val localPeer: LocalAuthenticatedPeer,
    private val entropy: LocalAuthenticationEntropy,
    private val environment: LocalAuthenticationEnvironment,
    private val sessionFence: LocalMasterRpcSessionFence,
) {
    // Referential equality is required by compareAndSet; do not make this a data class.
    private class Snapshot(val retired: Boolean = false,
        val entries: List<LocalMasterRpcRegisteredPeer> = emptyList())

    private val atom = MutableStateFlow(Snapshot())
    // Additive irreversible denial keeps identity closed even if a bounded CAS cannot win.
    // It never discards the atom's retained physical cleanup census.
    private val denied = MutableStateFlow(false)
    private val registration = MutableStateFlow<LocalMasterRpcPeerRegistration?>(null)
    private val registrationSlot = Semaphore(1)
    private var connectionOrdinal = 0L // Only the admitted registration worker changes it.

    internal fun bind(lifetime: LocalMasterRpcPeerRegistrationLifetime, trust: LocalMasterRpcTrust,
        appId: AppId, client: LocalMasterClient, principals: TrustedPrincipalFactory): LocalMasterRpcPeerRegistration {
        check(!denied.value && owner.operational && localPeer.isCurrent() && lifetime.isCurrent() && lifetime.cleanupScope.isActive &&
            appId == LocalMasterSdkProfileV1.appId(owner.originalScope))
        registration.value?.let { check(it.lifetime === lifetime); return it }
        val candidate = LocalMasterRpcPeerRegistration(this, lifetime, trust, appId, client, principals)
        check(registration.compareAndSet(null, candidate))
        sessionFence.bind(candidate)
        return candidate
    }

    internal suspend fun captureSessionEpoch(token: LocalMasterRpcPeerRegistration): LocalMasterRpcSessionEpoch? {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token)) return null
        val captured = sessionFence.captureSessionEpoch(token)
        currentCoroutineContext().ensureActive()
        return captured.takeIf { ownsCurrent(token) }
    }

    internal suspend fun retainApplicationSession(token: LocalMasterRpcPeerRegistration,
        epoch: LocalMasterRpcSessionEpoch, session: RpcApplicationSession, original: LocalEmployeePrincipal): Boolean {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || original.scope != owner.originalScope) return false
        val identity = LocalMasterRpcSessionIdentity(original.scope, original.actorId, original.deviceId,
            original.sessionId, original.sessionGeneration, original.admissionRevision, original.authenticationPurpose)
        val retained = sessionFence.retainApplicationSession(token, epoch, session, identity)
        currentCoroutineContext().ensureActive()
        return retained && ownsCurrent(token)
    }

    internal suspend fun retainResourceApplicationSession(token: LocalMasterRpcPeerRegistration,
        epoch: LocalMasterRpcSessionEpoch, session: RpcApplicationSession, original: LocalEmployeePrincipal): Boolean {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || original.scope != owner.originalScope) return false
        val identity = LocalMasterRpcSessionIdentity(original.scope, original.actorId, original.deviceId,
            original.sessionId, original.sessionGeneration, original.admissionRevision, original.authenticationPurpose)
        val retained = sessionFence.retainResourceApplicationSession(token, epoch, session, identity)
        currentCoroutineContext().ensureActive()
        return retained && ownsCurrent(token)
    }

    internal suspend fun retainAuthenticationExchangeSession(token: LocalMasterRpcPeerRegistration,
        epoch: LocalMasterRpcSessionEpoch, session: RpcApplicationSession,
        peer: LocalMasterRpcRegisteredPeer): Boolean {
        currentCoroutineContext().ensureActive()
        val device = exchangeDevice(token, session, peer) ?: return false
        val retained = sessionFence.retainAuthenticationExchangeSession(token, epoch, session, device)
        currentCoroutineContext().ensureActive()
        return retained && ownsCurrent(token) && peer.isCurrent()
    }

    internal suspend fun attachAuthenticationExchangeResult(token: LocalMasterRpcPeerRegistration,
        epoch: LocalMasterRpcSessionEpoch, session: RpcApplicationSession,
        peer: LocalMasterRpcRegisteredPeer, original: LocalEmployeePrincipal): Boolean {
        currentCoroutineContext().ensureActive()
        val device = exchangeDevice(token, session, peer) ?: return false
        if (original.scope != device.scope || original.deviceId != device.deviceId ||
            original.admissionRevision != device.admissionRevision) return false
        val identity = LocalMasterRpcSessionIdentity(original.scope, original.actorId, original.deviceId,
            original.sessionId, original.sessionGeneration, original.admissionRevision, original.authenticationPurpose)
        val attached = sessionFence.attachAuthenticationExchangeResult(token, epoch, session, device, identity)
        currentCoroutineContext().ensureActive()
        return attached && ownsCurrent(token) && peer.isCurrent()
    }

    /** Native/currentness callbacks never execute under the session fence's short mutation gate. */
    private fun exchangeDevice(token: LocalMasterRpcPeerRegistration, session: RpcApplicationSession,
        peer: LocalMasterRpcRegisteredPeer): LocalMasterRpcExchangeDeviceIdentity? {
        if (!ownsCurrent(token) || peer.registry !== this || !peer.isCurrent() || !session.isActive ||
            session.peer != peer.physicalSession.peer.fingerprint) return null
        val admission = peer.association.admission
        if (!admission.admitted || admission.scope != owner.originalScope ||
            admission.scope != peer.nativePeer.scope || admission.deviceId != peer.nativePeer.deviceId) return null
        return LocalMasterRpcExchangeDeviceIdentity(admission.scope, admission.deviceId, admission.revision)
    }

    internal suspend fun revokeApplicationSession(token: LocalMasterRpcPeerRegistration,
        session: RpcApplicationSession) {
        check(registration.value === token)
        sessionFence.revokeApplicationSession(token, session)
    }

    internal suspend fun authorizeBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, access: LocalMasterRpcBusinessAccess, requireRecovery: Boolean,
        peer: LocalMasterRpcRegisteredPeer?, resourceSession: RpcApplicationSession?): Boolean {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token)) return false
        val endpoint = token.client as? LocalMasterEndpoint ?: return false
        val audience = if (access.profile == LocalMasterRpcWorkflowProfile.KITCHEN)
            kitchenAudience(token, peer, resourceSession, access) ?: return false else null
        val allowed = endpoint.authorizeRpcBusiness(credentials, access, requireRecovery, audience) == LocalMasterRpcAccessResult.ALLOWED
        currentCoroutineContext().ensureActive()
        return allowed && ownsCurrent(token) && (audience == null || audience.current())
    }

    internal suspend fun readBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, request: ReadRequest, profile: LocalMasterRpcWorkflowProfile,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): ReadReply {
        if (profile != LocalMasterRpcWorkflowProfile.KITCHEN) return token.client.read(credentials, request)
        val audience = kitchenAudience(token, peer, session, LocalMasterRpcBusinessAccess.Read(request, profile))
            ?: return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        val endpoint = token.client as? LocalMasterEndpoint
            ?: return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        return endpoint.readRpcBusiness(credentials, request, audience)
    }

    internal suspend fun executeBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, request: MutationRequest, profile: LocalMasterRpcWorkflowProfile,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): MutationReply {
        if (profile != LocalMasterRpcWorkflowProfile.KITCHEN) return token.client.execute(credentials, request)
        val audience = kitchenAudience(token, peer, session, LocalMasterRpcBusinessAccess.Execute(request, profile))
            ?: return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
        val endpoint = token.client as? LocalMasterEndpoint
            ?: return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
        return endpoint.executeRpcBusiness(credentials, request, audience)
    }

    internal suspend fun lookupBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, identity: OperationIdentity, profile: LocalMasterRpcWorkflowProfile,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): LookupReply {
        if (profile != LocalMasterRpcWorkflowProfile.KITCHEN) return token.client.lookup(credentials, identity)
        val audience = kitchenAudience(token, peer, session, LocalMasterRpcBusinessAccess.Lookup(identity, profile))
            ?: return LookupReply.Deferred(identity.operationId, DeferredReason.AUTHORIZATION_DENIED)
        val endpoint = token.client as? LocalMasterEndpoint
            ?: return LookupReply.Deferred(identity.operationId, DeferredReason.AUTHORIZATION_DENIED)
        return endpoint.lookupRpcBusiness(credentials, identity, audience)
    }

    private suspend fun kitchenAudience(token: LocalMasterRpcPeerRegistration,
        peer: LocalMasterRpcRegisteredPeer?, session: RpcApplicationSession?,
        access: LocalMasterRpcBusinessAccess): RoomLocalDisplayRpcAudience? {
        currentCoroutineContext().ensureActive()
        if (access.profile != LocalMasterRpcWorkflowProfile.KITCHEN || !ownsCurrent(token) || peer == null ||
            session == null || peer.registry !== this || !peer.isCurrent() ||
            session.peer != peer.physicalSession.peer.fingerprint) return null
        val retained = sessionFence.captureResourceSession(token, session) ?: return null
        val identity = retained.identity
        val admission = peer.association.admission
        if (!admission.admitted || identity.scope != owner.originalScope || identity.scope != admission.scope ||
            identity.deviceId != admission.deviceId || identity.admissionRevision != admission.revision ||
            session.principal != identity.actorId.value || session.sessionId != identity.sessionId.value) return null
        val audience = RoomLocalDisplayRpcAudience(this, owner, peer, retained, access)
        currentCoroutineContext().ensureActive()
        return audience.takeIf { ownsCurrent(token) && it.current() }
    }

    /** Pure exact-entry state for the final protected phase, never native trust callbacks. */
    internal fun audienceStateCurrent(entry: LocalMasterRpcRegisteredPeer): Boolean {
        val snapshot = atom.value
        return entry.registry === this && !denied.value && !snapshot.retired && entry.permitsIdentity &&
            snapshot.entries.any { it === entry } && entry.physicalSession.closure.value == RpcPhysicalSessionClosure.Open
    }

    internal suspend fun awaitSessionRevocation(token: LocalMasterRpcPeerRegistration) {
        check(registration.value === token)
        sessionFence.awaitSessionRevocation()
    }

    internal suspend fun capture(connectionId: DomainId): LocalAuthenticatedPeer? {
        currentCoroutineContext().ensureActive()
        if (connectionId == localPeer.connectionId) {
            val current = localPeer.isCurrent()
            currentCoroutineContext().ensureActive()
            return localPeer.takeIf { current }
        }
        val entry = atom.value.entries.firstOrNull { it.nativePeer.connectionId == connectionId } ?: return null
        val current = entry.isCurrent()
        currentCoroutineContext().ensureActive()
        return entry.nativePeer.takeIf { current }
    }

    internal suspend fun register(token: LocalMasterRpcPeerRegistration,
        physical: RpcPhysicalSession): LocalMasterRpcRegisteredPeer? {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || !physical.isConnected || physical.closure.value != RpcPhysicalSessionClosure.Open) return null
        // Existing exact entries do not compete with another peer's native admission read.
        atom.value.entries.firstOrNull { it.physicalSession === physical }?.let {
            val current = it.isCurrent()
            currentCoroutineContext().ensureActive()
            return it.takeIf { current }
        }
        if (!registrationSlot.tryAcquire()) return null
        var published: LocalMasterRpcRegisteredPeer? = null
        try {
            if (!ownsCurrent(token)) return null
            // An already retired exact physical object is not a new connection.
            atom.value.entries.firstOrNull { it.physicalSession === physical }?.let {
                val current = it.isCurrent()
                currentCoroutineContext().ensureActive()
                return it.takeIf { current }
            }
            val association = token.trust.readPeerAssociation(token.appId, physical.peer) ?: return null
            currentCoroutineContext().ensureActive()
            if (!ownsCurrent(token) || !association.belongsTo(owner) || !association.isCurrent() ||
                !physical.isConnected || physical.closure.value != RpcPhysicalSessionClosure.Open) return null
            val id = freshConnectionId() ?: return null
            val entry = LocalMasterRpcRegisteredPeer(this, association, physical, id)
            // Retain the actual cleanup Job before publication, but start it only outside
            // native/service/Room work. Caller cancellation cannot cancel this service Job.
            entry.cleanup = token.lifetime.cleanupScope.launch(start = CoroutineStart.LAZY) { cleanup(entry) }
            if (!publish(entry)) {
                entry.cleanup.cancel()
                return null
            }
            published = entry
            entry.cleanup.start()
            currentCoroutineContext().ensureActive()
            return entry.takeIf { it.isCurrent() }
        } catch (failure: Throwable) {
            published?.invalidate()
            throw failure
        } finally { registrationSlot.release() }
    }

    internal fun isCurrent(entry: LocalMasterRpcRegisteredPeer): Boolean {
        val token = registration.value
        val before = atom.value
        if (entry.registry !== this || denied.value || before.retired || !entry.permitsIdentity ||
            before.entries.none { it === entry }) return false
        val current = try {
            token != null && ownsCurrent(token) && entry.association.belongsTo(owner) && entry.association.isCurrent() &&
                entry.physicalSession.isConnected && entry.physicalSession.closure.value == RpcPhysicalSessionClosure.Open
        } catch (_: Throwable) { false }
        if (!current) { entry.invalidate(); return false }
        val after = atom.value
        return !denied.value && !after.retired && entry.permitsIdentity && after.entries.any { it === entry }
    }

    internal fun retireAll() {
        denyIdentity()
        atom.value.entries.forEach { it.invalidate() }
    }

    /** Must run outside the service/native/Room slot, before original custody release. */
    internal suspend fun awaitCleanup() {
        retireAll()
        withContext(NonCancellable) {
            sessionFence.awaitSessionRevocation()
            // Drain a registration already between settlement and publication before
            // taking the complete cleanup census. Future registrations are denied.
            registrationSlot.acquire()
            val entries = try { atom.value.entries } finally { registrationSlot.release() }
            for (entry in entries) { entry.cleanup.start(); entry.cleanup.join() }
            for (entry in entries) {
                check(entry.physicalSession.closure.value == RpcPhysicalSessionClosure.Closed) {
                    "Original RPC physical cleanup is unresolved"
                }
                removeClosed(entry)
            }
            check(atom.value.entries.isEmpty()) { "Original RPC physical cleanup is unresolved" }
        }
    }

    private fun ownsCurrent(token: LocalMasterRpcPeerRegistration): Boolean =
        registration.value === token && !denied.value && !atom.value.retired && owner.operational && localPeer.isCurrent() &&
            token.lifetime.cleanupScope.isActive && token.lifetime.isCurrent()

    internal suspend fun currentAfterExecutionSettles(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials): LocalSessionViewResult {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token)) return LocalSessionViewResult.Unavailable
        val entry = atom.value.entries.firstOrNull { it.nativePeer.connectionId == credentials.connectionId }
            ?: return LocalSessionViewResult.Unavailable
        if (!entry.isCurrent()) return LocalSessionViewResult.Unavailable
        val endpoint = token.client as? LocalMasterEndpoint ?: return LocalSessionViewResult.Unavailable
        val result = endpoint.currentAfterExecutionSettles(credentials)
        currentCoroutineContext().ensureActive()
        return if (ownsCurrent(token) && entry.isCurrent()) result else LocalSessionViewResult.Unavailable
    }

    internal suspend fun remainingSessionLifetimeMillis(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials): Long? {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token)) return null
        val entry = atom.value.entries.firstOrNull { it.nativePeer.connectionId == credentials.connectionId } ?: return null
        if (!entry.isCurrent()) return null
        val before = environment.observe() ?: return null
        val current = token.client.current(credentials) as? LocalSessionViewResult.Current ?: return null
        currentCoroutineContext().ensureActive()
        val observed = environment.observe() ?: return null
        val view = current.view
        if (!ownsCurrent(token) || !entry.isCurrent() || view.scope != owner.originalScope ||
            view.deviceId != entry.nativePeer.deviceId || observed.issuerContextId != before.issuerContextId ||
            observed.moment.bootId != before.moment.bootId || observed.moment.elapsedMillis < before.moment.elapsedMillis ||
            view.accessExpiresAt.bootId != observed.moment.bootId || view.workExpiresAt.bootId != observed.moment.bootId) return null
        val deadline = minOf(view.accessExpiresAt.elapsedMillis, view.workExpiresAt.elapsedMillis)
        val remaining = deadline - observed.moment.elapsedMillis
        currentCoroutineContext().ensureActive()
        return remaining.takeIf { it > 0 }
    }

    private fun publish(entry: LocalMasterRpcRegisteredPeer): Boolean {
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            if (denied.value || before.retired || before.entries.size >= MAX_ENTRIES ||
                entry.nativePeer.connectionId == localPeer.connectionId ||
                before.entries.any { it.physicalSession === entry.physicalSession ||
                    it.nativePeer.connectionId == entry.nativePeer.connectionId }) return false
            if (atom.compareAndSet(before, Snapshot(false, before.entries + entry))) return true
        }
        denyIdentity()
        return false
    }

    private suspend fun cleanup(entry: LocalMasterRpcRegisteredPeer) {
        try {
            while (entry.isCurrent()) delay(CLEANUP_POLL_MILLIS)
        } finally {
            entry.invalidate()
            withContext(NonCancellable) {
                try {
                    entry.physicalSession.close()
                    if (entry.physicalSession.closure.value == RpcPhysicalSessionClosure.Closed) removeClosed(entry)
                } catch (_: Throwable) {
                    // Keep this exact physical object and Job. Unknown close is not release.
                }
            }
        }
    }

    private fun removeClosed(entry: LocalMasterRpcRegisteredPeer) {
        if (entry.physicalSession.closure.value != RpcPhysicalSessionClosure.Closed) return
        entry.invalidate()
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            if (before.entries.none { it === entry }) return
            if (atom.compareAndSet(before, Snapshot(before.retired, before.entries.filterNot { it === entry }))) return
        }
        denyIdentity()
    }

    private fun denyIdentity() {
        denied.value = true
        sessionFence.deny()
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            if (before.retired) return
            if (atom.compareAndSet(before, Snapshot(true, before.entries))) return
        }
    }

    private fun freshConnectionId(): DomainId? {
        if (connectionOrdinal == MAX_CONNECTION_ORDINAL) { retireAll(); return null }
        val ordinal = ++connectionOrdinal
        val bytes = ByteArray(16)
        return try {
            entropy.fill(bytes)
            // Native random prefix plus a never-reused 62-bit ordinal in this Master.
            // This is a public UUID locator, not a credential, clock or authorization token.
            for (index in 0 until 8) bytes[15 - index] = (ordinal ushr (index * 8)).toByte()
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte() // Custom UUIDv8 layout.
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            DomainId(buildString(36) {
                for (index in 0 until 16) {
                    if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                    val value = bytes[index].toInt() and 0xff
                    append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15])
                }
            })
        } finally { bytes.fill(0) }
    }

    private companion object {
        const val MAX_ENTRIES: Int = 128
        const val MAX_CAS_ATTEMPTS: Int = 32
        const val MAX_CONNECTION_ORDINAL: Long = 0x3fffffffffffffffL
        const val CLEANUP_POLL_MILLIS: Long = 250
    }
}

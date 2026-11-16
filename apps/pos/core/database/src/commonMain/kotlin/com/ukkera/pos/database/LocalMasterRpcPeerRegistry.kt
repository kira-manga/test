package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalAuthenticationEntropy
import com.ukkera.pos.security.LocalAuthenticationEnvironment
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalSessionView
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
    // Only this actual retained Host registration owns staged invocations and their cleanup.
    internal val physicalInvocations = LocalMasterRpcPhysicalRegistry(this)

    /** Actual Host closure only. Exact SDK application resource is captured inside core. */
    public suspend fun physicalInteractive(credentials: LocalGatewayCredentials,
        body: LocalMasterPhysicalRpcBodyV1, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): LocalMasterPhysicalRpcDelivery? =
        registry.physicalInteractive(this, credentials, body, peer, resourceSession)

    /** Transport eligibility only. Exact per-action authorization remains at the
     * protected endpoint/native gate, separately from this current resource identity. */
    public suspend fun physicalInteractiveSessionCurrent(credentials: LocalGatewayCredentials,
        peer: LocalMasterRpcRegisteredPeer, resourceSession: RpcApplicationSession): Boolean =
        registry.physicalInteractiveSessionCurrent(this, credentials, peer, resourceSession)

    /** Device-only immutable-evidence scope; never an employee resource or result. */
    public suspend fun physicalOriginalSessionCurrent(session: RpcApplicationSession,
        peer: LocalMasterRpcRegisteredPeer): Boolean = registry.physicalOriginalSessionCurrent(this, session, peer)

    /** Device-only immutable evidence recovery. No employee result, grant or fresh action. */
    public suspend fun physicalOriginal(request: LocalMasterPhysicalOriginalRequestV1,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): LocalMasterPhysicalOriginalResponseV1? =
        registry.physicalOriginal(this, request, peer, session)

    /** No supplied admission, connection ID, currentness predicate or latest-key lookup. */
    public suspend fun register(physicalSession: RpcPhysicalSession): LocalMasterRpcRegisteredPeer? =
        registry.register(this, physicalSession)

    /** Stage denial before the one actual complete native association census. No employee
     * or business scope is admitted here; the private Host supplies its real SDK objects. */
    public suspend fun registerAuthenticationExchange(session: RpcApplicationSession,
        physicalSession: RpcPhysicalSession): LocalMasterRpcRegisteredPeer? =
        registry.registerAuthenticationExchange(this, session, physicalSession)

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

    /** Existing identity-only SDK object after fresh original verification, never admission. */
    public suspend fun existingIdentitySessionCurrent(session: RpcApplicationSession,
        original: LocalEmployeePrincipal): Boolean =
        registry.existingIdentitySessionCurrent(this, session, original)

    /** Business output has a separate policy-fenced SDK session, never an identity-only scope. */
    public suspend fun retainResourceApplicationSession(epoch: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession, original: LocalEmployeePrincipal): Boolean =
        registry.retainResourceApplicationSession(this, epoch, session, original)

    /** Private Host exchange only: exact admitted device, not a supplied employee identity. */
    public suspend fun retainAuthenticationExchangeSession(epoch: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer): Boolean =
        registry.retainAuthenticationExchangeSession(this, epoch, session, peer)

    /** Staged device invalidation census followed by the actual fresh native association
     * read. This supplies no employee identity or business resource. */
    public suspend fun retainVerifiedAuthenticationExchangeSession(session: RpcApplicationSession,
        peer: LocalMasterRpcRegisteredPeer): Boolean =
        registry.retainVerifiedAuthenticationExchangeSession(this, session, peer)

    /** Post-issuance capture precedes fresh result verification; attachment is irreversible. */
    public suspend fun attachAuthenticationExchangeResult(epoch: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer,
        original: LocalEmployeePrincipal): Boolean =
        registry.attachAuthenticationExchangeResult(this, epoch, session, peer, original)

    /** Expected display result DATA only; stage before fresh principal work, never a grant. */
    public suspend fun prepareAuthenticationExchangeResult(session: RpcApplicationSession,
        peer: LocalMasterRpcRegisteredPeer, view: LocalSessionView): Boolean =
        registry.prepareAuthenticationExchangeResult(this, session, peer, view)

    /** Fresh verified principal must match the same continuously retained candidate exactly. */
    public suspend fun completeAuthenticationExchangeResult(session: RpcApplicationSession,
        peer: LocalMasterRpcRegisteredPeer, original: LocalEmployeePrincipal): Boolean =
        registry.completeAuthenticationExchangeResult(this, session, peer, original)

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

    /** Scoped progress uses the same genuine physical peer and protected audience as reads. */
    public suspend fun snapshotBusiness(credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile,
        selection: ReadRequest, expectedCut: LocalMasterChangeCursorV1?, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): LocalMasterChangesReplyV1 =
        registry.snapshotBusiness(this, credentials, profile, selection, expectedCut, peer, resourceSession)

    public suspend fun pullBusiness(credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile,
        selection: ReadRequest, cursor: LocalMasterChangeCursorV1, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): LocalMasterChangesReplyV1 =
        registry.pullBusiness(this, credentials, profile, selection, cursor, peer, resourceSession)

    public suspend fun snapshotBatchBusiness(credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile,
        selections: List<ReadRequest>, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): LocalMasterChangesBatchReplyV1 =
        registry.snapshotBatchBusiness(this, credentials, profile, selections, peer, resourceSession)

    /** Read-only family check; its inert classification is not an accepting resource grant. */
    public suspend fun authorizeChangesBusiness(credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile,
        selections: List<ReadRequest>, peer: LocalMasterRpcRegisteredPeer, resourceSession: RpcApplicationSession,
        capacityOnly: Boolean = false): LocalMasterChangesRpcAuthorizationV1 =
        registry.authorizeChangesBusiness(this, credentials, profile, selections, peer, resourceSession, capacityOnly)

    public suspend fun executeBusiness(credentials: LocalGatewayCredentials, request: MutationRequest,
        profile: LocalMasterRpcWorkflowProfile, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): MutationReply =
        registry.executeBusiness(this, credentials, request, profile, peer, resourceSession)

    public suspend fun lookupBusiness(credentials: LocalGatewayCredentials, identity: OperationIdentity,
        profile: LocalMasterRpcWorkflowProfile, peer: LocalMasterRpcRegisteredPeer,
        resourceSession: RpcApplicationSession): LookupReply =
        registry.lookupBusiness(this, credentials, identity, profile, peer, resourceSession)

    /** The SAME Master's actual creation service while this registration owns the current
     * Host. Every call on it still reauthorizes the transport-authenticated creator. */
    public suspend fun employeeCreationService(): LocalMasterEmployeeCreationClient? =
        registry.employeeCreationService(this)

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
    private val registrationQueue = Semaphore(MAX_ENTRIES)
    private var connectionOrdinal = 0L // Only the admitted registration worker changes it.

    internal fun bind(lifetime: LocalMasterRpcPeerRegistrationLifetime, trust: LocalMasterRpcTrust,
        appId: AppId, client: LocalMasterClient, principals: TrustedPrincipalFactory): LocalMasterRpcPeerRegistration {
        check(!denied.value && owner.operational && localPeer.isCurrent() && lifetime.isCurrent() && lifetime.cleanupScope.isActive &&
            appId == owner.compositionProfile.appId(owner.originalScope))
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

    internal suspend fun existingIdentitySessionCurrent(token: LocalMasterRpcPeerRegistration,
        session: RpcApplicationSession, original: LocalEmployeePrincipal): Boolean {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || original.scope != owner.originalScope) return false
        val identity = LocalMasterRpcSessionIdentity(original.scope, original.actorId, original.deviceId,
            original.sessionId, original.sessionGeneration, original.admissionRevision, original.authenticationPurpose)
        val current = sessionFence.existingIdentitySessionCurrent(token, session, identity)
        currentCoroutineContext().ensureActive()
        return current && ownsCurrent(token)
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

    internal suspend fun retainVerifiedAuthenticationExchangeSession(token: LocalMasterRpcPeerRegistration,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer): Boolean {
        val trace = LocalMasterRpcReadTrace(LocalMasterRpcReadTracePhase.AUTHENTICATE,
            LocalMasterRpcReadStage.EXCHANGE_DEVICE_CANDIDATE)
        var stage by trace
        var accepted = false
        try {
            currentCoroutineContext().ensureActive()
            val device = exchangeDevice(token, session, peer) ?: return false
            if (!sessionFence.prepareAuthenticationExchangeSession(token, session, device)) return false
            // register() can reuse a continuously retained peer. This distinct protected read
            // must actually reobserve the original native association AFTER staging denial.
            stage = LocalMasterRpcReadStage.EXCHANGE_ADMISSION_READ
            val fresh = token.trust.readPeerAssociation(token.appId, peer.physicalSession.peer) ?: return false
            currentCoroutineContext().ensureActive()
            stage = LocalMasterRpcReadStage.EXCHANGE_NATIVE_CURRENT
            if (!ownsCurrent(token) || !peer.isCurrent() || !fresh.belongsTo(owner) || !fresh.isCurrent()) return false
            // Native reads materialize new admission objects. Compare the complete canonical
            // record (including descriptors/capabilities), never object identity or revision alone.
            stage = LocalMasterRpcReadStage.EXCHANGE_ADMISSION_RECORD
            val expectedAdmission = ProtectedLocalSecurityCodec.encodeAdmission(peer.association.admission)
            val observedAdmission = ProtectedLocalSecurityCodec.encodeAdmission(fresh.admission)
            try { if (!expectedAdmission.contentEquals(observedAdmission)) return false }
            finally { expectedAdmission.fill(0); observedAdmission.fill(0) }
            stage = LocalMasterRpcReadStage.EXCHANGE_SDK_RECORD
            val expected = peer.association.sdkIdentity.copyWireBytes()
            val observed = fresh.sdkIdentity.copyWireBytes()
            try { if (!expected.contentEquals(observed)) return false }
            finally { expected.fill(0); observed.fill(0) }
            stage = LocalMasterRpcReadStage.EXCHANGE_DEVICE_COMPLETION
            val completed = sessionFence.completeAuthenticationExchangeSession(token, session, device)
            if (!completed && !session.isActive) stage = LocalMasterRpcReadStage.EXCHANGE_SESSION_INACTIVE
            currentCoroutineContext().ensureActive()
            if (completed) stage = LocalMasterRpcReadStage.EXCHANGE_FINAL_CURRENT
            accepted = completed && ownsCurrent(token) && peer.isCurrent() && fresh.isCurrent()
            return accepted
        } catch (failure: Throwable) {
            reportLocalMasterRpcReadFailure(stage, failure)
            throw failure
        } finally {
            trace.finish()
            if (!accepted) reportLocalMasterRpcReadFailure(stage, null)
        }
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

    internal suspend fun prepareAuthenticationExchangeResult(token: LocalMasterRpcPeerRegistration,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer, view: LocalSessionView): Boolean {
        currentCoroutineContext().ensureActive()
        val device = exchangeDevice(token, session, peer) ?: return false
        if (view.scope != device.scope || view.deviceId != device.deviceId) return false
        val candidate = LocalMasterRpcSessionIdentity(view.scope, view.actorId, view.deviceId,
            view.sessionId, view.generation, device.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        val prepared = sessionFence.prepareAuthenticationExchangeResult(token, session, device, candidate)
        currentCoroutineContext().ensureActive()
        return prepared && ownsCurrent(token) && peer.isCurrent()
    }

    internal suspend fun completeAuthenticationExchangeResult(token: LocalMasterRpcPeerRegistration,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer, original: LocalEmployeePrincipal): Boolean {
        currentCoroutineContext().ensureActive()
        val device = exchangeDevice(token, session, peer) ?: return false
        val identity = LocalMasterRpcSessionIdentity(original.scope, original.actorId, original.deviceId,
            original.sessionId, original.sessionGeneration, original.admissionRevision, original.authenticationPurpose)
        val attached = sessionFence.completeAuthenticationExchangeResult(token, session, device, identity)
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
        if (access.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS) {
            val exactPeer = peer ?: return false
            val retained = changesResource(token, credentials, exactPeer, resourceSession ?: return false) ?: return false
            val allowed = endpoint.authorizeRpcBusinessOriginal(credentials, access, requireRecovery, retained) ==
                LocalMasterRpcAccessResult.ALLOWED
            currentCoroutineContext().ensureActive()
            return allowed && ownsCurrent(token) && exactPeer.isCurrent() && retained.current()
        }
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

    internal suspend fun snapshotBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile, selection: ReadRequest,
        expectedCut: LocalMasterChangeCursorV1?, peer: LocalMasterRpcRegisteredPeer,
        session: RpcApplicationSession): LocalMasterChangesReplyV1 {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || peer.registry !== this || !peer.isCurrent() || !session.isActive)
            return LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
        val endpoint = token.client as? LocalMasterEndpoint
            ?: return LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
        val audience = if (profile == LocalMasterRpcWorkflowProfile.KITCHEN)
            kitchenAudience(token, peer, session, LocalMasterRpcBusinessAccess.Read(selection, profile))
                ?: return LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
            else null
        val result = if (audience == null) endpoint.snapshot(credentials, profile, selection, expectedCut)
            else endpoint.snapshotRpcBusiness(credentials, profile, selection, expectedCut, audience)
        currentCoroutineContext().ensureActive()
        return if (ownsCurrent(token) && peer.isCurrent() && session.isActive && (audience == null || audience.current())) result
            else LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
    }

    internal suspend fun pullBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile, selection: ReadRequest,
        cursor: LocalMasterChangeCursorV1, peer: LocalMasterRpcRegisteredPeer,
        session: RpcApplicationSession): LocalMasterChangesReplyV1 {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || peer.registry !== this || !peer.isCurrent() || !session.isActive)
            return LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
        val endpoint = token.client as? LocalMasterEndpoint
            ?: return LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
        val audience = if (profile == LocalMasterRpcWorkflowProfile.KITCHEN)
            kitchenAudience(token, peer, session, LocalMasterRpcBusinessAccess.Read(selection, profile))
                ?: return LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
            else null
        val result = if (audience == null) endpoint.pull(credentials, profile, selection, cursor)
            else endpoint.pullRpcBusiness(credentials, profile, selection, cursor, audience)
        currentCoroutineContext().ensureActive()
        return if (ownsCurrent(token) && peer.isCurrent() && session.isActive && (audience == null || audience.current())) result
            else LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
    }

    internal suspend fun snapshotBatchBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile, selections: List<ReadRequest>,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): LocalMasterChangesBatchReplyV1 {
        val retained = changesResource(token, credentials, peer, session)
            ?: return LocalMasterChangesBatchReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
        if (profile != LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
            return LocalMasterChangesBatchReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
        val endpoint = token.client as? LocalMasterEndpoint
            ?: return LocalMasterChangesBatchReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
        val reply = endpoint.snapshotBatchRpcOriginal(credentials, profile, selections, retained)
        currentCoroutineContext().ensureActive()
        return if (ownsCurrent(token) && peer.isCurrent() && retained.current()) reply
            else LocalMasterChangesBatchReplyV1.Unavailable(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
    }

    internal suspend fun authorizeChangesBusiness(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, profile: LocalMasterRpcWorkflowProfile, selections: List<ReadRequest>,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession,
        capacityOnly: Boolean): LocalMasterChangesRpcAuthorizationV1 {
        fun denied() = LocalMasterChangesRpcAuthorizationV1(LocalMasterChangesRpcAccessResultV1.DENIED, null)
        // Capture exact retained DATA here; the endpoint freshly authenticates and joins
        // this identity after its actual queue wait, before protected query authorization.
        val retained = changesResource(token, credentials, peer, session) ?: return denied()
        val endpoint = token.client as? LocalMasterEndpoint ?: return denied()
        val result = if (capacityOnly) endpoint.authorizeChangesCapacity(credentials, profile, retained)
            else if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                endpoint.authorizeChangesBusiness(credentials, profile, selections, retained)
            else {
                if (selections.size != 1) return denied()
                when (val snapshot = snapshotBusiness(token, credentials, profile, selections.single(), null, peer, session)) {
                    is LocalMasterChangesReplyV1.Snapshot -> LocalMasterChangesRpcAuthorizationV1(
                        LocalMasterChangesRpcAccessResultV1.ALLOWED, LocalMasterChangesBindingV1.digest(listOf(snapshot.cursor)))
                    is LocalMasterChangesReplyV1.Unchanged -> denied()
                    is LocalMasterChangesReplyV1.Unavailable -> LocalMasterChangesRpcAuthorizationV1(
                        if (snapshot.reason == LocalMasterChangesFailureV1.CAPACITY_EXCEEDED) LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY
                        else if (snapshot.reason == LocalMasterChangesFailureV1.MALFORMED_REQUEST) LocalMasterChangesRpcAccessResultV1.MALFORMED_ONLY
                        else if (snapshot.reason in setOf(LocalMasterChangesFailureV1.AUTHORIZATION_DENIED,
                                LocalMasterChangesFailureV1.WRONG_SCOPE, LocalMasterChangesFailureV1.UNKNOWN_QUERY)) LocalMasterChangesRpcAccessResultV1.DENIED
                        else LocalMasterChangesRpcAccessResultV1.UNAVAILABLE, null)
                }
            }
        currentCoroutineContext().ensureActive()
        return if (ownsCurrent(token) && peer.isCurrent() && retained.current()) result else denied()
    }

    /** Actual retained SDK resource DATA only. It neither authenticates nor authorizes. */
    private suspend fun changesResource(token: LocalMasterRpcPeerRegistration, credentials: LocalGatewayCredentials,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): LocalMasterRpcResourceSession? {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || peer.registry !== this || !peer.isCurrent() ||
            credentials.connectionId != peer.nativePeer.connectionId || session.peer != peer.physicalSession.peer.fingerprint) return null
        val retained = sessionFence.captureResourceSession(token, session) ?: return null
        val identity = retained.identity
        val admission = peer.association.admission
        if (identity.purpose != AuthenticationPurpose.INTERACTIVE || identity.scope != owner.originalScope ||
            !admission.admitted || admission.scope != identity.scope || admission.deviceId != identity.deviceId ||
            admission.revision != identity.admissionRevision || session.principal != identity.actorId.value ||
            session.sessionId != identity.sessionId.value) return null
        return retained.takeIf { ownsCurrent(token) && peer.isCurrent() && it.current() }
    }

    /** Actual retained SDK resource and freshly checked exact original identity, never request claims. */
    private suspend fun changesSession(token: LocalMasterRpcPeerRegistration, credentials: LocalGatewayCredentials,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): LocalMasterRpcResourceSession? {
        currentCoroutineContext().ensureActive()
        val retained = changesResource(token, credentials, peer, session) ?: return null
        val original = token.principals.authenticateLocal(credentials) ?: return null
        val identity = retained.identity
        if (original.authenticationPurpose != identity.purpose || original.scope != identity.scope ||
            original.actorId != identity.actorId || original.deviceId != identity.deviceId ||
            original.sessionId != identity.sessionId || original.sessionGeneration != identity.generation ||
            original.admissionRevision != identity.admissionRevision) return null
        currentCoroutineContext().ensureActive()
        return retained.takeIf { ownsCurrent(token) && peer.isCurrent() && it.current() }
    }

    internal suspend fun physicalInteractiveSessionCurrent(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): Boolean {
        val retained = changesSession(token, credentials, peer, session) ?: return false
        val endpoint = token.client as? LocalMasterEndpoint ?: return false
        return endpoint.physicalRemoteGate != null && ownsCurrent(token) && peer.isCurrent() && retained.current()
    }

    internal suspend fun physicalOriginalSessionCurrent(token: LocalMasterRpcPeerRegistration,
        session: RpcApplicationSession, peer: LocalMasterRpcRegisteredPeer): Boolean {
        val device = exchangeDevice(token, session, peer) ?: return false
        val endpoint = token.client as? LocalMasterEndpoint ?: return false
        return endpoint.physicalRemoteGate != null && sessionFence.deviceOnlyExchangeCurrent(token, session, device) &&
            ownsCurrent(token) && peer.isCurrent() && session.isActive
    }

    internal suspend fun physicalInteractive(token: LocalMasterRpcPeerRegistration,
        credentials: LocalGatewayCredentials, body: LocalMasterPhysicalRpcBodyV1,
        peer: LocalMasterRpcRegisteredPeer, session: RpcApplicationSession): LocalMasterPhysicalRpcDelivery? {
        // changesSession is the existing full exact original-principal/resource/admission join.
        // It captures an actual ResourceSession, not a caller-nominated identity or predicate.
        val retained = changesSession(token, credentials, peer, session) ?: return null
        val endpoint = token.client as? LocalMasterEndpoint ?: return null
        if (endpoint.physicalRemoteGate == null) return null
        val origin = LocalMasterRpcPhysicalOrigin(token, peer, retained)
        return token.physicalInvocations.exchange(origin, credentials, body).takeIf {
            ownsCurrent(token) && peer.isCurrent() && retained.current()
        }
    }

    internal suspend fun physicalOriginal(token: LocalMasterRpcPeerRegistration,
        request: LocalMasterPhysicalOriginalRequestV1, peer: LocalMasterRpcRegisteredPeer,
        session: RpcApplicationSession): LocalMasterPhysicalOriginalResponseV1? {
        currentCoroutineContext().ensureActive()
        if (!physicalOriginalSessionCurrent(token, session, peer) || peer.registry !== this ||
            !peer.association.admission.admitted || peer.association.admission.scope != owner.originalScope) return null
        val endpoint = token.client as? LocalMasterEndpoint ?: return null
        val sameGate = endpoint.physicalRemoteGate ?: return null
        val device = LocalMasterPhysicalOriginalDevice(token, peer)
        val retained = sameGate.retainRemoteOriginal(device, request)
        currentCoroutineContext().ensureActive()
        if (!physicalOriginalSessionCurrent(token, session, peer) || !device.owns(sameGate) || endpoint.physicalRemoteGate !== sameGate) return null
        val body = request.originalBody.copyBytes()
        val digest = try { com.ukkera.pos.security.sha256(body) } finally { body.fill(0) }
        return LocalMasterPhysicalOriginalResponseV1(request.clientAllocationId, digest, retained)
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

    internal suspend fun employeeCreationService(token: LocalMasterRpcPeerRegistration): LocalMasterEmployeeCreationClient? {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token)) return null
        return (token.client as? LocalMasterEndpoint)?.takeIf { ownsCurrent(token) }
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

    internal suspend fun registerAuthenticationExchange(token: LocalMasterRpcPeerRegistration,
        session: RpcApplicationSession, physical: RpcPhysicalSession): LocalMasterRpcRegisteredPeer? {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || session.peer != physical.peer.fingerprint || !physical.isConnected ||
            physical.closure.value != RpcPhysicalSessionClosure.Open) return null
        if (!sessionFence.prepareNativeAuthenticationExchange(token, session, physical)) return null
        // New registration already performs the real complete census. A cached exact peer
        // explicitly rereads it once here. Both reads occur AFTER the pending denial cut.
        val peer = registerPeer(token, physical, freshExisting = true) ?: return null
        val device = exchangeDevice(token, session, peer) ?: return null
        if (!sessionFence.completeNativeAuthenticationExchange(token, session, physical, device)) return null
        currentCoroutineContext().ensureActive()
        return peer.takeIf { ownsCurrent(token) && it.isCurrent() && session.isActive }
    }

    internal suspend fun register(token: LocalMasterRpcPeerRegistration,
        physical: RpcPhysicalSession): LocalMasterRpcRegisteredPeer? = registerPeer(token, physical, freshExisting = false)

    private suspend fun refreshRegisteredAssociation(token: LocalMasterRpcPeerRegistration,
        peer: LocalMasterRpcRegisteredPeer): Boolean {
        if (!ownsCurrent(token) || !peer.isCurrent()) return false
        val fresh = token.trust.readPeerAssociation(token.appId, peer.physicalSession.peer) ?: return false
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || !peer.isCurrent() || !fresh.belongsTo(owner) || !fresh.isCurrent()) return false
        val expectedAdmission = ProtectedLocalSecurityCodec.encodeAdmission(peer.association.admission)
        val observedAdmission = ProtectedLocalSecurityCodec.encodeAdmission(fresh.admission)
        try { if (!expectedAdmission.contentEquals(observedAdmission)) return false }
        finally { expectedAdmission.fill(0); observedAdmission.fill(0) }
        val expected = peer.association.sdkIdentity.copyWireBytes()
        val observed = fresh.sdkIdentity.copyWireBytes()
        try { if (!expected.contentEquals(observed)) return false }
        finally { expected.fill(0); observed.fill(0) }
        return ownsCurrent(token) && peer.isCurrent() && fresh.isCurrent()
    }

    private suspend fun registerPeer(token: LocalMasterRpcPeerRegistration,
        physical: RpcPhysicalSession, freshExisting: Boolean): LocalMasterRpcRegisteredPeer? {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrent(token) || !physical.isConnected || physical.closure.value != RpcPhysicalSessionClosure.Open) return null
        // Existing exact entries do not compete with another peer's native admission read.
        atom.value.entries.firstOrNull { it.physicalSession === physical }?.let {
            val current = it.isCurrent()
            currentCoroutineContext().ensureActive()
            return if (current && (!freshExisting || refreshRegisteredAssociation(token, it))) it else null
        }
        if (!registrationQueue.tryAcquire()) return null
        var slotOwned = false
        var published: LocalMasterRpcRegisteredPeer? = null
        try {
            // Waiting owns no native work or peer authority. Concurrent first connections
            // share the original reader instead of treating contention as withdrawn trust.
            registrationSlot.acquire()
            slotOwned = true
            currentCoroutineContext().ensureActive()
            if (!ownsCurrent(token)) return null
            // An already retired exact physical object is not a new connection.
            atom.value.entries.firstOrNull { it.physicalSession === physical }?.let {
                val current = it.isCurrent()
                currentCoroutineContext().ensureActive()
                return if (current && (!freshExisting || refreshRegisteredAssociation(token, it))) it else null
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
        } finally {
            if (slotOwned) registrationSlot.release()
            registrationQueue.release()
        }
    }

    internal fun isCurrent(entry: LocalMasterRpcRegisteredPeer): Boolean {
        val token = registration.value
        val before = atom.value
        if (entry.registry !== this || denied.value || before.retired || !entry.permitsIdentity ||
            before.entries.none { it === entry }) return false
        var failureStage = LocalMasterRpcReadStage.PEER_OWNER_ENDED
        var diagnosticFailure: Throwable? = null
        val current = try {
            if (token == null || !ownsCurrent(token)) false
            else {
                failureStage = LocalMasterRpcReadStage.PEER_ASSOCIATION_ENDED
                if (!entry.association.belongsTo(owner) || !entry.association.isCurrent()) false
                else {
                    failureStage = LocalMasterRpcReadStage.PEER_SDK_LINK_ENDED
                    entry.physicalSession.isConnected && entry.physicalSession.closure.value == RpcPhysicalSessionClosure.Open
                }
            }
        } catch (failure: Throwable) { diagnosticFailure = failure; false }
        if (!current) {
            reportLocalMasterRpcReadFailure(failureStage, diagnosticFailure)
            entry.invalidate()
            return false
        }
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
            registration.value?.physicalInvocations?.close()
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
                    reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.MASTER_PHYSICAL_ENTRY_CLEANUP, Throwable())
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

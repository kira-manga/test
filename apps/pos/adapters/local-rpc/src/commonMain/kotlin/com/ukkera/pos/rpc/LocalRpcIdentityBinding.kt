package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterRpcPeerAssociation
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.TrustedPrincipalFactory
import dev.p2pkit.core.PeerIdentity
import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcAuthenticationContext
import dev.p2pkit.rpc.RpcAuthorizationContext
import dev.p2pkit.rpc.RpcAuthorizationScope
import dev.p2pkit.rpc.RpcCallContext
import dev.p2pkit.rpc.RpcPhysicalSession
import dev.p2pkit.rpc.RpcPhysicalSessionClosure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * Legacy identity-only provenance, never an RPC-body or discovery lookup.
 *
 * [PeerIdentity] is a descriptor, not a physical-lifetime token. Implementations must resolve the
 * actual authenticated physical connection of the SDK/native callback using genuine callback
 * context, and return null when that evidence is unavailable. A fingerprint-to-latest-connection
 * lookup is not sufficient. Use [LocalRpcPhysicalPeerProvenance] with the physical binding overload
 * for SDK callback-owned serving. Each physical lifetime has a distinct connection ID; all returned
 * descriptors belong to that same authenticated key. The returned peer's currentness must test
 * its exact captured lifetime and must never revive a replaced or closed lifetime.
 */
public fun interface LocalRpcPeerProvenance {
    public suspend fun capture(peer: PeerIdentity): LocalAuthenticatedPeer?
}

/**
 * Trusted native admission for the exact SDK-owned callback lifetime. Implementations must retain
 * that capability's identity, never resolve a newest connection by fingerprint or generation.
 * The returned POS connection ID and device/scope/key facts must belong to this same lifetime.
 * Missing native evidence returns null; the SDK capability alone is not POS device admission.
 */
public fun interface LocalRpcPhysicalPeerProvenance {
    public suspend fun capture(physicalSession: RpcPhysicalSession): LocalAuthenticatedPeer?
}

/** Internal owned-Host seam; not an application-supplied provenance or admission callback. */
internal fun interface LocalRpcAssociatedPhysicalPeerProvenance {
    suspend fun capture(physicalSession: RpcPhysicalSession): LocalRpcAssociatedPhysicalPeer?
}

/** Native admission and SDK identity remain separate, tied to one retained registry entry. */
internal class LocalRpcAssociatedPhysicalPeer(
    val nativePeer: LocalAuthenticatedPeer,
    val association: LocalMasterRpcPeerAssociation,
) {
    fun matches(peer: PeerIdentity): Boolean {
        val admission = association.admission
        val sdk = association.sdkIdentity
        return association.isCurrent() && nativePeer.isCurrent() && admission.admitted &&
            sdk.peerId == peer.peerId && sdk.fingerprint == peer.fingerprint &&
            nativePeer.scope == admission.scope && nativePeer.deviceId == admission.deviceId &&
            nativePeer.publicKeyIdentifier == admission.publicKeyIdentifier &&
            nativePeer.publicFingerprint == admission.publicFingerprint
    }

    override fun toString(): String = "LocalRpcAssociatedPhysicalPeer([REDACTED])"
}

/**
 * One original, freshly verified interactive login and its owned credential copy.
 *
 * Trusted composition retains this exact owner for that login, never substitutes a latest employee,
 * and must [retire] it on logout, replacement or physical-lifetime retirement. Physical bindings
 * close their exact SDK lifetime; native admission and lifecycle composition remain required.
 * Every scope uses one exact SDK application session.
 *
 * A successful check returns identity only. It grants no resource permission, Master transaction,
 * durable outcome or outbound commitment. Current Master checks and SDK revocation/commit ordering
 * remain required before private serving can be installed.
 */
public class LocalRpcIdentityBinding private constructor(
    private val originalSdkPeer: PeerIdentity,
    private val originalPhysicalPeer: LocalAuthenticatedPeer,
    private val originalPrincipal: LocalEmployeePrincipal,
    private val ownedBearer: OpaqueCredential,
    private val principalFactory: TrustedPrincipalFactory,
    private val provenance: LocalRpcPeerProvenance,
    private val applicationSession: RpcApplicationSession,
    private val originalRpcPhysicalSession: RpcPhysicalSession?,
    private val originalAssociation: LocalMasterRpcPeerAssociation?,
) {
    private val gate: Mutex = Mutex()
    private var retired: Boolean = false

    /** SDK-owned cleanup outcome, not a business rollback or remote receipt. Null for legacy binding. */
    public val physicalClosure: StateFlow<RpcPhysicalSessionClosure>?
        get() = originalRpcPhysicalSession?.closure

    /**
     * Fresh original-identity check before constructing immutable, bounded resource metadata.
     * Legacy identity-only entry; a physical binding refuses it. The resource label is not
     * authorization, and must never contain a bearer secret.
     */
    public suspend fun authorizationScope(peer: PeerIdentity, resource: String): RpcAuthorizationScope? {
        currentCoroutineContext().ensureActive()
        if (originalRpcPhysicalSession != null) return null
        return withOriginalPrincipal(peer, null) { RpcAuthorizationScope(applicationSession, resource) }
    }

    /** Authentication entry point for registerScopedWithPhysicalSession; never follows a successor. */
    public suspend fun authorizationScope(
        context: RpcAuthenticationContext,
        resource: String,
    ): RpcAuthorizationScope? {
        currentCoroutineContext().ensureActive()
        if (originalRpcPhysicalSession == null) return null
        return withOriginalPrincipal(context.peer, context.physicalSession) {
            RpcAuthorizationScope(applicationSession, resource)
        }
    }

    /** Original immutable metadata only, not a fresh authorization or resource grant. */
    internal fun originalIdentityFor(scope: RpcAuthorizationScope): LocalEmployeePrincipal? =
        originalPrincipal.takeIf { scope.session === applicationSession }

    /**
     * Reauthenticate the original credentials for this exact owner session and callback peer.
     * A matching session ID on another SDK session instance is not this binding.
     */
    public suspend fun currentOriginalPrincipal(context: RpcAuthorizationContext): LocalEmployeePrincipal? {
        currentCoroutineContext().ensureActive()
        if (context.scope.session !== applicationSession) return null
        // rc10 also supplies physical metadata to legacy scoped registrations. Preserve their
        // identity-only check; it does not qualify native physical provenance or private serving.
        if (originalRpcPhysicalSession == null) return withOriginalPrincipal(context.peer, null) { it }
        if (context.originalPhysicalSession !== originalRpcPhysicalSession) return null
        return withOriginalPrincipal(context.peer, context.physicalSession) { it }
    }

    /** Original handler identity only; the Master still authorizes the protected operation. */
    public suspend fun currentOriginalPrincipal(context: RpcCallContext): LocalEmployeePrincipal? {
        currentCoroutineContext().ensureActive()
        if (originalRpcPhysicalSession == null || context.authorizationScope?.session !== applicationSession) return null
        return withOriginalPrincipal(context.peer, context.physicalSession) { it }
    }

    /** Private Host loan after a fresh exact original check; the caller always destroys its copy. */
    internal suspend fun currentOriginalCredentials(context: RpcAuthorizationContext): LocalGatewayCredentials? {
        if (context.scope.session !== applicationSession || originalRpcPhysicalSession == null ||
            context.originalPhysicalSession !== originalRpcPhysicalSession) return null
        return withOriginalPrincipal(context.peer, context.physicalSession) {
            LocalGatewayCredentials(originalPhysicalPeer.connectionId, ownedBearer.useBytes(::OpaqueCredential))
        }
    }

    internal suspend fun currentOriginalCredentials(context: RpcCallContext): LocalGatewayCredentials? {
        if (context.authorizationScope?.session !== applicationSession || originalRpcPhysicalSession == null) return null
        return withOriginalPrincipal(context.peer, context.physicalSession) {
            LocalGatewayCredentials(originalPhysicalPeer.connectionId, ownedBearer.useBytes(::OpaqueCredential))
        }
    }

    /**
     * Irreversible local retirement and release of the owned bearer, followed by SDK revocation.
     * In-flight verifiers own disposable copies; retirement winning the final check prevents their
     * publication. Physical bindings also await exact SDK teardown; failure remains observable in
     * [physicalClosure] and propagates without reviving the login. This is not a business-operation
     * or already-committed-output rollback and does not wait for host-owned handlers.
     */
    @Throws(Exception::class)
    public suspend fun retire(): Unit {
        val callerContext = currentCoroutineContext()
        withContext(NonCancellable) {
            gate.withLock {
                if (!retired) {
                    retired = true
                    ownedBearer.destroy()
                }
            }
            applicationSession.revoke()
            originalRpcPhysicalSession?.close()
        }
        callerContext.ensureActive()
    }

    /** Release a naturally expired/disconnected login without claiming physical teardown.
     * Public explicit retirement still closes the exact SDK lifetime. */
    internal suspend fun releaseExpiredApplicationSession(): Unit = withContext(NonCancellable) {
        check(originalRpcPhysicalSession?.isConnected == false || !applicationSession.isActive)
        gate.withLock {
            retired = true
            ownedBearer.destroy()
        }
        applicationSession.revoke()
    }

    private suspend fun <T> withOriginalPrincipal(
        peer: PeerIdentity,
        rpcPhysicalSession: RpcPhysicalSession?,
        publish: (LocalEmployeePrincipal) -> T,
    ): T? {
        var disposableBearer: OpaqueCredential? = null
        return try {
            currentCoroutineContext().ensureActive()
            if (peer != originalSdkPeer) return null
            if (rpcPhysicalSession !== originalRpcPhysicalSession || !rpcPhysicalCurrent()) return null
            val bearer = gate.withLock {
                if (retired || !applicationSession.isActive || !rpcPhysicalCurrent() || !originalPhysicalPeer.isCurrent()) null
                else ownedBearer.useBytes(::OpaqueCredential)
            } ?: return null
            disposableBearer = bearer

            val physicalPeer = provenance.capture(peer)
            currentCoroutineContext().ensureActive()
            if (physicalPeer == null) return null
            if (!sameCurrentPhysicalPeer(originalPhysicalPeer, physicalPeer)) return null

            val principal = principalFactory.authenticateLocal(
                LocalGatewayCredentials(originalPhysicalPeer.connectionId, bearer),
            )
            currentCoroutineContext().ensureActive()
            if (principal == null) return null
            if (!sameOriginalPrincipal(originalPrincipal, principal)) return null

            currentCoroutineContext().ensureActive()
            gate.withLock {
                if (retired || !applicationSession.isActive || !rpcPhysicalCurrent() ||
                    !sameCurrentPhysicalPeer(originalPhysicalPeer, physicalPeer)
                ) null
                else publish(principal)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            disposableBearer?.destroy()
        }
    }

    private fun rpcPhysicalCurrent(): Boolean = originalRpcPhysicalSession?.isConnected != false &&
        originalAssociation?.isCurrent() != false

    override fun toString(): String = "LocalRpcIdentityBinding([REDACTED])"

    public companion object {
        /**
         * Capture a private copy, actual physical provenance and a freshly verified local principal.
         * Caller-owned credentials are never passed to a source or destroyed by this adapter.
         *
         * [lifetime] is a required positive finite cap supplied by trusted composition from current
         * local session policy, never request metadata. SDK elapsed time is not assumed equivalent
         * to the POS authority clock; the original credentials are freshly verified on every check.
         * A later login requires a new owner after retirement, even if identifiers happen to match.
         * This descriptor-only compatibility entry does not capture an SDK physical capability;
         * private physical serving must use the [RpcAuthenticationContext] overload instead.
         */
        public suspend fun bind(
            peer: PeerIdentity,
            credentials: LocalGatewayCredentials,
            principalFactory: TrustedPrincipalFactory,
            provenance: LocalRpcPeerProvenance,
            lifetime: Duration,
        ): LocalRpcIdentityBinding? = bindOriginal(peer, credentials, principalFactory, provenance, lifetime, null)

        /**
         * Bind the genuine authenticated callback capability before native verification can suspend.
         * Native provenance must join this exact capability to current protected POS admission.
         * Caller-supplied connection IDs are compared, never accepted as transport evidence.
         */
        public suspend fun bind(
            context: RpcAuthenticationContext,
            credentials: LocalGatewayCredentials,
            principalFactory: TrustedPrincipalFactory,
            provenance: LocalRpcPhysicalPeerProvenance,
            lifetime: Duration,
        ): LocalRpcIdentityBinding? {
            currentCoroutineContext().ensureActive()
            val physical = context.physicalSession
            if (physical.peer != context.peer || !physical.isConnected) return null
            return bindOriginal(context.peer, credentials, principalFactory,
                LocalRpcPeerProvenance { peer ->
                    if (peer != physical.peer || !physical.isConnected) null else provenance.capture(physical)
                }, lifetime, physical)
        }

        /**
         * Only the owned Host callback/registry may supply this additive association route.
         * It does not reinterpret the legacy native fingerprint as an SDK fingerprint.
         */
        internal suspend fun bindAssociated(
            context: RpcAuthenticationContext,
            credentials: LocalGatewayCredentials,
            principalFactory: TrustedPrincipalFactory,
            provenance: LocalRpcAssociatedPhysicalPeerProvenance,
            lifetime: Duration,
        ): LocalRpcIdentityBinding? {
            try {
                currentCoroutineContext().ensureActive()
                if (!lifetime.isPositive() || !lifetime.isFinite()) return null
                val physical = context.physicalSession
                if (physical.peer != context.peer || !physical.isConnected) return null
                val original = provenance.capture(physical)
                currentCoroutineContext().ensureActive()
                if (original == null || !physical.isConnected || !original.matches(context.peer)) return null
                return bindOriginal(context.peer, credentials, principalFactory,
                    LocalRpcPeerProvenance { peer ->
                        if (peer != physical.peer || !physical.isConnected || !original.matches(peer)) null
                        else {
                            val current = provenance.capture(physical)
                            currentCoroutineContext().ensureActive()
                            if (current == null || current.nativePeer !== original.nativePeer ||
                                current.association !== original.association || !current.matches(peer) ||
                                !physical.isConnected) null else current.nativePeer
                        }
                    }, lifetime, physical, original.association)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return null
            }
        }

        private suspend fun bindOriginal(
            peer: PeerIdentity,
            credentials: LocalGatewayCredentials,
            principalFactory: TrustedPrincipalFactory,
            provenance: LocalRpcPeerProvenance,
            lifetime: Duration,
            rpcPhysicalSession: RpcPhysicalSession?,
            association: LocalMasterRpcPeerAssociation? = null,
        ): LocalRpcIdentityBinding? {
            var retainedBearer: OpaqueCredential? = null
            var unownedSession: RpcApplicationSession? = null
            return try {
                currentCoroutineContext().ensureActive()
                if (!lifetime.isPositive() || !lifetime.isFinite()) return null
                if (rpcPhysicalSession?.isConnected == false) return null
                val fingerprint = peer.fingerprint ?: return null
                val physicalPeer = provenance.capture(peer)
                currentCoroutineContext().ensureActive()
                if (physicalPeer == null) return null
                if (physicalPeer.connectionId != credentials.connectionId || !physicalPeer.isCurrent()) return null
                if (association == null) {
                    // Preserve both existing public compatibility routes without a permissive fallback.
                    if (physicalPeer.publicFingerprint != fingerprint.value) return null
                } else {
                    if (rpcPhysicalSession == null ||
                        !LocalRpcAssociatedPhysicalPeer(physicalPeer, association).matches(peer)) return null
                }

                val bearer = credentials.bearer.useBytes(::OpaqueCredential)
                retainedBearer = bearer
                val principal = authenticateDisposable(
                    principalFactory, LocalGatewayCredentials(physicalPeer.connectionId, bearer),
                )
                currentCoroutineContext().ensureActive()
                if (principal == null) return null
                if (principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE ||
                    principal.scope != physicalPeer.scope || principal.deviceId != physicalPeer.deviceId
                ) return null

                val currentPeer = provenance.capture(peer)
                currentCoroutineContext().ensureActive()
                if (currentPeer == null) return null
                if (!sameCurrentPhysicalPeer(physicalPeer, currentPeer) || rpcPhysicalSession?.isConnected == false) return null

                val session = RpcApplicationSession(
                    peer = fingerprint,
                    principal = principal.actorId.value,
                    sessionId = principal.sessionId.value,
                    lifetime = lifetime,
                )
                unownedSession = session
                currentCoroutineContext().ensureActive()
                if (!session.isActive || !sameCurrentPhysicalPeer(physicalPeer, currentPeer) ||
                    rpcPhysicalSession?.isConnected == false || association?.isCurrent() == false) return null

                val binding = LocalRpcIdentityBinding(
                    peer, physicalPeer, principal, bearer, principalFactory, provenance, session, rpcPhysicalSession,
                    association,
                )
                retainedBearer = null
                unownedSession = null
                binding
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                retainedBearer?.destroy()
                unownedSession?.let {
                    it.revoke()
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }
}

private suspend fun authenticateDisposable(
    principalFactory: TrustedPrincipalFactory,
    credentials: LocalGatewayCredentials,
): LocalEmployeePrincipal? {
    val bearer = credentials.bearer.useBytes(::OpaqueCredential)
    return try {
        val principal = principalFactory.authenticateLocal(LocalGatewayCredentials(credentials.connectionId, bearer))
        currentCoroutineContext().ensureActive()
        principal
    } finally {
        bearer.destroy()
    }
}

private fun sameCurrentPhysicalPeer(original: LocalAuthenticatedPeer, current: LocalAuthenticatedPeer): Boolean =
    current.connectionId == original.connectionId && current.scope == original.scope &&
        current.deviceId == original.deviceId && current.publicKeyIdentifier == original.publicKeyIdentifier &&
        current.publicFingerprint == original.publicFingerprint && original.isCurrent() && current.isCurrent()

private fun sameOriginalPrincipal(original: LocalEmployeePrincipal, current: LocalEmployeePrincipal): Boolean =
    current.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
        current.authenticationPurpose == original.authenticationPurpose && current.scope == original.scope &&
        current.actorId == original.actorId && current.deviceId == original.deviceId &&
        current.sessionId == original.sessionId && current.sessionGeneration == original.sessionGeneration &&
        current.admissionRevision == original.admissionRevision

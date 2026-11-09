package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.database.LocalMasterClientRpcBridgeV1
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcClient
import dev.p2pkit.rpc.RpcConnectionState
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcHost
import dev.p2pkit.rpc.RpcHostState
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcReply
import dev.p2pkit.rpc.RpcRetry
import dev.p2pkit.rpc.RpcSelectedHost
import dev.p2pkit.rpc.RpcTrustPurpose
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.transport.lan.OrganizationLan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Identity ownership phase; READY does not itself assert activated transport. */
public enum class LocalRpcFactoryPhase { NEW, ACQUIRING, READY, CLOSING, CLOSED, UNCERTAIN }

/**
 * Retains one actual official RPC factory role for native identity acquisition/reconstruction.
 * All policy and lifetime handling is shared; the platform supplies its genuine RpcPlatform,
 * retained application lifetime, explicit LAN and local durable trust store.
 *
 * Its public identity-only boundary exposes no raw role or networking. Internal activation is
 * confined to the service-retained original Host and the qualified selected-original Client.
 * Public identity correspondence is not native custody, Master selection, device admission or
 * employee authority. Native composition must retain this owner before calling [acquire], and
 * independently persist/qualify its original before enabling RPC traffic.
 *
 * An abandoned waiter does not abandon construction or an admitted transport operation. Close
 * first denies publication, drains that acquisition/start/connect/endpoint work, and awaits the
 * exact role's close. An uncertain constructor without a returned handle or failed close remains
 * retained and unavailable; no automatic replacement is allocated.
 */
public class LocalRpcFactoryOwner private constructor(
    private val platform: RpcPlatform,
    applicationScope: CoroutineScope,
    private val appId: AppId,
    private val exactDeviceName: String,
    private val lan: OrganizationLan,
    private val trustStore: RpcTrustStore,
    private val host: Boolean,
    private val matchesOriginal: ((RpcLocalIdentity) -> Boolean)?,
    private val sessionIngress: LocalRpcMasterSessionIngress? = null,
    private val servingCurrent: (() -> Boolean)? = null,
) {
    private val gate: Mutex = Mutex()
    private val state: MutableStateFlow<LocalRpcFactoryPhase> = MutableStateFlow(LocalRpcFactoryPhase.NEW)
    public val phase: StateFlow<LocalRpcFactoryPhase> = state.asStateFlow()
    private val parent: Job = requireNotNull(applicationScope.coroutineContext[Job]) {
        "A retained application lifetime is required"
    }.also {
        require(it.isActive) { "The application lifetime is inactive" }
        LocalDevicePairingProfileV2.validateAcquisitionText(appId.value)
        LocalDevicePairingProfileV2.validateAcquisitionText(exactDeviceName)
    }
    // Independent of a requesting coroutine, but watched/drained by the retained parent lifetime.
    private val ownerJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(applicationScope.coroutineContext.minusKey(Job) + ownerJob)
    private var acquisition: Deferred<RpcLocalIdentity>? = null
    private var starting: Deferred<Unit>? = null
    private var endpointRead: Deferred<RpcEndpoint>? = null
    private var connecting: Deferred<Unit>? = null
    private var authenticationCalls: Int = 0 // Shared selected-Client call loans, including directory work.
    private var directoryBridge: LocalMasterClientRpcBridgeV1? = null
    private val authenticationDrained: CompletableDeferred<Unit> = CompletableDeferred()
    private var connectionTarget: RpcSelectedHost? = null
    private var closing: Deferred<Unit>? = null
    private var role: OwnedRole? = null
    // Published only after exact selected-original negotiation; never exposes the SDK role.
    private val selectedConnection = MutableStateFlow<OwnedRole.Client?>(null)
    private var constructorEntered: Boolean = false
    private val retired: CompletableDeferred<Unit> = CompletableDeferred()

    init {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                retired.await()
            } finally {
                // Parent cancellation is a request for actual close, not proof of native cleanup.
                withContext(NonCancellable) {
                    try { closeOwned() }
                    catch (_: Exception) { /* Phase/resources remain UNCERTAIN; this is not successful close. */ }
                }
            }
        }
    }

    /** Read actual factory-owned identity, without activating any network or POS authority. */
    public suspend fun acquire(): RpcLocalIdentity {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            check(parent.isActive && state.value in setOf(LocalRpcFactoryPhase.NEW,
                LocalRpcFactoryPhase.ACQUIRING, LocalRpcFactoryPhase.READY)) { "RPC identity owner is unavailable" }
            acquisition ?: scope.async(start = CoroutineStart.LAZY) { acquireOwned() }.also {
                acquisition = it
                state.value = LocalRpcFactoryPhase.ACQUIRING
                it.start()
            }
        }
        val identity = pending.await()
        currentCoroutineContext().ensureActive()
        gate.withLock {
            check(parent.isActive && state.value == LocalRpcFactoryPhase.READY) { "RPC identity owner is retired" }
        }
        return identity
    }

    /** Only the privately retained original-service route can activate this exact Host. */
    internal suspend fun startServingHost() {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            requireAvailable()
            requireServingHost()
            starting ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    acquire()
                    val retained = gate.withLock {
                        requireReady()
                        requireServingHost()
                        checkNotNull(role as? OwnedRole.Host) { "Original RPC Host is unavailable" }
                    }
                    // This store only asserts the current complete durable native set. It
                    // cannot enroll peers or repair a stale SDK cache before activation.
                    trustStore.replace(appId, RpcTrustPurpose.HostClients, retained.trustedFingerprints)
                    gate.withLock { requireReady(); requireServingHost() }
                    retained.start()
                    gate.withLock {
                        requireReady()
                        requireServingHost()
                        check(retained.state == RpcHostState.Running) { "Original RPC Host is not running" }
                    }
                } catch (failure: Throwable) {
                    retireFailedOperation()
                    throw failure
                }
            }.also { starting = it; it.start() }
        }
        pending.await()
        currentCoroutineContext().ensureActive()
        assertServingHostRunning()
    }

    /** Observe the exact SDK role, including when an original-owner startup result was cached. */
    internal suspend fun assertServingHostRunning() {
        currentCoroutineContext().ensureActive()
        val running = gate.withLock {
            requireReady()
            requireServingHost()
            (role as? OwnedRole.Host)?.state == RpcHostState.Running
        }
        if (!running) {
            withContext(NonCancellable) { retireFailedOperation() }
            error("Original RPC Host is not running")
        }
        currentCoroutineContext().ensureActive()
    }

    /** Fresh numeric information from the same explicitly started, retained official Host. */
    internal suspend fun servingEndpoint(): RpcEndpoint {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            requireAvailable()
            requireServingHost()
            val startup = checkNotNull(starting) { "Original RPC Host has not been started" }
            endpointRead?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    startup.await()
                    val retained = gate.withLock {
                        requireReady()
                        requireServingHost()
                        checkNotNull(role as? OwnedRole.Host) { "Original RPC Host is unavailable" }
                    }
                    val endpoint = retained.endpoint()
                    gate.withLock {
                        requireReady()
                        requireServingHost()
                        check(retained.state == RpcHostState.Running) { "Original RPC Host is not running" }
                    }
                    endpoint
                } catch (failure: Throwable) {
                    retireFailedOperation()
                    throw failure
                }
            }.also { endpointRead = it; it.start() }
        }
        val endpoint = pending.await()
        currentCoroutineContext().ensureActive()
        assertServingHostRunning()
        return endpoint
    }

    /** One exact numeric selection, only with the internally qualified original-Master store. */
    internal suspend fun connectSelectedHost(selectedHost: RpcSelectedHost) {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            requireAvailable()
            check(!host && trustStore is LocalRpcSelectedMasterTrustStore) {
                "Selected-original RPC Client is unavailable"
            }
            connectionTarget?.let { original ->
                check(original.fingerprint == selectedHost.fingerprint &&
                    original.endpoint.address == selectedHost.endpoint.address &&
                    original.endpoint.port == selectedHost.endpoint.port) { "Original Master selection differs" }
            }
            connecting ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    acquire()
                    val retained = gate.withLock {
                        requireReady()
                        checkNotNull(role as? OwnedRole.Client) { "Selected-original RPC Client is unavailable" }
                    }
                    trustStore.replace(appId, RpcTrustPurpose.SelectedHosts, setOf(selectedHost.fingerprint))
                    gate.withLock { requireReady() }
                    retained.connect(selectedHost)
                    // The selected native owner must still be current after actual negotiation.
                    trustStore.replace(appId, RpcTrustPurpose.SelectedHosts, setOf(selectedHost.fingerprint))
                    gate.withLock {
                        requireReady()
                        check(retained.state == RpcConnectionState.Ready) { "Original RPC Client is not connected" }
                        selectedConnection.value = retained
                    }
                } catch (failure: Throwable) {
                    retireFailedOperation()
                    throw failure
                }
            }.also { connectionTarget = selectedHost; connecting = it; it.start() }
        }
        pending.await()
        currentCoroutineContext().ensureActive()
        trustStore.replace(appId, RpcTrustPurpose.SelectedHosts, setOf(selectedHost.fingerprint))
        val ready = gate.withLock {
            requireReady()
            (role as? OwnedRole.Client)?.state == RpcConnectionState.Ready
        }
        if (!ready) {
            withContext(NonCancellable) { retireFailedOperation() }
            error("Original RPC Client is not connected")
        }
        currentCoroutineContext().ensureActive()
    }

    /** Local lifetime sample, not admission/session/permission authority or a connection attempt. */
    internal fun selectedClientConnectedNow(): Boolean = parent.isActive &&
        state.value == LocalRpcFactoryPhase.READY && selectedConnection.value?.state == RpcConnectionState.Ready

    /** Observe the existing exact connection; this cannot start a connection or repair trust. */
    internal suspend fun assertSelectedClientConnected() {
        currentCoroutineContext().ensureActive()
        gate.withLock { requireSelectedClientConnected() }
        currentCoroutineContext().ensureActive()
    }

    /**
     * Private common call loan from the selected-original owner. Close denies new loans and drains
     * every admitted invocation before touching the exact SDK role. The outer original owner
     * separately retains complete result mapping/delivery, including abandoned interactive callers.
     */
    internal suspend fun callSelectedClientAuthentication(
        request: LocalRpcAuthenticationRequest,
    ): LocalRpcAuthenticationResponse? {
        var admitted = false
        var codec: ClientAuthenticationCodec? = null
        try {
            currentCoroutineContext().ensureActive()
            require(request.validateShape()) { "Authentication request is unavailable" }
            val retained = gate.withLock {
                requireSelectedClientConnected()
                checkNotNull(role as? OwnedRole.Client).also {
                    authenticationCalls++
                    admitted = true
                }
            }
            val exchangeCodec = ClientAuthenticationCodec(request).also { codec = it }
            val reply = retained.authenticationCall(exchangeCodec.procedure, request)
            withContext(NonCancellable) {
                gate.withLock { releaseAuthenticationCall() }
                admitted = false
            }
            gate.withLock {
                currentCoroutineContext().ensureActive()
                requireSelectedClientConnected()
                check(role === retained) { "Selected original RPC Client differs" }
            }
            return when (reply) {
                is RpcReply.Success -> exchangeCodec.transfer(reply.value)
                is RpcReply.ApplicationError -> null
            }
        } finally {
            // Includes an SDK-decoded result abandoned by its own timeout/cancellation boundary.
            codec?.destroy()
            request.destroy()
            if (admitted) withContext(NonCancellable) { gate.withLock { releaseAuthenticationCall() } }
        }
    }

    /** The SDK role stays private; only its owning module's fixed invocation bridge receives it. */
    internal suspend fun retainSupplierBridge(original: LocalDevicePairingSdkOriginal,
        approved: LocalDevicePairingApprovedMaster, lifetime: Job, connectionId: DomainId): LocalMasterClientRpcBridgeV1 =
        retainDirectoryBridge(original, approved, lifetime, connectionId, LocalMasterRpcWorkflowProfile.SUPPLIERS)

    /** One immutable directory selection belongs to the exact retained selected Client. */
    internal suspend fun retainDirectoryBridge(original: LocalDevicePairingSdkOriginal,
        approved: LocalDevicePairingApprovedMaster, lifetime: Job, connectionId: DomainId,
        profile: LocalMasterRpcWorkflowProfile): LocalMasterClientRpcBridgeV1 =
        gate.withLock {
            currentCoroutineContext().ensureActive()
            requireSelectedClientConnected()
            val retained = directoryBridge
            if (retained != null) {
                check(retained.profile == profile) { "Directory bridge profile differs" }
                check(retained.isCurrent()) { "Directory bridge owner differs" }
                retained
            } else checkNotNull(role as? OwnedRole.Client)
                .directoryBridge(original, approved, lifetime, connectionId, profile).also { directoryBridge = it }
        }

    internal suspend fun <T : Any> withSupplierBridge(bridge: LocalMasterClientRpcBridgeV1,
        call: suspend (LocalMasterClientRpcBridgeV1) -> T): T {
        currentCoroutineContext().ensureActive()
        check(bridge.profile == LocalMasterRpcWorkflowProfile.SUPPLIERS) { "Supplier bridge profile differs" }
        return withDirectoryBridge(bridge, call)
    }

    internal suspend fun <T : Any> withDirectoryBridge(bridge: LocalMasterClientRpcBridgeV1,
        call: suspend (LocalMasterClientRpcBridgeV1) -> T): T {
        var admitted = false
        try {
            val retained = gate.withLock {
                currentCoroutineContext().ensureActive()
                requireSelectedClientConnected()
                check(directoryBridge === bridge && bridge.isCurrent()) { "Directory bridge owner differs" }
                checkNotNull(role as? OwnedRole.Client).also { authenticationCalls++; admitted = true }
            }
            val result = call(bridge)
            gate.withLock {
                currentCoroutineContext().ensureActive()
                requireSelectedClientConnected()
                check(role === retained && directoryBridge === bridge && bridge.isCurrent()) { "Directory bridge owner differs" }
            }
            return result
        } finally {
            if (admitted) withContext(NonCancellable) { gate.withLock { releaseAuthenticationCall() } }
        }
    }

    /** Caller cancellation cannot abandon actual close or release uncertain native ownership. */
    public suspend fun close() {
        val caller = currentCoroutineContext()
        withContext(NonCancellable) { closeOwned() }
        caller.ensureActive()
    }

    private suspend fun acquireOwned(): RpcLocalIdentity {
        try {
            gate.withLock {
                check(parent.isActive && state.value == LocalRpcFactoryPhase.ACQUIRING)
                constructorEntered = true
            }
            val created = if (host) {
                OwnedRole.Host(RpcHost.create(platform, scope) {
                    appId = this@LocalRpcFactoryOwner.appId
                    deviceName = exactDeviceName
                    lan = this@LocalRpcFactoryOwner.lan
                    trustStore = this@LocalRpcFactoryOwner.trustStore
                    advertise = false
                    allowNearbyPairing = false
                    sessionIngress?.install(this)
                })
            } else {
                OwnedRole.Client(RpcClient.create(platform, scope) {
                    appId = this@LocalRpcFactoryOwner.appId
                    deviceName = exactDeviceName
                    lan = this@LocalRpcFactoryOwner.lan
                    trustStore = this@LocalRpcFactoryOwner.trustStore
                    transportReconnect = false
                })
            }
            // Retain the exact returned role before inspecting its identity or publishing a result.
            gate.withLock { role = created }
            val actual = created.identity
            check(actual.appId == appId && actual.deviceName == exactDeviceName) { "RPC identity differs" }
            check(matchesOriginal?.invoke(actual) != false) { "Original RPC identity differs" }
            gate.withLock {
                check(parent.isActive && state.value == LocalRpcFactoryPhase.ACQUIRING) { "RPC identity owner is retired" }
                state.value = LocalRpcFactoryPhase.READY
            }
            return actual
        } catch (failure: Throwable) {
            gate.withLock {
                if (state.value != LocalRpcFactoryPhase.CLOSING) state.value = LocalRpcFactoryPhase.UNCERTAIN
            }
            // A returned rejected role can be closed; an unreturned factory cannot prove teardown.
            // The same original close worker owns cleanup, including after lost delivery.
            // Schedule, never await our own acquisition from its cleanup worker.
            beginClose()
            throw failure
        }
    }

    private suspend fun closeOwned() {
        val pending = beginClose()
        pending.await()
        ownerJob.cancelAndJoin()
    }

    private fun requireAvailable() {
        check(parent.isActive && state.value in setOf(LocalRpcFactoryPhase.NEW,
            LocalRpcFactoryPhase.ACQUIRING, LocalRpcFactoryPhase.READY)) { "RPC identity owner is unavailable" }
    }

    private fun requireReady() {
        check(parent.isActive && state.value == LocalRpcFactoryPhase.READY) { "RPC identity owner is retired" }
    }

    private fun requireServingHost() {
        check(host && sessionIngress != null && trustStore is LocalRpcTrustStore &&
            servingCurrent?.invoke() == true) { "Original-service RPC Host is unavailable" }
    }

    private fun requireSelectedClientConnected() {
        requireReady()
        check(!host && trustStore is LocalRpcSelectedMasterTrustStore && connectionTarget != null &&
            connecting?.isCompleted == true && connecting?.isCancelled == false &&
            (role as? OwnedRole.Client)?.state == RpcConnectionState.Ready) {
            "Selected-original RPC Client is not connected"
        }
    }

    private fun releaseAuthenticationCall() {
        check(authenticationCalls > 0) { "Authentication call ownership differs" }
        authenticationCalls--
        if (closing != null && authenticationCalls == 0) authenticationDrained.complete(Unit)
    }

    private suspend fun retireFailedOperation() {
        gate.withLock {
            if (state.value != LocalRpcFactoryPhase.CLOSING && state.value != LocalRpcFactoryPhase.CLOSED) {
                state.value = LocalRpcFactoryPhase.UNCERTAIN
            }
        }
        // Do not await the cleanup worker from an operation that cleanup itself drains.
        beginClose()
    }

    private suspend fun beginClose(): Deferred<Unit> = gate.withLock {
        closing ?: scope.async<Unit>(start = CoroutineStart.LAZY) {
            val creating = gate.withLock { acquisition }
            // Do not cancel factory work: it can own resources before returning a handle.
            creating?.join()
            val operations = gate.withLock { listOfNotNull<Job>(starting, endpointRead, connecting) }
            operations.forEach { it.join() }
            gate.withLock { if (authenticationCalls == 0) authenticationDrained.complete(Unit) }
            authenticationDrained.await()
            val retainedRole = gate.withLock { role }
            try {
                check(retainedRole != null || !constructorEntered) { "RPC construction cleanup is uncertain" }
                sessionIngress?.closeOwned()
                retainedRole?.close()
                gate.withLock {
                    role = null
                    state.value = LocalRpcFactoryPhase.CLOSED
                }
                retired.complete(Unit)
            } catch (failure: Throwable) {
                gate.withLock { state.value = LocalRpcFactoryPhase.UNCERTAIN }
                throw failure
            }
        }.also {
            closing = it
            selectedConnection.value = null
            directoryBridge?.deny()
            state.value = LocalRpcFactoryPhase.CLOSING
            sessionIngress?.deny()
            it.start()
        }
    }

    override fun toString(): String = "LocalRpcFactoryOwner([REDACTED])"

    public companion object {
        public fun captureHost(platform: RpcPlatform, applicationScope: CoroutineScope, appId: AppId,
            exactDeviceName: String, lan: OrganizationLan, trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, appId, exactDeviceName, lan, trustStore, true, null)

        public fun captureClient(platform: RpcPlatform, applicationScope: CoroutineScope, appId: AppId,
            exactDeviceName: String, lan: OrganizationLan, trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, appId, exactDeviceName, lan, trustStore, false, null)

        /** Private original-service serving route; callers cannot install arbitrary callbacks. */
        internal fun reconstructServingHost(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: LocalMasterSdkOriginalV1, lan: OrganizationLan, trustStore: RpcTrustStore,
            ingress: LocalRpcMasterSessionIngress, isCurrent: () -> Boolean): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, original.appId, original.exactDeviceName,
                lan, trustStore, true, { actual ->
                    original.matchesSdkTransportIdentity(actual.appId, actual.peerId, actual.fingerprint,
                        actual.pairingQr) && actual.deviceName == original.exactDeviceName
                }, ingress, isCurrent)

        /** A real Master original, never a substituted Client pairing descriptor or native key. */
        public fun reconstructHost(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: LocalMasterSdkOriginalV1, lan: OrganizationLan,
            trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, original.appId, original.exactDeviceName,
                lan, trustStore, true, { actual ->
                    original.matchesSdkTransportIdentity(actual.appId, actual.peerId, actual.fingerprint,
                        actual.pairingQr) && actual.deviceName == original.exactDeviceName
                })

        /** Exact original DATA/name readback; custody, admission and current authority remain separate. */
        public fun reconstructHost(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: LocalDevicePairingSdkOriginal, lan: OrganizationLan,
            trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            reconstructHost(platform, applicationScope, original.identity, original.exactDeviceName,
                lan, trustStore)

        /** Exact original DATA/name readback; this does not select or trust a Master. */
        public fun reconstructClient(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: LocalDevicePairingSdkOriginal, lan: OrganizationLan,
            trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            reconstructClient(platform, applicationScope, original.identity, original.exactDeviceName,
                lan, trustStore)

        public fun reconstructHost(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: LocalDevicePairingSdkIdentityV2, exactOriginalDeviceName: String,
            lan: OrganizationLan, trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, original.appId, exactOriginalDeviceName,
                lan, trustStore, true, nativeExpectation(original))

        public fun reconstructClient(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: LocalDevicePairingSdkIdentityV2, exactOriginalDeviceName: String,
            lan: OrganizationLan, trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, original.appId, exactOriginalDeviceName,
                lan, trustStore, false, nativeExpectation(original))

        /** Same-process original DATA from an actual factory; not durable native admission. */
        public fun reconstructHost(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: RpcLocalIdentity, lan: OrganizationLan, trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, original.appId, original.deviceName,
                lan, trustStore, true, snapshotExpectation(original))

        /** Same-process original DATA from an actual factory; not a selected-Master trust grant. */
        public fun reconstructClient(platform: RpcPlatform, applicationScope: CoroutineScope,
            original: RpcLocalIdentity, lan: OrganizationLan, trustStore: RpcTrustStore): LocalRpcFactoryOwner =
            LocalRpcFactoryOwner(platform, applicationScope, original.appId, original.deviceName,
                lan, trustStore, false, snapshotExpectation(original))

        private fun nativeExpectation(original: LocalDevicePairingSdkIdentityV2): (RpcLocalIdentity) -> Boolean =
            { actual -> original.matchesSdkTransportIdentity(actual.appId, actual.peerId,
                actual.fingerprint, actual.pairingQr) }

        private fun snapshotExpectation(original: RpcLocalIdentity): (RpcLocalIdentity) -> Boolean =
            { actual -> actual.appId == original.appId && actual.peerId == original.peerId &&
                actual.fingerprint == original.fingerprint && actual.pairingQr == original.pairingQr &&
                actual.deviceName == original.deviceName }
    }
}

private sealed interface OwnedRole {
    val identity: RpcLocalIdentity
    suspend fun close()

    class Host(private val value: RpcHost): OwnedRole {
        override val identity: RpcLocalIdentity get() = value.localIdentity
        val state: RpcHostState get() = value.state.value
        val trustedFingerprints: Set<PeerFingerprint> get() = value.trust.fingerprints().toSet()
        suspend fun start() { value.start() }
        suspend fun endpoint(): RpcEndpoint = value.endpoint()
        override suspend fun close() { value.close() }
    }
    class Client(private val value: RpcClient): OwnedRole {
        override val identity: RpcLocalIdentity get() = value.localIdentity
        val state: RpcConnectionState get() = value.state.value
        suspend fun connect(host: RpcSelectedHost) { value.connect(host) }
        suspend fun authenticationCall(
            procedure: RpcProcedure<LocalRpcAuthenticationRequest, LocalRpcAuthenticationResponse, LocalRpcAuthenticationError>,
            request: LocalRpcAuthenticationRequest,
        ): RpcReply<LocalRpcAuthenticationResponse, LocalRpcAuthenticationError> =
            value.call(procedure, request, retry = RpcRetry.RecoverOnly(maxAttempts = 1))
        fun directoryBridge(original: LocalDevicePairingSdkOriginal, approved: LocalDevicePairingApprovedMaster,
            lifetime: Job, connectionId: DomainId, profile: LocalMasterRpcWorkflowProfile): LocalMasterClientRpcBridgeV1 =
            LocalMasterClientRpcBridgeV1.bind(value, original, approved, lifetime, connectionId, profile)
        override suspend fun close() { value.close() }
    }
}

/** Per-call ownership wrappers preserve the selected procedure's exact wire identity and codecs. */
internal class ClientAuthenticationCodec(private val request: LocalRpcAuthenticationRequest) {
    private sealed interface DecodedOwnership {
        data object Open : DecodedOwnership
        class Held(val response: LocalRpcAuthenticationResponse) : DecodedOwnership
        data object Closed : DecodedOwnership
    }
    private val decoded = MutableStateFlow<DecodedOwnership>(DecodedOwnership.Open)
    // Missing CURRENT v2 remains unavailable; no automatic v1 fallback or reinvocation.
    private val shared = if (request.action == LocalRpcAuthenticationAction.CURRENT)
        authenticationCurrentProcedure else authenticationExchangeProcedure
    val procedure = RpcProcedure(
        name = shared.name, version = shared.version,
        request = object : KSerializer<LocalRpcAuthenticationRequest> by shared.request {
            override fun serialize(encoder: Encoder, value: LocalRpcAuthenticationRequest) {
                check(value === request) { "Authentication request ownership differs" }
                try { shared.request.serialize(encoder, value) } finally { value.destroy() }
            }
        },
        response = object : KSerializer<LocalRpcAuthenticationResponse> by shared.response {
            override fun deserialize(decoder: Decoder): LocalRpcAuthenticationResponse {
                val response = shared.response.deserialize(decoder)
                if (!response.matchesAction(request.action)) {
                    response.destroy()
                    throw SerializationException("Authentication response action differs")
                }
                if (!decoded.compareAndSet(DecodedOwnership.Open, DecodedOwnership.Held(response))) {
                    response.destroy()
                    throw SerializationException("Authentication response ownership differs")
                }
                return response
            }
        },
        applicationError = shared.applicationError, retrySafety = shared.retrySafety,
        requestLimitBytes = shared.requestLimitBytes, responseLimitBytes = shared.responseLimitBytes,
        errorLimitBytes = shared.errorLimitBytes,
    )

    fun transfer(response: LocalRpcAuthenticationResponse): LocalRpcAuthenticationResponse {
        val held = decoded.value
        check(held is DecodedOwnership.Held && held.response === response &&
            decoded.compareAndSet(held, DecodedOwnership.Closed)) { "Authentication response ownership differs" }
        return response
    }

    fun destroy() {
        try {
            // One-way state has at most two competing transitions: Open -> Held -> Closed.
            repeat(2) {
                val owned = decoded.value
                if (owned === DecodedOwnership.Closed) return
                if (decoded.compareAndSet(owned, DecodedOwnership.Closed)) {
                    (owned as? DecodedOwnership.Held)?.response?.destroy()
                    return
                }
            }
            check(decoded.value === DecodedOwnership.Closed) { "Authentication response ownership differs" }
        } finally {
            request.destroy()
        }
    }
}

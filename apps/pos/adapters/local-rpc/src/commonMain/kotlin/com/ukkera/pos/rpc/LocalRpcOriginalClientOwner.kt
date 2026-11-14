package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.database.LocalMasterClientRpcBridgeV1
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LocalMasterRpcBusinessAccess
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcPlatform
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
import kotlinx.serialization.SerializationException

/** Genuine native original read/close seam; it has no acquisition or repair operation. */
internal interface LocalRpcOriginalClientSource {
    suspend fun recoverOriginal(): LocalDevicePairingSdkOriginal
    suspend fun close()
}

/**
 * One existing-original handoff, retained by the application before its first read.
 * Native source close must actually return before the shared official factory is retained
 * and acquired. The factory owner supplies all actual role acquisition and cleanup behavior.
 * Failed operations are cached; unknown cleanup keeps this holder and its exact source/factory.
 * The selected-Master route consumes only the coordinated qualified native readback, while
 * the existing identity-only route makes no selection claim. Both use this same lifecycle.
 */
internal class LocalRpcOriginalClientOwner private constructor(
    applicationScope: CoroutineScope,
    private val parent: Job,
    private val identitySource: LocalRpcOriginalClientSource?,
    private val selectedMasterSource: LocalRpcSelectedMasterClientSource?,
    private val platform: () -> RpcPlatform,
    private val lan: OrganizationLan,
    private val providedTrustStore: RpcTrustStore?,
    private val onConfirmedClose: () -> Unit,
) {
    /** Existing identity-only composition; it cannot establish a selected Master. */
    constructor(
        applicationScope: CoroutineScope,
        parent: Job,
        source: LocalRpcOriginalClientSource,
        platform: () -> RpcPlatform,
        lan: OrganizationLan,
        trustStore: RpcTrustStore,
        onConfirmedClose: () -> Unit,
    ) : this(applicationScope, parent, source, null, platform, lan, trustStore, onConfirmedClose)

    /** Production selected-Master composition accepts no external store or approval DATA. */
    constructor(
        applicationScope: CoroutineScope,
        parent: Job,
        source: LocalRpcSelectedMasterClientSource,
        platform: () -> RpcPlatform,
        lan: OrganizationLan,
        onConfirmedClose: () -> Unit,
    ) : this(applicationScope, parent, null, source, platform, lan, null, onConfirmedClose)

    private val gate = Mutex()
    private val state = MutableStateFlow(LocalRpcFactoryPhase.NEW)
    val phase: StateFlow<LocalRpcFactoryPhase> = state.asStateFlow()
    private val originalData = MutableStateFlow<LocalDevicePairingSdkOriginal?>(null)
    val original: StateFlow<LocalDevicePairingSdkOriginal?> = originalData.asStateFlow()
    private val approvedMasterData = MutableStateFlow<LocalDevicePairingApprovedMaster?>(null)
    /** Immutable selected-original identification; it does not assert a current grant. */
    val approvedMaster: StateFlow<LocalDevicePairingApprovedMaster?> = approvedMasterData.asStateFlow()
    private val ownerJob: Job = SupervisorJob()
    private val scope = CoroutineScope(applicationScope.coroutineContext.minusKey(Job) + ownerJob)
    private var acquisition: Deferred<RpcLocalIdentity>? = null
    private var connection: Deferred<Unit>? = null
    private var selectedEndpoint: RpcEndpoint? = null
    private var sourceClosing: Deferred<Unit>? = null
    private var closing: Deferred<Unit>? = null
    private var factory: LocalRpcFactoryOwner? = null
    private var remoteAuthentication: LocalRpcRemoteAuthenticationService? = null
    private var remoteDirectory: LocalRpcRemoteDirectoryClient? = null
    private var directoryBridge: LocalMasterClientRpcBridgeV1? = null
    private class DirectoryLifetime(val client: LocalRpcRemoteDirectoryClient, val factory: LocalRpcFactoryOwner)
    private val directoryLifetime = MutableStateFlow<DirectoryLifetime?>(null)
    private val directoryOperations = mutableSetOf<DirectoryOperation<*>>()
    private val directoryWorkers = mutableSetOf<Job>()
    private class DirectoryOperation<T : Any>(
        val credentials: LocalGatewayCredentials,
        val access: LocalMasterRpcBusinessAccess?,
        val call: suspend (LocalMasterClientRpcBridgeV1, LocalGatewayCredentials, DomainId) -> T,
    ) {
        lateinit var worker: Job
        val completed = CompletableDeferred<Unit>()
        var awaiting = true
        var result: Result<T>? = null
    }
    private class AuthenticationLifetime(
        val service: LocalRpcRemoteAuthenticationService,
        val factory: LocalRpcFactoryOwner,
    )
    private val authenticationLifetime = MutableStateFlow<AuthenticationLifetime?>(null)
    private val authenticationOperations = mutableSetOf<AuthenticationOperation<*>>()
    private val authenticationWorkers = mutableSetOf<Job>()
    private var selectedMasterTrustStore: LocalRpcSelectedMasterTrustStore? = null
    private var checkedFactoryIdentity: RpcLocalIdentity? = null
    private var sourceClosed = false
    private var factoryEntered = false
    private var holderReleased = false
    private val retired = CompletableDeferred<Unit>()

    private class AuthenticationOperation<T : Any>(
        val request: LocalRpcAuthenticationRequest,
        val consume: (LocalRpcAuthenticationResponse?) -> T,
        val destroy: (T) -> Unit,
    ) {
        lateinit var worker: Job
        val completed = CompletableDeferred<Unit>()
        var awaiting = true
        var result: Result<T>? = null

        fun destroyResult() {
            result?.getOrNull()?.let(destroy)
            result = null
        }
    }

    init {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                retired.await()
            } finally {
                withContext(NonCancellable) {
                    try { closeOwned() }
                    catch (_: Throwable) {
                        // The native holder retains this owner on unknown cleanup.
                    }
                }
            }
        }
    }

    suspend fun acquire(): RpcLocalIdentity {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            check(parent.isActive && (state.value == LocalRpcFactoryPhase.NEW ||
                state.value == LocalRpcFactoryPhase.ACQUIRING || state.value == LocalRpcFactoryPhase.READY)) {
                "Original RPC client owner is unavailable"
            }
            acquisition ?: scope.async(start = CoroutineStart.LAZY) { acquireOwned() }.also {
                acquisition = it
                state.value = LocalRpcFactoryPhase.ACQUIRING
                it.start()
            }
        }
        val identity = pending.await()
        currentCoroutineContext().ensureActive()
        gate.withLock {
            check(parent.isActive && state.value == LocalRpcFactoryPhase.READY) { "Original RPC client owner is retired" }
        }
        return identity
    }

    suspend fun close() {
        val caller = currentCoroutineContext()
        withContext(NonCancellable) { closeOwned() }
        caller.ensureActive()
    }

    /** Explicit numeric connection to the qualified original Master, never a caller-supplied pin. */
    suspend fun connect(endpoint: RpcEndpoint) {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            check(selectedMasterSource != null && parent.isActive && state.value in setOf(
                LocalRpcFactoryPhase.NEW, LocalRpcFactoryPhase.ACQUIRING, LocalRpcFactoryPhase.READY,
            )) { "Selected original Client is unavailable" }
            selectedEndpoint?.let {
                require(it.address == endpoint.address && it.port == endpoint.port) {
                    "Original Master endpoint differs"
                }
            }
            connection ?: scope.async(start = CoroutineStart.LAZY) { connectOwned(endpoint) }.also {
                selectedEndpoint = endpoint
                connection = it
                it.start()
            }
        }
        pending.await()
        currentCoroutineContext().ensureActive()
        val selected = gate.withLock {
            requireConnectedOwner()
            checkNotNull(factory) to RpcSelectedHost(
                checkNotNull(approvedMasterData.value).masterSdkOriginal.fingerprint, endpoint,
            )
        }
        // Reobserve the exact SDK connection even for a previously successful attempt.
        // The factory retains that attempt; this cannot reconnect or choose a new role.
        selected.first.connectSelectedHost(selected.second)
        gate.withLock { requireConnectedOwner() }
    }

    /** Narrow authentication on this already-connected original; this cannot connect or replace it. */
    suspend fun authenticationService(): LocalRpcRemoteAuthenticationService {
        currentCoroutineContext().ensureActive()
        val retainedFactory = gate.withLock {
            requireConnectedOwner()
            requireConnectionAttemptFinished()
            checkNotNull(factory)
        }
        retainedFactory.assertSelectedClientConnected()
        return gate.withLock {
            currentCoroutineContext().ensureActive()
            requireConnectedOwner()
            requireConnectionAttemptFinished()
            check(factory === retainedFactory) { "Selected original Client differs" }
            (remoteAuthentication ?: LocalRpcRemoteAuthenticationService(this, DomainId.random()).also {
                remoteAuthentication = it
            }).also { service ->
                authenticationLifetime.value = AuthenticationLifetime(service, retainedFactory)
            }
        }
    }

    /** Existing supplier selection; the actual retained profile cannot be replaced. */
    suspend fun supplierClient(): LocalRpcRemoteSupplierClient = directoryClient(LocalMasterRpcWorkflowProfile.SUPPLIERS)

    /** One fixed directory profile on this same selected-original authentication connection. */
    suspend fun directoryClient(profile: LocalMasterRpcWorkflowProfile): LocalRpcRemoteDirectoryClient {
        val authentication = authenticationService()
        val retainedFactory = gate.withLock {
            requireConnectedOwner()
            remoteDirectory?.let { check(it.profile == profile) { "Selected directory profile differs" } }
            checkNotNull(factory)
        }
        val bridge = retainedFactory.retainDirectoryBridge(checkNotNull(originalData.value),
            checkNotNull(approvedMasterData.value), parent, authentication.connectionId, profile)
        return gate.withLock {
            currentCoroutineContext().ensureActive()
            requireConnectedOwner()
            check(factory === retainedFactory && authenticationCurrent(authentication, authentication.connectionId))
            remoteDirectory?.let { check(it.profile == profile) { "Selected directory profile differs" } }
            directoryBridge = bridge
            (remoteDirectory ?: LocalRpcRemoteDirectoryClient(this, authentication, profile).also { remoteDirectory = it }).also {
                directoryLifetime.value = DirectoryLifetime(it, retainedFactory)
            }
        }
    }

    /** Remote employee creation on the SAME retained directory connection and bridge. The
     * profile stays fixed; creation uses its own procedure and the Master's own authorization. */
    suspend fun employeeCreationClient(directory: LocalRpcRemoteDirectoryClient): LocalRpcRemoteEmployeeCreationClient =
        gate.withLock {
            currentCoroutineContext().ensureActive()
            requireConnectedOwner()
            check(directoryClientCurrent(directory)) { "Selected directory client is retired" }
            LocalRpcRemoteEmployeeCreationClient(this, directory)
        }

    internal fun directoryClientCurrent(client: LocalRpcRemoteDirectoryClient): Boolean {
        val retained = directoryLifetime.value ?: return false
        return retained.client === client && parent.isActive && state.value == LocalRpcFactoryPhase.READY &&
            retained.factory.selectedClientConnectedNow() && authenticationServiceCurrent(client.authentication, client.connectionId)
    }

    /** Actual owner retains admitted work and its result until exact one-shot adoption or abandonment. */
    internal suspend fun <T : Any> directoryExchange(client: LocalRpcRemoteDirectoryClient,
        credentials: LocalGatewayCredentials, access: LocalMasterRpcBusinessAccess?,
        unavailable: () -> T, busy: () -> T,
        call: suspend (LocalMasterClientRpcBridgeV1, LocalGatewayCredentials, DomainId) -> T): T {
        currentCoroutineContext().ensureActive()
        var saturated = false
        val operation = gate.withLock {
            if (!directoryClientCurrent(client) || credentials.connectionId != client.connectionId ||
                access != null && access.profile != client.profile) return@withLock null
            directoryWorkers.removeAll { it.isCompleted }
            if (directoryOperations.size >= 8 || directoryWorkers.size >= 8) { saturated = true; return@withLock null }
            val owned = LocalGatewayCredentials(credentials.connectionId, credentials.bearer.useBytes(::OpaqueCredential))
            try {
                val retainedFactory = checkNotNull(factory)
                val bridge = checkNotNull(directoryBridge)
                DirectoryOperation(owned, access, call).also { admitted ->
                    admitted.worker = scope.launch(start = CoroutineStart.LAZY) { directoryOwned(client, retainedFactory, bridge, admitted) }
                    directoryOperations.add(admitted)
                    directoryWorkers.add(admitted.worker)
                    if (!admitted.worker.start()) {
                        directoryOperations.remove(admitted); directoryWorkers.remove(admitted.worker)
                        error("Selected original Client is retired")
                    }
                }
            } catch (failure: Throwable) { owned.bearer.destroy(); throw failure }
        } ?: return if (saturated) busy() else unavailable()
        try {
            operation.completed.await()
            currentCoroutineContext().ensureActive()
            return gate.withLock {
                currentCoroutineContext().ensureActive()
                check(directoryClientCurrent(client) && operation.awaiting) { "Selected original Client is retired" }
                val result = checkNotNull(operation.result)
                operation.result = null; operation.awaiting = false; directoryOperations.remove(operation)
                result.getOrThrow()
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                gate.withLock {
                    operation.awaiting = false; operation.result = null
                    if (operation.completed.isCompleted) directoryOperations.remove(operation)
                }
            }
            throw failure
        }
    }

    private suspend fun <T : Any> directoryOwned(client: LocalRpcRemoteDirectoryClient,
        retainedFactory: LocalRpcFactoryOwner, bridge: LocalMasterClientRpcBridgeV1, operation: DirectoryOperation<T>) {
        var result: Result<T>? = null
        try {
            gate.withLock { check(directoryClientCurrent(client) && factory === retainedFactory && directoryBridge === bridge) }
            // Fresh actual Current supplies the captured actor; no caller actor/device claims cross the wire.
            val current = client.authentication.current(operation.credentials) as? LocalSessionViewResult.Current
                ?: error("Directory session is unavailable")
            val approved = checkNotNull(approvedMasterData.value)
            check(current.view.scope == approved.scope && current.view.deviceId == approved.targetDeviceId)
            if (operation.access is LocalMasterRpcBusinessAccess.Lookup)
                check(operation.access.identity.originalActorId == current.view.actorId)
            gate.withLock { check(directoryClientCurrent(client) && factory === retainedFactory && directoryBridge === bridge) }
            val value = retainedFactory.withDirectoryBridge(bridge) {
                operation.call(it, operation.credentials, current.view.actorId)
            }
            result = Result.success(value)
        } catch (failure: Throwable) { result = Result.failure(failure) }
        finally {
            operation.credentials.bearer.destroy()
            withContext(NonCancellable) {
                gate.withLock {
                    operation.result = if (operation.awaiting && directoryClientCurrent(client) &&
                        factory === retainedFactory && directoryBridge === bridge) result
                        else Result.failure(IllegalStateException("Selected original Client is retired"))
                    if (!operation.awaiting) { operation.result = null; directoryOperations.remove(operation) }
                    operation.completed.complete(Unit)
                }
            }
        }
    }

    /** Reads only published immutable ownership and live thread-safe lifetime observations. */
    internal fun authenticationServiceCurrent(service: LocalRpcRemoteAuthenticationService, connectionId: DomainId): Boolean {
        val retained = authenticationLifetime.value ?: return false
        return retained.service === service && service.connectionId == connectionId && parent.isActive &&
            state.value == LocalRpcFactoryPhase.READY && retained.factory.selectedClientConnectedNow()
    }

    /**
     * Snapshot only after bounded admission. The owner, rather than an interactive waiter, retains
     * admitted work and a pending one-shot result until transfer, abandonment or confirmed close.
     */
    internal suspend fun <T : Any> authenticationExchange(
        service: LocalRpcRemoteAuthenticationService,
        connectionId: DomainId,
        snapshot: () -> LocalRpcAuthenticationRequest?,
        consume: (LocalRpcAuthenticationResponse?) -> T,
        unavailable: () -> T,
        busy: () -> T,
        destroy: (T) -> Unit,
    ): T {
        currentCoroutineContext().ensureActive()
        var saturated = false
        val operation = gate.withLock {
            if (!authenticationCurrent(service, connectionId)) return@withLock null
            authenticationWorkers.removeAll { it.isCompleted }
            if (authenticationOperations.size >= MAXIMUM_AUTHENTICATION_EXCHANGES ||
                authenticationWorkers.size >= MAXIMUM_AUTHENTICATION_EXCHANGES) {
                saturated = true
                return@withLock null
            }
            val request = snapshot() ?: return@withLock null
            if (!request.validateShape()) {
                request.destroy()
                return@withLock null
            }
            try {
                val retainedFactory = checkNotNull(factory)
                AuthenticationOperation(request, consume, destroy).also { admitted ->
                    admitted.worker = scope.launch(start = CoroutineStart.LAZY) {
                        authenticationOwned(service, connectionId, retainedFactory, admitted)
                    }
                    authenticationOperations.add(admitted)
                    authenticationWorkers.add(admitted.worker)
                    if (!admitted.worker.start()) {
                        authenticationOperations.remove(admitted)
                        authenticationWorkers.remove(admitted.worker)
                        error("Selected original Client is retired")
                    }
                }
            } catch (failure: Throwable) {
                request.destroy()
                throw failure
            }
        } ?: return if (saturated) busy() else unavailable()

        try {
            operation.completed.await()
            currentCoroutineContext().ensureActive()
            return gate.withLock {
                currentCoroutineContext().ensureActive()
                check(authenticationCurrent(service, connectionId) && operation.awaiting) {
                    "Selected original Client is retired"
                }
                val result = checkNotNull(operation.result) { "Authentication exchange is unavailable" }
                operation.result = null
                operation.awaiting = false
                authenticationOperations.remove(operation)
                result.getOrThrow()
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                gate.withLock {
                    operation.awaiting = false
                    operation.destroyResult()
                    if (operation.completed.isCompleted) authenticationOperations.remove(operation)
                }
            }
            throw failure
        }
    }

    private suspend fun <T : Any> authenticationOwned(
        service: LocalRpcRemoteAuthenticationService,
        connectionId: DomainId,
        retainedFactory: LocalRpcFactoryOwner,
        operation: AuthenticationOperation<T>,
    ) {
        var response: LocalRpcAuthenticationResponse? = null
        var value: T? = null
        var failure: Throwable? = null
        try {
            gate.withLock {
                check(authenticationCurrent(service, connectionId) && factory === retainedFactory) {
                    "Selected original Client is retired"
                }
            }
            response = retainedFactory.callSelectedClientAuthentication(operation.request)
            gate.withLock {
                check(authenticationCurrent(service, connectionId) && factory === retainedFactory) {
                    "Selected original Client is retired"
                }
                if (operation.awaiting) {
                    value = operation.consume(response)
                    validateAuthenticationOutput(operation.request, checkNotNull(value))
                }
            }
        } catch (caught: Throwable) {
            // Keep actual transport execution evidence/uncertainty; no retry or success substitute.
            failure = caught
        } finally {
            response?.destroy()
            operation.request.destroy()
            withContext(NonCancellable) {
                gate.withLock {
                    val owned = value
                    if (operation.awaiting && authenticationCurrent(service, connectionId) &&
                        failure == null && owned != null) {
                        operation.result = Result.success(owned)
                        value = null
                    } else {
                        owned?.let(operation.destroy)
                        value = null
                        operation.result = Result.failure(failure ?:
                            IllegalStateException("Selected original Client is retired"))
                    }
                    if (!operation.awaiting) {
                        operation.destroyResult()
                        authenticationOperations.remove(operation)
                    }
                    operation.completed.complete(Unit)
                }
            }
        }
    }

    /** Native selection fixes branch/device; request data supplies no such authority. */
    private fun validateAuthenticationOutput(request: LocalRpcAuthenticationRequest, result: Any) {
        val view = when (result) {
            is LocalAuthenticationResult.Authenticated -> result.tokens.view
            is LocalSessionViewResult.Current -> result.view
            else -> return
        }
        val approved = checkNotNull(approvedMasterData.value)
        if (view.scope != approved.scope || view.deviceId != approved.targetDeviceId ||
            (request.action == LocalRpcAuthenticationAction.LOGIN && view.login.value != request.login)) {
            throw SerializationException("Authentication response original identity differs")
        }
        // The exact Master clock domain/deadlines are preserved, without Client clock comparisons.
    }

    private fun authenticationCurrent(service: LocalRpcRemoteAuthenticationService, connectionId: DomainId): Boolean =
        authenticationServiceCurrent(service, connectionId) && sourceClosed &&
            selectedMasterSource != null && selectedMasterTrustStore != null && approvedMasterData.value != null &&
            checkedFactoryIdentity != null && factory?.phase?.value == LocalRpcFactoryPhase.READY &&
            selectedEndpoint != null && connection?.isCompleted == true && connection?.isCancelled == false &&
            remoteAuthentication === service && service.connectionId == connectionId

    private fun requireConnectionAttemptFinished() {
        check(selectedEndpoint != null && connection?.isCompleted == true && connection?.isCancelled == false) {
            "Selected original Client is not connected"
        }
    }

    private suspend fun connectOwned(endpoint: RpcEndpoint) {
        try {
            acquire()
            val selected = gate.withLock {
                requireConnectedOwner()
                checkNotNull(factory) to RpcSelectedHost(
                    checkNotNull(approvedMasterData.value).masterSdkOriginal.fingerprint, endpoint,
                )
            }
            selected.first.connectSelectedHost(selected.second)
            gate.withLock { requireConnectedOwner() }
        } catch (failure: Throwable) {
            gate.withLock {
                if (state.value != LocalRpcFactoryPhase.CLOSING && state.value != LocalRpcFactoryPhase.CLOSED) {
                    state.value = LocalRpcFactoryPhase.UNCERTAIN
                }
            }
            // Cleanup owns this exact connection attempt, including a lost result delivery.
            // Schedule it without waiting for our own admitted operation to finish.
            beginClose()
            throw failure
        }
    }

    private fun requireConnectedOwner() {
        check(parent.isActive && state.value == LocalRpcFactoryPhase.READY && sourceClosed &&
            selectedMasterSource != null && selectedMasterTrustStore != null && approvedMasterData.value != null &&
            checkedFactoryIdentity != null && factory?.phase?.value == LocalRpcFactoryPhase.READY) {
            "Selected original Client is retired"
        }
    }

    private suspend fun acquireOwned(): RpcLocalIdentity {
        try {
            gate.withLock { requireAcquiring() }
            val selected = selectedMasterSource?.recover()
            val retainedOriginal = selected?.original ?: checkNotNull(identitySource).recoverOriginal()
            val selectedStore = selected?.let { LocalRpcSelectedMasterTrustStore(this, it) }
            val retainedTrustStore: RpcTrustStore = selectedStore ?: checkNotNull(providedTrustStore)
            gate.withLock {
                requireAcquiring()
                originalData.value = retainedOriginal
                approvedMasterData.value = selected?.approvedMaster
                selectedMasterTrustStore = selectedStore
            }
            closeSourceOnce().await()
            val retainedFactory = gate.withLock {
                requireAcquiring()
                sourceClosed = true
                factoryEntered = true
                LocalRpcFactoryOwner.reconstructClient(platform(), scope, retainedOriginal, lan, retainedTrustStore)
                    .also { factory = it }
            }
            gate.withLock { requireAcquiring() }
            val identity = retainedFactory.acquire()
            gate.withLock {
                requireAcquiring()
                check(selected?.matchesFactoryIdentity(identity) != false) { "Selected Client original identity differs" }
                checkedFactoryIdentity = identity
                state.value = LocalRpcFactoryPhase.READY
            }
            return identity
        } catch (failure: Throwable) {
            gate.withLock {
                if (state.value != LocalRpcFactoryPhase.CLOSING) state.value = LocalRpcFactoryPhase.UNCERTAIN
            }
            beginClose()
            throw failure
        }
    }

    private fun requireAcquiring() {
        check(parent.isActive && state.value == LocalRpcFactoryPhase.ACQUIRING) { "Original RPC client owner is retired" }
    }

    /** Only the exact internally installed projection can read this active original/factory. */
    internal suspend fun readSelectedMasterTrust(
        store: LocalRpcSelectedMasterTrustStore,
        readback: LocalRpcSelectedMasterClientReadback,
        appId: AppId,
        purpose: RpcTrustPurpose,
        expected: Set<PeerFingerprint>?,
    ): Set<PeerFingerprint> {
        currentCoroutineContext().ensureActive()
        return gate.withLock {
            check(parent.isActive && sourceClosed && selectedMasterTrustStore === store &&
                originalData.value === readback.original && approvedMasterData.value === readback.approvedMaster) {
                "Selected Master trust owner is unavailable"
            }
            val retainedFactory = checkNotNull(factory) { "Selected Client factory is unavailable" }
            when (state.value) {
                LocalRpcFactoryPhase.ACQUIRING -> {
                    // Official rc11 loads pins before constructing the Kit. This grants no
                    // publication: the actual identity must still match before READY.
                    check(retainedFactory.phase.value == LocalRpcFactoryPhase.ACQUIRING) {
                        "Selected Client factory is unavailable"
                    }
                }
                LocalRpcFactoryPhase.READY -> {
                    check(retainedFactory.phase.value == LocalRpcFactoryPhase.READY &&
                        readback.matchesFactoryIdentity(checkNotNull(checkedFactoryIdentity))) {
                        "Selected Client factory original differs"
                    }
                }
                else -> error("Selected Master trust owner is retired")
            }
            readback.project(appId, purpose, expected)
        }
    }

    private suspend fun closeSourceOnce(): Deferred<Unit> = gate.withLock {
        sourceClosing ?: scope.async(start = CoroutineStart.LAZY) {
            if (identitySource != null) identitySource.close() else checkNotNull(selectedMasterSource).close()
        }.also {
            sourceClosing = it
            it.start()
        }
    }

    private suspend fun closeOwned() {
        beginClose().await()
        ownerJob.cancelAndJoin()
        gate.withLock {
            if (!holderReleased) {
                onConfirmedClose()
                holderReleased = true
            }
        }
    }

    private suspend fun beginClose(): Deferred<Unit> = gate.withLock {
        val workersToDrain = authenticationWorkers.toList() + directoryWorkers.toList()
        closing ?: scope.async<Unit>(start = CoroutineStart.LAZY) {
            gate.withLock { acquisition }?.join()
            gate.withLock { connection }?.join()
            workersToDrain.forEach { it.join() }
            try {
                closeSourceOnce().await()
                val retainedFactory = gate.withLock {
                    check(factory != null || !factoryEntered) { "Original RPC factory composition cleanup is uncertain" }
                    factory
                }
                retainedFactory?.close()
                gate.withLock {
                    factory = null
                    selectedMasterTrustStore = null
                    checkedFactoryIdentity = null
                    remoteAuthentication = null
                    remoteDirectory = null
                    directoryBridge = null
                    directoryOperations.clear()
                    directoryWorkers.clear()
                    authenticationOperations.clear()
                    authenticationWorkers.clear()
                    state.value = LocalRpcFactoryPhase.CLOSED
                }
                retired.complete(Unit)
            } catch (failure: Throwable) {
                gate.withLock { state.value = LocalRpcFactoryPhase.UNCERTAIN }
                throw failure
            }
        }.also {
            closing = it
            authenticationLifetime.value = null
            directoryLifetime.value = null
            directoryBridge?.deny()
            remoteDirectory?.deny()
            directoryOperations.forEach { operation -> operation.awaiting = false; operation.result = null }
            state.value = LocalRpcFactoryPhase.CLOSING
            authenticationOperations.forEach { operation ->
                operation.awaiting = false
                operation.destroyResult()
            }
            it.start()
        }
    }

    companion object {
        private const val MAXIMUM_AUTHENTICATION_EXCHANGES = 8

        /** Validate before a native holder claims the caller's existing target. */
        fun retentionLifetime(applicationScope: CoroutineScope): Job =
            requireNotNull(applicationScope.coroutineContext[Job]) {
                "A retained application lifetime is required"
            }.also {
                require(it.isActive) { "The application lifetime is inactive" }
            }

        /** Identity-only callers must supply their own compatible store; selection stays separate. */
        fun retentionLifetime(applicationScope: CoroutineScope, trustStore: RpcTrustStore): Job =
            retentionLifetime(applicationScope).also {
                require(trustStore !is LocalRpcTrustStore) { "A Client-compatible RPC trust store is required" }
            }
    }
}

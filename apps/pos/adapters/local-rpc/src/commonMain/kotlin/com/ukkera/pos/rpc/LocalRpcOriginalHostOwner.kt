package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.database.LocalMasterRpcPeerRegistration
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
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

/** Native operational yield and same-Master trust only; no capture or custody-close API. */
internal interface LocalRpcOriginalHostSource {
    fun isCurrent(): Boolean
    suspend fun yieldOriginal(): LocalMasterSdkOriginal
    suspend fun trustStore(): RpcTrustStore
    suspend fun peerRegistration(): LocalMasterRpcPeerRegistration? = null
}

/**
 * Service-retained original Master identity reconstruction. Yield releases no native Master
 * resources. The exact official factory owns role acquisition/close; this owner only sequences
 * existing-original yield, same-Master trust and reconstruction. Explicit startup requires the
 * service-pushed peer registration and activates only this exact retained Host. Endpoint reads
 * use its actual numeric listener information; neither operation creates business authority.
 * The service must retain this resource before construction and drain it before native teardown.
 */
internal class LocalRpcOriginalHostOwner(
    applicationScope: CoroutineScope,
    private val parent: Job,
    private val source: LocalRpcOriginalHostSource,
    private val platform: () -> RpcPlatform,
    private val lan: OrganizationLan,
) {
    private val gate = Mutex()
    private val state = MutableStateFlow(LocalRpcFactoryPhase.NEW)
    val phase: StateFlow<LocalRpcFactoryPhase> = state.asStateFlow()
    private val originalData = MutableStateFlow<LocalMasterSdkOriginal?>(null)
    val original: StateFlow<LocalMasterSdkOriginal?> = originalData.asStateFlow()
    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(applicationScope.coroutineContext.minusKey(Job) + ownerJob)
    private var acquisition: Deferred<RpcLocalIdentity>? = null
    private var starting: Deferred<Unit>? = null
    private var endpointRead: Deferred<RpcEndpoint>? = null
    private var closing: Deferred<Unit>? = null
    private var factory: LocalRpcFactoryOwner? = null
    private var factoryEntered = false
    private var sessionIngress: LocalRpcMasterSessionIngress? = null
    private val retired = CompletableDeferred<Unit>()

    init {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                retired.await()
            } finally {
                withContext(NonCancellable) {
                    try { closeOwned() }
                    catch (_: Throwable) { /* The service retains uncertain native ownership. */ }
                }
            }
        }
    }

    suspend fun acquire(): RpcLocalIdentity {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            check(parent.isActive && source.isCurrent() && state.value in setOf(
                LocalRpcFactoryPhase.NEW, LocalRpcFactoryPhase.ACQUIRING, LocalRpcFactoryPhase.READY,
            )) { "Original RPC Host owner is unavailable" }
            acquisition ?: scope.async(start = CoroutineStart.LAZY) { acquireOwned() }.also {
                acquisition = it
                state.value = LocalRpcFactoryPhase.ACQUIRING
                it.start()
            }
        }
        val identity = pending.await()
        currentCoroutineContext().ensureActive()
        gate.withLock {
            requireReady()
        }
        return identity
    }

    /** Caller cancellation does not abandon the service-retained, single startup attempt. */
    suspend fun start() {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            requireAvailable()
            starting ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    acquire()
                    val retainedFactory = gate.withLock {
                        requireReady()
                        check(sessionIngress != null) { "Original Host peer registration is unavailable" }
                        checkNotNull(factory)
                    }
                    retainedFactory.startServingHost()
                    gate.withLock { requireReady() }
                } catch (failure: Throwable) {
                    retireFailedOperation()
                    throw failure
                }
            }.also { starting = it; it.start() }
        }
        pending.await()
        currentCoroutineContext().ensureActive()
        requireRunning()
    }

    /** Requires explicit startup; each completed read can be followed by a fresh SDK read. */
    suspend fun endpoint(): RpcEndpoint {
        currentCoroutineContext().ensureActive()
        val pending = gate.withLock {
            requireAvailable()
            val startup = checkNotNull(starting) { "Original RPC Host has not been started" }
            endpointRead?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    startup.await()
                    val retainedFactory = gate.withLock { requireReady(); checkNotNull(factory) }
                    val endpoint = retainedFactory.servingEndpoint()
                    gate.withLock { requireReady() }
                    endpoint
                } catch (failure: Throwable) {
                    retireFailedOperation()
                    throw failure
                }
            }.also { endpointRead = it; it.start() }
        }
        val endpoint = pending.await()
        currentCoroutineContext().ensureActive()
        requireRunning()
        return endpoint
    }

    suspend fun closeOwned() {
        withContext(NonCancellable) {
            beginClose().await()
            ownerJob.cancelAndJoin()
        }
    }

    private suspend fun acquireOwned(): RpcLocalIdentity {
        try {
            gate.withLock { requireAcquiring() }
            val retainedOriginal = source.yieldOriginal()
            gate.withLock {
                requireAcquiring()
                originalData.value = retainedOriginal
            }
            // No service slot or native/Room loan is retained across SDK work. This adapter
            // is derived from the same actual Master, never supplied by a remote caller.
            val trust = source.trustStore()
            val registration = source.peerRegistration()
            val ingress = registration?.let { LocalRpcMasterSessionIngress(it, scope, parent) }
            gate.withLock {
                sessionIngress = ingress
                if (state.value != LocalRpcFactoryPhase.ACQUIRING ||
                    !parent.isActive || !source.isCurrent()) ingress?.deny()
                requireAcquiring()
            }
            val retainedFactory = gate.withLock {
                requireAcquiring()
                factoryEntered = true
                (if (ingress == null) LocalRpcFactoryOwner.reconstructHost(platform(), scope, retainedOriginal, lan, trust)
                 else LocalRpcFactoryOwner.reconstructServingHost(platform(), scope, retainedOriginal, lan, trust, ingress,
                     { parent.isActive && source.isCurrent() && state.value == LocalRpcFactoryPhase.READY }))
                    .also { factory = it }
            }
            gate.withLock { requireAcquiring() }
            val identity = retainedFactory.acquire()
            gate.withLock {
                requireAcquiring()
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
        check(parent.isActive && source.isCurrent() && state.value == LocalRpcFactoryPhase.ACQUIRING) {
            "Original RPC Host owner is retired"
        }
    }

    private fun requireAvailable() {
        check(parent.isActive && source.isCurrent() && state.value in setOf(
            LocalRpcFactoryPhase.NEW, LocalRpcFactoryPhase.ACQUIRING, LocalRpcFactoryPhase.READY,
        )) { "Original RPC Host owner is unavailable" }
    }

    private fun requireReady() {
        check(parent.isActive && source.isCurrent() && state.value == LocalRpcFactoryPhase.READY &&
            factory?.phase?.value == LocalRpcFactoryPhase.READY) { "Original RPC Host owner is retired" }
    }

    private suspend fun requireRunning() {
        val retainedFactory = gate.withLock { requireReady(); checkNotNull(factory) }
        try {
            retainedFactory.assertServingHostRunning()
        } catch (failure: Throwable) {
            // A cancelled observer leaves valid owned startup intact. A known SDK loss
            // already retires the factory; retain/drain this original wrapper as well.
            if (retainedFactory.phase.value != LocalRpcFactoryPhase.READY) {
                withContext(NonCancellable) { retireFailedOperation() }
            }
            throw failure
        }
        gate.withLock { requireReady() }
    }

    private suspend fun retireFailedOperation() {
        gate.withLock {
            if (state.value != LocalRpcFactoryPhase.CLOSING && state.value != LocalRpcFactoryPhase.CLOSED) {
                state.value = LocalRpcFactoryPhase.UNCERTAIN
            }
        }
        beginClose()
    }

    private suspend fun beginClose(): Deferred<Unit> = gate.withLock {
        closing ?: scope.async<Unit>(start = CoroutineStart.LAZY) {
            gate.withLock { acquisition }?.join()
            val operations = gate.withLock { listOfNotNull<Job>(starting, endpointRead) }
            operations.forEach { it.join() }
            try {
                gate.withLock { sessionIngress }?.closeOwned()
                val retainedFactory = gate.withLock {
                    check(factory != null || !factoryEntered) { "Original RPC Host composition cleanup is uncertain" }
                    factory
                }
                retainedFactory?.close()
                gate.withLock {
                    factory = null
                    state.value = LocalRpcFactoryPhase.CLOSED
                }
                retired.complete(Unit)
            } catch (failure: Throwable) {
                gate.withLock { state.value = LocalRpcFactoryPhase.UNCERTAIN }
                throw failure
            }
        }.also {
            closing = it
            state.value = LocalRpcFactoryPhase.CLOSING
            sessionIngress?.deny()
            it.start()
        }
    }

    companion object {
        fun retentionLifetime(applicationScope: CoroutineScope): Job =
            requireNotNull(applicationScope.coroutineContext[Job]) { "A retained application lifetime is required" }
                .also { require(it.isActive) { "The application lifetime is inactive" } }
    }
}

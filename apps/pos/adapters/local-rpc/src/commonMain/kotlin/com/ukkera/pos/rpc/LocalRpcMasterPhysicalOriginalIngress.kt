package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterClientRpcErrorV1
import com.ukkera.pos.database.LocalMasterPhysicalOriginalRequestV1
import com.ukkera.pos.database.LocalMasterPhysicalOriginalResponseV1
import com.ukkera.pos.database.LocalMasterPhysicalRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcPeerRegistration
import com.ukkera.pos.database.LocalMasterRpcRegisteredPeer
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcAuthenticationContext
import dev.p2pkit.rpc.RpcAuthorizationContext
import dev.p2pkit.rpc.RpcAuthorizationScope
import dev.p2pkit.rpc.RpcCallContext
import dev.p2pkit.rpc.RpcHostConfiguration
import dev.p2pkit.rpc.RpcPhysicalSession
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
import kotlin.time.Duration.Companion.seconds

/** Actual Host device-only immutable original recovery. A scope cannot issue credentials,
 * disclose employee results, become an employee resource, Start or publish an instruction.
 * The existing native gate proves the complete original, certificate, device and catalogue.
 * No grant, admitted-device identity or native-force assertion is taken from request DATA. */
internal class LocalRpcMasterPhysicalOriginalIngress(
    private val registration: LocalMasterRpcPeerRegistration,
    cleanupScope: CoroutineScope,
    private val parent: Job,
) {
    private class Entry(val physical: RpcPhysicalSession, val peer: LocalMasterRpcRegisteredPeer,
        val binding: PayloadDigest, val scope: RpcAuthorizationScope)
    private val gate = Mutex()
    private val admissionGate = Mutex()
    private val retired = MutableStateFlow(false)
    private val entries = mutableListOf<Entry>()
    private val failedSessions = mutableListOf<RpcApplicationSession>()
    private val slots = Semaphore(128)
    private var callbacks = 0
    private val drained = CompletableDeferred<Unit>()
    private var cleanupFailure: Throwable? = null
    private val cleanupJob = cleanupScope.launch(start = CoroutineStart.LAZY) {
        try {
            while (isActive && !isRetired()) {
                delay(1000)
                admissionGate.withLock { discardExpired() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { cleanupFailure = failure; deny() }
    }
    init { cleanupJob.start() }

    fun install(configuration: RpcHostConfiguration) {
        configuration.registerScopedWithPhysicalSession(LocalMasterPhysicalRpcProtocolV1.ORIGINAL,
            authenticate = ::authenticate, authorize = ::authorize, handler = ::retain)
    }

    private fun isRetired(): Boolean = retired.value || !parent.isActive
    fun deny() { retired.value = true }

    suspend fun closeOwned() = withContext(NonCancellable) {
        deny()
        cleanupJob.cancelAndJoin()
        // Retire every scope that could already have escaped before callback drain.
        gate.withLock { entries.map { it.scope.session } + failedSessions }.forEach {
            registration.revokeApplicationSession(it)
        }
        gate.withLock { if (callbacks == 0) drained.complete(Unit) }
        drained.await()
        admissionGate.withLock {
            gate.withLock { entries.map { it.scope.session } + failedSessions }.forEach {
                registration.revokeApplicationSession(it)
            }
            cleanupFailure?.let { throw it }
            gate.withLock { entries.clear(); failedSessions.clear() }
        }
    }

    private suspend fun <T> withCallback(refuse: () -> T, work: suspend () -> T): T {
        val acquired = slots.tryAcquire()
        if (!acquired) return refuse()
        var admitted = false
        try {
            admitted = gate.withLock { if (isRetired()) false else { callbacks++; true } }
            return if (admitted) work() else refuse()
        } finally {
            withContext(NonCancellable) {
                if (admitted) gate.withLock { callbacks--; if (retired.value && callbacks == 0) drained.complete(Unit) }
                slots.release()
            }
        }
    }

    private suspend fun discardExpired() {
        val expired = gate.withLock { entries.filter { !it.scope.session.isActive || !it.physical.isConnected } }
        for (entry in expired) {
            registration.revokeApplicationSession(entry.scope.session)
            gate.withLock { entries.remove(entry) }
        }
    }

    private suspend fun authenticate(context: RpcAuthenticationContext, request: LocalMasterPhysicalOriginalRequestV1):
        RpcAuthorizationScope? = withCallback({ null }) { admissionGate.withLock {
            var candidate: RpcApplicationSession? = null
            try {
                currentCoroutineContext().ensureActive()
                if (isRetired() || context.peer != context.physicalSession.peer) return@withLock null
                discardExpired()
                val binding = request.bindingDigest
                val existing = gate.withLock { entries.firstOrNull {
                    it.physical === context.physicalSession && it.binding == binding
                } }
                if (existing != null) {
                    val peer = registration.registerAuthenticationExchange(existing.scope.session, context.physicalSession)
                        ?: return@withLock null
                    return@withLock existing.scope.takeIf { !isRetired() && peer === existing.peer && peer.isCurrent() &&
                        registration.physicalOriginalSessionCurrent(existing.scope.session, peer) }
                }
                if (gate.withLock { entries.size + failedSessions.size >= 128 }) return@withLock null
                val fingerprint = context.peer.fingerprint ?: return@withLock null
                val session = RpcApplicationSession(fingerprint, "pos.physical.original.device", DomainId.random().value, 120.seconds)
                candidate = session
                val peer = registration.registerAuthenticationExchange(session, context.physicalSession) ?: return@withLock null
                if (!registration.physicalOriginalSessionCurrent(session, peer)) return@withLock null
                val scope = RpcAuthorizationScope(session, "${LocalMasterPhysicalRpcProtocolV1.ORIGINAL.name}#${binding.value}")
                val published = gate.withLock {
                    if (isRetired() || !peer.isCurrent() || !session.isActive || entries.size >= 128) false
                    else { entries.add(Entry(context.physicalSession, peer, binding, scope)); true }
                }
                if (!published) return@withLock null
                candidate = null
                scope
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
            finally { withContext(NonCancellable) {
                candidate?.let { session ->
                    try { registration.revokeApplicationSession(session) }
                    catch (failure: Throwable) {
                        gate.withLock { failedSessions.add(session) }
                        cleanupFailure = failure; deny(); throw failure
                    }
                }
            } }
        } }

    private suspend fun authorize(context: RpcAuthorizationContext): Boolean = withCallback({ false }) {
        val entry = gate.withLock { entries.firstOrNull { it.scope.session === context.scope.session } }
            ?: return@withCallback false
        context.procedureName == LocalMasterPhysicalRpcProtocolV1.ORIGINAL.name &&
            context.procedureVersion == LocalMasterPhysicalRpcProtocolV1.ORIGINAL.version &&
            context.scope.resource == entry.scope.resource && context.originalPhysicalSession === entry.physical &&
            context.physicalSession === entry.physical && context.peer == entry.physical.peer &&
            !isRetired() && registration.physicalOriginalSessionCurrent(entry.scope.session, entry.peer)
    }

    private suspend fun retain(context: RpcCallContext, request: LocalMasterPhysicalOriginalRequestV1):
        RpcReply<LocalMasterPhysicalOriginalResponseV1, LocalMasterClientRpcErrorV1> = withCallback(::unavailable) {
            try {
                val entry = gate.withLock { entries.firstOrNull { it.scope.session === context.authorizationScope?.session } }
                    ?: return@withCallback unavailable()
                if (context.authorizationScope?.resource != entry.scope.resource || context.physicalSession !== entry.physical ||
                    context.peer != entry.physical.peer || request.bindingDigest != entry.binding ||
                    !registration.physicalOriginalSessionCurrent(entry.scope.session, entry.peer)) return@withCallback unavailable()
                val response = registration.physicalOriginal(request, entry.peer, entry.scope.session) ?: return@withCallback unavailable()
                currentCoroutineContext().ensureActive()
                if (isRetired() || !registration.physicalOriginalSessionCurrent(entry.scope.session, entry.peer)) unavailable()
                else RpcReply.Success(response)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); unavailable() }
        }

    private fun unavailable(): RpcReply<LocalMasterPhysicalOriginalResponseV1, LocalMasterClientRpcErrorV1> =
        RpcReply.ApplicationError(LocalMasterClientRpcErrorV1.UNAVAILABLE)
}

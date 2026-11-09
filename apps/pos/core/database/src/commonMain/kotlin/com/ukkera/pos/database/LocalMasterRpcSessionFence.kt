package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.rpc.RpcApplicationSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opaque observation belonging to one private original-Host registration. */
public class LocalMasterRpcSessionEpoch internal constructor(
    private val fence: LocalMasterRpcSessionFence,
    private val origin: Any,
    private val epoch: Long,
) {
    internal fun matches(fence: LocalMasterRpcSessionFence, origin: Any, epoch: Long): Boolean =
        this.fence === fence && this.origin === origin && this.epoch == epoch

    override fun toString(): String = "LocalMasterRpcSessionEpoch([REDACTED])"
}

/** Inert original binding metadata; construction never authenticates a principal. */
internal data class LocalMasterRpcSessionIdentity(
    val scope: BranchScope,
    val actorId: DomainId,
    val deviceId: DomainId,
    val sessionId: DomainId,
    val generation: SessionGeneration,
    val admissionRevision: Revision,
    val purpose: AuthenticationPurpose,
)

/** Actual retained resource session; SDK activity and pure retirement are separate boundaries. */
internal class LocalMasterRpcResourceSession(
    internal val identity: LocalMasterRpcSessionIdentity,
    internal val session: RpcApplicationSession,
    private val retained: MutableStateFlow<Boolean>,
    private val denied: MutableStateFlow<Boolean>,
) {
    /** SDK activity samples its own clock. Never call this after the protected final clock. */
    internal fun current(): Boolean {
        if (!stateCurrent()) return false
        if (!session.isActive) retained.value = false
        return stateCurrent()
    }

    /** Pure irreversible POS retirement only; no SDK getter, clock or callback. */
    internal fun stateCurrent(): Boolean = !denied.value && retained.value
}

/** Exact admitted device for a private authentication exchange, never an employee grant. */
internal data class LocalMasterRpcExchangeDeviceIdentity(
    val scope: BranchScope,
    val deviceId: DomainId,
    val admissionRevision: Revision,
)

/** Explicit effects of the audited protected mutation paths, not permission/resource grants. */
internal sealed interface LocalMasterRpcSessionInvalidation {
    fun invalidates(identity: LocalMasterRpcSessionIdentity): Boolean
    fun invalidatesExchange(identity: LocalMasterRpcExchangeDeviceIdentity): Boolean
    fun invalidatesResource(identity: LocalMasterRpcSessionIdentity): Boolean = invalidates(identity)

    data object All : LocalMasterRpcSessionInvalidation {
        override fun invalidates(identity: LocalMasterRpcSessionIdentity): Boolean = true
        override fun invalidatesExchange(identity: LocalMasterRpcExchangeDeviceIdentity): Boolean = true
    }
    data object None : LocalMasterRpcSessionInvalidation {
        override fun invalidates(identity: LocalMasterRpcSessionIdentity): Boolean = false
        override fun invalidatesExchange(identity: LocalMasterRpcExchangeDeviceIdentity): Boolean = false
    }

    /** A changed activated policy fences private business output, not identity/status. */
    class Policy(val scope: BranchScope, val actorId: DomainId) : LocalMasterRpcSessionInvalidation {
        override fun invalidates(identity: LocalMasterRpcSessionIdentity): Boolean = false
        override fun invalidatesExchange(identity: LocalMasterRpcExchangeDeviceIdentity): Boolean = false
        override fun invalidatesResource(identity: LocalMasterRpcSessionIdentity): Boolean =
            identity.scope == scope && identity.actorId == actorId
    }

    class Account(
        next: ProtectedLocalAccount,
        previousCredentialRevision: Revision?,
    ) : LocalMasterRpcSessionInvalidation {
        private val state = next.state
        val scope: BranchScope = state.scope
        val actorId: DomainId = state.actorId
        private val credentialInvalid = next.credential == null || previousCredentialRevision == null ||
            previousCredentialRevision != next.credentialRevision
        private val enabled = state.enabled && state.revision.value != Long.MAX_VALUE &&
            state.generation.value != Long.MAX_VALUE
        private val active = state.sessions.filter { it.status == SessionStatus.ACTIVE }

        override fun invalidates(identity: LocalMasterRpcSessionIdentity): Boolean =
            identity.scope == scope && identity.actorId == actorId &&
                (credentialInvalid || !enabled || active.none {
                    it.scope == identity.scope && it.actorId == identity.actorId &&
                        it.sessionId == identity.sessionId && it.deviceId == identity.deviceId &&
                        it.generation == identity.generation && it.authenticationPurpose == identity.purpose
                })

        // A pending/denied device exchange is not an employee session. An attached result's
        // actual employee identity is independently selected by invalidates above.
        override fun invalidatesExchange(identity: LocalMasterRpcExchangeDeviceIdentity): Boolean = false
    }

    class Admission(next: LocalDeviceAdmission) : LocalMasterRpcSessionInvalidation {
        val scope: BranchScope = next.scope
        val deviceId: DomainId = next.deviceId
        private val admitted = next.admitted
        private val revision = next.revision

        override fun invalidates(identity: LocalMasterRpcSessionIdentity): Boolean =
            identity.scope == scope && identity.deviceId == deviceId &&
                (!admitted || revision != identity.admissionRevision)

        override fun invalidatesExchange(identity: LocalMasterRpcExchangeDeviceIdentity): Boolean =
            identity.scope == scope && identity.deviceId == deviceId &&
                (!admitted || revision != identity.admissionRevision)
    }
}

/**
 * One original Master's exact SDK application-session census, shared by its protected store
 * and private original-Host registration. The origin is referential: production binds only
 * the actual registration object, never a device/session identifier or supplied callback.
 *
 * Capture precedes principal verification; retention follows it. Every protected mutation advances
 * the epoch and selects invalidated original identities under a short gate, then awaits each
 * exact SDK revoke outside that gate before any protected provider/SQL write. The original writer loan
 * also owns principal-verification reads through settlement, so verification begun after that
 * epoch change cannot read the preceding protected state while the write is still outstanding.
 *
 * Account and admission transitions revoke only original identities invalidated by their next
 * facts. Grants-only policy, throttle/audit and immutable issuance writes preserve identity-only
 * sessions, while every activated-policy write cuts the affected actor's retained resource-disclosure
 * sessions. Resource retention never grants a permission. Device-only authentication exchanges are admission-fenced before handler start;
 * after issuance their one-way attached result identity is also account-fenced. Original-scope
 * boundaries and denied cleanup drain the complete retained census.
 * It does not close physical connections, retire identity bindings, undo committed results or
 * restore sessions on rollback. Native checks, application callbacks and SDK operations stay
 * outside the gate.
 */
internal class LocalMasterRpcSessionFence {
    private val gate = Mutex()
    private val origin = MutableStateFlow<Any?>(null)
    private val denied = MutableStateFlow(false)
    private var epoch = 0L
    private data class Entry(
        val session: RpcApplicationSession,
        val identity: LocalMasterRpcSessionIdentity?,
        val exchangeDevice: LocalMasterRpcExchangeDeviceIdentity? = null,
        val resourceDisclosure: Boolean = false,
        val active: MutableStateFlow<Boolean> = MutableStateFlow(true),
    )
    private val sessions = mutableListOf<Entry>()

    internal fun bind(token: Any) {
        origin.value?.let { check(it === token); return }
        check(origin.compareAndSet(null, token))
    }

    /** Synchronous irreversible denial; the retained revocation census is never discarded. */
    internal fun deny() { denied.value = true }

    internal suspend fun captureSessionEpoch(token: Any): LocalMasterRpcSessionEpoch? {
        currentCoroutineContext().ensureActive()
        return gate.withLock {
            if (denied.value || origin.value !== token) null
            else LocalMasterRpcSessionEpoch(this, token, epoch)
        }
    }

    internal suspend fun retainApplicationSession(
        token: Any,
        observed: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession,
        identity: LocalMasterRpcSessionIdentity,
    ): Boolean = retainEmployeeSession(token, observed, session, identity, resourceDisclosure = false)

    internal suspend fun retainResourceApplicationSession(
        token: Any,
        observed: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession,
        identity: LocalMasterRpcSessionIdentity,
    ): Boolean = retainEmployeeSession(token, observed, session, identity, resourceDisclosure = true)

    private suspend fun retainEmployeeSession(
        token: Any,
        observed: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession,
        identity: LocalMasterRpcSessionIdentity,
        resourceDisclosure: Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!session.isActive || identity.purpose != AuthenticationPurpose.INTERACTIVE ||
            session.principal != identity.actorId.value || session.sessionId != identity.sessionId.value) return false
        val retained = gate.withLock {
            if (denied.value || origin.value !== token || !observed.matches(this, token, epoch)) false
            else {
                val existing = sessions.firstOrNull { it.session === session }
                when {
                    existing != null -> existing.exchangeDevice == null && existing.identity == identity &&
                        existing.resourceDisclosure == resourceDisclosure && existing.active.value
                    sessions.size >= MAXIMUM_SESSIONS -> false
                    else -> { sessions.add(Entry(session, identity, resourceDisclosure = resourceDisclosure)); true }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return retained && session.isActive
    }

    /** Exact existing resource entry only; a candidate or identity-only SDK scope cannot qualify. */
    internal suspend fun captureResourceSession(token: Any,
        session: RpcApplicationSession): LocalMasterRpcResourceSession? {
        currentCoroutineContext().ensureActive()
        val result = gate.withLock {
            if (denied.value || origin.value !== token) null
            else sessions.firstOrNull { it.session === session && it.resourceDisclosure &&
                it.exchangeDevice == null && it.active.value }?.let { entry ->
                entry.identity?.let { LocalMasterRpcResourceSession(it, session, entry.active, denied) }
            }
        }
        currentCoroutineContext().ensureActive()
        return result?.takeIf { it.current() }
    }

    /**
     * Retain one actual device-authenticated exchange before its SDK handler can start.
     * Its SDK identifiers remain inert exchange metadata, never employee identity claims.
     */
    internal suspend fun retainAuthenticationExchangeSession(
        token: Any,
        observed: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession,
        device: LocalMasterRpcExchangeDeviceIdentity,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!session.isActive) return false
        val retained = gate.withLock {
            if (denied.value || origin.value !== token || !observed.matches(this, token, epoch)) false
            else {
                val existing = sessions.firstOrNull { it.session === session }
                when {
                    existing != null -> existing.exchangeDevice == device
                    sessions.size >= MAXIMUM_SESSIONS -> false
                    else -> { sessions.add(Entry(session, null, device)); true }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return retained && session.isActive
    }

    /**
     * One-way attachment of a freshly verified private result to that same exchange's
     * revocation census. Capture AFTER issuance, BEFORE verification. SDK principal/session
     * metadata is unchanged; this attachment never makes the exchange a business scope.
     */
    internal suspend fun attachAuthenticationExchangeResult(
        token: Any,
        observed: LocalMasterRpcSessionEpoch,
        session: RpcApplicationSession,
        device: LocalMasterRpcExchangeDeviceIdentity,
        identity: LocalMasterRpcSessionIdentity,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!session.isActive || identity.purpose != AuthenticationPurpose.INTERACTIVE ||
            identity.scope != device.scope || identity.deviceId != device.deviceId ||
            identity.admissionRevision != device.admissionRevision) return false
        val attached = gate.withLock {
            if (denied.value || origin.value !== token || !observed.matches(this, token, epoch)) false
            else {
                val index = sessions.indexOfFirst { it.session === session }
                if (index < 0) false else {
                    val original = sessions[index]
                    when {
                        original.exchangeDevice != device -> false
                        original.identity != null -> original.identity == identity
                        else -> { sessions[index] = original.copy(identity = identity); true }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return attached && session.isActive
    }

    /** Only actual successful SDK revocation releases an exact census entry. */
    internal suspend fun revokeApplicationSession(token: Any, session: RpcApplicationSession) {
        check(origin.value === token)
        revokeCaptured(listOf(session))
    }

    /** Every mutation advances publication epochs; later paired writes must not skip invalidation. */
    internal suspend fun beforeProtectedWrite(
        invalidation: LocalMasterRpcSessionInvalidation = LocalMasterRpcSessionInvalidation.All,
    ) {
        currentCoroutineContext().ensureActive()
        val captured = gate.withLock {
            if (epoch == Long.MAX_VALUE) {
                deny()
                error("Original RPC session epoch is exhausted")
            }
            epoch++
            // A prior uncertain revoke cannot disappear behind a later empty/unrelated selection.
            sessions.filter {
                denied.value || it.identity?.let { identity ->
                    if (it.resourceDisclosure) invalidation.invalidatesResource(identity)
                    else invalidation.invalidates(identity)
                } == true ||
                    it.exchangeDevice?.let(invalidation::invalidatesExchange) == true
            }.map { it.session }
        }
        revokeCaptured(captured)
    }

    /** Denial precedes the complete snapshot, including a retention already holding the gate. */
    internal suspend fun awaitSessionRevocation() {
        deny()
        val captured = gate.withLock { sessions.map { it.session } }
        revokeCaptured(captured)
        gate.withLock {
            check(sessions.isEmpty()) { "Original RPC session revocation is unresolved" }
        }
    }

    private suspend fun revokeCaptured(captured: List<RpcApplicationSession>) {
        // Publish irreversible audience retirement before invoking the SDK. An unresolved
        // revoke retains its census entry, but can never revive an already captured audience.
        gate.withLock { sessions.filter { entry -> captured.any { it === entry.session } }
            .forEach { it.active.value = false } }
        val caller = currentCoroutineContext()
        withContext(NonCancellable) {
            var unresolved = false
            var cancelled = false
            for (session in captured) {
                try {
                    session.revoke()
                    gate.withLock { sessions.removeAll { it.session === session } }
                } catch (_: CancellationException) {
                    deny()
                    cancelled = true
                } catch (_: Throwable) {
                    // Keep the exact uncertain entry, deny new retention and attempt the rest.
                    // No SDK/provider exception details escape this boundary.
                    deny()
                    unresolved = true
                }
            }
            if (cancelled) throw CancellationException("Original RPC session revocation was cancelled")
            check(!unresolved) { "Original RPC session revocation is unresolved" }
        }
        // Cancellation never rolls back the epoch/revocations or permits the caller's write.
        caller.ensureActive()
    }

    private companion object {
        const val MAXIMUM_SESSIONS: Int = 128
    }
}

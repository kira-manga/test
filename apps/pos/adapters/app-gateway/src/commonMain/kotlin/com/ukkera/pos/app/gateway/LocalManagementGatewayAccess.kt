package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.security.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/** One actual authentication attachment shared by management adapters. This holder retains
 * negative retirement and observes the same service/lease before and after work. Currentness
 * and cached grants never grant writer authority; the real service reauthorizes each request. */
internal class LocalManagementGatewayAccess(
    private val connection: LocalAuthenticationClientConnection,
    private val owner: LocalClientIdentity,
    private val credentials: LocalWorkflowCredentialSource,
    private val retireOriginalSession: () -> Unit,
) {
    private val retired = MutableStateFlow(false)
    init {
        require(connection.identity.scope == owner.scope && connection.identity.deviceId == owner.deviceId)
    }

    /** Only the exact already-observed self-revocation result may cross this retirement.
     * Failed presentation cleanup cannot change the observed Master effect. */
    fun retireAfterObservedSelfRevocation() {
        retired.value = true
        try { retireOriginalSession() } catch (_: Exception) {
            // The installed sink invalidates its fence/lifecycle in finally. Failed negative
            // holders keep their existing root cleanup custody; this does not prove close.
        }
    }

    suspend fun <T> withAccess(body: suspend (Access) -> T): T {
        try {
            currentCoroutineContext().ensureActive()
            if (retired.value) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            try {
                val initial = currentView(lease)
                val access = Access(lease, initial)
                val result = body(access)
                access.observedSelfRevocation?.let { definite ->
                    // Only the exact already-observed terminal metadata may cross this deliberately
                    // retired lifetime. No read, lookup, cancellation or later mutation is exempted.
                    check(result == definite && retired.value)
                    return result
                }
                val final = currentView(lease)
                if (final.sessionId != initial.sessionId || final.generation != initial.generation || final.policy.revision != initial.policy.revision)
                    throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                access.checkCurrent()
                return result
            } finally { lease.destroy() }
        } catch (failure: GatewayFailure) {
            if (failure.problem == LocalServiceProblem.SESSION_ENDED && retired.compareAndSet(false, true)) {
                // Terminal observations must retire the exact attachment even when read/ACK
                // returns a compatible generic refusal. Ordinary target DENIED and temporary
                // unavailability are not terminal identity facts. The installed sink fences
                // only this original adapter; it cannot retire a successor attachment.
                try { retireOriginalSession() } catch (_: Exception) {
                    // The actual sink invalidates its session fence/lifecycle in finally.
                    // Failed negative holders retain their existing root cleanup custody;
                    // their failure cannot restore access or change this terminal fact.
                }
            }
            throw failure
        }
    }
    suspend fun currentView(lease: LocalWorkflowCredentialLease): LocalSessionView {
        currentCoroutineContext().ensureActive()
        if (retired.value || !connection.isCurrent() || !lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        val view = when (val result = lease.observeSession(connection.authentication)) {
            is LocalSessionViewResult.Current -> result.view
            LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        }
        if (retired.value || !connection.isCurrent() || !lease.isCurrent() || view.actorId != owner.actorId || view.deviceId != owner.deviceId ||
            view.scope != owner.scope) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return view
    }
    inner class Access(val lease: LocalWorkflowCredentialLease, val view: LocalSessionView) {
        var observedSelfRevocation: LocalAdministrationResult.Known? = null
        fun isOwnSessionRevocation(command: LocalAdministrationCommand): Boolean =
            command.action == LocalAdministrationAction.REVOKE_SESSION && command.targetId == owner.actorId &&
                command.sessionId == view.sessionId
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (retired.value || !connection.isCurrent() || !lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        }
    }

    fun hasGrant(view: LocalSessionView, directory: LocalAdministrationDirectory): Boolean {
        val permission = Permission(if (directory == LocalAdministrationDirectory.ACCOUNTS)
            SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION else SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER)
        val grants = view.policy.grants.filter { it.permission == permission && (it.scope == owner.scope || it.scope == owner.scope.company()) &&
            (it.namedSubjectId == null || it.namedSubjectId == owner.actorId) }
        // A denial for one named target must not hide all other management targets. This is
        // only an area preflight; the master evaluates the actual read/action binding afresh.
        return grants.any { it.effect == GrantEffect.ALLOW } && grants.none { it.effect == GrantEffect.DENY && it.targetIds == null }
    }
    fun requireGrant(view: LocalSessionView, directory: LocalAdministrationDirectory) {
        if (!hasGrant(view, directory)) throw GatewayFailure(LocalServiceProblem.DENIED)
    }
}

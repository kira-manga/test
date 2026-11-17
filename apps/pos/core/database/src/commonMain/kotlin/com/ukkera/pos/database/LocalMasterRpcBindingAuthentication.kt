package com.ukkera.pos.database

import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/** One actual protected session read, not a cached grant or a resource authorization. */
internal class RoomRpcBindingIdentity(val principal: LocalEmployeePrincipal,
    private val remainingAtObservationMillis: Long, private val began: TimeMark) {
    fun remainingMillis(): Long? {
        val elapsed = began.elapsedNow()
        if (elapsed.isNegative()) return null
        return (remainingAtObservationMillis - elapsed.inWholeMilliseconds).takeIf { it > 0 }
    }
}

internal sealed interface RoomRpcBindingResult {
    class Authenticated(val identity: RoomRpcBindingIdentity) : RoomRpcBindingResult
    data object Denied : RoomRpcBindingResult
    data object Unavailable : RoomRpcBindingResult
    data object Busy : RoomRpcBindingResult
    data object StorageUncertain : RoomRpcBindingResult
}

/**
 * One-use sealed identity and expiry ceiling from this Master's actual protected current
 * session operation. Only the exact credentials and original awaiting callback may consume
 * it. It supplies no device admission, permission, operation result or disclosure authority.
 * The Host must still freshly join the new SDK resource and authorize the protected query.
 */
public class LocalMasterRpcBindingAuthentication internal constructor(
    private val credentials: LocalGatewayCredentials,
    private val identity: RoomRpcBindingIdentity,
    private val caller: CoroutineContext,
) {
    private val consumed = MutableStateFlow(false)

    /** Scheduling ceiling only. Time spent after the protected observation is subtracted. */
    public fun remainingLifetimeMillis(): Long? = identity.remainingMillis()

    /** No accepting callback, caller-supplied identity or reusable verification result. */
    public suspend fun consume(actual: LocalGatewayCredentials): Pair<LocalEmployeePrincipal, Long>? {
        val current = currentCoroutineContext()
        current.ensureActive()
        caller.ensureActive()
        if (actual !== credentials || current[Job] !== caller[Job] || !consumed.compareAndSet(false, true)) return null
        return identity.remainingMillis()?.let { identity.principal to it }
    }

    override fun toString(): String = "LocalMasterRpcBindingAuthentication([REDACTED])"
}

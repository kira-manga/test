package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalClientCleanupStatus
import com.ukkera.pos.app.SignInFailure
import com.ukkera.pos.app.SignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Presentation only. A role here grants no native authority, RPC access or employee session. */
public enum class LocalClientRole { MASTER, CLIENT }

/**
 * Shared application gateway publication and stale-sign-in fencing. Platforms publish only
 * their own actual native-connected client; sessions never migrate to a replacement owner.
 * The callbacks describe that already-owned client's lifetime, not an authorization provider.
 */
public class LocalClientPublication : BranchUiGateway {
    private class Publication(val owner: Any, val gateway: BranchUiGateway, val role: LocalClientRole,
        val isCurrent: () -> Boolean, val deny: () -> Unit)
    private val current = MutableStateFlow<Publication?>(null)

    public val role: Flow<LocalClientRole?> = current.map { value ->
        value?.takeIf { it.isCurrent() }?.role
    }.distinctUntilChanged()

    public fun publish(owner: Any, gateway: BranchUiGateway, role: LocalClientRole,
        isCurrent: () -> Boolean, denyNewWork: () -> Unit) {
        check(isCurrent() && current.compareAndSet(null, Publication(owner, gateway, role, isCurrent, denyNewWork)))
    }

    public fun withdraw(owner: Any) {
        val captured = current.value ?: return
        if (captured.owner === owner && current.compareAndSet(captured, null)) captured.deny()
    }

    override val configured: Boolean get() = current.value?.isCurrent?.invoke() == true
    override val signInSecretMaxLength: Int get() {
        val captured = current.value?.takeIf { it.isCurrent() } ?: return 256
        val maximum = captured.gateway.signInSecretMaxLength
        return if (current.value === captured && captured.isCurrent()) maximum else 256
    }
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
        signIn(account, secret, false)

    override suspend fun signIn(account: String, secret: CharArray, explicitDeviceMove: Boolean): SignInResult<BranchUiSession> {
        try {
            val captured = current.value?.takeIf { it.isCurrent() }
                ?: return SignInResult.Rejected(SignInFailure.NOT_CONFIGURED)
            val result = captured.gateway.signIn(account, secret, explicitDeviceMove)
            if (current.value !== captured || !captured.isCurrent()) {
                if (result is SignInResult.Authenticated) result.session.close()
                return SignInResult.Rejected(SignInFailure.UNAVAILABLE)
            }
            return result
        } finally { secret.fill('\u0000') }
    }

    override suspend fun cleanupStatus(): LocalClientCleanupStatus = cleanup(retry = false)
    override suspend fun retryCleanup(): LocalClientCleanupStatus = cleanup(retry = true)

    private suspend fun cleanup(retry: Boolean): LocalClientCleanupStatus {
        val captured = current.value?.takeIf { it.isCurrent() } ?: return LocalClientCleanupStatus(false)
        val result = if (retry) captured.gateway.retryCleanup() else captured.gateway.cleanupStatus()
        return if (current.value === captured && captured.isCurrent()) result else LocalClientCleanupStatus(false)
    }
}

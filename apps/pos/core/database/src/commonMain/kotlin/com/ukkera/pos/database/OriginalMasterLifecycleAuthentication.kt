package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Same original peer/security services only; lifecycle.apply still checks current authority
 * and the complete original request inside its protected transaction. Owns no caller secret. */
internal suspend fun authenticateOriginalLifecycle(authentication: LocalAuthenticationService,
    lifecycle: LocalMasterLifecycleService, peer: LocalAuthenticatedPeer,
    login: LocalLoginIdentifier, password: CharArray,
    request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult {
    currentCoroutineContext().ensureActive()
    if (!peer.isCurrent()) return LocalMasterLifecycleResult.Unavailable
    var tokens: LocalSessionTokens? = null
    try {
        when (val authenticated = authentication.signIn(peer.connectionId, login, password,
            explicitDeviceMove = true)) {
            is LocalAuthenticationResult.Authenticated -> tokens = authenticated.tokens
            is LocalAuthenticationResult.Denied -> return if (authenticated.reason == LocalAuthenticationDenial.SERVICE_BUSY)
                LocalMasterLifecycleResult.Busy
            else LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.AUTHENTICATION_REFUSED)
            LocalAuthenticationResult.Unavailable -> return LocalMasterLifecycleResult.Unavailable
            LocalAuthenticationResult.StorageUncertain -> return LocalMasterLifecycleResult.StorageUncertain
        }
        currentCoroutineContext().ensureActive()
        if (!peer.isCurrent()) return LocalMasterLifecycleResult.Unavailable
        val result = lifecycle.apply(LocalGatewayCredentials(peer.connectionId, checkNotNull(tokens).access), request)
        currentCoroutineContext().ensureActive()
        return if (!peer.isCurrent()) LocalMasterLifecycleResult.Unavailable else result
    } finally { tokens?.destroy() }
}

package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/** Fixed diagnostic phases only: no credentials, identifiers, authority or provider callbacks. */
internal enum class OriginalLifecyclePhase {
    NATIVE_ADMISSION, NATIVE_SLOT_ENTERED, ENDPOINT_SELECTION, ENDPOINT_SUBMITTED,
    PEER_UNAVAILABLE, AUTHENTICATION_ENTERED, AUTHENTICATION_AUTHENTICATED,
    AUTHENTICATION_DENIED, AUTHENTICATION_BUSY, AUTHENTICATION_UNAVAILABLE,
    AUTHENTICATION_STORAGE_UNCERTAIN, APPLY_ENTERED, APPLY_COMMITTED, APPLY_DENIED,
    APPLY_BUSY, APPLY_UNAVAILABLE, APPLY_STORAGE_UNCERTAIN,
}

internal class OriginalLifecycleDiagnostics {
    private val observed = MutableStateFlow(OriginalLifecyclePhase.NATIVE_ADMISSION)
    internal val phase: OriginalLifecyclePhase get() = observed.value
    internal fun mark(phase: OriginalLifecyclePhase) { observed.value = phase }
}

/** Same original peer/security services only; lifecycle.apply still checks current authority
 * and the complete original request inside its protected transaction. Owns no caller secret. */
internal suspend fun authenticateOriginalLifecycle(authentication: LocalAuthenticationService,
    lifecycle: LocalMasterLifecycleService, peer: LocalAuthenticatedPeer,
    login: LocalLoginIdentifier, password: CharArray,
    request: LocalMasterLifecycleRequest, diagnostics: OriginalLifecycleDiagnostics): LocalMasterLifecycleResult {
    currentCoroutineContext().ensureActive()
    if (!peer.isCurrent()) {
        diagnostics.mark(OriginalLifecyclePhase.PEER_UNAVAILABLE)
        return LocalMasterLifecycleResult.Unavailable
    }
    var tokens: LocalSessionTokens? = null
    try {
        diagnostics.mark(OriginalLifecyclePhase.AUTHENTICATION_ENTERED)
        when (val authenticated = authentication.signIn(peer.connectionId, login, password,
            explicitDeviceMove = true)) {
            is LocalAuthenticationResult.Authenticated -> {
                tokens = authenticated.tokens
                diagnostics.mark(OriginalLifecyclePhase.AUTHENTICATION_AUTHENTICATED)
            }
            is LocalAuthenticationResult.Denied -> return if (authenticated.reason == LocalAuthenticationDenial.SERVICE_BUSY) {
                diagnostics.mark(OriginalLifecyclePhase.AUTHENTICATION_BUSY)
                LocalMasterLifecycleResult.Busy
            } else {
                diagnostics.mark(OriginalLifecyclePhase.AUTHENTICATION_DENIED)
                LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.AUTHENTICATION_REFUSED)
            }
            LocalAuthenticationResult.Unavailable -> {
                diagnostics.mark(OriginalLifecyclePhase.AUTHENTICATION_UNAVAILABLE)
                return LocalMasterLifecycleResult.Unavailable
            }
            LocalAuthenticationResult.StorageUncertain -> {
                diagnostics.mark(OriginalLifecyclePhase.AUTHENTICATION_STORAGE_UNCERTAIN)
                return LocalMasterLifecycleResult.StorageUncertain
            }
        }
        currentCoroutineContext().ensureActive()
        if (!peer.isCurrent()) {
            diagnostics.mark(OriginalLifecyclePhase.PEER_UNAVAILABLE)
            return LocalMasterLifecycleResult.Unavailable
        }
        diagnostics.mark(OriginalLifecyclePhase.APPLY_ENTERED)
        val result = lifecycle.apply(LocalGatewayCredentials(peer.connectionId, checkNotNull(tokens).access), request)
        diagnostics.mark(when (result) {
            is LocalMasterLifecycleResult.Committed -> OriginalLifecyclePhase.APPLY_COMMITTED
            is LocalMasterLifecycleResult.Denied -> OriginalLifecyclePhase.APPLY_DENIED
            LocalMasterLifecycleResult.Busy -> OriginalLifecyclePhase.APPLY_BUSY
            LocalMasterLifecycleResult.Unavailable -> OriginalLifecyclePhase.APPLY_UNAVAILABLE
            LocalMasterLifecycleResult.StorageUncertain -> OriginalLifecyclePhase.APPLY_STORAGE_UNCERTAIN
        })
        currentCoroutineContext().ensureActive()
        return if (!peer.isCurrent()) {
            diagnostics.mark(OriginalLifecyclePhase.PEER_UNAVAILABLE)
            LocalMasterLifecycleResult.Unavailable
        } else result
    } finally { tokens?.destroy() }
}

package com.ukkera.pos.android.independent.aosfsetupv2

import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import org.json.JSONObject

/** Real native signIn/current/refresh/logout lifetime over the exact reopened original peer. */
internal class AosfSessionLifetime(
    private val owner: AosfNativeOwner,
    private val evidence: AosfEvidence,
) {
    private val owned = mutableListOf<LocalSessionTokens>()

    fun own(tokens: LocalSessionTokens): LocalSessionTokens {
        owned += tokens
        return tokens
    }

    suspend fun signIn(login: LocalLoginIdentifier, password: CharArray): LocalSessionTokens {
        val original = checkNotNull(owner.original)
        check(original.isCurrent() && original.peer.isCurrent())
        val copy = password.copyOf()
        val reply = try {
            original.client.signIn(original.peer.connectionId, login, copy)
        } finally {
            copy.fill('\u0000')
        }
        val tokens = (reply as? LocalAuthenticationResult.Authenticated)?.tokens
            ?: error("Actual local sign-in was not authenticated: $reply")
        return own(tokens)
    }

    suspend fun current(tokens: LocalSessionTokens): LocalSessionViewResult =
        current(credentials(tokens))

    suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        checkNotNull(owner.original).client.current(credentials)

    suspend fun refresh(tokens: LocalSessionTokens): LocalAuthenticationResult =
        refresh(credentials(tokens, refresh = true))

    suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        val reply = checkNotNull(owner.original).client.refresh(credentials)
        if (reply is LocalAuthenticationResult.Authenticated) own(reply.tokens)
        return reply
    }

    suspend fun logout(tokens: LocalSessionTokens): LocalSignOutResult =
        checkNotNull(owner.original).client.logout(credentials(tokens))

    suspend fun requireCurrent(tokens: LocalSessionTokens, label: String): LocalSessionView {
        val view = (current(tokens) as? LocalSessionViewResult.Current)?.view
            ?: error("$label is not a current authenticated local session")
        return view
    }

    suspend fun requireRetired(tokens: LocalSessionTokens, label: String) {
        check(current(tokens) is LocalSessionViewResult.Denied) { "$label current was accepted after retirement" }
        check(refresh(tokens) is LocalAuthenticationResult.Denied) { "$label refresh was accepted after retirement" }
        evidence.event("RETIRED_SESSION_REJECTED", JSONObject().put("label", label))
    }

    suspend fun retire(tokens: LocalSessionTokens, label: String) {
        val result = logout(tokens)
        check(result == LocalSignOutResult.Revoked) { "$label explicit logout was not confirmed: $result" }
        evidence.event("EXPLICIT_SESSION_RETIRED", JSONObject().put("label", label)
            .put("logoutResult", result.toString()))
        requireRetired(tokens, label)
    }

    fun tampered(tokens: LocalSessionTokens): LocalGatewayCredentials {
        val original = checkNotNull(owner.original)
        val bytes = tokens.access.useBytes { it.copyOf() }
        try {
            check(bytes.isNotEmpty())
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            return LocalGatewayCredentials(original.peer.connectionId, OpaqueCredential(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    fun foreignConnection(tokens: LocalSessionTokens): LocalGatewayCredentials =
        LocalGatewayCredentials(DomainId.random(), tokens.access)

    fun credentials(tokens: LocalSessionTokens, refresh: Boolean = false) =
        LocalGatewayCredentials(checkNotNull(owner.original).peer.connectionId,
            if (refresh) tokens.refresh else tokens.access)

    fun destroyAll() {
        owned.distinct().forEach { runCatching { it.destroy() } }
        owned.clear()
    }
}

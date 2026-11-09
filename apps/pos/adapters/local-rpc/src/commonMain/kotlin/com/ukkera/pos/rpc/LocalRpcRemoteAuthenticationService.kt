package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.types.DomainId

/**
 * Authentication only, through one still-connected, retained selected-original SDK Client.
 * [connectionId] is an opaque local correlation supplied by that owner. It is checked locally
 * and is never sent to the Master, whose physical callback supplies its own connection identity.
 * This port provides no business gateway, trusted peer, admission or permission capability.
 *
 * Every explicit call gets a new CSPRNG exchange ID and one SDK invocation attempt. Transport
 * failures retain the SDK's execution evidence: they do not prove rollback or permit an automatic
 * authentication retry. Received Master clock domains/deadlines remain display data only.
 */
public class LocalRpcRemoteAuthenticationService internal constructor(
    private val owner: LocalRpcOriginalClientOwner,
    public val connectionId: DomainId,
) : LocalAuthenticationService {
    /**
     * Synchronous sample of this exact retained local connection. Recheck before UI adoption;
     * this does not replace the Master's fresh authorization or promise future connectivity.
     */
    public fun isCurrent(): Boolean = owner.authenticationServiceCurrent(this, connectionId)

    override public suspend fun signIn(
        connectionId: DomainId,
        login: LocalLoginIdentifier,
        password: CharArray,
        explicitDeviceMove: Boolean,
    ): LocalAuthenticationResult = owner.authenticationExchange(
        service = this, connectionId = connectionId,
        snapshot = {
            if (password.size !in 4..1024) null else LocalRpcAuthenticationRequest(
                exchangeId = DomainId.random().value, action = LocalRpcAuthenticationAction.LOGIN,
                login = login.value, password = password.copyOf(), explicitDeviceMove = explicitDeviceMove,
            )
        },
        consume = { it?.authenticationResult() ?: LocalAuthenticationResult.Unavailable },
        unavailable = { LocalAuthenticationResult.Unavailable },
        busy = { LocalAuthenticationResult.Denied(LocalAuthenticationDenial.SERVICE_BUSY) },
        destroy = { if (it is LocalAuthenticationResult.Authenticated) it.tokens.destroy() },
    )

    override public suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult =
        owner.authenticationExchange(
            service = this, connectionId = credentials.connectionId,
            snapshot = { bearerRequest(LocalRpcAuthenticationAction.REFRESH, credentials) },
            consume = { it?.authenticationResult() ?: LocalAuthenticationResult.Unavailable },
            unavailable = { LocalAuthenticationResult.Unavailable },
            busy = { LocalAuthenticationResult.Denied(LocalAuthenticationDenial.SERVICE_BUSY) },
            destroy = { if (it is LocalAuthenticationResult.Authenticated) it.tokens.destroy() },
        )

    override public suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        owner.authenticationExchange(
            service = this, connectionId = credentials.connectionId,
            snapshot = { bearerRequest(LocalRpcAuthenticationAction.CURRENT, credentials) },
            consume = { it?.sessionViewResult() ?: LocalSessionViewResult.Unavailable },
            unavailable = { LocalSessionViewResult.Unavailable }, busy = { LocalSessionViewResult.Busy },
            destroy = {},
        )

    override public suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult =
        owner.authenticationExchange(
            service = this, connectionId = credentials.connectionId,
            snapshot = { bearerRequest(LocalRpcAuthenticationAction.LOGOUT, credentials) },
            consume = { it?.signOutResult() ?: LocalSignOutResult.Unavailable },
            unavailable = { LocalSignOutResult.Unavailable }, busy = { LocalSignOutResult.Busy },
            destroy = {},
        )

    private fun bearerRequest(
        action: LocalRpcAuthenticationAction,
        credentials: LocalGatewayCredentials,
    ): LocalRpcAuthenticationRequest? = credentials.bearer.useBytes { bytes ->
        if (bytes.size != 52) null else LocalRpcAuthenticationRequest(
            exchangeId = DomainId.random().value, action = action, bearer = bytes.copyOf(),
        )
    }

    override fun toString(): String = "LocalRpcRemoteAuthenticationService([REDACTED])"
}

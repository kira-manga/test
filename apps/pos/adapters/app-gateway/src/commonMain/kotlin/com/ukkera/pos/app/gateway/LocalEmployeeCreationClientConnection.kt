package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterEmployeeCreationClient
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Explicit remote creation composition on an already retained authentication connection.
 * The native root must bind [creation] and [currentLifetime] to that exact original Client;
 * a supplied service, scalar identity or this carrier does not prove that association.
 * Every creation call still requires the Master's current creator/target authorization.
 *
 * This connection exposes no business client, general administration, authenticated local
 * peer or service incarnation. A remote attachment keeps its distinct credential-journal tag.
 * Nothing here acquires, pairs, repairs or replaces native state, or installs a missing RPC.
 */
public class LocalEmployeeCreationClientConnection(
    public val creation: LocalMasterEmployeeCreationClient,
    private val authenticationConnection: LocalAuthenticationClientConnection,
    private val currentLifetime: () -> Boolean,
) : LocalAuthenticationClientConnection {
    private val denied = MutableStateFlow(false)
    override public val authentication: LocalAuthenticationService = authenticationConnection.authentication
    override public val connectionId: DomainId = authenticationConnection.connectionId
    override public val identity: LocalClientDeviceIdentity = authenticationConnection.identity
    override public val labels: LocalBranchDisplayLabels = authenticationConnection.labels
    override public val credentialBinding: LocalClientAuthenticationBinding = authenticationConnection.credentialBinding

    init {
        require(labels.scope == identity.scope)
        require(credentialBinding is LocalClientAuthenticationBinding.RemoteOriginalAttachment &&
            credentialBinding.connectionId == connectionId)
    }

    /** Deny a changed source binding as well as a retired original owner. This never follows a
     * replacement service, revives a denied holder or interprets a local connection correlation as a Master incarnation. */
    override public fun isCurrent(): Boolean {
        if (denied.value) return false
        val current = try {
            authenticationConnection.authentication === authentication &&
                authenticationConnection.connectionId == connectionId &&
                authenticationConnection.identity == identity &&
                authenticationConnection.credentialBinding == credentialBinding &&
                authenticationConnection.isCurrent() && currentLifetime()
        } catch (_: Exception) { false }
        if (!current) denied.value = true
        return current && !denied.value
    }

    override fun toString(): String = "LocalEmployeeCreationClientConnection([REDACTED])"
}

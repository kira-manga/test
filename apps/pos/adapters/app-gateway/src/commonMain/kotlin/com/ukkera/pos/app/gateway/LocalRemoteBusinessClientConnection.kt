package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.types.DomainId

/**
 * An actual original LAN Client with the fixed LAN business routes. The native root supplies
 * its qualified device custody and exact original lifetime. This correlation is neither a
 * Master service incarnation nor an appointed peer. The optional physical companion must come
 * from the SAME native paired root. Administration, publications and setup closure use separate
 * ports; this carrier grants none of their authority.
 */
public class LocalRemoteBusinessClientConnection(
    public val endpoint: LocalMasterClient,
    override public val connectionId: DomainId,
    override public val identity: LocalClientDeviceIdentity,
    override public val labels: LocalBranchDisplayLabels,
    public val retailPhysicalBinding: LocalRemoteRetailPhysicalBinding? = null,
    private val currentLifetime: () -> Boolean,
) : LocalAuthenticationClientConnection {
    public val installed: LocalWorkflowInstallation = LocalWorkflowInstallation.remoteBusiness()
    override public val authentication: LocalAuthenticationService get() = endpoint
    override public val credentialBinding: LocalClientAuthenticationBinding =
        LocalClientAuthenticationBinding.RemoteOriginalAttachment(connectionId)

    init {
        require(labels.scope == identity.scope)
        require(retailPhysicalBinding == null || retailPhysicalBinding.endpoint === endpoint &&
            retailPhysicalBinding.connectionId == connectionId && retailPhysicalBinding.identity == identity)
    }

    override public fun isCurrent(): Boolean = try {
        endpoint.health.value == LocalMasterEndpointHealth.ACTIVE && currentLifetime()
    } catch (_: Exception) { false }

    override fun toString(): String = "LocalRemoteBusinessClientConnection([REDACTED])"
}

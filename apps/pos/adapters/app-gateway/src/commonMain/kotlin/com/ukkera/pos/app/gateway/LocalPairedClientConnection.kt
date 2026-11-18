package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.types.DomainId

/**
 * Installed paired-Client attachment selection, shared by the Android and desktop native roots.
 * Only branch business requests its SAME root's physical companion. A missing companion leaves
 * ordinary business available without claiming physical custody. This function owns no native
 * resource: the retained credential root owns acquisition, recovery and confirmed cleanup.
 */
public suspend fun localPairedClientConnection(
    authentication: LocalAuthenticationService,
    connectionId: DomainId,
    identity: LocalClientDeviceIdentity,
    labels: LocalBranchDisplayLabels,
    profile: LocalMasterRpcWorkflowProfile?,
    acquirePhysicalBinding: suspend () -> LocalRemoteRetailPhysicalBinding? = { null },
    currentLifetime: () -> Boolean,
): LocalAuthenticationClientConnection {
    require(labels.scope == identity.scope)
    if (profile == null) return LocalAuthenticationOnlyConnection(authentication, connectionId,
        identity, labels, currentLifetime)
    val endpoint = requireNotNull(authentication as? LocalMasterClient) { "Business endpoint is unavailable" }
    return if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS) {
        LocalRemoteBusinessClientConnection(endpoint, connectionId, identity, labels,
            acquirePhysicalBinding(), currentLifetime)
    } else {
        LocalDirectoryClientConnection(endpoint, connectionId, identity, labels, profile,
            supplierReferences = profile == LocalMasterRpcWorkflowProfile.SUPPLIERS, currentLifetime = currentLifetime)
    }
}

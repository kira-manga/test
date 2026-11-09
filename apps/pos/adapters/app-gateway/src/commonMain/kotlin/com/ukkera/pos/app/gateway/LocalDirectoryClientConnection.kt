package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.types.DomainId

/**
 * One actual retained original Client, with one immutable supported business profile installed:
 * supplier/warehouse directory, current-document reports, normalized stock transfers or station
 * Start/Ready or own unlocked retail order editing. The native root supplies its qualified device/branch custody and exact original
 * lifetime. This local
 * correlation is not an appointed-Master incarnation, authenticated peer or business authority.
 * No administration, company-publication or physical capability is installed by this carrier.
 */
public class LocalDirectoryClientConnection(
    public val endpoint: LocalMasterClient,
    override public val connectionId: DomainId,
    override public val identity: LocalClientDeviceIdentity,
    override public val labels: LocalBranchDisplayLabels,
    public val profile: LocalMasterRpcWorkflowProfile,
    supplierReferences: Boolean,
    private val currentLifetime: () -> Boolean,
) : LocalAuthenticationClientConnection {
    /** Existing directory compositions retain their original installed protocol versions. */
    public constructor(endpoint: LocalMasterClient, connectionId: DomainId, identity: LocalClientDeviceIdentity,
        labels: LocalBranchDisplayLabels, profile: LocalMasterRpcWorkflowProfile, currentLifetime: () -> Boolean) :
        this(endpoint, connectionId, identity, labels, profile, false, currentLifetime)

    /** Preserve existing supplier source callers, including their trailing lifetime lambda. */
    public constructor(endpoint: LocalMasterClient, connectionId: DomainId, identity: LocalClientDeviceIdentity,
        labels: LocalBranchDisplayLabels, currentLifetime: () -> Boolean) :
        this(endpoint, connectionId, identity, labels, LocalMasterRpcWorkflowProfile.SUPPLIERS, false, currentLifetime)

    // The additive supplier reference profile requires explicit composition before any request.
    // It is neither remote capability negotiation nor Master authority; failed calls never downgrade.
    public val installed: LocalWorkflowInstallation = LocalWorkflowInstallation.directoryOnly(profile,
        supplierReferences = supplierReferences)
    override public val authentication: LocalAuthenticationService get() = endpoint
    override public val credentialBinding: LocalClientAuthenticationBinding =
        LocalClientAuthenticationBinding.RemoteOriginalAttachment(connectionId)

    init {
        require(labels.scope == identity.scope)
        require(!supplierReferences || profile == LocalMasterRpcWorkflowProfile.SUPPLIERS)
    }

    override public fun isCurrent(): Boolean = try {
        endpoint.health.value == LocalMasterEndpointHealth.ACTIVE && currentLifetime()
    } catch (_: Exception) { false }

    override fun toString(): String = "LocalDirectoryClientConnection([REDACTED])"
}

/** Source compatibility only; the original owner still fixes and checks its actual profile. */
public typealias LocalSupplierClientConnection = LocalDirectoryClientConnection

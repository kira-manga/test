package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.types.DomainId

/**
 * Client-local attachment binding, not Master authority or a remotely supplied physical identity.
 * Historical journals contain only [MasterServiceIncarnation]; a remote local correlation must
 * carry its distinct tag and never acquire the meaning of that historical field.
 */
public sealed interface LocalClientAuthenticationBinding {
    public data class MasterServiceIncarnation(public val serviceIncarnationId: DomainId) : LocalClientAuthenticationBinding {
        override fun toString(): String = "MasterServiceIncarnation([REDACTED])"
    }

    public data class RemoteOriginalAttachment(public val connectionId: DomainId) : LocalClientAuthenticationBinding {
        override fun toString(): String = "RemoteOriginalAttachment([REDACTED])"
    }
}

/**
 * Root-composed authentication on one actual retained connection. Coordinates and labels are
 * qualified by its native owner, not by a login body. This interface grants no trusted peer,
 * employee permission, installed business capability or native journal enrollment authority.
 */
public interface LocalAuthenticationClientConnection {
    public val authentication: LocalAuthenticationService
    public val connectionId: DomainId
    public val identity: LocalClientDeviceIdentity
    public val labels: LocalBranchDisplayLabels
    public val credentialBinding: LocalClientAuthenticationBinding

    /** Exact synchronous owner/root denial and retained connection sample; no blocking IO. */
    public fun isCurrent(): Boolean
}

/**
 * Explicit absence of business composition. The root supplies the same actual authentication
 * service and its owner's local connection correlation, with qualified device/branch custody.
 * The lifetime closure must observe that exact service/owner and root denial, not a READY flag.
 * Construction is composition only: neither this carrier nor scalar equality establishes trust.
 */
public class LocalAuthenticationOnlyConnection(
    override public val authentication: LocalAuthenticationService,
    override public val connectionId: DomainId,
    override public val identity: LocalClientDeviceIdentity,
    override public val labels: LocalBranchDisplayLabels,
    private val currentLifetime: () -> Boolean,
) : LocalAuthenticationClientConnection {
    override public val credentialBinding: LocalClientAuthenticationBinding =
        LocalClientAuthenticationBinding.RemoteOriginalAttachment(connectionId)

    init { require(labels.scope == identity.scope) }

    override public fun isCurrent(): Boolean = try { currentLifetime() } catch (_: Exception) { false }

    override fun toString(): String = "LocalAuthenticationOnlyConnection([REDACTED])"
}

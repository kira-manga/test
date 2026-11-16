package com.ukkera.pos.rpc

import com.ukkera.pos.types.DomainId

/**
 * A bounded presentation of an official SDK discovery claim. The opaque candidate ID belongs
 * to one retained Client lifetime. Neither a name, fingerprint claim nor [matchesOriginalMaster]
 * grants trust, device admission or employee access; connection rechecks the actual paired original
 * and the SDK's current discovery claim before cryptographic negotiation.
 */
public data class LocalRpcDiscoveredMaster(
    public val candidateId: DomainId,
    public val displayName: String,
    public val fingerprint: String,
    public val matchesOriginalMaster: Boolean,
)

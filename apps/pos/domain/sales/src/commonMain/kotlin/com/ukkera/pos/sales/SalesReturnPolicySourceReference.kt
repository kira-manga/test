package com.ukkera.pos.sales

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable

/** Distinct immutable sale-promise companion; never a current-policy or pricing reference. */
@Serializable
public data class SalesReturnPolicySourceReference(public val id: DomainId, public val digest: PayloadDigest)

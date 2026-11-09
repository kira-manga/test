package com.ukkera.pos.sales

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable

/**
 * Exact immutable, protected per-quote price-source companion. This reference is
 * copied to collection and original receipt history; it is not current pricing,
 * an authorization grant, or proof of the referenced source's provenance.
 * A trusted store must require the companion and its original retained producer.
 */
@Serializable
public data class SalesPriceSourceReference(public val id: DomainId, public val digest: PayloadDigest)

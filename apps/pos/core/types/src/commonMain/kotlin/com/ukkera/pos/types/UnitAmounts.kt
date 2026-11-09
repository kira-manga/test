package com.ukkera.pos.types

import kotlinx.serialization.Serializable

/** Exact configured/snapshotted price, not a posted amount. The owning command validates its sign. */
@Serializable
public data class UnitPrice(public val currency: Currency, public val amount: ExactDecimal) {
    init {
        if (amount.scale > 8) throw UnsupportedPrecisionException(amount.scale, 8)
    }

    public fun toFraction(): ExactRational = amount.toFraction()

    public companion object {
        public fun parse(currency: Currency, text: String): UnitPrice = UnitPrice(currency, ExactDecimal.parse(text, 8))
    }
}

/**
 * Exact configured/snapshotted cost. Repeating moving averages stay [ExactRational]; do not
 * force each ingredient/share through this decimal type or a monetary posting boundary.
 */
@Serializable
public data class UnitCost(public val currency: Currency, public val amount: ExactDecimal) {
    init {
        if (amount.scale > 8) throw UnsupportedPrecisionException(amount.scale, 8)
    }

    public fun toFraction(): ExactRational = amount.toFraction()

    public companion object {
        public fun parse(currency: Currency, text: String): UnitCost = UnitCost(currency, ExactDecimal.parse(text, 8))
    }
}

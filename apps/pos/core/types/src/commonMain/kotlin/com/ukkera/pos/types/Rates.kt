package com.ukkera.pos.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Explicit percent, so `14` means 14/100, NOT the untyped multiplier 14.
 * Nonnegative, at most eight fractional digits and the ExactDecimal 38-digit coefficient bound.
 * No statutory/business maximum is inferred: a discount or supported tax profile must apply
 * its own explicit ceiling using [requireAtMost]. This type deliberately represents >100%.
 */
@Serializable
public data class PercentageRate(public val percent: ExactDecimal) {
    init {
        if (percent.scale > 8) throw UnsupportedPrecisionException(percent.scale, 8)
        require(percent >= ExactDecimal.parse("0")) { "Percentage rate cannot be negative" }
    }

    public fun toFraction(): ExactRational = percent.toFraction() / ExactRational.ratio(100, 1)

    public fun requireAtMost(maximumPercent: ExactDecimal): PercentageRate {
        require(maximumPercent >= ExactDecimal.parse("0")) { "Percentage ceiling cannot be negative" }
        require(percent <= maximumPercent) { "Percentage exceeds its explicit ceiling" }
        return this
    }

    public companion object {
        public fun parse(text: String): PercentageRate = PercentageRate(ExactDecimal.parse(text, 8))
    }
}

/**
 * Positive source-to-target unit multiplier with bounded 64-bit ratio components.
 * The owning UOM snapshot binds source/target unit IDs. Conversion must reject a result
 * that cannot be stored exactly at the target Quantity scale, rather than rounding it.
 */
@Serializable(with = UnitConversionRateSerializer::class)
public class UnitConversionRate private constructor(
    public val numerator: Long,
    public val denominator: Long,
) {
    public fun toFraction(): ExactRational = ExactRational.ratio(numerator, denominator)

    public fun canonicalString(): String = "$numerator/$denominator"

    override fun toString(): String = canonicalString()

    override fun equals(other: Any?): Boolean =
        other is UnitConversionRate && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    public companion object {
        public fun ratio(numerator: Long, denominator: Long): UnitConversionRate {
            require(numerator > 0 && denominator > 0) { "Unit-conversion ratio components must be positive" }
            var a = numerator
            var b = denominator
            while (b != 0L) {
                val remainder = a % b
                a = b
                b = remainder
            }
            return UnitConversionRate(numerator / a, denominator / a)
        }

        public fun parseCanonical(text: String): UnitConversionRate {
            require(text.length <= 39) { "Unit-conversion ratio exceeds its wire bound" }
            val rational = ExactRational.parseCanonical(text)
            val numerator = rational.canonicalString().substringBefore('/').toLongOrNull()
            val denominator = rational.canonicalString().substringAfter('/').toLongOrNull()
            require(numerator != null && denominator != null) { "Unit-conversion ratio exceeds signed 64-bit bounds" }
            return ratio(numerator, denominator)
        }
    }
}

public object UnitConversionRateSerializer : KSerializer<UnitConversionRate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UnitConversionRate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UnitConversionRate): Unit = encoder.encodeString(value.canonicalString())

    override fun deserialize(decoder: Decoder): UnitConversionRate =
        decodeScalar(decoder, "UnitConversionRate", 39) { UnitConversionRate.parseCanonical(it) }
}

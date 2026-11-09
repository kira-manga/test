package com.ukkera.pos.types

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Bounded input decimal; normalized for semantic hashing. Parsing never rounds.
 * The caller supplies the domain precision (quantity <= 6, unit price <= 8).
 */
@Serializable(with = ExactDecimalSerializer::class)
public class ExactDecimal private constructor(
    internal val coefficient: BigInteger,
    public val scale: Int,
) : Comparable<ExactDecimal> {
    public fun toFraction(): ExactRational = ExactRational.of(coefficient, powerOfTen(scale))

    /** Useful for stock quantities. Excess precision is an error, not rounding. */
    public fun toScaledLongExact(targetScale: Int): Long {
        require(targetScale in 0..18) { "Unsupported scale" }
        val result = if (targetScale >= scale) {
            coefficient * powerOfTen(targetScale - scale)
        } else {
            val (whole, remainder) = coefficient.divideAndRemainder(powerOfTen(scale - targetScale))
            if (remainder != BigInteger.ZERO) throw ArithmeticException("Exact scale conversion would lose precision")
            whole
        }
        return result.longValue(true)
    }

    public fun canonicalString(): String {
        if (scale == 0) return coefficient.toString()
        val digits = coefficient.abs().toString().padStart(scale + 1, '0')
        val sign = if (coefficient.signum() < 0) "-" else ""
        return sign + digits.dropLast(scale) + "." + digits.takeLast(scale)
    }

    override fun toString(): String = canonicalString()

    override fun compareTo(other: ExactDecimal): Int = toFraction().compareTo(other.toFraction())

    override fun equals(other: Any?): Boolean =
        other is ExactDecimal && coefficient == other.coefficient && scale == other.scale

    override fun hashCode(): Int = 31 * coefficient.hashCode() + scale

    public companion object {
        private val decimalPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
        private val ten = BigInteger.fromLong(10)

        public fun parse(text: String, maxFractionDigits: Int = 8): ExactDecimal {
            require(maxFractionDigits in 0..18) { "Unsupported precision contract" }
            require(text.length <= 40 && decimalPattern.matches(text)) { "Invalid bounded decimal" }
            val fractionDigits = text.substringAfter('.', "").length
            if (fractionDigits > maxFractionDigits) throw UnsupportedPrecisionException(fractionDigits, maxFractionDigits)
            val coefficientText = text.replace(".", "")
            require(coefficientText.count { it in '0'..'9' } <= 38) { "Decimal coefficient exceeds 38 digits" }
            var coefficient = BigInteger.parseString(coefficientText, 10)
            var scale = fractionDigits
            if (coefficient == BigInteger.ZERO) return ExactDecimal(BigInteger.ZERO, 0)
            while (scale > 0) {
                val (quotient, remainder) = coefficient.divideAndRemainder(ten)
                if (remainder != BigInteger.ZERO) break
                coefficient = quotient
                scale--
            }
            return ExactDecimal(coefficient, scale)
        }

        /** Wire decoding is strict; user-facing [parse] may normalize trailing zeros/negative zero. */
        public fun parseCanonical(text: String, maxFractionDigits: Int = 18): ExactDecimal {
            val value = parse(text, maxFractionDigits)
            require(value.canonicalString() == text) { "Decimal string is not canonical" }
            return value
        }
    }
}

public object ExactDecimalSerializer : KSerializer<ExactDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ExactDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExactDecimal): Unit = encoder.encodeString(value.canonicalString())

    override fun deserialize(decoder: Decoder): ExactDecimal =
        decodeScalar(decoder, "ExactDecimal", 40) { ExactDecimal.parseCanonical(it) }
}

/** Exact intermediate fractions: no binary floating point or premature tax rounding. */
@Serializable(with = ExactRationalSerializer::class)
public class ExactRational private constructor(
    private val numerator: BigInteger,
    private val denominator: BigInteger,
) : Comparable<ExactRational> {
    public operator fun plus(other: ExactRational): ExactRational =
        of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)

    public operator fun minus(other: ExactRational): ExactRational =
        of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)

    public operator fun times(other: ExactRational): ExactRational =
        of(numerator * other.numerator, denominator * other.denominator)

    public operator fun div(other: ExactRational): ExactRational =
        of(numerator * other.denominator, denominator * other.numerator)

    public operator fun unaryMinus(): ExactRational = of(BigInteger.ZERO - numerator, denominator)

    override fun compareTo(other: ExactRational): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    /** Reduced signed numerator and positive denominator; zero is always `0/1`. */
    public fun canonicalString(): String = "$numerator/$denominator"

    override fun toString(): String = canonicalString()

    override fun equals(other: Any?): Boolean =
        other is ExactRational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    /** Exact scaled integer conversion, rejecting fractional loss and signed 64-bit overflow. */
    public fun toScaledLongExact(scale: Int): Long {
        require(scale in 0..18) { "Unsupported exact scale" }
        val (whole, remainder) = (numerator * powerOfTen(scale)).divideAndRemainder(denominator)
        if (remainder != BigInteger.ZERO) throw ArithmeticException("Exact scale conversion would lose precision")
        return whole.longValue(true)
    }

    /** Mathematical floor, including negative inputs; not truncation toward zero. */
    public fun floorScaled(scale: Int): Long {
        require(scale in 0..18) { "Unsupported floor scale" }
        val (whole, remainder) = (numerator * powerOfTen(scale)).divideAndRemainder(denominator)
        val floor = if (numerator.signum() < 0 && remainder != BigInteger.ZERO) whole - BigInteger.ONE else whole
        return floor.longValue(true)
    }

    /** Explicit operational HALF_UP posting; negative halves round away from zero. */
    public fun toMoney(currency: Currency): Money = Money(currency, roundHalfUp(currency.fractionDigits))

    public fun roundHalfUp(scale: Int): Long {
        require(scale in 0..18) { "Unsupported posting scale" }
        val magnitude = numerator.abs() * powerOfTen(scale)
        val (whole, remainder) = magnitude.divideAndRemainder(denominator)
        val rounded = if (remainder * BigInteger.fromLong(2) >= denominator) whole + BigInteger.ONE else whole
        return (if (numerator.signum() < 0) BigInteger.ZERO - rounded else rounded).longValue(true)
    }

    public companion object {
        /** Engineering/resource bound, not an approximation. Operations exceeding it fail exactly. */
        public const val MAX_COMPONENT_DIGITS: Int = 1_024

        private val canonicalPattern = Regex("(?:0|-?[1-9][0-9]*)/[1-9][0-9]*")

        public fun ratio(numerator: Long, denominator: Long): ExactRational =
            of(BigInteger.fromLong(numerator), BigInteger.fromLong(denominator))

        public fun parseCanonical(text: String): ExactRational {
            require(text.length <= 2 * MAX_COMPONENT_DIGITS + 2 && canonicalPattern.matches(text)) {
                "Invalid bounded rational string"
            }
            val numeratorText = text.substringBefore('/')
            val denominatorText = text.substringAfter('/')
            require(numeratorText.removePrefix("-").length <= MAX_COMPONENT_DIGITS &&
                denominatorText.length <= MAX_COMPONENT_DIGITS) { "Rational component exceeds digit bound" }
            val result = of(BigInteger.parseString(numeratorText, 10), BigInteger.parseString(denominatorText, 10))
            require(result.canonicalString() == text) { "Rational string is not reduced/canonical" }
            return result
        }

        internal fun of(numerator: BigInteger, denominator: BigInteger): ExactRational {
            if (denominator == BigInteger.ZERO) throw ArithmeticException("Division by zero")
            val sign = if (denominator.signum() < 0) -1 else 1
            var n = if (sign < 0) BigInteger.ZERO - numerator else numerator
            var d = denominator.abs()
            // Reduction bounds growth across supported composite calculations.
            var a = n.abs()
            var b = d
            while (b != BigInteger.ZERO) {
                val remainder = a.divideAndRemainder(b).second
                a = b
                b = remainder
            }
            if (a != BigInteger.ZERO) {
                n /= a
                d /= a
            }
            if (n.abs().toString().length > MAX_COMPONENT_DIGITS || d.toString().length > MAX_COMPONENT_DIGITS) {
                throw ArithmeticException("Exact rational exceeds component digit bound")
            }
            return ExactRational(n, d)
        }
    }
}

public object ExactRationalSerializer : KSerializer<ExactRational> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ExactRational", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExactRational): Unit = encoder.encodeString(value.canonicalString())

    override fun deserialize(decoder: Decoder): ExactRational =
        decodeScalar(decoder, "ExactRational", 2 * ExactRational.MAX_COMPONENT_DIGITS + 2) { ExactRational.parseCanonical(it) }
}

internal fun powerOfTen(scale: Int): BigInteger = BigInteger.fromLong(10).pow(scale)

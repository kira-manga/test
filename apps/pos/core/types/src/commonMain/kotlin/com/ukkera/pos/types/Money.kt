package com.ukkera.pos.types

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Release one deliberately admits EGP only; a new currency is a contract change. */
@Serializable
public enum class Currency(public val fractionDigits: Int) {
    EGP(2),
}

/** Posted money. Exact intermediate arithmetic must precede this bounded boundary. */
@Serializable
public data class Money(
    public val currency: Currency,
    @Serializable(with = MinorUnitsSerializer::class)
    public val minor: Long,
) {
    public operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return copy(minor = (BigInteger.fromLong(minor) + BigInteger.fromLong(other.minor)).longValue(true))
    }

    public operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return copy(minor = (BigInteger.fromLong(minor) - BigInteger.fromLong(other.minor)).longValue(true))
    }

    public operator fun unaryMinus(): Money =
        copy(minor = (BigInteger.ZERO - BigInteger.fromLong(minor)).longValue(true))
}

/** Canonical decimal-string JSON, never an IEEE-754 number or exponent. */
public object MinorUnitsSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MinorUnits", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long): Unit = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Long {
        val text = decoder.strictScalarString("Minor units", 20)
        if (!canonicalInteger.matches(text)) {
            throw SerializationException("Minor units must be a canonical signed integer string")
        }
        return text.toLongOrNull() ?: throw SerializationException("Minor units exceed signed 64-bit bounds")
    }

    private val canonicalInteger = Regex("(?:0|[1-9][0-9]{0,18}|-[1-9][0-9]{0,18})")
}

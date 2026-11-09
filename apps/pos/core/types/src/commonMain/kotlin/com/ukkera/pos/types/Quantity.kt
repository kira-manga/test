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
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Precision is an input/contract error, never an instruction to round. */
public class UnsupportedPrecisionException(
    public val actualScale: Int,
    public val maximumScale: Int,
) : IllegalArgumentException("Unsupported precision $actualScale; supported scale is 0..$maximumScale")

/**
 * Signed product base-unit quantity. `scaled = 1234, scale = 3` means exactly 1.234 units.
 * Scale is the unit/SKU precision snapshot, not inferred from significant decimal digits.
 * Addition/subtraction require identical unit and scale; changing that contract is explicit.
 * Equality preserves the snapshot; comparison is numerical across scales of the SAME unit.
 */
@Serializable
public data class Quantity(
    public val unitId: DomainId,
    @Serializable(with = MinorUnitsSerializer::class)
    public val scaled: Long,
    @Serializable(with = QuantityScaleSerializer::class)
    public val scale: Int,
) : Comparable<Quantity> {
    init {
        requireQuantityScale(scale)
    }

    public fun toFraction(): ExactRational = ExactRational.of(BigInteger.fromLong(scaled), powerOfTen(scale))

    public fun rescaleExact(scale: Int): Quantity {
        requireQuantityScale(scale)
        return Quantity(unitId, toFraction().toScaledLongExact(scale), scale)
    }

    /** Use on command input; ledger movements may be signed and zero balances remain representable. */
    public fun requirePositive(): Quantity {
        require(scaled > 0) { "Command quantity must be positive" }
        return this
    }

    /** Serial-tracked units must be whole even when their stored precision snapshot is nonzero. */
    public fun requireWholeUnits(): Quantity {
        require(scaled % powerOfTen(scale).longValue(true) == 0L) { "Serial-tracked quantity must contain whole units" }
        return this
    }

    public operator fun plus(other: Quantity): Quantity {
        requireSameContract(other)
        return copy(scaled = (BigInteger.fromLong(scaled) + BigInteger.fromLong(other.scaled)).longValue(true))
    }

    public operator fun minus(other: Quantity): Quantity {
        requireSameContract(other)
        return copy(scaled = (BigInteger.fromLong(scaled) - BigInteger.fromLong(other.scaled)).longValue(true))
    }

    public operator fun unaryMinus(): Quantity =
        copy(scaled = (BigInteger.ZERO - BigInteger.fromLong(scaled)).longValue(true))

    override fun compareTo(other: Quantity): Int {
        require(unitId == other.unitId) { "Quantity unit mismatch" }
        return toFraction().compareTo(other.toFraction())
    }

    private fun requireSameContract(other: Quantity) {
        require(unitId == other.unitId) { "Quantity unit mismatch" }
        require(scale == other.scale) { "Quantity precision mismatch; rescale explicitly" }
    }

    public companion object {
        public fun parse(unitId: DomainId, text: String, scale: Int): Quantity {
            requireQuantityScale(scale)
            return Quantity(unitId, ExactDecimal.parse(text, scale).toScaledLongExact(scale), scale)
        }
    }
}

private fun requireQuantityScale(scale: Int) {
    if (scale !in 0..6) throw UnsupportedPrecisionException(scale, 6)
}

/** Small numeric scale, not a quoted number, exponent, or lossy integer coercion. */
public object QuantityScaleSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("QuantityScale", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) {
        requireQuantityScale(value)
        encoder.encodeInt(value)
    }

    override fun deserialize(decoder: Decoder): Int {
        val scale = if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element !is JsonPrimitive || element.isString || element.content.length != 1 || element.content[0] !in '0'..'6') {
                throw SerializationException("Quantity scale must be an integer in 0..6")
            }
            element.content[0] - '0'
        } else {
            decoder.decodeInt()
        }
        if (scale !in 0..6) throw SerializationException("Quantity scale must be an integer in 0..6")
        return scale
    }
}

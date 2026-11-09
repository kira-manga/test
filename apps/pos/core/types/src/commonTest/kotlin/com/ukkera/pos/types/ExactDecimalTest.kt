package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExactDecimalTest {
    @Test
    fun canonicalSerializerRejectsNoncanonicalAliasesAndRetainsDeclaredDomainPrecision() {
        for (text in listOf("0", "-1.23", "9".repeat(38), "0.000000000000000001")) {
            val value = ExactDecimal.parseCanonical(text)
            assertEquals("\"$text\"", Json.encodeToString(value))
            assertEquals(value, Json.decodeFromString<ExactDecimal>("\"$text\""))
        }
        for (wire in listOf("0", "1.23", "true", "null", "{}", "[]", "\"-0\"", "\"1.00\"", "\"0.0\"", "\"1e2\"", "\"0.0000000000000000001\"")) {
            assertFailsWith<SerializationException>(wire) { Json.decodeFromString<ExactDecimal>(wire) }
        }
    }

    @Test
    fun canonicalValuesNormalizeWithoutLosingMeaning() {
        val examples = mapOf("0" to "0", "-0" to "0", "0.00000000" to "0", "-0.000" to "0",
            "12.34000000" to "12.34", "-0.00001000" to "-0.00001", "100.000000" to "100")
        for ((input, expected) in examples) {
            val value = ExactDecimal.parse(input)
            assertEquals(expected, value.canonicalString())
            assertEquals(ExactDecimal.parse(expected), value)
            assertEquals(ExactDecimal.parse(expected).hashCode(), value.hashCode())
        }
    }

    @Test
    fun unsupportedTextPrecisionAndCoefficientSizeAreRejected() {
        val invalid = listOf("", " ", "+1", "01", "-01", "1.", ".1", "1e1", "1E1", "NaN",
            "Infinity", "١", "1,00", " 1", "1 ", "1.000000000", "1".repeat(39))
        for (input in invalid) assertFailsWith<IllegalArgumentException>(input) { ExactDecimal.parse(input) }
        assertFailsWith<IllegalArgumentException> { ExactDecimal.parse("1.0", 0) }
        assertFailsWith<IllegalArgumentException> { ExactDecimal.parse("0", -1) }
        assertFailsWith<IllegalArgumentException> { ExactDecimal.parse("0", 19) }
        assertEquals("9".repeat(38), ExactDecimal.parse("9".repeat(38)).canonicalString())
        assertEquals("0.000000000000000001", ExactDecimal.parse("0.000000000000000001", 18).canonicalString())
    }

    @Test
    fun stockScaleConversionNeverRounds() {
        assertEquals(123_456_789L, ExactDecimal.parse("123.456789", 6).toScaledLongExact(6))
        assertEquals(-123_456L, ExactDecimal.parse("-0.123456", 6).toScaledLongExact(6))
        assertEquals(125L, ExactDecimal.parse("1.250000").toScaledLongExact(2))
        assertFailsWith<ArithmeticException> { ExactDecimal.parse("1.250001").toScaledLongExact(2) }
        assertFailsWith<IllegalArgumentException> { ExactDecimal.parse("1.0000001", 6) }
        assertFailsWith<ArithmeticException> { ExactDecimal.parse("9223372036854775808").toScaledLongExact(0) }
        assertEquals(Long.MIN_VALUE, ExactDecimal.parse("-9223372036854775808").toScaledLongExact(0))
        assertFailsWith<IllegalArgumentException> { ExactDecimal.parse("1").toScaledLongExact(19) }
    }

    @Test
    fun halfUpHasExplicitSignedTieRule() {
        val examples = mapOf("0.00499999" to 0L, "0.005" to 1L, "0.00500001" to 1L,
            "-0.00499999" to 0L, "-0.005" to -1L, "-0.00500001" to -1L,
            "1.235" to 124L, "-1.235" to -124L, "0" to 0L)
        for ((input, expected) in examples) {
            assertEquals(Money(Currency.EGP, expected), ExactDecimal.parse(input).toFraction().toMoney(Currency.EGP), input)
        }
    }

    @Test
    fun exactIntermediateDivisionDoesNotPrematurelyRound() {
        val third = ExactRational.ratio(1, 3)
        assertEquals(100L, (third + third + third).roundHalfUp(2))
        assertEquals(33L, third.roundHalfUp(2))
        assertEquals(100L, (third * ExactRational.ratio(3, 1)).roundHalfUp(2))
        assertEquals(1L, (third / third).roundHalfUp(0))
        assertEquals(0L, (third - third).roundHalfUp(18))
        assertEquals(-50L, ExactRational.ratio(1, -2).roundHalfUp(2))
        assertEquals(50L, ExactRational.ratio(-1, -2).roundHalfUp(2))
    }

    @Test
    fun divideByZeroAndPostingOverflowAreErrors() {
        assertFailsWith<ArithmeticException> { ExactRational.ratio(0, 0) }
        assertFailsWith<ArithmeticException> { ExactRational.ratio(1, 1) / ExactRational.ratio(0, 1) }
        assertFailsWith<ArithmeticException> { ExactRational.ratio(Long.MAX_VALUE, 1).roundHalfUp(1) }
        assertFailsWith<ArithmeticException> { ExactRational.ratio(Long.MIN_VALUE, -1).roundHalfUp(0) }
        assertEquals(Long.MIN_VALUE, ExactRational.ratio(Long.MIN_VALUE, 1).roundHalfUp(0))
        assertFailsWith<IllegalArgumentException> { ExactRational.ratio(0, 1).roundHalfUp(-1) }
    }

    @Test
    fun dependentServiceAndTaxFixtureIsExactlyRepresentable() {
        // Hypothetical arithmetic fixture, not a fiscal policy approval or full pricing engine.
        val net = ExactDecimal.parse("200").toFraction()
        val factor = ExactDecimal.parse("1.14").toFraction() +
            ExactDecimal.parse("0.10").toFraction() * ExactDecimal.parse("1.14").toFraction()
        val before = net * factor
        val discount = ExactDecimal.parse("25.08").toFraction()
        val adjustedNet = net - discount / factor
        assertEquals(25_080L, before.toMoney(Currency.EGP).minor)
        assertEquals(18_000L, adjustedNet.toMoney(Currency.EGP).minor)
        assertEquals(22_572L, (adjustedNet * factor).toMoney(Currency.EGP).minor)
        assertEquals(21_935L, ((net - discount) * factor).toMoney(Currency.EGP).minor)
    }
}

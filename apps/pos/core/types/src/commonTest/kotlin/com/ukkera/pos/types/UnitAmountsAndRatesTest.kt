package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnitAmountsAndRatesTest {
    @Test
    fun unitAmountsRemainExactUntilTheChosenPostingBoundary() {
        val price = UnitPrice.parse(Currency.EGP, "123.45678901")
        val cost = UnitCost.parse(Currency.EGP, "0.00000001")
        assertEquals("123.45678901", price.amount.canonicalString())
        assertEquals(Money(Currency.EGP, 1), (cost.toFraction() * ExactRational.ratio(1_000_000, 1)).toMoney(Currency.EGP))
        assertEquals(Money(Currency.EGP, 0), cost.toFraction().toMoney(Currency.EGP)) // Not a permitted per-component cost algorithm.
        assertEquals("-1", UnitCost.parse(Currency.EGP, "-1").amount.canonicalString()) // Sign policy belongs to command.
        assertFailsWith<ArithmeticException> { UnitPrice.parse(Currency.EGP, "9".repeat(38)).toFraction().toMoney(Currency.EGP) }
    }

    @Test
    fun unitAmountConstructorsAndInputParsersEnforcePrecisionWithoutRounding() {
        assertFailsWith<UnsupportedPrecisionException> { UnitPrice.parse(Currency.EGP, "1.000000001") }
        assertFailsWith<UnsupportedPrecisionException> { UnitCost.parse(Currency.EGP, "0.000000001") }
        val excessive = ExactDecimal.parse("0.000000001", 9)
        assertFailsWith<UnsupportedPrecisionException> { UnitCost(Currency.EGP, excessive) }
        assertFailsWith<UnsupportedPrecisionException> { UnitPrice(Currency.EGP, excessive) }
        assertFailsWith<IllegalArgumentException> { UnitCost.parse(Currency.EGP, "1e-8") }
    }

    @Test
    fun unitAmountWireUsesCanonicalExactDecimalStrings() {
        val price = UnitPrice.parse(Currency.EGP, "1.23000000")
        assertEquals("""{"currency":"EGP","amount":"1.23"}""", Json.encodeToString(price))
        assertEquals(price, Json.decodeFromString<UnitPrice>(Json.encodeToString(price)))
        val cost = UnitCost.parse(Currency.EGP, "0.00000001")
        assertEquals(cost, Json.decodeFromString<UnitCost>(Json.encodeToString(cost)))
        assertFailsWith<SerializationException> { Json.decodeFromString<UnitPrice>("""{"currency":"EGP","amount":1.23}""") }
        assertFailsWith<SerializationException> { Json.decodeFromString<UnitCost>("""{"currency":"EGP","amount":"1.00"}""") }
    }

    @Test
    fun percentagesHaveExplicitMeaningAndRequireFeatureSpecificCeilings() {
        val tax = PercentageRate.parse("14")
        assertEquals(ExactRational.ratio(7, 50), tax.toFraction())
        assertEquals(ExactRational.ratio(1, 1), PercentageRate.parse("100").toFraction())
        assertEquals(ExactRational.ratio(0, 1), PercentageRate.parse("0").toFraction())
        assertEquals(tax, tax.requireAtMost(ExactDecimal.parse("100")))
        assertEquals("101", PercentageRate.parse("101").percent.canonicalString())
        assertFailsWith<IllegalArgumentException> { PercentageRate.parse("101").requireAtMost(ExactDecimal.parse("100")) }
        assertFailsWith<IllegalArgumentException> { tax.requireAtMost(ExactDecimal.parse("-1")) }
        assertFailsWith<IllegalArgumentException> { PercentageRate.parse("-0.00000001") }
        assertFailsWith<UnsupportedPrecisionException> { PercentageRate.parse("0.000000001") }
        assertFailsWith<IllegalArgumentException> { PercentageRate.parse("9".repeat(39)) }
        assertEquals("""{"percent":"14"}""", Json.encodeToString(tax))
        assertEquals(tax, Json.decodeFromString<PercentageRate>(Json.encodeToString(tax)))
        assertFailsWith<SerializationException> { Json.decodeFromString<PercentageRate>("""{"percent":14}""") }
    }

    @Test
    fun conversionRatiosArePositiveReducedAndBounded() {
        val conversion = UnitConversionRate.ratio(24, 10)
        assertEquals("12/5", conversion.canonicalString())
        assertEquals(conversion, UnitConversionRate.ratio(12, 5))
        assertEquals(ExactRational.ratio(12, 5), conversion.toFraction())
        assertEquals("1/1", UnitConversionRate.ratio(Long.MAX_VALUE, Long.MAX_VALUE).canonicalString())
        assertEquals("\"12/5\"", Json.encodeToString(conversion))
        assertEquals(conversion, Json.decodeFromString<UnitConversionRate>("\"12/5\""))
        for ((numerator, denominator) in listOf(0L to 1L, 1L to 0L, -1L to 1L, 1L to -1L)) {
            assertFailsWith<IllegalArgumentException> { UnitConversionRate.ratio(numerator, denominator) }
        }
        for (wire in listOf("2", "\"2/4\"", "\"0/1\"", "\"1/0\"", "\"-1/2\"", "\"9223372036854775808/1\"")) {
            assertFailsWith<SerializationException>(wire) { Json.decodeFromString<UnitConversionRate>(wire) }
        }
        assertFailsWith<ArithmeticException> { UnitConversionRate.ratio(1, 3).toFraction().toScaledLongExact(6) }
    }
}

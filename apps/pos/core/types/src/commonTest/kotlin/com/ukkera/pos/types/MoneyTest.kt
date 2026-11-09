package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun postedMoneyUsesStringMinorUnitsIncludingSignedBoundaries() {
        for (minor in listOf(0L, 1L, -1L, 9_007_199_254_740_993L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val value = Money(Currency.EGP, minor)
            val wire = """{"currency":"EGP","minor":"$minor"}"""
            assertEquals(wire, Json.encodeToString(value))
            assertEquals(value, Json.decodeFromString<Money>(wire))
        }
    }

    @Test
    fun numbersAndOtherJsonTypesCannotMasqueradeAsMinorUnitStrings() {
        for (token in listOf("0", "123", "1.0", "1e3", "null", "true", "[]", "{}")) {
            assertFailsWith<SerializationException>(token) {
                Json.decodeFromString<Money>("""{"currency":"EGP","minor":$token}""")
            }
        }
    }

    @Test
    fun malformedNoncanonicalAndOutOfRangeMinorUnitsAreRejected() {
        val invalid = listOf("", "-0", "00", "01", "-01", "+1", "1.0", "1e2", " 1", "1 ",
            "١", "NaN", "Infinity", "9223372036854775808", "-9223372036854775809", "1".repeat(100))
        for (token in invalid) {
            assertFailsWith<SerializationException>(token) {
                Json.decodeFromString<Money>("""{"currency":"EGP","minor":"$token"}""")
            }
        }
    }

    @Test
    fun unrecognizedCurrencyIsRejectedRatherThanConverted() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Money>("""{"currency":"USD","minor":"1"}""")
        }
    }

    @Test
    fun additionSubtractionAndNegationAreExact() {
        val a = Money(Currency.EGP, 25_080)
        val b = Money(Currency.EGP, 2_508)
        assertEquals(22_572L, (a - b).minor)
        assertEquals(a, a - b + b)
        assertEquals(-2_508L, (-b).minor)
        assertEquals(Long.MIN_VALUE, (Money(Currency.EGP, Long.MIN_VALUE + 1) - Money(Currency.EGP, 1)).minor)
        assertEquals(Long.MAX_VALUE, (Money(Currency.EGP, Long.MAX_VALUE - 1) + Money(Currency.EGP, 1)).minor)
    }

    @Test
    fun postedOverflowIsNotWrapped() {
        assertFailsWith<ArithmeticException> { Money(Currency.EGP, Long.MAX_VALUE) + Money(Currency.EGP, 1) }
        assertFailsWith<ArithmeticException> { Money(Currency.EGP, Long.MIN_VALUE) - Money(Currency.EGP, 1) }
        assertFailsWith<ArithmeticException> { -Money(Currency.EGP, Long.MIN_VALUE) }
    }
}

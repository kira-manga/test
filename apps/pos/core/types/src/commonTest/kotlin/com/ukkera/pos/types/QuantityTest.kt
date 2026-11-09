package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QuantityTest {
    private val unit = DomainId("10000000-0000-4000-8000-000000000001")
    private val otherUnit = DomainId("10000000-0000-4000-8000-000000000002")

    @Test
    fun signedQuantityWireRetainsUnitScaleAndEvery64BitValue() {
        for (scaled in listOf(0L, 1L, -1L, 9_007_199_254_740_993L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val quantity = Quantity(unit, scaled, 6)
            val wire = """{"unitId":"${unit.value}","scaled":"$scaled","scale":6}"""
            assertEquals(wire, Json.encodeToString(quantity))
            assertEquals(quantity, Json.decodeFromString<Quantity>(wire))
        }
    }

    @Test
    fun parsingAndRescalingAreExactAtAllSupportedScales() {
        assertEquals(123_456_789L, Quantity.parse(unit, "123.456789", 6).scaled)
        assertEquals(Long.MAX_VALUE, Quantity.parse(unit, "9223372036854.775807", 6).scaled)
        assertEquals(Long.MIN_VALUE, Quantity.parse(unit, "-9223372036854.775808", 6).scaled)
        val value = Quantity.parse(unit, "1.25", 6)
        assertEquals(Quantity(unit, 125, 2), value.rescaleExact(2))
        assertEquals(value, value.rescaleExact(2).rescaleExact(6))
        assertEquals(ExactRational.ratio(5, 4), value.toFraction())
        assertFailsWith<ArithmeticException> { value.rescaleExact(1) }
        assertFailsWith<ArithmeticException> { Quantity(unit, Long.MAX_VALUE, 0).rescaleExact(6) }
        assertFailsWith<ArithmeticException> { Quantity.parse(unit, "9223372036854.775808", 6) }
        assertFailsWith<UnsupportedPrecisionException> { Quantity.parse(unit, "1.0000001", 6) }
        assertFailsWith<UnsupportedPrecisionException> { Quantity.parse(unit, "1.2300", 2) }
        assertFailsWith<UnsupportedPrecisionException> { Quantity(unit, 0, -1) }
        assertFailsWith<UnsupportedPrecisionException> { Quantity(unit, 0, 7) }
    }

    @Test
    fun positiveCommandsAndWholeSerializedUnitsAreSeparateGuards() {
        val fractional = Quantity.parse(unit, "0.000001", 6)
        assertEquals(fractional, fractional.requirePositive())
        assertFailsWith<IllegalArgumentException> { fractional.requireWholeUnits() }
        assertEquals(Quantity(unit, -1_000_000, 6), Quantity(unit, -1_000_000, 6).requireWholeUnits())
        assertEquals(Quantity(unit, 0, 6), Quantity(unit, 0, 6).requireWholeUnits())
        assertFailsWith<IllegalArgumentException> { Quantity(unit, 0, 6).requirePositive() }
        assertFailsWith<IllegalArgumentException> { Quantity(unit, -1, 6).requirePositive() }
    }

    @Test
    fun comparisonIsExactButArithmeticMustPreserveTheExplicitUnitPrecisionContract() {
        val fine = Quantity(unit, 1_000_000, 6)
        val whole = Quantity(unit, 1, 0)
        assertEquals(0, fine.compareTo(whole))
        assertNotEquals(fine, whole) // Snapshot scale is intentionally part of equality.
        assertTrue(Quantity(unit, 1_000_001, 6) > whole)
        assertTrue(Quantity(unit, Long.MIN_VALUE, 6) < Quantity(unit, Long.MAX_VALUE, 0))
        assertEquals(Quantity(unit, 2_000_000, 6), fine + whole.rescaleExact(6))
        assertEquals(Quantity(unit, 0, 6), fine - fine)
        assertEquals(Quantity(unit, -1_000_000, 6), -fine)
        assertFailsWith<IllegalArgumentException> { fine + whole }
        assertFailsWith<IllegalArgumentException> { fine - Quantity(otherUnit, 1_000_000, 6) }
        assertFailsWith<IllegalArgumentException> { fine.compareTo(Quantity(otherUnit, 1, 0)) }
    }

    @Test
    fun signedQuantityArithmeticNeverWraps() {
        assertEquals(Quantity(unit, Long.MAX_VALUE, 6), Quantity(unit, Long.MAX_VALUE - 1, 6) + Quantity(unit, 1, 6))
        assertFailsWith<ArithmeticException> { Quantity(unit, Long.MAX_VALUE, 6) + Quantity(unit, 1, 6) }
        assertFailsWith<ArithmeticException> { Quantity(unit, Long.MIN_VALUE, 6) - Quantity(unit, 1, 6) }
        assertFailsWith<ArithmeticException> { -Quantity(unit, Long.MIN_VALUE, 6) }
    }

    @Test
    fun wireCannotMasqueradeQuantityOrScaleTypes() {
        for (scaled in listOf("1", "1.0", "null", "\"01\"", "\"-0\"", "\"9223372036854775808\"")) {
            assertFailsWith<SerializationException>(scaled) {
                Json.decodeFromString<Quantity>("""{"unitId":"${unit.value}","scaled":$scaled,"scale":6}""")
            }
        }
        for (scale in listOf("\"6\"", "6.0", "6e0", "-0", "7", "-1", "true", "null")) {
            assertFailsWith<SerializationException>(scale) {
                Json.decodeFromString<Quantity>("""{"unitId":"${unit.value}","scaled":"1","scale":$scale}""")
            }
        }
    }
}

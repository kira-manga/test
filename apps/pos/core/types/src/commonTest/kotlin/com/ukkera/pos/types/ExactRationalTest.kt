package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactRationalTest {
    @Test
    fun reducedEqualityCanonicalTextAndSignsAgree() {
        val third = ExactRational.ratio(1, 3)
        assertEquals(third, ExactRational.ratio(-2, -6))
        assertEquals(third.hashCode(), ExactRational.ratio(3, 9).hashCode())
        assertEquals("1/3", third.canonicalString())
        assertEquals("-1/3", (-third).canonicalString())
        assertEquals("0/1", ExactRational.ratio(0, Long.MIN_VALUE).canonicalString())
        assertEquals("9223372036854775808/1", ExactRational.ratio(Long.MIN_VALUE, -1).canonicalString())
        assertEquals(third, ExactRational.parseCanonical(third.canonicalString()))
    }

    @Test
    fun comparisonDoesNotRoundOrOverflowCrossProducts() {
        assertTrue(ExactRational.ratio(1, 3) > ExactDecimal.parse("0.33333333").toFraction())
        assertTrue(ExactRational.ratio(1, 3) < ExactDecimal.parse("0.33333334").toFraction())
        assertTrue(ExactRational.ratio(Long.MAX_VALUE, Long.MAX_VALUE - 1) > ExactRational.ratio(1, 1))
        assertTrue(ExactRational.ratio(Long.MIN_VALUE, Long.MAX_VALUE) < ExactRational.ratio(-1, 1))
        assertEquals(0, ExactRational.ratio(Long.MIN_VALUE, Long.MIN_VALUE).compareTo(ExactRational.ratio(1, 1)))
        assertTrue(ExactDecimal.parse("-1.00000001") < ExactDecimal.parse("-1"))
        assertEquals(0, ExactDecimal.parse("1.00").compareTo(ExactDecimal.parse("1")))
    }

    @Test
    fun exactScaleRejectsFractionalLossAndOverflow() {
        assertEquals(125L, ExactRational.ratio(1, 8).toScaledLongExact(3))
        assertEquals(-125L, ExactRational.ratio(1, -8).toScaledLongExact(3))
        assertEquals(Long.MIN_VALUE, ExactRational.ratio(Long.MIN_VALUE, 1).toScaledLongExact(0))
        assertEquals(Long.MAX_VALUE, ExactRational.ratio(Long.MAX_VALUE, 1).toScaledLongExact(0))
        assertFailsWith<ArithmeticException> { ExactRational.ratio(1, 3).toScaledLongExact(18) }
        assertFailsWith<ArithmeticException> { ExactRational.ratio(1, 8).toScaledLongExact(2) }
        assertFailsWith<ArithmeticException> { ExactRational.ratio(Long.MAX_VALUE, 1).toScaledLongExact(1) }
        assertFailsWith<IllegalArgumentException> { ExactRational.ratio(0, 1).toScaledLongExact(19) }
    }

    @Test
    fun floorIsMathematicalAndHasCheckedPostingBounds() {
        assertEquals(33L, ExactRational.ratio(1, 3).floorScaled(2))
        assertEquals(-34L, ExactRational.ratio(-1, 3).floorScaled(2))
        assertEquals(-1L, ExactRational.ratio(-1, 3).floorScaled(0))
        assertEquals(-50L, ExactRational.ratio(-1, 2).floorScaled(2))
        assertEquals(Long.MIN_VALUE, ExactRational.ratio(Long.MIN_VALUE, 1).floorScaled(0))
        assertEquals(Long.MAX_VALUE, ExactRational.ratio(Long.MAX_VALUE, 1).floorScaled(0))
        assertFailsWith<ArithmeticException> {
            (ExactRational.ratio(Long.MIN_VALUE, 1) - ExactRational.ratio(1, 100)).floorScaled(0)
        }
        assertFailsWith<ArithmeticException> { ExactRational.ratio(Long.MAX_VALUE, 1).floorScaled(1) }
        assertFailsWith<IllegalArgumentException> { ExactRational.ratio(0, 1).floorScaled(-1) }
    }

    @Test
    fun rationalWireIsStrictReducedStringNotNumericOrAnObject() {
        for (text in listOf("0/1", "-1/3", "9223372036854775808/1", "1/1000000000000000000")) {
            val value = ExactRational.parseCanonical(text)
            assertEquals("\"$text\"", Json.encodeToString(value))
            assertEquals(value, Json.decodeFromString<ExactRational>("\"$text\""))
        }
        for (text in listOf("", "1", "1/0", "0/2", "2/4", "-0/1", "01/1", "+1/1", "1/-2", "1/01", " 1/2", "1e2/1", "١/٢")) {
            assertFailsWith<SerializationException>(text) { Json.decodeFromString<ExactRational>("\"$text\"") }
        }
        for (wire in listOf("0", "1.0", "true", "null", "{}", "[]")) {
            assertFailsWith<SerializationException>(wire) { Json.decodeFromString<ExactRational>(wire) }
        }
    }

    @Test
    fun rationalComponentBoundAppliesToWireAndReducedArithmeticResults() {
        val digits = "9".repeat(ExactRational.MAX_COMPONENT_DIGITS)
        val maximum = ExactRational.parseCanonical("$digits/1")
        assertEquals(maximum, Json.decodeFromString<ExactRational>(Json.encodeToString(maximum)))
        assertEquals(ExactRational.ratio(1, 1), maximum / maximum)
        assertFailsWith<ArithmeticException> { maximum * ExactRational.ratio(2, 1) }
        assertFailsWith<IllegalArgumentException> { ExactRational.parseCanonical("${digits}9/1") }
        assertFailsWith<SerializationException> { Json.decodeFromString<ExactRational>("\"1/${digits}9\"") }
    }
}

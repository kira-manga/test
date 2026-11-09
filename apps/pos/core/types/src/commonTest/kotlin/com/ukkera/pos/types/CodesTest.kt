package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CodesTest {
    @Test
    fun codesAndBarcodesPreserveLeadingZerosAndOriginalText() {
        val code = Code.exact("000123")
        val barcode = Barcode.exact("00000123456789012345678901234567890")
        assertEquals("000123", code.original)
        assertEquals(code.original, code.normalizedKey)
        assertEquals(barcode.original, barcode.normalizedKey)
        assertEquals(code, Json.decodeFromString<Code>(Json.encodeToString(code)))
        assertEquals(barcode, Json.decodeFromString<Barcode>(Json.encodeToString(barcode)))
        assertEquals("""{"original":"000123","normalizedKey":"000123","normalization":"EXACT_V1"}""", Json.encodeToString(code))
        assertNotEquals(code, Code.exact("123"))
    }

    @Test
    fun exactVersionDoesNotGuessTrimCaseOrUnicodePolicies() {
        assertNotEquals(Code.exact("A"), Code.exact("a"))
        assertNotEquals(Code.exact("A"), Code.exact(" A "))
        assertEquals("٠٠١٢", Code.exact("٠٠١٢").original)
        val gs1Text = "0100012345678901\u001D210001"
        assertEquals(gs1Text, Json.decodeFromString<Barcode>(Json.encodeToString(Barcode.exact(gs1Text))).original)
        assertFailsWith<IllegalArgumentException> { Code("001", "1", CodeNormalization.EXACT_V1) }
        assertFailsWith<IllegalArgumentException> { Barcode("001", "1", CodeNormalization.EXACT_V1) }
    }

    @Test
    fun stringOnlyAndLengthBoundsAreEnforcedBeforeCatalogUniqueness() {
        assertFailsWith<IllegalArgumentException> { Code.exact("") }
        assertFailsWith<IllegalArgumentException> { Barcode.exact("1".repeat(257)) }
        assertEquals(256, Code.exact("0".repeat(256)).original.length)
        assertEquals("\uD83D\uDE00", Code.exact("\uD83D\uDE00").original)
        assertFailsWith<IllegalArgumentException> { Code.exact("\uD800") }
        assertFailsWith<IllegalArgumentException> { Barcode.exact("\uDC00") }
        assertFailsWith<IllegalArgumentException> { Code.exact("\uD800A") }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Code>("""{"original":123,"normalizedKey":"123","normalization":"EXACT_V1"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Barcode>("""{"original":"123","normalizedKey":123,"normalization":"EXACT_V1"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Code>("""{"original":"123","normalizedKey":"123","normalization":"UNKNOWN_V2"}""")
        }
    }
}

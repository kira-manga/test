package com.ukkera.pos.types

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentifiersTest {
    private val uuid = "7b9d2d33-092e-4c20-b73d-c049f8a5743e"

    @Test
    fun identifiersAreDistinctCanonicalStringTypes() {
        assertEquals("\"$uuid\"", Json.encodeToString(DomainId(uuid)))
        assertEquals(DomainId(uuid), Json.decodeFromString<DomainId>("\"$uuid\""))
        assertEquals(OperationId(uuid), Json.decodeFromString<OperationId>("\"$uuid\""))
        for (invalid in listOf(uuid.uppercase(), "{$uuid}", uuid.replace("-", ""), " $uuid", "42")) {
            assertFailsWith<IllegalArgumentException> { DomainId(invalid) }
            assertFailsWith<IllegalArgumentException> { OperationId(invalid) }
        }
    }

    @Test
    fun revisionsAreScalarDecimalStringsWithCheckedIncrement() {
        for (value in listOf(0L, 1L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            assertEquals("\"$value\"", Json.encodeToString(Revision(value)))
            assertEquals(Revision(value), Json.decodeFromString<Revision>("\"$value\""))
        }
        assertEquals(Revision(43), Revision(42).next())
        assertFailsWith<IllegalArgumentException> { Revision(-1) }
        assertFailsWith<ArithmeticException> { Revision(Long.MAX_VALUE).next() }
        for (wire in listOf("1", "\"-1\"", "\"-0\"", "\"01\"", "{\"value\":\"1\"}", "\"9223372036854775808\"")) {
            assertFailsWith<SerializationException>(wire) { Json.decodeFromString<Revision>(wire) }
        }
    }

    @Test
    fun uuidGenerationUsesVersion4AndIetfVariantWithoutAClockInput() {
        val identifiers = List(512) { DomainId.random().value } + List(512) { OperationId.random().value }
        assertEquals(identifiers.size, identifiers.toSet().size) // Smoke test, not a proof of entropy.
        for (identifier in identifiers) {
            assertEquals('4', identifier[14])
            assertTrue(identifier[19] in "89ab")
            assertEquals(identifier, identifier.lowercase())
            assertEquals(identifier, DomainId(identifier).value)
        }
    }

    @Test
    fun idSerializersRejectNonStringsAndNoncanonicalStrings() {
        for (wire in listOf("42", "null", "true", "{}", "[]", "\"${uuid.uppercase()}\"", "\"$uuid \"")) {
            assertFailsWith<SerializationException>(wire) { Json.decodeFromString<DomainId>(wire) }
            assertFailsWith<SerializationException>(wire) { Json.decodeFromString<OperationId>(wire) }
        }
    }

    @Test
    fun journalSequenceIsCheckedNonnegativeAndNotATimeOrderingShortcut() {
        for (value in listOf(0L, 1L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            assertEquals("\"$value\"", Json.encodeToString(JournalSequence(value)))
            assertEquals(JournalSequence(value), Json.decodeFromString<JournalSequence>("\"$value\""))
        }
        assertEquals(JournalSequence(43), JournalSequence(42).next())
        assertTrue(JournalSequence(Long.MAX_VALUE) > JournalSequence(0))
        assertTrue(Revision(Long.MAX_VALUE) > Revision(0))
        assertFailsWith<IllegalArgumentException> { JournalSequence(-1) }
        assertFailsWith<ArithmeticException> { JournalSequence(Long.MAX_VALUE).next() }
        for (wire in listOf("1", "\"-1\"", "\"-0\"", "\"01\"", "\"1e2\"", "{\"value\":\"1\"}", "\"9223372036854775808\"")) {
            assertFailsWith<SerializationException>(wire) { Json.decodeFromString<JournalSequence>(wire) }
        }
    }
}

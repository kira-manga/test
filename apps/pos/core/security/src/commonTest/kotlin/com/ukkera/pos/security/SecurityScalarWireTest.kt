package com.ukkera.pos.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecurityScalarWireTest {
    @Test
    fun sessionGenerationsAreExactCanonicalDecimalStringsPastJavascriptSafeIntegerRange() {
        val value = SessionGeneration(9_007_199_254_740_993)
        assertEquals("\"9007199254740993\"", Json.encodeToString(value))
        assertEquals(value, Json.decodeFromString<SessionGeneration>("\"9007199254740993\""))
        for (bad in listOf("9007199254740993", "\"-1\"", "\"01\"", "\"1e3\"", "\"9223372036854775808\"")) {
            assertFailsWith<SerializationException> { Json.decodeFromString<SessionGeneration>(bad) }
        }
    }

    @Test
    fun approvalMonotonicCounterAlsoUsesTheStrictLongWireContract() {
        val value = moment(9_007_199_254_740_993)
        val encoded = Json.encodeToString(value)
        assertEquals(value, Json.decodeFromString<MonotonicMoment>(encoded))
        assertEquals(
            "{\"bootId\":\"00000000-0000-0000-0000-000000000900\",\"elapsedMillis\":\"9007199254740993\"}",
            encoded,
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString<MonotonicMoment>(encoded.replace("\"9007199254740993\"", "9007199254740993"))
        }
    }

    @Test
    fun digestNeverCoercesNumericTokensEvenWhenCallerEnablesLenientJson() {
        val digest = PayloadDigest("1".repeat(64))
        val encoded = "\"" + digest.value + "\""
        for (json in listOf(Json, Json { isLenient = true })) {
            assertEquals(digest, json.decodeFromString<PayloadDigest>(encoded))
            assertEquals(encoded, json.encodeToString(digest))
            for (bad in listOf("1".repeat(64), "null", "{}", "[]", "\"A" + "1".repeat(63) + "\"")) {
                assertFailsWith<SerializationException> { json.decodeFromString<PayloadDigest>(bad) }
            }
        }
    }
}

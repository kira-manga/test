package com.ukkera.pos.database

import com.ukkera.pos.security.LocalAccessTokenHash
import kotlin.test.*

/** Pure binary-profile/ownership tests, not credential entropy or identity/transport acceptance. */
class LocalAccessBearerSyntaxTest {
    private fun bytes(): ByteArray = byteArrayOf(0x55, 0x4c, 0x41, 0x31) +
        ByteArray(16) { (it * 17).toByte() } + ByteArray(32) { it.toByte() }

    @Test fun fixedLiteralUuidUsesNetworkOrderNotPlatformGuidOrder() {
        val parsed = assertNotNull(ParsedLocalAccessBearer.parse(bytes()))
        try {
            assertEquals("00112233-4455-6677-8899-aabbccddeeff", parsed.sessionId.value)
            assertTrue(parsed.matches(LocalAccessTokenHash.fromCanonicalSha256(
                "208bd4c0ca7941f488f90770a84f24472cf47d0e2b980f400b26ba718b32a0bb")))
        } finally { parsed.close() }
    }

    @Test fun rejectsEveryNonExactLengthIncludingTruncationAndTrailingData() {
        for (size in 0..100) if (size != 52) assertNull(ParsedLocalAccessBearer.parse(bytes().copyOf(size)), "$size")
        assertNull(ParsedLocalAccessBearer.parse(bytes().copyOf(8192)))
    }

    @Test fun everyMagicAndVersionByteMustMatchExactly() {
        repeat(4) { index ->
            for (value in 0..255) if (value.toByte() != bytes()[index]) {
                assertNull(ParsedLocalAccessBearer.parse(bytes().also { it[index] = value.toByte() }))
            }
        }
    }

    @Test fun secretIsOwnedAndCallerMutationCannotChangeParsedSelectionOrHash() {
        val input = bytes(); val before = input.copyOf()
        val parsed = assertNotNull(ParsedLocalAccessBearer.parse(input))
        assertContentEquals(before, input)
        input.fill(0)
        try {
            assertEquals("00112233-4455-6677-8899-aabbccddeeff", parsed.sessionId.value)
            assertTrue(parsed.matches(LocalAccessTokenHash.hashSecret(ByteArray(32) { it.toByte() })))
            assertFalse(parsed.matches(LocalAccessTokenHash.hashSecret(ByteArray(32))))
        } finally { parsed.close() }
    }

    @Test fun closeIsIdempotentAndPreventsAnyLaterSecretUse() {
        val parsed = assertNotNull(ParsedLocalAccessBearer.parse(bytes()))
        parsed.close(); parsed.close()
        val error = assertFailsWith<IllegalStateException> { parsed.matches(LocalAccessTokenHash.hashSecret(ByteArray(32))) }
        assertNull(error.cause)
        assertEquals("ParsedLocalAccessBearer([REDACTED])", parsed.toString())
        assertFalse(parsed.sessionId.value in error.toString())
    }

    @Test fun zeroAndHighBitLocatorBytesAreCanonicalIdsNotClaimsOrNewSecretProfiles() {
        for (byte in listOf(0.toByte(), 0xff.toByte())) {
            val input = bytes().also { for (index in 4..19) it[index] = byte }
            val parsed = assertNotNull(ParsedLocalAccessBearer.parse(input))
            try {
                assertEquals(if (byte == 0.toByte()) "00000000-0000-0000-0000-000000000000" else
                    "ffffffff-ffff-ffff-ffff-ffffffffffff", parsed.sessionId.value)
                assertTrue(parsed.matches(LocalAccessTokenHash.hashSecret(ByteArray(32) { it.toByte() })))
            } finally { parsed.close() }
        }
    }
}

package com.ukkera.pos.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DigestTest {
    @Test
    fun sha256MatchesPublishedKnownAnswerVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(byteArrayOf()).value,
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).value,
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).value,
        )
    }

    @Test
    fun digestSyntaxIsStrictAndDoesNotAuthenticateArbitraryPayloads() {
        assertFailsWith<IllegalArgumentException> { PayloadDigest("0".repeat(63)) }
        assertFailsWith<IllegalArgumentException> { PayloadDigest("A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { PayloadDigest("z".repeat(64)) }
        assertEquals("0".repeat(64), PayloadDigest("0".repeat(64)).value) // Syntax only, no verification claim.
    }
}

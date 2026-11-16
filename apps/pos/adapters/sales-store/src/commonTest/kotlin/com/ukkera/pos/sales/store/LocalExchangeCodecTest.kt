package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Exchange command wire contract (RET-05/06/07). Self-authored, NOT independent. */
class LocalExchangeCodecTest {
    private val at = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private fun slice(n: Int, minor: Long = 100) = LocalExchangeSliceV1(cashId(100 + n), cashId(200 + n), cashMoney(minor))
    private fun request(slices: List<LocalExchangeSliceV1> = listOf(slice(1))) = LocalExchangeRequestV1(cashId(1), cashId(2), cashId(3),
        cashId(4), Revision(2), cashId(5), Revision(3), slices, LocalCashEvidenceV1(cashId(6), at))

    @Test fun requestRoundTripsCanonicallyAndIsVersionOneOfItsOwnCommand() {
        val value = request(listOf(slice(1), slice(2, 50)))
        val bytes = LocalExchangeCodec.encode(value)
        assertEquals(value, LocalExchangeCodec.decode(bytes))
        assertEquals(bytes, LocalExchangeCodec.normalize(bytes))
        assertEquals("sales.exchange.write", LocalExchangeCodec.COMMAND.name)
        assertEquals(1, LocalExchangeCodec.COMMAND.version)
        assertNotEquals(LocalExchangeCodec.key(cashId(1)), LocalExchangeCodec.key(cashId(2)))
        assertNotEquals(LocalExchangeCodec.claim(cashId(1)), LocalExchangeCodec.claim(cashId(2)))
    }

    @Test fun slicesMustBePositiveAndBounded() {
        assertFailsWith<IllegalArgumentException> { slice(1, 0) }
        assertFailsWith<IllegalArgumentException> { slice(1, -1) }
        assertFailsWith<IllegalArgumentException> { request(emptyList()) }
        assertFailsWith<IllegalArgumentException> { request((1..17).map { slice(it) }) }
        request((1..16).map { slice(it) })
    }

    @Test fun resultsRoundTripAndRefusalsCarryNoAppliedState() {
        val applied = LocalExchangeResultV1.Applied(cashId(1), cashId(4), Revision(3), cashMoney(0), cashId(5), Revision(4), cashMoney(100))
        assertEquals(applied, LocalExchangeCodec.result(LocalExchangeCodec.result(applied)))
        for (reason in LocalExchangeRefusalV1.entries) {
            val refused = LocalExchangeResultV1.Refused(reason)
            assertEquals(refused, LocalExchangeCodec.result(LocalExchangeCodec.result(refused)))
        }
    }

    @Test fun malformedOrOversizedBytesAreRefused() {
        val bytes = LocalExchangeCodec.encode(request())
        assertFailsWith<Exception> { LocalExchangeCodec.decode(StoredBytes(bytes.copyBytes().copyOf(bytes.size - 1))) }
        assertFailsWith<Exception> { LocalExchangeCodec.decode(StoredBytes(ByteArray(8_193) { ' '.code.toByte() })) }
    }
}

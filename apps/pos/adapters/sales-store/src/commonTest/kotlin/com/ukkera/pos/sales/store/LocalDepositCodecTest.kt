package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.DepositEntitlement
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Container deposit command wire contract (LF-CAT-11, CAT-10). Self-authored, NOT independent. */
class LocalDepositCodecTest {
    private val at = UtcInstant.parseCanonical("2026-11-14T12:00:00Z")
    private fun evidence() = LocalCashEvidenceV1(cashId(9), at)
    private fun collect(containers: Long = 2) = LocalDepositRequestV1.Collect(cashId(1), cashId(2), cashId(3), cashId(4),
        Revision(2), containers, evidence())
    private fun refund(containers: Long = 1) = LocalDepositRequestV1.Refund(cashId(1), Revision(0), cashId(4), Revision(3),
        cashId(5), containers, evidence())

    @Test fun requestsRoundTripCanonicallyAsVersionOneOfTheirOwnCommand() {
        for (value in listOf(collect(), refund())) {
            val bytes = LocalDepositCodec.encode(value)
            assertEquals(value, LocalDepositCodec.decode(bytes))
            assertEquals(bytes, LocalDepositCodec.normalize(bytes))
        }
        assertEquals("sales.deposit.write", LocalDepositCodec.COMMAND.name)
        assertEquals(1, LocalDepositCodec.COMMAND.version)
        assertEquals("sales.deposit.read", LocalDepositCodec.QUERY.name)
        assertNotEquals(LocalDepositCodec.encode(collect()), LocalDepositCodec.encode(refund()))
    }

    @Test fun claimsAndKeysAreDistinctPerIdentity() {
        assertNotEquals(LocalDepositCodec.key(cashId(1)), LocalDepositCodec.key(cashId(2)))
        assertNotEquals(LocalDepositCodec.key(cashId(1)), LocalDepositCodec.saleKey(cashId(1)))
        val claims = listOf(LocalDepositCodec.claim(cashId(1)), LocalDepositCodec.portionClaim(cashId(1)), LocalDepositCodec.refundClaim(cashId(1)))
        assertEquals(3, claims.distinct().size)
    }

    @Test fun containerCountsMustBePositiveAndBounded() {
        assertFailsWith<IllegalArgumentException> { collect(0) }
        assertFailsWith<IllegalArgumentException> { collect(-1) }
        assertFailsWith<IllegalArgumentException> { collect(DepositEntitlement.MAX_CONTAINERS + 1) }
        assertFailsWith<IllegalArgumentException> { refund(0) }
        collect(DepositEntitlement.MAX_CONTAINERS)
    }

    @Test fun refusalsRoundTripWithoutAppliedState() {
        for (reason in LocalDepositRefusalV1.entries) {
            val refused = LocalDepositResultV1.Refused(reason)
            assertEquals(refused, LocalDepositCodec.result(LocalDepositCodec.result(refused)))
        }
    }

    @Test fun malformedOrOversizedBytesAreRefused() {
        val bytes = LocalDepositCodec.encode(collect())
        assertFailsWith<Exception> { LocalDepositCodec.decode(StoredBytes(bytes.copyBytes().copyOf(bytes.size - 1))) }
        assertFailsWith<Exception> { LocalDepositCodec.decode(StoredBytes(ByteArray(4_097) { ' '.code.toByte() })) }
    }
}

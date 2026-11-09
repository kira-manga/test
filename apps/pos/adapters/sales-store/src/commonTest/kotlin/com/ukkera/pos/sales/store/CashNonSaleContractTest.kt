package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashNonSaleContractTest {
    @Test fun canonicalRequestPreservesExactScalarsAndFreeEvidenceWithoutAuthorityFields() = runTest {
        val fixture = CashStoreFixture()
        val request = fixture.request(fixture.open(), amount = cashMoney(9_007_199_254_740_993))
            .copy(expectedDomainRevision = Revision(9_007_199_254_740_993))
        val bytes = CashNonSaleCommandCodec.encode(request)
        assertEquals(bytes, CashNonSaleCommandCodec.normalize(bytes))
        assertEquals(request, CashNonSaleCommandCodec.decodeRequest(bytes))
        assertTrue(bytes.cashText().contains("\"minor\":\"9007199254740993\""))
        assertTrue(bytes.cashText().contains("\"expectedDomainRevision\":\"9007199254740993\""))
        listOf("actorId", "scope", "operationId", "payloadDigest", "authorization").forEach {
            assertFalse(bytes.cashText().contains("\"$it\":"))
        }
        assertTrue(bytes.size <= CashNonSaleStoreProfile.MAX_REQUEST_BYTES)
    }

    @Test fun unknownAuthorityFieldsDuplicatesAndAlternateJsonAreRejected() = runTest {
        val fixture = CashStoreFixture()
        val request = fixture.request(fixture.open())
        val canonical = CashNonSaleCommandCodec.encode(request).cashText()
        val forged = listOf("actorId", "scope", "operationId", "semanticDigest", "authorization", "schemaVersion")
            .map { canonical.dropLast(1) + ",\"$it\":\"untrusted\"}" }
        val duplicate = canonical.dropLast(1) + ",\"sourceId\":\"${request.sourceId.value}\"}"
        val reordered = "{\"sourceId\":\"${request.sourceId.value}\"," +
            canonical.removePrefix("{").replace("\"sourceId\":\"${request.sourceId.value}\",", "")
        (forged + listOf(duplicate, reordered, " $canonical", canonical + "\n", canonical + "{}"))
            .forEach { assertFailsWith<IllegalArgumentException> { CashNonSaleCommandCodec.normalize(cashBytes(it)) } }
    }

    @Test fun numericAndAlternateScalarRepresentationsAreNotNormalizedIntoMoneyOrAuthority() = runTest {
        val fixture = CashStoreFixture()
        val canonical = CashNonSaleCommandCodec.encode(fixture.request(fixture.open())).cashText()
        val invalid = listOf(
            canonical.replace("\"minor\":\"100\"", "\"minor\":100"),
            canonical.replace("\"minor\":\"100\"", "\"minor\":\"1e2\""),
            canonical.replace("\"minor\":\"100\"", "\"minor\":\"0100\""),
            canonical.replace("\"expectedDomainRevision\":\"0\"", "\"expectedDomainRevision\":0"),
            canonical.replace("\"EGP\"", "\"USD\""),
            canonical.replace("11:59:00Z", "11:59:00+00:00"),
            canonical.replace("NON_SALE_PAY_IN", "CUSTOMER_RECEIPT"),
            canonical.replace("NON_SALE_PAY_IN", "SAFE_DROP"),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { CashNonSaleCommandCodec.normalize(cashBytes(it)) } }
    }

    @Test fun amountsEvidenceByteBoundsUtf8AndNestingFailClosed() = runTest {
        val fixture = CashStoreFixture()
        val request = fixture.request(fixture.open())
        listOf(request.copy(amount = cashMoney(0)), request.copy(amount = cashMoney(-1)),
            request.copy(reason = " \n"), request.copy(reason = "x".repeat(257))).forEach {
            assertFailsWith<IllegalArgumentException> { CashNonSaleCommandCodec.encode(it) }
        }
        listOf("[]", "{\"nested\":" + "{".repeat(9), "{\"nested\":[]}", "{}", "null", "\"unterminated")
            .forEach { assertFailsWith<IllegalArgumentException> { CashNonSaleCommandCodec.normalize(cashBytes(it)) } }
        assertFailsWith<IllegalArgumentException> {
            CashNonSaleCommandCodec.normalize(StoredBytes(ByteArray(CashNonSaleStoreProfile.MAX_REQUEST_BYTES + 1) { 32 }))
        }
        assertFailsWith<IllegalArgumentException> {
            CashNonSaleCommandCodec.normalize(StoredBytes(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        // Control characters and multibyte actual reasons are counted as encoded
        // UTF-8 bytes, not assumed to fit because their character count fits.
        val maximumEvidence = request.copy(reason = "é" + "\u0001".repeat(255))
        assertEquals(maximumEvidence, CashNonSaleCommandCodec.decodeRequest(CashNonSaleCommandCodec.encode(maximumEvidence)))
    }

    @Test fun recordedResultsAreTypedBoundedAndDoNotInventPaymentAcknowledgements() {
        val values = listOf(
            CashNonSaleResultV1.Applied(cashId(1), Revision(2), cashId(2), Revision(1), cashMoney(9_007_199_254_740_993)),
            CashNonSaleResultV1.DomainRejected(SalesRule.INSUFFICIENT_DRAWER_CASH),
            CashNonSaleResultV1.SessionMissing,
            CashNonSaleResultV1.Unsupported(CashNonSaleUnsupported.PENDING_FINANCIAL_OR_CUSTODY_WORK),
            CashNonSaleResultV1.Unsupported(CashNonSaleUnsupported.INVENTORY_SETTLEMENT_SOURCE_REQUIRED),
        )
        values.forEach {
            val encoded = CashNonSaleResultCodec.encode(it)
            assertTrue(encoded.size <= CashNonSaleStoreProfile.MAX_RESULT_BYTES)
            assertEquals(it, CashNonSaleResultCodec.decode(encoded))
            assertFalse(encoded.cashText().contains("authorization"))
            assertFalse(encoded.cashText().contains("bank"))
        }
        val applied = CashNonSaleResultCodec.encode(values.first()).cashText()
        assertFailsWith<IllegalArgumentException> { CashNonSaleResultCodec.decode(cashBytes(applied.replace("APPLIED", "PAID"))) }
        assertFailsWith<IllegalArgumentException> { CashNonSaleResultCodec.decode(cashBytes(applied.replace("\"domainRevision\":\"2\"", "\"domainRevision\":\"0\""))) }
        assertFailsWith<IllegalArgumentException> { CashNonSaleResultCodec.decode(cashBytes(applied.replace("\"9007199254740993\"", "\"-1\""))) }
    }

    @Test fun kernelStorageRevisionMappingIsCheckedRatherThanWrappedOrEquated() {
        assertEquals(Revision(1), SalesStorageRevision.toStorage(Revision(0)))
        assertEquals(Revision(0), SalesStorageRevision.toDomain(Revision(1)))
        assertEquals(Revision(Long.MAX_VALUE), SalesStorageRevision.toStorage(Revision(Long.MAX_VALUE - 1)))
        assertEquals(Revision(Long.MAX_VALUE - 1), SalesStorageRevision.toDomain(Revision(Long.MAX_VALUE)))
        assertFailsWith<ArithmeticException> { SalesStorageRevision.toStorage(Revision(Long.MAX_VALUE)) }
        assertFailsWith<IllegalArgumentException> { SalesStorageRevision.toDomain(Revision(0)) }
    }
}

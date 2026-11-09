package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Explicit source-validation slices, not genuine original/history or persistence verification. */
class RetailCustomerReceiptFrozenV1SourceValidationTest {
    private val f = RetailCustomerReceiptFrozenV1Fixture

    @Test
    fun rcr005And033MissingOriginalCannotBecomeNotRecorded() = runTest {
        val view = MissingOriginalView()
        for (sale in listOf(f.sale, f.unknownSale)) {
            assertFailsWith<LocalSalesSourceUnavailable> { readRetailCustomerReceiptOriginalSource(view, sale) }
        }
        assertTrue(view.requestedKeys.isNotEmpty())
    }

    @Test
    fun rcr006S1LiteralHeadAndEventValidateWithoutRewritingTheirValues() {
        val headBytes = f.headBytes(f.head(1))
        val head = retailCustomerReceiptHeadImage(Revision(1), LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA,
            headBytes, sha256(headBytes.copyBytes()), f.scope, f.original)
        assertEquals(f.head(1), head.value)
        assertEquals(listOf(f.each(1), f.kg(1_125_000)), head.value.recordedPortions)
        val eventBytes = f.eventBytes(f.event(1))
        assertEquals(f.event(1), retailCustomerReceiptEventValue(f.e1, LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA,
            eventBytes, sha256(eventBytes.copyBytes()), f.original))
        assertEquals(headBytes, f.headBytes(f.head(1)))
        assertEquals(eventBytes, f.eventBytes(f.event(1)))
    }

    @Test
    fun rcr006WrongScopeHeadAndMissingLatestLocatorAreUnavailable() {
        val s1 = f.head(1)
        for (invalid in listOf(s1.copy(source = s1.source.copy(scope = LocalSalesScope.from(f.otherBranchScope))),
            s1.copy(lastOperationId = f.e2), s1.copy(eventOperationIds = emptyList()))) {
            val bytes = f.headBytes(invalid)
            assertFailsWith<LocalSalesSourceUnavailable> { retailCustomerReceiptHeadImage(Revision(1),
                LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA, bytes, sha256(bytes.copyBytes()), f.scope, f.original) }
        }
        assertEquals(listOf(f.e1), s1.eventOperationIds)
        assertEquals(f.e1, s1.lastOperationId)
    }

    @Test
    fun rcr006InvalidOriginalUnitInCumulativeHeadIsUnavailable() {
        val invalid = f.head(1).copy(recordedPortions = listOf(f.each(1), f.kg(1_125_000, f.gramUnit)))
        val bytes = f.headBytes(invalid)
        assertFailsWith<LocalSalesSourceUnavailable> { retailCustomerReceiptHeadImage(Revision(1),
            LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA, bytes, sha256(bytes.copyBytes()), f.scope, f.original) }
        assertEquals(listOf(f.each(1), f.kg(1_125_000)), f.head(1).recordedPortions)
    }

    @Test
    fun rcr006EventWrongSaleScopeOrOriginalOperationIsUnavailable() {
        val e1 = f.event(1)
        val variants = listOf(e1.copy(after = e1.after.copy(source = e1.after.source.copy(saleId = f.unknownSale))),
            e1.copy(after = e1.after.copy(source = e1.after.source.copy(scope = LocalSalesScope.from(f.otherBranchScope)))),
            e1.copy(operationId = f.e2))
        for (invalid in variants) assertInvalidEvent(invalid)
        assertEquals(f.sale, e1.after.source.saleId)
        assertEquals(f.e1, e1.operationId)
    }

    @Test
    fun rcr006MissingAndMismatchedEventResultLinksAreUnavailable() {
        val e1 = f.event(1)
        assertInvalidEvent(e1.copy(result = e1.result.copy(saleId = f.unknownSale)))
        assertInvalidEvent(e1.copy(result = e1.result.copy(eventOperationId = f.e2)))
        assertInvalidEvent(e1.copy(result = e1.result.copy(receivedAt = f.paidAt)))
        val root = localSalesJson.parseToJsonElement(f.eventBytes(e1).copyBytes().decodeToString()).jsonObject
        val missing = StoredBytes(JsonObject(root - "result").toString().encodeToByteArray())
        assertFailsWith<LocalSalesSourceUnavailable> { retailCustomerReceiptEventValue(f.e1,
            LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, missing, sha256(missing.copyBytes()), f.original) }
        assertEquals(f.e1, e1.result.eventOperationId)
        assertEquals(f.e1ReceivedAt, e1.result.receivedAt)
    }

    @Test
    fun rcr006CorruptEventBytesRemainUnavailableAndGoodBytesStillRecover() {
        val good = f.eventBytes(f.event(1))
        val corrupt = StoredBytes("not-a-receipt-event".encodeToByteArray())
        assertFailsWith<LocalSalesSourceUnavailable> { retailCustomerReceiptEventValue(f.e1,
            LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, corrupt, sha256(corrupt.copyBytes()), f.original) }
        assertEquals(f.event(1), retailCustomerReceiptEventValue(f.e1, LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA,
            good, sha256(good.copyBytes()), f.original))
        assertEquals(good, f.eventBytes(f.event(1)))
    }

    @Test
    fun rcr033DefaultRecordExplicitLookupAndMalformedPurposeStayDistinct() {
        val default = LocalRetailCustomerReceiptReadCodec.request(LocalRetailCustomerReceiptQueryV1.Head(f.sale))
        assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD,
            (LocalRetailCustomerReceiptReadCodec.decodeQuery(default.payload) as LocalRetailCustomerReceiptQueryV1.Head).access)
        val lookup = LocalRetailCustomerReceiptQueryV1.Event(f.sale, f.e1, LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP)
        assertEquals(lookup, LocalRetailCustomerReceiptReadCodec.decodeQuery(LocalRetailCustomerReceiptReadCodec.encode(lookup)))
        val root = localSalesJson.parseToJsonElement(default.payload.copyBytes().decodeToString()).jsonObject
        for (purpose in listOf(kotlinx.serialization.json.JsonPrimitive("UNKNOWN"), kotlinx.serialization.json.JsonPrimitive("PAYMENT"),
            kotlinx.serialization.json.JsonPrimitive(""), kotlinx.serialization.json.JsonNull)) {
            val body = StoredBytes(JsonObject(root + ("access" to purpose)).toString().encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptReadHandler().normalize(body) }
        }
    }

    private fun assertInvalidEvent(value: LocalRetailCustomerReceiptEventV1) {
        val bytes = f.eventBytes(value)
        assertFailsWith<LocalSalesSourceUnavailable> { retailCustomerReceiptEventValue(f.e1,
            LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, bytes, sha256(bytes.copyBytes()), f.original) }
    }

    private inner class MissingOriginalView : CommandReadView {
        override val scope = f.scope
        val requestedKeys = mutableListOf<AggregateKey>()
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? { requestedKeys += key; return null }
        override suspend fun claim(key: ClaimKey): DomainId? = null
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = null
    }
}

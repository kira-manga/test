package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pure production/codec slices only. No Master, durable original, auth, SQLite or RPC claim. */
class RetailCustomerReceiptFrozenV1ProductionTest {
    private val f = RetailCustomerReceiptFrozenV1Fixture

    @Test
    fun rcr007PositiveMinimaAndOriginalMaximaAreExact() {
        for (portion in listOf(f.each(1), f.kg(1), f.each(3), f.kg(2_750_000))) {
            val request = f.request(0, f.e1ReceivedAt, listOf(portion))
            assertApplied(f.propose(f.e1, request), request, f.e1, 1, listOf(f.e1), listOf(portion), f.e1RecordedAt)
        }
    }

    @Test
    fun rcr007MixedE1AndFinalMeasuredRemainderKeepFrozenTotals() {
        val e1 = f.request(1)
        assertApplied(f.propose(f.e1, e1), e1, f.e1, 1, listOf(f.e1),
            listOf(f.each(1), f.kg(1_125_000)), f.e1RecordedAt)
        val before = f.headImage(2)
        val e3 = f.request(3)
        assertApplied(f.propose(f.e3, e3, 2, f.e3RecordedAt, before), e3, f.e3, 3, listOf(f.e1, f.e2, f.e3),
            listOf(f.each(3), f.kg(2_750_000)), f.e3RecordedAt)
        assertEquals(f.headBytes(f.head(2)), before.payload)
        assertEquals(before.payload, f.headBytes(before.value))
        assertEquals(listOf(f.kg(1_000_000)), e3.portions) // Each is omitted, not supplied as zero.
    }

    @Test
    fun rcr007AtS3EitherPositiveExtraIsQuantityExceeded() {
        val before = f.headImage(3)
        val beforeHead = before.payload
        for (extra in listOf(f.each(1), f.kg(1))) {
            assertRefused(f.propose(f.raceD, f.request(3, f.e3ReceivedAt, listOf(extra)), 3, f.e3RecordedAt, before),
                LocalRetailCustomerReceiptRefusalV1.QUANTITY_EXCEEDED)
            assertEquals(beforeHead, before.payload)
            assertEquals(beforeHead, f.headBytes(before.value))
        }
    }

    @Test
    fun rcr008S2OneMicroUnitExcessIsNotRoundedDown() {
        val before = f.headImage(2)
        val beforeBytes = before.payload
        val request = f.request(2, f.e3ReceivedAt, listOf(f.kg(1_000_001)))
        assertRefused(f.propose(f.e3, request, 2, f.e3RecordedAt, before), LocalRetailCustomerReceiptRefusalV1.QUANTITY_EXCEEDED)
        assertEquals(beforeBytes, before.payload)
        assertEquals(beforeBytes, f.headBytes(before.value))
        assertEquals(listOf(f.each(3), f.kg(1_750_000)), before.value.recordedPortions)
    }

    @Test
    fun rcr008ZeroAndNegativeCommandQuantitiesRefuseBeforeProposal() {
        for (scaled in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> { f.each(scaled) }
        }
    }

    @Test
    fun rcr008HalfEachAndWrongKgBaseUnitAreDistinctInvalidInputs() {
        assertRefused(f.propose(f.e1, f.request(0, f.e1ReceivedAt, listOf(f.each(5, scale = 1)))),
            LocalRetailCustomerReceiptRefusalV1.INVALID_QUANTITY)
        assertRefused(f.propose(f.e1, f.request(0, f.e1ReceivedAt, listOf(f.kg(1_125_000, unit = f.gramUnit)))),
            LocalRetailCustomerReceiptRefusalV1.INVALID_QUANTITY)
    }

    @Test
    fun rcr008UnsupportedOriginalPrecisionAndSeventhMeasuredDigitRefuse() {
        assertFailsWith<IllegalArgumentException> { Quantity(f.kgUnit, 1, 7) }
        assertFailsWith<IllegalArgumentException> { Quantity.parse(f.kgUnit, "0.0000001", 6) }
        val originalJson = localSalesJson.parseToJsonElement(
            LocalRetailCustomerReceiptReadCodec.result(LocalRetailCustomerReceiptReadResultV1.NotRecorded(
                f.original.publicOriginal())).copyBytes().decodeToString()).jsonObject
        val original = originalJson.getValue("original").jsonObject
        val portions = original.getValue("portions") as JsonArray
        val kg = portions[1].jsonObject
        val unsupportedQuantity = JsonObject(kg.getValue("quantity").jsonObject + ("scale" to JsonPrimitive(7)))
        val invalidOriginal = JsonObject(original + ("portions" to JsonArray(listOf(portions[0],
            JsonObject(kg + ("quantity" to unsupportedQuantity))))))
        assertFailsWith<IllegalArgumentException> {
            LocalRetailCustomerReceiptReadCodec.decode(bytes(JsonObject(originalJson + ("original" to invalidOriginal))))
        }
    }

    @Test
    fun rcr008MalformedNonfiniteExponentAndOverflowLiteralsRefusePrecisely() {
        for (literal in listOf("NaN", "Infinity", "1e999999", "9223372036854775808", "not-a-quantity")) {
            val error = assertFails(literal) { Quantity.parse(f.eachUnit, literal, 0) }
            assertTrue(error is IllegalArgumentException || error is ArithmeticException, literal)
        }
    }

    @Test
    fun rcr008And028InvalidMeasuredItemRejectsTheWholeMixedBody() {
        val request = f.request(0, f.e1ReceivedAt, listOf(f.each(1), f.kg(1)))
        val root = requestJson(request)
        val portions = root.getValue("portions") as JsonArray
        val measured = portions[1].jsonObject
        val invalidQuantity = JsonObject(measured.getValue("quantity").jsonObject + ("scale" to JsonPrimitive(7)))
        val body = bytes(JsonObject(root + ("portions" to JsonArray(listOf(portions[0],
            JsonObject(measured + ("quantity" to invalidQuantity)))))))
        assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptCodec.decode(body) }
        assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptHandler().normalize(body) }
        assertEquals(1L, request.portions[0].quantity.scaled)
    }

    @Test
    fun rcr009InclusivePaidAndMasterTimeEndpointsAreAccepted() {
        for (received in listOf(f.paidAt, f.e1RecordedAt)) {
            val request = f.request(1).copy(receivedAt = received)
            assertApplied(f.propose(f.e1, request), request, f.e1, 1, listOf(f.e1),
                listOf(f.each(1), f.kg(1_125_000)), f.e1RecordedAt)
        }
    }

    @Test
    fun rcr009And010OneMillisecondOutsideEitherCommitBoundRefuses() {
        for ((received, recorded) in listOf(
            f.instant(1_767_261_599_999) to f.e1RecordedAt,
            f.instant(1_767_355_200_001) to f.e1RecordedAt,
            f.e1ReceivedAt to f.instant(1_767_306_599_999),
        )) {
            assertRefused(f.propose(f.e1, f.request(1).copy(receivedAt = received), recordedAt = recorded),
                LocalRetailCustomerReceiptRefusalV1.INVALID_OBSERVATION_TIME)
        }
    }

    @Test
    fun rcr009E1KeepsUtcCairoDateAndSeparateMasterTime() {
        val request = LocalRetailCustomerReceiptCodec.decode(LocalRetailCustomerReceiptCodec.encode(f.request(1)))
        assertEquals(1_767_306_600_000, request.receivedAt.value.toEpochMilliseconds())
        assertEquals("2026-01-01T22:30:00Z", request.receivedAt.canonicalString())
        assertEquals("Africa/Cairo", request.receiptTimeZoneId)
        assertEquals("2026-01-02", request.receivedAt.value.toLocalDateTime(TimeZone.of(request.receiptTimeZoneId)).date.toString())
        assertApplied(f.propose(f.e1, request), request, f.e1, 1, listOf(f.e1),
            listOf(f.each(1), f.kg(1_125_000)), f.e1RecordedAt)
    }

    @Test
    fun rcr009OnlyExactCairoZoneIsAccepted() {
        for (zone in listOf("UTC", "Europe/London", "africa/cairo", "Africa/Cairo ", "UNKNOWN/ZONE")) {
            assertRefused(f.propose(f.e1, f.request(1).copy(receiptTimeZoneId = zone)),
                LocalRetailCustomerReceiptRefusalV1.INVALID_RECEIPT_TIME_ZONE)
        }
        for (zone in listOf(JsonPrimitive(""), JsonNull)) {
            assertFailsWith<IllegalArgumentException> {
                LocalRetailCustomerReceiptCodec.decode(bytes(JsonObject(requestJson(f.request(1)) + ("receiptTimeZoneId" to zone))))
            }
        }
    }

    @Test
    fun rcr003And009MissingObservationTimeZoneOrObservationCannotDefault() {
        for (field in listOf("receivedAt", "receiptTimeZoneId", "observation")) {
            val body = bytes(JsonObject(requestJson(f.request(1)) - field))
            assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptCodec.decode(body) }
            assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptHandler().normalize(body) }
        }
    }

    @Test
    fun rcr009UnsupportedAndNullObservationCannotBecomeCounterReceipt() {
        for (observation in listOf(JsonPrimitive("DELIVERED"), JsonPrimitive("PAID"), JsonPrimitive("SERVED"),
            JsonPrimitive("OUT_FOR_DELIVERY"), JsonPrimitive("UNKNOWN"), JsonNull)) {
            assertFailsWith<IllegalArgumentException> {
                LocalRetailCustomerReceiptCodec.decode(bytes(JsonObject(requestJson(f.request(1)) + ("observation" to observation))))
            }
        }
        assertEquals(LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
            LocalRetailCustomerReceiptCodec.decode(LocalRetailCustomerReceiptCodec.encode(f.request(1))).observation)
    }

    @Test
    fun rcr032FreshStaleCasAtS0S1S2IsRevisionConflict() {
        val variants = listOf(Triple(0, f.raceD, f.request(1).copy(expectedHeadRevision = Revision(1))),
            Triple(1, f.e2, f.request(2).copy(expectedHeadRevision = Revision(0))),
            Triple(2, f.e3, f.request(3).copy(expectedHeadRevision = Revision(1))))
        for ((state, operation, request) in variants) {
            val before = if (state == 0) null else f.headImage(state)
            val beforeBytes = before?.payload
            assertRefused(f.propose(operation, request, state, f.e3RecordedAt, before), LocalRetailCustomerReceiptRefusalV1.REVISION_CONFLICT)
            assertEquals(beforeBytes, before?.payload)
            if (before != null) assertEquals(beforeBytes, f.headBytes(before.value))
        }
    }

    @Test
    fun rcr032FreshE2CasOneAdvancesToExactS2OnceInProposal() {
        val request = f.request(2)
        assertApplied(f.propose(f.e2, request, 1, f.e2RecordedAt), request, f.e2, 2, listOf(f.e1, f.e2),
            listOf(f.each(3), f.kg(1_750_000)), f.e2RecordedAt)
        assertEquals(f.headBytes(f.head(1)), f.headImage(1).payload)
    }

    @Test
    fun rcr036SingleKgUiObservationKeepsEachAtOneNotBaseE2Three() {
        val request = f.request(1, f.e2ReceivedAt, listOf(f.kg(625_000)))
        assertApplied(f.propose(f.uiOperation, request, 1, f.e2RecordedAt), request, f.uiOperation, 2,
            listOf(f.e1, f.uiOperation), listOf(f.each(1), f.kg(1_750_000)), f.e2RecordedAt)
    }

    @Test
    fun rcr032NegativeOverflowAndMalformedRevisionLiteralsRefuse() {
        for (literal in listOf("-1", "9223372036854775808", "not-a-revision")) {
            val body = bytes(JsonObject(requestJson(f.request(1)) + ("expectedHeadRevision" to JsonPrimitive(literal))))
            assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptCodec.decode(body) }
        }
    }

    @Test
    fun rcr028ZeroThirtyThreeDuplicateAndReversedPortionsAreNotRepaired() {
        val e1 = f.request(1)
        val thirtyThree = (1..33).map { index -> LocalRetailCustomerReceiptPortionV1(
            com.ukkera.pos.types.DomainId("14000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}"),
            Quantity(f.eachUnit, 1, 0)) }
        for (portions in listOf(emptyList(), thirtyThree, listOf(f.each(1), f.each(1)), e1.portions.reversed())) {
            assertFailsWith<IllegalArgumentException> { e1.copy(portions = portions) }
            val body = bytes(JsonObject(requestJson(e1) + ("portions" to localSalesJson.parseToJsonElement(
                localSalesJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(
                    LocalRetailCustomerReceiptPortionV1.serializer()), portions)))))
            assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptHandler().normalize(body) }
        }
    }

    @Test
    fun rcr005And028UnknownPortionRefusesAndMalformedIdsNeverNormalize() {
        val request = f.request(0, f.e1ReceivedAt, listOf(LocalRetailCustomerReceiptPortionV1(f.unknownPortion,
            Quantity(f.eachUnit, 1, 0))))
        assertRefused(f.propose(f.e1, request), LocalRetailCustomerReceiptRefusalV1.INVALID_QUANTITY)
        val e1 = requestJson(f.request(1))
        assertFailsWith<IllegalArgumentException> {
            LocalRetailCustomerReceiptHandler().normalize(bytes(JsonObject(e1 + ("saleId" to JsonPrimitive("not-a-uuid")))))
        }
        val portions = e1.getValue("portions") as JsonArray
        val malformed = JsonObject(portions[0].jsonObject + ("portionId" to JsonPrimitive("not-a-uuid")))
        assertFailsWith<IllegalArgumentException> {
            LocalRetailCustomerReceiptHandler().normalize(bytes(JsonObject(e1 + ("portions" to JsonArray(listOf(malformed, portions[1]))))))
        }
    }

    private fun assertRefused(outcome: ProposedOutcome, reason: LocalRetailCustomerReceiptRefusalV1) {
        val rejected = assertIs<ProposedOutcome.Reject>(outcome)
        assertEquals(LocalRetailCustomerReceiptResultV1.Refused(reason), LocalRetailCustomerReceiptCodec.decodeResult(rejected.result))
    }

    private fun assertApplied(outcome: ProposedOutcome, request: LocalRetailCustomerReceiptRequestV1,
        operation: OperationId, revision: Long, events: List<OperationId>, cumulative: List<LocalRetailCustomerReceiptPortionV1>,
        recordedAt: UtcInstant) {
        val applied = assertIs<ProposedOutcome.Apply>(outcome)
        assertEquals(LocalRetailCustomerReceiptResultV1.Applied(f.sale, operation, Revision(revision),
            request.receivedAt, f.zone, recordedAt), LocalRetailCustomerReceiptCodec.decodeResult(applied.result))
        val head = localSalesDecode(applied.changes.single { it.key == retailCustomerReceiptHeadKey(f.sale) }.payload,
            LocalRetailCustomerReceiptHeadV1.serializer())
        assertEquals(Revision(revision), head.headRevision)
        assertEquals(events, head.eventOperationIds)
        assertEquals(operation, head.lastOperationId)
        assertEquals(cumulative, head.recordedPortions)
        assertEquals(f.original.coordinates, head.source)
        val event = localSalesDecode(applied.changes.single { it.key == retailCustomerReceiptEventKey(operation) }.payload,
            LocalRetailCustomerReceiptEventV1.serializer())
        assertEquals(operation, event.operationId)
        assertEquals(f.collector, event.actorId)
        assertEquals(request.portions, event.portions)
        assertEquals(request.observation, event.observation)
        assertEquals(request.receivedAt, event.receivedAt)
        assertEquals(f.zone, event.receiptTimeZoneId)
        assertEquals(recordedAt, event.recordedAt)
        assertEquals(head, event.after)
        assertEquals(LocalRetailCustomerReceiptCodec.decodeResult(applied.result), event.result)
        assertTrue(applied.changes.all { it.key.kind in setOf(LocalRetailCustomerReceiptProfileV1.HEAD,
            LocalRetailCustomerReceiptProfileV1.EVENT, LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE) })
        assertTrue(applied.jobs.isEmpty())
        assertTrue(applied.claims.all { it.key.namespace == LocalRetailCustomerReceiptProfileV1.NAMESPACE })
    }

    private fun requestJson(request: LocalRetailCustomerReceiptRequestV1): JsonObject = localSalesJson.parseToJsonElement(
        LocalRetailCustomerReceiptCodec.encode(request).copyBytes().decodeToString()).jsonObject
    private fun bytes(value: JsonObject): StoredBytes = StoredBytes(value.toString().encodeToByteArray())
}

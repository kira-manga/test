package com.ukkera.pos.app.gateway.independent.managerreassignmentv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** Codec-only cuts of the frozen cases; no handler, authority, persistence or whole-case result. */
class ManagerReassignmentCodecSlicesTest {
    private val f = FrozenManagerReassignmentV1

    @Test
    fun mra001And003ExplicitKeysAndOriginalRequestHaveNoOwnerOrAuthorityFields() {
        assertEquals(CommandKey("sales.collection.stock", 2), LocalCollectionStockCodecV2.COMMAND)
        assertEquals(ReadKey("sales.order.reassignment", 1), LocalManagerReassignmentCodec.QUERY)
        assertNotEquals(LocalManagerOrderReviewCodec.QUERY, LocalManagerReassignmentCodec.QUERY)
        val command = LocalCollectionStockCodecV2.command(f.operation(), f.request())
        assertEquals(f.operation(), command.operationId)
        assertEquals(f.request(), LocalCollectionStockCodecV2.decode(command.payload))
        assertEquals(command.payload, LocalCollectionStockCodecV2.normalize(command.payload))
        val objectBody = f.objectOf(command.payload)
        assertEquals("REASSIGN_ORDER", objectBody.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("type", "orderId", "expectedOrderRevision", "previousOwnerId", "recipientId", "reasonCode", "evidence"),
            objectBody.keys)
        assertEquals(setOf("evidenceId", "occurredAt"), objectBody.getValue("evidence").jsonObject.keys)
        val read = LocalManagerReassignmentCodec.request(LocalManagerOrderReviewQueryV1(f.target))
        assertEquals(LocalManagerReassignmentCodec.QUERY, read.query)
        assertEquals(setOf("orderId"), f.objectOf(read.payload).keys)
        // This checks bytes/identity only, not durable staging or a currently installed capability.
    }

    @Test
    fun mra004EveryFrozenReasonIsPreservedExactlyOrRefusedByNormalization() {
        for (reason in f.acceptedReasons) {
            val body = LocalCollectionStockCodecV2.encode(f.request(reason))
            assertEquals(reason, assertIs<LocalCollectionStockRequestV2.ReassignOrder>(
                LocalCollectionStockCodecV2.decode(body)).reasonCode)
            assertEquals(body, LocalCollectionStockCodecV2.normalize(body))
        }
        val base = f.objectOf(LocalCollectionStockCodecV2.encode(f.request()))
        for (reason in f.rejectedReasons) {
            assertFailsWith<IllegalArgumentException>(reason) { LocalCollectionStockCodecV2.encode(f.request(reason)) }
            val invalid = f.objectBody(base.changed("reasonCode", JsonPrimitive(reason)))
            assertFailsWith<IllegalArgumentException>(reason) { LocalCollectionStockCodecV2.normalize(invalid) }
        }
        for (invalid in listOf(base.without("reasonCode"), base.changed("reasonCode", JsonNull))) {
            assertFailsWith<IllegalArgumentException> { LocalCollectionStockCodecV2.normalize(f.objectBody(invalid)) }
        }
        // decode alone is structural; normalization supplies the explicit reason-code validation.
    }

    @Test
    fun mra005And027MalformedOrMissingIdentityEvidenceAndStateCannotDefaultIntoACommand() {
        val base = f.objectOf(LocalCollectionStockCodecV2.encode(f.request()))
        for (field in listOf("orderId", "expectedOrderRevision", "previousOwnerId", "recipientId", "evidence")) {
            assertFailsWith<IllegalArgumentException>(field) { LocalCollectionStockCodecV2.normalize(f.objectBody(base.without(field))) }
        }
        val evidence = base.getValue("evidence").jsonObject
        for (field in listOf("evidenceId", "occurredAt")) {
            val body = f.objectBody(base.changed("evidence", evidence.without(field)))
            assertFailsWith<IllegalArgumentException>(field) { LocalCollectionStockCodecV2.normalize(body) }
        }
        val invalid = listOf(
            base.changed("orderId", JsonPrimitive("")),
            base.changed("previousOwnerId", JsonPrimitive("not-an-identifier")),
            base.changed("recipientId", JsonNull),
            base.changed("expectedOrderRevision", JsonPrimitive(-1)),
            base.changed("type", JsonPrimitive("UNKNOWN_REASSIGNMENT")),
            base.changed("actorId", JsonPrimitive(f.manager.value)),
            base.changed("evidence", evidence.changed("evidenceId", JsonPrimitive(""))),
            base.changed("evidence", evidence.changed("occurredAt", JsonPrimitive("not-a-time"))),
            base.changed("evidence", evidence.changed("occurredAt", JsonPrimitive("2026-10-30T11:00:00+02:00"))),
        )
        for (value in invalid) assertFailsWith<IllegalArgumentException> {
            LocalCollectionStockCodecV2.normalize(f.objectBody(value))
        }
        for (bytes in listOf("{}".encodeToByteArray(), "[]".encodeToByteArray(),
            "{\"type\":\"REASSIGN_ORDER\"".encodeToByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            assertFailsWith<IllegalArgumentException> { LocalCollectionStockCodecV2.normalize(StoredBytes(bytes)) }
        }
        assertFailsWith<IllegalArgumentException> { OperationId("") }
        // No transport/load/OS bound, unknown-recipient qualification, or operation registry was exercised.
    }

    @Test
    fun mra001And008CandidateDecoderRejectsWrongTargetCursorLegacyFoodAndLiveReferences() {
        val query = LocalManagerOrderReviewQueryV1(f.target)
        val candidate = f.candidate()
        assertEquals(listOf(candidate), LocalManagerReassignmentCodec.decode(f.readBody(), query).orders)
        assertTrue(LocalManagerReassignmentCodec.decode(f.readBody(emptyList()), query).orders.isEmpty())
        val unsupported = listOf(
            candidate.copy(order = candidate.order.copy(orderId = f.id("ORDER-A-OTHER"))),
            candidate.copy(stockProfile = LocalOrderStockProfileV2.LEGACY_BOUNDED),
            candidate.copy(order = candidate.order.copy(selection = candidate.order.selection.copy(activity = LocalSalesActivity.RESTAURANT))),
            candidate.copy(order = candidate.order.copy(selection = candidate.order.selection.copy(activity = LocalSalesActivity.CAFE))),
            candidate.copy(order = candidate.order.copy(collectionId = f.id("ORDER-A-OTHER"))),
            candidate.copy(order = candidate.order.copy(saleId = f.id("ORDER-A-OTHER"))),
            candidate.copy(order = candidate.order.copy(handoffId = f.id("LEGACY-HANDOFF-COMPLETED"))),
            candidate.copy(order = candidate.order.copy(handoffRecipientId = f.recipient)),
        )
        for (value in unsupported) assertFailsWith<IllegalArgumentException> {
            LocalManagerReassignmentCodec.decode(f.readBody(listOf(value)), query)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalManagerReassignmentCodec.decode(f.readBody(nextAfter = f.id("ORDER-A-OTHER")), query)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalManagerReassignmentCodec.decode(f.readBody(listOf(candidate, candidate)), query)
        }
        // A controlled empty response is not proof of genuine absence or complete source/history.
    }

    @Test
    fun mra012And024SubstantiveBodiesAndLegacyOfferAcceptRemainDistinctUnderTheSameId() {
        val base = f.request()
        val original = LocalCollectionStockCodecV2.command(f.operation(), base)
        val changed = listOf(
            base.copy(orderId = f.id("ORDER-A-OTHER")), base.copy(previousOwnerId = f.creator),
            base.copy(recipientId = f.alternateRecipient), base.copy(expectedOrderRevision = f.r18),
            base.copy(reasonCode = "A"), base.copy(evidence = base.evidence.copy(evidenceId = f.id("EVIDENCE-MGR-002"))),
            base.copy(evidence = base.evidence.copy(occurredAt = UtcInstant.parseCanonical("2026-10-30T09:01:00Z"))),
        )
        for (request in changed) {
            val command = LocalCollectionStockCodecV2.command(f.operation(), request)
            assertEquals(original.operationId, command.operationId)
            assertNotEquals(original.payload, command.payload)
        }
        val offer = LocalCollectionStockRequestV2.OfferHandoff(f.target, f.r17,
            f.id("LEGACY-HANDOFF-COMPLETED"), f.recipient, base.evidence)
        val accept = LocalCollectionStockRequestV2.AcceptHandoff(f.target, f.r17,
            f.id("LEGACY-HANDOFF-COMPLETED"), base.evidence)
        for ((request, kind) in listOf(offer to "OFFER_HANDOFF", accept to "ACCEPT_HANDOFF")) {
            val body = LocalCollectionStockCodecV2.encode(request)
            assertEquals(request, LocalCollectionStockCodecV2.decode(body))
            assertEquals(kind, f.objectOf(body).getValue("type").jsonPrimitive.content)
            assertNotEquals(original.payload, body)
        }
        assertEquals(base, LocalCollectionStockCodecV2.decode(original.payload))
        // Body distinction is not genuine deduplication, an old-host upgrade, or a committed conflict result.
    }
}

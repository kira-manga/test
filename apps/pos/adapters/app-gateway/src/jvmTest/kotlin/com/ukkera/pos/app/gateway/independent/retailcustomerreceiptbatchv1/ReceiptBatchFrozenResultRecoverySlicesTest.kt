package com.ukkera.pos.app.gateway.independent.retailcustomerreceiptbatchv1

import com.ukkera.pos.app.*
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Frozen result/recovery presentation with controlled replies and journal models.
 * Canonical codes bind the separately reviewed public RESULT-CODES contract.
 */
class ReceiptBatchFrozenResultRecoverySlicesTest {
    private val f = ReceiptBatchFrozen

    @Test fun rcbe014MatchingCommittedResultAcknowledgesAndOffersExactOriginalEvent() = runTest {
        val id = f.variant("RCBE-014/MATCHING-COMMITTED")
        val c = ReceiptBatchGateway()
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent()), id)
        c.endpoint.executeReply = f.reply()
        val recorded = assertIs<LocalOperationResult.Recorded>(c.gateway.submit(f.operation), id)
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, recorded.decision, id)
        assertEquals(f.operation, recorded.operationId, id)
        val expected = f.expected.obj("committedResult")
        val payload = assertIs<LocalRetailCustomerReceiptResultV1.Applied>(
            LocalRetailCustomerReceiptCodec.decodeResult(f.resultPayload()), id)
        assertEquals(expected.text("saleId"), payload.saleId.value, id)
        assertEquals(expected.text("operationId"), payload.eventOperationId.value, id)
        assertEquals(expected.text("headRevision").toLong(), payload.headRevision.value, id)
        assertEquals(f.instant(expected.text("receivedAt")), payload.receivedAt, id)
        assertEquals(expected.text("receiptTimeZoneId"), payload.receiptTimeZoneId, id)
        assertEquals(f.instant(expected.text("recordedAt")), payload.recordedAt, id)
        val locator = requireNotNull(recorded.result).reference
        assertEquals(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, locator.kind, id)
        assertEquals(expected.text("operationId"), locator.id.value, id)
        assertEquals(expected.text("saleId"), locator.parentId?.value, id)
        c.endpoint.found = f.reply(replayed = true)
        assertIs<LocalAcknowledgementResult.Acknowledged>(c.gateway.acknowledgeRecorded(f.operation), id)
        assertTrue(c.journal.entries.values.single().acknowledged, id)
        val page = c.page(locator)
        val query = assertIs<LocalRetailCustomerReceiptQueryV1.Event>(c.endpoint.queries.last(), id)
        assertEquals(expected.text("operationId"), query.operationId.value, id)
        assertEquals(expected.text("saleId"), query.saleId.value, id)
        assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD, query.access, id)
        assertEquals(locator, page.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT }.reference, id)
        val values = page.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT }.values.map { it.value.english }
        assertTrue(expected.text("receivedAt") in values, id)
        assertTrue(expected.text("receiptTimeZoneId") in values, id)
        assertTrue(expected.text("recordedAt") in values, id)
        assertEquals(1, c.endpoint.executions.size, id)
    }

    @Test fun rcbe014MismatchedAndMissingResultsNeverDisplayReceiptSuccess() = runTest {
        val original = Json.parseToJsonElement(f.resultPayload().copyBytes().decodeToString()).jsonObject
        val rootMismatch = f.fixtures.array("rootMutations").map { it.jsonObject }.single { it.text("variantId") == "SALE-REFERENCE-MISMATCH" }
        val changedTime = f.fixtures.obj("recoveryVariants").array("changedOriginalBodyCandidates").map { it.jsonObject }
            .single { it.text("variantId") == "REUSE-ID-CHANGED-TIME" }.text("changedReceivedAt")
        val mutations = listOf(
            "UNKNOWN" to ("type" to JsonPrimitive("UNKNOWN")),
            "WRONG-SALE" to ("saleId" to rootMismatch.obj("mutation").getValue("value")),
            "WRONG-OPERATION" to ("eventOperationId" to JsonPrimitive(f.main.array("priorEvents").first().jsonObject.text("operationId"))),
            "WRONG-REVISION" to ("headRevision" to JsonPrimitive(f.main.obj("head").text("revision"))),
            "WRONG-TIME" to ("receivedAt" to JsonPrimitive(changedTime)),
            "WRONG-ZONE" to ("receiptTimeZoneId" to JsonPrimitive(f.fixtures.obj("recoveryVariants").text("changedDeviceTimeZone")))
        )
        val replies = mutations.map { (name, mutation) ->
            name to f.reply(StoredBytes(JsonObject(original.toMutableMap().also { it[mutation.first] = mutation.second })
                .toString().encodeToByteArray()))
        } + listOf(
            "MISSING" to f.reply(StoredBytes(ByteArray(0))),
            "NONCOMMITTED" to f.reply(committed = false),
            "WRONG-CODE" to f.reply(code = f.REFUSED_CODE),
            "ENVELOPE-PAYLOAD-CONFLICT" to f.reply(StoredBytes(JsonObject(original.toMutableMap().also {
                it["recordedAt"] = JsonPrimitive(f.fixtures.obj("recoveryVariants").text("laterClock"))
            }).toString().encodeToByteArray()))
        )
        replies.forEach { (name, reply) ->
            val id = f.variant("RCBE-014/" + name)
            val c = ReceiptBatchGateway()
            assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent()), id)
            c.endpoint.executeReply = reply
            assertIs<LocalOperationResult.Unresolved>(c.gateway.submit(f.operation), id)
            assertEquals(1, c.journal.entries.size, id)
            assertEquals(f.operation, c.journal.entries.values.single().operationId, id)
        }
    }

    @Test fun rcbe014EnglishAndArabicIdentifyRecordedObservationSeparatelyFromPayment() = runTest {
        val c = ReceiptBatchGateway()
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent()))
        c.endpoint.executeReply = f.reply()
        val recorded = assertIs<LocalOperationResult.Recorded>(c.gateway.submit(f.operation))
        val event = c.page(requireNotNull(recorded.result).reference)
        val englishId = f.variant("RCBE-014/ENGLISH")
        val english = recorded.message.english + " " + requireNotNull(event.notice).english
        assertTrue(english.lowercase().contains("record"), englishId)
        assertTrue(english.lowercase().contains("receipt") || english.lowercase().contains("observation"), englishId)
        assertTrue(english.lowercase().contains("payment"), englishId)
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, recorded.decision, englishId)
        val arabicId = f.variant("RCBE-014/ARABIC")
        val arabic = recorded.message.arabic + " " + requireNotNull(event.notice).arabic
        assertTrue(arabic.contains("استلام") || arabic.contains("ملاحظة"), arabicId)
        assertTrue(arabic.contains("دفع"), arabicId)
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, recorded.decision, arabicId)
    }

    @Test fun rcbe015LostReplyRetainsOriginalIdentityAndRecoversWithoutAnotherExecute() = runTest {
        recoverInterrupted("RCBE-015/LOST-REPLY", IOException("Controlled lost reply"))
    }

    @Test fun rcbe015CancelledResponseRetainsOriginalIdentityAndRecoversWithoutAnotherExecute() = runTest {
        recoverInterrupted("RCBE-015/CANCELLED-RESPONSE", CancellationException("Controlled cancelled response"))
    }

    private suspend fun recoverInterrupted(variant: String, failure: Throwable) {
        val id = f.variant(variant)
        val c = ReceiptBatchGateway()
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent()), id)
        val saved = c.journal.entries.values.single()
        val bytes = saved.request().payload.copyBytes()
        c.endpoint.executeFailure = failure
        if (failure is CancellationException) {
            try {
                c.gateway.submit(f.operation)
                fail(id + ": cancellation was not propagated")
            } catch (_: CancellationException) { }
        } else assertIs<LocalOperationResult.Unresolved>(c.gateway.submit(f.operation), id)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED.name, c.journal.entries.values.single().phase, id)
        assertContentEquals(bytes, c.journal.entries.values.single().request().payload.copyBytes(), id)
        val restored = c.journal.modelRoundTrip()
        c.endpoint.executeFailure = null
        c.endpoint.head = f.postCommitHead(c.scenario)
        c.endpoint.found = f.reply(replayed = true)
        val reopened = c.reopenedGateway(restored)
        val result = assertIs<LocalOperationResult.Recorded>(reopened.lookup(f.operation), id)
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, result.decision, id)
        assertTrue(result.replayed, id)
        assertIs<LocalOperationResult.Recorded>(reopened.submit(f.operation), id)
        assertEquals(1, c.endpoint.executions.size, id)
        assertEquals(setOf(f.operation), restored.entries.keys, id)
        assertFrozenRequest(id, restored.entries.values.single())
        assertContentEquals(bytes, restored.entries.values.single().request().payload.copyBytes(), id)
        assertTrue(c.endpoint.lookups.all { it == saved.identity() }, id)
    }

    @Test fun rcbe015RecreatedGatewayUsesTheFrozenOriginalRequestAtANewerHead() = runTest {
        val id = f.variant("RCBE-015/RECONNECT")
        val c = ReceiptBatchGateway()
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent()), id)
        val saved = c.journal.entries.values.single()
        c.endpoint.head = f.postCommitHead(c.scenario)
        c.endpoint.found = f.reply(replayed = true)
        assertIs<LocalOperationResult.Recorded>(c.reopenedGateway().lookup(f.operation), id)
        assertFrozenRequest(id, c.journal.entries.values.single())
        assertContentEquals(saved.request().payload.copyBytes(), c.journal.entries.values.single().request().payload.copyBytes(), id)
        assertEquals(listOf(saved.identity()).toSet(), c.endpoint.lookups.toSet(), id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
    }

    @Test fun rcbe015AccountSwitchAndCurrentDenialPreserveUnresolvedEvidence() = runTest {
        listOf("ACCOUNT-SWITCH", "CURRENT-DENIAL").forEach { name ->
            val id = f.variant("RCBE-015/" + name)
            val c = ReceiptBatchGateway()
            assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent()), id)
            val saved = c.journal.entries.values.single()
            if (name == "ACCOUNT-SWITCH") {
                c.endpoint.actor = DomainId(f.fixtures.obj("recoveryVariants").array("changedOriginalBodyCandidates").map { it.jsonObject }
                    .single { it.text("variantId") == "REUSE-ID-CHANGED-ACTOR" }.text("changedTrustedActorId"))
            } else c.endpoint.lookupFailure = DeferredReason.AUTHORIZATION_DENIED
            assertIs<LocalOperationResult.Unresolved>(c.reopenedGateway().lookup(f.operation), id)
            assertIs<LocalOperationResult.Unresolved>(c.reopenedGateway().submit(f.operation), id)
            assertEquals(saved, c.journal.entries.values.single(), id)
            assertFrozenRequest(id, c.journal.entries.values.single())
            assertTrue(c.endpoint.executions.isEmpty(), id)
        }
    }
}

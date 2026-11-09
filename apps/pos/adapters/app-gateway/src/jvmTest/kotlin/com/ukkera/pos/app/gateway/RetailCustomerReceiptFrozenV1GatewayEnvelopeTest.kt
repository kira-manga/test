package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared result/ACK and original recovery slices; controlled replies are not proof of commit. */
class RetailCustomerReceiptFrozenV1GatewayEnvelopeTest {
    private val f = RetailCustomerReceiptFrozenV1GatewayFixture

    @Test
    fun rcr036And039CanonicalMatchingSubmitIsRecordedOnlyWithOriginalReference() = runTest {
        val h = staged()
        val result = assertIs<LocalOperationResult.Recorded>(h.gateway.submit(f.uiOperation))
        assertEquals(f.uiOperation, result.operationId)
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, result.decision)
        val record = assertNotNull(result.result)
        assertEquals(f.uiEventReference, record.reference)
        assertTrue(record.values.any { it.value.english == f.uiReceivedAt.canonicalString() })
        assertTrue(record.values.any { it.value.english == f.zone })
        assertEquals(f.uiOperation, h.endpoint.executions.single().operationId)
        assertEquals(LocalRetailCustomerReceiptCodec.encode(f.uiRequest()), h.endpoint.executions.single().payload)
        assertEquals(f.collector, h.endpoint.lookups.single().originalActorId)
    }

    @Test
    fun rcr039WrongMissingOrNoncanonicalCodeCannotTrustCommittedPayload() = runTest {
        for (code in listOf("wrong-committed-code", "", "COMMITTED")) {
            val h = staged()
            h.endpoint.executionReply = f.reply(code = code)
            assertIs<LocalOperationResult.Unresolved>(h.gateway.submit(f.uiOperation))
            assertOriginalBodyRetained(h)
        }
    }

    @Test
    fun rcr039ContradictoryRejectedUnknownOrUncommittedPayloadCannotPresentSuccess() = runTest {
        val unknown = mutatedResult("type", JsonPrimitive("UNKNOWN"))
        val rejected = f.resultBytes(LocalRetailCustomerReceiptResultV1.Refused(LocalRetailCustomerReceiptRefusalV1.REVISION_CONFLICT))
        for (reply in listOf(f.reply(rejected), f.reply(unknown), f.reply(committed = false))) {
            val h = staged()
            h.endpoint.executionReply = reply
            assertIs<LocalOperationResult.Unresolved>(h.gateway.submit(f.uiOperation))
            assertOriginalBodyRetained(h)
        }
    }

    @Test
    fun rcr039WrongSaleOperationObservedTimeZoneRevisionOrCommitBoundsAreUncertain() = runTest {
        val variants = listOf(
            mutatedResult("saleId", JsonPrimitive(f.unknownSale.value)),
            mutatedResult("eventOperationId", JsonPrimitive(f.e1.value)),
            mutatedResult("receivedAt", JsonPrimitive(f.e1ReceivedAt.canonicalString())),
            mutatedResult("receiptTimeZoneId", JsonPrimitive("UTC")),
            mutatedResult("headRevision", JsonPrimitive("0")),
            mutatedResult("headRevision", JsonPrimitive("3")),
            mutatedResult("recordedAt", JsonPrimitive(f.instant(1_767_694_499_999).canonicalString())),
        )
        for (payload in variants) {
            val h = staged()
            h.endpoint.executionReply = f.reply(payload)
            assertIs<LocalOperationResult.Unresolved>(h.gateway.submit(f.uiOperation))
            assertOriginalBodyRetained(h)
        }
        val wrongEnvelopeTime = staged()
        wrongEnvelopeTime.endpoint.executionReply = f.reply(recordedAt = f.e3RecordedAt)
        assertIs<LocalOperationResult.Unresolved>(wrongEnvelopeTime.gateway.submit(f.uiOperation))
        assertOriginalBodyRetained(wrongEnvelopeTime)
    }

    @Test
    fun rcr039MissingCorruptPayloadOrWrongOriginalEnvelopeCannotBecomeRecordedReceipt() = runTest {
        val validJson = f.wire.parseToJsonElement(f.resultBytes(f.uiResult()).copyBytes().decodeToString()).jsonObject
        val missingField = StoredBytes(JsonObject(validJson - "receivedAt").toString().encodeToByteArray())
        for (reply in listOf(f.reply(StoredBytes(byteArrayOf())), f.reply(StoredBytes("not-a-result".encodeToByteArray())),
            f.reply(missingField), f.reply(operation = f.e1), f.reply(actor = f.recorderB))) {
            val h = staged()
            h.endpoint.executionReply = reply
            assertIs<LocalOperationResult.Unresolved>(h.gateway.submit(f.uiOperation))
            assertOriginalBodyRetained(h)
        }
    }

    @Test
    fun rcr017And032OriginalE1ReplayRecoversOriginalCasZeroResultWithoutExecute() = runTest {
        val h = ControlledRetailReceiptGateway()
        val request = LocalRetailCustomerReceiptCodec.command(f.e1, f.e1Request())
        // Controlled saved original, not a genuine earlier production paid sale or durable commit.
        val intent = LocalCommandIntent(f.e1, LocalAction.RETAIL_CUSTOMER_RECEIPT, 1, null, emptyList())
        val saved = StoredClientIntentV1.staged(h.journal.owner, intent, request,
            LocalText("Original observation", "الملاحظة الأصلية"), listOf(
                LocalRecordValue(LocalText("Original operation", "العملية الأصلية"), LocalText(f.e1.value, f.e1.value))))
        h.journal.entries[f.e1] = saved
        h.endpoint.found = f.reply(f.resultBytes(LocalRetailCustomerReceiptResultV1.Applied(f.sale, f.e1,
            Revision(1), f.e1ReceivedAt, f.zone, f.e1RecordedAt)), operation = f.e1, recordedAt = f.e1RecordedAt, replayed = true)
        val result = assertIs<LocalOperationResult.Recorded>(h.gateway.lookup(f.e1))
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, result.decision)
        assertEquals(f.e1, result.operationId)
        assertEquals(f.e1.value, result.result?.reference?.id?.value)
        assertTrue(result.replayed)
        assertTrue(result.result!!.values.any { it.value.english == f.e1ReceivedAt.canonicalString() })
        assertEquals(Revision(0), LocalRetailCustomerReceiptCodec.decode(h.journal.entries.getValue(f.e1).request().payload).expectedHeadRevision)
        assertEquals(f.head(1), h.endpoint.head)
        assertTrue(h.endpoint.executions.isEmpty())
    }

    @Test
    fun rcr040AckAndDuplicateAckKeepExactOriginalOperationDespiteNewerHead() = runTest {
        val h = staged()
        val result = assertIs<LocalOperationResult.Recorded>(h.gateway.submit(f.uiOperation))
        h.endpoint.found = f.reply(replayed = true)
        h.endpoint.head = f.head(3)
        repeat(2) {
            val ack = assertIs<LocalAcknowledgementResult.Acknowledged>(h.gateway.acknowledgeRecorded(f.uiOperation))
            assertEquals(f.uiOperation, ack.operationId)
        }
        assertTrue(h.journal.acknowledgements.all { it == f.uiOperation })
        assertTrue(h.journal.entries.getValue(f.uiOperation).acknowledged)
        assertOriginalBodyRetained(h)
        assertTrue(h.endpoint.lookups.all { it.operationId == f.uiOperation && it.originalActorId == f.collector })
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, result.decision)
        assertEquals(f.uiEventReference, result.result?.reference)
        val page = assertIs<LocalReadResult.Available>(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS,
            reference = result.result!!.reference))).page
        assertEquals(f.uiOperation, (h.endpoint.queries.last() as LocalRetailCustomerReceiptQueryV1.Event).operationId)
        assertTrue(page.records.first().values.any { it.value.english == f.uiReceivedAt.canonicalString() })
        assertEquals(1, h.endpoint.executions.size)
        assertEquals(1, h.journal.stagedCalls)
    }

    @Test
    fun rcr040FailedAckPersistencePreservesOriginalRecoveryAndNoNewSubmit() = runTest {
        val h = staged()
        assertIs<LocalOperationResult.Recorded>(h.gateway.submit(f.uiOperation))
        h.endpoint.found = f.reply(replayed = true)
        h.journal.acknowledgeAvailable = false
        assertIs<LocalAcknowledgementResult.Unresolved>(h.gateway.acknowledgeRecorded(f.uiOperation))
        assertFalse(h.journal.entries.getValue(f.uiOperation).acknowledged)
        val recovered = assertIs<LocalOperationResult.Recorded>(h.reopenedGateway().lookup(f.uiOperation))
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, recovered.decision)
        assertEquals(f.uiEventReference, recovered.result?.reference)
        assertOriginalBodyRetained(h)
        assertEquals(1, h.endpoint.executions.size)
    }

    @Test
    fun rcr040LateAckAfterLeaseRevocationCannotAcknowledgeOrReleaseResult() = runTest {
        val h = staged()
        assertIs<LocalOperationResult.Recorded>(h.gateway.submit(f.uiOperation))
        h.endpoint.found = f.reply(replayed = true)
        h.endpoint.onLookup = { h.lifetimeCurrent = false }
        val result = assertIs<LocalAcknowledgementResult.Unresolved>(h.gateway.acknowledgeRecorded(f.uiOperation))
        assertEquals(LocalServiceProblem.SESSION_ENDED, result.problem)
        assertFalse(h.journal.entries.getValue(f.uiOperation).acknowledged)
        assertTrue(h.journal.acknowledgements.isEmpty())
        assertOriginalBodyRetained(h)
        assertEquals(1, h.endpoint.executions.size)
    }

    @Test
    fun rcr040WrongOriginalResultCannotYieldSuccessfulAckTarget() = runTest {
        val h = staged()
        h.endpoint.executionReply = f.reply(operation = f.e1)
        assertIs<LocalOperationResult.Unresolved>(h.gateway.submit(f.uiOperation))
        h.endpoint.found = f.reply(operation = f.e1)
        assertIs<LocalAcknowledgementResult.Unresolved>(h.gateway.acknowledgeRecorded(f.uiOperation))
        assertFalse(h.journal.entries.getValue(f.uiOperation).acknowledged)
        assertTrue(h.journal.acknowledgements.isEmpty())
        assertOriginalBodyRetained(h)
    }

    private suspend fun staged(): ControlledRetailReceiptGateway = ControlledRetailReceiptGateway().also {
        assertIs<LocalStageResult.Staged>(it.gateway.stage(f.intent()))
    }
    private fun assertOriginalBodyRetained(h: ControlledRetailReceiptGateway) {
        val entry = h.journal.entries.getValue(f.uiOperation)
        assertEquals(f.uiOperation, entry.operationId)
        assertEquals(f.collector, entry.owner.actorId)
        assertEquals(LocalRetailCustomerReceiptCodec.encode(f.uiRequest()), entry.request().payload)
    }
    private fun mutatedResult(field: String, value: kotlinx.serialization.json.JsonElement): StoredBytes {
        val original = f.wire.parseToJsonElement(f.resultBytes(f.uiResult()).copyBytes().decodeToString()).jsonObject
        return StoredBytes(JsonObject(original + (field to value)).toString().encodeToByteArray())
    }
}

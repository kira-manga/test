package com.ukkera.pos.app.gateway.independent.counterreceiptrpc

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SUPPORT only: unchanged CRRPC-V1-002/003/010/012–014/019/020/022/023/028,
 * with the independent frozen v1.1 result-code supplement. Controlled endpoint
 * and model journal calls do not establish Master authority, native originals,
 * admitted RPC, event/business ledgers, durable persistence or real restart.
 */
class CounterReceiptRpcGatewaySupportingTest {
    private val f = CounterReceiptRpcData

    @Test
    fun support002ExplicitInstallationHasOnlyReceiptActionsAndKnownSaleEntryDoesNotInventOriginals() = runTest {
        val g = CounterReceiptRpcControlledGateway()
        for (action in LocalAction.entries) assertEquals(action == LocalAction.RETAIL_CUSTOMER_RECEIPT, g.installed.allows(action))
        for (workflow in listOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS, LocalWorkflow.RETURNS)) {
            val page = assertIs<LocalReadResult.Available>(g.gateway.read(f.query(workflow, search = ""))).page
            assertTrue(page.records.isEmpty())
            assertTrue(page.forms.isEmpty())
        }
        assertTrue(g.endpoint.queries.isEmpty())
        for (workflow in listOf(LocalWorkflow.SETUP, LocalWorkflow.KITCHEN, LocalWorkflow.CASH)) {
            val result = assertIs<LocalReadResult.Unavailable>(g.gateway.read(LocalWorkflowQuery(workflow)))
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, result.problem)
        }
        assertTrue(g.endpoint.executions.isEmpty())
        assertTrue(g.endpoint.queries.isEmpty())
    }

    @Test
    fun support003RecordDenialNeverFallsBackAndExplicitReturnsIsDocumentaryOnly() = runTest {
        for ((record, lookup) in listOf(true to false, false to true, true to true, false to false)) {
            val g = CounterReceiptRpcControlledGateway(record, lookup)
            val recordResult = g.gateway.read(f.query())
            if (record) {
                assertIs<LocalReadResult.Available>(recordResult)
                assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD,
                    (g.endpoint.queries.single() as LocalRetailCustomerReceiptQueryV1.Head).access)
            } else {
                assertEquals(LocalServiceProblem.DENIED, assertIs<LocalReadResult.Unavailable>(recordResult).problem)
                assertTrue(g.endpoint.queries.isEmpty())
            }
            val before = g.endpoint.queries.size
            val documentary = g.gateway.read(f.query(LocalWorkflow.RETURNS))
            if (lookup) {
                val page = assertIs<LocalReadResult.Available>(documentary).page
                assertTrue(page.forms.isEmpty())
                assertTrue(page.records.none { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH })
                assertEquals(LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP,
                    (g.endpoint.queries.last() as LocalRetailCustomerReceiptQueryV1.Head).access)
            } else {
                assertEquals(LocalServiceProblem.DENIED, assertIs<LocalReadResult.Unavailable>(documentary).problem)
                assertEquals(before, g.endpoint.queries.size)
            }
            assertTrue(g.endpoint.executions.isEmpty())
        }
    }

    @Test
    fun support010ExactEventSearchPreservesOriginalOperationAndPurposeAndMalformedReferencesRefuse() = runTest {
        for (workflow in listOf(LocalWorkflow.ORDERS, LocalWorkflow.RETURNS)) {
            val g = CounterReceiptRpcControlledGateway()
            val page = assertIs<LocalReadResult.Available>(g.gateway.read(f.query(workflow,
                "customer-receipt-event:${f.sale.value}:${f.operation.value}"))).page
            val query = assertIs<LocalRetailCustomerReceiptQueryV1.Event>(g.endpoint.queries.single())
            assertEquals(f.sale, query.saleId)
            assertEquals(f.operation, query.operationId)
            assertEquals(if (workflow == LocalWorkflow.RETURNS) LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP
                else LocalRetailCustomerReceiptReadAccessV1.RECORD, query.access)
            assertEquals(f.eventReference(), page.records.first().reference)
            assertTrue(page.forms.isEmpty())
        }
        for (search in listOf("customer-receipt:not-an-id", "customer-receipt-event:${f.sale.value}",
            "customer-receipt-event:${f.sale.value}:${f.operation.value}:extra")) {
            val g = CounterReceiptRpcControlledGateway()
            assertEquals(LocalServiceProblem.INVALID_INPUT,
                assertIs<LocalReadResult.Unavailable>(g.gateway.read(f.query(search = search))).problem)
            assertTrue(g.endpoint.queries.isEmpty())
        }
    }

    @Test
    fun support012And014StageBindsOneSortedE1CommandAndNeverSubmitsOrInventsTimeOrZone() = runTest {
        val g = CounterReceiptRpcControlledGateway()
        assertIs<LocalStageResult.Staged>(g.gateway.stage(f.intent()))
        val entry = g.journal.entries.values.single()
        assertEquals(f.operation, entry.operationId)
        assertEquals(f.owner, entry.owner.actorId)
        assertEquals(2, entry.formVersion)
        assertEquals(LocalRetailCustomerReceiptCodec.COMMAND, entry.request().command)
        assertEquals(f.request(), LocalRetailCustomerReceiptCodec.decode(entry.request().payload))
        assertEquals(f.fixtures.obj("batchInput").array("expectedWirePortionOrder").map { it.toString().trim('"') },
            LocalRetailCustomerReceiptCodec.decode(entry.request().payload).portions.map { it.portionId.value })
        assertEquals(1, g.journal.stagedCalls)
        assertTrue(g.endpoint.executions.isEmpty())
        assertIs<LocalStageResult.Staged>(g.reopenedGateway().stage(f.intent()))
        assertEquals(1, g.journal.entries.size)
        assertEquals(1, g.journal.stagedCalls)
        assertTrue(g.endpoint.executions.isEmpty())
    }

    @Test
    fun support012And013MissingFieldsStaleRevisionAndAlteredUnitsRefuseBeforeStaging() = runTest {
        for (field in listOf(LocalField.CUSTOMER_RECEIVED_AT, LocalField.CUSTOMER_RECEIPT_TIME_ZONE)) {
            val g = CounterReceiptRpcControlledGateway()
            assertIs<LocalStageResult.NotStaged>(g.gateway.stage(f.intent(values = f.values().filter { it.field != field })))
            assertTrue(g.journal.entries.isEmpty())
            assertTrue(g.endpoint.executions.isEmpty())
        }
        val stale = CounterReceiptRpcControlledGateway()
        stale.endpoint.head = f.laterHead()
        assertIs<LocalStageResult.NotStaged>(stale.gateway.stage(f.intent()))
        assertTrue(stale.journal.entries.isEmpty())
        assertTrue(stale.endpoint.executions.isEmpty())
        val wrongUnit = CounterReceiptRpcControlledGateway()
        val rows = f.rows().map { row -> LocalInputLine(row.lineId, row.values.map {
            if (it.field == LocalField.UNIT) LocalScalarInput(it.field, LocalScalarValue.Identifier(f.unit("ITEM"))) else it
        }) }
        assertIs<LocalStageResult.NotStaged>(wrongUnit.gateway.stage(f.intent(rows = rows)))
        assertTrue(wrongUnit.journal.entries.isEmpty())
        assertTrue(wrongUnit.endpoint.executions.isEmpty())
    }

    @Test
    fun support019And028MatchingOriginalAppliedIsRecordedOnlyAndAckCannotCreateAnotherDispatch() = runTest {
        val g = CounterReceiptRpcControlledGateway()
        assertIs<LocalStageResult.Staged>(g.gateway.stage(f.intent()))
        val storedBody = g.journal.entries.getValue(f.operation).request().payload
        val recorded = assertIs<LocalOperationResult.Recorded>(g.gateway.submit(f.operation))
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, recorded.decision)
        assertEquals(f.eventReference(), recorded.result?.reference)
        assertEquals(storedBody, g.endpoint.executions.single().payload)
        val retained = g.journal.entries.getValue(f.operation).recorded!!
        assertEquals(f.applied(), LocalRetailCustomerReceiptCodec.decodeResult(StoredBytes(decodeHex(retained.payloadHex, 2_048))))
        g.endpoint.found = f.reply(replayed = true)
        assertIs<LocalAcknowledgementResult.Acknowledged>(g.gateway.acknowledgeRecorded(f.operation))
        assertIs<LocalAcknowledgementResult.Acknowledged>(g.gateway.acknowledgeRecorded(f.operation))
        assertTrue(g.journal.entries.getValue(f.operation).acknowledged)
        assertEquals(1, g.endpoint.executions.size)
        assertEquals(storedBody, g.journal.entries.getValue(f.operation).request().payload)
    }

    @Test
    fun support019InvalidEnvelopeVectorsNeverRememberOrAcknowledgeReceiptSuccess() = runTest {
        val refused = f.resultBytes(LocalRetailCustomerReceiptResultV1.Refused(LocalRetailCustomerReceiptRefusalV1.REVISION_CONFLICT))
        val applied = f.applied()
        val wrongZone = JsonObject(f.wire.parseToJsonElement(f.resultBytes(applied).copyBytes().decodeToString()).jsonObject
            .toMutableMap().also { it["receiptTimeZoneId"] = JsonPrimitive("UTC") })
        val invalid = listOf(
            f.reply(code = "COMMITTED"),
            f.reply(committed = false),
            f.reply(payload = refused, code = f.refusedCode),
            f.reply(committed = false, code = f.refusedCode),
            f.reply(payload = StoredBytes(byteArrayOf())),
            f.reply(payload = refused),
            f.reply(payload = f.resultBytes(applied.copy(saleId = f.id("otherSale")))),
            f.reply(payload = f.resultBytes(applied.copy(eventOperationId = f.otherOperation))),
            f.reply(payload = f.resultBytes(applied.copy(headRevision = com.ukkera.pos.types.Revision(2)))),
            f.reply(payload = f.resultBytes(applied.copy(receivedAt = f.instant("2026-11-01T20:30:01Z")))),
            f.reply(payload = f.resultBytes(applied.copy(recordedAt = f.instant("2026-11-01T20:35:01Z")))),
            f.reply(payload = StoredBytes(wrongZone.toString().encodeToByteArray())),
            f.reply(operation = f.otherOperation),
            f.reply(actor = f.otherActor),
            f.reply(recordedAt = f.instant("2026-11-01T20:35:01Z")),
        )
        for (reply in invalid) {
            val g = CounterReceiptRpcControlledGateway()
            assertIs<LocalStageResult.Staged>(g.gateway.stage(f.intent()))
            val original = g.journal.entries.getValue(f.operation).request().payload
            g.endpoint.executionReply = reply
            assertIs<LocalOperationResult.Unresolved>(g.gateway.submit(f.operation))
            assertNull(g.journal.entries.getValue(f.operation).recorded)
            assertIs<LocalAcknowledgementResult.Unresolved>(g.gateway.acknowledgeRecorded(f.operation))
            assertTrue(g.journal.acknowledgements.isEmpty())
            assertFalse(g.journal.entries.getValue(f.operation).acknowledged)
            assertEquals(original, g.journal.entries.getValue(f.operation).request().payload)
            assertEquals(1, g.journal.entries.size)
            assertEquals(1, g.endpoint.executions.size)
        }
    }

    @Test
    fun support019MatchingOrdinaryRefusalRemainsRejectedNotReceiptOrFinancialSuccess() = runTest {
        val g = CounterReceiptRpcControlledGateway()
        assertIs<LocalStageResult.Staged>(g.gateway.stage(f.intent()))
        g.endpoint.executionReply = f.reply(payload = f.resultBytes(LocalRetailCustomerReceiptResultV1.Refused(
            LocalRetailCustomerReceiptRefusalV1.REVISION_CONFLICT)), committed = false, code = f.refusedCode)
        val result = assertIs<LocalOperationResult.Recorded>(g.gateway.submit(f.operation))
        assertEquals(LocalRecordedDecision.REJECTED, result.decision)
        assertNull(result.result)
        assertFalse(g.journal.entries.getValue(f.operation).recorded!!.committed)
    }

    @Test
    fun support020And022UnknownOutcomeAndReauthorizationRecoverOnlyTheOriginalWithoutNewSubmission() = runTest {
        val g = CounterReceiptRpcControlledGateway()
        assertIs<LocalStageResult.Staged>(g.gateway.stage(f.intent()))
        val original = g.journal.entries.getValue(f.operation).request().payload
        g.endpoint.executionReply = MutationReply.Deferred(f.operation, DeferredReason.STORAGE_UNCERTAIN)
        assertIs<LocalOperationResult.Unresolved>(g.gateway.submit(f.operation))
        assertNull(g.journal.entries.getValue(f.operation).recorded)
        g.endpoint.currentAvailable = false
        assertIs<LocalOperationResult.Unresolved>(g.gateway.lookup(f.operation))
        assertEquals(1, g.endpoint.executions.size)
        g.endpoint.currentAvailable = true
        g.endpoint.found = f.reply(replayed = true)
        g.endpoint.head = f.laterHead()
        val recovered = assertIs<LocalOperationResult.Recorded>(g.reopenedGateway().lookup(f.operation))
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, recovered.decision)
        assertEquals(f.eventReference(), recovered.result?.reference)
        assertTrue(recovered.replayed)
        assertEquals(original, g.journal.entries.getValue(f.operation).request().payload)
        assertTrue(g.endpoint.lookups.all { it.operationId == f.operation && it.originalActorId == f.owner })
        assertEquals(1, g.endpoint.executions.size)
        assertEquals(1, g.journal.entries.size)
    }
}

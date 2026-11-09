@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.foodtableassignmentrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcGate
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcWriteCut
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentCodecV1
import com.ukkera.pos.types.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** FTA-RPC-v1-S03 supporting typed-result and controlled-journal slices only.
 * Frozen fixture A supplies O1/order7, F1/floor2, T01/table3 and first binding1.
 * These tests qualify no Master refusal predicate, native durability, authenticated
 * RPC, SDK transport Receipt, server eviction or whole W08/#59 case. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FoodTableAssignmentRpcV1TypedResultSupportingTest {
    private val input get() = FrozenFoodTableAssignmentRpcV1GatewayInputs

    @Test
    fun ftaRpcV1S03ExactASuccessAndStableRefusalValidateBeforeRemembranceAndApplicationAck() = runTest {
        assertEquals(input.id("O1"), input.request.orderId)
        assertEquals(Revision(7), input.request.expectedOrderRevision)
        assertEquals(input.id("F1"), input.request.floorId)
        assertEquals(Revision(2), input.request.expectedFloorRevision)
        assertEquals(input.id("T01"), input.request.tableId)
        assertEquals(Revision(3), input.request.expectedTableRevision)
        assertNull(input.request.expectedAssignmentRevision)
        assertEquals(Revision(1), input.expectedBinding.revision)
        for ((carrier, expected) in listOf(input.carrier() to input.expectedSuccess,
            input.carrier(committed = false) to input.expectedRefusal)) withFoodTableAssignmentRpcV1Fixture { f ->
            val original = f.journal.entries.getValue(input.operation)
            f.supplied = carrier
            assertEquals(expected, tableAssignmentOutcome(input.request, carrier.committed, carrier.code, carrier.result))
            val result = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(input.operation))
            assertEquals(input.operation, result.operationId)
            assertEquals(if (carrier.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED, result.decision)
            val remembered = f.journal.entries.getValue(input.operation)
            assertOriginalA(original, remembered)
            assertEquals(StoredKernelResultV1.from(carrier), remembered.recorded)
            assertFalse(remembered.acknowledged)
            assertEquals(1, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
            assertNoDispatch(f)

            assertIs<LocalAcknowledgementResult.Acknowledged>(f.gateway.acknowledgeRecorded(input.operation))
            val acknowledged = f.journal.entries.getValue(input.operation)
            assertOriginalA(original, acknowledged)
            assertEquals(remembered.recorded, acknowledged.recorded)
            assertTrue(acknowledged.acknowledged)
            assertEquals(listOf(input.operation), f.journal.cleared)
            assertTrue(f.events.indexOf("result.retained") >= 0)
            assertTrue(f.events.indexOf("ack.before-retention") > f.events.indexOf("result.retained"))
            assertTrue(f.master.lookups.all { it == original.identity() })
            assertNoDispatch(f)
        }
    }

    @Test
    fun ftaRpcV1S03MalformedForeignTargetsAndRevisionsNeverReachRemembranceOrApplicationAck() = runTest {
        val alteredOrderRevision = input.successJson.toMutableMap().apply {
            put("orderRevision", JsonPrimitive("8"))
            put("assignment", JsonObject(input.successJson.getValue("assignment").jsonObject.toMutableMap().apply {
                put("observedOrderRevision", JsonPrimitive("8"))
            }))
        }
        val payloads = listOf(
            "malformed/truncated" to input.bytes("{"),
            "malformed/missing-assignment" to input.payload(JsonObject(input.successJson - "assignment")),
            "malformed/noncanonical-order-id" to input.resultWith("orderId", JsonPrimitive("O1")),
            "malformed/negative-revision" to input.bindingWith("revision", JsonPrimitive("-1")),
            "wrong-family-or-version/FOOD-result" to input.bytes("""{"code":"food.order.draft_created","orderId":"${input.id("O1").value}","orderRevision":"7","portions":[]}"""),
            "wrong-target-or-revision/order" to input.resultWith("orderId", JsonPrimitive(input.id("unknown_order").value)),
            "wrong-target-or-revision/floor" to input.bindingWith("floorId", JsonPrimitive(input.id("F2").value)),
            "wrong-target-or-revision/table" to input.bindingWith("tableId", JsonPrimitive(input.id("T02").value)),
            "wrong-target-or-revision/order7-to8" to input.payload(JsonObject(alteredOrderRevision)),
            "wrong-target-or-revision/first-binding1-to2" to input.bindingWith("revision", JsonPrimitive("2")),
            "wrong-target-or-revision/floor2-to3" to input.bindingWith("floorRevision", JsonPrimitive("3")),
            "wrong-target-or-revision/table3-to4" to input.bindingWith("tableRevision", JsonPrimitive("4")),
            "inconsistent-success-or-refusal/refusal-with-success-fields" to input.resultWith("code", JsonPrimitive(input.refusalCode)),
            "inconsistent-success-or-refusal/success-without-binding" to input.resultWith("assignment", JsonNull),
        )
        for ((variant, payload) in payloads) withFoodTableAssignmentRpcV1Fixture { f ->
            val original = f.journal.entries.getValue(input.operation)
            f.supplied = input.carrier(result = payload)
            assertFails(variant) { tableAssignmentOutcome(input.request, true, "committed", payload) }
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(input.operation), variant)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(input.operation), variant)
            assertUnresolvedWithoutResultWrites(f, original, variant)
        }
    }

    @Test
    fun ftaRpcV1S03WrongOriginalAndInconsistentEnvelopesCannotRememberOrAcknowledgeA() = runTest {
        val carriers = listOf(
            "wrong-original/operation" to input.carrier(operationId = OperationId(input.id("B").value)),
            "wrong-original/actor" to input.carrier(originalActorId = input.id("other_owner")),
            "malformed/envelope-with-no-original-sequence" to input.carrier(sequence = JournalSequence(0)),
            "inconsistent-success-or-refusal/outer-code" to input.carrier(code = input.refusalCode),
            "inconsistent-success-or-refusal/committed-refusal" to input.carrier(result = input.refusalPayload),
            "inconsistent-success-or-refusal/rejected-success" to input.carrier(committed = false, result = input.successPayload),
            "inconsistent-success-or-refusal/refusal-outer-code" to input.carrier(committed = false, code = "committed"),
        )
        for ((variant, carrier) in carriers) withFoodTableAssignmentRpcV1Fixture { f ->
            val original = f.journal.entries.getValue(input.operation)
            f.supplied = carrier
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(input.operation), variant)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(input.operation), variant)
            assertUnresolvedWithoutResultWrites(f, original, variant)
        }
    }

    @Test
    fun ftaRpcV1S03UnsupportedSavedEntryFormAndCommandVersionsRemainUnresolved() = runTest {
        // The existing typed result has no version/family discriminator. Exercise its
        // actual saved envelope/form/command fields, not an invented result DTO field.
        val original = input.entry()
        val variants = listOf(
            "wrong-family-or-version/saved-entry" to original.copy(version = 2),
            "wrong-family-or-version/form" to original.copy(formVersion = 2),
            "wrong-family-or-version/command" to original.copy(commandVersion = 2),
            "wrong-family-or-version/action" to original.copy(action = LocalAction.FOOD_ADD.name),
        )
        for ((variant, malformed) in variants) withFoodTableAssignmentRpcV1Fixture(malformed) { f ->
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(input.operation), variant)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(input.operation), variant)
            assertEquals(malformed, f.journal.entries.getValue(input.operation), variant)
            assertNull(f.journal.entries.getValue(input.operation).recorded, variant)
            assertFalse(f.journal.entries.getValue(input.operation).acknowledged, variant)
            assertEquals(0, f.journal.rememberCalls, variant)
            assertEquals(0, f.journal.ackCalls, variant)
            assertTrue(f.journal.cleared.isEmpty(), variant)
            assertNoDispatch(f, variant)
        }
    }

    @Test
    fun ftaRpcV1S03SuccessfulRemembranceMustCompleteBeforeApplicationAckEnters() = runTest(timeout = 10.seconds) {
        for (carrier in listOf(input.carrier(), input.carrier(committed = false))) withFoodTableAssignmentRpcV1Fixture { f ->
            val original = f.journal.entries.getValue(input.operation)
            f.supplied = carrier
            val remember = WarehouseRpcGate().also { f.journal.rememberGate = it }
            val ack = WarehouseRpcGate().also { f.journal.ackGate = it }
            val remembering = async { f.gateway.lookup(input.operation) }
            try {
                withTimeout(5_000) { remember.entered.await() }
                assertEquals(original, f.journal.entries.getValue(input.operation))
                assertNull(f.journal.entries.getValue(input.operation).recorded)
                val acknowledging = async { f.gateway.acknowledgeRecorded(input.operation) }
                runCurrent()
                assertFalse(remembering.isCompleted)
                assertFalse(acknowledging.isCompleted)
                assertFalse(ack.entered.isCompleted)
                assertEquals(0, f.journal.ackCalls)
                assertNoDispatch(f)

                remember.release()
                assertIs<LocalOperationResult.Recorded>(remembering.await())
                withTimeout(5_000) { ack.entered.await() }
                val remembered = f.journal.entries.getValue(input.operation)
                assertOriginalA(original, remembered)
                assertEquals(StoredKernelResultV1.from(carrier), remembered.recorded)
                assertFalse(remembered.acknowledged)
                assertFalse(acknowledging.isCompleted)
                assertTrue(f.journal.cleared.isEmpty())
                assertTrue(f.events.indexOf("ack.before-retention") > f.events.indexOf("result.retained"))
                ack.release()
                assertIs<LocalAcknowledgementResult.Acknowledged>(acknowledging.await())
                assertEquals(listOf(input.operation), f.journal.cleared)
                assertTrue(f.master.lookups.all { it == original.identity() })
                assertNoDispatch(f)
            } finally { remember.release(); ack.release() }
        }
    }

    @Test
    fun ftaRpcV1S03FailedRemembrancePreservesExactAAndSendsNoApplicationAck() = runTest {
        for (carrier in listOf(input.carrier(), input.carrier(committed = false))) withFoodTableAssignmentRpcV1Fixture { f ->
            val original = f.journal.entries.getValue(input.operation)
            f.supplied = carrier
            f.journal.rememberCut = WarehouseRpcWriteCut.FAILED
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(input.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(input.operation))
            assertEquals(original, f.journal.entries.getValue(input.operation))
            assertNull(f.journal.entries.getValue(input.operation).recorded)
            assertFalse(f.journal.entries.getValue(input.operation).acknowledged)
            assertEquals(1, f.journal.rememberCalls)
            assertFalse("result.retained" in f.events)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.journal.cleared.isEmpty())
            assertTrue(f.master.lookups.all { it == original.identity() })
            assertNoDispatch(f)
        }
    }

    @Test
    fun ftaRpcV1S03NoTypedTerminalCannotEstablishRemembranceOrApplicationAcknowledgement() = runTest {
        withFoodTableAssignmentRpcV1Fixture { f ->
            val original = f.journal.entries.getValue(input.operation)
            f.supplied = null
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(input.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(input.operation))
            assertUnresolvedWithoutResultWrites(f, original)
            // Only the gateway's no-terminal negative is bound. Actual receipt-only
            // SDK delivery/decoding is absent here and remains S03/RB08 unbound.
        }
    }

    private fun assertOriginalA(original: StoredClientIntentV1, actual: StoredClientIntentV1) {
        assertTrue(original.sameIntent(actual))
        assertEquals(input.operation, actual.operationId)
        assertEquals(input.owner, actual.owner)
        assertEquals(original.inputDigest, actual.inputDigest)
        assertEquals(original.semanticSha256, actual.semanticSha256)
        assertEquals(original.payloadHex, actual.payloadHex)
        assertEquals(original.identity(), actual.identity())
        assertEquals(input.request, LocalFoodTableAssignmentCodecV1.decode(actual.request().payload))
    }

    private fun assertUnresolvedWithoutResultWrites(f: FoodTableAssignmentRpcV1ControlledFixture,
        original: StoredClientIntentV1, variant: String = "FTA-RPC-v1-S03") {
        assertEquals(original, f.journal.entries.getValue(input.operation), variant)
        assertNull(f.journal.entries.getValue(input.operation).recorded, variant)
        assertFalse(f.journal.entries.getValue(input.operation).acknowledged, variant)
        assertEquals(0, f.journal.rememberCalls, variant)
        assertEquals(0, f.journal.ackCalls, variant)
        assertTrue(f.journal.cleared.isEmpty(), variant)
        assertNoDispatch(f, variant)
    }

    private fun assertNoDispatch(f: FoodTableAssignmentRpcV1ControlledFixture, variant: String = "FTA-RPC-v1-S03") {
        assertTrue(f.master.mutations.isEmpty(), variant)
        assertTrue(f.master.reads.isEmpty(), variant)
        assertEquals(0, f.journal.stageCalls, variant)
        assertEquals(0, f.journal.dispatchCalls, variant)
    }
}

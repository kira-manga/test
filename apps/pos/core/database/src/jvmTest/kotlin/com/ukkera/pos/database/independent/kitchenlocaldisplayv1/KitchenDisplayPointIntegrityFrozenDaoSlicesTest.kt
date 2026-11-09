package com.ukkera.pos.database

import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.AssumptionViolatedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * All 20 frozen detached SQLite variants. These are supporting KLD-02/05/09 slices;
 * they do not qualify a Master owner, FOOD original, application delivery or admitted RPC.
 */
class KitchenDisplayPointIntegrityFrozenDaoSlicesTest {
    private val inputs = KitchenDisplayPointFrozenInputs.load()

    @Test fun kldPoint01Base() = execute("KLD-POINT-01.BASE")
    @Test fun kldPoint01Min() = execute("KLD-POINT-01.MIN")
    @Test fun kldPoint01Max() = execute("KLD-POINT-01.MAX")
    @Test fun kldPoint02Scope() = execute("KLD-POINT-02.SCOPE")
    @Test fun kldPoint02Operation() = execute("KLD-POINT-02.OPERATION")
    @Test fun kldPoint02Ordinal() = execute("KLD-POINT-02.ORDINAL")
    @Test fun kldPoint02Missing() = execute("KLD-POINT-02.MISSING")
    @Test fun kldPoint03Body() = execute("KLD-POINT-03.BODY")
    @Test fun kldPoint03Hash() = execute("KLD-POINT-03.HASH")
    @Test fun kldPoint03Empty() = execute("KLD-POINT-03.EMPTY")
    @Test fun kldPoint03Oversize() = execute("KLD-POINT-03.OVERSIZE")
    @Test fun kldPoint03Kind() = execute("KLD-POINT-03.KIND")
    @Test fun kldPoint03Hashcase() = execute("KLD-POINT-03.HASHCASE")
    @Test fun kldPoint04Body() = execute("KLD-POINT-04.BODY")
    @Test fun kldPoint04Scope() = execute("KLD-POINT-04.SCOPE")
    @Test fun kldPoint04Operation() = execute("KLD-POINT-04.OPERATION")
    @Test fun kldPoint04Ordinal() = execute("KLD-POINT-04.ORDINAL")
    @Test fun kldPoint04Kind() = execute("KLD-POINT-04.KIND")
    @Test fun kldPoint04Hash() = execute("KLD-POINT-04.HASH")
    @Test fun kldPoint05Newer() = execute("KLD-POINT-05.NEWER")

    private fun execute(id: String): Unit = runBlocking {
        val case = inputs.case(id)
        val harness = KitchenLocalDisplayRoomHarness.create()
        try {
            materialize(harness, case.initialRows, case.initialRows, id)
            var expectedRows = case.initialRows
            for ((step, action) in case.actions.withIndex()) {
                when (action) {
                    is KitchenDisplayPointFrozenInputs.Action.Insert -> {
                        materialize(harness, listOf(action.row), action.expectedRows, id)
                        expectedRows = action.expectedRows
                    }
                    is KitchenDisplayPointFrozenInputs.Action.Read ->
                        readAndPreserve(harness, action, expectedRows, "$id step ${step + 1}")
                }
            }
        } finally {
            harness.close()
        }
    }

    /** A rejected or coerced fixture is UNBOUND, never a successful point-reader assertion. */
    private suspend fun materialize(harness: KitchenLocalDisplayRoomHarness,
        insert: List<KitchenDisplayPointFrozenInputs.Row>, expected: List<KitchenDisplayPointFrozenInputs.Row>, id: String) {
        val actual = try {
            for (row in insert) harness.insert(row)
            harness.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AssumptionViolatedException("UNBOUND $id: prescribed SQLite fixture preparation was rejected", failure)
        }
        if (actual != expected) {
            throw AssumptionViolatedException("UNBOUND $id: prescribed raw values or SQLite storage classes were not retained")
        }
    }

    private suspend fun readAndPreserve(harness: KitchenLocalDisplayRoomHarness,
        action: KitchenDisplayPointFrozenInputs.Action.Read, expectedRows: List<KitchenDisplayPointFrozenInputs.Row>, label: String) {
        val before = harness.snapshot()
        assertEquals(expectedRows, before, "$label complete pre-read raw cut")
        val selectorBefore = action.selector.copy()
        val bodyBefore = action.expectedBody.bytes.copyBytes()
        try {
            val observation = observePoint(harness.dao, action)
            when (action.outcome) {
                KitchenDisplayPointFrozenInputs.Outcome.SUCCESS_BYTES -> {
                    assertTrue(observation is PointObservation.Bytes, "$label must succeed with actual stored bytes")
                    val expected = checkNotNull(action.returnedBody)
                    val actual = observation.body.copyBytes()
                    assertContentEquals(expected.bytes.copyBytes(), actual, "$label exact opaque body")
                    assertEquals(expected.byteLength, actual.size, "$label exact byte length")
                    assertEquals(expected.digest, KitchenDisplayPointFrozenInputs.independentDigest(actual), "$label literal digest")
                }
                KitchenDisplayPointFrozenInputs.Outcome.EXPLICIT_INTEGRITY_FAILURE ->
                    assertTrue(observation is PointObservation.IntegrityFailure,
                        "$label must fail explicitly without successful body or empty result")
            }
        } finally {
            assertEquals(before, harness.snapshot(), "$label complete post-read raw cut and storage classes")
            assertEquals(selectorBefore, action.selector, "$label original selector unchanged")
            assertContentEquals(bodyBefore, action.expectedBody.bytes.copyBytes(), "$label expected body unchanged")
        }
    }

    private sealed interface PointObservation {
        data class Bytes(val body: StoredBytes) : PointObservation
        data class IntegrityFailure(val failure: Exception) : PointObservation
    }

    private suspend fun observePoint(dao: MasterDao,
        action: KitchenDisplayPointFrozenInputs.Action.Read): PointObservation {
        val selector = action.selector
        val scope = selector.branchScope()
        assertEquals(selector.scope, scope.storageKey(), "Frozen scope conversion must preserve literal selector")
        try {
            // Isolated DAO helper only. No native owner or live-lifetime evidence is supplied.
            requireMasterLocalDisplayIntent(dao, scope, OperationId(selector.operationId), selector.ordinal,
                action.expectedBody.bytes, checkActive = {})
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: MasterLocalDisplayDeliveryUnavailable) {
            throw AssertionError("Ordinary source unavailability is not frozen integrity failure", unavailable)
        } catch (failure: IllegalArgumentException) {
            return PointObservation.IntegrityFailure(failure)
        } catch (failure: IllegalStateException) {
            return PointObservation.IntegrityFailure(failure)
        }
        // The production helper returns Unit. This separate observation reads the actual
        // validated DAO row; it never manufactures output by returning the supplied body.
        val row = checkNotNull(dao.outboxIntent(selector.scope, selector.operationId, selector.ordinal)) {
            "Successful point validation did not retain its exact DAO row"
        }
        return PointObservation.Bytes(StoredBytes(row.body))
    }
}

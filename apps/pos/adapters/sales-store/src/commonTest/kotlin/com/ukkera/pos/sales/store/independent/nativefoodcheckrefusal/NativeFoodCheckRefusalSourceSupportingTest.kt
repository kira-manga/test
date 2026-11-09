@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.nativefoodcheckrefusal

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** NRFP-v1 bounded request and missing-source/activity controls only. No present ORDER,
 * native original, claim owner proof, retained history or authorized writer is constructed. */
class NativeFoodCheckRefusalSourceSupportingTest {
    private val scope = BranchScope(id(1), id(2), id(3))
    private val actor = id(4)
    private val order = id(5)
    private val operation = OperationId(id(6).value)
    private val draft = LocalFoodStockRequestV2.ActivityProtected(LocalFoodDraftCreateV1(
        order, LocalSalesActivity.RESTAURANT, LocalFoodServiceV1(LocalSalesChannel.COUNTER, "Synthetic counter")))
    private val addBody = LocalFoodRequestV1.Add(order, Revision(3), Revision(2), listOf(
        LocalFoodPortionSelectionV1(id(7), id(8), id(9), Revision(1), id(10), emptyList(), Quantity(id(11), 1, 0))))
    private val add = LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(addBody)
    private val historical = MissingHistoricalActivity()
    private val support = LocalFoodCheckRefusalSupport(historical)
    private val enabled = PreparedFoodActivityAdmission(true, emptySet())

    @Test
    fun nrfpS001S002CanonicalRequestDataKeepsDraft2AndQuotedAdd3Distinct(): Unit = runTest {
        for (request in listOf(draft, add)) {
            val bytes = LocalFoodStockCodecV2.encode(request)
            assertEquals(request, LocalFoodStockCodecV2.decode(bytes))
            assertEquals(bytes, LocalFoodStockCodecV2.normalize(bytes))
        }
        assertEquals(order, draft.request.orderId)
        assertEquals(order, add.request.orderId)
        assertEquals(Revision(3), add.request.expectedOrderRevision)
        // Canonical request DATA does not prove the scalar present-source refusal predicate.
    }

    @Test
    fun nrfpS005AbsentDraftOrAddSourceDoesNotProduceClosedRefusal(): Unit = runTest {
        for (request in listOf(draft, add)) {
            val view = MissingOrderView()
            assertNull(support.prepare(request, view, context(request), enabled))
            assertEquals(1, view.aggregateReads)
            assertEquals(1, view.claimReads)
            assertEquals(0, view.retainedReads)
            assertEquals(0, historical.calls)
        }
    }

    @Test
    fun nrfpS005DanglingSourceClaimIsIntegrityFailureNotAcceptingAbsence(): Unit = runTest {
        for (request in listOf(draft, add)) {
            val view = MissingOrderView(danglingClaim = true)
            assertFailsWith<IllegalStateException> { support.prepare(request, view, context(request), enabled) }
            assertEquals(1, view.aggregateReads)
            assertEquals(1, view.claimReads)
            assertEquals(0, view.retainedReads)
        }
        // This existing source-integrity failure is not a universal outside-request code.
    }

    @Test
    fun nrfpS006MissingOrDisabledActivityCannotWaiveRequiredSourceGates(): Unit = runTest {
        for (request in listOf(draft, add)) {
            for (activity in listOf(null, PreparedFoodActivityAdmission(false, emptySet()))) {
                val view = MissingOrderView()
                assertNull(support.prepare(request, view, context(request), activity))
                assertEquals(0, view.aggregateReads)
                assertEquals(0, view.claimReads)
                assertEquals(0, view.retainedReads)
                assertEquals(0, historical.calls)
            }
        }
        // The positive no-unrelated-stock/recipe path still needs actual source/native gates.
    }

    @Test
    fun nrfpS005OtherFoodBodyFamiliesRemainOutsideClosedQualifier(): Unit = runTest {
        val outside = listOf(LocalFoodStockRequestV2.CreateDraft(draft.request),
            LocalFoodStockRequestV2.ActivityProtectedAdd(addBody),
            LocalFoodStockRequestV2.Preparation(LocalFoodRequestV1.Send(order, Revision(3), listOf(id(7)))))
        for (request in outside) {
            val view = MissingOrderView()
            // Only the closed support selector is exercised, not the outside handler outcome.
            assertNull(support.prepare(request, view, context(request), enabled))
            assertEquals(0, view.aggregateReads)
            assertEquals(0, view.claimReads)
            assertEquals(0, view.retainedReads)
        }
    }

    @Test
    fun nrfpS005WrongOuterCommandCannotSupplyFoodPreparationContext(): Unit = runTest {
        val view = MissingOrderView()
        val food = context(draft)
        val other = CommandContext(scope, actor, operation, StockStreamingCommandsV2.STOCK, food.semanticDigest)
        assertFailsWith<IllegalStateException> { support.prepare(draft, view, other, enabled) }
        assertEquals(0, view.aggregateReads)
        assertEquals(0, view.claimReads)
        assertEquals(0, view.retainedReads)
    }

    @Test
    fun nrfpS005CancelledSourceReadDoesNotBecomeClosedRefusalOrEmptyCut(): Unit = runTest {
        for (request in listOf(draft, add)) {
            val cancelled = CancellationException("Synthetic unavailable source")
            val view = MissingOrderView(aggregateFailure = cancelled)
            val observed = assertFailsWith<CancellationException> {
                support.prepare(request, view, context(request), enabled)
            }
            assertEquals(cancelled, observed)
            assertEquals(1, view.aggregateReads)
            assertEquals(0, view.claimReads)
            assertEquals(0, view.retainedReads)
        }
    }

    @Test
    fun nrfpS005MalformedCanonicalRequestIsNotRelabeledAsClosedFoodRequest() {
        for (bytes in listOf(StoredBytes("{}".encodeToByteArray()), StoredBytes(byteArrayOf()))) {
            assertFails { LocalFoodStockCodecV2.normalize(bytes) }
        }
    }

    private fun context(request: LocalFoodStockRequestV2): CommandContext = CommandContext(scope, actor, operation,
        StockStreamingCommandsV2.FOOD, semanticDigest(scope, actor, StockStreamingCommandsV2.FOOD,
            LocalFoodStockCodecV2.encode(request)))

    private inner class MissingOrderView(private val danglingClaim: Boolean = false,
        private val aggregateFailure: CancellationException? = null) : CommandReadView {
        override val scope: BranchScope get() = this@NativeFoodCheckRefusalSourceSupportingTest.scope
        var aggregateReads: Int = 0
        var claimReads: Int = 0
        var retainedReads: Int = 0
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
            aggregateReads++
            assertEquals(LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, order), key)
            aggregateFailure?.let { throw it }
            return null
        }
        override suspend fun claim(key: ClaimKey): DomainId? {
            claimReads++
            assertEquals(LocalFoodProfileV1.claim(LocalFoodProfileV1.ORDER, order), key)
            return if (danglingClaim) order else null
        }
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
            retainedReads++
            error("No retained original or native proof is bound")
        }
    }

    private class MissingHistoricalActivity : LocalFoodCheckHistoricalActivitySource {
        var calls: Int = 0
        override suspend fun before(command: RuntimeHistoricalCommand,
            activity: LocalSalesActivity): PreparedFoodActivityAdmission? {
            calls++
            return null
        }
        override suspend fun before(sources: RuntimeHistoricalFoodCheckRejectedSources,
            activity: LocalSalesActivity): PreparedFoodActivityAdmission? {
            calls++
            return null
        }
    }

    private fun id(number: Int): DomainId =
        DomainId("a6110401-0000-4000-8000-${number.toString().padStart(12, '0')}")
}

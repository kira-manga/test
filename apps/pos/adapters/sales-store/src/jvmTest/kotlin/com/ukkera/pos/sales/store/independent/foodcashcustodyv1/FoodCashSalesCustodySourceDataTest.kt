package com.ukkera.pos.sales.store.independent.foodcashcustodyv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.InventoryAllocationRecord
import com.ukkera.pos.inventory.store.LocalStockPaymentCustody
import com.ukkera.pos.inventory.store.LocalStockPaymentObservations
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Two supporting DATA checks, not full Start or any whole FCC case execution.
 * FCC-R-02's original instruction-held READY/SERVED and FCC-C-01's original
 * NOT_SENT states call actual FoodPaymentSources.collection/finish,
 * foodPaymentInitialPhysicalCustody and requireFoodPaymentCustody. The real
 * LocalStockPaymentObservations.paymentCustody supplies both typed stock cuts.
 * FCC-R-01/R-02 share the frozen already-issued ingredient quantity/value basis.
 *
 * The required second portion's literal absence and recipe/allocation mismatch
 * bind only supporting relationships from FCC-SRC-01 (Restaurant) / FCC-SRC-02
 * (Restaurant and Café). Candidate decoding/cut setup is outside refusal asserts.
 * Missing sources cannot yield a cut; a decoder-valid mismatch reaches the
 * actual sales membership check. Restoring means reusing the exact untouched
 * original DATA, never repairing quantities, replacing it with current history
 * or rebuilding an approximate subtotal. Every original FOOD, cash and stock
 * field, canonical source frame, input and current-row payload is compared.
 *
 * Untested: native first producer/retained originals, historical/current-owner
 * traversal/EOF, native source-failure qualification, V3/RPC/paging/fallback,
 * current employee/session/device/activity/kitchen authority, protected Start
 * journal/instruction disclosure, CASH I/O/A and financial/stock effects, native
 * timestamp capture-once/clock/order/date proof, retry or owner close/reopen,
 * platform/UI/receipt/audit/outbox behavior. DATA immutability is not durable
 * no-effects proof. All those frozen whole cases and timestamp v1.0.1 remain
 * NOT RUN; FOOD syntax and decoder acceptance confer no native authority.
 */
class FoodCashSalesCustodySourceDataTest {
    @Test
    fun restaurantActualSalesCustodyAcceptsThenLiteralSourcesRefuseAndRestoreData() = runTest {
        assertSourceBinding("restaurant")
    }

    @Test
    fun cafeActualSalesCustodyAcceptsThenLiteralSourcesRefuseAndRestoreData() = runTest {
        assertSourceBinding("cafe")
    }

    private suspend fun assertSourceBinding(mode: String) {
        val data = FoodCashSalesCustodySourceData(mode)
        val original = data.original
        val faults = if (data.frozen.issued) "FCC-SRC-01/02 literal DATA" else "FCC-SRC-02 literal DATA"
        val before = FoodCashSalesSourceSnapshot(original)
        suspend fun restored() {
            before.assertUnchanged(original, "$faults: preserve entire original before restoration")
            assertAccepted(data, original)
            before.assertUnchanged(original, "$faults: exact original remains unchanged after restoration")
        }
        restored()

        // FCC-SRC-01/02: absent required portion DATA, not a native original-selector fault.
        val second = AggregateKey(LocalFoodProfileV1.PORTION, data.frozen.portionIds[1])
        val missing = data.missingSecondPortion()
        assertNull(missing.observed.current(second), "$faults: required second portion is absent")
        assertEquals(original.inputs, missing.inputs, "$faults: no omission of required portion from the input census")
        assertEquals(original.observed.sources.filterNot { it is StreamedSourceObservationV2.Current && it.key == second },
            missing.observed.sources, "$faults: only the controlled current DATA point is omitted")
        val missingBefore = FoodCashSalesSourceSnapshot(missing)
        val missingReader = FoodPaymentSources(missing.observed)
        before.assertUnchanged(original, "$faults: preserve entire original before the missing-source refusal")
        val absence = assertFailsWith<IllegalStateException>("$faults: actual sales-source lookup must refuse") {
            missingReader.collection(missing.inputs, missing.cashInput)
        }
        assertEquals("food.payment.source_missing", absence.message, "$faults: refusal is the actual required-source lookup")
        missingBefore.assertUnchanged(missing, "$faults: missing-source interpreter leaves all supplied DATA inert")
        restored()

        // FCC-SRC-01/02: decoder-valid original stock/recipe relationship mismatch DATA.
        val mismatched = data.mismatchedSecondPortionIngredient()
        val old = checkNotNull(original.observed.current(second)); val changed = checkNotNull(mismatched.observed.current(second))
        assertEquals(old.key, changed.key, "$faults: the mismatch is not a rekey")
        assertEquals(old.generation, changed.generation, "$faults: the syntax generation is retained")
        assertEquals(old.schema, changed.schema, "$faults: no lower-version fallback")
        assertNotEquals(old.payloadHash, changed.payloadHash, "$faults: the controlled content mismatch has its actual new digest")
        assertEquals(original.observed.sources.filterNot { it is StreamedSourceObservationV2.Current && it.key == second },
            mismatched.observed.sources.filterNot { it is StreamedSourceObservationV2.Current && it.key == second },
            "$faults: every other FOOD/cash/stock source remains the exact original")
        assertEquals(original.inputs.filterNot { it.key() == second }, mismatched.inputs.filterNot { it.key() == second },
            "$faults: all other original inputs are preserved")
        assertEquals(original.cashInput, mismatched.cashInput, "$faults: the original cash snapshot is not changed")
        val mismatchedBefore = FoodCashSalesSourceSnapshot(mismatched)
        val mismatchCut = readSalesSources(mismatched) // Decoder/source/quote setup must succeed outside the refusal assertion.
        val mismatchStock = selectStock(mismatched, mismatchCut) // Real inventory DATA decoding must also succeed first.
        assertTrue(foodPaymentInitialPhysicalCustody(mismatchCut.collection, mismatchCut.order, mismatchCut.portions, mismatchCut.cash),
            "$faults: the unchanged original physical state passes before the membership refusal")
        before.assertUnchanged(original, "$faults: preserve entire original before the relationship refusal")
        val relationship = assertFailsWith<IllegalStateException>("$faults: actual FOOD recipe/allocation join must refuse") {
            requireFoodPaymentCustody(mismatchCut, mismatchStock)
        }
        assertEquals("food.stock.unrequested_ingredient", relationship.message,
            "$faults: refusal comes from the actual recipe membership check, not fixture decoding")
        mismatchedBefore.assertUnchanged(mismatched, "$faults: relationship refusal does not repair or mutate DATA")
        restored()
    }

    private suspend fun assertAccepted(data: FoodCashSalesCustodySourceData, sources: FoodCashSalesCustodySources) {
        val case = if (data.frozen.issued) "FCC-R-02 original instruction-held DATA" else "FCC-C-01 original unsent collection DATA"
        val cut = readSalesSources(sources)
        val stock = selectStock(sources, cut)
        assertTrue(foodPaymentInitialPhysicalCustody(cut.collection, cut.order, cut.portions, cut.cash), "$case: initial physical custody")
        requireFoodPaymentCustody(cut, stock) // Actual acceptance, not a replacement predicate or interpreter.
        assertEquals(data.frozen.portionIds, cut.portions.map { it.id }, "$case: complete original portions")
        assertEquals(data.historicalQuote, cut.collection.quote.payable, "$case: frozen quote stays historical")
        assertEquals(data.zero, cut.collection.collected, "$case: no applied coverage in the instruction-held DATA")
        assertEquals(data.drawerLedger, cut.cash.expectedCash, "$case: frozen pre-application drawer DATA")
        assertEquals(data.zero, cut.cash.totals.received, "$case: no received ledger DATA")
        assertEquals(data.zero, cut.cash.totals.change, "$case: no change ledger DATA")
        assertNull(cut.collection.saleId, "$case: no sale in this original instruction-held DATA")
        assertNull(cut.collection.cancellationRefundId, "$case: no cancellation/refund in the original DATA")
        val allocations: List<InventoryAllocationRecord>
        if (data.frozen.issued) {
            assertTrue(stock.reserved.isEmpty(), "$case: already-issued ingredients are not reserved anew")
            assertEquals(cut.issuedReservationIds.toSet(), stock.issued.keys, "$case: complete typed issued union")
            assertEquals(data.frozen.completePortionCount, stock.issued.size, "$case: per-portion DATA maps, not native issue-set count")
            assertTrue(cut.portions.all { (it.body as FoodBodyV1.Portion).preparation.let { state ->
                state.status == FoodPreparationStatus.READY && state.fulfillment == FoodFulfillmentStatus.SERVED
            } }, "$case: both original portions remain READY/SERVED")
            allocations = stock.issued.values.flatMap { it.allocations }
            for (ingredient in data.frozen.ingredients) assertEquals(ingredient.expectedIssuedValue,
                stock.issued.values.flatMap { it.slices }.filter { it.allocation.position.pool.skuId == ingredient.skuId }
                    .fold(data.zero) { sum, slice -> sum + slice.originalValue },
                "FCC-R-01/R-02 frozen ingredient custody DATA: exact original issued value")
            assertEquals(data.frozen.originalTotalValue, stock.issued.values.fold(data.zero) { sum, value -> sum + value.issue.originalValue },
                "FCC-R-01/R-02 frozen ingredient custody DATA: 6,400 minor original value, not new issues")
        } else {
            assertTrue(stock.issued.isEmpty(), "$case: no unsent ingredient issue")
            assertEquals(cut.reservedReservationIds.toSet(), stock.reserved.keys, "$case: complete typed reserved union")
            assertEquals(data.frozen.completePortionCount, stock.reserved.size, "$case: both original portions")
            assertTrue(cut.portions.all { (it.body as FoodBodyV1.Portion).let { portion ->
                portion.preparation.status == FoodPreparationStatus.NOT_SENT && portion.preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER &&
                    portion.preparation.tasks.isEmpty() && portion.preparation.issueId == null && portion.normalizedIssue == null
            } }, "$case: NOT_SENT/NOT_HANDED_OVER with no issue or kitchen task DATA")
            allocations = stock.reserved.values.flatMap { it.allocations }
        }
        for (ingredient in data.frozen.ingredients) assertEquals(ingredient.expectedCustody,
            allocations.filter { it.allocation.position.pool.skuId == ingredient.skuId }
                .fold(ingredient.perPortion.copy(scaled = 0)) { sum, member -> sum + member.allocation.quantity },
            "$case: exact frozen complete ingredient quantity")
    }

    private fun readSalesSources(sources: FoodCashSalesCustodySources): FoodPaymentLiteralCut {
        val reader = FoodPaymentSources(sources.observed)
        val cut = reader.collection(sources.inputs, sources.cashInput)
        reader.finish(stock = true)
        return cut
    }

    private fun selectStock(sources: FoodCashSalesCustodySources, cut: FoodPaymentLiteralCut): LocalStockPaymentCustody =
        LocalStockPaymentObservations.paymentCustody(sources.observed, cut.reservedReservationIds, cut.issuedReservationIds)
}

/** Full original DATA comparison, never a convenient amount-only or stock-only subtotal. */
private class FoodCashSalesSourceSnapshot(sources: FoodCashSalesCustodySources) {
    private val scope = sources.observed.scope
    private val inputs = sources.inputs.toList()
    private val inputBytes = inputs.map { localSalesEncode(it, FoodFinancialInputV1.serializer()).copyBytes() }
    private val cash = sources.cashInput
    private val cashBytes = localSalesEncode(cash, FoodFinancialCashInputV1.serializer()).copyBytes()
    private val fields = sources.observed.sources
    private val frames = fields.map { sources.observed.copySourceFrameBytes(it) }
    private val payloads = fields.filterIsInstance<StreamedSourceObservationV2.Current>().map { it.payload.copyBytes() }

    fun assertUnchanged(sources: FoodCashSalesCustodySources, case: String) {
        assertEquals(scope, sources.observed.scope, case)
        assertEquals(inputs, sources.inputs, case)
        inputs.indices.forEach { assertContentEquals(inputBytes[it], localSalesEncode(sources.inputs[it], FoodFinancialInputV1.serializer()).copyBytes(), case) }
        assertEquals(cash, sources.cashInput, case)
        assertContentEquals(cashBytes, localSalesEncode(sources.cashInput, FoodFinancialCashInputV1.serializer()).copyBytes(), case)
        assertEquals(fields, sources.observed.sources, case)
        fields.indices.forEach { assertContentEquals(frames[it], sources.observed.copySourceFrameBytes(sources.observed.sources[it]), case) }
        val current = sources.observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>()
        assertEquals(payloads.size, current.size, case)
        current.indices.forEach { assertContentEquals(payloads[it], current[it].payload.copyBytes(), case) }
    }
}

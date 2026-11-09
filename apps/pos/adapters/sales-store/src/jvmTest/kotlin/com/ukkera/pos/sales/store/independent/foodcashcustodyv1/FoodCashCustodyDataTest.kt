package com.ukkera.pos.sales.store.independent.foodcashcustodyv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.InventoryAllocationRecord
import com.ukkera.pos.inventory.KitchenDisposition
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.inventory.store.LocalStockPaymentCustody
import com.ukkera.pos.inventory.store.LocalStockPaymentObservations
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.inventory.store.LocalStockRelationalCodecV2
import com.ukkera.pos.inventory.store.LocalStockRelationalProfileV2
import com.ukkera.pos.types.*
import kotlin.test.*

/**
 * Four supporting DATA bindings of frozen food-cash-cold-recovery-v1 v1.0.0:
 * FCC-R-01/R-02's already-issued two-portion stock, FCC-C-01's unsent-reserved
 * two-portion stock, and the literal allocation-source faults of FCC-SRC-01/02.
 * Only the real paymentCustody interpreter is called; no replacement interpreter.
 *
 * The separately versioned timestamp-binding v1.0.1 changes no custody quantities
 * or states. These tests bind no native first-phase times and supply no clock.
 * Canonical rows/claims/complete finite ranges are explicitly non-native DATA;
 * decoder validity is not native source custody, producer history or authority.
 * Source faults do not qualify either frozen native source-failure layer.
 *
 * No requireIssuedOriginal, actual writer/owner, protected session, CASH I/O/A,
 * financial effect, paid expiry protection, current on-hand effect, native reopen,
 * admitted RPC, kitchen task or device/platform result is exercised. The Restaurant
 * helper's per-portion issue-map size is not the frozen native issue-set count.
 * Source-byte retention proves only that this inert interpreter does not mutate
 * its supplied DATA. Full FCC cases/scenarios remain NOT RUN, not supporting PASS.
 */
class FoodCashCustodyDataTest {
    @Test
    fun restaurantWholeTwoPortionsRemainIssuedCustodyData() {
        val data = FoodCashCustodyData("restaurant")
        val before = FoodCashSourceSnapshot(data.observed)
        assertRestaurant(data, custody(data, data.observed))
        before.assertUnchanged(data.observed)
    }

    @Test
    fun cafeWholeTwoUnsentPortionsRemainReservedCustodyData() {
        val data = FoodCashCustodyData("cafe")
        val before = FoodCashSourceSnapshot(data.observed)
        assertCafe(data, custody(data, data.observed))
        before.assertUnchanged(data.observed)
    }

    @Test
    fun restaurantRequiredAllocationSourceRefusesThenExactOriginalRestoresData() {
        assertRefusalAndRestoration(FoodCashCustodyData("restaurant"))
    }

    @Test
    fun cafeRequiredAllocationSourceRefusesThenExactOriginalRestoresData() {
        assertRefusalAndRestoration(FoodCashCustodyData("cafe"))
    }

    private fun custody(data: FoodCashCustodyData, observed: StockReadObservations): LocalStockPaymentCustody {
        val ids = data.portions.map { it.reservationId }
        return LocalStockPaymentObservations.paymentCustody(observed,
            if (data.frozen.issued) emptyList() else ids, if (data.frozen.issued) ids else emptyList())
    }

    private fun assertRestaurant(data: FoodCashCustodyData, result: LocalStockPaymentCustody) {
        assertTrue(result.reserved.isEmpty())
        assertEquals(data.portions.map { it.reservationId }.toSet(), result.issued.keys)
        assertEquals(data.frozen.completePortionCount, result.issued.size) // One DATA reservation per portion, not native issue sets.
        for (portion in data.portions) {
            val cut = result.issued.getValue(portion.reservationId)
            assertEquals(portion.reservationId, cut.header.id)
            assertEquals(ReservationStatus.ISSUED, cut.header.status)
            assertFalse(cut.header.retainsStockClaim)
            assertEquals(0L, cut.header.holdCount)
            assertEquals(portion.issueId, cut.header.issueId)
            assertEquals(portion.issueId, cut.issue.id)
            assertEquals(portion.reservationId, cut.issue.reservationId)
            assertEquals(cut.header.binding, cut.issue.binding)
            assertEquals(data.frozen.orderId, cut.header.binding.orderId)
            assertEquals(data.frozen.actorId, cut.header.binding.originalActorId)
            assertEquals(ValueIssuePurpose.DIRECT_RECIPE, cut.issue.purpose)
            assertEquals(KitchenDisposition.ACTIVE, cut.issue.disposition)
            assertEquals(portion.producer, cut.firstIssueProducer) // Literal DATA tuple, never an authenticated original.
            assertAllocations(data, portion, cut.allocations)
            assertEquals(cut.header.allocationCount, cut.slices.size.toLong())
            assertEquals(portion.allocations.toSet(), cut.slices.map { it.allocation }.toSet())
            for (ingredient in data.frozen.ingredients) {
                val slice = cut.slices.single { it.allocation.position.pool.skuId == ingredient.skuId }
                assertEquals(portion.issueId, slice.issueId)
                assertEquals(ingredient.perPortion, slice.allocation.quantity)
                assertEquals(ingredient.portionValue, slice.originalValue)
                assertEquals(ingredient.perPortion.copy(scaled = 0), slice.restoredQuantity)
            }
            assertEquals(cut.issue.originalValue, cut.slices.fold(Money(data.frozen.currency, 0)) { sum, slice -> sum + slice.originalValue })
        }
        val allocations = result.issued.values.flatMap { it.allocations }
        val slices = result.issued.values.flatMap { it.slices }
        for (ingredient in data.frozen.ingredients) {
            assertEquals(ingredient.expectedCustody, quantityTotal(allocations, ingredient)) // Frozen 2 bread / 300 g meat.
            assertEquals(ingredient.expectedIssuedValue, slices.filter { it.allocation.position.pool.skuId == ingredient.skuId }
                .fold(Money(data.frozen.currency, 0)) { sum, slice -> sum + slice.originalValue }) // Frozen 400 / 6,000 minor.
        }
        assertEquals(data.frozen.originalTotalValue, result.issued.values.fold(Money(data.frozen.currency, 0)) {
            sum, cut -> sum + cut.issue.originalValue }) // Frozen 6,400 minor, never a newly computed oracle.
        assertEquals(data.frozen.portionIds.toSet(), allocations.map { it.allocation.lineId }.toSet())
        assertTrue(data.observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>().none {
            it.key.kind == StockRelationalKindsV2.POSITION || it.key.kind == StockRelationalKindsV2.ITEM })
    }

    private fun assertCafe(data: FoodCashCustodyData, result: LocalStockPaymentCustody) {
        assertTrue(result.issued.isEmpty())
        assertEquals(data.portions.map { it.reservationId }.toSet(), result.reserved.keys)
        assertEquals(data.frozen.completePortionCount, result.reserved.size)
        for (portion in data.portions) {
            val cut = result.reserved.getValue(portion.reservationId)
            assertEquals(portion.reservationId, cut.header.id)
            assertEquals(ReservationStatus.RESERVED, cut.header.status)
            assertTrue(cut.header.retainsStockClaim)
            assertEquals(0L, cut.header.holdCount)
            assertNull(cut.header.issueId)
            assertEquals(data.frozen.orderId, cut.header.binding.orderId)
            assertEquals(data.frozen.actorId, cut.header.binding.originalActorId)
            assertAllocations(data, portion, cut.allocations)
            assertEquals(data.frozen.ingredients.map { it.skuId }.toSet(), cut.items.map { it.skuId }.toSet())
            assertTrue(cut.items.all { it.storageRevision == Revision(1) }) // Syntax revision, not native history.
        }
        val allocations = result.reserved.values.flatMap { it.allocations }
        for (ingredient in data.frozen.ingredients) {
            assertEquals(ingredient.expectedCustody, quantityTotal(allocations, ingredient)) // Frozen 36 g beans / 400 ml milk.
        }
        assertEquals(data.frozen.portionIds.toSet(), allocations.map { it.allocation.lineId }.toSet())
        assertTrue(data.observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>().none {
            it.key.kind == StockRelationalKindsV2.ISSUE || it.key.kind == StockRelationalKindsV2.ISSUE_SLICE ||
                it.key.kind == StockRelationalKindsV2.VALUE_EVENT || it.key.kind == StockRelationalKindsV2.VALUE_SLICE })
    }

    private fun assertAllocations(data: FoodCashCustodyData, portion: FoodCashCustodyPortion, allocations: List<InventoryAllocationRecord>) {
        assertEquals(data.frozen.ingredients.size, allocations.size)
        assertTrue(allocations.all { it.reservationId == portion.reservationId && it.allocation.lineId == portion.portionId })
        assertEquals(portion.allocations.toSet(), allocations.map { it.allocation }.toSet())
        for (ingredient in data.frozen.ingredients) assertEquals(ingredient.perPortion,
            allocations.single { it.allocation.position.pool.skuId == ingredient.skuId }.allocation.quantity)
    }

    private fun quantityTotal(allocations: List<InventoryAllocationRecord>, ingredient: FoodCashCustodyIngredient): Quantity =
        allocations.filter { it.allocation.position.pool.skuId == ingredient.skuId }
            .fold(ingredient.perPortion.copy(scaled = 0)) { total, row -> total + row.allocation.quantity }

    /** Literal missing original allocation / stock relationship mismatch only (FCC-SRC-01/02).
     * Candidates are constructed/decoded outside assertFailsWith; setup rejection cannot pass. */
    private fun assertRefusalAndRestoration(data: FoodCashCustodyData) {
        val original = data.observed
        val before = FoodCashSourceSnapshot(original)
        fun restored() {
            before.assertUnchanged(original)
            val result = custody(data, original)
            if (data.frozen.issued) assertRestaurant(data, result) else assertCafe(data, result)
            before.assertUnchanged(original)
        }
        restored()
        val target = LocalStockRelationalProfileV2.allocationKey(data.portions[1].allocations.first().allocationId)
        val missing = data.missingAllocationSource(target)
        assertNull(missing.current(target))
        assertEquals(original.sources.filterNot { it is StreamedSourceObservationV2.Current && it.key == target }, missing.sources)
        val missingBefore = FoodCashSourceSnapshot(missing)
        assertFailsWith<LocalStockRejected> { custody(data, missing) }
        missingBefore.assertUnchanged(missing)
        restored()

        val otherReservation = data.portions[0].reservationId
        val mismatched = data.mismatchedAllocationSource(target, otherReservation)
        val changed = checkNotNull(mismatched.current(target))
        val old = checkNotNull(original.current(target))
        assertEquals(old.key, changed.key); assertEquals(old.generation, changed.generation); assertEquals(old.schema, changed.schema)
        assertNotEquals(old.payloadHash, changed.payloadHash)
        assertEquals(original.sources.filterIsInstance<StreamedSourceObservationV2.Claim>(),
            mismatched.sources.filterIsInstance<StreamedSourceObservationV2.Claim>())
        val originalMembers = original.sources.filterIsInstance<StreamedSourceObservationV2.StockMember>()
        val candidateMembers = mismatched.sources.filterIsInstance<StreamedSourceObservationV2.StockMember>()
        assertEquals(originalMembers.map { it.query to it.record.identity.key }, candidateMembers.map { it.query to it.record.identity.key })
        val projected = LocalStockRelationalCodecV2.project(mismatched.scope,
            StoredAggregate(changed.key, changed.generation, changed.schema, changed.payload, changed.payloadHash))
        assertEquals(otherReservation, (projected as StockIndexRecordV2.Allocation).reservationId)
        assertTrue(candidateMembers.filter { it.record.identity.key == target }.all { it.record == projected })
        for (terminal in mismatched.sources.filterIsInstance<StreamedSourceObservationV2.StockRange>()) {
            val checked = mutableListOf<StockIndexRecordV2>()
            mismatched.range(terminal.query) { checked += it } // Validate the new chain before the custody refusal.
            assertEquals(candidateMembers.filter { it.query == terminal.query }.map { it.record }, checked)
        }
        val mismatchedBefore = FoodCashSourceSnapshot(mismatched)
        assertFailsWith<LocalStockRejected> { custody(data, mismatched) }
        mismatchedBefore.assertUnchanged(mismatched)
        restored()
    }
}

/** Separate retained source fields, every canonical frame and every row payload byte. */
private class FoodCashSourceSnapshot(observed: StockReadObservations) {
    private val scope = observed.scope
    private val fields = observed.sources
    private val frames = fields.map { observed.copySourceFrameBytes(it) }
    private val payloads = fields.filterIsInstance<StreamedSourceObservationV2.Current>().map { it.payload.copyBytes() }

    fun assertUnchanged(observed: StockReadObservations) {
        assertEquals(scope, observed.scope)
        assertEquals(fields, observed.sources)
        fields.indices.forEach { assertContentEquals(frames[it], observed.copySourceFrameBytes(observed.sources[it])) }
        val current = observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>()
        assertEquals(payloads.size, current.size)
        current.indices.forEach { assertContentEquals(payloads[it], current[it].payload.copyBytes()) }
    }
}

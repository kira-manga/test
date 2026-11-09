package com.ukkera.pos.inventory.store.independent.returnstockdisposition

import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.ExactRational
import kotlin.test.*

/** Supporting CORE arithmetic/guard slices of frozen RSD-002/003, not whole-case
 * passes. Pure projections and scalar issue data are not native source/history,
 * actual current acceptance/authority, durable inventory or fresh ledger evidence. */
class ReturnStockDispositionFrozenInventorySlicesTest {
    private val inputs get() = FrozenCoreReturnStockInventoryInputs

    @Test
    fun rsd002_rsd003_frozenBaselineRetainsEarlierOriginalCostAndLaterAcquisition() {
        val data = FrozenCoreReturnStockInventoryData()
        val opening = inputs.fact("owned opening inventory")
        val issued = inputs.fact("original sale issue")
        val earlier = inputs.fact("earlier inspected-sellable return")
        val acquired = inputs.fact("later acquisition")
        assertPool(data.openingPool, opening.literal("quantity"), opening.literal("value"))
        assertEquals(inputs.quantity(issued.literal("quantity_delta")), data.issue.quantity)
        assertEquals(inputs.money(issued.literal("value_delta")), data.issue.value)
        assertPool(data.issue.after, issued.literal("remaining_quantity"), issued.literal("remaining_value"))
        assertEquals(inputs.quantity(inputs.originalSale.literal("quantity")), data.beforeSlice.allocation.quantity)
        assertEquals(inputs.money(inputs.originalSale.literal("immutable_issued_cost")), data.beforeSlice.originalValue)
        assertEquals(data.earlierQuantity, data.beforeSlice.restoredQuantity)
        assertEquals(inputs.money(earlier.literal("original_value_delta")), data.earlierValue)
        assertEquals(inputs.quantity(acquired.literal("quantity_delta")), data.acquisition.quantity)
        assertEquals(inputs.money(acquired.literal("value_delta")), data.acquisition.value)
        assertPool(data.beforePool, inputs.before.literal("sellable_pool_quantity"), inputs.before.literal("sellable_pool_value"))
        assertExactAverage(data.beforePool, inputs.before.literal("pool_average"))
        // This is an arithmetic sequence, not a verified historical source join.
    }

    @Test
    fun rsd002_twoAcceptedCoreUnitsUseOriginalFortyAndProducePoolFourOneTwenty() {
        val data = FrozenCoreReturnStockInventoryData()
        val originalBefore = data.beforeSlice.copy()
        val poolBefore = data.beforePool.copy()
        val request = data.request()
        val selection = request.selections.single()
        requireStockRetailReturnSelection(request, selection, data.beforeSlice)
        val destination = stockRetailReturnDestination(selection, data.beforePosition, data.beforePosition, data.now)
        val value = InventoryValuationProjection.originalRestorationValue(data.beforeSlice, selection.quantity)
        val delta = InventoryValuationProjection.receive(data.beforePool, selection.quantity, value)
        val position = stockRestorationPosition(data.beforePosition, data.beforePosition, destination, selection.quantity, data.now)

        assertEquals(inputs.quantity(inputs.complete.literal("quantity_delta")), delta.quantity)
        assertEquals(inputs.money(inputs.complete.literal("original_value_delta")), value)
        assertEquals(value, delta.value)
        assertPool(delta.after, inputs.complete.literal("quantity"), inputs.complete.literal("value"))
        assertExactAverage(delta.after, inputs.complete.literal("pool_average"))
        assertEquals(data.poolKey, delta.after.key)
        assertEquals(data.positionKey, position.key)
        assertEquals(delta.after.onHand, position.onHand)
        assertEquals(StockSafety.CLEAR, position.safety)
        assertEquals(inputs.money(inputs.complete.literal("cumulative_disposed_original_cost")), data.earlierValue + value)
        assertEquals(inputs.money(inputs.complete.literal("original_unreturned_cost")), originalBefore.originalValue - data.earlierValue - value)
        assertEquals(inputs.money(inputs.complete.literal("remaining_disposition_original_cost")), data.acceptedCost - value)
        assertEquals(originalBefore, data.beforeSlice)
        assertEquals(poolBefore, data.beforePool)
        // Current average 40.00 or later acquisition cost 60.00 never supplies value.
        // Position availability/holds, accepted-source frontier, money, immutable
        // result/audit/outbox and actual native cost lineage remain UNBOUND.
    }

    @Test
    fun rsd003a_splitOneThenRemainingOneRetainsTwentyPerOriginalUnit() {
        val data = FrozenCoreReturnStockInventoryData()
        val one = inputs.quantity("1") // Exact frozen RSD-003(a) action.
        val firstRequest = data.request(one)
        val firstSelection = firstRequest.selections.single()
        requireStockRetailReturnSelection(firstRequest, firstSelection, data.beforeSlice)
        val firstDestination = stockRetailReturnDestination(firstSelection, data.beforePosition, data.beforePosition, data.now)
        val firstValue = InventoryValuationProjection.originalRestorationValue(data.beforeSlice, one)
        val first = InventoryValuationProjection.receive(data.beforePool, one, firstValue)
        val firstPosition = stockRestorationPosition(data.beforePosition, data.beforePosition, firstDestination, one, data.now)
        val firstSlice = data.beforeSlice.copy(restoredQuantity = data.beforeSlice.restoredQuantity + one)
        val firstPoolSnapshot = first.after.copy()
        val firstSliceSnapshot = firstSlice.copy()

        assertEquals(inputs.money(inputs.originalSale.literal("issued_unit_cost")), firstValue)
        assertPool(first.after, inputs.partial.literal("quantity"), inputs.partial.literal("value"))
        assertEquals(first.after.onHand, firstPosition.onHand)
        assertEquals(inputs.money(inputs.partial.literal("remaining_disposition_original_cost")), data.acceptedCost - firstValue)

        val secondRequest = data.request(one, postingNumber = 2)
        val secondSelection = secondRequest.selections.single()
        requireStockRetailReturnSelection(secondRequest, secondSelection, firstSlice)
        val secondDestination = stockRetailReturnDestination(secondSelection, firstPosition, firstPosition, data.now)
        val secondValue = InventoryValuationProjection.originalRestorationValue(firstSlice, one)
        val second = InventoryValuationProjection.receive(first.after, one, secondValue)
        val secondPosition = stockRestorationPosition(firstPosition, firstPosition, secondDestination, one, data.now)
        val afterSlice = firstSlice.copy(restoredQuantity = firstSlice.restoredQuantity + one)

        assertEquals(inputs.money(inputs.originalSale.literal("issued_unit_cost")), secondValue)
        assertEquals(inputs.money(inputs.complete.literal("original_value_delta")), firstValue + secondValue)
        assertPool(second.after, inputs.complete.literal("quantity"), inputs.complete.literal("value"))
        assertExactAverage(second.after, inputs.complete.literal("pool_average"))
        assertEquals(second.after.onHand, secondPosition.onHand)
        assertEquals(inputs.quantity(inputs.complete.literal("cumulative_disposed_original_quantity")), afterSlice.restoredQuantity)
        assertEquals(inputs.money(inputs.complete.literal("remaining_disposition_original_cost")), data.acceptedCost - firstValue - secondValue)
        assertEquals(inputs.money(inputs.complete.literal("original_unreturned_cost")), afterSlice.originalValue - data.earlierValue - firstValue - secondValue)
        assertNotEquals(firstRequest.restorationId, secondRequest.restorationId)
        assertEquals(firstPoolSnapshot, first.after)
        assertEquals(firstSliceSnapshot, firstSlice)
        assertEquals(inputs.money(inputs.originalSale.literal("immutable_issued_cost")), afterSlice.originalValue)
        // Slice copies are DATA, not committed issue updates or refreshed native
        // revisions. The original allocation still has one unreturned unit; that
        // is not another accepted stock allowance. RSD-003(b) is UNBOUND here.
    }

    @Test
    fun rsd003c_foreignIssueAllocationOrPortionCannotSupplyRetailSelection() {
        val data = FrozenCoreReturnStockInventoryData()
        val request = data.request()
        val selection = request.selections.single()
        val foreign = inputs.id(99)
        val before = data.beforeSlice.copy()
        for (wrong in listOf(data.beforeSlice.copy(issueId = foreign),
            data.beforeSlice.copy(allocation = data.beforeSlice.allocation.copy(allocationId = foreign)),
            data.beforeSlice.copy(allocation = data.beforeSlice.allocation.copy(lineId = foreign)))) {
            assertFailsWith<LocalStockRejected> { requireStockRetailReturnSelection(request, selection, wrong) }
        }
        assertEquals(before, data.beforeSlice)
        assertPool(data.beforePool, inputs.before.literal("sellable_pool_quantity"), inputs.before.literal("sellable_pool_value"))
        // No receipt/return/acceptance/history proof is replaced with these DTOs.
    }

    @Test
    fun rsd002_releaseDestinationRequiresExistingMatchingEligibleDataPosition() {
        val data = FrozenCoreReturnStockInventoryData()
        val selection = data.request().selections.single()
        assertEquals(data.positionKey, stockRetailReturnDestination(selection, data.beforePosition, data.beforePosition, data.now))
        assertFailsWith<LocalStockRejected> { stockRetailReturnDestination(selection, data.beforePosition, null, data.now) }
        val otherBranch = data.beforePosition.copy(key = data.positionKey.copy(
            pool = data.poolKey.copy(scope = data.poolKey.scope.copy(branchId = inputs.id(99)))))
        assertFailsWith<LocalStockRejected> {
            stockRetailReturnDestination(selection, data.beforePosition, otherBranch, data.now)
        }
        assertPool(data.beforePool, inputs.before.literal("sellable_pool_quantity"), inputs.before.literal("sellable_pool_value"))
        // Existing safe DATA and exact-key guards only. No configured branch
        // destination, native expiry/safety, current authority or stock read is supplied.
    }

    private fun assertPool(pool: InventoryPoolProjection, quantity: String, value: String) {
        assertEquals(inputs.quantity(quantity), pool.onHand)
        assertEquals(inputs.money(value), pool.carryingValue)
    }

    private fun assertExactAverage(pool: InventoryPoolProjection, expectedPerEach: String) {
        val actualMinorPerEach = ExactRational.ratio(pool.carryingValue.minor, 1) / pool.onHand.toFraction()
        assertEquals(ExactRational.ratio(inputs.money(expectedPerEach).minor, 1), actualMinorPerEach)
    }
}

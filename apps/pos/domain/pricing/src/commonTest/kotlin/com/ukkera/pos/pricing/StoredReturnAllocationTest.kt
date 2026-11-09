package com.ukkera.pos.pricing

import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoredReturnAllocationTest {
    @Test fun repeatedPartialReturnsConsumeOriginalCentsAndFinalRemainder() {
        val original = line(1, "0.03333333", quantity = "3")
        val snapshot = OperationalPricingEngine.calculate(request(listOf(original)))
        var cursor = snapshot.openReturnCursor(id(1))
        val one = Quantity.parse(unit, "1", 6)
        val amounts = mutableListOf<Money>()
        repeat(3) {
            val result = cursor.consume(one)
            amounts += result.payable
            cursor = result.remaining
        }
        assertEquals(money(10), snapshot.payable)
        assertEquals(listOf(money(3), money(3), money(4)), amounts)
        assertEquals(money(0), cursor.remainingPayable)
        assertEquals(0L, cursor.remainingQuantity.scaled)
        assertFailsWith<IllegalArgumentException> { cursor.consume(one) }
        assertEquals(money(10), snapshot.openReturnCursor(id(1)).remainingPayable) // Original immutable snapshot unchanged.
    }

    @Test fun eachStoredTaxComponentHasMonotonePartitionIndependentConsumption() {
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "0.01111111", quantity = "3", percent = "50"))))
        val start = snapshot.openReturnCursor(id(1))
        val one = Quantity.parse(unit, "1", 6)
        val first = start.consume(one)
        val second = first.remaining.consume(one)
        val together = start.consume(Quantity.parse(unit, "2", 6))
        val last = second.remaining.consume(one)
        assertEquals(money(5), snapshot.payable)
        assertEquals(listOf(money(1), money(2), money(2)), listOf(first.payable, second.payable, last.payable))
        for (component in start.originalComponents) {
            val portions = listOf(first, second, last).map { part -> part.components.single { it.key == component.key }.amount }
            assertEquals(component.amount, portions.sumMoney(EGP))
            assertTrue(portions.all { it.minor >= 0 })
            assertEquals(first.components.single { it.key == component.key }.amount +
                second.components.single { it.key == component.key }.amount,
                together.components.single { it.key == component.key }.amount)
        }
        assertEquals(together.remaining.remainingComponents, second.remaining.remainingComponents)
    }

    @Test fun originalServiceAndTaxAreConsumedWithoutCurrentRuleOrPriceRecalculation() {
        val originalLine = line(1, "114", quantity = "2", percent = "14", inclusion = TaxInclusion.INCLUSIVE)
        val snapshot = OperationalPricingEngine.calculate(request(listOf(originalLine),
            listOf(service(setOf(id(1)))), listOf(fixed(2_508, listOf(id(1))))))
        val cursor = snapshot.openReturnCursor(id(1))
        // A new quote deliberately uses unrelated current prices/taxes and has no
        // effect on the original cursor. No current rules are an input to consume.
        OperationalPricingEngine.calculate(request(listOf(line(1, "999", quantity = "2", percent = "50")),
            listOf(service(setOf(id(1)), "25", "30"))))
        val half = cursor.consume(Quantity.parse(unit, "1", 6))
        assertEquals(money(11_286), half.payable)
        assertEquals(money(22_572), cursor.remainingPayable)
        assertEquals(money(11_286), half.remaining.remainingPayable)
        assertEquals(snapshot.snapshotId, half.remaining.originalSnapshotId)
        assertEquals(snapshot.revision, half.remaining.revision)
        assertEquals(PricingProfiles.STORED_RETURN_V1, half.remaining.profile)
    }

    @Test fun itemReturnDoesNotAutomaticallyReversePerformedOrIndependentCharges() {
        val fixed = PricingCharge.Fixed(id(7_100), Revision(1), ChargeKind.DELIVERY, UnitPrice.parse(EGP, "20"),
            TaxInclusion.EXCLUSIVE, tax("14"), ChargeRefundTreatment.EXPLICIT_ONLY)
        val explicit = service(setOf(id(1)), treatment = ChargeRefundTreatment.EXPLICIT_ONLY)
        val never = service(setOf(id(1)), index = 7_200, treatment = ChargeRefundTreatment.NEVER)
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "100", percent = "14")),
            listOf(fixed, explicit, never)))
        assertEquals(money(11_400), snapshot.openReturnCursor(id(1)).remainingPayable)
        assertEquals(money(12_540), snapshot.openReturnCursor(id(1), setOf(explicit.chargeId)).remainingPayable)
        assertFailsWith<IllegalArgumentException> { snapshot.openReturnCursor(id(1), setOf(never.chargeId)) }
        assertFailsWith<IllegalArgumentException> { snapshot.openReturnCursor(id(1), setOf(fixed.chargeId)) }
        assertFailsWith<IllegalArgumentException> { snapshot.openReturnCursor(id(1), setOf(id(88))) }
        assertFailsWith<IllegalArgumentException> { snapshot.openReturnCursor(id(88)) }
    }

    @Test fun returnUsesOriginalUnitPrecisionAndPositiveRemainingQuantity() {
        val cursor = OperationalPricingEngine.calculate(request(listOf(line(1, "1", quantity = "1.234567"))))
            .openReturnCursor(id(1))
        for (invalid in listOf(Quantity(unit, 0, 6), Quantity(unit, -1, 6), Quantity(unit, 1_234_568, 6),
            Quantity(id(99), 1, 6), Quantity(unit, 1, 0))) {
            assertFailsWith<IllegalArgumentException> { cursor.consume(invalid) }
        }
        val first = cursor.consume(Quantity(unit, 1, 6))
        assertEquals(money(0), first.payable)
        val last = first.remaining.consume(first.remaining.remainingQuantity)
        assertEquals(cursor.remainingPayable, last.payable)
        assertEquals(0L, last.remaining.remainingQuantity.scaled)
    }

    @Test fun largeQuantityAndPostingCursorNeverUsesOverflowingLongProducts() {
        val original = line(1, "0.01", scale = 0).copy(quantity = Quantity(unit, Long.MAX_VALUE, 0))
        val cursor = OperationalPricingEngine.calculate(request(listOf(original))).openReturnCursor(id(1))
        val almostAll = cursor.consume(Quantity(unit, Long.MAX_VALUE - 1, 0))
        assertEquals(money(Long.MAX_VALUE - 1), almostAll.payable)
        val final = almostAll.remaining.consume(Quantity(unit, 1, 0))
        assertEquals(money(1), final.payable)
        assertEquals(money(0), final.remaining.remainingPayable)
    }
}

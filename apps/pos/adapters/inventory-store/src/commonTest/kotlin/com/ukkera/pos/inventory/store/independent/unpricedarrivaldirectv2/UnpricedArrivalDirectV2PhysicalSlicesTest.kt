package com.ukkera.pos.inventory.store.independent.unpricedarrivaldirectv2

import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen UAD2 physical slices through the actual source-neutral planner.
 * These are NOT registered/protected commands. They establish neither native source
 * qualification nor durable custody/action-use/result/money/history effects.
 * No UAD2 whole case, concurrent schedule, cross-profile claim or platform lane passes here.
 * Refusal assertions conserve the supplied physical model only; other ledgers are UNBOUND.
 */
class UnpricedArrivalDirectV2PhysicalSlicesTest {
    private fun successor(original: ArrivalPhysicalOriginal, before: List<ArrivalSliceV1>, input: ArrivalPhysicalTransition): List<ArrivalSliceV1> =
        arrivalPhysicalSuccessorShared(original, original.arrivalId, before, input).first

    private fun initial(original: ArrivalPhysicalOriginal): List<ArrivalSliceV1> = arrivalInitialSlicesShared(original.lines).also {
        requireArrivalPhysicalStateShared(original, it)
    }

    private fun mainJourney(original: ArrivalPhysicalOriginal = Uad2Inputs.mainOriginal()): Map<String, List<ArrivalSliceV1>> {
        val c1 = initial(original)
        val c2 = successor(original, c1, Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyA, Uad2Inputs.quantity("A", "10"), Uad2Inputs.qualifySplit, passed = true))
        val c3 = successor(original, c2, Uad2Inputs.transition(original, LocalArrivalActionV1.REJECT,
            Uad2Inputs.qualifySplit.custodyUnitId, Uad2Inputs.quantity("A", "4"), Uad2Inputs.rejectSplit))
        val c4 = successor(original, c3, Uad2Inputs.transition(original, LocalArrivalActionV1.DISPATCH,
            Uad2Inputs.rejectSplit.custodyUnitId, Uad2Inputs.quantity("A", "3"), Uad2Inputs.dispatchSplit,
            recipient = Uad2Inputs.id("SUPPLIER-S")))
        return mapOf("C1" to c1, "C2" to c2, "C3" to c3, "C4" to c4)
    }

    private fun assertMainCut(slices: List<ArrivalSliceV1>, name: String) {
        val expected = Uad2Inputs.cuts.single { it.name == name }
        val a = slices.filter { it.lineId == Uad2Inputs.id("LINE-MAIN-A") }
        val b = slices.filter { it.lineId == Uad2Inputs.id("LINE-MAIN-B") }
        val dispositions = listOf(LocalArrivalDispositionV1.UNINSPECTED_HELD, LocalArrivalDispositionV1.PASSED_HELD,
            LocalArrivalDispositionV1.REJECTED_HELD, LocalArrivalDispositionV1.REFUSED, LocalArrivalDispositionV1.DISPATCHED)
        for ((index, disposition) in dispositions.withIndex()) {
            assertEquals(expected.a[index], a.filter { it.disposition == disposition }.sumOf { it.quantity.scaled }, "$name A $disposition")
            assertEquals(if (disposition == LocalArrivalDispositionV1.UNINSPECTED_HELD) expected.bUninspected else 0L,
                b.filter { it.disposition == disposition }.sumOf { it.quantity.scaled }, "$name B $disposition")
        }
        fun held(slice: ArrivalSliceV1): Boolean = slice.disposition in listOf(LocalArrivalDispositionV1.UNINSPECTED_HELD,
            LocalArrivalDispositionV1.PASSED_HELD, LocalArrivalDispositionV1.REJECTED_HELD)
        assertEquals(expected.aHeld, a.filter(::held).sumOf { it.quantity.scaled }, "$name A held")
        assertEquals(expected.totalHeld, slices.filter(::held).sumOf { it.quantity.scaled }, "$name total held")
        assertTrue(slices.all { it.quantity.unitId == Uad2Inputs.unit && it.quantity.scale == 0 })
        assertEquals(24L, a.sumOf { it.quantity.scaled }, "$name original A")
        assertEquals(6L, b.sumOf { it.quantity.scaled }, "$name original B")
    }

    private fun assertRefused(original: ArrivalPhysicalOriginal, before: List<ArrivalSliceV1>, input: ArrivalPhysicalTransition) {
        val retained = before.toList()
        assertFailsWith<ArrivalRejected> { successor(original, before, input) }
        assertEquals(retained, before, "refusal must not alter the prior physical model")
    }

    private data class TrackCuts(val original: ArrivalPhysicalOriginal, val recorded: List<ArrivalSliceV1>,
        val lotIdentified: List<ArrivalSliceV1>, val serialIdentified: List<ArrivalSliceV1>,
        val passed: List<ArrivalSliceV1>, val rejected: List<ArrivalSliceV1>)

    private fun trackJourney(): TrackCuts {
        val original = Uad2Inputs.trackingOriginal()
        val recorded = initial(original)
        val lot = successor(original, recorded, Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyL, Uad2Inputs.quantity("L", "2.5"), lot = Uad2Inputs.id("LOT-L"), expiresAt = Uad2Inputs.expiry))
        val serial = successor(original, lot, Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyT, Uad2Inputs.quantity("T", "1"), Uad2Inputs.serialSplit, serial = Uad2Inputs.newSerial))
        val passSerial = successor(original, serial, Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.serialSplit.custodyUnitId, Uad2Inputs.quantity("T", "1"), passed = true))
        val passed = successor(original, passSerial, Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyL, Uad2Inputs.quantity("L", "2.5"), passed = true))
        val rejected = successor(original, passed, Uad2Inputs.transition(original, LocalArrivalActionV1.REJECT,
            Uad2Inputs.custodyT, Uad2Inputs.quantity("T", "1")))
        return TrackCuts(original, recorded, lot, serial, passed, rejected)
    }

    @Test
    fun uad2_01_record_qualify_reject_dispatch_have_the_four_frozen_physical_cuts() {
        for ((cut, slices) in mainJourney()) assertMainCut(slices, cut)
    }

    @Test
    fun uad2_01_partial_actions_preserve_remainders_originals_and_exact_requested_ancestry() {
        val original = Uad2Inputs.mainOriginal()
        val retainedOriginal = original.copy(lines = original.lines.toList(), items = original.items.toList())
        val cuts = mainJourney(original)
        val a = cuts.getValue("C1").single { it.custodyUnitId == Uad2Inputs.custodyA }
        val b = cuts.getValue("C1").single { it.custodyUnitId == Uad2Inputs.custodyB }
        assertEquals(a.copy(quantity = Uad2Inputs.quantity("A", "14")),
            cuts.getValue("C4").single { it.custodyUnitId == Uad2Inputs.custodyA })
        for (cut in cuts.values) assertEquals(b, cut.single { it.custodyUnitId == Uad2Inputs.custodyB })
        val expectedParents = mapOf(Uad2Inputs.qualifySplit.custodyUnitId to Uad2Inputs.custodyA,
            Uad2Inputs.rejectSplit.custodyUnitId to Uad2Inputs.qualifySplit.custodyUnitId,
            Uad2Inputs.dispatchSplit.custodyUnitId to Uad2Inputs.rejectSplit.custodyUnitId)
        val expectedSources = mapOf(Uad2Inputs.qualifySplit.custodyUnitId to Uad2Inputs.qualifySplit.sourceId,
            Uad2Inputs.rejectSplit.custodyUnitId to Uad2Inputs.rejectSplit.sourceId,
            Uad2Inputs.dispatchSplit.custodyUnitId to Uad2Inputs.dispatchSplit.sourceId)
        for (slice in cuts.getValue("C4").filter { it.lineId == Uad2Inputs.id("LINE-MAIN-A") }) {
            assertEquals(Uad2Inputs.sourceA, slice.rootSourceId)
            assertEquals(Uad2Inputs.custodyA, slice.originalCustodyUnitId)
            if (slice.custodyUnitId != Uad2Inputs.custodyA) {
                assertEquals(expectedParents.getValue(slice.custodyUnitId), slice.parentCustodyUnitId)
                assertEquals(expectedSources.getValue(slice.custodyUnitId), slice.sourceId)
            }
        }
        assertEquals(retainedOriginal, original)
    }

    @Test
    fun uad2_01_the_same_pure_partial_input_has_the_same_identity_and_leaves_before_unchanged() {
        val original = Uad2Inputs.mainOriginal()
        val before = initial(original)
        val retained = before.toList()
        val input = Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY, Uad2Inputs.custodyA,
            Uad2Inputs.quantity("A", "10"), Uad2Inputs.qualifySplit, passed = true)
        assertEquals(successor(original, before, input), successor(original, before, input))
        assertEquals(retained, before)
        // This deterministic planner assertion is not an operation replay/durable-result test.
    }

    @Test
    fun uad2_02_missing_tracking_is_not_fabricated_and_identification_alone_never_passes_inspection() {
        val cuts = trackJourney()
        for (slice in cuts.recorded) {
            assertEquals(LocalArrivalDispositionV1.UNINSPECTED_HELD, slice.disposition)
            assertNull(slice.lotId); assertNull(slice.expiresAt); assertNull(slice.serialId); assertNull(slice.serialCode)
        }
        val lot = cuts.lotIdentified.single { it.custodyUnitId == Uad2Inputs.custodyL }
        assertEquals(Uad2Inputs.quantity("L", "2.5"), lot.quantity)
        assertEquals(Uad2Inputs.id("LOT-L"), lot.lotId)
        assertEquals(Uad2Inputs.expiry, lot.expiresAt)
        assertEquals(LocalArrivalDispositionV1.UNINSPECTED_HELD, lot.disposition)
        val identified = cuts.serialIdentified.single { it.custodyUnitId == Uad2Inputs.serialSplit.custodyUnitId }
        assertEquals(Uad2Inputs.quantity("T", "1"), identified.quantity)
        assertEquals(Uad2Inputs.newSerial, identified.serialCode)
        assertEquals(LocalArrivalDispositionV1.UNINSPECTED_HELD, identified.disposition)
        assertEquals(Uad2Inputs.custodyT, identified.parentCustodyUnitId)
        val remainder = cuts.serialIdentified.single { it.custodyUnitId == Uad2Inputs.custodyT }
        assertEquals(Uad2Inputs.quantity("T", "1"), remainder.quantity)
        assertNull(remainder.serialId); assertNull(remainder.serialCode)
        assertEquals(LocalArrivalDispositionV1.UNINSPECTED_HELD, remainder.disposition)
        // Observed code-only physical data is not a genuine serial-registration source.
        assertNull(identified.serialId); assertNull(identified.serialRegistrationRevision)
    }

    @Test
    fun uad2_02_explicit_inspections_and_rejection_have_the_exact_frozen_tracking_cut() {
        val cuts = trackJourney()
        val lot = cuts.rejected.single { it.custodyUnitId == Uad2Inputs.custodyL }
        assertEquals(Uad2Inputs.quantity("L", "2.5"), lot.quantity)
        assertEquals(LocalArrivalDispositionV1.PASSED_HELD, lot.disposition)
        assertEquals(Uad2Inputs.id("LOT-L"), lot.lotId); assertEquals(Uad2Inputs.expiry, lot.expiresAt)
        val serial = cuts.rejected.single { it.custodyUnitId == Uad2Inputs.serialSplit.custodyUnitId }
        assertEquals(Uad2Inputs.quantity("T", "1"), serial.quantity)
        assertEquals(LocalArrivalDispositionV1.PASSED_HELD, serial.disposition)
        assertEquals(Uad2Inputs.newSerial, serial.serialCode)
        val rejected = cuts.rejected.single { it.custodyUnitId == Uad2Inputs.custodyT }
        assertEquals(Uad2Inputs.quantity("T", "1"), rejected.quantity)
        assertEquals(LocalArrivalDispositionV1.REJECTED_HELD, rejected.disposition)
        assertNull(rejected.serialCode)
        assertEquals(2L, cuts.rejected.filter { it.lineId == Uad2Inputs.id("LINE-TRACK-T") }.sumOf { it.quantity.scaled })
        assertEquals(cuts.original.lines, Uad2Inputs.trackingOriginal().lines)
    }

    @Test
    fun uad2_02_splitting_alone_is_not_a_qualification_observation() {
        val original = Uad2Inputs.mainOriginal()
        assertRefused(original, initial(original), Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyA, Uad2Inputs.quantity("A", "10"), Uad2Inputs.qualifySplit))
    }

    @Test
    fun uad2_02_06_known_lot_expiry_and_serial_cannot_be_replaced() {
        val cuts = trackJourney()
        assertRefused(cuts.original, cuts.lotIdentified, Uad2Inputs.transition(cuts.original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyL, Uad2Inputs.quantity("L", "2.5"), lot = Uad2Inputs.id("LOT-OTHER")))
        assertRefused(cuts.original, cuts.lotIdentified, Uad2Inputs.transition(cuts.original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyL, Uad2Inputs.quantity("L", "2.5"), expiresAt = Uad2Inputs.changedExpiry))
        assertRefused(cuts.original, cuts.serialIdentified, Uad2Inputs.transition(cuts.original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.serialSplit.custodyUnitId, Uad2Inputs.quantity("T", "1"), serial = Uad2Inputs.differentSerial))
    }

    @Test
    fun uad2_02_rejected_held_cannot_be_requalified_or_rejected_again() {
        val cuts = trackJourney()
        assertRefused(cuts.original, cuts.rejected, Uad2Inputs.transition(cuts.original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyT, Uad2Inputs.quantity("T", "1"), passed = true))
        assertRefused(cuts.original, cuts.rejected, Uad2Inputs.transition(cuts.original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyT, Uad2Inputs.quantity("T", "1"), serial = Uad2Inputs.differentSerial))
        assertRefused(cuts.original, cuts.rejected, Uad2Inputs.transition(cuts.original, LocalArrivalActionV1.REJECT,
            Uad2Inputs.custodyT, Uad2Inputs.quantity("T", "1")))
    }

    @Test
    fun uad2_02_door_refusal_keeps_five_original_units_and_all_later_actions_refuse() {
        val original = Uad2Inputs.doorOriginal()
        val before = initial(original)
        val refused = before.single()
        assertEquals(Uad2Inputs.quantity("A", "5"), refused.quantity)
        assertEquals(LocalArrivalDispositionV1.REFUSED, refused.disposition)
        assertEquals(0L, before.filter { it.disposition in listOf(LocalArrivalDispositionV1.UNINSPECTED_HELD,
            LocalArrivalDispositionV1.PASSED_HELD, LocalArrivalDispositionV1.REJECTED_HELD) }.sumOf { it.quantity.scaled })
        assertEquals(0L, before.filter { it.disposition == LocalArrivalDispositionV1.DISPATCHED }.sumOf { it.quantity.scaled })
        for (action in listOf(LocalArrivalActionV1.QUALIFY, LocalArrivalActionV1.REJECT, LocalArrivalActionV1.DISPATCH)) {
            assertRefused(original, before, Uad2Inputs.transition(original, action, Uad2Inputs.custodyDoor,
                Uad2Inputs.quantity("A", "5"), passed = action == LocalArrivalActionV1.QUALIFY, recipient = Uad2Inputs.id("SUPPLIER-S")))
        }
    }

    @Test
    fun uad2_02_dispatch_refuses_uninspected_passed_wrong_recipient_excess_and_departed_sources() {
        val original = Uad2Inputs.mainOriginal()
        val cuts = mainJourney()
        assertRefused(original, cuts.getValue("C1"), Uad2Inputs.transition(original, LocalArrivalActionV1.DISPATCH,
            Uad2Inputs.custodyA, Uad2Inputs.quantity("A", "24"), recipient = Uad2Inputs.id("SUPPLIER-S")))
        assertRefused(original, cuts.getValue("C2"), Uad2Inputs.transition(original, LocalArrivalActionV1.DISPATCH,
            Uad2Inputs.qualifySplit.custodyUnitId, Uad2Inputs.quantity("A", "10"), recipient = Uad2Inputs.id("SUPPLIER-S")))
        assertRefused(original, cuts.getValue("C3"), Uad2Inputs.transition(original, LocalArrivalActionV1.DISPATCH,
            Uad2Inputs.rejectSplit.custodyUnitId, Uad2Inputs.quantity("A", "4"), recipient = Uad2Inputs.id("SUPPLIER-OTHER")))
        assertRefused(original, cuts.getValue("C4"), Uad2Inputs.transition(original, LocalArrivalActionV1.DISPATCH,
            Uad2Inputs.rejectSplit.custodyUnitId, Uad2Inputs.quantity("A", "2"), recipient = Uad2Inputs.id("SUPPLIER-S")))
        assertRefused(original, cuts.getValue("C4"), Uad2Inputs.transition(original, LocalArrivalActionV1.DISPATCH,
            Uad2Inputs.dispatchSplit.custodyUnitId, Uad2Inputs.quantity("A", "3"), recipient = Uad2Inputs.id("SUPPLIER-S")))
    }

    @Test
    fun uad2_05_last_one_unit_has_exact_c5_cut_and_cannot_depart_twice_sequentially() {
        val original = Uad2Inputs.mainOriginal()
        val c4 = mainJourney().getValue("C4")
        val input = Uad2Inputs.transition(original, LocalArrivalActionV1.DISPATCH,
            Uad2Inputs.rejectSplit.custodyUnitId, Uad2Inputs.quantity("A", "1"), recipient = Uad2Inputs.id("SUPPLIER-S"))
        val c5 = successor(original, c4, input)
        assertMainCut(c5, "C5")
        assertRefused(original, c5, input)
        // The two-operation concurrent last-unit race and one durable origin remain UNBOUND.
    }

    @Test
    fun uad2_06_zero_negative_excess_and_wrong_unit_refuse_without_changing_the_physical_cut() {
        val original = Uad2Inputs.mainOriginal()
        val before = initial(original)
        for (quantity in listOf(Quantity(Uad2Inputs.unit, 0, 0), Quantity(Uad2Inputs.unit, -1, 0),
            Quantity(Uad2Inputs.unit, 25, 0), Quantity(Uad2Inputs.meter, 24, 0))) {
            assertRefused(original, before, Uad2Inputs.transition(original, LocalArrivalActionV1.REJECT, Uad2Inputs.custodyA, quantity))
        }
    }

    @Test
    fun uad2_06_fractional_serial_and_unsupported_kg_precision_are_not_rounded_into_valid_quantities() {
        val original = Uad2Inputs.trackingOriginal()
        val before = initial(original)
        assertRefused(original, before, Uad2Inputs.transition(original, LocalArrivalActionV1.QUALIFY, Uad2Inputs.custodyT,
            Quantity.parse(Uad2Inputs.unit, "1.5", 1), Uad2Inputs.serialSplit, serial = Uad2Inputs.newSerial))
        assertFails { Quantity.parse(Uad2Inputs.unit, "1.5", 0) }
        assertFails { Quantity.parse(Uad2Inputs.kilogram, "2.5000001", 3) }
        assertEquals(before, initial(original))
        // The public quantity parser cannot construct the seven-decimal frozen input.
    }

    @Test
    fun uad2_06_duplicate_serial_and_reused_partial_identity_refuse() {
        val cuts = trackJourney()
        assertRefused(cuts.original, cuts.serialIdentified, Uad2Inputs.transition(cuts.original, LocalArrivalActionV1.QUALIFY,
            Uad2Inputs.custodyT, Uad2Inputs.quantity("T", "1"), serial = Uad2Inputs.newSerial))
        val original = Uad2Inputs.mainOriginal()
        val c2 = mainJourney().getValue("C2")
        assertRefused(original, c2, Uad2Inputs.transition(original, LocalArrivalActionV1.REJECT,
            Uad2Inputs.qualifySplit.custodyUnitId, Uad2Inputs.quantity("A", "4"),
            LocalArrivalSplitV2(Uad2Inputs.sourceA, Uad2Inputs.custodyA)))
        // Live/historical registration and cross-profile permanent claims remain UNBOUND.
    }

    @Test
    fun uad2_06_disconnected_source_ancestry_is_not_a_valid_physical_state() {
        val original = Uad2Inputs.mainOriginal()
        val before = mainJourney().getValue("C4")
        val disconnected = before.map { slice -> if (slice.custodyUnitId == Uad2Inputs.dispatchSplit.custodyUnitId)
            slice.copy(parentCustodyUnitId = Uad2Inputs.custodyB) else slice }
        assertFails { requireArrivalPhysicalStateShared(original, disconnected) }
        assertMainCut(before, "C4")
    }

    @Test
    fun uad2_01_06_original_two_cartons_convert_exactly_to_twenty_four_base_units() {
        val conversion = ProductUnitConversion(Uad2Inputs.conversionCarrier, Revision(4), Uad2Inputs.id("PRODUCT-A"),
            Uad2Inputs.carton, Uad2Inputs.unit, UnitConversionRate.ratio(12, 1))
        val base = conversion.convertExact(Quantity.parse(Uad2Inputs.carton, "2", 0), 0)
        assertEquals(Uad2Inputs.quantity("A", "24"), base)
        assertEquals(base, Uad2Inputs.mainOriginal().lines.single { it.skuId == Uad2Inputs.id("SKU-A") }.baseQuantity)
        for ((cut, slices) in mainJourney()) assertMainCut(slices, cut)
        // Native activation of future rate 10 and original authenticated-UOM retention are UNBOUND.
    }
}

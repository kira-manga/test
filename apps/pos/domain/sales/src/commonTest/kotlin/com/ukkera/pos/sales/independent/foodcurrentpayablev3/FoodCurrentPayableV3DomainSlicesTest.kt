package com.ukkera.pos.sales.independent.foodcurrentpayablev3

import com.ukkera.pos.pricing.ChargeKind
import com.ukkera.pos.pricing.ChargeRefundTreatment
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingCharge
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.sales.FoodCurrentUnpaidPayable
import com.ukkera.pos.sales.FoodIngredientReturnDisposition
import com.ukkera.pos.sales.FoodPreparation
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supporting FOOD-CURRENT-V3-CASES-1 subassertions. Factory/codec validation is real;
 * native sources, complete MIX/ROUND admission, financial workflow and authorization are not bound.
 */
class FoodCurrentPayableV3DomainSlicesTest {
    private val f = FoodCurrentPayableV3DomainInputs

    @Test
    fun fcp3001_frozenMixPostingArithmeticAndAdmissibleOriginalBatchSlices() {
        val live = f.mix.take(5)
        assertEquals(f.egp(17_020), live.fold(f.egp(0)) { total, item -> total + f.egp(item.net) })
        assertEquals(f.egp(2_383), live.fold(f.egp(0)) { total, item -> total + f.egp(item.tax) })
        assertEquals(f.egp(19_403), live.fold(f.egp(0)) { total, item -> total + f.egp(item.gross) })
        assertEquals(f.egp(30_803), f.mix.fold(f.egp(0)) { total, item -> total + f.egp(item.gross) })
        assertEquals(f.egp(15_966), f.mix.take(2).fold(f.egp(0)) { total, item -> total + f.egp(item.gross) })

        val states = f.mixedStates().associateBy { it.portionId }
        // D's prescribed posting is not admitted by the current original-pricing codec.
        // The full frozen MIX arithmetic above is not mislabeled as a production projection.
        for (item in f.mix.filter { it.label != "D" }) {
            val quote = f.quote(item)
            val original = StoredPricingSnapshotCodec.encode(quote)
            assertEquals(f.egp(item.net), posted(quote, PriceComponentKind.ITEM_NET), "FCP3-001 ${item.label} original net")
            assertEquals(f.egp(item.tax), posted(quote, PriceComponentKind.ITEM_TAX), "FCP3-001 ${item.label} original tax")
            assertEquals(f.egp(item.gross), quote.payable)
            assertEquals(original, StoredPricingSnapshotCodec.encode(StoredPricingSnapshotCodec.decode(original)))
            val state = states.getValue(item.portionId)
            assertEquals(f.egp(if (item.label == "F") 0 else item.gross), FoodCurrentUnpaidPayable.projectIncludingPrepared(
                listOf(quote), listOf(state), f.egp(item.gross), null, null), "FCP3-001 ${item.label} preparation slice")
            assertEquals(original, StoredPricingSnapshotCodec.encode(quote), "FCP3-001 original postings unchanged")
        }
    }

    @Test
    fun fcp3001_003_008_ordinaryPreparationAndServiceDoNotBecomePaymentOrNeverSentCancellation() {
        val states = f.mixedStates()
        assertTrue(states.all(FoodCurrentUnpaidPayable::isOrdinaryUnpaid))
        assertEquals(4, states.count { it.issueId != null })
        assertEquals(4, states.sumOf { it.tasks.size })
        assertEquals(FoodPreparationStatus.CANCELLED_BEFORE_START, states.last().status)
        assertTrue(FoodCurrentUnpaidPayable.isNeverSent(states.last()))
        assertTrue(states.last().tasks.isEmpty() && states.last().issueId == null && states.last().reservationId == null)
        assertTrue(states.take(5).map { if (it.issueId == null) f.queued(it.portionId) else it }
            .all(FoodCurrentUnpaidPayable::isOrdinaryUnpaid), "FCP3-001 all-ordinary-sent preparation eligibility")
        val full = f.fullStates()
        val quotes = f.fullQuotes(false)
        assertNull(FoodCurrentUnpaidPayable.project(quotes, full, f.egp(3_648), null, null),
            "FCP3-008 V2 retains the unsent-only boundary")
        val neverSent = full.map { f.unsent(it.portionId) }
        assertEquals(f.egp(3_648), FoodCurrentUnpaidPayable.project(quotes, neverSent, f.egp(3_648), null, null))
        val cancelled = neverSent.map(f::cancelUnsent)
        assertEquals(f.egp(0), FoodCurrentUnpaidPayable.projectIncludingPrepared(quotes, cancelled, f.egp(3_648), null, null))
        assertEquals(f.egp(0), FoodCurrentUnpaidPayable.project(quotes, cancelled, f.egp(3_648), null, null))
        assertEquals(32, cancelled.map { it.portionId }.distinct().size)
        assertTrue(cancelled.all { it.tasks.isEmpty() && it.issueId == null && it.reservationId == null })
    }

    @Test
    fun fcp3002_singleQuantityTaxBoundaryAndMixedTaxBaselineAreExact() {
        val r2 = f.quote(f.sourceId(502), listOf(f.line(f.sourceId(512), "3.25", TaxInclusion.EXCLUSIVE)))
        assertEquals(f.egp(325), posted(r2, PriceComponentKind.ITEM_NET))
        assertEquals(f.egp(46), posted(r2, PriceComponentKind.ITEM_TAX))
        assertEquals(f.egp(371), r2.payable)
        val encoded = StoredPricingSnapshotCodec.encode(r2)
        assertEquals(encoded, StoredPricingSnapshotCodec.encode(StoredPricingSnapshotCodec.decode(encoded)))
        val baseline = f.quote(f.sourceId(503), listOf(f.line(f.sourceId(513), "114", TaxInclusion.INCLUSIVE),
            f.line(f.sourceId(514), "100", TaxInclusion.EXCLUSIVE)))
        assertEquals(f.egp(20_000), posted(baseline, PriceComponentKind.ITEM_NET))
        assertEquals(f.egp(2_800), posted(baseline, PriceComponentKind.ITEM_TAX))
        assertEquals(f.egp(22_800), baseline.payable)
        // The frozen R1 has three distinct preparation identities; it is not bound by merging
        // them into one quantity-three portion. Complete ROUND source admission remains unbound.
    }

    @Test
    fun fcp3003_emptyOpenUncollectedProjectionIsKnownZero() {
        assertEquals(f.egp(0), FoodCurrentUnpaidPayable.projectIncludingPrepared(emptyList(), emptyList(), f.egp(0), null, null))
        assertEquals(f.egp(0), FoodCurrentUnpaidPayable.project(emptyList(), emptyList(), f.egp(0), null, null))
        assertNull(FoodCurrentUnpaidPayable.projectIncludingPrepared(emptyList(), emptyList(), f.egp(0), null,
            OperationId(f.sourceId(601).value)))
    }

    @Test
    fun fcp3004_oldSavedAPostingsStayPinnedWhileNewSavedAUsesTheNewRevision() {
        val a = f.mix.first()
        val live = mutableListOf(f.line(a.portionId, a.unitPrice, a.inclusion))
        val original = f.quote(a.batchId, live)
        val bytes = StoredPricingSnapshotCodec.encode(original)
        live[0] = live[0].copy(unitPrice = UnitPrice.parse(Currency.EGP, "171.00"), priceRevision = Revision(6))
        assertEquals(f.egp(11_400), FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(original), listOf(f.unsent(a.portionId)),
            f.egp(11_400), null, null), "FCP3-004 original A slice after external input change")
        assertEquals(bytes, StoredPricingSnapshotCodec.encode(original))
        val later = f.quote(f.sourceId(602), live)
        assertEquals(f.egp(15_000), posted(later, PriceComponentKind.ITEM_NET))
        assertEquals(f.egp(2_100), posted(later, PriceComponentKind.ITEM_TAX))
        assertEquals(f.egp(17_100), later.payable)
        assertEquals(Revision(5), original.lines.single().priceRevision)
        assertEquals(Revision(6), later.lines.single().priceRevision)
        // These are resolved-input/pricing slices, not catalog activation or whole MIX admission.
    }

    @Test
    fun fcp3005_validPreparationBoundariesAndUnsupportedMoneyStayUnavailable() {
        val item = f.mix[1]
        val quote = f.quote(item)
        val ready = f.ready(item.portionId)
        val requested = FoodPreparation.requestCancellation(f.queued(item.portionId), Revision(1), f.sourceId(603), f.at)
        val confirmed = FoodPreparation.confirmUnstarted(requested, requested.revision, requested.tasks.single().stationId,
            f.sourceId(604), f.sourceId(605), FoodIngredientReturnDisposition.RELEASED, f.at)
        val started = f.preparing(item.portionId)
        val wasteRequest = FoodPreparation.requestCancellation(started, started.revision, f.sourceId(606), f.at)
        val waste = FoodPreparation.discardStarted(wasteRequest, wasteRequest.revision)
        val pickup = FoodPreparation.pickUp(ready, ready.revision, f.at)
        for ((label, state) in listOf("U07 request" to requested, "U08 sent cancellation" to confirmed,
            "U09 waste" to waste, "U06 preparation pickup" to pickup)) {
            assertFalse(FoodCurrentUnpaidPayable.isNeverSent(state), label)
            assertFalse(FoodCurrentUnpaidPayable.isOrdinaryUnpaid(state), label)
            assertNull(FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(quote), listOf(state), f.egp(item.gross), null, null), label)
        }
        assertNull(FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(quote), listOf(ready), f.egp(item.gross), f.sourceId(607), null),
            "U01 explicit collection boundary")
        assertNull(FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(quote), listOf(ready), f.egp(item.gross), null,
            OperationId(f.sourceId(608).value)), "U05 explicit closed boundary")
        val charge = PricingCharge.Fixed(f.sourceId(609), Revision(1), ChargeKind.OTHER, UnitPrice.parse(Currency.EGP, "1.00"),
            TaxInclusion.EXCLUSIVE, f.zeroTax, ChargeRefundTreatment.EXPLICIT_ONLY)
        val unsupported = OperationalPricingEngine.calculate(PricingRequest(f.sourceId(610), Revision(0), Currency.EGP,
            listOf(f.line(item.portionId, item.unitPrice, item.inclusion)), charges = listOf(charge)))
        val encoded = StoredPricingSnapshotCodec.encode(unsupported)
        assertNull(FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(unsupported), listOf(ready), unsupported.payable, null, null),
            "U12 otherwise complete pure price input has an unsupported current-obligation interpretation")
        assertEquals(encoded, StoredPricingSnapshotCodec.encode(unsupported))
        // No paid/partial/unknown-money document, approved pickup or native issue/reversal is established here.
    }

    @Test
    fun fcp3006_007_completeMaximumProfilesRetainOriginalAmountsAndRefuseMissingOffPageMembers() {
        for (eightPerBatch in listOf(false, true)) {
            val quotes = f.fullQuotes(eightPerBatch)
            val states = f.fullStates()
            val originals = quotes.map(StoredPricingSnapshotCodec::encode)
            assertEquals(if (eightPerBatch) 4 else 32, quotes.size)
            for (quote in quotes) {
                assertEquals(if (eightPerBatch) 8 else 1, quote.lines.size)
                assertEquals(f.egp(if (eightPerBatch) 800 else 100), posted(quote, PriceComponentKind.ITEM_NET))
                assertEquals(f.egp(if (eightPerBatch) 112 else 14), posted(quote, PriceComponentKind.ITEM_TAX))
                assertEquals(f.egp(if (eightPerBatch) 912 else 114), quote.payable)
                assertEquals(StoredPricingSnapshotCodec.encode(quote),
                    StoredPricingSnapshotCodec.encode(StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(quote))))
            }
            repeat(3) {
                assertEquals(f.egp(3_648), FoodCurrentUnpaidPayable.projectIncludingPrepared(quotes, states, f.egp(3_648), null, null))
            }
            for (collection in listOf(null, f.sourceId(611))) {
                assertFailsWith<SalesRuleViolation>("FCP3-006 missing last complete-order member, including unavailable collection boundary") {
                    FoodCurrentUnpaidPayable.projectIncludingPrepared(quotes, states.dropLast(1), f.egp(3_648), collection, null)
                }
            }
            assertEquals(originals, quotes.map(StoredPricingSnapshotCodec::encode))
        }
        val quotes = f.fullQuotes(false)
        val states = f.fullStates()
        assertFailsWith<SalesRuleViolation>("FCP3-006 duplicate original") {
            FoodCurrentUnpaidPayable.projectIncludingPrepared(quotes.dropLast(1) + quotes.first(), states, f.egp(3_648), null, null)
        }
        assertFailsWith<SalesRuleViolation>("FCP3-006 missing last off-page original batch") {
            FoodCurrentUnpaidPayable.projectIncludingPrepared(quotes.dropLast(1), states, f.egp(3_648), null, null)
        }
        assertFailsWith<SalesRuleViolation>("FCP3-006 extraneous complete member") {
            FoodCurrentUnpaidPayable.projectIncludingPrepared(quotes, states.dropLast(1) + f.unsent(f.fullPortion(33)), f.egp(3_648), null, null)
        }
        assertFailsWith<SalesRuleViolation>("FCP3-007 33 complete members/batches cannot be a partial known amount") {
            FoodCurrentUnpaidPayable.projectIncludingPrepared(quotes + f.quote(f.fullBatch(33),
                listOf(f.line(f.fullPortion(33), "1.00", TaxInclusion.EXCLUSIVE))), states + f.unsent(f.fullPortion(33)),
                f.egp(3_762), null, null)
        }
        val oversized = f.quote(f.sourceId(612), (1..9).map { f.line(f.fullPortion(it), "1.00", TaxInclusion.EXCLUSIVE) })
        assertFailsWith<SalesRuleViolation>("FCP3-007 nine-portion saved batch") {
            FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(oversized), states.take(9), oversized.payable, null, null)
        }
        val emptyBatch = f.quote(f.sourceId(613), emptyList())
        assertFailsWith<SalesRuleViolation>("FCP3-007 empty saved batch alongside otherwise valid member") {
            FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(quotes.first(), emptyBatch), states.take(1), f.egp(114), null, null)
        }
        assertFailsWith<SalesRuleViolation>("FCP3-007 saved batch on empty order") {
            FoodCurrentUnpaidPayable.projectIncludingPrepared(listOf(emptyBatch), emptyList(), f.egp(0), null, null)
        }
    }

    @Test
    fun fcp3006_corruptedAdmissibleOriginalPostingRefusesTheProductionStoredCodec() {
        val original = StoredPricingSnapshotCodec.encode(f.fullQuotes(false).first())
        val corrupt = original.replace("\"minor\":\"114\"", "\"minor\":\"115\"")
        assertFails("FCP3-006 a changed original gross cannot retain its exact source posting") {
            StoredPricingSnapshotCodec.decode(corrupt)
        }
        assertEquals(original, StoredPricingSnapshotCodec.encode(StoredPricingSnapshotCodec.decode(original)))
        // This corrupts an admissible FULL32 original in memory; it does not repair or mutate durable history.
    }

    private fun posted(quote: PricingSnapshot, kind: PriceComponentKind) =
        quote.components.filter { it.kind == kind }.fold(f.egp(0)) { total, component -> total + component.posted }
}

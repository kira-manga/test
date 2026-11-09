package com.ukkera.pos.sales.independent.foodcurrentpayablev3

import com.ukkera.pos.pricing.PostedPriceComponent
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingProfiles
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxPostingPolicy
import com.ukkera.pos.sales.FoodCurrentUnpaidPayable
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supporting FCP3-POSTING-1.0.0 pricing, original stored-codec, preparation and pure projection bindings.
 * Complete in-memory MIX/PRE/ROUND profiles do not establish native source custody, durable effects,
 * actual order read revisions, current authority, registered RPC, restart or whole-case qualification.
 */
class FoodCurrentPayableV3PostingResolutionSlicesTest {
    private val f = FoodCurrentPayableV3DomainInputs
    private val v = FoodCurrentPayableV3PostingResolutionInputs
    private val minorPerEgp = ExactRational.ratio(100, 1)

    @Test
    fun fcp3Posting100_fcp3001_completeMixOriginalDocumentsConserveExactAndPostedComponents() {
        val quotes = v.mix.map(f::quote)
        assertEquals(f.mix.map { it.batchId }, quotes.map { it.snapshotId })
        assertEquals(f.mix.map { it.portionId }, quotes.flatMap { it.lines }.map { it.portionId })
        assertEquals(6, quotes.size)
        for ((item, quote) in v.mix.zip(quotes)) {
            assertOriginalDocument(quote, item.net, item.tax, item.gross)
            assertEquals(listOf(item.portionId), quote.lines.map { it.portionId })
            val line = quote.lines.single()
            assertEquals(item.portionId, line.lineId)
            assertEquals(Revision(5), line.priceRevision)
            assertEquals(f.unit, line.quantity.unitId)
            assertEquals(1L, line.quantity.scaled)
            assertEquals(0, line.quantity.scale)
            assertEquals(UnitPrice.parse(Currency.EGP, item.unitPrice), line.unitPrice)
            assertEquals(item.inclusion, line.inclusion)
            assertEquals(f.tax, line.tax)
            val (exactNetMinor, exactTaxMinor) = v.mixExactMinor.getValue(item.label)
            assertEquals(exactNetMinor, component(quote, item.portionId, PriceComponentKind.ITEM_NET).exact * minorPerEgp,
                "FCP3-POSTING-1.0.0 ${item.label} exact original net")
            assertEquals(exactTaxMinor, component(quote, item.portionId, PriceComponentKind.ITEM_TAX).exact * minorPerEgp,
                "FCP3-POSTING-1.0.0 ${item.label} exact original tax")
            assertEquals(exactNetMinor + exactTaxMinor, quote.exactPayable * minorPerEgp)
            val encoded = StoredPricingSnapshotCodec.encode(quote)
            val decoded = StoredPricingSnapshotCodec.decode(encoded)
            assertOriginalDocument(decoded, item.net, item.tax, item.gross)
            assertEquals(quote.components, decoded.components)
            assertEquals(quote.lines, decoded.lines)
            assertEquals(encoded, StoredPricingSnapshotCodec.encode(decoded))
        }
        assertEquals(f.egp(27_019), posted(quotes, PriceComponentKind.ITEM_NET))
        assertEquals(f.egp(3_783), posted(quotes, PriceComponentKind.ITEM_TAX))
        assertEquals(f.egp(30_802), payable(quotes))
        assertEquals(f.egp(17_019), posted(quotes.take(5), PriceComponentKind.ITEM_NET))
        assertEquals(f.egp(2_383), posted(quotes.take(5), PriceComponentKind.ITEM_TAX))
        assertEquals(f.egp(19_402), payable(quotes.take(5)))
        assertEquals(f.egp(15_966), payable(quotes.take(2)))
        val d = quotes[3]
        assertEquals(ExactRational.ratio(38_019, 100), d.exactPayable * minorPerEgp)
        assertEquals(333L, component(d, v.mix[3].portionId, PriceComponentKind.ITEM_NET).posted.minor)
        assertEquals(47L, component(d, v.mix[3].portionId, PriceComponentKind.ITEM_TAX).posted.minor)
    }

    @Test
    fun fcp3Posting100_fcp3001_008_012_completeMixAndSentVariantKeepOriginalCurrentAndCancelACut() {
        val originals = v.mix.map(f::quote).map(StoredPricingSnapshotCodec::encode)
        val quotes = originals.map(StoredPricingSnapshotCodec::decode)
        val mixed = f.mixedStates()
        assertEquals(4, mixed.count { it.issueId != null })
        assertEquals(4, mixed.sumOf { it.tasks.size })
        assertTrue(mixed.all(FoodCurrentUnpaidPayable::isOrdinaryUnpaid))
        assertEquals(f.egp(19_402), FoodCurrentUnpaidPayable.projectIncludingPrepared(
            quotes, mixed, f.egp(30_802), null, null))
        assertNull(FoodCurrentUnpaidPayable.project(quotes, mixed, f.egp(30_802), null, null),
            "FCP3-008 V2 does not acquire ordinary prepared-work semantics")

        val allSent = mixed.map { if (it.portionId == v.mix.first().portionId) f.queued(it.portionId) else it }
        assertEquals(5, allSent.count { it.issueId != null })
        assertEquals(5, allSent.sumOf { it.tasks.size })
        assertEquals(mixed.last(), allSent.last(), "F remains the genuine pure never-sent cancellation")
        assertEquals(f.egp(19_402), FoodCurrentUnpaidPayable.projectIncludingPrepared(
            quotes, allSent, f.egp(30_802), null, null))
        assertNull(FoodCurrentUnpaidPayable.project(quotes, allSent, f.egp(30_802), null, null))

        val cancelA = mixed.map { if (it.portionId == v.mix.first().portionId) f.cancelUnsent(it) else it }
        assertEquals(FoodPreparationStatus.CANCELLED_BEFORE_START, cancelA.first().status)
        assertTrue(FoodCurrentUnpaidPayable.isNeverSent(cancelA.first()))
        assertEquals(4, cancelA.count { it.issueId != null })
        assertEquals(4, cancelA.sumOf { it.tasks.size })
        assertEquals(f.egp(8_002), FoodCurrentUnpaidPayable.projectIncludingPrepared(
            quotes, cancelA, f.egp(30_802), null, null))
        assertNull(FoodCurrentUnpaidPayable.project(quotes, cancelA, f.egp(30_802), null, null))
        assertEquals(f.egp(30_802), payable(quotes))
        assertEquals(originals, quotes.map(StoredPricingSnapshotCodec::encode))
        // These are genuine pure preparation transitions, not a native 41/42 transaction or durable stock cut.
    }

    @Test
    fun fcp3Posting100_fcp3003_008_completePreCancellationCutsRetainHistoryAndOldV2KnownAmounts() {
        val originals = v.mix.map(f::quote).map(StoredPricingSnapshotCodec::encode)
        val quotes = originals.map(StoredPricingSnapshotCodec::decode)
        val before = v.mix.map { f.unsent(it.portionId) }
        val afterF = before.map { if (it.portionId == v.mix.last().portionId) f.cancelUnsent(it) else it }
        val allCancelled = afterF.map {
            if (it.status == FoodPreparationStatus.NOT_SENT) f.cancelUnsent(it) else it
        }
        for ((states, expected) in listOf(before to 30_802L, afterF to 19_402L, allCancelled to 0L)) {
            assertEquals(v.mix.map { it.portionId }, states.map { it.portionId })
            assertEquals(6, states.map { it.portionId }.distinct().size)
            assertTrue(states.all(FoodCurrentUnpaidPayable::isNeverSent))
            assertTrue(states.all { it.tasks.isEmpty() && it.issueId == null && it.issueRevision == null &&
                it.reservationId == null && it.sentAt == null && it.servedAt == null && it.pickedUpAt == null })
            assertEquals(f.egp(expected), FoodCurrentUnpaidPayable.projectIncludingPrepared(
                quotes, states, f.egp(30_802), null, null))
            assertEquals(f.egp(expected), FoodCurrentUnpaidPayable.project(
                quotes, states, f.egp(30_802), null, null), "FCP3-008 unchanged V2 pure PRE boundary")
            assertEquals(f.egp(30_802), payable(quotes))
            assertEquals(originals, quotes.map(StoredPricingSnapshotCodec::encode))
        }
        assertEquals(0, before.count { it.status == FoodPreparationStatus.CANCELLED_BEFORE_START })
        assertEquals(1, afterF.count { it.status == FoodPreparationStatus.CANCELLED_BEFORE_START })
        assertEquals(6, allCancelled.count { it.status == FoodPreparationStatus.CANCELLED_BEFORE_START })
        assertTrue(allCancelled.all { it.revision == Revision(1) })
        // This pure API has no order/storage join; no independent persisted PRE order or physical effect is claimed.
    }

    @Test
    fun fcp3Posting100_fcp3002_threeDistinctR1PortionsKeepFrozenCanonicalResidualAndStoredComponents() {
        val lines = v.r1Lines()
        val original = f.quote(v.r1Batch, lines)
        val encoded = StoredPricingSnapshotCodec.encode(original)
        val expectedNetWinner = v.r1.minBy { it.portionId.value }.portionId
        assertEquals(v.r1.first().portionId, expectedNetWinner)
        assertEquals(listOf("R1a", "R1b", "R1c"), v.r1.map { it.label })
        for (quote in listOf(original, StoredPricingSnapshotCodec.decode(encoded))) {
            assertOriginalDocument(quote, 1_000, 141, 1_141)
            assertEquals(v.r1Batch, quote.snapshotId)
            assertEquals(v.r1.map { it.portionId }, quote.lines.map { it.portionId })
            assertEquals(3, quote.lines.map { it.portionId }.distinct().size)
            assertEquals(6, quote.components.size)
            assertEquals(3L, quote.lines.sumOf { it.quantity.scaled })
            assertEquals(ExactRational.ratio(114_057, 100), quote.exactPayable * minorPerEgp)
            for (portion in v.r1) {
                val line = quote.lines.single { it.portionId == portion.portionId }
                assertEquals(portion.portionId, line.lineId)
                assertEquals(1L, line.quantity.scaled)
                assertEquals(0, line.quantity.scale)
                assertEquals(f.unit, line.quantity.unitId)
                assertEquals(Revision(5), line.priceRevision)
                assertEquals(UnitPrice.parse(Currency.EGP, "3.335"), line.unitPrice)
                assertEquals(TaxInclusion.EXCLUSIVE, line.inclusion)
                assertEquals(f.tax, line.tax)
                val net = component(quote, portion.portionId, PriceComponentKind.ITEM_NET)
                val tax = component(quote, portion.portionId, PriceComponentKind.ITEM_TAX)
                assertEquals("p/${portion.portionId.value}/item/net", net.key)
                assertEquals("p/${portion.portionId.value}/item/tax", tax.key)
                assertEquals(ExactRational.ratio(667, 2), net.exact * minorPerEgp)
                assertEquals(ExactRational.ratio(4_669, 100), tax.exact * minorPerEgp)
                assertEquals(f.egp(portion.net), net.posted, "${portion.label} frozen net allocation")
                assertEquals(f.egp(portion.tax), tax.posted, "${portion.label} frozen tax allocation")
                assertEquals(f.egp(portion.gross), line.payable)
                assertEquals(line.payable, net.posted + tax.posted)
                assertEquals(1L, tax.posted.minor - tax.exact.floorScaled(2))
            }
            assertEquals(1_137L, quote.components.sumOf { it.exact.floorScaled(2) })
            assertEquals(4L, quote.components.sumOf { it.posted.minor - it.exact.floorScaled(2) })
            val netWinners = quote.components.filter { it.kind == PriceComponentKind.ITEM_NET &&
                it.posted.minor - it.exact.floorScaled(2) == 1L }.map { it.portionId }
            assertEquals(listOf(expectedNetWinner), netWinners)
            assertEquals(encoded, StoredPricingSnapshotCodec.encode(quote))
        }
        for (ordered in listOf(lines.reversed(), lines.drop(1) + lines.take(1))) {
            val reordered = f.quote(v.r1Batch, ordered)
            assertEquals(original.components, reordered.components,
                "Frozen canonical identity, not incoming line order, selects the one net residual")
            assertEquals(encoded, StoredPricingSnapshotCodec.encode(reordered))
        }
    }

    @Test
    fun fcp3Posting100_fcp3002_roundPreparedReadSumsTwoOriginalDocumentsWithoutMergingOrReallocating() {
        val originals = v.roundQuotes().map(StoredPricingSnapshotCodec::encode)
        val quotes = originals.map(StoredPricingSnapshotCodec::decode)
        assertEquals(listOf(v.r1Batch, v.r2Batch), quotes.map { it.snapshotId })
        assertEquals(listOf(3, 1), quotes.map { it.lines.size })
        assertOriginalDocument(quotes[0], 1_000, 141, 1_141)
        assertOriginalDocument(quotes[1], 325, 46, 371)
        assertEquals(f.egp(v.r2.gross), quotes[1].lines.single().payable)
        assertEquals(ExactRational.ratio(325, 1),
            component(quotes[1], v.r2.portionId, PriceComponentKind.ITEM_NET).exact * minorPerEgp)
        assertEquals(ExactRational.ratio(91, 2),
            component(quotes[1], v.r2.portionId, PriceComponentKind.ITEM_TAX).exact * minorPerEgp)
        assertEquals(f.egp(1_325), posted(quotes, PriceComponentKind.ITEM_NET))
        assertEquals(f.egp(187), posted(quotes, PriceComponentKind.ITEM_TAX))
        assertEquals(f.egp(1_512), payable(quotes))
        val combinedExact = quotes.fold(ExactRational.ratio(0, 1)) { total, quote -> total + quote.exactPayable }
        assertEquals(ExactRational.ratio(151_107, 100), combinedExact * minorPerEgp)
        assertEquals(f.egp(1_511), combinedExact.toMoney(Currency.EGP),
            "Frozen forbidden merged-document diagnostic; the current read must instead sum saved 1141 + 371")

        val states = v.roundStates()
        assertEquals((v.r1 + v.r2).map { it.portionId }, states.map { it.portionId })
        assertEquals(listOf(FoodPreparationStatus.NOT_SENT, FoodPreparationStatus.QUEUED,
            FoodPreparationStatus.READY, FoodPreparationStatus.PREPARING), states.map { it.status })
        assertEquals(FoodFulfillmentStatus.SERVED, states[2].fulfillment)
        assertTrue(states.filterIndexed { index, _ -> index != 2 }.all {
            it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER
        })
        for ((orderedQuotes, orderedStates) in listOf(quotes to states, quotes.reversed() to states.reversed(),
            quotes.reversed() to states)) {
            assertEquals(f.egp(1_512), FoodCurrentUnpaidPayable.projectIncludingPrepared(
                orderedQuotes, orderedStates, f.egp(1_512), null, null))
            assertNull(FoodCurrentUnpaidPayable.project(orderedQuotes, orderedStates, f.egp(1_512), null, null))
            assertEquals(originals, quotes.map(StoredPricingSnapshotCodec::encode))
        }
        // Source-list order/repeated pure reads and codec round trips are not pagination or process restart custody.
    }

    private fun component(quote: PricingSnapshot, portionId: DomainId, kind: PriceComponentKind): PostedPriceComponent =
        quote.components.single { it.portionId == portionId && it.kind == kind }

    private fun posted(quotes: List<PricingSnapshot>, kind: PriceComponentKind) = quotes.flatMap { it.components }
        .filter { it.kind == kind }.fold(f.egp(0)) { total, component -> total + component.posted }

    private fun payable(quotes: List<PricingSnapshot>) = quotes.fold(f.egp(0)) { total, quote -> total + quote.payable }

    private fun assertOriginalDocument(quote: PricingSnapshot, net: Long, tax: Long, gross: Long) {
        assertEquals(PricingProfiles.LINEAR_PROTECTED_V2, quote.profile)
        assertEquals(TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION, quote.taxPostingPolicy)
        assertEquals(Currency.EGP, quote.currency)
        assertEquals(Revision(0), quote.revision)
        assertTrue(quote.charges.isEmpty() && quote.discounts.isEmpty())
        assertEquals(f.egp(net), posted(listOf(quote), PriceComponentKind.ITEM_NET))
        assertEquals(f.egp(tax), posted(listOf(quote), PriceComponentKind.ITEM_TAX))
        assertEquals(f.egp(gross), quote.payable)
        assertEquals(quote.payable, quote.beforeDiscountPayable)
        assertEquals(quote.payable, quote.components.fold(f.egp(0)) { total, component -> total + component.posted })
        assertEquals(quote.payable, quote.lines.fold(f.egp(0)) { total, line -> total + line.payable })
        assertEquals(quote.exactPayable, quote.exactBeforeDiscountPayable)
        assertEquals(quote.exactPayable, quote.components.fold(ExactRational.ratio(0, 1)) { total, component ->
            total + component.exact
        })
        assertEquals(quote.payable, quote.exactPayable.toMoney(Currency.EGP))
        assertEquals(quote.lines.size * 2, quote.components.size)
        assertTrue(quote.components.all { it.exactBeforeDiscounts == it.exact &&
            it.postedBeforeDiscounts == it.posted && it.chargeId == null && it.posted.minor >= 0 &&
            it.posted.minor - it.exact.floorScaled(Currency.EGP.fractionDigits) in 0L..1L })
    }
}

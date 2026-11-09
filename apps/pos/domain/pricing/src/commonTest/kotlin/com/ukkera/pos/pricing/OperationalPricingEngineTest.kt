package com.ukkera.pos.pricing

import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationalPricingEngineTest {
    @Test fun hypotheticalMixedTaxServiceFixedPayableFixture() {
        val lines = listOf(line(1, "114", inclusion = TaxInclusion.INCLUSIVE, percent = "14"),
            line(2, "100", percent = "14"))
        val snapshot = OperationalPricingEngine.calculate(request(lines,
            listOf(service(lines.map { it.portionId }.toSet())), listOf(fixed(2_508, lines.map { it.portionId }))))

        assertEquals(money(25_080), snapshot.beforeDiscountPayable)
        assertEquals(money(22_572), snapshot.payable)
        assertEquals(money(2_508), snapshot.advertisedDiscount)
        assertEquals(exact("225.72"), snapshot.exactPayable)
        assertEquals(money(18_000), snapshot.total(PriceComponentKind.ITEM_NET))
        assertEquals(money(2_520), snapshot.total(PriceComponentKind.ITEM_TAX))
        assertEquals(money(1_800), snapshot.total(PriceComponentKind.CHARGE_NET))
        assertEquals(money(252), snapshot.total(PriceComponentKind.CHARGE_TAX))
        assertEquals(exact("20"), snapshot.discounts.single().allocations
            .filter { it.kind == PriceComponentKind.ITEM_NET }.map { it.exactReduction }.sumExact())
        assertEquals(exact("2.28"), snapshot.discounts.single().allocations
            .filter { it.chargeId != null }.map { it.exactReduction }.sumExact())
        assertEquals(listOf(money(11_286), money(11_286)), snapshot.lines.map { it.payable })
        assertConserves(snapshot)
    }

    @Test fun mixedInclusionDoesNotTaxInclusiveAmountsTwiceAndZeroRateIsSeparate() {
        val lines = listOf(line(1, "114", percent = "14", inclusion = TaxInclusion.INCLUSIVE),
            line(2, "100", percent = "14"), line(3, "7.5", percent = "0", inclusion = TaxInclusion.INCLUSIVE))
        val snapshot = OperationalPricingEngine.calculate(request(lines))
        assertEquals(money(23_550), snapshot.payable)
        assertEquals(money(20_750), snapshot.total(PriceComponentKind.ITEM_NET))
        assertEquals(money(2_800), snapshot.total(PriceComponentKind.ITEM_TAX))
        assertEquals(TaxInclusion.INCLUSIVE, snapshot.lines.last().inclusion)
        assertEquals(tax("0"), snapshot.lines.last().tax)
        assertConserves(snapshot)
    }

    @Test fun quantityTimesEightDigitPriceRemainsExactUntilDocumentPosting() {
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "0.12345678", quantity = "1.234567"))))
        assertEquals(exact("1.234567") * exact("0.12345678"), snapshot.exactPayable)
        assertEquals(money(15), snapshot.payable)
        assertConserves(snapshot)
    }

    @Test fun nonnegativeTaxAndServiceOverOneHundredPercentAreNotInventedLegalLimits() {
        val lines = listOf(line(1, "10", percent = "150"))
        val snapshot = OperationalPricingEngine.calculate(request(lines,
            listOf(service(setOf(id(1)), percent = "200", taxPercent = "250"))))
        assertEquals(money(9_500), snapshot.payable) // 10 + 15 + 20 + 50.
        assertConserves(snapshot)
    }

    @Test fun fixedInclusiveChargeIsUnaffectedByItemDiscount() {
        val fee = PricingCharge.Fixed(id(7_111), Revision(2), ChargeKind.DELIVERY, UnitPrice.parse(EGP, "11.4"),
            TaxInclusion.INCLUSIVE, tax("14"), ChargeRefundTreatment.EXPLICIT_ONLY)
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "100", percent = "14")),
            listOf(fee), listOf(fixed(11_400, listOf(id(1))))))
        assertEquals(money(12_540), snapshot.beforeDiscountPayable)
        assertEquals(money(1_140), snapshot.payable)
        assertEquals(money(1_000), snapshot.total(PriceComponentKind.CHARGE_NET))
        assertEquals(money(140), snapshot.total(PriceComponentKind.CHARGE_TAX))
        assertTrue(snapshot.discounts.single().allocations.filter { it.chargeId != null }.all { it.exactReduction == ZERO })
    }

    @Test fun subCentCapacityCanReducePostedPayableByOneCent() {
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "0.006")),
            adjustments = listOf(fixed(1, listOf(id(1))))))
        assertEquals(money(1), snapshot.beforeDiscountPayable)
        assertEquals(money(0), snapshot.payable)
        assertNull(snapshot.discounts.single().maximumPayableReduction)
        assertEquals(money(1), snapshot.discounts.single().capacityPayableReductionUpperBound)
        assertEquals(exact("0.006"), snapshot.discounts.single().exactPayableReduction)
        assertEquals(money(1), snapshot.discounts.single().advertisedPayableReduction)
        assertConserves(snapshot)
    }

    @Test fun maximumDependsOnFullDocumentBoundaryNotCeilingOrFloorOfCapacity() {
        val fee = PricingCharge.Fixed(id(7_111), Revision(2), ChargeKind.PACKAGING, UnitPrice.parse(EGP, "0.006"),
            TaxInclusion.EXCLUSIVE, tax(), ChargeRefundTreatment.EXPLICIT_ONLY)
        val error = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(listOf(line(1, "0.006")),
                listOf(fee), listOf(fixed(1, listOf(id(1))))))
        }
        assertEquals(PricingFailure.DISCOUNT_LIMIT_EXCEEDED, error.failure)
        assertEquals(money(0), error.maximumPayableReduction)
        val tiny = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(listOf(line(1, "0.004")),
                adjustments = listOf(fixed(1, listOf(id(1))))))
        }
        assertEquals(money(0), tiny.maximumPayableReduction)
    }

    @Test fun cappedProportionalAllocationRedistributesOnlyWithinEligibleCapacity() {
        val lines = listOf(line(1, "100", cap = limits(1_000)), line(2, "100"), line(3, "100", cap = limits(0)))
        val snapshot = OperationalPricingEngine.calculate(request(lines,
            adjustments = listOf(fixed(5_000, listOf(id(1), id(2))))))
        assertEquals(listOf(exact("90"), exact("60"), exact("100")), snapshot.lines.map { it.exactItemNet })
        assertEquals(money(25_000), snapshot.payable)
        assertEquals(money(11_000), snapshot.discounts.single().capacityPayableReductionUpperBound)
        assertConserves(snapshot)
    }

    @Test fun perAdjustmentPortionCapAndBothAmountAndPercentageLimitsApply() {
        val lines = listOf(line(1, "100"), line(2, "100"))
        val adjustment = fixed(1_500, listOf(id(1), id(2)), cap = limits(10_000, "10")).copy(
            eligiblePortions = listOf(EligibleDiscountPortion(id(1), money(100)), EligibleDiscountPortion(id(2))))
        val snapshot = OperationalPricingEngine.calculate(request(lines, adjustments = listOf(adjustment)))
        assertEquals(listOf(exact("99"), exact("86")), snapshot.lines.map { it.exactItemNet })
        val error = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(lines, adjustments = listOf(adjustment.copy(value = DiscountValue.FixedPayable(money(2_001))))))
        }
        assertNull(error.maximumPayableReduction)
        assertEquals(money(2_000), error.capacityPayableReductionUpperBound)
    }

    @Test fun separateEntriesCannotEvadeCumulativeBasketOrLineCaps() {
        val adjustments = listOf(fixed(700, listOf(id(1))),
            fixed(400, listOf(id(1)), index = 6_001, stacking = StackingPermission.EXPLICITLY_ALLOWED))
        for (request in listOf(
            request(listOf(line(1, "100")), adjustments = adjustments, cap = limits(1_000)),
            request(listOf(line(1, "100", cap = limits(1_000))), adjustments = adjustments),
        )) {
            val error = assertFailsWith<PricingRejectedException> { OperationalPricingEngine.calculate(request) }
            assertEquals(money(300), error.capacityPayableReductionUpperBound)
        }
    }

    @Test fun eachPercentageUsesCurrentBaseAndStackingMustBeExplicit() {
        val first = fixed(1_000, listOf(id(1)), stage = AdjustmentStage.RESOLVED_ITEM_PROMOTION)
        val second = fixed(0, listOf(id(1)), index = 6_001, stacking = StackingPermission.EXPLICITLY_ALLOWED).copy(
            value = DiscountValue.Percentage(PercentageRate.parse("10")))
        val request = request(listOf(line(1, "100")), adjustments = listOf(first, second)).copy(
            promotionResolution = PromotionResolution.RESOLVED_CONCRETE_PORTIONS)
        val snapshot = OperationalPricingEngine.calculate(request)
        assertEquals(money(8_100), snapshot.payable)
        assertEquals(listOf(money(1_000), money(900)), snapshot.discounts.map { it.advertisedPayableReduction })
        val error = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request.copy(adjustments = listOf(first,
                second.copy(stacking = StackingPermission.EXCLUSIVE))))
        }
        assertEquals(PricingFailure.STACKING_NOT_ALLOWED, error.failure)
        assertConserves(snapshot)
    }

    @Test fun percentRequestIsRejectedRatherThanSilentlyReducedToCap() {
        val adjustment = fixed(0, listOf(id(1)), cap = limits(1_000)).copy(value = DiscountValue.Percentage(PercentageRate.parse("20")))
        val error = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(listOf(line(1, "100")), adjustments = listOf(adjustment)))
        }
        assertEquals(money(1_000), error.capacityPayableReductionUpperBound)
    }

    @Test fun exactUnchangedComponentsAreFrozenEvenWhenHamiltonWouldReassignACent() {
        val adjustment = fixed(0, listOf(id(1))).copy(value = DiscountValue.Percentage(PercentageRate.parse("50")))
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "0.006"), line(2, "0.004")),
            adjustments = listOf(adjustment)))
        val ineligible = snapshot.discounts.single().allocations.single {
            it.portionId == id(2) && it.kind == PriceComponentKind.ITEM_NET
        }
        assertEquals(ZERO, ineligible.exactReduction)
        assertEquals(money(0), ineligible.advertisedReduction)
        assertEquals(money(0), ineligible.postingDelta)
        assertEquals(money(0), snapshot.advertisedDiscount)
        assertEquals(listOf(money(1), money(0)), snapshot.lines.map { it.payable })
        assertConserves(snapshot)
    }

    @Test fun excludedPortionCannotGainACentWhenEligiblePortionsAreDiscounted() {
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "0.018"), line(2, "0.017"), line(3, "0.016")),
            adjustments = listOf(fixed(1, listOf(id(1), id(2))))))
        assertEquals(listOf(money(2), money(2), money(1)), snapshot.lines.map { source ->
            snapshot.components.filter { it.portionId == source.portionId }.map { it.postedBeforeDiscounts }.sumMoney(EGP)
        })
        assertEquals(listOf(money(2), money(1), money(1)), snapshot.lines.map { it.payable })
        assertEquals(exact("0.018") * exact("0.025") / exact("0.035"), snapshot.lines[0].exactItemNet)
        assertConserves(snapshot)
    }

    @Test fun entirePostedDiscountCannotFallOnAnExcludedPortion() {
        val snapshot = OperationalPricingEngine.calculate(request(listOf(line(1, "0.011"), line(2, "0.012"), line(3, "0.0155")),
            adjustments = listOf(fixed(1, listOf(id(1), id(2))))))
        assertEquals(listOf(money(0), money(1), money(2)), snapshot.lines.map { it.payable })
        assertEquals(money(1), snapshot.advertisedDiscount)
        val excluded = snapshot.discounts.single().allocations.filter { it.portionId == id(3) }
        assertTrue(excluded.all { it.advertisedReduction == money(0) && it.exactReduction == ZERO })
        assertConserves(snapshot)
    }

    @Test fun feasibleDiscountsAreNotAMonotoneThresholdAndCapacityIsNotAPromise() {
        val lines = listOf(line(1, "0.016"), line(2, "0.016"), line(3, "0.007"), line(4, "0.007"), line(5, "0.007"))
        val error = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(lines, adjustments = listOf(fixed(1, listOf(id(1), id(2))))))
        }
        assertEquals(PricingFailure.POSTING_CONSTRAINT_INFEASIBLE, error.failure)
        assertNull(error.maximumPayableReduction)
        assertEquals(money(2), error.capacityPayableReductionUpperBound)
        val feasible = OperationalPricingEngine.calculate(request(lines, adjustments = listOf(fixed(2, listOf(id(1), id(2))))))
        assertEquals(listOf(money(0), money(0), money(1), money(1), money(1)), feasible.lines.map { it.payable })
        assertNull(feasible.discounts.single().maximumPayableReduction)
        assertConserves(feasible)
    }

    @Test fun sequentialDiscountsPreserveCumulativeOriginalPostedLineCaps() {
        val lines = listOf(line(1, "0.018", cap = limits(1)), line(2, "0.017", cap = limits(1)), line(3, "0.016"))
        val adjustments = listOf(fixed(1, listOf(id(1), id(2))),
            fixed(1, listOf(id(1), id(2)), index = 6_001, stacking = StackingPermission.EXPLICITLY_ALLOWED))
        val snapshot = OperationalPricingEngine.calculate(request(lines, adjustments = adjustments))
        assertEquals(listOf(money(1), money(1), money(1)), snapshot.lines.map { it.payable })
        for (portion in listOf(id(1), id(2))) {
            val reduction = snapshot.discounts.flatMap { it.allocations }.filter { it.portionId == portion }
                .map { it.advertisedReduction }.sumMoney(EGP)
            assertEquals(money(1), reduction)
        }
        val returns = snapshot.lines.map { snapshot.openReturnCursor(it.portionId).consume(it.quantity).payable }.sumMoney(EGP)
        assertEquals(snapshot.payable, returns)
        assertConserves(snapshot)
    }

    @Test fun tiesUseCanonicalPortionKeysNotInputOrLocaleOrder() {
        val lines = (1..10).map { line(it, "0.005") }
        val first = OperationalPricingEngine.calculate(request(lines))
        val permuted = OperationalPricingEngine.calculate(request(lines.reversed()))
        assertEquals(first.components, permuted.components)
        assertEquals(money(5), first.payable)
        assertEquals(List(5) { money(1) } + List(5) { money(0) }, first.lines.map { it.payable })
        assertConserves(first)
    }

    @Test fun resolvedEqualBundleHasEqualOriginalRefundablePaidAllocations() {
        val lines = listOf(line(1, "100"), line(2, "100"))
        val adjustment = fixed(10_000, listOf(id(1), id(2)), stage = AdjustmentStage.RESOLVED_ITEM_PROMOTION)
        val snapshot = OperationalPricingEngine.calculate(request(lines, adjustments = listOf(adjustment)).copy(
            promotionResolution = PromotionResolution.RESOLVED_CONCRETE_PORTIONS))
        assertEquals(money(10_000), snapshot.payable)
        assertEquals(listOf(money(5_000), money(5_000)), snapshot.lines.map { it.payable })
        assertEquals(money(5_000), snapshot.openReturnCursor(id(1)).remainingPayable)
    }

    @Test fun highestPostedValueAndLargeExactProductWorkWithoutLongIntermediateOverflow() {
        val big = line(1, "0.01", scale = 0).copy(quantity = Quantity(unit, Long.MAX_VALUE, 0))
        val snapshot = OperationalPricingEngine.calculate(request(listOf(big)))
        assertEquals(money(Long.MAX_VALUE), snapshot.payable)
        assertConserves(snapshot)
        val limit = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(listOf(big.copy(unitPrice = UnitPrice.parse(EGP, "0.01000001")))))
        }
        assertEquals(PricingFailure.ARITHMETIC_OR_POSTING_LIMIT, limit.failure)
        val boundary = line(1, "92233720368547758.07")
        assertEquals(money(Long.MAX_VALUE), OperationalPricingEngine.calculate(request(listOf(boundary, line(2, "0.004")))).payable)
        assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(listOf(boundary, line(2, "0.005"))))
        }
    }

    @Test fun unsupportedProfilesBasesAndUnresolvedCampaignsFailBeforeCollection() {
        val base = request(listOf(line(1, "100")))
        val unsupported = listOf(
            base.copy(profile = "fiscal-line-rounding/unknown"),
            base.copy(promotionResolution = PromotionResolution.UNRESOLVED_CAMPAIGNS),
            base.copy(charges = listOf(service(setOf(id(1))).copy(base = PercentageChargeBase.NET_INCLUDING_OTHER_CHARGES))),
            base.copy(charges = listOf(service(setOf(id(1))).copy(base = PercentageChargeBase.TIERED_OR_MINIMUM))),
            base.copy(adjustments = listOf(fixed(10, listOf(id(1)), stage = AdjustmentStage.RESOLVED_ITEM_PROMOTION))),
        )
        for (request in unsupported) {
            assertEquals(PricingFailure.UNSUPPORTED_CONFIGURATION,
                assertFailsWith<PricingRejectedException> { OperationalPricingEngine.calculate(request) }.failure)
        }
    }

    @Test fun operationalTaxPostingDoesNotPretendToSatisfyCoupledFiscalLineRounding() {
        val fees = listOf(7_001, 7_002).map { fee -> PricingCharge.Fixed(id(fee), Revision(1), ChargeKind.PACKAGING,
            UnitPrice.parse(EGP, "0.103"), TaxInclusion.EXCLUSIVE, tax(), ChargeRefundTreatment.EXPLICIT_ONLY) }
        val input = request(listOf(line(1, "0.036", percent = "14")), fees, listOf(fixed(1, listOf(id(1)))))
        val operational = OperationalPricingEngine.calculate(input)
        assertEquals(money(25), operational.beforeDiscountPayable)
        assertEquals(money(24), operational.payable)
        assertEquals(money(3), operational.total(PriceComponentKind.ITEM_NET))
        assertEquals(money(1), operational.total(PriceComponentKind.ITEM_TAX))
        assertEquals(money(20), operational.total(PriceComponentKind.CHARGE_NET))
        val taxComponent = operational.components.single { it.kind == PriceComponentKind.ITEM_TAX }
        assertEquals(money(0), taxComponent.exact.toMoney(EGP)) // Independent fiscal HALF_UP would require zero.
        for (unsupported in listOf(TaxPostingPolicy.INDEPENDENT_LINE_HALF_UP, TaxPostingPolicy.COUPLED_FISCAL_ADAPTER)) {
            assertEquals(PricingFailure.UNSUPPORTED_CONFIGURATION,
                assertFailsWith<PricingRejectedException> { OperationalPricingEngine.calculate(input.copy(taxPostingPolicy = unsupported)) }.failure)
            assertEquals(PricingFailure.UNSUPPORTED_CONFIGURATION,
                assertFailsWith<PricingRejectedException> {
                    OperationalPricingEngine.validateSupportedProfile(PricingProfiles.LINEAR_PROTECTED_V2, unsupported)
                }.failure)
        }
        assertConserves(operational)
    }

    @Test fun invalidSignsDuplicatesUnknownPortionsCountsAndDiscountOverHundredAreRejected() {
        val base = request(listOf(line(1, "100")))
        val invalid = listOf(
            base.copy(lines = listOf(line(1, "-1"))),
            base.copy(lines = listOf(line(1, "1", quantity = "0"))),
            base.copy(lines = base.lines + base.lines),
            base.copy(charges = listOf(service(setOf(id(99))))),
            base.copy(adjustments = listOf(fixed(-1, listOf(id(1))))),
            base.copy(adjustments = listOf(fixed(1, listOf(id(1), id(1))))),
            base.copy(adjustments = listOf(fixed(1, listOf(id(99))))),
            base.copy(adjustments = listOf(fixed(0, listOf(id(1))).copy(value = DiscountValue.Percentage(PercentageRate.parse("100.01"))))),
            base.copy(lines = (1..257).map { line(it, "1") }),
        )
        for (request in invalid) assertEquals(PricingFailure.INVALID_INPUT,
            assertFailsWith<PricingRejectedException> { OperationalPricingEngine.calculate(request) }.failure)
    }

    @Test fun noExplicitLimitsMeansNoDiscountPermission() {
        val raw = line(1, "100").copy(discountLimits = DiscountLimits.none(EGP))
        val error = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(listOf(raw), adjustments = listOf(fixed(1, listOf(id(1))))))
        }
        assertEquals(money(0), error.maximumPayableReduction)
    }

    @Test fun resultDoesNotRetainMutableInputCollectionsOrOfferMutableLists() {
        val lines = mutableListOf(line(1, "100"))
        val targets = mutableSetOf(id(1))
        val charges = mutableListOf<PricingCharge>(service(targets))
        val eligible = mutableListOf(EligibleDiscountPortion(id(1)))
        val adjustments = mutableListOf(fixed(100, listOf(id(1))).copy(eligiblePortions = eligible))
        val snapshot = OperationalPricingEngine.calculate(request(lines, charges, adjustments))
        val protectedTotal = snapshot.payable
        lines.clear(); charges.clear(); targets.clear(); eligible.clear(); adjustments.clear()
        assertEquals(1, snapshot.lines.size)
        assertEquals(listOf(id(1)), snapshot.charges.single().eligiblePortionIds)
        assertEquals(listOf(EligibleDiscountPortion(id(1))), snapshot.discounts.single().eligiblePortions)
        assertEquals(protectedTotal, snapshot.payable)
        assertFails { (snapshot.components as MutableList<PostedPriceComponent>).clear() }
        assertFails { (snapshot.charges.single().eligiblePortionIds as MutableList<*>).clear() }
        assertFalse(snapshot.components.isEmpty())
    }

    @Test fun emptyBasketHasZeroSnapshotAndCannotCreateDiscountValue() {
        val snapshot = OperationalPricingEngine.calculate(request(emptyList()))
        assertEquals(money(0), snapshot.payable)
        assertTrue(snapshot.components.isEmpty())
        val error = assertFailsWith<PricingRejectedException> {
            OperationalPricingEngine.calculate(request(emptyList(), adjustments = listOf(fixed(1, emptyList()))))
        }
        assertEquals(money(0), error.maximumPayableReduction)
    }
}

private fun PricingSnapshot.total(kind: PriceComponentKind): Money = components.filter { it.kind == kind }.map { it.posted }.sumMoney(EGP)

internal fun assertConserves(snapshot: PricingSnapshot) {
    assertEquals(snapshot.payable, snapshot.components.map { it.posted }.sumMoney(EGP))
    assertEquals(snapshot.beforeDiscountPayable, snapshot.components.map { it.postedBeforeDiscounts }.sumMoney(EGP))
    assertEquals(snapshot.exactPayable, snapshot.components.map { it.exact }.sumExact())
    assertEquals(snapshot.advertisedDiscount, snapshot.discounts.map { it.advertisedPayableReduction }.sumMoney(EGP))
    assertTrue(snapshot.components.all { it.posted.minor >= 0 && it.exact >= ZERO })
    for (discount in snapshot.discounts) {
        assertEquals(discount.beforePayable - discount.afterPayable, discount.advertisedPayableReduction)
        assertEquals(discount.advertisedPayableReduction, discount.allocations.map { it.advertisedReduction }.sumMoney(EGP))
        assertEquals(discount.advertisedPayableReduction, discount.allocations.map { it.postingDelta }.sumMoney(EGP))
        assertEquals(discount.exactPayableReduction, discount.allocations.map { it.exactReduction }.sumExact())
        assertTrue(discount.allocations.all { it.exactReduction >= ZERO && it.advertisedReduction.minor >= 0 })
        assertTrue(discount.allocations.all { it.exactReduction != ZERO || it.postingDelta.minor == 0L })
        assertTrue(discount.allocations.all { it.advertisedReduction == it.postingDelta })
        assertEquals(discount.advertisedPayableReduction.toFraction() - discount.exactPayableReduction,
            discount.allocations.map { it.roundingDifference }.sumExact())
    }
    assertTrue(snapshot.components.all { it.posted.minor <= it.postedBeforeDiscounts.minor })
    assertTrue(snapshot.components.all { it.exact != it.exactBeforeDiscounts || it.posted == it.postedBeforeDiscounts })
}

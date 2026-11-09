package com.ukkera.pos.pricing

import com.ukkera.pos.types.Code
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromotionSelectorTest {
    @Test fun comparesActualPayableReductionRatherThanNominalPercentageAndReconsidersDisjointRules() {
        val input = promoInput(listOf(line(1, "1"), line(2, "10")),
            listOf(promoRule(1, setOf(1), percent = "50"), promoRule(2, setOf(2), percent = "10")))
        val quote = PromotionSelector.quote(input)
        assertEquals(listOf(promoRuleId(2), promoRuleId(1)), quote.selected.map { it.rule.ruleId })
        assertEquals(listOf(money(100), money(50)), quote.selected.map { it.advertisedReductionWhenSelected })
        assertEquals(money(950), quote.pricing.payable)
        assertEquals(3, quote.candidateTrials)
    }

    @Test fun partialOverlapsAreRescoredOnRemainingConcretePortions() {
        val input = promoInput((1..3).map { line(it, "100") },
            listOf(promoRule(1, setOf(1, 2), minor = 1_500), promoRule(2, setOf(2, 3), percent = "10")))
        val quote = PromotionSelector.quote(input)
        assertEquals(listOf(promoRuleId(2), promoRuleId(1)), quote.selected.map { it.rule.ruleId })
        assertEquals(listOf(listOf(id(2), id(3)), listOf(id(1))), quote.selected.map { selected -> selected.paidPortions.map { it.portionId } })
        assertEquals(listOf(money(2_000), money(1_500)), quote.selected.map { it.advertisedReductionWhenSelected })
        assertEquals(money(26_500), quote.pricing.payable)
        assertEquals(3, quote.selected.flatMap { it.paidPortions }.map { it.portionId }.distinct().size)
    }

    @Test fun tenVersusSixPlusSixExplainsGreedyNotGlobalOptimization() {
        val quote = PromotionSelector.quote(promoInput(listOf(line(1, "100"), line(2, "100")), listOf(
            promoRule(1, setOf(1, 2), minor = 1_000), promoRule(2, setOf(1), minor = 600), promoRule(3, setOf(2), minor = 600))))
        assertEquals(listOf(promoRuleId(1)), quote.selected.map { it.rule.ruleId })
        assertEquals(money(19_000), quote.pricing.payable) // A=10; B+C=12 would leave 188, but is a set optimizer's alternative.
        assertTrue(600L + 600L > quote.pricing.advertisedDiscount.minor)
        assertTrue(quote.selectionExplanation.contains("not a globally greatest combined offer"))
        assertEquals(listOf(money(1_000), money(600), money(600)), quote.evaluations.filter { it.round == 0 }.map { it.comparedPostedReduction })
        assertEquals(2, quote.evaluations.count { it.reason == PromotionDecisionReason.PREVIOUSLY_CONSUMED_PORTIONS })
    }

    @Test fun priorityThenCanonicalRuleIdentityBreakPostedTiesWithoutIntegerOverflow() {
        val lower = promoRule(1, priority = Int.MIN_VALUE)
        val higher = promoRule(2, priority = Int.MAX_VALUE)
        val input = promoInput(listOf(line(1, "100")), listOf(lower, higher))
        val priority = PromotionSelector.quote(input)
        assertEquals(promoRuleId(2), priority.selected.single().rule.ruleId)
        assertTrue(priority.evaluations.any { it.reason == PromotionDecisionReason.LOWER_PRIORITY })
        val tied = input.copy(rules = listOf(higher.copy(priority = 1), lower.copy(priority = 1)))
        val canonical = PromotionSelector.quote(tied)
        assertEquals(promoRuleId(1), canonical.selected.single().rule.ruleId)
        assertTrue(canonical.evaluations.any { it.reason == PromotionDecisionReason.CANONICAL_RULE_ID_TIE_LOSS })
    }

    @Test fun everyLaterGroupCandidateUsesTheSelectedPrefixNotTheOriginalQuote() {
        val groups = listOf(promoGroup(1, compatible = setOf(2)), promoGroup(2, compatible = setOf(1)))
        val input = promoInput(listOf(line(1, "100")), listOf(promoRule(1, minor = 1_000),
            promoRule(2, percent = "10", group = 2), promoRule(3, minor = 950, group = 2)), groups)
            .copy(calculationGrants = autoStacking(1, 2))
        val quote = PromotionSelector.quote(input)
        assertEquals(listOf(promoRuleId(1), promoRuleId(3)), quote.selected.map { it.rule.ruleId })
        assertEquals(money(8_050), quote.pricing.payable)
        assertEquals(money(900), quote.evaluations.single { it.ruleId == promoRuleId(2) && it.round == 1 }.comparedPostedReduction)
        assertEquals(listOf(StackingPermission.EXCLUSIVE, StackingPermission.EXPLICITLY_ALLOWED), quote.pricing.discounts.map { it.stacking })
        assertEquals(money(9_000), PromotionSelector.quote(input.copy(calculationGrants = PromotionCalculationGrants())).pricing.payable)
    }

    @Test fun zeroReductionStillConsumesQualifiersAndDoesNotGrantAnotherAutomaticPromotion() {
        val quote = PromotionSelector.quote(promoInput(listOf(line(1, "100")),
            listOf(promoRule(1, minor = 0), promoRule(2, minor = 1_000, group = 2)), listOf(promoGroup(1), promoGroup(2))))
        assertEquals(money(10_000), quote.pricing.payable)
        assertEquals(listOf(promoRuleId(1)), quote.selected.map { it.rule.ruleId })
        assertEquals(PromotionDecisionReason.PREVIOUSLY_CONSUMED_PORTIONS, quote.evaluations.last().reason)
    }

    @Test fun manualStackingRequiresBothConfiguredAndSuppliedCalculationPermission() {
        val manual = fixed(0, listOf(id(1)), stacking = StackingPermission.EXPLICITLY_ALLOWED).copy(
            value = DiscountValue.Percentage(PercentageRate.parse("10")))
        val input = promoInput(listOf(line(1, "100")), listOf(promoRule(1))).copy(manualDiscounts = listOf(manual))
        assertPromotionFailure(PromotionFailure.STACKING_NOT_PERMITTED) { PromotionSelector.quote(input) }
        val configured = input.copy(groups = listOf(promoGroup(manual = true)))
        assertPromotionFailure(PromotionFailure.STACKING_NOT_PERMITTED) { PromotionSelector.quote(configured) }
        val supplied = configured.copy(calculationGrants = PromotionCalculationGrants(manualStackingGroupIds = setOf(promoGroupId(1))))
        assertPromotionFailure(PromotionFailure.STACKING_NOT_PERMITTED) {
            PromotionSelector.quote(supplied.copy(manualDiscounts = listOf(manual.copy(stacking = StackingPermission.EXCLUSIVE))))
        }
        val quote = PromotionSelector.quote(supplied)
        assertEquals(money(8_100), quote.pricing.payable)
        assertEquals(money(8_100), quote.selected.single().paidPortions.single().automaticallyRefundable)
        assertEquals(supplied.calculationGrants, quote.calculationGrants) // Still calculation input, not actual approval evidence.
    }

    @Test fun itemGroupsPrecedeExplicitBillGroupsAndManualAdjustments() {
        val groups = listOf(promoGroup(1), promoGroup(2, PromotionStage.BILL))
        val input = promoInput(listOf(line(1, "100"), line(2, "100")),
            listOf(promoRule(1, setOf(1)), promoRule(2, setOf(2), percent = "10", group = 2)), groups)
        val quote = PromotionSelector.quote(input)
        assertEquals(listOf(AdjustmentStage.RESOLVED_ITEM_PROMOTION, AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT), quote.pricing.discounts.map { it.stage })
        assertEquals(money(18_000), quote.pricing.payable)
        assertPromotionFailure(PromotionFailure.INVALID_INPUT) { PromotionSelector.quote(input.copy(groups = groups.reversed())) }
    }

    @Test fun exclusionsExactCodesChannelsUtcEndpointsAndMinimumsAreExplained() {
        val category = id(17_000)
        val rule = promoRule(1, percent = "10").let { source -> source.copy(eligibility = source.eligibility.copy(
            items = PromotionItemSelector(PromotionMatchMode.INCLUDED_SKU_OR_CATEGORY,
                includedSkuIds = setOf(promoSku(2)), includedCategoryIds = setOf(category), excludedSkuIds = setOf(promoSku(2))),
            channels = setOf(Code.exact("dine-in")), requiredCode = Code.exact("000SAVE"),
            window = PromotionWindow(PromotionTimeMode.UTC_HALF_OPEN_WINDOW, promoAt, UtcInstant.parseCanonical("2026-10-24T12:00:00Z")),
            minimumQuantity = Quantity.parse(unit, "1", 6), minimumSpend = money(2_000))) }
        val base = promoInput(listOf(line(1, "20"), line(2, "10"), line(3, "30")), listOf(rule))
        val input = base.copy(portions = base.portions.map { it.copy(categoryIds = if (it.portionId != id(3)) setOf(category) else emptySet()) },
            context = base.context.copy(enteredCodes = setOf(Code.exact("000SAVE"))))
        val quote = PromotionSelector.quote(input)
        assertEquals(listOf(id(1)), quote.selected.single().paidPortions.map { it.portionId })
        assertEquals(money(5_800), quote.pricing.payable)
        val variants = listOf(
            input.copy(context = input.context.copy(effectiveAt = UtcInstant.parseCanonical("2026-10-24T12:00:00Z"))) to PromotionDecisionReason.OUTSIDE_UTC_WINDOW,
            input.copy(context = input.context.copy(effectiveAt = UtcInstant.parseCanonical("2026-10-23T11:59:59Z"))) to PromotionDecisionReason.OUTSIDE_UTC_WINDOW,
            input.copy(context = input.context.copy(channel = Code.exact("delivery"))) to PromotionDecisionReason.CHANNEL_NOT_ELIGIBLE,
            input.copy(context = input.context.copy(enteredCodes = setOf(Code.exact("000save")))) to PromotionDecisionReason.CODE_NOT_ENTERED,
            input.copy(rules = listOf(rule.copy(eligibility = rule.eligibility.copy(minimumQuantity = Quantity.parse(unit, "2", 6))))) to PromotionDecisionReason.MINIMUM_QUANTITY_NOT_MET,
            input.copy(rules = listOf(rule.copy(eligibility = rule.eligibility.copy(minimumSpend = money(2_001))))) to PromotionDecisionReason.MINIMUM_SPEND_NOT_MET,
        )
        variants.forEach { (variant, reason) ->
            val notSelected = PromotionSelector.quote(variant)
            assertEquals(reason, notSelected.evaluations.single().reason)
            assertEquals(money(6_000), notSelected.pricing.payable)
        }
    }

    @Test fun explicitlyOriginalThresholdBasisDoesNotSilentlySwitchAfterAStackedDiscount() {
        val second = promoRule(2, percent = "10", group = 2).let { it.copy(eligibility = it.eligibility.copy(minimumSpend = money(10_000))) }
        val input = promoInput(listOf(line(1, "100")), listOf(promoRule(1, percent = "50"), second),
            listOf(promoGroup(1, compatible = setOf(2)), promoGroup(2, compatible = setOf(1))))
            .copy(calculationGrants = autoStacking(1, 2))
        val quote = PromotionSelector.quote(input)
        assertEquals(money(4_500), quote.pricing.payable)
        assertEquals(exact("100"), quote.evaluations.last().originalCohortMarginalGross)
        assertEquals(money(500), quote.evaluations.last().comparedPostedReduction)
    }

    @Test fun exactMeasuredQuantitiesChooseTheHighestAllUnitTierWithoutChangingStoredPrecision() {
        val tiers = listOf("1" to "10", "2" to "20", "3" to "50").map { (quantity, percent) ->
            PromotionQuantityTier(Quantity.parse(unit, quantity, 6), DiscountValue.Percentage(PercentageRate.parse(percent))) }
        val rule = promoRule(1).copy(benefit = PromotionBenefit.QuantityTiers(tiers, PromotionTierStrategy.ALL_MATCHED_UNITS_HIGHEST_THRESHOLD))
        val input = promoInput(listOf(line(1, "100", quantity = "1.2", scale = 1), line(2, "100", quantity = "0.8", scale = 6)), listOf(rule))
        val quote = PromotionSelector.quote(input)
        assertEquals(money(16_000), quote.pricing.payable)
        assertEquals(Quantity.parse(unit, "2", 6), quote.selected.single().selectedTierMinimum)
        assertEquals(listOf(1, 6), quote.pricing.lines.map { it.quantity.scale })
        val below = input.copy(pricing = input.pricing.copy(lines = listOf(line(1, "100", quantity = "0.5"))), portions =
            listOf(input.portions.first().copy(availableQuantityForQuote = Quantity.parse(unit, "0.5", 6))))
        assertEquals(PromotionDecisionReason.NO_QUALIFIED_QUANTITY_TIER, PromotionSelector.quote(below).evaluations.single().reason)
    }

    @Test fun quantityThresholdsCannotAddUnlikeUnits() {
        val sources = listOf(line(1, "100"), line(2, "100").copy(quantity = Quantity.parse(id(9_001), "1", 6)))
        val rule = promoRule(1).let { it.copy(eligibility = it.eligibility.copy(minimumQuantity = Quantity.parse(unit, "1", 6))) }
        assertPromotionFailure(PromotionFailure.INCOMPATIBLE_QUANTITY_UNITS) { PromotionSelector.quote(promoInput(sources, listOf(rule))) }
    }

    @Test fun fixedReductionAppliesOnceToItsDeclaredScopeNotOncePerQuantityUnit() {
        val quote = PromotionSelector.quote(promoInput(listOf(line(1, "10", quantity = "3")), listOf(promoRule(1, minor = 500))))
        assertEquals(money(2_500), quote.pricing.payable)
        assertEquals(Quantity.parse(unit, "3", 6), quote.selected.single().paidPortions.single().quantity)
    }

    @Test fun equalHundredBundlePreservesFiftyPerUnitAndItsDisclosureAndOriginalReturns() {
        val benefit = promoBundle(listOf(1, 2), 10_000)
        val quote = PromotionSelector.quote(promoInput((1..3).map { line(it, "100") }, listOf(promoRule(1).copy(benefit = benefit))))
        assertEquals(money(20_000), quote.pricing.payable)
        assertEquals(listOf(money(5_000), money(5_000)), quote.selected.single().paidPortions.map { it.finalPaid })
        assertEquals(listOf(money(5_000), money(5_000)), quote.selected.single().paidPortions.map { it.automaticallyRefundable })
        assertEquals(benefit.disclosure, (quote.selected.single().rule.benefit as PromotionBenefit.FixedPriceBundle).disclosure)
        val restored = StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(quote.pricing))
        assertEquals(money(5_000), restored.openReturnCursor(id(1)).consume(restored.lines.first().quantity).payable)
        assertEquals(money(5_000), restored.openReturnCursor(id(2)).remainingPayable)
        assertEquals(inputPortionIds(quote), quote.pricing.lines.map { it.portionId })
    }

    @Test fun bundleAndBuyGetRejectCapRedistributionEvenWhenV2CanPostTheCohortTotal() {
        for (benefit in listOf(promoBundle(listOf(1, 2), 10_000), promoBuyGet())) {
            for (firstCap in listOf(0L, 4_999L)) {
                val sources = listOf(line(1, "100", cap = limits(firstCap)), line(2, "100"))
                // The underlying V2 result is valid FOR GENERIC capped discounts,
                // but is not the promised equal-original bundle paid allocation.
                val generic = OperationalPricingEngine.calculate(request(sources,
                    adjustments = listOf(fixed(10_000, listOf(id(1), id(2))))))
                assertEquals(listOf(money(10_000 - firstCap), money(firstCap)), generic.lines.map { it.payable })
                val raw = promoInput(sources, listOf(promoRule(1).copy(benefit = benefit), promoRule(2, minor = 1)))
                val input = if (benefit is PromotionBenefit.BuyXGetY) raw.sameSku() else raw
                val error = assertFailsWith<PromotionRejectedException> { PromotionSelector.quote(input) }
                assertEquals(PromotionFailure.PAID_ALLOCATION_NOT_SUPPORTED, error.failure)
                assertEquals(promoRuleId(1), error.ruleId)
                assertEquals(null, error.pricingFailure) // V2 itself did not reject; the target-spread contract did.
            }
        }
    }

    @Test fun bundleAndBuyGetAcceptSufficientAsymmetricCapsAndPinTheSelectionPaidSpread() {
        for (benefit in listOf(promoBundle(listOf(1, 2), 10_000), promoBuyGet())) {
            val raw = promoInput(listOf(line(1, "100", cap = limits(5_000)), line(2, "100")),
                listOf(promoRule(1).copy(benefit = benefit)))
            val quote = PromotionSelector.quote(if (benefit is PromotionBenefit.BuyXGetY) raw.sameSku() else raw)
            val selected = quote.selected.single()
            assertEquals(listOf(money(5_000), money(5_000)), selected.paidPortions.map { it.finalPaid })
            assertEquals(listOf(id(1), id(2)), selected.targetPaidAllocations.map { it.portionId })
            assertEquals(listOf(exact("100"), exact("100")), selected.targetPaidAllocations.map { it.originalMarginalGross })
            assertEquals(listOf(money(5_000), money(5_000)), selected.targetPaidAllocations.map { it.payableWhenSelected })
            assertFails { (selected.targetPaidAllocations as MutableList<*>).clear() }
        }
    }

    @Test fun bundleTargetChecksOriginalWeightsRatherThanSilentlyUsingAnUnequalEarlierDiscountPrefix() {
        val groups = listOf(promoGroup(1, compatible = setOf(2)), promoGroup(2, compatible = setOf(1)))
        val bundle = promoRule(2, group = 2).copy(benefit = promoBundle(listOf(1, 2), 10_000))
        val input = promoInput(listOf(line(1, "100"), line(2, "100")),
            listOf(promoRule(1, setOf(1), minor = 2_000), bundle), groups).copy(calculationGrants = autoStacking(1, 2))
        val error = assertFailsWith<PromotionRejectedException> { PromotionSelector.quote(input) }
        assertEquals(PromotionFailure.PAID_ALLOCATION_NOT_SUPPORTED, error.failure)
        assertEquals(bundle.ruleId, error.ruleId) // Current80/100 would yield44.44/55.56, not the original-weighted50/50.
        val permitted = PromotionSelector.quote(input.copy(rules = listOf(promoRule(1, percent = "10"), bundle)))
        assertEquals(listOf(money(5_000), money(5_000)), permitted.selected.last().paidPortions.map { it.finalPaid })
    }

    @Test fun equalCurrentBuyGetDoesNotForgetDifferentOriginalValuesAfterEarlierStacking() {
        val category = id(17_500)
        val prior = promoRule(1, minor = 10_000).let { it.copy(eligibility = it.eligibility.copy(
            items = PromotionItemSelector(PromotionMatchMode.INCLUDED_SKU_OR_CATEGORY, includedCategoryIds = setOf(category)))) }
        val buyGet = promoRule(2, group = 2).copy(benefit = promoBuyGet())
        val raw = promoInput(listOf(line(1, "100"), line(2, "200")), listOf(prior, buyGet),
            listOf(promoGroup(1, compatible = setOf(2)), promoGroup(2, compatible = setOf(1)))).sameSku()
        val input = raw.copy(calculationGrants = autoStacking(1, 2), portions = raw.portions.map {
            it.copy(categoryIds = if (it.portionId == id(2)) setOf(category) else emptySet()) })
        val error = assertFailsWith<PromotionRejectedException> { PromotionSelector.quote(input) }
        assertEquals(PromotionFailure.PAID_ALLOCATION_NOT_SUPPORTED, error.failure)
        assertEquals(buyGet.ruleId, error.ruleId) // Current100/100 is equal, but original100/200 requires33.33/66.67.
    }

    @Test fun permittedLaterTargetedManualDiscountKeepsSelectionSpreadSeparateFromFinalReturns() {
        val manual = fixed(1_000, listOf(id(1)), stacking = StackingPermission.EXPLICITLY_ALLOWED)
        val input = promoInput(listOf(line(1, "100"), line(2, "100")),
            listOf(promoRule(1).copy(benefit = promoBundle(listOf(1, 2), 10_000))), groups = listOf(promoGroup(manual = true)))
            .copy(manualDiscounts = listOf(manual), calculationGrants = PromotionCalculationGrants(manualStackingGroupIds = setOf(promoGroupId(1))))
        val quote = PromotionSelector.quote(input)
        assertEquals(listOf(money(5_000), money(5_000)), quote.selected.single().targetPaidAllocations.map { it.payableWhenSelected })
        assertEquals(listOf(money(4_000), money(5_000)), quote.selected.single().paidPortions.map { it.finalPaid })
        assertEquals(listOf(money(4_000), money(5_000)), quote.selected.single().paidPortions.map { it.automaticallyRefundable })
        val restored = StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(quote.pricing))
        assertEquals(money(4_000), restored.openReturnCursor(id(1)).consume(restored.lines.first().quantity).payable)
        assertEquals(money(5_000), restored.openReturnCursor(id(2)).remainingPayable)
    }

    @Test fun zeroAndUnequalBundleOriginalValuesHaveExplicitDeterministicTargetAllocations() {
        for (benefit in listOf(promoBundle(listOf(1, 2), 0), promoBuyGet())) {
            val raw = promoInput(listOf(line(1, "0"), line(2, "0")), listOf(promoRule(1).copy(benefit = benefit)))
            val zero = PromotionSelector.quote(if (benefit is PromotionBenefit.BuyXGetY) raw.sameSku() else raw)
            assertEquals(listOf(money(0), money(0)), zero.selected.single().targetPaidAllocations.map { it.payableWhenSelected })
            assertEquals(listOf(exact("0"), exact("0")), zero.selected.single().targetPaidAllocations.map { it.originalMarginalGross })
        }
        val input = promoInput(listOf(line(1, "0"), line(2, "100"), line(3, "200")),
            listOf(promoRule(1).copy(benefit = promoBundle(listOf(1, 2, 3), 10_000))))
        val weighted = PromotionSelector.quote(input)
        assertEquals(listOf(money(0), money(3_333), money(6_667)), weighted.selected.single().targetPaidAllocations.map { it.payableWhenSelected })
        assertEquals(weighted.selected.single().targetPaidAllocations.map { it.payableWhenSelected }, weighted.pricing.lines.map { it.payable })
        val reversed = PromotionSelector.quote(input.copy(pricing = input.pricing.copy(lines = input.pricing.lines.reversed()), portions = input.portions.reversed()))
        assertEquals(weighted.selected.single().targetPaidAllocations, reversed.selected.single().targetPaidAllocations)
        val free = PromotionSelector.quote(input.copy(rules = listOf(promoRule(1).copy(benefit = promoBundle(listOf(1, 2, 3), 0)))))
        assertEquals(listOf(money(0), money(0), money(0)), free.selected.single().targetPaidAllocations.map { it.payableWhenSelected })
    }

    @Test fun explicitBundleGrossTargetIncludesDependentChargesButNotIndependentDelivery() {
        val delivery = PricingCharge.Fixed(id(7_800), Revision(1), ChargeKind.DELIVERY, UnitPrice.parse(EGP, "10"),
            TaxInclusion.EXCLUSIVE, tax(), ChargeRefundTreatment.EXPLICIT_ONLY)
        val quote = PromotionSelector.quote(promoInput(listOf(line(1, "114", percent = "14", inclusion = TaxInclusion.INCLUSIVE), line(2, "100", percent = "14")),
            listOf(promoRule(1).copy(benefit = promoBundle(listOf(1, 2), 22_572))), charges = listOf(service(setOf(id(1), id(2))), delivery)))
        assertEquals(money(26_080), quote.pricing.beforeDiscountPayable)
        assertEquals(money(23_572), quote.pricing.payable)
        assertEquals(money(2_508), quote.selected.single().advertisedReductionWhenSelected)
        assertEquals(listOf(money(11_286), money(11_286)), quote.selected.single().paidPortions.map { it.finalPaid })
        assertEquals(money(1_000), quote.pricing.components.filter { it.chargeId == delivery.chargeId }.map { it.posted }.sumMoney(EGP))
    }

    @Test fun incompleteBundleCannotInventStockOrAwardNonexistentUnits() {
        val benefit = promoBundle(listOf(1, 2), 10_000).copy(requirements = listOf(
            PromotionBundleRequirement(promoSku(1), unit, 2), PromotionBundleRequirement(promoSku(2), unit, 1)))
        val quote = PromotionSelector.quote(promoInput(listOf(line(1, "100"), line(2, "100")), listOf(promoRule(1).copy(benefit = benefit))))
        assertEquals(PromotionDecisionReason.INCOMPLETE_BUNDLE_OR_BUY_GET, quote.evaluations.single().reason)
        assertEquals(money(20_000), quote.pricing.payable)
        assertTrue(quote.selected.isEmpty())
    }

    @Test fun buyOneGetOneRunsOnlyItsDeclaredOneApplicationAndSharesPaidValueAcrossBothUnits() {
        val input = promoInput((1..4).map { line(it, "100") }, listOf(promoRule(1).copy(benefit = promoBuyGet()))).sameSku()
        val quote = PromotionSelector.quote(input)
        assertEquals(money(30_000), quote.pricing.payable)
        assertEquals(listOf(id(1), id(2)), quote.selected.single().paidPortions.map { it.portionId })
        assertEquals(listOf(money(5_000), money(5_000)), quote.selected.single().paidPortions.map { it.finalPaid })
        assertEquals(listOf(money(5_000), money(5_000), money(10_000), money(10_000)), quote.pricing.lines.map { it.payable })
        assertEquals(listOf(promoApplicationId(1)), quote.pricing.discounts.map { it.adjustmentId })
    }

    @Test fun buyTwoGetOneUsesExactPayForTwoTargetNotAnApproximateThirdPercentageOrZeroRefundItem() {
        val quote = PromotionSelector.quote(promoInput((1..3).map { line(it, "100") },
            listOf(promoRule(1).copy(benefit = promoBuyGet(2, 1)))).sameSku())
        assertEquals(money(20_000), quote.pricing.payable)
        assertEquals(listOf(money(6_667), money(6_667), money(6_666)), quote.pricing.lines.map { it.payable })
        val restored = StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(quote.pricing))
        val first = restored.openReturnCursor(id(1)).consume(Quantity.parse(unit, "0.333333", 6))
        val last = first.remaining.consume(Quantity.parse(unit, "0.666667", 6))
        assertEquals(money(2_222), first.payable)
        assertEquals(money(4_445), last.payable)
        assertEquals(money(0), last.remaining.remainingPayable)
    }

    @Test fun mixedPriceBuyGetAndImplicitPortionSplittingFailInsteadOfChoosingHiddenFreeUnits() {
        val rule = promoRule(1).copy(benefit = promoBuyGet())
        assertPromotionFailure(PromotionFailure.MIXED_BUY_GET_VALUES) {
            PromotionSelector.quote(promoInput(listOf(line(1, "100"), line(2, "120")), listOf(rule)).sameSku())
        }
        assertPromotionFailure(PromotionFailure.ATOMIC_PORTIONS_REQUIRED) {
            PromotionSelector.quote(promoInput(listOf(line(1, "100", quantity = "2")), listOf(rule)).sameSku())
        }
        assertPromotionFailure(PromotionFailure.ATOMIC_PORTIONS_REQUIRED) {
            PromotionSelector.quote(promoInput(listOf(line(1, "100", quantity = "0.5"), line(2, "100", quantity = "1.5")), listOf(rule)).sameSku())
        }
    }

    @Test fun ineligiblePostedPromotionBlocksRatherThanSilentlySkippingToAFeasibleAlternative() {
        val sources = listOf(line(1, "0.016"), line(2, "0.016"), line(3, "0.007"), line(4, "0.007"), line(5, "0.007"))
        val oneCent = promoRule(1, setOf(1, 2), minor = 1)
        val twoCents = promoRule(2, setOf(1, 2), minor = 2)
        val error = assertFailsWith<PromotionRejectedException> { PromotionSelector.quote(promoInput(sources, listOf(oneCent, twoCents))) }
        assertEquals(PromotionFailure.CANDIDATE_PRICING_REJECTED, error.failure)
        assertEquals(PricingFailure.POSTING_CONSTRAINT_INFEASIBLE, error.pricingFailure)
        assertEquals(oneCent.ruleId, error.ruleId)
        val feasible = PromotionSelector.quote(promoInput(sources, listOf(twoCents)))
        assertEquals(listOf(money(0), money(0), money(1), money(1), money(1)), feasible.pricing.lines.map { it.payable })
    }

    @Test fun promotionLimitsAreNotClampedAndCannotBypassOriginalLineOrBasketCaps() {
        val input = promoInput(listOf(line(1, "100", cap = limits(500))), listOf(promoRule(1, minor = 100), promoRule(2, minor = 1_000)))
        val error = assertFailsWith<PromotionRejectedException> { PromotionSelector.quote(input) }
        assertEquals(PricingFailure.DISCOUNT_LIMIT_EXCEEDED, error.pricingFailure)
        assertEquals(promoRuleId(2), error.ruleId)
        val ruleLimited = promoInput(listOf(line(1, "100")), listOf(promoRule(1).copy(limits = limits(500))))
        assertEquals(PricingFailure.DISCOUNT_LIMIT_EXCEEDED, assertFailsWith<PromotionRejectedException> { PromotionSelector.quote(ruleLimited) }.pricingFailure)
    }

    @Test fun unsupportedRulesRejectEvenWhenNotCurrentlyEligibleAndFiscalPolicyNeverFallsBack() {
        val original = promoRule(1)
        val unavailable = original.eligibility.copy(window = PromotionWindow(PromotionTimeMode.UTC_HALF_OPEN_WINDOW,
            UtcInstant.parseCanonical("2027-01-01T00:00:00Z")))
        val variants = listOf(
            original.copy(maximumApplicationsPerBasket = 2),
            original.copy(redemptionScope = PromotionRedemptionScope.BRANCH_HARD_CAP_UNSUPPORTED),
            original.copy(redemptionScope = PromotionRedemptionScope.GLOBAL_HARD_CAP_UNSUPPORTED),
            original.copy(eligibility = unavailable.copy(window = unavailable.window.copy(mode = PromotionTimeMode.LOCAL_RECURRING_UNSUPPORTED))),
            original.copy(eligibility = unavailable.copy(thresholdBasis = PromotionThresholdBasis.OTHER_UNSUPPORTED)),
            original.copy(benefit = (original.benefit as PromotionBenefit.ScopedDiscount).copy(interpretation = PromotionDiscountInterpretation.PER_UNIT_UNSUPPORTED)),
            original.copy(benefit = PromotionBenefit.QuantityTiers(listOf(PromotionQuantityTier(Quantity.parse(unit, "1", 6), DiscountValue.FixedPayable(money(1)))),
                PromotionTierStrategy.PROGRESSIVE_UNSUPPORTED)),
            original.copy(benefit = promoBundle(listOf(1), 100).copy(targetBasis = PromotionTargetBasis.ITEM_NET_ONLY_UNSUPPORTED)),
            original.copy(benefit = promoBundle(listOf(1), 100).copy(returnAllocation = PromotionReturnAllocation.REPRICE_RETAINED_PORTIONS_UNSUPPORTED)),
            original.copy(benefit = promoBuyGet().copy(strategy = PromotionBuyGetStrategy.CHEAPEST_OR_MIXED_PRICE_UNSUPPORTED)),
            original.copy(benefit = promoBuyGet().copy(returnAllocation = PromotionReturnAllocation.ZERO_REFUNDABLE_REWARD_UNSUPPORTED)),
        )
        variants.forEach { variant -> assertPromotionFailure(PromotionFailure.UNSUPPORTED_CONFIGURATION) {
            PromotionSelector.quote(promoInput(listOf(line(1, "100")), listOf(variant)))
        } }
        val input = promoInput(listOf(line(1, "100")), listOf(original))
        assertPromotionFailure(PromotionFailure.UNSUPPORTED_CONFIGURATION) { PromotionSelector.quote(input.copy(profile = "global-best/unknown")) }
        val fiscal = assertFailsWith<PromotionRejectedException> {
            PromotionSelector.quote(input.copy(pricing = input.pricing.copy(taxPostingPolicy = TaxPostingPolicy.INDEPENDENT_LINE_HALF_UP)))
        }
        assertEquals(PricingFailure.UNSUPPORTED_CONFIGURATION, fiscal.pricingFailure)
    }

    @Test fun inconsistentSourceIdentityAvailabilityAndConfigurationInputsAreRejected() {
        val input = promoInput(listOf(line(1, "100")), listOf(promoRule(1)))
        assertPromotionFailure(PromotionFailure.INSUFFICIENT_AVAILABLE_QUANTITY) {
            PromotionSelector.quote(input.copy(portions = listOf(input.portions.single().copy(availableQuantityForQuote = Quantity.parse(unit, "0", 6)))))
        }
        val invalid = listOf(
            input.copy(applicationIds = emptyMap()),
            input.copy(portions = emptyList()),
            input.copy(portions = input.portions + input.portions),
            input.copy(pricing = input.pricing.copy(promotionResolution = PromotionResolution.UNRESOLVED_CAMPAIGNS)),
            input.copy(pricing = input.pricing.copy(adjustments = listOf(fixed(1, listOf(id(1)))))),
            input.copy(groups = listOf(promoGroup(compatible = setOf(2)), promoGroup(2))),
            input.copy(manualDiscounts = listOf(fixed(1, listOf(id(1))).copy(adjustmentId = promoApplicationId(1)))),
        )
        invalid.forEach { assertPromotionFailure(PromotionFailure.INVALID_INPUT) { PromotionSelector.quote(it) } }
    }

    @Test fun boundedWorstCaseVisitsEachUnusedCandidateAndRejectsMoreThanSixteenRules() {
        val quote = PromotionSelector.quote(promoInput((1..16).map { line(it, "100") }, (1..16).map { promoRule(it, setOf(it), minor = 100) }))
        assertEquals(136, quote.candidateTrials)
        assertEquals(16, quote.selected.size)
        assertEquals(money(158_400), quote.pricing.payable)
        assertPromotionFailure(PromotionFailure.RESOURCE_LIMIT) {
            PromotionSelector.quote(promoInput((1..17).map { line(it, "100") }, (1..17).map { promoRule(it, setOf(it), minor = 100) }))
        }
        assertPromotionFailure(PromotionFailure.RESOURCE_LIMIT) {
            PromotionSelector.quote(promoInput((1..65).map { line(it, "100") }, emptyList()))
        }
    }

    @Test fun callerMutationAndInputPermutationCannotAlterProtectedTraceOrPaidAllocation() {
        val requirements = mutableListOf(PromotionBundleRequirement(promoSku(2), unit, 1), PromotionBundleRequirement(promoSku(1), unit, 1))
        val included = mutableSetOf(promoSku(1), promoSku(2))
        val rule = promoRule(1, setOf(1, 2)).let { it.copy(eligibility = it.eligibility.copy(items = it.eligibility.items.copy(includedSkuIds = included)),
            benefit = promoBundle(listOf(1, 2), 10_000).copy(requirements = requirements)) }
        val mutableRules = mutableListOf(rule)
        val input = promoInput(listOf(line(1, "100"), line(2, "100")), mutableRules)
        val quote = PromotionSelector.quote(input)
        val permuted = PromotionSelector.quote(input.copy(pricing = input.pricing.copy(lines = input.pricing.lines.reversed()),
            portions = input.portions.reversed(), rules = input.rules.reversed()))
        assertEquals(quote.pricing.components, permuted.pricing.components)
        assertEquals(quote.selected.single().paidPortions, permuted.selected.single().paidPortions)
        requirements.clear(); included.clear(); mutableRules.clear()
        assertEquals(2, (quote.selected.single().rule.benefit as PromotionBenefit.FixedPriceBundle).requirements.size)
        assertEquals(2, quote.selected.single().rule.eligibility.items.includedSkuIds.size)
        assertFails { (quote.evaluations as MutableList<*>).clear() }
        assertFails { (quote.selected.single().rule.eligibility.items.includedSkuIds as MutableSet<*>).clear() }
        assertFails { ((quote.selected.single().rule.benefit as PromotionBenefit.FixedPriceBundle).requirements as MutableList<*>).clear() }
    }
}

private fun inputPortionIds(quote: PromotionQuote) = quote.portions.map { it.portionId }
private fun assertPromotionFailure(failure: PromotionFailure, action: () -> Unit) {
    assertEquals(failure, assertFailsWith<PromotionRejectedException>(block = action).failure)
}

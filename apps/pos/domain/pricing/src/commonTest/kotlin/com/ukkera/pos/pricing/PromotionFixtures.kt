package com.ukkera.pos.pricing

import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal fun promoSku(index: Int): DomainId = id(12_000 + index)
internal fun promoGroupId(index: Int): DomainId = id(13_000 + index)
internal fun promoRuleId(index: Int): DomainId = id(14_000 + index)
internal fun promoApplicationId(index: Int): DomainId = id(15_000 + index)
internal val promoAt = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
internal fun promoGroup(index: Int = 1, stage: PromotionStage = PromotionStage.ITEM, compatible: Set<Int> = emptySet(), manual: Boolean = false): PromotionGroup =
    PromotionGroup(promoGroupId(index), stage, compatible.map(::promoGroupId).toSet(), manual)

internal fun promoRule(
    index: Int,
    skus: Set<Int>? = null,
    minor: Long = 1_000,
    percent: String? = null,
    group: Int = 1,
    priority: Int = 0,
): PromotionRule = PromotionRule(promoRuleId(index), Revision(2), promoGroupId(group), priority,
    PromotionEligibility(PromotionItemSelector(if (skus == null) PromotionMatchMode.ALL_BASKET_PORTIONS else PromotionMatchMode.INCLUDED_SKU_OR_CATEGORY,
        includedSkuIds = skus.orEmpty().map(::promoSku).toSet()), emptySet(), PromotionWindow(PromotionTimeMode.UTC_HALF_OPEN_WINDOW),
        PromotionThresholdBasis.ORIGINAL_COHORT_MARGINAL_GROSS),
    PromotionBenefit.ScopedDiscount(if (percent == null) DiscountValue.FixedPayable(money(minor)) else DiscountValue.Percentage(PercentageRate.parse(percent)),
        PromotionDiscountInterpretation.ONCE_PER_MATCHED_SCOPE), limits(), 1, PromotionRedemptionScope.BASKET_ONLY)

internal fun promoInput(
    lines: List<ResolvedPriceLine>,
    rules: List<PromotionRule>,
    groups: List<PromotionGroup> = listOf(promoGroup()),
    charges: List<PricingCharge> = emptyList(),
): PromotionSelectionRequest = PromotionSelectionRequest(
    request(lines, charges),
    lines.map { source -> PromotionPortionFacts(source.portionId, promoSku(source.portionId.value.takeLast(12).toInt()), emptySet(), source.quantity) },
    PromotionContext(Code.exact("dine-in"), promoAt, emptySet()), groups, rules,
    rules.associate { it.ruleId to promoApplicationId(it.ruleId.value.takeLast(12).toInt() - 14_000) },
    profile = PromotionProfiles.GROUP_ORDERED_GREEDY_V1,
)

internal fun promoDisclosure(): PromotionDisclosure = PromotionDisclosure(id(16_000), Revision(3),
    Code.exact("HYPOTHETICAL fixture: disclose original paid value per unit; no retained-item repricing."))

internal fun promoBundle(skus: List<Int>, minor: Long): PromotionBenefit.FixedPriceBundle = PromotionBenefit.FixedPriceBundle(
    skus.map { PromotionBundleRequirement(promoSku(it), unit, 1) }, money(minor),
    PromotionTargetBasis.POSTED_PORTION_GROSS_WITH_DEPENDENT_CHARGES,
    PromotionReturnAllocation.ORIGINAL_PAID_VALUE_ACROSS_ALL_CONSUMED_PORTIONS, promoDisclosure())

internal fun promoBuyGet(buy: Int = 1, get: Int = 1): PromotionBenefit.BuyXGetY = PromotionBenefit.BuyXGetY(
    promoSku(1), unit, buy, get, PromotionBuyGetStrategy.EQUAL_CURRENT_VALUE_SAME_SKU,
    PromotionTargetBasis.POSTED_PORTION_GROSS_WITH_DEPENDENT_CHARGES,
    PromotionReturnAllocation.ORIGINAL_PAID_VALUE_ACROSS_ALL_CONSUMED_PORTIONS, promoDisclosure())

internal fun PromotionSelectionRequest.sameSku(): PromotionSelectionRequest = copy(portions = portions.map { it.copy(skuId = promoSku(1)) })
internal fun autoStacking(first: Int, second: Int): PromotionCalculationGrants = PromotionCalculationGrants(
    automaticStackingPairs = setOf(PromotionGroupPair.between(promoGroupId(first), promoGroupId(second))))

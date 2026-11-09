package com.ukkera.pos.pricing

import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money

/** Bound every caller-owned collection before copying; no mutable input is retained. */
internal fun freezePromotionRequest(input: PromotionSelectionRequest): PromotionSelectionRequest {
    promotionRequire(input.profile == PromotionProfiles.GROUP_ORDERED_GREEDY_V1,
        PromotionFailure.UNSUPPORTED_CONFIGURATION, "Unsupported promotion selection profile")
    promotionRequire(input.pricing.adjustments.isEmpty() && input.pricing.promotionResolution == PromotionResolution.NONE,
        PromotionFailure.INVALID_INPUT, "Promotion selection requires a clean resolved base, not preselected adjustments")
    fun bound(condition: Boolean) = promotionRequire(condition, PromotionFailure.RESOURCE_LIMIT, "Promotion input exceeds its bounded profile")
    bound(input.pricing.lines.size <= PromotionProfiles.MAX_PORTIONS && input.portions.size <= PromotionProfiles.MAX_PORTIONS)
    bound(input.rules.size <= PromotionProfiles.MAX_RULES && input.groups.size <= PromotionProfiles.MAX_RULES)
    bound(input.applicationIds.size <= PromotionProfiles.MAX_RULES)
    bound(input.manualDiscounts.size <= PricingProfiles.MAX_ADJUSTMENTS && input.rules.size + input.manualDiscounts.size <= PricingProfiles.MAX_ADJUSTMENTS)
    bound(input.pricing.charges.size <= PricingProfiles.MAX_CHARGES && input.context.enteredCodes.size <= PromotionProfiles.MAX_RULES)
    bound(input.calculationGrants.automaticStackingPairs.size <= PromotionProfiles.MAX_RULES * (PromotionProfiles.MAX_RULES - 1) / 2)
    bound(input.calculationGrants.manualStackingGroupIds.size <= PromotionProfiles.MAX_RULES)
    input.groups.forEach { bound(it.compatibleGroupIds.size <= PromotionProfiles.MAX_RULES) }
    input.portions.forEach { bound(it.categoryIds.size <= PromotionProfiles.MAX_CATEGORIES_PER_PORTION) }
    input.pricing.charges.filterIsInstance<PricingCharge.Percentage>().forEach { bound(it.eligiblePortionIds.size <= PromotionProfiles.MAX_PORTIONS) }
    input.manualDiscounts.forEach { bound(it.eligiblePortions.size <= PromotionProfiles.MAX_PORTIONS) }
    input.rules.forEach { rule ->
        bound(rule.eligibility.items.includedSkuIds.size <= 256 && rule.eligibility.items.includedCategoryIds.size <= 256 &&
            rule.eligibility.items.excludedSkuIds.size <= 256 && rule.eligibility.channels.size <= 64)
        when (val benefit = rule.benefit) {
            is PromotionBenefit.QuantityTiers -> bound(benefit.tiers.size <= PromotionProfiles.MAX_RULES)
            is PromotionBenefit.FixedPriceBundle -> bound(benefit.requirements.size <= PromotionProfiles.MAX_PORTIONS)
            else -> Unit
        }
    }
    return input.copy(
        pricing = input.pricing.copy(lines = immutableList(input.pricing.lines), charges = immutableList(input.pricing.charges.map {
            if (it is PricingCharge.Percentage) it.copy(eligiblePortionIds = promotionIds(it.eligiblePortionIds)) else it
        }), adjustments = emptyList()),
        portions = immutableList(input.portions.sortedBy { it.portionId.value }.map { it.copy(categoryIds = promotionIds(it.categoryIds)) }),
        context = input.context.copy(enteredCodes = promotionCodes(input.context.enteredCodes)),
        groups = immutableList(input.groups.map { it.copy(compatibleGroupIds = promotionIds(it.compatibleGroupIds)) }),
        rules = immutableList(input.rules.sortedBy { it.ruleId.value }.map { rule -> rule.copy(
            eligibility = rule.eligibility.copy(items = rule.eligibility.items.copy(
                includedSkuIds = promotionIds(rule.eligibility.items.includedSkuIds),
                includedCategoryIds = promotionIds(rule.eligibility.items.includedCategoryIds),
                excludedSkuIds = promotionIds(rule.eligibility.items.excludedSkuIds)),
                channels = promotionCodes(rule.eligibility.channels)),
            benefit = when (val benefit = rule.benefit) {
                is PromotionBenefit.QuantityTiers -> benefit.copy(tiers = immutableList(benefit.tiers))
                is PromotionBenefit.FixedPriceBundle -> benefit.copy(requirements = immutableList(benefit.requirements.sortedBy { it.skuId.value }))
                else -> benefit
            }) }),
        applicationIds = input.applicationIds.entries.sortedBy { it.key.value }.associate { it.key to it.value },
        manualDiscounts = immutableList(input.manualDiscounts.map { it.copy(eligiblePortions = immutableList(it.eligiblePortions)) }),
        calculationGrants = input.calculationGrants.copy(
            automaticStackingPairs = promotionSet(input.calculationGrants.automaticStackingPairs.sortedWith(
                compareBy<PromotionGroupPair> { it.first.value }.thenBy { it.second.value })),
            manualStackingGroupIds = promotionIds(input.calculationGrants.manualStackingGroupIds)),
    )
}

internal fun validatePromotionRequest(input: PromotionSelectionRequest) {
    val currency = input.pricing.currency
    val groups = input.groups.associateBy { it.groupId }
    val portions = input.pricing.lines.associateBy { it.portionId }
    promotionRequire(groups.size == input.groups.size && portions.size == input.pricing.lines.size &&
        input.rules.map { it.ruleId }.distinct().size == input.rules.size,
        PromotionFailure.INVALID_INPUT, "Duplicate promotion group, rule or portion identity")
    promotionRequire(input.portions.map { it.portionId }.toSet() == portions.keys &&
        input.portions.map { it.portionId }.distinct().size == input.portions.size,
        PromotionFailure.INVALID_INPUT, "Promotion facts must identify every price portion exactly once")
    promotionRequire(input.applicationIds.keys == input.rules.map { it.ruleId }.toSet(),
        PromotionFailure.INVALID_INPUT, "Supply one stable application UUID for every rule")
    val adjustmentIds = input.applicationIds.values.toList() + input.manualDiscounts.map { it.adjustmentId }
    promotionRequire(adjustmentIds.distinct().size == adjustmentIds.size,
        PromotionFailure.INVALID_INPUT, "Promotion application and manual adjustment IDs must be distinct")
    input.portions.forEach { fact ->
        val sold = portions.getValue(fact.portionId).quantity
        promotionRequire(sold.unitId == fact.availableQuantityForQuote.unitId && sold.scale == fact.availableQuantityForQuote.scale,
            PromotionFailure.INCOMPATIBLE_QUANTITY_UNITS, "Availability must use the original portion unit and precision")
        promotionRequire(fact.availableQuantityForQuote.scaled >= sold.scaled && sold.scaled > 0,
            PromotionFailure.INSUFFICIENT_AVAILABLE_QUANTITY, "A priced portion exceeds frozen available quantity; revalidate stock")
    }
    promotionRequire(input.groups.zipWithNext().all { (first, next) -> first.stage.ordinal <= next.stage.ordinal },
        PromotionFailure.INVALID_INPUT, "Declared item groups must precede bill groups")
    input.groups.forEach { group ->
        promotionRequire(group.groupId !in group.compatibleGroupIds && group.compatibleGroupIds.all { other ->
            other in groups && group.groupId in groups.getValue(other).compatibleGroupIds
        }, PromotionFailure.INVALID_INPUT, "Cross-group compatibility must be known, distinct and mutual")
    }
    input.calculationGrants.automaticStackingPairs.forEach { pair ->
        promotionRequire(pair.first in groups && pair.second in groups && pair.second in groups.getValue(pair.first).compatibleGroupIds,
            PromotionFailure.INVALID_INPUT, "Calculation stacking inputs cannot enlarge configured compatibility")
    }
    promotionRequire(input.calculationGrants.manualStackingGroupIds.all {
        it in groups && groups.getValue(it).permitsFollowingManualDiscount
    }, PromotionFailure.INVALID_INPUT, "Calculation inputs cannot enlarge manual stacking configuration")
    promotionRequire(input.manualDiscounts.all { it.stage == AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT },
        PromotionFailure.INVALID_INPUT, "Manual adjustments follow automatic promotion groups")
    input.rules.forEach { rule ->
        promotionRequire(rule.groupId in groups, PromotionFailure.INVALID_INPUT, "Unknown promotion group", rule.ruleId)
        validatePromotionRule(rule, groups.getValue(rule.groupId), currency)
    }
}

private fun validatePromotionRule(rule: PromotionRule, group: PromotionGroup, currency: Currency) {
    fun supported(condition: Boolean, message: String) = promotionRequire(condition, PromotionFailure.UNSUPPORTED_CONFIGURATION, message, rule.ruleId)
    fun valid(condition: Boolean, message: String) = promotionRequire(condition, PromotionFailure.INVALID_INPUT, message, rule.ruleId)
    supported(rule.maximumApplicationsPerBasket == 1 && rule.redemptionScope == PromotionRedemptionScope.BASKET_ONLY,
        "Only one declared application per basket is supported; no branch/global redemption adapter exists")
    validatePromotionLimits(rule.limits, currency, rule.ruleId)
    val eligibility = rule.eligibility
    supported(eligibility.thresholdBasis == PromotionThresholdBasis.ORIGINAL_COHORT_MARGINAL_GROSS,
        "Unsupported promotion threshold basis")
    supported(eligibility.window.mode == PromotionTimeMode.UTC_HALF_OPEN_WINDOW, "Recurring/local-time windows require a separate profile")
    val start = eligibility.window.fromInclusive
    val end = eligibility.window.untilExclusive
    valid(start == null || end == null || start < end, "Promotion UTC window must be nonempty and increasing")
    when (eligibility.items.mode) {
        PromotionMatchMode.ALL_BASKET_PORTIONS -> valid(eligibility.items.includedSkuIds.isEmpty() && eligibility.items.includedCategoryIds.isEmpty(),
            "All-basket matching cannot hide ignored inclusion fields")
        PromotionMatchMode.INCLUDED_SKU_OR_CATEGORY -> valid(eligibility.items.includedSkuIds.isNotEmpty() || eligibility.items.includedCategoryIds.isNotEmpty(),
            "Included matching requires an explicit SKU/category filter")
    }
    eligibility.minimumQuantity?.let { valid(it.scaled > 0, "Minimum quantity must be positive") }
    eligibility.minimumSpend?.let { validatePromotionMoney(it, currency, rule.ruleId) }
    when (val benefit = rule.benefit) {
        is PromotionBenefit.ScopedDiscount -> {
            supported(benefit.interpretation == PromotionDiscountInterpretation.ONCE_PER_MATCHED_SCOPE,
                "Fixed/percent benefit applies once to its matched cohort; per-unit multiplication is unsupported")
            validatePromotionValue(benefit.value, currency, rule.ruleId)
        }
        is PromotionBenefit.QuantityTiers -> {
            supported(group.stage == PromotionStage.ITEM && benefit.strategy == PromotionTierStrategy.ALL_MATCHED_UNITS_HIGHEST_THRESHOLD,
                "Only explicit all-unit highest-threshold item tiers are supported")
            valid(benefit.tiers.isNotEmpty(), "Quantity tier list cannot be empty")
            val unit = benefit.tiers.first().minimumQuantity.unitId
            valid(benefit.tiers.all { it.minimumQuantity.scaled > 0 && it.minimumQuantity.unitId == unit },
                "Tier quantities must be positive in one explicit unit")
            valid(benefit.tiers.zipWithNext().all { (first, next) -> first.minimumQuantity < next.minimumQuantity },
                "Quantity tiers must have distinct increasing thresholds")
            valid(eligibility.minimumQuantity == null || eligibility.minimumQuantity.unitId == unit, "Tier and eligibility units disagree")
            benefit.tiers.forEach { validatePromotionValue(it.value, currency, rule.ruleId) }
        }
        is PromotionBenefit.FixedPriceBundle -> {
            supported(group.stage == PromotionStage.ITEM, "Bundles must precede bill/manual discounts")
            validatePaidCohortProfile(benefit.targetBasis, benefit.returnAllocation, rule.ruleId)
            valid(benefit.requirements.isNotEmpty() && benefit.requirements.map { it.skuId }.distinct().size == benefit.requirements.size,
                "Bundle must declare distinct SKU requirements")
            valid(benefit.requirements.all { it.units in 1..PromotionProfiles.MAX_PORTIONS } &&
                benefit.requirements.sumOf { it.units } <= PromotionProfiles.MAX_PORTIONS, "Bundle unit count exceeds its bounded cohort")
            valid(eligibility.minimumQuantity == null || benefit.requirements.all { it.unitId == eligibility.minimumQuantity.unitId },
                "Bundle minimum quantity cannot combine unlike units")
            validatePromotionMoney(benefit.payable, currency, rule.ruleId)
        }
        is PromotionBenefit.BuyXGetY -> {
            supported(group.stage == PromotionStage.ITEM && benefit.strategy == PromotionBuyGetStrategy.EQUAL_CURRENT_VALUE_SAME_SKU,
                "Only explicit same-SKU equal-current-value buy/get is supported")
            validatePaidCohortProfile(benefit.targetBasis, benefit.returnAllocation, rule.ruleId)
            valid(benefit.buyUnits in 1..PromotionProfiles.MAX_PORTIONS && benefit.rewardUnits in 1..PromotionProfiles.MAX_PORTIONS &&
                benefit.buyUnits + benefit.rewardUnits <= PromotionProfiles.MAX_PORTIONS, "Buy/get counts exceed their bounded cohort")
            valid(eligibility.minimumQuantity == null || eligibility.minimumQuantity.unitId == benefit.unitId,
                "Buy/get minimum quantity has another unit")
        }
    }
}

private fun validatePaidCohortProfile(basis: PromotionTargetBasis, allocation: PromotionReturnAllocation, ruleId: DomainId) {
    promotionRequire(basis == PromotionTargetBasis.POSTED_PORTION_GROSS_WITH_DEPENDENT_CHARGES &&
        allocation == PromotionReturnAllocation.ORIGINAL_PAID_VALUE_ACROSS_ALL_CONSUMED_PORTIONS,
        PromotionFailure.UNSUPPORTED_CONFIGURATION, "Unsupported bundle/buy-get target or historical paid/refund allocation", ruleId)
}

private fun validatePromotionValue(value: DiscountValue, currency: Currency, ruleId: DomainId) {
    when (value) {
        is DiscountValue.FixedPayable -> validatePromotionMoney(value.amount, currency, ruleId)
        is DiscountValue.Percentage -> promotionRequire(value.rate.toFraction() <= ONE,
            PromotionFailure.INVALID_INPUT, "Promotion percentage exceeds 100", ruleId)
    }
}

private fun validatePromotionLimits(limits: DiscountLimits, currency: Currency, ruleId: DomainId) {
    validatePromotionMoney(limits.maximumPayableReduction, currency, ruleId)
    promotionRequire(limits.maximumPercentage.toFraction() <= ONE, PromotionFailure.INVALID_INPUT, "Promotion cap percentage exceeds 100", ruleId)
}

private fun validatePromotionMoney(value: Money, currency: Currency, ruleId: DomainId) {
    promotionRequire(value.currency == currency && value.minor >= 0, PromotionFailure.INVALID_INPUT, "Invalid promotion money", ruleId)
}

internal fun promotionRequire(condition: Boolean, failure: PromotionFailure, message: String, ruleId: DomainId? = null) {
    if (!condition) throw PromotionRejectedException(failure, message, ruleId)
}

private fun promotionIds(source: Set<DomainId>): Set<DomainId> = promotionSet(source.sortedBy { it.value })
private fun promotionCodes(source: Set<Code>): Set<Code> = promotionSet(source.sortedWith(
    compareBy<Code> { it.normalization.ordinal }.thenBy { it.normalizedKey }.thenBy { it.original }))
private fun <T> promotionSet(source: List<T>): Set<T> = object : AbstractSet<T>() {
    private val entries = immutableList(source)
    override val size: Int get() = entries.size
    override fun iterator(): Iterator<T> = entries.iterator()
}

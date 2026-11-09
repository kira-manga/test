package com.ukkera.pos.pricing

import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Explicit, bounded technical choices; not all campaign semantics or a global optimizer. */
public object PromotionProfiles {
    public const val GROUP_ORDERED_GREEDY_V1: String = "group-ordered-greedy-actual-posted/v1"
    public const val MAX_RULES: Int = 16
    public const val MAX_PORTIONS: Int = 64
    public const val MAX_CANDIDATE_TRIALS: Int = 136
    public const val MAX_CATEGORIES_PER_PORTION: Int = 16
    public const val SELECTION_EXPLANATION: String =
        "Choose the greatest single currently eligible posted reduction in each declared group, " +
            "then consume its portions and reconsider remaining rules. This greedy result is not a globally greatest combined offer."
}

public enum class PromotionStage { ITEM, BILL }
public enum class PromotionMatchMode { ALL_BASKET_PORTIONS, INCLUDED_SKU_OR_CATEGORY }
public enum class PromotionTimeMode { UTC_HALF_OPEN_WINDOW, LOCAL_RECURRING_UNSUPPORTED }
public enum class PromotionThresholdBasis { ORIGINAL_COHORT_MARGINAL_GROSS, OTHER_UNSUPPORTED }
public enum class PromotionDiscountInterpretation { ONCE_PER_MATCHED_SCOPE, PER_UNIT_UNSUPPORTED }
public enum class PromotionTierStrategy { ALL_MATCHED_UNITS_HIGHEST_THRESHOLD, PROGRESSIVE_UNSUPPORTED }
public enum class PromotionTargetBasis { POSTED_PORTION_GROSS_WITH_DEPENDENT_CHARGES, ITEM_NET_ONLY_UNSUPPORTED }
public enum class PromotionBuyGetStrategy { EQUAL_CURRENT_VALUE_SAME_SKU, CHEAPEST_OR_MIXED_PRICE_UNSUPPORTED }
public enum class PromotionReturnAllocation {
    /** At selection, allocate the posted target by exact ORIGINAL gross weights, largest remainder and portion ID. */
    ORIGINAL_PAID_VALUE_ACROSS_ALL_CONSUMED_PORTIONS,
    ZERO_REFUNDABLE_REWARD_UNSUPPORTED,
    REPRICE_RETAINED_PORTIONS_UNSUPPORTED,
}
public enum class PromotionRedemptionScope { BASKET_ONLY, BRANCH_HARD_CAP_UNSUPPORTED, GLOBAL_HARD_CAP_UNSUPPORTED }

/** SKU/category expansion is already resolved by a trusted catalog adapter, not looked up here. */
public data class PromotionItemSelector(
    public val mode: PromotionMatchMode,
    public val includedSkuIds: Set<DomainId> = emptySet(),
    public val includedCategoryIds: Set<DomainId> = emptySet(),
    public val excludedSkuIds: Set<DomainId> = emptySet(),
)

/** UTC endpoints are explicit [fromInclusive, untilExclusive); both null explicitly means always. */
public data class PromotionWindow(
    public val mode: PromotionTimeMode,
    public val fromInclusive: UtcInstant? = null,
    public val untilExclusive: UtcInstant? = null,
)

public data class PromotionEligibility(
    public val items: PromotionItemSelector,
    /** Empty explicitly admits every supplied channel. */
    public val channels: Set<Code>,
    public val window: PromotionWindow,
    public val thresholdBasis: PromotionThresholdBasis,
    public val requiredCode: Code? = null,
    public val minimumQuantity: Quantity? = null,
    public val minimumSpend: Money? = null,
)

/** A receipt must render both this pinned disclosure and the actual paid allocation. Not legal approval. */
public data class PromotionDisclosure(
    public val termsId: DomainId,
    public val revision: Revision,
    public val receiptText: Code,
)

public data class PromotionBundleRequirement(
    public val skuId: DomainId,
    public val unitId: DomainId,
    public val units: Int,
)

public data class PromotionQuantityTier(public val minimumQuantity: Quantity, public val value: DiscountValue)

public sealed interface PromotionBenefit {
    /** Fixed is once over this cohort, NOT implicitly multiplied by unit count. */
    public data class ScopedDiscount(
        public val value: DiscountValue,
        public val interpretation: PromotionDiscountInterpretation,
    ) : PromotionBenefit

    public data class QuantityTiers(
        public val tiers: List<PromotionQuantityTier>,
        public val strategy: PromotionTierStrategy,
    ) : PromotionBenefit

    /** One complete explicit SKU-count bundle, choosing existing atomic portions by stable ID. */
    public data class FixedPriceBundle(
        public val requirements: List<PromotionBundleRequirement>,
        public val payable: Money,
        public val targetBasis: PromotionTargetBasis,
        public val returnAllocation: PromotionReturnAllocation,
        public val disclosure: PromotionDisclosure,
    ) : PromotionBenefit

    /** One same-SKU equal-current-gross cohort, with the target spread over ALL buy+reward units. */
    public data class BuyXGetY(
        public val skuId: DomainId,
        public val unitId: DomainId,
        public val buyUnits: Int,
        public val rewardUnits: Int,
        public val strategy: PromotionBuyGetStrategy,
        public val targetBasis: PromotionTargetBasis,
        public val returnAllocation: PromotionReturnAllocation,
        public val disclosure: PromotionDisclosure,
    ) : PromotionBenefit
}

/** Group list order is semantic. ITEM groups must precede BILL groups. */
public data class PromotionGroup(
    public val groupId: DomainId,
    public val stage: PromotionStage,
    /** Compatibility must be mutual; same-group consumption is always exclusive. */
    public val compatibleGroupIds: Set<DomainId> = emptySet(),
    public val permitsFollowingManualDiscount: Boolean = false,
)

public data class PromotionRule(
    public val ruleId: DomainId,
    public val revision: Revision,
    public val groupId: DomainId,
    /** Greater priority wins an equal ACTUAL posted reduction; canonical rule ID breaks a further tie. */
    public val priority: Int,
    public val eligibility: PromotionEligibility,
    public val benefit: PromotionBenefit,
    public val limits: DiscountLimits,
    /** This profile admits exactly one, explicitly declared application per rule/basket. */
    public val maximumApplicationsPerBasket: Int,
    public val redemptionScope: PromotionRedemptionScope,
)

public data class PromotionPortionFacts(
    public val portionId: DomainId,
    public val skuId: DomainId,
    public val categoryIds: Set<DomainId>,
    /** A frozen calculation input, NOT a reservation, stock ledger or proof against concurrent sale. */
    public val availableQuantityForQuote: Quantity,
)

public data class PromotionContext(
    public val channel: Code,
    /** Supplied trusted-side observation; this calculator never reads a clock or infers a local window. */
    public val effectiveAt: UtcInstant,
    public val enteredCodes: Set<Code>,
)

public data class PromotionGroupPair(public val first: DomainId, public val second: DomainId) {
    init { require(first.value < second.value) { "Stacking pair must contain distinct canonical ascending group IDs" } }
    public companion object {
        public fun between(first: DomainId, second: DomainId): PromotionGroupPair =
            if (first.value < second.value) PromotionGroupPair(first, second) else PromotionGroupPair(second, first)
    }
}

/**
 * Resolved calculation permissions only. Neither these values nor their names prove
 * authorization. A transactional adapter must bind actual approvals to the complete
 * actor/scope/configuration/quantity/quote digest and revalidate before collection.
 */
public data class PromotionCalculationGrants(
    public val automaticStackingPairs: Set<PromotionGroupPair> = emptySet(),
    public val manualStackingGroupIds: Set<DomainId> = emptySet(),
)

public data class PromotionSelectionRequest(
    /** Existing resolved catalog inputs only; adjustments must be empty and promotion resolution NONE. */
    public val pricing: PricingRequest,
    public val portions: List<PromotionPortionFacts>,
    public val context: PromotionContext,
    public val groups: List<PromotionGroup>,
    public val rules: List<PromotionRule>,
    /** One caller-generated, retry-stable application UUID per rule. No IDs or portions are invented here. */
    public val applicationIds: Map<DomainId, DomainId>,
    public val manualDiscounts: List<PriceAdjustment> = emptyList(),
    public val calculationGrants: PromotionCalculationGrants = PromotionCalculationGrants(),
    /** Must be explicitly opted into; no universal/default campaign evaluation semantics are inferred. */
    public val profile: String,
)

public enum class PromotionDecisionReason {
    SELECTED,
    LOWER_POSTED_REDUCTION,
    LOWER_PRIORITY,
    CANONICAL_RULE_ID_TIE_LOSS,
    OUTSIDE_UTC_WINDOW,
    CHANNEL_NOT_ELIGIBLE,
    CODE_NOT_ENTERED,
    NO_MATCHING_PORTIONS,
    PREVIOUSLY_CONSUMED_PORTIONS,
    INCOMPLETE_BUNDLE_OR_BUY_GET,
    MINIMUM_QUANTITY_NOT_MET,
    MINIMUM_SPEND_NOT_MET,
    NO_QUALIFIED_QUANTITY_TIER,
}

public class PromotionEvaluation internal constructor(
    public val round: Int,
    public val groupId: DomainId,
    public val ruleId: DomainId,
    public val reason: PromotionDecisionReason,
    portionIds: List<DomainId>,
    public val comparedPostedReduction: Money?,
    public val originalCohortMarginalGross: ExactRational?,
    public val selectedTierMinimum: Quantity?,
    public val winnerRuleId: DomainId?,
) {
    public val portionIds: List<DomainId> = immutableList(portionIds)
}

public data class PromotionPaidPortion internal constructor(
    public val portionId: DomainId,
    public val quantity: Quantity,
    public val finalPaid: Money,
    /** EXPLICIT_ONLY/performed/fixed charges are not smuggled into automatic refunds. */
    public val automaticallyRefundable: Money,
)

/** Required posted bundle/buy-get spread, verified against V2 at this promotion's selection step. */
public data class PromotionTargetPaidPortion internal constructor(
    public val portionId: DomainId,
    public val originalMarginalGross: ExactRational,
    /** Later separately permitted adjustments may change the FINAL historical paid value. */
    public val payableWhenSelected: Money,
)

public class SelectedPromotion internal constructor(
    public val rule: PromotionRule,
    public val applicationId: DomainId,
    public val advertisedReductionWhenSelected: Money,
    public val selectedTierMinimum: Quantity?,
    targetPaidAllocations: List<PromotionTargetPaidPortion>,
    paidPortions: List<PromotionPaidPortion>,
) {
    /** Empty for scoped discounts/tiers. Not a second amount to refund after later discounts. */
    public val targetPaidAllocations: List<PromotionTargetPaidPortion> = immutableList(targetPaidAllocations)
    /** Final paid/refundable amounts after ALL selected promotions and permitted manual discounts. */
    public val paidPortions: List<PromotionPaidPortion> = immutableList(paidPortions)
}

/**
 * Immutable calculated preview, not authorization, stock consumption, a redemption
 * ledger, fiscal qualification or a durable sale. Persist its full rule/context/trace
 * alongside pricing; serializing pricing alone does not preserve selection evidence.
 */
public class PromotionQuote internal constructor(
    public val selectorProfile: String,
    public val pricing: PricingSnapshot,
    public val context: PromotionContext,
    public val calculationGrants: PromotionCalculationGrants,
    groups: List<PromotionGroup>,
    rules: List<PromotionRule>,
    portions: List<PromotionPortionFacts>,
    selected: List<SelectedPromotion>,
    evaluations: List<PromotionEvaluation>,
    manualDiscounts: List<PriceAdjustment>,
    public val candidateTrials: Int,
) {
    public val selectionExplanation: String get() = PromotionProfiles.SELECTION_EXPLANATION
    public val groups: List<PromotionGroup> = immutableList(groups)
    public val rules: List<PromotionRule> = immutableList(rules)
    public val portions: List<PromotionPortionFacts> = immutableList(portions)
    public val selected: List<SelectedPromotion> = immutableList(selected)
    public val evaluations: List<PromotionEvaluation> = immutableList(evaluations)
    public val manualDiscounts: List<PriceAdjustment> = immutableList(manualDiscounts)
}

public enum class PromotionFailure {
    INVALID_INPUT,
    UNSUPPORTED_CONFIGURATION,
    INSUFFICIENT_AVAILABLE_QUANTITY,
    INCOMPATIBLE_QUANTITY_UNITS,
    ATOMIC_PORTIONS_REQUIRED,
    MIXED_BUY_GET_VALUES,
    PROMOTION_WOULD_INCREASE_PRICE,
    PAID_ALLOCATION_NOT_SUPPORTED,
    STACKING_NOT_PERMITTED,
    CANDIDATE_PRICING_REJECTED,
    RESOURCE_LIMIT,
    ARITHMETIC_LIMIT,
}

public class PromotionRejectedException(
    public val failure: PromotionFailure,
    message: String,
    public val ruleId: DomainId? = null,
    public val pricingFailure: PricingFailure? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

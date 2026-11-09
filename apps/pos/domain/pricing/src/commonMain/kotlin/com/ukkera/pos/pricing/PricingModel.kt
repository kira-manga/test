package com.ukkera.pos.pricing

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlinx.serialization.Serializable

/** An operational arithmetic contract, NOT an Egyptian fiscal certification. */
public object PricingProfiles {
    public const val LINEAR_PROTECTED_V2: String = "linear-protected-components-half-up-lr/v2"
    public const val STORED_RETURN_V1: String = "stored-component-cumulative-floor/v1"
    public const val MAX_PORTIONS: Int = 256
    public const val MAX_CHARGES: Int = 32
    public const val MAX_ADJUSTMENTS: Int = 32
    public const val MAX_COMPONENTS: Int = 10_000
}

@Serializable
public enum class TaxInclusion { INCLUSIVE, EXCLUSIVE }
@Serializable
public enum class TaxPostingPolicy {
    OPERATIONAL_COMPONENT_ALLOCATION,
    INDEPENDENT_LINE_HALF_UP,
    COUPLED_FISCAL_ADAPTER,
}

/** Inclusion is deliberately not part of the category/rate decision. */
@Serializable
public data class TaxSnapshot(
    public val policyId: DomainId,
    public val revision: Revision,
    public val categoryCode: String,
    public val rate: PercentageRate,
)

/**
 * Both limits are explicit. A missing human permission is not an unlimited cap.
 * The caller supplies the intersection of applicable authorization/company limits;
 * this calculator does not authenticate the caller or reserve campaign rights.
 */
@Serializable
public data class DiscountLimits(
    public val maximumPayableReduction: Money,
    public val maximumPercentage: PercentageRate,
) {
    public companion object {
        public fun none(currency: Currency): DiscountLimits =
            DiscountLimits(Money(currency, 0), PercentageRate.parse("0"))
    }
}

/** Already selected effective price, UOM and applicable tax, not a catalog lookup. */
public data class ResolvedPriceLine(
    public val lineId: DomainId,
    public val portionId: DomainId,
    public val priceRevision: Revision,
    public val quantity: Quantity,
    public val unitPrice: UnitPrice,
    public val inclusion: TaxInclusion,
    public val tax: TaxSnapshot,
    public val discountLimits: DiscountLimits = DiscountLimits.none(unitPrice.currency),
)

@Serializable
public enum class ChargeKind { SERVICE, DELIVERY, PACKAGING, OTHER }

@Serializable
public enum class ChargeRefundTreatment {
    /** Only available for charges allocated to concrete item portions. */
    PROPORTIONAL_WITH_ITEMS,
    /** A separate authorized performance/reason decision is required. */
    EXPLICIT_ONLY,
    /** Not part of the calculator's automatic item-return entitlement. */
    NEVER,
}

/** Unsupported values are representable so configuration cannot silently fall back. */
public enum class PercentageChargeBase {
    DISCOUNTED_ITEM_NET,
    ITEM_GROSS,
    NET_INCLUDING_OTHER_CHARGES,
    TIERED_OR_MINIMUM,
}

public sealed interface PricingCharge {
    public val chargeId: DomainId
    public val revision: Revision
    public val kind: ChargeKind
    public val tax: TaxSnapshot
    public val refundTreatment: ChargeRefundTreatment

    /** Exact fixed amount; independent of item discount and quantity. */
    public data class Fixed(
        override val chargeId: DomainId,
        override val revision: Revision,
        override val kind: ChargeKind,
        public val amount: UnitPrice,
        public val inclusion: TaxInclusion,
        override val tax: TaxSnapshot,
        override val refundTreatment: ChargeRefundTreatment,
    ) : PricingCharge

    /** Rate is a typed percentage, e.g. parse("10") means ten percent. */
    public data class Percentage(
        override val chargeId: DomainId,
        override val revision: Revision,
        override val kind: ChargeKind,
        public val rate: PercentageRate,
        public val eligiblePortionIds: Set<DomainId>,
        override val tax: TaxSnapshot,
        override val refundTreatment: ChargeRefundTreatment,
        public val base: PercentageChargeBase = PercentageChargeBase.DISCOUNTED_ITEM_NET,
    ) : PricingCharge
}

@Serializable
public enum class AdjustmentStage { RESOLVED_ITEM_PROMOTION, ORDER_OR_MANUAL_DISCOUNT }
@Serializable
public enum class StackingPermission { EXCLUSIVE, EXPLICITLY_ALLOWED }
@Serializable
public enum class PromotionResolution { NONE, RESOLVED_CONCRETE_PORTIONS, UNRESOLVED_CAMPAIGNS }

@Serializable
public sealed interface DiscountValue {
    /** The before/after POSTED payable must differ by exactly this amount. */
    @Serializable
    public data class FixedPayable(public val amount: Money) : DiscountValue

    /** Applied to the CURRENT eligible marginal payable, not fixed charges. */
    @Serializable
    public data class Percentage(public val rate: PercentageRate) : DiscountValue
}

@Serializable
public data class EligibleDiscountPortion(
    public val portionId: DomainId,
    /** Optional additional cap, never a substitute for required line/basket limits. */
    public val maximumPayableReduction: Money? = null,
)

/**
 * Concrete, selected adjustments in the declared calculation order. Campaign
 * selection/BOGO consumption/global rights must be resolved before this boundary.
 */
public data class PriceAdjustment(
    public val adjustmentId: DomainId,
    public val ruleRevision: Revision,
    public val stage: AdjustmentStage,
    public val value: DiscountValue,
    public val eligiblePortions: List<EligibleDiscountPortion>,
    public val limits: DiscountLimits,
    public val stacking: StackingPermission = StackingPermission.EXCLUSIVE,
)

public data class PricingRequest(
    public val snapshotId: DomainId,
    public val revision: Revision,
    public val currency: Currency,
    public val lines: List<ResolvedPriceLine>,
    public val charges: List<PricingCharge> = emptyList(),
    public val adjustments: List<PriceAdjustment> = emptyList(),
    /** Cumulative across ALL adjustments, so splitting entries cannot bypass it. */
    public val discountLimits: DiscountLimits = DiscountLimits.none(currency),
    public val profile: String = PricingProfiles.LINEAR_PROTECTED_V2,
    public val promotionResolution: PromotionResolution = PromotionResolution.NONE,
    public val taxPostingPolicy: TaxPostingPolicy = TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION,
)

public enum class PricingFailure {
    INVALID_INPUT,
    UNSUPPORTED_CONFIGURATION,
    POSTING_CONSTRAINT_INFEASIBLE,
    DISCOUNT_LIMIT_EXCEEDED,
    STACKING_NOT_ALLOWED,
    ARITHMETIC_OR_POSTING_LIMIT,
}

public class PricingRejectedException(
    public val failure: PricingFailure,
    message: String,
    /** An attainable maximum is not inferred from a capacity bound or monotonicity. */
    public val maximumPayableReduction: Money? = null,
    /** Necessary capacity bound only: some smaller reductions can be infeasible. */
    public val capacityPayableReductionUpperBound: Money? = null,
) : IllegalArgumentException(message)

@Serializable
public enum class PriceComponentKind { ITEM_NET, ITEM_TAX, CHARGE_NET, CHARGE_TAX }

/**
 * Final positive component posting. Discount allocations are explanatory records;
 * do not subtract them again from these already-discounted amounts.
 */
@Serializable
public data class PostedPriceComponent internal constructor(
    public val key: String,
    public val lineId: DomainId?,
    public val portionId: DomainId?,
    public val chargeId: DomainId?,
    public val kind: PriceComponentKind,
    public val tax: TaxSnapshot,
    public val refundTreatment: ChargeRefundTreatment,
    public val exactBeforeDiscounts: ExactRational,
    public val exact: ExactRational,
    public val postedBeforeDiscounts: Money,
    public val posted: Money,
)

@Serializable
public data class PricedLineSnapshot internal constructor(
    public val lineId: DomainId,
    public val portionId: DomainId,
    public val priceRevision: Revision,
    public val quantity: Quantity,
    public val unitPrice: UnitPrice,
    public val inclusion: TaxInclusion,
    public val tax: TaxSnapshot,
    public val discountLimits: DiscountLimits,
    public val exactOriginalItemNet: ExactRational,
    public val exactItemNet: ExactRational,
    /** Includes the percentage charge components assigned to this portion. */
    public val payable: Money,
)

/** Resolved charge definition copied, with no caller-owned mutable collections. */
public class ChargeRuleSnapshot internal constructor(
    public val chargeId: DomainId,
    public val revision: Revision,
    public val kind: ChargeKind,
    public val tax: TaxSnapshot,
    public val refundTreatment: ChargeRefundTreatment,
    public val fixedAmount: UnitPrice?,
    public val inclusion: TaxInclusion,
    public val percentage: PercentageRate?,
    eligiblePortionIds: List<DomainId>,
) {
    public val eligiblePortionIds: List<DomainId> = immutableList(eligiblePortionIds)
}

@Serializable
public data class DiscountComponentAllocation internal constructor(
    public val key: String,
    public val portionId: DomainId?,
    public val chargeId: DomainId?,
    public val kind: PriceComponentKind,
    public val exactBefore: ExactRational,
    public val exactAfter: ExactRational,
    public val exactReduction: ExactRational,
    /** Actual nonnegative before-minus-after posted reduction; approval/cap usage. */
    public val advertisedReduction: Money,
    /** Equal to advertisedReduction in v2; an excluded component has zero delta. */
    public val postingDelta: Money,
    /** Posted reduction minus exact reduction, NOT a second discount or posting. */
    public val roundingDifference: ExactRational,
)

public class AppliedDiscountSnapshot internal constructor(
    public val adjustmentId: DomainId,
    public val ruleRevision: Revision,
    public val stage: AdjustmentStage,
    public val value: DiscountValue,
    public val limits: DiscountLimits,
    public val stacking: StackingPermission,
    eligiblePortions: List<EligibleDiscountPortion>,
    /** Null means no attainable maximum has been qualified; do not offer it as a threshold. */
    public val maximumPayableReduction: Money?,
    public val capacityPayableReductionUpperBound: Money,
    public val exactEligiblePayable: ExactRational,
    public val exactAvailablePayableReduction: ExactRational,
    public val exactPayableReduction: ExactRational,
    public val advertisedPayableReduction: Money,
    public val beforePayable: Money,
    public val afterPayable: Money,
    allocations: List<DiscountComponentAllocation>,
) {
    public val eligiblePortions: List<EligibleDiscountPortion> = immutableList(eligiblePortions)
    public val allocations: List<DiscountComponentAllocation> = immutableList(allocations)
}

/**
 * Factory-only immutable protected result. Copy these exact bases and postings
 * into the durable sale; retaining a mutable catalog reference is not sufficient.
 * This in-memory calculation result is not evidence of a durable commit.
 */
public class PricingSnapshot internal constructor(
    public val snapshotId: DomainId,
    public val revision: Revision,
    public val currency: Currency,
    public val profile: String,
    public val taxPostingPolicy: TaxPostingPolicy,
    public val promotionResolution: PromotionResolution,
    public val discountLimits: DiscountLimits,
    public val beforeDiscountPayable: Money,
    public val payable: Money,
    public val exactBeforeDiscountPayable: ExactRational,
    public val exactPayable: ExactRational,
    lines: List<PricedLineSnapshot>,
    charges: List<ChargeRuleSnapshot>,
    components: List<PostedPriceComponent>,
    discounts: List<AppliedDiscountSnapshot>,
) {
    public val lines: List<PricedLineSnapshot> = immutableList(lines)
    public val charges: List<ChargeRuleSnapshot> = immutableList(charges)
    public val components: List<PostedPriceComponent> = immutableList(components)
    public val discounts: List<AppliedDiscountSnapshot> = immutableList(discounts)

    public val advertisedDiscount: Money get() = beforeDiscountPayable - payable

    /** The caller authorizes any EXPLICIT_ONLY charge decision against performance. */
    public fun openReturnCursor(
        portionId: DomainId,
        explicitlyRefundableChargeIds: Set<DomainId> = emptySet(),
    ): StoredReturnCursor = StoredReturnCursor.start(this, portionId, explicitlyRefundableChargeIds)
}

/** Read-only even after a JVM collection cast; input and backing storage do not escape. */
internal fun <T> immutableList(source: Collection<T>): List<T> = object : AbstractList<T>() {
    private val entries = source.toList()
    override val size: Int get() = entries.size
    override fun get(index: Int): T = entries[index]
}

package com.ukkera.pos.pricing

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity

/** Pure bounded selection over frozen facts. Every candidate posts through the reviewed V2 engine. */
public object PromotionSelector {
    public fun quote(input: PromotionSelectionRequest): PromotionQuote = try {
        select(freezePromotionRequest(input))
    } catch (failure: ArithmeticException) {
        throw PromotionRejectedException(PromotionFailure.ARITHMETIC_LIMIT, "Exact promotion arithmetic exceeded its bound", cause = failure)
    }

    private fun select(input: PromotionSelectionRequest): PromotionQuote {
        validatePromotionRequest(input)
        val groups = input.groups.associateBy { it.groupId }
        val lines = input.pricing.lines.associateBy { it.portionId }
        val original = calculatePromotionPricing(input.pricing, null)
        val originalGross = portionExacts(original)
        var current = original
        val selected = mutableListOf<PromotionTrial>()
        val adjustments = mutableListOf<PriceAdjustment>()
        val owners = mutableMapOf<DomainId, MutableSet<DomainId>>()
        val evaluations = mutableListOf<PromotionEvaluation>()
        var trials = 0
        var round = 0
        for (group in input.groups) {
            val unused = input.rules.filter { it.groupId == group.groupId }.toMutableList()
            while (unused.isNotEmpty()) {
                // Trials retain light explanation data, not N copies of the full
                // prefix snapshot. Only the current best posting must survive.
                val candidates = mutableListOf<PromotionTrial>()
                var best: PromotionTrial? = null
                var bestPricing: PricingSnapshot? = null
                val currentGross = portionExacts(current)
                val currentLines = current.lines.associateBy { it.portionId }
                for (rule in unused) {
                    when (val candidate = buildPromotionCandidate(input, rule, lines, originalGross, currentGross, currentLines, owners)) {
                        is PromotionCandidate.NotEligible -> evaluations += PromotionEvaluation(round, group.groupId, rule.ruleId,
                            candidate.reason, candidate.portions, null, candidate.originalGross, candidate.selectedTier, null)
                        is PromotionCandidate.Eligible -> {
                            promotionRequire(trials < PromotionProfiles.MAX_CANDIDATE_TRIALS, PromotionFailure.RESOURCE_LIMIT,
                                "Promotion candidate trial budget exhausted", rule.ruleId)
                            trials++
                            val stacking = if (candidate.portions.any { !owners[it].isNullOrEmpty() })
                                StackingPermission.EXPLICITLY_ALLOWED else StackingPermission.EXCLUSIVE
                            val adjustment = PriceAdjustment(input.applicationIds.getValue(rule.ruleId), rule.revision,
                                if (group.stage == PromotionStage.ITEM) AdjustmentStage.RESOLVED_ITEM_PROMOTION else AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT,
                                candidate.value, candidate.portions.map { EligibleDiscountPortion(it) }, rule.limits, stacking)
                            val priced = calculatePromotionPricing(withPromotionAdjustments(input, adjustments + adjustment, adjustments.size + 1), rule.ruleId)
                            val reduction = current.payable - priced.payable
                            check(reduction.minor >= 0)
                            candidate.requiredCohortPayable?.let { target ->
                                val posted = priced.lines.associate { it.portionId to it.payable }
                                check(candidate.portions.map { posted.getValue(it) }.sumMoney(input.pricing.currency) == target) {
                                    "V2 did not preserve the explicit bundle/buy-get cohort target"
                                }
                                // V2's capped water filling can satisfy the total but
                                // shift a bundle's paid value onto the wrong units.
                                // Decline that combination; never rewrite V2 postings.
                                promotionRequire(candidate.requiredPaidSpread.all { posted.getValue(it.portionId) == it.payableWhenSelected },
                                    PromotionFailure.PAID_ALLOCATION_NOT_SUPPORTED,
                                    "V2 cannot preserve the declared original-gross-weighted bundle/buy-get paid spread under these caps, prior adjustments or component postings",
                                    rule.ruleId)
                            }
                            val trial = PromotionTrial(rule, candidate, adjustment, reduction)
                            candidates += trial
                            val previous = best
                            if (previous == null || promotionTrialOrder.compare(trial, previous) < 0) {
                                best = trial
                                bestPricing = priced
                            }
                        }
                    }
                }
                val winner = best ?: break
                for (candidate in candidates) {
                    val reason = when {
                        candidate.rule.ruleId == winner.rule.ruleId -> PromotionDecisionReason.SELECTED
                        candidate.reduction.minor < winner.reduction.minor -> PromotionDecisionReason.LOWER_POSTED_REDUCTION
                        candidate.rule.priority < winner.rule.priority -> PromotionDecisionReason.LOWER_PRIORITY
                        else -> PromotionDecisionReason.CANONICAL_RULE_ID_TIE_LOSS
                    }
                    evaluations += PromotionEvaluation(round, group.groupId, candidate.rule.ruleId, reason, candidate.plan.portions,
                        candidate.reduction, candidate.plan.originalGross, candidate.plan.selectedTier, winner.rule.ruleId)
                }
                adjustments += winner.adjustment
                selected += winner
                current = requireNotNull(bestPricing)
                // Consume qualifiers AND rewards, even for a zero-money promotion.
                winner.plan.portions.forEach { owners.getOrPut(it) { mutableSetOf() } += group.groupId }
                unused.removeAll { it.ruleId == winner.rule.ruleId }
                round++
            }
        }
        for (manual in input.manualDiscounts) {
            val affectedGroups = manual.eligiblePortions.flatMap { owners[it.portionId].orEmpty() }.toSet()
            promotionRequire(affectedGroups.isEmpty() || (manual.stacking == StackingPermission.EXPLICITLY_ALLOWED &&
                affectedGroups.all { groups.getValue(it).permitsFollowingManualDiscount && it in input.calculationGrants.manualStackingGroupIds }),
                PromotionFailure.STACKING_NOT_PERMITTED, "Manual-on-promotion discount needs explicit configured and supplied calculation permission")
        }
        val final = if (input.manualDiscounts.isEmpty()) current else
            calculatePromotionPricing(withPromotionAdjustments(input, adjustments + input.manualDiscounts, adjustments.size), null)
        val finalLines = final.lines.associateBy { it.portionId }
        val applied = selected.map { trial -> SelectedPromotion(trial.rule, trial.adjustment.adjustmentId, trial.reduction, trial.plan.selectedTier,
            trial.plan.requiredPaidSpread,
            trial.plan.portions.map { id ->
                val line = finalLines.getValue(id)
                PromotionPaidPortion(id, line.quantity, line.payable, final.openReturnCursor(id).remainingPayable)
            }) }
        return PromotionQuote(input.profile, final, input.context, input.calculationGrants, input.groups, input.rules, input.portions,
            applied, evaluations, input.manualDiscounts, trials)
    }
}

private data class PromotionTrial(
    val rule: PromotionRule,
    val plan: PromotionCandidate.Eligible,
    val adjustment: PriceAdjustment,
    val reduction: Money,
)

private val promotionTrialOrder: Comparator<PromotionTrial> = compareByDescending<PromotionTrial> { it.reduction.minor }
    .thenByDescending { it.rule.priority }.thenBy { it.rule.ruleId.value }

private sealed interface PromotionCandidate {
    data class NotEligible(
        val reason: PromotionDecisionReason,
        val portions: List<DomainId> = emptyList(),
        val originalGross: ExactRational? = null,
        val selectedTier: Quantity? = null,
    ) : PromotionCandidate
    data class Eligible(
        val portions: List<DomainId>,
        val originalGross: ExactRational,
        val value: DiscountValue,
        val selectedTier: Quantity? = null,
        val requiredCohortPayable: Money? = null,
        val requiredPaidSpread: List<PromotionTargetPaidPortion> = emptyList(),
    ) : PromotionCandidate
}

private fun buildPromotionCandidate(
    input: PromotionSelectionRequest,
    rule: PromotionRule,
    lines: Map<DomainId, ResolvedPriceLine>,
    originalGross: Map<DomainId, ExactRational>,
    currentGross: Map<DomainId, ExactRational>,
    currentLines: Map<DomainId, PricedLineSnapshot>,
    owners: Map<DomainId, Set<DomainId>>,
): PromotionCandidate {
    val eligibility = rule.eligibility
    val time = input.context.effectiveAt
    if ((eligibility.window.fromInclusive?.let { time < it } == true) || (eligibility.window.untilExclusive?.let { time >= it } == true)) {
        return PromotionCandidate.NotEligible(PromotionDecisionReason.OUTSIDE_UTC_WINDOW)
    }
    if (eligibility.channels.isNotEmpty() && input.context.channel !in eligibility.channels) {
        return PromotionCandidate.NotEligible(PromotionDecisionReason.CHANNEL_NOT_ELIGIBLE)
    }
    if (eligibility.requiredCode != null && eligibility.requiredCode !in input.context.enteredCodes) {
        return PromotionCandidate.NotEligible(PromotionDecisionReason.CODE_NOT_ENTERED)
    }
    val matching = input.portions.filter { fact -> matchesPromotionItems(eligibility.items, fact) && when (val benefit = rule.benefit) {
        is PromotionBenefit.FixedPriceBundle -> benefit.requirements.any { it.skuId == fact.skuId }
        is PromotionBenefit.BuyXGetY -> benefit.skuId == fact.skuId
        else -> true
    } }
    if (matching.isEmpty()) return PromotionCandidate.NotEligible(PromotionDecisionReason.NO_MATCHING_PORTIONS)
    val available = matching.filter { fact -> owners[fact.portionId].orEmpty().all { owner ->
        owner != rule.groupId && PromotionGroupPair.between(owner, rule.groupId) in input.calculationGrants.automaticStackingPairs
    } }
    if (available.isEmpty()) return PromotionCandidate.NotEligible(PromotionDecisionReason.PREVIOUSLY_CONSUMED_PORTIONS, matching.map { it.portionId })
    val cohort = when (val benefit = rule.benefit) {
        is PromotionBenefit.FixedPriceBundle -> {
            val picked = mutableListOf<DomainId>()
            for (requirement in benefit.requirements) {
                val eligible = available.filter { it.skuId == requirement.skuId }
                eligible.forEach { requireAtomicPromotionPortion(lines.getValue(it.portionId), requirement.unitId, rule.ruleId) }
                if (eligible.size < requirement.units) return PromotionCandidate.NotEligible(
                    PromotionDecisionReason.INCOMPLETE_BUNDLE_OR_BUY_GET, available.map { it.portionId })
                picked += eligible.take(requirement.units).map { it.portionId }
            }
            picked.sortedBy { it.value }
        }
        is PromotionBenefit.BuyXGetY -> {
            available.forEach { requireAtomicPromotionPortion(lines.getValue(it.portionId), benefit.unitId, rule.ruleId) }
            val count = benefit.buyUnits + benefit.rewardUnits
            if (available.size < count) return PromotionCandidate.NotEligible(
                PromotionDecisionReason.INCOMPLETE_BUNDLE_OR_BUY_GET, available.map { it.portionId })
            // No hidden cheapest/free-item choice, even when unused matching units have a different value.
            val firstValue = currentGross.getValue(available.first().portionId)
            promotionRequire(available.all { currentGross.getValue(it.portionId) == firstValue },
                PromotionFailure.MIXED_BUY_GET_VALUES, "Mixed current buy/get values require an explicit different profile", rule.ruleId)
            available.take(count).map { it.portionId }
        }
        else -> available.map { it.portionId }
    }
    val originalCohort = cohort.map { originalGross.getValue(it) }.sumExact()
    eligibility.minimumQuantity?.let { minimum ->
        if (cohortQuantity(cohort, lines, minimum.unitId, rule.ruleId) < minimum.toFraction()) {
            return PromotionCandidate.NotEligible(PromotionDecisionReason.MINIMUM_QUANTITY_NOT_MET, cohort, originalCohort)
        }
    }
    if (eligibility.minimumSpend != null && originalCohort < eligibility.minimumSpend.toFraction()) {
        return PromotionCandidate.NotEligible(PromotionDecisionReason.MINIMUM_SPEND_NOT_MET, cohort, originalCohort)
    }
    return when (val benefit = rule.benefit) {
        is PromotionBenefit.ScopedDiscount -> PromotionCandidate.Eligible(cohort, originalCohort, benefit.value)
        is PromotionBenefit.QuantityTiers -> {
            val quantity = cohortQuantity(cohort, lines, benefit.tiers.first().minimumQuantity.unitId, rule.ruleId)
            val tier = benefit.tiers.lastOrNull { quantity >= it.minimumQuantity.toFraction() }
                ?: return PromotionCandidate.NotEligible(PromotionDecisionReason.NO_QUALIFIED_QUANTITY_TIER, cohort, originalCohort)
            PromotionCandidate.Eligible(cohort, originalCohort, tier.value, tier.minimumQuantity)
        }
        is PromotionBenefit.FixedPriceBundle -> targetPayableCandidate(cohort, originalGross, originalCohort, benefit.payable, currentLines, rule.ruleId)
        is PromotionBenefit.BuyXGetY -> {
            val target = (currentGross.getValue(cohort.first()) * ExactRational.ratio(benefit.buyUnits.toLong(), 1)).toMoney(input.pricing.currency)
            targetPayableCandidate(cohort, originalGross, originalCohort, target, currentLines, rule.ruleId)
        }
    }
}

private fun matchesPromotionItems(selector: PromotionItemSelector, fact: PromotionPortionFacts): Boolean =
    fact.skuId !in selector.excludedSkuIds && (selector.mode == PromotionMatchMode.ALL_BASKET_PORTIONS ||
        fact.skuId in selector.includedSkuIds || fact.categoryIds.any { it in selector.includedCategoryIds })

private fun requireAtomicPromotionPortion(line: ResolvedPriceLine, unitId: DomainId, ruleId: DomainId) {
    promotionRequire(line.quantity.unitId == unitId, PromotionFailure.INCOMPATIBLE_QUANTITY_UNITS,
        "Bundle/buy-get unit differs from its concrete price portion; convert explicitly", ruleId)
    promotionRequire(line.quantity.toFraction() == ONE, PromotionFailure.ATOMIC_PORTIONS_REQUIRED,
        "Bundle/buy-get requires supplied one-unit portions with their own stable IDs; implicit splitting is forbidden", ruleId)
}

private fun cohortQuantity(cohort: List<DomainId>, lines: Map<DomainId, ResolvedPriceLine>, unitId: DomainId, ruleId: DomainId): ExactRational {
    promotionRequire(cohort.all { lines.getValue(it).quantity.unitId == unitId }, PromotionFailure.INCOMPATIBLE_QUANTITY_UNITS,
        "Promotion thresholds cannot add unlike quantity units", ruleId)
    return cohort.map { lines.getValue(it).quantity.toFraction() }.sumExact()
}

private fun targetPayableCandidate(
    cohort: List<DomainId>, originalGross: Map<DomainId, ExactRational>, originalCohort: ExactRational, target: Money,
    currentLines: Map<DomainId, PricedLineSnapshot>, ruleId: DomainId,
): PromotionCandidate.Eligible {
    val currentPayable = cohort.map { currentLines.getValue(it).payable }.sumMoney(target.currency)
    promotionRequire(target.minor <= currentPayable.minor, PromotionFailure.PROMOTION_WOULD_INCREASE_PRICE,
        "Declared promotional cohort payable exceeds its current posted paid allocation", ruleId)
    return PromotionCandidate.Eligible(cohort, originalCohort, DiscountValue.FixedPayable(currentPayable - target), requiredCohortPayable = target,
        requiredPaidSpread = originalPaidTargetSpread(cohort, originalGross, originalCohort, target))
}

private data class PromotionPaidQuota(val id: DomainId, val originalGross: ExactRational, val floor: Money, val remainder: ExactRational)

/** A CHECK target, never a replacement for V2's protected component posting. */
private fun originalPaidTargetSpread(
    cohort: List<DomainId>, originalGross: Map<DomainId, ExactRational>, originalCohort: ExactRational, target: Money,
): List<PromotionTargetPaidPortion> {
    if (originalCohort == ZERO) {
        check(target.minor == 0L) { "A zero-original-value cohort cannot have a positive discounted target" }
        return cohort.map { PromotionTargetPaidPortion(it, ZERO, target) }
    }
    val quotas = cohort.map { id ->
        val weight = originalGross.getValue(id)
        val share = target.toFraction() * weight / originalCohort
        val floor = Money(target.currency, share.floorScaled(target.currency.fractionDigits))
        PromotionPaidQuota(id, weight, floor, share - floor.toFraction())
    }
    val remaining = (target - quotas.map { it.floor }.sumMoney(target.currency)).minor
    val candidates = quotas.filter { it.remainder > ZERO }
        .sortedWith(compareByDescending<PromotionPaidQuota> { it.remainder }.thenBy { it.id.value })
    check(remaining in 0L..candidates.size.toLong()) { "Invalid proportional promotion paid remainder" }
    val winners = candidates.take(remaining.toInt()).map { it.id }.toSet()
    return quotas.map { quota -> PromotionTargetPaidPortion(quota.id, quota.originalGross,
        quota.floor + Money(target.currency, if (quota.id in winners) 1 else 0)) }
}

private fun portionExacts(snapshot: PricingSnapshot): Map<DomainId, ExactRational> = snapshot.lines.associate { line ->
    line.portionId to snapshot.components.filter { it.portionId == line.portionId }.map { it.exact }.sumExact()
}

private fun withPromotionAdjustments(input: PromotionSelectionRequest, adjustments: List<PriceAdjustment>, automaticCount: Int): PricingRequest =
    input.pricing.copy(adjustments = adjustments,
        promotionResolution = if (automaticCount == 0) PromotionResolution.NONE else PromotionResolution.RESOLVED_CONCRETE_PORTIONS)

private fun calculatePromotionPricing(input: PricingRequest, ruleId: DomainId?): PricingSnapshot = try {
    OperationalPricingEngine.calculate(input)
} catch (failure: PricingRejectedException) {
    throw PromotionRejectedException(PromotionFailure.CANDIDATE_PRICING_REJECTED,
        "Pricing rejected this promotion/base/manual quote; never silently skip or clamp its advertised reduction",
        ruleId, failure.failure, failure)
}

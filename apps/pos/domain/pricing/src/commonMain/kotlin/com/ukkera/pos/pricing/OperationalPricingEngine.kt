package com.ukkera.pos.pricing

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money

/**
 * Exact bounded linear calculator. Callers must reject any exception before
 * publication/collection; falling back to an undiscounted or untaxed sale is unsafe.
 */
public object OperationalPricingEngine {
    /** Invoke during policy publication as well as on the collection calculation path. */
    public fun validateSupportedProfile(profile: String, taxPostingPolicy: TaxPostingPolicy): Unit {
        if (profile != PricingProfiles.LINEAR_PROTECTED_V2 || taxPostingPolicy != TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION) {
            reject(PricingFailure.UNSUPPORTED_CONFIGURATION, "Unsupported operational/fiscal posting profile; qualification is required")
        }
    }

    public fun calculate(request: PricingRequest): PricingSnapshot = try {
        calculateValidated(request)
    } catch (failure: PricingRejectedException) {
        throw failure
    } catch (failure: ArithmeticException) {
        throw PricingRejectedException(
            PricingFailure.ARITHMETIC_OR_POSTING_LIMIT,
            "Exact arithmetic or signed 64-bit posting limit exceeded",
        )
    }

    private fun calculateValidated(input: PricingRequest): PricingSnapshot {
        // Check counts BEFORE defensive copies of potentially untrusted collections.
        validateInputCounts(input)
        // No caller-owned collection is retained in the result or calculation state.
        val request = input.copy(
            lines = input.lines.toList(),
            charges = input.charges.map {
                if (it is PricingCharge.Percentage) it.copy(eligiblePortionIds = it.eligiblePortionIds.toSet()) else it
            },
            adjustments = input.adjustments.map { it.copy(eligiblePortions = it.eligiblePortions.toList()) },
        )
        validate(request)
        val currency = request.currency
        val charges = request.charges.sortedBy { it.chargeId.value }
        val states = request.lines.sortedBy { it.portionId.value }.map { source ->
            val entered = source.quantity.toFraction() * source.unitPrice.toFraction()
            val net = interpretNet(entered, source.inclusion, source.tax)
            val factor = ONE + source.tax.rate.toFraction() + charges.filterIsInstance<PricingCharge.Percentage>()
                .filter { source.portionId in it.eligiblePortionIds }
                .map { it.rate.toFraction() * (ONE + it.tax.rate.toFraction()) }.sumExact()
            val gross = net * factor
            LineState(source, net, factor, cap(gross, source.discountLimits))
        }
        val statesById = states.associateBy { it.source.portionId }
        val originalComponents = components(states, charges)
        val originalExact = originalComponents.map { it.exact }.sumExact()
        val originalPosted = postComponents(originalComponents, currency)
        val originalPayable = originalExact.toMoney(currency)
        val originalPostedByPortion = states.associate { line -> line.source.portionId to
            originalComponents.filter { it.portionId == line.source.portionId }.map { originalPosted.getValue(it.key) }.sumMoney(currency) }
        val originalGrossBasis = states.map { it.originalNet * it.factor }.sumExact()
        val basketCap = cap(originalGrossBasis, request.discountLimits)
        var usedBasketCap = ZERO
        var currentComponents = originalComponents
        var currentPosted = originalPosted
        var currentExact = originalExact
        var currentPayable = originalPayable
        val applied = mutableListOf<AppliedDiscountSnapshot>()

        for (adjustment in request.adjustments) {
            val scope = adjustment.eligiblePortions.sortedBy { it.portionId.value }
            if (adjustment.stacking != StackingPermission.EXPLICITLY_ALLOWED &&
                scope.any { statesById.getValue(it.portionId).consumedGross > ZERO }
            ) {
                reject(PricingFailure.STACKING_NOT_ALLOWED, "Reusing an adjusted portion requires explicit stacking")
            }
            val eligible = scope.map { target ->
                val line = statesById.getValue(target.portionId)
                val weight = line.net * line.factor
                var capacity = minExact(weight, line.limit - line.consumedGross)
                target.maximumPayableReduction?.let { capacity = minExact(capacity, it.toFraction()) }
                GrossCapacity(target.portionId, weight, capacity)
            }
            val currentEligibleGross = eligible.map { it.weight }.sumExact()
            val capacity = minExact(
                eligible.map { it.cap }.sumExact(),
                minExact(cap(currentEligibleGross, adjustment.limits), basketCap - usedBasketCap),
            )
            check(capacity >= ZERO && capacity <= currentExact) { "Invalid discount capacity" }
            // A capacity below one minor unit can still cross a posting boundary.
            // Flooring capacity itself would advertise the wrong maximum.
            val mathematicalUpperBound = currentPayable - (currentExact - capacity).toMoney(currency)
            val currentPostedByPortion = states.associate { line -> line.source.portionId to
                currentComponents.filter { it.portionId == line.source.portionId }.map { currentPosted.getValue(it.key) }.sumMoney(currency) }
            val actualPostedCapacity = eligible.map { target ->
                if (target.cap == ZERO) Money(currency, 0) else {
                    val source = statesById.getValue(target.id).source
                    val alreadyUsed = originalPostedByPortion.getValue(target.id) - currentPostedByPortion.getValue(target.id)
                    val lineRemaining = source.discountLimits.maximumPayableReduction - alreadyUsed
                    val scopeCap = scope.single { it.portionId == target.id }.maximumPayableReduction
                    minMoney(currentPostedByPortion.getValue(target.id), minMoney(lineRemaining, scopeCap ?: lineRemaining))
                }
            }.sumMoney(currency)
            val basketPostedRemaining = request.discountLimits.maximumPayableReduction - (originalPayable - currentPayable)
            val upperBound = minMoney(mathematicalUpperBound,
                minMoney(actualPostedCapacity, minMoney(adjustment.limits.maximumPayableReduction, basketPostedRemaining)))
            check(upperBound.minor >= 0)
            val reduction = when (val value = adjustment.value) {
                is DiscountValue.FixedPayable -> {
                    if (value.amount.minor > upperBound.minor) limitExceeded(upperBound)
                    // Translation by an integer minor unit preserves nonnegative
                    // HALF_UP. At a depleted fractional cap, use the cap itself.
                    minExact(value.amount.toFraction(), capacity)
                }
                is DiscountValue.Percentage -> {
                    val exact = currentEligibleGross * value.rate.toFraction()
                    if (exact > capacity) limitExceeded(upperBound)
                    exact
                }
            }
            val grossAllocations = allocateCappedGross(reduction, eligible)
            val nextNets = states.associate { line -> line.source.portionId to
                line.net - (grossAllocations[line.source.portionId] ?: ZERO) / line.factor }
            val nextComponents = components(states, charges, nextNets)
            val nextExact = nextComponents.map { it.exact }.sumExact()
            val nextPayable = nextExact.toMoney(currency)
            val advertised = currentPayable - nextPayable
            check(currentExact - nextExact == reduction) { "Marginal payable reduction did not reconcile" }
            if (adjustment.value is DiscountValue.FixedPayable && advertised != adjustment.value.amount) {
                reject(PricingFailure.UNSUPPORTED_CONFIGURATION, "Requested fixed reduction cannot be posted exactly")
            }
            check(advertised.minor >= 0) { "A discount increased document payable" }
            if (advertised.minor > upperBound.minor) limitExceeded(upperBound)
            val nextPosted = constrainedPost(currentComponents, currentPosted, nextComponents, nextPayable,
                statesById, originalPostedByPortion, currentPostedByPortion, scope, upperBound)
            val nextByKey = nextComponents.associateBy { it.key }
            val reductions = currentComponents.associate { it.key to it.exact - nextByKey.getValue(it.key).exact }
            check(reductions.values.all { it >= ZERO }) { "A linear component increased exactly" }
            val allocations = currentComponents.map { component ->
                val delta = currentPosted.getValue(component.key) - nextPosted.getValue(component.key)
                check(delta.minor >= 0)
                check(reductions.getValue(component.key) > ZERO || delta.minor == 0L)
                DiscountComponentAllocation(
                    component.key, component.portionId, component.chargeId, component.kind,
                    component.exact, nextByKey.getValue(component.key).exact,
                    reductions.getValue(component.key), delta, delta, delta.toFraction() - reductions.getValue(component.key),
                )
            }
            check(allocations.map { it.advertisedReduction }.sumMoney(currency) == advertised)
            for (line in states) {
                line.net = nextNets.getValue(line.source.portionId)
                line.consumedGross += grossAllocations[line.source.portionId] ?: ZERO
                check(line.net >= ZERO && line.consumedGross <= line.limit) { "Line discount exceeded its exact cap" }
            }
            usedBasketCap += reduction
            applied += AppliedDiscountSnapshot(
                adjustment.adjustmentId, adjustment.ruleRevision, adjustment.stage, adjustment.value,
                adjustment.limits, adjustment.stacking, scope, if (upperBound.minor == 0L) upperBound else null,
                upperBound, currentEligibleGross, capacity, reduction, advertised,
                currentPayable, nextPayable, allocations,
            )
            currentComponents = nextComponents
            currentPosted = nextPosted
            currentExact = nextExact
            currentPayable = nextPayable
        }

        val originalByKey = originalComponents.associateBy { it.key }
        val postedComponents = currentComponents.map { component ->
            PostedPriceComponent(
                component.key, component.lineId, component.portionId, component.chargeId,
                component.kind, component.tax, component.refundTreatment,
                originalByKey.getValue(component.key).exact, component.exact,
                originalPosted.getValue(component.key), currentPosted.getValue(component.key),
            )
        }
        val postedLines = states.map { line ->
            val source = line.source
            PricedLineSnapshot(
                source.lineId, source.portionId, source.priceRevision, source.quantity,
                source.unitPrice, source.inclusion, source.tax, source.discountLimits, line.originalNet, line.net,
                postedComponents.filter { it.portionId == source.portionId }.map { it.posted }.sumMoney(currency),
            )
        }
        check(postedComponents.map { it.posted }.sumMoney(currency) == currentPayable)
        check(applied.map { it.advertisedPayableReduction }.sumMoney(currency) == originalPayable - currentPayable)
        return PricingSnapshot(
            request.snapshotId, request.revision, currency, request.profile, request.taxPostingPolicy, request.promotionResolution,
            request.discountLimits, originalPayable, currentPayable, originalExact, currentExact,
            postedLines, charges.map { it.snapshot() }, postedComponents, applied,
        )
    }

    private fun validate(request: PricingRequest) {
        validateSupportedProfile(request.profile, request.taxPostingPolicy)
        if (request.promotionResolution == PromotionResolution.UNRESOLVED_CAMPAIGNS) {
            reject(PricingFailure.UNSUPPORTED_CONFIGURATION, "Unresolved campaign selection")
        }
        validateInputCounts(request)
        val portions = request.lines.map { it.portionId }.toSet()
        invalidUnless(portions.size == request.lines.size, "Duplicate portion identity")
        invalidUnless(request.charges.map { it.chargeId }.toSet().size == request.charges.size, "Duplicate charge identity")
        invalidUnless(request.adjustments.map { it.adjustmentId }.toSet().size == request.adjustments.size, "Duplicate adjustment identity")
        validateLimits(request.discountLimits, request.currency)
        for (line in request.lines) {
            invalidUnless(line.quantity.scaled > 0, "Sale quantity must be positive")
            invalidUnless(line.unitPrice.currency == request.currency, "Price currency mismatch")
            invalidUnless(line.unitPrice.toFraction() >= ZERO, "Sale unit price cannot be negative")
            validateTax(line.tax)
            validateLimits(line.discountLimits, request.currency)
        }
        var componentCount = request.lines.size * 2
        for (charge in request.charges) {
            validateTax(charge.tax)
            when (charge) {
                is PricingCharge.Fixed -> {
                    invalidUnless(charge.amount.currency == request.currency, "Charge currency mismatch")
                    invalidUnless(charge.amount.toFraction() >= ZERO, "Fixed charge cannot be negative")
                    if (charge.refundTreatment == ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS) {
                        reject(PricingFailure.UNSUPPORTED_CONFIGURATION, "Independent fixed charges need a separate refund entitlement")
                    }
                    componentCount += 2
                }
                is PricingCharge.Percentage -> {
                    if (charge.base != PercentageChargeBase.DISCOUNTED_ITEM_NET) {
                        reject(PricingFailure.UNSUPPORTED_CONFIGURATION, "Only linear charges on discounted item net are supported")
                    }
                    invalidUnless(charge.eligiblePortionIds.size <= request.lines.size, "Too many eligible charge portions")
                    invalidUnless(charge.eligiblePortionIds.all { it in portions }, "Unknown charge portion")
                    // No invented statutory maximum. The exact scalar and posted
                    // amount bounds reject unsupported magnitude without rounding.
                    invalidUnless(charge.rate.toFraction() >= ZERO, "Charge percentage cannot be negative")
                    componentCount += charge.eligiblePortionIds.size * 2
                }
            }
        }
        invalidUnless(componentCount <= PricingProfiles.MAX_COMPONENTS, "Too many posting components")
        var previousStage = AdjustmentStage.RESOLVED_ITEM_PROMOTION
        for (adjustment in request.adjustments) {
            invalidUnless(adjustment.stage.ordinal >= previousStage.ordinal, "Promotion must precede order/manual discount")
            previousStage = adjustment.stage
            if (adjustment.stage == AdjustmentStage.RESOLVED_ITEM_PROMOTION &&
                request.promotionResolution != PromotionResolution.RESOLVED_CONCRETE_PORTIONS
            ) reject(PricingFailure.UNSUPPORTED_CONFIGURATION, "Promotion requires resolved concrete consumption")
            invalidUnless(adjustment.eligiblePortions.size <= request.lines.size, "Too many discount portions")
            invalidUnless(adjustment.eligiblePortions.map { it.portionId }.toSet().size == adjustment.eligiblePortions.size,
                "Duplicate discount portion")
            for (target in adjustment.eligiblePortions) {
                invalidUnless(target.portionId in portions, "Unknown discount portion")
                target.maximumPayableReduction?.let { validateNonnegativeMoney(it, request.currency) }
            }
            validateLimits(adjustment.limits, request.currency)
            when (val value = adjustment.value) {
                is DiscountValue.FixedPayable -> validateNonnegativeMoney(value.amount, request.currency)
                is DiscountValue.Percentage -> invalidUnless(value.rate.toFraction() in ZERO..ONE, "Discount percentage must be in 0..100")
            }
        }
    }
}

private class LineState(
    val source: ResolvedPriceLine,
    val originalNet: ExactRational,
    val factor: ExactRational,
    val limit: ExactRational,
    var net: ExactRational = originalNet,
    var consumedGross: ExactRational = ZERO,
)

private data class ExactComponent(
    val key: String,
    val lineId: DomainId?,
    val portionId: DomainId?,
    val chargeId: DomainId?,
    val kind: PriceComponentKind,
    val tax: TaxSnapshot,
    val refundTreatment: ChargeRefundTreatment,
    val exact: ExactRational,
)

private fun components(states: List<LineState>, charges: List<PricingCharge>, nets: Map<DomainId, ExactRational> = emptyMap()): List<ExactComponent> {
    val result = mutableListOf<ExactComponent>()
    for (line in states) {
        val source = line.source
        val net = nets[source.portionId] ?: line.net
        val prefix = "p/${source.portionId.value}/item"
        result += ExactComponent("$prefix/net", source.lineId, source.portionId, null,
            PriceComponentKind.ITEM_NET, source.tax, ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS, net)
        result += ExactComponent("$prefix/tax", source.lineId, source.portionId, null,
            PriceComponentKind.ITEM_TAX, source.tax, ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS, net * source.tax.rate.toFraction())
    }
    for (charge in charges) {
        when (charge) {
            is PricingCharge.Fixed -> {
                val net = interpretNet(charge.amount.toFraction(), charge.inclusion, charge.tax)
                val prefix = "c/${charge.chargeId.value}/fixed"
                result += ExactComponent("$prefix/net", null, null, charge.chargeId, PriceComponentKind.CHARGE_NET,
                    charge.tax, charge.refundTreatment, net)
                result += ExactComponent("$prefix/tax", null, null, charge.chargeId, PriceComponentKind.CHARGE_TAX,
                    charge.tax, charge.refundTreatment, net * charge.tax.rate.toFraction())
            }
            is PricingCharge.Percentage -> for (line in states.filter { it.source.portionId in charge.eligiblePortionIds }) {
                val source = line.source
                val net = (nets[source.portionId] ?: line.net) * charge.rate.toFraction()
                val prefix = "p/${source.portionId.value}/charge/${charge.chargeId.value}"
                result += ExactComponent("$prefix/net", source.lineId, source.portionId, charge.chargeId,
                    PriceComponentKind.CHARGE_NET, charge.tax, charge.refundTreatment, net)
                result += ExactComponent("$prefix/tax", source.lineId, source.portionId, charge.chargeId,
                    PriceComponentKind.CHARGE_TAX, charge.tax, charge.refundTreatment, net * charge.tax.rate.toFraction())
            }
        }
    }
    return result.sortedBy { it.key }
}

private data class GrossCapacity(val id: DomainId, val weight: ExactRational, val cap: ExactRational)

/** Capped proportional water filling; no intermediate monetary rounding. */
private fun allocateCappedGross(total: ExactRational, capacities: List<GrossCapacity>): Map<DomainId, ExactRational> {
    check(total >= ZERO && total <= capacities.map { it.cap }.sumExact())
    var remainder = total
    var active = capacities.filter { it.cap > ZERO }
    val allocations = capacities.associate { it.id to ZERO }.toMutableMap()
    while (remainder > ZERO) {
        val weights = active.map { it.weight }.sumExact()
        check(weights > ZERO) { "Positive discount without eligible weight" }
        val saturated = active.filter { remainder * it.weight / weights > it.cap }
        if (saturated.isEmpty()) {
            for (entry in active) allocations[entry.id] = remainder * entry.weight / weights
            remainder = ZERO
        } else {
            for (entry in saturated) {
                allocations[entry.id] = entry.cap
                remainder -= entry.cap
            }
            val ids = saturated.map { it.id }.toSet()
            active = active.filterNot { it.id in ids }
        }
    }
    check(allocations.values.sumExact() == total)
    return allocations
}

private fun postComponents(components: List<ExactComponent>, currency: Currency): Map<String, Money> {
    val exacts = components.associate { it.key to it.exact }
    return largestRemainder(exacts, exacts.values.sumExact().toMoney(currency))
}

private data class ProtectedQuota(
    val key: String,
    val portionId: DomainId?,
    val lower: Money,
    val upper: Money,
    val remainder: ExactRational,
)

/**
 * Protected, capped after-posting profile: every component remains within its
 * exact floor/ceil, no component increases, and exact-unaffected postings freeze.
 * Feasibility is NOT monotone in requested discount; evaluate this target only.
 */
private fun constrainedPost(
    beforeComponents: List<ExactComponent>,
    beforePosted: Map<String, Money>,
    afterComponents: List<ExactComponent>,
    target: Money,
    states: Map<DomainId, LineState>,
    originalPostedByPortion: Map<DomainId, Money>,
    currentPostedByPortion: Map<DomainId, Money>,
    scope: List<EligibleDiscountPortion>,
    capacityUpperBound: Money,
): Map<String, Money> {
    val before = beforeComponents.associateBy { it.key }
    val scopeIds = scope.map { it.portionId }.toSet()
    val quotas = afterComponents.map { component ->
        val previous = before.getValue(component.key)
        val posted = beforePosted.getValue(component.key)
        check(component.exact <= previous.exact)
        val floor = Money(target.currency, component.exact.floorScaled(target.currency.fractionDigits))
        val remainder = component.exact - floor.toFraction()
        if (component.exact == previous.exact) {
            ProtectedQuota(component.key, component.portionId, posted, posted, remainder)
        } else {
            check(component.portionId in scopeIds && component.portionId != null)
            check(floor.minor <= posted.minor)
            // min(ceil(exact), before), without overflowing at Long.MAX_VALUE.
            val upper = if (remainder > ZERO && floor.minor < posted.minor) floor + Money(target.currency, 1) else floor
            ProtectedQuota(component.key, component.portionId, floor, upper, remainder)
        }
    }
    val chosen = quotas.associate { it.key to it.lower }.toMutableMap()
    val byPortion = quotas.groupBy { it.portionId }
    val priority = compareByDescending<ProtectedQuota> { it.remainder }.thenBy { it.key }
    // Retain cents required by cumulative original posted amount caps first.
    // These lower-bound constraints are disjoint portion groups, not an optimizer.
    for (entry in scope) {
        val group = byPortion.getValue(entry.portionId)
        val floorSum = group.map { chosen.getValue(it.key) }.sumMoney(target.currency)
        val minimumFromLineCap = originalPostedByPortion.getValue(entry.portionId) -
            states.getValue(entry.portionId).source.discountLimits.maximumPayableReduction
        val minimumFromScopeCap = entry.maximumPayableReduction?.let { currentPostedByPortion.getValue(entry.portionId) - it }
            ?: Money(target.currency, 0)
        val needed = maxOf(0L, minimumFromLineCap.minor - floorSum.minor, minimumFromScopeCap.minor - floorSum.minor)
        val candidates = group.filter { it.upper.minor > chosen.getValue(it.key).minor }.sortedWith(priority)
        if (needed > candidates.size.toLong()) infeasible(capacityUpperBound)
        candidates.take(needed.toInt()).forEach { chosen[it.key] = chosen.getValue(it.key) + Money(target.currency, 1) }
    }
    val residual = (target - chosen.values.sumMoney(target.currency)).minor
    val remaining = quotas.filter { it.upper.minor > chosen.getValue(it.key).minor }.sortedWith(priority)
    if (residual < 0 || residual > remaining.size.toLong()) infeasible(capacityUpperBound)
    remaining.take(residual.toInt()).forEach { chosen[it.key] = chosen.getValue(it.key) + Money(target.currency, 1) }
    check(chosen.values.sumMoney(target.currency) == target)
    check(quotas.all { chosen.getValue(it.key).minor in it.lower.minor..it.upper.minor })
    return chosen
}

private data class PostingQuota(val key: String, val floor: Money, val remainder: ExactRational)

/** Absolute exact component quotas, not separately rounded line/tax subtotals. */
private fun largestRemainder(exacts: Map<String, ExactRational>, total: Money): Map<String, Money> {
    val scale = total.currency.fractionDigits
    val quotas = exacts.entries.map { (key, value) ->
        check(value >= ZERO) { "Negative operational component" }
        val floor = Money(total.currency, value.floorScaled(scale))
        PostingQuota(key, floor, value - floor.toFraction())
    }
    val residual = (total - quotas.map { it.floor }.sumMoney(total.currency)).minor
    check(residual >= 0 && residual <= quotas.size.toLong()) { "Invalid document rounding residual" }
    val winners = quotas.filter { it.remainder > ZERO }
        .sortedWith(compareByDescending<PostingQuota> { it.remainder }.thenBy { it.key })
        .take(residual.toInt()).map { it.key }.toSet()
    check(winners.size.toLong() == residual)
    return quotas.associate { quota ->
        quota.key to (quota.floor + Money(total.currency, if (quota.key in winners) 1 else 0))
    }.also { check(it.values.sumMoney(total.currency) == total) }
}

private fun PricingCharge.snapshot(): ChargeRuleSnapshot = when (this) {
    is PricingCharge.Fixed -> ChargeRuleSnapshot(chargeId, revision, kind, tax, refundTreatment,
        amount, inclusion, null, emptyList())
    is PricingCharge.Percentage -> ChargeRuleSnapshot(chargeId, revision, kind, tax, refundTreatment,
        null, TaxInclusion.EXCLUSIVE, rate, eligiblePortionIds.sortedBy { it.value })
}

private fun interpretNet(amount: ExactRational, inclusion: TaxInclusion, tax: TaxSnapshot): ExactRational =
    if (inclusion == TaxInclusion.INCLUSIVE) amount / (ONE + tax.rate.toFraction()) else amount

private fun cap(basis: ExactRational, limits: DiscountLimits): ExactRational =
    minExact(basis, minExact(limits.maximumPayableReduction.toFraction(), basis * limits.maximumPercentage.toFraction()))

private fun validateLimits(limits: DiscountLimits, currency: Currency) {
    validateNonnegativeMoney(limits.maximumPayableReduction, currency)
    invalidUnless(limits.maximumPercentage.toFraction() in ZERO..ONE, "Discount cap percentage must be in 0..100")
}

private fun validateInputCounts(request: PricingRequest) {
    invalidUnless(request.lines.size <= PricingProfiles.MAX_PORTIONS, "Too many price portions")
    invalidUnless(request.charges.size <= PricingProfiles.MAX_CHARGES, "Too many charge rules")
    invalidUnless(request.adjustments.size <= PricingProfiles.MAX_ADJUSTMENTS, "Too many adjustments")
    request.charges.filterIsInstance<PricingCharge.Percentage>().forEach {
        invalidUnless(it.eligiblePortionIds.size <= PricingProfiles.MAX_PORTIONS, "Too many eligible charge portions")
    }
    request.adjustments.forEach { invalidUnless(it.eligiblePortions.size <= PricingProfiles.MAX_PORTIONS, "Too many discount portions") }
}

private fun validateTax(tax: TaxSnapshot) {
    invalidUnless(tax.categoryCode.isNotBlank() && tax.categoryCode.length <= 128, "Invalid tax category snapshot")
    invalidUnless(tax.rate.toFraction() >= ZERO, "Tax percentage cannot be negative")
}

private fun validateNonnegativeMoney(money: Money, currency: Currency) {
    invalidUnless(money.currency == currency && money.minor >= 0, "Expected nonnegative money in document currency")
}

private fun invalidUnless(condition: Boolean, message: String) {
    if (!condition) reject(PricingFailure.INVALID_INPUT, message)
}

private fun reject(failure: PricingFailure, message: String): Nothing = throw PricingRejectedException(failure, message)

private fun limitExceeded(maximum: Money): Nothing = throw PricingRejectedException(
    PricingFailure.DISCOUNT_LIMIT_EXCEEDED, "Requested discount exceeds current eligible or authorized capacity",
    if (maximum.minor == 0L) maximum else null, maximum,
)

private fun infeasible(upperBound: Money): Nothing = throw PricingRejectedException(
    PricingFailure.POSTING_CONSTRAINT_INFEASIBLE,
    "Document target cannot preserve protected components, exact floor/ceil and posted amount caps; do not collect",
    null, upperBound,
)

internal val ZERO: ExactRational = ExactRational.ratio(0, 1)
internal val ONE: ExactRational = ExactRational.ratio(1, 1)
internal fun minExact(first: ExactRational, second: ExactRational): ExactRational = if (first <= second) first else second
private fun minMoney(first: Money, second: Money): Money {
    check(first.currency == second.currency)
    return if (first.minor <= second.minor) first else second
}
internal fun Iterable<ExactRational>.sumExact(): ExactRational = fold(ZERO) { total, amount -> total + amount }
internal fun Iterable<Money>.sumMoney(currency: Currency): Money = fold(Money(currency, 0)) { total, amount -> total + amount }
internal fun Money.toFraction(): ExactRational = ExactRational.ratio(minor, 100)

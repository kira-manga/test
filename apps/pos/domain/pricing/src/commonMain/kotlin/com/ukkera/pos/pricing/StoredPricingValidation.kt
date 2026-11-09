package com.ukkera.pos.pricing

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money

/**
 * Version-pinned verification of historical records, not repricing/hydration by
 * calculate(). No catalog, clock, campaign selector or current policy is read.
 * A valid decode returns the stored amounts unchanged; mismatches reject.
 */
internal fun validateStoredPricingSnapshot(snapshot: PricingSnapshot) {
    // Historical support is pinned here, not borrowed from the live quote guard:
    // retiring a profile for new sales must not silently reprice or strand receipts.
    require(snapshot.profile == PricingProfiles.LINEAR_PROTECTED_V2 &&
        snapshot.taxPostingPolicy == TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION) {
        "Unsupported stored pricing algorithm/policy; explicit historical support required"
    }
    require(snapshot.promotionResolution != PromotionResolution.UNRESOLVED_CAMPAIGNS)
    require(snapshot.lines.size <= PricingProfiles.MAX_PORTIONS && snapshot.charges.size <= PricingProfiles.MAX_CHARGES &&
        snapshot.discounts.size <= PricingProfiles.MAX_ADJUSTMENTS && snapshot.components.size <= PricingProfiles.MAX_COMPONENTS)
    val currency = snapshot.currency
    storedLimits(snapshot.discountLimits, currency)
    require(snapshot.lines.map { it.portionId.value }.isCanonicalKeys())
    require(snapshot.charges.map { it.chargeId.value }.isCanonicalKeys())
    require(snapshot.components.map { it.key }.isCanonicalKeys())
    require(snapshot.discounts.map { it.adjustmentId }.distinct().size == snapshot.discounts.size)
    val lines = snapshot.lines.associateBy { it.portionId }
    for (line in snapshot.lines) {
        require(line.quantity.scaled > 0 && line.unitPrice.currency == currency && line.unitPrice.toFraction() >= ZERO)
        storedTax(line.tax)
        storedLimits(line.discountLimits, currency)
        val entered = line.quantity.toFraction() * line.unitPrice.toFraction()
        val original = if (line.inclusion == TaxInclusion.INCLUSIVE) entered / (ONE + line.tax.rate.toFraction()) else entered
        require(original == line.exactOriginalItemNet && line.exactItemNet >= ZERO && line.exactItemNet <= original)
        storedMoney(line.payable, currency)
    }
    for (charge in snapshot.charges) {
        storedTax(charge.tax)
        require((charge.fixedAmount == null) != (charge.percentage == null)) { "Stored charge must have one supported basis" }
        require(charge.eligiblePortionIds.map { it.value }.isCanonicalKeys())
        require(charge.eligiblePortionIds.all { it in lines })
        if (charge.fixedAmount != null) {
            require(charge.fixedAmount.currency == currency && charge.fixedAmount.toFraction() >= ZERO)
            require(charge.eligiblePortionIds.isEmpty() && charge.refundTreatment != ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS)
        } else require(charge.inclusion == TaxInclusion.EXCLUSIVE)
    }
    val originalNets = snapshot.lines.associate { it.portionId to it.exactOriginalItemNet }
    val original = storedNodes(snapshot, originalNets)
    require(original.keys == snapshot.components.map { it.key }.toSet())
    for (component in snapshot.components) {
        val node = original.getValue(component.key)
        require(component.lineId == node.lineId && component.portionId == node.portionId && component.chargeId == node.chargeId &&
            component.kind == node.kind && component.tax == node.tax && component.refundTreatment == node.treatment)
        require(component.exactBeforeDiscounts == node.exact && component.exact >= ZERO)
        storedMoney(component.postedBeforeDiscounts, currency)
        storedMoney(component.posted, currency)
    }
    var exacts = original.mapValues { it.value.exact }
    val originalPosted = snapshot.components.associate { it.key to it.postedBeforeDiscounts }
    validateOriginalPosting(exacts, originalPosted, currency)
    var posted = originalPosted
    val originalGross = storedGroupExact(original, exacts)
    val originalGroupPosted = storedGroupMoney(original, posted, currency)
    val originalExact = exacts.values.sumExact()
    require(snapshot.exactBeforeDiscountPayable == originalExact && snapshot.beforeDiscountPayable == originalExact.toMoney(currency))
    val lineCaps = lines.mapValues { (id, line) -> storedCap(originalGross.getValue(id), line.discountLimits) }
    val basketCap = storedCap(originalGross.values.sumExact(), snapshot.discountLimits)
    val usedLine = lines.mapValues { ZERO }.toMutableMap()
    var usedBasket = ZERO
    var previousStage = AdjustmentStage.RESOLVED_ITEM_PROMOTION
    for (discount in snapshot.discounts) {
        require(discount.stage.ordinal >= previousStage.ordinal)
        previousStage = discount.stage
        if (discount.stage == AdjustmentStage.RESOLVED_ITEM_PROMOTION) {
            require(snapshot.promotionResolution == PromotionResolution.RESOLVED_CONCRETE_PORTIONS)
        }
        storedLimits(discount.limits, currency)
        require(discount.eligiblePortions.map { it.portionId.value }.isCanonicalKeys())
        val scope = discount.eligiblePortions.associateBy { it.portionId }
        require(scope.keys.all { it in lines })
        scope.values.forEach { it.maximumPayableReduction?.let { amount -> storedMoney(amount, currency) } }
        if (discount.stacking != StackingPermission.EXPLICITLY_ALLOWED) require(scope.keys.all { usedLine.getValue(it) == ZERO })
        require(discount.allocations.map { it.key } == snapshot.components.map { it.key })
        val rows = discount.allocations.associateBy { it.key }
        val currentGross = storedGroupExact(original, exacts)
        val currentGroupPosted = storedGroupMoney(original, posted, currency)
        val caps = scope.mapValues { (id, target) ->
            var cap = minExact(currentGross.getValue(id), lineCaps.getValue(id) - usedLine.getValue(id))
            target.maximumPayableReduction?.let { cap = minExact(cap, it.toFraction()) }
            require(cap >= ZERO)
            cap
        }
        val eligibleGross = scope.keys.map { currentGross.getValue(it) }.sumExact()
        val capacity = minExact(caps.values.sumExact(), minExact(storedCap(eligibleGross, discount.limits), basketCap - usedBasket))
        val beforeExact = exacts.values.sumExact()
        val beforePayable = beforeExact.toMoney(currency)
        val postedCapacity = scope.keys.map { id ->
            if (caps.getValue(id) == ZERO) Money(currency, 0) else {
                val remaining = lines.getValue(id).discountLimits.maximumPayableReduction -
                    (originalGroupPosted.getValue(id) - currentGroupPosted.getValue(id))
                storedMinMoney(currentGroupPosted.getValue(id), storedMinMoney(remaining, scope.getValue(id).maximumPayableReduction ?: remaining))
            }
        }.sumMoney(currency)
        val upper = storedMinMoney(beforePayable - (beforeExact - capacity).toMoney(currency),
            storedMinMoney(postedCapacity, storedMinMoney(discount.limits.maximumPayableReduction,
                snapshot.discountLimits.maximumPayableReduction - (snapshot.beforeDiscountPayable - beforePayable))))
        require(discount.capacityPayableReductionUpperBound == upper)
        require(discount.maximumPayableReduction == if (upper.minor == 0L) upper else null)
        require(discount.exactEligiblePayable == eligibleGross && discount.exactAvailablePayableReduction == capacity)
        val expectedReduction = when (val value = discount.value) {
            is DiscountValue.FixedPayable -> {
                storedMoney(value.amount, currency)
                require(value.amount.minor <= upper.minor)
                minExact(value.amount.toFraction(), capacity)
            }
            is DiscountValue.Percentage -> {
                require(value.rate.toFraction() <= ONE)
                eligibleGross * value.rate.toFraction()
            }
        }
        require(expectedReduction <= capacity && discount.exactPayableReduction == expectedReduction)
        for ((key, row) in rows) {
            val node = original.getValue(key)
            require(row.portionId == node.portionId && row.chargeId == node.chargeId && row.kind == node.kind)
            require(row.exactBefore == exacts.getValue(key) && row.exactAfter >= ZERO && row.exactAfter <= row.exactBefore)
            require(row.exactReduction == row.exactBefore - row.exactAfter)
            require(node.portionId in scope || row.exactReduction == ZERO)
            storedMoney(row.postingDelta, currency)
            require(row.postingDelta == row.advertisedReduction && row.postingDelta.minor <= posted.getValue(key).minor)
            require(row.exactReduction > ZERO || row.postingDelta.minor == 0L)
            require(row.roundingDifference == row.postingDelta.toFraction() - row.exactReduction)
        }
        val afterExacts = rows.mapValues { it.value.exactAfter }
        val afterNets = lines.keys.associateWith { rows.getValue("p/${it.value}/item/net").exactAfter }
        val afterNodes = storedNodes(snapshot, afterNets)
        require(afterNodes.all { (key, node) -> node.exact == afterExacts.getValue(key) })
        val actualGrossReductions = storedGroupExact(original, rows.mapValues { it.value.exactReduction })
        require(actualGrossReductions.values.sumExact() == expectedReduction)
        // Characterize capped proportional water filling without recomputing a quote:
        // all unsaturated nonzero weights have one lambda; saturated caps lie below it.
        require(scope.keys.all { actualGrossReductions.getValue(it) <= caps.getValue(it) })
        val unsaturated = scope.keys.filter { actualGrossReductions.getValue(it) < caps.getValue(it) }
        if (unsaturated.isNotEmpty()) {
            val first = unsaturated.first()
            val lambda = actualGrossReductions.getValue(first) / currentGross.getValue(first)
            require(unsaturated.all { actualGrossReductions.getValue(it) / currentGross.getValue(it) == lambda })
            require((scope.keys - unsaturated.toSet()).all { id ->
                currentGross.getValue(id) == ZERO || caps.getValue(id) / currentGross.getValue(id) <= lambda
            })
        } else require(scope.keys.all { actualGrossReductions.getValue(it) == caps.getValue(it) })
        val afterPosted = rows.mapValues { (key, row) -> posted.getValue(key) - row.postingDelta }
        val afterPayable = afterExacts.values.sumExact().toMoney(currency)
        require(discount.beforePayable == beforePayable && discount.afterPayable == afterPayable)
        require(discount.advertisedPayableReduction == beforePayable - afterPayable && discount.advertisedPayableReduction.minor <= upper.minor)
        if (discount.value is DiscountValue.FixedPayable) require(discount.value.amount == discount.advertisedPayableReduction)
        require(afterPosted.values.sumMoney(currency) == afterPayable)
        validateProtectedStoredPosting(original, exacts, posted, afterExacts, afterPosted, afterPayable,
            lines, originalGroupPosted, currentGroupPosted, scope)
        scope.keys.forEach { usedLine[it] = usedLine.getValue(it) + actualGrossReductions.getValue(it) }
        usedBasket += expectedReduction
        exacts = afterExacts
        posted = afterPosted
    }
    require(snapshot.exactPayable == exacts.values.sumExact() && snapshot.payable == snapshot.exactPayable.toMoney(currency))
    require(snapshot.components.all { it.exact == exacts.getValue(it.key) && it.posted == posted.getValue(it.key) })
    val finalGroups = storedGroupMoney(original, posted, currency)
    require(snapshot.lines.all { it.exactItemNet == exacts.getValue("p/${it.portionId.value}/item/net") && it.payable == finalGroups.getValue(it.portionId) })
}

private data class StoredNode(
    val lineId: DomainId?, val portionId: DomainId?, val chargeId: DomainId?, val kind: PriceComponentKind,
    val tax: TaxSnapshot, val treatment: ChargeRefundTreatment, val exact: ExactRational,
)

private fun storedNodes(snapshot: PricingSnapshot, nets: Map<DomainId, ExactRational>): Map<String, StoredNode> {
    val nodes = mutableMapOf<String, StoredNode>()
    for (line in snapshot.lines) {
        val prefix = "p/${line.portionId.value}/item"
        val net = nets.getValue(line.portionId)
        nodes["$prefix/net"] = StoredNode(line.lineId, line.portionId, null, PriceComponentKind.ITEM_NET,
            line.tax, ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS, net)
        nodes["$prefix/tax"] = StoredNode(line.lineId, line.portionId, null, PriceComponentKind.ITEM_TAX,
            line.tax, ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS, net * line.tax.rate.toFraction())
    }
    for (charge in snapshot.charges) {
        if (charge.fixedAmount != null) {
            val net = if (charge.inclusion == TaxInclusion.INCLUSIVE) charge.fixedAmount.toFraction() / (ONE + charge.tax.rate.toFraction())
                else charge.fixedAmount.toFraction()
            val prefix = "c/${charge.chargeId.value}/fixed"
            nodes["$prefix/net"] = StoredNode(null, null, charge.chargeId, PriceComponentKind.CHARGE_NET, charge.tax, charge.refundTreatment, net)
            nodes["$prefix/tax"] = StoredNode(null, null, charge.chargeId, PriceComponentKind.CHARGE_TAX, charge.tax, charge.refundTreatment, net * charge.tax.rate.toFraction())
        } else for (id in charge.eligiblePortionIds) {
            val line = snapshot.lines.single { it.portionId == id }
            val net = nets.getValue(id) * requireNotNull(charge.percentage).toFraction()
            val prefix = "p/${id.value}/charge/${charge.chargeId.value}"
            nodes["$prefix/net"] = StoredNode(line.lineId, id, charge.chargeId, PriceComponentKind.CHARGE_NET, charge.tax, charge.refundTreatment, net)
            nodes["$prefix/tax"] = StoredNode(line.lineId, id, charge.chargeId, PriceComponentKind.CHARGE_TAX, charge.tax, charge.refundTreatment, net * charge.tax.rate.toFraction())
        }
    }
    // Use the persisted component order even for bounded intermediate arithmetic.
    return nodes.entries.sortedBy { it.key }.associate { it.key to it.value }
}

private fun validateOriginalPosting(exacts: Map<String, ExactRational>, posted: Map<String, Money>, currency: Currency) {
    val floor = exacts.mapValues { Money(currency, it.value.floorScaled(currency.fractionDigits)) }
    val extra = (exacts.values.sumExact().toMoney(currency) - floor.values.sumMoney(currency)).minor
    require(extra in 0..exacts.size.toLong())
    val winners = exacts.keys.sortedWith(compareByDescending<String> { exacts.getValue(it) - floor.getValue(it).toFraction() }.thenBy { it })
        .take(extra.toInt()).toSet()
    require(posted.all { (key, amount) -> amount == floor.getValue(key) + Money(currency, if (key in winners) 1 else 0) })
}

private fun validateProtectedStoredPosting(
    nodes: Map<String, StoredNode>, beforeExacts: Map<String, ExactRational>, before: Map<String, Money>,
    afterExacts: Map<String, ExactRational>, after: Map<String, Money>, target: Money,
    lines: Map<DomainId, PricedLineSnapshot>, originalGroups: Map<DomainId, Money>, currentGroups: Map<DomainId, Money>,
    scope: Map<DomainId, EligibleDiscountPortion>,
) {
    val currency = target.currency
    val floors = afterExacts.mapValues { Money(currency, it.value.floorScaled(currency.fractionDigits)) }
    val remainders = afterExacts.mapValues { (key, exact) -> exact - floors.getValue(key).toFraction() }
    val baseline = afterExacts.mapValues { (key, exact) -> if (exact == beforeExacts.getValue(key)) before.getValue(key) else floors.getValue(key) }.toMutableMap()
    val candidates = afterExacts.keys.filter { key -> afterExacts.getValue(key) != beforeExacts.getValue(key) &&
        remainders.getValue(key) > ZERO && floors.getValue(key).minor < before.getValue(key).minor }.toMutableSet()
    val order = compareByDescending<String> { remainders.getValue(it) }.thenBy { it }
    for ((id, targetScope) in scope) {
        val keys = nodes.filterValues { it.portionId == id }.keys
        val base = keys.map { baseline.getValue(it) }.sumMoney(currency)
        val lineMinimum = originalGroups.getValue(id) - lines.getValue(id).discountLimits.maximumPayableReduction
        val scopeMinimum = targetScope.maximumPayableReduction?.let { currentGroups.getValue(id) - it } ?: Money(currency, 0)
        val retain = maxOf(0L, lineMinimum.minor - base.minor, scopeMinimum.minor - base.minor)
        val choices = (keys intersect candidates).sortedWith(order)
        require(retain <= choices.size.toLong())
        choices.take(retain.toInt()).forEach { baseline[it] = baseline.getValue(it) + Money(currency, 1); candidates.remove(it) }
    }
    val remainder = (target - baseline.values.sumMoney(currency)).minor
    require(remainder in 0..candidates.size.toLong())
    candidates.sortedWith(order).take(remainder.toInt()).forEach { baseline[it] = baseline.getValue(it) + Money(currency, 1) }
    require(baseline == after) { "Stored protected allocation does not satisfy its versioned rule" }
}

private fun storedGroupExact(nodes: Map<String, StoredNode>, exacts: Map<String, ExactRational>): Map<DomainId, ExactRational> =
    nodes.entries.filter { it.value.portionId != null }.groupBy { requireNotNull(it.value.portionId) }
        .mapValues { (_, group) -> group.map { exacts.getValue(it.key) }.sumExact() }
private fun storedGroupMoney(nodes: Map<String, StoredNode>, amounts: Map<String, Money>, currency: Currency): Map<DomainId, Money> =
    nodes.entries.filter { it.value.portionId != null }.groupBy { requireNotNull(it.value.portionId) }
        .mapValues { (_, group) -> group.map { amounts.getValue(it.key) }.sumMoney(currency) }
private fun storedCap(basis: ExactRational, limits: DiscountLimits): ExactRational =
    minExact(basis, minExact(limits.maximumPayableReduction.toFraction(), basis * limits.maximumPercentage.toFraction()))
private fun storedMinMoney(first: Money, second: Money): Money = if (first.minor <= second.minor) first else second
private fun storedMoney(money: Money, currency: Currency) { require(money.currency == currency && money.minor >= 0) }
private fun storedLimits(limits: DiscountLimits, currency: Currency) {
    storedMoney(limits.maximumPayableReduction, currency)
    require(limits.maximumPercentage.toFraction() <= ONE)
}
private fun storedTax(tax: TaxSnapshot) { require(tax.categoryCode.isNotBlank() && tax.categoryCode.length <= 128) }
private fun List<String>.isCanonicalKeys(): Boolean = this == sorted() && distinct().size == size

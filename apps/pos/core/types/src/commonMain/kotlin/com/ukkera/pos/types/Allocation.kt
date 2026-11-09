package com.ukkera.pos.types

import com.ionspin.kotlin.bignum.integer.BigInteger

public data class AllocationWeight(public val key: String, public val weight: Long)
public data class MoneyAllocation(public val key: String, public val amount: Money)

/**
 * Signed largest-remainder allocation with canonical ASCII key ties. Keys are
 * internal line/portion/charge IDs, never localized display labels. ASCII makes
 * lexicographic tie order identical at the Kotlin and Go boundaries.
 * Weights are not caps:
 * pricing/entitlement commands must validate their own eligibility and bounds first.
 */
public fun allocateByLargestRemainder(total: Money, weights: List<AllocationWeight>): List<MoneyAllocation> {
    require(weights.size <= 10_000) { "Allocation exceeds bounded item count" }
    require(weights.all {
        it.key.isNotEmpty() && it.key.length <= 256 && it.key.all { char -> char in '!'..'~' } && it.weight >= 0
    }) { "Invalid allocation weight" }
    require(weights.map { it.key }.toSet().size == weights.size) { "Duplicate allocation key" }
    if (weights.isEmpty()) {
        require(total.minor == 0L) { "Cannot allocate nonzero money to no recipients" }
        return emptyList()
    }
    val weightSum = weights.fold(BigInteger.ZERO) { sum, item -> sum + BigInteger.fromLong(item.weight) }
    if (weightSum == BigInteger.ZERO) {
        require(total.minor == 0L) { "Cannot allocate nonzero money with zero weights" }
        return weights.map { MoneyAllocation(it.key, Money(total.currency, 0)) }
    }
    val magnitude = BigInteger.fromLong(total.minor).abs()
    val quotas = weights.map { weight ->
        val (whole, remainder) = (magnitude * BigInteger.fromLong(weight.weight)).divideAndRemainder(weightSum)
        Quota(weight.key, whole, remainder)
    }
    val floorSum = quotas.fold(BigInteger.ZERO) { sum, item -> sum + item.whole }
    val extraCount = (magnitude - floorSum).intValue(true)
    check(extraCount in 0..weights.size) { "Allocation conservation error" }
    val winners = quotas.sortedWith(compareByDescending<Quota> { it.remainder }.thenBy { it.key })
        .take(extraCount).map { it.key }.toSet()
    return quotas.map { quota ->
        val absolute = quota.whole + if (quota.key in winners) BigInteger.ONE else BigInteger.ZERO
        val signed = if (total.minor < 0) BigInteger.ZERO - absolute else absolute
        MoneyAllocation(quota.key, Money(total.currency, signed.longValue(true)))
    }
}

private data class Quota(val key: String, val whole: BigInteger, val remainder: BigInteger)

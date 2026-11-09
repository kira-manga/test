package com.ukkera.pos.pricing

import java.math.BigInteger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent integer-price reference for the declared greedy selector, NOT a global optimizer. */
class IndependentPromotionOracleTest {
    @Test fun seededPartialOverlapGreedyChoicesMatchAnIndependentModelWithDeterministicShrinking() {
        val random = Random(PROMOTION_SEED)
        repeat(120) { index ->
            val sources = (1..1 + random.nextInt(7)).map { PromotionOracleSource(it, random.nextInt(101)) }
            val rules = (1..1 + random.nextInt(7)).map { number ->
                val included = sources.filter { random.nextBoolean() }.map { it.number }.toSet().ifEmpty { setOf(sources.first().number) }
                PromotionOracleRule(number, included, listOf(0, 10, 25, 50, 100)[random.nextInt(5)], random.nextInt(5) - 2)
            }
            val case = PromotionOracleCase(sources, rules)
            try {
                verify(case)
            } catch (failure: AssertionError) {
                throw AssertionError("promotionSeed=$PROMOTION_SEED case=$index reducedFailure=${shrink(case)}", failure)
            }
        }
    }

    private fun verify(case: PromotionOracleCase) {
        val expected = reference(case)
        val input = promoInput(case.sources.map { line(it.number, it.wholePrice.toString()) }, case.rules.map {
            promoRule(it.number, it.portions, percent = it.percent.toString(), priority = it.priority)
        })
        val actual = try { PromotionSelector.quote(input) } catch (failure: Exception) {
            throw AssertionError("Integer-price oracle fixture unexpectedly rejected: $case", failure)
        }
        assertEquals(expected.selected.map { promoRuleId(it.rule.number) }, actual.selected.map { it.rule.ruleId }, case.toString())
        assertEquals(expected.selected.map { selected -> selected.portions.sorted().map(::id) },
            actual.selected.map { selected -> selected.paidPortions.map { it.portionId } }, case.toString())
        assertEquals(expected.selected.map { it.reduction.longValueExact() }, actual.selected.map { it.advertisedReductionWhenSelected.minor }, case.toString())
        assertEquals(expected.paid.values.fold(BigInteger.ZERO, BigInteger::add).longValueExact(), actual.pricing.payable.minor, case.toString())
        assertEquals(expected.paid.mapKeys { id(it.key) }.mapValues { it.value.longValueExact() },
            actual.pricing.lines.associate { it.portionId to it.payable.minor }, case.toString())
        assertEquals(expected.trials, actual.candidateTrials, case.toString())
        val permuted = PromotionSelector.quote(input.copy(pricing = input.pricing.copy(lines = input.pricing.lines.reversed()),
            portions = input.portions.reversed(), rules = input.rules.reversed()))
        assertEquals(actual.pricing.components, permuted.pricing.components, case.toString())
        val stored = StoredPricingSnapshotCodec.encode(actual.pricing)
        assertEquals(stored, StoredPricingSnapshotCodec.encode(StoredPricingSnapshotCodec.decode(stored)), case.toString())
    }

    private fun shrink(original: PromotionOracleCase): PromotionOracleCase {
        var reduced = original
        repeat(32) {
            val next = reduced.shrinks().firstOrNull { candidate ->
                try { verify(candidate); false } catch (_: AssertionError) { true }
            } ?: return reduced
            reduced = next
        }
        return reduced
    }
}

private const val PROMOTION_SEED: Long = 0xF070_6EE0L
private val PROMOTION_HUNDRED = BigInteger.valueOf(100)
private data class PromotionOracleSource(val number: Int, val wholePrice: Int)
private data class PromotionOracleRule(val number: Int, val portions: Set<Int>, val percent: Int, val priority: Int)
private data class PromotionOracleCase(val sources: List<PromotionOracleSource>, val rules: List<PromotionOracleRule>) {
    fun shrinks(): List<PromotionOracleCase> = buildList {
        rules.forEach { removed -> add(copy(rules = rules.filterNot { it.number == removed.number })) }
        if (sources.size > 1) sources.forEach { removed -> add(copy(sources = sources.filterNot { it.number == removed.number },
            rules = rules.map { it.copy(portions = it.portions - removed.number) }.filter { it.portions.isNotEmpty() })) }
        sources.filter { it.wholePrice > 0 }.forEach { smaller -> add(copy(sources = sources.map {
            if (it.number == smaller.number) it.copy(wholePrice = it.wholePrice / 2) else it
        })) }
    }
}
private data class PromotionOracleSelection(val rule: PromotionOracleRule, val portions: Set<Int>, val reduction: BigInteger)
private data class PromotionOracleResult(val selected: List<PromotionOracleSelection>, val paid: Map<Int, BigInteger>, val trials: Int)

/** JDK integer cents only. No production exact arithmetic, pricing or sorting comparator generates expectations. */
private fun reference(case: PromotionOracleCase): PromotionOracleResult {
    val original = case.sources.associate { it.number to BigInteger.valueOf(it.wholePrice.toLong()) * PROMOTION_HUNDRED }
    val paid = original.toMutableMap()
    val available = original.keys.toMutableSet()
    val unused = case.rules.toMutableList()
    val selected = mutableListOf<PromotionOracleSelection>()
    var trials = 0
    while (unused.isNotEmpty()) {
        var winner: PromotionOracleSelection? = null
        for (rule in unused) {
            val portions = rule.portions intersect available
            if (portions.isEmpty()) continue
            trials++
            val reduction = portions.fold(BigInteger.ZERO) { sum, id ->
                sum + original.getValue(id) * BigInteger.valueOf(rule.percent.toLong()) / PROMOTION_HUNDRED
            }
            val previous = winner
            if (previous == null || reduction > previous.reduction || (reduction == previous.reduction &&
                    (rule.priority > previous.rule.priority || (rule.priority == previous.rule.priority && rule.number < previous.rule.number)))) {
                winner = PromotionOracleSelection(rule, portions, reduction)
            }
        }
        val chosen = winner ?: break
        selected += chosen
        chosen.portions.forEach { id -> paid[id] = original.getValue(id) * BigInteger.valueOf(100L - chosen.rule.percent) / PROMOTION_HUNDRED }
        available.removeAll(chosen.portions)
        unused.removeAll { it.number == chosen.rule.number }
    }
    return PromotionOracleResult(selected, paid, trials)
}

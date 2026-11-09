package com.ukkera.pos.pricing

import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The oracle uses only JDK BigDecimal parsing and JDK BigInteger fractions.
 * It never calls production rational, tax, allocation or rounding helpers to
 * generate expected amounts. Uncapped discounts use a closed-form uniform net
 * multiplier, independent of the production capped water-filling implementation.
 */
class IndependentPricingOracleTest {
    @Test fun seededMixedRateInclusionQuantityServiceAndFixedReductionOracle() {
        val random = Random(SEED)
        repeat(160) { index ->
            val lines = (1..(1 + random.nextInt(6))).map { number ->
                OracleLine(number, decimal(1 + random.nextInt(100_000), 3),
                    decimal(1 + random.nextInt(1_000_000), 5),
                    RATES[random.nextInt(RATES.size)], random.nextBoolean())
            }
            val charges = (0 until random.nextInt(4)).map { number ->
                OracleCharge(7_000 + number * 10, RATES[random.nextInt(RATES.size)],
                    RATES[random.nextInt(RATES.size)], lines.filter { random.nextBoolean() }.map { it.number }.toSet())
            }
            val fixed = if (random.nextBoolean()) OracleFixed(decimal(random.nextInt(100_000), 5),
                RATES[random.nextInt(RATES.size)], random.nextBoolean()) else null
            val eligible = lines.filter { random.nextBoolean() }.map { it.number }.toSet().ifEmpty { setOf(lines.first().number) }
            val skeleton = OracleCase(lines, charges, fixed, eligible, 0)
            val capacityMinor = skeleton.eligibleGross().floor(2).longValueExact().coerceAtMost(100_000)
            val amount = random.nextInt(capacityMinor.toInt() + 1).toLong()
            val case = skeleton.copy(discountMinor = amount)
            try {
                verify(case)
            } catch (failure: AssertionError) {
                val minimal = shrink(case)
                throw AssertionError("seed=$SEED case=$index reducedFailure=$minimal", failure)
            }
        }
    }

    @Test fun seededFractionalCapsBoundAdvertisedReductionWithoutPromisingFeasibility() {
        val random = Random(SEED xor 0xCAFL)
        repeat(160) { index ->
            val price = decimal(1 + random.nextInt(999), 3)
            val fee = decimal(random.nextInt(999), 3)
            val percent = random.nextInt(101).toString()
            val eligible = JdkFraction.decimal(price)
            val capacity = eligible * JdkFraction.decimal(percent) / HUNDRED
            val before = eligible + JdkFraction.decimal(fee)
            val maximum = before.round(2) - (before - capacity).round(2)
            val originalComponents = mapOf(
                "p/${id(1).value}/item/net" to eligible,
                "p/${id(1).value}/item/tax" to JdkFraction.ZERO,
                "c/${id(7_700).value}/fixed/net" to JdkFraction.decimal(fee),
                "c/${id(7_700).value}/fixed/tax" to JdkFraction.ZERO,
            )
            val originalPostings = postWithJdk(originalComponents)
            val maxMinor = minOf(maximum.longValueExact(),
                if (capacity == JdkFraction.ZERO) 0L else originalPostings.getValue("p/${id(1).value}/item/net"))
            val amount = random.nextInt(maxMinor.toInt() + 1).toLong()
            val charge = PricingCharge.Fixed(id(7_700), Revision(1), ChargeKind.DELIVERY, UnitPrice.parse(EGP, fee),
                TaxInclusion.EXCLUSIVE, tax(), ChargeRefundTreatment.EXPLICIT_ONLY)
            val input = request(listOf(line(1, price, cap = limits(percent = percent))),
                listOf(charge), listOf(fixed(amount, listOf(id(1)))))
            val context = "seed=${SEED xor 0xCAFL} case=$index price=$price fee=$fee cap=$percent requested=$amount"
            val reduction = if (JdkFraction.minor(amount) <= capacity) JdkFraction.minor(amount) else capacity
            val afterComponents = originalComponents + ("p/${id(1).value}/item/net" to eligible - reduction)
            val expected = protectedPostWithJdk(originalComponents, originalPostings, afterComponents)
            if (expected == null) {
                assertEquals(PricingFailure.POSTING_CONSTRAINT_INFEASIBLE,
                    assertFailsWith<PricingRejectedException>(context) { OperationalPricingEngine.calculate(input) }.failure)
            } else {
                val snapshot = OperationalPricingEngine.calculate(input)
                assertEquals(amount, snapshot.advertisedDiscount.minor, context)
                assertEquals(maxMinor, snapshot.discounts.single().capacityPayableReductionUpperBound.minor, context)
                val exactReduction = JdkFraction.parse(snapshot.discounts.single().exactPayableReduction.canonicalString())
                assertTrue(exactReduction >= JdkFraction.ZERO && exactReduction <= capacity, context)
                assertEquals((before - exactReduction).round(2).longValueExact(), snapshot.payable.minor, context)
                assertEquals(expected, snapshot.components.associate { it.key to it.posted.minor }, context)
                assertStoredRoundTrip(snapshot, context)
            }
            val error = assertFailsWith<PricingRejectedException>(context) {
                OperationalPricingEngine.calculate(input.copy(adjustments = listOf(fixed(maxMinor + 1, listOf(id(1))))))
            }
            assertEquals(money(maxMinor), error.capacityPayableReductionUpperBound, context)
        }
    }

    @Test fun seededReturnPartitionsMatchIndependentCumulativeIntegerOracle() {
        val random = Random(SEED xor 0xBEEFL)
        repeat(100) { index ->
            val scaled = (10 + random.nextInt(100_000)).toLong()
            val original = line(1, decimal(1 + random.nextInt(100_000), 4), percent = RATES[random.nextInt(RATES.size)])
                .copy(quantity = Quantity(unit, scaled, 6))
            val snapshot = OperationalPricingEngine.calculate(request(listOf(original),
                listOf(service(setOf(id(1)), percent = "7.125", taxPercent = "14"))))
            var cursor = assertStoredRoundTrip(snapshot, "return seed=$SEED case=$index").openReturnCursor(id(1))
            val totals = cursor.originalComponents.associate { it.key to BigInteger.valueOf(it.amount.minor) }
            val cuts = (listOf(0L, scaled) + List(8) { 1L + random.nextInt((scaled - 1).toInt()) }).distinct().sorted()
            val consumed = totals.mapValues { BigInteger.ZERO }.toMutableMap()
            for ((previous, next) in cuts.zipWithNext()) {
                val returned = cursor.consume(Quantity(unit, next - previous, 6))
                for (component in returned.components) {
                    val cumulative = totals.getValue(component.key) * BigInteger.valueOf(next) / BigInteger.valueOf(scaled)
                    val expected = cumulative - consumed.getValue(component.key)
                    assertEquals(expected.longValueExact(), component.amount.minor, "seed=$SEED case=$index cumulative=$next key=${component.key}")
                    consumed[component.key] = cumulative
                }
                cursor = returned.remaining
            }
            assertEquals(totals, consumed)
            assertEquals(money(0), cursor.remainingPayable)
            assertEquals(0L, cursor.remainingQuantity.scaled)
        }
    }

    @Test fun mixedRatesAndDependentChargesRespectClosedFormCapSaturation() {
        // A has gross factor 1.254, B has 1.1. Saturate A's explicit 1-EGP
        // gross cap, then the remaining 4 EGP belongs to B. This expected
        // allocation is direct algebra, not the production water-fill helper.
        val lines = listOf(line(1, "11.4", percent = "14", inclusion = TaxInclusion.INCLUSIVE, cap = limits(100)),
            line(2, "10"))
        val charges = listOf(service(setOf(id(1)), "10", "14", index = 7_000),
            service(setOf(id(2)), "10", "0", index = 7_100))
        val snapshot = OperationalPricingEngine.calculate(request(lines, charges, listOf(fixed(500, listOf(id(1), id(2))))))
        val expectedA = JdkFraction.decimal("10") - JdkFraction.ONE / JdkFraction.decimal("1.254")
        val expectedB = JdkFraction.decimal("10") - JdkFraction.decimal("4") / JdkFraction.decimal("1.1")
        assertEquals(expectedA.canonical(), snapshot.lines[0].exactItemNet.canonicalString())
        assertEquals(expectedB.canonical(), snapshot.lines[1].exactItemNet.canonicalString())
        assertEquals(money(2_354), snapshot.beforeDiscountPayable)
        assertEquals(money(1_854), snapshot.payable)
        assertConserves(snapshot)
        assertStoredRoundTrip(snapshot, "closed-form cap saturation")
    }

    private fun verify(case: OracleCase) {
        val before = case.exactComponents(discounted = false)
        val after = case.exactComponents(discounted = true)
        val expectedBefore = postWithJdk(before)
        val expectedAfter = protectedPostWithJdk(before, expectedBefore, after)
        if (expectedAfter == null) {
            val error = assertFailsWith<PricingRejectedException> { OperationalPricingEngine.calculate(case.request()) }
            assertTrue(error.failure == PricingFailure.POSTING_CONSTRAINT_INFEASIBLE || error.failure == PricingFailure.DISCOUNT_LIMIT_EXCEEDED)
            return
        }
        val actual = OperationalPricingEngine.calculate(case.request())
        assertEquals(expectedBefore.values.fold(0L, Long::plus), actual.beforeDiscountPayable.minor)
        assertEquals(expectedAfter.values.fold(0L, Long::plus), actual.payable.minor)
        assertEquals(case.discountMinor, actual.advertisedDiscount.minor)
        assertEquals(after.keys, actual.components.map { it.key }.toSet())
        for (component in actual.components) {
            assertEquals(before.getValue(component.key).canonical(), component.exactBeforeDiscounts.canonicalString(), component.key)
            assertEquals(after.getValue(component.key).canonical(), component.exact.canonicalString(), component.key)
            assertEquals(expectedBefore.getValue(component.key), component.postedBeforeDiscounts.minor, component.key)
            assertEquals(expectedAfter.getValue(component.key), component.posted.minor, component.key)
        }
        // Input ordering is irrelevant; only the explicitly ordered adjustment list is semantic.
        val input = case.request()
        val reordered = OperationalPricingEngine.calculate(input.copy(lines = input.lines.reversed(), charges = input.charges.reversed(),
            adjustments = input.adjustments.map { it.copy(eligiblePortions = it.eligiblePortions.reversed()) }))
        assertEquals(actual.components, reordered.components)
        assertConserves(actual)
        assertStoredRoundTrip(actual, case.toString())
    }

    /** Round-trip validation is another subject under test, never the expected-value oracle. */
    private fun assertStoredRoundTrip(snapshot: PricingSnapshot, context: String): PricingSnapshot {
        try {
            val encoded = StoredPricingSnapshotCodec.encode(snapshot)
            val restored = StoredPricingSnapshotCodec.decode(encoded)
            assertEquals(encoded, StoredPricingSnapshotCodec.encode(restored), context)
            assertEquals(snapshot.components, restored.components, context)
            assertEquals(snapshot.lines, restored.lines, context)
            return restored
        } catch (failure: Exception) {
            throw AssertionError("Valid independent-oracle fixture failed stored validation: $context", failure)
        }
    }

    /** Bounded deterministic failure reducer: remove portions/charges/fixed fee or reduce discount. */
    private fun shrink(original: OracleCase): OracleCase {
        var reduced = original
        repeat(32) {
            val next = reduced.shrinks().firstOrNull { candidate ->
                if (JdkFraction.minor(candidate.discountMinor) > candidate.eligibleGross()) false
                else try { verify(candidate); false } catch (_: AssertionError) { true }
            } ?: return reduced
            reduced = next
        }
        return reduced
    }
}

private const val SEED: Long = 0xF070_2026L
private val RATES = listOf("0", "5", "7.125", "14", "25.00000001", "150")
private val HUNDRED = JdkFraction.whole(100)
private fun decimal(unscaled: Int, scale: Int): String = BigDecimal.valueOf(unscaled.toLong(), scale).toPlainString()

private data class OracleLine(val number: Int, val quantity: String, val price: String, val tax: String, val inclusive: Boolean) {
    fun net(): JdkFraction {
        val entered = JdkFraction.decimal(quantity) * JdkFraction.decimal(price)
        return if (inclusive) entered / (JdkFraction.ONE + rate()) else entered
    }
    fun rate(): JdkFraction = JdkFraction.decimal(tax) / HUNDRED
}

private data class OracleCharge(val number: Int, val percent: String, val tax: String, val portions: Set<Int>) {
    fun factor(): JdkFraction = JdkFraction.decimal(percent) / HUNDRED * (JdkFraction.ONE + JdkFraction.decimal(tax) / HUNDRED)
}
private data class OracleFixed(val amount: String, val tax: String, val inclusive: Boolean)

private data class OracleCase(
    val lines: List<OracleLine>,
    val charges: List<OracleCharge>,
    val fixedCharge: OracleFixed?,
    val eligible: Set<Int>,
    val discountMinor: Long,
) {
    fun eligibleGross(): JdkFraction = lines.filter { it.number in eligible }.map { line ->
        line.net() * (JdkFraction.ONE + line.rate() + charges.filter { line.number in it.portions }.map { it.factor() }.sumJdk())
    }.sumJdk()

    fun exactComponents(discounted: Boolean): Map<String, JdkFraction> {
        val result = sortedMapOf<String, JdkFraction>()
        val multiplier = if (!discounted || discountMinor == 0L) JdkFraction.ONE
            else JdkFraction.ONE - JdkFraction.minor(discountMinor) / eligibleGross()
        for (line in lines) {
            val net = line.net() * if (line.number in eligible) multiplier else JdkFraction.ONE
            val prefix = "p/${id(line.number).value}"
            result["$prefix/item/net"] = net
            result["$prefix/item/tax"] = net * line.rate()
            for (charge in charges.filter { line.number in it.portions }) {
                val chargeNet = net * JdkFraction.decimal(charge.percent) / HUNDRED
                result["$prefix/charge/${id(charge.number).value}/net"] = chargeNet
                result["$prefix/charge/${id(charge.number).value}/tax"] = chargeNet * JdkFraction.decimal(charge.tax) / HUNDRED
            }
        }
        fixedCharge?.let { fixed ->
            val rate = JdkFraction.decimal(fixed.tax) / HUNDRED
            val net = JdkFraction.decimal(fixed.amount) / if (fixed.inclusive) JdkFraction.ONE + rate else JdkFraction.ONE
            result["c/${id(7_700).value}/fixed/net"] = net
            result["c/${id(7_700).value}/fixed/tax"] = net * rate
        }
        return result
    }

    fun request(): PricingRequest {
        val sources = lines.map { source -> line(source.number, source.price, source.quantity, source.tax,
            if (source.inclusive) TaxInclusion.INCLUSIVE else TaxInclusion.EXCLUSIVE, scale = 3) }
        val rules = charges.map { charge -> service(charge.portions.map(::id).toSet(), charge.percent, charge.tax, charge.number) }
        val fixedRule = fixedCharge?.let { charge -> PricingCharge.Fixed(id(7_700), Revision(1), ChargeKind.DELIVERY,
            UnitPrice.parse(EGP, charge.amount), if (charge.inclusive) TaxInclusion.INCLUSIVE else TaxInclusion.EXCLUSIVE,
            tax(charge.tax), ChargeRefundTreatment.EXPLICIT_ONLY) }
        return request(sources, rules + listOfNotNull(fixedRule), listOf(fixed(discountMinor, eligible.map(::id))))
    }

    fun shrinks(): List<OracleCase> = buildList {
        if (fixedCharge != null) add(copy(fixedCharge = null))
        charges.indices.forEach { index -> add(copy(charges = charges.filterIndexed { i, _ -> i != index })) }
        if (lines.size > 1) for (line in lines) add(copy(lines = lines.filterNot { it == line },
            charges = charges.map { it.copy(portions = it.portions - line.number) }, eligible = eligible - line.number))
        if (discountMinor > 0) add(copy(discountMinor = discountMinor / 2))
    }
}

/** JDK-only absolute-quota largest-remainder oracle with canonical key ties. */
private fun postWithJdk(exacts: Map<String, JdkFraction>): Map<String, Long> {
    val total = exacts.values.sumJdk().round(2)
    val allocated = exacts.mapValues { (_, value) -> value.floor(2) }.toMutableMap()
    val remainders = exacts.mapValues { (key, value) -> value * HUNDRED - JdkFraction.whole(allocated.getValue(key)) }
    val count = (total - allocated.values.fold(BigInteger.ZERO, BigInteger::add)).intValueExact()
    val ordered = remainders.entries.sortedWith { first, second ->
        val compared = second.value.compareTo(first.value)
        if (compared != 0) compared else first.key.compareTo(second.key)
    }
    repeat(count) { index -> val key = ordered[index].key; allocated[key] = allocated.getValue(key) + BigInteger.ONE }
    return allocated.mapValues { it.value.longValueExact() }
}

/**
 * Independent dynamic-programming choice of retained cents (not production sort).
 * Unchanged components are fixed and changed floor/ceil choices cannot increase.
 * Random cases have generous line caps; exact-cap saturation is tested separately.
 */
private fun protectedPostWithJdk(
    before: Map<String, JdkFraction>,
    beforePosted: Map<String, Long>,
    after: Map<String, JdkFraction>,
): Map<String, Long>? {
    val baseline = sortedMapOf<String, Long>()
    val candidates = sortedMapOf<String, JdkFraction>()
    for ((key, exact) in after) {
        if (exact == before.getValue(key)) baseline[key] = beforePosted.getValue(key)
        else {
            val floor = exact.floor(2).longValueExact()
            baseline[key] = floor
            val remainder = exact * HUNDRED - JdkFraction.whole(floor)
            if (remainder > JdkFraction.ZERO && floor < beforePosted.getValue(key)) candidates[key] = remainder
        }
    }
    val residual = after.values.sumJdk().round(2).longValueExact() - baseline.values.sum()
    if (residual < 0 || residual > candidates.size) return null
    val choices = arrayOfNulls<OracleChoice>(residual.toInt() + 1)
    choices[0] = OracleChoice(JdkFraction.ZERO, emptyList())
    for ((key, remainder) in candidates) {
        for (count in residual.toInt() downTo 1) {
            val prior = choices[count - 1] ?: continue
            val option = OracleChoice(prior.score + remainder, prior.keys + key)
            val existing = choices[count]
            if (existing == null || option.score > existing.score ||
                (option.score == existing.score && option.keys.joinToString("\n") < existing.keys.joinToString("\n"))) choices[count] = option
        }
    }
    for (key in choices[residual.toInt()]?.keys ?: return null) baseline[key] = baseline.getValue(key) + 1
    return baseline
}

private data class OracleChoice(val score: JdkFraction, val keys: List<String>)

private fun Iterable<JdkFraction>.sumJdk(): JdkFraction = fold(JdkFraction.ZERO, JdkFraction::plus)

private class JdkFraction(n: BigInteger, d: BigInteger) : Comparable<JdkFraction> {
    private val numerator: BigInteger
    private val denominator: BigInteger
    init {
        require(d.signum() != 0)
        val gcd = n.gcd(d)
        numerator = n / gcd * BigInteger.valueOf(d.signum().toLong())
        denominator = d.abs() / gcd
    }
    operator fun plus(other: JdkFraction): JdkFraction = JdkFraction(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
    operator fun minus(other: JdkFraction): JdkFraction = JdkFraction(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)
    operator fun times(other: JdkFraction): JdkFraction = JdkFraction(numerator * other.numerator, denominator * other.denominator)
    operator fun div(other: JdkFraction): JdkFraction = JdkFraction(numerator * other.denominator, denominator * other.numerator)
    override fun compareTo(other: JdkFraction): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)
    override fun equals(other: Any?): Boolean = other is JdkFraction && numerator == other.numerator && denominator == other.denominator
    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()
    fun floor(scale: Int): BigInteger {
        require(numerator.signum() >= 0)
        return numerator * BigInteger.TEN.pow(scale) / denominator
    }
    fun round(scale: Int): BigInteger {
        require(numerator.signum() >= 0)
        val divided = (numerator * BigInteger.TEN.pow(scale)).divideAndRemainder(denominator)
        return divided[0] + if (divided[1] * BigInteger.TWO >= denominator) BigInteger.ONE else BigInteger.ZERO
    }
    fun canonical(): String = "$numerator/$denominator"

    companion object {
        val ZERO = whole(0)
        val ONE = whole(1)
        fun whole(value: Long): JdkFraction = JdkFraction(BigInteger.valueOf(value), BigInteger.ONE)
        fun whole(value: BigInteger): JdkFraction = JdkFraction(value, BigInteger.ONE)
        fun minor(value: Long): JdkFraction = JdkFraction(BigInteger.valueOf(value), BigInteger.valueOf(100))
        fun decimal(value: String): JdkFraction {
            val parsed = BigDecimal(value)
            return JdkFraction(parsed.unscaledValue(), BigInteger.TEN.pow(parsed.scale()))
        }
        fun parse(value: String): JdkFraction = JdkFraction(BigInteger(value.substringBefore('/')), BigInteger(value.substringAfter('/')))
    }
}

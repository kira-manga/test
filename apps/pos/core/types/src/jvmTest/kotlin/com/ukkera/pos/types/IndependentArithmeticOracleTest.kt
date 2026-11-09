package com.ukkera.pos.types

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** JDK arithmetic deliberately shares no implementation with the portable bignum code. */
class IndependentArithmeticOracleTest {
    @Test
    fun rationalComparisonMatchesJdkBigIntegersAcrossSigned64BitComponents() {
        val random = Random(0x434F4D50)
        repeat(5_000) {
            val a = random.nextLong()
            val b = random.nextLong().let { if (it == 0L) 1L else it }
            val c = random.nextLong()
            val d = random.nextLong().let { if (it == 0L) 1L else it }
            val cross = BigInteger.valueOf(a) * BigInteger.valueOf(d) - BigInteger.valueOf(c) * BigInteger.valueOf(b)
            val expectedSign = cross.signum() * (if ((b < 0) == (d < 0)) 1 else -1)
            val actual = ExactRational.ratio(a, b).compareTo(ExactRational.ratio(c, d))
            assertEquals(expectedSign, actual.compareTo(0), "$a/$b vs $c/$d")
        }
    }

    @Test
    fun exactConversionAndSignedFloorMatchJdkWithoutSharingRoundingImplementation() {
        val random = Random(0x464C4F4F)
        repeat(5_000) { index ->
            val numerator = if (index % 10 == 0) random.nextLong() else random.nextLong(-1_000_000_000L, 1_000_000_000L)
            val denominator = when (index % 5) {
                0 -> 1L
                1 -> 8L
                2 -> -100L
                else -> random.nextLong(1, 1_000_000L)
            }
            val scale = random.nextInt(0, 9)
            val rational = ExactRational.ratio(numerator, denominator)
            val referenceNumerator = BigDecimal.valueOf(numerator)
            val referenceDenominator = BigDecimal.valueOf(denominator)
            val floor = runCatching {
                referenceNumerator.divide(referenceDenominator, scale, RoundingMode.FLOOR).movePointRight(scale).longValueExact()
            }
            if (floor.isSuccess) assertEquals(floor.getOrThrow(), rational.floorScaled(scale))
            else assertFailsWith<ArithmeticException> { rational.floorScaled(scale) }
            val exact = runCatching {
                referenceNumerator.divide(referenceDenominator, scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact()
            }
            if (exact.isSuccess) assertEquals(exact.getOrThrow(), rational.toScaledLongExact(scale))
            else assertFailsWith<ArithmeticException> { rational.toScaledLongExact(scale) }
        }
    }

    @Test
    fun quantityRescalingAndCheckedAdditionMatchJdkBigDecimalAndBigInteger() {
        val unit = DomainId("10000000-0000-4000-8000-000000000001")
        val random = Random(0x5155414E)
        repeat(3_000) {
            val a = random.nextLong()
            val b = random.nextLong()
            val scale = random.nextInt(0, 7)
            val targetScale = random.nextInt(0, 7)
            val quantity = Quantity(unit, a, scale)
            val expectedScale = runCatching {
                BigDecimal.valueOf(a, scale).movePointRight(targetScale).longValueExact()
            }
            if (expectedScale.isSuccess) assertEquals(expectedScale.getOrThrow(), quantity.rescaleExact(targetScale).scaled)
            else assertFailsWith<ArithmeticException> { quantity.rescaleExact(targetScale) }
            val expectedSum = runCatching { (BigInteger.valueOf(a) + BigInteger.valueOf(b)).longValueExact() }
            if (expectedSum.isSuccess) assertEquals(expectedSum.getOrThrow(), (quantity + Quantity(unit, b, scale)).scaled)
            else assertFailsWith<ArithmeticException> { quantity + Quantity(unit, b, scale) }
        }
    }

    @Test
    fun signedDecimalPostingMatchesJdkHalfUpAcrossTenThousandValues() {
        val random = Random(0x554B4B45)
        repeat(10_000) {
            val coefficient = random.nextLong(-1_000_000_000_000L, 1_000_000_000_000L)
            val scale = random.nextInt(0, 9)
            val reference = BigDecimal.valueOf(coefficient, scale)
            val expected = reference.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
            val actual = ExactDecimal.parse(reference.toPlainString()).toFraction().toMoney(Currency.EGP).minor
            assertEquals(expected, actual, reference.toPlainString())
        }
    }

    @Test
    fun rationalPostingMatchesIndependentIntegerOracleAcrossSignedDenominators() {
        val random = Random(0x52415449)
        repeat(5_000) {
            val numerator = random.nextLong(-1_000_000_000L, 1_000_000_000L)
            var denominator = random.nextLong(-1_000_000L, 1_000_000L)
            if (denominator == 0L) denominator = 1
            val scale = random.nextInt(0, 7)
            val expected = BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP)
                .movePointRight(scale).longValueExact()
            assertEquals(expected, ExactRational.ratio(numerator, denominator).roundHalfUp(scale))
        }
    }

    @Test
    fun allocationMatchesJdkOracleAndConservesForLargeSignedMoneyAndWeights() {
        val random = Random(0x414C4C4F)
        repeat(2_000) { iteration ->
            val total = when (iteration % 50) { 0 -> Long.MIN_VALUE; 1 -> Long.MAX_VALUE; else -> random.nextLong() }
            val weights = List(random.nextInt(1, 31)) { index ->
                AllocationWeight("id-${index.toString().padStart(2, '0')}", if (index % 7 == 6) 0L else random.nextLong(1, Long.MAX_VALUE))
            }.shuffled(random)
            val actual = allocateByLargestRemainder(Money(Currency.EGP, total), weights).associate { it.key to it.amount.minor }
            val expected = oracleAllocate(total, weights)
            assertEquals(expected, actual, "iteration $iteration")
            assertEquals(BigInteger.valueOf(total), actual.values.fold(BigInteger.ZERO) { sum, n -> sum + BigInteger.valueOf(n) })
            assertEquals(actual, allocateByLargestRemainder(Money(Currency.EGP, total), weights.shuffled(random)).associate { it.key to it.amount.minor })
            assertTrue(actual.values.all { if (total < 0) it <= 0 else it >= 0 })
            weights.filter { it.weight == 0L }.forEach { assertEquals(0L, actual[it.key]) }
        }
    }

    private fun oracleAllocate(total: Long, weights: List<AllocationWeight>): Map<String, Long> {
        val sum = weights.fold(BigInteger.ZERO) { acc, item -> acc + BigInteger.valueOf(item.weight) }
        val magnitude = BigInteger.valueOf(total).abs()
        val quotients = weights.associate { item -> item.key to magnitude.multiply(BigInteger.valueOf(item.weight)).divideAndRemainder(sum) }
        val unallocated = magnitude - quotients.values.fold(BigInteger.ZERO) { acc, parts -> acc + parts[0] }
        val order = quotients.keys.sortedWith(compareByDescending<String> { quotients.getValue(it)[1] }.thenBy { it })
        val bonus = order.take(unallocated.intValueExact()).toSet()
        return quotients.mapValues { (key, parts) ->
            val value = parts[0] + if (key in bonus) BigInteger.ONE else BigInteger.ZERO
            (if (total < 0) value.negate() else value).longValueExact()
        }
    }
}

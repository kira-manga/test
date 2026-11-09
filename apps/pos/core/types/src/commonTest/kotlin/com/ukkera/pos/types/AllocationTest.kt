package com.ukkera.pos.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AllocationTest {
    @Test
    fun allocationConservesAndTiesUseInternalIdsNotInputOrder() {
        val weights = listOf(AllocationWeight("c", 1), AllocationWeight("a", 1), AllocationWeight("b", 1))
        val result = allocateByLargestRemainder(Money(Currency.EGP, 100), weights)
        assertEquals(listOf("c", "a", "b"), result.map { it.key })
        assertEquals(mapOf("a" to 34L, "b" to 33L, "c" to 33L), result.associate { it.key to it.amount.minor })
        assertEquals(result.associateBy { it.key },
            allocateByLargestRemainder(Money(Currency.EGP, 100), weights.reversed()).associateBy { it.key })
    }

    @Test
    fun negativeTotalsMirrorPositiveAllocations() {
        val weights = listOf(AllocationWeight("b", 1), AllocationWeight("a", 1), AllocationWeight("z", 0))
        for (total in listOf(0L, 1L, 3L, 127L, Long.MAX_VALUE)) {
            val positive = allocateByLargestRemainder(Money(Currency.EGP, total), weights)
            val negative = allocateByLargestRemainder(Money(Currency.EGP, -total), weights)
            assertEquals(positive.map { -it.amount.minor }, negative.map { it.amount.minor })
            assertEquals(0L, positive.last().amount.minor)
        }
    }

    @Test
    fun wholeSignedRangeAndOverflowingWeightSumRemainExact() {
        val weights = listOf(AllocationWeight("b", Long.MAX_VALUE), AllocationWeight("a", Long.MAX_VALUE))
        assertEquals(listOf(Long.MIN_VALUE / 2, Long.MIN_VALUE / 2),
            allocateByLargestRemainder(Money(Currency.EGP, Long.MIN_VALUE), weights).map { it.amount.minor })
        assertEquals(listOf(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2 + 1),
            allocateByLargestRemainder(Money(Currency.EGP, Long.MAX_VALUE), weights).map { it.amount.minor })
        assertEquals(Long.MIN_VALUE,
            allocateByLargestRemainder(Money(Currency.EGP, Long.MIN_VALUE), listOf(AllocationWeight("a", 1))).single().amount.minor)
    }

    @Test
    fun emptyAndZeroWeightsAreOnlyValidForZeroTotal() {
        assertEquals(emptyList(), allocateByLargestRemainder(Money(Currency.EGP, 0), emptyList()))
        assertEquals(0L, allocateByLargestRemainder(Money(Currency.EGP, 0), listOf(AllocationWeight("a", 0))).single().amount.minor)
        assertFailsWith<IllegalArgumentException> { allocateByLargestRemainder(Money(Currency.EGP, 1), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            allocateByLargestRemainder(Money(Currency.EGP, 1), listOf(AllocationWeight("a", 0)))
        }
    }

    @Test
    fun duplicateUnboundedLocalizedAndNegativeWeightsAreRejected() {
        val invalid = listOf(listOf(AllocationWeight("a", 1), AllocationWeight("a", 2)),
            listOf(AllocationWeight("a", -1)), listOf(AllocationWeight("", 1)),
            listOf(AllocationWeight("a b", 1)), listOf(AllocationWeight("س", 1)),
            listOf(AllocationWeight("a".repeat(257), 1)), List(10_001) { AllocationWeight("k$it", 1) })
        for (weights in invalid) assertFailsWith<IllegalArgumentException> {
            allocateByLargestRemainder(Money(Currency.EGP, 1), weights)
        }
    }
}

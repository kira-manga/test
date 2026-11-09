package com.ukkera.pos.security.independent.nativecredentialthrottle

import com.ukkera.pos.security.SelfOwnedFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.localNativeCredentialDeadlineMillis
import com.ukkera.pos.security.localNativeCredentialFailureDelayMillis
import com.ukkera.pos.security.localNativeCredentialNextFailureCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Frozen NCT-v1 arithmetic slices from qa/pos/designs/native-credential-throttle/cases.md.
 * Synthetic scalar inputs establish no native reservation, credential or authority. */
class LocalNativeCredentialThrottleSharedSlicesTest {
    @Test
    fun nctV1001NextCountBoundaries() {
        val vectors = listOf(
            0 to 1,
            1 to 2,
            8 to 9,
            9 to 10,
            999_998 to 999_999,
            999_999 to 1_000_000,
        )
        vectors.forEach { (retainedCount, expectedNextCount) ->
            assertEquals(
                expectedNextCount,
                localNativeCredentialNextFailureCount(retainedCount),
                "NCT-V1-001 retained=$retainedCount",
            )
        }
    }

    @Test
    fun nctV1002SaturatedCountSequence() {
        val first = localNativeCredentialNextFailureCount(1_000_000)
        assertEquals(1_000_000, first, "NCT-V1-002 first output")
        val second = localNativeCredentialNextFailureCount(first)
        assertEquals(1_000_000, second, "NCT-V1-002 second output")
        val third = localNativeCredentialNextFailureCount(second)
        assertEquals(1_000_000, third, "NCT-V1-002 third output")
    }

    @Test
    fun nctV1003UnsupportedRetainedCounts() {
        val vectors = listOf(-1_000_000, -1, 1_000_001, 2_000_000)
        vectors.forEach { retainedCount ->
            assertStateUncertain("NCT-V1-003 retained=$retainedCount") {
                localNativeCredentialNextFailureCount(retainedCount)
            }
        }
    }

    @Test
    fun nctV1004ExactDoublingAndCapTransition() {
        val vectors = listOf(
            DelayDeadlineVector(1, 1_000L, 1_001_000L),
            DelayDeadlineVector(2, 2_000L, 1_002_000L),
            DelayDeadlineVector(3, 4_000L, 1_004_000L),
            DelayDeadlineVector(4, 8_000L, 1_008_000L),
            DelayDeadlineVector(5, 16_000L, 1_016_000L),
            DelayDeadlineVector(6, 32_000L, 1_032_000L),
            DelayDeadlineVector(7, 64_000L, 1_064_000L),
            DelayDeadlineVector(8, 128_000L, 1_128_000L),
            DelayDeadlineVector(9, 256_000L, 1_256_000L),
            DelayDeadlineVector(10, 300_000L, 1_300_000L),
        )
        vectors.forEach { vector ->
            val context = "NCT-V1-004 reserved=${vector.reservedCount}"
            val actualDelay = localNativeCredentialFailureDelayMillis(vector.reservedCount)
            assertEquals(vector.expectedDelayMillis, actualDelay, "$context delay")
            assertEquals(
                vector.expectedDeadlineMillis,
                localNativeCredentialDeadlineMillis(ELAPSED_E, actualDelay),
                "$context deadline",
            )
        }
    }

    @Test
    fun nctV1005SupportedLargeCountsRemainCapped() {
        val vectors = listOf(11, 31, 32, 63, 64, 999_999, 1_000_000)
        vectors.forEach { reservedCount ->
            val context = "NCT-V1-005 reserved=$reservedCount"
            val actualDelay = localNativeCredentialFailureDelayMillis(reservedCount)
            assertEquals(300_000L, actualDelay, "$context delay")
            assertEquals(
                1_300_000L,
                localNativeCredentialDeadlineMillis(ELAPSED_E, actualDelay),
                "$context deadline",
            )
        }
    }

    @Test
    fun nctV1006InvalidFailureDelayCounts() {
        val vectors = listOf(-1_000_000, -1, 0, 1_000_001, 2_000_000)
        vectors.forEach { reservedCount ->
            assertStateUncertain("NCT-V1-006 reserved=$reservedCount") {
                localNativeCredentialFailureDelayMillis(reservedCount)
            }
        }
    }

    @Test
    fun nctV1007ValidDeadlineIdentitiesAndLongLimits() {
        val vectors = listOf(
            DeadlineVector(0L, 0L, 0L),
            DeadlineVector(0L, 1_000L, 1_000L),
            DeadlineVector(ELAPSED_E, 0L, 1_000_000L),
            DeadlineVector(ELAPSED_E, 300_001L, 1_300_001L),
            DeadlineVector(LONG_MAX, 0L, LONG_MAX),
            DeadlineVector(0L, LONG_MAX, LONG_MAX),
            DeadlineVector(9_223_372_036_854_775_806L, 1L, LONG_MAX),
            DeadlineVector(1L, 9_223_372_036_854_775_806L, LONG_MAX),
        )
        vectors.forEach { vector ->
            assertEquals(
                vector.expectedDeadlineMillis,
                localNativeCredentialDeadlineMillis(vector.elapsedMillis, vector.durationMillis),
                "NCT-V1-007 elapsed=${vector.elapsedMillis} duration=${vector.durationMillis}",
            )
        }
    }

    @Test
    fun nctV1008NegativeDeadlineOperands() {
        val vectors = listOf(
            -1L to 0L,
            0L to -1L,
            -1L to 1L,
            1L to -1L,
            -1L to -1L,
            LONG_MAX to -1L,
            -1L to LONG_MAX,
            Long.MIN_VALUE to 0L,
            0L to Long.MIN_VALUE,
        )
        vectors.forEach { (elapsedMillis, durationMillis) ->
            assertStateUncertain("NCT-V1-008 elapsed=$elapsedMillis duration=$durationMillis") {
                localNativeCredentialDeadlineMillis(elapsedMillis, durationMillis)
            }
        }
    }

    @Test
    fun nctV1009OverflowingDeadlineSums() {
        val vectors = listOf(
            LONG_MAX to 1L,
            1L to LONG_MAX,
            9_223_372_036_854_775_806L to 2L,
            LONG_MAX to LONG_MAX,
        )
        vectors.forEach { (elapsedMillis, durationMillis) ->
            assertStateUncertain("NCT-V1-009 elapsed=$elapsedMillis duration=$durationMillis") {
                localNativeCredentialDeadlineMillis(elapsedMillis, durationMillis)
            }
        }
    }

    @Test
    fun nctV1010ComposedExactFitAndOverflowBoundaries() {
        val vectors = listOf(
            ComposedVector(8, EXACT_FIT_E9, 9, 256_000L, LONG_MAX),
            ComposedVector(8, EXACT_FIT_E9_PLUS_ONE, 9, 256_000L, null),
            ComposedVector(9, EXACT_FIT_E10, 10, 300_000L, LONG_MAX),
            ComposedVector(9, EXACT_FIT_E10_PLUS_ONE, 10, 300_000L, null),
            ComposedVector(999_999, EXACT_FIT_E10, 1_000_000, 300_000L, LONG_MAX),
            ComposedVector(999_999, EXACT_FIT_E10_PLUS_ONE, 1_000_000, 300_000L, null),
            ComposedVector(1_000_000, EXACT_FIT_E10, 1_000_000, 300_000L, LONG_MAX),
            ComposedVector(1_000_000, EXACT_FIT_E10_PLUS_ONE, 1_000_000, 300_000L, null),
        )
        vectors.forEach { vector ->
            val context = "NCT-V1-010 retained=${vector.retainedCount} elapsed=${vector.elapsedMillis}"
            val actualNextCount = localNativeCredentialNextFailureCount(vector.retainedCount)
            assertEquals(vector.expectedNextCount, actualNextCount, "$context next count")
            val actualDelay = localNativeCredentialFailureDelayMillis(actualNextCount)
            assertEquals(vector.expectedDelayMillis, actualDelay, "$context delay")
            val expectedDeadline = vector.expectedDeadlineMillis
            if (expectedDeadline == null) {
                assertStateUncertain("$context deadline") {
                    localNativeCredentialDeadlineMillis(vector.elapsedMillis, actualDelay)
                }
            } else {
                assertEquals(
                    expectedDeadline,
                    localNativeCredentialDeadlineMillis(vector.elapsedMillis, actualDelay),
                    "$context deadline",
                )
            }
        }
    }

    private fun assertStateUncertain(context: String, action: () -> Unit) {
        val failure = assertFailsWith<SelfOwnedFailureV1>(context) { action() }
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, failure.reason, context)
    }

    private data class DelayDeadlineVector(
        val reservedCount: Int,
        val expectedDelayMillis: Long,
        val expectedDeadlineMillis: Long,
    )

    private data class DeadlineVector(
        val elapsedMillis: Long,
        val durationMillis: Long,
        val expectedDeadlineMillis: Long,
    )

    private data class ComposedVector(
        val retainedCount: Int,
        val elapsedMillis: Long,
        val expectedNextCount: Int,
        val expectedDelayMillis: Long,
        val expectedDeadlineMillis: Long?,
    )

    private companion object {
        const val ELAPSED_E: Long = 1_000_000L
        const val LONG_MAX: Long = 9_223_372_036_854_775_807L
        const val EXACT_FIT_E9: Long = 9_223_372_036_854_519_807L
        const val EXACT_FIT_E9_PLUS_ONE: Long = 9_223_372_036_854_519_808L
        const val EXACT_FIT_E10: Long = 9_223_372_036_854_475_807L
        const val EXACT_FIT_E10_PLUS_ONE: Long = 9_223_372_036_854_475_808L
    }
}

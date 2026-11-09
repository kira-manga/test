package com.ukkera.pos.sales.independent.returnelapsedwindows

import com.ukkera.pos.sales.ElapsedReturnWindow
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Partial temporal/constructor slices of return-elapsed-windows-v1, not whole
 * case acceptance. Starts, periods and submissions are already-approved numeric
 * premises; this pure helper supplies no evidence, policy or workflow authority.
 *
 * RTE-U09 evidence guards, U10 publication/activation, U11 synthetic checked-tick
 * limits and all genuine production/native integration assertions remain unbound.
 * DST, cutoff and later-processing groups exercise their UTC tuples only, not
 * configuration, timezone projection or immutable submission-history integration.
 */
class ElapsedReturnWindowFrozenTimingSlicesTest {
    @Test
    fun rteU01ConfigurablePeriodsMatchFrozenTemporalTuples() {
        assertFrozenTiming("RTE-U01", ElapsedReturnWindowFrozenInputs.configurablePeriods)
    }

    @Test
    fun rteU02MillisecondBoundaryPreservesInclusiveExpiry() {
        assertFrozenTiming("RTE-U02", ElapsedReturnWindowFrozenInputs.millisecondBoundary)
    }

    @Test
    fun rteU03NanosecondBoundaryPreservesInclusiveExpiry() {
        assertFrozenTiming("RTE-U03", ElapsedReturnWindowFrozenInputs.nanosecondBoundary)
    }

    @Test
    fun rteU04FractionalCarryDoesNotAdmitTheNextSecond() {
        assertFrozenTiming("RTE-U04", ElapsedReturnWindowFrozenInputs.fractionalCarry)
    }

    @Test
    fun rteU05CalendarCrossingTuplesKeepExactElapsedDurations() {
        assertFrozenTiming("RTE-U05", ElapsedReturnWindowFrozenInputs.calendarCrossings)
    }

    @Test
    fun rteU06DstAndRepeatedOffsetUtcTuplesKeepFrozenDeadlines() {
        assertFrozenTiming("RTE-U06", ElapsedReturnWindowFrozenInputs.dstUtcTuples)
        assertFrozenTiming("RTE-U06", ElapsedReturnWindowFrozenInputs.repeatedOffsetTuples)
    }

    @Test
    fun rteU07ClosingCutoffNumericSlicesMatchFrozenTuples() {
        assertFrozenTiming("RTE-U07", ElapsedReturnWindowFrozenInputs.closingCutoffTuples)
    }

    @Test
    fun rteU08SubmissionComparisonSlicesUseOnlyFrozenSubmission() {
        // There is no processing-clock input here; retained T/P history is unbound.
        assertFrozenTiming("RTE-U08", ElapsedReturnWindowFrozenInputs.laterProcessingTuples)
    }

    @Test
    fun rteU10NonpositiveDaysAreRejectedByTimingConstructor() {
        ElapsedReturnWindowFrozenInputs.nonpositivePeriods.forEach { vector ->
            // Decode the bindable fixture before the assertion, so a harness
            // conversion failure cannot masquerade as production rejection.
            val days = vector.receiptWindowDays.toInt()
            assertFailsWith<IllegalArgumentException>("RTE-U10/${vector.id}: ${vector.expectedState}") {
                ElapsedReturnWindow(days)
            }
        }
    }

    @Test
    fun rteU11WideExactSecondDurationsDoNotWrapOrLoseFraction() {
        // The frozen wide tuples assert epoch seconds/nanos, not ISO or tick ranges.
        assertFrozenTiming("RTE-U11", ElapsedReturnWindowFrozenInputs.wideExactSeconds)
    }

    @Test
    fun rteU12PromiseAndReplacementCounterexampleCompareNumericPeriodsOnly() {
        // This does not join or choose between original promises and current policy.
        assertFrozenTiming("RTE-U12", ElapsedReturnWindowFrozenInputs.preservedOriginalPromiseTuple)
        assertFrozenTiming("RTE-U12", ElapsedReturnWindowFrozenInputs.forbiddenReplacementCounterexampleTuple)
    }

    @Test
    fun rteI03ConditionalMultiDateIntervalsAreTemporalOnly() {
        // No receipt allocation or earliest/latest starting rule is approved here.
        assertFrozenTiming("RTE-I03", ElapsedReturnWindowFrozenInputs.conditionalMultiDateTuples)
    }

    @Test
    fun rteI09ConditionalDefectIntervalsAreTemporalOnly() {
        // A numeric ordinary-window result is not defect classification or denial.
        assertFrozenTiming("RTE-I09", ElapsedReturnWindowFrozenInputs.conditionalDefectTuples)
    }

    private fun assertFrozenTiming(caseId: String, vectors: List<FrozenElapsedTimingVector>) {
        vectors.forEach { vector ->
            val label = "$caseId/${vector.id}"
            val start = vector.approvedStart.toUtcInstant()
            val submission = vector.authoritativeSubmission.toUtcInstant()
            assertFrozenInstant(vector.approvedStart, start, "$label approved start")
            assertFrozenInstant(vector.authoritativeSubmission, submission, "$label submission")

            val window = ElapsedReturnWindow(vector.approvedDays)
            val expiry = assertNotNull(window.expiresAt(start), "$label exact expiry must be representable")
            assertFrozenInstant(vector.expectedExpiry, expiry, "$label expiry")

            // Expected deadlines and durations come from the frozen tuples, never
            // from the implementation or a second calculation of days × 86,400.
            val observedElapsedSeconds = expiry.value.epochSeconds - start.value.epochSeconds
            assertEquals(vector.durationSeconds.toLong(), observedElapsedSeconds, "$label duration seconds")
            assertEquals(vector.durationHours.toLong(), observedElapsedSeconds / 3_600L, "$label duration hours")

            val expectedTiming = when (vector.expectedTiming) {
                "WITHIN_WINDOW" -> true
                "OUTSIDE_WINDOW" -> false
                else -> error("$label is not a bindable frozen temporal result")
            }
            assertEquals(expectedTiming, window.containsSubmission(start, submission), "$label ${vector.expectedTiming}")
        }
    }

    private fun assertFrozenInstant(expected: FrozenElapsedInstantTuple, actual: UtcInstant, label: String) {
        assertEquals(expected.epochSecond.toLong(), actual.value.epochSeconds, "$label epoch seconds")
        assertEquals(expected.nanosecond.toInt(), actual.value.nanosecondsOfSecond, "$label nanosecond fraction")
    }

    private fun FrozenElapsedInstantTuple.toUtcInstant(): UtcInstant =
        UtcInstant(Instant.fromEpochSeconds(epochSecond.toLong(), nanosecond.toInt()))
}

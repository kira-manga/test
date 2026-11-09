package com.ukkera.pos.sales.store.independent.returnsubmissiontimingv1

import com.ukkera.pos.sales.ElapsedReturnWindow
import com.ukkera.pos.sales.store.LocalReturnCommercialTimingV1
import com.ukkera.pos.sales.store.LocalReturnCommercialWindowStatusV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Six method-level numeric slices of frozen return-submission-timing-v1 U01–U06.
 * Supported timing facts bind to the observation value/observe and elapsed-window
 * interfaces; within/outside are temporal observations, never eligibility.
 * The required IDs and UTC label below are inert unasserted shape fillers, not
 * frozen source/operation identities or evidence of an approved receipt zone.
 * No principal, trusted source record or accepting qualification is constructed.
 *
 * UTC/offset checks bind only the supplied absolute representations, not a UI or
 * IANA-zone/civil-calendar workflow. Closing/claimed/processing fixtures bind only
 * their numeric projection; no configuration, actual clock or immutable intake
 * history is exercised. OV reference values bind exact seconds/nanoseconds only,
 * not product range limits, a signed-64 storage type or a timing/eligibility grant.
 * All I01–I12/P01–P03 genuine source, PENDING, history, authority, recovery,
 * platform, business-effect and whole-case assertions remain unbound here.
 * No QUALIFY wire format is inferred from this pre-format freeze.
 */
class ReturnSubmissionTimingFrozenNumericSlicesTest {
    private val frozen: FrozenReturnSubmissionTimingInputs = FrozenReturnSubmissionTimingInputs

    @Test
    fun rtsU01_inclusiveNanosecondBoundary_numericObservationOnly() {
        frozen.forCase("RTS-U01").forEach { assertNumericTiming(it) }
    }

    @Test
    fun rtsU02_configurableElapsedDurations_numericObservationOnly() {
        // The 1/7/30-day values are synthetic configurable periods, not legal categories.
        frozen.forCase("RTS-U02").forEach { assertNumericTiming(it) }
    }

    @Test
    fun rtsU03_suppliedUtcAndOffsetDstTuples_numericEquivalenceOnly() {
        frozen.forCase("RTS-U03").forEach { vector ->
            val utcObservation = assertNumericTiming(vector)
            val offsetStart = numericInstant(vector.text("start_explicit_offset"))
            val offsetSubmission = numericInstant(vector.text("expiry_explicit_offset"))
            val offsetObservation = assertNumericTiming(vector, offsetStart, offsetSubmission)
            assertEquals(utcObservation.receivedAt, offsetObservation.receivedAt, vector.text("id"))
            assertEquals(utcObservation.submittedAt, offsetObservation.submittedAt, vector.text("id"))
            assertEquals(utcObservation.expiresAt, offsetObservation.expiresAt, vector.text("id"))

            val expiry = assertNotNull(utcObservation.expiresAt)
            val elapsedSeconds = expiry.value.epochSeconds - utcObservation.receivedAt.value.epochSeconds
            assertNotEquals(vector.text("following_civil_day_same_clock_elapsed_seconds").toLong(), elapsedSeconds,
                "${vector.text("id")}: the supplied civil-clock interval is not the elapsed expiry")
            // The supplied offset expiry is an absolute-instant oracle, not an output-format/UI assertion.
        }
    }

    @Test
    fun rtsU04_afterClosingBeforeExpiry_numericObservationOnly() {
        // No closing/cutoff configuration is accepted by these pure interfaces.
        frozen.forCase("RTS-U04").forEach { assertNumericTiming(it) }
    }

    @Test
    fun rtsU05_originalSubmissionDistinctFromClaimAndProcessing_numericProjectionOnly() {
        frozen.forCase("RTS-U05").forEach { vector ->
            val observation = assertNumericTiming(vector)
            val claimed = numericInstant(vector.text("customer_claimed_time"))
            val processing = numericInstant(vector.text("later_qualification_or_processing"))
            assertNotEquals(claimed, observation.submittedAt, vector.text("id"))
            assertNotEquals(processing, observation.submittedAt, vector.text("id"))
            // C and Q are not substituted for the supplied S. This cannot prove that
            // a Master captured S, or that an actual later qualification retained it.
        }
    }

    @Test
    fun rtsU06_epochSecondRepresentationRetainsExactReferenceExpiries_arithmeticOnly() {
        frozen.forCase("RTS-U06").forEach { vector ->
            val label = "${vector.text("case")}/${vector.text("id")}"
            val startTuple = splitEpochNanoseconds(vector.text("start_epoch_nanoseconds"))
            val expiryTuple = splitEpochNanoseconds(vector.text("exact_expiry_epoch_nanoseconds"))
            val start = startTuple.toNumericInstant()
            val expectedExpiry = expiryTuple.toNumericInstant()
            assertEpochTuple(startTuple, start, "$label supplied start")
            assertEpochTuple(expiryTuple, expectedExpiry, "$label supplied exact expiry")

            // The supplied exact values are represented as seconds plus nanoseconds;
            // no signed-64 total-nanosecond narrowing, wrapped or saturated expected
            // value is used. Exact tuple checks also detect factory clamping.
            val actualExpiry = assertNotNull(ElapsedReturnWindow(vector.text("configured_days").toInt())
                .expiresAt(start), "$label expiry in the exercised exact representation")
            assertEpochTuple(expiryTuple, actualExpiry, "$label expiry")
            assertEquals(expectedExpiry, actualExpiry, "$label expiry")
            val observedSeconds = actualExpiry.value.epochSeconds - start.value.epochSeconds
            assertEquals(vector.text("elapsed_seconds").toLong(), observedSeconds, "$label elapsed seconds")
            assertEquals(start.value.nanosecondsOfSecond, actualExpiry.value.nanosecondsOfSecond,
                "$label fractional second")
            assertEquals(vector.text("elapsed_nanoseconds"), observedSeconds.toString() + "000000000",
                "$label exact elapsed nanoseconds without Long narrowing")
            // OV fixtures supply no submission instant. No status, actual production
            // overflow boundary, supported-range limit or business effect is inferred.
        }
    }

    private fun assertNumericTiming(vector: JsonObject,
        actualStart: UtcInstant = numericInstant(vector.text("approved_start")),
        actualSubmission: UtcInstant = numericInstant(vector.text("original_submission"))): LocalReturnCommercialTimingV1 {
        val label = "${vector.text("case")}/${vector.text("id")}"
        val expectedStart = numericInstant(vector.text("approved_start"))
        val expectedSubmission = numericInstant(vector.text("original_submission"))
        val expectedExpiry = numericInstant(vector.text("exact_expiry"))
        val days = vector.text("configured_days").toInt()
        val within = when (vector.text("expected_timing")) {
            "within" -> true
            "outside" -> false
            else -> error("$label has no frozen bindable numeric timing expectation")
        }
        val expectedStatus = if (within) LocalReturnCommercialWindowStatusV1.WITHIN
            else LocalReturnCommercialWindowStatusV1.OUTSIDE
        assertEquals(expectedStart, actualStart, "$label supplied start")
        assertEquals(expectedSubmission, actualSubmission, "$label supplied submission")

        val observation = LocalReturnCommercialTimingV1.observe(days, SHAPE_ONLY_RECEIPT_ID,
            actualStart, SHAPE_ONLY_ZONE, SHAPE_ONLY_SUBMISSION_ID, actualSubmission)
        assertEquals(days, observation.days, "$label original duration")
        assertEquals(expectedStart, observation.receivedAt, "$label receipt start")
        assertEquals(expectedSubmission, observation.submittedAt, "$label original submission")
        assertEquals(expectedStatus, observation.status, "$label temporal status only")
        val observedExpiry = assertNotNull(observation.expiresAt, "$label exact expiry")
        assertEquals(expectedExpiry, observedExpiry, "$label exact expiry")

        val window = ElapsedReturnWindow(days)
        assertEquals(expectedExpiry, window.expiresAt(actualStart), "$label elapsed-window expiry")
        assertEquals(within, window.containsSubmission(actualStart, actualSubmission), "$label inclusive comparison")

        // Observed quantities are compared with frozen integers, not with deadlines
        // calculated by a second days-times-seconds expected-value implementation.
        val nanosPerSecond = frozen.arithmetic.text("nanoseconds_per_second").toLong()
        val observedSeconds = observedExpiry.value.epochSeconds - actualStart.value.epochSeconds
        val observedNanos = observedSeconds * nanosPerSecond +
            observedExpiry.value.nanosecondsOfSecond - actualStart.value.nanosecondsOfSecond
        val submissionDeltaNanos = (actualSubmission.value.epochSeconds - observedExpiry.value.epochSeconds) *
            nanosPerSecond + actualSubmission.value.nanosecondsOfSecond - observedExpiry.value.nanosecondsOfSecond
        assertEquals(vector.text("elapsed_seconds").toLong(), observedSeconds, "$label elapsed seconds")
        assertEquals(vector.text("elapsed_nanoseconds").toLong(), observedNanos, "$label elapsed nanoseconds")
        assertEquals(vector.text("submission_minus_expiry_nanoseconds").toLong(), submissionDeltaNanos,
            "$label submission minus expiry")
        return observation
    }

    /** Numeric parsing only: neither the fixture spelling nor offsets assert a wire format. */
    private fun numericInstant(text: String): UtcInstant = UtcInstant(Instant.parse(text))

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private data class EpochNanoTuple(val seconds: Long, val nanos: Int) {
        fun toNumericInstant(): UtcInstant = UtcInstant(Instant.fromEpochSeconds(seconds, nanos))
    }

    /** Lossless decimal splitting of the two nonnegative frozen reference values. */
    private fun splitEpochNanoseconds(text: String): EpochNanoTuple {
        require(text.isNotEmpty() && text.all { it in '0'..'9' })
        val padded = text.padStart(10, '0')
        val split = padded.length - 9
        return EpochNanoTuple(padded.substring(0, split).toLong(), padded.substring(split).toInt())
    }

    private fun assertEpochTuple(expected: EpochNanoTuple, actual: UtcInstant, label: String) {
        assertEquals(expected.seconds, actual.value.epochSeconds, "$label epoch seconds")
        assertEquals(expected.nanos, actual.value.nanosecondsOfSecond, "$label nanoseconds")
    }

    private companion object {
        // Required container coordinates only. No actual original/history identity or zone is asserted.
        val SHAPE_ONLY_RECEIPT_ID: DomainId = DomainId("00000000-0000-4000-8000-000000000001")
        val SHAPE_ONLY_SUBMISSION_ID: OperationId = OperationId("00000000-0000-4000-8000-000000000002")
        const val SHAPE_ONLY_ZONE: String = "UTC"
    }
}

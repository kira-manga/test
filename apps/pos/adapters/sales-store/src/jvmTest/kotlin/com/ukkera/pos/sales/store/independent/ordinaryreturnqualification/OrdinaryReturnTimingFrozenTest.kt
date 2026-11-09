package com.ukkera.pos.sales.store.independent.ordinaryreturnqualification

import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import kotlin.time.Instant

/** RQ v1 L1 arithmetic/representation support only. These values do not prove
 * paid-sale/receipt history, signed applicability, employee authority or eligibility. */
class OrdinaryReturnTimingFrozenTest {
    private val receiptId = DomainId("00000000-0000-4000-8000-000000000503")
    private val recordOperation = OperationId("00000000-0000-4000-8000-000000000602")
    private val inspectionId = DomainId("00000000-0000-4000-8000-000000000504")
    private val received = instant("2026-10-20T14:27:18.125Z")

    @Test
    fun rq003_rq006_frozenBeforeExactAfterElapsedWindowsRetainMilliseconds() {
        val vectors = listOf(
            Boundary(3, 259_200, "2026-10-23T14:27:18.124Z", "2026-10-23T14:27:18.125Z", "2026-10-23T14:27:18.126Z"),
            Boundary(7, 604_800, "2026-10-27T14:27:18.124Z", "2026-10-27T14:27:18.125Z", "2026-10-27T14:27:18.126Z"),
            Boundary(14, 1_209_600, "2026-11-03T14:27:18.124Z", "2026-11-03T14:27:18.125Z", "2026-11-03T14:27:18.126Z"),
            Boundary(21, 1_814_400, "2026-11-10T14:27:18.124Z", "2026-11-10T14:27:18.125Z", "2026-11-10T14:27:18.126Z"),
            Boundary(30, 2_592_000, "2026-11-19T14:27:18.124Z", "2026-11-19T14:27:18.125Z", "2026-11-19T14:27:18.126Z"))
        for (vector in vectors) {
            for ((submitted, expected) in listOf(vector.before to LocalReturnCommercialWindowStatusV1.WITHIN,
                vector.exact to LocalReturnCommercialWindowStatusV1.WITHIN,
                vector.after to LocalReturnCommercialWindowStatusV1.OUTSIDE)) {
                val timing = observe(vector.days, vector.days, received, instant(submitted))
                assertEquals(expected, timing.status, "${vector.days}: $submitted")
                assertEquals(instant(vector.exact), timing.expiresAt)
                assertEquals(vector.seconds, (assertNotNull(timing.expiresAt).value - received.value).inWholeSeconds)
                assertEquals(vector.days, timing.originalCommercialDays)
                assertEquals(vector.days, timing.applicableReceiptWindowDays)
                assertEquals(recordOperation, timing.submissionOperationId)
                assertEquals(receiptId, timing.receiptEventId)
            }
        }
        // The 3/7-day rows are explicitly arithmetic DATA; this test grants no legal applicability.
    }

    @Test
    fun rq007_bothFrozenPeriodsRemainAuditableAndMoreFavorablePeriodControls() {
        val receipt = instant("2026-10-01T10:00:00.125Z")
        val vectors = listOf(
            Favorable(21, 14, 21, "2026-10-20T10:00:00.125Z", "2026-10-22T10:00:00.125Z"),
            Favorable(14, 30, 30, "2026-10-25T10:00:00.125Z", "2026-10-31T10:00:00.125Z"),
            Favorable(30, 21, 30, "2026-10-25T10:00:00.125Z", "2026-10-31T10:00:00.125Z"))
        for (vector in vectors) {
            val timing = observe(vector.captured, vector.current, receipt, instant(vector.submitted))
            assertEquals(vector.captured, timing.originalCommercialDays)
            assertEquals(vector.current, timing.applicableReceiptWindowDays)
            assertEquals(vector.effective, timing.effectiveDays)
            assertEquals(instant(vector.expiry), timing.expiresAt)
            assertEquals(LocalReturnCommercialWindowStatusV1.WITHIN, timing.status)
        }
    }

    @Test
    fun rq004_laterProcessingCannotChangeFrozenOriginalSubmissionCoordinate() {
        val original = instant("2026-11-03T14:27:18.124Z")
        val timing = observe(14, 14, received, original)
        assertEquals(instant("2026-11-03T14:27:18.125Z"), timing.expiresAt)
        assertTrue(instant("2026-11-06T09:00:00.000Z") > assertNotNull(timing.expiresAt))
        assertTrue(instant("2026-11-07T09:00:00.000Z") > assertNotNull(timing.expiresAt))
        assertEquals(recordOperation, timing.submissionOperationId)
        assertEquals(original, timing.submittedAt)
        assertEquals(LocalReturnCommercialWindowStatusV1.WITHIN, timing.status)
    }

    @Test
    fun rq008_cairoDstFrozenOffsetInstantsUseElapsedDaysInsteadOfCivilDate() {
        val receipt = instant("2026-10-24T23:30:00.250+03:00")
        assertEquals(instant("2026-10-24T20:30:00.250Z"), receipt)
        for ((submitted, expected) in listOf(
            "2026-11-07T20:30:00.249Z" to LocalReturnCommercialWindowStatusV1.WITHIN,
            "2026-11-07T20:30:00.250Z" to LocalReturnCommercialWindowStatusV1.WITHIN,
            "2026-11-07T22:30:00.250+02:00" to LocalReturnCommercialWindowStatusV1.WITHIN,
            "2026-11-07T20:30:00.251Z" to LocalReturnCommercialWindowStatusV1.OUTSIDE,
            "2026-11-07T23:30:00.250+02:00" to LocalReturnCommercialWindowStatusV1.OUTSIDE)) {
            val timing = observe(14, 14, receipt, instant(submitted))
            assertEquals(instant("2026-11-07T20:30:00.250Z"), timing.expiresAt)
            assertEquals(1_209_600L, (assertNotNull(timing.expiresAt).value - receipt.value).inWholeSeconds)
            assertEquals(expected, timing.status, submitted)
        }
    }

    @Test
    fun rq009_rq010_chronologyAndExcessiveFrozenDurationCannotGrantTiming() {
        val contradiction = observe(14, 14, received, instant("2026-10-20T14:27:18.124Z"))
        assertEquals(LocalReturnCommercialWindowStatusV1.UNAVAILABLE, contradiction.status)
        val valid = observe(14, 14, received, instant("2026-11-03T14:27:18.124Z"))
        val json = Json { encodeDefaults = true }
        val fields = json.parseToJsonElement(json.encodeToString(LocalReturnOrdinaryTimingV1.serializer(), valid)).jsonObject
        val excessive = JsonObject(fields + ("originalCommercialDays" to json.parseToJsonElement("9223372036854775807")))
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(LocalReturnOrdinaryTimingV1.serializer(), excessive.toString())
        }
        // The exact F5 duration is outside this Int-valued representation; no truncation/retry is used.
    }

    @Test
    fun rq010_frozenMicrosecondAndNanosecondArithmeticIsLossless() {
        val vectors = listOf(
            Precision("2026-10-20T14:27:18.123456Z", "2026-11-03T14:27:18.123455Z",
                "2026-11-03T14:27:18.123456Z", "2026-11-03T14:27:18.123457Z"),
            Precision("2026-10-20T14:27:18.123456789Z", "2026-11-03T14:27:18.123456788Z",
                "2026-11-03T14:27:18.123456789Z", "2026-11-03T14:27:18.123456790Z"))
        for (vector in vectors) {
            for ((submitted, expected) in listOf(vector.before to LocalReturnCommercialWindowStatusV1.WITHIN,
                vector.exact to LocalReturnCommercialWindowStatusV1.WITHIN,
                vector.after to LocalReturnCommercialWindowStatusV1.OUTSIDE)) {
                val timing = observe(14, 14, instant(vector.received), instant(submitted))
                assertEquals(expected, timing.status)
                assertEquals(instant(vector.exact), timing.expiresAt)
                val json = Json { encodeDefaults = true }
                assertEquals(timing, json.decodeFromString(LocalReturnOrdinaryTimingV1.serializer(),
                    json.encodeToString(LocalReturnOrdinaryTimingV1.serializer(), timing)))
            }
        }
        // This does not claim native storage/RPC/clock support at either precision.
    }

    @Test
    fun rq015_rq028_rq032_literalInspectionTimeIsRetainedAndMustMatchUtcEvidence() {
        val utc = instant("2026-11-05T08:00:00.987Z")
        val literal = "2026-11-05T10:00:00.987+02:00"
        val inspection = LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
            LocalCashEvidenceV1(inspectionId, utc), literal)
        assertEquals(literal, inspection.reportedOccurredAt)
        assertEquals(utc, inspection.evidence.occurredAt)
        assertFailsWith<IllegalArgumentException> {
            LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
                LocalCashEvidenceV1(inspectionId, utc), "2026-11-05T10:00:00.987Z")
        }
        assertFailsWith<IllegalArgumentException> {
            LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
                LocalCashEvidenceV1(inspectionId, utc), " $literal")
        }
    }

    private fun observe(captured: Int, current: Int, receipt: UtcInstant, submitted: UtcInstant) =
        LocalReturnOrdinaryTimingV1.observe("ordinary_clothing_unchanged", captured, current, receiptId,
            receipt, "Africa/Cairo", recordOperation, submitted)
    private fun instant(literal: String) = UtcInstant(Instant.parse(literal))
    private data class Boundary(val days: Int, val seconds: Long, val before: String, val exact: String, val after: String)
    private data class Favorable(val captured: Int, val current: Int, val effective: Int, val submitted: String, val expiry: String)
    private data class Precision(val received: String, val before: String, val exact: String, val after: String)
}

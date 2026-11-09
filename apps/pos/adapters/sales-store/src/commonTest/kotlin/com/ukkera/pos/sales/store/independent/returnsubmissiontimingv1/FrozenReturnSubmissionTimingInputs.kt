package com.ukkera.pos.sales.store.independent.returnsubmissiontimingv1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unchanged arithmetic and numeric-vector projection from return-submission-timing-v1.
 * Manifest SHA-256: 4fe2e42ff0aed4369b441ac26fa3982c1574826200bdecf20a4b49f4419aed20.
 * These values are supplied temporal premises, not genuine receipt/submission,
 * period capture, historical producer, authority or legal-applicability evidence.
 * Fixture text is not a production wire/record encoding oracle.
 */
internal object FrozenReturnSubmissionTimingInputs {
    private const val NUMERIC_FIXTURES_JSON: String = """
    {
      "arithmetic": {
        "seconds_per_day": "86400",
        "nanoseconds_per_second": "1000000000",
        "nanoseconds_per_day": "86400000000000",
        "expiry_rule": "approved_start_instant + configured_days * nanoseconds_per_day",
        "inclusive_rule": "original_submission_instant <= exact_expiry_instant",
        "method": "Independent integer duration arithmetic; Gregorian UTC dates and explicit offsets; fractional nanoseconds retained without floating-point rounding."
      },
      "numeric_vector_count": 13,
      "numeric_vectors": [
        {
          "id": "EL-01",
          "case": "RTS-U01",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "14",
          "elapsed_seconds": "1209600",
          "elapsed_nanoseconds": "1209600000000000",
          "exact_expiry": "2026-11-02T10:15:30.123456789Z",
          "original_submission": "2026-11-02T10:15:30.123456788Z",
          "submission_minus_expiry_nanoseconds": "-1",
          "expected_timing": "within"
        },
        {
          "id": "EL-02",
          "case": "RTS-U01",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "14",
          "elapsed_seconds": "1209600",
          "elapsed_nanoseconds": "1209600000000000",
          "exact_expiry": "2026-11-02T10:15:30.123456789Z",
          "original_submission": "2026-11-02T10:15:30.123456789Z",
          "submission_minus_expiry_nanoseconds": "0",
          "expected_timing": "within"
        },
        {
          "id": "EL-03",
          "case": "RTS-U01",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "14",
          "elapsed_seconds": "1209600",
          "elapsed_nanoseconds": "1209600000000000",
          "exact_expiry": "2026-11-02T10:15:30.123456789Z",
          "original_submission": "2026-11-02T10:15:30.123456790Z",
          "submission_minus_expiry_nanoseconds": "1",
          "expected_timing": "outside"
        },
        {
          "id": "EL-04",
          "case": "RTS-U02",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "1",
          "elapsed_seconds": "86400",
          "elapsed_nanoseconds": "86400000000000",
          "exact_expiry": "2026-10-20T10:15:30.123456789Z",
          "original_submission": "2026-10-20T10:15:30.123456789Z",
          "submission_minus_expiry_nanoseconds": "0",
          "expected_timing": "within"
        },
        {
          "id": "EL-05",
          "case": "RTS-U02",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "7",
          "elapsed_seconds": "604800",
          "elapsed_nanoseconds": "604800000000000",
          "exact_expiry": "2026-10-26T10:15:30.123456789Z",
          "original_submission": "2026-10-26T10:15:30.123456789Z",
          "submission_minus_expiry_nanoseconds": "0",
          "expected_timing": "within"
        },
        {
          "id": "EL-06",
          "case": "RTS-U02",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "30",
          "elapsed_seconds": "2592000",
          "elapsed_nanoseconds": "2592000000000000",
          "exact_expiry": "2026-11-18T10:15:30.123456789Z",
          "original_submission": "2026-11-18T10:15:30.123456789Z",
          "submission_minus_expiry_nanoseconds": "0",
          "expected_timing": "within"
        },
        {
          "id": "EL-07",
          "case": "RTS-U03",
          "approved_start": "2026-03-07T17:00:00.000000007Z",
          "start_explicit_offset": "2026-03-07T12:00:00.000000007-05:00",
          "configured_days": "1",
          "elapsed_seconds": "86400",
          "elapsed_nanoseconds": "86400000000000",
          "exact_expiry": "2026-03-08T17:00:00.000000007Z",
          "expiry_explicit_offset": "2026-03-08T13:00:00.000000007-04:00",
          "original_submission": "2026-03-08T17:00:00.000000007Z",
          "submission_minus_expiry_nanoseconds": "0",
          "following_civil_day_same_clock_elapsed_seconds": "82800",
          "expected_timing": "within"
        },
        {
          "id": "EL-08",
          "case": "RTS-U03",
          "approved_start": "2026-10-31T16:00:00.999999997Z",
          "start_explicit_offset": "2026-10-31T12:00:00.999999997-04:00",
          "configured_days": "1",
          "elapsed_seconds": "86400",
          "elapsed_nanoseconds": "86400000000000",
          "exact_expiry": "2026-11-01T16:00:00.999999997Z",
          "expiry_explicit_offset": "2026-11-01T11:00:00.999999997-05:00",
          "original_submission": "2026-11-01T16:00:00.999999997Z",
          "submission_minus_expiry_nanoseconds": "0",
          "following_civil_day_same_clock_elapsed_seconds": "90000",
          "expected_timing": "within"
        },
        {
          "id": "EL-09",
          "case": "RTS-U04",
          "approved_start": "2026-10-19T23:59:59.999999999Z",
          "configured_days": "1",
          "elapsed_seconds": "86400",
          "elapsed_nanoseconds": "86400000000000",
          "exact_expiry": "2026-10-20T23:59:59.999999999Z",
          "original_submission": "2026-10-20T18:00:00Z",
          "submission_minus_expiry_nanoseconds": "-21599999999999",
          "store_closing_utc": "17:00:00",
          "business_day_cutoff_utc": "04:00:00",
          "expected_timing": "within"
        },
        {
          "id": "EL-10",
          "case": "RTS-U05",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "14",
          "elapsed_seconds": "1209600",
          "elapsed_nanoseconds": "1209600000000000",
          "exact_expiry": "2026-11-02T10:15:30.123456789Z",
          "original_submission": "2026-11-02T10:15:30.123456788Z",
          "customer_claimed_time": "2026-11-04T09:00:00Z",
          "later_qualification_or_processing": "2026-11-05T12:00:00Z",
          "submission_minus_expiry_nanoseconds": "-1",
          "expected_timing": "within"
        },
        {
          "id": "EL-11",
          "case": "RTS-U05",
          "approved_start": "2026-10-19T10:15:30.123456789Z",
          "configured_days": "14",
          "elapsed_seconds": "1209600",
          "elapsed_nanoseconds": "1209600000000000",
          "exact_expiry": "2026-11-02T10:15:30.123456789Z",
          "original_submission": "2026-11-02T10:15:30.123456790Z",
          "customer_claimed_time": "2026-11-01T09:00:00Z",
          "later_qualification_or_processing": "2026-11-05T12:00:00Z",
          "submission_minus_expiry_nanoseconds": "1",
          "expected_timing": "outside"
        },
        {
          "id": "OV-01",
          "case": "RTS-U06",
          "start_epoch_nanoseconds": "0",
          "configured_days": "106752",
          "elapsed_seconds": "9223372800",
          "elapsed_nanoseconds": "9223372800000000000",
          "exact_expiry_epoch_nanoseconds": "9223372800000000000",
          "signed_64_nanosecond_reference_max": "9223372036854775807",
          "excess_over_reference_max_nanoseconds": "763145224193",
          "expected": "Exact mathematical value if supported; otherwise no asserted deadline, timing grant or business effect. Product range/encoding remains unbound."
        },
        {
          "id": "OV-02",
          "case": "RTS-U06",
          "start_epoch_nanoseconds": "9223372036854775800",
          "configured_days": "1",
          "elapsed_seconds": "86400",
          "elapsed_nanoseconds": "86400000000000",
          "exact_expiry_epoch_nanoseconds": "9223458436854775800",
          "signed_64_nanosecond_reference_max": "9223372036854775807",
          "excess_over_reference_max_nanoseconds": "86399999999993",
          "expected": "Exact mathematical value if supported; otherwise no asserted deadline, timing grant or business effect. Product range/encoding remains unbound."
        }
      ]
    }
    """

    private val root: JsonObject = Json.parseToJsonElement(NUMERIC_FIXTURES_JSON).jsonObject
    val arithmetic: JsonObject = root.getValue("arithmetic").jsonObject
    val vectors: List<JsonObject> = root.getValue("numeric_vectors").jsonArray.map { it.jsonObject }

    fun forCase(caseId: String): List<JsonObject> = vectors.filter {
        it.getValue("case").jsonPrimitive.content == caseId
    }
}

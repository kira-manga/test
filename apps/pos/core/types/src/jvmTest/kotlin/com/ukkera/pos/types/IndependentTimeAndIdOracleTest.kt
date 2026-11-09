package com.ukkera.pos.types

import java.time.Instant as JdkInstant
import java.time.LocalDate as JdkDate
import java.time.LocalTime as JdkTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.datetime.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JDK algorithms independently enumerate cutoff boundaries. On JVM, datetime and this oracle
 * use the same JDK tzdb; this checks our mapping algorithm, NOT independent/native tzdb accuracy.
 */
class IndependentTimeAndIdOracleTest {
    @Test
    fun businessDateMatchesIndependentJdkBoundaryEnumerationAcrossIanaTransitions() {
        val examples = listOf(
            "Africa/Cairo" to "2026-04-23T22:00:00Z",
            "Africa/Cairo" to "2026-10-29T21:00:00Z",
            "America/New_York" to "2026-03-08T07:00:00Z",
            "America/New_York" to "2026-11-01T06:00:00Z",
            "Europe/Berlin" to "2026-10-25T01:00:00Z",
            "Australia/Lord_Howe" to "2026-04-04T15:00:00Z",
            "Pacific/Apia" to "2011-12-30T10:00:00Z",
            "Asia/Kathmandu" to "2026-01-01T00:00:00Z",
        )
        val cutoffs = listOf(0 to 30, 1 to 30, 2 to 30, 4 to 0, 23 to 30)
        for ((zoneName, transition) in examples) {
            val zone = ZoneId.of(zoneName)
            val center = JdkInstant.parse(transition)
            for ((hour, minute) in cutoffs) {
                val localCutoff = JdkTime.of(hour, minute)
                val calendar = BusinessCalendar(IanaTimeZone(zoneName), LocalTime(hour, minute), CutoffResolution.SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP)
                for (offset in -144..144) {
                    val instant = center.plusSeconds(offset * 1800L)
                    val localDate = instant.atZone(zone).toLocalDate()
                    // Deliberately enumerate all surrounding boundaries instead of sharing the production search.
                    val expected = (-3L..3L).map { localDate.plusDays(it) }
                        .filter { !it.atTime(localCutoff).atZone(zone).toInstant().isAfter(instant) }
                        .maxOrNull()!!
                    val actual = calendar.businessDateAt(UtcInstant.parseCanonical(instant.toString()))
                    assertEquals(expected.toString(), actual.canonicalString(), "$zoneName $localCutoff $instant")
                }
            }
        }
    }

    @Test
    fun startOfBusinessDatesAndUtcFormattingMatchJdkOracles() {
        val random = Random(0x54494D45)
        repeat(2_000) {
            val epochSecond = random.nextLong(-2_208_988_800L, 4_102_444_800L) // 1900..2100, including leap years.
            val nanos = random.nextInt(0, 1_000_000_000)
            val expected = JdkInstant.ofEpochSecond(epochSecond, nanos.toLong()).toString()
            val actual = UtcInstant(kotlin.time.Instant.fromEpochSeconds(epochSecond, nanos.toLong()))
            assertEquals(expected, actual.canonicalString())
            assertEquals(actual, UtcInstant.parseCanonical(expected))
        }
        for (zoneName in listOf("Africa/Cairo", "America/New_York", "Australia/Lord_Howe", "Pacific/Apia")) {
            val zone = ZoneId.of(zoneName)
            val calendar = BusinessCalendar(IanaTimeZone(zoneName), LocalTime(0, 30), CutoffResolution.SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP)
            val base = JdkDate.of(2026, 1, 1)
            repeat(365) { day ->
                val date = base.plusDays(day.toLong())
                val expected = date.atTime(0, 30).atZone(zone).toInstant().toString()
                assertEquals(expected, calendar.startOf(BusinessDate.parseCanonical(date.toString())).canonicalString())
            }
        }
    }

    @Test
    fun generatedIdsParseAsJdkUuidVersion4Variant2() {
        repeat(1_000) {
            for (text in listOf(DomainId.random().value, OperationId.random().value)) {
                val uuid = UUID.fromString(text)
                assertEquals(4, uuid.version())
                assertEquals(2, uuid.variant())
                assertEquals(text, uuid.toString())
            }
        }
    }
}

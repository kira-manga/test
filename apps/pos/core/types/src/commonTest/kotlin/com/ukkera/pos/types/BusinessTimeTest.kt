package com.ukkera.pos.types

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BusinessTimeTest {
    private val resolution = CutoffResolution.SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP

    @Test
    fun utcInstantAndBusinessDateWireAreCanonicalAndDistinct() {
        for (text in listOf("2026-10-23T12:34:56Z", "2026-10-23T12:34:56.100Z", "2026-10-23T12:34:56.123456789Z", "1970-01-01T00:00:00Z")) {
            val instant = UtcInstant.parseCanonical(text)
            assertEquals("\"$text\"", Json.encodeToString(instant))
            assertEquals(instant, Json.decodeFromString<UtcInstant>(Json.encodeToString(instant)))
        }
        val date = BusinessDate(LocalDate(2024, 2, 29))
        assertEquals("\"2024-02-29\"", Json.encodeToString(date))
        assertEquals(date, Json.decodeFromString<BusinessDate>(Json.encodeToString(date)))
        for (text in listOf("2026-10-23T12:34:56+00:00", "2026-10-23T14:34:56+02:00", "2026-10-23T12:34:56.000Z", "2026-10-23", "2026-10-23T12:34:56")) {
            assertFailsWith<SerializationException>(text) { Json.decodeFromString<UtcInstant>("\"$text\"") }
        }
        assertFailsWith<SerializationException> { Json.decodeFromString<UtcInstant>("1792758896000") }
        assertFailsWith<SerializationException> { Json.decodeFromString<BusinessDate>("\"2023-02-29\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString<BusinessDate>("\"2024-2-29\"") }
    }

    @Test
    fun cairoCalendarNeedsAnExplicitCutoffAndIanaZone() {
        val calendar = BusinessCalendar(IanaTimeZone.AFRICA_CAIRO, LocalTime(4, 0), resolution)
        assertEquals(date("2025-12-31"), calendar.businessDateAt(instant("2026-01-01T01:59:59.999999999Z")))
        assertEquals(date("2026-01-01"), calendar.businessDateAt(instant("2026-01-01T02:00:00Z")))
        assertEquals(instant("2026-01-01T02:00:00Z"), calendar.startOf(date("2026-01-01")))
        assertEquals(calendar, Json.decodeFromString<BusinessCalendar>(Json.encodeToString(calendar)))
        assertFailsWith<IllegalArgumentException> { IanaTimeZone("+02:00") }
        assertFailsWith<IllegalArgumentException> { IanaTimeZone("Africa/NotCairo") }
        assertFailsWith<SerializationException> { Json.decodeFromString<IanaTimeZone>("2") }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<BusinessCalendar>("""{"zone":"Africa/Cairo","cutoff":"04:00"}""")
        }
        for (wireCutoff in listOf("400", "\"04:00:00\"", "\"24:00\"")) {
            assertFailsWith<SerializationException> {
                Json.decodeFromString<BusinessCalendar>("""{"zone":"Africa/Cairo","cutoff":$wireCutoff,"cutoffResolution":"SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP"}""")
            }
        }
    }

    @Test
    fun cairoSpringGapShiftsTheExplicitCutoffForward() {
        // 2026-04-24 00:00 -> 01:00; 00:30 resolves to 01:30 (not 01:00).
        val calendar = BusinessCalendar(IanaTimeZone.AFRICA_CAIRO, LocalTime(0, 30), resolution)
        assertEquals(instant("2026-04-23T22:30:00Z"), calendar.startOf(date("2026-04-24")))
        assertEquals(date("2026-04-23"), calendar.businessDateAt(instant("2026-04-23T22:29:59.999999999Z")))
        assertEquals(date("2026-04-24"), calendar.businessDateAt(instant("2026-04-23T22:30:00Z")))
    }

    @Test
    fun cairoAutumnOverlapDoesNotRollBusinessDateBackward() {
        // 2026-10-30 00:00 daylight time rolls back to 2026-10-29 23:00 standard time.
        val calendar = BusinessCalendar(IanaTimeZone.AFRICA_CAIRO, LocalTime(23, 30), resolution)
        assertEquals(instant("2026-10-29T20:30:00Z"), calendar.startOf(date("2026-10-29")))
        assertEquals(date("2026-10-28"), calendar.businessDateAt(instant("2026-10-29T20:29:59Z")))
        assertEquals(date("2026-10-29"), calendar.businessDateAt(instant("2026-10-29T20:30:00Z")))
        // Local 23:15 is now the SECOND occurrence; comparing local time alone would be wrong.
        assertEquals(date("2026-10-29"), calendar.businessDateAt(instant("2026-10-29T21:15:00Z")))
        assertEquals(date("2026-10-29"), calendar.businessDateAt(instant("2026-10-29T21:30:00Z")))
    }

    @Test
    fun businessDaysCanContain23Or25HoursAndAreNot24HourDurations() {
        val calendar = BusinessCalendar(IanaTimeZone.AFRICA_CAIRO, LocalTime(0, 0), resolution)
        val spring = calendar.startOf(date("2026-04-25")).value - calendar.startOf(date("2026-04-24")).value
        val autumn = calendar.startOf(date("2026-10-30")).value - calendar.startOf(date("2026-10-29")).value
        assertEquals(23L, spring.inWholeHours)
        assertEquals(25L, autumn.inWholeHours)
    }

    @Test
    fun anotherIanaZoneUsesItsOwnGapAndOverlapRules() {
        val newYork = IanaTimeZone("America/New_York")
        val spring = BusinessCalendar(newYork, LocalTime(2, 30), resolution)
        assertEquals(instant("2026-03-08T07:30:00Z"), spring.startOf(date("2026-03-08")))
        val autumn = BusinessCalendar(newYork, LocalTime(1, 30), resolution)
        assertEquals(instant("2026-11-01T05:30:00Z"), autumn.startOf(date("2026-11-01")))
        assertEquals(date("2026-11-01"), autumn.businessDateAt(instant("2026-11-01T06:15:00Z")))
    }

    @Test
    fun skippedCivilDateDoesNotInventAnAlreadyStartedBusinessDay() {
        val calendar = BusinessCalendar(IanaTimeZone("Pacific/Apia"), LocalTime(3, 0), resolution)
        // Apia skipped 2011-12-30; both these cutoffs resolve to the same next civil-day instant.
        assertEquals(calendar.startOf(date("2011-12-30")), calendar.startOf(date("2011-12-31")))
        assertEquals(date("2011-12-29"), calendar.businessDateAt(instant("2011-12-30T12:59:59Z")))
        assertEquals(date("2011-12-31"), calendar.businessDateAt(instant("2011-12-30T13:00:00Z")))
    }

    @Test
    fun rollbackOfSuppliedWallTimeDoesNotDetermineJournalOrder() {
        val first = instant("2026-10-23T12:00:00Z")
        val afterClockRollback = instant("2026-10-23T11:59:00Z")
        val firstSequence = JournalSequence(41)
        val nextSequence = firstSequence.next()
        assertTrue(afterClockRollback < first)
        assertTrue(nextSequence > firstSequence)
        // No utility infers master sequence, observation time or legal document date from this clock.
    }

    private fun instant(text: String): UtcInstant = UtcInstant.parseCanonical(text)
    private fun date(text: String): BusinessDate = BusinessDate.parseCanonical(text)
}

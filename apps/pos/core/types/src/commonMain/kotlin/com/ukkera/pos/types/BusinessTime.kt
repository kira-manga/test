package com.ukkera.pos.types

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** UTC instant, never a local date/time and never an ordering/identity source. */
@Serializable(with = UtcInstantSerializer::class)
public data class UtcInstant(public val value: Instant) : Comparable<UtcInstant> {
    public fun canonicalString(): String = value.toString()

    override fun compareTo(other: UtcInstant): Int = value.compareTo(other.value)

    override fun toString(): String = canonicalString()

    public companion object {
        public fun parseCanonical(text: String): UtcInstant {
            require(text.length <= 64) { "Instant exceeds its wire bound" }
            val instant = Instant.parse(text)
            require(instant.toString() == text) { "Instant must be canonical UTC with a Z suffix" }
            return UtcInstant(instant)
        }
    }
}

/** A configured IANA identifier, not the device default zone or a guessed fixed UTC offset. */
@Serializable(with = IanaTimeZoneSerializer::class)
@JvmInline
public value class IanaTimeZone(public val value: String) {
    init {
        require(value.length <= 128 && value in TimeZone.availableZoneIds) { "Unknown or non-IANA timezone" }
    }

    public fun resolve(): TimeZone = TimeZone.of(value)

    public companion object {
        public val AFRICA_CAIRO: IanaTimeZone = IanaTimeZone("Africa/Cairo")
    }
}

/** Operational date only. A fiscal document date must be separately named and validated. */
@Serializable(with = BusinessDateSerializer::class)
public data class BusinessDate(public val value: LocalDate) : Comparable<BusinessDate> {
    public fun canonicalString(): String = value.toString()

    override fun compareTo(other: BusinessDate): Int = value.compareTo(other.value)

    override fun toString(): String = canonicalString()

    public companion object {
        public fun parseCanonical(text: String): BusinessDate {
            require(text.length <= 32) { "Business date exceeds its wire bound" }
            val date = LocalDate.parse(text)
            require(date.toString() == text) { "Business date must be canonical ISO local date" }
            return BusinessDate(date)
        }
    }
}

/**
 * Explicitly selected resolution, NOT an implicit branch business policy. A cutoff in a gap
 * moves forward by the gap duration; a repeated cutoff uses its earlier occurrence.
 * These are kotlinx.datetime LocalDateTime.toInstant's documented semantics.
 */
@Serializable
public enum class CutoffResolution {
    SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP,
}

/**
 * Pure mapping from a supplied UTC instant to an operational date. No clock reads, fiscal-date
 * inference or default cutoff. Persist this configuration/version with the relevant event.
 * Master commit and optional client observation instants remain distinct fields in that event.
 */
@Serializable
public data class BusinessCalendar(
    public val zone: IanaTimeZone,
    @Serializable(with = LocalCutoffSerializer::class)
    public val cutoff: LocalTime,
    public val cutoffResolution: CutoffResolution,
) {
    public fun startOf(date: BusinessDate): UtcInstant = UtcInstant(resolveCutoff(date.value, zone.resolve()))

    /**
     * Compare real cutoff instants, not just local clock labels: the business date cannot
     * roll backward when a DST overlap repeats a wall-clock time before the cutoff.
     * Calendar-date stepping (not subtraction of 24h) also handles skipped civil dates.
     */
    public fun businessDateAt(instant: UtcInstant): BusinessDate {
        val timeZone = zone.resolve()
        var candidate = instant.value.toLocalDateTime(timeZone).date
        // IANA date-line/DST transitions fit this bounded search; fail rather than guess otherwise.
        repeat(4) {
            val nextDate = candidate.plus(1, DateTimeUnit.DAY)
            val start = resolveCutoff(candidate, timeZone)
            val nextStart = resolveCutoff(nextDate, timeZone)
            when {
                instant.value < start -> candidate = candidate.minus(1, DateTimeUnit.DAY)
                instant.value >= nextStart -> candidate = nextDate
                else -> return BusinessDate(candidate)
            }
        }
        throw IllegalStateException("Unsupported timezone cutoff discontinuity")
    }

    private fun resolveCutoff(date: LocalDate, timeZone: TimeZone): Instant = when (cutoffResolution) {
        CutoffResolution.SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP -> LocalDateTime(date, cutoff).toInstant(timeZone)
    }
}

public object UtcInstantSerializer : KSerializer<UtcInstant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UtcInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UtcInstant): Unit = encoder.encodeString(value.canonicalString())

    override fun deserialize(decoder: Decoder): UtcInstant =
        decodeScalar(decoder, "UtcInstant", 64) { UtcInstant.parseCanonical(it) }
}

public object IanaTimeZoneSerializer : KSerializer<IanaTimeZone> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IanaTimeZone", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IanaTimeZone): Unit = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): IanaTimeZone = decodeScalar(decoder, "IanaTimeZone", 128, ::IanaTimeZone)
}

public object BusinessDateSerializer : KSerializer<BusinessDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BusinessDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BusinessDate): Unit = encoder.encodeString(value.canonicalString())

    override fun deserialize(decoder: Decoder): BusinessDate =
        decodeScalar(decoder, "BusinessDate", 32) { BusinessDate.parseCanonical(it) }
}

public object LocalCutoffSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalCutoff", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime): Unit = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalTime = decodeScalar(decoder, "LocalCutoff", 18) { text ->
        val time = LocalTime.parse(text)
        require(time.toString() == text) { "Cutoff must be canonical ISO local time" }
        time
    }
}

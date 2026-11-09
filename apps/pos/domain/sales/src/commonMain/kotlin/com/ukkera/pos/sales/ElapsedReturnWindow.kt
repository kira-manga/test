package com.ukkera.pos.sales

import com.ukkera.pos.types.UtcInstant
import kotlin.time.Instant

/**
 * Configured elapsed 24-hour window, not a civil/operational-day calculation or a
 * legal eligibility decision. The caller must establish the applicable policy,
 * approved starting instant and authoritative original submission evidence.
 * Customer-asserted dates and the later processing clock supply no authority.
 */
public data class ElapsedReturnWindow(public val days: Int) {
    init { require(days > 0) { "return.window.invalid_days" } }

    /** Null is an unrepresentable deadline, never an infinite accepting window. */
    public fun expiresAt(startedAt: UtcInstant): UtcInstant? {
        val seconds = days.toLong() * 86_400L
        val start = startedAt.value
        if (start.epochSeconds > Long.MAX_VALUE - seconds) return null
        val target = start.epochSeconds + seconds
        val expiry = try {
            Instant.fromEpochSeconds(target, start.nanosecondsOfSecond)
        } catch (_: IllegalArgumentException) { return null }
        // Instant factories may clamp at their representable bounds. Saturation
        // or lost fractional seconds must not fabricate a supported deadline.
        if (expiry.epochSeconds != target || expiry.nanosecondsOfSecond != start.nanosecondsOfSecond) return null
        return UtcInstant(expiry)
    }

    /**
     * Exact inclusive expiry. A later qualification/processing instant is
     * deliberately not an input. Null means chronology/arithmetic is unsupported,
     * not timely, late or proof of missing delivery/submission evidence.
     */
    public fun containsSubmission(startedAt: UtcInstant, submittedAt: UtcInstant): Boolean? {
        if (submittedAt < startedAt) return null
        val expiry = expiresAt(startedAt) ?: return null
        return submittedAt <= expiry
    }
}

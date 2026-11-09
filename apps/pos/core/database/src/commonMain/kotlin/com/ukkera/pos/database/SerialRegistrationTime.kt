package com.ukkera.pos.database

import com.ukkera.pos.types.UtcInstant

/**
 * A serial-qualification instant from the kernel-owned time source, not recordedAt or a
 * request timestamp. Construction is confined to native/core qualification and original
 * readers. No current production source implements the required source qualification and
 * supported precision. An interval comparison grants no action authorization and is not
 * the kernel's final commit decision or its original historical evidence.
 */
public class QualifiedSerialRegistrationTime internal constructor(public val instant: UtcInstant) {
    public fun contains(validFrom: UtcInstant, validUntil: UtcInstant?): Boolean =
        validFrom <= instant && (validUntil == null || instant < validUntil)

    override fun toString(): String = "QualifiedSerialRegistrationTime([REDACTED])"
}

/**
 * Separate qualification contract on the SAME clock instance installed in the kernel.
 * Ordinary MasterClock implementations supply audit timestamps only. A native adapter must
 * implement the actual source, qualification and supported precision before returning a
 * value. Commit-time validation and retaining the original decision remain kernel obligations.
 */
internal interface SerialRegistrationMasterClock : MasterClock {
    fun qualifiedSerialRegistrationTime(): QualifiedSerialRegistrationTime?
}

package com.ukkera.pos.security

/**
 * Immutable original association DATA and its separately retained exact SDK acquisition name.
 * Neither this readback nor its signed identity grants custody, admission or a live connection.
 */
public class LocalDevicePairingSdkOriginal internal constructor(
    public val identity: LocalDevicePairingSdkIdentityV2,
    public val exactDeviceName: String,
) {
    override fun toString(): String = "LocalDevicePairingSdkOriginal([REDACTED])"
}

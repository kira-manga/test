package com.ukkera.pos.security

/** Shared arithmetic for native installer/head attempts, not employee-login policy or
 * evidence of an actual credential match. The native owner still owns reservation,
 * current authorization, persistence, verification and its original deadline. */
internal fun localNativeCredentialNextFailureCount(previous: Int): Int {
    if (previous !in 0..SelfOwnedInstallationProfileV1.MAXIMUM_FAILED_ATTEMPTS)
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    return minOf(previous + 1, SelfOwnedInstallationProfileV1.MAXIMUM_FAILED_ATTEMPTS)
}

internal fun localNativeCredentialFailureDelayMillis(reservedFailures: Int): Long {
    if (reservedFailures !in 1..SelfOwnedInstallationProfileV1.MAXIMUM_FAILED_ATTEMPTS)
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    return if (reservedFailures >= 10) SelfOwnedInstallationProfileV1.MAXIMUM_COOLDOWN_MILLIS
    else minOf(1_000L shl (reservedFailures - 1), SelfOwnedInstallationProfileV1.MAXIMUM_COOLDOWN_MILLIS)
}

internal fun localNativeCredentialDeadlineMillis(now: Long, duration: Long): Long {
    if (now < 0 || duration < 0 || now > Long.MAX_VALUE - duration)
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    return now + duration
}

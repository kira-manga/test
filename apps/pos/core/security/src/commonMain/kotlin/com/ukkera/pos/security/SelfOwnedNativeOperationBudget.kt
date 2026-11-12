package com.ukkera.pos.security

/** Diagnostic phases only. None of these values establishes native ownership or authority. */
internal enum class SelfOwnedNativeOperationPhase(val diagnosticName: String) {
    EXECUTE_AND_DELIVERY("execute-and-delivery"),
    ROOM_SETTLEMENT_AND_DELIVERY("room-jobs-through-native-settlement-and-delivery"),
    OWNER_CLOSE("actual-native-owner-close"),
    UNDELIVERED_DISCARD("undelivered-discard"),
}

/**
 * Shared immutable timing policy for one already-admitted native invocation.
 *
 * The native owner supplies its genuinely observed monotonic elapsed time and retains the
 * atomic holder, serialization, resources, watchdog, process lease and exclusion mechanism.
 * This value is not a clock, native observation, custody proof or permission to begin work.
 * The owner must still recheck currentness and the exact retained invocation at every boundary.
 *
 * Disposal belongs to the original invocation. Changing its diagnostic phase cannot sample
 * a new clock, admit a replacement token or renew the original 30,000-ms deadline. Final owner
 * close is separately admitted only after the prior native invocation actually finishes.
 */
internal class SelfOwnedNativeOperationBudget private constructor(
    private val invocation: Any,
    val phase: SelfOwnedNativeOperationPhase,
    val deadlineMillis: Long,
) {
    fun isOwnedBy(token: Any): Boolean = invocation === token

    fun isExpiredAt(nativeElapsedMillis: Long): Boolean = nativeElapsedMillis >= deadlineMillis

    /** The native owner serializes this transition with its watchdog and retained holder. */
    fun forUndeliveredDiscard(token: Any): SelfOwnedNativeOperationBudget? =
        if (isOwnedBy(token)) {
            SelfOwnedNativeOperationBudget(invocation, SelfOwnedNativeOperationPhase.UNDELIVERED_DISCARD, deadlineMillis)
        } else {
            null
        }

    override fun toString(): String = "SelfOwnedNativeOperationBudget([REDACTED])"

    companion object {
        /** Call only at native admission, never to give unfinished work another allowance. */
        fun admit(
            token: Any,
            phase: SelfOwnedNativeOperationPhase,
            nativeElapsedMillis: Long,
        ): SelfOwnedNativeOperationBudget? {
            if (phase == SelfOwnedNativeOperationPhase.UNDELIVERED_DISCARD || nativeElapsedMillis < 0 ||
                nativeElapsedMillis > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.NATIVE_OPERATION_MILLIS) {
                return null
            }
            return SelfOwnedNativeOperationBudget(
                token, phase, nativeElapsedMillis + SelfOwnedInstallationProfileV1.NATIVE_OPERATION_MILLIS,
            )
        }
    }
}

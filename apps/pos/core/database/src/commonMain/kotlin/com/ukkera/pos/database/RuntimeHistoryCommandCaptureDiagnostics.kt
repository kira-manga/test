package com.ukkera.pos.database

/** Fixed source branches only. This observation cannot authorize a history or original. */
internal enum class RuntimeHistoryCommandCaptureBranch {
    NO_ACTIVE_JOIN, EARLIER_NATIVE, EARLIER_ORDINARY, NATIVE_ORIGINAL,
    UNKNOWN_EVIDENCE, REJECTION_PROFILE, FOUNDING_ORDINARY, EMPLOYEE_ORDINARY,
}

internal expect fun runtimeHistoryCaptureDiagnosticsEnabled(): Boolean

/** The caller permits only an installed producer's static key, never arbitrary stored text.
 * No actor/device/session ID, requirement value, payload or credential is recorded. */
internal expect fun reportRuntimeHistoryCommandCaptureRefusal(key: CommandKey?, purpose: RuntimeWritePurposeV2,
    branch: RuntimeHistoryCommandCaptureBranch, founding: Boolean, cohost: Boolean?,
    ordinaryEmployeeProof: Boolean, supervisorReviewProof: Boolean)

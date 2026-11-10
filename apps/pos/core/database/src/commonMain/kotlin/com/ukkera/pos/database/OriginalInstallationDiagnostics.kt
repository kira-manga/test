package com.ukkera.pos.database

/** Observational only. Never publishes messages, arguments, records, paths or key material. */
internal expect fun reportOriginalInstallationBodyFailure(failure: Throwable)

/** Original static failure before mandatory unknown-frame exclusion; no failure details escape. */
internal expect fun reportRuntimeHistoryLoanFailure(failure: Throwable)

/** Fixed source phases only; no submitted operation or stored value is included. */
internal enum class RuntimeHistoryDiagnosticStage {
    STREAMED_SPOOL_CREATE_ENTER, STREAMED_SPOOL_CREATED, STREAMED_PROPOSAL_RETURNED,
    STREAMED_SEAL_RETURNED, STREAMED_INVALIDATION_RETURNED, STREAMED_HISTORY_CAPTURED,
    STREAMED_PERSIST_RETURNED,
}
internal expect fun reportRuntimeHistoryStage(stage: RuntimeHistoryDiagnosticStage)

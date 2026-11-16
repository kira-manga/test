package com.ukkera.pos.database

internal actual fun runtimeHistoryCaptureDiagnosticsEnabled(): Boolean = false

internal actual fun reportRuntimeHistoryCommandCaptureRefusal(key: CommandKey?, purpose: RuntimeWritePurposeV2,
    branch: RuntimeHistoryCommandCaptureBranch, founding: Boolean, cohost: Boolean?,
    ordinaryEmployeeProof: Boolean, supervisorReviewProof: Boolean) = Unit

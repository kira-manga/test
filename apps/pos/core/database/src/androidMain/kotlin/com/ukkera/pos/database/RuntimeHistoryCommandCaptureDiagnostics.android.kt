package com.ukkera.pos.database

internal actual fun runtimeHistoryCaptureDiagnosticsEnabled(): Boolean = try {
    java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")
} catch (_: Throwable) { false }

internal actual fun reportRuntimeHistoryCommandCaptureRefusal(key: CommandKey?, purpose: RuntimeWritePurposeV2,
    branch: RuntimeHistoryCommandCaptureBranch, founding: Boolean, cohost: Boolean?,
    ordinaryEmployeeProof: Boolean, supervisorReviewProof: Boolean) {
    try {
        if (!runtimeHistoryCaptureDiagnosticsEnabled()) return
        android.util.Log.w("UkkeraNativeOwner", "Native command capture refused: installedKey=" +
            (key?.let { "${it.name}/${it.version}" } ?: "UNSUPPORTED") +
            "; purpose=${purpose.name}; branch=${branch.name}; founding=$founding; cohost=$cohost;" +
            " ordinaryEmployeeProof=$ordinaryEmployeeProof; supervisorReviewProof=$supervisorReviewProof")
    } catch (_: Throwable) { /* Observation never changes the exact original refusal. */ }
}

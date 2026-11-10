package com.ukkera.pos.database

/** A single bounded source-location witness before the ordinary protected refusal is returned. */
internal actual fun reportOriginalInstallationBodyFailure(failure: Throwable) =
    reportStaticFailure("Original installation body refused", failure)

internal actual fun reportRuntimeHistoryLoanFailure(failure: Throwable) =
    reportStaticFailure("Runtime history loan failed before exclusion", failure)

internal actual fun reportPhysicalRecordingFailure(failure: Throwable) =
    reportStaticFailure("Physical recording failed before protected refusal", failure)

private fun reportStaticFailure(phase: String, failure: Throwable) {
    try {
        val locations = failure.stackTrace.filter {
            it.className.startsWith("com.ukkera.pos.")
        }.take(8).joinToString(" <- ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        val nativeReason = (failure as? com.ukkera.pos.security.NativeHistoryUnavailable)?.reason?.name ?: "none"
        android.util.Log.w("UkkeraNativeOwner",
            "$phase: ${failure.javaClass.simpleName}; nativeReason=$nativeReason; $locations")
    } catch (_: Throwable) { /* No diagnostic failure may change the protected outcome. */ }
}

internal actual fun reportRuntimeHistoryStage(stage: RuntimeHistoryDiagnosticStage) {
    try { android.util.Log.i("UkkeraNativeOwner", "Runtime history phase: ${stage.name}") }
    catch (_: Throwable) { /* Observation cannot change the outcome or skip mandatory denial. */ }
}

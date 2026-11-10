package com.ukkera.pos.database

/** A single bounded source-location witness before the ordinary protected refusal is returned. */
internal actual fun reportOriginalInstallationBodyFailure(failure: Throwable) {
    try {
        val locations = failure.stackTrace.filter {
            it.className.startsWith("com.ukkera.pos.")
        }.take(8).joinToString(" <- ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        val nativeReason = (failure as? com.ukkera.pos.security.NativeHistoryUnavailable)?.reason?.name ?: "none"
        android.util.Log.w("UkkeraNativeOwner",
            "Original installation body refused: ${failure.javaClass.simpleName}; nativeReason=$nativeReason; $locations")
    } catch (_: Throwable) { /* No diagnostic failure may change the protected outcome. */ }
}

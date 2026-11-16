package com.ukkera.pos.database

internal actual fun reportLocalMasterRpcAuthenticationTiming(number: Long, phase: LocalMasterRpcAuthenticationTracePhase,
    stage: LocalMasterRpcAuthenticationStage, totalMicros: Long, final: Boolean) {
    try {
        if (!localMasterRpcReadTraceEnabled()) return
        android.util.Log.i("UkkeraNativeOwner", "LAN RPC authentication timing: trace=$number; phase=${phase.name}; stage=${stage.name}; totalUs=$totalMicros; final=$final")
    } catch (_: Throwable) { /* Observation cannot change the actual outcome. */ }
}

internal actual fun localMasterRpcReadTraceEnabled(): Boolean =
    java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")

internal actual fun reportLocalMasterRpcReadTiming(number: Long, phase: LocalMasterRpcReadTracePhase,
    stage: LocalMasterRpcReadStage, elapsedMicros: Long, totalMicros: Long, final: Boolean) {
    try {
        if (!localMasterRpcReadTraceEnabled()) return
        android.util.Log.i("UkkeraNativeOwner", "LAN RPC read timing: trace=$number; phase=${phase.name}; stage=${stage.name}; elapsedUs=$elapsedMicros; totalUs=$totalMicros; final=$final")
    } catch (_: Throwable) { /* Observation cannot change the actual outcome. */ }
}

/** A single bounded source-location witness before the ordinary protected refusal is returned. */
internal actual fun reportOriginalInstallationBodyFailure(failure: Throwable) =
    reportStaticFailure("Original installation body refused", failure)

internal actual fun reportRuntimeHistoryLoanFailure(failure: Throwable) =
    reportStaticFailure("Runtime history loan failed before exclusion", failure)

internal actual fun reportPhysicalRecordingFailure(failure: Throwable) =
    reportStaticFailure("Physical recording failed before protected refusal", failure)

public actual fun reportLocalMasterRpcReadFailure(stage: LocalMasterRpcReadStage, failure: Throwable?) {
    try {
        if (!java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")) return
        if (failure != null) reportStaticFailure("LAN RPC read refused: stage=${stage.name}", failure)
        else android.util.Log.i("UkkeraNativeOwner", "LAN RPC read refused: stage=${stage.name}")
    } catch (_: Throwable) { /* Observation cannot change admission or refusal. */ }
}

internal actual fun reportLocalAccessVerificationFailure(stage: LocalAccessVerificationStage, failure: Throwable?) {
    try {
        if (!java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")) return
        if (failure != null) reportStaticFailure("Local access verification: stage=${stage.name}", failure)
        else android.util.Log.i("UkkeraNativeOwner", "Local access verification refused: stage=${stage.name}")
    } catch (_: Throwable) { /* Observation cannot change the verification result. */ }
}

public actual fun reportLocalMasterRpcAuthenticationStage(stage: LocalMasterRpcAuthenticationStage) {
    try { android.util.Log.i("UkkeraNativeOwner", "LAN RPC authentication: stage=${stage.name}") }
    catch (_: Throwable) { /* Observation never changes admission or refusal. */ }
}

private fun reportStaticFailure(phase: String, failure: Throwable) {
    try {
        val locations = failure.stackTrace.filter {
            it.className.startsWith("com.ukkera.pos.")
        }.take(8).joinToString(" <- ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        val nativeReason = (failure as? com.ukkera.pos.security.NativeHistoryUnavailable)?.reason?.name ?: "none"
        val rpc = (failure as? dev.p2pkit.rpc.RpcFailure)?.let {
            "; rpcKind=${it.kind.name}; rpcPhase=${it.phase.name}; rpcEvidence=${it.executionEvidence.name}"
        }.orEmpty()
        android.util.Log.w("UkkeraNativeOwner",
            "$phase: ${failure.javaClass.simpleName}; nativeReason=$nativeReason$rpc; $locations")
    } catch (_: Throwable) { /* No diagnostic failure may change the protected outcome. */ }
}

internal actual fun reportRuntimeHistoryStage(stage: RuntimeHistoryDiagnosticStage) {
    try { android.util.Log.i("UkkeraNativeOwner", "Runtime history phase: ${stage.name}") }
    catch (_: Throwable) { /* Observation cannot change the outcome or skip mandatory denial. */ }
}

package com.ukkera.pos.database

internal actual fun reportLocalMasterRpcAuthenticationTiming(number: Long, phase: LocalMasterRpcAuthenticationTracePhase,
    stage: LocalMasterRpcAuthenticationStage, totalMicros: Long, final: Boolean) {
    try {
        if (!localMasterRpcReadTraceEnabled()) return
        DesktopSelfOwnedMasterDiagnostics.info("LAN RPC authentication timing: trace=$number; phase=${phase.name}; stage=${stage.name}; totalUs=$totalMicros; final=$final")
    } catch (_: Throwable) { /* Observation cannot change the actual outcome. */ }
}

internal actual fun localMasterRpcReadTraceEnabled(): Boolean =
    java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")

internal actual fun reportLocalMasterRpcReadTiming(number: Long, phase: LocalMasterRpcReadTracePhase,
    stage: LocalMasterRpcReadStage, elapsedMicros: Long, totalMicros: Long, final: Boolean) {
    try {
        if (!localMasterRpcReadTraceEnabled()) return
        DesktopSelfOwnedMasterDiagnostics.info("LAN RPC read timing: trace=$number; phase=${phase.name}; stage=${stage.name}; elapsedUs=$elapsedMicros; totalUs=$totalMicros; final=$final")
    } catch (_: Throwable) { /* Observation cannot change the actual outcome. */ }
}

internal actual fun reportOriginalInstallationBodyFailure(failure: Throwable) =
    reportStaticFailure("Original installation body refused", failure)

internal actual fun reportRuntimeHistoryLoanFailure(failure: Throwable) =
    reportStaticFailure("Runtime history work refused", failure)

internal actual fun reportRuntimeHistoryStage(stage: RuntimeHistoryDiagnosticStage) {
    try {
        if (!localMasterRpcReadTraceEnabled()) return
        DesktopSelfOwnedMasterDiagnostics.info("Native physical stage: ${stage.name}")
    } catch (_: Throwable) { /* Observation cannot change the actual outcome. */ }
}

public actual fun reportLocalMasterRpcAuthenticationStage(stage: LocalMasterRpcAuthenticationStage) {
    try { DesktopSelfOwnedMasterDiagnostics.info("LAN RPC authentication: stage=${stage.name}") }
    catch (_: Throwable) { /* Observation never changes admission or refusal. */ }
}

internal actual fun reportPhysicalRecordingFailure(failure: Throwable) =
    reportStaticFailure("Physical recording refused", failure)

public actual fun reportLocalMasterRpcReadFailure(stage: LocalMasterRpcReadStage, failure: Throwable?) {
    try {
        if (!java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")) return
        if (failure != null) reportStaticFailure("LAN RPC read refused: stage=${stage.name}", failure)
        else DesktopSelfOwnedMasterDiagnostics.info("LAN RPC read refused: stage=${stage.name}")
    } catch (_: Throwable) { /* Observation cannot change admission or refusal. */ }
}

internal actual fun reportLocalAccessVerificationFailure(stage: LocalAccessVerificationStage, failure: Throwable?) {
    try {
        if (!java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")) return
        if (failure != null) reportStaticFailure("Local access verification: stage=${stage.name}", failure)
        else DesktopSelfOwnedMasterDiagnostics.info("Local access verification refused: stage=${stage.name}")
    } catch (_: Throwable) { /* Observation cannot change the verification result. */ }
}

/** Bounded static locations only; no exception messages, paths, inputs or key material. */
private fun reportStaticFailure(phase: String, failure: Throwable) {
    try {
        val locations = failure.stackTrace.filter { it.className.startsWith("com.ukkera.pos.") }
            .take(8).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        val nativeReason = (failure as? com.ukkera.pos.security.NativeHistoryUnavailable)?.reason?.name ?: "none"
        val rpc = (failure as? dev.p2pkit.rpc.RpcFailure)?.let {
            "; rpcKind=${it.kind.name}; rpcPhase=${it.phase.name}; rpcEvidence=${it.executionEvidence.name}"
        }.orEmpty()
        DesktopSelfOwnedMasterDiagnostics.warn("$phase: ${failure.javaClass.simpleName}; nativeReason=$nativeReason$rpc; $locations")
    } catch (_: Throwable) { /* Diagnostics cannot change custody or the protected outcome. */ }
}

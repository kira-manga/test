package com.ukkera.pos.database

internal actual fun reportLocalMasterRpcAuthenticationTiming(number: Long, phase: LocalMasterRpcAuthenticationTracePhase,
    stage: LocalMasterRpcAuthenticationStage, totalMicros: Long, final: Boolean) = Unit

internal actual fun localMasterRpcReadTraceEnabled(): Boolean = false

internal actual fun reportLocalMasterRpcReadTiming(number: Long, phase: LocalMasterRpcReadTracePhase,
    stage: LocalMasterRpcReadStage, elapsedMicros: Long, totalMicros: Long, final: Boolean) = Unit

internal actual fun reportOriginalInstallationBodyFailure(failure: Throwable) = Unit

internal actual fun reportRuntimeHistoryLoanFailure(failure: Throwable) = Unit

internal actual fun reportRuntimeHistoryStage(stage: RuntimeHistoryDiagnosticStage) = Unit

internal actual fun reportPhysicalRecordingFailure(failure: Throwable) = Unit

public actual fun reportLocalMasterRpcAuthenticationStage(stage: LocalMasterRpcAuthenticationStage) = Unit

public actual fun reportLocalMasterRpcReadFailure(stage: LocalMasterRpcReadStage, failure: Throwable?) = Unit

internal actual fun reportLocalAccessVerificationFailure(stage: LocalAccessVerificationStage, failure: Throwable?) = Unit

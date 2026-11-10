package com.ukkera.pos.database

internal actual fun reportOriginalInstallationBodyFailure(failure: Throwable) = Unit

internal actual fun reportRuntimeHistoryLoanFailure(failure: Throwable) = Unit

internal actual fun reportRuntimeHistoryStage(stage: RuntimeHistoryDiagnosticStage) = Unit

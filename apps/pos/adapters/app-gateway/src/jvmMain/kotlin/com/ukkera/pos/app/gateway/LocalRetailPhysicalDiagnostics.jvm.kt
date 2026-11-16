package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.DeferredReason

internal actual fun reportLocalRetailPhysicalFailure(stage: LocalRetailPhysicalFailureStage,
    reason: DeferredReason?, failure: Throwable?) {
    try {
        if (!java.lang.Boolean.getBoolean("ukkera.lan.verifierDiagnostics")) return
        val frames = failure?.stackTrace?.filter { it.className.startsWith("com.ukkera.pos.") }
            ?.take(8)?.joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        val message = "Retail physical continuation refused: stage=${stage.name}; deferred=${reason?.name ?: "none"}; " +
            "failure=${failure?.javaClass?.simpleName ?: "none"}; $frames"
        System.err.println(message)
    } catch (_: Throwable) { /* Observation never changes the exact original result. */ }
}

package com.ukkera.pos.app.gateway.setup

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Native reentrant mutex only; all protected state and transition rules stay in common code. */
internal expect class LocalSetupMonitor() {
    fun lock()
    fun unlock()
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T> withSetupLock(monitor: LocalSetupMonitor, action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    monitor.lock()
    try { return action() } finally { monitor.unlock() }
}

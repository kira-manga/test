package com.ukkera.pos.app.gateway.setup

import java.util.concurrent.locks.ReentrantLock

internal actual class LocalSetupMonitor actual constructor() {
    private val value = ReentrantLock()
    actual fun lock(): Unit = value.lock()
    actual fun unlock(): Unit = value.unlock()
}

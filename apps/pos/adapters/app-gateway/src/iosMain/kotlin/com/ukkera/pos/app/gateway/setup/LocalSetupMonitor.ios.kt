package com.ukkera.pos.app.gateway.setup

import platform.Foundation.NSRecursiveLock

internal actual class LocalSetupMonitor actual constructor() {
    private val value = NSRecursiveLock()
    actual fun lock(): Unit = value.lock()
    actual fun unlock(): Unit = value.unlock()
}

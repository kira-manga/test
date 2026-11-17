package com.ukkera.pos.database

import platform.Foundation.NSRecursiveLock

internal actual class LocalAuthorizationObservationLock actual constructor() {
    private val delegate = NSRecursiveLock()
    actual fun lock() { delegate.lock() }
    actual fun unlock() { delegate.unlock() }
}

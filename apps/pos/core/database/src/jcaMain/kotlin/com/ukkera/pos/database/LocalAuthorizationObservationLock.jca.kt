package com.ukkera.pos.database

import java.util.concurrent.locks.ReentrantLock

internal actual class LocalAuthorizationObservationLock actual constructor() {
    private val delegate = ReentrantLock()
    actual fun lock() { delegate.lock() }
    actual fun unlock() { delegate.unlock() }
}

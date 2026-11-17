package com.ukkera.pos.database

/** Only the trusted synchronous clock sample and its same-owner accounting share this lock.
 * No Room loan, suspension, native acquisition or request callback may execute under it. */
internal expect class LocalAuthorizationObservationLock() {
    fun lock()
    fun unlock()
}

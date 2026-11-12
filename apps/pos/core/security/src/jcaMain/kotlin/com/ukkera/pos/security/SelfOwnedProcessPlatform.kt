package com.ukkera.pos.security

/**
 * OS process facts and diagnostics for the shared native owner work/watchdog. Android supplies
 * Process/SystemClock/Log; desktop supplies ProcessHandle and its own boot-including monotonic clock.
 * Every value is an observation; nothing here grants authority.
 */
internal interface SelfOwnedProcessPlatform {
    fun pid(): Int
    fun uid(): Int
    /** Same-boot monotonic milliseconds, including suspended time where the OS supports it. */
    fun elapsedMillis(): Long
    /** Best-effort self-termination of the exact current process; never another PID. */
    fun terminate(pid: Int)
    fun info(message: String)
    fun warn(message: String)
    /** Native errno of a platform exception, when it carries one. */
    fun errno(failure: Throwable): Int? = null
}

/** Exact owner of a retained original Room close (the native installation lifetime). */
internal interface SelfOwnedOriginalRoomCloseOwner {
    fun requireOriginalRoomCloseOwner(driver: Any)
}

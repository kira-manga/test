package com.ukkera.pos.app

/** Runs independent negative cleanup without losing either later work or the original failure. */
internal class LocalSessionCleanup {
    private var failure: Throwable? = null
    val failed: Boolean get() = failure != null

    /** A holder may be released only when its own cleanup completed successfully. */
    fun attempt(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: Throwable) {
        val original = failure
        if (original == null) failure = error
        else if (original !== error) original.addSuppressed(error)
        false
    }

    fun throwIfFailed() { failure?.let { throw it } }
}

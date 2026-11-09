package com.ukkera.pos.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ukkera.pos.app.LocalSessionCleanup

/** UI-loop-only, negative presentation lifetime. Never a service or native authorization. */
internal class LocalConfigurationEntryLifetime {
    var identity: Any by mutableStateOf(Any())
        private set
    var accessDenied: Boolean = false
        private set
    private var clearPresentation: (() -> Unit)? = null
    // A failed composed callback stays reachable after its entry/store is removed.
    // Retention permits negative cleanup only; it cannot restore its old identity.
    private val retiredPresentations = mutableListOf<() -> Unit>()

    fun isCurrent(captured: Any): Boolean = identity === captured

    /** One composed card; releasing an old observer must not detach its replacement. */
    fun attach(captured: Any, clear: () -> Unit): () -> Unit {
        if (!isCurrent(captured)) {
            retiredPresentations += clear
            clearRetiredPresentations()
            return {}
        }
        clearPresentation?.let { retiredPresentations += it }
        clearPresentation = null
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { clearRetiredPresentations() }
        if (cleanup.failed) {
            // A replacement cannot be installed after failed old cleanup. Still attempt
            // its independent cleanup and retain it too if that attempt fails.
            retiredPresentations += clear
            if (cleanup.attempt(clear)) releaseRetiredPresentation(clear)
            cleanup.throwIfFailed()
        }
        clearPresentation = clear
        return { if (clearPresentation === clear) clearPresentation = null }
    }

    /** Invalidate callbacks and destroy composed private state BEFORE navigation/publication. */
    fun retire(denied: Boolean = accessDenied) {
        val clear = clearPresentation
        clearPresentation = null
        accessDenied = denied
        identity = Any()
        clear?.let { retiredPresentations += it }
        clearRetiredPresentations()
    }

    private fun clearRetiredPresentations() {
        val cleanup = LocalSessionCleanup()
        retiredPresentations.toList().forEach { clear ->
            if (cleanup.attempt(clear)) releaseRetiredPresentation(clear)
        }
        cleanup.throwIfFailed()
    }

    private fun releaseRetiredPresentation(clear: () -> Unit) {
        val index = retiredPresentations.indexOfFirst { it === clear }
        if (index >= 0) retiredPresentations.removeAt(index)
    }

    fun accessObserved() { accessDenied = false }
}

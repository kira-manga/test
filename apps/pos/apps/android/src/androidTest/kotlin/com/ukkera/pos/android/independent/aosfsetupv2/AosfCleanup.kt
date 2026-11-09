package com.ukkera.pos.android.independent.aosfsetupv2

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Attempt each owned cleanup once; uncertainty must fail even an otherwise skipped case. */
internal suspend fun cleanupAosf(vararg actions: suspend () -> Unit) {
    withContext(NonCancellable) {
        var failure: AssertionError? = null
        for (action in actions) {
            try { action() } catch (problem: Throwable) {
                val retained = failure ?: AssertionError("AOSF cleanup was not confirmed", problem)
                    .also { failure = it }
                if (retained.cause !== problem) retained.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
}

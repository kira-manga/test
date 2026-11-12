package com.ukkera.pos.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Native adapters retain OS requests and I/O independently of a cancelled caller.
 * Data-only source ports have no native lifetime to certify. This port supplies only
 * negative cleanup custody; it grants no session, file or business authority.
 * Native adapters must retain every failed/unproved close here before returning
 * its error. The tracker owns those obligations independently of result delivery. */
interface LocalNativePurchasingDocumentFiles : LocalPurchasingDocumentFiles {
    val nativeCleanup: LocalPurchasingDocumentNativeCleanup
}

/** Constructed by the native adapter, never by an employee/session callback. A lease
 * ends only at its actual native completion. Unproved close retains its original
 * holder as negative evidence; a cancelled wait cannot release it. */
class LocalPurchasingDocumentNativeCleanup internal constructor() {
    private class Failure(val holder: Any, val retry: (suspend () -> Unit)?)
    private data class State(val leases: Set<Lease> = emptySet(), val unconfirmed: List<Failure> = emptyList())
    private val state = MutableStateFlow(State())
    internal val hasUnconfirmed: Boolean get() = state.value.unconfirmed.isNotEmpty()
    internal val clear: Boolean get() = state.value.let { it.leases.isEmpty() && it.unconfirmed.isEmpty() }

    private fun update(change: (State) -> State) {
        while (true) {
            val before = state.value
            if (state.compareAndSet(before, change(before))) return
        }
    }

    internal fun retain(holder: Any): Lease = Lease(holder).also { lease ->
        update { it.copy(leases = it.leases + lease) }
    }

    internal fun unconfirmed(holder: Any, retry: (suspend () -> Unit)? = null) {
        update { before ->
            val retained = before.unconfirmed.firstOrNull { it.holder === holder }
            val failure = Failure(holder, retry ?: retained?.retry)
            before.copy(unconfirmed = before.unconfirmed.filterNot { it.holder === holder } + failure)
        }
    }

    /** Only a fresh conclusive observation of this exact retained holder resolves it. */
    internal fun resolved(holder: Any) {
        update { it.copy(unconfirmed = it.unconfirmed.filterNot { failure -> failure.holder === holder }) }
    }

    /** Only native adapters register safe negative actions. A returned retry or an
     * unrelated success cannot resolve anything; the action must observe release
     * and call resolved for its exact holder. Unproved descriptor numbers are never
     * registered for retry. One failed action does not prevent the other attempts. */
    internal suspend fun retry() {
        val retained = state.value.unconfirmed
        for (failure in retained) {
            val action = failure.retry ?: continue
            if (state.value.unconfirmed.none { it.holder === failure.holder }) continue
            try { action() }
            catch (_: Throwable) { unconfirmed(failure.holder, action) }
        }
    }

    internal suspend fun <T> during(holder: Any, action: suspend () -> T): T {
        val lease = retain(holder)
        try { return action() } finally { lease.completed() }
    }

    internal inner class Lease(private val holder: Any) {
        /** Actual callback/close completion only. Caller's cancellation is not completion. */
        fun completed() { update { it.copy(leases = it.leases - this) } }
        override fun toString(): String = "NativeDocumentCleanupLease([REDACTED])"
    }

    override fun toString(): String = "LocalPurchasingDocumentNativeCleanup([REDACTED])"
}

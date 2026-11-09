package com.ukkera.pos.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Negative-only attachment. Implementations must deny synchronously, without an observer hop. */
fun interface LocalRetailPhysicalDenial { fun deny() }

/**
 * One entry/session incarnation. Closing the private CAS state prevents racing attachments;
 * every attached REAL original lifetime is denied before deny returns to a mutation owner.
 * This object is never a grant. There is no reset, timer, saved-state decoder or active flag
 * accepted by the physical kernel. Its private flow has no presentation subscribers.
 */
class LocalRetailPhysicalLifetime : LocalRetailPhysicalDenial {
    private data class State(val denied: Boolean, val entries: List<Attachment>,
        val activeDrains: Int = 0, val drainFailed: Boolean = false)
    private val state = MutableStateFlow(State(false, emptyList()))
    val denied: Boolean get() = state.value.denied

    fun attach(denial: LocalRetailPhysicalDenial): Attachment? {
        val value = Attachment(this, denial)
        while (true) {
            val before = state.value
            if (before.denied || before.entries.size >= 128) { denial.deny(); return null }
            if (state.compareAndSet(before, before.copy(entries = before.entries + value))) return value
        }
    }

    override fun deny() {
        val entries: List<Attachment>
        while (true) {
            val before = state.value
            if (before.denied && before.entries.isEmpty()) return
            check(before.activeDrains != Int.MAX_VALUE)
            val next = before.copy(denied = true, activeDrains = before.activeDrains + 1,
                drainFailed = before.activeDrains != 0 && before.drainFailed)
            if (state.compareAndSet(before, next)) { entries = before.entries; break }
        }
        // Every participant performs the exact denials. No callback runs inside CAS, and
        // another participant's success cannot discard custody while this one can still fail.
        var failed = false
        try { denyAll(entries) }
        catch (error: Throwable) { failed = true; throw error }
        finally {
            while (true) {
                val before = state.value
                check(before.denied && before.activeDrains > 0)
                val remaining = before.activeDrains - 1
                val cohortFailed = before.drainFailed || failed
                val next = before.copy(activeDrains = remaining, drainFailed = cohortFailed,
                    entries = if (remaining == 0 && !cohortFailed) emptyList() else before.entries)
                if (state.compareAndSet(before, next)) break
            }
        }
    }

    private fun denyAll(entries: List<Attachment>) {
        var failure: Throwable? = null
        for (entry in entries) try { entry.denyTarget() }
        catch (error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) failure.addSuppressed(error)
        }
        // One failing negative attachment cannot strand later originals. Retain every
        // attachment on failure; only a completely successful drain may clear the list.
        failure?.let { throw it }
    }

    private fun remove(value: Attachment) {
        while (true) {
            val before = state.value
            if (before.denied || value !in before.entries) return
            if (state.compareAndSet(before, before.copy(entries = before.entries.filterNot { it === value }))) return
        }
    }

    class Attachment internal constructor(private val owner: LocalRetailPhysicalLifetime,
        private val target: LocalRetailPhysicalDenial) {
        internal fun denyTarget() { target.deny() }
        /** Always denies first. Retirement is not a lifetime action cap or a new permission. */
        fun close() { target.deny(); owner.remove(this) }
    }
}

/** Runtime-created removal bookkeeping, never a grant or a serializable navigation token. */
internal class LocalRetailPhysicalOwner(val lifetime: LocalRetailPhysicalLifetime,
    private val root: LocalRetailPhysicalLifetime.Attachment,
    private val entry: LocalRetailPhysicalLifetime.Attachment) : LocalRetailPhysicalDenial {
    override fun deny() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { lifetime.deny() }
        cleanup.attempt { entry.close() }
        cleanup.attempt { root.close() }
        cleanup.throwIfFailed()
    }
}

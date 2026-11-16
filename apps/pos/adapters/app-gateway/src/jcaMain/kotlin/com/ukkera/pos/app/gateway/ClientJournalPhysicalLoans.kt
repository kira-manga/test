package com.ukkera.pos.app.gateway

import com.ukkera.pos.sales.store.LocalPhysicalExternalCustody
import com.ukkera.pos.sales.store.LocalPhysicalExternalDenialRegistration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/**
 * Physical-loan census of ONE successful employee intent journal owner (Android application or
 * desktop Master service cohost). The owner decides whether a custody may capture a loan; this
 * state machine only fences loans/observers on retirement. One CAS atom prevents retirement/
 * capture races. Observer failure never prevents other end-only denials or loan drainage; no
 * reset/replacement or positive verifier is exposed.
 */
internal class ClientJournalPhysicalLoans : ClientJournalPhysicalOwner {
    private class PhysicalState(val denied: Boolean, val loans: List<PhysicalLoan>,
        val observers: List<PhysicalObserver>)
    private val physical = AtomicReference(PhysicalState(false, emptyList(), emptyList()))
    private val physicalDrain = CompletableDeferred<Unit>()
    private val physicalDenialDelivered = CompletableDeferred<Unit>()

    /** Final memory-only denial; safe for the registered transaction participant read. */
    override val physicalDenied: Boolean get() = physical.get().denied
    override val physicalLoansDrained: Boolean get() = physicalDenialDelivered.isCompleted &&
        physical.get().let { it.denied && it.loans.isEmpty() }

    /** No native IO or grant callback; the owner already joined the exact custody. */
    fun capture(): LocalPhysicalExternalCustody? {
        val loan = PhysicalLoan()
        while (true) {
            val selected = physical.get()
            if (selected.denied) return null
            if (physical.compareAndSet(selected, PhysicalState(false, selected.loans + loan, selected.observers))) return loan
        }
    }

    override fun denyPhysical() {
        val selected = denyPhysicalState() ?: return
        try {
            selected.loans.forEach { it.deny() }
            selected.observers.forEach { it.deny() }
        } finally {
            // A racing second retire/close cannot report drainage before the first denial
            // has delivered every end-only core-atom notification.
            physicalDenialDelivered.complete(Unit)
            if (physical.get().loans.isEmpty()) physicalDrain.complete(Unit)
        }
    }

    private fun denyPhysicalState(): PhysicalState? {
        while (true) {
            val selected = physical.get()
            if (selected.denied) return null
            if (physical.compareAndSet(selected, PhysicalState(true, selected.loans, selected.observers))) return selected
        }
    }

    override suspend fun awaitPhysicalDrain() {
        denyPhysical()
        if (physical.get().loans.isEmpty()) physicalDrain.complete(Unit)
        physicalDenialDelivered.await()
        physicalDrain.await()
    }

    private fun releasePhysicalLoan(loan: PhysicalLoan) {
        while (true) {
            val selected = physical.get()
            if (selected.loans.none { it === loan }) return
            val next = PhysicalState(selected.denied, selected.loans.filterNot { it === loan }, selected.observers)
            if (physical.compareAndSet(selected, next)) return
        }
    }

    private fun observePhysicalDenial(onDenial: () -> Unit): PhysicalObserver {
        val observer = PhysicalObserver(onDenial)
        while (true) {
            val selected = physical.get()
            if (selected.denied) { observer.deny(); return observer }
            if (physical.compareAndSet(selected, PhysicalState(false, selected.loans, selected.observers + observer))) return observer
        }
    }

    private fun releasePhysicalObserver(observer: PhysicalObserver) {
        while (true) {
            val selected = physical.get()
            if (selected.observers.none { it === observer } || physical.compareAndSet(selected,
                    PhysicalState(selected.denied, selected.loans, selected.observers.filterNot { it === observer }))) return
        }
    }

    private inner class PhysicalObserver(private val onDenial: () -> Unit) : LocalPhysicalExternalDenialRegistration {
        private val finished = AtomicReference(false)
        fun deny() {
            if (finished.compareAndSet(false, true)) try { onDenial() } catch (_: Throwable) { /* Denial cannot grant or stop other observers. */ }
        }
        override fun release() { finished.set(true); releasePhysicalObserver(this) }
    }

    private enum class PhysicalLoanState { OWNED, RELEASING, RELEASED, UNCERTAIN }
    private inner class PhysicalLoan : LocalPhysicalExternalCustody {
        private val state = AtomicReference(PhysicalLoanState.OWNED)
        private val invocationDenied = AtomicReference(false)
        override val denied: Boolean get() = lifetimeDenied || invocationDenied.get() || state.get() != PhysicalLoanState.OWNED
        override val lifetimeDenied: Boolean get() = physicalDenied
        override fun deny() { invocationDenied.set(true) }
        override fun observeLifetimeDenial(onDenial: () -> Unit): LocalPhysicalExternalDenialRegistration = observePhysicalDenial(onDenial)
        override fun release(): Boolean {
            if (!state.compareAndSet(PhysicalLoanState.OWNED, PhysicalLoanState.RELEASING)) return state.get() == PhysicalLoanState.RELEASED
            val complete = try { releasePhysicalLoan(this); true } catch (_: Throwable) { false }
            state.set(if (complete) PhysicalLoanState.RELEASED else PhysicalLoanState.UNCERTAIN)
            if (!complete) denyPhysical()
            else if (physical.get().let { it.denied && it.loans.isEmpty() }) {
                // Cleanup publication already succeeded; a memory-only wake cannot turn it
                // into a failed release after the actual loan has left the owner census.
                try { physicalDrain.complete(Unit) } catch (_: Throwable) { /* Close also resamples the empty census. */ }
            }
            return complete
        }
    }
}

package com.ukkera.pos.app.gateway

import com.ukkera.pos.types.DomainId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal enum class ClientJournalKind { CREDENTIALS, INTENTS, ACTOR_ALLOCATIONS }
internal sealed interface ClientJournalOutcome<out T> {
    class Value<T>(val value: T) : ClientJournalOutcome<T>
    data object Unavailable : ClientJournalOutcome<Nothing>
}
internal interface ClientJournalTask<T> {
    fun execute(): T
    fun discard(value: T)
    fun clearInputs()
}

/** Genuine process identity and monotonic elapsed time of the owning process (Android or desktop). */
internal interface ClientJournalProcess {
    fun matchesOriginal(): Boolean
    fun elapsedMillis(): Long
}

/** Physical-loan facet of a successful journal owner that the shared work fences on retirement. */
internal interface ClientJournalPhysicalOwner {
    fun denyPhysical()
    val physicalDenied: Boolean
    val physicalLoansDrained: Boolean
    suspend fun awaitPhysicalDrain()
}

/** No snapshot/epoch can substitute for this actual same-process retained-resource exclusion. */
internal class ClientJournalProcessLease private constructor(private val journalId: DomainId) {
    private var retainedFile: Any? = null
    private var retainedWork: ClientJournalWork? = null
    fun retain(file: Any, work: ClientJournalWork) = synchronized(gate) {
        check(current[journalId] === this && retainedFile == null && retainedWork == null)
        retainedFile = file; retainedWork = work
    }
    fun releaseUnused() = synchronized(gate) {
        check(current[journalId] === this && retainedFile == null && retainedWork == null)
        current.remove(journalId); Unit
    }
    fun releaseAfterActualClose() = synchronized(gate) {
        check(current[journalId] === this)
        current.remove(journalId); retainedFile = null; retainedWork = null
    }
    companion object {
        private val gate = Any()
        private val current = mutableMapOf<DomainId, ClientJournalProcessLease>()
        fun acquire(journalId: DomainId): ClientJournalProcessLease? = synchronized(gate) {
            if (journalId in current) null else ClientJournalProcessLease(journalId).also { current[journalId] = it }
        }
    }
}

/**
 * One admitted owned task, no mutable-input waiter queue. Cancellation abandons delivery, not
 * disk work. The independently scheduled watchdog denies access on overrun, retaining the
 * operation, native resources and process lease. It NEVER kills the cohost/master process.
 * Native-close uncertainty leaves the close result exceptional and exclusion retained.
 */
internal class ClientJournalWork(private val lease: ClientJournalProcessLease, private val process: ClientJournalProcess) {
    private val gate = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastElapsed = AtomicLong(process.elapsedMillis())
    private var accepting = true
    private var closeUnknown = false
    private var closed = false
    private var active: Invocation<*>? = null
    private var segment: Segment? = null
    private var closeResult: CompletableDeferred<Unit>? = null
    private val successfulOwner = AtomicReference<ClientJournalPhysicalOwner?>(null)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ukkera-client-journal-watchdog").apply { isDaemon = true }
    }

    init {
        try {
            check(process.matchesOriginal() && lastElapsed.get() >= 0)
            watchdog.scheduleWithFixedDelay({
                try {
                    val timedOut = synchronized(gate) { segment?.let { clock() >= it.deadline } == true }
                    if (timedOut) retireAccess()
                } catch (_: Throwable) { retireAccess() }
            }, 100L, 100L, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) { watchdog.shutdownNow(); scope.cancel(); throw failure }
    }

    private fun clock(): Long {
        check(process.matchesOriginal())
        repeat(8) {
            val previous = lastElapsed.get()
            val now = process.elapsedMillis()
            check(now >= 0 && now >= previous)
            if (lastElapsed.compareAndSet(previous, now)) return now
        }
        error("client.journal.clock")
    }

    fun requireActive() {
        val usable = synchronized(gate) {
            accepting && !closed && !closeUnknown && segment?.let { clock() < it.deadline } == true
        }
        if (!usable) retireAccess()
        check(usable) { "client.journal.retired" }
    }

    private fun arm(token: Any) = synchronized(gate) {
        check(!closed && !closeUnknown)
        val now = clock()
        check(now <= Long.MAX_VALUE - NATIVE_SEGMENT_MILLIS)
        segment = Segment(token, now + NATIVE_SEGMENT_MILLIS)
    }

    private fun disarm(token: Any) = synchronized(gate) {
        if (!closeUnknown && segment?.token === token) segment = null
    }

    fun attachSuccessfulOwner(owner: ClientJournalPhysicalOwner) {
        check(successfulOwner.compareAndSet(null, owner) || successfulOwner.get() === owner)
        // A racing retirement first changed acceptance, then samples this reference.
        // Attachment independently notices retirement even if it missed the earlier sample.
        if (synchronized(gate) { !accepting || closed || closeUnknown }) owner.denyPhysical()
    }

    fun isCurrentOwner(owner: ClientJournalPhysicalOwner): Boolean {
        if (successfulOwner.get() !== owner) return false
        val usable = try { synchronized(gate) {
            accepting && !closed && !closeUnknown && process.matchesOriginal() &&
                (segment?.let { clock() < it.deadline } ?: true)
        } } catch (_: Throwable) { false }
        if (!usable) retireAccess()
        return usable && !owner.physicalDenied
    }

    private fun retireAccess() {
        val invocation = synchronized(gate) { accepting = false; active }
        successfulOwner.get()?.denyPhysical()
        invocation?.abandon(postRefusal = true)
    }

    fun prepareNativeClose() {
        synchronized(gate) { accepting = false }
        val owner = successfulOwner.get()
        owner?.denyPhysical()
        check(owner?.physicalLoansDrained != false) { "client.journal.physical-custody-held" }
    }

    fun nativeClosureUnknown() {
        synchronized(gate) { closeUnknown = true; accepting = false }
        retireAccess()
    }

    fun retireView(): CompletableDeferred<Unit> {
        val invocation = synchronized(gate) { accepting = false; active }
        successfulOwner.get()?.denyPhysical()
        invocation?.abandon(postRefusal = true)
        return invocation?.finished ?: CompletableDeferred(Unit)
    }

    suspend fun <T> run(prepare: () -> ClientJournalTask<T>): ClientJournalOutcome<T> {
        currentCoroutineContext().ensureActive()
        val invocation: Invocation<T>
        synchronized(gate) {
            if (!accepting || closed || closeUnknown || active != null) return ClientJournalOutcome.Unavailable
            // Copy bounded private input only after winning this admission slot.
            val task = try { prepare() }
            catch (_: Exception) { return ClientJournalOutcome.Unavailable }
            catch (_: Throwable) { retireAccess(); return ClientJournalOutcome.Unavailable }
            invocation = try { Invocation(task) }
            catch (_: Throwable) {
                try { task.clearInputs() } finally { retireAccess() }
                return ClientJournalOutcome.Unavailable
            }
            active = invocation
            try { arm(invocation) } catch (_: Throwable) {
                accepting = false
                try { task.clearInputs() } finally { active = null; successfulOwner.get()?.denyPhysical() }
                return ClientJournalOutcome.Unavailable
            }
        }
        var claimed = false
        try {
            val result = suspendCancellableCoroutine<ClientJournalOutcome<T>> { continuation ->
                invocation.continuation = continuation
                continuation.invokeOnCancellation { invocation.abandon(postRefusal = false) }
                scope.launch { invocation.execute() }
            }
            currentCoroutineContext().ensureActive()
            claimed = invocation.claim()
            return if (claimed || result is ClientJournalOutcome.Unavailable) result
                else ClientJournalOutcome.Unavailable
        } finally { if (!claimed) invocation.abandon(postRefusal = false) }
    }

    fun requestClose(closeNative: () -> Unit): CompletableDeferred<Unit> {
        val result: CompletableDeferred<Unit>
        val invocation: Invocation<*>?
        synchronized(gate) {
            closeResult?.let { return it }
            accepting = false
            result = CompletableDeferred()
            closeResult = result
            invocation = active
        }
        successfulOwner.get()?.denyPhysical()
        invocation?.abandon(postRefusal = true)
        scope.launch {
            val token = Any()
            try {
                invocation?.finished?.await()
                // Waiting on memory loans is not a split native acquisition or a renewed
                // authorization segment. Real close begins only after the captured borrowers
                // have certainly released; unknown cleanup keeps every original resource held.
                successfulOwner.get()?.awaitPhysicalDrain()
                arm(token)
                closeNative()
                synchronized(gate) {
                    val watched = segment
                    check(!closeUnknown && watched?.token === token && clock() < watched.deadline)
                    lease.releaseAfterActualClose()
                    closed = true
                    segment = null
                }
                result.complete(Unit)
                watchdog.shutdown()
                scope.cancel()
            } catch (_: Throwable) {
                nativeClosureUnknown()
                result.completeExceptionally(IllegalStateException("Client journal native close remains unconfirmed"))
                // Keep the watchdog/scope/resource lease after any uncertain physical close.
            }
        }
        return result
    }

    private inner class Invocation<T>(private val task: ClientJournalTask<T>) {
        val finished = CompletableDeferred<Unit>()
        @Volatile var continuation: CancellableContinuation<ClientJournalOutcome<T>>? = null
        private var result: ClientJournalOutcome<T>? = null
        private var ready = false
        private var abandoned = false
        private var posted = false
        private var disposing = false
        private var done = false

        fun execute() {
            val outcome: ClientJournalOutcome<T> = try {
                requireActive()
                if (synchronized(gate) { abandoned }) ClientJournalOutcome.Unavailable
                else ClientJournalOutcome.Value(task.execute())
            } catch (_: Throwable) {
                retireAccess()
                ClientJournalOutcome.Unavailable
            }
            try { task.clearInputs() } catch (_: Throwable) { nativeClosureUnknown() }
            try { requireActive() } catch (_: Throwable) { synchronized(gate) { abandoned = true } }
            val dispose = synchronized(gate) {
                result = outcome; ready = true
                if (abandoned || !accepting || closeUnknown) { abandoned = true; disposing = true; true }
                else { posted = true; false }
            }
            if (dispose) disposeAndFinish()
            else continuation?.resume(outcome) { _, _, _ -> abandon(postRefusal = false) }
        }

        fun abandon(postRefusal: Boolean) {
            var sendRefusal = false
            val launch = synchronized(gate) {
                if (done) return
                abandoned = true
                if (postRefusal && !posted && continuation != null) { posted = true; sendRefusal = true }
                (ready && !disposing).also { if (it) disposing = true }
            }
            if (sendRefusal) continuation?.resume(ClientJournalOutcome.Unavailable) { _, _, _ -> }
            if (launch) scope.launch { disposeAndFinish() }
        }

        fun claim(): Boolean = synchronized(gate) {
            if (!ready || done || abandoned || disposing || !accepting || closeUnknown) return@synchronized false
            try { requireActive() } catch (_: Throwable) { return@synchronized false }
            done = true
            if (active === this) active = null
            disarm(this)
            finished.complete(Unit)
            true
        }

        private fun disposeAndFinish() {
            try {
                // Disposal is part of the original bounded segment, not an unlimited unowned job.
                val outcome = result
                if (outcome is ClientJournalOutcome.Value) task.discard(outcome.value)
            } catch (_: Throwable) { nativeClosureUnknown() }
            finally {
                val send = synchronized(gate) {
                    val unposted = !posted
                    posted = true; done = true
                    if (active === this) active = null
                    disarm(this)
                    finished.complete(Unit)
                    unposted
                }
                if (send) continuation?.resume(ClientJournalOutcome.Unavailable) { _, _, _ -> }
            }
        }
    }

    private class Segment(val token: Any, val deadline: Long)
    private companion object { const val NATIVE_SEGMENT_MILLIS = 120_000L }
}


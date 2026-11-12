package com.ukkera.pos.security

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface AndroidOwnedResult<out T> {
    class Value<T>(val value: T) : AndroidOwnedResult<T>
    data class Unavailable(val reason: SelfOwnedInstallationFailureV1) : AndroidOwnedResult<Nothing>
}

/** Internal operation descriptions, NOT injected human/native/custody verifiers. */
internal interface AndroidOwnedTask<T> {
    /** Fixed completion observations for the authored publication approval only. No task data. */
    val traceCompletion: Boolean get() = false
    fun execute(): T
    fun discard(value: T)
    fun clearInputs()
}

/**
 * One admitted action, copied only after admission; no mutable input waiter queue. An owned
 * fresh scope does not inherit caller cancellation. Native execute, undelivered disposal and
 * actual file/owner close ALL retain admission and use an independently scheduled OS-clock
 * watchdog. Retirement requests this PID's exclusion, not keystore-service termination.
 *
 * A returned clock/lifetime Boolean is internal DENIAL observation only, never key/Room proof.
 */
internal class AndroidOwnedInstallationWork(
    private val processLease: AndroidProvisionerProcessLease,
    internal val platform: SelfOwnedProcessPlatform,
) {
    private val gate = Any()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    internal val roomCoroutines = AndroidOwnedRoomCoroutines(this, job, platform)
    private val pid = platform.pid()
    private val uid = platform.uid()
    private val retired = AtomicBoolean(false)
    private val exclusionRequested = AtomicBoolean(false)
    private val lastElapsed = AtomicLong(platform.elapsedMillis())
    private val segment = AtomicReference<SelfOwnedNativeOperationBudget?>(null)
    private var invocation: Invocation<*>? = null
    private var roomInvocation: RoomInvocation<*>? = null
    private var closing = false
    private var actualLifetimeClosed = false
    private var closeResult: CompletableDeferred<Unit>? = null
    private enum class RoomClosePhase { RETAINED, CLOSING, RETURNED }
    private class OriginalRoomClose(val owner: SelfOwnedOriginalRoomCloseOwner,
        val driver: Any, val database: Any, val closeActual: () -> Unit) {
        var phase: RoomClosePhase = RoomClosePhase.RETAINED // Guarded by gate; actual return only.
    }
    private var originalRoomClose: OriginalRoomClose? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ukkera-self-owned-installation-watchdog").apply { isDaemon = true }
    }

    init {
        try {
            require(pid > 0 && uid >= 0 && lastElapsed.get() >= 0)
            watchdog.scheduleWithFixedDelay({
                try {
                    synchronized(gate) {
                        val watched = segment.get()
                        if (watched != null && watched.isExpiredAt(clock()) && segment.get() === watched) {
                            requestProcessExclusion(ExclusionReason.WATCHDOG_DEADLINE_REACHED)
                        }
                    }
                } catch (_: Throwable) { requestProcessExclusion(ExclusionReason.WATCHDOG_CALLBACK_FAILED) }
            }, 100L, 100L, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            // Construction precedes any registry/key open. No native resource can be forgotten.
            retired.set(true); watchdog.shutdownNow(); scope.cancel(); throw failure
        }
    }

    fun clock(): Long {
        if (platform.pid() != pid || platform.uid() != uid) {
            requestProcessExclusion(ExclusionReason.PROCESS_IDENTITY_CHANGED); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        repeat(4) {
            val previous = lastElapsed.get()
            val sampled = platform.elapsedMillis()
            if (sampled < 0 || sampled < previous) {
                requestProcessExclusion(ExclusionReason.ELAPSED_SAMPLE_INVALID); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            }
            if (lastElapsed.compareAndSet(previous, sampled)) return sampled
        }
        requestProcessExclusion(ExclusionReason.ELAPSED_UPDATE_CONTENTION_LIMIT); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    }

    fun requireActive() {
        val now = clock(); val watched = segment.get()
        if (watched != null && watched.isExpiredAt(now)) requestProcessExclusion(ExclusionReason.ACTIVE_DEADLINE_REACHED)
        if (retired.get() || synchronized(gate) { closing }) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    }

    /** Cleanup-only custody observation. Denial/closing cannot authorize new data work, but
     * already-admitted rollback, TEMP cleanup and native close retain their ORIGINAL deadline. */
    internal fun requireWatchedRoom(token: Any) {
        val now = clock()
        val watched = segment.get()
        if (watched == null || !watched.isOwnedBy(token) || watched.isExpiredAt(now) || exclusionRequested.get() ||
            synchronized(gate) { actualLifetimeClosed }) {
            requestProcessExclusion(ExclusionReason.WATCHED_ROOM_GUARD_REFUSED); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
    }

    internal fun roomToken(context: CoroutineContext): Any? =
        context[RoomElement]?.takeIf { it.owner === this }?.invocation

    fun currentDeadlineMillis(): Long {
        requireActive()
        return segment.get()?.deadlineMillis ?: selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    }

    /** An actual close failed/has unknown completion. Never retry a potentially reused descriptor. */
    fun nativeClosureUnknown() {
        // Capture static originating callers before exclusion; never retain the Throwable,
        // native resource, exception message or inputs in the asynchronous observation.
        val origin = try { Throwable().stackTrace.filter {
            it.className.startsWith("com.ukkera.pos.")
        }.take(6).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" } }
        catch (_: Throwable) { "unavailable" }
        requestProcessExclusion(ExclusionReason.NATIVE_CLOSURE_UNKNOWN, origin)
    }

    /** Permanent local denial. Only actual close can release the process lease. */
    fun retire() { retired.set(true) }

    private fun requestProcessExclusion(reason: ExclusionReason, origin: String? = null) {
        val request = synchronized(gate) {
            if (actualLifetimeClosed) return
            retired.set(true)
            exclusionRequested.compareAndSet(false, true)
        }
        if (!request) return
        logProcessExclusionAsync(reason, origin)
        // Never guess another PID/session or pretend this call is proof of actual death.
        if (platform.pid() == pid && platform.uid() == uid) {
            try { platform.terminate(pid) } catch (_: Throwable) { /* lease remains held; no replacement is admitted */ }
        }
    }

    private fun arm(token: Any, phase: SelfOwnedNativeOperationPhase) {
        if (exclusionRequested.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val now = clock()
        val admitted = SelfOwnedNativeOperationBudget.admit(token, phase, now)
        if (admitted == null) {
            requestProcessExclusion(ExclusionReason.DEADLINE_OVERFLOW_RISK); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        segment.set(admitted)
    }

    /** Disposal is part of the admitted invocation; only its diagnostic phase changes. */
    private fun enterDiscard(token: Any) {
        synchronized(gate) {
            val admitted = segment.get()?.forUndeliveredDiscard(token)
            if (admitted == null) {
                requestProcessExclusion(ExclusionReason.DISCARD_GUARD_REFUSED)
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            }
            // Keep the admission deadline even after cancellation or a late native return.
            segment.set(admitted)
            if (exclusionRequested.get() || admitted.isExpiredAt(clock())) {
                requestProcessExclusion(ExclusionReason.DISCARD_GUARD_REFUSED)
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            }
        }
    }

    private fun disarm(token: Any) {
        if (exclusionRequested.get()) return // Keep the watchdog/lease after an unproved exclusion request.
        val previous = segment.get()
        if (previous?.isOwnedBy(token) == true) segment.compareAndSet(previous, null)
    }

    /** prepare MUST only copy bounded already-validated inputs; no IO or externally supplied code. */
    suspend fun <T> run(prepare: () -> AndroidOwnedTask<T>): AndroidOwnedResult<T> {
        currentCoroutineContext().ensureActive()
        // Native key/anchor operations inside the actual retained Room loan reuse THIS
        // watchdog/slot; they cannot queue a second lifetime or deadlock on their own slot.
        val parent = currentCoroutineContext()[RoomElement]
        if (parent != null && parent.owner === this) {
            synchronized(gate) { check(roomInvocation === parent.invocation && !closing) }
            requireActive()
            val task = prepare()
            return try { AndroidOwnedResult.Value(task.execute()) }
            catch (failure: SelfOwnedFailureV1) { AndroidOwnedResult.Unavailable(failure.reason) }
            finally { task.clearInputs() }
        }
        val owned: Invocation<T>
        synchronized(gate) {
            if (closing || retired.get()) return AndroidOwnedResult.Unavailable(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            if (invocation != null || roomInvocation != null) return AndroidOwnedResult.Unavailable(SelfOwnedInstallationFailureV1.BUSY)
            val task = try { prepare() } catch (_: Exception) {
                return AndroidOwnedResult.Unavailable(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            }
            owned = Invocation(task)
            invocation = owned
            try { arm(owned, SelfOwnedNativeOperationPhase.EXECUTE_AND_DELIVERY) } catch (failure: Throwable) {
                try { task.clearInputs() } finally { invocation = null; requestProcessExclusion(ExclusionReason.ACTION_ARM_FAILED) }
                throw failure
            }
        }
        var claimed = false
        try {
            val result = suspendCancellableCoroutine<AndroidOwnedResult<T>> { continuation ->
                owned.continuation = continuation
                continuation.invokeOnCancellation { owned.abandon() }
                scope.launch { owned.execute() }
            }
            currentCoroutineContext().ensureActive()
            claimed = owned.claim()
            // A refusal carries no resource/authority. It may report the original failure while
            // disposal/close still holds the slot; it never claims native closure completed.
            return if (claimed || result is AndroidOwnedResult.Unavailable) result
                else AndroidOwnedResult.Unavailable(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        } finally {
            if (!claimed) owned.abandon()
        }
    }

    /** Registration is synchronous in the original watched open/control invocation, not a
     * later caller/cleanup lane. The exact driver and bound database operation remain owned
     * even after denial or an abandoned close wait. A second/replacement binding is refused. */
    internal fun retainOriginalRoomClose(owner: SelfOwnedOriginalRoomCloseOwner,
        driver: Any, database: Any, closeActual: () -> Unit) {
        val token = roomCoroutines.currentNativeInvocation()
        synchronized(gate) {
            requireActive()
            check(roomInvocation === token && !closing && !actualLifetimeClosed && originalRoomClose == null)
            originalRoomClose = OriginalRoomClose(owner, driver, database, closeActual)
        }
    }

    /** A registered database is already owned by the final native cleanup, not by private
     * output disposal. Even a partial application binding cannot regain a raw close lane. */
    internal fun requireNoRetainedOriginalRoomClose() {
        synchronized(gate) {
            check(originalRoomClose == null) { "Original Room close belongs to terminal native cleanup" }
        }
    }

    private fun closeOriginalRoomAfterActualDrain(token: Any) {
        requireWatchedRoom(token)
        roomCoroutines.requireActualClose() // Final cleanup context, never a live Room control/query actor.
        val original = synchronized(gate) {
            check(closing && invocation == null && roomInvocation == null && !actualLifetimeClosed)
            originalRoomClose?.also {
                check(it.phase == RoomClosePhase.RETAINED)
                it.phase = RoomClosePhase.CLOSING
            }
        }
        // Outside the lifecycle monitor: the independent watchdog cannot be postponed by
        // actual Room close. A throw/unknown leaves CLOSING, cached failure and custody held;
        // no native driver close follows and this descriptor operation is never retried.
        original?.let { it.owner.requireOriginalRoomCloseOwner(it.driver) }
        original?.closeActual?.invoke()
        requireWatchedRoom(token)
        synchronized(gate) {
            original?.let {
                check(originalRoomClose === it && it.phase == RoomClosePhase.CLOSING)
                it.phase = RoomClosePhase.RETURNED
            }
        }
    }

    internal fun requireOriginalRoomClosedBeforeNativeClose(driver: Any?) {
        synchronized(gate) {
            originalRoomClose?.let {
                check(it.driver === driver && it.phase == RoomClosePhase.RETURNED)
            }
        }
    }

    /** Nonblocking request; actual cleanup belongs to the fresh owner even if its caller exits. */
    fun requestClose(closeNative: () -> Unit): CompletableDeferred<Unit> {
        val result: CompletableDeferred<Unit>
        val active: Invocation<*>?
        val roomActive: RoomInvocation<*>?
        synchronized(gate) {
            closeResult?.let { return it }
            closing = true
            result = CompletableDeferred()
            closeResult = result
            active = invocation
            roomActive = roomInvocation
        }
        active?.abandon()
        roomActive?.abandon()
        scope.launch {
            val token = Any()
            try {
                active?.finished?.await()
                roomActive?.finished?.await()
                arm(token, SelfOwnedNativeOperationPhase.OWNER_CLOSE)
                // Room.close cancels without joining. The real parent, children, detached
                // cleanup jobs and runnable tails must finish BEFORE any raw descriptor close.
                cleanupPhase("room-drain-entered")
                roomCoroutines.closeLifetime(token)
                cleanupPhase("room-drain-returned")
                roomCoroutines.nativeClose(token) {
                    cleanupPhase("room-close-entered")
                    closeOriginalRoomAfterActualDrain(token)
                    cleanupPhase("room-close-returned")
                    cleanupPhase("native-close-entered")
                    closeNative()
                    cleanupPhase("native-close-returned")
                }
                synchronized(gate) {
                    val current = segment.get()
                    if (current == null || !current.isOwnedBy(token) || current.isExpiredAt(clock()) || exclusionRequested.get()) {
                        requestProcessExclusion(ExclusionReason.FINAL_CLOSE_GUARD_REFUSED); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                    }
                    // Serialize final lease release with watchdog retirement: an old, already
                    // closed watchdog must not kill a newly acquired owner in this same PID.
                    roomCoroutines.requireLifetimeRelease()
                    processLease.releaseAfterActualClose()
                    actualLifetimeClosed = true
                    disarm(token)
                }
                result.complete(Unit)
                watchdog.shutdown()
                scope.cancel()
            } catch (failure: Throwable) {
                // Request exclusion BEFORE removing the only cleanup watchdog. Keep the lease.
                requestProcessExclusion(ExclusionReason.FINAL_CLOSE_PATH_FAILED)
                result.completeExceptionally(failure)
                // No disarm/shutdown/lease release on unknown close.
            }
        }
        return result
    }

    /** Caller cancellation abandons delivery, never the actual Room Job. Completion of the
     * body Job, its real query/background work AND dispatch/restore tails precedes publication. */
    suspend fun <T> runRoom(block: suspend () -> T, discard: suspend (T) -> Unit): T {
        currentCoroutineContext().ensureActive()
        check(currentCoroutineContext()[RoomElement] == null) { "Nested original Room loan" }
        val call: RoomInvocation<T>
        synchronized(gate) {
            requireActive()
            if (invocation != null || roomInvocation != null) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
            call = RoomInvocation(block, discard)
            roomInvocation = call
            arm(call, SelfOwnedNativeOperationPhase.ROOM_SETTLEMENT_AND_DELIVERY)
        }
        try {
            roomCoroutines.beginInvocation(call)
            scope.launch { call.execute() }
        } catch (failure: Throwable) { call.failUnknown(failure); throw failure }
        var claimed = false
        try {
            val value = call.result.await()
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                requireActive()
                check(roomInvocation === call && !call.abandoned)
                roomCoroutines.releaseInvocation(call)
                call.claimed = true; claimed = true
                roomInvocation = null; disarm(call); call.finished.complete(Unit)
            }
            return value.getOrThrow()
        } finally { if (!claimed) call.abandon() }
    }

    private class RoomElement(val owner: AndroidOwnedInstallationWork, val invocation: Any) :
        ThreadContextElement<AndroidOwnedRoomCoroutines.ThreadState>, AbstractCoroutineContextElement(Key) {
        override fun updateThreadContext(context: CoroutineContext): AndroidOwnedRoomCoroutines.ThreadState =
            owner.roomCoroutines.enter(context, query = false)
        override fun restoreThreadContext(context: CoroutineContext, oldState: AndroidOwnedRoomCoroutines.ThreadState) =
            owner.roomCoroutines.leave(oldState, query = false)
        companion object Key : CoroutineContext.Key<RoomElement>
    }

    private inner class RoomInvocation<T>(val body: suspend () -> T, val discard: suspend (T) -> Unit) {
        val result = CompletableDeferred<Result<T>>()
        val finished = CompletableDeferred<Unit>()
        var abandoned = false
        var claimed = false
        private var output: Result<T>? = null
        private var disposing = false

        suspend fun execute() {
            try {
                var bodyResult: Result<T>? = null
                val actual = scope.launch(RoomElement(this@AndroidOwnedInstallationWork, this) + roomCoroutines.dispatcher,
                    start = CoroutineStart.LAZY) {
                    bodyResult = try { requireActive(); Result.success(body()) }
                        catch (failure: Throwable) {
                            // Open failure cleanup can retire this owner before runRoom's
                            // delivery guard. Retain the originating static location too.
                            logUnavailableNativeAction(failure)
                            Result.failure(failure)
                        }
                }
                roomCoroutines.bindForeground(this, actual)
                roomCoroutines.traceStage("foreground-before-start")
                actual.start(); actual.join()
                roomCoroutines.traceStage("foreground-joined")
                roomCoroutines.finishInvocation(this)
                val value = checkNotNull(bodyResult) { "Owned Room body did not actually return" }
                val dispose = synchronized(gate) {
                    output = value
                    if (abandoned || closing || retired.get()) { abandoned = true; disposing = true; true } else false
                }
                result.complete(value)
                if (dispose) disposeNative()
            } catch (failure: Throwable) { failUnknown(failure) }
        }

        fun abandon() {
            val dispose = synchronized(gate) {
                if (claimed || finished.isCompleted) return
                abandoned = true
                if (output != null && !disposing) { disposing = true; true } else false
            }
            if (dispose) scope.launch { disposeNative() }
        }

        private suspend fun disposeNative() {
            try {
                // Even suspending actual Room close runs as owned work; no new deadline and
                // no caller's finally/dispatcher is trusted to drain the abandoned database.
                var failure: Throwable? = null
                var returned = false
                val actual = scope.launch(RoomElement(this@AndroidOwnedInstallationWork, this) + roomCoroutines.dispatcher,
                    start = CoroutineStart.LAZY) {
                    try { output?.getOrNull()?.let { discard(it) }; returned = true }
                    catch (caught: Throwable) { failure = caught }
                }
                roomCoroutines.bindForeground(this, actual, disposal = true)
                actual.start(); actual.join()
                failure?.let { throw it }
                check(returned) { "Owned Room disposal did not actually return" }
                roomCoroutines.finishInvocation(this)
                requireWatchedRoom(this)
                synchronized(gate) {
                    roomCoroutines.releaseInvocation(this)
                    if (roomInvocation === this) roomInvocation = null
                    disarm(this); finished.complete(Unit)
                }
            } catch (failure: Throwable) { failUnknown(failure) }
        }

        fun failUnknown(failure: Throwable) {
            roomCoroutines.fault(failure)
            result.complete(Result.failure(failure))
            finished.completeExceptionally(failure)
            // No slot release, disarm, native close or replacement after uncertain custody.
        }
    }

    private inner class Invocation<T>(private val task: AndroidOwnedTask<T>) {
        val finished = CompletableDeferred<Unit>()
        var continuation: CancellableContinuation<AndroidOwnedResult<T>>? = null
        private var result: AndroidOwnedResult<T>? = null
        private var abandoned = false
        private var ready = false
        private var posted = false
        private var disposing = false
        private var done = false

        private fun completionPhase(phase: String) {
            if (!task.traceCompletion) return
            try { platform.info("Native publication delivery: $phase") }
            catch (_: Throwable) { /* Diagnostics cannot alter ownership or the original result. */ }
        }

        fun execute() {
            val outcome: AndroidOwnedResult<T> = try {
                val skip = synchronized(gate) { abandoned }
                if (skip) AndroidOwnedResult.Unavailable(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                else {
                    requireActive()
                    // This worker is outside gate and its watchdog is already armed. A
                    // stalled diagnostic cannot claim or delay the process-exclusion path.
                    // The implementation class is a static source location, never task data.
                    try { platform.info("Native action entered: ${task.javaClass.name}") }
                    catch (_: Throwable) { /* diagnostics do not change the original result */ }
                    requireActive()
                    AndroidOwnedResult.Value(task.execute())
                }
            } catch (failure: SelfOwnedFailureV1) {
                if (failure.reason in RETIRING_FAILURES) retire()
                if (failure.reason == SelfOwnedInstallationFailureV1.STATE_UNCERTAIN ||
                    failure.reason == SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN) {
                    logUnavailableNativeAction(failure)
                }
                AndroidOwnedResult.Unavailable(failure.reason)
            } catch (failure: Throwable) {
                logUnavailableNativeAction(failure)
                retire(); AndroidOwnedResult.Unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            completionPhase("task-returned")
            try { task.clearInputs() } catch (_: Throwable) { requestProcessExclusion(ExclusionReason.ACTION_INPUT_CLEAR_FAILED) }
            completionPhase("inputs-cleared")
            try { requireActive() } catch (_: Throwable) { synchronized(gate) { abandoned = true } }
            val dispose: Boolean
            synchronized(gate) {
                result = outcome; ready = true
                dispose = abandoned || closing || retired.get()
                if (dispose) { disposing = true; abandoned = true }
                else posted = true
            }
            completionPhase(if (dispose) "disposal-selected" else "result-posting")
            if (dispose) disposeAndFinish()
            else continuation!!.resume(outcome) { _, _, _ -> abandon() }
        }

        fun abandon() {
            val launch: Boolean
            synchronized(gate) {
                if (done) return
                abandoned = true
                launch = ready && !disposing
                if (launch) disposing = true
            }
            if (launch) {
                // Admission remains watched while disposal waits for its worker.
                scope.launch { disposeAndFinish() }
            }
        }

        fun claim(): Boolean {
            // Keep diagnostic IO outside gate while the original watchdog is still armed.
            completionPhase("claim-entered")
            val accepted = synchronized(gate) {
                if (!ready || done || abandoned || closing || retired.get() || disposing) return@synchronized false
                try { requireActive() } catch (_: Throwable) { return@synchronized false }
                done = true
                if (invocation === this) invocation = null
                disarm(this)
                finished.complete(Unit)
                true
            }
            // Claim has finished its original ownership decision. Dispatch diagnostic IO
            // separately so neither the caller nor the owner gate waits for log delivery.
            if (task.traceCompletion) try {
                scope.launch { completionPhase(if (accepted) "claim-accepted" else "claim-refused") }
            } catch (_: Throwable) { /* Diagnostic dispatch cannot change claim ownership. */ }
            return accepted
        }

        private fun disposeAndFinish() {
            try {
                enterDiscard(this)
                val value = result
                if (value is AndroidOwnedResult.Value) task.discard(value.value)
                val watched = segment.get()
                if (watched == null || !watched.isOwnedBy(this) || watched.isExpiredAt(clock())) {
                    requestProcessExclusion(ExclusionReason.DISCARD_GUARD_REFUSED)
                }
            } catch (_: Throwable) {
                requestProcessExclusion(ExclusionReason.DISCARD_PATH_FAILED) // still under the watched segment
            } finally {
                val resumeFailure: Boolean
                synchronized(gate) {
                    resumeFailure = !posted
                    posted = true; done = true
                    if (invocation === this) invocation = null
                    disarm(this)
                    finished.complete(Unit)
                }
                if (resumeFailure) {
                    val refusal = result as? AndroidOwnedResult.Unavailable
                        ?: AndroidOwnedResult.Unavailable(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                    continuation?.resume(refusal) { _, _, _ -> }
                }
            }
        }
    }

    private enum class ExclusionReason {
        WATCHDOG_DEADLINE_REACHED,
        WATCHDOG_CALLBACK_FAILED,
        PROCESS_IDENTITY_CHANGED,
        ELAPSED_SAMPLE_INVALID,
        ELAPSED_UPDATE_CONTENTION_LIMIT,
        ACTIVE_DEADLINE_REACHED,
        WATCHED_ROOM_GUARD_REFUSED,
        NATIVE_CLOSURE_UNKNOWN,
        DEADLINE_OVERFLOW_RISK,
        ACTION_ARM_FAILED,
        FINAL_CLOSE_GUARD_REFUSED,
        FINAL_CLOSE_PATH_FAILED,
        ACTION_INPUT_CLEAR_FAILED,
        DISCARD_GUARD_REFUSED,
        DISCARD_PATH_FAILED,
    }

    private fun logProcessExclusionAsync(reason: ExclusionReason, origin: String?) {
        try {
            val watched = segment.get()
            val phase = watched?.phase?.diagnosticName
            val deadlineMillis = watched?.deadlineMillis
            // Direct OS sampling cannot recursively request exclusion through clock().
            val elapsedMillis = try { platform.elapsedMillis() } catch (_: Throwable) { null }
            // Capture scalars only, never the budget/token or native inputs. This is best
            // effort: asynchronous logging can lose the race with SIG9 and proves no closure.
            scope.launch {
                try {
                    platform.warn("Native process exclusion requested: reason=${reason.name}; " +
                        "phase=${phase ?: "none"}; elapsedMillis=${elapsedMillis ?: "unavailable"}; " +
                        "deadlineMillis=${deadlineMillis ?: "none"}; origin=${origin ?: "unobserved"}")
                } catch (_: Throwable) { /* logging cannot change denial or native custody */ }
            }
        } catch (_: Throwable) {
            // Capture, dispatch or thread-start failure cannot skip the exclusion signals.
        }
    }

    /** Called only outside gate while the existing terminal-close watchdog is armed. */
    private fun cleanupPhase(phase: String) {
        try { platform.info("Owner cleanup phase: $phase") }
        catch (_: Throwable) { /* Diagnostics do not establish or change native closure. */ }
    }

    /** Diagnostics are never authority: retain the same refusal and unknown-outcome rules. */
    private fun logUnavailableNativeAction(failure: Throwable) {
        try {
            // Never log exception messages/causes, file paths, inputs, identities or key data.
            // Provider exceptions can fill the first frames with framework internals.
            // Keep the bounded originating native callers as well; these are static code
            // locations, with no exception messages, arguments or native resource values.
            val frames = failure.stackTrace
            val locations = (frames.take(2) + frames.filter {
                it.className.startsWith("com.ukkera.pos.")
            }.take(4)).distinct().joinToString(" <- ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            val errno = platform.errno(failure)?.let { "; errno=$it" }.orEmpty()
            val reason = (failure as? SelfOwnedFailureV1)?.reason?.name?.let { "; reason=$it" }.orEmpty()
            platform.warn("Native action refused: ${failure.javaClass.simpleName}$errno$reason; $locations")
        } catch (_: Throwable) {
            // Diagnostic failure cannot change retirement, closure or the original result.
        }
    }

    companion object {
        private val RETIRING_FAILURES: Set<SelfOwnedInstallationFailureV1> = setOf(
            SelfOwnedInstallationFailureV1.INVALID_APPLICATION, SelfOwnedInstallationFailureV1.USER_LOCKED,
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN,
            SelfOwnedInstallationFailureV1.NATIVE_RETIRED, SelfOwnedInstallationFailureV1.UNAVAILABLE,
        )
    }
}

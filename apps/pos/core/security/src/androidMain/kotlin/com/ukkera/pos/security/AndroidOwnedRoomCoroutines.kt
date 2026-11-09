package com.ukkera.pos.security

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext

/**
 * Custody of the PINNED Room3 3.0.3 query scope, not an observer-name allowlist. Room creates
 * SupervisorJob(the supplied Job); getQueryContext removes only that Job. Consequently the
 * real writer keeps our private invocation element, whereas scope-launched invalidation has
 * only our query element. Neither a CoroutineName nor the currently attached frame grants SQL.
 *
 * Every dispatch is retained BEFORE enqueue, and every undispatched context entry is retained
 * until restore. Completed Jobs alone are insufficient: their dispatch/restore tails can still
 * be running. Room's NonCancellable BEGIN/end scopes are enrolled at their actual synchronous
 * context entry with the live parent's actor, then retained as real Jobs on later resumptions.
 * NonCancellable itself is never joined. Nothing here joins Room's intentionally idle supervisor
 * on a per-loan drain. Only final shutdown cancels and joins that supervisor and its parent.
 *
 * Lock order: work.gate may precede this gate; NEVER call work, dispatch, cancel, join or complete
 * a waiter while holding this gate. Unknown custody permanently retains the original exclusion.
 */
internal class AndroidOwnedRoomCoroutines(
    private val work: AndroidOwnedInstallationWork,
    ownerJob: Job,
) {
    internal enum class Kind { FOREGROUND, BACKGROUND, NATIVE }
    internal class Actor internal constructor(val invocation: Any, val root: Job, val kind: Kind)
    internal class Entry internal constructor(val actor: Actor, val job: Job, val query: Boolean)
    internal class ThreadState internal constructor(val previous: Entry?, val entered: Entry?)
    private class Ticket(val actor: Actor?, val job: Job?)

    private val gate = Any()
    private val parent = SupervisorJob(ownerJob)
    private val uncertain = AtomicBoolean(false)
    private val jobs = IdentityHashMap<Job, Actor>()
    private val backgroundRoots = IdentityHashMap<Job, Actor>()
    private val tickets = linkedSetOf<Ticket>()
    private val entries = linkedSetOf<Entry>()
    private val controlThread = ThreadLocal<Entry?>()
    private val queryThread = ThreadLocal<Entry?>()
    private val dispatchThread = ThreadLocal<Ticket?>()
    private val nativeThread = ThreadLocal<Actor?>()
    private val closeThread = ThreadLocal<Any?>()
    private var changed = CompletableDeferred<Unit>()
    private var invocation: Any? = null
    private var foreground: Actor? = null
    private var fenced = true
    private var quiet = true
    private var builderStarted = false
    private var builderReturned = false
    private var roomSupervisor: Job? = null
    private var stopping = false
    private var stopped = false

    internal val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        // Also intercept first launches and already-cancelled resumptions. No inline bypass.
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            var failure: Throwable? = null
            val ticket = synchronized(gate) {
                val actor = try { actorLocked(context) } catch (caught: Throwable) { failure = caught; null }
                Ticket(actor, context[Job]).also { tickets += it }
            }
            if (failure != null) fault()
            try {
                Dispatchers.IO.dispatch(context, Runnable {
                    val old = dispatchThread.get()
                    dispatchThread.set(ticket)
                    try { block.run() }
                    finally {
                        dispatchThread.set(old)
                        synchronized(gate) { tickets.remove(ticket) }
                        pulse()
                    }
                })
            } catch (caught: Throwable) {
                // A throwing executor does not prove that it did not enqueue. Retain the ticket.
                fault(); throw caught
            }
        }
    }
    private val queryElement = object : ThreadContextElement<ThreadState>, AbstractCoroutineContextElement(QueryKey) {
        override fun updateThreadContext(context: CoroutineContext): ThreadState = enter(context, query = true)
        override fun restoreThreadContext(context: CoroutineContext, oldState: ThreadState) = leave(oldState, query = true)
    }
    private object QueryKey : CoroutineContext.Key<ThreadContextElement<ThreadState>>
    private val failures = CoroutineExceptionHandler { _, _ -> fault() }

    fun beginInvocation(token: Any) {
        work.requireWatchedRoom(token)
        synchronized(gate) {
            check(!uncertain.get() && invocation == null && tickets.isEmpty() && entries.isEmpty())
            check(!stopping && !stopped) { "Original Room lifecycle is closing" }
            check(jobs.keys.all { it.isCompleted })
            jobs.clear(); backgroundRoots.clear()
            invocation = token; foreground = null; fenced = false; quiet = false
        }
    }

    /** LAZY owned launch is registered before start(), not inferred later from a caller Job. */
    fun bindForeground(token: Any, actual: Job, disposal: Boolean = false) {
        synchronized(gate) {
            check(invocation === token && !uncertain.get())
            if (disposal) {
                check(quiet && tickets.isEmpty() && entries.isEmpty() && jobs.keys.all { it.isCompleted })
                jobs.clear(); backgroundRoots.clear(); quiet = false // No new Room queries in disposal.
            } else check(foreground == null)
            foreground = Actor(token, actual, Kind.FOREGROUND).also { jobs[actual] = it }
        }
        actual.invokeOnCompletion { pulse() }
    }

    fun queryContext(): CoroutineContext {
        val control = requireControl()
        work.requireActive()
        synchronized(gate) {
            check(control.actor === foreground && !builderStarted && !stopping && !fenced)
            check(parent.children.none())
            builderStarted = true
        }
        // Deliberately NO invocation element: Room scope launches are not financial work.
        return parent + dispatcher + queryElement + failures
    }

    fun afterActualBuilder() {
        requireControl(); work.requireActive()
        synchronized(gate) {
            check(builderStarted && !builderReturned && !stopping)
            val actual = parent.children.toList().single()
            check(roomSupervisor == null || roomSupervisor === actual)
            roomSupervisor = actual; builderReturned = true
        }
    }

    /** Called by the private invocation element as well as the native query element. These
     * callbacks MUST NOT throw into coroutines' thread-context machinery. */
    fun enter(context: CoroutineContext, query: Boolean): ThreadState {
        val local = if (query) queryThread else controlThread
        val previous = local.get()
        var entered: Entry? = null
        try {
            synchronized(gate) {
                check(context[ContinuationInterceptor] === dispatcher)
                val actor = actorLocked(context)
                val actual = checkNotNull(context[Job])
                check(actual !== NonCancellable && actual !== parent && actual !== roomSupervisor)
                entered = Entry(actor, actual, query).also { entries += it }
            }
            local.set(entered)
        } catch (_: Throwable) { local.set(null); fault() }
        return ThreadState(previous, entered)
    }

    fun leave(state: ThreadState, query: Boolean) {
        try {
            (if (query) queryThread else controlThread).set(state.previous)
            synchronized(gate) { state.entered?.let { check(entries.remove(it)) } }
            // The encompassing dispatch ticket signals after its complete restore tail. An
            // undispatched entry without such a ticket must supply its own completion signal.
            if (dispatchThread.get() == null) pulse()
        } catch (_: Throwable) { fault() }
    }

    private fun actorLocked(context: CoroutineContext): Actor {
        check(!uncertain.get() && invocation != null && jobs.size < MAX_RETAINED && tickets.size < MAX_RETAINED)
        val actual = checkNotNull(context[Job])
        check(actual !== NonCancellable && actual !== parent && actual !== roomSupervisor)
        val caller = work.roomToken(context)
        if (context[QueryKey] != null) check(!stopped)
        val origin = queryThread.get() ?: controlThread.get()
        val known = jobs[actual]
        if (caller != null) {
            val actor = checkNotNull(foreground)
            check(caller === invocation && actor.invocation === caller && (known == null || known === actor))
            if (context[QueryKey] != null && fenced && known == null)
                check(origin != null && origin in entries && origin.query && origin.actor === actor)
            jobs[actual] = actor
            return actor
        }
        check(context[QueryKey] === queryElement)
        if (known != null) { check(known.kind == Kind.BACKGROUND); return known }
        val supervisor = roomSupervisor ?: parent.children.toList().single().also {
            check(builderStarted && !builderReturned); roomSupervisor = it
        }
        val root = supervisor.children.firstOrNull { contains(it, actual) }
        val actor = if (root != null) {
            backgroundRoots[root] ?: run {
                // Even while admitting a new loan, an old/external refresh cannot silently
                // acquire that loan's fresh watchdog. A NEW scope root must be scheduled by
                // an actual retained context of THIS invocation, before enqueue.
                check(origin != null && origin in entries && origin.actor.invocation === invocation)
                Actor(checkNotNull(invocation), root, Kind.BACKGROUND).also { backgroundRoots[root] = it; jobs[root] = it }
            }
        } else {
            // Actual withContext(NonCancellable) entry: only a STILL LIVE synchronous query
            // context can adopt its detached scoped Job. Later dispatches use the retained Job.
            check(origin != null && origin in entries && origin.query && origin.actor.kind == Kind.BACKGROUND &&
                origin.actor.invocation === invocation && !origin.actor.root.isCompleted)
            origin.actor
        }
        jobs[actual] = actor
        return actor
    }

    private fun contains(root: Job, selected: Job): Boolean {
        val pending = ArrayDeque<Job>(); pending.add(root)
        var seen = 0
        while (pending.isNotEmpty()) {
            check(++seen <= MAX_RETAINED)
            val job = pending.removeFirst()
            if (job === selected) return true
            job.children.forEach(pending::addLast)
        }
        return false
    }

    private fun captureRootsLocked() {
        val supervisor = roomSupervisor
        if (supervisor != null) {
            check(parent.children.all { it === supervisor } || stopping)
            for (root in supervisor.children) {
                val actor = checkNotNull(backgroundRoots[root]) { "Room child was not admitted by actual owned dispatch" }
                jobs[root] = actor
            }
        } else check(!builderReturned)
    }

    /** Called OUTSIDE the actual outer query context; retains this exact original deadline. */
    suspend fun drainBeforeNative() {
        val current = requireControl()
        check(currentCoroutineContext()[Job] === current.actor.root && queryThread.get() == null)
        synchronized(gate) { captureRootsLocked(); fenced = true; quiet = false }
        drain(current.actor.invocation, insideControl = true, finalStop = false)
    }

    fun <T> nativeSql(block: () -> T): T {
        val current = requireQuietControl()
        work.requireActive()
        val old = nativeThread.get()
        check(old == null)
        nativeThread.set(Actor(current.actor.invocation, current.actor.root, Kind.NATIVE))
        try { return block() } finally { nativeThread.set(old) }
    }

    fun resumeQueriesAfterNative() {
        requireQuietControl(); work.requireActive()
        synchronized(gate) { check(!stopping); fenced = false; quiet = false }
    }

    suspend fun finishInvocation(token: Any) {
        synchronized(gate) {
            check(invocation === token && foreground?.root?.isCompleted == true)
            captureRootsLocked(); fenced = true; quiet = false
        }
        drain(token, insideControl = false, finalStop = false)
    }

    /** Sole release edge; called before work disarms or admits another invocation. */
    fun releaseInvocation(token: Any) {
        synchronized(gate) {
            check(invocation === token && quiet && !uncertain.get() && tickets.isEmpty() && entries.isEmpty())
            check(jobs.keys.all { it.isCompleted } && roomSupervisor?.children?.none() != false)
            invocation = null; foreground = null; jobs.clear(); backgroundRoots.clear()
        }
    }

    /** BEFORE RoomDatabase.close(), whose own close implementation cancels WITHOUT joining. */
    suspend fun prepareActualRoomClose() {
        val current = requireControl()
        check(currentCoroutineContext()[Job] === current.actor.root && queryThread.get() == null)
        synchronized(gate) { captureRootsLocked(); fenced = true; quiet = false; stopping = true }
        parent.cancel()
        drain(current.actor.invocation, insideControl = true, finalStop = true)
    }

    /** Final owner cleanup has no foreground invocation and uses its separately armed close
     * segment. The Room parent was retained even when build() threw before returning a DB. */
    suspend fun closeLifetime(token: Any) {
        work.requireWatchedRoom(token)
        synchronized(gate) { check(invocation == null); fenced = true; quiet = false; stopping = true }
        parent.cancel()
        drain(token, insideControl = false, finalStop = true)
    }

    fun <T> nativeClose(token: Any, block: () -> T): T {
        work.requireWatchedRoom(token)
        synchronized(gate) {
            check(invocation == null && stopped && quiet && !uncertain.get() &&
                parent.isCompleted && tickets.isEmpty() && entries.isEmpty() && jobs.keys.all { it.isCompleted })
        }
        val old = closeThread.get(); check(old == null); closeThread.set(token)
        try { return block() } finally { closeThread.set(old) }
    }

    fun requireActualClose() {
        val close = closeThread.get()
        if (close != null) {
            work.requireWatchedRoom(close)
            synchronized(gate) { check(stopped && parent.isCompleted && quiet && !uncertain.get()) }
        } else {
            val current = requireQuietControl()
            work.requireWatchedRoom(current.actor.invocation)
            synchronized(gate) { check(stopped && parent.isCompleted) }
        }
    }

    fun sqlActor(): Actor {
        val native = nativeThread.get()
        if (native != null) { work.requireWatchedRoom(native.invocation); return native }
        val current = checkNotNull(queryThread.get()) { "SQL outside the original Room query context" }
        synchronized(gate) {
            check(!uncertain.get() && current in entries && current.actor.invocation === invocation && !quiet)
        }
        work.requireWatchedRoom(current.actor.invocation)
        return current.actor
    }

    fun requireSameActor(actual: Actor, expected: Actor) {
        check(actual === expected || (actual.kind == Kind.NATIVE && expected.kind == Kind.NATIVE &&
            actual.invocation === expected.invocation && actual.root === expected.root))
    }

    /** Room evicts its real cached statements BEFORE closing the connection wrapper. That
     * synchronous close edge has a drained control context, not a query coroutine context. */
    fun requireStatementClose(previous: Actor?) {
        if (queryThread.get() == null && nativeThread.get() == null) requireActualClose()
        else {
            val actual = sqlActor()
            if (previous != null) requireSameActor(actual, previous)
        }
    }

    fun requireLifetimeRelease() {
        synchronized(gate) {
            check(invocation == null && stopped && parent.isCompleted && quiet && !uncertain.get())
            check(tickets.isEmpty() && entries.isEmpty() && jobs.keys.all { it.isCompleted })
        }
    }

    fun currentNativeInvocation(): Any = requireQuietControl().actor.invocation

    private fun requireControl(): Entry {
        val current = checkNotNull(controlThread.get()) { "Original owned Room invocation required" }
        synchronized(gate) { check(!uncertain.get() && current in entries && current.actor === foreground) }
        work.requireWatchedRoom(current.actor.invocation)
        return current
    }

    private fun requireQuietControl(): Entry {
        val current = requireControl()
        synchronized(gate) {
            check(fenced && quiet && queryThread.get() == null)
            check(tickets.all { it === dispatchThread.get() } && entries.all { it === current })
            check(jobs.keys.all { it === current.actor.root || it.isCompleted })
        }
        return current
    }

    private suspend fun drain(token: Any, insideControl: Boolean, finalStop: Boolean) {
        while (true) {
            work.requireWatchedRoom(token)
            val current = if (insideControl) requireControl() else null
            var waitJob: Job? = null
            val waiter: CompletableDeferred<Unit>
            val done: Boolean
            synchronized(gate) {
                check(!uncertain.get() && (invocation === token || (finalStop && invocation == null)))
                // An unregistered scope child appearing after the fence is NOT adopted by a
                // healthy flag. Actual dispatch from retained work must enroll it first.
                if (!finalStop) captureRootsLocked()
                val retained = jobs.keys.toMutableList()
                if (finalStop) retained += parent
                waitJob = retained.firstOrNull { !it.isCompleted && it !== current?.actor?.root }
                val ownTicket = if (insideControl) dispatchThread.get() else null
                val idle = tickets.all { it === ownTicket } && entries.all { it === current }
                done = waitJob == null && idle
                if (done) { quiet = true; if (finalStop) stopped = true }
                waiter = changed
            }
            if (done) return
            val pending = waitJob
            if (pending != null) pending.join() else waiter.await()
        }
    }

    fun fault() {
        if (uncertain.compareAndSet(false, true)) {
            // Denial/exclusion only, never proof of death and never a lease-release path.
            work.nativeClosureUnknown()
            parent.cancel()
            pulse()
        }
    }

    private fun pulse() {
        val previous = synchronized(gate) { changed.also { changed = CompletableDeferred() } }
        previous.complete(Unit)
    }

    private companion object { const val MAX_RETAINED = 4096 }
}

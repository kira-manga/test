package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterPhysicalRpcClientV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.rpc.LocalRpcRemoteDirectoryClient
import com.ukkera.pos.sales.store.DistributedPhysicalClientJournalOwner
import com.ukkera.pos.sales.store.DistributedPhysicalClientNativeAcquisition
import com.ukkera.pos.sales.store.DistributedPhysicalClientOperation
import com.ukkera.pos.sales.store.DistributedPhysicalClientResolution
import com.ukkera.pos.sales.store.DistributedPhysicalRawRecoveryAcquisition
import com.ukkera.pos.sales.store.DistributedPhysicalNativeAction
import com.ukkera.pos.sales.store.LocalPhysicalExternalCustody
import com.ukkera.pos.sales.store.LocalPhysicalSpoolClose
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Root-owned physical children of the exact successfully admitted paired actor source. Every
 * native acquisition and reconnect close is retained before IO, independently of a login/UI
 * waiter. Completed known closes can be retired; an unknown acquisition/close keeps its slot
 * and actual parent loan. There is no age-based eviction or retry with a new enrollment.
 */
internal class PairedDeviceRemotePhysicalOwners(
    private val source: PairedDeviceActorIntentSource,
    private val parentCurrent: () -> Boolean,
    /** Concrete Android/desktop native factory, supplied only by the credential root. */
    private val acquireNative: (LocalMasterPhysicalRpcClientV1, LocalClientIntentJournal,
        DomainId, LocalPhysicalExternalCustody, DistributedPhysicalNativeAction) -> DistributedPhysicalClientNativeAcquisition?,
    private val acquireRecovery: (LocalMasterPhysicalRpcClientV1, PairedPhysicalOriginalSelection,
        LocalPhysicalExternalCustody) -> DistributedPhysicalRawRecoveryAcquisition?,
    private val acquireResolution: (LocalMasterPhysicalRpcClientV1, PairedPhysicalOriginalSelection,
        LocalPhysicalExternalCustody) -> DistributedPhysicalClientNativeAcquisition?,
) : LocalRemotePhysicalActors {
    private class ActorSlot(val journal: LocalClientIntentJournal, val journalId: DomainId,
        val disposition: LocalPairedDeviceCredentialJournalAction) {
        @Volatile var selected: Attempt? = null
        @Volatile var opened = false
    }
    private class Attempt(val binding: LocalRemoteRetailPhysicalBinding,
        @Volatile var previous: Attempt?) {
        lateinit var work: Deferred<Actor?>
        @Volatile var acquisition: DistributedPhysicalClientNativeAcquisition? = null
        @Volatile var actor: Actor? = null
        @Volatile var recovery: Job? = null
        @Volatile var failureBeforeAcquisition = false
        @Volatile var closed: LocalPhysicalSpoolClose? = null
    }
    private class RawAttempt(val binding: LocalRemoteRetailPhysicalBinding,
        val selection: PairedPhysicalOriginalSelection, val qualification: LocalPhysicalExternalCustody) {
        @Volatile var acquisition: DistributedPhysicalRawRecoveryAcquisition? = null
        @Volatile var closed: LocalPhysicalSpoolClose? = null
    }
    private class ResolverAttempt(val binding: LocalRemoteRetailPhysicalBinding,
        val selection: PairedPhysicalOriginalSelection) {
        lateinit var work: Deferred<DistributedPhysicalClientJournalOwner?>
        @Volatile var acquisition: DistributedPhysicalClientNativeAcquisition? = null
        @Volatile var native: DistributedPhysicalClientJournalOwner? = null
        @Volatile var factoryEntered = false
        @Volatile var closed: LocalPhysicalSpoolClose? = null
    }
    private class Actor(
        private val parent: PairedDeviceRemotePhysicalOwners,
        private val selected: Attempt,
        override val journal: LocalClientIntentJournal,
        private val journalId: DomainId,
        override val native: DistributedPhysicalClientJournalOwner?,
    ) : LocalRemoteRetailPhysicalActor {
        override fun isCurrent(): Boolean = selected.closed == null && parent.isCurrent(selected.binding) &&
            selected.actor === this && parent.source.physicalJournalId(journal) == journalId &&
            (native == null || native.actorIntentJournalId == journalId)

        override suspend fun begin(request: MutationRequest): DistributedPhysicalClientOperation? {
            currentCoroutineContext().ensureActive()
            val owner = native ?: return null
            if (!isCurrent() || !parent.source.recheckPhysical(journal) || !isCurrent()) return null
            val loan = parent.source.capturePhysical(journal) ?: return null
            // begin owns this counted actual loan immediately, including its cancellation path.
            return owner.begin(request, loan)
        }

        override suspend fun beginResolution(request: MutationRequest): DistributedPhysicalClientResolution? {
            currentCoroutineContext().ensureActive()
            if (!isCurrent() || !parent.source.recheckPhysical(journal) || !isCurrent()) return null
            val original = parent.resolutionOwner(selected.binding, request, journal.owner.actorId) ?: return null
            if (!isCurrent() || !parent.source.recheckPhysical(journal) || !isCurrent()) return null
            val resolverLoan = parent.source.capturePhysical(journal) ?: return null
            // The original catalogue and the authenticated resolver journal remain distinct.
            return original.beginLinkedResolution(request, journal.owner.actorId, resolverLoan)
        }
    }

    private val denied = AtomicBoolean(false)
    private val lifecycle = SupervisorJob()
    private val owned = CoroutineScope(lifecycle + Dispatchers.IO)
    private val admission = Mutex()
    private val stateGate = Any()
    private val closure = AtomicReference<CompletableDeferred<LocalPhysicalSpoolClose>?>(null)
    private var binding: LocalRemoteRetailPhysicalBinding? = null
    private val slots = mutableListOf<ActorSlot>()
    private val rawAttempts = mutableListOf<RawAttempt>()
    private val resolverAttempts = mutableListOf<ResolverAttempt>()
    private val recovered = mutableMapOf<DomainId, LocalMasterPhysicalRpcClientV1>()
    // Retained before the first binding can start device-only recovery work.
    private val originalRecovery = owned.launch(start = CoroutineStart.LAZY) {
        while (current()) {
            val selected = synchronized(stateGate) { binding }
            if (selected != null && isCurrent(selected)) {
                try { recoverExistingOriginals(selected) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Exact native attempts remain in the root census. */ }
            }
            delay(RECOVERY_POLL_MILLIS)
        }
    }

    suspend fun binding(endpoint: LocalRpcRemoteDirectoryClient): LocalRemoteRetailPhysicalBinding? {
        currentCoroutineContext().ensureActive()
        if (!current() || !endpoint.isCurrent()) return null
        val rpc = endpoint.physicalCompanion() ?: return null
        if (!current() || !endpoint.isCurrent() || rpc.connectionId != endpoint.connectionId ||
            !rpc.hasOriginal(source.physicalOriginal.sdkOriginal, source.physicalOriginal.approvedOriginal)) return null
        return admission.withLock {
            if (!current() || !endpoint.isCurrent() || !rpc.isCurrent()) return@withLock null
            synchronized(stateGate) {
                val previous = binding
                if (previous?.endpoint === endpoint && previous.isCurrent()) return@synchronized previous
                previous?.let { old -> slots.mapNotNull { it.selected }.filter { it.binding === old }
                    .forEach { it.actor?.native?.denyNewWork() } }
                LocalRemoteRetailPhysicalBinding(endpoint, endpoint.connectionId,
                    LocalClientDeviceIdentity(rpc.scope, rpc.originalDeviceId), rpc, this).also {
                        binding = it
                        originalRecovery.start()
                    }
            }
        }
    }

    private fun current(): Boolean = !denied.get() && closure.get() == null && parentCurrent()

    override fun isCurrent(binding: LocalRemoteRetailPhysicalBinding): Boolean =
        current() && synchronized(stateGate) { this.binding === binding } &&
            (binding.endpoint as? LocalRpcRemoteDirectoryClient)?.isCurrent() == true && binding.rpc.isCurrent()

    override suspend fun acquire(binding: LocalRemoteRetailPhysicalBinding,
        journal: LocalClientIntentJournal): LocalRemoteRetailPhysicalActor? {
        currentCoroutineContext().ensureActive()
        val work = admission.withLock {
            if (!isCurrent(binding)) return@withLock null
            val id = source.physicalJournalId(journal) ?: return@withLock null
            val disposition = source.physicalDisposition(journal) ?: return@withLock null
            val resolver = synchronized(stateGate) { resolverAttempts.singleOrNull { it.selection.journalId == id } }
            if (resolver != null) {
                if (!resolver.work.isCompleted || closeResolver(resolver) != LocalPhysicalSpoolClose.CLOSED) return@withLock null
                synchronized(stateGate) { check(resolverAttempts.remove(resolver)) }
            }
            val slot = synchronized(stateGate) {
                // A genuine raw-recovery FD with unknown close still owns this catalogue.
                if (rawAttempts.any { it.selection.journalId == id }) return@synchronized null
                slots.singleOrNull { it.journal === journal } ?: run {
                    if (slots.size >= MAX_ACTORS || slots.any { it.journalId == id || it.journal.owner == journal.owner })
                        return@synchronized null
                    ActorSlot(journal, id, disposition).also { slots.add(it) }
                }
            } ?: return@withLock null
            val previous = slot.selected
            if (previous != null && previous.binding === binding && previous.closed == null) return@withLock previous.work
            // The close is part of the independently supervised acquisition below. Retain the
            // replacement before awaiting, and never reopen after an unknown previous close.
            // A canceled waiter cannot turn an in-flight acquisition into another native
            // enrollment. Wait for its retained worker to settle before admitting a successor.
            if (previous != null && (!previous.work.isCompleted || previous.closed == LocalPhysicalSpoolClose.HELD))
                return@withLock null
            val attempt = Attempt(binding, previous)
            synchronized(stateGate) { slot.selected = attempt }
            attempt.work = owned.async(start = CoroutineStart.LAZY) { acquireOwned(slot, attempt, previous) }
            attempt.work.start()
            attempt.work
        } ?: return null
        val result = work.await()
        currentCoroutineContext().ensureActive()
        return result?.takeIf { isCurrent(binding) && it.isCurrent() }
    }

    /** Select the actual recording catalogue on this original Client. Current resolver
     * identity never chooses a replacement namespace; absent/foreign/unknown stays denied. */
    private suspend fun resolutionOwner(binding: LocalRemoteRetailPhysicalBinding, request: MutationRequest,
        resolverActorId: DomainId): DistributedPhysicalClientJournalOwner? {
        val hot = synchronized(stateGate) { slots.mapNotNull { it.selected }.filter {
            it.binding === binding && it.closed == null && it.work.isCompleted }.mapNotNull { it.actor?.native } }
        for (native in hot) {
            if (!isCurrent(binding)) return null
            if (native.supportsLinkedResolution(request, resolverActorId)) return native
        }
        val selections = source.physicalResolutionSelections() ?: return null
        check(selections.size <= MAX_PAIRED_ACTOR_ALLOCATIONS && selections.map { it.journalId }.distinct().size == selections.size)
        for (selection in selections) {
            if (!isCurrent(binding) || !source.recheckPhysicalRecovery(selection)) return null
            val previousActor = synchronized(stateGate) { slots.singleOrNull { it.journalId == selection.journalId }?.selected }
            if (previousActor != null && previousActor.closed == null) {
                if (previousActor.binding === binding) continue // The genuine hot owner was checked above.
                if (!previousActor.work.isCompleted || closeAttempt(previousActor) != LocalPhysicalSpoolClose.CLOSED) return null
            }
            val pending = admission.withLock {
                if (!isCurrent(binding) || synchronized(stateGate) { rawAttempts.any { it.selection.journalId == selection.journalId } })
                    return@withLock null
                val previous = synchronized(stateGate) { resolverAttempts.singleOrNull { it.selection.journalId == selection.journalId } }
                if (previous != null) {
                    if (previous.binding === binding && previous.closed == null) return@withLock previous
                    if (!previous.work.isCompleted || closeResolver(previous) != LocalPhysicalSpoolClose.CLOSED) return@withLock null
                    synchronized(stateGate) { check(resolverAttempts.remove(previous)) }
                }
                if (synchronized(stateGate) { resolverAttempts.size >= MAX_ACTORS }) return@withLock null
                ResolverAttempt(binding, selection).also { attempt ->
                    synchronized(stateGate) { resolverAttempts.add(attempt) } // Retain before Current/native IO.
                    attempt.work = owned.async(start = CoroutineStart.LAZY) {
                        if (!isCurrent(binding) || !source.recheckPhysicalRecovery(selection)) return@async null
                        val loan = source.capturePhysicalRecovery(selection) ?: return@async null
                        attempt.factoryEntered = true
                        val acquisition = acquireResolution(binding.rpc, selection, loan) ?: return@async null
                        attempt.acquisition = acquisition
                        acquisition.await()?.also { attempt.native = it }
                            ?.takeIf { isCurrent(binding) && source.recheckPhysicalRecovery(selection) }
                    }
                    attempt.work.start()
                }
            } ?: return null
            val native = pending.work.await()
            if (native == null) {
                // An unrelated genuine allocation may have no physical catalogue. Continue
                // only after its concrete acquisition proves certain native/loan closure.
                if (closeResolver(pending) != LocalPhysicalSpoolClose.CLOSED) return null
                synchronized(stateGate) { check(resolverAttempts.remove(pending)) }
                continue
            }
            if (!isCurrent(binding)) return null
            if (native.supportsLinkedResolution(request, resolverActorId)) return native
            // A fully known nonmatching catalogue can close; unknown ownership cannot.
            if (closeResolver(pending) != LocalPhysicalSpoolClose.CLOSED) return null
            synchronized(stateGate) { check(resolverAttempts.remove(pending)) }
        }
        return null
    }

    private suspend fun closeResolver(attempt: ResolverAttempt): LocalPhysicalSpoolClose = withContext(NonCancellable) {
        attempt.closed?.let { return@withContext it }
        attempt.work.start(); attempt.work.join()
        (attempt.acquisition?.close() ?: if (attempt.factoryEntered) LocalPhysicalSpoolClose.HELD else LocalPhysicalSpoolClose.CLOSED)
            .also { attempt.closed = it }
    }

    private suspend fun acquireOwned(slot: ActorSlot, attempt: Attempt, previous: Attempt?): Actor? {
        try {
            if (previous != null) {
                previous.work.join()
                if (closeAttempt(previous) != LocalPhysicalSpoolClose.CLOSED) return null
                attempt.previous = null // Actual known close is the only retirement proof.
            }
            if (!isCurrent(attempt.binding) || !source.recheckPhysical(slot.journal) || !isCurrent(attempt.binding)) return null
            val loan = source.capturePhysical(slot.journal) ?: return null
            var transferred = false
            val acquired = try {
                val action = if (slot.opened || slot.disposition != LocalPairedDeviceCredentialJournalAction.ENROLL)
                    DistributedPhysicalNativeAction.REOPEN_EXISTING else DistributedPhysicalNativeAction.ENROLL_ORIGINAL
                // The concrete native factory owns/retires the supplied qualification loan,
                // even when it returns null or throws before returning an acquisition.
                transferred = true
                attempt.failureBeforeAcquisition = true
                acquireNative(attempt.binding.rpc, slot.journal, slot.journalId, loan, action)
            } finally {
                if (!transferred) { loan.deny(); check(loan.release()) }
            }
            if (acquired == null) { attempt.failureBeforeAcquisition = true; return null }
            attempt.acquisition = acquired
            attempt.failureBeforeAcquisition = false
            val native = acquired.await()
            if (native == null && acquired.close() != LocalPhysicalSpoolClose.CLOSED) {
                attempt.closed = LocalPhysicalSpoolClose.HELD
                return null
            }
            // A known closed unavailable own catalogue cannot grant Start. It also cannot
            // invent a requirement that a supervisor own a second physical namespace before
            // resolving an old original in the actual recorded actor's existing catalogue.
            val actor = Actor(this, attempt, slot.journal, slot.journalId, native)
            attempt.actor = actor
            if (native != null) {
                slot.opened = true // A later transport generation must use existing-only reopening.
                attempt.recovery = owned.launch(start = CoroutineStart.LAZY) {
                    while (current() && attempt.closed == null) {
                        // Device-only immutable evidence route. No employee credentials are read.
                        // A lost ACK may repeat exact bytes; it never allocates or rearms an action.
                        // Keep the actual existing native owner available after a transport
                        // successor is selected, including while no employee is signed in. The
                        // native method independently requires the SAME complete SDK original.
                        val selected = synchronized(stateGate) { binding }
                        if (selected != null && isCurrent(selected)) {
                            try { native.redeliverOriginals(selected.rpc) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Native custody remains retained; no success is asserted. */ }
                        }
                        delay(RECOVERY_POLL_MILLIS)
                    }
                }.also { it.start() }
            }
            if (!actor.isCurrent() || !source.recheckPhysical(slot.journal) || !actor.isCurrent()) {
                native?.denyNewWork(); return null
            }
            return actor
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
    }

    /** Before employee login, enumerate only genuine existing device allocations. Recovery
     * cannot enroll a missing catalogue, open an actor intent owner, or return a fresh owner. */
    private suspend fun recoverExistingOriginals(binding: LocalRemoteRetailPhysicalBinding) {
        val selections = source.physicalRecoverySelections() ?: return
        check(selections.size <= MAX_PAIRED_ACTOR_ALLOCATIONS &&
            selections.map { it.journalId }.distinct().size == selections.size)
        for (selection in selections) {
            if (!isCurrent(binding)) return
            if (!source.recheckPhysicalRecovery(selection) || !isCurrent(binding)) continue
            val attempt = admission.withLock {
                if (!isCurrent(binding) || synchronized(stateGate) {
                    slots.any { it.journalId == selection.journalId } ||
                        resolverAttempts.any { it.selection.journalId == selection.journalId } ||
                        rawAttempts.any { it.selection.journalId == selection.journalId } ||
                        rawAttempts.size >= MAX_ACTORS || recovered[selection.journalId] === binding.rpc
                }) return@withLock null
                val qualification = source.capturePhysicalRecovery(selection) ?: return@withLock null
                RawAttempt(binding, selection, qualification).also { selected ->
                    synchronized(stateGate) { rawAttempts.add(selected) }
                }
            } ?: continue
            // This retained census entry excludes an interactive open of the same catalogue.
            // Native IO and network delivery do not hold the root's actor-admission mutex.
            var factoryEntered = false
            var acknowledged = false
            try {
                if (!source.recheckPhysicalRecovery(selection) || !isCurrent(binding)) continue
                // The concrete factory wraps this actual allocation loan before native IO.
                // After entry, a null/throw remains unknown custody in this root census.
                factoryEntered = true
                val acquisition = acquireRecovery(binding.rpc, selection, attempt.qualification) ?: continue
                attempt.acquisition = acquisition
                if (!source.recheckPhysicalRecovery(selection) || !isCurrent(binding)) continue
                val result = acquisition.redeliver()
                acknowledged = result != null && result.values.all {
                    it == com.ukkera.pos.database.LocalMasterPhysicalOriginalRetentionV1.MASTER_ORIGINAL_RETAINED
                }
            } finally {
                withContext(NonCancellable) {
                    val closed = if (factoryEntered) attempt.acquisition?.close() ?: LocalPhysicalSpoolClose.HELD
                    else try {
                        // No native factory was entered. Only this actual counted loan exists.
                        attempt.qualification.deny()
                        if (attempt.qualification.release()) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
                    } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
                    attempt.closed = closed
                    if (closed == LocalPhysicalSpoolClose.CLOSED) synchronized(stateGate) {
                        check(rawAttempts.remove(attempt))
                        if (acknowledged && isCurrent(binding)) recovered[selection.journalId] = binding.rpc
                    }
                }
            }
        }
    }

    private suspend fun closeAttempt(attempt: Attempt): LocalPhysicalSpoolClose {
        attempt.closed?.let { return it }
        attempt.actor?.native?.denyNewWork()
        attempt.recovery?.cancel()
        attempt.recovery?.join()
        // Called only after previous.work completed, or from root close after joining it.
        val previous = attempt.previous
        if (previous != null) {
            previous.work.join()
            if (closeAttempt(previous) != LocalPhysicalSpoolClose.CLOSED) {
                attempt.closed = LocalPhysicalSpoolClose.HELD
                return LocalPhysicalSpoolClose.HELD
            }
            attempt.previous = null
        }
        val result = attempt.acquisition?.close() ?: if (attempt.failureBeforeAcquisition)
            LocalPhysicalSpoolClose.HELD else LocalPhysicalSpoolClose.CLOSED
        attempt.closed = result
        return result
    }

    fun denyNewWork() {
        denied.set(true)
        synchronized(stateGate) { slots.mapNotNull { it.selected }.forEach { it.actor?.native?.denyNewWork() } }
        synchronized(stateGate) { resolverAttempts.forEach { it.native?.denyNewWork() } }
    }

    /** Close genuine physical children BEFORE the actor source waits for its counted loans. */
    suspend fun close(): LocalPhysicalSpoolClose = withContext(NonCancellable) {
        denyNewWork()
        val result = CompletableDeferred<LocalPhysicalSpoolClose>()
        if (!closure.compareAndSet(null, result)) return@withContext checkNotNull(closure.get()).await()
        try {
            originalRecovery.cancel()
            originalRecovery.join() // Its finally joins/closes every accepted native acquisition.
            val selected = admission.withLock { synchronized(stateGate) { slots.mapNotNull { it.selected } } }
            selected.forEach { it.work.start(); it.work.join() }
            var closed = true
            for (attempt in selected) if (closeAttempt(attempt) != LocalPhysicalSpoolClose.CLOSED) closed = false
            val resolvers = synchronized(stateGate) { resolverAttempts.toList() }
            for (attempt in resolvers) if (closeResolver(attempt) != LocalPhysicalSpoolClose.CLOSED) closed = false
            val raw = synchronized(stateGate) { rawAttempts.toList() }
            for (attempt in raw) {
                val actual = attempt.closed ?: attempt.acquisition?.close() ?: LocalPhysicalSpoolClose.HELD
                attempt.closed = actual
                if (actual != LocalPhysicalSpoolClose.CLOSED) closed = false
            }
            if (closed) lifecycle.cancel()
            result.complete(if (closed) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD)
        } catch (_: Exception) { result.complete(LocalPhysicalSpoolClose.HELD) }
        result.await()
    }

    override fun toString(): String = "PairedDeviceRemotePhysicalOwners([REDACTED])"

    private companion object {
        const val MAX_ACTORS = 128
        const val RECOVERY_POLL_MILLIS = 2_000L
    }
}

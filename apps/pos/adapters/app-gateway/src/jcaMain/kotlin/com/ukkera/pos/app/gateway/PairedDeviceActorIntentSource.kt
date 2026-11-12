package com.ukkera.pos.app.gateway

import com.ukkera.pos.rpc.LocalOriginalRpcAuthenticationSource
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.security.OpaqueCredential
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Root-retained source for one genuine device ledger and the final actual selected-original
 * RPC owner. The common sign-in handshake supplies its freshly owned bearer only AFTER fresh
 * Current; this source independently invokes actual Current again. Stored RETAINED tokens are
 * never read here. current(owner) ONLY selects a successfully admitted, already-open actor.
 * Actor disposition is immutable from the explicit source selection, separate from the
 * device custody disposition. Neither selection is inferred from absent actor files. */
internal class PairedDeviceActorIntentSource(
    private val rootOwner: Any,
    private val parentCurrent: () -> Boolean,
    private val ledger: PairedDeviceActorAllocationJournal,
    private val rpc: LocalOriginalRpcAuthenticationSource,
    private val actorIntentAction: LocalPairedDeviceCredentialJournalAction,
    /** The platform's actual actor journal acquisition (Android files/KeyStore, desktop files/OS keys). */
    private val openActor: suspend (PairedDeviceActorJournalCustody) -> ClientJournalOutcome<PairedClientJournalOwner>,
) : LocalAuthenticatedClientIntentJournalSource {
    private class HeldActor(val custody: PairedDeviceActorJournalCustody,
        val owner: PairedClientJournalOwner)
    private class Acquired(val held: HeldActor, val newlyOpened: Boolean)
    private class Acquisition(val expected: LocalClientIdentity, val credentials: LocalGatewayCredentials) {
        lateinit var pending: Deferred<Acquired?>
    }

    private val gate = Any()
    private val denied = AtomicBoolean(false)
    private val ownedJob = SupervisorJob()
    private val owned = CoroutineScope(ownedJob + Dispatchers.IO)
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)
    private var active: Acquisition? = null
    private val retainedActors = mutableListOf<HeldActor>()
    private val published = mutableMapOf<LocalClientIdentity, HeldActor>()

    fun belongsTo(rpc: LocalOriginalRpcAuthenticationSource,
        actorIntentAction: LocalPairedDeviceCredentialJournalAction): Boolean =
        this.rpc === rpc && this.actorIntentAction == actorIntentAction
    private fun isCurrent(): Boolean = !denied.get() && parentCurrent() && ledger.isCurrent()

    override suspend fun current(owner: LocalClientIdentity): LocalClientIntentJournal? {
        currentCoroutineContext().ensureActive()
        return synchronized(gate) {
            if (!isCurrent()) null else published[owner]?.let { retained ->
                retained.owner.intentJournal?.takeIf { it.owner == owner && retained.owner.isCurrent() }
            }
        }
    }

    override suspend fun acquireAuthenticated(owner: LocalClientIdentity,
        credentials: LocalGatewayCredentials): LocalClientIntentJournal? {
        currentCoroutineContext().ensureActive()
        val acquisition = synchronized(gate) {
            // No mutable input is copied before winning the one bounded owned slot. There
            // is no waiter queue or automatic native/authentication retry after saturation.
            if (!isCurrent() || active != null) return null
            val snapshot = try { LocalGatewayCredentials(credentials.connectionId,
                credentials.bearer.useBytes(::OpaqueCredential)) } catch (_: Exception) { return null }
            Acquisition(owner, snapshot).also { selected ->
                active = selected
                try {
                    selected.pending = owned.async(start = CoroutineStart.LAZY) { acquireOwned(selected) }
                    selected.pending.start()
                } catch (failure: Throwable) {
                    snapshot.bearer.destroy()
                    active = null
                    throw failure
                }
            }
        }
        var transferred = false
        try {
            val acquired = acquisition.pending.await() ?: return null
            currentCoroutineContext().ensureActive()
            return synchronized(gate) {
                val held = acquired.held
                val journal = held.owner.intentJournal
                if (active !== acquisition || !isCurrent() || journal?.owner != owner || !held.owner.isCurrent()) return null
                val previous = published[owner]
                if (previous != null && previous !== held) return null
                published[owner] = held
                active = null
                transferred = true
                journal
            }
        } finally {
            if (!transferred) withContext(NonCancellable) {
                try {
                    // Cancellation abandons delivery, NOT accepted Current/native work.
                    // Capture and certainly close a new undelivered exact native owner.
                    val acquired = acquisition.pending.await()
                    if (acquired?.newlyOpened == true) {
                        try { acquired.held.owner.close() } finally { acquired.held.custody.close() }
                    }
                } finally {
                    acquisition.credentials.bearer.destroy()
                    synchronized(gate) { if (active === acquisition) active = null }
                }
            }
        }
    }

    private suspend fun acquireOwned(acquisition: Acquisition): Acquired? {
        try {
            val current = PairedDeviceCurrentActor.authenticate(ledger.nativeOriginal,
                rpc, acquisition.expected, acquisition.credentials) ?: return null
            if (!isCurrent() || !current.isCurrent()) return null
            val existing = synchronized(gate) { published[current.identity] }
            if (existing != null) {
                // Reuse the SAME successful live owner; this is neither a second ENROLL nor
                // an existing/unknown allocation repaired under a different disposition.
                if (!existing.owner.isCurrent() || existing.owner.intentJournal?.owner != current.identity) return null
                return Acquired(existing, newlyOpened = false)
            }
            val custody = ledger.prepareActorCustody(current, actorIntentAction) ?: return null
            var owner: PairedClientJournalOwner? = null
            var deliveredToSource = false
            try {
                if (!isCurrent()) return null
                val acquired = openActor(custody)
                if (acquired !is ClientJournalOutcome.Value) return null
                owner = acquired.value // Retain exact success BEFORE a further suspension.
                val held = HeldActor(custody, acquired.value)
                synchronized(gate) { retainedActors.add(held) }
                if (!acquired.value.isCurrent() || !custody.finishNativeHandoff(rootOwner) ||
                    !isCurrent() || !current.isCurrent()) return null
                currentCoroutineContext().ensureActive()
                return Acquired(held, newlyOpened = true).also { deliveredToSource = true }
            } finally {
                if (!deliveredToSource) withContext(NonCancellable) {
                    try { owner?.close() } finally { custody.close() }
                }
            }
        } finally { acquisition.credentials.bearer.destroy() }
    }

    /** Memory-only withdrawal precedes any drain/native close. */
    fun denyNewWork() { denied.set(true) }

    /** The aggregate retains this source, every admitted acquisition and every actual owner.
     * Close awaits owned Current/native work, then each original actor's actual close. An
     * exceptional/unknown close remains cached; native process exclusion is never released
     * here, retried, replaced or converted to an empty allocation/intent history. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        denyNewWork()
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        var failure: Throwable? = null
        suspend fun attempt(action: suspend () -> Unit) {
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
            }
        }
        val pending = synchronized(gate) { active }
        attempt { pending?.pending?.await() }
        pending?.credentials?.bearer?.destroy()
        val retained = synchronized(gate) { retainedActors.toList() }
        for (held in retained) {
            attempt { held.owner.close() }
            attempt { held.custody.close() }
        }
        attempt { ownedJob.cancelAndJoin() }
        val problem = failure
        if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
        result.await()
    }

    override fun toString(): String = "PairedDeviceActorIntentSource([REDACTED])"
}

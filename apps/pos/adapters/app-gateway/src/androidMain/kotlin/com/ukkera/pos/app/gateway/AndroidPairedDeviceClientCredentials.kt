package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.rpc.AndroidOriginalRpcClientOwner
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

public sealed interface AndroidPairedDeviceClientCredentialsResult {
    public class Opened internal constructor(public val credentials: AndroidPairedDeviceClientCredentials) :
        AndroidPairedDeviceClientCredentialsResult
    public data object Unavailable : AndroidPairedDeviceClientCredentialsResult
}

/**
 * Root-owned device journal for the exact approved original additional device.
 * Its actual AES/key-head/file worker is acquired BEFORE the target is transferred to RPC,
 * and survives that source's denial/close. The original acquire route remains credentials-only.
 * The separate explicit acquireForActorIntents route also admits a genuine protected actor
 * allocation ledger before transfer. Financial intents are never stored in credentials or
 * allocation metadata. No scalar approved peer/cohost lease/replacement history is supplied.
 */
public class AndroidPairedDeviceClientCredentials private constructor(
    private val application: Application,
    private val custody: PairedDeviceCredentialJournalCustody,
    private val credentialsOwner: AndroidLocalClientJournalOwner,
    private val actorAllocationOwner: AndroidLocalClientJournalOwner?,
    private val actorIntentAction: LocalPairedDeviceCredentialJournalAction?,
) {
    public val credentials: LocalClientCredentialJournal = checkNotNull(credentialsOwner.credentialJournal)
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val actorGate = Any()
    private var actorSource: PairedDeviceActorIntentSource? = null
    private var remotePhysical: PairedDeviceRemotePhysicalOwners? = null

    /** Exact admitted native journal lifetime, independent of old target/RPC currentness. */
    public fun isCurrent(): Boolean = closure.get() == null && credentialsOwner.isCurrent() &&
        actorAllocationOwner?.isCurrent() != false

    /** One root-retained source for the SAME final actual RPC owner. The source's sign-in
     * handshake independently obtains fresh Master Current with the live sign-in bearer;
     * current(identity) never enrolls or reads RETAINED tokens. This cannot add actor custody
     * to a credential-only acquisition after its native source was transferred/closed. */
    public fun actorIntentSource(rpc: AndroidOriginalRpcClientOwner): LocalAuthenticatedClientIntentJournalSource? {
        if (!isCurrent()) return null
        val ledger = actorAllocationOwner?.actorAllocationJournal ?: return null
        val action = actorIntentAction ?: return null
        return synchronized(actorGate) {
            if (!isCurrent()) null else {
                val existing = actorSource
                if (existing != null) {
                    check(existing.belongsTo(rpc, action)) { "Actor source original RPC owner or disposition differs" }
                    existing
                } else PairedDeviceActorIntentSource(application, ::isCurrent, ledger, rpc, action) { custody ->
                    when (val acquired = AndroidLocalClientJournalOwner.acquirePairedActor(application, custody)) {
                        is ClientJournalOutcome.Value -> ClientJournalOutcome.Value(acquired.value.paired)
                        ClientJournalOutcome.Unavailable -> ClientJournalOutcome.Unavailable
                    }
                }.also { actorSource = it }
            }
        }
    }

    /** SAME actual selected-original RPC plus genuine successful actor-native custody. */
    public suspend fun remotePhysicalBinding(rpc: AndroidOriginalRpcClientOwner,
        endpoint: com.ukkera.pos.rpc.LocalRpcRemoteDirectoryClient): LocalRemoteRetailPhysicalBinding? {
        val source = actorIntentSource(rpc) as? PairedDeviceActorIntentSource ?: return null
        val owners = synchronized(actorGate) {
            if (!isCurrent()) null else remotePhysical ?: PairedDeviceRemotePhysicalOwners(source, ::isCurrent,
                acquireNative = { companion, journal, journalId, loan, action ->
                com.ukkera.pos.sales.store.AndroidDistributedRetailPhysicalRecording.acquireClient(
                    application, custody, companion, journal.owner.actorId, journalId, loan, action)
                }, acquireRecovery = { companion, selection, loan ->
                    com.ukkera.pos.sales.store.AndroidDistributedRetailPhysicalRecording.acquireRawOriginalRecovery(
                        application, custody, companion, selection.actorId, selection.journalId, loan)
                }, acquireResolution = { companion, selection, loan ->
                    com.ukkera.pos.sales.store.AndroidDistributedRetailPhysicalRecording.acquireExistingResolution(
                        application, custody, companion, selection.actorId, selection.journalId, loan)
                }).also { remotePhysical = it }
        } ?: return null
        return owners.binding(endpoint)
    }

    /** Retire first and await actual native journal close. Unknown close retains that
     * owner's process lease; repeat close observes the same result, never retries OS close. */
    public suspend fun close(): Unit = withContext(NonCancellable) {
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        val selected = synchronized(actorGate) {
            (actorSource?.also { it.denyNewWork() }) to (remotePhysical?.also { it.denyNewWork() })
        }
        val source = selected.first
        // A child with unknown FD/force/close custody keeps every parent anchor. Calling
        // source.close here would wait forever for the same retained counted loan.
        if (selected.second?.close() == com.ukkera.pos.sales.store.LocalPhysicalSpoolClose.HELD) {
            result.completeExceptionally(IllegalStateException("physical.client.custody_held"))
            result.await()
            return@withContext
        }
        var failure: Throwable? = null
        suspend fun attempt(action: suspend () -> Unit) {
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
            }
        }
        // Withdraw/drain accepted actor Current/native work before closing the independent
        // allocation and credential anchors. Each actual close remains owned and cached.
        attempt { source?.close() }
        attempt { actorAllocationOwner?.close() }
        attempt { credentialsOwner.close() }
        attempt { custody.close() }
        val problem = failure
        if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
        result.await()
    }

    override fun toString(): String = "AndroidPairedDeviceClientCredentials([REDACTED])"

    public companion object {
        /** The capability is retained by the root before this suspension. Source proof is
         * retained until full typed manifest/head/continuity admission and protected final
         * handoff. No automatic enroll, new capability after uncertainty or restored login. */
        public suspend fun acquire(application: Application, custody: PairedDeviceCredentialJournalCustody):
            AndroidPairedDeviceClientCredentialsResult = acquireActual(application, custody, actorIntentAction = null)

        /** Explicit supplier actor route. Both actual native journal owners are captured
         * before final source handoff. The device disposition alone controls allocation-ledger
         * creation; REOPEN/RECOVER cannot initialize a missing ledger. The separate immutable
         * actor disposition controls opening an actor after fresh actual Master Current. */
        public suspend fun acquireForActorIntents(application: Application,
            custody: PairedDeviceCredentialJournalCustody,
            actorIntentAction: LocalPairedDeviceCredentialJournalAction = custody.action): AndroidPairedDeviceClientCredentialsResult =
            acquireActual(application, custody, actorIntentAction)

        private suspend fun acquireActual(application: Application, custody: PairedDeviceCredentialJournalCustody,
            actorIntentAction: LocalPairedDeviceCredentialJournalAction?): AndroidPairedDeviceClientCredentialsResult {
            currentCoroutineContext().ensureActive()
            var owner: AndroidLocalClientJournalOwner? = null
            var actorAllocationOwner: AndroidLocalClientJournalOwner? = null
            var delivered = false
            try {
                val acquired = AndroidLocalClientJournalOwner.acquirePairedDevice(application, custody)
                if (acquired !is ClientJournalOutcome.Value) return AndroidPairedDeviceClientCredentialsResult.Unavailable
                owner = acquired.value // Capture the actual owner immediately, before more suspension.
                if (actorIntentAction != null) {
                    val allocations = AndroidLocalClientJournalOwner.acquirePairedActorAllocations(application, custody)
                    if (allocations !is ClientJournalOutcome.Value) return AndroidPairedDeviceClientCredentialsResult.Unavailable
                    actorAllocationOwner = allocations.value // Capture before final native source recheck.
                    if (!allocations.value.isCurrent() || allocations.value.actorAllocationJournal?.isCurrent() != true)
                        return AndroidPairedDeviceClientCredentialsResult.Unavailable
                }
                if (!acquired.value.isCurrent() || !custody.finishNativeHandoff(application))
                    return AndroidPairedDeviceClientCredentialsResult.Unavailable
                val credentials = AndroidPairedDeviceClientCredentials(application, custody, acquired.value,
                    actorAllocationOwner, actorIntentAction)
                currentCoroutineContext().ensureActive()
                if (!credentials.isCurrent()) return AndroidPairedDeviceClientCredentialsResult.Unavailable
                return AndroidPairedDeviceClientCredentialsResult.Opened(credentials).also { delivered = true }
            } finally {
                if (!delivered) withContext(NonCancellable) {
                    // Undelivered/failed qualification never drops either exact owner.
                    // Unknown native close remains retained by the journal's process lease.
                    try { actorAllocationOwner?.close() }
                    finally { try { owner?.close() } finally { custody.close() } }
                }
            }
        }
    }
}

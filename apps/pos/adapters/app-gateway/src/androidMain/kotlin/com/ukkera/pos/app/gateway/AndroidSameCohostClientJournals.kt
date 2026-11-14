package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.database.AndroidEmployeeClientCustody
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.NativeEmployeeJournalAllocation
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Aggregate of privately delivered genuine owners. Never creates/reopens on actor lookup,
 * accepts a supplied journal/currentness callback, or falls back to the first actor's intent.
 * All employees reuse the existing device credential owner; only their intent lease is new. */
internal class AndroidSameCohostClientJournals(
    private val application: Application,
    private val originalClient: AndroidOriginalLocalClient,
    private val original: AndroidOriginalClientJournals,
) : SameCohostClientJournals {
    init { check(original.belongsTo(originalClient) && originalClient.belongsTo(application)) }
    override val credentials: LocalClientCredentialJournal get() = original.credentials
    private class EmployeeOwner(val custody: AndroidEmployeeClientCustody, val allocation: NativeEmployeeJournalAllocation,
        val native: AndroidLocalClientJournalOwner) {
        val intents: LocalClientIntentJournal = checkNotNull(native.intentJournal)
        fun current(): Boolean = !native.physicalDenied && native.ownsEmployeePhysical(custody) &&
            custody.isCurrent() && allocation.belongsTo(custody.native) && allocation.originalOpenClaimed()
    }
    private val employees = AtomicReference<List<EmployeeOwner>>(emptyList())
    private val acquiring = Mutex()
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)

    override suspend fun current(owner: LocalClientIdentity): LocalClientIntentJournal? {
        if (closure.get() != null) return null
        original.current(owner)?.let { return it.takeIf { closure.get() == null } }
        return employees.get().singleOrNull { it.intents.owner == owner && it.current() }?.intents
            ?.takeIf { closure.get() == null }
    }

    override suspend fun acquireEmployee(custody: AndroidEmployeeClientCustody, action: NativeEmployeeClientJournalAction): Boolean = acquiring.withLock {
        currentCoroutineContext().ensureActive()
        if (closure.get() != null || !original.belongsTo(originalClient) || !custody.belongsTo(originalClient) ||
            !custody.belongsTo(application)) return@withLock false
        val owner = LocalClientIdentity(custody.scope, custody.actorId, custody.deviceId)
        employees.get().singleOrNull { it.intents.owner == owner }?.let {
            // An already delivered owner is reused as-is; never acquire a competing allocation
            // or pretend a withdrawn/uncertain native file lease has closed.
            return@withLock it.custody === custody && it.current()
        }
        if (employees.get().size >= MAX_CLIENT_ENROLLMENTS || original.current(owner) != null) return@withLock false
        val allocation = when (val selected = custody.acquireJournalAction(action)) {
            is LocalCompanyAuthorityResult.Value -> selected.value
            is LocalCompanyAuthorityResult.Unavailable -> return@withLock false
        }
        var native: AndroidLocalClientJournalOwner? = null
        var delivered = false
        try {
            val acquired = AndroidLocalClientJournalOwner.acquireEmployee(application, custody, allocation)
            if (acquired !is ClientJournalOutcome.Value) return@withLock false
            native = acquired.value
            currentCoroutineContext().ensureActive()
            if (closure.get() != null || !original.belongsTo(originalClient) || !custody.belongsTo(originalClient) ||
                !allocation.belongsTo(custody.native) || !allocation.originalOpenClaimed()) return@withLock false
            val entry = EmployeeOwner(custody, allocation, acquired.value)
            check(entry.intents.owner == owner && acquired.value.credentialJournal == null)
            employees.set(employees.get() + entry)
            delivered = true
            true
        } finally {
            if (!delivered) withContext(NonCancellable) { native?.close() }
        }
    }

    /** Only this exact privately delivered entry can bind physical participation. This never
     * creates/reopens an owner on actor lookup, borrows the first actor or accepts a journal port. */
    override suspend fun admitEmployeePhysical(custody: AndroidEmployeeClientCustody): Boolean = acquiring.withLock {
        currentCoroutineContext().ensureActive()
        if (closure.get() != null || !custody.belongsTo(originalClient) || !custody.belongsTo(application)) return@withLock false
        val entry = employees.get().singleOrNull { it.custody === custody } ?: return@withLock false
        if (!entry.current()) return@withLock false
        val physical = originalClient.physicalRecordingGate() as? AndroidOriginalPhysicalJournalExecutionGate ?: return@withLock false
        physical.admitEmployee(originalClient, entry.custody, entry.native) && closure.get() == null && entry.current()
    }

    /** Close every employee intent before the original device credential owner. Any unknown
     * close is retained as failure; low-level real OS leases are never released by this list. */
    override suspend fun close(): Unit = withContext(NonCancellable) {
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        // End already delivered employee lifetimes synchronously, BEFORE waiting for the
        // acquisition/admission serializer. Any in-flight undelivered owner closes in finally.
        employees.get().forEach { it.native.denyPhysical() }
        acquiring.withLock {
            var failure: Throwable? = null
            for (employee in employees.get().asReversed()) {
                try { employee.native.close() } catch (caught: Throwable) {
                    if (failure == null) failure = caught else checkNotNull(failure).addSuppressed(caught)
                }
            }
            try { original.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else checkNotNull(failure).addSuppressed(caught)
            }
            val problem = failure
            if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
        }
        result.await()
    }
    override fun toString(): String = "AndroidSameCohostClientJournals([REDACTED])"
}

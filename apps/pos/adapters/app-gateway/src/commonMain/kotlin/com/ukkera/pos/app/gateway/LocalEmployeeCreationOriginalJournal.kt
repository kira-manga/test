package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalEmployeeCreationReceiptV1
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/** Local storage outcomes only. None authenticates a session, authorizes an INSERT or proves
 * native settlement. Only the protected coordinator consumes its first-dispatch handoff. */
internal sealed interface EmployeeCreationJournalResult {
    class Retained(val entry: StoredClientEmployeeCreationV1): EmployeeCreationJournalResult
    /** Only a durable PREPARED -> DISPATCHED replacement returns this memory-only handoff.
     * Reopen/read/retry can return Retained, never reconstruct this first-dispatch handoff. */
    class FirstDispatch(val entry: StoredClientEmployeeCreationV1): EmployeeCreationJournalResult
    /** Removed while the actual durable entry was still PREPARED. This is not a Master tombstone. */
    data object RemovedPrepared: EmployeeCreationJournalResult
    /** No local row observed; never evidence of Master absence, rollback or permission to redispatch. */
    data object NotRetained: EmployeeCreationJournalResult
    data object ForgottenTerminal: EmployeeCreationJournalResult
    data object IdentityConflict: EmployeeCreationJournalResult
    data object Capacity: EmployeeCreationJournalResult
    data object Unavailable: EmployeeCreationJournalResult
    data object StorageUncertain: EmployeeCreationJournalResult
}

/** Internal control journal over real encrypted custody. It contains the full original and
 * creator/device attribution, but no password, hash, approval or native capability. It does not
 * query or mutate the Master. Current session/authority checks belong to the protected service
 * consumer; reading this journal never restores an interactive login or grants dispatch. */
internal class LocalEmployeeCreationOriginalJournal(
    private val journal: LocalClientCredentialJournal,
    private val owner: LocalClientIdentity,
) {
    init { require(journal.owner.scope == owner.scope && journal.owner.deviceId == owner.deviceId) }

    suspend fun retained(): EmployeeCreationJournalResult = access { state ->
        state.employeeCreations.singleOrNull { it.owner == owner }?.let { EmployeeCreationJournalResult.Retained(it) }
            ?: EmployeeCreationJournalResult.NotRetained
    }

    suspend fun prepare(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationJournalResult = access { state ->
        if (original.scope != owner.scope || original.operatorId != owner.actorId)
            return@access EmployeeCreationJournalResult.IdentityConflict
        val entry = StoredClientEmployeeCreationV1.capture(owner, original)
        val prior = state.employeeCreations.firstOrNull { it.owner == owner || it.original.requestId == original.requestId }
        if (prior != null) return@access if (prior.owner == owner &&
            prior.original.copyWireBytes().useWiped { before -> original.copyWireBytes().useWiped { before.contentEquals(it) } })
            EmployeeCreationJournalResult.Retained(prior) else EmployeeCreationJournalResult.IdentityConflict
        if (state.enrollments.any { it.requestId == original.requestId } ||
            state.administrations.any { it.command.requestId == original.requestId }) return@access EmployeeCreationJournalResult.IdentityConflict
        if (state.employeeCreations.size >= MAX_CLIENT_EMPLOYEE_CREATIONS) return@access EmployeeCreationJournalResult.Capacity
        replace(state, state.employeeCreations + entry) { EmployeeCreationJournalResult.Retained(entry) }
    }

    /** Bind the complete caller-selected original under the same journal mutex as the
     * durable transition. A delayed action cannot claim a replacement with the same ID. */
    suspend fun claimFirstDispatch(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationJournalResult = access { state ->
        val entry = state.find(original.requestId) ?: return@access EmployeeCreationJournalResult.NotRetained
        if (!entry.original.copyWireBytes().useWiped { before -> original.copyWireBytes().useWiped { before.contentEquals(it) } })
            return@access EmployeeCreationJournalResult.IdentityConflict
        if (entry.phase != StoredEmployeeCreationPhaseV1.PREPARED) return@access EmployeeCreationJournalResult.Retained(entry)
        val next = entry.dispatched()
        replaceEntry(state, entry, next) { EmployeeCreationJournalResult.FirstDispatch(next) }
    }

    /** Prepared work can be removed locally. Once dispatched, persist only a stop request;
     * lookup/native server cancellation is still required. Never report Cancelled or forget it. */
    suspend fun requestStop(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationJournalResult = access { state ->
        val entry = state.find(original.requestId) ?: return@access EmployeeCreationJournalResult.NotRetained
        if (!entry.original.copyWireBytes().useWiped { before -> original.copyWireBytes().useWiped { before.contentEquals(it) } })
            return@access EmployeeCreationJournalResult.IdentityConflict
        when {
            entry.phase == StoredEmployeeCreationPhaseV1.PREPARED -> replace(state,
                state.employeeCreations.filterNot { it === entry }) { EmployeeCreationJournalResult.RemovedPrepared }
            entry.terminal || entry.stopRequested -> EmployeeCreationJournalResult.Retained(entry)
            else -> {
                val next = entry.stopped()
                replaceEntry(state, entry, next) { EmployeeCreationJournalResult.Retained(next) }
            }
        }
    }

    /** The consumer must first validate its actual current protected service response.
     * This storage operation checks exact correlation, not the authenticity of public DATA. */
    suspend fun recordApplied(original: LocalDelegatedEmployeeCreationV1,
        receipt: LocalEmployeeCreationReceiptV1): EmployeeCreationJournalResult = access { state ->
        // Check the response against the queried original BEFORE selecting any journal row.
        // A miscorrelated receipt cannot terminalize another entry that replaced that row.
        if (receipt.scope != original.scope || receipt.requestId != original.requestId ||
            receipt.originalOperatorId != original.operatorId || receipt.actorId != original.actorId ||
            receipt.contentDigest != original.contentDigest) return@access EmployeeCreationJournalResult.IdentityConflict
        val entry = state.find(original.requestId) ?: return@access EmployeeCreationJournalResult.NotRetained
        if (!entry.original.copyWireBytes().useWiped { before -> original.copyWireBytes().useWiped { before.contentEquals(it) } } ||
            entry.phase == StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT ||
            entry.phase == StoredEmployeeCreationPhaseV1.CANCELLED) return@access EmployeeCreationJournalResult.IdentityConflict
        if (entry.phase == StoredEmployeeCreationPhaseV1.APPLIED) return@access EmployeeCreationJournalResult.Retained(entry)
        val next = entry.applied(receipt)
        replaceEntry(state, entry, next) { EmployeeCreationJournalResult.Retained(next) }
    }

    /** Store an actual protected server cancellation only. Local stop/absence/uncertainty
     * cannot produce this terminal phase, and another original cannot inherit the response. */
    suspend fun recordCancelled(original: LocalDelegatedEmployeeCreationV1,
        receipt: LocalEmployeeCreationReceiptV1): EmployeeCreationJournalResult = access { state ->
        if (receipt.scope != original.scope || receipt.requestId != original.requestId ||
            receipt.originalOperatorId != original.operatorId || receipt.actorId != original.actorId ||
            receipt.contentDigest != original.contentDigest) return@access EmployeeCreationJournalResult.IdentityConflict
        val entry = state.find(original.requestId) ?: return@access EmployeeCreationJournalResult.NotRetained
        if (!entry.original.copyWireBytes().useWiped { before -> original.copyWireBytes().useWiped { before.contentEquals(it) } } ||
            entry.phase == StoredEmployeeCreationPhaseV1.APPLIED || entry.phase == StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT)
            return@access EmployeeCreationJournalResult.IdentityConflict
        if (entry.phase == StoredEmployeeCreationPhaseV1.CANCELLED) return@access EmployeeCreationJournalResult.Retained(entry)
        val next = entry.cancelled(receipt)
        replaceEntry(state, entry, next) { EmployeeCreationJournalResult.Retained(next) }
    }

    /** A late response for a removed/replaced PREPARED original cannot terminalize another
     * original that reused its request ID while the protected lookup was suspended. */
    suspend fun recordIdentityConflict(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationJournalResult = access { state ->
        val entry = state.find(original.requestId) ?: return@access EmployeeCreationJournalResult.NotRetained
        if (!entry.original.copyWireBytes().useWiped { before -> original.copyWireBytes().useWiped { before.contentEquals(it) } })
            return@access EmployeeCreationJournalResult.IdentityConflict
        if (entry.phase == StoredEmployeeCreationPhaseV1.APPLIED || entry.phase == StoredEmployeeCreationPhaseV1.CANCELLED)
            return@access EmployeeCreationJournalResult.IdentityConflict
        if (entry.phase == StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT) return@access EmployeeCreationJournalResult.Retained(entry)
        val next = entry.identityConflict()
        replaceEntry(state, entry, next) { EmployeeCreationJournalResult.Retained(next) }
    }

    suspend fun forgetTerminal(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationJournalResult = access { state ->
        val entry = state.find(original.requestId) ?: return@access EmployeeCreationJournalResult.NotRetained
        if (!entry.original.copyWireBytes().useWiped { before -> original.copyWireBytes().useWiped { before.contentEquals(it) } })
            return@access EmployeeCreationJournalResult.IdentityConflict
        if (!entry.terminal) return@access EmployeeCreationJournalResult.Retained(entry)
        replace(state, state.employeeCreations.filterNot { it === entry }) { EmployeeCreationJournalResult.ForgottenTerminal }
    }

    private fun StoredClientCredentials.find(requestId: DomainId): StoredClientEmployeeCreationV1? =
        employeeCreations.singleOrNull { it.owner == owner && it.original.requestId == requestId }

    private suspend fun replaceEntry(state: StoredClientCredentials, before: StoredClientEmployeeCreationV1,
        next: StoredClientEmployeeCreationV1, result: () -> EmployeeCreationJournalResult): EmployeeCreationJournalResult =
        replace(state, state.employeeCreations.map { if (it === before) next else it }, result)

    private suspend fun replace(state: StoredClientCredentials, entries: List<StoredClientEmployeeCreationV1>,
        result: () -> EmployeeCreationJournalResult): EmployeeCreationJournalResult {
        val next = state.next(employeeCreations = entries)
        return when (journal.replace(state.revision, next)) {
            JournalWrite.DURABLE -> {
                // Cancellation after the durable transition leaves the retained phase intact,
                // but must not deliver a new first-dispatch handoff to a cancelled caller.
                currentCoroutineContext().ensureActive()
                result()
            }
            JournalWrite.NO_CAPACITY -> EmployeeCreationJournalResult.Capacity
            JournalWrite.IDENTITY_CONFLICT -> EmployeeCreationJournalResult.IdentityConflict
            JournalWrite.UNAVAILABLE -> EmployeeCreationJournalResult.StorageUncertain
        }
    }

    private suspend fun access(body: suspend (StoredClientCredentials) -> EmployeeCreationJournalResult): EmployeeCreationJournalResult =
        journal.requests.withLock {
            currentCoroutineContext().ensureActive()
            try {
                when (val read = journal.read()) {
                    JournalRead.Unavailable -> EmployeeCreationJournalResult.Unavailable
                    is JournalRead.Value -> try { body(read.value) } finally { read.value.destroy() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { EmployeeCreationJournalResult.StorageUncertain }
        }
}

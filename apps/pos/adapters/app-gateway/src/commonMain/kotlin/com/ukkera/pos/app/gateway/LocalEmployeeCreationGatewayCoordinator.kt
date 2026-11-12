package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.app.gateway.LocalManagementGatewayAccess.Access
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Complete original-owned creation workflow. Local/cohost administration shares its exact
 * request gate and protected access holder. A separately composed remote connection must use
 * the same genuine authentication/journal lifetime; no local native peer is manufactured. */
internal class LocalEmployeeCreationGatewayCoordinator(
    private val journal: LocalClientCredentialJournal,
    private val owner: LocalClientIdentity,
    private val endpoint: LocalEmployeeCreationServiceV1,
    private val requests: Mutex,
    private val management: LocalManagementGatewayAccess,
) {
    private val creationOriginals = LocalEmployeeCreationOriginalJournal(journal, owner)
    init { require(journal.owner.scope == owner.scope && journal.owner.deviceId == owner.deviceId) }

    private suspend fun <T> withAccess(body: suspend (Access) -> T): T = management.withAccess(body)
    private fun requireGrant(view: LocalSessionView, directory: LocalAdministrationDirectory) = management.requireGrant(view, directory)

    internal suspend fun employeeCreationContext(target: LocalEmployeeCreationTargetV1): LocalEmployeeCreationContextResult = requests.withLock {
        try { withAccess { access ->
            if (target.actorId == owner.actorId) return@withAccess LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.INVALID_INPUT)
            requireGrant(access.view, LocalAdministrationDirectory.ACCOUNTS)
            requireNoEmployeeCreationOriginal()
            val result = endpoint.employeeCreationContext(access.lease.credentials, target)
            reobserveCreationAccess(access)
            when (result) {
                is LocalEmployeeCreationContextResultV1.Available -> {
                    val context = result.context
                    if (context.scope != owner.scope || context.operatorId != owner.actorId || context.target != target)
                        LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.INVALID_INPUT)
                    else LocalEmployeeCreationContextResult.Available(context)
                }
                is LocalEmployeeCreationContextResultV1.Refused -> LocalEmployeeCreationContextResult.Refused(result.review)
                LocalEmployeeCreationContextResultV1.Denied -> LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.DENIED)
                LocalEmployeeCreationContextResultV1.Busy -> LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.RETRY_LATER)
                LocalEmployeeCreationContextResultV1.StorageUncertain -> LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                LocalEmployeeCreationContextResultV1.Unavailable -> LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalEmployeeCreationContextResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    internal suspend fun captureEmployeeCreationDefinition(draft: LocalEmployeeCreationDraftV1): LocalEmployeeCreationDefinitionResult = requests.withLock {
        try { withAccess { access ->
            if (draft.scope != owner.scope || draft.actorId == owner.actorId)
                return@withAccess LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.INVALID_INPUT)
            requireGrant(access.view, LocalAdministrationDirectory.ACCOUNTS)
            requireNoEmployeeCreationOriginal()
            val result = endpoint.captureEmployeeCreationDefinition(access.lease.credentials, draft)
            reobserveCreationAccess(access)
            when (result) {
                is LocalEmployeeCreationDefinitionResultV1.Captured -> {
                    if (result.original.operatorId != owner.actorId || !draft.matchesRequested(result.original))
                        LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.INVALID_INPUT)
                    else LocalEmployeeCreationDefinitionResult.Captured(result.original)
                }
                is LocalEmployeeCreationDefinitionResultV1.Refused -> LocalEmployeeCreationDefinitionResult.Refused(result.review)
                LocalEmployeeCreationDefinitionResultV1.Denied -> LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.DENIED)
                LocalEmployeeCreationDefinitionResultV1.Busy -> LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.RETRY_LATER)
                LocalEmployeeCreationDefinitionResultV1.StorageUncertain -> LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                LocalEmployeeCreationDefinitionResultV1.Unavailable -> LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalEmployeeCreationDefinitionResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    private suspend fun requireNoEmployeeCreationOriginal() {
        when (creationOriginals.retained()) {
            EmployeeCreationJournalResult.NotRetained -> Unit
            is EmployeeCreationJournalResult.Retained -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            EmployeeCreationJournalResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            else -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        }
    }

    internal suspend fun pendingEmployeeCreationOriginal(): EmployeeCreationPendingResult = requests.withLock {
        try { withAccess { access ->
            requireGrant(access.view, LocalAdministrationDirectory.ACCOUNTS)
            val retained = when (val local = creationOriginals.retained()) {
                is EmployeeCreationJournalResult.Retained -> local.entry
                EmployeeCreationJournalResult.NotRetained -> return@withAccess EmployeeCreationPendingResult.Available(null)
                EmployeeCreationJournalResult.StorageUncertain -> return@withAccess EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                else -> return@withAccess EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
            }
            // Area access alone cannot expose the private definition of a denied target.
            // The actual service must authenticate/authorize this exact original before any
            // retained projection is delivered, including PREPARED or already terminal data.
            val status = lookupEmployeeCreation(access, retained.original)
            reobserveCreationAccess(access)
            when (status) {
                is LocalEmployeeCreationStatusV1.Applied,
                is LocalEmployeeCreationStatusV1.Cancelled,
                LocalEmployeeCreationStatusV1.IdentityConflict -> when (val recorded = recordEmployeeCreationTerminal(retained.original, status)) {
                    is EmployeeCreationJournalResult.Retained -> EmployeeCreationPendingResult.Available(recorded.entry)
                    EmployeeCreationJournalResult.StorageUncertain -> EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                    else -> EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
                }
                LocalEmployeeCreationStatusV1.NotObserved -> if (retained.terminal)
                    EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                    else EmployeeCreationPendingResult.Available(retained)
                LocalEmployeeCreationStatusV1.Denied -> EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.DENIED)
                LocalEmployeeCreationStatusV1.Busy -> EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.RETRY_LATER)
                LocalEmployeeCreationStatusV1.StorageUncertain -> EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                LocalEmployeeCreationStatusV1.Unavailable -> EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { EmployeeCreationPendingResult.Unavailable(failure.problem) }
        catch (_: Exception) { EmployeeCreationPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    /** Stage only the complete checked original, never a password, permit or first-dispatch
     * handoff. Repeated preparation returns the retained original without changing its phase. */
    internal suspend fun prepareEmployeeCreationOriginal(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationControlResult = requests.withLock {
        var stagingAttempted = false
        // Until the actual journal read proves otherwise, a prior original may survive.
        // Losing the session here must not tell the caller that its original was absent.
        var retainedOriginalPossible = true
        try { withAccess { access ->
            if (original.scope != owner.scope || original.operatorId != owner.actorId)
                return@withAccess EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.INVALID_INPUT)
            requireGrant(access.view, LocalAdministrationDirectory.ACCOUNTS)
            when (val retained = creationOriginals.retained()) {
                is EmployeeCreationJournalResult.Retained -> {
                    val same = retained.entry.original.copyWireBytes().useWiped { before ->
                        original.copyWireBytes().useWiped { before.contentEquals(it) } }
                    return@withAccess if (same) EmployeeCreationControlResult.Retained(retained.entry)
                        else EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.RETRY_LATER)
                }
                EmployeeCreationJournalResult.NotRetained -> retainedOriginalPossible = false
                else -> return@withAccess creationControlRecorded(original, retained)
            }
            when (val review = endpoint.reviewEmployeeCreation(access.lease.credentials, original)) {
                is LocalEmployeeCreationReviewV1.InputsChecked -> {
                    if (review.requestId != original.requestId || review.operatorId != original.operatorId ||
                        review.actorId != original.actorId || review.contentDigest != original.contentDigest)
                        return@withAccess EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.INVALID_INPUT)
                }
                is LocalEmployeeCreationReviewV1.Refused -> return@withAccess EmployeeCreationControlResult.Refused(original.requestId, review)
                LocalEmployeeCreationReviewV1.Denied -> return@withAccess EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.DENIED)
                LocalEmployeeCreationReviewV1.Busy -> return@withAccess EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.RETRY_LATER)
                LocalEmployeeCreationReviewV1.StorageUncertain -> return@withAccess EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.STORAGE_UNCERTAIN)
                LocalEmployeeCreationReviewV1.Unavailable -> return@withAccess EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.UNAVAILABLE)
            }
            reobserveCreationAccess(access)
            stagingAttempted = true
            creationControlRecorded(original, creationOriginals.prepare(original))
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            if (stagingAttempted || retainedOriginalPossible) EmployeeCreationControlResult.Unresolved(original.requestId, failure.problem)
            else EmployeeCreationControlResult.NotPrepared(failure.problem)
        }
        catch (_: Exception) {
            if (stagingAttempted || retainedOriginalPossible) EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE)
            else EmployeeCreationControlResult.NotPrepared(LocalServiceProblem.UNAVAILABLE)
        }
    }

    /** Negative-only local control. Dispatched work retains its original and a stop request;
     * this method never calls server cancellation or reports a cancellation receipt. */
    internal suspend fun stopEmployeeCreationOriginal(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationControlResult =
        controlEmployeeCreationOriginal(original, CreationLocalControl.STOP)

    /** Remove only the exact already terminal local record, after explicit acknowledgement. */
    internal suspend fun acknowledgeEmployeeCreationOriginal(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationControlResult =
        controlEmployeeCreationOriginal(original, CreationLocalControl.ACKNOWLEDGE)

    private enum class CreationLocalControl { STOP, ACKNOWLEDGE }

    private suspend fun controlEmployeeCreationOriginal(original: LocalDelegatedEmployeeCreationV1,
        action: CreationLocalControl): EmployeeCreationControlResult = requests.withLock {
        try { withAccess { access ->
            if (original.scope != owner.scope || original.operatorId != owner.actorId)
                return@withAccess EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.DENIED)
            access.checkCurrent()
            creationControlRecorded(original, when (action) {
                CreationLocalControl.STOP -> creationOriginals.requestStop(original)
                CreationLocalControl.ACKNOWLEDGE -> creationOriginals.forgetTerminal(original)
            })
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { EmployeeCreationControlResult.Unresolved(original.requestId, failure.problem) }
        catch (_: Exception) { EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE) }
    }

    private fun creationControlRecorded(original: LocalDelegatedEmployeeCreationV1,
        result: EmployeeCreationJournalResult): EmployeeCreationControlResult = when (result) {
        is EmployeeCreationJournalResult.Retained -> EmployeeCreationControlResult.Retained(result.entry)
        EmployeeCreationJournalResult.RemovedPrepared -> EmployeeCreationControlResult.RemovedPrepared
        EmployeeCreationJournalResult.ForgottenTerminal -> EmployeeCreationControlResult.ForgottenTerminal
        EmployeeCreationJournalResult.NotRetained -> EmployeeCreationControlResult.NoLocalOriginal
        EmployeeCreationJournalResult.IdentityConflict -> EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.INVALID_INPUT)
        EmployeeCreationJournalResult.Capacity -> EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.RETRY_LATER)
        EmployeeCreationJournalResult.Unavailable -> EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE)
        EmployeeCreationJournalResult.StorageUncertain -> EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
        // None of these control operations claims dispatch. Never expose an unexpected
        // memory-only handoff as permission to retry or to construct another original.
        is EmployeeCreationJournalResult.FirstDispatch -> EmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE)
    }

    /** Consume only this invocation's memory-only durable first-dispatch handoff. A reopened
     * DISPATCHED row is lookup/cancellation-only. The actual service must authorize/settle the
     * original; no public UI calls this internal journal consumer yet. */
    internal suspend fun submitEmployeeCreationOriginal(original: LocalDelegatedEmployeeCreationV1,
        newPassword: CharArray?): EmployeeCreationSubmissionResult = requests.withLock {
        var claimAttempted = false
        var password: CharArray? = null
        try {
            currentCoroutineContext().ensureActive()
            if (original.scope != owner.scope || original.operatorId != owner.actorId ||
                original.copyRequestedDefinition().credentialWasProvided != (newPassword != null) ||
                newPassword != null && newPassword.size !in 4..1024)
                return@withLock EmployeeCreationSubmissionResult.NotDispatched(LocalServiceProblem.INVALID_INPUT)
            password = newPassword?.copyOf()
            withAccess { access ->
                requireGrant(access.view, LocalAdministrationDirectory.ACCOUNTS)
                reobserveCreationAccess(access)
                // Mark before the durable call: cancellation/unknown return after its write
                // must never be reported as proof that this original stayed PREPARED.
                claimAttempted = true
                when (val claimed = creationOriginals.claimFirstDispatch(original)) {
                    is EmployeeCreationJournalResult.FirstDispatch -> Unit
                    is EmployeeCreationJournalResult.Retained -> return@withAccess if (claimed.entry.terminal)
                        EmployeeCreationSubmissionResult.Existing(claimed.entry)
                        else EmployeeCreationSubmissionResult.NeedsRecovery(claimed.entry)
                    else -> return@withAccess creationSubmissionFailure(original, claimed)
                }
                reobserveCreationAccess(access)
                val outcome = executeEmployeeCreation(access, original, password)
                reobserveCreationAccess(access)
                when (outcome) {
                    is LocalEmployeeCreationExecutionV1.Inserted -> {
                        val recorded = creationOriginals.recordApplied(original, outcome.receipt)
                        if (recorded is EmployeeCreationJournalResult.Retained &&
                            recorded.entry.phase == StoredEmployeeCreationPhaseV1.APPLIED)
                            EmployeeCreationSubmissionResult.Inserted(recorded.entry)
                        else creationSubmissionFailure(original, recorded)
                    }
                    is LocalEmployeeCreationExecutionV1.Existing -> {
                        val recorded = recordEmployeeCreationTerminal(original, outcome.status)
                        if (recorded is EmployeeCreationJournalResult.Retained && recorded.entry.terminal)
                            EmployeeCreationSubmissionResult.Existing(recorded.entry)
                        else creationSubmissionFailure(original, recorded)
                    }
                    is LocalEmployeeCreationExecutionV1.Refused -> EmployeeCreationSubmissionResult.Refused(original.requestId, outcome.review)
                    LocalEmployeeCreationExecutionV1.Denied -> EmployeeCreationSubmissionResult.Unresolved(original.requestId, LocalServiceProblem.DENIED)
                    LocalEmployeeCreationExecutionV1.Busy -> EmployeeCreationSubmissionResult.Unresolved(original.requestId, LocalServiceProblem.RETRY_LATER)
                    LocalEmployeeCreationExecutionV1.StorageUncertain -> EmployeeCreationSubmissionResult.Unresolved(original.requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
                    LocalEmployeeCreationExecutionV1.Unavailable -> EmployeeCreationSubmissionResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            if (claimAttempted) EmployeeCreationSubmissionResult.Unresolved(original.requestId, failure.problem)
            else EmployeeCreationSubmissionResult.NotDispatched(failure.problem)
        } catch (_: Exception) {
            if (claimAttempted) EmployeeCreationSubmissionResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE)
            else EmployeeCreationSubmissionResult.NotDispatched(LocalServiceProblem.UNAVAILABLE)
        } finally { password?.fill('\u0000') }
    }

    /** Explicit same-original server cancellation only after a durable local stop request.
     * PREPARED work must instead be removed by stopEmployeeCreationOriginal. Neither caller
     * cancellation nor a missing observation creates a tombstone or restores first dispatch. */
    internal suspend fun cancelDispatchedEmployeeCreationOriginal(original: LocalDelegatedEmployeeCreationV1):
        EmployeeCreationRecoveryResult = requests.withLock {
        try { withAccess { access ->
            if (original.scope != owner.scope || original.operatorId != owner.actorId)
                return@withAccess EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.INVALID_INPUT)
            requireGrant(access.view, LocalAdministrationDirectory.ACCOUNTS)
            reobserveCreationAccess(access)
            val retained = when (val local = creationOriginals.retained()) {
                is EmployeeCreationJournalResult.Retained -> local.entry
                EmployeeCreationJournalResult.NotRetained -> return@withAccess EmployeeCreationRecoveryResult.NoLocalOriginal
                else -> return@withAccess creationRecoveryFailure(original.requestId, local)
            }
            val same = retained.original.copyWireBytes().useWiped { before ->
                original.copyWireBytes().useWiped { before.contentEquals(it) } }
            if (!same) return@withAccess EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.INVALID_INPUT)
            if (retained.terminal) return@withAccess EmployeeCreationRecoveryResult.Known(retained)
            if (retained.phase != StoredEmployeeCreationPhaseV1.DISPATCHED)
                return@withAccess EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.INVALID_INPUT)
            val stopped = creationOriginals.requestStop(original)
            if (stopped !is EmployeeCreationJournalResult.Retained)
                return@withAccess creationRecoveryFailure(original.requestId, stopped)
            if (stopped.entry.terminal) return@withAccess EmployeeCreationRecoveryResult.Known(stopped.entry)
            if (stopped.entry.phase != StoredEmployeeCreationPhaseV1.DISPATCHED || !stopped.entry.stopRequested)
                return@withAccess EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE)
            reobserveCreationAccess(access)
            val outcome = cancelEmployeeCreation(access, original)
            reobserveCreationAccess(access)
            when (outcome) {
                is LocalEmployeeCreationCancellationV1.Recorded -> creationRecoveryRecorded(original.requestId,
                    creationOriginals.recordCancelled(original, outcome.receipt))
                is LocalEmployeeCreationCancellationV1.Existing -> creationRecoveryRecorded(original.requestId,
                    recordEmployeeCreationTerminal(original, outcome.status))
                LocalEmployeeCreationCancellationV1.Denied -> EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.DENIED)
                LocalEmployeeCreationCancellationV1.Busy -> EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.RETRY_LATER)
                LocalEmployeeCreationCancellationV1.StorageUncertain -> EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
                LocalEmployeeCreationCancellationV1.Unavailable -> EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { EmployeeCreationRecoveryResult.Unresolved(original.requestId, failure.problem) }
        catch (_: Exception) { EmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.UNAVAILABLE) }
    }

    /** A real accepting transport calls this consumer before its terminal Receipt. Ordinary
     * cohost services retain their existing call path; no transport acceptance is invented. */
    private suspend fun executeEmployeeCreation(access: Access, original: LocalDelegatedEmployeeCreationV1,
        password: CharArray?): LocalEmployeeCreationExecutionV1 =
        if (endpoint is LocalEmployeeCreationResultAcceptanceV1)
            endpoint.executeEmployeeCreationAccepting(access.lease.credentials, original, password) { status ->
                retainEmployeeCreationTerminal(access, original, status)
            }
        else endpoint.executeEmployeeCreation(access.lease.credentials, original, password)

    private suspend fun cancelEmployeeCreation(access: Access,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationCancellationV1 =
        if (endpoint is LocalEmployeeCreationResultAcceptanceV1)
            endpoint.cancelEmployeeCreationAccepting(access.lease.credentials, original) { status ->
                retainEmployeeCreationTerminal(access, original, status)
            }
        else endpoint.cancelEmployeeCreation(access.lease.credentials, original)

    private suspend fun lookupEmployeeCreation(access: Access,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationStatusV1 =
        if (endpoint is LocalEmployeeCreationResultAcceptanceV1)
            endpoint.lookupEmployeeCreationAccepting(access.lease.credentials, original) { status ->
                retainEmployeeCreationTerminal(access, original, status)
            }
        else endpoint.lookupEmployeeCreation(access.lease.credentials, original)

    /** No journal mutex spans a remote/current read. The actual journal checks complete original
     * bytes and the exact receipt before its durable replacement. A failed/unknown retention or
     * a lost current session throws through the consumer, so it cannot authorize Receipt. */
    private suspend fun retainEmployeeCreationTerminal(access: Access, original: LocalDelegatedEmployeeCreationV1,
        status: LocalEmployeeCreationStatusV1) {
        reobserveCreationAccess(access)
        val expectedPhase = when (status) {
            is LocalEmployeeCreationStatusV1.Applied -> StoredEmployeeCreationPhaseV1.APPLIED
            is LocalEmployeeCreationStatusV1.Cancelled -> StoredEmployeeCreationPhaseV1.CANCELLED
            LocalEmployeeCreationStatusV1.IdentityConflict -> StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT
            else -> throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
        }
        val retained = when (val result = recordEmployeeCreationTerminal(original, status)) {
            is EmployeeCreationJournalResult.Retained -> result.entry
            EmployeeCreationJournalResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            EmployeeCreationJournalResult.Capacity -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            EmployeeCreationJournalResult.IdentityConflict -> throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            else -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        }
        if (retained.phase != expectedPhase || !retained.terminal)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        when (status) {
            is LocalEmployeeCreationStatusV1.Applied -> if (retained.appliedReceipt() != status.receipt)
                throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            is LocalEmployeeCreationStatusV1.Cancelled -> if (retained.cancelledReceipt() != status.receipt)
                throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            else -> Unit // Only the exact-original IdentityConflict phase can reach here.
        }
        reobserveCreationAccess(access)
    }

    private suspend fun recordEmployeeCreationTerminal(original: LocalDelegatedEmployeeCreationV1,
        status: LocalEmployeeCreationStatusV1): EmployeeCreationJournalResult = when (status) {
        is LocalEmployeeCreationStatusV1.Applied -> creationOriginals.recordApplied(original, status.receipt)
        is LocalEmployeeCreationStatusV1.Cancelled -> creationOriginals.recordCancelled(original, status.receipt)
        LocalEmployeeCreationStatusV1.IdentityConflict -> creationOriginals.recordIdentityConflict(original)
        else -> throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
    }

    private fun creationSubmissionFailure(original: LocalDelegatedEmployeeCreationV1,
        result: EmployeeCreationJournalResult): EmployeeCreationSubmissionResult =
        EmployeeCreationSubmissionResult.Unresolved(original.requestId, when (result) {
            EmployeeCreationJournalResult.Capacity -> LocalServiceProblem.RETRY_LATER
            EmployeeCreationJournalResult.IdentityConflict -> LocalServiceProblem.INVALID_INPUT
            EmployeeCreationJournalResult.StorageUncertain -> LocalServiceProblem.STORAGE_UNCERTAIN
            else -> LocalServiceProblem.UNAVAILABLE
        })

    private suspend fun reobserveCreationAccess(access: Access) {
        val observed = management.currentView(access.lease)
        if (observed.sessionId != access.view.sessionId || observed.generation != access.view.generation ||
            observed.policy.revision != access.view.policy.revision) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        access.checkCurrent()
    }

    /** Recover only the complete retained immutable original. A missing observation never
     * restores a first-dispatch handoff, removes an original, cancels server work or proves
     * native commit/close. Delayed callers cannot select a replacement by request ID alone. */
    internal suspend fun recoverEmployeeCreationOriginal(original: LocalDelegatedEmployeeCreationV1): EmployeeCreationRecoveryResult = requests.withLock {
        val requestId = original.requestId
        try { withAccess { access ->
            if (original.scope != owner.scope || original.operatorId != owner.actorId)
                return@withAccess EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.DENIED)
            val retained = when (val local = creationOriginals.retained()) {
                is EmployeeCreationJournalResult.Retained -> local.entry
                EmployeeCreationJournalResult.NotRetained -> return@withAccess EmployeeCreationRecoveryResult.NoLocalOriginal
                else -> return@withAccess creationRecoveryFailure(requestId, local)
            }
            if (retained.original.requestId != requestId) return@withAccess EmployeeCreationRecoveryResult.NoLocalOriginal
            val same = retained.original.copyWireBytes().useWiped { before ->
                original.copyWireBytes().useWiped { before.contentEquals(it) } }
            if (!same) return@withAccess EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.INVALID_INPUT)
            access.checkCurrent()
            val status = lookupEmployeeCreation(access, retained.original)
            // Re-observe the actual current session after the protected lookup, before writing
            // its outcome to the journal. The enclosing boundary checks again before delivery.
            reobserveCreationAccess(access)
            when (status) {
                is LocalEmployeeCreationStatusV1.Applied -> creationRecoveryRecorded(requestId,
                    creationOriginals.recordApplied(retained.original, status.receipt))
                is LocalEmployeeCreationStatusV1.Cancelled -> creationRecoveryRecorded(requestId,
                    creationOriginals.recordCancelled(retained.original, status.receipt))
                LocalEmployeeCreationStatusV1.IdentityConflict -> creationRecoveryRecorded(requestId,
                    creationOriginals.recordIdentityConflict(retained.original))
                LocalEmployeeCreationStatusV1.NotObserved -> if (retained.terminal)
                    EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
                    else EmployeeCreationRecoveryResult.NotObserved(requestId)
                LocalEmployeeCreationStatusV1.Denied -> EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.DENIED)
                LocalEmployeeCreationStatusV1.Busy -> EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.RETRY_LATER)
                LocalEmployeeCreationStatusV1.StorageUncertain -> EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
                LocalEmployeeCreationStatusV1.Unavailable -> EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.UNAVAILABLE)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { EmployeeCreationRecoveryResult.Unresolved(requestId, failure.problem) }
        catch (_: Exception) { EmployeeCreationRecoveryResult.Unresolved(requestId, LocalServiceProblem.UNAVAILABLE) }
    }

    private fun creationRecoveryRecorded(requestId: DomainId, result: EmployeeCreationJournalResult): EmployeeCreationRecoveryResult =
        if (result is EmployeeCreationJournalResult.Retained && result.entry.original.requestId == requestId && result.entry.terminal)
            EmployeeCreationRecoveryResult.Known(result.entry)
        else creationRecoveryFailure(requestId, result)

    private fun creationRecoveryFailure(requestId: DomainId, result: EmployeeCreationJournalResult): EmployeeCreationRecoveryResult =
        EmployeeCreationRecoveryResult.Unresolved(requestId, if (result == EmployeeCreationJournalResult.StorageUncertain)
            LocalServiceProblem.STORAGE_UNCERTAIN else LocalServiceProblem.UNAVAILABLE)

}

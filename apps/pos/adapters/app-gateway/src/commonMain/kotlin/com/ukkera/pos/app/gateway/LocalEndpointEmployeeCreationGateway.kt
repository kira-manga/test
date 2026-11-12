package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1
import com.ukkera.pos.security.LocalEmployeeCreationDraftV1

/** Maps actual protected/journal outcomes into presentation DATA. It owns no authority,
 * password persistence or redispatch policy; those remain in the exact-original coordinator. */
public class LocalEndpointEmployeeCreationGateway internal constructor(
    private val coordinator: LocalEmployeeCreationGatewayCoordinator,
) : LocalEmployeeCreationUiGateway {
    /** Composition only. The root must supply the actual retained remote creation owner and
     * its exact authentication/journal lifetime; this constructor cannot establish authority. */
    public constructor(connection: LocalEmployeeCreationClientConnection,
        journal: LocalClientCredentialJournal, owner: LocalClientIdentity,
        credentials: LocalWorkflowCredentialSource, retireOriginalSession: () -> Unit) : this(
        LocalEmployeeCreationGatewayCoordinator(journal, owner, connection.creation,
            kotlinx.coroutines.sync.Mutex(), LocalManagementGatewayAccess(connection, owner, credentials, retireOriginalSession)))

    override val configured: Boolean = true

    override suspend fun context(target: LocalEmployeeCreationTargetV1): LocalEmployeeCreationContextResult =
        coordinator.employeeCreationContext(target)

    override suspend fun capture(draft: LocalEmployeeCreationDraftV1): LocalEmployeeCreationDefinitionResult =
        coordinator.captureEmployeeCreationDefinition(draft)

    override suspend fun pending(): LocalEmployeeCreationPendingResult =
        when (val result = coordinator.pendingEmployeeCreationOriginal()) {
            is EmployeeCreationPendingResult.Available -> LocalEmployeeCreationPendingResult.Available(result.entry?.presentation())
            is EmployeeCreationPendingResult.Unavailable -> LocalEmployeeCreationPendingResult.Unavailable(result.problem)
        }

    override suspend fun prepare(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult =
        coordinator.prepareEmployeeCreationOriginal(original).presentation()

    override suspend fun submit(original: LocalDelegatedEmployeeCreationV1, newPassword: CharArray?): LocalEmployeeCreationSubmissionResult = try {
        when (val result = coordinator.submitEmployeeCreationOriginal(original, newPassword)) {
            is EmployeeCreationSubmissionResult.Inserted -> LocalEmployeeCreationSubmissionResult.Inserted(result.entry.presentation())
            is EmployeeCreationSubmissionResult.Existing -> LocalEmployeeCreationSubmissionResult.Existing(result.entry.presentation())
            is EmployeeCreationSubmissionResult.NeedsRecovery -> LocalEmployeeCreationSubmissionResult.NeedsRecovery(result.entry.presentation())
            is EmployeeCreationSubmissionResult.Refused -> LocalEmployeeCreationSubmissionResult.Refused(result.requestId, result.review)
            is EmployeeCreationSubmissionResult.NotDispatched -> LocalEmployeeCreationSubmissionResult.NotDispatched(result.problem)
            is EmployeeCreationSubmissionResult.Unresolved -> LocalEmployeeCreationSubmissionResult.Unresolved(result.requestId, result.problem)
        }
    } finally {
        // Includes cancellation before the coordinator acquires its request mutex.
        newPassword?.fill('\u0000')
    }

    override suspend fun lookup(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationRecoveryResult =
        coordinator.recoverEmployeeCreationOriginal(original).presentation()

    override suspend fun stop(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult =
        coordinator.stopEmployeeCreationOriginal(original).presentation()

    override suspend fun cancel(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationRecoveryResult =
        coordinator.cancelDispatchedEmployeeCreationOriginal(original).presentation()

    override suspend fun acknowledge(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult =
        coordinator.acknowledgeEmployeeCreationOriginal(original).presentation()
}

private fun StoredClientEmployeeCreationV1.presentation(): LocalEmployeeCreationPending = LocalEmployeeCreationPending(
    original = original,
    phase = when (phase) {
        StoredEmployeeCreationPhaseV1.PREPARED -> LocalEmployeeCreationPhase.PREPARED
        StoredEmployeeCreationPhaseV1.DISPATCHED -> LocalEmployeeCreationPhase.DISPATCHED
        StoredEmployeeCreationPhaseV1.APPLIED -> LocalEmployeeCreationPhase.APPLIED
        StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT -> LocalEmployeeCreationPhase.IDENTITY_CONFLICT
        StoredEmployeeCreationPhaseV1.CANCELLED -> LocalEmployeeCreationPhase.CANCELLED
    },
    stopRequested = stopRequested,
    receipt = appliedReceipt() ?: cancelledReceipt(),
)

private fun EmployeeCreationControlResult.presentation(): LocalEmployeeCreationControlResult = when (this) {
    is EmployeeCreationControlResult.Retained -> LocalEmployeeCreationControlResult.Retained(entry.presentation())
    EmployeeCreationControlResult.RemovedPrepared -> LocalEmployeeCreationControlResult.RemovedPrepared
    EmployeeCreationControlResult.ForgottenTerminal -> LocalEmployeeCreationControlResult.AcknowledgedTerminal
    EmployeeCreationControlResult.NoLocalOriginal -> LocalEmployeeCreationControlResult.NoLocalOriginal
    is EmployeeCreationControlResult.Refused -> LocalEmployeeCreationControlResult.Refused(requestId, review)
    is EmployeeCreationControlResult.NotPrepared -> LocalEmployeeCreationControlResult.NotPrepared(problem)
    is EmployeeCreationControlResult.Unresolved -> LocalEmployeeCreationControlResult.Unresolved(requestId, problem)
}

private fun EmployeeCreationRecoveryResult.presentation(): LocalEmployeeCreationRecoveryResult = when (this) {
    is EmployeeCreationRecoveryResult.Known -> LocalEmployeeCreationRecoveryResult.Known(entry.presentation())
    EmployeeCreationRecoveryResult.NoLocalOriginal -> LocalEmployeeCreationRecoveryResult.NoLocalOriginal
    is EmployeeCreationRecoveryResult.NotObserved -> LocalEmployeeCreationRecoveryResult.NotObserved(requestId)
    is EmployeeCreationRecoveryResult.Unresolved -> LocalEmployeeCreationRecoveryResult.Unresolved(requestId, problem)
}

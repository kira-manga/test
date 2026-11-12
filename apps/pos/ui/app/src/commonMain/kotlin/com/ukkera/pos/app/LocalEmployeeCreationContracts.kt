package com.ukkera.pos.app

import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalEmployeeCreationReceiptV1
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1
import com.ukkera.pos.security.LocalEmployeeCreationDraftV1
import com.ukkera.pos.security.LocalEmployeeCreationContextV1
import com.ukkera.pos.types.DomainId

enum class LocalEmployeeCreationPhase { PREPARED, DISPATCHED, APPLIED, IDENTITY_CONFLICT, CANCELLED }

/** Historical journal DATA, never authority, safe absence or a first-dispatch handoff.
 * The complete immutable original is retained; no password belongs in this projection. */
class LocalEmployeeCreationPending(
    val original: LocalDelegatedEmployeeCreationV1,
    val phase: LocalEmployeeCreationPhase,
    val stopRequested: Boolean,
    val receipt: LocalEmployeeCreationReceiptV1?,
) {
    val terminal: Boolean get() = phase == LocalEmployeeCreationPhase.APPLIED ||
        phase == LocalEmployeeCreationPhase.IDENTITY_CONFLICT || phase == LocalEmployeeCreationPhase.CANCELLED
    init {
        require(!stopRequested || phase != LocalEmployeeCreationPhase.PREPARED)
        require((receipt != null) == (phase == LocalEmployeeCreationPhase.APPLIED || phase == LocalEmployeeCreationPhase.CANCELLED))
        receipt?.let {
            require(it.scope == original.scope && it.requestId == original.requestId &&
                it.originalOperatorId == original.operatorId && it.actorId == original.actorId &&
                it.contentDigest == original.contentDigest)
        }
    }
    override fun toString(): String = "LocalEmployeeCreationPending([REDACTED])"
}

sealed interface LocalEmployeeCreationPendingResult {
    class Available(val operation: LocalEmployeeCreationPending?) : LocalEmployeeCreationPendingResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalEmployeeCreationPendingResult
}

sealed interface LocalEmployeeCreationControlResult {
    class Retained(val operation: LocalEmployeeCreationPending) : LocalEmployeeCreationControlResult
    /** Local removal while PREPARED; this is not a server cancellation receipt. */
    data object RemovedPrepared : LocalEmployeeCreationControlResult
    /** Explicit removal of an exact terminal local record; the Master original is retained. */
    data object AcknowledgedTerminal : LocalEmployeeCreationControlResult
    /** No matching local original, with no conclusion about server state or redispatch. */
    data object NoLocalOriginal : LocalEmployeeCreationControlResult
    data class Refused(val requestId: DomainId, val review: LocalEmployeeCreationReviewV1.Refused) : LocalEmployeeCreationControlResult {
        override fun toString(): String = "LocalEmployeeCreationControlResult.Refused([REDACTED])"
    }
    /** This invocation did not attempt local staging; a prior original may still exist. */
    data class NotPrepared(val problem: LocalServiceProblem) : LocalEmployeeCreationControlResult
    data class Unresolved(val requestId: DomainId, val problem: LocalServiceProblem) : LocalEmployeeCreationControlResult {
        override fun toString(): String = "LocalEmployeeCreationControlResult.Unresolved([REDACTED])"
    }
}

sealed interface LocalEmployeeCreationSubmissionResult {
    /** A fresh protected insertion was durably associated with the exact local original. */
    class Inserted(val operation: LocalEmployeeCreationPending) : LocalEmployeeCreationSubmissionResult {
        init { require(operation.phase == LocalEmployeeCreationPhase.APPLIED) }
    }
    /** An earlier terminal original existed; this invocation did not install a new password. */
    class Existing(val operation: LocalEmployeeCreationPending) : LocalEmployeeCreationSubmissionResult {
        init { require(operation.terminal) }
    }
    /** A prior dispatch may only be recovered or explicitly cancelled, never rearmed. */
    class NeedsRecovery(val operation: LocalEmployeeCreationPending) : LocalEmployeeCreationSubmissionResult {
        init { require(operation.phase == LocalEmployeeCreationPhase.DISPATCHED) }
    }
    /** Protected input refusal after first dispatch; retain the dispatched original. */
    data class Refused(val requestId: DomainId, val review: LocalEmployeeCreationReviewV1.Refused) : LocalEmployeeCreationSubmissionResult {
        override fun toString(): String = "LocalEmployeeCreationSubmissionResult.Refused([REDACTED])"
    }
    /** No first-dispatch claim attempted by this invocation; no prior absence is implied. */
    data class NotDispatched(val problem: LocalServiceProblem) : LocalEmployeeCreationSubmissionResult
    data class Unresolved(val requestId: DomainId, val problem: LocalServiceProblem) : LocalEmployeeCreationSubmissionResult {
        override fun toString(): String = "LocalEmployeeCreationSubmissionResult.Unresolved([REDACTED])"
    }
}

sealed interface LocalEmployeeCreationRecoveryResult {
    class Known(val operation: LocalEmployeeCreationPending) : LocalEmployeeCreationRecoveryResult {
        init { require(operation.terminal) }
    }
    data object NoLocalOriginal : LocalEmployeeCreationRecoveryResult
    /** Protected read only: retain the original/stop flag, without rollback or retry inference. */
    data class NotObserved(val requestId: DomainId) : LocalEmployeeCreationRecoveryResult {
        override fun toString(): String = "LocalEmployeeCreationRecoveryResult.NotObserved([REDACTED])"
    }
    data class Unresolved(val requestId: DomainId, val problem: LocalServiceProblem) : LocalEmployeeCreationRecoveryResult {
        override fun toString(): String = "LocalEmployeeCreationRecoveryResult.Unresolved([REDACTED])"
    }
}

sealed interface LocalEmployeeCreationContextResult {
    class Available(val context: LocalEmployeeCreationContextV1) : LocalEmployeeCreationContextResult
    class Refused(val review: LocalEmployeeCreationReviewV1.Refused) : LocalEmployeeCreationContextResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalEmployeeCreationContextResult
}

sealed interface LocalEmployeeCreationDefinitionResult {
    class Captured(val original: LocalDelegatedEmployeeCreationV1) : LocalEmployeeCreationDefinitionResult
    class Refused(val review: LocalEmployeeCreationReviewV1.Refused) : LocalEmployeeCreationDefinitionResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalEmployeeCreationDefinitionResult
}

/** Exact-original application port. Presentations must retire private state on session loss;
 * returned journal projections never restore a login or grant authority to create an account. */
interface LocalEmployeeCreationUiGateway {
    val configured: Boolean
    /** No-effect protected input reads; neither prepares a journal row nor permits dispatch. */
    suspend fun context(target: LocalEmployeeCreationTargetV1): LocalEmployeeCreationContextResult =
        LocalEmployeeCreationContextResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    suspend fun capture(draft: LocalEmployeeCreationDraftV1): LocalEmployeeCreationDefinitionResult =
        LocalEmployeeCreationDefinitionResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    /** Reconciles the creator's retained original under current session and exact protected
     * target lookup before exposing private details. Never dispatches creation. */
    suspend fun pending(): LocalEmployeeCreationPendingResult
    suspend fun prepare(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult
    /** Consumes and clears the caller's transient password array on every exit, even cancellation. */
    suspend fun submit(original: LocalDelegatedEmployeeCreationV1, newPassword: CharArray?): LocalEmployeeCreationSubmissionResult
    suspend fun lookup(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationRecoveryResult
    /** Removes PREPARED locally, or retains DISPATCHED with a durable stop request. No server call. */
    suspend fun stop(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult
    /** Explicit protected cancellation of the exact DISPATCHED original, after durable stop. */
    suspend fun cancel(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationRecoveryResult
    suspend fun acknowledge(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult
}

object UnavailableLocalEmployeeCreation : LocalEmployeeCreationUiGateway {
    override val configured: Boolean = false
    override suspend fun pending(): LocalEmployeeCreationPendingResult =
        LocalEmployeeCreationPendingResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepare(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult =
        LocalEmployeeCreationControlResult.NotPrepared(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun submit(original: LocalDelegatedEmployeeCreationV1, newPassword: CharArray?): LocalEmployeeCreationSubmissionResult {
        newPassword?.fill('\u0000')
        return LocalEmployeeCreationSubmissionResult.NotDispatched(LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun lookup(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationRecoveryResult =
        LocalEmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun stop(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult =
        LocalEmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun cancel(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationRecoveryResult =
        LocalEmployeeCreationRecoveryResult.Unresolved(original.requestId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun acknowledge(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationControlResult =
        LocalEmployeeCreationControlResult.Unresolved(original.requestId, LocalServiceProblem.NOT_CONFIGURED)
}

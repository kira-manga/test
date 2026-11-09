package com.ukkera.pos.app

import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Presentation of a durable original ceremony. No phase or historical receipt grants authority. */
enum class LocalActionApprovalPhase {
    PREVIEW_REQUIRED, READY_TO_CONFIRM, ISSUE_UNRESOLVED, ATTACHED, CONSUMED, REFUSED,
    RETIRE_UNRESOLVED, RETIRED, RETIRE_RETRY_REQUIRED,
}

enum class LocalActionApprovalRefusal { AUTHORIZATION_REFUSED, THROTTLED, CONFIRMATION_CHANGED }

/** A received pre-queue response, not retirement, approval usability or business absence. */
enum class LocalActionApprovalRetirementNoAdmissionReason { QUEUE_FULL, ENDPOINT_UNAVAILABLE }

/** Display-only invocation metadata. No copied identity can finish an unresolved request. */
data class LocalActionApprovalRetirementAttempt(
    val attemptId: DomainId,
    val sequence: Long,
    val noAdmissionReason: LocalActionApprovalRetirementNoAdmissionReason? = null,
) {
    init { require(sequence > 0) }
}

/** The real Master's requirement for the already saved original request, not a fresh form. */
class LocalActionApprovalReview(
    val operationId: OperationId,
    val action: LocalAction,
    val requestorId: DomainId,
    val requestorPolicyRevision: Revision,
    requirement: AuthorizationRequirement,
    val confirmationDigest: PayloadDigest,
    val rule: LocalArrivalRuleOptionV2,
    summary: List<LocalRecordValue>,
) {
    val requirement: AuthorizationRequirement = requirement.copy()
    private val summaryEntries = summary.toList()
    val summary: List<LocalRecordValue> get() = summaryEntries.toList()

    init {
        require(action in localArrivalActionsV2 && rule.action == action && !rule.directOnly)
        require(rule.approvalRoute != null && rule.approvalRoute?.mode != LocalArrivalApprovalModeV2.DIRECT)
        require(requirement.permission.verb == PermissionVerb.PERFORM && requirement.binding?.operationId == operationId)
        require(summaryEntries.size in 1..32)
    }
}

/** Historical cancellation metadata, never permission to reuse the old approval. */
data class LocalActionApprovalRetirement(
    val retirementId: DomainId,
    val originalApprovalDigest: PayloadDigest,
    val retiredAt: MonotonicMoment,
)

/** An immutable previous approval, retained when explicitly beginning a replacement. */
data class LocalRetiredActionApproval(
    val approvalId: DomainId,
    val approverId: DomainId,
    val issuedAt: MonotonicMoment,
    val expiresAt: MonotonicMoment,
    val confirmationDigest: PayloadDigest,
    val retirement: LocalActionApprovalRetirement,
    val retirementAttempt: LocalActionApprovalRetirementAttempt? = null,
    val completedRetirementAttempts: List<LocalActionApprovalRetirementAttempt> = emptyList(),
)

/** Display-safe original identity/status. Passwords, interactive bearers and approval grants are absent. */
data class LocalActionApprovalState(
    val phase: LocalActionApprovalPhase,
    val rule: LocalArrivalRuleOptionV2,
    val approvalId: DomainId? = null,
    val review: LocalActionApprovalReview? = null,
    val approverId: DomainId? = null,
    val issuedAt: MonotonicMoment? = null,
    val expiresAt: MonotonicMoment? = null,
    val refusal: LocalActionApprovalRefusal? = null,
    val retirement: LocalActionApprovalRetirement? = null,
    val retiredApprovals: List<LocalRetiredActionApproval> = emptyList(),
    val retirementAttempt: LocalActionApprovalRetirementAttempt? = null,
    /** Earlier received no-admission completions for this same approval, never grants. */
    val completedRetirementAttempts: List<LocalActionApprovalRetirementAttempt> = emptyList(),
) {
    init {
        require(!rule.directOnly && rule.approvalRoute != null)
        require((phase == LocalActionApprovalPhase.PREVIEW_REQUIRED) == (review == null))
        require(phase != LocalActionApprovalPhase.PREVIEW_REQUIRED || approvalId == null)
        require(phase in setOf(LocalActionApprovalPhase.PREVIEW_REQUIRED, LocalActionApprovalPhase.READY_TO_CONFIRM) ||
            approvalId != null)
        val recorded = phase in setOf(LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.CONSUMED,
            LocalActionApprovalPhase.RETIRE_UNRESOLVED, LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED,
            LocalActionApprovalPhase.RETIRED)
        require(recorded == (approverId != null) && recorded == (issuedAt != null) && recorded == (expiresAt != null))
        require((phase == LocalActionApprovalPhase.REFUSED) == (refusal != null))
        require((phase == LocalActionApprovalPhase.RETIRED) == (retirement != null))
        require(retiredApprovals.size <= 16 && retiredApprovals.map { it.approvalId }.distinct().size == retiredApprovals.size)
        require(retiredApprovals.none { it.approvalId == approvalId })
        require(retirementAttempt == null || phase in setOf(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
            LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, LocalActionApprovalPhase.CONSUMED, LocalActionApprovalPhase.RETIRED))
        // Older unresolved presentations may lack invocation metadata. That is lookup-only,
        // never an inferred completed request or an accepting retry default.
        require(phase != LocalActionApprovalPhase.RETIRE_UNRESOLVED || retirementAttempt?.noAdmissionReason == null)
        require(phase != LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED || retirementAttempt?.noAdmissionReason != null)
        require(completedRetirementAttempts.all { it.noAdmissionReason != null })
        require(completedRetirementAttempts.isEmpty() || retirementAttempt != null)
        require(retiredApprovals.all { retired ->
            retired.completedRetirementAttempts.all { it.noAdmissionReason != null } &&
                (retired.completedRetirementAttempts.isEmpty() || retired.retirementAttempt != null)
        })
        val attempts = retiredApprovals.flatMap { it.completedRetirementAttempts + listOfNotNull(it.retirementAttempt) } +
            completedRetirementAttempts + listOfNotNull(retirementAttempt)
        require(attempts.zipWithNext().all { (before, after) -> before.sequence < after.sequence })
        require(attempts.map { it.attemptId }.distinct().size == attempts.size)
        require(expiresAt == null || issuedAt != null && expiresAt.bootId == issuedAt.bootId &&
            expiresAt.elapsedMillis > issuedAt.elapsedMillis)
    }
}

sealed interface LocalActionApprovalResult {
    /** Authoritative reads/issuance were observed; submit still checks the current Master. */
    data class Available(val operation: LocalPendingOperation) : LocalActionApprovalResult {
        init { require(operation.actionApproval != null) }
    }

    /** Keep the original operation/approval identity. This is never permission to issue again. */
    data class Unavailable(val operationId: OperationId, val problem: LocalServiceProblem) : LocalActionApprovalResult
}

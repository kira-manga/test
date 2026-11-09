package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/**
 * Boot identity + monotonic elapsed time supplied by a qualified platform clock. Reboot/unknown
 * time does not extend short-lived approval validity. Wall time is only an audit timestamp.
 */
@Serializable
public data class MonotonicMoment(
    public val bootId: DomainId,
    @Serializable(with = MinorUnitsSerializer::class)
    public val elapsedMillis: Long,
) {
    init {
        require(elapsedMillis >= 0)
    }
}

public data class NamedSelfApproval(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val action: SecurityAction,
)

public class SelfApprovalPolicy(
    public val revision: Revision,
    namedExceptions: Set<NamedSelfApproval> = emptySet(),
) {
    init {
        require(namedExceptions.size <= 256)
    }

    private val exceptions: Set<NamedSelfApproval> = namedExceptions.toSet()

    public fun permits(scope: BranchScope, actorId: DomainId, action: SecurityAction): Boolean =
        NamedSelfApproval(scope, actorId, action) in exceptions
}

/** Explicit company/delegated local activation; absent policy never enables manager approval. */
public class LocalApprovalPolicy(
    public val validForMillis: Long,
    namedSelfApprovalActions: Set<SecurityAction>,
) {
    private val actions = namedSelfApprovalActions.toSet()
    init { require(validForMillis in 1..600_000 && actions.size <= 256) }
    public val namedSelfApprovalActions: Set<SecurityAction> get() = actions.toSet()
    public fun forActor(scope: BranchScope, actorId: DomainId, revision: Revision): SelfApprovalPolicy =
        SelfApprovalPolicy(revision, actions.map { NamedSelfApproval(scope, actorId, it) }.toSet())
    override fun toString(): String = "LocalApprovalPolicy([REDACTED])"
}

/**
 * Protected persisted record, NOT an RPC assertion. The exact requirement includes actor-specific
 * ownership, document/revision, lines/payload digest, money/base, reason, operation and tenant.
 * Read from the local approval table only; a body supplied approval object is never authoritative.
 */
public data class ActionApprovalRecord(
    public val approvalId: DomainId,
    public val requestorId: DomainId,
    public val approverId: DomainId,
    public val requirement: AuthorizationRequirement,
    public val requestorPolicyRevision: Revision,
    public val approverPolicyRevision: Revision,
    public val selfApprovalPolicyRevision: Revision,
    public val issuedAt: MonotonicMoment,
    public val validForMillis: Long,
    public val revision: Revision,
    public val consumedByOperationId: OperationId?,
) {
    init {
        require(requirement.scope is BranchScope)
        require(requirement.permission.verb == PermissionVerb.PERFORM)
        require(requirement.binding?.operationId != null)
        require(validForMillis in 1..600_000)
    }
}

/** Inert issuance witnesses, never a login, bearer or REQUEST authorization. */
@Serializable
public data class ActionApprovalRequestorAuthority(
    public val sessionId: DomainId,
    public val generation: SessionGeneration,
    public val deviceId: DomainId,
    public val credentialRevision: Revision,
    public val admissionRevision: Revision,
) {
    init {
        require(generation.value in 1 until Long.MAX_VALUE)
        require(credentialRevision.value < Long.MAX_VALUE && admissionRevision.value < Long.MAX_VALUE)
    }
    override fun toString(): String = "ActionApprovalRequestorAuthority([REDACTED])"
}

/** Selectors for the actual native chronological origin/retirement join, not ancestor authority. */
@Serializable
public data class ActionApprovalReplacementOrigin(
    public val rootApprovalId: DomainId,
    public val rootApprovalDigest: PayloadDigest,
    public val predecessorApprovalId: DomainId,
    public val predecessorApprovalDigest: PayloadDigest,
    public val retirementId: DomainId,
    public val retirementDigest: PayloadDigest,
) {
    init {
        require(retirementId != predecessorApprovalId && retirementId != rootApprovalId)
        require(rootApprovalId != predecessorApprovalId || rootApprovalDigest == predecessorApprovalDigest)
    }
    override fun toString(): String = "ActionApprovalReplacementOrigin([REDACTED])"
}

/** Protected issue provenance. Neither current interactive login nor a copied approval DTO grants it.
 * The base requester witnesses retain original BUSINESS attribution. Only a complete v3
 * replacement carries separate current issuance/consumption authority and origin witnesses. */
public class ProtectedLocalActionApproval(
    public val record: ActionApprovalRecord,
    public val issuerContextId: DomainId,
    public val requestorSessionId: DomainId,
    public val requestorGeneration: SessionGeneration,
    public val requestorDeviceId: DomainId,
    public val requestorCredentialRevision: Revision,
    public val requestorAdmissionRevision: Revision,
    public val approverDeviceId: DomainId,
    public val approverCredentialRevision: Revision,
    public val approverAdmissionRevision: Revision,
    public val confirmationDigest: PayloadDigest,
    /** Null only for the preserved historical v1 payload, which cannot be consumed. */
    public val approverAccountRevision: Revision? = null,
    public val approverGeneration: SessionGeneration? = null,
    /** Exact original submission identity for same-ID issuance retry, not semantic authority. */
    public val issueRequestDigest: PayloadDigest? = null,
    /** Both extensions are mandatory in v3 and absent in the preserved v1/v2 payloads. */
    public val currentRequestorAuthority: ActionApprovalRequestorAuthority? = null,
    public val replacementOrigin: ActionApprovalReplacementOrigin? = null,
) {
    init {
        require(requestorGeneration.value in 1 until Long.MAX_VALUE)
        require(record.validForMillis <= Long.MAX_VALUE - record.issuedAt.elapsedMillis)
        require((record.revision.value == 0L && record.consumedByOperationId == null) ||
            (record.revision.value == 1L && record.consumedByOperationId != null &&
                record.consumedByOperationId == record.requirement.binding?.operationId))
        require((approverAccountRevision == null && approverGeneration == null && issueRequestDigest == null) ||
            (approverAccountRevision != null && approverAccountRevision.value < Long.MAX_VALUE &&
                approverGeneration != null && approverGeneration.value < Long.MAX_VALUE && issueRequestDigest != null))
        require((currentRequestorAuthority == null) == (replacementOrigin == null))
        replacementOrigin?.let {
            require(approverAccountRevision != null && it.rootApprovalId != record.approvalId &&
                it.predecessorApprovalId != record.approvalId && it.retirementId != record.approvalId)
        }
    }
    public val hasApproverIncarnation: Boolean get() = approverAccountRevision != null
    public val hasReplacementOrigin: Boolean get() = replacementOrigin != null
    /** Version-specific witnesses only; callers must still prove current eligibility/native origin. */
    public val issuingRequestorAuthority: ActionApprovalRequestorAuthority get() = currentRequestorAuthority ?:
        ActionApprovalRequestorAuthority(requestorSessionId, requestorGeneration, requestorDeviceId,
            requestorCredentialRevision, requestorAdmissionRevision)
    public fun consumed(record: ActionApprovalRecord): ProtectedLocalActionApproval = ProtectedLocalActionApproval(
        record, issuerContextId, requestorSessionId, requestorGeneration, requestorDeviceId, requestorCredentialRevision,
        requestorAdmissionRevision, approverDeviceId, approverCredentialRevision, approverAdmissionRevision, confirmationDigest,
        approverAccountRevision, approverGeneration, issueRequestDigest, currentRequestorAuthority, replacementOrigin)
    override fun toString(): String = "ProtectedLocalActionApproval([REDACTED])"
}

/** Current activated approver policy read in the consuming transaction, not copied token claims. */
public data class CurrentApproverState(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val enabled: Boolean,
    public val policyRevision: Revision,
)

public enum class ApprovalDenial {
    AUTHORIZATION_NOT_BOUND,
    LOCAL_HUMANS_REQUIRED,
    SELF_APPROVAL_NOT_CONFIGURED,
    APPROVAL_STALE,
    APPROVAL_CONSUMED,
    APPROVER_REVOKED,
    CLOCK_UNPROVEN,
    INVALID_REQUEST,
}

public sealed interface ApprovalIssueDecision {
    public data class Issued(public val record: ActionApprovalRecord) : ApprovalIssueDecision
    public data class Denied(public val reason: ApprovalDenial) : ApprovalIssueDecision
}

public sealed interface ApprovalConsumeDecision {
    public data class Consumed(
        public val record: ActionApprovalRecord,
        public val authorization: AuthorizedAction,
    ) : ApprovalConsumeDecision

    public data class Denied(public val reason: ApprovalDenial) : ApprovalConsumeDecision
}

/**
 * Pure approval reducers. Both request/approve permits must have been freshly authorized in the
 * issuing transaction. A consuming request permit and current approver/self-policy state must be
 * reloaded in the same write transaction as consumption + protected business effects. Persisting
 * an approval separately from the command is supported; consuming it separately is NOT.
 */
public object ActionApprovals {
    public fun issue(
        approvalId: DomainId,
        requirement: AuthorizationRequirement,
        requestAuthorization: AuthorizedAction,
        approveAuthorization: AuthorizedAction,
        selfPolicy: SelfApprovalPolicy,
        now: MonotonicMoment,
        validForMillis: Long,
    ): ApprovalIssueDecision {
        fun deny(reason: ApprovalDenial): ApprovalIssueDecision = ApprovalIssueDecision.Denied(reason)
        val scope = requirement.scope as? BranchScope ?: return deny(ApprovalDenial.INVALID_REQUEST)
        if (requirement.permission.verb != PermissionVerb.PERFORM ||
            requirement.binding?.operationId == null || validForMillis !in 1..600_000
        ) return deny(ApprovalDenial.INVALID_REQUEST)
        if (requestAuthorization.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            approveAuthorization.principalKind != PrincipalKind.LOCAL_EMPLOYEE
        ) return deny(ApprovalDenial.LOCAL_HUMANS_REQUIRED)
        if (!requestAuthorization.isBoundTo(requirement.forVerb(PermissionVerb.REQUEST)) ||
            !approveAuthorization.isBoundTo(requirement.forVerb(PermissionVerb.APPROVE))
        ) return deny(ApprovalDenial.AUTHORIZATION_NOT_BOUND)
        if (requestAuthorization.actorId == approveAuthorization.actorId &&
            !selfPolicy.permits(scope, requestAuthorization.actorId, requirement.permission.action)
        ) return deny(ApprovalDenial.SELF_APPROVAL_NOT_CONFIGURED)
        return ApprovalIssueDecision.Issued(
            ActionApprovalRecord(
                approvalId, requestAuthorization.actorId, approveAuthorization.actorId, requirement,
                requestAuthorization.policyRevision, approveAuthorization.policyRevision, selfPolicy.revision,
                now, validForMillis, Revision(0), null,
            ),
        )
    }

    public fun consume(
        record: ActionApprovalRecord,
        requirement: AuthorizationRequirement,
        requestAuthorization: AuthorizedAction,
        currentApprover: CurrentApproverState,
        selfPolicy: SelfApprovalPolicy,
        now: MonotonicMoment,
    ): ApprovalConsumeDecision {
        fun deny(reason: ApprovalDenial): ApprovalConsumeDecision = ApprovalConsumeDecision.Denied(reason)
        if (record.consumedByOperationId != null) return deny(ApprovalDenial.APPROVAL_CONSUMED)
        if (record.requirement != requirement ||
            requestAuthorization.actorId != record.requestorId ||
            requestAuthorization.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            !requestAuthorization.isBoundTo(requirement.forVerb(PermissionVerb.REQUEST), record.requestorId)
        ) return deny(ApprovalDenial.AUTHORIZATION_NOT_BOUND)
        if (record.issuedAt.bootId != now.bootId || now.elapsedMillis < record.issuedAt.elapsedMillis) {
            return deny(ApprovalDenial.CLOCK_UNPROVEN)
        }
        // Safe subtraction: nonnegative, same-boot monotonic readings established above.
        if (now.elapsedMillis - record.issuedAt.elapsedMillis >= record.validForMillis) {
            return deny(ApprovalDenial.APPROVAL_STALE)
        }
        if (!currentApprover.enabled || currentApprover.actorId != record.approverId ||
            currentApprover.scope != record.requirement.scope
        ) return deny(ApprovalDenial.APPROVER_REVOKED)
        if (currentApprover.policyRevision != record.approverPolicyRevision ||
            requestAuthorization.policyRevision != record.requestorPolicyRevision ||
            selfPolicy.revision != record.selfApprovalPolicyRevision ||
            record.revision.value == Long.MAX_VALUE
        ) return deny(ApprovalDenial.APPROVAL_STALE)
        val scope = record.requirement.scope as BranchScope
        if (record.requestorId == record.approverId &&
            !selfPolicy.permits(scope, record.requestorId, requirement.permission.action)
        ) return deny(ApprovalDenial.SELF_APPROVAL_NOT_CONFIGURED)
        val operationId = requirement.binding?.operationId ?: return deny(ApprovalDenial.INVALID_REQUEST)
        val consumed = record.copy(revision = record.revision.next(), consumedByOperationId = operationId)
        return ApprovalConsumeDecision.Consumed(
            consumed,
            AuthorizedAction.approved(requestAuthorization, consumed),
        )
    }

    private fun AuthorizationRequirement.forVerb(verb: PermissionVerb): AuthorizationRequirement =
        copy(permission = permission.copy(verb = verb))
}

/** No default storage. Implement with unique IDs/CAS in the authoritative branch write transaction. */
public interface ApprovalTransaction {
    public suspend fun find(scope: BranchScope, approvalId: DomainId): ActionApprovalRecord?
    public suspend fun insert(record: ActionApprovalRecord): Boolean
    public suspend fun replace(expectedRevision: Revision, record: ActionApprovalRecord): Boolean
    public suspend fun currentApprover(scope: BranchScope, actorId: DomainId): CurrentApproverState?
}

package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Trusted kernel composition port, NOT a request-body requirement. It normalizes the original
 * typed command, computes its server semantic digest and exact operation binding, and checks
 * current business/recovery/writer admission in this actual writer view. It writes no command,
 * outcome or effects. Null means the original command cannot currently be prepared for approval.
 */
public fun interface LocalApprovalCommandPreparation {
    public suspend fun prepare(principal: LocalEmployeePrincipal, request: MutationRequest,
        view: CommandReadView, additionalResources: Set<RecoveryResourceKey>): PreparedLocalApprovalCommand?
}

public class PreparedLocalApprovalCommand internal constructor(
    requirement: AuthorizationRequirement,
    affectedResources: Set<RecoveryResourceKey>,
    internal val actionApprovalBusinessAttribution: ActionApprovalBusinessAttributionSupport =
        ActionApprovalBusinessAttributionSupport.UNSUPPORTED,
) {
    public val requirement: AuthorizationRequirement = requirement.copy()
    private val resources = affectedResources.toSet()
    public val affectedResources: Set<RecoveryResourceKey> get() = resources.toSet()
    init {
        require(requirement.scope is BranchScope && requirement.permission.verb == PermissionVerb.PERFORM &&
            requirement.binding?.operationId != null)
        require(resources.isNotEmpty())
    }
    override fun toString(): String = "PreparedLocalApprovalCommand([REDACTED])"
}

/** Display-safe exact server requirement. This preview/digest grants no authority by itself. */
public class LocalActionApprovalPreview internal constructor(
    public val requestorId: DomainId,
    public val requestorPolicyRevision: Revision,
    requirement: AuthorizationRequirement,
    public val confirmationDigest: PayloadDigest,
) {
    public val requirement: AuthorizationRequirement = requirement.copy()
    override fun toString(): String = "LocalActionApprovalPreview([REDACTED])"
}

/** A historical receipt, not a bearer or a reusable APPROVE/PERFORM proof. */
public class LocalActionApprovalReceipt internal constructor(
    public val approvalId: DomainId,
    public val requestorId: DomainId,
    public val approverId: DomainId,
    requirement: AuthorizationRequirement,
    public val confirmationDigest: PayloadDigest,
    public val issuedAt: MonotonicMoment,
    public val expiresAt: MonotonicMoment,
    public val consumedByOperationId: OperationId?,
) {
    public val requirement: AuthorizationRequirement = requirement.copy()
    override fun toString(): String = "LocalActionApprovalReceipt([REDACTED])"
}

/** Immutable Master retirement of one original approval; neither a new grant nor business absence at a later cut. */
public class LocalActionApprovalRetirementReceipt internal constructor(
    public val retirementId: DomainId,
    public val approvalId: DomainId,
    public val operationId: OperationId,
    public val requestorId: DomainId,
    public val confirmationDigest: PayloadDigest,
    public val originalApprovalDigest: PayloadDigest,
    public val retiredAt: MonotonicMoment,
) {
    override fun toString(): String = "LocalActionApprovalRetirementReceipt([REDACTED])"
}

public enum class LocalActionApprovalDenial { AUTHORIZATION_REFUSED, THROTTLED, CONFIRMATION_CHANGED }

public sealed interface LocalActionApprovalPreviewResult {
    public class Ready(public val preview: LocalActionApprovalPreview) : LocalActionApprovalPreviewResult
    public data object Denied : LocalActionApprovalPreviewResult
    public data object Busy : LocalActionApprovalPreviewResult
    public data object Unavailable : LocalActionApprovalPreviewResult
    public data object StorageUncertain : LocalActionApprovalPreviewResult
}

public sealed interface LocalActionApprovalIssueResult {
    /** Only after MATCH completion, protected approval/audit COMMIT and outer cleanup. */
    public class Issued(public val receipt: LocalActionApprovalReceipt) : LocalActionApprovalIssueResult
    public data class Denied(public val reason: LocalActionApprovalDenial) : LocalActionApprovalIssueResult
    public data object Busy : LocalActionApprovalIssueResult
    public data object Unavailable : LocalActionApprovalIssueResult
    public data object StorageUncertain : LocalActionApprovalIssueResult
}

public sealed interface LocalActionApprovalStatus {
    public class Recorded(public val receipt: LocalActionApprovalReceipt) : LocalActionApprovalStatus
    public class Retired(public val receipt: LocalActionApprovalReceipt,
        public val retirement: LocalActionApprovalRetirementReceipt) : LocalActionApprovalStatus
    /** Observed absence only; a still-running original issuance may subsequently commit. */
    public data object NotRecorded : LocalActionApprovalStatus
    public data object Denied : LocalActionApprovalStatus
    public data object Busy : LocalActionApprovalStatus
    public data object Unavailable : LocalActionApprovalStatus
    public data object StorageUncertain : LocalActionApprovalStatus
}

public sealed interface LocalActionApprovalRetireResult {
    /** Only after the permanent native-backed tombstone and its audit/history commit. */
    public class Retired(public val receipt: LocalActionApprovalReceipt,
        public val retirement: LocalActionApprovalRetirementReceipt) : LocalActionApprovalRetireResult
    /** The original business recovery route must resolve this consumption; no replacement is authorized. */
    public class Consumed(public val receipt: LocalActionApprovalReceipt) : LocalActionApprovalRetireResult
    /**
     * This exact invocation was never enqueued by the endpoint, so it cannot execute later.
     * This is NOT approval usability, permanent retirement or evidence of business absence.
     * A lost response remains unknown; ordinary lookup cannot reconstruct this disposition.
     */
    public class NotAdmitted internal constructor(
        public val attempt: LocalActionApprovalRetirementAttempt,
        public val reason: LocalActionApprovalNoAdmissionReason,
    ) : LocalActionApprovalRetireResult
    public data object Denied : LocalActionApprovalRetireResult
    public data object Busy : LocalActionApprovalRetireResult
    public data object Unavailable : LocalActionApprovalRetireResult
    public data object StorageUncertain : LocalActionApprovalRetireResult
}

public enum class LocalActionApprovalNoAdmissionReason { QUEUE_FULL, ENDPOINT_UNAVAILABLE }

/**
 * Exact client handoff coordinates, not an authority or a durable Master disposition.
 * The sequence is retained across explicit retries to fence an older-cycle reply even if
 * its random invocation ID is accidentally reused. All original command coordinates match.
 */
public data class LocalActionApprovalRetirementAttempt(
    public val attemptId: DomainId,
    public val sequence: Long,
    public val approvalId: DomainId,
    public val operationId: OperationId,
    public val command: CommandKey,
    public val originalPayloadDigest: PayloadDigest,
    public val confirmationDigest: PayloadDigest,
) {
    init { require(sequence > 0) }

    public fun matches(request: MutationRequest): Boolean = request.actionApprovalId == null &&
        request.operationId == operationId && request.command == command &&
        sha256(request.payload.bytes()) == originalPayloadDigest

    override fun toString(): String = "LocalActionApprovalRetirementAttempt([REDACTED])"

    public companion object {
        public fun capture(attemptId: DomainId, sequence: Long, approvalId: DomainId,
            request: MutationRequest, confirmationDigest: PayloadDigest): LocalActionApprovalRetirementAttempt {
            require(request.actionApprovalId == null)
            return LocalActionApprovalRetirementAttempt(attemptId, sequence, approvalId, request.operationId,
                request.command, sha256(request.payload.bytes()), confirmationDigest)
        }
    }
}

/**
 * Local manager reauthentication on the requestor's authenticated physical device. Password
 * snapshots follow bounded admission; hashing runs without Room. No ordinary manager session,
 * interactive token, central fallback or default approval policy is created. The original ID,
 * command and confirmed preview digest must be retained across an unknown issuance reply.
 * Consumption occurs ONLY in the successful original command's effects/result transaction.
 */
public interface LocalActionApprovalService {
    /** A replacement must select the latest permanently retired original. The selector
     * itself grants nothing; current native history, exact input/policy and original
     * requester-session attribution are independently checked by the Master. */
    public suspend fun preview(requestor: LocalGatewayCredentials, request: MutationRequest,
        retiredApprovalId: DomainId? = null): LocalActionApprovalPreviewResult
    public suspend fun issue(approvalId: DomainId, requestor: LocalGatewayCredentials, request: MutationRequest,
        confirmationDigest: PayloadDigest, approverLogin: LocalLoginIdentifier,
        approverPassword: CharArray, retiredApprovalId: DomainId? = null): LocalActionApprovalIssueResult
    /** Original ID + immutable server preview digest; reauthorizes current requestor, no manager password. */
    public suspend fun lookup(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus
    /**
     * Explicitly fences the requestor's issued, unused approval on the same native writer as
     * business consumption. The complete original unapproved command and confirmed digest must
     * match. A missing original or any terminal business original cannot be retired for retry.
     * An unknown reply is lookup-only. A retired approval is never renewed or consumed; obtaining
     * another approval requires a separate current preview/confirmation/own-authentication flow.
     * Current recovery/retirement login does not authorize reminting historical business
     * attribution. Cross-session replacement requires independently native-qualified original
     * business attribution, fresh current authority and explicit support from both the trusted
     * prepared handler and installed historical producer.
     */
    public suspend fun retire(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest): LocalActionApprovalRetireResult
}

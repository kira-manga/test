package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Complete original-request DATA for branch-employee creation, separate from a company-head
 * enrollment approval. The original creator is retained across session replacement. Passwords,
 * credentials, head receipts and native capabilities are never included in these bytes.
 *
 * The nested enrollment definition supplies the existing lossless account/policy/configuration/
 * admission representation only. Its historical digest is not this request's identity or authority.
 * Native creation, current authorization, configuration/delegation policy, absence checks and
 * historical replay still need their own complete writer proof before any account can be inserted.
 */
public class LocalDelegatedEmployeeCreationV1 private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    private val metadata = decodeFields(bytes).let { fields ->
        Metadata(fields.operatorId, fields.requested.request.scope, fields.requested.request.requestId,
            fields.requested.request.targetId)
    }
    public val operatorId: DomainId = metadata.operatorId
    public val scope: BranchScope = metadata.scope
    public val requestId: DomainId = metadata.requestId
    public val actorId: DomainId = metadata.actorId
    public val contentDigest: PayloadDigest = sha256(bytes)

    /** Freshly decoded DATA: mutating a caller-held collection cannot change this original. */
    public fun copyRequestedDefinition(): LocalEmployeeEnrollmentDefinition = decodeFields(bytes).requested
    public fun copyWireBytes(): ByteArray = bytes.copyOf()

    /** Exact target/revision/request association, never a reusable creation permit. */
    public fun authorizationRequirement(): AuthorizationRequirement = AuthorizationRequirement(
        permission = Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER),
        scope = scope,
        binding = ActionBinding(actorId, Revision(0), contentDigest, OperationId(requestId.value),
            "local-delegated-employee-creation"),
    )

    override fun toString(): String = "LocalDelegatedEmployeeCreationV1([REDACTED])"

    public companion object {
        // The existing complete definition plus one bounded original-creator envelope.
        public const val MAXIMUM_BYTES: Int = LocalEmployeeEnrollmentProfileV1.MAXIMUM_DEFINITION_BYTES + 1024
        private const val DOMAIN = "local-delegated-employee-creation/v1"

        public fun capture(operatorId: DomainId,
            requested: LocalEmployeeEnrollmentDefinition): LocalDelegatedEmployeeCreationV1 {
            require(operatorId != requested.request.targetId)
            val definition = requested.copyWireBytes()
            val canonical = try {
                selfOwnedEncode(DOMAIN, MAXIMUM_BYTES) { id(operatorId); field(definition) }
            } finally { definition.fill(0) }
            return try { LocalDelegatedEmployeeCreationV1(canonical) } finally { canonical.fill(0) }
        }

        public fun decode(canonical: ByteArray): LocalDelegatedEmployeeCreationV1 {
            require(canonical.size in 1..MAXIMUM_BYTES)
            return LocalDelegatedEmployeeCreationV1(canonical)
        }

        private class Metadata(val operatorId: DomainId, val scope: BranchScope,
            val requestId: DomainId, val actorId: DomainId)
        private class Fields(val operatorId: DomainId, val requested: LocalEmployeeEnrollmentDefinition)
        private fun decodeFields(canonical: ByteArray): Fields = selfOwnedDecode(canonical, DOMAIN) {
            val operator = id()
            val nested = field(LocalEmployeeEnrollmentProfileV1.MAXIMUM_DEFINITION_BYTES)
            val requested = try { LocalEmployeeEnrollmentDefinition.decode(nested) } finally { nested.fill(0) }
            require(operator != requested.request.targetId && requested.definition.policy.revision == Revision(0))
            Fields(operator, requested)
        }
    }
}

/** Read-time input failures; none reports a committed creation or authorizes an insertion. */
public enum class LocalEmployeeCreationInputFailureV1 {
    GRANT_DELEGATION,
    CONFIGURATION_DELEGATION,
    INCONSISTENT_DEFINITION,
    ADMISSIONS_CHANGED,
    TARGET_UNAVAILABLE,
    ACCOUNT_CAPACITY,
}

public sealed interface LocalEmployeeCreationReviewV1 {
    /** All checked inputs agreed at the protected read. This is neither safe future absence,
     * a durable original outcome, nor native/configuration/approval-policy authority. */
    public class InputsChecked(public val requestId: DomainId, public val operatorId: DomainId,
        public val actorId: DomainId, public val contentDigest: PayloadDigest): LocalEmployeeCreationReviewV1 {
        override fun toString(): String = "LocalEmployeeCreationReviewV1.InputsChecked([REDACTED])"
    }
    public class Refused(public val reason: LocalEmployeeCreationInputFailureV1,
        public val delegationFailure: LocalEmployeeDelegationFailureV1? = null): LocalEmployeeCreationReviewV1 {
        init { require((reason == LocalEmployeeCreationInputFailureV1.GRANT_DELEGATION) == (delegationFailure != null)) }
    }
    public data object Denied: LocalEmployeeCreationReviewV1
    public data object Unavailable: LocalEmployeeCreationReviewV1
    public data object StorageUncertain: LocalEmployeeCreationReviewV1
    public data object Busy: LocalEmployeeCreationReviewV1
}

/** Read-back DATA for this exact retained creation original. This receipt is not employee,
 * key, native-origin or Master authority, and does not restore the creator's old session. */
public data class LocalEmployeeCreationReceiptV1(
    public val scope: BranchScope,
    public val requestId: DomainId,
    public val originalOperatorId: DomainId,
    public val actorId: DomainId,
    public val contentDigest: PayloadDigest,
) {
    override fun toString(): String = "LocalEmployeeCreationReceiptV1([REDACTED])"
}

/** Exact original lookup only. No status authorizes a new identity, insertion or retry. */
public sealed interface LocalEmployeeCreationStatusV1 {
    public class Applied(public val receipt: LocalEmployeeCreationReceiptV1): LocalEmployeeCreationStatusV1 {
        override fun toString(): String = "LocalEmployeeCreationStatusV1.Applied([REDACTED])"
    }
    /** The same original key has a permanent cancellation tombstone. This does not undo a
     * creation, revoke the creator's session or prove native cleanup by itself. */
    public class Cancelled(public val receipt: LocalEmployeeCreationReceiptV1): LocalEmployeeCreationStatusV1 {
        override fun toString(): String = "LocalEmployeeCreationStatusV1.Cancelled([REDACTED])"
    }
    /** No matching event was observed on this protected read. This is not safe absence,
     * cancellation, a rollback conclusion or permission to dispatch another original. */
    public data object NotObserved: LocalEmployeeCreationStatusV1
    public data object IdentityConflict: LocalEmployeeCreationStatusV1
    public data object Denied: LocalEmployeeCreationStatusV1
    public data object Unavailable: LocalEmployeeCreationStatusV1
    public data object StorageUncertain: LocalEmployeeCreationStatusV1
    public data object Busy: LocalEmployeeCreationStatusV1
}

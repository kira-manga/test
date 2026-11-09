package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException

public enum class ResultRecoveryAccess {
    ORIGINAL_ACTOR,
    SUPERVISOR_AUDITED,
}

/** No result bytes, record existence, coverage watermark or unrelated owner's private data. */
public data class ResultRecoveryAuditEvent(
    public val scope: BranchScope,
    public val operationId: OperationId,
    public val originalActorId: DomainId,
    public val readingActorId: DomainId,
    public val policyRevision: Revision,
)

public fun interface ResultRecoveryAudit {
    /** Must durably append in the same result-read transaction, or fail closed. */
    public suspend fun record(event: ResultRecoveryAuditEvent): Boolean
}

public object DenySupervisorResultRecoveryAudit : ResultRecoveryAudit {
    override suspend fun record(event: ResultRecoveryAuditEvent): Boolean = false
}

public sealed interface ResultRecoveryDecision {
    public class Allowed internal constructor(
        public val scope: BranchScope,
        public val operationId: OperationId,
        public val originalActorId: DomainId,
        public val readingActorId: DomainId,
        public val access: ResultRecoveryAccess,
    ) : ResultRecoveryDecision

    public data class Denied(public val reason: AuthorizationDenial) : ResultRecoveryDecision
}

/** Canonical query DATA shared by live recovery and the authenticated-history comparison. */
internal fun resultRecoveryRequirement(scope: BranchScope, originalActorId: DomainId,
    operationId: OperationId): AuthorizationRequirement {
    val query = ("result-recovery-v1\n" + scope.organizationId.value + "\n" + scope.companyId.value + "\n" +
        scope.branchId.value + "\n" + operationId.value + "\n" + originalActorId.value).encodeToByteArray()
    val digest = try { sha256(query) } finally { query.fill(0) }
    return AuthorizationRequirement(
        permission = Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM),
        scope = scope,
        binding = ActionBinding(DomainId(operationId.value), Revision(0), digest, operationId, "supervisor.result-recovery"),
    )
}

/**
 * A deliberately narrower route than performing fresh money operations. The original actor may
 * resolve a previously submitted operation after ordinary cashier grants change, but must still
 * authenticate a CURRENT enabled local session. Disabled accounts require the explicit audited
 * supervisor route, never copied tokens or relabeling the command.
 *
 * The adapter must supply the STORED original actor where a result exists, and authorize before
 * returning any result/absence/coverage detail. For absent records the query's claimed owner is
 * only a search target, not permission to expose another owner's result.
 */
public class ResultRecoveryPolicy(
    private val provider: CurrentAuthorizationProvider,
    private val audit: ResultRecoveryAudit = DenySupervisorResultRecoveryAudit,
) {
    public suspend fun authorize(
        principal: AuthenticatedPrincipal?,
        scope: BranchScope,
        originalActorId: DomainId,
        operationId: OperationId,
    ): ResultRecoveryDecision = authorizeCurrent(principal, scope, originalActorId, operationId, requireGrant = false)

    /** Resource profiles that require an explicit current recovery grant, including the
     * remote supplier workflow. Original ownership alone does not supply that permission.
     * The ordinary original-actor recovery contract above remains unchanged. */
    public suspend fun authorizeWithCurrentGrant(
        principal: AuthenticatedPrincipal?,
        scope: BranchScope,
        originalActorId: DomainId,
        operationId: OperationId,
    ): ResultRecoveryDecision = authorizeCurrent(principal, scope, originalActorId, operationId, requireGrant = true)

    private suspend fun authorizeCurrent(
        principal: AuthenticatedPrincipal?,
        scope: BranchScope,
        originalActorId: DomainId,
        operationId: OperationId,
        requireGrant: Boolean,
    ): ResultRecoveryDecision {
        fun deny(reason: AuthorizationDenial): ResultRecoveryDecision = ResultRecoveryDecision.Denied(reason)
        if (principal == null) return deny(AuthorizationDenial.UNAUTHENTICATED)
        if (principal !is LocalEmployeePrincipal) return deny(AuthorizationDenial.WRONG_PRINCIPAL_KIND)
        if (principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) {
            return deny(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED)
        }
        if (principal.scope != scope) return deny(AuthorizationDenial.WRONG_SCOPE)
        val snapshot = try {
            provider.current(principal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return deny(AuthorizationDenial.AUTHORITY_UNAVAILABLE)
        } ?: return deny(AuthorizationDenial.AUTHORITY_UNAVAILABLE)
        AuthorizationEvaluator.currentIdentityFailure(principal, snapshot)?.let { return deny(it) }
        val requirement = resultRecoveryRequirement(scope, originalActorId, operationId)
        if (AuthorizationEvaluator.matchingGrants(principal, snapshot, requirement).any { it.effect == GrantEffect.DENY }) {
            return deny(AuthorizationDenial.EXPLICIT_DENY)
        }
        if (requireGrant) when (val decision = AuthorizationEvaluator.evaluate(principal, snapshot, requirement)) {
            is AuthorizationDecision.Denied -> return deny(decision.reason)
            is AuthorizationDecision.Allowed -> Unit
        }
        if (principal.actorId == originalActorId) {
            return ResultRecoveryDecision.Allowed(
                scope, operationId, originalActorId, principal.actorId, ResultRecoveryAccess.ORIGINAL_ACTOR,
            )
        }
        when (val decision = AuthorizationEvaluator.evaluate(principal, snapshot, requirement)) {
            is AuthorizationDecision.Denied -> return deny(decision.reason)
            is AuthorizationDecision.Allowed -> Unit
        }
        val recorded = try {
            audit.record(ResultRecoveryAuditEvent(scope, operationId, originalActorId, principal.actorId, snapshot.policyRevision))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!recorded) return deny(AuthorizationDenial.AUTHORITY_UNAVAILABLE)
        return ResultRecoveryDecision.Allowed(
            scope, operationId, originalActorId, principal.actorId, ResultRecoveryAccess.SUPERVISOR_AUDITED,
        )
    }
}

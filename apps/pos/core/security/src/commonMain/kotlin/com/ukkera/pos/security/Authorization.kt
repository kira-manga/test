package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException

public enum class PermissionVerb {
    PERFORM,
    REQUEST,
    APPROVE,
    ADMINISTER,
    EXPORT,
}

public enum class RequiredLimits {
    NONE,
    AMOUNT,
    AMOUNT_AND_PERCENTAGE,
}

/**
 * No wildcard permission and no unknown-action allow path. The realm check cannot be overridden
 * by a role grant: a central administrator/device/service can never become a branch cashier.
 */
public enum class SecurityAction(
    public val requiredLimits: RequiredLimits = RequiredLimits.NONE,
    public val ownerPrivate: Boolean = false,
    public val requiresBinding: Boolean = true,
) {
    ORDER_CREATE,
    ORDER_READ(ownerPrivate = true),
    ORDER_EDIT(ownerPrivate = true),
    ORDER_HANDOFF(ownerPrivate = true),
    ORDER_REASSIGN,
    DISCOUNT(RequiredLimits.AMOUNT_AND_PERCENTAGE, ownerPrivate = true),
    PRICE_OVERRIDE(RequiredLimits.AMOUNT_AND_PERCENTAGE, ownerPrivate = true),
    /** Branch catalog edit, not an owner-private order discount. Exact basis is company policy. */
    BRANCH_PRICE_EDIT(RequiredLimits.AMOUNT_AND_PERCENTAGE),
    CASH_OPEN(RequiredLimits.AMOUNT),
    CASH_MOVE(RequiredLimits.AMOUNT, ownerPrivate = true),
    CASH_TRANSFER(RequiredLimits.AMOUNT, ownerPrivate = true),
    CASH_RECEIVE(RequiredLimits.AMOUNT, ownerPrivate = true),
    CASH_CLOSE(ownerPrivate = true),
    CASH_REVIEW,
    CASH_READ(ownerPrivate = true),
    CASH_CONFIGURE,
    COLLECTION_BEGIN(RequiredLimits.AMOUNT, ownerPrivate = true),
    COLLECTION_ATTEST(RequiredLimits.AMOUNT, ownerPrivate = true),
    COLLECTION_RESOLVE(RequiredLimits.AMOUNT, ownerPrivate = true),
    COLLECTION_FINALIZE(RequiredLimits.AMOUNT, ownerPrivate = true),
    COLLECTION_FINALIZE_ZERO(RequiredLimits.AMOUNT, ownerPrivate = true),
    RETURN_CLAIM,
    RETURN_ACCEPT(RequiredLimits.AMOUNT),
    RETURN_RECEIPT_LOOKUP,
    REFUND_RECORD(RequiredLimits.AMOUNT, ownerPrivate = true),
    REFUND_RESOLVE(RequiredLimits.AMOUNT, ownerPrivate = true),
    INVENTORY_QUANTITY_READ(requiresBinding = false),
    INVENTORY_COST_READ(requiresBinding = false),
    INVENTORY_ADJUST,
    /** Local activation requires separately authenticated company publication, not branch-global creation. */
    CATALOG_ACTIVATE,
    CATALOG_READ(requiresBinding = false),
    /** Employee-attributed configuration history, distinct from cashier price lookup. */
    CATALOG_AUDIT_READ(requiresBinding = false),
    WAREHOUSE_CONFIGURE,
    SUPPLIER_ADMINISTRATION,
    PURCHASING_READ(requiresBinding = false),
    PURCHASING_APPROVE(RequiredLimits.AMOUNT),
    OPENING_INVENTORY_APPROVE(RequiredLimits.AMOUNT),
    TENDER_CONFIGURE,
    KITCHEN_TASK,
    DELIVERY_ASSIGNMENT(ownerPrivate = true),
    BRANCH_REPORT(requiresBinding = false),
    LOCAL_ACCOUNT_ADMINISTRATION,
    DEVICE_ADMINISTRATION,
    OPERATION_RESULT_RECOVERY,
    RECOVERY_COPY,
    RECOVERY_ACTIVATION,
    RECOVERY_RECONCILIATION,
    COMPANY_STRUCTURE,
    COMPANY_PEOPLE,
    COMPANY_CATALOG,
    COMPANY_POLICY,
    COMPANY_REPORT(requiresBinding = false),
    DEVICE_STATUS(requiresBinding = false),
    MASTER_COMMIT,
    CENTRAL_INGESTION,
    /** Distinct supplier drawer cash-out; appended to preserve existing persisted ordinals. */
    SUPPLIER_CASH_SETTLE(RequiredLimits.AMOUNT, ownerPrivate = true),
    SUPPLIER_CASH_RESOLVE(RequiredLimits.AMOUNT, ownerPrivate = true),
    /** Explicit local initial activity activation; existing catalog grants do not imply it. */
    BRANCH_ACTIVITY_ACTIVATE,
    /** Local floor/table configuration; appended without expanding existing catalog grants. */
    FLOOR_CONFIGURE,
    /** Definitions only, never an owner-private bill or a table occupancy grant. */
    FLOOR_READ(requiresBinding = false),
    /** Actual counter goods-receipt attestation; payment/handoff grants do not imply it. */
    CUSTOMER_RECEIPT_RECORD(ownerPrivate = true),
    /** Documentary availability only; no purchasing, receiving or financial approval. */
    PURCHASING_DOCUMENT_CAPTURE,
    /** Independently captured evidence; this grant cannot adopt another employee's pending capture. */
    PURCHASING_DOCUMENT_ATTACH,
    /** Original supplier files can contain private costs; quantity/report grants never imply access. */
    PURCHASING_DOCUMENT_READ(requiresBinding = false),
    /** Linked post-close classification only; no existing review/collection grant implies it. */
    PAYMENT_CLASSIFICATION_CORRECT(RequiredLimits.AMOUNT),
    /** Typed supplier claims only; never receipt, variance approval or payment authority. */
    PURCHASING_INVOICE_CAPTURE,
    /** Exact accepted-goods matching and payable recognition; not variance or settlement authority. */
    PURCHASING_INVOICE_MATCH_RECOGNIZE(RequiredLimits.AMOUNT),
    /**
     * Explicit voluntary acknowledgement that the saved original setup result was delivered.
     * Appended without changing any existing ordinal or expanding an existing grant. This does
     * not complete installation, authenticate an employee, start RUN or perform business go-live.
     */
    SETUP_RESULT_DELIVERY_ACKNOWLEDGE,
    /**
     * Protected read/recovery of the saved original setup result. Appended separately from all
     * existing reads; it discloses only the setup-result projection and never mutates setup,
     * native or business state.
     */
    SETUP_RESULT_READ,
    /**
     * Responsible local business go-live for the saved original installation. Appended as a
     * distinct exact action; installation, delivery acknowledgement, login and RUN never imply it.
     */
    SETUP_BUSINESS_GO_LIVE,
    /**
     * Explicit current setup-configuration settlement: actual activity, currency, timezone,
     * business-day cutoff and legal/tax identity recorded against the saved original. Appended
     * separately from every existing administrator/manager grant and from go-live.
     */
    SETUP_CONFIGURATION_SETTLE,
    /**
     * Explicit current settlement of a complete catalog import manifest whose rows reference
     * actual retained catalog publication operations. This does not activate a catalog itself
     * and does not expand CATALOG_ACTIVATE or any manager grant.
     */
    SETUP_CATALOG_IMPORT_SETTLE,
    /**
     * Explicit current settlement of counted opening stock/value and opening drawer float whose
     * rows reference actual retained stock/cash operations. Never a stock/cash operation grant.
     */
    SETUP_OPENING_SETTLE,
    /** Explicit disclosure of fiscal configuration and opening checkpoint details. */
    SETUP_CHECKPOINTS_READ,
    /**
     * Explicit current local lifecycle authority for intentional STOP and same-original RESUME.
     * Appended without changing an existing ordinal or granting any role by default. The action
     * is authorized only through the narrow protected lifecycle transaction; setup/UI state,
     * cached grants, a plain open command and automatic launch never imply it.
     */
    MASTER_LIFECYCLE,
}

public data class Permission(public val action: SecurityAction, public val verb: PermissionVerb)

/** Caller constructs this from normalized server-computed content, never a body-supplied digest. */
public data class ActionBinding(
    public val documentId: DomainId,
    public val revision: Revision,
    public val payloadDigest: PayloadDigest,
    public val operationId: OperationId? = null,
    public val reasonCode: String,
) {
    init {
        require(reasonCode.length in 1..128 && reasonCode.all { it.isLetterOrDigit() || it in "._-" }) {
            "Use a bounded reason code; private/free-form text belongs in protected evidence"
        }
    }
}

public sealed interface LimitUse {
    public data object NotApplicable : LimitUse

    public data class Monetary(public val amount: Money) : LimitUse {
        init {
            require(amount.minor >= 0)
        }
    }

    /** The authoritative eligible base, not a percentage asserted by a client. */
    public data class Proportional(public val amount: Money, public val baseAmount: Money) : LimitUse {
        init {
            require(amount.currency == baseAmount.currency)
            require(amount.minor >= 0 && baseAmount.minor > 0)
        }
    }
}

/** An explicitly activated absence of a monetary ceiling, still restricted to ONE currency. */
public data class NoMonetaryCeiling(public val currency: Currency)

public data class PermissionLimits(
    public val maximumAmount: Money? = null,
    public val maximumPercentageBasisPoints: Int? = null,
    public val noMonetaryCeiling: NoMonetaryCeiling? = null,
) {
    init {
        require(maximumAmount == null || maximumAmount.minor >= 0)
        require(maximumPercentageBasisPoints == null || maximumPercentageBasisPoints in 0..10_000)
        require(maximumAmount == null || noMonetaryCeiling == null) { "Choose a finite or explicit absent monetary ceiling, not both" }
    }
}

public enum class GrantEffect {
    ALLOW,
    DENY,
}

public enum class OwnerAccess {
    OWN_ONLY,
    ANY_OWNER,
}

public sealed interface OwnerConstraint {
    public data object Unrestricted : OwnerConstraint
    public data class OwnerPrivate(public val ownerId: DomainId) : OwnerConstraint
}

/**
 * Effective, activated grants are loaded only from the trusted policy store. A delegated grant
 * must carry its ceiling; absence of a limit never means infinity. One complete grant must cover
 * an action: limits from unrelated grants cannot be combined into a larger permission.
 */
public class PermissionGrant(
    public val permission: Permission,
    public val scope: TenantScope,
    public val effect: GrantEffect,
    public val limits: PermissionLimits = PermissionLimits(),
    public val ownerAccess: OwnerAccess = OwnerAccess.OWN_ONLY,
    public val namedSubjectId: DomainId? = null,
    targetIds: Set<DomainId>? = null,
    public val delegationCeiling: PermissionLimits? = null,
) {
    init {
        require(targetIds == null || targetIds.size in 1..256)
    }

    public val targetIds: Set<DomainId>? = targetIds?.toSet()
}

public data class AuthorizationRequirement(
    public val permission: Permission,
    public val scope: TenantScope,
    public val binding: ActionBinding? = null,
    public val limitUse: LimitUse = LimitUse.NotApplicable,
    public val owner: OwnerConstraint = OwnerConstraint.Unrestricted,
)

/**
 * Snapshot read in the same serialized transaction as the protected operation. All status fields
 * are explicit. A policy preflight outside that transaction is not commit authorization.
 */
public class AuthorizationSnapshot(
    public val subjectId: DomainId,
    public val kind: PrincipalKind,
    public val scope: TenantScope,
    public val authenticationId: DomainId,
    public val generation: SessionGeneration,
    public val deviceId: DomainId?,
    public val admissionRevision: Revision?,
    public val policyRevision: Revision,
    public val accountEnabled: Boolean,
    public val sessionActive: Boolean,
    public val deviceAdmitted: Boolean,
    public val authenticationPurpose: AuthenticationPurpose,
    grants: List<PermissionGrant>,
) {
    init {
        require(grants.size <= 1024) { "Effective policy exceeds supported bounds" }
    }

    public val grants: List<PermissionGrant> = grants.toList()
}

public fun interface CurrentAuthorizationProvider {
    /**
     * Resolve CURRENT durable account/session/token/device/policy state, not the principal's
     * cached sign-in claims. Expired tokens, pending refresh ambiguity and unavailable storage
     * return no active session. IO/key errors preserve evidence and fail closed.
     */
    public suspend fun current(principal: AuthenticatedPrincipal): AuthorizationSnapshot?
}

public object DenyAllAuthorizationProvider : CurrentAuthorizationProvider {
    override suspend fun current(principal: AuthenticatedPrincipal): AuthorizationSnapshot? = null
}

public enum class AuthorizationDenial {
    UNAUTHENTICATED,
    AUTHORITY_UNAVAILABLE,
    STALE_AUTHENTICATION,
    ACCOUNT_DISABLED,
    SESSION_REVOKED,
    DEVICE_NOT_ADMITTED,
    WRONG_SCOPE,
    WRONG_PRINCIPAL_KIND,
    INVALID_REQUIREMENT,
    PERMISSION_DENIED,
    EXPLICIT_DENY,
    OWNER_REQUIRED,
    LIMIT_REQUIRED,
    LIMIT_EXCEEDED,
    NAMED_DELEGATION_REQUIRED,
    ASSIGNMENT_REQUIRED,
    PRIVILEGED_AUTHENTICATION_REQUIRED,
}

/**
 * A transaction-local proof, not a reusable bearer/approval. Pure domain engines compare the
 * complete requirement and actor via [isBoundTo]. Reusing it in another transaction is prohibited:
 * the gateway must call AuthorizationPolicy again to recheck revocation and policy.
 */
public class AuthorizedAction private constructor(
    public val actorId: DomainId,
    public val principalKind: PrincipalKind,
    public val requirement: AuthorizationRequirement,
    public val policyRevision: Revision,
    public val authenticationId: DomainId,
    public val generation: SessionGeneration,
    public val approvalId: DomainId?,
    /** Actual approver checked by approval consumption; null for direct authorization. */
    public val approverId: DomainId?,
    /** Current policy revision checked for [approverId], not a durable commit receipt. */
    public val approverPolicyRevision: Revision?,
) {
    init {
        require((approvalId == null) == (approverId == null) &&
            (approverId == null) == (approverPolicyRevision == null))
    }

    public fun isBoundTo(
        expected: AuthorizationRequirement,
        expectedActorId: DomainId = actorId,
    ): Boolean = actorId == expectedActorId && requirement == expected

    internal companion object {
        internal fun issue(
            principal: AuthenticatedPrincipal,
            requirement: AuthorizationRequirement,
            snapshot: AuthorizationSnapshot,
        ): AuthorizedAction = AuthorizedAction(
            principal.subjectId, principal.kind, requirement, snapshot.policyRevision,
            principal.authenticationId, principal.generation, null, null, null,
        )

        internal fun approved(
            request: AuthorizedAction,
            consumed: ActionApprovalRecord,
        ): AuthorizedAction {
            check(consumed.consumedByOperationId != null &&
                consumed.consumedByOperationId == consumed.requirement.binding?.operationId &&
                request.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                request.policyRevision == consumed.requestorPolicyRevision &&
                request.isBoundTo(consumed.requirement.copy(
                    permission = consumed.requirement.permission.copy(verb = PermissionVerb.REQUEST)), consumed.requestorId))
            return AuthorizedAction(
                request.actorId, request.principalKind, consumed.requirement, request.policyRevision,
                request.authenticationId, request.generation, consumed.approvalId,
                consumed.approverId, consumed.approverPolicyRevision,
            )
        }
    }
}

public sealed interface AuthorizationDecision {
    public data class Allowed(public val authorization: AuthorizedAction) : AuthorizationDecision
    public data class Denied(public val reason: AuthorizationDenial) : AuthorizationDecision
}

public class AuthorizationPolicy(private val provider: CurrentAuthorizationProvider) {
    /**
     * Identity-only check in the current writer/read transaction. Null is NOT permission to read a
     * result or perform an action, and is not a reusable proof. A new typed operation must still
     * call [authorize]; an existing/absent outcome lookup must still use ResultRecoveryPolicy.
     * Privileged/recovery identities may be valid here while their unrelated actions remain denied.
     */
    public suspend fun currentIdentityDenial(principal: AuthenticatedPrincipal?): AuthorizationDenial? {
        if (principal == null) return AuthorizationDenial.UNAUTHENTICATED
        val snapshot = try {
            provider.current(principal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AuthorizationDenial.AUTHORITY_UNAVAILABLE
        } ?: return AuthorizationDenial.AUTHORITY_UNAVAILABLE
        return AuthorizationEvaluator.currentIdentityFailure(principal, snapshot)
    }

    public suspend fun authorize(
        principal: AuthenticatedPrincipal?,
        requirement: AuthorizationRequirement,
    ): AuthorizationDecision {
        if (principal == null) return AuthorizationDecision.Denied(AuthorizationDenial.UNAUTHENTICATED)
        val snapshot = try {
            provider.current(principal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AuthorizationDecision.Denied(AuthorizationDenial.AUTHORITY_UNAVAILABLE)
        } ?: return AuthorizationDecision.Denied(AuthorizationDenial.AUTHORITY_UNAVAILABLE)
        return AuthorizationEvaluator.evaluate(principal, snapshot, requirement)
    }
}

/** Pure evaluator for preloaded transactional snapshots; it never authenticates request DTOs. */
public object AuthorizationEvaluator {
    public fun evaluate(
        principal: AuthenticatedPrincipal,
        snapshot: AuthorizationSnapshot,
        requirement: AuthorizationRequirement,
    ): AuthorizationDecision {
        fun deny(reason: AuthorizationDenial): AuthorizationDecision = AuthorizationDecision.Denied(reason)
        currentIdentityFailure(principal, snapshot)?.let { return deny(it) }
        if (!principal.scope.contains(requirement.scope)) return deny(AuthorizationDenial.WRONG_SCOPE)
        if (!realmAllows(principal, requirement.permission)) return deny(AuthorizationDenial.WRONG_PRINCIPAL_KIND)
        if (principal is LocalEmployeePrincipal && !purposeAllows(principal, requirement.permission)) {
            return deny(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED)
        }

        val action = requirement.permission.action
        if (action.requiresBinding && requirement.binding == null) return deny(AuthorizationDenial.INVALID_REQUIREMENT)
        if (action.ownerPrivate && requirement.owner !is OwnerConstraint.OwnerPrivate) {
            return deny(AuthorizationDenial.INVALID_REQUIREMENT)
        }
        when (action.requiredLimits) {
            RequiredLimits.NONE -> Unit
            RequiredLimits.AMOUNT -> if (requirement.limitUse == LimitUse.NotApplicable) {
                return deny(AuthorizationDenial.LIMIT_REQUIRED)
            }
            RequiredLimits.AMOUNT_AND_PERCENTAGE -> if (requirement.limitUse !is LimitUse.Proportional) {
                return deny(AuthorizationDenial.LIMIT_REQUIRED)
            }
        }
        if (requirement.permission.verb == PermissionVerb.APPROVE) {
            val local = principal as? LocalEmployeePrincipal
            if (local != null && local.authenticationPurpose == AuthenticationPurpose.INTERACTIVE) {
                return deny(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED)
            }
        }
        if (action == SecurityAction.RECOVERY_ACTIVATION &&
            (principal as? LocalEmployeePrincipal)?.authenticationPurpose != AuthenticationPurpose.PREPARED_LOCAL_RECOVERY
        ) {
            return deny(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED)
        }
        val matching = matchingGrants(principal, snapshot, requirement)
        if (matching.any { it.effect == GrantEffect.DENY }) return deny(AuthorizationDenial.EXPLICIT_DENY)
        val allows = matching.filter { it.effect == GrantEffect.ALLOW }
        if (allows.isEmpty()) return deny(AuthorizationDenial.PERMISSION_DENIED)
        var failure = AuthorizationDenial.PERMISSION_DENIED
        for (grant in allows) {
            if (action == SecurityAction.KITCHEN_TASK && grant.targetIds == null) {
                failure = AuthorizationDenial.ASSIGNMENT_REQUIRED
                continue
            }
            if (requiresNamedDelegation(action) &&
                (grant.namedSubjectId != principal.subjectId || grant.targetIds == null)
            ) {
                failure = AuthorizationDenial.NAMED_DELEGATION_REQUIRED
                continue
            }
            val owner = requirement.owner
            if (owner is OwnerConstraint.OwnerPrivate && owner.ownerId != principal.subjectId &&
                grant.ownerAccess != OwnerAccess.ANY_OWNER
            ) {
                failure = AuthorizationDenial.OWNER_REQUIRED
                continue
            }
            val bounds = limitFailure(grant.limits, requirement.limitUse)
                ?: grant.delegationCeiling?.let { limitFailure(it, requirement.limitUse) }
            if (bounds != null) {
                failure = bounds
                continue
            }
            return AuthorizationDecision.Allowed(AuthorizedAction.issue(principal, requirement, snapshot))
        }
        return deny(failure)
    }

    /**
     * Pure historical grant decision for an already verified local session/account/admission
     * cut. It constructs only an ephemeral principal/snapshot and returns a boolean; no
     * authority object escapes. Callers must still prove the session/currentness facts against
     * their own historical evidence. Never use this as a request authentication entry point.
     */
    public fun historicalLocalEmployeeAllows(
        actorId: DomainId,
        scope: BranchScope,
        deviceId: DomainId,
        sessionId: DomainId,
        sessionGeneration: SessionGeneration,
        admissionRevision: Revision,
        authenticationPurpose: AuthenticationPurpose,
        policyRevision: Revision,
        accountEnabled: Boolean,
        credentialPresent: Boolean,
        sessionActive: Boolean,
        admissionAdmitted: Boolean,
        grants: List<PermissionGrant>,
        requirement: AuthorizationRequirement,
    ): Boolean {
        if (!credentialPresent) return false
        val principal = LocalEmployeePrincipal.verified(VerifiedLocalIdentity(
            actorId, scope, deviceId, sessionId, sessionGeneration, admissionRevision, authenticationPurpose))
        val snapshot = AuthorizationSnapshot(actorId, PrincipalKind.LOCAL_EMPLOYEE, scope, sessionId,
            sessionGeneration, deviceId, admissionRevision, policyRevision, accountEnabled, sessionActive,
            admissionAdmitted, authenticationPurpose, grants)
        return evaluate(principal, snapshot, requirement) is AuthorizationDecision.Allowed
    }

    internal fun currentIdentityFailure(
        principal: AuthenticatedPrincipal,
        snapshot: AuthorizationSnapshot,
    ): AuthorizationDenial? {
        if (principal.generation.value == Long.MAX_VALUE) return AuthorizationDenial.STALE_AUTHENTICATION
        if (snapshot.subjectId != principal.subjectId || snapshot.kind != principal.kind ||
            snapshot.scope != principal.scope || snapshot.authenticationId != principal.authenticationId ||
            snapshot.generation != principal.generation || snapshot.deviceId != principal.deviceId ||
            snapshot.admissionRevision != principal.admissionRevision
        ) return AuthorizationDenial.STALE_AUTHENTICATION
        if (!snapshot.accountEnabled) return AuthorizationDenial.ACCOUNT_DISABLED
        if (!snapshot.sessionActive) return AuthorizationDenial.SESSION_REVOKED
        if (principal.kind != PrincipalKind.CENTRAL_HUMAN && !snapshot.deviceAdmitted) {
            return AuthorizationDenial.DEVICE_NOT_ADMITTED
        }
        val purpose = when (principal) {
            is LocalEmployeePrincipal -> principal.authenticationPurpose
            is CentralHumanPrincipal -> AuthenticationPurpose.INTERACTIVE
            is BranchServicePrincipal, is AdmittedDevicePrincipal -> AuthenticationPurpose.SERVICE
        }
        if (snapshot.authenticationPurpose != purpose) {
            return AuthorizationDenial.STALE_AUTHENTICATION
        }
        return null
    }

    internal fun matchingGrants(
        principal: AuthenticatedPrincipal,
        snapshot: AuthorizationSnapshot,
        requirement: AuthorizationRequirement,
    ): List<PermissionGrant> = snapshot.grants.filter {
        grantMatches(it, principal.subjectId, requirement.permission, requirement.scope, requirement.binding?.documentId)
    }

    private fun grantMatches(grant: PermissionGrant, subjectId: DomainId, permission: Permission,
        scope: TenantScope, targetId: DomainId?): Boolean = grant.permission == permission && grant.scope.contains(scope) &&
        (grant.namedSubjectId == null || grant.namedSubjectId == subjectId) &&
        (grant.targetIds == null || targetId != null && targetId in grant.targetIds)

    /**
     * Potential recipient of this exact order, not login/commit authorization. Receiving makes
     * the actor its own owner; ORDER_HANDOFF has no monetary/delegation limit. Uses the SAME
     * matching predicate and explicit-deny precedence as evaluate(), without fabricating a
     * principal, session or AuthorizedAction. Protected callers separately require enabled
     * current account/credential/configuration; actual acceptance uses evaluate() normally.
     */
    public fun permitsLocalHandoffRecipient(policy: ActivatedLocalPolicy, scope: BranchScope, orderId: DomainId): Boolean {
        if (policy.scope != scope) return false
        val matches = policy.grants.filter { grantMatches(it, policy.actorId,
            Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM), scope, orderId) }
        return matches.none { it.effect == GrantEffect.DENY } && matches.any { it.effect == GrantEffect.ALLOW }
    }

    /** An exact existing target for an empty owner-private read; this does not issue a grant. */
    public fun localHandoffInboxAuthorizationTarget(policy: ActivatedLocalPolicy, scope: BranchScope): DomainId? {
        if (permitsLocalHandoffRecipient(policy, scope, policy.actorId)) return policy.actorId
        return policy.grants.asSequence().filter { it.permission == Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM) &&
            it.effect == GrantEffect.ALLOW && it.scope.contains(scope) && (it.namedSubjectId == null || it.namedSubjectId == policy.actorId) }
            .flatMap { it.targetIds.orEmpty().asSequence() }.distinct().sortedBy { it.value }
            .firstOrNull { permitsLocalHandoffRecipient(policy, scope, it) }
    }

    /**
     * Pure historical grant comparison for an already joined original interactive employee.
     * This does not authenticate, reconstruct a principal, invoke a current provider or issue
     * AuthorizedAction. The native history consumer must separately prove the exact historical
     * account, policy, admission, immutable issuance, active session and original observation.
     * Approval and recovery authentication are deliberately outside this comparison.
     */
    public fun permitsHistoricalInteractiveLocalRequirement(
        policy: ActivatedLocalPolicy,
        actorId: DomainId,
        scope: BranchScope,
        requirement: AuthorizationRequirement,
    ): Boolean {
        if (requirement.permission.verb == PermissionVerb.APPROVE ||
            requirement.permission.action == SecurityAction.RECOVERY_ACTIVATION) return false
        return permitsHistoricalLocalRequirement(policy, actorId, scope, requirement)
    }

    /**
     * Pure original-actor recovery comparison, not current authentication or a recovery
     * decision. The reader first proves the actual interactive account/session/admission,
     * immutable issuance, observations and original actor. Uses live recovery's exact query
     * binding and deny predicate; it cannot qualify a supervisor or issue Allowed.
     */
    public fun permitsHistoricalOriginalActorResultRecovery(policy: ActivatedLocalPolicy,
        actorId: DomainId, scope: BranchScope, operationId: OperationId, originalActorId: DomainId): Boolean {
        if (policy.actorId != actorId || policy.scope != scope || actorId != originalActorId) return false
        val requirement = resultRecoveryRequirement(scope, originalActorId, operationId)
        return policy.grants.none { it.effect == GrantEffect.DENY &&
            grantMatches(it, actorId, requirement.permission, requirement.scope, requirement.binding?.documentId) }
    }

    /** A comparison ONLY after the history reader joins the actual private MATCH attempt
     * and both original humans. It creates no privileged principal, session or action. */
    public fun permitsHistoricalPrivilegedLocalApproval(
        policy: ActivatedLocalPolicy,
        actorId: DomainId,
        scope: BranchScope,
        requirement: AuthorizationRequirement,
    ): Boolean {
        if (requirement.permission.verb != PermissionVerb.APPROVE || requirement.permission.action in setOf(
                SecurityAction.RECOVERY_COPY, SecurityAction.RECOVERY_ACTIVATION, SecurityAction.RECOVERY_RECONCILIATION)) return false
        return permitsHistoricalLocalRequirement(policy, actorId, scope, requirement)
    }

    private fun permitsHistoricalLocalRequirement(policy: ActivatedLocalPolicy, actorId: DomainId,
        scope: BranchScope, requirement: AuthorizationRequirement): Boolean {
        if (policy.scope != scope || policy.actorId != actorId || !scope.contains(requirement.scope) ||
            !localRealmAllows(requirement.permission.action)) return false
        val action = requirement.permission.action
        if (action.requiresBinding && requirement.binding == null ||
            action.ownerPrivate && requirement.owner !is OwnerConstraint.OwnerPrivate) return false
        if (action.requiredLimits == RequiredLimits.AMOUNT && requirement.limitUse == LimitUse.NotApplicable ||
            action.requiredLimits == RequiredLimits.AMOUNT_AND_PERCENTAGE && requirement.limitUse !is LimitUse.Proportional) return false
        val matches = policy.grants.filter {
            grantMatches(it, actorId, requirement.permission, requirement.scope, requirement.binding?.documentId)
        }
        if (matches.any { it.effect == GrantEffect.DENY }) return false
        return matches.any { grant ->
            grant.effect == GrantEffect.ALLOW &&
                (action != SecurityAction.KITCHEN_TASK || grant.targetIds != null) &&
                (!requiresNamedDelegation(action) || grant.namedSubjectId == actorId && grant.targetIds != null) &&
                (requirement.owner !is OwnerConstraint.OwnerPrivate || requirement.owner.ownerId == actorId ||
                    grant.ownerAccess == OwnerAccess.ANY_OWNER) &&
                limitFailure(grant.limits, requirement.limitUse) == null &&
                (grant.delegationCeiling?.let { limitFailure(it, requirement.limitUse) } == null)
        }
    }

    internal fun limitFailure(limits: PermissionLimits, use: LimitUse): AuthorizationDenial? {
        if (use == LimitUse.NotApplicable) return null
        val amount = when (use) {
            is LimitUse.Monetary -> use.amount
            is LimitUse.Proportional -> use.amount
            LimitUse.NotApplicable -> return null
        }
        val maximum = limits.maximumAmount
        if (maximum != null) {
            if (maximum.currency != amount.currency || amount.minor > maximum.minor) return AuthorizationDenial.LIMIT_EXCEEDED
        } else {
            val explicit = limits.noMonetaryCeiling ?: return AuthorizationDenial.LIMIT_REQUIRED
            if (explicit.currency != amount.currency) return AuthorizationDenial.LIMIT_EXCEEDED
        }
        if (use is LimitUse.Proportional) {
            val basisPoints = limits.maximumPercentageBasisPoints ?: return AuthorizationDenial.LIMIT_REQUIRED
            // Exact floor(base * bp / 10000) without overflow, floating point or rounding up.
            val base = use.baseAmount.minor
            val ceiling = (base / 10_000) * basisPoints + ((base % 10_000) * basisPoints) / 10_000
            if (amount.minor > ceiling) return AuthorizationDenial.LIMIT_EXCEEDED
        }
        return null
    }

    private fun requiresNamedDelegation(action: SecurityAction): Boolean =
        action == SecurityAction.RECOVERY_ACTIVATION || action == SecurityAction.RECOVERY_RECONCILIATION

    private fun purposeAllows(principal: LocalEmployeePrincipal, permission: Permission): Boolean =
        when (principal.authenticationPurpose) {
            AuthenticationPurpose.INTERACTIVE -> permission.verb != PermissionVerb.APPROVE
            AuthenticationPurpose.PRIVILEGED_APPROVAL -> permission.verb == PermissionVerb.APPROVE
            AuthenticationPurpose.PREPARED_LOCAL_RECOVERY -> permission.action in setOf(
                SecurityAction.RECOVERY_COPY, SecurityAction.RECOVERY_ACTIVATION, SecurityAction.RECOVERY_RECONCILIATION,
            )
            AuthenticationPurpose.SERVICE -> false
        }

    private fun realmAllows(principal: AuthenticatedPrincipal, permission: Permission): Boolean {
        val action = permission.action
        val central = action in setOf(
            SecurityAction.COMPANY_STRUCTURE, SecurityAction.COMPANY_PEOPLE, SecurityAction.COMPANY_CATALOG,
            SecurityAction.COMPANY_POLICY, SecurityAction.COMPANY_REPORT,
        )
        return when (principal) {
            is CentralHumanPrincipal -> central
            is LocalEmployeePrincipal -> localRealmAllows(action)
            is AdmittedDevicePrincipal ->
                action == SecurityAction.DEVICE_STATUS && permission.verb == PermissionVerb.PERFORM
            is BranchServicePrincipal -> permission.verb == PermissionVerb.PERFORM && when (principal.servicePurpose) {
                BranchServicePurpose.MASTER -> action == SecurityAction.MASTER_COMMIT
                BranchServicePurpose.CENTRAL_INGESTION -> action == SecurityAction.CENTRAL_INGESTION
                BranchServicePurpose.KITCHEN_DISPLAY -> action == SecurityAction.KITCHEN_TASK
                BranchServicePurpose.RECOVERY_COPY_PROVIDER -> action == SecurityAction.RECOVERY_COPY
            }
        }
    }

    internal fun localRealmAllows(action: SecurityAction): Boolean = action !in setOf(
        SecurityAction.COMPANY_STRUCTURE, SecurityAction.COMPANY_PEOPLE, SecurityAction.COMPANY_CATALOG,
        SecurityAction.COMPANY_POLICY, SecurityAction.COMPANY_REPORT, SecurityAction.DEVICE_STATUS,
        SecurityAction.MASTER_COMMIT, SecurityAction.CENTRAL_INGESTION,
    )
}

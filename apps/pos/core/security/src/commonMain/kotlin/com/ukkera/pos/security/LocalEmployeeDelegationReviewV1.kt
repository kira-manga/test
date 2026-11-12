package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Negative preflight categories. None is a durable result or a native creation proof. */
public enum class LocalEmployeeDelegationFailureV1 {
    ORIGINAL_CREATOR_MISMATCH,
    INTERACTIVE_EMPLOYEE_REQUIRED,
    CURRENT_ADMINISTRATION_REQUIRED,
    EXPLICIT_DENY,
    DELEGATION_CEILING_REQUIRED,
    GRANT_EXPANSION,
}

/**
 * Shared pure review of the GRANTS in one complete proposed creation. A passing review is not
 * account creation authorization: it does not qualify configuration/approval-policy delegation,
 * authenticate a supplied snapshot, establish absence, prepare native history or settle a writer.
 * The actual writer must supply its current protected snapshot and repeat this review there.
 * Company-head enrollment deliberately retains its separate authority path.
 */
public object LocalEmployeeDelegationReviewV1 {
    public fun failure(principal: LocalEmployeePrincipal, snapshot: AuthorizationSnapshot,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeDelegationFailureV1? {
        if (original.operatorId != principal.actorId) return LocalEmployeeDelegationFailureV1.ORIGINAL_CREATOR_MISMATCH
        if (principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE)
            return LocalEmployeeDelegationFailureV1.INTERACTIVE_EMPLOYEE_REQUIRED
        if (principal.scope != original.scope || AuthorizationEvaluator.evaluate(principal, snapshot,
                original.authorizationRequirement()) !is AuthorizationDecision.Allowed)
            return LocalEmployeeDelegationFailureV1.CURRENT_ADMINISTRATION_REQUIRED

        return grantsFailure(snapshot.grants, principal.actorId, original)
    }

    /** Pure historical policy comparison. The caller must independently join the real
     * chronological account, policy, admission and interactive issuance at the event cut.
     * A supplied/decoded policy cannot authenticate a creator or grant a creation permit. */
    public fun historicalFailure(policy: ActivatedLocalPolicy, operatorId: DomainId,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeDelegationFailureV1? {
        if (original.operatorId != operatorId) return LocalEmployeeDelegationFailureV1.ORIGINAL_CREATOR_MISMATCH
        if (policy.scope != original.scope || policy.actorId != operatorId ||
            !AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, operatorId, original.scope,
                original.authorizationRequirement())) return LocalEmployeeDelegationFailureV1.CURRENT_ADMINISTRATION_REQUIRED
        return grantsFailure(policy.grants, operatorId, original)
    }

    private fun grantsFailure(grants: List<PermissionGrant>, operatorId: DomainId,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeDelegationFailureV1? {
        val proposed = original.copyRequestedDefinition().definition.policy
        val current = grants.filter { it.scope.contains(original.scope) &&
            (it.namedSubjectId == null || it.namedSubjectId == operatorId) }
        for (grant in proposed.grants) {
            // Requested DENYs are preserved in the complete request and confer no new right.
            if (grant.effect == GrantEffect.DENY) continue
            if (!AuthorizationEvaluator.localRealmAllows(grant.permission.action))
                return LocalEmployeeDelegationFailureV1.GRANT_EXPANSION
            val matching = current.filter { it.permission == grant.permission }
            // An overlapping current DENY cannot be evaded with a different ALLOW grant,
            // a recipient identity, an ownership qualifier or a partly intersecting target set.
            if (matching.any { it.effect == GrantEffect.DENY && targetsOverlap(it, grant) })
                return LocalEmployeeDelegationFailureV1.EXPLICIT_DENY
            val allows = matching.filter { it.effect == GrantEffect.ALLOW }
            if (allows.none { it.delegationCeiling != null })
                return LocalEmployeeDelegationFailureV1.DELEGATION_CEILING_REQUIRED
            if (allows.none { covers(it, grant) }) return LocalEmployeeDelegationFailureV1.GRANT_EXPANSION
        }
        return null
    }

    private fun covers(parent: PermissionGrant, child: PermissionGrant): Boolean {
        val ceiling = parent.delegationCeiling ?: return false
        if (!parent.scope.contains(child.scope) || !targetsContain(parent, child) ||
            child.ownerAccess == OwnerAccess.ANY_OWNER && parent.ownerAccess != OwnerAccess.ANY_OWNER) return false
        val required = child.permission.action.requiredLimits
        if (!limitsContain(parent.limits, child.limits, required) || !limitsContain(ceiling, child.limits, required)) return false
        // A recipient may receive a nontransferable grant. An explicitly supplied onward
        // ceiling must fit this SAME grant and both of the original creator's bounds.
        val onward = child.delegationCeiling ?: return true
        return limitsContain(child.limits, onward, required) && limitsContain(parent.limits, onward, required) &&
            limitsContain(ceiling, onward, required)
    }

    private fun targetsContain(parent: PermissionGrant, child: PermissionGrant): Boolean {
        val parentTargets = parent.targetIds ?: return true
        val childTargets = child.targetIds ?: return false
        return parentTargets.containsAll(childTargets)
    }

    private fun targetsOverlap(left: PermissionGrant, right: PermissionGrant): Boolean {
        val leftTargets = left.targetIds ?: return true
        val rightTargets = right.targetIds ?: return true
        return leftTargets.any { it in rightTargets }
    }

    private fun limitsContain(upper: PermissionLimits, lower: PermissionLimits, required: RequiredLimits): Boolean {
        val amount = lower.maximumAmount
        val noCeiling = lower.noMonetaryCeiling
        if (required != RequiredLimits.NONE || amount != null || noCeiling != null) {
            if (amount != null) {
                val maximum = upper.maximumAmount
                if (maximum != null) {
                    if (maximum.currency != amount.currency || amount.minor > maximum.minor) return false
                } else if (upper.noMonetaryCeiling?.currency != amount.currency) return false
            } else {
                if (noCeiling == null || upper.noMonetaryCeiling?.currency != noCeiling.currency) return false
            }
        }
        if (required == RequiredLimits.AMOUNT_AND_PERCENTAGE || lower.maximumPercentageBasisPoints != null) {
            val lowerPercentage = lower.maximumPercentageBasisPoints ?: return false
            val upperPercentage = upper.maximumPercentageBasisPoints ?: return false
            if (lowerPercentage > upperPercentage) return false
        }
        return true
    }
}

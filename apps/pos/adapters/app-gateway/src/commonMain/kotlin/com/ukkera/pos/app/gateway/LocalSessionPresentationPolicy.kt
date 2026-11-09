package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.TenantScope
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId

/**
 * Value comparison for withdrawing UI input, not a second authorization engine. In particular,
 * equal navigation areas do not establish equal limits, targets, ownership or delegated rights.
 * Revision-only activation does not change these values. Unknown/new actions participate in the
 * full comparison; only the explicit report/cost actions are unrelated to ordinary cashier input.
 */
internal data class LocalSessionPresentationPolicy(
    private val grants: Set<LocalPresentationGrant>,
    private val session: LocalSessionPolicy,
    private val throttle: ThrottlePolicy,
    private val approvalDuration: Long?,
    private val selfApprovalActions: Set<SecurityAction>?,
) {
    fun nextEpochs(previous: LocalSessionPresentationPolicy, epochs: LocalSessionDraftEpochs): LocalSessionDraftEpochs {
        fun advance(value: Long, changed: Boolean): Long {
            if (!changed) return value
            check(value < Long.MAX_VALUE) { "Private input continuity exhausted" }
            return value + 1
        }
        return LocalSessionDraftEpochs(
            advance(epochs.general, this != previous),
            advance(epochs.cashier, cashierInputs() != previous.cashierInputs()),
        )
    }

    private fun cashierInputs(): LocalSessionPresentationPolicy = copy(grants = grants.filterNot {
        it.permission.action in REPORT_ONLY_INPUTS
    }.toSet())

    companion object {
        fun capture(policy: ActivatedLocalPolicy): LocalSessionPresentationPolicy = LocalSessionPresentationPolicy(
            policy.grants.map(LocalPresentationGrant::capture).toSet(), policy.sessionPolicy, policy.throttlePolicy,
            policy.approvalPolicy?.validForMillis, policy.approvalPolicy?.namedSelfApprovalActions,
        )

        private val REPORT_ONLY_INPUTS = setOf(SecurityAction.BRANCH_REPORT, SecurityAction.COMPANY_REPORT,
            SecurityAction.INVENTORY_COST_READ)
    }
}

/** Every grant field participates; reference equality of PermissionGrant would be incorrect. */
internal data class LocalPresentationGrant(
    val permission: Permission,
    val scope: TenantScope,
    val effect: GrantEffect,
    val limits: PermissionLimits,
    val ownerAccess: OwnerAccess,
    val namedSubjectId: DomainId?,
    val targetIds: Set<DomainId>?,
    val delegationCeiling: PermissionLimits?,
) {
    companion object {
        fun capture(grant: PermissionGrant): LocalPresentationGrant = LocalPresentationGrant(grant.permission,
            grant.scope, grant.effect, grant.limits, grant.ownerAccess, grant.namedSubjectId,
            grant.targetIds?.toSet(), grant.delegationCeiling)
    }
}

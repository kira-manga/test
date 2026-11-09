package com.ukkera.pos.database.independent.employeepolicyfocusedv1

import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.TenantScope
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Literal inputs from employee-policy-activation-focused/v1, reviewed candidate v1.1.
 * These are pure value inputs, not provisioned accounts, sessions, active policy or authority.
 * Only mapped grants, limits, identities and caps are represented here. Roles, account enabled
 * state, own report projections and the company discount-approval route need separate bindings.
 */
internal object EmployeePolicyFocusedFrozenFixtures {
    val organization = DomainId("5be25b23-e1bb-443c-a00a-d718af4ce891")
    val company = DomainId("4790cdf0-ed2e-48fc-a04b-4a47e1de4b81")
    val scopeA = BranchScope(organization, company, DomainId("a363f2e6-0a08-41d9-8ee4-b6c26edbde44"))
    val scopeB = BranchScope(organization, company, DomainId("7919c6b2-ab5f-4d9e-9d77-38a7e0ad7d76"))
    val subject = DomainId("e52d7f33-9e34-4cac-8e50-1b4e253c20b2")
    val peer = DomainId("089a2301-314b-41f1-95b4-f716e0d5a470")
    val branchBAccount = DomainId("02821cf9-e73d-408e-8b9c-aab079789407")
    val namedAdministrator = DomainId("24fe1b9a-0351-4fcf-a2fb-bf5d7ae97508")
    val managerWithoutDelegation = DomainId("ae30260e-ef9d-40b8-acda-13a6c8137b1e")
    val saleId = DomainId("533fe22e-276e-4d89-8723-8f68c318dbb8")
    val receiptOperation = OperationId("f9b9c6ac-96d1-45e2-bf2a-8f3194b363c0")

    val devices = listOf(
        DomainId("50e21ba1-085d-457f-9610-2cb9cb4c3ccb"),
        DomainId("f823985e-53f1-4ea8-9bfa-201a3eaa6906"),
        DomainId("de7a7b7c-eedd-4ba4-89ec-bb2b930b6b3f"),
    )
    val originalSessionIds = listOf(
        DomainId("3a6b331a-7fa0-4b7d-9b71-8484e3ef6bf2"),
        DomainId("fc091a94-6c33-45d7-85d3-22ee38c4c70e"),
        DomainId("b4b47573-ee12-44d9-8a2f-a7762d5955c3"),
    )

    data class PolicyInput(
        val actorId: DomainId,
        val revision: Long,
        val sessionCap: Int,
        val discountPerform: Boolean,
        val discountAmountMinor: Long,
        val discountBasisPoints: Int,
        val receiptRecordOwn: Boolean,
        val returnLookup: Boolean,
        val costRead: Boolean,
    )

    val subject11 = PolicyInput(subject, 11, 3, true, 3_000, 2_500, true, true, true)
    val subject12 = PolicyInput(subject, 12, 1, true, 1_000, 1_000, false, true, false)
    val subject13 = PolicyInput(subject, 13, 1, true, 1_500, 1_500, true, false, false)
    val peer41 = PolicyInput(peer, 41, 1, false, 0, 0, true, false, false)
    val peer42 = PolicyInput(peer, 42, 1, false, 0, 0, false, false, false)
    val allPolicyInputs = listOf(subject11, subject12, subject13, peer41, peer42)

    data class DefinitionInput(val next: PolicyInput, val requestId: DomainId, val predecessor: Long)
    val definitionInputs = listOf(
        DefinitionInput(subject12, DomainId("fdba4f07-dcc0-4318-b330-2800c8a33552"), 11),
        DefinitionInput(subject13, DomainId("a8318dd5-0c6c-45cd-ae75-893a6d53ca2f"), 12),
        DefinitionInput(peer42, DomainId("a8693b0d-0d64-4d8e-bcc5-3e54135446de"), 41),
    )

    // Constructor-only scaffolding: the freeze supplies no throttle numbers. No throttle,
    // authentication-attempt, approval or current-session behavior is asserted from these values.
    private val throttleScaffolding = ThrottlePolicy(3, 1_000L, 900_000L, 120_000L)

    fun policy(input: PolicyInput): ActivatedLocalPolicy = ActivatedLocalPolicy(
        scopeA, input.actorId, Revision(input.revision), listOf(
            grant(input.actorId, SecurityAction.ORDER_CREATE, true),
            grant(input.actorId, SecurityAction.DISCOUNT, input.discountPerform,
                limits = PermissionLimits(egp(input.discountAmountMinor), input.discountBasisPoints)),
            grant(input.actorId, SecurityAction.CUSTOMER_RECEIPT_RECORD, input.receiptRecordOwn),
            grant(input.actorId, SecurityAction.RETURN_RECEIPT_LOOKUP, input.returnLookup),
            grant(input.actorId, SecurityAction.INVENTORY_QUANTITY_READ, true),
            grant(input.actorId, SecurityAction.INVENTORY_COST_READ, input.costRead),
            grant(input.actorId, SecurityAction.BRANCH_REPORT, false, PermissionVerb.EXPORT),
            grant(input.actorId, SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, false, PermissionVerb.ADMINISTER),
            grant(input.actorId, SecurityAction.RECOVERY_ACTIVATION, false),
        ), LocalSessionPolicy(input.sessionCap), throttleScaffolding,
    )

    fun replaceGrants(policy: ActivatedLocalPolicy, grants: List<PermissionGrant>): ActivatedLocalPolicy =
        ActivatedLocalPolicy(policy.scope, policy.actorId, policy.revision, grants,
            policy.sessionPolicy, policy.throttlePolicy, policy.approvalPolicy)

    private fun grant(actorId: DomainId, action: SecurityAction, allow: Boolean,
        verb: PermissionVerb = PermissionVerb.PERFORM, limits: PermissionLimits = PermissionLimits()): PermissionGrant =
        PermissionGrant(Permission(action, verb), scopeA, if (allow) GrantEffect.ALLOW else GrantEffect.DENY,
            limits, OwnerAccess.OWN_ONLY, actorId)

    fun egp(minor: Long): Money = Money(Currency.EGP, minor)

    /**
     * Static authorization selectors ONLY. The zero digest is not an encoded R1/order payload,
     * and these values establish no eligible document, current session or commit authorization.
     */
    fun requirement(action: SecurityAction, verb: PermissionVerb = PermissionVerb.PERFORM,
        scope: TenantScope = scopeA, targetId: DomainId = saleId,
        owner: OwnerConstraint = OwnerConstraint.Unrestricted,
        use: LimitUse = LimitUse.NotApplicable): AuthorizationRequirement = AuthorizationRequirement(
        Permission(action, verb), scope,
        ActionBinding(targetId, Revision(0), PayloadDigest("0".repeat(64)), receiptOperation,
            "policy.focused.selector"), use, owner,
    )

    fun receiptRequirement(ownerId: DomainId = subject): AuthorizationRequirement =
        requirement(SecurityAction.CUSTOMER_RECEIPT_RECORD, owner = OwnerConstraint.OwnerPrivate(ownerId))

    fun discountRequirement(discountMinor: Long, subtotalMinor: Long): AuthorizationRequirement =
        requirement(SecurityAction.DISCOUNT, owner = OwnerConstraint.OwnerPrivate(subject),
            use = LimitUse.Proportional(egp(discountMinor), egp(subtotalMinor)))

    /** Pure historical grant comparison; deliberately supplies no authenticated principal. */
    fun matches(policy: ActivatedLocalPolicy, requirement: AuthorizationRequirement,
        actorId: DomainId = policy.actorId): Boolean =
        AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, actorId, policy.scope, requirement)

    /**
     * F-DELEGATION's named subject-only selectors and finite limits. Revision zero is a
     * constructor coordinate, not an observed administrator-policy revision or activation.
     * The target's initial 2000/20 limits are value-carrier inputs only, not a claim that the
     * real administrator grant has that representation or that a proposed change is permitted.
     * This value is not a delegation approval or an implementation of local policy management.
     */
    fun namedDelegationPolicy(): ActivatedLocalPolicy = ActivatedLocalPolicy(
        scopeA, namedAdministrator, Revision(0), listOf(PermissionGrant(
            Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER),
            scopeA, GrantEffect.ALLOW, PermissionLimits(egp(2_000), 2_000), OwnerAccess.OWN_ONLY,
            namedAdministrator, setOf(subject), PermissionLimits(egp(3_000), 3_000),
        )), LocalSessionPolicy(1), throttleScaffolding,
    )
}

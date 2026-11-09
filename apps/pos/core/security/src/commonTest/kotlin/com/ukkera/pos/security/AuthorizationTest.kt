package com.ukkera.pos.security

import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AuthorizationTest {
    @Test
    fun permissionVerbsAreIndependent(): Unit = runTest {
        val actor = local()
        val required = requirement()
        val state = snapshot(actor, listOf(grant(required)))
        assertIs<AuthorizationDecision.Allowed>(AuthorizationEvaluator.evaluate(actor, state, required))
        for (verb in PermissionVerb.entries.filter { it != PermissionVerb.PERFORM }) {
            assertIs<AuthorizationDecision.Denied>(
                AuthorizationEvaluator.evaluate(actor, state, required.copy(permission = required.permission.copy(verb = verb))),
            )
        }
    }

    @Test
    fun companyAndBranchScopesNeverInheritOrganizationMembership(): Unit = runTest {
        val actor = local()
        val required = requirement()
        for (wrong in listOf(
            branch.copy(organizationId = id(21)),
            branch.copy(companyId = id(22)),
            branch.copy(branchId = id(23)),
        )) {
            val target = required.copy(scope = wrong)
            assertEquals(
                AuthorizationDecision.Denied(AuthorizationDenial.WRONG_SCOPE),
                AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(grant(target))), target),
            )
        }
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.WRONG_SCOPE),
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(grant(required.copy(scope = branch.company())))),
                required.copy(scope = branch.company()),
            ),
        )
    }

    @Test
    fun ownerPrivateResourcesNeedExplicitOtherOwnerGrantAndMetadata(): Unit = runTest {
        val actor = local()
        val required = requirement(ownerId = id(5))
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.OWNER_REQUIRED),
            AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(grant(required))), required),
        )
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(grant(required, ownerAccess = OwnerAccess.ANY_OWNER))), required,
            ),
        )
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.INVALID_REQUIREMENT),
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(grant(required))),
                required.copy(owner = OwnerConstraint.Unrestricted),
            ),
        )
    }

    @Test
    fun explicitDenyWinsRegardlessOfAllowOrder(): Unit = runTest {
        val actor = local()
        val required = requirement()
        val allows = grant(required)
        val denies = grant(required, effect = GrantEffect.DENY)
        for (grants in listOf(listOf(allows, denies), listOf(denies, allows))) {
            assertEquals(
                AuthorizationDecision.Denied(AuthorizationDenial.EXPLICIT_DENY),
                AuthorizationEvaluator.evaluate(actor, snapshot(actor, grants), required),
            )
        }
    }

    @Test
    fun bothAmountAndPercentageMustFitOneGrant(): Unit = runTest {
        val actor = local()
        val required = requirement(
            SecurityAction.DISCOUNT,
            limitUse = LimitUse.Proportional(money(1_500), money(10_000)),
        )
        for (limits in listOf(
            PermissionLimits(money(2_000), 1_000),
            PermissionLimits(money(1_000), 2_000),
        )) {
            assertEquals(
                AuthorizationDecision.Denied(AuthorizationDenial.LIMIT_EXCEEDED),
                AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(grant(required, limits = limits))), required),
            )
        }
        val splitGrants = listOf(
            grant(required, limits = PermissionLimits(money(2_000), 1_000)),
            grant(required, limits = PermissionLimits(money(1_000), 2_000)),
        )
        assertIs<AuthorizationDecision.Denied>(AuthorizationEvaluator.evaluate(actor, snapshot(actor, splitGrants), required))
        val exact = required.copy(limitUse = LimitUse.Proportional(money(1_500), money(10_000)))
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(grant(exact, limits = PermissionLimits(money(1_500), 1_500)))), exact,
            ),
        )
    }

    @Test
    fun omittedLimitsAndUnderdeclaredUseAreNotUnlimited(): Unit = runTest {
        val actor = local()
        val required = requirement(
            SecurityAction.DISCOUNT, limitUse = LimitUse.Proportional(money(1), money(10_000)),
        )
        for (limits in listOf(PermissionLimits(), PermissionLimits(money(100)), PermissionLimits(null, 10_000))) {
            assertEquals(
                AuthorizationDecision.Denied(AuthorizationDenial.LIMIT_REQUIRED),
                AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(grant(required, limits = limits))), required),
            )
        }
        for (use in listOf(LimitUse.NotApplicable, LimitUse.Monetary(money(1)))) {
            assertEquals(
                AuthorizationDecision.Denied(AuthorizationDenial.LIMIT_REQUIRED),
                AuthorizationEvaluator.evaluate(
                    actor, snapshot(actor, listOf(grant(required, limits = PermissionLimits(money(100), 100)))),
                    required.copy(limitUse = use),
                ),
            )
        }
    }

    @Test
    fun percentageCeilingUsesExactFloorWithoutOverflowOrRoundingUp(): Unit = runTest {
        val actor = local()
        val max = requirement(SecurityAction.DISCOUNT, limitUse = LimitUse.Proportional(money(Long.MAX_VALUE), money(Long.MAX_VALUE)))
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(grant(max, limits = PermissionLimits(money(Long.MAX_VALUE), 10_000)))), max,
            ),
        )
        val fractional = max.copy(limitUse = LimitUse.Proportional(money(10), money(9_999)))
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.LIMIT_EXCEEDED),
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(grant(fractional, limits = PermissionLimits(money(10), 10)))), fractional,
            ),
        )
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(grant(fractional, limits = PermissionLimits(money(10), 10)))),
                fractional.copy(limitUse = LimitUse.Proportional(money(9), money(9_999))),
            ),
        )
    }

    @Test
    fun delegationCeilingCannotBeExpandedByEffectiveGrant(): Unit = runTest {
        val actor = local()
        val required = requirement(SecurityAction.CASH_OPEN, limitUse = LimitUse.Monetary(money(500)))
        val delegated = grant(required, limits = PermissionLimits(money(1_000)), delegationCeiling = PermissionLimits(money(499)))
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.LIMIT_EXCEEDED),
            AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(delegated)), required),
        )
        val missing = grant(required, limits = PermissionLimits(money(1_000)), delegationCeiling = PermissionLimits())
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.LIMIT_REQUIRED),
            AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(missing)), required),
        )
    }

    @Test
    fun everyCallRechecksCurrentLocalRevocationGenerationAndAdmission(): Unit = runTest {
        val actor = local()
        val required = requirement()
        var current = snapshot(actor, listOf(grant(required)))
        val policy = AuthorizationPolicy { current }
        assertIs<AuthorizationDecision.Allowed>(policy.authorize(actor, required))
        for ((state, denial) in listOf(
            snapshot(actor, listOf(grant(required)), enabled = false) to AuthorizationDenial.ACCOUNT_DISABLED,
            snapshot(actor, listOf(grant(required)), active = false) to AuthorizationDenial.SESSION_REVOKED,
            snapshot(actor, listOf(grant(required)), admitted = false) to AuthorizationDenial.DEVICE_NOT_ADMITTED,
            snapshot(actor, listOf(grant(required)), generation = SessionGeneration(2)) to AuthorizationDenial.STALE_AUTHENTICATION,
            snapshot(actor, listOf(grant(required)), admissionRevision = Revision(2)) to AuthorizationDenial.STALE_AUTHENTICATION,
            snapshot(actor, listOf(grant(required)), subjectId = id(5)) to AuthorizationDenial.STALE_AUTHENTICATION,
        )) {
            current = state
            assertEquals(AuthorizationDecision.Denied(denial), policy.authorize(actor, required))
        }
        current = snapshot(actor)
        assertEquals(AuthorizationDecision.Denied(AuthorizationDenial.PERMISSION_DENIED), policy.authorize(actor, required))
    }

    @Test
    fun unavailableAuthorityAndNullPrincipalFailClosed(): Unit = runTest {
        val actor = local()
        val required = requirement()
        val policy = AuthorizationPolicy { error("synthetic protected-store failure") }
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.AUTHORITY_UNAVAILABLE), policy.authorize(actor, required),
        )
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.UNAUTHENTICATED), policy.authorize(null, required),
        )
    }

    @Test
    fun activationRequiresNamedTargetedDelegationAndPreparedRecoveryAuthentication(): Unit = runTest {
        val required = requirement(SecurityAction.RECOVERY_ACTIVATION, PermissionVerb.APPROVE)
        val ordinary = local(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL)
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED),
            AuthorizationEvaluator.evaluate(ordinary, snapshot(ordinary, listOf(grant(required))), required),
        )
        val recovery = local(purpose = AuthenticationPurpose.PREPARED_LOCAL_RECOVERY)
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.NAMED_DELEGATION_REQUIRED),
            AuthorizationEvaluator.evaluate(recovery, snapshot(recovery, listOf(grant(required))), required),
        )
        val named = grant(required, namedSubjectId = recovery.actorId, targets = setOf(checkNotNull(required.binding).documentId))
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(recovery, snapshot(recovery, listOf(named)), required),
        )
        assertIs<AuthorizationDecision.Denied>(
            AuthorizationEvaluator.evaluate(
                recovery, snapshot(recovery, listOf(named)),
                required.copy(binding = checkNotNull(required.binding).copy(documentId = id(40))),
            ),
        )
    }

    @Test
    fun authorizedActionIsExactlyBoundButNotAReusableApproval(): Unit = runTest {
        val actor = local()
        val required = requirement()
        val permit = authorized(actor, required)
        assertTrue(permit.isBoundTo(required, actor.actorId))
        assertFalse(permit.isBoundTo(required, id(5)))
        assertFalse(permit.isBoundTo(required.copy(binding = binding("different-lines"))))
        assertFalse(permit.isBoundTo(required.copy(binding = binding().copy(revision = Revision(2)))))
        assertFalse(permit.isBoundTo(required.copy(binding = binding(operationId = op(20)))))
        assertEquals(null, permit.approvalId)
    }

    @Test
    fun recoveryAndApprovalCredentialsCannotBeRepurposedAsInteractiveCashierSessions(): Unit = runTest {
        val sale = requirement(SecurityAction.COLLECTION_ATTEST, limitUse = LimitUse.Monetary(money(100)))
        for (purpose in listOf(AuthenticationPurpose.PRIVILEGED_APPROVAL, AuthenticationPurpose.PREPARED_LOCAL_RECOVERY)) {
            val actor = local(purpose = purpose)
            assertEquals(
                AuthorizationDecision.Denied(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED),
                AuthorizationEvaluator.evaluate(
                    actor, snapshot(actor, listOf(grant(sale, limits = PermissionLimits(money(1_000))))), sale,
                ),
            )
        }
        val approveDiscount = requirement(
            SecurityAction.DISCOUNT, PermissionVerb.APPROVE, limitUse = LimitUse.Proportional(money(100), money(1_000)),
        )
        val prepared = local(purpose = AuthenticationPurpose.PREPARED_LOCAL_RECOVERY)
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED),
            AuthorizationEvaluator.evaluate(
                prepared, snapshot(prepared, listOf(grant(approveDiscount, limits = PermissionLimits(money(1_000), 10_000)))),
                approveDiscount,
            ),
        )
    }

    @Test
    fun kitchenTaskGrantsMustBeTargetScopedRatherThanBranchWide(): Unit = runTest {
        val actor = local()
        val task = requirement(SecurityAction.KITCHEN_TASK)
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.ASSIGNMENT_REQUIRED),
            AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(grant(task))), task),
        )
        val targeted = grant(task, targets = setOf(checkNotNull(task.binding).documentId))
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(targeted)), task),
        )
        assertIs<AuthorizationDecision.Denied>(
            AuthorizationEvaluator.evaluate(
                actor, snapshot(actor, listOf(targeted)), task.copy(binding = binding().copy(documentId = id(99))),
            ),
        )
    }
}

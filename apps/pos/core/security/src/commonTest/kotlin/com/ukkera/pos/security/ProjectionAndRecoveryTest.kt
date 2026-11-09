package com.ukkera.pos.security

import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProjectionAndRecoveryTest {
    @Test
    fun everyOwnerPrivateReadPathDeniesAnotherOwner(): Unit = runTest {
        val actor = local()
        val required = requirement(SecurityAction.ORDER_READ)
        val grants = listOf(grant(required), grant(required.copy(permission = required.permission.copy(verb = PermissionVerb.EXPORT))))
        val policy = ProjectionPolicy(AuthorizationPolicy { snapshot(actor, grants) })
        for (channel in ProjectionChannel.entries) {
            assertEquals(
                ProjectionDecision.Denied(AuthorizationDenial.OWNER_REQUIRED),
                policy.authorize(actor, branch, ProjectionKind.OWN_ACTIVE_ORDER, binding(), id(5), channel),
            )
        }
    }

    @Test
    fun exportIsSeparateAndQuantityProjectionNeverSerializesCostOrPrivateFields(): Unit = runTest {
        val actor = local()
        val required = requirement(SecurityAction.INVENTORY_QUANTITY_READ)
        val policy = ProjectionPolicy(AuthorizationPolicy { snapshot(actor, listOf(grant(required))) })
        val allowed = assertIs<ProjectionDecision.Allowed>(
            policy.authorize(actor, branch, ProjectionKind.INVENTORY_QUANTITY, binding(), null, ProjectionChannel.QUERY),
        )
        val all = ProjectionField.entries.associateWith { "synthetic-value" }
        val selected = allowed.projection.select(all)
        assertTrue(ProjectionField.QUANTITY in selected)
        assertFalse(ProjectionField.UNIT_COST in selected)
        assertFalse(ProjectionField.MARGIN in selected)
        assertFalse(ProjectionField.DELIVERY_CONTACT in selected)
        assertFalse(ProjectionField.TENDER_SUMMARY in selected)
        assertEquals(
            ProjectionDecision.Denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorize(actor, branch, ProjectionKind.INVENTORY_QUANTITY, binding(), null, ProjectionChannel.EXPORT),
        )
        assertEquals(
            ProjectionDecision.Denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorize(actor, branch, ProjectionKind.INVENTORY_COST, binding(), null, ProjectionChannel.QUERY),
        )
    }

    @Test
    fun returnsLookupIsLimitedSameBranchProjectionNotGeneralOtherOwnerAccess(): Unit = runTest {
        val actor = local()
        val required = requirement(SecurityAction.RETURN_RECEIPT_LOOKUP)
        val policy = ProjectionPolicy(AuthorizationPolicy { snapshot(actor, listOf(grant(required))) })
        val allowed = assertIs<ProjectionDecision.Allowed>(
            policy.authorize(actor, branch, ProjectionKind.SAME_BRANCH_RETURN_RECEIPT, binding(), id(5), ProjectionChannel.DIRECT_ID),
        )
        val selected = allowed.projection.select(ProjectionField.entries.associateWith { "fixture" })
        assertTrue(ProjectionField.TENDER_SUMMARY in selected)
        assertFalse(ProjectionField.OWNER in selected)
        assertFalse(ProjectionField.UNIT_COST in selected)
        assertFalse(ProjectionField.DELIVERY_CONTACT in selected)
        assertEquals(
            ProjectionDecision.Denied(AuthorizationDenial.WRONG_SCOPE),
            policy.authorize(
                actor, branch.copy(branchId = id(99)), ProjectionKind.SAME_BRANCH_RETURN_RECEIPT, binding(), id(5),
                ProjectionChannel.DIRECT_ID,
            ),
        )
        assertIs<ProjectionDecision.Denied>(
            policy.authorize(actor, branch, ProjectionKind.OWN_ACTIVE_ORDER, binding(), id(5), ProjectionChannel.DIRECT_ID),
        )
    }

    @Test
    fun subscriptionsAndCachedViewsReauthorizeAndCacheKeysPartitionSessions(): Unit = runTest {
        val actor = local()
        val required = requirement(SecurityAction.ORDER_READ)
        var current = snapshot(actor, listOf(grant(required)))
        val policy = ProjectionPolicy(AuthorizationPolicy { current })
        val first = assertIs<ProjectionDecision.Allowed>(
            policy.authorize(actor, branch, ProjectionKind.OWN_ACTIVE_ORDER, binding(), actor.actorId, ProjectionChannel.CACHED_VIEW),
        )
        current = snapshot(actor, listOf(grant(required)), enabled = false)
        for (channel in listOf(ProjectionChannel.SUBSCRIPTION, ProjectionChannel.CACHED_VIEW)) {
            assertEquals(
                ProjectionDecision.Denied(AuthorizationDenial.ACCOUNT_DISABLED),
                policy.authorize(actor, branch, ProjectionKind.OWN_ACTIVE_ORDER, binding(), actor.actorId, channel),
            )
        }
        val again = local(generation = SessionGeneration(2), sessionId = id(17))
        current = snapshot(again, listOf(grant(required)), policyRevision = Revision(2))
        val next = assertIs<ProjectionDecision.Allowed>(
            policy.authorize(again, branch, ProjectionKind.OWN_ACTIVE_ORDER, binding(), actor.actorId, ProjectionChannel.CACHED_VIEW),
        )
        assertNotEquals(first.projection.cacheKey, next.projection.cacheKey)
    }

    @Test
    fun originalActorCanRecoverWithoutFreshMoneyGrantsButNotWithRevokedSession(): Unit = runTest {
        val actor = local()
        var current = snapshot(actor)
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { current })
        val decision = assertIs<ResultRecoveryDecision.Allowed>(policy.authorize(actor, branch, actor.actorId, op(10)))
        assertEquals(ResultRecoveryAccess.ORIGINAL_ACTOR, decision.access)
        assertEquals(id(4), decision.originalActorId)
        assertIs<AuthorizationDecision.Denied>(
            AuthorizationPolicy { current }.authorize(
                actor, requirement(SecurityAction.COLLECTION_ATTEST, limitUse = LimitUse.Monetary(money(100))),
            ),
        )
        current = snapshot(actor, active = false)
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.SESSION_REVOKED),
            policy.authorize(actor, branch, actor.actorId, op(10)),
        )
    }

    @Test
    fun supervisorNeedsExplicitScopedRecoveryPermissionAndDurableAudit(): Unit = runTest {
        val supervisor = local(id(5))
        val required = requirement(SecurityAction.OPERATION_RESULT_RECOVERY)
        var current = snapshot(supervisor)
        val audits = mutableListOf<ResultRecoveryAuditEvent>()
        val policy = ResultRecoveryPolicy(
            CurrentAuthorizationProvider { current },
            ResultRecoveryAudit { audits.add(it); true },
        )
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorize(supervisor, branch, id(4), op(10)),
        )
        assertTrue(audits.isEmpty())
        current = snapshot(supervisor, listOf(grant(required)))
        val allowed = assertIs<ResultRecoveryDecision.Allowed>(policy.authorize(supervisor, branch, id(4), op(10)))
        assertEquals(ResultRecoveryAccess.SUPERVISOR_AUDITED, allowed.access)
        assertEquals(id(4), allowed.originalActorId)
        assertEquals(id(5), allowed.readingActorId)
        assertEquals(id(4), audits.single().originalActorId)
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.AUTHORITY_UNAVAILABLE),
            ResultRecoveryPolicy(CurrentAuthorizationProvider { current }).authorize(supervisor, branch, id(4), op(10)),
        )
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.WRONG_SCOPE),
            policy.authorize(supervisor, branch.copy(branchId = id(99)), id(4), op(10)),
        )
        assertEquals(1, audits.size)
    }

    @Test
    fun admittedDeviceAndServiceCannotReadHumanPendingOperationOutcomes(): Unit = runTest {
        val factory = TrustedPrincipalFactory(FixtureVerifier(device = VerifiedDeviceIdentity(id(6), branch, Revision(1))))
        val device = checkNotNull(factory.authenticateDevice(DeviceGatewayCredentials(id(8))))
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { snapshot(device) })
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.WRONG_PRINCIPAL_KIND),
            policy.authorize(device, branch, id(6), op(10)),
        )
    }

    @Test
    fun deliveryContactProjectionIsConfinedToTheStoredAssignedCourierAcrossEveryPath(): Unit = runTest {
        val courier = local()
        val required = requirement(SecurityAction.DELIVERY_ASSIGNMENT)
        val grants = listOf(grant(required), grant(required.copy(permission = required.permission.copy(verb = PermissionVerb.EXPORT))))
        val policy = ProjectionPolicy(AuthorizationPolicy { snapshot(courier, grants) })
        for (channel in ProjectionChannel.entries) {
            assertEquals(
                ProjectionDecision.Denied(AuthorizationDenial.OWNER_REQUIRED),
                policy.authorize(courier, branch, ProjectionKind.ASSIGNED_DELIVERY, binding(), id(5), channel),
            )
            assertEquals(
                ProjectionDecision.Denied(AuthorizationDenial.INVALID_REQUIREMENT),
                policy.authorize(courier, branch, ProjectionKind.ASSIGNED_DELIVERY, binding(), null, channel),
            )
        }
        val own = assertIs<ProjectionDecision.Allowed>(
            policy.authorize(courier, branch, ProjectionKind.ASSIGNED_DELIVERY, binding(), courier.actorId, ProjectionChannel.DIRECT_ID),
        )
        assertTrue(ProjectionField.DELIVERY_CONTACT in own.projection.fields)
    }

    @Test
    fun stepUpCredentialCannotExploitOriginalActorRecoveryException(): Unit = runTest {
        for (purpose in listOf(AuthenticationPurpose.PRIVILEGED_APPROVAL, AuthenticationPurpose.PREPARED_LOCAL_RECOVERY)) {
            val actor = local(purpose = purpose)
            val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { snapshot(actor) })
            assertEquals(
                ResultRecoveryDecision.Denied(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED),
                policy.authorize(actor, branch, actor.actorId, op(10)),
            )
        }
    }

    @Test
    fun explicitResultRecoveryDenyAlsoAppliesToTheOriginalActor(): Unit = runTest {
        val actor = local()
        val required = requirement(SecurityAction.OPERATION_RESULT_RECOVERY)
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider {
            snapshot(actor, listOf(grant(required, effect = GrantEffect.DENY)))
        })
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.EXPLICIT_DENY),
            policy.authorize(actor, branch, actor.actorId, op(10)),
        )
    }
}

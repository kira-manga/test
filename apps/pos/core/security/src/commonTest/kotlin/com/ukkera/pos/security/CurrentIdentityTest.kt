package com.ukkera.pos.security

import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class CurrentIdentityTest {
    @Test
    fun validIdentityDoesNotRequirePermissionsOrGrantAnAction(): Unit = runTest {
        val actor = local()
        val policy = AuthorizationPolicy { snapshot(actor) }
        assertNull(policy.currentIdentityDenial(actor))
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorize(actor, requirement()),
        )
    }

    @Test
    fun resultRecoveryDenyDoesNotInvalidateIdentityOrUnrelatedNewOperationGrant(): Unit = runTest {
        val actor = local()
        val edit = requirement()
        val resultRead = requirement(SecurityAction.OPERATION_RESULT_RECOVERY)
        val state = snapshot(actor, listOf(grant(edit), grant(resultRead, effect = GrantEffect.DENY)))
        val provider = CurrentAuthorizationProvider { state }
        val policy = AuthorizationPolicy(provider)
        assertNull(policy.currentIdentityDenial(actor))
        assertIs<AuthorizationDecision.Allowed>(policy.authorize(actor, edit))
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.EXPLICIT_DENY),
            ResultRecoveryPolicy(provider).authorize(actor, branch, actor.actorId, op(10)),
        )
    }

    @Test
    fun validStepUpIdentityDoesNotBypassActionOrResultRecoveryPurposeRestriction(): Unit = runTest {
        val actor = local(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL)
        val approve = requirement(SecurityAction.CASH_REVIEW, PermissionVerb.APPROVE)
        val edit = requirement()
        val provider = CurrentAuthorizationProvider { snapshot(actor, listOf(grant(approve), grant(edit))) }
        val policy = AuthorizationPolicy(provider)
        assertNull(policy.currentIdentityDenial(actor))
        assertIs<AuthorizationDecision.Allowed>(policy.authorize(actor, approve))
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED),
            policy.authorize(actor, edit),
        )
        assertEquals(
            ResultRecoveryDecision.Denied(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED),
            ResultRecoveryPolicy(provider).authorize(actor, branch, actor.actorId, op(10)),
        )
    }

    @Test
    fun eachIdentityCheckReadsCurrentDisableRevocationGenerationAndAdmission(): Unit = runTest {
        val actor = local()
        var current = snapshot(actor)
        var reads = 0
        val policy = AuthorizationPolicy { reads++; current }
        assertNull(policy.currentIdentityDenial(actor))
        for ((state, reason) in listOf(
            snapshot(actor, enabled = false) to AuthorizationDenial.ACCOUNT_DISABLED,
            snapshot(actor, active = false) to AuthorizationDenial.SESSION_REVOKED,
            snapshot(actor, admitted = false) to AuthorizationDenial.DEVICE_NOT_ADMITTED,
            snapshot(actor, generation = SessionGeneration(2)) to AuthorizationDenial.STALE_AUTHENTICATION,
            snapshot(actor, admissionRevision = Revision(2)) to AuthorizationDenial.STALE_AUTHENTICATION,
            snapshot(actor, scope = branch.copy(companyId = id(99))) to AuthorizationDenial.STALE_AUTHENTICATION,
        )) {
            current = state
            assertEquals(reason, policy.currentIdentityDenial(actor))
        }
        assertEquals(7, reads)
    }

    @Test
    fun identityStorageFailuresDenyAndCancellationStillPropagates(): Unit = runTest {
        val actor = local()
        assertEquals(
            AuthorizationDenial.AUTHORITY_UNAVAILABLE,
            AuthorizationPolicy(DenyAllAuthorizationProvider).currentIdentityDenial(actor),
        )
        val broken = AuthorizationPolicy { error("synthetic protected registry failure") }
        assertEquals(AuthorizationDenial.AUTHORITY_UNAVAILABLE, broken.currentIdentityDenial(actor))
        assertEquals(AuthorizationDenial.UNAUTHENTICATED, broken.currentIdentityDenial(null))
        val cancelled = AuthorizationPolicy { throw CancellationException("synthetic caller cancellation") }
        assertFailsWith<CancellationException> { cancelled.currentIdentityDenial(actor) }
    }
}

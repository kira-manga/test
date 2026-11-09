package com.ukkera.pos.security.independent.androidlifecycleresumev1

import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ALR2-S06 over the real production historical Boolean evaluator. This checks only the pure
 * grant/account/admission comparison; the exact historical session, credential, issuance,
 * issuer, policy and event joins remain in the chronological replays.
 */
internal class HistoricalLifecycleAuthorizationV2Test {
    @Test
    fun historicalLifecycleEvaluationReturnsOnlyPrimitiveBoolean() {
        val allowed: Boolean = AuthorizationEvaluator.historicalLocalEmployeeAllows(
            ACTOR_ID, SCOPE, DEVICE_ID, SESSION_ID, SessionGeneration(5), Revision(6),
            AuthenticationPurpose.INTERACTIVE, Revision(7), true, true, true, true,
            listOf(grant()), requirement(),
        )
        assertTrue(allowed, "ALR2-S06 exact grant allows")

        val method = AuthorizationEvaluator::class.java.methods.single {
            it.name.startsWith("historicalLocalEmployeeAllows")
        }
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType,
            "ALR2-S06 return type is primitive Boolean")
        assertFalse(AuthorizationDecision::class.java.isAssignableFrom(method.returnType),
            "ALR2-S06 no AuthorizationDecision escapes")
        assertFalse(AuthorizedAction::class.java.isAssignableFrom(method.returnType),
            "ALR2-S06 no AuthorizedAction escapes")
    }

    @Test
    fun missingDeniedWrongScopeWrongPermissionAndWrongTargetFailClosed() {
        assertFalse(allows(emptyList()), "ALR2-S06 missing grant")
        assertFalse(allows(listOf(grant(effect = GrantEffect.DENY))), "ALR2-S06 explicit deny")
        assertFalse(allows(listOf(grant(scope = SCOPE.copy(branchId = OTHER_ID)))), "ALR2-S06 wrong scope")
        assertFalse(allows(listOf(grant(permission = Permission(SecurityAction.MASTER_LIFECYCLE,
            PermissionVerb.PERFORM)))), "ALR2-S06 wrong verb")
        assertFalse(allows(listOf(grant(permission = Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION,
            PermissionVerb.ADMINISTER)))), "ALR2-S06 wrong action")
        assertFalse(allows(listOf(grant(namedSubjectId = OTHER_ID))), "ALR2-S06 named delegation mismatch")
        assertFalse(allows(listOf(grant(targetIds = setOf(OTHER_ID)))), "ALR2-S06 target list mismatch")
        assertTrue(allows(listOf(grant(targetIds = setOf(INSTALLATION_ID)))), "ALR2-S06 exact target list")
    }

    @Test
    fun disabledAccountUnadmittedDeviceAndWrongPurposeFailClosed() {
        assertFalse(allows(listOf(grant()), accountEnabled = false), "ALR2-S06 disabled account")
        assertFalse(allows(listOf(grant()), admissionAdmitted = false), "ALR2-S06 unadmitted device")
        assertFalse(allows(listOf(grant()), purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL),
            "ALR2-S06 non-interactive lifecycle purpose")
        assertFalse(allows(listOf(grant()), purpose = AuthenticationPurpose.SERVICE),
            "ALR2-S06 service purpose")
    }

    private fun allows(grants: List<PermissionGrant>, accountEnabled: Boolean = true,
        admissionAdmitted: Boolean = true, purpose: AuthenticationPurpose = AuthenticationPurpose.INTERACTIVE):
        Boolean = AuthorizationEvaluator.historicalLocalEmployeeAllows(
        ACTOR_ID, SCOPE, DEVICE_ID, SESSION_ID, SessionGeneration(5), Revision(6),
        purpose, Revision(7), accountEnabled, true, true, admissionAdmitted, grants, requirement(),
    )

    private fun grant(effect: GrantEffect = GrantEffect.ALLOW,
        scope: BranchScope = SCOPE,
        permission: Permission = Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER),
        namedSubjectId: DomainId? = null,
        targetIds: Set<DomainId>? = null): PermissionGrant = PermissionGrant(
        permission, scope, effect, PermissionLimits(), OwnerAccess.OWN_ONLY, namedSubjectId, targetIds,
    )

    private fun requirement(): AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER), SCOPE,
        ActionBinding(INSTALLATION_ID, Revision(0), REQUEST_DIGEST, OperationId(EVENT_ID.value),
            "local-master-lifecycle.stop"),
    )

    private companion object {
        private val ORG_ID = DomainId("00000000-0000-4000-8000-000000002002")
        private val COMPANY_ID = DomainId("00000000-0000-4000-8000-000000002003")
        private val BRANCH_ID = DomainId("00000000-0000-4000-8000-000000002004")
        private val SCOPE = BranchScope(ORG_ID, COMPANY_ID, BRANCH_ID)
        private val ACTOR_ID = DomainId("00000000-0000-4000-8000-000000002012")
        private val DEVICE_ID = DomainId("00000000-0000-4000-8000-000000002007")
        private val SESSION_ID = DomainId("00000000-0000-4000-8000-000000002020")
        private val INSTALLATION_ID = DomainId("00000000-0000-4000-8000-000000002005")
        private val EVENT_ID = DomainId("00000000-0000-4000-8000-000000002013")
        private val OTHER_ID = DomainId("00000000-0000-4000-8000-000000002099")
        private val REQUEST_DIGEST = PayloadDigest("39cff46dc003253e5b2d73c68ebb102e17ee0265f769876dad889a11508c2121")
    }
}

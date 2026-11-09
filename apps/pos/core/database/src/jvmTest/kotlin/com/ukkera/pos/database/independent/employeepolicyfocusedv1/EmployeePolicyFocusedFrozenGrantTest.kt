package com.ukkera.pos.database.independent.employeepolicyfocusedv1

import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Actual pure Authorization grant comparison using the fixed EPF policies. No accepting
 * verifier/provider, authenticated principal, native source, price writer or receipt is supplied.
 * A Boolean comparison is not a current Master refusal code, commit or protected-content result.
 */
class EmployeePolicyFocusedFrozenGrantTest {
    private val fixture = EmployeePolicyFocusedFrozenFixtures

    @Test
    fun epf04_amount1500OnSubtotal20000FailsDespitePercentage750BasisPointsPassing() {
        assertFalse(fixture.matches(fixture.policy(fixture.subject12), fixture.discountRequirement(1_500, 20_000)))
    }

    @Test
    fun epf04_amount600OnSubtotal5000FailsDespiteAmountBelow1000() {
        assertFalse(fixture.matches(fixture.policy(fixture.subject12), fixture.discountRequirement(600, 5_000)))
    }

    @Test
    fun epf04_amount1000OnSubtotal10000MeetsBothFrozenCeilingsExactly() {
        assertTrue(fixture.matches(fixture.policy(fixture.subject12), fixture.discountRequirement(1_000, 10_000)))
        // No payable 9000/order adjustment is inferred from permission comparison.
    }

    @Test
    fun epf02_missingRequiredMonetaryLimitIsNotUnlimitedAndDoesNotRemoveRoutineGrant() {
        val original = fixture.policy(fixture.subject12)
        val missingAmount = fixture.replaceGrants(original, original.grants.map { grant ->
            if (grant.permission.action != SecurityAction.DISCOUNT) grant else PermissionGrant(
                grant.permission, grant.scope, grant.effect, PermissionLimits(maximumPercentageBasisPoints = 1_000),
                grant.ownerAccess, grant.namedSubjectId, grant.targetIds, grant.delegationCeiling,
            )
        })
        assertFalse(fixture.matches(missingAmount, fixture.discountRequirement(1_000, 10_000)))
        assertTrue(fixture.matches(missingAmount, fixture.requirement(SecurityAction.ORDER_CREATE)))
        // Setup/default/error resolution and actual routine checkout remain UNBOUND.
    }

    @Test
    fun epf05_subjectRecordAndDocumentaryLookupMatchOnlyTheirFrozenPolicyMatrix() {
        val record = fixture.receiptRequirement()
        val lookup = fixture.requirement(SecurityAction.RETURN_RECEIPT_LOOKUP)
        assertTrue(fixture.matches(fixture.policy(fixture.subject11), record))
        assertTrue(fixture.matches(fixture.policy(fixture.subject11), lookup))
        assertFalse(fixture.matches(fixture.policy(fixture.subject12), record))
        assertTrue(fixture.matches(fixture.policy(fixture.subject12), lookup))
        assertTrue(fixture.matches(fixture.policy(fixture.subject13), record))
        assertFalse(fixture.matches(fixture.policy(fixture.subject13), lookup))
        // Also the permitted EPF-04/07 policy cuts; no authentication or historical result claim.
    }

    @Test
    fun epf05_peer41And42DoNotInheritSubjectsDocumentaryLookup() {
        val ownRecord = fixture.receiptRequirement(fixture.peer)
        val lookup = fixture.requirement(SecurityAction.RETURN_RECEIPT_LOOKUP)
        assertTrue(fixture.matches(fixture.policy(fixture.peer41), ownRecord))
        assertFalse(fixture.matches(fixture.policy(fixture.peer41), lookup))
        assertFalse(fixture.matches(fixture.policy(fixture.peer42), ownRecord))
        assertFalse(fixture.matches(fixture.policy(fixture.peer42), lookup))
    }

    @Test
    fun epf04_subject12KeepsQuantityGrantButHasNoCostOrReportExportGrant() {
        val policy = fixture.policy(fixture.subject12)
        assertTrue(fixture.matches(policy, fixture.requirement(SecurityAction.INVENTORY_QUANTITY_READ)))
        assertFalse(fixture.matches(policy, fixture.requirement(SecurityAction.INVENTORY_COST_READ)))
        assertFalse(fixture.matches(policy, fixture.requirement(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT)))
        // Own revenue, margin projection, serialization and export artifacts are not bound here.
    }

    @Test
    fun epf05_ownReceiptGrantNeverBecomesAnyOwnerOrPeersOriginalReceipt() {
        assertTrue(fixture.matches(fixture.policy(fixture.subject11), fixture.receiptRequirement(fixture.subject)))
        assertFalse(fixture.matches(fixture.policy(fixture.subject11), fixture.receiptRequirement(fixture.peer)))
        assertTrue(fixture.matches(fixture.policy(fixture.subject13), fixture.receiptRequirement(fixture.subject)))
        assertFalse(fixture.matches(fixture.policy(fixture.subject13), fixture.receiptRequirement(fixture.peer)))
        assertFalse(fixture.matches(fixture.policy(fixture.peer41), fixture.receiptRequirement(fixture.subject)))
        // This also bounds EPF-07 peer isolation, not stored R1 adoption/recovery.
    }

    @Test
    fun epf06_namedDelegationMatchesOnlyItsExplicitActorTargetAndBranchSelectors() {
        val policy = fixture.namedDelegationPolicy()
        fun management(target: com.ukkera.pos.types.DomainId = fixture.subject,
            scope: com.ukkera.pos.security.TenantScope = fixture.scopeA) = fixture.requirement(
            SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER, scope, target,
            OwnerConstraint.Unrestricted,
        )
        assertTrue(fixture.matches(policy, management()))
        assertFalse(fixture.matches(policy, management(fixture.branchBAccount)))
        assertFalse(fixture.matches(policy, management(fixture.branchBAccount, fixture.scopeB)))
        assertFalse(fixture.matches(policy, management(fixture.namedAdministrator)))
        assertFalse(fixture.matches(policy, management(), fixture.managerWithoutDelegation))
        assertFalse(fixture.matches(policy, fixture.requirement(SecurityAction.COMPANY_REPORT,
            PermissionVerb.ADMINISTER, fixture.scopeA.company(), fixture.subject)))
        // Target/scope comparison only. Tighten 1000/10 and excess 3500/35 proposal adjudication,
        // administrative writes, offline effects and emergency-disable permission remain UNBOUND.
    }
}

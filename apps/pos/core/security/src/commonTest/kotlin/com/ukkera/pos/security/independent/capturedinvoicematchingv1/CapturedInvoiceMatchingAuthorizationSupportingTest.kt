package com.ukkera.pos.security.independent.capturedinvoicematchingv1

import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.BranchServicePurpose
import com.ukkera.pos.security.DenyAllPrincipalVerifier
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Frozen mapping: CCM-08/09, base v1.0.1 + binding v1.0.0. Source handoff: NOT RUN.
 * Exercises AuthorizationPolicy with controlled current snapshots. It does not establish
 * serialized Master authorization/business commit, persistence, RPC, effects or result recovery.
 * Refusal families are intentionally not assigned command-level codes by this supporting slice.
 */
class CapturedInvoiceMatchingAuthorizationSupportingTest {
    private val input = CapturedInvoiceMatchingFrozenInputs

    @Test
    fun ccm08CaptureAndOriginalReadGrantsDoNotAllowCombinedMutation() = runTest {
        val principal = input.principal()
        val unrelated = listOf(
            SecurityAction.PURCHASING_DOCUMENT_CAPTURE,
            SecurityAction.PURCHASING_DOCUMENT_ATTACH,
            SecurityAction.PURCHASING_DOCUMENT_READ,
            SecurityAction.PURCHASING_INVOICE_CAPTURE,
        ).map { input.grant(action = it) }
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, unrelated))

        assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(current).authorize(principal, input.requirement()))
    }

    @Test
    fun ccm08QuantityAndPurchasingApprovalGrantsDoNotAllowCombinedMutation() = runTest {
        val principal = input.principal()
        val grants = listOf(
            input.grant(action = SecurityAction.INVENTORY_QUANTITY_READ),
            input.grant(action = SecurityAction.PURCHASING_READ),
            input.grant(action = SecurityAction.PURCHASING_APPROVE),
        )
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, grants))

        assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(current).authorize(principal, input.requirement()))
    }

    @Test
    fun ccm08CombinedActionNeedsItsExactPerformVerb() = runTest {
        val principal = input.principal()
        for (verb in listOf(PermissionVerb.REQUEST, PermissionVerb.APPROVE, PermissionVerb.ADMINISTER)) {
            val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant(verb = verb))))
            assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(current).authorize(principal, input.requirement()), verb.name)
        }
    }

    @Test
    fun ccm08FullRawClaimAtExactFiniteCeilingIsAllowedAndOneMinorBelowIsDenied() = runTest {
        val principal = input.principal()
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant(
            limits = PermissionLimits(maximumAmount = input.money(23_999L)),
        ))))
        val policy = AuthorizationPolicy(current)
        val requirement = input.requirement()

        assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, requirement))
        current.value = input.snapshot(principal, listOf(input.grant(
            limits = PermissionLimits(maximumAmount = input.money(24_000L)),
        )))
        val allowed = assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, requirement)).authorization
        assertTrue(allowed.isBoundTo(requirement, principal.subjectId))
    }

    @Test
    fun ccm08PartialSelectionDoesNotReduceTheFullOriginalCeiling() = runTest {
        val principal = input.principal()
        for (fixture in input.partialCeilings) {
            val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant(
                limits = PermissionLimits(maximumAmount = input.money(fixture.selectedMinor)),
            ))))
            val policy = AuthorizationPolicy(current)
            val requirement = input.requirement(fixture.fullRawMinor)

            assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, requirement), fixture.fixture)
            current.value = input.snapshot(principal, listOf(input.grant(
                limits = PermissionLimits(maximumAmount = input.money(fixture.fullRawMinor)),
            )))
            assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, requirement), fixture.fixture)
        }
    }

    @Test
    fun ccm08ExplicitNoCeilingIsAcceptedWhileOmittedCeilingIsDenied() = runTest {
        val principal = input.principal()
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant(
            limits = PermissionLimits(),
        ))))
        val policy = AuthorizationPolicy(current)

        assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, input.requirement()))
        current.value = input.snapshot(principal, listOf(input.grant(limits = input.noCeiling())))
        assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, input.requirement()))
    }

    @Test
    fun ccm08CombinedPermissionDoesNotGrantSourceOrDocumentRead() = runTest {
        val principal = input.principal()
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant())))
        val policy = AuthorizationPolicy(current)
        val actions = listOf(
            SecurityAction.PURCHASING_READ,
            SecurityAction.INVENTORY_QUANTITY_READ,
            SecurityAction.INVENTORY_COST_READ,
            SecurityAction.PURCHASING_DOCUMENT_READ,
        )
        assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, input.requirement()))
        for (action in actions) {
            val read = input.requirement().copy(
                permission = Permission(action, PermissionVerb.PERFORM),
                binding = null,
                limitUse = LimitUse.NotApplicable,
            )
            assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, read), action.name)
        }
    }

    @Test
    fun ccm08CombinedPermissionDoesNotGrantPurchasingApprovalOrSupplierSettlement() = runTest {
        val principal = input.principal()
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant())))
        val policy = AuthorizationPolicy(current)
        val unrelated = listOf(
            input.requirement().copy(permission = Permission(SecurityAction.PURCHASING_APPROVE, PermissionVerb.PERFORM)),
            input.requirement().copy(
                permission = Permission(SecurityAction.SUPPLIER_CASH_SETTLE, PermissionVerb.PERFORM),
                owner = OwnerConstraint.OwnerPrivate(input.actorId),
            ),
        )

        assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, input.requirement()))
        for (requirement in unrelated) assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, requirement))
    }

    @Test
    fun ccm08AdmittedDeviceAndMasterServiceCannotBecomeFinancialEmployees() = runTest {
        val verifier = object : PrincipalVerifier by DenyAllPrincipalVerifier {
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity =
                VerifiedDeviceIdentity(input.deviceId, input.scope, Revision(0))

            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity =
                VerifiedServiceIdentity(input.id(12), input.deviceId, input.scope,
                    input.id(13), SessionGeneration(1), Revision(0), BranchServicePurpose.MASTER)
        }
        val factory = TrustedPrincipalFactory(verifier)
        val device = assertNotNull(factory.authenticateDevice(DeviceGatewayCredentials(input.id(14))))
        val secret = OpaqueCredential(ByteArray(16) { 0x35 })
        val service = try {
            assertNotNull(factory.authenticateService(ServiceGatewayCredentials(input.id(15), secret)))
        } finally {
            secret.destroy()
        }
        for (principal in listOf(device, service)) {
            val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant())))
            assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(current).authorize(principal, input.requirement()))
        }
    }

    @Test
    fun ccm08UnavailableOrInactiveCurrentIdentityRefusesDespiteRetainedPrincipal() = runTest {
        val principal = input.principal()
        val grants = listOf(input.grant())
        val cuts = listOf(
            null,
            input.snapshot(principal, grants, accountEnabled = false),
            input.snapshot(principal, grants, sessionActive = false),
            input.snapshot(principal, grants, deviceAdmitted = false),
            input.snapshot(principal, grants, generation = SessionGeneration(2)),
        )
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, grants))
        val policy = AuthorizationPolicy(current)

        assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, input.requirement()))
        for (cut in cuts) {
            current.value = cut
            assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, input.requirement()))
        }
        assertIs<AuthorizationDecision.Denied>(policy.authorize(null, input.requirement()))
    }

    @Test
    fun ccm08ExplicitDenyAndWrongActorOrTenantGrantsCannotAuthorize() = runTest {
        val principal = input.principal()
        val wrongScope = BranchScope(input.scope.organizationId, input.scope.companyId, input.id(16))
        val invalidGrants = listOf(
            listOf(input.grant(), input.grant(effect = GrantEffect.DENY)),
            listOf(input.grant(namedSubjectId = input.otherActorId)),
            listOf(input.grant(grantScope = wrongScope)),
        )
        for (grants in invalidGrants) {
            val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, grants))
            assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(current).authorize(principal, input.requirement()))
        }
    }

    @Test
    fun ccm09PolicyReloadRefusesFreshWorkAfterGrantOrSessionRevocation() = runTest {
        val principal = input.principal()
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant())))
        val policy = AuthorizationPolicy(current)
        val requirement = input.requirement()
        val originalProof = assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, requirement)).authorization

        current.value = input.snapshot(principal, emptyList(), policyRevision = Revision(1))
        assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, requirement))
        current.value = input.snapshot(principal, listOf(input.grant()), sessionActive = false, policyRevision = Revision(1))
        assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, requirement))
        assertEquals(3, current.reads)
        assertEquals(Revision(0), originalProof.policyRevision)
        assertTrue(originalProof.isBoundTo(requirement, principal.subjectId))
        // This preserves a proof's old data only; no business result/commit ordering is asserted.
    }

    @Test
    fun ccm09AuthorizationCannotBeReboundToChangedOriginalActorScopeBodyOrAmount() = runTest {
        val principal = input.principal()
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, listOf(input.grant(
            targetIds = setOf(input.invoiceId), namedSubjectId = input.actorId,
        ))))
        val policy = AuthorizationPolicy(current)
        val requirement = input.requirement()
        val original = assertNotNull(requirement.binding)
        val proof = assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, requirement)).authorization
        val changed = listOf(
            requirement.copy(binding = original.copy(documentId = input.otherInvoiceId)),
            requirement.copy(binding = original.copy(revision = Revision(1))),
            requirement.copy(binding = original.copy(payloadDigest = sha256("CCM/F-QUALITY/changed-policy-comparison-body".encodeToByteArray()))),
            requirement.copy(binding = original.copy(operationId = OperationId(input.id(17).value))),
            requirement.copy(limitUse = LimitUse.Monetary(input.money(22_000L))),
            requirement.copy(scope = input.scope.copy(branchId = input.id(16))),
            requirement.copy(permission = Permission(SecurityAction.PURCHASING_APPROVE, PermissionVerb.PERFORM)),
        )

        assertTrue(proof.isBoundTo(requirement, input.actorId))
        assertFalse(proof.isBoundTo(requirement, input.otherActorId))
        for (other in changed) assertFalse(proof.isBoundTo(other, input.actorId))
        assertIs<AuthorizationDecision.Denied>(policy.authorize(principal,
            requirement.copy(binding = original.copy(documentId = input.otherInvoiceId))))
        assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, requirement.copy(binding = null)))
        assertIs<AuthorizationDecision.Denied>(policy.authorize(principal, requirement.copy(limitUse = LimitUse.NotApplicable)))
    }
}

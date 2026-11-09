package com.ukkera.pos.security.independent.approvalprovenancev1

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen L1 public authorization slices only; see README for unbound full-case obligations. */
class ApprovalProvenanceV1FrozenUnitSlicesTest {
    private val inputs = ApprovalProvenanceV1Inputs

    @Test
    fun apv01_directNullBeforeAndAfterSeparateInvocations() = runTest {
        val observations = ApprovalUnitObservations()
        val directRequirement = inputs.requirement(inputs.direct)
        val firstDirect = inputs.evaluate(inputs.directActor, directRequirement, PermissionVerb.PERFORM)
        observations.observe(firstDirect)
        val firstProof = assertIs<AuthorizationDecision.Allowed>(firstDirect).authorization
        assertDirect(firstProof, directRequirement)
        assertEquals(UnitObservationCounts(1, 1, 0), observations.counts)

        val firstApproval = inputs.issue()
        val first = consume(firstApproval, inputs.baseline, inputs.requester, inputs.manager)
        observations.observe(first)
        assertApproved(assertIs<ApprovalConsumeDecision.Consumed>(first), inputs.baseline, inputs.requester,
            inputs.manager, inputs.approval)
        val secondApproval = inputs.issue(inputs.secondApproval, inputs.second, approver = inputs.anotherApprover)
        val second = consume(secondApproval, inputs.second, inputs.requester, inputs.anotherApprover)
        observations.observe(second)
        assertApproved(assertIs<ApprovalConsumeDecision.Consumed>(second), inputs.second, inputs.requester,
            inputs.anotherApprover, inputs.secondApproval)

        val beforeSecondDirect = observations.counts
        val secondDirect = inputs.evaluate(inputs.directActor, directRequirement, PermissionVerb.PERFORM)
        observations.observe(secondDirect)
        val secondProof = assertIs<AuthorizationDecision.Allowed>(secondDirect).authorization
        assertDirect(secondProof, directRequirement)
        assertDirect(firstProof, directRequirement)
        assertNotSame(firstProof, secondProof)
        assertEquals(beforeSecondDirect.successfulConsumptions, observations.counts.successfulConsumptions)
        assertEquals(UnitObservationCounts(4, 4, 2), observations.counts)
        assertEquals(UnitConsumerObservation(inputs.directActor.id, null, inputs.directOperation, null,
            inputs.policy, null), observations.consumerCalls.first())
        assertEquals(observations.consumerCalls.first(), observations.consumerCalls.last())
    }

    @Test
    fun apv02_separateRMThenRNRetainsBothImmutablePairsAndExactOperations() = runTest {
        val observations = ApprovalUnitObservations()
        assertEquals("Synthetic User", inputs.requester.displayLabel)
        assertEquals(inputs.requester.displayLabel, inputs.manager.displayLabel)
        assertNotEquals(inputs.requester.id, inputs.manager.id)
        val firstIssued = inputs.issue()
        val first = assertIs<ApprovalConsumeDecision.Consumed>(consume(firstIssued, inputs.baseline,
            inputs.requester, inputs.manager))
        observations.observe(first)
        assertApproved(first, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        assertEquals(UnitObservationCounts(1, 1, 1), observations.counts)
        assertEquals(UnitConsumerObservation(inputs.requester.id, inputs.manager.id, inputs.operation,
            inputs.approval, inputs.policy, inputs.policy), observations.consumerCalls.single())

        val secondIssued = inputs.issue(inputs.secondApproval, inputs.second, approver = inputs.anotherApprover)
        val second = assertIs<ApprovalConsumeDecision.Consumed>(consume(secondIssued, inputs.second,
            inputs.requester, inputs.anotherApprover))
        observations.observe(second)
        assertApproved(second, inputs.second, inputs.requester, inputs.anotherApprover, inputs.secondApproval)
        assertApproved(first, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        assertNotSame(first.authorization, second.authorization)
        assertEquals(UnitObservationCounts(2, 2, 2), observations.counts)
        assertEquals(listOf(
            UnitConsumerObservation(inputs.requester.id, inputs.manager.id, inputs.operation,
                inputs.approval, inputs.policy, inputs.policy),
            UnitConsumerObservation(inputs.requester.id, inputs.anotherApprover.id, inputs.secondOperation,
                inputs.secondApproval, inputs.policy, inputs.policy)), observations.consumerCalls)
        assertIssuedUnchanged(firstIssued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
    }

    @Test
    fun apv03_explicitActiveNamedSelfReturnsSSNotNull() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue(inputs.selfApproval, inputs.self, inputs.soleOperator, inputs.soleOperator)
        val consumed = assertIs<ApprovalConsumeDecision.Consumed>(consume(issued, inputs.self,
            inputs.soleOperator, inputs.soleOperator))
        observations.observe(consumed)
        assertApproved(consumed, inputs.self, inputs.soleOperator, inputs.soleOperator, inputs.selfApproval)
        assertNotNull(consumed.authorization.approverId)
        assertEquals(consumed.authorization.actorId, consumed.authorization.approverId)
        assertEquals(UnitObservationCounts(1, 1, 1), observations.counts)
        assertEquals(UnitConsumerObservation(inputs.soleOperator.id, inputs.soleOperator.id, inputs.selfOperation,
            inputs.selfApproval, inputs.policy, inputs.policy), observations.consumerCalls.single())
    }

    @Test
    fun apv04a_separateCannotTurnManagerOwnRequestIntoSelfApproval() = runTest {
        val observations = ApprovalUnitObservations()
        val requirement = inputs.requirement(inputs.baseline)
        assertIs<AuthorizationDecision.Denied>(inputs.evaluate(inputs.manager, requirement, PermissionVerb.PERFORM))
        val issue = ActionApprovals.issue(inputs.approval, requirement,
            inputs.permit(inputs.manager, requirement, PermissionVerb.REQUEST),
            inputs.permit(inputs.manager, requirement, PermissionVerb.APPROVE),
            inputs.selfPolicy, inputs.issuedAt, inputs.validForMillis)
        assertIs<ApprovalIssueDecision.Denied>(issue)
        assertEquals(UnitObservationCounts.ZERO, observations.counts)
    }

    @Test
    fun apv04b_distinctUnauthorizedRequesterCannotSupplyAnApproverPermit() = runTest {
        val observations = ApprovalUnitObservations()
        val requirement = inputs.requirement(inputs.baseline)
        val request = inputs.permit(inputs.manager, requirement, PermissionVerb.REQUEST)
        assertEquals(inputs.manager.id, request.actorId)
        assertNotEquals(inputs.requester.id, request.actorId)
        assertIs<AuthorizationDecision.Denied>(inputs.evaluate(inputs.requester, requirement, PermissionVerb.APPROVE))
        assertIs<AuthorizationDecision.Denied>(inputs.evaluate(inputs.manager, requirement, PermissionVerb.PERFORM))
        // No approve permit exists to issue/consume; never manufacture one from distinct IDs.
        assertEquals(UnitObservationCounts.ZERO, observations.counts)
    }

    @Test
    fun apv05_namedSelfAbsentInactiveWrongAccountAndWrongActionAllRefuse() = runTest {
        val policies = listOf(
            "APV-05-a absent" to SelfApprovalPolicy(inputs.policy),
            "APV-05-b inactive" to SelfApprovalPolicy(inputs.policy),
            "APV-05-c different named account" to SelfApprovalPolicy(inputs.policy,
                setOf(NamedSelfApproval(inputs.scope, inputs.manager.id, inputs.action))),
            "APV-05-d different action" to SelfApprovalPolicy(inputs.policy,
                setOf(NamedSelfApproval(inputs.scope, inputs.soleOperator.id, inputs.otherAction))),
        )
        for ((variant, currentPolicy) in policies) {
            val observations = ApprovalUnitObservations()
            val requirement = inputs.requirement(inputs.self)
            val request = inputs.permit(inputs.soleOperator, requirement, PermissionVerb.REQUEST)
            val approve = inputs.permit(inputs.soleOperator, requirement, PermissionVerb.APPROVE)
            assertIs<ApprovalIssueDecision.Denied>(ActionApprovals.issue(inputs.selfApproval, requirement,
                request, approve, currentPolicy, inputs.issuedAt, inputs.validForMillis), variant)
            val issued = inputs.issue(inputs.selfApproval, inputs.self, inputs.soleOperator, inputs.soleOperator)
            assertRefused(ActionApprovals.consume(issued, requirement, request,
                inputs.currentApprover(inputs.soleOperator), currentPolicy, inputs.now), observations, variant)
            assertIssuedUnchanged(issued, inputs.self, inputs.soleOperator, inputs.soleOperator, inputs.selfApproval)
        }
    }

    @Test
    fun apv06a_currentRequesterSessionRevocationRefusesAtThePublicEvaluatorSlice() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        assertIs<AuthorizationDecision.Denied>(inputs.evaluate(inputs.requester, inputs.requirement(inputs.baseline),
            PermissionVerb.REQUEST, sessionActive = false))
        // A denied fresh request cannot supply the required transaction-local consuming input.
        assertEquals(UnitObservationCounts.ZERO, observations.counts)
        assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
    }

    @Test
    fun apv06b_currentApproverAccountDisableRefusesWithoutAConsumingProof() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        val requirement = inputs.requirement(inputs.baseline)
        assertIs<AuthorizationDecision.Denied>(inputs.evaluate(inputs.manager, requirement,
            PermissionVerb.APPROVE, accountEnabled = false))
        assertRefused(ActionApprovals.consume(issued, requirement,
            inputs.permit(inputs.requester, requirement, PermissionVerb.REQUEST),
            inputs.currentApprover(inputs.manager, enabled = false), inputs.selfPolicy, inputs.now), observations)
        assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
    }

    @Test
    fun apv06c_currentApproverSessionRevocationRefusesAtThePublicEvaluatorSlice() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        assertIs<AuthorizationDecision.Denied>(inputs.evaluate(inputs.manager, inputs.requirement(inputs.baseline),
            PermissionVerb.APPROVE, sessionActive = false))
        // CurrentApproverState has no qualifying-session field. Do not invent one or a gateway.
        assertEquals(UnitObservationCounts.ZERO, observations.counts)
        assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
    }

    @Test
    fun apv06d_activatedPolicyElevenRemovesApprovalAndCannotReauthorizeTheOldRecord() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        val requirement = inputs.requirement(inputs.baseline)
        assertIs<AuthorizationDecision.Denied>(inputs.evaluate(inputs.manager, requirement,
            PermissionVerb.APPROVE, policyRevision = inputs.nextPolicy, approvalPermission = false))
        assertRefused(ActionApprovals.consume(issued, requirement,
            inputs.permit(inputs.requester, requirement, PermissionVerb.REQUEST, inputs.nextPolicy),
            inputs.currentApprover(inputs.manager, inputs.nextPolicy), SelfApprovalPolicy(inputs.nextPolicy), inputs.now), observations)
        assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
    }

    @Test
    fun apv06e_suppliedActivatedTenAcceptsWhilePendingElevenIsNotCurrentAuthoritySlice() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        // The frozen pending-11 description is not an activated snapshot or a central API.
        // This is only the activated-10 positive control; policy-source selection stays unbound.
        val consumed = assertIs<ApprovalConsumeDecision.Consumed>(consume(issued, inputs.baseline,
            inputs.requester, inputs.manager))
        observations.observe(consumed)
        assertApproved(consumed, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        assertEquals(UnitObservationCounts(1, 1, 1), observations.counts)
    }

    @Test
    fun apv07a_b_e_canonicalRequesterApproverAndForeignAccountBindingSlicesRefuse() = runTest {
        run {
            val observations = ApprovalUnitObservations()
            val issued = inputs.issue()
            val requirement = inputs.requirement(inputs.baseline)
            assertRefused(ActionApprovals.consume(issued, requirement,
                inputs.permit(inputs.manager, requirement, PermissionVerb.REQUEST),
                inputs.currentApprover(inputs.manager), inputs.selfPolicy, inputs.now), observations, "APV-07-A")
            assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        }
        run {
            val observations = ApprovalUnitObservations()
            val issued = inputs.issue()
            val requirement = inputs.requirement(inputs.baseline)
            val otherwiseAuthorized = inputs.permit(inputs.anotherApprover, requirement, PermissionVerb.APPROVE)
            assertEquals(inputs.anotherApprover.id, otherwiseAuthorized.actorId)
            assertRefused(ActionApprovals.consume(issued, requirement,
                inputs.permit(inputs.requester, requirement, PermissionVerb.REQUEST),
                inputs.currentApprover(inputs.anotherApprover), inputs.selfPolicy, inputs.now), observations, "APV-07-B")
            assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        }
        for (foreign in listOf(inputs.otherBranchRequester, inputs.otherCompanyRequester)) {
            val observations = ApprovalUnitObservations()
            val issued = inputs.issue()
            val requirement = inputs.requirement(inputs.baseline)
            assertIs<AuthorizationDecision.Denied>(inputs.evaluate(foreign, requirement, PermissionVerb.REQUEST), "APV-07-E/${foreign.label}")
            // Foreign canonical accounts cannot supply a current request permit in the bound scope.
            assertEquals(UnitObservationCounts.ZERO, observations.counts)
            assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        }
    }

    @Test
    fun apv07c_d_f_g_exactCompanyBranchOperationAndActionMismatchCannotRebindTheRecord() = runTest {
        val baselineRequirement = inputs.requirement(inputs.baseline)
        val variants = listOf(
            "APV-07-C" to baselineRequirement.copy(scope = inputs.scope.copy(companyId = inputs.otherCompany)),
            "APV-07-D" to baselineRequirement.copy(scope = inputs.scope.copy(branchId = inputs.otherBranch)),
            "APV-07-F" to baselineRequirement.copy(binding = assertNotNull(baselineRequirement.binding).copy(operationId = inputs.thirdOperation)),
            "APV-07-G" to baselineRequirement.copy(permission = Permission(inputs.otherAction, PermissionVerb.PERFORM)),
        )
        for ((variant, changedRequirement) in variants) {
            val observations = ApprovalUnitObservations()
            val issued = inputs.issue()
            // Only the stated bound field changes. The original fresh request permit remains
            // an input to the public exact-binding check, not authority for the changed action.
            val request = inputs.permit(inputs.requester, baselineRequirement, PermissionVerb.REQUEST)
            assertNotEquals(baselineRequirement, changedRequirement, variant)
            assertRefused(ActionApprovals.consume(issued, changedRequirement, request,
                inputs.currentApprover(inputs.manager), inputs.selfPolicy, inputs.now), observations, variant)
            assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        }
    }

    @Test
    fun apv07h_policyBindingMismatchRefusesEvenWhenCurrentPermissionsStillAllow() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        val requirement = inputs.requirement(inputs.baseline)
        val currentRequest = inputs.permit(inputs.requester, requirement, PermissionVerb.REQUEST, inputs.nextPolicy)
        val currentApprove = inputs.permit(inputs.manager, requirement, PermissionVerb.APPROVE, inputs.nextPolicy)
        assertEquals(inputs.nextPolicy, currentRequest.policyRevision)
        assertEquals(inputs.nextPolicy, currentApprove.policyRevision)
        assertRefused(ActionApprovals.consume(issued, requirement, currentRequest,
            inputs.currentApprover(inputs.manager, inputs.nextPolicy), SelfApprovalPolicy(inputs.nextPolicy), inputs.now), observations)
        assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
    }

    @Test
    fun apv08_changedReceiptVersionLineQuantityAmountReasonOrExpiredValidityAllRefuse() = runTest {
        val variants = listOf(
            "APV-08-a" to inputs.baseline.copy(documentId = inputs.secondReceipt),
            "APV-08-b" to inputs.baseline.copy(documentRevision = Revision(8)),
            "APV-08-c" to inputs.baseline.copy(lineId = inputs.secondLine),
            "APV-08-d" to inputs.baseline.copy(quantity = 3),
            "APV-08-e" to inputs.baseline.copy(amount = inputs.changedAmount),
            "APV-08-f" to inputs.baseline.copy(reason = inputs.changedReason),
        )
        for ((variant, changedInput) in variants) {
            val observations = ApprovalUnitObservations()
            val issued = inputs.issue()
            val changedRequirement = inputs.requirement(changedInput)
            assertNotEquals(inputs.requirement(inputs.baseline), changedRequirement, variant)
            assertRefused(ActionApprovals.consume(issued, changedRequirement,
                inputs.permit(inputs.requester, changedRequirement, PermissionVerb.REQUEST),
                inputs.currentApprover(inputs.manager), inputs.selfPolicy, inputs.now), observations, variant)
            assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        }
        run {
            val observations = ApprovalUnitObservations()
            val issued = inputs.issue()
            val requirement = inputs.requirement(inputs.baseline)
            assertRefused(ActionApprovals.consume(issued, requirement,
                inputs.permit(inputs.requester, requirement, PermissionVerb.REQUEST),
                inputs.currentApprover(inputs.manager), inputs.selfPolicy, inputs.expiredNow), observations, "APV-08-g")
            assertIssuedUnchanged(issued, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        }
    }

    @Test
    fun apv09c_consumedApprovalCannotMintAnotherProofAndRetainedRMIsUnchanged() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        val first = assertIs<ApprovalConsumeDecision.Consumed>(consume(issued, inputs.baseline,
            inputs.requester, inputs.manager))
        observations.observe(first)
        val before = observations.counts
        assertEquals(UnitObservationCounts(1, 1, 1), before)
        assertRefused(consume(first.record, inputs.baseline, inputs.requester, inputs.manager), observations,
            "APV-09-c", before)
        assertApproved(first, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        assertEquals(1, observations.proofs.size)
        assertEquals(UnitConsumerObservation(inputs.requester.id, inputs.manager.id, inputs.operation,
            inputs.approval, inputs.policy, inputs.policy), observations.consumerCalls.single())
    }

    @Test
    fun apv09e_sameOperationChangedQuantityCannotConsumeAgainPublicApprovalSlice() = runTest {
        val observations = ApprovalUnitObservations()
        val issued = inputs.issue()
        val first = assertIs<ApprovalConsumeDecision.Consumed>(consume(issued, inputs.baseline,
            inputs.requester, inputs.manager))
        observations.observe(first)
        val changed = inputs.baseline.copy(quantity = 3)
        assertEquals(inputs.operation, changed.operationId)
        assertRefused(consume(first.record, changed, inputs.requester, inputs.manager), observations,
            "APV-09-e approval-consumption slice", UnitObservationCounts(1, 1, 1))
        assertApproved(first, inputs.baseline, inputs.requester, inputs.manager, inputs.approval)
        // No operation-result store is substituted: changed-payload recovery/conflict is unbound.
    }

    private suspend fun consume(record: ActionApprovalRecord, input: BoundReceipt,
        requester: UnitAccount, approver: UnitAccount): ApprovalConsumeDecision {
        val requirement = inputs.requirement(input)
        return ActionApprovals.consume(record, requirement, inputs.permit(requester, requirement, PermissionVerb.REQUEST),
            inputs.currentApprover(approver), inputs.selfPolicy, inputs.now)
    }

    private fun assertDirect(proof: AuthorizedAction, requirement: AuthorizationRequirement) {
        assertEquals(inputs.directActor.id, proof.actorId)
        assertEquals(PrincipalKind.LOCAL_EMPLOYEE, proof.principalKind)
        assertEquals(requirement, proof.requirement)
        assertEquals(inputs.directOperation, proof.requirement.binding?.operationId)
        assertEquals(inputs.policy, proof.policyRevision)
        assertNull(proof.approvalId)
        assertNull(proof.approverId)
        assertNull(proof.approverPolicyRevision)
        assertTrue(proof.isBoundTo(requirement, inputs.directActor.id))
        assertFalse(proof.isBoundTo(requirement, inputs.manager.id))
    }

    private fun assertApproved(consumed: ApprovalConsumeDecision.Consumed, input: BoundReceipt,
        requester: UnitAccount, approver: UnitAccount, approvalId: DomainId) {
        val requirement = inputs.requirement(input)
        val proof = consumed.authorization
        assertEquals(requester.id, proof.actorId)
        assertEquals(approver.id, proof.approverId)
        assertEquals(approvalId, proof.approvalId)
        assertEquals(PrincipalKind.LOCAL_EMPLOYEE, proof.principalKind)
        assertEquals(inputs.policy, proof.policyRevision)
        assertEquals(inputs.policy, proof.approverPolicyRevision)
        assertEquals(requirement, proof.requirement)
        assertEquals(input.operationId, consumed.record.consumedByOperationId)
        assertEquals(input.operationId, proof.requirement.binding?.operationId)
        assertEquals(requester.id, consumed.record.requestorId)
        assertEquals(approver.id, consumed.record.approverId)
        assertEquals(consumed.record.approverId, proof.approverId)
        assertEquals(consumed.record.approverPolicyRevision, proof.approverPolicyRevision)
        assertEquals(requirement, consumed.record.requirement)
        assertTrue(proof.isBoundTo(requirement, requester.id))
        if (requester.id != approver.id) assertFalse(proof.isBoundTo(requirement, approver.id))
    }

    private fun assertIssuedUnchanged(record: ActionApprovalRecord, input: BoundReceipt,
        requester: UnitAccount, approver: UnitAccount, approvalId: DomainId) {
        assertEquals(approvalId, record.approvalId)
        assertEquals(requester.id, record.requestorId)
        assertEquals(approver.id, record.approverId)
        assertEquals(inputs.requirement(input), record.requirement)
        assertEquals(inputs.policy, record.requestorPolicyRevision)
        assertEquals(inputs.policy, record.approverPolicyRevision)
        assertEquals(inputs.policy, record.selfApprovalPolicyRevision)
        assertEquals(inputs.issuedAt, record.issuedAt)
        assertEquals(inputs.validForMillis, record.validForMillis)
        assertNull(record.consumedByOperationId)
    }

    private fun assertRefused(decision: ApprovalConsumeDecision, observations: ApprovalUnitObservations,
        variant: String = "frozen refusal", before: UnitObservationCounts = UnitObservationCounts.ZERO) {
        observations.observe(decision)
        assertIs<ApprovalConsumeDecision.Denied>(decision, variant)
        assertEquals(before, observations.counts, variant)
    }
}

package com.ukkera.pos.security

import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ApprovalTest {
    private data class Fixture(
        val requirement: AuthorizationRequirement,
        val request: AuthorizedAction,
        val approve: AuthorizedAction,
        val selfPolicy: SelfApprovalPolicy,
        val currentApprover: CurrentApproverState,
    ) {
        fun issue(): ApprovalIssueDecision = ActionApprovals.issue(
            id(30), requirement, request, approve, selfPolicy, moment(1_000), 60_000,
        )

        fun consume(
            record: ActionApprovalRecord,
            expected: AuthorizationRequirement = requirement,
            requestAuthorization: AuthorizedAction = request,
            current: CurrentApproverState = currentApprover,
            policy: SelfApprovalPolicy = selfPolicy,
            now: MonotonicMoment = moment(2_000),
        ): ApprovalConsumeDecision = ActionApprovals.consume(record, expected, requestAuthorization, current, policy, now)
    }

    private suspend fun fixture(
        samePerson: Boolean = false,
        selfPolicy: SelfApprovalPolicy = SelfApprovalPolicy(Revision(1)),
    ): Fixture {
        val requestor = local()
        val approver = local(
            if (samePerson) requestor.actorId else id(5),
            AuthenticationPurpose.PRIVILEGED_APPROVAL,
            sessionId = id(9),
        )
        val required = requirement(SecurityAction.DISCOUNT, limitUse = LimitUse.Proportional(money(500), money(10_000)))
        val request = authorized(requestor, required.copy(permission = required.permission.copy(verb = PermissionVerb.REQUEST)))
        val approve = authorized(approver, required.copy(permission = required.permission.copy(verb = PermissionVerb.APPROVE)))
        return Fixture(required, request, approve, selfPolicy, CurrentApproverState(branch, approver.actorId, true, Revision(1)))
    }

    @Test
    fun approvalIsSingleUseAndNeverChangesCollectorIdentity(): Unit = runTest {
        val fixture = fixture()
        val issued = assertIs<ApprovalIssueDecision.Issued>(fixture.issue()).record
        val consumed = assertIs<ApprovalConsumeDecision.Consumed>(fixture.consume(issued))
        assertEquals(fixture.request.actorId, consumed.authorization.actorId)
        assertEquals(id(5), issued.approverId)
        assertEquals(id(4), issued.requestorId)
        assertEquals(id(30), consumed.authorization.approvalId)
        assertTrue(consumed.authorization.isBoundTo(fixture.requirement, id(4)))
        assertEquals(op(10), consumed.record.consumedByOperationId)
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.APPROVAL_CONSUMED), fixture.consume(consumed.record),
        )
    }

    @Test
    fun anyActorDocumentRevisionPayloadReasonScopeOrAmountEditInvalidatesApproval(): Unit = runTest {
        val fixture = fixture()
        val issued = assertIs<ApprovalIssueDecision.Issued>(fixture.issue()).record
        val required = fixture.requirement
        val originalBinding = checkNotNull(required.binding)
        val edits = listOf(
            required.copy(binding = originalBinding.copy(documentId = id(99))),
            required.copy(binding = originalBinding.copy(revision = Revision(2))),
            required.copy(binding = originalBinding.copy(payloadDigest = sha256("other-lines".encodeToByteArray()))),
            required.copy(binding = originalBinding.copy(reasonCode = "changed.reason")),
            required.copy(binding = originalBinding.copy(operationId = op(99))),
            required.copy(scope = branch.copy(branchId = id(99))),
            required.copy(scope = branch.copy(companyId = id(99))),
            required.copy(owner = OwnerConstraint.OwnerPrivate(id(99))),
            required.copy(limitUse = LimitUse.Proportional(money(501), money(10_000))),
            required.copy(limitUse = LimitUse.Proportional(money(500), money(10_001))),
            required.copy(permission = required.permission.copy(action = SecurityAction.PRICE_OVERRIDE)),
        )
        for (edited in edits) {
            assertEquals(
                ApprovalConsumeDecision.Denied(ApprovalDenial.AUTHORIZATION_NOT_BOUND), fixture.consume(issued, expected = edited),
            )
        }
        val other = local(id(99))
        val otherRequest = authorized(other, required.copy(permission = required.permission.copy(verb = PermissionVerb.REQUEST)))
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.AUTHORIZATION_NOT_BOUND),
            fixture.consume(issued, requestAuthorization = otherRequest),
        )
    }

    @Test
    fun selfApprovalDefaultsDenyAndRequiresExplicitNamedActionScopeException(): Unit = runTest {
        assertEquals(
            ApprovalIssueDecision.Denied(ApprovalDenial.SELF_APPROVAL_NOT_CONFIGURED), fixture(samePerson = true).issue(),
        )
        for (exception in listOf(
            NamedSelfApproval(branch, id(5), SecurityAction.DISCOUNT),
            NamedSelfApproval(branch, id(4), SecurityAction.PRICE_OVERRIDE),
            NamedSelfApproval(branch.copy(companyId = id(99)), id(4), SecurityAction.DISCOUNT),
        )) {
            val denied = fixture(true, SelfApprovalPolicy(Revision(1), setOf(exception)))
            assertEquals(
                ApprovalIssueDecision.Denied(ApprovalDenial.SELF_APPROVAL_NOT_CONFIGURED), denied.issue(),
            )
        }
        val permitted = fixture(true, SelfApprovalPolicy(Revision(1), setOf(NamedSelfApproval(branch, id(4), SecurityAction.DISCOUNT))))
        val issued = assertIs<ApprovalIssueDecision.Issued>(permitted.issue()).record
        assertIs<ApprovalConsumeDecision.Consumed>(permitted.consume(issued))
    }

    @Test
    fun expiryBootChangeAndMonotonicRollbackNeverExtendApprovals(): Unit = runTest {
        val fixture = fixture()
        val issued = assertIs<ApprovalIssueDecision.Issued>(fixture.issue()).record
        assertIs<ApprovalConsumeDecision.Consumed>(fixture.consume(issued, now = moment(60_999)))
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.APPROVAL_STALE), fixture.consume(issued, now = moment(61_000)),
        )
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.CLOCK_UNPROVEN), fixture.consume(issued, now = moment(0, id(901))),
        )
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.CLOCK_UNPROVEN), fixture.consume(issued, now = moment(999)),
        )
    }

    @Test
    fun currentApproverDisableAndPolicyActivationInvalidateOldApproval(): Unit = runTest {
        val fixture = fixture()
        val issued = assertIs<ApprovalIssueDecision.Issued>(fixture.issue()).record
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.APPROVER_REVOKED),
            fixture.consume(issued, current = fixture.currentApprover.copy(enabled = false)),
        )
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.APPROVAL_STALE),
            fixture.consume(issued, current = fixture.currentApprover.copy(policyRevision = Revision(2))),
        )
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.APPROVAL_STALE),
            fixture.consume(issued, policy = SelfApprovalPolicy(Revision(2))),
        )
        val freshRequest = authorized(
            local(), fixture.requirement.copy(permission = fixture.requirement.permission.copy(verb = PermissionVerb.REQUEST)),
            policyRevision = Revision(2),
        )
        assertEquals(
            ApprovalConsumeDecision.Denied(ApprovalDenial.APPROVAL_STALE),
            fixture.consume(issued, requestAuthorization = freshRequest),
        )
    }

    @Test
    fun ordinaryPerformPermissionIsNotAnApprovalOrRequest(): Unit = runTest {
        val fixture = fixture()
        val perform = authorized(local(), fixture.requirement)
        assertEquals(
            ApprovalIssueDecision.Denied(ApprovalDenial.AUTHORIZATION_NOT_BOUND),
            ActionApprovals.issue(id(30), fixture.requirement, perform, fixture.approve, fixture.selfPolicy, moment(1_000), 1_000),
        )
        assertEquals(
            ApprovalIssueDecision.Denied(ApprovalDenial.AUTHORIZATION_NOT_BOUND),
            ActionApprovals.issue(id(30), fixture.requirement, fixture.request, perform, fixture.selfPolicy, moment(1_000), 1_000),
        )
        assertEquals(
            ApprovalIssueDecision.Denied(ApprovalDenial.INVALID_REQUEST),
            ActionApprovals.issue(
                id(30), fixture.requirement.copy(binding = binding(operationId = null)),
                fixture.request, fixture.approve, fixture.selfPolicy, moment(1_000), 1_000,
            ),
        )
    }
}

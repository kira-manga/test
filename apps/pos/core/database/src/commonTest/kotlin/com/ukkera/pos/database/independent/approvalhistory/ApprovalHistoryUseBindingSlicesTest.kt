package com.ukkera.pos.database

import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OH-01/02/03/06/07 representation slices only. No genuine issue/use or business commit is exercised. */
class ApprovalHistoryUseBindingSlicesTest {
    private val inputs = ApprovalHistoryRepresentationInputs

    @Test fun inertApplyRetainsOriginalRequestorApproverRequirementAndSameOperationConsumption() {
        val decoded = roundtrip(inputs.envelope())
        val command = decoded.command()
        val issued = decoded.approvalUse.issued(inputs.scope)
        val consumed = assertNotNull(decoded.approvalUse.consumed(inputs.scope))
        val event = assertNotNull(decoded.approvalUse.event(inputs.scope))
        assertEquals(RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED, decoded.approvalUse.kind)
        assertEquals(RuntimeWritePurposeV2.COMMAND_APPLY.tag, command.purposeTag)
        assertEquals(inputs.inertApprovalId, command.actionApprovalId)
        assertEquals(inputs.requestor, issued.record.requestorId)
        assertEquals(inputs.manager, issued.record.approverId)
        assertEquals(inputs.separatePolicy, issued.record.requestorPolicyRevision)
        assertEquals(inputs.separatePolicy, issued.record.approverPolicyRevision)
        assertEquals(inputs.requirement().value(), issued.record.requirement)
        assertEquals(inputs.operation, command.operationId)
        assertEquals(inputs.semanticDigest, command.semanticDigest)
        assertEquals(inputs.document, command.requirement.documentId)
        assertEquals(inputs.documentRevision, command.requirement.documentRevision)
        assertEquals(inputs.discount, command.requirement.amount)
        assertEquals(inputs.subtotal, command.requirement.baseAmount)
        assertEquals(Revision(0), issued.record.revision)
        assertNull(issued.record.consumedByOperationId)
        assertEquals(issued.record.copy(revision = Revision(1), consumedByOperationId = inputs.operation), consumed.record)
        assertEquals(inputs.requestor, event.operatorId)
        assertEquals(inputs.document, event.targetId)
        assertEquals(inputs.expectedImages(consumed, event), decoded.approvalUse.images)
    }

    @Test fun inertTerminalRejectRetainsApprovalLocatorAndUnconsumedOriginalWithoutDirectFallback() {
        val source = inputs.envelope(RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED)
        val decoded = roundtrip(source)
        assertEquals(source, decoded)
        assertEquals(RuntimeWritePurposeV2.COMMAND_REJECT.tag, decoded.command().purposeTag)
        assertEquals(inputs.inertApprovalId, decoded.command().actionApprovalId)
        assertEquals(RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED, decoded.approvalUse.kind)
        assertEquals(Revision(0), decoded.approvalUse.issued(inputs.scope).record.revision)
        assertNull(decoded.approvalUse.issued(inputs.scope).record.consumedByOperationId)
        assertNull(decoded.approvalUse.consumedApproval)
        assertNull(decoded.approvalUse.consumptionEventId)
        assertNull(decoded.approvalUse.consumptionEvent)
        assertTrue(decoded.approvalUse.images.isEmpty())
    }

    @Test fun appliedAndRejectedUseKindsCannotExchangeCommandPurposeOrConsumptionShape() {
        val apply = inputs.envelope()
        val reject = inputs.envelope(RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED)
        assertJoinRejected(apply, reject.command())
        assertJoinRejected(reject, apply.command())
        assertRejected(apply.copy(approvalUse = apply.approvalUse.copy(kind = RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED)))
        assertRejected(reject.copy(approvalUse = reject.approvalUse.copy(kind = RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED)))
    }

    @Test fun appliedUseCannotOmitConsumedRecordConsumptionEventOrEitherOriginalImage() {
        val original = inputs.envelope()
        val use = original.approvalUse
        for (changed in listOf(use.copy(consumedApproval = null), use.copy(consumptionEventId = null),
            use.copy(consumptionEvent = null), use.copy(images = emptyList()),
            use.copy(images = use.images.take(1)), use.copy(images = use.images.drop(1)))) {
            assertRejected(original.copy(approvalUse = changed))
        }
    }

    @Test fun rejectedUseCannotHideConsumedRecordEventLocatorEventBytesOrAnApprovalImage() {
        val original = inputs.envelope(RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED)
        val use = original.approvalUse
        val apply = inputs.use()
        for (changed in listOf(use.copy(consumedApproval = apply.consumedApproval),
            use.copy(consumptionEventId = apply.consumptionEventId), use.copy(consumptionEvent = apply.consumptionEvent),
            use.copy(images = apply.images.take(1)), apply.copy(kind = RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED))) {
            assertRejected(original.copy(approvalUse = changed))
        }
    }

    @Test fun exactApprovalLocatorCannotBeAbsentUnknownOrReplacedInEitherUseKind() {
        for (kind in RuntimeActionApprovalUseKindV1.entries) {
            val original = inputs.envelope(kind)
            assertJoinRejected(original, original.command().copy(actionApprovalId = null))
            assertJoinRejected(original, original.command().copy(actionApprovalId = inputs.unknownApproval))
            assertRejected(original.copy(approvalUse = original.approvalUse.copy(approvalId = inputs.unknownApproval)))
        }
    }

    @Test fun requestorActorSessionDeviceGenerationPolicyAndIncarnationRemainOriginal() {
        val original = inputs.envelope()
        val command = original.command()
        val requestor = command.authorization
        val changedCredential = requestor.credentialRevision.next()
        val changedAdmission = requestor.admissionRevision.next()
        val changes = listOf(requestor.copy(actorId = inputs.otherActor),
            requestor.copy(sessionId = inputs.otherSession), requestor.copy(deviceId = inputs.fixtureManagerDevice),
            requestor.copy(generation = requestor.generation.next()), requestor.copy(policyRevision = inputs.deniedPolicy),
            requestor.copy(accountRevision = requestor.accountRevision.next()),
            requestor.copy(credentialRevision = changedCredential,
                access = requestor.access.copy(credentialRevisionAtIssue = changedCredential)),
            requestor.copy(admissionRevision = changedAdmission,
                access = requestor.access.copy(admissionRevisionAtIssue = changedAdmission)),
            requestor.copy(access = requestor.access.copy(issuerContextId = inputs.otherInertIssuer)))
        for (changed in changes) assertJoinRejected(original, command.copy(authorization = changed))
    }

    @Test fun originalApproverCannotBeReplacedByAnotherPersonPolicyDeviceOrIncarnation() {
        val original = inputs.envelope()
        val approver = original.approvalUse.approver
        val changes = listOf(approver.copy(actorId = inputs.otherApprover),
            approver.copy(deviceId = inputs.fixtureManagerDevice), approver.copy(policyRevision = inputs.deniedPolicy),
            approver.copy(accountRevision = approver.accountRevision.next()),
            approver.copy(generation = approver.generation.next()),
            approver.copy(credentialRevision = approver.credentialRevision.next()),
            approver.copy(admissionRevision = approver.admissionRevision.next()),
            approver.copy(scope = inputs.scope.copy(branchId = inputs.otherBranch)))
        for (changed in changes) {
            changed.validate()
            assertRejected(original.copy(approvalUse = original.approvalUse.copy(approver = changed)))
        }
    }

    @Test fun originalScopeAndTargetOperationCannotBeReboundByAConsistentReplacementCommandDto() {
        val original = inputs.envelope()
        val command = original.command()
        for (scope in listOf(inputs.scope.copy(organizationId = inputs.otherOrganization),
            inputs.scope.copy(companyId = inputs.otherCompany), inputs.scope.copy(branchId = inputs.otherBranch))) {
            assertJoinRejected(original, command.copy(requirement = command.requirement.copy(scope = scope),
                authorization = command.authorization.copy(scope = scope)))
        }
        assertJoinRejected(original, command.copy(operationId = inputs.otherOperation,
            requirement = command.requirement.copy(operationId = inputs.otherOperation)))
    }

    @Test fun originalRequirementIncludesActionDocumentRevisionOwnerReasonMoneyBaseAndContent() {
        val original = inputs.envelope()
        val command = original.command()
        val requirement = command.requirement
        val changes = listOf(requirement.copy(action = SecurityAction.PRICE_OVERRIDE),
            requirement.copy(documentId = inputs.otherDocument), requirement.copy(documentRevision = Revision(18)),
            requirement.copy(ownerId = inputs.otherActor), requirement.copy(ownerId = null),
            requirement.copy(reasonCode = "fixture.oh.changed"), requirement.copy(amount = inputs.changedDiscount),
            requirement.copy(baseAmount = Money(Currency.EGP, 25_000)),
            requirement.copy(limitKind = "monetary", baseAmount = null),
            requirement.copy(amount = inputs.changedDiscount, payloadDigest = inputs.changedAmountSemanticDigest),
            requirement.copy(payloadDigest = inputs.changedLineSemanticDigest))
        for (changed in changes) assertJoinRejected(original, command.copy(requirement = changed, semanticDigest = changed.payloadDigest))
    }

    @Test fun consumedRecordMustBeTheExactOriginalRevisionTransitionEvenWithMatchingReplacementImages() {
        val original = inputs.envelope()
        val consumed = assertNotNull(original.approvalUse.consumed(inputs.scope))
        val record = consumed.record
        val anotherOperation = record.requirement.copy(binding = assertNotNull(record.requirement.binding).copy(operationId = inputs.otherOperation))
        val changes = listOf(inputs.copyApproval(consumed, record = record.copy(requestorId = inputs.otherActor)),
            inputs.copyApproval(consumed, record = record.copy(approverId = inputs.otherApprover)),
            inputs.copyApproval(consumed, record = record.copy(approvalId = inputs.unknownApproval)),
            inputs.copyApproval(consumed, record = record.copy(requirement = anotherOperation, consumedByOperationId = inputs.otherOperation)),
            inputs.copyApproval(consumed, record = record.copy(revision = Revision(0), consumedByOperationId = null)),
            inputs.copyApproval(consumed, record = record.copy(requestorPolicyRevision = inputs.deniedPolicy)),
            inputs.copyApproval(consumed, record = record.copy(approverPolicyRevision = inputs.deniedPolicy)),
            inputs.copyApproval(consumed, record = record.copy(selfApprovalPolicyRevision = inputs.namedSelfPolicy)),
            inputs.copyApproval(consumed, record = record.copy(validForMillis = record.validForMillis + 1)),
            inputs.copyApproval(consumed, record = record.copy(issuedAt = record.issuedAt.copy(elapsedMillis = 2_001))),
            inputs.copyApproval(consumed, issuerContextId = inputs.otherInertIssuer),
            inputs.copyApproval(consumed, requestorSessionId = inputs.otherSession),
            inputs.copyApproval(consumed, confirmationDigest = inputs.inertDifferentDigest),
            inputs.copyApproval(consumed, issueRequestDigest = inputs.inertDifferentDigest))
        for (changed in changes) {
            val event = inputs.eventCarrier(changed)
            val use = original.approvalUse.copy(consumedApproval = inputs.approvalText(changed),
                consumptionEventId = event.eventId, consumptionEvent = inputs.eventText(event),
                images = inputs.expectedImages(changed, event))
            assertRejected(original.copy(approvalUse = use))
        }
    }

    @Test fun originalConsumptionEventBindsKindTargetRequestorSessionAccountMomentAndRecordDigest() {
        val original = inputs.envelope()
        val event = assertNotNull(original.approvalUse.event(inputs.scope))
        val changes = listOf(inputs.copyEvent(event, kind = LocalAuthEventKind.ACTION_APPROVAL_ISSUED),
            inputs.copyEvent(event, targetId = inputs.otherDocument), inputs.copyEvent(event, operatorId = inputs.manager),
            inputs.copyEvent(event, operatorId = null), inputs.copyEvent(event, sessionId = inputs.otherSession),
            inputs.copyEvent(event, sessionId = null), inputs.copyEvent(event, accountRevision = Revision(4)),
            inputs.copyEvent(event, accountRevision = null), inputs.copyEvent(event, at = event.at.copy(elapsedMillis = 5_001)),
            inputs.copyEvent(event, contentDigest = inputs.inertDifferentDigest), inputs.copyEvent(event, contentDigest = null),
            inputs.copyEvent(event, scope = inputs.scope.copy(branchId = inputs.otherBranch)))
        for (changed in changes) assertRejected(original.copy(approvalUse = inputs.withEvent(original.approvalUse, changed)))
    }

    @Test fun eventLocatorAndOrderedImagesCannotBeSubstitutedDuplicatedOrPartiallyRetained() {
        val original = inputs.envelope()
        val use = original.approvalUse
        assertRejected(original.copy(approvalUse = use.copy(consumptionEventId = inputs.otherInertEventId)))
        val approval = use.images[0]
        val event = use.images[1]
        val changes = listOf(listOf(event, approval), listOf(approval, approval), listOf(event, event),
            use.images + event, listOf(approval.copy(recordId = inputs.unknownApproval), event),
            listOf(approval.copy(plaintextDigest = inputs.inertDifferentDigest), event),
            listOf(approval.copy(tableTag = RuntimeHistoryTableV2.ACCOUNT.tag), event),
            listOf(approval.copy(mutationTag = RuntimeHistoryMutationKindV2.INSERT.tag, revision = Revision(0)), event),
            listOf(approval, event.copy(recordId = inputs.otherInertEventId)),
            listOf(approval, event.copy(plaintextDigest = inputs.inertDifferentDigest)))
        for (changed in changes) assertRejected(original.copy(approvalUse = use.copy(images = changed)))
    }

    @Test fun originalObservationRequiresSameIssuerBootAndStrictUnrenewedApprovalDeadline() {
        for (kind in RuntimeActionApprovalUseKindV1.entries) {
            val original = inputs.envelope(kind)
            val use = original.approvalUse
            val lastValid = MonotonicMoment(inputs.inertBoot, inputs.issuedAt.elapsedMillis + inputs.validForMillis - 1)
            roundtrip(original.copy(approvalUse = observedAt(use, use.observed.copy(moment = lastValid))))
            val changes = listOf(use.observed.copy(issuerContextId = inputs.otherInertIssuer),
                use.observed.copy(moment = use.observed.moment.copy(bootId = inputs.otherInertBoot)),
                use.observed.copy(moment = inputs.issuedAt.copy(elapsedMillis = inputs.issuedAt.elapsedMillis - 1)),
                use.observed.copy(moment = inputs.issuedAt.copy(elapsedMillis = inputs.issuedAt.elapsedMillis + inputs.validForMillis)),
                use.observed.copy(moment = inputs.issuedAt.copy(elapsedMillis = inputs.issuedAt.elapsedMillis + inputs.validForMillis + 1)))
            for (changed in changes) assertRejected(original.copy(approvalUse = observedAt(use, changed)))
        }
    }

    @Test fun namedSelfRepresentationRetainsAAndAExactNamedActionAndOriginalPolicyNine() {
        val original = inputs.envelope(namedSelf = true)
        val decoded = roundtrip(original)
        val issued = decoded.approvalUse.issued(inputs.scope)
        assertEquals(inputs.requestor, issued.record.requestorId)
        assertEquals(inputs.requestor, issued.record.approverId)
        assertEquals(inputs.namedSelfPolicy, issued.record.requestorPolicyRevision)
        assertEquals(inputs.namedSelfPolicy, issued.record.approverPolicyRevision)
        assertEquals(inputs.namedSelfPolicy, issued.record.selfApprovalPolicyRevision)
        assertEquals(SecurityAction.DISCOUNT, decoded.command().requirement.action)
        assertEquals(inputs.namedSelfSemanticDigest, decoded.command().semanticDigest)
        assertRejected(original.copy(approvalUse = original.approvalUse.copy(
            approver = original.approvalUse.approver.copy(actorId = inputs.manager))))
        assertJoinRejected(original, original.command().copy(requirement = original.command().requirement.copy(action = SecurityAction.COMPANY_POLICY)))
        assertJoinRejected(original, original.command().copy(authorization = original.command().authorization.copy(policyRevision = inputs.separatePolicy)))
    }

    private fun observedAt(use: RuntimeActionApprovalUseEvidenceV1, observed: RuntimeApprovalObservationV1): RuntimeActionApprovalUseEvidenceV1 {
        val changed = use.copy(observed = observed)
        val event = use.event(inputs.scope) ?: return changed
        return inputs.withEvent(changed, inputs.copyEvent(event, at = observed.moment))
    }

    private fun assertJoinRejected(original: RuntimeApprovedCommandEvidenceV1, command: RuntimeCommandAuthorizationEvidenceV1) {
        command.validate() // Replacement V1 data is well-formed; its original-approval join must still fail.
        assertRejected(inputs.withCommand(original, command))
    }

    private fun assertRejected(value: RuntimeApprovedCommandEvidenceV1) {
        assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeActionApprovalEvidenceCodec.encode(value) }
    }

    private fun roundtrip(value: RuntimeApprovedCommandEvidenceV1): RuntimeApprovedCommandEvidenceV1 {
        val bytes = RuntimeActionApprovalEvidenceCodec.encode(value)
        return try { RuntimeActionApprovalEvidenceCodec.decodeCommand(bytes) } finally { bytes.fill(0) }
    }
}

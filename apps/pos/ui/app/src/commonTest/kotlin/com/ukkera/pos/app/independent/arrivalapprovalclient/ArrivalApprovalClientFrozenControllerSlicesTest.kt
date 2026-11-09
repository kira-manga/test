@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.arrivalapprovalclient

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.arrivalapprovalclient.AawClientUiFixture.Companion.withController
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen AAW-03/04/05/06/07 and OH-04/06 presentation slices. Supplied review and
 * historical-receipt DATA exercise the actual shared controller and runtime;
 * they do not qualify authentication, AP issuance/consume, native custody,
 * durable Master history, RPC, C1-C4 quantities or the money/stock vector.
 * Every complete OH/QAM/UAD2/AAW case and native variant remains NOT_RUN/UNBOUND.
 */
class ArrivalApprovalClientFrozenControllerSlicesTest {
    private val inputs = AawClientUiInputs

    @Test
    fun aaw07CancelBackRetainsOriginalPendingInputWithoutApprovalOrReceivingSubmission() = runTest {
        withController { fixture ->
            val controller = fixture.controller
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            assertTrue(assertNotNull(controller.state.value.actionApproval).canConfirm)
            controller.confirmActionApproval(true, fixture.token())
            controller.openActionApproverPassword(fixture.token())
            assertTrue(assertNotNull(controller.state.value.actionApproval).passwordEntry)
            controller.closeActionApproverPassword(fixture.token())
            assertFalse(assertNotNull(controller.state.value.actionApproval).passwordEntry)
            controller.closeActionApproval(fixture.token())
            assertNull(controller.state.value.actionApproval)
            assertEquals(inputs.operation, controller.state.value.operationId)
            assertEquals(inputs.summary, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation.summary)
            assertEquals(inputs.operation, controller.state.value.pending.single().operationId)
            assertTrue(fixture.port.issues.isEmpty())
            fixture.port.assertNoBusinessCalls()
            assertRequesterSessionUnchanged(fixture)

            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            assertFalse(assertNotNull(controller.state.value.actionApproval).confirmed)
            assertFalse(assertNotNull(controller.state.value.actionApproval).passwordEntry)
            assertEquals(listOf(inputs.operation, inputs.operation), fixture.port.previews)
        }
    }

    @Test
    fun aaw07UnconfirmedOrCancelledPasswordEntryCannotIssueOnBehalfOfAnApprover() = runTest {
        withController { fixture ->
            val controller = fixture.controller
            val beforeReview = charArrayOf('b', 'e', 'f', 'o', 'r', 'e')
            controller.issueActionApproval("APPROVER-A", beforeReview, fixture.token())
            assertTrue(beforeReview.all { it == '\u0000' })
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            controller.openActionApproverPassword(fixture.token())
            assertFalse(assertNotNull(controller.state.value.actionApproval).passwordEntry)
            val unconfirmed = charArrayOf('u', 'n', 'c', 'o', 'n', 'f', 'i', 'r', 'm', 'e', 'd')
            controller.issueActionApproval("APPROVER-A", unconfirmed, fixture.token())
            assertTrue(unconfirmed.all { it == '\u0000' })
            controller.confirmActionApproval(true, fixture.token())
            controller.openActionApproverPassword(fixture.token())
            controller.closeActionApproverPassword(fixture.token())
            val cancelled = charArrayOf('c', 'a', 'n', 'c', 'e', 'l', 'l', 'e', 'd')
            controller.issueActionApproval("APPROVER-A", cancelled, fixture.token())
            runCurrent()
            assertTrue(cancelled.all { it == '\u0000' })
            assertTrue(fixture.port.issues.isEmpty())
            assertEquals(inputs.operation, controller.state.value.operationId)
            fixture.port.assertNoBusinessCalls()
            assertRequesterSessionUnchanged(fixture)
        }
    }

    @Test
    fun aaw01And02SuppliedOriginalReceiptsReturnAllThreeRoutesToTheRequestersSameOperation() = runTest {
        inputs.journeys.forEach { journey ->
            val rule = inputs.rule(journey.namedSelf)
            val initial = inputs.pending(rule = rule, operationId = journey.operationId)
            withController(initial) { fixture ->
                val receiptSample = DomainId.random()
                val attached = inputs.pending(LocalActionApprovalPhase.ATTACHED, rule, receiptSample,
                    journey.approverId, journey.operationId)
                fixture.port.issueResult = LocalActionApprovalResult.Available(attached)
                val controller = fixture.controller
                controller.reviewActionApproval(journey.operationId, fixture.token())
                runCurrent()
                controller.confirmActionApproval(true, fixture.token())
                controller.openActionApproverPassword(fixture.token())
                val password = charArrayOf('s', 'y', 'n', 't', 'h', 'e', 't', 'i', 'c')
                val login = if (journey.approverId == inputs.requester) "REQUESTER-R" else "APPROVER-A"
                controller.issueActionApproval(login, password, fixture.token())
                assertTrue(password.all { it == '\u0000' })
                runCurrent()
                val call = fixture.port.issues.single()
                assertEquals(journey.operationId, call.operationId)
                assertEquals(journey.confirmationDigest, call.confirmation)
                assertEquals(login, call.login)
                assertTrue(call.borrowedPassword.all { it == '\u0000' })
                val presentation = assertNotNull(controller.state.value.actionApproval)
                val approval = assertNotNull(presentation.approval)
                assertEquals(journey.operationId, presentation.operationId)
                assertEquals(receiptSample, approval.approvalId)
                assertEquals(journey.approverId, approval.approverId)
                assertEquals(inputs.requester, assertNotNull(approval.review).requestorId)
                assertEquals(LocalActionApprovalPhase.ATTACHED, approval.phase)
                assertNull(presentation.confirmedDigest)
                assertFalse(presentation.passwordEntry)
                assertEquals(journey.operationId, controller.state.value.operationId)
                assertEquals(inputs.summary, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation.summary)
                fixture.port.assertNoBusinessCalls()
                assertRequesterSessionUnchanged(fixture)
            }
        }
    }

    @Test
    fun aaw05And06LostIssuanceReplyKeepsOriginalTargetAndRequiresLookupWithoutReissue() = runTest {
        withController { fixture ->
            val controller = fixture.controller
            fixture.port.issueResult = LocalActionApprovalResult.Unavailable(inputs.operation, LocalServiceProblem.STORAGE_UNCERTAIN)
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            controller.confirmActionApproval(true, fixture.token())
            controller.openActionApproverPassword(fixture.token())
            controller.issueActionApproval("APPROVER-A", charArrayOf('u', 'n', 'k', 'n', 'o', 'w', 'n'), fixture.token())
            runCurrent()
            val uncertain = assertNotNull(controller.state.value.actionApproval)
            assertTrue(uncertain.lookupRequired)
            assertNotNull(uncertain.problem)
            assertFalse(uncertain.confirmed)
            assertFalse(uncertain.passwordEntry)
            assertEquals(inputs.operation, uncertain.operationId)

            controller.confirmActionApproval(true, fixture.token())
            controller.openActionApproverPassword(fixture.token())
            controller.issueActionApproval("APPROVER-A", charArrayOf('a', 'g', 'a', 'i', 'n'), fixture.token())
            runCurrent()
            assertEquals(1, fixture.port.issues.size)
            val savedIdSample = DomainId.random()
            fixture.port.approvalLookupResult = LocalActionApprovalResult.Available(
                inputs.pending(LocalActionApprovalPhase.ISSUE_UNRESOLVED, approvalId = savedIdSample))
            controller.lookupActionApproval(inputs.operation, fixture.token())
            runCurrent()
            assertEquals(savedIdSample, assertNotNull(controller.state.value.actionApproval?.approval).approvalId)
            assertTrue(assertNotNull(controller.state.value.actionApproval).lookupRequired)

            fixture.port.approvalLookupResult = LocalActionApprovalResult.Available(
                inputs.pending(LocalActionApprovalPhase.ATTACHED, approvalId = savedIdSample))
            controller.lookupActionApproval(inputs.operation, fixture.token())
            runCurrent()
            assertEquals(listOf(inputs.operation, inputs.operation), fixture.port.approvalLookups)
            assertEquals(1, fixture.port.issues.size)
            assertEquals(inputs.operation, controller.state.value.operationId)
            assertEquals(savedIdSample, assertNotNull(controller.state.value.actionApproval?.approval).approvalId)
            fixture.port.assertNoBusinessCalls()
            assertRequesterSessionUnchanged(fixture)
        }
    }

    @Test
    fun aaw04And07KnownSessionLossClearsPrivateApprovalAndFencesALateIssuedReceipt() = runTest {
        withController { fixture ->
            val controller = fixture.controller
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            controller.confirmActionApproval(true, fixture.token())
            controller.openActionApproverPassword(fixture.token())
            val held = fixture.port.holdIssue()
            val oldToken = fixture.token()
            controller.issueActionApproval("APPROVER-A", charArrayOf('h', 'e', 'l', 'd'), oldToken)
            runCurrent()
            held.entered.await()
            fixture.session.lifecycle.value = LocalSessionLifecycle.REAUTHENTICATION_REQUIRED
            runCurrent()
            assertNull(controller.state.value.actionApproval)
            assertFalse(controller.actionApprovalDisplayCurrent(oldToken))
            held.reply.complete(LocalActionApprovalResult.Available(inputs.pending(LocalActionApprovalPhase.ATTACHED)))
            runCurrent()
            held.returned.await()
            assertNull(controller.state.value.actionApproval)
            assertTrue(fixture.port.issues.single().borrowedPassword.all { it == '\u0000' })
            controller.issueActionApproval("APPROVER-A", charArrayOf('s', 't', 'a', 'l', 'e'), oldToken)
            runCurrent()
            assertEquals(1, fixture.port.issues.size)
            assertEquals(inputs.operation, fixture.port.pendingOperation.operationId)
            assertEquals(inputs.summary, fixture.port.pendingOperation.summary)
            fixture.port.assertNoBusinessCalls()
        }
    }

    @Test
    fun aaw07EntryClosureBeforeQueuedAuthenticationCannotDeliverApprovalOrRetainPresentation() = runTest {
        withController { fixture ->
            val controller = fixture.controller
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            controller.confirmActionApproval(true, fixture.token())
            controller.openActionApproverPassword(fixture.token())
            val callerPassword = charArrayOf('q', 'u', 'e', 'u', 'e', 'd')
            controller.issueActionApproval("APPROVER-A", callerPassword, fixture.token())
            controller.deactivate()
            runCurrent()
            assertTrue(callerPassword.all { it == '\u0000' })
            assertTrue(fixture.port.issues.isEmpty())
            assertNull(controller.state.value.actionApproval)
            assertEquals(inputs.operation, fixture.port.pendingOperation.operationId)
            fixture.port.assertNoBusinessCalls()
        }
    }

    @Test
    fun aaw07AttachmentReplacementKeepsOriginalRecoveryTargetAndDropsOldConfirmation() = runTest {
        withController { fixture ->
            val original = fixture.controller
            original.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            original.confirmActionApproval(true, fixture.token())
            original.openActionApproverPassword(fixture.token())
            val oldToken = fixture.token()
            val freshPort = ControlledAawClientUiPort()
            val current = fixture.replaceAttachment(freshPort)
            assertNull(current.state.value.actionApproval)
            assertEquals(inputs.operation, current.state.value.operationId)
            assertTrue(current.state.value.attachmentReviewRequired)
            assertTrue(freshPort.previews.isEmpty())
            assertTrue(freshPort.issues.isEmpty())
            current.reviewAttachment(fixture.token())
            runCurrent()
            assertEquals(inputs.operation, current.state.value.pending.single().operationId)
            original.issueActionApproval("APPROVER-A", charArrayOf('s', 't', 'a', 'l', 'e'), oldToken)
            current.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            val review = assertNotNull(current.state.value.actionApproval)
            assertEquals(inputs.operation, review.operationId)
            assertNull(review.confirmedDigest)
            assertFalse(review.passwordEntry)
            assertTrue(fixture.port.issues.isEmpty())
            assertTrue(freshPort.issues.isEmpty())
            fixture.port.assertNoBusinessCalls()
            freshPort.assertNoBusinessCalls()
            assertRequesterSessionUnchanged(fixture)
        }
    }

    @Test
    fun aaw03And07DifferentReturnedRequesterOrOperationCannotOpenApproverCredentials() = runTest {
        val invalidReplies = listOf(
            inputs.pending(LocalActionApprovalPhase.READY_TO_CONFIRM, operationId = inputs.conflictingOperation),
            inputs.pending(LocalActionApprovalPhase.READY_TO_CONFIRM, requestor = inputs.otherRequester),
        )
        invalidReplies.forEach { reply ->
            withController { fixture ->
                fixture.port.previewResult = LocalActionApprovalResult.Available(reply)
                fixture.controller.reviewActionApproval(inputs.operation, fixture.token())
                runCurrent()
                val failed = assertNotNull(fixture.controller.state.value.actionApproval)
                assertEquals(inputs.operation, failed.operationId)
                assertNotNull(failed.problem)
                assertFalse(failed.canConfirm)
                fixture.controller.confirmActionApproval(true, fixture.token())
                fixture.controller.openActionApproverPassword(fixture.token())
                fixture.controller.issueActionApproval("APPROVER-A", charArrayOf('w', 'r', 'o', 'n', 'g'), fixture.token())
                runCurrent()
                assertTrue(fixture.port.issues.isEmpty())
                assertFalse(assertNotNull(fixture.controller.state.value.actionApproval).passwordEntry)
                fixture.port.assertNoBusinessCalls()
            }
        }
    }
}

private fun assertRequesterSessionUnchanged(fixture: AawClientUiFixture) {
    assertEquals(AawClientUiInputs.requester, fixture.session.display.actorId)
    assertEquals(listOf("synthetic-requester"), fixture.signIns)
    assertEquals(0, fixture.session.closedCount)
}

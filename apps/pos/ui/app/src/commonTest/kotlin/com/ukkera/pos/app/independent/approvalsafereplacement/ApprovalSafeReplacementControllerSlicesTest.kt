@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.approvalsafereplacement

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.approvalsafereplacement.AsrUiFixture.Companion.withAsrController
import com.ukkera.pos.app.independent.arrivalapprovalclient.AawClientUiInputs
import com.ukkera.pos.security.PayloadDigest
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
 * Frozen ASR v1 controller/presentation slices. Real shared controller/runtime,
 * confirmation, secret-copy cleanup and callback fences over supplied DTO DATA.
 * These observations do not authenticate anyone, retire/issue an approval,
 * commit business work, qualify native history or exercise actual UI devices.
 * All complete cases and native/platform variants remain NOT_RUN/UNBOUND.
 */
class ApprovalSafeReplacementControllerSlicesTest {
    private val inputs = AawClientUiInputs

    @Test
    fun asr01And02And13RetiredDisplayOrModalReopenNeverAutomaticallyBeginsIssuesOrSubmits() = runTest {
        for (journey in inputs.journeys) {
            val retiredData = asrUiPendingData(LocalActionApprovalPhase.RETIRED, journey = journey)
            withAsrController(retiredData) { fixture ->
                val controller = fixture.controller
                controller.reviewActionApproval(journey.operationId, fixture.token())
                runCurrent()
                val presentation = assertNotNull(controller.state.value.actionApproval)
                assertEquals(retiredData.actionApproval, presentation.approval)
                assertFalse(presentation.canConfirm)
                controller.confirmActionApproval(true, fixture.token())
                controller.openActionApproverPassword(fixture.token())
                controller.issueActionApproval("APPROVER-A", charArrayOf('n', 'o', 'n', 'e'), fixture.token())
                controller.retireActionApproval(fixture.token())
                controller.submit(journey.operationId, fixture.token())
                runCurrent()
                assertEquals(retiredData, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation)
                assertFalse(assertNotNull(controller.state.value.actionApproval).passwordEntry)
                controller.closeActionApproval(fixture.token())
                assertNull(controller.state.value.actionApproval)
                controller.reviewActionApproval(journey.operationId, fixture.token())
                runCurrent()
                assertEquals(retiredData.actionApproval, controller.state.value.actionApproval?.approval)
                assertEquals(listOf(journey.operationId, journey.operationId), fixture.port.approvalLookups)
                assertTrue(fixture.port.begins.isEmpty())
                assertTrue(fixture.port.retirements.isEmpty())
                assertTrue(fixture.port.previews.isEmpty())
                assertTrue(fixture.port.issues.isEmpty())
                fixture.port.assertNoBusinessCalls()
                assertRequesterDisplayUnchanged(fixture)
            }
        }
    }

    @Test
    fun asr01And08And13ExplicitBeginPreviewConfirmationAndOwnPasswordAreSeparateFromBusinessSubmit() = runTest {
        for (journey in inputs.journeys) {
            val retiredData = asrUiPendingData(LocalActionApprovalPhase.RETIRED, journey = journey)
            val archive = asrUiArchivesData(1, journey)
            val previewData = asrUiPendingData(LocalActionApprovalPhase.PREVIEW_REQUIRED, 1, archive, journey)
            val readyData = asrUiPendingData(LocalActionApprovalPhase.READY_TO_CONFIRM, 1, archive, journey)
            val attachedData = asrUiPendingData(LocalActionApprovalPhase.ATTACHED, 1, archive, journey)
            withAsrController(retiredData) { fixture ->
                val controller = fixture.controller
                fixture.port.beginResult = LocalActionApprovalResult.Available(previewData)
                fixture.port.previewResult = LocalActionApprovalResult.Available(readyData)
                fixture.port.issueResult = LocalActionApprovalResult.Available(attachedData)
                controller.reviewActionApproval(journey.operationId, fixture.token())
                runCurrent()
                controller.beginActionApprovalReplacement(fixture.token())
                runCurrent()
                val opened = assertNotNull(controller.state.value.actionApproval)
                assertEquals(LocalActionApprovalPhase.PREVIEW_REQUIRED, opened.approval?.phase)
                assertNull(opened.approval?.approvalId)
                assertNull(opened.approval?.review)
                assertFalse(opened.confirmed)
                assertFalse(opened.passwordEntry)
                assertTrue(fixture.port.previews.isEmpty())
                assertTrue(fixture.port.issues.isEmpty())
                fixture.port.assertNoBusinessCalls()
                assertEquals(retiredData.actionApproval?.approvalId, archive.single().approvalId)
                assertEquals(retiredData.actionApproval?.retirement, archive.single().retirement)
                controller.refreshActionApproval(fixture.token())
                runCurrent()
                assertTrue(assertNotNull(controller.state.value.actionApproval).canConfirm)
                assertFalse(assertNotNull(controller.state.value.actionApproval).confirmed)
                controller.confirmActionApproval(true, fixture.token())
                controller.openActionApproverPassword(fixture.token())
                val password = "SYN-ASR-OWN-AUTH-INPUT".toCharArray()
                val login = if (journey.approverId == inputs.requester) "REQUESTER-R" else "APPROVER-A"
                controller.issueActionApproval(login, password, fixture.token())
                assertTrue(password.all { it == '\u0000' })
                runCurrent()
                val call = fixture.port.issues.single()
                assertEquals(journey.operationId, call.operationId)
                assertEquals(journey.confirmationDigest, call.confirmation)
                assertEquals(login, call.login)
                assertTrue(call.borrowedPassword.all { it == '\u0000' })
                val issuedDisplay = assertNotNull(controller.state.value.actionApproval)
                assertEquals(attachedData.actionApproval, issuedDisplay.approval)
                assertNull(issuedDisplay.confirmedDigest)
                assertFalse(issuedDisplay.passwordEntry)
                assertEquals(archive, issuedDisplay.approval?.retiredApprovals)
                assertEquals(inputs.requester, issuedDisplay.approval?.review?.requestorId)
                assertEquals(journey.operationId, controller.state.value.operationId)
                assertEquals(inputs.summary, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation.summary)
                assertEquals(listOf(journey.operationId), fixture.port.begins)
                assertEquals(listOf(journey.operationId), fixture.port.previews)
                fixture.port.assertNoBusinessCalls()
                assertRequesterDisplayUnchanged(fixture)
            }
        }
    }

    @Test
    fun asr07UncertainRetirementRetainsCurrentDisplayAndOnlyLookupCanRecoverIt() = runTest {
        val attachedData = asrUiPendingData()
        val unknownData = asrUiPendingData(LocalActionApprovalPhase.RETIRE_UNRESOLVED)
        val retiredData = asrUiPendingData(LocalActionApprovalPhase.RETIRED)
        withAsrController(attachedData) { fixture ->
            val controller = fixture.controller
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            controller.retireActionApproval(fixture.token())
            runCurrent()
            val uncertain = assertNotNull(controller.state.value.actionApproval)
            assertTrue(uncertain.lookupRequired)
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, uncertain.problem)
            assertEquals(attachedData.actionApproval, uncertain.approval)
            repeat(2) {
                controller.retireActionApproval(fixture.token())
                controller.beginActionApprovalReplacement(fixture.token())
                controller.refreshActionApproval(fixture.token())
                controller.confirmActionApproval(true, fixture.token())
                controller.openActionApproverPassword(fixture.token())
                controller.issueActionApproval("APPROVER-A", charArrayOf('h', 'e', 'l', 'd'), fixture.token())
                controller.submit(inputs.operation, fixture.token())
            }
            runCurrent()
            assertEquals(1, fixture.port.retirements.size)
            assertTrue(fixture.port.begins.isEmpty())
            assertTrue(fixture.port.previews.isEmpty())
            assertTrue(fixture.port.issues.isEmpty())
            fixture.port.lookupResult = LocalActionApprovalResult.Available(unknownData)
            controller.lookupActionApproval(inputs.operation, fixture.token())
            runCurrent()
            assertEquals(unknownData.actionApproval, controller.state.value.actionApproval?.approval)
            assertTrue(assertNotNull(controller.state.value.actionApproval).lookupRequired)
            fixture.port.lookupResult = LocalActionApprovalResult.Available(retiredData)
            controller.lookupActionApproval(inputs.operation, fixture.token())
            runCurrent()
            assertEquals(retiredData.actionApproval, controller.state.value.actionApproval?.approval)
            assertFalse(assertNotNull(controller.state.value.actionApproval).lookupRequired)
            assertEquals(1, fixture.port.retirements.size)
            assertTrue(fixture.port.begins.isEmpty())
            assertTrue(fixture.port.issues.isEmpty())
            fixture.port.assertNoBusinessCalls()
            assertRequesterDisplayUnchanged(fixture)
        }
    }

    @Test
    fun asr05And08And11UnknownIssuanceKeepsLatestIdAndArchiveAndLookupNeverSubmits() = runTest {
        for (replacement in listOf(false, true)) {
            val index = if (replacement) 1 else 0
            val archive = asrUiArchivesData(index)
            val unknownData = asrUiPendingData(LocalActionApprovalPhase.ISSUE_UNRESOLVED, index, archive)
            withAsrController(unknownData) { fixture ->
                val controller = fixture.controller
                controller.reviewActionApproval(inputs.operation, fixture.token())
                runCurrent()
                assertTrue(assertNotNull(controller.state.value.actionApproval).lookupRequired)
                repeat(2) {
                    controller.beginActionApprovalReplacement(fixture.token())
                    controller.retireActionApproval(fixture.token())
                    controller.refreshActionApproval(fixture.token())
                    controller.confirmActionApproval(true, fixture.token())
                    controller.openActionApproverPassword(fixture.token())
                    controller.issueActionApproval("APPROVER-A", charArrayOf('n', 'o', 'n', 'e'), fixture.token())
                }
                runCurrent()
                assertEquals(unknownData.actionApproval, controller.state.value.actionApproval?.approval)
                val attachedData = asrUiPendingData(LocalActionApprovalPhase.ATTACHED, index, archive)
                fixture.port.lookupResult = LocalActionApprovalResult.Available(attachedData)
                controller.lookupActionApproval(inputs.operation, fixture.token())
                runCurrent()
                val recovered = assertNotNull(controller.state.value.actionApproval)
                assertEquals(asrUiApprovalCarrier(index), recovered.approval?.approvalId)
                assertEquals(archive, recovered.approval?.retiredApprovals)
                assertFalse(recovered.passwordEntry)
                assertFalse(recovered.confirmed)
                assertTrue(fixture.port.begins.isEmpty())
                assertTrue(fixture.port.retirements.isEmpty())
                assertTrue(fixture.port.previews.isEmpty())
                assertTrue(fixture.port.issues.isEmpty())
                fixture.port.assertNoBusinessCalls()
                assertRequesterDisplayUnchanged(fixture)
            }
        }
    }

    @Test
    fun asr13CancelBeforeRetirementOrDuringReplacementReviewRetainsOriginalAndArchivedData() = runTest {
        val states = listOf(asrUiPendingData(), asrUiPendingData(LocalActionApprovalPhase.PREVIEW_REQUIRED,
            1, asrUiArchivesData(1)))
        for (initial in states) withAsrController(initial) { fixture ->
            val controller = fixture.controller
            fixture.port.previewResult = LocalActionApprovalResult.Available(asrUiPendingData(
                LocalActionApprovalPhase.READY_TO_CONFIRM, 1, assertNotNull(initial.actionApproval).retiredApprovals))
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            controller.confirmActionApproval(true, fixture.token())
            controller.openActionApproverPassword(fixture.token())
            controller.closeActionApproverPassword(fixture.token())
            assertFalse(assertNotNull(controller.state.value.actionApproval).passwordEntry)
            val retained = assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation
            controller.closeActionApproval(fixture.token())
            assertNull(controller.state.value.actionApproval)
            assertEquals(retained, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation)
            assertEquals(inputs.summary, retained.summary)
            assertEquals(initial.actionApproval?.retiredApprovals, retained.actionApproval?.retiredApprovals)
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            assertFalse(assertNotNull(controller.state.value.actionApproval).confirmed)
            assertFalse(assertNotNull(controller.state.value.actionApproval).passwordEntry)
            assertTrue(fixture.port.retirements.isEmpty())
            assertTrue(fixture.port.begins.isEmpty())
            assertTrue(fixture.port.issues.isEmpty())
            fixture.port.assertNoBusinessCalls()
            assertRequesterDisplayUnchanged(fixture)
        }
    }

    @Test
    fun asr13UnconfirmedCancelledAndRepeatedOwnAuthenticationInputsCannotCreateAnotherIssue() = runTest {
        for (journey in inputs.journeys) {
            val archive = asrUiArchivesData(1, journey)
            val initial = asrUiPendingData(LocalActionApprovalPhase.PREVIEW_REQUIRED, 1, archive, journey)
            withAsrController(initial) { fixture ->
                val controller = fixture.controller
                fixture.port.previewResult = LocalActionApprovalResult.Available(asrUiPendingData(
                    LocalActionApprovalPhase.READY_TO_CONFIRM, 1, archive, journey))
                fixture.port.issueResult = LocalActionApprovalResult.Available(asrUiPendingData(
                    LocalActionApprovalPhase.REFUSED, 1, archive, journey))
                controller.reviewActionApproval(journey.operationId, fixture.token())
                runCurrent()
                val unconfirmed = charArrayOf('n', 'o', 'n', 'e')
                controller.openActionApproverPassword(fixture.token())
                controller.issueActionApproval("APPROVER-A", unconfirmed, fixture.token())
                assertTrue(unconfirmed.all { it == '\u0000' })
                controller.confirmActionApproval(true, fixture.token())
                controller.openActionApproverPassword(fixture.token())
                controller.closeActionApproverPassword(fixture.token())
                val cancelled = charArrayOf('c', 'a', 'n', 'c', 'e', 'l')
                controller.issueActionApproval("APPROVER-A", cancelled, fixture.token())
                assertTrue(cancelled.all { it == '\u0000' })
                assertTrue(fixture.port.issues.isEmpty())
                controller.openActionApproverPassword(fixture.token())
                val held = fixture.port.hold(AsrUiCallKind.ISSUE)
                val login = if (journey.approverId == inputs.requester) "REQUESTER-R" else "APPROVER-A"
                controller.issueActionApproval(login, charArrayOf('o', 'w', 'n', 'x'), fixture.token())
                runCurrent()
                held.entered.await()
                repeat(2) {
                    controller.issueActionApproval(login, charArrayOf('a', 'g', 'a', 'i', 'n'), fixture.token())
                }
                held.reply.complete(fixture.port.issueResult)
                runCurrent()
                held.returned.await()
                val refusal = assertNotNull(controller.state.value.actionApproval)
                assertEquals(LocalActionApprovalPhase.REFUSED, refusal.approval?.phase)
                assertEquals(LocalActionApprovalRefusal.AUTHORIZATION_REFUSED, refusal.approval?.refusal)
                assertEquals(asrUiApprovalCarrier(1), refusal.approval?.approvalId)
                assertEquals(archive, refusal.approval?.retiredApprovals)
                assertFalse(refusal.canConfirm)
                assertFalse(refusal.passwordEntry)
                val call = fixture.port.issues.single()
                assertEquals(login, call.login)
                assertEquals(journey.confirmationDigest, call.confirmation)
                assertTrue(call.borrowedPassword.all { it == '\u0000' })
                fixture.port.assertNoBusinessCalls()
                assertRequesterDisplayUnchanged(fixture)
            }
        }
        // The queued copy is also cleared if the entry closes before handoff starts.
        withAsrController(asrUiPendingData(LocalActionApprovalPhase.PREVIEW_REQUIRED)) { fixture ->
            fixture.port.previewResult = LocalActionApprovalResult.Available(asrUiPendingData(LocalActionApprovalPhase.READY_TO_CONFIRM))
            fixture.controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            fixture.controller.confirmActionApproval(true, fixture.token())
            fixture.controller.openActionApproverPassword(fixture.token())
            val queued = charArrayOf('q', 'u', 'e', 'u', 'e', 'd')
            fixture.controller.issueActionApproval("APPROVER-A", queued, fixture.token())
            fixture.controller.deactivate()
            runCurrent()
            assertTrue(queued.all { it == '\u0000' })
            assertTrue(fixture.port.issues.isEmpty())
            assertNull(fixture.controller.state.value.actionApproval)
            fixture.port.assertNoBusinessCalls()
        }
    }

    @Test
    fun asr08And11And13LateRetirementBeginIssueOrLookupCallbacksCannotReviveARetiredWorkspace() = runTest {
        for (kind in listOf(AsrUiCallKind.RETIRE, AsrUiCallKind.BEGIN, AsrUiCallKind.ISSUE, AsrUiCallKind.LOOKUP)) {
            for (fence in listOf("lifecycle", "deactivate", "attachment")) {
                val archive = asrUiArchivesData(1)
                val initial = when (kind) {
                    AsrUiCallKind.RETIRE -> asrUiPendingData()
                    AsrUiCallKind.BEGIN -> asrUiPendingData(LocalActionApprovalPhase.RETIRED)
                    AsrUiCallKind.ISSUE -> asrUiPendingData(LocalActionApprovalPhase.PREVIEW_REQUIRED, 1, archive)
                    else -> asrUiPendingData(LocalActionApprovalPhase.ISSUE_UNRESOLVED, 1, archive)
                }
                withAsrController(initial) { fixture ->
                    val old = fixture.controller
                    fixture.port.previewResult = LocalActionApprovalResult.Available(asrUiPendingData(
                        LocalActionApprovalPhase.READY_TO_CONFIRM, 1, archive))
                    old.reviewActionApproval(inputs.operation, fixture.token())
                    runCurrent()
                    val held = fixture.port.hold(kind)
                    if (kind == AsrUiCallKind.ISSUE) {
                        old.confirmActionApproval(true, fixture.token())
                        old.openActionApproverPassword(fixture.token())
                    }
                    val oldToken = fixture.token()
                    when (kind) {
                        AsrUiCallKind.RETIRE -> old.retireActionApproval(oldToken)
                        AsrUiCallKind.BEGIN -> old.beginActionApprovalReplacement(oldToken)
                        AsrUiCallKind.ISSUE -> {
                            old.issueActionApproval("APPROVER-A", charArrayOf('h', 'e', 'l', 'd'), oldToken)
                        }
                        else -> old.lookupActionApproval(inputs.operation, oldToken)
                    }
                    runCurrent()
                    held.entered.await()
                    var freshPort: ControlledAsrUiPort? = null
                    when (fence) {
                        "lifecycle" -> fixture.session.lifecycle.value = LocalSessionLifecycle.REAUTHENTICATION_REQUIRED
                        "deactivate" -> old.deactivate()
                        else -> {
                            freshPort = ControlledAsrUiPort(initial)
                            fixture.replaceAttachment(freshPort)
                        }
                    }
                    runCurrent()
                    assertNull(old.state.value.actionApproval)
                    assertFalse(old.actionApprovalDisplayCurrent(oldToken))
                    // An old AP0 reply cannot select itself into the fresh AP1 workspace.
                    held.reply.complete(LocalActionApprovalResult.Available(asrUiPendingData(LocalActionApprovalPhase.RETIRED)))
                    runCurrent()
                    held.returned.await()
                    assertNull(old.state.value.actionApproval)
                    assertNull(fixture.controller.state.value.actionApproval)
                    old.issueActionApproval("APPROVER-A", charArrayOf('s', 't', 'a', 'l', 'e'), oldToken)
                    runCurrent()
                    if (kind == AsrUiCallKind.ISSUE) {
                        assertEquals(1, fixture.port.issues.size)
                        assertTrue(fixture.port.issues.single().borrowedPassword.all { it == '\u0000' })
                    } else assertTrue(fixture.port.issues.isEmpty())
                    if (freshPort != null) {
                        assertTrue(fixture.controller.state.value.attachmentReviewRequired)
                        fixture.controller.reviewAttachment(fixture.token())
                        runCurrent()
                        assertEquals(initial.operationId, fixture.controller.state.value.pending.single().operationId)
                        assertEquals(initial.actionApproval?.approvalId,
                            fixture.controller.state.value.pending.single().actionApproval?.approvalId)
                        assertEquals(initial.actionApproval?.retiredApprovals,
                            fixture.controller.state.value.pending.single().actionApproval?.retiredApprovals)
                        assertTrue(freshPort.begins.isEmpty())
                        assertTrue(freshPort.retirements.isEmpty())
                        assertTrue(freshPort.issues.isEmpty())
                        freshPort.assertNoBusinessCalls()
                    }
                    fixture.port.assertNoBusinessCalls()
                }
            }
        }
    }

    @Test
    fun asr04And10And14MismatchedReturnedRequesterOperationOrActionCannotOpenReplacementAuthentication() = runTest {
        val archive = asrUiArchivesData(1)
        val wrongOperation = asrUiPendingData(LocalActionApprovalPhase.READY_TO_CONFIRM, 1, archive,
            operationId = inputs.conflictingOperation)
        val wrongRequester = asrUiPendingData(LocalActionApprovalPhase.READY_TO_CONFIRM, 1, archive,
            requestor = inputs.otherRequester)
        val ready = asrUiPendingData(LocalActionApprovalPhase.READY_TO_CONFIRM, 1, archive)
        val originalRule = assertNotNull(ready.actionApproval).rule
        val wrongRule = LocalArrivalRuleOptionV2(DomainId("a2000000-0000-4000-8000-00000000000c"),
            LocalAction.ARRIVAL_QUALIFY, false, originalRule.minimumDocuments, originalRule.maximumDocuments,
            originalRule.media, originalRule.purposes, originalRule.retention, originalRule.approvalRoute,
            originalRule.ruleDetails)
        val originalReview = assertNotNull(ready.actionApproval?.review)
        val wrongAction = ready.copy(action = LocalAction.ARRIVAL_QUALIFY, actionApproval = ready.actionApproval?.copy(
            rule = wrongRule, review = LocalActionApprovalReview(inputs.operation, LocalAction.ARRIVAL_QUALIFY,
                inputs.requester, originalReview.requestorPolicyRevision, originalReview.requirement,
                PayloadDigest("8b420ae7f162badef75fdb2d09cb5f42cdcdc672b45fe239d9d76a3e6566717a"),
                wrongRule, inputs.summary)))
        for (bad in listOf(wrongOperation, wrongRequester, wrongAction)) {
            val retiredData = asrUiPendingData(LocalActionApprovalPhase.RETIRED)
            withAsrController(retiredData) { fixture ->
                val controller = fixture.controller
                fixture.port.beginResult = LocalActionApprovalResult.Available(bad)
                controller.reviewActionApproval(inputs.operation, fixture.token())
                runCurrent()
                controller.beginActionApprovalReplacement(fixture.token())
                runCurrent()
                val refused = assertNotNull(controller.state.value.actionApproval)
                assertEquals(inputs.operation, refused.operationId)
                assertEquals(retiredData.actionApproval, refused.approval)
                assertNotNull(refused.problem)
                assertFalse(refused.canConfirm)
                controller.confirmActionApproval(true, fixture.token())
                controller.openActionApproverPassword(fixture.token())
                val password = charArrayOf('w', 'r', 'o', 'n', 'g')
                controller.issueActionApproval("APPROVER-A", password, fixture.token())
                runCurrent()
                assertTrue(password.all { it == '\u0000' })
                assertTrue(fixture.port.issues.isEmpty())
                assertEquals(inputs.operation, controller.state.value.operationId)
                assertEquals(retiredData, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation)
                fixture.port.assertNoBusinessCalls()
            }
        }
    }

    @Test
    fun asr16CurrentRetirementDisplayKeepsSixteenPreviousPlusCurrentWhenGatewayRefusesAnotherBegin() = runTest {
        val archive = asrUiArchivesData(16)
        val attachedData = asrUiPendingData(LocalActionApprovalPhase.ATTACHED, 16, archive)
        val retiredData = asrUiPendingData(LocalActionApprovalPhase.RETIRED, 16, archive)
        withAsrController(attachedData) { fixture ->
            val controller = fixture.controller
            fixture.port.retirementResult = LocalActionApprovalResult.Available(retiredData)
            fixture.port.beginResult = LocalActionApprovalResult.Unavailable(inputs.operation, LocalServiceProblem.RETRY_LATER)
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            controller.retireActionApproval(fixture.token())
            runCurrent()
            assertEquals(retiredData.actionApproval, controller.state.value.actionApproval?.approval)
            assertTrue(fixture.port.begins.isEmpty())
            repeat(2) {
                controller.beginActionApprovalReplacement(fixture.token())
                runCurrent()
                val held = assertNotNull(controller.state.value.actionApproval)
                assertEquals(LocalServiceProblem.RETRY_LATER, held.problem)
                assertEquals(retiredData.actionApproval, held.approval)
                assertFalse(held.canConfirm)
            }
            fixture.port.lookupResult = LocalActionApprovalResult.Available(retiredData)
            controller.closeActionApproval(fixture.token())
            controller.reviewActionApproval(inputs.operation, fixture.token())
            runCurrent()
            val retained = assertNotNull(controller.state.value.actionApproval?.approval)
            assertEquals(archive, retained.retiredApprovals)
            assertEquals(asrUiApprovalCarrier(16), retained.approvalId)
            assertEquals(17, (retained.retiredApprovals.map { it.approvalId } + assertNotNull(retained.approvalId)).distinct().size)
            assertEquals(listOf(inputs.operation), fixture.port.retirements)
            assertEquals(listOf(inputs.operation, inputs.operation), fixture.port.begins)
            assertTrue(fixture.port.previews.isEmpty())
            assertTrue(fixture.port.issues.isEmpty())
            fixture.port.assertNoBusinessCalls()
            assertRequesterDisplayUnchanged(fixture)
        }
    }
}

private fun assertRequesterDisplayUnchanged(fixture: AsrUiFixture) {
    assertEquals(AawClientUiInputs.requester, fixture.session.display.actorId)
    assertEquals(AawClientUiInputs.company, fixture.session.display.companyId)
    assertEquals(AawClientUiInputs.branch, fixture.session.display.branchId)
    assertEquals(listOf("synthetic-requester"), fixture.signIns)
    assertEquals(0, fixture.session.closedCount)
}

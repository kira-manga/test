package com.ukkera.pos.app.independent.returnrightsauthoringv1

import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.SecurityRoot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual runtime/entry/controller cleanup with negative controlled ports; no native or Master authority is assumed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReturnRightsAuthoringControllerTest {
    private val inputs = FrozenReturnRightsAuthoringUiInputs

    @Test
    fun raa15ReturnRightsAndOtherConfigurationCardsCannotClearEachOthersDirtyOrCriticalGuards() = runTest {
        withController { ui, controller ->
            fun expected(value: LeaveGuard) = assertEquals(value, ui.runtime.navigator.state.value.guard)
            controller.companyCatalogGuard(true, false); controller.returnRightsGuard(true, false); expected(LeaveGuard.DIRTY)
            controller.companyCatalogGuard(false, false); expected(LeaveGuard.DIRTY)
            controller.foodConfigurationGuard(false, true); expected(LeaveGuard.CRITICAL)
            controller.returnRightsGuard(false, false); expected(LeaveGuard.CRITICAL)
            controller.returnRightsGuard(true, false); controller.foodConfigurationGuard(false, false); expected(LeaveGuard.DIRTY)
            controller.companyCatalogGuard(false, true); controller.returnRightsGuard(false, false); expected(LeaveGuard.CRITICAL)
            controller.companyCatalogGuard(false, false); expected(LeaveGuard.CLEAN)
            controller.returnRightsGuard(false, true); controller.foodConfigurationGuard(true, false); expected(LeaveGuard.CRITICAL)
            controller.returnRightsGuard(false, false); expected(LeaveGuard.DIRTY)
            controller.foodConfigurationGuard(false, false); expected(LeaveGuard.CLEAN)
            ui.session.ports.forEach { it.assertNoCommands() }
        }
    }

    @Test
    fun raa11Raa15AcceptedCurrentDenialSynchronouslyClearsEntryHeldDocumentaryInputWithoutEndingTheSession() = runTest {
        withController { ui, controller ->
            val drafts = ui.entry().returnRightsDrafts
            drafts.populateUnsigned()
            controller.returnRightsGuard(true, false)
            val captured = drafts.lifetime.identity
            var cleared = 0
            drafts.lifetime.attach(captured) {
                assertFalse(drafts.lifetime.isCurrent(captured))
                assertEquals(LocalServiceProblem.DENIED, controller.state.value.readProblem)
                cleared++
            }
            val reads = ui.session.port.readCalls
            val pending = ui.session.port.pendingCalls
            ui.session.port.pendingReply = LocalPendingResult.Unavailable(LocalServiceProblem.DENIED)
            controller.refreshConfigurationPending(ui.runtime.navigator.token())
            runCurrent()
            assertEquals(1, cleared)
            assertEquals(LocalServiceProblem.DENIED, controller.state.value.readProblem)
            assertNull(controller.state.value.page)
            assertFalse(drafts.hasDraft)
            assertNull(drafts.retainedActionId.value)
            assertFalse(drafts.approvalHeld.value)
            assertNull(drafts.notice.value)
            assertTrue(drafts.lifetime.accessDenied)
            assertTrue(ui.session.isCurrent())
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, ui.runtime.navigator.state.value.root)
            assertEquals(LeaveGuard.CLEAN, ui.runtime.navigator.state.value.guard)
            assertEquals(reads, ui.session.port.readCalls)
            assertEquals(pending + 1, ui.session.port.pendingCalls)
            ui.session.ports.forEach { it.assertNoCommands() }
        }
    }

    @Test
    fun raa15OldDeniedCallbackCannotClearReplacementInputButFreshAcceptedDenialStillMustClearIt() = runTest {
        withController { ui, old ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { ui.runtime.monitorAuthenticatedSession() }
            runCurrent()
            val entry = ui.entry()
            val drafts = entry.returnRightsDrafts
            drafts.populateUnsigned()
            val oldIdentity = drafts.lifetime.identity
            val held = ui.session.port.holdNextPending()
            old.refreshConfigurationPending(ui.runtime.navigator.token())
            runCurrent()
            held.captured.await()
            val freshPort = ui.session.replaceAttachment()
            runCurrent()
            assertFalse(drafts.lifetime.isCurrent(oldIdentity))
            val current = assertNotNull(entry.localWorkflows(ui.runtime, ui.runtime.navigator.token()))
            current.activate(LocalWorkflow.CATALOG)
            drafts.populateUnsigned("D1")
            held.reply.complete(LocalPendingResult.Unavailable(LocalServiceProblem.DENIED))
            runCurrent()
            held.returned.await()
            assertFalse(entry.denyLocalConfigurationAccess(old))
            inputs.assertLiteralDraft(assertNotNull(drafts.editor.value).toDraft(), "D1")
            assertFalse(drafts.lifetime.accessDenied)
            assertNull(current.state.value.readProblem)
            freshPort.pendingReply = LocalPendingResult.Unavailable(LocalServiceProblem.DENIED)
            current.refreshConfigurationPending(ui.runtime.navigator.token())
            runCurrent()
            assertEquals(LocalServiceProblem.DENIED, current.state.value.readProblem)
            assertFalse(drafts.hasDraft)
            assertTrue(drafts.lifetime.accessDenied)
            ui.session.ports.forEach { it.assertNoCommands() }
        }
    }

    @Test
    fun raa15LocalLockClearsEntryInputAndOriginalCoordinatesEvenWhileItsAuthoringGuardIsCritical() = runTest {
        withController { ui, controller ->
            val drafts = ui.entry().returnRightsDrafts
            drafts.populateUnsigned()
            val captured = drafts.lifetime.identity
            controller.returnRightsGuard(true, true)
            assertEquals(LeaveGuard.CRITICAL, ui.runtime.navigator.state.value.guard)
            ui.runtime.lock(ui.runtime.navigator.token())
            assertEquals(SecurityRoot.BOOTSTRAP, ui.runtime.navigator.state.value.root)
            assertFalse(drafts.lifetime.isCurrent(captured))
            assertFalse(drafts.hasDraft)
            assertNull(drafts.retainedActionId.value)
            assertFalse(drafts.approvalHeld.value)
            assertNull(drafts.notice.value)
            ui.session.ports.forEach { it.assertNoCommands() }
        }
    }

    private suspend fun TestScope.withController(body: suspend TestScope.(ControlledReturnRightsAuthoringUi, LocalWorkflowController) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ui = ControlledReturnRightsAuthoringUi()
        try {
            ui.attach()
            val controller = assertNotNull(ui.entry().localWorkflows(ui.runtime, ui.runtime.navigator.token()))
            controller.activate(LocalWorkflow.CATALOG)
            runCurrent()
            body(this, ui, controller)
        } finally { ui.close(); Dispatchers.resetMain() }
    }
}

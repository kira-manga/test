@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.approvalretirementnoadmission

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.database.LocalActionApprovalNoAdmissionReason
import com.ukkera.pos.database.LocalActionApprovalRetireResult
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.*

/**
 * Frozen ARRNA v2 controller slices over the actual gateway and encrypted JVM journal.
 * Historical receipt/view/sign-in inputs and received negatives are controlled client
 * evidence, not native grants, authenticated originals or current-Master/business proof.
 * Held-tap lookup, transport replay, lost-negative fencing and whole cases stay UNBOUND.
 */
class ArrivalRetirementNoAdmissionControllerSlicesTest {
    @Test
    fun arrna08ExactCompletionRequiresSeparateRetryAndKeepsSameApprovalI0I1History() = runTest {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            withArrivalRetirementController(fixture) { ui ->
                val operation = fixture.inputs.operationId
                val i0 = assertNotNull(fixture.i0.actionApproval?.retirementAttempt)
                ui.review(operation)
                assertTrue(assertNotNull(ui.controller.state.value.actionApproval).canRetryRetirement)
                ui.controller.retireActionApproval(ui.token())
                runCurrent()
                assertTrue(ui.audit.retirements.isEmpty())

                ui.controller.retryActionApprovalRetirement(ui.token())
                ui.awaitIdle()
                val completedI1 = fixture.latest()
                val i1 = assertNotNull(completedI1.actionApproval?.retirementAttempt)
                assertNotEquals(i0.attemptId, i1.attemptId)
                assertNotNull(i1.noAdmissionReason)
                assertEquals(listOf(i0), completedI1.actionApproval?.completedRetirementAttempts)
                assertEquals(fixture.inputs.approvalId, completedI1.actionApproval?.approvalId)
                assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED,
                    ui.controller.state.value.actionApproval?.approval?.phase)
                assertFalse(assertIs<LocalOperationResult.Pending>(ui.controller.state.value.operation).operation.submitAllowed)

                // Drain, close/reenter and recreate from a fresh real store connection are passive.
                runCurrent()
                ui.controller.closeActionApproval(ui.token())
                ui.review(operation)
                ui.recreate()
                assertEquals(completedI1, fixture.latest())
                assertEquals(listOf(operation), ui.audit.retirements)
                ui.review(operation)
                assertTrue(assertNotNull(ui.controller.state.value.actionApproval).canRetryRetirement)

                val heldI2 = ArrivalRetirementHold()
                fixture.endpoint.onRetire = heldI2::receive
                try {
                    ui.controller.retryActionApprovalRetirement(ui.token())
                    val handoff = heldI2.entered.await()
                    val pendingI2 = assertNotNull(handoff.durable.actionApproval)
                    val i2 = assertNotNull(pendingI2.retirementAttempt)
                    assertTrue(i2.attemptId !in listOf(i0.attemptId, i1.attemptId))
                    assertTrue(i2.sequence > i1.sequence)
                    assertNull(i2.noAdmissionReason)
                    assertEquals(listOf(i0, i1), pendingI2.completedRetirementAttempts)
                    assertOriginalRequest(fixture.inputs.request, handoff.request)
                    assertEquals(fixture.inputs.confirmationDigest, handoff.attempt.confirmationDigest)
                    assertEquals(handoff.durable, fixture.latest())
                    fixture.assertOriginal(handoff.durable)

                    ui.controller.retryActionApprovalRetirement(ui.token())
                    runCurrent()
                    assertEquals(2, ui.audit.retirements.size)
                    // The working gate ignores this held tap; the required lookup row is UNBOUND.
                    heldI2.reply.complete(LocalActionApprovalRetireResult.NotAdmitted(handoff.attempt,
                        LocalActionApprovalNoAdmissionReason.QUEUE_FULL))
                    ui.awaitIdle()
                    val completedI2 = fixture.latest()
                    assertNotNull(completedI2.actionApproval?.retirementAttempt?.noAdmissionReason)
                    assertEquals(i2.attemptId, completedI2.actionApproval?.retirementAttempt?.attemptId)
                    assertEquals(listOf(i0, i1), completedI2.actionApproval?.completedRetirementAttempts)
                    fixture.assertOriginal(completedI2)
                    assertEquals(fixture.baselineRetirements + 2, fixture.endpoint.retirements.size)
                    ui.assertNoBusinessOrNewCeremony()
                } finally {
                    heldI2.reply.complete(LocalActionApprovalRetireResult.Unavailable)
                    ui.awaitIdle()
                }
            }
        }
    }

    @Test
    fun arrna03And07And08UncertaintySuppressesCachedSubmitAfterDialogCloseAndReopen() = runTest {
        for (outcome in listOf("generic denial", "unavailable", "completion-save failure")) {
            withArrivalRetirementNoAdmissionFixture(completedI0 = false) { fixture ->
                withArrivalRetirementController(fixture) { ui ->
                    val operation = fixture.inputs.operationId
                    ui.review(operation)
                    assertTrue(assertIs<LocalOperationResult.Pending>(ui.controller.state.value.operation).operation.submitAllowed)

                    // Capture a real older ATTACHED display, then finish I0 during fixture setup.
                    // I0 is durable before I1; its completion cannot refresh that cached UI hint.
                    assertIs<LocalActionApprovalResult.Available>(fixture.gateway.retireActionApproval(operation))
                    val completedI0 = fixture.latest()
                    val i0 = assertNotNull(completedI0.actionApproval?.retirementAttempt)
                    assertNotNull(i0.noAdmissionReason)
                    val baseline = fixture.endpoint.retirements.size
                    if (outcome == "completion-save failure") fixture.journal.failCompletion = true
                    else fixture.endpoint.onRetire = {
                        if (outcome == "generic denial") LocalActionApprovalRetireResult.Denied
                        else LocalActionApprovalRetireResult.Unavailable
                    }

                    ui.controller.retireActionApproval(ui.token())
                    assertFalse(assertIs<LocalOperationResult.Pending>(ui.controller.state.value.operation).operation.submitAllowed)
                    assertFalse(ui.controller.state.value.pending.single { it.operationId == operation }.submitAllowed)
                    ui.awaitIdle()
                    val pendingI1 = fixture.latest()
                    assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, pendingI1.actionApproval?.localPhase)
                    assertEquals(listOf(i0), pendingI1.actionApproval?.completedRetirementAttempts)
                    assertNull(pendingI1.actionApproval?.retirementAttempt?.noAdmissionReason)
                    ui.controller.closeActionApproval(ui.token())
                    assertNull(ui.controller.state.value.actionApproval)
                    assertTrue(ui.controller.state.value.pending.none { it.operationId == operation && it.submitAllowed })
                    (ui.controller.state.value.operation as? LocalOperationResult.Pending)?.let {
                        assertFalse(it.operation.submitAllowed)
                    }
                    ui.controller.submit(operation, ui.token())
                    ui.awaitIdle()
                    assertTrue(ui.audit.submissions.isEmpty())

                    ui.recreate()
                    ui.review(operation)
                    val recovered = assertNotNull(ui.controller.state.value.actionApproval)
                    assertTrue(recovered.lookupRequired)
                    assertFalse(recovered.canRetryRetirement)
                    assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, recovered.approval?.phase)
                    val lookups = ui.audit.approvalLookups.size
                    ui.controller.lookupActionApproval(operation, ui.token())
                    ui.awaitIdle()
                    assertEquals(lookups + 1, ui.audit.approvalLookups.size)
                    assertEquals(pendingI1, fixture.latest())
                    assertEquals(baseline + 1, fixture.endpoint.retirements.size)
                    fixture.assertOriginal(pendingI1)
                    ui.assertNoBusinessOrNewCeremony()
                }
            }
        }
    }

    @Test
    fun arrna08FreshRetryStillChecksCurrentClientSessionAccountAndRequestHint() = runTest {
        for (denial in listOf("ended credentials", "denied current session", "other account", "no request hint")) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                withArrivalRetirementController(fixture) { ui ->
                    ui.review(fixture.inputs.operationId)
                    assertTrue(assertNotNull(ui.controller.state.value.actionApproval).canRetryRetirement)
                    val original = fixture.latest()
                    when (denial) {
                        "ended credentials" -> fixture.credentials.active = false
                        "denied current session" -> fixture.endpoint.currentDenied = true
                        "other account" -> fixture.endpoint.view = fixture.inputs.sessionView(actor = fixture.inputs.approver)
                        else -> fixture.endpoint.view = fixture.inputs.sessionView(requestHint = false)
                    }
                    ui.controller.retryActionApprovalRetirement(ui.token())
                    ui.awaitRetirementFinished(1)
                    assertEquals(listOf(fixture.inputs.operationId), ui.audit.retirements)
                    assertEquals(fixture.baselineRetirements, fixture.endpoint.retirements.size)
                    assertEquals(original, fixture.latest())
                    fixture.assertOriginal(original)
                    ui.assertNoBusinessOrNewCeremony()
                }
            }
        }
    }

    @Test
    fun arrna08ObsoleteCompletedDeliveryCannotReviveLifecycleOrReplaceRecordB() = runTest {
        for (fence in listOf("lifecycle", "other entry")) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                withArrivalRetirementController(fixture) { ui ->
                    val operationA = fixture.inputs.operationId
                    ui.review(operationA)
                    val old = ui.controller
                    val delivery = ui.audit.holdNextRetirementDelivery()
                    old.retryActionApprovalRetirement(ui.token())
                    assertIs<LocalActionApprovalResult.Available>(delivery.entered.await())
                    val completedI1 = fixture.latest()
                    assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, completedI1.actionApproval?.localPhase)
                    assertEquals(listOf(assertNotNull(fixture.i0.actionApproval?.retirementAttempt)),
                        completedI1.actionApproval?.completedRetirementAttempts)

                    if (fence == "lifecycle") {
                        ui.session.lifecycle.value = LocalSessionLifecycle.REAUTHENTICATION_REQUIRED
                        old.state.first { it.actionApproval == null }
                        assertNull(old.state.value.actionApproval)
                        assertFalse(old.actionApprovalDisplayCurrent(ui.token()))
                        delivery.release.complete(Unit)
                        delivery.delivered.await()
                        ui.awaitRetirementFinished(1)
                        assertNull(old.state.value.actionApproval)
                        assertEquals(completedI1, fixture.latest())
                        assertEquals(fixture.baselineRetirements + 1, fixture.endpoint.retirements.size)
                    } else {
                        old.deactivate()
                        val inputsB = ArrivalRetirementNoAdmissionInputs("B")
                        fixture.saveHistoricalAttached(inputsB)
                        ui.recreate()
                        ui.review(inputsB.operationId)
                        val heldB = ArrivalRetirementHold()
                        fixture.endpoint.onRetire = heldB::receive
                        try {
                            ui.controller.retireActionApproval(ui.token())
                            val i3 = heldB.entered.await()
                            assertEquals(12, inputsB.baseUnits)
                            assertOriginalRequest(inputsB.request, i3.request)
                            delivery.release.complete(Unit)
                            delivery.delivered.await()
                            ui.audit.awaitRetirementsReturned(1)
                            assertNull(old.state.value.actionApproval)
                            assertEquals(inputsB.operationId, ui.controller.state.value.actionApproval?.operationId)
                            assertTrue(assertNotNull(ui.controller.state.value.actionApproval).lookupRequired)
                            assertEquals(i3.durable, fixture.host.find(inputsB.operationId))
                            assertEquals(completedI1, fixture.latest())
                            fixture.assertOriginal(i3.durable, inputsB)
                            heldB.reply.complete(LocalActionApprovalRetireResult.Unavailable)
                            ui.awaitIdle()
                            ui.controller.closeActionApproval(ui.token())
                            ui.review(inputsB.operationId)
                            assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
                                ui.controller.state.value.actionApproval?.approval?.phase)
                            assertEquals(i3.attempt.attemptId,
                                ui.controller.state.value.actionApproval?.approval?.retirementAttempt?.attemptId)
                            assertEquals(fixture.baselineRetirements + 2, fixture.endpoint.retirements.size)
                        } finally {
                            heldB.reply.complete(LocalActionApprovalRetireResult.Unavailable)
                            ui.awaitIdle()
                        }
                    }
                    fixture.assertOriginal(completedI1)
                    ui.assertNoBusinessOrNewCeremony()
                }
            }
        }
    }
}

private fun assertOriginalRequest(expected: MutationRequest, actual: MutationRequest) {
    assertEquals(expected.operationId, actual.operationId)
    assertEquals(expected.command, actual.command)
    assertContentEquals(expected.payload.bytes(), actual.payload.bytes())
    assertEquals(expected.actionApprovalId, actual.actionApprovalId)
    assertNull(actual.actionApprovalId)
}

/** Holds only UI delivery AFTER the real gateway durably processed its exact response. */
private class HeldArrivalRetirementDelivery {
    val entered = CompletableDeferred<LocalActionApprovalResult>()
    val release = CompletableDeferred<Unit>()
    val delivered = CompletableDeferred<Unit>()
    suspend fun deliver(result: LocalActionApprovalResult): LocalActionApprovalResult = withContext(NonCancellable) {
        check(entered.complete(result))
        release.await()
        delivered.complete(Unit)
        result
    }
}

private class ArrivalRetirementControllerAudit(private val actual: LocalWorkflowGateway) : LocalWorkflowGateway by actual {
    val retirements = mutableListOf<OperationId>()
    val approvalLookups = mutableListOf<OperationId>()
    val submissions = mutableListOf<OperationId>()
    private val otherCalls = mutableListOf<String>()
    private var nextDelivery: HeldArrivalRetirementDelivery? = null
    private val deliveries = mutableListOf<HeldArrivalRetirementDelivery>()
    private val returnedRetirements = MutableStateFlow(0)
    fun holdNextRetirementDelivery() = HeldArrivalRetirementDelivery().also {
        check(nextDelivery == null)
        nextDelivery = it
        deliveries += it
    }
    override suspend fun retireActionApproval(operationId: OperationId): LocalActionApprovalResult {
        retirements += operationId
        val held = nextDelivery.also { nextDelivery = null }
        try {
            val result = actual.retireActionApproval(operationId)
            return held?.deliver(result) ?: result
        } finally {
            returnedRetirements.value += 1
        }
    }
    suspend fun awaitRetirementsReturned(count: Int) { returnedRetirements.first { it >= count } }
    override suspend fun lookupActionApproval(operationId: OperationId): LocalActionApprovalResult {
        approvalLookups += operationId
        return actual.lookupActionApproval(operationId)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return actual.submit(operationId)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        otherCalls += "stage"
        return actual.stage(intent)
    }
    override suspend fun previewActionApproval(operationId: OperationId): LocalActionApprovalResult {
        otherCalls += "preview"
        return actual.previewActionApproval(operationId)
    }
    override suspend fun issueActionApproval(operationId: OperationId, confirmationDigest: com.ukkera.pos.security.PayloadDigest,
        approverLogin: String, approverPassword: CharArray): LocalActionApprovalResult {
        otherCalls += "issue"
        return actual.issueActionApproval(operationId, confirmationDigest, approverLogin, approverPassword)
    }
    override suspend fun beginActionApprovalReplacement(operationId: OperationId): LocalActionApprovalResult {
        otherCalls += "replacement"
        return actual.beginActionApprovalReplacement(operationId)
    }
    fun assertNoBusinessOrNewCeremony() { assertTrue(submissions.isEmpty()); assertTrue(otherCalls.isEmpty()) }
    fun releaseHeld() = deliveries.forEach { it.release.complete(Unit) }
}

/** Controlled UI lifecycle/display only; all retirement and recovery use the real gateway. */
private class ArrivalRetirementControllerSession(fixture: ArrivalRetirementNoAdmissionFixture,
    override val workflows: LocalWorkflowGateway) : BranchUiSession {
    override val display = SessionDisplayContext(fixture.inputs.requester, fixture.inputs.scope.companyId,
        fixture.inputs.scope.branchId, "Synthetic original requester", "Synthetic company", "Synthetic branch", setOf(AppArea.STOCK))
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(fixture.inputs.requester, 1))
    private var active = true
    override fun isCurrent() = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    override suspend fun mayRestore(route: AppRoute) = active && RouteRegistry.area(route) in display.allowedAreas
    override fun close() { active = false; lifecycle.value = LocalSessionLifecycle.LOCKED; attachmentUpdates.value = null }
}

private class ArrivalRetirementControllerFixture(val runtime: UkkeraRuntime, val session: ArrivalRetirementControllerSession,
    val audit: ArrivalRetirementControllerAudit, private val fixture: ArrivalRetirementNoAdmissionFixture,
    private val scope: TestScope) {
    var controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), scope)
        private set
    fun token(): NavigationToken = runtime.navigator.token()
    suspend fun awaitIdle() { controller.state.first { !it.working } }
    suspend fun awaitRetirementFinished(count: Int) {
        audit.awaitRetirementsReturned(count)
        // A current-session refusal may retire the UI attachment before its busy flag clears.
        combine(controller.state, session.lifecycle) { state, lifecycle ->
            !state.working || lifecycle != LocalSessionLifecycle.ACTIVE
        }.first { it }
    }
    suspend fun review(operationId: OperationId) {
        controller.reviewActionApproval(operationId, token())
        awaitIdle()
        assertEquals(operationId, controller.state.value.actionApproval?.operationId)
    }
    suspend fun recreate() {
        controller.close()
        fixture.reopen()
        controller = LocalWorkflowController(runtime, session, token(), scope)
        controller.activate(LocalWorkflow.RECEIVING)
        awaitIdle()
    }
    fun assertNoBusinessOrNewCeremony() { audit.assertNoBusinessOrNewCeremony(); fixture.assertNoBusinessOrIssuance() }
}

private suspend fun TestScope.withArrivalRetirementController(fixture: ArrivalRetirementNoAdmissionFixture,
    block: suspend (ArrivalRetirementControllerFixture) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audit = ArrivalRetirementControllerAudit(fixture.gateway)
    val session = ArrivalRetirementControllerSession(fixture, audit)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var ui: ArrivalRetirementControllerFixture? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-requester", charArrayOf('x'), runtime.navigator.token())
        ui = ArrivalRetirementControllerFixture(runtime, session, audit, fixture, this)
        ui.controller.activate(LocalWorkflow.RECEIVING)
        ui.awaitIdle()
        block(ui)
    } finally {
        audit.releaseHeld()
        try {
            audit.awaitRetirementsReturned(audit.retirements.size)
        } finally {
            try { ui?.controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
        }
    }
}

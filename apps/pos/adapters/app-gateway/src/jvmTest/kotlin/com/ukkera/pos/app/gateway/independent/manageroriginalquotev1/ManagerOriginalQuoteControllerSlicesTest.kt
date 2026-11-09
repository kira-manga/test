@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.manageroriginalquotev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.test.*

/** Actual shared runtime/controller with controlled session and delivery seams. */
class ManagerOriginalQuoteControllerSlicesTest {
    @Test
    fun a007ActualAttachmentRetirementAndNewUserCannotAdoptEvenTransientLatePrivateQuote() = runTest {
        val delivery = HeldQuoteDelivery()
        withQuoteController(delivery = delivery) { fixture ->
            assertTrue(delivery.ready.isCompleted)
            assertFrozenExclusiveDisplay(assertIs<LocalReadResult.Available>(delivery.result).page)
            assertNull(fixture.controller.state.value.page)
            var retired = false
            val privateAfterRetirement = mutableListOf<LocalWorkflowPage>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.state.collect { state -> if (retired) state.page?.let(privateAfterRetirement::add) }
            }
            var nextController: LocalWorkflowController? = null
            try {
                retired = true
                fixture.runtime.localSessionEnded(fixture.session)
                runCurrent()
                val next = QuoteControllerSession(UnavailableLocalWorkflows, null, OriginalQuoteFrozen.nextActor,
                    OriginalQuoteFrozen.nextSession, setOf(AppArea.CASHIER))
                fixture.signIn(next)
                nextController = LocalWorkflowController(fixture.runtime, next, fixture.runtime.navigator.token(), this)
                assertEquals(OriginalQuoteFrozen.nextActor, fixture.runtime.presentation.value.display?.actorId)
                assertNull(nextController.state.value.page)
                delivery.release.complete(Unit)
                runCurrent()
                assertTrue(delivery.delivered, "Held boundary must actually deliver the obsolete Available reply")
                assertTrue(privateAfterRetirement.isEmpty(), "No private target/creator/pricing emission after retirement")
                assertNull(fixture.controller.state.value.page)
                assertNull(nextController.state.value.page)
                fixture.control.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
            } finally { observer.cancel(); nextController?.close() }
        }
        // Native retirement, pending-payment preservation and out-of-order F-RAT remain unbound.
    }

    @Test
    fun a005ActualReadonlyCardsCannotSwitchOwnerRouteAndClearKeepsSearchAndOriginalOperationCoordinate() = runTest {
        val original = OperationId(OriginalQuoteFrozen.ids.text("original_unknown_operation"))
        withQuoteController(original = original,
            selected = LocalRecordReference(LocalRecordKind.ORDER, OriginalQuoteFrozen.exclusive.id("target_order_id"))) { fixture ->
            val before = fixture.controller.state.value
            val page = assertNotNull(before.page)
            assertFrozenExclusiveDisplay(page)
            for (record in page.records) {
                fixture.controller.selectRecord(record.reference, fixture.runtime.navigator.token())
                runCurrent()
                assertEquals(before.search, fixture.controller.state.value.search)
                assertEquals(before.reference, fixture.controller.state.value.reference)
                assertEquals(page, fixture.controller.state.value.page)
            }
            fixture.controller.selectRecord(null, fixture.runtime.navigator.token())
            fixture.controller.refreshPending(fixture.runtime.navigator.token())
            runCurrent()
            val after = fixture.controller.state.value
            assertNull(after.reference)
            assertEquals("manager:" + OriginalQuoteFrozen.exclusive.text("target_order_id"), after.search)
            assertEquals(original, after.operationId)
            assertEquals(before.operation, after.operation)
            assertFalse(after.recordedAcknowledged)
            assertTrue(after.managerOrderReview)
            assertFalse(after.canStart)
            assertNull(after.form)
            assertFrozenExclusiveDisplay(assertNotNull(after.page))
            assertTrue(fixture.control.endpoint.keys.all { it == OriginalQuoteFrozen.quoteKey })
            fixture.control.assertReadOnly()
        }
        // This binds the UI's original-operation coordinate; actual actor/digest/4000/persistence remains B03.
    }
}

private class HeldQuoteDelivery {
    val ready = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var result: LocalReadResult? = null
    var delivered = false
}
private class HeldQuoteGateway(private val actual: LocalWorkflowGateway, private val held: HeldQuoteDelivery) : LocalWorkflowGateway by actual {
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        val result = actual.read(query)
        held.result = result
        held.ready.complete(Unit)
        withContext(NonCancellable) { held.release.await(); held.delivered = true }
        return result
    }
}

private class QuoteControllerSession(
    override val workflows: LocalWorkflowGateway,
    private val control: OriginalQuoteGatewayControl?,
    actor: DomainId = OriginalQuoteFrozen.manager,
    owner: DomainId = OriginalQuoteFrozen.session,
    areas: Set<AppArea> = setOf(AppArea.ORDERS),
) : BranchUiSession {
    override val display = SessionDisplayContext(actor, OriginalQuoteFrozen.scope.companyId, OriginalQuoteFrozen.scope.branchId,
        "Synthetic local employee", "Synthetic company", "Synthetic branch", areas)
    private var active = true
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(owner, 1))
    private val lifetimes = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifetimes
    override fun isCurrent(): Boolean = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    override suspend fun mayRestore(route: AppRoute): Boolean = active && RouteRegistry.area(route) in display.allowedAreas
    override fun close() {
        active = false
        control?.leaseCurrent = false
        attachments.value = null
        lifetimes.value = LocalSessionLifecycle.LOCKED
    }
}
private class QuoteControllerSignIn(var session: BranchUiSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
}
private class QuoteControllerFixture(val runtime: UkkeraRuntime, val controller: LocalWorkflowController,
    val session: QuoteControllerSession, val control: OriginalQuoteGatewayControl, private val signInPort: QuoteControllerSignIn) {
    suspend fun signIn(next: BranchUiSession) {
        signInPort.session = next
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn(next.display.actorName, charArrayOf('x'), runtime.navigator.token())
        assertEquals(next.display, runtime.presentation.value.display)
    }
}
private suspend fun TestScope.withQuoteController(
    delivery: HeldQuoteDelivery? = null,
    original: OperationId? = null,
    selected: LocalRecordReference? = null,
    block: suspend TestScope.(QuoteControllerFixture) -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var runtime: UkkeraRuntime? = null
    var controller: LocalWorkflowController? = null
    try {
        val control = OriginalQuoteGatewayControl()
        val gateway = delivery?.let { HeldQuoteGateway(control.gateway, it) } ?: control.gateway
        val session = QuoteControllerSession(gateway, control)
        val signInPort = QuoteControllerSignIn(session)
        val actualRuntime = UkkeraRuntime(AppDependencies(branchGateway = signInPort, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
        runtime = actualRuntime
        actualRuntime.openSignIn(false, actualRuntime.navigator.token())
        actualRuntime.signIn(session.display.actorName, charArrayOf('x'), actualRuntime.navigator.token())
        val workspace = LocalWorkspaceState(workflow = LocalWorkflow.ORDERS, search = OriginalQuoteFrozen.workflow().search,
            reference = selected, operationId = original, operation = original?.let { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) })
        val actualController = LocalWorkflowController(actualRuntime, session, actualRuntime.navigator.token(), this,
            LocalWorkflowContinuation(workspace, null, null, null, null))
        controller = actualController
        actualController.activate(LocalWorkflow.ORDERS)
        runCurrent()
        block(QuoteControllerFixture(actualRuntime, actualController, session, control, signInPort))
    } finally {
        delivery?.release?.complete(Unit)
        try { controller?.close(); runtime?.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

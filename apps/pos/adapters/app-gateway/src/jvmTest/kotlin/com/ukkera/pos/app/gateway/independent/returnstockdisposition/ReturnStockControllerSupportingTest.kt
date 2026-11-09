@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.returnstockdisposition

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Supplemental common-controller paths from frozen RSD-v1. Sessions, public reads,
 * replies and journal acknowledgements are controlled. Native screens, devices,
 * payment instructions, actual stock/value/cash, authority, LAN and restart are unbound. */
class ReturnStockControllerSupportingTest {
    private val input = ReturnStockFrozenInputs

    @Test
    fun rsd001_rsd010_rsd013_rsd014_explicitSaveOriginalRecoveryAndAckNeverActivateRefundControls() = runTest {
        withStockController { slice ->
            val c = slice.controller
            assertTrue(c.state.value.canStart)
            assertEquals(input.stockReference, c.state.value.reference)
            c.openForm(LocalAction.RETAIL_REFUND_START, slice.token())
            assertNull(c.state.value.form)
            c.retailPhysical.loadPending(slice.token())
            c.retailPhysical.prepareRefund(input.returnId, input.id(8160), slice.token())
            c.openRetailCollection(slice.token())
            runCurrent()
            assertNoPhysicalAction(slice)
            c.openForm(LocalAction.RETURN_STOCK_POST, slice.token())
            val form = assertNotNull(c.state.value.form)
            assertEquals("0", form.lines.single().values.getValue(LocalField.QUANTITY).text)
            assertNull(form.lines.single().values[LocalField.CUSTODY]?.selected)
            c.stage(slice.token())
            runCurrent()
            assertEquals(LocalField.CONFIRMED, c.state.value.issue?.field)
            assertTrue(slice.port.staged.isEmpty())
            c.edit(LocalField.QUANTITY, LocalDraftValue("1"), slice.token(), input.allocation)
            c.edit(LocalField.CUSTODY, LocalDraftValue(selected = LocalScalarValue.Choice("RELEASED")), slice.token(), input.allocation)
            c.edit(LocalField.OCCURRED_AT, LocalDraftValue(input.occurredAt.canonicalString()), slice.token())
            c.confirmForm(true, slice.token())
            c.stage(slice.token())
            runCurrent()
            val intent = slice.port.staged.single()
            val operation = intent.operationId
            val original = assertNotNull(slice.control.journal.entry)
            val body = original.request().payload
            val request = LocalReturnStockCodecV1.decode(body)
            assertEquals(listOf(LocalReturnStockSelectionV1(input.allocation, input.core(1), LocalReturnStockCustodyV1.RELEASED)), request.selections)
            assertEquals(input.core(2), request.acceptedQuantity)
            assertEquals(input.owner, original.owner.actorId)
            assertEquals(operation, c.state.value.operationId)
            assertIs<LocalOperationResult.Pending>(c.state.value.operation)
            assertTrue(slice.control.endpoint.executed.isEmpty())
            c.submit(input.otherOperation, slice.token())
            c.lookup(input.otherOperation, slice.token())
            c.edit(LocalField.QUANTITY, LocalDraftValue("2"), slice.token(), input.allocation)
            runCurrent()
            assertEquals(body, assertNotNull(slice.control.journal.entry).request().payload)
            assertTrue(slice.control.endpoint.executed.isEmpty())
            c.submit(operation, slice.token()) // Controlled Deferred delivery, not native commit failure.
            runCurrent()
            assertEquals(operation, assertIs<LocalOperationResult.Unresolved>(c.state.value.operation).operationId)
            c.submit(operation, slice.token()) // Unknown cannot be silently resubmitted before original lookup.
            runCurrent()
            assertEquals(1, slice.control.endpoint.executed.size)
            val reply = slice.control.endpoint.recorded(LocalReturnStockResultV1.Applied(input.returnId,
                request.restorationId, Revision(1), input.core(1), input.core(1), LocalReturnStockStatusV1.PARTIALLY_POSTED), operation)
            slice.control.endpoint.onLookup = { LookupReply.Found(reply) }
            c.lookup(operation, slice.token())
            runCurrent()
            val recorded = assertIs<LocalOperationResult.Recorded>(c.state.value.operation)
            assertEquals(operation, recorded.returnStock?.operationId)
            assertEquals(input.stockReference, recorded.result?.reference)
            assertNull(recorded.food)
            assertFalse(c.state.value.recordedAcknowledged)
            c.acknowledgeRecorded(input.otherOperation, slice.token())
            runCurrent()
            assertEquals(0, slice.control.journal.acknowledgeCalls)
            slice.control.endpoint.source = input.partialOriginal().copy(lastPostingOperationId = operation)
            c.acknowledgeRecorded(operation, slice.token())
            runCurrent()
            assertTrue(c.state.value.recordedAcknowledged)
            assertEquals(input.stockReference, c.state.value.reference)
            assertNotNull(c.state.value.page)
            assertEquals(1, slice.control.journal.acknowledgeCalls)
            assertEquals(body, assertNotNull(slice.control.journal.entry).request().payload)
            assertEquals(original.identity(), assertNotNull(slice.control.journal.entry).identity())
            assertEquals(listOf(operation), slice.control.endpoint.executed.map { it.operationId })
            assertTrue(slice.control.endpoint.selectors.all { it.caseId == input.caseId && it.returnId == input.returnId })
            c.retailPhysical.loadPending(slice.token())
            c.retailPhysical.prepareRefund(input.returnId, input.id(8160), slice.token())
            c.openRetailCollection(slice.token())
            runCurrent()
            assertNoPhysicalAction(slice)
        }
    }

    @Test
    fun rsd010_rsd013_changedSavedSourceMetadataCannotReleaseFreshFrozenInputOrRedirectAck() = runTest {
        withStockController { slice ->
            val c = slice.controller
            c.openForm(LocalAction.RETURN_STOCK_POST, slice.token())
            c.edit(LocalField.QUANTITY, LocalDraftValue("1"), slice.token(), input.allocation)
            c.edit(LocalField.CUSTODY, LocalDraftValue(selected = LocalScalarValue.Choice("RELEASED")), slice.token(), input.allocation)
            c.edit(LocalField.OCCURRED_AT, LocalDraftValue(input.occurredAt.canonicalString()), slice.token())
            c.confirmForm(true, slice.token())
            c.stage(slice.token())
            runCurrent()
            val original = assertNotNull(slice.control.journal.entry)
            val request = LocalReturnStockCodecV1.decode(original.request().payload)
            val reply = slice.control.endpoint.recorded(LocalReturnStockResultV1.Applied(input.returnId,
                request.restorationId, Revision(1), input.core(1), input.core(1), LocalReturnStockStatusV1.PARTIALLY_POSTED), original.operationId)
            slice.control.endpoint.onExecute = { reply }
            slice.control.endpoint.onLookup = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
            // Mutate UI delivery after a valid gateway observation. This tests only the
            // controller's frozen-input binding, not malformed Master-result retention.
            slice.port.recordedDelivery = { recorded -> recorded.copy(returnStock = assertNotNull(recorded.returnStock).copy(saleId = input.id(8161))) }
            c.submit(original.operationId, slice.token())
            runCurrent()
            assertIs<LocalOperationResult.Unresolved>(c.state.value.operation)
            assertTrue(c.state.value.formFrozen)
            assertEquals(input.stockReference, c.state.value.reference)
            assertEquals(original.request().payload, assertNotNull(slice.control.journal.entry).request().payload)
            c.acknowledgeRecorded(original.operationId, slice.token())
            runCurrent()
            assertEquals(0, slice.control.journal.acknowledgeCalls)
            slice.port.recordedDelivery = { it }
            slice.control.endpoint.onLookup = { LookupReply.Found(reply) }
            c.lookup(original.operationId, slice.token())
            runCurrent()
            val recovered = assertIs<LocalOperationResult.Recorded>(c.state.value.operation)
            assertEquals(input.sale, recovered.returnStock?.saleId)
            assertEquals(input.stockReference, recovered.result?.reference)
            assertEquals(1, slice.control.endpoint.executed.size)
            assertNoPhysicalAction(slice)
        }
    }

    private fun assertNoPhysicalAction(slice: ReturnStockControllerSlice) {
        assertTrue(slice.physical.calls.isEmpty())
        assertNull(slice.controller.retailPhysical.snapshotDraft())
        assertNull(slice.controller.retailPhysical.state.value.instruction)
        assertNull(slice.controller.retailPhysical.state.value.preview)
        assertNull(slice.controller.retailPhysical.state.value.operationId)
    }
}

private class ReturnStockUiPort(private val actual: LocalWorkflowGateway) : LocalWorkflowGateway by actual {
    val staged = mutableListOf<LocalCommandIntent>()
    var recordedDelivery: (LocalOperationResult.Recorded) -> LocalOperationResult.Recorded = { it }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        staged += intent
        return actual.stage(intent)
    }
    override suspend fun submit(operationId: OperationId) = delivered(actual.submit(operationId))
    override suspend fun lookup(operationId: OperationId) = delivered(actual.lookup(operationId))
    private fun delivered(result: LocalOperationResult): LocalOperationResult =
        if (result is LocalOperationResult.Recorded) recordedDelivery(result) else result
}

/** No payment service is bound. Calls are observations only, never payment success. */
private class ReturnStockNoPaymentPort : LocalRetailPhysicalGateway by UnavailableLocalRetailPhysical {
    val calls = mutableListOf<String>()
    override suspend fun prepare(request: LocalRetailPhysicalRequest): LocalRetailPhysicalReply {
        calls += "prepare"
        return LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun pending(afterFact: DomainId?): LocalRetailPhysicalReply {
        calls += "pending"
        return LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun pendingRefunds(afterFact: DomainId?): LocalRetailPhysicalReply {
        calls += "pending-refunds"
        return LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun acceptedRefundDues(caseId: DomainId, returnId: DomainId): LocalRetailPhysicalReply {
        calls += "accepted-refund-dues"
        return LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    }
}

private class ReturnStockControllerSession(override val workflows: LocalWorkflowGateway,
    override val retailPhysical: LocalRetailPhysicalGateway, private val control: ReturnStockGatewayFixture) : BranchUiSession {
    private val input = ReturnStockFrozenInputs
    override val display = SessionDisplayContext(input.owner, input.scope.companyId, input.scope.branchId,
        "Synthetic stock operator", "Synthetic company", "Synthetic branch", setOf(AppArea.ORDERS))
    private var active = true
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(input.session, 1))
    private val lifetimes = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifetimes
    override fun isCurrent() = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    override suspend fun mayRestore(route: AppRoute) = active && RouteRegistry.area(route) in display.allowedAreas
    override fun close() {
        active = false
        control.leaseCurrent = false
        attachments.value = null
        lifetimes.value = LocalSessionLifecycle.LOCKED
    }
}

private class ReturnStockControllerSignIn(private val session: BranchUiSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
}

private class ReturnStockControllerSlice(val runtime: UkkeraRuntime, val controller: LocalWorkflowController,
    val control: ReturnStockGatewayFixture, val port: ReturnStockUiPort, val physical: ReturnStockNoPaymentPort) {
    fun token(): NavigationToken = runtime.navigator.token()
}

private suspend fun TestScope.withStockController(block: suspend TestScope.(ReturnStockControllerSlice) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var runtime: UkkeraRuntime? = null
    var controller: LocalWorkflowController? = null
    try {
        val control = ReturnStockGatewayFixture()
        val port = ReturnStockUiPort(control.gateway)
        val physical = ReturnStockNoPaymentPort()
        val session = ReturnStockControllerSession(port, physical, control)
        val actualRuntime = UkkeraRuntime(AppDependencies(branchGateway = ReturnStockControllerSignIn(session),
            platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
        runtime = actualRuntime
        actualRuntime.openSignIn(false, actualRuntime.navigator.token())
        actualRuntime.signIn(session.display.actorName, charArrayOf('x'), actualRuntime.navigator.token())
        assertEquals(session.display, actualRuntime.presentation.value.display)
        val workspace = LocalWorkspaceState(workflow = LocalWorkflow.RETURNS, reference = ReturnStockFrozenInputs.stockReference)
        val actualController = LocalWorkflowController(actualRuntime, session, actualRuntime.navigator.token(), this,
            LocalWorkflowContinuation(workspace, null, null, null, null))
        controller = actualController
        actualController.activate(LocalWorkflow.RETURNS)
        runCurrent()
        block(ReturnStockControllerSlice(actualRuntime, actualController, control, port, physical))
    } finally {
        try { controller?.close(); runtime?.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

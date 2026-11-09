package com.ukkera.pos.app.independent.retailcustomerreceiptv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** RCR-040's UI-controller slice. Replies are controlled; no native result/ACK persistence is claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetailCustomerReceiptFrozenControllerAckTest {
    @Test
    fun rcr040ExplicitAckFollowsOriginalEventAndDuplicateAckNeverResubmits() = runTest {
        for (workflow in listOf(LocalWorkflow.ORDERS, LocalWorkflow.CHECKOUT)) withController(workflow) { ui, controller ->
            val token = ui.runtime.navigator.token()
            controller.acknowledgeRecorded(originalOperation, token)
            runCurrent()
            val state = controller.state.value
            assertTrue(state.recordedAcknowledged)
            assertEquals(originalEvent, state.reference)
            assertEquals(originalEvent, ui.port.reads.last().reference)
            assertRecordedOriginal(state)
            // A later Head is not a new ACK target: retain the immutable original Event.
            ui.port.latestEvent = laterEvent
            controller.acknowledgeRecorded(originalOperation, ui.runtime.navigator.token())
            runCurrent()
            assertEquals(originalEvent, controller.state.value.reference)
            assertRecordedOriginal(controller.state.value)
            ui.port.assertNoSubmitOrStage()
        }
    }

    @Test
    fun rcr040FailedLocalAckKeepsOriginalRecordedOutcomeWithoutEventNavigation() = runTest {
        withController(LocalWorkflow.ORDERS) { ui, controller ->
            val originalReadCount = ui.port.reads.size
            ui.port.acknowledgement = LocalAcknowledgementResult.Unresolved(originalOperation, LocalServiceProblem.STORAGE_UNCERTAIN)
            controller.acknowledgeRecorded(originalOperation, ui.runtime.navigator.token())
            runCurrent()
            assertFalse(controller.state.value.recordedAcknowledged)
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, controller.state.value.acknowledgementProblem)
            assertEquals(originalHead, controller.state.value.reference)
            assertEquals(originalReadCount, ui.port.reads.size)
            assertRecordedOriginal(controller.state.value)
            ui.port.assertNoSubmitOrStage()
        }
    }

    @Test
    fun rcr040MismatchedAckIdentityCannotBecomeOriginalEventNavigation() = runTest {
        withController(LocalWorkflow.CHECKOUT) { ui, controller ->
            ui.port.acknowledgement = LocalAcknowledgementResult.Acknowledged(OperationId(laterEvent.id.value))
            controller.acknowledgeRecorded(originalOperation, ui.runtime.navigator.token())
            runCurrent()
            assertFalse(controller.state.value.recordedAcknowledged)
            assertEquals(LocalServiceProblem.UNAVAILABLE, controller.state.value.acknowledgementProblem)
            assertEquals(originalHead, controller.state.value.reference)
            assertRecordedOriginal(controller.state.value)
            ui.port.assertNoSubmitOrStage()
        }
    }

    @Test
    fun rcr040LateAckAfterEntryLockCannotAdoptOrReadOriginalEvent() = runTest {
        withController(LocalWorkflow.ORDERS) { ui, controller ->
            val reply = CompletableDeferred<LocalAcknowledgementResult>()
            ui.port.heldAck = reply
            controller.acknowledgeRecorded(originalOperation, ui.runtime.navigator.token())
            runCurrent()
            val readsBeforeLock = ui.port.reads.size
            assertEquals(LocalWorkBusy.ACKNOWLEDGING, controller.state.value.busy)
            ui.runtime.lock(ui.runtime.navigator.token())
            reply.complete(LocalAcknowledgementResult.Acknowledged(originalOperation))
            runCurrent()
            assertTrue(ui.port.ackReturned, "The delayed reply must actually return, not disappear through cancellation")
            assertFalse(controller.state.value.recordedAcknowledged)
            assertEquals(readsBeforeLock, ui.port.reads.size)
            assertFalse(ui.session.isCurrent())
            ui.port.assertNoSubmitOrStage()
        }
    }

    private suspend fun TestScope.withController(workflow: LocalWorkflow,
        body: suspend TestScope.(ControlledReceiptUi, LocalWorkflowController) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ui = ControlledReceiptUi()
        var controller: LocalWorkflowController? = null
        try {
            ui.runtime.openSignIn(false, ui.runtime.navigator.token())
            ui.runtime.signIn("synthetic-receipt-ui", charArrayOf('s'), ui.runtime.navigator.token())
            controller = LocalWorkflowController(ui.runtime, ui.session, ui.runtime.navigator.token(), backgroundScope,
                LocalWorkflowContinuation(LocalWorkspaceState(workflow = workflow, reference = originalHead,
                    operationId = originalOperation, operation = originalRecorded), null,
                    LocalAction.RETAIL_CUSTOMER_RECEIPT, null, null))
            controller.activate(workflow)
            runCurrent()
            assertRecordedOriginal(controller.state.value)
            body(ui, controller)
        } finally {
            controller?.close()
            ui.runtime.close()
            Dispatchers.resetMain()
        }
    }

    private fun assertRecordedOriginal(state: LocalWorkspaceState) {
        assertEquals(originalOperation, state.operationId)
        val result = assertIs<LocalOperationResult.Recorded>(state.operation)
        assertEquals(originalOperation, result.operationId)
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, result.decision)
        assertEquals(originalEvent, result.result?.reference)
        assertEquals(originalRecorded.result?.values, result.result?.values)
    }
}

// Literal semantic F-UI identities/time/Cairo. These are not genuine authority or paid-sale sources.
private val originalOperation = OperationId("18000000-0000-4000-8000-000000000001")
private val saleId = DomainId("06000000-0000-4000-8000-000000000001")
private val originalHead = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT, saleId)
private val originalEvent = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT,
    DomainId(originalOperation.value), parentId = saleId)
private val laterEvent = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT,
    DomainId("10000000-0000-4000-8000-000000000003"), parentId = saleId)
private val originalRecorded = LocalOperationResult.Recorded(originalOperation, LocalRecordedDecision.RECORDED_ONLY,
    LocalText("Recorded evidence", "دليل مسجل"), LocalRecord(originalEvent, LocalText("Original event", "الحدث الأصلي"),
        values = listOf(LocalRecordValue(LocalText("Observed UTC", "وقت الاستلام"), LocalText("2026-01-06T10:15:00Z", "2026-01-06T10:15:00Z")),
            LocalRecordValue(LocalText("Zone", "المنطقة الزمنية"), LocalText("Africa/Cairo", "Africa/Cairo")))))

private class ControlledReceiptUi {
    val port = ControlledReceiptWorkflow()
    val session = object : BranchUiSession {
        private var live = true
        override val display = SessionDisplayContext(DomainId("04000000-0000-4000-8000-000000000001"),
            DomainId("02000000-0000-4000-8000-000000000001"), DomainId("03000000-0000-4000-8000-000000000001"),
            "Synthetic employee", "Synthetic company", "Synthetic branch", setOf(AppArea.CASHIER, AppArea.ORDERS))
        override val workflows: LocalWorkflowGateway get() = port
        override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
        override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(
            DomainId("19000000-0000-4000-8000-000000000001"), 1))
        override fun isCurrent() = live
        override suspend fun current(): LocalSessionCheckResult {
            val attachment = attachmentUpdates.value
            return if (live && attachment != null) LocalSessionCheckResult.Current(display, attachment)
                else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
        }
        override suspend fun mayRestore(route: AppRoute) = false
        override fun close() { live = false; attachmentUpdates.value = null }
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
}

private class ControlledReceiptWorkflow : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    var latestEvent = originalEvent
    var acknowledgement: LocalAcknowledgementResult = LocalAcknowledgementResult.Acknowledged(originalOperation)
    var heldAck: CompletableDeferred<LocalAcknowledgementResult>? = null
    val ackRequests = mutableListOf<OperationId>()
    var ackReturned = false
        private set
    private var acknowledged = false
    private var stages = 0
    private var submits = 0
    override suspend fun pending(cursor: String?) = LocalPendingResult.Available(if (acknowledged) emptyList() else
        listOf(LocalPendingOperation(originalOperation, LocalAction.RETAIL_CUSTOMER_RECEIPT,
            LocalText("Original recorded receipt", "إيصال مسجل أصلي"), phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED,
            recordedDecision = LocalRecordedDecision.RECORDED_ONLY)))
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query
        return LocalReadResult.Available(LocalWorkflowPage(query.workflow,
            listOf(if (query.reference == originalEvent) requireNotNull(originalRecorded.result)
                else LocalRecord(originalHead, LocalText("Current head", "الإجمالي الحالي"),
                    values = listOf(LocalRecordValue(LocalText("Latest event", "آخر حدث"), LocalText(latestEvent.id.value, latestEvent.id.value))))), emptyList()))
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages++
        throw AssertionError("ACK must not stage a receipt")
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submits++
        throw AssertionError("ACK must not resubmit a receipt")
    }
    override suspend fun lookup(operationId: OperationId) = originalRecorded
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        ackRequests += operationId
        val result = heldAck?.let { withContext(NonCancellable) { it.await() } } ?: acknowledgement
        ackReturned = true
        if (result is LocalAcknowledgementResult.Acknowledged && result.operationId == originalOperation) acknowledged = true
        return result
    }
    fun assertNoSubmitOrStage() {
        assertEquals(0, stages); assertEquals(0, submits)
        assertTrue(ackRequests.isNotEmpty())
        assertTrue(ackRequests.all { it == originalOperation }, "Every public ACK request must retain original identity")
    }
}

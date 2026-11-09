@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.managerreassignmentv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalCollectionStockCodecV2
import com.ukkera.pos.sales.store.LocalCollectionStockRequestV2
import com.ukkera.pos.sales.store.LocalManagerOrderReviewQueryV1
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual runtime-owned attachment/controller with controlled sessions and gateway delivery. */
class ManagerReassignmentControllerSlicesTest {
    private val f = FrozenManagerReassignmentV1

    @Test
    fun mra003And004And028FixedTargetRawArabicAndOriginalGeneratedIdsSurviveSeparateSubmit() = runTest {
        withReassignmentController { slice ->
            val c = slice.controller
            val page = assertNotNull(c.state.value.page)
            assertTrue(c.state.value.canStart)
            assertFalse(page.records.single().selectable)
            for (action in listOf(LocalAction.ORDER_OFFER_HANDOFF, LocalAction.ORDER_ACCEPT_HANDOFF,
                LocalAction.ORDER_REPLACE, LocalAction.RETAIL_COLLECTION_BEGIN, LocalAction.RETAIL_REFUND_START)) {
                c.openForm(action, slice.token())
                assertNull(c.state.value.form)
            }
            c.selectRecord(page.records.single().reference, slice.token())
            c.openRetailCollection(slice.token())
            assertNull(c.state.value.reference)
            c.openForm(LocalAction.ORDER_REASSIGN, slice.token())
            val opened = assertNotNull(c.state.value.form)
            val fixedFields = opened.descriptor.fields.filterIsInstance<LocalFormField.Scalar>().filter { it.readOnly }
            for (field in fixedFields) {
                c.edit(field.field, LocalDraftValue(if (field.kind == LocalInputKind.INTEGER) "1" else f.id("ORDER-A-OTHER").value), slice.token())
                assertEquals(opened.values.getValue(field.field), c.state.value.form?.values?.get(field.field))
            }
            val evidenceId = DomainId(opened.values.getValue(LocalField.EVIDENCE_ID).text)
            val reason = "غياب_الموظف١"
            c.edit(LocalField.RECIPIENT_ID, LocalDraftValue(f.recipient.value), slice.token())
            c.edit(LocalField.REASON, LocalDraftValue(reason), slice.token())
            c.edit(LocalField.OCCURRED_AT, LocalDraftValue(f.attestedAt.canonicalString()), slice.token())
            c.stage(slice.token())
            runCurrent()
            assertEquals(LocalField.CONFIRMED, c.state.value.issue?.field)
            assertNull(c.state.value.operationId)
            assertTrue(slice.port.staged.isEmpty())
            c.confirmForm(true, slice.token())
            c.stage(slice.token())
            runCurrent()
            val intent = slice.port.staged.single()
            val originalId = intent.operationId
            assertEquals(originalId, c.state.value.operationId)
            assertIs<LocalOperationResult.Pending>(c.state.value.operation)
            val saved = slice.control.journal.entries.getValue(originalId)
            val originalBody = saved.request().payload
            assertEquals(f.request(reason).copy(evidence = LocalCashEvidenceV1(evidenceId, f.attestedAt)),
                LocalCollectionStockCodecV2.decode(originalBody))
            assertEquals(f.manager, saved.owner.actorId)
            assertEquals(f.device, saved.owner.deviceId)
            assertEquals(1, slice.control.journal.stageWrites)
            slice.control.assertNoDispatch()
            c.refresh(slice.token())
            c.openRetailCollection(slice.token())
            c.edit(LocalField.REASON, LocalDraftValue("A"), slice.token())
            runCurrent()
            assertEquals(originalId, c.state.value.operationId)
            assertEquals(originalBody, slice.control.journal.entries.getValue(originalId).request().payload)
            slice.control.assertNoDispatch()
            c.submit(originalId, slice.token())
            runCurrent()
            assertEquals(originalId, assertIs<LocalOperationResult.Unresolved>(c.state.value.operation).operationId)
            assertEquals(originalBody, slice.control.endpoint.executions.single().payload)
            assertEquals(originalId, slice.control.endpoint.executions.single().operationId)
            c.submit(originalId, slice.token())
            c.edit(LocalField.REASON, LocalDraftValue("A"), slice.token())
            runCurrent()
            assertEquals(1, slice.control.endpoint.executions.size)
            assertEquals(originalBody, slice.control.journal.entries.getValue(originalId).request().payload)
            assertEquals(reason, assertIs<LocalCollectionStockRequestV2.ReassignOrder>(
                LocalCollectionStockCodecV2.decode(originalBody)).reasonCode)
            // Evidence/operation IDs bind actual UI-created values at creation; neither is native proof.
        }
        // Native UI, RTL layout/touch/resize, durable Master state and actual recipient app remain unbound.
    }

    @Test
    fun mra001And005And028MalformedCandidateOrFormCannotActivateAReassignmentEditor() = runTest {
        val corruptions: List<Pair<String, (LocalWorkflowPage) -> LocalWorkflowPage>> = listOf(
            "selectable" to { page -> page.copy(records = page.records.map { it.copy(selectable = true) }) },
            "wrong kind" to { page -> page.copy(records = page.records.map { it.copy(reference = it.reference.copy(kind = LocalRecordKind.COLLECTION)) }) },
            "wrong target" to { page -> page.copy(records = page.records.map { it.copy(reference = it.reference.copy(id = f.id("ORDER-A-OTHER"))) }) },
            "second order" to { page -> page.copy(records = page.records + page.records.single().copy(
                reference = LocalRecordReference(LocalRecordKind.ORDER, f.id("ORDER-A-OTHER"), f.r17))) },
            "cursor" to { page -> page.copy(nextCursor = "controlled-next") },
            "old form version" to { page -> page.copy(forms = page.forms.map { it.copy(version = 1) }) },
            "legacy action" to { page -> page.copy(forms = page.forms.map { it.copy(action = LocalAction.ORDER_ACCEPT_HANDOFF) }) },
            "editable target" to { page -> page.changedScalar(LocalField.ORDER_ID) { it.copy(readOnly = false) } },
            "optional prior owner" to { page -> page.changedScalar(LocalField.PREVIOUS_OWNER_ID) { it.copy(required = false) } },
            "wrong fixed target" to { page -> page.changedScalar(LocalField.ORDER_ID) { it.copy(initialValue = LocalScalarValue.Identifier(f.id("ORDER-A-OTHER"))) } },
            "wrong prior owner type" to { page -> page.changedScalar(LocalField.PREVIOUS_OWNER_ID) { it.copy(initialValue = LocalScalarValue.Text(f.previousOwner.value)) } },
            "stale form revision" to { page -> page.changedScalar(LocalField.EXPECTED_REVISION) { it.copy(initialValue = LocalScalarValue.Integer(f.r16.value)) } },
            "form without summary" to { page -> page.copy(records = emptyList()) },
        )
        for ((name, corruption) in corruptions) withReassignmentController(transform = corruption) { slice ->
            assertNull(slice.controller.state.value.page, name)
            assertEquals(LocalServiceProblem.UNAVAILABLE, slice.controller.state.value.readProblem, name)
            slice.controller.openForm(LocalAction.ORDER_REASSIGN, slice.token())
            slice.controller.stage(slice.token())
            slice.controller.openRetailCollection(slice.token())
            runCurrent()
            assertNull(slice.controller.state.value.form, name)
            assertNull(slice.controller.state.value.operationId, name)
            assertTrue(slice.port.staged.isEmpty(), name)
            assertEquals(0, slice.control.journal.stageWrites, name)
            slice.control.assertNoDispatch()
        }
        // Controlled late-page/form binding only; native target authorization remains required.
    }

    @Test
    fun mra001And028SelectorChangeRetiresStaleReadCapabilitiesButKeepsOriginalResultCoordinate() = runTest {
        withReassignmentController(originalMarker = f.operation()) { slice ->
            val c = slice.controller
            val original = assertNotNull(c.state.value.operation)
            val page = assertNotNull(c.state.value.page)
            val reference = c.state.value.reference
            c.selectRecord(page.records.single().reference, slice.token())
            c.openRetailCollection(slice.token())
            assertEquals(reference, c.state.value.reference)
            c.changeSearch("manager:${f.target.value}", slice.token())
            assertNull(c.state.value.page)
            assertNull(c.state.value.reference)
            assertNull(c.state.value.priceDraft)
            assertNull(c.state.value.pricePreview)
            assertEquals(f.operation(), c.state.value.operationId)
            assertEquals(original, c.state.value.operation)
            c.openForm(LocalAction.ORDER_REASSIGN, slice.token())
            c.stage(slice.token())
            assertTrue(slice.port.staged.isEmpty())
            c.refresh(slice.token())
            runCurrent()
            val manager = assertNotNull(c.state.value.page)
            assertTrue(manager.forms.isEmpty() && manager.records.none { it.selectable })
            c.changeSearch("reassign:${f.target.value}", slice.token())
            assertNull(c.state.value.page, "A manager quote cannot supply a stale reassignment form")
            assertEquals(f.operation(), c.state.value.operationId)
            c.refresh(slice.token())
            runCurrent()
            assertEquals(listOf(LocalAction.ORDER_REASSIGN), assertNotNull(c.state.value.page).forms.map { it.action })
            // This step tests a mismatched reply, not an earlier permission refusal.
            // Both selected targets have separate exact hints; the controlled reply
            // still names ORDER-MAIN, so it cannot serve the new authorized selector.
            val otherTarget = f.id("ORDER-A-OTHER")
            slice.control.endpoint.currentReply = LocalSessionViewResult.Current(
                f.sessionView(listOf(f.hint(), f.hint(target = otherTarget))))
            val readsBeforeOtherTarget = slice.control.endpoint.reads.size
            c.changeSearch("reassign:${otherTarget.value}", slice.token())
            assertNull(c.state.value.page)
            assertNull(c.state.value.reference)
            c.refresh(slice.token())
            runCurrent()
            assertNull(c.state.value.page)
            assertEquals(LocalServiceProblem.UNAVAILABLE, c.state.value.readProblem)
            assertEquals(readsBeforeOtherTarget + 1, slice.control.endpoint.reads.size)
            assertEquals(LocalManagerOrderReviewQueryV1(otherTarget), slice.control.endpoint.selectors.last())
            assertEquals(setOf("orderId"), slice.control.endpoint.selectorFields.last())
            assertEquals(f.operation(), c.state.value.operationId)
            assertEquals(original, c.state.value.operation)
            assertEquals(0, slice.control.journal.stageWrites)
            assertTrue(slice.port.staged.isEmpty())
            slice.control.assertNoDispatch()
            // The prior Recorded marker is controlled UI state, not a claimed original durable result.
        }
    }
}

private fun LocalWorkflowPage.changedScalar(field: LocalField,
    change: (LocalFormField.Scalar) -> LocalFormField.Scalar): LocalWorkflowPage = copy(forms = forms.map { form ->
        form.copy(fields = form.fields.map { if (it is LocalFormField.Scalar && it.field == field) change(it) else it })
    })

private class ReassignmentUiPort(private val actual: LocalWorkflowGateway,
    private val transform: (LocalWorkflowPage) -> LocalWorkflowPage) : LocalWorkflowGateway by actual {
    val staged = mutableListOf<LocalCommandIntent>()
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult = when (val result = actual.read(query)) {
        is LocalReadResult.Available -> LocalReadResult.Available(transform(result.page))
        is LocalReadResult.Unavailable -> result
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        staged += intent
        return actual.stage(intent)
    }
}

private class ReassignmentControllerSession(override val workflows: LocalWorkflowGateway,
    private val control: ReassignmentGatewayControl) : BranchUiSession {
    private val f = FrozenManagerReassignmentV1
    override val display = SessionDisplayContext(f.manager, f.scope.companyId, f.scope.branchId,
        "Synthetic manager", "Synthetic company", "Synthetic branch", setOf(AppArea.ORDERS))
    private var active = true
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(f.session, 1))
    private val lifetimes = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifetimes
    override fun isCurrent(): Boolean = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    override suspend fun mayRestore(route: AppRoute): Boolean = active && RouteRegistry.area(route) in display.allowedAreas
    override fun close() {
        active = false
        control.leaseCurrent = false
        attachments.value = null
        lifetimes.value = LocalSessionLifecycle.LOCKED
    }
}

private class ReassignmentControllerSignIn(private val session: BranchUiSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
}

private class ReassignmentControllerSlice(val runtime: UkkeraRuntime, val controller: LocalWorkflowController,
    val control: ReassignmentGatewayControl, val port: ReassignmentUiPort) {
    fun token(): NavigationToken = runtime.navigator.token()
}

private suspend fun TestScope.withReassignmentController(
    transform: (LocalWorkflowPage) -> LocalWorkflowPage = { it }, originalMarker: OperationId? = null,
    block: suspend TestScope.(ReassignmentControllerSlice) -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var runtime: UkkeraRuntime? = null
    var controller: LocalWorkflowController? = null
    try {
        val f = FrozenManagerReassignmentV1
        val control = ReassignmentGatewayControl()
        val port = ReassignmentUiPort(control.gateway, transform)
        val session = ReassignmentControllerSession(port, control)
        val actualRuntime = UkkeraRuntime(AppDependencies(branchGateway = ReassignmentControllerSignIn(session),
            platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
        runtime = actualRuntime
        actualRuntime.openSignIn(false, actualRuntime.navigator.token())
        actualRuntime.signIn(session.display.actorName, charArrayOf('x'), actualRuntime.navigator.token())
        assertEquals(session.display, actualRuntime.presentation.value.display)
        val workspace = LocalWorkspaceState(workflow = LocalWorkflow.ORDERS, search = f.query().search,
            reference = originalMarker?.let { LocalRecordReference(LocalRecordKind.ORDER, f.target, f.r17) },
            operationId = originalMarker, operation = originalMarker?.let { LocalOperationResult.Recorded(it,
                LocalRecordedDecision.APPLIED, LocalText("Controlled prior UI result", "نتيجة واجهة سابقة مضبوطة")) })
        val actualController = LocalWorkflowController(actualRuntime, session, actualRuntime.navigator.token(), this,
            LocalWorkflowContinuation(workspace, null, originalMarker?.let { LocalAction.ORDER_REASSIGN }, null, null))
        controller = actualController
        actualController.activate(LocalWorkflow.ORDERS)
        runCurrent()
        block(ReassignmentControllerSlice(actualRuntime, actualController, control, port))
    } finally {
        try { controller?.close(); runtime?.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

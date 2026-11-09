@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.supplierinvoicecapturerpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.ui.localWorkflows
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/**
 * Supporting wiring for frozen qa/pos/designs/supplier-invoice-capture-rpc-v1.
 * Shared UI only: no canonical claim, authoritative business port, native custody, RPC or restart run.
 */
class SupplierInvoiceCaptureRpcUiProfileSupportingTest {
    /** ICR-01/09 partial: route selection for the declared restricted workspace, not availability/admission. */
    @Test
    fun icr01Icr09CaptureWorkspaceRoutesExcludeOtherBusinessAreas() {
        assertTrue(invoiceCaptureUiRoute(AppRoute.Purchasing))
        assertTrue(invoiceCaptureUiRoute(AppRoute.SessionAccess))
        for (route in listOf(AppRoute.Catalog, AppRoute.Setup, AppRoute.Orders, AppRoute.Cashier,
            AppRoute.Stock, AppRoute.Drawer, AppRoute.Reports, AppRoute.Food, AppRoute.People, AppRoute.Devices))
            assertFalse(invoiceCaptureUiRoute(route))
        assertEquals(listOf(LocalWorkflow.PURCHASING),
            localWorkflows(AppArea.PURCHASING, LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY))
        for (area in AppArea.entries.filterNot { it == AppArea.PURCHASING })
            assertTrue(localWorkflows(area, LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY).isEmpty())
    }

    /** ICR-03/09 partial: actual controller selectors issue invoice/PO reads only; callback data is scripted. */
    @Test
    fun icr03Icr09ControllerKeepsInvoiceAndPoSelectorsOnly() = runTest {
        withIcrUi(initialWorkflow = LocalWorkflow.CATALOG) {
            assertTrue(port.reads.isEmpty())
            controller.activate(LocalWorkflow.PURCHASING)
            runCurrent()
            controller.selectPurchasing(LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES), runtime.navigator.token())
            runCurrent()
            assertEquals(LocalPurchasingSection.INVOICES, controller.state.value.purchasingQuery.section)
            val calls = port.reads.size
            for (section in LocalPurchasingSection.entries.filter { it !in listOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS) }) {
                controller.selectPurchasing(LocalPurchasingUiQuery(section), runtime.navigator.token())
                runCurrent()
                assertEquals(LocalPurchasingSection.INVOICES, controller.state.value.purchasingQuery.section)
                assertEquals(calls, port.reads.size)
            }
            controller.selectPurchasing(LocalPurchasingUiQuery(LocalPurchasingSection.ORDERS), runtime.navigator.token())
            runCurrent()
            assertEquals(LocalPurchasingSection.ORDERS, port.reads.last().purchasing?.section)
            assertTrue(port.reads.all { it.workflow == LocalWorkflow.PURCHASING &&
                it.purchasing?.section in listOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS) })
            assertTrue(port.stages.isEmpty())
            assertTrue(port.submissions.isEmpty())
        }
    }

    /** ICR-09 partial: an excluded form in a controlled response cannot become a displayed/staged action. */
    @Test
    fun icr09ControllerRejectsExcludedConfigurationForm() = runTest {
        withIcrUi(configure = {
            it.page = LocalWorkflowPage(LocalWorkflow.PURCHASING,
                records = emptyList(),
                forms = listOf(LocalActionForm(LocalAction.SUPPLIER_CREATE, 1, LocalText("Synthetic foreign form", ""),
                    emptyList())))
        }) {
            assertNull(controller.state.value.page)
            assertNotNull(controller.state.value.readProblem)
            controller.openForm(LocalAction.SUPPLIER_CREATE, runtime.navigator.token())
            controller.stage(runtime.navigator.token())
            runCurrent()
            assertNull(controller.state.value.form)
            assertTrue(port.stages.isEmpty())
            assertTrue(port.submissions.isEmpty())
            assertTrue(port.lookups.isEmpty())
            assertTrue(port.acknowledgements.isEmpty())
        }
    }

    /** ICR-21/22 partial: mixed in-memory presentation keeps C0 unresolved and cannot adopt or dispatch it. */
    @Test
    fun icr21Icr22IncompatibleOriginalStaysInertAcrossUiDeactivationAndRetirement() = runTest {
        // C0 remains the frozen employee-A/company-A/branch-A original. This UI observation
        // carries no canonical claim, device/native binding or qualified recovery capability.
        val c0 = OperationId("80000000-0000-4000-8000-000000000101")
        val unresolved = LocalOperationResult.Unresolved(c0, LocalServiceProblem.UNAVAILABLE)
        val original = LocalWorkflowContinuation(
            LocalWorkspaceState(workflow = LocalWorkflow.RECEIVING,
                purchasingQuery = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES),
                operationId = c0, operation = unresolved),
            frozen = null, originalAction = LocalAction.SUPPLIER_INVOICE_CAPTURE, retail = null, supplier = null)

        withIcrUi(continuation = original) {
            repeat(2) { round ->
                assertFalse(controller.invoiceCaptureWorkspaceCompatible())
                assertEquals(LocalWorkflow.RECEIVING, controller.state.value.workflow)
                assertEquals(c0, controller.state.value.operationId)
                assertSame(unresolved, controller.state.value.operation)
                val token = runtime.navigator.token()
                controller.refresh(token)
                controller.selectWorkflow(LocalWorkflow.PURCHASING, token)
                controller.selectPurchasing(LocalPurchasingUiQuery(LocalPurchasingSection.ORDERS), token)
                controller.openForm(LocalAction.SUPPLIER_INVOICE_CAPTURE, token)
                controller.stage(token)
                controller.retryStage(token)
                controller.submit(c0, token)
                controller.lookup(c0, token)
                controller.acknowledgeRecorded(c0, token)
                controller.reviewAttachment(token)
                runCurrent()
                assertEquals(listOf(0, 0, 0, 0, 0, 0), port.callCounts(), "inert round $round")

                controller.deactivate()
                runCurrent()
                if (round == 0) {
                    controller.activate(LocalWorkflow.PURCHASING)
                    runCurrent()
                }
            }
            val retired = assertNotNull(controller.retireForAttachment())
            assertEquals(original, retired)
            assertSame(unresolved, retired.workspace.operation)
            val oldCallback = runtime.navigator.token()
            controller.activate(LocalWorkflow.PURCHASING)
            controller.submit(c0, oldCallback)
            controller.lookup(c0, oldCallback)
            controller.acknowledgeRecorded(c0, oldCallback)
            runCurrent()
            assertEquals(listOf(0, 0, 0, 0, 0, 0), port.callCounts())
            assertEquals(original, retired)
        }
    }
}

/** CommonTest scripted UI callbacks; not an invoice decoder, journal, Master or RPC adapter. */
private class IcrUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val pendingReads = mutableListOf<String?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var page = LocalWorkflowPage(LocalWorkflow.PURCHASING, emptyList(), emptyList())
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query
        return LocalReadResult.Available(page)
    }
    override suspend fun pending(cursor: String?): LocalPendingResult {
        pendingReads += cursor
        return LocalPendingResult.Available(emptyList())
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.NotStaged(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    fun callCounts() = listOf(reads.size, pendingReads.size, stages.size, submissions.size, lookups.size, acknowledgements.size)
}

private class IcrUiSession(override val workflows: IcrUiPort) : BranchUiSession {
    // Exact employee/company/branch carriers from frozen fixtures.json identities; they issue no authority.
    override val display = SessionDisplayContext(DomainId("90000000-0000-4000-8000-000000000001"),
        DomainId("20000000-0000-4000-8000-000000000001"), DomainId("30000000-0000-4000-8000-000000000001"),
        "Synthetic ICR owner", "Synthetic company", "Synthetic branch", setOf(AppArea.PURCHASING),
        workspaceMode = LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY)
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var current = true
    override fun isCurrent() = current
    override suspend fun current(): LocalSessionCheckResult = if (current) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = current && invoiceCaptureUiRoute(route)
    override fun close() { current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

private class IcrUiFixture(val runtime: UkkeraRuntime, val controller: LocalWorkflowController, val port: IcrUiPort)

private suspend fun TestScope.withIcrUi(
    initialWorkflow: LocalWorkflow = LocalWorkflow.PURCHASING,
    continuation: LocalWorkflowContinuation? = null,
    configure: (IcrUiPort) -> Unit = {},
    block: suspend IcrUiFixture.() -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = IcrUiPort().also(configure)
    val session = IcrUiSession(port)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var controller: LocalWorkflowController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-icr-owner", charArrayOf('x'), runtime.navigator.token())
        val created = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, continuation)
        controller = created
        created.activate(initialWorkflow)
        runCurrent()
        IcrUiFixture(runtime, created, port).block()
    } finally {
        try { controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

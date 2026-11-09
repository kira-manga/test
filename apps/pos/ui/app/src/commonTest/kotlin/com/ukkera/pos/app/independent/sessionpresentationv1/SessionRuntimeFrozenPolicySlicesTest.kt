package com.ukkera.pos.app.independent.sessionpresentationv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppNavigator
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf

/**
 * Runtime/navigation/search slices of the unchanged local-session-presentation-v1 freeze.
 * Controlled BranchUiSession projections drive the production common presentation paths; the
 * non-authorizing draft epochs are presentation inputs, not policy activation or master grants.
 * All protected workflow reads and pending reads receive controlled unavailable replies.
 *
 * No supported protected note/reason checkpoint, original pending-payment tuple, actual report
 * cost/margin projection, subscription, UI frame or admitted RPC is supplied. Those observations
 * and every complete case/variant remain NOT_RUN. A retained in-memory search is not evidence
 * of durable checkpoint recovery or original-payment identity/effect preservation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeFrozenPolicySlicesTest {
    @Test
    fun lsp001A01A03V01_sameActorKeepsActiveCashierSearchAndStartsNoWork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var runtimeToClose: UkkeraRuntime? = null
        try {
            val session = ControlledUiSession(sampleDisplay())
            val gateway = ControlledUiGateway(session)
            val runtime = runtime(gateway).also { runtimeToClose = it }
            signIn(runtime)
            val before = requireNotNull(runtime.presentation.value.display)
            val activeRoute = runtime.navigator.state.value.activeEntry.route
            check(activeRoute == AppRoute.Cashier) { "This slice requires the same active cashier workflow." }
            val model = activeEntry(runtime)
            val original = requireNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            original.changeSearch(FrozenUiSelection.SAFE_SEARCH, runtime.navigator.token())
            check(original.state.value.search == FrozenUiSelection.SAFE_SEARCH) {
                "The supported in-memory search edit must be captured before renewal."
            }
            val signInsBeforeRenewal = gateway.signInCalls
            // The common UI receives equal captured continuity epochs after a new attachment.
            // Actual newer policy-reference activation is outside this controlled projection.
            val held = session.holdRefresh(before.copy(localDraftEpochs = LocalSessionDraftEpochs(1, 1)))
            runtime.refreshLocalSession(runtime.navigator.token())
            runCurrent()
            held.captured.await()
            held.release()
            runCurrent()

            val current = requireNotNull(runtime.presentation.value.display)
            assertSameEmployee(before, current, FrozenUiSelection.RENEWAL_UI)
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root,
                FrozenUiSelection.RENEWAL_UI.message("the enabled employee remains in the workspace"))
            assertTrue(session.isCurrent(), FrozenUiSelection.RENEWAL_UI.message("the controlled client is still current"))
            assertEquals(activeRoute, runtime.navigator.state.value.activeEntry.route,
                FrozenUiSelection.RENEWAL_UI.message("the active cashier route is retained"))
            val continued = requireNotNull(activeEntry(runtime).localWorkflows(runtime, runtime.navigator.token()))
            continued.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            assertEquals(LocalWorkflow.CHECKOUT, continued.state.value.workflow,
                FrozenUiSelection.RENEWAL_UI.message("the supported search remains in the same cashier workflow"))
            assertEquals(FrozenUiSelection.SAFE_SEARCH, continued.state.value.search,
                FrozenUiSelection.RENEWAL_UI.message("the captured safe search remains recoverable in memory"))
            assertNull(runtime.navigator.state.value.confirmation,
                FrozenUiSelection.RENEWAL_UI.message("renewal supplies no implicit confirmation"))
            assertNoImplicitWork(session, gateway, signInsBeforeRenewal, FrozenUiSelection.RENEWAL_BOUNDARY)
        } finally {
            try { runtimeToClose?.close() } finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun lsp002A01A03_reportNarrowingKeepsCashierSearchAndRejectsRetiredRoutes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var runtimeToClose: UkkeraRuntime? = null
        try {
            val session = ControlledUiSession(sampleDisplay())
            val gateway = ControlledUiGateway(session)
            val runtime = runtime(gateway).also { runtimeToClose = it }
            // This contains only routes, not a protected draft or old report/cursor/media data.
            val oldRoutes = AppNavigator().apply {
                replaceRoot(SecurityRoot.BRANCH_WORKSPACE, session.display)
                check(navigate(NavigationAction.SelectArea(AppArea.REPORTS), token()) == NavigationResult.CHANGED)
            }
            check(runtime.navigator.quarantineRestore(requireNotNull(oldRoutes.checkpoint())))
            signIn(runtime)
            val before = requireNotNull(runtime.presentation.value.display)
            check(AppArea.REPORTS in before.allowedAreas && AppArea.CASHIER in before.allowedAreas)
            val original = requireNotNull(activeEntry(runtime).localWorkflows(runtime, runtime.navigator.token()))
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            original.changeSearch(FrozenUiSelection.SAFE_SEARCH, runtime.navigator.token())
            check(original.state.value.search == FrozenUiSelection.SAFE_SEARCH)
            val signInsBeforeRenewal = gateway.signInCalls
            // Report-only cleanup changes general continuity, not ordinary cashier continuity.
            // This projection does not stand in for LOCAL-DELEGATE activation or a grant read.
            val held = session.holdRefresh(before.copy(allowedAreas = setOf(AppArea.CASHIER),
                localDraftEpochs = LocalSessionDraftEpochs(2, 1)))
            runtime.refreshLocalSession(runtime.navigator.token())
            runCurrent()
            held.captured.await()
            held.release()
            runCurrent()

            val current = requireNotNull(runtime.presentation.value.display)
            assertSameEmployee(before, current, FrozenUiSelection.NARROWED_UI)
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root,
                FrozenUiSelection.NARROWED_UI.message("report narrowing is not employee logout"))
            assertFalse(runtime.presentation.value.locallyLocked,
                FrozenUiSelection.NARROWED_UI.message("the same enabled cashier remains interactive"))
            assertFalse(AppArea.REPORTS in current.allowedAreas,
                FrozenUiSelection.NARROWED_UI.message("the retired report navigation hint is absent"))
            assertFalse(AppArea.REPORTS in runtime.navigator.state.value.stacks,
                FrozenUiSelection.NARROWED_UI.message("retired report route history is removed"))
            val continued = requireNotNull(activeEntry(runtime).localWorkflows(runtime, runtime.navigator.token()))
            continued.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            assertEquals(LocalWorkflow.CHECKOUT, continued.state.value.workflow,
                FrozenUiSelection.NARROWED_UI.message("the still-permitted cashier workflow remains active"))
            assertEquals(FrozenUiSelection.SAFE_SEARCH, continued.state.value.search,
                FrozenUiSelection.NARROWED_UI.message("supported cashier search survives report-only narrowing"))
            assertFalse(runtime.restoreAuthenticatedRoutes(),
                FrozenUiSelection.NARROWED_UI.message("the old report route-only checkpoint cannot restore a removed area"))
            assertEquals(NavigationResult.IGNORED,
                runtime.navigator.navigate(NavigationAction.SelectArea(AppArea.REPORTS), runtime.navigator.token()),
                FrozenUiSelection.NARROWED_UI.message("current navigation denies the removed report area"))
            assertEquals(AppRoute.Cashier, runtime.navigator.state.value.activeEntry.route,
                FrozenUiSelection.NARROWED_UI.message("retired route attempts do not replace the permitted cashier"))
            assertNoImplicitWork(session, gateway, signInsBeforeRenewal, FrozenUiSelection.NARROWED_IDENTITY)
        } finally {
            try { runtimeToClose?.close() } finally { Dispatchers.resetMain() }
        }
    }

    private fun runtime(gateway: BranchUiGateway): UkkeraRuntime = UkkeraRuntime(AppDependencies(
        branchGateway = gateway, platform = PlatformCapabilities(PlatformFamily.DESKTOP),
    ))

    private suspend fun signIn(runtime: UkkeraRuntime) {
        runtime.openSignIn(company = false, runtime.navigator.token())
        runtime.signIn("ACTOR-A", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'), runtime.navigator.token())
    }

    private fun activeEntry(runtime: UkkeraRuntime): EntryViewModel {
        val id = runtime.navigator.state.value.activeEntry.id
        val root = runtime.rootScope
        val provider = ViewModelProvider.create(root.viewModelStore,
            factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
        return provider["entry-$id", EntryViewModel::class]
    }

    private fun assertSameEmployee(before: SessionDisplayContext, after: SessionDisplayContext, oracle: FrozenUiAssertion) {
        assertEquals(before.actorId, after.actorId, oracle.message("employee sample identity is unchanged"))
        assertEquals(before.companyId, after.companyId, oracle.message("company sample scope is unchanged"))
        assertEquals(before.branchId, after.branchId, oracle.message("branch sample scope is unchanged"))
    }

    private fun assertNoImplicitWork(session: ControlledUiSession, gateway: ControlledUiGateway,
        signInsBeforeRenewal: Int, oracle: FrozenUiAssertion) {
        assertEquals(signInsBeforeRenewal, gateway.signInCalls, oracle.message("no implicit re-login"))
        assertEquals(0, session.boundary.operationDispatches.size,
            oracle.message("newly initiated stage/submit requests are zero"))
        assertEquals(0, session.boundary.lookupCalls,
            oracle.message("there is no automatic outcome lookup before an explicit original-ID user action"))
        assertEquals(0, session.boundary.acknowledgementCalls,
            oracle.message("renewal never acknowledges or finalizes a pending result"))
    }

    private fun sampleDisplay(): SessionDisplayContext = SessionDisplayContext(
        actorId = DomainId("00000000-0000-4000-8000-000000000001"),
        companyId = DomainId("00000000-0000-4000-8000-000000000002"),
        branchId = DomainId("00000000-0000-4000-8000-000000000003"),
        actorName = "ACTOR-A", companyName = "COMPANY-1", branchName = "BRANCH-A",
        allowedAreas = setOf(AppArea.CASHIER, AppArea.REPORTS),
        localDraftEpochs = LocalSessionDraftEpochs(1, 1),
    )
}

/** Current display/key are captured together before the controlled held response is released. */
private class ControlledUiSession(initialDisplay: SessionDisplayContext) : BranchUiSession {
    private var currentDisplay = initialDisplay
    private var closed = false
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(
        DomainId("00000000-0000-4000-8000-000000000004"), 1))
    private val lifecycleState = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var refreshPlan: ControlledUiRefresh? = null
    val boundary = ControlledUiWorkflowBoundary()
    override val display: SessionDisplayContext get() = currentDisplay
    override val workflows: LocalWorkflowGateway get() = boundary
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifecycleState
    override fun isCurrent(): Boolean = !closed && attachments.value != null

    fun holdRefresh(nextDisplay: SessionDisplayContext): ControlledUiRefresh {
        check(refreshPlan == null)
        return ControlledUiRefresh(nextDisplay).also { refreshPlan = it }
    }

    override suspend fun refresh(): LocalSessionCheckResult {
        val plan = refreshPlan ?: return LocalSessionCheckResult.Unavailable(LocalServiceProblem.UNAVAILABLE, attachmentKey)
        refreshPlan = null
        val previous = requireNotNull(attachments.value)
        val next = previous.copy(generation = previous.generation + 1)
        currentDisplay = plan.display
        attachments.value = next
        val captured = LocalSessionCheckResult.Current(plan.display, next)
        plan.captured.complete(Unit)
        plan.awaitRelease()
        return captured
    }

    override suspend fun current(): LocalSessionCheckResult = if (isCurrent())
        LocalSessionCheckResult.Current(currentDisplay, attachments.value)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachments.value)

    override suspend fun mayRestore(route: AppRoute): Boolean = isCurrent() && RouteRegistry.area(route) in currentDisplay.allowedAreas

    override fun close() {
        closed = true
        attachments.value = null
        lifecycleState.value = LocalSessionLifecycle.LOCKED
    }
}

private class ControlledUiRefresh(val display: SessionDisplayContext) {
    val captured = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    fun release() { released.complete(Unit) }
    suspend fun awaitRelease() { released.await() }
}

private class ControlledUiGateway(private val session: ControlledUiSession) : BranchUiGateway {
    var signInCalls = 0
        private set
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
        signInCalls++
        check(account == "ACTOR-A")
        return SignInResult.Authenticated(session)
    }
}

/** No successful business projection, pending tuple, stage, result or acknowledgement is made. */
private class ControlledUiWorkflowBoundary : LocalWorkflowGateway {
    val operationDispatches = mutableListOf<String>()
    var lookupCalls = 0
        private set
    var acknowledgementCalls = 0
        private set
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult =
        LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    override suspend fun pending(cursor: String?): LocalPendingResult =
        LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        operationDispatches += "STAGE"
        return LocalStageResult.NotStaged(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        operationDispatches += "SUBMIT"
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookupCalls++
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgementCalls++
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
}

private data class FrozenUiAssertion(val id: String, val expected: String) {
    fun message(observedSlice: String): String = "$id: $observedSlice. Frozen expectation: $expected"
}

/** Verbatim selections from LSP-CASES-1 and LSP-FIXTURES-1; no alternative expected results. */
private object FrozenUiSelection {
    const val SAFE_SEARCH = "LSP 000031"
    val RENEWAL_UI = FrozenUiAssertion("LSP-001-A01", "Same active workflow can still recover its supported checkpointed PRIVATE-A-LSP-DRAFT and LSP 000031 safe fields. No user change, implicit confirmation or false payment/sale completion is shown.")
    val RENEWAL_BOUNDARY = FrozenUiAssertion("LSP-001-A03", "Between accepted renewal and explicit lookup, newly initiated payment/charge/sale/approval/kitchen mutations are zero. The explicit lookup uses current authorization and original OP-PAY-ORIGINAL.")
    val NARROWED_UI = FrozenUiAssertion("LSP-002-A01", "A remains the same enabled employee. Cost/margin sensitive views and revoked actions are cleared/denied after current policy becomes known; supported safe input in the still-permitted same cashier workflow remains recoverable.")
    val NARROWED_IDENTITY = FrozenUiAssertion("LSP-002-A03", "Original pending payment ID/body/actor remain pinned; retired permission snapshot is never proof of a new action. No automatic payment retry or new body is generated.")
}

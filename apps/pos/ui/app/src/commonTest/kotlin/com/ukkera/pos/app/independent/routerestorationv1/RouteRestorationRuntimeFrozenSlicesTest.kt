package com.ukkera.pos.app.independent.routerestorationv1

import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionLifecycle
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
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Bounded navigation-state slices of local-route-restoration-v1, frozen version 1.0.
 * Actual AppNavigator checkpoint/quarantine and UkkeraRuntime restoration/monitoring are used.
 * Display, attachment and restoration booleans are controlled unit inputs. No successful
 * protected read, admitted employee/device, activated master policy or rendered frame exists.
 *
 * CP-order binds only its supported order identifier and required Orders root here. Checkpoint
 * save/reopen is in memory, not durable native lifecycle. private_draft_d1, payload markers,
 * real duplicate deliveries, fresh protected reads and all five business-effect observations
 * are unbound. R-cost/CP-inventory, RPC and native variants and every whole case remain NOT_RUN.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteRestorationRuntimeFrozenSlicesTest {
    @Test
    fun lrr001B_orderCheckpointCannotSelectItsEmployeeAfterQuarantine() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var runtimeToClose: UkkeraRuntime? = null
        try {
            val session = ControlledRouteRestorationSession(
                FrozenRouteRestorationUiInputs.employeeBProjection(), FrozenRouteRestorationUiInputs.SESSION_B,
            )
            val gateway = ControlledRouteRestorationGateway(session)
            val runtime = runtime(gateway).also { runtimeToClose = it }
            check(runtime.navigator.quarantineRestore(orderCheckpoint()))
            assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root,
                FrozenRouteRestorationUiInputs.QUARANTINE.message("navigation-state slice: checkpoint keeps bootstrap"))
            assertNull(runtime.presentation.value.display,
                FrozenRouteRestorationUiInputs.QUARANTINE.message("navigation-state slice: no employee is selected"))
            assertNoOldOrder(runtime, FrozenRouteRestorationUiInputs.QUARANTINE)

            signIn(runtime, "employee_b")
            val before = runtime.navigator.state.value
            assertFalse(runtime.restoreAuthenticatedRoutes(),
                FrozenRouteRestorationUiInputs.OTHER_EMPLOYEE.message("navigation-state slice: employee_a's order checkpoint is rejected"))
            assertEquals(before, runtime.navigator.state.value,
                FrozenRouteRestorationUiInputs.OTHER_EMPLOYEE.message("navigation-state slice: employee_b's current surface is retained"))
            assertNoOldOrder(runtime, FrozenRouteRestorationUiInputs.OTHER_EMPLOYEE)
            assertEquals(FrozenRouteRestorationUiInputs.EMPLOYEE_B, runtime.presentation.value.display?.actorId,
                FrozenRouteRestorationUiInputs.OTHER_EMPLOYEE.message("controlled employee_b projection is preserved"))
            assertTrue(session.isCurrent(),
                FrozenRouteRestorationUiInputs.OTHER_EMPLOYEE.message("controlled employee_b capability is not closed"))
            assertEquals(1, gateway.signInCalls,
                FrozenRouteRestorationUiInputs.OTHER_EMPLOYEE.message("checkpoint initiates no additional sign-in"))
            assertTrue(session.validationCalls.isEmpty(),
                FrozenRouteRestorationUiInputs.OTHER_EMPLOYEE.message("wrong-owner checkpoint reaches no route validation callback"))
        } finally {
            try { runtimeToClose?.close() } finally { Dispatchers.resetMain() }
        }
    }

    @Test
    fun lrr002P_pendingOrderCompletionCannotRestoreWithdrawnHistoryAfterRegrant() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var runtimeToClose: UkkeraRuntime? = null
        var monitor: Job? = null
        try {
            val session = ControlledRouteRestorationSession(
                FrozenRouteRestorationUiInputs.employeeAProjection(FrozenRouteRestorationUiInputs.P40),
                FrozenRouteRestorationUiInputs.SESSION_A,
            )
            val gateway = ControlledRouteRestorationGateway(session)
            val runtime = runtime(gateway).also { runtimeToClose = it }
            check(runtime.navigator.quarantineRestore(orderCheckpoint()))
            signIn(runtime, "employee_a")
            monitor = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.monitorAuthenticatedSession()
            }
            runCurrent()
            val before = requireNotNull(runtime.presentation.value.display)
            val initialAttachment = requireNotNull(session.attachmentKey)
            val held = session.holdOrderValidation()
            val restoration = async(start = CoroutineStart.UNDISPATCHED) { runtime.restoreAuthenticatedRoutes() }
            assertEquals(initialAttachment, held.captured.await())
            check(session.validationCalls == listOf(AppRoute.Orders, AppRoute.Order(FrozenRouteRestorationUiInputs.ORDER_A))) {
                "The unit schedule must complete the Orders root callback before holding the order callback."
            }
            assertNoOldOrder(runtime, FrozenRouteRestorationUiInputs.WITHDRAWN_ROUTE)

            // This is a Current presentation sample, not a policy activation at a master.
            session.replaceCurrentProjection(FrozenRouteRestorationUiInputs.employeeAProjection(FrozenRouteRestorationUiInputs.P41))
            runCurrent()
            assertEquals(session.attachmentKey, runtime.presentation.value.localAttachment)
            assertEquals(setOf(AppArea.STOCK), runtime.presentation.value.display?.allowedAreas)
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root,
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("controlled permission withdrawal keeps the local workspace"))
            assertEquals(AppRoute.Stock, runtime.navigator.state.value.activeEntry.route,
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("navigation-state slice: current quantity surface remains"))
            assertEquals(before.actorId, runtime.presentation.value.display?.actorId,
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("controlled employee identity remains employee_a"))
            assertTrue(session.isCurrent(),
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("controlled session_a capability is not closed"))
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle.value,
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("controlled permission loss produces no terminal lifecycle"))
            assertNull(runtime.presentation.value.sessionProblem,
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("permission withdrawal is not reported as session failure"))
            assertFalse(runtime.presentation.value.locallyLocked,
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("permission withdrawal does not lock the valid-session projection"))

            held.release()
            assertFalse(restoration.await(),
                FrozenRouteRestorationUiInputs.WITHDRAWN_ROUTE.message("old true callback cannot restore the private order reference"))
            assertNoOldOrder(runtime, FrozenRouteRestorationUiInputs.WITHDRAWN_ROUTE)
            runtime.navigator.navigate(NavigationAction.Back, runtime.navigator.token())
            runtime.navigator.navigate(NavigationAction.SelectArea(AppArea.ORDERS), runtime.navigator.token())
            assertNoOldOrder(runtime, FrozenRouteRestorationUiInputs.WITHDRAWN_ROUTE)

            session.replaceCurrentProjection(FrozenRouteRestorationUiInputs.employeeAProjection(FrozenRouteRestorationUiInputs.P42))
            runCurrent()
            assertEquals(session.attachmentKey, runtime.presentation.value.localAttachment)
            assertNoOldOrder(runtime, FrozenRouteRestorationUiInputs.NO_AUTOMATIC_RETURN)
            val callbacksBeforeRetry = session.validationCalls.toList()
            assertFalse(runtime.restoreAuthenticatedRoutes(),
                FrozenRouteRestorationUiInputs.NO_AUTOMATIC_RETURN.message("consumed old checkpoint cannot automatically restore after regrant"))
            assertEquals(callbacksBeforeRetry, session.validationCalls,
                FrozenRouteRestorationUiInputs.NO_AUTOMATIC_RETURN.message("retry initiates no old checkpoint callback"))
            assertEquals(1, gateway.signInCalls,
                FrozenRouteRestorationUiInputs.VALID_SESSION.message("withdrawal and regrant initiate no extra sign-in"))
            assertNoOldOrder(runtime, FrozenRouteRestorationUiInputs.NO_AUTOMATIC_RETURN)
        } finally {
            monitor?.cancel()
            try { runtimeToClose?.close() } finally { Dispatchers.resetMain() }
        }
    }

    private fun runtime(gateway: BranchUiGateway): UkkeraRuntime = UkkeraRuntime(AppDependencies(
        branchGateway = gateway, platform = PlatformCapabilities(PlatformFamily.DESKTOP),
    ))

    private suspend fun signIn(runtime: UkkeraRuntime, accountAlias: String) {
        runtime.openSignIn(company = false, runtime.navigator.token())
        runtime.signIn(accountAlias, charArrayOf('s', 'a', 'm', 'p', 'l', 'e'), runtime.navigator.token())
        check(runtime.navigator.state.value.root == SecurityRoot.BRANCH_WORKSPACE) {
            "The controlled unit session must be attached before its restoration schedule."
        }
    }

    private fun orderCheckpoint(): String {
        val source = AppNavigator()
        source.replaceRoot(SecurityRoot.BRANCH_WORKSPACE,
            FrozenRouteRestorationUiInputs.employeeAProjection(FrozenRouteRestorationUiInputs.P40)
                .copy(allowedAreas = setOf(AppArea.ORDERS)))
        check(source.navigate(NavigationAction.Push(AppRoute.Order(FrozenRouteRestorationUiInputs.ORDER_A)), source.token()) ==
            NavigationResult.CHANGED)
        return requireNotNull(source.checkpoint())
    }

    private fun assertNoOldOrder(runtime: UkkeraRuntime, assertion: FrozenRouteRestorationUiAssertion) {
        assertFalse(runtime.navigator.state.value.stacks.values.flatten().any {
            it.route == AppRoute.Order(FrozenRouteRestorationUiInputs.ORDER_A)
        }, assertion.message("navigation-state slice: no old order reference in any Back/tab stack; frame/payload/draft observations unbound"))
    }
}

/** Only presentation samples. The locator never becomes a credential or authoritative session. */
private class ControlledRouteRestorationSession(initialDisplay: SessionDisplayContext, val sessionSample: DomainId) : BranchUiSession {
    private var currentDisplay = initialDisplay
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(
        FrozenRouteRestorationUiInputs.DEVICE_A, 1,
    ))
    private val lifecycleState = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var heldOrder: HeldRestorationBooleanSample? = null
    val validationCalls = mutableListOf<AppRoute>()
    override val display: SessionDisplayContext get() = currentDisplay
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifecycleState
    override fun isCurrent(): Boolean = lifecycleState.value == LocalSessionLifecycle.ACTIVE && attachments.value != null

    fun holdOrderValidation(): HeldRestorationBooleanSample = HeldRestorationBooleanSample().also {
        check(heldOrder == null)
        check(sessionSample == FrozenRouteRestorationUiInputs.SESSION_A)
        heldOrder = it
    }

    fun replaceCurrentProjection(next: SessionDisplayContext) {
        val previous = requireNotNull(attachments.value)
        check(next.actorId == currentDisplay.actorId && next.companyId == currentDisplay.companyId &&
            next.branchId == currentDisplay.branchId)
        currentDisplay = next
        attachments.value = previous.copy(generation = previous.generation + 1)
    }

    override suspend fun current(): LocalSessionCheckResult = if (isCurrent())
        LocalSessionCheckResult.Current(currentDisplay, attachments.value)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachments.value)

    override suspend fun mayRestore(route: AppRoute): Boolean {
        validationCalls += route
        if (!isCurrent()) return false
        if (route == AppRoute.Orders) return true // Unit callback only; no protected read result.
        if (route != AppRoute.Order(FrozenRouteRestorationUiInputs.ORDER_A)) return false
        val held = heldOrder ?: return false
        heldOrder = null
        held.captured.complete(requireNotNull(attachments.value))
        held.awaitRelease()
        return true // Deliberately obsolete Boolean input; no real earlier allowed reply exists.
    }

    override fun close() {
        attachments.value = null
        lifecycleState.value = LocalSessionLifecycle.LOCKED
    }
}

private class HeldRestorationBooleanSample {
    val captured = CompletableDeferred<LocalSessionAttachmentKey>()
    private val released = CompletableDeferred<Unit>()
    fun release() { released.complete(Unit) }
    suspend fun awaitRelease() { released.await() }
}

private class ControlledRouteRestorationGateway(private val session: ControlledRouteRestorationSession) : BranchUiGateway {
    override val configured = true
    var signInCalls = 0
        private set
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
        check(account == session.display.actorName)
        signInCalls++
        return SignInResult.Authenticated(session)
    }
}

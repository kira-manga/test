package com.ukkera.pos.app.independent.sessionnoaccessv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalInventorySection
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionEndResult
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf

/**
 * Controlled public-boundary replies drive real common runtime/navigation/controller paths.
 * No successful protected record, original payment body, native clock, actual authentication,
 * durable storage or policy activation is fabricated. The boundary implements no UI lifecycle
 * fence: the real runtime/controller must reject retained callbacks and late replies itself.
 */
internal class NoAccessRuntimeFixture {
    val session = NoAccessControlledUiSession(FrozenNoAccessUiInputs.p10Display())
    val gateway = NoAccessControlledUiGateway(session)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway,
        platform = PlatformCapabilities(PlatformFamily.DESKTOP)))

    suspend fun attachExistingP10Session() {
        runtime.openSignIn(company = false, runtime.navigator.token())
        runtime.signIn(FrozenNoAccessUiInputs.EMPLOYEE_A_NAME, charArrayOf('s', 'a', 'm', 'p', 'l', 'e'),
            runtime.navigator.token())
        check(runtime.presentation.value.display == session.display) {
            "The existing P10 session must be attached before this assertion slice."
        }
    }

    fun monitor(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        runtime.monitorAuthenticatedSession()
    }

    fun activeEntry(): EntryViewModel {
        val id = runtime.navigator.state.value.activeEntry.id
        val root = runtime.rootScope
        val provider = ViewModelProvider.create(root.viewModelStore,
            factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
        return provider["entry-$id", EntryViewModel::class]
    }

    fun activeController(): LocalWorkflowController = requireNotNull(
        activeEntry().localWorkflows(runtime, runtime.navigator.token()),
    ) { "This slice requires an actual currently permitted common workflow entry." }

    fun close() {
        session.releaseHeldReplies()
        runtime.close()
    }
}

internal class NoAccessControlledUiSession(initialDisplay: SessionDisplayContext) : BranchUiSession {
    private var currentDisplay = initialDisplay
    private var closed = false
    // An ephemeral presentation owner is not the frozen authentication session ID.
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(
        LocalSessionAttachmentKey(DomainId.random(), 1))
    private val lifecycleState = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var currentBoundary = NoAccessControlledWorkflowBoundary()
    val boundaries = mutableListOf(currentBoundary)
    var workflowAcquisitions = 0
        private set
    var refreshCalls = 0
        private set
    var logoutCalls = 0
        private set
    private var logoutPlan: NoAccessHeldLogout? = null

    override val display: SessionDisplayContext get() = currentDisplay
    override val workflows: LocalWorkflowGateway get() {
        workflowAcquisitions++
        return currentBoundary
    }
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifecycleState
    override fun isCurrent(): Boolean = !closed && attachments.value != null

    /** Input to a controlled Current observation; not a local policy-administration action. */
    fun publishCurrent(display: SessionDisplayContext) {
        check(isCurrent())
        val before = requireNotNull(attachments.value)
        currentDisplay = display
        currentBoundary = NoAccessControlledWorkflowBoundary().also { boundaries += it }
        attachments.value = before.copy(generation = before.generation + 1)
    }

    fun holdDepartedCostError(): NoAccessHeldRead = currentBoundary.holdCostFailure()

    fun holdUnavailableLogout(): NoAccessHeldLogout {
        check(logoutPlan == null)
        return NoAccessHeldLogout().also { logoutPlan = it }
    }

    override suspend fun current(): LocalSessionCheckResult = if (isCurrent())
        LocalSessionCheckResult.Current(currentDisplay, attachments.value)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachments.value)

    override suspend fun refresh(): LocalSessionCheckResult {
        refreshCalls++
        // Positive zero-grant renewal has no frozen public mapping. No such success is supplied.
        return LocalSessionCheckResult.Unavailable(LocalServiceProblem.UNAVAILABLE, attachments.value)
    }

    override suspend fun mayRestore(route: AppRoute): Boolean = isCurrent() &&
        RouteRegistry.area(route) in currentDisplay.allowedAreas

    override suspend fun logout(): LocalSessionEndResult {
        logoutCalls++
        val plan = logoutPlan ?: return LocalSessionEndResult.UNRESOLVED
        plan.captured.complete(Unit)
        withContext(NonCancellable) { plan.awaitRelease() }
        return LocalSessionEndResult.UNRESOLVED
    }

    override fun close() {
        closed = true
        attachments.value = null
        lifecycleState.value = LocalSessionLifecycle.LOCKED
    }

    fun releaseHeldReplies() {
        boundaries.forEach { it.releaseHeldReply() }
        logoutPlan?.release()
    }

    val readCalls: Int get() = boundaries.sumOf { it.readQueries.size }
    val pendingCalls: Int get() = boundaries.sumOf { it.pendingCalls }
    val businessDispatches: Int get() = boundaries.sumOf { it.stageCalls + it.submitCalls }
    val lookupCalls: Int get() = boundaries.sumOf { it.lookupCalls }
    val acknowledgementCalls: Int get() = boundaries.sumOf { it.acknowledgementCalls }
}

internal class NoAccessControlledUiGateway(private val session: NoAccessControlledUiSession) : BranchUiGateway {
    var signInCalls = 0
        private set
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
        signInCalls++
        check(account == FrozenNoAccessUiInputs.EMPLOYEE_A_NAME)
        return SignInResult.Authenticated(session)
    }
}

/** Successful projections/commands are absent; call counters alone do not prove durable effects. */
internal class NoAccessControlledWorkflowBoundary : LocalWorkflowGateway {
    val readQueries = mutableListOf<LocalWorkflowQuery>()
    var pendingCalls = 0
        private set
    var stageCalls = 0
        private set
    var submitCalls = 0
        private set
    var lookupCalls = 0
        private set
    var acknowledgementCalls = 0
        private set
    private var heldCost: NoAccessHeldRead? = null

    fun holdCostFailure(): NoAccessHeldRead {
        check(heldCost == null)
        return NoAccessHeldRead().also { heldCost = it }
    }

    fun releaseHeldReply() { heldCost?.release() }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        readQueries += query
        val held = heldCost.takeIf { query.workflow == LocalWorkflow.INVENTORY && query.inventory == LocalInventorySection.COSTS }
        if (held != null) {
            held.captured.complete(Unit)
            // Keep a queued old response alive even when the actual departing entry cancels
            // its await. No old-owner safety decision is implemented in this boundary.
            withContext(NonCancellable) { held.awaitRelease() }
        }
        return LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun pending(cursor: String?): LocalPendingResult {
        pendingCalls++
        return LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stageCalls++
        return LocalStageResult.NotStaged(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submitCalls++
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

internal class NoAccessHeldRead {
    val captured = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    fun release() { released.complete(Unit) }
    suspend fun awaitRelease() { released.await() }
}

internal class NoAccessHeldLogout {
    val captured = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    fun release() { released.complete(Unit) }
    suspend fun awaitRelease() { released.await() }
}

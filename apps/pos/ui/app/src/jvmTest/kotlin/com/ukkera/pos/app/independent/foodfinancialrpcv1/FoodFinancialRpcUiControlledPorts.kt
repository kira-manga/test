@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodfinancialrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain

/** Exact presentation-port schedule only; no disk durability, Master admission, RPC or native source claim. */
internal class FoodFinancialUiBarrier {
    val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    suspend fun awaitRelease() { entered.complete(Unit); release.await() }
    fun release() { release.complete(Unit) }
}

internal class FoodFinancialUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val pendingReads = mutableListOf<String?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var savedIntent: LocalCommandIntent? = null
        private set
    var pendingOriginal: LocalPendingOperation? = null
    var readReply: suspend (LocalWorkflowQuery) -> LocalReadResult = {
        LocalReadResult.Available(FrozenFoodFinancialUi.page(it.food ?: LocalFoodUiQuery.MyChecks()))
    }
    var pendingReply: suspend (String?) -> LocalPendingResult = {
        LocalPendingResult.Available(listOfNotNull(pendingOriginal))
    }
    var stageReply: suspend (LocalCommandIntent) -> LocalStageResult = {
        LocalStageResult.Staged(FrozenFoodFinancialUi.pending(it))
    }
    var submitReply: suspend (OperationId) -> LocalOperationResult = {
        LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE)
    }
    var lookupReply: suspend (OperationId) -> LocalOperationResult = {
        LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE)
    }
    var acknowledgementReply: suspend (OperationId) -> LocalAcknowledgementResult = {
        LocalAcknowledgementResult.Unresolved(it, LocalServiceProblem.STORAGE_UNCERTAIN)
    }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult { reads += query; return readReply(query) }
    override suspend fun pending(cursor: String?): LocalPendingResult { pendingReads += cursor; return pendingReply(cursor) }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        savedIntent = intent
        return stageReply(intent).also { if (it is LocalStageResult.Staged) pendingOriginal = it.operation }
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        pendingOriginal = pendingOriginal?.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED)
        return submitReply(operationId)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult { lookups += operationId; return lookupReply(operationId) }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return acknowledgementReply(operationId).also {
            if (it is LocalAcknowledgementResult.Acknowledged && it.operationId == operationId) pendingOriginal = null
        }
    }
    fun counts() = listOf(reads.size, pendingReads.size, stages.size, submissions.size, lookups.size, acknowledgements.size)
}

/** Synthetic session; health and display gate UI only and must not be mistaken for authenticated business rights. */
internal class FoodFinancialUiSession(override val workflows: FoodFinancialUiPort,
    override val display: SessionDisplayContext) : BranchUiSession {
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var current = true
    override fun isCurrent() = current
    override suspend fun current(): LocalSessionCheckResult = if (current) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = current && when (display.workspaceMode) {
        LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY -> foodCashFinancialUiRoute(route)
        LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY -> foodCheckUiRoute(route)
        else -> false
    }
    override fun close() { current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class FoodFinancialUiFixture(val runtime: UkkeraRuntime, val port: FoodFinancialUiPort,
    private val session: FoodFinancialUiSession, private val scope: TestScope,
    var controller: LocalWorkflowController) {
    fun token() = runtime.navigator.token()
    fun recreate(): LocalWorkflowContinuation {
        val retained = checkNotNull(controller.retireForAttachment())
        controller = LocalWorkflowController(runtime, session, token(), scope.backgroundScope, retained)
        controller.activate(LocalWorkflow.FOOD)
        return retained
    }
}

internal suspend fun TestScope.withFoodFinancialUi(continuation: LocalWorkflowContinuation? = null,
    mode: LocalSessionWorkspaceMode = LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY,
    initialQuery: LocalFoodUiQuery = LocalFoodUiQuery.MyChecks(), language: AppLanguage = AppLanguage.ENGLISH,
    configure: (FoodFinancialUiPort) -> Unit = {}, block: suspend FoodFinancialUiFixture.() -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = FoodFinancialUiPort().also(configure)
    val session = FoodFinancialUiSession(port, FrozenFoodFinancialUi.display(mode))
    val login = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = login,
        platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var fixture: FoodFinancialUiFixture? = null
    try {
        runtime.changeLanguage(language)
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-financial-owner", charArrayOf('x'), runtime.navigator.token())
        val controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, continuation)
        val activeFixture = FoodFinancialUiFixture(runtime, port, session, this, controller)
        fixture = activeFixture
        controller.activate(LocalWorkflow.FOOD, food = initialQuery)
        runCurrent()
        activeFixture.block()
    } finally {
        try { fixture?.controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

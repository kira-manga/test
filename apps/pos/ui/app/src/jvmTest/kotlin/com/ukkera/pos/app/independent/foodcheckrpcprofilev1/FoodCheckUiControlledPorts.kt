@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.assertTrue

/** Captures attempts at the presentation port, never grants authority or certifies durable staging/ACK. */
internal class FoodCheckUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val pendingReads = mutableListOf<String?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var readReply: suspend (LocalWorkflowQuery) -> LocalReadResult = { LocalReadResult.Available(FrozenFoodCheckUiInputs.ownPage()) }
    var pendingReply: suspend (String?) -> LocalPendingResult = { LocalPendingResult.Available(emptyList()) }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult { reads += query; return readReply(query) }
    override suspend fun pending(cursor: String?): LocalPendingResult { pendingReads += cursor; return pendingReply(cursor) }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT)
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
    fun counts() = listOf(reads.size, pendingReads.size, stages.size, submissions.size, lookups.size, acknowledgements.size)
    fun assertNoBusinessOrRecoveryCalls() {
        assertTrue(stages.isEmpty() && submissions.isEmpty() && lookups.isEmpty() && acknowledgements.isEmpty())
    }
}

internal class FoodCheckUiSession(override val workflows: FoodCheckUiPort) : BranchUiSession {
    override val display = FrozenFoodCheckUiInputs.display()
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var current = true
    override fun isCurrent() = current
    override suspend fun current(): LocalSessionCheckResult = if (current) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = current && foodCheckUiRoute(route)
    override fun close() { current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class FoodCheckUiFixture(val runtime: UkkeraRuntime, val port: FoodCheckUiPort,
    val controller: LocalWorkflowController) {
    fun token() = runtime.navigator.token()
}

internal suspend fun TestScope.withFoodCheckUi(continuation: LocalWorkflowContinuation? = null,
    configure: (FoodCheckUiPort) -> Unit = {}, block: suspend FoodCheckUiFixture.() -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = FoodCheckUiPort().also(configure)
    val session = FoodCheckUiSession(port)
    val login = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = login,
        platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var controller: LocalWorkflowController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-check-owner", charArrayOf('x'), runtime.navigator.token())
        controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, continuation)
        controller.activate(LocalWorkflow.FOOD)
        runCurrent()
        FoodCheckUiFixture(runtime, port, controller).block()
    } finally {
        try { controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

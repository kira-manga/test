@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.kitchenboard

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.test.*

internal class KbaHeldRead {
    val requested = CompletableDeferred<LocalWorkflowQuery>()
    val reply = CompletableDeferred<LocalReadResult>()
    val returned = CompletableDeferred<Unit>()
}

/** Controlled public presentation ports only; no Master writer, native journal or RPC. */
internal class KbaPort(val actor: DomainId = KbaInputs.actorA,
    initial: LocalFoodUiTask = KbaInputs.queued, var original: KbaOriginal? = null) : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val submittedOriginals = mutableListOf<KbaOriginal>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    val boardRows = mutableMapOf(KbaInputs.stationA to listOf(initial),
        KbaInputs.stationB to listOf(KbaInputs.stationBQueued))
    val taskRows = mutableMapOf(initial.taskId to initial,
        KbaInputs.stationBQueued.taskId to KbaInputs.stationBQueued)
    var readProblem: LocalServiceProblem? = null
    var lookupResult: LocalOperationResult? = null
    var submitResult: LocalOperationResult? = null
    var returnRecordedOnSubmit = false
    var formSource: ((LocalFoodUiTask) -> List<LocalActionForm>)? = null
    private var nextHeld: KbaHeldRead? = null
    private val heldReads = mutableListOf<KbaHeldRead>()

    fun holdNextRead() = KbaHeldRead().also { check(nextHeld == null); nextHeld = it; heldReads += it }

    fun page(query: LocalFoodUiQuery, rows: List<LocalFoodUiTask>,
        forms: List<LocalActionForm> = emptyList()): LocalReadResult.Available =
        LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.KITCHEN, emptyList(), forms,
            food = LocalFoodUiPage(query, KbaInputs.company, KbaInputs.branch, actor, Revision(1),
                KbaInputs.observedAt, LocalFoodUiBody.Tasks(rows))))

    fun taskPage(row: LocalFoodUiTask, action: LocalAction? = null,
        forms: List<LocalActionForm>? = null): LocalReadResult.Available =
        page(KbaInputs.taskQuery(row), listOf(row), forms ?: action?.let { listOf(KbaInputs.form(row, it)) }.orEmpty())

    private fun available(query: LocalFoodUiQuery): LocalReadResult = when (query) {
        is LocalFoodUiQuery.StationTasks -> page(query, assertNotNull(boardRows[query.stationId]))
        is LocalFoodUiQuery.Task -> {
            val row = assertNotNull(taskRows[query.taskId])
            val forms = formSource?.invoke(row) ?: when {
                row.taskState == LocalFoodTaskState.QUEUED &&
                    row.preparation in setOf(LocalFoodPreparation.QUEUED, LocalFoodPreparation.PREPARING) ->
                    listOf(KbaInputs.form(row, LocalAction.FOOD_STATION_START))
                row.taskState == LocalFoodTaskState.STARTED && row.preparation == LocalFoodPreparation.PREPARING ->
                    listOf(KbaInputs.form(row, LocalAction.FOOD_STATION_READY))
                else -> emptyList()
            }
            page(query, listOf(row), forms)
        }
        is LocalFoodUiQuery.ConfiguredStations -> LocalReadResult.Available(LocalWorkflowPage(
            LocalWorkflow.KITCHEN, emptyList(), emptyList(), food = LocalFoodUiPage(query, KbaInputs.company,
                KbaInputs.branch, actor, Revision(1), KbaInputs.observedAt, LocalFoodUiBody.Stations(emptyList()))))
        else -> error("No frozen kitchen-board input for selector")
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        assertEquals(LocalWorkflow.KITCHEN, query.workflow)
        reads += query
        nextHeld?.let { held ->
            nextHeld = null
            return withContext(NonCancellable) {
                held.requested.complete(query)
                try { held.reply.await() } finally { held.returned.complete(Unit) }
            }
        }
        return readProblem?.let { LocalReadResult.Unavailable(it) } ?: available(assertNotNull(query.food))
    }

    override suspend fun pending(cursor: String?): LocalPendingResult =
        LocalPendingResult.Available(original?.takeIf { it.actor == actor }?.let { listOf(it.pending()) }.orEmpty())

    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        val row = assertNotNull(taskRows.values.singleOrNull { candidate ->
            KbaInputs.values(candidate) == intent.values
        })
        val stored = KbaOriginal(actor, row, intent, LocalPendingPhase.STAGED)
        original = stored
        return LocalStageResult.Staged(stored.pending())
    }

    fun recorded(operationId: OperationId): LocalOperationResult.Recorded = LocalOperationResult.Recorded(
        operationId, LocalRecordedDecision.RECORDED_ONLY, LocalText("Controlled original result", "نتيجة أصل متحكم بها"))

    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        original?.takeIf { it.actor == actor && it.intent.operationId == operationId }?.let(submittedOriginals::add)
        return submitResult ?: if (returnRecordedOnSubmit) recorded(operationId)
            else LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        return lookupResult ?: original?.takeIf { it.actor == actor && it.intent.operationId == operationId }
            ?.let { LocalOperationResult.Pending(it.pending()) }
            ?: LocalOperationResult.Unresolved(operationId, LocalServiceProblem.DENIED)
    }

    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return LocalAcknowledgementResult.Acknowledged(operationId)
    }

    fun assertNoNewOriginalsOrCommands() {
        assertTrue(stages.isEmpty(), "No original was staged at the controller boundary")
        assertTrue(submissions.isEmpty(), "No business command was submitted at the controller boundary")
        assertTrue(acknowledgements.isEmpty())
    }

    fun releaseHeld() = heldReads.forEach { it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) }
}

private class KbaSession(initial: KbaPort,
    initialEpochs: LocalSessionDraftEpochs = LocalSessionDraftEpochs(1, 1)) : BranchUiSession {
    override var workflows: LocalWorkflowGateway = initial
    override var display = SessionDisplayContext(initial.actor, KbaInputs.company, KbaInputs.branch,
        "Synthetic kitchen employee", "Synthetic kitchen company", "Synthetic branch",
        setOf(AppArea.ORDERS), initialEpochs)
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(initial.actor, 1))
    private var active = true
    private var replacement: KbaPort? = null
    private var replacementEpochs: LocalSessionDraftEpochs? = null
    fun replaceWith(port: KbaPort, epochs: LocalSessionDraftEpochs) { replacement = port; replacementEpochs = epochs }
    override fun isCurrent() = active
    override suspend fun mayRestore(route: AppRoute) = active
    override suspend fun refresh(): LocalSessionCheckResult {
        val fresh = assertNotNull(replacement)
        assertEquals(display.actorId, fresh.actor)
        workflows = fresh
        display = display.copy(localDraftEpochs = assertNotNull(replacementEpochs))
        replacement = null
        replacementEpochs = null
        attachmentUpdates.value = LocalSessionAttachmentKey(display.actorId, assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override fun close() { active = false }
    override suspend fun logout() = LocalSessionEndResult.REVOKED
}

private class KbaBranchGateway(initial: KbaSession) : BranchUiGateway {
    var nextSession = initial
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
        SignInResult.Authenticated(nextSession)
}

internal class KbaFixture private constructor(val runtime: UkkeraRuntime, private var session: KbaSession,
    private val branchGateway: KbaBranchGateway, val originalPort: KbaPort, private val scope: TestScope) {
    private val ports = mutableListOf(originalPort)
    var port = originalPort
        private set
    private var model = entryModel(runtime)
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        private set
    fun token() = runtime.navigator.token()

    fun leaveKitchen() {
        val departed = runtime.navigator.state.value.activeEntry.id
        runtime.navigate(NavigationAction.Back, token())
        runtime.navigator.state.value.confirmation?.let { runtime.confirmLeave(it.id) }
        assertTrue(runtime.navigator.state.value.activeEntry.id != departed, "Permitted Back removes the kitchen entry")
        scope.runCurrent()
    }

    fun openKitchen(query: LocalFoodUiQuery.StationTasks): LocalWorkflowController {
        assertEquals(NavigationResult.CHANGED,
            runtime.navigator.navigate(NavigationAction.Push(AppRoute.FoodStation(query.stationId)), token()))
        model = entryModel(runtime)
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.KITCHEN, food = query)
        scope.runCurrent()
        return controller
    }

    fun replaceAttachment(fresh: KbaPort,
        epochs: LocalSessionDraftEpochs = assertNotNull(session.display.localDraftEpochs)): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh, epochs)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        port = fresh
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.KITCHEN, food = KbaInputs.first)
        scope.runCurrent()
        return controller
    }

    suspend fun switchAccount(fresh: KbaPort, query: LocalFoodUiQuery.StationTasks): LocalWorkflowController {
        runtime.signOut()
        scope.runCurrent()
        ports += fresh
        session = KbaSession(fresh)
        branchGateway.nextSession = session
        port = fresh
        runtime.openSignIn(false, token())
        runtime.signIn("synthetic-kitchen-account", charArrayOf('x'), token())
        return openKitchen(query)
    }

    fun assertNoNewOriginalsOrCommands() = ports.forEach(KbaPort::assertNoNewOriginalsOrCommands)
    fun releaseHeld() = ports.forEach(KbaPort::releaseHeld)

    companion object {
        suspend fun TestScope.withKitchenBoard(row: LocalFoodUiTask = KbaInputs.queued,
            query: LocalFoodUiQuery.StationTasks = KbaInputs.first, original: KbaOriginal? = null,
            block: suspend (KbaFixture) -> Unit) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = KbaPort(initial = row, original = original)
            val session = KbaSession(port)
            val gateway = KbaBranchGateway(session)
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway,
                platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            var fixture: KbaFixture? = null
            try {
                runtime.openSignIn(false, runtime.navigator.token())
                runtime.signIn("synthetic-kitchen-account", charArrayOf('x'), runtime.navigator.token())
                assertEquals(NavigationResult.CHANGED, runtime.navigator.navigate(
                    NavigationAction.Push(AppRoute.FoodStation(query.stationId)), runtime.navigator.token()))
                fixture = KbaFixture(runtime, session, gateway, port, this)
                fixture.controller.activate(LocalWorkflow.KITCHEN, food = query)
                runCurrent()
                block(fixture)
            } finally {
                fixture?.releaseHeld()
                port.releaseHeld()
                try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
            }
        }
    }
}

private fun entryModel(runtime: UkkeraRuntime): EntryViewModel {
    val entry = runtime.navigator.state.value.activeEntry
    val root = runtime.rootScope
    val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
        initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
    })
    return provider["entry-${entry.id}", EntryViewModel::class]
}

internal fun assertKbaReview(controller: LocalWorkflowController, row: LocalFoodUiTask, action: LocalAction) {
    val state = controller.state.value
    val draft = assertNotNull(state.form)
    assertEquals(action, draft.descriptor.action)
    assertEquals(KbaInputs.taskQuery(row), state.foodQuery)
    assertEquals(listOf(row), assertIs<LocalFoodUiBody.Tasks>(state.page?.food?.body).tasks)
    assertEquals(KbaInputs.values(row), draft.descriptor.fields.filterIsInstance<LocalFormField.Scalar>()
        .map { LocalScalarInput(it.field, assertNotNull(it.initialValue)) })
    assertTrue(draft.descriptor.fields.filterIsInstance<LocalFormField.Scalar>().all { it.readOnly })
    assertNotNull(draft.descriptor.confirmation)
    assertFalse(draft.confirmed)
    assertNull(state.operationId)
    assertNull(state.operation)
}

internal fun assertKbaRefused(controller: LocalWorkflowController) {
    assertNull(controller.state.value.page)
    assertNull(controller.state.value.form)
    assertNull(controller.state.value.operationId)
    assertNull(controller.state.value.operation)
    assertNotNull(controller.state.value.readProblem)
}

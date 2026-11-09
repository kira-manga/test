@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.fooddeliverydraftsv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Frozen F_BLOCKED_TWO current display input, never an authoritative order seed. */
internal fun fdpUiDeliveryObservation() = LocalFoodUiDeliveryObservation(FdpUiFrozenInputs.restaurantOrder,
    Revision(30), LocalFoodUiDeliveryDetails(
        FdpUiFrozenInputs.baseTexts.getValue(LocalField.FOOD_DELIVERY_RECIPIENT),
        FdpUiFrozenInputs.baseTexts.getValue(LocalField.FOOD_DELIVERY_CONTACT),
        FdpUiFrozenInputs.baseTexts.getValue(LocalField.FOOD_DELIVERY_ADDRESS)))

internal fun fdpUiBlockedCheck(
    observation: LocalFoodUiDeliveryObservation? = fdpUiDeliveryObservation(),
    channel: String = "DELIVERY",
): LocalFoodUiBody.Check {
    val reference = when (channel) {
        "TAKEAWAY" -> FdpUiFrozenInputs.pickupReference
        "DINE_IN" -> FdpUiFrozenInputs.tableReference
        else -> FdpUiFrozenInputs.reference
    }
    val check = LocalFoodUiCheck(FdpUiFrozenInputs.restaurantOrder, FdpUiFrozenInputs.owner, Revision(30),
        "RESTAURANT", LocalFoodUiService(channel, reference, ""), 2, null, null)
    val portions = listOf(FdpUiFrozenInputs.firstPortion, FdpUiFrozenInputs.secondPortion).map { id ->
        LocalFoodUiPortion(id, "Synthetic quoted burger", Quantity(FdpUiFrozenInputs.servingUnit, 1, 0), "",
            Revision(1), LocalFoodPreparation.NOT_SENT, LocalFoodFulfillment.NOT_HANDED_OVER,
            false, false, null, "")
    }
    return LocalFoodUiBody.Check(check, portions, Money(Currency.EGP, 22800), true,
        kitchen = LocalFoodUiKitchenObservation(FdpUiFrozenInputs.restaurantOrder, Revision(30), 2,
            LocalFoodUiKitchenStatus.UNAVAILABLE), delivery = observation)
}

internal class FdpUiHeldRead {
    val requested = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalReadResult>()
    val returned = CompletableDeferred<Unit>()
}

/** Scripted common presentation port. It supplies no writer, authenticated RPC or native journal. */
internal class FdpUiPort(
    var formSource: LocalActionForm = fdpUiDraftForm(),
    var checkSource: LocalFoodUiBody.Check = fdpUiBlockedCheck(),
    var stageSummary: List<LocalRecordValue> = fdpUiSummary(FdpUiFrozenInputs.baseTexts),
) : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var previews = 0
    var readProblem: LocalServiceProblem? = null
    var lookupResult: LocalOperationResult? = null
    var pendingOperations: List<LocalPendingOperation> = emptyList()
    var stagedOperation: LocalPendingOperation? = null
        private set
    private var nextHeld: FdpUiHeldRead? = null
    private val heldReads = mutableListOf<FdpUiHeldRead>()

    fun holdNextRead(): FdpUiHeldRead = FdpUiHeldRead().also {
        check(nextHeld == null)
        nextHeld = it
        heldReads += it
    }

    fun available(query: LocalFoodUiQuery): LocalReadResult.Available {
        val body: LocalFoodUiBody = when (query) {
            is LocalFoodUiQuery.MyChecks -> LocalFoodUiBody.Checks(emptyList())
            is LocalFoodUiQuery.Check -> checkSource
            is LocalFoodUiQuery.Menu -> LocalFoodUiBody.Menu(null, emptyList())
            else -> error("No frozen UI input for this selector")
        }
        val forms = if (query is LocalFoodUiQuery.MyChecks) listOf(formSource) else emptyList()
        return LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms,
            food = LocalFoodUiPage(query, FdpUiFrozenInputs.company, FdpUiFrozenInputs.branch,
                FdpUiFrozenInputs.owner, Revision(1), UtcInstant.parseCanonical("2026-11-01T07:00:00Z"), body)))
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        assertEquals(LocalWorkflow.FOOD, query.workflow)
        val selected = assertNotNull(query.food)
        reads += query
        nextHeld?.let { held ->
            nextHeld = null
            return withContext(NonCancellable) {
                held.requested.complete(Unit)
                held.reply.await().also { held.returned.complete(Unit) }
            }
        }
        return readProblem?.let { LocalReadResult.Unavailable(it) } ?: available(selected)
    }

    override suspend fun pending(cursor: String?): LocalPendingResult =
        LocalPendingResult.Available(pendingOperations)

    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        val operation = LocalPendingOperation(intent.operationId, intent.action, fdpUiText("Stored draft request"),
            summary = stageSummary, submitAllowed = true)
        stagedOperation = operation
        return LocalStageResult.Staged(operation)
    }

    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        return lookupResult ?: LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        error("No recorded authoritative result is bound by this fixture")
    }

    override suspend fun previewFood(input: LocalFoodPreviewInput): LocalFoodPricePreviewResult {
        previews++
        return LocalFoodPricePreviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    }

    fun assertNoBusinessCalls() {
        assertTrue(stages.isEmpty())
        assertNoDispatchOrRecoveryCalls()
    }

    fun assertNoDispatchOrRecoveryCalls() {
        assertTrue(submissions.isEmpty())
        assertTrue(lookups.isEmpty())
        assertTrue(acknowledgements.isEmpty())
        assertEquals(0, previews)
    }

    fun releaseHeld() = heldReads.forEach {
        it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE))
    }
}

private class FdpUiSession(initial: FdpUiPort) : BranchUiSession {
    override val display = SessionDisplayContext(FdpUiFrozenInputs.owner, FdpUiFrozenInputs.company,
        FdpUiFrozenInputs.branch, "Synthetic owner", "Synthetic FOOD company", "Synthetic branch",
        setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initial
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(
        LocalSessionAttachmentKey(FdpUiFrozenInputs.owner, 1))
    private var active = true
    private var replacement: FdpUiPort? = null
    fun replaceWith(port: FdpUiPort) { replacement = port }
    override fun isCurrent(): Boolean = active
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(FdpUiFrozenInputs.owner,
            assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override fun close() { active = false }
}

internal class FdpUiFixture private constructor(
    val runtime: UkkeraRuntime,
    private val session: FdpUiSession,
    private val model: EntryViewModel,
    val port: FdpUiPort,
    private val scope: TestScope,
) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        private set
    private val ports = mutableListOf(port)
    fun token(): NavigationToken = runtime.navigator.token()
    fun replaceAttachment(fresh: FdpUiPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.FOOD)
        return controller
    }
    fun releaseHeld() = ports.forEach(FdpUiPort::releaseHeld)

    companion object {
        suspend fun TestScope.withController(
            form: LocalActionForm = fdpUiDraftForm(),
            query: LocalFoodUiQuery = LocalFoodUiQuery.MyChecks(),
            summary: List<LocalRecordValue> = fdpUiSummary(FdpUiFrozenInputs.baseTexts),
            block: suspend (FdpUiFixture) -> Unit,
        ) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = FdpUiPort(formSource = form, stageSummary = summary)
            val session = FdpUiSession(port)
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
                    SignInResult.Authenticated(session)
            }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            var fixture: FdpUiFixture? = null
            try {
                runtime.openSignIn(false, runtime.navigator.token())
                runtime.signIn("synthetic-owner", charArrayOf('x'), runtime.navigator.token())
                assertEquals(NavigationResult.CHANGED,
                    runtime.navigator.navigate(NavigationAction.Push(AppRoute.Food), runtime.navigator.token()))
                val entry = runtime.navigator.state.value.activeEntry
                val root = runtime.rootScope
                val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
                    initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
                })
                fixture = FdpUiFixture(runtime, session,
                    provider["entry-${entry.id}", EntryViewModel::class], port, this)
                fixture.controller.activate(LocalWorkflow.FOOD, food = query)
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

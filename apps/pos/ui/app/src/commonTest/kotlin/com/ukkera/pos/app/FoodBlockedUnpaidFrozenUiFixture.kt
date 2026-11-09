@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.test.*

/** Exact frozen public check inputs only; no FoodRow, financial original or historical frame. */
internal object FbuUiInputs {
    fun id(suffix: String) = DomainId("fd300000-0000-4000-8000-${suffix.padStart(12, '0')}")
    val owner = id("040")
    val company = id("010")
    val branch = id("020")
    val one = Quantity(id("240"), 1, 0)
    val time = UtcInstant.parseCanonical("2026-11-01T08:00:00Z")
    const val itemNote = "Serve together / يُقدَّم معًا"
    const val seatNote = "Seat 2 / المقعد ٢"
    val r0 = FbuUiOrder(id("100"), "RESTAURANT", emptyList(), 0)
    val c0 = FbuUiOrder(id("101"), "CAFE", emptyList(), 0)
    val r2 = FbuUiOrder(id("102"), "RESTAURANT", listOf(id("301"), id("302")), 20_000)
    val c2 = FbuUiOrder(id("103"), "CAFE", listOf(id("311"), id("312")), 6_000)
    val mixed = FbuUiOrder(id("106"), "RESTAURANT", listOf(id("321"), id("322"), id("323")),
        30_000, setOf(id("321")))
    val issued = FbuUiOrder(id("108"), "RESTAURANT", listOf(id("301"), id("302")),
        20_000, setOf(id("301"), id("302")))

    fun body(order: FbuUiOrder, status: LocalFoodUiKitchenStatus? = LocalFoodUiKitchenStatus.UNAVAILABLE,
        visible: List<DomainId> = order.portionIds): LocalFoodUiBody.Check {
        val check = LocalFoodUiCheck(order.orderId, owner, Revision(7), order.activity,
            LocalFoodUiService(order.channel, if (order.channel == "DINE_IN") id("090").value else "FBU-P001",
                if (order.channel == "DINE_IN") seatNote else ""), order.portionIds.size, null, null)
        val portions = visible.map { portionId ->
            // Pure display representatives. Revisions/descriptions are constructor-only metadata;
            // QUEUED does not supply the original issue/task, native custody or authority.
            val issuedMember = portionId in order.issuedIds
            LocalFoodUiPortion(portionId, "Synthetic serving / حصة تجريبية", one, itemNote,
                Revision(if (issuedMember) 2 else 1),
                if (issuedMember) LocalFoodPreparation.QUEUED else LocalFoodPreparation.NOT_SENT,
                LocalFoodFulfillment.NOT_HANDED_OVER, false, false,
                if (order == mixed) null else order.portionIds.first(), seatNote)
        }
        return LocalFoodUiBody.Check(check, portions, Money(Currency.EGP, order.quoteMinor), false,
            kitchen = status?.let { LocalFoodUiKitchenObservation(order.orderId, Revision(7),
                order.portionIds.size - order.issuedIds.size, it) })
    }

    fun available(query: LocalFoodUiQuery, check: LocalFoodUiBody.Check): LocalReadResult.Available {
        val body: LocalFoodUiBody = when (query) {
            is LocalFoodUiQuery.Check -> check
            is LocalFoodUiQuery.MyChecks -> LocalFoodUiBody.Checks(emptyList())
            is LocalFoodUiQuery.Menu -> LocalFoodUiBody.Menu(null, emptyList()) // Selector-only, not a catalog source.
            else -> error("Unbound frozen UI selector")
        }
        // Branch revision 1 is reply metadata, not a saved-order revision oracle.
        return LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(),
            if (query is LocalFoodUiQuery.Check) listOf(serviceForm(check)) else emptyList(),
            food = LocalFoodUiPage(query, company, branch, owner, Revision(1), time, body)))
    }

    private fun serviceForm(body: LocalFoodUiBody.Check): LocalActionForm {
        fun field(key: LocalField, kind: LocalInputKind, initial: LocalScalarValue, readOnly: Boolean = false,
            required: Boolean = true) = LocalFormField.Scalar(key, LocalText(key.name, key.name), kind,
            initialValue = initial, readOnly = readOnly, required = required)
        return LocalActionForm(LocalAction.FOOD_EDIT_SERVICE_DETAILS, 1,
            LocalText("Review service labels", "مراجعة أوصاف الخدمة"), listOf(
                field(LocalField.ORDER_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(body.check.orderId), true),
                field(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalInputKind.INTEGER, LocalScalarValue.Integer(7), true),
                field(LocalField.SERVICE_REFERENCE, LocalInputKind.TEXT, LocalScalarValue.Text(body.check.service.reference)),
                field(LocalField.SEAT_NOTE, LocalInputKind.TEXT, LocalScalarValue.Text(body.check.service.seatNote), required = false)))
    }
}

internal data class FbuUiOrder(val orderId: DomainId, val activity: String, val portionIds: List<DomainId>,
    val quoteMinor: Long, val issuedIds: Set<DomainId> = emptySet(), val channel: String = "DINE_IN")

internal class HeldFbuUiRead {
    val requested = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalReadResult>()
    val returned = CompletableDeferred<Unit>()
}

/** Scripted UI-input port only. No accepting writer/absence, original result or native journal. */
internal class FbuUiPort(var checkSource: LocalFoodUiBody.Check = FbuUiInputs.body(FbuUiInputs.r2)) : LocalWorkflowGateway {
    val reads = mutableListOf<LocalFoodUiQuery>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var previews = 0
    var readProblem: LocalServiceProblem? = null
    var pendingProblem: LocalServiceProblem? = null
    private var nextHeld: HeldFbuUiRead? = null
    private val heldReads = mutableListOf<HeldFbuUiRead>()

    fun holdNextRead(): HeldFbuUiRead = HeldFbuUiRead().also {
        check(nextHeld == null); nextHeld = it; heldReads += it
    }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        assertEquals(LocalWorkflow.FOOD, query.workflow)
        val selected = assertNotNull(query.food)
        reads += selected
        nextHeld?.let { held ->
            nextHeld = null
            return withContext(NonCancellable) {
                held.requested.complete(Unit)
                held.reply.await().also { held.returned.complete(Unit) }
            }
        }
        return readProblem?.let { LocalReadResult.Unavailable(it) } ?: FbuUiInputs.available(selected, checkSource)
    }
    override suspend fun pending(cursor: String?): LocalPendingResult =
        pendingProblem?.let { LocalPendingResult.Unavailable(it) } ?: LocalPendingResult.Available(emptyList())
    override suspend fun previewFood(input: LocalFoodPreviewInput): LocalFoodPricePreviewResult {
        previews++; return LocalFoodPricePreviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent; return LocalStageResult.NotStaged(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId; return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId; return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId; error("No recorded original is supplied")
    }
    fun assertNoBusinessCalls() {
        assertTrue(stages.isEmpty()); assertTrue(submissions.isEmpty()); assertTrue(lookups.isEmpty())
        assertTrue(acknowledgements.isEmpty()); assertEquals(0, previews)
    }
    fun releaseHeld() {
        heldReads.forEach { it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) }
    }
}

/** Session/display inputs are not authentication evidence; epochs are constructor metadata. */
private class FbuUiSession(initial: FbuUiPort) : BranchUiSession {
    override val display = SessionDisplayContext(FbuUiInputs.owner, FbuUiInputs.company, FbuUiInputs.branch,
        "Synthetic owner", "Synthetic FOOD company", "Synthetic branch", setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initial
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(FbuUiInputs.owner, 1))
    private var active = true
    private var replacement: FbuUiPort? = null
    fun replaceWith(port: FbuUiPort) { replacement = port }
    override fun isCurrent(): Boolean = active
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(FbuUiInputs.owner, assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override fun close() { active = false }
}

internal class FbuUiFixture private constructor(val runtime: UkkeraRuntime, private val session: FbuUiSession,
    private val model: EntryViewModel, val port: FbuUiPort, private val scope: TestScope) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        private set
    private val ports = mutableListOf(port)
    fun token(): NavigationToken = runtime.navigator.token()
    fun replaceAttachment(fresh: FbuUiPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.FOOD)
        return controller
    }
    fun releaseHeld() = ports.forEach(FbuUiPort::releaseHeld)

    companion object {
        suspend fun TestScope.withController(order: FbuUiOrder = FbuUiInputs.r2,
            block: suspend (FbuUiFixture) -> Unit) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = FbuUiPort(FbuUiInputs.body(order))
            val session = FbuUiSession(port)
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
                    SignInResult.Authenticated(session)
            }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            var fixture: FbuUiFixture? = null
            try {
                runtime.openSignIn(false, runtime.navigator.token())
                runtime.signIn("owner", charArrayOf('x'), runtime.navigator.token())
                assertEquals(NavigationResult.CHANGED,
                    runtime.navigator.navigate(NavigationAction.Push(AppRoute.Food), runtime.navigator.token()))
                val entry = runtime.navigator.state.value.activeEntry
                val root = runtime.rootScope
                val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
                    initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
                })
                fixture = FbuUiFixture(runtime, session, provider["entry-${entry.id}", EntryViewModel::class], port, this)
                fixture.controller.activate(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(order.orderId))
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

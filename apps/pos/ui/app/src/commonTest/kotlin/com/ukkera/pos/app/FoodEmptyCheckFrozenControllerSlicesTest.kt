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

/**
 * Controlled UI/controller slices of qa/pos/freezes/food-empty-check-v1.
 * Public form/read/result replies below are scripted seams, not installed-app,
 * authentication, durable Master, stock/money, restart or RPC evidence.
 * EFD-07 binds only the empty-check Add selector. Quantity 2, the complete source
 * and the exact 22000/28000 saved-line assertions remain unbound and unchanged.
 */
class FoodEmptyCheckFrozenControllerSlicesTest {
    @Test
    fun efd01OpenRefreshAndReopenHaveZeroBusinessSubmissions() = runTest {
        uiEmptyServiceInputs.forEach { input ->
            withEmptyCheckController { fixture ->
                fixture.openAndFill(input)
                fixture.controller.stage(fixture.token()) // No explicit confirmation.
                fixture.controller.refresh(fixture.token())
                runCurrent()
                fixture.port.assertNoMutationCalls()
                fixture.controller.discardForm(fixture.token())
                fixture.controller.refresh(fixture.token())
                runCurrent()
                fixture.openAndFill(input)
                fixture.port.assertNoMutationCalls()
            }
        }
    }

    @Test
    fun efd02ExplicitConfirmationRetainsExactLabelsAndFollowsTheOriginalCheckOnlyAfterAck() = runTest {
        uiEmptyServiceInputs.forEach { input ->
            withEmptyCheckController { fixture ->
                fixture.openAndFill(input)
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                val original = fixture.port.stages.single()
                fixture.assertOriginalCreateInput(original, input)
                assertTrue(fixture.port.submissions.isEmpty(), "Staging is not submission")
                val orderId = original.uiOrderId()
                val returnedRevision = Revision(13) // Controlled response input; no initial revision is mandated.
                fixture.port.checkSource = uiEmptyCheck(orderId, returnedRevision, input)
                fixture.port.submissionResult = LocalOperationResult.Recorded(original.operationId,
                    LocalRecordedDecision.APPLIED, uiEmptyText("Controlled recorded empty check"),
                    food = LocalFoodOperationDetails("food.order.draft_created", orderId, returnedRevision,
                        emptyList(), LocalFoodUiQuery.Check(orderId)))

                fixture.controller.submit(original.operationId, fixture.token())
                runCurrent()
                val shown = assertIs<LocalOperationResult.Recorded>(fixture.controller.state.value.operation)
                val shownFood = assertNotNull(shown.food)
                assertEquals(original.operationId, shown.operationId)
                assertEquals(orderId, shownFood.orderId)
                assertEquals(returnedRevision, shownFood.orderStorageRevision)
                assertTrue(shownFood.portions.isEmpty())
                assertTrue(fixture.port.reads.none { it is LocalFoodUiQuery.Check },
                    "A result must not trigger a pre-ack read or a second opening")
                fixture.controller.acknowledgeRecorded(original.operationId, fixture.token())
                runCurrent()
                assertEquals(listOf(original.operationId), fixture.port.acknowledgements)
                val current = assertIs<LocalFoodUiBody.Check>(fixture.controller.state.value.page?.food?.body)
                assertEquals(orderId, current.check.orderId)
                assertEquals(returnedRevision, current.check.orderStorageRevision)
                assertEquals(input.reference, current.check.service.reference)
                assertEquals(input.note.orEmpty(), current.check.service.seatNote)
                assertEquals(0, current.check.portionCount)
                assertTrue(current.portions.isEmpty())
                assertEquals(0L, current.originalQuotedTotal.minor)
                assertNull(current.financial)
                assertNull(current.check.collectionId)
                assertFails { current.copy(originalQuotedTotal = Money(Currency.EGP, 11000)) }
                assertFails { current.copy(chargeResolutionRequired = true) }
                assertFails { current.check.copy(collectionId = DomainId.random()) }
                assertFails { current.check.copy(workClosed = true) }

                // The normal Add control supplies this selector from the accepted current check.
                val add = LocalFoodUiQuery.Menu(orderId = current.check.orderId,
                    expectedOrderStorageRevision = current.check.orderStorageRevision)
                fixture.controller.selectFood(add, fixture.token())
                runCurrent()
                assertEquals(add, fixture.port.reads.last())
                assertEquals(add, fixture.controller.state.value.foodQuery)
                assertEquals(1, fixture.port.stages.size)
                assertEquals(listOf(original.operationId), fixture.port.submissions)
                // No configured serving, quantity-1 save or price is substituted for EFD-07.
            }
        }
    }

    @Test
    fun efd03AbsentEmptyAndAsciiBlankReferencesCannotPassUiConfirmation() = runTest {
        listOf<String?>(null, "", "   ").forEach { reference ->
            withEmptyCheckController { fixture ->
                fixture.openAndFill(uiEmptyServiceInputs.first(), reference)
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                assertEquals(LocalField.SERVICE_REFERENCE, fixture.controller.state.value.issue?.field)
                fixture.port.assertNoMutationCalls()
            }
        }
    }

    @Test
    fun efd04LateAllowedReadCannotReviveANewerDeniedAttachmentOrConfirmOldInput() = runTest {
        withEmptyCheckController { fixture ->
            fixture.openAndFill(uiEmptyServiceInputs.first())
            fixture.controller.discardForm(fixture.token())
            val held = fixture.port.holdNextRead()
            fixture.controller.refresh(fixture.token())
            runCurrent()
            held.requested.await()
            val oldController = fixture.controller
            val oldToken = fixture.token()
            val deniedPort = EmptyUiPort().apply { denyReads = true }
            val current = fixture.replaceAttachment(deniedPort)
            current.reviewAttachment(fixture.token())
            runCurrent()
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            assertEquals(LocalServiceProblem.DENIED, current.state.value.readProblem)

            held.reply.complete(uiEmptyPage(LocalFoodUiQuery.MyChecks(), null))
            runCurrent()
            held.returned.await()
            oldController.openForm(LocalAction.FOOD_CREATE_DRAFT, oldToken)
            oldController.confirmForm(true, oldToken)
            oldController.stage(oldToken)
            current.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
            current.confirmForm(true, fixture.token())
            current.stage(fixture.token())
            runCurrent()
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            fixture.port.assertNoMutationCalls()
            deniedPort.assertNoMutationCalls()
        }
    }

    @Test
    fun efd06AttachmentReviewAndReadOnlyLookupKeepTheOriginalUnresolvedOperation() = runTest {
        withEmptyCheckController { fixture ->
            fixture.openAndFill(uiEmptyServiceInputs[2])
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            val original = fixture.port.stages.single()
            fixture.controller.submit(original.operationId, fixture.token())
            runCurrent()
            assertEquals(original.operationId,
                assertIs<LocalOperationResult.Unresolved>(fixture.controller.state.value.operation).operationId)
            val fresh = EmptyUiPort()
            val current = fixture.replaceAttachment(fresh)
            assertEquals(original.operationId, current.state.value.operationId)
            assertNull(current.state.value.form)
            assertTrue(current.state.value.attachmentReviewRequired)
            assertTrue(fresh.reads.isEmpty(), "Attachment replacement performs no implicit recovery or read")
            fresh.assertNoMutationCalls()

            current.reviewAttachment(fixture.token())
            runCurrent()
            assertFalse(current.state.value.attachmentReviewRequired)
            assertEquals(original.operationId, current.state.value.operationId)
            assertNull(current.state.value.form)
            fresh.assertNoMutationCalls()
            current.lookup(original.operationId, fixture.token())
            runCurrent()
            assertEquals(listOf(original.operationId), fresh.lookups)
            assertEquals(original.operationId,
                assertIs<LocalOperationResult.Unresolved>(current.state.value.operation).operationId)
            assertTrue(fresh.stages.isEmpty())
            assertTrue(fresh.submissions.isEmpty())
            assertEquals(1, fixture.port.stages.size)
            fixture.assertOriginalCreateInput(original, uiEmptyServiceInputs[2])
        }
    }

    @Test
    fun efd07ExistingSfcEmptyCheckKeepsItsOwnRevisionSevenAddSelector() = runTest {
        val sfcOrder = DomainId("f0000000-0000-4000-8000-000000000001")
        val existing = uiEmptyCheck(sfcOrder, Revision(7), uiEmptyServiceInputs.first())
        withEmptyCheckController(LocalFoodUiQuery.Check(sfcOrder), existing) { fixture ->
            val current = assertIs<LocalFoodUiBody.Check>(fixture.controller.state.value.page?.food?.body)
            val add = LocalFoodUiQuery.Menu(orderId = current.check.orderId,
                expectedOrderStorageRevision = current.check.orderStorageRevision)
            fixture.controller.selectFood(add, fixture.token())
            runCurrent()
            assertEquals(LocalFoodUiQuery.Menu(orderId = sfcOrder, expectedOrderStorageRevision = Revision(7)),
                fixture.port.reads.last())
            fixture.port.assertNoMutationCalls()
        }
    }
}

private data class UiEmptyServiceInput(val channel: String, val reference: String, val note: String?)
private val uiEmptyServiceInputs = listOf(
    UiEmptyServiceInput("DINE_IN", "T-02", "Seat 02 / المقعد 02"),
    UiEmptyServiceInput("COUNTER", "Counter 01 / الكاونتر 01", null),
    UiEmptyServiceInput("TAKEAWAY", "  Pickup 0007 / استلام 0007  ", "For pickup / للاستلام"),
)
private val UI_EMPTY_COMPANY = DomainId("a0000000-0000-4000-8000-000000000002")
private val UI_EMPTY_BRANCH = DomainId("a0000000-0000-4000-8000-000000000004")
private val UI_EMPTY_OWNER = DomainId("b0000000-0000-4000-8000-000000000001")
private fun uiEmptyText(value: String) = LocalText(value, value)
private fun LocalCommandIntent.uiOrderId(): DomainId =
    (values.single { it.field == LocalField.ORDER_ID }.value as LocalScalarValue.Identifier).value

/** Production descriptor shape only; this seam is not a protected gateway read. */
private fun uiEmptyCreateForm(): LocalActionForm {
    fun scalar(field: LocalField, kind: LocalInputKind, required: Boolean = true, readOnly: Boolean = false,
        choices: List<String> = emptyList(), maximum: Int = 256) = LocalFormField.Scalar(field, uiEmptyText(field.name),
        kind, required = required, readOnly = readOnly, choices = choices.map { LocalChoice(it, uiEmptyText(it)) },
        maximumCharacters = maximum)
    return LocalActionForm(LocalAction.FOOD_CREATE_DRAFT, 1, uiEmptyText("Open an empty food check"), listOf(
        scalar(LocalField.ORDER_ID, LocalInputKind.IDENTIFIER, readOnly = true, maximum = 36),
        scalar(LocalField.ACTIVITY, LocalInputKind.CHOICE, choices = listOf("RESTAURANT", "CAFE")),
        scalar(LocalField.CHANNEL, LocalInputKind.CHOICE, choices = uiEmptyServiceInputs.map { it.channel }),
        scalar(LocalField.SERVICE_REFERENCE, LocalInputKind.TEXT, maximum = 80),
        scalar(LocalField.SEAT_NOTE, LocalInputKind.TEXT, required = false, maximum = 160),
    ), confirmation = uiEmptyText("Confirm opening the empty check"))
}

private fun uiEmptyCheck(orderId: DomainId, revision: Revision, input: UiEmptyServiceInput) =
    LocalFoodUiCheck(orderId, UI_EMPTY_OWNER, revision, "RESTAURANT",
        LocalFoodUiService(input.channel, input.reference, input.note.orEmpty()), 0, null, null)

private fun uiEmptyPage(query: LocalFoodUiQuery, check: LocalFoodUiCheck?): LocalReadResult {
    val body = when (query) {
        is LocalFoodUiQuery.MyChecks -> LocalFoodUiBody.Checks(emptyList())
        is LocalFoodUiQuery.Check -> LocalFoodUiBody.Check(assertNotNull(check), emptyList(), Money(Currency.EGP, 0), false)
        is LocalFoodUiQuery.Menu -> LocalFoodUiBody.Menu(null, emptyList()) // Selector-only slice; no source substitution.
        else -> error("Unbound empty-check selector")
    }
    return LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(),
        if (query is LocalFoodUiQuery.MyChecks) listOf(uiEmptyCreateForm()) else emptyList(),
        food = LocalFoodUiPage(query, UI_EMPTY_COMPANY, UI_EMPTY_BRANCH, UI_EMPTY_OWNER, Revision(10),
            UtcInstant.parseCanonical("2026-10-30T09:00:00Z"), body)))
}

private class HeldEmptyRead {
    val requested = CompletableDeferred<Unit>()
    val returned = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalReadResult>()
}

private class EmptyUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalFoodUiQuery?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var checkSource: LocalFoodUiCheck? = null
    var submissionResult: LocalOperationResult? = null
    var denyReads = false
    private var nextHeld: HeldEmptyRead? = null
    private val heldReads = mutableListOf<HeldEmptyRead>()
    fun holdNextRead(): HeldEmptyRead = HeldEmptyRead().also { check(nextHeld == null); nextHeld = it; heldReads += it }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        assertEquals(LocalWorkflow.FOOD, query.workflow)
        reads += query.food
        nextHeld?.let { held ->
            nextHeld = null
            return withContext(NonCancellable) {
                held.requested.complete(Unit)
                held.reply.await().also { held.returned.complete(Unit) }
            }
        }
        return if (denyReads) LocalReadResult.Unavailable(LocalServiceProblem.DENIED)
        else uiEmptyPage(assertNotNull(query.food), checkSource)
    }
    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.Staged(LocalPendingOperation(intent.operationId, intent.action,
            uiEmptyText("Controlled original create intent"), submitAllowed = true))
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return submissionResult ?: LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return LocalAcknowledgementResult.Acknowledged(operationId)
    }
    fun assertNoMutationCalls() {
        assertTrue(stages.isEmpty()); assertTrue(submissions.isEmpty()); assertTrue(acknowledgements.isEmpty())
    }
    fun releaseHeld() { heldReads.forEach { it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) } }
}

private class EmptyUiSession(initial: EmptyUiPort) : BranchUiSession {
    override val display = SessionDisplayContext(UI_EMPTY_OWNER, UI_EMPTY_COMPANY, UI_EMPTY_BRANCH,
        "Owner A", "Food company", "Food branch", setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initial
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(UI_EMPTY_OWNER, 1))
    private var active = true
    private var replacement: EmptyUiPort? = null
    fun replaceWith(port: EmptyUiPort) { replacement = port }
    override fun isCurrent(): Boolean = active
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(UI_EMPTY_OWNER, assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override fun close() { active = false }
}

private class EmptyUiFixture(val runtime: UkkeraRuntime, private val session: EmptyUiSession,
    private val model: EntryViewModel, val port: EmptyUiPort, private val scope: TestScope) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
    private val ports = mutableListOf(port)
    fun token(): NavigationToken = runtime.navigator.token()
    fun openAndFill(input: UiEmptyServiceInput, reference: String? = input.reference) {
        controller.openForm(LocalAction.FOOD_CREATE_DRAFT, token())
        controller.edit(LocalField.ACTIVITY, LocalDraftValue(selected = LocalScalarValue.Choice("RESTAURANT")), token())
        controller.edit(LocalField.CHANNEL, LocalDraftValue(selected = LocalScalarValue.Choice(input.channel)), token())
        reference?.let { controller.edit(LocalField.SERVICE_REFERENCE, LocalDraftValue(it), token()) }
        input.note?.let { controller.edit(LocalField.SEAT_NOTE, LocalDraftValue(it), token()) }
        assertNotNull(controller.state.value.form)
    }
    fun assertOriginalCreateInput(original: LocalCommandIntent, input: UiEmptyServiceInput) {
        assertEquals(LocalAction.FOOD_CREATE_DRAFT, original.action)
        assertNull(original.reference)
        assertNull(original.lineField)
        assertTrue(original.lines.isEmpty())
        assertNotNull(original.uiOrderId())
        val values = original.values.associate { it.field to it.value }
        assertEquals(LocalScalarValue.Choice("RESTAURANT"), values[LocalField.ACTIVITY])
        assertEquals(LocalScalarValue.Choice(input.channel), values[LocalField.CHANNEL])
        assertEquals(LocalScalarValue.Text(input.reference), values[LocalField.SERVICE_REFERENCE])
        assertEquals(input.note?.let(LocalScalarValue::Text), values[LocalField.SEAT_NOTE])
        assertEquals(setOf(LocalField.ORDER_ID, LocalField.ACTIVITY, LocalField.CHANNEL, LocalField.SERVICE_REFERENCE) +
            if (input.note == null) emptySet() else setOf(LocalField.SEAT_NOTE), values.keys)
    }
    fun replaceAttachment(fresh: EmptyUiPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.FOOD)
        return controller
    }
    fun releaseHeld() = ports.forEach(EmptyUiPort::releaseHeld)
}

private suspend fun TestScope.withEmptyCheckController(initialQuery: LocalFoodUiQuery = LocalFoodUiQuery.MyChecks(),
    checkSource: LocalFoodUiCheck? = null, block: suspend (EmptyUiFixture) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = EmptyUiPort().also { it.checkSource = checkSource }
    val session = EmptyUiSession(port)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var fixture: EmptyUiFixture? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("owner-a", charArrayOf('x'), runtime.navigator.token())
        assertEquals(NavigationResult.CHANGED,
            runtime.navigator.navigate(NavigationAction.Push(AppRoute.Food), runtime.navigator.token()))
        val entry = runtime.navigator.state.value.activeEntry
        val root = runtime.rootScope
        val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
            initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
        })
        fixture = EmptyUiFixture(runtime, session, provider["entry-${entry.id}", EntryViewModel::class], port, this)
        fixture.controller.activate(LocalWorkflow.FOOD, food = initialQuery)
        runCurrent()
        block(fixture)
    } finally {
        fixture?.releaseHeld()
        port.releaseHeld()
        try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Controlled L1 runtime/controller slices of frozen shared-food-source-continuity-v1:
 * SFC-05's HANDOFF parent isolation and SFC-06 schedule 1's late source retirement.
 * Scripted Current/DENIED/read replies prove no native renewal, policy activation, Master
 * business cut, durable authority or RPC result. The production offer form has no reason
 * field; that frozen input remains unbound. BG-06 checkpoint restoration is not prescribed.
 */
class FoodSourceContinuityLateReplyTest {
    @Test
    fun sfc05OriginalDirectoryReplyCannotAdoptLookalikeOrder() = runTest {
        for (lookalikeReplyFirst in listOf(false, true)) {
            withLateFoodController { fixture ->
                val originalController = fixture.controller
                val original = fixture.selectOriginalLaterPageRecipient()
                val originalToken = fixture.runtime.navigator.token()
                val freshPort = LateFoodPort()
                val heldOriginal = freshPort.expect(LATE_FOOD_ROOT)
                val lookalikeRead = freshPort.expect(LATE_FOOD_LOOKALIKE_ROOT)
                val formerlyAuthorized = lateRecipientPage(LATE_FOOD_ROOT,
                    LATE_FOOD_ELIGIBLE.take(20), LATE_FOOD_ORIGINAL_NEXT, 10)
                // The distinct order's current page includes P with the same visible label.
                // Its remaining recipient is behind a continuation, not silently omitted.
                val lookalikeItems = listOf(LATE_FOOD_ELIGIBLE.last()) + LATE_FOOD_ELIGIBLE.take(19)
                val lookalikeReply = lateRecipientPage(LATE_FOOD_LOOKALIKE_ROOT, lookalikeItems,
                    LATE_FOOD_LOOKALIKE_ROOT.copy(after = lookalikeItems.last().actorId), 10)

                val earlier = fixture.replaceAttachment(freshPort)
                fixture.assertPendingOriginalInput(earlier, original)
                earlier.reviewAttachment(fixture.runtime.navigator.token())
                runCurrent()
                heldOriginal.requested.await()
                fixture.assertPendingOriginalInput(earlier, original)
                val earlierToken = fixture.runtime.navigator.token()

                // Use the normal guarded Back/reentry path for a separate source, without
                // injecting an old checkpoint into the new entry or choosing its recipient.
                val lookalike = fixture.leaveAndOpenLookalike()
                runCurrent()
                lookalikeRead.requested.await()
                assertLookalikeHasNoOriginalProposal(lookalike, pageAvailable = false)
                fixture.assertNoCommands()

                if (lookalikeReplyFirst) {
                    lookalikeRead.reply.complete(lookalikeReply)
                    runCurrent()
                    lookalikeRead.returned.await()
                    assertLookalikeHasNoOriginalProposal(lookalike, pageAvailable = true)
                }
                heldOriginal.reply.complete(formerlyAuthorized)
                runCurrent()
                heldOriginal.returned.await()
                assertLookalikeHasNoOriginalProposal(lookalike, pageAvailable = lookalikeReplyFirst)
                assertNoActiveFoodSource(earlier)
                fixture.assertNoCommands()

                if (!lookalikeReplyFirst) {
                    lookalikeRead.reply.complete(lookalikeReply)
                    runCurrent()
                    lookalikeRead.returned.await()
                }
                assertLookalikeHasNoOriginalProposal(lookalike, pageAvailable = true)
                val currentRecipients = assertNotNull(lookalike.state.value.page?.food)
                    .body as LocalFoodUiBody.Recipients
                assertTrue(currentRecipients.recipients.any {
                    it.actorId == original.recipientId && it.label == original.label
                }, "SFC-05: equal recipient labels/IDs on another parent cannot select P automatically")

                fixture.tryConfirmation(originalController, originalToken)
                fixture.tryConfirmation(earlier, earlierToken)
                fixture.tryConfirmation(earlier, fixture.runtime.navigator.token())
                fixture.tryConfirmation(lookalike, fixture.runtime.navigator.token())
                assertLookalikeHasNoOriginalProposal(lookalike, pageAvailable = true)
                freshPort.assertReadPlanConsumed()
                fixture.assertNoCommands()
            }
        }
    }

    @Test
    fun sfc06LateAuthorizedRecipientReplyCannotRestoreNewerDeniedAttachment() = runTest {
        for (holdSupplementalPage in listOf(false, true)) {
            withLateFoodController { fixture ->
                val originalController = fixture.controller
                val original = fixture.selectOriginalLaterPageRecipient()
                val originalToken = fixture.runtime.navigator.token()
                val earlierPort = LateFoodPort()
                val rootRead = earlierPort.expect(LATE_FOOD_ROOT)
                val held = if (holdSupplementalPage) earlierPort.expect(LATE_FOOD_ORIGINAL_NEXT) else rootRead
                val formerlyAuthorized = if (holdSupplementalPage)
                    lateRecipientPage(LATE_FOOD_ORIGINAL_NEXT, LATE_FOOD_ELIGIBLE.drop(20), null, 10)
                else lateRecipientPage(LATE_FOOD_ROOT, LATE_FOOD_ELIGIBLE.take(20), LATE_FOOD_ORIGINAL_NEXT, 10)

                val earlier = fixture.replaceAttachment(earlierPort)
                fixture.assertPendingOriginalInput(earlier, original)
                earlier.reviewAttachment(fixture.runtime.navigator.token())
                runCurrent()
                rootRead.requested.await()
                if (holdSupplementalPage) {
                    rootRead.reply.complete(lateRecipientPage(LATE_FOOD_ROOT,
                        LATE_FOOD_ELIGIBLE.take(20), LATE_FOOD_ORIGINAL_NEXT, 10))
                    runCurrent()
                    rootRead.returned.await()
                }
                held.requested.await()
                fixture.assertPendingOriginalInput(earlier, original)
                val earlierToken = fixture.runtime.navigator.token()

                val deniedPort = LateFoodPort()
                val denyRead = deniedPort.expect(LATE_FOOD_ROOT)
                val current = fixture.replaceAttachment(deniedPort)
                fixture.assertPendingOriginalInput(current, original)
                val newerAttachment = assertNotNull(fixture.runtime.presentation.value.localAttachment)
                current.reviewAttachment(fixture.runtime.navigator.token())
                runCurrent()
                denyRead.requested.await()
                denyRead.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.DENIED))
                runCurrent()
                denyRead.returned.await()
                assertDeniedFoodSource(current)
                assertNoActiveFoodSource(earlier)
                fixture.assertNoCommands()

                // The old controlled Available reply is delivered only after the newer
                // controller has observed DENIED. Cancellation cannot suppress this event.
                held.reply.complete(formerlyAuthorized)
                runCurrent()
                held.returned.await()
                assertDeniedFoodSource(current)
                assertNoActiveFoodSource(earlier)
                assertEquals(newerAttachment, fixture.runtime.presentation.value.localAttachment,
                    "SFC-06: the old source reply cannot replace the newer client attachment")

                fixture.tryConfirmation(originalController, originalToken)
                fixture.tryConfirmation(earlier, earlierToken)
                fixture.tryConfirmation(earlier, fixture.runtime.navigator.token())
                current.chooseFoodRecipient(original.recipientId, fixture.runtime.navigator.token())
                fixture.tryConfirmation(current, fixture.runtime.navigator.token())
                assertDeniedFoodSource(current)
                earlierPort.assertReadPlanConsumed()
                deniedPort.assertReadPlanConsumed()
                fixture.assertNoCommands()
                // No Master gateway denial, durable rotation/activation or business readback
                // is claimed by these blocked client actions and controlled call counters.
            }
        }
    }
}

private val LATE_FOOD_COMPANY = DomainId("a0000000-0000-4000-8000-000000000002")
private val LATE_FOOD_BRANCH = DomainId("a0000000-0000-4000-8000-000000000004")
private val LATE_FOOD_ACTOR = DomainId("b0000000-0000-4000-8000-000000000001")
private val LATE_FOOD_ORDER = DomainId("f0000000-0000-4000-8000-000000000003")
private val LATE_FOOD_LOOKALIKE_ORDER = DomainId("f0000000-0000-4000-8000-000000000004")
private val LATE_FOOD_ROOT = LocalFoodUiQuery.Recipients(LATE_FOOD_ORDER, Revision(7))
private val LATE_FOOD_LOOKALIKE_ROOT = LocalFoodUiQuery.Recipients(LATE_FOOD_LOOKALIKE_ORDER, Revision(7))
private val LATE_FOOD_ELIGIBLE = (1..21).map { index ->
    LocalFoodUiRecipient(DomainId("c1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
        "Recipient ${index.toString().padStart(2, '0')} / مستلم ${index.toString().padStart(2, '0')}")
}
private val LATE_FOOD_ORIGINAL_NEXT = LATE_FOOD_ROOT.copy(after = LATE_FOOD_ELIGIBLE[19].actorId)

private fun lateRecipientPage(query: LocalFoodUiQuery.Recipients, items: List<LocalFoodUiRecipient>,
    next: LocalFoodUiQuery.Recipients?, branchRevision: Long): LocalReadResult = LocalReadResult.Available(
    LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), listOf(lateOfferForm(query)), food = LocalFoodUiPage(
        query, LATE_FOOD_COMPANY, LATE_FOOD_BRANCH, LATE_FOOD_ACTOR, Revision(branchRevision),
        UtcInstant.parseCanonical("2026-10-30T09:00:00Z"),
        LocalFoodUiBody.Recipients(query.orderId, query.expectedOrderStorageRevision, items), next)))

/** Current production uncollected offer shape, without substituting an invented reason field. */
private fun lateOfferForm(query: LocalFoodUiQuery.Recipients): LocalActionForm {
    fun identifier(field: LocalField, id: DomainId? = null) = LocalFormField.Scalar(field,
        LocalText(field.name, field.name), LocalInputKind.IDENTIFIER, maximumCharacters = 36,
        initialValue = id?.let(LocalScalarValue::Identifier), readOnly = true)
    return LocalActionForm(LocalAction.FOOD_OFFER_HANDOFF, 1,
        LocalText("Choose an eligible recipient", "اختيار مستلم مؤهل"), listOf(
            identifier(LocalField.ORDER_ID, query.orderId),
            LocalFormField.Scalar(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
                LocalText("Expected order revision", "مراجعة الطلب المتوقعة"), LocalInputKind.INTEGER,
                maximumCharacters = 19,
                initialValue = LocalScalarValue.Integer(query.expectedOrderStorageRevision.value), readOnly = true),
            identifier(LocalField.HANDOFF_ID), identifier(LocalField.RECIPIENT_ID)),
        confirmation = LocalText("Review the original proposal", "مراجعة الاقتراح الأصلي"))
}

private fun assertLookalikeHasNoOriginalProposal(controller: LocalWorkflowController, pageAvailable: Boolean) {
    val state = controller.state.value
    assertEquals(LATE_FOOD_LOOKALIKE_ROOT, state.foodQuery, "SFC-05: the active parent is the exact lookalike order")
    assertNull(state.form, "SFC-05: original selection/confirmation cannot create a lookalike form")
    assertNull(state.formInput, "SFC-05: a separate source must not adopt the original input checkpoint")
    if (!pageAvailable) {
        assertNull(state.page, "SFC-05: the pending lookalike read cannot accept the original directory reply")
        return
    }
    val page = assertNotNull(state.page)
    val food = assertNotNull(page.food)
    assertEquals(LATE_FOOD_LOOKALIKE_ROOT, food.query)
    val body = food.body as LocalFoodUiBody.Recipients
    assertEquals(LATE_FOOD_LOOKALIKE_ORDER, body.orderId)
    assertEquals(Revision(7), body.orderStorageRevision)
    val fields = page.forms.single().fields.filterIsInstance<LocalFormField.Scalar>()
    assertEquals(LocalScalarValue.Identifier(LATE_FOOD_LOOKALIKE_ORDER),
        fields.single { it.field == LocalField.ORDER_ID }.initialValue)
    assertEquals(LocalScalarValue.Integer(7),
        fields.single { it.field == LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION }.initialValue)
    assertNull(fields.single { it.field == LocalField.RECIPIENT_ID }.initialValue)
    assertNull(fields.single { it.field == LocalField.HANDOFF_ID }.initialValue)
}

private fun assertNoActiveFoodSource(controller: LocalWorkflowController) {
    val state = controller.state.value
    assertNull(state.page, "SFC-06/SFC-05: retired protected directory details are absent")
    assertNull(state.form, "SFC-06/SFC-05: retired full form and confirmation are absent")
    assertNull(state.referencePicker, "SFC-06/SFC-05: retired protected selection controls are absent")
    // Safe inert checkpoint retention after denial is BG-06. Do not require its presence
    // or absence; protected source labels must be absent if such input is presented.
    state.formInput?.let { input ->
        assertTrue(input.values.values.all { it.referenceLabel == null })
        assertTrue(input.lines.all { line -> line.values.values.all { it.referenceLabel == null } })
    }
}

private fun assertDeniedFoodSource(controller: LocalWorkflowController) {
    assertNoActiveFoodSource(controller)
    assertEquals(LocalServiceProblem.DENIED, controller.state.value.readProblem,
        "SFC-06: the newer denied source remains denied after the old Available reply")
    assertFalse(controller.state.value.canStart, "SFC-06: the old reply cannot restore a client work affordance")
}

private class LateFoodRead(val query: LocalFoodUiQuery.Recipients) {
    val requested = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalReadResult>()
    val returned = CompletableDeferred<Unit>()
}

private class LateFoodPort : LocalWorkflowGateway {
    private val steps = ArrayDeque<LateFoodRead>()
    private val planned = mutableListOf<LateFoodRead>()
    val reads = mutableListOf<LocalFoodUiQuery?>()
    private val stages = mutableListOf<LocalCommandIntent>()
    private val submissions = mutableListOf<OperationId>()
    private val lookups = mutableListOf<OperationId>()

    fun expect(query: LocalFoodUiQuery.Recipients): LateFoodRead = LateFoodRead(query).also {
        steps.addLast(it)
        planned += it
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query.food
        assertEquals(LocalWorkflow.FOOD, query.workflow)
        assertTrue(steps.isNotEmpty(), "Unexpected controlled FOOD read")
        val step = steps.removeFirst()
        assertEquals(step.query, query.food)
        step.requested.complete(Unit)
        // Model a captured transport reply which still arrives after the UI cancels its job.
        // The returned barrier proves delivery at this controlled port, not Master authority.
        return withContext(NonCancellable) {
            val result = step.reply.await()
            step.returned.complete(Unit)
            result
        }
    }

    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.Staged(LocalPendingOperation(intent.operationId, intent.action,
            LocalText("Controlled client intent", "طلب عميل مُتحكم به"), submitAllowed = true))
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    fun assertNoCommands() {
        assertTrue(stages.isEmpty(), "SFC-05/SFC-06: no automatic or blocked-confirmation staging")
        assertTrue(submissions.isEmpty(), "SFC-05/SFC-06: zero business submissions on every attachment")
        assertTrue(lookups.isEmpty(), "SFC-05/SFC-06: reads/restoration do not start operation recovery")
    }
    fun assertReadPlanConsumed() {
        assertTrue(steps.isEmpty())
        assertEquals<List<LocalFoodUiQuery?>>(planned.map { it.query }, reads)
        assertTrue(planned.all { it.requested.isCompleted && it.returned.isCompleted })
    }
    fun releaseForCleanup() {
        planned.forEach { it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) }
    }
}

private class LateFoodSession(initialPort: LateFoodPort) : BranchUiSession {
    override val display = SessionDisplayContext(LATE_FOOD_ACTOR, LATE_FOOD_COMPANY, LATE_FOOD_BRANCH,
        "Owner A", "Food company", "Food branch", setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initialPort
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(LATE_FOOD_ACTOR, 1))
    private var active = true
    private var replacement: LateFoodPort? = null
    fun replaceWith(port: LateFoodPort) { replacement = port }
    override fun isCurrent(): Boolean = active
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(LATE_FOOD_ACTOR, assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override fun close() { active = false }
}

private data class LateRecipientCheckpoint(val recipientId: DomainId, val handoffId: DomainId,
    val label: String, val source: LocalFoodUiQuery.Recipients)

private class LateFoodControllerFixture(val runtime: UkkeraRuntime, private val session: LateFoodSession,
    private var model: EntryViewModel, val originalPort: LateFoodPort, private val scope: TestScope) {
    private val ports = mutableListOf(originalPort)
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        private set

    fun start() { controller.activate(LocalWorkflow.FOOD, food = LATE_FOOD_ROOT) }
    fun selectOriginalLaterPageRecipient(): LateRecipientCheckpoint {
        scope.runCurrent()
        val first = assertNotNull(controller.state.value.page?.food)
        val firstItems = (first.body as LocalFoodUiBody.Recipients).recipients
        assertTrue(firstItems.size <= 20)
        val next = assertNotNull(first.next)
        controller.refresh(runtime.navigator.token(), nextPage = true)
        scope.runCurrent()
        val last = assertNotNull(controller.state.value.page?.food)
        assertEquals(next, last.query)
        assertNull(last.next)
        val lastItems = (last.body as LocalFoodUiBody.Recipients).recipients
        assertTrue(lastItems.size <= 20)
        val union = firstItems + lastItems
        assertEquals(LATE_FOOD_ELIGIBLE.map { it.actorId }.toSet(), union.map { it.actorId }.toSet())
        assertEquals(21, union.size)
        assertEquals(21, union.map { it.actorId }.distinct().size)
        val p = lastItems.first { later -> firstItems.none { it.actorId == later.actorId } }
        controller.chooseFoodRecipient(p.actorId, runtime.navigator.token())
        controller.confirmForm(true, runtime.navigator.token())
        val form = assertNotNull(controller.state.value.form)
        assertTrue(form.confirmed)
        assertTrue(form.descriptor.title.english.contains(p.label))
        assertEquals(p.actorId.value, form.values.getValue(LocalField.RECIPIENT_ID).text)
        assertEquals(LATE_FOOD_ORDER.value, form.values.getValue(LocalField.ORDER_ID).text)
        assertEquals("7", form.values.getValue(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).text)
        val source = assertNotNull(form.foodRecipientSource)
        assertEquals(LATE_FOOD_ORDER, source.orderId)
        assertEquals(Revision(7), source.expectedOrderStorageRevision)
        originalPort.assertReadPlanConsumed()
        assertNoCommands()
        return LateRecipientCheckpoint(p.actorId,
            DomainId(form.values.getValue(LocalField.HANDOFF_ID).text), p.label, source)
    }
    fun replaceAttachment(port: LateFoodPort): LocalWorkflowController {
        ports += port
        session.replaceWith(port)
        runtime.refreshLocalSession(runtime.navigator.token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        controller.activate(LocalWorkflow.FOOD, food = LATE_FOOD_ROOT)
        assertTrue(controller.state.value.attachmentReviewRequired)
        assertNull(controller.state.value.page)
        assertNull(controller.state.value.form)
        assertTrue(port.reads.isEmpty(), "Attachment replacement itself starts no source read")
        assertNoCommands()
        return controller
    }
    fun assertPendingOriginalInput(current: LocalWorkflowController, original: LateRecipientCheckpoint) {
        assertNull(current.state.value.form, "Original confirmation is inactive until a fresh compatible source is bound")
        assertTrue(current.state.value.attachmentReviewRequired)
        assertEquals(LATE_FOOD_ROOT, current.state.value.foodQuery, "The fresh read starts at the original parent root")
        val input = assertNotNull(current.state.value.formInput)
        assertEquals(original.recipientId.value, input.values.getValue(LocalField.RECIPIENT_ID).text)
        assertEquals(original.handoffId.value, input.values.getValue(LocalField.HANDOFF_ID).text)
        assertEquals(original.source, input.foodRecipientSource)
        assertTrue(input.values.values.all { it.referenceLabel == null })
    }
    fun leaveAndOpenLookalike(): LocalWorkflowController {
        val departedEntry = runtime.navigator.state.value.activeEntry.id
        runtime.navigate(NavigationAction.Back, runtime.navigator.token())
        runtime.navigator.state.value.confirmation?.let { runtime.confirmLeave(it.id) }
        assertTrue(runtime.navigator.state.value.activeEntry.id != departedEntry)
        assertEquals(NavigationResult.CHANGED,
            runtime.navigator.navigate(NavigationAction.Push(AppRoute.Food), runtime.navigator.token()))
        model = lateFoodEntryModel(runtime)
        controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        controller.activate(LocalWorkflow.FOOD, food = LATE_FOOD_LOOKALIKE_ROOT)
        return controller
    }
    fun tryConfirmation(current: LocalWorkflowController, token: NavigationToken) {
        current.confirmForm(true, token)
        current.stage(token)
        scope.runCurrent()
    }
    fun assertNoCommands() { ports.forEach(LateFoodPort::assertNoCommands) }
    fun releaseForCleanup() { ports.forEach(LateFoodPort::releaseForCleanup) }
}

private fun lateFoodEntryModel(runtime: UkkeraRuntime): EntryViewModel {
    val entry = runtime.navigator.state.value.activeEntry
    val root = runtime.rootScope
    val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
        initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
    })
    return provider["entry-${entry.id}", EntryViewModel::class]
}

private suspend fun TestScope.withLateFoodController(block: suspend (LateFoodControllerFixture) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val originalPort = LateFoodPort()
    originalPort.expect(LATE_FOOD_ROOT).reply.complete(lateRecipientPage(LATE_FOOD_ROOT,
        LATE_FOOD_ELIGIBLE.take(20), LATE_FOOD_ORIGINAL_NEXT, 10))
    originalPort.expect(LATE_FOOD_ORIGINAL_NEXT).reply.complete(lateRecipientPage(LATE_FOOD_ORIGINAL_NEXT,
        LATE_FOOD_ELIGIBLE.drop(20), null, 10))
    val session = LateFoodSession(originalPort)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var fixture: LateFoodControllerFixture? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("owner-a", charArrayOf('x'), runtime.navigator.token())
        assertEquals(NavigationResult.CHANGED,
            runtime.navigator.navigate(NavigationAction.Push(AppRoute.Food), runtime.navigator.token()))
        fixture = LateFoodControllerFixture(runtime, session, lateFoodEntryModel(runtime), originalPort, this)
        fixture.start()
        block(fixture)
    } finally {
        fixture?.releaseForCleanup()
        originalPort.releaseForCleanup()
        try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

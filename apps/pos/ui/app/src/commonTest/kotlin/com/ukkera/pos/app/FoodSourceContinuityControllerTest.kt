@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L1 common-controller slices of shared-food-source-continuity-v1, not whole-case passes.
 * Controlled session/read/stage replies do not prove renewal, authority, durable offers or RPC.
 * The production offer form has no typed reason field: that frozen input is not substituted.
 */
class FoodSourceContinuityControllerTest {
    @Test
    fun sfc02FreshChainRestoresOriginalLaterPageRecipientOnlyAfterTerminal() = runTest {
        withFoodController { fixture ->
            val original = fixture.selectOriginalLaterPageRecipient()
            val fresh = ControlledFoodPort()
            val freshFirst = ROOT
            val freshEmpty = ROOT.copy(after = original.recipientId)
            val freshLast = ROOT.copy(after = DISABLED_ACCOUNT)
            val firstReply = fresh.expect(freshFirst)
            val emptyReply = fresh.expect(freshEmpty)
            val lastReply = fresh.expect(freshLast)
            val controller = fixture.replaceAttachment(fresh)

            assertNotNull(controller.state.value.formInput)
            assertNull(controller.state.value.form)
            assertTrue(fresh.reads.isEmpty(), "Attachment replacement must not submit or reuse discovery")
            fixture.tryOldConfirmation(controller)
            fresh.assertNoCommands()
            fixture.originalPort.assertNoCommands()

            controller.reviewAttachment(fixture.runtime.navigator.token())
            runCurrent()
            fixture.originalPort.assertNoCommands()
            assertEquals<List<LocalFoodUiQuery?>>(listOf(freshFirst), fresh.reads)
            fixture.assertPendingInput(controller, original)

            // P was first seen on original page 2, but is on fresh page 1. No order is assumed.
            firstReply.complete(recipientPage(freshFirst,
                listOf(LocalFoodUiRecipient(original.recipientId, RENAMED_LABEL)), freshEmpty, 11))
            runCurrent()
            fixture.assertPendingInput(controller, original)
            fixture.tryOldConfirmation(controller)
            fresh.assertNoCommands()
            fixture.originalPort.assertNoCommands()
            assertEquals<List<LocalFoodUiQuery?>>(listOf(freshFirst, freshEmpty), fresh.reads)

            // An empty continuing page is not terminal and must not restore an editable form.
            emptyReply.complete(recipientPage(freshEmpty, emptyList(), freshLast, 11))
            runCurrent()
            fixture.assertPendingInput(controller, original)
            fresh.assertNoCommands()
            fixture.originalPort.assertNoCommands()
            assertEquals<List<LocalFoodUiQuery?>>(listOf(freshFirst, freshEmpty, freshLast), fresh.reads)

            val remaining = ELIGIBLE.filterNot { it.actorId == original.recipientId }
            lastReply.complete(recipientPage(freshLast, remaining, null, 11))
            runCurrent()
            fresh.assertReadPlanConsumed()
            assertFalse(controller.state.value.attachmentReviewRequired)
            assertNull(controller.state.value.formInput)
            val rebound = assertNotNull(controller.state.value.form)
            assertEquals(original.recipientId.value, rebound.values.getValue(LocalField.RECIPIENT_ID).text)
            assertEquals(original.handoffId.value, rebound.values.getValue(LocalField.HANDOFF_ID).text)
            assertEquals(ORDER.value, rebound.values.getValue(LocalField.ORDER_ID).text)
            assertEquals("7", rebound.values.getValue(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).text)
            assertTrue(rebound.descriptor.title.english.contains(RENAMED_LABEL))
            assertFalse(rebound.descriptor.title.english.contains(original.originalLabel))
            assertFalse(rebound.confirmed, "Earlier confirmation cannot authorize the rebound source")
            fresh.assertNoCommands()
            fixture.originalPort.assertNoCommands()

            controller.stage(fixture.runtime.navigator.token())
            runCurrent()
            fresh.assertNoCommands()
            fixture.originalPort.assertNoCommands()
            controller.confirmForm(true, fixture.runtime.navigator.token())
            controller.stage(fixture.runtime.navigator.token())
            runCurrent()
            fixture.originalPort.assertNoCommands()
            val intent = fresh.stages.single()
            assertEquals(LocalAction.FOOD_OFFER_HANDOFF, intent.action)
            assertEquals(LocalScalarValue.Identifier(original.recipientId),
                intent.values.single { it.field == LocalField.RECIPIENT_ID }.value)
            assertEquals(LocalScalarValue.Identifier(original.handoffId),
                intent.values.single { it.field == LocalField.HANDOFF_ID }.value)
            assertEquals(LocalScalarValue.Identifier(ORDER),
                intent.values.single { it.field == LocalField.ORDER_ID }.value)
            assertEquals(LocalScalarValue.Integer(7),
                intent.values.single { it.field == LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION }.value)
            assertTrue(fresh.submissions.isEmpty(), "Saving the client intent is not an offer submission")
            controller.submit(intent.operationId, fixture.runtime.navigator.token())
            runCurrent()
            fixture.originalPort.assertNoCommands()
            assertEquals(listOf(intent.operationId), fresh.submissions)
            assertEquals(1, fresh.stages.size)
            // The controlled port returns Unresolved; no stored offer or owner transition is claimed.
            assertTrue(controller.state.value.operation is LocalOperationResult.Unresolved)
        }
    }

    @Test
    fun sfc04MissingRecipientOrSupplementalRefusalCannotRestoreOldProposal() = runTest {
        for (refused in listOf(false, true)) {
            withFoodController { fixture ->
                val original = fixture.selectOriginalLaterPageRecipient()
                val fresh = ControlledFoodPort()
                val next = ROOT.copy(after = ELIGIBLE.first().actorId)
                val rootReply = fresh.expect(ROOT)
                val finalReply = fresh.expect(next)
                val controller = fixture.replaceAttachment(fresh)
                controller.reviewAttachment(fixture.runtime.navigator.token())
                runCurrent()
                fixture.originalPort.assertNoCommands()
                fixture.assertPendingInput(controller, original)

                val firstItems = if (refused) listOf(LocalFoodUiRecipient(original.recipientId, RENAMED_LABEL))
                    else ELIGIBLE.filterNot { it.actorId == original.recipientId }
                rootReply.complete(recipientPage(ROOT, firstItems, next, 11))
                runCurrent()
                fixture.assertPendingInput(controller, original)
                fixture.tryOldConfirmation(controller)
                fresh.assertNoCommands()
                fixture.originalPort.assertNoCommands()
                if (refused) finalReply.complete(LocalReadResult.Unavailable(LocalServiceProblem.DENIED))
                else finalReply.complete(recipientPage(next,
                    listOf(LocalFoodUiRecipient(EQUAL_LABEL_OTHER, original.originalLabel)), null, 11))
                runCurrent()
                fresh.assertReadPlanConsumed()
                assertNull(controller.state.value.form)
                assertNotNull(controller.state.value.readProblem, "Missing/refused P requires explicit resolution")
                if (refused) assertNull(controller.state.value.page, "Refusal must retire inaccessible source details")
                // Incompatible/inaccessible unstaged checkpoint retention is intentionally not asserted.
                fixture.tryOldConfirmation(controller)
                fresh.assertNoCommands()
                fixture.originalPort.assertNoCommands()
            }
        }
    }
}

private val COMPANY = DomainId("a0000000-0000-4000-8000-000000000002")
private val BRANCH = DomainId("a0000000-0000-4000-8000-000000000004")
private val ACTOR = DomainId("b0000000-0000-4000-8000-000000000001")
private val ORDER = DomainId("f0000000-0000-4000-8000-000000000003")
private val DISABLED_ACCOUNT = DomainId("c2000000-0000-4000-8000-000000000001")
private val EQUAL_LABEL_OTHER = DomainId("b0000000-0000-4000-8000-000000000002")
private val ROOT = LocalFoodUiQuery.Recipients(ORDER, Revision(7))
private const val RENAMED_LABEL = "Recipient renamed / المستلم باسم جديد"
private val ELIGIBLE = (1..21).map { index ->
    LocalFoodUiRecipient(DomainId("c1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
        "Recipient ${index.toString().padStart(2, '0')} / مستلم ${index.toString().padStart(2, '0')}")
}

private fun recipientPage(query: LocalFoodUiQuery.Recipients, items: List<LocalFoodUiRecipient>,
    next: LocalFoodUiQuery.Recipients?, branchRevision: Long): LocalReadResult = LocalReadResult.Available(
    LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), listOf(offerForm(query)), food = LocalFoodUiPage(
        query, COMPANY, BRANCH, ACTOR, Revision(branchRevision), UtcInstant.parseCanonical("2026-10-30T09:00:00Z"),
        LocalFoodUiBody.Recipients(query.orderId, query.expectedOrderStorageRevision, items), next)))

/** Shape of the current uncollected production offer form; no invented reason or financial fields. */
private fun offerForm(query: LocalFoodUiQuery.Recipients): LocalActionForm {
    fun identifier(field: LocalField, id: DomainId? = null) = LocalFormField.Scalar(field,
        LocalText(field.name, field.name), LocalInputKind.IDENTIFIER, maximumCharacters = 36,
        initialValue = id?.let(LocalScalarValue::Identifier), readOnly = true)
    return LocalActionForm(LocalAction.FOOD_OFFER_HANDOFF, 1,
        LocalText("Choose an eligible recipient", "اختيار مستلم مؤهل"), listOf(
            identifier(LocalField.ORDER_ID, query.orderId),
            LocalFormField.Scalar(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
                LocalText("Expected order revision", "مراجعة الطلب المتوقعة"), LocalInputKind.INTEGER,
                maximumCharacters = 19, initialValue = LocalScalarValue.Integer(query.expectedOrderStorageRevision.value), readOnly = true),
            identifier(LocalField.HANDOFF_ID), identifier(LocalField.RECIPIENT_ID)),
        confirmation = LocalText("Review the original proposal", "مراجعة الاقتراح الأصلي"))
}

private class ControlledFoodPort : LocalWorkflowGateway {
    private data class ReadStep(val query: LocalFoodUiQuery.Recipients, val reply: CompletableDeferred<LocalReadResult>)
    private val steps = ArrayDeque<ReadStep>()
    private val planned = mutableListOf<LocalFoodUiQuery.Recipients>()
    val reads = mutableListOf<LocalFoodUiQuery?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    private val lookups = mutableListOf<OperationId>()
    fun expect(query: LocalFoodUiQuery.Recipients): CompletableDeferred<LocalReadResult> =
        CompletableDeferred<LocalReadResult>().also { steps.addLast(ReadStep(query, it)); planned += query }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query.food
        assertEquals(LocalWorkflow.FOOD, query.workflow)
        assertTrue(steps.isNotEmpty(), "Unexpected controller read")
        val next = steps.removeFirst()
        assertEquals(next.query, query.food)
        return next.reply.await()
    }
    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        // Controlled L1 reply only: this is not a production durable-staging implementation.
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
    fun assertNoCommands() { assertTrue(stages.isEmpty()); assertTrue(submissions.isEmpty()); assertTrue(lookups.isEmpty()) }
    fun assertReadPlanConsumed() { assertTrue(steps.isEmpty()); assertEquals<List<LocalFoodUiQuery?>>(planned, reads) }
}

private class ControlledFoodSession(initialPort: ControlledFoodPort) : BranchUiSession {
    override val display = SessionDisplayContext(ACTOR, COMPANY, BRANCH, "Owner A", "Food company", "Food branch",
        setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initialPort
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(ACTOR, 1))
    private var active = true
    private var replacement: ControlledFoodPort? = null
    fun replaceWith(port: ControlledFoodPort) { replacement = port }
    override fun isCurrent(): Boolean = active
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(ACTOR, assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override fun close() { active = false }
}

private data class RecipientCheckpoint(val recipientId: DomainId, val handoffId: DomainId, val originalLabel: String)
private class FoodControllerFixture(val runtime: UkkeraRuntime, val session: ControlledFoodSession,
    private val model: EntryViewModel, val originalPort: ControlledFoodPort, val scope: TestScope) {
    private var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
    fun start() { controller.activate(LocalWorkflow.FOOD, food = ROOT) }
    fun selectOriginalLaterPageRecipient(): RecipientCheckpoint {
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
        assertEquals(ELIGIBLE.map { it.actorId }.toSet(), union.map { it.actorId }.toSet())
        assertEquals(21, union.size)
        assertEquals(21, union.map { it.actorId }.distinct().size)
        val p = lastItems.first { later -> firstItems.none { it.actorId == later.actorId } }
        controller.chooseFoodRecipient(p.actorId, runtime.navigator.token())
        controller.confirmForm(true, runtime.navigator.token()) // A prior confirmation, never staged.
        val original = assertNotNull(controller.state.value.form)
        assertEquals(p.actorId.value, original.values.getValue(LocalField.RECIPIENT_ID).text)
        assertTrue(original.confirmed)
        originalPort.assertReadPlanConsumed()
        originalPort.assertNoCommands()
        return RecipientCheckpoint(p.actorId, DomainId(original.values.getValue(LocalField.HANDOFF_ID).text), p.label)
    }
    fun replaceAttachment(port: ControlledFoodPort): LocalWorkflowController {
        session.replaceWith(port)
        runtime.refreshLocalSession(runtime.navigator.token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        controller.activate(LocalWorkflow.FOOD, food = ROOT)
        assertTrue(controller.state.value.attachmentReviewRequired)
        originalPort.assertNoCommands()
        return controller
    }
    fun assertPendingInput(current: LocalWorkflowController, original: RecipientCheckpoint) {
        assertNull(current.state.value.form)
        val input = assertNotNull(current.state.value.formInput)
        assertEquals(original.recipientId.value, input.values.getValue(LocalField.RECIPIENT_ID).text)
        assertEquals(original.handoffId.value, input.values.getValue(LocalField.HANDOFF_ID).text)
    }
    fun tryOldConfirmation(current: LocalWorkflowController) {
        current.confirmForm(true, runtime.navigator.token())
        current.stage(runtime.navigator.token())
        scope.runCurrent()
    }
}

private suspend fun TestScope.withFoodController(block: suspend (FoodControllerFixture) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val originalPort = ControlledFoodPort()
    val next = ROOT.copy(after = ELIGIBLE[19].actorId)
    originalPort.expect(ROOT).complete(recipientPage(ROOT, ELIGIBLE.take(20), next, 10))
    originalPort.expect(next).complete(recipientPage(next, ELIGIBLE.drop(20), null, 10))
    val session = ControlledFoodSession(originalPort)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
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
        val model = provider["entry-${entry.id}", EntryViewModel::class]
        val fixture = FoodControllerFixture(runtime, session, model, originalPort, this)
        fixture.start()
        block(fixture)
    } finally {
        try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

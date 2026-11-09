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
import com.ukkera.pos.types.Quantity
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
 * Bounded L1 MENU slices of frozen shared-food-source-continuity-v1 SFC-03/SFC-04.
 * FOOD_CREATE binds metadata, selected identities and both exact notes through the normal editor.
 * Its original generated order/serving identities are fixed before attachment replacement.
 *
 * Native identity binding: product -> output SKU; variant -> recipe; cheese option -> complete
 * choice and its one modifier. Each complete choice name joins the frozen product/variant/option
 * labels after separating their English/Arabic parts. No lookalike identity is interchangeable.
 *
 * The existing revision-7 empty draft/Add body, editable quantity 2 in one saved line, price
 * revisions, 22,000/28,000 amounts and durable effects are UNBOUND here. A configured serving
 * row is not a replacement oracle for those requirements. Controlled catalog generations 5/6
 * are native read bindings, not proof of base-price revisions or catalog/policy activation.
 * Controlled session/read/stage replies do not prove authority, renewal, persistence or RPC.
 * Every complete case remains NOT_RUN; these methods can establish only their named slices.
 */
class FoodMenuCurrentSourceRestorationTest {
    @Test
    fun sfc03FreshMenuRestoresSelectedIdsAndBothNotesThenStagesCurrentMetadata() = runTest {
        withMenuController { fixture ->
            val original = fixture.selectOriginalMenuInput()
            val fresh = ControlledMenuPort()
            val reply = fresh.expect(MENU_QUERY)
            val controller = fixture.replaceAttachment(fresh)

            fixture.assertPendingInput(controller, original)
            assertNull(controller.state.value.page, "Old protected menu details must be retired")
            assertTrue(fresh.reads.isEmpty())
            fixture.tryOldConfirmation(controller)
            fresh.assertNoCommands()
            fixture.originalPort.assertNoCommands()

            controller.reviewAttachment(fixture.runtime.navigator.token())
            runCurrent()
            assertEquals<List<LocalFoodUiQuery?>>(listOf(MENU_QUERY), fresh.reads)
            fixture.assertPendingInput(controller, original)
            fixture.tryOldConfirmation(controller)
            fresh.assertNoCommands()
            fixture.originalPort.assertNoCommands()

            reply.complete(menuPage(CURRENT_CATALOG, listOf(originalRecipe(current = true)), Revision(11)))
            runCurrent()
            fresh.assertReadPlanConsumed()
            assertFalse(controller.state.value.attachmentReviewRequired)
            assertNull(controller.state.value.formInput)
            assertNull(controller.state.value.readProblem)
            val rebound = assertNotNull(controller.state.value.form)
            fixture.assertBoundSelection(rebound, original, current = true)
            assertFalse(rebound.confirmed, "Prior confirmation must not authorize the new source")
            val acceptedMenu = assertNotNull(controller.state.value.page?.food).body as LocalFoodUiBody.Menu
            assertEquals(CURRENT_CATALOG, acceptedMenu.catalogRevision)
            assertEquals(Revision(2), acceptedMenu.recipes.single().recipeRevision)
            assertEquals(currentChoiceNames(), acceptedMenu.recipes.single().choices.single().names)
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
            assertEquals(LocalAction.FOOD_CREATE, intent.action)
            assertEquals(LocalScalarValue.Identifier(original.orderId), intent.scalar(LocalField.ORDER_ID))
            assertEquals(LocalScalarValue.Integer(CURRENT_CATALOG.value), intent.scalar(LocalField.EXPECTED_CATALOG_REVISION))
            assertEquals(LocalScalarValue.Choice("RESTAURANT"), intent.scalar(LocalField.ACTIVITY))
            assertEquals(LocalScalarValue.Choice("DINE_IN"), intent.scalar(LocalField.CHANNEL))
            assertEquals(LocalScalarValue.Text("T-02"), intent.scalar(LocalField.SERVICE_REFERENCE))
            assertEquals(LocalScalarValue.Text(MENU_SEAT_NOTE), intent.scalar(LocalField.SEAT_NOTE))
            val serving = intent.lines.single()
            assertEquals(original.servingId, serving.lineId)
            assertEquals(LocalScalarValue.Identifier(MENU_PRODUCT), serving.scalar(LocalField.SKU_ID))
            assertEquals(LocalScalarValue.Identifier(MENU_VARIANT), serving.scalar(LocalField.RECIPE_ID))
            assertEquals(LocalScalarValue.Identifier(MENU_CHEESE_OPTION), serving.scalar(LocalField.CHOICE_ID))
            assertEquals(LocalScalarValue.Text(MENU_CHEESE_OPTION.value), serving.scalar(LocalField.MODIFIER_IDS))
            assertEquals(LocalScalarValue.Integer(2), serving.scalar(LocalField.EXPECTED_FOOD_RECIPE_REVISION))
            assertEquals(LocalScalarValue.Text(currentChoiceNames().english), serving.scalar(LocalField.NAME_ENGLISH))
            assertEquals(LocalScalarValue.Text(currentChoiceNames().arabic), serving.scalar(LocalField.NAME_ARABIC))
            assertEquals(LocalScalarValue.Text(MENU_ITEM_NOTE), serving.scalar(LocalField.ITEM_NOTE))
            assertTrue(fresh.submissions.isEmpty(), "Explicit client staging must not automatically submit")
            assertTrue(fresh.lookups.isEmpty())
            // This is one captured client intent, not a saved line or a price/effect observation.
        }
    }

    @Test
    fun sfc04UnavailableOriginalVariantDoesNotAdoptEqualLabelLookalike() = runTest {
        withMenuController { fixture ->
            fixture.assertMenuSourceBlocked(listOf(lookalikeRecipe()))
        }
    }

    @Test
    fun sfc04RemovedCheeseOptionDoesNotAdoptPlainVariantOrEqualLabelOtherOption() = runTest {
        withMenuController { fixture ->
            // The original variant still has its base Standard choice, but its selected cheese
            // option is gone. The different product/variant still supplies same-label cheese.
            fixture.assertMenuSourceBlocked(listOf(originalRecipeWithoutCheese(), lookalikeRecipe()))
        }
    }
}

private val MENU_COMPANY = DomainId("a0000000-0000-4000-8000-000000000002")
private val MENU_BRANCH = DomainId("a0000000-0000-4000-8000-000000000004")
private val MENU_ACTOR = DomainId("b0000000-0000-4000-8000-000000000001")
private val MENU_PRODUCT = DomainId("f1000000-0000-4000-8000-000000000001")
private val MENU_VARIANT = DomainId("f1000000-0000-4000-8000-000000000002")
private val MENU_CHEESE_OPTION = DomainId("f1000000-0000-4000-8000-000000000003")
private val LOOKALIKE_PRODUCT = DomainId("f1000000-0000-4000-8000-000000000004")
private val LOOKALIKE_VARIANT = DomainId("f1000000-0000-4000-8000-000000000005")
private val LOOKALIKE_CHEESE_OPTION = DomainId("f1000000-0000-4000-8000-000000000006")
// Native binding for the frozen semantic unit "each"; no unit identity oracle was specified.
private val MENU_EACH_UNIT = DomainId("f6000000-0000-4000-8000-000000000001")
private val MENU_QUERY = LocalFoodUiQuery.Menu()
private val INITIAL_CATALOG = Revision(5)
private val CURRENT_CATALOG = Revision(6)
private const val MENU_ITEM_NOTE = "no salt / بدون ملح"
private const val MENU_SEAT_NOTE = "Seat 02 / المقعد 02"

private fun frozenChoiceNames(productLabel: String, withCheese: Boolean = true): LocalText {
    val labels = listOf(productLabel, "Standard / عادي") + if (withCheese) listOf("Cheese / جبن") else emptyList()
    return LocalText(labels.joinToString(" · ") { it.substringBefore(" / ") },
        labels.joinToString(" · ") { it.substringAfter(" / ") })
}

private fun initialChoiceNames(): LocalText = frozenChoiceNames("Burger / برجر")
private fun currentChoiceNames(): LocalText = frozenChoiceNames("Burger today / برجر اليوم")

private fun originalRecipe(current: Boolean = false): LocalFoodUiRecipe = LocalFoodUiRecipe(
    MENU_VARIANT, Revision(if (current) 2 else 1), MENU_PRODUCT, Quantity(MENU_EACH_UNIT, 1, 0), true,
    listOf(LocalFoodUiChoice(MENU_CHEESE_OPTION, if (current) currentChoiceNames() else initialChoiceNames(),
        listOf(MENU_CHEESE_OPTION))))

private fun originalRecipeWithoutCheese(): LocalFoodUiRecipe = originalRecipe().copy(choices = listOf(
    // The existing variant ID binds the complete unmodified Standard choice; it is not cheese.
    LocalFoodUiChoice(MENU_VARIANT, frozenChoiceNames("Burger / برجر", withCheese = false), emptyList())))

private fun lookalikeRecipe(): LocalFoodUiRecipe = LocalFoodUiRecipe(
    LOOKALIKE_VARIANT, Revision(1), LOOKALIKE_PRODUCT, Quantity(MENU_EACH_UNIT, 1, 0), true,
    listOf(LocalFoodUiChoice(LOOKALIKE_CHEESE_OPTION, initialChoiceNames(), listOf(LOOKALIKE_CHEESE_OPTION))))

private fun menuPage(catalog: Revision, recipes: List<LocalFoodUiRecipe>, branchRevision: Revision): LocalReadResult =
    LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), listOf(menuCreateForm(catalog)),
        food = LocalFoodUiPage(MENU_QUERY, MENU_COMPANY, MENU_BRANCH, MENU_ACTOR, branchRevision,
            UtcInstant.parseCanonical("2026-10-30T09:00:00Z"), LocalFoodUiBody.Menu(catalog, recipes))))

/** Current production FOOD_CREATE read descriptor; the normal menu selection adds its columns. */
private fun menuCreateForm(catalog: Revision): LocalActionForm {
    fun choice(field: LocalField, choices: List<LocalChoice>) = LocalFormField.Scalar(field,
        LocalText(field.name, field.name), LocalInputKind.CHOICE, maximumCharacters = 256, choices = choices)
    return LocalActionForm(LocalAction.FOOD_CREATE, 1, LocalText("Create food check", "إنشاء طلب طعام"), listOf(
        LocalFormField.Scalar(LocalField.ORDER_ID, LocalText("Order ID", "معرف الطلب"), LocalInputKind.IDENTIFIER,
            maximumCharacters = 36, readOnly = true),
        choice(LocalField.ACTIVITY, listOf(LocalChoice("RESTAURANT", LocalText("Restaurant", "مطعم")),
            LocalChoice("CAFE", LocalText("Café", "مقهى")))),
        choice(LocalField.CHANNEL, listOf(LocalChoice("DINE_IN", LocalText("Dine-in", "داخل المكان")),
            LocalChoice("COUNTER", LocalText("Counter", "الكاونتر")), LocalChoice("TAKEAWAY", LocalText("Takeaway", "سفري")))),
        LocalFormField.Scalar(LocalField.SERVICE_REFERENCE, LocalText("Service reference", "مرجع الخدمة"),
            LocalInputKind.TEXT, maximumCharacters = 80),
        LocalFormField.Scalar(LocalField.SEAT_NOTE, LocalText("Seat note", "ملاحظة المقعد"),
            LocalInputKind.TEXT, required = false, maximumCharacters = 160),
        LocalFormField.Scalar(LocalField.EXPECTED_CATALOG_REVISION, LocalText("Catalog revision", "مراجعة الكتالوج"),
            LocalInputKind.INTEGER, maximumCharacters = 19, initialValue = LocalScalarValue.Integer(catalog.value), readOnly = true)),
        confirmation = LocalText("I reviewed the exact identifiers, amounts and evidence. Save this immutable intent for separate submission.",
            "راجعت المعرفات والمبالغ والأدلة الدقيقة. احفظ هذا الطلب الثابت لإرساله في خطوة منفصلة."))
}

private fun LocalCommandIntent.scalar(field: LocalField): LocalScalarValue = values.single { it.field == field }.value
private fun LocalInputLine.scalar(field: LocalField): LocalScalarValue = values.single { it.field == field }.value

private class ControlledMenuPort : LocalWorkflowGateway {
    private data class ReadStep(val query: LocalFoodUiQuery.Menu, val reply: CompletableDeferred<LocalReadResult>)
    private val steps = ArrayDeque<ReadStep>()
    private val planned = mutableListOf<LocalFoodUiQuery.Menu>()
    val reads = mutableListOf<LocalFoodUiQuery?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    fun expect(query: LocalFoodUiQuery.Menu): CompletableDeferred<LocalReadResult> =
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
        assertTrue(stages.isEmpty())
        assertTrue(submissions.isEmpty())
        assertTrue(lookups.isEmpty())
    }
    fun assertReadPlanConsumed() {
        assertTrue(steps.isEmpty())
        assertEquals<List<LocalFoodUiQuery?>>(planned, reads)
    }
}

private class ControlledMenuSession(initialPort: ControlledMenuPort) : BranchUiSession {
    override val display = SessionDisplayContext(MENU_ACTOR, MENU_COMPANY, MENU_BRANCH, "Owner A", "Food company", "Food branch",
        setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initialPort
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(MENU_ACTOR, 1))
    private var active = true
    private var replacement: ControlledMenuPort? = null
    fun replaceWith(port: ControlledMenuPort) { replacement = port }
    override fun isCurrent(): Boolean = active
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(MENU_ACTOR, assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override fun close() { active = false }
}

private data class MenuCheckpoint(val orderId: DomainId, val servingId: DomainId)

private class MenuControllerFixture(val runtime: UkkeraRuntime, private val session: ControlledMenuSession,
    private val model: EntryViewModel, val originalPort: ControlledMenuPort, private val scope: TestScope) {
    private var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
    fun start() { controller.activate(LocalWorkflow.FOOD, food = MENU_QUERY) }
    fun selectOriginalMenuInput(): MenuCheckpoint {
        scope.runCurrent()
        controller.chooseFood(MENU_VARIANT, MENU_CHEESE_OPTION, runtime.navigator.token())
        val servingId = assertNotNull(controller.state.value.form).lines.single().lineId
        controller.edit(LocalField.ACTIVITY, LocalDraftValue(selected = LocalScalarValue.Choice("RESTAURANT")), runtime.navigator.token())
        controller.edit(LocalField.CHANNEL, LocalDraftValue(selected = LocalScalarValue.Choice("DINE_IN")), runtime.navigator.token())
        controller.edit(LocalField.SERVICE_REFERENCE, LocalDraftValue("T-02"), runtime.navigator.token())
        controller.edit(LocalField.SEAT_NOTE, LocalDraftValue(MENU_SEAT_NOTE), runtime.navigator.token())
        controller.edit(LocalField.ITEM_NOTE, LocalDraftValue(MENU_ITEM_NOTE), runtime.navigator.token(), servingId)
        controller.confirmForm(true, runtime.navigator.token()) // Prior confirmation, never staged.
        val form = assertNotNull(controller.state.value.form)
        val original = MenuCheckpoint(DomainId(form.values.getValue(LocalField.ORDER_ID).text), servingId)
        assertBoundSelection(form, original, current = false)
        assertTrue(form.confirmed)
        originalPort.assertReadPlanConsumed()
        originalPort.assertNoCommands()
        return original
    }
    fun replaceAttachment(port: ControlledMenuPort): LocalWorkflowController {
        session.replaceWith(port)
        runtime.refreshLocalSession(runtime.navigator.token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        controller.activate(LocalWorkflow.FOOD, food = MENU_QUERY)
        assertTrue(controller.state.value.attachmentReviewRequired)
        originalPort.assertNoCommands()
        return controller
    }
    fun assertPendingInput(current: LocalWorkflowController, original: MenuCheckpoint) {
        assertNull(current.state.value.form)
        val input = assertNotNull(current.state.value.formInput)
        assertEquals(original.orderId.value, input.values.getValue(LocalField.ORDER_ID).text)
        assertEquals(MENU_SEAT_NOTE, input.values.getValue(LocalField.SEAT_NOTE).text)
        val serving = input.lines.single()
        assertEquals(original.servingId, serving.lineId)
        assertEquals(MENU_VARIANT.value, serving.values.getValue(LocalField.RECIPE_ID).text)
        assertEquals(MENU_CHEESE_OPTION.value, serving.values.getValue(LocalField.CHOICE_ID).text)
        assertEquals(MENU_ITEM_NOTE, serving.values.getValue(LocalField.ITEM_NOTE).text)
        val selection = assertNotNull(serving.foodMenuSelection)
        assertEquals(MENU_PRODUCT, selection.outputSkuId)
        assertEquals(listOf(MENU_CHEESE_OPTION), selection.modifierIds)
        assertFalse(serving.values.containsKey(LocalField.NAME_ENGLISH))
        assertFalse(serving.values.containsKey(LocalField.NAME_ARABIC))
    }
    fun assertBoundSelection(form: LocalFormDraft, original: MenuCheckpoint, current: Boolean) {
        assertEquals(LocalAction.FOOD_CREATE, form.descriptor.action)
        assertEquals(original.orderId.value, form.values.getValue(LocalField.ORDER_ID).text)
        assertEquals(MENU_SEAT_NOTE, form.values.getValue(LocalField.SEAT_NOTE).text)
        assertEquals((if (current) CURRENT_CATALOG else INITIAL_CATALOG).value.toString(),
            form.values.getValue(LocalField.EXPECTED_CATALOG_REVISION).text)
        val serving = form.lines.single()
        assertEquals(original.servingId, serving.lineId)
        assertEquals(MENU_PRODUCT.value, serving.values.getValue(LocalField.SKU_ID).text)
        assertEquals(MENU_VARIANT.value, serving.values.getValue(LocalField.RECIPE_ID).text)
        assertEquals(MENU_CHEESE_OPTION.value, serving.values.getValue(LocalField.CHOICE_ID).text)
        assertEquals(MENU_CHEESE_OPTION.value, serving.values.getValue(LocalField.MODIFIER_IDS).text)
        assertEquals(if (current) "2" else "1", serving.values.getValue(LocalField.EXPECTED_FOOD_RECIPE_REVISION).text)
        val names = if (current) currentChoiceNames() else initialChoiceNames()
        assertEquals(names.english, serving.values.getValue(LocalField.NAME_ENGLISH).text)
        assertEquals(names.arabic, serving.values.getValue(LocalField.NAME_ARABIC).text)
        assertEquals(MENU_ITEM_NOTE, serving.values.getValue(LocalField.ITEM_NOTE).text)
    }
    fun tryOldConfirmation(current: LocalWorkflowController) {
        current.confirmForm(true, runtime.navigator.token())
        current.stage(runtime.navigator.token())
        scope.runCurrent()
    }
    fun assertMenuSourceBlocked(recipes: List<LocalFoodUiRecipe>) {
        val original = selectOriginalMenuInput()
        val fresh = ControlledMenuPort()
        val reply = fresh.expect(MENU_QUERY)
        val current = replaceAttachment(fresh)
        assertPendingInput(current, original)
        tryOldConfirmation(current)
        fresh.assertNoCommands()
        originalPort.assertNoCommands()
        current.reviewAttachment(runtime.navigator.token())
        scope.runCurrent()
        assertPendingInput(current, original)
        reply.complete(menuPage(CURRENT_CATALOG, recipes, Revision(11)))
        scope.runCurrent()
        fresh.assertReadPlanConsumed()
        assertNull(current.state.value.form, "An unavailable original selection cannot become an alternate line")
        assertNotNull(current.state.value.readProblem, "The original source requires an explicit unavailable/resolution state")
        fresh.assertNoCommands()
        originalPort.assertNoCommands()
        // BG-06 leaves incompatible unstaged checkpoint retention/display unbound.
        tryOldConfirmation(current)
        assertNull(current.state.value.form)
        fresh.assertNoCommands()
        originalPort.assertNoCommands()
    }
}

private suspend fun TestScope.withMenuController(block: suspend (MenuControllerFixture) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val originalPort = ControlledMenuPort()
    originalPort.expect(MENU_QUERY).complete(menuPage(INITIAL_CATALOG, listOf(originalRecipe()), Revision(10)))
    val session = ControlledMenuSession(originalPort)
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
        val fixture = MenuControllerFixture(runtime, session, model, originalPort, this)
        fixture.start()
        block(fixture)
    } finally {
        try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

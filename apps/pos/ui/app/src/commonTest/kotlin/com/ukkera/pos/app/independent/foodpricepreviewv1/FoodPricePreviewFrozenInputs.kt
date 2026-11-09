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
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen synthetic inputs; the unit ID binds the supplied "1 burger" recipe output. */
internal object FoodPricePreviewFrozenInputs {
    val company = DomainId("a0000000-0000-4000-8000-000000000002")
    val branch = DomainId("a0000000-0000-4000-8000-000000000004")
    val actor = DomainId("b0000000-0000-4000-8000-000000000001")
    val order = DomainId("f0000000-0000-4000-8000-000000000001")
    val product = DomainId("f1000000-0000-4000-8000-000000000001")
    val variant = DomainId("f1000000-0000-4000-8000-000000000002")
    val cheese = DomainId("f1000000-0000-4000-8000-000000000003")
    val unit = DomainId("f6000000-0000-4000-8000-000000000001")
    val lookalikeOrder = DomainId("f0000000-0000-4000-8000-000000000002")
    val lookalikeProduct = DomainId("f1000000-0000-4000-8000-000000000004")
    val lookalikeVariant = DomainId("f1000000-0000-4000-8000-000000000005")
    val lookalikeOption = DomainId("f1000000-0000-4000-8000-000000000006")
    const val itemNote = "no salt / بدون ملح"
    const val seatNote = "Seat 02 / المقعد 02"
    val menu = LocalFoodUiQuery.Menu(orderId = order, expectedOrderStorageRevision = Revision(7))
}

/**
 * Literal independent oracles from food-pre-save-preview-v1 cases/fixtures.
 * The controlled reply builder never calculates an expected amount from implementation output.
 */
internal data class FoodPricePreviewFrozenExpected(
    val servingQuantity: Long,
    val unitMinor: Long,
    val lineMinor: Long,
    val orderMinor: Long,
    val includesTax: Boolean,
    val netMinor: Long,
    val taxMinor: Long,
) {
    companion object {
        val originalOne = FoodPricePreviewFrozenExpected(1, 11000, 11000, 11000, true, 11000, 0)
        val originalTwo = FoodPricePreviewFrozenExpected(2, 11000, 22000, 22000, true, 22000, 0)
        val currentTwo = FoodPricePreviewFrozenExpected(2, 14000, 28000, 28000, true, 28000, 0)
        val tierOne = FoodPricePreviewFrozenExpected(1, 11000, 11000, 11000, true, 11000, 0)
        val tierTwo = FoodPricePreviewFrozenExpected(2, 10000, 20000, 20000, true, 20000, 0)
        val inclusive14 = FoodPricePreviewFrozenExpected(2, 11400, 22800, 22800, true, 20000, 2800)
        val exclusive14 = FoodPricePreviewFrozenExpected(2, 10000, 22800, 22800, false, 20000, 2800)
        // LINE-TAX-HALF-UP is deliberately unbound until its declared public profile exists.
    }
}

/** EGP's frozen amounts are minor units; UnitPrice accepts exact major units. */
internal fun foodPricePreviewFrozenUnitPrice(unitMinor: Long): UnitPrice {
    require(unitMinor >= 0)
    val major = "${unitMinor / 100}.${(unitMinor % 100).toString().padStart(2, '0')}"
    return UnitPrice.parse(Currency.EGP, major)
}

internal fun foodPricePreviewFrozenReply(
    input: LocalFoodPreviewInput,
    expected: FoodPricePreviewFrozenExpected,
): LocalFoodPricePreviewResult.Available {
    fun money(minor: Long) = Money(Currency.EGP, minor)
    val line = LocalFoodPricePreviewLine(input.lines.single().lineId,
        Quantity(FoodPricePreviewFrozenInputs.unit, expected.servingQuantity, 0),
        foodPricePreviewFrozenUnitPrice(expected.unitMinor), money(expected.lineMinor),
        expected.includesTax, money(expected.netMinor), money(expected.taxMinor))
    return LocalFoodPricePreviewResult.Available(LocalFoodPricePreview(input.snapshot(), listOf(line),
        money(expected.orderMinor), money(expected.orderMinor), money(0), money(expected.taxMinor),
        money(0), money(expected.netMinor)))
}

/** A saved coroutine schedule for a controlled UI port, not a native/RPC response barrier. */
internal class FoodPricePreviewFrozenHeldReply<T> {
    val requested = CompletableDeferred<Unit>()
    val returned = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<T>()
    suspend fun awaitReply(): T = withContext(NonCancellable) {
        requested.complete(Unit)
        reply.await().also { returned.complete(Unit) }
    }
}

/**
 * Presentation-only port. Business entry points record accidental dispatch and refuse it.
 * There is no simulated authoritative Master, durable commit or synthetic business success.
 */
internal class FoodPricePreviewFrozenPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalFoodUiQuery>()
    val previews = mutableListOf<LocalFoodPreviewInput>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    var catalogRevision = Revision(5)
    var policyRevision = Revision(10)
    var recipeRevision = Revision(1)
    var readProblem: LocalServiceProblem? = null
    var previewProblem: LocalServiceProblem? = null
    var cancelNextPreview = false
    var expected = FoodPricePreviewFrozenExpected.originalTwo
    var replyInput: (LocalFoodPreviewInput) -> LocalFoodPreviewInput = { it }
    private var nextRead: FoodPricePreviewFrozenHeldReply<LocalReadResult>? = null
    private var nextPreview: FoodPricePreviewFrozenHeldReply<LocalFoodPricePreviewResult>? = null
    private val heldReads = mutableListOf<FoodPricePreviewFrozenHeldReply<LocalReadResult>>()
    private val heldPreviews = mutableListOf<FoodPricePreviewFrozenHeldReply<LocalFoodPricePreviewResult>>()

    fun holdNextRead() = FoodPricePreviewFrozenHeldReply<LocalReadResult>().also {
        check(nextRead == null); nextRead = it; heldReads += it
    }
    fun holdNextPreview() = FoodPricePreviewFrozenHeldReply<LocalFoodPricePreviewResult>().also {
        check(nextPreview == null); nextPreview = it; heldPreviews += it
    }

    fun page(query: LocalFoodUiQuery): LocalReadResult {
        readProblem?.let { return LocalReadResult.Unavailable(it) }
        val ids = FoodPricePreviewFrozenInputs
        val body = when (query) {
            is LocalFoodUiQuery.Check -> LocalFoodUiBody.Check(
                LocalFoodUiCheck(query.orderId, ids.actor, Revision(7), "RESTAURANT",
                    LocalFoodUiService("DINE_IN", "T-02", ""), 0, null, null),
                emptyList(), Money(Currency.EGP, 0), false)
            is LocalFoodUiQuery.Menu -> LocalFoodUiBody.Menu(catalogRevision, listOf(
                LocalFoodUiRecipe(ids.variant, recipeRevision, ids.product, Quantity(ids.unit, 1, 0), true,
                    listOf(LocalFoodUiChoice(ids.cheese, LocalText("Cheese", "جبنة"), listOf(ids.cheese))))))
            else -> error("This frozen presentation fixture has no binding for $query")
        }
        return LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(),
            if (query is LocalFoodUiQuery.Menu) listOf(addForm(query)) else emptyList(),
            food = LocalFoodUiPage(query, ids.company, ids.branch, ids.actor, policyRevision,
                UtcInstant.parseCanonical("2026-10-30T09:00:00Z"), body)))
    }

    private fun addForm(query: LocalFoodUiQuery.Menu): LocalActionForm {
        fun fixed(field: LocalField, kind: LocalInputKind, value: LocalScalarValue, maximum: Int) =
            LocalFormField.Scalar(field, LocalText(field.name, field.name), kind,
                maximumCharacters = maximum, readOnly = true, initialValue = value)
        return LocalActionForm(LocalAction.FOOD_ADD, 1, LocalText("Add food", "إضافة طعام"), listOf(
            fixed(LocalField.ORDER_ID, LocalInputKind.IDENTIFIER,
                LocalScalarValue.Identifier(assertNotNull(query.orderId)), 36),
            fixed(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalInputKind.INTEGER,
                LocalScalarValue.Integer(assertNotNull(query.expectedOrderStorageRevision).value), 19),
            fixed(LocalField.EXPECTED_CATALOG_REVISION, LocalInputKind.INTEGER,
                LocalScalarValue.Integer(catalogRevision.value), 19)),
            confirmation = LocalText("Confirm the exact selection", "تأكيد الاختيار الدقيق"))
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        check(query.workflow == LocalWorkflow.FOOD)
        val selected = checkNotNull(query.food)
        reads += selected
        nextRead?.let { nextRead = null; return it.awaitReply() }
        return page(selected)
    }

    override suspend fun previewFood(input: LocalFoodPreviewInput): LocalFoodPricePreviewResult {
        previews += input.snapshot()
        nextPreview?.let { nextPreview = null; return it.awaitReply() }
        if (cancelNextPreview) {
            cancelNextPreview = false
            throw CancellationException("Canceled controlled preview query")
        }
        previewProblem?.let { return LocalFoodPricePreviewResult.Unavailable(it) }
        return foodPricePreviewFrozenReply(replyInput(input), expected)
    }

    override suspend fun pending(cursor: String?) = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.NotStaged(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    }

    fun assertNoBusinessCalls() {
        assertTrue(stages.isEmpty(), "Preview must not stage a business operation")
        assertTrue(submissions.isEmpty(), "Preview must not dispatch a business operation")
        assertTrue(lookups.isEmpty(), "Preview must not adopt an original business operation")
    }
    fun releaseHeld() {
        heldReads.forEach { it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) }
        heldPreviews.forEach { it.reply.complete(LocalFoodPricePreviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) }
    }
}

internal class FoodPricePreviewFrozenSession(initial: FoodPricePreviewFrozenPort) : BranchUiSession {
    private val ids = FoodPricePreviewFrozenInputs
    override val display = SessionDisplayContext(ids.actor, ids.company, ids.branch,
        "Owner A", "Food company", "Food branch", setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(ids.actor, 1))
    override var workflows: LocalWorkflowGateway = initial
    private var active = true
    private var replacement: FoodPricePreviewFrozenPort? = null
    fun replaceWith(port: FoodPricePreviewFrozenPort) { replacement = port }
    override fun isCurrent() = active && lifecycle.value == LocalSessionLifecycle.ACTIVE
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = checkNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(ids.actor, checkNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute) = isCurrent()
    override fun close() { active = false }
}

/** Native editor identities are captured before the read; they are never replaced by a reply. */
internal data class FoodPricePreviewFrozenCheckpoint(
    val lineId: DomainId,
    val preparationIds: List<DomainId>,
    val servingQuantity: String,
)

internal class FoodPricePreviewFrozenControllerFixture(
    val runtime: UkkeraRuntime,
    val session: FoodPricePreviewFrozenSession,
    private val model: EntryViewModel,
    val port: FoodPricePreviewFrozenPort,
    private val scope: TestScope,
) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
    private val ports = mutableListOf(port)
    fun token(): NavigationToken = runtime.navigator.token()

    fun enter(quantity: String = "2"): FoodPricePreviewFrozenCheckpoint {
        val ids = FoodPricePreviewFrozenInputs
        val empty = assertIs<LocalFoodUiBody.Check>(controller.state.value.page?.food?.body)
        assertEquals(ids.order, empty.check.orderId)
        assertEquals(Revision(7), empty.check.orderStorageRevision)
        assertTrue(empty.portions.isEmpty())
        assertEquals(Money(Currency.EGP, 0), empty.originalQuotedTotal)
        controller.selectFood(ids.menu, token())
        scope.runCurrent()
        controller.chooseFood(ids.variant, ids.cheese, token())
        val line = assertNotNull(controller.state.value.form).lines.single().lineId
        controller.edit(LocalField.FOOD_SERVING_COUNT, LocalDraftValue(quantity), token(), line)
        controller.edit(LocalField.ITEM_NOTE, LocalDraftValue(ids.itemNote), token(), line)
        controller.edit(LocalField.LINE_SEAT_NOTE, LocalDraftValue(ids.seatNote), token(), line)
        return checkpoint().also { assertDraft(it); assertNoBusinessCalls() }
    }

    fun checkpoint(): FoodPricePreviewFrozenCheckpoint = assertNotNull(controller.state.value.form).lines.single().let {
        FoodPricePreviewFrozenCheckpoint(it.lineId, it.foodPortionIds.toList(),
            it.values.getValue(LocalField.FOOD_SERVING_COUNT).text)
    }

    private val activePort get() = session.workflows as FoodPricePreviewFrozenPort

    fun preview(expected: FoodPricePreviewFrozenExpected = activePort.expected) {
        activePort.expected = expected
        controller.previewFood(token())
        scope.runCurrent()
    }

    fun assertDraft(checkpoint: FoodPricePreviewFrozenCheckpoint, current: Boolean = false) {
        val ids = FoodPricePreviewFrozenInputs
        val form = assertNotNull(controller.state.value.form)
        assertEquals(LocalAction.FOOD_ADD, form.descriptor.action)
        fun fixed(field: LocalField): LocalScalarValue {
            val coordinate = form.descriptor.fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == field }
            assertTrue(coordinate.readOnly, "The current protected descriptor owns $field")
            return assertNotNull(coordinate.initialValue)
        }
        assertEquals(LocalScalarValue.Identifier(ids.order), fixed(LocalField.ORDER_ID))
        assertEquals(LocalScalarValue.Integer(7), fixed(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION))
        assertEquals(LocalScalarValue.Integer(if (current) 6L else 5L), fixed(LocalField.EXPECTED_CATALOG_REVISION))
        val line = form.lines.single()
        val source = assertNotNull(line.foodMenuSource)
        assertEquals(ids.order, source.orderId)
        assertEquals(Revision(7), source.expectedOrderStorageRevision)
        assertEquals(checkpoint.lineId, line.lineId)
        assertEquals(checkpoint.preparationIds, line.foodPortionIds)
        assertEquals(checkpoint.servingQuantity, line.values.getValue(LocalField.FOOD_SERVING_COUNT).text)
        assertEquals(ids.product.value, line.values.getValue(LocalField.SKU_ID).text)
        assertEquals(ids.variant.value, line.values.getValue(LocalField.RECIPE_ID).text)
        assertEquals(ids.cheese.value, line.values.getValue(LocalField.CHOICE_ID).text)
        assertEquals(ids.cheese.value, line.values.getValue(LocalField.MODIFIER_IDS).text)
        assertEquals(if (current) "2" else "1", line.values.getValue(LocalField.EXPECTED_FOOD_RECIPE_REVISION).text)
        assertEquals(ids.itemNote, line.values.getValue(LocalField.ITEM_NOTE).text)
        assertEquals(ids.seatNote, line.values.getValue(LocalField.LINE_SEAT_NOTE).text)
        assertTrue(form.values.keys.none { it == LocalField.SEAT_NOTE }, "Seat note is line input")
    }

    fun assertPreview(expected: FoodPricePreviewFrozenExpected, checkpoint: FoodPricePreviewFrozenCheckpoint,
        current: Boolean = false) {
        val ids = FoodPricePreviewFrozenInputs
        val preview = assertNotNull(controller.state.value.foodPricePreview)
        val input = preview.input
        assertTrue(input.sameSelection(activePort.previews.last()), "Reply snapshots preserve exact selection")
        fun scalar(field: LocalField) = input.values.single { it.field == field }.value
        fun column(field: LocalField) = input.lines.single().values.single { it.field == field }.value
        assertEquals(LocalAction.FOOD_ADD, input.action)
        assertEquals(LocalScalarValue.Identifier(ids.order), scalar(LocalField.ORDER_ID))
        assertEquals(LocalScalarValue.Integer(7), scalar(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION))
        assertEquals(LocalScalarValue.Integer(if (current) 6L else 5L), scalar(LocalField.EXPECTED_CATALOG_REVISION))
        assertEquals(checkpoint.lineId, input.lines.single().lineId)
        assertEquals(LocalScalarValue.Integer(expected.servingQuantity), column(LocalField.FOOD_SERVING_COUNT))
        assertEquals(LocalScalarValue.Identifier(ids.product), column(LocalField.SKU_ID))
        assertEquals(LocalScalarValue.Identifier(ids.variant), column(LocalField.RECIPE_ID))
        assertEquals(LocalScalarValue.Identifier(ids.cheese), column(LocalField.CHOICE_ID))
        assertEquals(LocalScalarValue.Text(ids.cheese.value), column(LocalField.MODIFIER_IDS))
        assertEquals(LocalScalarValue.Text(ids.itemNote), column(LocalField.ITEM_NOTE))
        assertEquals(LocalScalarValue.Text(ids.seatNote), column(LocalField.LINE_SEAT_NOTE))
        val line = preview.lines.single()
        assertEquals(checkpoint.lineId, line.lineId)
        assertEquals(Quantity(ids.unit, expected.servingQuantity, 0), line.quantity)
        assertEquals(foodPricePreviewFrozenUnitPrice(expected.unitMinor), line.unitPrice)
        assertEquals(Money(Currency.EGP, expected.unitMinor), line.unitPrice.toFraction().toMoney(Currency.EGP))
        assertEquals(ExactRational.ratio(expected.unitMinor, 100L), line.unitPrice.toFraction())
        assertEquals(Money(Currency.EGP, expected.lineMinor), line.payable)
        assertEquals(expected.includesTax, line.taxIncluded)
        assertEquals(Money(Currency.EGP, expected.netMinor), line.net)
        assertEquals(Money(Currency.EGP, expected.taxMinor), line.tax)
        assertEquals(Money(Currency.EGP, expected.orderMinor), preview.payable)
        assertEquals(Money(Currency.EGP, expected.orderMinor), preview.beforeDiscount)
        assertEquals(Money(Currency.EGP, expected.netMinor), preview.net)
        assertEquals(Money(Currency.EGP, expected.taxMinor), preview.tax)
        assertEquals(Money(Currency.EGP, 0), preview.discount)
        assertEquals(Money(Currency.EGP, 0), preview.charges)
        assertNull(controller.state.value.foodPricePreviewProblem)
        assertDraft(checkpoint, current)
        assertNoBusinessCalls()
    }

    fun replaceAttachment(fresh: FoodPricePreviewFrozenPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.FOOD, food = FoodPricePreviewFrozenInputs.menu)
        return controller
    }

    fun assertNoBusinessCalls() {
        ports.forEach(FoodPricePreviewFrozenPort::assertNoBusinessCalls)
        assertNull(controller.state.value.operationId, "A preview must not mint an operation identity")
        assertNull(controller.state.value.operation)
    }
    fun releaseHeld() = ports.forEach(FoodPricePreviewFrozenPort::releaseHeld)
}

internal suspend fun TestScope.withFoodPricePreviewFrozenController(
    monitorSession: Boolean = false,
    block: suspend (FoodPricePreviewFrozenControllerFixture) -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = FoodPricePreviewFrozenPort()
    val session = FoodPricePreviewFrozenSession(port)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var fixture: FoodPricePreviewFrozenControllerFixture? = null
    var monitor: Job? = null
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
        fixture = FoodPricePreviewFrozenControllerFixture(runtime, session,
            provider["entry-${entry.id}", EntryViewModel::class], port, this)
        fixture.controller.activate(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(FoodPricePreviewFrozenInputs.order))
        if (monitorSession) monitor = backgroundScope.launch { runtime.monitorAuthenticatedSession() }
        runCurrent()
        block(fixture)
    } finally {
        fixture?.releaseHeld()
        port.releaseHeld()
        monitor?.cancel()
        try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

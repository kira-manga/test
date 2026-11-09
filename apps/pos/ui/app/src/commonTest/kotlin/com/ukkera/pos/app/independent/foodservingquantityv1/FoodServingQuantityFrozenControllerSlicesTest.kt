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
 * Bounded controller/presentation bindings of food-serving-quantity-v1 FQ01–04.
 * The existing empty order and revision 7 are supplied frozen preconditions. Product,
 * variant and cheese bind to output SKU, recipe and complete choice/modifier respectively.
 * Preparation identities are captured after normal explicit input, before refresh/staging.
 *
 * Session/read/stage replies are controlled seams. They prove neither renewal lineage,
 * Master authority/activation, a saved commercial line, durable storage, RPC nor restart.
 * The 22000/28000 inputs below verify read-only projection; price calculation and the
 * independent stock/value/payment census remain unbound. Every whole case remains NOT_RUN.
 */
class FoodServingQuantityFrozenControllerSlicesTest {
    @Test
    fun fq01EnglishAndArabicTwoStageOneOriginalLineAndExactLineNotesWithoutSubmission() = runTest {
        listOf("2", "٢").forEach { enteredCount ->
            withQuantityController { fixture ->
                val original = fixture.enterTwo(editFromOne = false, enteredCount = enteredCount)
                fixture.controller.stage(fixture.token())
                runCurrent()
                assertEquals(LocalField.CONFIRMED, fixture.controller.state.value.issue?.field)
                fixture.port.assertNoCommands()

                fixture.confirmAndStage()
                fixture.assertStaged(fixture.port.stages.single(), original, current = false)
                assertTrue(fixture.port.submissions.isEmpty())
                assertTrue(fixture.port.lookups.isEmpty())
            }
        }
    }

    @Test
    fun fq01ExplicitOneToTwoKeepsTheOriginalLineAndConfiguredServingQuantity() = runTest {
        withQuantityController { fixture ->
            val original = fixture.enterTwo(editFromOne = true)
            fixture.port.assertNoCommands()
            fixture.confirmAndStage()
            fixture.assertStaged(fixture.port.stages.single(), original, current = false)
            assertEquals(1, fixture.port.stages.single().lines.size)
            assertTrue(fixture.port.submissions.isEmpty())
        }
    }

    @Test
    fun fq01AttachmentRefreshRetainsTwoAndPreparationIdentitiesUntilFreshConfirmation() = runTest {
        listOf(false to "2", true to "2", false to "٢", true to "٢").forEach { (editFromOne, enteredCount) ->
            withQuantityController { fixture ->
                val original = fixture.enterTwo(editFromOne, enteredCount)
                fixture.controller.confirmForm(true, fixture.token())
                val fresh = QuantityUiPort().apply { policyRevision = Revision(11) }
                val held = fresh.holdNextRead()
                val current = fixture.replaceAttachment(fresh)
                fixture.assertPending(current, original)
                assertNull(current.state.value.page)
                fresh.assertNoCommands()

                current.reviewAttachment(fixture.token())
                runCurrent()
                held.requested.await()
                fixture.assertPending(current, original)
                fixture.tryConfirmation(current)
                fresh.assertNoCommands()
                fixture.port.assertNoCommands()

                held.reply.complete(fresh.page(QUANTITY_MENU))
                runCurrent()
                held.returned.await()
                fixture.assertDraft(assertNotNull(current.state.value.form), original, current = false)
                assertFalse(assertNotNull(current.state.value.form).confirmed)
                assertNull(current.state.value.formInput)
                assertFalse(current.state.value.attachmentReviewRequired)
                assertEquals(listOf(QUANTITY_MENU, LocalFoodUiQuery.Check(QUANTITY_ORDER)), fresh.reads)

                current.stage(fixture.token())
                runCurrent()
                fresh.assertNoCommands()
                current.confirmForm(true, fixture.token())
                current.stage(fixture.token())
                runCurrent()
                fixture.assertStaged(fresh.stages.single(), original, current = false)
                assertTrue(fresh.submissions.isEmpty())
                fixture.port.assertNoCommands()
            }
        }
    }

    @Test
    fun fq02CompatibleFreshSourceKeepsTwoAndBothNotesAndStagesOnlyFreshPins() = runTest {
        withQuantityController { fixture ->
            val original = fixture.enterTwo(editFromOne = false)
            fixture.controller.confirmForm(true, fixture.token())
            val fresh = QuantityUiPort().apply {
                catalogRevision = Revision(6)
                policyRevision = Revision(11)
                recipes = listOf(quantityRecipe(current = true))
            }
            val current = fixture.replaceAttachment(fresh)
            fixture.assertPending(current, original)
            fixture.tryConfirmation(current)
            fresh.assertNoCommands()
            current.reviewAttachment(fixture.token())
            runCurrent()
            fixture.assertDraft(assertNotNull(current.state.value.form), original, current = true)
            assertFalse(assertNotNull(current.state.value.form).confirmed)
            fixture.port.assertNoCommands()
            fresh.assertNoCommands()

            current.stage(fixture.token())
            runCurrent()
            fresh.assertNoCommands()
            current.confirmForm(true, fixture.token())
            current.stage(fixture.token())
            runCurrent()
            fixture.assertStaged(fresh.stages.single(), original, current = true)
            assertTrue(fresh.submissions.isEmpty())
            assertTrue(fresh.lookups.isEmpty())
        }
    }

    @Test
    fun fq03UnavailableVariantOrCheeseCannotSubstituteEqualLabelsForQuantityTwo() = runTest {
        val unavailableSources = listOf(
            listOf(quantityLookalikeRecipe()),
            listOf(quantityRecipe().copy(choices = listOf(
                LocalFoodUiChoice(QUANTITY_VARIANT, LocalText("Burger", "برجر"), emptyList()))),
                quantityLookalikeRecipe()),
        )
        unavailableSources.forEach { recipes ->
            withQuantityController { fixture ->
                val original = fixture.enterTwo(editFromOne = false)
                val fresh = QuantityUiPort().apply { this.recipes = recipes; policyRevision = Revision(11) }
                val current = fixture.replaceAttachment(fresh)
                fixture.assertPending(current, original)
                current.reviewAttachment(fixture.token())
                runCurrent()
                assertNull(current.state.value.form)
                assertNull(current.state.value.page)
                assertNotNull(current.state.value.readProblem)
                fixture.tryConfirmation(current)
                fresh.assertNoCommands()
                fixture.port.assertNoCommands()
                // Inaccessible/incompatible unstaged retention is FQ-V1-B06/UNBOUND.
            }
        }
    }

    @Test
    fun fq03LateAllowedMenuCannotReviveQuantityTwoAfterANewerDeniedAttachment() = runTest {
        withQuantityController { fixture ->
            val original = fixture.enterTwo(editFromOne = false)
            val earlier = QuantityUiPort().apply { policyRevision = Revision(11) }
            val held = earlier.holdNextRead()
            val retired = fixture.replaceAttachment(earlier)
            fixture.assertPending(retired, original)
            retired.reviewAttachment(fixture.token())
            runCurrent()
            held.requested.await()

            val denied = QuantityUiPort().apply { denyReads = true; policyRevision = Revision(12) }
            val current = fixture.replaceAttachment(denied)
            current.reviewAttachment(fixture.token())
            runCurrent()
            assertEquals(LocalServiceProblem.DENIED, current.state.value.readProblem)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            held.reply.complete(earlier.page(QUANTITY_MENU))
            runCurrent()
            held.returned.await()

            fixture.tryConfirmation(retired)
            fixture.tryConfirmation(current)
            assertEquals(LocalServiceProblem.DENIED, current.state.value.readProblem)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            assertNull(current.state.value.operationId)
            earlier.assertNoCommands()
            denied.assertNoCommands()
            fixture.port.assertNoCommands()
        }
    }

    @Test
    fun fq03ChangedOriginalOrderRevisionCannotRebindTheOldQuantityTwoEdit() = runTest {
        withQuantityController { fixture ->
            val original = fixture.enterTwo(editFromOne = true)
            val fresh = QuantityUiPort().apply {
                orderRevision = Revision(9) // Controlled changed cut; no revision increment is inferred.
                policyRevision = Revision(11)
            }
            val current = fixture.replaceAttachment(fresh)
            fixture.assertPending(current, original)
            current.reviewAttachment(fixture.token())
            runCurrent()
            assertNull(current.state.value.form)
            assertNotNull(current.state.value.readProblem)
            fixture.tryConfirmation(current)
            fresh.assertNoCommands()
            fixture.port.assertNoCommands()
        }
    }

    @Test
    fun fq04StagedQuantityTwoSurvivesAttachmentReviewAndLookupWithoutRebuildingInput() = runTest {
        withQuantityController { fixture ->
            val original = fixture.enterTwo(editFromOne = false)
            fixture.confirmAndStage()
            val staged = fixture.port.stages.single()
            fixture.assertStaged(staged, original, current = false)
            val fresh = QuantityUiPort().apply {
                catalogRevision = Revision(6)
                policyRevision = Revision(11)
                recipes = listOf(quantityRecipe(current = true))
            }
            val current = fixture.replaceAttachment(fresh)
            assertEquals(staged.operationId, current.state.value.operationId)
            assertNull(current.state.value.form)
            assertNull(current.state.value.formInput)
            assertTrue(fresh.reads.isEmpty())
            fresh.assertNoCommands()

            current.reviewAttachment(fixture.token())
            runCurrent()
            assertEquals(staged.operationId, current.state.value.operationId)
            assertNull(current.state.value.form)
            assertNull(current.state.value.formInput)
            fresh.assertNoCommands()
            current.lookup(staged.operationId, fixture.token())
            runCurrent()
            assertEquals(listOf(staged.operationId), fresh.lookups)
            assertEquals(staged.operationId,
                assertIs<LocalOperationResult.Unresolved>(current.state.value.operation).operationId)
            assertTrue(fresh.stages.isEmpty())
            assertTrue(fresh.submissions.isEmpty())
            assertEquals(1, fixture.port.stages.size)
            assertTrue(fixture.port.submissions.isEmpty())
            fixture.assertStaged(staged, original, current = false)
        }
    }

    @Test
    fun fq01And02CompleteReadProjectionRetainsOneQuantityTwoLineAndBothExactNotes() {
        listOf(22000L, 28000L).forEach { quotedMinor ->
            val check = quantityReadBody(quotedMinor)
            val line = assertNotNull(check.completeCommercialLines()).single()
            assertEquals(QUANTITY_LINE, line.lineId)
            assertEquals(Quantity(QUANTITY_UNIT, 2, 0), line.quantity)
            assertEquals(QUANTITY_ITEM_NOTE, line.itemNote)
            assertEquals(QUANTITY_SEAT_NOTE, line.seatNote)
            assertEquals(2, line.portionCount)
            assertEquals("", check.check.service.seatNote, "A check-header note does not supply the line note")
            assertEquals(Money(Currency.EGP, quotedMinor), check.originalQuotedTotal)
            assertEquals(2, check.portions.size)
            assertTrue(check.portions.all { it.quantity == Quantity(QUANTITY_UNIT, 1, 0) })
        }
    }

    @Test
    fun fq06PartialReadProjectionCannotClaimTheWholeCommercialLineQuantity() {
        val complete = quantityReadBody(22000)
        complete.portions.forEach { visible ->
            val partial = complete.copy(portions = listOf(visible))
            assertEquals(2, partial.check.portionCount)
            assertEquals(Quantity(QUANTITY_UNIT, 1, 0), partial.portions.single().quantity)
            assertEquals(QUANTITY_ITEM_NOTE, partial.portions.single().itemNote)
            assertEquals(QUANTITY_SEAT_NOTE, partial.portions.single().seatNote)
            assertNull(partial.completeCommercialLines(), "One selected member is not a whole quantity-two line")
            assertEquals(Money(Currency.EGP, 22000), partial.originalQuotedTotal)
        }
    }

    @Test
    fun fqSharedZeroCountCannotStageAHiddenPartialOrDefaultServing() = runTest {
        withQuantityController { fixture ->
            val original = fixture.enterTwo(editFromOne = false)
            fixture.controller.edit(LocalField.FOOD_SERVING_COUNT, LocalDraftValue("0"), fixture.token(), original.lineId)
            fixture.confirmAndStage()
            assertEquals(LocalField.FOOD_SERVING_COUNT, fixture.controller.state.value.issue?.field)
            assertNull(fixture.controller.state.value.operationId)
            fixture.port.assertNoCommands()
        }
    }
}

private val QUANTITY_COMPANY = DomainId("a0000000-0000-4000-8000-000000000002")
private val QUANTITY_BRANCH = DomainId("a0000000-0000-4000-8000-000000000004")
private val QUANTITY_ACTOR = DomainId("b0000000-0000-4000-8000-000000000001")
private val QUANTITY_ORDER = DomainId("f0000000-0000-4000-8000-000000000001")
private val QUANTITY_LINE = DomainId("f3000000-0000-4000-8000-000000000001")
private val QUANTITY_SECOND = DomainId("f3000000-0000-4000-8000-000000000002")
private val QUANTITY_PRODUCT = DomainId("f1000000-0000-4000-8000-000000000001")
private val QUANTITY_VARIANT = DomainId("f1000000-0000-4000-8000-000000000002")
private val QUANTITY_CHEESE = DomainId("f1000000-0000-4000-8000-000000000003")
// A fixed native unit binding for the frozen recipe output "burger", not a universal food rule.
private val QUANTITY_UNIT = DomainId("f6000000-0000-4000-8000-000000000001")
private val QUANTITY_MENU = LocalFoodUiQuery.Menu(orderId = QUANTITY_ORDER, expectedOrderStorageRevision = Revision(7))
private const val QUANTITY_ITEM_NOTE = "no salt / بدون ملح"
private const val QUANTITY_SEAT_NOTE = "Seat 02 / المقعد 02"

private fun quantityNames(current: Boolean): LocalText = if (current) LocalText("Burger today", "برجر اليوم")
    else LocalText("Burger", "برجر")
private fun quantityRecipe(current: Boolean = false): LocalFoodUiRecipe = LocalFoodUiRecipe(
    QUANTITY_VARIANT, Revision(if (current) 2 else 1), QUANTITY_PRODUCT, Quantity(QUANTITY_UNIT, 1, 0), true,
    listOf(LocalFoodUiChoice(QUANTITY_CHEESE, quantityNames(current), listOf(QUANTITY_CHEESE))))
private fun quantityLookalikeRecipe(): LocalFoodUiRecipe = quantityRecipe().copy(
    recipeId = DomainId("f1000000-0000-4000-8000-000000000005"),
    outputSkuId = DomainId("f1000000-0000-4000-8000-000000000004"),
    choices = listOf(LocalFoodUiChoice(DomainId("f1000000-0000-4000-8000-000000000006"),
        quantityNames(false), listOf(DomainId("f1000000-0000-4000-8000-000000000006")))))

private fun quantityAddForm(query: LocalFoodUiQuery.Menu, catalog: Revision): LocalActionForm {
    fun scalar(field: LocalField, kind: LocalInputKind, value: LocalScalarValue, maximum: Int) =
        LocalFormField.Scalar(field, LocalText(field.name, field.name), kind,
            maximumCharacters = maximum, readOnly = true, initialValue = value)
    return LocalActionForm(LocalAction.FOOD_ADD, 1, LocalText("Add configured food", "إضافة طعام مُعدّ"), listOf(
        scalar(LocalField.ORDER_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(assertNotNull(query.orderId)), 36),
        scalar(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalInputKind.INTEGER,
            LocalScalarValue.Integer(assertNotNull(query.expectedOrderStorageRevision).value), 19),
        scalar(LocalField.EXPECTED_CATALOG_REVISION, LocalInputKind.INTEGER, LocalScalarValue.Integer(catalog.value), 19),
    ), confirmation = LocalText("Confirm this exact input for separate submission", "تأكيد هذه المدخلات الدقيقة للإرسال المنفصل"))
}

private fun quantityReadBody(quotedMinor: Long): LocalFoodUiBody.Check = LocalFoodUiBody.Check(
    LocalFoodUiCheck(QUANTITY_ORDER, QUANTITY_ACTOR, Revision(7), "RESTAURANT",
        LocalFoodUiService("DINE_IN", "T-02", ""), 2, null, null),
    listOf(QUANTITY_LINE, QUANTITY_SECOND).map { id ->
        LocalFoodUiPortion(id, "Burger / برجر", Quantity(QUANTITY_UNIT, 1, 0), QUANTITY_ITEM_NOTE,
            Revision(1), LocalFoodPreparation.NOT_SENT, LocalFoodFulfillment.NOT_HANDED_OVER,
            started = false, cancellationRequested = false, lineId = QUANTITY_LINE, seatNote = QUANTITY_SEAT_NOTE)
    }, Money(Currency.EGP, quotedMinor), false)

private class QuantityHeldRead {
    val requested = CompletableDeferred<Unit>()
    val returned = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalReadResult>()
}

private class QuantityUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalFoodUiQuery>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    var catalogRevision = Revision(5)
    var policyRevision = Revision(10)
    var orderRevision = Revision(7)
    var recipes = listOf(quantityRecipe())
    var denyReads = false
    private var nextHeld: QuantityHeldRead? = null
    private val holds = mutableListOf<QuantityHeldRead>()
    fun holdNextRead(): QuantityHeldRead = QuantityHeldRead().also {
        check(nextHeld == null); nextHeld = it; holds += it
    }
    fun page(query: LocalFoodUiQuery): LocalReadResult {
        val body: LocalFoodUiBody = when (query) {
            is LocalFoodUiQuery.Menu -> LocalFoodUiBody.Menu(catalogRevision, recipes)
            is LocalFoodUiQuery.Check -> LocalFoodUiBody.Check(
                LocalFoodUiCheck(query.orderId, QUANTITY_ACTOR, orderRevision, "RESTAURANT",
                    LocalFoodUiService("DINE_IN", "T-02", ""), 0, null, null), emptyList(), Money(Currency.EGP, 0), false)
            else -> error("Unbound quantity UI read")
        }
        return LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(),
            if (query is LocalFoodUiQuery.Menu) listOf(quantityAddForm(query, catalogRevision)) else emptyList(),
            food = LocalFoodUiPage(query, QUANTITY_COMPANY, QUANTITY_BRANCH, QUANTITY_ACTOR, policyRevision,
                UtcInstant.parseCanonical("2026-10-30T09:00:00Z"), body)))
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
        return if (denyReads) LocalReadResult.Unavailable(LocalServiceProblem.DENIED) else page(selected)
    }
    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.Staged(LocalPendingOperation(intent.operationId, intent.action,
            LocalText("Controlled quantity input", "مدخلات كمية مُتحكم بها"), submitAllowed = true))
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
        assertTrue(stages.isEmpty()); assertTrue(submissions.isEmpty()); assertTrue(lookups.isEmpty())
    }
    fun releaseHeld() { holds.forEach { it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) } }
}

private class QuantityUiSession(initial: QuantityUiPort) : BranchUiSession {
    override val display = SessionDisplayContext(QUANTITY_ACTOR, QUANTITY_COMPANY, QUANTITY_BRANCH,
        "Owner A", "Food company", "Food branch", setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initial
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(QUANTITY_ACTOR, 1))
    private var active = true
    private var replacement: QuantityUiPort? = null
    fun replaceWith(port: QuantityUiPort) { replacement = port }
    override fun isCurrent(): Boolean = active
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(QUANTITY_ACTOR, assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override fun close() { active = false }
}

private data class QuantityCheckpoint(val lineId: DomainId, val preparationIds: List<DomainId>, val enteredCount: String)

private class QuantityControllerFixture(val runtime: UkkeraRuntime, private val session: QuantityUiSession,
    private val model: EntryViewModel, val port: QuantityUiPort, private val scope: TestScope) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
    private val ports = mutableListOf(port)
    fun token(): NavigationToken = runtime.navigator.token()
    fun enterTwo(editFromOne: Boolean, enteredCount: String = "2"): QuantityCheckpoint {
        val empty = assertIs<LocalFoodUiBody.Check>(controller.state.value.page?.food?.body)
        assertEquals(QUANTITY_ORDER, empty.check.orderId)
        assertEquals(Revision(7), empty.check.orderStorageRevision)
        assertEquals(0, empty.check.portionCount)
        val add = LocalFoodUiQuery.Menu(orderId = empty.check.orderId,
            expectedOrderStorageRevision = empty.check.orderStorageRevision)
        controller.selectFood(add, token())
        scope.runCurrent()
        controller.chooseFood(QUANTITY_VARIANT, QUANTITY_CHEESE, token())
        val lineId = assertNotNull(controller.state.value.form).lines.single().lineId
        if (editFromOne) {
            controller.edit(LocalField.FOOD_SERVING_COUNT, LocalDraftValue("1"), token(), lineId)
            assertEquals("1", assertNotNull(controller.state.value.form).lines.single().values.getValue(LocalField.FOOD_SERVING_COUNT).text)
            port.assertNoCommands()
        }
        controller.edit(LocalField.FOOD_SERVING_COUNT, LocalDraftValue(enteredCount), token(), lineId)
        controller.edit(LocalField.ITEM_NOTE, LocalDraftValue(QUANTITY_ITEM_NOTE), token(), lineId)
        controller.edit(LocalField.LINE_SEAT_NOTE, LocalDraftValue(QUANTITY_SEAT_NOTE), token(), lineId)
        val line = assertNotNull(controller.state.value.form).lines.single()
        val original = QuantityCheckpoint(lineId, line.foodPortionIds.toList(), enteredCount)
        assertEquals(2, original.preparationIds.size)
        assertEquals(2, original.preparationIds.distinct().size)
        assertEquals(original.lineId, original.preparationIds.first())
        assertDraft(assertNotNull(controller.state.value.form), original, current = false)
        assertFalse(assertNotNull(controller.state.value.form).confirmed)
        port.assertNoCommands()
        return original
    }
    fun confirmAndStage() {
        controller.confirmForm(true, token())
        controller.stage(token())
        scope.runCurrent()
    }
    fun tryConfirmation(current: LocalWorkflowController) {
        current.confirmForm(true, token())
        current.stage(token())
        scope.runCurrent()
    }
    fun replaceAttachment(fresh: QuantityUiPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.FOOD, food = QUANTITY_MENU)
        assertTrue(controller.state.value.attachmentReviewRequired)
        return controller
    }
    fun assertPending(current: LocalWorkflowController, original: QuantityCheckpoint) {
        assertNull(current.state.value.form)
        val input = assertNotNull(current.state.value.formInput)
        assertEquals("7", input.values.getValue(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).text)
        val line = input.lines.single()
        // The readonly add target is retained in its original public selector, not editable values.
        val source = assertNotNull(line.foodMenuSource)
        assertEquals(QUANTITY_MENU, source)
        assertEquals(QUANTITY_MENU, current.state.value.foodQuery)
        assertEquals(QUANTITY_ORDER, source.orderId)
        assertEquals(Revision(7), source.expectedOrderStorageRevision)
        val selection = assertNotNull(line.foodMenuSelection)
        assertEquals(QUANTITY_PRODUCT, selection.outputSkuId)
        assertEquals(listOf(QUANTITY_CHEESE), selection.modifierIds)
        assertEquals(Quantity(QUANTITY_UNIT, 1, 0), selection.portionQuantity)
        assertEquals(original.lineId, line.lineId)
        assertEquals(original.preparationIds, line.foodPortionIds)
        assertEquals(original.enteredCount, line.values.getValue(LocalField.FOOD_SERVING_COUNT).text)
        assertEquals(QUANTITY_ITEM_NOTE, line.values.getValue(LocalField.ITEM_NOTE).text)
        assertEquals(QUANTITY_SEAT_NOTE, line.values.getValue(LocalField.LINE_SEAT_NOTE).text)
        assertEquals(QUANTITY_VARIANT.value, line.values.getValue(LocalField.RECIPE_ID).text)
        assertEquals(QUANTITY_CHEESE.value, line.values.getValue(LocalField.CHOICE_ID).text)
        assertFalse(line.values.containsKey(LocalField.NAME_ENGLISH))
        assertFalse(line.values.containsKey(LocalField.NAME_ARABIC))
    }
    fun assertDraft(form: LocalFormDraft, original: QuantityCheckpoint, current: Boolean) {
        assertEquals(LocalAction.FOOD_ADD, form.descriptor.action)
        assertEquals(QUANTITY_ORDER.value, form.values.getValue(LocalField.ORDER_ID).text)
        assertEquals("7", form.values.getValue(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).text)
        assertEquals(if (current) "6" else "5", form.values.getValue(LocalField.EXPECTED_CATALOG_REVISION).text)
        val line = form.lines.single()
        assertEquals(original.lineId, line.lineId)
        assertEquals(original.preparationIds, line.foodPortionIds)
        assertEquals(original.enteredCount, line.values.getValue(LocalField.FOOD_SERVING_COUNT).text)
        assertEquals("1", line.values.getValue(LocalField.QUANTITY).text)
        assertEquals(QUANTITY_UNIT.value, line.values.getValue(LocalField.UNIT).text)
        assertEquals(QUANTITY_PRODUCT.value, line.values.getValue(LocalField.SKU_ID).text)
        assertEquals(QUANTITY_VARIANT.value, line.values.getValue(LocalField.RECIPE_ID).text)
        assertEquals(QUANTITY_CHEESE.value, line.values.getValue(LocalField.CHOICE_ID).text)
        assertEquals(QUANTITY_CHEESE.value, line.values.getValue(LocalField.MODIFIER_IDS).text)
        assertEquals(if (current) "2" else "1", line.values.getValue(LocalField.EXPECTED_FOOD_RECIPE_REVISION).text)
        assertEquals(QUANTITY_ITEM_NOTE, line.values.getValue(LocalField.ITEM_NOTE).text)
        assertEquals(QUANTITY_SEAT_NOTE, line.values.getValue(LocalField.LINE_SEAT_NOTE).text)
    }
    fun assertStaged(intent: LocalCommandIntent, original: QuantityCheckpoint, current: Boolean) {
        fun scalar(field: LocalField): LocalScalarValue = intent.values.single { it.field == field }.value
        val line = intent.lines.single()
        fun column(field: LocalField): LocalScalarValue = line.values.single { it.field == field }.value
        assertEquals(LocalAction.FOOD_ADD, intent.action)
        assertEquals(LocalField.LINES, intent.lineField)
        assertNull(intent.reference)
        assertEquals(LocalScalarValue.Identifier(QUANTITY_ORDER), scalar(LocalField.ORDER_ID))
        assertEquals(LocalScalarValue.Integer(7), scalar(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION))
        assertEquals(LocalScalarValue.Integer(if (current) 6L else 5L), scalar(LocalField.EXPECTED_CATALOG_REVISION))
        assertEquals(original.lineId, line.lineId)
        assertEquals(LocalScalarValue.Integer(2), column(LocalField.FOOD_SERVING_COUNT))
        assertEquals(LocalScalarValue.Text(original.preparationIds.joinToString(",") { it.value }), column(LocalField.FOOD_PORTION_IDS))
        assertEquals(LocalScalarValue.Decimal("1"), column(LocalField.QUANTITY))
        assertEquals(LocalScalarValue.Identifier(QUANTITY_UNIT), column(LocalField.UNIT))
        assertEquals(LocalScalarValue.Identifier(QUANTITY_PRODUCT), column(LocalField.SKU_ID))
        assertEquals(LocalScalarValue.Identifier(QUANTITY_VARIANT), column(LocalField.RECIPE_ID))
        assertEquals(LocalScalarValue.Identifier(QUANTITY_CHEESE), column(LocalField.CHOICE_ID))
        assertEquals(LocalScalarValue.Text(QUANTITY_CHEESE.value), column(LocalField.MODIFIER_IDS))
        assertEquals(LocalScalarValue.Integer(if (current) 2L else 1L), column(LocalField.EXPECTED_FOOD_RECIPE_REVISION))
        assertEquals(LocalScalarValue.Text(quantityNames(current).english), column(LocalField.NAME_ENGLISH))
        assertEquals(LocalScalarValue.Text(quantityNames(current).arabic), column(LocalField.NAME_ARABIC))
        assertEquals(LocalScalarValue.Text(QUANTITY_ITEM_NOTE), column(LocalField.ITEM_NOTE))
        assertEquals(LocalScalarValue.Text(QUANTITY_SEAT_NOTE), column(LocalField.LINE_SEAT_NOTE))
        assertTrue(intent.values.none { it.field == LocalField.SEAT_NOTE }, "The exact seat note belongs to the commercial line")
    }
    fun releaseHeld() = ports.forEach(QuantityUiPort::releaseHeld)
}

private suspend fun TestScope.withQuantityController(block: suspend (QuantityControllerFixture) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = QuantityUiPort()
    val session = QuantityUiSession(port)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var fixture: QuantityControllerFixture? = null
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
        fixture = QuantityControllerFixture(runtime, session,
            provider["entry-${entry.id}", EntryViewModel::class], port, this)
        fixture.controller.activate(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(QUANTITY_ORDER))
        runCurrent()
        block(fixture)
    } finally {
        fixture?.releaseHeld()
        port.releaseHeld()
        try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}

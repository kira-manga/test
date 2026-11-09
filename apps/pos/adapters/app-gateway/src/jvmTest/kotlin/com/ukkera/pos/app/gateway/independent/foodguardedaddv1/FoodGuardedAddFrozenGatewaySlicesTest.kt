package com.ukkera.pos.app.gateway.independent.foodguardedaddv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Bounded L1 slices of frozen food-guarded-add-v1 GFA-003–006/008/012.
 * Supplied menu/order/current-session DTOs and controlled client persistence
 * responses confer no authority. Pricing uses the real pure engine with frozen
 * selected prices, not a genuine current catalog or Master pricing service.
 * No qualified lookup absence, original result, business dispatch, native
 * persistence, actual process restart or admitted transport is supplied.
 */
public class FoodGuardedAddFrozenGatewaySlicesTest {
    @Test
    public fun gfa003CurrentRestaurantEmptyCheckStagesOnlyTheGuardedLiteral(): Unit = runTest {
        assertCurrentStaging(FoodGuardedAddFrozenInputs.restaurantOrder, FoodGuardedAddFrozenInputs.GUARDED)
    }

    @Test
    public fun gfa003CurrentCafeNonemptyCheckStagesOnlyTheNewSelectedPortions(): Unit = runTest {
        val fixture = assertCurrentStaging(FoodGuardedAddFrozenInputs.cafeOrder, FoodGuardedAddFrozenInputs.cafeGolden())
        val before = fixture.endpoint.suppliedDetails.single()
        assertEquals(FoodGuardedAddFrozenInputs.actor, before.summary.ownerId)
        assertEquals(LocalSalesActivity.CAFE, before.summary.activity)
        assertEquals(LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "SYN-PICKUP-007"), before.summary.service)
        assertEquals(listOf(FoodGuardedAddFrozenInputs.existingPortion), before.portions.map { it.portionId })
        assertEquals(Money(Currency.EGP, 10000), before.originalQuotedTotal)
        val request = assertIs<LocalFoodRequestV1.Add>(savedFoodPreparation(assertNotNull(fixture.journal.entry).request()))
        assertFalse(request.portions.any { it.portionId == FoodGuardedAddFrozenInputs.existingPortion })
        assertEquals(before, fixture.endpoint.suppliedDetails.single())
        // This supplied pre-Add read is not independent evidence of unchanged durable state.
    }

    @Test
    public fun gfa003CurrentMenuFormsUseAddTwoAndGfa004CreateRemainsOne(): Unit {
        val fixture = FoodGuardedAddFrozenGatewayFixture()
        val recipe = menuRecipe()
        val menu = LocalFoodUiBody.Menu(Revision(20), listOf(LocalFoodUiRecipe(recipe.recipeId, recipe.recipeRevision,
            recipe.outputSkuId, recipe.portionQuantity, recipe.active,
            recipe.choices.map { LocalFoodUiChoice(it.choiceId, LocalText(it.names.english, it.names.arabic), it.modifierIds) })))
        fixture.withHintAccess { access ->
            listOf(FoodGuardedAddFrozenInputs.restaurantOrder, FoodGuardedAddFrozenInputs.cafeOrder).forEach { order ->
                val form = assertNotNull(foodMenuForm(access, LocalFoodUiQuery.Menu(orderId = order,
                    expectedOrderStorageRevision = Revision(7)), menu))
                assertEquals(LocalAction.FOOD_ADD, form.action)
                assertEquals(2, form.version)
            }
            val create = assertNotNull(foodMenuForm(access, LocalFoodUiQuery.Menu(), menu))
            assertEquals(LocalAction.FOOD_CREATE, create.action)
            assertEquals(1, create.version)
        }
        val addInput = FoodGuardedAddFrozenInputs.preview()
        assertEquals(1, LocalFoodPreviewInput(LocalAction.FOOD_CREATE, 1, addInput.values, addInput.lines).formVersion)
        assertFails { LocalFoodPreviewInput(LocalAction.FOOD_CREATE, 2, addInput.values, addInput.lines) }
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
    }

    @Test
    public fun gfa003MissingGuardedCommandSupportCannotStageOrDowngrade(): Unit = runTest {
        val fixture = FoodGuardedAddFrozenGatewayFixture(guardedCommandSupported = false)
        assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(FoodGuardedAddFrozenInputs.intent()))
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
        // The frozen refusal code is UNBOUND.
    }

    @Test
    public fun gfa004ExplicitFormOneEmptyCheckKeepsLegacyStockBytesAndOriginalId(): Unit = runTest {
        val fixture = FoodGuardedAddFrozenGatewayFixture()
        val input = FoodGuardedAddFrozenInputs.intent(1, FoodGuardedAddFrozenInputs.legacyStockOperation)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input))
        val saved = assertNotNull(fixture.journal.entry)
        assertStored(saved, FoodGuardedAddFrozenSavedProfile.LEGACY_STOCK)
        assertEquals(FoodGuardedAddFrozenInputs.guarded(), savedFoodPreparation(saved.request()))
        assertEquals(1, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
    }

    @Test
    public fun gfa004SavedExplicitLegacyFoodProfileKeepsItsLiteralVersionAndOriginalId(): Unit {
        val saved = FoodGuardedAddFrozenSavedProfile.LEGACY_FOOD.stored()
        val fixture = FoodGuardedAddFrozenGatewayFixture(saved)
        assertStored(assertNotNull(fixture.journal.entry), FoodGuardedAddFrozenSavedProfile.LEGACY_FOOD)
        assertEquals(FoodGuardedAddFrozenInputs.guarded(), savedFoodPreparation(saved.request()))
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
        // A supplied already-saved legacy representation is not fresh legacy admission.
    }

    @Test
    public fun gfa005AddFormOneAndTwoPreviewTheSameTwoPortionsAtMinor30000(): Unit = runTest {
        listOf(1, 2).forEach { version ->
            val fixture = FoodGuardedAddFrozenGatewayFixture()
            val input = FoodGuardedAddFrozenInputs.preview(version)
            val preview = assertIs<LocalFoodPricePreviewResult.Available>(fixture.gateway.previewFood(input)).preview
            assertPreview(preview, input, 30000, 15000)
            assertEquals(version, preview.input.formVersion)
            assertEquals(listOf(FoodGuardedAddFrozenInputs.portionIds[0], FoodGuardedAddFrozenInputs.portionIds[1]),
                preview.lines.map { it.lineId })
            assertEquals(FoodGuardedAddFrozenInputs.guarded(), fixture.endpoint.selections.single())
            assertEquals(FoodGuardedAddFrozenInputs.LEGACY_FOOD, fixture.endpoint.reads.single {
                it.query == LocalFoodSelectionPreviewCodecV1.QUERY }.payload.copyBytes().decodeToString())
            assertReadOnlyPreview(fixture)
        }
    }

    @Test
    public fun gfa005MeasuredFormOneAndTwoPreviewExactlyOnePointTwoFiveKgAtMinor3125(): Unit = runTest {
        listOf(1, 2).forEach { version ->
            val fixture = FoodGuardedAddFrozenGatewayFixture()
            val input = FoodGuardedAddFrozenInputs.preview(version, FoodGuardedAddFrozenInputs.measured())
            val preview = assertIs<LocalFoodPricePreviewResult.Available>(fixture.gateway.previewFood(input)).preview
            assertPreview(preview, input, 3125, 2500)
            assertEquals(version, preview.input.formVersion)
            assertEquals(Quantity(FoodGuardedAddFrozenInputs.kgUnit, 1250000, 6), preview.lines.single().quantity)
            assertEquals(FoodGuardedAddFrozenInputs.measured(), fixture.endpoint.selections.single())
            assertReadOnlyPreview(fixture)
        }
    }

    @Test
    public fun gfa005QuantityOrNoteEditCannotMatchThePriorPreviewSelection(): Unit = runTest {
        val fixture = FoodGuardedAddFrozenGatewayFixture()
        val input = FoodGuardedAddFrozenInputs.preview()
        val before = input.snapshot()
        val preview = assertIs<LocalFoodPricePreviewResult.Available>(fixture.gateway.previewFood(input)).preview
        val changedQuantity = replaceLineField(input, 0, LocalField.QUANTITY, LocalScalarValue.Decimal("2"))
        val changedNote = replaceLineField(input, 1, LocalField.ITEM_NOTE, LocalScalarValue.Text("No salt"))
        assertFalse(preview.input.sameSelection(changedQuantity))
        assertFalse(preview.input.sameSelection(changedNote))
        assertTrue(preview.input.sameSelection(before))
        assertTrue(input.sameSelection(before))
        assertReadOnlyPreview(fixture)
        // The shared comparison is bound; live controller invalidation remains unbound here.
    }

    @Test
    public fun gfa006GuardedOriginalReconstructionRetainsTheObservedDigestAndLiteral(): Unit = runTest {
        assertUnresolvedReconstruction(FoodGuardedAddFrozenSavedProfile.GUARDED)
    }

    @Test
    public fun gfa006LegacyStockOriginalReconstructionRetainsTheObservedDigestAndLiteral(): Unit = runTest {
        assertUnresolvedReconstruction(FoodGuardedAddFrozenSavedProfile.LEGACY_STOCK)
    }

    @Test
    public fun gfa006LegacyFoodOriginalReconstructionRetainsTheObservedDigestAndLiteral(): Unit = runTest {
        assertUnresolvedReconstruction(FoodGuardedAddFrozenSavedProfile.LEGACY_FOOD)
    }

    @Test
    public fun gfa006And012AnotherCurrentActorCannotAdoptAnySavedOriginal(): Unit = runTest {
        FoodGuardedAddFrozenSavedProfile.entries.forEach { profile ->
            val original = profile.stored()
            val fixture = FoodGuardedAddFrozenGatewayFixture(original)
            fixture.endpoint.currentActor = FoodGuardedAddFrozenInputs.otherActor
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(FoodGuardedAddFrozenInputs.intent(profile.formVersion, profile.operationId)))
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(profile.operationId))
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(profile.operationId))
            assertEquals(original, fixture.journal.entry)
            assertEquals(FoodGuardedAddFrozenInputs.actor, assertNotNull(fixture.journal.entry).owner.actorId)
            assertEquals(0, fixture.journal.stageCalls)
            assertTrue(fixture.journal.reads.isEmpty())
            assertTrue(fixture.endpoint.reads.isEmpty())
            assertTrue(fixture.endpoint.lookups.isEmpty())
            fixture.assertNoMutationDispatch()
        }
    }

    @Test
    public fun gfa008OneAndEightSupportedPreparationsQuoteMinor15000And120000(): Unit = runTest {
        listOf(FoodGuardedAddFrozenInputs.onePortion() to 15000L,
            FoodGuardedAddFrozenInputs.eightPortions() to 120000L).forEach { (request, expectedMinor) ->
            val fixture = FoodGuardedAddFrozenGatewayFixture()
            val input = FoodGuardedAddFrozenInputs.preview(2, request)
            val preview = assertIs<LocalFoodPricePreviewResult.Available>(fixture.gateway.previewFood(input)).preview
            assertPreview(preview, input, expectedMinor, 15000)
            assertEquals(FoodGuardedAddFrozenInputs.portionIds.take(request.portions.size), preview.lines.map { it.lineId })
            assertEquals(request, fixture.endpoint.selections.single())
            assertReadOnlyPreview(fixture)
        }
    }

    @Test
    public fun gfa008DeclaredWholePieceAndMeasuredPrecisionViolationsCannotStageOrPreview(): Unit = runTest {
        val whole = FoodGuardedAddFrozenInputs.preview()
        val fraction = replaceLineField(replaceLineField(whole, 0, LocalField.QUANTITY, LocalScalarValue.Decimal("0.1")),
            0, LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(1))
        val measured = FoodGuardedAddFrozenInputs.preview(2, FoodGuardedAddFrozenInputs.measured())
        val scaleSeven = replaceLineField(replaceLineField(measured, 0, LocalField.QUANTITY, LocalScalarValue.Decimal("1.2500001")),
            0, LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(7))
        listOf(fraction, scaleSeven).forEach { invalid ->
            val fixture = FoodGuardedAddFrozenGatewayFixture()
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(LocalCommandIntent(FoodGuardedAddFrozenInputs.operation,
                invalid.action, invalid.formVersion, null, invalid.values, LocalField.LINES, invalid.lines)))
            assertIs<LocalFoodPricePreviewResult.Unavailable>(fixture.gateway.previewFood(invalid))
            assertNull(fixture.journal.entry)
            assertEquals(0, fixture.journal.stageCalls)
            assertTrue(fixture.endpoint.selections.isEmpty())
            assertTrue(fixture.endpoint.lookups.isEmpty())
            fixture.assertNoMutationDispatch()
        }
    }

    @Test
    public fun gfa008CheckedFrozenMultiplicationOverflowRefusesAtThePurePriceBoundary(): Unit {
        val fixture = FoodGuardedAddFrozenGatewayFixture()
        val tax = TaxSnapshot(FoodGuardedAddFrozenInputs.choice, Revision(3), "EGP", PercentageRate.parse("0"))
        val selected = ResolvedPriceLine(FoodGuardedAddFrozenInputs.portionIds[0], FoodGuardedAddFrozenInputs.portionIds[0],
            Revision(6), Quantity(FoodGuardedAddFrozenInputs.pieceUnit, 9223372036854775807, 0),
            UnitPrice.parse(Currency.EGP, "150"), TaxInclusion.INCLUSIVE, tax)
        assertFails { OperationalPricingEngine.calculate(PricingRequest(FoodGuardedAddFrozenInputs.restaurantOrder,
            Revision(0), Currency.EGP, listOf(selected))) }
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
        // This arithmetic refusal does not establish a Master rollback or persistence failure cut.
    }

    @Test
    public fun gfa012MissingUnsupportedIncompatibleAndMalformedSavedInputsFailValidation(): Unit {
        val original = FoodGuardedAddFrozenSavedProfile.GUARDED.stored()
        val fixture = FoodGuardedAddFrozenGatewayFixture(original)
        val encoded = journalJson.encodeToString(StoredClientIntentV1.serializer(), original)
        val changedCommand = foodGuardedAddFrozenEdit(foodGuardedAddFrozenEdit(encoded, "/commandName",
            JsonPrimitive("sales.food.write")), "/commandVersion", JsonPrimitive(1))
        val invalid = listOf(
            "missing saved version" to foodGuardedAddFrozenEdit(encoded, "/formVersion", null),
            "unsupported saved version 3" to foodGuardedAddFrozenEdit(encoded, "/formVersion", JsonPrimitive(3)),
            "incompatible legacy command and guarded body" to changedCommand,
            "malformed saved body" to foodGuardedAddFrozenEdit(encoded, "/payloadHex", JsonPrimitive(encodeHex("{}".encodeToByteArray()))),
            "missing saved body" to foodGuardedAddFrozenEdit(encoded, "/payloadHex", JsonNull),
        )
        invalid.forEach { (name, body) ->
            assertFails(name) { journalJson.decodeFromString(StoredClientIntentV1.serializer(), body).validate(original.owner) }
        }
        assertFails { original.copy(formVersion = 1).validate(original.owner) }
        listOf(FoodGuardedAddFrozenSavedProfile.LEGACY_STOCK, FoodGuardedAddFrozenSavedProfile.LEGACY_FOOD).forEach { profile ->
            val legacy = profile.stored()
            assertFails { legacy.copy(formVersion = 2).validate(legacy.owner) }
        }
        assertFails { original.copy(owner = original.owner.copy(actorId = FoodGuardedAddFrozenInputs.otherActor)).validate(original.owner) }
        assertEquals(original, fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
    }

    @Test
    public fun gfa012UnavailableStorageCannotSynthesizeAnIntentOrDispatchTheSavedOriginal(): Unit = runTest {
        val original = FoodGuardedAddFrozenSavedProfile.GUARDED.stored()
        val fixture = FoodGuardedAddFrozenGatewayFixture(original)
        fixture.journal.unavailable = true
        assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(FoodGuardedAddFrozenInputs.intent()))
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(original.operationId))
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(original.operationId))
        assertEquals(original, fixture.journal.entry)
        assertStored(assertNotNull(fixture.journal.entry), FoodGuardedAddFrozenSavedProfile.GUARDED)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
    }

    private suspend fun assertCurrentStaging(orderId: DomainId, expectedBody: String): FoodGuardedAddFrozenGatewayFixture {
        val fixture = FoodGuardedAddFrozenGatewayFixture()
        val input = FoodGuardedAddFrozenInputs.intent(request = FoodGuardedAddFrozenInputs.guarded(orderId))
        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input))
        val saved = assertNotNull(fixture.journal.entry)
        assertEquals(LocalAction.FOOD_ADD, staged.operation.action)
        assertEquals(FoodGuardedAddFrozenInputs.operation, staged.operation.operationId)
        assertEquals(FoodGuardedAddFrozenInputs.operation, saved.operationId)
        assertEquals(2, saved.formVersion)
        assertEquals(FoodGuardedAddFrozenInputs.actor, saved.owner.actorId)
        val request = saved.request()
        assertEquals(LocalFoodStockCodecV2.COMMAND, request.command)
        assertEquals(CommandKey("sales.food.stock", 2), request.command)
        assertNull(request.actionApprovalId)
        assertEquals(expectedBody, request.payload.copyBytes().decodeToString())
        assertEquals(FoodGuardedAddFrozenInputs.guarded(orderId),
            assertIs<LocalFoodStockRequestV2.ActivityProtectedAdd>(LocalFoodStockCodecV2.decode(request.payload)).request)
        assertTrue(actionMatches(LocalAction.FOOD_ADD, request))
        saved.validate(fixture.journal.owner)
        assertEquals(1, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.selections.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
        return fixture
    }

    private suspend fun assertUnresolvedReconstruction(profile: FoodGuardedAddFrozenSavedProfile) {
        val initial = FoodGuardedAddFrozenGatewayFixture()
        val input = FoodGuardedAddFrozenInputs.intent(profile.formVersion, profile.operationId)
        val original = if (profile == FoodGuardedAddFrozenSavedProfile.LEGACY_FOOD) profile.stored() else {
            assertIs<LocalStageResult.Staged>(initial.gateway.stage(input))
            assertNotNull(initial.journal.entry)
        }
        assertStored(original, profile)
        val firstDigest = original.semanticSha256
        val firstInputDigest = original.inputDigest
        val encoded = journalJson.encodeToString(StoredClientIntentV1.serializer(), original)
        val restored = journalJson.decodeFromString(StoredClientIntentV1.serializer(), encoded)
        restored.validate(original.owner)
        val installations = listOf(
            LocalWorkflowInstallation(setOf(LocalFoodStockCodecV2.COMMAND), emptySet()),
            LocalWorkflowInstallation(setOf(LocalFoodStockCodecV2.COMMAND, LocalFoodProfileV1.COMMAND), emptySet()),
            LocalWorkflowInstallation(setOf(LocalFoodStockCodecV2.COMMAND, LocalFoodProfileV1.COMMAND),
                setOf(LocalStockRelationalQueriesV2.PROFILE)),
            LocalWorkflowInstallation(setOf(LocalFoodProfileV1.COMMAND), emptySet()),
        )
        installations.forEach { installation ->
            val reopened = FoodGuardedAddFrozenGatewayFixture(restored, installation = installation)
            reopened.endpoint.refuseReads = true
            assertIs<LocalStageResult.Uncertain>(reopened.gateway.stage(input))
            assertIs<LocalOperationResult.Unresolved>(reopened.gateway.lookup(profile.operationId))
            assertIs<LocalOperationResult.Unresolved>(reopened.gateway.submit(profile.operationId))
            val retained = assertNotNull(reopened.journal.entry)
            assertEquals(original, retained)
            assertStored(retained, profile)
            assertEquals(firstDigest, retained.semanticSha256)
            assertEquals(firstInputDigest, retained.inputDigest)
            assertEquals(original.identity(), retained.identity())
            assertEquals(listOf(original.identity(), original.identity(), original.identity()), reopened.endpoint.lookups)
            assertEquals(0, reopened.journal.stageCalls)
            assertTrue(reopened.endpoint.reads.isEmpty())
            reopened.assertNoMutationDispatch()
        }
        initial.assertNoMutationDispatch()
        // These changed installation hints and adapter reconstruction do not prove
        // actual activity/catalog publication changes, physical capability or native restart.
    }

    private fun assertStored(saved: StoredClientIntentV1, profile: FoodGuardedAddFrozenSavedProfile) {
        assertEquals(profile.formVersion, saved.formVersion)
        assertEquals(profile.operationId, saved.operationId)
        assertEquals(FoodGuardedAddFrozenInputs.actor, saved.owner.actorId)
        assertEquals(FoodGuardedAddFrozenInputs.scope, saved.owner.scope)
        assertEquals(FoodGuardedAddFrozenInputs.device, saved.owner.deviceId)
        assertEquals(profile.command, saved.request().command)
        assertEquals(profile.body, saved.request().payload.copyBytes().decodeToString())
        assertNull(saved.request().actionApprovalId)
        assertEquals(LocalAction.FOOD_ADD, saved.localAction)
        saved.validate(saved.owner)
    }

    private fun assertPreview(preview: LocalFoodPricePreview, input: LocalFoodPreviewInput, expectedMinor: Long, unitMinor: Long) {
        assertTrue(preview.input.sameSelection(input))
        assertEquals(Money(Currency.EGP, expectedMinor), preview.payable)
        assertEquals(Money(Currency.EGP, expectedMinor), preview.beforeDiscount)
        assertEquals(Money(Currency.EGP, expectedMinor), preview.net)
        assertEquals(Money(Currency.EGP, 0), preview.tax)
        assertEquals(Money(Currency.EGP, 0), preview.discount)
        assertEquals(Money(Currency.EGP, 0), preview.charges)
        assertTrue(preview.lines.all { it.unitPrice.toFraction().toMoney(Currency.EGP) == Money(Currency.EGP, unitMinor) })
    }

    private fun assertReadOnlyPreview(fixture: FoodGuardedAddFrozenGatewayFixture) {
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.journal.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
    }

    private fun replaceLineField(input: LocalFoodPreviewInput, index: Int, field: LocalField,
        value: LocalScalarValue): LocalFoodPreviewInput = input.copy(lines = input.lines.mapIndexed { coordinate, line ->
        if (coordinate != index) line else LocalInputLine(line.lineId, line.values.map {
            if (it.field == field) LocalScalarInput(field, value) else it
        })
    })
}

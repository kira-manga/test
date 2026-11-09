package com.ukkera.pos.app.gateway.independent.foodservingquantityv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Host-only controlled bindings of food-serving-quantity-v1 FQ01–04/FQ06.
 * Typed read payloads and the common fixture's volatile client journal exercise
 * production gateway mapping, first-stage validation and original-intent recovery.
 * They do not establish actual authority/activation, a durable saved commercial line,
 * price generation, business effects, native storage, protected RPC or a real restart.
 * The existing empty order/revision 7 and 22000/28000 read quotes are supplied facts.
 * Every whole frozen case/variant remains NOT_RUN until all required paths are bound.
 */
public class FoodServingQuantityFrozenGatewayHostSlicesTest {
    @Test
    public fun fq01HostCountTwoStagesOneOriginalLineAndTwoSortedPreparationsWithoutDispatch(): Unit = runTest {
        val endpoint = FoodQuantityHostEndpoint()
        val fixture = FoodQuantityGatewayFixture(endpoint)
        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(foodQuantityIntent()))
        assertEquals(FOOD_QUANTITY_OPERATION, staged.operation.operationId)
        assertEquals(LocalAction.FOOD_ADD, staged.operation.action)
        assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
        assertTrue(staged.operation.submitAllowed, "A controlled display hint, not Master authority")
        assertHostOriginalStaged(fixture, current = false)
        assertHostPreparationReads(endpoint)
        assertTrue(endpoint.lookups.isEmpty())
        fixture.assertNoBusinessDispatch()
    }

    @Test
    public fun fq02HostFreshCatalogAndRecipeKeepExactQuantityTwoLineAndNotes(): Unit = runTest {
        val endpoint = FoodQuantityHostEndpoint().apply { useFreshSource() }
        val fixture = FoodQuantityGatewayFixture(endpoint)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(foodQuantityIntent(current = true)))
        assertHostOriginalStaged(fixture, current = true)
        assertHostPreparationReads(endpoint)
        assertTrue(endpoint.lookups.isEmpty())
        fixture.assertNoBusinessDispatch()
    }

    @Test
    public fun fq03HostStaleCatalogOrRecipeCannotStageQuantityTwo(): Unit = runTest {
        val staleSources = listOf(
            FoodQuantityHostEndpoint().apply { catalogRevision = Revision(6) } to LocalField.EXPECTED_CATALOG_REVISION,
            FoodQuantityHostEndpoint().apply { recipes = listOf(foodQuantityHostRecipe().copy(recipeRevision = Revision(2))) } to LocalField.RECIPE_ID,
        )
        staleSources.forEach { (endpoint, field) ->
            val fixture = FoodQuantityGatewayFixture(endpoint)
            assertEquals(LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT, field),
                fixture.gateway.stage(foodQuantityIntent()))
            assertHostPreparationReads(endpoint)
            assertHostNothingStaged(fixture)
        }
    }

    @Test
    public fun fq03HostMissingOrLookalikeVariantAndOptionCannotStageQuantityTwo(): Unit = runTest {
        val original = foodQuantityHostRecipe()
        val lookalikeChoice = original.choices.single().copy(choiceId = FOOD_QUANTITY_HOST_LOOKALIKE_OPTION,
            modifierIds = listOf(FOOD_QUANTITY_HOST_LOOKALIKE_OPTION), pricingSkuId = FOOD_QUANTITY_HOST_LOOKALIKE_PRODUCT)
        val sources = listOf(
            Triple(emptyList<LocalFoodMenuRecipeV1>(), LocalServiceProblem.FOOD_TARGET_UNAVAILABLE, null),
            Triple(listOf(original.copy(recipeId = FOOD_QUANTITY_HOST_LOOKALIKE_VARIANT,
                outputSkuId = FOOD_QUANTITY_HOST_LOOKALIKE_PRODUCT, choices = listOf(lookalikeChoice))),
                LocalServiceProblem.FOOD_TARGET_UNAVAILABLE, null),
            Triple(listOf(original.copy(active = false)), LocalServiceProblem.INVALID_INPUT, LocalField.RECIPE_ID),
            Triple(listOf(original.copy(choices = listOf(lookalikeChoice))), LocalServiceProblem.INVALID_INPUT, LocalField.CHOICE_ID),
            Triple(listOf(original.copy(outputSkuId = FOOD_QUANTITY_HOST_LOOKALIKE_PRODUCT)),
                LocalServiceProblem.INVALID_INPUT, LocalField.RECIPE_ID),
        )
        sources.forEach { (recipes, problem, field) ->
            val endpoint = FoodQuantityHostEndpoint().apply { this.recipes = recipes }
            val fixture = FoodQuantityGatewayFixture(endpoint)
            assertEquals(LocalStageResult.NotStaged(problem, field), fixture.gateway.stage(foodQuantityIntent()))
            assertHostPreparationReads(endpoint)
            assertHostNothingStaged(fixture)
        }
    }

    @Test
    public fun fq03HostChangedOwnerOrderOrRevisionCannotRebindQuantityTwo(): Unit = runTest {
        val original = foodQuantityHostEmptyDetail()
        val changedCuts = listOf(
            Triple(original.copy(summary = original.summary.copy(ownerId = FOOD_QUANTITY_OTHER_ACTOR)),
                LocalServiceProblem.UNAVAILABLE, null),
            Triple(original.copy(summary = original.summary.copy(orderId = FOOD_QUANTITY_HOST_LOOKALIKE_ORDER)),
                LocalServiceProblem.UNAVAILABLE, null),
            Triple(original.copy(summary = original.summary.copy(revision = Revision(9))),
                LocalServiceProblem.INVALID_INPUT, LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION),
        )
        changedCuts.forEach { (detail, problem, field) ->
            val endpoint = FoodQuantityHostEndpoint().apply { orderDetail = detail }
            val fixture = FoodQuantityGatewayFixture(endpoint)
            assertEquals(LocalStageResult.NotStaged(problem, field), fixture.gateway.stage(foodQuantityIntent()))
            assertEquals(listOf(LocalFoodQueryV1.Order(FOOD_QUANTITY_ORDER)), endpoint.orderQueries)
            assertTrue(endpoint.menuQueries.isEmpty(), "A mismatched owner/order cut cannot disclose a new menu for staging")
            assertHostNothingStaged(fixture)
        }
    }

    @Test
    public fun fqSharedHostInvalidCountsConfiguredQuantityOrPreparationIdsCannotStagePartialWork(): Unit = runTest {
        val original = foodQuantityIntent()
        val invalidInputs = listOf(
            original.foodQuantityLineChange(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(0)),
            original.foodQuantityLineChange(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(-1)),
            original.foodQuantityLineChange(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(9)),
            original.foodQuantityLineChange(LocalField.FOOD_PORTION_IDS, null),
            original.foodQuantityLineChange(LocalField.FOOD_PORTION_IDS, LocalScalarValue.Text(FOOD_QUANTITY_LINE.value)),
            original.foodQuantityLineChange(LocalField.FOOD_PORTION_IDS,
                LocalScalarValue.Text("${FOOD_QUANTITY_LINE.value},${FOOD_QUANTITY_LINE.value}")),
            original.foodQuantityLineChange(LocalField.FOOD_PORTION_IDS,
                LocalScalarValue.Text("${FOOD_QUANTITY_SECOND.value},${FOOD_QUANTITY_LINE.value}")),
        ).map { it to LocalField.FOOD_SERVING_COUNT } + listOf(
            original.foodQuantityLineChange(LocalField.QUANTITY, LocalScalarValue.Decimal("2")) to LocalField.CHOICE_ID,
        )
        invalidInputs.forEach { (intent, field) ->
            val endpoint = FoodQuantityHostEndpoint()
            val fixture = FoodQuantityGatewayFixture(endpoint)
            assertEquals(LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT, field), fixture.gateway.stage(intent))
            assertHostNothingStaged(fixture)
        }
        // These are first-stage declared-input boundaries, not actual partial SEND/stock tests.
    }

    @Test
    public fun fq04HostExactOriginalRetryOnlyLooksUpTheStagedRequest(): Unit = runTest {
        val endpoint = FoodQuantityHostEndpoint()
        val fixture = FoodQuantityGatewayFixture(endpoint)
        val originalInput = foodQuantityIntent()
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(originalInput))
        val originalEntry = assertNotNull(fixture.journal.entry)
        val originalReadCount = endpoint.reads.size
        endpoint.useFreshSource()
        repeat(2) {
            assertEquals(LocalStageResult.Uncertain(FOOD_QUANTITY_OPERATION), fixture.gateway.stage(originalInput))
            assertEquals(originalEntry, fixture.journal.entry)
            assertEquals(1, fixture.journal.stageCalls)
            assertEquals(originalReadCount, endpoint.reads.size)
            assertHostOriginalStaged(fixture, current = false)
        }
        assertEquals(listOf(originalEntry.identity(), originalEntry.identity()), endpoint.lookups)
        fixture.assertNoBusinessDispatch()
        // Deferred lookup is not authoritative absence, a committed replay result or permission to send.
    }

    @Test
    public fun fq04HostChangedCountEitherLineNoteOrSourceCannotRewriteSameOperation(): Unit = runTest {
        val original = foodQuantityIntent()
        val changedInputs = listOf(
            original.foodQuantityLineChange(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(1))
                .foodQuantityLineChange(LocalField.FOOD_PORTION_IDS, LocalScalarValue.Text(FOOD_QUANTITY_LINE.value)),
            original.foodQuantityLineChange(LocalField.ITEM_NOTE, LocalScalarValue.Text("salt / ملح")),
            original.foodQuantityLineChange(LocalField.LINE_SEAT_NOTE, LocalScalarValue.Text("Seat 03 / المقعد 03")),
            foodQuantityIntent(current = true),
        )
        changedInputs.forEach { changed ->
            val endpoint = FoodQuantityHostEndpoint()
            val fixture = FoodQuantityGatewayFixture(endpoint)
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(original))
            val originalEntry = assertNotNull(fixture.journal.entry)
            val originalReadCount = endpoint.reads.size
            endpoint.useFreshSource()
            assertEquals(LocalStageResult.Uncertain(FOOD_QUANTITY_OPERATION), fixture.gateway.stage(changed))
            assertEquals(originalEntry, fixture.journal.entry)
            assertEquals(originalReadCount, endpoint.reads.size)
            assertTrue(endpoint.lookups.isEmpty(), "Changed input must not probe/rewrite the original request")
            assertHostOriginalStaged(fixture, current = false)
            fixture.assertNoBusinessDispatch()
        }
        // This request carries no payable input; MISMATCH-PAYABLE remains unbound here.
    }

    @Test
    public fun fq04HostReconstructedGatewayLooksUpOriginalBodyActorAndIdWithoutFreshPreparation(): Unit = runTest {
        val endpoint = FoodQuantityHostEndpoint()
        val fixture = FoodQuantityGatewayFixture(endpoint)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(foodQuantityIntent()))
        val original = assertNotNull(fixture.journal.entry)
        val originalReadCount = endpoint.reads.size
        endpoint.useFreshSource()
        val reconstructed = fixture.newGateway()
        repeat(2) {
            val unresolved = assertIs<LocalOperationResult.Unresolved>(reconstructed.lookup(FOOD_QUANTITY_OPERATION))
            assertEquals(FOOD_QUANTITY_OPERATION, unresolved.operationId)
            assertEquals(LocalServiceProblem.UNAVAILABLE, unresolved.problem)
            assertEquals(original, fixture.journal.entry)
            assertEquals(originalReadCount, endpoint.reads.size)
            assertHostOriginalStaged(fixture, current = false)
        }
        assertEquals(listOf(original.identity(), original.identity()), endpoint.lookups)
        assertTrue(endpoint.lookups.all { it.originalActorId == FOOD_QUANTITY_OWNER &&
            it.branchId == FOOD_QUANTITY_SCOPE.branchId && it.operationId == FOOD_QUANTITY_OPERATION })
        fixture.assertNoBusinessDispatch()
        // Reconstructing an adapter over volatile test data is not a native client/Master restart.
    }

    @Test
    public fun fq01And02HostCompleteOwnerProjectionKeepsOneQuantityTwoLineAndBothNotes(): Unit = runTest {
        listOf(false to 22000L, true to 28000L).forEach { (current, quotedMinor) ->
            val endpoint = FoodQuantityHostEndpoint().apply {
                orderDetail = foodQuantityHostCompleteDetail(current, quotedMinor)
            }
            val fixture = FoodQuantityGatewayFixture(endpoint)
            val query = LocalFoodUiQuery.Check(FOOD_QUANTITY_ORDER)
            val page = readHostQuantityPage(fixture, query)
            val body = assertIs<LocalFoodUiBody.Check>(page.body)
            assertHostQuantityProjection(body, current, quotedMinor)
            val line = assertNotNull(body.completeCommercialLines()).single()
            assertEquals(FOOD_QUANTITY_LINE, line.lineId)
            assertEquals(Quantity(FOOD_QUANTITY_UNIT, 2, 0), line.quantity)
            assertEquals(2, line.portionCount)
            assertEquals(FOOD_QUANTITY_ITEM_NOTE, line.itemNote)
            assertEquals(FOOD_QUANTITY_SEAT_NOTE, line.seatNote)
            assertNull(page.next)
            assertEquals(listOf(LocalFoodQueryV1.Order(FOOD_QUANTITY_ORDER)), endpoint.orderQueries)
            assertTrue(endpoint.menuQueries.isEmpty())
            assertHostReadOnly(fixture)
        }
    }

    @Test
    public fun fq06HostPartialPagesCannotAggregateTheWholeCommercialLine(): Unit = runTest {
        val endpoint = FoodQuantityHostEndpoint().apply {
            orderDetail = foodQuantityHostCompleteDetail(current = false, quotedMinor = 22000)
            pageSize = 1
        }
        val fixture = FoodQuantityGatewayFixture(endpoint)
        val firstQuery = LocalFoodUiQuery.Check(FOOD_QUANTITY_ORDER)
        val first = readHostQuantityPage(fixture, firstQuery)
        val firstBody = assertIs<LocalFoodUiBody.Check>(first.body)
        val next = assertIs<LocalFoodUiQuery.Check>(assertNotNull(first.next))
        assertEquals(firstQuery.copy(afterPortionId = FOOD_QUANTITY_SECOND), next)
        val second = readHostQuantityPage(fixture, next)
        val secondBody = assertIs<LocalFoodUiBody.Check>(second.body)
        listOf(firstBody, secondBody).forEach { body ->
            assertEquals(2, body.check.portionCount)
            assertEquals(Quantity(FOOD_QUANTITY_UNIT, 1, 0), body.portions.single().quantity)
            assertEquals(FOOD_QUANTITY_LINE, body.portions.single().commercialLineId)
            assertEquals(FOOD_QUANTITY_ITEM_NOTE, body.portions.single().itemNote)
            assertEquals(FOOD_QUANTITY_SEAT_NOTE, body.portions.single().seatNote)
            assertEquals(Money(Currency.EGP, 22000), body.originalQuotedTotal)
            assertNull(body.completeCommercialLines(), "Each response is one selected member, not a complete quantity-two line")
        }
        assertEquals(FOOD_QUANTITY_SECOND, firstBody.portions.single().portionId)
        assertEquals(FOOD_QUANTITY_LINE, secondBody.portions.single().portionId)
        assertNull(second.next)
        assertEquals(listOf(LocalFoodQueryV1.Order(FOOD_QUANTITY_ORDER),
            LocalFoodQueryV1.Order(FOOD_QUANTITY_ORDER, FOOD_QUANTITY_SECOND)), endpoint.orderQueries)
        assertHostReadOnly(fixture)
        // Following read pages does not send a partial quantity or establish stock conservation.
    }
}

private val FOOD_QUANTITY_HOST_LOOKALIKE_ORDER = DomainId("f0000000-0000-4000-8000-000000000002")
private val FOOD_QUANTITY_HOST_LOOKALIKE_PRODUCT = DomainId("f1000000-0000-4000-8000-000000000004")
private val FOOD_QUANTITY_HOST_LOOKALIKE_VARIANT = DomainId("f1000000-0000-4000-8000-000000000005")
private val FOOD_QUANTITY_HOST_LOOKALIKE_OPTION = DomainId("f1000000-0000-4000-8000-000000000006")
private val foodQuantityHostObservedAt = UtcInstant.parseCanonical("2026-10-30T09:00:00Z")
private val foodQuantityHostWire = Json { encodeDefaults = true }

private fun foodQuantityHostRecipe(current: Boolean = false): LocalFoodMenuRecipeV1 {
    val names = foodQuantityNames(current)
    return LocalFoodMenuRecipeV1(FOOD_QUANTITY_VARIANT, Revision(if (current) 2L else 1L), FOOD_QUANTITY_PRODUCT,
        Quantity(FOOD_QUANTITY_UNIT, 1, 0), true, listOf(LocalFoodMenuChoiceV1(FOOD_QUANTITY_CHEESE,
            LocalNamesV1(names.english, names.arabic), listOf(FOOD_QUANTITY_CHEESE), FOOD_QUANTITY_PRODUCT)))
}

private fun foodQuantityHostEmptyDetail(): LocalFoodOrderDetailV1 = LocalFoodOrderDetailV1(
    LocalFoodOrderSummaryV1(FOOD_QUANTITY_ORDER, FOOD_QUANTITY_OWNER, Revision(7), LocalSalesActivity.RESTAURANT,
        LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "T-02", ""), 0, null, null),
    emptyList(), Money(Currency.EGP, 0), false)

private fun foodQuantityHostCompleteDetail(current: Boolean, quotedMinor: Long): LocalFoodOrderDetailV1 {
    val names = foodQuantityNames(current)
    val portions = listOf(FOOD_QUANTITY_SECOND, FOOD_QUANTITY_LINE).map { id ->
        LocalFoodPortionViewV1(id, "${names.english} / ${names.arabic}", Quantity(FOOD_QUANTITY_UNIT, 1, 0),
            listOf(FOOD_QUANTITY_CHEESE), FOOD_QUANTITY_ITEM_NOTE,
            FoodPreparationState(id, Revision(1), FoodPreparationStatus.NOT_SENT),
            lineId = FOOD_QUANTITY_LINE, seatNote = FOOD_QUANTITY_SEAT_NOTE)
    }
    val empty = foodQuantityHostEmptyDetail()
    return empty.copy(summary = empty.summary.copy(portionCount = 2), portions = portions,
        originalQuotedTotal = Money(Currency.EGP, quotedMinor))
}

private fun assertHostPreparationReads(endpoint: FoodQuantityHostEndpoint) {
    assertEquals(listOf(LocalFoodQueryV1.Order(FOOD_QUANTITY_ORDER)), endpoint.orderQueries)
    assertEquals(listOf(LocalFoodMenuQueryV1(recipeId = FOOD_QUANTITY_VARIANT)), endpoint.menuQueries)
    assertEquals(listOf(LocalFoodProfileV1.QUERY, LocalFoodMenuCodecV1.QUERY), endpoint.reads.map { it.query })
}

private fun assertHostOriginalStaged(fixture: FoodQuantityGatewayFixture, current: Boolean) {
    val entry = assertNotNull(fixture.journal.entry)
    assertEquals(1, fixture.journal.stageCalls)
    assertEquals(LocalClientIdentity(FOOD_QUANTITY_SCOPE, FOOD_QUANTITY_OWNER, FOOD_QUANTITY_DEVICE), entry.owner)
    assertEquals(FOOD_QUANTITY_OPERATION, entry.operationId)
    assertEquals(LocalPendingPhase.STAGED, entry.localPhase)
    assertNull(entry.recorded)
    assertFalse(entry.acknowledged)
    val request = entry.request()
    assertEquals(LocalFoodStockCodecV2.COMMAND, request.command, "The supplied empty order uses its normalized preparation route")
    assertEquals(FOOD_QUANTITY_OPERATION, request.operationId)
    assertNull(request.actionApprovalId)
    val actual = assertIs<LocalFoodRequestV1.Add>(assertIs<LocalFoodStockRequestV2.Preparation>(
        LocalFoodStockCodecV2.decode(request.payload)).request)
    val expectedPortions = listOf(FOOD_QUANTITY_SECOND, FOOD_QUANTITY_LINE).map { id ->
        LocalFoodPortionSelectionV1(id, FOOD_QUANTITY_PRODUCT, FOOD_QUANTITY_VARIANT, Revision(if (current) 2L else 1L),
            FOOD_QUANTITY_CHEESE, listOf(FOOD_QUANTITY_CHEESE), Quantity(FOOD_QUANTITY_UNIT, 1, 0),
            FOOD_QUANTITY_ITEM_NOTE, lineId = FOOD_QUANTITY_LINE, seatNote = FOOD_QUANTITY_SEAT_NOTE)
    }
    val expected = LocalFoodRequestV1.Add(FOOD_QUANTITY_ORDER, Revision(7), Revision(if (current) 6L else 5L), expectedPortions)
    assertEquals(expected, actual, "Exact sorted, distinct configured members; neither a hidden partial nor a doubled configured serving")
    assertEquals(LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.Preparation(expected)), request.payload)
    val group = LocalFoodSelectionGroups.group(actual.portions).single()
    assertEquals(FOOD_QUANTITY_LINE, group.lineId)
    assertEquals(Quantity(FOOD_QUANTITY_UNIT, 2, 0), group.totalQuantity)
    assertEquals(2, group.portions.size)
}

private fun assertHostNothingStaged(fixture: FoodQuantityGatewayFixture) {
    assertNull(fixture.journal.entry)
    assertEquals(0, fixture.journal.stageCalls)
    assertTrue(fixture.endpoint.lookups.isEmpty())
    fixture.assertNoBusinessDispatch()
}

private fun assertHostReadOnly(fixture: FoodQuantityGatewayFixture) = assertHostNothingStaged(fixture)

private suspend fun readHostQuantityPage(fixture: FoodQuantityGatewayFixture, query: LocalFoodUiQuery.Check): LocalFoodUiPage {
    val result = assertIs<LocalReadResult.Available>(fixture.gateway.read(LocalWorkflowQuery(LocalWorkflow.FOOD, food = query)))
    assertEquals(LocalWorkflow.FOOD, result.page.workflow)
    assertNull(result.page.nextCursor)
    val page = assertNotNull(result.page.food)
    assertEquals(query, page.query)
    assertEquals(FOOD_QUANTITY_SCOPE.companyId, page.companyId)
    assertEquals(FOOD_QUANTITY_SCOPE.branchId, page.branchId)
    assertEquals(FOOD_QUANTITY_OWNER, page.actorId)
    assertEquals(Revision(10), page.branchRevision, "Controlled reply metadata is not policy activation evidence")
    assertEquals(foodQuantityHostObservedAt, page.observedAt)
    return page
}

private fun assertHostQuantityProjection(body: LocalFoodUiBody.Check, current: Boolean, quotedMinor: Long) {
    val names = foodQuantityNames(current)
    assertEquals(FOOD_QUANTITY_ORDER, body.check.orderId)
    assertEquals(FOOD_QUANTITY_OWNER, body.check.ownerId)
    assertEquals(Revision(7), body.check.orderStorageRevision)
    assertEquals(2, body.check.portionCount)
    assertEquals("", body.check.service.seatNote, "Header-only seat-note readback cannot satisfy the exact line note")
    assertEquals(listOf(FOOD_QUANTITY_SECOND, FOOD_QUANTITY_LINE), body.portions.map { it.portionId })
    body.portions.forEach { portion ->
        assertEquals(FOOD_QUANTITY_LINE, portion.commercialLineId)
        assertEquals(Quantity(FOOD_QUANTITY_UNIT, 1, 0), portion.quantity)
        assertEquals("${names.english} / ${names.arabic}", portion.description)
        assertEquals(FOOD_QUANTITY_ITEM_NOTE, portion.itemNote)
        assertEquals(FOOD_QUANTITY_SEAT_NOTE, portion.seatNote)
        assertEquals(LocalFoodPreparation.NOT_SENT, portion.preparation)
        assertFalse(portion.started)
    }
    assertEquals(Money(Currency.EGP, quotedMinor), body.originalQuotedTotal)
    assertFalse(body.chargeResolutionRequired)
    assertNull(body.financial)
}

/** Typed host-only Data construction. No native handler, authentication, loan or writer is invoked. */
private class FoodQuantityHostEndpoint : ControlledFoodQuantityEndpoint() {
    var catalogRevision = Revision(5)
    var recipes = listOf(foodQuantityHostRecipe())
    var orderDetail = foodQuantityHostEmptyDetail()
    var pageSize = 8
    val menuQueries = mutableListOf<LocalFoodMenuQueryV1>()
    val orderQueries = mutableListOf<LocalFoodQueryV1.Order>()

    fun useFreshSource() {
        catalogRevision = Revision(6)
        recipes = listOf(foodQuantityHostRecipe(current = true))
        view = foodQuantityCurrentView(policyRevision = 11)
    }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        val payload = when (request.query) {
            LocalFoodMenuCodecV1.QUERY -> {
                val query = decode(request.payload, LocalFoodMenuQueryV1.serializer())
                menuQueries += query
                val matching = recipes.filter { query.recipeId == null || it.recipeId == query.recipeId }
                    .sortedBy { it.recipeId.value }
                encode(LocalFoodMenuPageV1(catalogRevision, matching, null), LocalFoodMenuPageV1.serializer())
            }
            LocalFoodProfileV1.QUERY -> {
                val query = assertIs<LocalFoodQueryV1.Order>(decode(request.payload, LocalFoodQueryV1.serializer()))
                orderQueries += query
                val after = query.afterPortionId
                val remaining = orderDetail.portions.sortedBy { it.portionId.value }.filter {
                    after == null || it.portionId.value > after.value
                }
                val chosen = remaining.take(minOf(pageSize, query.limit))
                encode(LocalFoodReadResultV1(detail = orderDetail.copy(portions = chosen),
                    nextAfter = if (remaining.size > chosen.size) chosen.last().portionId else null), LocalFoodReadResultV1.serializer())
            }
            else -> error("Unbound host-only quantity read")
        }
        return ReadReply.Data(payload, Revision(10), foodQuantityHostObservedAt)
    }

    private fun <T> decode(payload: StoredBytes, serializer: KSerializer<T>): T {
        val bytes = payload.copyBytes()
        return try { foodQuantityHostWire.decodeFromString(serializer, bytes.decodeToString()) }
            finally { bytes.fill(0) }
    }

    private fun <T> encode(value: T, serializer: KSerializer<T>): StoredBytes {
        val bytes = foodQuantityHostWire.encodeToString(serializer, value).encodeToByteArray()
        return try { StoredBytes(bytes) } finally { bytes.fill(0) }
    }
}

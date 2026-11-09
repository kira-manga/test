package com.ukkera.pos.sales.store.independent.foodblockedunpaidstatev1

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Bounded codec/normalizer slices of food-blocked-unpaid-state-v1 (FBU-01/02/11/13).
 * Public DTOs are controlled inputs, not authenticated Master observations. Revision 7,
 * IDs, serving quantities, notes and quote amounts are the exact frozen existing cuts.
 * Preparation revision 1 and descriptions are constructor metadata, not history proof.
 * No prepare/project, FoodRow, writer, persistence, native authority or RPC is exercised.
 * All 24 whole FBU cases and production journeys remain NOT_RUN.
 */
class FoodBlockedUnpaidFrozenDiagnosticSlicesTest {
    @Test
    fun fbu11DiagnosticOrderSelectorRetainsLegacyFieldsAndOneToEightBounds() {
        val recipes = UnreadRecipes()
        val handler = LocalFoodCheckKitchenReadHandler(recipes, LocalStockRepository())
        assertEquals(ReadKey("sales.food.check-kitchen.read", 1), handler.key)
        assertEquals(ReadKey("sales.food.read", 1), LocalFoodProfileV1.QUERY)
        for (limit in 1..8) for (after in listOf(null, id("301"))) {
            val query = LocalFoodQueryV1.Order(id("102"), after, limit)
            val legacy = LocalFoodReadCodecV1.encode(query)
            val request = LocalFoodCheckKitchenCodecV1.read(query)
            assertEquals(handler.key, request.query)
            assertEquals(legacy, request.payload)
            assertEquals(legacy, handler.normalize(request.payload))
            assertEquals(query, LocalFoodCheckKitchenCodecV1.query(request.payload))
            val fields = localSalesJson.parseToJsonElement(legacy.copyBytes().decodeToString()).jsonObject
            assertEquals(setOf("type", "orderId", "afterPortionId", "limit"), fields.keys)
            assertEquals(JsonPrimitive("ORDER"), fields["type"])
            assertEquals(JsonPrimitive(id("102").value), fields["orderId"])
            assertEquals(after?.let { JsonPrimitive(it.value) } ?: JsonNull, fields["afterPortionId"])
            assertEquals(JsonPrimitive(limit), fields["limit"])
        }
        assertEquals(0, recipes.calls)
    }

    @Test
    fun fbu13DiagnosticNormalizerRejectsOtherSelectorsAndMalformedOrderWire() {
        val recipes = UnreadRecipes()
        val handler = LocalFoodCheckKitchenReadHandler(recipes, LocalStockRepository())
        val otherSelectors = listOf(LocalFoodQueryV1.Own(), LocalFoodQueryV1.Handoff(id("102")),
            LocalFoodQueryV1.Station(id("071")), LocalFoodQueryV1.Task(id("071"), id("600")))
        otherSelectors.forEach { assertFails { handler.normalize(LocalFoodReadCodecV1.encode(it)) } }
        for (limit in listOf(-1, 0, 9)) {
            val query = LocalFoodQueryV1.Order(id("102"), limit = limit)
            assertFails { LocalFoodCheckKitchenCodecV1.encode(query) }
            assertFails { handler.normalize(localSalesEncode(query, LocalFoodQueryV1.serializer())) }
        }
        val valid = LocalFoodCheckKitchenCodecV1.encode(LocalFoodQueryV1.Order(id("102")))
        val fields = localSalesJson.parseToJsonElement(valid.copyBytes().decodeToString()).jsonObject
        val malformed = listOf(
            JsonObject(fields + ("callerAvailable" to JsonPrimitive(true))).toString(),
            JsonObject(fields + ("orderId" to JsonPrimitive(7))).toString(),
            JsonObject(fields + ("limit" to JsonPrimitive("8"))).toString(),
            " " + valid.copyBytes().decodeToString(),
        )
        malformed.forEach { assertFails { handler.normalize(bytes(it)) } }
        assertEquals(0, recipes.calls)
    }

    @Test
    fun fbu01And02WrapperPreservesExactEmptyAndTwoPortionLegacyDetails() {
        for (fixture in shapes) {
            val food = detail(fixture)
            val observation = LocalFoodCheckKitchenObservationV1(fixture.orderId, Revision(7),
                fixture.portions.size, LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
            val wrapped = LocalFoodCheckKitchenResultV1(food, observation)
            val encoded = encode(wrapped)
            val actual = LocalFoodCheckKitchenCodecV1.result(encoded)
            assertEquals(wrapped, actual)
            assertEquals(encoded, encode(actual))
            assertEquals(localSalesEncode(food, LocalFoodReadResultV1.serializer()),
                localSalesEncode(actual.food, LocalFoodReadResultV1.serializer()))
            val check = assertNotNull(actual.food.detail)
            assertEquals(fixture.orderId, check.summary.orderId)
            assertEquals(id("040"), check.summary.ownerId)
            assertEquals(Revision(7), check.summary.revision)
            assertEquals(fixture.activity, check.summary.activity)
            assertEquals(fixture.portions, check.portions.map { it.portionId })
            assertEquals(Money(Currency.EGP, fixture.quoteMinor), check.originalQuotedTotal)
            assertEquals("UNPAID_NO_COLLECTION", check.collectionState)
            assertTrue(check.portions.all { it.quantity == Quantity(id("240"), 1, 0) &&
                it.itemNote == ITEM_NOTE && it.seatNote == SEAT_NOTE && it.modifierIds.isEmpty() &&
                it.preparation.status == FoodPreparationStatus.NOT_SENT && it.preparation.issueId == null })
            assertEquals(observation, actual.kitchen)
            assertEquals(setOf("food", "kitchen"),
                localSalesJson.parseToJsonElement(encoded.copyBytes().decodeToString()).jsonObject.keys)
        }
    }

    @Test
    fun fbu11AbsentAndExplicitUnknownDiagnosticsNeverBecomeUnavailableOrPositiveSupport() {
        val food = detail(shapes[2])
        val legacyBytes = localSalesEncode(food, LocalFoodReadResultV1.serializer())
        assertEquals(food, LocalFoodReadCodecV1.result(legacyBytes))
        val absentBytes = encode(LocalFoodCheckKitchenResultV1(food))
        val absent = LocalFoodCheckKitchenCodecV1.result(absentBytes)
        assertNull(absent.kitchen)
        assertFalse(localSalesJson.parseToJsonElement(absentBytes.copyBytes().decodeToString()).jsonObject.containsKey("kitchen"))
        val unknown = LocalFoodCheckKitchenObservationV1(id("102"), Revision(7), 2,
            LocalFoodCheckKitchenStatusV1.UNKNOWN)
        assertEquals(unknown, LocalFoodCheckKitchenCodecV1.result(encode(LocalFoodCheckKitchenResultV1(food, unknown))).kitchen)
        assertEquals(listOf("UNKNOWN", "UNAVAILABLE", "NO_UNSENT_WORK"),
            LocalFoodCheckKitchenStatusV1.entries.map { it.name })
        assertFails { LocalFoodReadCodecV1.result(absentBytes) }
        assertFails { LocalFoodCheckKitchenCodecV1.result(legacyBytes) }
    }

    @Test
    fun fbu01EmptyNoUnsentWorkObservationIsAcceptedWithoutChangingTheUnpaidDraft() {
        val food = detail(shapes[0]) // Exact EMPTY_NO_UNSENT_WORK: R0, revision 7, count 0.
        val observation = LocalFoodCheckKitchenObservationV1(id("100"), Revision(7), 0,
            LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
        val actual = LocalFoodCheckKitchenCodecV1.result(encode(LocalFoodCheckKitchenResultV1(food, observation)))
        assertEquals(food, actual.food)
        assertEquals(observation, actual.kitchen)
        val check = assertNotNull(actual.food.detail)
        assertEquals(0, check.summary.portionCount)
        assertTrue(check.portions.isEmpty())
        assertEquals(Money(Currency.EGP, 0), check.originalQuotedTotal)
        assertEquals("UNPAID_NO_COLLECTION", check.collectionState)
        assertNull(check.summary.financialCollectionId)
        assertNull(check.financialHandoff)
    }

    @Test
    fun fbu13DiagnosticRejectsForeignStaleMissingAndInconsistentWholeOrderBindings() {
        val food = detail(shapes[2])
        val valid = encode(LocalFoodCheckKitchenResultV1(food,
            LocalFoodCheckKitchenObservationV1(id("102"), Revision(7), 2, LocalFoodCheckKitchenStatusV1.UNAVAILABLE)))
        val root = localSalesJson.parseToJsonElement(valid.copyBytes().decodeToString()).jsonObject
        val observation = root.getValue("kitchen").jsonObject
        val invalid = listOf(
            observation + ("orderId" to JsonPrimitive(id("103").value)),
            observation + ("orderStorageRevision" to JsonPrimitive("6")),
            observation - "orderStorageRevision",
            observation + ("unsentPortionCount" to JsonPrimitive(-1)),
            observation + ("unsentPortionCount" to JsonPrimitive(3)),
            observation + ("unsentPortionCount" to JsonPrimitive(33)),
            observation + ("status" to JsonPrimitive("NO_UNSENT_WORK")),
        )
        invalid.forEach { fields ->
            assertFails { LocalFoodCheckKitchenCodecV1.result(bytes(JsonObject(root + ("kitchen" to JsonObject(fields))).toString())) }
        }
        val observationValue = assertNotNull(LocalFoodCheckKitchenCodecV1.result(valid).kitchen)
        assertFails { LocalFoodCheckKitchenResultV1(LocalFoodReadResultV1(unavailable = LocalFoodReadUnavailableV2.TARGET_UNAVAILABLE), observationValue) }
        assertFails { LocalFoodCheckKitchenResultV1(food.copy(orders = listOf(assertNotNull(food.detail).summary)), observationValue) }
    }
}

private const val ITEM_NOTE = "Serve together / يُقدَّم معًا"
private const val SEAT_NOTE = "Seat 2 / المقعد ٢"
private fun id(suffix: String) = DomainId("fd300000-0000-4000-8000-${suffix.padStart(12, '0')}")
private fun bytes(text: String) = StoredBytes(text.encodeToByteArray())
private fun encode(value: LocalFoodCheckKitchenResultV1) = localSalesEncode(value, LocalFoodCheckKitchenResultV1.serializer())
private data class Shape(val orderId: DomainId, val activity: LocalSalesActivity, val portions: List<DomainId>, val quoteMinor: Long)
private val shapes = listOf(
    Shape(id("100"), LocalSalesActivity.RESTAURANT, emptyList(), 0),
    Shape(id("101"), LocalSalesActivity.CAFE, emptyList(), 0),
    Shape(id("102"), LocalSalesActivity.RESTAURANT, listOf(id("301"), id("302")), 20_000),
    Shape(id("103"), LocalSalesActivity.CAFE, listOf(id("311"), id("312")), 6_000),
)
private fun detail(shape: Shape): LocalFoodReadResultV1 {
    val portions = shape.portions.map { portionId -> LocalFoodPortionViewV1(portionId,
        "Synthetic serving / حصة تجريبية", Quantity(id("240"), 1, 0), emptyList(), ITEM_NOTE,
        FoodPreparationState(portionId, Revision(1), FoodPreparationStatus.NOT_SENT), shape.portions.first(), SEAT_NOTE) }
    return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(LocalFoodOrderSummaryV1(shape.orderId, id("040"),
        Revision(7), shape.activity, LocalFoodServiceV1(LocalSalesChannel.DINE_IN, id("090").value, SEAT_NOTE),
        shape.portions.size, null, null), portions, Money(Currency.EGP, shape.quoteMinor), false))
}

/** Normalization must never use a recipe, kitchen or accepting history source. */
private class UnreadRecipes : TransactionFoodRecipeSourceV1 {
    var calls = 0
    private fun unexpected(): Nothing { calls++; error("No source or history authority is supplied") }
    override suspend fun prepareSelectionPreview(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision,
        portions: List<LocalFoodPortionSelectionV1>): PreparedFoodSelectionPreviewV1? = unexpected()
    override suspend fun prepare(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeBatchV1? = unexpected()
    override suspend fun currentForSend(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Boolean = unexpected()
    override suspend fun prepareKitchenExecution(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, snapshots: List<LocalFoodRecipeSnapshotV1>): PreparedMutationAdmissionRestriction? = unexpected()
    override suspend fun requireHistorical(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Unit = unexpected()
}

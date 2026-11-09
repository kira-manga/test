package com.ukkera.pos.sales.store.independent.fooddeliverydraftsv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Bounded codec/constructor slices of food-delivery-drafts-v1 only. No Master,
 * authenticated activity, original producer, native store, RPC or business effect
 * is supplied. All 23 whole D cases and ten operational R families remain NOT_RUN.
 */
class FoodDeliveryDraftsFrozenContractSlicesTest {
    private val f = FdpFrozenData

    @Test
    fun d03All34FrozenMetadataTokensValidateOrRefuseWithoutNormalization() {
        assertEquals(34, f.metadataVariants.size)
        for (variant in f.metadataVariants) {
            val caseId = f.caseId(variant)
            val raw = contractBytes(f.rawDraft(variant))
            if (f.validates(variant)) {
                val decoded = assertIs<LocalFoodStockRequestV2.ActivityProtected>(LocalFoodStockCodecV2.decode(raw), caseId)
                val expected = f.attemptedFields(variant)
                assertEquals(contractDetails(expected), decoded.request.deliveryDetails, caseId)
                assertExactFields(expected, assertNotNull(decoded.request.deliveryDetails), caseId)
                assertEquals(raw, LocalFoodStockCodecV2.encode(decoded), caseId)
                variant["utf16CodeUnits"]?.let { units ->
                    val field = variant.getValue("field").jsonPrimitive.content
                    assertEquals(units.jsonPrimitive.int, expected.getValue(field).length, caseId)
                }
                assertEquals("DEL-SYN-030", decoded.request.service.reference, caseId)
                assertEquals("", decoded.request.service.seatNote, caseId)
            } else {
                assertFailsWith<IllegalArgumentException>(caseId) { LocalFoodStockCodecV2.decode(raw) }
                val fields = f.attemptedFields(variant)
                if (fields.keys.containsAll(listOf("recipient", "contact", "address")))
                    assertFailsWith<IllegalArgumentException>(caseId) { contractDetails(fields) }
            }
        }
        assertNotEquals(f.attemptedFields(f.metadataVariants[25]).getValue("recipient"),
            f.attemptedFields(f.metadataVariants[26]).getValue("recipient"), "Composed and decomposed inputs are distinct")
    }

    @Test
    fun d03All99FrozenAsciiControlFieldAttemptsRefuseBothConstructorAndCodec() {
        val fields = f.objectAt("deliveryMetadataTechnicalRules").getValue("controlCrossProduct").jsonObject
            .getValue("fields").jsonArray.map { it.jsonPrimitive.content }
        val codepoints = f.prohibitedCodepoints()
        assertEquals(33, codepoints.size)
        assertEquals(listOf("recipient", "contact", "address"), fields)
        var attempts = 0
        for (field in fields) for (codepoint in codepoints) {
            val caseId = "FDP-D03-C-${field.uppercase()}-${codepoint.toString(16).uppercase().padStart(4, '0')}"
            val values = f.baseFields() + (field to "A${codepoint.toChar()}B")
            assertFailsWith<IllegalArgumentException>(caseId) { contractDetails(values) }
            val variant = JsonObject(mapOf("deliveryDetails" to JsonObject(values.mapValues { JsonPrimitive(it.value) })))
            assertFailsWith<IllegalArgumentException>(caseId) { LocalFoodStockCodecV2.decode(contractBytes(f.rawDraft(variant))) }
            attempts++
        }
        assertEquals(99, attempts)
    }

    @Test
    fun d01D04D19DeliveryAndNeutralReferenceCommandsHaveExactFrozenCanonicalBytes() {
        for (canonical in listOf(f.canonicalDraft, f.neutralDraft)) {
            val input = Json.parseToJsonElement(canonical).jsonObject.getValue("request").jsonObject
            val reference = input.getValue("service").jsonObject.getValue("reference").jsonPrimitive.content
            val draft = LocalFoodDraftCreateV1(contractId("orderIds", "deliveryRestaurant"), LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(LocalSalesChannel.DELIVERY, reference, ""), contractDetails(f.baseFields()))
            val command = LocalFoodStockCodecV2.command(OperationId(f.text("operationIds", "create")),
                LocalFoodStockRequestV2.ActivityProtected(draft))
            assertEquals("sales.food.stock", command.command.name)
            assertEquals(2, command.command.version)
            assertEquals(OperationId(f.text("operationIds", "create")), command.operationId)
            assertEquals(canonical, command.payload.copyBytes().decodeToString())
            assertNull(command.actionApprovalId)
            assertEquals(draft, assertIs<LocalFoodStockRequestV2.ActivityProtected>(LocalFoodStockCodecV2.decode(command.payload)).request)
            assertExactFields(f.baseFields(), assertNotNull(draft.deliveryDetails))
            assertEquals("", draft.service.seatNote)
        }
    }

    @Test
    fun d02CafeRequestKeepsItsExactActivityOrderAndDeliveryChannel() {
        val draft = LocalFoodDraftCreateV1(contractId("orderIds", "deliveryCafe"), LocalSalesActivity.CAFE,
            LocalFoodServiceV1(LocalSalesChannel.DELIVERY, "DEL-SYN-030", ""), contractDetails(f.baseFields()))
        val bytes = LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.ActivityProtected(draft))
        val decoded = assertIs<LocalFoodStockRequestV2.ActivityProtected>(LocalFoodStockCodecV2.decode(bytes)).request
        assertEquals(draft, decoded)
        assertEquals(contractId("orderIds", "deliveryCafe"), decoded.orderId)
        assertEquals(LocalSalesActivity.CAFE, decoded.activity)
        assertEquals(LocalSalesChannel.DELIVERY, decoded.service.channel)
        assertExactFields(f.baseFields(), assertNotNull(decoded.deliveryDetails))
        // Codec retention does not qualify the missing-exact-activity admission variants.
    }

    @Test
    fun d06GuardedAddHasExactFrozenPortionsRevisionsNotesAndNoReplacementMetadata() {
        val request = contractAdd()
        val bytes = LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.ActivityProtectedAdd(request))
        assertEquals(f.canonicalAdd, bytes.copyBytes().decodeToString())
        val decoded = assertIs<LocalFoodStockRequestV2.ActivityProtectedAdd>(LocalFoodStockCodecV2.decode(bytes)).request
        assertEquals(request, decoded)
        assertEquals(Revision(20), decoded.expectedOrderRevision)
        assertEquals(Revision(40), decoded.expectedCatalogRevision)
        assertEquals(listOf(contractId("portionIds", "selectedFirst"), contractId("portionIds", "selectedSecond")),
            decoded.portions.map { it.portionId })
        assertTrue(decoded.portions.all { it.expectedRecipeRevision == Revision(7) && it.quantity == contractOne() &&
            it.modifierIds.isEmpty() && it.itemNote == "" && it.lineId == null && it.seatNote == "" })
        val root = Json.parseToJsonElement(f.canonicalAdd).jsonObject
        assertFalse("deliveryDetails" in root.getValue("request").jsonObject)
        root.getValue("request").jsonObject.getValue("portions").jsonArray.forEach { portion ->
            assertEquals(JsonPrimitive(""), portion.jsonObject.getValue("itemNote"))
            assertFalse("seatNote" in portion.jsonObject)
        }
        // This is not saved Add state, historical quotes, stock, collection or final payable.
    }

    @Test
    fun d19D21NonDeliveryPayloadsOmitMetadataAndPresentInapplicableDetailsCannotDecode() {
        val nonDelivery = f.objectAt("compatibility").getValue("canonicalNonDeliveryDraftPayloads").jsonObject
        for ((_, fixture) in nonDelivery) {
            val canonical = fixture.jsonObject.getValue("canonicalPayloadJson").jsonPrimitive.content
            val root = Json.parseToJsonElement(canonical).jsonObject
            val input = root.getValue("request").jsonObject
            val service = input.getValue("service").jsonObject
            val draft = LocalFoodDraftCreateV1(DomainId(input.getValue("orderId").jsonPrimitive.content),
                LocalSalesActivity.RESTAURANT, LocalFoodServiceV1(LocalSalesChannel.valueOf(service.getValue("channel").jsonPrimitive.content),
                    service.getValue("reference").jsonPrimitive.content, ""))
            val bytes = LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.ActivityProtected(draft))
            assertEquals(canonical, bytes.copyBytes().decodeToString())
            assertEquals(draft, assertIs<LocalFoodStockRequestV2.ActivityProtected>(LocalFoodStockCodecV2.decode(bytes)).request)
            assertFalse("deliveryDetails" in Json.parseToJsonElement(bytes.copyBytes().decodeToString()).jsonObject.getValue("request").jsonObject)
            assertNull(draft.deliveryDetails)
            val invalid = JsonObject(root + ("request" to JsonObject(input + ("deliveryDetails" to f.baseDetails))))
            assertFailsWith<IllegalArgumentException> { LocalFoodStockCodecV2.decode(contractBytes(invalid.toString())) }
            assertFailsWith<IllegalArgumentException> { draft.copy(deliveryDetails = contractDetails(f.baseFields())) }
        }
        assertFailsWith<IllegalArgumentException> {
            LocalFoodRequestV1.Create(contractId("orderIds", "deliveryRestaurant"), LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(LocalSalesChannel.DELIVERY, "DEL-SYN-030", ""), Revision(40), contractAdd().portions)
        }
        // No retained legacy original, older-client qualification or downgrade is supplied.
    }

    @Test
    fun d20ExactSelectorObservationAndAdditiveWrapperOrderingPreserveLegacyFoodInput() {
        val query = LocalFoodQueryV1.Order(contractId("orderIds", "deliveryRestaurant"), null, 8)
        val request = LocalFoodCheckKitchenCodecV1.read(query)
        assertEquals("sales.food.check-kitchen.read", request.query.name)
        assertEquals(1, request.query.version)
        assertEquals(f.canonicalSelector, request.payload.copyBytes().decodeToString())
        val food = contractFood()
        val delivery = LocalFoodDeliveryObservationV1(query.orderId, Revision(30), contractDetails(f.baseFields()))
        val wrapper = LocalFoodCheckKitchenResultV1(food,
            LocalFoodCheckKitchenObservationV1(query.orderId, Revision(30), 2, LocalFoodCheckKitchenStatusV1.UNAVAILABLE), delivery)
        val bytes = localSalesEncode(wrapper, LocalFoodCheckKitchenResultV1.serializer())
        val root = Json.parseToJsonElement(bytes.copyBytes().decodeToString()).jsonObject
        assertEquals(listOf("food", "kitchen", "delivery"), root.keys.toList())
        assertEquals(f.canonicalObservation, root.getValue("delivery").toString())
        assertTrue(root.getValue("delivery").jsonObject.getValue("orderStorageRevision").jsonPrimitive.isString)
        val legacy = localSalesEncode(food, LocalFoodReadResultV1.serializer())
        assertEquals(Json.parseToJsonElement(legacy.copyBytes().decodeToString()), root.getValue("food"))
        assertEquals(wrapper, LocalFoodCheckKitchenCodecV1.result(bytes))
        assertEquals(food, LocalFoodReadCodecV1.result(legacy))
        val missingKitchen = localSalesEncode(wrapper.copy(kitchen = null), LocalFoodCheckKitchenResultV1.serializer())
        assertEquals(listOf("food", "delivery"), Json.parseToJsonElement(missingKitchen.copyBytes().decodeToString()).jsonObject.keys.toList())
        val nonDelivery = LocalFoodCheckKitchenResultV1(contractFood(LocalSalesChannel.TAKEAWAY))
        val nonDeliveryRoot = Json.parseToJsonElement(localSalesEncode(nonDelivery,
            LocalFoodCheckKitchenResultV1.serializer()).copyBytes().decodeToString()).jsonObject
        assertEquals(listOf("food"), nonDeliveryRoot.keys.toList())
        assertFalse("delivery" in nonDeliveryRoot)
        // Complete legacy FOOD bytes/schema and admitted protected-read effects remain unbound.
    }

    @Test
    fun d20MissingMalformedForeignStaleAndNonDeliveryMetadataRefuseUsableWrapper() {
        for ((caseId, raw) in contractInvalidWrappers())
            assertFailsWith<IllegalArgumentException>(caseId) { LocalFoodCheckKitchenCodecV1.result(contractBytes(raw)) }
    }
}

private fun contractBytes(value: String): StoredBytes = StoredBytes(value.encodeToByteArray())
private fun contractId(group: String, key: String): DomainId = DomainId(FdpFrozenData.text(group, key))
private fun contractOne(): Quantity = Quantity(contractId("catalog", "servingUnitId"), 1, 0)
private fun contractDetails(fields: Map<String, String>): LocalFoodDeliveryDetailsV1 =
    LocalFoodDeliveryDetailsV1(fields.getValue("recipient"), fields.getValue("contact"), fields.getValue("address"))
private fun assertExactFields(expected: Map<String, String>, actual: LocalFoodDeliveryDetailsV1, message: String? = null) {
    assertEquals(expected.getValue("recipient"), actual.recipient, message)
    assertEquals(expected.getValue("contact"), actual.contact, message)
    assertEquals(expected.getValue("address"), actual.address, message)
}

private fun contractAdd(): LocalFoodRequestV1.Add = LocalFoodRequestV1.Add(contractId("orderIds", "deliveryRestaurant"),
    Revision(20), Revision(40), listOf("selectedFirst", "selectedSecond").map { key ->
        LocalFoodPortionSelectionV1(contractId("portionIds", key), contractId("catalog", "burgerItemId"),
            contractId("catalog", "burgerRecipeId"), Revision(7), contractId("catalog", "defaultChoiceId"), emptyList(), contractOne(), "")
    })

/** Unsent public response constructors only; preparation revision/name are not original evidence. */
private fun contractFood(channel: LocalSalesChannel = LocalSalesChannel.DELIVERY): LocalFoodReadResultV1 {
    val order = contractId("orderIds", if (channel == LocalSalesChannel.DELIVERY) "deliveryRestaurant" else "takeaway")
    val reference = if (channel == LocalSalesChannel.DELIVERY) "DEL-SYN-030" else "PU-SYN-030"
    val portions = listOf("selectedFirst", "selectedSecond").map { key ->
        val id = contractId("portionIds", key)
        LocalFoodPortionViewV1(id, "Synthetic quoted burger", contractOne(), emptyList(), "",
            FoodPreparationState(id, Revision(1), FoodPreparationStatus.NOT_SENT))
    }
    return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
        LocalFoodOrderSummaryV1(order, DomainId(FdpFrozenData.nestedText("actors", "ownerCashier", "actorId")), Revision(30),
            LocalSalesActivity.RESTAURANT, LocalFoodServiceV1(channel, reference, ""), 2, null, null),
        portions, Money(Currency.EGP, 22_800), true))
}

/** Raw invalid DTO attempts are not historical frames, originals or protected service replies. */
private fun contractInvalidWrappers(): List<Pair<String, String>> {
    val f = FdpFrozenData
    val order = contractId("orderIds", "deliveryRestaurant")
    val wrapper = LocalFoodCheckKitchenResultV1(contractFood(),
        LocalFoodCheckKitchenObservationV1(order, Revision(30), 2, LocalFoodCheckKitchenStatusV1.UNAVAILABLE),
        LocalFoodDeliveryObservationV1(order, Revision(30), contractDetails(f.baseFields())))
    val root = Json.parseToJsonElement(localSalesEncode(wrapper, LocalFoodCheckKitchenResultV1.serializer())
        .copyBytes().decodeToString()).jsonObject
    val delivery = root.getValue("delivery").jsonObject
    val missingRecipient = f.metadataVariants[2].getValue("deliveryDetails")
    val nonDeliveryFood = Json.parseToJsonElement(localSalesEncode(contractFood(LocalSalesChannel.TAKEAWAY),
        LocalFoodReadResultV1.serializer()).copyBytes().decodeToString())
    val nonDeliveryOrder = JsonPrimitive(f.text("orderIds", "takeaway"))
    val nonDeliveryKitchen = JsonObject(root.getValue("kitchen").jsonObject + ("orderId" to nonDeliveryOrder))
    return listOf(
        f.caseId(f.ownCheckVariants[1]) to JsonObject(root - "delivery").toString(),
        f.caseId(f.ownCheckVariants[2]) to JsonObject(root + ("delivery" to JsonObject(delivery + ("details" to missingRecipient)))).toString(),
        f.caseId(f.ownCheckVariants[3]) to JsonObject(root + ("delivery" to JsonObject(delivery +
            ("orderId" to JsonPrimitive(f.text("orderIds", "otherOwner")))))).toString(),
        f.caseId(f.ownCheckVariants[4]) to JsonObject(root + ("delivery" to JsonObject(delivery +
            ("orderStorageRevision" to JsonPrimitive("20"))))).toString(),
        f.caseId(f.ownCheckVariants[5]) to JsonObject(root + ("food" to nonDeliveryFood) + ("kitchen" to nonDeliveryKitchen) +
            ("delivery" to JsonObject(delivery + ("orderId" to nonDeliveryOrder)))).toString(),
    )
}

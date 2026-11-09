package com.ukkera.pos.sales.store.independent.foodcurrentpayablev2

import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV2
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.sales.store.LocalFoodReadCodecV1
import com.ukkera.pos.sales.store.LocalFoodReadResultV1
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FCPV2-001/002 and bounded old-protocol subassertions; no authorization or custody proof. */
class FoodCurrentPayableFrozenCodecSlicesTest {
    private val f = FoodCurrentPayableFrozenCodecInputs

    @Test
    fun fcpv2001_allEightNullableAndOptionalFieldVariantsRemainDistinct() {
        for (kitchen in listOf(false, true)) for (delivery in listOf(false, true)) {
            for (current in listOf(Money(Currency.EGP, 39_000), null)) {
                val bound = f.v2(kitchen, delivery, current)
                val encoded = localSalesEncode(bound, LocalFoodCheckKitchenResultV2.serializer())
                val frame = Json.parseToJsonElement(f.text(encoded)).jsonObject
                assertTrue("currentUnpaidPayable" in frame, "FCPV2-001: nullable is mandatory")
                assertEquals(kitchen, "kitchen" in frame, "FCPV2-001: original kitchen optionality")
                assertEquals(delivery, "delivery" in frame, "FCPV2-001: original delivery optionality")
                assertEquals(if (current == null) JsonNull else
                    Json.parseToJsonElement("{\"currency\":\"EGP\",\"minor\":\"39000\"}"),
                    frame.getValue("currentUnpaidPayable"), "FCPV2-001: existing Money representation")

                val decoded = LocalFoodCheckKitchenCodecV2.result(encoded)
                assertEquals(current, decoded.currentUnpaidPayable)
                assertEquals(bound.food, decoded.food)
                assertEquals(bound.kitchen, decoded.kitchen)
                assertEquals(bound.delivery, decoded.delivery)
                assertEquals(f.text(encoded), f.text(localSalesEncode(decoded,
                    LocalFoodCheckKitchenResultV2.serializer())), "FCPV2-001: exact codec round trip")
            }
        }
        // Delivery-present frames bind the existing DELIVERY service requirement. The food
        // binding stays unchanged between known/null variants, rather than inventing V1 optionality.
    }

    @Test
    fun fcpv2002_missingUnknownAndMalformedFieldsRefuse() {
        val encoded = localSalesEncode(f.v2(true, false, Money(Currency.EGP, 39_000)),
            LocalFoodCheckKitchenResultV2.serializer())
        val valid = Json.parseToJsonElement(f.text(encoded)).jsonObject
        val mutations = linkedMapOf(
            "FCPV2-002(a): missing current" to JsonObject(valid - "currentUnpaidPayable"),
            "FCPV2-002(b): missing food" to JsonObject(valid - "food"),
            "FCPV2-002(c): unknown top-level field" to
                JsonObject(valid + ("unexpectedCurrentPayableField" to JsonPrimitive(true))),
            "FCPV2-002(d): Money cannot be a boolean" to withMoney(valid, JsonPrimitive(true)),
            "FCPV2-002(e): missing required minor constituent" to
                withMoney(valid, JsonObject(mapOf("currency" to JsonPrimitive("EGP")))),
            "FCPV2-002(f): minor must be a canonical decimal string" to
                withMoney(valid, JsonObject(mapOf("currency" to JsonPrimitive("EGP"), "minor" to JsonPrimitive(39_000)))),
            "FCPV2-002(f): exponent is not canonical minor money" to
                withMoney(valid, JsonObject(mapOf("currency" to JsonPrimitive("EGP"), "minor" to JsonPrimitive("3.9e4")))),
        )
        for ((assertion, frame) in mutations) {
            assertFails(assertion) { LocalFoodCheckKitchenCodecV2.result(f.bytes(frame.toString())) }
        }
        val explicitNull = withMoney(valid, JsonNull)
        assertEquals(null, LocalFoodCheckKitchenCodecV2.result(f.bytes(explicitNull.toString())).currentUnpaidPayable,
            "FCPV2-001/002: valid explicit null differs from missing or malformed")
    }

    @Test
    fun fcpv2003_orderSelectorAndProtocolVersionsRetainExistingPublicRepresentation() {
        val selector = LocalFoodQueryV1.Order(f.order, limit = 1)
        // Existing selector contract: LOCAL-FOOD-DELIVERY-DRAFTS.md, selector binding.
        val expected = "{\"type\":\"ORDER\",\"orderId\":\"${f.order.value}\",\"afterPortionId\":null,\"limit\":1}"
        val v1 = LocalFoodCheckKitchenCodecV1.read(selector)
        val v2 = LocalFoodCheckKitchenCodecV2.read(selector)
        val legacy = LocalFoodReadCodecV1.read(selector)
        assertEquals("sales.food.check-kitchen.read", v1.query.name)
        assertEquals(1, v1.query.version)
        assertEquals("sales.food.check-kitchen.read", v2.query.name)
        assertEquals(2, v2.query.version)
        assertEquals("sales.food.read", legacy.query.name)
        assertEquals(1, legacy.query.version)
        for (request in listOf(v1, v2, legacy)) assertEquals(expected, f.text(request.payload))
    }

    @Test
    fun fcpv2003_oldResultCodecsAcceptTheirOwnShapeWithoutCurrentField() {
        for (kitchen in listOf(false, true)) for (delivery in listOf(false, true)) {
            val bound = f.v2(kitchen, delivery, null)
            val old = LocalFoodCheckKitchenResultV1(bound.food, bound.kitchen, bound.delivery)
            val v1Bytes = localSalesEncode(old, LocalFoodCheckKitchenResultV1.serializer())
            val oldFrame = Json.parseToJsonElement(f.text(v1Bytes)).jsonObject
            assertFalse("currentUnpaidPayable" in oldFrame, "FCPV2-003: no V2 field inserted into V1")
            assertEquals(kitchen, "kitchen" in oldFrame)
            assertEquals(delivery, "delivery" in oldFrame)
            assertEquals(old, LocalFoodCheckKitchenCodecV1.result(v1Bytes))

            val legacyBytes = localSalesEncode(bound.food, LocalFoodReadResultV1.serializer())
            assertFalse("currentUnpaidPayable" in Json.parseToJsonElement(f.text(legacyBytes)).jsonObject)
            assertEquals(bound.food, LocalFoodReadCodecV1.result(legacyBytes))
        }
        // This checks exclusion of the additive field and existing codec shapes, not an
        // independent pre-V2 result golden baseline or actual old-version protected reads.
    }

    private fun withMoney(frame: JsonObject, money: JsonElement): JsonObject =
        JsonObject(frame + ("currentUnpaidPayable" to money))
}

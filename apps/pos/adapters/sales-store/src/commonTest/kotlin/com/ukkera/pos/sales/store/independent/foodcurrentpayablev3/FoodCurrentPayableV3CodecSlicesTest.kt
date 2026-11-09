package com.ukkera.pos.sales.store.independent.foodcurrentpayablev3

import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV3
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV3
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.sales.store.LocalFoodReadCodecV1
import com.ukkera.pos.sales.store.LocalFoodReadResultV1
import com.ukkera.pos.sales.store.localSalesEncode
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Supporting FCP3-007/008 wire contracts; no Master, original source or admission qualification. */
class FoodCurrentPayableV3CodecSlicesTest {
    private val f = FoodCurrentPayableV3CodecInputs

    @Test
    fun fcp3008_v3KnownNullAndExistingOptionalFieldsRoundTripExactly() {
        for (kitchen in listOf(false, true)) for (delivery in listOf(false, true)) {
            for (amount in listOf(f.egp(19_403), null)) {
                val input = f.v3(kitchen, delivery, amount)
                val bytes = localSalesEncode(input, LocalFoodCheckKitchenResultV3.serializer())
                val frame = Json.parseToJsonElement(f.text(bytes)).jsonObject
                assertEquals(setOf("food", "currentUnpaidPayable") +
                    (if (kitchen) setOf("kitchen") else emptySet()) + (if (delivery) setOf("delivery") else emptySet()), frame.keys)
                assertEquals(if (amount == null) JsonNull else money("19403"), frame.getValue("currentUnpaidPayable"))
                assertEquals(kitchen, "kitchen" in frame)
                assertEquals(delivery, "delivery" in frame)
                val decoded = LocalFoodCheckKitchenCodecV3.result(bytes)
                assertEquals(input, decoded)
                assertEquals(f.text(bytes), f.text(localSalesEncode(decoded, LocalFoodCheckKitchenResultV3.serializer())))
            }
        }
        val served = LocalFoodCheckKitchenResultV3(f.food(visible = listOf(5), next = null), currentUnpaidPayable = f.egp(19_403))
        assertEquals(f.egp(19_403), LocalFoodCheckKitchenCodecV3.result(
            localSalesEncode(served, LocalFoodCheckKitchenResultV3.serializer())).currentUnpaidPayable)
        val empty = LocalFoodCheckKitchenResultV3(f.empty(), currentUnpaidPayable = f.egp(0))
        assertEquals(f.egp(0), LocalFoodCheckKitchenCodecV3.result(
            localSalesEncode(empty, LocalFoodCheckKitchenResultV3.serializer())).currentUnpaidPayable)
    }

    @Test
    fun fcp3008_missingUnknownAndMalformedMoneyNeverBecomeAcceptingNull() {
        val valid = Json.parseToJsonElement(f.text(localSalesEncode(f.v3(true, false, f.egp(19_403)),
            LocalFoodCheckKitchenResultV3.serializer()))).jsonObject
        val mutations = linkedMapOf(
            "missing current" to JsonObject(valid - "currentUnpaidPayable"),
            "extra top-level field" to JsonObject(valid + ("unexpected" to JsonPrimitive(true))),
            "extra Money field" to withMoney(valid, JsonObject(money("19403") + ("unexpected" to JsonPrimitive(true)))),
            "boolean amount" to withMoney(valid, JsonPrimitive(true)),
            "string amount" to withMoney(valid, JsonPrimitive("19403")),
            "missing currency" to withMoney(valid, JsonObject(mapOf("minor" to JsonPrimitive("19403")))),
            "missing minor" to withMoney(valid, JsonObject(mapOf("currency" to JsonPrimitive("EGP")))),
            "numeric minor" to withMoney(valid, money(JsonPrimitive(19_403))),
            "fractional minor" to withMoney(valid, money("19403.0")),
            "overflow minor" to withMoney(valid, money("9223372036854775808")),
            "mixed order currency" to withMoney(valid, JsonObject(mapOf("currency" to JsonPrimitive("USD"), "minor" to JsonPrimitive("19403")))),
            "negative current" to withMoney(valid, money("-1")),
            "historical overflow" to withMoney(valid, money("30804")),
        )
        for ((label, frame) in mutations) assertFails("FCP3-008 $label refuses") {
            LocalFoodCheckKitchenCodecV3.result(f.bytes(frame.toString()))
        }
        assertNull(LocalFoodCheckKitchenCodecV3.result(f.bytes(withMoney(valid, JsonNull).toString())).currentUnpaidPayable,
            "FCP3-008 explicit valid null retains its different meaning")
    }

    @Test
    fun fcp3008_requiredNestedObservationsDuplicateFieldsAndMalformedFramesRetainStrictValidation() {
        val validText = f.text(localSalesEncode(f.v3(true, false, f.egp(19_403)), LocalFoodCheckKitchenResultV3.serializer()))
        val valid = Json.parseToJsonElement(validText).jsonObject
        val kitchen = valid.getValue("kitchen").jsonObject
        val delivered = Json.parseToJsonElement(f.text(localSalesEncode(f.v3(false, true, f.egp(19_403)),
            LocalFoodCheckKitchenResultV3.serializer()))).jsonObject
        val malformed = listOf(
            "missing food" to JsonObject(valid - "food").toString(),
            "boolean food" to JsonObject(valid + ("food" to JsonPrimitive(true))).toString(),
            "boolean kitchen" to JsonObject(valid + ("kitchen" to JsonPrimitive(true))).toString(),
            "kitchen wrong order" to JsonObject(valid + ("kitchen" to JsonObject(kitchen +
                ("orderId" to JsonPrimitive(f.emptyOrder.value))))).toString(),
            "kitchen wrong revision" to JsonObject(valid + ("kitchen" to JsonObject(kitchen +
                ("orderStorageRevision" to JsonPrimitive("42"))))).toString(),
            "boolean delivery" to JsonObject(delivered + ("delivery" to JsonPrimitive(true))).toString(),
            "duplicate current" to validText.replace("\"currentUnpaidPayable\":", "\"currentUnpaidPayable\":null,\"currentUnpaidPayable\":"),
            "duplicate Money minor" to validText.replace("\"minor\":\"19403\"", "\"minor\":\"19403\",\"minor\":\"19403\""),
            "truncated output" to validText.dropLast(1),
            // Whitespace alone is invalid JSON at any length; this is not an independent size-only guard proof.
            "overlong malformed input" to " ".repeat(65_537),
        )
        for ((label, text) in malformed) assertFails("FCP3-008 $label refuses") {
            LocalFoodCheckKitchenCodecV3.result(f.bytes(text))
        }
    }

    @Test
    fun fcp3007_008_existingSelectorWireAndPageBoundsAreUnchangedAcrossVersions() {
        for (limit in listOf(1, 8)) {
            val query = LocalFoodQueryV1.Order(f.order, limit = limit)
            val expected = "{\"type\":\"ORDER\",\"orderId\":\"${f.order.value}\",\"afterPortionId\":null,\"limit\":$limit}"
            val requests = listOf(LocalFoodCheckKitchenCodecV3.read(query), LocalFoodCheckKitchenCodecV2.read(query),
                LocalFoodCheckKitchenCodecV1.read(query), LocalFoodReadCodecV1.read(query))
            assertEquals(listOf(3, 2, 1, 1), requests.map { it.query.version })
            assertEquals(listOf("sales.food.check-kitchen.read", "sales.food.check-kitchen.read", "sales.food.check-kitchen.read", "sales.food.read"),
                requests.map { it.query.name })
            for (request in requests) assertEquals(expected, f.text(request.payload))
        }
        for (limit in listOf(0, 9)) {
            val invalid = LocalFoodQueryV1.Order(f.order, limit = limit)
            assertFails("FCP3-007 V3 selector page $limit") { LocalFoodCheckKitchenCodecV3.read(invalid) }
            assertFails("FCP3-007 V2 unchanged selector page $limit") { LocalFoodCheckKitchenCodecV2.read(invalid) }
            assertFails("FCP3-007 V1 unchanged selector page $limit") { LocalFoodCheckKitchenCodecV1.read(invalid) }
            assertFails("FCP3-007 legacy unchanged selector page $limit") { LocalFoodReadCodecV1.read(invalid) }
        }
    }

    @Test
    fun fcp3008_v2RetainsKnownPresendAndEmptyButPreparedMixIsNull() {
        val pre = LocalFoodCheckKitchenResultV2(f.food(prepared = false), currentUnpaidPayable = f.egp(19_403))
        val encodedPre = localSalesEncode(pre, LocalFoodCheckKitchenResultV2.serializer())
        assertEquals(f.egp(19_403), LocalFoodCheckKitchenCodecV2.result(encodedPre).currentUnpaidPayable)
        val empty = LocalFoodCheckKitchenResultV2(f.empty(), currentUnpaidPayable = f.egp(0))
        assertEquals(f.egp(0), LocalFoodCheckKitchenCodecV2.result(
            localSalesEncode(empty, LocalFoodCheckKitchenResultV2.serializer())).currentUnpaidPayable)
        val mixed = LocalFoodCheckKitchenResultV2(f.food(), currentUnpaidPayable = null)
        val encodedMixed = localSalesEncode(mixed, LocalFoodCheckKitchenResultV2.serializer())
        assertTrue("currentUnpaidPayable" in Json.parseToJsonElement(f.text(encodedMixed)).jsonObject)
        assertNull(LocalFoodCheckKitchenCodecV2.result(encodedMixed).currentUnpaidPayable)
        assertFails("FCP3-008 V2 never acquires prepared-work current validation") {
            LocalFoodCheckKitchenResultV2(f.food(visible = listOf(5), next = null), currentUnpaidPayable = f.egp(19_403))
        }
        // These DTO assertions do not prove a genuine PRE cancellation or complete MIX source history.
    }

    @Test
    fun fcp3008_v1AndLegacyKeepTheirOldFieldsAndOrdinaryPreparationObservations() {
        val food = f.food(visible = listOf(3, 4, 5), next = f.portion(5))
        val old = LocalFoodCheckKitchenResultV1(food)
        val oldBytes = localSalesEncode(old, LocalFoodCheckKitchenResultV1.serializer())
        assertFalse("currentUnpaidPayable" in Json.parseToJsonElement(f.text(oldBytes)).jsonObject)
        assertEquals(old, LocalFoodCheckKitchenCodecV1.result(oldBytes))
        val legacyBytes = localSalesEncode(food, LocalFoodReadResultV1.serializer())
        assertFalse("currentUnpaidPayable" in Json.parseToJsonElement(f.text(legacyBytes)).jsonObject)
        assertEquals(food, LocalFoodReadCodecV1.result(legacyBytes))
        assertEquals(food.detail?.portions?.map { it.preparation }, LocalFoodCheckKitchenCodecV1.result(oldBytes).food.detail?.portions?.map { it.preparation })
    }

    private fun money(minor: String) = money(JsonPrimitive(minor))
    private fun money(minor: JsonElement) = JsonObject(mapOf("currency" to JsonPrimitive("EGP"), "minor" to minor))
    private fun withMoney(frame: JsonObject, value: JsonElement) = JsonObject(frame + ("currentUnpaidPayable" to value))
}

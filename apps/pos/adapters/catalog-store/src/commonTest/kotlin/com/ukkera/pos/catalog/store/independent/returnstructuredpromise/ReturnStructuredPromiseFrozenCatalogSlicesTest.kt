package com.ukkera.pos.catalog.store.independent.returnstructuredpromise

import com.ukkera.pos.catalog.store.LocalRetailReturnPromiseV1
import com.ukkera.pos.catalog.store.decodeValue
import com.ukkera.pos.catalog.store.encodeValue
import com.ukkera.pos.catalog.store.returnPromiseDescriptor
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseDescriptorV2
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Frozen v1 L1 original-value/member and actual catalog-descriptor slices.
 * No full signed host, legacy whole-host golden, native authoring, durable capture,
 * current rights activation, source authority or original RET/Q-gate pass is supplied.
 * U-HOST/U-SIGN/U-RAW-NULL/U-LEGAL/U-JOIN/U-LEGACY-GOLDEN remain UNBOUND.
 */
class ReturnStructuredPromiseFrozenCatalogSlicesTest {
    private val f = FrozenReturnStructuredPromiseInputs

    @Test
    fun rspD01To06_allEightValidVectorsPreserveOriginalRolesAndOptionalMembers() {
        for (vector in f.rows("validInputs")) {
            val id = f.text(vector, "id")
            assertValue(promise(vector.getValue("input").jsonObject),
                vector.getValue("expectedExtension").jsonObject, f.base, id)
        }
    }

    @Test
    fun rspD07_allFifteenPresenceVectorsUseFrozenWholeValueOutcomes() {
        for (vector in f.rows("presenceVectors")) {
            val id = "RSP-V1-D07/" + f.text(vector, "id")
            val input = vector.getValue("input").jsonObject
            if (f.text(vector, "outcome") == "VALID") {
                assertValue(promise(input), vector.getValue("expectedExtension").jsonObject, f.base, id)
            } else assertRefused(input, id)
        }
    }

    @Test
    fun rspD08To10_allTwelveInvalidRangeVectorsRefuseWithoutChangingPriorValue() {
        for (vector in f.rows("invalidRangeVectors")) {
            assertRefused(vector.getValue("input").jsonObject, f.text(vector, "id"))
        }
    }

    @Test
    fun rspD12_exactFrozenMemberFragmentsAreEmittedAsIntegers() {
        val outputs = listOf("V-CHANGE-MIN", "V-DEFECT-MIN", "V-MAX-DAYS").map { id ->
            fields(encodeValue(promise(f.input(id)), LocalRetailReturnPromiseV1.serializer()))
        }
        for (vector in f.memberRows("valid")) {
            val fragment = f.text(vector, "fragment")
            val expected = Json.parseToJsonElement("{$fragment}").jsonObject.entries.single()
            val actual = outputs.first { it[expected.key] == expected.value }.getValue(expected.key)
            assertFalse(actual.jsonPrimitive.isString, f.text(vector, "id"))
            assertEquals("{$fragment}", JsonObject(mapOf(expected.key to actual)).toString(), f.text(vector, "id"))
        }
        // Raw signed duplicates/aliases/nulls and whole-host acceptance stay UNBOUND.
    }

    @Test
    fun rspD13_frozenRevisionScalarsKeepEveryDigitOrRefuseNewValue() {
        for (vector in f.rows("revisionVectors")) {
            val id = "RSP-V1-D13/" + f.text(vector, "id")
            val raw = rawPromise(f.input("V-ORIGINAL"), revision = Json.parseToJsonElement(f.text(vector, "wireScalar")))
            if (f.text(vector, "outcome") == "VALID") {
                val base = JsonObject(f.base + ("originalRevision" to vector.getValue("expectedRevision")))
                assertValue(decodeValue(raw, LocalRetailReturnPromiseV1.serializer()), f.expected("V-ORIGINAL"), base, id)
            } else {
                assertFails(id) { decodeValue(raw, LocalRetailReturnPromiseV1.serializer()) }
            }
        }
    }

    @Test
    fun rspD14_frozenOriginalIdentityInputsAreNeverConvertedOrReplaced() {
        for (vector in f.rows("idVectors")) {
            val id = "RSP-V1-D14/" + f.text(vector, "id")
            val raw = rawPromise(f.input("V-ORIGINAL"), identity = vector.getValue("value"))
            if (f.text(vector, "outcome") == "VALID") {
                val base = JsonObject(f.base + ("originalPromiseId" to vector.getValue("value")))
                assertValue(decodeValue(raw, LocalRetailReturnPromiseV1.serializer()), f.expected("V-ORIGINAL"), base, id)
            } else {
                assertFails(id) { decodeValue(raw, LocalRetailReturnPromiseV1.serializer()) }
                assertFails(id + " public identity scalar") { DomainId(f.text(vector, "value")) }
            }
        }
    }

    @Test
    fun rspD15_numericAndJsonLookingLiteralTermsNeverSupplyStructuredMembers() {
        val text = f.other("F-TEXT-NUMBERS")
        val base = JsonObject(f.base + mapOf("literalEnglish" to text.getValue("literalEnglish"),
            "literalArabic" to text.getValue("literalArabic")))
        for (id in listOf("V-ABSENT", "V-ORIGINAL")) {
            assertValue(promise(f.input(id), base), f.expected(id), base, "RSP-V1-D15/$id")
        }
    }

    @Test
    fun rspD16_categorySpellingIsDocumentaryAndNeverPopulatesPeriods() {
        val base = JsonObject(f.base + ("originalCategory" to f.other("F-CATEGORY-OTHER").getValue("originalCategory")))
        for (id in listOf("V-ABSENT", "V-ORIGINAL")) {
            assertValue(promise(f.input(id), base), f.expected(id), base, "RSP-V1-D16/$id")
        }
    }

    @Test
    fun rspD17_callerMapMutationCannotChangeProducedOriginalOrDescriptor() {
        val caller = f.input("V-ORIGINAL").toMutableMap()
        val baseCaller = f.base.toMutableMap()
        val original = promise(JsonObject(caller), JsonObject(baseCaller))
        val originalBytes = encodeValue(original, LocalRetailReturnPromiseV1.serializer())
        val originalDescriptor = assertNotNull(returnPromiseDescriptor(original))
        caller.clear()
        caller.putAll(f.input("V-LATER-SHORTER"))
        baseCaller["literalEnglish"] = f.other("F-TEXT-NUMBERS").getValue("literalEnglish")
        baseCaller["originalCategory"] = f.other("F-CATEGORY-OTHER").getValue("originalCategory")
        assertValue(original, f.expected("V-ORIGINAL"), f.base, "RSP-V1-D17")
        assertEquals(originalBytes, encodeValue(original, LocalRetailReturnPromiseV1.serializer()))
        assertEquals(originalDescriptor, returnPromiseDescriptor(original))
        // Equality is of this in-memory original, not native signature/storage evidence.
    }

    @Test
    fun rspD18_clearingCreatesSeparateValuesAndCannotClearOnlyOneDefectField() {
        val original = promise(f.input("V-ORIGINAL"))
        val before = encodeValue(original, LocalRetailReturnPromiseV1.serializer())
        val descriptorBefore = assertNotNull(returnPromiseDescriptor(original))
        val defectCleared = original.copy(defectiveReceiptWindowDays = null, defectiveResolutionDays = null)
        val changeOnly = JsonObject(f.expected("V-ORIGINAL").filterKeys { it == "changeOfMindReceiptWindowDays" })
        assertValue(defectCleared, changeOnly, f.base, "RSP-V1-D18 pair cleared")
        assertValue(defectCleared.copy(changeOfMindReceiptWindowDays = null), f.expected("V-ABSENT"),
            f.base, "RSP-V1-D18 all cleared")
        assertFails("RSP-V1-D18 receipt-only clear") { original.copy(defectiveReceiptWindowDays = null) }
        assertFails("RSP-V1-D18 resolution-only clear") { original.copy(defectiveResolutionDays = null) }
        assertValue(original, f.expected("V-ORIGINAL"), f.base, "RSP-V1-D18 prior original")
        assertEquals(before, encodeValue(original, LocalRetailReturnPromiseV1.serializer()))
        assertEquals(descriptorBefore, returnPromiseDescriptor(original))
    }

    private fun promise(input: JsonObject, base: JsonObject = f.base): LocalRetailReturnPromiseV1 =
        LocalRetailReturnPromiseV1(DomainId(f.text(base, "originalPromiseId")),
            Revision(f.text(base, "originalRevision").toLong()), f.text(base, "originalCategory"),
            f.text(base, "literalEnglish"), f.text(base, "literalArabic"),
            f.day(input, "changeOfMindReceiptWindowDays"), f.day(input, "defectiveReceiptWindowDays"),
            f.day(input, "defectiveResolutionDays"))

    private fun rawPromise(input: JsonObject,
        revision: kotlinx.serialization.json.JsonElement = JsonPrimitive(f.text(f.base, "originalRevision")),
        identity: kotlinx.serialization.json.JsonElement = JsonPrimitive(f.text(f.base, "originalPromiseId"))): StoredBytes {
        val value = linkedMapOf("policyId" to identity, "policyRevision" to revision,
            "categoryCode" to JsonPrimitive(f.text(f.base, "originalCategory")),
            "englishTerms" to JsonPrimitive(f.text(f.base, "literalEnglish")),
            "arabicTerms" to JsonPrimitive(f.text(f.base, "literalArabic")))
        value.putAll(input)
        return StoredBytes(JsonObject(value).toString().encodeToByteArray())
    }

    private fun assertRefused(input: JsonObject, label: String) {
        val retained = promise(f.input("V-ORIGINAL"))
        val bytesBefore = encodeValue(retained, LocalRetailReturnPromiseV1.serializer())
        val descriptorBefore = assertNotNull(returnPromiseDescriptor(retained))
        assertFails(label + " real value decoder") { decodeValue(rawPromise(input), LocalRetailReturnPromiseV1.serializer()) }
        if (input.values.all { it == JsonNull || it.jsonPrimitive.intOrNull != null }) {
            assertFails(label + " real typed value") { promise(input) }
        }
        // Overflow has a real decoder binding; it cannot be passed to an Int constructor.
        assertEquals(bytesBefore, encodeValue(retained, LocalRetailReturnPromiseV1.serializer()), label)
        assertEquals(descriptorBefore, returnPromiseDescriptor(retained), label)
        assertValue(retained, f.expected("V-ORIGINAL"), f.base, label + " retained")
    }

    private fun assertValue(value: LocalRetailReturnPromiseV1, expected: JsonObject, base: JsonObject, label: String) {
        val original = fields(encodeValue(value, LocalRetailReturnPromiseV1.serializer()))
        assertExtension(original, expected, label + " original")
        assertEquals(f.text(base, "originalPromiseId"), value.policyId.value, label)
        assertEquals(f.text(base, "originalRevision"), value.policyRevision.value.toString(), label)
        assertEquals(f.text(base, "originalCategory"), value.categoryCode, label)
        assertEquals(f.text(base, "literalEnglish"), value.englishTerms, label)
        assertEquals(f.text(base, "literalArabic"), value.arabicTerms, label)
        assertTrue(original.getValue("policyRevision").jsonPrimitive.isString, label + " revision string")
        assertEquals(f.text(base, "originalRevision"), original.getValue("policyRevision").jsonPrimitive.content, label)
        val descriptor = assertNotNull(returnPromiseDescriptor(value), label)
        assertEquals(value.policyId, descriptor.policyId, label)
        assertEquals(value.policyRevision, descriptor.policyRevision, label)
        assertEquals(value.categoryCode, descriptor.categoryCode, label)
        assertExtension(fields(encodeValue(descriptor, LocalSalesReturnPromiseDescriptorV2.serializer())), expected, label + " descriptor")
        // Digest/signing-domain goldens require their separate frozen model profile.
    }

    private fun assertExtension(value: JsonObject, expected: JsonObject, label: String) {
        assertEquals(expected, JsonObject(value.filterKeys { it in f.extensionNames }), label)
        for (element in expected) assertFalse(value.getValue(element.key).jsonPrimitive.isString, label + " integer " + element.key)
    }

    private fun fields(value: StoredBytes): JsonObject =
        Json.parseToJsonElement(value.copyBytes().decodeToString()).jsonObject
}

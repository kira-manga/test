package com.ukkera.pos.catalog.store.independent.returnstructuredpromise

import com.ukkera.pos.catalog.store.LocalRetailReturnPromiseV1
import com.ukkera.pos.catalog.store.decodeValue
import com.ukkera.pos.catalog.store.encodeValue
import com.ukkera.pos.catalog.store.returnPromiseDescriptor
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseDescriptorV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Four frozen L0/L1 model goldens; no whole signed-host/native/quote/storage acceptance. */
class ReturnPromiseModelCompatibilityFrozenTest {
    private val g = FrozenReturnPromiseModelCompatibilityGoldens
    private val f = FrozenReturnStructuredPromiseInputs

    @Test fun rspMcV101_literalRevision5ModelBytesAndDigest() = assertGolden("G-LITERAL-R5")
    @Test fun rspMcV102_explicitRevision5ModelBytesAndDigest() = assertGolden("G-EXPLICIT-R5")
    @Test fun rspMcV103_literalHighRevisionModelBytesAndDigest() = assertGolden("G-LITERAL-RJS")
    @Test fun rspMcV104_explicitHighRevisionModelBytesAndDigest() = assertGolden("G-EXPLICIT-RJS")

    private fun assertGolden(id: String) {
        val vector = g.vector(id)
        val input = vector.getValue("input").jsonObject
        val days = input.getValue("specifiedDays").jsonObject
        val original = LocalRetailReturnPromiseV1(DomainId(text(input, "policyId")),
            Revision(text(input, "policyRevision").toLong()), text(input, "categoryCode"),
            text(input, "englishTerms"), text(input, "arabicTerms"),
            f.day(days, "changeOfMindReceiptWindowDays"), f.day(days, "defectiveReceiptWindowDays"),
            f.day(days, "defectiveResolutionDays"))
        val encoded = encodeValue(original, LocalRetailReturnPromiseV1.serializer())
        val expectedPromise = text(vector, "expectedPromiseJson").encodeToByteArray(throwOnInvalidSequence = true)
        assertContentEquals(expectedPromise, encoded.copyBytes(), id + " exact promise bytes")
        assertEquals(number(vector, "expectedPromiseUtf8ByteCount"), encoded.size, id)
        val promiseFields = Json.parseToJsonElement(encoded.copyBytes().decodeToString()).jsonObject
        assertEquals(number(vector, "expectedPromiseMemberCount"), promiseFields.size, id)
        assertEquals(days, JsonObject(promiseFields.filterKeys { it in f.extensionNames }), id)
        assertTrue(promiseFields.getValue("policyRevision").jsonPrimitive.isString, id)
        assertEquals(text(input, "policyRevision"), promiseFields.getValue("policyRevision").jsonPrimitive.content, id)

        val prefix = text(g.root, "domainPrefix").encodeToByteArray(throwOnInvalidSequence = true)
        val expectedPrefix = text(g.root, "domainPrefixUtf8Hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expectedPrefix, prefix, id + " exact domain including LF")
        assertEquals(number(g.root, "domainPrefixUtf8ByteCount"), prefix.size, id)
        val preimage = prefix + encoded.copyBytes()
        assertEquals(number(vector, "expectedDigestPreimageUtf8ByteCount"), preimage.size, id)
        val expectedDigest = text(vector, "expectedPromiseDigest")
        assertEquals(expectedDigest, sha256(preimage).value, id + " frozen domain digest")

        val descriptor = assertNotNull(returnPromiseDescriptor(original), id)
        assertEquals(expectedDigest, descriptor.promiseDigest.value, id + " actual descriptor digest")
        assertTrue(descriptor.promiseDigest.value.matches(Regex("[0-9a-f]{64}")), id)
        val descriptorBytes = encodeValue(descriptor, LocalSalesReturnPromiseDescriptorV2.serializer())
        assertContentEquals(text(vector, "expectedDescriptorJson").encodeToByteArray(throwOnInvalidSequence = true),
            descriptorBytes.copyBytes(), id + " exact descriptor bytes")
        assertEquals(number(vector, "expectedDescriptorUtf8ByteCount"), descriptorBytes.size, id)
        val descriptorFields = Json.parseToJsonElement(descriptorBytes.copyBytes().decodeToString()).jsonObject
        assertEquals(number(vector, "expectedDescriptorMemberCount"), descriptorFields.size, id)
        assertEquals(days, JsonObject(descriptorFields.filterKeys { it in f.extensionNames }), id)
        assertTrue(descriptorFields.getValue("policyRevision").jsonPrimitive.isString, id)
        assertEquals(text(input, "policyRevision"), descriptorFields.getValue("policyRevision").jsonPrimitive.content, id)
        for (key in days.keys) {
            assertFalse(promiseFields.getValue(key).jsonPrimitive.isString, id)
            assertFalse(descriptorFields.getValue(key).jsonPrimitive.isString, id)
        }

        val readOriginal = decodeValue(encoded, LocalRetailReturnPromiseV1.serializer())
        val readDescriptor = decodeValue(descriptorBytes, LocalSalesReturnPromiseDescriptorV2.serializer())
        assertEquals(encoded, encodeValue(readOriginal, LocalRetailReturnPromiseV1.serializer()), id + " original round trip")
        assertEquals(descriptorBytes, encodeValue(readDescriptor, LocalSalesReturnPromiseDescriptorV2.serializer()), id + " descriptor round trip")
        assertEquals(expectedDigest, assertNotNull(returnPromiseDescriptor(readOriginal)).promiseDigest.value, id)
        if (id == "G-EXPLICIT-R5") {
            assertNotEquals(text(g.vector("G-LITERAL-R5"), "expectedPromiseDigest"), descriptor.promiseDigest.value, id)
        }
        if (id == "G-EXPLICIT-RJS") {
            assertNotEquals(text(g.vector("G-LITERAL-RJS"), "expectedPromiseDigest"), descriptor.promiseDigest.value, id)
            assertNotEquals(text(g.vector("G-EXPLICIT-R5"), "expectedPromiseDigest"), descriptor.promiseDigest.value, id)
        }
    }

    private fun text(value: JsonObject, name: String): String = value.getValue(name).jsonPrimitive.content
    private fun number(value: JsonObject, name: String): Int = value.getValue(name).jsonPrimitive.int
}

package com.ukkera.pos.catalog.store.independent.supplierreferencev2

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.CodeNormalization
import kotlin.test.*
import kotlinx.serialization.json.*

/** Supporting strict value/current DTO grammar only; no pre-transition V1 golden or native RPC. */
class SupplierReferenceV2CodecSlicesTest {
    private val f = SupplierReferenceV2Inputs

    @Test
    fun sref2001_allFrozenValidValuesRetainTheirExactUtf16OriginalKeyAndMarker() {
        for (vector in f.valid) {
            assertEquals(vector.units, vector.text.length, vector.label)
            val code = Code.exact(vector.text)
            assertEquals(vector.text, code.original, vector.label)
            assertEquals(vector.text, code.normalizedKey, vector.label)
            assertEquals(CodeNormalization.EXACT_V1, code.normalization, vector.label)
            val request = LocalSupplierRequestV2(null, f.supplier(reference = vector.text))
            assertEquals(request, LocalSupplierCodecV2.decode(LocalSupplierCodecV2.encode(request)), vector.label)
        }
        val absent = LocalSupplierRequestV2(null, f.supplier(reference = null))
        assertNull(LocalSupplierCodecV2.decode(LocalSupplierCodecV2.encode(absent)).supplier.reference)
        assertNotEquals(Code.exact(f.nfc), Code.exact(f.nfd))
        // The reference contract explicitly does not inherit name-only control restrictions.
        assertEquals("\u0001", Code.exact("\u0001").original)
    }

    @Test
    fun sref2001_invalidLengthsAndSurrogatesRefuseAtActualValueAndAsciiEscapedWireBoundaries() {
        for (vector in f.invalid) {
            assertEquals(vector.units, vector.text.length, vector.label)
            assertFailsWith<IllegalArgumentException>(vector.label) { Code.exact(vector.text) }
            // ASCII escapes carry malformed UTF-16 unchanged to the strict decoder, not a
            // UTF-8 encoder that might replace an unpaired Kotlin surrogate first.
            assertFails(vector.label) { LocalSupplierCodecV2.decode(wireReference(vector.text, vector.text)) }
        }
        assertFails { LocalSupplierCodecV2.decode(wireReference(f.literal, "0007-Ab")) }
        assertFails { LocalSupplierCodecV2.decode(wireReference(f.literal, f.literal, "UNKNOWN_V2")) }
    }

    @Test
    fun sref2001_maximumNamesAndEscapedReferenceRoundTripWithoutReducingValueLimits() {
        val names = LocalNamesV1("A".repeat(160), "م".repeat(160))
        val request = LocalSupplierRequestV2(null, f.supplier(names = names, reference = f.escaped256))
        val bytes = LocalSupplierCodecV2.encode(request)
        assertTrue(bytes.size in 1..65_536)
        assertEquals(request, LocalSupplierCodecV2.decode(bytes))
        assertEquals(256, request.supplier.reference!!.original.length)
        assertFailsWith<IllegalArgumentException> { LocalNamesV1("A".repeat(161), "") }
        assertFailsWith<IllegalArgumentException> { LocalNamesV1("", "م".repeat(161)) }
    }

    @Test
    fun sref2003_currentV1GrammarOmitsReferenceAndRefusesItsAdditionWithoutCallingItAnOldGolden() {
        val request = LocalConfigurationRequestV1.ConfigureSupplier(null,
            LocalSupplierV1(f.legacy, f.initialNames, true))
        val currentBytes = LocalConfigurationCodec.encode(request)
        val objectValue = localConfigurationJson.parseToJsonElement(currentBytes.copyBytes().decodeToString()).jsonObject
        assertEquals(setOf("type", "expectedStorageRevision", "supplier"), objectValue.keys)
        val supplier = objectValue.getValue("supplier").jsonObject
        assertEquals(setOf("id", "names", "active"), supplier.keys)
        assertEquals(request, LocalConfigurationCodec.decode(currentBytes))
        for (extra in listOf(JsonNull, localConfigurationJson.encodeToJsonElement(Code.serializer(), Code.exact(f.literal)))) {
            val changed = JsonObject(objectValue + ("supplier" to JsonObject(supplier + ("reference" to extra))))
            assertFails { LocalConfigurationCodec.decode(StoredBytes(changed.toString().encodeToByteArray())) }
        }
        val result = LocalConfigurationResultV1("configuration.applied", f.legacy, com.ukkera.pos.types.Revision(0))
        val resultBytes = encodeValue(result, LocalConfigurationResultV1.serializer())
        assertEquals(setOf("code", "id", "revision"),
            localConfigurationJson.parseToJsonElement(resultBytes.copyBytes().decodeToString()).jsonObject.keys)
        assertEquals(result, LocalConfigurationCodec.decodeResult(resultBytes))
        // These are current grammar checks, not genuinely retained pre-transition bytes.
    }

    @Test
    fun sref2002_v2RequiresExplicitReferenceStateAndRejectsUnknownOrCoercedMembers() {
        val base = localConfigurationJson.parseToJsonElement(
            LocalSupplierCodecV2.encode(LocalSupplierRequestV2(null, f.supplier())).copyBytes().decodeToString()).jsonObject
        val supplier = base.getValue("supplier").jsonObject
        val mutations = listOf(
            JsonObject(supplier - "reference"),
            JsonObject(supplier + ("reference" to JsonPrimitive(f.literal))),
            JsonObject(supplier + ("active" to JsonPrimitive("true"))),
            JsonObject(supplier + ("referenceExtra" to JsonNull)),
        )
        for (value in mutations) {
            assertFails { LocalSupplierCodecV2.decode(StoredBytes(JsonObject(base + ("supplier" to value)).toString().encodeToByteArray())) }
        }
    }

    @Test
    fun sref2007_v1AndV2SelectorLimitsRemainOneThroughFifty() {
        for (limit in listOf(1, 50)) {
            val v2 = LocalSupplierQueryV2(limit = limit)
            assertEquals(v2, LocalSupplierCodecV2.decodeQuery(LocalSupplierCodecV2.query(v2)))
            val v1 = LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER, limit = limit)
            assertEquals(v1, LocalConfigurationCodec.decodeQuery(LocalConfigurationCodec.query(v1)))
        }
        for (limit in listOf(0, 51)) {
            assertFailsWith<IllegalArgumentException> { LocalSupplierQueryV2(limit = limit) }
            assertFailsWith<IllegalArgumentException> { LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER, limit = limit) }
            assertFails { LocalSupplierCodecV2.decodeQuery(StoredBytes("{\"id\":null,\"after\":null,\"limit\":$limit}".encodeToByteArray())) }
        }
        assertFailsWith<IllegalArgumentException> { LocalSupplierQueryV2(id = f.current, after = f.legacy) }
    }

    private fun wireReference(original: String, key: String, marker: String = "EXACT_V1"): StoredBytes {
        fun escaped(value: String) = value.map { "\\u${it.code.toString(16).padStart(4, '0')}" }.joinToString("")
        return StoredBytes(("{\"expectedStorageRevision\":null,\"supplier\":{\"id\":\"${f.current.value}\","
            + "\"names\":{\"english\":\"Atlas Supply\",\"arabic\":\"\"},\"active\":true,"
            + "\"reference\":{\"original\":\"${escaped(original)}\",\"normalizedKey\":\"${escaped(key)}\","
            + "\"normalization\":\"$marker\"}}}").encodeToByteArray())
    }
}

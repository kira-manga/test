package com.ukkera.pos.sales.store.independent.foodroutefreeaddv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.FoodBodyV1
import com.ukkera.pos.sales.store.FoodEnvelopeV1
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.foodQuotationEnvelope
import com.ukkera.pos.sales.store.foodQuotationEnvelopeBytes
import com.ukkera.pos.sales.store.foodStoredEnvelope
import com.ukkera.pos.sales.store.localSalesEncode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.text.CharacterCodingException

/** Frozen C10–C14 representation assertions only; no installed/native/business qualification. */
internal class FoodRouteFreeAddStoredCodecTest {
    private val quotation: FrozenStoredWire get() = FrozenStorageInputs.quotation
    private val portion: FrozenStoredWire get() = FrozenStorageInputs.portion
    private val both: List<FrozenStoredWire> get() = listOf(quotation, portion)

    @Test fun c10CompleteQuotationSidecarGolden() {
        val value = quotation.baseline()
        val body = assertIs<FoodBodyV1.RecipeQuotation>(value.body)
        assertEquals("sales.food.recipe-quotation", value.kind)
        assertEquals("1", value.revision.value.toString())
        assertEquals(FrozenStorageInputs.quotationId, value.id.value)
        assertEquals(FrozenStorageInputs.id("addOperation"), value.operationId.value)
        assertEquals(FrozenStorageInputs.id("order"), body.orderId.value)
        assertEquals(FrozenStorageInputs.id("priceBatchCodecOnly"), body.priceBatchId.value)
        assertContentEquals(FrozenStorageInputs.wire("recipeQuotationBody").utf8(),
            localSalesEncode(body as FoodBodyV1, FoodBodyV1.serializer()).copyBytes())
        assertContentEquals(FrozenStorageInputs.wire("quotation").utf8(),
            localSalesEncode(body.quotation, LocalFoodRecipeQuotationV1.serializer()).copyBytes())
        val expected = FrozenStorageInputs.fixtures.getValue("codecQuotation").jsonObject
        assertEquals(expected.getValue("signedPublication").jsonPrimitive.content, body.quotation.signedPublication)
        assertEquals(expected.getValue("description").jsonPrimitive.content, body.quotation.description)
        assertEquals(FrozenStorageInputs.id("recipePublicationOperationCodecOnly"),
            body.quotation.sources.first().operationId?.value)
        assertTrue(body.quotation.sources.drop(1).all { it.operationId == null })
    }

    @Test fun c11CompleteQuotedPortionGolden() {
        val value = portion.baseline()
        val body = assertIs<FoodBodyV1.QuotedPortion>(value.body)
        assertEquals("sales.food.portion", value.kind)
        assertEquals("1", value.revision.value.toString())
        assertEquals(FrozenStorageInputs.portionId(0), value.id.value)
        assertEquals(FrozenStorageInputs.id("addOperation"), value.operationId.value)
        assertEquals(FrozenStorageInputs.id("order"), body.orderId.value)
        assertEquals(FrozenStorageInputs.id("priceBatchCodecOnly"), body.priceBatchId.value)
        assertEquals(FrozenStorageInputs.quotationId, body.quotationId.value)
        val actualBodyBytes = localSalesEncode(body as FoodBodyV1, FoodBodyV1.serializer()).copyBytes()
        assertContentEquals(FrozenStorageInputs.wire("quotedPortionBody").utf8(), actualBodyBytes)
        val expectedBody = Json.parseToJsonElement(FrozenStorageInputs.wire("quotedPortionBody")).jsonObject
        assertContentEquals(expectedBody.getValue("selection").toString().utf8(),
            localSalesEncode(body.selection, LocalFoodPortionSelectionV1.serializer()).copyBytes())
        val actualBody = Json.parseToJsonElement(actualBodyBytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        assertEquals(FrozenStorageInputs.fixtures.getValue("codecPreparation"), actualBody.getValue("preparation"))
        assertEquals("0", body.preparation.revision.value.toString())
        assertEquals("NOT_SENT", body.preparation.status.name)
        assertEquals("NOT_HANDED_OVER", body.preparation.fulfillment.name)
    }

    @Test fun c12MissingTasksRefused() = rejectPreparationOmission("tasks")

    @Test fun c12MissingReservationIdRefused() = rejectPreparationOmission("reservationId")

    @Test fun c12MissingIssueRevisionRefused() = rejectPreparationOmission("issueRevision")

    @Test fun c12ExplicitPickedUpAtDefaultRefused() {
        val changed = editObject(portion.text, listOf("body", "preparation")) { this["pickedUpAt"] = JsonNull }
        reject(portion, changed.utf8(), "pickedUpAt explicit null")
    }

    @Test fun c12AddedWarehouseFieldRefused() {
        val changed = editObject(portion.text, listOf("body")) { this["warehouseId"] = JsonNull }
        reject(portion, changed.utf8(), "added warehouseId")
    }

    @Test fun c12AddedCollectionFieldRefused() {
        val changed = editObject(portion.text, listOf("body")) { this["financialCollectionId"] = JsonNull }
        reject(portion, changed.utf8(), "added financialCollectionId")
    }

    @Test fun c12DifferentPreparationPortionRefused() {
        val changed = editObject(portion.text, listOf("body", "preparation")) {
            this["portionId"] = JsonPrimitive(FrozenStorageInputs.portionId(1))
        }
        reject(portion, changed.utf8(), "preparation portionId P2")
    }

    @Test fun c12PreparationRevisionOneAtEnvelopeOneRefused() {
        val changed = editObject(portion.text, listOf("body", "preparation")) { this["revision"] = JsonPrimitive("1") }
        reject(portion, changed.utf8(), "preparation revision1, envelope revision1")
    }

    @Test fun c12QuotedPortionUnderLegacyRoutedSchemasRefused() {
        for (schema in listOf("sales.food.portion/v1", "sales.food.portion/stock-v2")) {
            reject(portion, portion.text.utf8(), "quotation codec with $schema", schema)
            reject(portion, portion.text.utf8(), "legacy stored dispatch with $schema", schema, legacy = true)
        }
    }

    @Test fun c12RecipeQuotationUnderPortionSchemaRefused() {
        reject(quotation, quotation.text.utf8(), "RECIPE_QUOTATION under quoted portion schema", portion.schema)
    }

    @Test fun c13ReorderedEnvelopeFieldsRefused() {
        for (wire in both) reject(wire, reorderFields(wire.text, emptyList(), "scope", "kind").utf8(), "scope/kind reordered")
    }

    @Test fun c13ReorderedBodyFieldsRefused() {
        for (wire in both) reject(wire, reorderFields(wire.text, listOf("body"), "type", "orderId").utf8(), "type/orderId reordered")
    }

    @Test fun c13LeadingWhitespaceRefused() {
        for (wire in both) reject(wire, (" " + wire.text).utf8(), "leading space")
    }

    @Test fun c13TrailingWhitespaceRefused() {
        for (wire in both) reject(wire, (wire.text + " ").utf8(), "trailing space")
    }

    @Test fun c13UnknownEnvelopeFieldRefused() {
        for (wire in both) {
            val changed = editObject(wire.text) { this["unknown"] = JsonNull }
            reject(wire, changed.utf8(), "unknown envelope field")
        }
    }

    @Test fun c13UnknownBodyFieldRefused() {
        for (wire in both) {
            val changed = editObject(wire.text, listOf("body")) { this["unknown"] = JsonNull }
            reject(wire, changed.utf8(), "unknown body field")
        }
    }

    @Test fun c13MissingEnvelopeFieldRefused() {
        for (wire in both) {
            val changed = editObject(wire.text) { assertNotNull(remove("operationId")) }
            reject(wire, changed.utf8(), "missing operationId")
        }
    }

    @Test fun c13MissingBodyFieldRefused() {
        for (wire in both) {
            val changed = editObject(wire.text, listOf("body")) { assertNotNull(remove("orderId")) }
            reject(wire, changed.utf8(), "missing orderId")
        }
    }

    @Test fun c13DuplicatedFieldRefused() {
        val member = "\"operationId\":\"${FrozenStorageInputs.id("addOperation")}\","
        for (wire in both) {
            val changed = replaceOnce(wire.text, member, member + member)
            reject(wire, changed.utf8(), "duplicated operationId")
        }
    }

    @Test fun c13NoncanonicalUuidRefused() {
        for (wire in both) {
            val path = if (wire == quotation) listOf("body", "quotation") else listOf("body")
            val changed = editObject(wire.text, path) {
                val original = getValue("quotationId").jsonPrimitive.content
                assertTrue(original != original.uppercase())
                this["quotationId"] = JsonPrimitive(original.uppercase())
            }
            reject(wire, changed.utf8(), "uppercase quotationId")
        }
    }

    @Test fun c13NoncanonicalRevisionRefused() {
        for (wire in both) {
            val changed = editObject(wire.text) { this["revision"] = JsonPrimitive("01") }
            reject(wire, changed.utf8(), "envelope revision01")
        }
    }

    @Test fun c13IsolatedSurrogateDescriptionRefused() {
        val original = JsonPrimitive(FrozenStorageInputs.fixtures.getValue("codecQuotation")
            .jsonObject.getValue("description").jsonPrimitive.content).toString()
        val changed = replaceOnce(quotation.text, "\"description\":$original", "\"description\":\"\\ud800\"")
        reject(quotation, changed.utf8(), "isolated surrogate escape in description")
    }

    @Test fun c13IsolatedSurrogateSignedPublicationRefused() {
        val original = JsonPrimitive(FrozenStorageInputs.fixtures.getValue("codecQuotation")
            .jsonObject.getValue("signedPublication").jsonPrimitive.content).toString()
        val changed = replaceOnce(quotation.text, "\"signedPublication\":$original", "\"signedPublication\":\"\\ud800\"")
        reject(quotation, changed.utf8(), "isolated surrogate escape in signedPublication")
    }

    @Test fun c13InvalidUtf8Refused() {
        for (wire in both) reject(wire, byteArrayOf(0xc0.toByte(), 0xaf.toByte()), "invalid UTF8 C0 AF")
    }

    @Test fun c14ExactByteCeilingShapeReadable() {
        val boundary = FrozenStorageInputs.boundary
        val wire = FrozenStorageInputs.ceiling
        assertEquals(28_672, boundary.getValue("limitBytes").jsonPrimitive.int)
        assertEquals(28_672, wire.text.utf8().size)
        assertEquals(boundary.getValue("limitSha256").jsonPrimitive.content, sha256(wire.text.utf8()))
        val value = wire.baseline()
        val body = assertIs<FoodBodyV1.RecipeQuotation>(value.body)
        assertEquals(12_102, body.quotation.signedPublication.utf8().size)
        assertEquals(12_102, boundary.getValue("limitSignedPublicationBytes").jsonPrimitive.int)
    }

    @Test fun c14OneByteAboveCeilingRefused() {
        val boundary = FrozenStorageInputs.boundary
        val text = boundary.getValue("overLimitEnvelope").jsonPrimitive.content
        val bytes = text.utf8()
        assertEquals(28_673, bytes.size)
        assertEquals(28_673, boundary.getValue("overLimitBytes").jsonPrimitive.int)
        assertEquals(boundary.getValue("overLimitSha256").jsonPrimitive.content, sha256(bytes))
        val signed = Json.parseToJsonElement(text).jsonObject.getValue("body").jsonObject
            .getValue("quotation").jsonObject.getValue("signedPublication").jsonPrimitive.content
        assertEquals(12_103, signed.utf8().size)
        assertEquals(12_103, boundary.getValue("overLimitSignedPublicationBytes").jsonPrimitive.int)
        reject(FrozenStorageInputs.ceiling, bytes, "28,673 UTF8 bytes")
    }

    private fun rejectPreparationOmission(field: String) {
        val changed = editObject(portion.text, listOf("body", "preparation")) { assertNotNull(remove(field)) }
        reject(portion, changed.utf8(), "missing preparation.$field")
    }

    /** The valid baseline and all fixture/StoredBytes construction stay outside the rejection block. */
    private fun reject(baseline: FrozenStoredWire, bytes: ByteArray, label: String,
        schema: String = baseline.schema, legacy: Boolean = false) {
        baseline.baseline()
        assertTrue(schema != baseline.schema || legacy || !bytes.contentEquals(baseline.text.utf8()),
            "Refusal input must change bytes or dispatch schema")
        val stored = StoredBytes(bytes)
        val rejected = try {
            if (legacy) foodStoredEnvelope(schema, stored) else foodQuotationEnvelope(schema, stored)
            null
        } catch (failure: IllegalArgumentException) {
            failure
        } catch (failure: IllegalStateException) {
            failure
        } catch (failure: CharacterCodingException) {
            failure
        }
        assertNotNull(rejected, "${baseline.name}: $label must refuse")
        assertContentEquals(bytes, stored.copyBytes(), "Refusal must preserve the supplied stored bytes")
    }
}

private data class FrozenStoredWire(val name: String, val schema: String, val text: String) {
    fun baseline(): FoodEnvelopeV1 {
        val bytes = StoredBytes(text.utf8())
        val value = foodQuotationEnvelope(schema, bytes)
        assertContentEquals(text.utf8(), foodQuotationEnvelopeBytes(value, schema).copyBytes(), "$name exact golden bytes")
        return value
    }
}

/** Load the released files verbatim; input-integrity failures are prerequisites, never refusals. */
private object FrozenStorageInputs {
    private const val BASE_SHA256 = "0c586c149c5bc95af8d9d380dda1c3f798dd04cef4b94dab70cc8f4697f7a266"
    private const val BINDING_SHA256 = "d40ab7a39c4ec05694e6f7114b7800f609d8c7a0f0f9bf682f8d13c518713ea2"

    val fixtures: JsonObject by lazy {
        val directory = freezeDirectory()
        val freezeBytes = Files.readAllBytes(directory.resolve("freeze.json"))
        assertEquals(BASE_SHA256, sha256(freezeBytes), "Released freeze identity")
        val freeze = Json.parseToJsonElement(freezeBytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        assertEquals("food-route-free-add-v1", freeze.getValue("version").jsonPrimitive.content)
        val members = freeze.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("README.md", "cases.md", "fixtures.json", "source-manifest.json"),
            members.map { it.getValue("path").jsonPrimitive.content })
        for (member in members) {
            val bytes = Files.readAllBytes(directory.resolve(member.getValue("path").jsonPrimitive.content))
            assertEquals(member.getValue("bytes").jsonPrimitive.int, bytes.size, "Frozen member byte count")
            assertEquals(member.getValue("sha256").jsonPrimitive.content, sha256(bytes), "Frozen member digest")
        }
        val bindingBytes = Files.readAllBytes(directory.resolve("binding-status-v1.1.json"))
        assertEquals(BINDING_SHA256, sha256(bindingBytes), "Frozen additive binding qualification")
        val binding = Json.parseToJsonElement(bindingBytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        assertEquals(BASE_SHA256, binding.getValue("base_freeze_sha256").jsonPrimitive.content)
        Json.parseToJsonElement(Files.readAllBytes(directory.resolve("fixtures.json"))
            .decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    val quotation: FrozenStoredWire get() = FrozenStoredWire("C10", wire("recipeQuotationSchema"), wire("recipeQuotationEnvelope"))
    val portion: FrozenStoredWire get() = FrozenStoredWire("C11", wire("quotedPortionSchema"), wire("quotedPortionEnvelope"))
    val boundary: JsonObject get() = fixtures.getValue("storedByteBoundary").jsonObject
    val ceiling: FrozenStoredWire get() = FrozenStoredWire("C14 ceiling", wire("recipeQuotationSchema"),
        boundary.getValue("limitEnvelope").jsonPrimitive.content)
    val quotationId: String get() = fixtures.getValue("quotationIds").jsonArray[0].jsonPrimitive.content

    fun wire(name: String): String = fixtures.getValue("wireGolden").jsonObject.getValue(name).jsonPrimitive.content
    fun id(name: String): String = fixtures.getValue("ids").jsonObject.getValue(name).jsonPrimitive.content
    fun portionId(index: Int): String = fixtures.getValue("portionIds").jsonArray[index].jsonPrimitive.content

    private fun freezeDirectory(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath().normalize()
        while (directory != null) {
            val candidate = directory.resolve("qa/pos/freezes/food-route-free-add-v1")
            if (Files.isRegularFile(candidate.resolve("freeze.json"))) return candidate
            directory = directory.parent
        }
        error("Released food-route-free-add-v1 freeze not found above the test working directory")
    }
}

private fun editObject(text: String, path: List<String> = emptyList(), edit: MutableMap<String, JsonElement>.() -> Unit): String {
    val original = Json.parseToJsonElement(text)
    assertEquals(text, original.toString(), "Frozen mutation baseline must preserve literal JSON")
    fun visit(element: JsonElement, remaining: List<String>): JsonObject {
        val members = element.jsonObject.toMutableMap()
        if (remaining.isEmpty()) members.edit()
        else members[remaining.first()] = visit(members.getValue(remaining.first()), remaining.drop(1))
        return JsonObject(members)
    }
    return visit(original, path).toString()
}

private fun reorderFields(text: String, path: List<String>, first: String, second: String): String =
    editObject(text, path) {
        val originals = toMap()
        assertTrue(first in originals && second in originals)
        val names = keys.map { if (it == first) second else if (it == second) first else it }
        clear()
        for (name in names) this[name] = originals.getValue(name)
    }

private fun replaceOnce(text: String, original: String, replacement: String): String {
    val index = text.indexOf(original)
    assertTrue(index >= 0 && text.indexOf(original, index + original.length) < 0,
        "Frozen text mutation must select exactly one field")
    return text.substring(0, index) + replacement + text.substring(index + original.length)
}

private fun String.utf8(): ByteArray = encodeToByteArray(throwOnInvalidSequence = true)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

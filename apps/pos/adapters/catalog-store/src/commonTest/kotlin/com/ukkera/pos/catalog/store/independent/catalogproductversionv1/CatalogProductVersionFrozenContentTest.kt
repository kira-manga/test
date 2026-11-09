package com.ukkera.pos.catalog.store.independent.catalogproductversionv1

import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalProductAttributeValueV1
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalVariantAxisV1
import com.ukkera.pos.catalog.store.localCatalogProductShapeRejection
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CPR-FROZEN-1 L1 content/codec slices only. No native signing, history completion,
 * activation, employee grant, database effect or communication is supplied.
 * Whole CPR cases and their L2/L3 variants remain UNBOUND / NOT_RUN.
 */
class CatalogProductVersionFrozenContentTest {
    private val f = FrozenCatalogProductInputs
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test fun cpr001_completeNewSimpleBodyHasExplicitEmptySelection_contentOnly() {
        val product = f.newProduct()
        val input = f.sku(product, skuId = f.newSkuId, selections = emptyList())
        assertNull(localCatalogProductShapeRejection(input))
        val restored = roundTrip(input)
        assertEquals(f.newProductId, restored.productId)
        val body = assertNotNull(restored.productDefinition)
        assertEquals(Revision(0), body.revision)
        assertEquals("Plain stocked item", body.names.english)
        assertEquals("", body.names.arabic)
        assertEquals(listOf(f.retail), body.categoryIds)
        assertEquals(emptyList(), body.variantAxes)
        assertEquals("synthetic", assertIs<LocalProductAttributeValueV1.Text>(body.attributes.single().value).value.original)
        assertEquals(emptyList(), assertNotNull(restored.variantValues))
        val wire = publicationObject(restored)
        assertTrue("productDefinition" in wire)
        assertEquals("[]", wire["variantValues"].toString())
        // The value 0 is a supplied frozen body, not proof of EMPTY_AUTHENTICATED.
    }

    @Test fun cpr007_completeShirtBodyAndSelectionsRoundTripInExactOrder() {
        val restored = roundTrip(f.sku())
        f.assertShirt(assertNotNull(restored.productDefinition), "CPR-007.A")
        assertEquals(f.blueMedium(), restored.variantValues)
        assertEquals(Revision(41), restored.skuRevision)
        assertEquals(Revision(101), restored.catalogRevision)
        assertEquals("SHIRT-BLUE-M-01", restored.code)
        assertEquals(listOf("0000000000002"), restored.barcodes)
    }

    @Test fun cpr007_independentArabicOnlyEditKeepsEnglishEmpty() {
        val product = f.body(revision = Revision(8), names = LocalNamesV1("", "قميص قطني"))
        val restored = assertNotNull(roundTrip(f.sku(product, skuRevision = Revision(42))).productDefinition)
        assertEquals(f.shirtId, restored.id)
        assertEquals(Revision(8), restored.revision)
        assertEquals("", restored.names.english)
        assertEquals("قميص قطني", restored.names.arabic)
        assertEquals(f.shirt().categoryIds, restored.categoryIds)
        assertEquals(f.shirt().variantAxes, restored.variantAxes)
        assertEquals(f.shirt().attributes, restored.attributes)
    }

    @Test fun cpr007_name160BoundariesAcceptAnd161OrBothEmptyRefuse() {
        for ((language, character) in listOf("english" to "A", "arabic" to "ق")) {
            val accepted = if (language == "english") LocalNamesV1(character.repeat(160), "")
                else LocalNamesV1("", character.repeat(160))
            val restored = assertNotNull(roundTrip(f.sku(f.body(names = accepted))).productDefinition)
            assertEquals(accepted, restored.names, language)
            assertFailsWith<IllegalArgumentException>(language) {
                if (language == "english") LocalNamesV1(character.repeat(161), "")
                else LocalNamesV1("", character.repeat(161))
            }
        }
        assertFailsWith<IllegalArgumentException> { LocalNamesV1("", "") }
    }

    @Test fun cpr007_eachRequiredBodyAndNameFieldOmissionRefusesDecode() {
        val complete = json.encodeToJsonElement(LocalPublishedProductV1.serializer(), f.shirt()).jsonObject
        for (field in listOf("id", "revision", "names", "categoryIds", "variantAxes", "attributes", "status")) {
            assertFails("CPR-007.H missing $field") {
                json.decodeFromJsonElement(LocalPublishedProductV1.serializer(), JsonObject(complete - field))
            }
        }
        for (field in listOf("english", "arabic")) {
            val incomplete = JsonObject(complete + ("names" to JsonObject(complete.getValue("names").jsonObject - field)))
            assertFails("CPR-007.H missing names.$field") {
                json.decodeFromJsonElement(LocalPublishedProductV1.serializer(), incomplete)
            }
        }
    }

    @Test fun cpr008_absentExtensionsStayOmittedInCompatibleCodec_contentOnly() {
        val input = f.sku(product = null, selections = null)
        val original = LocalConfigurationCodec.canonicalPublication(input)
        val restored = roundTrip(input)
        assertNull(restored.productDefinition)
        assertNull(restored.variantValues)
        assertEquals(Revision(41), restored.skuRevision)
        assertEquals(Revision(101), restored.catalogRevision)
        assertTrue("productDefinition" !in publicationObject(restored))
        assertTrue("variantValues" !in publicationObject(restored))
        assertContentEquals(original, LocalConfigurationCodec.canonicalPublication(restored))
        // This synthetic absent-extension input is not a supported historic golden,
        // authentic old signature, native cut or migration qualification (Q03).
    }

    @Test fun cpr010_invalidSelectionsAndMismatchedParentRefuseShape() {
        val input = f.sku()
        val selections = assertNotNull(input.variantValues)
        val variants = listOf(
            "A undeclared green" to input.copy(variantValues = listOf(
                selections[0].copy(value = Code.exact("green")), selections[1])),
            "B missing size" to input.copy(variantValues = selections.take(1)),
            "C foreign axis" to input.copy(variantValues = listOf(
                selections[0].copy(axisId = f.foreignAxis), selections[1])),
            "D conflicting duplicate color" to input.copy(variantValues = selections +
                selections[0].copy(value = Code.exact("black"))),
            "E parent mismatch" to input.copy(productId = f.newProductId),
            "F null selection" to input.copy(variantValues = null))
        for ((variant, candidate) in variants) {
            assertNotNull(localCatalogProductShapeRejection(candidate), "CPR-010.$variant")
        }
        f.assertShirt(assertNotNull(input.productDefinition), "original CPR-010 body")
        assertEquals(f.blueMedium(), input.variantValues)
    }

    @Test fun cpr010_scalarSelectionCannotDecodeAsDeclaredStructure() {
        val original = publicationObject(f.sku())
        for (value in listOf(JsonPrimitive("blue/m"), JsonPrimitive(7))) {
            assertFails("CPR-010.F scalar $value") {
                json.decodeFromJsonElement(LocalPublishedSkuV1.serializer(), JsonObject(original + ("variantValues" to value)))
            }
        }
    }

    @Test fun cpr011_decimal30AndLiteralBusinessLikeTextRemainTypedMetadata() {
        val original = f.shirt()
        val attributes = original.attributes.toMutableList()
        attributes[2] = attributes[2].copy(value = LocalProductAttributeValueV1.Text(Code.exact("stock.receive(100)")))
        val edited = f.body(revision = Revision(8), categories = listOf(f.clothing, f.tops, f.seasonal), attributes = attributes)
        val restored = assertNotNull(roundTrip(f.sku(edited, skuRevision = Revision(42))).productDefinition)
        assertEquals(Revision(8), restored.revision)
        assertEquals(listOf(f.clothing, f.tops, f.seasonal), restored.categoryIds)
        assertEquals("30", assertIs<LocalProductAttributeValueV1.Decimal>(restored.attributes[1].value).value.canonicalString())
        assertEquals("stock.receive(100)", assertIs<LocalProductAttributeValueV1.Text>(restored.attributes[2].value).value.original)
        f.assertShirt(original, "CPR-011 original")
        // Codec metadata has no inventory/financial execution in this harness.
    }

    @Test fun cpr011_invalidDeclaredDecimalRefusesInsteadOfExecutingText() {
        val complete = json.encodeToJsonElement(LocalProductAttributeValueV1.serializer(),
            LocalProductAttributeValueV1.Decimal(com.ukkera.pos.types.ExactDecimal.parse("30"))).jsonObject
        assertFails("CPR-011 type-invalid Decimal") {
            json.decodeFromJsonElement(LocalProductAttributeValueV1.serializer(),
                JsonObject(complete + ("value" to JsonPrimitive("stock.receive(100)"))))
        }
    }

    @Test fun cpr012_ownedBodyListsCannotFollowLaterInputMutation() {
        val original = f.shirt()
        val categories = original.categoryIds.toMutableList()
        val colorValues = original.variantAxes[0].values.toMutableList()
        val axes = mutableListOf(LocalVariantAxisV1(f.color, Code.exact("color"), colorValues), original.variantAxes[1])
        val attributes = original.attributes.toMutableList()
        val captured = f.body(categories = categories, axes = axes, attributes = attributes)
        val bytes = LocalConfigurationCodec.canonicalProductDefinition(captured)
        categories += f.seasonal
        categories.reverse()
        colorValues += Code.exact("green")
        axes.reverse()
        attributes[0] = attributes[0].copy(value = LocalProductAttributeValueV1.Text(Code.exact("linen")))
        attributes.reverse()
        f.assertShirt(captured, "CPR-012 captured body")
        assertContentEquals(bytes, LocalConfigurationCodec.canonicalProductDefinition(captured))
        assertEquals("Cotton shirt", captured.names.english)
        assertEquals("", captured.names.arabic)
        // Selected-value capture is bound separately to the actual shared editor.
    }

    private fun roundTrip(value: LocalPublishedSkuV1): LocalPublishedSkuV1 {
        val request = LocalConfigurationRequestV1.InstallCompanySku(f.envelope(value))
        val restored = assertIs<LocalConfigurationRequestV1.InstallCompanySku>(
            LocalConfigurationCodec.decode(LocalConfigurationCodec.encode(request)))
        assertEquals(request, restored)
        return restored.publication.definition
    }

    private fun publicationObject(value: LocalPublishedSkuV1): JsonObject =
        json.encodeToJsonElement(LocalPublishedSkuV1.serializer(), value).jsonObject
}

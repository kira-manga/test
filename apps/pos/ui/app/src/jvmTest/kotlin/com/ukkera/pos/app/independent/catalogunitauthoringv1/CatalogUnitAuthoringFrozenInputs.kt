package com.ukkera.pos.app.independent.catalogunitauthoringv1

import com.ukkera.pos.app.LocalCompanyPriceTaxInclusion
import com.ukkera.pos.app.LocalCompanySkuDraft
import com.ukkera.pos.app.LocalCompanySkuFulfillment
import com.ukkera.pos.app.LocalCompanySkuPreview
import com.ukkera.pos.app.LocalCompanyStockTracking
import com.ukkera.pos.app.ui.CompanySkuEditor
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.CodeNormalization
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Inputs and oracles are read from the unchanged authoring v1 freeze.
 * Auxiliary price/tax/clock/display IDs only satisfy the existing editor's mandatory
 * fields. They are not native originals, new case variants or publication-byte oracles.
 */
internal object CatalogUnitAuthoringFrozenInputs {
    private const val FREEZE = "qa/pos/freezes/catalog-unit-authoring-v1"
    private val directory: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(FREEZE) }
            .firstOrNull { Files.isRegularFile(it.resolve("FREEZE.json")) }
            ?: error("Missing frozen catalog unit authoring expectations: $FREEZE")
    }
    val fixtures: JsonObject by lazy {
        val manifest = read("FREEZE.json")
        require(manifest.text("version") == "catalog-unit-authoring-v1")
        read("fixtures.json")
    }
    private val status: JsonObject by lazy { read("status.json") }
    val legacy: JsonObject get() = definition("F-LEGACY").obj("publication_content")
    val base: JsonObject get() = definition("F-BASE-ONLY").obj("explicit_base_input")
    val pack: JsonObject get() = definition("F-PACK")
    val price: JsonObject get() = definition("F-PACK-PRICE").obj("exact_existing_base_unit_price")
    val observedAt: UtcInstant = UtcInstant.parseCanonical("2026-11-01T00:00:00Z")

    private fun read(name: String): JsonObject =
        Json.parseToJsonElement(Files.readString(directory.resolve(name))).jsonObject

    fun definition(name: String): JsonObject = fixtures.obj("fixture_definitions").obj(name)
    fun rows(name: String): List<JsonObject> = fixtures.array(name).map { it.jsonObject }
    fun id(name: String): DomainId = DomainId(fixtures.obj("ids").text(name))

    /** Supplementary port coordinates, never a replacement for a frozen business identity. */
    fun bindingId(name: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        ("catalog-unit-authoring-v1:$name").encodeToByteArray()).toString())

    fun variant(caseId: String, variantId: String): String {
        val row = status.array("case_outcomes").map { it.jsonObject }
            .single { it.text("case_id") == caseId }
        require(row.array("input_variants").any { it.jsonObject.text("variant_id") == variantId }) {
            "Variant is not in the frozen specification: $caseId/$variantId"
        }
        return "$caseId/$variantId (bounded public/editor slice only)"
    }

    fun units(withPack: Boolean): List<CatalogUnit> =
        definition(if (withPack) "F-PACK" else "F-BASE-ONLY")
            .array("expected_unit_definitions").map { unit(it.jsonObject) }

    fun unit(value: JsonObject): CatalogUnit {
        val code = value.obj("code")
        return CatalogUnit(DomainId(value.text("id")),
            Code(code.text("original"), code.text("normalizedKey"), CodeNormalization.valueOf(code.text("normalization"))),
            UnitDimension.valueOf(value.text("dimension")), value.int("maximumScale"))
    }

    fun conversion(): ProductUnitConversion {
        val input = pack.obj("conversion_input")
        return ProductUnitConversion(DomainId(input.text("id")), Revision(0), id("product"),
            id("source_unit"), id("base_unit"),
            UnitConversionRate.ratio(input.text("numerator_text").toLong(), input.text("denominator_text").toLong()))
    }

    /** Optional-field compatibility only; the generic F-LEGACY golden is NOT mapped by this draft. */
    fun legacyDraft(explicitEmptyConversions: Boolean = false): LocalCompanySkuDraft {
        val draft = LocalCompanySkuDraft(
            publicationId = id("publication"), skuId = id("sku"), productId = id("product"), baseUnitId = id("base_unit"),
            priceRuleId = bindingId("price-rule"), taxPolicyId = bindingId("tax-policy"),
            englishName = legacy.obj("names").text("english"), arabicName = legacy.obj("names").text("arabic"),
            code = legacy.text("code"), barcodes = legacy.array("barcodes").map { it.jsonPrimitive.content },
            unitPriceEgp = price.text("decimal"), taxCategory = "SYNTHETIC", taxPercent = "0",
            taxInclusion = LocalCompanyPriceTaxInclusion.EXCLUDED, canPurchase = legacy.flag("canPurchase"),
            quantityScale = legacy.int("quantityScale"), tracking = LocalCompanyStockTracking.valueOf(legacy.text("tracking")),
            requiresExpiry = legacy.flag("requiresExpiry"))
        return if (explicitEmptyConversions) draft.copy(conversions = emptyList()) else draft
    }

    fun draft(withPack: Boolean = false): LocalCompanySkuDraft = legacyDraft().copy(
        unitDefinitions = units(withPack), conversions = if (withPack) listOf(conversion()) else emptyList())

    fun editor(withPack: Boolean = false): CompanySkuEditor {
        val source = pack.obj("explicit_source_input")
        val rate = pack.obj("conversion_input")
        return CompanySkuEditor(publicationId = id("publication"), skuId = id("sku"), productId = id("product"),
            baseUnitId = id("base_unit"), priceRuleId = bindingId("price-rule"), taxPolicyId = bindingId("tax-policy"),
            englishName = legacy.obj("names").text("english"), arabicName = legacy.obj("names").text("arabic"),
            code = legacy.text("code"), barcodes = legacy.array("barcodes").joinToString("\n") { it.jsonPrimitive.content },
            price = price.text("decimal"), taxCategory = "SYNTHETIC", taxPercent = "0",
            inclusion = LocalCompanyPriceTaxInclusion.EXCLUDED, taxConfirmed = true, canPurchase = legacy.flag("canPurchase"),
            quantityScale = base.int("skuQuantityScale"), tracking = LocalCompanyStockTracking.valueOf(legacy.text("tracking")),
            requiresExpiry = legacy.flag("requiresExpiry"), baseUnitIdentity = base.text("id"), baseUnitCode = base.text("code_text"),
            unitDimension = UnitDimension.valueOf(base.text("dimension")), baseMaximumScale = base.int("maximumScale"),
            packEnabled = withPack, packUnitIdentity = source.text("id"), packUnitCode = source.text("code_text"),
            packMaximumScale = source.int("maximumScale"), conversionIdentity = rate.text("id"),
            conversionNumerator = rate.text("numerator_text"), conversionDenominator = rate.text("denominator_text"))
    }

    /** Public DATA specimen for rendered/recovery-port observations; no native Action or Output exists. */
    fun preview(withPack: Boolean = true, legacyOmission: Boolean = false): LocalCompanySkuPreview = LocalCompanySkuPreview(
        publicationId = id("publication"), skuId = id("sku"), productId = id("product"), baseUnitId = id("base_unit"),
        catalogRevision = Revision(legacy.text("catalogRevision").toLong()), skuRevision = Revision(legacy.text("skuRevision").toLong()),
        englishName = legacy.obj("names").text("english"), arabicName = legacy.obj("names").text("arabic"),
        code = legacy.text("code"), barcodes = legacy.array("barcodes").map { it.jsonPrimitive.content },
        unitPriceEgp = price.text("decimal"), taxCategory = "SYNTHETIC", taxPercent = "0",
        taxInclusion = LocalCompanyPriceTaxInclusion.EXCLUDED, taxPolicyId = bindingId("tax-policy"), priceRuleId = bindingId("price-rule"),
        effectiveFrom = observedAt, canPurchase = legacy.flag("canPurchase"), quantityScale = legacy.int("quantityScale"),
        tracking = LocalCompanyStockTracking.valueOf(legacy.text("tracking")), requiresExpiry = legacy.flag("requiresExpiry"),
        fulfillment = LocalCompanySkuFulfillment.valueOf(legacy.text("fulfillment")), canSell = legacy.flag("canSell"),
        unitDefinitions = if (legacyOmission) null else units(withPack),
        conversions = if (withPack && !legacyOmission) listOf(conversion()) else emptyList())

    fun assertLegacyDraftFields(actual: LocalCompanySkuDraft, message: String) {
        assertEquals(id("publication"), actual.publicationId, message)
        assertEquals(id("sku"), actual.skuId, message)
        assertEquals(id("product"), actual.productId, message)
        assertEquals(id("base_unit"), actual.baseUnitId, message)
        assertEquals(legacy.obj("names").text("english"), actual.englishName, message)
        assertEquals(legacy.obj("names").text("arabic"), actual.arabicName, message)
        assertEquals(legacy.text("code"), actual.code, message)
        assertEquals(legacy.array("barcodes").map { it.jsonPrimitive.content }, actual.barcodes, message)
        assertEquals(legacy.flag("canPurchase"), actual.canPurchase, message)
        assertEquals(legacy.text("tracking"), actual.tracking.name, message)
        assertEquals(legacy.flag("requiresExpiry"), actual.requiresExpiry, message)
        assertNull(actual.returnPromise, message)
    }

    fun assertUnits(actual: List<CatalogUnit>?, expected: JsonArray, message: String) {
        assertEquals(expected.size, actual?.size, message)
        expected.zip(requireNotNull(actual)).forEach { (row, unit) ->
            val value = row.jsonObject
            assertEquals(value.text("id"), unit.id.value, message)
            assertEquals(value.obj("code").text("original"), unit.code.original, message)
            assertEquals(value.obj("code").text("normalizedKey"), unit.code.normalizedKey, message)
            assertEquals(value.obj("code").text("normalization"), unit.code.normalization.name, message)
            assertEquals(value.text("dimension"), unit.dimension.name, message)
            assertEquals(value.int("maximumScale"), unit.maximumScale, message)
        }
    }

    fun assertPackConversion(actual: ProductUnitConversion, message: String, rate: String? = null) {
        val expected = pack.obj("expected_conversion_semantics")
        assertEquals(expected.text("id"), actual.id.value, message)
        assertEquals(expected.text("revision"), actual.revision.value.toString(), message)
        assertEquals(expected.text("productBindingFromPublication"), actual.productId.value, message)
        assertEquals(expected.text("sourceUnitId"), actual.sourceUnitId.value, message)
        assertEquals(expected.text("targetUnitId"), actual.targetUnitId.value, message)
        assertEquals(rate ?: expected.text("reducedRate"), actual.rate.canonicalString(), message)
        assertNull(actual.massVolumeEvidenceId, message)
    }

    fun assertPackPreview(actual: LocalCompanySkuPreview, message: String) {
        assertUnits(actual.unitDefinitions, fixtures.obj("review_pack_expected").array("exact_units"), message)
        assertEquals(1, actual.conversions.size, message)
        assertPackConversion(actual.conversions.single(), message)
        assertEquals(fixtures.obj("review_pack_expected").int("skuQuantityScale"), actual.quantityScale, message)
        assertEquals(fixtures.obj("review_pack_expected").text("authenticated_publication_product_binding"), actual.productId.value, message)
        assertEquals(price.text("decimal"), actual.unitPriceEgp, message)
        assertEquals(price.text("basis_unit_id"), actual.baseUnitId.value, message)
    }
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
internal fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

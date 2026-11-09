package com.ukkera.pos.app.independent.catalogproductversionv1

import com.ukkera.pos.app.LocalCompanyPriceTaxInclusion
import com.ukkera.pos.app.LocalCompanyProductDraft
import com.ukkera.pos.app.ui.CompanyAttributeType
import com.ukkera.pos.app.ui.CompanyProductAttributeEditor
import com.ukkera.pos.app.ui.CompanySkuEditor
import com.ukkera.pos.app.ui.CompanyVariantAxisEditor
import com.ukkera.pos.catalog.CatalogAttribute
import com.ukkera.pos.catalog.CatalogAttributeValue
import com.ukkera.pos.catalog.CatalogStatus
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.VariantAxis
import com.ukkera.pos.catalog.VariantValue
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** CPR-FROZEN-1 / CPR-FIXTURE-1 literal editor bindings; supporting IDs convey no native authority. */
internal object FrozenCatalogProductEditorInputs {
    val product = DomainId("50000000-0000-4000-8000-000000000002")
    val blueMediumSku = DomainId("60000000-0000-4000-8000-000000000002")
    val blackSmallSku = DomainId("60000000-0000-4000-8000-000000000003")
    val clothing = DomainId("a1000000-0000-4000-8000-000000000001")
    val tops = DomainId("a1000000-0000-4000-8000-000000000002")
    val seasonal = DomainId("a1000000-0000-4000-8000-000000000003")
    val color = DomainId("a2000000-0000-4000-8000-000000000001")
    val size = DomainId("a2000000-0000-4000-8000-000000000002")
    val foreignAxis = DomainId("b1000000-0000-4000-8000-000000000009")
    private val base = DomainId("b1000000-0000-4000-8000-000000000001")

    fun selection(blackSmall: Boolean = false): List<VariantValue> = listOf(
        VariantValue(color, Code.exact(if (blackSmall) "black" else "blue")),
        VariantValue(size, Code.exact(if (blackSmall) "s" else "m")))

    fun definition(): LocalCompanyProductDraft = LocalCompanyProductDraft("Cotton shirt", "",
        listOf(clothing, tops), listOf(
            VariantAxis(color, Code.exact("color"), listOf(Code.exact("blue"), Code.exact("black"))),
            VariantAxis(size, Code.exact("size"), listOf(Code.exact("s"), Code.exact("m")))), listOf(
            CatalogAttribute(Code.exact("material"), CatalogAttributeValue.Text(Code.exact("cotton"))),
            CatalogAttribute(Code.exact("wash_temperature"), CatalogAttributeValue.Decimal(ExactDecimal.parse("30"))),
            CatalogAttribute(Code.exact("note"), CatalogAttributeValue.Text(Code.exact("synthetic")))), CatalogStatus.ACTIVE)

    fun editor(blackSmall: Boolean = false): CompanySkuEditor = CompanySkuEditor(
        publicationId = DomainId(if (blackSmall) "b2000000-0000-4000-8000-000000000002" else "b2000000-0000-4000-8000-000000000001"),
        skuId = if (blackSmall) blackSmallSku else blueMediumSku, productId = product, productIdentity = product.value,
        baseUnitId = base, baseUnitIdentity = base.value, baseUnitCode = "unit",
        unitDimension = UnitDimension.COUNT, baseMaximumScale = 0,
        priceRuleId = DomainId("b1000000-0000-4000-8000-000000000003"),
        taxPolicyId = DomainId("b1000000-0000-4000-8000-000000000004"),
        englishName = "Cotton shirt", arabicName = "",
        code = if (blackSmall) "SHIRT-BLACK-S-01" else "SHIRT-BLUE-M-01",
        barcodes = if (blackSmall) "0000000000003" else "0000000000002",
        price = "2.00", taxCategory = "synthetic", taxPercent = "0",
        inclusion = LocalCompanyPriceTaxInclusion.EXCLUDED, taxConfirmed = true, canPurchase = true,
        defineProduct = true, productEnglishName = "Cotton shirt", productArabicName = "",
        productCategories = "${clothing.value}\n${tops.value}", variantAxes = listOf(
            CompanyVariantAxisEditor(color.value, "color", "blue\nblack", if (blackSmall) "black" else "blue"),
            CompanyVariantAxisEditor(size.value, "size", "s\nm", if (blackSmall) "s" else "m")), productAttributes = listOf(
            CompanyProductAttributeEditor("material", CompanyAttributeType.TEXT, "cotton"),
            CompanyProductAttributeEditor("wash_temperature", CompanyAttributeType.DECIMAL, "30"),
            CompanyProductAttributeEditor("note", CompanyAttributeType.TEXT, "synthetic")))

    fun assertDefinition(value: LocalCompanyProductDraft, message: String) {
        assertEquals("Cotton shirt", value.englishName, message)
        assertEquals("", value.arabicName, message)
        assertEquals(listOf(clothing, tops), value.categoryIds, message)
        assertEquals(listOf(color, size), value.variantAxes.map { it.id }, message)
        assertEquals(listOf("color", "size"), value.variantAxes.map { it.code.original }, message)
        assertEquals(listOf(listOf("blue", "black"), listOf("s", "m")),
            value.variantAxes.map { axis -> axis.values.map { it.original } }, message)
        assertEquals(listOf("material", "wash_temperature", "note"), value.attributes.map { it.key.original }, message)
        assertEquals("cotton", assertIs<CatalogAttributeValue.Text>(value.attributes[0].value, message).value.original, message)
        assertEquals("30", assertIs<CatalogAttributeValue.Decimal>(value.attributes[1].value, message).value.canonicalString(), message)
        assertEquals("synthetic", assertIs<CatalogAttributeValue.Text>(value.attributes[2].value, message).value.original, message)
        assertEquals(CatalogStatus.ACTIVE, value.status, message)
    }
}

package com.ukkera.pos.catalog.store.independent.catalogproductversionv1

import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalProductAttributeV1
import com.ukkera.pos.catalog.store.LocalProductAttributeValueV1
import com.ukkera.pos.catalog.store.LocalProductStatusV1
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalSignedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalVariantAxisV1
import com.ukkera.pos.catalog.store.LocalVariantValueV1
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Revision
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Literal CPR-FROZEN-1 / CPR-FIXTURE-1 bindings from
 * qa/pos/freezes/catalog-product-revision-source-v1/fixtures.json.
 * Supporting publication/unit IDs are codec inputs, never native identities or cuts.
 * Lists of these definitions supply content only, not authenticated completed history.
 */
internal object FrozenCatalogProductInputs {
    val company = CompanyScope(DomainId("10000000-0000-4000-8000-000000000001"),
        DomainId("20000000-0000-4000-8000-000000000001"))
    val newProductId = DomainId("50000000-0000-4000-8000-000000000001")
    val shirtId = DomainId("50000000-0000-4000-8000-000000000002")
    val newSkuId = DomainId("60000000-0000-4000-8000-000000000001")
    val blueMediumId = DomainId("60000000-0000-4000-8000-000000000002")
    val blackSmallId = DomainId("60000000-0000-4000-8000-000000000003")
    val clothing = DomainId("a1000000-0000-4000-8000-000000000001")
    val tops = DomainId("a1000000-0000-4000-8000-000000000002")
    val seasonal = DomainId("a1000000-0000-4000-8000-000000000003")
    val retail = DomainId("a1000000-0000-4000-8000-000000000004")
    val color = DomainId("a2000000-0000-4000-8000-000000000001")
    val size = DomainId("a2000000-0000-4000-8000-000000000002")
    val unit = DomainId("b1000000-0000-4000-8000-000000000001")
    val foreignAxis = DomainId("b1000000-0000-4000-8000-000000000002")
    val extraSku = DomainId("b1000000-0000-4000-8000-000000000003")

    fun publication(number: Int): DomainId =
        DomainId("b2000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    fun blueMedium(): List<LocalVariantValueV1> = listOf(
        LocalVariantValueV1(color, Code.exact("blue")), LocalVariantValueV1(size, Code.exact("m")))

    fun blackSmall(): List<LocalVariantValueV1> = listOf(
        LocalVariantValueV1(color, Code.exact("black")), LocalVariantValueV1(size, Code.exact("s")))

    fun shirt(): LocalPublishedProductV1 = LocalPublishedProductV1(shirtId, Revision(7),
        LocalNamesV1("Cotton shirt", ""), listOf(clothing, tops), listOf(
            LocalVariantAxisV1(color, Code.exact("color"), listOf(Code.exact("blue"), Code.exact("black"))),
            LocalVariantAxisV1(size, Code.exact("size"), listOf(Code.exact("s"), Code.exact("m")))), listOf(
            LocalProductAttributeV1(Code.exact("material"), LocalProductAttributeValueV1.Text(Code.exact("cotton"))),
            LocalProductAttributeV1(Code.exact("wash_temperature"), LocalProductAttributeValueV1.Decimal(ExactDecimal.parse("30"))),
            LocalProductAttributeV1(Code.exact("note"), LocalProductAttributeValueV1.Text(Code.exact("synthetic")))),
        LocalProductStatusV1.ACTIVE)

    fun newProduct(): LocalPublishedProductV1 = LocalPublishedProductV1(newProductId, Revision(0),
        LocalNamesV1("Plain stocked item", ""), listOf(retail), emptyList(), listOf(
            LocalProductAttributeV1(Code.exact("note"), LocalProductAttributeValueV1.Text(Code.exact("synthetic")))),
        LocalProductStatusV1.ACTIVE)

    fun body(original: LocalPublishedProductV1 = shirt(), revision: Revision = original.revision,
        names: LocalNamesV1 = original.names, categories: List<DomainId> = original.categoryIds,
        axes: List<LocalVariantAxisV1> = original.variantAxes,
        attributes: List<LocalProductAttributeV1> = original.attributes,
        status: LocalProductStatusV1 = original.status, id: DomainId = original.id): LocalPublishedProductV1 =
        LocalPublishedProductV1(id, revision, names, categories, axes, attributes, status)

    /** Complete frozen templates, each reset independently from SHIRT_7. */
    fun edits(): List<Pair<String, LocalPublishedProductV1>> = listOf(
        "SHIRT_8_NAME_EDIT" to body(revision = Revision(8), names = LocalNamesV1("Summer cotton shirt", "")),
        "SHIRT_8_CATEGORY_EDIT" to body(revision = Revision(8), categories = listOf(clothing, tops, seasonal)),
        "SHIRT_8_AXIS_EDIT" to body(revision = Revision(8), axes = listOf(
            LocalVariantAxisV1(color, Code.exact("color"), listOf(Code.exact("blue"), Code.exact("black"), Code.exact("green"))),
            LocalVariantAxisV1(size, Code.exact("size"), listOf(Code.exact("s"), Code.exact("m"))))),
        "SHIRT_8_ATTRIBUTE_EDIT" to body(revision = Revision(8), attributes = listOf(
            LocalProductAttributeV1(Code.exact("material"), LocalProductAttributeValueV1.Text(Code.exact("linen"))),
            LocalProductAttributeV1(Code.exact("wash_temperature"), LocalProductAttributeValueV1.Decimal(ExactDecimal.parse("30"))),
            LocalProductAttributeV1(Code.exact("note"), LocalProductAttributeValueV1.Text(Code.exact("synthetic"))))),
        "SHIRT_8_STATUS_EDIT" to body(revision = Revision(8), status = LocalProductStatusV1.ARCHIVED))

    fun sku(product: LocalPublishedProductV1? = shirt(), skuId: DomainId = blueMediumId,
        publicationId: DomainId = publication(41), skuRevision: Revision = Revision(41),
        selections: List<LocalVariantValueV1>? = blueMedium(), productId: DomainId = product?.id ?: shirtId,
        active: Boolean = true): LocalPublishedSkuV1 = LocalPublishedSkuV1(
        company = company, publicationId = publicationId, skuId = skuId, productId = productId,
        catalogRevision = Revision(101), skuRevision = skuRevision, names = LocalNamesV1("Cotton shirt", ""),
        code = if (skuId == blackSmallId) "SHIRT-BLACK-S-01" else "SHIRT-BLUE-M-01",
        barcodes = listOf(if (skuId == blackSmallId) "0000000000003" else "0000000000002"),
        baseUnitId = unit, quantityScale = 0, tracking = LocalTrackingV1.QUANTITY,
        fulfillment = LocalFulfillmentV1.RETAIL, requiresExpiry = false, canPurchase = true,
        canSell = false, active = active, conversions = emptyList(), prices = emptyList(),
        productDefinition = product, variantValues = selections)

    /** Fixed opaque signature framing for a codec round trip; no verification or authority. */
    fun envelope(sku: LocalPublishedSkuV1): LocalSignedSkuV1 =
        LocalSignedSkuV1(sku, publication(255), "00".repeat(64))

    /** Explicit SHIRT_PRIOR_0..6 and SHIRT_7 values; these are not native completions. */
    fun contentChain(): List<LocalPublishedSkuV1> = (0..6).map { revision ->
        sku(body(revision = Revision(revision.toLong()), names = LocalNamesV1("Cotton shirt revision $revision", "")),
            publicationId = publication(revision + 1), skuRevision = Revision(revision.toLong()))
    } + sku() + sku(skuId = blackSmallId, publicationId = publication(42),
        skuRevision = Revision(12), selections = blackSmall())

    fun assertShirt(value: LocalPublishedProductV1, message: String) {
        assertEquals(shirtId, value.id, message)
        assertEquals(Revision(7), value.revision, message)
        assertEquals("Cotton shirt", value.names.english, message)
        assertEquals("", value.names.arabic, message)
        assertEquals(listOf(clothing, tops), value.categoryIds, message)
        assertEquals(listOf(color, size), value.variantAxes.map { it.id }, message)
        assertEquals(listOf("color", "size"), value.variantAxes.map { it.code.original }, message)
        assertEquals(listOf(listOf("blue", "black"), listOf("s", "m")),
            value.variantAxes.map { axis -> axis.values.map { it.original } }, message)
        assertEquals(listOf("material", "wash_temperature", "note"), value.attributes.map { it.key.original }, message)
        assertEquals("cotton", assertIs<LocalProductAttributeValueV1.Text>(value.attributes[0].value, message).value.original, message)
        assertEquals("30", assertIs<LocalProductAttributeValueV1.Decimal>(value.attributes[1].value, message).value.canonicalString(), message)
        assertEquals("synthetic", assertIs<LocalProductAttributeValueV1.Text>(value.attributes[2].value, message).value.original, message)
        assertEquals(LocalProductStatusV1.ACTIVE, value.status, message)
    }
}

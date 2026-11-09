package com.ukkera.pos.catalog.store.independent.catalogunitsv3

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalSignedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalUnitConversionV1
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate

/** Literal expectations copied from frozen catalog-unit-authority-v3/fixtures.json. */
internal object CatalogUnitsV3Fixture {
    val organization = DomainId("10000000-aaaa-4000-8000-000000000001")
    val company = DomainId("10000000-aaaa-4000-8000-000000000002")
    val productA = DomainId("20000000-bbbb-4000-8000-000000000001")
    val skuA = DomainId("20000000-bbbb-4000-8000-000000000002")
    val productB = DomainId("20000000-bbbb-4000-8000-000000000003")
    val publication1 = DomainId("30000000-cccc-4000-8000-000000000001")
    val publication2 = DomainId("30000000-cccc-4000-8000-000000000002")
    val piece = DomainId("40000000-dddd-4000-8000-000000000001")
    val carton = DomainId("40000000-dddd-4000-8000-000000000002")
    val kilogram = DomainId("40000000-dddd-4000-8000-000000000003")
    val gram = DomainId("40000000-dddd-4000-8000-000000000004")
    val litre = DomainId("40000000-dddd-4000-8000-000000000005")
    val massPortion = DomainId("40000000-dddd-4000-8000-000000000006")
    val newPieceId = DomainId("40000000-dddd-4000-8000-000000000007")
    val legacyRaw = DomainId("40000000-dddd-4000-8000-000000000008")
    val conversion = DomainId("50000000-eeee-4000-8000-000000000001")
    val evidence = DomainId("50000000-eeee-4000-8000-000000000002")
    val root = DomainId("60000000-ffff-4000-8000-000000000001")
    val operation2 = DomainId("70000000-aaaa-4000-8000-000000000002")

    const val LEGACY_CANONICAL = "ukkera.company-sku-publication/v1\n{\"company\":{\"organizationId\":\"10000000-aaaa-4000-8000-000000000001\",\"companyId\":\"10000000-aaaa-4000-8000-000000000002\"},\"publicationId\":\"30000000-cccc-4000-8000-000000000001\",\"skuId\":\"20000000-bbbb-4000-8000-000000000002\",\"productId\":\"20000000-bbbb-4000-8000-000000000001\",\"catalogRevision\":\"1\",\"skuRevision\":\"1\",\"names\":{\"english\":\"Fixture\",\"arabic\":\"Fixture\"},\"code\":\"000012\",\"barcodes\":[],\"baseUnitId\":\"40000000-dddd-4000-8000-000000000001\",\"quantityScale\":0,\"tracking\":\"QUANTITY\",\"fulfillment\":\"RETAIL\",\"requiresExpiry\":false,\"canPurchase\":true,\"canSell\":false,\"active\":true,\"conversions\":[],\"prices\":[]}"
    const val EXTENDED_CANONICAL = "ukkera.company-sku-publication/v1\n{\"company\":{\"organizationId\":\"10000000-aaaa-4000-8000-000000000001\",\"companyId\":\"10000000-aaaa-4000-8000-000000000002\"},\"publicationId\":\"30000000-cccc-4000-8000-000000000001\",\"skuId\":\"20000000-bbbb-4000-8000-000000000002\",\"productId\":\"20000000-bbbb-4000-8000-000000000001\",\"catalogRevision\":\"1\",\"skuRevision\":\"1\",\"names\":{\"english\":\"Fixture\",\"arabic\":\"Fixture\"},\"code\":\"000012\",\"barcodes\":[],\"baseUnitId\":\"40000000-dddd-4000-8000-000000000001\",\"quantityScale\":0,\"tracking\":\"QUANTITY\",\"fulfillment\":\"RETAIL\",\"requiresExpiry\":false,\"canPurchase\":true,\"canSell\":false,\"active\":true,\"conversions\":[],\"prices\":[],\"unitDefinitions\":[{\"id\":\"40000000-dddd-4000-8000-000000000001\",\"code\":{\"original\":\"piece\",\"normalizedKey\":\"piece\",\"normalization\":\"EXACT_V1\"},\"dimension\":\"COUNT\",\"maximumScale\":0}]}"

    val scope = CompanyScope(organization, company)
    val pieceDefinition = CatalogUnit(piece, Code.exact("piece"), UnitDimension.COUNT, 0)
    val cartonDefinition = CatalogUnit(carton, Code.exact("carton"), UnitDimension.COUNT, 0)

    fun legacy(): LocalPublishedSkuV1 = LocalPublishedSkuV1(
        company = scope, publicationId = publication1, skuId = skuA, productId = productA,
        catalogRevision = Revision(1), skuRevision = Revision(1), names = LocalNamesV1("Fixture", "Fixture"),
        code = "000012", barcodes = emptyList(), baseUnitId = piece, quantityScale = 0,
        tracking = LocalTrackingV1.QUANTITY, fulfillment = LocalFulfillmentV1.RETAIL,
        requiresExpiry = false, canPurchase = true, canSell = false, active = true,
        conversions = emptyList(), prices = emptyList(),
    )

    fun firstHistory(): LocalPublishedSkuV1 = legacy().copy(
        unitDefinitions = listOf(pieceDefinition, cartonDefinition),
        conversions = listOf(LocalUnitConversionV1(conversion, Revision(1), carton, piece, UnitConversionRate.ratio(12, 1))),
    )

    fun secondHistory(): LocalPublishedSkuV1 = firstHistory().copy(
        publicationId = publication2, catalogRevision = Revision(2), skuRevision = Revision(2),
        conversions = listOf(LocalUnitConversionV1(conversion, Revision(2), carton, piece, UnitConversionRate.ratio(10, 1))),
    )

    /** Shape/history input only. No signature verifier or production admission accepts this fixture. */
    fun controlled(definition: LocalPublishedSkuV1): LocalSignedSkuV1 =
        LocalSignedSkuV1(definition, definition.publicationId, "01".repeat(64))
}

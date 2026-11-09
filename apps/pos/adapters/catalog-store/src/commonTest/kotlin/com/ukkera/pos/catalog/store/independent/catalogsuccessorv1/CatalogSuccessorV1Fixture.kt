package com.ukkera.pos.catalog.store.independent.catalogsuccessorv1

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalSignedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalUnitConversionV1
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

/**
 * Exact semantic inputs from catalog-successor-restart-v1/fixtures.json.
 * These public values and inert envelopes are not authenticated native originals,
 * a completed history, an activation, or a fixture for the runtime scan.
 */
internal object CatalogSuccessorV1Fixture {
    val company = CompanyScope(id("000001"), id("000002"))
    val each = id("000301")
    val carton = id("000302")
    val newCount = id("000303")
    val sku = id("000401")
    val product = id("000402")
    val otherProduct = id("000403")
    val conflictSku = id("000404")
    val conversion = id("000701")
    val saleDocument1 = id("001002")
    val saleDocument2 = id("001004")

    // U-WIRE-01 permits semantic public-type binding, not a price wire golden.
    // These IDs, revision labels and neutral selection fields are constructor-only
    // bindings for the standard row/zero tax; they confer no authority.
    private val standardRule = id("003101")
    private val zeroTaxPolicy = id("003201")
    private val inertKey = id("003301")
    val selectionAt = UtcInstant.parseCanonical("1970-01-01T00:00:00Z")

    val eachDefinition = CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)
    val cartonDefinition = CatalogUnit(carton, Code.exact("CTN"), UnitDimension.COUNT, 0)

    val p1 = LocalPublishedSkuV1(
        company = company,
        publicationId = id("000501"),
        skuId = sku,
        productId = product,
        catalogRevision = Revision(1),
        skuRevision = Revision(1),
        names = LocalNamesV1("Synthetic carton sample", "عبوة اختبار"),
        code = "000012",
        barcodes = listOf("000000000012"),
        baseUnitId = each,
        quantityScale = 0,
        tracking = LocalTrackingV1.QUANTITY,
        fulfillment = LocalFulfillmentV1.RETAIL,
        requiresExpiry = false,
        canPurchase = true,
        canSell = true,
        active = true,
        conversions = listOf(LocalUnitConversionV1(conversion, Revision(1), carton, each,
            UnitConversionRate.parseCanonical("12/1"))),
        prices = listOf(standardPrice(1, "3.25")),
        unitDefinitions = listOf(eachDefinition, cartonDefinition),
    )

    val p2 = p1.copy(
        publicationId = id("000502"),
        catalogRevision = Revision(2),
        skuRevision = Revision(2),
        conversions = listOf(LocalUnitConversionV1(conversion, Revision(2), carton, each,
            UnitConversionRate.parseCanonical("10/1"))),
        prices = listOf(standardPrice(2, "4.10")),
    )

    /** DTO shape only: never submit this envelope to a verifier, owner or Master. */
    fun inertEnvelope(definition: LocalPublishedSkuV1): LocalSignedSkuV1 =
        LocalSignedSkuV1(definition, inertKey, "00".repeat(64))

    private fun standardPrice(version: Long, amount: String): LocalPriceV1 = LocalPriceV1(
        ruleId = standardRule,
        revision = Revision(version),
        channel = null,
        priority = 0,
        minimumQuantity = Quantity(each, 1, 0),
        effectiveFrom = selectionAt,
        unitPrice = UnitPrice.parse(Currency.EGP, amount),
        inclusion = TaxInclusion.EXCLUSIVE,
        tax = TaxSnapshot(zeroTaxPolicy, Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")),
    )

    private fun id(suffix: String): DomainId = DomainId("00000000-0000-4000-8000-000000$suffix")
}

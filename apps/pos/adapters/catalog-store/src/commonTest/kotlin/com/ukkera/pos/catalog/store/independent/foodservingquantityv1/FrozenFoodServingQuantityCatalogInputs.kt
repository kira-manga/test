package com.ukkera.pos.catalog.store.independent.foodservingquantityv1

import com.ukkera.pos.catalog.store.LocalFoodIngredientDefinitionV1
import com.ukkera.pos.catalog.store.LocalFoodRecipeChoiceV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalPublishedFoodRecipeV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.PreparedLocalSkuPricing
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

/** Frozen business values with explicit synthetic SKU/UOM/recipe representation bindings. */
internal object FrozenFoodServingQuantityCatalogInputs {
    const val ITEM_NOTE = "no salt / بدون ملح"
    const val SEAT_NOTE = "Seat 02 / المقعد 02"

    val scope = BranchScope(
        DomainId("a0000000-0000-4000-8000-000000000001"),
        DomainId("a0000000-0000-4000-8000-000000000002"),
        DomainId("a0000000-0000-4000-8000-000000000004"),
    )
    val productId = DomainId("f1000000-0000-4000-8000-000000000001")
    val variantId = DomainId("f1000000-0000-4000-8000-000000000002")
    val cheeseOptionId = DomainId("f1000000-0000-4000-8000-000000000003")
    val lookalikeProductId = DomainId("f1000000-0000-4000-8000-000000000004")
    val lookalikeVariantId = DomainId("f1000000-0000-4000-8000-000000000005")
    val lookalikeCheeseOptionId = DomainId("f1000000-0000-4000-8000-000000000006")
    val lineId = DomainId("f3000000-0000-4000-8000-000000000001")
    val secondPortionId = DomainId("f3000000-0000-4000-8000-000000000002")
    val breadId = DomainId("f2000000-0000-4000-8000-000000000001")
    val meatId = DomainId("f2000000-0000-4000-8000-000000000002")
    val cheeseId = DomainId("f2000000-0000-4000-8000-000000000003")
    val stationId = DomainId("f2000000-0000-4000-8000-000000000005")

    val burgerUnitId = DomainId("f5000000-0000-4000-8000-000000000001")
    val eachUnitId = DomainId("f5000000-0000-4000-8000-000000000002")
    val recipeId = DomainId("f5000000-0000-4000-8000-000000000003")
    val originalPublicationId = DomainId("f5000000-0000-4000-8000-000000000004")
    val currentPublicationId = DomainId("f5000000-0000-4000-8000-000000000005")
    val gramUnitId = DomainId("f5000000-0000-4000-8000-000000000006")
    val quoteId = DomainId("f5000000-0000-4000-8000-000000000007")
    val taxPolicyId = DomainId("f5000000-0000-4000-8000-000000000008")
    val combinationSkuId = DomainId("f5000000-0000-4000-8000-000000000009")
    val combinationPriceId = DomainId("f5000000-0000-4000-8000-000000000010")
    val basePriceId = DomainId("f5000000-0000-4000-8000-000000000011")

    val at = UtcInstant.parseCanonical("2026-10-30T09:00:00Z")
    val one = Quantity(burgerUnitId, 1, 0)
    val two = Quantity(burgerUnitId, 2, 0)
    val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "T-02")
    // This codec/source input is fixed here; no actual native catalog cursor is asserted.
    val catalogCheckpointRevision = Revision(5)
    val zeroTax = TaxSnapshot(taxPolicyId, Revision(1), "fixture-zero", PercentageRate.parse("0"))

    val original = FrozenQuantityPriceSource(Revision(5), Revision(1), originalPublicationId,
        LocalNamesV1("Burger", "برجر"), "100.00", "110.00", 11_000, 22_000, 150)
    val current = FrozenQuantityPriceSource(Revision(6), Revision(2), currentPublicationId,
        LocalNamesV1("Burger today", "برجر اليوم"), "130.00", "140.00", 14_000, 28_000, 160)

    fun twoServings(source: FrozenQuantityPriceSource = original): List<LocalFoodPortionSelectionV1> =
        listOf(lineId, secondPortionId).map { portionId ->
            LocalFoodPortionSelectionV1(portionId, variantId, recipeId, source.recipeRevision,
                variantId, listOf(cheeseOptionId), one, ITEM_NOTE, lineId, SEAT_NOTE)
        }

    fun recipe(source: FrozenQuantityPriceSource): LocalPublishedFoodRecipeV1 = LocalPublishedFoodRecipeV1(
        scope.company(), source.publicationId, recipeId, source.recipeRevision, variantId, source.baseRevision,
        one, one, listOf(LocalFoodRecipeChoiceV1(variantId, source.names, listOf(cheeseOptionId), listOf(
            LocalFoodIngredientDefinitionV1(breadId, breadId, Revision(1), Quantity(eachUnitId, 1, 0)),
            LocalFoodIngredientDefinitionV1(meatId, meatId, Revision(1), Quantity(gramUnitId, source.meatGrams, 0)),
            LocalFoodIngredientDefinitionV1(cheeseId, cheeseId, Revision(1), Quantity(eachUnitId, 1, 0)),
        ), listOf(stationId), combinationSkuId)), true)

    fun definitions(source: FrozenQuantityPriceSource): Map<DomainId, LocalPublishedSkuV1> = mapOf(
        variantId to sku(variantId, productId, burgerUnitId, source.names, source, LocalFulfillmentV1.MADE_TO_ORDER,
            listOf(price(source, basePriceId, source.basePrice))),
        combinationSkuId to combinationSku(source),
        breadId to ingredient(breadId, eachUnitId, "bread", source),
        meatId to ingredient(meatId, gramUnitId, "meat", source),
        cheeseId to ingredient(cheeseId, eachUnitId, "cheese", source),
    )

    fun preparedPrice(source: FrozenQuantityPriceSource): PreparedLocalSkuPricing = PreparedLocalSkuPricing(
        scope, combinationSku(source), emptyMap(), emptyMap(), emptyMap(), emptySet())

    private fun combinationSku(source: FrozenQuantityPriceSource): LocalPublishedSkuV1 =
        sku(combinationSkuId, productId, burgerUnitId, source.names, source, LocalFulfillmentV1.MADE_TO_ORDER,
            listOf(price(source, combinationPriceId, source.combinationPrice)))

    private fun price(source: FrozenQuantityPriceSource, id: DomainId, amount: String): LocalPriceV1 =
        LocalPriceV1(id, source.baseRevision, "DINE_IN", 0, one, effectiveFrom = at,
            unitPrice = UnitPrice.parse(Currency.EGP, amount), inclusion = TaxInclusion.INCLUSIVE, tax = zeroTax)

    private fun ingredient(id: DomainId, unitId: DomainId, name: String, source: FrozenQuantityPriceSource): LocalPublishedSkuV1 =
        sku(id, id, unitId, LocalNamesV1(name, ""), source, LocalFulfillmentV1.RETAIL, emptyList()).copy(skuRevision = Revision(1))

    private fun sku(id: DomainId, product: DomainId, unitId: DomainId, names: LocalNamesV1,
        source: FrozenQuantityPriceSource, fulfillment: LocalFulfillmentV1, prices: List<LocalPriceV1>): LocalPublishedSkuV1 =
        LocalPublishedSkuV1(
            company = scope.company(), publicationId = source.publicationId, skuId = id, productId = product,
            catalogRevision = source.baseRevision, skuRevision = source.baseRevision, names = names,
            code = id.value, barcodes = emptyList(), baseUnitId = unitId, quantityScale = 0,
            tracking = LocalTrackingV1.QUANTITY, fulfillment = fulfillment, requiresExpiry = false,
            canPurchase = fulfillment == LocalFulfillmentV1.RETAIL, canSell = prices.isNotEmpty(), active = true,
            conversions = emptyList(), prices = prices,
        )
}

internal data class FrozenQuantityPriceSource(
    val baseRevision: Revision,
    val recipeRevision: Revision,
    val publicationId: DomainId,
    val names: LocalNamesV1,
    val basePrice: String,
    val combinationPrice: String,
    val unitMinor: Long,
    val payableMinor: Long,
    val meatGrams: Long,
)

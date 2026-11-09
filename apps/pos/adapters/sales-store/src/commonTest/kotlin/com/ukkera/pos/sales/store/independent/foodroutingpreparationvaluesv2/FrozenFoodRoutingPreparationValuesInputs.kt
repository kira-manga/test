package com.ukkera.pos.sales.store.independent.foodroutingpreparationvaluesv2

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.LocalFoodIngredientV1
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.LocalFoodSourceReferenceV1
import com.ukkera.pos.sales.store.LocalFoodStationRouteV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** Literal fixtures from food-routing-preparation-values-v2; dummy bytes prove no authenticity. */
internal object FrozenFoodRoutingPreparationValuesInputs {
    fun u(n: Int): DomainId = DomainId("70000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    fun q(unit: Int, scaled: Long, scale: Int): Quantity = Quantity(u(unit), scaled, scale)

    val quotationId: DomainId = DomainId("78859e14-e04e-7099-c492-ddb03f902b18")
    val snapshotAId: DomainId = DomainId("5b55bd86-c685-c7af-09d1-339d7eb03ca8")
    val snapshotBId: DomainId = DomainId("a495674d-67d3-1825-7734-dbe4535a8af4")
    const val DESCRIPTION: String = "Tomato bowl | EXTRA basil; no onions"
    const val SIGNED_PUBLICATION: String = """{"definition":{"company":{"organizationId":"70000000-0000-4000-8000-000000000001","companyId":"70000000-0000-4000-8000-000000000002"},"publicationId":"70000000-0000-4000-8000-000000000005","recipeId":"70000000-0000-4000-8000-000000000006","revision":"11","outputSkuId":"70000000-0000-4000-8000-000000000007","outputSkuRevision":"7","yieldQuantity":{"unitId":"70000000-0000-4000-8000-000000000013","scaled":"400","scale":2},"portionQuantity":{"unitId":"70000000-0000-4000-8000-000000000013","scaled":"150","scale":2},"choices":[{"id":"70000000-0000-4000-8000-000000000008","names":{"english":"Tomato bowl","arabic":"Tomato bowl"},"modifierIds":["70000000-0000-4000-8000-000000000011","70000000-0000-4000-8000-000000000010"],"ingredients":[{"componentId":"70000000-0000-4000-8000-000000000015","skuId":"70000000-0000-4000-8000-000000000016","skuRevision":"13","quantityPerYield":{"unitId":"70000000-0000-4000-8000-000000000012","scaled":"1200","scale":3}},{"componentId":"70000000-0000-4000-8000-000000000014","skuId":"70000000-0000-4000-8000-000000000016","skuRevision":"13","quantityPerYield":{"unitId":"70000000-0000-4000-8000-000000000012","scaled":"800","scale":3}}],"routeKeys":["70000000-0000-4000-8000-000000000018","70000000-0000-4000-8000-000000000017"],"pricingSkuId":"70000000-0000-4000-8000-000000000009"}],"active":true},"signingKeyId":"70000000-0000-4000-8000-000000000024","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}"""

    val c28: LocalFoodSourceReferenceV1 = reference("local.catalog.publication", 28, 5,
        "725a7d4a0907ebce0243577beb7c6c82b514581ab0377e6d44ffc878e0924f7e", 34, 24, null)
    val f5: LocalFoodSourceReferenceV1 = reference("local.food.publication", 5, 3,
        "66bbc5eb9ecc9abd778336c556651bc285484807d96917d39377834fae579bd0", 34, 24, 26)
    val c27: LocalFoodSourceReferenceV1 = reference("local.catalog.publication", 27, 4,
        "0ed097adff523ed9407764ec7c84ffb47ad5d0edf67cda0c27ef1ac4f883d87b", 34, 24, null)
    val ra21: LocalFoodSourceReferenceV1 = reference("local.food.configuration-version", 21, 6,
        "b66ebf2a626f33ac1747cfdd2d7f4022125eb3e46ebdc66a7cd213a260673006", 35, null, 21)
    val sa23: LocalFoodSourceReferenceV1 = reference("local.food.configuration-version", 23, 2,
        "fea3d2dd6a09f33d8790b0bf795f5c66a94e4ffb3d2808b03366694c79a2bae4", 35, null, 23)
    val sa22: LocalFoodSourceReferenceV1 = reference("local.food.configuration-version", 22, 8,
        "eacf3d36a7a8a8627f0cc45f366e1fb12b213cb11462c2dd36d070684c836eb3", 35, null, 22)
    val rb29: LocalFoodSourceReferenceV1 = reference("local.food.configuration-version", 29, 7,
        "61a11c6c8c7a9369fe2c0c9bd95fbc3aa62e92a5bf52e9a4e1c719dea9a01a48", 35, null, 29)
    val sb33: LocalFoodSourceReferenceV1 = reference("local.food.configuration-version", 33, 4,
        "52582334a16e5a694392eee5be14e8293de01dfb43670bd3efbe34fb4c688bf8", 35, null, 33)
    val sb32: LocalFoodSourceReferenceV1 = reference("local.food.configuration-version", 32, 9,
        "f5ba3f3d7c60df3ee9652fa335de9abe0cb39cc788cd2d2795abbb285c833f5e", 35, null, 32)

    fun modifiers(): List<DomainId> = listOf(u(11), u(10))
    fun routeKeys(): List<DomainId> = listOf(u(18), u(17))
    fun ingredients(): List<LocalFoodIngredientV1> = listOf(
        LocalFoodIngredientV1(u(15), u(16), Revision(13), q(12, 1200, 3), q(12, 450, 3)),
        LocalFoodIngredientV1(u(14), u(16), Revision(13), q(12, 800, 3), q(12, 300, 3)),
    )
    fun originalSources(): List<LocalFoodSourceReferenceV1> = listOf(c28, f5, c27)
    fun routesA(): List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(5), FoodStationDeliveryMode.PAPER),
        LocalFoodStationRouteV1(u(17), u(19), Revision(9), FoodStationDeliveryMode.DISPLAY),
    )
    fun routesB(): List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(10), FoodStationDeliveryMode.DISPLAY),
        LocalFoodStationRouteV1(u(17), u(19), Revision(12), FoodStationDeliveryMode.PAPER),
    )
    fun sourcesA(): List<LocalFoodSourceReferenceV1> = listOf(ra21, sa23, sa22)
    fun sourcesB(): List<LocalFoodSourceReferenceV1> = listOf(rb29, sb33, sb32)
    fun scope(): BranchScope = BranchScope(u(1), u(2), u(3))
    fun resources(): Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, u(3)),
        RecoveryResourceKey(RecoveryResourceKind.STOCK, u(30)),
        RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, u(4)),
    )

    fun quotation(
        activity: LocalSalesActivity = LocalSalesActivity.RESTAURANT,
        channel: LocalSalesChannel = LocalSalesChannel.DINE_IN,
        modifierIds: List<DomainId> = modifiers(),
        ingredientValues: List<LocalFoodIngredientV1> = ingredients(),
        requiredRouteKeys: List<DomainId> = routeKeys(),
        sourceValues: List<LocalFoodSourceReferenceV1> = originalSources(),
    ): LocalFoodRecipeQuotationV1 = LocalFoodRecipeQuotationV1(
        quotationId = quotationId, portionId = u(4), activity = activity, channel = channel,
        recipeId = u(6), recipeRevision = Revision(11), publicationId = u(5),
        outputSkuId = u(7), outputSkuRevision = Revision(7), choiceId = u(8),
        selectedModifiers = modifierIds, description = DESCRIPTION,
        yieldQuantity = q(13, 400, 2), portionQuantity = q(13, 150, 2), pricingSkuId = u(9),
        selectedIngredients = ingredientValues, requiredRouteKeys = requiredRouteKeys,
        signedPublication = SIGNED_PUBLICATION, publicationSources = sourceValues,
    )

    private fun reference(kind: String, id: Int, revision: Long, digest: String, authority: Int,
        signingKey: Int?, operation: Int?): LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1(
        kind, u(id), Revision(revision), PayloadDigest(digest), u(authority),
        signingKey?.let(::u), operation?.let { OperationId(u(it).value) },
    )
}

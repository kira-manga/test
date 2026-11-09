package com.ukkera.pos.sales.store.independent.foodoriginalroutingcorrespondencev1

import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.LocalFoodIngredientV1
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.LocalFoodRecipeSnapshotV1
import com.ukkera.pos.sales.store.LocalFoodSourceReferenceV1
import com.ukkera.pos.sales.store.LocalFoodStationRouteV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** Frozen correspondence-v1 inputs incorporate values-v2 dummy fixtures, without source authority. */
internal object FrozenFoodOriginalRoutingInputs {
    fun u(n: Int): DomainId = DomainId("70000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    fun q(unit: Int, scaled: Long, scale: Int): Quantity = Quantity(u(unit), scaled, scale)

    val quotationId: DomainId = DomainId("78859e14-e04e-7099-c492-ddb03f902b18")
    val snapshotAId: DomainId = DomainId("5b55bd86-c685-c7af-09d1-339d7eb03ca8")
    val snapshotBId: DomainId = DomainId("a495674d-67d3-1825-7734-dbe4535a8af4")
    val changedPortionSnapshotId: DomainId = DomainId("50e6acd1-5cd2-487f-c7e0-a6d09f729d99")
    val changedPublicationSnapshotId: DomainId = DomainId("4e11f9a1-8daf-66af-d02f-7b39cb9c0431")
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
    fun ingredients(): List<LocalFoodIngredientV1> = listOf(
        LocalFoodIngredientV1(u(15), u(16), Revision(13), q(12, 1200, 3), q(12, 450, 3)),
        LocalFoodIngredientV1(u(14), u(16), Revision(13), q(12, 800, 3), q(12, 300, 3)),
    )
    fun originalSources(): List<LocalFoodSourceReferenceV1> = listOf(c28, f5, c27)
    fun sourcesA(): List<LocalFoodSourceReferenceV1> = listOf(ra21, sa23, sa22)
    fun sourcesB(): List<LocalFoodSourceReferenceV1> = listOf(rb29, sb33, sb32)
    fun routesA(): List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(5), FoodStationDeliveryMode.PAPER),
        LocalFoodStationRouteV1(u(17), u(19), Revision(9), FoodStationDeliveryMode.DISPLAY),
    )
    fun routesB(): List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(10), FoodStationDeliveryMode.DISPLAY),
        LocalFoodStationRouteV1(u(17), u(19), Revision(12), FoodStationDeliveryMode.PAPER),
    )

    fun quotation(): LocalFoodRecipeQuotationV1 = LocalFoodRecipeQuotationV1(
        quotationId, u(4), LocalSalesActivity.RESTAURANT, LocalSalesChannel.DINE_IN,
        u(6), Revision(11), u(5), u(7), Revision(7), u(8), modifiers(), DESCRIPTION,
        q(13, 400, 2), q(13, 150, 2), u(9), ingredients(), listOf(u(18), u(17)),
        SIGNED_PUBLICATION, originalSources(),
    )

    fun a0(
        modifierIds: List<DomainId> = modifiers(),
        ingredientValues: List<LocalFoodIngredientV1> = ingredients(),
        routes: List<LocalFoodStationRouteV1> = routesA(),
        sources: List<LocalFoodSourceReferenceV1> = originalSources() + sourcesA(),
    ): LocalFoodRecipeSnapshotV1 = candidate(snapshotAId, u(21), u(30), modifierIds, ingredientValues, routes, sources)

    fun b0(): LocalFoodRecipeSnapshotV1 = candidate(snapshotBId, u(29), u(31), modifiers(), ingredients(),
        routesB(), originalSources() + sourcesB())

    fun a1(): LocalFoodRecipeSnapshotV1 = a0(sources = originalSources() + listOf(sa22, ra21, sa23))

    private fun candidate(snapshotId: DomainId, version: DomainId, warehouse: DomainId,
        modifiers: List<DomainId>, ingredients: List<LocalFoodIngredientV1>,
        routes: List<LocalFoodStationRouteV1>, sources: List<LocalFoodSourceReferenceV1>): LocalFoodRecipeSnapshotV1 =
        LocalFoodRecipeSnapshotV1(
            snapshotId = snapshotId, portionId = u(4), routeVersionId = version,
            activity = LocalSalesActivity.RESTAURANT, channel = LocalSalesChannel.DINE_IN,
            recipeId = u(6), recipeRevision = Revision(11), publicationId = u(5),
            outputSkuId = u(7), outputSkuRevision = Revision(7), choiceId = u(8),
            modifierIds = modifiers, description = DESCRIPTION, yieldQuantity = q(13, 400, 2),
            portionQuantity = q(13, 150, 2), pricingSkuId = u(9), ingredients = ingredients,
            warehouseId = warehouse, routes = routes, signedPublication = SIGNED_PUBLICATION, sources = sources,
        )

    private fun reference(kind: String, id: Int, revision: Long, digest: String, authority: Int,
        key: Int?, operation: Int?): LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1(
        kind, u(id), Revision(revision), PayloadDigest(digest), u(authority),
        key?.let(::u), operation?.let { OperationId(u(it).value) },
    )
}

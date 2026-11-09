package com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1

import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.FoodBodyV1
import com.ukkera.pos.sales.store.FoodEnvelopeV1
import com.ukkera.pos.sales.store.LocalFoodIngredientV1
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.LocalFoodRecipeSnapshotV1
import com.ukkera.pos.sales.store.LocalFoodSourceReferenceV1
import com.ukkera.pos.sales.store.LocalFoodStationRouteV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.LocalSalesPriceSourceV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice

/** Literal value inputs from the resources v1 freeze and its unchanged incorporated freezes. */
internal object FrozenFoodResourceValues {
    fun u(number: Int): DomainId = DomainId("70000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun operation(number: Int): OperationId = OperationId(u(number).value)
    fun quantity(unit: Int, scaled: Long, scale: Int): Quantity = Quantity(u(unit), scaled, scale)
    fun price(decimal: String): UnitPrice = UnitPrice.parse(Currency.EGP, decimal)
    fun key(kind: RecoveryResourceKind, id: DomainId): RecoveryResourceKey = RecoveryResourceKey(kind, id)

    val quotation1Id = DomainId("78859e14-e04e-7099-c492-ddb03f902b18")
    val quotation2Id = DomainId("7ba5465b-442c-1fa6-4d0c-fbcaa6e3a726")
    val snapshotAId = DomainId("5b55bd86-c685-c7af-09d1-339d7eb03ca8")
    val snapshotBId = DomainId("a495674d-67d3-1825-7734-dbe4535a8af4")
    val snapshotA2Id = DomainId("63c10a5e-46ee-46d3-61d3-18a2c1c9ccf9")
    const val description = "Tomato bowl | EXTRA basil; no onions"
    const val signedPublication = """{"definition":{"company":{"organizationId":"70000000-0000-4000-8000-000000000001","companyId":"70000000-0000-4000-8000-000000000002"},"publicationId":"70000000-0000-4000-8000-000000000005","recipeId":"70000000-0000-4000-8000-000000000006","revision":"11","outputSkuId":"70000000-0000-4000-8000-000000000007","outputSkuRevision":"7","yieldQuantity":{"unitId":"70000000-0000-4000-8000-000000000013","scaled":"400","scale":2},"portionQuantity":{"unitId":"70000000-0000-4000-8000-000000000013","scaled":"150","scale":2},"choices":[{"id":"70000000-0000-4000-8000-000000000008","names":{"english":"Tomato bowl","arabic":"Tomato bowl"},"modifierIds":["70000000-0000-4000-8000-000000000011","70000000-0000-4000-8000-000000000010"],"ingredients":[{"componentId":"70000000-0000-4000-8000-000000000015","skuId":"70000000-0000-4000-8000-000000000016","skuRevision":"13","quantityPerYield":{"unitId":"70000000-0000-4000-8000-000000000012","scaled":"1200","scale":3}},{"componentId":"70000000-0000-4000-8000-000000000014","skuId":"70000000-0000-4000-8000-000000000016","skuRevision":"13","quantityPerYield":{"unitId":"70000000-0000-4000-8000-000000000012","scaled":"800","scale":3}}],"routeKeys":["70000000-0000-4000-8000-000000000018","70000000-0000-4000-8000-000000000017"],"pricingSkuId":"70000000-0000-4000-8000-000000000009"}],"active":true},"signingKeyId":"70000000-0000-4000-8000-000000000024","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}"""
    const val policyDigest = "8c177fa56b8071333540359d43a1bdbca6f4dc94a3cfbcbdda7f2a9a6d5db979"
    const val quotedSchema = "sales.food.portion/quotation-v1"
    const val routedSchema = "sales.food.portion/stock-v2"
    const val sidecarSchema = "sales.food.recipe-quotation/v1"
    const val batchSchema = "sales.food.price-batch/stock-v2"

    private val originalAuthorization = listOf(6, 5, 7, 9, 16, 28, 34, 24, 26, 27, 108, 109).map(::u)
    private val originalStock = listOf(7, 9, 16).map(::u)

    // Only literal freeze tables define these sets; production collectors are never an oracle.
    val e1 = exact(listOf(u(100), u(4), quotation1Id, u(101)), originalAuthorization, originalStock)
    val e2 = exact(listOf(u(100), u(104), quotation2Id, u(101)), originalAuthorization, originalStock)
    private val routingAAuthorization = listOf(30, 20, 19, 21, 23, 22, 35).map(::u)
    val ra = e1 + exact(listOf(snapshotAId, u(20), u(19)), routingAAuthorization, listOf(u(30)))
    val ra2 = e2 + exact(listOf(snapshotA2Id, u(20), u(19)), routingAAuthorization, listOf(u(30)))
    val rb = e1 + exact(listOf(snapshotBId, u(20), u(19)),
        listOf(31, 20, 19, 29, 33, 32, 35).map(::u), listOf(u(31)))
    val e2Other = e2 - authorization(108, 109) + authorization(145, 146)
    val e1Policy = e1 + authorization(130, 131)
    val e1Override = e1 + authorization(130, 131, 132)
    val raOverride = ra + authorization(130, 131, 132)

    fun authorization(vararg ids: Int): Set<RecoveryResourceKey> =
        ids.map { key(RecoveryResourceKind.AUTHORIZATION, u(it)) }.toSet()

    private fun exact(custody: List<DomainId>, authorization: List<DomainId>, stock: List<DomainId>): Set<RecoveryResourceKey> =
        (custody.map { key(RecoveryResourceKind.ORDER_CUSTODY, it) } +
            authorization.map { key(RecoveryResourceKind.AUTHORIZATION, it) } +
            stock.map { key(RecoveryResourceKind.STOCK, it) }).toSet()
}

internal data class FrozenFoodResourceTriple(
    val portion: FoodEnvelopeV1,
    val sidecar: FoodEnvelopeV1,
    val priceBatch: FoodEnvelopeV1,
    val portionSchema: String,
) {
    val schemas: List<String> get() = listOf(portionSchema, FrozenFoodResourceValues.sidecarSchema,
        FrozenFoodResourceValues.batchSchema)
    val envelopes: List<FoodEnvelopeV1> get() = listOf(portion, sidecar, priceBatch)
}

/** Construction only. The pricing factory supplies a valid snapshot, never an amount oracle. */
internal class FrozenFoodResourceFixture {
    private fun u(number: Int) = FrozenFoodResourceValues.u(number)
    private fun q(unit: Int, scaled: Long, scale: Int) = FrozenFoodResourceValues.quantity(unit, scaled, scale)
    private fun operation(number: Int) = FrozenFoodResourceValues.operation(number)
    val scope = BranchScope(u(1), u(2), u(3))
    val i1 = LocalFoodIngredientV1(u(15), u(16), Revision(13), q(12, 1200, 3), q(12, 450, 3))
    val i2 = LocalFoodIngredientV1(u(14), u(16), Revision(13), q(12, 800, 3), q(12, 300, 3))
    val c28 = original("local.catalog.publication", 28, 5,
        "725a7d4a0907ebce0243577beb7c6c82b514581ab0377e6d44ffc878e0924f7e", null)
    val f5 = original("local.food.publication", 5, 3,
        "66bbc5eb9ecc9abd778336c556651bc285484807d96917d39377834fae579bd0", operation(26))
    val c27 = original("local.catalog.publication", 27, 4,
        "0ed097adff523ed9407764ec7c84ffb47ad5d0edf67cda0c27ef1ac4f883d87b", null)
    val ra21 = version(21, 6, "b66ebf2a626f33ac1747cfdd2d7f4022125eb3e46ebdc66a7cd213a260673006")
    val sa23 = version(23, 2, "fea3d2dd6a09f33d8790b0bf795f5c66a94e4ffb3d2808b03366694c79a2bae4")
    val sa22 = version(22, 8, "eacf3d36a7a8a8627f0cc45f366e1fb12b213cb11462c2dd36d070684c836eb3")
    val rb29 = version(29, 7, "61a11c6c8c7a9369fe2c0c9bd95fbc3aa62e92a5bf52e9a4e1c719dea9a01a48")
    val sb33 = version(33, 4, "52582334a16e5a694392eee5be14e8293de01dfb43670bd3efbe34fb4c688bf8")
    val sb32 = version(32, 9, "f5ba3f3d7c60df3ee9652fa335de9abe0cb39cc788cd2d2795abbb285c833f5e")
    val originals = listOf(c28, f5, c27)
    val routesA = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(5), FoodStationDeliveryMode.PAPER),
        LocalFoodStationRouteV1(u(17), u(19), Revision(9), FoodStationDeliveryMode.DISPLAY),
    )
    val routesB = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(10), FoodStationDeliveryMode.DISPLAY),
        LocalFoodStationRouteV1(u(17), u(19), Revision(12), FoodStationDeliveryMode.PAPER),
    )
    val sourcesA = originals + listOf(ra21, sa23, sa22)
    val quotation1 = quotation(u(4), FrozenFoodResourceValues.quotation1Id)
    val quotation2 = quotation(u(104), FrozenFoodResourceValues.quotation2Id)
    val a0 = snapshot(u(4), FrozenFoodResourceValues.snapshotAId, u(21), u(30), routesA, sourcesA)
    val b0 = snapshot(u(4), FrozenFoodResourceValues.snapshotBId, u(29), u(31), routesB,
        originals + listOf(rb29, sb33, sb32))
    val a1 = snapshot(u(4), FrozenFoodResourceValues.snapshotAId, u(21), u(30), routesA,
        originals + listOf(sa22, ra21, sa23))
    val a2 = snapshot(u(104), FrozenFoodResourceValues.snapshotA2Id, u(21), u(30), routesA, sourcesA)
    val ps1 = priceSource(u(4))
    val ps2 = priceSource(u(104))
    val pricing = pricingSnapshot()
    val t: String = StoredPricingSnapshotCodec.encode(pricing)
    val priceBatch = batch(t, listOf(ps1, ps2))
    val sidecar1 = sidecar(quotation1)
    val sidecar2 = sidecar(quotation2)
    val quoted1 = quoted(u(4), FrozenFoodResourceValues.quotation1Id)
    val quoted2 = quoted(u(104), FrozenFoodResourceValues.quotation2Id)

    fun quotedTriple(member: Int = 1, batch: FoodEnvelopeV1 = priceBatch): FrozenFoodResourceTriple =
        FrozenFoodResourceTriple(if (member == 1) quoted1 else quoted2,
            if (member == 1) sidecar1 else sidecar2, batch, FrozenFoodResourceValues.quotedSchema)

    fun routedTriple(recipe: LocalFoodRecipeSnapshotV1 = a0, batch: FoodEnvelopeV1 = priceBatch,
        laterOperation: OperationId = operation(103)): FrozenFoodResourceTriple =
        FrozenFoodResourceTriple(FoodEnvelopeV1(scope, "sales.food.portion", recipe.portionId, Revision(2), laterOperation,
            FoodBodyV1.Portion(u(100), selection(recipe.portionId), u(101), recipe, preparation(recipe.portionId, 1))),
            if (recipe.portionId == u(104)) sidecar2 else sidecar1, batch, FrozenFoodResourceValues.routedSchema)

    fun batch(quote: String = t, sources: List<LocalSalesPriceSourceV2>): FoodEnvelopeV1 =
        FoodEnvelopeV1(scope, "sales.food.price-batch", u(101), Revision(1), operation(102),
            FoodBodyV1.PriceBatch(u(100), quote, sources))

    fun pricingSnapshot(snapshotId: DomainId = u(101), revision: Revision = Revision(0),
        firstQuantity: Quantity = q(13, 150, 2), firstRevision: Revision = Revision(5),
        firstPrice: UnitPrice = FrozenFoodResourceValues.price("110.00")): PricingSnapshot =
        OperationalPricingEngine.calculate(PricingRequest(snapshotId, revision, Currency.EGP,
            listOf(resolved(u(4), firstQuantity, firstRevision, firstPrice),
                resolved(u(104), q(13, 150, 2), Revision(5), FrozenFoodResourceValues.price("110.00")))))

    fun quotation(portion: DomainId = u(4), id: DomainId = FrozenFoodResourceValues.quotation1Id,
        modifiers: List<DomainId> = listOf(u(11), u(10)),
        ingredients: List<LocalFoodIngredientV1> = listOf(i1, i2),
        routeKeys: List<DomainId> = listOf(u(18), u(17)),
        sources: List<LocalFoodSourceReferenceV1> = originals): LocalFoodRecipeQuotationV1 =
        LocalFoodRecipeQuotationV1(id, portion, LocalSalesActivity.RESTAURANT, LocalSalesChannel.DINE_IN,
            u(6), Revision(11), u(5), u(7), Revision(7), u(8), modifiers,
            FrozenFoodResourceValues.description, q(13, 400, 2), q(13, 150, 2), u(9),
            ingredients, routeKeys, FrozenFoodResourceValues.signedPublication, sources)

    private fun snapshot(portion: DomainId, id: DomainId, routeVersion: DomainId, warehouse: DomainId,
        routes: List<LocalFoodStationRouteV1>, sources: List<LocalFoodSourceReferenceV1>): LocalFoodRecipeSnapshotV1 =
        LocalFoodRecipeSnapshotV1(id, portion, routeVersion, LocalSalesActivity.RESTAURANT, LocalSalesChannel.DINE_IN,
            u(6), Revision(11), u(5), u(7), Revision(7), u(8), listOf(u(11), u(10)),
            FrozenFoodResourceValues.description, q(13, 400, 2), q(13, 150, 2), u(9), listOf(i1, i2),
            warehouse, routes, FrozenFoodResourceValues.signedPublication, sources)

    private fun sidecar(quotation: LocalFoodRecipeQuotationV1): FoodEnvelopeV1 =
        FoodEnvelopeV1(scope, "sales.food.recipe-quotation", quotation.quotationId, Revision(1), operation(102),
            FoodBodyV1.RecipeQuotation(u(100), u(101), quotation))

    private fun quoted(portion: DomainId, quotationId: DomainId): FoodEnvelopeV1 =
        FoodEnvelopeV1(scope, "sales.food.portion", portion, Revision(1), operation(102),
            FoodBodyV1.QuotedPortion(u(100), selection(portion), u(101), quotationId, preparation(portion, 0)))

    private fun selection(portion: DomainId): LocalFoodPortionSelectionV1 =
        LocalFoodPortionSelectionV1(portion, u(7), u(6), Revision(11), u(8), listOf(u(10), u(11)),
            q(13, 150, 2), "No added salt", u(4), "Seat 7")

    private fun preparation(portion: DomainId, revision: Long) =
        FoodPreparationState(portion, Revision(revision), FoodPreparationStatus.NOT_SENT)

    private fun priceSource(portion: DomainId): LocalSalesPriceSourceV2 =
        LocalSalesPriceSourceV2(scope, u(4), portion, u(9), u(108), u(109), Revision(5),
            PayloadDigest("b3db074c3128d10d016d2277d5bf14a0a682b0e1e71cd4b316a5ada5e29b80da"),
            FrozenFoodResourceValues.price("110.00"), FrozenFoodResourceValues.price("110.00"),
            null, null, null, null, null, null)

    private fun resolved(portion: DomainId, quantity: Quantity, revision: Revision, price: UnitPrice) =
        ResolvedPriceLine(u(4), portion, revision, quantity, price, TaxInclusion.EXCLUSIVE,
            TaxSnapshot(u(110), Revision(1), "FOOD", PercentageRate.parse("0")))

    private fun original(kind: String, id: Int, revision: Long, digest: String, operation: OperationId?) =
        LocalFoodSourceReferenceV1(kind, u(id), Revision(revision), PayloadDigest(digest), u(34), u(24), operation)

    private fun version(id: Int, revision: Long, digest: String) =
        LocalFoodSourceReferenceV1("local.food.configuration-version", u(id), Revision(revision),
            PayloadDigest(digest), u(35), null, operation(id))
}

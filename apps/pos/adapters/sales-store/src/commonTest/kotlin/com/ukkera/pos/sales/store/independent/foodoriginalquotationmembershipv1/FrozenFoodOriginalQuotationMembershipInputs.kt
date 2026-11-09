package com.ukkera.pos.sales.store.independent.foodoriginalquotationmembershipv1

import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Literal fixtures from food-original-quotation-membership-v1 and its incorporated freezes. */
internal object FrozenFoodOriginalQuotationMembershipInputs {
    fun u(number: Int): DomainId = DomainId("70000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun op(number: Int): OperationId = OperationId(u(number).value)
    fun q(unit: Int, scaled: Long, scale: Int): Quantity = Quantity(u(unit), scaled, scale)

    val scope: BranchScope = BranchScope(u(1), u(2), u(3))
    val quotation1Id: DomainId = DomainId("78859e14-e04e-7099-c492-ddb03f902b18")
    val quotation2Id: DomainId = DomainId("7ba5465b-442c-1fa6-4d0c-fbcaa6e3a726")
    val quotationXId: DomainId = DomainId("f4a07131-cd8c-83d2-d257-e1502b7b75e6")
    val snapshotA0Id: DomainId = DomainId("5b55bd86-c685-c7af-09d1-339d7eb03ca8")
    val snapshotA2Id: DomainId = DomainId("63c10a5e-46ee-46d3-61d3-18a2c1c9ccf9")
    val snapshotAXId: DomainId = DomainId("50e6acd1-5cd2-487f-c7e0-a6d09f729d99")
    val snapshotB0Id: DomainId = DomainId("a495674d-67d3-1825-7734-dbe4535a8af4")
    val changedPublicationSnapshotId: DomainId = DomainId("4e11f9a1-8daf-66af-d02f-7b39cb9c0431")

    const val description: String = "Tomato bowl | EXTRA basil; no onions"
    const val signedPublication: String = """{"definition":{"company":{"organizationId":"70000000-0000-4000-8000-000000000001","companyId":"70000000-0000-4000-8000-000000000002"},"publicationId":"70000000-0000-4000-8000-000000000005","recipeId":"70000000-0000-4000-8000-000000000006","revision":"11","outputSkuId":"70000000-0000-4000-8000-000000000007","outputSkuRevision":"7","yieldQuantity":{"unitId":"70000000-0000-4000-8000-000000000013","scaled":"400","scale":2},"portionQuantity":{"unitId":"70000000-0000-4000-8000-000000000013","scaled":"150","scale":2},"choices":[{"id":"70000000-0000-4000-8000-000000000008","names":{"english":"Tomato bowl","arabic":"Tomato bowl"},"modifierIds":["70000000-0000-4000-8000-000000000011","70000000-0000-4000-8000-000000000010"],"ingredients":[{"componentId":"70000000-0000-4000-8000-000000000015","skuId":"70000000-0000-4000-8000-000000000016","skuRevision":"13","quantityPerYield":{"unitId":"70000000-0000-4000-8000-000000000012","scaled":"1200","scale":3}},{"componentId":"70000000-0000-4000-8000-000000000014","skuId":"70000000-0000-4000-8000-000000000016","skuRevision":"13","quantityPerYield":{"unitId":"70000000-0000-4000-8000-000000000012","scaled":"800","scale":3}}],"routeKeys":["70000000-0000-4000-8000-000000000018","70000000-0000-4000-8000-000000000017"],"pricingSkuId":"70000000-0000-4000-8000-000000000009"}],"active":true},"signingKeyId":"70000000-0000-4000-8000-000000000024","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}"""

    val c28: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.catalog.publication", u(28), Revision(5),
        PayloadDigest("725a7d4a0907ebce0243577beb7c6c82b514581ab0377e6d44ffc878e0924f7e"), u(34), u(24), null)
    val f5: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.food.publication", u(5), Revision(3),
        PayloadDigest("66bbc5eb9ecc9abd778336c556651bc285484807d96917d39377834fae579bd0"), u(34), u(24), op(26))
    val c27: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.catalog.publication", u(27), Revision(4),
        PayloadDigest("0ed097adff523ed9407764ec7c84ffb47ad5d0edf67cda0c27ef1ac4f883d87b"), u(34), u(24), null)
    val ra21: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.food.configuration-version", u(21), Revision(6),
        PayloadDigest("b66ebf2a626f33ac1747cfdd2d7f4022125eb3e46ebdc66a7cd213a260673006"), u(35), null, op(21))
    val sa23: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.food.configuration-version", u(23), Revision(2),
        PayloadDigest("fea3d2dd6a09f33d8790b0bf795f5c66a94e4ffb3d2808b03366694c79a2bae4"), u(35), null, op(23))
    val sa22: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.food.configuration-version", u(22), Revision(8),
        PayloadDigest("eacf3d36a7a8a8627f0cc45f366e1fb12b213cb11462c2dd36d070684c836eb3"), u(35), null, op(22))
    val rb29: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.food.configuration-version", u(29), Revision(7),
        PayloadDigest("61a11c6c8c7a9369fe2c0c9bd95fbc3aa62e92a5bf52e9a4e1c719dea9a01a48"), u(35), null, op(29))
    val sb33: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.food.configuration-version", u(33), Revision(4),
        PayloadDigest("52582334a16e5a694392eee5be14e8293de01dfb43670bd3efbe34fb4c688bf8"), u(35), null, op(33))
    val sb32: LocalFoodSourceReferenceV1 = LocalFoodSourceReferenceV1("local.food.configuration-version", u(32), Revision(9),
        PayloadDigest("f5ba3f3d7c60df3ee9652fa335de9abe0cb39cc788cd2d2795abbb285c833f5e"), u(35), null, op(32))

    val originalSources: List<LocalFoodSourceReferenceV1> = listOf(c28, f5, c27)
    val sourcesA0: List<LocalFoodSourceReferenceV1> = listOf(c28, f5, c27, ra21, sa23, sa22)
    val sourcesA1: List<LocalFoodSourceReferenceV1> = listOf(c28, f5, c27, sa22, ra21, sa23)
    val sourcesB0: List<LocalFoodSourceReferenceV1> = listOf(c28, f5, c27, rb29, sb33, sb32)
    val routesA: List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(5), FoodStationDeliveryMode.PAPER),
        LocalFoodStationRouteV1(u(17), u(19), Revision(9), FoodStationDeliveryMode.DISPLAY))
    val routesB: List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(18), u(20), Revision(10), FoodStationDeliveryMode.DISPLAY),
        LocalFoodStationRouteV1(u(17), u(19), Revision(12), FoodStationDeliveryMode.PAPER))

    fun ingredients(): List<LocalFoodIngredientV1> = listOf(
        LocalFoodIngredientV1(u(15), u(16), Revision(13), q(12, 1200, 3), q(12, 450, 3)),
        LocalFoodIngredientV1(u(14), u(16), Revision(13), q(12, 800, 3), q(12, 300, 3)))

    fun quotation(portionId: DomainId, quotationId: DomainId): LocalFoodRecipeQuotationV1 =
        LocalFoodRecipeQuotationV1(quotationId, portionId, LocalSalesActivity.RESTAURANT, LocalSalesChannel.DINE_IN,
            u(6), Revision(11), u(5), u(7), Revision(7), u(8), listOf(u(11), u(10)), description,
            q(13, 400, 2), q(13, 150, 2), u(9), ingredients(), listOf(u(18), u(17)),
            signedPublication, originalSources)

    /** All original fields are declared directly; no quotation projection or checker constructs this value. */
    fun candidate(portionId: DomainId = u(4), snapshotId: DomainId = snapshotA0Id,
        routeVersionId: DomainId = u(21), warehouseId: DomainId = u(30),
        routes: List<LocalFoodStationRouteV1> = routesA,
        sources: List<LocalFoodSourceReferenceV1> = sourcesA0): LocalFoodRecipeSnapshotV1 =
        LocalFoodRecipeSnapshotV1(snapshotId, portionId, routeVersionId, LocalSalesActivity.RESTAURANT,
            LocalSalesChannel.DINE_IN, u(6), Revision(11), u(5), u(7), Revision(7), u(8),
            listOf(u(11), u(10)), description, q(13, 400, 2), q(13, 150, 2), u(9), ingredients(),
            warehouseId, routes, signedPublication, sources)

    fun selection(portionId: DomainId): LocalFoodPortionSelectionV1 = LocalFoodPortionSelectionV1(
        portionId, u(7), u(6), Revision(11), u(8), listOf(u(10), u(11)), q(13, 150, 2),
        "No added salt", u(4), "Seat 7")

    fun preparation(portionId: DomainId, revision: Long): FoodPreparationState =
        FoodPreparationState(portionId, Revision(revision), FoodPreparationStatus.NOT_SENT)

    fun quotedPortion(portionId: DomainId, quotationId: DomainId): FoodEnvelopeV1 = FoodEnvelopeV1(
        scope, LocalFoodProfileV1.PORTION, portionId, Revision(1), op(102),
        FoodBodyV1.QuotedPortion(u(100), selection(portionId), u(101), quotationId, preparation(portionId, 0)))

    fun routedPortion(portionId: DomainId, recipe: LocalFoodRecipeSnapshotV1): FoodEnvelopeV1 = FoodEnvelopeV1(
        scope, LocalFoodProfileV1.PORTION, portionId, Revision(2), op(103),
        FoodBodyV1.Portion(u(100), selection(portionId), u(101), recipe, preparation(portionId, 1)))

    fun sidecar(quotation: LocalFoodRecipeQuotationV1): FoodEnvelopeV1 = FoodEnvelopeV1(
        scope, FoodQuotationStorageV1.RECIPE_QUOTATION, quotation.quotationId, Revision(1), op(102),
        FoodBodyV1.RecipeQuotation(u(100), u(101), quotation))

    fun priceSources(lineId: DomainId = u(4)): List<LocalSalesPriceSourceV2> = listOf(
        priceSource(u(4), lineId), priceSource(u(104), lineId))

    private fun priceSource(portionId: DomainId, lineId: DomainId): LocalSalesPriceSourceV2 =
        LocalSalesPriceSourceV2(scope, lineId, portionId, u(9), u(108), u(109), Revision(5),
            PayloadDigest("b3db074c3128d10d016d2277d5bf14a0a682b0e1e71cd4b316a5ada5e29b80da"),
            UnitPrice.parse(Currency.EGP, "110.00"), UnitPrice.parse(Currency.EGP, "110.00"),
            null, null, null, null, null, null)

    fun priceBatch(snapshotRevision: Revision = Revision(0), lineId: DomainId = u(4),
        firstQuantity: Quantity = q(13, 150, 2), firstPriceRevision: Revision = Revision(5),
        firstUnitPrice: UnitPrice = UnitPrice.parse(Currency.EGP, "110.00"),
        amounts: FrozenMembershipAmounts = FrozenMembershipAmounts.ORIGINAL): FoodEnvelopeV1 {
        val tax = TaxSnapshot(u(110), Revision(1), "FOOD", PercentageRate.parse("0"))
        // This public factory constructs the frozen resolved inputs; all amount assertions below are fixed oracles.
        val quote = OperationalPricingEngine.calculate(PricingRequest(u(101), snapshotRevision, Currency.EGP, listOf(
            ResolvedPriceLine(lineId, u(4), firstPriceRevision, firstQuantity, firstUnitPrice, TaxInclusion.EXCLUSIVE, tax),
            ResolvedPriceLine(lineId, u(104), Revision(5), q(13, 150, 2),
                UnitPrice.parse(Currency.EGP, "110.00"), TaxInclusion.EXCLUSIVE, tax))))
        assertFrozenAmounts(quote, amounts, "frozen pricing fixture construction")
        val originalCanonicalQuote = StoredPricingSnapshotCodec.encode(quote)
        return FoodEnvelopeV1(scope, LocalFoodProfileV1.PRICE_BATCH, u(101), Revision(1), op(102),
            FoodBodyV1.PriceBatch(u(100), originalCanonicalQuote, priceSources(lineId)))
    }

    fun input(bodies: FrozenMembershipBodies = FrozenMembershipBodies.MIXED): FrozenMembershipInput {
        assertEquals(1533, signedPublication.encodeToByteArray().size, "incorporated literal S length")
        val q1 = quotation(u(4), quotation1Id)
        val q2 = quotation(u(104), quotation2Id)
        val qp1 = quotedPortion(u(4), quotation1Id)
        val qp2 = quotedPortion(u(104), quotation2Id)
        val rp1 = routedPortion(u(4), candidate())
        val rp2 = routedPortion(u(104), candidate(u(104), snapshotA2Id))
        val portions = when (bodies) {
            FrozenMembershipBodies.QUOTED -> listOf(qp1, qp2)
            FrozenMembershipBodies.MIXED -> listOf(qp1, rp2)
            FrozenMembershipBodies.ROUTED -> listOf(rp1, rp2)
        }
        val order = FoodEnvelopeV1(scope, LocalFoodProfileV1.ORDER, u(100), Revision(2), op(103),
            FoodBodyV1.Order(u(111), u(112), LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Membership fixture", "Seat 7"),
                listOf(u(4), u(104)), listOf(u(101)), Money(Currency.EGP, 33000)))
        return FrozenMembershipInput(order, portions, listOf(sidecar(q1), sidecar(q2)), priceBatch())
    }

    fun assertFrozenAmounts(quote: PricingSnapshot, amounts: FrozenMembershipAmounts, label: String) {
        assertEquals(Money(Currency.EGP, amounts.totalMinor), quote.beforeDiscountPayable, "$label before-discount total")
        assertEquals(Money(Currency.EGP, amounts.totalMinor), quote.payable, "$label payable total")
        assertEquals(ExactRational.parseCanonical(amounts.totalExact), quote.exactBeforeDiscountPayable, "$label exact before-discount total")
        assertEquals(ExactRational.parseCanonical(amounts.totalExact), quote.exactPayable, "$label exact payable total")
        assertEquals(2, quote.lines.size, "$label line count")
        val first = quote.lines[0]
        assertEquals(ExactRational.parseCanonical(amounts.firstExact), first.exactOriginalItemNet, "$label first original net")
        assertEquals(ExactRational.parseCanonical(amounts.firstExact), first.exactItemNet, "$label first current net")
        assertEquals(Money(Currency.EGP, amounts.firstMinor), first.payable, "$label first posted payable")
        val second = quote.lines[1]
        assertEquals(ExactRational.parseCanonical("165/1"), second.exactOriginalItemNet, "$label second original net")
        assertEquals(ExactRational.parseCanonical("165/1"), second.exactItemNet, "$label second current net")
        assertEquals(Money(Currency.EGP, 16500), second.payable, "$label second posted payable")
        assertTrue(quote.charges.isEmpty(), "$label no charges")
        assertTrue(quote.discounts.isEmpty(), "$label no discounts")
        quote.components.filter { it.kind == PriceComponentKind.ITEM_TAX }.forEach {
            assertEquals(ExactRational.parseCanonical("0/1"), it.exactBeforeDiscounts, "$label original tax")
            assertEquals(ExactRational.parseCanonical("0/1"), it.exact, "$label current tax")
            assertEquals(Money(Currency.EGP, 0), it.postedBeforeDiscounts, "$label original posted tax")
            assertEquals(Money(Currency.EGP, 0), it.posted, "$label posted tax")
        }
    }
}

internal enum class FrozenMembershipBodies { QUOTED, MIXED, ROUTED }

internal data class FrozenMembershipAmounts(val firstExact: String, val firstMinor: Long,
    val totalExact: String, val totalMinor: Long) {
    companion object {
        val ORIGINAL: FrozenMembershipAmounts = FrozenMembershipAmounts("165/1", 16500, "330/1", 33000)
        val FIRST_QUANTITY_200: FrozenMembershipAmounts = FrozenMembershipAmounts("220/1", 22000, "385/1", 38500)
        val FIRST_PRICE_111: FrozenMembershipAmounts = FrozenMembershipAmounts("333/2", 16650, "663/2", 33150)
    }
}

internal data class FrozenMembershipInput(val order: FoodEnvelopeV1, val portions: List<FoodEnvelopeV1>,
    val sidecars: List<FoodEnvelopeV1>, val priceBatch: FoodEnvelopeV1) {
    fun orderBody(change: (FoodBodyV1.Order) -> FoodBodyV1.Order): FrozenMembershipInput =
        copy(order = order.copy(body = change(order.body as FoodBodyV1.Order)))
    fun portion(index: Int, change: (FoodEnvelopeV1) -> FoodEnvelopeV1): FrozenMembershipInput =
        copy(portions = portions.mapIndexed { position, value -> if (position == index) change(value) else value })
    fun sidecar(index: Int, change: (FoodEnvelopeV1) -> FoodEnvelopeV1): FrozenMembershipInput =
        copy(sidecars = sidecars.mapIndexed { position, value -> if (position == index) change(value) else value })
    fun priceBody(change: (FoodBodyV1.PriceBatch) -> FoodBodyV1.PriceBatch): FrozenMembershipInput =
        copy(priceBatch = priceBatch.copy(body = change(priceBatch.body as FoodBodyV1.PriceBatch)))
}

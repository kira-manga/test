package com.ukkera.pos.sales.store.independent.foodatomicquotationbindingv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*

/** Literal public constructor inputs from food-atomic-quotation-binding-value-inputs-v1.
 * These synthetic signed bytes/references establish no trusted source or native custody.
 * PRICE_BATCH, accepting producer history and complete resource closure remain unbound. */
internal object FrozenFoodAtomicQuotationBindingInputs {
    fun u(number: Int): DomainId = DomainId("00000000-0000-0000-0000-" + number.toString().padStart(12, '0'))
    fun op(number: Int): OperationId = OperationId(u(number).value)
    fun quantity(unit: Int, scaled: Long, scale: Int = 0): Quantity = Quantity(u(unit), scaled, scale)

    val scope: BranchScope = BranchScope(u(1), u(2), u(3))
    val quotation1Id: DomainId = DomainId("c761b406-9ac8-0562-e80d-eed027b30964")
    val quotation2Id: DomainId = DomainId("d07388f3-3f4c-882b-0040-74c4b766736b")
    val snapshot1Id: DomainId = DomainId("32cb4443-e35c-722e-976c-298759ff8fd2")
    val snapshot2Id: DomainId = DomainId("97443225-4072-3fff-b133-90e05531ee00")
    const val SIGNED_PUBLICATION: String = """{"definition":{"company":{"organizationId":"00000000-0000-0000-0000-000000000001","companyId":"00000000-0000-0000-0000-000000000002"},"publicationId":"00000000-0000-0000-0000-000000000111","recipeId":"00000000-0000-0000-0000-000000000110","revision":"5","outputSkuId":"00000000-0000-0000-0000-000000000100","outputSkuRevision":"3","yieldQuantity":{"unitId":"00000000-0000-0000-0000-000000000020","scaled":"1","scale":0},"portionQuantity":{"unitId":"00000000-0000-0000-0000-000000000020","scaled":"1","scale":0},"choices":[{"id":"00000000-0000-0000-0000-000000000112","names":{"english":"Burger","arabic":"برجر"},"modifierIds":[],"ingredients":[{"componentId":"00000000-0000-0000-0000-000000000115","skuId":"00000000-0000-0000-0000-000000000102","skuRevision":"2","quantityPerYield":{"unitId":"00000000-0000-0000-0000-000000000021","scaled":"1","scale":0}},{"componentId":"00000000-0000-0000-0000-000000000116","skuId":"00000000-0000-0000-0000-000000000103","skuRevision":"4","quantityPerYield":{"unitId":"00000000-0000-0000-0000-000000000022","scaled":"150","scale":0}}],"routeKeys":["00000000-0000-0000-0000-000000000120"],"pricingSkuId":"00000000-0000-0000-0000-000000000101"}],"active":true},"signingKeyId":"00000000-0000-0000-0000-000000000160","signatureHex":"41e47eabb34d6848b3492924ea6e00b311c0be4572aa69908d74462fea748f5bc62a4609cc7f0a7a2f357542ec24b10fde23c84cd71c54081424d5e46a519301"}"""

    fun originalSources(): List<LocalFoodSourceReferenceV1> = listOf(
        LocalFoodSourceReferenceV1("local.food.publication", u(111), Revision(1),
            PayloadDigest("3dbba99660841f468be999da1fdcba0308d57b95e15eb35ddd2cb0ce28659a45"), u(10), u(160), op(170)),
        LocalFoodSourceReferenceV1("local.catalog.publication", u(150), Revision(1),
            PayloadDigest("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), u(10), u(160), null),
    )

    fun ingredients(): List<LocalFoodIngredientV1> = listOf(
        LocalFoodIngredientV1(u(115), u(102), Revision(2), quantity(21, 1), quantity(21, 1)),
        LocalFoodIngredientV1(u(116), u(103), Revision(4), quantity(22, 150), quantity(22, 150)),
    )

    fun routes(): List<LocalFoodStationRouteV1> = listOf(
        LocalFoodStationRouteV1(u(120), u(130), Revision(3), FoodStationDeliveryMode.DISPLAY),
    )

    fun routeSources(): List<LocalFoodSourceReferenceV1> = listOf(
        LocalFoodSourceReferenceV1("local.food.configuration-version", u(132), Revision(1),
            PayloadDigest("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), u(10), null, op(132)),
        LocalFoodSourceReferenceV1("local.food.configuration-version", u(135), Revision(1),
            PayloadDigest("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"), u(10), null, op(135)),
    )

    fun quotation(
        portionId: DomainId = u(301),
        quotationId: DomainId = if (portionId == u(302)) quotation2Id else quotation1Id,
        activity: LocalSalesActivity = LocalSalesActivity.RESTAURANT,
        channel: LocalSalesChannel = LocalSalesChannel.DINE_IN,
        recipeId: DomainId = u(110),
        recipeRevision: Revision = Revision(5),
        publicationId: DomainId = u(111),
        outputSkuId: DomainId = u(100),
        outputSkuRevision: Revision = Revision(3),
        choiceId: DomainId = u(112),
        modifierIds: List<DomainId> = emptyList(),
        description: String = "Burger",
        yieldQuantity: Quantity = quantity(20, 1),
        portionQuantity: Quantity = quantity(20, 1),
        pricingSkuId: DomainId = u(101),
        ingredientValues: List<LocalFoodIngredientV1> = ingredients(),
        routeKeys: List<DomainId> = listOf(u(120)),
        signedPublication: String = SIGNED_PUBLICATION,
        sourceValues: List<LocalFoodSourceReferenceV1> = originalSources(),
    ): LocalFoodRecipeQuotationV1 = LocalFoodRecipeQuotationV1(
        quotationId, portionId, activity, channel, recipeId, recipeRevision, publicationId, outputSkuId,
        outputSkuRevision, choiceId, modifierIds, description, yieldQuantity, portionQuantity, pricingSkuId,
        ingredientValues, routeKeys, signedPublication, sourceValues,
    )

    fun project(quotation: LocalFoodRecipeQuotationV1 = quotation(),
        routeVersionId: DomainId = u(132), warehouseId: DomainId = u(140),
        routeValues: List<LocalFoodStationRouteV1> = routes(),
        sourceValues: List<LocalFoodSourceReferenceV1> = routeSources()): LocalFoodRecipeSnapshotV1 =
        FoodRecipeRouting.fromQuotation(quotation, routeVersionId, warehouseId, routeValues, sourceValues)

    fun selection(portionId: DomainId = u(301)): LocalFoodPortionSelectionV1 =
        LocalFoodPortionSelectionV1(portionId, u(100), u(110), Revision(5), u(112), emptyList(),
            quantity(20, 1), "No salt", u(301), "Seat 2")

    fun preparation(portionId: DomainId = u(301), revision: Long = 0): FoodPreparationState =
        FoodPreparationState(portionId, Revision(revision), FoodPreparationStatus.NOT_SENT,
            FoodFulfillmentStatus.NOT_HANDED_OVER, emptyList(), null, null, null, null, null, null, null)

    fun quotedEnvelope(portionId: DomainId = u(301)): FoodEnvelopeV1 {
        val quotation = quotation(portionId)
        return FoodEnvelopeV1(scope, "sales.food.portion", portionId, Revision(1), op(401),
            FoodBodyV1.QuotedPortion(u(200), selection(portionId), u(201), quotation.quotationId,
                preparation(portionId)))
    }

    fun sidecar(portionId: DomainId = u(301)): FoodEnvelopeV1 {
        val quotation = quotation(portionId)
        return FoodEnvelopeV1(scope, "sales.food.recipe-quotation", quotation.quotationId, Revision(1), op(401),
            FoodBodyV1.RecipeQuotation(u(200), u(201), quotation))
    }

    /** Lawful unissued routed shape expressly declared in the companion; no actual conversion is asserted. */
    fun routedEnvelope(portionId: DomainId = u(301)): FoodEnvelopeV1 =
        FoodEnvelopeV1(scope, "sales.food.portion", portionId, Revision(2), op(501),
            FoodBodyV1.Portion(u(200), selection(portionId), u(201), project(quotation(portionId)),
                preparation(portionId, 1)))

    fun quotationBytes(value: LocalFoodRecipeQuotationV1): StoredBytes =
        localSalesEncode(value, LocalFoodRecipeQuotationV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)

    fun snapshotBytes(value: LocalFoodRecipeSnapshotV1): StoredBytes =
        localSalesEncode(value, LocalFoodRecipeSnapshotV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)

    fun envelopeBytes(value: FoodEnvelopeV1): StoredBytes =
        localSalesEncode(value, FoodEnvelopeV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)
}

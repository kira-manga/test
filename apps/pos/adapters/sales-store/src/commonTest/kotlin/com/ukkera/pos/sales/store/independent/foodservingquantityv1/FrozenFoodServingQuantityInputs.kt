package com.ukkera.pos.sales.store.independent.foodservingquantityv1

import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodRequestV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/**
 * Inputs from food-serving-quantity-v1/fixtures.json. The unit, recipe and second
 * serving identifiers are declared synthetic representation bindings. They are
 * not native creation, retained-operation or catalog-activation evidence.
 */
internal object FrozenFoodServingQuantityInputs {
    const val ITEM_NOTE = "no salt / بدون ملح"
    const val SEAT_NOTE = "Seat 02 / المقعد 02"
    const val ORIGINAL_DESCRIPTION = "Burger / برجر"

    val scope = BranchScope(
        DomainId("a0000000-0000-4000-8000-000000000001"),
        DomainId("a0000000-0000-4000-8000-000000000002"),
        DomainId("a0000000-0000-4000-8000-000000000004"),
    )
    val ownerA = DomainId("b0000000-0000-4000-8000-000000000001")
    val otherEmployee = DomainId("b0000000-0000-4000-8000-000000000002")
    val orderId = DomainId("f0000000-0000-4000-8000-000000000001")
    val lineId = DomainId("f3000000-0000-4000-8000-000000000001")
    val secondPortionId = DomainId("f3000000-0000-4000-8000-000000000002")
    val saveOperationId = OperationId("f4000000-0000-4000-8000-000000000001")
    val variantId = DomainId("f1000000-0000-4000-8000-000000000002")
    val cheeseOptionId = DomainId("f1000000-0000-4000-8000-000000000003")
    val lookalikeVariantId = DomainId("f1000000-0000-4000-8000-000000000005")
    val lookalikeCheeseOptionId = DomainId("f1000000-0000-4000-8000-000000000006")
    val stationId = DomainId("f2000000-0000-4000-8000-000000000005")
    val burgerUnitId = DomainId("f5000000-0000-4000-8000-000000000001")
    val recipeId = DomainId("f5000000-0000-4000-8000-000000000003")
    val taskId = DomainId("f5000000-0000-4000-8000-000000000007")

    val one = Quantity(burgerUnitId, 1, 0)
    val two = Quantity(burgerUnitId, 2, 0)
    val existingOrderRevision = Revision(7)
    val originalRecipeRevision = Revision(1)
    val currentRecipeRevision = Revision(2)
    // Fixed codec input only. A production catalog cursor needs its own checkpoint.
    val catalogCheckpointRevision = Revision(5)
    val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "T-02")

    fun twoServings(recipeRevision: Revision = originalRecipeRevision): List<LocalFoodPortionSelectionV1> =
        listOf(lineId, secondPortionId).map { portionId ->
            LocalFoodPortionSelectionV1(
                portionId = portionId,
                itemId = variantId,
                recipeId = recipeId,
                expectedRecipeRevision = recipeRevision,
                choiceId = variantId,
                modifierIds = listOf(cheeseOptionId),
                quantity = one,
                itemNote = ITEM_NOTE,
                lineId = lineId,
                seatNote = SEAT_NOTE,
            )
        }

    fun add(portions: List<LocalFoodPortionSelectionV1> = twoServings()): LocalFoodRequestV1.Add =
        LocalFoodRequestV1.Add(orderId, existingOrderRevision, catalogCheckpointRevision, portions)

    // Literal legacy v1 shape with both additive fields absent. Quantity 1 is the
    // explicit starting fixture, not an editor default or a quantity-two save.
    val legacySelectionJson =
        """{"portionId":"f3000000-0000-4000-8000-000000000001","itemId":"f1000000-0000-4000-8000-000000000002","recipeId":"f5000000-0000-4000-8000-000000000003","expectedRecipeRevision":"1","choiceId":"f1000000-0000-4000-8000-000000000002","modifierIds":["f1000000-0000-4000-8000-000000000003"],"quantity":{"unitId":"f5000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":"no salt / بدون ملح"}"""
    val legacyAddJson =
        """{"type":"ADD","orderId":"f0000000-0000-4000-8000-000000000001","expectedOrderRevision":"7","expectedCatalogRevision":"5","portions":[$legacySelectionJson]}"""
}

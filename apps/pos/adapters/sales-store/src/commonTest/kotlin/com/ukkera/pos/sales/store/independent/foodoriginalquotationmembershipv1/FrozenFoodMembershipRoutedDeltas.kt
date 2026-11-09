package com.ukkera.pos.sales.store.independent.foodoriginalquotationmembershipv1

import com.ukkera.pos.sales.store.LocalFoodIngredientV1
import com.ukkera.pos.sales.store.LocalFoodRecipeSnapshotV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Revision
import com.ukkera.pos.sales.store.independent.foodoriginalquotationmembershipv1.FrozenFoodOriginalQuotationMembershipInputs as F

/** Every independent FORC-002–005 delta incorporated unchanged by FQM-006. */
internal data class FrozenFoodMembershipRoutedDelta(val id: String, val constructorOnly: Boolean = false,
    val change: (LocalFoodRecipeSnapshotV1) -> LocalFoodRecipeSnapshotV1)

internal fun frozenFoodMembershipRoutedDeltas(): List<FrozenFoodMembershipRoutedDelta> = listOf(
    FrozenFoodMembershipRoutedDelta("FORC-002 portion-and-derived-id") {
        it.copy(portionId = F.u(90), snapshotId = F.snapshotAXId)
    },
    FrozenFoodMembershipRoutedDelta("FORC-002 activity") { it.copy(activity = LocalSalesActivity.CAFE) },
    FrozenFoodMembershipRoutedDelta("FORC-002 channel") { it.copy(channel = LocalSalesChannel.DELIVERY) },
    FrozenFoodMembershipRoutedDelta("FORC-002 recipe-id") { it.copy(recipeId = F.u(91)) },
    FrozenFoodMembershipRoutedDelta("FORC-002 recipe-revision") { it.copy(recipeRevision = Revision(12)) },
    FrozenFoodMembershipRoutedDelta("FORC-002 publication-and-derived-id") {
        it.copy(publicationId = F.u(92), snapshotId = F.changedPublicationSnapshotId,
            sources = listOf(F.c28, F.f5.copy(id = F.u(92)), F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-002 output-sku") { it.copy(outputSkuId = F.u(93)) },
    FrozenFoodMembershipRoutedDelta("FORC-002 output-revision") { it.copy(outputSkuRevision = Revision(8)) },
    FrozenFoodMembershipRoutedDelta("FORC-002 choice") { it.copy(choiceId = F.u(94)) },
    FrozenFoodMembershipRoutedDelta("FORC-002 pricing-sku") { it.copy(pricingSkuId = F.u(95)) },
    FrozenFoodMembershipRoutedDelta("FORC-002 description-byte") { it.copy(description = F.description + " ") },
    FrozenFoodMembershipRoutedDelta("FORC-002 signed-publication-byte") {
        it.copy(signedPublication = F.signedPublication.replace("0".repeat(128), "0".repeat(127) + "1"))
    },
    FrozenFoodMembershipRoutedDelta("FORC-002 yield-and-required-quantities") {
        it.copy(yieldQuantity = F.q(13, 800, 2), ingredients = listOf(
            it.ingredients[0].copy(requiredBaseQuantity = F.q(12, 225, 3)),
            it.ingredients[1].copy(requiredBaseQuantity = F.q(12, 150, 3))))
    },
    FrozenFoodMembershipRoutedDelta("FORC-002 portion-and-required-quantities") {
        it.copy(portionQuantity = F.q(13, 200, 2), ingredients = listOf(
            it.ingredients[0].copy(requiredBaseQuantity = F.q(12, 600, 3)),
            it.ingredients[1].copy(requiredBaseQuantity = F.q(12, 400, 3))))
    },
    FrozenFoodMembershipRoutedDelta("FORC-002 yield-and-portion-units") {
        it.copy(yieldQuantity = F.q(90, 400, 2), portionQuantity = F.q(90, 150, 2))
    },
    FrozenFoodMembershipRoutedDelta("FORC-002 yield-and-portion-scales") {
        it.copy(yieldQuantity = F.q(13, 4000, 3), portionQuantity = F.q(13, 1500, 3))
    },

    FrozenFoodMembershipRoutedDelta("FORC-003 modifier-order") { it.copy(modifierIds = listOf(F.u(10), F.u(11))) },
    FrozenFoodMembershipRoutedDelta("FORC-003 modifier-missing") { it.copy(modifierIds = listOf(F.u(11))) },
    FrozenFoodMembershipRoutedDelta("FORC-003 modifier-substitution") { it.copy(modifierIds = listOf(F.u(11), F.u(90))) },
    FrozenFoodMembershipRoutedDelta("FORC-003 modifier-duplicate", constructorOnly = true) {
        it.copy(modifierIds = listOf(F.u(11), F.u(11)))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 ingredient-order") { it.copy(ingredients = listOf(it.ingredients[1], it.ingredients[0])) },
    FrozenFoodMembershipRoutedDelta("FORC-003 ingredient-missing") { it.copy(ingredients = listOf(it.ingredients[0])) },
    FrozenFoodMembershipRoutedDelta("FORC-003 component-id") {
        it.copy(ingredients = listOf(it.ingredients[0].copy(componentId = F.u(90)), it.ingredients[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 ingredient-sku") {
        it.copy(ingredients = listOf(it.ingredients[0].copy(skuId = F.u(91)), it.ingredients[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 ingredient-revision") {
        it.copy(ingredients = listOf(it.ingredients[0].copy(skuRevision = Revision(14)), it.ingredients[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 ingredient-amounts") {
        it.copy(ingredients = listOf(it.ingredients[0].copy(quantityPerYield = F.q(12, 1208, 3),
            requiredBaseQuantity = F.q(12, 453, 3)), it.ingredients[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 ingredient-units") {
        it.copy(ingredients = listOf(it.ingredients[0].copy(quantityPerYield = F.q(90, 1200, 3),
            requiredBaseQuantity = F.q(90, 450, 3)), it.ingredients[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 ingredient-scales") {
        it.copy(ingredients = listOf(it.ingredients[0].copy(quantityPerYield = F.q(12, 120, 2),
            requiredBaseQuantity = F.q(12, 45, 2)), it.ingredients[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 inconsistent-required-quantity", constructorOnly = true) {
        it.copy(ingredients = listOf(it.ingredients[0].copy(requiredBaseQuantity = F.q(12, 451, 3)), it.ingredients[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-003 merged-components") {
        it.copy(ingredients = listOf(LocalFoodIngredientV1(F.u(15), F.u(16), Revision(13),
            F.q(12, 2000, 3), F.q(12, 750, 3))))
    },

    FrozenFoodMembershipRoutedDelta("FORC-004 original-prefix-order") {
        it.copy(sources = listOf(F.f5, F.c28, F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-prefix-missing") {
        it.copy(sources = listOf(F.c28, F.f5, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-prefix-duplicate", constructorOnly = true) {
        it.copy(sources = listOf(F.c28, F.f5, F.c28, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-publication-extra", constructorOnly = true) {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.c28, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 prefix-interleaving") {
        it.copy(sources = listOf(F.c28, F.ra21, F.f5, F.c27, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-kind") {
        it.copy(sources = listOf(F.c28.copy(kind = "local.food.configuration-version"), F.f5, F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-id") {
        it.copy(sources = listOf(F.c28.copy(id = F.u(90)), F.f5, F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-revision") {
        it.copy(sources = listOf(F.c28.copy(revision = Revision(6)), F.f5, F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-digest") {
        it.copy(sources = listOf(F.c28.copy(digest = PayloadDigest("993ae89ef7480edd74db4993358b632c9a72e089cb7edfbaddb320ab08c95f81")),
            F.f5, F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-authority") {
        it.copy(sources = listOf(F.c28.copy(authorizedBy = F.u(90)), F.f5, F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-signing-key") {
        it.copy(sources = listOf(F.c28.copy(signingKeyId = F.u(90)), F.f5, F.c27, F.ra21, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-004 original-operation") {
        it.copy(sources = listOf(F.c28, F.f5.copy(operationId = F.op(90)), F.c27, F.ra21, F.sa23, F.sa22))
    },

    FrozenFoodMembershipRoutedDelta("FORC-005 derived-id", constructorOnly = true) { it.copy(snapshotId = F.u(90)) },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-version-without-reference") {
        it.copy(routeVersionId = F.u(29), snapshotId = F.snapshotB0Id)
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-order") { it.copy(routes = listOf(F.routesA[1], F.routesA[0])) },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-missing") { it.copy(routes = listOf(F.routesA[0])) },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-key-substitution") {
        it.copy(routes = listOf(F.routesA[0], F.routesA[1].copy(routeKey = F.u(90))))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-key-duplicate", constructorOnly = true) {
        it.copy(routes = listOf(F.routesA[0], F.routesA[1].copy(routeKey = F.u(18))))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 station-duplicate", constructorOnly = true) {
        it.copy(routes = listOf(F.routesA[0], F.routesA[1].copy(stationId = F.u(20))))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 station-revision-nonpositive") {
        it.copy(routes = listOf(F.routesA[0].copy(stationRevision = Revision(0)), F.routesA[1]))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-version-missing") {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 station-version-missing") {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.ra21, F.sa23))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 station-version-duplicate", constructorOnly = true) {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.ra21, F.sa23, F.sa23))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 publication-as-station-version", constructorOnly = true) {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.ra21, F.sa23, F.c28))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-version-revision-nonpositive") {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.ra21.copy(revision = Revision(0)), F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-version-signing-key") {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.ra21.copy(signingKeyId = F.u(24)), F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-version-operation-missing") {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.ra21.copy(operationId = null), F.sa23, F.sa22))
    },
    FrozenFoodMembershipRoutedDelta("FORC-005 route-version-operation-mismatched") {
        it.copy(sources = listOf(F.c28, F.f5, F.c27, F.ra21.copy(operationId = F.op(90)), F.sa23, F.sa22))
    })

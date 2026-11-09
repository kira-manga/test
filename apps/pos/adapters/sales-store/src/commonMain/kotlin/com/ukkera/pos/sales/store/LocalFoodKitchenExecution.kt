package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.types.DomainId

/** Called with a complete authoritative cut, never a caller's unsent/capability flag. */
internal suspend fun foodKitchenExecutionRestriction(view: CommandReadView, recipes: TransactionFoodRecipeSourceV1,
    order: FoodOrderCut, portions: List<FoodRow>): PreparedMutationAdmissionRestriction? {
    // Draft/Add does not establish dispatch, delivery fulfillment or collection eligibility.
    // Every caller here admits fresh physical/financial work, not original fact retention/application.
    if (order.value.service.channel == LocalSalesChannel.DELIVERY) return PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
    if (portions.any { it.envelope.body is FoodBodyV1.QuotedPortion })
        return PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
    val unsent = portions.map { it.envelope.body as FoodBodyV1.Portion }
        .filter { it.preparation.status == FoodPreparationStatus.NOT_SENT }
    if (unsent.isEmpty()) return null // No new kitchen work; do not disable issued-obligation reconciliation.
    return recipes.prepareKitchenExecution(view, order.value.activity, order.value.service, unsent.map { it.recipe })
}

/** Preparation admission must cover BeginCollection's ENTIRE unsent order, not a UI subset. */
internal class FoodKitchenExecutionAdmission(val restriction: PreparedMutationAdmissionRestriction?,
    val resources: Set<RecoveryResourceKey>, val binding: FoodQuotationBindingPreparation? = null)

/** A reserved/financial source is never repaired or rebound by fresh routing preparation. */
private suspend fun foodUnboundKitchenAdmission(view: CommandReadView, recipes: TransactionFoodStockSource,
    order: FoodOrderCut, portionIds: List<DomainId>): FoodKitchenExecutionAdmission? {
    val rows = portionIds.map { id -> foodRead(view, LocalFoodProfileV1.PORTION, id) ?: error("food.portion_missing") }
    val quoted = rows.filter { it.envelope.body is FoodBodyV1.QuotedPortion }
    if (quoted.isEmpty()) return null
    val resources = foodResources(order, rows).toMutableSet()
    val source = recipes as? TransactionFoodRecipeQuotationVerificationSourceV1
    if (source != null) {
        val batches = mutableSetOf<DomainId>()
        for (row in quoted) {
            val portion = row.envelope.body as? FoodBodyV1.QuotedPortion ?: error("food.quotation.portion_required")
            if (batches.add(portion.priceBatchId)) resources += foodQuotedPortion(view, order, row.envelope.id, source).resources
        }
    }
    return FoodKitchenExecutionAdmission(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, resources)
}

internal suspend fun foodStockKitchenExecutionAdmission(request: LocalFoodStockRequestV2, view: CommandReadView,
    recipes: TransactionFoodStockSource): FoodKitchenExecutionAdmission? = when (request) {
    is LocalFoodStockRequestV2.Preparation -> when (val action = request.request) {
        is LocalFoodRequestV1.Send -> {
            val order = foodOrder(view, action.orderId)
            val ids = action.portionIds.filter { it in order.value.portionIds }
            val binding = foodPrepareQuotationBinding(view, recipes, order, ids, wholeOrder = false)
            FoodKitchenExecutionAdmission(binding.restriction, binding.resources + binding.members.flatMap { member -> listOf(
                RecoveryResourceKey(RecoveryResourceKind.STOCK, foodReservationId(member.before.envelope.id)),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, foodIssueId(member.before.envelope.id))) +
                member.routed?.recipe?.routes.orEmpty().map { orderResource(foodTaskId(member.before.envelope.id, it.stationId)) }
            }, if (binding.restriction == null) binding.retainPreparation() else null)
        }
        else -> null
    }
    is LocalFoodStockRequestV2.Financial -> when (val action = request.request) {
        is LocalFoodFinancialRequestV1.BeginCollection -> {
            val order = foodOrder(view, action.orderId)
            val binding = foodPrepareQuotationBinding(view, recipes, order, order.value.portionIds, wholeOrder = true)
            val cash = LocalSalesStorage.cash(view, action.sessionId)
            FoodKitchenExecutionAdmission(binding.restriction,
                binding.resources + cashResources(cash) + orderResource(action.collectionId) + binding.before.map {
                    RecoveryResourceKey(RecoveryResourceKind.STOCK, foodReservationId(it.envelope.id))
                }, if (binding.restriction == null) binding.retainPreparation() else null)
        }
        is LocalFoodFinancialRequestV1.SendReserved -> {
            val order = foodOrder(view, action.orderId)
            val unbound = foodUnboundKitchenAdmission(view, recipes, order, order.value.portionIds)
            if (unbound != null) unbound else {
                val cut = foodFinancialCollectionCut(view, action.collectionId, recipes)
                val selected = action.portionIds.mapNotNull { id -> cut.portions.singleOrNull { it.envelope.id == id } }
                FoodKitchenExecutionAdmission(foodKitchenExecutionRestriction(view, recipes, cut.order, selected),
                    foodFinancialResources(cut) + selected.flatMap { row -> listOf(
                        RecoveryResourceKey(RecoveryResourceKind.STOCK, foodIssueId(row.envelope.id))) +
                        (row.envelope.body as FoodBodyV1.Portion).recipe.routes.map { orderResource(foodTaskId(row.envelope.id, it.stationId)) }
                    })
            }
        }
        else -> null
    }
    else -> null // Draft/Add and original financial facts do not authorize fresh physical work.
}

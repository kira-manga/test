package com.ukkera.pos.sales.store

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

/** One commercial line's independently prepared configured servings in a single new quote batch. */
public class LocalFoodSelectionGroup internal constructor(
    public val lineId: DomainId,
    portions: List<LocalFoodPortionSelectionV1>,
    public val totalQuantity: Quantity,
) {
    public val portions: List<LocalFoodPortionSelectionV1> = portions.toList()
}

/** Grouping validates selection identity; it does not provide catalog or stock authority. */
public object LocalFoodSelectionGroups {
    public fun group(portions: List<LocalFoodPortionSelectionV1>): List<LocalFoodSelectionGroup> {
        require(portions.size in 1..LocalFoodProfileV1.MAX_PORTIONS_PER_COMMAND &&
            portions.map { it.portionId }.distinct().size == portions.size)
        return portions.groupBy { it.commercialLineId }.map { (lineId, members) ->
            val first = members.first()
            require(members.any { it.portionId == lineId }) { "food.line.anchor_not_in_batch" }
            // Members already share commercialLineId. The anchor may omit its raw
            // lineId while another member names it explicitly; preserve those bytes.
            require(members.all { it.itemId == first.itemId &&
                it.recipeId == first.recipeId && it.expectedRecipeRevision == first.expectedRecipeRevision &&
                it.choiceId == first.choiceId && it.modifierIds.toSet() == first.modifierIds.toSet() &&
                it.quantity == first.quantity && it.itemNote == first.itemNote && it.seatNote == first.seatNote
            }) { "food.line.incompatible_portions" }
            val total = members.fold(first.quantity.copy(scaled = 0)) { sum, member ->
                require(member.quantity.scaled <= Long.MAX_VALUE - sum.scaled) { "food.line.quantity_capacity" }
                sum + member.quantity
            }
            LocalFoodSelectionGroup(lineId, members, total)
        }
    }
}

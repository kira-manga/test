package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalFoodMenuQueryV1
import com.ukkera.pos.catalog.store.LocalFoodMenuRecipeV1
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

internal data class ParsedFoodSelection(val request: LocalFoodRequestV1, val originalDraft: Boolean)

/** Shared first-stage selection checks. No operation identity, pricing engine or route is created. */
internal suspend fun parseFoodSelection(access: GatewayAccess, action: LocalAction, orderId: DomainId,
    values: InputValues, lineField: LocalField?, lines: List<LocalInputLine>,
    preserveInputOrder: Boolean = false): ParsedFoodSelection {
    if (action !in setOf(LocalAction.FOOD_CREATE, LocalAction.FOOD_ADD)) invalid()
    access.requireAction(action, orderId)
    val expectedOrder = if (action == LocalAction.FOOD_ADD) values.revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION) else null
    val activity = if (action == LocalAction.FOOD_CREATE) LocalSalesActivity.valueOf(values.choice(LocalField.ACTIVITY)) else null
    val service = if (action == LocalAction.FOOD_CREATE) {
        val channel = LocalSalesChannel.valueOf(values.choice(LocalField.CHANNEL))
        if (channel == LocalSalesChannel.DELIVERY) invalid(LocalField.CHANNEL)
        LocalFoodServiceV1(channel, values.text(LocalField.SERVICE_REFERENCE), values.optionalText(LocalField.SEAT_NOTE).orEmpty())
    } else null
    val catalog = values.revision(LocalField.EXPECTED_CATALOG_REVISION)
    if (lineField != LocalField.LINES || lines.size !in 1..8) invalid(LocalField.LINES)
    var currentPortionCount = 0
    var originalDraft = false
    if (expectedOrder != null) {
        val current = foodOwnerAt(access, orderId, expectedOrder)
        originalDraft = current.summary.portionCount == 0
        currentPortionCount = current.summary.portionCount
        if (current.summary.handoffId != null || current.summary.financialCollectionId != null || current.summary.workClosed ||
            currentPortionCount >= 32) invalid(LocalField.LINES)
    }
    val recipes = mutableMapOf<DomainId, LocalFoodMenuRecipeV1>()
    val portions = lines.flatMap { line ->
        val fields = InputValues(line.values)
        val recipeId = fields.id(LocalField.RECIPE_ID)
        val recipe = recipes[recipeId] ?: foodMenuRead(access, LocalFoodMenuQueryV1(recipeId = recipeId)).page.let { menu ->
            if (menu.catalogRevision != catalog) invalid(LocalField.EXPECTED_CATALOG_REVISION)
            (menu.recipes.singleOrNull() ?: foodMissing()).also { recipes[recipeId] = it }
        }
        if (!recipe.active || fields.revision(LocalField.EXPECTED_FOOD_RECIPE_REVISION) != recipe.recipeRevision ||
            fields.id(LocalField.SKU_ID) != recipe.outputSkuId) invalid(LocalField.RECIPE_ID)
        val choiceId = fields.id(LocalField.CHOICE_ID)
        val choice = recipe.choices.singleOrNull { it.choiceId == choiceId } ?: invalid(LocalField.CHOICE_ID)
        if (fields.id(LocalField.UNIT) != recipe.portionQuantity.unitId ||
            fields.integer(LocalField.QUANTITY_SCALE) != recipe.portionQuantity.scale.toLong() ||
            Quantity.parse(recipe.portionQuantity.unitId, fields.decimal(LocalField.QUANTITY),
                recipe.portionQuantity.scale) != recipe.portionQuantity ||
            fields.optionalText(LocalField.MODIFIER_IDS).orEmpty() != choice.modifierIds.joinToString(",") { it.value } ||
            fields.optionalText(LocalField.NAME_ENGLISH).orEmpty() != choice.names.english ||
            fields.optionalText(LocalField.NAME_ARABIC).orEmpty() != choice.names.arabic) invalid(LocalField.CHOICE_ID)
        val note = fields.optionalText(LocalField.ITEM_NOTE).orEmpty()
        val seatNote = fields.optionalText(LocalField.LINE_SEAT_NOTE).orEmpty()
        val count = fields.optionalInteger(LocalField.FOOD_SERVING_COUNT) ?: 1L
        if (count !in 1L..8L) invalid(LocalField.FOOD_SERVING_COUNT)
        val idsText = fields.optionalText(LocalField.FOOD_PORTION_IDS)
        val ids = if (idsText == null && count == 1L) listOf(line.lineId) else {
            val entries = idsText?.split(',') ?: invalid(LocalField.FOOD_SERVING_COUNT)
            if (entries.size.toLong() != count) invalid(LocalField.FOOD_SERVING_COUNT)
            entries.map { DomainId(it) }.also {
                if (it.first() != line.lineId || it.distinct().size != it.size) invalid(LocalField.FOOD_SERVING_COUNT)
            }
        }
        fields.finish()
        ids.map { portionId -> LocalFoodPortionSelectionV1(portionId, recipe.outputSkuId, recipeId, recipe.recipeRevision,
            choice.choiceId, choice.modifierIds.toList(), recipe.portionQuantity, note,
            if (count > 1L) line.lineId else null, seatNote) }
    }.let { if (preserveInputOrder) it else it.sortedBy { portion -> portion.portionId.value } }
    if (portions.size !in 1..8 || currentPortionCount + portions.size > 32 ||
        portions.map { it.portionId }.distinct().size != portions.size) invalid(LocalField.LINES)
    LocalFoodSelectionGroups.group(portions)
    val request = if (expectedOrder == null) LocalFoodRequestV1.Create(orderId, checkNotNull(activity), checkNotNull(service), catalog, portions)
        else LocalFoodRequestV1.Add(orderId, expectedOrder, catalog, portions)
    return ParsedFoodSelection(request, originalDraft)
}

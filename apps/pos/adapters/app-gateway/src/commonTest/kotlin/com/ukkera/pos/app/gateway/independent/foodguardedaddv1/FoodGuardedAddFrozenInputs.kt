package com.ukkera.pos.app.gateway.independent.foodguardedaddv1

import com.ukkera.pos.app.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*

/** Literal inputs from food-guarded-add-v1; no production outcome is inferred here. */
internal object FoodGuardedAddFrozenInputs {
    val scope: BranchScope = BranchScope(DomainId("81000000-0000-4000-8000-000000000001"),
        DomainId("82000000-0000-4000-8000-000000000001"), DomainId("83000000-0000-4000-8000-000000000001"))
    val actor: DomainId = DomainId("84000000-0000-4000-8000-000000000001")
    val otherActor: DomainId = DomainId("84000000-0000-4000-8000-000000000002")
    val device: DomainId = DomainId("85000000-0000-4000-8000-000000000001")
    val session: DomainId = DomainId("86000000-0000-4000-8000-000000000001")
    val restaurantOrder: DomainId = DomainId("87000000-0000-4000-8000-000000000001")
    val cafeOrder: DomainId = DomainId("87000000-0000-4000-8000-000000000002")
    val otherOrder: DomainId = DomainId("87000000-0000-4000-8000-000000000003")
    val operation: OperationId = OperationId("88000000-0000-4000-8000-000000000101")
    val legacyStockOperation: OperationId = OperationId("88000000-0000-4000-8000-000000000103")
    val legacyFoodOperation: OperationId = OperationId("88000000-0000-4000-8000-000000000104")
    val item: DomainId = DomainId("8a000000-0000-4000-8000-000000000001")
    val measuredItem: DomainId = DomainId("8a000000-0000-4000-8000-000000000002")
    val recipe: DomainId = DomainId("8b000000-0000-4000-8000-000000000001")
    val measuredRecipe: DomainId = DomainId("8b000000-0000-4000-8000-000000000002")
    val choice: DomainId = DomainId("8c000000-0000-4000-8000-000000000001")
    val modifier: DomainId = DomainId("8d000000-0000-4000-8000-000000000001")
    val existingPortion: DomainId = DomainId("8e000000-0000-4000-8000-000000000099")
    val pieceUnit: DomainId = DomainId("8f000000-0000-4000-8000-000000000001")
    val kgUnit: DomainId = DomainId("8f000000-0000-4000-8000-000000000003")
    val line: DomainId = DomainId("93000000-0000-4000-8000-000000000001")
    val table: DomainId = DomainId("94000000-0000-4000-8000-000000000001")
    val portionIds: List<DomainId> = listOf(
        "8e000000-0000-4000-8000-000000000001", "8e000000-0000-4000-8000-000000000002",
        "8e000000-0000-4000-8000-000000000003", "8e000000-0000-4000-8000-000000000004",
        "8e000000-0000-4000-8000-000000000005", "8e000000-0000-4000-8000-000000000006",
        "8e000000-0000-4000-8000-000000000007", "8e000000-0000-4000-8000-000000000008",
        "8e000000-0000-4000-8000-000000000009",
    ).map(::DomainId)

    const val GUARDED: String = """{"type":"ACTIVITY_PROTECTED_ADD","request":{"orderId":"87000000-0000-4000-8000-000000000001","expectedOrderRevision":"7","expectedCatalogRevision":"20","portions":[{"portionId":"8e000000-0000-4000-8000-000000000001","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":"No salt"},{"portionId":"8e000000-0000-4000-8000-000000000002","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":""}]}}"""
    const val OPTIONAL_GUARDED: String = """{"type":"ACTIVITY_PROTECTED_ADD","request":{"orderId":"87000000-0000-4000-8000-000000000001","expectedOrderRevision":"7","expectedCatalogRevision":"20","portions":[{"portionId":"8e000000-0000-4000-8000-000000000001","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":["8d000000-0000-4000-8000-000000000001"],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":"No salt","lineId":"93000000-0000-4000-8000-000000000001","seatNote":"Seat 2"},{"portionId":"8e000000-0000-4000-8000-000000000002","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":""}]}}"""
    const val LEGACY_STOCK: String = """{"type":"PREPARATION","request":{"type":"ADD","orderId":"87000000-0000-4000-8000-000000000001","expectedOrderRevision":"7","expectedCatalogRevision":"20","portions":[{"portionId":"8e000000-0000-4000-8000-000000000001","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":"No salt"},{"portionId":"8e000000-0000-4000-8000-000000000002","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":""}]}}"""
    const val LEGACY_FOOD: String = """{"type":"ADD","orderId":"87000000-0000-4000-8000-000000000001","expectedOrderRevision":"7","expectedCatalogRevision":"20","portions":[{"portionId":"8e000000-0000-4000-8000-000000000001","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":"No salt"},{"portionId":"8e000000-0000-4000-8000-000000000002","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":""}]}"""
    const val MEASURED: String = """{"type":"ACTIVITY_PROTECTED_ADD","request":{"orderId":"87000000-0000-4000-8000-000000000001","expectedOrderRevision":"7","expectedCatalogRevision":"20","portions":[{"portionId":"8e000000-0000-4000-8000-000000000001","itemId":"8a000000-0000-4000-8000-000000000002","recipeId":"8b000000-0000-4000-8000-000000000002","expectedRecipeRevision":"3","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000003","scaled":"1250000","scale":6},"itemNote":""}]}}"""
    const val HIGH_REVISION: String = """{"type":"ACTIVITY_PROTECTED_ADD","request":{"orderId":"87000000-0000-4000-8000-000000000001","expectedOrderRevision":"9007199254740993","expectedCatalogRevision":"9007199254740994","portions":[{"portionId":"8e000000-0000-4000-8000-000000000001","itemId":"8a000000-0000-4000-8000-000000000001","recipeId":"8b000000-0000-4000-8000-000000000001","expectedRecipeRevision":"9007199254740995","choiceId":"8c000000-0000-4000-8000-000000000001","modifierIds":[],"quantity":{"unitId":"8f000000-0000-4000-8000-000000000001","scaled":"1","scale":0},"itemNote":"No salt"}]}}"""

    fun guarded(orderId: DomainId = restaurantOrder): LocalFoodRequestV1.Add = LocalFoodRequestV1.Add(
        orderId, Revision(7), Revision(20), listOf(portionIds[0], portionIds[1]).mapIndexed { index, id ->
            LocalFoodPortionSelectionV1(id, item, recipe, Revision(3), choice, emptyList(),
                Quantity(pieceUnit, 1, 0), if (index == 0) "No salt" else "")
        })

    fun optionalGuarded(): LocalFoodRequestV1.Add = guarded().let { original -> original.copy(
        portions = listOf(original.portions[0].copy(modifierIds = listOf(modifier), lineId = line, seatNote = "Seat 2"),
            original.portions[1])) }

    fun onePortion(): LocalFoodRequestV1.Add = guarded().let { it.copy(portions = listOf(it.portions[0])) }
    fun eightPortions(): LocalFoodRequestV1.Add = guarded().let { original -> original.copy(
        portions = portionIds.take(8).map { original.portions[1].copy(portionId = it) }) }

    fun measured(): LocalFoodRequestV1.Add = LocalFoodRequestV1.Add(restaurantOrder, Revision(7), Revision(20),
        listOf(LocalFoodPortionSelectionV1(portionIds[0], measuredItem, measuredRecipe, Revision(3), choice,
            emptyList(), Quantity(kgUnit, 1250000, 6), "")))

    fun highRevision(): LocalFoodRequestV1.Add = onePortion().let { original -> original.copy(
        expectedOrderRevision = Revision(9007199254740993), expectedCatalogRevision = Revision(9007199254740994),
        portions = listOf(original.portions.single().copy(expectedRecipeRevision = Revision(9007199254740995)))) }

    /** GFA-003 explicitly permits only the café target-coordinate change to this body. */
    fun cafeGolden(): String = GUARDED.replace(restaurantOrder.value, cafeOrder.value)

    fun intent(formVersion: Int = 2, operationId: OperationId = operation,
        request: LocalFoodRequestV1.Add = guarded()): LocalCommandIntent = LocalCommandIntent(
        operationId, LocalAction.FOOD_ADD, formVersion, null, values(request), LocalField.LINES, inputLines(request))

    fun preview(formVersion: Int = 2, request: LocalFoodRequestV1.Add = guarded()): LocalFoodPreviewInput =
        LocalFoodPreviewInput(LocalAction.FOOD_ADD, formVersion, values(request), inputLines(request))

    private fun values(request: LocalFoodRequestV1.Add): List<LocalScalarInput> = listOf(
        LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(request.orderId)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(request.expectedOrderRevision.value)),
        LocalScalarInput(LocalField.EXPECTED_CATALOG_REVISION, LocalScalarValue.Integer(request.expectedCatalogRevision.value)),
    )

    private fun inputLines(request: LocalFoodRequestV1.Add): List<LocalInputLine> = request.portions.map { portion ->
        val decimal = when (portion.quantity) {
            Quantity(pieceUnit, 1, 0) -> "1"
            Quantity(kgUnit, 1250000, 6) -> "1.25"
            else -> error("No frozen valid form-input binding for this quantity")
        }
        // The ID labels bind required public menu text; they are not display or price oracles.
        LocalInputLine(portion.portionId, listOf(
            LocalScalarInput(LocalField.RECIPE_ID, LocalScalarValue.Identifier(portion.recipeId)),
            LocalScalarInput(LocalField.EXPECTED_FOOD_RECIPE_REVISION, LocalScalarValue.Integer(portion.expectedRecipeRevision.value)),
            LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(portion.itemId)),
            LocalScalarInput(LocalField.CHOICE_ID, LocalScalarValue.Identifier(portion.choiceId)),
            LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(portion.quantity.unitId)),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(decimal)),
            LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(portion.quantity.scale.toLong())),
            LocalScalarInput(LocalField.MODIFIER_IDS, LocalScalarValue.Text(portion.modifierIds.joinToString(",") { it.value })),
            LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text(portion.choiceId.value)),
            LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text("")),
            LocalScalarInput(LocalField.ITEM_NOTE, LocalScalarValue.Text(portion.itemNote)),
        ))
    }
}

/** A literal JSON-pointer edit, independently applied to the frozen body named by the case. */
internal fun foodGuardedAddFrozenEdit(base: String, pointer: String, value: JsonElement?): String {
    val coordinates = pointer.removePrefix("/").split('/')
    fun edit(current: JsonElement, remaining: List<String>): JsonElement {
        val key = remaining.first()
        return when (current) {
            is JsonObject -> JsonObject(current.toMutableMap().also { fields ->
                if (remaining.size == 1) {
                    if (value == null) fields.remove(key) else fields[key] = value
                } else fields[key] = edit(fields.getValue(key), remaining.drop(1))
            })
            is JsonArray -> JsonArray(current.toMutableList().also { elements ->
                val index = key.toInt()
                if (remaining.size == 1) {
                    if (value == null) elements.removeAt(index) else elements[index] = value
                } else elements[index] = edit(elements[index], remaining.drop(1))
            })
            else -> error("Frozen edit path does not name an object or array")
        }
    }
    return edit(Json.parseToJsonElement(base), coordinates).toString()
}

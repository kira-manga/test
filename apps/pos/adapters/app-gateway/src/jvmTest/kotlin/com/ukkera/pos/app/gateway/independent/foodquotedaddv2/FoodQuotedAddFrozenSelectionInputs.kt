package com.ukkera.pos.app.gateway.independent.foodquotedaddv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exact additive FQADD2-BINDING-001 and -004 selections. Only the selection
 * arrays have independent canonical bytes; order/cursor coordinates remain
 * controlled gateway metadata, not native source or command-envelope evidence.
 */
internal object FoodQuotedAddFrozenSelectionInputs {
    val firstPortion: DomainId = DomainId("00000000-0000-4000-8000-00000000a102")
    val secondPortion: DomainId = DomainId("00000000-0000-4000-8000-00000000a101")
    val laterPortion: DomainId = DomainId("00000000-0000-4000-8000-00000000a104")

    val firstSelections: List<LocalFoodPortionSelectionV1> = listOf(
        selection(firstPortion, "No salt", firstPortion, "Seat 2"),
        selection(secondPortion, "Separate bowl", secondPortion, "Seat 1"),
    )

    // A separately frozen raw-default input, never a continuation-time rewrite
    // of BINDING-001's explicit self-member line anchors.
    val singleDefaultSelections: List<LocalFoodPortionSelectionV1> = listOf(
        selection(firstPortion, "No salt", null, "Seat 2"),
    )

    const val FIRST_CANONICAL_ARRAY: String = """[{"portionId":"00000000-0000-4000-8000-00000000a102","itemId":"00000000-0000-4000-8000-00000000a201","recipeId":"00000000-0000-4000-8000-00000000a301","expectedRecipeRevision":"11","choiceId":"00000000-0000-4000-8000-00000000a303","modifierIds":[],"quantity":{"unitId":"00000000-0000-4000-8000-00000000a501","scaled":"1","scale":0},"itemNote":"No salt","lineId":"00000000-0000-4000-8000-00000000a102","seatNote":"Seat 2"},{"portionId":"00000000-0000-4000-8000-00000000a101","itemId":"00000000-0000-4000-8000-00000000a201","recipeId":"00000000-0000-4000-8000-00000000a301","expectedRecipeRevision":"11","choiceId":"00000000-0000-4000-8000-00000000a303","modifierIds":[],"quantity":{"unitId":"00000000-0000-4000-8000-00000000a501","scaled":"1","scale":0},"itemNote":"Separate bowl","lineId":"00000000-0000-4000-8000-00000000a101","seatNote":"Seat 1"}]"""
    const val SINGLE_DEFAULT_CANONICAL_ARRAY: String = """[{"portionId":"00000000-0000-4000-8000-00000000a102","itemId":"00000000-0000-4000-8000-00000000a201","recipeId":"00000000-0000-4000-8000-00000000a301","expectedRecipeRevision":"11","choiceId":"00000000-0000-4000-8000-00000000a303","modifierIds":[],"quantity":{"unitId":"00000000-0000-4000-8000-00000000a501","scaled":"1","scale":0},"itemNote":"No salt","seatNote":"Seat 2"}]"""

    fun singletonIntent(formVersion: Int = 3): LocalCommandIntent = intent(formVersion,
        listOf(uiLine(firstPortion, "No salt", "Seat 2")))

    /** Stable input selectors for a supplied saved request, not initial parser admission of explicit anchors. */
    fun savedFirstInputIntent(formVersion: Int = 3): LocalCommandIntent = intent(formVersion, listOf(
        uiLine(firstPortion, "No salt", "Seat 2"),
        uiLine(secondPortion, "Separate bowl", "Seat 1"),
    ))

    fun firstAdd(): LocalFoodRequestV1.Add = add(firstSelections)

    fun add(selections: List<LocalFoodPortionSelectionV1>): LocalFoodRequestV1.Add = LocalFoodRequestV1.Add(
        FoodQuotedAddBoundedUiInputs.selectorOrder,
        FoodQuotedAddBoundedUiInputs.displayedOrderRevision,
        FoodQuotedAddBoundedUiInputs.displayedCatalogRevision,
        selections,
    )

    fun quotedFirstRequest(): MutationRequest = LocalFoodStockCodecV2.command(
        FoodQuotedAddBoundedUiInputs.firstOperation,
        LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(firstAdd()),
    )

    fun savedFirstEntry(formVersion: Int = 3, request: MutationRequest = quotedFirstRequest()): StoredClientIntentV1 =
        StoredClientIntentV1.staged(
            LocalClientIdentity(FoodQuotedAddBoundedUiInputs.scope, FoodQuotedAddBoundedUiInputs.owner,
                FoodQuotedAddBoundedUiInputs.device),
            savedFirstInputIntent(formVersion), request,
            LocalText("Supplied quoted Add", "Supplied quoted Add"),
            listOf(LocalRecordValue(LocalText("Supplied order", "Supplied order"),
                LocalText(FoodQuotedAddBoundedUiInputs.selectorOrder.value, FoodQuotedAddBoundedUiInputs.selectorOrder.value))),
        )

    private fun selection(portion: DomainId, itemNote: String, lineId: DomainId?, seatNote: String): LocalFoodPortionSelectionV1 =
        LocalFoodPortionSelectionV1(portion, FoodQuotedAddBoundedUiInputs.outputSku,
            FoodQuotedAddBoundedUiInputs.recipe, Revision(11), FoodQuotedAddBoundedUiInputs.choice, emptyList(),
            Quantity(FoodQuotedAddBoundedUiInputs.portionUnit, 1, 0), itemNote, lineId, seatNote)

    private fun intent(formVersion: Int, lines: List<LocalInputLine>): LocalCommandIntent = LocalCommandIntent(
        FoodQuotedAddBoundedUiInputs.firstOperation, LocalAction.FOOD_ADD, formVersion, null,
        listOf(
            LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(FoodQuotedAddBoundedUiInputs.selectorOrder)),
            LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
                LocalScalarValue.Integer(FoodQuotedAddBoundedUiInputs.displayedOrderRevision.value)),
            LocalScalarInput(LocalField.EXPECTED_CATALOG_REVISION,
                LocalScalarValue.Integer(FoodQuotedAddBoundedUiInputs.displayedCatalogRevision.value)),
        ), LocalField.LINES, lines,
    )

    private fun uiLine(portion: DomainId, itemNote: String, seatNote: String): LocalInputLine = LocalInputLine(portion, listOf(
        LocalScalarInput(LocalField.RECIPE_ID, LocalScalarValue.Identifier(FoodQuotedAddBoundedUiInputs.recipe)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_RECIPE_REVISION, LocalScalarValue.Integer(11)),
        LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(FoodQuotedAddBoundedUiInputs.outputSku)),
        LocalScalarInput(LocalField.CHOICE_ID, LocalScalarValue.Identifier(FoodQuotedAddBoundedUiInputs.choice)),
        LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(FoodQuotedAddBoundedUiInputs.portionUnit)),
        LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(0)),
        LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("1")),
        LocalScalarInput(LocalField.MODIFIER_IDS, LocalScalarValue.Text("")),
        // The supplied label binds the controlled menu only; it is not a captured recipe description oracle.
        LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text(FoodQuotedAddBoundedUiInputs.choice.value)),
        LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text("")),
        LocalScalarInput(LocalField.ITEM_NOTE, LocalScalarValue.Text(itemNote)),
        LocalScalarInput(LocalField.LINE_SEAT_NOTE, LocalScalarValue.Text(seatNote)),
        LocalScalarInput(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(1)),
    ))
}

/** Public profile/field checks plus the unchanged independently frozen selection-array bytes. */
internal fun assertFoodQuotedAddBoundedRequest(request: MutationRequest,
    selections: List<LocalFoodPortionSelectionV1>, canonicalArray: String) {
    assertEquals(FoodQuotedAddBoundedUiInputs.firstOperation, request.operationId)
    assertEquals("sales.food.stock", request.command.name)
    assertEquals(2, request.command.version)
    assertNull(request.actionApprovalId)
    val wrapper = assertIs<LocalFoodStockRequestV2.ActivityProtectedQuotedAdd>(LocalFoodStockCodecV2.decode(request.payload))
    assertEquals(FoodQuotedAddFrozenSelectionInputs.add(selections), wrapper.request)
    val raw = request.payload.copyBytes().decodeToString()
    val outer = foodQuotedAddUiJson.parseToJsonElement(raw).jsonObject
    assertEquals(setOf("type", "request"), outer.keys)
    assertEquals("ACTIVITY_PROTECTED_QUOTED_ADD", outer.getValue("type").jsonPrimitive.content)
    val body = outer.getValue("request").jsonObject
    assertEquals(setOf("orderId", "expectedOrderRevision", "expectedCatalogRevision", "portions"), body.keys)
    assertTrue(body.getValue("expectedOrderRevision").jsonPrimitive.isString)
    assertTrue(body.getValue("expectedCatalogRevision").jsonPrimitive.isString)
    assertEquals(canonicalArray, body.getValue("portions").toString())
    assertTrue(raw.contains("\"portions\":$canonicalArray"), "The frozen canonical selection array must occur unchanged on the wire")
}

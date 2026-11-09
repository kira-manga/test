package com.ukkera.pos.app.independent.foodtableassignmentrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FTA-RPC-v1-S01/S02/S03 presentation gates only; current authority remains at the Master. */
class FoodTableAssignmentRpcV1UiProfileSupportingTest {
    @Test
    fun s01SelectorsAndRoutesExcludeOtherFoodOperations() {
        val input = TableAssignmentUiInput
        val allowed = listOf(LocalFoodUiQuery.MyChecks(), LocalFoodUiQuery.Check(input.orderId),
            LocalFoodUiQuery.TableAssignment(input.orderId, Revision(7)), LocalFoodUiQuery.FloorDirectory(),
            LocalFoodUiQuery.FloorTables(input.floorId), input.selection)
        allowed.forEach { assertTrue(it.isFoodTableAssignmentUiQuery(), it.toString()) }
        val excluded = listOf(LocalFoodUiQuery.Menu(), LocalFoodUiQuery.ConfiguredStations(),
            LocalFoodUiQuery.StationTasks(input.tableId), LocalFoodUiQuery.Recipients(input.orderId, Revision(7)),
            LocalFoodUiQuery.Collection(input.orderId), LocalFoodUiQuery.Receipt(input.orderId),
            LocalFoodUiQuery.TableAssignment(input.orderId, Revision(0)))
        excluded.forEach { assertFalse(it.isFoodTableAssignmentUiQuery(), it.toString()) }
        listOf(AppRoute.Orders, AppRoute.Food, AppRoute.FoodOrder(input.orderId), AppRoute.SessionAccess)
            .forEach { assertTrue(foodTableAssignmentUiRoute(it)) }
        listOf(AppRoute.Cashier, AppRoute.FoodStation(input.tableId), AppRoute.FoodHandoff(input.orderId),
            AppRoute.Drawer, AppRoute.Catalog, AppRoute.Setup).forEach { assertFalse(foodTableAssignmentUiRoute(it)) }
    }

    @Test
    fun s01OnlySevenScalarObservedFormCoordinatesAreAdmitted() {
        val input = TableAssignmentUiInput
        val form = input.form()
        assertTrue(form.isFoodTableAssignmentUiForm(input.selection, input.destination))
        for (action in listOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD, LocalAction.FOOD_SEND,
            LocalAction.FOOD_EDIT_SERVICE_DETAILS, LocalAction.FOOD_OFFER_HANDOFF, LocalAction.FOOD_BEGIN_COLLECTION,
            LocalAction.FLOOR_CONFIGURE, LocalAction.TABLE_CONFIGURE)) {
            assertFalse(form.copy(action = action).isFoodTableAssignmentUiForm(input.selection, input.destination), action.name)
        }
        val invalid = listOf(form.copy(version = 2), form.copy(reservedOperationId = input.a),
            form.copy(confirmation = null), form.copy(fields = form.fields.dropLast(1)),
            form.copy(fields = form.fields + LocalFormField.Lines(LocalField.LINES, input.text("Empty group"),
                listOf(LocalFormField.Scalar(LocalField.QUANTITY, input.text("Quantity"), LocalInputKind.INTEGER)),
                minimumLines = 0, maximumLines = 1)),
            form.copy(fields = form.fields.map { if (it.field == LocalField.TABLE_ID)
                (it as LocalFormField.Scalar).copy(readOnly = false) else it }),
            form.copy(fields = form.fields.map { if (it.field == LocalField.EXPECTED_FLOOR_STORAGE_REVISION)
                (it as LocalFormField.Scalar).copy(initialValue = LocalScalarValue.Integer(1)) else it }))
        invalid.forEach { assertFalse(it.isFoodTableAssignmentUiForm(input.selection, input.destination)) }
    }

    @Test
    fun s02FreshDestinationDoesNotFreshenTheStagedOriginalCoordinates() {
        val input = TableAssignmentUiInput
        val form = input.form()
        val refreshed = LocalFoodUiBody.Table(input.floor.copy(storageRevision = Revision(3)),
            input.table.copy(storageRevision = Revision(4)))
        assertFalse(form.isFoodTableAssignmentUiForm(input.selection, refreshed))
        val original = input.original()
        assertTrue(original.isFoodTableAssignmentUiIntent())
        assertEquals(input.a, original.operationId)
        assertEquals(input.values, original.values)
        assertFalse(LocalCommandIntent(input.a, original.action, 1,
            LocalRecordReference(LocalRecordKind.ORDER, input.orderId), original.values).isFoodTableAssignmentUiIntent())
        assertFalse(LocalCommandIntent(input.a, original.action, 1, null, original.values,
            lineField = LocalField.LINES).isFoodTableAssignmentUiIntent())
        assertFalse(LocalCommandIntent(input.a, original.action, 1, null, original.values +
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Integer(1))).isFoodTableAssignmentUiIntent())
    }

    @Test
    fun s03OnlyTheOriginalAssignmentTerminalPresentationIsAdmitted() {
        val input = TableAssignmentUiInput
        val original = input.original()
        for (decision in listOf(LocalRecordedDecision.APPLIED, LocalRecordedDecision.REJECTED)) {
            assertTrue(input.recorded(decision = decision).isFoodTableAssignmentUiResult(LocalAction.FOOD_ASSIGN_TABLE, original))
        }
        assertFalse(input.recorded(input.b).isFoodTableAssignmentUiResult(LocalAction.FOOD_ASSIGN_TABLE, original))
        assertFalse(input.recorded(decision = LocalRecordedDecision.RECORDED_ONLY)
            .isFoodTableAssignmentUiResult(LocalAction.FOOD_ASSIGN_TABLE, original))
        assertFalse(input.recorded().copy(result = LocalRecord(LocalRecordReference(LocalRecordKind.ORDER, input.orderId), input.text("Foreign record")))
            .isFoodTableAssignmentUiResult(LocalAction.FOOD_ASSIGN_TABLE, original))
        assertFalse(LocalOperationResult.Pending(input.pending().copy(action = LocalAction.FOOD_CREATE_DRAFT))
            .isFoodTableAssignmentUiResult(LocalAction.FOOD_ASSIGN_TABLE, original))
        assertFalse(LocalOperationResult.Unresolved(input.b, LocalServiceProblem.UNAVAILABLE)
            .isFoodTableAssignmentUiResult(LocalAction.FOOD_ASSIGN_TABLE, original))
        // The raw typed success/refusal comparison and durable remembrance are separate gateway bindings.
    }
}

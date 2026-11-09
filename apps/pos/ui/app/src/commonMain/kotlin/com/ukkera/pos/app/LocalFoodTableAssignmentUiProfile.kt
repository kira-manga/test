package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.AppRoute

/** Presentation refusal gates only; the Master retains current check and directory authority. */
internal fun foodTableAssignmentUiRoute(route: AppRoute): Boolean = route == AppRoute.Orders ||
    route == AppRoute.Food || route is AppRoute.FoodOrder || route == AppRoute.SessionAccess

internal fun LocalFoodUiQuery.isFoodTableAssignmentUiQuery(): Boolean = when (this) {
    is LocalFoodUiQuery.MyChecks, is LocalFoodUiQuery.Check, is LocalFoodUiQuery.FloorDirectory,
    is LocalFoodUiQuery.FloorTables, is LocalFoodUiQuery.TableDefinition -> true
    is LocalFoodUiQuery.TableAssignment -> expectedOrderStorageRevision.value >= 1
    else -> false
}

private val tableAssignmentIdFields = setOf(LocalField.ORDER_ID, LocalField.FLOOR_ID, LocalField.TABLE_ID)
private val tableAssignmentRevisionFields = setOf(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
    LocalField.EXPECTED_FLOOR_STORAGE_REVISION, LocalField.EXPECTED_TABLE_STORAGE_REVISION)
private val tableAssignmentRequiredFields = tableAssignmentIdFields + tableAssignmentRevisionFields
private val tableAssignmentFields = tableAssignmentRequiredFields + LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION

/** All destination and source coordinates are fixed by the actual reviewed protected pages. */
internal fun LocalActionForm.isFoodTableAssignmentUiForm(query: LocalFoodUiQuery,
    destination: LocalFoodUiBody.Table? = null): Boolean {
    val selection = query as? LocalFoodUiQuery.TableDefinition ?: return false
    val target = selection.assignment ?: return false
    if (action != LocalAction.FOOD_ASSIGN_TABLE || version != 1 || reservedOperationId != null ||
        confirmation == null || openingAllocations != null || arrivalEvidence != null ||
        fields.any { it !is LocalFormField.Scalar }) return false
    val scalars = fields.filterIsInstance<LocalFormField.Scalar>()
    if (scalars.size != tableAssignmentFields.size || scalars.map { it.field }.toSet() != tableAssignmentFields ||
        scalars.any { !it.readOnly || it.allowNegative || it.referenceSource != null || it.references.isNotEmpty() || it.choices.isNotEmpty() ||
            it.required != (it.field != LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION) ||
            it.kind != (if (it.field in tableAssignmentIdFields) LocalInputKind.IDENTIFIER else LocalInputKind.INTEGER) }) return false
    fun fixed(field: LocalField): LocalScalarValue? = scalars.single { it.field == field }.initialValue
    if (tableAssignmentIdFields.any { fixed(it) !is LocalScalarValue.Identifier } ||
        tableAssignmentRevisionFields.any { (fixed(it) as? LocalScalarValue.Integer)?.value?.let { value -> value >= 1 } != true } ||
        fixed(LocalField.ORDER_ID) != LocalScalarValue.Identifier(target.orderId) ||
        fixed(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION) != LocalScalarValue.Integer(target.expectedOrderStorageRevision.value) ||
        fixed(LocalField.TABLE_ID) != LocalScalarValue.Identifier(selection.tableId) ||
        fixed(LocalField.FLOOR_ID) == fixed(LocalField.TABLE_ID) ||
        fixed(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION) !=
            target.expectedAssignmentStorageRevision?.let { LocalScalarValue.Integer(it.value) }) return false
    return destination == null || (destination.floor.active && destination.table.active &&
        destination.table.id == selection.tableId && destination.table.floorId == destination.floor.id &&
        fixed(LocalField.FLOOR_ID) == LocalScalarValue.Identifier(destination.floor.id) &&
        fixed(LocalField.EXPECTED_FLOOR_STORAGE_REVISION) == LocalScalarValue.Integer(destination.floor.storageRevision.value) &&
        fixed(LocalField.EXPECTED_TABLE_STORAGE_REVISION) == LocalScalarValue.Integer(destination.table.storageRevision.value))
}

internal fun LocalWorkflowPage.matchesFoodTableAssignmentUiPage(query: LocalFoodUiQuery): Boolean {
    val source = food ?: return false
    if (workflow != LocalWorkflow.FOOD || !query.isFoodTableAssignmentUiQuery() || source.query != query ||
        source.next?.isFoodTableAssignmentUiQuery() == false || records.isNotEmpty() || nextCursor != null ||
        priceControls != null || branchReport != null || openingAllocations != null || managerQuoteDetails != null ||
        returnRights != null || forms.any { !it.isFoodTableAssignmentUiForm(query, source.body as? LocalFoodUiBody.Table) }) return false
    return when (query) {
        is LocalFoodUiQuery.MyChecks -> source.body is LocalFoodUiBody.Checks && forms.isEmpty()
        is LocalFoodUiQuery.Check -> source.body is LocalFoodUiBody.Check && forms.isEmpty() &&
            source.body.check.orderStorageRevision.value >= 1 && source.body.kitchen == null &&
            source.body.delivery == null && source.body.currentUnpaidPayable == null
        is LocalFoodUiQuery.TableAssignment -> source.body is LocalFoodUiBody.TableAssignment && forms.isEmpty()
        is LocalFoodUiQuery.FloorDirectory -> source.body is LocalFoodUiBody.Floors && forms.isEmpty()
        is LocalFoodUiQuery.FloorTables -> source.body is LocalFoodUiBody.Tables && forms.isEmpty()
        is LocalFoodUiQuery.TableDefinition -> source.body is LocalFoodUiBody.Table &&
            (query.assignment != null || forms.isEmpty())
        else -> false
    }
}

internal fun LocalCommandIntent.isFoodTableAssignmentUiIntent(): Boolean {
    if (action != LocalAction.FOOD_ASSIGN_TABLE || formVersion != 1 || reference != null || arrivalEvidence != null ||
        lineField != null || lines.isNotEmpty() || !values.map { it.field }.toSet().containsAll(tableAssignmentRequiredFields) ||
        values.any { it.field !in tableAssignmentFields }) return false
    fun fixed(field: LocalField): LocalScalarValue? = values.singleOrNull { it.field == field }?.value
    return tableAssignmentIdFields.all { fixed(it) is LocalScalarValue.Identifier } &&
        tableAssignmentRevisionFields.all { (fixed(it) as? LocalScalarValue.Integer)?.value?.let { value -> value >= 1 } == true } &&
        fixed(LocalField.FLOOR_ID) != fixed(LocalField.TABLE_ID) &&
        (fixed(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION)?.let {
            it is LocalScalarValue.Integer && it.value >= 1
        } != false)
}

internal fun LocalPendingOperation.isFoodTableAssignmentUiPending(): Boolean = action == LocalAction.FOOD_ASSIGN_TABLE &&
    actionApproval == null && recordedDecision != LocalRecordedDecision.RECORDED_ONLY

/** The gateway qualifies the terminal typed binding against its actual saved original first. */
internal fun LocalOperationResult.isFoodTableAssignmentUiResult(action: LocalAction? = null,
    original: LocalCommandIntent? = null): Boolean {
    if (action != null && action != LocalAction.FOOD_ASSIGN_TABLE || original?.isFoodTableAssignmentUiIntent() == false ||
        original != null && action != null && original.action != action) return false
    return when (this) {
        is LocalOperationResult.Pending -> operation.isFoodTableAssignmentUiPending() &&
            (original == null || operation.operationId == original.operationId)
        is LocalOperationResult.Unresolved -> original == null || operationId == original.operationId
        is LocalOperationResult.Recorded -> decision in setOf(LocalRecordedDecision.APPLIED, LocalRecordedDecision.REJECTED) &&
            result == null && food == null && returnStock == null && (original == null || operationId == original.operationId)
    }
}

/** Incompatible continuations stay inert without replacing unresolved durable request identities. */
internal fun LocalWorkspaceState.isFoodTableAssignmentUiWorkspace(): Boolean = workflow == LocalWorkflow.FOOD &&
    search.isEmpty() && reference == null && foodQuery.isFoodTableAssignmentUiQuery() &&
    form?.let { it.descriptor.isFoodTableAssignmentUiForm(foodQuery) && it.foodRecipientSource == null && it.lines.isEmpty() &&
        it.values.keys == tableAssignmentFields } != false &&
    formInput?.let { input -> input.action == LocalAction.FOOD_ASSIGN_TABLE && input.version == 1 &&
        foodQuery is LocalFoodUiQuery.TableDefinition && foodQuery.assignment != null && input.reservedOperationId == null &&
        input.openingQuery == null && input.foodRecipientSource == null && input.lineField == null && input.lines.isEmpty() &&
        input.columns.isEmpty() && input.sourceLineIds.isEmpty() && input.minimumLines == null && input.maximumLines == null &&
        input.allowBlankLines == null && input.allowDuplicateLines == null && input.scalars.keys == tableAssignmentFields &&
        input.values.keys.all { it in tableAssignmentFields } && input.scalars.all { (field, shape) -> shape.readOnly &&
            !shape.allowNegative && shape.required == (field != LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION) &&
            shape.kind == (if (field in tableAssignmentIdFields) LocalInputKind.IDENTIFIER else LocalInputKind.INTEGER) } } != false &&
    page?.matchesFoodTableAssignmentUiPage(foodQuery) != false && pending.all { it.isFoodTableAssignmentUiPending() } &&
    operation?.isFoodTableAssignmentUiResult() != false && referencePicker == null && openingCounts == null &&
    priceDraft == null && pricePreview == null && foodPricePreview == null && foodPricePreviewProblem == null &&
    kitchenBoardOrigin == null && actionApproval == null && !purchasingDocuments.hasDraft

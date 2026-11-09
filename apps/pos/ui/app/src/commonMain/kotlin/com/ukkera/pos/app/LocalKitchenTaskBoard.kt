package com.ukkera.pos.app

/** A board affordance only. The fresh protected Task and its existing form still decide admission. */
internal fun LocalFoodUiTask.kitchenBoardAction(): LocalAction? = when {
    taskState == LocalFoodTaskState.QUEUED && preparation == LocalFoodPreparation.QUEUED ->
        LocalAction.FOOD_STATION_START
    taskState == LocalFoodTaskState.STARTED && preparation == LocalFoodPreparation.PREPARING ->
        LocalAction.FOOD_STATION_READY
    else -> null
}

/** Compare business coordinates; all displayed review content comes from the fresh reply. */
internal fun LocalFoodUiTask.matchesKitchenBoardSelection(selected: LocalFoodUiTask): Boolean =
    stationId == selected.stationId && taskId == selected.taskId && orderId == selected.orderId &&
        portionId == selected.portionId && orderStorageRevision == selected.orderStorageRevision &&
        preparationDomainRevision == selected.preparationDomainRevision && taskState == selected.taskState &&
        preparation == selected.preparation

/** Refusal-only binding to the unchanged six fixed task fields. Never rewrite a returned form. */
internal fun LocalActionForm.matchesKitchenBoardTask(task: LocalFoodUiTask, selectedAction: LocalAction): Boolean {
    if (action != selectedAction || action != task.kitchenBoardAction() || version != 1 ||
        reservedOperationId != null || openingAllocations != null || confirmation == null || fields.size != 6 ||
        fields.any { it !is LocalFormField.Scalar }) return false
    val scalars = fields.filterIsInstance<LocalFormField.Scalar>()
    fun fixed(field: LocalField, kind: LocalInputKind, value: LocalScalarValue): Boolean =
        scalars.singleOrNull { it.field == field }?.let {
            it.required && it.readOnly && it.kind == kind && it.initialValue == value &&
                it.referenceSource == null && it.references.isEmpty() && it.choices.isEmpty()
        } == true
    return fixed(LocalField.ORDER_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(task.orderId)) &&
        fixed(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalInputKind.INTEGER,
            LocalScalarValue.Integer(task.orderStorageRevision.value)) &&
        fixed(LocalField.PORTION_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(task.portionId)) &&
        fixed(LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION, LocalInputKind.INTEGER,
            LocalScalarValue.Integer(task.preparationDomainRevision.value)) &&
        fixed(LocalField.STATION_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(task.stationId)) &&
        fixed(LocalField.TASK_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(task.taskId))
}

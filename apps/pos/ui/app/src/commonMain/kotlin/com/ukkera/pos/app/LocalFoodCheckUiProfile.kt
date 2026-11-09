package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId

/** Closed presentation subset only; every source, command and original still needs Master authority. */
internal val foodCheckUiActions: Set<LocalAction> = setOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD)

internal fun foodCheckUiRoute(route: AppRoute): Boolean = route == AppRoute.Orders || route == AppRoute.Food ||
    route is AppRoute.FoodOrder || route == AppRoute.SessionAccess

internal fun LocalFoodUiQuery.isFoodCheckUiQuery(): Boolean = when (this) {
    is LocalFoodUiQuery.MyChecks, is LocalFoodUiQuery.Check -> true
    is LocalFoodUiQuery.Menu -> expectedOrderStorageRevision?.value?.let { it >= 1 } != false
    else -> false
}

internal fun LocalAction.isFoodCheckUiAction(version: Int): Boolean = when (this) {
    LocalAction.FOOD_CREATE_DRAFT -> version == 2
    LocalAction.FOOD_ADD -> version == 3
    else -> false
}

internal fun LocalFoodUiBody.Check.foodCheckAddMenu(): LocalFoodUiQuery.Menu? =
    if (check.workClosed || check.collectionId != null || financial != null || check.handoffId != null || check.portionCount >= 32) null
    else LocalFoodUiQuery.Menu(orderId = check.orderId, expectedOrderStorageRevision = check.orderStorageRevision)

private val foodCheckDraftFields = setOf(LocalField.ORDER_ID, LocalField.ACTIVITY, LocalField.CHANNEL,
    LocalField.SERVICE_REFERENCE, LocalField.SEAT_NOTE, LocalField.FOOD_DELIVERY_RECIPIENT,
    LocalField.FOOD_DELIVERY_CONTACT, LocalField.FOOD_DELIVERY_ADDRESS)
private val foodCheckAddFields = setOf(LocalField.ORDER_ID, LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
    LocalField.EXPECTED_CATALOG_REVISION)
private val foodCheckSelectionFields = setOf(LocalField.RECIPE_ID, LocalField.EXPECTED_FOOD_RECIPE_REVISION,
    LocalField.SKU_ID, LocalField.CHOICE_ID, LocalField.MODIFIER_IDS, LocalField.UNIT, LocalField.QUANTITY_SCALE,
    LocalField.QUANTITY, LocalField.FOOD_SERVING_COUNT, LocalField.NAME_ENGLISH, LocalField.NAME_ARABIC,
    LocalField.ITEM_NOTE, LocalField.LINE_SEAT_NOTE)

/** Refusal checks on existing forms; this profile never constructs a broader FOOD editor. */
internal fun LocalActionForm.isFoodCheckUiForm(query: LocalFoodUiQuery): Boolean {
    if (!action.isFoodCheckUiAction(version) || reservedOperationId != null || openingAllocations != null ||
        arrivalEvidence != null || confirmation == null) return false
    val scalars = fields.filterIsInstance<LocalFormField.Scalar>()
    val groups = fields.filterIsInstance<LocalFormField.Lines>()
    if (scalars.map { it.field }.toSet().size != scalars.size || groups.size > 1) return false
    if (scalars.any { it.referenceSource != null || it.references.isNotEmpty() ||
            it.kind in setOf(LocalInputKind.REFERENCE, LocalInputKind.MULTI_REFERENCE) }) return false
    val group = groups.singleOrNull()
    return when (action) {
        LocalAction.FOOD_CREATE_DRAFT -> query is LocalFoodUiQuery.MyChecks && group == null &&
            scalars.map { it.field }.toSet() == foodCheckDraftFields &&
            scalars.single { it.field == LocalField.ORDER_ID }.let { it.readOnly && it.kind == LocalInputKind.IDENTIFIER }
        LocalAction.FOOD_ADD -> {
            val menu = query as? LocalFoodUiQuery.Menu ?: return false
            if (menu.orderId == null || menu.expectedOrderStorageRevision == null ||
                scalars.map { it.field }.toSet() != foodCheckAddFields || scalars.any { !it.readOnly }) return false
            fun fixed(field: LocalField): LocalScalarValue? = scalars.single { it.field == field }.initialValue
            fixed(LocalField.ORDER_ID) == LocalScalarValue.Identifier(menu.orderId) &&
                fixed(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION) == LocalScalarValue.Integer(menu.expectedOrderStorageRevision.value) &&
                (fixed(LocalField.EXPECTED_CATALOG_REVISION) as? LocalScalarValue.Integer)?.value?.let { it >= 0 } == true &&
                (group == null || group.field == LocalField.LINES && group.minimumLines == 1 && group.maximumLines == 8 &&
                    !group.allowBlankLines && group.columns.map { it.field }.toSet() == foodCheckSelectionFields &&
                    group.columns.all { it.referenceSource == null && it.references.isEmpty() &&
                        it.kind !in setOf(LocalInputKind.REFERENCE, LocalInputKind.MULTI_REFERENCE) &&
                        (it.readOnly || it.field in setOf(LocalField.FOOD_SERVING_COUNT, LocalField.ITEM_NOTE, LocalField.LINE_SEAT_NOTE)) })
        }
        else -> false
    }
}

internal fun LocalWorkflowPage.matchesFoodCheckUiPage(query: LocalFoodUiQuery): Boolean {
    val source = food ?: return false
    if (workflow != LocalWorkflow.FOOD || !query.isFoodCheckUiQuery() || source.query != query ||
        source.next?.isFoodCheckUiQuery() == false || records.isNotEmpty() || nextCursor != null || priceControls != null ||
        branchReport != null || openingAllocations != null || managerQuoteDetails != null || returnRights != null ||
        forms.any { !it.isFoodCheckUiForm(query) || it.fields.any { field -> field is LocalFormField.Lines } }) return false
    return when (query) {
        is LocalFoodUiQuery.MyChecks -> source.body is LocalFoodUiBody.Checks
        is LocalFoodUiQuery.Menu -> source.body is LocalFoodUiBody.Menu
        is LocalFoodUiQuery.Check -> {
            val check = source.body as? LocalFoodUiBody.Check ?: return false
            val kitchen = check.kitchen ?: return false
            forms.isEmpty() && !check.tableAssignmentAvailable && check.check.orderStorageRevision.value >= 1 &&
                (kitchen.unsentPortionCount != 0 || kitchen.status == LocalFoodUiKitchenStatus.NO_UNSENT_WORK)
        }
        else -> false
    }
}

internal fun LocalCommandIntent.isFoodCheckUiIntent(): Boolean = action.isFoodCheckUiAction(formVersion) &&
    reference == null && arrivalEvidence == null && when (action) {
        LocalAction.FOOD_CREATE_DRAFT -> lineField == null && lines.isEmpty() && values.all { it.field in foodCheckDraftFields }
        LocalAction.FOOD_ADD -> lineField == LocalField.LINES && lines.size in 1..8 &&
            values.map { it.field }.toSet() == foodCheckAddFields &&
            lines.all { line -> line.values.all { it.field in foodCheckSelectionFields || it.field == LocalField.FOOD_PORTION_IDS } }
        else -> false
    }

internal fun LocalPendingOperation.isFoodCheckUiPending(): Boolean = action in foodCheckUiActions && actionApproval == null &&
    recordedDecision != LocalRecordedDecision.RECORDED_ONLY

/** A typed terminal projection is still qualified against the actual saved original by the gateway. */
internal fun LocalOperationResult.isFoodCheckUiResult(action: LocalAction? = null, original: LocalCommandIntent? = null): Boolean {
    if (action != null && action !in foodCheckUiActions || original?.isFoodCheckUiIntent() == false ||
        original != null && action != null && original.action != action) return false
    return when (this) {
        is LocalOperationResult.Pending -> operation.isFoodCheckUiPending() && (action == null || operation.action == action)
        is LocalOperationResult.Unresolved -> true
        is LocalOperationResult.Recorded -> {
            val details = food ?: return false
            if (result != null || returnStock != null) return false
            if (decision == LocalRecordedDecision.REJECTED) return details.orderId == null && details.orderStorageRevision == null &&
                details.portions.isEmpty() && details.followUp == null &&
                when (action ?: original?.action) {
                    LocalAction.FOOD_CREATE_DRAFT -> details.code == "food.stock.rule.duplicate_source_allocation"
                    LocalAction.FOOD_ADD -> details.code == "food.stock.rule.revision_conflict"
                    null -> details.code in setOf("food.stock.rule.duplicate_source_allocation", "food.stock.rule.revision_conflict")
                    else -> false
                }
            if (decision != LocalRecordedDecision.APPLIED || details.orderId == null || details.orderStorageRevision == null ||
                details.followUp != LocalFoodUiQuery.Check(details.orderId)) return false
            val draft = details.code == "food.order.draft_created"
            val added = details.code == "food.order.added"
            if ((!draft && !added) || action == LocalAction.FOOD_CREATE_DRAFT && !draft || action == LocalAction.FOOD_ADD && !added ||
                draft && (details.orderStorageRevision.value != 1L || details.portions.isNotEmpty()) ||
                added && (details.orderStorageRevision.value < 2 || details.portions.size !in 1..8 || details.portions.any {
                    it.preparation != LocalFoodPreparation.NOT_SENT || it.preparationDomainRevision.value != 0L || it.originalIssueId != null
                })) return false
            if (original != null) {
                val order = original.values.singleOrNull { it.field == LocalField.ORDER_ID }?.value as? LocalScalarValue.Identifier
                if (order?.value != details.orderId) return false
                if (added) {
                    val expected = original.values.singleOrNull { it.field == LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION }
                        ?.value as? LocalScalarValue.Integer ?: return false
                    if (expected.value !in 1 until Long.MAX_VALUE || details.orderStorageRevision.value != expected.value + 1 ||
                        original.foodCheckPortionIds() != details.portions.map { it.portionId }) return false
                }
            }
            true
        }
    }
}

private fun LocalCommandIntent.foodCheckPortionIds(): List<DomainId>? {
    return try {
        lines.flatMap { line ->
            val count = (line.values.singleOrNull { it.field == LocalField.FOOD_SERVING_COUNT }?.value as? LocalScalarValue.Integer)
                ?.value ?: return null
            if (count == 1L) listOf(line.lineId) else {
                if (count !in 2L..8L) return null
                val ids = (line.values.singleOrNull { it.field == LocalField.FOOD_PORTION_IDS }?.value as? LocalScalarValue.Text)
                    ?.value?.split(',')?.map(::DomainId) ?: return null
                if (ids.size.toLong() != count || ids.firstOrNull() != line.lineId) return null
                ids
            }
        }.takeIf { it.size in 1..8 && it.distinct().size == it.size }
    } catch (_: IllegalArgumentException) { null }
}

/** Incompatible retained input stays inert with its original recovery identity preserved. */
internal fun LocalWorkspaceState.isFoodCheckUiWorkspace(): Boolean = workflow == LocalWorkflow.FOOD && search.isEmpty() &&
    reference == null && foodQuery.isFoodCheckUiQuery() &&
    form?.let { it.descriptor.isFoodCheckUiForm(foodQuery) && it.foodRecipientSource == null } != false &&
    formInput?.let { input -> input.action.isFoodCheckUiAction(input.version) && input.reservedOperationId == null &&
        input.openingQuery == null && input.foodRecipientSource == null && when (input.action) {
            LocalAction.FOOD_CREATE_DRAFT -> foodQuery is LocalFoodUiQuery.MyChecks && input.lineField == null && input.lines.isEmpty() &&
                input.scalars.keys == foodCheckDraftFields && input.values.keys.all { it in foodCheckDraftFields }
            LocalAction.FOOD_ADD -> foodQuery is LocalFoodUiQuery.Menu && foodQuery.orderId != null &&
                input.lineField == LocalField.LINES && input.lines.size in 1..8 && input.scalars.keys == foodCheckAddFields &&
                input.columns.keys == foodCheckSelectionFields && input.foodMenuSourceQueries(foodQuery) != null
            else -> false
        } } != false &&
    page?.matchesFoodCheckUiPage(foodQuery) != false && pending.all { it.isFoodCheckUiPending() } &&
    operation?.isFoodCheckUiResult() != false && referencePicker == null && openingCounts == null && priceDraft == null &&
    pricePreview == null && kitchenBoardOrigin == null && actionApproval == null && !purchasingDocuments.hasDraft &&
    foodPricePreview?.input?.let { it.action == LocalAction.FOOD_ADD && it.formVersion == 3 } != false

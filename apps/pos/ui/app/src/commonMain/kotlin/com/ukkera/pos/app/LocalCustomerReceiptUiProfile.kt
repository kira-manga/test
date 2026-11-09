package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.AppRoute

/** Presentation subset only; original evidence and current permission belong to the Master. */
internal val customerReceiptUiWorkflows: Set<LocalWorkflow> =
    setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS, LocalWorkflow.RETURNS)

internal fun customerReceiptUiRoute(route: AppRoute): Boolean =
    route == AppRoute.Cashier || route == AppRoute.Orders || route == AppRoute.SessionAccess

internal fun LocalRecordReference.isCustomerReceiptUiSelection(): Boolean = when (kind) {
    LocalRecordKind.CUSTOMER_RECEIPT -> parentId == null && revision == null
    LocalRecordKind.CUSTOMER_RECEIPT_PORTION, LocalRecordKind.CUSTOMER_RECEIPT_BATCH ->
        parentId != null && revision != null
    LocalRecordKind.CUSTOMER_RECEIPT_EVENT -> parentId != null && revision == null
    else -> false
}

/** Refuse unrelated retained editors without destroying their original recovery identity. */
internal fun LocalWorkspaceState.isCustomerReceiptUiWorkspace(): Boolean =
    workflow in customerReceiptUiWorkflows && reference?.isCustomerReceiptUiSelection() != false &&
        (workflow != LocalWorkflow.RETURNS || returnQuery.section == LocalReturnSection.RECEIPTS &&
            form == null && formInput == null && page?.forms.isNullOrEmpty()) &&
        form?.descriptor?.let { it.action == LocalAction.RETAIL_CUSTOMER_RECEIPT && it.version in 1..2 &&
            it.reservedOperationId == null && it.openingAllocations == null && it.arrivalEvidence == null } != false &&
        formInput?.let { it.action == LocalAction.RETAIL_CUSTOMER_RECEIPT && it.version in 1..2 &&
            it.reservedOperationId == null && it.openingQuery == null && it.foodRecipientSource == null &&
            (if (it.version == 1) it.lineField == null && it.lines.isEmpty() else it.lineField == LocalField.LINES) } != false &&
        page?.let { it.workflow == workflow && it.records.all { record -> record.reference.isCustomerReceiptUiSelection() } &&
            it.forms.all { descriptor -> descriptor.action == LocalAction.RETAIL_CUSTOMER_RECEIPT && descriptor.version in 1..2 } &&
            it.priceControls == null && it.branchReport == null && it.food == null && it.openingAllocations == null &&
            it.managerQuoteDetails == null && it.returnRights == null } != false &&
        pending.all { it.action == LocalAction.RETAIL_CUSTOMER_RECEIPT } &&
        (when (val outcome = operation) {
            is LocalOperationResult.Recorded -> outcome.decision in setOf(LocalRecordedDecision.RECORDED_ONLY, LocalRecordedDecision.REJECTED) &&
                outcome.food == null && outcome.returnStock == null && outcome.result?.reference?.isCustomerReceiptUiSelection() != false
            is LocalOperationResult.Pending -> outcome.operation.action == LocalAction.RETAIL_CUSTOMER_RECEIPT
            is LocalOperationResult.Unresolved, null -> true
        }) &&
        referencePicker == null && openingCounts == null && priceDraft == null && pricePreview == null &&
        foodPricePreview == null && kitchenBoardOrigin == null && actionApproval == null && !purchasingDocuments.hasDraft

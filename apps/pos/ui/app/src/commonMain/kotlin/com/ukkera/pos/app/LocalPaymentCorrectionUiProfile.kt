package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId

/** Closed presentation subset only. Every read and saved operation still needs current Master access. */
internal val paymentCorrectionUiWorkflows: Set<LocalWorkflow> = setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS)

internal fun paymentCorrectionUiRoute(route: AppRoute): Boolean =
    route == AppRoute.Cashier || route == AppRoute.Orders || route == AppRoute.SessionAccess

internal fun LocalRecordReference.isPaymentCorrectionUiSelection(): Boolean = revision == null && when (kind) {
    LocalRecordKind.PAYMENT_CORRECTION_SOURCE -> parentId == null
    LocalRecordKind.PAYMENT_CORRECTION_HISTORY -> parentId != null
    else -> false
}

private fun paymentCorrectionUiSale(search: String): DomainId? =
    if (!search.startsWith("payment-correction:")) null else try {
        DomainId(search.removePrefix("payment-correction:"))
    } catch (_: IllegalArgumentException) { null }

internal fun LocalWorkflowQuery.isPaymentCorrectionUiQuery(): Boolean {
    if (workflow !in paymentCorrectionUiWorkflows || pricePreview != null || branchReport != null || food != null ||
        purchasing != null || returns != null || cash != null || inventory != null || openingAllocations != null) return false
    val selected = reference
    if (selected != null && !selected.isPaymentCorrectionUiSelection()) return false
    if (cursor != null && selected?.kind != LocalRecordKind.PAYMENT_CORRECTION_HISTORY) return false
    if (selected != null) return search.isEmpty()
    if (search.isEmpty()) return true
    return paymentCorrectionUiSale(search) != null
}

private val paymentCorrectionUiFields: Set<LocalField> = setOf(LocalField.SALE_ID, LocalField.PAYMENT_ID,
    LocalField.EXPECTED_SESSION_REVISION, LocalField.AMOUNT, LocalField.PAYMENT_METHOD, LocalField.TENDER_ID,
    LocalField.EXPECTED_REVISION, LocalField.PAYMENT_TERMINAL, LocalField.EXTERNAL_REFERENCE,
    LocalField.EVIDENCE_ID, LocalField.OCCURRED_AT, LocalField.REASON)

internal fun LocalActionForm.matchesPaymentCorrectionUiForm(selection: LocalRecordReference?): Boolean {
    if (action != LocalAction.PAYMENT_CLASSIFICATION_CORRECT || version != 1 || selection == null ||
        selection.kind != LocalRecordKind.PAYMENT_CORRECTION_HISTORY || !selection.isPaymentCorrectionUiSelection() ||
        reservedOperationId != null || openingAllocations != null || arrivalEvidence != null || confirmation == null ||
        fields.any { it !is LocalFormField.Scalar }) return false
    val scalars = fields.filterIsInstance<LocalFormField.Scalar>()
    if (scalars.map { it.field }.toSet() - LocalField.SOURCE_ID != paymentCorrectionUiFields ||
        scalars.any { it.referenceSource != null || it.references.isNotEmpty() ||
            it.kind in setOf(LocalInputKind.REFERENCE, LocalInputKind.MULTI_REFERENCE) }) return false
    fun originalId(field: LocalField, value: DomainId?): Boolean = scalars.singleOrNull { it.field == field }?.let {
        it.readOnly && (it.initialValue as? LocalScalarValue.Identifier)?.value == value
    } == true
    return originalId(LocalField.SALE_ID, selection.parentId) && originalId(LocalField.PAYMENT_ID, selection.id) &&
        scalars.singleOrNull { it.field == LocalField.EXPECTED_SESSION_REVISION }?.let {
            it.readOnly && (it.initialValue as? LocalScalarValue.Integer)?.value?.let { value -> value >= 0 } == true
        } == true && scalars.singleOrNull { it.field == LocalField.AMOUNT }?.let {
            it.readOnly && it.initialValue is LocalScalarValue.Decimal
        } == true && scalars.singleOrNull { it.field == LocalField.SOURCE_ID }?.let {
            it.readOnly && it.initialValue is LocalScalarValue.Identifier
        } != false
}

internal fun LocalWorkflowPage.matchesPaymentCorrectionUiPage(query: LocalWorkflowQuery): Boolean {
    if (!query.isPaymentCorrectionUiQuery() || workflow != query.workflow || priceControls != null ||
        branchReport != null || food != null || openingAllocations != null || managerQuoteDetails != null || returnRights != null) return false
    if (records.isEmpty()) return forms.isEmpty() && nextCursor == null
    val selected = query.reference ?: paymentCorrectionUiSale(query.search)?.let {
        LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_SOURCE, it)
    } ?: return false
    val close = records.filter { it.reference.kind == LocalRecordKind.CASH_SESSION }
    if (close.size != 1 || close.single().let { it.selectable || it.reference.parentId != null || it.reference.revision != null }) return false
    val payments = records.filter { it.reference.kind == LocalRecordKind.PAYMENT_CORRECTION_HISTORY }
    val corrections = records.filter { it.reference.kind == LocalRecordKind.PAYMENT_CORRECTION }
    if (close.size + payments.size + corrections.size != records.size || records.any { it.supplierId != null } ||
        payments.any { !it.selectable || !it.reference.isPaymentCorrectionUiSelection() } ||
        corrections.any { it.selectable || it.reference.parentId != selected.id || it.reference.revision == null }) return false
    return when (selected.kind) {
        LocalRecordKind.PAYMENT_CORRECTION_SOURCE -> payments.size in 1..64 && payments.all { it.reference.parentId == selected.id } &&
            corrections.isEmpty() && forms.isEmpty() && nextCursor == null
        LocalRecordKind.PAYMENT_CORRECTION_HISTORY -> payments.singleOrNull()?.reference == selected && corrections.size <= 5 &&
            forms.size <= 1 && forms.all { query.cursor == null && it.matchesPaymentCorrectionUiForm(selected) }
        else -> false
    }
}

internal fun LocalOperationResult.isPaymentCorrectionUiResult(): Boolean = when (this) {
    is LocalOperationResult.Pending -> operation.action == LocalAction.PAYMENT_CLASSIFICATION_CORRECT
    is LocalOperationResult.Unresolved -> true
    is LocalOperationResult.Recorded -> food == null && returnStock == null && when (decision) {
        LocalRecordedDecision.APPLIED -> result?.let {
            it.selectable && it.reference.kind == LocalRecordKind.PAYMENT_CORRECTION_HISTORY && it.reference.isPaymentCorrectionUiSelection()
        } == true
        LocalRecordedDecision.REJECTED -> result == null
        LocalRecordedDecision.RECORDED_ONLY -> false
    }
}

internal fun LocalWorkspaceState.isPaymentCorrectionUiWorkspace(): Boolean = workflow in paymentCorrectionUiWorkflows &&
    reference?.isPaymentCorrectionUiSelection() != false &&
    form?.let { it.lines.isEmpty() && it.descriptor.matchesPaymentCorrectionUiForm(reference) } != false &&
    formInput?.let { it.action == LocalAction.PAYMENT_CLASSIFICATION_CORRECT && it.version == 1 &&
        it.lineField == null && it.lines.isEmpty() && it.openingQuery == null &&
        it.values.keys.all { field -> field in paymentCorrectionUiFields || field == LocalField.SOURCE_ID } } != false &&
    page?.matchesPaymentCorrectionUiPage(LocalWorkflowQuery(workflow, search, reference)) != false &&
    pending.all { it.action == LocalAction.PAYMENT_CLASSIFICATION_CORRECT } && operation?.isPaymentCorrectionUiResult() != false &&
    referencePicker == null && openingCounts == null && priceDraft == null && pricePreview == null &&
    foodPricePreview == null && kitchenBoardOrigin == null && actionApproval == null && !purchasingDocuments.hasDraft

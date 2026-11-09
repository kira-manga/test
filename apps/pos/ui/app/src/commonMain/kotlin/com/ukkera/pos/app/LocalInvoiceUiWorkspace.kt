package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId

/** Shared invoice/PO read presentation. The two command surfaces remain disjoint. */
internal val invoiceUiSections: List<LocalPurchasingSection> =
    listOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS)

internal val invoiceCaptureUiSections: List<LocalPurchasingSection> = invoiceUiSections

internal fun LocalSessionWorkspaceMode.invoiceUiAction(): LocalAction? = when (this) {
    LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY -> LocalAction.SUPPLIER_INVOICE_CAPTURE
    LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY -> LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE
    else -> null
}

internal fun LocalRecordReference.isInvoiceUiSelection(section: LocalPurchasingSection): Boolean {
    val expected = when (section) {
        LocalPurchasingSection.INVOICES -> LocalRecordKind.PURCHASE_INVOICE
        LocalPurchasingSection.ORDERS -> LocalRecordKind.PURCHASE
        else -> return false
    }
    return parentId == null && revision == null && kind == expected
}

private fun LocalRecordReference.isInvoiceMatchLine(invoiceId: DomainId): Boolean =
    kind == LocalRecordKind.PURCHASE_LINE && parentId == invoiceId && revision == null

private fun LocalScalarValue.isInvoiceMatchValue(invoiceId: DomainId): Boolean = when (this) {
    is LocalScalarValue.Reference -> value.isInvoiceMatchLine(invoiceId)
    is LocalScalarValue.References -> values.all { it.isInvoiceMatchLine(invoiceId) }
    else -> true
}

internal fun LocalActionForm.isInvoiceUiForm(action: LocalAction, reference: LocalRecordReference?): Boolean {
    if (this.action != action || version != 1 || openingAllocations != null) return false
    return when (action) {
        LocalAction.SUPPLIER_INVOICE_CAPTURE -> true
        LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ->
            reference != null && reference.isInvoiceUiSelection(LocalPurchasingSection.INVOICES) && arrivalEvidence == null &&
                fields.all { field -> field is LocalFormField.Scalar && field.referenceSource == null &&
                    field.references.all { it.reference.isInvoiceMatchLine(reference.id) } &&
                    field.initialValue?.isInvoiceMatchValue(reference.id) != false }
        else -> false
    }
}

internal fun LocalFormDraft.isInvoiceUiDraft(action: LocalAction, reference: LocalRecordReference?): Boolean =
    descriptor.isInvoiceUiForm(action, reference) &&
        (action != LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE || lines.isEmpty() && foodRecipientSource == null &&
            reference != null && values.values.all { it.selected?.isInvoiceMatchValue(reference.id) != false })

internal fun LocalFormInputContinuation.isInvoiceUiInput(action: LocalAction, reference: LocalRecordReference?): Boolean =
    this.action == action && version == 1 &&
        (action != LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ||
            reference != null && reference.isInvoiceUiSelection(LocalPurchasingSection.INVOICES) && lineField == null &&
            columns.isEmpty() && minimumLines == null && maximumLines == null && allowBlankLines == null &&
            allowDuplicateLines == null && openingQuery == null && sourceLineIds.isEmpty() && lines.isEmpty() &&
            foodRecipientSource == null && values.values.all { it.selected?.isInvoiceMatchValue(reference.id) != false })

/** Original input shape only. A fresh source read and current Master authority are separate. */
internal fun LocalCommandIntent.isInvoiceUiIntent(action: LocalAction): Boolean =
    this.action == action && formVersion == 1 && arrivalEvidence == null && when (action) {
        LocalAction.SUPPLIER_INVOICE_CAPTURE ->
            reference?.isInvoiceUiSelection(LocalPurchasingSection.ORDERS) != false && lineField == LocalField.LINES
        LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ->
            reference != null && reference.isInvoiceUiSelection(LocalPurchasingSection.INVOICES) && lineField == null && lines.isEmpty() &&
                values.all { it.value.isInvoiceMatchValue(reference.id) }
        else -> false
    }

internal fun LocalWorkflowPage.isInvoiceUiPage(section: LocalPurchasingSection, action: LocalAction,
    reference: LocalRecordReference?): Boolean =
    workflow == LocalWorkflow.PURCHASING && section in invoiceUiSections &&
        forms.all { it.isInvoiceUiForm(action, reference) } &&
        records.all { record -> record.reference.isInvoiceUiSelection(section) || !record.selectable &&
            record.reference.kind in setOf(LocalRecordKind.PURCHASE_LINE, LocalRecordKind.PURCHASE_CHARGE,
                LocalRecordKind.PURCHASE_MATCH, LocalRecordKind.SUPPLIER_CREDIT, LocalRecordKind.SUPPLIER_SETTLEMENT_ALLOCATION) } &&
        priceControls == null && food == null && returnRights == null && branchReport == null &&
        openingAllocations == null && managerQuoteDetails == null

internal fun LocalPendingOperation.isInvoiceUiPending(action: LocalAction): Boolean =
    this.action == action && actionApproval == null

internal fun LocalOperationResult.isInvoiceUiResult(action: LocalAction, requireOriginalAction: Boolean = false): Boolean = when (this) {
    is LocalOperationResult.Pending -> operation.isInvoiceUiPending(action)
    is LocalOperationResult.Recorded -> food == null && returnStock == null &&
        (if (requireOriginalAction) originalAction == action else originalAction?.let { it == action } != false) &&
        result?.reference?.isInvoiceUiSelection(LocalPurchasingSection.INVOICES) != false
    is LocalOperationResult.Unresolved -> true
}

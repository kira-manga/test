package com.ukkera.pos.catalog.store

import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Closed canonical outcomes, not a namespace-prefix accepting result classifier. */
public object LocalCapturedSupplierInvoiceMatchCodesV2 {
    public const val SUCCESS: String = "purchasing.captured_invoice_matched_recognized"
    public const val SOURCE_READY: String = "purchasing.captured_match_source_ready"
    public val REFUSALS: Set<String> = setOf(
        "purchasing.original_required", "purchasing.original_revision_conflict", "purchasing.original_association_mismatch",
        "purchasing.identity_already_used", "purchasing.stale_revision", "purchasing.order_required",
        "purchasing.order_line_required", "purchasing.invoice_line_required", "purchasing.order_association_conflict",
        "purchasing.allocation_association_conflict", "purchasing.multi_order_association_unsupported",
        "purchasing.variance_requires_approval", "purchasing.receipt_required", "purchasing.receipt_source_mismatch",
        "purchasing.accepted_receipt_required", "purchasing.capacity_required", "purchasing.capacity_unavailable",
        "purchasing.quantity_unit_mismatch", "purchasing.remaining_quantity_exceeded", "purchasing.whole_serial_required",
        "purchasing.receipt_cost_variance", "purchasing.rounding_variance",
    )
}

/** A deliberate three-way selection. All versions are actual storage versions, not domain counters.
 * Null first progress/receipt-billing/payable versions mean claim-checked absence, never zero.
 * No supplied amount or documentary reference grants matching or recognition authority. */
@Serializable
public data class LocalMatchCapturedSupplierInvoiceV2(
    public val matchId: DomainId, public val invoiceId: DomainId, public val invoiceLineId: DomainId,
    public val expectedInvoiceRevision: Revision,
    public val originalDocumentId: DomainId, public val expectedOriginalRevision: Revision,
    public val orderId: DomainId, public val expectedOrderRevision: Revision, public val allocationId: DomainId,
    public val receiptId: DomainId, public val receiptLineId: DomainId, public val expectedReceiptRevision: Revision,
    public val expectedProgressRevision: Revision?, public val expectedOrderMatchRevision: Revision,
    public val expectedOrderBillingRevision: Revision, public val expectedReceiptBillingRevision: Revision?,
    public val expectedPayableRevision: Revision?, public val quantity: Quantity, public val reasonCode: String,
) {
    init {
        require(quantity.scaled > 0)
        require(listOfNotNull(expectedInvoiceRevision, expectedOriginalRevision, expectedOrderRevision,
            expectedReceiptRevision, expectedProgressRevision, expectedOrderMatchRevision, expectedOrderBillingRevision,
            expectedReceiptBillingRevision, expectedPayableRevision).all { it.value >= 1 })
        purchasingReason(reasonCode)
    }
}

/** Protected quoting of the explicit sources; the returned heads reserve nothing. */
@Serializable
public data class LocalCapturedSupplierInvoiceMatchSourceQueryV2(
    public val invoiceId: DomainId, public val invoiceLineId: DomainId, public val expectedInvoiceRevision: Revision,
    public val originalDocumentId: DomainId, public val expectedOriginalRevision: Revision,
    public val orderId: DomainId, public val expectedOrderRevision: Revision, public val allocationId: DomainId,
    public val receiptId: DomainId, public val receiptLineId: DomainId, public val expectedReceiptRevision: Revision,
    public val quantity: Quantity,
) {
    init {
        require(quantity.scaled > 0 && listOf(expectedInvoiceRevision, expectedOriginalRevision,
            expectedOrderRevision, expectedReceiptRevision).all { it.value >= 1 })
    }
}

@Serializable
public data class LocalCapturedSupplierInvoiceMatchSourceV2(
    public val query: LocalCapturedSupplierInvoiceMatchSourceQueryV2,
    public val code: String, public val availability: LocalPurchasingReadAvailabilityV1,
    public val sourcePresent: Boolean?, public val sourceAndTermsCompatible: Boolean?,
    public val invoiceRevision: Revision?, public val originalRevision: Revision?, public val orderRevision: Revision?,
    public val receiptRevision: Revision?, public val progressRevision: Revision?, public val orderMatchRevision: Revision?,
    public val orderBillingRevision: Revision?, public val receiptBillingRevision: Revision?, public val payableRevision: Revision?,
    public val sourceId: DomainId?, public val invoiceRemaining: Quantity?, public val orderRemaining: Quantity?,
    public val acceptedReceiptRemaining: Quantity?, public val originalInvoiceClaim: Money?,
    public val incrementNet: Money?, public val incrementTax: Money?, public val recognizedPayable: Money?,
) {
    init {
        if (availability == LocalPurchasingReadAvailabilityV1.AVAILABLE) require(sourcePresent != null && sourceAndTermsCompatible != null)
        else require(sourcePresent == null && sourceAndTermsCompatible == null && invoiceRevision == null && originalRevision == null &&
            orderRevision == null && receiptRevision == null && progressRevision == null && orderMatchRevision == null &&
            orderBillingRevision == null && receiptBillingRevision == null && payableRevision == null && sourceId == null &&
            invoiceRemaining == null && orderRemaining == null && acceptedReceiptRemaining == null && originalInvoiceClaim == null &&
            incrementNet == null && incrementTax == null && recognizedPayable == null)
    }
}

/** Complete original claim is produced by the Master for protected original recovery, never client authority. */
@Serializable
public data class LocalCapturedSupplierInvoiceMatchResultV2(
    public val code: String, public val id: DomainId, public val invoiceId: DomainId,
    public val invoiceRevision: Revision, public val originalInvoiceClaim: Money,
    public val revision: Revision?, public val progressRevision: Revision?, public val payableRevision: Revision?,
    public val quantity: Quantity?, public val net: Money?, public val tax: Money?,
) {
    init {
        require(invoiceRevision.value >= 1 && originalInvoiceClaim.currency == Currency.EGP && originalInvoiceClaim.minor >= 0)
        require(code == LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS || code in LocalCapturedSupplierInvoiceMatchCodesV2.REFUSALS)
        require((code == LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS) == (revision != null))
        require((revision == null) == (progressRevision == null) && (revision == null) == (payableRevision == null) &&
            (revision == null) == (quantity == null) && (revision == null) == (net == null) && (revision == null) == (tax == null))
    }
}

@Serializable
public data class LocalCapturedSupplierInvoiceRecognitionLineV2(
    public val lineId: DomainId, public val allocationId: DomainId?, public val matchedQuantity: Quantity,
    public val matchedNet: Money?, public val matchedTax: Money?,
)

/** Current recognition is separate from the unchanged original documentary capture and its original status. */
@Serializable
public data class LocalCapturedSupplierInvoiceRecognitionV2(
    public val invoiceId: DomainId, public val invoiceRevision: Revision, public val status: String,
    public val originalDocumentId: DomainId, public val originalRevision: Revision,
    public val orderId: DomainId, public val orderRevision: Revision, public val progressRevision: Revision,
    public val payableRevision: Revision?, public val lines: List<LocalCapturedSupplierInvoiceRecognitionLineV2>,
    public val settlementEligible: Boolean,
    public val recognizedPayable: Money?, public val unmatchedClaim: Money?, public val remainingDue: Money?,
    public val appliedCredit: Money?, public val allocatedPayment: Money?, public val reservedSupplierCash: Money?,
    public val availableToAllocate: Money?,
)

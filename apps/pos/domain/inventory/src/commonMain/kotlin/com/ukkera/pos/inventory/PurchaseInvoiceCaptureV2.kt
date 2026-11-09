package com.ukkera.pos.inventory

import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** An explicitly requested local original commitment, never a supplier's free-text reference. */
@Serializable
public data class PurchaseInvoiceOrderLinkV2(public val orderId: DomainId, public val revision: Revision) {
    init { require(revision.value >= 1) }
}

/** Selection records the employee's deliberate choice; it grants no receiving or matching rights. */
@Serializable
public enum class PurchaseInvoiceEntryProvenanceV2 { DOCUMENTARY, SELECTED_ORDER_LINE }

/**
 * The actual supplied terms are immutable documentary claims. An optional internal allocation
 * identifies an original PO line as context, not a receipt allocation or consumption of it.
 * A literal supplier allocation reference is retained independently, including UUID-looking text.
 */
@Serializable
public data class PurchaseInvoiceCaptureLineV2(
    public val lineId: DomainId,
    public val goods: PurchaseGoodsV1,
    public val provenance: PurchaseInvoiceEntryProvenanceV2,
    public val internalAllocationId: DomainId? = null,
    public val supplierAllocationReference: String? = null,
) {
    init {
        require(provenance != PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE || internalAllocationId != null)
        supplierAllocationReference?.let(::purchaseInvoiceLiteralReferenceV2)
    }
}

/**
 * Additive capture-only invoice. Missing PO/allocation links are explicit; none is manufactured.
 * The command qualifies supplied SKU/product/unit identities and precision at the local Master.
 * The claimed conversion, price and amounts remain the supplier's original terms, not catalog
 * authority, an accepted substitution, recognized expense, receipt, variance approval or payment.
 * sourceEvidenceId is an employee-supplied documentary reference, not attachment qualification.
 */
@Serializable
public data class PurchaseInvoiceCaptureV2(
    public val id: DomainId,
    public val supplierId: DomainId,
    public val reference: String,
    public val invoiceDate: BusinessDate,
    public val dueDate: BusinessDate,
    public val sourceEvidenceId: DomainId,
    public val lines: List<PurchaseInvoiceCaptureLineV2>,
    public val charges: List<PurchaseChargeV1>,
    public val internalOrderLink: PurchaseInvoiceOrderLinkV2? = null,
    public val supplierOrderReference: String? = null,
) {
    init {
        // Retain the same declared local invoice-reference normalization as the original command.
        normalizedPurchaseReference(reference)
        require(lines.size in 1..16 && lines.map { it.lineId }.distinct().size == lines.size)
        require(charges.size <= 8 && charges.map { it.id }.distinct().size == charges.size)
        require(internalOrderLink != null || lines.all { it.internalAllocationId == null })
        supplierOrderReference?.let(::purchaseInvoiceLiteralReferenceV2)
        total()
    }

    public fun total(): Money = (lines.map { it.goods.total() } + charges.map { it.total() })
        .fold(purchaseZero(), Money::plus)
}

/** Literal documentary strings have no lookup/foreign-key or Unicode normalization semantics. */
private fun purchaseInvoiceLiteralReferenceV2(value: String) {
    require(value.length in 1..96 && value.isNotBlank() && value.none { it.code < 0x20 || it.code == 0x7f })
}

/** Missing association and changed terms are visible; a valid link alone never approves them. */
public fun purchaseInvoiceCaptureDiscrepanciesV2(
    order: PurchaseOrderV1?, invoice: PurchaseInvoiceCaptureV2,
): Set<PurchaseDiscrepancyV1> = buildSet {
    if (invoice.charges.isNotEmpty()) add(PurchaseDiscrepancyV1.CHARGE_MATCH_REQUIRED)
    for (line in invoice.lines) {
        val original = line.internalAllocationId?.let { id -> order?.lines?.singleOrNull { it.allocationId == id } }
        if (original == null) add(PurchaseDiscrepancyV1.ORDER_LINE_REQUIRED)
        else addAll(purchaseLineDiscrepancies(original.goods, line.goods))
        if (!line.goods.amountsAgree()) add(PurchaseDiscrepancyV1.AMOUNT_VARIANCE)
    }
    for (original in order?.lines.orEmpty()) {
        val matching = invoice.lines.filter {
            it.internalAllocationId == original.allocationId && it.goods.units == original.goods.units
        }
        val maximum = original.goods.baseQuantity()
        var quantity = maximum.copy(scaled = 0)
        for (line in matching) {
            val billed = line.goods.baseQuantity()
            if (billed > maximum - quantity) { add(PurchaseDiscrepancyV1.QUANTITY_VARIANCE); break }
            quantity += billed
        }
    }
}

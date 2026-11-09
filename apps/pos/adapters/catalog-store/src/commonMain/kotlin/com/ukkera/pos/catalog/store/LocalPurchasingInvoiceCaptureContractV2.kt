package com.ukkera.pos.catalog.store

import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** Original explicit capture input. Optional internal links and literal references are distinct. */
@Serializable
public data class LocalCaptureSupplierInvoiceV2(
    public val invoice: PurchaseInvoiceCaptureV2,
    public val expectedSupplierRevision: Revision,
    public val reasonCode: String,
) { init { purchasingReason(reasonCode); require(expectedSupplierRevision.value >= 1) } }

/** Capture creates only the original INVOICE row. There is no matching-progress revision. */
@Serializable
public data class LocalInvoiceCaptureResultV2(
    public val code: String, public val id: DomainId, public val revision: Revision?,
)

/** Original selected commitment snapshot, kept separate from actual supplier goods. */
@Serializable
public data class LocalPurchasingInvoiceOriginalLineV2(
    public val allocationId: DomainId, public val skuId: DomainId, public val warehouseId: DomainId,
    public val units: PurchaseUnitsV1, public val quantity: Quantity, public val baseQuantity: Quantity,
    public val unitPrice: UnitPrice?, public val discount: PercentageRate?, public val purchaseTax: PurchaseTaxV1?,
    public val net: Money?, public val taxAmount: Money?,
)

/** Operational documentary facts are protected; financial fields require current cost access. */
@Serializable
public data class LocalPurchasingInvoiceCaptureLineViewV2(
    public val lineId: DomainId,
    public val provenance: PurchaseInvoiceEntryProvenanceV2,
    public val internalAllocationId: DomainId?, public val supplierAllocationReference: String?,
    public val skuId: DomainId, public val supplierItemReference: String,
    public val units: PurchaseUnitsV1, public val originalQuantity: Quantity, public val baseQuantity: Quantity,
    public val originalOrderLine: LocalPurchasingInvoiceOriginalLineV2?,
    public val unitPrice: UnitPrice?, public val discount: PercentageRate?, public val purchaseTax: PurchaseTaxV1?,
    public val claimedNet: Money?, public val claimedTax: Money?,
)

/** A source-backed recorded claim. UNMATCHED_UNAPPROVED never means recognized or payable. */
@Serializable
public data class LocalPurchasingInvoiceCaptureViewV2(
    public val id: DomainId, public val revision: Revision, public val supplierId: DomainId,
    public val status: String, public val date: BusinessDate, public val dueDate: BusinessDate,
    public val internalOrderLink: PurchaseInvoiceOrderLinkV2?,
    public val reference: String, public val supplierOrderReference: String?, public val sourceEvidenceId: DomainId,
    public val operationId: OperationId, public val originalActorId: DomainId, public val recordedAt: UtcInstant,
    public val policyRevision: Revision,
    public val lines: List<LocalPurchasingInvoiceCaptureLineViewV2>, public val discrepancies: List<PurchaseDiscrepancyV1>,
    public val charges: List<PurchaseChargeV1>?, public val supplierClaim: Money?,
)

/** Exactly one genuine format, with no conversion of capture data into invented legacy fields. */
@Serializable
public data class LocalPurchasingInvoiceDocumentV2(
    public val legacy: LocalPurchasingDocumentViewV1? = null,
    public val capture: LocalPurchasingInvoiceCaptureViewV2? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val recognition: LocalCapturedSupplierInvoiceRecognitionV2? = null,
) { init { require((legacy == null) != (capture == null) && (recognition == null || capture != null)) } }

/** READ_INVOICES accepts the unchanged query shape, constrained to section INVOICE. */
@Serializable
public data class LocalPurchasingInvoicePageV2(
    public val documents: List<LocalPurchasingInvoiceDocumentV2>?, public val nextAfter: DomainId?,
    public val includesCosts: Boolean,
    public val availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.AVAILABLE,
    public val smallerPageLimit: Int? = null,
) {
    init {
        if (availability == LocalPurchasingReadAvailabilityV1.AVAILABLE) require(documents != null && smallerPageLimit == null)
        else require(documents == null && nextAfter == null)
        require(smallerPageLimit == null || smallerPageLimit in 1..4)
    }
}

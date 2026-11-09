@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public object LocalPurchasingProfile {
    public val APPROVE_ORDER: CommandKey = CommandKey("local.purchasing.order.approve", 1)
    public val RECORD_INVOICE: CommandKey = CommandKey("local.purchasing.invoice.record", 1)
    public val CAPTURE_INVOICE: CommandKey = CommandKey("local.purchasing.invoice.record", 2)
    public val MATCH_INVOICE: CommandKey = CommandKey("local.purchasing.invoice.match", 1)
    /** Separate identity: never widen the original compatible-terms Match semantics. */
    public val MATCH_UNCONTESTED_INVOICE: CommandKey = CommandKey("local.purchasing.invoice.match-uncontested", 1)
    public val MATCH_CAPTURED_INVOICE: CommandKey = CommandKey("local.purchasing.invoice.match-recognize", 2)
    public val RECORD_CREDIT: CommandKey = CommandKey("local.purchasing.credit.record", 1)
    public val READ: ReadKey = ReadKey("local.purchasing.read", 1)
    public val READ_INVOICES: ReadKey = ReadKey("local.purchasing.invoice.read", 2)
    public val READ_MATCH_SOURCE: ReadKey = ReadKey("local.purchasing.match-source", 1)
    public val READ_UNCONTESTED_MATCH_SOURCE: ReadKey = ReadKey("local.purchasing.match-uncontested-source", 1)
    public val READ_CAPTURED_MATCH_SOURCE: ReadKey = ReadKey("local.purchasing.invoice.match-recognize-source", 2)
    public val READ_CREDIT_SOURCE: ReadKey = ReadKey("local.purchasing.credit-source", 1)
    public const val ORDER: String = "local.purchasing.order"
    public const val INVOICE: String = "local.purchasing.invoice"
    public const val PROGRESS: String = "local.purchasing.invoice-progress"
    public const val ORDER_BILLED: String = "local.purchasing.order-billed"
    public const val ORDER_MATCHES: String = "local.purchasing.order-matches"
    public const val RECEIPT_BILLED: String = "local.purchasing.receipt-billed"
    public const val MATCH: String = "local.purchasing.match"
    public const val PAYABLE: String = "local.purchasing.payable"
    public const val CREDIT: String = "local.purchasing.credit"
    public const val INVOICE_CREDITS: String = "local.purchasing.invoice-credits"
    public const val CREDIT_SOURCE_CLAIM: String = "local.purchasing.credit-source"
    public const val DOCUMENT_CLAIM: String = "local.purchasing.document"
    public const val REFERENCE_CLAIM: String = "local.purchasing.reference"
    public const val MAX_MATCHES: Int = 64
    public const val MAX_CREDITS: Int = 64
    public fun schema(kind: String): String = "$kind/v1"
}

@Serializable
public data class LocalApprovePurchaseOrderV1(
    public val order: PurchaseOrderV1, public val expectedSupplierRevision: Revision,
    public val reasonCode: String,
) { init { purchasingReason(reasonCode) } }

@Serializable
public data class LocalRecordSupplierInvoiceV1(
    public val invoice: PurchaseInvoiceV1, public val expectedOrderRevision: Revision,
    public val expectedSupplierRevision: Revision, public val reasonCode: String,
) { init { purchasingReason(reasonCode) } }

/** Revisions are STORAGE revisions; a missing receipt-billing/payable row is explicitly null. */
@Serializable
public data class LocalMatchSupplierInvoiceV1(
    public val matchId: DomainId, public val invoiceId: DomainId, public val invoiceLineId: DomainId,
    public val expectedInvoiceRevision: Revision, public val expectedProgressRevision: Revision,
    public val expectedOrderMatchRevision: Revision,
    public val expectedOrderBillingRevision: Revision, public val expectedPayableRevision: Revision?,
    public val receiptId: DomainId, public val receiptLineId: DomainId, public val expectedReceiptRevision: Revision,
    public val expectedReceiptBillingRevision: Revision?, public val quantity: Quantity, public val reasonCode: String,
) { init { require(quantity.scaled > 0); purchasingReason(reasonCode) } }

/** Explicit review of the claimed slice versus the original PO recognition; no uplift approval. */
@Serializable
public data class LocalMatchUncontestedSupplierInvoiceV1(
    public val match: LocalMatchSupplierInvoiceV1, public val amounts: PurchaseUncontestedAmountsV1,
)

@Serializable
public data class LocalPurchasingResultV1(
    public val code: String, public val id: DomainId, public val revision: Revision?, public val progressRevision: Revision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val creditRevision: Revision? = null,
)

@Serializable
public enum class LocalPurchasingSectionV1 { ORDER, INVOICE, PAYABLE }

@Serializable
public enum class LocalPurchasingReadAvailabilityV1 { AVAILABLE, HYDRATION_CAPACITY_UNAVAILABLE, UNSUPPORTED_INVOICE_CAPTURE, SOURCE_UNAVAILABLE }

@Serializable
public data class LocalPurchasingQueryV1(
    public val section: LocalPurchasingSectionV1, public val id: DomainId? = null,
    public val after: DomainId? = null, public val limit: Int = 5,
    public val includeCosts: Boolean = false, public val export: Boolean = false,
) {
    init { require(limit in 1..5 && (id == null || after == null)); require(section != LocalPurchasingSectionV1.PAYABLE || includeCosts) }
}

/** Typed operational facts; no source envelopes, employee credentials or reusable authority. */
@Serializable
public data class LocalPurchasingLineViewV1(
    public val lineId: DomainId, public val allocationId: DomainId, public val skuId: DomainId,
    public val orderBillingRevision: Revision?,
    public val warehouseId: DomainId?, public val units: PurchaseUnitsV1, public val originalQuantity: Quantity,
    public val baseQuantity: Quantity, public val matchedQuantity: Quantity,
    public val receivedCommitted: Quantity?, public val receivedAccepted: Quantity?, public val inspectionPending: Quantity?,
    public val unitPrice: UnitPrice?, public val discount: PercentageRate?, public val purchaseTax: PurchaseTaxV1?,
    public val claimedNet: Money?, public val claimedTax: Money?, public val matchedNet: Money?, public val matchedTax: Money?,
    /** Claimed money for already allocated goods, not an approved/payable amount. Costs permission required. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val disputedPriceClaim: Money? = null,
)

@Serializable
public data class LocalPurchasingDocumentViewV1(
    public val id: DomainId, public val revision: Revision, public val supplierId: DomainId,
    public val orderId: DomainId, public val status: String, public val date: BusinessDate, public val dueDate: BusinessDate?,
    public val progressRevision: Revision?, public val payableRevision: Revision?,
    public val lines: List<LocalPurchasingLineViewV1>, public val discrepancies: List<PurchaseDiscrepancyV1>,
    public val reference: String?, public val sourceEvidenceId: DomainId?, public val paymentTerms: String?,
    public val charges: List<PurchaseChargeV1>?, public val supplierClaim: Money?,
    public val matchedPayable: Money?, public val unmatchedClaim: Money?, public val remainingDue: Money?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val disputedPriceClaim: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val creditRevision: Revision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val appliedCredit: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val credits: List<LocalPurchasingCreditViewV1>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val settlementRevision: Revision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val allocatedPayment: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val reservedSupplierCash: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val availableToAllocate: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val settlementStatus: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val settlementAllocations: List<LocalSupplierAllocationViewV1>? = null,
    /** True only for shared payable views backed by an actual captured v2 invoice. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val capturedInvoice: Boolean = false,
)

@Serializable
public data class LocalPurchasingPageV1(
    public val section: LocalPurchasingSectionV1, public val documents: List<LocalPurchasingDocumentViewV1>?,
    public val nextAfter: DomainId?, public val includesCosts: Boolean,
    public val availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.AVAILABLE,
    /** Advice only, never a promise that a smaller page fits. Null for exact-ID/limit-one reads. */
    public val smallerPageLimit: Int? = null,
) {
    init {
        if (availability == LocalPurchasingReadAvailabilityV1.AVAILABLE) require(documents != null && smallerPageLimit == null)
        else require(documents == null && nextAfter == null)
        require(smallerPageLimit == null || smallerPageLimit in 1..4)
    }
}

@Serializable
public data class LocalPurchasingMatchSourceQueryV1(
    public val invoiceId: DomainId, public val invoiceLineId: DomainId, public val receiptId: DomainId, public val receiptLineId: DomainId,
)

/** Input versions/capacities for the selected current source, NOT a reservation or an approval. */
@Serializable
public data class LocalPurchasingMatchSourceV1(
    public val query: LocalPurchasingMatchSourceQueryV1, public val sourcePresent: Boolean?,
    public val invoiceRevision: Revision?, public val progressRevision: Revision?, public val orderBillingRevision: Revision?,
    public val payableRevision: Revision?, public val receiptRevision: Revision?, public val receiptBillingRevision: Revision?,
    public val sourceId: DomainId?, public val invoiceRemaining: Quantity?, public val orderRemaining: Quantity?,
    public val acceptedReceiptRemaining: Quantity?, public val sourceAndTermsCompatible: Boolean?,
    public val orderMatchRevision: Revision?,
    public val availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.AVAILABLE,
) {
    init {
        if (availability == LocalPurchasingReadAvailabilityV1.AVAILABLE) require(sourcePresent != null && sourceAndTermsCompatible != null)
        else require(sourcePresent == null && sourceAndTermsCompatible == null && invoiceRevision == null && progressRevision == null &&
            orderBillingRevision == null && payableRevision == null && receiptRevision == null && receiptBillingRevision == null &&
            sourceId == null && invoiceRemaining == null && orderRemaining == null && acceptedReceiptRemaining == null && orderMatchRevision == null)
    }
}

/** The requested quantity is quoted at the same authorized source cut; it reserves nothing. */
@Serializable
public data class LocalPurchasingUncontestedMatchSourceQueryV1(
    public val source: LocalPurchasingMatchSourceQueryV1, public val quantity: Quantity,
) { init { require(quantity.scaled > 0) } }

/** Null amounts are an explicit unavailable/incompatible/insufficient cut, never zero approval. */
@Serializable
public data class LocalPurchasingUncontestedMatchSourceV1(
    public val query: LocalPurchasingUncontestedMatchSourceQueryV1,
    public val source: LocalPurchasingMatchSourceV1, public val amounts: PurchaseUncontestedAmountsV1?,
) {
    init {
        require(source.query == query.source)
        require(amounts == null || source.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE &&
            source.sourcePresent == true && source.sourceAndTermsCompatible == true)
    }
}

public object LocalPurchasingCodec {
    /** Shared original command normalization for production clients and retained requests. */
    public fun normalize(key: CommandKey, bytes: StoredBytes): StoredBytes = purchasingNormalize(key, bytes)
    public fun decodeApprove(bytes: StoredBytes): LocalApprovePurchaseOrderV1 = purchasingDecode(bytes, LocalApprovePurchaseOrderV1.serializer())
    public fun decodeInvoice(bytes: StoredBytes): LocalRecordSupplierInvoiceV1 = purchasingDecode(bytes, LocalRecordSupplierInvoiceV1.serializer())
    public fun decodeCaptureInvoice(bytes: StoredBytes): LocalCaptureSupplierInvoiceV2 = purchasingDecode(bytes, LocalCaptureSupplierInvoiceV2.serializer())
    public fun decodeMatch(bytes: StoredBytes): LocalMatchSupplierInvoiceV1 = purchasingDecode(bytes, LocalMatchSupplierInvoiceV1.serializer())
    public fun decodeUncontestedMatch(bytes: StoredBytes): LocalMatchUncontestedSupplierInvoiceV1 = purchasingDecode(bytes, LocalMatchUncontestedSupplierInvoiceV1.serializer())
    public fun decodeCapturedMatch(bytes: StoredBytes): LocalMatchCapturedSupplierInvoiceV2 = purchasingDecode(bytes, LocalMatchCapturedSupplierInvoiceV2.serializer())
    public fun decodeCredit(bytes: StoredBytes): LocalRecordSupplierCreditV1 = purchasingDecode(bytes, LocalRecordSupplierCreditV1.serializer())
    public fun approve(request: LocalApprovePurchaseOrderV1): StoredBytes = purchasingEncode(request, LocalApprovePurchaseOrderV1.serializer())
    public fun invoice(request: LocalRecordSupplierInvoiceV1): StoredBytes = purchasingEncode(request, LocalRecordSupplierInvoiceV1.serializer())
    public fun captureInvoice(request: LocalCaptureSupplierInvoiceV2): StoredBytes = purchasingEncode(request, LocalCaptureSupplierInvoiceV2.serializer())
    public fun match(request: LocalMatchSupplierInvoiceV1): StoredBytes = purchasingEncode(request, LocalMatchSupplierInvoiceV1.serializer())
    public fun matchUncontested(request: LocalMatchUncontestedSupplierInvoiceV1): StoredBytes = purchasingEncode(request, LocalMatchUncontestedSupplierInvoiceV1.serializer())
    public fun matchCapturedInvoice(request: LocalMatchCapturedSupplierInvoiceV2): StoredBytes = purchasingEncode(request, LocalMatchCapturedSupplierInvoiceV2.serializer())
    public fun credit(request: LocalRecordSupplierCreditV1): StoredBytes = purchasingEncode(request, LocalRecordSupplierCreditV1.serializer())
    public fun query(request: LocalPurchasingQueryV1): StoredBytes = purchasingEncode(request, LocalPurchasingQueryV1.serializer())
    public fun decodeQuery(bytes: StoredBytes): LocalPurchasingQueryV1 = purchasingDecode(bytes, LocalPurchasingQueryV1.serializer())
    public fun matchSource(request: LocalPurchasingMatchSourceQueryV1): StoredBytes = purchasingEncode(request, LocalPurchasingMatchSourceQueryV1.serializer())
    public fun uncontestedMatchSource(request: LocalPurchasingUncontestedMatchSourceQueryV1): StoredBytes = purchasingEncode(request, LocalPurchasingUncontestedMatchSourceQueryV1.serializer())
    public fun capturedMatchSource(request: LocalCapturedSupplierInvoiceMatchSourceQueryV2): StoredBytes = purchasingEncode(request, LocalCapturedSupplierInvoiceMatchSourceQueryV2.serializer())
    public fun decodeCapturedMatchSourceQuery(bytes: StoredBytes): LocalCapturedSupplierInvoiceMatchSourceQueryV2 = purchasingDecode(bytes, LocalCapturedSupplierInvoiceMatchSourceQueryV2.serializer())
    public fun capturedMatchSourceResult(bytes: StoredBytes): LocalCapturedSupplierInvoiceMatchSourceV2 = purchasingDecode(bytes, LocalCapturedSupplierInvoiceMatchSourceV2.serializer())
    public fun capturedMatchResult(bytes: StoredBytes): LocalCapturedSupplierInvoiceMatchResultV2 = purchasingDecode(bytes, LocalCapturedSupplierInvoiceMatchResultV2.serializer())
    public fun capturedMatchResult(result: LocalCapturedSupplierInvoiceMatchResultV2): StoredBytes = purchasingEncode(result, LocalCapturedSupplierInvoiceMatchResultV2.serializer())
    public fun creditSource(request: LocalPurchasingCreditSourceQueryV1): StoredBytes = purchasingEncode(request, LocalPurchasingCreditSourceQueryV1.serializer())
    public fun result(bytes: StoredBytes): LocalPurchasingResultV1 = purchasingDecode(bytes, LocalPurchasingResultV1.serializer())
    public fun page(bytes: StoredBytes): LocalPurchasingPageV1 = purchasingDecode(bytes, LocalPurchasingPageV1.serializer())
    public fun invoicePage(bytes: StoredBytes): LocalPurchasingInvoicePageV2 = purchasingDecode(bytes, LocalPurchasingInvoicePageV2.serializer())
    public fun captureResult(bytes: StoredBytes): LocalInvoiceCaptureResultV2 = purchasingDecode(bytes, LocalInvoiceCaptureResultV2.serializer())
    public fun captureResult(result: LocalInvoiceCaptureResultV2): StoredBytes = purchasingEncode(result, LocalInvoiceCaptureResultV2.serializer())
    public fun matchSourceResult(bytes: StoredBytes): LocalPurchasingMatchSourceV1 = purchasingDecode(bytes, LocalPurchasingMatchSourceV1.serializer())
    public fun uncontestedMatchSourceResult(bytes: StoredBytes): LocalPurchasingUncontestedMatchSourceV1 = purchasingDecode(bytes, LocalPurchasingUncontestedMatchSourceV1.serializer())
    public fun creditSourceResult(bytes: StoredBytes): LocalPurchasingCreditSourceV1 = purchasingDecode(bytes, LocalPurchasingCreditSourceV1.serializer())
}

internal val purchasingJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
internal fun <T> purchasingEncode(value: T, serializer: KSerializer<T>): StoredBytes =
    StoredBytes(purchasingJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true))
internal fun <T> purchasingDecode(bytes: StoredBytes, serializer: KSerializer<T>): T =
    purchasingJson.decodeFromString(serializer, bytes.copyBytes().decodeToString(throwOnInvalidSequence = true))
internal fun purchasingReason(value: String) { require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "._-" }) }
internal fun purchasingKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun purchasingClaim(kind: String, id: DomainId): ClaimKey = ClaimKey(LocalPurchasingProfile.DOCUMENT_CLAIM, "$kind/${id.value}")
internal fun purchasingResult(code: String, id: DomainId, revision: Revision? = null, progress: Revision? = null): StoredBytes =
    purchasingEncode(LocalPurchasingResultV1(code, id, revision, progress), LocalPurchasingResultV1.serializer())

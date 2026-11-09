@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalPurchaseReceiptFacts
import com.ukkera.pos.inventory.store.LocalPoReceiptLineValuationV1
import com.ukkera.pos.inventory.store.LocalStockSourceStamp
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

@Serializable
internal data class PurchasingSource(val kind: String, val id: DomainId, val revision: Revision, val digest: String) {
    companion object {
        fun from(row: StoredAggregate) = PurchasingSource(row.key.kind, row.key.id, row.revision, row.digest.value)
        fun from(stamp: LocalStockSourceStamp) = PurchasingSource(stamp.key.kind, stamp.key.id, stamp.storageRevision, stamp.digest.value)
    }
}

@Serializable
internal data class PurchasingProducer(val operationId: OperationId, val actorId: DomainId, val command: String, val digest: String) {
    companion object { fun from(context: CommandContext) = PurchasingProducer(context.operationId, context.originalActorId, context.key.name, context.semanticDigest.value) }
}

@Serializable
internal data class PurchasingRecord(
    val scope: BranchScope, val kind: String, val id: DomainId, val revision: Revision,
    val producer: PurchasingProducer, val recordedAt: UtcInstant, val policyRevision: Revision,
    val body: PurchasingBody,
)

@Serializable
internal sealed interface PurchasingBody {
    @Serializable @SerialName("ORDER")
    data class Order(val value: PurchaseOrderV1, val normalizedReference: String, val sources: List<PurchasingSource>) : PurchasingBody
    @Serializable @SerialName("INVOICE")
    data class Invoice(val value: PurchaseInvoiceV1, val normalizedReference: String, val orderSource: PurchasingSource,
        val supplierSource: PurchasingSource, val discrepancies: List<PurchaseDiscrepancyV1>) : PurchasingBody
    @Serializable @SerialName("INVOICE_CAPTURE_V2")
    data class InvoiceCapture(val value: PurchaseInvoiceCaptureV2, val normalizedReference: String,
        val orderSource: PurchasingSource?, val orderOperationId: OperationId?, val supplierSource: PurchasingSource,
        val catalogSources: List<PurchasingCaptureCatalogSourceV2>, val discrepancies: List<PurchaseDiscrepancyV1>) : PurchasingBody
    @Serializable @SerialName("PROGRESS")
    data class Progress(val invoiceId: DomainId, val lines: List<PurchasingLineProgress>, val matchIds: List<DomainId>) : PurchasingBody
    @Serializable @SerialName("ORDER_BILLED")
    data class OrderBilled(val orderId: DomainId, val allocationId: DomainId, val quantity: Quantity,
        val net: Money, val tax: Money, val matchIds: List<DomainId>) : PurchasingBody
    @Serializable @SerialName("ORDER_MATCHES")
    data class OrderMatches(val orderId: DomainId, val matchIds: List<DomainId>) : PurchasingBody
    @Serializable @SerialName("RECEIPT_BILLED")
    data class ReceiptBilled(val receiptId: DomainId, val lineId: DomainId, val sourceId: DomainId,
        val orderId: DomainId, val allocationId: DomainId, val quantity: Quantity,
        val net: Money, val tax: Money, val matchIds: List<DomainId>) : PurchasingBody
    @Serializable @SerialName("MATCH")
    data class Match(val request: LocalMatchSupplierInvoiceV1, val orderId: DomainId, val allocationId: DomainId,
        val receipt: PurchasingReceiptSnapshot, val invoiceBefore: Quantity, val orderBefore: Quantity, val receiptBefore: Quantity,
        val net: Money, val tax: Money,
        /** Default MUST be omitted even under encodeDefaults=true; old MATCH payloads stay canonical. */
        @EncodeDefault(EncodeDefault.Mode.NEVER) val uncontested: PurchaseUncontestedAmountsV1? = null) : PurchasingBody
    @Serializable @SerialName("CAPTURE_MATCH_V2")
    data class CapturedMatch(
        val request: LocalMatchCapturedSupplierInvoiceV2,
        val originalSource: PurchasingDocumentSource,
        val invoiceSource: PurchasingSource, val invoiceOperationId: OperationId,
        val orderSource: PurchasingSource, val orderOperationId: OperationId,
        val receipt: PurchasingReceiptSnapshot,
        val invoiceBefore: Quantity, val orderBefore: Quantity, val receiptBefore: Quantity,
        val net: Money, val tax: Money,
    ) : PurchasingBody
    @Serializable @SerialName("PAYABLE")
    data class Payable(val invoiceId: DomainId, val orderId: DomainId, val supplierId: DomainId, val dueDate: BusinessDate,
        val status: PurchasingPayableStatus, val net: Money, val tax: Money, val matchIds: List<DomainId>) : PurchasingBody
    @Serializable @SerialName("CREDIT")
    data class Credit(val request: LocalRecordSupplierCreditV1, val supplierId: DomainId,
        val normalizedReference: String, val allocation: PurchasingCreditAllocation) : PurchasingBody
    @Serializable @SerialName("INVOICE_CREDITS")
    data class InvoiceCredits(val invoiceId: DomainId, val creditIds: List<DomainId>) : PurchasingBody
    @Serializable @SerialName("SUPPLIER_CASH_ATTEMPT")
    data class SettlementAttempt(val value: PurchasingSupplierAttempt) : PurchasingBody
    @Serializable @SerialName("SUPPLIER_PAYMENT")
    data class SupplierPayment(val value: PurchasingSupplierPayment) : PurchasingBody
    @Serializable @SerialName("SUPPLIER_ADVANCE")
    data class SupplierAdvance(val value: PurchasingSupplierAdvance) : PurchasingBody
    @Serializable @SerialName("ADVANCE_ALLOCATIONS")
    data class AdvanceAllocations(val advanceId: DomainId, val allocationIds: List<DomainId>) : PurchasingBody
    @Serializable @SerialName("SETTLEMENT_ALLOCATION")
    data class SettlementAllocation(val value: PurchasingSupplierAllocation) : PurchasingBody
    @Serializable @SerialName("INVOICE_SETTLEMENTS")
    data class InvoiceSettlements(val invoiceId: DomainId, val allocationIds: List<DomainId>,
        val settlementIds: List<DomainId>, val lastEventId: DomainId) : PurchasingBody
    @Serializable @SerialName("SETTLEMENT_EVENT")
    data class SettlementEvent(val value: PurchasingSettlementEvent) : PurchasingBody
}

@Serializable internal enum class PurchasingPayableStatus { UNSETTLED }
@Serializable internal data class PurchasingLineProgress(val lineId: DomainId, val quantity: Quantity, val net: Money, val tax: Money)

/** Original matching-cut receipt evidence; not permission to match this quantity a second time. */
@Serializable
internal data class PurchasingReceiptSnapshot(
    val receiptId: DomainId, val lineId: DomainId, val sourceId: DomainId, val source: PurchasingSource,
    val allowanceSource: PurchasingSource, val supplierId: DomainId, val basisId: DomainId, val basisRevision: Revision,
    val allocationId: DomainId, val skuId: DomainId, val warehouseId: DomainId,
    val originalQuantity: Quantity, val acceptedQuantity: Quantity, val originalOwnedValue: Money,
    val lotId: DomainId?, val serialId: DomainId?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val poValuation: LocalPoReceiptLineValuationV1? = null,
) {
    companion object {
        fun from(facts: LocalPurchaseReceiptFacts) = PurchasingReceiptSnapshot(facts.receiptId, facts.lineId, facts.sourceId,
            PurchasingSource.from(facts.source), PurchasingSource.from(requireNotNull(facts.allowance).source),
            requireNotNull(facts.supplierId), facts.basisId, facts.basisRevision, facts.allocationId, facts.skuId, facts.warehouseId,
            facts.baseQuantity, facts.acceptedQuantity, requireNotNull(facts.originalOwnedValue), facts.lotId, facts.serialId, facts.poValuation)
    }
}

internal data class PurchasingRow(val row: StoredAggregate, val value: PurchasingRecord)
@Serializable
internal data class PurchasingCaptureCatalogSourceV2(val skuSource: PurchasingSource, val publicationSource: PurchasingSource,
    val unitSources: List<PurchasingCaptureUnitSourceV2>)
@Serializable
internal data class PurchasingCaptureUnitSourceV2(val unitId: DomainId, val publicationSource: PurchasingSource)
internal data class PurchasingInvoiceCaptureCut(val invoice: PurchasingRow, val originalOrder: PurchaseOrderV1?)
internal data class PurchasingInvoiceCut(
    val invoice: PurchasingRow, val order: PurchasingRow, val progress: PurchasingRow, val payable: PurchasingRow?,
    val matches: List<PurchasingMatchFact>, val credits: PurchasingCreditLedger, val settlements: PurchasingSettlementLedger,
)

internal val purchasingKinds: Set<String> = setOf(LocalPurchasingProfile.ORDER, LocalPurchasingProfile.INVOICE,
    LocalPurchasingProfile.PROGRESS, LocalPurchasingProfile.ORDER_BILLED, LocalPurchasingProfile.ORDER_MATCHES, LocalPurchasingProfile.RECEIPT_BILLED,
    LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PAYABLE, LocalPurchasingProfile.CREDIT, LocalPurchasingProfile.INVOICE_CREDITS) +
    LocalSupplierSettlementProfile.kinds

internal fun purchasingReferenceClaim(scope: BranchScope, type: String, supplierId: DomainId, reference: String): ClaimKey =
    ClaimKey(LocalPurchasingProfile.REFERENCE_CLAIM, "$type/${supplierId.value}/" +
        sha256(("purchasing-reference/v1/${scope.companyId.value}/${supplierId.value}/" + normalizedPurchaseReference(reference)).encodeToByteArray()).value)

internal fun purchasingReferenceResource(key: ClaimKey): DomainId {
    val hash = sha256((key.namespace + "/" + key.key).encodeToByteArray()).value
    return DomainId("${hash.substring(0, 8)}-${hash.substring(8, 12)}-${hash.substring(12, 16)}-${hash.substring(16, 20)}-${hash.substring(20, 32)}")
}

internal fun purchasingChange(
    context: CommandContext, kind: String, id: DomainId, previous: PurchasingRow?, body: PurchasingBody,
    at: UtcInstant, policyRevision: Revision,
): AggregateChange = AggregateChange(purchasingKey(kind, id), previous?.row?.revision, LocalPurchasingProfile.schema(kind),
    purchasingEncode(PurchasingRecord(context.scope, kind, id, (previous?.row?.revision ?: Revision(0)).next(),
        PurchasingProducer.from(context), at, policyRevision, body), PurchasingRecord.serializer()))

internal fun purchasingAcquisition(kind: String, id: DomainId): ClaimChange = ClaimChange.Acquire(purchasingClaim(kind, id), id)

internal fun purchasingInitialProgress(invoice: PurchaseInvoiceV1): PurchasingBody.Progress = PurchasingBody.Progress(invoice.id,
    invoice.lines.map { PurchasingLineProgress(it.lineId, it.goods.baseQuantity().copy(scaled = 0), purchaseZero(), purchaseZero()) }, emptyList())

internal fun purchasingInitialProgress(invoice: PurchasingInvoiceClaim): PurchasingBody.Progress = PurchasingBody.Progress(invoice.id,
    invoice.lines.map { PurchasingLineProgress(it.lineId, it.goods.baseQuantity().copy(scaled = 0), purchaseZero(), purchaseZero()) }, emptyList())

internal fun purchasingBasis(order: PurchaseOrderV1): LocalReceivingApprovalV1 = LocalReceivingApprovalV1(order.id,
    LocalBasisKindV1.LOCAL_PURCHASE, order.supplierId, order.validFrom, order.validUntil,
    order.lines.map { LocalApprovedStockLineV1(it.allocationId, it.goods.skuId, it.warehouseId,
        it.goods.baseQuantity(), it.goods.net, it.inspectionRequired) })

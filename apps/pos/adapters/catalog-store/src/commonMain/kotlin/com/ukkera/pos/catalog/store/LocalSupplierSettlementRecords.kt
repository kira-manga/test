@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.purchaseZero
import com.ukkera.pos.sales.SupplierCashCommitment
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

@Serializable
internal data class PurchasingSettlementIndexSource(val source: PurchasingSource, val operationId: OperationId)

/** Exact observed economic cut. Null invoice fields mean a genuine supplier-only advance. */
@Serializable
internal data class PurchasingSupplierCashBasis(
    val supplierSource: PurchasingSource, val invoiceSource: PurchasingSource?, val progressSource: PurchasingSource?,
    val payableSource: PurchasingSource?, val creditSource: PurchasingSource?, val settlementSource: PurchasingSettlementIndexSource?,
    val recognized: Money?, val appliedCredit: Money?, val allocated: Money?, val reserved: Money?, val remainingDue: Money?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val capturedInvoice: Boolean = false,
) {
    fun digest(): com.ukkera.pos.security.PayloadDigest = sha256(purchasingEncode(this, serializer()).copyBytes())
}

@Serializable internal enum class PurchasingSupplierAttemptStatus { AWAITING_ATTESTATION, OUTCOME_UNKNOWN, CASH_PAID, NOT_PAID }
@Serializable
internal data class PurchasingSupplierAttempt(
    val target: LocalSupplierCashTarget, val source: LocalSupplierCashSource, val amount: Money,
    val basisBefore: PurchasingSupplierCashBasis, val cashSourcesBefore: List<PurchasingSource>,
    val slotDigest: com.ukkera.pos.security.PayloadDigest, val status: PurchasingSupplierAttemptStatus,
    val originalBodyDigest: com.ukkera.pos.security.PayloadDigest? = null,
    val resolutionBodyDigest: com.ukkera.pos.security.PayloadDigest? = null,
    val applicationOperationIds: List<OperationId> = emptyList(),
) {
    fun commitment(): SupplierCashCommitment = SupplierCashCommitment(target.supplierId, target.invoiceId,
        target.settlementId, when (val selection = source) {
            is LocalSupplierCashSource.Invoice -> selection.expectedProgressRevision
            is LocalSupplierCashSource.Advance -> selection.expectedSupplierRevision
        }, amount)
}

/** One original physical payout. Its invoice allocations and its advance balance are separate. */
@Serializable
internal data class PurchasingSupplierPayment(
    val id: DomainId, val supplierId: DomainId, val target: LocalSupplierCashTarget, val amount: Money,
    val phase: LocalSupplierCashPhase, val originalBodyDigest: com.ukkera.pos.security.PayloadDigest,
    val observedBodyDigest: com.ukkera.pos.security.PayloadDigest, val evidence: LocalCashEvidenceV1,
    val sourceAttempt: PurchasingSource, val cashMovementId: DomainId,
)
@Serializable
internal data class PurchasingSupplierAdvance(val id: DomainId, val supplierId: DomainId,
    val paymentId: DomainId, val paymentSource: PurchasingSource, val amount: Money)

/** Allocation uses source funds and current gross recognition; it makes no physical money. */
@Serializable
internal data class PurchasingSupplierAllocation(
    val id: DomainId, val invoiceId: DomainId, val supplierId: DomainId, val paymentId: DomainId,
    val advanceId: DomainId?, val amount: Money, val basisBefore: PurchasingSupplierCashBasis,
    val paymentSource: PurchasingSource, val advanceSource: PurchasingSource?, val advanceAllocationSource: PurchasingSource?,
    val unappliedAdvanceBefore: Money?, val reasonCode: String,
)

@Serializable internal enum class PurchasingSettlementEventKind { START_CASH, APPLY_ORIGINAL, APPLY_RESOLUTION, ALLOCATE_ADVANCE }
/** Every invoice index generation retains its exact predecessor and command-selected source. */
@Serializable
internal data class PurchasingSettlementEvent(
    val id: DomainId, val kind: PurchasingSettlementEventKind, val invoiceId: DomainId?,
    val basisBefore: PurchasingSupplierCashBasis, val attemptSource: PurchasingSource?,
    val advanceSource: PurchasingSource?, val advanceAllocationSource: PurchasingSource?,
    val cashSourcesBefore: List<PurchasingSource>, val originalSource: PurchasingSource?, val resolutionSource: PurchasingSource?,
    val originalUnknownApplicationOperationId: OperationId?,
)

internal data class PurchasingSettlementIndexImage(val source: PurchasingSource, val record: PurchasingRecord,
    val body: PurchasingBody.InvoiceSettlements, val allocated: Money = purchaseZero(), val reserved: Money = purchaseZero())
internal data class PurchasingSettlementLedger(val index: PurchasingRow?, val allocations: List<PurchasingRow>,
    val attempts: List<PurchasingRow>, val allocatedTotal: Money, val reservedTotal: Money,
    val indexHistory: List<PurchasingSettlementIndexImage>)
internal data class PurchasingAdvanceCut(val advance: PurchasingRow, val payment: PurchasingRow,
    val index: PurchasingRow, val allocations: List<PurchasingRow>, val allocated: Money, val unapplied: Money)

internal fun purchasingSettlementIndexSource(row: PurchasingRow?): PurchasingSettlementIndexSource? = row?.let {
    PurchasingSettlementIndexSource(PurchasingSource.from(it.row), it.value.producer.operationId)
}
internal fun purchasingSupplierCashBasis(supplier: StoredAggregate, cut: PurchasingInvoiceCut?): PurchasingSupplierCashBasis {
    if (cut == null) return PurchasingSupplierCashBasis(PurchasingSource.from(supplier), null, null, null, null, null,
        null, null, null, null, null)
    val due = purchasingDue(cut) ?: error("Supplier recognized payable unavailable")
    return PurchasingSupplierCashBasis(PurchasingSource.from(supplier), PurchasingSource.from(cut.invoice.row),
        PurchasingSource.from(cut.progress.row), PurchasingSource.from(checkNotNull(cut.payable).row),
        cut.credits.index?.let { PurchasingSource.from(it.row) }, purchasingSettlementIndexSource(cut.settlements.index),
        due.recognized, due.appliedCredit, cut.settlements.allocatedTotal, cut.settlements.reservedTotal, due.remainingDue,
        capturedInvoice = purchasingInvoiceClaim(cut.invoice).captured)
}
internal fun purchasingSource(write: RetainedAggregateWrite): PurchasingSource {
    check(write.payloadDigest == sha256(write.payload.copyBytes()))
    return PurchasingSource(write.key.kind, write.key.id, write.storageGeneration, write.payloadDigest.value)
}

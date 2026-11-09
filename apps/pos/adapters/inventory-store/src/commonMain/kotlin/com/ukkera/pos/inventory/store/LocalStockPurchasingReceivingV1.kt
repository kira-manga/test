package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** This calculation allocates an APPROVED PO net amount. A generic allowance ceiling is not a price. */
public object LocalPoReceiptProfileV1 {
    public const val CALCULATION: String = "po.net-goods.cumulative-floor/v1"
    internal const val ORDER_KIND: String = "local.purchasing.order"
    internal val APPROVAL_COMMAND: CommandKey = CommandKey("local.purchasing.order.approve", 1)
}

/** Counted BASE units, not a fictional fractional carton. The original PO UOM stays in its retained proof. */
public data class LocalPoReceiptLineV1(
    public val lineId: DomainId, public val allocationId: DomainId, public val sourceId: DomainId,
    public val skuId: DomainId, public val warehouseId: DomainId, public val quantity: Quantity,
    public val lotId: DomainId?, public val expiresAt: UtcInstant?, public val disposition: LocalReceiptDisposition,
    public val serialId: DomainId? = null, public val serialCode: String? = null,
) {
    init {
        localStockCheck(quantity.scaled > 0, LocalStockFailure.INVALID_QUANTITY)
        localStockCheck(lotId != null || expiresAt == null, LocalStockFailure.INVALID_LOT)
        localStockCheck((serialId == null) == (serialCode == null), LocalStockFailure.INVALID_SERIAL)
        serialCode?.let(::requireStockSerialCode)
    }
    internal fun valued(value: Money?): LocalGoodsReceiptLine = LocalGoodsReceiptLine(lineId, allocationId, sourceId,
        skuId, warehouseId, quantity, null, lotId, expiresAt, disposition, value, serialId, serialCode)
}

/** Null is an explicit expectation of claim-checked absence, never an unavailable-read fallback. */
@Serializable
public data class LocalPoAllowanceVersionV1(public val allocationId: DomainId, public val storageRevision: Revision?) {
    init { require(storageRevision == null || storageRevision.value >= 1) }
}

/**
 * Value-free employee command. Line order is retained: it deterministically assigns the residual minor
 * units within each allocation. Receipt/line/source/operation identities survive save, retry and lookup.
 * Only owned slices consume an allowance, so only those allocations have an expected storage revision.
 */
public class LocalPoReceiptRequestV1(
    public val receiptId: DomainId, public val orderId: DomainId,
    public val expectedOrderRevision: Revision, public val expectedBasisRevision: Revision,
    items: List<LocalStockItemVersion>, allowances: List<LocalPoAllowanceVersionV1>,
    lines: List<LocalPoReceiptLineV1>, public val reasonCode: String,
) {
    init {
        require(expectedOrderRevision.value >= 1)
        localStockCheck(lines.size in 1..LocalStockStoreProfile.MAX_LINES_PER_COMMAND, LocalStockFailure.BOUND_EXCEEDED)
        localStockCheck(items.size in 1..LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND, LocalStockFailure.BOUND_EXCEEDED)
        localStockCheck(items.map { it.skuId }.distinct().size == items.size && items.map { it.skuId }.toSet() == lines.map { it.skuId }.toSet())
        localStockCheck(lines.map { it.lineId }.distinct().size == lines.size && lines.map { it.sourceId }.distinct().size == lines.size)
        localStockCheck(lines.mapNotNull { it.serialId }.distinct().size == lines.count { it.serialId != null } &&
            lines.mapNotNull { it.serialCode }.distinct().size == lines.count { it.serialCode != null }, LocalStockFailure.INVALID_SERIAL)
        localStockCheck(allowances.size <= LocalStockStoreProfile.MAX_LINES_PER_COMMAND &&
            allowances.map { it.allocationId }.distinct().size == allowances.size &&
            allowances.map { it.allocationId }.toSet() == lines.filter { poOwned(it.disposition) }.map { it.allocationId }.toSet())
        localStockReason(reasonCode)
    }
    public val items: List<LocalStockItemVersion> = localStockFrozen(items.sortedBy { it.skuId.value })
    public val allowances: List<LocalPoAllowanceVersionV1> = localStockFrozen(allowances.sortedBy { it.allocationId.value })
    public val lines: List<LocalPoReceiptLineV1> = localStockFrozen(lines)
}

/**
 * Trusted composition port, NOT a DTO, client grant, remote callback or alternate stock repository.
 * The concrete catalog-store reader must verify the actual immutable PO's complete retained approval,
 * its current document/reference/allocation claims and the exact receiving basis in this SAME view.
 */
public fun interface LocalStockPurchaseOrderReaderV1 {
    public suspend fun order(view: CommandReadView, id: DomainId): LocalStockApprovedPurchaseOrderV1?
}

/** Historical data, not reusable authorization. No constructor can manufacture kernel-retained evidence. */
public class LocalStockApprovedPurchaseOrderV1(
    order: PurchaseOrderV1, public val source: LocalStockSourceStamp,
    public val basis: LocalStockReceivingBasis, public val approval: RetainedCommandEvidence,
) {
    public val order: PurchaseOrderV1 = order.copy(lines = localStockFrozen(order.lines), charges = localStockFrozen(order.charges))
}

/** Stored only in cost-bearing producer/matching evidence, never in the clerk's read or command. */
@Serializable
public data class LocalPoReceiptOrderSourceV1(
    public val orderId: DomainId, public val storageRevision: Revision,
    public val digest: String, public val approvalOperationId: OperationId,
) {
    internal fun stamp(): LocalStockSourceStamp = LocalStockSourceStamp(
        AggregateKey(LocalPoReceiptProfileV1.ORDER_KIND, orderId), storageRevision, PayloadDigest(digest))
}

/** Exact original slice allocation, not a new charge, tolerance or permission to receive/match it again. */
@Serializable
public data class LocalPoReceiptLineValuationV1(
    public val calculation: String, public val orderSource: LocalPoReceiptOrderSourceV1,
    public val receiptId: DomainId, public val lineId: DomainId, public val sourceId: DomainId,
    public val allocationId: DomainId, public val fullQuantity: Quantity, public val approvedNet: Money,
    public val quantityBefore: Quantity, public val quantity: Quantity, public val originalValue: Money,
)

/** Shared CLOSED proven-profile predicate. Legacy callers must keep their original exact-ratio checks. */
public fun LocalPoReceiptLineValuationV1.agreesWith(
    order: PurchaseOrderV1, allocation: PurchaseOrderLineV1, receiptId: DomainId, lineId: DomainId,
    sourceId: DomainId, quantity: Quantity, originalValue: Money,
): Boolean {
    val full = allocation.goods.baseQuantity()
    if (calculation != LocalPoReceiptProfileV1.CALCULATION || orderSource.orderId != order.id ||
        orderSource.storageRevision != Revision(1) || this.receiptId != receiptId || this.lineId != lineId || this.sourceId != sourceId ||
        allocationId != allocation.allocationId || allocation.receiptCostBasis != PurchaseReceiptCostBasisV1.NET_GOODS_ONLY ||
        fullQuantity != full || approvedNet != allocation.goods.net || this.quantity != quantity || this.originalValue != originalValue ||
        quantityBefore.unitId != full.unitId || quantityBefore.scale != full.scale || quantityBefore.scaled < 0 || quantityBefore > full ||
        quantity.unitId != full.unitId || quantity.scale != full.scale || quantity.scaled <= 0 || quantity > full - quantityBefore) return false
    PayloadDigest(orderSource.digest)
    return originalValue == purchaseCumulativeAmount(approvedNet, quantityBefore + quantity, full) -
        purchaseCumulativeAmount(approvedNet, quantityBefore, full)
}

@Serializable
internal data class StockPoReceiptLineWire(
    val lineId: DomainId, val allocationId: DomainId, val sourceId: DomainId, val skuId: DomainId, val warehouseId: DomainId,
    val quantity: Quantity, val lotId: DomainId?, val expiresAt: UtcInstant?, val disposition: String,
    val serialId: DomainId?, val serialCode: String?,
) {
    fun restore(): LocalPoReceiptLineV1 = LocalPoReceiptLineV1(lineId, allocationId, sourceId, skuId, warehouseId,
        quantity, lotId, expiresAt, stockEnum(disposition), serialId, serialCode)
    companion object {
        fun from(line: LocalPoReceiptLineV1): StockPoReceiptLineWire = StockPoReceiptLineWire(line.lineId, line.allocationId,
            line.sourceId, line.skuId, line.warehouseId, line.quantity, line.lotId, line.expiresAt, line.disposition.name, line.serialId, line.serialCode)
    }
}

@Serializable
internal data class StockPoReceiptRequestWire(
    val receiptId: DomainId, val orderId: DomainId, val expectedOrderRevision: Revision, val expectedBasisRevision: Revision,
    val items: List<StockItemVersionWire>, val allowances: List<LocalPoAllowanceVersionV1>,
    val lines: List<StockPoReceiptLineWire>, val reasonCode: String,
) {
    fun restore(): LocalPoReceiptRequestV1 = LocalPoReceiptRequestV1(receiptId, orderId, expectedOrderRevision, expectedBasisRevision,
        items.map(StockItemVersionWire::restore), allowances, lines.map(StockPoReceiptLineWire::restore), reasonCode)
    companion object {
        fun from(input: LocalPoReceiptRequestV1): StockPoReceiptRequestWire = StockPoReceiptRequestWire(input.receiptId,
            input.orderId, input.expectedOrderRevision, input.expectedBasisRevision, input.items.map(StockItemVersionWire::from),
            input.allowances, input.lines.map(StockPoReceiptLineWire::from), input.reasonCode)
    }
}

@Serializable
internal data class StockPoReceiptPrefixWire(
    val allocationId: DomainId, val storageRevision: Revision?, val quantity: Quantity, val ownedValue: Money,
)

@Serializable
internal data class StockPoReceiptValuationWire(
    val calculation: String, val orderSource: LocalPoReceiptOrderSourceV1,
    val order: PurchaseOrderV1, val prefixes: List<StockPoReceiptPrefixWire>,
)

/** Metadata-only preparation input: there is deliberately no value or executable receipt in this type. */
internal data class StockReceiptPhysicalInput(
    val lineId: DomainId, val allocationId: DomainId, val sourceId: DomainId, val skuId: DomainId, val warehouseId: DomainId,
    val quantity: Quantity, val lotId: DomainId?, val expiresAt: UtcInstant?, val disposition: LocalReceiptDisposition,
    val serialId: DomainId?, val serialCode: String?,
)

/** Both real command profiles use one stock loader/planner. Their normalized identities are NEVER swapped. */
internal class StockReceiptPreparationInput private constructor(
    private val legacy: LocalGoodsReceiptRequest?, val purchase: LocalPoReceiptRequestV1?,
    val receiptId: DomainId, val basisId: DomainId, val expectedBasisRevision: Revision,
    val items: List<LocalStockItemVersion>, val lines: List<StockReceiptPhysicalInput>, val reasonCode: String,
) {
    fun resolve(source: LocalStockApprovedPurchaseOrderV1?, basis: LocalStockReceivingBasis,
                allowances: Map<DomainId, LocalStockRecord<StockAllowanceWire>?>): Pair<LocalGoodsReceiptRequest, StockPoReceiptValuationWire?> {
        legacy?.let { return it to null }
        val request = requireNotNull(purchase)
        val approved = source ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
        localStockCheck(approved.source.storageRevision == request.expectedOrderRevision && basis.revision == request.expectedBasisRevision,
            LocalStockFailure.STALE_REVISION)
        localStockCheck(approved.order.id == request.orderId && approved.basis.scope == basis.scope &&
            approved.basis.basisId == basis.basisId && approved.basis.revision == basis.revision &&
            approved.basis.kind == basis.kind && approved.basis.supplierId == basis.supplierId && approved.basis.lines == basis.lines &&
            approved.basis.validFrom == basis.validFrom && approved.basis.validUntil == basis.validUntil &&
            approved.basis.approvedBy == basis.approvedBy && approved.basis.policyId == basis.policyId &&
            approved.basis.sources == basis.sources, LocalStockFailure.STORAGE_INCONSISTENT)
        val prefixes = request.allowances.map { expected ->
            val before = allowances.getValue(expected.allocationId)
            localStockCheck(before?.row?.revision == expected.storageRevision, LocalStockFailure.STALE_REVISION)
            val line = approved.order.lines.singleOrNull { it.allocationId == expected.allocationId }
                ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
            val full = line.goods.baseQuantity()
            before?.value?.let { value ->
                localStockCheck(value.basisId == approved.order.id && value.allocationId == line.allocationId &&
                    value.supplierId == approved.order.supplierId && value.skuId == line.goods.skuId && value.warehouseId == line.warehouseId,
                    LocalStockFailure.BASIS_UNAVAILABLE)
            }
            StockPoReceiptPrefixWire(line.allocationId, before?.row?.revision,
                before?.value?.committedQuantity ?: full.copy(scaled = 0), before?.value?.committedOwnedValue ?: purchaseZero())
        }
        val proof = StockPoReceiptValuationWire(LocalPoReceiptProfileV1.CALCULATION, approved.reference(), approved.order, prefixes)
        return derivePoReceipt(request, proof) to proof
    }
    companion object {
        fun legacy(request: LocalGoodsReceiptRequest): StockReceiptPreparationInput = StockReceiptPreparationInput(request, null,
            request.receiptId, request.basisId, request.expectedBasisRevision, request.items, request.lines.map { line ->
                StockReceiptPhysicalInput(line.lineId, line.allocationId, line.sourceId, line.skuId, line.warehouseId, line.quantity,
                    line.lotId, line.expiresAt, line.disposition, line.serialId, line.serialCode)
            }, request.reasonCode)
        fun purchase(request: LocalPoReceiptRequestV1): StockReceiptPreparationInput = StockReceiptPreparationInput(null, request,
            request.receiptId, request.orderId, request.expectedBasisRevision, request.items, request.lines.map { line ->
                StockReceiptPhysicalInput(line.lineId, line.allocationId, line.sourceId, line.skuId, line.warehouseId, line.quantity,
                    line.lotId, line.expiresAt, line.disposition, line.serialId, line.serialCode)
            }, request.reasonCode)
    }
}

private fun LocalStockApprovedPurchaseOrderV1.reference(): LocalPoReceiptOrderSourceV1 =
    LocalPoReceiptOrderSourceV1(order.id, source.storageRevision, source.digest.value, approval.operationId)

internal fun poOwned(disposition: LocalReceiptDisposition): Boolean =
    disposition == LocalReceiptDisposition.RELEASED || disposition == LocalReceiptDisposition.QUARANTINE

/** The SINGLE pricing algorithm is reused by preparation and historical proof. Never independent slice rounding. */
internal fun derivePoReceipt(request: LocalPoReceiptRequestV1, proof: StockPoReceiptValuationWire): LocalGoodsReceiptRequest {
    val order = proof.order
    localStockCheck(proof.calculation == LocalPoReceiptProfileV1.CALCULATION && order.id == request.orderId &&
        proof.orderSource.orderId == order.id && proof.orderSource.storageRevision == request.expectedOrderRevision,
        LocalStockFailure.BASIS_UNAVAILABLE)
    PayloadDigest(proof.orderSource.digest)
    localStockCheck(proof.prefixes.map { LocalPoAllowanceVersionV1(it.allocationId, it.storageRevision) } == request.allowances,
        LocalStockFailure.STALE_REVISION)
    val prefixes = proof.prefixes.associateBy { it.allocationId }
    val cursors = mutableMapOf<DomainId, Quantity>()
    for (prefix in proof.prefixes) {
        val allocation = order.lines.singleOrNull { it.allocationId == prefix.allocationId }
            ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
        val full = allocation.goods.baseQuantity()
        localStockCheck(prefix.quantity.unitId == full.unitId && prefix.quantity.scale == full.scale &&
            prefix.quantity.scaled >= 0 && prefix.quantity <= full, LocalStockFailure.INVALID_UNIT)
        // Do not absorb a prior caller-valued discrepancy into this receipt or reprice old stock.
        localStockCheck(prefix.ownedValue == purchaseCumulativeAmount(allocation.goods.net, prefix.quantity, full),
            LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        if (prefix.storageRevision == null) localStockCheck(prefix.quantity.scaled == 0L && prefix.ownedValue == purchaseZero(),
            LocalStockFailure.STORAGE_INCONSISTENT)
        cursors[prefix.allocationId] = prefix.quantity
    }
    val lines = request.lines.map { line ->
        val allocation = order.lines.singleOrNull { it.allocationId == line.allocationId &&
            it.goods.skuId == line.skuId && it.warehouseId == line.warehouseId }
            ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
        val full = allocation.goods.baseQuantity()
        localStockCheck(allocation.receiptCostBasis == PurchaseReceiptCostBasisV1.NET_GOODS_ONLY && allocation.goods.amountsAgree(),
            LocalStockFailure.BASIS_UNAVAILABLE)
        localStockCheck(line.quantity.unitId == full.unitId && line.quantity.scale == full.scale, LocalStockFailure.INVALID_UNIT)
        val value = if (!poOwned(line.disposition)) null else {
            localStockCheck(line.allocationId in prefixes, LocalStockFailure.STALE_REVISION)
            val before = cursors.getValue(line.allocationId)
            localStockCheck(line.quantity <= full - before, LocalStockFailure.ALLOWANCE_EXCEEDED)
            val after = before + line.quantity
            val amount = purchaseCumulativeAmount(allocation.goods.net, after, full) - purchaseCumulativeAmount(allocation.goods.net, before, full)
            cursors[line.allocationId] = after
            // A proven free/subminor slice may be zero. Missing price/source NEVER reaches this calculation.
            localStockCheck(amount.currency == Currency.EGP && amount.minor >= 0, LocalStockFailure.STORAGE_INCONSISTENT)
            amount
        }
        line.valued(value)
    }
    return LocalGoodsReceiptRequest(request.receiptId, request.orderId, request.expectedBasisRevision, request.items, lines, request.reasonCode)
}

internal fun requireApprovedPoSource(source: LocalStockApprovedPurchaseOrderV1, scope: BranchScope, id: DomainId) {
    val approval = source.approval
    val row = approval.aggregateWrites.singleOrNull { it.key == source.source.key }
        ?: throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)
    localStockCheck(source.order.id == id && source.source.key == AggregateKey(LocalPoReceiptProfileV1.ORDER_KIND, id) &&
        source.source.storageRevision == Revision(1) && row.storageGeneration == source.source.storageRevision && row.payloadDigest == source.source.digest &&
        row.schema == "${LocalPoReceiptProfileV1.ORDER_KIND}/v1" && approval.scope == scope && approval.committed &&
        approval.command == LocalPoReceiptProfileV1.APPROVAL_COMMAND && source.basis.scope == scope && source.basis.basisId == id &&
        source.basis.kind == LocalStockBasisKind.LOCAL_PURCHASE && source.basis.revision == Revision(0) &&
        source.basis.supplierId == source.order.supplierId && source.basis.approvedBy == approval.originalActorId &&
        source.basis.validFrom == source.order.validFrom && source.basis.validUntil == source.order.validUntil &&
        source.basis.lines == source.order.lines.map { LocalStockApprovedLine(it.allocationId, it.goods.skuId, it.warehouseId,
            it.goods.baseQuantity(), it.goods.net, it.inspectionRequired) }, LocalStockFailure.STORAGE_INCONSISTENT)
}

/** Resolve a derived request as DATA for closed proof; never invoke an old handler or change its semantic digest. */
internal fun receiptRequestForProducer(evidence: RetainedCommandEvidence): LocalGoodsReceiptRequest {
    if (evidence.command != LocalStockStoreProfile.RECEIVE_PO) return LocalStockCommandCodec.receipt(evidence.normalized)
    val request = LocalStockCommandCodec.poReceipt(evidence.normalized)
    val receipt = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId), StockReceiptWire.serializer())
    val proof = receipt.poValuation ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(receipt.basisKind == LocalStockBasisKind.LOCAL_PURCHASE.name && receipt.supplierId == proof.order.supplierId &&
        receipt.sources.contains(StockSourceStampWire.from(proof.orderSource.stamp())) &&
        evidence.recordedAt >= proof.order.validFrom && (proof.order.validUntil?.let { evidence.recordedAt < it } != false),
        LocalStockFailure.STORAGE_INCONSISTENT)
    return try { derivePoReceipt(request, proof) }
    catch (_: LocalStockRejected) { throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT) }
}

/** Called for every new-profile producer by the real stock loader, including predecessor/source reads. */
internal fun requirePoReceiptSource(evidence: RetainedCommandEvidence, source: LocalStockApprovedPurchaseOrderV1) {
    val request = LocalStockCommandCodec.poReceipt(evidence.normalized)
    val receipt = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId), StockReceiptWire.serializer())
    val proof = receipt.poValuation ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    requireApprovedPoSource(source, evidence.scope, request.orderId)
    localStockCheck(proof.order == source.order && proof.orderSource == source.reference() &&
        receipt.approvedBy == source.basis.approvedBy && receipt.policyId == source.basis.policyId &&
        receipt.sources.containsAll(source.basis.sources.map(StockSourceStampWire::from)) &&
        source.approval.operationId != evidence.operationId && source.approval.recordedAt <= evidence.recordedAt &&
        (source.approval.assignmentId != evidence.assignmentId || source.approval.authorityEpoch != evidence.authorityEpoch ||
            source.approval.sequence < evidence.sequence), LocalStockFailure.STORAGE_INCONSISTENT)
}

internal fun poReceiptLineValuation(receipt: StockReceiptWire, lineId: DomainId): LocalPoReceiptLineValuationV1? {
    val proof = receipt.poValuation ?: return null
    val line = receipt.lines.singleOrNull { it.request.lineId == lineId }
        ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    val input = line.request.restore()
    if (!poOwned(input.disposition)) return null
    val allocation = proof.order.lines.single { it.allocationId == input.allocationId }
    var before = proof.prefixes.single { it.allocationId == input.allocationId }.quantity
    for (preceding in receipt.lines.takeWhile { it.request.lineId != lineId }) {
        if (preceding.request.allocationId == input.allocationId && poOwned(preceding.request.restore().disposition)) before += preceding.baseQuantity
    }
    return LocalPoReceiptLineValuationV1(proof.calculation, proof.orderSource, receipt.receiptId, input.lineId, input.sourceId,
        input.allocationId, allocation.goods.baseQuantity(), allocation.goods.net, before, line.baseQuantity, requireNotNull(input.originalValue)).also {
        localStockCheck(it.agreesWith(proof.order, allocation, receipt.receiptId, input.lineId, input.sourceId, line.baseQuantity,
            requireNotNull(input.originalValue)), LocalStockFailure.STORAGE_INCONSISTENT)
    }
}

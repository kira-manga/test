package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Literal allowance facts from the actual current loan or native BEFORE cut.
 * They are not a new receiving permission or an unused-right proof. */
public class LocalPurchaseAllowanceFacts internal constructor(
    public val allocationId: DomainId, public val basisId: DomainId, public val supplierId: DomainId?,
    public val skuId: DomainId, public val warehouseId: DomainId,
    public val committedQuantity: Quantity, public val acceptedQuantity: Quantity,
    public val pendingInspectionQuantity: Quantity, public val committedOwnedValue: Money,
    public val source: LocalStockSourceStamp,
)

/**
 * Non-serializable stock facts from a current loan or a core-created native BEFORE cut.
 * Original receipt, observed inspection-release amount, existing allowance and source claims
 * are checked against REAL complete producers. The constructor is not a remote verifier port;
 * these facts alone are not historical-frontier/recovery authority.
 */
public class LocalPurchaseReceiptFacts internal constructor(
    public val scope: BranchScope, public val receiptId: DomainId, public val lineId: DomainId,
    public val sourceId: DomainId, public val receiptRevision: Revision,
    public val basisId: DomainId, public val basisRevision: Revision, public val basisKind: LocalStockBasisKind,
    public val supplierId: DomainId?, public val allocationId: DomainId,
    public val skuId: DomainId, public val warehouseId: DomainId,
    public val purchaseQuantity: Quantity, public val baseQuantity: Quantity,
    public val conversion: ProductUnitConversion?, public val originalOwnedValue: Money?,
    public val disposition: LocalReceiptDisposition, public val acceptedQuantity: Quantity,
    public val lotId: DomainId?, public val serialId: DomainId?, public val receivedAt: UtcInstant,
    public val allowance: LocalPurchaseAllowanceFacts?, public val source: LocalStockSourceStamp,
    public val poValuation: LocalPoReceiptLineValuationV1?,
    resources: Set<RecoveryResourceKey>,
) {
    private val copiedResources: Set<RecoveryResourceKey> = resources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = copiedResources.toSet()
}

public suspend fun LocalStockRepository.purchaseAllowanceFacts(
    view: CommandReadView, allocationId: DomainId,
): LocalPurchaseAllowanceFacts? = purchaseAllowanceFacts(CurrentPurchasingStockObservation(this, view), allocationId)

/** Only core-created active history reaches this overload; no current row is substituted. */
public suspend fun LocalStockRepository.purchaseAllowanceFacts(
    command: RuntimeHistoricalCommand, allocationId: DomainId,
): LocalPurchaseAllowanceFacts? = purchaseAllowanceFacts(HistoricalPurchasingStockObservation(command), allocationId)

private suspend fun purchaseAllowanceFacts(
    observation: PurchasingStockObservation, allocationId: DomainId,
): LocalPurchaseAllowanceFacts? = observation.document(AggregateKey(LocalStockStoreProfile.ALLOWANCE, allocationId), StockAllowanceWire.serializer())?.let { record ->
    val value = record.value
    requireAllowance(value)
    localStockCheck(value.allocationId == allocationId, LocalStockFailure.STORAGE_INCONSISTENT)
    LocalPurchaseAllowanceFacts(value.allocationId, value.basisId, value.supplierId, value.skuId, value.warehouseId,
        value.committedQuantity, value.acceptedQuantity, value.pendingInspectionQuantity, value.committedOwnedValue,
        LocalStockSourceStamp(record.row.key, record.row.revision, record.row.digest))
}

public suspend fun LocalStockRepository.purchaseReceiptFacts(
    view: CommandReadView, receiptId: DomainId, lineId: DomainId,
): LocalPurchaseReceiptFacts? = purchaseReceiptFacts(CurrentPurchasingStockObservation(this, view), receiptId, lineId)

public suspend fun LocalStockRepository.purchaseReceiptFacts(
    command: RuntimeHistoricalCommand, receiptId: DomainId, lineId: DomainId,
): LocalPurchaseReceiptFacts? = purchaseReceiptFacts(HistoricalPurchasingStockObservation(command), receiptId, lineId)

private suspend fun purchaseReceiptFacts(
    observation: PurchasingStockObservation, receiptId: DomainId, lineId: DomainId,
): LocalPurchaseReceiptFacts? {
    val receipt = observation.document(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, receiptId), StockReceiptWire.serializer()) ?: return null
    val header = receipt.value
    localStockCheck(header.receiptId == receiptId, LocalStockFailure.STORAGE_INCONSISTENT)
    val line = header.lines.singleOrNull { it.request.lineId == lineId } ?: return null
    val input = line.request.restore()
    localStockCheck(observation.claim(LocalStockStoreProfile.receiptLineClaim(input.sourceId)) == receiptId, LocalStockFailure.STORAGE_INCONSISTENT)
    val sourceOwner = observation.claim(LocalStockStoreProfile.sourceClaim(input.sourceId))
    if (input.disposition == LocalReceiptDisposition.REFUSED) {
        localStockCheck(sourceOwner == null, LocalStockFailure.STORAGE_INCONSISTENT)
    } else {
        val originId = sourceOwner ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        val (identity, request) = observation.receiptOriginal(OperationId(originId.value))
        localStockCheck(identity.operationId.value == originId.value, LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(request.receiptId == receiptId && request.basisId == header.basisId &&
            request.lines.singleOrNull { it.lineId == lineId } == input, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    val zero = line.baseQuantity.copy(scaled = 0)
    localStockCheck(line.releasedFromQuarantine.unitId == zero.unitId && line.releasedFromQuarantine.scale == zero.scale &&
        line.releasedFromQuarantine >= zero && line.releasedFromQuarantine <= line.baseQuantity, LocalStockFailure.STORAGE_INCONSISTENT)
    val accepted = when (input.disposition) {
        LocalReceiptDisposition.RELEASED -> { localStockCheck(line.releasedFromQuarantine == zero, LocalStockFailure.STORAGE_INCONSISTENT); line.baseQuantity }
        LocalReceiptDisposition.QUARANTINE -> line.releasedFromQuarantine
        LocalReceiptDisposition.REJECTED_HELD, LocalReceiptDisposition.REFUSED -> {
            localStockCheck(line.releasedFromQuarantine == zero, LocalStockFailure.STORAGE_INCONSISTENT); zero
        }
    }
    val allowance = purchaseAllowanceFacts(observation, input.allocationId)
    if (input.disposition in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE)) {
        val current = allowance ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(current.basisId == header.basisId && current.supplierId == header.supplierId && current.skuId == input.skuId &&
            current.warehouseId == input.warehouseId && current.committedQuantity.unitId == zero.unitId &&
            current.committedQuantity.scale == zero.scale && current.committedQuantity >= line.baseQuantity &&
            current.acceptedQuantity >= accepted, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    val resources = buildSet {
        add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, header.basisId))
        add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, input.allocationId))
        listOf(receiptId, lineId, input.sourceId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
        listOf(header.recordedBy, header.approvedBy, header.policyId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)) }
        header.sources.forEach { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.id)) }
        header.supplierId?.let { add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, it)) }
        listOfNotNull(input.skuId, input.warehouseId, input.sourceId, input.lotId, input.serialId).forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it))
        }
        if (input.disposition != LocalReceiptDisposition.REFUSED) {
            val position = receiptPosition(input, header.supplierId).inScope(observation.scope)
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.positionKey(position).id))
            if (input.disposition != LocalReceiptDisposition.REJECTED_HELD)
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.poolKey(position.pool).id))
        }
    }
    return LocalPurchaseReceiptFacts(observation.scope, receiptId, lineId, input.sourceId, receipt.row.revision,
        header.basisId, header.basisRevision, LocalStockBasisKind.valueOf(header.basisKind), header.supplierId, input.allocationId,
        input.skuId, input.warehouseId, input.quantity, line.baseQuantity,
        line.conversion?.let { ProductUnitConversion(it.id, it.revision, it.productId, it.sourceUnitId, it.targetUnitId, it.rate, it.massVolumeEvidenceId) },
        input.originalValue, input.disposition, accepted, input.lotId, input.serialId, header.receivedAt, allowance,
        LocalStockSourceStamp(receipt.row.key, receipt.row.revision, receipt.row.digest), poReceiptLineValuation(header, lineId), resources)
}

/** Closed factual observation sources. Neither can construct a historical CommandReadView,
 * execute a handler, invent an original, or treat unavailable production history as empty. */
private sealed interface PurchasingStockObservation {
    val scope: BranchScope
    suspend fun <T> document(key: AggregateKey, serializer: KSerializer<T>): LocalStockRecord<T>?
    suspend fun claim(key: ClaimKey): DomainId?
    suspend fun receiptOriginal(operationId: OperationId): Pair<StreamedProducerIdentityV2, LocalGoodsReceiptRequest>
}

private fun purchasingStockClaim(key: AggregateKey): ClaimKey = when (key.kind) {
    LocalStockStoreProfile.RECEIPT_DOCUMENT -> LocalStockStoreProfile.documentClaim(key.id)
    LocalStockStoreProfile.ALLOWANCE -> LocalStockStoreProfile.allowanceClaim(key.id)
    else -> inconsistentStock()
}

private class CurrentPurchasingStockObservation(
    private val repository: LocalStockRepository, private val view: CommandReadView,
) : PurchasingStockObservation {
    override val scope: BranchScope get() = view.scope
    override suspend fun <T> document(key: AggregateKey, serializer: KSerializer<T>): LocalStockRecord<T>? {
        val record = repository.record(view, key, serializer)
        val owner = claim(purchasingStockClaim(key))
        localStockCheck(if (record == null) owner == null else owner == key.id, LocalStockFailure.STORAGE_INCONSISTENT)
        return record
    }
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
    override suspend fun receiptOriginal(operationId: OperationId): Pair<StreamedProducerIdentityV2, LocalGoodsReceiptRequest> {
        if (repository.isRelationalCurrent(view)) {
            val original = view.requireStockRelational().originals.read(operationId) ?: unavailableProducer()
            when (original) {
                is RetainedOriginalV2.Legacy -> repository.requireProducer(view, original.evidence)
                is RetainedOriginalV2.Streamed -> repository.requireRelationalProducer(view, original.evidence)
            }
            return purchasingStockProducer(original) to purchasingReceiptRequest(original, scope)
        }
        val original = view.retainedCommand(operationId) ?: unavailableProducer()
        repository.requireProducer(view, original)
        // The legacy loan returns its actual retained evidence, not a core-created
        // original-frame capability. Interpret its verified data without minting one.
        return StreamedProducerIdentityV2(original.operationId, original.command, original.originalActorId,
            original.semanticDigest) to purchasingReceiptRequest(original, scope)
    }
}

private class HistoricalPurchasingStockObservation(private val command: RuntimeHistoricalCommand) : PurchasingStockObservation {
    override val scope: BranchScope get() = command.scope
    override suspend fun <T> document(key: AggregateKey, serializer: KSerializer<T>): LocalStockRecord<T>? {
        val source = command.aggregateSource(key)
        val owner = claim(purchasingStockClaim(key))
        localStockCheck(if (source == null) owner == null else owner == key.id, LocalStockFailure.STORAGE_INCONSISTENT)
        if (source == null) return null
        val record = receiptDocument(source.row, scope, serializer)
        val producer = record.envelope.producer
        localStockCheck(purchasingStockProducer(source.original) == StreamedProducerIdentityV2(producer.operationId,
            producer.commandKey(), producer.actorId, com.ukkera.pos.security.PayloadDigest(producer.digest)), LocalStockFailure.STORAGE_INCONSISTENT)
        return record
    }
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun receiptOriginal(operationId: OperationId): Pair<StreamedProducerIdentityV2, LocalGoodsReceiptRequest> {
        val original = command.earlierOriginal(operationId) ?: unavailableProducer()
        return purchasingStockProducer(original) to purchasingReceiptRequest(original, scope)
    }
}

private fun purchasingStockProducer(original: RetainedOriginalV2): StreamedProducerIdentityV2 = when (original) {
    is RetainedOriginalV2.Legacy -> with(original.evidence) { StreamedProducerIdentityV2(operationId, command, originalActorId, semanticDigest) }
    is RetainedOriginalV2.Streamed -> original.evidence.identity.producer
}

/** Data reconstruction only AFTER the actual whole producer passed on its real observation
 * source. The normalized receipt uses the same original PO allocation algorithm as receiving. */
private suspend fun purchasingReceiptRequest(original: RetainedOriginalV2, scope: BranchScope): LocalGoodsReceiptRequest = when (original) {
    is RetainedOriginalV2.Legacy -> purchasingReceiptRequest(original.evidence, scope)
    is RetainedOriginalV2.Streamed -> {
        val evidence = original.evidence
        localStockCheck(evidence.identity.scope == scope && evidence.identity.producer.command == LocalStockRelationalProfileV2.COMMAND,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val command = LocalStockRelationalCommandsV2.decode(evidence.normalized)
        localStockCheck(LocalStockRelationalCommandsV2.encode(command) == evidence.normalized, LocalStockFailure.STORAGE_INCONSISTENT)
        when (command) {
            is LocalStockRelationalCommand.Receive -> {
                localStockCheck(command.operationId == evidence.identity.producer.operationId, LocalStockFailure.STORAGE_INCONSISTENT)
                command.request
            }
            is LocalStockRelationalCommand.ReceivePo -> {
                val request = command.request
                localStockCheck(command.operationId == evidence.identity.producer.operationId, LocalStockFailure.STORAGE_INCONSISTENT)
                val row = evidence.writeRow(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId)) ?: inconsistentStock()
                val receipt = receiptDocument(row, scope, StockReceiptWire.serializer()).value
                val proof = receipt.poValuation ?: inconsistentStock()
                localStockCheck(receipt.basisKind == LocalStockBasisKind.LOCAL_PURCHASE.name && receipt.basisId == request.orderId &&
                    receipt.supplierId == proof.order.supplierId && receipt.sources.contains(StockSourceStampWire.from(proof.orderSource.stamp())) &&
                    evidence.identity.recordedAt >= proof.order.validFrom &&
                    (proof.order.validUntil?.let { evidence.identity.recordedAt < it } != false), LocalStockFailure.STORAGE_INCONSISTENT)
                try { derivePoReceipt(request, proof) }
                catch (_: LocalStockRejected) { throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT) }
            }
            else -> inconsistentStock()
        }
    }
}

private fun purchasingReceiptRequest(evidence: RetainedCommandEvidence, scope: BranchScope): LocalGoodsReceiptRequest {
    localStockCheck(evidence.scope == scope && evidence.command in setOf(LocalStockStoreProfile.RECEIVE,
        LocalStockStoreProfile.RECEIVE_SERIAL, LocalStockStoreProfile.OPENING, LocalStockStoreProfile.OPENING_SERIAL,
        LocalStockStoreProfile.RECEIVE_PO), LocalStockFailure.STORAGE_INCONSISTENT)
    return receiptRequestForProducer(evidence)
}

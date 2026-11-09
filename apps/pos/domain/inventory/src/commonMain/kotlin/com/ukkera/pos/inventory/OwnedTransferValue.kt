package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** One permanent value edge; physical lot/serial custody is bound by the paired stock movements. */
public data class OwnedTransferValueLink internal constructor(
    public val transferId: DomainId,
    public val allocationId: DomainId,
    public val sourcePool: StockPoolKey,
    public val destinationPool: StockPoolKey,
    public val sourceEventId: DomainId,
    public val destinationEventId: DomainId,
    public val sourceRevision: Revision,
    public val destinationRevision: Revision,
    public val quantity: Quantity,
    /** The actual source-pool carrying value removed THEN, never its original receipt price. */
    public val originalValue: Money,
)

/** Cumulative reference-minus-original import from a named incoming transfer, not an acquisition. */
public data class IncomingTransferCostTarget(public val receiptEventId: DomainId, public val targetDelta: Money)
public data class ValuationPoolRevision(public val pool: StockPoolKey, public val revision: Revision)

/** Fixed same-SKU local frontier. The adapter additionally retains exact row/page source hashes. */
public class TransferCorrectionBasis internal constructor(
    public val adjustmentId: DomainId,
    public val sourcePool: StockPoolKey,
    public val changedSource: ReceiptCostTarget,
    pools: List<ValuationPoolRevision>,
) {
    public val pools: List<ValuationPoolRevision> = frozenList(pools)
}

internal fun transferPoolOrder(key: StockPoolKey): String =
    "${key.scope.companyId.value}/${key.scope.branchId.value}/${key.skuId.value}/${key.warehouseId.value}"

internal fun valueDestinationOrder(destination: ValueDestination): String = when (destination) {
    ValueDestination.Held -> "0"
    is ValueDestination.Consumed -> "1:${destination.issueId.value}:${destination.allocationId.value}"
    is ValueDestination.Transferred -> "2:${destination.transferId.value}:${destination.allocationId.value}"
}

internal fun transferDestination(link: OwnedTransferValueLink): ValueDestination.Transferred =
    ValueDestination.Transferred(link.transferId, link.allocationId)

internal fun requireTransferLink(link: OwnedTransferValueLink) {
    guard(link.sourcePool.scope == link.destinationPool.scope && link.sourcePool.skuId == link.destinationPool.skuId &&
        link.sourcePool.ownership == StockOwnership.CompanyOwned && link.destinationPool.ownership == StockOwnership.CompanyOwned &&
        link.sourcePool.warehouseId != link.destinationPool.warehouseId && link.sourceEventId != link.destinationEventId &&
        link.sourceRevision.value >= 1 && link.destinationRevision.value >= 0,
        InventoryFailure.UNSUPPORTED_LINEAGE, "Transfer requires distinct same-company/branch owned warehouse pools")
    positive(link.quantity)
    guard(link.originalValue.currency == com.ukkera.pos.types.Currency.EGP && link.originalValue.minor >= 0,
        InventoryFailure.NEGATIVE_VALUE, "Transfer carrying value must be nonnegative")
}

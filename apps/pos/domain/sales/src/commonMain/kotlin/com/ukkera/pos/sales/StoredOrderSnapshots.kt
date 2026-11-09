package com.ukkera.pos.sales

import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Allocation identity and quantity only, never safety/admission/issue proof. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredRetailAllocation(
    val allocationId: DomainId, val portionId: DomainId, val companyId: DomainId, val branchId: DomainId,
    val skuId: DomainId, val warehouseId: DomainId, val supplierOwnerId: DomainId?,
    val lotId: DomainId?, val custody: String, val quantity: Quantity,
    /** Absent in every historical non-serial v1 encoding, not an alias for lotId. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialId: DomainId? = null,
) {
    fun restore(): StockAllocation = StockAllocation(allocationId, portionId,
        StockPositionKey(StockPoolKey(InventoryScope(companyId, branchId), skuId, warehouseId,
            supplierOwnerId?.let { StockOwnership.SupplierOwned(it) } ?: StockOwnership.CompanyOwned), lotId,
            enumValueOf<StockCustody>(custody), serialId), quantity)

    companion object {
        fun from(value: StockAllocation): StoredRetailAllocation = StoredRetailAllocation(value.allocationId, value.lineId,
            value.position.pool.scope.companyId, value.position.pool.scope.branchId, value.position.pool.skuId,
            value.position.pool.warehouseId, (value.position.pool.ownership as? StockOwnership.SupplierOwned)?.supplierId,
            value.position.lotId, value.position.custody.name, value.quantity, value.position.serialId)
    }
}

@Serializable
internal data class StoredHandoff(
    val id: DomainId, val orderId: DomainId, val previousOwnerId: DomainId, val intendedRecipientId: DomainId,
    val offeredAtRevision: Revision, val evidence: StoredCashierEvidence,
) {
    fun restore(): HandoffOffer = HandoffOffer(id, orderId, previousOwnerId, intendedRecipientId, offeredAtRevision, evidence.restore())

    companion object {
        fun from(value: HandoffOffer): StoredHandoff = StoredHandoff(value.id, value.orderId, value.previousOwnerId,
            value.intendedRecipientId, value.offeredAtRevision, StoredCashierEvidence.from(value.evidence))
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredOrder(
    val originalCreatorId: DomainId, val ownerId: DomainId, val quote: String,
    val stockRequirements: List<StoredRetailAllocation>?, val handoff: StoredHandoff?,
    val collectionId: DomainId?, val paidSaleId: DomainId?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val priceSourceReference: SalesPriceSourceReference? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val normalizedStock: List<RetailStockDemandV2>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
) {
    fun restore(binding: SalesSnapshotBinding): OwnedOrder {
        snapshotCheck((stockRequirements == null) != (normalizedStock == null))
        snapshotCheck((stockRequirements?.size ?: normalizedStock!!.size) in 1..256, SalesSnapshotFailure.TOO_LARGE)
        return OwnedOrder(binding.id, binding.scope, originalCreatorId, ownerId, binding.revision,
            StoredPricingSnapshotCodec.decode(quote), normalizedStock?.let { RetailStockRequirements.normalized(it) } ?: RetailStockRequirements(checkNotNull(stockRequirements).map { it.restore() }),
            handoff?.restore(), collectionId, paidSaleId, priceSourceReference, returnPolicySourceReference).also(::validateStoredOrder)
    }

    companion object {
        fun from(value: OwnedOrder): StoredOrder = StoredOrder(value.originalCreatorId, value.ownerId,
            StoredPricingSnapshotCodec.encode(value.quote), if (value.stockRequirements.normalized) null else value.stockRequirements.allocations.map(StoredRetailAllocation::from),
            value.handoff?.let(StoredHandoff::from), value.collectionId, value.paidSaleId, value.priceSourceReference, value.stockRequirements.normalizedLines, value.returnPolicySourceReference)
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredCollection(
    val orderId: DomainId, val lockedOrderRevision: Revision, val originalCreatorId: DomainId,
    val collectorId: DomainId, val cashSessionId: DomainId, val quote: String,
    val stockRequirements: List<StoredRetailAllocation>?, val reservationId: DomainId,
    val attempts: List<StoredPaymentAttempt>, val saleId: DomainId?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val priceSourceReference: SalesPriceSourceReference? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val normalizedStock: List<RetailStockDemandV2>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
) {
    fun restore(binding: SalesSnapshotBinding): CollectionLock {
        snapshotCheck((stockRequirements == null) != (normalizedStock == null))
        snapshotCheck((stockRequirements?.size ?: normalizedStock!!.size) in 1..256 && attempts.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        return CollectionLock(binding.id, binding.scope, orderId, lockedOrderRevision, originalCreatorId, collectorId,
            cashSessionId, StoredPricingSnapshotCodec.decode(quote), normalizedStock?.let { RetailStockRequirements.normalized(it) } ?: RetailStockRequirements(checkNotNull(stockRequirements).map { it.restore() }),
            reservationId, binding.revision, attempts.map { it.restore() }, saleId, priceSourceReference, returnPolicySourceReference).also(::validateStoredCollection)
    }

    companion object {
        fun from(value: CollectionLock): StoredCollection = StoredCollection(value.orderId, value.lockedOrderRevision,
            value.originalCreatorId, value.collectorId, value.cashSessionId, StoredPricingSnapshotCodec.encode(value.quote),
            if (value.stockRequirements.normalized) null else value.stockRequirements.allocations.map(StoredRetailAllocation::from), value.reservationId,
            value.attempts.map(StoredPaymentAttempt::from), value.saleId, value.priceSourceReference, value.stockRequirements.normalizedLines, value.returnPolicySourceReference)
    }
}

internal fun validateStoredOrder(order: OwnedOrder) {
    order.stockRequirements.validate(order.scope, order.quote)
    snapshotCheck(order.returnPolicySourceReference == null || order.stockRequirements.normalized && order.priceSourceReference != null)
    snapshotCheck(order.paidSaleId == null || order.collectionId != null)
    order.handoff?.let { handoff ->
        snapshotCheck(order.collectionId == null && order.paidSaleId == null && handoff.orderId == order.id &&
            handoff.previousOwnerId == order.ownerId && handoff.intendedRecipientId != order.ownerId &&
            handoff.offeredAtRevision == order.revision && handoff.evidence.actorId == order.ownerId)
    }
}

internal fun validateStoredCollection(collection: CollectionLock) {
    collection.stockRequirements.validate(collection.scope, collection.quote)
    snapshotCheck(collection.returnPolicySourceReference == null || collection.stockRequirements.normalized && collection.priceSourceReference != null)
    snapshotCheck(collection.lockedOrderRevision > Revision(0))
    snapshotCheck(collection.attempts.map { it.id }.distinct().size == collection.attempts.size)
    snapshotCheck(collection.attempts.map { it.originalOperationId }.distinct().size == collection.attempts.size)
    snapshotCheck(collection.revision.value >= collection.attempts.size.toLong())
    var collected = collection.quote.payable.zero()
    for ((index, attempt) in collection.attempts.withIndex()) {
        validateStoredPaymentAttempt(attempt)
        snapshotCheck(attempt.foodResolutionObservation == null && attempt.payment?.foodResolutionObservation == null)
        snapshotCheck(attempt.originalActorId == collection.collectorId && attempt.originalCashSessionId == collection.cashSessionId)
        sameCurrency(collection.quote.payable, attempt.amount)
        snapshotCheck(attempt.amount.minor <= (collection.quote.payable - collected).minor)
        snapshotCheck(!attempt.status.isUnresolved() || index == collection.attempts.lastIndex)
        attempt.payment?.let { payment ->
            snapshotCheck(payment.collectionId == collection.id)
            collected += payment.amount
        }
    }
    snapshotCheck(collected == collection.collected && collection.remainingPayable.minor >= 0L)
    snapshotCheck(collection.saleId == null || !collection.unresolved && collected == collection.quote.payable)
}

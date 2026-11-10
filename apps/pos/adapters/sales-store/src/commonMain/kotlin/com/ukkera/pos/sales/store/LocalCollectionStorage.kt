package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.store.LocalStockReadCut
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

internal data class LocalCollectionCut(val collection: LocalSalesRow<CollectionLock>, val order: LocalOrderCut, val cash: LocalCashCut)

/** Reads money/order/custody only. Outcome recording MUST NOT depend on live inventory availability. */
internal suspend fun readCollection(view: CommandReadView, id: DomainId): LocalCollectionCut {
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, id))
    val collection = SalesSnapshotCodec.decodeHistoricalCollection(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, view.scope, id, row.domainRevision()))
    if (collection.stockRequirements.normalized) requireCommerceCurrent(view, row)
    val order = readOrder(view, collection.orderId)
    val cash = LocalSalesStorage.cash(view, collection.cashSessionId)
    SalesSnapshotLinks.requireCollection(collection, order.order.value, cash.session.value)
    requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("collection", id)) == collection.orderId) { "sales.collection.source" }
    for (attempt in collection.attempts) {
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id)) == id) { "sales.collection.source" }
        if (attempt.payment != null) requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) == id) { "sales.collection.source" }
    }
    return LocalCollectionCut(LocalSalesRow(row, collection), order, cash)
}

internal fun collectionWrite(after: CollectionLock, before: StoredAggregate?): AggregateChange =
    domainWrite(LocalSalesStoreProfile.COLLECTION_KIND, after.id, after.revision, SalesSnapshotCodec.encodeCollection(after), before)

internal fun requiredStockVersions(actual: LocalStockReadCut, required: List<LocalSalesStockRevisionV1>, skuIds: Set<DomainId>): Boolean =
    required.map { it.skuId }.toSet() == skuIds && required.all { requested ->
        actual.items.singleOrNull { it.skuId == requested.skuId }?.storageRevision == requested.storageRevision
    }

internal fun requireCollectionReservation(cut: LocalCollectionCut, stock: LocalStockReadCut): StockReservation {
    val collection = cut.collection.value
    val reservation = stock.snapshot.reservation(collection.reservationId) ?: error("sales.stock.reservation")
    check(reservation.binding.orderId == collection.orderId && reservation.binding.collectionId == collection.id &&
        reservation.binding.quoteRevision == collection.quote.revision && reservation.binding.originalActorId == collection.collectorId &&
        reservation.binding.exposure != CollectionExposure.UNCOLLECTED &&
        reservation.allocations.associateBy { it.allocationId } == collection.stockRequirements.allocations.associateBy { it.allocationId }) {
        "sales.stock.binding"
    }
    return reservation
}

/**
 * Literal original sales read values for the complete retained-composite validator. They are
 * bounded per-source rows, NOT an imported inventory snapshot, authority or a replay proposal.
 * The original key/revision is checked against the command and decoded domain state by the
 * closed producer validator; the kernel retains every before/after and original operation.
 */
internal fun collectionBefore(context: CommandContext, rows: List<Pair<String, StoredAggregate>>): List<AggregateChange> =
    rows.map { (role, row) -> AggregateChange(collectionBeforeKey(context.operationId, role), null, row.schema, row.payload) }

internal fun collectionBeforeKey(operationId: OperationId, role: String): AggregateKey {
    check(role in setOf("order", "collection", "cash", "work", "price_sources", "return_policy_capture")) { "sales.composite.role" }
    val hex = sha256("ukkera.sales.collection-before/v1\u0000${operationId.value}\u0000$role".encodeToByteArray()).value
    val id = DomainId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}")
    return AggregateKey(LocalSalesStoreProfile.COLLECTION_BEFORE_KIND, id)
}

/** Actual per-ID normalized reservation; never constructs a legacy StockReservation/empty plan. */
internal suspend fun requireNormalizedCollectionReservation(collection: CollectionLock,
    header: com.ukkera.pos.inventory.InventoryReservationHeader,
    each: suspend (suspend (com.ukkera.pos.inventory.InventoryAllocationRecord) -> Unit) -> Unit) {
    requireNormalizedCollectionBinding(collection, header)
    requireRetailAllocations(checkNotNull(collection.stockRequirements.normalizedLines), header, each)
    check((collection.saleId == null) || header.status == com.ukkera.pos.inventory.ReservationStatus.ISSUED)
}

private fun requireNormalizedCollectionBinding(collection: CollectionLock,
    header: com.ukkera.pos.inventory.InventoryReservationHeader) {
    check(collection.stockRequirements.normalized && header.id == collection.reservationId &&
        header.binding == com.ukkera.pos.inventory.CollectionBinding(collection.orderId, collection.quote.revision,
            collection.id, collection.collectorId, CollectionExposure.UNKNOWN))
}

internal data class NormalizedCollectionStockRead(
    val header: com.ukkera.pos.inventory.InventoryReservationHeader,
    val read: com.ukkera.pos.inventory.store.LocalStockRelationalRead,
)

internal suspend fun normalizedCollectionStock(view: CommandReadView, stock: com.ukkera.pos.inventory.store.LocalStockRepository,
    collection: CollectionLock): com.ukkera.pos.inventory.InventoryReservationHeader =
    normalizedCollectionStockRead(view, stock, collection).header

/** Uses one checked member stream for demand totals and the caller's bounded hold projection. */
internal suspend fun normalizedCollectionStockRead(view: CommandReadView, stock: com.ukkera.pos.inventory.store.LocalStockRepository,
    collection: CollectionLock, hold: suspend (com.ukkera.pos.inventory.InventoryHoldRecord) -> Unit = { }): NormalizedCollectionStockRead {
    val read = stock.observeRelational(view)
    val allocations = RetailAllocationCheck(checkNotNull(collection.stockRequirements.normalizedLines), collection.reservationId)
    val header = checkNotNull(read.reservationWithMembers(collection.reservationId, { _, member -> allocations.observe(member) }, hold))
    requireNormalizedCollectionBinding(collection, header)
    allocations.finish(header)
    check((collection.saleId == null) || header.status == com.ukkera.pos.inventory.ReservationStatus.ISSUED)
    header.issueId?.let { id ->
        val issue = checkNotNull(read.issue(id))
        check(issue.reservationId == header.id && issue.binding == header.binding && issue.purpose == com.ukkera.pos.inventory.ValueIssuePurpose.RETAIL &&
            issue.allocationCount == header.allocationCount)
        requireRetailIssueSlices(checkNotNull(collection.stockRequirements.normalizedLines), issue) { read.issueAllocations(id, it) }
        val original = checkNotNull(read.issueOriginal(id))
        stock.requireRelationalProducer(view, original.original)
        val request = LocalCollectionStockCodecV2.decode(original.original.normalized) as? LocalCollectionStockRequestV2.Finalize
            ?: error("sales.stock.first_issue_profile")
        check(original.original.identity.producer.command == com.ukkera.pos.database.StockStreamingCommandsV2.COLLECTION &&
            original.storageGeneration == Revision(1) && original.header.revision == Revision(1) && original.header.id == id &&
            original.header.reservationId == header.id && original.header.allocationCount == issue.allocationCount &&
            original.header.originalValue == issue.originalValue && original.header.binding == issue.binding && original.header.purpose == issue.purpose &&
            request.issueId == id && request.orderId == collection.orderId && request.collectionId == collection.id &&
            request.saleId == collection.saleId && original.original.identity.producer.originalActorId == collection.collectorId)
    }
    return NormalizedCollectionStockRead(header, read)
}

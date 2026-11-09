package com.ukkera.pos.inventory.store

import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** One canonical complete item index; converting a malformed list to a set is not validation. */
internal fun requireStockItemIndex(item: StockItemWire, scope: BranchScope, skuId: DomainId) {
    localStockCheck(item.skuId == skuId && item.quantityScale in 0..6 && item.positions.isNotEmpty() &&
        item.positions.all { it.skuId == skuId } && item.pools.all { it.skuId == skuId }, LocalStockFailure.STORAGE_INCONSISTENT)
    val positions = item.positions.map { LocalStockStoreProfile.positionKey(it.key(scope)).id }
    localStockCheck(item.positions.all { it.serialId == null } || item.positions.all { it.serialId != null },
        LocalStockFailure.STORAGE_INCONSISTENT)
    val pools = item.pools.map { LocalStockStoreProfile.poolKey(it.key(scope)).id }
    localStockCheck(positions.distinct().size == positions.size && positions == positions.sortedBy { it.value } &&
        pools.distinct().size == pools.size && pools == pools.sortedBy { it.value } &&
        item.activeReservationIds.distinct().size == item.activeReservationIds.size &&
        item.activeReservationIds == item.activeReservationIds.sortedBy { it.value }, LocalStockFailure.STORAGE_INCONSISTENT)
    val owned = item.positions.map { it.key(scope).pool }.filter { it.ownership == StockOwnership.CompanyOwned }.toSet()
    localStockCheck(item.pools.map { it.key(scope) }.toSet() == owned, LocalStockFailure.STORAGE_INCONSISTENT)
}

/** Finite work/memory envelope for one loan-bound loader, including historical witnesses. */
internal class StockLoadBudget {
    private var protectedBytes: Long = 0
    private var readRows: Int = 0
    private var producers: Int = 0

    fun row(bytes: Int) {
        protectedBytes += bytes
        readRows++
        requireRemaining()
    }

    fun producer(evidence: RetainedCommandEvidence) {
        producers++
        // Count literal data and conservative framing, not only the one requested after row.
        protectedBytes += 8_192L + evidence.normalized.size + evidence.result.size +
            evidence.aggregateWrites.sumOf { it.payload.size.toLong() + 512 } +
            evidence.claimChanges.sumOf { 128L + it.key.namespace.length + it.key.key.length } +
            evidence.jobs.sumOf { it.body.size.toLong() + 128 }
        requireRemaining()
    }

    private fun requireRemaining() {
        if (protectedBytes > 64L * 1_024 * 1_024 || readRows > 16_384 || producers > 4_096)
            throw LocalStockHydrationCapacityUnavailable()
    }
}

/** Fixed immutable segmentation, with each new page namespaced to its creator pool generation. */
internal fun requireStockPoolPages(header: StockPoolWire, poolId: DomainId, generation: Revision) {
    localStockCheck((header.ownedTransferVersion == null || header.ownedTransferVersion == InventoryRecordCodec.OWNED_TRANSFER_VERSION) &&
        header.encodedBytes in 1..InventoryRecordCodec.MAX_BYTES &&
        header.pages.size in 1..LocalStockStoreProfile.MAX_POOL_PAGES &&
        header.pages.size == (header.encodedBytes - 1) / LocalStockStoreProfile.VALUE_PAGE_BYTES + 1 &&
        header.pages.map { it.id }.distinct().size == header.pages.size, LocalStockFailure.STORAGE_INCONSISTENT)
    PayloadDigest(header.encodedDigest)
    for ((ordinal, page) in header.pages.withIndex()) {
        PayloadDigest(page.digest)
        val expectedBytes = minOf(LocalStockStoreProfile.VALUE_PAGE_BYTES,
            header.encodedBytes - ordinal * LocalStockStoreProfile.VALUE_PAGE_BYTES)
        localStockCheck(page.bytes == expectedBytes && page.creationGeneration.value in 1..generation.value &&
            page.id == stockValuePageId(poolId, page.creationGeneration, ordinal, page.digest), LocalStockFailure.STORAGE_INCONSISTENT)
    }
}

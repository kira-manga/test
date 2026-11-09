package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StockRelationalKindsV2

/**
 * Version exclusion. Fresh initialization has its own complete owner-observed estate producer;
 * this gate itself cannot initialize, import, approve native coverage or reinterpret old commands.
 * Legacy migration and every broader writer/read-route join remain separate required work.
 */
internal suspend fun requireLegacyStockCurrent(view: CommandReadView) {
    // Any marker (including an invalid one) prohibits a new legacy mutation/current read. Old
    // retained-original decoding never calls this gate, so historical bytes/replay remain exact.
    if (view.aggregate(LocalStockRelationalProfileV2.activationKey(view.scope.branchId)) != null)
        unavailableProducer()
    // A markerless partial normalized installation is NOT permission to continue the old writer.
    // We need only detect one row per closed kind, not load a branch or invent a fake census.
    // Unsupported key enumeration throws through; it never becomes an empty legacy profile.
    for (kind in StockRelationalKindsV2.all.sorted()) {
        val page = view.aggregateKeys(kind, limit = 1)
        localStockCheck(page.keys.isEmpty() && page.nextAfter == null, LocalStockFailure.STORAGE_INCONSISTENT)
    }
}

internal suspend fun requireNormalizedStockCurrent(read: LocalStockRelationalRead) {
    // record() performs the actual same-loan canonical/index census, complete original producer
    // validation and genuine one-hop source crosswalk. Merely decoding a marker cannot activate.
    val row = read.record(LocalStockRelationalProfileV2.activationKey(read.scope.branchId)) ?: unavailableProducer()
    val marker = row.body as? StockRelationalBody.Activation ?: inconsistentStock()
    localStockCheck(stockActivationShape(marker), LocalStockFailure.STORAGE_INCONSISTENT)
    // Only the genuinely empty fresh producer is installed here. A legacy migration marker is
    // still unavailable until complete originals, value/restoration and all current writers join.
    localStockCheck(marker.sourceProfile == STOCK_FRESH_PROFILE_V2, LocalStockFailure.PRODUCER_UNAVAILABLE)
}

internal const val STOCK_FRESH_PROFILE_V2: String = "local.stock.fresh-empty/v2"
internal fun stockActivationShape(marker: StockRelationalBody.Activation): Boolean =
    marker.targetProfile == "local.stock.relational/v2" && when (marker.sourceProfile) {
        STOCK_FRESH_PROFILE_V2 -> marker.legacyAggregateCount == 0L && marker.normalizedAggregateCount == 2L
        "local.stock.normalized/v1" -> marker.legacyAggregateCount > 0 && marker.normalizedAggregateCount > 0
        else -> false
    }

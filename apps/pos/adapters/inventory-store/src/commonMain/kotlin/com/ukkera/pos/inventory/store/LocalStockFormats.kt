package com.ukkera.pos.inventory.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Omitted extensions preserve old bytes. Owned value lineage has its own explicit v3 profile. */
internal fun stockRecordVersion(kind: String, body: String): Int {
    // Receipt v4 explicitly retains approved-PO derivation. Old omitted extensions preserve old bytes;
    // inventory effects/pools retain their own serial/linked-transfer versions, not a global rewrite.
    val poReceipt = when (kind) {
        LocalStockStoreProfile.RECEIPT_DOCUMENT -> stockBody(body, StockReceiptWire.serializer()).poValuation != null
        LocalStockStoreProfile.POSTING -> stockBody(body, StockPostingWire.serializer()).writes.any {
            it.kind == LocalStockStoreProfile.RECEIPT_DOCUMENT && it.schema == LocalStockStoreProfile.schema(it.kind, 4) }
        else -> false
    }
    if (poReceipt) return 4
    val ownedTransfer = when (kind) {
        LocalStockStoreProfile.POOL -> stockBody(body, StockPoolWire.serializer()).ownedTransferVersion.let {
            localStockCheck(it == null || it == 3, LocalStockFailure.STORAGE_INCONSISTENT); it == 3 }
        LocalStockStoreProfile.TRANSFER_DOCUMENT -> stockBody(body, StockTransferWire.serializer()).owned != null
        LocalStockStoreProfile.POSTING -> stockBody(body, StockPostingWire.serializer()).writes.any {
            it.kind.startsWith("local.stock.") && it.schema == LocalStockStoreProfile.schema(it.kind, 3) }
        else -> false
    }
    if (ownedTransfer) return 3
    val serial = when (kind) {
        LocalStockStoreProfile.ITEM -> stockBody(body, StockItemWire.serializer()).positions.any { it.serialId != null }
        LocalStockStoreProfile.POSITION -> stockBody(body, StockPositionWire.serializer()).position.serialId != null
        LocalStockStoreProfile.RESERVATION -> stockEncodedRecordVersion(stockBody(body, StockReservationWire.serializer()).encoded) == 2
        LocalStockStoreProfile.ISSUE -> stockEncodedRecordVersion(stockBody(body, StockIssueWire.serializer()).encoded) == 2
        LocalStockStoreProfile.SERIAL_ORIGIN -> true
        LocalStockStoreProfile.RECEIPT_DOCUMENT -> stockBody(body, StockReceiptWire.serializer()).lines.any { it.request.serialId != null }
        LocalStockStoreProfile.COUNT_DOCUMENT -> stockBody(body, StockCountWire.serializer()).position.serialId != null
        LocalStockStoreProfile.INSPECTION -> stockBody(body, StockInspectionWire.serializer()).request.serialId != null
        LocalStockStoreProfile.TRANSFER_DOCUMENT -> stockBody(body, StockTransferWire.serializer()).request.source.serialId != null
        LocalStockStoreProfile.POSTING -> stockBody(body, StockPostingWire.serializer()).writes.any {
            it.kind.startsWith("local.stock.") && it.schema == LocalStockStoreProfile.schema(it.kind, 2) }
        else -> false
    }
    return if (serial) 2 else 1
}

private fun stockEncodedRecordVersion(encoded: String): Int = Json.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.int.also {
    localStockCheck(it in 1..2, LocalStockFailure.STORAGE_INCONSISTENT)
}

internal fun stockPoolPageSchema(header: StockPoolWire): String =
    LocalStockStoreProfile.schema(LocalStockStoreProfile.VALUE_PAGE, header.ownedTransferVersion ?: 1)

internal fun requireStockPoolProfile(header: StockPoolWire, pool: com.ukkera.pos.inventory.OwnedValuationPool) {
    localStockCheck(header.ownedTransferVersion == if (pool.value.hasTransferLineage) 3 else null,
        LocalStockFailure.STORAGE_INCONSISTENT)
}

internal fun stockSchema(kind: String, body: String): String = LocalStockStoreProfile.schema(kind, stockRecordVersion(kind, body))

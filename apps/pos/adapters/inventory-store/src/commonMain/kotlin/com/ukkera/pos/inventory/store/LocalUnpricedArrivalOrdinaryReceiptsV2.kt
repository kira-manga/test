package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/** Null line means a source-only partition selection, not an invented receipt-line alias. */
internal data class ArrivalOrdinaryReceiptIdentityV2(val sourceId: DomainId, val lineId: DomainId?)
internal data class ArrivalOrdinaryReceiptCutV2(val used: Boolean, val resources: Set<RecoveryResourceKey>)

private const val MAX_ARRIVAL_RECEIPT_CENSUS_V2 = 512
private const val MAX_ARRIVAL_RECEIPT_BYTES_V2 = 16_777_216L

/** Ordinary receiving's historical receiptLineClaim is keyed by sourceId. It cannot
 * establish absence of an actual lineId with a different source. Use the complete
 * genuine receipt-document membership instead; never add fabricated legacy claims. */
internal suspend fun observeArrivalOrdinaryReceiptsV2(stock: LocalStockRepository, view: CommandReadView,
    identities: List<ArrivalOrdinaryReceiptIdentityV2>): ArrivalOrdinaryReceiptCutV2 {
    require(identities.isNotEmpty())
    val rows = mutableListOf<StockReceiptWire>()
    val resources = linkedSetOf<RecoveryResourceKey>()
    var after: DomainId? = null
    var bytes = 0L
    do {
        val page = view.aggregateKeys(LocalStockStoreProfile.RECEIPT_DOCUMENT, after, 32)
        check(page.keys.size <= 32 && page.keys.distinct().size == page.keys.size &&
            page.keys.all { it.kind == LocalStockStoreProfile.RECEIPT_DOCUMENT } &&
            page.keys == page.keys.sortedBy { it.id.value } &&
            (after == null || page.keys.all { it.id.value > checkNotNull(after).value }) &&
            (page.nextAfter == null || page.nextAfter == page.keys.lastOrNull()?.id))
        for (key in page.keys) {
            if (rows.size >= MAX_ARRIVAL_RECEIPT_CENSUS_V2) throw ArrivalHydrationCapacityUnavailableV2()
            val receipt = checkNotNull(stock.record(view, key, StockReceiptWire.serializer())) { "Receipt census lost a member" }
            bytes += receipt.row.payload.size
            if (bytes > MAX_ARRIVAL_RECEIPT_BYTES_V2) throw ArrivalHydrationCapacityUnavailableV2()
            requireArrivalReceiptIdentityV2(key, receipt.value)
            check(view.claim(LocalStockStoreProfile.documentClaim(key.id)) == key.id)
            for (line in receipt.value.lines)
                check(view.claim(LocalStockStoreProfile.receiptLineClaim(line.request.sourceId)) == key.id)
            rows += receipt.value
            resources += arrivalOrdinaryReceiptResourcesV2(receipt.value)
        }
        after = page.nextAfter
    } while (after != null)
    for (identity in identities) {
        val owner = view.claim(LocalStockStoreProfile.receiptLineClaim(identity.sourceId))
        check(owner == null || rows.singleOrNull { it.receiptId == owner }?.lines?.any {
            it.request.sourceId == identity.sourceId
        } == true) { "Selected receipt source has no genuine census owner" }
    }
    return arrivalOrdinaryReceiptCutV2(identities, rows, resources)
}

/** Core-authenticated complete native BEFORE membership. Present sources must already
 * have completed their own whole stock producer join; no historical CommandReadView. */
internal suspend fun observeArrivalOrdinaryReceiptsBeforeV2(command: RuntimeHistoricalCommand,
    identities: List<ArrivalOrdinaryReceiptIdentityV2>): ArrivalOrdinaryReceiptCutV2 {
    require(identities.isNotEmpty())
    val rows = command.aggregates(LocalStockStoreProfile.RECEIPT_DOCUMENT)
    if (rows.size > MAX_ARRIVAL_RECEIPT_CENSUS_V2 || rows.sumOf { it.payload.size.toLong() } > MAX_ARRIVAL_RECEIPT_BYTES_V2)
        throw ArrivalHydrationCapacityUnavailableV2()
    val resources = linkedSetOf<RecoveryResourceKey>()
    val receipts = rows.map { row ->
        val source = checkNotNull(command.aggregateSource(row.key)) { "Original receipt census lost a member" }
        check(source.row.key == row.key && source.row.revision == row.revision && source.row.schema == row.schema &&
            source.row.payload == row.payload && source.row.digest == row.digest && sha256(row.payload.copyBytes()) == row.digest)
        val envelope = stockRecord(source.row, command.scope)
        val receipt = stockBody(envelope.body, StockReceiptWire.serializer())
        requireArrivalReceiptIdentityV2(row.key, receipt)
        check(command.claim(LocalStockStoreProfile.documentClaim(row.key.id)) == row.key.id)
        for (line in receipt.lines)
            check(command.claim(LocalStockStoreProfile.receiptLineClaim(line.request.sourceId)) == row.key.id)
        resources += arrivalOrdinaryReceiptResourcesV2(receipt)
        receipt
    }
    for (identity in identities) {
        val owner = command.claim(LocalStockStoreProfile.receiptLineClaim(identity.sourceId))
        check(owner == null || receipts.singleOrNull { it.receiptId == owner }?.lines?.any {
            it.request.sourceId == identity.sourceId
        } == true) { "Original selected receipt source has no genuine census owner" }
    }
    return arrivalOrdinaryReceiptCutV2(identities, receipts, resources)
}

private fun requireArrivalReceiptIdentityV2(key: AggregateKey, receipt: StockReceiptWire) {
    check(key.kind == LocalStockStoreProfile.RECEIPT_DOCUMENT && receipt.receiptId == key.id &&
        receipt.lines.isNotEmpty() && receipt.lines.size <= LocalStockStoreProfile.MAX_LINES_PER_COMMAND &&
        receipt.lines.map { it.request.lineId }.distinct().size == receipt.lines.size &&
        receipt.lines.map { it.request.sourceId }.distinct().size == receipt.lines.size)
    receipt.lines.forEach { it.request.restore() }
}

private fun arrivalOrdinaryReceiptResourcesV2(receipt: StockReceiptWire): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, receipt.receiptId))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, receipt.recordedBy))
    for (line in receipt.lines) {
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, line.request.lineId))
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, line.request.sourceId))
    }
}

private fun arrivalOrdinaryReceiptCutV2(identities: List<ArrivalOrdinaryReceiptIdentityV2>, receipts: List<StockReceiptWire>,
    resources: Set<RecoveryResourceKey>): ArrivalOrdinaryReceiptCutV2 {
    val sources = identities.map { it.sourceId }.toSet()
    val lines = identities.mapNotNull { it.lineId }.toSet()
    return ArrivalOrdinaryReceiptCutV2(receipts.any { receipt -> receipt.lines.any {
        it.request.sourceId in sources || it.request.lineId in lines
    } }, resources.toSet())
}

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

internal fun returnStockSchema(kind: String): String = "$kind/v1"

/** A current scalar is trusted only after its actual complete prior producer is closed. */
internal suspend fun readReturnStockState(view: CommandReadView, returnId: DomainId): LocalSalesRow<LocalReturnStockStateV1>? {
    val key = LocalReturnStockProfileV1.stateKey(returnId)
    val row = view.aggregate(key)
    val claim = view.claim(LocalReturnStockProfileV1.stateClaim(returnId))
    requireLocalSalesSource(claim == row?.let { returnId })
    if (row == null) {
        requireLocalSalesSource(view.aggregate(commerceLinkKey(key)) == null)
        return null
    }
    val value = decodeLocalSalesSource(row.payload, LocalReturnStockStateV1.serializer(), 8_192)
    requireLocalSalesSource(row.schema == returnStockSchema(key.kind) && row.digest == sha256(row.payload.copyBytes()) &&
        value.scope == view.scope && value.returnId == returnId && value.revision == row.revision && value.revision.value >= 1 &&
        value.postingCount == value.revision.value && value.postedQuantity.scaled > 0 && value.postedQuantity <= value.acceptedQuantity)
    val link = LocalSalesStorage.required(view, commerceLinkKey(key), commerceLinkSchema())
    val identity = decodeLocalSalesSource(link.payload, CommerceRowLinkV2.serializer())
    requireLocalSalesSource(identity.scope == view.scope && identity.kind == key.kind && identity.id == key.id &&
        identity.generation == row.revision && identity.digest == row.digest && identity.operationId == value.lastOperationId &&
        identity.command == LocalReturnStockProfileV1.COMMAND.name && identity.version == LocalReturnStockProfileV1.COMMAND.version)
    val actual = (view.requireStockRelational().originals.read(identity.operationId) as? RetainedOriginalV2.Streamed)?.evidence
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(actual.identity.producer == StreamedProducerIdentityV2(identity.operationId,
        LocalReturnStockProfileV1.COMMAND, identity.actorId, identity.semanticDigest))
    commerceRepository(view).requireRelationalProducer(view, actual)
    requireCommerceWrite(actual, row)
    requireCommerceWrite(actual, link)
    return LocalSalesRow(row, value)
}

internal suspend fun captureReturnStockState(sink: StreamedCommandSink, before: LocalSalesRow<LocalReturnStockStateV1>?,
    returnId: DomainId, view: CommandReadView) {
    val row = before?.stored
    if (row == null) sink.source(StreamedSourceObservationV2.CurrentAbsent(LocalReturnStockProfileV1.stateKey(returnId)))
    else {
        val link = decodeLocalSalesSource(LocalSalesStorage.required(view, commerceLinkKey(row.key), commerceLinkSchema()).payload,
            CommerceRowLinkV2.serializer())
        sink.source(StreamedSourceObservationV2.Aggregate(row.key, row.revision, row.schema, row.payload, row.digest,
            StreamedProducerIdentityV2(link.operationId, LocalReturnStockProfileV1.COMMAND, link.actorId, link.semanticDigest)))
    }
}

internal fun requireReturnStockIssue(cut: LocalReturnStockAcceptanceCut, issue: LocalStockIssueHeader,
    first: LocalStockIssueOriginal) {
    val sale = cut.sale.sale.value
    requireLocalSalesSource(issue.id == sale.stockIssueId && issue.purpose == ValueIssuePurpose.RETAIL &&
        issue.binding.orderId == sale.orderId && issue.binding.quoteRevision == sale.quote.revision &&
        issue.binding.originalActorId == sale.collectorId && first.header.id == issue.id && first.storageGeneration == Revision(1) &&
        first.original.identity == cut.saleProducer.identity && first.original.journalHash == cut.saleProducer.journalHash)
}

internal fun returnStockInventoryRequest(posting: LocalReturnStockPostingV1): LocalStockRetailReturnRestoration =
    LocalStockRetailReturnRestoration(posting.request.restorationId, posting.after.returnId, posting.after.saleId,
        posting.after.portionId, posting.after.issueId, posting.request.expectedIssueRevision, posting.quantity,
        posting.request.selections.map { LocalStockRetailReturnSelection(it.allocationId, it.quantity,
            if (it.custody == LocalReturnStockCustodyV1.RELEASED) StockCustody.RELEASED else StockCustody.QUARANTINE) })

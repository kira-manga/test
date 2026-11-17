package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.BusinessOriginalAggregate
import com.ukkera.pos.database.BusinessOriginalProgram
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.NativeBusinessOriginalProgram
import com.ukkera.pos.database.StreamedProducerIdentityV2
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Actual native BEFORE original-sale/current-entitlement evidence only, NOT customer
 * delivery evidence. Every present row has its exact earlier WHOLE-joined native original;
 * no historical CommandReadView, copied producer, current-policy default or rights source
 * is constructed. Advanced normalized sources require every appended contribution. */
internal suspend fun findReturnCaseHistoryEntitlement(command: RuntimeHistoricalCommand,
    saleId: DomainId, sources: LocalReturnCaseHistorySources = LocalReturnCaseHistorySources(command)): LocalEntitlementCut? =
    findReturnCaseHistoryEntitlement(NativeBusinessOriginalProgram(command), saleId, sources)

internal suspend fun findReturnCaseHistoryEntitlement(command: BusinessOriginalProgram,
    saleId: DomainId, sources: LocalReturnCaseHistorySources = LocalReturnCaseHistorySources(command)): LocalEntitlementCut? {
    val saleKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId)
    if (command.aggregate(saleKey) == null) {
        // Preserve true absence versus a partial/damaged retained receipt, exactly as live.
        check(command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("sale", saleId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("return_entitlement", saleId)) == null &&
            command.aggregate(LocalReturnEntitlementProvenanceProfile.locator(saleId)) == null &&
            command.claim(LocalReturnEntitlementProvenanceProfile.locatorClaim(saleId)) == null)
        return null
    }
    val (sale, finalized) = returnCaseHistorySaleSources(command, saleId)
    val entitlementSource = returnCaseHistoryReceiptRequired(command,
        LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId))
    val row = entitlementSource.row
    val entitlementOriginal = entitlementSource.original
    check(row.schema == LocalSalesStoreProfile.schema(row.key.kind))
    val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, command.scope, saleId, row.domainRevision()))
    check(SalesSnapshotCodec.encodePaidSale(entitlement.sale) == SalesSnapshotCodec.encodePaidSale(sale.sale.value))
    val normalized = if (row.revision == Revision(1)) {
        check(entitlementSource.original === finalized &&
            command.aggregate(LocalReturnEntitlementProvenanceProfile.locator(saleId)) == null &&
            command.claim(LocalReturnEntitlementProvenanceProfile.locatorClaim(saleId)) == null)
        returnCaseHistoryReceiptCommerceLink(command, entitlementSource)
        initialReturnEntitlementSourceCut(sale, row, finalized.evidence)
    } else if (entitlementOriginal is RetainedOriginalV2.Legacy &&
        entitlementOriginal.evidence.command == LocalSalesStoreProfile.RETURN_COMMAND) {
        // aggregateSource supplied this exact earlier WHOLE-joined writer. Old ordinary
        // acceptance never wrote a CASE locator/contribution; do not invent one from it.
        val original = entitlementOriginal.evidence
        val request = LocalReturnCommandCodec.decode(original.normalized) as? LocalReturnRequestV1.Accept
            ?: returnCaseHistoryReceiptUnavailable()
        check(original.scope == command.scope && original.committed && original.nativeOriginalProvenance == null &&
            request.saleId == saleId && request.expectedEntitlementRevision.next() == entitlement.revision &&
            original.aggregateWrites.singleOrNull { it.key == row.key }?.let {
                it.storageGeneration == row.revision && it.schema == row.schema &&
                    it.payload == row.payload && it.payloadDigest == row.digest
            } == true && command.aggregate(LocalReturnEntitlementProvenanceProfile.locator(saleId)) == null &&
            command.claim(LocalReturnEntitlementProvenanceProfile.locatorClaim(saleId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("return_claim", request.claimId)) == saleId &&
            command.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId)) == saleId &&
            command.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId)) == request.claimId &&
            command.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.claimId)) == request.returnId)
        null // Proven old-format remaining rights, never a new appendable CASE chain.
    } else readReturnCaseHistoryLinkedEntitlement(command, sale, entitlementSource, finalized, sources)
    return LocalEntitlementCut(sale.sale, LocalSalesRow(row, entitlement), sale.returnPolicyCapture, sale.priceSources, normalized)
}

/** A prior pending qualification's immutable witness is compared to the real SALE source,
 * independently of today's entitlement. It is not requalified against a later rights row. */
internal suspend fun readReturnCaseHistorySaleSource(command: RuntimeHistoricalCommand,
    saleId: DomainId): LocalReturnSaleCut = readReturnCaseHistorySaleSource(NativeBusinessOriginalProgram(command), saleId)

internal suspend fun readReturnCaseHistorySaleSource(command: BusinessOriginalProgram,
    saleId: DomainId): LocalReturnSaleCut = returnCaseHistorySaleSources(command, saleId).first

private suspend fun returnCaseHistorySaleSources(command: BusinessOriginalProgram,
    saleId: DomainId): Pair<LocalReturnSaleCut, RetainedOriginalV2.Streamed> {
    val saleSource = returnCaseHistoryReceiptRequired(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId))
    val saleRow = saleSource.row
    check(saleRow.revision == Revision(1) && saleRow.schema == LocalSalesStoreProfile.schema(saleRow.key.kind))
    val sale = SalesSnapshotCodec.decodePaidSale(saleRow.text(),
        SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, command.scope, saleId, saleRow.domainRevision()))
    val finalized = saleSource.original as? RetainedOriginalV2.Streamed ?: returnCaseHistoryReceiptUnavailable()
    check(finalized.evidence.identity.scope == command.scope &&
        finalized.evidence.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
    val request = LocalCollectionStockCodecV2.decode(finalized.evidence.normalized) as? LocalCollectionStockRequestV2.Finalize
        ?: returnCaseHistoryReceiptUnavailable()
    check(request.saleId == sale.id && request.orderId == sale.orderId && request.issueId == sale.stockIssueId)
    returnCaseHistoryReceiptCommerceLink(command, saleSource)

    val priceReference = sale.priceSourceReference ?: returnCaseHistoryReceiptUnavailable()
    val priceSource = returnCaseHistoryReceiptRequired(command,
        LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, priceReference.id))
    check(priceSource.row.revision == Revision(1) && priceSource.row.schema == LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA)
    val prices = requirePriceSources(priceSource.row.payload, command.scope, sale.orderId, sale.quote, priceReference)
    check(prices.normalizedStock)
    val quoted = command.earlierOriginal(prices.originalQuoteOperationId) as? RetainedOriginalV2.Streamed
        ?: returnCaseHistoryReceiptUnavailable()
    check(priceSource.original === quoted)
    requireNormalizedPaymentQuoteRequest(quoted.evidence, sale.orderId, prices.quoteRevision)
    requireCommerceWrite(quoted.evidence, priceSource.row)

    val capture = sale.returnPolicySourceReference?.let { reference ->
        val source = returnCaseHistoryReceiptRequired(command, AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id))
        check(source.row.revision == Revision(1) && source.row.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA &&
            source.original === quoted)
        val value = requireReturnPolicyCapture(source.row.payload, command.scope, sale.orderId, sale.quote, reference, prices.sources)
        check(value.originalQuoteOperationId == prices.originalQuoteOperationId && value.id == prices.id)
        requireNormalizedPaymentQuoteRequest(quoted.evidence, sale.orderId, value.quoteRevision)
        requireCommerceWrite(quoted.evidence, source.row)
        LocalSalesRow(source.row, value)
    }
    check(command.claim(LocalSalesStoreProfile.unique("sale", saleId)) == sale.orderId &&
        command.claim(LocalSalesStoreProfile.unique("sale_order", sale.orderId)) == saleId &&
        command.claim(LocalSalesStoreProfile.unique("return_entitlement", saleId)) == saleId)
    // The exact whole quote already joined its original publication/capture obligations.
    // Absence of this immutable companion remains absence; no current policy is backfilled.
    return LocalReturnSaleCut(LocalSalesRow(saleRow, sale), LocalSalesRow(priceSource.row, prices), capture) to finalized
}

/** Exact immutable sale OR initial-entitlement link from the SAME genuine Finalize. */
private suspend fun returnCaseHistoryReceiptCommerceLink(command: BusinessOriginalProgram,
    source: BusinessOriginalAggregate) {
    val paired = returnCaseHistoryReceiptRequired(command, commerceLinkKey(source.row.key))
    check(paired.row.revision == Revision(1) && paired.row.schema == commerceLinkSchema() &&
        paired.original === source.original && sha256(paired.row.payload.copyBytes()) == paired.row.digest)
    val link = decodeLocalSalesSource(paired.row.payload, CommerceRowLinkV2.serializer())
    val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: returnCaseHistoryReceiptUnavailable()
    check(original.identity.scope == command.scope && original.identity.producer.command == StockStreamingCommandsV2.COLLECTION &&
        link.scope == command.scope && link.kind == source.row.key.kind && link.id == source.row.key.id &&
        link.generation == source.row.revision && link.digest == source.row.digest &&
        original.identity.producer == StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version),
            link.actorId, link.semanticDigest))
    requireCommerceWrite(original, source.row)
    requireCommerceWrite(original, paired.row)
}

private suspend fun returnCaseHistoryReceiptRequired(command: BusinessOriginalProgram,
    key: AggregateKey): BusinessOriginalAggregate = command.aggregateSource(key) ?: returnCaseHistoryReceiptUnavailable()

private fun returnCaseHistoryReceiptUnavailable(): Nothing = error("sales.return.case_history.receipt_source_unavailable")

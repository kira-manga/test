package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StreamedProducerIdentityV2
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Genuine immutable normalized sale and initial Finalize AFTER, selected by native history.
 * Today's remaining entitlement never supplies sold quantities or a receipt instant. Null
 * means this original profile cannot be joined, not proof of an absent sale or non-delivery.
 */
internal suspend fun readRetailCustomerReceiptHistoryOriginal(command: RuntimeHistoricalCommand,
    saleId: DomainId): LocalRetailCustomerReceiptOriginalSource? {
    val saleKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId)
    val source = command.aggregateSource(saleKey) ?: return null
    val finalized = source.original as? RetainedOriginalV2.Streamed ?: return null
    if (source.row.revision != Revision(1) || source.row.schema != LocalSalesStoreProfile.schema(saleKey.kind) ||
        finalized.evidence.identity.producer.command != StockStreamingCommandsV2.COLLECTION) return null
    val request = LocalCollectionStockCodecV2.decode(finalized.evidence.normalized) as? LocalCollectionStockRequestV2.Finalize
        ?: return null
    check(finalized.evidence.identity.scope == command.scope && request.saleId == saleId &&
        command.earlierOriginal(finalized.evidence.identity.producer.operationId) === finalized)

    // This shared native source reader closes exact SALE/commerce-link/quote/capture origins.
    // It is an immutable source cut, not an entitlement or historical CommandReadView.
    val sale = readReturnCaseHistorySaleSource(command, saleId)
    check(sale.sale.stored.key == source.row.key && sale.sale.stored.revision == source.row.revision &&
        sale.sale.stored.schema == source.row.schema && sale.sale.stored.payload == source.row.payload &&
        sale.sale.stored.digest == source.row.digest && request.orderId == sale.sale.value.orderId &&
        request.issueId == sale.sale.value.stockIssueId)

    val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId)
    // writeRow is core's actual complete original-stream projection, never a supplied row.
    val initial = finalized.evidence.writeRow(key) ?: error("sales.customer_receipt.history_initial_entitlement_missing")
    check(initial.key == key && initial.revision == Revision(1) && initial.schema == LocalSalesStoreProfile.schema(key.kind))
    val entitlement = validateLocalSalesSource {
        SalesSnapshotCodec.decodeReceiptEntitlement(initial.text(),
            SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, command.scope, saleId, Revision(0)))
    }
    val linkRow = finalized.evidence.writeRow(commerceLinkKey(key))
        ?: error("sales.customer_receipt.history_initial_entitlement_link_missing")
    check(linkRow.key == commerceLinkKey(key) && linkRow.revision == Revision(1) &&
        linkRow.schema == commerceLinkSchema() && sha256(linkRow.payload.copyBytes()) == linkRow.digest)
    val link = decodeLocalSalesSource(linkRow.payload, CommerceRowLinkV2.serializer())
    check(link.scope == command.scope && link.kind == initial.key.kind && link.id == initial.key.id &&
        link.generation == initial.revision && link.digest == initial.digest && finalized.evidence.identity.producer ==
            StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest))
    requireCommerceWrite(finalized.evidence, linkRow)
    return retailCustomerReceiptOriginalSource(sale, LocalSalesRow(initial, entitlement), finalized.evidence)
}

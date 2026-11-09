package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.RetailStockDemandV2
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.SalesPriceSourceReference
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** One insert-only companion per actually selected quote; never an optional live-price lookup. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class LocalSalesPriceSourcesV2(
    val scope: LocalSalesScope, val id: DomainId, val orderId: DomainId,
    val quoteRevision: Revision, val quoteDigest: PayloadDigest, val originalQuoteOperationId: OperationId,
    val profile: LocalSalesPriceSourceProfile, val sources: List<LocalSalesPriceSourceV2>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val normalizedStock: Boolean = false,
)

internal data class LocalPreparedPriceSources(val reference: SalesPriceSourceReference, val change: AggregateChange) {
    val revision: LocalSalesRevision get() = LocalSalesRevision(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id, Revision(0))
}

internal fun preparePriceSources(context: CommandContext, priced: LocalSalesQuotedOrder): LocalPreparedPriceSources? {
    if (priced.priceSourceProfile == LocalSalesPriceSourceProfile.HISTORICAL_COMPANY_ONLY_V1) {
        check(priced.priceSources.isEmpty()) { "sales.price_sources.legacy" }
        return null
    }
    val id = DomainId(context.operationId.value)
    val value = LocalSalesPriceSourcesV2(LocalSalesScope.from(context.scope), id, priced.quote.snapshotId,
        priced.quote.revision, priceQuoteDigest(priced.quote), context.operationId, priced.priceSourceProfile,
        priced.priceSources.sortedBy { it.portionId.value }, priced.stockRequirements.normalized)
    val payload = localSalesEncode(value, LocalSalesPriceSourcesV2.serializer())
    val reference = SalesPriceSourceReference(id, sha256(payload.copyBytes()))
    requirePriceSources(payload, context.scope, value.orderId, priced.quote, reference, if (priced.stockRequirements.normalized) null else priced.stockRequirements.allocations, priced.stockRequirements.normalizedLines)
    return LocalPreparedPriceSources(reference, AggregateChange(
        LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, id), null,
        LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA, payload))
}

/** Pure source-link validation, shared by current loaders and literal composite witnesses. */
internal fun requirePriceSources(payload: StoredBytes, scope: BranchScope, orderId: DomainId,
    quote: PricingSnapshot, reference: SalesPriceSourceReference, allocations: List<StockAllocation>? = null, normalizedLines: List<RetailStockDemandV2>? = null): LocalSalesPriceSourcesV2 {
    val value = decodeLocalSalesSource(payload, LocalSalesPriceSourcesV2.serializer())
    requireLocalSalesSource(value.scope.value() == scope && value.id == reference.id && sha256(payload.copyBytes()) == reference.digest &&
        value.id == DomainId(value.originalQuoteOperationId.value) && value.orderId == orderId && quote.snapshotId == orderId &&
        value.quoteRevision == quote.revision && value.quoteDigest == priceQuoteDigest(quote) &&
        value.profile == LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2 && value.sources.size in 1..256 &&
        value.sources.map { it.portionId }.distinct().size == value.sources.size &&
        value.sources == value.sources.sortedBy { it.portionId.value } && value.sources.size == quote.lines.size &&
        value.sources.map { it.portionId }.toSet() == quote.lines.map { it.portionId }.toSet())
    requireLocalSalesSource(normalizedLines == null || value.normalizedStock)
    requireLocalSalesSource(allocations == null || !value.normalizedStock)
    val sources = value.sources.associateBy { it.portionId }
    for (line in quote.lines) {
        val source = sources.getValue(line.portionId)
        requireLocalSalesSource(source.scope == scope && source.lineId == line.lineId &&
            source.companyPriceRevision == line.priceRevision && source.effectiveUnitPrice == line.unitPrice)
        if (normalizedLines != null) requireLocalSalesSource(normalizedLines.single { it.portionId == line.portionId }.let { it.skuId == source.skuId && it.quantity == line.quantity })
        if (allocations != null) requireLocalSalesSource(allocations.filter { it.lineId == line.portionId }
            .map { it.position.pool.skuId }.toSet() == setOf(source.skuId))
    }
    return value
}

/**
 * Only original quote/selection/allocations and the original retained producer are consulted.
 * Hash/provenance consistency is not historical authorization or recovery frontier proof.
 * Never wrap transaction IO exceptions as a clean missing/corrupt source.
 */
internal suspend fun readPriceSources(view: CommandReadView, orderId: DomainId, quote: PricingSnapshot,
    reference: SalesPriceSourceReference?, allocations: List<StockAllocation>? = null,
    normalizedLines: List<RetailStockDemandV2>? = null): LocalSalesRow<LocalSalesPriceSourcesV2>? {
    if (reference == null) return null // Explicit historical snapshot profile, not inferred v2 authority.
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id),
        LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA)
    requireLocalSalesSource(row.revision == Revision(1))
    val value = requirePriceSources(row.payload, view.scope, orderId, quote, reference, allocations, normalizedLines)
    if (value.normalizedStock) {
        val original = (view.requireStockRelational().originals.read(value.originalQuoteOperationId) as? RetainedOriginalV2.Streamed)?.evidence
            ?: error("sales.stock.quote_original_missing")
        commerceRepository(view).requireRelationalProducer(view, original)
        requireCommerceWrite(original, row)
        check(original.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
        when (val request = LocalCollectionStockCodecV2.decode(original.normalized)) {
            is LocalCollectionStockRequestV2.CreateOrder -> check(request.orderId == orderId && value.quoteRevision == Revision(0))
            is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> check(request.orderId == orderId && value.quoteRevision.value > 0)
            else -> error("sales.stock.quote_original_request_mismatch")
        }
        if (normalizedLines == null) {
            // Price/quote evidence belongs to the immutable paid sale, not today's advanced
            // entitlement. Its CURRENT writer is checked separately by readEntitlement.
            // A CASE acceptance must never be forced through the stale Finalize rights link.
            val saleId = checkNotNull(view.claim(LocalSalesStoreProfile.unique("sale_order", orderId)))
            val saleRow = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId))
            val sale = SalesSnapshotCodec.decodePaidSale(saleRow.text(), SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, view.scope, saleId, saleRow.domainRevision()))
            check(sale.orderId == orderId && sale.priceSourceReference == reference && priceQuoteDigest(sale.quote) == value.quoteDigest)
            requireNormalizedReturnSaleSource(view, saleRow, sale, value)
        }
        return LocalSalesRow(row, value)
    }
    val producer = view.retainedCommand(value.originalQuoteOperationId)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    validateLocalSalesSource { requirePriceSourcesProducer(producer, row.payload, reference, value, quote) }
    return LocalSalesRow(row, value)
}

private fun requirePriceSourcesProducer(producer: RetainedCommandEvidence, payload: StoredBytes,
    reference: SalesPriceSourceReference, sources: LocalSalesPriceSourcesV2, quote: PricingSnapshot) {
    requireLocalSalesSource(producer.committed && producer.scope == sources.scope.value() && producer.jobs.isEmpty() &&
        producer.operationId == sources.originalQuoteOperationId && producer.command == LocalSalesStoreProfile.ORDER_COMMAND &&
        semanticDigest(producer.scope, producer.originalActorId, producer.command, producer.normalized) == producer.semanticDigest &&
        LocalOrderCommandCodec.normalize(producer.normalized) == producer.normalized)
    val request = LocalOrderCommandCodec.decode(producer.normalized)
    val selection: LocalOrderSelectionV1
    val revision: Revision
    val action: LocalSalesAction
    when (request) {
        is LocalOrderRequestV1.Create -> {
            requireLocalSalesSource(request.orderId == sources.orderId && sources.quoteRevision == Revision(0))
            selection = request.selection; revision = Revision(0); action = LocalSalesAction.ORDER_CREATED
        }
        is LocalOrderRequestV1.ReplaceUnlocked -> {
            requireLocalSalesSource(request.orderId == sources.orderId && sources.quoteRevision.value > 0)
            selection = request.selection; revision = request.expectedRevision.next(); action = LocalSalesAction.ORDER_EDITED
        }
        else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    }
    val sourceKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id)
    val sourceRow = producer.aggregateWrites.singleOrNull { it.key == sourceKey }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(sourceRow.storageGeneration == Revision(1) && sourceRow.schema == LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA &&
        sourceRow.payload == payload && sourceRow.payloadDigest == reference.digest)
    val orderKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, sources.orderId)
    val orderRow = producer.aggregateWrites.singleOrNull { it.key == orderKey }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(orderRow.schema == LocalSalesStoreProfile.schema(orderKey.kind) && orderRow.storageGeneration == revision.next())
    val order = SalesSnapshotCodec.decodeOrder(orderRow.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, producer.scope, sources.orderId, revision))
    requireLocalSalesSource(order.priceSourceReference == reference && order.ownerId == producer.originalActorId &&
        order.collectionId == null && order.paidSaleId == null && order.handoff == null &&
        StoredPricingSnapshotCodec.encode(order.quote) == StoredPricingSnapshotCodec.encode(quote))
    requirePriceSources(payload, producer.scope, order.id, order.quote, reference, order.stockRequirements.allocations)
    requireSelectedQuote(selection, order.quote, order.id, sources.quoteRevision)
    val contextKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, order.id)
    val contextRow = producer.aggregateWrites.singleOrNull { it.key == contextKey }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(contextRow.schema == LocalSalesStoreProfile.schema(contextKey.kind) && contextRow.storageGeneration == sources.quoteRevision.next())
    val context = decodeLocalSalesSource(contextRow.payload, LocalOrderContext.serializer())
    requireLocalSalesSource(context == LocalOrderContext(sources.scope, order.id, sources.quoteRevision, sources.quoteRevision, selection))
    val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, order.id, revision))
    val keys = mutableSetOf(sourceKey, orderKey, contextKey)
    if (request is LocalOrderRequestV1.Create) {
        requireLocalSalesSource(order.originalCreatorId == producer.originalActorId)
        val workKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, producer.originalActorId)
        val workRow = producer.aggregateWrites.singleOrNull { it.key == workKey }
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        val work = decodeLocalSalesSource(workRow.payload, LocalWorkState.serializer())
        requireLocalSalesSource(workRow.schema == LocalSalesStoreProfile.schema(workKey.kind) && workRow.storageGeneration == work.revision.next() &&
            work.scope == sources.scope && work.id == producer.originalActorId && order.id in work.orderIds)
        revisions += LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)
        keys += workKey
        requireLocalSalesSource(producer.claimChanges.size == 1 && producer.claimChanges.single().let {
            it.action == RetainedClaimAction.ACQUIRE && it.key == LocalSalesStoreProfile.unique("order", order.id) && it.ownerId == order.id
        })
    } else requireLocalSalesSource(producer.claimChanges.isEmpty())
    revisions += LocalSalesRevision(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id, Revision(0))
    requireLocalSalesSource(producer.aggregateWrites.size == keys.size && producer.aggregateWrites.map { it.key }.toSet() == keys &&
        producer.result == LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(action, order.id, revisions, order.quote.payable)))
}

internal fun priceSourcesResources(reference: SalesPriceSourceReference?): Set<RecoveryResourceKey> =
    reference?.let { setOf(orderResource(it.id)) } ?: emptySet()

internal fun priceSourcesBefore(order: LocalOrderCut): List<Pair<String, com.ukkera.pos.database.StoredAggregate>> =
    listOfNotNull(order.priceSources?.let { "price_sources" to it.stored })

private fun priceQuoteDigest(quote: PricingSnapshot): PayloadDigest =
    sha256(StoredPricingSnapshotCodec.encode(quote).encodeToByteArray(throwOnInvalidSequence = true))

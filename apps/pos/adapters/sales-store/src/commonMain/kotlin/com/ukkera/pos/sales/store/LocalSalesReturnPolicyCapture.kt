package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.RetailStockDemandV2
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesReturnPolicySourceReference
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Absence is the exact original signed SKU's absence, never today's default return policy. */
@Serializable
public enum class LocalSalesReturnPromiseStateV2 { SIGNED_PRESENT, SIGNED_ABSENT }

/** Original commercial terms. No legal applicability, receipt date or eligibility is derived. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalSalesReturnPromiseDescriptorV2(
    public val policyId: DomainId, public val policyRevision: Revision,
    public val categoryCode: String, public val promiseDigest: PayloadDigest,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val changeOfMindReceiptWindowDays: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val defectiveReceiptWindowDays: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val defectiveResolutionDays: Int? = null,
) {
    init {
        require(changeOfMindReceiptWindowDays == null || changeOfMindReceiptWindowDays >= 14)
        require((defectiveReceiptWindowDays == null) == (defectiveResolutionDays == null))
        require(defectiveReceiptWindowDays == null || defectiveReceiptWindowDays >= 30 && defectiveResolutionDays in 1..7)
    }
}

/** One deduplicated exact immutable signed publication and its genuine branch install producer. */
@Serializable
public data class LocalSalesReturnPublicationV2(
    public val skuId: DomainId, public val skuRevision: Revision, public val signingKeyId: DomainId,
    public val source: LocalReturnCaseSourceReferenceV1,
    public val state: LocalSalesReturnPromiseStateV2,
    public val promise: LocalSalesReturnPromiseDescriptorV2?,
) {
    public val publicationId: DomainId get() = source.id
}

@Serializable
public data class LocalSalesReturnPortionCaptureV2(
    public val lineId: DomainId, public val portionId: DomainId, public val publicationId: DomainId,
)

/** Trusted catalogue output only; RPC selectors never supply an original policy capture. */
@Serializable
public data class LocalSalesReturnPolicyCaptureInputV2(
    public val portions: List<LocalSalesReturnPortionCaptureV2>,
    public val publications: List<LocalSalesReturnPublicationV2>,
)

/** Insert-only NEW normalized quote companion. Mixed baskets retain explicit signed absence. */
@Serializable
public data class LocalSalesReturnPolicyCaptureV2(
    public val scope: BranchScope, public val id: DomainId, public val orderId: DomainId,
    public val quoteRevision: Revision, public val quoteDigest: PayloadDigest,
    public val originalQuoteOperationId: OperationId,
    public val input: LocalSalesReturnPolicyCaptureInputV2,
    public val profile: String = "LOCAL_RETAIL_ORIGINAL_PROMISE_V2",
)

public object LocalSalesReturnPolicyCaptureCodecV2 {
    public const val KIND: String = LocalSalesReturnPolicyCaptureCodecV1.KIND
    public const val SCHEMA: String = "sales.return.sale-policy/v2"
    // StoredBytes' real global ceiling. Do not advertise a larger nominal codec/frame budget.
    public const val MAX_BYTES: Int = 65_536
    public fun encode(value: LocalSalesReturnPolicyCaptureV2): StoredBytes {
        require(value.profile == "LOCAL_RETAIL_ORIGINAL_PROMISE_V2" &&
            value.id.value == value.originalQuoteOperationId.value) { "sales.return.policy_capture" }
        requireReturnPolicyInput(value.input)
        return localSalesEncode(value, LocalSalesReturnPolicyCaptureV2.serializer(), MAX_BYTES)
    }
    public fun decode(body: StoredBytes): LocalSalesReturnPolicyCaptureV2 =
        localSalesDecode(body, LocalSalesReturnPolicyCaptureV2.serializer(), MAX_BYTES).also { require(encode(it) == body) }
}

internal fun requireReturnPolicyInput(value: LocalSalesReturnPolicyCaptureInputV2) {
    require(value.portions.size in 1..256 && value.publications.size in 1..256 &&
        value.portions == value.portions.sortedBy { it.portionId.value } &&
        value.publications == value.publications.sortedBy { it.publicationId.value } &&
        value.portions.map { it.portionId }.distinct().size == value.portions.size &&
        value.publications.map { it.publicationId }.distinct().size == value.publications.size &&
        value.publications.map { it.skuId }.distinct().size == value.publications.size &&
        value.portions.map { it.publicationId }.toSet() == value.publications.map { it.publicationId }.toSet()) {
        "sales.return.policy_capture"
    }
    require(value.publications.any { it.state == LocalSalesReturnPromiseStateV2.SIGNED_PRESENT })
    for (publication in value.publications) {
        val source = publication.source
        require(source.kind == "local.catalog.publication" && source.schema == "local.catalog.publication/v1" &&
            source.storageRevision == Revision(1) && source.producerCommand == "local.configuration.write" &&
            source.producerVersion == 1 && (publication.state == LocalSalesReturnPromiseStateV2.SIGNED_PRESENT) == (publication.promise != null))
        publication.promise?.let { promise ->
            require(promise.categoryCode.length in 1..128 && promise.categoryCode.all { it.isLetterOrDigit() || it in "._-" })
        }
    }
}

internal data class LocalPreparedReturnPolicyCapture(
    val reference: SalesReturnPolicySourceReference, val change: AggregateChange,
) {
    val revision: LocalSalesRevision get() = LocalSalesRevision(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id, Revision(0))
}

internal fun prepareReturnPolicyCapture(context: CommandContext, quoted: LocalSalesQuotedOrder): LocalPreparedReturnPolicyCapture? {
    val input = quoted.returnPolicyCapture ?: return null
    check(quoted.stockRequirements.normalized && quoted.priceSourceProfile == LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2)
    val id = DomainId(context.operationId.value)
    val value = LocalSalesReturnPolicyCaptureV2(context.scope, id, quoted.quote.snapshotId, quoted.quote.revision,
        returnPolicyQuoteDigest(quoted.quote), context.operationId, input)
    val payload = LocalSalesReturnPolicyCaptureCodecV2.encode(value)
    val reference = SalesReturnPolicySourceReference(id, sha256(payload.copyBytes()))
    requireReturnPolicyCapture(payload, context.scope, value.orderId, quoted.quote, reference,
        quoted.priceSources, quoted.stockRequirements.normalizedLines)
    return LocalPreparedReturnPolicyCapture(reference, AggregateChange(AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, id),
        null, LocalSalesReturnPolicyCaptureCodecV2.SCHEMA, payload))
}

/** Complete per-portion quote/price/stock projection. Missing or absent promise is not eligibility. */
internal fun requireReturnPolicyCapture(payload: StoredBytes, scope: BranchScope, orderId: DomainId,
    quote: PricingSnapshot, reference: SalesReturnPolicySourceReference, prices: List<LocalSalesPriceSourceV2>,
    normalizedLines: List<RetailStockDemandV2>? = null): LocalSalesReturnPolicyCaptureV2 {
    val value = validateLocalSalesSource { LocalSalesReturnPolicyCaptureCodecV2.decode(payload) }
    requireLocalSalesSource(value.scope == scope && value.id == reference.id && sha256(payload.copyBytes()) == reference.digest &&
        value.orderId == orderId && quote.snapshotId == orderId && value.quoteRevision == quote.revision &&
        value.quoteDigest == returnPolicyQuoteDigest(quote) && prices.size == quote.lines.size &&
        value.input.portions.size == quote.lines.size && value.input.portions.map { it.portionId }.toSet() == quote.lines.map { it.portionId }.toSet())
    val publications = value.input.publications.associateBy { it.publicationId }
    for (portion in value.input.portions) {
        val publication = publications.getValue(portion.publicationId)
        val line = quote.lines.single { it.portionId == portion.portionId }
        val price = prices.singleOrNull { it.portionId == portion.portionId }
        requireLocalSalesSource(price != null && portion.lineId == line.lineId && price.lineId == line.lineId &&
            price.scope == scope && price.skuId == publication.skuId && price.companyPublicationId == publication.publicationId)
        if (normalizedLines != null) requireLocalSalesSource(normalizedLines.single { it.portionId == portion.portionId }.let {
            it.skuId == publication.skuId && it.quantity == line.quantity
        })
    }
    return value
}

/** Uses the existing whole original commerce producer, not a current policy or fake historical view. */
internal suspend fun readReturnPolicyCapture(view: CommandReadView, orderId: DomainId, quote: PricingSnapshot,
    reference: SalesReturnPolicySourceReference?, prices: List<LocalSalesPriceSourceV2>,
    normalizedLines: List<RetailStockDemandV2>? = null): LocalSalesRow<LocalSalesReturnPolicyCaptureV2>? {
    if (reference == null) return null
    val row = LocalSalesStorage.required(view, AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id),
        LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
    requireLocalSalesSource(row.revision == Revision(1))
    val value = requireReturnPolicyCapture(row.payload, view.scope, orderId, quote, reference, prices, normalizedLines)
    val original = (view.requireStockRelational().originals.read(value.originalQuoteOperationId) as? RetainedOriginalV2.Streamed)?.evidence
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(original.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
    commerceRepository(view).requireRelationalProducer(view, original)
    requireCommerceWrite(original, row)
    when (val request = LocalCollectionStockCodecV2.decode(original.normalized)) {
        is LocalCollectionStockRequestV2.CreateOrder -> requireLocalSalesSource(request.orderId == orderId && value.quoteRevision == Revision(0))
        is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> requireLocalSalesSource(request.orderId == orderId && value.quoteRevision.value > 0)
        else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    }
    commerceRecordOriginal(view, row, original)
    return LocalSalesRow(row, value)
}

internal suspend fun originalReturnPolicyCapture(sources: LocalCommerceOriginalSources, orderId: DomainId,
    quote: PricingSnapshot, reference: SalesReturnPolicySourceReference?, prices: List<LocalSalesPriceSourceV2>,
    normalizedLines: List<RetailStockDemandV2>?): LocalSalesReturnPolicyCaptureV2? {
    if (reference == null) return null
    val row = sources.aggregateOriginal(AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id), OperationId(reference.id.value))
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(row.generation == Revision(1) && row.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
    return requireReturnPolicyCapture(row.payload, sources.scope, orderId, quote, reference, prices, normalizedLines)
}

public fun returnPolicyPublicationResources(publication: LocalSalesReturnPublicationV2): Set<RecoveryResourceKey> = buildSet {
    val source = publication.source
    listOf(publication.publicationId, publication.skuId, publication.signingKeyId,
        DomainId(source.producerOperationId.value), source.producerActorId).forEach {
        add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it))
    }
    publication.promise?.let { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.policyId)) }
}

public fun returnPolicyCaptureInputResources(input: LocalSalesReturnPolicyCaptureInputV2): Set<RecoveryResourceKey> =
    input.publications.flatMap { returnPolicyPublicationResources(it) }.toSet()

internal fun returnPolicyCaptureResources(capture: LocalSalesReturnPolicyCaptureV2?): Set<RecoveryResourceKey> =
    capture?.let { returnPolicyCaptureInputResources(it.input) + orderResource(it.id) }.orEmpty()

private fun returnPolicyQuoteDigest(quote: PricingSnapshot): PayloadDigest =
    sha256(StoredPricingSnapshotCodec.encode(quote).encodeToByteArray(throwOnInvalidSequence = true))

/** Exact whole companion in the ordinary native financial BEFORE; never a selected-portion subset. */
internal fun returnPolicyCaptureBefore(order: LocalOrderCut): List<Pair<String, StoredAggregate>> =
    listOfNotNull(order.returnPolicyCapture?.let { "return_policy_capture" to it.stored })

/** Payment keeps the same actual quote producer for BOTH immutable companions. */
internal fun requirePaymentReturnPolicyCapture(payload: StoredBytes, scope: BranchScope, order: OwnedOrder,
    prices: LocalSalesPriceSourcesV2): LocalSalesReturnPolicyCaptureV2 {
    requireLocalSalesSource(order.stockRequirements.normalized && prices.normalizedStock &&
        prices.id == order.priceSourceReference?.id)
    val reference = order.returnPolicySourceReference
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    val capture = requireReturnPolicyCapture(payload, scope, order.id, order.quote, reference, prices.sources,
        checkNotNull(order.stockRequirements.normalizedLines))
    requireLocalSalesSource(capture.originalQuoteOperationId == prices.originalQuoteOperationId && capture.id == prices.id)
    return capture
}

/** Exact Create/Replace dispatch only; callers still require the genuine unchanged whole producer. */
internal fun requireNormalizedPaymentQuoteRequest(original: RetainedStreamedCommand, orderId: DomainId, quoteRevision: Revision) {
    requireLocalSalesSource(original.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
    when (val request = LocalCollectionStockCodecV2.decode(original.normalized)) {
        is LocalCollectionStockRequestV2.CreateOrder -> requireLocalSalesSource(request.orderId == orderId && quoteRevision == Revision(0))
        is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> requireLocalSalesSource(request.orderId == orderId && quoteRevision.value > 0)
        else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    }
}

internal class LocalPaymentReturnPolicySourceProjection(val capture: LocalSalesReturnPolicyCaptureV2,
    val priceSource: StreamedSourceObservationV2.Current)

/** Pure full V2 projection from the CLOSED original payment census, not opaque current-row authority. */
internal fun requirePaymentReturnPolicySource(observed: StockReadObservations,
    source: StreamedSourceObservationV2.Current): LocalPaymentReturnPolicySourceProjection {
    requireLocalSalesSource(source.key.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND &&
        source.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA && source.generation == Revision(1) &&
        sha256(source.payload.copyBytes()) == source.payloadHash)
    val literal = validateLocalSalesSource { LocalSalesReturnPolicyCaptureCodecV2.decode(source.payload) }
    val orderSource = observed.current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, literal.orderId))
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(orderSource.schema == LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ORDER_KIND) &&
        orderSource.generation.value > 0 && sha256(orderSource.payload.copyBytes()) == orderSource.payloadHash)
    val order = SalesSnapshotCodec.decodeOrder(orderSource.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, observed.scope, literal.orderId, Revision(orderSource.generation.value - 1)))
    val priceReference = order.priceSourceReference
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    val priceSource = observed.current(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, priceReference.id))
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(priceSource.schema == LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA && priceSource.generation == Revision(1) &&
        sha256(priceSource.payload.copyBytes()) == priceSource.payloadHash)
    val prices = requirePriceSources(priceSource.payload, observed.scope, order.id, order.quote, priceReference,
        normalizedLines = checkNotNull(order.stockRequirements.normalizedLines))
    val capture = requirePaymentReturnPolicyCapture(source.payload, observed.scope, order, prices)
    requireLocalSalesSource(source.key.id == capture.id)
    return LocalPaymentReturnPolicySourceProjection(capture, priceSource)
}

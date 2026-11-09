package com.ukkera.pos.sales

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Content coordinates, not a pointer, storage grant, current quote or financial authority. */
@Serializable public data class FoodFinancialFrameReferenceV2(public val byteCount: Int, public val digest: PayloadDigest)
/** Every literal source must travel with the document in the same finite authenticated bundle. */
public data class FoodFinancialEncodedFrameV2(public val document: String, public val sources: List<String>)

/**
 * Explicit normalized DOMAIN profile. Original prices are supplied from exact PRICE_BATCH
 * leaves. Collection basis/attempts, original tender rows and sale basis occur once as literal
 * content-addressed nodes, not nested again in each collection/sale/entitlement/refund.
 * The closed node types form a fixed acyclic graph (sale -> collection/tenders); there is no
 * callback, IO, arbitrary historical pointer or recursive ancestry scan. All nodes and prices
 * must be provided by the adapter's exact current producer + original predecessor crosswalk.
 * These logical bounds do NOT enlarge StoredBytes or promise every combined proposal fits.
 */
public object FoodFinancialFramesV2 {
    public const val MAX_LOGICAL_BYTES: Int = 4_194_304
    public const val MAX_SOURCES: Int = 4

    public fun encodeCollection(value: FoodCollection): FoodFinancialEncodedFrameV2 = checkedSnapshot {
        validateFoodCollection(value)
        val writer = FrameWriterV2(value.scope)
        writer.finish("COLLECTION", value.id, value.revision, FrameStateV2.Collection(writer.collection(value)))
    }
    public fun decodeCollection(text: String, scope: BranchScope, id: DomainId, revision: Revision,
        originalBatches: List<FoodPriceBatch>, sources: List<String>): FoodCollection = checkedSnapshot {
        val reader = FrameReaderV2(text, "COLLECTION", scope, id, revision, originalBatches, sources)
        reader.collection((reader.state as FrameStateV2.Collection).value, id, revision).also { reader.finish() }
    }
    public fun encodeSale(value: FoodPaidSale): FoodFinancialEncodedFrameV2 = checkedSnapshot {
        validateFoodSale(value)
        val writer = FrameWriterV2(value.scope)
        writer.finish("PAID_FOOD", value.id, Revision(0), FrameStateV2.Sale(writer.sale(value)))
    }
    public fun decodeSale(text: String, scope: BranchScope, id: DomainId, originalBatches: List<FoodPriceBatch>,
        sources: List<String>): FoodPaidSale = checkedSnapshot {
        val reader = FrameReaderV2(text, "PAID_FOOD", scope, id, Revision(0), originalBatches, sources)
        reader.sale((reader.state as FrameStateV2.Sale).value, id).also { reader.finish() }
    }
    public fun encodeEntitlement(value: FoodReceiptEntitlement): FoodFinancialEncodedFrameV2 = checkedSnapshot {
        validateFoodEntitlement(value)
        val writer = FrameWriterV2(value.sale.scope)
        writer.finish("FOOD_ENTITLEMENT", value.sale.id, value.revision, FrameStateV2.Entitlement(writer.sale(value.sale),
            value.cursors.map { StoredConsumedQuantity(it.portionId, it.consumedQuantity) }, writer.tenders(value.remainingTenders)))
    }
    public fun decodeEntitlement(text: String, scope: BranchScope, id: DomainId, revision: Revision,
        originalBatches: List<FoodPriceBatch>, sources: List<String>): FoodReceiptEntitlement = checkedSnapshot {
        val reader = FrameReaderV2(text, "FOOD_ENTITLEMENT", scope, id, revision, originalBatches, sources)
        val state = reader.state as FrameStateV2.Entitlement
        snapshotCheck(state.cursors.size in 1..32, SalesSnapshotFailure.TOO_LARGE)
        val sale = reader.sale(state.sale, id)
        FoodReceiptEntitlement(sale, revision, state.cursors.map { cursor ->
            restoreOriginalCursor(sale.quote.batchFor(cursor.portionId).quote, cursor.portionId, cursor.consumedQuantity)
        }, reader.tenders(state.remainingTenders)).also { validateFoodEntitlement(it); reader.finish() }
    }
    public fun encodeRefund(value: FoodRefundObligation): FoodFinancialEncodedFrameV2 = checkedSnapshot {
        validateFoodRefund(value)
        val writer = FrameWriterV2(value.scope)
        writer.finish("UNSENT_FOOD_REFUND", value.id, value.revision, FrameStateV2.Refund(value.originalCollection.id,
            value.originalCollection.revision, writer.collection(value.originalCollection), value.cancellationOperationId,
            value.cancellationReasonId, value.acceptingActorId, value.acceptedAt, value.attempts.map(StoredRefundAttempt::from)))
    }
    public fun decodeRefund(text: String, scope: BranchScope, id: DomainId, revision: Revision,
        originalBatches: List<FoodPriceBatch>, sources: List<String>): FoodRefundObligation = checkedSnapshot {
        val reader = FrameReaderV2(text, "UNSENT_FOOD_REFUND", scope, id, revision, originalBatches, sources)
        val state = reader.state as FrameStateV2.Refund
        snapshotCheck(state.attempts.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        FoodRefundObligation(id, reader.collection(state.collection, state.collectionId, state.collectionRevision),
            state.cancellationOperationId, state.reasonId, state.actorId, state.acceptedAt, revision,
            state.attempts.map { it.restore() }).also { validateFoodRefund(it); reader.finish() }
    }

    /** Discover only the closed bounded literal list. This is not validation or source authority. */
    public fun references(text: String): List<FoodFinancialFrameReferenceV2> = checkedSnapshot { frameDocumentV2(text).sources.toList() }
}

@Serializable private data class FramePriceBasisV2(val id: DomainId, val sourceDigest: PayloadDigest)
@Serializable private data class FrameCollectionV2(val basis: FoodFinancialFrameReferenceV2,
    val saleId: DomainId?, val cancellationRefundId: DomainId?)
@Serializable private sealed interface FrameStateV2 {
    @Serializable @SerialName("COLLECTION") data class Collection(val value: FrameCollectionV2) : FrameStateV2
    @Serializable @SerialName("PAID_FOOD") data class Sale(val value: FoodFinancialFrameReferenceV2) : FrameStateV2
    @Serializable @SerialName("FOOD_ENTITLEMENT") data class Entitlement(val sale: FoodFinancialFrameReferenceV2,
        val cursors: List<StoredConsumedQuantity>, val remainingTenders: FoodFinancialFrameReferenceV2) : FrameStateV2
    @Serializable @SerialName("UNSENT_FOOD_REFUND") data class Refund(val collectionId: DomainId, val collectionRevision: Revision,
        val collection: FrameCollectionV2, val cancellationOperationId: OperationId, val reasonId: DomainId,
        val actorId: DomainId, val acceptedAt: UtcInstant, val attempts: List<StoredRefundAttempt>) : FrameStateV2
}
@OptIn(ExperimentalSerializationApi::class)
@Serializable private sealed interface FrameNodeV2 {
    @Serializable @SerialName("COLLECTION_BASIS") data class Collection(val id: DomainId, val orderId: DomainId,
        val lockedCommercialRevision: Revision, val originalCreatorId: DomainId, val collectorId: DomainId,
        val cashSessionId: DomainId, val batches: List<FramePriceBasisV2>, val portions: List<FoodFinancialPortion>,
        val attempts: List<StoredPaymentAttempt>,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val responsibility: List<FoodCollectionResponsibilityV1> = emptyList()) : FrameNodeV2
    @Serializable @SerialName("COMPONENT_TENDERS") data class Tenders(val rows: List<StoredComponentTenderRow>) : FrameNodeV2
    @Serializable @SerialName("PAID_FOOD_BASIS") data class Sale(val id: DomainId, val collectionId: DomainId,
        val collectionRevision: Revision, val collection: FrameCollectionV2, val finalizationOperationId: OperationId,
        val finalizedAt: UtcInstant, val tenders: FoodFinancialFrameReferenceV2) : FrameNodeV2
}
@Serializable private data class FrameNodeEnvelopeV2(val profile: String, val scope: StoredSalesScope, val node: FrameNodeV2)
@Serializable private data class FrameEnvelopeV2(val profile: String, val kind: String, val scope: StoredSalesScope,
    val id: DomainId, val revision: Revision, val sources: List<FoodFinancialFrameReferenceV2>, val state: FrameStateV2)
private const val FRAME_DOMAIN_PROFILE_V2 = "GENUINE_LOCAL_FOOD_FINANCIAL_V2"
private const val FRAME_SOURCE_PROFILE_V2 = "GENUINE_LOCAL_FOOD_FINANCIAL_BASIS_V2"

private class FrameWriterV2(private val scope: BranchScope) {
    private val sources = linkedMapOf<FoodFinancialFrameReferenceV2, String>()
    private fun node(value: FrameNodeV2): FoodFinancialFrameReferenceV2 {
        val encoded = frameEncodeV2(FrameNodeEnvelopeV2(FRAME_SOURCE_PROFILE_V2, StoredSalesScope.from(scope), value),
            FrameNodeEnvelopeV2.serializer())
        val bytes = encoded.encodeToByteArray(throwOnInvalidSequence = true)
        val reference = FoodFinancialFrameReferenceV2(bytes.size, sha256(bytes))
        val prior = sources.entries.singleOrNull { it.key.digest == reference.digest }
        if (prior != null) {
            snapshotCheck(prior.key == reference && prior.value == encoded, SalesSnapshotFailure.BINDING_MISMATCH)
            return prior.key
        }
        snapshotCheck(sources.size < FoodFinancialFramesV2.MAX_SOURCES, SalesSnapshotFailure.TOO_LARGE)
        sources[reference] = encoded
        return reference
    }
    fun collection(value: FoodCollection): FrameCollectionV2 = FrameCollectionV2(node(FrameNodeV2.Collection(value.id,
        value.orderId, value.lockedCommercialRevision, value.originalCreatorId, value.collectorId, value.cashSessionId,
        value.quote.batches.map { FramePriceBasisV2(it.id, it.sourceDigest) }, value.portions,
        value.attempts.map(StoredPaymentAttempt::from), value.responsibility)), value.saleId, value.cancellationRefundId)
    fun tenders(rows: List<OriginalComponentTenderAllocation>): FoodFinancialFrameReferenceV2 =
        node(FrameNodeV2.Tenders(rows.map(StoredComponentTenderRow::from)))
    fun sale(value: FoodPaidSale): FoodFinancialFrameReferenceV2 {
        val collection = collection(value.collection)
        val tenders = tenders(value.originalTenderAllocations)
        return node(FrameNodeV2.Sale(value.id, value.collection.id, value.collection.revision, collection,
            value.finalizationOperationId, value.finalizedAt, tenders))
    }
    fun finish(kind: String, id: DomainId, revision: Revision, state: FrameStateV2): FoodFinancialEncodedFrameV2 {
        val document = frameEncodeV2(FrameEnvelopeV2(FRAME_DOMAIN_PROFILE_V2, kind, StoredSalesScope.from(scope), id,
            revision, sources.keys.toList(), state), FrameEnvelopeV2.serializer())
        snapshotCheck(document.encodeToByteArray(throwOnInvalidSequence = true).size.toLong() + sources.keys.sumOf { it.byteCount.toLong() } <=
            FoodFinancialFramesV2.MAX_LOGICAL_BYTES, SalesSnapshotFailure.TOO_LARGE)
        return FoodFinancialEncodedFrameV2(document, sources.values.toList())
    }
}

private class FrameReaderV2(text: String, kind: String, private val scope: BranchScope, id: DomainId,
    revision: Revision, private val originals: List<FoodPriceBatch>, sources: List<String>) {
    private val document = frameDocumentV2(text)
    private val nodes: Map<FoodFinancialFrameReferenceV2, FrameNodeV2>
    private val used = linkedSetOf<FoodFinancialFrameReferenceV2>()
    val state: FrameStateV2 get() = document.state
    init {
        snapshotCheck(document.kind == kind && document.scope.restore() == scope && document.id == id && document.revision == revision,
            SalesSnapshotFailure.BINDING_MISMATCH)
        snapshotCheck(sources.size == document.sources.size && text.encodeToByteArray(throwOnInvalidSequence = true).size.toLong() +
            document.sources.sumOf { it.byteCount.toLong() } <= FoodFinancialFramesV2.MAX_LOGICAL_BYTES, SalesSnapshotFailure.TOO_LARGE)
        nodes = document.sources.zip(sources).associate { (reference, literal) ->
            foodSnapshotBound(literal, FoodFinancialFramesV2.MAX_LOGICAL_BYTES)
            val bytes = literal.encodeToByteArray(throwOnInvalidSequence = true)
            snapshotCheck(reference.byteCount == bytes.size && reference.digest == sha256(bytes), SalesSnapshotFailure.BINDING_MISMATCH)
            val value = frameDecodeV2(literal, FrameNodeEnvelopeV2.serializer())
            snapshotCheck(value.profile == FRAME_SOURCE_PROFILE_V2 && value.scope.restore() == scope, SalesSnapshotFailure.BINDING_MISMATCH)
            reference to value.node
        }
    }
    fun collection(value: FrameCollectionV2, id: DomainId, revision: Revision): FoodCollection {
        val basis = nodes[value.basis] as? FrameNodeV2.Collection
            ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.BINDING_MISMATCH)
        snapshotCheck(basis.id == id, SalesSnapshotFailure.BINDING_MISMATCH)
        snapshotCheck(basis.batches.size in 1..32 && basis.portions.size in 1..32 && basis.attempts.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        snapshotCheck(originals.size == basis.batches.size && originals.map { it.id }.distinct().size == originals.size,
            SalesSnapshotFailure.BINDING_MISMATCH)
        val prices = basis.batches.map { ref -> originals.singleOrNull { it.id == ref.id && it.sourceDigest == ref.sourceDigest }
            ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.BINDING_MISMATCH) }
        used += value.basis
        return FoodCollection(id, scope, basis.orderId, basis.lockedCommercialRevision, basis.originalCreatorId, basis.collectorId,
            basis.cashSessionId, FoodFinancialQuote(prices), basis.portions, revision, basis.attempts.map { it.restore() },
            value.saleId, value.cancellationRefundId, basis.responsibility).also(::validateFoodCollection)
    }
    fun tenders(reference: FoodFinancialFrameReferenceV2): List<OriginalComponentTenderAllocation> {
        val basis = nodes[reference] as? FrameNodeV2.Tenders
            ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.BINDING_MISMATCH)
        snapshotCheck(basis.rows.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        used += reference
        return basis.rows.map { it.restore() }
    }
    fun sale(reference: FoodFinancialFrameReferenceV2, id: DomainId): FoodPaidSale {
        val basis = nodes[reference] as? FrameNodeV2.Sale
            ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.BINDING_MISMATCH)
        snapshotCheck(basis.id == id, SalesSnapshotFailure.BINDING_MISMATCH)
        val collection = collection(basis.collection, basis.collectionId, basis.collectionRevision)
        val tenders = tenders(basis.tenders)
        used += reference
        return FoodPaidSale(id, collection, basis.finalizationOperationId, basis.finalizedAt, tenders).also(::validateFoodSale)
    }
    fun finish() { snapshotCheck(used.toList() == document.sources, SalesSnapshotFailure.NON_CANONICAL) }
}

private fun frameDocumentV2(text: String): FrameEnvelopeV2 = frameDecodeV2(text, FrameEnvelopeV2.serializer()).also {
    snapshotCheck(it.profile == FRAME_DOMAIN_PROFILE_V2 && it.sources.size in 1..FoodFinancialFramesV2.MAX_SOURCES &&
        it.sources.map { source -> source.digest }.distinct().size == it.sources.size &&
        it.sources.all { source -> source.byteCount in 1..FoodFinancialFramesV2.MAX_LOGICAL_BYTES }, SalesSnapshotFailure.BINDING_MISMATCH)
    snapshotCheck(it.kind == when (it.state) {
        is FrameStateV2.Collection -> "COLLECTION"
        is FrameStateV2.Sale -> "PAID_FOOD"
        is FrameStateV2.Entitlement -> "FOOD_ENTITLEMENT"
        is FrameStateV2.Refund -> "UNSENT_FOOD_REFUND"
    }, SalesSnapshotFailure.BINDING_MISMATCH)
}
private fun <T> frameEncodeV2(value: T, serializer: KSerializer<T>): String =
    salesSnapshotJson.encodeToString(serializer, value).also { foodSnapshotBound(it, FoodFinancialFramesV2.MAX_LOGICAL_BYTES) }
private fun <T> frameDecodeV2(text: String, serializer: KSerializer<T>): T {
    foodSnapshotBound(text, FoodFinancialFramesV2.MAX_LOGICAL_BYTES)
    val value = salesSnapshotJson.decodeFromString(serializer, text)
    snapshotCheck(salesSnapshotJson.encodeToString(serializer, value) == text, SalesSnapshotFailure.NON_CANONICAL)
    return value
}

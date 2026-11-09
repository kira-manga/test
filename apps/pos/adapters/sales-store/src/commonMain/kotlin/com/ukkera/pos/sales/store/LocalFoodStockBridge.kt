package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** New explicit NONPHYSICAL route. An old pending food/financial operation is never rekeyed. */
@Serializable public sealed interface LocalFoodStockRequestV2 {
    @Serializable @SerialName("ACTIVITY_PROTECTED") public data class ActivityProtected(public val request: LocalFoodDraftCreateV1) : LocalFoodStockRequestV2
    @Serializable @SerialName("ACTIVITY_PROTECTED_ADD") public data class ActivityProtectedAdd(public val request: LocalFoodRequestV1.Add) : LocalFoodStockRequestV2
    @Serializable @SerialName("ACTIVITY_PROTECTED_QUOTED_ADD") public data class ActivityProtectedQuotedAdd(public val request: LocalFoodRequestV1.Add) : LocalFoodStockRequestV2
    @Serializable @SerialName("CREATE_DRAFT") public data class CreateDraft(public val request: LocalFoodDraftCreateV1) : LocalFoodStockRequestV2
    @Serializable @SerialName("PREPARATION") public data class Preparation(public val request: LocalFoodRequestV1) : LocalFoodStockRequestV2
    @Serializable @SerialName("FINANCIAL") public data class Financial(public val request: LocalFoodFinancialRequestV1) : LocalFoodStockRequestV2
}
public object LocalFoodStockCodecV2 {
    public val COMMAND: CommandKey = StockStreamingCommandsV2.FOOD
    public fun encode(value: LocalFoodStockRequestV2): StoredBytes {
        when (value) {
            is LocalFoodStockRequestV2.ActivityProtected -> require(value.request.activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
            is LocalFoodStockRequestV2.ActivityProtectedAdd -> LocalFoodCodecV1.encode(value.request)
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> LocalFoodCodecV1.encode(value.request)
            is LocalFoodStockRequestV2.CreateDraft -> require(value.request.activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
            is LocalFoodStockRequestV2.Preparation -> {
                LocalFoodCodecV1.encode(value.request)
                require(value.request !is LocalFoodRequestV1.OfferHandoff && value.request !is LocalFoodRequestV1.AcceptHandoff &&
                    value.request !is LocalFoodRequestV1.CancelHandoff) { "food.stock.handoff_original_profile_required" }
            }
            is LocalFoodStockRequestV2.Financial -> {
                LocalFoodFinancialCodecV1.encode(value.request)
                require(foodNonphysicalRequest(value.request)) { "food.stock.physical_admission_not_installed" }
            }
        }
        return localSalesEncode(value, LocalFoodStockRequestV2.serializer(), 32_768)
    }
    public fun decode(bytes: StoredBytes): LocalFoodStockRequestV2 = localSalesDecode(bytes, LocalFoodStockRequestV2.serializer(), 32_768)
    public fun normalize(bytes: StoredBytes): StoredBytes = encode(decode(bytes))
    public fun command(id: OperationId, request: LocalFoodStockRequestV2): MutationRequest = MutationRequest(id, COMMAND, encode(request))
}

/**
 * The real catalog adapter supplies BOTH same-loan current reads and exact original literal
 * verification. No default, Boolean certificate, invented current view or accepting provider.
 */
public interface TransactionFoodStockSource : TransactionFoodRecipeSourceV1, TransactionCommerceStockAuthority {
    public suspend fun prepareActivity(view: CommandReadView, activity: LocalSalesActivity): PreparedFoodActivityAdmission
    public suspend fun requireOriginalActivity(sources: LocalCommerceOriginalSources,
        activity: LocalSalesActivity): Set<RecoveryResourceKey>
    public suspend fun requireOriginalHistorical(sources: LocalCommerceOriginalSources,
        snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey>
    public suspend fun requireOriginalCurrentForSend(sources: LocalCommerceOriginalSources,
        snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey>
    public suspend fun requireOriginalQuote(sources: LocalCommerceOriginalSources, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision, selections: List<LocalFoodPortionSelectionV1>,
        quoted: LocalFoodQuotedBatchV1): Set<RecoveryResourceKey>
    /** Mandatory signature check on recipes returned by the COMPLETE original v1 commerce proof. */
    public fun requireLegacyRecipe(snapshot: LocalFoodRecipeSnapshotV1, scope: BranchScope)
}

/** Request-confined source observation, not a transferable employee or activity grant. */
public class PreparedFoodActivityAdmission(public val sourceEnabled: Boolean, recoveryResources: Set<RecoveryResourceKey>) {
    private val resources: Set<RecoveryResourceKey> = recoveryResources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()
}

internal fun foodStockSchema(kind: String): String = "$kind/stock-v2"

/** Original send actor is not substituted by a later collection owner, KDS actor or handoff. */
@Serializable internal data class FoodStockIssueReferenceV2(val issueId: DomainId, val reservationId: DomainId,
    val operationId: OperationId, val actorId: DomainId, val semanticDigest: PayloadDigest,
    val orderId: DomainId, val lockedQuoteRevision: Revision, val collectionId: DomainId, val collectionActorId: DomainId,
    val exposure: String, val allocationCount: Long, val originalValue: Money, val recipeSnapshotId: DomainId,
    val requiredStationIds: List<DomainId>) {
    fun binding(): CollectionBinding = CollectionBinding(orderId, lockedQuoteRevision, collectionId, collectionActorId,
        CollectionExposure.entries.single { it.name == exposure })
}
internal fun foodIssueReference(context: CommandContext, issue: LocalStockIssueHeader): FoodStockIssueReferenceV2 {
    check(issue.revision == Revision(1) && issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.startedStationIds.isEmpty() &&
        issue.disposition == KitchenDisposition.ACTIVE)
    return FoodStockIssueReferenceV2(issue.id, issue.reservationId, context.operationId, context.originalActorId, context.semanticDigest,
        issue.binding.orderId, issue.binding.quoteRevision, issue.binding.collectionId, issue.binding.originalActorId,
        issue.binding.exposure.name, issue.allocationCount, issue.originalValue, checkNotNull(issue.recipeSnapshotId), issue.requiredStationIds)
}
internal fun requireFoodIssueReference(reference: FoodStockIssueReferenceV2, portion: FoodBodyV1.Portion) {
    check(reference.issueId == portion.preparation.issueId && reference.reservationId == portion.preparation.reservationId &&
        reference.issueId == foodIssueId(portion.selection.portionId) && reference.reservationId == foodReservationId(portion.selection.portionId) &&
        reference.orderId == portion.orderId && reference.lockedQuoteRevision.value >= 1 && reference.allocationCount > 0 &&
        reference.originalValue.currency == Currency.EGP && reference.originalValue.minor >= 0 &&
        reference.recipeSnapshotId == portion.recipe.snapshotId && reference.requiredStationIds == portion.recipe.routes.map { it.stationId } &&
        portion.preparation.tasks.map { it.stationId } == reference.requiredStationIds &&
        (if (portion.financialCollectionId == null) reference.exposure == CollectionExposure.UNCOLLECTED.name &&
            reference.collectionId == foodStockContextId(portion.orderId)
        else reference.exposure == CollectionExposure.UNKNOWN.name && reference.collectionId == portion.financialCollectionId))
}
internal fun requireFoodCurrentIssueHeader(portion: FoodBodyV1.Portion, issue: LocalStockIssueHeader) {
    val source = checkNotNull(portion.normalizedIssue) { "food.stock.legacy_issue_migration_required" }
    requireFoodIssueReference(source, portion)
    val disposition = when (portion.preparation.status) {
        FoodPreparationStatus.CANCELLED_BEFORE_START -> KitchenDisposition.CANCELLED_BEFORE_START
        FoodPreparationStatus.WASTE_AFTER_START -> KitchenDisposition.WASTE_AFTER_START
        else -> KitchenDisposition.ACTIVE
    }
    check(issue.id == source.issueId && issue.reservationId == source.reservationId && issue.binding == source.binding() &&
        issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.revision == portion.preparation.issueRevision &&
        issue.allocationCount == source.allocationCount && issue.originalValue == source.originalValue &&
        issue.recipeSnapshotId == source.recipeSnapshotId && issue.requiredStationIds == source.requiredStationIds && issue.disposition == disposition &&
        issue.startedStationIds.toSet() == portion.preparation.tasks.filter { it.startedAt != null }.map { it.stationId }.toSet())
}
internal suspend fun requireFoodFirstIssue(portion: FoodBodyV1.Portion, first: LocalStockIssueOriginal) {
    val source = checkNotNull(portion.normalizedIssue)
    val header = first.header
    check(first.storageGeneration == Revision(1) && first.original.identity.producer == StreamedProducerIdentityV2(source.operationId,
        StockStreamingCommandsV2.FOOD, source.actorId, source.semanticDigest) && first.original.identity.recordedAt == portion.preparation.sentAt &&
        header.id == source.issueId && header.reservationId == source.reservationId && header.binding == source.binding() &&
        header.revision == Revision(1) && header.purpose == ValueIssuePurpose.DIRECT_RECIPE && header.allocationCount == source.allocationCount && header.originalValue == source.originalValue &&
        header.recipeSnapshotId == source.recipeSnapshotId && header.requiredStationIds == source.requiredStationIds &&
        header.startedStationIds.isEmpty() && header.disposition == KitchenDisposition.ACTIVE)
    // The handle came from the owner after exact literal hash/generation and first-slice source
    // crosswalk, not from this scalar DTO. Food's complete original producer is still required.
}

/** Finite recipe totals, but every physical allocation is consumed through EOF. */
internal suspend fun requireFoodReservationMembers(portion: FoodBodyV1.Portion, header: InventoryReservationHeader,
    each: suspend (suspend (InventoryAllocationRecord) -> Unit) -> Unit) {
    check(header.id == foodReservationId(portion.selection.portionId) && header.binding.orderId == portion.orderId)
    val ingredients = portion.recipe.ingredients.associateBy { foodIngredientLine(portion.selection.portionId, it.componentId) }
    val totals = ingredients.mapValues { Quantity(it.value.requiredBaseQuantity.unitId, 0, it.value.requiredBaseQuantity.scale) }.toMutableMap()
    var count = 0L
    each { member ->
        val a = member.allocation; val ingredient = ingredients[a.lineId] ?: error("food.stock.unrequested_ingredient")
        check(member.reservationId == header.id && a.position.pool.skuId == ingredient.skuId &&
            a.position.pool.warehouseId == portion.recipe.warehouseId && a.position.pool.ownership == StockOwnership.CompanyOwned &&
            a.position.custody == StockCustody.RELEASED && a.position.serialId == null && a.quantity.scaled > 0)
        totals[a.lineId] = totals.getValue(a.lineId) + a.quantity
        check(count < Long.MAX_VALUE); count++
    }
    check(count == header.allocationCount && ingredients.all { (id, ingredient) -> totals.getValue(id) == ingredient.requiredBaseQuantity })
}
internal suspend fun requireFoodIssueMembers(portion: FoodBodyV1.Portion, header: LocalStockIssueHeader,
    each: suspend (suspend (InventoryIssueSlice) -> Unit) -> Unit) {
    val ingredients = portion.recipe.ingredients.associateBy { foodIngredientLine(portion.selection.portionId, it.componentId) }
    val totals = ingredients.mapValues { Quantity(it.value.requiredBaseQuantity.unitId, 0, it.value.requiredBaseQuantity.scale) }.toMutableMap()
    var count = 0L; var value = Money(Currency.EGP, 0)
    each { member ->
        val a = member.allocation; val ingredient = ingredients[a.lineId] ?: error("food.stock.unrequested_issue_ingredient")
        check(member.issueId == header.id && a.position.pool.skuId == ingredient.skuId && a.position.pool.warehouseId == portion.recipe.warehouseId &&
            a.position.pool.ownership == StockOwnership.CompanyOwned && a.position.custody == StockCustody.RELEASED && a.position.serialId == null &&
            a.quantity.scaled > 0 && member.originalValue.currency == Currency.EGP && member.originalValue.minor >= 0 &&
            (if (header.disposition == KitchenDisposition.CANCELLED_BEFORE_START) member.restoredQuantity == a.quantity
            else member.restoredQuantity.scaled == 0L))
        totals[a.lineId] = totals.getValue(a.lineId) + a.quantity; value += member.originalValue
        check(count < Long.MAX_VALUE); count++
    }
    check(count == header.allocationCount && value == header.originalValue && ingredients.all { (id, ingredient) -> totals.getValue(id) == ingredient.requiredBaseQuantity })
}

@Serializable private data class FoodStockLeafV2(val byteCount: Int, val digest: PayloadDigest, val chunks: List<DomainId>)
@Serializable private data class FoodStockDocumentV2(val scope: BranchScope, val kind: String, val id: DomainId,
    val revision: Revision, val operationId: OperationId, val batchIds: List<DomainId>, val document: FoodStockLeafV2,
    val sources: List<FoodStockLeafV2>)
private const val FOOD_STOCK_CHUNK_V2: Int = 65_536
private fun foodStockChunkId(operation: OperationId, kind: String, id: DomainId, leaf: Int, chunk: Int): DomainId =
    foodId("financial-stock-chunk-v2", "${operation.value}/$kind/${id.value}/$leaf/$chunk")
private fun foodStockDocument(bytes: StoredBytes): FoodStockDocumentV2 = localSalesDecode(bytes, FoodStockDocumentV2.serializer(), 32_768).also { root ->
    check(root.revision.value >= 1 && root.kind in setOf(LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.SALE,
        LocalFoodFinancialProfileV1.ENTITLEMENT, LocalFoodFinancialProfileV1.REFUND) && root.batchIds.size in 1..32 &&
        root.batchIds.distinct().size == root.batchIds.size && root.sources.size in 1..FoodFinancialFramesV2.MAX_SOURCES)
    val leaves = listOf(root.document) + root.sources
    check(leaves.sumOf { it.byteCount.toLong() } <= FoodFinancialFramesV2.MAX_LOGICAL_BYTES)
    for ((leafIndex, leaf) in leaves.withIndex()) check(leaf.byteCount in 1..FoodFinancialFramesV2.MAX_LOGICAL_BYTES &&
        leaf.chunks.size == (leaf.byteCount + FOOD_STOCK_CHUNK_V2 - 1) / FOOD_STOCK_CHUNK_V2 &&
        leaf.chunks == leaf.chunks.indices.map { foodStockChunkId(root.operationId, root.kind, root.id, leafIndex, it) })
}
private fun foodStockFrameBody(kind: String, encoded: FoodFinancialEncodedFrameV2): FoodFinancialBodyV1 = when (kind) {
    LocalFoodFinancialProfileV1.COLLECTION -> FoodFinancialBodyV1.Collection(encoded)
    LocalFoodFinancialProfileV1.SALE -> FoodFinancialBodyV1.Sale(encoded)
    LocalFoodFinancialProfileV1.ENTITLEMENT -> FoodFinancialBodyV1.Entitlement(encoded)
    LocalFoodFinancialProfileV1.REFUND -> FoodFinancialBodyV1.Refund(encoded)
    else -> error("food.stock.document_kind")
}
private suspend fun foodStockLiteralWrite(original: RetainedStreamedCommand, key: AggregateKey): RetainedAggregateWrite {
    var found: RetainedAggregateWrite? = null
    original.frames { frame -> if (frame is RetainedCommandFrameV2.Write && frame.value.key == key) {
        check(found == null); found = frame.value
    } }
    return found ?: error("food.stock.original_leaf_missing")
}
internal suspend fun foodStockReadDocument(original: RetainedStreamedCommand, bytes: StoredBytes,
    accountChunk: (suspend (AggregateKey, StoredBytes) -> Unit)? = null): FoodFinancialEnvelopeV1 {
    val root = foodStockDocument(bytes)
    check(root.scope == original.identity.scope && root.operationId == original.identity.producer.operationId &&
        original.identity.producer.command == StockStreamingCommandsV2.FOOD)
    suspend fun leaf(value: FoodStockLeafV2): String {
        val bytes = ByteArray(value.byteCount); var offset = 0
        for (id in value.chunks) {
            val key = AggregateKey(LocalFoodFinancialProfileV1.FRAME, id)
            val write = foodStockLiteralWrite(original, key)
            val chunk = write.payload.copyBytes()
            check(write.storageGeneration == Revision(1) && write.schema == foodStockSchema(key.kind) &&
                sha256(chunk) == write.payloadDigest && chunk.size == minOf(FOOD_STOCK_CHUNK_V2, bytes.size - offset))
            chunk.copyInto(bytes, offset); offset += chunk.size
            accountChunk?.invoke(key, write.payload)
        }
        check(offset == value.byteCount && sha256(bytes) == value.digest)
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
    val encoded = FoodFinancialEncodedFrameV2(leaf(root.document), root.sources.map { leaf(it) })
    val sources = LocalCommerceOriginalSources(original)
    val batches = root.batchIds.map { id ->
        val row = checkNotNull(sources.aggregateOriginal(AggregateKey(LocalFoodProfileV1.PRICE_BATCH, id)))
        check(row.schema in setOf(LocalFoodProfileV1.schema(row.key.kind), foodStockSchema(row.key.kind)))
        val value = foodEnvelope(row.payload)
        check(value.id == id && value.scope == root.scope && value.revision == row.generation)
        FoodPriceBatch(id, row.digest, StoredPricingSnapshotCodec.decode((value.body as FoodBodyV1.PriceBatch).quote))
    }
    return FoodFinancialEnvelopeV1(root.scope, root.kind, root.id, root.revision, root.operationId,
        foodStockFrameBody(root.kind, encoded), true, batches).also(::requireFoodFinancialEnvelope)
}

/** Decode the SAME persisted financial root from the core-selected native CURRENT rows.
 * Its original has already passed the whole FOOD/stock/quote/BEFORE-AFTER/resource joins.
 * Streamed originals are inert here: no frames(), CommandReadView or producer replay is
 * reopened. Every requested immutable chunk is a current native row of that exact original;
 * original price batches remain their actual immutable native generation1 documents. */
internal suspend fun foodStockReadCurrentDocument(state: RuntimeHistoricalFoodCurrentState,
    source: RuntimeHistoricalAggregateSource): FoodFinancialEnvelopeV1 {
    val rows = state.sources().associateBy { it.row.key }
    check(rows[source.row.key] === source)
    val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence
        ?: error("food.current_history.financial_original_profile")
    val root = foodStockDocument(source.row.payload)
    check(source.row.schema == foodStockSchema(root.kind) && root.scope == state.scope &&
        root.kind == source.row.key.kind && root.id == source.row.key.id && root.revision == source.row.revision &&
        root.operationId == original.identity.producer.operationId && original.identity.scope == state.scope &&
        original.identity.producer.command == StockStreamingCommandsV2.FOOD)
    fun leaf(value: FoodStockLeafV2): String {
        val bytes = ByteArray(value.byteCount); var offset = 0
        for (id in value.chunks) {
            val key = AggregateKey(LocalFoodFinancialProfileV1.FRAME, id)
            val chunkSource = rows[key] ?: error("food.current_history.financial_chunk_missing")
            val row = chunkSource.row
            val chunk = row.payload.copyBytes()
            check(chunkSource.original === source.original && row.revision == Revision(1) &&
                row.schema == foodStockSchema(key.kind) && sha256(chunk) == row.digest &&
                chunk.size == minOf(FOOD_STOCK_CHUNK_V2, bytes.size - offset))
            chunk.copyInto(bytes, offset); offset += chunk.size
        }
        check(offset == value.byteCount && sha256(bytes) == value.digest)
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
    val encoded = FoodFinancialEncodedFrameV2(leaf(root.document), root.sources.map(::leaf))
    val batches = root.batchIds.map { id ->
        val row = rows[AggregateKey(LocalFoodProfileV1.PRICE_BATCH, id)]?.row
            ?: error("food.current_history.financial_price_missing")
        check(row.revision == Revision(1) && row.schema == foodStockSchema(row.key.kind))
        val value = foodEnvelope(row.payload)
        check(value.scope == root.scope && value.id == id && value.revision == row.revision)
        FoodPriceBatch(id, row.digest, StoredPricingSnapshotCodec.decode((value.body as FoodBodyV1.PriceBatch).quote))
    }
    return FoodFinancialEnvelopeV1(root.scope, root.kind, root.id, root.revision, root.operationId,
        foodStockFrameBody(root.kind, encoded), true, batches).also(::requireFoodFinancialEnvelope)
}

internal suspend fun foodStockObserveFinancial(view: CommandReadView, row: StoredAggregate): FoodFinancialEnvelopeV1 {
    // Simple indexes/claims keep their old body; the explicit storage schema determines dispatch.
    if (row.key.kind !in setOf(LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.SALE,
            LocalFoodFinancialProfileV1.ENTITLEMENT, LocalFoodFinancialProfileV1.REFUND)) return foodFinancialEnvelope(row.payload)
    val root = foodStockDocument(row.payload)
    val original = (view.requireStockRelational().originals.read(root.operationId) as? RetainedOriginalV2.Streamed)?.evidence
        ?: error("food.stock.financial_original_missing")
    requireCommerceWrite(original, row)
    return foodStockReadDocument(original, row.payload)
}

/** Exact literal cross-profile witness, not a substitute for the retained original's whole proof. */
internal fun requireFoodStockFinancialLiteral(bytes: StoredBytes, value: FoodFinancialEnvelopeV1) {
    val root = foodStockDocument(bytes)
    check(value.framed && root.scope == value.scope && root.kind == value.kind && root.id == value.id &&
        root.revision == value.revision && root.operationId == value.operationId &&
        root.batchIds == value.originalBatches.map { it.id })
    val encoded = when (val body = value.body) {
        is FoodFinancialBodyV1.Collection -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
        is FoodFinancialBodyV1.Sale -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
        is FoodFinancialBodyV1.Entitlement -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
        is FoodFinancialBodyV1.Refund -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
        else -> error("food.handoff.normalized_financial_kind")
    }
    val literals = listOf(encoded.document) + encoded.sources
    val leaves = listOf(root.document) + root.sources
    check(literals.size == leaves.size)
    for ((literal, leaf) in literals.zip(leaves)) {
        val text = literal.encodeToByteArray(throwOnInvalidSequence = true)
        check(text.size == leaf.byteCount && sha256(text) == leaf.digest)
    }
}

/** Only finite food documents are buffered; physical inventory rows stay entirely in the sink. */
private class FoodStockWrites(private val view: CommandReadView, private val context: CommandContext,
    private val sink: StreamedCommandSink) {
    private val writes = CommerceWrites(view, context, sink)
    val foodAfter = linkedMapOf<AggregateKey, FoodEnvelopeV1>()
    val revisions = mutableListOf<LocalFoodStorageRevisionV1>()
    suspend fun food(kind: String, id: DomainId, body: FoodBodyV1, before: FoodRow?,
        schema: String = foodStockSchema(kind)): FoodEnvelopeV1 {
        check((before == null || before.row.key == AggregateKey(kind, id)) && foodStreamedSchema(kind, schema))
        val value = FoodEnvelopeV1(context.scope, kind, id, before?.row?.revision?.next() ?: Revision(1), context.operationId, body)
        requireFoodStoredEnvelope(value, schema)
        put(AggregateKey(kind, id), foodStoredEnvelopeBytes(value, schema), before?.row, schema)
        check(foodAfter.put(AggregateKey(kind, id), value) == null)
        return value
    }
    suspend fun financial(kind: String, id: DomainId, body: FoodFinancialBodyV1, before: FoodFinancialRow?, batches: List<FoodRow> = emptyList()) {
        val revision = before?.row?.revision?.next() ?: Revision(1)
        val encoded = when (body) {
            is FoodFinancialBodyV1.Collection -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
            is FoodFinancialBodyV1.Sale -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
            is FoodFinancialBodyV1.Entitlement -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
            is FoodFinancialBodyV1.Refund -> FoodFinancialEncodedFrameV2(body.snapshot, body.sources)
            else -> null
        }
        val payload = if (encoded == null) {
            val value = FoodFinancialEnvelopeV1(context.scope, kind, id, revision, context.operationId, body)
            requireFoodFinancialEnvelope(value); localSalesEncode(value, FoodFinancialEnvelopeV1.serializer(), 65_536)
        } else {
            check(batches.size in 1..32 && batches.map { it.envelope.id }.distinct().size == batches.size)
            val originals = batches.map { batch -> FoodPriceBatch(batch.envelope.id, batch.row.digest,
                StoredPricingSnapshotCodec.decode((batch.envelope.body as FoodBodyV1.PriceBatch).quote)) }
            requireFoodFinancialEnvelope(FoodFinancialEnvelopeV1(context.scope, kind, id, revision, context.operationId, body, true, originals))
            val literals = listOf(encoded.document) + encoded.sources
            check(literals.size in 2..FoodFinancialFramesV2.MAX_SOURCES + 1)
            var total = 0L
            suspend fun leaf(index: Int, text: String): FoodStockLeafV2 {
                val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
                total += bytes.size; foodCapacity(total <= FoodFinancialFramesV2.MAX_LOGICAL_BYTES && bytes.isNotEmpty())
                val ids = mutableListOf<DomainId>(); var offset = 0
                while (offset < bytes.size) {
                    val chunkId = foodStockChunkId(context.operationId, kind, id, index, ids.size)
                    val chunk = StoredBytes(bytes.copyOfRange(offset, minOf(bytes.size, offset + FOOD_STOCK_CHUNK_V2)))
                    writes.put(AggregateChange(AggregateKey(LocalFoodFinancialProfileV1.FRAME, chunkId), null,
                        foodStockSchema(LocalFoodFinancialProfileV1.FRAME), chunk), null)
                    ids += chunkId; offset += chunk.size
                }
                return FoodStockLeafV2(bytes.size, sha256(bytes), ids)
            }
            val leaves = literals.mapIndexed { index, text -> leaf(index, text) }
            localSalesEncode(FoodStockDocumentV2(context.scope, kind, id, revision, context.operationId,
                batches.map { it.envelope.id }, leaves.first(), leaves.drop(1)), FoodStockDocumentV2.serializer(), 32_768)
        }
        put(AggregateKey(kind, id), payload, before?.row)
    }
    private suspend fun put(key: AggregateKey, payload: StoredBytes, before: StoredAggregate?,
        schema: String = foodStockSchema(key.kind)) {
        check(foodStreamedSchema(key.kind, schema))
        writes.put(AggregateChange(key, before?.revision, schema, payload), before)
        if (before == null) writes.claim(ClaimChange.Acquire(foodSourceClaim(key), key.id))
        revisions += LocalFoodStorageRevisionV1(key.kind, key.id, before?.revision?.next() ?: Revision(1))
    }
    suspend fun claim(key: ClaimKey, owner: DomainId) { check(view.claim(key) == null); writes.claim(ClaimChange.Acquire(key, owner)) }
    suspend fun jobs(jobs: List<JobIntent>) = writes.jobs(jobs)
    suspend fun finish(inventory: LocalStockRelationalTransaction, result: StoredBytes, resources: Set<RecoveryResourceKey>): StreamedMutationDecision.Apply {
        writes.resources(resources); emitCommerceRowResources(sink)
        return inventory.finish(result)
    }
    fun financialResult(code: String, id: DomainId, amount: Money? = null): StoredBytes = localSalesEncode(
        LocalFoodFinancialResultV1(code, id, revisions.toList(), amount), LocalFoodFinancialResultV1.serializer(), 16_384)
}
internal fun foodSourceClaim(key: AggregateKey): ClaimKey = if (key.kind in LocalFoodProfileV1.kinds ||
    key.kind == FoodQuotationStorageV1.RECIPE_QUOTATION)
    LocalFoodProfileV1.claim(key.kind, key.id) else LocalFoodFinancialProfileV1.source(key.kind, key.id)

private class FoodQuotedCurrentOrder(val portions: List<FoodRow>, val batches: List<FoodRow>,
    val resources: Set<RecoveryResourceKey>)
private class FoodQuotedAddCapture(val prepared: PreparedFoodRecipeQuotationBatchV1,
    val resources: Set<RecoveryResourceKey>)

/** Complete commercial membership, using the saved economics of both routed and quoted work. */
private fun requireFoodQuotedOrderCommercial(order: FoodEnvelopeV1, portions: List<FoodEnvelopeV1>,
    batches: List<FoodEnvelopeV1>) {
    val body = order.body as FoodBodyV1.Order
    check(portions.map { it.id } == body.portionIds && batches.map { it.id } == body.batchIds)
    var total = Money(Currency.EGP, 0)
    for (batch in batches) {
        val price = batch.body as? FoodBodyV1.PriceBatch ?: error("food.quotation.price_batch_required")
        check(batch.scope == order.scope && batch.revision == Revision(1) && price.orderId == order.id)
        val quote = StoredPricingSnapshotCodec.decode(price.quote)
        val members = portions.filter { row -> when (val portion = row.body) {
            is FoodBodyV1.Portion -> portion.priceBatchId == batch.id
            is FoodBodyV1.QuotedPortion -> portion.priceBatchId == batch.id
            else -> error("food.quotation.portion_required")
        } }
        check(members.isNotEmpty() && quote.snapshotId == batch.id && quote.revision == Revision(0) &&
            quote.lines.map { it.portionId } == members.map { it.id } &&
            price.sources.map { it.portionId } == members.map { it.id })
        for ((index, member) in members.withIndex()) {
            val selection = when (val portion = member.body) {
                is FoodBodyV1.Portion -> { check(portion.orderId == order.id); portion.selection }
                is FoodBodyV1.QuotedPortion -> { check(portion.orderId == order.id); portion.selection }
                else -> error("food.quotation.portion_required")
            }
            check(member.scope == order.scope && quote.lines[index].quantity == selection.quantity &&
                quote.lines[index].lineId == selection.commercialLineId &&
                price.sources[index].lineId == selection.commercialLineId && price.sources[index].scope == order.scope)
        }
        total += quote.payable
    }
    check(total == body.originalQuotedTotal)
}

private fun requireSameFoodQuotationBatch(expected: LocalFoodRecipeQuotationBatchV1,
    actual: LocalFoodRecipeQuotationBatchV1) {
    check(StoredPricingSnapshotCodec.encode(expected.quote) == StoredPricingSnapshotCodec.encode(actual.quote) &&
        expected.priceSources == actual.priceSources && expected.quotations.size == actual.quotations.size)
    for ((before, after) in expected.quotations.zip(actual.quotations)) check(
        localSalesEncode(before, LocalFoodRecipeQuotationV1.serializer(), FoodQuotationStorageV1.MAX_BYTES) ==
            localSalesEncode(after, LocalFoodRecipeQuotationV1.serializer(), FoodQuotationStorageV1.MAX_BYTES))
}

/** One actual authorized loan, one sink, one final kernel settlement for the whole operation. */
public class LocalFoodStockHandler(private val stock: LocalStockRepository, private val authority: LocalStockAuthorityReader,
    private val recipes: TransactionFoodStockSource,
    private val foodCheckRefusals: LocalFoodCheckRefusalSupport? = null) : MasterStreamingCommandHandler,
    LocalMasterRpcRetainedResultAccessProvider, LocalMasterRpcRetainedResultSourceAccessProvider {
    override val key: CommandKey = StockStreamingCommandsV2.FOOD
    override val writableKinds: Set<String> = (StockRelationalKindsV2.all - StockRelationalKindsV2.ACTIVATION) +
        (LocalFoodProfileV1.kinds - setOf(LocalFoodProfileV1.EVENT, LocalFoodProfileV1.BEFORE)) +
        FoodQuotationStorageV1.RECIPE_QUOTATION + foodFinancialDocumentKinds + LocalFoodFinancialProfileV1.FRAME
    override val claimNamespaces: Set<String> = LocalStockStoreProfile.inventoryClaimNamespaces +
        setOf(LocalFoodProfileV1.CLAIM, LocalFoodFinancialProfileV1.SOURCE_NAMESPACE)
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodStockCodecV2.normalize(input)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.KITCHEN && profile != LocalMasterRpcWorkflowProfile.FOOD_CHECKS &&
            profile != LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL)
            return false
        if (normalize(normalized) != normalized) return false
        val request = LocalFoodStockCodecV2.decode(normalized)
        return when (profile) {
            LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> request is LocalFoodStockRequestV2.ActivityProtected ||
                request is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd
            LocalMasterRpcWorkflowProfile.KITCHEN ->
                (request as? LocalFoodStockRequestV2.Preparation)?.request?.foodKitchenRpcStation() != null
            LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> request is LocalFoodStockRequestV2.Financial &&
                (request.request is LocalFoodFinancialRequestV1.BeginCollection ||
                    request.request is LocalFoodFinancialRequestV1.FinalizePaid)
            else -> false
        }
    }
    override fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        context: CommandContext): List<AuthorizationRequirement> {
        check(profile == LocalMasterRpcWorkflowProfile.KITCHEN && context.key == key &&
            normalize(normalized) == normalized &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val preparation = checkNotNull(LocalFoodStockCodecV2.decode(normalized) as? LocalFoodStockRequestV2.Preparation)
        val station = checkNotNull(preparation.request.foodKitchenRpcStation())
        return listOf(foodKitchenRpcStationResultRequirement(station, context))
    }
    override suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        if ((profile != LocalMasterRpcWorkflowProfile.FOOD_CHECKS &&
                profile != LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL) || context.key != key ||
            context.scope != view.scope || !supportsRpcWorkflow(profile, normalized) ||
            semanticDigest(context.scope, context.originalActorId, key, normalized) != context.semanticDigest) return null
        if (profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL)
            return foodCashFinancialRetainedStockRequirements(normalized, foodReadView(commerceLoan(view, stock)), context)
        // Original ownership is derived on this same writer loan. The context actor or
        // today's ORDER cannot substitute for the original Add BEFORE owner.
        return foodCheckRefusals?.retainedRequirements(normalized, foodReadView(commerceLoan(view, stock)), context)
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedStreamingMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalFoodStockCodecV2.decode(normalized)
        val preparationView = foodReadView(commerceLoan(view, stock))
        val admission = foodStockAdmission(request, preparationView, context)
        val requestedActivity = when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> request.request.activity
            is LocalFoodStockRequestV2.ActivityProtectedAdd ->
                (foodObserveRow(preparationView, LocalFoodProfileV1.ORDER, request.request.orderId)?.envelope?.body as? FoodBodyV1.Order)?.activity
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd ->
                (foodObserveRow(preparationView, LocalFoodProfileV1.ORDER, request.request.orderId)?.envelope?.body as? FoodBodyV1.Order)?.activity
            else -> null
        }
        val activity = requestedActivity?.let { recipes.prepareActivity(preparationView, it) }
        val quotationSource = recipes as? TransactionFoodRecipeQuotationVerificationSourceV1
        val scalarRefusal = if (request !is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd || quotationSource != null)
            foodCheckRefusals?.prepare(request, preparationView, context, activity) else null
        var quotationPreparationFailure: RuntimeException? = null
        var quotationUnavailable = request is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd && quotationSource == null
        val quotation = if (scalarRefusal == null && request is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd &&
            activity?.sourceEnabled == true && quotationSource != null) {
            try {
                prepareQuotedAdd(request.request, preparationView, context, quotationSource).also {
                    if (it == null) quotationUnavailable = true
                }
            } catch (failure: SalesRuleViolation) { quotationPreparationFailure = failure; null }
            catch (failure: FoodCapacityExceeded) { quotationPreparationFailure = failure; null }
            catch (failure: LocalStockHydrationCapacityUnavailable) { quotationPreparationFailure = failure; null }
        } else null
        // Keep old normalized bytes recoverable, but do not create new unprotected drafts.
        val legacyQuotation = request is LocalFoodStockRequestV2.Preparation &&
            (request.request is LocalFoodRequestV1.Create || request.request is LocalFoodRequestV1.Add)
        // Rich preparation reads can refuse a target or reach a named finite-work
        // limit. Translate those only inside the authorized proposal, as before.
        var kitchenPreparationFailure: RuntimeException? = null
        val kitchen = try { foodStockKitchenExecutionAdmission(request, preparationView, recipes) }
        catch (failure: SalesRuleViolation) { kitchenPreparationFailure = failure; null }
        catch (failure: FoodCapacityExceeded) { kitchenPreparationFailure = failure; null }
        catch (failure: LocalStockHydrationCapacityUnavailable) { kitchenPreparationFailure = failure; null }
        catch (failure: LocalSalesWireCapacityExceeded) { kitchenPreparationFailure = failure; null }
        val restriction = if (request is LocalFoodStockRequestV2.CreateDraft || legacyQuotation || activity?.sourceEnabled == false ||
            ((request is LocalFoodStockRequestV2.ActivityProtectedAdd || request is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd) && requestedActivity == null) ||
            quotationUnavailable)
            PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
        else kitchen?.restriction
        return FoodStockPrepared(context, admission, request is LocalFoodStockRequestV2.Financial,
            activity?.recoveryResources.orEmpty() + kitchen?.resources.orEmpty() + quotation?.resources.orEmpty(), restriction,
            scalarRefusal) foodBody@ { command, at, sink ->
            if (scalarRefusal != null) {
                // Only these newly qualified scalar refusals run early. Current Master
                // authorization has already joined, and native sink/context, exact same-writer
                // sources/claims and enabled activity still qualify below. A proven rejection
                // cannot create or add work, so unrelated downstream inventory/recipe
                // preparation is unnecessary. Every other request keeps its original ordering.
                sink.requireContext(view.requireStockRelational(), context)
                val refusalLoan = FoodReadView(CommerceLoan(view, stock, sink))
                val captured = recipes.prepareActivity(refusalLoan, checkNotNull(requestedActivity))
                check(captured.sourceEnabled && captured.recoveryResources == checkNotNull(activity).recoveryResources)
                return@foodBody scalarRefusal.decide(refusalLoan, context, captured)
            }
            quotationPreparationFailure?.let { throw it }
            kitchenPreparationFailure?.let { throw it }
            sink.requireContext(view.requireStockRelational(), context)
            val loan = FoodReadView(CommerceLoan(view, stock, sink))
            // The first read admits resources; this genuine sink-bearing read retains originals.
            val capturedActivity = requestedActivity?.let { selectedActivity ->
                recipes.prepareActivity(loan, selectedActivity).also {
                    check(it.sourceEnabled && it.recoveryResources == checkNotNull(activity).recoveryResources)
                }
            }
            val inventory = stock.streamRelational(loan, context, sink, authority, at)
            val writes = FoodStockWrites(loan, context, sink)
            when (request) {
                is LocalFoodStockRequestV2.ActivityProtected -> createDraft(request.request, loan, context, inventory, writes,
                    checkNotNull(capturedActivity).recoveryResources)
                is LocalFoodStockRequestV2.ActivityProtectedAdd -> createOrAdd(request.request, loan, context, at, inventory, writes,
                    checkNotNull(capturedActivity).recoveryResources)
                is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> createQuotedAdd(request.request, loan, context, at, inventory, writes,
                    checkNotNull(capturedActivity).recoveryResources, checkNotNull(quotationSource), checkNotNull(quotation))
                is LocalFoodStockRequestV2.CreateDraft -> error("food.stock.legacy_draft_requires_original")
                is LocalFoodStockRequestV2.Preparation -> when (val selected = request.request) {
                    is LocalFoodRequestV1.Create -> error("food.stock.legacy_quote_requires_original")
                    is LocalFoodRequestV1.Add -> error("food.stock.legacy_add_requires_original")
                    is LocalFoodRequestV1.EditServiceDetails -> editServiceDetails(selected, loan, context, inventory, writes)
                    is LocalFoodRequestV1.Send -> sendUncollected(selected, loan, context, at, inventory, writes,
                        checkNotNull(kitchen?.binding))
                    else -> changePortion(selected, loan, context, at, inventory, writes)
                }
                is LocalFoodStockRequestV2.Financial -> when (val selected = request.request) {
                    is LocalFoodFinancialRequestV1.BeginCollection -> begin(selected, loan, context, command, at, inventory, writes,
                        checkNotNull(kitchen?.binding))
                    is LocalFoodFinancialRequestV1.FinalizePaid -> finalize(selected, loan, context, command, inventory, writes)
                    is LocalFoodFinancialRequestV1.SendReserved -> sendReserved(selected, loan, context, at, inventory, writes)
                    is LocalFoodFinancialRequestV1.PaidPickup -> pickup(selected, loan, context, at, inventory, writes)
                    is LocalFoodFinancialRequestV1.CancelWholeUnsent -> cancel(selected, loan, context, command, at, inventory, writes)
                    is LocalFoodFinancialRequestV1.CloseResolvedWork -> close(selected, loan, context, inventory, writes)
                    is LocalFoodFinancialRequestV1.RecordQualityClaim -> claim(selected, loan, context, at, inventory, writes)
                    else -> error("food.stock.physical_admission_not_installed")
                }
            }
        }
    }

    private suspend fun createDraft(request: LocalFoodDraftCreateV1, view: CommandReadView, context: CommandContext,
        inventory: LocalStockRelationalTransaction, writes: FoodStockWrites,
        activityResources: Set<RecoveryResourceKey>): StreamedMutationDecision {
        foodFinancialRule(foodRead(view, LocalFoodProfileV1.ORDER, request.orderId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val owner = foodOpenOwnerIndex(view, context.originalActorId)
        val admission = foodWorkAdmission(view, context.originalActorId)
        foodFinancialRule(admission.accepting, SalesRule.CLOSING_BLOCKED)
        val priorIds = foodOpenOrderIds(owner)
        foodFinancialRule(request.orderId !in priorIds, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        foodCapacity(priorIds.size < LocalFoodProfileV1.MAX_ACTIVE_INDEX)
        val after = writes.food(LocalFoodProfileV1.ORDER, request.orderId,
            FoodBodyV1.Order(context.originalActorId, context.originalActorId, request.activity, request.service,
                emptyList(), emptyList(), Money(Currency.EGP, 0), deliveryDetails = request.deliveryDetails),
            null, LocalFoodDraftProfileV1.ORDER_SCHEMA)
        writes.food(LocalFoodProfileV1.OWNER_INDEX, context.originalActorId,
            FoodBodyV1.OwnerIndex(priorIds + request.orderId), owner)
        return writes.finish(inventory, localSalesEncode(foodResult(LocalFoodDraftProfileV1.CREATED, after), LocalFoodResultV1.serializer()),
            setOf(orderResource(request.orderId), orderResource(context.originalActorId)) + admission.resources +
                foodActorResource(context.originalActorId) + activityResources)
    }

    private suspend fun editServiceDetails(request: LocalFoodRequestV1.EditServiceDetails, view: CommandReadView,
        context: CommandContext, inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        val order = foodOrder(view, request.orderId)
        foodFinancialOwner(context, order, request.expectedOrderRevision)
        val after = writes.food(LocalFoodProfileV1.ORDER, request.orderId, order.value.copy(
            service = order.value.service.copy(reference = request.reference, seatNote = request.seatNote)), order.order,
            if (order.order.row.schema == LocalFoodDraftProfileV1.ORDER_SCHEMA) LocalFoodDraftProfileV1.ORDER_SCHEMA else foodStockSchema(LocalFoodProfileV1.ORDER))
        return writes.finish(inventory, localSalesEncode(foodResult(foodResultCode(request), after), LocalFoodResultV1.serializer()),
            foodResources(order) + foodActorResource(context.originalActorId))
    }

    private suspend fun quotedCurrentOrder(view: CommandReadView, order: FoodOrderCut,
        source: TransactionFoodRecipeQuotationVerificationSourceV1): FoodQuotedCurrentOrder {
        val originalMembers = foodOriginalQuotationPortions(view, order, recipes, quotationSource = source)
        val byId = originalMembers.associateBy { it.portion.envelope.id }
        check(byId.size == originalMembers.size)
        val portions = order.value.portionIds.map { id -> byId[id]?.portion ?: foodPortion(view, order, id, recipes) }
        val batches = order.value.batchIds.map { id -> foodRead(view, LocalFoodProfileV1.PRICE_BATCH, id)
            ?: error("food.quotation.price_batch_missing") }
        requireFoodQuotedOrderCommercial(order.order.envelope, portions.map { it.envelope }, batches.map { it.envelope })
        return FoodQuotedCurrentOrder(portions, batches, foodResources(order, portions) + originalMembers.flatMap { it.resources })
    }

    private suspend fun prepareQuotedAdd(request: LocalFoodRequestV1.Add, view: CommandReadView,
        context: CommandContext, source: TransactionFoodRecipeQuotationVerificationSourceV1): FoodQuotedAddCapture? {
        val order = foodOrder(view, request.orderId)
        foodFinancialOwner(context, order, request.expectedOrderRevision)
        foodFinancialRule(order.value.financialCollectionId == null, SalesRule.PAYABLE_LOCKED)
        val admission = foodWorkAdmission(view, context.originalActorId)
        foodFinancialRule(admission.accepting, SalesRule.CLOSING_BLOCKED)
        foodCapacity(order.value.portionIds.size + request.portions.size <= LocalFoodProfileV1.MAX_ORDER_PORTIONS)
        val prior = quotedCurrentOrder(view, order, source)
        for (selection in request.portions) foodFinancialRule(
            foodRead(view, LocalFoodProfileV1.PORTION, selection.portionId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val batchId = DomainId(context.operationId.value)
        foodFinancialRule(foodRead(view, LocalFoodProfileV1.PRICE_BATCH, batchId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val prepared = source.prepareQuotation(view, order.value.activity, order.value.service,
            request.expectedCatalogRevision, request.portions) ?: return null
        check(prepared.scope == context.scope)
        return FoodQuotedAddCapture(prepared, prior.resources + admission.resources + prepared.recoveryResources +
            orderResource(batchId) + request.portions.map { orderResource(it.portionId) })
    }

    private suspend fun createQuotedAdd(request: LocalFoodRequestV1.Add, view: CommandReadView, context: CommandContext,
        at: UtcInstant, inventory: LocalStockRelationalTransaction, writes: FoodStockWrites,
        activityResources: Set<RecoveryResourceKey>, source: TransactionFoodRecipeQuotationVerificationSourceV1,
        admission: FoodQuotedAddCapture): StreamedMutationDecision {
        // This is the real sink-bearing source loan; the earlier observation is not authority.
        val captured = prepareQuotedAdd(request, view, context, source)
            ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.UNSUPPORTED_SELECTOR)
        check(captured.resources == admission.resources)
        val order = foodOrder(view, request.orderId)
        foodFinancialOwner(context, order, request.expectedOrderRevision)
        foodFinancialRule(order.value.financialCollectionId == null, SalesRule.PAYABLE_LOCKED)
        val batchId = DomainId(context.operationId.value)
        val quoted = captured.prepared.quote(batchId, at)
        requireSameFoodQuotationBatch(admission.prepared.quote(batchId, at), quoted)
        check(quoted.quote.snapshotId == batchId && quoted.quote.revision == Revision(0) &&
            quoted.quotations.map { it.portionId } == request.portions.map { it.portionId } &&
            quoted.quote.lines.map { it.portionId } == request.portions.map { it.portionId })
        val priceBody = FoodBodyV1.PriceBatch(request.orderId, StoredPricingSnapshotCodec.encode(quoted.quote), quoted.priceSources)
        val price = FoodEnvelopeV1(context.scope, LocalFoodProfileV1.PRICE_BATCH, batchId, Revision(1), context.operationId, priceBody)
        val quotations = quoted.quotations.map { quotation ->
            foodFinancialRule(foodRead(view, FoodQuotationStorageV1.RECIPE_QUOTATION, quotation.quotationId) == null,
                SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            FoodEnvelopeV1(context.scope, FoodQuotationStorageV1.RECIPE_QUOTATION, quotation.quotationId, Revision(1),
                context.operationId, FoodBodyV1.RecipeQuotation(request.orderId, batchId, quotation))
        }
        val portions = request.portions.zip(quoted.quotations).map { (selection, quotation) ->
            FoodEnvelopeV1(context.scope, LocalFoodProfileV1.PORTION, selection.portionId, Revision(1), context.operationId,
                FoodBodyV1.QuotedPortion(request.orderId, selection, batchId, quotation.quotationId,
                    FoodPreparationState(selection.portionId, Revision(0), FoodPreparationStatus.NOT_SENT)))
        }
        val body = order.value.copy(portionIds = order.value.portionIds + portions.map { it.id },
            batchIds = order.value.batchIds + batchId, originalQuotedTotal = order.value.originalQuotedTotal + quoted.quote.payable)
        val after = FoodEnvelopeV1(context.scope, LocalFoodProfileV1.ORDER, request.orderId,
            order.order.row.revision.next(), context.operationId, body)
        requireSameFoodQuotationBatch(quoted, foodQuotationBatch(after, portions, quotations, price))
        // Bound/canonical encoding is established for the entire finite write set before emitting it.
        foodStoredEnvelopeBytes(price, foodStockSchema(price.kind))
        quotations.forEach { foodStoredEnvelopeBytes(it, FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA) }
        portions.forEach { foodStoredEnvelopeBytes(it, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA) }
        foodStoredEnvelopeBytes(after, foodStockSchema(after.kind))
        writes.food(price.kind, price.id, priceBody, null)
        for (index in portions.indices) {
            val quotation = quotations[index]
            val portion = portions[index]
            writes.food(quotation.kind, quotation.id, quotation.body, null, FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA)
            writes.food(portion.kind, portion.id, portion.body, null, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
        }
        val saved = writes.food(after.kind, after.id, body, order.order)
        val quotationResources = portions.indices.flatMap { foodQuotationResources(portions[it], quotations[it], price) }.toSet()
        return writes.finish(inventory, localSalesEncode(foodResult(foodResultCode(request), saved, portions), LocalFoodResultV1.serializer()),
            captured.resources + activityResources + quotationResources + foodActorResource(context.originalActorId))
    }

    private suspend fun createOrAdd(request: LocalFoodRequestV1, view: CommandReadView, context: CommandContext, at: UtcInstant,
        inventory: LocalStockRelationalTransaction, writes: FoodStockWrites,
        activityResources: Set<RecoveryResourceKey> = emptySet()): StreamedMutationDecision {
        val creating = request is LocalFoodRequestV1.Create
        val orderId = request.foodOrderId()
        val order = if (creating) null else foodOrder(view, orderId)
        if (order != null) {
            foodFinancialOwner(context, order, request.foodOrderRevision())
            foodFinancialRule(order.value.financialCollectionId == null, SalesRule.PAYABLE_LOCKED)
        } else foodFinancialRule(foodRead(view, LocalFoodProfileV1.ORDER, orderId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val owner = order?.ownerIndex ?: foodOpenOwnerIndex(view, context.originalActorId)
        val admission = foodWorkAdmission(view, context.originalActorId)
        foodFinancialRule(admission.accepting, SalesRule.CLOSING_BLOCKED)
        val selections = when (request) { is LocalFoodRequestV1.Create -> request.portions; is LocalFoodRequestV1.Add -> request.portions; else -> error("food.stock.create_or_add") }
        foodCapacity((order?.value?.portionIds?.size ?: 0) + selections.size <= LocalFoodProfileV1.MAX_ORDER_PORTIONS)
        if (creating) foodCapacity(foodOpenOrderIds(owner).size < LocalFoodProfileV1.MAX_ACTIVE_INDEX)
        for (selection in selections) foodFinancialRule(foodRead(view, LocalFoodProfileV1.PORTION, selection.portionId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val batchId = DomainId(context.operationId.value)
        check(foodRead(view, LocalFoodProfileV1.PRICE_BATCH, batchId) == null)
        val activity = if (request is LocalFoodRequestV1.Create) request.activity else checkNotNull(order).value.activity
        val service = if (request is LocalFoodRequestV1.Create) request.service else checkNotNull(order).value.service
        val catalogRevision = if (request is LocalFoodRequestV1.Create) request.expectedCatalogRevision else (request as LocalFoodRequestV1.Add).expectedCatalogRevision
        val prepared = recipes.prepare(view, activity, service, catalogRevision, selections)
            ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.UNSUPPORTED_SELECTOR)
        check(prepared.scope == context.scope)
        val quoted = prepared.quote(batchId, at)
        check(quoted.quote.snapshotId == batchId && quoted.quote.revision == Revision(0) && quoted.snapshots.map { it.portionId } == selections.map { it.portionId } &&
            quoted.quote.lines.map { it.portionId } == selections.map { it.portionId })
        writes.food(LocalFoodProfileV1.PRICE_BATCH, batchId,
            FoodBodyV1.PriceBatch(orderId, StoredPricingSnapshotCodec.encode(quoted.quote), quoted.priceSources), null)
        val portions = selections.zip(quoted.snapshots).map { (selection, snapshot) -> writes.food(LocalFoodProfileV1.PORTION, selection.portionId,
            FoodBodyV1.Portion(orderId, selection, batchId, snapshot, FoodPreparationState(selection.portionId, Revision(0), FoodPreparationStatus.NOT_SENT)), null) }
        val body = order?.value?.let { it.copy(portionIds = it.portionIds + portions.map { p -> p.id }, batchIds = it.batchIds + batchId,
            originalQuotedTotal = it.originalQuotedTotal + quoted.quote.payable) }
            ?: FoodBodyV1.Order(context.originalActorId, context.originalActorId, activity, service, portions.map { it.id }, listOf(batchId), quoted.quote.payable)
        val after = writes.food(LocalFoodProfileV1.ORDER, orderId, body, order?.order)
        if (creating) writes.food(LocalFoodProfileV1.OWNER_INDEX, context.originalActorId,
            FoodBodyV1.OwnerIndex(foodOpenOrderIds(owner) + orderId), owner)
        val resources = (order?.let { foodResources(it) } ?: setOf(orderResource(orderId), orderResource(context.originalActorId))) +
            admission.resources + prepared.recoveryResources + activityResources + orderResource(batchId) +
                selections.map { orderResource(it.portionId) } + foodActorResource(context.originalActorId)
        return writes.finish(inventory, localSalesEncode(foodResult(foodResultCode(request), after, portions), LocalFoodResultV1.serializer()), resources)
    }

    private suspend fun begin(request: LocalFoodFinancialRequestV1.BeginCollection, view: CommandReadView, context: CommandContext,
        command: SalesCommand, at: UtcInstant, inventory: LocalStockRelationalTransaction, writes: FoodStockWrites,
        preparation: FoodQuotationBindingPreparation): StreamedMutationDecision {
        val order = foodOrder(view, request.orderId)
        foodFinancialOwner(context, order, request.expectedOrderRevision)
        foodFinancialRule(order.order.row.schema != LocalFoodDraftProfileV1.ORDER_SCHEMA, SalesRule.INVALID_STATE)
        foodFinancialRule(order.value.financialCollectionId == null, SalesRule.PAYABLE_LOCKED)
        val binding = foodPrepareQuotationBinding(view, recipes, order, order.value.portionIds, wholeOrder = true)
        foodFinancialRule(binding.restriction == null, SalesRule.UNSUPPORTED_STOCK_TIMING)
        preparation.requireMatches(binding)
        val portions = binding.members
        val batches = order.value.batchIds.map { foodRead(view, LocalFoodProfileV1.PRICE_BATCH, it) ?: error("food.stock.price_missing") }
        val quote = foodFinancialQuote(request.orderId, batches.map { it.envelope })
        check(quote.portionIds == order.value.portionIds && quote.payable == order.value.originalQuotedTotal)
        foodFinancialRule(portions.none { it.requireRouted().preparation.status in foodChargeDecisionStates }, SalesRule.INVALID_STATE)
        val cash = LocalSalesStorage.cash(view, request.sessionId)
        foodFinancialRule(foodFinancialRead(view, LocalFoodFinancialProfileV1.COLLECTION, request.collectionId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        // Only literal routed before rows can carry prior stock. A quoted before is never replaced
        // with a fabricated routed FoodRow to make this native current proof accept it.
        foodFinancialRelationalStock(view, stock, inventory.observations(), binding.before.filter {
            it.envelope.body is FoodBodyV1.Portion
        })
        val unsent = portions.filter { it.requireRouted().preparation.status == FoodPreparationStatus.NOT_SENT }
        for (member in unsent) foodFinancialRule(recipes.currentForSend(view, member.requireRouted().recipe), SalesRule.UNSUPPORTED_STOCK_TIMING)
        val links = portions.map { member ->
            val p = member.requireRouted(); val id = member.before.envelope.id
            FoodFinancialPortion(id, p.priceBatchId, p.recipe.snapshotId, p.selection.quantity, foodReservationId(id),
                if (p.preparation.issueId == null) FoodStockOrigin.RESERVED_FOR_COLLECTION else FoodStockOrigin.ISSUED_BEFORE_COLLECTION, p.preparation.issueId)
        }
        val collection = FoodCollections.begin(command, context.scope, request.orderId, request.expectedOrderRevision,
            order.value.creatorId, order.value.ownerId, cash.session.value, request.expectedSessionRevision, request.collectionId, quote, links)
        val demands = unsent.map { member -> foodDemand(member.requireRouted(),
            foodFinancialStockBinding(collection), request.orderId, request.expectedOrderRevision) }
        val authorityResources = recipes.observeReservationAuthority(view, demands.flatMap { it.lines })
        if (demands.isNotEmpty()) inventory.reserveFoodBeforeCollection(demands)
        // Every unsent portion remains independently reserved. No selected-subset or split commit.
        for (member in unsent) check(inventory.plannedReservation(foodReservationId(member.before.envelope.id)).let {
            it.status == ReservationStatus.RESERVED && it.binding == foodFinancialStockBinding(collection) && it.expiresAtEpochMillis == null
        })
        val bindingResources = mutableSetOf<RecoveryResourceKey>()
        for (member in portions.filter { it.bindsQuotation }) {
            val p = member.requireRouted()
            // One real schema transition. NOT_SENT keeps no issue/tasks; the collection pins
            // the genuine reservation separately until SendReserved issues that allocation.
            val after = writes.food(LocalFoodProfileV1.PORTION, member.before.envelope.id,
                p.copy(preparation = p.preparation.copy(revision = p.preparation.revision.next())), member.before)
            bindingResources += member.afterResources(after)
        }
        writes.food(LocalFoodProfileV1.ORDER, request.orderId, order.value.copy(financialCollectionId = collection.id), order.order)
        writes.financial(LocalFoodFinancialProfileV1.COLLECTION, collection.id,
            FoodFinancialBodyV1.Collection(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeCollection(collection) }), null, batches)
        return writes.finish(inventory, writes.financialResult("food.collection.locked", collection.id, quote.payable),
            binding.resources + bindingResources + cashResources(cash) + orderResource(collection.id) +
                authorityResources + foodActorResource(context.originalActorId))
    }

    private suspend fun finalize(request: LocalFoodFinancialRequestV1.FinalizePaid, view: CommandReadView, context: CommandContext,
        command: SalesCommand, inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        foodFinancialRelationalStock(view, stock, inventory.observations(), cut.portions, cut.value)
        foodFinancialRule(cut.value.currentOwnerId == context.originalActorId && cut.order.value.ownerId == context.originalActorId, SalesRule.OWNER_REQUIRED)
        foodFinancialRule(cut.portions.none { (it.envelope.body as FoodBodyV1.Portion).preparation.status in foodChargeDecisionStates }, SalesRule.INVALID_STATE)
        foodFinancialRule(foodFinancialRead(view, LocalFoodFinancialProfileV1.SALE, request.saleId) == null &&
            foodFinancialRead(view, LocalFoodFinancialProfileV1.ENTITLEMENT, request.saleId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val after = FoodCollections.finalize(command, cut.value, request.expectedCollectionRevision, request.saleId)
        writes.financial(LocalFoodFinancialProfileV1.COLLECTION, cut.value.id,
            FoodFinancialBodyV1.Collection(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeCollection(after.collection) }), cut.row, cut.batches)
        writes.financial(LocalFoodFinancialProfileV1.SALE, request.saleId,
            FoodFinancialBodyV1.Sale(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeSale(after.sale) }), null, cut.batches)
        writes.financial(LocalFoodFinancialProfileV1.ENTITLEMENT, request.saleId,
            FoodFinancialBodyV1.Entitlement(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeEntitlement(after.entitlement) }), null, cut.batches)
        // Existing exact payment coverage only. No cash, new tender observation, stock issue or fulfillment.
        return writes.finish(inventory, writes.financialResult("food.sale.finalized", request.saleId, after.sale.quote.payable),
            foodFinancialResources(cut) + entitlementResource(request.saleId) + foodActorResource(context.originalActorId))
    }

    private suspend fun sendUncollected(request: LocalFoodRequestV1.Send, view: CommandReadView, context: CommandContext, at: UtcInstant,
        inventory: LocalStockRelationalTransaction, writes: FoodStockWrites,
        preparation: FoodQuotationBindingPreparation): StreamedMutationDecision {
        val order = foodOrder(view, request.orderId)
        foodFinancialOwner(context, order, request.expectedOrderRevision)
        foodFinancialRule(order.order.row.schema != LocalFoodDraftProfileV1.ORDER_SCHEMA, SalesRule.INVALID_STATE)
        foodFinancialRule(order.value.financialCollectionId == null, SalesRule.PAYABLE_LOCKED)
        foodFinancialRule(request.portionIds.all { it in order.value.portionIds }, SalesRule.INVALID_STATE)
        val selected = foodPrepareQuotationBinding(view, recipes, order, request.portionIds, wholeOrder = false)
        foodFinancialRule(selected.restriction == null, SalesRule.UNSUPPORTED_STOCK_TIMING)
        preparation.requireMatches(selected)
        for (member in selected.members) {
            val p = member.requireRouted()
            foodFinancialRule(p.preparation.status == FoodPreparationStatus.NOT_SENT && recipes.currentForSend(view, p.recipe), SalesRule.INVALID_STATE)
        }
        val binding = CollectionBinding(request.orderId, request.expectedOrderRevision, foodStockContextId(request.orderId),
            context.originalActorId, CollectionExposure.UNCOLLECTED)
        val demands = selected.members.map { foodDemand(it.requireRouted(), binding, request.orderId, request.expectedOrderRevision) }
        val authorityResources = recipes.observeReservationAuthority(view, demands.flatMap { it.lines })
        inventory.reserveUncollectedFood(demands)
        val changed = sendSelected(selected.before, null, view, context, at, inventory, writes,
            selected.members.map { it.requireRouted() })
        writeFoodStations(view, writes, changed.map { it.body as FoodBodyV1.Portion }, true)
        val after = writes.food(LocalFoodProfileV1.ORDER, request.orderId, order.value, order.order)
        writes.jobs(changed.flatMap { foodDeliveryJobs(order.value, it.body as FoodBodyV1.Portion, "PREPARE") })
        return writes.finish(inventory, localSalesEncode(foodResult("food.sent", after, changed), LocalFoodResultV1.serializer()),
            selected.resources + selected.members.zip(changed).flatMap { (member, after) -> member.afterResources(after) } +
                foodSendResources(changed) + authorityResources + foodActorResource(context.originalActorId))
    }
    private suspend fun sendReserved(request: LocalFoodFinancialRequestV1.SendReserved, view: CommandReadView, context: CommandContext,
        at: UtcInstant, inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
        foodFinancialRule(cut.value.orderId == request.orderId && cut.value.revision == request.expectedCollectionRevision &&
            cut.value.cancellationRefundId == null, SalesRule.REVISION_CONFLICT)
        foodFinancialRelationalStock(view, stock, inventory.observations(), cut.portions, cut.value)
        foodFinancialRule(request.portionIds.all { id -> cut.portions.any { it.envelope.id == id } }, SalesRule.INVALID_STATE)
        val selected = request.portionIds.map { id -> cut.portions.single { it.envelope.id == id } }
        for (row in selected) {
            val p = row.envelope.body as FoodBodyV1.Portion
            foodFinancialRule(p.preparation.status == FoodPreparationStatus.NOT_SENT &&
                cut.value.portions.single { it.portionId == row.envelope.id }.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION &&
                recipes.currentForSend(view, p.recipe), SalesRule.INVALID_STATE)
        }
        val changed = sendSelected(selected, cut.value.id, view, context, at, inventory, writes)
        writeFoodStations(view, writes, changed.map { it.body as FoodBodyV1.Portion }, true)
        writes.food(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value, cut.order.order)
        writes.jobs(changed.flatMap { foodDeliveryJobs(cut.order.value, it.body as FoodBodyV1.Portion, "PREPARE") })
        return writes.finish(inventory, writes.financialResult("food.reserved.sent", request.orderId),
            foodFinancialResources(cut) + foodSendResources(changed) + foodActorResource(context.originalActorId))
    }
    private suspend fun sendSelected(selected: List<FoodRow>, collectionId: DomainId?, view: CommandReadView, context: CommandContext,
        at: UtcInstant, inventory: LocalStockRelationalTransaction, writes: FoodStockWrites,
        routed: List<FoodBodyV1.Portion> = selected.map { it.envelope.body as FoodBodyV1.Portion }): List<FoodEnvelopeV1> {
        check(routed.size == selected.size)
        return selected.mapIndexed { index, row ->
            val p = routed[index]
            check(p.selection.portionId == row.envelope.id)
            val reservation = inventory.plannedReservation(foodReservationId(row.envelope.id))
            inventory.issueReserved(LocalStockReservedIssue(reservation.id, reservation.revision, foodIssueId(row.envelope.id), ValueIssuePurpose.DIRECT_RECIPE,
                RecipeIssueRequest(p.recipe.snapshotId, p.recipe.routes.map { it.stationId })))
            val tasks = p.recipe.routes.map { FoodStationTask(foodTaskId(row.envelope.id, it.stationId), it.stationId, it.mode, FoodStationStatus.QUEUED) }
            for (task in tasks) writes.claim(LocalFoodProfileV1.claim("task", task.taskId), p.orderId)
            val next = FoodPreparation.sent(p.preparation, p.preparation.revision, reservation.id, foodIssueId(row.envelope.id), tasks, at)
            writes.food(LocalFoodProfileV1.PORTION, row.envelope.id, p.copy(preparation = next, originalIssue = null,
                financialCollectionId = collectionId, normalizedIssue = foodIssueReference(context, inventory.plannedIssue(foodIssueId(row.envelope.id)))), row)
        }
    }

    private suspend fun changePortion(request: LocalFoodRequestV1, view: CommandReadView, context: CommandContext, at: UtcInstant,
        inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        val order = foodOrder(view, request.foodOrderId())
        foodFinancialRule(order.order.envelope.revision == request.foodOrderRevision(), SalesRule.REVISION_CONFLICT)
        val stationAction = request is LocalFoodRequestV1.Station || request is LocalFoodRequestV1.DiscardStarted
        if (!stationAction) foodFinancialOwner(context, order, request.foodOrderRevision())
        foodFinancialRule(order.order.row.schema != LocalFoodDraftProfileV1.ORDER_SCHEMA, SalesRule.INVALID_STATE)
        foodFinancialRule(request.foodPortionId() in order.value.portionIds, SalesRule.INVALID_STATE)
        val observed = foodRead(view, LocalFoodProfileV1.PORTION, request.foodPortionId()) ?: error("food.portion_missing")
        if (observed.envelope.body is FoodBodyV1.QuotedPortion) {
            if (request !is LocalFoodRequestV1.Cancel) throw SalesRuleViolation(SalesRule.INVALID_STATE)
            return cancelQuotedPortion(request, view, context, at, inventory, writes, order)
        }
        val row = foodPortion(view, order, request.foodPortionId(), recipes)
        val p = row.envelope.body as FoodBodyV1.Portion
        val confirming = request is LocalFoodRequestV1.Station && request.action == LocalFoodStationActionV1.CONFIRM_UNSTARTED
        val financial = if (confirming) foodKitchenFinancialCut(view, order, recipes) else null
        val current = foodFinancialRelationalStock(view, stock, inventory.observations(), listOf(row), financial?.value)
        val before = p.preparation
        foodFinancialRule(before.revision == request.foodPortionRevision(), SalesRule.REVISION_CONFLICT)
        if (request is LocalFoodRequestV1.Cancel && before.status == FoodPreparationStatus.NOT_SENT)
            foodFinancialRule(order.value.financialCollectionId == null, SalesRule.PAYABLE_LOCKED)
        val next = when (request) {
            is LocalFoodRequestV1.Station -> {
                foodFinancialRule(before.tasks.any { it.stationId == request.stationId }, SalesRule.PERMISSION_DENIED)
                when (request.action) {
                    LocalFoodStationActionV1.START -> {
                        val value = FoodPreparation.start(before, request.expectedPortionRevision, request.stationId, at)
                        val issue = current.issues.getValue(checkNotNull(before.issueId))
                        inventory.acknowledgeKitchenStart(issue.id, issue.revision, request.stationId); value
                    }
                    LocalFoodStationActionV1.READY -> FoodPreparation.ready(before, request.expectedPortionRevision, request.stationId, at)
                    LocalFoodStationActionV1.CONFIRM_UNSTARTED -> {
                        requireFoodKitchenTaskSources(p) { view.claim(it) }
                        val value = FoodPreparation.confirmUnstarted(before, request.expectedPortionRevision, request.stationId,
                            checkNotNull(request.confirmationId), context.originalActorId, checkNotNull(request.returnDisposition), at)
                        writes.claim(LocalFoodProfileV1.claim("confirmation", checkNotNull(request.confirmationId)), row.envelope.id)
                        if (value.status == FoodPreparationStatus.CANCELLED_BEFORE_START) {
                            val issue = current.issues.getValue(checkNotNull(before.issueId))
                            inventory.restoreRecipeBeforeStart(foodKitchenRestoration(context.operationId, p, value, issue))
                        }
                        value
                    }
                }
            }
            is LocalFoodRequestV1.Cancel -> FoodPreparation.requestCancellation(before, request.expectedPortionRevision, request.reasonId, at)
            is LocalFoodRequestV1.DiscardStarted -> {
                foodFinancialRule(before.tasks.any { it.stationId == request.stationId }, SalesRule.PERMISSION_DENIED)
                val value = FoodPreparation.discardStarted(before, request.expectedPortionRevision)
                val issue = current.issues.getValue(checkNotNull(before.issueId))
                inventory.classifyWasteAfterStart(issue.id, issue.revision, checkNotNull(before.cancellationReasonId)); value
            }
            is LocalFoodRequestV1.Serve -> {
                foodFinancialRule(order.value.service.channel == LocalSalesChannel.DINE_IN, SalesRule.PAYABLE_LOCKED)
                FoodPreparation.serve(before, request.expectedPortionRevision, at)
            }
            else -> error("food.stock.portion_action")
        }
        val changed = writes.food(LocalFoodProfileV1.PORTION, row.envelope.id, p.copy(preparation = next), row)
        val finished = next.status in setOf(FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START) ||
            next.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER
        if (finished && before.tasks.isNotEmpty()) writeFoodStations(view, writes, listOf(changed.body as FoodBodyV1.Portion), false)
        else requireFoodCurrentStations(view, p)
        val after = writes.food(LocalFoodProfileV1.ORDER, request.foodOrderId(), order.value, order.order)
        val jobs = if (request is LocalFoodRequestV1.Cancel && before.tasks.isNotEmpty())
            foodDeliveryJobs(order.value, changed.body as FoodBodyV1.Portion, "CANCEL_REQUEST_CONFIRM_REQUIRED") else emptyList()
        writes.jobs(jobs)
        return writes.finish(inventory, localSalesEncode(foodResult(foodResultCode(request), after, listOf(changed)), LocalFoodResultV1.serializer()),
            foodResources(order, listOf(row)) + foodActorResource(context.originalActorId) +
                financial?.let(::foodFinancialResources).orEmpty() +
                if (confirming) foodKitchenConfirmationResources(next) else emptySet())
    }

    private suspend fun cancelQuotedPortion(request: LocalFoodRequestV1.Cancel, view: CommandReadView,
        context: CommandContext, at: UtcInstant, inventory: LocalStockRelationalTransaction,
        writes: FoodStockWrites, order: FoodOrderCut): StreamedMutationDecision {
        foodFinancialOwner(context, order, request.expectedOrderRevision)
        foodFinancialRule(order.value.financialCollectionId == null, SalesRule.PAYABLE_LOCKED)
        val source = recipes as? TransactionFoodRecipeQuotationVerificationSourceV1
            ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        val cut = foodQuotedPortion(view, order, request.portionId, source)
        val portion = cut.portion.envelope.body as FoodBodyV1.QuotedPortion
        foodFinancialRule(portion.preparation.revision == request.expectedPortionRevision, SalesRule.REVISION_CONFLICT)
        foodFinancialRule(portion.preparation.status == FoodPreparationStatus.NOT_SENT, SalesRule.INVALID_STATE)
        val next = FoodPreparation.requestCancellation(portion.preparation, request.expectedPortionRevision, request.reasonId, at)
        val changed = writes.food(LocalFoodProfileV1.PORTION, request.portionId,
            portion.copy(preparation = next), cut.portion, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
        val after = writes.food(LocalFoodProfileV1.ORDER, request.orderId, order.value, order.order)
        requireFoodQuotationPair(after, changed, cut.quotation.envelope, cut.priceBatch.envelope, cut.batch)
        return writes.finish(inventory, localSalesEncode(foodResult(foodResultCode(request), after, listOf(changed)), LocalFoodResultV1.serializer()),
            foodResources(order, listOf(cut.portion)) + cut.resources + foodActorResource(context.originalActorId))
    }

    private suspend fun pickup(request: LocalFoodFinancialRequestV1.PaidPickup, view: CommandReadView, context: CommandContext, at: UtcInstant,
        inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
        foodFinancialRule(cut.value.orderId == request.orderId && cut.value.status == FoodCollectionStatus.FINALIZED_PAID &&
            cut.value.cancellationRefundId == null && cut.order.value.service.channel in setOf(LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY), SalesRule.INSUFFICIENT_COVERAGE)
        foodFinancialRelationalStock(view, stock, inventory.observations(), cut.portions, cut.value)
        val saleId = checkNotNull(cut.value.saleId)
        val sale = requiredFinancial(view, LocalFoodFinancialProfileV1.SALE, saleId)
        val entitlement = requiredFinancial(view, LocalFoodFinancialProfileV1.ENTITLEMENT, saleId)
        val rights = foodFinancialEntitlement(entitlement.envelope)
        requireFoodPaidSource(cut.value, foodFinancialSale(sale.envelope), rights)
        foodFinancialRule(rights.cursors.single { it.portionId == request.portionId }.consumedQuantity.scaled == 0L, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val row = cut.portions.single { it.envelope.id == request.portionId }; val p = row.envelope.body as FoodBodyV1.Portion
        val after = writes.food(LocalFoodProfileV1.PORTION, row.envelope.id,
            p.copy(preparation = FoodPreparation.pickUp(p.preparation, request.expectedPortionRevision, at)), row)
        writeFoodStations(view, writes, listOf(after.body as FoodBodyV1.Portion), false)
        writes.food(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value, cut.order.order)
        return writes.finish(inventory, writes.financialResult("food.paid_pickup.recorded", request.orderId),
            foodFinancialResources(cut) + foodActorResource(context.originalActorId))
    }

    private suspend fun cancel(request: LocalFoodFinancialRequestV1.CancelWholeUnsent, view: CommandReadView, context: CommandContext,
        command: SalesCommand, at: UtcInstant, inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
        val current = foodFinancialRelationalStock(view, stock, inventory.observations(), cut.portions, cut.value)
        foodFinancialRule(foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND, request.refundId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val index = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND_INDEX, cut.value.orderId)
        val paid = cut.value.saleId?.let { requiredFinancial(view, LocalFoodFinancialProfileV1.SALE, it) }
        val rightsRow = cut.value.saleId?.let { requiredFinancial(view, LocalFoodFinancialProfileV1.ENTITLEMENT, it) }
        val rights = rightsRow?.let { foodFinancialEntitlement(it.envelope) }
        if (paid != null && rights != null) requireFoodPaidSource(cut.value, foodFinancialSale(paid.envelope), rights)
        val after = FoodCollections.cancelWhollyUnsent(command, cut.value, request.expectedCollectionRevision,
            cut.portions.map { (it.envelope.body as FoodBodyV1.Portion).preparation }, request.refundId, request.reasonId, rights)
        val resolution = CollectionResolutionReference(foodCancellationResolutionId(context.operationId), cut.value.orderId, cut.value.id, request.refundId)
        for (link in cut.value.portions) inventory.release(LocalStockRelease(link.reservationId,
            current.reservations.getValue(link.reservationId).revision, resolution))
        writes.financial(LocalFoodFinancialProfileV1.COLLECTION, cut.value.id,
            FoodFinancialBodyV1.Collection(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeCollection(after.collection) }), cut.row, cut.batches)
        writes.financial(LocalFoodFinancialProfileV1.REFUND, request.refundId,
            FoodFinancialBodyV1.Refund(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeRefund(after.refund) }), null, cut.batches)
        val ids = (index?.envelope?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty(); foodCapacity(ids.size < 32)
        writes.financial(LocalFoodFinancialProfileV1.REFUND_INDEX, cut.value.orderId, FoodFinancialBodyV1.RefundIndex(cut.value.orderId, ids + request.refundId), index)
        after.entitlement?.let { writes.financial(LocalFoodFinancialProfileV1.ENTITLEMENT, it.sale.id,
            FoodFinancialBodyV1.Entitlement(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeEntitlement(it) }), rightsRow, cut.batches) }
        for (row in cut.portions) {
            val p = row.envelope.body as FoodBodyV1.Portion
            writes.food(LocalFoodProfileV1.PORTION, row.envelope.id,
                p.copy(preparation = FoodPreparation.requestCancellation(p.preparation, p.preparation.revision, request.reasonId, at)), row)
        }
        writes.food(LocalFoodProfileV1.ORDER, cut.value.orderId, cut.order.value, cut.order.order)
        writes.claim(LocalFoodFinancialProfileV1.source("unsent-resolution", resolution.resolutionId), request.refundId)
        return writes.finish(inventory, writes.financialResult("food.unsent.cancelled_refund_obligation", request.refundId, after.refund.amount),
            foodFinancialResources(cut) + entitlementResource(request.refundId) + entitlementResource(cut.value.orderId) +
                orderResource(request.reasonId) + orderResource(resolution.resolutionId) + foodActorResource(context.originalActorId))
    }

    private suspend fun close(request: LocalFoodFinancialRequestV1.CloseResolvedWork, view: CommandReadView, context: CommandContext,
        inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
        foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
        foodFinancialRule(cut.value.orderId == request.orderId && cut.value.revision == request.expectedCollectionRevision, SalesRule.REVISION_CONFLICT)
        foodFinancialRelationalStock(view, stock, inventory.observations(), cut.portions, cut.value)
        val claimIndexId = foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, request.orderId)
        val claims = foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX, claimIndexId)
        val index = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND_INDEX, request.orderId)
        val ids = (index?.envelope?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty()
        check(ids == listOfNotNull(cut.value.cancellationRefundId))
        val refunds = ids.map { foodFinancialRefund(requiredFinancial(view, LocalFoodFinancialProfileV1.REFUND, it).envelope) }
        refunds.forEach { requireFoodRefundSource(cut.value, it) }
        foodFinancialRule((claims?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty().isEmpty() &&
            !cut.value.unresolved && refunds.all { !it.unresolved && it.due.minor == 0L }, SalesRule.CLOSING_BLOCKED)
        cut.value.saleId?.let { id -> requireFoodPaidSource(cut.value, foodFinancialSale(requiredFinancial(view, LocalFoodFinancialProfileV1.SALE, id).envelope),
            foodFinancialEntitlement(requiredFinancial(view, LocalFoodFinancialProfileV1.ENTITLEMENT, id).envelope)) }
        val complete = if (cut.value.cancellationRefundId != null) cut.portions.all { (it.envelope.body as FoodBodyV1.Portion).preparation.let { p ->
            p.status == FoodPreparationStatus.CANCELLED_BEFORE_START && p.issueId == null && p.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER
        } } else cut.value.status == FoodCollectionStatus.FINALIZED_PAID && cut.portions.all {
            (it.envelope.body as FoodBodyV1.Portion).preparation.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER }
        foodFinancialRule(complete, SalesRule.CLOSING_BLOCKED)
        writes.food(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value.copy(closedByOperationId = context.operationId), cut.order.order)
        writes.food(LocalFoodProfileV1.OWNER_INDEX, cut.order.value.ownerId,
            FoodBodyV1.OwnerIndex(foodOpenOrderIds(cut.order.ownerIndex) - request.orderId), cut.order.ownerIndex)
        return writes.finish(inventory, writes.financialResult("food.work.closed", request.orderId), foodFinancialResources(cut) +
            ids.map(::entitlementResource) + entitlementResource(request.orderId) + entitlementResource(claimIndexId) + foodActorResource(context.originalActorId))
    }

    private suspend fun claim(request: LocalFoodFinancialRequestV1.RecordQualityClaim, view: CommandReadView, context: CommandContext, at: UtcInstant,
        inventory: LocalStockRelationalTransaction, writes: FoodStockWrites): StreamedMutationDecision {
        foodFinancialRule(foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM, request.claimId) == null, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        foodFinancialRule(request.facts.claimedAt <= at && request.facts.evidence.occurredAt <= at, SalesRule.INVALID_EVIDENCE)
        val ownerIndexId = foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, context.originalActorId)
        val owner = foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX, ownerIndexId)
        val order = request.facts.assertedOrderId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX,
            foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, it)) }
        writes.financial(LocalFoodFinancialProfileV1.CLAIM, request.claimId,
            FoodFinancialBodyV1.Claim(request.facts, context.originalActorId, at, context.originalActorId, null), null)
        suspend fun append(row: FoodFinancialRow?, target: FoodClaimIndexTargetV1, id: DomainId) {
            val ids = (row?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty(); foodCapacity(ids.size < 64)
            writes.financial(LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(target, id), FoodFinancialBodyV1.ClaimIndex(target, id, ids + request.claimId), row)
        }
        append(owner, FoodClaimIndexTargetV1.OWNER, context.originalActorId)
        request.facts.assertedOrderId?.let { append(order, FoodClaimIndexTargetV1.ORDER, it) }
        return writes.finish(inventory, writes.financialResult("food.quality_claim.recorded", request.claimId),
            foodClaimResources(request, context.originalActorId) + foodActorResource(context.originalActorId))
    }
    private suspend fun requiredFinancial(view: CommandReadView, kind: String, id: DomainId): FoodFinancialRow =
        foodFinancialRead(view, kind, id) ?: error("food.stock.financial_source_missing")
}

private fun foodActorResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
private fun foodDemand(portion: FoodBodyV1.Portion, binding: CollectionBinding, setId: DomainId, revision: Revision): LocalStockReservationDemand =
    LocalStockReservationDemand(foodReservationId(portion.selection.portionId), binding, setId, revision, portion.recipe.ingredients.map {
        LocalStockAllocationRequest(foodIngredientLine(portion.selection.portionId, it.componentId), it.skuId, portion.recipe.warehouseId, it.requiredBaseQuantity) })
private fun foodSendResources(portions: List<FoodEnvelopeV1>): Set<RecoveryResourceKey> = portions.flatMap { row ->
    val p = row.body as FoodBodyV1.Portion
    listOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, foodIssueId(row.id)), RecoveryResourceKey(RecoveryResourceKind.STOCK, foodReservationId(row.id))) +
        p.preparation.tasks.flatMap { listOf(orderResource(it.stationId), orderResource(it.taskId)) }
}.toSet()
private fun foodClaimResources(request: LocalFoodFinancialRequestV1.RecordQualityClaim, actor: DomainId): Set<RecoveryResourceKey> =
    setOf(entitlementResource(request.claimId), entitlementResource(foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, actor)), orderResource(actor)) +
        listOfNotNull(request.facts.assertedOrderId?.let { entitlementResource(foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, it)) },
            request.facts.assertedOrderId?.let(::orderResource), request.facts.assertedSaleId?.let(::entitlementResource),
            request.facts.assertedPortionId?.let(::orderResource), request.facts.evidence.evidenceId.let(::orderResource))
private suspend fun requireFoodCurrentStations(view: CommandReadView, portion: FoodBodyV1.Portion) {
    for (task in portion.preparation.tasks) {
        val row = foodRead(view, LocalFoodProfileV1.STATION_INDEX, task.stationId) ?: error("food.stock.station_index_missing")
        check((row.envelope.body as FoodBodyV1.StationIndex).tasks.single { it.taskId == task.taskId } ==
            FoodTaskLocatorV1(task.taskId, portion.orderId, portion.selection.portionId))
    }
}
private suspend fun writeFoodStations(view: CommandReadView, writes: FoodStockWrites, portions: List<FoodBodyV1.Portion>, adding: Boolean) {
    for (station in portions.flatMap { it.preparation.tasks }.map { it.stationId }.distinct()) {
        val row = foodRead(view, LocalFoodProfileV1.STATION_INDEX, station)
        val prior = (row?.envelope?.body as? FoodBodyV1.StationIndex)?.tasks.orEmpty()
        val selected = portions.filter { p -> p.preparation.tasks.any { it.stationId == station } }.map {
            FoodTaskLocatorV1(foodTaskId(it.selection.portionId, station), it.orderId, it.selection.portionId) }
        check(selected.all { (it !in prior) == adding })
        val after = if (adding) prior + selected else prior.filterNot { old -> selected.any { it.taskId == old.taskId } }
        foodCapacity(after.size <= LocalFoodProfileV1.MAX_ACTIVE_INDEX)
        writes.food(LocalFoodProfileV1.STATION_INDEX, station, FoodBodyV1.StationIndex(after), row)
    }
}

private suspend fun foodStockAdmission(request: LocalFoodStockRequestV2, view: CommandReadView, context: CommandContext): CommerceAdmission {
    if (request is LocalFoodStockRequestV2.ActivityProtected) return CommerceAdmission(SecurityAction.ORDER_CREATE,
        request.request.orderId, Revision(0), context.originalActorId, "food.operation", null)
    if (request is LocalFoodStockRequestV2.CreateDraft) return CommerceAdmission(SecurityAction.ORDER_CREATE,
        request.request.orderId, Revision(0), context.originalActorId, "food.operation", null)
    val preparation = when (request) {
        is LocalFoodStockRequestV2.ActivityProtectedAdd -> request.request
        is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> request.request
        is LocalFoodStockRequestV2.Preparation -> request.request
        else -> null
    }
    if (preparation != null) {
        val r = preparation
        val order = if (r is LocalFoodRequestV1.Create) null else foodObserveRow(view, LocalFoodProfileV1.ORDER, r.foodOrderId())?.envelope?.body as? FoodBodyV1.Order
        return CommerceAdmission(foodAction(r), foodTarget(r), r.foodOrderRevision(), order?.ownerId ?: context.originalActorId, "food.operation", null)
    }
    val r = (request as LocalFoodStockRequestV2.Financial).request
    if (r is LocalFoodFinancialRequestV1.RecordQualityClaim) return CommerceAdmission(SecurityAction.RETURN_CLAIM,
        r.claimId, Revision(0), context.originalActorId, "food.quality.claim", null)
    if (r is LocalFoodFinancialRequestV1.FinalizePaid || r is LocalFoodFinancialRequestV1.CancelWholeUnsent) {
        val id = if (r is LocalFoodFinancialRequestV1.FinalizePaid) r.collectionId else (r as LocalFoodFinancialRequestV1.CancelWholeUnsent).collectionId
        val revision = if (r is LocalFoodFinancialRequestV1.FinalizePaid) r.expectedCollectionRevision else (r as LocalFoodFinancialRequestV1.CancelWholeUnsent).expectedCollectionRevision
        val value = foodFinancialObserve(view, LocalFoodFinancialProfileV1.COLLECTION, id)?.second?.let(::foodFinancialCollection)
        val amount = if (r is LocalFoodFinancialRequestV1.CancelWholeUnsent) value?.collected else value?.quote?.payable
        val action = if (r is LocalFoodFinancialRequestV1.CancelWholeUnsent) SecurityAction.COLLECTION_RESOLVE else
            if (amount?.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
        return CommerceAdmission(action, id, revision, value?.currentOwnerId ?: context.originalActorId,
            if (r is LocalFoodFinancialRequestV1.FinalizePaid) "food.collection.finalize" else "food.cancel_whole_unsent", amount ?: Money(Currency.EGP, 0))
    }
    val (id, revision, reason) = when (r) {
        is LocalFoodFinancialRequestV1.BeginCollection -> Triple(r.orderId, r.expectedOrderRevision, "food.collection.begin")
        is LocalFoodFinancialRequestV1.SendReserved -> Triple(r.orderId, r.expectedOrderRevision, "food.collection.send_reserved")
        is LocalFoodFinancialRequestV1.PaidPickup -> Triple(r.orderId, r.expectedOrderRevision, "food.paid_pickup")
        is LocalFoodFinancialRequestV1.CloseResolvedWork -> Triple(r.orderId, r.expectedOrderRevision, "food.close_resolved_work")
        else -> error("food.stock.financial_action")
    }
    val order = foodObserveRow(view, LocalFoodProfileV1.ORDER, id)?.envelope?.body as? FoodBodyV1.Order
    return CommerceAdmission(if (r is LocalFoodFinancialRequestV1.BeginCollection) SecurityAction.COLLECTION_BEGIN else SecurityAction.ORDER_EDIT,
        id, revision, order?.ownerId ?: context.originalActorId, reason,
        if (r is LocalFoodFinancialRequestV1.BeginCollection) order?.originalQuotedTotal ?: Money(Currency.EGP, 0) else null)
}
private class FoodStockPrepared(private val context: CommandContext, admission: CommerceAdmission, private val financial: Boolean,
    activityResources: Set<RecoveryResourceKey>, override val admissionRestriction: PreparedMutationAdmissionRestriction?,
    private val foodCheckRefusal: LocalFoodCheckPreparedRefusal?,
    private val body: suspend (SalesCommand, UtcInstant, StreamedCommandSink) -> StreamedMutationDecision) : PreparedStreamingMutation() {
    private val reason = admission.reason
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(admission.action, context.scope, admission.target,
        admission.revision, context.operationId, context.semanticDigest, reason, admission.owner, admission.amount)
    override val affectedResources: Set<RecoveryResourceKey> = setOf(orderResource(admission.target), orderResource(admission.owner),
        foodActorResource(context.originalActorId)) + activityResources
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = if (admission.action == SecurityAction.RETURN_CLAIM)
        setOf(RecoveryResourceKind.AUTHORIZATION) else setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)
    override val allowsActionApproval: Boolean = false
    override fun supportsRecordedRejection(decision: StreamedMutationDecision.Reject): Boolean =
        !financial && foodCheckRefusal?.supports(decision, requirement, affectedResources) == true
    override suspend fun propose(authorization: AuthorizedAction, recordedAt: UtcInstant, sink: StreamedCommandSink): StreamedMutationDecision {
        check(admissionRestriction == null && authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.approvalId == null && authorization.isBoundTo(requirement, context.originalActorId))
        fun refuse(code: String): StreamedMutationDecision = StreamedMutationDecision.Reject(code,
            if (financial) localSalesEncode(LocalFoodFinancialResultV1(code, null), LocalFoodFinancialResultV1.serializer(), 16_384)
            else localSalesEncode(LocalFoodResultV1(code, null, null), LocalFoodResultV1.serializer()))
        return try { body(SalesCommand(context.operationId, authorization, recordedAt.value, context.semanticDigest, reason), recordedAt, sink) }
        catch (_: FoodCapacityExceeded) { refuse("food.stock.capacity_required") }
        catch (_: LocalStockHydrationCapacityUnavailable) { refuse("food.stock.capacity_required") }
        catch (_: LocalSalesWireCapacityExceeded) { refuse("food.stock.capacity_required") }
        catch (failure: LocalSalesQuoteUnavailable) { refuse(if (failure.reason == LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED)
            "food.stock.capacity_required" else "food.stock.current_recipe_required") }
        catch (failure: SalesRuleViolation) { refuse("food.stock.rule.${failure.rule.name.lowercase()}") }
        catch (failure: LocalStockRejected) {
            when (failure.failure) {
                LocalStockFailure.BOUND_EXCEEDED -> refuse("food.stock.capacity_required")
                LocalStockFailure.INSUFFICIENT_STOCK, LocalStockFailure.UNSAFE_STOCK -> refuse("food.stock.unavailable")
                else -> throw failure
            }
        }
        // IO, arithmetic, corrupt source and unavailable native settlement remain uncertainty.
    }
}

/**
 * Required whole commerce half of the normalized stock producer. The stock owner first proves
 * every physical row/value/source transition; this verifier binds that complete posting to the
 * literal food request, immutable quote/recipe, EVERY portion, original money and all companion
 * effects/claims/jobs/results/resources. No stock-only Boolean or fabricated historical grant.
 */
public class LocalFoodStockProducer(private val recipes: TransactionFoodStockSource) : LocalStockRelationalCompositeVerifier {
    override val commands: Set<CommandKey> = setOf(StockStreamingCommandsV2.FOOD)
    override suspend fun requireComplete(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection) {
        LocalFoodProducer.requireNormalizedComplete(original, stock, recipes)
    }
    override suspend fun requireOriginalSource(original: RetainedOriginalV2, observation: StreamedSourceObservationV2.Aggregate) {
        check(observation.key.kind in LocalFoodProfileV1.kinds - setOf(LocalFoodProfileV1.EVENT, LocalFoodProfileV1.BEFORE) ||
            observation.key.kind == FoodQuotationStorageV1.RECIPE_QUOTATION ||
            observation.key.kind in foodFinancialDocumentKinds)
        when (original) {
            // The repository has already required the WHOLE prior streamed producer and literal
            // write identity through this same loan. This is closed profile dispatch, not approval.
            is RetainedOriginalV2.Streamed -> check(original.evidence.identity.producer.command in commands)
            is RetainedOriginalV2.Legacy -> {
                val snapshots = when (original.evidence.command) {
                    LocalFoodProfileV1.COMMAND -> LocalFoodProducer.requireComplete(original.evidence)
                    LocalFoodFinancialProfileV1.COMMAND -> LocalFoodFinancialProducer.requireComplete(original.evidence)
                    else -> error("food.stock.original_profile_unavailable")
                }
                snapshots.forEach { recipes.requireLegacyRecipe(it, original.evidence.scope) }
            }
        }
    }
    override suspend fun requireOriginalSource(view: CommandReadView, original: RetainedOriginalV2,
        observation: StreamedSourceObservationV2.Aggregate) {
        if (original is RetainedOriginalV2.Legacy && original.evidence.command in foodPaymentCommands) {
            // Only the genuine scalar collection root. Raw/native intake is never money.
            check(observation.key.kind == LocalFoodFinancialProfileV1.COLLECTION &&
                observation.schema == foodFinancialSchemaV2(observation.key.kind) && isFoodPaymentFinancialProducer(original.evidence))
            val bounded = foodReadView(view)
            val actual = bounded.foodProducer(original.evidence.operationId)
            requireSameFoodPaymentOriginal(actual, original.evidence)
            bounded.requireFoodPredecessors(actual)
            check(actual.aggregateWrites.single { it.key == observation.key }.let {
                it.storageGeneration == observation.generation && it.schema == observation.schema &&
                    it.payload == observation.payload && it.payloadDigest == observation.payloadHash
            })
            foodPaymentInputs(actual).filter { it.kind == LocalFoodProfileV1.PORTION }.forEach {
                recipes.requireLegacyRecipe((foodPaymentFoodEnvelope(it, actual.scope).body as FoodBodyV1.Portion).recipe, actual.scope)
            }
        } else if (original is RetainedOriginalV2.Legacy && original.evidence.command == LocalFoodRefundProfile.COMMAND) {
            // Complete genuine accepted-liability payout root; never raw/native money or stock restoration.
            check(observation.key.kind == LocalFoodFinancialProfileV1.REFUND &&
                observation.schema == foodFinancialSchemaV2(observation.key.kind) && isFoodRefundFinancialProducer(original.evidence))
            val bounded = foodReadView(view)
            val actual = bounded.foodProducer(original.evidence.operationId)
            requireSameFoodRefundOriginal(actual, original.evidence)
            bounded.requireFoodPredecessors(actual)
            check(actual.aggregateWrites.single { it.key == observation.key }.let {
                it.storageGeneration == observation.generation && it.schema == observation.schema &&
                    it.payload == observation.payload && it.payloadDigest == observation.payloadHash
            })
        } else requireOriginalSource(original, observation)
    }
}

internal suspend fun requireFoodNormalizedProducer(original: RetainedStreamedCommand,
    stock: LocalStockRelationalInspection, recipes: TransactionFoodStockSource) {
    FoodStockOriginalProof(original, stock, recipes).requireComplete()
}

internal enum class FoodPriceBatchProducerProfile { ROUTED_CREATE_OR_ADD, QUOTED_ADD }

/** Exact immutable batch/request classification only. Callers still prove the genuine producer,
 * literal original write and native custody; this does not assert current sidecar absence. */
internal fun foodPriceBatchProducerProfile(priceBatch: FoodEnvelopeV1,
    legacyProducer: RetainedCommandEvidence?, normalizedProducer: RetainedStreamedCommand?): FoodPriceBatchProducerProfile {
    requireFoodEnvelope(priceBatch)
    val priced = priceBatch.body as? FoodBodyV1.PriceBatch ?: error("food.quotation.price_batch_required")
    check((legacyProducer == null) != (normalizedProducer == null) &&
        priceBatch.id.value == priceBatch.operationId.value)
    val (profile, request) = if (legacyProducer != null) {
        check(legacyProducer.committed && legacyProducer.scope == priceBatch.scope &&
            legacyProducer.operationId == priceBatch.operationId && legacyProducer.command == LocalFoodProfileV1.COMMAND &&
            LocalFoodCodecV1.normalize(legacyProducer.normalized) == legacyProducer.normalized &&
            semanticDigest(legacyProducer.scope, legacyProducer.originalActorId, legacyProducer.command,
                legacyProducer.normalized) == legacyProducer.semanticDigest)
        FoodPriceBatchProducerProfile.ROUTED_CREATE_OR_ADD to LocalFoodCodecV1.decode(legacyProducer.normalized)
    } else {
        val producer = checkNotNull(normalizedProducer)
        check(producer.identity.scope == priceBatch.scope && producer.identity.producer.operationId == priceBatch.operationId &&
            producer.identity.producer.command == StockStreamingCommandsV2.FOOD &&
            LocalFoodStockCodecV2.normalize(producer.normalized) == producer.normalized &&
            semanticDigest(producer.identity.scope, producer.identity.producer.originalActorId,
                producer.identity.producer.command, producer.normalized) == producer.identity.producer.semanticDigest)
        when (val wrapper = LocalFoodStockCodecV2.decode(producer.normalized)) {
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> FoodPriceBatchProducerProfile.QUOTED_ADD to wrapper.request
            is LocalFoodStockRequestV2.ActivityProtectedAdd -> FoodPriceBatchProducerProfile.ROUTED_CREATE_OR_ADD to wrapper.request
            is LocalFoodStockRequestV2.Preparation -> FoodPriceBatchProducerProfile.ROUTED_CREATE_OR_ADD to wrapper.request
            else -> error("food.quotation.batch_origin_profile_required")
        }
    }
    val selections = when (request) {
        is LocalFoodRequestV1.Create -> request.portions
        is LocalFoodRequestV1.Add -> request.portions
        else -> error("food.quotation.batch_origin_create_or_add_required")
    }
    LocalFoodSelectionGroups.group(selections)
    val quote = StoredPricingSnapshotCodec.decode(priced.quote)
    check(request.foodOrderId() == priced.orderId && quote.lines.map { Triple(it.lineId, it.portionId, it.quantity) } ==
        selections.map { Triple(it.commercialLineId, it.portionId, it.quantity) })
    return profile
}

private data class FoodOriginalRow(val row: LocalCommerceOriginalRow, val value: FoodEnvelopeV1)
private data class FoodOriginalFinancialRow(val row: LocalCommerceOriginalRow, val value: FoodFinancialEnvelopeV1)
private class FoodOriginalOrder(val row: FoodOriginalRow, val index: FoodOriginalRow) {
    val value: FoodBodyV1.Order get() = row.value.body as FoodBodyV1.Order
}
private class FoodOriginalCollection(val row: FoodOriginalFinancialRow, val order: FoodOriginalOrder,
    val portions: List<FoodOriginalRow>, val batches: List<FoodOriginalRow>) {
    val value: FoodCollection get() = foodFinancialCollection(row.value)
}
private class FoodOriginalQuotationBatch(val portions: List<FoodOriginalRow>, val quotations: List<FoodOriginalRow>,
    val priceBatch: FoodOriginalRow, val batch: LocalFoodRecipeQuotationBatchV1)

private class FoodStockOriginalProof(private val original: RetainedStreamedCommand,
    private val stock: LocalStockRelationalInspection, private val recipes: TransactionFoodStockSource) {
    private val proof = CommerceProof(original)
    private val legacyCustody = FoodRetainedCustody(original, recipes, stock)
    private val actor: DomainId get() = original.identity.producer.originalActorId
    private val operation: OperationId get() = original.identity.producer.operationId
    private val at: UtcInstant get() = original.identity.recordedAt
    private val foodBefore = mutableMapOf<AggregateKey, FoodOriginalRow?>()
    private val financialBefore = mutableMapOf<AggregateKey, FoodOriginalFinancialRow?>()
    // Literal origins are confined to this active proof. Queued native producers still have to
    // finish in the repository's outer traversal; these maps are not completion certificates.
    private val foodOriginalProducers = mutableMapOf<OperationId, RetainedOriginalV2>()
    private val priceBatchProfiles = mutableMapOf<DomainId, FoodPriceBatchProducerProfile>()
    private val quotationBatches = mutableMapOf<DomainId, FoodOriginalQuotationBatch>()
    private val revisions = mutableListOf<LocalFoodStorageRevisionV1>()
    private val claims = mutableListOf<ClaimChange>()
    private var jobs: List<JobIntent> = emptyList()
    private var result: StoredBytes? = null
    private var expectedRestoration: LocalStockKitchenRestoration? = null
    private val resources = mutableSetOf<RecoveryResourceKey>()

    suspend fun requireComplete() {
        check(original.identity.producer.command == StockStreamingCommandsV2.FOOD && stock.operationId == operation &&
            stock.scope == original.identity.scope && stock.recordedAt == at &&
            LocalFoodStockCodecV2.normalize(original.normalized) == original.normalized &&
            semanticDigest(original.identity.scope, actor, StockStreamingCommandsV2.FOOD, original.normalized) == original.identity.producer.semanticDigest)
        proof.load()
        when (val wrapper = LocalFoodStockCodecV2.decode(original.normalized)) {
            is LocalFoodStockRequestV2.ActivityProtected -> {
                resources += recipes.requireOriginalActivity(proof.sources, wrapper.request.activity)
                createDraft(wrapper.request)
            }
            is LocalFoodStockRequestV2.ActivityProtectedAdd -> createOrAdd(wrapper.request, requireActivity = true)
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> createQuotedAdd(wrapper.request)
            is LocalFoodStockRequestV2.CreateDraft -> createDraft(wrapper.request)
            is LocalFoodStockRequestV2.Preparation -> when (val request = wrapper.request) {
                is LocalFoodRequestV1.Create, is LocalFoodRequestV1.Add -> createOrAdd(request)
                is LocalFoodRequestV1.EditServiceDetails -> editServiceDetails(request)
                is LocalFoodRequestV1.Send -> sendUncollected(request)
                is LocalFoodRequestV1.Station, is LocalFoodRequestV1.Cancel, is LocalFoodRequestV1.DiscardStarted, is LocalFoodRequestV1.Serve -> changePortion(request)
                else -> error("food.stock.original_handoff_profile_required")
            }
            is LocalFoodStockRequestV2.Financial -> when (val request = wrapper.request) {
                is LocalFoodFinancialRequestV1.BeginCollection -> begin(request)
                is LocalFoodFinancialRequestV1.FinalizePaid -> finalize(request)
                is LocalFoodFinancialRequestV1.SendReserved -> sendReserved(request)
                is LocalFoodFinancialRequestV1.PaidPickup -> pickup(request)
                is LocalFoodFinancialRequestV1.CancelWholeUnsent -> cancel(request)
                is LocalFoodFinancialRequestV1.CloseResolvedWork -> close(request)
                is LocalFoodFinancialRequestV1.RecordQualityClaim -> claim(request)
                else -> error("food.stock.physical_profile_not_installed")
            }
        }
        // Global closure, including every financial/nonstation path. Merely checking the
        // restoration path would let an otherwise valid FOOD command conceal extra stock.
        var restorations = 0
        stock.restorationIntents { actual ->
            val expected = checkNotNull(expectedRestoration) { "food.stock.unrequested_restoration" }
            check(restorations++ == 0 && actual.restorationId == expected.restorationId &&
                actual.issueId == expected.issueId && actual.expectedRevision == expected.expectedRevision &&
                actual.confirmations == expected.confirmations)
        }
        check(restorations == if (expectedRestoration == null) 0 else 1)
        proof.finish(checkNotNull(result), claims, jobs)
        proof.requireSourceComplement(stock)
        stock.requireResources(resources + foodActorResource(actor) + commerceRowResources(original))
    }
    private suspend fun beforeFood(kind: String, id: DomainId): FoodOriginalRow? {
        val key = AggregateKey(kind, id)
        if (key in foodBefore) return foodBefore[key]
        check(kind in LocalFoodProfileV1.kinds - setOf(LocalFoodProfileV1.EVENT, LocalFoodProfileV1.BEFORE) ||
            kind == FoodQuotationStorageV1.RECIPE_QUOTATION)
        val row = proof.sources.aggregateOriginal(key)
        val owner = proof.sources.claim(foodSourceClaim(key))
        if (row == null) { check(owner == null); foodBefore[key] = null; return null }
        check(owner == id && (row.schema == LocalFoodProfileV1.schema(kind) || foodStreamedSchema(kind, row.schema)))
        val value = foodStoredEnvelope(row.schema, row.payload)
        check(value.scope == proof.sources.scope && value.kind == kind && value.id == id && value.revision == row.generation)
        proof.sources.aggregateOriginal(key, value.operationId)
        if (!foodStreamedSchema(kind, row.schema)) legacyCustody.requireWholeAndDirect(value.operationId)
        return FoodOriginalRow(row, value).also { foodBefore[key] = it }
    }
    private suspend fun requiredFood(kind: String, id: DomainId): FoodOriginalRow =
        beforeFood(kind, id) ?: error("food.stock.required_original_food_missing")
    private suspend fun beforeFinancial(kind: String, id: DomainId): FoodOriginalFinancialRow? {
        val key = AggregateKey(kind, id)
        if (key in financialBefore) return financialBefore[key]
        check(kind in foodFinancialDocumentKinds)
        val row = proof.sources.aggregateOriginal(key)
        val owner = proof.sources.claim(foodSourceClaim(key))
        if (row == null) { check(owner == null); financialBefore[key] = null; return null }
        check(owner == id)
        val value = when (row.schema) {
            foodStockSchema(kind) -> if (kind in foodStockFramedKinds) {
                val root = foodStockDocument(row.payload)
                val source = (proof.sources.original(root.operationId) as? RetainedOriginalV2.Streamed)?.evidence
                    ?: error("food.stock.original_document_missing")
                foodStockReadDocument(source, row.payload)
            } else foodFinancialEnvelope(row.payload)
            foodFinancialSchemaV2(kind) -> {
                val root = foodFinancialRootV2(row.payload)
                val source = (proof.sources.original(root.operationId) as? RetainedOriginalV2.Legacy)?.evidence
                    ?: error("food.stock.original_legacy_bundle_missing")
                if (isFoodPaymentFinancialProducer(source)) {
                    check(kind == LocalFoodFinancialProfileV1.COLLECTION)
                    FoodFinancialBundleReaderV2(source, foodPaymentStoredEvent(source).framing, allowStockInputs = true).document(row.payload)
                } else if (isFoodRefundFinancialProducer(source)) {
                    check(kind == LocalFoodFinancialProfileV1.REFUND)
                    FoodFinancialBundleReaderV2(source, foodRefundStoredEvent(source).framing, allowStockInputs = true).document(row.payload)
                } else if (isPaidFoodHandoffProducer(source)) {
                    FoodFinancialBundleReaderV2(source, paidFoodHandoffEvent(source).framing, allowStockInputs = true).document(row.payload)
                } else {
                    val event = foodFinancialStoredEvent(source)
                    FoodFinancialBundleReaderV2(source, checkNotNull(event.framing)).document(row.payload)
                }
            }
            LocalFoodFinancialProfileV1.schema(kind) -> foodFinancialEnvelope(row.payload)
            else -> error("food.stock.original_document_schema")
        }
        check(value.scope == proof.sources.scope && value.kind == kind && value.id == id && value.revision == row.generation)
        proof.sources.aggregateOriginal(key, value.operationId)
        if (row.schema != foodStockSchema(kind)) legacyCustody.requireWholeAndDirect(value.operationId)
        return FoodOriginalFinancialRow(row, value).also { financialBefore[key] = it }
    }
    private suspend fun requiredFinancial(kind: String, id: DomainId): FoodOriginalFinancialRow =
        beforeFinancial(kind, id) ?: error("food.stock.required_original_financial_missing")
    private fun peekFood(kind: String, id: DomainId): FoodEnvelopeV1 {
        val row = proof.write(AggregateKey(kind, id))
        check(foodStreamedSchema(kind, row.schema))
        return foodStoredEnvelope(row.schema, row.payload)
    }
    private suspend fun afterFood(kind: String, id: DomainId, body: FoodBodyV1, before: FoodOriginalRow?,
        schema: String = foodStockSchema(kind)): FoodEnvelopeV1 {
        val key = AggregateKey(kind, id)
        check(key in foodBefore && foodBefore[key] == before && foodStreamedSchema(kind, schema))
        val value = FoodEnvelopeV1(proof.sources.scope, kind, id, before?.row?.generation?.next() ?: Revision(1), operation, body)
        requireFoodStoredEnvelope(value, schema)
        proof.account(key, foodStoredEnvelopeBytes(value, schema), schema, before?.row)
        noteRoot(key, value.revision, before == null)
        return value
    }
    private suspend fun afterFinancial(kind: String, id: DomainId, before: FoodOriginalFinancialRow?,
        batches: List<FoodOriginalRow> = emptyList()): FoodFinancialEnvelopeV1 {
        val key = AggregateKey(kind, id)
        check(key in financialBefore && financialBefore[key] == before)
        val row = proof.write(key)
        check(row.schema == foodStockSchema(kind))
        val value = if (kind in foodStockFramedKinds) {
            val root = foodStockDocument(row.payload)
            check(root.batchIds == batches.map { it.value.id } && localSalesEncode(root, FoodStockDocumentV2.serializer(), 32_768) == row.payload)
            foodStockReadDocument(original, row.payload) { chunkKey, bytes ->
                proof.account(chunkKey, bytes, foodStockSchema(LocalFoodFinancialProfileV1.FRAME), null)
            }
        } else foodFinancialEnvelope(row.payload)
        check(value.scope == proof.sources.scope && value.kind == kind && value.id == id && value.operationId == operation &&
            value.revision == (before?.row?.generation?.next() ?: Revision(1)))
        proof.account(key, row.payload, foodStockSchema(kind), before?.row)
        noteRoot(key, value.revision, before == null)
        return value
    }
    private fun noteRoot(key: AggregateKey, revision: Revision, new: Boolean) {
        revisions += LocalFoodStorageRevisionV1(key.kind, key.id, revision)
        if (new) claims += ClaimChange.Acquire(foodSourceClaim(key), key.id)
    }
    private suspend fun acquire(key: ClaimKey, owner: DomainId) {
        check(proof.sources.claim(key) == null); claims += ClaimChange.Acquire(key, owner)
    }
    private fun financialResult(code: String, id: DomainId, amount: Money? = null) {
        check(result == null)
        result = localSalesEncode(LocalFoodFinancialResultV1(code, id, revisions.toList(), amount), LocalFoodFinancialResultV1.serializer(), 16_384)
    }
    private fun preparationResult(code: String, order: FoodEnvelopeV1, portions: List<FoodEnvelopeV1>) {
        check(result == null); result = localSalesEncode(foodResult(code, order, portions), LocalFoodResultV1.serializer())
    }
    private suspend fun order(id: DomainId): FoodOriginalOrder {
        val row = requiredFood(LocalFoodProfileV1.ORDER, id); val value = row.value.body as FoodBodyV1.Order
        val index = requiredFood(LocalFoodProfileV1.OWNER_INDEX, value.ownerId)
        check(value.closedByOperationId == null && id in (index.value.body as FoodBodyV1.OwnerIndex).orderIds)
        return FoodOriginalOrder(row, index)
    }
    private fun owner(order: FoodOriginalOrder, revision: Revision) {
        check(order.row.value.revision == revision && order.value.ownerId == actor && order.value.handoff == null && order.value.closedByOperationId == null)
    }
    private suspend fun portion(order: FoodOriginalOrder, id: DomainId): FoodOriginalRow {
        check(id in order.value.portionIds)
        val row = requiredFood(LocalFoodProfileV1.PORTION, id)
        if (row.value.body is FoodBodyV1.QuotedPortion) {
            val batch = quotationBatch(order, row)
            check(batch.portions.single { it.value.id == id }.row == row.row)
            return row
        }
        val p = row.value.body as? FoodBodyV1.Portion ?: error("food.stock.original_portion_required")
        check(p.orderId == order.row.value.id && p.priceBatchId in order.value.batchIds &&
            p.recipe.activity == order.value.activity && p.recipe.channel == order.value.service.channel)
        val priceBatch = requiredFood(LocalFoodProfileV1.PRICE_BATCH, p.priceBatchId)
        if (priceBatchProfile(priceBatch) == FoodPriceBatchProducerProfile.QUOTED_ADD) {
            val batch = quotationBatch(order, row)
            check(batch.portions.single { it.value.id == id }.row == row.row)
            return row
        }
        // Old routed producers have their own complete origin proof. Preserve the selected
        // legacy path: their consumers need not have observed every unselected batch member.
        requireLegacyQuotationObservation(row)
        resources += recipes.requireOriginalHistorical(proof.sources, p.recipe)
        val batch = priceBatch.value.body as FoodBodyV1.PriceBatch
        val priced = StoredPricingSnapshotCodec.decode(batch.quote)
        check(batch.orderId == p.orderId && priced.lines.single { it.portionId == id }.quantity == p.selection.quantity &&
            batch.sources.single { it.portionId == id }.skuId == p.recipe.pricingSkuId)
        return row
    }

    /** Same actual literal Aggregate producer and exact original AFTER write. Every streamed
     * producer is queued once in the native owner's closed proof, not recursively approved here. */
    private suspend fun foodOriginalProducer(row: FoodOriginalRow): RetainedOriginalV2 {
        var observed: StreamedSourceObservationV2.Aggregate? = null
        original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate
            if (source?.key == row.row.key) { check(observed == null); observed = source }
        }
        val source = checkNotNull(observed) { "food.quotation.original_aggregate_source_required" }
        check(source.generation == row.row.generation && source.schema == row.row.schema && source.payload == row.row.payload &&
            source.payloadHash == row.row.digest && source.producer.operationId == row.value.operationId)
        val cached = foodOriginalProducers[row.value.operationId]
        val actual = cached ?: stock.original(row.value.operationId) ?: error("food.quotation.original_producer_missing")
        when (actual) {
            is RetainedOriginalV2.Legacy -> {
                val producer = actual.evidence
                check(!foodStreamedSchema(row.value.kind, row.row.schema) && producer.committed &&
                    producer.scope == row.value.scope && producer.operationId == row.value.operationId &&
                    source.producer == StreamedProducerIdentityV2(producer.operationId, producer.command,
                        producer.originalActorId, producer.semanticDigest))
                val write = producer.aggregateWrites.single { it.key == row.row.key }
                check(write.storageGeneration == row.row.generation && write.schema == row.row.schema &&
                    write.payload == row.row.payload && write.payloadDigest == row.row.digest)
                // beforeFood already required whole/direct legacy custody on this same loan.
            }
            is RetainedOriginalV2.Streamed -> {
                val producer = actual.evidence
                check(foodStreamedSchema(row.value.kind, row.row.schema) && producer.identity.scope == row.value.scope &&
                    producer.identity.producer == source.producer && producer.identity.producer.command == StockStreamingCommandsV2.FOOD)
                val write = producer.writeRow(row.row.key) ?: error("food.quotation.original_write_missing")
                check(write.revision == row.row.generation && write.schema == row.row.schema &&
                    write.payload == row.row.payload && write.digest == row.row.digest)
                if (cached == null) stock.requireCompleteOriginal(producer)
            }
        }
        if (cached == null) check(foodOriginalProducers.put(row.value.operationId, actual) == null)
        return actual
    }

    private suspend fun priceBatchProfile(priceBatch: FoodOriginalRow): FoodPriceBatchProducerProfile {
        priceBatchProfiles[priceBatch.value.id]?.let { return it }
        val producer = foodOriginalProducer(priceBatch)
        val profile = foodPriceBatchProducerProfile(priceBatch.value,
            (producer as? RetainedOriginalV2.Legacy)?.evidence, (producer as? RetainedOriginalV2.Streamed)?.evidence)
        check(priceBatchProfiles.put(priceBatch.value.id, profile) == null)
        return profile
    }

    /** A proved routed origin is not inferred from a failed sidecar lookup. If this consumer
     * actually retained a sidecar observation, authenticate its real absence and claim too. */
    private suspend fun requireLegacyQuotationObservation(row: FoodOriginalRow) {
        val portion = row.value.body as FoodBodyV1.Portion
        val id = LocalFoodQuotationProfileV1.quotationId(row.value.id, portion.recipe.publicationId)
        val key = AggregateKey(FoodQuotationStorageV1.RECIPE_QUOTATION, id)
        var observed = false
        original.frames { frame -> when (val source = (frame as? RetainedCommandFrameV2.Source)?.value) {
            is StreamedSourceObservationV2.Aggregate -> if (source.key == key) observed = true
            is StreamedSourceObservationV2.Current -> if (source.key == key) observed = true
            is StreamedSourceObservationV2.CurrentAbsent -> if (source.key == key) observed = true
            else -> Unit
        } }
        if (observed) check(beforeFood(key.kind, id) == null) { "food.quotation.legacy_sidecar_unexpected" }
        // No observation is not SQL absence. The exact old batch producer determines this
        // profile; complete native current census and original source complement remain required.
    }

    /** Restrictive callers remain all-quoted even though the literal batch loader is neutral. */
    private suspend fun quotedQuotationBatch(order: FoodOriginalOrder, selected: FoodOriginalRow): FoodOriginalQuotationBatch {
        check(selected.value.body is FoodBodyV1.QuotedPortion) { "food.quotation.portion_required" }
        val batch = quotationBatch(order, selected)
        check(batch.portions.all { it.value.body is FoodBodyV1.QuotedPortion }) { "food.quotation.all_quoted_required" }
        return batch
    }

    private suspend fun quotationBatch(order: FoodOriginalOrder, selected: FoodOriginalRow): FoodOriginalQuotationBatch {
        val (orderId, batchId) = when (val portion = selected.value.body) {
            is FoodBodyV1.QuotedPortion -> portion.orderId to portion.priceBatchId
            is FoodBodyV1.Portion -> portion.orderId to portion.priceBatchId
            else -> error("food.quotation.portion_required")
        }
        check(orderId == order.row.value.id && batchId in order.value.batchIds)
        quotationBatches[batchId]?.let { return it }
        val source = recipes as? TransactionFoodRecipeQuotationVerificationSourceV1
            ?: error("food.quotation.original_source_unavailable")
        val priceBatch = requiredFood(LocalFoodProfileV1.PRICE_BATCH, batchId)
        check(priceBatchProfile(priceBatch) == FoodPriceBatchProducerProfile.QUOTED_ADD) {
            "food.quotation.sidecar_backed_batch_origin_required"
        }
        val price = priceBatch.value.body as? FoodBodyV1.PriceBatch ?: error("food.quotation.price_batch_required")
        val quote = StoredPricingSnapshotCodec.decode(price.quote)
        val portions = quote.lines.map { line ->
            check(line.portionId in order.value.portionIds)
            requiredFood(LocalFoodProfileV1.PORTION, line.portionId)
        }
        val quotations = portions.map { row ->
            val id = when (val value = row.value.body) {
                is FoodBodyV1.QuotedPortion -> value.quotationId
                is FoodBodyV1.Portion -> LocalFoodQuotationProfileV1.quotationId(row.value.id, value.recipe.publicationId)
                else -> error("food.quotation.portion_required")
            }
            requiredFood(FoodQuotationStorageV1.RECIPE_QUOTATION, id)
        }
        val batch = foodOriginalQuotationBatch(order.row.value, portions.map { it.value }, quotations.map { it.value }, priceBatch.value)
        for (index in portions.indices) {
            foodOriginalProducer(portions[index])
            foodOriginalProducer(quotations[index])
            resources += source.requireOriginalHistoricalQuotation(proof.sources, batch.quotations[index])
            (portions[index].value.body as? FoodBodyV1.Portion)?.let {
                resources += recipes.requireOriginalHistorical(proof.sources, it.recipe)
            }
            resources += foodOriginalQuotationResources(portions[index].value, quotations[index].value, priceBatch.value)
        }
        return FoodOriginalQuotationBatch(portions, quotations, priceBatch, batch).also {
            check(quotationBatches.put(batchId, it) == null)
        }
    }

    /** Authentic routing is proved on this binding operation's literal cut. The quoted before
     * body/schema and genuine producer remain untouched; only the separate projected body is used
     * for demands and the exact after transition. Recovery does not borrow today's kitchen grant. */
    private suspend fun bindingPortions(order: FoodOriginalOrder,
        before: List<FoodOriginalRow>): List<FoodBodyV1.Portion> {
        val quoted = before.any { it.value.body is FoodBodyV1.QuotedPortion }
        val routing = if (quoted) recipes as? TransactionFoodRecipeRoutingVerificationSource
            ?: error("food.quotation.original_routing_source_unavailable") else null
        if (quoted) resources += recipes.requireOriginalActivity(proof.sources, order.value.activity)
        return before.map { row -> when (val body = row.value.body) {
            is FoodBodyV1.Portion -> body
            is FoodBodyV1.QuotedPortion -> {
                check(body.preparation.status == FoodPreparationStatus.NOT_SENT)
                val batch = quotationBatch(order, row)
                val index = batch.portions.indexOfFirst { it.value.id == row.value.id }
                check(index >= 0 && batch.portions[index].row == row.row)
                val quotation = (batch.quotations[index].value.body as FoodBodyV1.RecipeQuotation).quotation
                val actual = peekFood(LocalFoodProfileV1.PORTION, row.value.id).body as? FoodBodyV1.Portion
                    ?: error("food.quotation.routed_after_required")
                val snapshot = FoodRecipeRouting.requireOriginalFacts(quotation, actual.recipe)
                resources += checkNotNull(routing).requireOriginalRouting(proof.sources, quotation, snapshot)
                foodQuotationBindingPortion(row.value, quotation, snapshot)
            }
            else -> error("food.quotation.portion_required")
        } }
    }

    private suspend fun bindingAfterResources(order: FoodOriginalOrder, before: FoodOriginalRow,
        after: FoodEnvelopeV1) {
        val batchId = when (val body = before.value.body) {
            is FoodBodyV1.QuotedPortion -> body.priceBatchId
            is FoodBodyV1.Portion -> body.priceBatchId
            else -> error("food.quotation.portion_required")
        }
        val priceBatch = requiredFood(LocalFoodProfileV1.PRICE_BATCH, batchId)
        if (priceBatchProfile(priceBatch) == FoodPriceBatchProducerProfile.ROUTED_CREATE_OR_ADD) return
        val batch = quotationBatch(order, before)
        val index = batch.portions.indexOfFirst { it.value.id == before.value.id }
        check(index >= 0 && batch.portions[index].row == before.row)
        resources += foodOriginalQuotationResources(after, batch.quotations[index].value, batch.priceBatch.value)
    }
    private suspend fun collection(id: DomainId): FoodOriginalCollection {
        val row = requiredFinancial(LocalFoodFinancialProfileV1.COLLECTION, id)
        val value = foodFinancialCollection(row.value); val order = order(value.orderId)
        check(order.value.financialCollectionId == id && order.value.ownerId == value.currentOwnerId &&
            order.value.creatorId == value.originalCreatorId && order.value.handoff == null && value.currentOwnerId == actor)
        val portions = order.value.portionIds.map { portion(order, it) }
        check(portions.all { it.value.body is FoodBodyV1.Portion }) { "food.stock.quoted_collection_unavailable" }
        val batches = order.value.batchIds.map { requiredFood(LocalFoodProfileV1.PRICE_BATCH, it) }
        requireFoodFinancialQuote(value, order.value, portions.map { it.value }, batches.map { it.value })
        requireBeforeStock(portions, value)
        return FoodOriginalCollection(row, order, portions, batches)
    }
    /** Actual financial source/quote, but no cashier-ownership authorization for a station actor. */
    private suspend fun kitchenCollection(order: FoodOriginalOrder): FoodOriginalCollection? {
        val id = order.value.financialCollectionId ?: return null
        val row = requiredFinancial(LocalFoodFinancialProfileV1.COLLECTION, id)
        val value = foodFinancialCollection(row.value)
        check(value.scope == proof.sources.scope && value.orderId == order.row.value.id &&
            value.currentOwnerId == order.value.ownerId && value.originalCreatorId == order.value.creatorId)
        val portions = order.value.portionIds.map { portion(order, it) }
        check(portions.all { it.value.body is FoodBodyV1.Portion }) { "food.stock.quoted_collection_unavailable" }
        val batches = order.value.batchIds.map { requiredFood(LocalFoodProfileV1.PRICE_BATCH, it) }
        requireFoodFinancialQuote(value, order.value, portions.map { it.value }, batches.map { it.value })
        return FoodOriginalCollection(row, order, portions, batches)
    }
    private fun orderResources(order: FoodOriginalOrder, portions: List<FoodOriginalRow> = emptyList()): Set<RecoveryResourceKey> =
        foodEnvelopeResources(order.row.value, order.index.value, portions.map { it.value })
    private fun collectionResources(cut: FoodOriginalCollection): Set<RecoveryResourceKey> =
        orderResources(cut.order, cut.portions) + orderResource(cut.value.id) + cashResource(cut.value.cashSessionId) +
            cut.value.portions.flatMap { listOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.reservationId)) +
                listOfNotNull(it.priorIssueId?.let { id -> RecoveryResourceKey(RecoveryResourceKind.STOCK, id) }) } +
            listOfNotNull(cut.value.saleId?.let(::entitlementResource), cut.value.cancellationRefundId?.let(::entitlementResource))
    private suspend fun workAdmission(owner: DomainId): Set<RecoveryResourceKey> {
        val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, owner)
        val row = checkNotNull(proof.sources.aggregate(key))
        val value = localSalesDecode(row.payload, LocalCustodianState.serializer())
        check(row.schema == LocalSalesStoreProfile.schema(key.kind) && value.scope.value() == proof.sources.scope &&
            value.id == owner && value.revision.next() == row.generation &&
            proof.sources.claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, owner)) == owner &&
            proof.sources.claim(LocalSalesStoreProfile.activeCustodian(owner)) == value.activeSessionId)
        var resources: Set<RecoveryResourceKey> = setOf(cashResource(owner), orderResource(owner), foodActorResource(owner))
        value.activeSessionId?.let { id ->
            val cash = originalCash(proof.sources, id)
            check(cash.session.status == CashSessionStatus.OPEN); resources += cash.resources
        }
        return resources
    }
    private suspend fun requireBeforeStock(portions: List<FoodOriginalRow>, collection: FoodCollection? = null) {
        for (row in portions) {
            val p = row.value.body as? FoodBodyV1.Portion ?: error("food.stock.quoted_execution_unavailable")
            val link = collection?.portions?.single { it.portionId == row.value.id }
            val reservationId = link?.reservationId ?: p.preparation.reservationId
            val reservation = reservationId?.let { id ->
                val header = checkNotNull(stock.beforeReservation(id))
                requireFoodReservationMembers(p, header) { stock.beforeAllocations(id, it) }
                // This accessor performs the complete original member/count/claim crosswalk,
                // including nonempty holds. A held reservation is not silently made available.
                var holds = 0L; stock.beforeHolds(id) { check(holds < Long.MAX_VALUE); holds++ }
                check(holds == header.holdCount); header
            }
            val issue = p.preparation.issueId?.let { id ->
                check(p.normalizedIssue != null) { "food.stock.original_legacy_issue_migration_required" }
                val header = checkNotNull(stock.beforeIssue(id))
                requireFoodCurrentIssueHeader(p, header)
                requireFoodIssueMembers(p, header) { stock.beforeIssueSlices(id, it) }
                requireFoodFirstIssue(p, checkNotNull(stock.issueOriginal(id)))
                check(checkNotNull(reservation).status == ReservationStatus.ISSUED && reservation.issueId == id &&
                    reservation.binding == header.binding && reservation.allocationCount == header.allocationCount)
                header
            }
            if (link != null) {
                val header = checkNotNull(reservation)
                if (link.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION) {
                    check(header.binding == foodFinancialStockBinding(checkNotNull(collection)) && header.expiresAtEpochMillis == null)
                    if (collection.cancellationRefundId != null) check(header.status == ReservationStatus.RELEASED && issue == null &&
                        p.preparation.status == FoodPreparationStatus.CANCELLED_BEFORE_START)
                    else if (issue == null) check(header.retainsStockClaim && p.preparation.status == FoodPreparationStatus.NOT_SENT)
                    else check(p.financialCollectionId == collection.id && issue.reservationId == link.reservationId)
                } else check(issue != null && issue.id == link.priorIssueId && issue.binding.exposure == CollectionExposure.UNCOLLECTED &&
                    issue.binding.collectionId == foodStockContextId(collection.orderId))
            }
        }
    }
    private suspend fun reservationIntents(portions: List<FoodOriginalRow>, binding: CollectionBinding,
        setId: DomainId, revision: Revision,
        routed: List<FoodBodyV1.Portion> = portions.map { it.value.body as FoodBodyV1.Portion }) {
        check(routed.size == portions.size)
        var ordinal = 0
        stock.reservationIntents { intent ->
            val row = portions.getOrNull(ordinal) ?: error("food.stock.extra_reservation")
            val p = routed[ordinal]
            check(p.selection.portionId == row.value.id)
            check(intent.ordinal == ordinal++ && intent.reservationId == foodReservationId(row.value.id) && intent.binding == binding &&
                intent.allocationSetId == setId && intent.allocationSetRevision == revision && intent.expiresAt == null &&
                intent.lineCount == p.recipe.ingredients.size && stock.beforeReservation(intent.reservationId) == null)
            var lineOrdinal = 0
            stock.demandLines(intent.reservationId) { line ->
                val ingredient = p.recipe.ingredients.getOrNull(lineOrdinal) ?: error("food.stock.extra_ingredient")
                check(line.ordinal == lineOrdinal++ && line.reservationId == intent.reservationId && line.request == LocalStockAllocationRequest(
                    foodIngredientLine(row.value.id, ingredient.componentId), ingredient.skuId, p.recipe.warehouseId, ingredient.requiredBaseQuantity) &&
                    line.skuRevision == ingredient.skuRevision)
                resources += recipes.requireOriginalReservationAuthority(proof.sources, line)
            }
            check(lineOrdinal == p.recipe.ingredients.size)
            val after = checkNotNull(stock.reservation(intent.reservationId))
            check(after.binding == binding && after.holdCount == 0L && after.expiresAtEpochMillis == null)
            requireFoodReservationMembers(p, after) { stock.allocations(after.id, it) }
        }
        check(ordinal == portions.size)
    }
    private suspend fun stations(portions: List<FoodBodyV1.Portion>, adding: Boolean) {
        for (id in portions.flatMap { it.preparation.tasks }.map { it.stationId }.distinct()) {
            val row = beforeFood(LocalFoodProfileV1.STATION_INDEX, id)
            val prior = (row?.value?.body as? FoodBodyV1.StationIndex)?.tasks.orEmpty()
            val selected = portions.filter { p -> p.preparation.tasks.any { it.stationId == id } }.map {
                FoodTaskLocatorV1(foodTaskId(it.selection.portionId, id), it.orderId, it.selection.portionId) }
            check(selected.all { (it !in prior) == adding })
            val next = if (adding) prior + selected else prior.filterNot { old -> selected.any { it.taskId == old.taskId } }
            afterFood(LocalFoodProfileV1.STATION_INDEX, id, FoodBodyV1.StationIndex(next), row)
        }
    }
    private suspend fun unchangedStations(portion: FoodBodyV1.Portion) {
        for (task in portion.preparation.tasks) {
            val index = requiredFood(LocalFoodProfileV1.STATION_INDEX, task.stationId).value.body as FoodBodyV1.StationIndex
            check(index.tasks.single { it.taskId == task.taskId } == FoodTaskLocatorV1(task.taskId, portion.orderId, portion.selection.portionId))
        }
    }

    private suspend fun createDraft(request: LocalFoodDraftCreateV1) {
        requireNoStockActions(stock)
        check(beforeFood(LocalFoodProfileV1.ORDER, request.orderId) == null)
        val index = beforeFood(LocalFoodProfileV1.OWNER_INDEX, actor)
        val priorIds = (index?.value?.body as? FoodBodyV1.OwnerIndex)?.orderIds.orEmpty()
        check(request.orderId !in priorIds && priorIds.size < LocalFoodProfileV1.MAX_ACTIVE_INDEX)
        resources += workAdmission(actor)
        val after = afterFood(LocalFoodProfileV1.ORDER, request.orderId,
            FoodBodyV1.Order(actor, actor, request.activity, request.service, emptyList(), emptyList(), Money(Currency.EGP, 0),
                deliveryDetails = request.deliveryDetails),
            null, LocalFoodDraftProfileV1.ORDER_SCHEMA)
        afterFood(LocalFoodProfileV1.OWNER_INDEX, actor, FoodBodyV1.OwnerIndex(priorIds + request.orderId), index)
        resources += setOf(orderResource(request.orderId), orderResource(actor))
        preparationResult(LocalFoodDraftProfileV1.CREATED, after, emptyList())
    }

    private suspend fun editServiceDetails(request: LocalFoodRequestV1.EditServiceDetails) {
        requireNoStockActions(stock)
        val order = order(request.orderId)
        owner(order, request.expectedOrderRevision)
        val after = afterFood(LocalFoodProfileV1.ORDER, request.orderId, order.value.copy(
            service = order.value.service.copy(reference = request.reference, seatNote = request.seatNote)), order.row,
            if (order.row.row.schema == LocalFoodDraftProfileV1.ORDER_SCHEMA) LocalFoodDraftProfileV1.ORDER_SCHEMA else foodStockSchema(LocalFoodProfileV1.ORDER))
        resources += orderResources(order)
        preparationResult(foodResultCode(request), after, emptyList())
    }

    private suspend fun createQuotedAdd(request: LocalFoodRequestV1.Add) {
        requireNoStockActions(stock)
        val source = recipes as? TransactionFoodRecipeQuotationVerificationSourceV1
            ?: error("food.quotation.original_source_unavailable")
        val prior = order(request.orderId)
        owner(prior, request.expectedOrderRevision)
        check(prior.value.financialCollectionId == null)
        resources += recipes.requireOriginalActivity(proof.sources, prior.value.activity)
        resources += workAdmission(actor)
        check(prior.value.portionIds.size + request.portions.size <= LocalFoodProfileV1.MAX_ORDER_PORTIONS)
        val priorPortions = prior.value.portionIds.map { portion(prior, it) }
        val priorBatches = prior.value.batchIds.map { requiredFood(LocalFoodProfileV1.PRICE_BATCH, it) }
        requireFoodQuotedOrderCommercial(prior.row.value, priorPortions.map { it.value }, priorBatches.map { it.value })
        val batchId = DomainId(operation.value)
        check(beforeFood(LocalFoodProfileV1.PRICE_BATCH, batchId) == null)
        val priceBody = peekFood(LocalFoodProfileV1.PRICE_BATCH, batchId).body as? FoodBodyV1.PriceBatch
            ?: error("food.quotation.price_batch_required")
        val quote = StoredPricingSnapshotCodec.decode(priceBody.quote)
        check(priceBody.orderId == request.orderId && quote.snapshotId == batchId && quote.revision == Revision(0) &&
            quote.lines.map { it.portionId } == request.portions.map { it.portionId })
        val quotations = request.portions.map { selection ->
            check(beforeFood(LocalFoodProfileV1.PORTION, selection.portionId) == null)
            val portion = peekFood(LocalFoodProfileV1.PORTION, selection.portionId).body as? FoodBodyV1.QuotedPortion
                ?: error("food.quotation.portion_required")
            check(beforeFood(FoodQuotationStorageV1.RECIPE_QUOTATION, portion.quotationId) == null)
            val body = peekFood(FoodQuotationStorageV1.RECIPE_QUOTATION, portion.quotationId).body as? FoodBodyV1.RecipeQuotation
                ?: error("food.quotation.sidecar_required")
            check(body.orderId == request.orderId && body.priceBatchId == batchId && body.quotation.portionId == selection.portionId)
            body.quotation
        }
        val captured = LocalFoodRecipeQuotationBatchV1(quote, quotations, priceBody.sources)
        resources += source.requireOriginalQuotation(proof.sources, prior.value.activity, prior.value.service,
            request.expectedCatalogRevision, request.portions, captured)
        val priceBatch = afterFood(LocalFoodProfileV1.PRICE_BATCH, batchId, priceBody, null)
        val sidecars = mutableListOf<FoodEnvelopeV1>()
        val portions = request.portions.zip(quotations).map { (selection, quotation) ->
            sidecars += afterFood(FoodQuotationStorageV1.RECIPE_QUOTATION, quotation.quotationId,
                FoodBodyV1.RecipeQuotation(request.orderId, batchId, quotation), null, FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA)
            afterFood(LocalFoodProfileV1.PORTION, selection.portionId,
                FoodBodyV1.QuotedPortion(request.orderId, selection, batchId, quotation.quotationId,
                    FoodPreparationState(selection.portionId, Revision(0), FoodPreparationStatus.NOT_SENT)),
                null, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
        }
        val body = prior.value.copy(portionIds = prior.value.portionIds + portions.map { it.id },
            batchIds = prior.value.batchIds + batchId, originalQuotedTotal = prior.value.originalQuotedTotal + quote.payable)
        val after = afterFood(LocalFoodProfileV1.ORDER, request.orderId, body, prior.row)
        requireSameFoodQuotationBatch(captured, foodQuotationBatch(after, portions, sidecars, priceBatch))
        requireFoodQuotedOrderCommercial(after, priorPortions.map { it.value } + portions, priorBatches.map { it.value } + priceBatch)
        resources += orderResources(prior, priorPortions) + orderResource(batchId) + request.portions.map { orderResource(it.portionId) }
        for (index in portions.indices) resources += foodQuotationResources(portions[index], sidecars[index], priceBatch)
        preparationResult(foodResultCode(request), after, portions)
    }

    private suspend fun createOrAdd(request: LocalFoodRequestV1, requireActivity: Boolean = false) {
        requireNoStockActions(stock)
        val creating = request is LocalFoodRequestV1.Create
        val orderId = request.foodOrderId()
        val prior = if (creating) { check(beforeFood(LocalFoodProfileV1.ORDER, orderId) == null); null } else order(orderId)
        prior?.let { owner(it, request.foodOrderRevision()); check(it.value.financialCollectionId == null) }
        val index = prior?.index ?: beforeFood(LocalFoodProfileV1.OWNER_INDEX, actor)
        val priorIds = (index?.value?.body as? FoodBodyV1.OwnerIndex)?.orderIds.orEmpty()
        if (creating) check(orderId !in priorIds && priorIds.size < LocalFoodProfileV1.MAX_ACTIVE_INDEX)
        resources += workAdmission(actor)
        val selections = when (request) { is LocalFoodRequestV1.Create -> request.portions; is LocalFoodRequestV1.Add -> request.portions; else -> error("food.stock.create_or_add") }
        check((prior?.value?.portionIds?.size ?: 0) + selections.size <= LocalFoodProfileV1.MAX_ORDER_PORTIONS)
        val activity = if (request is LocalFoodRequestV1.Create) request.activity else checkNotNull(prior).value.activity
        if (requireActivity) resources += recipes.requireOriginalActivity(proof.sources, activity)
        val service = if (request is LocalFoodRequestV1.Create) request.service else checkNotNull(prior).value.service
        val catalogueRevision = if (request is LocalFoodRequestV1.Create) request.expectedCatalogRevision else (request as LocalFoodRequestV1.Add).expectedCatalogRevision
        val batchId = DomainId(operation.value)
        check(beforeFood(LocalFoodProfileV1.PRICE_BATCH, batchId) == null)
        val batch = peekFood(LocalFoodProfileV1.PRICE_BATCH, batchId).body as FoodBodyV1.PriceBatch
        val quote = StoredPricingSnapshotCodec.decode(batch.quote)
        check(batch.orderId == orderId && quote.snapshotId == batchId && quote.revision == Revision(0) &&
            quote.lines.map { it.portionId } == selections.map { it.portionId })
        val snapshots = selections.map { selection ->
            check(beforeFood(LocalFoodProfileV1.PORTION, selection.portionId) == null)
            (peekFood(LocalFoodProfileV1.PORTION, selection.portionId).body as FoodBodyV1.Portion).recipe
        }
        resources += recipes.requireOriginalQuote(proof.sources, activity, service, catalogueRevision, selections,
            LocalFoodQuotedBatchV1(quote, snapshots, batch.sources))
        afterFood(LocalFoodProfileV1.PRICE_BATCH, batchId, batch, null)
        val portions = selections.zip(snapshots).map { (selection, snapshot) ->
            afterFood(LocalFoodProfileV1.PORTION, selection.portionId, FoodBodyV1.Portion(orderId, selection, batchId, snapshot,
                FoodPreparationState(selection.portionId, Revision(0), FoodPreparationStatus.NOT_SENT)), null)
        }
        val body = prior?.value?.let { it.copy(portionIds = it.portionIds + portions.map { p -> p.id },
            batchIds = it.batchIds + batchId, originalQuotedTotal = it.originalQuotedTotal + quote.payable) }
            ?: FoodBodyV1.Order(actor, actor, activity, service, portions.map { it.id }, listOf(batchId), quote.payable)
        val after = afterFood(LocalFoodProfileV1.ORDER, orderId, body, prior?.row)
        if (creating) afterFood(LocalFoodProfileV1.OWNER_INDEX, actor, FoodBodyV1.OwnerIndex(priorIds + orderId), index)
        resources += (prior?.let { orderResources(it) } ?: setOf(orderResource(orderId), orderResource(actor))) +
            orderResource(batchId) + selections.map { orderResource(it.portionId) }
        preparationResult(foodResultCode(request), after, portions)
    }
    private suspend fun begin(request: LocalFoodFinancialRequestV1.BeginCollection) {
        requireNoStockActions(stock, reserve = true)
        val order = order(request.orderId); owner(order, request.expectedOrderRevision)
        check(order.row.row.schema != LocalFoodDraftProfileV1.ORDER_SCHEMA && order.value.financialCollectionId == null)
        val portions = order.value.portionIds.map { portion(order, it) }
        val batches = order.value.batchIds.map { requiredFood(LocalFoodProfileV1.PRICE_BATCH, it) }
        check(portions.none { foodStoredPortionPreparation(it.value).status in foodChargeDecisionStates })
        // Original quoted preparations carry no physical identities; prove all real prior
        // routed stock without substituting an invented routed original row for a quotation.
        requireBeforeStock(portions.filter { it.value.body is FoodBodyV1.Portion })
        val routed = bindingPortions(order, portions)
        val cash = originalCash(proof.sources, request.sessionId)
        check(cash.session.revision == request.expectedSessionRevision && cash.session.custodianId == actor && cash.session.status == CashSessionStatus.OPEN)
        check(beforeFinancial(LocalFoodFinancialProfileV1.COLLECTION, request.collectionId) == null)
        val unsentIndices = portions.indices.filter { routed[it].preparation.status == FoodPreparationStatus.NOT_SENT }
        val unsent = unsentIndices.map { portions[it] }
        val unsentRouted = unsentIndices.map { routed[it] }
        for (p in unsentRouted) resources += recipes.requireOriginalCurrentForSend(proof.sources, p.recipe)
        val effective = portions.mapIndexed { index, row ->
            if (row.value.body is FoodBodyV1.QuotedPortion) {
                val p = routed[index]
                val after = afterFood(LocalFoodProfileV1.PORTION, row.value.id,
                    p.copy(preparation = p.preparation.copy(revision = p.preparation.revision.next())), row)
                bindingAfterResources(order, row, after)
                after
            } else row.value
        }
        afterFood(LocalFoodProfileV1.ORDER, request.orderId, order.value.copy(financialCollectionId = request.collectionId), order.row)
        val collection = foodFinancialCollection(afterFinancial(LocalFoodFinancialProfileV1.COLLECTION, request.collectionId, null, batches))
        check(collection.revision == Revision(0) && collection.scope == proof.sources.scope && collection.orderId == request.orderId &&
            collection.lockedCommercialRevision == request.expectedOrderRevision && collection.originalCreatorId == order.value.creatorId &&
            collection.collectorId == actor && collection.cashSessionId == request.sessionId && collection.attempts.isEmpty() &&
            collection.saleId == null && collection.cancellationRefundId == null)
        requireFoodFinancialQuote(collection, order.value, effective, batches.map { it.value })
        check(collection.portions == portions.mapIndexed { index, row ->
            val p = routed[index]
            FoodFinancialPortion(row.value.id, p.priceBatchId, p.recipe.snapshotId, p.selection.quantity, foodReservationId(row.value.id),
                if (p.preparation.issueId == null) FoodStockOrigin.RESERVED_FOR_COLLECTION else FoodStockOrigin.ISSUED_BEFORE_COLLECTION, p.preparation.issueId)
        })
        reservationIntents(unsent, foodFinancialStockBinding(collection), request.orderId, request.expectedOrderRevision, unsentRouted)
        for (row in unsent) check(checkNotNull(stock.reservation(foodReservationId(row.value.id))).let {
            it.revision == Revision(1) && it.status == ReservationStatus.RESERVED && it.issueId == null
        })
        resources += orderResources(order, portions) + cash.resources + orderResource(collection.id)
        financialResult("food.collection.locked", collection.id, collection.quote.payable)
    }
    private suspend fun finalize(request: LocalFoodFinancialRequestV1.FinalizePaid) {
        requireNoStockActions(stock)
        val cut = collection(request.collectionId); val before = cut.value
        check(before.revision == request.expectedCollectionRevision && before.saleId == null && before.cancellationRefundId == null &&
            !before.unresolved && before.collected == before.quote.payable &&
            cut.portions.none { (it.value.body as FoodBodyV1.Portion).preparation.status in foodChargeDecisionStates })
        check(beforeFinancial(LocalFoodFinancialProfileV1.SALE, request.saleId) == null && beforeFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, request.saleId) == null)
        val after = foodFinancialCollection(afterFinancial(LocalFoodFinancialProfileV1.COLLECTION, before.id, cut.row, cut.batches))
        check(after.revision == before.revision.next() && after.saleId == request.saleId && after.cancellationRefundId == null &&
            FoodFinancialSnapshots.sameCollectionSource(before, after))
        val sale = foodFinancialSale(afterFinancial(LocalFoodFinancialProfileV1.SALE, request.saleId, null, cut.batches))
        val rights = foodFinancialEntitlement(afterFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, request.saleId, null, cut.batches))
        check(FoodFinancialFramesV2.encodeCollection(sale.collection) == FoodFinancialFramesV2.encodeCollection(before) &&
            sale.finalizationOperationId == operation && sale.finalizedAt == at && rights.revision == Revision(0))
        requireFoodPaidSource(after, sale, rights)
        resources += collectionResources(cut) + entitlementResource(request.saleId)
        financialResult("food.sale.finalized", request.saleId, sale.quote.payable)
    }
    private suspend fun sendUncollected(request: LocalFoodRequestV1.Send) {
        requireNoStockActions(stock, reserve = true, issue = true)
        val order = order(request.orderId); owner(order, request.expectedOrderRevision)
        check(order.row.row.schema != LocalFoodDraftProfileV1.ORDER_SCHEMA && order.value.financialCollectionId == null)
        val selected = request.portionIds.map { portion(order, it) }
        check(selected.all { foodStoredPortionPreparation(it.value).status == FoodPreparationStatus.NOT_SENT })
        val routed = bindingPortions(order, selected)
        for (p in routed) {
            check(p.preparation.status == FoodPreparationStatus.NOT_SENT)
            resources += recipes.requireOriginalCurrentForSend(proof.sources, p.recipe)
        }
        val binding = CollectionBinding(request.orderId, request.expectedOrderRevision, foodStockContextId(request.orderId), actor, CollectionExposure.UNCOLLECTED)
        reservationIntents(selected, binding, request.orderId, request.expectedOrderRevision, routed)
        val changed = sendSelected(selected, null, binding, true, routed)
        for ((before, after) in selected.zip(changed)) bindingAfterResources(order, before, after)
        stations(changed.map { it.body as FoodBodyV1.Portion }, true)
        val after = afterFood(LocalFoodProfileV1.ORDER, request.orderId, order.value, order.row)
        jobs = changed.flatMap { foodDeliveryJobs(order.value, it.body as FoodBodyV1.Portion, "PREPARE") }
        resources += orderResources(order, selected) + foodSendResources(changed)
        preparationResult("food.sent", after, changed)
    }
    private suspend fun sendReserved(request: LocalFoodFinancialRequestV1.SendReserved) {
        requireNoStockActions(stock, issue = true)
        val cut = collection(request.collectionId); owner(cut.order, request.expectedOrderRevision)
        check(cut.value.orderId == request.orderId && cut.value.revision == request.expectedCollectionRevision && cut.value.cancellationRefundId == null)
        val selected = request.portionIds.map { id -> cut.portions.single { it.value.id == id } }
        for (row in selected) {
            val p = row.value.body as FoodBodyV1.Portion
            check(p.preparation.status == FoodPreparationStatus.NOT_SENT &&
                cut.value.portions.single { it.portionId == row.value.id }.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION)
            resources += recipes.requireOriginalCurrentForSend(proof.sources, p.recipe)
        }
        val changed = sendSelected(selected, cut.value.id, foodFinancialStockBinding(cut.value), false)
        stations(changed.map { it.body as FoodBodyV1.Portion }, true)
        afterFood(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value, cut.order.row)
        jobs = changed.flatMap { foodDeliveryJobs(cut.order.value, it.body as FoodBodyV1.Portion, "PREPARE") }
        resources += collectionResources(cut) + foodSendResources(changed)
        financialResult("food.reserved.sent", request.orderId)
    }
    private suspend fun sendSelected(selected: List<FoodOriginalRow>, collectionId: DomainId?, binding: CollectionBinding,
        newlyReserved: Boolean,
        routed: List<FoodBodyV1.Portion> = selected.map { it.value.body as FoodBodyV1.Portion }): List<FoodEnvelopeV1> {
        check(routed.size == selected.size)
        var ordinal = 0
        val changed = mutableListOf<FoodEnvelopeV1>()
        stock.issueIntents { intent ->
            val row = selected.getOrNull(ordinal) ?: error("food.stock.extra_issue")
            val p = routed[ordinal++]
            check(p.selection.portionId == row.value.id)
            val reserved = if (newlyReserved) null else checkNotNull(stock.beforeReservation(foodReservationId(row.value.id)))
            val revision = reserved?.revision ?: Revision(1)
            if (reserved != null) check(reserved.status == ReservationStatus.RESERVED && reserved.holdCount == 0L && reserved.binding == binding &&
                reserved.expiresAtEpochMillis == null && reserved.issueId == null)
            check(intent.reservationId == foodReservationId(row.value.id) && intent.expectedRevision == revision && intent.issueId == foodIssueId(row.value.id) &&
                intent.purpose == ValueIssuePurpose.DIRECT_RECIPE && intent.recipe?.recipeSnapshotId == p.recipe.snapshotId &&
                intent.recipe?.requiredStationIds == p.recipe.routes.map { it.stationId } && stock.beforeIssue(intent.issueId) == null)
            val issue = checkNotNull(stock.issue(intent.issueId)); val afterReservation = checkNotNull(stock.reservation(intent.reservationId))
            check(afterReservation.revision == revision.next() && afterReservation.status == ReservationStatus.ISSUED && afterReservation.holdCount == 0L &&
                afterReservation.binding == binding && afterReservation.expiresAtEpochMillis == null && afterReservation.issueId == issue.id &&
                afterReservation.allocationCount == issue.allocationCount && (reserved == null || reserved.allocationCount == issue.allocationCount) &&
                issue.revision == Revision(1) && issue.binding == binding && issue.reservationId == intent.reservationId &&
                issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.disposition == KitchenDisposition.ACTIVE && issue.startedStationIds.isEmpty())
            val tasks = p.recipe.routes.map { FoodStationTask(foodTaskId(row.value.id, it.stationId), it.stationId, it.mode, FoodStationStatus.QUEUED) }
            for (task in tasks) acquire(LocalFoodProfileV1.claim("task", task.taskId), p.orderId)
            val state = FoodPreparation.sent(p.preparation, p.preparation.revision, intent.reservationId, intent.issueId, tasks, at)
            val reference = FoodStockIssueReferenceV2(issue.id, issue.reservationId, operation, actor, original.identity.producer.semanticDigest,
                issue.binding.orderId, issue.binding.quoteRevision, issue.binding.collectionId, issue.binding.originalActorId,
                issue.binding.exposure.name, issue.allocationCount, issue.originalValue, checkNotNull(issue.recipeSnapshotId), issue.requiredStationIds)
            val after = p.copy(preparation = state, originalIssue = null, financialCollectionId = collectionId, normalizedIssue = reference)
            requireFoodCurrentIssueHeader(after, issue)
            requireFoodIssueMembers(after, issue) { stock.issueSlices(issue.id, it) }
            requireFoodFirstIssue(after, checkNotNull(stock.issueOriginal(issue.id)))
            changed += afterFood(LocalFoodProfileV1.PORTION, row.value.id, after, row)
        }
        check(ordinal == selected.size)
        return changed
    }
    private suspend fun changePortion(request: LocalFoodRequestV1) {
        val start = request is LocalFoodRequestV1.Station && request.action == LocalFoodStationActionV1.START
        val waste = request is LocalFoodRequestV1.DiscardStarted
        val confirming = request is LocalFoodRequestV1.Station && request.action == LocalFoodStationActionV1.CONFIRM_UNSTARTED
        val order = order(request.foodOrderId())
        check(order.row.row.schema != LocalFoodDraftProfileV1.ORDER_SCHEMA && order.row.value.revision == request.foodOrderRevision())
        if (request !is LocalFoodRequestV1.Station && request !is LocalFoodRequestV1.DiscardStarted) owner(order, request.foodOrderRevision())
        val row = portion(order, request.foodPortionId())
        if (row.value.body is FoodBodyV1.QuotedPortion) {
            check(request is LocalFoodRequestV1.Cancel) { "food.stock.quoted_execution_unavailable" }
            cancelQuotedPortion(request, order, row)
            return
        }
        val p = row.value.body as? FoodBodyV1.Portion ?: error("food.stock.original_portion_required")
        val financial = if (confirming) kitchenCollection(order) else null
        requireBeforeStock(listOf(row), financial?.value)
        check(p.preparation.revision == request.foodPortionRevision())
        val next = when (request) {
            is LocalFoodRequestV1.Station -> when (request.action) {
                LocalFoodStationActionV1.START -> FoodPreparation.start(p.preparation, request.expectedPortionRevision, request.stationId, at)
                LocalFoodStationActionV1.READY -> FoodPreparation.ready(p.preparation, request.expectedPortionRevision, request.stationId, at)
                LocalFoodStationActionV1.CONFIRM_UNSTARTED -> {
                    requireFoodKitchenTaskSources(p) { proof.sources.claim(it) }
                    val value = FoodPreparation.confirmUnstarted(p.preparation, request.expectedPortionRevision, request.stationId,
                        checkNotNull(request.confirmationId), actor, checkNotNull(request.returnDisposition), at)
                    acquire(LocalFoodProfileV1.claim("confirmation", checkNotNull(request.confirmationId)), row.value.id)
                    if (value.status == FoodPreparationStatus.CANCELLED_BEFORE_START) {
                        check(expectedRestoration == null)
                        expectedRestoration = foodKitchenRestoration(operation, p, value,
                            checkNotNull(stock.beforeIssue(checkNotNull(p.preparation.issueId))))
                    }
                    value
                }
            }
            is LocalFoodRequestV1.Cancel -> {
                check(order.value.financialCollectionId == null || p.preparation.status != FoodPreparationStatus.NOT_SENT)
                FoodPreparation.requestCancellation(p.preparation, request.expectedPortionRevision, request.reasonId, at)
            }
            is LocalFoodRequestV1.DiscardStarted -> {
                check(p.preparation.tasks.any { it.stationId == request.stationId })
                FoodPreparation.discardStarted(p.preparation, request.expectedPortionRevision)
            }
            is LocalFoodRequestV1.Serve -> {
                check(order.value.service.channel == LocalSalesChannel.DINE_IN)
                FoodPreparation.serve(p.preparation, request.expectedPortionRevision, at)
            }
            else -> error("food.stock.portion_action")
        }
        // Only the genuine final all-station transition above may request restoration. The
        // whole-producer census still checks its exact identity and excludes every extra one.
        requireNoStockActions(stock, start = start, waste = waste, restore = expectedRestoration != null)
        if (start) {
            var count = 0
            stock.kitchenStartIntents { intent -> check(count++ == 0 && intent == LocalStockKitchenStart(checkNotNull(p.preparation.issueId),
                checkNotNull(p.preparation.issueRevision), (request as LocalFoodRequestV1.Station).stationId)) }
            check(count == 1)
        }
        if (waste) {
            var count = 0
            stock.wasteIntents { intent -> check(count++ == 0 && intent == LocalStockKitchenWaste(checkNotNull(p.preparation.issueId),
                checkNotNull(p.preparation.issueRevision), checkNotNull(p.preparation.cancellationReasonId))) }
            check(count == 1)
        }
        val afterBody = p.copy(preparation = next)
        if (start || waste || expectedRestoration != null) {
            val issue = checkNotNull(stock.issue(checkNotNull(p.preparation.issueId)))
            requireFoodCurrentIssueHeader(afterBody, issue)
            if (expectedRestoration != null) {
                // Restoration changes every current slice zero-to-full once, at its original
                // cost. The immutable original send/reference and issued reservation survive.
                requireFoodIssueMembers(afterBody, issue) { stock.issueSlices(issue.id, it) }
            } else {
                // Start/waste retain their immutable slices rather than implying an AFTER write.
                requireFoodIssueMembers(afterBody, issue) { stock.beforeIssueSlices(issue.id, it) }
            }
        }
        val after = afterFood(LocalFoodProfileV1.PORTION, row.value.id, afterBody, row)
        val finished = next.status in setOf(FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START) ||
            next.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER
        if (finished && p.preparation.tasks.isNotEmpty()) stations(listOf(afterBody), false) else unchangedStations(p)
        val afterOrder = afterFood(LocalFoodProfileV1.ORDER, request.foodOrderId(), order.value, order.row)
        if (request is LocalFoodRequestV1.Cancel && p.preparation.tasks.isNotEmpty()) jobs = foodDeliveryJobs(order.value, afterBody, "CANCEL_REQUEST_CONFIRM_REQUIRED")
        resources += orderResources(order, listOf(row))
        financial?.let { resources += collectionResources(it) }
        if (confirming) resources += foodKitchenConfirmationResources(next)
        preparationResult(foodResultCode(request), afterOrder, listOf(after))
    }

    private suspend fun cancelQuotedPortion(request: LocalFoodRequestV1.Cancel, order: FoodOriginalOrder,
        row: FoodOriginalRow) {
        requireNoStockActions(stock)
        owner(order, request.expectedOrderRevision)
        check(order.value.financialCollectionId == null)
        val portion = row.value.body as? FoodBodyV1.QuotedPortion ?: error("food.quotation.portion_required")
        check(portion.preparation.status == FoodPreparationStatus.NOT_SENT &&
            portion.preparation.revision == request.expectedPortionRevision)
        val batch = quotedQuotationBatch(order, row)
        val quotation = batch.quotations.single { it.value.id == portion.quotationId }
        val next = FoodPreparation.requestCancellation(portion.preparation, request.expectedPortionRevision, request.reasonId, at)
        val changed = afterFood(LocalFoodProfileV1.PORTION, request.portionId, portion.copy(preparation = next), row,
            FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
        val after = afterFood(LocalFoodProfileV1.ORDER, request.orderId, order.value, order.row)
        requireFoodQuotationPair(after, changed, quotation.value, batch.priceBatch.value, batch.batch)
        resources += orderResources(order, listOf(row))
        preparationResult(foodResultCode(request), after, listOf(changed))
    }

    private suspend fun pickup(request: LocalFoodFinancialRequestV1.PaidPickup) {
        requireNoStockActions(stock)
        val cut = collection(request.collectionId); owner(cut.order, request.expectedOrderRevision)
        check(cut.value.orderId == request.orderId && cut.value.status == FoodCollectionStatus.FINALIZED_PAID && cut.value.cancellationRefundId == null &&
            cut.order.value.service.channel in setOf(LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY))
        val id = checkNotNull(cut.value.saleId)
        val sale = foodFinancialSale(requiredFinancial(LocalFoodFinancialProfileV1.SALE, id).value)
        val rights = foodFinancialEntitlement(requiredFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, id).value)
        requireFoodPaidSource(cut.value, sale, rights)
        check(rights.cursors.single { it.portionId == request.portionId }.consumedQuantity.scaled == 0L)
        val row = cut.portions.single { it.value.id == request.portionId }; val p = row.value.body as FoodBodyV1.Portion
        val next = p.copy(preparation = FoodPreparation.pickUp(p.preparation, request.expectedPortionRevision, at))
        afterFood(LocalFoodProfileV1.PORTION, row.value.id, next, row)
        stations(listOf(next), false)
        afterFood(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value, cut.order.row)
        resources += collectionResources(cut)
        financialResult("food.paid_pickup.recorded", request.orderId)
    }
    private suspend fun cancel(request: LocalFoodFinancialRequestV1.CancelWholeUnsent) {
        requireNoStockActions(stock, release = true)
        val cut = collection(request.collectionId); owner(cut.order, request.expectedOrderRevision)
        val before = cut.value
        check(before.revision == request.expectedCollectionRevision && !before.unresolved && before.cancellationRefundId == null &&
            before.portions.all { it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION } && cut.portions.all {
                (it.value.body as FoodBodyV1.Portion).preparation.let { p -> p.status == FoodPreparationStatus.NOT_SENT && p.issueId == null &&
                    p.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER }
            })
        check(beforeFinancial(LocalFoodFinancialProfileV1.REFUND, request.refundId) == null)
        val index = beforeFinancial(LocalFoodFinancialProfileV1.REFUND_INDEX, before.orderId)
        val sale = before.saleId?.let { foodFinancialSale(requiredFinancial(LocalFoodFinancialProfileV1.SALE, it).value) }
        val rightsRow = before.saleId?.let { requiredFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, it) }
        val rights = rightsRow?.let { foodFinancialEntitlement(it.value) }
        if (sale != null && rights != null) {
            requireFoodPaidSource(before, sale, rights)
            check(rights.revision == Revision(0) && rights.cursors.all { it.consumedQuantity.scaled == 0L })
        }
        val after = foodFinancialCollection(afterFinancial(LocalFoodFinancialProfileV1.COLLECTION, before.id, cut.row, cut.batches))
        check(after.revision == before.revision.next() && after.saleId == before.saleId && after.cancellationRefundId == request.refundId &&
            FoodFinancialSnapshots.sameCollectionSource(before, after))
        val refund = foodFinancialRefund(afterFinancial(LocalFoodFinancialProfileV1.REFUND, request.refundId, null, cut.batches))
        check(refund.revision == Revision(0) && refund.attempts.isEmpty() && refund.cancellationOperationId == operation &&
            refund.cancellationReasonId == request.reasonId && refund.acceptingActorId == actor && refund.acceptedAt == at &&
            FoodFinancialFramesV2.encodeCollection(refund.originalCollection) == FoodFinancialFramesV2.encodeCollection(before) && refund.amount == before.collected)
        val ids = (index?.value?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty()
        check(ids.size < 32 && afterFinancial(LocalFoodFinancialProfileV1.REFUND_INDEX, before.orderId, index).body ==
            FoodFinancialBodyV1.RefundIndex(before.orderId, ids + request.refundId))
        if (rightsRow != null) {
            val next = foodFinancialEntitlement(afterFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, checkNotNull(before.saleId), rightsRow, cut.batches))
            check(next.revision == checkNotNull(rights).revision.next() &&
                FoodFinancialFramesV2.encodeSale(next.sale) == FoodFinancialFramesV2.encodeSale(checkNotNull(sale)) &&
                next.cursors.all { it.remainingQuantity.scaled == 0L } && next.remainingTenders.all { row -> row.shares.all { it.amount.minor == 0L } })
        }
        for (row in cut.portions) {
            val p = row.value.body as FoodBodyV1.Portion
            afterFood(LocalFoodProfileV1.PORTION, row.value.id,
                p.copy(preparation = FoodPreparation.requestCancellation(p.preparation, p.preparation.revision, request.reasonId, at)), row)
        }
        afterFood(LocalFoodProfileV1.ORDER, before.orderId, cut.order.value, cut.order.row)
        val resolution = CollectionResolutionReference(foodCancellationResolutionId(operation), before.orderId, before.id, request.refundId)
        var count = 0
        stock.releaseIntents { intent ->
            val link = before.portions.getOrNull(count++) ?: error("food.stock.extra_release")
            val previous = checkNotNull(stock.beforeReservation(link.reservationId)); val next = checkNotNull(stock.reservation(link.reservationId))
            check(intent == LocalStockRelease(link.reservationId, previous.revision, resolution) && previous.retainsStockClaim &&
                next.revision == previous.revision.next() && next.status == ReservationStatus.RELEASED && next.binding == previous.binding &&
                next.allocationCount == previous.allocationCount && next.holdCount == previous.holdCount &&
                next.expiresAtEpochMillis == previous.expiresAtEpochMillis && next.issueId == previous.issueId)
            // Release changes only the typed header/claims. All immutable member identities and
            // retained hold rows are already proved by before* and the owner's whole transition.
        }
        check(count == before.portions.size)
        acquire(LocalFoodFinancialProfileV1.source("unsent-resolution", resolution.resolutionId), request.refundId)
        resources += collectionResources(cut) + entitlementResource(request.refundId) + entitlementResource(before.orderId) +
            orderResource(request.reasonId) + orderResource(resolution.resolutionId)
        financialResult("food.unsent.cancelled_refund_obligation", request.refundId, refund.amount)
    }
    private suspend fun close(request: LocalFoodFinancialRequestV1.CloseResolvedWork) {
        requireNoStockActions(stock)
        val cut = collection(request.collectionId); owner(cut.order, request.expectedOrderRevision)
        check(cut.value.orderId == request.orderId && cut.value.revision == request.expectedCollectionRevision && !cut.value.unresolved)
        val claimIndexId = foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, request.orderId)
        val claimIndex = beforeFinancial(LocalFoodFinancialProfileV1.CLAIM_INDEX, claimIndexId)
        check((claimIndex?.value?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty().isEmpty())
        val index = beforeFinancial(LocalFoodFinancialProfileV1.REFUND_INDEX, request.orderId)
        val ids = (index?.value?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty()
        check(ids == listOfNotNull(cut.value.cancellationRefundId))
        val refunds = ids.map { foodFinancialRefund(requiredFinancial(LocalFoodFinancialProfileV1.REFUND, it).value) }
        for (refund in refunds) { requireFoodRefundSource(cut.value, refund); check(!refund.unresolved && refund.due.minor == 0L) }
        cut.value.saleId?.let { id ->
            requireFoodPaidSource(cut.value, foodFinancialSale(requiredFinancial(LocalFoodFinancialProfileV1.SALE, id).value),
                foodFinancialEntitlement(requiredFinancial(LocalFoodFinancialProfileV1.ENTITLEMENT, id).value))
        }
        if (cut.value.cancellationRefundId != null) check(cut.portions.all {
            (it.value.body as FoodBodyV1.Portion).preparation.let { p -> p.status == FoodPreparationStatus.CANCELLED_BEFORE_START &&
                p.issueId == null && p.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER }
        }) else check(cut.value.status == FoodCollectionStatus.FINALIZED_PAID && cut.portions.all {
            (it.value.body as FoodBodyV1.Portion).preparation.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER
        })
        afterFood(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value.copy(closedByOperationId = operation), cut.order.row)
        afterFood(LocalFoodProfileV1.OWNER_INDEX, cut.order.value.ownerId,
            FoodBodyV1.OwnerIndex((cut.order.index.value.body as FoodBodyV1.OwnerIndex).orderIds - request.orderId), cut.order.index)
        resources += collectionResources(cut) + ids.map(::entitlementResource) + entitlementResource(request.orderId) + entitlementResource(claimIndexId)
        financialResult("food.work.closed", request.orderId)
    }
    private suspend fun claim(request: LocalFoodFinancialRequestV1.RecordQualityClaim) {
        requireNoStockActions(stock)
        check(beforeFinancial(LocalFoodFinancialProfileV1.CLAIM, request.claimId) == null && request.facts.claimedAt <= at && request.facts.evidence.occurredAt <= at)
        val ownerIndex = beforeFinancial(LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, actor))
        val orderIndex = request.facts.assertedOrderId?.let { beforeFinancial(LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, it)) }
        check(afterFinancial(LocalFoodFinancialProfileV1.CLAIM, request.claimId, null).body == FoodFinancialBodyV1.Claim(request.facts, actor, at, actor, null))
        suspend fun append(row: FoodOriginalFinancialRow?, target: FoodClaimIndexTargetV1, id: DomainId) {
            val ids = (row?.value?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty()
            check(ids.size < 64 && afterFinancial(LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(target, id), row).body ==
                FoodFinancialBodyV1.ClaimIndex(target, id, ids + request.claimId))
        }
        append(ownerIndex, FoodClaimIndexTargetV1.OWNER, actor)
        request.facts.assertedOrderId?.let { append(orderIndex, FoodClaimIndexTargetV1.ORDER, it) }
        resources += foodClaimResources(request, actor)
        financialResult("food.quality_claim.recorded", request.claimId)
    }
}
private val foodStockFramedKinds: Set<String> = setOf(LocalFoodFinancialProfileV1.COLLECTION,
    LocalFoodFinancialProfileV1.SALE, LocalFoodFinancialProfileV1.ENTITLEMENT, LocalFoodFinancialProfileV1.REFUND)

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockContribution
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class FoodHandoffV1(val id: DomainId, val from: DomainId, val to: DomainId, val offeredAt: UtcInstant)
@Serializable internal data class FoodTaskLocatorV1(val taskId: DomainId, val orderId: DomainId, val portionId: DomainId)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable internal sealed interface FoodBodyV1 {
    @Serializable @SerialName("ORDER")
    data class Order(val creatorId: DomainId, val ownerId: DomainId, val activity: LocalSalesActivity,
        val service: LocalFoodServiceV1, val portionIds: List<DomainId>, val batchIds: List<DomainId>,
        val originalQuotedTotal: Money,
        val handoff: FoodHandoffV1? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val financialCollectionId: DomainId? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val closedByOperationId: OperationId? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val deliveryDetails: LocalFoodDeliveryDetailsV1? = null) : FoodBodyV1
    @Serializable @SerialName("PORTION")
    data class Portion(val orderId: DomainId, val selection: LocalFoodPortionSelectionV1, val priceBatchId: DomainId,
        val recipe: LocalFoodRecipeSnapshotV1, val preparation: FoodPreparationState,
        /** Literal original allocations/cost at send. Current movements remain solely in normalized stock. */
        val originalIssue: String? = null,
        /** Only the genuine financial SEND_RESERVED producer may install this original issue link. */
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val financialCollectionId: DomainId? = null,
        /** Scalar link to the exact first-issued normalized producer, NEVER an embedded lot list. */
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val normalizedIssue: FoodStockIssueReferenceV2? = null) : FoodBodyV1
    @Serializable @SerialName("RECIPE_QUOTATION")
    data class RecipeQuotation(val orderId: DomainId, val priceBatchId: DomainId,
        val quotation: LocalFoodRecipeQuotationV1) : FoodBodyV1
    @Serializable @SerialName("QUOTED_PORTION")
    data class QuotedPortion(val orderId: DomainId, val selection: LocalFoodPortionSelectionV1,
        val priceBatchId: DomainId, val quotationId: DomainId,
        val preparation: FoodPreparationState) : FoodBodyV1
    @Serializable @SerialName("PRICE_BATCH")
    data class PriceBatch(val orderId: DomainId, val quote: String, val sources: List<LocalSalesPriceSourceV2>) : FoodBodyV1
    @Serializable @SerialName("OWNER_INDEX")
    data class OwnerIndex(val orderIds: List<DomainId>) : FoodBodyV1
    @Serializable @SerialName("STATION_INDEX")
    data class StationIndex(val tasks: List<FoodTaskLocatorV1>) : FoodBodyV1
}
@Serializable internal data class FoodEnvelopeV1(val scope: BranchScope, val kind: String, val id: DomainId,
    val revision: Revision, val operationId: OperationId, val body: FoodBodyV1)
@Serializable internal data class FoodBeforeV1(val envelope: FoodEnvelopeV1)
@Serializable internal data class FoodAfterRefV1(val kind: String, val id: DomainId, val revision: Revision, val digest: PayloadDigest)
@Serializable internal data class FoodEventV1(val scope: BranchScope, val operationId: OperationId, val actorId: DomainId,
    val recordedAt: UtcInstant, val beforeIds: List<DomainId>, val after: List<FoodAfterRefV1>, val stockProduced: Boolean,
    val result: LocalFoodResultV1)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable internal data class FoodDeliveryV1(val orderId: DomainId, val portionId: DomainId, val taskId: DomainId,
    val stationId: DomainId, val portionRevision: Revision, val mode: FoodStationDeliveryMode,
    val instruction: String, val description: String, val quantity: Quantity, val itemNote: String, val serviceReference: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val seatNote: String = "")

/** Minimal current row observation for routing a permission/refusal, NOT a provenance proof. */
internal data class FoodRowObservation(val row: StoredAggregate, val envelope: FoodEnvelopeV1)
/** Complete current food-row/provenance observation; the producer is immutable source data, not permission. */
internal class FoodRow private constructor(val row: StoredAggregate, val envelope: FoodEnvelopeV1,
    private val legacyProducer: RetainedCommandEvidence?, val normalizedProducer: RetainedStreamedCommand?) {
    constructor(row: StoredAggregate, envelope: FoodEnvelopeV1, producer: RetainedCommandEvidence) : this(row, envelope, producer, null)
    constructor(row: StoredAggregate, envelope: FoodEnvelopeV1, producer: RetainedStreamedCommand) : this(row, envelope, null, producer)
    /** Old writers cannot rewrite/rekey normalized history through their v1 before-copy profile. */
    val producer: RetainedCommandEvidence get() = legacyProducer ?: throw SalesRuleViolation(SalesRule.UNSUPPORTED_STOCK_TIMING)
}
internal data class FoodOrderCut(val order: FoodRow, val ownerIndex: FoodRow) {
    val value: FoodBodyV1.Order get() = order.envelope.body as FoodBodyV1.Order
}

internal fun foodEnvelopeBytes(value: FoodEnvelopeV1): StoredBytes = localSalesEncode(value, FoodEnvelopeV1.serializer(), 28_672)
internal fun foodEnvelope(bytes: StoredBytes): FoodEnvelopeV1 = localSalesDecode(bytes, FoodEnvelopeV1.serializer(), 28_672).also(::requireFoodEnvelope)
internal fun requireFoodEnvelope(value: FoodEnvelopeV1) {
    check(value.revision.value >= 1)
    when (val body = value.body) {
        is FoodBodyV1.Order -> check(value.kind == LocalFoodProfileV1.ORDER && body.portionIds.size in 1..LocalFoodProfileV1.MAX_ORDER_PORTIONS &&
            body.portionIds.distinct().size == body.portionIds.size && body.batchIds.size in 1..LocalFoodProfileV1.MAX_ORDER_PORTIONS &&
            body.batchIds.distinct().size == body.batchIds.size && body.activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) &&
            (body.service.channel == LocalSalesChannel.DELIVERY) == (body.deliveryDetails != null) &&
            body.originalQuotedTotal.currency == Currency.EGP && body.originalQuotedTotal.minor >= 0 &&
            (body.handoff == null || body.handoff.from == body.ownerId && body.handoff.to != body.ownerId) &&
            (body.closedByOperationId == null || body.financialCollectionId != null && body.handoff == null))
        is FoodBodyV1.Portion -> {
            check(value.kind == LocalFoodProfileV1.PORTION && value.id == body.selection.portionId && value.id == body.preparation.portionId &&
                value.id == body.recipe.portionId &&
                value.revision.value == body.preparation.revision.value + 1 && body.selection.itemId == body.recipe.outputSkuId &&
                body.selection.recipeId == body.recipe.recipeId && body.selection.expectedRecipeRevision == body.recipe.recipeRevision &&
                body.selection.choiceId == body.recipe.choiceId && body.selection.modifierIds.toSet() == body.recipe.modifierIds.toSet() &&
                body.selection.quantity == body.recipe.portionQuantity &&
                (body.originalIssue != null || body.normalizedIssue != null) == (body.preparation.issueId != null) &&
                !(body.originalIssue != null && body.normalizedIssue != null) &&
                (body.financialCollectionId == null || body.preparation.issueId != null))
            body.normalizedIssue?.let { requireFoodIssueReference(it, body) }
            if (body.originalIssue != null) {
                val issue = InventoryRecordCodec.decodeIssue(body.originalIssue, InventoryScope(value.scope.companyId, value.scope.branchId),
                    checkNotNull(body.preparation.issueId), Revision(1))
                check(issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.kitchen?.recipeSnapshotId == body.recipe.snapshotId &&
                    issue.kitchen?.requiredStationIds == body.recipe.routes.map { it.stationId } &&
                    issue.reservationId == body.preparation.reservationId && issue.binding.orderId == body.orderId &&
                    (if (body.financialCollectionId == null) issue.binding.exposure == CollectionExposure.UNCOLLECTED &&
                        issue.binding.collectionId == foodStockContextId(body.orderId)
                    else issue.binding.collectionId == body.financialCollectionId && issue.binding.exposure == CollectionExposure.UNKNOWN) &&
                    body.preparation.tasks.map { it.stationId } == body.recipe.routes.map { it.stationId })
                requireFoodIssueRecipe(issue, body)
            }
        }
        is FoodBodyV1.PriceBatch -> {
            val quote = StoredPricingSnapshotCodec.decode(body.quote)
            check(value.kind == LocalFoodProfileV1.PRICE_BATCH && value.revision == Revision(1) && quote.snapshotId == value.id && quote.revision == Revision(0) &&
                quote.currency == Currency.EGP && quote.payable.minor >= 0 && quote.charges.isEmpty() && quote.discounts.isEmpty() &&
                quote.lines.size in 1..LocalFoodProfileV1.MAX_PORTIONS_PER_COMMAND && body.sources.size == quote.lines.size &&
                body.sources.map { it.portionId }.toSet() == quote.lines.map { it.portionId }.toSet())
            for (source in body.sources) check(source.scope == value.scope && quote.lines.single { it.portionId == source.portionId }.let {
                it.lineId == source.lineId && it.priceRevision == source.companyPriceRevision
            })
        }
        is FoodBodyV1.OwnerIndex -> check(value.kind == LocalFoodProfileV1.OWNER_INDEX && body.orderIds.size <= LocalFoodProfileV1.MAX_ACTIVE_INDEX &&
            body.orderIds.distinct().size == body.orderIds.size)
        is FoodBodyV1.StationIndex -> check(value.kind == LocalFoodProfileV1.STATION_INDEX && body.tasks.size <= LocalFoodProfileV1.MAX_ACTIVE_INDEX &&
            body.tasks.map { it.taskId }.distinct().size == body.tasks.size)
        is FoodBodyV1.RecipeQuotation, is FoodBodyV1.QuotedPortion -> error("food.quotation.explicit_schema_required")
    }
}

/** Empty draft authority comes from an explicit schema and its complete original producer. */
internal fun requireFoodDraftEnvelope(value: FoodEnvelopeV1) {
    check(value.kind == LocalFoodProfileV1.ORDER && value.revision.value >= 1)
    val order = value.body as? FoodBodyV1.Order ?: error("food.draft.order_required")
    check(order.creatorId == order.ownerId && order.activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) &&
        (order.service.channel == LocalSalesChannel.DELIVERY) == (order.deliveryDetails != null) &&
        order.portionIds.isEmpty() && order.batchIds.isEmpty() && order.originalQuotedTotal == Money(Currency.EGP, 0) &&
        order.handoff == null && order.financialCollectionId == null && order.closedByOperationId == null)
}

internal fun foodStreamedSchema(kind: String, schema: String): Boolean = schema == foodStockSchema(kind) ||
    kind == LocalFoodProfileV1.ORDER && schema == LocalFoodDraftProfileV1.ORDER_SCHEMA ||
    kind == LocalFoodProfileV1.PORTION && schema == FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA ||
    kind == FoodQuotationStorageV1.RECIPE_QUOTATION && schema == FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA

internal fun requireFoodStoredEnvelope(value: FoodEnvelopeV1, schema: String) {
    when (schema) {
        LocalFoodDraftProfileV1.ORDER_SCHEMA -> requireFoodDraftEnvelope(value)
        FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA ->
            requireFoodQuotationEnvelope(value, schema)
        else -> {
            check(schema == LocalFoodProfileV1.schema(value.kind) || schema == foodStockSchema(value.kind))
            requireFoodEnvelope(value)
        }
    }
}

/** The explicit schema is part of the stored identity; no quotation uses a routed writer. */
internal fun foodStoredEnvelopeBytes(value: FoodEnvelopeV1, schema: String): StoredBytes {
    requireFoodStoredEnvelope(value, schema)
    return when (schema) {
        FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA ->
            foodQuotationEnvelopeBytes(value, schema)
        else -> foodEnvelopeBytes(value)
    }
}

/** Never infer an empty draft from legacy bytes or an unavailable source. */
internal fun foodStoredEnvelope(schema: String, bytes: StoredBytes): FoodEnvelopeV1 {
    val value = when (schema) {
        LocalFoodDraftProfileV1.ORDER_SCHEMA -> localSalesDecode(bytes, FoodEnvelopeV1.serializer(), 28_672)
        FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA ->
            foodQuotationEnvelope(schema, bytes)
        else -> foodEnvelope(bytes)
    }
    requireFoodStoredEnvelope(value, schema)
    if (schema == LocalFoodDraftProfileV1.ORDER_SCHEMA) check(foodEnvelopeBytes(value) == bytes)
    return value
}

internal fun foodBeforeId(operationId: OperationId, kind: String, id: DomainId): DomainId = foodId("before", "${operationId.value}/$kind/${id.value}")
internal fun foodTaskId(portionId: DomainId, stationId: DomainId): DomainId = foodId("task", "${portionId.value}/${stationId.value}")
internal fun foodReservationId(portionId: DomainId): DomainId = foodId("reservation", portionId.value)
internal fun foodIssueId(portionId: DomainId): DomainId = foodId("issue", portionId.value)
internal fun foodStockContextId(orderId: DomainId): DomainId = foodId("uncollected-stock-context", orderId.value)
internal fun foodIngredientLine(portionId: DomainId, componentId: DomainId): DomainId = foodId("ingredient", "${portionId.value}/${componentId.value}")

internal suspend fun foodObserveRow(view: CommandReadView, kind: String, id: DomainId): FoodRowObservation? {
    check(kind in LocalFoodProfileV1.kinds - setOf(LocalFoodProfileV1.EVENT, LocalFoodProfileV1.BEFORE) ||
        kind == FoodQuotationStorageV1.RECIPE_QUOTATION)
    val row = view.aggregate(LocalFoodProfileV1.key(kind, id))
    val claim = view.claim(LocalFoodProfileV1.claim(kind, id))
    if (row == null) { check(claim == null) { "food.claim_without_source" }; return null }
    check(claim == id && (row.schema == LocalFoodProfileV1.schema(kind) || foodStreamedSchema(kind, row.schema)) &&
        sha256(row.payload.copyBytes()) == row.digest)
    val value = foodStoredEnvelope(row.schema, row.payload)
    check(value.kind == kind && value.id == id && value.scope == view.scope && value.revision == row.revision)
    return FoodRowObservation(row, value)
}

internal suspend fun foodRead(view: CommandReadView, kind: String, id: DomainId): FoodRow? {
    val bounded = foodReadView(view)
    val observed = foodObserveRow(bounded, kind, id) ?: return null
    val row = observed.row
    val value = observed.envelope
    if (foodStreamedSchema(kind, row.schema)) {
        val original = bounded.foodStockProducer(value.operationId)
        requireCommerceWrite(original, row)
        bounded.recordCommerceOriginal(row, original)
        return FoodRow(row, value, original)
    }
    val producer = bounded.foodProducer(value.operationId)
    check(producer.aggregateWrites.single { it.key == row.key }.let {
        it.storageGeneration == row.revision && it.payload == row.payload && it.payloadDigest == row.digest && it.schema == row.schema
    })
    bounded.requireFoodPredecessors(producer)
    commerceRecordLegacy(bounded, row, producer)
    return FoodRow(row, value, producer)
}

internal suspend fun foodOrder(view: CommandReadView, id: DomainId, includeClosed: Boolean = false): FoodOrderCut {
    val bounded = foodReadView(view)
    val order = foodRead(bounded, LocalFoodProfileV1.ORDER, id) ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
    val body = order.envelope.body as FoodBodyV1.Order
    val index = foodRead(bounded, LocalFoodProfileV1.OWNER_INDEX, body.ownerId) ?: error("food.owner_index_missing")
    val active = id in (index.envelope.body as FoodBodyV1.OwnerIndex).orderIds
    check(active == (body.closedByOperationId == null)) { "food.owner_index_disagrees_with_closure" }
    if (!active && !includeClosed) throw SalesRuleViolation(SalesRule.IMMUTABLE_RECORD)
    return FoodOrderCut(order, index)
}
/** Bounded whole-order summary source. Rich recipe/current-stock work is selected by page first. */
internal suspend fun foodPortionSummary(view: CommandReadView, order: FoodOrderCut, id: DomainId): FoodRow {
    val row = foodCurrentPortionSummary(view, order, id)
    checkRoutedFoodPortion(row.envelope)
    return row
}

/** Current stored membership only; richer projections still close their immutable source facts. */
internal suspend fun foodCurrentPortionSummary(view: CommandReadView, order: FoodOrderCut, id: DomainId): FoodRow {
    check(id in order.value.portionIds) { "food.portion_not_owned" }
    val row = foodRead(view, LocalFoodProfileV1.PORTION, id) ?: error("food.portion_missing")
    when (val body = row.envelope.body) {
        is FoodBodyV1.Portion -> check(body.orderId == order.order.envelope.id && body.priceBatchId in order.value.batchIds &&
            body.recipe.activity == order.value.activity && body.recipe.channel == order.value.service.channel)
        is FoodBodyV1.QuotedPortion -> {
            requireFoodQuotedOrder(order.order.envelope)
            check(body.orderId == order.order.envelope.id && body.priceBatchId in order.value.batchIds)
        }
        else -> error("food.portion.body_required")
    }
    return row
}

/** Existing execution readers deliberately refuse work which has no routed snapshot. */
internal fun checkRoutedFoodPortion(envelope: FoodEnvelopeV1): FoodBodyV1.Portion =
    envelope.body as? FoodBodyV1.Portion ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)

internal fun foodStoredPortionPreparation(envelope: FoodEnvelopeV1): FoodPreparationState = when (val body = envelope.body) {
    is FoodBodyV1.Portion -> body.preparation
    is FoodBodyV1.QuotedPortion -> body.preparation
    else -> error("food.portion.body_required")
}

internal fun foodStoredPortionSelection(envelope: FoodEnvelopeV1): LocalFoodPortionSelectionV1 = when (val body = envelope.body) {
    is FoodBodyV1.Portion -> body.selection
    is FoodBodyV1.QuotedPortion -> body.selection
    else -> error("food.portion.body_required")
}
internal suspend fun foodPortion(view: CommandReadView, order: FoodOrderCut, id: DomainId,
    source: TransactionFoodRecipeSourceV1,
    quotationSource: TransactionFoodRecipeQuotationVerificationSourceV1? = source as? TransactionFoodRecipeQuotationVerificationSourceV1): FoodRow {
    val bounded = foodReadView(view)
    val row = foodPortionSummary(bounded, order, id)
    val body = row.envelope.body as FoodBodyV1.Portion
    val batch = foodRead(bounded, LocalFoodProfileV1.PRICE_BATCH, body.priceBatchId) ?: error("food.price_batch_missing")
    foodOriginalQuotationPortion(bounded, order, row, batch, source, quotationSource)?.let { return it.portion }
    source.requireHistorical(bounded, body.recipe)
    val priced = batch.envelope.body as FoodBodyV1.PriceBatch
    check(priced.orderId == body.orderId)
    val quote = StoredPricingSnapshotCodec.decode(priced.quote)
    check(quote.lines.single { it.portionId == id }.quantity == body.selection.quantity &&
        priced.sources.single { it.portionId == id }.skuId == body.recipe.pricingSkuId)
    return row
}

internal fun foodResources(order: FoodOrderCut, portions: List<FoodRow> = emptyList()): Set<RecoveryResourceKey> =
    foodEnvelopeResources(order.order.envelope, order.ownerIndex.envelope, portions.map { it.envelope })
/** Resource derivation from literal envelopes, not a fabricated current/retained row. */
internal fun foodEnvelopeResources(order: FoodEnvelopeV1, ownerIndex: FoodEnvelopeV1,
    portions: List<FoodEnvelopeV1>): Set<RecoveryResourceKey> = buildSet {
    val body = order.body as FoodBodyV1.Order
    add(orderResource(order.id)); add(orderResource(ownerIndex.id))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, body.ownerId))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, body.creatorId))
    body.handoff?.let { add(orderResource(it.id)); add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.to)) }
    for (row in portions) {
        add(orderResource(row.id))
        when (val portion = row.body) {
            is FoodBodyV1.Portion -> {
                add(orderResource(portion.priceBatchId)); add(orderResource(portion.recipe.snapshotId))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, portion.recipe.recipeId))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, portion.recipe.publicationId))
                portion.recipe.sources.forEach {
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.id))
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.authorizedBy))
                    it.signingKeyId?.let { key -> add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, key)) }
                    it.operationId?.let { operation -> add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, DomainId(operation.value))) }
                }
                portion.recipe.routes.forEach { add(orderResource(it.stationId)); add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.stationId)) }
                portion.recipe.ingredients.forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.skuId)) }
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, portion.recipe.warehouseId))
                portion.preparation.issueId?.let { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
                portion.preparation.reservationId?.let { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
                portion.preparation.tasks.forEach { add(orderResource(it.taskId)) }
            }
            is FoodBodyV1.QuotedPortion -> {
                requireFoodQuotationEnvelope(row, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
                add(orderResource(portion.priceBatchId)); add(orderResource(portion.quotationId))
            }
            else -> error("food.portion.body_required")
        }
    }
}

/** Closed typed write set with immutable before copies. No direct database mutations or publication of success. */
internal class FoodWrites(private val context: CommandContext, private val at: UtcInstant) {
    private val before = linkedMapOf<AggregateKey, FoodRow>()
    private val after = linkedMapOf<AggregateKey, FoodEnvelopeV1>()
    fun put(kind: String, id: DomainId, body: FoodBodyV1, prior: FoodRow? = null): FoodEnvelopeV1 {
        val key = AggregateKey(kind, id)
        check(key !in after && (prior == null || prior.row.key == key))
        if (prior != null) {
            check(prior.producer.operationId == prior.envelope.operationId && prior.producer.scope == context.scope)
            before[key] = prior
        }
        val value = FoodEnvelopeV1(context.scope, kind, id, prior?.row?.revision?.next() ?: Revision(1), context.operationId, body)
        requireFoodEnvelope(value)
        after[key] = value
        return value
    }
    fun apply(result: LocalFoodResultV1, jobs: List<JobIntent> = emptyList(),
        stock: LocalStockRepository? = null, contribution: LocalStockContribution? = null,
        extraClaims: List<ClaimChange> = emptyList()): ProposedOutcome.Apply {
        check((stock == null) == (contribution == null))
        // These actual producers become the NEXT row's mandatory direct predecessor set.
        // Admission precedes Apply and uses the same framed union bound as future foodRead.
        // A 1-MiB outer proposal alone says nothing about this independent parent union.
        foodAdmitPredecessors(before.values)
        val changes = after.map { (key, value) -> AggregateChange(key, before[key]?.row?.revision,
            LocalFoodProfileV1.schema(key.kind), foodEnvelopeBytes(value)) }.toMutableList()
        val refs = changes.map { FoodAfterRefV1(it.key.kind, it.key.id, checkNotNull(after[it.key]).revision, sha256(it.payload.copyBytes())) }
        val beforeIds = mutableListOf<DomainId>()
        for ((key, row) in before) {
            val id = foodBeforeId(context.operationId, key.kind, key.id)
            beforeIds += id
            changes += AggregateChange(AggregateKey(LocalFoodProfileV1.BEFORE, id), null,
                LocalFoodProfileV1.schema(LocalFoodProfileV1.BEFORE), localSalesEncode(FoodBeforeV1(row.envelope), FoodBeforeV1.serializer(), 32_768))
        }
        val event = FoodEventV1(context.scope, context.operationId, context.originalActorId, at, beforeIds, refs, contribution != null, result)
        changes += AggregateChange(AggregateKey(LocalFoodProfileV1.EVENT, DomainId(context.operationId.value)), null,
            LocalFoodProfileV1.schema(LocalFoodProfileV1.EVENT), localSalesEncode(event, FoodEventV1.serializer()))
        val claims = after.filterKeys { it !in before }.map { (key, _) ->
            ClaimChange.Acquire(LocalFoodProfileV1.claim(key.kind, key.id), key.id)
        } + extraClaims
        val encoded = localSalesEncode(result, LocalFoodResultV1.serializer())
        foodAdmitProposal(encoded, changes, claims, jobs)
        val proposal = if (contribution == null) ProposedOutcome.Apply(encoded, changes, claims, jobs)
        else checkNotNull(stock).compose(context, contribution, encoded, changes, claims, jobs)
        // Also account for the WHOLE composed stock/food producer, not a food-only estimate.
        foodAdmitProposal(proposal.result, proposal.changes, proposal.claims, proposal.jobs)
        return proposal
    }
}

internal fun foodEvent(evidence: RetainedCommandEvidence): FoodEventV1 {
    val row = evidence.aggregateWrites.single { it.key == AggregateKey(LocalFoodProfileV1.EVENT, DomainId(evidence.operationId.value)) }
    check(row.storageGeneration == Revision(1) && row.schema == LocalFoodProfileV1.schema(row.key.kind))
    return localSalesDecode(row.payload, FoodEventV1.serializer())
}
internal fun foodBefore(evidence: RetainedCommandEvidence, id: DomainId): FoodBeforeV1 {
    val row = evidence.aggregateWrites.single { it.key == AggregateKey(LocalFoodProfileV1.BEFORE, id) }
    check(row.storageGeneration == Revision(1) && row.schema == LocalFoodProfileV1.schema(row.key.kind))
    return localSalesDecode(row.payload, FoodBeforeV1.serializer(), 32_768).also {
        requireFoodEnvelope(it.envelope)
        check(id == foodBeforeId(evidence.operationId, it.envelope.kind, it.envelope.id))
    }
}
internal fun foodResult(code: String, order: FoodEnvelopeV1, portions: List<FoodEnvelopeV1> = emptyList()): LocalFoodResultV1 =
    LocalFoodResultV1(code, order.id, order.revision, portions.map {
        val state = foodStoredPortionPreparation(it)
        LocalFoodPortionRevisionV1(it.id, state.revision, state.status, state.issueId)
    })
internal fun foodReject(code: String): ProposedOutcome = ProposedOutcome.Reject(code,
    localSalesEncode(LocalFoodResultV1(code, null, null), LocalFoodResultV1.serializer()))

internal fun requireFoodIssueRecipe(issue: StockIssue, portion: FoodBodyV1.Portion) {
    check(issue.allocations.isNotEmpty() && issue.allocations.all { it.allocation.position.serialId == null &&
        it.allocation.position.pool.warehouseId == portion.recipe.warehouseId &&
        it.allocation.position.pool.ownership == StockOwnership.CompanyOwned && it.allocation.position.custody == StockCustody.RELEASED })
    val grouped = issue.allocations.groupBy { it.allocation.lineId }
    check(grouped.keys == portion.recipe.ingredients.map { foodIngredientLine(portion.selection.portionId, it.componentId) }.toSet())
    for (ingredient in portion.recipe.ingredients) {
        val rows = grouped.getValue(foodIngredientLine(portion.selection.portionId, ingredient.componentId))
        check(rows.all { it.allocation.position.pool.skuId == ingredient.skuId })
        check(rows.fold(Quantity(ingredient.requiredBaseQuantity.unitId, 0, ingredient.requiredBaseQuantity.scale)) { total, row ->
            total + row.allocation.quantity
        } == ingredient.requiredBaseQuantity)
    }
}

internal suspend fun foodOpenOwnerIndex(view: CommandReadView, ownerId: DomainId): FoodRow? =
    foodRead(view, LocalFoodProfileV1.OWNER_INDEX, ownerId)
internal fun foodOpenOrderIds(index: FoodRow?): List<DomainId> =
    (index?.envelope?.body as? FoodBodyV1.OwnerIndex)?.orderIds.orEmpty()

internal sealed interface FoodClosingWork {
    data class Observed(val index: FoodRow?, val claimIndex: FoodFinancialRow?) : FoodClosingWork {
        val orderIds: List<DomainId> get() = foodOpenOrderIds(index)
        val unresolvedClaimIds: List<DomainId> get() = (claimIndex?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty()
        val blockingWorkIds: List<DomainId> get() = orderIds + unresolvedClaimIds
    }
    data object CapacityRequired : FoodClosingWork
}

/** Both index identities are fenced even when absent or when the bounded read is unavailable. */
internal fun foodClosingResources(work: FoodClosingWork, ownerId: DomainId): Set<RecoveryResourceKey> {
    val observed = work as? FoodClosingWork.Observed
    return setOf(orderResource(ownerId), entitlementResource(foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, ownerId))) +
        observed?.orderIds.orEmpty().map(::orderResource) + observed?.unresolvedClaimIds.orEmpty().map(::entitlementResource)
}

/** Inability to verify the owner index is NEVER an empty-work observation or permission to close. */
internal suspend fun foodClosingWork(view: CommandReadView, ownerId: DomainId): FoodClosingWork = try {
    val bounded = foodReadView(view)
    FoodClosingWork.Observed(foodOpenOwnerIndex(bounded, ownerId), foodFinancialRead(bounded, LocalFoodFinancialProfileV1.CLAIM_INDEX,
        foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, ownerId)))
} catch (_: FoodCapacityExceeded) {
    FoodClosingWork.CapacityRequired
}

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Explicit stored identities. Registration and genuine source/producer custody remain separate. */
internal object FoodQuotationStorageV1 {
    const val RECIPE_QUOTATION: String = "sales.food.recipe-quotation"
    const val RECIPE_QUOTATION_SCHEMA: String = "sales.food.recipe-quotation/v1"
    const val QUOTED_PORTION_SCHEMA: String = "sales.food.portion/quotation-v1"
    const val MAX_BYTES: Int = 28_672
}

/** Explicit schema dispatch never sends quotation bodies through a routed codec. */
internal fun foodQuotationEnvelopeBytes(value: FoodEnvelopeV1, schema: String): StoredBytes {
    requireFoodQuotationEnvelope(value, schema)
    return localSalesEncode(value, FoodEnvelopeV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)
}

internal fun foodQuotationEnvelope(schema: String, bytes: StoredBytes): FoodEnvelopeV1 {
    val value = localSalesDecode(bytes, FoodEnvelopeV1.serializer(), FoodQuotationStorageV1.MAX_BYTES)
    requireFoodQuotationEnvelope(value, schema)
    check(foodQuotationEnvelopeBytes(value, schema) == bytes) { "food.quotation.non_canonical" }
    return value
}

internal fun requireFoodQuotationEnvelope(value: FoodEnvelopeV1, schema: String) {
    when (val body = value.body) {
        is FoodBodyV1.RecipeQuotation -> {
            val quotation = body.quotation
            check(schema == FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA &&
                value.kind == FoodQuotationStorageV1.RECIPE_QUOTATION && value.revision == Revision(1) &&
                value.id == quotation.quotationId && quotation.quotationId ==
                    LocalFoodQuotationProfileV1.quotationId(quotation.portionId, quotation.publicationId))
            requireFoodQuotationUnicode(quotation.description)
            requireFoodQuotationUnicode(quotation.signedPublication)
        }
        is FoodBodyV1.QuotedPortion -> {
            val selection = body.selection
            val preparation = body.preparation
            check(schema == FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA && value.kind == LocalFoodProfileV1.PORTION &&
                value.id == selection.portionId && value.id == preparation.portionId)
            check(selection.expectedRecipeRevision.value > 0 && selection.quantity.scaled > 0 &&
                selection.modifierIds.size <= 16 && selection.modifierIds.distinct().size == selection.modifierIds.size)
            foodText(selection.itemNote, 240)
            foodText(selection.seatNote, 160)
            requireFoodQuotationUnicode(selection.itemNote)
            requireFoodQuotationUnicode(selection.seatNote)
            check(preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && preparation.tasks.isEmpty() &&
                preparation.reservationId == null && preparation.issueId == null && preparation.issueRevision == null &&
                preparation.sentAt == null && preparation.servedAt == null && preparation.pickedUpAt == null)
            when (preparation.status) {
                FoodPreparationStatus.NOT_SENT -> check(preparation.revision == Revision(0) &&
                    preparation.cancellationReasonId == null && preparation.cancellationRequestedAt == null)
                FoodPreparationStatus.CANCELLED_BEFORE_START -> check(preparation.revision == Revision(1) &&
                    preparation.cancellationReasonId != null && preparation.cancellationRequestedAt != null)
                else -> error("food.quotation.bound_preparation")
            }
            check(value.revision.value == preparation.revision.value + 1)
        }
        else -> error("food.quotation.body_required")
    }
}

/** Structural joins against explicit expected data, NOT source/original/native ownership or authorization.
 * Complete write-set closure and genuine producer checks remain mandatory at the installed boundary. */
internal fun requireFoodQuotationPair(order: FoodEnvelopeV1, portion: FoodEnvelopeV1,
    quotation: FoodEnvelopeV1, priceBatch: FoodEnvelopeV1, expected: LocalFoodRecipeQuotationBatchV1) {
    requireFoodQuotedOrder(order)
    requireFoodQuotationEnvelope(portion, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
    requireFoodOriginalQuotationPair(order, portion, quotation, priceBatch, expected)
}

/** Literal sidecar membership for an actual quoted OR routed body; never converts a stored view. */
private fun requireFoodOriginalQuotationPair(order: FoodEnvelopeV1, portion: FoodEnvelopeV1,
    quotation: FoodEnvelopeV1, priceBatch: FoodEnvelopeV1, expected: LocalFoodRecipeQuotationBatchV1) {
    requireFoodEnvelope(order)
    requireFoodQuotationEnvelope(quotation, FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA)
    requireFoodEnvelope(priceBatch)
    val orderBody = order.body as? FoodBodyV1.Order ?: error("food.quotation.order_required")
    val quotationBody = quotation.body as FoodBodyV1.RecipeQuotation
    val batchBody = priceBatch.body as? FoodBodyV1.PriceBatch ?: error("food.quotation.price_batch_required")
    val recipe = quotationBody.quotation
    val selected: LocalFoodPortionSelectionV1
    val portionOrderId: DomainId
    val portionBatchId: DomainId
    when (val body = portion.body) {
        is FoodBodyV1.QuotedPortion -> {
            requireFoodQuotedOrder(order)
            requireFoodQuotationEnvelope(portion, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
            selected = body.selection
            portionOrderId = body.orderId
            portionBatchId = body.priceBatchId
            check(body.quotationId == quotation.id &&
                (body.preparation.revision != Revision(0) || portion.operationId == quotation.operationId))
        }
        is FoodBodyV1.Portion -> {
            requireFoodEnvelope(portion)
            check(body.preparation.revision.value >= 1) { "food.quotation.binding_transition_required" }
            FoodRecipeRouting.requireOriginalFacts(recipe, body.recipe)
            selected = body.selection
            portionOrderId = body.orderId
            portionBatchId = body.priceBatchId
        }
        else -> error("food.quotation.portion_required")
    }
    check(order.scope == portion.scope && order.scope == quotation.scope && order.scope == priceBatch.scope &&
        portionOrderId == order.id && quotationBody.orderId == order.id && batchBody.orderId == order.id &&
        portion.id in orderBody.portionIds && priceBatch.id in orderBody.batchIds &&
        portionBatchId == priceBatch.id && quotationBody.priceBatchId == priceBatch.id &&
        recipe.portionId == portion.id && quotation.operationId == priceBatch.operationId)
    check(recipe.activity == orderBody.activity && recipe.channel == orderBody.service.channel &&
        selected.itemId == recipe.outputSkuId && selected.recipeId == recipe.recipeId &&
        selected.expectedRecipeRevision == recipe.recipeRevision && selected.choiceId == recipe.choiceId &&
        selected.modifierIds.toSet() == recipe.modifierIds.toSet() && selected.quantity == recipe.portionQuantity)

    // Compare the whole original price document and every ordered provenance field, never just a total or SKU.
    check(batchBody.quote == StoredPricingSnapshotCodec.encode(expected.quote) && batchBody.sources == expected.priceSources)
    val originals = expected.quotations
    val prices = expected.quote.lines
    check(originals.size == prices.size && originals.map { it.portionId } == prices.map { it.portionId } &&
        originals.map { it.portionId } == expected.priceSources.map { it.portionId } &&
        originals.map { it.portionId }.distinct().size == originals.size &&
        originals.map { it.quotationId }.distinct().size == originals.size)
    for (index in originals.indices) {
        val original = originals[index]
        val price = prices[index]
        val source = expected.priceSources[index]
        check(original.activity == orderBody.activity && original.channel == orderBody.service.channel &&
            original.portionId in orderBody.portionIds &&
            price.quantity == original.portionQuantity && source.skuId == original.pricingSkuId &&
            source.scope == order.scope && price.lineId == source.lineId &&
            price.priceRevision == source.companyPriceRevision && price.unitPrice == source.effectiveUnitPrice)
    }
    val index = originals.indexOfFirst { it.portionId == portion.id }
    check(index >= 0 && prices[index].lineId == selected.commercialLineId)
    check(localSalesEncode(recipe, LocalFoodRecipeQuotationV1.serializer(), FoodQuotationStorageV1.MAX_BYTES) ==
        localSalesEncode(originals[index], LocalFoodRecipeQuotationV1.serializer(), FoodQuotationStorageV1.MAX_BYTES))
}

/** Unbound work cannot participate in a handoff, a money lock or a closed check. */
internal fun requireFoodQuotedOrder(order: FoodEnvelopeV1) {
    requireFoodEnvelope(order)
    val value = order.body as? FoodBodyV1.Order ?: error("food.quotation.order_required")
    check(value.handoff == null && value.financialCollectionId == null && value.closedByOperationId == null) {
        "food.quotation.unpaid_open_order_required"
    }
}

/** Complete literal members of ONE immutable quotation batch, in the original price-line order.
 * This is a structural join. It neither supplies source verification nor native producer custody. */
internal fun foodQuotationBatch(order: FoodEnvelopeV1, portions: List<FoodEnvelopeV1>,
    quotations: List<FoodEnvelopeV1>, priceBatch: FoodEnvelopeV1): LocalFoodRecipeQuotationBatchV1 {
    requireFoodQuotedOrder(order)
    check(portions.all { it.body is FoodBodyV1.QuotedPortion }) { "food.quotation.all_quoted_required" }
    return foodOriginalQuotationBatch(order, portions, quotations, priceBatch)
}

/** Complete ordered original sidecars across actual quoted/routed members, structural only.
 * The all-quoted wrapper above keeps existing Add/cancellation source producers restrictive. */
internal fun foodOriginalQuotationBatch(order: FoodEnvelopeV1, portions: List<FoodEnvelopeV1>,
    quotations: List<FoodEnvelopeV1>, priceBatch: FoodEnvelopeV1): LocalFoodRecipeQuotationBatchV1 {
    requireFoodEnvelope(order)
    if (portions.any { it.body is FoodBodyV1.QuotedPortion }) requireFoodQuotedOrder(order)
    requireFoodEnvelope(priceBatch)
    val priced = priceBatch.body as? FoodBodyV1.PriceBatch ?: error("food.quotation.price_batch_required")
    val quote = StoredPricingSnapshotCodec.decode(priced.quote)
    val memberIds = quote.lines.map { it.portionId }
    check(priceBatch.scope == order.scope && priced.orderId == order.id &&
        priceBatch.id in (order.body as FoodBodyV1.Order).batchIds &&
        portions.size == quote.lines.size && portions.map { it.id } == memberIds &&
        priced.sources.map { it.portionId } == memberIds &&
        quotations.size == portions.size && quotations.map { it.id }.distinct().size == quotations.size)
    val selections = portions.map {
        when (val body = it.body) {
            is FoodBodyV1.QuotedPortion -> {
                requireFoodQuotationEnvelope(it, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
                body.selection
            }
            is FoodBodyV1.Portion -> {
                requireFoodEnvelope(it)
                body.selection
            }
            else -> error("food.quotation.portion_required")
        }
    }
    LocalFoodSelectionGroups.group(selections)
    val originals = quotations.map {
        requireFoodQuotationEnvelope(it, FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA)
        (it.body as FoodBodyV1.RecipeQuotation).quotation
    }
    check(originals.map { it.portionId } == memberIds)
    val batch = LocalFoodRecipeQuotationBatchV1(quote, originals, priced.sources)
    for (index in portions.indices)
        requireFoodOriginalQuotationPair(order, portions[index], quotations[index], priceBatch, batch)
    return batch
}

/** Resource aliases from an exact literal pair. Every referenced source must still be authenticated
 * through the real current/original loan; symbolic route keys create no physical resource. */
internal fun foodQuotationResources(portion: FoodEnvelopeV1, quotation: FoodEnvelopeV1,
    priceBatch: FoodEnvelopeV1): Set<RecoveryResourceKey> {
    requireFoodQuotationEnvelope(portion, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
    return foodOriginalQuotationResources(portion, quotation, priceBatch)
}

/** Quotation/routing aliases only. Native/source and operation-effect unions remain mandatory. */
internal fun foodOriginalQuotationResources(portion: FoodEnvelopeV1, quotation: FoodEnvelopeV1,
    priceBatch: FoodEnvelopeV1): Set<RecoveryResourceKey> {
    requireFoodQuotationEnvelope(quotation, FoodQuotationStorageV1.RECIPE_QUOTATION_SCHEMA)
    requireFoodEnvelope(priceBatch)
    val saved = quotation.body as FoodBodyV1.RecipeQuotation
    val priced = priceBatch.body as? FoodBodyV1.PriceBatch ?: error("food.quotation.price_batch_required")
    val recipe = saved.quotation
    val selection: LocalFoodPortionSelectionV1
    val orderId: DomainId
    val batchId: DomainId
    val routed: LocalFoodRecipeSnapshotV1?
    when (val body = portion.body) {
        is FoodBodyV1.QuotedPortion -> {
            requireFoodQuotationEnvelope(portion, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
            check(body.quotationId == quotation.id &&
                (body.preparation.revision != Revision(0) || portion.operationId == quotation.operationId))
            selection = body.selection; orderId = body.orderId; batchId = body.priceBatchId; routed = null
        }
        is FoodBodyV1.Portion -> {
            requireFoodEnvelope(portion)
            check(body.preparation.revision.value >= 1) { "food.quotation.binding_transition_required" }
            routed = FoodRecipeRouting.requireOriginalFacts(recipe, body.recipe)
            selection = body.selection; orderId = body.orderId; batchId = body.priceBatchId
        }
        else -> error("food.quotation.portion_required")
    }
    val prices = StoredPricingSnapshotCodec.decode(priced.quote).lines
    // Provenance follows the immutable commercial member order, not an unordered lookup.
    check(priced.sources.map { it.portionId } == prices.map { it.portionId }) {
        "food.quotation.ordered_price_sources_required"
    }
    val price = prices.single { it.portionId == portion.id }
    val provenance = priced.sources.single { it.portionId == portion.id }
    check(portion.scope == quotation.scope && portion.scope == priceBatch.scope &&
        orderId == saved.orderId && orderId == priced.orderId &&
        batchId == priceBatch.id && saved.priceBatchId == priceBatch.id && recipe.portionId == portion.id &&
        quotation.operationId == priceBatch.operationId &&
        selection.itemId == recipe.outputSkuId && selection.recipeId == recipe.recipeId &&
        selection.expectedRecipeRevision == recipe.recipeRevision && selection.choiceId == recipe.choiceId &&
        selection.modifierIds.toSet() == recipe.modifierIds.toSet() && selection.quantity == recipe.portionQuantity &&
        price.lineId == selection.commercialLineId && price.quantity == recipe.portionQuantity &&
        provenance.scope == portion.scope && provenance.skuId == recipe.pricingSkuId &&
        provenance.lineId == price.lineId && provenance.companyPriceRevision == price.priceRevision &&
        provenance.effectiveUnitPrice == price.unitPrice)
    return buildSet {
        add(orderResource(orderId)); add(orderResource(portion.id))
        add(orderResource(quotation.id)); add(orderResource(priceBatch.id))
        fun source(id: DomainId) { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)) }
        source(recipe.recipeId); source(recipe.publicationId)
        for (sku in (listOf(recipe.outputSkuId, recipe.pricingSkuId) + recipe.ingredients.map { it.skuId }).distinct()) {
            source(sku); add(RecoveryResourceKey(RecoveryResourceKind.STOCK, sku))
        }
        for (reference in recipe.sources) {
            source(reference.id); source(reference.authorizedBy)
            reference.signingKeyId?.let(::source)
            reference.operationId?.let { source(DomainId(it.value)) }
        }
        source(provenance.companyPublicationId); source(provenance.companyPriceRuleId)
        provenance.policyEntryId?.let(::source); provenance.policyPublicationId?.let(::source)
        provenance.branchOverrideEventId?.let(::source)
        if (routed != null) {
            add(orderResource(routed.snapshotId))
            source(routed.warehouseId); add(RecoveryResourceKey(RecoveryResourceKind.STOCK, routed.warehouseId))
            for (route in routed.routes) { add(orderResource(route.stationId)); source(route.stationId) }
            for (reference in routed.sources) {
                source(reference.id); source(reference.authorizedBy)
                reference.signingKeyId?.let(::source)
                reference.operationId?.let { source(DomainId(it.value)) }
            }
        }
    }
}

/** The selected pair plus the complete original batch and all aliases actually loaded for it. */
internal data class FoodQuotationPortionCut(
    val portion: FoodRow,
    val quotation: FoodRow,
    val priceBatch: FoodRow,
    val batch: LocalFoodRecipeQuotationBatchV1,
    val resources: Set<RecoveryResourceKey>,
)

/** Closes every quoted batch once; full-order callers also prove no extra current batch members. */
internal suspend fun foodQuotedPortions(view: CommandReadView, order: FoodOrderCut,
    source: TransactionFoodRecipeQuotationVerificationSourceV1,
    currentPortions: List<FoodRow>? = null): List<FoodQuotationPortionCut> {
    val bounded = foodReadView(view)
    val all = currentPortions ?: order.value.portionIds.map { foodCurrentPortionSummary(bounded, order, it) }
    check(all.map { it.envelope.id } == order.value.portionIds)
    val quoted = all.filter { it.envelope.body is FoodBodyV1.QuotedPortion }
    val closed = mutableListOf<FoodQuotationPortionCut>()
    for (batchId in quoted.map { (it.envelope.body as FoodBodyV1.QuotedPortion).priceBatchId }.distinct()) {
        val cut = foodCloseQuotationBatch(bounded, order, batchId, source, all)
        check(cut.all { it.portion.envelope.body is FoodBodyV1.QuotedPortion }) { "food.quotation.all_quoted_required" }
        closed += cut
    }
    val byId = closed.associateBy { it.portion.envelope.id }
    check(byId.size == closed.size && quoted.all { it.envelope.id in byId })
    return quoted.map { checkNotNull(byId[it.envelope.id]) }
}

/** Complete sidecar-backed batches only; a proved legacy producer does not imply sidecar custody. */
internal suspend fun foodOriginalQuotationPortions(view: CommandReadView, order: FoodOrderCut,
    source: TransactionFoodRecipeSourceV1, currentPortions: List<FoodRow>? = null,
    quotationSource: TransactionFoodRecipeQuotationVerificationSourceV1? = source as? TransactionFoodRecipeQuotationVerificationSourceV1): List<FoodQuotationPortionCut> {
    val bounded = foodReadView(view)
    val all = currentPortions ?: order.value.portionIds.map { foodCurrentPortionSummary(bounded, order, it) }
    check(all.map { it.envelope.id } == order.value.portionIds)
    val closed = mutableListOf<FoodQuotationPortionCut>()
    for (batchId in order.value.batchIds) {
        val batchRow = foodRead(bounded, LocalFoodProfileV1.PRICE_BATCH, batchId) ?: error("food.price_batch_missing")
        check((batchRow.envelope.body as FoodBodyV1.PriceBatch).orderId == order.order.envelope.id)
        if (foodCurrentPriceBatchProducerProfile(batchRow) == FoodPriceBatchProducerProfile.QUOTED_ADD) {
            val quotations = quotationSource ?: error("food.quotation.read_support_missing")
            closed += foodCloseQuotationBatch(bounded, order, batchId, quotations, all, batchRow, source)
        } else {
            check(all.none { (it.envelope.body as? FoodBodyV1.QuotedPortion)?.priceBatchId == batchId })
        }
    }
    check(closed.map { it.portion.envelope.id }.distinct().size == closed.size)
    return closed
}

/** Null means only an explicitly proved routed legacy origin, never a missing observation. */
internal suspend fun foodOriginalQuotationPortion(view: CommandReadView, order: FoodOrderCut,
    portion: FoodRow, priceBatch: FoodRow, source: TransactionFoodRecipeSourceV1,
    quotationSource: TransactionFoodRecipeQuotationVerificationSourceV1? = source as? TransactionFoodRecipeQuotationVerificationSourceV1): FoodQuotationPortionCut? {
    val routed = checkRoutedFoodPortion(portion.envelope)
    check(portion.envelope.id in order.value.portionIds && routed.orderId == order.order.envelope.id &&
        routed.priceBatchId == priceBatch.envelope.id && priceBatch.envelope.id in order.value.batchIds &&
        (priceBatch.envelope.body as FoodBodyV1.PriceBatch).orderId == routed.orderId)
    if (foodCurrentPriceBatchProducerProfile(priceBatch) == FoodPriceBatchProducerProfile.ROUTED_CREATE_OR_ADD) {
        return null
    }
    val quotations = quotationSource ?: error("food.quotation.read_support_missing")
    return foodCloseQuotationBatch(foodReadView(view), order, priceBatch.envelope.id, quotations, null, priceBatch, source)
        .single { it.portion.envelope.id == portion.envelope.id }
        .also { check(it.portion.row == portion.row) }
}

private fun foodCurrentPriceBatchProducerProfile(row: FoodRow): FoodPriceBatchProducerProfile =
    foodPriceBatchProducerProfile(row.envelope, if (row.normalizedProducer == null) row.producer else null,
        row.normalizedProducer)

/** Selected complete batch only, so a cancellation need not acquire unrelated recipe batches. */
internal suspend fun foodQuotedPortion(view: CommandReadView, order: FoodOrderCut, id: DomainId,
    source: TransactionFoodRecipeQuotationVerificationSourceV1): FoodQuotationPortionCut {
    val bounded = foodReadView(view)
    val selected = foodCurrentPortionSummary(bounded, order, id)
    val portion = selected.envelope.body as? FoodBodyV1.QuotedPortion ?: error("food.quotation.portion_required")
    val closed = foodCloseQuotationBatch(bounded, order, portion.priceBatchId, source, null)
    check(closed.all { it.portion.envelope.body is FoodBodyV1.QuotedPortion }) { "food.quotation.all_quoted_required" }
    return closed
        .single { it.portion.envelope.id == id }
}

internal suspend fun foodCloseQuotationBatch(view: CommandReadView, order: FoodOrderCut, batchId: DomainId,
    source: TransactionFoodRecipeQuotationVerificationSourceV1, currentPortions: List<FoodRow>?,
    currentPriceBatch: FoodRow? = null,
    historicalSource: TransactionFoodRecipeSourceV1? = source as? TransactionFoodRecipeSourceV1): List<FoodQuotationPortionCut> {
    check(batchId in order.value.batchIds)
    val batchRow = currentPriceBatch ?: foodRead(view, LocalFoodProfileV1.PRICE_BATCH, batchId) ?: error("food.price_batch_missing")
    check(batchRow.envelope.id == batchId && foodCurrentPriceBatchProducerProfile(batchRow) == FoodPriceBatchProducerProfile.QUOTED_ADD)
    val priced = batchRow.envelope.body as FoodBodyV1.PriceBatch
    val memberIds = StoredPricingSnapshotCodec.decode(priced.quote).lines.map { it.portionId }
    val known = currentPortions?.associateBy { it.envelope.id }
    if (currentPortions != null) {
        val actual = currentPortions.filter { when (val body = it.envelope.body) {
            is FoodBodyV1.Portion -> body.priceBatchId == batchId
            is FoodBodyV1.QuotedPortion -> body.priceBatchId == batchId
            else -> error("food.portion.body_required")
        } }.map { it.envelope.id }
        check(actual.size == memberIds.size && actual.toSet() == memberIds.toSet()) { "food.quotation.batch_membership" }
    }
    val portions = memberIds.map { id ->
        if (known != null) checkNotNull(known[id]) else foodCurrentPortionSummary(view, order, id)
    }
    val sidecars = portions.map {
        val id = when (val body = it.envelope.body) {
            is FoodBodyV1.QuotedPortion -> body.quotationId
            is FoodBodyV1.Portion -> LocalFoodQuotationProfileV1.quotationId(it.envelope.id, body.recipe.publicationId)
            else -> error("food.quotation.portion_required")
        }
        foodRead(view, FoodQuotationStorageV1.RECIPE_QUOTATION, id) ?: error("food.quotation.missing")
    }
    val batch = foodOriginalQuotationBatch(order.order.envelope, portions.map { it.envelope }, sidecars.map { it.envelope }, batchRow.envelope)
    // foodRead already required the actual native/whole original producer for every member,
    // immutable sidecar and batch. This mandatory source check adds the signed fact closure.
    for (recipe in batch.quotations) source.requireHistoricalQuotation(view, recipe)
    for (portion in portions) {
        val routed = portion.envelope.body as? FoodBodyV1.Portion ?: continue
        val historical = historicalSource ?: error("food.quotation.routed_source_unavailable")
        historical.requireHistorical(view, routed.recipe)
    }
    val resources = portions.indices.flatMap {
        foodOriginalQuotationResources(portions[it].envelope, sidecars[it].envelope, batchRow.envelope)
    }.toSet()
    return portions.indices.map { FoodQuotationPortionCut(portions[it], sidecars[it], batchRow, batch, resources) }
}

/** Escaped invalid scalar values are refused too, even when the surrounding JSON is valid UTF-8. */
private fun requireFoodQuotationUnicode(value: String) {
    value.encodeToByteArray(throwOnInvalidSequence = true)
}

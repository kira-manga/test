package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Checked read-only routing, not stored membership, kitchen ownership or a permission grant. */
internal suspend fun foodPrepareQuotationRouting(view: CommandReadView,
    source: TransactionFoodRecipeRoutingVerificationSource,
    quotation: LocalFoodRecipeQuotationV1): PreparedFoodRecipeRouting? {
    val prepared = source.prepareRouting(view, quotation) ?: return null
    check(prepared.scope == view.scope)
    return PreparedFoodRecipeRouting(prepared.scope,
        FoodRecipeRouting.requireOriginalFacts(quotation, prepared.snapshot), prepared.recoveryResources)
}

/** The projected body is never presented as the actual before row or as execution authority. */
internal fun foodQuotationBindingPortion(before: FoodEnvelopeV1, quotation: LocalFoodRecipeQuotationV1,
    snapshot: LocalFoodRecipeSnapshotV1): FoodBodyV1.Portion {
    requireFoodQuotationEnvelope(before, FoodQuotationStorageV1.QUOTED_PORTION_SCHEMA)
    val portion = before.body as FoodBodyV1.QuotedPortion
    check(portion.quotationId == quotation.quotationId && before.id == quotation.portionId &&
        portion.preparation.status == FoodPreparationStatus.NOT_SENT)
    return FoodBodyV1.Portion(portion.orderId, portion.selection, portion.priceBatchId,
        FoodRecipeRouting.requireOriginalFacts(quotation, snapshot), portion.preparation)
}

/** Real borrowed original handles stay in this cut, confined to the current source loan. */
internal class FoodQuotationBindingMember(val before: FoodRow, val routed: FoodBodyV1.Portion?,
    val quotation: FoodQuotationPortionCut?) {
    val bindsQuotation: Boolean get() = before.envelope.body is FoodBodyV1.QuotedPortion
    fun requireRouted(): FoodBodyV1.Portion = checkNotNull(routed) { "food.quotation.binding_unavailable" }
    fun afterResources(after: FoodEnvelopeV1): Set<RecoveryResourceKey> = quotation?.let {
        foodOriginalQuotationResources(after, it.quotation.envelope, it.priceBatch.envelope)
    }.orEmpty()
}

internal class FoodQuotationBindingCut(val order: FoodOrderCut, val members: List<FoodQuotationBindingMember>,
    val quotations: List<FoodQuotationPortionCut>, val resources: Set<RecoveryResourceKey>,
    val restriction: PreparedMutationAdmissionRestriction?) {
    val before: List<FoodRow> get() = members.map { it.before }
    fun retainPreparation(): FoodQuotationBindingPreparation = FoodQuotationBindingPreparation(this)
}

private data class FoodQuotationBindingLiteral(val key: AggregateKey, val revision: Revision, val schema: String,
    val payload: StoredBytes, val digest: PayloadDigest)
private fun foodQuotationBindingLiteral(row: StoredAggregate): FoodQuotationBindingLiteral =
    FoodQuotationBindingLiteral(row.key, row.revision, row.schema, row.payload, row.digest)
private data class FoodQuotationBindingFacts(val before: FoodQuotationBindingLiteral, val routed: StoredBytes?)

/** Only immutable scalar/byte observations escape preparation; no native producer or view does. */
internal class FoodQuotationBindingPreparation internal constructor(cut: FoodQuotationBindingCut) {
    private val order = foodQuotationBindingLiteral(cut.order.order.row)
    private val owner = foodQuotationBindingLiteral(cut.order.ownerIndex.row)
    private val members = cut.members.map { member -> FoodQuotationBindingFacts(
        foodQuotationBindingLiteral(member.before.row), member.routed?.let {
            localSalesEncode(it, FoodBodyV1.Portion.serializer(), FoodQuotationStorageV1.MAX_BYTES)
        }) }
    private val quotations = cut.quotations.map { quotation -> listOf(
        foodQuotationBindingLiteral(quotation.portion.row), foodQuotationBindingLiteral(quotation.quotation.row),
        foodQuotationBindingLiteral(quotation.priceBatch.row)) }
    private val resources = cut.resources.toSet()
    private val restriction = cut.restriction

    fun requireMatches(actual: FoodQuotationBindingCut) {
        val captured = FoodQuotationBindingPreparation(actual)
        check(restriction == null && captured.restriction == null && order == captured.order &&
            owner == captured.owner && members == captured.members && quotations == captured.quotations &&
            resources == captured.resources) { "food.quotation.binding_preparation_changed" }
    }
}

/** Complete original batches are authenticated first; only the requested unsent work is routed.
 * No row, reservation, task or collection is written by this genuine current-loan preparation. */
internal suspend fun foodPrepareQuotationBinding(view: CommandReadView, recipes: TransactionFoodStockSource,
    order: FoodOrderCut, portionIds: List<DomainId>, wholeOrder: Boolean): FoodQuotationBindingCut {
    val bounded = foodReadView(view)
    check(portionIds.distinct().size == portionIds.size && (!wholeOrder || portionIds == order.value.portionIds))
    val observed = portionIds.map { foodCurrentPortionSummary(bounded, order, it) }
    if (wholeOrder) foodFinancialRule(observed.none {
        foodStoredPortionPreparation(it.envelope).status in foodChargeDecisionStates
    }, SalesRule.INVALID_STATE)
    else foodFinancialRule(observed.all {
        foodStoredPortionPreparation(it.envelope).status == FoodPreparationStatus.NOT_SENT
    }, SalesRule.INVALID_STATE)
    val quotationSource = recipes as? TransactionFoodRecipeQuotationVerificationSourceV1
    if (observed.any { it.envelope.body is FoodBodyV1.QuotedPortion } && quotationSource == null)
        return FoodQuotationBindingCut(order, observed.map { FoodQuotationBindingMember(it,
            it.envelope.body as? FoodBodyV1.Portion, null) }, emptyList(), foodResources(order, observed),
            PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE)

    val quotations = if (wholeOrder) foodOriginalQuotationPortions(bounded, order, recipes, observed, quotationSource)
        else foodSelectedBindingQuotations(bounded, order, observed, recipes, quotationSource)
    val byId = quotations.associateBy { it.portion.envelope.id }
    check(byId.size == quotations.size)
    val rows = observed.map { row -> byId[row.envelope.id]?.portion ?: foodPortion(bounded, order, row.envelope.id, recipes) }
    val resources = (foodResources(order, rows) + quotations.flatMap { it.resources }).toMutableSet()
    val routingSource = recipes as? TransactionFoodRecipeRoutingVerificationSource
    val quoted = rows.any { it.envelope.body is FoodBodyV1.QuotedPortion }
    var restriction: PreparedMutationAdmissionRestriction? = null
    if (quoted) {
        val activity = recipes.prepareActivity(bounded, order.value.activity)
        resources += activity.recoveryResources
        if (!activity.sourceEnabled || routingSource == null)
            restriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
    }
    val members = rows.map { row ->
        val quotation = byId[row.envelope.id]
        val routed = when (val body = row.envelope.body) {
            is FoodBodyV1.Portion -> body
            is FoodBodyV1.QuotedPortion -> {
                val saved = checkNotNull(quotation).quotation.envelope.body as FoodBodyV1.RecipeQuotation
                val prepared = routingSource?.let { foodPrepareQuotationRouting(bounded, it, saved.quotation) }
                if (prepared == null) { restriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE; null }
                else {
                    resources += prepared.recoveryResources
                    foodQuotationBindingPortion(row.envelope, saved.quotation, prepared.snapshot)
                }
            }
            else -> error("food.quotation.portion_required")
        }
        FoodQuotationBindingMember(row, routed, quotation)
    }
    if (order.value.service.channel == LocalSalesChannel.DELIVERY)
        restriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
    if (restriction == null) {
        val unsent = members.map { it.requireRouted() }.filter { it.preparation.status == FoodPreparationStatus.NOT_SENT }
        if (unsent.isNotEmpty()) restriction = recipes.prepareKitchenExecution(bounded,
            order.value.activity, order.value.service, unsent.map { it.recipe })
    }
    return FoodQuotationBindingCut(order, members, quotations, resources.toSet(), restriction)
}

/** Selected ingress closes each touched sidecar-backed batch, not unrelated order batches. */
private suspend fun foodSelectedBindingQuotations(view: CommandReadView, order: FoodOrderCut,
    selected: List<FoodRow>, recipes: TransactionFoodStockSource,
    source: TransactionFoodRecipeQuotationVerificationSourceV1?): List<FoodQuotationPortionCut> {
    val closed = mutableListOf<FoodQuotationPortionCut>()
    val batches = mutableSetOf<DomainId>()
    for (row in selected) {
        val batchId = when (val portion = row.envelope.body) {
            is FoodBodyV1.Portion -> portion.priceBatchId
            is FoodBodyV1.QuotedPortion -> portion.priceBatchId
            else -> error("food.quotation.portion_required")
        }
        if (!batches.add(batchId)) continue
        val priceBatch = foodRead(view, LocalFoodProfileV1.PRICE_BATCH, batchId) ?: error("food.quotation.price_batch_missing")
        val profile = foodPriceBatchProducerProfile(priceBatch.envelope,
            if (priceBatch.normalizedProducer == null) priceBatch.producer else null, priceBatch.normalizedProducer)
        if (profile == FoodPriceBatchProducerProfile.ROUTED_CREATE_OR_ADD) {
            check(selected.none { (it.envelope.body as? FoodBodyV1.QuotedPortion)?.priceBatchId == batchId })
            continue
        }
        val quotationSource = source ?: error("food.quotation.read_support_missing")
        closed += foodCloseQuotationBatch(view, order, batchId, quotationSource, currentPortions = null,
            currentPriceBatch = priceBatch, historicalSource = recipes)
    }
    check(closed.map { it.portion.envelope.id }.distinct().size == closed.size)
    return closed
}

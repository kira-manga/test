package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Separate identity for original recipe facts; never a routed execution snapshot or grant. */
public object LocalFoodQuotationProfileV1 {
    public fun quotationId(portionId: DomainId, publicationId: DomainId): DomainId =
        foodId("recipe-quotation", "${portionId.value}/${publicationId.value}")
}

/** Authentic source facts may be captured without branch fulfillment configuration.
 * Construction checks shape only: signatures and original ownership still need the installed source.
 * The private serialized collections are copied into read-only storage before they can escape. */
@Serializable
public class LocalFoodRecipeQuotationV1(
    public val quotationId: DomainId,
    public val portionId: DomainId,
    public val activity: LocalSalesActivity,
    public val channel: LocalSalesChannel,
    public val recipeId: DomainId,
    public val recipeRevision: Revision,
    public val publicationId: DomainId,
    public val outputSkuId: DomainId,
    public val outputSkuRevision: Revision,
    public val choiceId: DomainId,
    @SerialName("modifierIds") private var selectedModifiers: List<DomainId>,
    public val description: String,
    public val yieldQuantity: Quantity,
    public val portionQuantity: Quantity,
    public val pricingSkuId: DomainId,
    @SerialName("ingredients") private var selectedIngredients: List<LocalFoodIngredientV1>,
    @SerialName("routeKeys") private var requiredRouteKeys: List<DomainId>,
    public val signedPublication: String,
    @SerialName("sources") private var publicationSources: List<LocalFoodSourceReferenceV1>,
) {
    public val modifierIds: List<DomainId> get() = selectedModifiers
    public val ingredients: List<LocalFoodIngredientV1> get() = selectedIngredients
    public val routeKeys: List<DomainId> get() = requiredRouteKeys
    public val sources: List<LocalFoodSourceReferenceV1> get() = publicationSources

    init {
        selectedModifiers = quotationList(selectedModifiers)
        selectedIngredients = quotationList(selectedIngredients)
        requiredRouteKeys = quotationList(requiredRouteKeys)
        publicationSources = quotationList(publicationSources)
        require(quotationId == LocalFoodQuotationProfileV1.quotationId(portionId, publicationId))
        require(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) &&
            channel in setOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY,
                LocalSalesChannel.DELIVERY))
        require(recipeRevision.value > 0 && outputSkuRevision.value > 0)
        foodText(description, LocalFoodProfileV1.MAX_RECIPE_DESCRIPTION, false)
        require(modifierIds.size <= 16 && modifierIds.distinct().size == modifierIds.size)
        require(routeKeys.size in 1..4 && routeKeys.distinct().size == routeKeys.size)
        require(ingredients.size in 1..16 && ingredients.map { it.componentId }.distinct().size == ingredients.size)
        require(yieldQuantity.unitId == portionQuantity.unitId && yieldQuantity.scale == portionQuantity.scale &&
            yieldQuantity.scaled > 0 && portionQuantity.scaled > 0)
        for (ingredient in ingredients) {
            require(ingredient.skuRevision.value > 0 && ingredient.quantityPerYield.scaled > 0 &&
                ingredient.requiredBaseQuantity.scaled > 0 &&
                ingredient.requiredBaseQuantity.unitId == ingredient.quantityPerYield.unitId &&
                ingredient.requiredBaseQuantity.scale == ingredient.quantityPerYield.scale)
            require((ingredient.quantityPerYield.toFraction() * portionQuantity.toFraction() / yieldQuantity.toFraction())
                .toScaledLongExact(ingredient.requiredBaseQuantity.scale) == ingredient.requiredBaseQuantity.scaled)
        }
        require(signedPublication.isNotEmpty() && signedPublication.length <= LocalFoodProfileV1.MAX_SIGNED_RECIPE_BYTES &&
            signedPublication.encodeToByteArray(throwOnInvalidSequence = true).size <= LocalFoodProfileV1.MAX_SIGNED_RECIPE_BYTES)
        require(sources.size in 2..32 && sources.map { it.kind to it.id }.distinct().size == sources.size &&
            sources.all { it.revision.value > 0 && it.signingKeyId != null })
        require(sources.count { it.kind == "local.food.publication" } == 1 &&
            sources.single { it.kind == "local.food.publication" }.let {
                it.id == publicationId && it.operationId != null
            })
        require(sources.all {
            (it.kind == "local.food.publication" && it.operationId != null) ||
                (it.kind == "local.catalog.publication" && it.operationId == null)
        })
    }
}

/** Original price/provenance and ordered recipe facts. This contains no fulfillment authority. */
public class LocalFoodRecipeQuotationBatchV1(
    public val quote: PricingSnapshot,
    quotations: List<LocalFoodRecipeQuotationV1>,
    priceSources: List<LocalSalesPriceSourceV2>,
) {
    public val quotations: List<LocalFoodRecipeQuotationV1> = quotationList(quotations)
    public val priceSources: List<LocalSalesPriceSourceV2> = quotationList(priceSources)
}

/** A distinct installed-source port: existing routed source support never implies this support. */
public interface TransactionFoodRecipeQuotationSourceV1 {
    public suspend fun prepareQuotation(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeQuotationBatchV1?
}

/** Installed immutable and literal-original source checks; no DTO supplies source authority.
 * These mandatory read-only checks do not grant Master access or install saved-operation custody. */
public interface TransactionFoodRecipeQuotationVerificationSourceV1 : TransactionFoodRecipeQuotationSourceV1 {
    public suspend fun requireHistoricalQuotation(view: CommandReadView, quotation: LocalFoodRecipeQuotationV1)
    public suspend fun requireOriginalHistoricalQuotation(sources: LocalCommerceOriginalSources,
        quotation: LocalFoodRecipeQuotationV1): Set<RecoveryResourceKey>
    public suspend fun requireOriginalQuotation(sources: LocalCommerceOriginalSources, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision, selections: List<LocalFoodPortionSelectionV1>,
        quoted: LocalFoodRecipeQuotationBatchV1): Set<RecoveryResourceKey>
}

/** All source reads finish before this pure pricing preparation leaves its authorized loan. */
public interface PreparedFoodRecipeQuotationBatchV1 {
    public val scope: BranchScope
    public val recoveryResources: Set<RecoveryResourceKey>
    public fun quote(batchId: DomainId, observedAt: UtcInstant): LocalFoodRecipeQuotationBatchV1
}

private fun <T> quotationList(source: List<T>): List<T> = object : AbstractList<T>() {
    private val entries = source.toList()
    override val size: Int get() = entries.size
    override fun get(index: Int): T = entries[index]
}

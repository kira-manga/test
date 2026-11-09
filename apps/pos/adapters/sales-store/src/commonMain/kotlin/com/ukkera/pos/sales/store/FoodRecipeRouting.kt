package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId

/** Read-only current routing for genuine saved quotations. This port grants no execution authority. */
public interface TransactionFoodRecipeRoutingSource {
    public suspend fun prepareRouting(
        view: CommandReadView,
        quotation: LocalFoodRecipeQuotationV1,
    ): PreparedFoodRecipeRouting?
}

/** Stronger mandatory source checks for the binding operation's literal original cut.
 * Preparation alone never advertises this support or establishes stored-operation custody. */
public interface TransactionFoodRecipeRoutingVerificationSource : TransactionFoodRecipeRoutingSource {
    public suspend fun requireOriginalRouting(
        sources: LocalCommerceOriginalSources,
        quotation: LocalFoodRecipeQuotationV1,
        snapshot: LocalFoodRecipeSnapshotV1,
    ): Set<RecoveryResourceKey>
}

/** Finished source reads only: neither the borrowed view nor mutable collections escape. */
public class PreparedFoodRecipeRouting(
    public val scope: BranchScope,
    snapshot: LocalFoodRecipeSnapshotV1,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    public val snapshot: LocalFoodRecipeSnapshotV1 = snapshot.immutableRoutingCopy()
    private val resources: Set<RecoveryResourceKey> = recoveryResources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()
}

/** Shape-only projection. Authentic current/original source ownership is checked by the adapter. */
public object FoodRecipeRouting {
    /** Exact original-fact correspondence only; use this retained value for subsequent reads.
     * Neither a successful shape check nor its returned snapshot supplies source authority. */
    public fun requireOriginalFacts(
        quotation: LocalFoodRecipeQuotationV1,
        snapshot: LocalFoodRecipeSnapshotV1,
    ): LocalFoodRecipeSnapshotV1 {
        val captured = snapshot.immutableRoutingCopy()
        val projected = fromQuotation(quotation, captured.routeVersionId, captured.warehouseId,
            captured.routes, captured.sources.drop(quotation.sources.size))
        require(captured == projected) { "food.routing.original_facts_mismatch" }
        return projected
    }

    public fun fromQuotation(
        quotation: LocalFoodRecipeQuotationV1,
        routeVersionId: DomainId,
        warehouseId: DomainId,
        routes: List<LocalFoodStationRouteV1>,
        routeSources: List<LocalFoodSourceReferenceV1>,
    ): LocalFoodRecipeSnapshotV1 {
        val capturedRoutes = routingList(routes)
        val capturedSources = routingList(routeSources)
        require(capturedRoutes.map { it.routeKey } == quotation.routeKeys &&
            capturedRoutes.all { it.stationRevision.value > 0 })
        require(capturedSources.size == capturedRoutes.size + 1 &&
            capturedSources.all { it.kind == "local.food.configuration-version" && it.revision.value > 0 &&
                it.signingKeyId == null && it.operationId?.value == it.id.value } &&
            capturedSources.map { it.id }.distinct().size == capturedSources.size &&
            capturedSources.count { it.id == routeVersionId } == 1)
        return LocalFoodRecipeSnapshotV1(
            snapshotId = LocalFoodProfileV1.recipeSnapshotId(quotation.portionId, quotation.publicationId, routeVersionId),
            portionId = quotation.portionId,
            routeVersionId = routeVersionId,
            activity = quotation.activity,
            channel = quotation.channel,
            recipeId = quotation.recipeId,
            recipeRevision = quotation.recipeRevision,
            publicationId = quotation.publicationId,
            outputSkuId = quotation.outputSkuId,
            outputSkuRevision = quotation.outputSkuRevision,
            choiceId = quotation.choiceId,
            modifierIds = routingList(quotation.modifierIds),
            description = quotation.description,
            yieldQuantity = quotation.yieldQuantity,
            portionQuantity = quotation.portionQuantity,
            pricingSkuId = quotation.pricingSkuId,
            ingredients = routingList(quotation.ingredients),
            warehouseId = warehouseId,
            routes = capturedRoutes,
            signedPublication = quotation.signedPublication,
            sources = routingList(quotation.sources + capturedSources),
        )
    }
}

private fun LocalFoodRecipeSnapshotV1.immutableRoutingCopy(): LocalFoodRecipeSnapshotV1 = copy(
    modifierIds = routingList(modifierIds),
    ingredients = routingList(ingredients),
    routes = routingList(routes),
    sources = routingList(sources),
)

private fun <T> routingList(source: List<T>): List<T> = object : AbstractList<T>() {
    private val entries = source.toList()
    override val size: Int get() = entries.size
    override fun get(index: Int): T = entries[index]
}

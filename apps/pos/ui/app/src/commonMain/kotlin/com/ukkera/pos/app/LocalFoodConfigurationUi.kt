package com.ukkera.pos.app

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.*

enum class LocalFoodConfigurationSourceKind { SKU, RECIPE, STATION, WAREHOUSE, ROUTE }
enum class LocalFoodConfigurationStationMode { DISPLAY, PAPER }
enum class LocalFoodConfigurationActivity { RESTAURANT, CAFE }
enum class LocalFoodConfigurationChannel { DINE_IN, COUNTER, TAKEAWAY, DELIVERY }

data class LocalFoodConfigurationLabel(val id: DomainId, val revision: Revision, val storageRevision: Revision,
    val names: LocalText, val active: Boolean)
data class LocalFoodConfigurationSourcePage(val kind: LocalFoodConfigurationSourceKind,
    val labels: List<LocalFoodConfigurationLabel>, val nextAfter: DomainId?) {
    init { require(labels.size <= 20 && labels.map { it.id }.distinct().size == labels.size) }
}
data class LocalFoodSkuSource(val id: DomainId, val skuRevision: Revision, val storageRevision: Revision,
    val productId: DomainId, val baseUnitId: DomainId, val quantityScale: Int, val names: LocalText,
    val tracking: LocalCompanyStockTracking?, val fulfillment: LocalCompanySkuFulfillment?,
    val active: Boolean, val canSell: Boolean, val canPurchase: Boolean)
data class LocalFoodRecipeSource(val id: DomainId, val revision: Revision, val storageRevision: Revision,
    val outputSkuId: DomainId, val names: LocalText, val routeKeys: List<DomainId>, val active: Boolean)
data class LocalFoodStationSource(val id: DomainId, val storageRevision: Revision, val names: LocalText,
    val mode: LocalFoodConfigurationStationMode, val active: Boolean)
data class LocalFoodWarehouseSource(val id: DomainId, val storageRevision: Revision, val names: LocalText, val active: Boolean)
data class LocalFoodRouteTarget(val routeKey: DomainId, val station: LocalFoodStationSource)
data class LocalFoodRouteSource(val id: DomainId, val storageRevision: Revision, val recipeId: DomainId,
    val recipeRevision: Revision, val activity: LocalFoodConfigurationActivity, val channel: LocalFoodConfigurationChannel,
    val warehouseId: DomainId, val targets: Map<DomainId, DomainId>, val active: Boolean)

sealed interface LocalFoodConfigurationDetail {
    data class Sku(val source: LocalFoodSkuSource) : LocalFoodConfigurationDetail
    data class Recipe(val source: LocalFoodRecipeSource) : LocalFoodConfigurationDetail
    data class Station(val source: LocalFoodStationSource) : LocalFoodConfigurationDetail
    data class Warehouse(val source: LocalFoodWarehouseSource) : LocalFoodConfigurationDetail
    data class Route(val source: LocalFoodRouteSource) : LocalFoodConfigurationDetail
}
sealed interface LocalFoodConfigurationReadResult {
    data class Page(val page: LocalFoodConfigurationSourcePage) : LocalFoodConfigurationReadResult
    /** Absent is a protected actual current ID lookup only, not unused rights/history authority. */
    data class Detail(val detail: LocalFoodConfigurationDetail?) : LocalFoodConfigurationReadResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalFoodConfigurationReadResult
}

data class LocalFoodRecipeIngredientDraft(val componentId: DomainId, val sku: LocalFoodSkuSource, val quantityPerYield: String) {
    init { require(quantityPerYield.length <= 64) }
}
data class LocalFoodRecipeChoiceDraft(val id: DomainId, val names: LocalText, val modifierIds: List<DomainId>,
    val ingredients: List<LocalFoodRecipeIngredientDraft>, val routeKeys: List<DomainId>, val pricingSku: LocalFoodSkuSource) {
    init { require(modifierIds.size <= 16 && ingredients.size in 1..16 && routeKeys.size in 1..4) }
}
/** Unsigned proposal with actual selected source revisions. Only native head approval can sign it. */
data class LocalFoodRecipeDraft(val publicationId: DomainId, val recipeId: DomainId, val outputSku: LocalFoodSkuSource,
    val expectedRecipeRevision: Revision?, val yieldQuantity: String, val portionQuantity: String,
    val choices: List<LocalFoodRecipeChoiceDraft>) {
    init { require(yieldQuantity.length <= 64 && portionQuantity.length <= 64 && choices.size in 1..8) }
}
data class LocalFoodRecipeIngredientPreview(val componentId: DomainId, val skuId: DomainId,
    val skuRevision: Revision, val quantityPerYield: Quantity)
data class LocalFoodRecipeChoicePreview(val id: DomainId, val names: LocalText, val modifierIds: List<DomainId>,
    val ingredients: List<LocalFoodRecipeIngredientPreview>, val routeKeys: List<DomainId>, val pricingSkuId: DomainId)
data class LocalFoodRecipePreview(val publicationId: DomainId, val recipeId: DomainId, val revision: Revision,
    val outputSkuId: DomainId, val outputSkuRevision: Revision, val yieldQuantity: Quantity,
    val portionQuantity: Quantity, val choices: List<LocalFoodRecipeChoicePreview>, val active: Boolean)
class LocalFoodRecipeReview(val action: CompanyBusinessPublicationAction, val preview: LocalFoodRecipePreview) {
    override fun toString(): String = "LocalFoodRecipeReview([REDACTED])"
}
data class LocalFoodRecipeCompleted(val originalActionId: DomainId, val preview: LocalFoodRecipePreview, val rootFingerprint: String)
data class LocalFoodConfigurationStatus(val references: List<CompanyBusinessPublicationReference>, val observedAt: UtcInstant) {
    init { require(references.size <= 256) }
}
sealed interface LocalFoodRecipeResult {
    data class Review(val review: LocalFoodRecipeReview) : LocalFoodRecipeResult
    data class Completed(val publication: LocalFoodRecipeCompleted) : LocalFoodRecipeResult
    data class Unavailable(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalFoodRecipeResult
}
sealed interface LocalFoodConfigurationStatusResult {
    data class Available(val status: LocalFoodConfigurationStatus) : LocalFoodConfigurationStatusResult
    data class Unavailable(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalFoodConfigurationStatusResult
}
data class LocalFoodStationDraft(val operationId: OperationId, val stationId: DomainId, val expectedStorageRevision: Revision?,
    val names: LocalText, val mode: LocalFoodConfigurationStationMode)
data class LocalFoodRouteDraft(val operationId: OperationId, val routeId: DomainId, val expectedStorageRevision: Revision?,
    val recipe: LocalFoodRecipeSource, val activity: LocalFoodConfigurationActivity, val channel: LocalFoodConfigurationChannel,
    val warehouse: LocalFoodWarehouseSource, val targets: List<LocalFoodRouteTarget>) {
    init { require(targets.size in 1..8 && targets.map { it.routeKey }.distinct().size == targets.size) }
}

/** Same actual native company owner and ordinary employee journal. Signing is NOT branch activation. */
interface LocalFoodConfigurationGateway {
    val configured: Boolean
    suspend fun status(): LocalFoodConfigurationStatusResult
    suspend fun sources(kind: LocalFoodConfigurationSourceKind, after: DomainId? = null): LocalFoodConfigurationReadResult
    suspend fun source(kind: LocalFoodConfigurationSourceKind, id: DomainId): LocalFoodConfigurationReadResult
    suspend fun prepare(draft: LocalFoodRecipeDraft): LocalFoodRecipeResult
    suspend fun review(originalActionId: DomainId): LocalFoodRecipeResult
    /** Buffer transfers on all outcomes; never retained in a draft/client/native request. */
    suspend fun approve(review: LocalFoodRecipeReview, headLogin: String, headSecret: CharArray): LocalFoodRecipeResult
    suspend fun stageActivation(originalActionId: DomainId): LocalStageResult
    suspend fun stageStation(draft: LocalFoodStationDraft): LocalStageResult
    suspend fun stageRoute(draft: LocalFoodRouteDraft): LocalStageResult
}
object UnavailableLocalFoodConfiguration : LocalFoodConfigurationGateway {
    override val configured: Boolean = false
    override suspend fun status(): LocalFoodConfigurationStatusResult = LocalFoodConfigurationStatusResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun sources(kind: LocalFoodConfigurationSourceKind, after: DomainId?): LocalFoodConfigurationReadResult = LocalFoodConfigurationReadResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun source(kind: LocalFoodConfigurationSourceKind, id: DomainId): LocalFoodConfigurationReadResult = LocalFoodConfigurationReadResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepare(draft: LocalFoodRecipeDraft): LocalFoodRecipeResult = LocalFoodRecipeResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun review(originalActionId: DomainId): LocalFoodRecipeResult = LocalFoodRecipeResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun approve(review: LocalFoodRecipeReview, headLogin: String, headSecret: CharArray): LocalFoodRecipeResult {
        headSecret.fill('\u0000'); return LocalFoodRecipeResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun stageStation(draft: LocalFoodStationDraft): LocalStageResult = LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun stageRoute(draft: LocalFoodRouteDraft): LocalStageResult = LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
}

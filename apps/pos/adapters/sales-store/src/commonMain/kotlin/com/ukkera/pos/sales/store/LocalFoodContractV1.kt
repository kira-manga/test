package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object LocalFoodProfileV1 {
    public val COMMAND: CommandKey = CommandKey("sales.food.write", 1)
    public val QUERY: ReadKey = ReadKey("sales.food.read", 1)
    public const val ORDER: String = "sales.food.order"
    public const val PORTION: String = "sales.food.portion"
    public const val PRICE_BATCH: String = "sales.food.price-batch"
    public const val OWNER_INDEX: String = "sales.food.owner-index"
    public const val STATION_INDEX: String = "sales.food.station-index"
    public const val BEFORE: String = "sales.food.before"
    public const val EVENT: String = "sales.food.event"
    public const val CLAIM: String = "sales.food.identity"
    public const val DELIVERY_JOB: String = "local.kitchen.deliver-v1"
    public const val MAX_PORTIONS_PER_COMMAND: Int = 8
    public const val MAX_ORDER_PORTIONS: Int = 32
    public const val MAX_ACTIVE_INDEX: Int = 128
    public const val MAX_SIGNED_RECIPE_BYTES: Int = 16_384
    public const val MAX_RECIPE_DESCRIPTION: Int = 323
    public val kinds: Set<String> = setOf(ORDER, PORTION, PRICE_BATCH, OWNER_INDEX, STATION_INDEX, BEFORE, EVENT)
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun claim(kind: String, id: DomainId): ClaimKey = ClaimKey(CLAIM, "$kind/${id.value}")
    public fun schema(kind: String): String = "$kind/v1"
    public fun recipeSnapshotId(portionId: DomainId, publicationId: DomainId, routeVersionId: DomainId): DomainId =
        foodId("recipe-snapshot", "${portionId.value}/${publicationId.value}/${routeVersionId.value}")
}

/** Explicit service labels; no customer contact/address or payment information enters station projections. */
@Serializable
public data class LocalFoodServiceV1(
    public val channel: LocalSalesChannel,
    public val reference: String,
    public val seatNote: String = "",
) {
    init {
        require(channel in setOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY,
            LocalSalesChannel.DELIVERY))
        foodText(reference, 80, false); foodText(seatNote, 160)
    }
}

/** Each ID is one independently cancellable configured serving, not an untracked fractional sub-batch. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodPortionSelectionV1(
    public val portionId: DomainId,
    public val itemId: DomainId,
    public val recipeId: DomainId,
    public val expectedRecipeRevision: Revision,
    public val choiceId: DomainId,
    public val modifierIds: List<DomainId>,
    public val quantity: Quantity,
    public val itemNote: String = "",
    /** An explicit commercial identity is anchored by a member of this same new batch. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val lineId: DomainId? = null,
    /** Line-specific service note, separate from the check's service.seatNote. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val seatNote: String = "",
) {
    /** Legacy selections retain their original one-portion commercial identity. */
    public val commercialLineId: DomainId get() = lineId ?: portionId
    init {
        require(quantity.scaled > 0 && modifierIds.size <= 16 && modifierIds.distinct().size == modifierIds.size)
        foodText(itemNote, 240); foodText(seatNote, 160)
    }
}

/** Exact collection DOMAIN revision observed with the corresponding order storage revision. */
@Serializable
public data class LocalFoodHandoffFinancialExpectationV1(public val collectionId: DomainId,
    public val expectedCollectionRevision: Revision)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public sealed interface LocalFoodRequestV1 {
    @Serializable @SerialName("CREATE")
    public data class Create(public val orderId: DomainId, public val activity: LocalSalesActivity,
        public val service: LocalFoodServiceV1, public val expectedCatalogRevision: Revision,
        public val portions: List<LocalFoodPortionSelectionV1>) : LocalFoodRequestV1 {
        init { require(service.channel != LocalSalesChannel.DELIVERY) { "food.create.delivery_draft_required" } }
    }
    @Serializable @SerialName("ADD")
    public data class Add(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val expectedCatalogRevision: Revision, public val portions: List<LocalFoodPortionSelectionV1>) : LocalFoodRequestV1
    @Serializable @SerialName("SEND")
    public data class Send(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val portionIds: List<DomainId>) : LocalFoodRequestV1
    @Serializable @SerialName("STATION")
    public data class Station(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val portionId: DomainId, public val expectedPortionRevision: Revision,
        public val stationId: DomainId, public val action: LocalFoodStationActionV1,
        public val confirmationId: DomainId? = null,
        public val returnDisposition: FoodIngredientReturnDisposition? = null) : LocalFoodRequestV1
    @Serializable @SerialName("SERVE")
    public data class Serve(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val portionId: DomainId, public val expectedPortionRevision: Revision) : LocalFoodRequestV1
    @Serializable @SerialName("CANCEL")
    public data class Cancel(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val portionId: DomainId, public val expectedPortionRevision: Revision, public val reasonId: DomainId) : LocalFoodRequestV1
    @Serializable @SerialName("DISCARD_STARTED")
    public data class DiscardStarted(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val portionId: DomainId, public val expectedPortionRevision: Revision, public val stationId: DomainId) : LocalFoodRequestV1
    @Serializable @SerialName("OFFER_HANDOFF")
    public data class OfferHandoff(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val handoffId: DomainId, public val recipientId: DomainId,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val financial: LocalFoodHandoffFinancialExpectationV1? = null) : LocalFoodRequestV1
    @Serializable @SerialName("ACCEPT_HANDOFF")
    public data class AcceptHandoff(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val handoffId: DomainId,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val financial: LocalFoodHandoffFinancialExpectationV1? = null) : LocalFoodRequestV1
    @Serializable @SerialName("CANCEL_HANDOFF")
    public data class CancelHandoff(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val handoffId: DomainId,
        @EncodeDefault(EncodeDefault.Mode.NEVER) public val financial: LocalFoodHandoffFinancialExpectationV1? = null) : LocalFoodRequestV1

    /** Service labels only: no channel, recipe, commercial or fulfillment changes. */
    @Serializable @SerialName("EDIT_SERVICE_DETAILS")
    public data class EditServiceDetails(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val reference: String, public val seatNote: String = "") : LocalFoodRequestV1 {
        init { foodText(reference, 80, false); foodText(seatNote, 160) }
    }
}

@Serializable
public enum class LocalFoodStationActionV1 { START, READY, CONFIRM_UNSTARTED }
@Serializable
public data class LocalFoodResultV1(public val code: String, public val orderId: DomainId?,
    public val orderRevision: Revision?, public val portions: List<LocalFoodPortionRevisionV1> = emptyList())
@Serializable
public data class LocalFoodPortionRevisionV1(public val portionId: DomainId, public val revision: Revision,
    public val status: FoodPreparationStatus, public val issueId: DomainId?)

/** Immutable recipe evidence. These are stored source facts, not accepted command DTOs or an authority token. */
@Serializable
public data class LocalFoodIngredientV1(
    public val componentId: DomainId, public val skuId: DomainId, public val skuRevision: Revision,
    public val quantityPerYield: Quantity, public val requiredBaseQuantity: Quantity,
)
@Serializable
public data class LocalFoodStationRouteV1(public val routeKey: DomainId, public val stationId: DomainId,
    public val stationRevision: Revision, public val mode: FoodStationDeliveryMode)
@Serializable
public data class LocalFoodSourceReferenceV1(
    public val kind: String, public val id: DomainId, public val revision: Revision,
    public val digest: PayloadDigest, public val authorizedBy: DomainId,
    public val signingKeyId: DomainId? = null, public val operationId: OperationId? = null,
)
@Serializable
public data class LocalFoodRecipeSnapshotV1(
    public val snapshotId: DomainId,
    public val portionId: DomainId, public val routeVersionId: DomainId,
    public val activity: LocalSalesActivity, public val channel: LocalSalesChannel,
    public val recipeId: DomainId, public val recipeRevision: Revision, public val publicationId: DomainId,
    public val outputSkuId: DomainId, public val outputSkuRevision: Revision,
    public val choiceId: DomainId, public val modifierIds: List<DomainId>,
    public val description: String, public val yieldQuantity: Quantity, public val portionQuantity: Quantity,
    public val pricingSkuId: DomainId,
    public val ingredients: List<LocalFoodIngredientV1>,
    public val warehouseId: DomainId, public val routes: List<LocalFoodStationRouteV1>,
    /** Exact canonical signed COMPANY recipe publication; no credentials or mutable caller recipe. */
    public val signedPublication: String,
    public val sources: List<LocalFoodSourceReferenceV1>,
) {
    init {
        foodText(description, LocalFoodProfileV1.MAX_RECIPE_DESCRIPTION, false)
        require(snapshotId == LocalFoodProfileV1.recipeSnapshotId(portionId, publicationId, routeVersionId))
        require(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) &&
            channel in setOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY,
                LocalSalesChannel.DELIVERY))
        require(ingredients.size in 1..16 && ingredients.map { it.componentId }.distinct().size == ingredients.size)
        require(routes.size in 1..4 && routes.map { it.stationId }.distinct().size == routes.size &&
            routes.map { it.routeKey }.distinct().size == routes.size)
        require(sources.size in 1..32 && sources.map { it.kind to it.id }.distinct().size == sources.size)
        require(signedPublication.isNotEmpty() && signedPublication.length <= LocalFoodProfileV1.MAX_SIGNED_RECIPE_BYTES &&
            signedPublication.encodeToByteArray(throwOnInvalidSequence = true).size <= LocalFoodProfileV1.MAX_SIGNED_RECIPE_BYTES &&
            modifierIds.size <= 16 && modifierIds.distinct().size == modifierIds.size)
        require(yieldQuantity.unitId == portionQuantity.unitId && yieldQuantity.scale == portionQuantity.scale &&
            yieldQuantity.scaled > 0 && portionQuantity.scaled > 0)
        for (ingredient in ingredients) {
            require(ingredient.quantityPerYield.scaled > 0 && ingredient.requiredBaseQuantity.scaled > 0 &&
                ingredient.requiredBaseQuantity.unitId == ingredient.quantityPerYield.unitId &&
                ingredient.requiredBaseQuantity.scale == ingredient.quantityPerYield.scale)
            require((ingredient.quantityPerYield.toFraction() * portionQuantity.toFraction() / yieldQuantity.toFraction())
                .toScaledLongExact(ingredient.requiredBaseQuantity.scale) == ingredient.requiredBaseQuantity.scaled)
        }
    }
}

public class LocalFoodQuotedBatchV1(public val quote: PricingSnapshot, snapshots: List<LocalFoodRecipeSnapshotV1>,
    public val priceSources: List<LocalSalesPriceSourceV2>) {
    public val snapshots: List<LocalFoodRecipeSnapshotV1> = snapshots.toList()
}

/** Only the installed catalog adapter implements this. No source is accepted from an RPC request. */
public interface TransactionFoodRecipeSourceV1 {
    /** Advisory pricing only: capture authenticated current selection facts in this loan,
     * without requiring fulfillment routes. The returned projection must not retain the view. */
    public suspend fun prepareSelectionPreview(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodSelectionPreviewV1?
    public suspend fun prepare(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeBatchV1?
    /** Current send rechecks activated recipe/routing identity, without repricing an already saved line. */
    public suspend fun currentForSend(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Boolean
    /** Installed execution support, not configured routing or caller capability. A positive
     * binding must cover this exact work on the genuine current Master-owned lifetime. */
    public suspend fun prepareKitchenExecution(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, snapshots: List<LocalFoodRecipeSnapshotV1>): PreparedMutationAdmissionRestriction?
    /** Checks retained signed publication and immutable configuration versions, not today's replacement recipe. */
    public suspend fun requireHistorical(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1)
}
/** Immutable preparation for an advisory price, not a saved recipe or fulfillment promise. */
public interface PreparedFoodSelectionPreviewV1 {
    public val scope: BranchScope
    public fun quote(snapshotId: DomainId, observedAt: UtcInstant): PricingSnapshot
}

public interface PreparedFoodRecipeBatchV1 {
    public val scope: BranchScope
    public val recoveryResources: Set<RecoveryResourceKey>
    public fun quote(batchId: DomainId, at: UtcInstant): LocalFoodQuotedBatchV1
}

public object LocalFoodCodecV1 {
    public fun encode(request: LocalFoodRequestV1): StoredBytes {
        when (request) {
            is LocalFoodRequestV1.Create -> {
                require(request.activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE)); selections(request.portions)
            }
            is LocalFoodRequestV1.Add -> selections(request.portions)
            is LocalFoodRequestV1.Send -> require(request.portionIds.size in 1..LocalFoodProfileV1.MAX_PORTIONS_PER_COMMAND &&
                request.portionIds.distinct().size == request.portionIds.size)
            is LocalFoodRequestV1.Station -> require(if (request.action == LocalFoodStationActionV1.CONFIRM_UNSTARTED)
                request.confirmationId != null && request.returnDisposition != null else request.confirmationId == null && request.returnDisposition == null)
            else -> Unit
        }
        return localSalesEncode(request, LocalFoodRequestV1.serializer(), 16_384)
    }
    public fun decode(bytes: StoredBytes): LocalFoodRequestV1 = localSalesDecode(bytes, LocalFoodRequestV1.serializer(), 16_384)
    public fun normalize(bytes: StoredBytes): StoredBytes = encode(decode(bytes))
    public fun result(bytes: StoredBytes): LocalFoodResultV1 = localSalesDecode(bytes, LocalFoodResultV1.serializer())
    public fun command(operationId: OperationId, request: LocalFoodRequestV1): MutationRequest =
        MutationRequest(operationId, LocalFoodProfileV1.COMMAND, encode(request))
    private fun selections(portions: List<LocalFoodPortionSelectionV1>) {
        LocalFoodSelectionGroups.group(portions)
    }
}

internal fun foodText(value: String, maximum: Int, empty: Boolean = true) {
    require(value.length <= maximum && (empty || value.isNotBlank()) && value.none { it.code < 0x20 || it.code == 0x7f })
}
internal fun foodId(kind: String, value: String): DomainId {
    val digest = sha256("ukkera.local-food/v1/$kind/$value".encodeToByteArray()).value
    return DomainId("${digest.substring(0, 8)}-${digest.substring(8, 12)}-${digest.substring(12, 16)}-${digest.substring(16, 20)}-${digest.substring(20, 32)}")
}

internal fun LocalFoodRequestV1.foodHandoffFinancialV1(): LocalFoodHandoffFinancialExpectationV1? = when (this) {
    is LocalFoodRequestV1.OfferHandoff -> financial
    is LocalFoodRequestV1.AcceptHandoff -> financial
    is LocalFoodRequestV1.CancelHandoff -> financial
    else -> null
}

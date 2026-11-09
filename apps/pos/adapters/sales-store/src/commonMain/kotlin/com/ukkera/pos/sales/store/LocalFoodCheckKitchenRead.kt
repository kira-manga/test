package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.FoodCurrentUnpaidPayable
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Diagnostic only: none of these values grants fresh kitchen or payment authority. */
@Serializable
public enum class LocalFoodCheckKitchenStatusV1 { UNKNOWN, UNAVAILABLE, NO_UNSENT_WORK }

/** Whole-order work from the same stored cut as the accompanying owner detail. */
@Serializable
public data class LocalFoodCheckKitchenObservationV1(
    public val orderId: DomainId,
    public val orderStorageRevision: Revision,
    public val unsentPortionCount: Int,
    public val status: LocalFoodCheckKitchenStatusV1,
) {
    init {
        require(orderStorageRevision.value >= 1 && unsentPortionCount in 0..LocalFoodProfileV1.MAX_ORDER_PORTIONS)
        require(status != LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK || unsentPortionCount == 0)
    }
}

/** Private delivery detail from exactly the accompanying current owned order cut. */
@Serializable
public data class LocalFoodDeliveryObservationV1(
    public val orderId: DomainId,
    public val orderStorageRevision: Revision,
    public val details: LocalFoodDeliveryDetailsV1,
) {
    init { require(orderStorageRevision.value >= 1) }

    override fun toString(): String = "LocalFoodDeliveryObservationV1([REDACTED])"
}

/** Additive schema; the legacy food result and its strict decoder keep their exact fields. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodCheckKitchenResultV1(
    public val food: LocalFoodReadResultV1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val kitchen: LocalFoodCheckKitchenObservationV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val delivery: LocalFoodDeliveryObservationV1? = null,
) {
    init { requireFoodCheckKitchenBinding(food, kitchen, delivery) }
}

/** Same owned order cut, with an explicit current amount separate from its historical quote. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodCheckKitchenResultV2(
    public val food: LocalFoodReadResultV1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val kitchen: LocalFoodCheckKitchenObservationV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val delivery: LocalFoodDeliveryObservationV1? = null,
    /** Mandatory even when unavailable; null never means zero or permission to collect. */
    public val currentUnpaidPayable: Money?,
) {
    init {
        requireFoodCheckKitchenBinding(food, kitchen, delivery)
        currentUnpaidPayable?.let { amount ->
            require(food.unavailable == null && food.orders.isEmpty() && food.tasks.isEmpty())
            val detail = requireNotNull(food.detail)
            val summary = detail.summary
            require(summary.portionCount in 0..LocalFoodProfileV1.MAX_ORDER_PORTIONS &&
                detail.portions.size <= summary.portionCount &&
                detail.portions.map { it.portionId }.distinct().size == detail.portions.size)
            require(!summary.workClosed && summary.financialCollectionId == null &&
                detail.collectionState == "UNPAID_NO_COLLECTION" && detail.financialHandoff == null &&
                !detail.chargeResolutionRequired)
            require(detail.originalQuotedTotal.currency == Currency.EGP && detail.originalQuotedTotal.minor >= 0 &&
                amount.currency == detail.originalQuotedTotal.currency && amount.minor >= 0 &&
                amount.minor <= detail.originalQuotedTotal.minor)
            require(detail.portions.all { it.portionId == it.preparation.portionId &&
                FoodCurrentUnpaidPayable.isNeverSent(it.preparation) })
            require(summary.portionCount != 0 || detail.originalQuotedTotal.minor == 0L && amount.minor == 0L && food.nextAfter == null)
        }
    }
}

/** Version3 admits ordinary prepared unpaid work without changing the older validation. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodCheckKitchenResultV3(
    public val food: LocalFoodReadResultV1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val kitchen: LocalFoodCheckKitchenObservationV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val delivery: LocalFoodDeliveryObservationV1? = null,
    /** Mandatory even when unavailable; preparation and service do not imply payment. */
    public val currentUnpaidPayable: Money?,
) {
    init {
        requireFoodCheckKitchenBinding(food, kitchen, delivery)
        currentUnpaidPayable?.let { amount ->
            require(food.unavailable == null && food.orders.isEmpty() && food.tasks.isEmpty())
            val detail = requireNotNull(food.detail)
            val summary = detail.summary
            require(summary.portionCount in 0..LocalFoodProfileV1.MAX_ORDER_PORTIONS &&
                detail.portions.size <= summary.portionCount &&
                detail.portions.map { it.portionId }.distinct().size == detail.portions.size)
            require(!summary.workClosed && summary.financialCollectionId == null &&
                detail.collectionState == "UNPAID_NO_COLLECTION" && detail.financialHandoff == null &&
                !detail.chargeResolutionRequired)
            require(detail.originalQuotedTotal.currency == Currency.EGP && detail.originalQuotedTotal.minor >= 0 &&
                amount.currency == detail.originalQuotedTotal.currency && amount.minor >= 0 &&
                amount.minor <= detail.originalQuotedTotal.minor)
            require(detail.portions.all { it.portionId == it.preparation.portionId &&
                FoodCurrentUnpaidPayable.isOrdinaryUnpaid(it.preparation) &&
                (it.preparation.fulfillment != FoodFulfillmentStatus.SERVED || summary.service.channel == LocalSalesChannel.DINE_IN) })
            require(summary.portionCount != 0 || detail.originalQuotedTotal.minor == 0L && amount.minor == 0L && food.nextAfter == null)
        }
    }
}

/** All versioned envelopes use the original field/binding validation. */
private fun requireFoodCheckKitchenBinding(food: LocalFoodReadResultV1,
    kitchen: LocalFoodCheckKitchenObservationV1?, delivery: LocalFoodDeliveryObservationV1?) {
    val detailSummary = food.detail?.summary
    require((detailSummary?.service?.channel == LocalSalesChannel.DELIVERY) == (delivery != null))
    delivery?.let { observation ->
        require(food.unavailable == null && food.orders.isEmpty() && food.tasks.isEmpty())
        val summary = requireNotNull(detailSummary)
        require(observation.orderId == summary.orderId && observation.orderStorageRevision == summary.revision)
    }
    kitchen?.let { observation ->
        require(food.unavailable == null && food.orders.isEmpty() && food.tasks.isEmpty())
        val summary = requireNotNull(food.detail).summary
        require(observation.orderId == summary.orderId && observation.orderStorageRevision == summary.revision &&
            summary.portionCount in 0..LocalFoodProfileV1.MAX_ORDER_PORTIONS &&
            observation.unsentPortionCount <= summary.portionCount)
        require(observation.unsentPortionCount > 0 || summary.portionCount == 0 ||
            observation.status == LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
    }
}

public object LocalFoodCheckKitchenCodecV1 {
    public val QUERY: ReadKey = ReadKey("sales.food.check-kitchen.read", 1)

    /** Retains the existing ORDER selector representation and its 1..8 page bound. */
    public fun encode(query: LocalFoodQueryV1.Order): StoredBytes = LocalFoodReadCodecV1.encode(query)
    public fun read(query: LocalFoodQueryV1.Order): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun result(bytes: StoredBytes): LocalFoodCheckKitchenResultV1 =
        localSalesDecode(bytes, LocalFoodCheckKitchenResultV1.serializer())

    internal fun query(bytes: StoredBytes): LocalFoodQueryV1.Order {
        val query = localSalesDecode(bytes, LocalFoodQueryV1.serializer(), 2_048)
        require(query is LocalFoodQueryV1.Order) { "food.check_kitchen.order_required" }
        return query
    }
}

public object LocalFoodCheckKitchenCodecV2 {
    public val QUERY: ReadKey = ReadKey("sales.food.check-kitchen.read", 2)

    public fun encode(query: LocalFoodQueryV1.Order): StoredBytes = LocalFoodReadCodecV1.encode(query)
    public fun read(query: LocalFoodQueryV1.Order): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun result(bytes: StoredBytes): LocalFoodCheckKitchenResultV2 =
        localSalesDecode(bytes, LocalFoodCheckKitchenResultV2.serializer())

    internal fun query(bytes: StoredBytes): LocalFoodQueryV1.Order = LocalFoodCheckKitchenCodecV1.query(bytes)
}

public object LocalFoodCheckKitchenCodecV3 {
    public val QUERY: ReadKey = ReadKey("sales.food.check-kitchen.read", 3)

    public fun encode(query: LocalFoodQueryV1.Order): StoredBytes = LocalFoodReadCodecV1.encode(query)
    public fun read(query: LocalFoodQueryV1.Order): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun result(bytes: StoredBytes): LocalFoodCheckKitchenResultV3 =
        localSalesDecode(bytes, LocalFoodCheckKitchenResultV3.serializer())

    internal fun query(bytes: StoredBytes): LocalFoodQueryV1.Order = LocalFoodCheckKitchenCodecV1.query(bytes)
}

/** Reuses the exact owner read, permissions, history closure and request-wide budget. */
public class LocalFoodCheckKitchenReadHandler(
    recipes: TransactionFoodRecipeSourceV1,
    stock: LocalStockRepository,
    quotations: TransactionFoodRecipeQuotationVerificationSourceV1? = null,
) : MasterReadHandler {
    private val foodReads = LocalFoodReadHandler(recipes, stock, quotations)
    override val key: ReadKey = LocalFoodCheckKitchenCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes =
        LocalFoodCheckKitchenCodecV1.encode(LocalFoodCheckKitchenCodecV1.query(input))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        (profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS || profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL) &&
            normalize(normalized) == normalized
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead =
        foodReads.prepareKitchenCheck(LocalFoodCheckKitchenCodecV1.query(normalized), view, context)
}

/** Version2 shares the owner pipeline, source closure and one request-wide budget with version1. */
public class LocalFoodCheckKitchenReadHandlerV2(
    recipes: TransactionFoodRecipeSourceV1,
    stock: LocalStockRepository,
    quotations: TransactionFoodRecipeQuotationVerificationSourceV1? = null,
) : MasterReadHandler {
    private val foodReads = LocalFoodReadHandler(recipes, stock, quotations)
    override val key: ReadKey = LocalFoodCheckKitchenCodecV2.QUERY
    override fun normalize(input: StoredBytes): StoredBytes =
        LocalFoodCheckKitchenCodecV2.encode(LocalFoodCheckKitchenCodecV2.query(input))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        (profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS || profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL) &&
            normalize(normalized) == normalized
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead =
        foodReads.prepareKitchenCheckV2(LocalFoodCheckKitchenCodecV2.query(normalized), view, context)
}

/** Version3 closes complete ordinary prepared work on the same protected owner pipeline. */
public class LocalFoodCheckKitchenReadHandlerV3(
    recipes: TransactionFoodRecipeSourceV1,
    stock: LocalStockRepository,
    quotations: TransactionFoodRecipeQuotationVerificationSourceV1? = null,
) : MasterReadHandler {
    private val foodReads = LocalFoodReadHandler(recipes, stock, quotations)
    override val key: ReadKey = LocalFoodCheckKitchenCodecV3.QUERY
    override fun normalize(input: StoredBytes): StoredBytes =
        LocalFoodCheckKitchenCodecV3.encode(LocalFoodCheckKitchenCodecV3.query(input))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        (profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS || profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL) &&
            normalize(normalized) == normalized
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead =
        foodReads.prepareKitchenCheckV3(LocalFoodCheckKitchenCodecV3.query(normalized), view, context)
}

/** No page/caller capability participates in this observation, and no source absence is caught. */
internal suspend fun foodCheckKitchenObservation(
    view: CommandReadView,
    recipes: TransactionFoodRecipeSourceV1,
    order: FoodOrderCut,
    portions: List<FoodBodyV1.Portion>,
): LocalFoodCheckKitchenObservationV1 {
    check(portions.map { it.selection.portionId } == order.value.portionIds)
    val unsent = portions.filter { it.preparation.status == FoodPreparationStatus.NOT_SENT }
    return foodKitchenObservation(view, recipes, order, unsent.map { it.recipe }, 0)
}

/** Complete stored-order work, after the owner read closes every quoted source/pair/producer. */
internal suspend fun foodCheckStoredKitchenObservation(
    view: CommandReadView,
    recipes: TransactionFoodRecipeSourceV1,
    order: FoodOrderCut,
    portions: List<FoodRow>,
    originalQuotationsById: Map<DomainId, FoodQuotationPortionCut>,
): LocalFoodCheckKitchenObservationV1 {
    check(portions.map { foodStoredPortionSelection(it.envelope).portionId } == order.value.portionIds)
    val unsent = portions.filter { foodStoredPortionPreparation(it.envelope).status == FoodPreparationStatus.NOT_SENT }
    val routingSource = recipes as? TransactionFoodRecipeRoutingVerificationSource
    // A routing projection alone is not an installed atomic binding or native-history path.
    // Full original cuts were authenticated by the surrounding owner read on this same loan.
    val bindingInstalled = unsent.any { it.envelope.body is FoodBodyV1.QuotedPortion } &&
        recipes is TransactionFoodStockSource && routingSource != null &&
        commerceRepository(view).hasRelationalProducer(StockStreamingCommandsV2.FOOD)
    val snapshots = mutableListOf<LocalFoodRecipeSnapshotV1>()
    var unbound = 0
    for (row in unsent) {
        when (val body = row.envelope.body) {
            is FoodBodyV1.Portion -> snapshots += body.recipe
            is FoodBodyV1.QuotedPortion -> {
                val original = checkNotNull(originalQuotationsById[row.envelope.id]) {
                    "food.quotation.closed_source_required"
                }
                check(original.portion.row == row.row && original.portion.envelope == row.envelope)
                val quotation = (original.quotation.envelope.body as FoodBodyV1.RecipeQuotation).quotation
                val prepared = if (bindingInstalled)
                    foodPrepareQuotationRouting(view, checkNotNull(routingSource), quotation)
                else null
                if (prepared == null) unbound++ else snapshots += prepared.snapshot
            }
            else -> error("food.portion.body_required")
        }
    }
    return foodKitchenObservation(view, recipes, order, snapshots, unbound)
}

private suspend fun foodKitchenObservation(view: CommandReadView, recipes: TransactionFoodRecipeSourceV1,
    order: FoodOrderCut, routedUnsent: List<LocalFoodRecipeSnapshotV1>,
    unboundUnsent: Int): LocalFoodCheckKitchenObservationV1 {
    val unsentCount = routedUnsent.size + unboundUnsent
    val status = foodKitchenWorkStatus(unsentCount, unboundUnsent, order.value.service.channel) {
        recipes.prepareKitchenExecution(view, order.value.activity, order.value.service, routedUnsent)
    }
    return LocalFoodCheckKitchenObservationV1(order.order.envelope.id, order.order.envelope.revision, unsentCount, status)
}

/** Work classification after the owner pipeline establishes the complete protected cut.
 * Scalar inputs are not proof of ownership or permission; only nonempty routed work needs
 * the actual execution preparation supplied by that same pipeline. */
internal suspend fun foodKitchenWorkStatus(unsentCount: Int, unboundUnsent: Int,
    channel: LocalSalesChannel,
    prepareExecution: suspend () -> PreparedMutationAdmissionRestriction?): LocalFoodCheckKitchenStatusV1 {
    require(unsentCount in 0..LocalFoodProfileV1.MAX_ORDER_PORTIONS && unboundUnsent in 0..unsentCount)
    return if (unsentCount == 0) {
        // An actual authorized empty check also has no work. Do not query sending support
        // or imply capability for later additions; collection/recovery remains separate.
        LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK
    } else if (unboundUnsent > 0 || channel == LocalSalesChannel.DELIVERY) {
        // Exact configured routes or a future positive kitchen source do not implement dispatch/fulfillment.
        // Unbound work requires a real later binding path, including work beyond the current page.
        LocalFoodCheckKitchenStatusV1.UNAVAILABLE
    } else {
        // Nonempty work still requires actual installed execution support.
        // A null restriction proves no positive capability or permission.
        when (prepareExecution()) {
            PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE -> LocalFoodCheckKitchenStatusV1.UNAVAILABLE
            null -> LocalFoodCheckKitchenStatusV1.UNKNOWN
        }
    }
}

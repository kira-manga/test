package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockHydrationCapacityUnavailable
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalFoodQueryV1 {
    @Serializable @SerialName("OWN")
    public data class Own(public val after: DomainId? = null, public val limit: Int = 20) : LocalFoodQueryV1
    @Serializable @SerialName("ORDER")
    public data class Order(public val orderId: DomainId, public val afterPortionId: DomainId? = null,
        public val limit: Int = 8) : LocalFoodQueryV1
    @Serializable @SerialName("HANDOFF")
    public data class Handoff(public val orderId: DomainId) : LocalFoodQueryV1
    @Serializable @SerialName("STATION")
    public data class Station(public val stationId: DomainId, public val afterTaskId: DomainId? = null, public val limit: Int = 16) : LocalFoodQueryV1
    @Serializable @SerialName("TASK")
    public data class Task(public val stationId: DomainId, public val taskId: DomainId) : LocalFoodQueryV1
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodOrderSummaryV1(public val orderId: DomainId, public val ownerId: DomainId, public val revision: Revision,
    public val activity: LocalSalesActivity, public val service: LocalFoodServiceV1, public val portionCount: Int,
    public val handoffId: DomainId?, public val intendedRecipientId: DomainId?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val financialCollectionId: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val workClosed: Boolean = false)
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodPortionViewV1(public val portionId: DomainId, public val description: String, public val quantity: Quantity,
    public val modifierIds: List<DomainId>, public val itemNote: String, public val preparation: FoodPreparationState,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val lineId: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val seatNote: String = "") {
    public val commercialLineId: DomainId get() = lineId ?: portionId
    init { foodText(seatNote, 160) }
}
@Serializable
public data class LocalFoodOrderDetailV1(public val summary: LocalFoodOrderSummaryV1, public val portions: List<LocalFoodPortionViewV1>,
    /** Historical quote only. Cancellation/waiver, collected money and future payable must remain separate. */
    public val originalQuotedTotal: Money, public val chargeResolutionRequired: Boolean,
    public val collectionState: String = "UNPAID_NO_COLLECTION", public val financialHandoff: LocalFoodHandoffFinancialV1? = null)
/** Closed KDS shape: no owner/cash/customer contacts/prices/costs/recipe stock allocation or source signatures. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodStationTaskViewV1(public val orderId: DomainId, public val orderRevision: Revision,
    public val portionId: DomainId, public val portionRevision: Revision, public val taskId: DomainId, public val stationId: DomainId,
    public val description: String, public val quantity: Quantity, public val itemNote: String, public val serviceReference: String,
    public val taskStatus: FoodStationStatus, public val preparationStatus: FoodPreparationStatus, public val deliveryMode: FoodStationDeliveryMode,
    /** An intent is not an acknowledgment from a printer or permission to repeat preparation. */
    public val deliveryEvidence: String = "DURABLE_INTENT_NOT_PHYSICAL_ACK",
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val seatNote: String = "") {
    init { foodText(seatNote, 160) }
}
@Serializable
public enum class LocalFoodReadUnavailableV2 { TARGET_UNAVAILABLE, CAPACITY_REQUIRED }
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class LocalFoodReadResultV1(public val orders: List<LocalFoodOrderSummaryV1> = emptyList(),
    public val detail: LocalFoodOrderDetailV1? = null, public val tasks: List<LocalFoodStationTaskViewV1> = emptyList(), public val nextAfter: DomainId? = null,
    /** An unavailable read is NOT an empty ledger, a partial page, or permission to retry a send. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val unavailable: LocalFoodReadUnavailableV2? = null) {
    init { require(unavailable == null || orders.isEmpty() && detail == null && tasks.isEmpty() && nextAfter == null) }
}

public object LocalFoodReadCodecV1 {
    public fun encode(query: LocalFoodQueryV1): StoredBytes {
        when (query) {
            is LocalFoodQueryV1.Own -> require(query.limit in 1..20)
            is LocalFoodQueryV1.Order -> require(query.limit in 1..8)
            is LocalFoodQueryV1.Station -> require(query.limit in 1..16)
            else -> Unit
        }
        return localSalesEncode(query, LocalFoodQueryV1.serializer(), 2_048)
    }
    public fun decode(bytes: StoredBytes): LocalFoodQueryV1 = localSalesDecode(bytes, LocalFoodQueryV1.serializer(), 2_048)
        .also { require(encode(it) == bytes) }
    public fun read(query: LocalFoodQueryV1): ReadRequest = ReadRequest(LocalFoodProfileV1.QUERY, encode(query))
    public fun result(bytes: StoredBytes): LocalFoodReadResultV1 = localSalesDecode(bytes, LocalFoodReadResultV1.serializer())
}

/** Output version only; all versions share one protected owner read and its immutable result. */
private enum class FoodReadProjection { LEGACY, CHECK_KITCHEN_V1, CHECK_KITCHEN_V2, CHECK_KITCHEN_V3 }
private class FoodReadProjectionResult(val result: LocalFoodCheckKitchenResultV1, val currentUnpaidPayable: Money? = null)

/** Each read is authorized against CURRENT local protected state in the same master loan. No cache grants. */
public class LocalFoodReadHandler(private val recipes: TransactionFoodRecipeSourceV1, private val stock: LocalStockRepository,
    private val quotations: TransactionFoodRecipeQuotationVerificationSourceV1? = null) : MasterReadHandler {
    override val key: ReadKey = LocalFoodProfileV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodReadCodecV1.encode(localSalesDecode(input, LocalFoodQueryV1.serializer(), 2_048))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        val query = LocalFoodReadCodecV1.decode(normalized)
        return when (profile) {
            LocalMasterRpcWorkflowProfile.KITCHEN -> query is LocalFoodQueryV1.Station || query is LocalFoodQueryV1.Task
            LocalMasterRpcWorkflowProfile.FOOD_CHECKS, LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS,
            LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL ->
                query is LocalFoodQueryV1.Own || query is LocalFoodQueryV1.Order
            else -> false
        }
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalFoodQueryV1.serializer(), 2_048)
        val bounded = FoodReadView(commerceLoan(view, stock))
        return when (query) {
            is LocalFoodQueryV1.Own -> ownRead(bounded, context, query)
            is LocalFoodQueryV1.Order -> ownerRead(bounded, context, query.orderId, false, query.afterPortionId, query.limit)
            is LocalFoodQueryV1.Handoff -> ownerRead(bounded, context, query.orderId, true)
            is LocalFoodQueryV1.Station -> stationRead(bounded, context, query.stationId, query.afterTaskId, query.limit, null)
            is LocalFoodQueryV1.Task -> stationRead(bounded, context, query.stationId, null, 1, query.taskId)
        }
    }

    internal suspend fun prepareKitchenCheck(query: LocalFoodQueryV1.Order, view: CommandReadView,
        context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == LocalFoodCheckKitchenCodecV1.QUERY)
        return ownerRead(FoodReadView(commerceLoan(view, stock)), context, query.orderId, false,
            query.afterPortionId, query.limit, projection = FoodReadProjection.CHECK_KITCHEN_V1)
    }

    internal suspend fun prepareKitchenCheckV2(query: LocalFoodQueryV1.Order, view: CommandReadView,
        context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == LocalFoodCheckKitchenCodecV2.QUERY)
        return ownerRead(FoodReadView(commerceLoan(view, stock)), context, query.orderId, false,
            query.afterPortionId, query.limit, projection = FoodReadProjection.CHECK_KITCHEN_V2)
    }

    internal suspend fun prepareKitchenCheckV3(query: LocalFoodQueryV1.Order, view: CommandReadView,
        context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == LocalFoodCheckKitchenCodecV3.QUERY)
        return ownerRead(FoodReadView(commerceLoan(view, stock)), context, query.orderId, false,
            query.afterPortionId, query.limit, projection = FoodReadProjection.CHECK_KITCHEN_V3)
    }

    private suspend fun ownRead(view: FoodReadView, context: ReadContext, query: LocalFoodQueryV1.Own): PreparedRead {
        // At most one index and twenty bounded order headers derive ALL exact permissions
        // before any richer producer union can exhaust the request. These are observations,
        // not grants; the kernel still authorizes every requirement before project().
        val header = foodObserveRow(view, LocalFoodProfileV1.OWNER_INDEX, context.actorId)
        val ids = (header?.envelope?.body as? FoodBodyV1.OwnerIndex)?.orderIds.orEmpty().sortedBy { it.value }
            .filter { query.after == null || it.value > query.after.value }
        val chosen = ids.take(query.limit)
        val headers = chosen.map { foodObserveRow(view, LocalFoodProfileV1.ORDER, it) ?: error("food.owner_order_missing") }
        val requirements = if (headers.isEmpty()) listOf(foodReadRequirement(context, SecurityAction.ORDER_READ, context.actorId,
            header?.envelope?.revision ?: Revision(0), context.actorId)) else headers.map {
            val owner = (it.envelope.body as FoodBodyV1.Order).ownerId
            check(owner == context.actorId) { "food.owner_index_mismatch" }
            foodReadRequirement(context, SecurityAction.ORDER_READ, it.envelope.id, it.envelope.revision, owner)
        }
        return foodBoundedRead(context, requirements) {
            foodOpenOwnerIndex(view, context.actorId)
            val orders = chosen.map { foodOrder(view, it).also { row ->
                check(row.value.ownerId == context.actorId)
                requireOwnerQuotationSources(view, row)
            } }
            LocalFoodReadResultV1(orders.map(::foodSummary), nextAfter = if (ids.size > chosen.size) chosen.last() else null)
        }
    }

    private suspend fun ownerRead(view: CommandReadView, context: ReadContext, id: DomainId, handoff: Boolean,
        afterPortionId: DomainId? = null, limit: Int = 8, projection: FoodReadProjection = FoodReadProjection.LEGACY): PreparedRead {
        val found = foodObserveRow(view, LocalFoodProfileV1.ORDER, id)
        val body = found?.envelope?.body as? FoodBodyV1.Order
        val allowed = body != null && if (handoff) body.handoff?.to == context.actorId else body.ownerId == context.actorId
        val action = if (handoff) SecurityAction.ORDER_HANDOFF else SecurityAction.ORDER_READ
        val owner = if (handoff) body?.handoff?.to ?: body?.ownerId ?: context.actorId else body?.ownerId ?: context.actorId
        val requirement = foodReadRequirement(context, action, id, found?.envelope?.revision ?: Revision(0), owner)
        if (!allowed) return foodPreparedProjection(context, listOf(requirement),
            FoodReadProjectionResult(LocalFoodCheckKitchenResultV1(LocalFoodReadResultV1(unavailable = LocalFoodReadUnavailableV2.TARGET_UNAVAILABLE))), projection)
        return foodBoundedProjection(context, listOf(requirement), projection) {
            val order = foodOrder(view, id, includeClosed = !handoff)
            // A prospective handoff gets responsibility summary, not ordinary owner finance.
            if (handoff) {
                for (portionId in order.value.portionIds) {
                    val row = foodObserveRow(view, LocalFoodProfileV1.PORTION, portionId) ?: error("food.portion_missing")
                    checkRoutedFoodPortion(row.envelope)
                }
                return@foodBoundedProjection FoodReadProjectionResult(LocalFoodCheckKitchenResultV1(LocalFoodReadResultV1(orders = listOf(foodSummary(order)))))
            }
            val remaining = order.value.portionIds.sortedBy { it.value }.filter { afterPortionId == null || it.value > afterPortionId.value }
            val selectedIds = remaining.take(limit)
            // The complete order shares this request's budget. Quoted work also closes every
            // immutable sidecar/batch and signed fact, including unbound work beyond the page.
            // A V2 known current amount also requires every routed member's historical facts.
            // V3 closes every routed member even when money is unavailable; required off-page
            // source failures cannot become an accepting null. Older paths retain their closure.
            val summaries = order.value.portionIds.map { foodCurrentPortionSummary(view, order, it) }
            val originalQuotations = foodOriginalQuotationPortions(view, order, recipes, summaries, quotations)
            val originalQuotationsById = originalQuotations.associateBy { it.portion.envelope.id }
            val preparations = summaries.map { foodStoredPortionPreparation(it.envelope) }
            val currentUnpaidCandidate = projection == FoodReadProjection.CHECK_KITCHEN_V2 &&
                order.value.financialCollectionId == null && order.value.closedByOperationId == null &&
                preparations.all(FoodCurrentUnpaidPayable::isNeverSent)
            val sourceIds = if (projection == FoodReadProjection.CHECK_KITCHEN_V3 || currentUnpaidCandidate)
                order.value.portionIds else selectedIds
            val sourceRows = sourceIds.map { portionId ->
                originalQuotationsById[portionId]?.portion ?: foodPortion(view, order, portionId, recipes, quotations)
            }
            val sourcesById = sourceRows.associateBy { it.envelope.id }
            val selectedRows = selectedIds.map { checkNotNull(sourcesById[it]) }
            // Every required issued portion needs the FULL real normalized source crosswalk,
            // original reservation and current transitive stock closure. V3 includes the
            // complete order; no page substitutes originalIssue JSON or omits a required source.
            for (row in sourceRows) {
                val portion = when (val body = row.envelope.body) {
                    is FoodBodyV1.Portion -> body
                    is FoodBodyV1.QuotedPortion -> continue
                    else -> error("food.portion.body_required")
                }
                val issueId = portion.preparation.issueId ?: continue
                if (portion.normalizedIssue != null) foodRequireNormalizedCurrentIssue(view, stock, row)
                else {
                    val cut = stock.observe(view, emptyList(), issueIds = listOf(issueId))
                    requireCurrentFoodIssue(portion, checkNotNull(cut.snapshot.issue(issueId)))
                }
            }
            if (projection == FoodReadProjection.CHECK_KITCHEN_V3 && order.value.financialCollectionId == null) {
                check(sourceRows.none { (it.envelope.body as? FoodBodyV1.Portion)?.financialCollectionId != null }) {
                    "food.read.uncollected_portion_collection_mismatch"
                }
            }
            val originalQuotes = order.value.batchIds.map { batchId ->
                val batch = foodRead(view, LocalFoodProfileV1.PRICE_BATCH, batchId) ?: error("food.price_batch_missing")
                val price = batch.envelope.body as FoodBodyV1.PriceBatch
                check(price.orderId == id)
                val quote = StoredPricingSnapshotCodec.decode(price.quote)
                val members = summaries.filter { when (val portion = it.envelope.body) {
                    is FoodBodyV1.Portion -> portion.priceBatchId == batchId
                    is FoodBodyV1.QuotedPortion -> portion.priceBatchId == batchId
                    else -> error("food.portion.body_required")
                } }.map { it.envelope.id }
                check(members.size == quote.lines.size && members.toSet() == quote.lines.map { it.portionId }.toSet()) {
                    "food.price_batch_membership"
                }
                quote
            }
            val total = originalQuotes.fold(Money(Currency.EGP, 0)) { sum, quote -> sum + quote.payable }
            check(total == order.value.originalQuotedTotal)
            val financial = order.value.financialCollectionId?.let { foodPaidHandoffCut(foodReadView(view), order) }
            val collection = financial?.value
            val currentUnpaidPayable = when (projection) {
                FoodReadProjection.CHECK_KITCHEN_V2 -> FoodCurrentUnpaidPayable.project(originalQuotes, preparations, total,
                    order.value.financialCollectionId, order.value.closedByOperationId)
                FoodReadProjection.CHECK_KITCHEN_V3 -> FoodCurrentUnpaidPayable.projectIncludingPrepared(originalQuotes, preparations, total,
                    order.value.financialCollectionId, order.value.closedByOperationId)
                else -> null
            }
            val detail = LocalFoodOrderDetailV1(foodSummary(order), selectedRows.map { row ->
                val selection = foodStoredPortionSelection(row.envelope)
                val description = when (val body = row.envelope.body) {
                    is FoodBodyV1.Portion -> body.recipe.description
                    is FoodBodyV1.QuotedPortion -> checkNotNull(originalQuotationsById[row.envelope.id]).batch.quotations
                        .single { it.portionId == row.envelope.id }.description
                    else -> error("food.portion.body_required")
                }
                LocalFoodPortionViewV1(selection.portionId, description, selection.quantity,
                    selection.modifierIds, selection.itemNote, foodStoredPortionPreparation(row.envelope), selection.lineId, selection.seatNote)
            }, total, preparations.any { it.cancellationReasonId != null } && collection?.cancellationRefundId == null && currentUnpaidPayable == null,
                collection?.status?.name ?: "UNPAID_NO_COLLECTION", financial?.projection())
            val food = LocalFoodReadResultV1(detail = detail, nextAfter = if (remaining.size > selectedIds.size) selectedIds.last() else null)
            val kitchen = if (projection != FoodReadProjection.LEGACY)
                foodCheckStoredKitchenObservation(view, recipes, order, summaries, originalQuotationsById)
            else null
            // This wrapper is private to the same owner cut; legacy projection still encodes only its food fields.
            val delivery = order.value.deliveryDetails?.let {
                LocalFoodDeliveryObservationV1(order.order.envelope.id, order.order.envelope.revision, it)
            }
            FoodReadProjectionResult(LocalFoodCheckKitchenResultV1(food, kitchen, delivery), currentUnpaidPayable)
        }
    }

    /** Order summaries can disclose quotation membership only after the same genuine closure. */
    private suspend fun requireOwnerQuotationSources(view: CommandReadView, order: FoodOrderCut) {
        foodOriginalQuotationPortions(view, order, recipes, quotationSource = quotations)
    }

    private suspend fun stationRead(view: CommandReadView, context: ReadContext, stationId: DomainId,
        afterTaskId: DomainId?, limit: Int, directTask: DomainId?): PreparedRead {
        val header = foodObserveRow(view, LocalFoodProfileV1.STATION_INDEX, stationId)
        val requirement = foodReadRequirement(context, SecurityAction.KITCHEN_TASK, stationId, header?.envelope?.revision ?: Revision(0), null)
        return foodBoundedRead(context, listOf(requirement)) {
            val index = foodRead(view, LocalFoodProfileV1.STATION_INDEX, stationId)
            val all = (index?.envelope?.body as? FoodBodyV1.StationIndex)?.tasks.orEmpty().sortedBy { it.taskId.value }
                .filter { (afterTaskId == null || it.taskId.value > afterTaskId.value) && (directTask == null || it.taskId == directTask) }
            val selected = all.take(limit)
            val tasks = mutableListOf<LocalFoodStationTaskViewV1>()
            for (locator in selected) {
                val order = foodOrder(view, locator.orderId)
                val row = foodPortion(view, order, locator.portionId, recipes, quotations)
                val portion = row.envelope.body as FoodBodyV1.Portion
                val task = portion.preparation.tasks.single { it.stationId == stationId && it.taskId == locator.taskId }
                check(view.claim(LocalFoodProfileV1.claim("task", task.taskId)) == locator.orderId)
                check(portion.preparation.status !in setOf(FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START) &&
                    portion.preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER)
                if (portion.normalizedIssue != null) foodRequireNormalizedCurrentIssue(view, stock, row)
                else {
                    val cut = stock.observe(view, emptyList(), issueIds = listOf(checkNotNull(portion.preparation.issueId)))
                    requireCurrentFoodIssue(portion, checkNotNull(cut.snapshot.issue(checkNotNull(portion.preparation.issueId))))
                }
                foodRequireDisplayDelivery(view, order, row, task)
                tasks += LocalFoodStationTaskViewV1(locator.orderId, order.order.envelope.revision, locator.portionId,
                    portion.preparation.revision, locator.taskId, stationId, portion.recipe.description, portion.selection.quantity,
                    portion.selection.itemNote, order.value.service.reference, task.status, portion.preparation.status, task.deliveryMode,
                    seatNote = portion.selection.seatNote)
            }
            LocalFoodReadResultV1(tasks = tasks, nextAfter = if (all.size > selected.size) selected.last().taskId else null)
        }
    }
}

/** Only expected finite capacity is translated; corruption/IO/arithmetic retain kernel uncertainty. */
private suspend fun foodBoundedRead(context: ReadContext, requirements: List<AuthorizationRequirement>,
    read: suspend () -> LocalFoodReadResultV1): PreparedRead =
    foodBoundedProjection(context, requirements, FoodReadProjection.LEGACY) { FoodReadProjectionResult(LocalFoodCheckKitchenResultV1(read())) }

private suspend fun foodBoundedProjection(context: ReadContext, requirements: List<AuthorizationRequirement>, projection: FoodReadProjection,
    read: suspend () -> FoodReadProjectionResult): PreparedRead {
    val result = try { read() }
    catch (_: FoodCapacityExceeded) {
        FoodReadProjectionResult(LocalFoodCheckKitchenResultV1(LocalFoodReadResultV1(unavailable = LocalFoodReadUnavailableV2.CAPACITY_REQUIRED)))
    }
    catch (_: LocalStockHydrationCapacityUnavailable) {
        FoodReadProjectionResult(LocalFoodCheckKitchenResultV1(LocalFoodReadResultV1(unavailable = LocalFoodReadUnavailableV2.CAPACITY_REQUIRED)))
    }
    return foodPreparedProjection(context, requirements, result, projection)
}

private fun foodSummary(order: FoodOrderCut): LocalFoodOrderSummaryV1 = LocalFoodOrderSummaryV1(order.order.envelope.id, order.value.ownerId,
    order.order.envelope.revision, order.value.activity, order.value.service, order.value.portionIds.size, order.value.handoff?.id, order.value.handoff?.to,
    order.value.financialCollectionId, order.value.closedByOperationId != null)
private fun foodReadRequirement(context: ReadContext, action: SecurityAction, id: DomainId, revision: Revision, owner: DomainId?): AuthorizationRequirement =
    AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
        ActionBinding(id, revision, context.semanticDigest, null, "food.read"),
        owner = if (action.ownerPrivate) OwnerConstraint.OwnerPrivate(checkNotNull(owner)) else OwnerConstraint.Unrestricted)
private fun foodPreparedProjection(context: ReadContext, requirements: List<AuthorizationRequirement>,
    result: FoodReadProjectionResult, projection: FoodReadProjection): PreparedRead =
    object : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> = requirements
        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            check(authorizations.size == requirements.size && authorizations.indices.all {
                authorizations[it].principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorizations[it].isBoundTo(requirements[it], context.actorId)
            })
            fun encode(value: FoodReadProjectionResult): StoredBytes = when (projection) {
                FoodReadProjection.LEGACY -> localSalesEncode(value.result.food, LocalFoodReadResultV1.serializer())
                FoodReadProjection.CHECK_KITCHEN_V1 -> localSalesEncode(value.result, LocalFoodCheckKitchenResultV1.serializer())
                FoodReadProjection.CHECK_KITCHEN_V2 -> localSalesEncode(LocalFoodCheckKitchenResultV2(value.result.food,
                    value.result.kitchen, value.result.delivery, value.currentUnpaidPayable), LocalFoodCheckKitchenResultV2.serializer())
                FoodReadProjection.CHECK_KITCHEN_V3 -> localSalesEncode(LocalFoodCheckKitchenResultV3(value.result.food,
                    value.result.kitchen, value.result.delivery, value.currentUnpaidPayable), LocalFoodCheckKitchenResultV3.serializer())
            }
            return try { encode(result) }
            catch (_: LocalSalesWireCapacityExceeded) {
                encode(FoodReadProjectionResult(LocalFoodCheckKitchenResultV1(LocalFoodReadResultV1(unavailable = LocalFoodReadUnavailableV2.CAPACITY_REQUIRED))))
            }
        }
    }

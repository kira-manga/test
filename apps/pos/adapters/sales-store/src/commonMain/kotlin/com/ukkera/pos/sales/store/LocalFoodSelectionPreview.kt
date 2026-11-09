package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockHydrationCapacityUnavailable
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Advisory selection refusal, never an empty/partial price or a persisted order decision. */
@Serializable
public enum class LocalFoodSelectionPreviewUnavailableV1 { TARGET_UNAVAILABLE, SOURCE_UNAVAILABLE, CAPACITY_REQUIRED }

public sealed interface LocalFoodSelectionPreviewResultV1 {
    public class Available(public val requestDigest: PayloadDigest, public val quote: PricingSnapshot) : LocalFoodSelectionPreviewResultV1
    public class Unavailable(public val reason: LocalFoodSelectionPreviewUnavailableV1) : LocalFoodSelectionPreviewResultV1
}

@Serializable
private sealed interface FoodSelectionPreviewWireV1 {
    @Serializable @SerialName("AVAILABLE")
    data class Available(val requestDigest: PayloadDigest, val pricing: String) : FoodSelectionPreviewWireV1
    @Serializable @SerialName("UNAVAILABLE")
    data class Unavailable(val reason: LocalFoodSelectionPreviewUnavailableV1) : FoodSelectionPreviewWireV1
}

/** Exact existing Create/Add bytes. There is no operation, stock-route selection or saved quote. */
public object LocalFoodSelectionPreviewCodecV1 {
    public val QUERY: ReadKey = ReadKey("sales.food.selection-preview", 1)
    public fun encode(request: LocalFoodRequestV1): StoredBytes {
        require(request is LocalFoodRequestV1.Create || request is LocalFoodRequestV1.Add)
        return LocalFoodCodecV1.encode(request)
    }
    public fun read(request: LocalFoodRequestV1): ReadRequest = ReadRequest(QUERY, encode(request))
    public fun result(bytes: StoredBytes): LocalFoodSelectionPreviewResultV1 = when (
        val wire = localSalesDecode(bytes, FoodSelectionPreviewWireV1.serializer())
    ) {
        is FoodSelectionPreviewWireV1.Available -> LocalFoodSelectionPreviewResultV1.Available(wire.requestDigest,
            StoredPricingSnapshotCodec.decode(wire.pricing))
        is FoodSelectionPreviewWireV1.Unavailable -> LocalFoodSelectionPreviewResultV1.Unavailable(wire.reason)
    }
}

/**
 * Same-master current selection read. The actual installed recipe source captures facts in
 * prepareSelectionPreview; only its pure pricing quote runs after the kernel has authorized every requirement.
 * Recipe snapshots, signatures, ingredients, costs and stock are never projected.
 */
public class LocalFoodSelectionPreviewReadHandler private constructor(
    private val recipes: TransactionFoodRecipeSourceV1,
) : MasterReadHandler {
    public companion object {
        /** Bind once to the registry's exact installed stock repository and ordinary read loan. */
        public fun withStock(stock: LocalStockRepository, recipes: TransactionFoodRecipeSourceV1): MasterReadHandler =
            CommerceReadHandler(LocalFoodSelectionPreviewReadHandler(recipes), stock)
    }

    override val key: ReadKey = LocalFoodSelectionPreviewCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodSelectionPreviewCodecV1.encode(
        localSalesDecodePreview(input, LocalFoodRequestV1.serializer(), 16_384))

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS &&
            LocalFoodCodecV1.decode(normalized) is LocalFoodRequestV1.Add

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope)
        val request = LocalFoodCodecV1.decode(normalized)
        val orderId: DomainId
        val revision: Revision
        val portions: List<LocalFoodPortionSelectionV1>
        when (request) {
            is LocalFoodRequestV1.Create -> { orderId = request.orderId; revision = Revision(0); portions = request.portions }
            is LocalFoodRequestV1.Add -> { orderId = request.orderId; revision = request.expectedOrderRevision; portions = request.portions }
            else -> error("food.preview.selection_required")
        }
        val bounded = foodReadView(view)
        // This bounded header only chooses the exact owner binding/refusal. Successful Add
        // still requires the complete current order/owner-index producer proof below.
        val header = foodObserveRow(bounded, LocalFoodProfileV1.ORDER, orderId)
        val observed = header?.envelope?.body as? FoodBodyV1.Order
        val action = if (request is LocalFoodRequestV1.Create) SecurityAction.ORDER_CREATE else SecurityAction.ORDER_EDIT
        val requirements = listOf(
            AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
                ActionBinding(orderId, revision, context.semanticDigest, null, "food.selection-preview"),
                owner = if (action.ownerPrivate) OwnerConstraint.OwnerPrivate(observed?.ownerId ?: context.actorId)
                    else OwnerConstraint.Unrestricted),
            AuthorizationRequirement(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), context.scope),
        )
        var originalQuotedTotal: Money? = null
        val preparation = try {
            when {
                request is LocalFoodRequestV1.Create && header != null -> FoodPreviewPreparation.Refused(LocalFoodSelectionPreviewUnavailableV1.TARGET_UNAVAILABLE)
                request is LocalFoodRequestV1.Add && (observed == null || header?.row?.revision != revision ||
                    observed.ownerId != context.actorId || observed.handoff != null || observed.financialCollectionId != null ||
                    observed.closedByOperationId != null) -> FoodPreviewPreparation.Refused(LocalFoodSelectionPreviewUnavailableV1.TARGET_UNAVAILABLE)
                request is LocalFoodRequestV1.Add && checkNotNull(observed).portionIds.size + portions.size > LocalFoodProfileV1.MAX_ORDER_PORTIONS ->
                    FoodPreviewPreparation.Refused(LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED)
                else -> {
                    // New serving identities must be unused; existing sent portions remain
                    // eligible order history and are not mistaken for this unsent selection.
                    val used = portions.any { foodObserveRow(bounded, LocalFoodProfileV1.PORTION, it.portionId) != null }
                    if (used) FoodPreviewPreparation.Refused(LocalFoodSelectionPreviewUnavailableV1.TARGET_UNAVAILABLE)
                    else {
                        val source = when (request) {
                            is LocalFoodRequestV1.Create -> recipes.prepareSelectionPreview(bounded, request.activity, request.service,
                                request.expectedCatalogRevision, portions)
                            is LocalFoodRequestV1.Add -> {
                                val current = foodOrder(bounded, orderId)
                                check(current.order.envelope.revision == revision && current.value == observed)
                                originalQuotedTotal = current.value.originalQuotedTotal
                                recipes.prepareSelectionPreview(bounded, current.value.activity, current.value.service, request.expectedCatalogRevision, portions)
                            }
                            else -> error("food.preview.selection_required")
                        }
                        if (source == null) FoodPreviewPreparation.Refused(LocalFoodSelectionPreviewUnavailableV1.SOURCE_UNAVAILABLE)
                        else {
                            check(source.scope == context.scope)
                            FoodPreviewPreparation.Source(source)
                        }
                    }
                }
            }
        } catch (_: FoodCapacityExceeded) {
            FoodPreviewPreparation.Refused(LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED)
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            FoodPreviewPreparation.Refused(LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED)
        } catch (failure: LocalSalesQuoteUnavailable) {
            FoodPreviewPreparation.Refused(foodPreviewQuoteRefusal(failure.reason))
        }
        // Neither the bounded view nor any transaction-bound order/source handle escapes.
        return foodSelectionPreviewRead(context, requirements, sha256(normalized.copyBytes()), orderId,
            portions.toList(), originalQuotedTotal, preparation)
    }
}

private sealed interface FoodPreviewPreparation {
    class Source(val value: PreparedFoodSelectionPreviewV1) : FoodPreviewPreparation
    class Refused(val reason: LocalFoodSelectionPreviewUnavailableV1) : FoodPreviewPreparation
}

private fun foodSelectionPreviewRead(context: ReadContext, required: List<AuthorizationRequirement>, requestDigest: PayloadDigest,
    orderId: DomainId, portions: List<LocalFoodPortionSelectionV1>, originalQuotedTotal: Money?, preparation: FoodPreviewPreparation): PreparedRead =
    object : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> = required.toList()
        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            check(authorizations.size == requirements.size && authorizations.indices.all {
                authorizations[it].principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorizations[it].isBoundTo(requirements[it], context.actorId)
            })
            if (preparation is FoodPreviewPreparation.Refused) return foodPreviewUnavailable(preparation.reason)
            return try {
                val quote = (preparation as FoodPreviewPreparation.Source).value.quote(orderId, observedAt)
                check(quote.snapshotId == orderId && quote.revision == Revision(0) && quote.currency == Currency.EGP)
                val byPortion = quote.lines.associateBy { it.portionId }
                check(quote.lines.size == portions.size && byPortion.size == portions.size && byPortion.keys == portions.map { it.portionId }.toSet())
                check(portions.all { selected -> byPortion.getValue(selected.portionId).let {
                    it.lineId == selected.commercialLineId && it.quantity == selected.quantity
                } })
                if (originalQuotedTotal != null && quote.payable.minor > Long.MAX_VALUE - originalQuotedTotal.minor)
                    return foodPreviewUnavailable(LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED)
                localSalesEncode(FoodSelectionPreviewWireV1.Available(requestDigest, StoredPricingSnapshotCodec.encode(quote)),
                    FoodSelectionPreviewWireV1.serializer())
            } catch (failure: LocalSalesQuoteUnavailable) {
                foodPreviewUnavailable(foodPreviewQuoteRefusal(failure.reason))
            } catch (_: FoodCapacityExceeded) {
                foodPreviewUnavailable(LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED)
            } catch (_: LocalSalesWireCapacityExceeded) {
                foodPreviewUnavailable(LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED)
            }
        }
    }

private fun foodPreviewQuoteRefusal(reason: LocalSalesQuoteUnavailableReason): LocalFoodSelectionPreviewUnavailableV1 =
    if (reason == LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED) LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED
    else LocalFoodSelectionPreviewUnavailableV1.SOURCE_UNAVAILABLE

private fun foodPreviewUnavailable(reason: LocalFoodSelectionPreviewUnavailableV1): StoredBytes =
    localSalesEncode(FoodSelectionPreviewWireV1.Unavailable(reason), FoodSelectionPreviewWireV1.serializer())

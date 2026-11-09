package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable
public data class LocalFoodHandoffResponsibilityQueryV1(public val orderId: DomainId, public val handoffId: DomainId,
    public val expectedOrderStorageRevision: Revision? = null)
@Serializable
public data class LocalFoodHandoffPortionV1(public val portionId: DomainId, public val description: String,
    public val quantity: Quantity, public val preparationDomainRevision: Revision, public val preparation: FoodPreparationStatus,
    public val fulfillment: FoodFulfillmentStatus, public val cancellationRequested: Boolean, public val startedWork: Boolean)
@Serializable
public data class LocalFoodHandoffResponsibilityV1(public val orderId: DomainId, public val handoffId: DomainId,
    public val oldOwnerId: DomainId, public val recipientId: DomainId, public val orderStorageRevision: Revision,
    public val activity: LocalSalesActivity, public val service: LocalFoodServiceV1, public val originalQuotedTotal: Money,
    public val chargeResolutionRequired: Boolean, public val portions: List<LocalFoodHandoffPortionV1>,
    public val financial: LocalFoodHandoffFinancialV1? = null) {
    init { require(portions.size in 1..32 && portions.map { it.portionId }.distinct().size == portions.size) }
}
@Serializable
public enum class LocalFoodHandoffUnavailableV1 { TARGET_UNAVAILABLE, CAPACITY_REQUIRED, NOT_PRECOLLECTION, PAYMENT_UNRESOLVED }
@Serializable
public data class LocalFoodHandoffResponsibilityResultV1(public val responsibility: LocalFoodHandoffResponsibilityV1? = null,
    public val unavailable: LocalFoodHandoffUnavailableV1? = null) {
    init { require((responsibility == null) != (unavailable == null)) }
}

public object LocalFoodHandoffResponsibilityCodecV1 {
    public val QUERY: ReadKey = ReadKey("sales.food.handoff-responsibility", 1)
    public fun read(query: LocalFoodHandoffResponsibilityQueryV1): ReadRequest = ReadRequest(QUERY,
        localSalesEncode(query, LocalFoodHandoffResponsibilityQueryV1.serializer(), 2_048))
    public fun result(bytes: StoredBytes): LocalFoodHandoffResponsibilityResultV1 =
        localSalesDecode(bytes, LocalFoodHandoffResponsibilityResultV1.serializer(), 65_536)
}

/**
 * The concrete package-owned proof uses this SAME borrowed view, the actual full current
 * food/financial producer and its installed interlocks. No accepting callback, raw legacy
 * header absence, caller-supplied principal or client authorization token is a substitute.
 */
public sealed interface LocalFoodHandoffPrecollectionProof {
    public suspend fun verify(view: CommandReadView, orderId: DomainId, orderStorageRevision: Revision): Boolean
}

/** Recipient-only complete thin responsibility. This read grants neither stock nor cash authority. */
public class LocalFoodHandoffResponsibilityReadHandler(private val precollection: LocalFoodHandoffPrecollectionProof) : MasterReadHandler {
    override val key: ReadKey = LocalFoodHandoffResponsibilityCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = localSalesEncode(
        localSalesDecode(input, LocalFoodHandoffResponsibilityQueryV1.serializer(), 2_048),
        LocalFoodHandoffResponsibilityQueryV1.serializer(), 2_048).also { require(it == input) }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalFoodHandoffResponsibilityQueryV1.serializer(), 2_048)
        val bounded = FoodReadView(view)
        val header = foodObserveRow(bounded, LocalFoodProfileV1.ORDER, query.orderId)
        val body = header?.envelope?.body as? FoodBodyV1.Order
        val recipient = body?.handoff?.to ?: body?.ownerId ?: context.actorId
        val requirement = AuthorizationRequirement(Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM), context.scope,
            ActionBinding(query.orderId, header?.envelope?.revision ?: Revision(0), context.semanticDigest, null, "food.handoff-responsibility"),
            owner = OwnerConstraint.OwnerPrivate(recipient))
        val matches = body?.handoff?.let { it.id == query.handoffId && it.to == context.actorId } == true &&
            (query.expectedOrderStorageRevision == null || query.expectedOrderStorageRevision == header?.envelope?.revision)
        val result = if (!matches) missingHandoff(LocalFoodHandoffUnavailableV1.TARGET_UNAVAILABLE) else try {
            val order = foodOrder(bounded, query.orderId)
            check(order.value.handoff?.id == query.handoffId && order.value.handoff?.to == context.actorId)
            val offer = foodHandoffRowV2(bounded, query.handoffId)
            if (offer == null) {
                check(bounded.claim(LocalFoodProfileV1.claim("handoff", query.handoffId)) == query.orderId &&
                    bounded.claim(LocalFoodHandoffProfileV2.incoming(context.actorId, query.handoffId)) == null &&
                    bounded.claim(LocalFoodProfileV1.claim("accepted-handoff", query.handoffId)) == null)
                foodRequireLegacyPendingV2(bounded, order)
            } else requirePendingHandoffV2(order, offer)
            val financial = if (order.value.financialCollectionId == null) null else foodPaidHandoffCut(bounded, order)
            if (financial?.unresolved == true) missingHandoff(LocalFoodHandoffUnavailableV1.PAYMENT_UNRESOLVED)
            else if (financial == null && !precollection.verify(bounded, query.orderId, order.order.envelope.revision))
                missingHandoff(LocalFoodHandoffUnavailableV1.NOT_PRECOLLECTION)
            else {
                val portions = order.value.portionIds.map { id ->
                    val portion = foodPortionSummary(bounded, order, id).envelope.body as FoodBodyV1.Portion
                    LocalFoodHandoffPortionV1(id, portion.recipe.description, portion.selection.quantity,
                        portion.preparation.revision, portion.preparation.status, portion.preparation.fulfillment,
                        portion.preparation.cancellationReasonId != null, portion.preparation.started)
                }
                val total = order.value.batchIds.map { id ->
                    val price = (foodRead(bounded, LocalFoodProfileV1.PRICE_BATCH, id)
                        ?: error("food.handoff.price_missing")).envelope.body as FoodBodyV1.PriceBatch
                    check(price.orderId == query.orderId)
                    StoredPricingSnapshotCodec.decode(price.quote).payable
                }.fold(Money(Currency.EGP, 0)) { sum, amount -> sum + amount }
                check(total == order.value.originalQuotedTotal && portions.size == order.value.portionIds.size)
                LocalFoodHandoffResponsibilityResultV1(LocalFoodHandoffResponsibilityV1(query.orderId, query.handoffId,
                    order.value.ownerId, context.actorId, order.order.envelope.revision, order.value.activity,
                    order.value.service, total, portions.any { it.cancellationRequested }, portions, financial?.projection()))
            }
        } catch (_: FoodCapacityExceeded) { missingHandoff(LocalFoodHandoffUnavailableV1.CAPACITY_REQUIRED) }
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(requirement, context.actorId))
                return try { localSalesEncode(result, LocalFoodHandoffResponsibilityResultV1.serializer(), 65_536) }
                catch (_: LocalSalesWireCapacityExceeded) { localSalesEncode(missingHandoff(
                    LocalFoodHandoffUnavailableV1.CAPACITY_REQUIRED), LocalFoodHandoffResponsibilityResultV1.serializer(), 65_536) }
            }
        }
    }
}

private fun missingHandoff(reason: LocalFoodHandoffUnavailableV1): LocalFoodHandoffResponsibilityResultV1 =
    LocalFoodHandoffResponsibilityResultV1(unavailable = reason)

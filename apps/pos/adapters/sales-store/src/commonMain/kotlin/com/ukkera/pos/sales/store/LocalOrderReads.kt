package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalOrderQueryV1 {
    @Serializable @SerialName("ORDER") public data class Order(public val orderId: DomainId) : LocalOrderQueryV1
    @Serializable @SerialName("OWN_ORDERS")
    public data class OwnOrders(public val after: DomainId? = null, public val limit: Int = 25) : LocalOrderQueryV1
    @Serializable @SerialName("HANDOFF") public data class Handoff(public val orderId: DomainId) : LocalOrderQueryV1
}

@Serializable
public data class LocalOrderPriceLineV1(public val portionId: DomainId, public val quantity: Quantity, public val payable: Money)
@Serializable
public data class LocalOrderComponentV1(
    public val key: String, public val portionId: DomainId?, public val chargeId: DomainId?, public val kind: String,
    public val amount: Money, public val taxCategory: String,
)
@Serializable
public data class LocalOrderViewV1(
    public val orderId: DomainId, public val ownerId: DomainId, public val revision: Revision, public val quoteRevision: Revision,
    public val selection: LocalOrderSelectionV1, public val beforeDiscount: Money, public val discount: Money, public val payable: Money,
    public val lines: List<LocalOrderPriceLineV1>, public val components: List<LocalOrderComponentV1>,
    public val collectionId: DomainId?, public val saleId: DomainId?, public val handoffId: DomainId?, public val handoffRecipientId: DomainId?,
)

@Serializable
public data class LocalOrderReadResultV1(public val orders: List<LocalOrderViewV1>, public val nextAfter: DomainId?)

/** Explicit wire discriminator; absence in the original read is NOT a legacy-stock proof. */
@Serializable
public enum class LocalOrderStockProfileV2 { LEGACY_BOUNDED, NORMALIZED }

@Serializable
public data class LocalProfiledOrderV2(public val order: LocalOrderViewV1, public val stockProfile: LocalOrderStockProfileV2)

@Serializable
public data class LocalOrderReadResultV2(public val orders: List<LocalProfiledOrderV2>, public val nextAfter: DomainId?)

/** Separate read identity preserves the original strict v1 response, including saved clients. */
public object LocalOrderReadCodecV2 {
    public val QUERY: ReadKey = ReadKey("sales.order.read", 2)
    public fun request(query: LocalOrderQueryV1): ReadRequest = ReadRequest(QUERY, LocalOrderReadCodec.encode(query))
    public fun decode(body: StoredBytes): LocalOrderReadResultV2 = localSalesDecode(body, LocalOrderReadResultV2.serializer())
}

public object LocalOrderReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.order.read", 1)
    public fun request(query: LocalOrderQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalOrderQueryV1): StoredBytes {
        if (query is LocalOrderQueryV1.OwnOrders) require(query.limit in 1..25) { "sales.page.invalid" }
        return localSalesEncode(query, LocalOrderQueryV1.serializer(), 2_048)
    }
    public fun decodeQuery(body: StoredBytes): LocalOrderQueryV1 = localSalesDecode(body, LocalOrderQueryV1.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalOrderReadResultV1 = localSalesDecode(body, LocalOrderReadResultV1.serializer())
}

public class LocalOrderReadHandler private constructor(private val stockProfile: Boolean) : MasterReadHandler {
    public constructor() : this(false)
    override public val key: ReadKey get() = if (stockProfile) LocalOrderReadCodecV2.QUERY else LocalOrderReadCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalOrderReadCodec.encode(LocalOrderReadCodec.decodeQuery(input))
    override public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.RETAIL_ORDERS) return false
        val query = LocalOrderReadCodec.decodeQuery(normalized)
        return when (query) {
            is LocalOrderQueryV1.Order, is LocalOrderQueryV1.OwnOrders -> LocalOrderReadCodec.encode(query) == normalized
            is LocalOrderQueryV1.Handoff -> false
        }
    }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope) { "sales.context.invalid" }
        val query = LocalOrderReadCodec.decodeQuery(normalized)
        val cuts: List<LocalOrderCut>
        val next: DomainId?
        val action: SecurityAction
        when (query) {
            is LocalOrderQueryV1.Order -> {
                if (!orderSourcePresent(view, query.orderId)) return emptyOrderRead(context, query.orderId, SecurityAction.ORDER_READ, stockProfile)
                cuts = listOf(readOrder(view, query.orderId))
                if (cuts.single().order.value.ownerId != context.actorId) return emptyOrderRead(context, query.orderId, SecurityAction.ORDER_READ, stockProfile)
                next = null
                action = SecurityAction.ORDER_READ
            }
            is LocalOrderQueryV1.Handoff -> {
                if (!orderSourcePresent(view, query.orderId)) return emptyOrderRead(context, query.orderId, SecurityAction.ORDER_HANDOFF, stockProfile)
                cuts = listOf(readOrder(view, query.orderId))
                if (cuts.single().order.value.handoff?.intendedRecipientId != context.actorId) return emptyOrderRead(context, query.orderId, SecurityAction.ORDER_HANDOFF, stockProfile)
                next = null
                action = SecurityAction.ORDER_HANDOFF
            }
            is LocalOrderQueryV1.OwnOrders -> {
                if (!localCustodianSourcePresent(view, context.actorId)) return emptyOrderRead(context, context.actorId, SecurityAction.ORDER_READ, stockProfile)
                val work = LocalSalesStorage.work(view, context.actorId)
                val ids = work.value.orderIds.sortedBy { it.value }.filter { query.after == null || it.value > query.after.value }
                val page = ids.take(query.limit)
                cuts = page.map { readOrder(view, it).also { cut -> check(cut.order.value.ownerId == context.actorId) { "sales.work.unavailable" } } }
                next = if (ids.size > page.size) page.last() else null
                action = SecurityAction.ORDER_READ
                if (cuts.isEmpty()) return if (stockProfile)
                    localSalesRead(context, action, context.actorId, work.value.revision, context.actorId,
                        "order.read.own", LocalOrderReadResultV2(emptyList(), null), LocalOrderReadResultV2.serializer())
                else localSalesRead(context, action, context.actorId, work.value.revision, context.actorId,
                        "order.read.own", LocalOrderReadResultV1(emptyList(), null), LocalOrderReadResultV1.serializer())
            }
        }
        val requirements = cuts.map { cut -> AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
            ActionBinding(cut.order.value.id, cut.order.value.revision, context.semanticDigest, null, "order.read"),
            owner = OwnerConstraint.OwnerPrivate(context.actorId)) }
        val result = LocalOrderReadResultV1(cuts.map(::orderView), next)
        val profiled = if (stockProfile) LocalOrderReadResultV2(cuts.mapIndexed { index, cut ->
            LocalProfiledOrderV2(result.orders[index], if (cut.order.value.stockRequirements.normalized)
                LocalOrderStockProfileV2.NORMALIZED else LocalOrderStockProfileV2.LEGACY_BOUNDED)
        }, next) else null
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = requirements
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.indices.all { authorizations[it].isBoundTo(requirements[it], context.actorId) }) {
                    "sales.authorization.invalid"
                }
                return if (profiled != null) localSalesEncode(profiled, LocalOrderReadResultV2.serializer())
                else localSalesEncode(result, LocalOrderReadResultV1.serializer())
            }
        }
    }

    public companion object {
        /** Same source/owner/authorization checks; only the explicitly versioned projection differs. */
        public fun withStockProfile(): MasterReadHandler = LocalOrderReadHandler(true)
    }
}

private fun emptyOrderRead(context: ReadContext, targetId: DomainId, action: SecurityAction, stockProfile: Boolean): PreparedRead =
    if (stockProfile) localSalesRead(context, action, targetId, Revision(0), context.actorId, "order.read.unavailable",
        LocalOrderReadResultV2(emptyList(), null), LocalOrderReadResultV2.serializer())
    else localSalesRead(context, action, targetId, Revision(0), context.actorId, "order.read.unavailable",
        LocalOrderReadResultV1(emptyList(), null), LocalOrderReadResultV1.serializer())

internal suspend fun orderSourcePresent(view: CommandReadView, id: DomainId): Boolean =
    localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, id),
        listOf(LocalSalesStoreProfile.unique("order", id)), listOf(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, id)))

internal fun orderView(cut: LocalOrderCut): LocalOrderViewV1 {
    val order = cut.order.value
    return LocalOrderViewV1(order.id, order.ownerId, order.revision, order.quote.revision, cut.context.value.selection,
        order.quote.beforeDiscountPayable, order.quote.advertisedDiscount, order.quote.payable,
        order.quote.lines.map { LocalOrderPriceLineV1(it.portionId, it.quantity, it.payable) },
        order.quote.components.map { LocalOrderComponentV1(it.key, it.portionId, it.chargeId, it.kind.name, it.posted, it.tax.categoryCode) },
        order.collectionId, order.paidSaleId, order.handoff?.id, order.handoff?.intendedRecipientId)
}

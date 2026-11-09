package com.ukkera.pos.sales

import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.time.Instant

/**
 * Bounded path: stock-tracked retail portions in their exact stock base unit.
 * Every pricing portion is covered in full. Non-stock services, mixed recipe/kit
 * timings and substitutions need their own authoritative capability/stock proof;
 * an empty list or positive payable is NEVER a stock bypass here.
 *
 * Inventory's lineId is the concrete priced portionId (not a parent logical line).
 */
/** One immutable priced portion requirement. It is demand, never a lot allocation or permission. */
@kotlinx.serialization.Serializable
public data class RetailStockDemandV2(public val portionId: DomainId, public val skuId: DomainId,
    public val warehouseId: DomainId, public val quantity: Quantity) {
    init { requireSales(quantity.scaled > 0, SalesRule.STOCK_PROOF_MISMATCH) }
}

public class RetailStockRequirements private constructor(legacy: List<StockAllocation>?, demand: List<RetailStockDemandV2>?) {
    public constructor(allocations: List<StockAllocation>) : this(allocations, null)
    private val legacyAllocations: List<StockAllocation>? = legacy?.let { frozenList(it, 256) }
    public val normalizedLines: List<RetailStockDemandV2>? = demand?.let { frozenList(it, 256) }
    /** Never manufacture an empty/truncated legacy proof from normalized per-ID storage. */
    public val allocations: List<StockAllocation> get() = legacyAllocations
        ?: throw SalesRuleViolation(SalesRule.UNSUPPORTED_STOCK_TIMING)
    public val normalized: Boolean get() = normalizedLines != null
    public val skuIds: Set<DomainId> get() = normalizedLines?.map { it.skuId }?.toSet()
        ?: allocations.map { it.position.pool.skuId }.toSet()
    init {
        requireSales((legacyAllocations == null) != (normalizedLines == null), SalesRule.STOCK_PROOF_MISMATCH)
        if (legacyAllocations != null) {
            requireSales(legacyAllocations.isNotEmpty(), SalesRule.STOCK_PROOF_MISMATCH)
            requireSales(legacyAllocations.map { it.allocationId }.distinct().size == legacyAllocations.size,
                SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        }
        if (normalizedLines != null) requireSales(normalizedLines.isNotEmpty() &&
            normalizedLines.map { it.portionId }.distinct().size == normalizedLines.size, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
    }
    public fun sameSource(other: RetailStockRequirements): Boolean = when {
        normalized != other.normalized -> false
        normalized -> normalizedLines == other.normalizedLines
        else -> allocations.associateBy { it.allocationId } == other.allocations.associateBy { it.allocationId }
    }
    internal fun validate(scope: BranchScope, quote: PricingSnapshot) {
        requireSales(quote.lines.isNotEmpty() && quote.lines.size <= 256, SalesRule.PAYLOAD_TOO_LARGE)
        requireSales(quote.lines.map { it.portionId }.distinct().size == quote.lines.size, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (normalizedLines != null) {
            requireSales(normalizedLines.map { it.portionId } == quote.lines.map { it.portionId }, SalesRule.STOCK_PROOF_MISMATCH)
            for ((line, requirement) in quote.lines.zip(normalizedLines))
                requireSales(line.quantity == requirement.quantity && requirement.quantity.scaled > 0, SalesRule.STOCK_PROOF_MISMATCH)
        } else {
            val byPortion = allocations.groupBy { it.lineId }
            requireSales(byPortion.keys == quote.lines.map { it.portionId }.toSet(), SalesRule.STOCK_PROOF_MISMATCH)
            requireSales(allocations.all { it.position.pool.scope == InventoryScope(scope.companyId, scope.branchId) }, SalesRule.WRONG_BRANCH)
            for (line in quote.lines) {
                val members = byPortion.getValue(line.portionId)
                requireSales(members.all { it.quantity.unitId == line.quantity.unitId && it.quantity.scale == line.quantity.scale }, SalesRule.UNSUPPORTED_STOCK_TIMING)
                val sum = members.fold(Quantity(line.quantity.unitId, 0, line.quantity.scale)) { total, allocation -> total + allocation.quantity }
                requireSales(sum == line.quantity && sum.scaled > 0, SalesRule.STOCK_PROOF_MISMATCH)
            }
        }
        quote.payable.requireNonnegative()
        requireSales(quote.components.isNotEmpty() && quote.components.size <= 1_024, SalesRule.PAYLOAD_TOO_LARGE)
        val componentsTotal = quote.components.fold(quote.payable.zero()) { total, component ->
            sameCurrency(quote.payable, component.posted); total + component.posted.requireNonnegative()
        }
        requireSales(componentsTotal == quote.payable, SalesRule.INVALID_AMOUNT)
    }
    public companion object {
        /** Only the explicit normalized quote route chooses this version; never rekey old work. */
        public fun normalized(lines: List<RetailStockDemandV2>): RetailStockRequirements = RetailStockRequirements(null, lines)
    }
}

public data class HandoffOffer internal constructor(
    public val id: DomainId,
    public val orderId: DomainId,
    public val previousOwnerId: DomainId,
    public val intendedRecipientId: DomainId,
    public val offeredAtRevision: Revision,
    public val evidence: CashierEvidence,
)

public class OwnedOrder internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val originalCreatorId: DomainId,
    public val ownerId: DomainId,
    public val revision: Revision,
    public val quote: PricingSnapshot,
    public val stockRequirements: RetailStockRequirements,
    public val handoff: HandoffOffer? = null,
    public val collectionId: DomainId? = null,
    public val paidSaleId: DomainId? = null,
    public val priceSourceReference: SalesPriceSourceReference? = null,
    public val returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
) {
    internal fun update(
        ownerId: DomainId = this.ownerId,
        quote: PricingSnapshot = this.quote,
        stockRequirements: RetailStockRequirements = this.stockRequirements,
        handoff: HandoffOffer? = this.handoff,
        collectionId: DomainId? = this.collectionId,
        paidSaleId: DomainId? = this.paidSaleId,
        priceSourceReference: SalesPriceSourceReference? = this.priceSourceReference,
        returnPolicySourceReference: SalesReturnPolicySourceReference? = this.returnPolicySourceReference,
    ): OwnedOrder = OwnedOrder(id, scope, originalCreatorId, ownerId, revision.next(), quote,
        stockRequirements, handoff, collectionId, paidSaleId, priceSourceReference, returnPolicySourceReference)
}

public data class OrderChanged(public val order: OwnedOrder) : SalesEffect
public data class HandoffOffered(public val handoff: HandoffOffer) : SalesEffect
public data class HandoffCancelled(public val handoff: HandoffOffer, public val actorId: DomainId) : SalesEffect
public data class HandoffAccepted(
    public val handoff: HandoffOffer,
    public val acceptance: CashierEvidence,
) : SalesEffect

/** Immutable manager facts, not an ordinary recipient acceptance or recipient authority. */
public data class OrderReassigned(
    public val scope: BranchScope,
    public val operationId: OperationId,
    public val orderId: DomainId,
    public val beforeRevision: Revision,
    public val afterRevision: Revision,
    public val managerId: DomainId,
    public val previousOwnerId: DomainId,
    public val recipientId: DomainId,
    public val reasonCode: String,
    public val evidence: CashierEvidence,
    public val recordedAt: Instant,
) : SalesEffect

public object Orders {
    public fun create(
        command: SalesCommand,
        scope: BranchScope,
        orderId: DomainId,
        quote: PricingSnapshot,
        stockRequirements: RetailStockRequirements,
        priceSourceReference: SalesPriceSourceReference? = null,
        returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
    ): SalesTransition<OwnedOrder> {
        val actor = authorize(command, "order.create", scope, orderId, Revision(0))
        stockRequirements.validate(scope, quote)
        requireSales(returnPolicySourceReference == null || stockRequirements.normalized && priceSourceReference != null, SalesRule.INVALID_STATE)
        val order = OwnedOrder(orderId, scope, actor, actor, Revision(0), quote, stockRequirements,
            priceSourceReference = priceSourceReference, returnPolicySourceReference = returnPolicySourceReference)
        return SalesTransition(command, order, emptyList(), listOf(SalesUniqueCheck(SalesUniqueKind.ORDER, orderId)), listOf(OrderChanged(order)))
    }

    /** Direct-ID check only. Indexed lists/subscriptions/cache invalidation remain gateway work. */
    public fun readOwn(command: SalesCommand, order: OwnedOrder, expectedRevision: Revision): OwnedOrder {
        requireRevision(order.revision, expectedRevision)
        val actor = authorize(command, "order.read", order.scope, order.id, expectedRevision)
        requireSales(actor == order.ownerId, SalesRule.OWNER_REQUIRED)
        return order
    }

    public fun replaceUnlockedQuote(
        command: SalesCommand,
        order: OwnedOrder,
        expectedRevision: Revision,
        quote: PricingSnapshot,
        stockRequirements: RetailStockRequirements,
        priceSourceReference: SalesPriceSourceReference? = null,
        returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
    ): SalesTransition<OwnedOrder> {
        requireOrderActor(command, "order.edit", order, expectedRevision)
        requireEditable(order)
        requireSales(order.handoff == null, SalesRule.HANDOFF_CONFLICT)
        stockRequirements.validate(order.scope, quote)
        requireSales(order.priceSourceReference == null || priceSourceReference != null, SalesRule.INVALID_STATE)
        requireSales(returnPolicySourceReference == null || stockRequirements.normalized && priceSourceReference != null, SalesRule.INVALID_STATE)
        val after = order.update(quote = quote, stockRequirements = stockRequirements, priceSourceReference = priceSourceReference,
            returnPolicySourceReference = returnPolicySourceReference)
        return orderTransition(command, order, after)
    }

    public fun offerHandoff(
        command: SalesCommand,
        order: OwnedOrder,
        expectedRevision: Revision,
        handoffId: DomainId,
        intendedRecipientId: DomainId,
        evidence: CashierEvidence,
    ): SalesTransition<OwnedOrder> {
        val actor = requireOrderActor(command, "order.handoff", order, expectedRevision)
        requireEditable(order)
        evidence.requireActor(actor)
        requireSales(order.handoff == null && intendedRecipientId != actor, SalesRule.HANDOFF_CONFLICT)
        val offer = HandoffOffer(handoffId, order.id, actor, intendedRecipientId, order.revision.next(), evidence)
        val after = order.update(handoff = offer)
        return SalesTransition(command, after, listOf(orderVersion(order)), listOf(SalesUniqueCheck(SalesUniqueKind.HANDOFF_OFFER, handoffId)),
            listOf(OrderChanged(after), HandoffOffered(offer)))
    }

    public fun acceptHandoff(
        command: SalesCommand,
        order: OwnedOrder,
        expectedRevision: Revision,
        handoffId: DomainId,
        evidence: CashierEvidence,
    ): SalesTransition<OwnedOrder> {
        requireRevision(order.revision, expectedRevision)
        val actor = authorize(command, "order.handoff", order.scope, order.id, expectedRevision)
        requireEditable(order)
        val offer = order.handoff ?: throw SalesRuleViolation(SalesRule.HANDOFF_CONFLICT)
        requireSales(offer.id == handoffId && offer.intendedRecipientId == actor && offer.previousOwnerId == order.ownerId &&
            offer.offeredAtRevision == expectedRevision, SalesRule.HANDOFF_CONFLICT)
        evidence.requireActor(actor)
        val after = order.update(ownerId = actor, handoff = null)
        return SalesTransition(command, after, listOf(orderVersion(order)),
            listOf(SalesUniqueCheck(SalesUniqueKind.HANDOFF_ACCEPTANCE, handoffId)), listOf(OrderChanged(after), HandoffAccepted(offer, evidence)))
    }

    public fun cancelHandoff(command: SalesCommand, order: OwnedOrder, expectedRevision: Revision, handoffId: DomainId): SalesTransition<OwnedOrder> {
        val actor = requireOrderActor(command, "order.handoff", order, expectedRevision)
        requireEditable(order)
        requireSales(order.handoff?.id == handoffId, SalesRule.HANDOFF_CONFLICT)
        val after = order.update(handoff = null)
        return SalesTransition(command, after, listOf(orderVersion(order)), emptyList(),
            listOf(OrderChanged(after), HandoffCancelled(checkNotNull(order.handoff), actor)))
    }

    /**
     * Manager-only normalized unpaid transfer. The authoritative store must independently
     * prove stored clothing/supermarket activity, current receiving eligibility, both work
     * indexes and retained original authority.
     * This pure proposal never logs in as the absent owner or recipient or retargets cash.
     */
    public fun reassign(
        command: SalesCommand,
        order: OwnedOrder,
        expectedRevision: Revision,
        previousOwnerId: DomainId,
        recipientId: DomainId,
        evidence: CashierEvidence,
    ): SalesTransition<OwnedOrder> {
        requireRevision(order.revision, expectedRevision)
        requireReasonCode(command.reasonCode)
        val manager = authorize(command, "order.reassign", order.scope, order.id, expectedRevision)
        requireSales(order.ownerId == previousOwnerId, SalesRule.OWNER_REQUIRED)
        requireSales(order.stockRequirements.normalized && order.priceSourceReference != null, SalesRule.UNSUPPORTED_STOCK_TIMING)
        requireEditable(order)
        requireSales(order.handoff == null && recipientId != previousOwnerId, SalesRule.HANDOFF_CONFLICT)
        evidence.requireActor(manager)
        val after = order.update(ownerId = recipientId)
        val reassignment = OrderReassigned(order.scope, command.operationId, order.id, order.revision, after.revision,
            manager, previousOwnerId, recipientId, command.reasonCode, evidence, command.recordedAt)
        return SalesTransition(command, after, listOf(orderVersion(order)), emptyList(),
            listOf(OrderChanged(after), reassignment))
    }
}

internal fun orderVersion(order: OwnedOrder): SalesVersionCheck = SalesVersionCheck(SalesAggregate.ORDER, order.id, order.revision)

internal fun requireOrderActor(command: SalesCommand, action: String, order: OwnedOrder, expectedRevision: Revision, amount: com.ukkera.pos.types.Money? = null): DomainId {
    requireRevision(order.revision, expectedRevision)
    val actor = authorize(command, action, order.scope, order.id, expectedRevision, amount)
    requireSales(actor == order.ownerId, SalesRule.OWNER_REQUIRED)
    return actor
}

private fun requireEditable(order: OwnedOrder) {
    requireSales(order.collectionId == null, SalesRule.PAYABLE_LOCKED)
    requireSales(order.paidSaleId == null, SalesRule.IMMUTABLE_RECORD)
}

private fun orderTransition(command: SalesCommand, before: OwnedOrder, after: OwnedOrder): SalesTransition<OwnedOrder> =
    SalesTransition(command, after, listOf(orderVersion(before)), emptyList(), listOf(OrderChanged(after)))

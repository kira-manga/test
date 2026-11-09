package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

public object LocalPurchasingReceivingProfileV1 {
    public val SOURCE: ReadKey = ReadKey("local.purchasing.receive-source", 1)
}

@Serializable
public data class LocalPurchasingReceiveSourceQueryV1(
    public val id: DomainId? = null, public val after: DomainId? = null, public val limit: Int = 5,
) { init { require(limit in 1..5 && (id == null || after == null)) } }

/** There are deliberately NO money, prices, tax, ceiling, opaque source envelopes or financial references. */
@Serializable
public data class LocalPurchasingReceiveAllocationV1(
    public val allocationId: DomainId, public val skuId: DomainId, public val warehouseId: DomainId,
    public val originalUnits: PurchaseUnitsV1, public val orderedPurchaseQuantity: Quantity, public val orderedBaseQuantity: Quantity,
    public val committedQuantity: Quantity, public val acceptedQuantity: Quantity, public val pendingInspectionQuantity: Quantity,
    public val remainingQuantity: Quantity, public val allowanceStorageRevision: Revision?,
    public val itemStorageRevision: Revision?, public val skuRevision: Revision?, public val tracking: LocalTrackingV1?,
    public val requiresExpiry: Boolean?, public val inspectionRequired: Boolean,
    public val currentItemMatches: Boolean, public val currentWarehouseActive: Boolean, public val valuationLineageReady: Boolean,
)

@Serializable
public data class LocalPurchasingReceiveOrderV1(
    public val orderId: DomainId, public val orderStorageRevision: Revision, public val basisRevision: Revision,
    public val supplierId: DomainId, public val validFrom: UtcInstant, public val validUntil: UtcInstant?,
    /** Headers only on list pages. Exact-ID reads hydrate the actual quantities, sources and item revisions. */
    public val allocations: List<LocalPurchasingReceiveAllocationV1>,
)

@Serializable
public data class LocalPurchasingReceiveSourceV1(
    public val orders: List<LocalPurchasingReceiveOrderV1>?, public val nextAfter: DomainId?,
    public val availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.AVAILABLE,
) {
    init {
        if (availability == LocalPurchasingReadAvailabilityV1.AVAILABLE) require(orders != null)
        else require(orders == null && nextAfter == null)
    }
}

public object LocalPurchasingReceivingCodecV1 {
    public fun query(query: LocalPurchasingReceiveSourceQueryV1): StoredBytes =
        purchasingEncode(query, LocalPurchasingReceiveSourceQueryV1.serializer()).also { require(it.size <= 4_096) }
    public fun source(query: LocalPurchasingReceiveSourceQueryV1): ReadRequest =
        ReadRequest(LocalPurchasingReceivingProfileV1.SOURCE, this.query(query))
    public fun decodeSource(bytes: StoredBytes): LocalPurchasingReceiveSourceV1 {
        require(bytes.size <= 65_536)
        return purchasingDecode(bytes, LocalPurchasingReceiveSourceV1.serializer())
    }
    internal fun decodeQuery(bytes: StoredBytes): LocalPurchasingReceiveSourceQueryV1 {
        require(bytes.size <= 4_096)
        return purchasingDecode(bytes, LocalPurchasingReceiveSourceQueryV1.serializer())
    }
}

/**
 * The real source adapter, installed once in the SAME normalized stock repository as sales and receiving.
 * Internal cost-bearing data never goes through the clerk's query/intent/result. Existing retained-PO
 * parsing and complete-producer verification are reused, not reimplemented in stock or trusted to a DTO.
 */
public class LocalPurchasingReceivingAuthority(private val configuration: LocalConfigurationRepository) : LocalStockPurchaseOrderReaderV1 {
    /** Literal retained PO + basis, joined to its actual complete original approval. Never
     * construct a historical CommandReadView or ask current catalog data to impersonate it. */
    internal suspend fun retainedStockOrder(original: RetainedStreamedCommand, id: DomainId): LocalStockApprovedPurchaseOrderV1 {
        val row = stockReceivingCurrent(original, purchasingKey(LocalPurchasingProfile.ORDER, id))
        val record = decodePurchasingRecord(row.payload)
        check(record.scope == original.identity.scope && record.kind == LocalPurchasingProfile.ORDER && record.id == id &&
            record.revision == row.revision && row.schema == LocalPurchasingProfile.schema(record.kind))
        val order = (record.body as? PurchasingBody.Order)?.value ?: error("Retained receiving PO unavailable")
        val approval = (original.original(record.producer.operationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("Whole original PO approval unavailable")
        requirePurchasingProducer(approval)
        check(approval.command == LocalPurchasingProfile.APPROVE_ORDER && approval.scope == original.identity.scope &&
            approval.operationId != original.identity.producer.operationId && approval.recordedAt <= original.identity.recordedAt &&
            (approval.assignmentId != original.identity.assignmentId || approval.authorityEpoch != original.identity.authorityEpoch || approval.sequence < original.identity.sequence))
        val write = approval.aggregateWrites.single { it.key == row.key }
        check(write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest &&
            record.producer == PurchasingProducer(approval.operationId, approval.originalActorId, approval.command.name, approval.semanticDigest.value))
        val basisRow = stockReceivingCurrent(original, LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, id))
        val originalBasis = approval.aggregateWrites.single { it.key == basisRow.key }
        check(originalBasis.storageGeneration == basisRow.revision && originalBasis.schema == basisRow.schema &&
            originalBasis.payload == basisRow.payload && originalBasis.payloadDigest == basisRow.digest)
        val basis = configuration.retainedStockBasis(original, id)
        val source = LocalStockApprovedPurchaseOrderV1(order, LocalStockSourceStamp(row.key, row.revision, row.digest), basis, approval)
        // Stock compares the exact whole-approval reference and each permanent claim observation.
        return source
    }

    override suspend fun order(view: CommandReadView, id: DomainId): LocalStockApprovedPurchaseOrderV1? = try {
        val reader = PurchasingReader(view)
        val row = reader.order(id)
        if (row == null) {
            check(reader.row(LocalPurchasingProfile.ORDER_MATCHES, id) == null) { "Purchasing index lost its order" }
            null
        } else {
            val order = (row.value.body as PurchasingBody.Order).value
            val approval = view.retainedCommand(row.value.producer.operationId) ?: error("Purchasing approval unavailable")
            requirePurchasingProducer(approval)
            val original = approval.aggregateWrites.single { it.key == row.row.key }
            check(original.storageGeneration == row.row.revision && original.payload == row.row.payload && original.payloadDigest == row.row.digest)
            val basis = configuration.receivingBasis(view, id) ?: error("Purchasing receiving basis unavailable")
            LocalStockApprovedPurchaseOrderV1(order, LocalStockSourceStamp(row.row.key, row.row.revision, row.row.digest), basis, approval)
        }
    } catch (_: PurchasingHydrationCapacityUnavailable) {
        // Only this typed budget signal is translated. Bad stored bytes/provenance remain uncertainty.
        throw LocalStockHydrationCapacityUnavailable()
    }
}

internal class PurchasingReceiveSourceRead(
    private val configuration: LocalConfigurationRepository, private val stock: LocalStockRepository,
) : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingReceivingProfileV1.SOURCE
    override fun normalize(input: StoredBytes): StoredBytes = LocalPurchasingReceivingCodecV1.query(LocalPurchasingReceivingCodecV1.decodeQuery(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = LocalPurchasingReceivingCodecV1.decodeQuery(normalized)
        val reader = PurchasingReader(view)
        val page = if (query.id == null) view.aggregateKeys(LocalPurchasingProfile.ORDER, query.after, query.limit) else null
        val keys = page?.keys ?: listOf(purchasingKey(LocalPurchasingProfile.ORDER, requireNotNull(query.id)))
        check(keys.size <= query.limit && keys.distinct().size == keys.size && keys.all { it.kind == LocalPurchasingProfile.ORDER })
        // At most five immutable bounded roots, before the optional stock dependency closure.
        val selected = keys.mapNotNull { selectedKey ->
            reader.row(LocalPurchasingProfile.ORDER, selectedKey.id).also { check(it != null || page == null) }
        }
        val bindings = selected.map { it.row.key.id to it.row.revision }.ifEmpty { listOf((query.id ?: view.scope.branchId) to Revision(0)) }
        val projection = try {
            if (query.id != null && selected.isEmpty()) check(reader.row(LocalPurchasingProfile.ORDER_MATCHES, query.id) == null) {
                "Purchasing index lost its order"
            }
            val orders = selected.map { selectedRow ->
                val row = reader.order(selectedRow.row.key.id) ?: error("Selected purchasing order unavailable")
                val order = (row.value.body as PurchasingBody.Order).value
                LocalPurchasingReceiveOrderV1(order.id, row.row.revision, Revision(0), order.supplierId, order.validFrom, order.validUntil,
                    if (query.id == null) emptyList() else allocations(view, order))
            }
            LocalPurchasingReceiveSourceV1(orders, page?.nextAfter)
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            unavailableReceiveSource()
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            unavailableReceiveSource()
        }
        val projectedBytes = purchasingEncode(projection, LocalPurchasingReceiveSourceV1.serializer())
        // This is an owned output budget, not corrupt storage and not a partially returned page.
        val encoded = if (projectedBytes.size <= 65_536) projectedBytes
            else purchasingEncode(unavailableReceiveSource(), LocalPurchasingReceiveSourceV1.serializer())
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = bindings.distinct().flatMap { (id, revision) ->
                listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ).map { action ->
                    AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
                        ActionBinding(id, revision, context.semanticDigest, reasonCode = "purchasing.receive-source"))
                }
            }
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (auth, required) ->
                    auth.principalKind == PrincipalKind.LOCAL_EMPLOYEE && auth.isBoundTo(required, context.actorId)
                })
                return encoded
            }
        }
    }

    private suspend fun allocations(view: CommandReadView, order: PurchaseOrderV1): List<LocalPurchasingReceiveAllocationV1> {
        val itemIds = order.lines.map { it.goods.skuId }.distinct()
        val cut = stock.observe(view, itemIds)
        val versions = cut.items.associate { it.skuId to it.storageRevision }
        val items = itemIds.associateWith { configuration.item(view, it) }
        val warehouses = order.lines.map { it.warehouseId }.distinct().associateWith { configuration.warehouse(view, it) }
        return order.lines.map { line ->
            val full = line.goods.baseQuantity()
            val zero = full.copy(scaled = 0)
            val allowance = stock.purchaseAllowanceFacts(view, line.allocationId)
            if (allowance != null) check(allowance.basisId == order.id && allowance.supplierId == order.supplierId &&
                allowance.skuId == line.goods.skuId && allowance.warehouseId == line.warehouseId &&
                allowance.committedQuantity.unitId == full.unitId && allowance.committedQuantity.scale == full.scale &&
                allowance.committedQuantity <= full && allowance.committedOwnedValue.minor <= line.goods.net.minor)
            val committed = allowance?.committedQuantity ?: zero
            val item = items[line.goods.skuId]
            val itemMatches = item != null && item.scope == view.scope && item.active && item.canPurchase && item.tracking != StockTracking.NONE &&
                item.productId == line.goods.units.productId && item.baseUnitId == full.unitId && item.quantityScale == full.scale
            LocalPurchasingReceiveAllocationV1(line.allocationId, line.goods.skuId, line.warehouseId, line.goods.units,
                line.goods.quantity, full, committed, allowance?.acceptedQuantity ?: zero, allowance?.pendingInspectionQuantity ?: zero,
                full - committed, allowance?.source?.storageRevision, versions.getValue(line.goods.skuId), item?.skuRevision,
                item?.let { LocalTrackingV1.valueOf(it.tracking.name) }, item?.requiresExpiry, line.inspectionRequired,
                itemMatches, warehouses[line.warehouseId]?.let { it.scope == view.scope && it.active } == true,
                allowance == null || allowance.committedOwnedValue == purchaseCumulativeAmount(line.goods.net, committed, full))
        }
    }
}

private fun unavailableReceiveSource(): LocalPurchasingReceiveSourceV1 = LocalPurchasingReceiveSourceV1(null, null,
    LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE)

/** Legacy UOM checks remain literal. The new profile records real BASE units and retains the PO's original conversion separately. */
internal fun purchasingReceiptUnitsAgree(original: PurchaseOrderLineV1, receipt: LocalPurchaseReceiptFacts): Boolean =
    receipt.baseQuantity.unitId == original.goods.units.baseUnitId && receipt.baseQuantity.scale == original.goods.units.baseScale &&
        (if (receipt.poValuation == null) receipt.purchaseQuantity.unitId == original.goods.units.purchaseUnitId && receipt.conversion == original.goods.units.conversion()
        else receipt.conversion == null && receipt.purchaseQuantity == receipt.baseQuantity)

/** The actual immutable order row/whole producer is authoritative, not the snapshot's asserted digest. */
internal fun purchasingReceiptOrderSourceAgrees(order: PurchasingRow, proof: LocalPoReceiptLineValuationV1?): Boolean =
    proof == null || proof.orderSource.let { source ->
        source.orderId == order.row.key.id && source.storageRevision == order.row.revision && source.digest == order.row.digest.value &&
            source.approvalOperationId == order.value.producer.operationId && order.row.key.kind == LocalPurchasingProfile.ORDER &&
            order.value.producer.command == LocalPurchasingProfile.APPROVE_ORDER.name
    }

internal fun purchasingReceiptValueAgrees(order: PurchaseOrderV1, original: PurchaseOrderLineV1, receipt: LocalPurchaseReceiptFacts): Boolean =
    purchasingReceiptValueAgrees(order, original, receipt.receiptId, receipt.lineId, receipt.sourceId, receipt.baseQuantity,
        receipt.originalOwnedValue, receipt.poValuation)

internal fun purchasingReceiptValueAgrees(order: PurchaseOrderV1, original: PurchaseOrderLineV1, receipt: PurchasingReceiptSnapshot): Boolean =
    purchasingReceiptValueAgrees(order, original, receipt.receiptId, receipt.lineId, receipt.sourceId, receipt.originalQuantity,
        receipt.originalOwnedValue, receipt.poValuation)

private fun purchasingReceiptValueAgrees(
    order: PurchaseOrderV1, original: PurchaseOrderLineV1, receiptId: DomainId, lineId: DomainId, sourceId: DomainId,
    quantity: Quantity, value: Money?, proof: LocalPoReceiptLineValuationV1?,
): Boolean {
    val full = original.goods.baseQuantity()
    if (value == null || quantity.scaled <= 0 || quantity.unitId != full.unitId || quantity.scale != full.scale) return false
    return if (proof == null) ExactRational.ratio(value.minor, quantity.scaled) == ExactRational.ratio(original.goods.net.minor, full.scaled)
        else proof.agreesWith(order, original, receiptId, lineId, sourceId, quantity, value)
}

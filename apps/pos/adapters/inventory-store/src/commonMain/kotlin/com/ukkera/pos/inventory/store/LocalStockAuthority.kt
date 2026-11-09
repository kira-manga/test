package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** An independently read current aggregate binding, not a signature or a publication ceremony. */
public data class LocalStockSourceStamp(
    public val key: AggregateKey,
    public val storageRevision: Revision,
    public val digest: PayloadDigest,
)

/**
 * Transaction-repository input. Never decode this type from an employee command. The composition
 * owner establishes publication, complete current policy and branch activation before returning it.
 * The stock adapter additionally re-reads every stamp in the same borrowed transaction.
 */
public class LocalStockItemAuthority(
    public val scope: BranchScope,
    public val skuId: DomainId,
    public val productId: DomainId,
    public val catalogRevision: Revision,
    public val skuRevision: Revision,
    public val baseUnitId: DomainId,
    public val quantityScale: Int,
    public val tracking: StockTracking,
    public val requiresExpiry: Boolean,
    public val canPurchase: Boolean,
    public val active: Boolean,
    conversions: List<ProductUnitConversion>,
    sources: List<LocalStockSourceStamp>,
    unitDefinitions: List<CatalogUnit>? = null,
) {
    init {
        require(quantityScale in 0..6)
        require(conversions.size <= 64 && conversions.map { it.reference }.distinct().size == conversions.size)
        require(conversions.all { it.productId == productId && it.targetUnitId == baseUnitId })
        require(sources.isNotEmpty() && sources.size <= 16 && sources.map { it.key }.distinct().size == sources.size)
        require(unitDefinitions == null || (unitDefinitions.size in 1..65 &&
            unitDefinitions.map { it.id }.distinct().size == unitDefinitions.size))
    }
    public val conversions: List<ProductUnitConversion> = localStockFrozen(conversions)
    public val sources: List<LocalStockSourceStamp> = localStockFrozen(sources)
    /** Exact signed metadata, not permission. The current catalog reader exposes no legacy
     * conversions without it; retained originals may still carry their old literal snapshots. */
    public val unitDefinitions: List<CatalogUnit>? = unitDefinitions?.let { localStockFrozen(it) }
}

public class LocalStockWarehouseAuthority(
    public val scope: BranchScope,
    public val warehouseId: DomainId,
    public val active: Boolean,
    sources: List<LocalStockSourceStamp>,
) {
    init { require(sources.isNotEmpty() && sources.size <= 16 && sources.map { it.key }.distinct().size == sources.size) }
    public val sources: List<LocalStockSourceStamp> = localStockFrozen(sources)
}

public enum class LocalStockBasisKind { OPENING, LOCAL_PURCHASE, PURCHASE_ORDER_ALLOCATION }

/**
 * A stable branch allocation. An amended approval retains its allocation ID; consuming another
 * revision does not replenish its quantity/value. Supplier replacement allowances need new,
 * explicitly approved allocations. Unbounded purchasing authority is not represented.
 */
public data class LocalStockApprovedLine(
    public val allocationId: DomainId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val maximumBaseQuantity: Quantity,
    public val maximumOwnedValue: Money,
    public val inspectionRequired: Boolean,
) {
    init {
        require(maximumBaseQuantity.scaled > 0)
        require(maximumOwnedValue.currency == Currency.EGP && maximumOwnedValue.minor >= 0)
    }
}

/**
 * Already activated authority, not a purchase request or an inference from an absent receipt.
 * Supplier invoices, settlements, company-shared budgets and undispatched allocations are not
 * created by this input. The source adapter must check supplier/category/delegation/approval rules.
 */
public class LocalStockReceivingBasis(
    public val scope: BranchScope,
    public val basisId: DomainId,
    public val revision: Revision,
    public val kind: LocalStockBasisKind,
    public val supplierId: DomainId?,
    public val approvedBy: DomainId,
    public val policyId: DomainId,
    public val validFrom: UtcInstant,
    public val validUntil: UtcInstant?,
    lines: List<LocalStockApprovedLine>,
    sources: List<LocalStockSourceStamp>,
) {
    init {
        require((kind == LocalStockBasisKind.OPENING) == (supplierId == null))
        require(validUntil == null || validUntil > validFrom)
        require(lines.isNotEmpty() && lines.size <= 128 && lines.map { it.allocationId }.distinct().size == lines.size)
        require(sources.isNotEmpty() && sources.size <= 16 && sources.map { it.key }.distinct().size == sources.size)
    }
    public val lines: List<LocalStockApprovedLine> = localStockFrozen(lines)
    public val sources: List<LocalStockSourceStamp> = localStockFrozen(sources)
}

/**
 * One activated, exact count/value approval. It is neither a receiving allowance nor a request
 * to price found units from the current moving average. The configuration owner must establish
 * the approving actor's current, value-bound INVENTORY_ADJUST authority before publishing it.
 */
public class LocalCountSurplusBasis(
    public val scope: BranchScope,
    public val basisId: DomainId,
    public val revision: Revision,
    public val countId: DomainId,
    public val countRevision: Revision,
    public val capturedItemRevision: Revision,
    public val position: LocalStockPositionRef,
    public val capturedQuantity: Quantity,
    public val countedQuantity: Quantity,
    /** Approved value of ONLY countedQuantity - capturedQuantity, not the whole closing count. */
    public val surplusValue: Money,
    public val approvedBy: DomainId,
    public val policyId: DomainId,
    public val validFrom: UtcInstant,
    public val validUntil: UtcInstant?,
    sources: List<LocalStockSourceStamp>,
) {
    init {
        require(countRevision.value >= 1 && capturedItemRevision.value >= 1)
        require(position.supplierId == null && position.custody != com.ukkera.pos.inventory.StockCustody.REJECTED_HELD && position.serialId == null)
        require(capturedQuantity.unitId == countedQuantity.unitId && capturedQuantity.scale == countedQuantity.scale &&
            capturedQuantity.scaled >= 0 && countedQuantity > capturedQuantity)
        require(surplusValue.currency == Currency.EGP && surplusValue.minor >= 0)
        require(validUntil == null || validUntil > validFrom)
        require(sources.isNotEmpty() && sources.size <= 16 && sources.map { it.key }.distinct().size == sources.size)
    }
    public val sources: List<LocalStockSourceStamp> = localStockFrozen(sources)
}

public enum class LocalStockCountApprovalStatus { OPEN, POSTED, RECOUNT_REQUIRED }

/**
 * Qualified exact company serial identity activated for this branch by trusted composition.
 * May originate in a signed registration or allocated namespace; a cashier assertion is neither.
 * Validity limits acceptance of this qualification, not permission to invent a new receipt.
 */
public class LocalStockSerialRegistration(
    public val scope: BranchScope,
    public val serialId: DomainId,
    public val skuId: DomainId,
    public val serialCode: String,
    public val registrationId: DomainId,
    public val revision: Revision,
    public val issuerId: DomainId,
    public val provenanceId: DomainId,
    public val validFrom: UtcInstant,
    public val validUntil: UtcInstant?,
    sources: List<LocalStockSourceStamp>,
) {
    init {
        requireStockSerialCode(serialCode)
        require(revision.value >= 1 && (validUntil == null || validUntil > validFrom))
        require(sources.isNotEmpty() && sources.size <= 16 && sources.map { it.key }.distinct().size == sources.size)
    }
    public val sources: List<LocalStockSourceStamp> = localStockFrozen(sources)
}

/**
 * Literal current facts for an independently authorized approval producer, not an approval or a
 * reusable stock proof. Sources are ORIGINAL stock-input witnesses; do not relabel them as the
 * later approval's current policy frontier. Recovery resources cover the entire loaded closure.
 */
public class LocalStockCountApprovalSnapshot internal constructor(
    public val scope: BranchScope,
    public val countId: DomainId,
    public val countRevision: Revision,
    public val position: LocalStockPositionRef,
    public val capturedItemRevision: Revision,
    public val capturedQuantity: Quantity,
    public val capturedBy: DomainId,
    public val capturedAt: UtcInstant,
    public val currentItemRevision: Revision,
    public val currentQuantity: Quantity,
    public val status: LocalStockCountApprovalStatus,
    sources: List<LocalStockSourceStamp>,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    public val sources: List<LocalStockSourceStamp> = localStockFrozen(sources)
    private val resources: Set<RecoveryResourceKey> = recoveryResources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()
}

/**
 * Trusted process-composition port, not a remotely supplied verifier. Reads must use this view,
 * not cached sign-in claims or a central call. A pending/unavailable approval returns null. Storage
 * or key failures must throw, rather than being reinterpreted as a new supplier, item or allowance.
 */
public interface LocalStockAuthorityReader {
    public suspend fun item(view: CommandReadView, skuId: DomainId): LocalStockItemAuthority?
    public suspend fun warehouse(view: CommandReadView, warehouseId: DomainId): LocalStockWarehouseAuthority?
    public suspend fun receivingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis?
    public suspend fun openingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis?
    public suspend fun countSurplusBasis(view: CommandReadView, basisId: DomainId): LocalCountSurplusBasis? = null
    public suspend fun serialRegistration(view: CommandReadView, serialId: DomainId): LocalStockSerialRegistration? = null
}

/** Purpose-bound historical receiving source reader. Implementations decode the ACTUAL literal
 * retained inputs and full PO approval on original's owner loan; no fake CommandReadView, current
 * configuration re-read, caller DTO, accepting boolean or reusable authority capability. */
public interface LocalStockRelationalAuthorityReader : LocalStockAuthorityReader {
    public suspend fun currentPurchaseOrder(view: CommandReadView, orderId: DomainId): LocalStockApprovedPurchaseOrderV1?
    public suspend fun retainedItem(original: com.ukkera.pos.database.RetainedStreamedCommand, skuId: DomainId): LocalStockItemAuthority
    public suspend fun retainedWarehouse(original: com.ukkera.pos.database.RetainedStreamedCommand, warehouseId: DomainId): LocalStockWarehouseAuthority
    public suspend fun retainedBasis(original: com.ukkera.pos.database.RetainedStreamedCommand, basisId: DomainId): LocalStockReceivingBasis
    public suspend fun retainedSerialRegistration(original: com.ukkera.pos.database.RetainedStreamedCommand, serialId: DomainId): LocalStockSerialRegistration?
    public suspend fun retainedCountSurplus(original: com.ukkera.pos.database.RetainedStreamedCommand, basisId: DomainId): LocalCountSurplusBasis
    public suspend fun retainedPurchaseOrder(original: com.ukkera.pos.database.RetainedStreamedCommand, orderId: DomainId): LocalStockApprovedPurchaseOrderV1
    /** Expected exact permanent ownership keys derived from the real typed basis/PO, not grants.
     * Writer reads every key on its actual loan; producer compares literal before observations. */
    public fun receivingClaims(basis: LocalStockReceivingBasis, purchase: LocalStockApprovedPurchaseOrderV1?): List<com.ukkera.pos.database.StreamedSourceObservationV2.Claim>
}

/** Optional REAL serial-registration authority. No implementation/default supplies a qualification. */
public interface LocalStockRelationalSerialAuthority {
    public suspend fun current(view: CommandReadView, serialId: DomainId): LocalStockSerialRegistration?
    public suspend fun retained(original: com.ukkera.pos.database.RetainedStreamedCommand, serialId: DomainId): LocalStockSerialRegistration?
}

/** No fabricated catalog, approval, default warehouse or adjacent-file authority. */
public object UnavailableLocalStockAuthority : LocalStockAuthorityReader {
    override public suspend fun item(view: CommandReadView, skuId: DomainId): LocalStockItemAuthority? = null
    override public suspend fun warehouse(view: CommandReadView, warehouseId: DomainId): LocalStockWarehouseAuthority? = null
    override public suspend fun receivingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis? = null
    override public suspend fun openingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis? = null
    override public suspend fun countSurplusBasis(view: CommandReadView, basisId: DomainId): LocalCountSurplusBasis? = null
}

internal fun <T> localStockFrozen(values: Collection<T>): List<T> = object : AbstractList<T>() {
    private val copied = values.toList()
    override val size: Int get() = copied.size
    override fun get(index: Int): T = copied[index]
}

/** Reuses the installed catalog/warehouse authority; no v2 request can supply these facts. */
internal suspend fun requireStockAllocationAuthority(
    read: LocalStockRelationalRead,
    authority: LocalStockAuthorityReader,
    request: LocalStockAllocationRequest,
): StockAllocationAuthorityV2Wire {
    val item = authority.item(read.view, request.skuId) ?: throw LocalStockRejected(LocalStockFailure.ITEM_UNAVAILABLE)
    val warehouse = authority.warehouse(read.view, request.warehouseId)
        ?: throw LocalStockRejected(LocalStockFailure.WAREHOUSE_UNAVAILABLE)
    localStockCheck(item.scope == read.scope && item.skuId == request.skuId && warehouse.scope == read.scope &&
        warehouse.warehouseId == request.warehouseId, LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(item.active && item.tracking != StockTracking.NONE, LocalStockFailure.ITEM_UNAVAILABLE)
    localStockCheck(warehouse.active, LocalStockFailure.WAREHOUSE_UNAVAILABLE)
    localStockCheck(request.baseQuantity.unitId == item.baseUnitId && request.baseQuantity.scale == item.quantityScale &&
        request.baseQuantity.scaled > 0, LocalStockFailure.INVALID_UNIT)
    if (item.tracking == StockTracking.SERIAL) localStockCheck(
        request.baseQuantity.scaled % stockSerialUnit(request.baseQuantity).scaled == 0L, LocalStockFailure.INVALID_SERIAL)
    val metadata = read.record(LocalStockRelationalProfileV2.itemKey(item.skuId)) ?: missingStock()
    val stored = metadata.body as? StockRelationalBody.Item ?: inconsistentStock()
    localStockCheck(stored.skuId == item.skuId && stored.unitId == item.baseUnitId && stored.scale == item.quantityScale &&
        stored.serialized == (item.tracking == StockTracking.SERIAL), LocalStockFailure.STORAGE_INCONSISTENT)
    requireStockAuthoritySources(read, item.sources)
    requireStockAuthoritySources(read, warehouse.sources)
    return StockAllocationAuthorityV2Wire(item.skuId, item.productId, item.catalogRevision, item.skuRevision,
        item.baseUnitId, item.quantityScale, when (item.tracking) {
            StockTracking.QUANTITY -> 1; StockTracking.LOT -> 2; StockTracking.SERIAL -> 3
            StockTracking.NONE -> inconsistentStock()
        }, item.requiresExpiry, warehouse.warehouseId,
        item.sources.map(StockSourceStampWire::from), warehouse.sources.map(StockSourceStampWire::from))
}

/** Every source is hydrated and retained literally in the SAME owner loan. No fake producer. */
internal suspend fun requireStockAuthoritySources(read: LocalStockRelationalRead, sources: List<LocalStockSourceStamp>) {
    localStockCheck(sources.isNotEmpty() && sources.size <= 16 && sources.map { it.key }.distinct().size == sources.size,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val sink = read.sink ?: inconsistentStock()
    for (stamp in sources) {
        val row = read.access.aggregate(stamp.key) ?: inconsistentStock()
        localStockCheck(row.key.kind !in com.ukkera.pos.database.StockRelationalKindsV2.all && row.revision == stamp.storageRevision &&
            row.digest == stamp.digest && com.ukkera.pos.security.sha256(row.payload.copyBytes()) == stamp.digest,
            LocalStockFailure.STORAGE_INCONSISTENT)
        sink.source(com.ukkera.pos.database.StreamedSourceObservationV2.Current(row.key, row.revision, row.schema, row.payload, row.digest))
        sink.resource(RecoveryResourceKey(com.ukkera.pos.recovery.RecoveryResourceKind.AUTHORIZATION, row.key.id))
    }
}

internal suspend fun requireStockSelectedPosition(
    read: LocalStockRelationalRead,
    repository: LocalStockRepository,
    authority: StockAllocationAuthorityV2Wire,
    position: com.ukkera.pos.inventory.StockPosition,
) {
    val key = position.key
    localStockCheck(key.pool.skuId == authority.skuId && key.pool.warehouseId == authority.warehouseId &&
        position.onHand.unitId == authority.unitId && position.onHand.scale == authority.scale,
        LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck((authority.tracking == 3) == (key.serialId != null) &&
        (authority.tracking != 2 || key.lotId != null) && (!authority.requiresExpiry || position.expiresAtEpochMillis != null),
        LocalStockFailure.STORAGE_INCONSISTENT)
    requireRelationalStockSerialPosition(read, repository, position)
}

internal suspend fun requireRelationalStockSerialPosition(
    read: LocalStockRelationalRead,
    repository: LocalStockRepository,
    position: com.ukkera.pos.inventory.StockPosition,
) {
    val key = position.key
    val serialId = key.serialId
    if (serialId != null) {
        // Qualification validity was checked when accepted, not silently renewed at checkout.
        // The genuine serial origin and permanent code/identity claims remain mandatory.
        val serial = stockSerial(repository, read.view, serialId) ?: inconsistentStock()
        requireStockSerialPosition(serial.value, position)
        // Pending qualification remains quarantined/unavailable, but does not hide other real
        // qualified serials from this COMPLETE SKU range. Do not renew old qualifications here.
        val producer = serial.envelope.producer
        read.sink?.source(com.ukkera.pos.database.StreamedSourceObservationV2.Aggregate(serial.row.key, serial.row.revision,
            serial.row.schema, serial.row.payload, serial.row.digest, com.ukkera.pos.database.StreamedProducerIdentityV2(
                producer.operationId, producer.commandKey(), producer.actorId, PayloadDigest(producer.digest))))
        localStockCheck(read.claim(LocalStockStoreProfile.serialClaim(serialId)) == serialId &&
            read.claim(LocalStockStoreProfile.serialCodeClaim(serial.value.serialCode)) == serialId,
            LocalStockFailure.STORAGE_INCONSISTENT)
        serial.value.registration?.restore()?.let { registration ->
            for (resource in serialRegistrationResources(registration)) read.sink?.resource(resource)
        }
    }
}

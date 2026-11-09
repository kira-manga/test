package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/** One real configuration/PO authority bridge, installed with the SAME stock repository and
 * handler authority. Current and historical reads use production decoders and signature checks;
 * no reusable authority token, fake historical view or alternate stock persistence is created. */
public class LocalStockRelationalAuthority(
    private val configuration: LocalConfigurationRepository,
    private val purchasing: LocalPurchasingReceivingAuthority,
    private val serials: LocalStockRelationalSerialAuthority? = null,
) : LocalStockRelationalAuthorityReader, LocalStockAuthorityReader by configuration {
    override public suspend fun currentPurchaseOrder(view: CommandReadView, orderId: DomainId): LocalStockApprovedPurchaseOrderV1? =
        purchasing.order(view, orderId)
    override public suspend fun serialRegistration(view: CommandReadView, serialId: DomainId): LocalStockSerialRegistration? = serials?.current(view, serialId)
    override public suspend fun retainedItem(original: RetainedStreamedCommand, skuId: DomainId): LocalStockItemAuthority =
        configuration.retainedStockItem(original, skuId)
    override public suspend fun retainedWarehouse(original: RetainedStreamedCommand, warehouseId: DomainId): LocalStockWarehouseAuthority =
        configuration.retainedStockWarehouse(original, warehouseId)
    override public suspend fun retainedBasis(original: RetainedStreamedCommand, basisId: DomainId): LocalStockReceivingBasis =
        configuration.retainedStockBasis(original, basisId)
    override public suspend fun retainedSerialRegistration(original: RetainedStreamedCommand, serialId: DomainId): LocalStockSerialRegistration? =
        serials?.retained(original, serialId)
    override public suspend fun retainedCountSurplus(original: RetainedStreamedCommand, basisId: DomainId): LocalCountSurplusBasis =
        retainedLocalCountSurplusBasis(original, basisId)
    override public suspend fun retainedPurchaseOrder(original: RetainedStreamedCommand, orderId: DomainId): LocalStockApprovedPurchaseOrderV1 =
        purchasing.retainedStockOrder(original, orderId)
    override public fun receivingClaims(basis: LocalStockReceivingBasis, purchase: LocalStockApprovedPurchaseOrderV1?): List<StreamedSourceObservationV2.Claim> {
        val claims = basis.lines.map { StreamedSourceObservationV2.Claim(
            ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${it.allocationId.value}"), basis.basisId) }.toMutableList()
        if (purchase != null) {
            check(purchase.order.id == basis.basisId && purchase.basis.scope == basis.scope)
            claims += StreamedSourceObservationV2.Claim(purchasingClaim(LocalPurchasingProfile.ORDER, purchase.order.id), purchase.order.id)
            claims += StreamedSourceObservationV2.Claim(purchasingReferenceClaim(basis.scope, "order", purchase.order.supplierId, purchase.order.reference), purchase.order.id)
        }
        check(claims.map { it.key }.distinct().size == claims.size)
        return claims
    }
}

/** The actual loan-bound retained frame reader performs a full EOF scan. This helper only
 * materializes ONE exact already-observed Current row; missing/Absent is never an empty approval. */
internal suspend fun stockReceivingCurrent(original: RetainedStreamedCommand, key: AggregateKey): StoredAggregate {
    var found: StreamedSourceObservationV2.Current? = null
    original.frames { frame ->
        val value = (frame as? RetainedCommandFrameV2.Source)?.value
        if (value is StreamedSourceObservationV2.Current && value.key == key) { check(found == null); found = value }
        if (value is StreamedSourceObservationV2.CurrentAbsent && value.key == key) error("Required receiving authority was absent")
    }
    val value = found ?: error("Retained literal receiving authority unavailable")
    check(value.generation.value >= 1 && value.payloadHash == sha256(value.payload.copyBytes()))
    val row = original.sourceRow(key) ?: error("Literal receiving authority source unavailable")
    check(row.key == value.key && row.revision == value.generation && row.schema == value.schema && row.payload == value.payload && row.digest == value.payloadHash)
    return row
}

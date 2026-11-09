package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/**
 * Actual cost-free arrival selections over the SAME branch configuration and borrowed transaction.
 * Signed item metadata is verified by LocalConfigurationRepository; no caller-provided SKU,
 * supplier, serial registration, evidence-policy selection or observation becomes authority.
 *
 * IMPORTANT LOCAL GATE: current configuration has no authoritative required/not-required
 * attachment policy or durable attachment/proof-retention source. Both methods below return
 * unavailable, NOT an optional/accepting policy. Installed arrival commands consequently retain
 * authorized UNSUPPORTED_POLICY rejections with no custody effects. Policy + real local durable
 * attachment integration is required next; installation of this adapter is not workflow readiness.
 */
public class LocalCatalogUnpricedArrivalAuthority(private val configuration: LocalConfigurationRepository) : LocalUnpricedArrivalAuthority {
    override suspend fun supplier(view: CommandReadView, id: DomainId): LocalArrivalSupplierAuthority? {
        val (row, record) = arrivalConfiguration(view, LocalConfigurationProfile.SUPPLIER, id) ?: return null
        val supplier = (record.body as? ConfigurationBody.Supplier)?.value ?: error("Arrival supplier source kind")
        val stamp = row.arrivalStamp()
        // Existing supplier configuration has no permanent ID claim. Do not invent one here.
        return LocalArrivalSupplierAuthority(view.scope,
            LocalArrivalSupplierViewV1(id, row.revision, supplier.names.arrivalNames(), supplier.active), stamp, listOf(stamp))
    }

    override suspend fun item(view: CommandReadView, id: DomainId): LocalArrivalItemAuthority? {
        val actual = configuration.item(view, id) ?: return null
        val (row, record) = arrivalConfiguration(view, LocalConfigurationProfile.SKU, id) ?: error("Arrival SKU source lost")
        val signed = (record.body as? ConfigurationBody.Sku)?.value ?: error("Arrival SKU source kind")
        val value = signed.definition
        check(actual.scope == view.scope && actual.skuId == id && value.skuId == id && actual.sources.contains(row.arrivalStamp()))
        val (publication, published) = arrivalConfiguration(view, LocalConfigurationProfile.PUBLICATION, value.publicationId)
            ?: error("Arrival signed publication unavailable")
        check((published.body as? ConfigurationBody.Sku)?.value == signed)
        for (identity in listOf("sku-code/${value.code}") + value.barcodes.map { "barcode/$it" })
            check(view.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, identity)) == id)
        // Retain the immutable signed publication coordinate without returning prices/signatures.
        val qualified = LocalStockItemAuthority(actual.scope, actual.skuId, actual.productId, actual.catalogRevision,
            actual.skuRevision, actual.baseUnitId, actual.quantityScale, actual.tracking, actual.requiresExpiry,
            actual.canPurchase, actual.active, actual.conversions, (actual.sources + publication.arrivalStamp()).distinct(),
            actual.unitDefinitions)
        return LocalArrivalItemAuthority(qualified, row.arrivalStamp(), value.names.arrivalNames(), value.code)
    }

    override suspend fun warehouse(view: CommandReadView, id: DomainId): LocalArrivalWarehouseAuthority? {
        val actual = configuration.warehouse(view, id) ?: return null
        val (row, record) = arrivalConfiguration(view, LocalConfigurationProfile.WAREHOUSE, id) ?: error("Arrival warehouse source lost")
        val warehouse = (record.body as? ConfigurationBody.Warehouse)?.value ?: error("Arrival warehouse source kind")
        check(actual.scope == view.scope && actual.warehouseId == id && actual.active == warehouse.active && row.arrivalStamp() in actual.sources)
        // The actual warehouse source likewise has no permanent configuration-ID claim.
        return LocalArrivalWarehouseAuthority(actual, row.arrivalStamp(), warehouse.names.arrivalNames())
    }

    override suspend fun serialRegistration(view: CommandReadView, id: DomainId): LocalStockSerialRegistration? =
        configuration.serialRegistration(view, id) // Currently unavailable; never synthesize one from a custody tag/code.

    override suspend fun keys(view: CommandReadView, kind: LocalArrivalSourceKindV1, after: DomainId?, limit: Int): LocalArrivalSourceKeys {
        require(limit in 1..LocalUnpricedArrivalProfileV1.MAX_HEADERS)
        val sourceKind = when (kind) {
            LocalArrivalSourceKindV1.SUPPLIER -> LocalConfigurationProfile.SUPPLIER
            LocalArrivalSourceKindV1.ITEM -> LocalConfigurationProfile.SKU
            LocalArrivalSourceKindV1.WAREHOUSE -> LocalConfigurationProfile.WAREHOUSE
            LocalArrivalSourceKindV1.SERIAL_REGISTRATION -> error("Serial registration requires an exact identity")
        }
        val page = view.aggregateKeys(sourceKind, after, limit)
        check(page.keys.size <= limit && page.keys.all { it.kind == sourceKind } && page.keys.distinct().size == page.keys.size &&
            page.keys == page.keys.sortedBy { it.id.value } && (after == null || page.keys.all { it.id.value > after.value }) &&
            (page.nextAfter == null || page.nextAfter == page.keys.lastOrNull()?.id))
        return LocalArrivalSourceKeys(page.keys.map { it.id }, page.nextAfter)
    }

    override suspend fun policy(view: CommandReadView, supplierId: DomainId): LocalArrivalPolicyAuthority? = null
    override suspend fun attachment(view: CommandReadView, id: DomainId, supplierId: DomainId,
        action: LocalArrivalActionV1): LocalArrivalAttachmentAuthority? = null
}

private suspend fun arrivalConfiguration(view: CommandReadView, kind: String, id: DomainId): Pair<StoredAggregate, ConfigurationRecord>? {
    val key = LocalConfigurationProfile.key(kind, id)
    val row = view.aggregate(key) ?: return null
    check(row.key == key && row.payload.size <= LocalUnpricedArrivalProfileV1.MAX_ROW_BYTES && sha256(row.payload.copyBytes()) == row.digest)
    return row to decodeRecord(view.scope, row)
}
private fun StoredAggregate.arrivalStamp(): LocalStockSourceStamp = LocalStockSourceStamp(key, revision, digest)
private fun LocalNamesV1.arrivalNames(): LocalArrivalNamesV1 = LocalArrivalNamesV1(english, arabic)

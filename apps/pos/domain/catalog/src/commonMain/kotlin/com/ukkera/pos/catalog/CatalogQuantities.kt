package com.ukkera.pos.catalog

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

public enum class CatalogQuantityFailure {
    SKU_UNAVAILABLE,
    UNIT_NOT_ENABLED,
    QUANTITY_NOT_POSITIVE,
    UNSUPPORTED_PRECISION,
    CONVERSION_MISSING_OR_MISMATCHED,
    INEXACT_OR_OVERFLOWING_CONVERSION,
    SERIAL_REQUIRES_WHOLE_BASE_UNITS,
}

public data class CatalogQuantitySnapshot(
    public val quantity: Quantity,
    public val baseQuantity: Quantity,
    public val conversionSnapshot: ProductUnitConversion?,
)

public sealed interface CatalogQuantityResult {
    public data class Resolved(public val snapshot: CatalogQuantitySnapshot) : CatalogQuantityResult
    public data class Rejected(public val reason: CatalogQuantityFailure) : CatalogQuantityResult
}

public object CatalogQuantities {
    /** Only currently declared sale units are selectable. Callers cannot supply a stale pack rate. */
    public fun resolveForSale(
        catalog: ValidatedCompanyCatalog,
        skuId: DomainId,
        quantity: Quantity,
    ): CatalogQuantityResult {
        val sku = catalog.index.skus[skuId]
            ?: return CatalogQuantityResult.Rejected(CatalogQuantityFailure.SKU_UNAVAILABLE)
        if (sku.status != CatalogStatus.ACTIVE || catalog.index.products[sku.productId]?.status != CatalogStatus.ACTIVE ||
            !sku.capabilities.canSell
        ) return CatalogQuantityResult.Rejected(CatalogQuantityFailure.SKU_UNAVAILABLE)
        return resolveSale(catalog.index, sku, quantity)
    }

    internal fun resolveSale(index: CatalogIndex, sku: CatalogSku, quantity: Quantity): CatalogQuantityResult {
        val sale = sku.saleUnits.singleOrNull { it.unitId == quantity.unitId }
        val scale = when {
            quantity.unitId == sku.baseUnitId -> sku.quantityScale
            sale != null -> sale.scale
            else -> return CatalogQuantityResult.Rejected(CatalogQuantityFailure.UNIT_NOT_ENABLED)
        }
        if (quantity.scaled <= 0) return CatalogQuantityResult.Rejected(CatalogQuantityFailure.QUANTITY_NOT_POSITIVE)
        val normalized = try {
            quantity.rescaleExact(scale)
        } catch (_: ArithmeticException) {
            return CatalogQuantityResult.Rejected(CatalogQuantityFailure.UNSUPPORTED_PRECISION)
        }
        return resolveBase(index, sku, normalized, sale?.conversion)
    }

    internal fun resolveBase(
        index: CatalogIndex,
        sku: CatalogSku,
        quantity: Quantity,
        conversion: CatalogVersionRef?,
    ): CatalogQuantityResult {
        if (quantity.scaled <= 0) return CatalogQuantityResult.Rejected(CatalogQuantityFailure.QUANTITY_NOT_POSITIVE)
        val record = conversion?.let { index.conversions[it] }
        if (quantity.unitId == sku.baseUnitId) {
            if (conversion != null) return CatalogQuantityResult.Rejected(CatalogQuantityFailure.CONVERSION_MISSING_OR_MISMATCHED)
        } else if (record == null || record.productId != sku.productId || record.sourceUnitId != quantity.unitId ||
            record.targetUnitId != sku.baseUnitId
        ) {
            return CatalogQuantityResult.Rejected(CatalogQuantityFailure.CONVERSION_MISSING_OR_MISMATCHED)
        }
        val base = try {
            record?.convertExact(quantity, sku.quantityScale) ?: quantity.rescaleExact(sku.quantityScale)
        } catch (_: ArithmeticException) {
            return CatalogQuantityResult.Rejected(CatalogQuantityFailure.INEXACT_OR_OVERFLOWING_CONVERSION)
        }
        if (sku.capabilities.tracking == StockTracking.SERIAL) {
            try {
                base.requireWholeUnits()
            } catch (_: IllegalArgumentException) {
                return CatalogQuantityResult.Rejected(CatalogQuantityFailure.SERIAL_REQUIRES_WHOLE_BASE_UNITS)
            }
        }
        return CatalogQuantityResult.Resolved(CatalogQuantitySnapshot(quantity, base, record))
    }
}

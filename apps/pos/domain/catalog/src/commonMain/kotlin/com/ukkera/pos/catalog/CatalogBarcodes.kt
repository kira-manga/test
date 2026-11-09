package com.ukkera.pos.catalog

import com.ukkera.pos.types.DomainId

public enum class CatalogBarcodeFailure { WRONG_COMPANY, UNKNOWN_NAMESPACE, INVALID_FORMAT, NOT_FOUND, ARCHIVED_OR_UNSELLABLE }

public data class CatalogScannedIdentity(
    public val identity: CompanyBarcodeIdentity,
    public val binding: CatalogVersionRef,
    public val productId: DomainId,
    public val skuId: DomainId,
    public val saleUnitId: DomainId,
    public val saleQuantityScale: Int,
    public val conversionSnapshot: ProductUnitConversion?,
)

public sealed interface CatalogBarcodeResult {
    public data class Found(public val identity: CatalogScannedIdentity) : CatalogBarcodeResult
    public data class Unavailable(public val reason: CatalogBarcodeFailure) : CatalogBarcodeResult
}

public object CatalogBarcodes {
    /**
     * Namespace is mandatory; there is no "guess from numeric value" overload. A scan resolves
     * identity/UOM, not measured quantity, embedded price, lot/serial allocation or stock availability.
     */
    public fun resolve(catalog: ValidatedCompanyCatalog, identity: CompanyBarcodeIdentity): CatalogBarcodeResult {
        if (identity.company != catalog.definition.scope) return CatalogBarcodeResult.Unavailable(CatalogBarcodeFailure.WRONG_COMPANY)
        val i = catalog.index
        val namespace = i.namespaces[identity.namespaceId] ?: return CatalogBarcodeResult.Unavailable(CatalogBarcodeFailure.UNKNOWN_NAMESPACE)
        if (!matchesBarcodeFormat(namespace.format, identity.barcode.original)) return CatalogBarcodeResult.Unavailable(CatalogBarcodeFailure.INVALID_FORMAT)
        val binding = catalog.definition.barcodes.singleOrNull { it.namespaceId == identity.namespaceId && it.value.normalizedKey == identity.barcode.normalizedKey }
            ?: return CatalogBarcodeResult.Unavailable(CatalogBarcodeFailure.NOT_FOUND)
        val sku = i.skus.getValue(binding.skuId)
        if (binding.status != CatalogStatus.ACTIVE || !isSellable(i, sku)) return CatalogBarcodeResult.Unavailable(CatalogBarcodeFailure.ARCHIVED_OR_UNSELLABLE)
        val sale = sku.saleUnits.singleOrNull { it.unitId == binding.saleUnitId }
        return CatalogBarcodeResult.Found(
            CatalogScannedIdentity(identity, CatalogVersionRef(binding.id, binding.revision), sku.productId, sku.id,
                binding.saleUnitId, sale?.scale ?: sku.quantityScale, sale?.conversion?.let { i.conversions.getValue(it) }),
        )
    }
}

internal fun matchesBarcodeFormat(format: BarcodeFormat, text: String): Boolean {
    if (format == BarcodeFormat.EXACT_LITERAL) {
        // Scanner terminators/control fields require an explicit parser, not trimming/guessing.
        return text.isNotEmpty() && text.all { !it.isWhitespace() && it.code >= 0x21 && it.code != 0x7f && it.code !in 0x80..0x9f }
    }
    val length = when (format) {
        BarcodeFormat.EXACT_LITERAL -> error("Handled literal format above")
        BarcodeFormat.EAN_8 -> 8
        BarcodeFormat.EAN_13 -> 13
        BarcodeFormat.UPC_A -> 12
    }
    if (text.length != length || text.any { it !in '0'..'9' }) return false
    var sum = 0
    for (index in 0 until length - 1) {
        val offsetFromRight = length - 2 - index
        sum += (text[index] - '0') * if (offsetFromRight % 2 == 0) 3 else 1
    }
    return (10 - sum % 10) % 10 == text.last() - '0'
}

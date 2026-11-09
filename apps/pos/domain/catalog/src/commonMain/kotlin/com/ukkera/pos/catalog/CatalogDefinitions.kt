package com.ukkera.pos.catalog

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Barcode
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

public enum class UnitDimension { COUNT, MASS, VOLUME, LENGTH, AREA, DURATION }

public data class CatalogUnit(
    public val id: DomainId,
    public val code: Code,
    public val dimension: UnitDimension,
    public val maximumScale: Int,
) {
    init { require(maximumScale in 0..6) }
}

public data class CatalogCategory(
    public val id: DomainId,
    public val code: Code,
    public val status: CatalogStatus = CatalogStatus.ACTIVE,
)

/** Category identity only: a separate tax owner supplies and pins the applicable rate/policy. */
public data class CatalogTaxCategory(public val id: DomainId, public val code: Code)

public class VariantAxis(
    public val id: DomainId,
    public val code: Code,
    values: List<Code>,
    bounds: CatalogBounds = CatalogBounds(),
) {
    public val values: List<Code> = frozen(values, bounds.maxChildren, "Variant values")

    override fun equals(other: Any?): Boolean = other is VariantAxis && id == other.id && code == other.code && values == other.values
    override fun hashCode(): Int = 31 * (31 * id.hashCode() + code.hashCode()) + values.hashCode()
}

public data class VariantValue(public val axisId: DomainId, public val value: Code)

/** Typed labels only; attributes cannot execute inventory, price, tax or payment behavior. */
public sealed interface CatalogAttributeValue {
    public data class Text(public val value: Code) : CatalogAttributeValue
    public data class Decimal(public val value: ExactDecimal) : CatalogAttributeValue
    public data class Flag(public val value: Boolean) : CatalogAttributeValue
}

public data class CatalogAttribute(public val key: Code, public val value: CatalogAttributeValue)

/**
 * SKUs enumerate actual stock identities. An optional explicit matrix limits their selected
 * combinations; an absent matrix preserves the earlier SKU-enumerated representation.
 * Neither representation generates a Cartesian set of SKUs.
 */
public class CatalogProduct(
    public val id: DomainId,
    public val revision: Revision,
    public val name: Code,
    categoryIds: List<DomainId> = emptyList(),
    variantAxes: List<VariantAxis> = emptyList(),
    attributes: List<CatalogAttribute> = emptyList(),
    public val status: CatalogStatus = CatalogStatus.ACTIVE,
    bounds: CatalogBounds = CatalogBounds(),
    allowedVariantCombinations: List<List<VariantValue>>? = null,
) {
    public val categoryIds: List<DomainId> = frozen(categoryIds, bounds.maxChildren, "Product categories")
    public val variantAxes: List<VariantAxis> = frozen(variantAxes, bounds.maxChildren, "Variant axes")
    public val attributes: List<CatalogAttribute> = frozen(attributes, bounds.maxChildren, "Typed attributes")
    /** Every row is a complete axis/value selection; both row order and value order are retained. */
    public val allowedVariantCombinations: List<List<VariantValue>>? = allowedVariantCombinations?.let { rows ->
        require(rows.size <= bounds.maxChildren) { "Allowed variant combinations exceed engineering capacity ${bounds.maxChildren}" }
        frozen(rows.map { frozen(it, bounds.maxChildren, "Allowed variant combination") }, bounds.maxChildren,
            "Allowed variant combinations")
    }
    private val allowedVariantSelectionKeys: Set<List<VariantValue>>? =
        this.allowedVariantCombinations?.map { row -> row.sortedBy { it.axisId.value } }?.toSet()

    public fun allowsVariantSelection(values: List<VariantValue>): Boolean {
        if (values.map { it.axisId }.distinct().size != values.size) return false
        val selected = values.associate { it.axisId to it.value }
        if (selected.keys != variantAxes.map { it.id }.toSet() || variantAxes.any { selected[it.id] !in it.values }) return false
        return allowedVariantSelectionKeys?.contains(values.sortedBy { it.axisId.value }) ?: true
    }

    internal fun sameContent(other: CatalogProduct): Boolean =
        id == other.id && revision == other.revision && name == other.name && categoryIds == other.categoryIds &&
            variantAxes == other.variantAxes && attributes == other.attributes && status == other.status &&
            allowedVariantCombinations == other.allowedVariantCombinations
}

public enum class StockTracking { NONE, QUANTITY, LOT, SERIAL }

public enum class FulfillmentKind {
    RETAIL,
    MADE_TO_ORDER,
    PREPARED_BATCH,
    VIRTUAL_KIT,
    ASSEMBLED_KIT,
    SERVICE,
}

public enum class ServiceCharging { FIXED, QUANTITY, ENTERED_DURATION }

/** Receipt-linked liability/remaining entitlement only; not a wallet or stored-value product. */
public data class ContainerDepositDefinition(
    public val reference: CatalogVersionRef,
    public val amountPerContainer: Money,
    public val liabilityAccountingMappingId: DomainId,
    public val taxCategoryId: DomainId,
    public val taxInclusion: CatalogTaxInclusion,
) {
    init { require(amountPerContainer.minor > 0) }
}

public data class ContainerCapability(public val deposit: ContainerDepositDefinition? = null)

/** Flags are independent where meaningful. The validator rejects incompatible stock paths. */
public data class SkuCapabilities(
    public val canPurchase: Boolean,
    public val canSell: Boolean,
    public val canProduce: Boolean,
    public val tracking: StockTracking,
    public val measured: Boolean = false,
    public val requiresExpiry: Boolean = false,
    public val ingredient: Boolean = false,
    public val fulfillment: FulfillmentKind = FulfillmentKind.RETAIL,
    public val composition: CatalogVersionRef? = null,
    public val serviceCharging: ServiceCharging? = null,
    public val container: ContainerCapability? = null,
)

/** Additional sale UOM: direct current product conversion to the SKU's base unit is explicit. */
public data class CatalogSaleUnit(
    public val unitId: DomainId,
    public val scale: Int,
    public val conversion: CatalogVersionRef,
) {
    init { require(scale in 0..6) }
}

public class CatalogSku(
    public val id: DomainId,
    public val revision: Revision,
    public val productId: DomainId,
    public val key: Code,
    public val baseUnitId: DomainId,
    public val quantityScale: Int,
    public val capabilities: SkuCapabilities,
    variantValues: List<VariantValue> = emptyList(),
    saleUnits: List<CatalogSaleUnit> = emptyList(),
    public val status: CatalogStatus = CatalogStatus.ACTIVE,
    bounds: CatalogBounds = CatalogBounds(),
) {
    init { require(quantityScale in 0..6) }
    public val variantValues: List<VariantValue> = frozen(variantValues, bounds.maxChildren, "SKU variant combination")
    public val saleUnits: List<CatalogSaleUnit> = frozen(saleUnits, bounds.maxChildren, "SKU sale units")

    internal fun sameContent(other: CatalogSku): Boolean =
        id == other.id && revision == other.revision && productId == other.productId && key == other.key &&
            baseUnitId == other.baseUnitId && quantityScale == other.quantityScale && capabilities == other.capabilities &&
            variantValues == other.variantValues && saleUnits == other.saleUnits && status == other.status
}

/** Only literal, explicitly named symbologies. No embedded-weight/price decoder or guessing. */
public enum class BarcodeFormat { EXACT_LITERAL, EAN_8, EAN_13, UPC_A }

public data class CatalogBarcodeNamespace(
    public val id: DomainId,
    public val code: Code,
    public val format: BarcodeFormat,
)

public data class CatalogBarcodeBinding(
    public val id: DomainId,
    public val revision: Revision,
    public val namespaceId: DomainId,
    public val value: Barcode,
    public val skuId: DomainId,
    public val saleUnitId: DomainId,
    public val status: CatalogStatus = CatalogStatus.ACTIVE,
)

/** Company + namespace + exact key. Leading zeros and original text are retained. */
public data class CompanyBarcodeIdentity(
    public val company: CompanyScope,
    public val namespaceId: DomainId,
    public val barcode: Barcode,
)

/**
 * Immutable conversion version. It is always product-specific, even for count/pack conversions.
 * A cross mass/volume conversion additionally requires a retained evidence reference; that ID
 * does not prove physical density measurement. The publication workflow must verify the evidence.
 */
public data class ProductUnitConversion(
    public val id: DomainId,
    public val revision: Revision,
    public val productId: DomainId,
    public val sourceUnitId: DomainId,
    public val targetUnitId: DomainId,
    public val rate: UnitConversionRate,
    public val massVolumeEvidenceId: DomainId? = null,
) {
    init { require(sourceUnitId != targetUnitId) { "Identity conversions need no rate record" } }
    public val reference: CatalogVersionRef get() = CatalogVersionRef(id, revision)

    /** Arithmetic on an already validated/pinned snapshot; no implicit chaining or rounding. */
    public fun convertExact(quantity: Quantity, targetScale: Int): Quantity {
        require(quantity.unitId == sourceUnitId) { "Conversion source unit mismatch" }
        return Quantity(targetUnitId, (quantity.toFraction() * rate.toFraction()).toScaledLongExact(targetScale), targetScale)
    }
}

public enum class CompositionKind { MADE_TO_ORDER, PREPARED_BATCH, VIRTUAL_KIT, ASSEMBLED_KIT }

public data class CatalogComponent(
    public val id: DomainId,
    public val skuId: DomainId,
    public val quantity: Quantity,
    public val conversion: CatalogVersionRef? = null,
) {
    init { quantity.requirePositive() }
}

/** Fixed selected composition. Modifiers/substitutions/configurable choice execution is not implemented. */
public class CatalogComposition(
    public val id: DomainId,
    public val revision: Revision,
    public val outputSkuId: DomainId,
    public val kind: CompositionKind,
    public val outputQuantity: Quantity,
    components: List<CatalogComponent>,
    bounds: CatalogBounds = CatalogBounds(),
) {
    init { outputQuantity.requirePositive() }
    public val components: List<CatalogComponent> = frozen(components, bounds.maxChildren, "Composition components")
    public val reference: CatalogVersionRef get() = CatalogVersionRef(id, revision)

    internal fun sameContent(other: CatalogComposition): Boolean =
        id == other.id && revision == other.revision && outputSkuId == other.outputSkuId && kind == other.kind &&
            outputQuantity == other.outputQuantity && components == other.components
}

public enum class PriceContextKind { STANDARD, CHANNEL, QUANTITY_TIER }

public data class CatalogPriceContext(
    public val id: DomainId,
    public val revision: Revision,
    public val code: Code,
    public val kind: PriceContextKind,
) {
    public val reference: CatalogVersionRef get() = CatalogVersionRef(id, revision)
}

public sealed interface CatalogPriceTarget {
    public data class Sku(public val id: DomainId) : CatalogPriceTarget
    public data class Product(public val id: DomainId) : CatalogPriceTarget
    public data class Category(public val id: DomainId) : CatalogPriceTarget
    public data object Standard : CatalogPriceTarget
}

/** Half-open UTC interval. Start only determines eligibility, never rank or revision. */
public data class CatalogEffectivePeriod(public val from: UtcInstant, public val untilExclusive: UtcInstant? = null) {
    init { require(untilExclusive == null || untilExclusive > from) }
    public fun contains(at: UtcInstant): Boolean = at >= from && (untilExclusive == null || at < untilExclusive)
    internal fun overlaps(other: CatalogEffectivePeriod): Boolean =
        (untilExclusive == null || other.from < untilExclusive) && (other.untilExclusive == null || from < other.untilExclusive)
}

public data class CatalogPriceQuantityRange(public val minimum: Quantity, public val maximumExclusive: Quantity? = null) {
    init {
        require(minimum.scaled >= 0)
        require(maximumExclusive == null || (maximumExclusive.unitId == minimum.unitId && maximumExclusive > minimum))
    }
    public fun contains(quantity: Quantity): Boolean = quantity.unitId == minimum.unitId && quantity >= minimum &&
        (maximumExclusive == null || quantity < maximumExclusive)
}

public enum class CatalogTaxInclusion { INCLUSIVE, EXCLUSIVE }

public data class CatalogPriceTerms(
    public val unitPrice: UnitPrice,
    public val taxInclusion: CatalogTaxInclusion,
    public val taxCategoryId: DomainId,
)

public data class CompanyPriceRule(
    public val id: DomainId,
    public val revision: Revision,
    public val context: CatalogVersionRef,
    public val target: CatalogPriceTarget,
    public val priority: Int,
    public val channel: Code?,
    public val quantityRange: CatalogPriceQuantityRange,
    public val period: CatalogEffectivePeriod,
    public val terms: CatalogPriceTerms,
) {
    public val reference: CatalogVersionRef get() = CatalogVersionRef(id, revision)
}

/** Relevant SKU/context/rule identity, NOT the company-wide publication revision. */
public data class CompanyPriceReference(
    public val company: CompanyScope,
    public val skuId: DomainId,
    public val context: CatalogVersionRef,
    public val rule: CatalogVersionRef,
)

/** Only the base amount is overridden; tax inclusion/category remain the selected company's terms. */
public data class BranchPriceOverride(
    public val scope: BranchScope,
    public val id: DomainId,
    public val revision: Revision,
    public val basedOn: CompanyPriceReference,
    public val unitPrice: UnitPrice,
    public val period: CatalogEffectivePeriod,
) {
    public val reference: CatalogVersionRef get() = CatalogVersionRef(id, revision)
}

/** Immutable definition registry plus explicit current version pointers; old versions are never inferred as current. */
public class CompanyCatalogDefinition(
    public val scope: CompanyScope,
    public val revision: Revision,
    public val policy: CompanyCatalogPolicy,
    units: List<CatalogUnit>,
    products: List<CatalogProduct>,
    skus: List<CatalogSku>,
    priceContexts: List<CatalogPriceContext>,
    priceRules: List<CompanyPriceRule>,
    activePriceRules: List<CatalogVersionRef>,
    categories: List<CatalogCategory> = emptyList(),
    taxCategories: List<CatalogTaxCategory> = emptyList(),
    barcodeNamespaces: List<CatalogBarcodeNamespace> = emptyList(),
    barcodes: List<CatalogBarcodeBinding> = emptyList(),
    conversions: List<ProductUnitConversion> = emptyList(),
    compositions: List<CatalogComposition> = emptyList(),
    public val bounds: CatalogBounds = CatalogBounds(),
) {
    public val units: List<CatalogUnit> = frozen(units, bounds.maxEntities, "Units")
    public val products: List<CatalogProduct> = frozen(products, bounds.maxEntities, "Products")
    public val skus: List<CatalogSku> = frozen(skus, bounds.maxEntities, "SKUs")
    public val priceContexts: List<CatalogPriceContext> = frozen(priceContexts, bounds.maxPriceRules, "Price context versions")
    public val priceRules: List<CompanyPriceRule> = frozen(priceRules, bounds.maxPriceRules, "Price rule versions")
    public val activePriceRules: List<CatalogVersionRef> = frozen(activePriceRules, bounds.maxPriceRules, "Active price rules")
    public val categories: List<CatalogCategory> = frozen(categories, bounds.maxEntities, "Categories")
    public val taxCategories: List<CatalogTaxCategory> = frozen(taxCategories, bounds.maxEntities, "Tax categories")
    public val barcodeNamespaces: List<CatalogBarcodeNamespace> = frozen(barcodeNamespaces, bounds.maxEntities, "Barcode namespaces")
    public val barcodes: List<CatalogBarcodeBinding> = frozen(barcodes, bounds.maxEntities, "Barcode bindings")
    public val conversions: List<ProductUnitConversion> = frozen(conversions, bounds.maxEntities, "Conversion versions")
    public val compositions: List<CatalogComposition> = frozen(compositions, bounds.maxEntities, "Composition versions")
}

public class BranchCatalogDefinition(
    public val scope: BranchScope,
    public val revision: Revision,
    public val companyCatalogRevision: Revision,
    public val policy: BranchCatalogPolicy,
    overrides: List<BranchPriceOverride> = emptyList(),
    historicalOverrides: List<BranchPriceOverride> = emptyList(),
    bounds: CatalogBounds = CatalogBounds(),
) {
    init {
        require(overrides.size.toLong() + historicalOverrides.size.toLong() <= bounds.maxPriceRules.toLong()) {
            "Current plus historical branch override versions exceed engineering capacity ${bounds.maxPriceRules}"
        }
    }
    public val overrides: List<BranchPriceOverride> = frozen(overrides, bounds.maxPriceRules, "Branch price overrides")
    /**
     * Inactive immutable versions, not drafts or implicitly selectable overrides. Current plus
     * historical versions form an append-only registry across validated branch revisions. The
     * protected adapter must supply the actual complete prior branch, not trust a client history.
     */
    public val historicalOverrides: List<BranchPriceOverride> =
        frozen(historicalOverrides, bounds.maxPriceRules, "Historical branch override versions")
}

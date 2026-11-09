package com.ukkera.pos.catalog

import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

public enum class CatalogIssueCode {
    ENGINEERING_BOUND_EXCEEDED,
    DUPLICATE_IDENTITY,
    WRONG_SCOPE,
    MISSING_REFERENCE,
    ARCHIVED_REFERENCE,
    INVALID_POLICY,
    INVALID_VARIANT,
    INVALID_CAPABILITIES,
    INVALID_CONVERSION,
    INVALID_COMPOSITION,
    COMPONENT_CYCLE,
    INVALID_BARCODE_FORMAT,
    INVALID_PRICE_RULE,
    PRICE_AMBIGUITY,
    HISTORY_UNAVAILABLE,
    STALE_HISTORY,
    REVISION_REGRESSION,
    VERSION_REWRITTEN,
    HISTORY_RECORD_REMOVED,
    STOCK_IDENTITY_CHANGED,
    IDENTIFIER_CHANGED,
    PUBLICATION_REQUIRED,
    STALE_COMPANY_BINDING,
    CAPABILITY_NOT_ENABLED,
    EXECUTION_UNSUPPORTED,
    STANDARD_PRICE_REQUIRED,
    OVERRIDE_AMBIGUITY,
}

public data class CatalogIssue(
    public val code: CatalogIssueCode,
    public val entityId: DomainId? = null,
    public val relatedId: DomainId? = null,
    public val detail: String,
) {
    init { require(detail.length <= 512) }
}

/**
 * Trusted activation input, read alongside the previous catalog in the writer transaction.
 * Empty lists are meaningful only when complete=true is actually established by that store.
 * This module cannot discover database history or turn an ID/boolean into authority.
 */
public class CatalogHistorySnapshot(
    public val scope: CompanyScope,
    public val previousCatalogRevision: Revision?,
    public val complete: Boolean,
    usedSkuIds: List<DomainId> = emptyList(),
    retainedProductIds: List<DomainId> = emptyList(),
    retainedUnitIds: List<DomainId> = emptyList(),
    retainedBarcodeIds: List<DomainId> = emptyList(),
    retainedConversions: List<CatalogVersionRef> = emptyList(),
    retainedCompositions: List<CatalogVersionRef> = emptyList(),
    retainedPrices: List<CatalogVersionRef> = emptyList(),
    retainedContexts: List<CatalogVersionRef> = emptyList(),
    bounds: CatalogBounds = CatalogBounds(),
) {
    public val usedSkuIds: List<DomainId> = frozen(usedSkuIds, bounds.maxEntities, "Used SKU identities")
    public val retainedProductIds: List<DomainId> = frozen(retainedProductIds, bounds.maxEntities, "Referenced products")
    public val retainedUnitIds: List<DomainId> = frozen(retainedUnitIds, bounds.maxEntities, "Referenced units")
    public val retainedBarcodeIds: List<DomainId> = frozen(retainedBarcodeIds, bounds.maxEntities, "Referenced barcodes")
    public val retainedConversions: List<CatalogVersionRef> = frozen(retainedConversions, bounds.maxEntities, "Referenced conversions")
    public val retainedCompositions: List<CatalogVersionRef> = frozen(retainedCompositions, bounds.maxEntities, "Referenced compositions")
    public val retainedPrices: List<CatalogVersionRef> = frozen(retainedPrices, bounds.maxPriceRules, "Referenced prices")
    public val retainedContexts: List<CatalogVersionRef> = frozen(retainedContexts, bounds.maxPriceRules, "Referenced contexts")

    internal fun isEmpty(): Boolean = usedSkuIds.isEmpty() && retainedProductIds.isEmpty() && retainedUnitIds.isEmpty() &&
        retainedBarcodeIds.isEmpty() && retainedConversions.isEmpty() && retainedCompositions.isEmpty() &&
        retainedPrices.isEmpty() && retainedContexts.isEmpty()
}

/** Structural validation only; not company publication, durable activation or stock availability. */
public class ValidatedCompanyCatalog internal constructor(public val definition: CompanyCatalogDefinition) {
    internal val index: CatalogIndex = CatalogIndex(definition)
}

public sealed interface CompanyCatalogValidation {
    public data class Valid(public val catalog: ValidatedCompanyCatalog) : CompanyCatalogValidation
    public class Rejected internal constructor(
        issues: List<CatalogIssue>,
        priceConflicts: List<CatalogPriceConflict>,
        public val diagnosticsTruncated: Boolean,
        bounds: CatalogBounds,
    ) : CompanyCatalogValidation {
        public val issues: List<CatalogIssue> = frozen(issues, bounds.maxIssues, "Catalog issues")
        public val priceConflicts: List<CatalogPriceConflict> = frozen(priceConflicts, bounds.maxIssues, "Price conflicts")
    }
}

internal class CatalogIndex(val definition: CompanyCatalogDefinition) {
    val units: Map<DomainId, CatalogUnit> = definition.units.associateBy { it.id }
    val products: Map<DomainId, CatalogProduct> = definition.products.associateBy { it.id }
    val skus: Map<DomainId, CatalogSku> = definition.skus.associateBy { it.id }
    val categories: Map<DomainId, CatalogCategory> = definition.categories.associateBy { it.id }
    val taxCategories: Map<DomainId, CatalogTaxCategory> = definition.taxCategories.associateBy { it.id }
    val namespaces: Map<DomainId, CatalogBarcodeNamespace> = definition.barcodeNamespaces.associateBy { it.id }
    val barcodes: Map<DomainId, CatalogBarcodeBinding> = definition.barcodes.associateBy { it.id }
    val conversions: Map<CatalogVersionRef, ProductUnitConversion> = definition.conversions.associateBy { it.reference }
    val compositions: Map<CatalogVersionRef, CatalogComposition> = definition.compositions.associateBy { it.reference }
    val contexts: Map<CatalogVersionRef, CatalogPriceContext> = definition.priceContexts.associateBy { it.reference }
    val prices: Map<CatalogVersionRef, CompanyPriceRule> = definition.priceRules.associateBy { it.reference }
    val activePrices: List<CompanyPriceRule> = definition.activePriceRules.mapNotNull { prices[it] }.sortedBy { it.id.value }
}

internal class CatalogDiagnostics(val bounds: CatalogBounds) {
    val issues: MutableList<CatalogIssue> = mutableListOf()
    val conflicts: MutableList<CatalogPriceConflict> = mutableListOf()
    var truncated: Boolean = false
    var exhausted: Boolean = false
        private set
    private var work: Int = 0
    val valid: Boolean get() = issues.isEmpty() && !truncated && !exhausted

    fun add(code: CatalogIssueCode, id: DomainId? = null, related: DomainId? = null, detail: String) {
        if (issues.size < bounds.maxIssues) issues += CatalogIssue(code, id, related, detail) else truncated = true
    }

    fun spend(): Boolean {
        if (exhausted) return false
        if (work == bounds.maxValidationWork) {
            exhausted = true
            add(CatalogIssueCode.ENGINEERING_BOUND_EXCEEDED, detail = "Validation work budget exhausted; no partial validation is accepted")
            return false
        }
        work++
        return true
    }

    fun rejected(): CompanyCatalogValidation.Rejected = CompanyCatalogValidation.Rejected(issues, conflicts, truncated, bounds)
}

public object CompanyCatalogValidator {
    public fun validate(
        definition: CompanyCatalogDefinition,
        history: CatalogHistorySnapshot,
        previous: ValidatedCompanyCatalog? = null,
    ): CompanyCatalogValidation {
        val diagnostics = CatalogDiagnostics(definition.bounds)
        validateBounds(definition, diagnostics)
        validateUniqueness(definition, diagnostics)
        if (!diagnostics.valid) return diagnostics.rejected()
        val index = CatalogIndex(definition)
        validateStructure(index, diagnostics)
        validateHistory(index, history, previous, diagnostics)
        if (!diagnostics.valid) return diagnostics.rejected()
        validateComponentGraph(index, diagnostics)
        if (!diagnostics.valid) return diagnostics.rejected()
        findPriceConflicts(index, diagnostics)
        return if (diagnostics.valid) CompanyCatalogValidation.Valid(ValidatedCompanyCatalog(definition)) else diagnostics.rejected()
    }
}

private fun validateBounds(d: CompanyCatalogDefinition, c: CatalogDiagnostics) {
    fun size(size: Int, limit: Int, id: DomainId? = null) {
        if (size > limit) c.add(CatalogIssueCode.ENGINEERING_BOUND_EXCEEDED, id, detail = "Nested definition exceeds publication engineering bounds")
    }
    size(d.policy.enabledContexts.size, d.bounds.maxChildren)
    size(d.policy.salesChannels.size, d.bounds.maxChannels)
    size(d.policy.channelSpecificPricesEnabled.size, d.bounds.maxChannels)
    size(d.policy.optionalActions.size, d.bounds.maxPolicyDeclarations)
    for (product in d.products) {
        size(product.categoryIds.size, d.bounds.maxChildren, product.id)
        size(product.variantAxes.size, d.bounds.maxChildren, product.id)
        size(product.attributes.size, d.bounds.maxChildren, product.id)
        product.variantAxes.forEach { size(it.values.size, d.bounds.maxChildren, it.id) }
        product.allowedVariantCombinations?.let { rows ->
            size(rows.size, d.bounds.maxChildren, product.id)
            rows.forEach { size(it.size, d.bounds.maxChildren, product.id) }
        }
    }
    for (sku in d.skus) {
        size(sku.variantValues.size, d.bounds.maxChildren, sku.id)
        size(sku.saleUnits.size, d.bounds.maxChildren, sku.id)
    }
    d.compositions.forEach { size(it.components.size, d.bounds.maxChildren, it.id) }
}

internal fun <T, K> unique(values: List<T>, key: (T) -> K, c: CatalogDiagnostics, label: String, id: DomainId? = null) {
    val seen = mutableSetOf<K>()
    for (value in values) {
        if (!c.spend()) return
        if (!seen.add(key(value))) c.add(CatalogIssueCode.DUPLICATE_IDENTITY, id, detail = "Duplicate $label")
    }
}

private fun validateUniqueness(d: CompanyCatalogDefinition, c: CatalogDiagnostics) {
    unique(d.units, { it.id }, c, "unit ID")
    unique(d.units, { it.code }, c, "unit code")
    unique(d.products, { it.id }, c, "product ID")
    unique(d.skus, { it.id }, c, "SKU ID")
    unique(d.skus, { it.key }, c, "company SKU key, including archived identities")
    unique(d.categories, { it.id }, c, "category ID")
    unique(d.categories, { it.code }, c, "category code")
    unique(d.taxCategories, { it.id }, c, "tax category ID")
    unique(d.taxCategories, { it.code }, c, "tax category code")
    unique(d.barcodeNamespaces, { it.id }, c, "barcode namespace ID")
    unique(d.barcodeNamespaces, { it.code }, c, "barcode namespace code")
    unique(d.barcodes, { it.id }, c, "barcode binding ID")
    unique(d.barcodes, { it.namespaceId to it.value.normalizedKey }, c, "company/namespace/barcode key, including archives")
    unique(d.conversions, { it.reference }, c, "conversion version")
    unique(d.compositions, { it.reference }, c, "composition version")
    unique(d.priceContexts, { it.reference }, c, "price context version")
    unique(d.priceRules, { it.reference }, c, "company price rule version")
    unique(d.activePriceRules, { it.id }, c, "active price rule ID; only one current revision per rule")
    unique(d.policy.enabledContexts, { it.id }, c, "enabled context ID; only one current revision per context")
    unique(d.policy.salesChannels, { it }, c, "sales channel")
    unique(d.policy.channelSpecificPricesEnabled, { it }, c, "channel price enablement")
    unique(d.policy.optionalActions, { it.id }, c, "action declaration ID")
    unique(d.policy.optionalActions, { it.permission }, c, "action permission declaration")
    for (product in d.products) {
        unique(product.categoryIds, { it }, c, "product category", product.id)
        unique(product.variantAxes, { it.id }, c, "variant axis ID", product.id)
        unique(product.variantAxes, { it.code }, c, "variant axis code", product.id)
        unique(product.attributes, { it.key }, c, "attribute key", product.id)
        product.variantAxes.forEach { axis -> unique(axis.values, { it }, c, "variant axis value", axis.id) }
        product.allowedVariantCombinations?.let { rows ->
            rows.forEach { unique(it, { value -> value.axisId }, c, "allowed combination axis", product.id) }
            unique(rows, { it.sortedBy { value -> value.axisId.value } }, c, "allowed variant combination", product.id)
        }
    }
    for (sku in d.skus) {
        unique(sku.variantValues, { it.axisId }, c, "SKU variant axis", sku.id)
        unique(sku.saleUnits, { it.unitId }, c, "SKU sale unit", sku.id)
    }
    unique(
        d.skus.filter { it.status == CatalogStatus.ACTIVE },
        { it.productId to it.variantValues.sortedBy { value -> value.axisId.value } },
        c, "active product/variant combination",
    )
}

private fun validateStructure(i: CatalogIndex, c: CatalogDiagnostics) {
    val d = i.definition
    val p = d.policy
    if (p.scope != d.scope) c.add(CatalogIssueCode.WRONG_SCOPE, p.id, detail = "Company policy belongs to another company")
    if (p.salesChannels.isEmpty()) c.add(CatalogIssueCode.INVALID_POLICY, p.id, detail = "Explicit sales channels are required")
    if (p.standardContext !in p.enabledContexts || i.contexts[p.standardContext]?.kind != PriceContextKind.STANDARD) {
        c.add(CatalogIssueCode.INVALID_POLICY, p.id, detail = "Exactly one explicitly enabled standard context is required")
    }
    for (context in p.enabledContexts) {
        val record = i.contexts[context]
        if (record == null) c.add(CatalogIssueCode.MISSING_REFERENCE, context.id, detail = "Enabled context version is missing")
        if (record?.kind == PriceContextKind.STANDARD && context != p.standardContext) {
            c.add(CatalogIssueCode.INVALID_POLICY, context.id, detail = "Another standard context is enabled")
        }
    }
    if (!p.salesChannels.containsAll(p.channelSpecificPricesEnabled)) {
        c.add(CatalogIssueCode.INVALID_POLICY, p.id, detail = "Channel price enablement must name admitted sales channels")
    }
    for (product in d.products) {
        if (!c.spend()) return
        product.categoryIds.forEach { category ->
            if (category !in i.categories) c.add(CatalogIssueCode.MISSING_REFERENCE, product.id, category, "Product category is missing")
            else if (product.status == CatalogStatus.ACTIVE && i.categories[category]?.status == CatalogStatus.ARCHIVED) {
                c.add(CatalogIssueCode.ARCHIVED_REFERENCE, product.id, category, "Active product refers to an archived category")
            }
        }
        product.variantAxes.filter { it.values.isEmpty() }.forEach {
            c.add(CatalogIssueCode.INVALID_VARIANT, product.id, it.id, "Variant axis has no declared values")
        }
        product.allowedVariantCombinations?.let { rows ->
            if (rows.isEmpty()) c.add(CatalogIssueCode.INVALID_VARIANT, product.id,
                detail = "An explicit allowed-combination matrix must contain at least one complete selection")
            for (row in rows) {
                if (!c.spend()) return
                val selected = row.associate { it.axisId to it.value }
                if (selected.keys != product.variantAxes.map { it.id }.toSet() ||
                    product.variantAxes.any { selected[it.id] !in it.values })
                    c.add(CatalogIssueCode.INVALID_VARIANT, product.id,
                        detail = "Allowed combination must specify exactly one declared value for every axis")
            }
        }
    }
    for (conversion in d.conversions) validateConversion(i, conversion, c)
    for (sku in d.skus) {
        if (!c.spend()) return
        val product = i.products[sku.productId]
        val unit = i.units[sku.baseUnitId]
        if (product == null) c.add(CatalogIssueCode.MISSING_REFERENCE, sku.id, sku.productId, "SKU product is missing")
        if (unit == null) c.add(CatalogIssueCode.MISSING_REFERENCE, sku.id, sku.baseUnitId, "SKU base unit is missing")
        if (unit != null && sku.quantityScale > unit.maximumScale) {
            c.add(CatalogIssueCode.INVALID_CAPABILITIES, sku.id, detail = "SKU precision exceeds its declared unit precision")
        }
        if (sku.status == CatalogStatus.ACTIVE && product?.status == CatalogStatus.ARCHIVED) {
            c.add(CatalogIssueCode.ARCHIVED_REFERENCE, sku.id, sku.productId, "Active SKU refers to an archived product")
        }
        if (product != null && sku.status == CatalogStatus.ACTIVE) {
            val chosen = sku.variantValues.associate { it.axisId to it.value }
            if (chosen.keys != product.variantAxes.map { it.id }.toSet() ||
                product.variantAxes.any { chosen[it.id] !in it.values }
            ) c.add(CatalogIssueCode.INVALID_VARIANT, sku.id, product.id, "SKU must specify exactly one declared value for every axis")
            else if (!product.allowsVariantSelection(sku.variantValues)) c.add(CatalogIssueCode.INVALID_VARIANT, sku.id, product.id,
                "SKU selected combination is not in the explicit allowed matrix")
        } else if (product?.allowedVariantCombinations != null && !product.allowsVariantSelection(sku.variantValues)) {
            c.add(CatalogIssueCode.INVALID_VARIANT, sku.id, product.id, "An archived SKU's selected combination must remain in the explicit allowed matrix")
        }
        validateCapabilities(i, sku, c)
        for (sale in sku.saleUnits) {
            val saleUnit = i.units[sale.unitId]
            if (sale.unitId == sku.baseUnitId || saleUnit == null || sale.scale > saleUnit.maximumScale) {
                c.add(CatalogIssueCode.INVALID_CONVERSION, sku.id, sale.unitId, "Additional sale UOM is missing, duplicates base, or has unsupported precision")
            }
            if (!sku.capabilities.measured && sku.capabilities.tracking != StockTracking.SERIAL && sale.scale != 0) {
                c.add(CatalogIssueCode.INVALID_CAPABILITIES, sku.id, sale.unitId, "Fractional sale units require explicit measured capability")
            }
            val increment = Quantity(sale.unitId, 1, sale.scale)
            if (CatalogQuantities.resolveBase(i, sku, increment, sale.conversion) is CatalogQuantityResult.Rejected) {
                c.add(CatalogIssueCode.INVALID_CONVERSION, sku.id, sale.conversion.id, "Smallest admitted sale increment must convert exactly to valid base/serial units")
            }
        }
    }
    for (composition in d.compositions) validateComposition(i, composition, c)
    validateVersionFamilies(i, c)
    for (barcode in d.barcodes) {
        if (!c.spend()) return
        val namespace = i.namespaces[barcode.namespaceId]
        val sku = i.skus[barcode.skuId]
        if (namespace == null || sku == null) {
            c.add(CatalogIssueCode.MISSING_REFERENCE, barcode.id, detail = "Barcode namespace or SKU is missing")
            continue
        }
        if (!matchesBarcodeFormat(namespace.format, barcode.value.original)) {
            c.add(CatalogIssueCode.INVALID_BARCODE_FORMAT, barcode.id, namespace.id, "Barcode does not satisfy its explicit literal format/checksum")
        }
        if (barcode.status == CatalogStatus.ACTIVE) {
            if (sku.status != CatalogStatus.ACTIVE || i.products[sku.productId]?.status != CatalogStatus.ACTIVE) {
                c.add(CatalogIssueCode.ARCHIVED_REFERENCE, barcode.id, sku.id, "Active barcode targets an archived SKU/product")
            }
            if (barcode.saleUnitId != sku.baseUnitId && sku.saleUnits.none { it.unitId == barcode.saleUnitId }) {
                c.add(CatalogIssueCode.INVALID_CONVERSION, barcode.id, barcode.saleUnitId, "Barcode unit is not a currently declared sale UOM")
            }
        } else if (barcode.saleUnitId !in i.units) {
            c.add(CatalogIssueCode.MISSING_REFERENCE, barcode.id, barcode.saleUnitId, "Archived barcode unit must be retained")
        }
    }
    validatePrices(i, c)
}

private fun validateCapabilities(i: CatalogIndex, sku: CatalogSku, c: CatalogDiagnostics) {
    val cap = sku.capabilities
    fun invalid(detail: String) = c.add(CatalogIssueCode.INVALID_CAPABILITIES, sku.id, detail = detail)
    val stocked = cap.tracking != StockTracking.NONE
    val stockPath = cap.fulfillment in setOf(FulfillmentKind.RETAIL, FulfillmentKind.PREPARED_BATCH, FulfillmentKind.ASSEMBLED_KIT)
    if (stocked != stockPath) invalid("Retail/batch/assembled paths require stock; recipe/virtual/service parents must not create fictional stock")
    if (cap.requiresExpiry && cap.tracking != StockTracking.LOT) invalid("Expiry requires lot tracking")
    if (cap.tracking == StockTracking.SERIAL && (cap.measured || i.units[sku.baseUnitId]?.dimension != UnitDimension.COUNT)) {
        invalid("Serial tracking requires whole count units, not measured inventory")
    }
    if (!cap.measured && cap.tracking != StockTracking.SERIAL && sku.quantityScale != 0) {
        invalid("Fractional quantity precision requires explicit measured capability")
    }
    if (cap.ingredient && !stocked) invalid("An issued ingredient must have its own tracked stock")
    val production = cap.fulfillment in setOf(FulfillmentKind.MADE_TO_ORDER, FulfillmentKind.PREPARED_BATCH, FulfillmentKind.ASSEMBLED_KIT)
    if (cap.canProduce != production) invalid("Production capability must agree with the declared production path")
    if (cap.canPurchase && !stocked) invalid("Non-stock purchasing execution is not represented by this catalog profile")
    val composed = cap.fulfillment in setOf(FulfillmentKind.MADE_TO_ORDER, FulfillmentKind.PREPARED_BATCH, FulfillmentKind.VIRTUAL_KIT, FulfillmentKind.ASSEMBLED_KIT)
    if ((cap.composition != null) != composed) invalid("Exactly the composed fulfillment paths require an explicit composition version")
    val composition = cap.composition?.let { i.compositions[it] }
    if (cap.composition != null && composition == null) invalid("Selected composition version is missing")
    if (composition != null && (composition.outputSkuId != sku.id || composition.kind.fulfillment() != cap.fulfillment)) {
        invalid("Composition output and stock path must match the SKU")
    }
    if ((cap.serviceCharging != null) != (cap.fulfillment == FulfillmentKind.SERVICE)) invalid("Service charging is explicit and only applies to non-stock services")
    if (cap.serviceCharging == ServiceCharging.ENTERED_DURATION && i.units[sku.baseUnitId]?.dimension != UnitDimension.DURATION) {
        invalid("Entered-duration service requires a duration UOM")
    }
    val container = cap.container
    if (container != null && (!stocked || cap.measured || i.units[sku.baseUnitId]?.dimension != UnitDimension.COUNT)) {
        invalid("Reusable containers require tracked whole-count inventory")
    }
    container?.deposit?.let {
        if (it.amountPerContainer.currency != Currency.EGP || it.taxCategoryId !in i.taxCategories) {
            invalid("Deposit needs EGP and explicit retained tax/accounting category mapping")
        }
    }
}

private fun validateConversion(i: CatalogIndex, conversion: ProductUnitConversion, c: CatalogDiagnostics) {
    if (!c.spend()) return
    val source = i.units[conversion.sourceUnitId]
    val target = i.units[conversion.targetUnitId]
    if (conversion.productId !in i.products || source == null || target == null) {
        c.add(CatalogIssueCode.MISSING_REFERENCE, conversion.id, detail = "Conversion product/source/target is missing")
        return
    }
    if (source.dimension == target.dimension) {
        if (conversion.massVolumeEvidenceId != null) {
            c.add(CatalogIssueCode.INVALID_CONVERSION, conversion.id, detail = "Mass/volume evidence is not a generic dimension bypass")
        }
        return
    }
    val massVolume = setOf(source.dimension, target.dimension) == setOf(UnitDimension.MASS, UnitDimension.VOLUME)
    if (!massVolume || conversion.massVolumeEvidenceId == null) {
        c.add(CatalogIssueCode.INVALID_CONVERSION, conversion.id, detail = "Cross-dimension conversion requires product-specific evidenced mass/volume; other cross dimensions are unsupported")
    }
}

private fun validateComposition(i: CatalogIndex, composition: CatalogComposition, c: CatalogDiagnostics) {
    if (!c.spend()) return
    val output = i.skus[composition.outputSkuId]
    if (output == null) {
        c.add(CatalogIssueCode.MISSING_REFERENCE, composition.id, composition.outputSkuId, "Composition output SKU is missing")
        return
    }
    if (composition.components.isEmpty()) c.add(CatalogIssueCode.INVALID_COMPOSITION, composition.id, detail = "Composition needs explicit components")
    unique(composition.components, { it.id }, c, "composition component ID", composition.id)
    if (composition.outputQuantity.unitId != output.baseUnitId ||
        CatalogQuantities.resolveBase(i, output, composition.outputQuantity, null) is CatalogQuantityResult.Rejected
    ) c.add(CatalogIssueCode.INVALID_COMPOSITION, composition.id, output.id, "Output quantity must be exact positive base/serial units")
    val isCurrent = output.status == CatalogStatus.ACTIVE && output.capabilities.composition == composition.reference
    for (component in composition.components) {
        if (!c.spend()) return
        val sku = i.skus[component.skuId]
        if (sku == null) {
            c.add(CatalogIssueCode.MISSING_REFERENCE, composition.id, component.skuId, "Component SKU is missing; archive rather than delete referenced records")
            continue
        }
        if (isCurrent && (sku.status != CatalogStatus.ACTIVE || i.products[sku.productId]?.status != CatalogStatus.ACTIVE)) {
            c.add(CatalogIssueCode.ARCHIVED_REFERENCE, composition.id, sku.id, "Current composition needs an active component")
        }
        if (composition.kind != CompositionKind.VIRTUAL_KIT && sku.capabilities.tracking == StockTracking.NONE) {
            c.add(CatalogIssueCode.INVALID_COMPOSITION, composition.id, sku.id, "Issued/assembled ingredients must be stocked; virtual kits alone delegate mixed fulfillment")
        }
        if (CatalogQuantities.resolveBase(i, sku, component.quantity, component.conversion) is CatalogQuantityResult.Rejected) {
            c.add(CatalogIssueCode.INVALID_COMPOSITION, composition.id, sku.id, "Component quantity/conversion must be exact and dimensionally bound to that product")
        }
    }
}

internal fun CompositionKind.fulfillment(): FulfillmentKind = when (this) {
    CompositionKind.MADE_TO_ORDER -> FulfillmentKind.MADE_TO_ORDER
    CompositionKind.PREPARED_BATCH -> FulfillmentKind.PREPARED_BATCH
    CompositionKind.VIRTUAL_KIT -> FulfillmentKind.VIRTUAL_KIT
    CompositionKind.ASSEMBLED_KIT -> FulfillmentKind.ASSEMBLED_KIT
}

private fun validateVersionFamilies(i: CatalogIndex, c: CatalogDiagnostics) {
    val d = i.definition
    val activeById = d.activePriceRules.associateBy { it.id }
    val contextsById = d.policy.enabledContexts.associateBy { it.id }
    d.conversions.groupBy { it.id }.forEach { (id, versions) ->
        val first = versions.first()
        if (versions.any { it.productId != first.productId || it.sourceUnitId != first.sourceUnitId || it.targetUnitId != first.targetUnitId }) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, id, detail = "Conversion identity cannot be repointed in another revision")
        }
    }
    d.compositions.groupBy { it.id }.forEach { (id, versions) ->
        val first = versions.first()
        if (versions.any { it.outputSkuId != first.outputSkuId || it.kind != first.kind }) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, id, detail = "Composition identity cannot change output SKU or fulfillment path")
        }
    }
    d.priceRules.groupBy { it.id }.forEach { (id, versions) ->
        val first = versions.first()
        if (versions.any { it.target != first.target || it.context.id != first.context.id || it.quantityRange.minimum.unitId != first.quantityRange.minimum.unitId }) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, id, detail = "Price rule identity cannot be repointed to another target/context/UOM")
        }
        val current = activeById[id]
        if (current != null && versions.any { it.revision > current.revision }) {
            c.add(CatalogIssueCode.REVISION_REGRESSION, id, detail = "Current price revision is older than a retained company version; stage future drafts outside the active catalog")
        }
    }
    d.priceContexts.groupBy { it.id }.forEach { (id, versions) ->
        if (versions.any { it.kind != versions.first().kind }) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, id, detail = "Context kind changes need a new context identity")
        }
        val current = contextsById[id]
        if (current != null && versions.any { it.revision > current.revision }) {
            c.add(CatalogIssueCode.REVISION_REGRESSION, id, detail = "Enabled price context cannot regress to an older retained version")
        }
    }
}

private fun validatePrices(i: CatalogIndex, c: CatalogDiagnostics) {
    val d = i.definition
    val activeReferences = d.activePriceRules.toSet()
    for (ref in d.activePriceRules) {
        if (ref !in i.prices) c.add(CatalogIssueCode.MISSING_REFERENCE, ref.id, detail = "Active company price version is missing")
    }
    for (rule in d.priceRules) {
        if (!c.spend()) return
        val context = i.contexts[rule.context]
        if (context == null || rule.terms.taxCategoryId !in i.taxCategories || rule.quantityRange.minimum.unitId !in i.units) {
            c.add(CatalogIssueCode.MISSING_REFERENCE, rule.id, detail = "Price context, UOM or tax category is missing")
        }
        if (rule.terms.unitPrice.currency != Currency.EGP || rule.terms.unitPrice.amount < ExactDecimal.parse("0")) {
            c.add(CatalogIssueCode.INVALID_PRICE_RULE, rule.id, detail = "Release-one sale prices must be nonnegative EGP")
        }
        val targetExists = when (val target = rule.target) {
            is CatalogPriceTarget.Sku -> target.id in i.skus
            is CatalogPriceTarget.Product -> target.id in i.products
            is CatalogPriceTarget.Category -> target.id in i.categories
            CatalogPriceTarget.Standard -> true
        }
        if (!targetExists) c.add(CatalogIssueCode.MISSING_REFERENCE, rule.id, detail = "Price target identity is missing")
        if (context != null) {
            val untiered = rule.quantityRange.minimum.scaled == 0L && rule.quantityRange.maximumExclusive == null
            val validShape = when (context.kind) {
                PriceContextKind.STANDARD -> rule.channel == null && untiered
                PriceContextKind.CHANNEL -> rule.channel != null && untiered
                PriceContextKind.QUANTITY_TIER -> rule.quantityRange.minimum.scaled > 0
            }
            if (!validShape) c.add(CatalogIssueCode.INVALID_PRICE_RULE, rule.id, detail = "Context kind does not match explicit channel/tier fields")
        }
        if (rule.reference !in activeReferences) continue
        if (rule.context !in d.policy.enabledContexts) {
            c.add(CatalogIssueCode.INVALID_POLICY, rule.id, rule.context.id, "Active price rule uses a context not explicitly enabled")
        }
        if (rule.channel != null && rule.channel !in d.policy.channelSpecificPricesEnabled) {
            c.add(CatalogIssueCode.INVALID_POLICY, rule.id, detail = "Channel-specific pricing must be explicitly enabled")
        }
        if (rule.channel != null && rule.channel !in d.policy.salesChannels) {
            c.add(CatalogIssueCode.INVALID_POLICY, rule.id, detail = "Price rule channel is not admitted")
        }
        var hasTarget = false
        for (sku in d.skus) {
            if (!c.spend()) return
            if (!isSellable(i, sku) || !targetMatches(i, rule.target, sku)) continue
            val scale = saleScale(sku, rule.quantityRange.minimum.unitId) ?: continue
            hasTarget = true
            try {
                rule.quantityRange.minimum.rescaleExact(scale)
                rule.quantityRange.maximumExclusive?.rescaleExact(scale)
                if (sku.capabilities.tracking == StockTracking.SERIAL && rule.quantityRange.minimum.unitId == sku.baseUnitId) {
                    rule.quantityRange.minimum.requireWholeUnits()
                    rule.quantityRange.maximumExclusive?.requireWholeUnits()
                }
            } catch (_: ArithmeticException) {
                c.add(CatalogIssueCode.INVALID_PRICE_RULE, rule.id, sku.id, "Quantity tier boundary is not exact in target sale UOM precision")
            } catch (_: IllegalArgumentException) {
                c.add(CatalogIssueCode.INVALID_PRICE_RULE, rule.id, sku.id, "Serial tier boundaries must use whole base units")
            }
        }
        if (!hasTarget) c.add(CatalogIssueCode.INVALID_PRICE_RULE, rule.id, detail = "Active price rule matches no active sellable SKU/UOM")
    }
}

internal fun saleScale(sku: CatalogSku, unitId: DomainId): Int? =
    if (unitId == sku.baseUnitId) sku.quantityScale else sku.saleUnits.singleOrNull { it.unitId == unitId }?.scale

internal fun isSellable(i: CatalogIndex, sku: CatalogSku): Boolean = sku.status == CatalogStatus.ACTIVE &&
    sku.capabilities.canSell && i.products[sku.productId]?.status == CatalogStatus.ACTIVE

internal fun targetMatches(i: CatalogIndex, target: CatalogPriceTarget, sku: CatalogSku): Boolean = when (target) {
    is CatalogPriceTarget.Sku -> target.id == sku.id
    is CatalogPriceTarget.Product -> target.id == sku.productId
    is CatalogPriceTarget.Category -> target.id in (i.products[sku.productId]?.categoryIds ?: emptyList())
    CatalogPriceTarget.Standard -> true
}

/** Iterative DFS of CURRENT compositions only. Historical snapshots are not spliced into today's graph. */
private fun validateComponentGraph(i: CatalogIndex, c: CatalogDiagnostics) {
    val graph = i.definition.skus.filter { it.status == CatalogStatus.ACTIVE }.associate { sku ->
        sku.id to (sku.capabilities.composition?.let { i.compositions[it] }?.components?.map { it.skuId } ?: emptyList())
    }
    val color = mutableMapOf<DomainId, Int>()
    data class Frame(val id: DomainId, var next: Int)
    for (root in graph.keys.sortedBy { it.value }) {
        if (color[root] != null) continue
        val stack = mutableListOf(Frame(root, 0))
        color[root] = 1
        while (stack.isNotEmpty()) {
            if (!c.spend()) return
            val frame = stack.last()
            val children = graph[frame.id] ?: emptyList()
            if (frame.next == children.size) {
                color[frame.id] = 2
                stack.removeAt(stack.lastIndex)
                continue
            }
            val next = children[frame.next++]
            when (color[next]) {
                1 -> c.add(CatalogIssueCode.COMPONENT_CYCLE, frame.id, next, "Current recipe/kit component graph contains a cycle")
                2 -> Unit
                else -> { color[next] = 1; stack += Frame(next, 0) }
            }
        }
    }
}

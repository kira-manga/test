package com.ukkera.pos.catalog

import com.ukkera.pos.types.DomainId

internal fun validateHistory(
    current: CatalogIndex,
    history: CatalogHistorySnapshot,
    previous: ValidatedCompanyCatalog?,
    c: CatalogDiagnostics,
) {
    val d = current.definition
    if (history.scope != d.scope || (previous != null && previous.definition.scope != d.scope)) {
        c.add(CatalogIssueCode.WRONG_SCOPE, detail = "History/previous catalog must belong to the exact company")
        return
    }
    if (!history.complete) {
        c.add(CatalogIssueCode.HISTORY_UNAVAILABLE, detail = "Unknown history cannot authorize identity changes or deletion")
        return
    }
    if (history.previousCatalogRevision != previous?.definition?.revision) {
        c.add(CatalogIssueCode.STALE_HISTORY, detail = "History must be bound to the exact previous catalog revision")
        return
    }
    if (previous == null) {
        if (!history.isEmpty()) c.add(CatalogIssueCode.STALE_HISTORY, detail = "Nonempty history requires its previous catalog baseline")
        return
    }
    val old = previous.index
    if (d.revision < old.definition.revision) {
        c.add(CatalogIssueCode.REVISION_REGRESSION, detail = "Company publication revision cannot go backwards")
    }
    if (d.revision == old.definition.revision && !sameCatalog(d, old.definition)) {
        c.add(CatalogIssueCode.VERSION_REWRITTEN, detail = "A company catalog revision cannot be rewritten")
    }
    val newPolicy = d.policy
    val oldPolicy = old.definition.policy
    if (newPolicy.id == oldPolicy.id) {
        if (newPolicy.revision < oldPolicy.revision) c.add(CatalogIssueCode.REVISION_REGRESSION, newPolicy.id, detail = "Company policy revision regressed")
        if (newPolicy.revision == oldPolicy.revision && !newPolicy.sameContent(oldPolicy)) {
            c.add(CatalogIssueCode.VERSION_REWRITTEN, newPolicy.id, detail = "Company policy content changed without a new policy revision")
        }
    }
    for (sku in d.skus) {
        if (!c.spend()) return
        val before = old.skus[sku.id] ?: continue
        if (sku.revision < before.revision) c.add(CatalogIssueCode.REVISION_REGRESSION, sku.id, detail = "SKU revision regressed")
        if (sku.revision == before.revision && !sku.sameContent(before)) {
            c.add(CatalogIssueCode.VERSION_REWRITTEN, sku.id, detail = "SKU content changed without a new revision")
        }
        sku.saleUnits.forEach { sale ->
            val priorSale = before.saleUnits.singleOrNull { it.unitId == sale.unitId }
            if (priorSale != null && priorSale.conversion.id == sale.conversion.id && priorSale.conversion.revision > sale.conversion.revision) {
                c.add(CatalogIssueCode.REVISION_REGRESSION, sku.id, sale.conversion.id, "Current sale conversion cannot silently regress")
            }
        }
    }
    for (product in d.products) {
        if (!c.spend()) return
        val before = old.products[product.id] ?: continue
        if (product.revision < before.revision) c.add(CatalogIssueCode.REVISION_REGRESSION, product.id, detail = "Product revision regressed")
        if (product.revision == before.revision && !product.sameContent(before)) {
            c.add(CatalogIssueCode.VERSION_REWRITTEN, product.id, detail = "Product content changed without a new revision")
        }
    }
    for (binding in d.barcodes) {
        val before = old.barcodes[binding.id] ?: continue
        if (binding.revision < before.revision) c.add(CatalogIssueCode.REVISION_REGRESSION, binding.id, detail = "Barcode binding revision regressed")
        if (binding.revision == before.revision && binding != before) {
            c.add(CatalogIssueCode.VERSION_REWRITTEN, binding.id, detail = "Barcode binding content changed without a new revision")
        }
    }
    // Version registries are append-only in this foundation. Removing a current pointer archives a
    // price/context; changing a recipe/pack adds a version. A future protected-archive workflow may
    // relocate this evidence, but a list omission cannot stand in for that workflow.
    retainVersions(old.conversions, current.conversions, { a, b -> a == b }, { it.id }, c, "conversion")
    retainVersions(old.compositions, current.compositions, { a, b -> a.sameContent(b) }, { it.id }, c, "composition")
    retainVersions(old.prices, current.prices, { a, b -> a == b }, { it.id }, c, "company price")
    retainVersions(old.contexts, current.contexts, { a, b -> a == b }, { it.id }, c, "price context")

    for (unit in d.units) {
        val before = old.units[unit.id]
        if (before != null && before != unit) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, unit.id, detail = "Unit code/dimension/precision identity is immutable; use a new unit ID")
        }
    }
    for (namespace in d.barcodeNamespaces) {
        val before = old.namespaces[namespace.id]
        if (before != null && before != namespace) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, namespace.id, detail = "Barcode namespace/symbology identity cannot be reinterpreted")
        }
    }
    for (category in d.taxCategories) {
        val before = old.taxCategories[category.id]
        if (before != null && before != category) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, category.id, detail = "Tax category code identity cannot be reassigned")
        }
    }
    checkHistoryReferences(history, old, current, c)
    val protectedSkus = history.usedSkuIds.toMutableSet()
    for (ref in history.retainedCompositions) {
        old.compositions[ref]?.let { composition ->
            protectedSkus += composition.outputSkuId
            protectedSkus += composition.components.map { it.skuId }
        }
    }
    for (id in protectedSkus) {
        if (!c.spend()) return
        val before = old.skus[id] ?: continue
        val after = current.skus[id] ?: continue // Missing IDs have already been diagnosed.
        if (before.productId != after.productId || before.key != after.key ||
            before.variantValues.associate { it.axisId to it.value } != after.variantValues.associate { it.axisId to it.value }
        ) c.add(CatalogIssueCode.IDENTIFIER_CHANGED, id, detail = "Used SKU/product/key/variant identity must remain; archive and create a new SKU")
        if (before.baseUnitId != after.baseUnitId || before.quantityScale != after.quantityScale ||
            before.capabilities.tracking != after.capabilities.tracking || before.capabilities.measured != after.capabilities.measured ||
            before.capabilities.requiresExpiry != after.capabilities.requiresExpiry || before.capabilities.fulfillment != after.capabilities.fulfillment
        ) c.add(CatalogIssueCode.STOCK_IDENTITY_CHANGED, id, detail = "Used stock unit/precision/tracking/fulfillment cannot change without a controlled migration, which is not implemented here")
        val beforeProduct = old.products[before.productId]
        val afterProduct = current.products[before.productId]
        for (choice in before.variantValues) {
            val oldAxis = beforeProduct?.variantAxes?.singleOrNull { it.id == choice.axisId }
            val newAxis = afterProduct?.variantAxes?.singleOrNull { it.id == choice.axisId }
            if (oldAxis == null || newAxis == null || oldAxis.code != newAxis.code || choice.value !in newAxis.values) {
                c.add(CatalogIssueCode.IDENTIFIER_CHANGED, id, choice.axisId, "Retain the declared identities/values of a used variant, even when archived")
            }
        }
    }
    val protectedBarcodeIds = (history.retainedBarcodeIds + old.definition.barcodes.filter { it.skuId in protectedSkus }.map { it.id }).toSet()
    for (id in protectedBarcodeIds) {
        if (!c.spend()) return
        val before = old.barcodes[id] ?: continue
        val after = current.barcodes[id]
        if (after == null) {
            c.add(CatalogIssueCode.HISTORY_RECORD_REMOVED, id, detail = "Archive a used barcode instead of deleting/reusing its identity")
        } else if (before.namespaceId != after.namespaceId || before.value != after.value || before.skuId != after.skuId || before.saleUnitId != after.saleUnitId) {
            c.add(CatalogIssueCode.IDENTIFIER_CHANGED, id, detail = "Used barcode namespace/value/SKU/unit identity cannot be repointed; adding an alias is allowed")
        }
    }
}

private fun checkHistoryReferences(history: CatalogHistorySnapshot, old: CatalogIndex, current: CatalogIndex, c: CatalogDiagnostics) {
    fun <K, V> check(refs: List<K>, previous: Map<K, V>, next: Map<K, V>, id: (K) -> DomainId, label: String) {
        for (ref in refs) {
            if (!c.spend()) return
            if (ref !in previous) c.add(CatalogIssueCode.STALE_HISTORY, id(ref), detail = "History $label reference is absent from its baseline")
            if (ref !in next) c.add(CatalogIssueCode.HISTORY_RECORD_REMOVED, id(ref), detail = "Referenced $label must be retained/archived")
        }
    }
    check(history.usedSkuIds, old.skus, current.skus, { it }, "SKU")
    check(history.retainedProductIds, old.products, current.products, { it }, "product")
    check(history.retainedUnitIds, old.units, current.units, { it }, "unit")
    check(history.retainedBarcodeIds, old.barcodes, current.barcodes, { it }, "barcode")
    check(history.retainedConversions, old.conversions, current.conversions, { it.id }, "conversion")
    check(history.retainedCompositions, old.compositions, current.compositions, { it.id }, "composition")
    check(history.retainedPrices, old.prices, current.prices, { it.id }, "price")
    check(history.retainedContexts, old.contexts, current.contexts, { it.id }, "context")
}

private fun <K, V> retainVersions(
    old: Map<K, V>,
    current: Map<K, V>,
    same: (V, V) -> Boolean,
    id: (K) -> DomainId,
    c: CatalogDiagnostics,
    label: String,
) {
    for ((reference, before) in old) {
        if (!c.spend()) return
        val after = current[reference]
        if (after == null) c.add(CatalogIssueCode.HISTORY_RECORD_REMOVED, id(reference), detail = "Retain the $label version; pointer removal is archival, not evidence deletion")
        else if (!same(before, after)) c.add(CatalogIssueCode.VERSION_REWRITTEN, id(reference), detail = "A $label version cannot change content")
    }
}

private fun sameCatalog(a: CompanyCatalogDefinition, b: CompanyCatalogDefinition): Boolean =
    a.scope == b.scope && a.revision == b.revision && a.policy.sameContent(b.policy) && a.units == b.units &&
        a.products.size == b.products.size && a.products.zip(b.products).all { (left, right) -> left.sameContent(right) } &&
        a.skus.size == b.skus.size && a.skus.zip(b.skus).all { (left, right) -> left.sameContent(right) } &&
        a.priceContexts == b.priceContexts && a.priceRules == b.priceRules && a.activePriceRules == b.activePriceRules &&
        a.categories == b.categories && a.taxCategories == b.taxCategories && a.barcodeNamespaces == b.barcodeNamespaces &&
        a.barcodes == b.barcodes && a.conversions == b.conversions && a.compositions.size == b.compositions.size &&
        a.compositions.zip(b.compositions).all { (left, right) -> left.sameContent(right) }

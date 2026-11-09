package com.ukkera.pos.catalog

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

public data class CatalogPriceQuery(
    public val skuId: DomainId,
    public val channel: Code,
    public val quantity: Quantity,
    public val at: UtcInstant,
)

public enum class PriceTargetSpecificity { STANDARD, CATEGORY, PRODUCT, SKU }

public data class CatalogPriceRank(
    public val priority: Int,
    public val specificity: PriceTargetSpecificity,
    public val channelSpecific: Boolean,
    public val minimumQuantity: Quantity,
)

public enum class PriceCandidateIneligibility {
    TARGET_MISMATCH,
    OUTSIDE_EFFECTIVE_PERIOD,
    CHANNEL_MISMATCH,
    QUANTITY_UNIT_MISMATCH,
    QUANTITY_OUTSIDE_TIER,
}

public class CatalogPriceCandidatePreview internal constructor(
    public val rule: CompanyPriceRule,
    public val rank: CatalogPriceRank,
    ineligibility: List<PriceCandidateIneligibility>,
    public val selected: Boolean,
) {
    public val ineligibility: List<PriceCandidateIneligibility> =
        frozen(ineligibility, PriceCandidateIneligibility.entries.size, "Price ineligibility reasons")
    public val eligible: Boolean get() = ineligibility.isEmpty()
}

public enum class BranchOverrideDisposition {
    APPLIED,
    OVERRIDES_DISABLED_BY_COMPANY,
    OUTSIDE_EFFECTIVE_PERIOD,
    DIFFERENT_SELECTED_CONTEXT_OR_RULE,
    SUPERSEDED_COMPANY_REVISION,
    NO_SELECTED_COMPANY_PRICE,
}

public data class BranchOverridePreview(
    public val reference: CatalogVersionRef,
    public val basedOn: CompanyPriceReference,
    public val unitPrice: UnitPrice,
    public val disposition: BranchOverrideDisposition,
)

/**
 * New-line snapshot only. It does not reprice a quote, collection, part-payment, return or posted
 * receipt. Adapters preserve both company/branch version domains, pin tax separately, and recheck
 * current activation, stock and authorization in the real transaction before committing effects.
 */
public class SelectedCatalogPrice internal constructor(
    public val company: CompanyScope,
    public val branch: BranchScope,
    /** Diagnostic publication version; not the branch-override revision fence. */
    public val companyCatalogRevision: Revision,
    public val companyPolicyRevision: Revision,
    public val productId: DomainId,
    public val skuId: DomainId,
    categoryIds: List<DomainId>,
    public val context: CatalogVersionRef,
    public val companyPrice: CompanyPriceReference,
    public val branchOverride: CatalogVersionRef?,
    /** Quantity in the price rule's explicit UOM. No inferred pack-price multiplication. */
    public val quantity: Quantity,
    public val baseQuantity: Quantity,
    public val conversionSnapshot: ProductUnitConversion?,
    public val unitPrice: UnitPrice,
    public val taxInclusion: CatalogTaxInclusion,
    public val taxCategoryId: DomainId,
    bounds: CatalogBounds,
) {
    public val categoryIds: List<DomainId> = frozen(categoryIds, bounds.maxChildren, "Selected categories")
}

public enum class CatalogPriceFailure {
    CHANNEL_NOT_ENABLED,
    SKU_NOT_IN_ASSORTMENT,
    SKU_UNAVAILABLE,
    INVALID_QUANTITY,
    NO_ELIGIBLE_PRICE,
    AMBIGUOUS_COMPANY_PRICE,
    AMBIGUOUS_BRANCH_OVERRIDE,
}

public sealed interface CatalogPriceOutcome {
    public data class Selected(public val price: SelectedCatalogPrice) : CatalogPriceOutcome
    public data class Unavailable(
        public val reason: CatalogPriceFailure,
        public val quantityFailure: CatalogQuantityFailure? = null,
    ) : CatalogPriceOutcome
}

/** Administrative explanation. The caller must apply protected-query projection/authorization. */
public class CatalogPricePreview internal constructor(
    public val query: CatalogPriceQuery,
    public val profile: PriceSelectionProfile,
    public val outcome: CatalogPriceOutcome,
    candidates: List<CatalogPriceCandidatePreview>,
    overrides: List<BranchOverridePreview>,
    bounds: CatalogBounds,
) {
    public val candidates: List<CatalogPriceCandidatePreview> = frozen(candidates, bounds.maxPriceRules, "Price candidates")
    public val overrides: List<BranchOverridePreview> = frozen(overrides, bounds.maxPriceRules, "Override explanations")
}

/** A concrete reproducible overlap witness and both incompatible prices/tax terms; no silent winner. */
public data class CatalogPriceConflict(
    public val profile: PriceSelectionProfile,
    public val witness: CatalogPriceQuery,
    public val first: CompanyPriceRule,
    public val second: CompanyPriceRule,
)

public object CatalogPriceSelector {
    public fun preview(plan: CatalogActivationPlan, query: CatalogPriceQuery): CatalogPricePreview {
        val catalog = plan.company
        val d = catalog.definition
        val branch = plan.branch
        val index = catalog.index
        val policy = d.policy
        fun unavailable(reason: CatalogPriceFailure, quantityFailure: CatalogQuantityFailure? = null): CatalogPricePreview =
            CatalogPricePreview(query, policy.profile, CatalogPriceOutcome.Unavailable(reason, quantityFailure), emptyList(), emptyList(), d.bounds)
        if (query.channel !in policy.salesChannels) return unavailable(CatalogPriceFailure.CHANNEL_NOT_ENABLED)
        if (query.skuId !in branch.policy.enabledSkuIds) return unavailable(CatalogPriceFailure.SKU_NOT_IN_ASSORTMENT)
        val sku = index.skus[query.skuId] ?: return unavailable(CatalogPriceFailure.SKU_UNAVAILABLE)
        if (!isSellable(index, sku)) return unavailable(CatalogPriceFailure.SKU_UNAVAILABLE)
        val quantities = when (val result = CatalogQuantities.resolveSale(index, sku, query.quantity)) {
            is CatalogQuantityResult.Resolved -> result.snapshot
            is CatalogQuantityResult.Rejected -> return unavailable(CatalogPriceFailure.INVALID_QUANTITY, result.reason)
        }
        val ineligible = index.activePrices.associate { rule -> rule.reference to ineligibility(index, rule, sku, query) }
        val eligible = index.activePrices.filter { ineligible.getValue(it.reference).isEmpty() }.sortedWith(ruleOrder)
        val selected = eligible.firstOrNull()
        val candidates = (eligible + index.activePrices.filter { ineligible.getValue(it.reference).isNotEmpty() }).map { rule ->
            CatalogPriceCandidatePreview(rule, rank(rule), ineligible.getValue(rule.reference), selected?.reference == rule.reference)
        }
        val base = selected?.let { CompanyPriceReference(d.scope, sku.id, it.context, it.reference) }
        val overrides = branch.overrides.filter { it.basedOn.skuId == sku.id }.sortedBy { it.id.value }.map { override ->
            val disposition = when {
                !policy.branchPriceOverridesEnabled -> BranchOverrideDisposition.OVERRIDES_DISABLED_BY_COMPANY
                base == null -> BranchOverrideDisposition.NO_SELECTED_COMPANY_PRICE
                override.basedOn.context.id != base.context.id || override.basedOn.rule.id != base.rule.id ->
                    BranchOverrideDisposition.DIFFERENT_SELECTED_CONTEXT_OR_RULE
                override.basedOn != base -> BranchOverrideDisposition.SUPERSEDED_COMPANY_REVISION
                !override.period.contains(query.at) -> BranchOverrideDisposition.OUTSIDE_EFFECTIVE_PERIOD
                else -> BranchOverrideDisposition.APPLIED
            }
            BranchOverridePreview(override.reference, override.basedOn, override.unitPrice, disposition)
        }
        val applied = overrides.filter { it.disposition == BranchOverrideDisposition.APPLIED }
        val outcome = when {
            selected == null -> CatalogPriceOutcome.Unavailable(CatalogPriceFailure.NO_ELIGIBLE_PRICE)
            eligible.any { samePreIdRank(selected, it) && selected.terms != it.terms } ->
                CatalogPriceOutcome.Unavailable(CatalogPriceFailure.AMBIGUOUS_COMPANY_PRICE)
            applied.size > 1 -> CatalogPriceOutcome.Unavailable(CatalogPriceFailure.AMBIGUOUS_BRANCH_OVERRIDE)
            else -> CatalogPriceOutcome.Selected(
                SelectedCatalogPrice(
                    d.scope, branch.scope, d.revision, policy.revision, sku.productId, sku.id,
                    index.products.getValue(sku.productId).categoryIds, selected.context, checkNotNull(base),
                    applied.singleOrNull()?.reference, quantities.quantity, quantities.baseQuantity, quantities.conversionSnapshot,
                    applied.singleOrNull()?.unitPrice ?: selected.terms.unitPrice,
                    selected.terms.taxInclusion, selected.terms.taxCategoryId, d.bounds,
                ),
            )
        }
        return CatalogPricePreview(query, policy.profile, outcome, candidates, overrides, d.bounds)
    }
}

private fun ineligibility(i: CatalogIndex, rule: CompanyPriceRule, sku: CatalogSku, query: CatalogPriceQuery): List<PriceCandidateIneligibility> {
    val reasons = mutableListOf<PriceCandidateIneligibility>()
    if (!targetMatches(i, rule.target, sku)) reasons += PriceCandidateIneligibility.TARGET_MISMATCH
    if (!rule.period.contains(query.at)) reasons += PriceCandidateIneligibility.OUTSIDE_EFFECTIVE_PERIOD
    if (rule.channel != null && rule.channel != query.channel) reasons += PriceCandidateIneligibility.CHANNEL_MISMATCH
    if (rule.quantityRange.minimum.unitId != query.quantity.unitId) reasons += PriceCandidateIneligibility.QUANTITY_UNIT_MISMATCH
    else if (!rule.quantityRange.contains(query.quantity)) reasons += PriceCandidateIneligibility.QUANTITY_OUTSIDE_TIER
    return reasons
}

internal fun specificity(target: CatalogPriceTarget): PriceTargetSpecificity = when (target) {
    is CatalogPriceTarget.Sku -> PriceTargetSpecificity.SKU
    is CatalogPriceTarget.Product -> PriceTargetSpecificity.PRODUCT
    is CatalogPriceTarget.Category -> PriceTargetSpecificity.CATEGORY
    CatalogPriceTarget.Standard -> PriceTargetSpecificity.STANDARD
}

private fun rank(rule: CompanyPriceRule): CatalogPriceRank =
    CatalogPriceRank(rule.priority, specificity(rule.target), rule.channel != null, rule.quantityRange.minimum)

/** Eligibility is established first, so compared rules use the same requested UOM. */
internal val ruleOrder: Comparator<CompanyPriceRule> = Comparator { left, right ->
    var result = right.priority.compareTo(left.priority)
    if (result == 0) result = specificity(right.target).compareTo(specificity(left.target))
    if (result == 0) result = (right.channel != null).compareTo(left.channel != null)
    if (result == 0) result = right.quantityRange.minimum.compareTo(left.quantityRange.minimum)
    if (result == 0) result = left.id.value.compareTo(right.id.value)
    result
}

internal fun samePreIdRank(left: CompanyPriceRule, right: CompanyPriceRule): Boolean =
    left.priority == right.priority && specificity(left.target) == specificity(right.target) &&
        (left.channel != null) == (right.channel != null) &&
        left.quantityRange.minimum.unitId == right.quantityRange.minimum.unitId &&
        left.quantityRange.minimum.compareTo(right.quantityRange.minimum) == 0

internal fun findPriceConflicts(i: CatalogIndex, c: CatalogDiagnostics) {
    data class OverlapGroup(val priority: Int, val target: PriceTargetSpecificity, val channel: Code?, val unit: DomainId, val minimum: String)
    val groups = i.activePrices.groupBy {
        OverlapGroup(it.priority, specificity(it.target), it.channel, it.quantityRange.minimum.unitId, it.quantityRange.minimum.toFraction().canonicalString())
    }
    val skus = i.definition.skus.sortedBy { it.id.value }
    for (group in groups.values) {
        for (leftIndex in group.indices) {
            val left = group[leftIndex]
            for (rightIndex in leftIndex + 1 until group.size) {
                if (!c.spend()) return
                val right = group[rightIndex]
                if (left.terms == right.terms || !left.period.overlaps(right.period)) continue
                val at = maxOf(left.period.from, right.period.from)
                for (sku in skus) {
                    if (!c.spend()) return
                    if (!isSellable(i, sku) || !targetMatches(i, left.target, sku) || !targetMatches(i, right.target, sku)) continue
                    val scale = saleScale(sku, left.quantityRange.minimum.unitId) ?: continue
                    val minimum = left.quantityRange.minimum.rescaleExact(scale)
                    val quantity = if (minimum.scaled > 0) minimum else if (
                        sku.capabilities.tracking == StockTracking.SERIAL && minimum.unitId == sku.baseUnitId
                    ) Quantity.parse(minimum.unitId, "1", scale) else Quantity(minimum.unitId, 1, scale)
                    if (!left.quantityRange.contains(quantity) || !right.quantityRange.contains(quantity) ||
                        CatalogQuantities.resolveSale(i, sku, quantity) is CatalogQuantityResult.Rejected
                    ) continue
                    val channel = left.channel ?: i.definition.policy.salesChannels.minBy { it.normalizedKey }
                    val conflict = CatalogPriceConflict(i.definition.policy.profile, CatalogPriceQuery(sku.id, channel, quantity, at), left, right)
                    if (c.conflicts.size < c.bounds.maxIssues) c.conflicts += conflict else c.truncated = true
                    c.add(CatalogIssueCode.PRICE_AMBIGUITY, left.id, right.id, "Equal pre-ID rank overlaps with incompatible price/tax terms; see concrete witness")
                    break
                }
            }
        }
    }
}

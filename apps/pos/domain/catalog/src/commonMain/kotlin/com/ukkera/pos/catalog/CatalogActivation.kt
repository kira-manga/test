package com.ukkera.pos.catalog

import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

public sealed interface CatalogPublicationEvidence {
    /** Offline arbitrary company-global codes cannot become active merely by passing local validation. */
    public data object PendingCompanyPublication : CatalogPublicationEvidence

    /** Trusted repository input. The ID itself is not proof of company authority/global uniqueness. */
    public data class CompanyPublicationReference(
        public val scope: CompanyScope,
        public val catalogRevision: Revision,
        public val publicationId: DomainId,
    ) : CatalogPublicationEvidence
}

/**
 * Supplied by the integration/qualification owner. Declaring an enum or accepting a definition
 * is NOT evidence of an executable workflow. No production/hardware/stock capability is assumed.
 */
public class CatalogExecutionManifest(
    public val id: DomainId,
    supported: List<CatalogExecutionCapability>,
    bounds: CatalogBounds = CatalogBounds(),
) {
    public val supported: List<CatalogExecutionCapability> = frozen(supported, bounds.maxChildren, "Execution support manifest")
}

/** Pure plan, not a durable activation/result, authorization, freshness lease or stock reservation. */
public class CatalogActivationPlan internal constructor(
    public val company: ValidatedCompanyCatalog,
    public val branch: BranchCatalogDefinition,
    public val publicationReference: CatalogPublicationEvidence.CompanyPublicationReference,
    public val executionManifestId: DomainId,
    public val validatedAt: UtcInstant,
    requiredCapabilities: List<CatalogExecutionCapability>,
) {
    public val requiredCapabilities: List<CatalogExecutionCapability> =
        frozen(requiredCapabilities, CatalogExecutionCapability.entries.size, "Required capabilities")
}

public sealed interface BranchCatalogValidation {
    public data class Ready(public val plan: CatalogActivationPlan) : BranchCatalogValidation
    public class Rejected internal constructor(issues: List<CatalogIssue>, public val diagnosticsTruncated: Boolean, bounds: CatalogBounds) : BranchCatalogValidation {
        public val issues: List<CatalogIssue> = frozen(issues, bounds.maxIssues, "Branch activation issues")
    }
}

public object BranchCatalogValidator {
    /**
     * Snapshot validation. A carried superseded override is inert, not a reason to block checkout.
     * New/edit commands must additionally call BranchPriceOverrideAdmission and the real security
     * gateway. Activation and authoritative history/manifest reads must be committed atomically.
     */
    public fun planActivation(
        company: ValidatedCompanyCatalog,
        branch: BranchCatalogDefinition,
        publication: CatalogPublicationEvidence,
        execution: CatalogExecutionManifest,
        at: UtcInstant,
        previousBranch: BranchCatalogDefinition? = null,
    ): BranchCatalogValidation {
        val d = company.definition
        val i = company.index
        val c = CatalogDiagnostics(d.bounds)
        if (branch.scope.company() != d.scope || branch.policy.scope != branch.scope) {
            c.add(CatalogIssueCode.WRONG_SCOPE, detail = "Branch/policy must belong to the exact company and branch")
        }
        if (branch.companyCatalogRevision != d.revision || branch.policy.companyPolicy != d.policy.reference) {
            c.add(CatalogIssueCode.STALE_COMPANY_BINDING, detail = "Branch activation must bind the exact received company catalog and policy")
        }
        val publicationReference = publication as? CatalogPublicationEvidence.CompanyPublicationReference
        if (publicationReference == null) {
            c.add(CatalogIssueCode.PUBLICATION_REQUIRED, detail = "Company-global identity creation remains pending authoritative publication")
        } else if (publicationReference.scope != d.scope || publicationReference.catalogRevision != d.revision) {
            c.add(CatalogIssueCode.STALE_COMPANY_BINDING, detail = "Company publication reference does not match this catalog")
        }
        if (branch.overrides.size.toLong() + branch.historicalOverrides.size.toLong() > d.bounds.maxPriceRules.toLong() ||
            branch.policy.enabledSkuIds.size > d.bounds.maxEntities ||
            branch.policy.enabledCapabilities.size > d.bounds.maxChildren || execution.supported.size > d.bounds.maxChildren ||
            branch.policy.optionalActions.size > d.bounds.maxPolicyDeclarations
        ) {
            c.add(CatalogIssueCode.ENGINEERING_BOUND_EXCEEDED, detail = "Branch definition exceeds company activation engineering bounds")
            return BranchCatalogValidation.Rejected(c.issues, c.truncated, d.bounds)
        }
        unique(branch.policy.enabledSkuIds, { it }, c, "branch assortment SKU")
        unique(branch.policy.enabledCapabilities, { it }, c, "enabled execution capability")
        unique(execution.supported, { it }, c, "execution support capability")
        unique(branch.policy.optionalActions, { it.id }, c, "branch declaration ID")
        unique(branch.policy.optionalActions, { it.permission }, c, "branch action permission declaration")
        unique(branch.overrides, { it.id }, c, "current branch override ID")
        val allOverrides = branch.overrides + branch.historicalOverrides
        unique(allOverrides, { it.reference }, c, "branch override version across current/history registries")
        if (!c.valid) return BranchCatalogValidation.Rejected(c.issues, c.truncated, d.bounds)
        if (previousBranch != null) validateBranchRevision(branch, previousBranch, c)
        for (id in branch.policy.enabledSkuIds) {
            if (!c.spend()) break
            val sku = i.skus[id]
            if (sku == null) c.add(CatalogIssueCode.MISSING_REFERENCE, id, detail = "Assortment SKU does not belong to this company catalog")
            else if (!isSellable(i, sku)) c.add(CatalogIssueCode.ARCHIVED_REFERENCE, id, detail = "Branch assortment requires an active sellable SKU/product")
        }
        val highestKnownRevisions = mutableMapOf<DomainId, Revision>()
        for (override in allOverrides) {
            if (!c.spend()) break
            val highest = highestKnownRevisions[override.id]
            if (highest == null || override.revision > highest) highestKnownRevisions[override.id] = override.revision
            val source = i.prices[override.basedOn.rule]
            if (override.scope != branch.scope || override.basedOn.company != d.scope) {
                c.add(CatalogIssueCode.WRONG_SCOPE, override.id, detail = "Override is bound to another company/branch")
            }
            if (source == null || source.context != override.basedOn.context || override.basedOn.skuId !in i.skus) {
                c.add(CatalogIssueCode.MISSING_REFERENCE, override.id, detail = "Override must retain the exact company SKU/context/rule identity it references")
            }
            if (override.unitPrice.currency != Currency.EGP || override.unitPrice.amount < ExactDecimal.parse("0")) {
                c.add(CatalogIssueCode.INVALID_PRICE_RULE, override.id, detail = "Override price must be nonnegative EGP")
            }
        }
        for (override in branch.overrides) {
            if (!c.spend()) break
            if (override.revision < highestKnownRevisions.getValue(override.id)) {
                c.add(CatalogIssueCode.REVISION_REGRESSION, override.id, detail = "Current override cannot regress below its retained historical revision high-water mark")
            }
        }
        val currentOverrides = branch.overrides.filter { it.basedOn.rule in d.activePriceRules && it.basedOn.context in d.policy.enabledContexts }
        if (d.policy.branchPriceOverridesEnabled) {
            groups@ for (group in currentOverrides.groupBy { it.basedOn }.values) {
                for (left in group.indices) for (right in left + 1 until group.size) {
                    if (!c.spend()) break@groups
                    if (group[left].period.overlaps(group[right].period)) {
                        c.add(CatalogIssueCode.OVERRIDE_AMBIGUITY, group[left].id, group[right].id, "Overlapping current overrides require an explicit replacement, not revision/timestamp sorting")
                    }
                }
            }
        }
        if (!c.valid) return BranchCatalogValidation.Rejected(c.issues, c.truncated, d.bounds)
        val required = requiredCapabilities(i, branch, c)
        if (!c.valid) return BranchCatalogValidation.Rejected(c.issues, c.truncated, d.bounds)
        for (capability in required) {
            if (capability !in branch.policy.enabledCapabilities) {
                c.add(CatalogIssueCode.CAPABILITY_NOT_ENABLED, detail = "Branch must explicitly enable required capability $capability")
            }
        }
        // Optional purchasing can be explicitly enabled without making every purchasable retail
        // SKU force an unrelated purchasing setup before a routine new-line price lookup.
        for (capability in branch.policy.enabledCapabilities) {
            if (capability !in execution.supported) c.add(CatalogIssueCode.EXECUTION_UNSUPPORTED, detail = "No supplied qualified execution support for $capability")
        }
        for (id in branch.policy.enabledSkuIds) {
            val sku = i.skus.getValue(id)
            val units = listOf(sku.baseUnitId) + sku.saleUnits.map { it.unitId }
            for (unit in units) {
                var covered = false
                for (rule in i.activePrices) {
                    if (!c.spend()) return BranchCatalogValidation.Rejected(c.issues, c.truncated, d.bounds)
                    if (rule.context == d.policy.standardContext && rule.quantityRange.minimum.unitId == unit &&
                        targetMatches(i, rule.target, sku) && rule.period.contains(at)
                    ) { covered = true; break }
                }
                if (!covered) c.add(CatalogIssueCode.STANDARD_PRICE_REQUIRED, id, unit, "Every enabled sale UOM needs an eligible standard fallback at the activation instant")
            }
        }
        if (!c.valid) return BranchCatalogValidation.Rejected(c.issues, c.truncated, d.bounds)
        return BranchCatalogValidation.Ready(CatalogActivationPlan(company, branch, checkNotNull(publicationReference), execution.id, at, required))
    }
}

private fun validateBranchRevision(next: BranchCatalogDefinition, previous: BranchCatalogDefinition, c: CatalogDiagnostics) {
    if (next.scope != previous.scope) {
        c.add(CatalogIssueCode.WRONG_SCOPE, detail = "Previous branch baseline belongs to another branch")
        return
    }
    if (next.revision < previous.revision) c.add(CatalogIssueCode.REVISION_REGRESSION, detail = "Branch definition revision regressed")
    val policiesEqual = sameBranchPolicy(next.policy, previous.policy)
    if (next.revision == previous.revision && (next.companyCatalogRevision != previous.companyCatalogRevision || !policiesEqual ||
            next.overrides != previous.overrides || next.historicalOverrides != previous.historicalOverrides)) {
        c.add(CatalogIssueCode.VERSION_REWRITTEN, detail = "Branch definition changed without a new revision")
    }
    if (next.policy.id == previous.policy.id) {
        if (next.policy.revision < previous.policy.revision) c.add(CatalogIssueCode.REVISION_REGRESSION, next.policy.id, detail = "Branch policy revision regressed")
        if (next.policy.revision == previous.policy.revision && !policiesEqual) {
            c.add(CatalogIssueCode.VERSION_REWRITTEN, next.policy.id, detail = "Branch policy changed without a new revision")
        }
    }
    if (previous.overrides.size.toLong() + previous.historicalOverrides.size.toLong() > c.bounds.maxPriceRules.toLong()) {
        c.add(CatalogIssueCode.ENGINEERING_BOUND_EXCEEDED, detail = "Prior override history exceeds the current engineering capacity; it cannot be silently discarded")
        return
    }
    val nextByVersion = (next.overrides + next.historicalOverrides).associateBy { it.reference }
    for (before in previous.overrides + previous.historicalOverrides) {
        if (!c.spend()) return
        val retained = nextByVersion[before.reference]
        if (retained == null) c.add(CatalogIssueCode.HISTORY_RECORD_REMOVED, before.id,
            detail = "Retain prior current/historical override versions when replacing or retiring an override")
        else if (retained != before) c.add(CatalogIssueCode.VERSION_REWRITTEN, before.id,
            detail = "Same override ID/revision must retain identical scope/base/price/period terms, including after retirement")
    }
}

private fun sameBranchPolicy(a: BranchCatalogPolicy, b: BranchCatalogPolicy): Boolean = a.scope == b.scope &&
    a.id == b.id && a.revision == b.revision && a.companyPolicy == b.companyPolicy && a.enabledSkuIds == b.enabledSkuIds &&
    a.enabledCapabilities == b.enabledCapabilities && a.optionalActions == b.optionalActions

private fun requiredCapabilities(i: CatalogIndex, branch: BranchCatalogDefinition, c: CatalogDiagnostics): List<CatalogExecutionCapability> {
    val result = mutableSetOf<CatalogExecutionCapability>()
    if (branch.policy.enabledSkuIds.isNotEmpty()) result += CatalogExecutionCapability.BASE_PRICE_SELECTION
    val visited = mutableSetOf<DomainId>()
    val pending = branch.policy.enabledSkuIds.toMutableList()
    while (pending.isNotEmpty()) {
        if (!c.spend()) break
        val id = pending.removeAt(pending.lastIndex)
        if (!visited.add(id)) continue
        val sku = i.skus.getValue(id)
        val cap = sku.capabilities
        if (i.products.getValue(sku.productId).variantAxes.isNotEmpty()) result += CatalogExecutionCapability.VARIANTS
        if (cap.tracking != StockTracking.NONE) result += CatalogExecutionCapability.STOCKED_RETAIL
        if (cap.measured) result += CatalogExecutionCapability.MEASURED_QUANTITY
        if (sku.saleUnits.isNotEmpty()) result += CatalogExecutionCapability.PACK_CONVERSION
        if (cap.tracking == StockTracking.LOT) result += CatalogExecutionCapability.LOT_TRACKING
        if (cap.requiresExpiry) result += CatalogExecutionCapability.EXPIRY_FEFO_QUARANTINE
        if (cap.tracking == StockTracking.SERIAL) result += CatalogExecutionCapability.SERIAL_TRACE
        if (cap.ingredient) result += CatalogExecutionCapability.INGREDIENTS
        when (cap.fulfillment) {
            FulfillmentKind.RETAIL -> Unit
            FulfillmentKind.MADE_TO_ORDER -> result += CatalogExecutionCapability.MADE_TO_ORDER_ISSUE_ON_SEND
            FulfillmentKind.PREPARED_BATCH -> result += CatalogExecutionCapability.PREPARED_BATCH_ACTUAL_YIELD
            FulfillmentKind.VIRTUAL_KIT -> result += CatalogExecutionCapability.VIRTUAL_KIT_COMPONENT_FULFILLMENT
            FulfillmentKind.ASSEMBLED_KIT -> result += CatalogExecutionCapability.ASSEMBLED_KIT_PRODUCTION
            FulfillmentKind.SERVICE -> result += CatalogExecutionCapability.NON_STOCK_SERVICE_COMPLETION
        }
        if (cap.container != null) result += CatalogExecutionCapability.REUSABLE_CONTAINER_MOVEMENT
        if (cap.container?.deposit != null) result += CatalogExecutionCapability.RECEIPT_LINKED_DEPOSIT
        cap.composition?.let { ref ->
            val composition = i.compositions.getValue(ref)
            if (composition.components.any { it.conversion != null }) result += CatalogExecutionCapability.PACK_CONVERSION
            pending += composition.components.map { it.skuId }
        }
    }
    return result.sortedBy { it.ordinal }
}

public enum class CatalogStockEffectContract {
    RETAIL_SKU_RESERVATION_AND_FULFILLMENT,
    MADE_TO_ORDER_COMPONENT_ISSUE_ON_KITCHEN_SEND,
    PRODUCTION_SEND_THEN_ACTUAL_YIELD_AND_FINISHED_SKU_SALE_ONCE,
    VIRTUAL_COMPONENTS_KEEP_THEIR_OWN_TIMING_NO_PARENT_STOCK,
    ASSEMBLY_CONSUMES_COMPONENTS_SALE_CONSUMES_PARENT_ONCE,
    SERVICE_COMPLETION_WITHOUT_STOCK,
}

/** Describes the required execution contract. This function neither issues stock nor completes work. */
public fun SkuCapabilities.stockEffectContract(): CatalogStockEffectContract = when (fulfillment) {
    FulfillmentKind.RETAIL -> CatalogStockEffectContract.RETAIL_SKU_RESERVATION_AND_FULFILLMENT
    FulfillmentKind.MADE_TO_ORDER -> CatalogStockEffectContract.MADE_TO_ORDER_COMPONENT_ISSUE_ON_KITCHEN_SEND
    FulfillmentKind.PREPARED_BATCH -> CatalogStockEffectContract.PRODUCTION_SEND_THEN_ACTUAL_YIELD_AND_FINISHED_SKU_SALE_ONCE
    FulfillmentKind.VIRTUAL_KIT -> CatalogStockEffectContract.VIRTUAL_COMPONENTS_KEEP_THEIR_OWN_TIMING_NO_PARENT_STOCK
    FulfillmentKind.ASSEMBLED_KIT -> CatalogStockEffectContract.ASSEMBLY_CONSUMES_COMPONENTS_SALE_CONSUMES_PARENT_ONCE
    FulfillmentKind.SERVICE -> CatalogStockEffectContract.SERVICE_COMPLETION_WITHOUT_STOCK
}

public enum class BranchOverrideEditFailure {
    WRONG_SCOPE,
    STALE_BRANCH_REVISION,
    COMPANY_OVERRIDES_DISABLED,
    STALE_OR_WRONG_COMPANY_PRICE,
    INVALID_PRICE,
    OVERRIDE_REVISION_CONFLICT,
    OVERLAPPING_OVERRIDE,
    COMPANY_ACTION_CONFIGURATION_INCOMPLETE,
    BRANCH_ACTION_CONFIGURATION_INCOMPLETE,
    UNLIMITED_EXECUTION_UNSUPPORTED,
}

public sealed interface BranchOverrideEditAdmission {
    /** Complete definition fields + current revision binding, NOT permission to save/apply the edit. */
    public data class ReadyForAuthorization(
        public val proposal: BranchPriceOverride,
        public val permission: Permission,
        public val companyDeclaration: CatalogActionDeclaration,
        public val branchDeclaration: CatalogActionDeclaration,
    ) : BranchOverrideEditAdmission
    public class Rejected internal constructor(
        reasons: List<BranchOverrideEditFailure>,
        companyPolicyIssues: List<PolicyConfigurationIssue>,
        branchPolicyIssues: List<PolicyConfigurationIssue>,
    ) : BranchOverrideEditAdmission {
        public val reasons: List<BranchOverrideEditFailure> = frozen(reasons.distinct(), BranchOverrideEditFailure.entries.size, "Override admission reasons")
        public val companyPolicyIssues: List<PolicyConfigurationIssue> = frozen(companyPolicyIssues, PolicyConfigurationIssue.entries.size, "Company policy gaps")
        public val branchPolicyIssues: List<PolicyConfigurationIssue> = frozen(branchPolicyIssues, PolicyConfigurationIssue.entries.size, "Branch policy gaps")
    }
}

public object BranchPriceOverrideAdmission {
    /** No amount-per-unit/basket interpretation is invented here; the gateway computes LimitUse. */
    public fun checkEdit(
        plan: CatalogActivationPlan,
        expectedBranchRevision: Revision,
        proposal: BranchPriceOverride,
    ): BranchOverrideEditAdmission {
        val company = plan.company.definition
        val branch = plan.branch
        val i = plan.company.index
        val reasons = mutableListOf<BranchOverrideEditFailure>()
        if (proposal.scope != branch.scope || proposal.basedOn.company != company.scope) reasons += BranchOverrideEditFailure.WRONG_SCOPE
        if (expectedBranchRevision != branch.revision) reasons += BranchOverrideEditFailure.STALE_BRANCH_REVISION
        if (!company.policy.branchPriceOverridesEnabled) reasons += BranchOverrideEditFailure.COMPANY_OVERRIDES_DISABLED
        val rule = i.prices[proposal.basedOn.rule]
        val sku = i.skus[proposal.basedOn.skuId]
        if (rule == null || sku == null || !isSellable(i, sku) || rule.reference !in company.activePriceRules ||
            rule.context != proposal.basedOn.context || rule.context !in company.policy.enabledContexts || !targetMatches(i, rule.target, sku)
        ) reasons += BranchOverrideEditFailure.STALE_OR_WRONG_COMPANY_PRICE
        if (proposal.unitPrice.currency != Currency.EGP || proposal.unitPrice.amount < ExactDecimal.parse("0")) reasons += BranchOverrideEditFailure.INVALID_PRICE
        val knownVersions = (branch.overrides + branch.historicalOverrides).filter { it.id == proposal.id }
        val highestKnown = knownVersions.maxByOrNull { it.revision }
        val sameVersion = knownVersions.singleOrNull { it.reference == proposal.reference }
        if ((highestKnown != null && proposal.revision < highestKnown.revision) || (sameVersion != null && proposal != sameVersion)) {
            reasons += BranchOverrideEditFailure.OVERRIDE_REVISION_CONFLICT
        }
        if (branch.overrides.any { it.id != proposal.id && it.basedOn == proposal.basedOn && it.period.overlaps(proposal.period) }) {
            reasons += BranchOverrideEditFailure.OVERLAPPING_OVERRIDE
        }
        val permission = Permission(SecurityAction.PRICE_OVERRIDE, PermissionVerb.PERFORM)
        val companyDeclaration = company.policy.optionalActions.singleOrNull { it.permission == permission }
        val branchDeclaration = branch.policy.optionalActions.singleOrNull { it.permission == permission }
        val companyGaps = CatalogPolicyConfiguration.issues(companyDeclaration)
        val branchGaps = CatalogPolicyConfiguration.issues(branchDeclaration)
        if (companyGaps.isNotEmpty()) reasons += BranchOverrideEditFailure.COMPANY_ACTION_CONFIGURATION_INCOMPLETE
        if (branchGaps.isNotEmpty()) reasons += BranchOverrideEditFailure.BRANCH_ACTION_CONFIGURATION_INCOMPLETE
        if (companyDeclaration?.monetaryCeiling == MonetaryCeilingDeclaration.ExplicitNoCeiling ||
            branchDeclaration?.monetaryCeiling == MonetaryCeilingDeclaration.ExplicitNoCeiling
        ) reasons += BranchOverrideEditFailure.UNLIMITED_EXECUTION_UNSUPPORTED
        if (reasons.isNotEmpty()) return BranchOverrideEditAdmission.Rejected(reasons, companyGaps, branchGaps)
        return BranchOverrideEditAdmission.ReadyForAuthorization(proposal, permission, checkNotNull(companyDeclaration), checkNotNull(branchDeclaration))
    }
}

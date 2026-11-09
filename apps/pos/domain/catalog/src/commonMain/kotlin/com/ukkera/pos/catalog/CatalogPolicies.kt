package com.ukkera.pos.catalog

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.RequiredLimits
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** An opt-in technical profile, not a claim that the business prose fixed a universal ranking. */
public enum class PriceSelectionProfile {
    /**
     * Eligibility first; priority DESC, SKU > product > flat category > standard, channel-specific
     * before fallback, minimum quantity DESC, canonical rule UUID ASC. Period start is NOT a rank.
     */
    PRIORITY_FIRST_V1,
}

/** Omission is never infinity. ExplicitNoCeiling is a definition, not execution permission. */
public sealed interface MonetaryCeilingDeclaration {
    public data object Missing : MonetaryCeilingDeclaration
    public data class Limited(public val amount: Money) : MonetaryCeilingDeclaration {
        init { require(amount.minor >= 0) }
    }
    public data object ExplicitNoCeiling : MonetaryCeilingDeclaration
}

/**
 * Configuration metadata referring to the EXISTING security action/verb. IDs are not grants,
 * and a complete declaration is not an AuthorizedAction. This does not evaluate actor/scope,
 * delegated ceilings, approval, amount usage, self-elevation or current revocation.
 */
public data class CatalogActionDeclaration(
    public val id: DomainId,
    public val permission: Permission,
    public val grantReferenceId: DomainId?,
    public val monetaryCeiling: MonetaryCeilingDeclaration = MonetaryCeilingDeclaration.Missing,
    public val maximumPercentageBasisPoints: Int? = null,
) {
    init { require(maximumPercentageBasisPoints == null || maximumPercentageBasisPoints in 0..10_000) }
}

public class CompanyCatalogPolicy(
    public val scope: CompanyScope,
    public val id: DomainId,
    public val revision: Revision,
    public val profile: PriceSelectionProfile,
    public val standardContext: CatalogVersionRef,
    enabledContexts: List<CatalogVersionRef>,
    salesChannels: List<Code>,
    channelSpecificPricesEnabled: List<Code> = emptyList(),
    public val branchPriceOverridesEnabled: Boolean = false,
    optionalActions: List<CatalogActionDeclaration> = emptyList(),
    bounds: CatalogBounds = CatalogBounds(),
) {
    public val enabledContexts: List<CatalogVersionRef> = frozen(enabledContexts, bounds.maxChildren, "Enabled contexts")
    public val salesChannels: List<Code> = frozen(salesChannels, bounds.maxChannels, "Sales channels")
    public val channelSpecificPricesEnabled: List<Code> =
        frozen(channelSpecificPricesEnabled, bounds.maxChannels, "Channel price enablement")
    public val optionalActions: List<CatalogActionDeclaration> =
        frozen(optionalActions, bounds.maxPolicyDeclarations, "Optional action declarations")
    public val reference: CatalogVersionRef get() = CatalogVersionRef(id, revision)

    internal fun sameContent(other: CompanyCatalogPolicy): Boolean =
        scope == other.scope && id == other.id && revision == other.revision && profile == other.profile &&
            standardContext == other.standardContext && enabledContexts == other.enabledContexts &&
            salesChannels == other.salesChannels && channelSpecificPricesEnabled == other.channelSpecificPricesEnabled &&
            branchPriceOverridesEnabled == other.branchPriceOverridesEnabled && optionalActions == other.optionalActions
}

/** Execution capabilities are independently admitted. Metadata never implements the workflow. */
public enum class CatalogExecutionCapability {
    BASE_PRICE_SELECTION,
    STOCKED_RETAIL,
    PURCHASING,
    VARIANTS,
    MEASURED_QUANTITY,
    PACK_CONVERSION,
    LOT_TRACKING,
    EXPIRY_FEFO_QUARANTINE,
    SERIAL_TRACE,
    INGREDIENTS,
    MADE_TO_ORDER_ISSUE_ON_SEND,
    PREPARED_BATCH_ACTUAL_YIELD,
    VIRTUAL_KIT_COMPONENT_FULFILLMENT,
    ASSEMBLED_KIT_PRODUCTION,
    NON_STOCK_SERVICE_COMPLETION,
    REUSABLE_CONTAINER_MOVEMENT,
    RECEIPT_LINKED_DEPOSIT,
}

public class BranchCatalogPolicy(
    public val scope: BranchScope,
    public val id: DomainId,
    public val revision: Revision,
    public val companyPolicy: CatalogVersionRef,
    enabledSkuIds: List<DomainId>,
    enabledCapabilities: List<CatalogExecutionCapability>,
    optionalActions: List<CatalogActionDeclaration> = emptyList(),
    bounds: CatalogBounds = CatalogBounds(),
) {
    public val enabledSkuIds: List<DomainId> = frozen(enabledSkuIds, bounds.maxEntities, "Branch assortment")
    public val enabledCapabilities: List<CatalogExecutionCapability> =
        frozen(enabledCapabilities, bounds.maxChildren, "Branch capabilities")
    public val optionalActions: List<CatalogActionDeclaration> =
        frozen(optionalActions, bounds.maxPolicyDeclarations, "Branch optional actions")
    public val reference: CatalogVersionRef get() = CatalogVersionRef(id, revision)
}

public enum class PolicyConfigurationIssue {
    DECLARATION_MISSING,
    GRANT_REFERENCE_MISSING,
    MONETARY_CEILING_MISSING,
    PERCENTAGE_CEILING_MISSING,
    WRONG_CURRENCY,
    EXPLICIT_NO_CEILING_EXECUTION_UNSUPPORTED,
}

public sealed interface SecurityLimitMapping {
    /** Still just input to security's existing evaluator, never an allow decision. */
    public data class Bounded(public val limits: PermissionLimits) : SecurityLimitMapping
    public class Unavailable internal constructor(issues: List<PolicyConfigurationIssue>) : SecurityLimitMapping {
        public val issues: List<PolicyConfigurationIssue> = frozen(issues, PolicyConfigurationIssue.entries.size, "Policy issues")
    }
}

/** On-demand setup checks: unrelated missing optional actions do not block routine selection. */
public object CatalogPolicyConfiguration {
    public fun issues(
        declaration: CatalogActionDeclaration?,
        currency: Currency = Currency.EGP,
    ): List<PolicyConfigurationIssue> {
        if (declaration == null) return frozen(listOf(PolicyConfigurationIssue.DECLARATION_MISSING), 1, "Policy issues")
        val result = mutableListOf<PolicyConfigurationIssue>()
        if (declaration.grantReferenceId == null) result += PolicyConfigurationIssue.GRANT_REFERENCE_MISSING
        val requirements = declaration.permission.action.requiredLimits
        if (requirements != RequiredLimits.NONE && declaration.monetaryCeiling == MonetaryCeilingDeclaration.Missing) {
            result += PolicyConfigurationIssue.MONETARY_CEILING_MISSING
        }
        if (requirements == RequiredLimits.AMOUNT_AND_PERCENTAGE && declaration.maximumPercentageBasisPoints == null) {
            result += PolicyConfigurationIssue.PERCENTAGE_CEILING_MISSING
        }
        val amount = (declaration.monetaryCeiling as? MonetaryCeilingDeclaration.Limited)?.amount
        if (amount != null && amount.currency != currency) result += PolicyConfigurationIssue.WRONG_CURRENCY
        return frozen(result, PolicyConfigurationIssue.entries.size, "Policy issues")
    }

    /** Existing PermissionLimits cannot express explicit unlimited monetary authority. Fail closed. */
    public fun mapToSecurityLimits(declaration: CatalogActionDeclaration?): SecurityLimitMapping {
        val gaps = issues(declaration)
        if (gaps.isNotEmpty()) return SecurityLimitMapping.Unavailable(gaps)
        checkNotNull(declaration)
        if (declaration.monetaryCeiling == MonetaryCeilingDeclaration.ExplicitNoCeiling) {
            return SecurityLimitMapping.Unavailable(listOf(PolicyConfigurationIssue.EXPLICIT_NO_CEILING_EXECUTION_UNSUPPORTED))
        }
        return SecurityLimitMapping.Bounded(
            PermissionLimits(
                (declaration.monetaryCeiling as? MonetaryCeilingDeclaration.Limited)?.amount,
                declaration.maximumPercentageBasisPoints,
            ),
        )
    }
}

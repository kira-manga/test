package com.ukkera.pos.sales.store

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlinx.serialization.Serializable

/** An explicit provenance profile; an absent legacy companion must never imply v2 authority. */
@Serializable
public enum class LocalSalesPriceSourceProfile {
    HISTORICAL_COMPANY_ONLY_V1,
    LOCAL_RETAIL_COMPANY_BRANCH_V2,
}

/**
 * Immutable per-portion selection evidence, not a grant or a current-price lookup. The ordinary
 * pricing snapshot keeps its COMPANY price revision. This companion keeps the independent
 * publication, exact SKU/rule context, optional company policy and branch-override domains.
 * Store it atomically with the quote and copy it unchanged to collection/sale/return history.
 * Every optional policy field is present together. An applied branch override always names its
 * exact signed policy entry; it never changes inherited company tax/eligibility terms.
 */
@Serializable
public data class LocalSalesPriceSourceV2(
    public val scope: BranchScope,
    public val lineId: DomainId,
    public val portionId: DomainId,
    public val skuId: DomainId,
    public val companyPublicationId: DomainId,
    public val companyPriceRuleId: DomainId,
    public val companyPriceRevision: Revision,
    public val companyPriceDigest: PayloadDigest,
    public val companyUnitPrice: UnitPrice,
    public val effectiveUnitPrice: UnitPrice,
    public val policyEntryId: DomainId?,
    public val policyEntryRevision: Revision?,
    public val policyPublicationId: DomainId?,
    public val policyEntryDigest: PayloadDigest?,
    public val branchOverrideEventId: DomainId?,
    public val branchOverrideRevision: Revision?,
) {
    init {
        require(companyUnitPrice.currency == effectiveUnitPrice.currency)
        require(listOf(policyEntryId, policyEntryRevision, policyPublicationId, policyEntryDigest).let { fields ->
            fields.all { it == null } || fields.all { it != null }
        })
        require((branchOverrideEventId == null) == (branchOverrideRevision == null))
        require(branchOverrideEventId == null || policyEntryId != null)
        require(branchOverrideEventId != null || effectiveUnitPrice == companyUnitPrice)
    }
}

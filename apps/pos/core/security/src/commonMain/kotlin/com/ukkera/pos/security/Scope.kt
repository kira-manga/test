package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable

/** Identifiers are targets, never proof that a caller belongs to a tenant. */
@Serializable
public sealed interface TenantScope {
    public val organizationId: DomainId
    public val companyId: DomainId
}

@Serializable
public data class CompanyScope(
    override val organizationId: DomainId,
    override val companyId: DomainId,
) : TenantScope

@Serializable
public data class BranchScope(
    override val organizationId: DomainId,
    override val companyId: DomainId,
    public val branchId: DomainId,
) : TenantScope {
    public fun company(): CompanyScope = CompanyScope(organizationId, companyId)
}

/** A company grant is not implicitly a grant to another company in its organization. */
internal fun TenantScope.contains(other: TenantScope): Boolean = when (this) {
    is BranchScope -> this == other
    is CompanyScope -> organizationId == other.organizationId && companyId == other.companyId
}

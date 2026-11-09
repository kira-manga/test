package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.Money

/** Pure bounded display projection. Parsing creates no native handle or instruction right. */
public class LocalRetailPhysicalProjection private constructor(
    public val requirement: LocalPhysicalJournalRequirementV1,
    public val target: LocalJournalTargetV3,
    public val amount: Money,
    public val tender: LocalTenderViewV1,
) {
    public companion object {
        public fun parse(canonicalRequirement: StoredBytes): LocalRetailPhysicalProjection {
            val requirement = LocalPhysicalJournalRequirementV1.decode(canonicalRequirement)
            val domain = retailDomainRequirementV3(requirement)
            retailJournalDomain(domain.target.kind).validateRequirement(requirement)
            return LocalRetailPhysicalProjection(requirement, domain.target, domain.amount, domain.tender)
        }
    }
}

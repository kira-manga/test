package com.ukkera.pos.sales.store

import com.ukkera.pos.database.NativeOriginalDomain
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.recovery.AuthorityIdentity

/** Pure complete domain-owner metadata, not native custody, currentness or a capacity permit.
 * Both the live gate and Android existing-only census use the same closed decoder. */
internal data class LocalPhysicalDomainOwners(
    val domain: LocalPhysicalJournalDomain,
    val authority: AuthorityIdentity,
    val master: LocalPhysicalCatalogueBinding,
    val client: LocalPhysicalCatalogueBinding,
)

internal fun localPhysicalDomainOwners(requirement: LocalPhysicalJournalRequirementV1): LocalPhysicalDomainOwners {
    val native = NativeOriginalDomain.entries.singleOrNull {
        it.command == requirement.command && it.domainProfile == requirement.domainProfile
    } ?: error("physical.domain.unsupported")
    val domain = when (native) {
        NativeOriginalDomain.RETAIL_PAYMENT_V3 -> LocalRetailPaymentJournalDomain
        NativeOriginalDomain.RETAIL_REFUND_V3 -> LocalRetailRefundJournalDomain
        NativeOriginalDomain.FOOD_PAYMENT_V1 -> LocalFoodCashJournalDomain
        NativeOriginalDomain.FOOD_REFUND_V1 -> LocalFoodRefundCashJournalDomain
        NativeOriginalDomain.SUPPLIER_CASH_V1 -> LocalSupplierCashJournalDomain
    }
    domain.validateRequirement(requirement)
    return when (native) {
        NativeOriginalDomain.RETAIL_PAYMENT_V3, NativeOriginalDomain.RETAIL_REFUND_V3 ->
            retailDomainRequirementV3(requirement).let { value -> LocalPhysicalDomainOwners(domain, value.authority,
                value.masterOwner.binding(requirement.scope, LocalPhysicalSpoolRole.MASTER),
                value.clientOwner.binding(requirement.scope, LocalPhysicalSpoolRole.CLIENT)) }
        NativeOriginalDomain.FOOD_PAYMENT_V1 -> foodPaymentDomain(requirement).let { value ->
            LocalPhysicalDomainOwners(domain, value.authority,
                value.masterOwner.binding(requirement.scope, LocalPhysicalSpoolRole.MASTER),
                value.clientOwner.binding(requirement.scope, LocalPhysicalSpoolRole.CLIENT)) }
        NativeOriginalDomain.FOOD_REFUND_V1 -> foodRefundDomain(requirement).let { value ->
            LocalPhysicalDomainOwners(domain, value.authority,
                value.masterOwner.binding(requirement.scope, LocalPhysicalSpoolRole.MASTER),
                value.clientOwner.binding(requirement.scope, LocalPhysicalSpoolRole.CLIENT)) }
        NativeOriginalDomain.SUPPLIER_CASH_V1 -> supplierCashDomain(requirement).let { value ->
            LocalPhysicalDomainOwners(domain, value.authority,
                value.masterOwner.binding(requirement.scope, LocalPhysicalSpoolRole.MASTER),
                value.clientOwner.binding(requirement.scope, LocalPhysicalSpoolRole.CLIENT)) }
    }
}

/** Closed actual-domain resolution descriptor dispatch, never a guard removal or retail alias. */
internal fun localPhysicalResolutionDescriptor(domain: LocalPhysicalJournalDomain,
    requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes, resolverActorId: DomainId,
    resolution: StoredBytes): StoredBytes {
    check(localPhysicalDomainOwners(requirement).domain === domain)
    return when (domain) {
        LocalRetailPaymentJournalDomain, LocalRetailRefundJournalDomain ->
            LocalRetailResolutionDescriptorV3.create(requirement, original, resolverActorId, resolution)
        LocalFoodCashJournalDomain ->
            LocalFoodPaymentResolutionDescriptor.create(requirement, original, resolverActorId, resolution)
        LocalFoodRefundCashJournalDomain ->
            LocalFoodRefundResolutionDescriptor.create(requirement, original, resolverActorId, resolution)
        LocalSupplierCashJournalDomain ->
            LocalSupplierCashResolutionDescriptor.create(requirement, original, resolverActorId, resolution)
        else -> error("physical.resolution.domain_unsupported")
    }
}

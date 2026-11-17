package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. retail-financial-resource-budget-supporting-v1.0.0.
 * Pure structural/byte-bound checks only. These values grant no native capacity or authority. */
class RetailFinancialResourceBudgetSupportingTest {
    private class Cut {
        val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
        val actor = DomainId.random()
        val master = LocalRetailJournalOwnerV3(DomainId.random(), DomainId.random(), DomainId.random(), DomainId.random())
        val client = LocalRetailJournalOwnerV3(DomainId.random(), DomainId.random(), DomainId.random(), DomainId.random())
        val target = LocalJournalTargetV3(SalesRecordingKind.REFUND_ATTESTATION, DomainId.random(), DomainId.random(),
            DomainId.random(), OperationId.random(), OperationId.random(), OperationId.random(),
            DomainId.random(), DomainId.random(), DomainId.random())

        fun resources(count: Int, kind: RecoveryResourceKind = RecoveryResourceKind.STOCK): List<RecoveryResourceKey> {
            val values = mutableSetOf(cashResource(target.sessionId), entitlementResource(target.documentId),
                entitlementResource(checkNotNull(target.originalSaleId)), entitlementResource(checkNotNull(target.originalClaimId)))
            repeat(count - values.size) { values += RecoveryResourceKey(kind, DomainId.random()) }
            return sortedRetailResources(values)
        }

        fun domain(resources: List<RecoveryResourceKey>) = LocalRetailDomainRequirementV3(3, target,
            Money(Currency.EGP, 11_400L), LocalTenderViewV1("CASH"), Revision(1), Revision(1),
            AuthorityIdentity(DomainId.random(), master.deviceId, Revision(1)), master, client, resources)

        fun requirement(domain: StoredBytes) = LocalPhysicalJournalRequirementV1(scope, actor,
            target.intentOperationId, sha256(byteArrayOf(1)), target.recordingOperationId, target.resolutionOperationId,
            target.attemptId, LocalFinancialJournalProfileV3.REFUND_PROFILE, LocalFinancialJournalProfileV3.RETURN_COMMAND,
            retailJournalTargetBindingV3(target), DomainId.random(), DomainId.random(), master.deviceId, client.deviceId,
            LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1,
            LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1, domain)

        fun requirement(resources: List<RecoveryResourceKey>) = requirement(retailDomainBytesV3(domain(resources)))
    }

    @Test fun oldFortyEightResourceRequirementStillRoundTripsExactly() {
        val cut = Cut(); val resources = cut.resources(48); val value = cut.requirement(resources)
        LocalRetailRefundJournalDomain.validateRequirement(value)
        val restored = LocalPhysicalJournalRequirementV1.decode(value.canonicalBytes)
        assertEquals(value.canonicalBytes, restored.canonicalBytes)
        assertEquals(value.canonicalDomainRequirement, restored.canonicalDomainRequirement)
        assertEquals(resources, retailDomainRequirementV3(restored).financialResources)
        LocalRetailRefundJournalDomain.validateRequirement(restored)
    }

    @Test fun expandedResourceSetsRetainEveryAliasWithinBothExistingByteBounds() {
        for (count in listOf(49, 64)) {
            val cut = Cut(); val resources = cut.resources(count); val value = cut.requirement(resources)
            assertTrue(value.canonicalDomainRequirement.size <= 6_144)
            assertTrue(value.canonicalBytes.size <= 8_192)
            LocalRetailRefundJournalDomain.validateRequirement(value)
            assertEquals(resources, retailDomainRequirementV3(value).financialResources)
        }
    }

    @Test fun sixtyFiveResourcesAreRefusedEvenWhenTheirWireFits() {
        val cut = Cut(); val value = cut.requirement(cut.resources(65))
        assertTrue(value.canonicalDomainRequirement.size <= 6_144)
        assertTrue(value.canonicalBytes.size <= 8_192)
        assertFailsWith<IllegalStateException> { LocalRetailRefundJournalDomain.validateRequirement(value) }
    }

    @Test fun duplicateAndUnsortedResourceSetsStillFailValidation() {
        val cut = Cut(); val resources = cut.resources(49); val baseline = cut.requirement(resources)
        LocalRetailRefundJournalDomain.validateRequirement(baseline)
        val duplicated = cut.requirement(resources.dropLast(1) + resources.first())
        val unsorted = cut.requirement(resources.reversed())
        assertFailsWith<IllegalStateException> { LocalRetailRefundJournalDomain.validateRequirement(duplicated) }
        assertFailsWith<IllegalStateException> { LocalRetailRefundJournalDomain.validateRequirement(unsorted) }
    }

    @Test fun allowedResourceCountCannotExceedTheUnchangedDomainByteLimit() {
        val cut = Cut(); val domain = cut.domain(cut.resources(64, RecoveryResourceKind.REFUND_ENTITLEMENT))
        val measured = localSalesEncode(domain, LocalRetailDomainRequirementV3.serializer(), 16_384)
        assertTrue(measured.size > 6_144, "Supporting fixture must reach the domain byte bound")
        assertFailsWith<LocalSalesWireCapacityExceeded> { retailDomainBytesV3(domain) }
    }

    @Test fun totalFrameLimitStillIncludesAllOuterMetadata() {
        val cut = Cut(); val baseline = cut.requirement(cut.resources(48))
        LocalRetailRefundJournalDomain.validateRequirement(baseline)
        // The constructor must refuse total framing before any domain decoder is invoked.
        assertFailsWith<IllegalArgumentException> { cut.requirement(StoredBytes(ByteArray(8_192) { 1 })) }
    }
}

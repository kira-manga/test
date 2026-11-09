package com.ukkera.pos.sales.store.independent.returnsourceomittedexpectation

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalReturnCaseRequestV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Literal RSE-v1 command and scalar inputs; no source, access or native provenance. */
internal object ReturnSourceOmittedExpectationFrozenInputs {
    const val body = "{\"type\":\"QUALIFY\",\"caseId\":\"c1530000-0000-4000-8000-000000000001\",\"expectedCaseRevision\":\"11\"}"
    val caseId = DomainId("c1530000-0000-4000-8000-000000000001")
    val otherCaseId = DomainId("c1530000-0000-4000-8000-000000000002")
    val operationId = OperationId("c1510000-0000-4000-8000-000000000001")
    val caseRevision = Revision(11)
    val currentEntitlementRevision = Revision(7)
    val request = LocalReturnCaseRequestV1.Qualify(caseId, caseRevision)
    val bytes: StoredBytes get() = StoredBytes(body.encodeToByteArray())

    // The freeze names distinct O1/C1/B1 and A/U, not their concrete UUIDs.
    // These inert identifiers fill those comparison slots; they authenticate nobody.
    val scope = BranchScope(dataId(1), dataId(2), dataId(3))
    val otherBranchScope = BranchScope(scope.organizationId, scope.companyId, dataId(4))
    val otherCompanyScope = BranchScope(scope.organizationId, dataId(5), scope.branchId)
    val otherOrganizationScope = BranchScope(dataId(6), scope.companyId, scope.branchId)
    val ownerId = dataId(7)
    val otherOwnerId = dataId(8)

    fun dataId(serial: Int): DomainId =
        DomainId("c1530d00-0000-4000-8000-${serial.toString().padStart(12, '0')}")
}

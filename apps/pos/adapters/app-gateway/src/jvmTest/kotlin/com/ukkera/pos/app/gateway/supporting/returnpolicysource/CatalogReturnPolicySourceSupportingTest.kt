package com.ukkera.pos.app.gateway.supporting.returnpolicysource

import com.ukkera.pos.catalog.store.LocalCatalogReturnPolicySource
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.sales.store.TransactionSalesReturnPolicySource
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * SELF-AUTHORED SUPPORTING CHECKS — NOT independent requirements-only expectations.
 * Synthetic data only. Confirms the production source composed by the Android and desktop
 * Master runtimes never prepares an accepting policy when the original sale evidence is
 * absent: a missing source is uncertainty (throws), not a default entitlement.
 */
class CatalogReturnPolicySourceSupportingTest {
    private val scope = BranchScope(DomainId("00000000-0000-4000-8000-000000000001"),
        DomainId("00000000-0000-4000-8000-000000000002"), DomainId("00000000-0000-4000-8000-000000000003"))
    private val sale = DomainId("00000000-0000-4000-8000-000000000010")

    @Test
    fun missingOriginalSaleNeverYieldsAPreparedPolicy() = runTest {
        val view = EmptyView(scope)
        val source: TransactionSalesReturnPolicySource = LocalCatalogReturnPolicySource(UnavailableCompanyPublicationSignatureVerifier)
        assertFails { source.current(view, sale) }
        assertTrue(view.aggregateReads > 0)
        assertTrue(view.retainedReads == 0)
    }
}

private class EmptyView(override val scope: BranchScope) : CommandReadView {
    var aggregateReads = 0
    var retainedReads = 0
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? { aggregateReads++; return null }
    override suspend fun claim(key: ClaimKey): DomainId? = null
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? { retainedReads++; return null }
}

package com.ukkera.pos.sales.store.independent.counterreceiptrpc

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.LocalMasterRpcRetainedResultSourceAccessProvider
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptQueryV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadAccessV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadCodec
import com.ukkera.pos.sales.store.LocalSalesHandlers
import com.ukkera.pos.sales.store.UnavailableSalesQuoteSource
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.sales.store.UnavailableSalesTenderSource
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SUPPORT only, CRRPC-V1-002/003/005/008/020. Uses the actual installed commerce
 * wrappers with unavailable sources. No principal, grant, accepted original or
 * production native authority is manufactured; no evaluate/project is authorized.
 */
class CounterReceiptRpcInstalledSupportingTest {
    private val f = CounterReceiptCodecData
    private val scope = BranchScope(DomainId("10000000-0000-4000-8000-000000000001"),
        DomainId("20000000-0000-4000-8000-000000000001"), DomainId("30000000-0000-4000-8000-000000000001"))
    private val stock = LocalStockRepository()
    private val command = LocalSalesHandlers.commands(stock, UnavailableSalesQuoteSource,
        UnavailableSalesTenderSource, UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity)
        .single { it.key == LocalRetailCustomerReceiptCodec.COMMAND }
    private val read = LocalSalesHandlers.reads(stock).single { it.key == LocalRetailCustomerReceiptReadCodec.QUERY }

    @Test
    fun support002ActualInstalledWrappersSelectOnlyTheExplicitReceiptProfile() {
        val body = LocalRetailCustomerReceiptCodec.encode(f.e1Request())
        val head = LocalRetailCustomerReceiptReadCodec.encode(LocalRetailCustomerReceiptQueryV1.Head(f.sale))
        assertEquals(body, command.normalize(body))
        assertEquals(head, read.normalize(head))
        assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(command)
        for (profile in LocalMasterRpcWorkflowProfile.entries) {
            assertEquals(profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
                command.supportsRpcWorkflow(profile, body))
            assertEquals(profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
                read.supportsRpcWorkflow(profile, head))
        }
    }

    @Test
    fun support005And008UnavailableOriginalCannotSupplyRetainedResultDisclosureRequirements() = runTest {
        val body = LocalRetailCustomerReceiptCodec.encode(f.e1Request())
        val view = MissingOriginalData(scope)
        val context = CommandContext(scope, f.owner, f.e1, command.key,
            semanticDigest(scope, f.owner, command.key, body))
        val provider = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(command)
        assertNull(provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
            body, view, context))
        assertTrue(view.aggregateReads > 0)
    }

    @Test
    fun support003ActualInstalledReadSelectsSeparatePermissionsAndRefusesUnapprovedProjection() = runTest {
        for (purpose in LocalRetailCustomerReceiptReadAccessV1.entries) {
            val body = LocalRetailCustomerReceiptReadCodec.encode(LocalRetailCustomerReceiptQueryV1.Head(f.sale, purpose))
            val prepared = read.prepare(body, MissingOriginalData(scope), ReadContext(scope, f.owner, read.key,
                sha256(body.copyBytes())))
            val action = if (purpose == LocalRetailCustomerReceiptReadAccessV1.RECORD)
                SecurityAction.CUSTOMER_RECEIPT_RECORD else SecurityAction.RETURN_RECEIPT_LOOKUP
            assertEquals(Permission(action, PermissionVerb.PERFORM), prepared.requirements.single().permission)
            assertFailsWith<IllegalStateException> { prepared.project(emptyList(), f.e1Request().receivedAt) }
        }
    }

    @Test
    fun support002And020RetainedSelectionRejectsOtherProfileOrMismatchedOriginalContextBeforeSourceAccess() = runTest {
        val body = LocalRetailCustomerReceiptCodec.encode(f.e1Request())
        val view = MissingOriginalData(scope)
        val other = DomainId("50000000-0000-4000-8000-000000000002")
        val context = CommandContext(scope, other, f.e1, command.key,
            semanticDigest(scope, f.owner, command.key, body))
        val provider = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(command)
        assertNull(provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS,
            body, view, context))
        assertFailsWith<IllegalStateException> {
            provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
                body, view, context)
        }
        assertEquals(0, view.aggregateReads)
        assertFalse(read.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS,
            LocalRetailCustomerReceiptReadCodec.encode(LocalRetailCustomerReceiptQueryV1.Head(f.sale))))
    }
}

private class MissingOriginalData(override val scope: BranchScope) : CommandReadView {
    var aggregateReads = 0
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? { aggregateReads++; return null }
    override suspend fun claim(key: ClaimKey): DomainId? = null
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = null
}

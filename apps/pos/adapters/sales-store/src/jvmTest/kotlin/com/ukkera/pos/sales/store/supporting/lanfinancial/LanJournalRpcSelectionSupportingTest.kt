package com.ukkera.pos.sales.store.supporting.lanfinancial

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Self-authored, NOT independent. Bounded selection/source-unavailability support for
 * frozen LAN-A07/D04/D05/D06/R03, qa/pos/designs/lan-master-client-v1/cases.json.
 * These tests create inert request/context DATA only. No native force, financial commit,
 * genuine historical source, current authority or whole LAN pass is asserted. */
class LanJournalRpcSelectionSupportingTest {
    private fun id() = DomainId.random()
    private val profile = LocalMasterRpcWorkflowProfile.LAN_BUSINESS
    private val stock = LocalStockRepository()
    private val collection = LocalJournalCollectionHandler(stock, UnavailableSalesTenderSource)
    private val returned = LocalJournalReturnHandler(stock = stock)
    private val evidence = LocalCashEvidenceV1(id(), UtcInstant.parseCanonical("2026-11-15T00:00:00Z"))

    @Test fun onlyAccountingBodiesUseOrdinaryExecuteWhilePhysicalRecoveryIsSeparate() {
        val start = LocalCollectionRequestV3.Start(id(), Revision(1), id(), Revision(2), Revision(3),
            listOf(LocalSalesStockRevisionV1(id(), Revision(1))), id(),
            LocalTenderSelectionV1.Cash, Money(Currency.EGP, 22_800), recordingOperationId = OperationId.random(),
            resolutionOperationId = OperationId.random(), masterAllocationId = id(), clientAllocationId = id())
        val target = start.target(OperationId.random())
        val nativeBodies = listOf(LocalCollectionCommandCodecV3.encode(start),
            LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.RecordOriginal(target, LocalPaymentOutcomeV3.Unknown(evidence))),
            LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.RecordResolution(target, "a".repeat(64), id(),
                LocalPaymentOutcomeV3.NotPaid(evidence, "synthetic.not-paid"))))
        nativeBodies.forEach { assertFalse(collection.supportsRpcWorkflow(profile, it)) }
        assertTrue(collection.supportsRpcPhysicalRetainedResult(profile, nativeBodies[0]))
        assertFalse(collection.supportsRpcPhysicalRetainedResult(profile, nativeBodies[1]))
        assertTrue(collection.supportsRpcPhysicalRetainedResult(profile, nativeBodies[2]))
        for (phase in LocalJournalFactPhaseV3.entries) {
            val body = LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.ApplyObservation(OperationId.random(), phase))
            assertTrue(collection.supportsRpcWorkflow(profile, body))
            assertFalse(collection.supportsRpcPhysicalRetainedResult(profile, body))
            for (other in LocalMasterRpcWorkflowProfile.entries.filterNot { it == profile })
                assertFalse(collection.supportsRpcWorkflow(other, body))
            val refund = LocalReturnCommandCodecV3.encode(LocalReturnRequestV3.ApplyObservation(OperationId.random(), phase))
            assertTrue(returned.supportsRpcWorkflow(profile, refund))
            assertFalse(returned.supportsRpcPhysicalRetainedResult(profile, refund))
        }
    }

    @Test fun missingNativeOriginalSourcesNeverFallBackToCurrentRowsOrAnAcceptingEmptyCut() = runTest {
        val scope = BranchScope(id(), id(), id())
        val view = object : CommandReadView {
            override val scope = scope
            override suspend fun aggregate(key: AggregateKey): StoredAggregate? = error("Current aggregate must not replace original sources")
            override suspend fun claim(key: ClaimKey): DomainId? = error("Current claim must not replace original sources")
            override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
                error("Current original lookup must not replace native original sources")
        }
        repeat(3) { assertNull(view.financialOriginalSources(OperationId.random())) }
    }

    @Test fun exactFinancialKeysAndVersionsAreClosed() {
        val commands = listOf(LocalFinancialJournalProfileV3.COLLECTION_COMMAND, LocalFinancialJournalProfileV3.RETURN_COMMAND,
            LocalRetailSupervisorCodecV1.COMMAND, LocalFoodPaymentProfile.COMMAND, LocalFoodRefundProfile.COMMAND,
            LocalFoodPaymentSupervisorCodec.COMMAND, LocalSupplierCashProfile.COMMAND)
        for (key in commands) {
            assertTrue(LocalMasterLanBusinessRpcKeysV1.accepts(key))
            assertFalse(LocalMasterLanBusinessRpcKeysV1.accepts(CommandKey(key.name, key.version + 1)))
            assertFalse(LocalMasterLanBusinessRpcKeysV1.accepts(CommandKey(key.name + ".other", key.version)))
        }
        assertTrue(LocalMasterLanBusinessRpcKeysV1.accepts(LocalFinancialReadCodecV3.QUERY))
        assertFalse(LocalMasterLanBusinessRpcKeysV1.accepts(ReadKey(LocalFinancialReadCodecV3.QUERY.name, 4)))
    }
}

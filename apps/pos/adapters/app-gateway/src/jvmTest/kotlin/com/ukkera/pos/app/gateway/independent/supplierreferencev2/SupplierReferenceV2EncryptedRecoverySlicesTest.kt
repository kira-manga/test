package com.ukkera.pos.app.gateway.independent.supplierreferencev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Real encrypted JVM client close/reopen and production gateway recovery, with controlled
 * Master/session/original replies. No real Master restart, native custody/current grant
 * fence/admitted RPC, or stronger L2/L3/L5/platform acceptance is claimed.
 */
class SupplierReferenceV2EncryptedRecoverySlicesTest {
    private val f = SupplierReferenceV2GatewayInputs

    @Test
    fun sref2005_008_lostReplyRetainsExactOriginalAcrossRealClientCloseReopenAndStaleAckCannotClearLaterIntent() = runTest {
        withSupplierReferenceV2Gateway { h ->
            createLegacy(h)
            val intent = f.intent(f.operation(301), f.supplier(f.id(101)), h.master.storage(f.id(101)))
            val gateway = h.gateway()
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val staged = h.host.find(intent.operationId)
            h.master.executeOverride = { request ->
                h.master.commitActual(request)
                MutationReply.Deferred(request.operationId, DeferredReason.STORAGE_UNCERTAIN)
            }
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            val unknown = h.host.find(intent.operationId)
            assertTrue(staged.sameIntent(unknown))
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, unknown.localPhase)
            assertNull(unknown.recorded)
            assertFalse(unknown.acknowledged)
            assertIs<LocalAcknowledgementResult.Unresolved>(gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(listOf(intent.operationId), h.host.pending().map { it.operationId })
            val original = h.master.originals.getValue(intent.operationId)
            assertEquals(Code.exact(f.literal), LocalSupplierCodecV2.decodeResult(original.result).reference)

            h.reopenClient()
            val reopenedGateway = h.gateway()
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalOperationResult.Unresolved>(reopenedGateway.lookup(intent.operationId)).problem)
            assertEquals(unknown, h.host.find(intent.operationId))
            h.reauthenticateOriginalControlledOwner()
            val recovered = assertIs<LocalOperationResult.Recorded>(reopenedGateway.lookup(intent.operationId))
            assertEquals(f.literal, recovered.result!!.values.single().value.english)
            assertEquals(LocalRecordedDecision.APPLIED, recovered.decision)
            assertEquals(staged.semanticSha256, h.host.find(intent.operationId).semanticSha256)
            assertEquals(staged.request().payload, h.host.find(intent.operationId).request().payload)
            assertEquals(StoredKernelResultV1.from(original), h.host.find(intent.operationId).recorded)
            repeat(2) {
                val resent = assertIs<LocalOperationResult.Recorded>(reopenedGateway.submit(intent.operationId))
                assertEquals(recovered.result, resent.result)
                assertEquals(recovered.operationId, resent.operationId)
            }
            assertEquals(1, h.master.executions.size, "Gateway recovery must not redispatch an already found original")
            assertEquals(2, h.master.originals.size)
            assertEquals(1, h.master.rowCount)
            assertIs<LocalAcknowledgementResult.Acknowledged>(reopenedGateway.acknowledgeRecorded(intent.operationId))
            assertTrue(h.host.pending().isEmpty())
            val next = f.intent(f.operation(501), f.supplier(f.id(101), f.mixed), h.master.storage(f.id(101)))
            assertIs<LocalStageResult.Staged>(reopenedGateway.stage(next))
            val later = h.host.find(next.operationId)
            assertIs<LocalAcknowledgementResult.Acknowledged>(reopenedGateway.acknowledgeRecorded(intent.operationId))
            assertEquals(listOf(next.operationId), h.host.pending().map { it.operationId })
            assertEquals(later, h.host.find(next.operationId))
            assertEquals(original, h.master.originals.getValue(intent.operationId))
        }
    }

    @Test
    fun sref2008_cancelAfterControlledHandlerCommitLeavesEncryptedOriginalUnresolvedUntilLookup() = runTest {
        withSupplierReferenceV2Gateway { h ->
            val intent = f.intent(f.operation(202), f.supplier())
            assertIs<LocalStageResult.Staged>(h.gateway().stage(intent))
            val staged = h.host.find(intent.operationId)
            h.master.executeOverride = { request ->
                h.master.commitActual(request)
                throw CancellationException("Controlled lost output after handler Apply")
            }
            assertFailsWith<CancellationException> { h.gateway().submit(intent.operationId) }
            val retained = h.host.find(intent.operationId)
            assertTrue(staged.sameIntent(retained))
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
            assertNull(retained.recorded)
            assertIs<LocalAcknowledgementResult.Unresolved>(h.gateway().acknowledgeRecorded(intent.operationId))
            h.reopenClient()
            h.reauthenticateOriginalControlledOwner()
            val recovered = assertIs<LocalOperationResult.Recorded>(h.gateway().lookup(intent.operationId))
            assertEquals(f.literal, recovered.result!!.values.single().value.english)
            assertEquals(1, h.master.executions.size)
            assertEquals(1, h.master.rowCount)
        }
    }

    @Test
    fun sref2003_007_originalV1OrV2StagingSurvivesReopenAndSupportRemovalNeverDowngradesV2() = runTest {
        for (version in listOf(1, 2)) {
            withSupplierReferenceV2Gateway { h ->
                val intent = f.intent(f.operation(if (version == 1) 201 else 202), f.supplier(text = if (version == 1) null else f.literal), formVersion = version)
                assertIs<LocalStageResult.Staged>(h.gateway().stage(intent))
                val staged = h.host.find(intent.operationId)
                val command = if (version == 1) LocalConfigurationProfile.COMMAND else LocalSupplierProfileV2.COMMAND
                assertEquals(command, staged.request().command)
                assertEquals(version, staged.formVersion)
                h.reopenClient()
                h.reauthenticateOriginalControlledOwner()
                h.installation = LocalWorkflowInstallation.supplierOnly(false)
                val gateway = h.gateway()
                if (version == 2) {
                    assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertIs<LocalPendingResult.Unavailable>(gateway.pending()).problem)
                    assertIs<LocalOperationResult.Unresolved>(gateway.lookup(intent.operationId))
                    assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
                    assertTrue(h.master.executions.isEmpty())
                    assertTrue(h.master.lookups.isEmpty())
                    assertEquals(staged, h.host.find(intent.operationId))
                } else {
                    assertIs<LocalOperationResult.Pending>(gateway.lookup(intent.operationId))
                    assertIs<LocalOperationResult.Recorded>(gateway.submit(intent.operationId))
                    assertEquals(listOf(LocalConfigurationProfile.COMMAND), h.master.executions.map { it.command })
                    assertEquals(staged.request().payload, h.master.executions.single().payload)
                    assertEquals(staged.semanticSha256, h.host.find(intent.operationId).semanticSha256)
                    assertEquals(command, h.host.find(intent.operationId).request().command)
                    assertTrue(h.host.find(intent.operationId).recorded!!.payloadHex.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun sref2005_sameIdLiteralOrNfcNfdInputChangeCannotReplaceEncryptedOriginalOrDispatchFallback() = runTest {
        withSupplierReferenceV2Gateway { h ->
            val intent = f.intent(f.operation(301), f.supplier())
            assertIs<LocalStageResult.Staged>(h.gateway().stage(intent))
            val original = h.host.find(intent.operationId)
            for (text in listOf<String?>(null, "0007-Ab", " 0007-ab  ", " 0007-Ab   ")) {
                val changed = f.intent(intent.operationId, f.supplier(text = text))
                assertIs<LocalStageResult.Uncertain>(h.gateway().stage(changed))
                assertEquals(original, h.host.find(intent.operationId))
            }
            assertTrue(h.master.executions.isEmpty())
            assertEquals(1, h.host.pending().size)
        }
        withSupplierReferenceV2Gateway { h ->
            val intent = f.intent(f.operation(403), f.supplier(text = f.nfc))
            assertIs<LocalStageResult.Staged>(h.gateway().stage(intent))
            val original = h.host.find(intent.operationId)
            assertIs<LocalStageResult.Uncertain>(h.gateway().stage(f.intent(intent.operationId, f.supplier(text = f.nfd))))
            assertEquals(original, h.host.find(intent.operationId))
            assertTrue(h.master.executions.isEmpty())
        }
        // This proves the gateway's saved-input conflict, not a native Master's
        // OPERATION_PAYLOAD_MISMATCH execution or historical identity verification.
    }

    @Test
    fun sref2008_009_wrongEncryptedOwnerAndCorruptClosedJournalRefuseWithoutInitializingEmptyEvidence() = runTest {
        withSupplierReferenceV2Gateway { h ->
            val intent = f.intent(f.operation(202), f.supplier())
            val gateway = h.gateway()
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val original = h.host.find(intent.operationId)
            assertIs<JvmClientJournalOpenResult.Unavailable>(h.host.reopen(f.owner.copy(actorId = f.id(32))))
            assertIs<JvmClientJournalOpenResult.Opened>(h.host.reopen())
            assertEquals(original, h.host.find(intent.operationId))
            val unavailableGateway = h.gateway()
            val originalManifest = h.host.corruptClosedManifest()
            try {
                assertIs<JvmClientJournalOpenResult.Unavailable>(h.host.reopen())
                assertIs<LocalPendingResult.Unavailable>(unavailableGateway.pending())
                assertIs<LocalStageResult.Uncertain>(unavailableGateway.stage(intent))
                assertTrue(h.master.executions.isEmpty())
                h.host.restoreClosedManifest(originalManifest)
                assertIs<JvmClientJournalOpenResult.Opened>(h.host.reopen())
                assertEquals(original, h.host.find(intent.operationId))
                assertEquals(listOf(intent.operationId), h.host.pending().map { it.operationId })
            } finally { originalManifest.fill(0) }
        }
    }

    @Test
    fun sref2006_008_currentOwnerLeaseAndRecoveryDenialHidePrivateOutputButRetainCommittedOriginal() = runTest {
        withSupplierReferenceV2Gateway { h ->
            h.master.createV2(f.supplier(), f.operation(202))
            h.master.actor = f.id(32)
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalReadResult.Unavailable>(h.gateway().read(f.query())).problem)
            assertTrue(h.master.reads.isEmpty())
            h.master.actor = f.actor
            h.master.currentResult = LocalSessionViewResult.Denied
            assertIs<LocalReadResult.Unavailable>(h.gateway().read(f.query()))
            h.master.currentResult = null
            h.master.readOverride = { request -> h.master.readActual(request).also { h.leaseCurrent = false } }
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalReadResult.Unavailable>(h.gateway().read(f.query())).problem)
            assertEquals(1, h.master.rowCount)
            assertTrue(h.master.executions.isEmpty())
        }
        withSupplierReferenceV2Gateway { h ->
            val intent = f.intent(f.operation(202), f.supplier())
            assertIs<LocalStageResult.Staged>(h.gateway().stage(intent))
            h.master.executeOverride = { request -> h.master.commitActual(request).also { h.leaseCurrent = false } }
            assertIs<LocalOperationResult.Unresolved>(h.gateway().submit(intent.operationId))
            val original = h.master.originals.getValue(intent.operationId)
            val retained = h.host.find(intent.operationId)
            assertEquals(StoredKernelResultV1.from(original), retained.recorded)
            h.reauthenticateOriginalControlledOwner()
            h.master.lookupOverride = { LookupReply.Deferred(it.operationId, DeferredReason.AUTHORIZATION_DENIED) }
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalOperationResult.Unresolved>(h.gateway().lookup(intent.operationId)).problem)
            assertIs<LocalAcknowledgementResult.Unresolved>(h.gateway().acknowledgeRecorded(intent.operationId))
            assertEquals(retained, h.host.find(intent.operationId))
            h.master.lookupOverride = null
            assertEquals(f.literal, assertIs<LocalOperationResult.Recorded>(h.gateway().lookup(intent.operationId)).result!!.values.single().value.english)
            assertEquals(original, h.master.originals.getValue(intent.operationId))
            assertEquals(1, h.master.rowCount)
        }
        // Controlled lease/error-port checks are not the required current native Master
        // authority/output-commit revocation barrier or separate grant provisioning proof.
    }

    private suspend fun createLegacy(h: SupplierReferenceV2GatewayFixture) = h.master.commitActual(MutationRequest(f.operation(201),
        LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureSupplier(null,
            LocalSupplierV1(f.id(101), f.initialNames, true)))))
}

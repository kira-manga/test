@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen CCM v1.0.1/binding v1.0.0 original-identity and retention contributions.
 * Uses the production gateway/entry codec over memory-only controlled source,
 * current-session and result ports. A Recorded callback is not a business commit;
 * recreation/codec roundtrip is not actual native restart, restore or migration.
 * Required production transaction/RPC cases remain separate from these supporting checks.
 */
class CapturedInvoiceMatchingRecoverySupportingTest {
    @Test
    fun ccm07And10RepeatedSubmissionKeepsOneControlledResultAndNoNewPreparation() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val originalRequest = assertNotNull(f.journal.entry).request()
        val first = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
        val originalReply = assertNotNull(f.endpoint.found)
        assertOriginalQualityResult(f, first, originalReply, assertNotNull(f.journal.entry))
        val readsBefore = f.endpoint.reads.size
        f.endpoint.rejectReads = true
        val repeated = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
        val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
        for (returned in listOf(repeated, recovered)) {
            assertOriginalQualityResult(f, returned, originalReply, assertNotNull(f.journal.entry))
            assertTrue(returned.replayed)
            assertEquals(first.result, returned.result)
        }
        assertEquals(1, f.endpoint.executions.size)
        assertEquals(originalRequest.payload, f.endpoint.executions.single().payload)
        assertEquals(originalRequest.payload, assertNotNull(f.journal.entry).request().payload)
        assertEquals(readsBefore, f.endpoint.reads.size)
        assertExactQualityMatchRequest(f, assertNotNull(f.journal.entry).request(), intent)
        // One controlled callback is not independent proof of one payable allocation.
    }

    @Test
    fun ccm10LostReplyAndEntryCodecRoundTripRecoverTheExactOriginalQualityResult() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId)).problem)
        val original = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, original.localPhase)
        assertNull(original.recorded)
        assertFalse(original.acknowledged)
        val restored = f.journal.codecRoundTrip()
        assertEquals(original, restored.entry)
        val readsBefore = f.endpoint.reads.size
        f.endpoint.rejectReads = true
        val returned = assertIs<LocalOperationResult.Recorded>(f.gateway(restored).submit(intent.operationId))
        assertOriginalQualityResult(f, returned, originalReply, assertNotNull(restored.entry))
        assertTrue(returned.replayed)
        assertEquals(original.identity(), f.endpoint.lookups.last())
        assertEquals(original.owner, assertNotNull(restored.entry).owner)
        assertEquals(original.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(original.semanticSha256, assertNotNull(restored.entry).semanticSha256)
        assertEquals(1, f.endpoint.executions.size)
        assertEquals(readsBefore, f.endpoint.reads.size)
        assertExactQualityMatchRequest(f, assertNotNull(restored.entry).request(), intent)
    }

    @Test
    fun ccm10ChangedSameOperationQuantityCannotReplaceTheUnresolvedOriginal() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val original = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val readsBefore = f.endpoint.reads.size
        val lookupsBefore = f.endpoint.lookups.size
        val stagesBefore = f.journal.stageCalls
        f.endpoint.rejectReads = true
        val changed = f.intent(quantity = "21")
        assertEquals(intent.operationId, changed.operationId)
        assertIs<LocalStageResult.Uncertain>(f.gateway.stage(changed))
        assertEquals(original, f.journal.entry)
        assertEquals(originalReply, f.endpoint.found)
        assertEquals(readsBefore, f.endpoint.reads.size)
        assertEquals(lookupsBefore, f.endpoint.lookups.size)
        assertEquals(stagesBefore, f.journal.stageCalls)
        assertEquals(1, f.endpoint.executions.size)
        val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
        assertOriginalQualityResult(f, recovered, originalReply, assertNotNull(f.journal.entry))
        assertExactQualityMatchRequest(f, assertNotNull(f.journal.entry).request(), intent)
        assertEquals(1, f.endpoint.executions.size)
        // Changed21 is a deliberate identity-conflict probe, not a changed quality oracle.
    }

    @Test
    fun ccm08And10DifferentActorCannotAdoptOrRecoverThisClientsOriginal() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val original = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val readsBefore = f.endpoint.reads.size
        val lookupsBefore = f.endpoint.lookups.size
        f.endpoint.rejectReads = true
        for (current in listOf(LocalSessionViewResult.Denied,
            LocalSessionViewResult.Current(f.view(actorId = f.otherActor)))) {
            f.endpoint.currentReply = current
            assertIs<LocalStageResult.Uncertain>(f.gateway.stage(intent))
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId)).problem)
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(intent.operationId)).problem)
            assertEquals(original, f.journal.entry)
            assertEquals(originalReply, f.endpoint.found)
            assertEquals(readsBefore, f.endpoint.reads.size)
            assertEquals(lookupsBefore, f.endpoint.lookups.size)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(1, f.endpoint.executions.size)
        }
        f.endpoint.currentReply = null
        val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
        assertOriginalQualityResult(f, recovered, originalReply, assertNotNull(f.journal.entry))
        assertEquals(f.actor, assertNotNull(f.journal.entry).owner.actorId)
        // Explicitly scoped supervisor recovery is a different route, not actor adoption.
    }

    @Test
    fun ccm10And11PresentationWaitsForResultRetention() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val release = CompletableDeferred<Unit>()
        f.journal.retentionRelease = release
        val submit = async { f.gateway.submit(intent.operationId) }
        runCurrent()
        assertTrue(f.journal.retentionEntered.isCompleted)
        assertFalse(submit.isCompleted)
        val outstanding = assertNotNull(f.journal.entry)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
        assertNull(outstanding.recorded)
        assertFalse(outstanding.acknowledged)
        assertEquals(0, f.journal.acknowledgeCalls)
        assertExactQualityMatchRequest(f, outstanding.request(), intent)
        release.complete(Unit)
        val returned = assertIs<LocalOperationResult.Recorded>(submit.await())
        assertOriginalQualityResult(f, returned, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
        assertEquals(1, f.endpoint.executions.size)
        // The wait is a controlled journal barrier, not a native I/O crash window.
    }

    @Test
    fun ccm10ResultRetentionFailureLeavesTheOriginalUnpresentedAndUnacknowledged() = runTest(timeout = 5.seconds) {
        for (failure in listOf(JournalWrite.NO_CAPACITY, JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE)) {
            val f = CapturedInvoiceMatchingGatewayFixture()
            val intent = f.intent()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val original = assertNotNull(f.journal.entry)
            f.journal.retentionOutcome = failure
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId)).problem)
            val originalReply = assertNotNull(f.endpoint.found)
            val outstanding = assertNotNull(f.journal.entry)
            assertEquals(original.owner, outstanding.owner)
            assertEquals(original.operationId, outstanding.operationId)
            assertEquals(original.request().payload, outstanding.request().payload)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertEquals(1, f.journal.rememberCalls)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, f.journal.acknowledgeCalls)
            val restored = f.journal.codecRoundTrip()
            val readsBefore = f.endpoint.reads.size
            f.endpoint.rejectReads = true
            val recreated = f.gateway(restored)
            val recovered = assertIs<LocalOperationResult.Recorded>(recreated.lookup(intent.operationId))
            assertOriginalQualityResult(f, recovered, originalReply, assertNotNull(restored.entry))
            assertExactQualityMatchRequest(f, assertNotNull(restored.entry).request(), intent)
            assertEquals(original.identity(), f.endpoint.lookups.last())
            assertEquals(readsBefore, f.endpoint.reads.size)
            assertEquals(1, f.endpoint.executions.size)
            assertIs<LocalAcknowledgementResult.Acknowledged>(recreated.acknowledgeRecorded(intent.operationId))
            assertEquals(1, restored.acknowledgeCalls)
            assertTrue(assertNotNull(restored.entry).acknowledged)
            assertEquals(StoredKernelResultV1.from(originalReply), assertNotNull(restored.entry).recorded)
            assertEquals(original.request().payload, assertNotNull(restored.entry).request().payload)
        }
    }

    @Test
    fun ccm10MismatchedOrCorruptMatchResultsCannotBeRetainedPresentedOrAcknowledged() = runTest(timeout = 5.seconds) {
        for (fault in listOf("MATCH_ID", "INVOICE_ID", "INVOICE_REVISION", "EVENT_REVISION",
            "PROGRESS_REVISION", "PAYABLE_REVISION", "QUANTITY", "TRUNCATED_BYTES")) {
            val f = CapturedInvoiceMatchingGatewayFixture()
            val intent = f.intent()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val original = assertNotNull(f.journal.entry)
            val valid = f.result(original.request())
            when (fault) {
                "MATCH_ID" -> f.endpoint.resultOverride = valid.copy(id = f.id(100))
                "INVOICE_ID" -> f.endpoint.resultOverride = valid.copy(invoiceId = f.id(101))
                "INVOICE_REVISION" -> f.endpoint.resultOverride = valid.copy(invoiceRevision = Revision(2))
                "EVENT_REVISION" -> f.endpoint.resultOverride = valid.copy(revision = Revision(2))
                "PROGRESS_REVISION" -> f.endpoint.resultOverride = valid.copy(progressRevision = Revision(2))
                "PAYABLE_REVISION" -> f.endpoint.resultOverride = valid.copy(payableRevision = Revision(2))
                "QUANTITY" -> f.endpoint.resultOverride = valid.copy(quantity = Quantity(f.each, 21, 0))
                else -> f.endpoint.rawResultOverride = StoredBytes("{\"code\":".encodeToByteArray())
            }
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId)).problem)
            val outstanding = assertNotNull(f.journal.entry)
            assertEquals(original.owner, outstanding.owner)
            assertEquals(original.request().payload, outstanding.request().payload)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertEquals(0, f.journal.rememberCalls)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, f.journal.acknowledgeCalls)
            f.endpoint.rejectReads = true
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(intent.operationId)).problem)
            assertEquals(outstanding, f.journal.entry)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(1, f.endpoint.executions.size)
            assertExactQualityMatchRequest(f, outstanding.request(), intent)
        }
        // Invalid bodies are never repaired into an invented original historical result.
    }

    @Test
    fun ccm08And10InvoiceCaptureOnlyProfileCannotReactivateRetainedMatching() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val original = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val restored = f.journal.codecRoundTrip()
        val captureOnly = f.gateway(restored,
            LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE))
        val readsBefore = f.endpoint.reads.size
        val lookupsBefore = f.endpoint.lookups.size
        f.endpoint.rejectReads = true
        assertIs<LocalStageResult.Uncertain>(captureOnly.stage(intent))
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
            assertIs<LocalOperationResult.Unresolved>(captureOnly.submit(intent.operationId)).problem)
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
            assertIs<LocalOperationResult.Unresolved>(captureOnly.lookup(intent.operationId)).problem)
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
            assertIs<LocalPendingResult.Unavailable>(captureOnly.pending()).problem)
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
            assertIs<LocalAcknowledgementResult.Unresolved>(captureOnly.acknowledgeRecorded(intent.operationId)).problem)
        assertEquals(original, restored.entry)
        assertEquals(originalReply, f.endpoint.found)
        assertEquals(readsBefore, f.endpoint.reads.size)
        assertEquals(lookupsBefore, f.endpoint.lookups.size)
        assertEquals(0, restored.stageCalls)
        assertEquals(0, restored.rememberCalls)
        assertEquals(0, restored.acknowledgeCalls)
        assertEquals(1, f.endpoint.executions.size)
        // Unsupported retained history is not presented as an empty successful pending page.
    }
}

internal fun assertOriginalQualityResult(f: CapturedInvoiceMatchingGatewayFixture, returned: LocalOperationResult.Recorded,
    originalReply: MutationReply.Recorded, retained: StoredClientIntentV1) {
    assertTrue(originalReply.committed)
    assertEquals("committed", originalReply.code)
    assertEquals(f.operation, originalReply.operationId)
    assertEquals(f.actor, originalReply.originalActorId)
    assertEquals(originalReply.operationId, returned.operationId)
    assertEquals(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, returned.originalAction)
    assertEquals(LocalRecordedDecision.APPLIED, returned.decision)
    assertEquals(f.actor, retained.owner.actorId)
    assertEquals(StoredKernelResultV1.from(originalReply), retained.recorded)
    assertEquals(LocalPendingPhase.RECORDED_UNACKNOWLEDGED, retained.localPhase)
    val value = LocalPurchasingCodec.capturedMatchResult(originalReply.result)
    assertEquals(LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS, value.code)
    assertEquals(f.match, value.id)
    assertEquals(f.invoice, value.invoiceId)
    assertEquals(Revision(1), value.invoiceRevision)
    assertEquals(Revision(1), value.revision)
    assertEquals(Revision(1), value.progressRevision)
    assertEquals(Revision(1), value.payableRevision)
    assertEquals(f.rawClaim, value.originalInvoiceClaim)
    assertEquals(f.selectedQuantity, value.quantity)
    assertEquals(f.recognized, value.net)
    assertEquals(f.zero, value.tax)
    val record = assertNotNull(returned.result)
    assertEquals(LocalRecordKind.PURCHASE_INVOICE, record.reference.kind)
    assertEquals(f.invoice, record.reference.id)
    assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, record.invoiceEntryKind)
    assertEquals(LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS, assertNotNull(record.status).english)
    assertEquals(f.match.value, matchingValue(record, fieldLabel(LocalField.MATCH_ID).english))
    assertEquals("22 · ${f.each.value}", matchingValue(record, fieldLabel(LocalField.QUANTITY).english))
    assertEquals("220.00 EGP", matchingValue(record, "This operation's recognized amount — not paid"))
    assertEquals("240.00 EGP", matchingValue(record, "Original supplier claim"))
    assertTrue(returned.message.english.contains("No stock or money moved"))
}

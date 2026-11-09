@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
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
 * Frozen original-identity/retention contributions, profile S only.
 * A controlled Recorded reply is not evidence of a business commit. Entry-codec
 * round trips are not native/database reopen, restart, restore or migration tests.
 */
class SupplierInvoiceCaptureRecoverySupportingTest {
    @Test
    fun sidc05LostReplyAndEntryCodecRoundTripRecoverTheExactChangedConversionOriginal() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("CHANGED_CONVERSION")
        val intent = f.intent(f.currentForm(fromOrder = true), claim)
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val originalEntry = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val restored = f.journal.codecRoundTrip()
        val readsBeforeRetry = f.endpoint.reads.size
        f.endpoint.rejectReads = true

        val returned = assertIs<LocalOperationResult.Recorded>(f.newGateway(restored).submit(intent.operationId))
        f.assertOriginalResult(returned, originalReply, assertNotNull(restored.entry))
        f.assertRequest(assertNotNull(restored.entry).request(), intent, claim)
        assertEquals(originalEntry.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(originalEntry.formVersion, assertNotNull(restored.entry).formVersion)
        assertEquals(originalEntry.owner, assertNotNull(restored.entry).owner)
        assertEquals(originalEntry.identity(), f.endpoint.lookups.last())
        assertEquals(readsBeforeRetry, f.endpoint.reads.size)
        assertEquals(1, f.endpoint.executions.size)
        f.assertOriginalOrderTerms()
        // The later catalog/PO fixture in SIDC-07 is not activated or persisted by this probe.
    }

    @Test
    fun sird03LostReplyRecoversOriginalContextAndActualGoodsWithoutNewReads() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.referencedClaim("DIFFERENT_ITEM_UNIT")
        val intent = f.intent(f.currentForm(fromOrder = true), claim,
            mode = "DOCUMENTARY_ORDER_LINE:${f.originalAllocationId.value}",
            supplierOrderReference = "PO-000042", supplierAllocationReferences = listOf(f.originalAllocationId.value))
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val originalEntry = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val restored = f.journal.codecRoundTrip()
        val readsBeforeRetry = f.endpoint.reads.size
        f.endpoint.rejectReads = true

        val returned = assertIs<LocalOperationResult.Recorded>(f.newGateway(restored).submit(intent.operationId))
        f.assertOriginalResult(returned, originalReply, assertNotNull(restored.entry))
        f.assertRequest(assertNotNull(restored.entry).request(), intent, claim, internalAllocationId = f.originalAllocationId)
        assertEquals(originalEntry.identity(), f.endpoint.lookups.last())
        assertEquals(originalEntry.owner, assertNotNull(restored.entry).owner)
        assertEquals(originalEntry.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(readsBeforeRetry, f.endpoint.reads.size)
        assertEquals(1, f.endpoint.executions.size)
        f.assertOriginalOrderTerms()
    }

    @Test
    fun sidc05AndSird03ChangedContextOrActualTermsCannotReplaceAnUnresolvedClientOriginal() = runTest(timeout = 5.seconds) {
        for (referenced in listOf(false, true)) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = if (referenced) FrozenSupplierInvoiceCaptureInputs.referencedClaim("DIFFERENT_ITEM_UNIT")
                else FrozenSupplierInvoiceCaptureInputs.captureClaim("CHANGED_CONVERSION")
            val mode = if (referenced) "DOCUMENTARY_ORDER_LINE:${f.originalAllocationId.value}" else "DOCUMENTARY_GOODS"
            val intent = f.intent(f.currentForm(fromOrder = true), claim, mode = mode)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            f.endpoint.loseNextReply = true
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
            val originalEntry = assertNotNull(f.journal.entry)
            val originalReply = assertNotNull(f.endpoint.found)
            val readsBeforeRetry = f.endpoint.reads.size
            val lookupsBeforeRetry = f.endpoint.lookups.size
            val stageCallsBeforeRetry = f.journal.stageCalls
            f.endpoint.rejectReads = true
            val changes = mutableListOf(
                replaceCaptureLineInput(intent, LocalField.QUANTITY, LocalScalarValue.Decimal(if (referenced) "5" else "4")),
                replaceCaptureLineInput(intent, LocalField.PURCHASE_CONVERSION_RATE,
                    LocalScalarValue.Text(if (referenced) "6/1" else "12/1")),
                replaceCaptureLineInput(intent, LocalField.SKU_ID, LocalScalarValue.Identifier(DomainId.random())),
                replaceCaptureHeader(intent, LocalField.EXTERNAL_REFERENCE, LocalScalarValue.Text(claim.reference + "-changed")),
            )
            if (referenced) changes += replaceCaptureLineInput(intent, LocalField.MODE,
                LocalScalarValue.Choice("DOCUMENTARY_ORDER_LINE:${DomainId.random().value}"))
            for (changed in changes) {
                assertEquals(intent.operationId, changed.operationId)
                assertIs<LocalStageResult.Uncertain>(f.gateway.stage(changed))
                assertEquals(originalEntry, f.journal.entry)
                assertEquals(originalReply, f.endpoint.found)
                assertEquals(readsBeforeRetry, f.endpoint.reads.size)
                assertEquals(lookupsBeforeRetry, f.endpoint.lookups.size)
                assertEquals(stageCallsBeforeRetry, f.journal.stageCalls)
                assertEquals(1, f.endpoint.executions.size)
            }
            val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
            f.assertOriginalResult(recovered, originalReply, assertNotNull(f.journal.entry))
            f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim,
                internalAllocationId = f.originalAllocationId.takeIf { referenced })
            assertEquals(1, f.endpoint.executions.size)
            // Uncertain proves client nonreplacement only. The alternate DTO ID is not a resolved
            // valid REF-B, and no authoritative semantic-conflict/duplicate posting is claimed.
        }
    }

    @Test
    fun sidc06MixedClaimPresentationWaitsForClientResultRetention() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
        val intent = f.intent(f.currentForm(), claim)
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val release = CompletableDeferred<Unit>()
        f.journal.retentionRelease = release
        val submit = async { f.gateway.submit(intent.operationId) }
        runCurrent()
        assertTrue(f.journal.retentionEntered.isCompleted)
        assertFalse(submit.isCompleted)
        val awaitingRetention = assertNotNull(f.journal.entry)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, awaitingRetention.localPhase)
        assertNull(awaitingRetention.recorded)
        assertFalse(awaitingRetention.acknowledged)
        f.assertRequest(awaitingRetention.request(), intent, claim)
        release.complete(Unit)

        val returned = assertIs<LocalOperationResult.Recorded>(submit.await())
        f.assertOriginalResult(returned, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
        assertEquals(1, f.endpoint.executions.size)
    }

    @Test
    fun sidc06FailedSubmitResultRetentionPreservesTheMixedOriginalAndBlocksAcknowledgement() = runTest(timeout = 5.seconds) {
        for (failure in listOf(JournalWrite.NO_CAPACITY, JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE)) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
            val intent = f.intent(f.currentForm(), claim)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val originalEntry = assertNotNull(f.journal.entry)
            f.journal.retentionOutcome = failure
            val unresolved = assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, unresolved.problem)
            val outstanding = assertNotNull(f.journal.entry)
            assertEquals(originalEntry.request().payload, outstanding.request().payload)
            assertEquals(originalEntry.owner, outstanding.owner)
            assertEquals(originalEntry.operationId, outstanding.operationId)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            f.assertRequest(outstanding.request(), intent, claim)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, f.journal.acknowledgeCalls)
            f.journal.retentionOutcome = JournalWrite.DURABLE

            val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
            f.assertOriginalResult(recovered, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
            assertEquals(1, f.endpoint.executions.size)
            assertIs<LocalAcknowledgementResult.Acknowledged>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertTrue(assertNotNull(f.journal.entry).acknowledged)
        }
    }

    @Test
    fun sidc06LostReplyLookupRequiresRetentionWithoutRedispatchingTheMixedClaim() = runTest(timeout = 5.seconds) {
        for (failure in listOf(JournalWrite.NO_CAPACITY, JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE)) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
            val intent = f.intent(f.currentForm(), claim)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            f.endpoint.loseNextReply = true
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
            val originalEntry = assertNotNull(f.journal.entry)
            f.journal.retentionOutcome = failure

            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(intent.operationId)).problem)
            assertEquals(originalEntry, f.journal.entry)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, f.journal.acknowledgeCalls)
            assertEquals(1, f.endpoint.executions.size)
            f.journal.retentionOutcome = JournalWrite.DURABLE
            val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
            f.assertOriginalResult(recovered, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
            f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim)
            assertEquals(1, f.endpoint.executions.size)
        }
    }

    @Test
    fun sidc04AndSird03RefusedOrDifferentCurrentActorCannotReadOrAdoptTheOriginal() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.referencedClaim("DIFFERENT_ITEM_UNIT")
        val intent = f.intent(f.currentForm(fromOrder = true), claim,
            mode = "DOCUMENTARY_ORDER_LINE:${f.originalAllocationId.value}")
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val originalEntry = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val readsBeforeRefusal = f.endpoint.reads.size
        val lookupsBeforeRefusal = f.endpoint.lookups.size
        for (refusal in listOf(LocalSessionViewResult.Denied,
            LocalSessionViewResult.Current(f.view(actor = DomainId.random())))) {
            f.endpoint.currentReply = refusal
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(intent.operationId)).problem)
            assertEquals(originalEntry, f.journal.entry)
            assertEquals(originalReply, f.endpoint.found)
            assertEquals(readsBeforeRefusal, f.endpoint.reads.size)
            assertEquals(lookupsBeforeRefusal, f.endpoint.lookups.size)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(1, f.endpoint.executions.size)
        }
        f.endpoint.currentReply = null
        val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
        f.assertOriginalResult(recovered, originalReply, assertNotNull(f.journal.entry))
        f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim, internalAllocationId = f.originalAllocationId)
        assertEquals(originalEntry.owner, assertNotNull(f.journal.entry).owner)
        // No Master activation/revocation race or separately authorized recovery reader is bound.
    }

    @Test
    fun originalResultIdentityAndCaptureVersionCannotBeReplacedBeforeRetentionOrAck() = runTest(timeout = 5.seconds) {
        for (invalid in listOf("WRONG_ID", "WRONG_REVISION", "LEGACY_PROGRESS")) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("CHANGED_CONVERSION")
            val intent = f.intent(f.currentForm(fromOrder = true), claim)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val original = assertNotNull(f.journal.entry)
            val id = headerId(intent, LocalField.INVOICE_ID)
            when (invalid) {
                "WRONG_ID" -> f.endpoint.resultOverride = LocalInvoiceCaptureResultV2("purchasing.invoice_captured", DomainId.random(), Revision(1))
                "WRONG_REVISION" -> f.endpoint.resultOverride = LocalInvoiceCaptureResultV2("purchasing.invoice_captured", id, Revision(37))
                else -> f.endpoint.rawResultOverride = FrozenSupplierInvoiceCaptureInputs.body(
                    LocalPurchasingResultV1("purchasing.invoice_captured", id, Revision(1), Revision(41)),
                    LocalPurchasingResultV1.serializer())
            }
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
            val outstanding = assertNotNull(f.journal.entry)
            assertEquals(original.request().payload, outstanding.request().payload)
            assertEquals(original.owner, outstanding.owner)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertEquals(0, f.journal.rememberCalls)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, f.journal.acknowledgeCalls)
            assertEquals(1, f.endpoint.executions.size)
            f.assertRequest(outstanding.request(), intent, claim)
        }
    }
}

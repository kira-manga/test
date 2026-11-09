@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.documentaryclientv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.documentaryclientv2.DocumentaryClientV2UiFixture.Companion.withDocumentaryClientV2
import com.ukkera.pos.app.navigation.BackCoordinator
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen shared-host slices, not native gesture, authority, journal, RPC or Z verification. */
class DocumentaryClientV2FrozenControllerSlicesTest {
    private val inputs = FrozenDocumentaryClientV2Inputs

    @Test
    fun di2_014_021_n04_preSubmitChildBackCanBeCancelledOrConfirmedWithoutSubmission() = runTest {
        withDocumentaryClientV2 { host ->
            val parent = LocalPurchasingDocumentSourcesResult.Available(listOf(inputs.sourceSummary("PRIMARY-A")))
            host.port.sourcesInput = parent
            host.controller.refreshPurchasingDocumentSources(host.token())
            host.drain()
            host.fillDraft("SMALL-CHILD")
            val chosen = host.import("SMALL-CHILD")
            assertEquals(LeaveGuard.DIRTY, host.runtime.navigator.state.value.guard)

            host.runtime.navigate(NavigationAction.Back, host.token())
            val first = assertNotNull(host.runtime.navigator.state.value.confirmation)
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, host.runtime.navigator.state.value.root)
            assertTrue(host.controller.state.value.purchasingDocuments.hasDraft)
            host.runtime.navigator.cancelLeave(first.id)
            assertContentEquals(inputs.fileBytes("PNG-A"), chosen.copyBytes())

            host.runtime.navigate(NavigationAction.Back, host.token())
            host.runtime.confirmLeave(assertNotNull(host.runtime.navigator.state.value.confirmation).id)
            host.drain()
            assertEquals(SecurityRoot.BOOTSTRAP, host.runtime.navigator.state.value.root)
            assertFailsWith<IllegalStateException> { chosen.copyBytes() }
            assertEquals(parent, host.port.sourcesInput)
            assertTrue(host.port.saves.isEmpty())
            assertTrue(host.port.transfers.isEmpty())
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_021_n02_cancelledDeniedOrRevokedSourceDoesNotBecomeAUriOnlySave() = runTest {
        val selections = listOf(LocalPurchasingDocumentFileSelection.Cancelled,
            LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.PERMISSION_DENIED),
            LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED))
        for (selection in selections) withDocumentaryClientV2 { host ->
            host.fillDraft("PRIMARY-A")
            host.files.importInput = selection
            host.controller.importPurchasingDocument(host.token())
            host.drain()
            val documents = host.controller.state.value.purchasingDocuments
            assertNull(documents.selectedFile)
            when (selection) {
                LocalPurchasingDocumentFileSelection.Cancelled -> assertTrue(documents.importCancelled)
                is LocalPurchasingDocumentFileSelection.Unavailable -> assertEquals(selection.problem, documents.fileProblem)
                is LocalPurchasingDocumentFileSelection.Selected -> error("No selected input in this frozen cancellation slice")
            }
            host.controller.savePurchasingDocument(host.token())
            host.drain()
            assertNotNull(host.controller.state.value.purchasingDocuments.inputIssue)
            assertTrue(host.port.saves.isEmpty())
            assertTrue(host.port.transfers.isEmpty())
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_021_024_privateSaveUncertaintyKeepsTheExactInputForAnExplicitRetry() = runTest {
        withDocumentaryClientV2 { host ->
            val documentId = inputs.documentId("PRIMARY-A")
            host.fillDraft("PRIMARY-A")
            val chosen = host.import("PRIMARY-A")
            host.port.saveInput = LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, documentId)
            host.controller.savePurchasingDocument(host.token())
            host.drain()
            val first = host.port.saves.single()
            assertEquals(inputs.captureInput("PRIMARY-A"), first.capture)
            assertContentEquals(inputs.fileBytes("PDF-MULTI"), first.bytes)
            val frozenDraft = assertNotNull(host.controller.state.value.purchasingDocuments.draft)
            assertTrue(frozenDraft.saveAttempted)
            assertEquals(host.port.saveInput, host.controller.state.value.purchasingDocuments.saveResult)
            assertContentEquals(first.bytes, chosen.copyBytes())

            val importsBefore = host.files.imports
            host.controller.editPurchasingDocument(LocalPurchasingDocumentField.SUPPLIER_REFERENCE, "0007-a", host.token())
            host.controller.choosePurchasingDocumentPurpose(inputs.purposes.first(), host.token())
            host.controller.importPurchasingDocument(host.token())
            host.drain()
            assertEquals(frozenDraft, host.controller.state.value.purchasingDocuments.draft)
            assertEquals(importsBefore, host.files.imports)

            host.port.saveInput = LocalPurchasingDocumentSourceResult.Saved(documentId)
            host.port.sourcesInput = LocalPurchasingDocumentSourcesResult.Available(listOf(inputs.sourceSummary("PRIMARY-A")))
            host.controller.savePurchasingDocument(host.token())
            host.drain()
            assertEquals(2, host.port.saves.size)
            assertEquals(first.capture, host.port.saves[1].capture)
            assertContentEquals(first.bytes, host.port.saves[1].bytes)
            val documents = host.controller.state.value.purchasingDocuments
            assertEquals(LocalPurchasingDocumentSourceResult.Saved(documentId), documents.saveResult)
            assertNull(documents.draft)
            assertNull(documents.selectedFile)
            assertFailsWith<IllegalStateException> { chosen.copyBytes() }
            // Private-source saved is deliberately not the branch captured state.
            assertTrue(documents.transfers.isEmpty())
            assertTrue(host.port.transfers.isEmpty())
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_015_correctionHostSendsTheExactNewIdentitySourceAndReasonWithoutReplacingA() = runTest {
        withDocumentaryClientV2 { host ->
            val documentId = inputs.documentId("CORRECTION-B")
            host.fillDraft("CORRECTION-B")
            host.import("CORRECTION-B")
            host.port.saveInput = LocalPurchasingDocumentSourceResult.Saved(documentId)
            host.port.sourcesInput = LocalPurchasingDocumentSourcesResult.Available(listOf(
                inputs.sourceSummary("PRIMARY-A"), inputs.sourceSummary("CORRECTION-B")))
            host.controller.savePurchasingDocument(host.token())
            host.drain()
            val saved = host.port.saves.single()
            assertEquals(inputs.captureInput("CORRECTION-B"), saved.capture)
            assertContentEquals(inputs.fileBytes("PDF-B"), saved.bytes)
            assertEquals(inputs.documentId("PRIMARY-A"), saved.capture.correctionOf)
            assertEquals("Supplier sent a revised documentary claim.", saved.capture.correctionReason)
            assertTrue(host.port.transfers.isEmpty())
            assertTrue(host.controller.state.value.purchasingDocuments.transfers.isEmpty())
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_027_invalidEnteredControlTextIsNotNormalizedIntoASave() = runTest {
        for (document in listOf("PRIMARY-A", "CORRECTION-B")) withDocumentaryClientV2 { host ->
            host.fillDraft(document)
            host.import(document)
            val field = if (document == "PRIMARY-A") LocalPurchasingDocumentField.SUPPLIER_REFERENCE
                else LocalPurchasingDocumentField.CORRECTION_REASON
            val value = inputs.controlText(if (document == "PRIMARY-A") "supplierReferenceOriginal" else "correctionReason")
            host.controller.editPurchasingDocument(field, value, host.token())
            host.controller.savePurchasingDocument(host.token())
            host.drain()
            val documents = host.controller.state.value.purchasingDocuments
            assertNotNull(documents.inputIssue)
            val retained = assertNotNull(documents.draft)
            assertFalse(retained.saveAttempted)
            assertEquals(value, if (document == "PRIMARY-A") retained.supplierReferenceOriginal else retained.correctionReason)
            assertTrue(host.port.saves.isEmpty())
            assertTrue(host.port.transfers.isEmpty())
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_013_reviewedLowerParentCasIsSavedSeparatelyAndNeverEditsTheOriginalRefusal() = runTest {
        withDocumentaryClientV2 { host ->
            val documentId = inputs.documentId("SMALL-CHILD")
            val refusedOperation = inputs.completeOperationId("SMALL-CHILD")
            val wrongParentCas = Revision(inputs.parentRevision.value + 1) // Frozen WRONG-PARENT-CAS is R+1 while actual R is unchanged.
            host.fillDraft("SMALL-CHILD")
            host.controller.editPurchasingDocument(LocalPurchasingDocumentField.PARENT_ATTACHMENTS_REVISION,
                wrongParentCas.value.toString(), host.token())
            host.import("SMALL-CHILD")
            host.port.saveInput = LocalPurchasingDocumentSourceResult.Saved(documentId)
            host.port.sourcesInput = LocalPurchasingDocumentSourcesResult.Available(listOf(inputs.sourceSummary("SMALL-CHILD")))
            host.controller.savePurchasingDocument(host.token())
            host.drain()
            val original = host.port.saves.single()
            assertEquals(inputs.captureInput("SMALL-CHILD").copy(expectedParentAttachmentsRevision = wrongParentCas), original.capture)

            val refusal = LocalPurchasingDocumentTransferResult.Rejected(documentId, refusedOperation, "purchasing.document.stale_revision")
            host.port.transferInput = refusal
            host.controller.transferPurchasingDocument(documentId, host.token())
            host.drain()
            host.controller.reviewPurchasingDocumentCompletion(documentId, host.token())
            val review = assertNotNull(host.controller.state.value.purchasingDocuments.completionRetry)
            assertEquals(refusedOperation, review.refusedCompleteOperationId)
            // Missing revision cannot infer the current parent or dispatch a semantic replacement.
            host.controller.savePurchasingDocumentCompletionRetry(host.token())
            assertNotNull(host.controller.state.value.purchasingDocuments.inputIssue)
            assertTrue(host.port.retries.isEmpty())

            host.controller.editPurchasingDocumentCompletionRevision(inputs.parentRevision.value.toString(), host.token())
            host.port.retryInput = LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.RETRY_LATER, documentId)
            host.controller.savePurchasingDocumentCompletionRetry(host.token())
            host.drain()
            val request = host.port.retries.single()
            assertEquals(DocumentaryClientV2RetryObservation(documentId, refusedOperation, inputs.parentRevision), request)
            assertTrue(request.revision < wrongParentCas)
            val frozenReview = assertNotNull(host.controller.state.value.purchasingDocuments.completionRetry)
            assertTrue(frozenReview.saveAttempted)
            host.controller.editPurchasingDocumentCompletionRevision(wrongParentCas.value.toString(), host.token())
            assertEquals(frozenReview, host.controller.state.value.purchasingDocuments.completionRetry)

            host.port.retryInput = LocalPurchasingDocumentSourceResult.Saved(documentId)
            host.controller.savePurchasingDocumentCompletionRetry(host.token())
            host.drain()
            assertEquals(listOf(request, request), host.port.retries)
            val savedReview = host.controller.state.value.purchasingDocuments
            assertNull(savedReview.completionRetry)
            assertEquals(LocalPurchasingDocumentSourceResult.Saved(documentId), savedReview.completionSaveResult)
            assertEquals(refusal, savedReview.transfers[documentId])
            assertEquals(refusedOperation, savedReview.completionOriginalRefusals[documentId])
            assertEquals(listOf(documentId), host.port.transfers)
            assertEquals(wrongParentCas, host.port.saves.single().capture.expectedParentAttachmentsRevision)
            assertContentEquals(inputs.fileBytes("PNG-A"), original.bytes)

            // A separate explicit transfer, not save/review, presents the new completion identity.
            val captured = LocalPurchasingDocumentTransferResult.Captured(documentId, inputs.refreshedCompleteOperationId("SMALL-CHILD"))
            host.port.transferInput = captured
            host.controller.transferPurchasingDocument(documentId, host.token())
            host.drain()
            assertEquals(listOf(documentId, documentId), host.port.transfers)
            assertEquals(captured, host.controller.state.value.purchasingDocuments.transfers[documentId])
            assertEquals(refusedOperation, host.controller.state.value.purchasingDocuments.completionOriginalRefusals[documentId])
            assertEquals(1, host.port.saves.size)
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_013_021_unknownTransferDoesNotAuthorizeACompletionReplacement() = runTest {
        withDocumentaryClientV2 { host ->
            val documentId = inputs.documentId("SMALL-CHILD")
            host.port.sourcesInput = LocalPurchasingDocumentSourcesResult.Available(listOf(inputs.sourceSummary("SMALL-CHILD")))
            host.controller.refreshPurchasingDocumentSources(host.token())
            host.drain()
            val unresolved = LocalPurchasingDocumentTransferResult.Unresolved(documentId,
                inputs.completeOperationId("SMALL-CHILD"), LocalServiceProblem.UNAVAILABLE)
            host.port.transferInput = unresolved
            host.controller.transferPurchasingDocument(documentId, host.token())
            host.drain()
            assertEquals(unresolved, host.controller.state.value.purchasingDocuments.transfers[documentId])
            host.controller.reviewPurchasingDocumentCompletion(documentId, host.token())
            assertNull(host.controller.state.value.purchasingDocuments.completionRetry)
            assertTrue(host.port.retries.isEmpty())
            assertTrue(host.port.saves.isEmpty())
            assertEquals(listOf(documentId), host.port.transfers)
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_021_n04_nonActiveSharedLifecycleClearsThePrivateDraftAndChosenOriginal() = runTest {
        val states = LocalSessionLifecycle.entries.filter { it != LocalSessionLifecycle.ACTIVE }
        for (state in states) withDocumentaryClientV2 { host ->
            host.fillDraft("PRIMARY-A")
            val chosen = host.import("PRIMARY-A")
            host.session.lifecycle.value = state
            host.drain()
            assertEquals(LocalPurchasingDocumentPresentation(), host.controller.state.value.purchasingDocuments)
            assertFalse(host.controller.purchasingDocumentDisplayCurrent(host.token()))
            assertFailsWith<IllegalStateException> { chosen.copyBytes() }
            assertTrue(host.port.saves.isEmpty())
            assertTrue(host.port.transfers.isEmpty())
            assertTrue(host.port.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_021_n04_lateImportCallbackCannotExposeOrAdoptAOriginalInXScope() = runTest {
        withDocumentaryClientV2 { host ->
            host.fillDraft("PRIMARY-A")
            val old = host.controller
            val delayed = host.files.holdNextImport()
            host.controller.importPurchasingDocument(host.token())
            host.drain()
            assertTrue(delayed.requested.isCompleted)
            val next = host.enterOtherEmployee()
            val before = host.controller.state.value.purchasingDocuments
            val chosen = inputs.selection("PRIMARY-A")
            delayed.release(chosen)
            host.drain()
            assertTrue(delayed.returned.isCompleted)
            assertEquals(before, host.controller.state.value.purchasingDocuments)
            assertEquals(LocalPurchasingDocumentPresentation(), old.state.value.purchasingDocuments)
            assertFailsWith<IllegalStateException> { chosen.copyBytes() }
            host.controller.transferPurchasingDocument(inputs.documentId("PRIMARY-A"), host.token())
            host.drain()
            assertTrue(next.transfers.isEmpty())
            assertTrue(next.saves.isEmpty())
            assertTrue(host.port.saves.isEmpty())
            assertTrue(next.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_021_024_n04_unknownSaveBlocksBackAndItsLateReplyCannotEnterXScope() = runTest {
        withDocumentaryClientV2 { host ->
            val documentId = inputs.documentId("PRIMARY-A")
            host.fillDraft("PRIMARY-A")
            val chosen = host.import("PRIMARY-A")
            val old = host.controller
            val delayed = host.port.holdNextSave(documentId)
            host.controller.savePurchasingDocument(host.token())
            host.drain()
            assertTrue(delayed.requested.isCompleted)
            assertEquals(NavigationResult.BLOCKED, BackCoordinator.disposition(host.runtime.navigator.state.value))
            host.runtime.navigate(NavigationAction.Back, host.token())
            assertNull(host.runtime.navigator.state.value.confirmation)
            assertTrue(host.controller.state.value.working)

            val next = host.enterOtherEmployee()
            assertFailsWith<IllegalStateException> { chosen.copyBytes() }
            val before = host.controller.state.value.purchasingDocuments
            delayed.release(LocalPurchasingDocumentSourceResult.Saved(documentId))
            host.drain()
            assertTrue(delayed.returned.isCompleted)
            assertEquals(before, host.controller.state.value.purchasingDocuments)
            assertEquals(LocalPurchasingDocumentPresentation(), old.state.value.purchasingDocuments)
            assertEquals(inputs.captureInput("PRIMARY-A"), host.port.saves.single().capture)
            assertContentEquals(inputs.fileBytes("PDF-MULTI"), host.port.saves.single().bytes)
            assertTrue(next.saves.isEmpty())
            assertTrue(next.transfers.isEmpty())
            assertTrue(next.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_021_024_n04_lateTransferOutcomeCannotRelabelAOperationOrPopulateXPresentation() = runTest {
        withDocumentaryClientV2 { host ->
            val documentId = inputs.documentId("PRIMARY-A")
            host.port.sourcesInput = LocalPurchasingDocumentSourcesResult.Available(listOf(inputs.sourceSummary("PRIMARY-A")))
            host.controller.refreshPurchasingDocumentSources(host.token())
            host.drain()
            val delayed = host.port.holdNextTransfer(documentId)
            val old = host.controller
            host.controller.transferPurchasingDocument(documentId, host.token())
            host.drain()
            assertTrue(delayed.requested.isCompleted)
            assertEquals(NavigationResult.BLOCKED, BackCoordinator.disposition(host.runtime.navigator.state.value))
            assertTrue(host.controller.state.value.purchasingDocuments.transfers.isEmpty())
            val next = host.enterOtherEmployee()
            val before = host.controller.state.value.purchasingDocuments
            delayed.release(LocalPurchasingDocumentTransferResult.Captured(documentId, inputs.completeOperationId("PRIMARY-A")))
            host.drain()
            assertTrue(delayed.returned.isCompleted)
            assertEquals(before, host.controller.state.value.purchasingDocuments)
            assertEquals(LocalPurchasingDocumentPresentation(), old.state.value.purchasingDocuments)
            host.controller.transferPurchasingDocument(documentId, host.token())
            host.drain()
            assertEquals(listOf(documentId), host.port.transfers)
            assertTrue(host.port.saves.isEmpty())
            assertTrue(next.transfers.isEmpty())
            assertTrue(next.saves.isEmpty())
            assertTrue(next.unrelatedCalls.isEmpty())
        }
    }

    @Test
    fun di2_021_n04_attachmentReplacementDoesNotCarryPrivateInputIntoItsContinuation() = runTest {
        withDocumentaryClientV2 { host ->
            host.fillDraft("PRIMARY-A")
            val chosen = host.import("PRIMARY-A")
            val next = DocumentaryClientV2Port()
            val renewed = host.replaceAttachment(next)
            assertTrue(renewed.state.value.attachmentReviewRequired)
            assertEquals(LocalPurchasingDocumentPresentation(), renewed.state.value.purchasingDocuments)
            assertFailsWith<IllegalStateException> { chosen.copyBytes() }
            renewed.reviewAttachment(host.token())
            host.drain()
            assertFalse(renewed.state.value.attachmentReviewRequired)
            assertEquals(LocalPurchasingDocumentSourcesResult.Available(emptyList()), renewed.state.value.purchasingDocuments.sources)
            assertNull(renewed.state.value.purchasingDocuments.draft)
            assertNull(renewed.state.value.purchasingDocuments.selectedFile)
            assertTrue(host.port.saves.isEmpty())
            assertTrue(next.saves.isEmpty())
            assertTrue(next.transfers.isEmpty())
            assertTrue(next.unrelatedCalls.isEmpty())
        }
    }
}

package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPurchasingDocumentSourceResult
import com.ukkera.pos.app.LocalPurchasingDocumentTransferResult
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.StoredClientDocumentarySourceV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentResultV1
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Frozen DI2-013 retry identity slices; controlled replies do not qualify a native parent or CAS. */
class DocumentaryClientFrozenCompletionRetrySlicesTest {
    private val inputs = FrozenDocumentaryClientInputsV2

    @Test
    fun di2_013_wrongFutureCasRefusalRemainsExactAndReviewedLowerHeadUsesFixedNewCompletion() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("SMALL-CHILD")
            // WRONG-PARENT-CAS is R+1 while the controlled actual head remains R.
            val original = inputs.source(document, parentAttachmentsRevision = inputs.parentRevision.next())
            val retryId = inputs.operation(111, 901)
            assertEquals(OperationId("10000000-0000-4000-8000-000000111901"), retryId)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(original))
            val port = ControlledDocumentaryClientPort(original)
            port.resultBody = { request -> if (request.operationId == document.completeId)
                LocalPurchasingDocumentResultV1("purchasing.document.stale_revision", document.id)
                else inputs.acceptedBody(original, request) }
            val refused = LocalPurchasingDocumentTransferResult.Rejected(document.id, document.completeId,
                "purchasing.document.stale_revision")
            assertEquals(refused, port.gateway(host).transferPurchasingDocument(document.id))
            val retainedRefusal = host.find(document.completeId)
            assertEquals(LocalPendingPhase.RECORDED_UNACKNOWLEDGED, retainedRefusal.localPhase)
            assertFalse(retainedRefusal.acknowledged)
            assertTrue(original.hasClosedStaleCompletion(retainedRefusal))
            assertEquals(refused, port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(original.operationIds, port.executions.map { it.operationId })

            val retry = original.prepareCompletionRetry(retryId, inputs.parentRevision)
            assertTrue(retry.isOneCompletionAppendOf(original))
            assertEquals(listOf(document.completeId, retryId), retry.completionOperationIds)
            assertEquals(original.complete, retry.complete)
            inputs.steps(original).forEach { request ->
                assertContentEquals(request.payload.copyBytes(), requireNotNull(retry.request(request.operationId)).payload.copyBytes())
            }
            assertEquals(inputs.parentRevision.next(), LocalPurchasingDocumentCodecV1.decodeComplete(retry.completeRequest().payload)
                .expectedParentAttachmentsRevision)
            assertEquals(inputs.parentRevision, LocalPurchasingDocumentCodecV1.decodeComplete(retry.latestCompleteRequest().payload)
                .expectedParentAttachmentsRevision)
            assertEquals(JournalWrite.DURABLE, host.journal.appendDocumentaryCompletionRetry(retry))
            assertEquals(JournalWrite.DURABLE, host.journal.appendDocumentaryCompletionRetry(retry))
            assertEquals(listOf(document.completeId), host.pending().map { it.operationId })
            assertEquals(original.operationIds, port.executions.map { it.operationId }) // saving is not dispatch
            host.reopenOriginal()
            assertEquals(retry, host.source(document.id))
            assertEquals(retainedRefusal.recorded, host.find(document.completeId).recorded)
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())

            // A lost local save response can recover only the already saved immediate child.
            port.view = inputs.view(inputs.otherOwner)
            assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(port.gateway(host).retryPurchasingDocumentCompletion(
                document.id, document.completeId, inputs.parentRevision))
            port.view = inputs.view()
            port.recoveryAllowed = false
            assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(port.gateway(host).retryPurchasingDocumentCompletion(
                document.id, document.completeId, inputs.parentRevision))
            port.recoveryAllowed = true
            repeat(2) {
                assertEquals(LocalPurchasingDocumentSourceResult.Saved(document.id), port.gateway(host).retryPurchasingDocumentCompletion(
                    document.id, document.completeId, inputs.parentRevision))
            }
            assertEquals(retry, host.source(document.id))
            assertEquals(2, host.source(document.id).completionAttemptCount)
            assertTrue(host.find(document.completeId).acknowledged)
            assertTrue(host.pending().isEmpty())
            assertEquals(original.operationIds, port.executions.map { it.operationId })

            assertEquals(LocalPurchasingDocumentTransferResult.Captured(document.id, retryId),
                port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(original.operationIds + retryId, port.executions.map { it.operationId })
            assertEquals(retainedRefusal.recorded, host.find(document.completeId).recorded)
            assertContentEquals(original.completeRequest().payload.copyBytes(), host.find(document.completeId).request().payload.copyBytes())
            assertContentEquals(retry.latestCompleteRequest().payload.copyBytes(), host.find(retryId).request().payload.copyBytes())
            assertTrue(host.find(retryId).acknowledged)
            host.reopenOriginal()
            assertEquals(retry, host.source(document.id))
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
        }
    }

    @Test
    fun di2_008_013_unknownOrDifferentOrMalformedRefusalCannotAuthorizeCompletionAppend() = runBlocking {
        val document = inputs.document("SMALL-CHILD")
        val original = inputs.source(document, parentAttachmentsRevision = inputs.parentRevision.next())
        val specimens = listOf(null,
            LocalPurchasingDocumentResultV1("purchasing.document.media_mismatch", document.id),
            LocalPurchasingDocumentResultV1("purchasing.document.stale_revision", document.id, byteLength = document.file.byteLength))
        specimens.forEach { body ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(original))
                val request = original.completeRequest()
                assertEquals(JournalWrite.DURABLE, host.journal.stage(inputs.staged(original, request)))
                assertEquals(JournalWrite.DURABLE, host.journal.markDispatched(request.operationId))
                val port = ControlledDocumentaryClientPort(original)
                if (body != null) {
                    val specimen = controlledRecorded(original, request, body, committed = false)
                    port.controlledResults[request.operationId] = specimen
                    assertEquals(JournalWrite.DURABLE, host.journal.remember(request.operationId, StoredKernelResultV1.from(specimen)))
                }
                val before = host.find(request.operationId)
                val retry = original.prepareCompletionRetry(inputs.operation(111, 901), inputs.parentRevision)
                assertNotEquals(JournalWrite.DURABLE, host.journal.appendDocumentaryCompletionRetry(retry))
                assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(port.gateway(host).retryPurchasingDocumentCompletion(
                    document.id, request.operationId, inputs.parentRevision))
                assertEquals(original, host.source(document.id))
                assertEquals(before, host.find(request.operationId))
                assertTrue(port.executions.isEmpty())
                host.reopenOriginal()
                assertEquals(original, host.source(document.id))
                assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
            }
        }
    }

    @Test
    fun di2_008_013_newCompletionCannotOverwriteRetainedPrefixCollideOrBypassRefusalCustody() = runBlocking {
        val document = inputs.document("SMALL-CHILD")
        val original = inputs.source(document, parentAttachmentsRevision = inputs.parentRevision.next())
        val retry = original.prepareCompletionRetry(inputs.operation(111, 901), inputs.parentRevision)
        withDocumentaryJournal { host ->
            assertNotEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(retry))
            assertTrue(host.sources().isEmpty())
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(original))
            assertNotEquals(JournalWrite.DURABLE, host.journal.appendDocumentaryCompletionRetry(retry))
            val request = original.completeRequest()
            assertEquals(JournalWrite.DURABLE, host.journal.stage(inputs.staged(original, request)))
            assertEquals(JournalWrite.DURABLE, host.journal.markDispatched(request.operationId))
            val refusal = StoredKernelResultV1.from(controlledRecorded(original, request,
                LocalPurchasingDocumentResultV1("purchasing.document.stale_revision", document.id), committed = false))
            assertEquals(JournalWrite.DURABLE, host.journal.remember(request.operationId, refusal))
            val other = inputs.source(inputs.document("PRIMARY-B"))
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(other))
            val collision = original.prepareCompletionRetry(other.completeOperationId, inputs.parentRevision)
            assertNotEquals(JournalWrite.DURABLE, host.journal.appendDocumentaryCompletionRetry(collision))
            val changed = StoredClientDocumentarySourceV1.prepare(inputs.owner, document.beginId,
                inputs.begin(document).copy(supplierReferenceOriginal = "0007-a"), document.chunkIds, document.completeId,
                original.complete, document.file.bytes).prepareCompletionRetry(inputs.operation(111, 901), inputs.parentRevision)
            assertNotEquals(JournalWrite.DURABLE, host.journal.appendDocumentaryCompletionRetry(changed))
            assertEquals(original, host.source(document.id))
            assertEquals(refusal, host.find(document.completeId).recorded)
            assertEquals(JournalWrite.DURABLE, host.journal.appendDocumentaryCompletionRetry(retry))
            host.reopenOriginal()
            assertEquals(retry, host.source(document.id))
            assertEquals(other, host.source(other.documentId))
            assertContentEquals(original.completeRequest().payload.copyBytes(), host.find(document.completeId).request().payload.copyBytes())
        }
    }
}

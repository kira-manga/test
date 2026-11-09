package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPurchasingDocumentSourcesResult
import com.ukkera.pos.app.LocalPurchasingDocumentTransferResult
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentStateV1
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Controlled U protocol replies plus real encrypted client storage; never native N/RPC acceptance. */
class DocumentaryClientFrozenTransferSlicesTest {
    private val inputs = FrozenDocumentaryClientInputsV2

    @Test
    fun di2_001_008_sequentialAndConcurrentResumeUseOnlyFiveDurablySavedRequests() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val port = ControlledDocumentaryClientPort(source)
            port.beforeExecute = { request ->
                assertEquals(source, host.source(document.id))
                val pending = host.pending().single()
                assertEquals(request.operationId, pending.operationId)
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, pending.localPhase)
                assertEquals(inputs.owner, pending.owner)
                assertContentEquals(requireNotNull(source.request(request.operationId)).payload.copyBytes(), request.payload.copyBytes())
            }
            val gateway = port.gateway(host)
            val results = coroutineScope {
                val first = async { gateway.transferPurchasingDocument(document.id) }
                val second = async { port.gateway(host).transferPurchasingDocument(document.id) }
                listOf(first.await(), second.await())
            }
            results.forEach { assertEquals(LocalPurchasingDocumentTransferResult.Captured(document.id, document.completeId), it) }
            assertEquals(source.operationIds, port.executions.map { it.operationId })
            assertTrue(host.pending().isEmpty())
            source.operationIds.forEach { id ->
                val entry = host.find(id)
                assertTrue(entry.acknowledged)
                assertEquals(StoredKernelResultV1.from(port.controlledResults.getValue(id)), entry.recorded)
                assertContentEquals(requireNotNull(source.request(id)).payload.copyBytes(), entry.request().payload.copyBytes())
            }
            host.reopenOriginal()
            assertEquals(source, host.source(document.id))
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
        }
    }

    @Test
    fun di2_008_eachLostStepReplyReopensAndRecoversSameOriginalBodyWithNewTransportIdentity() = runBlocking {
        val document = inputs.document("PRIMARY-A")
        val original = inputs.source(document)
        original.operationIds.forEachIndexed { index, lost ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(original))
                val port = ControlledDocumentaryClientPort(original)
                val firstTransport = port.connectionId
                port.loseReplyAt = lost
                val unknown = assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(document.id))
                assertEquals(lost, unknown.operationId)
                assertEquals(original.operationIds.take(index + 1), port.executions.map { it.operationId })
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, host.find(lost).localPhase)
                assertEquals(null, host.find(lost).recorded)
                assertEquals(original, host.source(document.id))
                host.reopenOriginal()
                port.connectionId = DomainId.random()
                val recovered = port.gateway(host).transferPurchasingDocument(document.id)
                assertEquals(LocalPurchasingDocumentTransferResult.Captured(document.id, document.completeId), recovered)
                assertEquals(original.operationIds, port.executions.map { it.operationId })
                assertTrue(port.connectionObservations.any { it != firstTransport })
                assertEquals(StoredKernelResultV1.from(port.controlledResults.getValue(lost)), host.find(lost).recorded)
                assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
                assertTrue(host.pending().isEmpty())
            }
        }
    }

    @Test
    fun di2_021_cancelledWaitAfterSendRetainsOriginalUnknownIdentityInsteadOfReplacementCapture() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val port = ControlledDocumentaryClientPort(source)
            port.afterExecute = { throw CancellationException("Controlled cancelled reply wait") }
            assertFailsWith<CancellationException> { port.gateway(host).transferPurchasingDocument(document.id) }
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, host.find(document.beginId).localPhase)
            assertEquals(null, host.find(document.beginId).recorded)
            assertEquals(source, host.source(document.id))
            host.reopenOriginal()
            port.afterExecute = {}
            assertEquals(LocalPurchasingDocumentTransferResult.Captured(document.id, document.completeId),
                port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(source.operationIds, port.executions.map { it.operationId })
        }
    }

    @Test
    fun di2_007_018_021_lateOriginalReplyCannotPublishIntoDifferentActorSession() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val port = ControlledDocumentaryClientPort(source)
            port.afterExecute = { port.leaseCurrent = false; port.view = inputs.view(inputs.otherOwner) }
            val late = assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(document.beginId, late.operationId)
            assertEquals(null, host.find(document.beginId).recorded)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, host.find(document.beginId).localPhase)
            port.leaseCurrent = true
            assertIs<LocalPurchasingDocumentSourcesResult.Unavailable>(port.gateway(host).purchasingDocumentSources())
            assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(listOf(document.beginId), port.executions.map { it.operationId })
            assertEquals(source, host.source(document.id))
            port.afterExecute = {}
            port.view = inputs.view()
            assertEquals(LocalPurchasingDocumentTransferResult.Captured(document.id, document.completeId),
                port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(source.operationIds, port.executions.map { it.operationId })
        }
    }

    @Test
    fun di2_020_existingResultRecoveryIsSeparateFromNewTransferAndMissingRecoveryScope() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val port = ControlledDocumentaryClientPort(source)
            port.loseReplyAt = document.completeId
            assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(document.id))
            val originalCount = port.executions.size
            port.view = inputs.view(grants = setOf(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM)))
            port.freshWorkAllowed = false
            assertEquals(LocalPurchasingDocumentTransferResult.Captured(document.id, document.completeId),
                port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(originalCount, port.executions.size)
            val other = inputs.source(inputs.document("PRIMARY-B"))
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(other))
            val fresh = assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(other.documentId))
            assertEquals(other.beginOperationId, fresh.operationId)
            assertEquals(originalCount, port.executions.size)
            port.recoveryAllowed = false
            assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(originalCount, port.executions.size)
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
        }
    }

    @Test
    fun di2_004_008_recordedMediaRefusalRemainsOriginalAndDoesNotAutomaticallyForkOrDelete() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document(188, "ONE-INVALID-PDF")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val port = ControlledDocumentaryClientPort(source)
            port.resultBody = { request -> if (request.operationId == document.completeId)
                LocalPurchasingDocumentResultV1("purchasing.document.media_mismatch", document.id)
                else inputs.acceptedBody(source, request) }
            val refused = LocalPurchasingDocumentTransferResult.Rejected(document.id, document.completeId, "purchasing.document.media_mismatch")
            assertEquals(refused, port.gateway(host).transferPurchasingDocument(document.id))
            val retained = host.find(document.completeId)
            assertEquals(LocalPendingPhase.RECORDED_UNACKNOWLEDGED, retained.localPhase)
            assertFalse(retained.acknowledged)
            assertEquals(StoredKernelResultV1.from(port.controlledResults.getValue(document.completeId)), retained.recorded)
            host.reopenOriginal()
            assertEquals(refused, port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(source.operationIds, port.executions.map { it.operationId })
            assertEquals(source, host.source(document.id))
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
        }
    }

    @Test
    fun di2_001_008_026_receiverRejectsWrongOriginalStepAndFalseCompletedLengthHash() = runBlocking {
        val document = inputs.document("PRIMARY-A")
        val source = inputs.source(document)
        val malformed: List<Pair<OperationId, (LocalPurchasingDocumentResultV1) -> LocalPurchasingDocumentResultV1>> = listOf(
            document.beginId to { body -> body.copy(beginOperationId = inputs.document("PRIMARY-B").beginId) },
            document.chunkIds[0] to { body -> body.copy(chunkIndex = 3) },
            document.completeId to { body -> body.copy(byteLength = inputs.file("PDF-A").byteLength) },
            document.completeId to { body -> body.copy(exactBytesDigest = inputs.wrongFullDigest) })
        malformed.forEach { (step, change) ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
                val port = ControlledDocumentaryClientPort(source)
                port.resultBody = { request -> inputs.acceptedBody(source, request).let { if (request.operationId == step) change(it) else it } }
                val result = assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(document.id))
                assertEquals(step, result.operationId)
                assertEquals(null, host.find(step).recorded)
                assertFalse(host.find(step).acknowledged)
                assertEquals(source, host.source(document.id))
            }
        }
    }

    @Test
    fun di2_008_018_receiverDoesNotAcceptForeignActorRecordedEnvelope() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val port = ControlledDocumentaryClientPort(source)
            port.envelope = { reply -> MutationReply.Recorded(reply.operationId, inputs.otherOwner.actorId, reply.committed,
                reply.code, reply.result, reply.authorityEpoch, reply.sequence, reply.recordedAt, reply.replayed) }
            val result = assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(document.id))
            assertEquals(document.beginId, result.operationId)
            assertEquals(null, host.find(document.beginId).recorded)
            assertEquals(source, host.source(document.id))
        }
    }

    @Test
    fun di2_025_malformedRetainedResultCannotBePublishedOrAcknowledgedAsVerifiedHistory() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            val request = source.beginRequest()
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            assertEquals(JournalWrite.DURABLE, host.journal.stage(inputs.staged(source, request)))
            assertEquals(JournalWrite.DURABLE, host.journal.markDispatched(request.operationId))
            val malformed = controlledRecorded(source, request,
                inputs.acceptedBody(source, request).copy(state = LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH))
            assertEquals(JournalWrite.DURABLE, host.journal.remember(request.operationId, StoredKernelResultV1.from(malformed)))
            val port = ControlledDocumentaryClientPort(source)
            port.controlledResults[request.operationId] = malformed
            assertIs<LocalOperationResult.Unresolved>(port.gateway(host).lookup(request.operationId))
            assertIs<LocalAcknowledgementResult.Unresolved>(port.gateway(host).acknowledgeRecorded(request.operationId))
            assertFalse(host.find(request.operationId).acknowledged)
            assertEquals(source, host.source(document.id))
            assertTrue(port.executions.isEmpty())
        }
    }
}

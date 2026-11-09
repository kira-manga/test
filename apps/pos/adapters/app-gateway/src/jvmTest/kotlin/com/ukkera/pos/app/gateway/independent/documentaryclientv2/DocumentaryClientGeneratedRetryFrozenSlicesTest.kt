package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.LocalPurchasingDocumentSourceResult
import com.ukkera.pos.app.LocalPurchasingDocumentTransferResult
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentResultV1
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Additive PCR1 client slices: real encrypted custody, controlled endpoint/failure seams. */
class DocumentaryClientGeneratedRetryFrozenSlicesTest {
    private val inputs = FrozenDocumentaryClientInputsV2

    @Test
    fun pcr1_001_generatedReviewIsDurableDistinctImmutableAndNeverAutomaticallySent() = runBlocking {
        knownRefusal { host, original, port ->
            val refusal = host.find(original.completeOperationId).recorded
            assertEquals(LocalPurchasingDocumentSourceResult.Saved(original.documentId),
                port.gateway(host).retryPurchasingDocumentCompletion(original.documentId,
                    original.completeOperationId, inputs.parentRevision))
            val reviewed = host.source(original.documentId)
            assertReviewed(original, reviewed)
            assertEquals(refusal, host.find(original.completeOperationId).recorded)
            assertTrue(host.find(original.completeOperationId).acknowledged)
            assertEquals(original.operationIds, port.executions.map { it.operationId })
            host.reopenOriginal()
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(LocalPurchasingDocumentSourceResult.Saved(original.documentId),
                port.gateway(host).retryPurchasingDocumentCompletion(original.documentId,
                    original.completeOperationId, inputs.parentRevision))
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(refusal, host.find(original.completeOperationId).recorded)
            assertEquals(original.operationIds, port.executions.map { it.operationId })
        }
    }

    @Test
    fun pcr1_002_beforeAppendFailureRetainsOnlyTheOriginalAndExplicitRetrySavesOneChild() = runBlocking {
        knownRefusal { host, original, port ->
            val refusal = host.find(original.completeOperationId)
            val failing = FaultingDocumentaryJournal(host.journal, failBeforeAppend = true)
            assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(gateway(port, failing)
                .retryPurchasingDocumentCompletion(original.documentId, original.completeOperationId, inputs.parentRevision))
            assertEquals(1, failing.appendCalls)
            assertEquals(0, failing.durableAppends)
            assertEquals(0, failing.acknowledgeCalls)
            assertEquals(original, host.source(original.documentId))
            assertEquals(refusal, host.find(original.completeOperationId))
            host.reopenOriginal()
            assertEquals(original, host.source(original.documentId))
            assertEquals(original.operationIds, port.executions.map { it.operationId })
            assertEquals(LocalPurchasingDocumentSourceResult.Saved(original.documentId),
                port.gateway(host).retryPurchasingDocumentCompletion(original.documentId,
                    original.completeOperationId, inputs.parentRevision))
            assertReviewed(original, host.source(original.documentId))
            assertEquals(original.operationIds, port.executions.map { it.operationId })
        }
    }

    @Test
    fun pcr1_002_lostDurableAppendReplyReopensAndRecoversExactlyTheImmediateChild() = runBlocking {
        knownRefusal { host, original, port ->
            val refusal = host.find(original.completeOperationId)
            val failing = FaultingDocumentaryJournal(host.journal, loseAppendReply = true)
            assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(gateway(port, failing)
                .retryPurchasingDocumentCompletion(original.documentId, original.completeOperationId, inputs.parentRevision))
            assertEquals(1, failing.appendCalls)
            assertEquals(1, failing.durableAppends)
            assertEquals(0, failing.acknowledgeCalls)
            val reviewed = host.source(original.documentId)
            assertReviewed(original, reviewed)
            assertEquals(refusal, host.find(original.completeOperationId))
            assertEquals(original.operationIds, port.executions.map { it.operationId })
            host.reopenOriginal()
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(LocalPurchasingDocumentSourceResult.Saved(original.documentId),
                port.gateway(host).retryPurchasingDocumentCompletion(original.documentId,
                    original.completeOperationId, inputs.parentRevision))
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(refusal.recorded, host.find(original.completeOperationId).recorded)
            assertTrue(host.find(original.completeOperationId).acknowledged)
            assertEquals(original.operationIds, port.executions.map { it.operationId })
        }
    }

    @Test
    fun pcr1_001_002_archiveFailureOccursOnlyAfterDurableChildAndRetryDoesNotCreateASibling() = runBlocking {
        knownRefusal { host, original, port ->
            val refusal = host.find(original.completeOperationId)
            val failing = FaultingDocumentaryJournal(host.journal, failAcknowledge = true)
            assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(gateway(port, failing)
                .retryPurchasingDocumentCompletion(original.documentId, original.completeOperationId, inputs.parentRevision))
            assertEquals(1, failing.appendCalls)
            assertEquals(1, failing.durableAppends)
            assertEquals(1, failing.acknowledgeCalls)
            val reviewed = host.source(original.documentId)
            assertReviewed(original, reviewed)
            assertEquals(refusal, host.find(original.completeOperationId))
            host.reopenOriginal()
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(LocalPurchasingDocumentSourceResult.Saved(original.documentId),
                port.gateway(host).retryPurchasingDocumentCompletion(original.documentId,
                    original.completeOperationId, inputs.parentRevision))
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(original.operationIds, port.executions.map { it.operationId })
        }
    }

    @Test
    fun pcr1_003_generatedStepRequiresExplicitTransferAndLostReplyRecoversTheSameIdentity() = runBlocking {
        knownRefusal { host, original, port ->
            val refusal = host.find(original.completeOperationId).recorded
            assertIs<LocalPurchasingDocumentSourceResult.Saved>(port.gateway(host)
                .retryPurchasingDocumentCompletion(original.documentId, original.completeOperationId, inputs.parentRevision))
            val reviewed = host.source(original.documentId)
            assertReviewed(original, reviewed)
            assertEquals(original.operationIds, port.executions.map { it.operationId })
            port.loseReplyAt = reviewed.latestCompleteOperationId
            assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host)
                .transferPurchasingDocument(original.documentId))
            assertEquals(original.operationIds + reviewed.latestCompleteOperationId, port.executions.map { it.operationId })
            val reply = assertNotNull(port.controlledResults[reviewed.latestCompleteOperationId])
            host.reopenOriginal()
            port.connectionId = DomainId.random()
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(original.operationIds + reviewed.latestCompleteOperationId, port.executions.map { it.operationId })
            val expected = LocalPurchasingDocumentTransferResult.Captured(original.documentId, reviewed.latestCompleteOperationId)
            assertEquals(expected, port.gateway(host).transferPurchasingDocument(original.documentId))
            assertEquals(expected, port.gateway(host).transferPurchasingDocument(original.documentId))
            assertEquals(original.operationIds + reviewed.latestCompleteOperationId, port.executions.map { it.operationId })
            assertEquals(StoredKernelResultV1.from(reply), host.find(reviewed.latestCompleteOperationId).recorded)
            assertEquals(refusal, host.find(original.completeOperationId).recorded)
            assertEquals(reviewed, host.source(original.documentId))
        }
    }

    @Test
    fun pcr1_004_005_unknownRefusalOrMissingCurrentActorGrantCannotForkOrAdoptASource() = runBlocking {
        withDocumentaryJournal { host ->
            val original = inputs.source(inputs.document("SMALL-CHILD"), parentAttachmentsRevision = inputs.parentRevision.next())
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(original))
            val port = refusalPort(original)
            port.loseReplyAt = original.completeOperationId
            assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(original.documentId))
            assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(port.gateway(host)
                .retryPurchasingDocumentCompletion(original.documentId, original.completeOperationId, inputs.parentRevision))
            assertEquals(original, host.source(original.documentId))
            assertEquals(original.operationIds, port.executions.map { it.operationId })
            host.reopenOriginal()
            assertEquals(LocalPurchasingDocumentTransferResult.Rejected(original.documentId, original.completeOperationId,
                "purchasing.document.stale_revision"), port.gateway(host).transferPurchasingDocument(original.documentId))
            val refusal = host.find(original.completeOperationId)
            for (view in listOf(inputs.view(grants = emptySet()), inputs.view(inputs.otherOwner), inputs.view(inputs.foreignOwner))) {
                port.view = view
                assertIs<LocalPurchasingDocumentSourceResult.Unavailable>(port.gateway(host)
                    .retryPurchasingDocumentCompletion(original.documentId, original.completeOperationId, inputs.parentRevision))
                assertEquals(original, host.source(original.documentId))
                assertEquals(refusal, host.find(original.completeOperationId))
                assertEquals(original.operationIds, port.executions.map { it.operationId })
            }
            port.view = inputs.view()
            assertIs<LocalPurchasingDocumentSourceResult.Saved>(port.gateway(host)
                .retryPurchasingDocumentCompletion(original.documentId, original.completeOperationId, inputs.parentRevision))
            val reviewed = host.source(original.documentId)
            assertReviewed(original, reviewed)
            port.view = inputs.view(grants = emptySet())
            assertIs<LocalPurchasingDocumentTransferResult.Unresolved>(port.gateway(host).transferPurchasingDocument(original.documentId))
            assertEquals(reviewed, host.source(original.documentId))
            port.view = inputs.view()
            host.reopenOriginal()
            assertEquals(reviewed, host.source(original.documentId))
            assertEquals(original.operationIds, port.executions.map { it.operationId })
        }
    }

    private fun assertReviewed(original: StoredClientDocumentarySourceV1, reviewed: StoredClientDocumentarySourceV1) {
        assertEquals(original.documentId, reviewed.documentId)
        assertEquals(original.owner, reviewed.owner)
        assertEquals(original.completeOperationId, reviewed.priorCompletionOperationIds.single())
        assertEquals(2, reviewed.completionAttemptCount)
        assertFalse(reviewed.latestCompleteOperationId in original.operationIds)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            .matches(reviewed.latestCompleteOperationId.value))
        assertEquals(reviewed.latestCompleteOperationId, OperationId(reviewed.latestCompleteOperationId.value))
        assertEquals(original.complete.copy(expectedParentAttachmentsRevision = inputs.parentRevision),
            LocalPurchasingDocumentCodecV1.decodeComplete(reviewed.latestCompleteRequest().payload))
        inputs.steps(original).forEach { before ->
            val retained = assertNotNull(reviewed.request(before.operationId))
            assertEquals(before.command, retained.command)
            assertEquals(before.operationId, retained.operationId)
            assertContentEquals(before.payload.copyBytes(), retained.payload.copyBytes())
        }
        assertContentEquals(inputs.file("PNG-A").bytes, reviewed.copyOriginalBytes())
    }

    private fun refusalPort(original: StoredClientDocumentarySourceV1) = ControlledDocumentaryClientPort(original).apply {
        resultBody = { request -> if (request.operationId == original.completeOperationId)
            LocalPurchasingDocumentResultV1("purchasing.document.stale_revision", original.documentId)
            else inputs.acceptedBody(original, request) }
    }

    private suspend fun knownRefusal(block: suspend (SyntheticDocumentaryEncryptedJournal,
        StoredClientDocumentarySourceV1, ControlledDocumentaryClientPort) -> Unit) = withDocumentaryJournal { host ->
        val original = inputs.source(inputs.document("SMALL-CHILD"), parentAttachmentsRevision = inputs.parentRevision.next())
        assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(original))
        val port = refusalPort(original)
        assertEquals(LocalPurchasingDocumentTransferResult.Rejected(original.documentId, original.completeOperationId,
            "purchasing.document.stale_revision"), port.gateway(host).transferPurchasingDocument(original.documentId))
        block(host, original, port)
    }

    private fun gateway(port: ControlledDocumentaryClientPort, journal: LocalClientIntentJournal) = LocalEndpointWorkflowGateway(
        port, journal, LocalWorkflowCredentialSource {
            if (!port.leaseCurrent) null else LocalWorkflowCredentialLease(LocalGatewayCredentials(port.connectionId,
                OpaqueCredential(ByteArray(32) { 0x31 }))) { port.leaseCurrent }
        }, LocalWorkflowInstallation(inputs.commands, setOf(LocalPurchasingDocumentProfileV1.READ, LocalPurchasingDocumentProfileV1.BYTES)))
}

/** Forward all successful IO to the real encrypted journal; inject only the named reply cut. */
private class FaultingDocumentaryJournal(
    private val delegate: LocalClientIntentJournal,
    private val failBeforeAppend: Boolean = false,
    private val loseAppendReply: Boolean = false,
    private val failAcknowledge: Boolean = false,
) : LocalClientIntentJournal(delegate.owner) {
    var appendCalls: Int = 0
        private set
    var durableAppends: Int = 0
        private set
    var acknowledgeCalls: Int = 0
        private set
    override suspend fun find(operationId: OperationId) = delegate.find(operationId)
    override suspend fun pending() = delegate.pending()
    override suspend fun stage(intent: StoredClientIntentV1) = delegate.stage(intent)
    override suspend fun markDispatched(operationId: OperationId) = delegate.markDispatched(operationId)
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1) = delegate.remember(operationId, result)
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgeCalls++
        return if (failAcknowledge) JournalWrite.UNAVAILABLE else delegate.acknowledge(operationId, result)
    }
    override suspend fun saveDocumentarySource(source: StoredClientDocumentarySourceV1) = delegate.saveDocumentarySource(source)
    override suspend fun appendDocumentaryCompletionRetry(source: StoredClientDocumentarySourceV1): JournalWrite {
        appendCalls++
        if (failBeforeAppend) return JournalWrite.UNAVAILABLE
        val outcome = delegate.appendDocumentaryCompletionRetry(source)
        if (outcome == JournalWrite.DURABLE) durableAppends++
        if (loseAppendReply && outcome == JournalWrite.DURABLE) error("Controlled reply loss after durable append")
        return outcome
    }
    override suspend fun documentarySource(documentId: DomainId) = delegate.documentarySource(documentId)
    override suspend fun documentarySources() = delegate.documentarySources()
    override suspend fun close() = delegate.close()
}

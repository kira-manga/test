package com.ukkera.pos.app.gateway.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JvmClientJournalCustody
import com.ukkera.pos.app.gateway.JvmClientJournalCustodyView
import com.ukkera.pos.app.gateway.JvmClientJournalOpenResult
import com.ukkera.pos.app.gateway.JvmEncryptedClientIntentJournal
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen CCM v1.0.1 / binding v1.0.0, F-QUALITY: original24/240, matched22/220.
 * Actual gateway and AES-GCM client storage with close/reopen and damaged-file probes.
 * The Master endpoint, key custody and current-session observations remain controlled;
 * these checks are not Master/Room/RPC, process-crash, native-key or whole-case proof.
 */
class CapturedInvoiceMatchingEncryptedJournalTest {
    @Test
    fun ccm07And10LostReplyReopensExactOriginalAndArchivesOnlyAfterExplicitAcknowledgement() = runTest(timeout = 30.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val storage = CapturedMatchEncryptedJournal.create(LocalClientIdentity(f.scope, f.actor, f.device))
        try {
            val intent = f.intent()
            val gateway = f.gateway(storage.journal)
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val staged = storage.find(intent.operationId)
            assertExactQualityMatchRequest(f, staged.request(), intent)
            f.endpoint.loseNextReply = true
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId)).problem)
            val outstanding = storage.find(intent.operationId)
            val originalReply = assertNotNull(f.endpoint.found)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertEquals(staged.owner, outstanding.owner)
            assertEquals(staged.request().payload, outstanding.request().payload)

            storage.reopen()
            assertEquals(outstanding, storage.find(intent.operationId))
            assertEquals(listOf(outstanding), storage.pending())
            val readsBefore = f.endpoint.reads.size
            f.endpoint.rejectReads = true
            val reopenedGateway = f.gateway(storage.journal)
            val recovered = assertIs<LocalOperationResult.Recorded>(reopenedGateway.lookup(intent.operationId))
            val retained = storage.find(intent.operationId)
            assertOriginalQualityResult(f, recovered, originalReply, retained)
            assertTrue(recovered.replayed)
            assertEquals(outstanding.identity(), f.endpoint.lookups.last())
            assertEquals(outstanding.request().payload, retained.request().payload)
            assertEquals(outstanding.semanticSha256, retained.semanticSha256)

            // Retaining the result is not acknowledgement: it must remain in the queue.
            storage.reopen()
            assertEquals(retained, storage.find(intent.operationId))
            assertEquals(listOf(retained), storage.pending())
            val retainedGateway = f.gateway(storage.journal)
            val repeated = assertIs<LocalOperationResult.Recorded>(retainedGateway.submit(intent.operationId))
            assertOriginalQualityResult(f, repeated, originalReply, storage.find(intent.operationId))
            assertEquals(recovered.result, repeated.result)
            assertIs<LocalAcknowledgementResult.Acknowledged>(retainedGateway.acknowledgeRecorded(intent.operationId))
            val archived = storage.find(intent.operationId)
            assertEquals(retained.copy(acknowledged = true), archived)
            assertTrue(storage.pending().isEmpty())

            storage.reopen()
            assertEquals(archived, storage.find(intent.operationId))
            assertTrue(storage.pending().isEmpty())
            val archivedGateway = f.gateway(storage.journal)
            val historical = assertIs<LocalOperationResult.Recorded>(archivedGateway.lookup(intent.operationId))
            assertEquals(recovered.result, historical.result)
            assertTrue(historical.replayed)
            assertEquals(archived, storage.find(intent.operationId))
            assertIs<LocalAcknowledgementResult.Acknowledged>(archivedGateway.acknowledgeRecorded(intent.operationId))
            assertEquals(archived, storage.find(intent.operationId))
            assertTrue(storage.pending().isEmpty())
            assertEquals(1, f.endpoint.executions.size)
            assertEquals(staged.request().payload, f.endpoint.executions.single().payload)
            assertEquals(readsBefore, f.endpoint.reads.size)
            assertExactQualityMatchRequest(f, archived.request(), intent)
        } finally { storage.dispose() }
    }

    @Test
    fun ccm08And10ReopenedOriginalRejectsChangedBodyAndOtherActor() = runTest(timeout = 30.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val storage = CapturedMatchEncryptedJournal.create(LocalClientIdentity(f.scope, f.actor, f.device))
        try {
            val intent = f.intent()
            val gateway = f.gateway(storage.journal)
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            f.endpoint.loseNextReply = true
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            val original = storage.find(intent.operationId)
            val originalReply = assertNotNull(f.endpoint.found)
            storage.reopen()
            val reopenedGateway = f.gateway(storage.journal)
            val readsBefore = f.endpoint.reads.size
            val lookupsBefore = f.endpoint.lookups.size
            f.endpoint.rejectReads = true
            // The same frozen identity-conflict probe as the existing CCM-10 supporting case.
            assertIs<LocalStageResult.Uncertain>(reopenedGateway.stage(f.intent(quantity = "21")))
            assertEquals(original, storage.find(intent.operationId))
            assertEquals(readsBefore, f.endpoint.reads.size)
            assertEquals(lookupsBefore, f.endpoint.lookups.size)
            for (current in listOf(LocalSessionViewResult.Denied,
                LocalSessionViewResult.Current(f.view(actorId = f.otherActor)))) {
                f.endpoint.currentReply = current
                assertIs<LocalStageResult.Uncertain>(reopenedGateway.stage(intent))
                assertEquals(LocalServiceProblem.SESSION_ENDED,
                    assertIs<LocalOperationResult.Unresolved>(reopenedGateway.submit(intent.operationId)).problem)
                assertEquals(LocalServiceProblem.SESSION_ENDED,
                    assertIs<LocalOperationResult.Unresolved>(reopenedGateway.lookup(intent.operationId)).problem)
                assertEquals(original, storage.find(intent.operationId))
                assertEquals(listOf(original), storage.pending())
                assertEquals(originalReply, f.endpoint.found)
                assertEquals(readsBefore, f.endpoint.reads.size)
                assertEquals(lookupsBefore, f.endpoint.lookups.size)
                assertEquals(1, f.endpoint.executions.size)
            }
            f.endpoint.currentReply = null
            storage.reopen()
            assertEquals(original, storage.find(intent.operationId))
            val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway(storage.journal).lookup(intent.operationId))
            assertOriginalQualityResult(f, recovered, originalReply, storage.find(intent.operationId))
            assertExactQualityMatchRequest(f, storage.find(intent.operationId).request(), intent)
            assertEquals(1, f.endpoint.executions.size)
        } finally { storage.dispose() }
    }

    @Test
    fun ccm10MissingOrTruncatedActiveHistoryCannotReopenAsAnEmptyQueue() = runTest(timeout = 30.seconds) {
        for (remove in listOf(false, true)) {
            val f = CapturedInvoiceMatchingGatewayFixture()
            val storage = CapturedMatchEncryptedJournal.create(LocalClientIdentity(f.scope, f.actor, f.device))
            try {
                val gateway = f.gateway(storage.journal)
                assertIs<LocalStageResult.Staged>(gateway.stage(f.intent()))
                f.endpoint.loseNextReply = true
                assertIs<LocalOperationResult.Unresolved>(gateway.submit(f.operation))
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, storage.find(f.operation).localPhase)
                val originalReply = assertNotNull(f.endpoint.found)
                storage.damageRetainedFile("active.v1.enc", remove)
                assertIs<JvmClientJournalOpenResult.Unavailable>(storage.openExisting())
                assertEquals(originalReply, f.endpoint.found)
                assertEquals(1, f.endpoint.executions.size)
            } finally { storage.dispose() }
        }
    }

    @Test
    fun ccm10MissingOrTruncatedAcknowledgedArchiveCannotReopenAsAnEmptyQueue() = runTest(timeout = 30.seconds) {
        for (remove in listOf(false, true)) {
            val f = CapturedInvoiceMatchingGatewayFixture()
            val storage = CapturedMatchEncryptedJournal.create(LocalClientIdentity(f.scope, f.actor, f.device))
            try {
                val gateway = f.gateway(storage.journal)
                assertIs<LocalStageResult.Staged>(gateway.stage(f.intent()))
                assertIs<LocalOperationResult.Recorded>(gateway.submit(f.operation))
                assertIs<LocalAcknowledgementResult.Acknowledged>(gateway.acknowledgeRecorded(f.operation))
                val originalReply = assertNotNull(f.endpoint.found)
                val archived = storage.find(f.operation)
                assertTrue(archived.acknowledged)
                assertEquals(StoredKernelResultV1.from(originalReply), archived.recorded)
                assertTrue(storage.pending().isEmpty())
                storage.damageRetainedFile("archive/${f.operation.value}.v1.enc", remove)
                assertIs<JvmClientJournalOpenResult.Unavailable>(storage.openExisting())
                assertEquals(originalReply, f.endpoint.found)
                assertEquals(1, f.endpoint.executions.size)
            } finally { storage.dispose() }
        }
    }
}

/** Real private files and production encryption; only custody/key observations are synthetic. */
private class CapturedMatchEncryptedJournal private constructor(
    private val directory: Path,
    private val owner: LocalClientIdentity,
    private val key: SecretKey,
) {
    private val journalId = DomainId.random()
    private val keyId = DomainId.random()
    private var contextLive = AtomicBoolean(true)
    private var view = newView()
    private val custody = JvmClientJournalCustody { view.takeIf { it.isCurrent() } }
    private var opened: JvmEncryptedClientIntentJournal? = null
    val journal: JvmEncryptedClientIntentJournal get() = requireNotNull(opened)

    private fun newView() = JvmClientJournalCustodyView(
        directory, owner, journalId, keyId, DomainId.random(), key, contextLive::get)

    private suspend fun closeHandle() {
        opened?.close()
        opened = null
        contextLive.set(false)
    }

    suspend fun openExisting(): JvmClientJournalOpenResult {
        closeHandle()
        contextLive = AtomicBoolean(true)
        view = newView()
        return JvmEncryptedClientIntentJournal.openExisting(custody).also {
            if (it is JvmClientJournalOpenResult.Opened) opened = it.journal
        }
    }

    suspend fun reopen() {
        assertIs<JvmClientJournalOpenResult.Opened>(openExisting(),
            "The retained encrypted journal must reopen, not initialize replacement history")
    }

    suspend fun find(operationId: OperationId): StoredClientIntentV1 =
        assertNotNull(assertIs<JournalRead.Value<StoredClientIntentV1?>>(journal.find(operationId)).value)

    suspend fun pending(): List<StoredClientIntentV1> =
        assertIs<JournalRead.Value<List<StoredClientIntentV1>>>(journal.pending()).value

    suspend fun damageRetainedFile(relative: String, remove: Boolean) {
        closeHandle()
        withContext(Dispatchers.IO) {
            val path = directory.resolve(relative)
            if (remove) Files.delete(path)
            else {
                val bytes = Files.readAllBytes(path)
                check(bytes.isNotEmpty())
                Files.write(path, bytes.copyOf(bytes.size / 2), StandardOpenOption.TRUNCATE_EXISTING)
            }
        }
    }

    suspend fun dispose() = withContext(NonCancellable) {
        try { closeHandle() }
        finally {
            withContext(Dispatchers.IO) {
                // This fixture owns only this newly created synthetic directory.
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    companion object {
        suspend fun create(owner: LocalClientIdentity): CapturedMatchEncryptedJournal {
            val directory = Files.createTempDirectory("captured-match-client-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val storage = CapturedMatchEncryptedJournal(directory, owner,
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            try {
                storage.opened = assertIs<JvmClientJournalOpenResult.Opened>(
                    JvmEncryptedClientIntentJournal.createNew(storage.custody),
                    "This check requires actual private POSIX storage, AES-GCM and durable IO").journal
                return storage
            } catch (failure: Throwable) {
                storage.dispose()
                throw failure
            }
        }
    }
}

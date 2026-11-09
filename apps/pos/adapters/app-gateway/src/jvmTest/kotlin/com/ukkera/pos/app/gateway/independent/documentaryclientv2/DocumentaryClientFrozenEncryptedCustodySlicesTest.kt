package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.JvmClientJournalOpenResult
import com.ukkera.pos.app.gateway.StoredClientDocumentarySourceV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.catalog.store.LocalCompletePurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.catalog.store.LocalWritePurchasingDocumentChunkV1
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real encrypted-file client slices only; no genuine Master/RPC/native custody result. */
class DocumentaryClientFrozenEncryptedCustodySlicesTest {
    private val inputs = FrozenDocumentaryClientInputsV2

    @Test
    fun di2_001_003_008_originalSourceBodiesAndBytesSurviveSameCustodyReopen() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val bodies = inputs.steps(source).map { it.payload.copyBytes() }
            val copy = host.source(document.id).copyOriginalBytes()
            copy.fill(0)
            assertTrue(host.pending().isEmpty()) // private saving is not a branch Begin/result
            host.reopenOriginal()
            val reopened = host.source(document.id)
            assertEquals(source, reopened)
            assertEquals(source.operationIds, reopened.operationIds)
            assertContentEquals(document.file.bytes, reopened.copyOriginalBytes())
            inputs.steps(reopened).forEachIndexed { index, request -> assertContentEquals(bodies[index], request.payload.copyBytes()) }
            assertEquals(listOf(source), host.sources())
        }
    }

    @Test
    fun di2_006_008_changedDocumentReferenceOrOriginalStepIdentityCannotReplaceSource() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val changedReference = StoredClientDocumentarySourceV1.prepare(inputs.owner, document.beginId,
                inputs.begin(document).copy(supplierReferenceOriginal = "0007-a"), document.chunkIds, document.completeId,
                LocalCompletePurchasingDocumentV1(document.id, document.beginId), document.file.bytes)
            assertEquals(JournalWrite.IDENTITY_CONFLICT, host.journal.saveDocumentarySource(changedReference))
            val other = inputs.document("PRIMARY-B")
            val changedDocument = StoredClientDocumentarySourceV1.prepare(inputs.owner, document.beginId,
                inputs.begin(other), other.chunkIds, other.completeId,
                LocalCompletePurchasingDocumentV1(other.id, document.beginId), other.file.bytes)
            assertEquals(JournalWrite.IDENTITY_CONFLICT, host.journal.saveDocumentarySource(changedDocument))
            assertEquals(source, host.source(document.id))
            assertEquals(listOf(source), host.sources())
            host.reopenOriginal()
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
            assertIs<JournalRead.Value<StoredClientDocumentarySourceV1?>>(host.journal.documentarySource(other.id)).let { assertEquals(null, it.value) }
        }
    }

    @Test
    fun di2_005_008_savedSlotOperationCannotStageShortCorruptOrChangedBody() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val begin = inputs.staged(source, source.beginRequest())
            assertEquals(JournalWrite.DURABLE, host.journal.stage(begin))
            val full = document.file.slot(0)
            val corrupt = full.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            listOf(full.copyOf(8191), corrupt).forEach { bytes ->
                val bad = LocalPurchasingDocumentCodecV1.writeChunk(document.chunkIds[0], LocalWritePurchasingDocumentChunkV1(
                    document.id, document.beginId, 0, inputs.hex(bytes)))
                assertEquals(JournalWrite.IDENTITY_CONFLICT, host.journal.stage(inputs.staged(source, bad)))
            }
            assertEquals(listOf(begin), host.pending())
            val exact = inputs.staged(source, source.chunkRequest(0))
            assertEquals(JournalWrite.DURABLE, host.journal.stage(exact))
            assertEquals(JournalWrite.DURABLE, host.journal.stage(exact))
            host.reopenOriginal()
            assertEquals(LocalPendingPhase.STAGED, host.find(exact.operationId).localPhase)
            assertContentEquals(source.chunkRequest(0).payload.copyBytes(), host.find(exact.operationId).request().payload.copyBytes())
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
        }
    }

    @Test
    fun di2_021_noDocumentaryStepCanBeStagedWithoutItsOriginalSourceCustody() = runBlocking {
        withDocumentaryJournal { host ->
            val source = inputs.source(inputs.document("PRIMARY-A"))
            assertEquals(JournalWrite.IDENTITY_CONFLICT, host.journal.stage(inputs.staged(source, source.beginRequest())))
            assertTrue(host.pending().isEmpty())
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            assertEquals(source, host.source(source.documentId))
        }
    }

    @Test
    fun di2_007_018_021_originalActorScopeAndDeviceCustodyCannotBeAdopted() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            assertIs<JvmClientJournalOpenResult.Unavailable>(host.openExistingAs(inputs.otherOwner))
            host.reopenOriginal()
            assertEquals(source, host.source(document.id))
            assertIs<JvmClientJournalOpenResult.Unavailable>(host.openExistingAs(inputs.foreignOwner))
            host.reopenOriginal()
            val wrongDevice = inputs.owner.copy(deviceId = inputs.otherOwner.actorId)
            assertIs<JvmClientJournalOpenResult.Unavailable>(host.openExistingAs(wrongDevice))
            host.reopenOriginal()
            assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
            assertEquals(JournalWrite.UNAVAILABLE, host.journal.saveDocumentarySource(inputs.source(document, inputs.otherOwner)))
            host.reopenOriginal()
            assertEquals(source, host.source(document.id))
        }
    }

    @Test
    fun di2_025_activeDocumentarySourceOmissionIsGapNotEmptyRecoveredManifest() = runBlocking {
        withDocumentaryJournal { host ->
            val source = inputs.source(inputs.document("PRIMARY-A"))
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            assertEquals(JournalWrite.DURABLE, host.journal.stage(inputs.staged(source, source.beginRequest())))
            assertEquals(JournalWrite.DURABLE, host.journal.markDispatched(source.beginOperationId))
            host.removeDocumentarySources()
            val altered = host.encryptedManifest()
            assertIs<JvmClientJournalOpenResult.Unavailable>(host.openExistingAs())
            assertContentEquals(altered, host.encryptedManifest())
        }
    }

    @Test
    fun di2_025_archivedDocumentarySourceOmissionIsGapNotLegacyEmptyCustody() = runBlocking {
        withDocumentaryJournal { host ->
            val source = inputs.source(inputs.document("PRIMARY-A"))
            val request = source.beginRequest()
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            assertEquals(JournalWrite.DURABLE, host.journal.stage(inputs.staged(source, request)))
            assertEquals(JournalWrite.DURABLE, host.journal.markDispatched(request.operationId))
            val retained = StoredKernelResultV1.from(controlledRecorded(source, request))
            assertEquals(JournalWrite.DURABLE, host.journal.remember(request.operationId, retained))
            assertEquals(JournalWrite.DURABLE, host.journal.acknowledge(request.operationId, retained))
            assertTrue(host.pending().isEmpty())
            host.removeDocumentarySources()
            val altered = host.encryptedManifest()
            assertIs<JvmClientJournalOpenResult.Unavailable>(host.openExistingAs())
            assertContentEquals(altered, host.encryptedManifest())
        }
    }

    @Test
    fun di2_025_missingArchivedOriginalStepIsExplicitGapAndIsNotRepaired() = runBlocking {
        withDocumentaryJournal { host ->
            val source = inputs.source(inputs.document("PRIMARY-A"))
            val request = source.beginRequest()
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            assertEquals(JournalWrite.DURABLE, host.journal.stage(inputs.staged(source, request)))
            val retained = StoredKernelResultV1.from(controlledRecorded(source, request))
            assertEquals(JournalWrite.DURABLE, host.journal.remember(request.operationId, retained))
            assertEquals(JournalWrite.DURABLE, host.journal.acknowledge(request.operationId, retained))
            val manifest = host.encryptedManifest()
            host.removeArchivedStep(request.operationId)
            assertIs<JvmClientJournalOpenResult.Unavailable>(host.openExistingAs())
            assertContentEquals(manifest, host.encryptedManifest())
        }
    }
}

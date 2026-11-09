package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.LocalPurchasingDocumentDownloadResult
import com.ukkera.pos.app.LocalPurchasingDocumentFileDestination
import com.ukkera.pos.app.LocalPurchasingDocumentFileDestinationSelection
import com.ukkera.pos.app.LocalPurchasingDocumentFileIoResult
import com.ukkera.pos.app.LocalPurchasingDocumentFileProblem
import com.ukkera.pos.app.LocalPurchasingDocumentFileSelection
import com.ukkera.pos.app.LocalPurchasingDocumentFiles
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.SecurityAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Controlled protected replies and output seams; not native chooser/output, Master or RPC acceptance. */
class DocumentaryClientFrozenDownloadSlicesTest {
    private val inputs = FrozenDocumentaryClientInputsV2

    @Test
    fun di2_001_011_026_validReceiverReconstructsExactPrimaryAndSmallChildBeforeControlledOutputClose() = runBlocking {
        val documents = listOf("PRIMARY-A" to PurchasingEvidencePurposeV1.SUPPLIER_INVOICE,
            "SMALL-CHILD" to PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY)
        documents.forEach { (name, purpose) ->
            withDocumentaryJournal { host ->
                val document = inputs.document(name)
                val source = inputs.source(document, purpose = purpose)
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
                val port = ControlledDocumentaryClientPort(source)
                val reads = ControlledDocumentaryReadReplies(document)
                reads.projection = inputs.fileProjection(document, purpose)
                port.readReply = { request -> reads.reply(request).also { reply ->
                    if (reply is ReadReply.Data) assertTrue(reply.payload.size <= 32_768)
                } }
                val files = ControlledDocumentaryDownloadFiles()
                assertEquals(LocalPurchasingDocumentDownloadResult.Saved(document.id),
                    port.gateway(host).downloadPurchasingDocument(document.id, files) { true })
                val output = assertNotNull(files.destination.output)
                assertContentEquals(document.file.bytes, output)
                assertEquals(document.file.byteLength, output.size)
                assertEquals(document.file.digest.value, inputs.hash(output))
                assertEquals(source.begin.originalFileName to document.file.media, files.chosenMetadata)
                assertEquals(listOf("write", "close"), files.destination.calls)
                assertTrue(files.destination.closed)
                assertTrue(reads.queries.all { it.forExport })
                assertEquals(document.file.slots.indices.toSet(), reads.queries.map { it.chunkIndex }.toSet())
                assertTrue(port.executions.isEmpty())
                assertTrue(port.lookups.isEmpty())
                assertTrue(host.pending().isEmpty())
                assertEquals(source, host.source(document.id))
            }
        }
    }

    @Test
    fun di2_026_corruptShortReorderedDuplicateOrMissingSlotsNeverProduceVerifiedOutput() = runBlocking {
        val document = inputs.document("PRIMARY-A")
        val source = inputs.source(document)
        listOf("corrupt", "short", "reordered", "duplicate", "missing").forEach { variant ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
                val port = ControlledDocumentaryClientPort(source)
                val reads = ControlledDocumentaryReadReplies(document)
                reads.slot = { index -> when {
                    variant == "corrupt" && index == 0 -> reads.originalSlot(0).copy(chunkHex = inputs.hex(
                        document.file.slot(0).also { it[0] = (it[0].toInt() xor 1).toByte() }))
                    variant == "short" && index == 0 -> reads.originalSlot(0).copy(chunkByteLength = 8191,
                        chunkDigest = PayloadDigest(inputs.fixture.getValue("negativeInputs").jsonObject
                            .getValue("SHORT-CHUNK-0").jsonObject.getValue("submittedSha256").jsonPrimitive.content),
                        chunkHex = inputs.hex(document.file.slot(0).copyOf(8191)))
                    variant == "reordered" && index == 0 -> reads.originalSlot(1)
                    variant == "duplicate" && index == 1 -> reads.originalSlot(0).copy(chunkIndex = 1)
                    variant == "missing" && index == 1 -> null
                    else -> reads.originalSlot(index)
                } }
                port.readReply = reads::reply
                val files = ControlledDocumentaryDownloadFiles()
                assertIs<LocalPurchasingDocumentDownloadResult.Unavailable>(
                    port.gateway(host).downloadPurchasingDocument(document.id, files) { true }, variant)
                assertEquals(null, files.destination.output, variant)
                assertFalse(files.destination.closed, variant)
                assertFalse("write" in files.destination.calls, variant)
                assertTrue("abort" in files.destination.calls, variant)
                assertTrue(port.executions.isEmpty())
                assertEquals(source, host.source(document.id))
                assertContentEquals(document.file.bytes, host.source(document.id).copyOriginalBytes())
            }
        }
    }

    @Test
    fun di2_026_falseFullDigestCannotPassEvenWhenEverySlotReplyRepeatsTheSameFalseMetadata() = runBlocking {
        withDocumentaryJournal { host ->
            val document = inputs.document("PRIMARY-A")
            val source = inputs.source(document)
            assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
            val port = ControlledDocumentaryClientPort(source)
            val reads = ControlledDocumentaryReadReplies(document)
            reads.projection = reads.projection.copy(exactBytesDigest = inputs.wrongFullDigest)
            port.readReply = reads::reply
            val files = ControlledDocumentaryDownloadFiles()
            assertIs<LocalPurchasingDocumentDownloadResult.Unavailable>(
                port.gateway(host).downloadPurchasingDocument(document.id, files) { true })
            assertEquals(document.file.slots.indices.toSet(), reads.queries.map { it.chunkIndex }.toSet())
            assertEquals(null, files.destination.output)
            assertFalse("write" in files.destination.calls)
            assertFalse(files.destination.closed)
            assertTrue(port.executions.isEmpty())
            assertEquals(source, host.source(document.id))
        }
    }

    @Test
    fun di2_017_026_readOnlyExportOnlyQuantityAndOtherPurchasingHintsCannotExportOriginalBytes() = runBlocking {
        val document = inputs.document("PRIMARY-A")
        val source = inputs.source(document)
        val profiles = listOf(setOf(inputs.read), setOf(inputs.export),
            setOf(Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM)),
            setOf(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
                Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
                Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT)))
        profiles.forEach { grants ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
                val port = ControlledDocumentaryClientPort(source)
                port.view = inputs.view(grants = grants)
                val reads = ControlledDocumentaryReadReplies(document)
                port.readReply = reads::reply
                val files = ControlledDocumentaryDownloadFiles()
                assertIs<LocalPurchasingDocumentDownloadResult.Unavailable>(
                    port.gateway(host).downloadPurchasingDocument(document.id, files) { true })
                assertEquals(0, files.chooserCalls)
                assertTrue(files.destination.calls.isEmpty())
                assertTrue(port.reads.isEmpty())
                assertTrue(port.executions.isEmpty())
                assertEquals(source, host.source(document.id))
            }
        }
    }

    @Test
    fun di2_019_021_026_cancelledChooserOrChangedLifetimeActorAndProtectedReadCannotPublishOutput() = runBlocking {
        val document = inputs.document("PRIMARY-A")
        val source = inputs.source(document)
        listOf("cancel", "lifetime", "actor", "protected-denial").forEach { variant ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
                val port = ControlledDocumentaryClientPort(source)
                val reads = ControlledDocumentaryReadReplies(document)
                port.readReply = reads::reply
                var current = true
                val files = ControlledDocumentaryDownloadFiles()
                files.onChoose = {
                    when (variant) {
                        "cancel" -> LocalPurchasingDocumentFileDestinationSelection.Cancelled
                        else -> {
                            when (variant) {
                                "lifetime" -> current = false
                                "actor" -> port.view = inputs.view(inputs.otherOwner)
                                "protected-denial" -> reads.denied = true
                            }
                            LocalPurchasingDocumentFileDestinationSelection.Chosen(files.destination)
                        }
                    }
                }
                val result = port.gateway(host).downloadPurchasingDocument(document.id, files) { current }
                if (variant == "cancel") assertEquals(LocalPurchasingDocumentDownloadResult.Cancelled(document.id), result)
                else assertIs<LocalPurchasingDocumentDownloadResult.Unavailable>(result, variant)
                assertEquals(1, files.chooserCalls)
                assertFalse("write" in files.destination.calls, variant)
                assertFalse(files.destination.closed, variant)
                assertEquals(null, files.destination.output, variant)
                assertTrue(port.executions.isEmpty())
                assertEquals(source, host.source(document.id))
            }
        }
    }

    @Test
    fun di2_019_026_protectedDenialDuringSlotsOrAfterReconstructionWinsOverCachedHints() = runBlocking {
        val document = inputs.document("PRIMARY-A")
        val source = inputs.source(document)
        listOf("during-slots", "after-reconstruction").forEach { schedule ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
                val port = ControlledDocumentaryClientPort(source)
                val reads = ControlledDocumentaryReadReplies(document)
                var triggered = false
                reads.beforeRead = { request ->
                    val cut = if (schedule == "during-slots") request.query == LocalPurchasingDocumentProfileV1.BYTES && reads.queries.size == 1
                        else request.query == LocalPurchasingDocumentProfileV1.READ && reads.queries.size == document.file.slots.size
                    if (cut) { triggered = true; reads.denied = true }
                }
                port.readReply = reads::reply
                val files = ControlledDocumentaryDownloadFiles()
                assertIs<LocalPurchasingDocumentDownloadResult.Unavailable>(
                    port.gateway(host).downloadPurchasingDocument(document.id, files) { true }, schedule)
                assertTrue(triggered, schedule)
                assertFalse("write" in files.destination.calls, schedule)
                assertFalse(files.destination.closed, schedule)
                assertEquals(null, files.destination.output, schedule)
                assertTrue(port.executions.isEmpty())
                assertEquals(source, host.source(document.id))
            }
        }
    }

    @Test
    fun di2_026_failedControlledWriteOrCloseIsNeverReportedAsVerifiedSavedOriginal() = runBlocking {
        val document = inputs.document("PRIMARY-A")
        val source = inputs.source(document)
        listOf("write", "close").forEach { failingCall ->
            withDocumentaryJournal { host ->
                assertEquals(JournalWrite.DURABLE, host.journal.saveDocumentarySource(source))
                val port = ControlledDocumentaryClientPort(source)
                port.readReply = ControlledDocumentaryReadReplies(document)::reply
                val files = ControlledDocumentaryDownloadFiles()
                if (failingCall == "write") files.destination.writeReply = LocalPurchasingDocumentFileIoResult.Unavailable(
                    LocalPurchasingDocumentFileProblem.IO_FAILED)
                else files.destination.closeReply = LocalPurchasingDocumentFileIoResult.Unavailable(LocalPurchasingDocumentFileProblem.CLOSE_FAILED)
                assertIs<LocalPurchasingDocumentDownloadResult.Unavailable>(
                    port.gateway(host).downloadPurchasingDocument(document.id, files) { true }, failingCall)
                assertTrue("abort" in files.destination.calls)
                assertFalse(files.destination.closed)
                assertEquals(null, files.destination.output)
                assertTrue(port.executions.isEmpty())
                assertEquals(source, host.source(document.id))
            }
        }
    }
}

/** Explicitly controlled in-memory OUTPUT seam, not a native chooser or a successful file delivery. */
private class ControlledDocumentaryDownloadFiles : LocalPurchasingDocumentFiles {
    override val bound = true
    val destination = ControlledDocumentaryDownloadDestination()
    var chooserCalls = 0
    var chosenMetadata: Pair<String, PurchasingEvidenceMediaV1>? = null
    var onChoose: suspend () -> LocalPurchasingDocumentFileDestinationSelection = {
        LocalPurchasingDocumentFileDestinationSelection.Chosen(destination)
    }

    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection =
        error("The download receiver must not import or substitute an external source")

    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection {
        chooserCalls++
        chosenMetadata = originalFileName to media
        if (!isCurrent()) return LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
        return onChoose()
    }
}

private class ControlledDocumentaryDownloadDestination : LocalPurchasingDocumentFileDestination {
    val calls = mutableListOf<String>()
    var output: ByteArray? = null
        private set
    var closed = false
        private set
    var writeReply: LocalPurchasingDocumentFileIoResult = LocalPurchasingDocumentFileIoResult.Succeeded
    var closeReply: LocalPurchasingDocumentFileIoResult = LocalPurchasingDocumentFileIoResult.Succeeded

    override suspend fun write(bytes: ByteArray, isCurrent: () -> Boolean): LocalPurchasingDocumentFileIoResult {
        calls += "write"
        if (!isCurrent()) return LocalPurchasingDocumentFileIoResult.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
        if (writeReply == LocalPurchasingDocumentFileIoResult.Succeeded) output = bytes.copyOf()
        return writeReply
    }

    override suspend fun close(): LocalPurchasingDocumentFileIoResult {
        calls += "close"
        if (closeReply == LocalPurchasingDocumentFileIoResult.Succeeded) closed = true
        return closeReply
    }

    override suspend fun abort(): LocalPurchasingDocumentFileIoResult {
        calls += "abort"
        output?.fill(0)
        output = null
        return LocalPurchasingDocumentFileIoResult.Succeeded
    }
}

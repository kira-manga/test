package com.ukkera.pos.app.gateway.independent.reportcompletev2

import com.ukkera.pos.app.LocalBranchReportAuditStage
import com.ukkera.pos.app.LocalBranchReportExportCoverage
import com.ukkera.pos.app.LocalBranchReportExportResult
import com.ukkera.pos.app.LocalBranchReportFilePhase
import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportExportCoverageV1
import com.ukkera.pos.sales.store.LocalBranchReportExportDestinationV1
import com.ukkera.pos.sales.store.LocalBranchReportExportOutcomeV1
import com.ukkera.pos.sales.store.LocalBranchReportExportRequestV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen common workflow slices with controlled master/native primitives. Actual generated
 * originals are compared only within each run. No fixed-I32, production grant/RPC/persistence,
 * durable native receipt, real restart or platform-acceptance proof is claimed by this suite.
 */
class LocalBranchReportFrozenWorkflowSlicesTest {
    @Test
    fun RE32_S202_completeF12UsesThreeDataReadsThenC1SpoolC2AndOriginalFinish() = runTest {
        withHarness(FrozenReportWorkflowInputs.f12Reads()) { h ->
            h.master.beforeRead = { read ->
                if (read.stage == "C1") {
                    assertEquals(0, h.files.spoolWrites)
                    assertNull(h.files.spooledBytes)
                }
                if (read.stage == "C2") {
                    assertEquals(1, h.files.spoolWrites)
                    assertContentEquals(FrozenReportWorkflowInputs.f12BytesCopy(), h.files.spooledBytes)
                }
                assertEquals(0, h.files.admittedLinks, "No publication before current C2 and the native gate")
            }
            val result = assertIs<LocalBranchReportExportResult.Outcome>(h.run())
            assertTrue(result.record.verifiedLocally)
            assertTrue(result.record.auditAcknowledged)
            assertEquals(completeEvents(listOf("D1", "D2", "D3")), h.events)
            assertEquals(listOf("D1", "D2", "D3", "C1", "C2"), h.master.readStages)
            assertCompleteOriginals(h, FrozenReportWorkflowInputs.F12_BYTE_COUNT)
            assertEquals(1, h.files.admittedLinks)
            val bytes = h.files.publishedBytes.single()
            assertContentEquals(FrozenReportWorkflowInputs.f12BytesCopy(), bytes)
            assertEquals(FrozenReportWorkflowInputs.F12_REFERENCE_SHA256, sha256(bytes).value)
        }
    }

    @Test
    fun RE32_S203_emptySectionUsesOneDataReadAndBothRootConfirmations() = runTest {
        withHarness(FrozenReportWorkflowInputs.f0Reads()) { h ->
            val result = assertIs<LocalBranchReportExportResult.Outcome>(h.run())
            assertTrue(result.record.verifiedLocally)
            assertTrue(result.record.auditAcknowledged)
            assertEquals(completeEvents(listOf("D1")), h.events)
            assertEquals(listOf("D1", "C1", "C2"), h.master.readStages)
            assertCompleteOriginals(h, FrozenReportWorkflowInputs.F0_BYTE_COUNT)
            val bytes = h.files.publishedBytes.single()
            assertContentEquals(FrozenReportWorkflowInputs.f0BytesCopy(), bytes)
            assertEquals(FrozenReportWorkflowInputs.F0_REFERENCE_SHA256, sha256(bytes).value)
            assertEquals(3, quotedCsvRecords(bytes).size, "Header, empty source metadata, completion; no business row")
        }
    }

    @Test
    fun RE32_001_omittedCoverageRechecksTheSelectedPageAfterSpoolWithoutRootTraversal() = runTest {
        withHarness(FrozenReportWorkflowInputs.pageReads()) { h ->
            h.master.beforeRead = { read ->
                if (read.stage == "PAGE_RECHECK") {
                    assertEquals(1, h.files.spoolWrites)
                    assertEquals(0, h.files.admittedLinks)
                }
            }
            val result = assertIs<LocalBranchReportExportResult.Outcome>(h.run(FrozenReportWorkflowInputs.pageSelection))
            assertTrue(result.record.verifiedLocally)
            assertEquals(listOf("PAGE", "PAGE_RECHECK"), h.master.readStages)
            assertEquals(listOf(FrozenReportWorkflowInputs.query(FrozenReportWorkflowInputs.key(6)),
                FrozenReportWorkflowInputs.query(FrozenReportWorkflowInputs.key(6))), h.master.readQueries)
            assertOriginalsRetained(h)
            val receipt = assertNotNull(h.files.latest)
            assertNull(receipt.query.coverage)
            assertEquals(FrozenReportWorkflowInputs.key(6), receipt.query.after)
            assertEquals(FrozenReportWorkflowInputs.key(11), assertNotNull(receipt.source).nextAfter)
            assertEquals(FrozenReportWorkflowInputs.baseObservation, assertNotNull(receipt.source).observedAt)
            val begin = assertIs<LocalBranchReportExportRequestV1.Begin>(h.master.commands.first().body)
            assertNull(begin.coverage)
            val receiptQuery = Json.parseToJsonElement(receiptWireText(receipt)).jsonObject.getValue("query").jsonObject
            assertFalse("coverage" in receiptQuery, "Null legacy coverage is absent from the receipt wire")
            val beginBytes = h.master.commands.first().request.payload.copyBytes()
            try {
                assertFalse("coverage" in Json.parseToJsonElement(beginBytes.decodeToString()).jsonObject,
                    "Null legacy coverage is absent from the original Begin wire")
            } finally { beginBytes.fill(0) }
            val records = quotedCsvRecords(h.files.publishedBytes.single())
            assertEquals(FrozenReportWorkflowInputs.header, records.first())
            assertEquals(6, records.size)
            assertEquals((7..11).map { FrozenReportWorkflowInputs.key(it).value }, records.drop(1).map { it[6] })
            assertEquals((7..11).map { (it * 100).toString() }, records.drop(1).map { it[15] })
            assertTrue(records.drop(1).all { it[3] == FrozenReportWorkflowInputs.baseObservation.canonicalString() &&
                it[4] == FrozenReportWorkflowInputs.key(6).value && it[5] == FrozenReportWorkflowInputs.key(11).value })
            assertFalse(records.any { it[0] == "complete-current-section/v2" })
            // Semantic PAGE checks only. The missing independent legacy byte goldens are not substituted.
        }
    }

    @Test
    fun RE32_S204_eachRootConfirmationRefusesChangedRevisionContentOrCurrentDenial() = runTest {
        for (stage in listOf("C1", "C2")) for (fault in listOf("revision", "amount", "denied")) {
            val reads = FrozenReportWorkflowInputs.f12Reads().map { read ->
                if (read.stage != stage) read else when (fault) {
                    "revision" -> read.copy(revision = FrozenReportWorkflowInputs.changedRevision)
                    "amount" -> read.copy(page = read.page.copy(rows = read.page.rows.mapIndexed { index, row ->
                        if (index != 0) row else row.copy(amounts = listOf(LocalBranchReportAmountV1(
                            row.amounts.single().metric, Money(row.amounts.single().amount.currency, 101))))
                    }))
                    else -> read
                }
            }
            withHarness(reads) { h ->
                if (fault == "denied") h.master.deniedReadStages += stage
                assertNoPublication(h, h.run())
                assertEquals(if (stage == "C1") listOf("D1", "D2", "D3", "C1")
                    else listOf("D1", "D2", "D3", "C1", "C2"), h.master.readStages, "$stage/$fault")
                assertEquals(if (stage == "C2") 1 else 0, h.files.spoolWrites)
                if (stage == "C2") assertContentEquals(FrozenReportWorkflowInputs.f12BytesCopy(), h.files.spooledBytes)
                if (fault == "denied") assertFalse(stage in h.master.protectedReplies, "A refused current read supplies no page")
                assertNoFreshFinish(h)
                assertOriginalsRetained(h)
            }
        }
    }

    @Test
    fun RE32_006_pagePostSpoolRecheckRefusesChangedCutOrCurrentReadDenial() = runTest {
        for (fault in listOf("revision", "denied")) {
            val reads = FrozenReportWorkflowInputs.pageReads().map { read ->
                if (read.stage == "PAGE_RECHECK" && fault == "revision") read.copy(revision = FrozenReportWorkflowInputs.changedRevision)
                else read
            }
            withHarness(reads) { h ->
                if (fault == "denied") h.master.deniedReadStages += "PAGE_RECHECK"
                assertNoPublication(h, h.run(FrozenReportWorkflowInputs.pageSelection))
                assertEquals(listOf("PAGE", "PAGE_RECHECK"), h.master.readStages)
                assertTrue(h.master.readQueries.all { it == FrozenReportWorkflowInputs.query(FrozenReportWorkflowInputs.key(6)) })
                assertEquals(1, h.files.spoolWrites)
                assertNoFreshFinish(h)
                assertOriginalsRetained(h)
            }
        }
    }

    @Test
    fun RE32_006_endedInteractiveOrCredentialLifetimeCannotPublishThePrivateSpool() = runTest {
        for (barrier in listOf("interactive-after-spool", "lease-after-C2")) {
            withHarness(FrozenReportWorkflowInputs.f12Reads()) { h ->
                if (barrier == "interactive-after-spool") h.files.onNativeEvent = { event ->
                    if (event == "native:phase:SPOOLED") h.interactive.end()
                } else h.master.afterRead = { read -> if (read.stage == "C2") h.leaseCurrent = false }
                assertNoPublication(h, h.run())
                assertEquals(if (barrier == "interactive-after-spool") listOf("D1", "D2", "D3", "C1")
                    else listOf("D1", "D2", "D3", "C1", "C2"), h.master.readStages)
                assertEquals(1, h.files.spoolWrites)
                assertContentEquals(FrozenReportWorkflowInputs.f12BytesCopy(), h.files.spooledBytes)
                assertNoFreshFinish(h)
                assertOriginalsRetained(h)
            }
        }
    }

    @Test
    fun RE32_010_nativeCurrentOwnerGateRefusalRetainsOriginalCancellationFinish() = runTest {
        withHarness(FrozenReportWorkflowInputs.f12Reads()) { h ->
            h.files.beforeCommitStart = { h.files.hostAlive = false }
            val result = assertIs<LocalBranchReportExportResult.Outcome>(h.run())
            assertNoPublication(h, result)
            assertEquals(1, h.files.linkAttempts, "Controlled primitive invoked; its commit-start refuses before link")
            assertEquals(LocalBranchReportFilePhase.CANCELLED, assertNotNull(h.files.latest).phase)
            val finish = assertIs<LocalBranchReportExportRequestV1.Finish>(h.master.commands.last().body)
            assertEquals(LocalBranchReportExportOutcomeV1.CANCELLED_BEFORE_PUBLICATION, finish.outcome)
            assertEquals(LocalBranchReportAuditStage.CANCELLED_BEFORE_PUBLICATION,
                assertNotNull(assertNotNull(h.files.latest).finish?.acknowledgement).stage)
            assertOriginalsRetained(h)
        }
    }

    @Test
    fun RE32_010_chooserCancellationOrUnacknowledgedBeginDispatchesNoData() = runTest {
        for (barrier in listOf("chooser", "Begin-denied")) {
            withHarness(FrozenReportWorkflowInputs.f12Reads()) { h ->
                if (barrier == "chooser") h.files.chooserCancelled = true else h.master.denyBegin = true
                val result = h.run()
                assertEquals(0, h.master.readQueries.size)
                assertEquals(0, h.files.spoolWrites)
                assertEquals(0, h.files.admittedLinks)
                if (barrier == "chooser") {
                    assertEquals(LocalBranchReportExportResult.ChooserCancelled, result)
                    assertNull(h.files.initial)
                    assertEquals(0, h.master.commands.size)
                } else {
                    assertNoPublication(h, result)
                    assertNull(assertNotNull(h.files.latest).begin?.acknowledgement)
                    assertNoFreshFinish(h)
                    assertOriginalsRetained(h)
                }
            }
        }
    }

    @Test
    fun RE32_009_deniedFinishDoesNotEraseOrRepublishTheControlledKnownSave() = runTest {
        withHarness(FrozenReportWorkflowInputs.f12Reads()) { h ->
            h.master.denyFinish = true
            val result = assertIs<LocalBranchReportExportResult.Outcome>(h.run())
            assertTrue(result.record.verifiedLocally)
            assertFalse(result.record.auditAcknowledged)
            assertEquals(LocalBranchReportFilePhase.PUBLISHED, result.record.receipt.phase)
            assertEquals(1, h.files.admittedLinks)
            assertEquals(2, h.master.commands.size, "No replacement Begin/Finish or automatic retry")
            assertEquals(listOf("D1", "D2", "D3", "C1", "C2"), h.master.readStages)
            assertContentEquals(FrozenReportWorkflowInputs.f12BytesCopy(), h.files.publishedBytes.single())
            assertNotNull(result.record.receipt.finish)
            assertNull(result.record.receipt.finish?.acknowledgement)
            assertOriginalsRetained(h)
        }
    }

    private suspend fun withHarness(reads: List<FrozenWorkflowRead>, body: suspend (ControlledReportWorkflowHarness) -> Unit) {
        val h = ControlledReportWorkflowHarness(reads)
        try { body(h) } finally { h.destroy() }
    }

    private fun assertNoPublication(h: ControlledReportWorkflowHarness, result: LocalBranchReportExportResult) {
        assertEquals(0, h.files.admittedLinks)
        assertEquals(0, h.files.publishedBytes.size)
        assertTrue(result is LocalBranchReportExportResult.Outcome || result is LocalBranchReportExportResult.Unavailable)
        if (result is LocalBranchReportExportResult.Outcome) {
            assertFalse(result.record.verifiedLocally)
            assertFalse(result.record.auditAcknowledged)
        }
    }

    private fun assertNoFreshFinish(h: ControlledReportWorkflowHarness) {
        assertEquals(1, h.master.commands.size)
        assertIs<LocalBranchReportExportRequestV1.Begin>(h.master.commands.single().body)
        assertNull(assertNotNull(h.files.latest).finish)
        assertNull(assertNotNull(h.files.latest).recovery)
    }

    private fun assertOriginalsRetained(h: ControlledReportWorkflowHarness) {
        val initial = assertNotNull(h.files.initial)
        val retained = assertNotNull(h.files.latest)
        assertEquals(initial.exportId, retained.exportId)
        assertEquals(initial.receiptId, retained.receiptId)
        assertEquals(initial.owner, retained.owner)
        assertEquals(FrozenReportWorkflowInputs.actorId, retained.owner.actorId)
        assertEquals(initial.query, retained.query)
        assertEquals(initial.destinationKind, retained.destinationKind)
        assertEquals(initial.beginOperationId, retained.beginOperationId)
        assertEquals(initial.finishOperationId, retained.finishOperationId)
        assertEquals(initial.recoveryOperationId, retained.recoveryOperationId)
        assertNull(retained.recovery)
        assertEquals(h.master.commands.size, h.master.commands.map { it.request.operationId }.distinct().size)
        for (sent in h.master.commands) {
            val command = if (sent.body is LocalBranchReportExportRequestV1.Begin) retained.begin else retained.finish
            val retainedCommand = assertNotNull(command)
            assertEquals(sent.stagedOriginal, retainedCommand.copy(acknowledgement = null),
                "Original operation/body/digest equal the volatile pre-dispatch capture; not durable-restart evidence")
            assertEquals(sent.request.payload.observedHex(), retainedCommand.canonicalBodyHex)
        }
    }

    private fun assertCompleteOriginals(h: ControlledReportWorkflowHarness, byteCount: Int) {
        assertOriginalsRetained(h)
        val receipt = assertNotNull(h.files.latest)
        assertEquals(LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2, receipt.query.coverage)
        assertNull(receipt.query.after)
        val begin = assertIs<LocalBranchReportExportRequestV1.Begin>(h.master.commands.first().body)
        assertEquals(FrozenReportWorkflowInputs.query(), begin.query)
        assertEquals(LocalBranchReportExportCoverageV1.COMPLETE_CURRENT_SECTION_V2, begin.coverage)
        assertEquals(LocalBranchReportExportDestinationV1.DESKTOP_LOCAL_FILE, begin.destination)
        assertEquals(Revision(0), assertNotNull(receipt.begin?.acknowledgement).auditRevision)
        assertEquals(Revision(1), assertNotNull(receipt.finish?.acknowledgement).auditRevision)
        val source = assertNotNull(receipt.source)
        assertEquals(FrozenReportWorkflowInputs.sourceRevision, source.branchRevision)
        assertEquals(FrozenReportWorkflowInputs.c1Time, source.observedAt)
        assertEquals(byteCount, source.byteCount)
        assertNull(source.nextAfter)
        val finish = assertIs<LocalBranchReportExportRequestV1.Finish>(h.master.commands.last().body)
        assertEquals(LocalBranchReportExportOutcomeV1.CLIENT_ATTESTED_PUBLISHED, finish.outcome)
        val file = assertNotNull(finish.file)
        assertEquals(source.branchRevision, file.sourceBranchRevision)
        assertEquals(FrozenReportWorkflowInputs.c1Time, file.sourceObservedAt)
        assertEquals(byteCount, file.byteCount)
        assertEquals(source.contentSha256, file.contentDigest.value, "Retained metadata must not change in Finish")
    }

    private fun completeEvents(dataStages: List<String>): List<String> = listOf("native:chooser", "native:selected",
        "native:staged:BEGIN", "master:command:BEGIN", "native:acknowledged:BEGIN:REQUESTED") +
        dataStages.map { "master:read:$it" } + listOf("master:read:C1", "native:spool-bytes", "native:phase:SPOOLED",
            "master:read:C2", "native:phase:PUBLICATION_ATTEMPTED", "native:link-attempt", "native:phase:PUBLISHED",
            "native:staged:FINISH", "master:command:FINISH", "native:acknowledged:FINISH:CLIENT_ATTESTED_PUBLISHED")

    /** Observation parser only: all expected values/bytes above come from the frozen public fixtures. */
    private fun quotedCsvRecords(bytes: ByteArray): List<List<String>> {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        assertFalse(text.startsWith('\uFEFF'))
        var cursor = 0
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        while (cursor < text.length) {
            assertEquals('"', text[cursor++], "Every field must be quoted")
            val field = StringBuilder()
            var closed = false
            while (cursor < text.length) {
                val character = text[cursor++]
                if (character != '"') field.append(character)
                else if (cursor < text.length && text[cursor] == '"') { field.append('"'); cursor += 1 }
                else { closed = true; break }
            }
            assertTrue(closed)
            record += field.toString()
            when {
                cursor < text.length && text[cursor] == ',' -> cursor += 1
                cursor + 1 < text.length && text[cursor] == '\r' && text[cursor + 1] == '\n' -> {
                    cursor += 2
                    assertEquals(FrozenReportWorkflowInputs.header.size, record.size,
                        "Every CSV record must have exactly the frozen 16 columns")
                    records += record
                    record = mutableListOf()
                }
                else -> throw AssertionError("CSV records must use quoted fields and CRLF")
            }
        }
        assertTrue(record.isEmpty())
        return records
    }
}

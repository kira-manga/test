package com.ukkera.pos.app.gateway.independent.historicaltenderreportv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.independent.reportcompletev2.ControlledReportWorkflowHarness
import com.ukkera.pos.app.gateway.independent.reportcompletev2.FrozenWorkflowRead
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportExportRequestV1
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Frozen selected-sale fresh EXPORT SUPPORT through the actual common workflow and receipt
 * state machine. Reused master/native primitives are controlled and volatile, not genuine
 * report/export grants, admitted RPC, native durability or original export recovery. */
class HistoricalTenderReportExportSupportingTest {
    private val f = HistoricalTenderGatewayInputs
    private val header = listOf("profile", "section", "branch_revision", "observed_at_utc", "selected_sale_id",
        "document_id", "document_revision", "related_order_id", "cash_session_id", "original_close_operation_id",
        "original_collector_id", "row_kind", "metric", "payment_attempt_id", "tender_kind", "tender_id",
        "tender_policy_revision", "currency", "minor_units", "latest_correction_id", "correction_count",
        "latest_correction_recorded_at_utc")

    @Test
    fun supportFreshSelectedCashExportReadsAfterCutRatherThanCapturedBeforeCutOrAnotherSale() = runTest {
        val before = f.before(f.cash10)
        HistoricalTenderMapperFixture(before).use { view ->
            val captured = assertNotNull(view.read().branchReport)
            assertEquals(before.branchRevision, captured.branchRevision)
            assertEquals(0L, captured.documents.single().retailTenderBreakdown!!.payments.single().correctionCount)
            withHarness(f.exportReads(f.cash10)) { h ->
                h.master.beforeRead = { read ->
                    assertEquals(0, h.files.admittedLinks)
                    if (read.stage == "SELECTED_RECHECK") assertEquals(1, h.files.spoolWrites)
                }
                val outcome = assertIs<LocalBranchReportExportResult.Outcome>(h.run(f.selection(f.cash10)))
                assertTrue(outcome.record.verifiedLocally && outcome.record.auditAcknowledged)
                assertOriginalSelectedQuery(h, f.cash10)
                assertEquals(listOf("SELECTED", "SELECTED_RECHECK"), h.master.readStages)
                assertCsv(h.files.publishedBytes.single(), f.cash10)
                assertEquals(f.cash10.branchRevision, outcome.record.receipt.source!!.branchRevision)
                assertEquals(f.cash10.observedAt, outcome.record.receipt.source!!.observedAt)
                assertFalse(f.split9999.row.id.value in h.files.publishedBytes.single().decodeToString())
            }
            assertEquals(before.branchRevision, captured.branchRevision)
            assertEquals(0L, captured.documents.single().retailTenderBreakdown!!.payments.single().correctionCount)
            view.assertOnlySelectedReportReads()
        }
        // Separate controlled fixtures exercise VIEW and fresh EXPORT bindings. They do not
        // claim a real authenticated same-client correction/cache/notification schedule.
    }

    @Test
    fun supportExplicitFreshSplitUncorrectedAndZeroExportsRetainOnlyTheirSelectedMembership() = runTest {
        for (source in listOf(f.split9999, f.cash4600, f.zero)) withHarness(f.exportReads(source)) { h ->
            val result = assertIs<LocalBranchReportExportResult.Outcome>(h.run(f.selection(source)))
            assertTrue(result.record.verifiedLocally && result.record.auditAcknowledged)
            assertOriginalSelectedQuery(h, source)
            assertEquals(1, h.files.spoolWrites)
            assertEquals(1, h.files.admittedLinks)
            assertEquals(listOf("SELECTED", "SELECTED_RECHECK"), h.master.readStages)
            assertCsv(h.files.publishedBytes.single(), source)
        }
    }

    @Test
    fun supportWrongSelectedResponseAndDamagedPaymentMembershipCannotSpoolOrPublish() = runTest {
        val selected = f.exportReads(f.cash10).first()
        val wrongSource = selected.copy(page = f.page(f.split9999, LocalBranchReportDeliveryV1.EXPORT))
        withHarness(listOf(wrongSource)) { h ->
            val result = h.run(f.selection(f.cash10))
            assertEquals(LocalServiceProblem.UNAVAILABLE, noPublication(h, result))
            assertEquals(0, h.files.spoolWrites)
            assertOriginalSelectedQuery(h, f.cash10)
            assertEquals(1, h.master.readQueries.size)
        }
        val split = f.exportReads(f.split9999).first()
        val damaged = split.copy(page = f.page(f.split9999, LocalBranchReportDeliveryV1.EXPORT, f.damagedPaymentSum(f.split9999)))
        withHarness(listOf(damaged)) { h ->
            assertEquals(LocalServiceProblem.UNAVAILABLE, noPublication(h, h.run(f.selection(f.split9999))))
            assertEquals(0, h.files.spoolWrites)
            assertOriginalSelectedQuery(h, f.split9999)
        }
    }

    @Test
    fun supportExportDenialAtBeginOrEitherCurrentDataReadCannotPublishEitherBasis() = runTest {
        for (barrier in listOf("BEGIN", "SELECTED", "SELECTED_RECHECK")) withHarness(f.exportReads(f.cash10)) { h ->
            if (barrier == "BEGIN") h.master.denyBegin = true else h.master.deniedReadStages += barrier
            assertEquals(LocalServiceProblem.DENIED, noPublication(h, h.run(f.selection(f.cash10))))
            assertEquals(if (barrier == "SELECTED_RECHECK") 1 else 0, h.files.spoolWrites)
            assertEquals(when (barrier) { "BEGIN" -> 0; "SELECTED" -> 1; else -> 2 }, h.master.readQueries.size)
            assertFalse(barrier in h.master.protectedReplies)
            assertOriginalSelectedQuery(h, f.cash10)
        }
        // Scripted current denials qualify dispatch/refusal only, not independent activated
        // report/export grant separation or real account/session/device revocation.
    }

    @Test
    fun supportPostSpoolChangedCutOrEndedInteractiveLeaseRetainsPrivateSpoolWithoutDisclosure() = runTest {
        for (fault in listOf("changed-revision", "changed-page", "interactive", "lease")) {
            val reads = f.exportReads(f.cash10).mapIndexed { index, read ->
                if (index != 1) read else when (fault) {
                    "changed-revision" -> read.copy(revision = Revision(f.cash10.branchRevision.value + 1))
                    "changed-page" -> read.copy(page = f.page(f.before(f.cash10), LocalBranchReportDeliveryV1.EXPORT))
                    else -> read
                }
            }
            withHarness(reads) { h ->
                if (fault == "interactive") h.files.onNativeEvent = { event ->
                    if (event == "native:phase:SPOOLED") h.interactive.end()
                }
                if (fault == "lease") h.master.afterRead = { read ->
                    if (read.stage == "SELECTED_RECHECK") h.leaseCurrent = false
                }
                val expected = if (fault.startsWith("changed")) LocalServiceProblem.REPORT_RESTART_REQUIRED
                    else LocalServiceProblem.SESSION_ENDED
                assertEquals(expected, noPublication(h, h.run(f.selection(f.cash10))))
                assertEquals(1, h.files.spoolWrites)
                assertCsv(assertNotNull(h.files.spooledBytes), f.cash10)
                assertEquals(if (fault == "interactive") 1 else 2, h.master.readQueries.size)
                assertOriginalSelectedQuery(h, f.cash10)
            }
        }
        // A scripted private spool and lifecycle gate are not genuine filesystem force,
        // concurrent master revocation/commit atomicity, restart or restore qualification.
    }

    private suspend fun withHarness(reads: List<FrozenWorkflowRead>, block: suspend (ControlledReportWorkflowHarness) -> Unit) {
        val harness = ControlledReportWorkflowHarness(reads)
        try { block(harness) } finally { harness.destroy() }
    }

    private fun noPublication(h: ControlledReportWorkflowHarness, result: LocalBranchReportExportResult): LocalServiceProblem? {
        assertEquals(0, h.files.admittedLinks)
        assertTrue(h.files.publishedBytes.isEmpty())
        assertEquals(1, h.master.commands.size)
        assertIs<LocalBranchReportExportRequestV1.Begin>(h.master.commands.single().body)
        assertNull(assertNotNull(h.files.latest).finish)
        assertNull(assertNotNull(h.files.latest).recovery)
        return when (result) {
            is LocalBranchReportExportResult.Outcome -> {
                assertFalse(result.record.verifiedLocally || result.record.auditAcknowledged)
                result.record.serviceProblem
            }
            is LocalBranchReportExportResult.Unavailable -> result.serviceProblem
            else -> error("Unexpected selected-export result")
        }
    }

    private fun assertOriginalSelectedQuery(h: ControlledReportWorkflowHarness, source: HistoricalTenderGatewayDocument) {
        val initial = assertNotNull(h.files.initial)
        val latest = assertNotNull(h.files.latest)
        assertEquals("RETAIL_TENDER_BREAKDOWNS", initial.query.section)
        assertEquals(LocalBranchReportFileProfile.RETAIL_TENDER_BREAKDOWN_V3, initial.query.profile)
        assertEquals(source.row.id, initial.query.selectedSaleId)
        assertNull(initial.query.after); assertNull(initial.query.coverage)
        assertEquals(initial.query, latest.query)
        assertEquals(initial.exportId, latest.exportId)
        assertEquals(initial.receiptId, latest.receiptId)
        assertEquals(initial.beginOperationId, latest.beginOperationId)
        val begin = assertIs<LocalBranchReportExportRequestV1.Begin>(h.master.commands.first().body)
        assertEquals(f.query(source, LocalBranchReportDeliveryV1.EXPORT), begin.query)
        assertNull(begin.coverage)
        assertEquals(initial.beginOperationId, h.master.commands.first().request.operationId)
        assertTrue(h.master.readQueries.all { it == begin.query })
    }

    private fun assertCsv(bytes: ByteArray, source: HistoricalTenderGatewayDocument) {
        val text = bytes.decodeToString()
        assertTrue(text.endsWith("\r\n"))
        // All bound IDs/amounts/classifications/times are synthetic safe scalars. This
        // structural parser is not a new frozen byte/order/quoting oracle.
        val fields = text.removeSuffix("\r\n").split("\r\n").map { line ->
            assertTrue(line.startsWith('"') && line.endsWith('"'))
            line.removePrefix("\"").removeSuffix("\"").split("\",\"").map { it.replace("\"\"", "\"") }
        }
        assertEquals(header, fields.first())
        assertTrue(fields.all { it.size == 22 })
        val rows = fields.drop(1).map { header.zip(it).toMap() }
        val detail = source.row.retailTenderBreakdown!!
        assertEquals(if (detail.payments.isEmpty()) 7 else 5 + detail.payments.size * 2, rows.size)
        for (row in rows) {
            assertEquals("selected-retail-paid-sale-tenders/v3", row.getValue("profile"))
            assertEquals(source.row.id.value, row.getValue("selected_sale_id"))
            assertEquals(source.row.id.value, row.getValue("document_id"))
            assertEquals(source.row.relatedDocumentId!!.value, row.getValue("related_order_id"))
            assertEquals(source.row.cashSessionId!!.value, row.getValue("cash_session_id"))
            assertEquals(detail.originalCloseOperationId.value, row.getValue("original_close_operation_id"))
            assertEquals(detail.originalCollectorId.value, row.getValue("original_collector_id"))
            assertEquals(source.branchRevision.value.toString(), row.getValue("branch_revision"))
            assertEquals(source.observedAt.canonicalString(), row.getValue("observed_at_utc"))
        }
        val unchanged = rows.filter { it.getValue("row_kind") == "UNCHANGED_ORIGINAL_DOCUMENT" }
        assertEquals(mapOf("PAID_DOCUMENT_TOTAL" to source.paidMinor, "CLOSING_EXPECTED" to source.closeMinor[0],
            "ACTUAL_COUNT" to source.closeMinor[1], "VARIANCE" to source.closeMinor[2], "PHYSICAL_CASH_DELTA" to 0L),
            unchanged.associate { it.getValue("metric") to it.getValue("minor_units").toLong() })
        for ((basis, expected) in listOf("ORIGINAL_RECORDED" to source.originalMinor,
            "RECONCILED_HISTORICAL" to source.reconciledMinor)) {
            val classified = rows.filter { it.getValue("row_kind") == basis }
            if (detail.payments.isEmpty()) {
                val marker = classified.single()
                assertEquals("EMPTY_PAYMENT_MEMBERSHIP", marker.getValue("metric"))
                for (field in listOf("payment_attempt_id", "tender_kind", "tender_id", "tender_policy_revision", "currency",
                    "minor_units", "latest_correction_id", "correction_count", "latest_correction_recorded_at_utc"))
                    assertEquals("", marker.getValue(field))
            } else {
                assertEquals(detail.payments.map { it.paymentAttemptId.value }.toSet(),
                    classified.map { it.getValue("payment_attempt_id") }.toSet())
                val actual = f.buckets(0, 0, 0).toMutableMap()
                for (row in classified) {
                    val kind = f.semantic(row.getValue("tender_kind"))
                    actual[kind] = actual.getValue(kind) + row.getValue("minor_units").toLong()
                    val part = detail.payments.single { it.paymentAttemptId.value == row.getValue("payment_attempt_id") }
                    assertEquals(part.amount.minor.toString(), row.getValue("minor_units"))
                    assertEquals(part.latestCorrectionId?.value.orEmpty(), row.getValue("latest_correction_id"))
                    assertEquals(part.correctionCount.toString(), row.getValue("correction_count"))
                }
                assertEquals(expected, actual)
                assertEquals(source.paidMinor, classified.sumOf { it.getValue("minor_units").toLong() })
            }
        }
        f.previous.privateCanaries.forEach { assertFalse(it in text) }
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.historicaltenderreportv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** SUPPORT bindings to the frozen historical-tender v2 expectations, not whole-case acceptance.
 * Real controller/formatting, controlled summaries and metadata; no rendered UI or native/RPC calls.
 */
class HistoricalTenderReportV2ControllerSupportingTest {
    @Test fun pcrBasis13SupportNoSelectedSaleMeansNoReadOrExport() = runTest {
        withHistoricalTenderReportV2Controller {
            assertEquals(LocalBranchReportQuery(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS), controller.state.value.branchReportQuery)
            controller.restartBranchReport(token())
            controller.refresh(token())
            controller.nextBranchReportPage(token())
            controller.exportBranchReport(token())
            controller.exportBranchReport(token(), LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2)
            runCurrent()
            assertNull(controller.state.value.page)
            assertNull(controller.state.value.readProblem)
            assertNull(controller.state.value.branchReportQuery.selectedSaleId)
            assertEquals(LocalWorkBusy.NONE, controller.state.value.busy)
            assertTrue(port.reads.isEmpty())
            assertTrue(port.exports.isEmpty())
            assertNoUnrelatedOrNativeCalls()
        }
    }

    @Test fun pcrBasis00And01SupportCashBasesKeepBilingualBucketsAndOriginalClose() = runTest {
        withHistoricalTenderReportV2Controller {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash4600Sale, token())
            runCurrent()
            val uncorrected = selectedDocument(controller.state.value)
            val uncorrectedDetail = assertNotNull(uncorrected.retailTenderBreakdown)
            assertBuckets(uncorrectedDetail.originalBuckets, listOf(Triple(LocalText("Cash", "نقداً"), null, 460000L)))
            assertBuckets(uncorrectedDetail.reconciledBuckets, listOf(Triple(LocalText("Cash", "نقداً"), null, 460000L)))
            assertEquals(460000L, paidMinor(uncorrected))
            assertClose(uncorrected, listOf(235000L, 236000L, 1000L))
            assertEquals(1, uncorrectedDetail.payments.size)
            assertEquals(460000L, uncorrectedDetail.payments.single().amount.minor)
            assertEquals(0L, uncorrectedDetail.payments.single().correctionCount)
            assertNull(uncorrectedDetail.payments.single().latestCorrectionId)
            assertNoUnrelatedOrNativeCalls()
        }
        withHistoricalTenderReportV2Controller {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash10Sale, token())
            runCurrent()
            val corrected = selectedDocument(controller.state.value)
            val detail = assertNotNull(corrected.retailTenderBreakdown)
            assertBuckets(detail.originalBuckets, listOf(Triple(LocalText("Card", "بطاقة"), HistoricalTenderReportV2Inputs.cardTender, 1000L)))
            assertBuckets(detail.reconciledBuckets, listOf(Triple(LocalText("Cash", "نقداً"), null, 1000L)))
            assertEquals(1000L, paidMinor(corrected))
            assertEquals(1000L, detail.originalBuckets.sumOf { it.amount.minor })
            assertEquals(1000L, detail.reconciledBuckets.sumOf { it.amount.minor })
            assertClose(corrected, listOf(235000L, 236000L, 1000L))
            assertEquals(HistoricalTenderReportV2Inputs.cash10Order, corrected.relatedDocumentId)
            assertEquals(HistoricalTenderReportV2Inputs.cashSession, corrected.cashSessionId)
            assertEquals(HistoricalTenderReportV2Inputs.cashCloseOperation, detail.originalCloseOperationId)
            assertEquals(HistoricalTenderReportV2Inputs.collector, detail.originalCollectorId)
            assertTrue(detail.originalCollectorId != HistoricalTenderReportV2Inputs.reader)
            val payment = detail.payments.single()
            assertEquals(HistoricalTenderReportV2Inputs.cash10Payment, payment.paymentAttemptId)
            assertEquals(1000L, payment.amount.minor)
            assertEquals(LocalText("Card", "بطاقة"), payment.originalClassification.label)
            assertEquals(LocalText("Cash", "نقداً"), payment.reconciledClassification.label)
            assertEquals(HistoricalTenderReportV2Inputs.cashCorrection, payment.latestCorrectionId)
            assertEquals(1L, payment.correctionCount)
            assertNoUnrelatedOrNativeCalls()
        }
    }

    @Test fun pcrBasis02SupportSplitAmountsAndOriginalPaymentLinksStaySeparate() = runTest {
        withHistoricalTenderReportV2Controller {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.split9999Sale, token())
            runCurrent()
            val document = selectedDocument(controller.state.value)
            val detail = assertNotNull(document.retailTenderBreakdown)
            assertBuckets(detail.originalBuckets, listOf(Triple(LocalText("Card", "بطاقة"), HistoricalTenderReportV2Inputs.cardTender, 9999L)))
            assertBuckets(detail.reconciledBuckets, listOf(
                Triple(LocalText("Card", "بطاقة"), HistoricalTenderReportV2Inputs.cardTender, 7264L),
                Triple(LocalText("External wallet", "محفظة إلكترونية"), HistoricalTenderReportV2Inputs.walletTender, 2735L)))
            assertEquals(9999L, paidMinor(document))
            assertEquals(9999L, detail.originalBuckets.sumOf { it.amount.minor })
            assertEquals(9999L, detail.reconciledBuckets.sumOf { it.amount.minor })
            assertClose(document, listOf(62550L, 62550L, 0L))
            assertEquals(listOf(HistoricalTenderReportV2Inputs.split7264Payment, HistoricalTenderReportV2Inputs.split2735Payment),
                detail.payments.map { it.paymentAttemptId })
            assertEquals(listOf(7264L, 2735L), detail.payments.map { it.amount.minor })
            assertEquals(listOf(0L, 1L), detail.payments.map { it.correctionCount })
            assertNull(detail.payments.first().latestCorrectionId)
            assertEquals(HistoricalTenderReportV2Inputs.splitCorrection, detail.payments.last().latestCorrectionId)
            assertEquals(HistoricalTenderReportV2Inputs.correctionRecordedAt, detail.payments.last().latestCorrectionRecordedAt)
            assertEquals(HistoricalTenderReportV2Inputs.cardTender, detail.payments.last().originalClassification.tenderId)
            assertEquals(HistoricalTenderReportV2Inputs.walletTender, detail.payments.last().reconciledClassification.tenderId)
            assertEquals(listOf("\u206672.64\u2069 EGP", "\u206627.35\u2069 EGP"),
                detail.reconciledBuckets.map { AppLanguage.ENGLISH.money(it.amount) })
            assertEquals(listOf("\u2066٧٢٫٦٤\u2069 ج.م", "\u2066٢٧٫٣٥\u2069 ج.م"),
                detail.reconciledBuckets.map { AppLanguage.ARABIC.money(it.amount) })
            val report = assertNotNull(controller.state.value.page?.branchReport)
            assertEquals(HistoricalTenderReportV2Inputs.splitCut, report.branchRevision)
            assertEquals(HistoricalTenderReportV2Inputs.observedAt, report.observedAt)
            assertEquals(HistoricalTenderReportV2Inputs.split9999Sale, report.selectedSaleId)
            assertNoUnrelatedOrNativeCalls()
        }
    }

    @Test fun pcrBasis14SupportPresentZeroHasTwoEmptyBasesWhileUnavailableNeverDefaultsToZero() = runTest {
        withHistoricalTenderReportV2Controller {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.zeroSale, token())
            runCurrent()
            val report = assertNotNull(controller.state.value.page?.branchReport)
            assertEquals(1, report.documents.size)
            assertEquals(HistoricalTenderReportV2Inputs.zeroSale, report.selectedSaleId)
            val document = report.documents.single()
            val detail = assertNotNull(document.retailTenderBreakdown)
            assertTrue(detail.payments.isEmpty())
            assertTrue(detail.originalBuckets.isEmpty())
            assertTrue(detail.reconciledBuckets.isEmpty())
            assertEquals(0L, paidMinor(document))
            assertClose(document, listOf(10000L, 10000L, 0L))
            assertEquals(HistoricalTenderReportV2Inputs.zeroOrder, document.relatedDocumentId)
            assertEquals(HistoricalTenderReportV2Inputs.zeroCloseOperation, detail.originalCloseOperationId)
            assertNull(document.paymentCorrection)
            assertNoUnrelatedOrNativeCalls()
        }
        for (problem in listOf(LocalServiceProblem.UNAVAILABLE, LocalServiceProblem.NOT_IMPLEMENTED)) {
            withHistoricalTenderReportV2Controller(configure = { readReply = { LocalReadResult.Unavailable(problem) } }) {
                controller.selectReportSale(HistoricalTenderReportV2Inputs.zeroSale, token())
                runCurrent()
                assertNull(controller.state.value.page)
                assertEquals(problem, controller.state.value.readProblem)
                assertEquals(HistoricalTenderReportV2Inputs.zeroSale, controller.state.value.branchReportQuery.selectedSaleId)
                assertTrue(port.exports.isEmpty())
                assertNoUnrelatedOrNativeCalls()
            }
        }
    }

    @Test fun pcrBasis13SupportSelectionAndRefreshUseExactSaleAndClearPreviousPage() = runTest {
        val delayedB = CompletableDeferred<LocalReadResult>()
        withHistoricalTenderReportV2Controller {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash10Sale, token())
            runCurrent()
            controller.refresh(token())
            runCurrent()
            val aQuery = LocalWorkflowQuery(LocalWorkflow.REPORTS, branchReport = LocalBranchReportQuery(
                LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, selectedSaleId = HistoricalTenderReportV2Inputs.cash10Sale))
            assertEquals(listOf(aQuery, aQuery), port.reads)
            assertEquals(HistoricalTenderReportV2Inputs.cash10Sale, selectedDocument(controller.state.value).id)
            port.readReply = { delayedB.await() }
            controller.selectReportSale(HistoricalTenderReportV2Inputs.split9999Sale, token())
            assertNull(controller.state.value.page)
            assertEquals(HistoricalTenderReportV2Inputs.split9999Sale, controller.state.value.branchReportQuery.selectedSaleId)
            runCurrent()
            assertEquals(LocalWorkflowQuery(LocalWorkflow.REPORTS, branchReport = LocalBranchReportQuery(
                LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, selectedSaleId = HistoricalTenderReportV2Inputs.split9999Sale)), port.reads.last())
            assertNull(controller.state.value.page)
            delayedB.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE))
            runCurrent()
            assertNull(controller.state.value.page)
            assertEquals(LocalServiceProblem.UNAVAILABLE, controller.state.value.readProblem)
            assertNoUnrelatedOrNativeCalls()
        }
    }

    @Test fun pcrBasis08And13SupportMismatchedSelectedReplyCannotAdoptAnotherSale() = runTest {
        withHistoricalTenderReportV2Controller(configure = {
            readReply = { LocalReadResult.Available(HistoricalTenderReportV2Inputs.page(HistoricalTenderReportV2Inputs.split9999Sale)) }
        }) {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash10Sale, token())
            runCurrent()
            assertNull(controller.state.value.page)
            assertEquals(LocalServiceProblem.UNAVAILABLE, controller.state.value.readProblem)
            assertEquals(HistoricalTenderReportV2Inputs.cash10Sale, controller.state.value.branchReportQuery.selectedSaleId)
            assertTrue(port.exports.isEmpty())
            assertNoUnrelatedOrNativeCalls()
        }
    }

    @Test fun pcrBasis10And13SupportExportForwardsOnlySelectedSaleAndPreservesView() = runTest {
        withHistoricalTenderReportV2Controller(configure = {
            exportReply = { _, _ -> LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.DENIED) }
        }) {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash10Sale, token())
            runCurrent()
            val originalView = assertNotNull(controller.state.value.page)
            val readsBefore = port.reads.size
            controller.nextBranchReportPage(token())
            controller.exportBranchReport(token(), LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2)
            runCurrent()
            assertTrue(port.exports.isEmpty())
            controller.exportBranchReport(token())
            runCurrent()
            assertEquals(LocalBranchReportExportSelection(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, null,
                selectedSaleId = HistoricalTenderReportV2Inputs.cash10Sale), port.exports.single())
            assertSame(originalView, controller.state.value.page)
            assertEquals(readsBefore, port.reads.size)
            assertNull(controller.state.value.readProblem)
            assertEquals(LocalServiceProblem.DENIED,
                (controller.state.value.branchReportExport.result as? LocalBranchReportExportResult.Unavailable)?.serviceProblem)
            assertFalse(port.exportLifetimes.single().isCurrent())
            controller.selectReportSale(HistoricalTenderReportV2Inputs.split9999Sale, token())
            runCurrent()
            controller.exportBranchReport(token())
            runCurrent()
            assertEquals(listOf(HistoricalTenderReportV2Inputs.cash10Sale, HistoricalTenderReportV2Inputs.split9999Sale),
                port.exports.map { it.selectedSaleId })
            assertTrue(port.exports.all { it.after == null && it.coverage == null })
            assertNoUnrelatedOrNativeCalls()
        }
    }

    @Test fun pcrBasis09SupportSessionInvalidationClearsBothBasesAndExportState() = runTest {
        withHistoricalTenderReportV2Controller(configure = {
            exportReply = { _, _ -> LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED,
                exportId = HistoricalTenderReportV2Inputs.exportA) }
            historyReply = LocalBranchReportExportHistory.Available(listOf(HistoricalTenderReportV2Inputs.originalARecord()), null)
        }) {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash10Sale, token())
            runCurrent()
            controller.exportBranchReport(token())
            runCurrent()
            controller.branchReportExportHistory(token())
            runCurrent()
            assertNotNull(controller.state.value.page?.branchReport?.documents?.single()?.retailTenderBreakdown)
            assertNotNull(controller.state.value.branchReportExport.result)
            assertTrue((controller.state.value.branchReportExport.history as LocalBranchReportExportHistory.Available).records.isNotEmpty())
            val oldToken = token()
            val callsBefore = port.reads.size to port.exports.size
            session.invalidate()
            assertFalse(controller.reportDisplayCurrent(oldToken))
            runCurrent()
            assertNull(controller.state.value.page)
            assertNull(controller.state.value.branchReportExport.result)
            assertNull(controller.state.value.branchReportExport.history)
            assertNull(controller.state.value.branchReportExport.progress)
            controller.restartBranchReport(oldToken)
            controller.exportBranchReport(oldToken)
            controller.recoverBranchReportExport(HistoricalTenderReportV2Inputs.exportA, oldToken)
            runCurrent()
            assertEquals(callsBefore, port.reads.size to port.exports.size)
            assertTrue(port.recoveries.isEmpty())
            assertNoUnrelatedOrNativeCalls()
        }
    }

    @Test fun pcrBasis09And13SupportLateExportAfterInvalidationCannotReviveProtectedReceipt() = runTest {
        val delayed = CompletableDeferred<LocalBranchReportExportResult>()
        val lateMetadata = LocalBranchReportExportResult.Outcome(HistoricalTenderReportV2Inputs.originalARecord())
        withHistoricalTenderReportV2Controller(configure = {
            exportReply = { _, _ -> withContext(NonCancellable) { delayed.await() } }
        }) {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash10Sale, token())
            runCurrent()
            try {
                controller.exportBranchReport(token())
                runCurrent()
                assertTrue(port.exportLifetimes.single().isCurrent())
                assertEquals(LocalWorkBusy.REPORT_EXPORT, controller.state.value.busy)
                session.invalidate()
                assertFalse(port.exportLifetimes.single().isCurrent())
                runCurrent()
                assertNull(controller.state.value.page)
                assertNull(controller.state.value.branchReportExport.result)
                delayed.complete(lateMetadata)
                runCurrent()
                assertNull(controller.state.value.page)
                assertNull(controller.state.value.branchReportExport.result)
                assertNull(controller.state.value.branchReportExport.history)
                assertFalse(port.exportLifetimes.single().isCurrent())
                assertNoUnrelatedOrNativeCalls()
            } finally { delayed.complete(lateMetadata) }
        }
    }

    @Test fun pcrBasis13SupportOriginalReceiptRecoveryRemainsBoundToAAfterSelectingB() = runTest {
        val original = HistoricalTenderReportV2Inputs.originalARecord()
        withHistoricalTenderReportV2Controller(configure = {
            historyReply = LocalBranchReportExportHistory.Available(listOf(original), null)
            recoveryReply = { LocalBranchReportExportResult.Outcome(original) }
        }) {
            controller.selectReportSale(HistoricalTenderReportV2Inputs.cash10Sale, token())
            runCurrent()
            controller.branchReportExportHistory(token())
            runCurrent()
            controller.selectReportSale(HistoricalTenderReportV2Inputs.split9999Sale, token())
            runCurrent()
            val bView = assertNotNull(controller.state.value.page)
            controller.recoverBranchReportExport(HistoricalTenderReportV2Inputs.exportA, token())
            runCurrent()
            controller.recoverBranchReportExport(HistoricalTenderReportV2Inputs.exportA, token())
            runCurrent()
            assertEquals(listOf(HistoricalTenderReportV2Inputs.exportA, HistoricalTenderReportV2Inputs.exportA), port.recoveries)
            assertSame(bView, controller.state.value.page)
            val recovered = assertNotNull(controller.state.value.branchReportExport.result as? LocalBranchReportExportResult.Outcome).record
            assertEquals(original, recovered)
            assertEquals(HistoricalTenderReportV2Inputs.cash10Sale, recovered.receipt.query.selectedSaleId)
            assertEquals(LocalBranchReportFileProfile.RETAIL_TENDER_BREAKDOWN_V3, recovered.receipt.query.profile)
            assertEquals(HistoricalTenderReportV2Inputs.cashAfterCut, recovered.receipt.source?.branchRevision)
            assertEquals(HistoricalTenderReportV2Inputs.split9999Sale, bView.branchReport?.selectedSaleId)
            assertTrue(port.exports.isEmpty())
            assertNoUnrelatedOrNativeCalls()
        }
    }

    private fun selectedDocument(state: LocalWorkspaceState): LocalBranchReportDocument {
        val report = assertNotNull(state.page?.branchReport)
        assertEquals(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, report.section)
        assertEquals(state.branchReportQuery.selectedSaleId, report.selectedSaleId)
        assertNull(report.after)
        assertNull(report.nextAfter)
        return report.documents.single().also { assertEquals(report.selectedSaleId, it.id) }
    }

    private fun paidMinor(document: LocalBranchReportDocument): Long =
        document.amounts.single { it.group == LocalBranchReportAmountGroup.PAID_SALE }.amount.minor

    private fun assertClose(document: LocalBranchReportDocument, expected: List<Long>) {
        assertEquals(expected, document.amounts.filter { it.group == LocalBranchReportAmountGroup.ORIGINAL_CLOSE }.map { it.amount.minor })
        assertEquals(0L, document.amounts.single { it.group == LocalBranchReportAmountGroup.PHYSICAL_MOVEMENT }.amount.minor)
    }

    private fun assertBuckets(actual: List<LocalBranchReportTenderBucket>, expected: List<Triple<LocalText, DomainId?, Long>>) {
        assertEquals(expected.size, actual.size)
        actual.zip(expected).forEach { (bucket, oracle) ->
            assertEquals(oracle.first.english, AppLanguage.ENGLISH.text(bucket.label))
            assertEquals(oracle.first.arabic, AppLanguage.ARABIC.text(bucket.label))
            assertTrue(bucket.label.english.isNotBlank() && bucket.label.arabic.isNotBlank())
            assertEquals(oracle.second, bucket.tenderId)
            assertEquals(oracle.third, bucket.amount.minor)
        }
    }
}

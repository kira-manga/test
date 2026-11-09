package com.ukkera.pos.app.gateway.independent.historicaltenderreportv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.GatewayFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Frozen selected-document basis SUPPORT at the real VIEW mapper over controlled wire DATA.
 * No native original/source qualification, actual grants, admitted RPC or Compose rendering. */
class HistoricalTenderReportPresentationSupportingTest {
    private val f = HistoricalTenderGatewayInputs

    @Test
    fun supportExplicitCashAndSplitSelectionsMapExactBothBasesWithoutReplacingOriginalClose() = runTest {
        for (source in listOf(f.cash4600, f.cash10, f.split9999)) {
            HistoricalTenderMapperFixture(source).use { fixture ->
                val workflow = fixture.read()
                val document = selectedDocument(workflow, source)
                val detail = assertNotNull(document.retailTenderBreakdown)
                assertEquals(source.originalMinor, amountsByKind(detail.originalBuckets))
                assertEquals(source.reconciledMinor, amountsByKind(detail.reconciledBuckets))
                assertEquals(source.paidMinor, detail.originalBuckets.sumOf { it.amount.minor })
                assertEquals(source.paidMinor, detail.reconciledBuckets.sumOf { it.amount.minor })
                assertTrue((detail.originalBuckets + detail.reconciledBuckets).all { it.amount.minor > 0 })
                assertEquals(source.row.retailTenderBreakdown!!.payments.map { it.paymentAttemptId },
                    detail.payments.map { it.paymentAttemptId })
                assertEquals(source.row.retailTenderBreakdown!!.payments.map { it.amount }, detail.payments.map { it.amount })
                for ((original, presented) in source.row.retailTenderBreakdown!!.payments.zip(detail.payments)) {
                    assertEquals(original.originalClassification.tenderId, presented.originalClassification.tenderId)
                    assertEquals(original.reconciledClassification.tenderId, presented.reconciledClassification.tenderId)
                    assertEquals(original.latestCorrectionId, presented.latestCorrectionId)
                    assertEquals(original.correctionCount, presented.correctionCount)
                    assertEquals(original.latestCorrectionRecordedAt, presented.latestCorrectionRecordedAt)
                    bilingual(presented.originalClassification.label)
                    bilingual(presented.reconciledClassification.label)
                }
                assertTrue(workflow.records.isEmpty() && workflow.forms.isEmpty())
                fixture.assertOnlySelectedReportReads()
            }
        }
        // The DTO's separately named basis containers are checked here, not the actual
        // Compose basis headings, layout/accessibility or rendered English/Arabic screen.
    }

    @Test
    fun supportPresentZeroRetainsOneSelectedDocumentAndBothEmptyBasesButMissingSourceRefuses() = runTest {
        HistoricalTenderMapperFixture(f.zero).use { fixture ->
            val document = selectedDocument(fixture.read(), f.zero)
            val detail = assertNotNull(document.retailTenderBreakdown)
            assertTrue(detail.payments.isEmpty())
            assertTrue(detail.originalBuckets.isEmpty() && detail.reconciledBuckets.isEmpty())
            fixture.assertOnlySelectedReportReads()
        }
        HistoricalTenderMapperFixture(f.zero).use { fixture ->
            fixture.removeSelectedSource()
            assertFailsWith<IllegalArgumentException> { fixture.read() }
            fixture.assertOnlySelectedReportReads()
        }
        // Zero/full-discount/fulfilled-stock native source ancestry is not supplied by wire DATA.
    }

    @Test
    fun supportOldCutCannotAppendAfterCutAndFourFindingsRemainVisibleWhenBasesBecomeEqual() = runTest {
        val before = f.before(f.cash10)
        HistoricalTenderMapperFixture(before).use { fixture ->
            val old = assertNotNull(fixture.read().branchReport)
            val oldDetail = assertNotNull(old.documents.single().retailTenderBreakdown)
            assertEquals(f.buckets(0, 1000, 0), amountsByKind(oldDetail.originalBuckets))
            assertEquals(f.buckets(0, 1000, 0), amountsByKind(oldDetail.reconciledBuckets))
            assertEquals(0L, oldDetail.payments.single().correctionCount)
            fixture.replace(f.cash10)
            assertEquals(LocalServiceProblem.REPORT_RESTART_REQUIRED,
                assertFailsWith<GatewayFailure> { fixture.read(before.branchRevision) }.problem)
            val fresh = assertNotNull(fixture.read().branchReport)
            assertEquals(f.cash10.branchRevision, fresh.branchRevision)
            assertEquals(f.cash10.observedAt, fresh.observedAt)
            assertEquals(f.buckets(1000, 0, 0), amountsByKind(fresh.documents.single().retailTenderBreakdown!!.reconciledBuckets))
            assertEquals(before.branchRevision, old.branchRevision)
            assertEquals(f.buckets(0, 1000, 0), amountsByKind(oldDetail.reconciledBuckets))
            fixture.assertOnlySelectedReportReads(3)
        }
        HistoricalTenderMapperFixture(f.chain4).use { fixture ->
            val document = selectedDocument(fixture.read(), f.chain4)
            val detail = assertNotNull(document.retailTenderBreakdown)
            assertEquals(f.buckets(0, 9999, 0), amountsByKind(detail.originalBuckets))
            assertEquals(f.buckets(0, 9999, 0), amountsByKind(detail.reconciledBuckets))
            val latest = detail.payments.single { it.paymentAttemptId == f.id("77000000", 2) }
            assertEquals(4L, latest.correctionCount)
            assertEquals(f.id("77000000", 34), latest.latestCorrectionId)
            assertEquals(f.chain4.observedAt, latest.latestCorrectionRecordedAt)
            fixture.assertOnlySelectedReportReads()
        }
        // Scripted cut/head DATA is not authenticated refresh, notification, chain or restore qualification.
    }

    @Test
    fun supportWrongSelectedSourceOrDamagedMembershipCannotReachPresentation() = runTest {
        HistoricalTenderMapperFixture(f.cash10).use { fixture ->
            fixture.replace(f.split9999)
            val failure = assertFailsWith<GatewayFailure> { fixture.read() }
            assertEquals(LocalServiceProblem.UNAVAILABLE, failure.problem)
            assertFalse(f.split9999.row.id.value in failure.toString())
            fixture.assertOnlySelectedReportReads()
        }
        val source = f.split9999
        val detail = source.row.retailTenderBreakdown!!
        val variants = listOf(f.damagedPaymentSum(source),
            source.row.copy(retailTenderBreakdown = detail.copy(payments = listOf(detail.payments.last()))),
            source.row.copy(retailTenderBreakdown = detail.copy(payments = listOf(detail.payments.first(), detail.payments.first()))))
        for (row in variants) HistoricalTenderMapperFixture(source).use { fixture ->
            fixture.replace(source, row)
            assertEquals(LocalServiceProblem.UNAVAILABLE, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlySelectedReportReads()
        }
    }

    @Test
    fun supportBothBasisSummaryAndPopulatedNestedPrivateFieldProbesExposeNoCanaries() = runTest {
        HistoricalTenderMapperFixture(f.split9999).use { fixture ->
            val safe = fixture.payload.copyBytes().decodeToString() + fixture.read().toString()
            f.previous.privateCanaries.forEach { assertFalse(it in safe) }
            fixture.assertOnlySelectedReportReads()
        }
        val fields = listOf("rawPaymentReference", "privatePaymentEvidence", "customerContact", "privateReason")
        fields.zip(f.previous.privateCanaries).forEach { (field, canary) ->
            HistoricalTenderMapperFixture(f.split9999).use { fixture ->
                fixture.addPrivatePaymentField(field, canary)
                assertTrue(canary in fixture.payload.copyBytes().decodeToString())
                val failure = assertFailsWith<IllegalArgumentException> { fixture.read() }
                f.previous.privateCanaries.forEach { assertFalse(it in failure.message.orEmpty()) }
                fixture.assertOnlySelectedReportReads()
            }
        }
        // Strict nested summary decoding is bound; genuine raw evidence/customer source redaction is not.
    }

    @Test
    fun supportControlledReadDenialAndLeaseEndingBeforeOrAfterReplyReturnNoDocument() = runTest {
        HistoricalTenderMapperFixture(f.cash10).use { fixture ->
            fixture.denied = true
            assertEquals(LocalServiceProblem.DENIED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlySelectedReportReads()
        }
        HistoricalTenderMapperFixture(f.cash10).use { fixture ->
            fixture.leaseCurrent = false
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlySelectedReportReads(0)
        }
        HistoricalTenderMapperFixture(f.cash10).use { fixture ->
            fixture.afterRead = { fixture.leaseCurrent = false }
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlySelectedReportReads()
        }
    }

    private fun selectedDocument(workflow: LocalWorkflowPage, source: HistoricalTenderGatewayDocument): LocalBranchReportDocument {
        val page = assertNotNull(workflow.branchReport)
        assertEquals(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, page.section)
        assertEquals(source.row.id, page.selectedSaleId)
        assertNull(page.after); assertNull(page.nextAfter)
        assertEquals(source.branchRevision, page.branchRevision)
        assertEquals(source.observedAt, page.observedAt)
        val document = page.documents.single()
        assertEquals(source.row.id, document.id)
        assertEquals(source.row.relatedDocumentId, document.relatedDocumentId)
        assertEquals(source.row.cashSessionId, document.cashSessionId)
        assertEquals(source.closeMinor, document.amounts.filter { it.group == LocalBranchReportAmountGroup.ORIGINAL_CLOSE }.map { it.amount.minor })
        assertEquals(source.paidMinor, document.amounts.single { it.group == LocalBranchReportAmountGroup.PAID_SALE }.amount.minor)
        assertEquals(0L, document.amounts.single { it.group == LocalBranchReportAmountGroup.PHYSICAL_MOVEMENT }.amount.minor)
        assertNull(document.paymentCorrection)
        val detail = assertNotNull(document.retailTenderBreakdown)
        assertEquals(source.row.retailTenderBreakdown!!.originalCloseOperationId, detail.originalCloseOperationId)
        assertEquals(f.previous.originalCollector, detail.originalCollectorId)
        assertNotEquals(f.previous.correctionActor, detail.originalCollectorId)
        document.amounts.forEach { bilingual(it.label) }
        bilingual(document.status)
        return document
    }

    private fun amountsByKind(buckets: List<LocalBranchReportTenderBucket>): Map<String, Long> {
        if (buckets.isEmpty()) return emptyMap()
        val result = f.buckets(0, 0, 0).toMutableMap()
        for (bucket in buckets) {
            val kind = when (bucket.tenderId) {
                f.card.tenderId -> "card"; f.wallet.tenderId -> "wallet"; null -> "cash"
                else -> error("Unexpected safe tender identity")
            }
            result[kind] = result.getValue(kind) + bucket.amount.minor
            bilingual(bucket.label)
        }
        return result
    }
    private fun bilingual(text: LocalText) {
        assertTrue(text.english.isNotBlank() && text.arabic.isNotBlank())
        assertNotEquals(text.english, text.arabic)
    }
}

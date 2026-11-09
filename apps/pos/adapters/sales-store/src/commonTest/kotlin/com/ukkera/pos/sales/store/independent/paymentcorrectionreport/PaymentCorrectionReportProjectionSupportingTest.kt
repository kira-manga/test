package com.ukkera.pos.sales.store.independent.paymentcorrectionreport

import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalPaymentCorrectionCloseViewV1
import com.ukkera.pos.sales.store.LocalPaymentCorrectionViewV1
import com.ukkera.pos.sales.store.LocalSalesSourceFailure
import com.ukkera.pos.sales.store.LocalSalesSourceUnavailable
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.sales.store.paymentCorrectionReportProjection
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Projection/arithmetic SUPPORT only; no native source, durable close or physical-effect qualification. */
class PaymentCorrectionReportProjectionSupportingTest {
    private val inputs = PaymentCorrectionReportSupportingInputs

    @Test
    fun supportCash10KeepsOriginalCloseAndLinkedClassificationExplanationDistinct() {
        val fixture = inputs.cash10
        val row = inputs.row(fixture)
        assertRow(fixture, row, inputs.cashCorrectionMetrics)
        val amounts = row.amounts.associate { it.metric to it.amount.minor }
        assertEquals(1000L, amounts.getValue(LocalBranchReportMetricV1.VARIANCE))
        assertEquals(0L, amounts.getValue(LocalBranchReportMetricV1.VARIANCE) -
            amounts.getValue(LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA))
        assertNotEquals(amounts.getValue(LocalBranchReportMetricV1.VARIANCE),
            amounts.getValue(LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA))
        assertReconciledArithmetic(fixture, row,
            mapOf("CASH" to 461000L, "CARD" to 399000L, "WALLET" to 0L), 860000)
        assertEquals(UtcInstant.parseCanonical("2026-11-03T01:59:59Z"), fixture.close.countEvidence.occurredAt)
        assertEquals(UtcInstant.parseCanonical("2026-11-03T02:00:01Z"), row.paymentCorrection?.recordedAt)
    }

    @Test
    fun supportExt2735ConservesExactFractionalClassificationsWithoutCashExplanation() {
        val fixture = inputs.ext2735
        val row = inputs.row(fixture)
        assertRow(fixture, row, inputs.externalCorrectionMetrics)
        assertReconciledArithmetic(fixture, row,
            mapOf("CASH" to 12550L, "CARD" to 7264L, "WALLET" to 3236L), 23050)
        assertEquals(0L, row.amounts.single { it.metric == LocalBranchReportMetricV1.VARIANCE }.amount.minor)
        assertEquals(0L, row.amounts.single { it.metric == LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA }.amount.minor)
        assertEquals("CARD", row.paymentCorrection?.previousClassification?.kind)
        assertEquals("EXTERNAL_WALLET", row.paymentCorrection?.correctedClassification?.kind)
    }

    @Test
    fun supportProjectionLeavesControlledOriginalAndNextSessionInputsUnchanged() {
        for (fixture in inputs.fixtures) {
            val closeBefore = localSalesEncode(fixture.close, LocalPaymentCorrectionCloseViewV1.serializer())
            val correctionBefore = localSalesEncode(fixture.correction, LocalPaymentCorrectionViewV1.serializer())
            val originalBefore = localSalesEncode(fixture.originalCloseRow, LocalBranchReportRowV1.serializer())
            // Supplied next-session scalars are transport controls, not a durable balance observation.
            val next = LocalBranchReportRowV1(fixture.nextSessionId, Revision(0),
                cashSessionId = fixture.nextSessionId, status = "OPEN", amounts = listOf(
                    LocalBranchReportAmountV1(LocalBranchReportMetricV1.OPENING_FLOAT, inputs.money(fixture.nextOpeningMinor)),
                    LocalBranchReportAmountV1(LocalBranchReportMetricV1.EXPECTED_CASH, inputs.money(fixture.nextOpeningMinor)),
                    LocalBranchReportAmountV1(LocalBranchReportMetricV1.ACTUAL_COLLECTIONS, inputs.money(0)),
                ))
            val nextBefore = localSalesEncode(next, LocalBranchReportRowV1.serializer())
            val row = inputs.row(fixture)
            assertEquals(closeBefore, localSalesEncode(fixture.close, LocalPaymentCorrectionCloseViewV1.serializer()))
            assertEquals(correctionBefore, localSalesEncode(fixture.correction, LocalPaymentCorrectionViewV1.serializer()))
            assertEquals(originalBefore, localSalesEncode(fixture.originalCloseRow, LocalBranchReportRowV1.serializer()))
            assertEquals(nextBefore, localSalesEncode(next, LocalBranchReportRowV1.serializer()))
            assertEquals(0L, next.amounts.single { it.metric == LocalBranchReportMetricV1.ACTUAL_COLLECTIONS }.amount.minor)
            assertEquals(0L, row.amounts.single { it.metric == LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA }.amount.minor)
            val newMoneyMetrics = setOf(LocalBranchReportMetricV1.ACTUAL_COLLECTIONS,
                LocalBranchReportMetricV1.CASH_RECEIVED, LocalBranchReportMetricV1.CHANGE_GIVEN,
                LocalBranchReportMetricV1.CASH_REFUNDS, LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL,
                LocalBranchReportMetricV1.TRANSFER_AMOUNT)
            assertFalse(row.amounts.any { it.metric in newMoneyMetrics })
        }
        assertEquals(50000L, inputs.cash10.nextOpeningMinor)
        assertEquals(10000L, inputs.ext2735.nextOpeningMinor)
    }

    @Test
    fun supportSafeProjectionOmitsPopulatedPrivateFreeText() {
        val source = inputs.withPrivateCanaries()
        val sourceText = localSalesEncode(source, LocalPaymentCorrectionViewV1.serializer()).copyBytes().decodeToString()
        val safe = inputs.row(inputs.ext2735, source)
        val safeText = localSalesEncode(safe, LocalBranchReportRowV1.serializer()).copyBytes().decodeToString()
        for (canary in inputs.privateCanaries) {
            assertTrue(canary in sourceText)
            assertFalse(canary in safeText)
        }
        assertEquals(inputs.correctionActorId, safe.paymentCorrection?.correctionActorId)
        assertEquals(inputs.originalCollectorId, safe.paymentCorrection?.originalCollectorId)
    }

    @Test
    fun supportProjectionRejectsConflictingOriginalCloseBindings() {
        val fixture = inputs.cash10
        val mismatched = listOf(
            fixture.close.copy(sessionId = fixture.nextSessionId),
            fixture.close.copy(originalCloseOperationId = fixture.correction.operationId),
            fixture.close.copy(submittedBy = inputs.correctionActorId),
        )
        for (close in mismatched) {
            val failure = assertFailsWith<LocalSalesSourceUnavailable> {
                paymentCorrectionReportProjection(fixture.correction, close)
            }
            assertEquals(LocalSalesSourceFailure.INVALID, failure.failure)
        }
        assertEquals(inputs.originalCollectorId, fixture.close.submittedBy)
    }

    @Test
    fun supportProjectionRejectsAReplacedOrInconsistentSubmittedVariance() {
        val fixture = inputs.cash10
        val changed = fixture.close.copy(variance = inputs.money(0))
        val failure = assertFailsWith<LocalSalesSourceUnavailable> {
            paymentCorrectionReportProjection(fixture.correction, changed)
        }
        assertEquals(LocalSalesSourceFailure.INVALID, failure.failure)
        assertEquals(inputs.money(235000), fixture.close.expectedCash)
        assertEquals(inputs.money(236000), fixture.close.counted)
        assertEquals(inputs.money(1000), fixture.close.variance)
    }

    private fun assertRow(fixture: PaymentCorrectionReportFixture, row: LocalBranchReportRowV1,
        expected: List<Pair<LocalBranchReportMetricV1, Long>>) {
        assertEquals(expected.map { (metric, minor) -> LocalBranchReportAmountV1(metric, inputs.money(minor)) }, row.amounts)
        assertEquals(fixture.correction.id, row.id)
        assertEquals(Revision(0), row.revision)
        assertEquals(fixture.correction.saleId, row.relatedDocumentId)
        assertEquals(fixture.correction.sessionId, row.cashSessionId)
        assertEquals("CLASSIFICATION_CORRECTED", row.status)
        assertEquals(0, row.pendingCount)
        assertNull(row.businessDate)
        val detail = checkNotNull(row.paymentCorrection)
        assertEquals(fixture.correction.paymentAttemptId, detail.paymentAttemptId)
        assertEquals(fixture.close.originalCloseOperationId, detail.originalCloseOperationId)
        assertEquals(inputs.originalCollectorId, detail.originalCollectorId)
        assertEquals(inputs.correctionActorId, detail.correctionActorId)
        assertNotEquals(detail.originalCollectorId, detail.correctionActorId)
        assertNotEquals(inputs.nextCollectorId, detail.correctionActorId)
        assertEquals(fixture.correction.previousClassification, detail.previousClassification)
        assertEquals(fixture.correction.correctedTender, detail.correctedClassification)
        assertEquals(fixture.correction.recordedAt, detail.recordedAt)
        assertNotEquals(fixture.close.countEvidence.occurredAt, detail.recordedAt)
    }

    /** A separately identified arithmetic check, not a production period-aggregation view. */
    private fun assertReconciledArithmetic(fixture: PaymentCorrectionReportFixture, row: LocalBranchReportRowV1,
        expected: Map<String, Long>, expectedTotal: Long) {
        val detail = checkNotNull(row.paymentCorrection)
        val metrics = row.amounts.associate { it.metric to it.amount.minor }
        val previous = metrics.getValue(LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA)
        val corrected = metrics.getValue(LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA)
        assertEquals(0L, previous + corrected)
        fun fixtureMethod(wireKind: String): String = if (wireKind == "EXTERNAL_WALLET") "WALLET" else wireKind
        val reconciled = fixture.originalClassifications.mapValues { (kind, original) -> original +
            (if (kind == fixtureMethod(detail.previousClassification.kind)) previous else 0) +
            (if (kind == fixtureMethod(detail.correctedClassification.kind)) corrected else 0)
        }
        assertEquals(expected, reconciled)
        assertEquals(expectedTotal, fixture.originalClassifications.values.sum())
        assertEquals(expectedTotal, reconciled.values.sum())
        assertEquals(expectedTotal, fixture.paidSalesMinor)
    }
}

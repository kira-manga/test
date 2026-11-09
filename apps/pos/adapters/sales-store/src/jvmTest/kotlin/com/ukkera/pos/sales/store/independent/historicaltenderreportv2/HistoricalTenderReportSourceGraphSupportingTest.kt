package com.ukkera.pos.sales.store.independent.historicaltenderreportv2

import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.sales.store.independent.paymentcorrectionreport.PaymentCorrectionReportSupportingInputs
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual current correction-source graph SUPPORT over inert, read-only DATA.
 * No source here qualifies native original persistence, current authority, atomicity or RPC. */
class HistoricalTenderReportSourceGraphSupportingTest {
    private val f = HistoricalTenderReportSupportingInputs
    private val d = HistoricalTenderSourceData
    private val p = LocalPaymentCorrectionProfileV1

    @Test fun supportVerifiedEmptyGraphReturnsNoHeadOnlyAfterCompleteCorrectionCensus() = runTest {
        for (fixture in listOf(f.cash4600, f.before(f.cash10), f.before(f.split9999), f.zero)) {
            val sale = d.sale(fixture)
            val session = d.session(fixture)
            val view = HistoricalTenderReadData(d.scope)
            val sources = LocalCurrentPaymentCorrectionSources(view)
            sources.requireSaleCoverage(sale)
            for (payment in sale.payments) assertNull(sources.head(sale, payment, session))
            assertTrue(view.rangePrefixes.containsAll(listOf("payment_correction/", "payment_correction_member/",
                "payment_correction_position/", "payment_corrections/")))
            assertTrue(view.originalRequests.isEmpty())
            assertEquals(fixture.paidMinor, sale.payments.sumOf { it.amount.minor })
        }
    }

    @Test fun supportOrphanPositionMemberRecordOrHeadClaimCannotMeanNoCorrectionEvenForZeroSale() = runTest {
        for (fixture in listOf(f.before(f.cash10), f.zero)) {
            val sale = d.sale(fixture)
            val orphanId = f.cash10.payments.single().latestCorrectionId!!
            val orphanPayment = f.cash10.payments.single().paymentAttemptId
            val keys = listOf(p.positionClaim(orphanPayment, com.ukkera.pos.types.Revision(0)),
                p.membershipClaim(orphanPayment, orphanId), p.recordClaim(orphanId), p.headClaim(orphanPayment))
            for (key in keys) {
                val view = HistoricalTenderReadData(d.scope, claims = mapOf(key to fixture.saleId))
                assertFailsWith<LocalSalesSourceUnavailable> { LocalCurrentPaymentCorrectionSources(view).requireSaleCoverage(sale) }
                assertTrue(view.originalRequests.isEmpty())
            }
        }
    }

    @Test fun supportNonemptyGraphWithoutActualRetainedProducerRefusesInsteadOfOriginalEquality() = runTest {
        val fixture = f.cash10
        val sale = d.sale(fixture)
        val session = d.session(fixture)
        val correction = PaymentCorrectionReportSupportingInputs.cash10.correction
        val view = d.graph(listOf(correction))
        val beforeSale = SalesSnapshotCodec.encodePaidSale(sale)
        val beforeClose = SalesSnapshotCodec.encodeCashSession(session)
        val beforeRows = view.rows.map { it.payload.copyBytes().decodeToString() }
        val sources = LocalCurrentPaymentCorrectionSources(view)
        sources.requireSaleCoverage(sale) // Graph membership is not producer qualification.
        assertFailsWith<LocalSalesSourceUnavailable> { sources.head(sale, sale.payments.single(), session) }
        assertEquals(listOf(correction.operationId), view.originalRequests)
        assertEquals(beforeSale, SalesSnapshotCodec.encodePaidSale(sale))
        assertEquals(beforeClose, SalesSnapshotCodec.encodeCashSession(session))
        assertEquals(beforeRows, view.rows.map { it.payload.copyBytes().decodeToString() })
    }

    @Test fun supportMismatchedClaimsMissingHeadAndWrongLatestRecordCannotPassCoverage() = runTest {
        val correction = PaymentCorrectionReportSupportingInputs.cash10.correction
        val baseline = d.graph(listOf(correction))
        val sale = d.sale(f.cash10)
        val position = p.positionClaim(correction.paymentAttemptId, correction.position)
        val member = p.membershipClaim(correction.paymentAttemptId, correction.id)
        val candidates = listOf(
            HistoricalTenderReadData(d.scope, baseline.rows, baseline.claims - position),
            HistoricalTenderReadData(d.scope, baseline.rows, baseline.claims + (position to f.split9999.saleId)),
            HistoricalTenderReadData(d.scope, baseline.rows, baseline.claims + (member to f.split9999.saleId)),
            HistoricalTenderReadData(d.scope, baseline.rows.filter { it.key.kind != p.HEAD }, baseline.claims),
            d.graph(listOf(correction), listOf(d.head(correction).copy(latestCorrectionId = f.id("78000000", 99)))))
        for (view in candidates) {
            assertFailsWith<LocalSalesSourceUnavailable> { LocalCurrentPaymentCorrectionSources(view).requireSaleCoverage(sale) }
            assertTrue(view.originalRequests.isEmpty())
        }
    }

    @Test fun supportZeroMembershipRefusesAnOtherwiseCompleteOrphanCorrectionGraph() = runTest {
        val zero = f.zero
        val sale = d.sale(zero)
        assertTrue(sale.payments.isEmpty())
        assertEquals(0L, sale.quote.payable.minor)
        assertEquals(1000L, sale.quote.advertisedDiscount.minor)
        val correction = PaymentCorrectionReportSupportingInputs.cash10.correction.copy(saleId = zero.saleId,
            sessionId = zero.close.sessionId, originalCloseOperationId = zero.close.originalCloseOperationId)
        val view = d.graph(listOf(correction))
        assertFailsWith<LocalSalesSourceUnavailable> { LocalCurrentPaymentCorrectionSources(view).requireSaleCoverage(sale) }
        assertTrue(view.originalRequests.isEmpty())
        assertEquals(0L, sale.quote.payable.minor)
    }

    @Test fun supportMissingPredecessorAndMissingLatestPageRefuseRatherThanFlattenLinkedHistory() = runTest {
        val records = d.chainRecords()
        val lastHead = d.head(records.last())
        val sale = d.sale(f.chain(4))
        for (missing in listOf(records.first().id, records.last().id)) {
            val view = d.graph(records.filter { it.id != missing }, listOf(lastHead))
            assertFailsWith<LocalSalesSourceUnavailable> { LocalCurrentPaymentCorrectionSources(view).requireSaleCoverage(sale) }
            assertTrue(view.originalRequests.isEmpty())
        }
    }

    @Test fun supportWrongSaleSessionOrCloseCannotBeUsedAsTheSelectedPaymentHead() = runTest {
        val fixture = f.cash10
        val sale = d.sale(fixture)
        val session = d.session(fixture)
        val original = PaymentCorrectionReportSupportingInputs.cash10.correction
        val variants = listOf(original.copy(saleId = f.split9999.saleId),
            original.copy(sessionId = f.split9999.close.sessionId),
            original.copy(originalCloseOperationId = f.split9999.close.originalCloseOperationId))
        for (correction in variants) {
            val view = d.graph(listOf(correction))
            assertFailsWith<LocalSalesSourceUnavailable> {
                LocalCurrentPaymentCorrectionSources(view).head(sale, sale.payments.single(), session)
            }
            assertTrue(view.originalRequests.isEmpty())
        }
    }
}

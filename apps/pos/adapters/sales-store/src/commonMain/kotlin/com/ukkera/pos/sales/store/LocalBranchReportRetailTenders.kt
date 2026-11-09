package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadInvalidSelection
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import com.ukkera.pos.sales.ExternalTenderKind

/** Selected immutable normalized retail sale only. No eligible-source filtering or inferred
 * cash-session/period census: an unavailable original, counted close or correction head fails
 * the complete read. Every original payment remains present, even if it has no corrections. */
internal suspend fun retailTenderBreakdownReportRow(view: CommandReadView, saleId: DomainId): LocalBranchReportRowV1 {
    val source = findPaymentCorrectionSale(view, saleId) ?: throw MasterReadInvalidSelection()
    if (!paymentCorrectionClosed(source.session)) throw MasterReadInvalidSelection()
    val records = LocalCurrentPaymentCorrectionSources(view)
    records.requireSaleCoverage(source.sale)
    val payments = source.sale.payments.map { payment ->
        val head = records.head(source.sale, payment, source.session)
        LocalBranchReportRetailTenderPaymentV3(payment.attemptId, payment.amount, tenderView(payment.tender),
            head?.latest?.correctedTender ?: tenderView(payment.tender), head?.latest?.id,
            head?.corrections?.size?.toLong() ?: 0L, head?.latest?.recordedAt)
    }
    return retailTenderBreakdownReportProjection(source.sale.id, source.original.sale.stored.domainRevision(),
        source.sale.orderId, source.sale.quote.payable, paymentCorrectionCloseView(source.session), payments)
}

/** Pure presentation after the Master has qualified original membership and every complete
 * correction head. The actual close is repeated unchanged, never recomputed from tenders. */
internal fun retailTenderBreakdownReportProjection(saleId: DomainId, revision: Revision,
    orderId: DomainId, paidTotal: Money, close: LocalPaymentCorrectionCloseViewV1,
    payments: List<LocalBranchReportRetailTenderPaymentV3>): LocalBranchReportRowV1 =
    LocalBranchReportRowV1(saleId, revision, relatedDocumentId = orderId, cashSessionId = close.sessionId,
        status = "PAID_SALE_TENDER_BREAKDOWN", amounts = listOf(
            LocalBranchReportAmountV1(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL, paidTotal),
            LocalBranchReportAmountV1(LocalBranchReportMetricV1.CLOSING_EXPECTED, close.expectedCash),
            LocalBranchReportAmountV1(LocalBranchReportMetricV1.ACTUAL_COUNT, close.counted),
            LocalBranchReportAmountV1(LocalBranchReportMetricV1.VARIANCE, close.variance),
            LocalBranchReportAmountV1(LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA, Money(paidTotal.currency, 0))),
        retailTenderBreakdown = LocalBranchReportRetailTenderBreakdownV3(close.originalCloseOperationId,
            close.submittedBy, payments.toList()))

/** Shape/arithmetic checks, never source authority or proof that a restore retained its tail. */
internal fun validRetailTenderBreakdownReportRow(row: LocalBranchReportRowV1, observedAt: UtcInstant): Boolean {
    val detail = row.retailTenderBreakdown ?: return false
    if (row.status != "PAID_SALE_TENDER_BREAKDOWN" || row.paymentCorrection != null || row.pendingCount != 0 ||
        row.relatedDocumentId == null || row.cashSessionId == null || row.businessDate != null ||
        row.storageRevision != null || row.finalizedAt != null || row.originalPaidDocumentId != null ||
        detail.payments.size !in 0..64 || detail.payments.map { it.paymentAttemptId }.distinct().size != detail.payments.size) return false
    fun validTender(tender: LocalTenderViewV1): Boolean =
        if (tender.kind == "CASH") tender == LocalTenderViewV1("CASH")
        else ExternalTenderKind.entries.any { it.name == tender.kind } && tender.tenderId != null && tender.policyRevision != null
    for (payment in detail.payments) {
        if (payment.amount.currency != Currency.EGP || payment.amount.minor <= 0 ||
            !validTender(payment.originalClassification) || !validTender(payment.reconciledClassification) ||
            payment.correctionCount !in 0L..LocalPaymentCorrectionProfileV1.MAX_SCAN_ROWS.toLong()) return false
        if (payment.correctionCount == 0L) {
            if (payment.latestCorrectionId != null || payment.latestCorrectionRecordedAt != null ||
                payment.originalClassification != payment.reconciledClassification) return false
        } else {
            if (payment.latestCorrectionId == null || payment.latestCorrectionRecordedAt == null ||
                payment.latestCorrectionRecordedAt > observedAt) return false
            if (payment.correctionCount == 1L &&
                payment.originalClassification.kind == payment.reconciledClassification.kind &&
                payment.originalClassification.tenderId == payment.reconciledClassification.tenderId) return false
        }
    }
    val latestIds = detail.payments.mapNotNull { it.latestCorrectionId }
    if (latestIds.distinct().size != latestIds.size) return false
    val expected = setOf(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL, LocalBranchReportMetricV1.CLOSING_EXPECTED,
        LocalBranchReportMetricV1.ACTUAL_COUNT, LocalBranchReportMetricV1.VARIANCE, LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA)
    val amounts = row.amounts.associate { it.metric to it.amount }
    if (amounts.keys != expected || row.amounts.size != expected.size || amounts.values.any { it.currency != Currency.EGP }) return false
    val total = amounts.getValue(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL)
    // Reject overflow without accepting a wrapped/convenient partial total.
    var sum = 0L
    for (payment in detail.payments) {
        if (sum > Long.MAX_VALUE - payment.amount.minor) return false
        sum += payment.amount.minor
    }
    val expectedCash = amounts.getValue(LocalBranchReportMetricV1.CLOSING_EXPECTED)
    val count = amounts.getValue(LocalBranchReportMetricV1.ACTUAL_COUNT)
    return total.minor >= 0 && sum == total.minor && expectedCash.minor >= 0 && count.minor >= 0 &&
        count - expectedCash == amounts.getValue(LocalBranchReportMetricV1.VARIANCE) &&
        amounts.getValue(LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA).minor == 0L
}

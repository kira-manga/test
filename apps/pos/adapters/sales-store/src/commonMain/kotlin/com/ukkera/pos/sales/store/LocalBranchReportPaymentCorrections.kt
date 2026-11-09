package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.UtcInstant
import com.ukkera.pos.sales.ExternalTenderKind

/** A real retained correction plus its qualified original payment and counted close.
 * Reading a correction row alone never supplies the original sale/close or a whole-period total.
 */
internal suspend fun paymentCorrectionReportRow(view: CommandReadView, id: DomainId): LocalBranchReportRowV1 {
    val records = LocalCurrentPaymentCorrectionSources(view)
    val correction = records.record(id)
    val source = findPaymentCorrectionSale(view, correction.saleId) ?: unavailablePaymentCorrectionSource()
    requireLocalSalesSource(paymentCorrectionClosed(source.session))
    val payment = source.sale.payments.singleOrNull { it.attemptId == correction.paymentAttemptId }
        ?: unavailablePaymentCorrectionSource()
    val head = records.head(source.sale, payment, source.session) ?: unavailablePaymentCorrectionSource()
    requireLocalSalesSource(correction in head.corrections)
    return paymentCorrectionReportProjection(correction, paymentCorrectionCloseView(source.session))
}

/** Pure projection after source qualification; original figures are repeated, never recomputed.
 * The cash classification delta explains this linked row only, not a whole-close reconciliation.
 */
internal fun paymentCorrectionReportProjection(correction: LocalPaymentCorrectionViewV1,
    close: LocalPaymentCorrectionCloseViewV1): LocalBranchReportRowV1 {
    requireLocalSalesSource(correction.sessionId == close.sessionId &&
        correction.originalCloseOperationId == close.originalCloseOperationId &&
        correction.collectorId == close.submittedBy && close.expectedCash.currency == correction.amount.currency &&
        close.counted - close.expectedCash == close.variance)
    val zero = Money(correction.amount.currency, 0)
    val cashDelta = (if (correction.correctedTender.kind == "CASH") correction.correctedClassificationDelta else zero) +
        (if (correction.previousClassification.kind == "CASH") correction.previousClassificationDelta else zero)
    fun amount(metric: LocalBranchReportMetricV1, value: Money): LocalBranchReportAmountV1 =
        LocalBranchReportAmountV1(metric, value)
    return LocalBranchReportRowV1(correction.id, correction.position,
        relatedDocumentId = correction.saleId, cashSessionId = correction.sessionId,
        status = "CLASSIFICATION_CORRECTED", amounts = listOf(
            amount(LocalBranchReportMetricV1.CLOSING_EXPECTED, close.expectedCash),
            amount(LocalBranchReportMetricV1.ACTUAL_COUNT, close.counted),
            amount(LocalBranchReportMetricV1.VARIANCE, close.variance),
            amount(LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, correction.previousClassificationDelta),
            amount(LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA, correction.correctedClassificationDelta),
            amount(LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, cashDelta),
            amount(LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA, correction.physicalCashDelta)),
        paymentCorrection = LocalBranchReportPaymentCorrectionV2(correction.paymentAttemptId,
            correction.originalCloseOperationId, correction.collectorId, correction.evidence.actorId,
            correction.previousClassification, correction.correctedTender, correction.recordedAt))
}

internal fun validPaymentCorrectionReportRow(row: LocalBranchReportRowV1, observedAt: UtcInstant): Boolean {
    val detail = row.paymentCorrection ?: return false
    if (row.retailTenderBreakdown != null || row.status != "CLASSIFICATION_CORRECTED" || row.pendingCount != 0 ||
        row.relatedDocumentId == null || row.cashSessionId == null || row.businessDate != null ||
        row.storageRevision != null || row.finalizedAt != null || row.originalPaidDocumentId != null ||
        detail.recordedAt > observedAt) return false
    fun validTender(tender: LocalTenderViewV1): Boolean =
        if (tender.kind == "CASH") tender == LocalTenderViewV1("CASH")
        else ExternalTenderKind.entries.any { it.name == tender.kind } && tender.tenderId != null && tender.policyRevision != null
    if (!validTender(detail.previousClassification) || !validTender(detail.correctedClassification) ||
        detail.previousClassification.kind == detail.correctedClassification.kind &&
            detail.previousClassification.tenderId == detail.correctedClassification.tenderId) return false
    val expected = setOf(LocalBranchReportMetricV1.CLOSING_EXPECTED, LocalBranchReportMetricV1.ACTUAL_COUNT,
        LocalBranchReportMetricV1.VARIANCE, LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA, LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA)
    val amounts = row.amounts.associate { it.metric to it.amount }
    if (amounts.keys != expected || row.amounts.size != expected.size ||
        amounts.values.any { it.currency != Currency.EGP }) return false
    val previous = amounts.getValue(LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA)
    val corrected = amounts.getValue(LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA)
    val closingExpected = amounts.getValue(LocalBranchReportMetricV1.CLOSING_EXPECTED)
    val count = amounts.getValue(LocalBranchReportMetricV1.ACTUAL_COUNT)
    if (previous.minor >= 0 || corrected.minor <= 0 || previous.minor != -corrected.minor ||
        closingExpected.minor < 0 || count.minor < 0 ||
        count - closingExpected != amounts.getValue(LocalBranchReportMetricV1.VARIANCE) ||
        amounts.getValue(LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA).minor != 0L) return false
    val zero = Money(Currency.EGP, 0)
    val cashDelta = (if (detail.correctedClassification.kind == "CASH") corrected else zero) +
        (if (detail.previousClassification.kind == "CASH") previous else zero)
    return amounts.getValue(LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA) == cashDelta
}

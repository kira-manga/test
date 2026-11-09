package com.ukkera.pos.app.gateway.independent.paymentcorrectionreport

import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportPaymentCorrectionV2
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** PCR-F-CASH10 / PCR-F-EXT2735 literal DTO inputs, not durable source or authority. */
internal object PaymentCorrectionReportInputs {
    val company = id(1)
    val branch = id(2)
    val organization = id(3)
    val reader = id(4)
    val device = id(5)
    val originalCollector = id(6)
    val correctionActor = id(7)
    val sale = id(8)
    val payment = id(9)
    val originalSession = id(10)
    val correction = id(11)
    val cardTender = id(12)
    val walletTender = id(13)
    val closeOperation = OperationId("23000000-0000-4000-8000-000000000014")
    val recordedAt = UtcInstant.parseCanonical("2026-11-03T02:00:01Z")
    val observedAt = UtcInstant.parseCanonical("2026-11-03T02:00:02Z")
    val earlierObservedAt = UtcInstant.parseCanonical("2026-11-03T02:00:00Z")
    val laterRecordedAt = UtcInstant.parseCanonical("2026-11-03T02:00:03Z")
    val branchRevision = Revision(37)

    val privateCanaries = listOf(
        "SYNTHETIC_PRIVATE_PAYMENT_REFERENCE_PCR",
        "SYNTHETIC_PRIVATE_PAYMENT_EVIDENCE_PCR",
        "SYNTHETIC_PRIVATE_CUSTOMER_CONTACT_PCR",
        "SYNTHETIC_PRIVATE_PAYMENT_REASON_TEXT_PCR",
    )

    val correctionMetrics = setOf(
        LocalBranchReportMetricV1.CLOSING_EXPECTED,
        LocalBranchReportMetricV1.ACTUAL_COUNT,
        LocalBranchReportMetricV1.VARIANCE,
        LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA,
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA,
    )

    val cashCorrection = LocalBranchReportRowV1(
        correction, Revision(0), relatedDocumentId = sale, cashSessionId = originalSession,
        status = "CLASSIFICATION_CORRECTED", amounts = listOf(
            amount(LocalBranchReportMetricV1.CLOSING_EXPECTED, 235000),
            amount(LocalBranchReportMetricV1.ACTUAL_COUNT, 236000),
            amount(LocalBranchReportMetricV1.VARIANCE, 1000),
            amount(LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, -1000),
            amount(LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA, 1000),
            amount(LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, 1000),
            amount(LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA, 0),
        ), paymentCorrection = LocalBranchReportPaymentCorrectionV2(payment, closeOperation,
            originalCollector, correctionActor, LocalTenderViewV1("CARD", cardTender, Revision(7)),
            LocalTenderViewV1("CASH"), recordedAt),
    )

    val externalCorrection = cashCorrection.copy(
        id = id(20), relatedDocumentId = id(21), cashSessionId = id(22),
        amounts = listOf(
            amount(LocalBranchReportMetricV1.CLOSING_EXPECTED, 62550),
            amount(LocalBranchReportMetricV1.ACTUAL_COUNT, 62550),
            amount(LocalBranchReportMetricV1.VARIANCE, 0),
            amount(LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, -2735),
            amount(LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA, 2735),
            amount(LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, 0),
            amount(LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA, 0),
        ), paymentCorrection = cashCorrection.paymentCorrection!!.copy(
            paymentAttemptId = id(23),
            originalCloseOperationId = OperationId("23000000-0000-4000-8000-000000000024"),
            correctedClassification = LocalTenderViewV1("EXTERNAL_WALLET", walletTender, Revision(2)),
        ),
    )

    /** Immutable original-close fields; this is not a complete cash/period projection. */
    val originalCashClose = LocalBranchReportRowV1(
        originalSession, Revision(4), relatedDocumentId = id(25), cashSessionId = originalSession,
        status = "SUBMITTED_CLOSED", businessDate = BusinessDate.parseCanonical("2026-11-02"),
        amounts = listOf(
            amount(LocalBranchReportMetricV1.CLOSING_EXPECTED, 235000),
            amount(LocalBranchReportMetricV1.ACTUAL_COUNT, 236000),
            amount(LocalBranchReportMetricV1.VARIANCE, 1000),
            amount(LocalBranchReportMetricV1.RETAINED_FLOAT, 50000),
            amount(LocalBranchReportMetricV1.ACTUAL_SAFE_DELIVERY, 186000),
        ),
    )

    fun id(number: Int): DomainId =
        DomainId("23000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    fun amount(metric: LocalBranchReportMetricV1, minor: Long): LocalBranchReportAmountV1 =
        LocalBranchReportAmountV1(metric, Money(Currency.EGP, minor))

    fun changeAmount(row: LocalBranchReportRowV1, metric: LocalBranchReportMetricV1, minor: Long): LocalBranchReportRowV1 {
        require(row.amounts.any { it.metric == metric })
        return row.copy(amounts = row.amounts.map { if (it.metric == metric) amount(metric, minor) else it })
    }
}

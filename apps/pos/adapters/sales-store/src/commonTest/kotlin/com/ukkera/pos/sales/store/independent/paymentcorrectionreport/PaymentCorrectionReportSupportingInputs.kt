package com.ukkera.pos.sales.store.independent.paymentcorrectionreport

import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportProjectionV1
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.sales.store.LocalCashActorEvidenceV1
import com.ukkera.pos.sales.store.LocalPaymentCorrectionCloseViewV1
import com.ukkera.pos.sales.store.LocalPaymentCorrectionViewV1
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.sales.store.paymentCorrectionReportProjection
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal data class PaymentCorrectionReportFixture(
    val fixtureId: String,
    val correction: LocalPaymentCorrectionViewV1,
    val close: LocalPaymentCorrectionCloseViewV1,
    val originalCloseRow: LocalBranchReportRowV1,
    val originalCloseMetrics: List<Pair<LocalBranchReportMetricV1, Long>>,
    val originalClassifications: Map<String, Long>,
    val paidSalesMinor: Long,
    val originalSaleCount: Int,
    val originalCollectionCount: Int,
    val nextSessionId: DomainId,
    val nextOpeningMinor: Long,
    val branchRevision: Revision,
    val observedAt: UtcInstant,
)

/** Exact money from payment-correction-report/fixtures-v1.json; IDs bind its labels to UUID scalars. */
internal object PaymentCorrectionReportSupportingInputs {
    const val PRIVATE_REFERENCE = "SYNTHETIC_PRIVATE_PAYMENT_REFERENCE_PCR"
    const val PRIVATE_EVIDENCE = "SYNTHETIC_PRIVATE_PAYMENT_EVIDENCE_PCR"
    const val PRIVATE_CONTACT = "SYNTHETIC_PRIVATE_CUSTOMER_CONTACT_PCR"
    const val PRIVATE_REASON = "SYNTHETIC_PRIVATE_PAYMENT_REASON_TEXT_PCR"
    val privateCanaries = listOf(PRIVATE_REFERENCE, PRIVATE_EVIDENCE, PRIVATE_CONTACT, PRIVATE_REASON)

    val originalCollectorId = id("73000000", 1)
    val correctionActorId = id("73000000", 2)
    val nextCollectorId = id("73000000", 3)
    val cardId = id("74000000", 1)
    val walletId = id("74000000", 2)
    val card = LocalTenderViewV1("CARD", cardId, Revision(1))
    val wallet = LocalTenderViewV1("EXTERNAL_WALLET", walletId, Revision(1))

    val cashCorrectionMetrics = listOf(
        LocalBranchReportMetricV1.CLOSING_EXPECTED to 235000L,
        LocalBranchReportMetricV1.ACTUAL_COUNT to 236000L,
        LocalBranchReportMetricV1.VARIANCE to 1000L,
        LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA to -1000L,
        LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA to 1000L,
        LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA to 1000L,
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA to 0L,
    )
    val externalCorrectionMetrics = listOf(
        LocalBranchReportMetricV1.CLOSING_EXPECTED to 62550L,
        LocalBranchReportMetricV1.ACTUAL_COUNT to 62550L,
        LocalBranchReportMetricV1.VARIANCE to 0L,
        LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA to -2735L,
        LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA to 2735L,
        LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA to 0L,
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA to 0L,
    )

    private val cashOriginalMetrics = listOf(
        LocalBranchReportMetricV1.OPENING_FLOAT to 100000L,
        LocalBranchReportMetricV1.CASH_RECEIVED to 500000L,
        LocalBranchReportMetricV1.CHANGE_GIVEN to 40000L,
        LocalBranchReportMetricV1.CASH_REFUNDS to 20000L,
        LocalBranchReportMetricV1.PAY_INS to 10000L,
        LocalBranchReportMetricV1.PAY_OUTS to 15000L,
        LocalBranchReportMetricV1.SAFE_DROPS to 300000L,
        LocalBranchReportMetricV1.EXPECTED_CASH to 235000L,
        LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS to 400000L,
        LocalBranchReportMetricV1.CLOSING_EXPECTED to 235000L,
        LocalBranchReportMetricV1.ACTUAL_COUNT to 236000L,
        LocalBranchReportMetricV1.VARIANCE to 1000L,
        LocalBranchReportMetricV1.RETAINED_FLOAT to 50000L,
        LocalBranchReportMetricV1.ACTUAL_SAFE_DELIVERY to 186000L,
    )
    private val externalOriginalMetrics = listOf(
        LocalBranchReportMetricV1.OPENING_FLOAT to 50000L,
        LocalBranchReportMetricV1.CASH_RECEIVED to 15000L,
        LocalBranchReportMetricV1.CHANGE_GIVEN to 2450L,
        LocalBranchReportMetricV1.CASH_REFUNDS to 0L,
        LocalBranchReportMetricV1.PAY_INS to 0L,
        LocalBranchReportMetricV1.PAY_OUTS to 0L,
        LocalBranchReportMetricV1.SAFE_DROPS to 0L,
        LocalBranchReportMetricV1.EXPECTED_CASH to 62550L,
        LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS to 10500L,
        LocalBranchReportMetricV1.CLOSING_EXPECTED to 62550L,
        LocalBranchReportMetricV1.ACTUAL_COUNT to 62550L,
        LocalBranchReportMetricV1.VARIANCE to 0L,
        LocalBranchReportMetricV1.RETAINED_FLOAT to 10000L,
        LocalBranchReportMetricV1.ACTUAL_SAFE_DELIVERY to 52550L,
    )

    val cash10 = fixture(
        fixtureId = "PCR-F-CASH10", prefix = "71000000", correctionMinor = 1000,
        corrected = LocalTenderViewV1("CASH"), originalMetrics = cashOriginalMetrics,
        classifications = mapOf("CASH" to 460000L, "CARD" to 400000L, "WALLET" to 0L),
        paidSalesMinor = 860000, originalCollectionCount = 3, nextOpeningMinor = 50000,
        sourceDate = BusinessDate.parseCanonical("2026-11-02"),
        closeSubmittedAt = UtcInstant.parseCanonical("2026-11-03T01:59:59Z"),
        correctionAt = UtcInstant.parseCanonical("2026-11-03T02:00:01Z"),
        observedAt = UtcInstant.parseCanonical("2026-11-03T02:00:02Z"),
        safeReason = "historical cash received but recorded as card",
    )
    // EXT2735 specifies no timestamp/calendar. These supplied scalar times select no period policy.
    val ext2735 = fixture(
        fixtureId = "PCR-F-EXT2735", prefix = "72000000", correctionMinor = 2735,
        corrected = wallet, originalMetrics = externalOriginalMetrics,
        classifications = mapOf("CASH" to 12550L, "CARD" to 9999L, "WALLET" to 501L),
        paidSalesMinor = 23050, originalCollectionCount = 4, nextOpeningMinor = 10000,
        sourceDate = null,
        closeSubmittedAt = UtcInstant.parseCanonical("2026-11-04T11:59:59Z"),
        correctionAt = UtcInstant.parseCanonical("2026-11-04T12:00:00Z"),
        observedAt = UtcInstant.parseCanonical("2026-11-04T12:00:01Z"),
        safeReason = "historical external payment recorded under wrong method",
    )
    val fixtures = listOf(cash10, ext2735)

    fun money(minor: Long): Money = Money(Currency.EGP, minor)

    fun row(fixture: PaymentCorrectionReportFixture,
        correction: LocalPaymentCorrectionViewV1 = fixture.correction): LocalBranchReportRowV1 =
        paymentCorrectionReportProjection(correction, fixture.close)

    fun page(fixture: PaymentCorrectionReportFixture,
        delivery: LocalBranchReportDeliveryV1 = LocalBranchReportDeliveryV1.VIEW,
        rows: List<LocalBranchReportRowV1> = listOf(row(fixture))): LocalBranchReportResultV1.Page =
        LocalBranchReportResultV1.Page(LocalBranchReportSectionV1.PAYMENT_CORRECTIONS,
            delivery, after = null, rows = rows, nextAfter = null,
            profile = LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2)

    fun originalClosePage(fixture: PaymentCorrectionReportFixture,
        delivery: LocalBranchReportDeliveryV1 = LocalBranchReportDeliveryV1.VIEW): LocalBranchReportResultV1.Page =
        LocalBranchReportResultV1.Page(LocalBranchReportSectionV1.CASH_SESSIONS,
            delivery, after = null, rows = listOf(fixture.originalCloseRow), nextAfter = null)

    /** Available raw free-text inputs are populated; this is not customer/attachment source traversal. */
    fun withPrivateCanaries(): LocalPaymentCorrectionViewV1 = ext2735.correction.copy(
        reason = "$PRIVATE_REASON $PRIVATE_CONTACT", terminal = PRIVATE_EVIDENCE, reference = PRIVATE_REFERENCE,
    )

    private fun fixture(fixtureId: String, prefix: String, correctionMinor: Long,
        corrected: LocalTenderViewV1, originalMetrics: List<Pair<LocalBranchReportMetricV1, Long>>,
        classifications: Map<String, Long>, paidSalesMinor: Long, originalCollectionCount: Int,
        nextOpeningMinor: Long, sourceDate: BusinessDate?, closeSubmittedAt: UtcInstant,
        correctionAt: UtcInstant, observedAt: UtcInstant, safeReason: String): PaymentCorrectionReportFixture {
        val correctionId = id(prefix, 1)
        val paymentId = id(prefix, 2)
        val saleId = id(prefix, 3)
        val sessionId = id(prefix, 4)
        val originalCloseOperation = operation(prefix, 11)
        val metrics = originalMetrics.toMap()
        val close = LocalPaymentCorrectionCloseViewV1(sessionId, originalCloseOperation, Revision(37),
            money(metrics.getValue(LocalBranchReportMetricV1.CLOSING_EXPECTED)),
            money(metrics.getValue(LocalBranchReportMetricV1.EXTERNAL_COLLECTIONS)), money(0),
            money(metrics.getValue(LocalBranchReportMetricV1.ACTUAL_COUNT)),
            money(metrics.getValue(LocalBranchReportMetricV1.VARIANCE)),
            money(metrics.getValue(LocalBranchReportMetricV1.RETAINED_FLOAT)),
            money(metrics.getValue(LocalBranchReportMetricV1.ACTUAL_SAFE_DELIVERY)), id(prefix, 8),
            originalCollectorId, LocalCashActorEvidenceV1(id(prefix, 7), originalCollectorId, closeSubmittedAt),
            if (metrics.getValue(LocalBranchReportMetricV1.VARIANCE) == 0L) null else "original submitted overage")
        val correction = LocalPaymentCorrectionViewV1(saleId, paymentId, sessionId, originalCollectorId,
            operation(prefix, 9), operation(prefix, 10), originalCloseOperation, money(correctionMinor),
            card, card, corrected, money(-correctionMinor), money(correctionMinor), money(0), Revision(0), null,
            LocalCashActorEvidenceV1(correctionId, correctionActorId, correctionAt), safeReason, null, null,
            operation(prefix, 12), correctionAt)
        val originalRow = LocalBranchReportRowV1(sessionId, Revision(38), id(prefix, 5), sessionId,
            "SUBMITTED_CLOSED", sourceDate, amounts = originalMetrics.map { (metric, minor) ->
                LocalBranchReportAmountV1(metric, money(minor))
            })
        return PaymentCorrectionReportFixture(fixtureId, correction, close, originalRow, originalMetrics,
            classifications, paidSalesMinor, 3, originalCollectionCount, id(prefix, 6), nextOpeningMinor,
            Revision(9001), observedAt)
    }

    private fun id(prefix: String, ordinal: Int): DomainId =
        DomainId("$prefix-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    private fun operation(prefix: String, ordinal: Int): OperationId =
        OperationId("$prefix-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
}

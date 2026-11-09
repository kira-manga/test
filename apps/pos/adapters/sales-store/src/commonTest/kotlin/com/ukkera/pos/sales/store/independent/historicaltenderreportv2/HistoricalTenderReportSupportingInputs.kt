package com.ukkera.pos.sales.store.independent.historicaltenderreportv2

import com.ukkera.pos.sales.store.*
import com.ukkera.pos.sales.store.independent.paymentcorrectionreport.PaymentCorrectionReportSupportingInputs
import com.ukkera.pos.types.*

internal data class HistoricalTenderDocument(
    val fixtureId: String,
    val saleId: DomainId,
    val orderId: DomainId,
    val close: LocalPaymentCorrectionCloseViewV1,
    val paidMinor: Long,
    val payments: List<LocalBranchReportRetailTenderPaymentV3>,
    val originalMinor: Map<String, Long>,
    val reconciledMinor: Map<String, Long>,
    val branchRevision: Revision,
    val observedAt: UtcInstant,
)

/** Frozen basis-fixtures-v2.json document amounts; supplied scalars are not native source qualification. */
internal object HistoricalTenderReportSupportingInputs {
    private val previous = PaymentCorrectionReportSupportingInputs
    val card: LocalTenderViewV1 = previous.card
    val wallet: LocalTenderViewV1 = previous.wallet
    val cash: LocalTenderViewV1 = LocalTenderViewV1("CASH")
    val collector: DomainId = previous.originalCollectorId

    val cash10 = HistoricalTenderDocument("PCR-BASIS-F-DOC-CASH10", previous.cash10.correction.saleId,
        id("71000000", 20), previous.cash10.close, 1000,
        listOf(corrected(previous.cash10.correction)),
        buckets(0, 1000, 0), buckets(1000, 0, 0), previous.cash10.branchRevision, previous.cash10.observedAt)

    val split9999 = HistoricalTenderDocument("PCR-BASIS-F-DOC-SPLIT9999", previous.ext2735.correction.saleId,
        id("72000000", 20), previous.ext2735.close, 9999,
        listOf(uncorrected(id("72000000", 21), 7264, card), corrected(previous.ext2735.correction)),
        buckets(0, 9999, 0), buckets(0, 7264, 2735), previous.ext2735.branchRevision, previous.ext2735.observedAt)

    val cash4600 = HistoricalTenderDocument("PCR-BASIS-F-DOC-CASH4600-NO-CORRECTION", id("75000000", 3),
        id("75000000", 20), previous.cash10.close, 460000,
        listOf(uncorrected(id("75000000", 2), 460000, cash)), buckets(460000, 0, 0), buckets(460000, 0, 0),
        previous.cash10.branchRevision, previous.cash10.observedAt)

    private val zeroCountAt = UtcInstant.parseCanonical("2026-11-04T13:00:00Z")
    val zero = HistoricalTenderDocument("PCR-BASIS-F-DOC-ZERO-PRESENT", id("76000000", 3), id("76000000", 20),
        LocalPaymentCorrectionCloseViewV1(id("76000000", 4), operation("76000000", 11), Revision(2),
            money(10000), money(0), money(0), money(10000), money(0), money(10000), money(0), null,
            collector, LocalCashActorEvidenceV1(id("76000000", 7), collector, zeroCountAt), null),
        0, emptyList(), emptyMap(), emptyMap(), Revision(9201), UtcInstant.parseCanonical("2026-11-04T13:00:01Z"))

    val documents: List<HistoricalTenderDocument> = listOf(cash4600, cash10, split9999, zero)
    val chainSaleId: DomainId = id("77000000", 3)
    val chainPaymentId: DomainId = id("77000000", 2)
    val chainOtherPaymentId: DomainId = id("77000000", 21)
    val chainIds: List<DomainId> = (1..4).map { id("77000000", 30 + it) }
    val chainReconciled: List<Map<String, Long>> = listOf(
        buckets(0, 9999, 0), buckets(0, 7264, 2735), buckets(2735, 7264, 0),
        buckets(0, 7264, 2735), buckets(0, 9999, 0))

    fun chain(cut: Int): HistoricalTenderDocument {
        require(cut in 0..4)
        val latest = listOf(card, wallet, cash, wallet, card)[cut]
        val at = UtcInstant.parseCanonical("2026-11-04T12:00:0${cut}Z")
        val corrected = LocalBranchReportRetailTenderPaymentV3(chainPaymentId, money(2735), card, latest,
            if (cut == 0) null else chainIds[cut - 1], cut.toLong(), if (cut == 0) null else at)
        val close = split9999.close.copy(sessionId = id("77000000", 4), originalCloseOperationId = operation("77000000", 11))
        return split9999.copy(fixtureId = "PCR-BASIS-F-DOC-CHAIN9999/cut-$cut", saleId = chainSaleId,
            orderId = id("77000000", 20), close = close,
            payments = listOf(uncorrected(chainOtherPaymentId, 7264, card), corrected),
            reconciledMinor = chainReconciled[cut], branchRevision = Revision(9300L + cut), observedAt = at)
    }

    fun before(fixture: HistoricalTenderDocument): HistoricalTenderDocument = fixture.copy(
        payments = fixture.payments.map { uncorrected(it.paymentAttemptId, it.amount.minor, it.originalClassification) },
        reconciledMinor = fixture.originalMinor)

    fun row(fixture: HistoricalTenderDocument,
        payments: List<LocalBranchReportRetailTenderPaymentV3> = fixture.payments): LocalBranchReportRowV1 =
        retailTenderBreakdownReportProjection(fixture.saleId, Revision(0), fixture.orderId, money(fixture.paidMinor),
            fixture.close, payments)

    fun page(fixture: HistoricalTenderDocument, delivery: LocalBranchReportDeliveryV1 = LocalBranchReportDeliveryV1.VIEW,
        row: LocalBranchReportRowV1 = row(fixture)): LocalBranchReportResultV1.Page = LocalBranchReportResultV1.Page(
        LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS, delivery, null, listOf(row), null,
        LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3, fixture.saleId)

    fun query(fixture: HistoricalTenderDocument,
        delivery: LocalBranchReportDeliveryV1 = LocalBranchReportDeliveryV1.VIEW): LocalBranchReportQueryV1 =
        LocalBranchReportQueryV1(LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS, limit = 1, delivery = delivery,
            profile = LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3, selectedSaleId = fixture.saleId)

    fun actualBuckets(payments: List<LocalBranchReportRetailTenderPaymentV3>, reconciled: Boolean): Map<String, Long> {
        if (payments.isEmpty()) return emptyMap()
        val result = buckets(0, 0, 0).toMutableMap()
        for (payment in payments) {
            val kind = (if (reconciled) payment.reconciledClassification else payment.originalClassification).kind
            val semantic = when (kind) { "CASH" -> "cash"; "CARD" -> "card"; "EXTERNAL_WALLET" -> "wallet"; else -> error(kind) }
            result[semantic] = result.getValue(semantic) + payment.amount.minor
        }
        return result
    }

    fun money(minor: Long): Money = Money(Currency.EGP, minor)
    fun id(prefix: String, suffix: Int): DomainId = DomainId("$prefix-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    fun operation(prefix: String, suffix: Int): OperationId = OperationId(id(prefix, suffix).value)
    private fun buckets(cash: Long, card: Long, wallet: Long): Map<String, Long> = mapOf("cash" to cash, "card" to card, "wallet" to wallet)
    private fun uncorrected(id: DomainId, minor: Long, tender: LocalTenderViewV1): LocalBranchReportRetailTenderPaymentV3 =
        LocalBranchReportRetailTenderPaymentV3(id, money(minor), tender, tender, null, 0, null)
    private fun corrected(source: LocalPaymentCorrectionViewV1): LocalBranchReportRetailTenderPaymentV3 =
        LocalBranchReportRetailTenderPaymentV3(source.paymentAttemptId, source.amount, source.originalTender,
            source.correctedTender, source.id, 1, source.recordedAt)
}

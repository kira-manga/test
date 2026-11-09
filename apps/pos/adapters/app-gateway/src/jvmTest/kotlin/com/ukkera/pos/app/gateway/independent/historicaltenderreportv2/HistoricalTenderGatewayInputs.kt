package com.ukkera.pos.app.gateway.independent.historicaltenderreportv2

import com.ukkera.pos.app.LocalBranchReportExportSelection
import com.ukkera.pos.app.LocalBranchReportSection
import com.ukkera.pos.app.gateway.independent.paymentcorrectionreport.PaymentCorrectionReportInputs
import com.ukkera.pos.app.gateway.independent.reportcompletev2.FrozenWorkflowRead
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*

internal data class HistoricalTenderGatewayDocument(
    val fixtureId: String,
    val row: LocalBranchReportRowV1,
    val paidMinor: Long,
    val closeMinor: List<Long>,
    val originalMinor: Map<String, Long>,
    val reconciledMinor: Map<String, Long>,
    val branchRevision: Revision,
    val observedAt: UtcInstant,
)

/** Frozen basis-fixtures-v2.json amounts and membership, bound to safe wire DATA only.
 * UUIDs/extra scalar times are constructor coordinates, not native sources or current grants. */
internal object HistoricalTenderGatewayInputs {
    val previous = PaymentCorrectionReportInputs
    val card = previous.cashCorrection.paymentCorrection!!.previousClassification
    val wallet = previous.externalCorrection.paymentCorrection!!.correctedClassification
    val cash = LocalTenderViewV1("CASH")

    val cash10 = fromCorrection("PCR-BASIS-F-DOC-CASH10", previous.cashCorrection, 1000,
        listOf(corrected(previous.cashCorrection, 1000)), buckets(0, 1000, 0), buckets(1000, 0, 0))
    val split9999 = fromCorrection("PCR-BASIS-F-DOC-SPLIT9999", previous.externalCorrection, 9999,
        listOf(uncorrected(id("72000000", 21), 7264, card), corrected(previous.externalCorrection, 2735)),
        buckets(0, 9999, 0), buckets(0, 7264, 2735))
    val cash4600 = cash10.copy(fixtureId = "PCR-BASIS-F-DOC-CASH4600-NO-CORRECTION",
        row = cash10.row.copy(id = id("75000000", 3), relatedDocumentId = id("75000000", 20),
            amounts = amounts(460000, 235000, 236000, 1000),
            retailTenderBreakdown = cash10.row.retailTenderBreakdown!!.copy(
                payments = listOf(uncorrected(id("75000000", 2), 460000, cash)))),
        paidMinor = 460000, originalMinor = buckets(460000, 0, 0), reconciledMinor = buckets(460000, 0, 0))
    val zero = HistoricalTenderGatewayDocument("PCR-BASIS-F-DOC-ZERO-PRESENT",
        LocalBranchReportRowV1(id("76000000", 3), Revision(0), id("76000000", 20), id("76000000", 4),
            "PAID_SALE_TENDER_BREAKDOWN", amounts = amounts(0, 10000, 10000, 0),
            retailTenderBreakdown = LocalBranchReportRetailTenderBreakdownV3(
                operation("76000000", 11), previous.originalCollector, emptyList())),
        0, listOf(10000, 10000, 0), emptyMap(), emptyMap(), Revision(9201),
        UtcInstant.parseCanonical("2026-11-04T13:00:01Z"))
    val chain4 = split9999.copy(fixtureId = "PCR-BASIS-F-DOC-CHAIN9999/cut-4",
        row = split9999.row.copy(id = id("77000000", 3), relatedDocumentId = id("77000000", 20),
            cashSessionId = id("77000000", 4), retailTenderBreakdown = LocalBranchReportRetailTenderBreakdownV3(
                operation("77000000", 11), previous.originalCollector, listOf(
                    uncorrected(id("77000000", 21), 7264, card),
                    LocalBranchReportRetailTenderPaymentV3(id("77000000", 2), money(2735), card, card,
                        id("77000000", 34), 4, UtcInstant.parseCanonical("2026-11-04T12:00:04Z"))))),
        reconciledMinor = buckets(0, 9999, 0), branchRevision = Revision(9304),
        observedAt = UtcInstant.parseCanonical("2026-11-04T12:00:04Z"))

    fun before(document: HistoricalTenderGatewayDocument): HistoricalTenderGatewayDocument = document.copy(
        row = document.row.copy(retailTenderBreakdown = document.row.retailTenderBreakdown!!.let { detail ->
            detail.copy(payments = detail.payments.map {
                uncorrected(it.paymentAttemptId, it.amount.minor, it.originalClassification)
            })
        }), reconciledMinor = document.originalMinor, branchRevision = Revision(36),
        observedAt = previous.earlierObservedAt)

    fun page(document: HistoricalTenderGatewayDocument,
        delivery: LocalBranchReportDeliveryV1 = LocalBranchReportDeliveryV1.VIEW,
        row: LocalBranchReportRowV1 = document.row): LocalBranchReportResultV1.Page = LocalBranchReportResultV1.Page(
        LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS, delivery, null, listOf(row), null,
        LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3, row.id)

    fun query(document: HistoricalTenderGatewayDocument,
        delivery: LocalBranchReportDeliveryV1 = LocalBranchReportDeliveryV1.VIEW): LocalBranchReportQueryV1 =
        LocalBranchReportQueryV1(LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS, limit = 1,
            delivery = delivery, profile = LocalBranchReportProjectionV1.RETAIL_TENDER_BREAKDOWN_V3,
            selectedSaleId = document.row.id)

    fun selection(document: HistoricalTenderGatewayDocument): LocalBranchReportExportSelection =
        LocalBranchReportExportSelection(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, null,
            selectedSaleId = document.row.id)

    fun exportReads(document: HistoricalTenderGatewayDocument): List<FrozenWorkflowRead> {
        val first = FrozenWorkflowRead("SELECTED", query(document, LocalBranchReportDeliveryV1.EXPORT),
            page(document, LocalBranchReportDeliveryV1.EXPORT), document.branchRevision, document.observedAt)
        return listOf(first, first.copy(stage = "SELECTED_RECHECK"))
    }

    fun damagedPaymentSum(document: HistoricalTenderGatewayDocument): LocalBranchReportRowV1 {
        val detail = document.row.retailTenderBreakdown!!
        return document.row.copy(retailTenderBreakdown = detail.copy(payments = detail.payments.mapIndexed { index, part ->
            if (index == 0) part.copy(amount = money(7263)) else part
        }))
    }

    fun semantic(kind: String): String = when (kind) {
        "CASH" -> "cash"; "CARD" -> "card"; "EXTERNAL_WALLET" -> "wallet"; else -> error("Unexpected safe classification")
    }
    fun buckets(cash: Long, card: Long, wallet: Long): Map<String, Long> =
        mapOf("cash" to cash, "card" to card, "wallet" to wallet)
    fun money(minor: Long): Money = Money(Currency.EGP, minor)
    fun id(prefix: String, suffix: Int): DomainId = DomainId("$prefix-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
    private fun operation(prefix: String, suffix: Int): OperationId = OperationId(id(prefix, suffix).value)

    private fun fromCorrection(fixtureId: String, source: LocalBranchReportRowV1, paidMinor: Long,
        payments: List<LocalBranchReportRetailTenderPaymentV3>, original: Map<String, Long>,
        reconciled: Map<String, Long>): HistoricalTenderGatewayDocument {
        val detail = source.paymentCorrection!!
        val close = source.amounts.take(3).map { it.amount.minor }
        return HistoricalTenderGatewayDocument(fixtureId,
            LocalBranchReportRowV1(source.relatedDocumentId!!, Revision(0), id("78000000", if (paidMinor == 1000L) 20 else 21),
                source.cashSessionId, "PAID_SALE_TENDER_BREAKDOWN", amounts = amounts(paidMinor, close[0], close[1], close[2]),
                retailTenderBreakdown = LocalBranchReportRetailTenderBreakdownV3(detail.originalCloseOperationId,
                    detail.originalCollectorId, payments)), paidMinor, close, original, reconciled,
            previous.branchRevision, previous.observedAt)
    }
    private fun amounts(paid: Long, expected: Long, count: Long, variance: Long): List<LocalBranchReportAmountV1> = listOf(
        LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL to paid,
        LocalBranchReportMetricV1.CLOSING_EXPECTED to expected,
        LocalBranchReportMetricV1.ACTUAL_COUNT to count,
        LocalBranchReportMetricV1.VARIANCE to variance,
        LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA to 0L,
    ).map { (metric, minor) -> LocalBranchReportAmountV1(metric, money(minor)) }
    private fun uncorrected(id: DomainId, minor: Long, tender: LocalTenderViewV1): LocalBranchReportRetailTenderPaymentV3 =
        LocalBranchReportRetailTenderPaymentV3(id, money(minor), tender, tender, null, 0, null)
    private fun corrected(source: LocalBranchReportRowV1, minor: Long): LocalBranchReportRetailTenderPaymentV3 =
        source.paymentCorrection!!.let { detail -> LocalBranchReportRetailTenderPaymentV3(detail.paymentAttemptId, money(minor),
            detail.previousClassification, detail.correctedClassification, source.id, 1, detail.recordedAt) }
}

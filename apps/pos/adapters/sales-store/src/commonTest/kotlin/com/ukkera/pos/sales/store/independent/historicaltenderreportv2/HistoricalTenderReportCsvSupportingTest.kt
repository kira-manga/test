package com.ukkera.pos.sales.store.independent.historicaltenderreportv2

import com.ukkera.pos.sales.store.*
import com.ukkera.pos.sales.store.independent.paymentcorrectionreport.PaymentCorrectionReportSupportingInputs
import kotlin.test.*

/** CSV semantic/format SUPPORT; no current export grant or native file publication is supplied. */
class HistoricalTenderReportCsvSupportingTest {
    private val f = HistoricalTenderReportSupportingInputs
    private val header = listOf("profile", "section", "branch_revision", "observed_at_utc", "selected_sale_id",
        "document_id", "document_revision", "related_order_id", "cash_session_id", "original_close_operation_id",
        "original_collector_id", "row_kind", "metric", "payment_attempt_id", "tender_kind", "tender_id",
        "tender_policy_revision", "currency", "minor_units", "latest_correction_id", "correction_count",
        "latest_correction_recorded_at_utc")

    @Test fun supportCsvKeepsExactFrozenOriginalAndReconciledPaymentMembershipAndSourceCut() {
        for (fixture in listOf(f.cash4600, f.cash10, f.split9999) + (0..4).map(f::chain)) {
            val bytes = LocalBranchReportCsv.encodePage(f.page(fixture, LocalBranchReportDeliveryV1.EXPORT),
                fixture.branchRevision, fixture.observedAt)
            val records = records(bytes)
            assertEquals(5 + fixture.payments.size * 2, records.size)
            for (record in records) {
                assertEquals("selected-retail-paid-sale-tenders/v3", record.getValue("profile"))
                assertEquals(fixture.saleId.value, record.getValue("selected_sale_id"))
                assertEquals(fixture.saleId.value, record.getValue("document_id"))
                assertEquals(fixture.orderId.value, record.getValue("related_order_id"))
                assertEquals(fixture.close.sessionId.value, record.getValue("cash_session_id"))
                assertEquals(fixture.close.originalCloseOperationId.value, record.getValue("original_close_operation_id"))
                assertEquals(f.collector.value, record.getValue("original_collector_id"))
                assertEquals(fixture.branchRevision.value.toString(), record.getValue("branch_revision"))
                assertEquals(fixture.observedAt.canonicalString(), record.getValue("observed_at_utc"))
                assertEquals("EGP", record.getValue("currency"))
            }
            for ((basis, expected) in listOf("ORIGINAL_RECORDED" to fixture.originalMinor,
                "RECONCILED_HISTORICAL" to fixture.reconciledMinor)) {
                val rows = records.filter { it.getValue("row_kind") == basis }
                assertEquals(fixture.payments.map { it.paymentAttemptId.value }.toSet(),
                    rows.map { it.getValue("payment_attempt_id") }.toSet())
                val actual = mutableMapOf("cash" to 0L, "card" to 0L, "wallet" to 0L)
                for (row in rows) {
                    val semantic = when (row.getValue("tender_kind")) {
                        "CASH" -> "cash"; "CARD" -> "card"; "EXTERNAL_WALLET" -> "wallet"; else -> error("Foreign tender") }
                    actual[semantic] = actual.getValue(semantic) + row.getValue("minor_units").toLong()
                    val payment = fixture.payments.single { it.paymentAttemptId.value == row.getValue("payment_attempt_id") }
                    assertEquals(payment.amount.minor.toString(), row.getValue("minor_units"))
                    assertEquals(payment.latestCorrectionId?.value.orEmpty(), row.getValue("latest_correction_id"))
                    assertEquals(payment.correctionCount.toString(), row.getValue("correction_count"))
                }
                assertEquals(expected, actual)
                assertEquals(fixture.paidMinor, rows.sumOf { it.getValue("minor_units").toLong() })
            }
            val original = records.filter { it.getValue("row_kind") == "UNCHANGED_ORIGINAL_DOCUMENT" }
            assertEquals(mapOf("PAID_DOCUMENT_TOTAL" to fixture.paidMinor, "CLOSING_EXPECTED" to fixture.close.expectedCash.minor,
                "ACTUAL_COUNT" to fixture.close.counted.minor, "VARIANCE" to fixture.close.variance.minor,
                "PHYSICAL_CASH_DELTA" to 0L), original.associate { it.getValue("metric") to it.getValue("minor_units").toLong() })
        }
    }

    @Test fun supportGenuineZeroCsvDoesNotInventPaymentCorrectionOrTenderClassification() {
        val fixture = f.zero
        val rows = records(LocalBranchReportCsv.encodePage(f.page(fixture, LocalBranchReportDeliveryV1.EXPORT),
            fixture.branchRevision, fixture.observedAt))
        assertEquals(7, rows.size)
        val original = rows.filter { it.getValue("row_kind") == "UNCHANGED_ORIGINAL_DOCUMENT" }
        assertEquals(5, original.size)
        val emptyBases = rows.filter { it.getValue("metric") == "EMPTY_PAYMENT_MEMBERSHIP" }
        assertEquals(setOf("ORIGINAL_RECORDED", "RECONCILED_HISTORICAL"), emptyBases.map { it.getValue("row_kind") }.toSet())
        assertEquals(2, emptyBases.size)
        for (row in rows) for (field in listOf("payment_attempt_id", "tender_kind", "tender_id", "tender_policy_revision",
            "latest_correction_id", "correction_count", "latest_correction_recorded_at_utc")) assertEquals("", row.getValue(field))
        assertEquals("0", rows.single { it.getValue("metric") == "PAID_DOCUMENT_TOTAL" }.getValue("minor_units"))
        assertEquals(fixture.saleId.value, rows.first().getValue("selected_sale_id"))
        for (row in emptyBases) {
            assertEquals("", row.getValue("currency"))
            assertEquals("", row.getValue("minor_units"))
        }
    }

    @Test fun supportCsvRejectsUnverifiedScalarsLegacyDispatchAndPrivateFreeTextFields() {
        val fixture = f.split9999
        assertFailsWith<IllegalArgumentException> {
            LocalBranchReportCsv.encodePage(f.page(fixture), fixture.branchRevision, fixture.observedAt)
        }
        val page = f.page(fixture, LocalBranchReportDeliveryV1.EXPORT)
        assertFailsWith<IllegalArgumentException> { LocalBranchReportCsvV1.encodePage(page, fixture.branchRevision, fixture.observedAt) }
        val malformed = f.row(fixture, fixture.payments.drop(1))
        assertFailsWith<IllegalArgumentException> {
            LocalBranchReportCsv.encodePage(f.page(fixture, LocalBranchReportDeliveryV1.EXPORT, malformed),
                fixture.branchRevision, fixture.observedAt)
        }
        val source = PaymentCorrectionReportSupportingInputs.withPrivateCanaries()
        val raw = localSalesEncode(source, LocalPaymentCorrectionViewV1.serializer()).copyBytes().decodeToString()
        val safePayment = LocalBranchReportRetailTenderPaymentV3(source.paymentAttemptId, source.amount,
            source.originalTender, source.correctedTender, source.id, 1, source.recordedAt)
        val csv = LocalBranchReportCsv.encodePage(f.page(fixture, LocalBranchReportDeliveryV1.EXPORT,
            f.row(fixture, listOf(fixture.payments.first(), safePayment))), fixture.branchRevision, fixture.observedAt).decodeToString()
        for (canary in PaymentCorrectionReportSupportingInputs.privateCanaries) {
            assertTrue(canary in raw)
            assertFalse(canary in csv)
        }
        for (field in listOf("reference", "terminal", "reason", "payerReference", "customer_contact")) assertFalse(field in header)
    }

    private fun records(bytes: ByteArray): List<Map<String, String>> {
        val text = bytes.decodeToString()
        assertTrue(text.endsWith("\r\n"))
        val fields = text.removeSuffix("\r\n").split("\r\n").map { line ->
            assertTrue(line.startsWith('"') && line.endsWith('"'))
            line.removePrefix("\"").removeSuffix("\"").split("\",\"").map { it.replace("\"\"", "\"") }
        }
        assertEquals(header, fields.first())
        for (row in fields) assertEquals(22, row.size)
        return fields.drop(1).map { header.zip(it).toMap() }
    }
}

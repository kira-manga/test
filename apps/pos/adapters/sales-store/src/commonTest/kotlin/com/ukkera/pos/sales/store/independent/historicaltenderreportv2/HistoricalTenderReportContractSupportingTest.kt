package com.ukkera.pos.sales.store.independent.historicaltenderreportv2

import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.*
import kotlin.test.*

/** Selected-source codec/projection SUPPORT; no native admission, financial persistence or period qualification. */
class HistoricalTenderReportContractSupportingTest {
    private val f = HistoricalTenderReportSupportingInputs

    @Test fun supportSelectedDocumentQueryAndEchoRetainExactIdentityWithoutCursorOrScopeExpansion() {
        for (fixture in f.documents) for (delivery in LocalBranchReportDeliveryV1.entries) {
            val query = f.query(fixture, delivery)
            val body = LocalBranchReportReadCodec.encode(query)
            val wire = Json.parseToJsonElement(body.copyBytes().decodeToString()).jsonObject
            assertEquals(fixture.saleId.value, wire.getValue("selectedSaleId").jsonPrimitive.content)
            assertEquals("RETAIL_TENDER_BREAKDOWNS", wire.getValue("section").jsonPrimitive.content)
            assertEquals("RETAIL_TENDER_BREAKDOWN_V3", wire.getValue("profile").jsonPrimitive.content)
            assertEquals(1, wire.getValue("limit").jsonPrimitive.int)
            assertEquals(JsonNull, wire.getValue("after"))
            assertEquals(body, LocalBranchReportReadHandler(LocalStockRepository()).normalize(body))
            val page = f.page(fixture, delivery)
            val encoded = localSalesEncode(page, LocalBranchReportResultV1.serializer())
            assertEquals(page, LocalBranchReportReadCodec.decode(encoded))
            assertEquals(fixture.saleId, page.rows.single().id)
            assertNull(page.after)
            assertNull(page.nextAfter)
            assertNull(page.rows.single().businessDate)
        }
    }

    @Test fun supportMalformedSelectedSelectorsAndOtherExportScopesCannotFallBackToAnEmptyReport() {
        val fixture = f.cash10
        val query = f.query(fixture)
        for (candidate in listOf<() -> LocalBranchReportQueryV1>(
            { query.copy(selectedSaleId = null) }, { query.copy(after = f.split9999.saleId) },
            { query.copy(limit = 5) }, { query.copy(profile = null) },
            { query.copy(profile = LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2) },
            { LocalBranchReportQueryV1(LocalBranchReportSectionV1.PAID_SALES, selectedSaleId = fixture.saleId) }))
            assertFailsWith<IllegalArgumentException> { candidate() }
        val page = f.page(fixture)
        for (candidate in listOf<() -> LocalBranchReportResultV1.Page>(
            { page.copy(selectedSaleId = f.split9999.saleId) }, { page.copy(rows = emptyList()) },
            { page.copy(rows = listOf(f.row(f.split9999))) }, { page.copy(nextAfter = fixture.saleId) }))
            assertFailsWith<IllegalArgumentException> { candidate() }
        assertFailsWith<IllegalArgumentException> {
            LocalBranchReportExportRequestV1.Begin(f.id("78000000", 1), f.query(fixture, LocalBranchReportDeliveryV1.EXPORT),
                f.id("78000000", 2), LocalBranchReportExportDestinationV1.DESKTOP_LOCAL_FILE,
                LocalBranchReportExportCoverageV1.COMPLETE_CURRENT_SECTION_V2)
        }
    }

    @Test fun supportFrozenDocumentAndEveryChainCutConserveBothBasesAndUnchangedOriginalClose() {
        val fixtures = f.documents + f.before(f.cash10) + (0..4).map(f::chain)
        for (fixture in fixtures) {
            val row = f.row(fixture)
            val detail = assertNotNull(row.retailTenderBreakdown)
            assertTrue(LocalBranchReportReadCodec.validRetailTenderBreakdownRow(row, fixture.observedAt), fixture.fixtureId)
            assertEquals(fixture.originalMinor, f.actualBuckets(detail.payments, reconciled = false))
            assertEquals(fixture.reconciledMinor, f.actualBuckets(detail.payments, reconciled = true))
            assertEquals(fixture.paidMinor, detail.payments.sumOf { it.amount.minor })
            assertEquals(fixture.close.originalCloseOperationId, detail.originalCloseOperationId)
            assertEquals(f.collector, detail.originalCollectorId)
            assertEquals(mapOf(LocalBranchReportMetricV1.PAID_DOCUMENT_TOTAL to fixture.paidMinor,
                LocalBranchReportMetricV1.CLOSING_EXPECTED to fixture.close.expectedCash.minor,
                LocalBranchReportMetricV1.ACTUAL_COUNT to fixture.close.counted.minor,
                LocalBranchReportMetricV1.VARIANCE to fixture.close.variance.minor,
                LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA to 0L), row.amounts.associate { it.metric to it.amount.minor })
            assertNull(row.paymentCorrection)
        }
        val restored = f.row(f.chain(4)).retailTenderBreakdown!!.payments.last()
        assertEquals(f.card, restored.reconciledClassification)
        assertEquals(4L, restored.correctionCount)
        assertEquals(f.chainIds.last(), restored.latestCorrectionId)
        assertTrue(f.zero.payments.isEmpty())
    }

    @Test fun supportDamagedOrUnverifiedMembershipNeverPassesTheCompleteRowGuard() {
        val fixture = f.split9999
        val row = f.row(fixture)
        val detail = assertNotNull(row.retailTenderBreakdown)
        val original = detail.payments.first()
        val corrected = detail.payments.last()
        val vectors = listOf(
            detail.copy(payments = listOf(corrected)),
            detail.copy(payments = listOf(original.copy(amount = f.money(7263)), corrected)),
            detail.copy(payments = listOf(original, corrected.copy(amount = f.money(2736)))),
            detail.copy(payments = emptyList()),
            detail.copy(payments = listOf(original, corrected.copy(latestCorrectionId = null))),
            detail.copy(payments = listOf(original, corrected.copy(latestCorrectionRecordedAt = null))),
            detail.copy(payments = listOf(original, corrected.copy(correctionCount = 0))),
            detail.copy(payments = listOf(original, corrected.copy(paymentAttemptId = original.paymentAttemptId))))
        for (candidate in vectors) assertFalse(LocalBranchReportReadCodec.validRetailTenderBreakdownRow(
            row.copy(retailTenderBreakdown = candidate), fixture.observedAt))
        val zeroRow = f.row(f.zero)
        assertFalse(LocalBranchReportReadCodec.validRetailTenderBreakdownRow(zeroRow.copy(
            retailTenderBreakdown = zeroRow.retailTenderBreakdown!!.copy(payments = listOf(
                original.copy(amount = f.money(0))))), f.zero.observedAt))
        assertFalse(LocalBranchReportReadCodec.validRetailTenderBreakdownRow(row.copy(
            amounts = row.amounts.map { if (it.metric == LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA)
                it.copy(amount = f.money(1)) else it }), fixture.observedAt))
    }

    @Test fun supportActualInstalledReaderClassifiesSelectedReportsWithoutGrantingOtherWorkflows() {
        val installed = LocalSalesHandlers.reads(LocalStockRepository())
        val request = LocalBranchReportReadCodec.request(f.query(f.cash10))
        val reader = installed.single { it.key == request.query }
        val normalized = reader.normalize(request.payload)
        assertEquals(request.payload, normalized)
        for (profile in LocalMasterRpcWorkflowProfile.entries)
            assertEquals(profile == LocalMasterRpcWorkflowProfile.REPORTS, reader.supportsRpcWorkflow(profile, normalized))
    }
}

package com.ukkera.pos.sales.store.independent.paymentcorrectionreport

import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportCsv
import com.ukkera.pos.sales.store.LocalBranchReportCsvV1
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportProjectionV1
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionCsvV2
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.sales.store.LocalPaymentCorrectionViewV1
import com.ukkera.pos.sales.store.independent.reportcompletev2.FrozenCompleteCsvSupplementInputs
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Formatting SUPPORT only; supplied EXPORT scalars are not current authorization or native file IO. */
class PaymentCorrectionReportCsvSupportingTest {
    private val inputs = PaymentCorrectionReportSupportingInputs
    private val correctionProfile = "current-local-payment-classifications-page/v2"
    private val correctionHeader = FrozenCompleteCsvSupplementInputs.header + listOf(
        "payment_attempt_id", "original_close_operation_id", "original_collector_id", "correction_actor_id",
        "previous_tender_kind", "previous_tender_id", "previous_tender_policy_revision", "corrected_tender_kind",
        "corrected_tender_id", "corrected_tender_policy_revision", "correction_recorded_at_utc",
    )

    @Test
    fun supportCash10CsvKeepsOriginalCloseBytesAndExactLinkedExplanation() {
        val fixture = inputs.cash10
        val page = inputs.page(fixture, LocalBranchReportDeliveryV1.EXPORT)
        val actual = LocalBranchReportCsv.encodePage(page, fixture.branchRevision, fixture.observedAt)
        assertContentEquals(expectedCorrectionCsv(fixture, inputs.cashCorrectionMetrics), actual)
        assertOriginalCloseBytesPreserved(fixture)
    }

    @Test
    fun supportExt2735CsvKeepsFractionalDeltasAndOriginalZeroVariance() {
        val fixture = inputs.ext2735
        val page = inputs.page(fixture, LocalBranchReportDeliveryV1.EXPORT)
        val actual = LocalBranchReportCsv.encodePage(page, fixture.branchRevision, fixture.observedAt)
        assertContentEquals(expectedCorrectionCsv(fixture, inputs.externalCorrectionMetrics), actual)
        assertOriginalCloseBytesPreserved(fixture)
    }

    @Test
    fun supportEmptyCorrectionCsvCarriesExplicitProfileWithoutFabricatedZeroRow() {
        val fixture = inputs.cash10
        val page = inputs.page(fixture, LocalBranchReportDeliveryV1.EXPORT, rows = emptyList())
        val expected = referenceCsv(listOf(correctionHeader,
            listOf(correctionProfile, "PAYMENT_CORRECTIONS", "9001", "2026-11-03T02:00:02Z", "", "") + List(21) { "" }))
        val actual = LocalBranchReportCsv.encodePage(page, fixture.branchRevision, fixture.observedAt)
        assertContentEquals(expected, actual)
        assertEquals(2, actual.decodeToString().removeSuffix("\r\n").split("\r\n").size)
        assertFailsWith<IllegalArgumentException> { LocalBranchReportCsvV1.encodePage(page, fixture.branchRevision, fixture.observedAt) }
    }

    @Test
    fun supportLegacyCompleteAndEmptyContainersKeepPreexistingExactBytes() {
        for (fixture in listOf(FrozenCompleteCsvSupplementInputs.f12, FrozenCompleteCsvSupplementInputs.empty)) {
            val csv = LocalBranchReportSectionCsvV2(fixture.query)
            for (bound in fixture.pages) csv.append(bound.page, bound.revision, bound.observedAt)
            val actual = csv.finish(fixture.confirmedRevision, fixture.confirmedAt)
            assertContentEquals(checkNotNull(fixture.expectedBytes), actual)
            assertEquals(checkNotNull(fixture.expectedByteCount), actual.size)
        }
    }

    @Test
    fun supportLegacyPageDispatchKeepsFrozenHeaderProfileAndEmptyRecordBytes() {
        for (fixture in listOf(FrozenCompleteCsvSupplementInputs.f12, FrozenCompleteCsvSupplementInputs.empty)) {
            val records = checkNotNull(fixture.expectedBytes).decodeToString().removeSuffix("\r\n").split("\r\n")
            assertEquals(if (fixture === FrozenCompleteCsvSupplementInputs.f12) 14 else 3, records.size)
            var offset = 1
            for (bound in fixture.pages) {
                val count = if (bound.page.rows.isEmpty()) 1 else bound.page.rows.sumOf { it.amounts.size }
                val expected = (listOf(records.first()) + records.subList(offset, offset + count))
                    .joinToString("\r\n", postfix = "\r\n").encodeToByteArray()
                assertContentEquals(expected, LocalBranchReportCsvV1.encodePage(bound.page, bound.revision, bound.observedAt))
                assertContentEquals(expected, LocalBranchReportCsv.encodePage(bound.page, bound.revision, bound.observedAt))
                offset += count
            }
            assertEquals(records.lastIndex, offset)
        }
    }

    @Test
    fun supportOldProfilesRejectCorrectionMetadataAndUnlabeledCorrectionMetrics() {
        val fixture = inputs.cash10
        val detail = checkNotNull(inputs.row(fixture).paymentCorrection)
        val correctionMetrics = listOf(
            LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA to -1000L,
            LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA to 1000L,
            LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA to 1000L,
            LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA to 0L,
        )
        for (section in LocalBranchReportSectionV1.entries.filter {
            it != LocalBranchReportSectionV1.PAYMENT_CORRECTIONS &&
                it != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS
        }) {
            val old = oldFamilyRow(section)
            val withMetadata = old.copy(paymentCorrection = detail)
            val candidates = listOf(withMetadata) + correctionMetrics.map { (metric, minor) ->
                old.copy(paymentCorrection = null, amounts = listOf(LocalBranchReportAmountV1(metric, inputs.money(minor))))
            }
            for (row in candidates) {
                val page = LocalBranchReportResultV1.Page(section, LocalBranchReportDeliveryV1.EXPORT, null, listOf(row), null)
                assertFailsWith<IllegalArgumentException> { LocalBranchReportCsvV1.encodePage(page, fixture.branchRevision, fixture.observedAt) }
                assertFailsWith<IllegalArgumentException> { LocalBranchReportCsv.encodePage(page, fixture.branchRevision, fixture.observedAt) }
                if (section == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS) {
                    val payout = page.copy(profile = LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2)
                    assertFailsWith<IllegalArgumentException> {
                        LocalBranchReportCsv.encodePage(payout, fixture.branchRevision, fixture.observedAt)
                    }
                }
            }
        }
    }

    @Test
    fun supportCorrectionCsvRequiresExportAndConsistentObservedScalars() {
        val fixture = inputs.cash10
        val row = inputs.row(fixture)
        assertFailsWith<IllegalArgumentException> {
            LocalBranchReportCsv.encodePage(inputs.page(fixture), fixture.branchRevision, fixture.observedAt)
        }
        val invalid = listOf(
            row.copy(paymentCorrection = null), row.copy(relatedDocumentId = null), row.copy(storageRevision = Revision(1)),
            row.copy(amounts = row.amounts.map {
                if (it.metric == LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA) it.copy(amount = inputs.money(1)) else it
            }),
            row.copy(amounts = row.amounts.filter { it.metric != LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA }),
        )
        for (candidate in invalid) assertFailsWith<IllegalArgumentException> {
            LocalBranchReportCsv.encodePage(inputs.page(fixture, LocalBranchReportDeliveryV1.EXPORT, listOf(candidate)),
                fixture.branchRevision, fixture.observedAt)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalBranchReportCsv.encodePage(inputs.page(fixture, LocalBranchReportDeliveryV1.EXPORT),
                fixture.branchRevision, fixture.close.countEvidence.occurredAt)
        }
    }

    @Test
    fun supportCsvDoesNotSerializePopulatedPrivateFreeTextCanaries() {
        val fixture = inputs.ext2735
        val source = inputs.withPrivateCanaries()
        val sourceText = localSalesEncode(source, LocalPaymentCorrectionViewV1.serializer()).copyBytes().decodeToString()
        val row = inputs.row(fixture, source)
        val page = inputs.page(fixture, LocalBranchReportDeliveryV1.EXPORT, listOf(row))
        val actual = LocalBranchReportCsv.encodePage(page, fixture.branchRevision, fixture.observedAt)
        assertContentEquals(expectedCorrectionCsv(fixture, inputs.externalCorrectionMetrics), actual)
        val csvText = actual.decodeToString()
        for (canary in inputs.privateCanaries) {
            assertTrue(canary in sourceText)
            assertFalse(canary in csvText)
        }
    }

    /** Frozen amount inputs are preserved by formatting, not asserted to have been hydrated from a master. */
    private fun assertOriginalCloseBytesPreserved(fixture: PaymentCorrectionReportFixture) {
        val page = inputs.originalClosePage(fixture, LocalBranchReportDeliveryV1.EXPORT)
        val before = LocalBranchReportCsv.encodePage(page, fixture.branchRevision, fixture.observedAt)
        val row = fixture.originalCloseRow
        val records = listOf(FrozenCompleteCsvSupplementInputs.header) + fixture.originalCloseMetrics.map { (metric, minor) ->
            listOf("current-local-documents-page/v1", "CASH_SESSIONS", fixture.branchRevision.value.toString(),
                fixture.observedAt.canonicalString(), "", "", row.id.value, "38", row.relatedDocumentId?.value.orEmpty(),
                fixture.close.sessionId.value, "SUBMITTED_CLOSED", row.businessDate?.canonicalString().orEmpty(), "0",
                metric.name, "EGP", minor.toString())
        }
        assertContentEquals(referenceCsv(records), before)
        inputs.row(fixture)
        assertContentEquals(before, LocalBranchReportCsv.encodePage(page, fixture.branchRevision, fixture.observedAt))
    }

    private fun expectedCorrectionCsv(fixture: PaymentCorrectionReportFixture,
        expectedMetrics: List<Pair<LocalBranchReportMetricV1, Long>>): ByteArray {
        val correction = fixture.correction
        val corrected = correction.correctedTender
        val metadata = listOf(correction.paymentAttemptId.value, fixture.close.originalCloseOperationId.value,
            inputs.originalCollectorId.value, inputs.correctionActorId.value, "CARD", inputs.cardId.value, "1",
            corrected.kind, corrected.tenderId?.value.orEmpty(), corrected.policyRevision?.value?.toString().orEmpty(),
            correction.recordedAt.canonicalString())
        val records = listOf(correctionHeader) + expectedMetrics.map { (metric, minor) ->
            listOf(correctionProfile, "PAYMENT_CORRECTIONS", fixture.branchRevision.value.toString(), fixture.observedAt.canonicalString(),
                "", "", correction.id.value, "0", correction.saleId.value, correction.sessionId.value,
                "CLASSIFICATION_CORRECTED", "", "0", metric.name, "EGP", minor.toString()) + metadata
        }
        return referenceCsv(records)
    }

    /** Keep all original FOOD scalar requirements valid, so the intentionally foreign family is the refusal. */
    private fun oldFamilyRow(section: LocalBranchReportSectionV1): LocalBranchReportRowV1 {
        val fixture = inputs.cash10
        val food = section in setOf(LocalBranchReportSectionV1.FOOD_COLLECTIONS,
            LocalBranchReportSectionV1.FOOD_PAID_SALES, LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)
        return fixture.originalCloseRow.copy(revision = Revision(0), businessDate = null,
            amounts = listOf(LocalBranchReportAmountV1(LocalBranchReportMetricV1.CLOSING_EXPECTED, inputs.money(235000))),
            storageRevision = if (food) Revision(1) else null,
            finalizedAt = if (section == LocalBranchReportSectionV1.FOOD_PAID_SALES) fixture.correction.recordedAt else null)
    }

    /** Reference records use the fixed quoted-field/CRLF contract, never current formatter output. */
    private fun referenceCsv(records: List<List<String>>): ByteArray = records.joinToString("") { fields ->
        fields.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\r\n"
    }.encodeToByteArray()
}

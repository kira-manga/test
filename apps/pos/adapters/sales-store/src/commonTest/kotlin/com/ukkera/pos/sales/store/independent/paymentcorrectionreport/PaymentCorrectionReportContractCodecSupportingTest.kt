package com.ukkera.pos.sales.store.independent.paymentcorrectionreport

import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportExportCodecV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportProjectionV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.sales.store.LocalPaymentCorrectionCodec
import com.ukkera.pos.sales.store.LocalPaymentCorrectionQueryV1
import com.ukkera.pos.sales.store.LocalSalesHandlers
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.sales.store.independent.reportcompletev2.FrozenCompleteCsvSupplementInputs
import com.ukkera.pos.sales.store.independent.reportcompletev2.FrozenCompleteReportInputs
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Typed profile/codec/scalar SUPPORT; no admitted read, source authority, export grant or RPC execution. */
class PaymentCorrectionReportContractCodecSupportingTest {
    private val inputs = PaymentCorrectionReportSupportingInputs

    @Test
    fun supportCorrectionQueryRequiresItsExactExplicitProfile() {
        val query = LocalBranchReportQueryV1(LocalBranchReportSectionV1.PAYMENT_CORRECTIONS,
            profile = LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2)
        val encoded = LocalBranchReportReadCodec.encode(query)
        assertEquals("{\"section\":\"PAYMENT_CORRECTIONS\",\"after\":null,\"limit\":5,\"delivery\":\"VIEW\",\"profile\":\"PAYMENT_CORRECTION_V2\"}",
            encoded.copyBytes().decodeToString())
        val request = LocalBranchReportReadCodec.request(query)
        assertEquals("sales.branch-report.read", request.query.name)
        assertEquals(1, request.query.version)
        assertEquals(encoded, request.payload)
    }

    @Test
    fun supportQueryAndPageRejectIncompatibleSectionProfiles() {
        for (section in LocalBranchReportSectionV1.entries) {
            if (section == LocalBranchReportSectionV1.PAYMENT_CORRECTIONS) {
                assertFailsWith<IllegalArgumentException> { LocalBranchReportQueryV1(section) }
                assertFailsWith<IllegalArgumentException> {
                    LocalBranchReportResultV1.Page(section, LocalBranchReportDeliveryV1.VIEW, null, emptyList(), null)
                }
                assertFailsWith<IllegalArgumentException> {
                    LocalBranchReportQueryV1(section, profile = LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2)
                }
                assertFailsWith<IllegalArgumentException> {
                    LocalBranchReportResultV1.Page(section, LocalBranchReportDeliveryV1.VIEW, null, emptyList(), null,
                        LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2)
                }
            } else {
                assertFailsWith<IllegalArgumentException> {
                    LocalBranchReportQueryV1(section, profile = LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2)
                }
                assertFailsWith<IllegalArgumentException> {
                    LocalBranchReportResultV1.Page(section, LocalBranchReportDeliveryV1.VIEW, null, emptyList(), null,
                        LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2)
                }
            }
        }
    }

    @Test
    fun supportTypedCanonicalRoundtripKeepsOriginalAndCorrectionFiguresSeparate() {
        for (fixture in inputs.fixtures) {
            val original = inputs.originalClosePage(fixture)
            val originalBody = encode(original)
            val originalRoundtrip = assertIs<LocalBranchReportResultV1.Page>(LocalBranchReportReadCodec.decode(originalBody))
            assertEquals(original, originalRoundtrip)
            assertEquals(fixture.originalCloseMetrics.map { (metric, minor) ->
                LocalBranchReportAmountV1(metric, inputs.money(minor))
            }, originalRoundtrip.rows.single().amounts)

            val page = inputs.page(fixture)
            val encoded = encode(page)
            val roundtrip = assertIs<LocalBranchReportResultV1.Page>(LocalBranchReportReadCodec.decode(encoded))
            assertEquals(page, roundtrip)
            assertEquals(encoded, encode(roundtrip))
            assertEquals(originalBody, encode(original))
            val text = encoded.copyBytes().decodeToString()
            val metadata = Json.parseToJsonElement(text).jsonObject.getValue("rows").jsonArray.single()
                .jsonObject.getValue("paymentCorrection").jsonObject
            assertEquals(setOf("paymentAttemptId", "originalCloseOperationId", "originalCollectorId",
                "correctionActorId", "previousClassification", "correctedClassification", "recordedAt"), metadata.keys)
            for (amount in roundtrip.rows.single().amounts) assertTrue("\"minor\":\"${amount.amount.minor}\"" in text)
            for (canary in inputs.privateCanaries) assertFalse(canary in text)
        }
    }

    @Test
    fun supportLegacyQueryAndPageDoNotAcquireCorrectionFieldsOrProfile() {
        val fixture = FrozenCompleteCsvSupplementInputs.f12
        assertEquals("{\"section\":\"SAFE_TRANSFERS\",\"after\":null,\"limit\":5,\"delivery\":\"EXPORT\"}",
            LocalBranchReportReadCodec.encode(fixture.query).copyBytes().decodeToString())
        val page = fixture.pages.first().page
        val body = encode(page)
        val text = body.copyBytes().decodeToString()
        assertFalse("\"profile\"" in text)
        assertFalse("\"paymentCorrection\"" in text)
        assertEquals(page, LocalBranchReportReadCodec.decode(body))
    }

    @Test
    fun supportSharedGuardAcceptsBothExactFrozenLinkedRowsAtTheirObservationBoundary() {
        for (fixture in inputs.fixtures) {
            val row = inputs.row(fixture)
            assertTrue(LocalBranchReportReadCodec.validPaymentCorrectionRow(row, fixture.observedAt))
            assertTrue(LocalBranchReportReadCodec.validPaymentCorrectionRow(row, fixture.correction.recordedAt))
        }
    }

    @Test
    fun supportSharedGuardRejectsMissingLinksFuturePostingAndForeignMetadata() {
        val fixture = inputs.cash10
        val row = inputs.row(fixture)
        val detail = checkNotNull(row.paymentCorrection)
        val invalid = listOf(
            row.copy(paymentCorrection = null), row.copy(relatedDocumentId = null), row.copy(cashSessionId = null),
            row.copy(status = "PAID_DOCUMENT"), row.copy(pendingCount = 1),
            row.copy(businessDate = BusinessDate.parseCanonical("2026-11-02")),
            row.copy(storageRevision = Revision(1)), row.copy(finalizedAt = fixture.correction.recordedAt),
            row.copy(originalPaidDocumentId = fixture.correction.saleId),
            row.copy(paymentCorrection = detail.copy(recordedAt = UtcInstant.parseCanonical("2027-01-01T00:00:00Z"))),
        )
        for (candidate in invalid) assertFalse(LocalBranchReportReadCodec.validPaymentCorrectionRow(candidate, fixture.observedAt))
        assertFalse(LocalBranchReportReadCodec.validPaymentCorrectionRow(row,
            UtcInstant.parseCanonical("2026-11-03T02:00:00Z")))
    }

    @Test
    fun supportSharedGuardRejectsIncompatibleOrUnchangedTenderMetadata() {
        val fixture = inputs.cash10
        val row = inputs.row(fixture)
        val detail = checkNotNull(row.paymentCorrection)
        val invalidDetails = listOf(
            detail.copy(previousClassification = inputs.card.copy(tenderId = null)),
            detail.copy(previousClassification = inputs.card.copy(policyRevision = null)),
            detail.copy(previousClassification = inputs.card.copy(kind = "UNKNOWN")),
            detail.copy(correctedClassification = inputs.card),
            detail.copy(correctedClassification = LocalTenderViewV1("CASH", inputs.cardId)),
            detail.copy(correctedClassification = LocalTenderViewV1("CASH", policyRevision = Revision(1))),
            detail.copy(correctedClassification = LocalTenderViewV1("CASH", requiresReference = true)),
        )
        for (candidate in invalidDetails) assertFalse(LocalBranchReportReadCodec.validPaymentCorrectionRow(
            row.copy(paymentCorrection = candidate), fixture.observedAt))
        val external = inputs.row(inputs.ext2735)
        val externalDetail = checkNotNull(external.paymentCorrection)
        assertFalse(LocalBranchReportReadCodec.validPaymentCorrectionRow(external.copy(paymentCorrection =
            externalDetail.copy(correctedClassification = inputs.wallet.copy(tenderId = null))), inputs.ext2735.observedAt))
        assertFalse(LocalBranchReportReadCodec.validPaymentCorrectionRow(external.copy(paymentCorrection =
            externalDetail.copy(correctedClassification = inputs.wallet.copy(policyRevision = null))), inputs.ext2735.observedAt))
    }

    @Test
    fun supportSharedGuardRejectsNonconservationPhysicalMoneyAndReplacedCloseFigures() {
        val fixture = inputs.cash10
        val row = inputs.row(fixture)
        val invalid = listOf(
            replaceMetric(row, LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, 1000),
            replaceMetric(row, LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, 0),
            replaceMetric(row, LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, -1001),
            replaceMetric(row, LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA, 1001),
            replaceMetric(row, LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, 0),
            replaceMetric(row, LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA, 1),
            replaceMetric(row, LocalBranchReportMetricV1.VARIANCE, 0),
            replaceMetric(row, LocalBranchReportMetricV1.ACTUAL_COUNT, 235000),
            replaceMetric(row, LocalBranchReportMetricV1.CLOSING_EXPECTED, -1),
            row.copy(amounts = row.amounts.filter { it.metric != LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA }),
            row.copy(amounts = row.amounts + LocalBranchReportAmountV1(LocalBranchReportMetricV1.ACTUAL_COLLECTIONS, inputs.money(0))),
        )
        for (candidate in invalid) assertFalse(LocalBranchReportReadCodec.validPaymentCorrectionRow(candidate, fixture.observedAt))
        assertFailsWith<IllegalArgumentException> { row.copy(amounts = row.amounts + row.amounts.first()) }
        val external = inputs.row(inputs.ext2735)
        assertFalse(LocalBranchReportReadCodec.validPaymentCorrectionRow(
            replaceMetric(external, LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, 2735), inputs.ext2735.observedAt))
    }

    @Test
    fun supportCanonicalDecodeRejectsPrivateKeysAlternativeMoneyAndMissingProfile() {
        val canonical = encode(inputs.page(inputs.ext2735)).copyBytes().decodeToString()
        val malformed = listOf(
            replaceExactlyOnce(canonical, "\"paymentCorrection\":{",
                "\"paymentCorrection\":{\"reference\":\"${inputs.PRIVATE_REFERENCE}\","),
            replaceExactlyOnce(canonical, "\"minor\":\"2735\"", "\"minor\":2735"),
            replaceExactlyOnce(canonical, "\"minor\":\"2735\"", "\"minor\":2.735e3"),
            replaceExactlyOnce(canonical, "\"minor\":\"2735\"", "\"minor\":\"02735\""),
            replaceExactlyOnce(canonical, ",\"profile\":\"PAYMENT_CORRECTION_V2\"", ""),
            replaceExactlyOnce(canonical, "\"profile\":\"PAYMENT_CORRECTION_V2\"", "\"profile\":null"),
            replaceExactlyOnce(canonical, "\"profile\":\"PAYMENT_CORRECTION_V2\"", "\"profile\":\"FOOD_REFUND_PAYOUT_V2\""),
            replaceExactlyOnce(canonical, "\"profile\":\"PAYMENT_CORRECTION_V2\"", "\"profile\":\"PAYMENT_CORRECTION_V3\""),
            " $canonical",
        )
        for (text in malformed) assertFailsWith<IllegalArgumentException> {
            LocalBranchReportReadCodec.decode(StoredBytes(text.encodeToByteArray()))
        }
    }

    @Test
    fun supportInstalledReadCompositionPreservesOnlyItsFixedRpcProfile() {
        // Construction supplies no original stock proof or native/admitted RPC fixture.
        val installed = LocalSalesHandlers.reads(LocalStockRepository())
        val requests = listOf(
            LocalBranchReportReadCodec.request(LocalBranchReportQueryV1(LocalBranchReportSectionV1.PAYMENT_CORRECTIONS,
                profile = LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2)) to LocalMasterRpcWorkflowProfile.REPORTS,
            LocalBranchReportExportCodecV1.read(FrozenCompleteReportInputs.exportId) to LocalMasterRpcWorkflowProfile.REPORTS,
            LocalPaymentCorrectionCodec.request(LocalPaymentCorrectionQueryV1.Source(inputs.cash10.correction.saleId)) to
                LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS,
        )
        assertEquals(listOf("sales.branch-report.read", "sales.branch-report-export.read", "sales.payment-correction.read"),
            requests.map { it.first.query.name })
        for ((request, expectedProfile) in requests) {
            assertEquals(1, request.query.version)
            val handler = installed.single { it.key == request.query }
            val normalized = handler.normalize(request.payload)
            assertEquals(request.payload, normalized)
            for (profile in LocalMasterRpcWorkflowProfile.entries) {
                assertEquals(profile == expectedProfile, handler.supportsRpcWorkflow(profile, normalized),
                    "${request.query.name} must not acquire $profile")
            }
        }
    }

    private fun encode(page: LocalBranchReportResultV1.Page): StoredBytes =
        localSalesEncode(page, LocalBranchReportResultV1.serializer())

    private fun replaceMetric(row: LocalBranchReportRowV1, metric: LocalBranchReportMetricV1,
        minor: Long): LocalBranchReportRowV1 = row.copy(amounts = row.amounts.map {
        if (it.metric == metric) LocalBranchReportAmountV1(metric, inputs.money(minor)) else it
    })

    private fun replaceExactlyOnce(text: String, original: String, replacement: String): String {
        check(text.windowed(original.length).count { it == original } == 1)
        return text.replace(original, replacement)
    }
}

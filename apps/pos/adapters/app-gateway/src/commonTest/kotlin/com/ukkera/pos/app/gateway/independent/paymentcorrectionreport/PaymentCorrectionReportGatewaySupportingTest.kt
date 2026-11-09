package com.ukkera.pos.app.gateway.independent.paymentcorrectionreport

import com.ukkera.pos.app.LocalBranchReportSection
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.gateway.GatewayFailure
import com.ukkera.pos.app.gateway.currentReportProfile
import com.ukkera.pos.app.gateway.validateBranchReportRows
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportProjectionV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen payment-correction-report v1 scalar/profile SUPPORT only. Controlled
 * DTOs do not qualify any whole PCR-REP-01–07 case, authority or financial effect.
 */
class PaymentCorrectionReportGatewaySupportingTest {
    private val input = PaymentCorrectionReportInputs
    private val section = LocalBranchReportSectionV1.PAYMENT_CORRECTIONS
    private val profile = LocalBranchReportProjectionV1.PAYMENT_CORRECTION_V2
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test
    fun pcrRep01SupportCorrectionSectionIsAppendedAndRequiresItsExplicitProfile() {
        val legacy = listOf("CASH_SESSIONS", "COLLECTIONS", "PAID_SALES", "ACCEPTED_RETURNS",
            "SAFE_TRANSFERS", "FOOD_COLLECTIONS", "FOOD_PAID_SALES", "FOOD_REFUND_OBLIGATIONS")
        assertEquals(legacy + "PAYMENT_CORRECTIONS", LocalBranchReportSectionV1.entries.take(section.ordinal + 1).map { it.name })
        assertEquals(legacy + "PAYMENT_CORRECTIONS", LocalBranchReportSection.entries.take(LocalBranchReportSection.PAYMENT_CORRECTIONS.ordinal + 1).map { it.name })
        assertEquals(profile, currentReportProfile(section))
        assertFailsWith<IllegalArgumentException> { LocalBranchReportQueryV1(section) }

        LocalBranchReportDeliveryV1.entries.forEach { delivery ->
            val query = LocalBranchReportQueryV1(section, delivery = delivery, profile = profile)
            val encoded = LocalBranchReportReadCodec.encode(query)
            assertEquals(query, wire.decodeFromString(LocalBranchReportQueryV1.serializer(), encoded.copyBytes().decodeToString()))
            assertTrue("\"profile\":\"PAYMENT_CORRECTION_V2\"" in encoded.copyBytes().decodeToString())
            val empty = LocalBranchReportResultV1.Page(section, delivery, null, emptyList(), null, profile)
            assertEquals(profile, empty.profile)
            assertTrue(empty.rows.isEmpty())
        }
        validateBranchReportRows(section, emptyList(), input.earlierObservedAt, profile)
    }

    @Test
    fun pcrRep05SupportProfilesCannotRelabelOtherDocumentFamilies() {
        unavailable { validateBranchReportRows(section, emptyList(), input.observedAt) }
        unavailable { validateBranchReportRows(section, emptyList(), input.observedAt,
            LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2) }
        LocalBranchReportSectionV1.entries.filter { it != section && it != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS }.forEach { legacy ->
            unavailable { validateBranchReportRows(legacy, emptyList(), input.observedAt, profile) }
            assertFailsWith<IllegalArgumentException> { LocalBranchReportQueryV1(legacy, profile = profile) }
            if (legacy == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)
                assertEquals(LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2, currentReportProfile(legacy))
            else assertNull(currentReportProfile(legacy))
        }
    }

    @Test
    fun pcrRep05SupportLegacyNullAndOriginalRefundProfileCodecsRemainCompatible() {
        LocalBranchReportSectionV1.entries.filter { it != section && it != LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS }.forEach { legacy ->
            LocalBranchReportDeliveryV1.entries.forEach { delivery ->
                val query = LocalBranchReportQueryV1(legacy, delivery = delivery)
                val encoded = LocalBranchReportReadCodec.encode(query).copyBytes().decodeToString()
                assertFalseProfileField(encoded)
                assertEquals(query, wire.decodeFromString(LocalBranchReportQueryV1.serializer(), encoded))
                val empty = LocalBranchReportResultV1.Page(legacy, delivery, null, emptyList(), null)
                val pageBytes = wire.encodeToString(LocalBranchReportResultV1.serializer(), empty).encodeToByteArray()
                assertFalseProfileField(pageBytes.decodeToString())
                assertEquals(empty, LocalBranchReportReadCodec.decode(StoredBytes(pageBytes)))
            }
        }
        val refund = LocalBranchReportQueryV1(LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS,
            profile = LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2)
        val encoded = LocalBranchReportReadCodec.encode(refund).copyBytes().decodeToString()
        assertTrue("\"profile\":\"FOOD_REFUND_PAYOUT_V2\"" in encoded)
        assertEquals(refund, wire.decodeFromString(LocalBranchReportQueryV1.serializer(), encoded))
    }

    @Test
    fun pcrRep01SupportFirstCashCorrectionKeepsCountedCloseAndZeroPhysicalMovement() {
        validate(input.cashCorrection)
        assertEquals(0L, input.cashCorrection.revision.value)
        assertEquals(input.correctionMetrics, input.cashCorrection.amounts.map { it.metric }.toSet())
        assertEquals(listOf(235000L, 236000L, 1000L, -1000L, 1000L, 1000L, 0L),
            input.cashCorrection.amounts.map { it.amount.minor })
        assertEquals(input.originalCollector, input.cashCorrection.paymentCorrection?.originalCollectorId)
        assertEquals(input.correctionActor, input.cashCorrection.paymentCorrection?.correctionActorId)
        assertEquals(input.recordedAt, input.cashCorrection.paymentCorrection?.recordedAt)
    }

    @Test
    fun pcrRep02SupportFractionalExternalCorrectionConservesClassificationWithoutCashVariance() {
        validate(input.externalCorrection)
        assertEquals(listOf(62550L, 62550L, 0L, -2735L, 2735L, 0L, 0L),
            input.externalCorrection.amounts.map { it.amount.minor })
        assertEquals("CARD", input.externalCorrection.paymentCorrection?.previousClassification?.kind)
        assertEquals("EXTERNAL_WALLET", input.externalCorrection.paymentCorrection?.correctedClassification?.kind)
    }

    @Test
    fun pcrRep05SupportCorrectionMetadataAndStatusMustBeExact() {
        val row = input.cashCorrection
        val malformed = listOf(
            row.copy(paymentCorrection = null),
            row.copy(relatedDocumentId = null),
            row.copy(cashSessionId = null),
            row.copy(businessDate = BusinessDate.parseCanonical("2026-11-02")),
            row.copy(storageRevision = Revision(1)),
            row.copy(finalizedAt = input.recordedAt),
            row.copy(originalPaidDocumentId = input.sale),
            row.copy(pendingCount = 1),
            row.copy(status = "SUBMITTED_CLOSED"),
        )
        malformed.forEach { unavailable { validate(it) } }
    }

    @Test
    fun pcrRep01And02SupportMissingExtraOrDuplicateMetricsAreNotAValidSummary() {
        val row = input.cashCorrection
        input.correctionMetrics.forEach { missing ->
            unavailable { validate(row.copy(amounts = row.amounts.filter { it.metric != missing })) }
        }
        unavailable { validate(row.copy(amounts = row.amounts +
            input.amount(LocalBranchReportMetricV1.ACTUAL_COLLECTIONS, 0))) }
        assertFailsWith<IllegalArgumentException> { row.copy(amounts = row.amounts + row.amounts.first()) }
    }

    @Test
    fun pcrRep01And02SupportBrokenCloseConservationAndPhysicalArithmeticFailValidation() {
        val row = input.cashCorrection
        val malformed = listOf(
            input.changeAmount(row, LocalBranchReportMetricV1.CLOSING_EXPECTED, 235001),
            input.changeAmount(row, LocalBranchReportMetricV1.ACTUAL_COUNT, 236001),
            input.changeAmount(row, LocalBranchReportMetricV1.VARIANCE, 0),
            input.changeAmount(row, LocalBranchReportMetricV1.CLOSING_EXPECTED, -1),
            input.changeAmount(row, LocalBranchReportMetricV1.ACTUAL_COUNT, -1),
            input.changeAmount(row, LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, -999),
            input.changeAmount(row, LocalBranchReportMetricV1.CORRECTED_CLASSIFICATION_DELTA, 999),
            input.changeAmount(row, LocalBranchReportMetricV1.PREVIOUS_CLASSIFICATION_DELTA, 1000),
            input.changeAmount(row, LocalBranchReportMetricV1.PHYSICAL_CASH_DELTA, 1000),
            input.changeAmount(row, LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, 0),
            input.changeAmount(input.externalCorrection, LocalBranchReportMetricV1.CASH_CLASSIFICATION_DELTA, 2735),
        )
        malformed.forEach { unavailable { validate(it) } }
    }

    @Test
    fun pcrRep03And07SupportCorrectionCannotBeLaterThanItsObservedReadCut() {
        unavailable { validateBranchReportRows(section, listOf(input.cashCorrection), input.earlierObservedAt, profile) }
        unavailable { validate(input.cashCorrection.copy(paymentCorrection =
            input.cashCorrection.paymentCorrection!!.copy(recordedAt = input.laterRecordedAt))) }
        validate(input.cashCorrection.copy(paymentCorrection =
            input.cashCorrection.paymentCorrection!!.copy(recordedAt = input.observedAt)))
    }

    @Test
    fun pcrRep05SupportLegacyCashSummaryRejectsInjectedCorrectionMetadata() {
        validateBranchReportRows(LocalBranchReportSectionV1.CASH_SESSIONS,
            listOf(input.originalCashClose), input.observedAt)
        unavailable { validateBranchReportRows(LocalBranchReportSectionV1.CASH_SESSIONS,
            listOf(input.originalCashClose.copy(paymentCorrection = input.cashCorrection.paymentCorrection)),
            input.observedAt) }
    }

    @Test
    fun pcrRep05SupportTenderMetadataCannotTurnUnknownOrUnchangedClassificationIntoCorrection() {
        val row = input.cashCorrection
        val detail = row.paymentCorrection!!
        val malformed = listOf(
            detail.copy(previousClassification = LocalTenderViewV1("UNKNOWN", input.cardTender, Revision(7))),
            detail.copy(previousClassification = LocalTenderViewV1("CARD", policyRevision = Revision(7))),
            detail.copy(previousClassification = LocalTenderViewV1("CARD", input.cardTender)),
            detail.copy(correctedClassification = LocalTenderViewV1("CASH", input.cardTender, Revision(7))),
            detail.copy(correctedClassification = LocalTenderViewV1("CASH", requiresReference = true)),
            detail.copy(correctedClassification = detail.previousClassification),
        )
        malformed.forEach { unavailable { validate(row.copy(paymentCorrection = it)) } }
    }

    private fun validate(row: LocalBranchReportRowV1) =
        validateBranchReportRows(section, listOf(row), input.observedAt, profile)

    private fun unavailable(action: () -> Unit) {
        assertEquals(LocalServiceProblem.UNAVAILABLE, assertFailsWith<GatewayFailure>(block = action).problem)
    }

    private fun assertFalseProfileField(encoded: String) {
        assertTrue("\"profile\":" !in encoded)
    }
}

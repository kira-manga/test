package com.ukkera.pos.sales.store.independent.pay10classificationcorrection

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * commerce-v1 COM-PAY-10-I/E contract SUPPORT only. The frozen wrong-card
 * amount, original close and later classification deltas remain exact.
 * Source/history DTOs below are synthetic carriers, not authenticated original
 * financial history. These checks do not qualify native authority, atomic
 * persistence, current-drawer conservation, admitted RPC, restart or installed UI.
 * Auxiliary IDs/revisions fill required carrier slots under the frozen alias rule.
 */
class Pay10ClassificationCorrectionContractSupportingTest {
    private val input get() = Pay10ContractInputs

    @Test
    fun supportFrozenRequestRetainsExactAmountAndHistoricalEvidence() {
        val body = LocalPaymentCorrectionCodec.encode(input.request)
        val restored = LocalPaymentCorrectionCodec.decode(body)
        assertEquals(input.sale, restored.saleId)
        assertEquals(input.payment, restored.paymentAttemptId)
        assertEquals(Money(Currency.EGP, 10000), restored.expectedAmount)
        assertEquals(LocalTenderSelectionV1.Cash, restored.correctedTender)
        assertEquals("synthetic receipt/count investigation", restored.reason)
        assertEquals(input.request.evidence, restored.evidence)
        assertEquals(null, restored.expectedLatestCorrectionId)
        assertEquals(body, LocalPaymentCorrectionCodec.normalize(body))
    }

    @Test
    fun supportMismatchAmount10001IsNotRoundedIntoOriginal10000Body() {
        val original = LocalPaymentCorrectionCodec.encode(input.request)
        val changed = LocalPaymentCorrectionCodec.encode(input.request.copy(expectedAmount = Money(Currency.EGP, 10001)))
        assertNotEquals(original, changed)
        assertEquals(Money(Currency.EGP, 10001), LocalPaymentCorrectionCodec.decode(changed).expectedAmount)
        assertEquals(Money(Currency.EGP, 10000), LocalPaymentCorrectionCodec.decode(original).expectedAmount)
        // This establishes exact semantic bytes, not durable same-ID mismatch handling.
    }

    @Test
    fun supportNonpositiveOverflowFractionalAndForeignCurrencyAmountsAreRejected() {
        for (minor in listOf(0L, -1L)) assertFailsWith<IllegalArgumentException> {
            input.request.copy(expectedAmount = Money(Currency.EGP, minor))
        }
        val body = LocalPaymentCorrectionCodec.encode(input.request)
        for (minor in listOf("9223372036854775808", "10000.1", "-0")) {
            assertFailsWith<IllegalArgumentException> {
                LocalPaymentCorrectionCodec.decode(requestAmount(body, minor))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            LocalPaymentCorrectionCodec.decode(requestAmount(body, "10000", currency = "USD"))
        }
    }

    @Test
    fun supportMissingEvidenceBlankReasonAndCashTerminalMetadataAreRejected() {
        assertFailsWith<IllegalArgumentException> { input.request.copy(reason = " ") }
        assertFailsWith<IllegalArgumentException> { input.request.copy(terminal = "old-card-terminal") }
        assertFailsWith<IllegalArgumentException> { input.request.copy(reference = "old-card-reference") }
        val body = LocalPaymentCorrectionCodec.encode(input.request)
        val objectValue = Json.parseToJsonElement(body.text()).jsonObject
        for (field in listOf("evidence", "reason", "expectedAmount")) assertFailsWith<IllegalArgumentException> {
            LocalPaymentCorrectionCodec.decode(bytes(JsonObject(objectValue - field).toString()))
        }
    }

    @Test
    fun supportUnknownDuplicateTruncatedOrNoncanonicalBodiesCannotBecomeCorrectionInstructions() {
        val canonical = LocalPaymentCorrectionCodec.encode(input.request)
        val text = canonical.text()
        val invalid = listOf(text.dropLast(1), " " + text,
            text.dropLast(1) + ",\"newPhysicalMovement\":true}",
            text.dropLast(1) + ",\"reason\":\"synthetic receipt/count investigation\"}")
        for (value in invalid) {
            assertNotEquals(text, value)
            assertFailsWith<IllegalArgumentException> { LocalPaymentCorrectionCodec.normalize(bytes(value)) }
        }
        assertEquals(canonical, LocalPaymentCorrectionCodec.normalize(canonical))
    }

    @Test
    fun supportSourceCarrierPreservesOriginalCardAttestationAndSubmittedClose() {
        val encoded = readBytes(LocalPaymentCorrectionReadResultV1.Source(input.source))
        val restored = assertIs<LocalPaymentCorrectionReadResultV1.Source>(LocalPaymentCorrectionCodec.read(encoded)).source
        assertOriginalClose(restored.originalClose)
        val payment = restored.payments.single()
        assertEquals(Money(Currency.EGP, 10000), payment.amount)
        assertEquals("CARD", payment.originalTender.kind)
        assertEquals(payment.originalTender, payment.latestClassification)
        assertEquals(input.collector, payment.originalAttestation.actorId)
        assertEquals(input.collector, restored.collectorId)
        assertEquals(input.intentOperation, payment.originalIntentOperationId)
        assertEquals(input.recordingOperation, payment.originalRecordingOperationId)
        assertEquals(null, payment.latestCorrectionId)
    }

    @Test
    fun supportHistoryCarrierKeepsOriginalCloseSeparateFromLinkedSignedAdjustment() {
        val encoded = readBytes(LocalPaymentCorrectionReadResultV1.History(input.page))
        val page = assertIs<LocalPaymentCorrectionReadResultV1.History>(LocalPaymentCorrectionCodec.read(encoded)).page
        assertOriginalClose(page.originalClose)
        assertEquals(Money(Currency.EGP, 10000), page.payment.amount)
        assertEquals("CARD", page.payment.originalTender.kind)
        val correction = page.corrections.single()
        assertEquals(Money(Currency.EGP, -10000), correction.previousClassificationDelta)
        assertEquals(Money(Currency.EGP, 10000), correction.correctedClassificationDelta)
        assertEquals(Money(Currency.EGP, 0), correction.physicalCashDelta)
        assertEquals("CARD", correction.originalTender.kind)
        assertEquals("CARD", correction.previousClassification.kind)
        assertEquals("CASH", correction.correctedTender.kind)
        assertEquals(input.collector, correction.collectorId)
        assertEquals(input.manager, correction.evidence.actorId)
        assertEquals(page.originalClose.originalCloseOperationId, correction.originalCloseOperationId)
        assertEquals(page.payment.originalRecordingOperationId, correction.originalRecordingOperationId)
        assertEquals(OperationId("a40f4978-c4fa-5844-a9a6-5b33b006e22b"), correction.operationId)
    }

    @Test
    fun supportRecordedResultCannotInventPositiveOrNegativePhysicalCashMovement() {
        val original = LocalPaymentCorrectionCodec.result(LocalPaymentCorrectionResultV1.Recorded(input.correction, input.sessionRevision))
        val result = assertIs<LocalPaymentCorrectionResultV1.Recorded>(LocalPaymentCorrectionCodec.result(original))
        assertEquals(Money(Currency.EGP, 0), result.correction.physicalCashDelta)
        for (minor in listOf("10000", "-10000")) assertFailsWith<IllegalArgumentException> {
            LocalPaymentCorrectionCodec.result(correctionAmount(original, "physicalCashDelta", minor))
        }
        assertEquals(input.sessionRevision, result.sessionRevision)
    }

    @Test
    fun supportRecordedResultRejectsAnUnbalancedHistoricalClassification() {
        val original = LocalPaymentCorrectionCodec.result(LocalPaymentCorrectionResultV1.Recorded(input.correction, input.sessionRevision))
        val invalid = listOf(correctionAmount(original, "previousClassificationDelta", "-9999"),
            correctionAmount(original, "correctedClassificationDelta", "10001"),
            correctionAmount(original, "amount", "10001"))
        for (body in invalid) assertFailsWith<IllegalArgumentException> { LocalPaymentCorrectionCodec.result(body) }
        assertEquals(input.correction,
            assertIs<LocalPaymentCorrectionResultV1.Recorded>(LocalPaymentCorrectionCodec.result(original)).correction)
    }

    @Test
    fun supportHistorySelectorIsBoundedAndRetainsExactSalePaymentAndCursor() {
        for (limit in listOf(1, 5)) {
            val query = LocalPaymentCorrectionQueryV1.History(input.sale, input.payment, input.correction.id, limit)
            val request = LocalPaymentCorrectionCodec.request(query)
            val restored = localSalesDecode(request.payload, LocalPaymentCorrectionQueryV1.serializer())
            assertEquals(query, restored)
            assertEquals(LocalPaymentCorrectionCodec.QUERY, request.query)
        }
        for (limit in listOf(0, 6)) assertFailsWith<IllegalArgumentException> {
            LocalPaymentCorrectionQueryV1.History(input.sale, input.payment, limit = limit)
        }
        assertTrue(input.sale != input.payment)
        // Exact-source cursor authority and pagination results require the production handler.
    }

    private fun assertOriginalClose(value: LocalPaymentCorrectionCloseViewV1) {
        assertEquals(Money(Currency.EGP, 100000), value.expectedCash)
        assertEquals(Money(Currency.EGP, 110000), value.counted)
        assertEquals(Money(Currency.EGP, 10000), value.variance)
        assertEquals(input.closeOperation, value.originalCloseOperationId)
        assertEquals(input.collector, value.submittedBy)
        assertEquals(input.collector, value.countEvidence.actorId)
    }

    private fun requestAmount(body: StoredBytes, minor: String, currency: String = "EGP"): StoredBytes {
        val root = Json.parseToJsonElement(body.text()).jsonObject
        return bytes(JsonObject(root + ("expectedAmount" to moneyObject(currency, minor))).toString())
    }

    private fun correctionAmount(body: StoredBytes, field: String, minor: String): StoredBytes {
        val root = Json.parseToJsonElement(body.text()).jsonObject
        val correction = root.getValue("correction").jsonObject
        return bytes(JsonObject(root + ("correction" to JsonObject(correction + (field to moneyObject("EGP", minor))))).toString())
    }

    private fun moneyObject(currency: String, minor: String) = JsonObject(linkedMapOf(
        "currency" to JsonPrimitive(currency), "minor" to JsonPrimitive(minor)))
    private fun StoredBytes.text() = copyBytes().decodeToString()
    private fun bytes(value: String) = StoredBytes(value.encodeToByteArray())
    private fun readBytes(value: LocalPaymentCorrectionReadResultV1) =
        localSalesEncode(value, LocalPaymentCorrectionReadResultV1.serializer())
}

private object Pay10ContractInputs {
    val sale = DomainId("8f6d317d-2e03-5c79-91d9-8c8dd7e5a308")
    val payment = DomainId("442df5db-d4e2-57ca-89ea-c864263f3440")
    val order = DomainId("347f9aa6-dcdc-59cd-bd68-35aa9ddc5708")
    val session = DomainId("b2be07ab-e9b9-5898-bb88-6442ab466aff")
    val collector = DomainId("24a96c96-d193-5950-98ed-5e90d0a5a59e")
    val manager = DomainId("d98be03d-544f-5317-9838-e4a170d7aca2")
    val closeOperation = OperationId("d493f524-100d-57b5-b120-9128099762dd")
    val intentOperation = OperationId("1c018b5d-ed2c-5ee2-a532-1fe5c93a6399")
    val recordingOperation = OperationId("c7ca4bc0-6d94-54d9-8745-7797b6619b4d")
    val sessionRevision = Revision(4)
    val yesterday = UtcInstant.parseCanonical("2026-11-03T10:00:00Z")
    val today = UtcInstant.parseCanonical("2026-11-04T10:00:00Z")
    val originalTender = LocalTenderViewV1("CARD", DomainId("e0e13c6a-63d7-5d10-9c31-7fd89d6c6f27"), Revision(1))
    val correctionEvidence = LocalCashEvidenceV1(DomainId("ecd09578-5be9-5fb9-8d2b-f676bdefa3c1"), today)
    val request = LocalPaymentCorrectionRequestV1(sale, payment, sessionRevision, null,
        Money(Currency.EGP, 10000), LocalTenderSelectionV1.Cash, correctionEvidence,
        "synthetic receipt/count investigation")
    val close = LocalPaymentCorrectionCloseViewV1(session, closeOperation, Revision(2),
        Money(Currency.EGP, 100000), Money(Currency.EGP, 10000), Money(Currency.EGP, 0),
        Money(Currency.EGP, 110000), Money(Currency.EGP, 10000), Money(Currency.EGP, 0),
        Money(Currency.EGP, 110000), DomainId("38e7e881-577c-5423-af05-e31afdd8382e"), collector,
        LocalCashActorEvidenceV1(DomainId("3e743c06-01f1-5d2b-9e2c-bcbc50ae5d7a"), collector, yesterday),
        "synthetic original overage")
    val originalPayment = LocalPaymentCorrectionPaymentViewV1(payment, Money(Currency.EGP, 10000), originalTender,
        intentOperation, recordingOperation,
        LocalCashActorEvidenceV1(DomainId("0b12b510-703c-55b6-a49b-23008e691563"), collector, yesterday),
        null, null, null, null, originalTender, 0)
    val source = LocalPaymentCorrectionSourceViewV1(sale, order, collector, session, sessionRevision, close, listOf(originalPayment))
    val correction = LocalPaymentCorrectionViewV1(sale, payment, session, collector, intentOperation,
        recordingOperation, closeOperation, Money(Currency.EGP, 10000), originalTender, originalTender,
        LocalTenderViewV1("CASH"), Money(Currency.EGP, -10000), Money(Currency.EGP, 10000), Money(Currency.EGP, 0),
        Revision(0), null, LocalCashActorEvidenceV1(correctionEvidence.evidenceId, manager, today),
        "synthetic receipt/count investigation", null, null,
        OperationId("a40f4978-c4fa-5844-a9a6-5b33b006e22b"), today)
    val page = LocalPaymentCorrectionsPageV1(sale, session, sessionRevision, close,
        originalPayment.copy(latestCorrectionId = correction.id, latestClassification = LocalTenderViewV1("CASH"), totalCorrections = 1),
        null, listOf(correction), null)
}

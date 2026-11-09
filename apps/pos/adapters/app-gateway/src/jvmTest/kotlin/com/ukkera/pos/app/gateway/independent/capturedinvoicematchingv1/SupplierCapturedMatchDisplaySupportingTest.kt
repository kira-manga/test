package com.ukkera.pos.app.gateway.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen SCD v1.0.0 supporting projection/refusal contributions only.
 * Wires real shared gateway/profile/codec/validation code over controlled readonly
 * wire replies. No Master authorization, actual source histories, durable matching,
 * ledger preservation, admitted RPC, native presentation or owner result is proved.
 * SCD-01-I4, SCD-02-I2 and SCD-03-I2 are not injectable independent contradictions
 * at this wire boundary; see README-supplier-captured-match-display.md.
 * Source handoff status: NOT RUN. SCD-05 remains entirely unbound here.
 */
class SupplierCapturedMatchDisplaySupportingTest {
    @Test
    fun scd01AllGoodsStayMatchedWithPositiveUnresolvedCharge() = runTest(timeout = 5.seconds) {
        assertAuthorizedScdFixture(scdDisplayExpectations[0])
    }

    @Test
    fun scd02PresentZeroChargeSurvivesAuthorizedCostProjection() = runTest(timeout = 5.seconds) {
        assertAuthorizedScdFixture(scdDisplayExpectations[1])
    }

    @Test
    fun scd03ZeroPricedRemainderStaysPartialAndVisible() = runTest(timeout = 5.seconds) {
        assertAuthorizedScdFixture(scdDisplayExpectations[2])
    }

    @Test
    fun scd01I1ToI3ContradictoryGoodsStatusMoneyAndEligibilityAreUnavailable() = runTest(timeout = 5.seconds) {
        val variants = listOf<Pair<String, (LocalCapturedSupplierInvoiceRecognitionV2) -> LocalCapturedSupplierInvoiceRecognitionV2>>(
            "SCD-01-I1" to { it.copy(status = "PARTIALLY_MATCHED_RECOGNIZED") },
            "SCD-01-I2" to { it.copy(unmatchedClaim = Money(Currency.EGP, 0)) },
            "SCD-01-I3" to { it.copy(settlementEligible = true) },
        )
        for ((caseId, change) in variants) assertRejectedScdProjection(scdDisplayExpectations[0], true, caseId) {
            it.copy(recognition = change(assertNotNull(it.recognition)))
        }
    }

    @Test
    fun scd02I1MoneyEqualityCannotMakeAnUnresolvedZeroChargeEligible() = runTest(timeout = 5.seconds) {
        assertRejectedScdProjection(scdDisplayExpectations[1], true, "SCD-02-I1") {
            it.copy(recognition = assertNotNull(it.recognition).copy(settlementEligible = true))
        }
    }

    @Test
    fun scd03I1AndI3ZeroUnmatchedMoneyCannotErasePartialGoodsOrGrantEligibility() = runTest(timeout = 5.seconds) {
        val variants = listOf<Pair<String, (LocalCapturedSupplierInvoiceRecognitionV2) -> LocalCapturedSupplierInvoiceRecognitionV2>>(
            "SCD-03-I1" to { it.copy(status = "MATCHED_RECOGNIZED") },
            "SCD-03-I3" to { it.copy(settlementEligible = true) },
        )
        for ((caseId, change) in variants) assertRejectedScdProjection(scdDisplayExpectations[2], true, caseId) {
            it.copy(recognition = change(assertNotNull(it.recognition)))
        }
    }

    @Test
    fun scd04CostRedactionPreservesEachGoodsIdentityQuantityAndStatus() = runTest(timeout = 5.seconds) {
        for (expected in scdDisplayExpectations) {
            val f = SupplierCapturedMatchDisplayGatewayFixture(expected.fixtureId)
            val originalInput = f.capture
            val cost = f.readScdDetails(costs = true)
            assertScdAuthorizedProjection(f, expected, cost)
            // A controlled Current reply is not an actual Master revocation or stale-client test.
            f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(costs = false))
            val redacted = f.readScdDetails(costs = false)
            assertScdRedactedProjection(f, expected, redacted)
            val operationalCostRecords = cost.page.records.filter { it.reference.kind != LocalRecordKind.PURCHASE_CHARGE }
                .map { record -> record.copy(values = record.values.filterNot {
                    it.label.english in scdSensitiveRecordLabels || it.value.english.contains("EGP")
                }) }
            assertEquals(operationalCostRecords, redacted.page.records)
            assertEquals(listOf(true, false), f.endpoint.reads.map {
                LocalPurchasingCodec.decodeQuery(it.payload).includeCosts
            })
            assertEquals(originalInput, f.capture)
            assertScdGatewayReadOnly(f)
        }
    }

    @Test
    fun scd04I1RawClaimLeakIsUnavailableForAllFixtures() = runTest(timeout = 5.seconds) {
        for (expected in scdDisplayExpectations) assertRejectedScdProjection(expected, false, "SCD-04-I1") {
            it.copy(capture = assertNotNull(it.capture).copy(supplierClaim = Money(Currency.EGP, expected.rawClaimMinor)))
        }
    }

    @Test
    fun scd04I2PositiveAndZeroChargeLeaksAreUnavailable() = runTest(timeout = 5.seconds) {
        for (expected in scdDisplayExpectations.filter { it.chargeIndex != null }) {
            val source = SupplierCapturedMatchDisplayGatewayFixture(expected.fixtureId).capture
            assertRejectedScdProjection(expected, false, "SCD-04-I2") {
                it.copy(capture = assertNotNull(it.capture).copy(charges = assertNotNull(source.charges)))
            }
        }
    }

    @Test
    fun scd04I3RedactionCannotRelabelGoodsCompletion() = runTest(timeout = 5.seconds) {
        for (expected in scdDisplayExpectations) assertRejectedScdProjection(expected, false, "SCD-04-I3") {
            val wrongStatus = if (expected.fixtureId == ScdDisplayFixture.SCD_F3) "MATCHED_RECOGNIZED"
                else "PARTIALLY_MATCHED_RECOGNIZED"
            it.copy(recognition = assertNotNull(it.recognition).copy(status = wrongStatus))
        }
    }

    @Test
    fun scd04MatchingProfileCarriesRedactedInvoiceAndControlledQuoteDenial() = runTest(timeout = 5.seconds) {
        for (expected in scdDisplayExpectations) {
            val f = SupplierCapturedMatchDisplayGatewayFixture(expected.fixtureId)
            val view = f.view(costs = false)
            f.endpoint.currentReply = LocalSessionViewResult.Current(view)
            val redacted = f.readScdDetails(costs = false)
            assertScdRedactedProjection(f, expected, redacted)
            val lease = f.lease()
            try {
                val access = GatewayAccess(f.endpoint, lease, view, f.installation)
                assertTrue(access.hasHint(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM)))
                assertTrue(access.hasHint(Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM)))
                assertFalse(access.hasHint(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM)))
                assertFalse(access.allow(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, f.invoice))
                for (free in if (expected.fixtureId == ScdDisplayFixture.SCD_F3) listOf(false, true) else listOf(false)) {
                    val query = f.sourceQuery(free)
                    val request = ReadRequest(LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE,
                        LocalPurchasingCodec.capturedMatchSource(query))
                    // Profile compatibility is not any of the three current protected-read grants.
                    f.installation.requireRead(request)
                    assertEquals(LocalServiceProblem.DENIED, assertFailsWith<GatewayFailure> { access.read(request) }.problem)
                    assertEquals(query, LocalPurchasingCodec.decodeCapturedMatchSourceQuery(f.endpoint.reads.last().payload))
                    assertEquals(f.original, query.originalDocumentId)
                    assertEquals(if (free) f.freeAllocation else f.pricedAllocation, query.allocationId)
                    assertEquals(if (free) f.freeReceiptLine else f.pricedReceiptLine, query.receiptLineId)
                    assertEquals(Quantity(f.each, if (free) 1L else 5L, 0), query.quantity)
                }
            } finally { lease.destroy() }
            assertEquals(1, f.endpoint.readReplies.size) // Invoice only; no accepting source quote was supplied.
            assertScdGatewayReadOnly(f)
            // This observes denial propagation only. Real three-grant enforcement is SCD-05 work.
        }
    }
}

/** Literal frozen expectations; these are not calculated from production recognition/status code. */
private data class ScdDisplayExpected(
    val fixtureId: ScdDisplayFixture, val reference: String, val rawClaimMinor: Long, val rawNetMinor: Long,
    val unmatchedMinor: Long, val status: String, val rawQuantity: Long, val matchedQuantity: Long,
    val unmatchedQuantity: Long, val chargeIndex: Int?, val chargeLabel: String?, val chargeNetMinor: Long,
)

private val scdDisplayExpectations = listOf(
    ScdDisplayExpected(ScdDisplayFixture.SCD_F1, "SCD-POSITIVE-001", 62_000, 55_000, 5_000,
        "MATCHED_RECOGNIZED", 5, 5, 0, 21, "freight", 5_000),
    ScdDisplayExpected(ScdDisplayFixture.SCD_F2, "SCD-ZERO-CHARGE-001", 57_000, 50_000, 0,
        "MATCHED_RECOGNIZED", 5, 5, 0, 22, "handling", 0),
    ScdDisplayExpected(ScdDisplayFixture.SCD_F3, "SCD-FREE-PARTIAL-001", 57_000, 50_000, 0,
        "PARTIALLY_MATCHED_RECOGNIZED", 8, 6, 2, null, null, 0),
)

private data class ScdDisplayRead(val page: LocalWorkflowPage, val wire: LocalPurchasingInvoicePageV2, val reply: ReadReply.Data)

private suspend fun SupplierCapturedMatchDisplayGatewayFixture.readScdDetails(costs: Boolean): ScdDisplayRead {
    val page = assertIs<LocalReadResult.Available>(gateway.read(uiQuery(costs))).page
    val reply = endpoint.readReplies.last()
    return ScdDisplayRead(page, LocalPurchasingCodec.invoicePage(reply.payload), reply)
}

private suspend fun assertAuthorizedScdFixture(expected: ScdDisplayExpected) {
    val f = SupplierCapturedMatchDisplayGatewayFixture(expected.fixtureId)
    val originalInput = f.capture
    assertScdAuthorizedProjection(f, expected, f.readScdDetails(costs = true))
    assertEquals(listOf(LocalPurchasingProfile.READ_INVOICES), f.endpoint.reads.map { it.query })
    assertEquals(f.query(true), LocalPurchasingCodec.decodeQuery(f.endpoint.reads.single().payload))
    f.installation.requireRead(f.endpoint.reads.single())
    assertEquals(originalInput, f.capture)
    assertScdGatewayReadOnly(f)
}

private fun assertScdAuthorizedProjection(f: SupplierCapturedMatchDisplayGatewayFixture, expected: ScdDisplayExpected, read: ScdDisplayRead) {
    assertTrue(read.wire.includesCosts)
    assertEquals(LocalPurchasingReadAvailabilityV1.AVAILABLE, read.wire.availability)
    assertNull(read.wire.nextAfter)
    val document = assertNotNull(read.wire.documents).single()
    val capture = assertNotNull(document.capture)
    val overlay = assertNotNull(document.recognition)
    assertNull(document.legacy)
    assertEquals(f.capture, capture)
    assertEquals(f.recognition, overlay)
    assertScdIdentityAndStatus(f, expected, read)
    assertScdLine(f, read, f.pricedLine, f.pricedAllocation, 5, 5, 0, "100", 50_000, 7_000)
    if (expected.fixtureId == ScdDisplayFixture.SCD_F3)
        assertScdLine(f, read, f.freeLine, f.freeAllocation, 3, 1, 2, "0", 0, 0)
    val charges = assertNotNull(capture.charges)
    if (expected.chargeIndex == null) {
        assertTrue(charges.isEmpty())
        assertTrue(read.page.records.none { it.reference.kind == LocalRecordKind.PURCHASE_CHARGE })
    } else {
        val charge = charges.single()
        assertEquals(f.id(expected.chargeIndex), charge.id)
        assertEquals(expected.chargeLabel, charge.code)
        assertEquals(f.original, charge.sourceEvidenceId)
        assertEquals(Money(Currency.EGP, expected.chargeNetMinor), charge.net)
        assertEquals(Money(Currency.EGP, 0), charge.taxAmount)
        assertEquals(Money(Currency.EGP, expected.chargeNetMinor), charge.total())
        val record = read.page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_CHARGE }
        assertEquals(charge.id, record.reference.id)
        assertEquals(expected.chargeLabel, record.title.english)
        assertEquals(scdMoneyText(expected.chargeNetMinor), matchingValue(record, fieldLabel(LocalField.NET_AMOUNT).english))
        assertEquals("0.00 EGP", matchingValue(record, fieldLabel(LocalField.TAX_AMOUNT).english))
    }
    assertEquals(expected.rawNetMinor, capture.lines.sumOf { assertNotNull(it.claimedNet).minor } + charges.sumOf { it.net.minor })
    assertEquals(7_000L, capture.lines.sumOf { assertNotNull(it.claimedTax).minor } + charges.sumOf { it.taxAmount.minor })
    assertEquals(50_000L, overlay.lines.sumOf { assertNotNull(it.matchedNet).minor })
    assertEquals(7_000L, overlay.lines.sumOf { assertNotNull(it.matchedTax).minor })
    assertEquals(expected.unmatchedMinor, expected.rawNetMinor - overlay.lines.sumOf { assertNotNull(it.matchedNet).minor })
    assertEquals(0L, capture.lines.sumOf { assertNotNull(it.claimedTax).minor } + charges.sumOf { it.taxAmount.minor } -
        overlay.lines.sumOf { assertNotNull(it.matchedTax).minor })
    val raw = assertNotNull(capture.supplierClaim)
    val payable = assertNotNull(overlay.recognizedPayable)
    val unmatched = assertNotNull(overlay.unmatchedClaim)
    val due = assertNotNull(overlay.remainingDue)
    val credit = assertNotNull(overlay.appliedCredit)
    val paid = assertNotNull(overlay.allocatedPayment)
    val reserved = assertNotNull(overlay.reservedSupplierCash)
    val available = assertNotNull(overlay.availableToAllocate)
    assertEquals(Money(Currency.EGP, expected.rawClaimMinor), raw)
    assertEquals(Money(Currency.EGP, 57_000), payable)
    assertEquals(Money(Currency.EGP, expected.unmatchedMinor), unmatched)
    assertEquals(Money(Currency.EGP, 57_000), due)
    assertEquals(Money(Currency.EGP, 0), credit)
    assertEquals(Money(Currency.EGP, 0), paid)
    assertEquals(Money(Currency.EGP, 0), reserved)
    assertEquals(Money(Currency.EGP, 57_000), available)
    assertEquals(raw, payable + unmatched)
    assertEquals(payable, due + credit + paid)
    assertEquals(due, reserved + available)
    val invoice = read.page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_INVOICE }
    val progress = read.page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_MATCH }
    assertEquals(scdMoneyText(expected.rawClaimMinor), matchingValue(invoice, "Original supplier claim — not an approval"))
    assertEquals("570.00 EGP", matchingValue(progress, "Recognized payable"))
    assertEquals(scdMoneyText(expected.unmatchedMinor), matchingValue(progress, "Claim still unmatched / unrecognized"))
    assertEquals("570.00 EGP", matchingValue(progress, "Remaining unpaid obligation"))
}

private fun assertScdIdentityAndStatus(f: SupplierCapturedMatchDisplayGatewayFixture, expected: ScdDisplayExpected, read: ScdDisplayRead) {
    val document = assertNotNull(read.wire.documents).single()
    val capture = assertNotNull(document.capture)
    val overlay = assertNotNull(document.recognition)
    assertEquals(f.id(101), f.scope.companyId)
    assertEquals(f.id(102), f.scope.branchId)
    assertEquals(f.id(103), capture.supplierId)
    assertEquals(f.id(1), capture.id)
    assertEquals(f.id(2), capture.sourceEvidenceId)
    assertEquals(f.id(3), assertNotNull(capture.internalOrderLink).orderId)
    assertEquals("2026-11-05", capture.date.canonicalString())
    assertEquals(expected.reference, capture.reference)
    assertEquals("UNMATCHED_UNAPPROVED", capture.status)
    assertEquals(Revision(1), capture.revision)
    assertEquals(f.capture.operationId, capture.operationId)
    assertEquals(f.actor, capture.originalActorId)
    assertEquals(f.invoice, overlay.invoiceId)
    assertEquals(capture.revision, overlay.invoiceRevision)
    assertEquals(f.original, overlay.originalDocumentId)
    assertEquals(f.order, overlay.orderId)
    assertEquals(expected.status, overlay.status)
    assertFalse(overlay.settlementEligible)
    val expectedLines = if (expected.fixtureId == ScdDisplayFixture.SCD_F3) listOf(f.pricedLine, f.freeLine) else listOf(f.pricedLine)
    assertEquals(expectedLines, capture.lines.map { it.lineId })
    assertEquals(expectedLines, overlay.lines.map { it.lineId })
    val invoice = read.page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_INVOICE }
    val progress = read.page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_MATCH }
    assertEquals(f.invoice, invoice.reference.id)
    assertEquals(expected.reference, invoice.title.english)
    assertEquals("UNMATCHED_UNAPPROVED", assertNotNull(invoice.status).english)
    assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, invoice.invoiceEntryKind)
    assertEquals(expected.status, matchingValue(invoice, "Current matching / recognition — separate from original capture"))
    assertEquals(expected.status, assertNotNull(progress.status).english)
    assertEquals("false", matchingValue(progress, "Settlement eligibility — separate permission still required"))
    val lines = read.page.records.filter { it.reference.kind == LocalRecordKind.PURCHASE_LINE }
    assertEquals(expectedLines, lines.map { it.reference.id })
    assertEquals(expected.rawQuantity, capture.lines.sumOf { it.baseQuantity.scaled })
    assertEquals(expected.matchedQuantity, overlay.lines.sumOf { it.matchedQuantity.scaled })
    assertEquals(expected.unmatchedQuantity, lines.sumOf {
        matchingValue(it, "Base quantity still unmatched").substringBefore(" · ").toLong()
    })
    assertTrue(capture.lines.all { it.baseQuantity.unitId == f.each && it.baseQuantity.scale == 0 })
    assertTrue(overlay.lines.all { it.matchedQuantity.unitId == f.each && it.matchedQuantity.scale == 0 })
    assertTrue(read.page.forms.isEmpty()) // No combined mutation hint: optional original editor is not invoked.
}

private fun assertScdLine(f: SupplierCapturedMatchDisplayGatewayFixture, read: ScdDisplayRead,
    lineId: DomainId, allocationId: DomainId, rawQuantity: Long, matchedQuantity: Long, unmatchedQuantity: Long,
    price: String, netMinor: Long, taxMinor: Long) {
    val document = assertNotNull(read.wire.documents).single()
    val line = assertNotNull(document.capture).lines.single { it.lineId == lineId }
    val recognized = assertNotNull(document.recognition).lines.single { it.lineId == lineId }
    val original = assertNotNull(line.originalOrderLine)
    assertEquals(Quantity(f.each, rawQuantity, 0), line.originalQuantity)
    assertEquals(Quantity(f.each, rawQuantity, 0), line.baseQuantity)
    assertEquals(Quantity(f.each, matchedQuantity, 0), recognized.matchedQuantity)
    assertEquals(line.baseQuantity, recognized.matchedQuantity + Quantity(f.each, unmatchedQuantity, 0))
    assertEquals(allocationId, line.internalAllocationId)
    assertEquals(allocationId, original.allocationId)
    assertEquals(allocationId, recognized.allocationId)
    assertEquals(line.units, original.units)
    assertEquals(line.baseQuantity, original.baseQuantity)
    assertEquals(1L, line.units.rate.numerator)
    assertEquals(1L, line.units.rate.denominator)
    assertEquals(UnitPrice.parse(Currency.EGP, price), line.unitPrice)
    assertEquals(PercentageRate.parse("0"), line.discount)
    assertEquals(PercentageRate.parse("14"), assertNotNull(line.purchaseTax).rate)
    assertEquals(Money(Currency.EGP, netMinor), line.claimedNet)
    assertEquals(Money(Currency.EGP, taxMinor), line.claimedTax)
    assertEquals(Money(Currency.EGP, netMinor), recognized.matchedNet)
    assertEquals(Money(Currency.EGP, taxMinor), recognized.matchedTax)
    assertEquals(line.unitPrice, original.unitPrice)
    assertEquals(line.claimedNet, original.net)
    assertEquals(line.claimedTax, original.taxAmount)
    val record = read.page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_LINE && it.reference.id == lineId }
    assertEquals("$rawQuantity · ${f.each.value}", matchingValue(record, "Actual documented base quantity"))
    assertEquals("$matchedQuantity · ${f.each.value}", matchingValue(record, "Current matched base quantity"))
    assertEquals("$unmatchedQuantity · ${f.each.value}", matchingValue(record, "Base quantity still unmatched"))
    assertEquals(allocationId.value, matchingValue(record, "Actual matching allocation"))
    assertEquals(scdMoneyText(netMinor), matchingValue(record, fieldLabel(LocalField.NET_AMOUNT).english))
    assertEquals(scdMoneyText(taxMinor), matchingValue(record, fieldLabel(LocalField.TAX_AMOUNT).english))
    assertEquals(scdMoneyText(netMinor), matchingValue(record, "Recognized net — not paid"))
    assertEquals(scdMoneyText(taxMinor), matchingValue(record, "Recognized tax — not paid"))
}

private fun assertScdRedactedProjection(f: SupplierCapturedMatchDisplayGatewayFixture, expected: ScdDisplayExpected, read: ScdDisplayRead) {
    assertFalse(read.wire.includesCosts)
    assertScdIdentityAndStatus(f, expected, read)
    val document = assertNotNull(read.wire.documents).single()
    val capture = assertNotNull(document.capture)
    val overlay = assertNotNull(document.recognition)
    assertNull(capture.supplierClaim)
    assertNull(capture.charges) // Empty or zero-valued charge substitutes are not redaction.
    for (line in capture.lines) {
        assertNull(line.unitPrice)
        assertNull(line.discount)
        assertNull(line.purchaseTax)
        assertNull(line.claimedNet)
        assertNull(line.claimedTax)
        val original = assertNotNull(line.originalOrderLine)
        assertNull(original.unitPrice)
        assertNull(original.discount)
        assertNull(original.purchaseTax)
        assertNull(original.net)
        assertNull(original.taxAmount)
    }
    for (line in overlay.lines) {
        assertNull(line.matchedNet)
        assertNull(line.matchedTax)
    }
    assertTrue(listOf(overlay.recognizedPayable, overlay.unmatchedClaim, overlay.remainingDue, overlay.appliedCredit,
        overlay.allocatedPayment, overlay.reservedSupplierCash, overlay.availableToAllocate).all { it == null })
    assertTrue(read.page.records.none { it.reference.kind == LocalRecordKind.PURCHASE_CHARGE })
    assertTrue(read.page.records.flatMap { it.values }.none {
        it.label.english in scdSensitiveRecordLabels || it.value.english.contains("EGP")
    })
    assertScdSerializedCostOmission(Json.parseToJsonElement(read.reply.payload.copyBytes().decodeToString()))
}

private suspend fun assertRejectedScdProjection(expected: ScdDisplayExpected, costs: Boolean, caseId: String,
    change: (LocalPurchasingInvoiceDocumentV2) -> LocalPurchasingInvoiceDocumentV2) {
    val f = SupplierCapturedMatchDisplayGatewayFixture(expected.fixtureId)
    f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(costs))
    val originalInput = f.capture
    val baseline = f.readScdDetails(costs)
    val originalDocument = assertNotNull(baseline.wire.documents).single()
    val changedDocument = change(originalDocument)
    assertNotEquals(originalDocument, changedDocument, caseId)
    val changedPage = baseline.wire.copy(documents = listOf(changedDocument))
    f.endpoint.pageOverride = changedPage
    val refused = assertIs<LocalReadResult.Unavailable>(f.gateway.read(f.uiQuery(costs)), caseId)
    assertTrue(refused.problem in setOf(LocalServiceProblem.UNAVAILABLE, LocalServiceProblem.STORAGE_UNCERTAIN), caseId)
    assertEquals(changedPage, LocalPurchasingCodec.invoicePage(f.endpoint.readReplies.last().payload), caseId)
    assertEquals(2, f.endpoint.reads.size, caseId)
    assertEquals(originalInput, f.capture, caseId)
    assertScdGatewayReadOnly(f)
    // Each variant starts from a separately accepted controlled baseline and changes only its stated field.
    // Unavailable presentation is not a new business refusal or proof about independent committed sources.
}

private fun assertScdGatewayReadOnly(f: SupplierCapturedMatchDisplayGatewayFixture) {
    assertNull(f.journal.entry)
    assertEquals(0, f.journal.stageCalls)
    assertEquals(0, f.journal.rememberCalls)
    assertEquals(0, f.journal.acknowledgeCalls)
    assertTrue(f.endpoint.executions.isEmpty())
    assertTrue(f.endpoint.lookups.isEmpty())
}

private fun scdMoneyText(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')} EGP"

private val scdSensitiveRecordLabels = setOf(
    fieldLabel(LocalField.UNIT_PRICE).english, fieldLabel(LocalField.DISCOUNT_PERCENT).english,
    fieldLabel(LocalField.TAX_SOURCE_ID).english, fieldLabel(LocalField.TAX_CATEGORY).english,
    fieldLabel(LocalField.TAX_RATE).english, fieldLabel(LocalField.NET_AMOUNT).english, fieldLabel(LocalField.TAX_AMOUNT).english,
    "Original supplier claim — not an approval", "Recognized payable", "Claim still unmatched / unrecognized",
    "Remaining unpaid obligation", "Recognized net — not paid", "Recognized tax — not paid",
    "Original PO unit price", "Original PO net", "Original PO tax",
)

private val scdSensitiveWireKeys = setOf("unitPrice", "discount", "purchaseTax", "claimedNet", "claimedTax", "net", "taxAmount",
    "matchedNet", "matchedTax", "supplierClaim", "recognizedPayable", "unmatchedClaim", "remainingDue", "appliedCredit",
    "allocatedPayment", "reservedSupplierCash", "availableToAllocate", "charges")

private fun assertScdSerializedCostOmission(element: JsonElement, path: String = "\$") {
    when (element) {
        is JsonObject -> {
            assertFalse("currency" in element.keys, path)
            for ((key, value) in element) {
                // Nullable fields in this existing wire format must contain no financial object or zero value.
                if (key in scdSensitiveWireKeys) assertEquals(JsonNull, value, "$path.$key")
                assertScdSerializedCostOmission(value, "$path.$key")
            }
        }
        is JsonArray -> element.forEachIndexed { index, value -> assertScdSerializedCostOmission(value, "$path[$index]") }
        else -> Unit
    }
}

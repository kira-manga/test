package com.ukkera.pos.app.gateway.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unchanged CCM v1.0.1 and combined-action binding v1.0.0 supporting contributions.
 * The shared gateway, codecs and projection validators are real; source heads,
 * current sessions and journal writes are controlled. No authoritative producers,
 * business commit, protected cost release, original bytes or CMP screen is tested.
 * Source handoff status: NOT RUN. No whole CCM case is established by this harness.
 */
class CapturedInvoiceMatchingGatewaySupportingTest {
    @Test
    fun ccm03And09StagingPinsTheExactQualitySourcesAndExplicitFirstHeadAbsence() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        val unchangedInput = intent.values.toList()
        val staged = assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val entry = assertNotNull(f.journal.entry)
        assertExactQualityMatchRequest(f, entry.request(), intent)
        assertEquals(unchangedInput, intent.values)
        assertEquals(intent.operationId, staged.operation.operationId)
        assertEquals(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, staged.operation.action)
        assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
        assertTrue(staged.operation.submitAllowed)
        assertNull(staged.operation.recordedDecision)
        assertNull(entry.recorded)
        assertNull(entry.actionApproval)
        assertEquals(listOf(LocalPurchasingProfile.READ_INVOICES, LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE),
            f.endpoint.reads.map { it.query })
        val query = Json.decodeFromString(LocalCapturedSupplierInvoiceMatchSourceQueryV2.serializer(),
            f.endpoint.reads.last().payload.copyBytes().decodeToString())
        val source = LocalPurchasingCodec.capturedMatchSourceResult(f.endpoint.readReplies.last().payload)
        assertEquals(query, source.query)
        assertEquals(f.invoice, query.invoiceId)
        assertEquals(f.invoiceLine, query.invoiceLineId)
        assertEquals(f.original, query.originalDocumentId)
        assertEquals(f.order, query.orderId)
        assertEquals(f.allocation, query.allocationId)
        assertEquals(f.receipt, query.receiptId)
        assertEquals(f.receiptLine, query.receiptLineId)
        assertEquals(f.selectedQuantity, query.quantity)
        assertEquals(f.rawClaim, source.originalInvoiceClaim)
        assertEquals(f.recognized, source.incrementNet)
        assertEquals(f.zero, source.incrementTax)
        assertNull(source.recognizedPayable)
        assertTrue(staged.operation.summary.any { it.value.english.contains("240.00 EGP") })
        assertTrue(staged.operation.summary.any { it.value.english.contains("220.00 EGP") })
        assertTrue(staged.operation.summary.any { it.value.english.contains("No existing payable") })
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        assertEquals("UNMATCHED_UNAPPROVED", f.capture.status)
        assertEquals(f.rawClaim, f.capture.supplierClaim)
    }

    @Test
    fun ccm09JournalCodecRoundTripKeepsTheExactCombinedCommandAndOriginalActor() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        val restored = f.journal.codecRoundTrip()
        val retained = assertNotNull(restored.entry)
        assertEquals(original, retained)
        assertEquals(original.owner, restored.owner)
        assertEquals(f.actor, retained.owner.actorId)
        assertEquals(original.identity(), retained.identity())
        assertEquals(original.inputDigest, retained.inputDigest)
        assertEquals(original.semanticSha256, retained.semanticSha256)
        assertEquals(1, retained.formVersion)
        assertEquals(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, retained.localAction)
        assertExactQualityMatchRequest(f, retained.request(), intent)
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // This is a serialized memory seam, not a native/database close and reopen.
    }

    @Test
    fun ccm08CombinedGrantAbsenceBlocksFreshPreparationWithoutSourceReads() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(combined = false))
        assertEquals(LocalServiceProblem.DENIED,
            assertIs<LocalStageResult.NotStaged>(f.gateway.stage(f.intent())).problem)
        assertNull(f.journal.entry)
        assertEquals(0, f.journal.stageCalls)
        assertTrue(f.endpoint.reads.isEmpty())
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // The current gateway hint is conservative UI defense, not Master authorization.
    }

    @Test
    fun ccm08And09CurrentCombinedGrantLossBlocksNewDispatchButKeepsTheOriginal() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        val readsBefore = f.endpoint.reads.size
        f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(combined = false))
        f.endpoint.rejectReads = true
        assertEquals(LocalServiceProblem.DENIED,
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId)).problem)
        assertEquals(original, f.journal.entry)
        assertEquals(original.identity(), f.endpoint.lookups.single())
        assertEquals(readsBefore, f.endpoint.reads.size)
        assertTrue(f.endpoint.executions.isEmpty())
        assertEquals(0, f.journal.rememberCalls)
        assertEquals(0, f.journal.acknowledgeCalls)
        // No serialized grant-revocation/business-commit ordering is established here.
    }

    @Test
    fun ccm08InvoiceCaptureOnlyProfileRejectsFreshMatchingWithoutJournalOrSourceWork() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val captureOnly = f.gateway(installation = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE))
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
            assertIs<LocalStageResult.NotStaged>(captureOnly.stage(f.intent())).problem)
        assertNull(f.journal.entry)
        assertEquals(0, f.journal.stageCalls)
        assertTrue(f.endpoint.reads.isEmpty())
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // Even a controlled combined grant cannot widen the installed capture-only profile.
    }

    @Test
    fun ccm03And11QualityProjectionSeparatesRawRecognizedAndUnresolvedMoneyWithCostsRedacted() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        f.endpoint.includeRecognition = true
        for (costs in listOf(true, false)) {
            val page = f.readProjection(detailed = true, costs = costs)
            val decoded = LocalPurchasingCodec.invoicePage(f.endpoint.readReplies.last().payload)
            assertEquals(costs, decoded.includesCosts)
            val document = assertNotNull(decoded.documents).single()
            val capture = assertNotNull(document.capture)
            val overlay = assertNotNull(document.recognition)
            assertNull(document.legacy)
            assertEquals(f.original, capture.sourceEvidenceId)
            assertEquals(f.capture.operationId, capture.operationId)
            assertEquals(f.actor, capture.originalActorId)
            assertEquals("UNMATCHED_UNAPPROVED", capture.status)
            assertEquals(f.capture.lines.single().originalQuantity, capture.lines.single().originalQuantity)
            assertEquals(24L, capture.lines.single().baseQuantity.scaled)
            assertEquals(12L, capture.lines.single().units.rate.numerator)
            assertEquals(1L, capture.lines.single().units.rate.denominator)
            assertEquals(f.selectedQuantity, overlay.lines.single().matchedQuantity)
            assertFalse(overlay.settlementEligible)
            val invoice = page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_INVOICE }
            val line = page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_LINE }
            val progress = page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_MATCH }
            assertEquals("UNMATCHED_UNAPPROVED", assertNotNull(invoice.status).english)
            assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, invoice.invoiceEntryKind)
            assertEquals("PARTIALLY_MATCHED_RECOGNIZED", assertNotNull(progress.status).english)
            assertEquals("24 · ${f.each.value}", matchingValue(line, "Actual documented base quantity"))
            assertEquals("22 · ${f.each.value}", matchingValue(line, "Current matched base quantity"))
            assertEquals("2 · ${f.each.value}", matchingValue(line, "Base quantity still unmatched"))
            assertEquals(f.allocation.value, matchingValue(line, "Actual matching allocation"))
            assertEquals("false", matchingValue(progress, "Settlement eligibility — separate permission still required"))
            if (costs) {
                assertEquals(f.rawClaim, capture.supplierClaim)
                assertEquals(f.recognized, overlay.recognizedPayable)
                assertEquals(f.unresolved, overlay.unmatchedClaim)
                assertEquals(f.recognized, overlay.remainingDue)
                assertEquals(f.zero, overlay.allocatedPayment)
                assertEquals(f.zero, overlay.appliedCredit)
                assertEquals(f.zero, overlay.reservedSupplierCash)
                assertEquals(f.recognized, overlay.availableToAllocate)
                assertEquals("240.00 EGP", matchingValue(invoice, "Original supplier claim — not an approval"))
                assertEquals("220.00 EGP", matchingValue(progress, "Recognized payable"))
                assertEquals("20.00 EGP", matchingValue(progress, "Claim still unmatched / unrecognized"))
                assertEquals("220.00 EGP", matchingValue(progress, "Remaining unpaid obligation"))
                assertEquals("220.00 EGP", matchingValue(line, "Recognized net — not paid"))
            } else {
                assertNull(capture.supplierClaim)
                assertNull(capture.charges)
                val redactedLine = capture.lines.single()
                assertNull(redactedLine.unitPrice)
                assertNull(redactedLine.discount)
                assertNull(redactedLine.purchaseTax)
                assertNull(redactedLine.claimedNet)
                assertNull(redactedLine.claimedTax)
                assertTrue(listOf(overlay.recognizedPayable, overlay.unmatchedClaim, overlay.remainingDue,
                    overlay.appliedCredit, overlay.allocatedPayment, overlay.reservedSupplierCash,
                    overlay.availableToAllocate).all { it == null })
                assertNull(overlay.lines.single().matchedNet)
                assertNull(overlay.lines.single().matchedTax)
                assertTrue(page.records.flatMap { it.values }.none { it.value.english.contains("EGP") })
            }
            // The bounded harness deliberately does not install the original-metadata editor read.
            assertTrue(page.forms.isEmpty())
        }
        assertEquals(f.rawClaim, f.capture.supplierClaim)
        assertEquals("UNMATCHED_UNAPPROVED", f.capture.status)
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // Controlled cost redaction/projection is not the protected three-grant Master read gate.
    }

    @Test
    fun ccm11DirectoryProjectionDoesNotInventClaimOrRecognitionDetailLines() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        f.endpoint.includeRecognition = true
        for (costs in listOf(true, false)) {
            val page = f.readProjection(detailed = false, costs = costs)
            val document = assertNotNull(LocalPurchasingCodec.invoicePage(f.endpoint.readReplies.last().payload).documents).single()
            assertTrue(assertNotNull(document.capture).lines.isEmpty())
            assertTrue(assertNotNull(document.recognition).lines.isEmpty())
            val invoice = page.records.single()
            assertEquals(LocalRecordKind.PURCHASE_INVOICE, invoice.reference.kind)
            assertEquals(f.invoice, invoice.reference.id)
            assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, invoice.invoiceEntryKind)
            assertEquals("UNMATCHED_UNAPPROVED", assertNotNull(invoice.status).english)
            assertEquals("PARTIALLY_MATCHED_RECOGNIZED",
                matchingValue(invoice, "Current matching / recognition — separate from original capture"))
            if (costs) assertEquals("240.00 EGP", matchingValue(invoice, "Original supplier claim — not an approval"))
            else assertTrue(invoice.values.none { it.value.english.contains("EGP") })
            assertTrue(page.forms.isEmpty())
        }
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // No persisted-directory, real pagination or native layout outcome is claimed.
    }

    @Test
    fun ccm10And11OverlayValidationRejectsMissingDetailAndFalsePaidOrAllocationBalances() {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val original = f.recognition()
        validateCapturedInvoiceRecognition(f.capture, original, costs = true, detailed = true)
        val invalid = listOf(
            original.copy(lines = emptyList()),
            original.copy(unmatchedClaim = f.zero),
            original.copy(allocatedPayment = f.recognized),
            original.copy(availableToAllocate = f.zero),
            original.copy(settlementEligible = true),
        )
        for (value in invalid) {
            assertEquals(LocalServiceProblem.UNAVAILABLE, assertFailsWith<GatewayFailure> {
                validateCapturedInvoiceRecognition(f.capture, value, costs = true, detailed = true)
            }.problem)
        }
        assertEquals(f.unresolved, original.unmatchedClaim)
        assertEquals(f.zero, original.allocatedPayment)
        assertEquals(f.recognized, original.remainingDue)
        assertFalse(original.settlementEligible)
        // Malformed projections fail closed; no settlement/variance policy is invented.
    }
}

internal fun assertExactQualityMatchRequest(f: CapturedInvoiceMatchingGatewayFixture, request: MutationRequest,
    intent: LocalCommandIntent) {
    assertEquals(intent.operationId, request.operationId)
    assertEquals(LocalPurchasingProfile.MATCH_CAPTURED_INVOICE, request.command)
    assertEquals("local.purchasing.invoice.match-recognize", request.command.name)
    assertEquals(2, request.command.version)
    assertNull(request.actionApprovalId)
    val selected = LocalPurchasingCodec.decodeCapturedMatch(request.payload)
    assertEquals(f.match, selected.matchId)
    assertEquals(f.invoice, selected.invoiceId)
    assertEquals(f.invoiceLine, selected.invoiceLineId)
    assertEquals(Revision(1), selected.expectedInvoiceRevision)
    assertEquals(f.original, selected.originalDocumentId)
    assertEquals(Revision(1), selected.expectedOriginalRevision)
    assertEquals(f.order, selected.orderId)
    assertEquals(Revision(1), selected.expectedOrderRevision)
    assertEquals(f.allocation, selected.allocationId)
    assertEquals(f.receipt, selected.receiptId)
    assertEquals(f.receiptLine, selected.receiptLineId)
    assertEquals(Revision(1), selected.expectedReceiptRevision)
    assertEquals(Revision(1), selected.expectedOrderMatchRevision)
    assertEquals(Revision(1), selected.expectedOrderBillingRevision)
    assertNull(selected.expectedProgressRevision)
    assertNull(selected.expectedReceiptBillingRevision)
    assertNull(selected.expectedPayableRevision)
    assertEquals(f.selectedQuantity, selected.quantity)
    assertEquals("purchasing.exact_goods", selected.reasonCode)
    assertEquals(request.payload, LocalPurchasingCodec.normalize(request.command, request.payload))
}

internal fun matchingValue(record: LocalRecord, englishLabel: String): String =
    record.values.single { it.label.english == englishLabel }.value.english

private suspend fun CapturedInvoiceMatchingGatewayFixture.readProjection(detailed: Boolean, costs: Boolean): LocalWorkflowPage =
    assertIs<LocalReadResult.Available>(gateway.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
        reference = if (detailed) LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, invoice) else null,
        purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES, includeCosts = costs)))).page

package com.ukkera.pos.sales.independent.pay10classificationcorrection

import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * commerce-v1 COM-PAY-10-I/E reducer SUPPORT only, with unchanged frozen values.
 * CARD originals are produced by actual normalized Start/outcome/finalization
 * and closing reducers. Reservation/issued-header, closing-work and principal
 * inputs remain controlled data, not genuine native sources or authority.
 * Snapshot equality is pure input immutability, not durable stock/entitlement/
 * current-drawer conservation. No atomic append/CAS, journal, RPC, restart,
 * receipt, installed UI or whole failure variant is qualified here.
 */
class Pay10ClassificationCorrectionReducerSupportingTest {
    @Test
    fun supportFrozenCardToCashCorrectionLinksExactOriginalAndPreservesSubmittedClose() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val input = original.input
        val before = original.snapshots()
        val payment = original.sale.payments.single()
        val close = assertNotNull(original.session.closeReport)
        assertEquals(Money(Currency.EGP, 10000), payment.amount)
        assertEquals(input.card, payment.tender)
        assertEquals(ExternalTenderKind.CARD, assertIs<Tender.External>(payment.tender).kind)
        assertEquals(input.collector, payment.collectorId)
        assertEquals(input.collector, payment.attestation.actorId)
        assertEquals(input.startOperation, payment.originalOperationId)
        assertEquals(input.recordingOperation, payment.recordingOperationId)
        assertEquals(CashSessionStatus.SUBMITTED_CLOSED, original.session.status)
        assertEquals(Money(Currency.EGP, 100000), close.cut.expected)
        assertEquals(Money(Currency.EGP, 10000), close.cut.externalCollections)
        assertEquals(Money(Currency.EGP, 110000), close.count.amount)
        assertEquals(Money(Currency.EGP, 10000), close.variance)
        assertEquals(input.closeOperation, close.operationId)
        assertEquals(input.collector, close.submittedBy)

        val correction = original.correction()
        assertEquals(input.saleId, correction.saleId)
        assertEquals(input.paymentId, correction.paymentAttemptId)
        assertEquals(original.session.id, correction.sessionId)
        assertEquals(input.collector, correction.collectorId)
        assertEquals(input.startOperation, correction.originalIntentOperationId)
        assertEquals(input.recordingOperation, correction.originalRecordingOperationId)
        assertEquals(input.closeOperation, correction.originalCloseOperationId)
        assertEquals(Money(Currency.EGP, 10000), correction.amount)
        assertEquals(input.card, correction.originalTender)
        assertEquals(input.card, correction.previousClassification)
        assertEquals(Tender.Cash, correction.correctedTender)
        assertEquals(Money(Currency.EGP, -10000), correction.previousClassificationDelta)
        assertEquals(Money(Currency.EGP, 10000), correction.correctedClassificationDelta)
        assertEquals(Money(Currency.EGP, 0), correction.physicalCashDelta)
        assertEquals(input.manager, correction.evidence.actorId)
        assertEquals(input.correctionEvidence(), correction.evidence)
        assertEquals("synthetic receipt/count investigation", correction.reason)
        assertEquals(OperationId("a40f4978-c4fa-5844-a9a6-5b33b006e22b"), correction.operationId)
        assertEquals(input.today, correction.recordedAt)
        assertEquals(Revision(0), correction.position)
        assertNull(correction.previousCorrectionId)
        assertNull(correction.terminal)
        assertNull(correction.reference)
        assertEquals(before, original.snapshots())
        assertEquals(Money(Currency.EGP, 100000), original.session.expectedCash)
        assertEquals(Money(Currency.EGP, 110000), assertNotNull(original.session.closeReport).count.amount)
        assertEquals(Money(Currency.EGP, 10000), assertNotNull(original.session.closeReport).variance)
        // Today's frozen drawer50000 is not a production ledger supplied to this
        // pure reducer. physicalCashDelta0 cannot qualify its durable conservation.
    }

    @Test
    fun supportExactRepeatedOriginalProducesTheSameLinkedValueWithoutRewritingInputs() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val before = original.snapshots()
        val first = original.correction()
        val repeated = original.correction()
        assertEquals(first, repeated)
        assertEquals(original.input.correctionOperation, repeated.operationId)
        assertEquals(original.input.manager, repeated.evidence.actorId)
        assertEquals(Money(Currency.EGP, 0), repeated.physicalCashDelta)
        assertEquals(before, original.snapshots())
        // Stable pure value only: no actual duplicate delivery, append count,
        // current-grant recheck, durable same-ID result or restart is exercised.
    }

    @Test
    fun supportChanged10001CannotReplaceTheFrozenOriginal10000CorrectionSource() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val before = original.snapshots()
        assertEquals(SalesRule.INVALID_AMOUNT, assertFailsWith<SalesRuleViolation> {
            original.correction(expectedAmount = Money(Currency.EGP, 10001))
        }.rule)
        assertEquals(before, original.snapshots())
        assertEquals(Money(Currency.EGP, 10000), original.sale.payments.single().amount)
        assertEquals(Money(Currency.EGP, 10000), original.correction().amount)
        // Exact-source amount check, not durable semantic-payload mismatch handling.
    }

    @Test
    fun supportStaleClosedSessionRevisionCannotRewriteTheOriginalCloseOrProduceAdjustment() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val before = original.snapshots()
        val stale = Revision(original.session.revision.value - 1)
        assertEquals(SalesRule.REVISION_CONFLICT, assertFailsWith<SalesRuleViolation> {
            original.correction(expectedSessionRevision = stale)
        }.rule)
        assertEquals(before, original.snapshots())
        assertEquals(Money(Currency.EGP, 10000), assertNotNull(original.session.closeReport).variance)
    }

    @Test
    fun supportDistinctBEvidenceCannotAdoptTheOriginalManagerCorrection() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val before = original.snapshots()
        assertNotEquals(original.input.manager, original.input.otherActor)
        assertEquals(SalesRule.INVALID_EVIDENCE, assertFailsWith<SalesRuleViolation> {
            original.correction(evidence = original.input.correctionEvidence(original.input.otherActor))
        }.rule)
        assertEquals(before, original.snapshots())
        assertEquals(original.input.collector, original.sale.payments.single().collectorId)
        assertEquals(original.input.manager, original.correction().evidence.actorId)
        // Evidence/command binding only, not authenticated changed-actor denial
        // or private durable-result recovery authorization.
    }

    @Test
    fun supportUnrelatedActionAmountOrTargetProofCannotAuthorizeTheFrozenCorrection() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val before = original.snapshots()
        for (action in listOf(SecurityAction.CASH_REVIEW, SecurityAction.COLLECTION_ATTEST)) {
            assertEquals(SalesRule.PERMISSION_DENIED, assertFailsWith<SalesRuleViolation> {
                original.correction(action = action)
            }.rule)
            assertEquals(before, original.snapshots())
        }
        assertEquals(SalesRule.PERMISSION_DENIED, assertFailsWith<SalesRuleViolation> {
            original.correction(proofAmount = Money(Currency.EGP, 10001))
        }.rule)
        assertEquals(SalesRule.PERMISSION_DENIED, assertFailsWith<SalesRuleViolation> {
            original.correction(proofTarget = original.input.secondAttemptId)
        }.rule)
        assertEquals(before, original.snapshots())
        // Declared authorization binding only: actual native current manager
        // grant/deny/removal, admitted device/session and commit race remain unrun.
    }

    @Test
    fun supportMissingExactOriginalPaymentCannotProduceAHistoricalAdjustment() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val before = original.snapshots()
        assertEquals(SalesRule.PAYMENT_UNRESOLVED, assertFailsWith<SalesRuleViolation> {
            original.correction(paymentAttemptId = original.input.secondAttemptId)
        }.rule)
        assertEquals(before, original.snapshots())
        assertEquals(original.input.paymentId, original.sale.payments.single().attemptId)
        // No original native row/source lookup or response disclosure is exercised.
    }

    @Test
    fun supportUnclosedInputCannotStandInForTheRequiredImmutableOriginalClose() = runTest {
        val original = Pay10NormalizedExternalFixture.pay10().closedPay10Original()
        val before = original.snapshots()
        assertEquals(CashSessionStatus.OPEN, original.beforeClosing.status)
        assertNull(original.beforeClosing.closeReport)
        assertEquals(SalesRule.IMMUTABLE_RECORD, assertFailsWith<SalesRuleViolation> {
            original.correction(sourceSession = original.beforeClosing)
        }.rule)
        assertEquals(before, original.snapshots())
        assertEquals(CashSessionStatus.SUBMITTED_CLOSED, original.session.status)
        assertEquals(Money(Currency.EGP, 10000), assertNotNull(original.session.closeReport).variance)
    }
}

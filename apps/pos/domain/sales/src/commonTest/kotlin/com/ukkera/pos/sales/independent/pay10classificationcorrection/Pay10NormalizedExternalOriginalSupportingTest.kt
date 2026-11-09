package com.ukkera.pos.sales.independent.pay10classificationcorrection

import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * commerce-v1 COM-PAY-01/04 frozen external-original SUPPORT for PAY-10.
 * Actual normalized Start and outcome reducers are used; a CARD original is
 * never replaced by a cash-only original. Controlled principals and reservation
 * headers are planning inputs, not authenticated native history or stock proof.
 * No business commit, current native policy, durable journal, RPC, restart,
 * receipt, stock issue, installed UI or whole frozen case is qualified here.
 */
class Pay10NormalizedExternalOriginalSupportingTest {
    @Test
    fun supportNormalizedCardStartAndConfirmedOriginalPreserveExact10000WithoutCashReceipt() = runTest {
        val fixture = Pay10NormalizedExternalFixture.pay01()
        val started = fixture.start()
        val pending = fixture.collection.attempts.single()
        assertEquals(fixture.startOperation, pending.originalOperationId)
        assertEquals(fixture.collector, pending.originalActorId)
        assertEquals(fixture.session.id, pending.originalCashSessionId)
        assertEquals(fixture.card, pending.tender)
        assertEquals(Money(Currency.EGP, 10000), pending.amount)
        assertEquals(AttemptStatus.AWAITING_ATTESTATION, pending.status)
        assertNull(pending.payment)
        assertEquals(listOf(fixture.paymentId), fixture.session.pendingAttemptIds)
        assertEquals(Money(Currency.EGP, 100000), fixture.session.expectedCash)
        assertEquals(Money(Currency.EGP, 0), fixture.session.reservedCashOut)
        assertTrue(started.effects.none { it is PaymentRecord || it is CashMovement })

        val confirmed = fixture.record(CollectionOutcome.ExternalAttested(fixture.externalEvidence()))
        val payment = assertNotNull(fixture.collection.attempts.single().payment)
        assertEquals(Money(Currency.EGP, 10000), payment.amount)
        assertEquals(fixture.card, payment.tender)
        assertEquals(fixture.collector, payment.collectorId)
        assertEquals(fixture.session.id, payment.cashSessionId)
        assertEquals(fixture.startOperation, payment.originalOperationId)
        assertEquals(fixture.recordingOperation, payment.recordingOperationId)
        assertEquals(fixture.evidence(), payment.attestation)
        assertEquals("SYN-T1", assertNotNull(payment.externalEvidence).terminal)
        assertEquals("SYN-OK-001", assertNotNull(payment.externalEvidence).reference)
        assertNull(payment.cashReceived)
        assertNull(payment.changeGiven)
        assertEquals(CollectionStatus.COVERED, fixture.collection.status)
        assertEquals(Money(Currency.EGP, 0), fixture.collection.remainingPayable)
        assertEquals(Money(Currency.EGP, 100000), fixture.session.expectedCash)
        assertEquals(Money(Currency.EGP, 10000), fixture.session.totals.externalCollections)
        assertEquals(Money(Currency.EGP, 0), fixture.session.totals.received)
        assertEquals(Money(Currency.EGP, 0), fixture.session.totals.change)
        assertTrue(fixture.session.pendingAttemptIds.isEmpty())
        assertEquals(payment, confirmed.effects.filterIsInstance<PaymentRecord>().single())
        assertTrue(confirmed.effects.none { it is CashMovement || it is SalePosted })
        // This reducer effect list is not a committed ledger or genuine original source.
    }

    @Test
    fun supportNotPaidOutcomeCannotProduceCardCoverageOrCashReceipt() = runTest {
        val fixture = Pay10NormalizedExternalFixture.pay01()
        fixture.start()
        val refusedPayment = fixture.record(CollectionOutcome.NotPaid(fixture.evidence(), "synthetic terminal not-paid"))
        val original = fixture.collection.attempts.single()
        assertEquals(AttemptStatus.NOT_PAID, original.status)
        assertEquals(fixture.startOperation, original.originalOperationId)
        assertEquals(fixture.collector, original.originalActorId)
        assertEquals(fixture.card, original.tender)
        assertNull(original.payment)
        assertEquals(CollectionStatus.LOCKED_UNPAID, fixture.collection.status)
        assertEquals(Money(Currency.EGP, 10000), fixture.collection.remainingPayable)
        assertEquals(Money(Currency.EGP, 100000), fixture.session.expectedCash)
        assertEquals(Money(Currency.EGP, 0), fixture.session.totals.externalCollections)
        assertTrue(refusedPayment.effects.none { it is PaymentRecord || it is CashMovement || it is SalePosted })
        // NOT_PAID has no external-reference carrier: the full reference-alone
        // command/receipt assertion remains outside this bounded SUPPORT check.
    }

    @Test
    fun supportMissingFrozenRequiredReferenceRejectsWithoutChangingTheOriginalAttempt() = runTest {
        val fixture = Pay10NormalizedExternalFixture.pay01()
        fixture.start()
        val before = fixture.snapshots()
        val missing = CollectionOutcome.ExternalAttested(ExternalTenderEvidence(fixture.evidence(), "SYN-T1", null))
        assertEquals(SalesRule.REFERENCE_REQUIRED, assertFailsWith<SalesRuleViolation> { fixture.record(missing) }.rule)
        assertEquals(before, fixture.snapshots())
        assertEquals(AttemptStatus.AWAITING_ATTESTATION, fixture.collection.attempts.single().status)
        assertNull(fixture.collection.attempts.single().payment)
        assertEquals(Money(Currency.EGP, 100000), fixture.session.expectedCash)
        assertEquals(Money(Currency.EGP, 0), fixture.session.totals.externalCollections)
    }

    @Test
    fun supportUnknownOriginalRetainsActorEvidenceAndBlocksAnotherNormalizedAttempt() = runTest {
        val fixture = Pay10NormalizedExternalFixture.pay04()
        fixture.start()
        fixture.record(CollectionOutcome.Unknown(fixture.evidence()))
        val before = fixture.snapshots()
        val original = fixture.collection.attempts.single()
        assertEquals(AttemptStatus.OUTCOME_UNKNOWN, original.status)
        assertEquals(fixture.evidence(), assertIs<CollectionOutcome.Unknown>(original.latestObservation).evidence)
        assertEquals(fixture.startOperation, original.originalOperationId)
        assertEquals(fixture.collector, original.originalActorId)
        assertEquals(fixture.session.id, original.originalCashSessionId)
        assertEquals(Money(Currency.EGP, 10000), original.amount)
        assertNull(original.payment)
        assertEquals(listOf(fixture.paymentId), fixture.session.pendingAttemptIds)
        assertEquals(SalesRule.PAYMENT_UNRESOLVED, assertFailsWith<SalesRuleViolation> {
            fixture.start(attemptId = fixture.secondAttemptId, operation = fixture.secondStartOperation)
        }.rule)
        assertEquals(before, fixture.snapshots())
        assertEquals(Money(Currency.EGP, 100000), fixture.session.expectedCash)
        // Retaining a scalar reservation input is NOT durable stock/expiry or
        // client cancellation/logout/restart/recovery qualification.
    }

    @Test
    fun supportDistinctBActorCannotAdoptOrRelabelTheOriginalCardRecording() = runTest {
        val fixture = Pay10NormalizedExternalFixture.pay01()
        fixture.start()
        val before = fixture.snapshots()
        assertEquals(SalesRule.OWNER_REQUIRED, assertFailsWith<SalesRuleViolation> {
            fixture.record(CollectionOutcome.ExternalAttested(fixture.externalEvidence()), actor = fixture.otherActor)
        }.rule)
        assertEquals(before, fixture.snapshots())
        val original = fixture.collection.attempts.single()
        assertEquals(fixture.collector, original.originalActorId)
        assertEquals(fixture.startOperation, original.originalOperationId)
        assertNull(original.payment)
        fixture.record(CollectionOutcome.ExternalAttested(fixture.externalEvidence()))
        assertEquals(fixture.collector, assertNotNull(fixture.collection.attempts.single().payment).collectorId)
        // Pure custody check only: no native same-ID journal lookup or private
        // result disclosure to B is exercised.
    }

    @Test
    fun supportFrozen10001CannotBeRoundedOrAllocatedIntoThe10000OriginalPayable() = runTest {
        val fixture = Pay10NormalizedExternalFixture.pay01()
        val before = fixture.snapshots()
        assertEquals(SalesRule.INVALID_AMOUNT, assertFailsWith<SalesRuleViolation> {
            fixture.start(amount = Money(Currency.EGP, 10001))
        }.rule)
        assertEquals(before, fixture.snapshots())
        assertTrue(fixture.collection.attempts.isEmpty())
        assertEquals(Money(Currency.EGP, 10000), fixture.collection.remainingPayable)
        assertEquals(Money(Currency.EGP, 100000), fixture.session.expectedCash)
        fixture.start(amount = Money(Currency.EGP, 10000))
        assertEquals(Money(Currency.EGP, 10000), fixture.collection.attempts.single().amount)
        // This is the beyond-remaining boundary, not durable same-ID mismatch.
    }

    @Test
    fun supportStaleNormalizedStartPreservesTheAlreadyPendingOriginalAttempt() = runTest {
        val fixture = Pay10NormalizedExternalFixture.pay01()
        val oldRevision = fixture.collection.revision
        val staleCommand = fixture.command(SecurityAction.COLLECTION_ATTEST, fixture.collection.id, oldRevision,
            Money(Currency.EGP, 10000), operation = fixture.secondStartOperation)
        fixture.start()
        val before = fixture.snapshots()
        assertEquals(SalesRule.REVISION_CONFLICT, assertFailsWith<SalesRuleViolation> {
            Collections.startNormalizedAttempt(staleCommand, fixture.collection, oldRevision,
                fixture.session, fixture.session.revision, fixture.secondAttemptId,
                Money(Currency.EGP, 10000), fixture.reservation, tender = fixture.card)
        }.rule)
        assertEquals(before, fixture.snapshots())
        assertEquals(Revision(1), fixture.collection.revision)
        assertEquals(fixture.paymentId, fixture.collection.attempts.single().id)
        assertEquals(fixture.startOperation, fixture.collection.attempts.single().originalOperationId)
        assertEquals(listOf(fixture.paymentId), fixture.session.pendingAttemptIds)
        assertEquals(Money(Currency.EGP, 100000), fixture.session.expectedCash)
    }
}

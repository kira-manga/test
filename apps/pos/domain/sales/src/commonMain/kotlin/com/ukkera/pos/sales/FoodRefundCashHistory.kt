package com.ukkera.pos.sales

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Shared pure value of the real authorized FOOD refund Start. No grant, source or cash permit. */
internal fun foodRefundStartedValue(refund: FoodRefundObligation, session: CashSession,
    intentOperationId: OperationId, actor: DomainId, attemptId: DomainId,
    originalPaymentId: DomainId, amount: Money): FoodRefundCashChange {
    requireSales(session.scope == refund.scope, SalesRule.WRONG_BRANCH)
    requireSessionOwner(session, actor)
    amount.requirePositive(); sameCurrency(refund.amount, amount)
    requireSales(!refund.unresolved, SalesRule.PAYMENT_UNRESOLVED)
    requireSales(refund.attempts.none { it.id == attemptId }, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
    val obligation = refund.obligations.singleOrNull { it.originalPaymentId == originalPaymentId }
        ?: throw SalesRuleViolation(SalesRule.TENDER_MISMATCH)
    val paid = refund.attempts.filter { it.originalPaymentId == originalPaymentId }
        .fold(amount.zero()) { total, item -> total + (item.refund?.amount ?: amount.zero()) }
    requireSales(amount.minor <= (obligation.amount - paid).minor, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
    val attempt = RefundAttempt(attemptId, intentOperationId, actor, session.id, originalPaymentId,
        obligation.tender, amount, AttemptStatus.AWAITING_ATTESTATION)
    return FoodRefundCashChange(refund.next(refund.attempts + attempt),
        session.beginAttempt(attemptId, if (obligation.tender == Tender.Cash) amount else amount.zero()))
}

/** Same exact immutable-observation accounting value as FoodRefunds; never physical replay. */
internal fun foodRefundAppliedValue(refund: FoodRefundObligation, session: CashSession, attemptId: DomainId,
    observationOperationId: OperationId, applicationOperationId: OperationId, actor: DomainId,
    outcome: RefundOutcome): FoodRefundCashChange {
    requireSales(observationOperationId != applicationOperationId, SalesRule.INVALID_EVIDENCE)
    val attempt = refund.attempts.singleOrNull { it.id == attemptId }
        ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
    requireSales(session.scope == refund.scope, SalesRule.WRONG_BRANCH)
    requireSessionOwner(session, actor)
    requireSales(attempt.status.isUnresolved() && attempt.originalActorId == actor &&
        attempt.originalCashSessionId == session.id && attempt.id in session.pendingAttemptIds,
        SalesRule.PAYMENT_UNRESOLVED)
    val evidence: CashierEvidence
    var external: ExternalTenderEvidence? = null
    val status = when (outcome) {
        is RefundOutcome.Unknown -> { evidence = outcome.evidence; AttemptStatus.OUTCOME_UNKNOWN }
        is RefundOutcome.NotPaid -> { evidence = outcome.evidence; requireReason(outcome.reason); AttemptStatus.NOT_PAID }
        is RefundOutcome.CashPaid -> {
            evidence = outcome.evidence
            requireSales(attempt.tender == Tender.Cash, SalesRule.TENDER_MISMATCH)
            AttemptStatus.ATTESTED_PAID
        }
        is RefundOutcome.ExternalAttested -> {
            evidence = outcome.evidence.attestation; external = outcome.evidence
            validateExternal(attempt.tender, outcome.evidence); AttemptStatus.ATTESTED_PAID
        }
    }
    evidence.requireActor(actor)
    val record = if (status != AttemptStatus.ATTESTED_PAID) null else RefundRecord(attempt.id, refund.id,
        attempt.originalPaymentId, attempt.originalOperationId, observationOperationId, actor, session.id,
        attempt.tender, attempt.amount, evidence, external)
    val movement = if (record != null && attempt.tender == Tender.Cash) CashMovement(attempt.id,
        applicationOperationId, session.id, actor, CashMovementKind.CUSTOMER_REFUND, -attempt.amount,
        attempt.amount.zero(), attempt.amount.zero(), evidence, null) else null
    val updated = RefundAttempt(attempt.id, attempt.originalOperationId, actor, session.id,
        attempt.originalPaymentId, attempt.tender, attempt.amount, status, outcome, record)
    val after = refund.next(refund.attempts.map { if (it.id == attempt.id) updated else it })
    val cash = if (status == AttemptStatus.OUTCOME_UNKNOWN) session else session.finishRecordedAttempt(
        applicationOperationId, attempt.id, if (attempt.tender == Tender.Cash) attempt.amount else attempt.amount.zero(),
        movement, externalRefund = if (record != null && attempt.tender is Tender.External) attempt.amount else attempt.amount.zero())
    return FoodRefundCashChange(after, cash, record, movement)
}

/** Literal retained FOOD CASH payout comparison. Decoding/comparing never authorizes a refund. */
public object FoodRefundCashHistory {
    public fun requireStarted(before: FoodRefundObligation, after: FoodRefundObligation,
        cashBefore: CashSession, cashAfter: CashSession, intentOperationId: OperationId, actorId: DomainId,
        expectedRefundRevision: Revision, expectedSessionRevision: Revision, attemptId: DomainId,
        originalPaymentId: DomainId, amount: Money) {
        requireRevision(before.revision, expectedRefundRevision)
        requireRevision(cashBefore.revision, expectedSessionRevision)
        requireSales(before.obligations.singleOrNull { it.originalPaymentId == originalPaymentId }?.tender == Tender.Cash,
            SalesRule.INVALID_EVIDENCE)
        val expected = foodRefundStartedValue(before, cashBefore, intentOperationId, actorId,
            attemptId, originalPaymentId, amount)
        requireRefund(after, expected.refund)
        requireCashHistoryImage(cashAfter, expected.session)
    }

    /** Exact movement DATA only, not an employee/native permit or a historical command. */
    public fun requireApplied(before: FoodRefundObligation, after: FoodRefundObligation,
        cashBefore: CashSession, cashAfter: CashSession, attemptId: DomainId,
        observationOperationId: OperationId, applicationOperationId: OperationId, originalActorId: DomainId,
        outcome: RefundOutcome, resolvingUnknown: Boolean): CashMovement? {
        val attempt = before.attempts.singleOrNull { it.id == attemptId }
            ?: throw SalesRuleViolation(SalesRule.INVALID_EVIDENCE)
        requireSales(attempt.tender == Tender.Cash && outcome !is RefundOutcome.ExternalAttested &&
            attempt.refund == null && attempt.resolutionObservation == null &&
            (if (resolvingUnknown) attempt.status == AttemptStatus.OUTCOME_UNKNOWN &&
                attempt.latestObservation is RefundOutcome.Unknown && outcome !is RefundOutcome.Unknown
            else attempt.status == AttemptStatus.AWAITING_ATTESTATION && attempt.latestObservation == null),
            SalesRule.INVALID_EVIDENCE)
        val expected = foodRefundAppliedValue(before, cashBefore, attemptId, observationOperationId,
            applicationOperationId, originalActorId, outcome)
        requireRefund(after, expected.refund)
        requireCashHistoryImage(cashAfter, expected.session)
        return expected.movement
    }

    private fun requireRefund(actual: FoodRefundObligation, expected: FoodRefundObligation) {
        requireSales(FoodFinancialFramesV2.encodeRefund(actual) == FoodFinancialFramesV2.encodeRefund(expected),
            SalesRule.INVALID_EVIDENCE)
    }
}

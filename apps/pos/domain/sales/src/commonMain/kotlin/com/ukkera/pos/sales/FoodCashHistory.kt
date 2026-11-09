package com.ukkera.pos.sales

import com.ukkera.pos.types.*
import com.ukkera.pos.security.PayloadDigest

/**
 * Literal retained FOOD CASH comparison only. Uses the SAME pure values as FoodCollections;
 * never builds SalesCommand/AuthorizedAction, takes cash, authorizes, or returns a transition.
 * Adapters separately prove the genuine originals, complete stock sources and native custody.
 */
public object FoodCashHistory {
    public fun requireStarted(before: FoodCollection, after: FoodCollection,
        cashBefore: CashSession, cashAfter: CashSession, intentOperationId: OperationId, actorId: DomainId,
        expectedCollectionRevision: Revision, expectedSessionRevision: Revision,
        attemptId: DomainId, amount: Money, payerReference: String?) {
        requireRevision(before.revision, expectedCollectionRevision)
        requireRevision(cashBefore.revision, expectedSessionRevision)
        val expected = foodStartedAttemptValue(before, cashBefore, intentOperationId, actorId,
            attemptId, Tender.Cash, amount, payerReference)
        requireCollection(after, expected.collection)
        requireCashHistoryImage(cashAfter, expected.session)
    }

    /** Returns only exact expected movement DATA for complete effect comparison, not a permit. */
    public fun requireApplied(before: FoodCollection, after: FoodCollection,
        cashBefore: CashSession, cashAfter: CashSession, attemptId: DomainId,
        recordedObservationOperationId: OperationId, applicationOperationId: OperationId,
        originalActorId: DomainId, outcome: CollectionOutcome): CashMovement? {
        val attempt = before.attempts.singleOrNull { it.id == attemptId }
            ?: throw SalesRuleViolation(SalesRule.INVALID_EVIDENCE)
        requireSales(attempt.tender == Tender.Cash && attempt.status == AttemptStatus.AWAITING_ATTESTATION &&
            attempt.latestObservation == null && attempt.payment == null && outcome !is CollectionOutcome.ExternalAttested,
            SalesRule.INVALID_EVIDENCE)
        val expected = foodAppliedObservationValue(before, cashBefore, attemptId,
            recordedObservationOperationId, applicationOperationId, originalActorId, outcome)
        requireCollection(after, expected.collection)
        requireCashHistoryImage(cashAfter, expected.session)
        return expected.movement
    }

    /** Pure FOOD supervised comparison. No SalesCommand, grant, current auth or retail link. */
    public fun requireSupervisedApplied(before: FoodCollection, after: FoodCollection,
        cashBefore: CashSession, cashAfter: CashSession, attemptId: DomainId,
        recordedObservationOperationId: OperationId, recordingActorId: DomainId,
        applicationOperationId: OperationId, accountingActorId: DomainId,
        originalUnknownOperationId: OperationId?, originalUnknownBodyDigest: PayloadDigest?,
        outcome: CollectionOutcome): CashMovement? {
        val attempt = before.attempts.singleOrNull { it.id == attemptId }
            ?: throw SalesRuleViolation(SalesRule.INVALID_EVIDENCE)
        requireSales(attempt.tender == Tender.Cash && outcome !is CollectionOutcome.ExternalAttested,
            SalesRule.INVALID_EVIDENCE)
        val link = foodRecordedResolutionLink(attempt, recordedObservationOperationId, recordingActorId,
            applicationOperationId, accountingActorId, originalUnknownOperationId, originalUnknownBodyDigest,
            outcome is CollectionOutcome.Unknown)
        val expected = foodAppliedObservationValue(before, cashBefore, attemptId,
            recordedObservationOperationId, applicationOperationId, attempt.originalActorId, outcome, recordingActorId, link)
        requireCollection(after, expected.collection)
        requireCashHistoryImage(cashAfter, expected.session)
        return expected.movement
    }

    private fun requireCollection(actual: FoodCollection, expected: FoodCollection) {
        requireSales(FoodFinancialFramesV2.encodeCollection(actual) == FoodFinancialFramesV2.encodeCollection(expected),
            SalesRule.INVALID_EVIDENCE)
    }
}

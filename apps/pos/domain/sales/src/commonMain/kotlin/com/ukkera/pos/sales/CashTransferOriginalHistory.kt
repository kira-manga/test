package com.ukkera.pos.sales

import com.ukkera.pos.types.*

/** Complete inert ordinary transfer/legacy-close image comparisons. These methods
 * create no SalesCommand, authorization, effects, receipt, payout or current session. */
public object CashTransferOriginalHistory {
    public fun requireDrop(before: CashSession, after: CashSession, transfer: SafeTransfer, actorId: DomainId,
        expectedRevision: Revision, transferId: DomainId, receivingCustodyId: DomainId,
        amount: Money, evidence: CashierEvidence): Unit {
        amount.requirePositive(); requireRevision(before.revision, expectedRevision)
        requireSessionOwner(before, actorId); evidence.requireActor(actorId)
        sameCurrency(before.expectedCash, amount)
        requireSales(receivingCustodyId != before.id && receivingCustodyId != before.drawerId, SalesRule.INVALID_STATE)
        requireSales(transferId !in before.pendingTransferIds, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        requireSales(before.availableCash.minor >= amount.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
        requireSafeTransferHistoryImage(transfer, SafeTransfer(transferId, before.scope, Revision(0), before.id,
            before.drawerId, actorId, receivingCustodyId, amount, evidence, false, SafeTransferStatus.AWAITING_RECEIPT))
        requireCashHistoryImage(after, before.update(totals = before.totals.copy(safeDrops = before.totals.safeDrops + amount),
            expectedCash = before.expectedCash - amount, cashLedgerRevision = before.cashLedgerRevision.next(),
            pendingTransferIds = before.pendingTransferIds + transferId))
    }

    public fun requireLegacyClose(before: CashSession, after: CashSession, transfer: SafeTransfer?, actorId: DomainId,
        operationId: OperationId, expectedRevision: Revision, assignments: CashAssignmentReadSet,
        count: CountedCash, retainedFloat: Money, transferId: DomainId?, receivingCustodyId: DomainId?,
        varianceReason: String?, deliveryEvidence: CashierEvidence?): Unit {
        requireRevision(before.revision, expectedRevision)
        requireSales(actorId == before.custodianId, SalesRule.OWNER_REQUIRED)
        requireSales(before.status == CashSessionStatus.CLOSING, SalesRule.IMMUTABLE_RECORD)
        requireSales(assignments.scope == before.scope && assignments.drawerId == before.drawerId &&
            assignments.custodianId == before.custodianId, SalesRule.WRONG_BRANCH)
        requireSales(assignments.activeDrawerSessionId == before.id && assignments.activeCustodianSessionId == before.id &&
            assignments.unresolvedClosingTransferId == null, SalesRule.CUSTODY_UNRESOLVED)
        count.evidence.requireActor(actorId)
        sameCurrency(count.amount, retainedFloat, before.expectedCash)
        retainedFloat.requireNonnegative()
        requireSales(retainedFloat.minor <= count.amount.minor, SalesRule.INVALID_AMOUNT)
        val cut = checkNotNull(before.closingCut)
        val variance = count.amount - cut.expected
        if (variance.minor != 0L) requireReason(varianceReason ?: "")
        varianceReason?.let(::requireReason)
        val delivery = count.amount - retainedFloat
        requireSales((delivery.minor > 0L) == (transferId != null && receivingCustodyId != null), SalesRule.INVALID_STATE)
        if (delivery.minor == 0L) requireSales(transferId == null && receivingCustodyId == null, SalesRule.INVALID_STATE)
        val report = CashCloseReport(before.id, cut, count, variance, retainedFloat, delivery,
            transferId, varianceReason, actorId, operationId)
        requireCashHistoryImage(after, before.update(status = CashSessionStatus.SUBMITTED_CLOSED, closeReport = report))
        if (delivery.minor == 0L) requireSales(transfer == null, SalesRule.INVALID_STATE)
        else {
            requireSales(receivingCustodyId != before.id && receivingCustodyId != before.drawerId, SalesRule.INVALID_STATE)
            val delivered = deliveryEvidence ?: throw SalesRuleViolation(SalesRule.INVALID_EVIDENCE)
            delivered.requireActor(actorId)
            requireSafeTransferHistoryImage(checkNotNull(transfer), SafeTransfer(checkNotNull(transferId), before.scope,
                Revision(0), before.id, before.drawerId, actorId, checkNotNull(receivingCustodyId), delivery, delivered,
                true, SafeTransferStatus.AWAITING_RECEIPT))
        }
    }
}

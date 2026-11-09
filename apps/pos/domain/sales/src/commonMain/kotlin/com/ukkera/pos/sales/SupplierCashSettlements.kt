package com.ukkera.pos.sales

import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** A supplier obligation selected by the purchasing repository, never a payment or authority. */
public data class SupplierCashCommitment(
    public val supplierId: DomainId,
    public val invoiceId: DomainId?,
    public val settlementId: DomainId,
    public val sourceRevision: Revision,
    public val amount: Money,
) {
    init { requireSales(amount.currency == Currency.EGP && amount.minor > 0L, SalesRule.INVALID_AMOUNT) }
    public val authorizationTarget: DomainId get() = invoiceId ?: supplierId
}

public fun supplierCashAuthorizationRequirement(
    scope: BranchScope, commitment: SupplierCashCommitment, operationId: OperationId,
    digest: PayloadDigest, reasonCode: String, custodianId: DomainId, resolution: Boolean = false,
): AuthorizationRequirement = salesAuthorizationRequirement(
    if (resolution) SecurityAction.SUPPLIER_CASH_RESOLVE else SecurityAction.SUPPLIER_CASH_SETTLE,
    scope, commitment.authorizationTarget, commitment.sourceRevision, operationId, digest, reasonCode,
    custodianId, commitment.amount,
)

public data class SupplierCashChange(public val session: CashSession, public val movement: CashMovement?)

/**
 * Uses the existing physical cash pool and pending-attempt cut. No invoice, stock, acquisition
 * cost or operating expense is posted here. The purchasing handler must join the selected
 * source, one native original and one allocation in its same durable transaction.
 */
public object SupplierCashSettlements {
    public fun reserve(
        command: SalesCommand, commitment: SupplierCashCommitment, session: CashSession,
        expectedSessionRevision: Revision, assignments: CashAssignmentReadSet, attemptId: DomainId,
    ): CashSession {
        authorizeSupplier(command, commitment, session, resolution = false)
        requireRevision(session.revision, expectedSessionRevision)
        requireSupplierAssignment(session, assignments)
        return session.beginAttempt(attemptId, commitment.amount)
    }

    /** UNKNOWN remains reserved; a mismatched actual payout is retained for reconciliation. */
    public fun finish(
        command: SalesCommand, commitment: SupplierCashCommitment, session: CashSession,
        expectedSessionRevision: Revision, assignments: CashAssignmentReadSet, attemptId: DomainId,
        paid: Boolean, evidence: CashierEvidence, resolution: Boolean = false,
    ): SupplierCashChange {
        val actor = authorizeSupplier(command, commitment, session, resolution)
        requireRevision(session.revision, expectedSessionRevision)
        requireSupplierAssignment(session, assignments)
        evidence.requireActor(actor)
        requireReason(command.reasonCode)
        return finishValue(command.operationId, commitment, session, attemptId, paid, evidence, command.reasonCode)
    }

    /** Literal historic images only; this does not run a command or create an authorization. */
    public fun requireHistoricalReserve(
        before: CashSession, after: CashSession, commitment: SupplierCashCommitment,
        originalActorId: DomainId, expectedSessionRevision: Revision, assignments: CashAssignmentReadSet,
        attemptId: DomainId,
    ): Unit {
        requireRevision(before.revision, expectedSessionRevision)
        requireSessionOwner(before, originalActorId)
        requireSupplierAssignment(before, assignments)
        requireCashHistoryImage(after, before.beginAttempt(attemptId, commitment.amount))
    }

    public fun requireHistoricalFinish(
        before: CashSession, after: CashSession, commitment: SupplierCashCommitment,
        originalActorId: DomainId, expectedSessionRevision: Revision, assignments: CashAssignmentReadSet,
        attemptId: DomainId, applicationOperationId: OperationId, paid: Boolean,
        evidence: CashierEvidence, reasonCode: String, movement: CashMovement?,
    ): Unit {
        requireRevision(before.revision, expectedSessionRevision)
        requireSessionOwner(before, originalActorId)
        requireSupplierAssignment(before, assignments)
        evidence.requireActor(originalActorId)
        requireReason(reasonCode)
        val expected = finishValue(applicationOperationId, commitment, before, attemptId, paid, evidence, reasonCode)
        requireCashHistoryImage(after, expected.session)
        requireSales(movement == expected.movement, SalesRule.INVALID_EVIDENCE)
    }

    /** Inert expected BEFORE-to-AFTER image for an already retained historical producer. */
    public fun historicalReserveImage(before: CashSession, commitment: SupplierCashCommitment,
        originalActorId: DomainId, assignments: CashAssignmentReadSet, attemptId: DomainId): CashSession {
        requireSessionOwner(before, originalActorId)
        requireSupplierAssignment(before, assignments)
        return before.beginAttempt(attemptId, commitment.amount)
    }

    public fun historicalFinishImage(before: CashSession, commitment: SupplierCashCommitment,
        originalActorId: DomainId, assignments: CashAssignmentReadSet, attemptId: DomainId,
        applicationOperationId: OperationId, paid: Boolean, evidence: CashierEvidence, reasonCode: String): SupplierCashChange {
        requireSessionOwner(before, originalActorId)
        requireSupplierAssignment(before, assignments)
        evidence.requireActor(originalActorId)
        requireReason(reasonCode)
        return finishValue(applicationOperationId, commitment, before, attemptId, paid, evidence, reasonCode)
    }

    /** Literal immutable cash-out vocabulary, never an expense, stock effect or permission. */
    public fun payoutMovement(commitment: SupplierCashCommitment, sessionId: DomainId, originalActorId: DomainId,
        attemptId: DomainId, applicationOperationId: OperationId, evidence: CashierEvidence, reasonCode: String): CashMovement {
        evidence.requireActor(originalActorId); requireReason(reasonCode)
        return CashMovement(attemptId, applicationOperationId, sessionId, originalActorId,
            CashMovementKind.INVENTORY_SETTLEMENT, -commitment.amount, commitment.amount.zero(), commitment.amount.zero(), evidence, reasonCode)
    }

    private fun authorizeSupplier(command: SalesCommand, commitment: SupplierCashCommitment,
        session: CashSession, resolution: Boolean): DomainId {
        val proof = command.authorization
        val expected = supplierCashAuthorizationRequirement(session.scope, commitment, command.operationId,
            command.payloadDigest, command.reasonCode, session.custodianId, resolution)
        requireSales(proof.principalKind == PrincipalKind.LOCAL_EMPLOYEE && proof.approvalId == null &&
            proof.isBoundTo(expected, session.custodianId), SalesRule.PERMISSION_DENIED)
        requireSessionOwner(session, proof.actorId)
        return proof.actorId
    }

    private fun finishValue(operationId: OperationId, commitment: SupplierCashCommitment,
        session: CashSession, attemptId: DomainId, paid: Boolean, evidence: CashierEvidence,
        reasonCode: String): SupplierCashChange {
        requireSales(session.status == CashSessionStatus.OPEN && attemptId in session.pendingAttemptIds,
            SalesRule.PAYMENT_UNRESOLVED)
        sameCurrency(session.expectedCash, commitment.amount)
        requireSales(commitment.amount.minor <= session.reservedCashOut.minor, SalesRule.INVALID_AMOUNT)
        val reserve = session.reservedCashOut - commitment.amount
        val totals = if (paid) session.totals.copy(payOuts = session.totals.payOuts + commitment.amount)
            else session.totals
        val expectedCash = if (paid) session.expectedCash - commitment.amount else session.expectedCash
        requireSales(expectedCash.minor >= reserve.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
        val movement = if (paid) payoutMovement(commitment, session.id, session.custodianId, attemptId, operationId, evidence, reasonCode) else null
        return SupplierCashChange(session.update(totals = totals, expectedCash = expectedCash,
            reservedCashOut = reserve, cashLedgerRevision = if (paid) session.cashLedgerRevision.next()
            else session.cashLedgerRevision, pendingAttemptIds = session.pendingAttemptIds - attemptId), movement)
    }

    private fun requireSupplierAssignment(session: CashSession, assignments: CashAssignmentReadSet) {
        requireSales(assignments.scope == session.scope && assignments.drawerId == session.drawerId &&
            assignments.custodianId == session.custodianId, SalesRule.WRONG_BRANCH)
        requireSales(assignments.activeDrawerSessionId == session.id && assignments.activeCustodianSessionId == session.id &&
            assignments.unresolvedClosingTransferId == null, SalesRule.CUSTODY_UNRESOLVED)
    }
}

package com.ukkera.pos.sales

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.datetime.LocalDate

public enum class CashSessionStatus { OPEN, CLOSING, SUBMITTED_CLOSED, REVIEWED }

public data class CountedCash(public val amount: Money, public val evidence: CashierEvidence) {
    init { amount.requireNonnegative() }
}

/** Current rows read by the master transaction; pre-checks do not replace unique indexes. */
public data class CashAssignmentReadSet(
    public val scope: BranchScope,
    public val drawerId: DomainId,
    public val drawerRevision: Revision,
    public val activeDrawerSessionId: DomainId?,
    public val unresolvedClosingTransferId: DomainId?,
    public val custodianId: DomainId,
    public val custodianRevision: Revision,
    public val activeCustodianSessionId: DomainId?,
)

public class ClosingWorkReadSet(
    public val scope: BranchScope,
    public val custodianId: DomainId,
    public val revision: Revision,
    ownedUnsettledOrderIds: List<DomainId>,
    otherUnresolvedFinancialIds: List<DomainId>,
) {
    public val ownedUnsettledOrderIds: List<DomainId> = frozenList(ownedUnsettledOrderIds)
    public val otherUnresolvedFinancialIds: List<DomainId> = frozenList(otherUnresolvedFinancialIds)
}

/** Gross received and change are evidence; the ledger posts their net exactly once. */
public data class CashTotals(
    public val received: Money,
    public val change: Money,
    public val refunds: Money,
    public val payIns: Money,
    public val payOuts: Money,
    public val safeDrops: Money,
    public val externalCollections: Money,
    public val externalRefunds: Money,
) {
    init {
        sameCurrency(received, change, refunds, payIns, payOuts, safeDrops, externalCollections, externalRefunds)
        listOf(received, change, refunds, payIns, payOuts, safeDrops, externalCollections, externalRefunds)
            .forEach { it.requireNonnegative() }
    }

    public fun expected(opening: Money): Money =
        opening + received - change - refunds + payIns - payOuts - safeDrops

    internal companion object {
        fun zero(amount: Money): CashTotals {
            val zero = amount.zero()
            return CashTotals(zero, zero, zero, zero, zero, zero, zero, zero)
        }
    }
}

public data class CashClosingCut(
    public val cashLedgerRevision: Revision,
    public val expected: Money,
    public val externalCollections: Money,
    public val externalRefunds: Money,
)

public data class CashCloseReport(
    public val sessionId: DomainId,
    public val cut: CashClosingCut,
    public val count: CountedCash,
    public val variance: Money,
    public val retainedFloat: Money,
    public val actualSafeDelivery: Money,
    public val safeTransferId: DomainId?,
    public val varianceReason: String?,
    public val submittedBy: DomainId,
    public val operationId: OperationId,
)

public data class CashReview(
    public val reviewerId: DomainId,
    public val evidence: CashierEvidence,
    public val reason: String,
    public val originalReport: CashCloseReport,
)

/** Session state, not a login session, mutable repository or employee debt account. */
public class CashSession internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val drawerId: DomainId,
    public val custodianId: DomainId,
    public val businessDate: LocalDate,
    public val revision: Revision,
    public val opening: CountedCash,
    public val openingSourceId: DomainId,
    public val totals: CashTotals,
    public val expectedCash: Money,
    public val reservedCashOut: Money,
    public val cashLedgerRevision: Revision,
    public val status: CashSessionStatus,
    pendingAttemptIds: List<DomainId> = emptyList(),
    pendingTransferIds: List<DomainId> = emptyList(),
    public val closingCut: CashClosingCut? = null,
    public val closeReport: CashCloseReport? = null,
    public val review: CashReview? = null,
) {
    public val pendingAttemptIds: List<DomainId> = frozenList(pendingAttemptIds, 128)
    public val pendingTransferIds: List<DomainId> = frozenList(pendingTransferIds, 128)
    public val availableCash: Money get() = expectedCash - reservedCashOut

    init {
        sameCurrency(opening.amount, expectedCash, reservedCashOut)
        expectedCash.requireNonnegative()
        reservedCashOut.requireNonnegative()
        requireSales(opening.evidence.actorId == custodianId, SalesRule.INVALID_EVIDENCE)
        requireSales(totals.expected(opening.amount) == expectedCash, SalesRule.INVALID_AMOUNT)
        requireSales(reservedCashOut.minor <= expectedCash.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
        requireSales(this.pendingAttemptIds.distinct().size == this.pendingAttemptIds.size, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        requireSales(this.pendingTransferIds.distinct().size == this.pendingTransferIds.size, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        when (status) {
            CashSessionStatus.OPEN -> requireSales(closingCut == null && closeReport == null && review == null, SalesRule.INVALID_STATE)
            CashSessionStatus.CLOSING -> requireSales(closingCut != null && closeReport == null && review == null, SalesRule.INVALID_STATE)
            CashSessionStatus.SUBMITTED_CLOSED -> requireSales(closingCut != null && closeReport != null && review == null, SalesRule.INVALID_STATE)
            CashSessionStatus.REVIEWED -> requireSales(closingCut != null && closeReport != null && review != null, SalesRule.INVALID_STATE)
        }
        if (status != CashSessionStatus.OPEN) requireSales(this.pendingAttemptIds.isEmpty() && this.pendingTransferIds.isEmpty() &&
            reservedCashOut.minor == 0L, SalesRule.CLOSING_BLOCKED)
    }

    internal fun update(
        totals: CashTotals = this.totals,
        expectedCash: Money = this.expectedCash,
        reservedCashOut: Money = this.reservedCashOut,
        cashLedgerRevision: Revision = this.cashLedgerRevision,
        status: CashSessionStatus = this.status,
        pendingAttemptIds: List<DomainId> = this.pendingAttemptIds,
        pendingTransferIds: List<DomainId> = this.pendingTransferIds,
        closingCut: CashClosingCut? = this.closingCut,
        closeReport: CashCloseReport? = this.closeReport,
        review: CashReview? = this.review,
    ): CashSession = CashSession(
        id, scope, drawerId, custodianId, businessDate, revision.next(), opening, openingSourceId,
        totals, expectedCash, reservedCashOut, cashLedgerRevision, status, pendingAttemptIds,
        pendingTransferIds, closingCut, closeReport, review,
    )
}

public enum class CashMovementKind {
    CUSTOMER_RECEIPT, CUSTOMER_REFUND, NON_SALE_PAY_IN, OPERATING_EXPENSE, INVENTORY_SETTLEMENT, SAFE_DROP,
}

public data class CashMovement internal constructor(
    public val sourceId: DomainId,
    public val operationId: OperationId,
    public val sessionId: DomainId,
    public val custodianId: DomainId,
    public val kind: CashMovementKind,
    public val delta: Money,
    public val cashReceived: Money,
    public val changeGiven: Money,
    public val evidence: CashierEvidence,
    public val reason: String?,
) : SalesEffect

public data class CashSessionChanged(public val session: CashSession) : SalesEffect

public data class CashAssignmentOpened(
    public val drawerId: DomainId,
    public val custodianId: DomainId,
    public val sessionId: DomainId,
) : SalesEffect

/** The drawer stays blocked until its actual closing safe transfer is acknowledged. */
public data class CashAssignmentReleased(
    public val drawerId: DomainId,
    public val custodianId: DomainId,
    public val sessionId: DomainId,
    public val unresolvedClosingTransferId: DomainId?,
) : SalesEffect

public enum class SafeTransferStatus { AWAITING_RECEIPT, RECEIVED }

/** The current receiving custody assignment, read in the same transaction as the receipt. */
public data class SafeCustodyReadSet(
    public val scope: BranchScope,
    public val custodyId: DomainId,
    public val custodianId: DomainId,
    public val revision: Revision,
)

public class SafeTransfer internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val revision: Revision,
    public val senderSessionId: DomainId,
    public val senderDrawerId: DomainId,
    public val senderCustodianId: DomainId,
    public val receivingCustodyId: DomainId,
    public val amount: Money,
    public val sentEvidence: CashierEvidence,
    public val closingDisposition: Boolean,
    public val status: SafeTransferStatus,
    public val receipt: CashierEvidence? = null,
)

public data class SafeTransferChanged(public val transfer: SafeTransfer) : SalesEffect

/** A receiving custody ledger credit, never a second sale/collection or sender debit. */
public data class SafeCustodyReceipt(
    public val transferId: DomainId,
    public val custodyId: DomainId,
    public val amount: Money,
    public val evidence: CashierEvidence,
) : SalesEffect

public data class ClosingCustodyReleased(
    public val drawerId: DomainId,
    public val transferId: DomainId,
) : SalesEffect

public data class CashWithTransfer(public val session: CashSession, public val transfer: SafeTransfer)
public data class ClosedCash(public val session: CashSession, public val transfer: SafeTransfer?)

/** Counted, exclusive physical cash custody. No network/bank or mutable storage is used. */
public object CashSessions {
    public fun open(
        command: SalesCommand,
        assignments: CashAssignmentReadSet,
        expectedDrawerRevision: Revision,
        expectedCustodianRevision: Revision,
        sessionId: DomainId,
        businessDate: LocalDate,
        opening: CountedCash,
        openingSourceId: DomainId,
    ): SalesTransition<CashSession> {
        requireRevision(assignments.drawerRevision, expectedDrawerRevision)
        requireRevision(assignments.custodianRevision, expectedCustodianRevision)
        val actor = authorize(command, "cash.open", assignments.scope, assignments.drawerId, expectedDrawerRevision, opening.amount)
        requireSales(actor == assignments.custodianId, SalesRule.OWNER_REQUIRED)
        opening.evidence.requireActor(actor)
        requireSales(assignments.activeDrawerSessionId == null, SalesRule.DRAWER_ASSIGNED)
        requireSales(assignments.activeCustodianSessionId == null, SalesRule.CUSTODIAN_ASSIGNED)
        requireSales(assignments.unresolvedClosingTransferId == null, SalesRule.CUSTODY_UNRESOLVED)
        val session = CashSession(
            sessionId, assignments.scope, assignments.drawerId, actor, businessDate, Revision(0),
            opening, openingSourceId, CashTotals.zero(opening.amount), opening.amount, opening.amount.zero(),
            Revision(0), CashSessionStatus.OPEN,
        )
        return SalesTransition(
            command, session,
            listOf(
                SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, assignments.drawerId, expectedDrawerRevision),
                SalesVersionCheck(SalesAggregate.CUSTODIAN_ASSIGNMENT, actor, expectedCustodianRevision),
            ),
            listOf(
                SalesUniqueCheck(SalesUniqueKind.CASH_SESSION, sessionId),
                SalesUniqueCheck(SalesUniqueKind.OPENING_FLOAT_SOURCE, openingSourceId),
                SalesUniqueCheck(SalesUniqueKind.ACTIVE_DRAWER, assignments.drawerId),
                SalesUniqueCheck(SalesUniqueKind.ACTIVE_CUSTODIAN, actor),
            ),
            listOf(CashSessionChanged(session), CashAssignmentOpened(assignments.drawerId, actor, sessionId)),
        )
    }

    /** Inventory settlement/expense/pay-in are distinct; no generic synthetic customer receipt. */
    public fun recordNonSale(
        command: SalesCommand,
        session: CashSession,
        expectedRevision: Revision,
        sourceId: DomainId,
        kind: CashMovementKind,
        amount: Money,
        evidence: CashierEvidence,
        reason: String,
    ): SalesTransition<CashSession> {
        requireSales(kind in listOf(CashMovementKind.NON_SALE_PAY_IN, CashMovementKind.OPERATING_EXPENSE, CashMovementKind.INVENTORY_SETTLEMENT), SalesRule.INVALID_STATE)
        val actor = requireCashActor(command, "cash.move", session, expectedRevision, amount.requirePositive())
        evidence.requireActor(actor)
        requireReason(reason)
        sameCurrency(session.expectedCash, amount)
        val increase = kind == CashMovementKind.NON_SALE_PAY_IN
        if (!increase) requireSales(session.availableCash.minor >= amount.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
        val totals = if (increase) session.totals.copy(payIns = session.totals.payIns + amount)
        else session.totals.copy(payOuts = session.totals.payOuts + amount)
        val delta = if (increase) amount else -amount
        val after = session.update(totals = totals, expectedCash = session.expectedCash + delta, cashLedgerRevision = session.cashLedgerRevision.next())
        val movement = CashMovement(sourceId, command.operationId, session.id, actor, kind, delta, amount.zero(), amount.zero(), evidence, reason)
        return cashTransition(command, session, after, listOf(movement), listOf(SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, sourceId)))
    }

    public fun dropToSafe(
        command: SalesCommand,
        session: CashSession,
        expectedRevision: Revision,
        transferId: DomainId,
        receivingCustodyId: DomainId,
        amount: Money,
        evidence: CashierEvidence,
    ): SalesTransition<CashWithTransfer> {
        val actor = requireCashActor(command, "cash.transfer", session, expectedRevision, amount.requirePositive())
        evidence.requireActor(actor)
        sameCurrency(session.expectedCash, amount)
        requireSales(receivingCustodyId != session.id && receivingCustodyId != session.drawerId, SalesRule.INVALID_STATE)
        requireSales(transferId !in session.pendingTransferIds, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        requireSales(session.availableCash.minor >= amount.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
        val transfer = SafeTransfer(
            transferId, session.scope, Revision(0), session.id, session.drawerId, actor, receivingCustodyId,
            amount, evidence, false, SafeTransferStatus.AWAITING_RECEIPT,
        )
        val after = session.update(
            totals = session.totals.copy(safeDrops = session.totals.safeDrops + amount),
            expectedCash = session.expectedCash - amount,
            cashLedgerRevision = session.cashLedgerRevision.next(),
            pendingTransferIds = session.pendingTransferIds + transferId,
        )
        val movement = CashMovement(transferId, command.operationId, session.id, actor, CashMovementKind.SAFE_DROP, -amount, amount.zero(), amount.zero(), evidence, null)
        return SalesTransition(command, CashWithTransfer(after, transfer), listOf(cashVersion(session)),
            listOf(SalesUniqueCheck(SalesUniqueKind.SAFE_TRANSFER, transferId), SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, transferId)),
            listOf(movement, CashSessionChanged(after), SafeTransferChanged(transfer)))
    }

    /** Conservative cut: reconcile all admitted financial attempts BEFORE freezing it. */
    public fun beginClosing(
        command: SalesCommand,
        session: CashSession,
        expectedRevision: Revision,
        work: ClosingWorkReadSet,
        expectedWorkRevision: Revision,
    ): SalesTransition<CashSession> {
        requireCashActor(command, "cash.close", session, expectedRevision)
        val after = closingValue(session, work, expectedWorkRevision)
        return SalesTransition(command, after,
            listOf(cashVersion(session), SalesVersionCheck(SalesAggregate.CLOSING_WORK_INDEX, work.custodianId, expectedWorkRevision)),
            emptyList(), listOf(CashSessionChanged(after)))
    }

    private fun closingValue(session: CashSession, work: ClosingWorkReadSet,
        expectedWorkRevision: Revision): CashSession {
        requireClosingWork(session, work, expectedWorkRevision)
        val cut = CashClosingCut(session.cashLedgerRevision, session.expectedCash, session.totals.externalCollections, session.totals.externalRefunds)
        return session.update(status = CashSessionStatus.CLOSING, closingCut = cut)
    }

    private fun requireClosingWork(session: CashSession, work: ClosingWorkReadSet,
        expectedWorkRevision: Revision) {
        requireRevision(work.revision, expectedWorkRevision)
        requireSales(work.scope == session.scope && work.custodianId == session.custodianId, SalesRule.WRONG_BRANCH)
        requireSales(session.pendingAttemptIds.isEmpty() && session.reservedCashOut.minor == 0L, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(session.pendingTransferIds.isEmpty(), SalesRule.CUSTODY_UNRESOLVED)
        requireSales(work.ownedUnsettledOrderIds.isEmpty() && work.otherUnresolvedFinancialIds.isEmpty(), SalesRule.CLOSING_BLOCKED)
    }

    /** Original BEFORE-value validation only; no command, AFTER image or effects are created. */
    public fun requireHistoricalBeginClosingBefore(before: CashSession, actorId: DomainId,
        expectedRevision: Revision, work: ClosingWorkReadSet, expectedWorkRevision: Revision): Unit {
        requireRevision(before.revision, expectedRevision)
        requireSessionOwner(before, actorId)
        requireClosingWork(before, work, expectedWorkRevision)
    }

    /** Inert literal comparison only. It neither restores authorization nor runs a command. */
    public fun requireHistoricalBeginClosing(before: CashSession, after: CashSession, actorId: DomainId,
        expectedRevision: Revision, work: ClosingWorkReadSet, expectedWorkRevision: Revision): Unit {
        requireRevision(before.revision, expectedRevision)
        requireSessionOwner(before, actorId)
        requireCashHistoryImage(after, closingValue(before, work, expectedWorkRevision))
    }

    public fun requireHistoricalCancelClosing(before: CashSession, after: CashSession, actorId: DomainId,
        expectedRevision: Revision): Unit {
        requireHistoricalCancelClosingBefore(before, actorId, expectedRevision)
        requireCashHistoryImage(after, before.update(status = CashSessionStatus.OPEN, closingCut = null))
    }

    public fun requireHistoricalCancelClosingBefore(before: CashSession, actorId: DomainId,
        expectedRevision: Revision): Unit {
        requireRevision(before.revision, expectedRevision)
        requireCancelledClosing(before, actorId)
    }

    private fun requireCancelledClosing(session: CashSession, actorId: DomainId) {
        requireSales(actorId == session.custodianId, SalesRule.OWNER_REQUIRED)
        requireSales(session.status == CashSessionStatus.CLOSING, SalesRule.IMMUTABLE_RECORD)
    }

    public fun cancelClosing(command: SalesCommand, session: CashSession, expectedRevision: Revision): SalesTransition<CashSession> {
        requireRevision(session.revision, expectedRevision)
        val actor = authorize(command, "cash.close", session.scope, session.id, expectedRevision)
        requireCancelledClosing(session, actor)
        return cashTransition(command, session, session.update(status = CashSessionStatus.OPEN, closingCut = null))
    }

    public fun submitClose(
        command: SalesCommand,
        session: CashSession,
        expectedRevision: Revision,
        assignments: CashAssignmentReadSet,
        count: CountedCash,
        retainedFloat: Money,
        safeTransferId: DomainId?,
        receivingCustodyId: DomainId?,
        varianceReason: String?,
        actualDeliveryEvidence: CashierEvidence? = null,
    ): SalesTransition<ClosedCash> {
        requireRevision(session.revision, expectedRevision)
        val actor = authorize(command, "cash.close", session.scope, session.id, expectedRevision, count.amount)
        requireSales(actor == session.custodianId, SalesRule.OWNER_REQUIRED)
        requireSales(session.status == CashSessionStatus.CLOSING, SalesRule.IMMUTABLE_RECORD)
        requireAssignment(session, assignments)
        count.evidence.requireActor(actor)
        sameCurrency(count.amount, retainedFloat, session.expectedCash)
        retainedFloat.requireNonnegative()
        requireSales(retainedFloat.minor <= count.amount.minor, SalesRule.INVALID_AMOUNT)
        val cut = checkNotNull(session.closingCut)
        val variance = count.amount - cut.expected
        if (variance.minor != 0L) requireReason(varianceReason ?: "")
        varianceReason?.let(::requireReason)
        val delivery = count.amount - retainedFloat
        requireSales((delivery.minor > 0L) == (safeTransferId != null && receivingCustodyId != null), SalesRule.INVALID_STATE)
        if (delivery.minor == 0L) requireSales(safeTransferId == null && receivingCustodyId == null, SalesRule.INVALID_STATE)
        val report = CashCloseReport(session.id, cut, count, variance, retainedFloat, delivery, safeTransferId, varianceReason, actor, command.operationId)
        val after = session.update(status = CashSessionStatus.SUBMITTED_CLOSED, closeReport = report)
        val transfer = if (delivery.minor == 0L) null else {
            requireSales(receivingCustodyId != session.id && receivingCustodyId != session.drawerId, SalesRule.INVALID_STATE)
            val delivered = actualDeliveryEvidence ?: throw SalesRuleViolation(SalesRule.INVALID_EVIDENCE)
            delivered.requireActor(actor)
            SafeTransfer(checkNotNull(safeTransferId), session.scope, Revision(0), session.id, session.drawerId, actor,
                checkNotNull(receivingCustodyId), delivery, delivered, true, SafeTransferStatus.AWAITING_RECEIPT)
        }
        // Closing disposition is based on ACTUAL count. It is not inserted in the frozen ledger.
        val effects: List<SalesEffect> = listOf(CashSessionChanged(after), CashAssignmentReleased(session.drawerId, actor, session.id, transfer?.id)) +
            if (transfer == null) emptyList() else listOf(SafeTransferChanged(transfer))
        return SalesTransition(command, ClosedCash(after, transfer),
            listOf(cashVersion(session), SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, session.drawerId, assignments.drawerRevision),
                SalesVersionCheck(SalesAggregate.CUSTODIAN_ASSIGNMENT, actor, assignments.custodianRevision)),
            if (transfer == null) emptyList() else listOf(SalesUniqueCheck(SalesUniqueKind.SAFE_TRANSFER, transfer.id)), effects)
    }

    public fun review(
        command: SalesCommand,
        session: CashSession,
        expectedRevision: Revision,
        evidence: CashierEvidence,
        reason: String,
    ): SalesTransition<CashSession> {
        requireRevision(session.revision, expectedRevision)
        val actor = authorize(command, "cash.review", session.scope, session.id, expectedRevision)
        val after = reviewedValue(session, actor, evidence, reason)
        return cashTransition(command, session, after)
    }

    private fun reviewedValue(session: CashSession, actor: DomainId, evidence: CashierEvidence,
        reason: String): CashSession {
        requireReview(session, actor, evidence, reason)
        // Named self-review workflows are not part of this conservative bounded path.
        return session.update(status = CashSessionStatus.REVIEWED, review = CashReview(actor, evidence, reason, checkNotNull(session.closeReport)))
    }

    private fun requireReview(session: CashSession, actor: DomainId, evidence: CashierEvidence,
        reason: String) {
        requireSales(actor != session.custodianId, SalesRule.OWNER_REQUIRED)
        evidence.requireActor(actor)
        requireReason(reason)
        requireSales(session.status == CashSessionStatus.SUBMITTED_CLOSED, SalesRule.IMMUTABLE_RECORD)
    }

    public fun requireHistoricalReviewBefore(before: CashSession, actorId: DomainId,
        expectedRevision: Revision, evidence: CashierEvidence, reason: String): Unit {
        requireRevision(before.revision, expectedRevision)
        requireReview(before, actorId, evidence, reason)
    }

    public fun requireHistoricalReview(before: CashSession, after: CashSession, actorId: DomainId,
        expectedRevision: Revision, evidence: CashierEvidence, reason: String): Unit {
        requireRevision(before.revision, expectedRevision)
        requireCashHistoryImage(after, reviewedValue(before, actorId, evidence, reason))
    }

    public fun receiveSafeTransfer(
        command: SalesCommand,
        transfer: SafeTransfer,
        expectedTransferRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        receivingCustody: SafeCustodyReadSet,
        evidence: CashierEvidence,
        closingDrawerReadSet: CashAssignmentReadSet? = null,
        countedClosing: CashClosingCount? = null,
    ): SalesTransition<CashWithTransfer> {
        requireRevision(transfer.revision, expectedTransferRevision)
        requireRevision(session.revision, expectedSessionRevision)
        val actor = authorize(command, "cash.receive", transfer.scope, transfer.id, expectedTransferRevision, transfer.amount)
        val value = receivedSafeValue(transfer, session, receivingCustody, actor, evidence,
            closingDrawerReadSet, countedClosing)
        val versions = mutableListOf(cashVersion(session), SalesVersionCheck(SalesAggregate.SAFE_TRANSFER, transfer.id, expectedTransferRevision),
            SalesVersionCheck(SalesAggregate.SAFE_CUSTODY, receivingCustody.custodyId, receivingCustody.revision))
        val effects = mutableListOf<SalesEffect>(SafeTransferChanged(value.transfer),
            SafeCustodyReceipt(transfer.id, transfer.receivingCustodyId, transfer.amount, evidence))
        if (transfer.closingDisposition) {
            val drawer = checkNotNull(closingDrawerReadSet)
            versions += SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, drawer.drawerId, drawer.drawerRevision)
            if (session.status != CashSessionStatus.CLOSING) effects += ClosingCustodyReleased(drawer.drawerId, transfer.id)
        } else effects += CashSessionChanged(value.session)
        return SalesTransition(command, value, versions, emptyList(), effects)
    }

    private fun receivedSafeValue(transfer: SafeTransfer, session: CashSession,
        receivingCustody: SafeCustodyReadSet, actor: DomainId, evidence: CashierEvidence,
        closingDrawerReadSet: CashAssignmentReadSet?, countedClosing: CashClosingCount?): CashWithTransfer {
        requireSafeReceiptActor(transfer, session, receivingCustody, actor, evidence)
        val afterTransfer = SafeTransfer(transfer.id, transfer.scope, transfer.revision.next(), transfer.senderSessionId,
            transfer.senderDrawerId, transfer.senderCustodianId, transfer.receivingCustodyId, transfer.amount,
            transfer.sentEvidence, transfer.closingDisposition, SafeTransferStatus.RECEIVED, evidence)
        requireSafeReceiptDisposition(transfer, session, closingDrawerReadSet, countedClosing)
        // Counted receipt keeps the original cut and assignments until explicit submission.
        val afterSession = if (transfer.closingDisposition) session
            else session.update(pendingTransferIds = session.pendingTransferIds - transfer.id)
        return CashWithTransfer(afterSession, afterTransfer)
    }

    private fun requireSafeReceiptActor(transfer: SafeTransfer, session: CashSession,
        receivingCustody: SafeCustodyReadSet, actor: DomainId, evidence: CashierEvidence) {
        evidence.requireActor(actor)
        requireSales(actor != transfer.senderCustodianId, SalesRule.OWNER_REQUIRED)
        requireSales(receivingCustody.scope == transfer.scope && receivingCustody.custodyId == transfer.receivingCustodyId,
            SalesRule.WRONG_BRANCH)
        requireSales(receivingCustody.custodianId == actor, SalesRule.OWNER_REQUIRED)
        requireSales(transfer.status == SafeTransferStatus.AWAITING_RECEIPT, SalesRule.IMMUTABLE_RECORD)
        requireSales(session.scope == transfer.scope && session.id == transfer.senderSessionId, SalesRule.WRONG_BRANCH)
    }

    private fun requireSafeReceiptDisposition(transfer: SafeTransfer, session: CashSession,
        closingDrawerReadSet: CashAssignmentReadSet?, countedClosing: CashClosingCount?) {
        if (transfer.closingDisposition) {
            val drawer = closingDrawerReadSet ?: throw SalesRuleViolation(SalesRule.CUSTODY_UNRESOLVED)
            if (session.status == CashSessionStatus.CLOSING) {
                CashCountedClosing.requirePendingDisposition(countedClosing ?: throw SalesRuleViolation(SalesRule.CUSTODY_UNRESOLVED), session, transfer)
                requireAssignment(session, drawer)
            } else {
                requireSales(countedClosing == null && session.closeReport?.safeTransferId == transfer.id, SalesRule.CUSTODY_UNRESOLVED)
                requireSales(drawer.scope == transfer.scope && drawer.drawerId == transfer.senderDrawerId &&
                    drawer.activeDrawerSessionId == null && drawer.unresolvedClosingTransferId == transfer.id, SalesRule.CUSTODY_UNRESOLVED)
            }
        } else {
            requireSales(transfer.id in session.pendingTransferIds, SalesRule.CUSTODY_UNRESOLVED)
        }
    }

    public fun requireHistoricalSafeReceiptBefore(beforeTransfer: SafeTransfer, before: CashSession,
        actorId: DomainId, expectedTransferRevision: Revision, expectedSessionRevision: Revision,
        receivingCustody: SafeCustodyReadSet, evidence: CashierEvidence,
        closingDrawerReadSet: CashAssignmentReadSet?, countedClosing: CashClosingCount?): Unit {
        requireRevision(beforeTransfer.revision, expectedTransferRevision)
        requireRevision(before.revision, expectedSessionRevision)
        requireSafeReceiptActor(beforeTransfer, before, receivingCustody, actorId, evidence)
        // Production advances this immutable transfer before checking its closing disposition.
        // Overflow remains uncertainty, never a caught business refusal.
        beforeTransfer.revision.next()
        requireSafeReceiptDisposition(beforeTransfer, before, closingDrawerReadSet, countedClosing)
    }

    /** Requires the complete original receipt images; cannot create a command or receipt. */
    public fun requireHistoricalSafeReceipt(beforeTransfer: SafeTransfer, afterTransfer: SafeTransfer,
        before: CashSession, after: CashSession, actorId: DomainId, expectedTransferRevision: Revision,
        expectedSessionRevision: Revision, receivingCustody: SafeCustodyReadSet, evidence: CashierEvidence,
        closingDrawerReadSet: CashAssignmentReadSet?, countedClosing: CashClosingCount?): Unit {
        requireRevision(beforeTransfer.revision, expectedTransferRevision)
        requireRevision(before.revision, expectedSessionRevision)
        val expected = receivedSafeValue(beforeTransfer, before, receivingCustody, actorId, evidence,
            closingDrawerReadSet, countedClosing)
        requireCashHistoryImage(after, expected.session)
        requireSafeTransferHistoryImage(afterTransfer, expected.transfer)
    }
}

internal fun requireCashActor(
    command: SalesCommand, action: String, session: CashSession, expectedRevision: Revision, amount: Money? = null,
): DomainId {
    requireRevision(session.revision, expectedRevision)
    val actor = authorize(command, action, session.scope, session.id, expectedRevision, amount)
    requireSales(actor == session.custodianId, SalesRule.OWNER_REQUIRED)
    requireSales(session.status == CashSessionStatus.OPEN, SalesRule.INVALID_STATE)
    return actor
}

internal fun requireSessionOwner(session: CashSession, actor: DomainId) {
    requireSales(session.custodianId == actor, SalesRule.OWNER_REQUIRED)
    requireSales(session.status == CashSessionStatus.OPEN, SalesRule.INVALID_STATE)
}

internal fun cashVersion(session: CashSession): SalesVersionCheck = SalesVersionCheck(SalesAggregate.CASH_SESSION, session.id, session.revision)

internal fun cashTransition(
    command: SalesCommand,
    before: CashSession,
    after: CashSession,
    extraEffects: List<SalesEffect> = emptyList(),
    unique: List<SalesUniqueCheck> = emptyList(),
): SalesTransition<CashSession> = SalesTransition(command, after, listOf(cashVersion(before)), unique, listOf(CashSessionChanged(after)) + extraEffects)

private fun requireAssignment(session: CashSession, assignments: CashAssignmentReadSet) {
    requireSales(assignments.scope == session.scope && assignments.drawerId == session.drawerId && assignments.custodianId == session.custodianId, SalesRule.WRONG_BRANCH)
    requireSales(assignments.activeDrawerSessionId == session.id && assignments.activeCustodianSessionId == session.id && assignments.unresolvedClosingTransferId == null, SalesRule.CUSTODY_UNRESOLVED)
}

internal fun CashSession.beginAttempt(attemptId: DomainId, cashOut: Money): CashSession {
    requireSales(status == CashSessionStatus.OPEN, SalesRule.INVALID_STATE)
    sameCurrency(expectedCash, cashOut)
    cashOut.requireNonnegative()
    requireSales(attemptId !in pendingAttemptIds, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
    requireSales(cashOut.minor <= availableCash.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
    return update(pendingAttemptIds = pendingAttemptIds + attemptId, reservedCashOut = reservedCashOut + cashOut)
}

/** Physical mutation helper is internal: public callers cannot manufacture a sale/refund. */
internal fun CashSession.finishAttempt(
    command: SalesCommand,
    attemptId: DomainId,
    cashReserved: Money,
    movement: CashMovement?,
    externalCollection: Money = expectedCash.zero(),
    externalRefund: Money = expectedCash.zero(),
): CashSession {
    return finishRecordedAttempt(command.operationId, attemptId, cashReserved, movement, externalCollection, externalRefund)
}

/** Exact pure accounting value shared with retained FOOD comparison; not an authorization. */
internal fun CashSession.finishRecordedAttempt(
    applicationOperationId: OperationId,
    attemptId: DomainId,
    cashReserved: Money,
    movement: CashMovement?,
    externalCollection: Money = expectedCash.zero(),
    externalRefund: Money = expectedCash.zero(),
): CashSession {
    requireSales(status == CashSessionStatus.OPEN && attemptId in pendingAttemptIds, SalesRule.PAYMENT_UNRESOLVED)
    sameCurrency(expectedCash, cashReserved, externalCollection, externalRefund)
    requireSales(cashReserved.minor >= 0 && cashReserved.minor <= reservedCashOut.minor, SalesRule.INVALID_AMOUNT)
    val releasedReserve = reservedCashOut - cashReserved
    var updatedTotals = totals.copy(externalCollections = totals.externalCollections + externalCollection,
        externalRefunds = totals.externalRefunds + externalRefund)
    val afterCash = if (movement == null) expectedCash else {
        requireSales(movement.operationId == applicationOperationId && movement.sessionId == id && movement.custodianId == custodianId, SalesRule.INVALID_EVIDENCE)
        updatedTotals = when (movement.kind) {
            CashMovementKind.CUSTOMER_RECEIPT -> updatedTotals.copy(received = totals.received + movement.cashReceived, change = totals.change + movement.changeGiven)
            CashMovementKind.CUSTOMER_REFUND -> updatedTotals.copy(refunds = totals.refunds - movement.delta)
            else -> throw SalesRuleViolation(SalesRule.INVALID_STATE)
        }
        expectedCash + movement.delta
    }
    requireSales(afterCash.minor >= releasedReserve.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
    return update(totals = updatedTotals, expectedCash = afterCash, reservedCashOut = releasedReserve,
        cashLedgerRevision = if (movement == null) cashLedgerRevision else cashLedgerRevision.next(), pendingAttemptIds = pendingAttemptIds - attemptId)
}

/** Compares every persisted field, including unchanged cash, reservation and report fields. */
internal fun requireCashHistoryImage(actual: CashSession, expected: CashSession) {
    requireSales(SalesSnapshotCodec.encodeCashSession(actual) == SalesSnapshotCodec.encodeCashSession(expected),
        SalesRule.INVALID_STATE)
}

internal fun requireSafeTransferHistoryImage(actual: SafeTransfer, expected: SafeTransfer) {
    requireSales(SalesSnapshotCodec.encodeSafeTransfer(actual) == SalesSnapshotCodec.encodeSafeTransfer(expected),
        SalesRule.INVALID_EVIDENCE)
}

package com.ukkera.pos.sales

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** An immutable observation, not a cash movement, balance correction or submitted report. */
public data class CashClosingCount(
    public val scope: BranchScope,
    public val sessionId: DomainId,
    public val position: Revision,
    public val previousCountId: DomainId?,
    public val replacesCountId: DomainId?,
    public val cycleRevision: Revision,
    public val sessionRevision: Revision,
    public val cut: CashClosingCut,
    public val count: CountedCash,
    public val reason: String?,
    public val operationId: OperationId,
) {
    public val id: DomainId get() = count.evidence.evidenceId
    init {
        requireSales((position == Revision(0)) == (previousCountId == null), SalesRule.INVALID_STATE)
        requireSales(previousCountId != id && (replacesCountId == null || replacesCountId == previousCountId), SalesRule.INVALID_STATE)
        requireSales(cycleRevision < sessionRevision, SalesRule.INVALID_STATE)
        sameCurrency(cut.expected, cut.externalCollections, cut.externalRefunds, count.amount)
        if (replacesCountId != null) requireReason(reason ?: "")
        reason?.let(::requireReason)
    }
}

public data class CashCountRecorded(public val record: CashClosingCount) : SalesEffect
public data class CountedClosing(public val session: CashSession, public val record: CashClosingCount)

/**
 * The separate count -> actual delivery -> recipient acknowledgement -> submit route.
 * Its count/history and delivery binding are read by the adapter on the SAME transaction.
 * No method asks the operator to move cash twice or adjusts the frozen expected balance.
 */
public object CashCountedClosing {
    public fun record(
        command: SalesCommand, session: CashSession, expectedRevision: Revision,
        latest: CashClosingCount?, expectedPreviousCountId: DomainId?, count: CountedCash, reason: String?,
    ): SalesTransition<CountedClosing> {
        val actor = closingActor(command, session, expectedRevision, count.amount)
        val value = recordedValue(session, actor, command.operationId, latest, expectedPreviousCountId, count, reason)
        val after = value.session
        val recorded = value.record
        return SalesTransition(command, CountedClosing(after, recorded), listOf(cashVersion(session)), emptyList(),
            listOf(CashSessionChanged(after), CashCountRecorded(recorded)))
    }

    private fun recordedValue(session: CashSession, actor: DomainId, operationId: OperationId,
        latest: CashClosingCount?, expectedPreviousCountId: DomainId?, count: CountedCash, reason: String?): CountedClosing {
        val previous = requireRecordValue(session, actor, latest, expectedPreviousCountId, count, reason)
        val cut = checkNotNull(session.closingCut)
        val after = session.update()
        val recorded = CashClosingCount(session.scope, session.id, latest?.position?.next() ?: Revision(0),
            latest?.id, previous?.id, previous?.cycleRevision ?: session.revision, after.revision, cut, count, reason, operationId)
        return CountedClosing(after, recorded)
    }

    private fun requireRecordValue(session: CashSession, actor: DomainId, latest: CashClosingCount?,
        expectedPreviousCountId: DomainId?, count: CountedCash, reason: String?): CashClosingCount? {
        count.evidence.requireActor(actor)
        val cut = checkNotNull(session.closingCut)
        sameCurrency(count.amount, cut.expected)
        latest?.let { requireCountSession(it, session) }
        val previous = latest?.takeIf { it.sessionRevision == session.revision }
        requireSales(previous?.id == expectedPreviousCountId, SalesRule.REVISION_CONFLICT)
        if (previous != null) requireSales(previous.cut == cut, SalesRule.INVALID_STATE)
        requireSales(latest?.id != count.evidence.evidenceId, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (previous != null) requireReason(reason ?: "")
        reason?.let(::requireReason)
        return previous
    }

    /** Pure original BEFORE validation; a refused count has no session/record AFTER to compare. */
    public fun requireHistoricalRecordBefore(before: CashSession, actorId: DomainId, expectedRevision: Revision,
        latest: CashClosingCount?, expectedPreviousCountId: DomainId?, count: CountedCash, reason: String?): Unit {
        historicalClosingActor(before, actorId, expectedRevision)
        requireRecordValue(before, actorId, latest, expectedPreviousCountId, count, reason)
    }

    /** Full original state comparison; no SalesCommand or current authority can be minted here. */
    public fun requireHistoricalRecord(before: CashSession, after: CashSession, actual: CashClosingCount,
        actorId: DomainId, operationId: OperationId, expectedRevision: Revision, latest: CashClosingCount?,
        expectedPreviousCountId: DomainId?, count: CountedCash, reason: String?): Unit {
        historicalClosingActor(before, actorId, expectedRevision)
        val expected = recordedValue(before, actorId, operationId, latest, expectedPreviousCountId, count, reason)
        requireCashHistoryImage(after, expected.session)
        requireSales(actual == expected.record, SalesRule.INVALID_EVIDENCE)
    }

    public fun deliver(
        command: SalesCommand, session: CashSession, expectedRevision: Revision, count: CashClosingCount,
        retainedFloat: Money, transferId: DomainId, receivingCustodyId: DomainId, evidence: CashierEvidence,
    ): SalesTransition<CashWithTransfer> {
        requireRevision(session.revision, expectedRevision)
        val amount = deliveryAmount(session, count, retainedFloat)
        val actor = authorize(command, "cash.transfer", session.scope, session.id, expectedRevision, amount)
        val value = deliveredValue(session, actor, amount, transferId, receivingCustodyId, evidence)
        return SalesTransition(command, value, listOf(cashVersion(session)),
            listOf(SalesUniqueCheck(SalesUniqueKind.SAFE_TRANSFER, transferId)),
            listOf(CashSessionChanged(value.session), SafeTransferChanged(value.transfer)))
    }

    private fun deliveryAmount(session: CashSession, count: CashClosingCount, retainedFloat: Money): Money {
        requireSales(session.status == CashSessionStatus.CLOSING, SalesRule.IMMUTABLE_RECORD)
        requireCurrentCount(count, session)
        sameCurrency(count.count.amount, retainedFloat)
        retainedFloat.requireNonnegative()
        return (count.count.amount - retainedFloat).requirePositive()
    }

    private fun deliveredValue(session: CashSession, actor: DomainId, amount: Money,
        transferId: DomainId, receivingCustodyId: DomainId, evidence: CashierEvidence): CashWithTransfer {
        requireDeliveryValue(session, actor, receivingCustodyId, evidence)
        val transfer = SafeTransfer(transferId, session.scope, Revision(0), session.id, session.drawerId,
            session.custodianId, receivingCustodyId, amount, evidence, true, SafeTransferStatus.AWAITING_RECEIPT)
        // Disposition is actual counted custody, not another safe drop in the frozen ledger.
        return CashWithTransfer(session.update(), transfer)
    }

    private fun requireDeliveryValue(session: CashSession, actor: DomainId,
        receivingCustodyId: DomainId, evidence: CashierEvidence) {
        requireSales(actor == session.custodianId, SalesRule.OWNER_REQUIRED)
        evidence.requireActor(actor)
        requireSales(receivingCustodyId != session.id && receivingCustodyId != session.drawerId, SalesRule.INVALID_STATE)
    }

    public fun requireHistoricalDeliveryBefore(before: CashSession, actorId: DomainId, expectedRevision: Revision,
        count: CashClosingCount, retainedFloat: Money, receivingCustodyId: DomainId, evidence: CashierEvidence): Unit {
        requireRevision(before.revision, expectedRevision)
        deliveryAmount(before, count, retainedFloat)
        requireDeliveryValue(before, actorId, receivingCustodyId, evidence)
    }

    /** Literal native history comparison, not permission to deliver cash again. */
    public fun requireHistoricalDelivery(before: CashSession, after: CashSession, transfer: SafeTransfer,
        actorId: DomainId, expectedRevision: Revision, count: CashClosingCount, retainedFloat: Money,
        transferId: DomainId, receivingCustodyId: DomainId, evidence: CashierEvidence): Unit {
        requireRevision(before.revision, expectedRevision)
        val amount = deliveryAmount(before, count, retainedFloat)
        val expected = deliveredValue(before, actorId, amount, transferId, receivingCustodyId, evidence)
        requireCashHistoryImage(after, expected.session)
        requireSafeTransferHistoryImage(transfer, expected.transfer)
    }

    /** Allows only this count's separate closing transfer while assignments are still held. */
    public fun requirePendingDisposition(count: CashClosingCount, session: CashSession, transfer: SafeTransfer) {
        requireCountSession(count, session)
        requireSales(session.status == CashSessionStatus.CLOSING && session.closingCut == count.cut &&
            session.revision == count.sessionRevision.next() && transfer.closingDisposition &&
            transfer.scope == session.scope && transfer.senderSessionId == session.id &&
            transfer.senderDrawerId == session.drawerId && transfer.senderCustodianId == session.custodianId &&
            transfer.sentEvidence.actorId == session.custodianId && transfer.amount.currency == count.count.amount.currency &&
            transfer.amount.minor in 1..count.count.amount.minor, SalesRule.CUSTODY_UNRESOLVED)
    }

    public fun submit(
        command: SalesCommand, session: CashSession, expectedRevision: Revision, assignments: CashAssignmentReadSet,
        count: CashClosingCount, retainedFloat: Money, transfer: SafeTransfer?, varianceReason: String?,
    ): SalesTransition<CashSession> {
        val actor = closingActor(command, session, expectedRevision, count.count.amount)
        val after = submittedValue(session, actor, command.operationId, assignments, count, retainedFloat, transfer, varianceReason)
        return SalesTransition(command, after, listOf(cashVersion(session),
            SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, session.drawerId, assignments.drawerRevision),
            SalesVersionCheck(SalesAggregate.CUSTODIAN_ASSIGNMENT, actor, assignments.custodianRevision)) +
            listOfNotNull(transfer?.let { SalesVersionCheck(SalesAggregate.SAFE_TRANSFER, it.id, it.revision) }), emptyList(),
            listOf(CashSessionChanged(after), CashAssignmentReleased(session.drawerId, actor, session.id, null)))
    }

    private fun submittedValue(session: CashSession, actor: DomainId, operationId: OperationId,
        assignments: CashAssignmentReadSet, count: CashClosingCount, retainedFloat: Money,
        transfer: SafeTransfer?, varianceReason: String?): CashSession {
        val delivered = requireSubmitValue(session, actor, assignments, count, retainedFloat, transfer, varianceReason)
        val report = CashCloseReport(session.id, count.cut, count.count, count.count.amount - count.cut.expected,
            retainedFloat, delivered, transfer?.id, varianceReason, actor, operationId)
        return session.update(status = CashSessionStatus.SUBMITTED_CLOSED, closeReport = report)
    }

    private fun requireSubmitValue(session: CashSession, actor: DomainId, assignments: CashAssignmentReadSet,
        count: CashClosingCount, retainedFloat: Money, transfer: SafeTransfer?, varianceReason: String?): Money {
        requireCountSession(count, session)
        requireSales(assignments.scope == session.scope && assignments.drawerId == session.drawerId &&
            assignments.custodianId == actor && assignments.activeDrawerSessionId == session.id &&
            assignments.activeCustodianSessionId == session.id && assignments.unresolvedClosingTransferId == null,
            SalesRule.CUSTODY_UNRESOLVED)
        sameCurrency(count.count.amount, retainedFloat)
        retainedFloat.requireNonnegative()
        val delivered = count.count.amount - retainedFloat
        if (transfer == null) {
            requireCurrentCount(count, session)
            requireSales(delivered.minor == 0L, SalesRule.CUSTODY_UNRESOLVED)
        } else {
            requirePendingDisposition(count, session, transfer)
            requireSales(transfer.status == SafeTransferStatus.RECEIVED && transfer.amount == delivered &&
                transfer.receipt != null && transfer.receipt.actorId != actor, SalesRule.CUSTODY_UNRESOLVED)
        }
        val variance = count.count.amount - count.cut.expected
        if (variance.minor != 0L) requireReason(varianceReason ?: "")
        varianceReason?.let(::requireReason)
        return delivered
    }

    public fun requireHistoricalSubmitBefore(before: CashSession, actorId: DomainId, expectedRevision: Revision,
        assignments: CashAssignmentReadSet, count: CashClosingCount, retainedFloat: Money,
        transfer: SafeTransfer?, varianceReason: String?): Unit {
        historicalClosingActor(before, actorId, expectedRevision)
        requireSubmitValue(before, actorId, assignments, count, retainedFloat, transfer, varianceReason)
    }

    public fun requireHistoricalSubmit(before: CashSession, after: CashSession, actorId: DomainId,
        operationId: OperationId, expectedRevision: Revision, assignments: CashAssignmentReadSet,
        count: CashClosingCount, retainedFloat: Money, transfer: SafeTransfer?, varianceReason: String?): Unit {
        historicalClosingActor(before, actorId, expectedRevision)
        requireCashHistoryImage(after, submittedValue(before, actorId, operationId, assignments, count,
            retainedFloat, transfer, varianceReason))
    }

    private fun historicalClosingActor(session: CashSession, actorId: DomainId, expected: Revision) {
        requireRevision(session.revision, expected)
        requireSales(actorId == session.custodianId, SalesRule.OWNER_REQUIRED)
        requireSales(session.status == CashSessionStatus.CLOSING, SalesRule.IMMUTABLE_RECORD)
    }

    public fun requireCountSession(count: CashClosingCount, session: CashSession) {
        requireSales(count.scope == session.scope && count.sessionId == session.id &&
            count.count.evidence.actorId == session.custodianId && count.sessionRevision <= session.revision &&
            count.count.amount.currency == session.expectedCash.currency, SalesRule.INVALID_EVIDENCE)
    }

    private fun requireCurrentCount(count: CashClosingCount, session: CashSession) {
        requireCountSession(count, session)
        requireSales(count.sessionRevision == session.revision && count.cut == session.closingCut, SalesRule.REVISION_CONFLICT)
    }

    private fun closingActor(command: SalesCommand, session: CashSession, expected: Revision, amount: Money): DomainId {
        requireRevision(session.revision, expected)
        val actor = authorize(command, "cash.close", session.scope, session.id, expected, amount)
        requireSales(actor == session.custodianId, SalesRule.OWNER_REQUIRED)
        requireSales(session.status == CashSessionStatus.CLOSING, SalesRule.IMMUTABLE_RECORD)
        return actor
    }
}

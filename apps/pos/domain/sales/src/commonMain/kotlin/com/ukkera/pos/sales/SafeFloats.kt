package com.ukkera.pos.sales

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/**
 * Safe-to-drawer float issue (opening top-up or replenishment) as an explicit custody
 * transfer. The safe custodian issues counted cash to an OPEN drawer session; it is not
 * drawer cash until the session custodian acknowledges the exact counted amount. An
 * unacknowledged issue stays a visible safe custody exception until received or recalled.
 *
 * Never revenue, never a customer receipt, and never a mutation of the original safe
 * receipts or drop transfers.
 */
public enum class SafeFloatStatus { AWAITING_RECEIPT, RECEIVED, RECALLED }

public class SafeFloat internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val revision: Revision,
    public val safeId: DomainId,
    public val safeCustodianId: DomainId,
    public val sessionId: DomainId,
    public val drawerId: DomainId,
    public val receivingCustodianId: DomainId,
    public val amount: Money,
    public val issueEvidence: CashierEvidence,
    public val status: SafeFloatStatus,
    public val receipt: CashierEvidence? = null,
    public val recall: CashierEvidence? = null,
    public val recallReason: String? = null,
) {
    init {
        amount.requirePositive()
        requireSales(safeCustodianId != receivingCustodianId, SalesRule.OWNER_REQUIRED)
        requireSales(issueEvidence.actorId == safeCustodianId, SalesRule.INVALID_EVIDENCE)
        when (status) {
            SafeFloatStatus.AWAITING_RECEIPT -> requireSales(receipt == null && recall == null && recallReason == null, SalesRule.INVALID_STATE)
            SafeFloatStatus.RECEIVED -> requireSales(receipt?.actorId == receivingCustodianId && recall == null && recallReason == null, SalesRule.INVALID_STATE)
            SafeFloatStatus.RECALLED -> requireSales(receipt == null && recall?.actorId == safeCustodianId && recallReason != null, SalesRule.INVALID_STATE)
        }
        recallReason?.let(::requireReason)
    }

    /** Persistence boundary only: every invariant above is re-validated. */
    public companion object {
        public fun restore(
            id: DomainId, scope: BranchScope, revision: Revision, safeId: DomainId, safeCustodianId: DomainId,
            sessionId: DomainId, drawerId: DomainId, receivingCustodianId: DomainId, amount: Money,
            issueEvidence: CashierEvidence, status: SafeFloatStatus, receipt: CashierEvidence?,
            recall: CashierEvidence?, recallReason: String?,
        ): SafeFloat = SafeFloat(id, scope, revision, safeId, safeCustodianId, sessionId, drawerId, receivingCustodianId,
            amount, issueEvidence, status, receipt, recall, recallReason)
    }
}

/**
 * Per-safe float ledger read in the master transaction. Safe receipts stay in the safe's own
 * row; available safe cash = received - issued + recalled. Revision 0 with zero totals is the
 * not-yet-stored ledger.
 */
public data class SafeFloatLedger(
    public val scope: BranchScope,
    public val safeId: DomainId,
    public val revision: Revision,
    public val issued: Money,
    public val recalled: Money,
) {
    init {
        sameCurrency(issued, recalled)
        issued.requireNonnegative()
        recalled.requireNonnegative()
        requireSales(recalled.minor <= issued.minor, SalesRule.INVALID_AMOUNT)
    }

    public fun available(safeReceived: Money): Money = safeReceived - issued + recalled
    public val outstanding: Money get() = issued - recalled
}

public data class SafeFloatIssued(public val float: SafeFloat, public val ledger: SafeFloatLedger) : SalesEffect
public data class SafeFloatChanged(public val float: SafeFloat) : SalesEffect
public data class SafeFloatLedgerChanged(public val ledger: SafeFloatLedger) : SalesEffect

public data class SafeFloatWithLedger(public val float: SafeFloat, public val ledger: SafeFloatLedger)
public data class SafeFloatWithSession(public val float: SafeFloat, public val session: CashSession)

public object SafeFloats {
    /** The safe custodian issues counted cash to another employee's OPEN session. */
    public fun issue(
        command: SalesCommand,
        safe: SafeCustodyReadSet,
        safeReceived: Money,
        ledger: SafeFloatLedger,
        expectedLedgerRevision: Revision,
        /** The receiving session's CURRENT row in the master transaction; it is not modified. */
        session: CashSession,
        floatId: DomainId,
        amount: Money,
        evidence: CashierEvidence,
    ): SalesTransition<SafeFloatWithLedger> {
        requireRevision(ledger.revision, expectedLedgerRevision)
        val actor = authorize(command, "cash.transfer", safe.scope, safe.custodyId, expectedLedgerRevision, amount.requirePositive())
        requireSales(actor == safe.custodianId, SalesRule.OWNER_REQUIRED)
        evidence.requireActor(actor)
        requireSales(ledger.scope == safe.scope && ledger.safeId == safe.custodyId && session.scope == safe.scope, SalesRule.WRONG_BRANCH)
        requireSales(session.status == CashSessionStatus.OPEN, SalesRule.INVALID_STATE)
        requireSales(session.custodianId != actor, SalesRule.OWNER_REQUIRED)
        requireSales(floatId != session.id && floatId != safe.custodyId && floatId != session.drawerId, SalesRule.INVALID_STATE)
        sameCurrency(safeReceived, ledger.issued, amount, session.expectedCash)
        requireSales(ledger.available(safeReceived).minor >= amount.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
        val float = SafeFloat(floatId, safe.scope, Revision(0), safe.custodyId, actor, session.id, session.drawerId,
            session.custodianId, amount, evidence, SafeFloatStatus.AWAITING_RECEIPT)
        val after = ledger.copy(revision = ledger.revision.next(), issued = ledger.issued + amount)
        return SalesTransition(command, SafeFloatWithLedger(float, after),
            listOf(SalesVersionCheck(SalesAggregate.SAFE_CUSTODY, safe.custodyId, safe.revision),
                SalesVersionCheck(SalesAggregate.SAFE_FLOAT_LEDGER, safe.custodyId, expectedLedgerRevision),
                cashVersion(session)),
            listOf(SalesUniqueCheck(SalesUniqueKind.SAFE_FLOAT, floatId), SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, floatId)),
            listOf(SafeFloatIssued(float, after)))
    }

    /** The receiving custodian counts the float into the drawer; an unequal count is refused. */
    public fun receive(
        command: SalesCommand,
        float: SafeFloat,
        expectedFloatRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        counted: CountedCash,
    ): SalesTransition<SafeFloatWithSession> {
        requireRevision(float.revision, expectedFloatRevision)
        requireRevision(session.revision, expectedSessionRevision)
        val actor = authorize(command, "cash.receive", float.scope, float.id, expectedFloatRevision, float.amount)
        val value = receivedValue(float, session, actor, counted)
        return SalesTransition(command, value,
            listOf(SalesVersionCheck(SalesAggregate.SAFE_FLOAT, float.id, expectedFloatRevision), cashVersion(session)),
            emptyList(),
            listOf(movementOf(command, value.session, float, counted), CashSessionChanged(value.session), SafeFloatChanged(value.float)))
    }

    private fun receivedValue(float: SafeFloat, session: CashSession, actor: DomainId,
        counted: CountedCash): SafeFloatWithSession {
        requireSales(float.status == SafeFloatStatus.AWAITING_RECEIPT, SalesRule.IMMUTABLE_RECORD)
        requireSales(actor == float.receivingCustodianId && actor == session.custodianId, SalesRule.OWNER_REQUIRED)
        requireSales(session.scope == float.scope && session.id == float.sessionId && session.drawerId == float.drawerId, SalesRule.WRONG_BRANCH)
        requireSales(session.status == CashSessionStatus.OPEN, SalesRule.INVALID_STATE)
        counted.evidence.requireActor(actor)
        sameCurrency(float.amount, counted.amount, session.expectedCash)
        // A short or over count is a custody discrepancy for the safe custodian, not drawer cash.
        requireSales(counted.amount == float.amount, SalesRule.INVALID_AMOUNT)
        val afterFloat = SafeFloat(float.id, float.scope, float.revision.next(), float.safeId, float.safeCustodianId,
            float.sessionId, float.drawerId, float.receivingCustodianId, float.amount, float.issueEvidence,
            SafeFloatStatus.RECEIVED, counted.evidence)
        val afterSession = session.update(
            totals = session.totals.copy(payIns = session.totals.payIns + float.amount),
            expectedCash = session.expectedCash + float.amount,
            cashLedgerRevision = session.cashLedgerRevision.next(),
        )
        return SafeFloatWithSession(afterFloat, afterSession)
    }

    private fun movementOf(command: SalesCommand, session: CashSession, float: SafeFloat, counted: CountedCash): CashMovement =
        CashMovement(float.id, command.operationId, session.id, session.custodianId, CashMovementKind.SAFE_FLOAT_RECEIPT,
            float.amount, float.amount.zero(), float.amount.zero(), counted.evidence, null)

    /** The safe custodian recalls an unacknowledged float back into safe availability. */
    public fun recall(
        command: SalesCommand,
        float: SafeFloat,
        expectedFloatRevision: Revision,
        ledger: SafeFloatLedger,
        expectedLedgerRevision: Revision,
        evidence: CashierEvidence,
        reason: String,
    ): SalesTransition<SafeFloatWithLedger> {
        requireRevision(float.revision, expectedFloatRevision)
        requireRevision(ledger.revision, expectedLedgerRevision)
        val actor = authorize(command, "cash.transfer", float.scope, float.id, expectedFloatRevision, float.amount)
        requireSales(actor == float.safeCustodianId, SalesRule.OWNER_REQUIRED)
        evidence.requireActor(actor)
        requireReason(reason)
        requireSales(float.status == SafeFloatStatus.AWAITING_RECEIPT, SalesRule.IMMUTABLE_RECORD)
        requireSales(ledger.scope == float.scope && ledger.safeId == float.safeId, SalesRule.WRONG_BRANCH)
        sameCurrency(ledger.issued, float.amount)
        requireSales(ledger.outstanding.minor >= float.amount.minor, SalesRule.INVALID_AMOUNT)
        val afterFloat = SafeFloat(float.id, float.scope, float.revision.next(), float.safeId, float.safeCustodianId,
            float.sessionId, float.drawerId, float.receivingCustodianId, float.amount, float.issueEvidence,
            SafeFloatStatus.RECALLED, null, evidence, reason)
        val afterLedger = ledger.copy(revision = ledger.revision.next(), recalled = ledger.recalled + float.amount)
        return SalesTransition(command, SafeFloatWithLedger(afterFloat, afterLedger),
            listOf(SalesVersionCheck(SalesAggregate.SAFE_FLOAT, float.id, expectedFloatRevision),
                SalesVersionCheck(SalesAggregate.SAFE_FLOAT_LEDGER, float.safeId, expectedLedgerRevision)),
            emptyList(), listOf(SafeFloatChanged(afterFloat), SafeFloatLedgerChanged(afterLedger)))
    }
}

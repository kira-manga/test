package com.ukkera.pos.sales

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/**
 * Receipt-linked refundable container deposits (LF-CAT-11, CAT-10). The terms come from the
 * ORIGINAL signed SKU publication captured with the paid sale, never from the current catalog.
 * Collected deposit cash is a liability in the collecting drawer, never revenue; each refund is an
 * actual cash payout of remaining containers, at most once per container. There is no balance,
 * credit or stored value: the entitlement is redeemable only by returning containers.
 */
public data class DepositTerms(
    public val skuId: DomainId,
    public val publicationId: DomainId,
    public val amountPerContainer: Money,
    public val liabilityMappingId: DomainId,
    public val taxCategoryId: DomainId,
) { init { amountPerContainer.requirePositive() } }

public class DepositEntitlement internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val revision: Revision,
    public val saleId: DomainId,
    public val portionId: DomainId,
    public val terms: DepositTerms,
    public val containers: Long,
    public val returned: Long,
    public val collectorId: DomainId,
    public val sessionId: DomainId,
    public val collectEvidence: CashierEvidence,
) {
    init {
        requireSales(containers in 1..MAX_CONTAINERS && returned in 0..containers, SalesRule.INVALID_AMOUNT)
        requireSales(collectEvidence.actorId == collectorId, SalesRule.INVALID_EVIDENCE)
        amount(terms.amountPerContainer, containers)
    }

    public val remaining: Long get() = containers - returned
    public val collected: Money get() = amount(terms.amountPerContainer, containers)
    public val refunded: Money get() = amount(terms.amountPerContainer, returned)

    public companion object {
        public const val MAX_CONTAINERS: Long = 100_000L

        /** Persistence boundary only: every invariant above is re-validated. */
        public fun restore(id: DomainId, scope: BranchScope, revision: Revision, saleId: DomainId, portionId: DomainId,
            terms: DepositTerms, containers: Long, returned: Long, collectorId: DomainId, sessionId: DomainId,
            collectEvidence: CashierEvidence): DepositEntitlement =
            DepositEntitlement(id, scope, revision, saleId, portionId, terms, containers, returned, collectorId, sessionId, collectEvidence)
    }
}

/** The bound original sold portion, from the master's verified sale and captured publication. */
public data class DepositSaleSource(
    public val scope: BranchScope,
    public val saleId: DomainId,
    public val portionId: DomainId,
    /** Whole containers sold in that portion. */
    public val soldContainers: Long,
    public val terms: DepositTerms,
) { init { requireSales(soldContainers > 0L, SalesRule.INVALID_AMOUNT) } }

public data class DepositWithSession(public val deposit: DepositEntitlement, public val session: CashSession)
public data class DepositChanged(public val deposit: DepositEntitlement) : SalesEffect

public object Deposits {
    /** The OPEN drawer custodian receives the deposit for containers leaving with the customer. */
    public fun collect(
        command: SalesCommand,
        source: DepositSaleSource,
        session: CashSession,
        expectedSessionRevision: Revision,
        depositId: DomainId,
        containers: Long,
        evidence: CashierEvidence,
    ): SalesTransition<DepositWithSession> {
        requireSales(containers in 1..minOf(source.soldContainers, DepositEntitlement.MAX_CONTAINERS), SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val amount = amount(source.terms.amountPerContainer, containers)
        val actor = requireCashActor(command, "cash.move", session, expectedSessionRevision, amount)
        evidence.requireActor(actor)
        requireSales(source.scope == session.scope, SalesRule.WRONG_BRANCH)
        requireSales(depositId != session.id && depositId != source.saleId && depositId != source.portionId, SalesRule.INVALID_STATE)
        sameCurrency(amount, session.expectedCash)
        val deposit = DepositEntitlement(depositId, session.scope, Revision(0), source.saleId, source.portionId, source.terms,
            containers, 0L, actor, session.id, evidence)
        val after = session.update(totals = session.totals.copy(payIns = session.totals.payIns + amount),
            expectedCash = session.expectedCash + amount, cashLedgerRevision = session.cashLedgerRevision.next())
        val movement = CashMovement(depositId, command.operationId, session.id, actor, CashMovementKind.DEPOSIT_COLLECTED,
            amount, amount.zero(), amount.zero(), evidence, null)
        return SalesTransition(command, DepositWithSession(deposit, after), listOf(cashVersion(session)),
            listOf(SalesUniqueCheck(SalesUniqueKind.DEPOSIT, depositId), SalesUniqueCheck(SalesUniqueKind.DEPOSIT_PORTION, source.portionId),
                SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, depositId)),
            listOf(movement, CashSessionChanged(after), DepositChanged(deposit)))
    }

    /** Actual cash refund for physically returned containers, from the refunder's OPEN drawer. */
    public fun refund(
        command: SalesCommand,
        deposit: DepositEntitlement,
        expectedDepositRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        refundId: DomainId,
        containers: Long,
        evidence: CashierEvidence,
    ): SalesTransition<DepositWithSession> {
        requireRevision(deposit.revision, expectedDepositRevision)
        requireRevision(session.revision, expectedSessionRevision)
        requireSales(containers in 1..deposit.remaining, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val amount = amount(deposit.terms.amountPerContainer, containers)
        val actor = authorize(command, "refund.record", deposit.scope, deposit.id, expectedDepositRevision, amount)
        requireSessionOwner(session, actor)
        evidence.requireActor(actor)
        requireSales(session.scope == deposit.scope, SalesRule.WRONG_BRANCH)
        requireSales(refundId != deposit.id && refundId != session.id, SalesRule.INVALID_STATE)
        sameCurrency(amount, session.expectedCash)
        requireSales(session.availableCash.minor >= amount.minor, SalesRule.INSUFFICIENT_DRAWER_CASH)
        val afterDeposit = DepositEntitlement(deposit.id, deposit.scope, deposit.revision.next(), deposit.saleId, deposit.portionId,
            deposit.terms, deposit.containers, deposit.returned + containers, deposit.collectorId, deposit.sessionId, deposit.collectEvidence)
        val after = session.update(totals = session.totals.copy(payOuts = session.totals.payOuts + amount),
            expectedCash = session.expectedCash - amount, cashLedgerRevision = session.cashLedgerRevision.next())
        val movement = CashMovement(refundId, command.operationId, session.id, actor, CashMovementKind.DEPOSIT_REFUNDED,
            -amount, amount.zero(), amount.zero(), evidence, null)
        return SalesTransition(command, DepositWithSession(afterDeposit, after),
            listOf(SalesVersionCheck(SalesAggregate.DEPOSIT_ENTITLEMENT, deposit.id, expectedDepositRevision), cashVersion(session)),
            listOf(SalesUniqueCheck(SalesUniqueKind.DEPOSIT_REFUND, refundId), SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, refundId)),
            listOf(movement, CashSessionChanged(after), DepositChanged(afterDeposit)))
    }
}

private fun amount(per: Money, containers: Long): Money {
    requireSales(containers >= 0L && (containers == 0L || per.minor <= Long.MAX_VALUE / containers), SalesRule.INVALID_AMOUNT)
    return Money(per.currency, per.minor * containers)
}

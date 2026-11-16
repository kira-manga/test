package com.ukkera.pos.sales

import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Money
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Container deposits against the frozen acceptance scenario CAT-10
 * (docs/pos/original-handoff/POS_ACCEPTANCE_SCENARIOS.md). Self-authored, NOT independent.
 */
class DepositsTest {
    private fun source(f: SalesFixture, sold: Long = 3, per: Money = egp(5)) =
        DepositSaleSource(f.scope, f.nextId(), f.nextId(), sold, DepositTerms(f.nextId(), f.nextId(), per, f.nextId(), f.nextId()))

    private suspend fun collect(f: SalesFixture, source: DepositSaleSource, session: CashSession, containers: Long,
        actor: com.ukkera.pos.types.DomainId = session.custodianId): SalesTransition<DepositWithSession> {
        val amount = Money(source.terms.amountPerContainer.currency, source.terms.amountPerContainer.minor * containers)
        return Deposits.collect(f.command(SecurityAction.CASH_MOVE, session.id, session.revision, amount, actor), source, session,
            session.revision, f.nextId(), containers, f.evidence(actor))
    }

    private suspend fun refund(f: SalesFixture, deposit: DepositEntitlement, session: CashSession, containers: Long,
        refundId: com.ukkera.pos.types.DomainId = f.nextId()): SalesTransition<DepositWithSession> {
        val amount = Money(deposit.terms.amountPerContainer.currency, deposit.terms.amountPerContainer.minor * containers)
        return Deposits.refund(f.command(SecurityAction.REFUND_RECORD, deposit.id, deposit.revision, amount, session.custodianId),
            deposit, deposit.revision, session, session.revision, refundId, containers, f.evidence(session.custodianId))
    }

    @Test fun cat10CollectIsSeparateLiabilityCashAndReturnRefundsActualCashOnce() = runTest {
        val f = SalesFixture()
        val drawer = f.openCash(egp(100))
        val collected = collect(f, source(f), drawer, 2)
        val deposit = collected.value.deposit
        assertEquals(egp(10), deposit.collected)
        assertEquals(egp(110), collected.value.session.expectedCash)
        val movement = collected.effects.filterIsInstance<CashMovement>().single()
        assertEquals(CashMovementKind.DEPOSIT_COLLECTED, movement.kind)
        assertEquals(egp(10), movement.delta)
        assertTrue(collected.unique.contains(SalesUniqueCheck(SalesUniqueKind.DEPOSIT_PORTION, deposit.portionId)))

        val returned = refund(f, deposit, collected.value.session, 2)
        assertEquals(0L, returned.value.deposit.remaining)
        assertEquals(egp(10), returned.value.deposit.refunded)
        assertEquals(egp(100), returned.value.session.expectedCash)
        assertEquals(CashMovementKind.DEPOSIT_REFUNDED, returned.effects.filterIsInstance<CashMovement>().single().kind)
        // A retry from another client against the consumed entitlement is refused.
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) { refund(f, returned.value.deposit, returned.value.session, 1) }
        // A stale copy is bound to its old revision, so the master's version check refuses it.
        assertRule(SalesRule.REVISION_CONFLICT) {
            Deposits.refund(f.command(SecurityAction.REFUND_RECORD, deposit.id, deposit.revision, egp(5), drawer.custodianId),
                returned.value.deposit, deposit.revision, returned.value.session, returned.value.session.revision, f.nextId(), 1,
                f.evidence(drawer.custodianId))
        }
        assertTrue(returned.versions.contains(SalesVersionCheck(SalesAggregate.DEPOSIT_ENTITLEMENT, deposit.id, deposit.revision)))
    }

    @Test fun partialReturnsConsumeOnlyTheirContainers() = runTest {
        val f = SalesFixture()
        val drawer = f.openCash(egp(100))
        val deposit = collect(f, source(f, sold = 5), drawer, 3)
        val first = refund(f, deposit.value.deposit, deposit.value.session, 1)
        assertEquals(2L, first.value.deposit.remaining)
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) { refund(f, first.value.deposit, first.value.session, 3) }
        assertEquals(0L, refund(f, first.value.deposit, first.value.session, 2).value.deposit.remaining)
    }

    @Test fun cannotDepositMoreThanSoldOrFromAnotherCustodiansDrawer() = runTest {
        val f = SalesFixture()
        val drawer = f.openCash(egp(100))
        val sold = source(f, sold = 2)
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) { collect(f, sold, drawer, 3) }
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) { collect(f, sold, drawer, 0) }
        assertRule(SalesRule.OWNER_REQUIRED) { collect(f, sold, drawer, 1, actor = f.other) }
    }

    @Test fun refundNeedsActualDrawerCash() = runTest {
        val f = SalesFixture()
        val collector = f.openCash(egp(0))
        val deposit = collect(f, source(f, sold = 1, per = egp(50)), collector, 1).value.deposit
        val empty = f.openCash(egp(10), actor = f.other)
        val amount = egp(50)
        assertRule(SalesRule.INSUFFICIENT_DRAWER_CASH) {
            Deposits.refund(f.command(SecurityAction.REFUND_RECORD, deposit.id, deposit.revision, amount, f.other),
                deposit, deposit.revision, empty, empty.revision, f.nextId(), 1, f.evidence(f.other))
        }
    }
}
